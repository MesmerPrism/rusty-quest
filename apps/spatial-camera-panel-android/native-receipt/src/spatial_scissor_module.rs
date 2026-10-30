//! Optional trusted pure scissor client. Code belongs to the process, never an
//! SDK lifetime. No unload, resource handle, Rust allocator or trait crosses ABI.
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    ffi::{c_void, CStr},
    fs,
    mem::{align_of, size_of},
    path::Path,
    sync::{
        atomic::{AtomicBool, AtomicPtr, AtomicU64, AtomicU8, Ordering},
        Arc, Mutex, OnceLock,
    },
};
const CONTRACT: u64 = 0x5251534349535331;
const MAX_IMAGE: u64 = 4 * 1024 * 1024;
const MAX_BYTES: u64 = 64 * 1024 * 1024;
const MAX_ATTEMPTS: u64 = 64;
const PENDING: u8 = 0;
const CANCELLED: u8 = 1;
const PUBLISHED: u8 = 2;
#[repr(C)]
#[derive(Clone, Copy)]
struct Header {
    version: u32,
    size: u32,
    contract: u64,
    generation: u64,
}
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct Pixels {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}
type Project = unsafe extern "C" fn(u32, u32, f32, f32, f32, f32, *mut Pixels, u32) -> i32;
#[repr(C)]
struct Table {
    header: Header,
    project: Option<Project>,
}
const _: () = {
    assert!(size_of::<Header>() == 24);
    assert!(size_of::<Pixels>() == 16);
    assert!(size_of::<Table>() == 32);
    assert!(std::mem::offset_of!(Table, project) == 24);
};

#[cfg(windows)]
mod image {
    use super::*;
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    extern "system" {
        fn LoadLibraryW(name: *const u16) -> *mut c_void;
        fn GetProcAddress(handle: *mut c_void, name: *const u8) -> *mut c_void;
    }
    pub fn open(path: &Path) -> usize {
        let wide: Vec<_> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        unsafe { LoadLibraryW(wide.as_ptr()) as usize }
    }
    pub fn symbol(handle: usize, name: &CStr) -> *mut c_void {
        unsafe { GetProcAddress(handle as *mut c_void, name.as_ptr().cast()) }
    }
}
#[cfg(unix)]
mod image {
    use super::*;
    use std::{
        ffi::{c_char, CString},
        os::unix::ffi::OsStrExt,
    };
    #[link(name = "dl")]
    extern "C" {
        fn dlopen(name: *const c_char, flags: i32) -> *mut c_void;
        fn dlsym(handle: *mut c_void, name: *const c_char) -> *mut c_void;
    }
    pub fn open(path: &Path) -> usize {
        let Ok(name) = CString::new(path.as_os_str().as_bytes()) else {
            return 0;
        };
        unsafe { dlopen(name.as_ptr(), 2) as usize }
    }
    pub fn symbol(handle: usize, name: &CStr) -> *mut c_void {
        unsafe { dlsym(handle as *mut c_void, name.as_ptr()) }
    }
}
struct Selected {
    function: Option<Project>,
    generation: u64,
    revision: u64,
    inset: bool,
    profile: String,
    artifact: String,
    #[cfg(test)]
    live_records: Arc<AtomicU64>,
}
#[cfg(test)]
impl Drop for Selected {
    fn drop(&mut self) {
        self.live_records.fetch_sub(1, Ordering::SeqCst);
    }
}
struct Entry {
    generation: u64,
    sha: String,
    inset: bool,
    function: Option<Project>,
    refusal: Option<&'static str>,
    ever_selected: bool,
    // Opaque process-loader reference, never dereferenced, serialized or closed.
    // This is not a Rust allocation or borrowed memory transferred across ABI.
    _resident_handle: usize,
}
#[derive(Default)]
struct Control {
    busy: bool,
    entries: Vec<Entry>,
    highest: u64,
    revision: u64,
    attempts: u64,
    charged: u64,
    next_request: u64,
    requests: BTreeMap<u64, Arc<AtomicU8>>,
    current: Option<Arc<Selected>>,
    retired: Vec<Arc<Selected>>,
}
#[derive(Clone, Debug, Default)]
pub(crate) struct Computed {
    pub generation: u64,
    pub revision: u64,
    pub width: u32,
    pub height: u32,
    pub rect_bits: [u32; 4],
    pub pixels: Pixels,
}
#[derive(Debug, Clone, Default)]
pub(crate) struct Snapshot {
    pub generation: u64,
    pub revision: u64,
    pub calls: u64,
    pub retained: u64,
    pub profile: String,
    pub artifact: String,
    pub pid: u32,
    pub busy: bool,
    pub inset: bool,
    pub last: Option<Computed>,
}
impl Snapshot {
    pub fn json(&self, request: &str, outcome: &str, reason: &str) -> String {
        let policy = if self.generation == 0 {
            "compiled"
        } else if self.inset {
            "inset_one_pixel"
        } else {
            "product"
        };
        let last=self.last.as_ref().map(|s|format!("{{\"generation\":{},\"selection_revision\":{},\"extent\":[{},{}],\"rect_bits\":[{},{},{},{}],\"pixels\":[{},{},{},{}]}}",s.generation,s.revision,s.width,s.height,s.rect_bits[0],s.rect_bits[1],s.rect_bits[2],s.rect_bits[3],s.pixels.x,s.pixels.y,s.pixels.width,s.pixels.height)).unwrap_or_else(||"null".into());
        format!("{{\"schema\":\"rusty.quest.development.scissor.readback.v1\",\"outcome\":\"{outcome}\",\"reason\":\"{reason}\",\"request_profile_sha256\":\"{request}\",\"selected_profile_sha256\":\"{}\",\"artifact_sha256\":\"{}\",\"native_pid\":{},\"generation\":{},\"selection_revision\":{},\"policy\":\"{policy}\",\"busy\":{},\"renderer_calls\":{},\"retained_images\":{},\"last_computed\":{last}}}",self.profile,self.artifact,self.pid,self.generation,self.revision,self.busy,self.calls,self.retained)
    }
}
pub(crate) struct Adapter {
    control: Mutex<Control>,
    active: AtomicPtr<Selected>,
    readers: AtomicU64,
    busy: AtomicBool,
    calls: AtomicU64,
    retained: AtomicU64,
    sample_sequence: AtomicU64,
    sample: [AtomicU64; 7],
    #[cfg(test)]
    before_publish: Mutex<Option<(Arc<std::sync::Barrier>, Arc<std::sync::Barrier>)>>,
    #[cfg(test)]
    live_records: Arc<AtomicU64>,
}
struct SelectionRead<'a> {
    owner: &'a Adapter,
    pointer: *mut Selected,
}
impl std::ops::Deref for SelectionRead<'_> {
    type Target = Selected;
    fn deref(&self) -> &Selected {
        // current/retired Arcs keep this immutable record alive until every SC reader
        // leaves. The record is never exposed to module code or retained by a caller.
        unsafe { &*self.pointer }
    }
}
impl Drop for SelectionRead<'_> {
    fn drop(&mut self) {
        self.owner.readers.fetch_sub(1, Ordering::SeqCst);
    }
}
impl Adapter {
    pub fn new() -> Self {
        Self {
            control: Mutex::new(Control::default()),
            active: AtomicPtr::new(std::ptr::null_mut()),
            readers: AtomicU64::new(0),
            busy: AtomicBool::new(false),
            calls: AtomicU64::new(0),
            retained: AtomicU64::new(0),
            sample_sequence: AtomicU64::new(0),
            sample: std::array::from_fn(|_| AtomicU64::new(0)),
            #[cfg(test)]
            before_publish: Mutex::new(None),
            #[cfg(test)]
            live_records: Arc::new(AtomicU64::new(0)),
        }
    }
    #[cfg(test)]
    pub fn test_before_publish(
        &self,
        entered: Arc<std::sync::Barrier>,
        release: Arc<std::sync::Barrier>,
    ) {
        *self.before_publish.lock().unwrap() = Some((entered, release));
    }
    #[cfg(test)]
    pub fn test_live_records(&self) -> u64 {
        self.live_records.load(Ordering::SeqCst)
    }
    fn selected(&self) -> Option<SelectionRead<'_>> {
        if self.active.load(Ordering::SeqCst).is_null() {
            return None;
        }
        self.readers.fetch_add(1, Ordering::SeqCst);
        let p = self.active.load(Ordering::SeqCst);
        if p.is_null() {
            self.readers.fetch_sub(1, Ordering::SeqCst);
            None
        } else {
            Some(SelectionRead {
                owner: self,
                pointer: p,
            })
        }
    }
    pub fn begin(&self) -> Result<u64, &'static str> {
        let mut c = self.control.lock().map_err(|_| "CONTROL")?;
        let id = c
            .next_request
            .checked_add(1)
            .filter(|v| *v <= i64::MAX as u64)
            .ok_or("REQUEST_EXHAUSTED")?;
        c.next_request = id;
        c.requests.insert(id, Arc::new(AtomicU8::new(PENDING)));
        Ok(id)
    }
    pub fn cancel(&self, id: u64) -> bool {
        let Ok(mut c) = self.control.lock() else {
            return false;
        };
        let Some(token) = c.requests.get(&id) else {
            return false;
        };
        let cancelled =
            match token.compare_exchange(PENDING, CANCELLED, Ordering::AcqRel, Ordering::Acquire) {
                Ok(_) => true,
                Err(v) => v == CANCELLED,
            };
        if cancelled {
            c.requests.remove(&id);
        }
        cancelled
    }
    pub fn end(&self, id: u64) {
        if let Ok(mut c) = self.control.lock() {
            c.requests.remove(&id);
        }
    }
    fn token(&self, id: u64) -> Result<Arc<AtomicU8>, &'static str> {
        self.control
            .lock()
            .map_err(|_| "CONTROL")?
            .requests
            .get(&id)
            .cloned()
            .ok_or("REQUEST_NOT_PENDING")
    }
    fn publish(
        &self,
        c: &mut Control,
        token: Option<&AtomicU8>,
        generation: u64,
        function: Option<Project>,
        inset: bool,
        profile: &str,
        sha: &str,
    ) -> Result<(), &'static str> {
        let revision = c
            .revision
            .checked_add(1)
            .filter(|v| *v <= i64::MAX as u64)
            .ok_or("REVISION_EXHAUSTED")?;
        let selected = Arc::new(Selected {
            function,
            generation,
            revision,
            inset,
            profile: profile.into(),
            artifact: sha.into(),
            #[cfg(test)]
            live_records: {
                self.live_records.fetch_add(1, Ordering::SeqCst);
                self.live_records.clone()
            },
        });
        // cancel() takes this same control mutex. Publication and cancellation have a
        // single winner; Java refuses cancellation once publication has won.
        if let Some(token) = token {
            token
                .compare_exchange(PENDING, PUBLISHED, Ordering::AcqRel, Ordering::Acquire)
                .map_err(|_| "REQUEST_CANCELLED")?;
        }
        let pointer = Arc::as_ptr(&selected) as *mut Selected;
        let previous = c.current.replace(selected);
        self.active.store(pointer, Ordering::SeqCst);
        if let Some(previous) = previous {
            c.retired.push(previous);
        }
        // SC reader entry precedes its pointer read. After this publication,
        // zero readers means no old pointer can still be borrowed; later readers
        // see this or a newer pointer. Only Rust metadata is reclaimed, never code.
        if self.readers.load(Ordering::SeqCst) == 0 {
            c.retired.clear();
        }
        c.revision = revision;
        Ok(())
    }
    #[cfg(test)]
    pub fn activate(
        &self,
        path: &Path,
        sha: &str,
        generation: u64,
        inset: bool,
        profile: &str,
    ) -> Result<(), &'static str> {
        let id = self.begin()?;
        let r = self.activate_request(id, path, sha, generation, inset, profile);
        self.end(id);
        r
    }
    pub fn activate_request(
        &self,
        id: u64,
        path: &Path,
        sha: &str,
        generation: u64,
        inset: bool,
        profile: &str,
    ) -> Result<(), &'static str> {
        let token = self.token(id)?;
        if token.load(Ordering::Acquire) != PENDING {
            return Err("REQUEST_CANCELLED");
        }
        if generation == 0 || generation > i64::MAX as u64 || !hex(sha) || !hex(profile) {
            return Err("INPUT");
        }
        {
            let mut c = self.control.lock().map_err(|_| "CONTROL")?;
            if c.busy {
                return Err("BUSY");
            }
            if let Some(index) = c.entries.iter().position(|e| e.generation == generation) {
                let e = &c.entries[index];
                if e.sha != sha || e.inset != inset {
                    return Err("GENERATION_COLLISION");
                }
                if let Some(reason) = e.refusal {
                    return Err(reason);
                }
                if self.selected().is_some_and(|s| s.generation == generation)
                    || !e.ever_selected && generation > c.highest
                {
                    let function = e.function;
                    self.publish(
                        &mut c,
                        Some(&token),
                        generation,
                        function,
                        inset,
                        profile,
                        sha,
                    )?;
                    c.entries[index].ever_selected = true;
                    c.highest = c.highest.max(generation);
                    return Ok(());
                }
                return Err("USE_SELECT_RETAINED");
            }
            if generation <= c.highest {
                return Err("STALE_GENERATION");
            }
            if c.attempts >= MAX_ATTEMPTS {
                return Err("IMAGE_BUDGET");
            }
            c.busy = true;
            self.busy.store(true, Ordering::Release);
        }
        // No control mutex across file I/O, native constructors/getter or probe calls.
        let mut opened = 0usize;
        let mut attempted = false;
        let result = (|| {
            let metadata = fs::symlink_metadata(path).map_err(|_| "IMAGE_METADATA")?;
            if !path.is_absolute()
                || metadata.file_type().is_symlink()
                || !metadata.is_file()
                || !metadata.permissions().readonly()
                || metadata.len() == 0
                || metadata.len() > MAX_IMAGE
            {
                return Err("IMAGE_FILE");
            }
            let canonical = fs::canonicalize(path).map_err(|_| "IMAGE_CANONICAL")?;
            #[cfg(windows)]
            let canonical = canonical
                .to_str()
                .and_then(|p| p.strip_prefix("\\\\?\\"))
                .map(std::path::PathBuf::from)
                .unwrap_or(canonical);
            if canonical != path {
                return Err("IMAGE_CANONICAL");
            }
            let bytes = fs::read(path).map_err(|_| "IMAGE_READ")?;
            if bytes.len() as u64 != metadata.len() || fingerprint(&bytes) != sha {
                return Err("IMAGE_SHA256");
            }
            if token.load(Ordering::Acquire) != PENDING {
                return Err("REQUEST_CANCELLED");
            }
            {
                let mut c = self.control.lock().map_err(|_| "CONTROL")?;
                let next = c
                    .charged
                    .checked_add(metadata.len())
                    .filter(|v| *v <= MAX_BYTES)
                    .ok_or("IMAGE_BUDGET")?;
                c.attempts += 1;
                c.charged = next;
                attempted = true;
            }
            opened = image::open(path);
            if opened == 0 {
                return Err("IMAGE_LOAD");
            }
            self.retained.fetch_add(1, Ordering::Relaxed);
            let symbol = image::symbol(opened, c"rq_scissor_api");
            if symbol.is_null() {
                return Err("ENTRY");
            }
            let getter: unsafe extern "C" fn() -> *const Header =
                unsafe { std::mem::transmute(symbol) };
            let header = unsafe { getter() };
            if header.is_null() || header as usize % align_of::<Header>() != 0 {
                return Err("HEADER_POINTER");
            }
            let value = unsafe { header.read() };
            if value.version != 1
                || value.size != size_of::<Table>() as u32
                || value.contract != CONTRACT
                || value.generation != generation
            {
                return Err("HEADER");
            }
            let function = unsafe { &*header.cast::<Table>() }
                .project
                .ok_or("FUNCTION")?;
            probe(function, inset)?;
            if fingerprint(&fs::read(path).map_err(|_| "IMAGE_REREAD")?) != sha {
                return Err("IMAGE_CHANGED");
            }
            Ok(function)
        })();
        #[cfg(test)]
        {
            let gate = self.before_publish.lock().unwrap().take();
            if let Some((entered, release)) = gate {
                entered.wait();
                release.wait();
            }
        }
        let mut c = self.control.lock().map_err(|_| "CONTROL")?;
        c.busy = false;
        self.busy.store(false, Ordering::Release);
        match result {
            Ok(function) => {
                let published = self.publish(
                    &mut c,
                    Some(&token),
                    generation,
                    Some(function),
                    inset,
                    profile,
                    sha,
                );
                let accepted = published.is_ok();
                if accepted {
                    c.highest = c.highest.max(generation);
                }
                c.entries.push(Entry {
                    generation,
                    sha: sha.into(),
                    inset,
                    function: Some(function),
                    refusal: None,
                    ever_selected: accepted,
                    _resident_handle: opened,
                });
                published
            }
            Err(reason) => {
                if attempted {
                    c.entries.push(Entry {
                        generation,
                        sha: sha.into(),
                        inset,
                        function: None,
                        refusal: Some(reason),
                        ever_selected: false,
                        _resident_handle: opened,
                    });
                }
                Err(reason)
            }
        }
    }
    #[cfg(test)]
    pub fn select_retained(&self, generation: u64, profile: &str) -> Result<(), &'static str> {
        let id = self.begin()?;
        let result = self.select_request(id, generation, profile);
        self.end(id);
        result
    }
    pub fn select_request(
        &self,
        id: u64,
        generation: u64,
        profile: &str,
    ) -> Result<(), &'static str> {
        let token = self.token(id)?;
        if !hex(profile) {
            return Err("INPUT");
        }
        let mut c = self.control.lock().map_err(|_| "CONTROL")?;
        if c.busy {
            return Err("BUSY");
        }
        let e = c
            .entries
            .iter()
            .find(|e| e.generation == generation && e.ever_selected && e.function.is_some())
            .ok_or("NOT_RETAINED")?;
        let (function, inset, sha) = (e.function, e.inset, e.sha.clone());
        self.publish(
            &mut c,
            Some(&token),
            generation,
            function,
            inset,
            profile,
            &sha,
        )
    }
    pub fn disable(&self, profile: &str) -> Result<(), &'static str> {
        if !hex(profile) {
            return Err("INPUT");
        }
        let mut c = self.control.lock().map_err(|_| "CONTROL")?;
        // Static mode is immediate even during a slow admission. Pending requests can
        // finish staging/validation, but cannot publish after this explicit disable.
        for token in c.requests.values() {
            let _ = token.compare_exchange(PENDING, CANCELLED, Ordering::AcqRel, Ordering::Acquire);
        }
        self.publish(&mut c, None, 0, None, false, profile, "")
    }
    pub fn project(&self, width: u32, height: u32, rect: [f32; 4]) -> Option<Pixels> {
        let (function, generation, revision) = {
            let selected = self.selected()?;
            (selected.function?, selected.generation, selected.revision)
        };
        let mut guarded = Guarded::new();
        if unsafe {
            function(
                width,
                height,
                rect[0],
                rect[1],
                rect[2],
                rect[3],
                &mut guarded.pixels,
                16,
            )
        } != 0
            || !guarded.valid(width, height)
        {
            return None;
        }
        let _ = self
            .calls
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| {
                Some(v.saturating_add(1))
            });
        let seq = self.sample_sequence.load(Ordering::SeqCst);
        // Fixed atomic telemetry only, no lock/wait/allocation. Contended writers may
        // skip one sample. SC ordering makes the generation/rectangle read coherent.
        if seq & 1 == 0
            && self
                .sample_sequence
                .compare_exchange(seq, seq.wrapping_add(1), Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
        {
            let p = guarded.pixels;
            for (slot, v) in self.sample.iter().zip([
                generation,
                revision,
                pack(width, height),
                pack(rect[0].to_bits(), rect[1].to_bits()),
                pack(rect[2].to_bits(), rect[3].to_bits()),
                pack(p.x as u32, p.y as u32),
                pack(p.width, p.height),
            ]) {
                slot.store(v, Ordering::SeqCst);
            }
            self.sample_sequence
                .store(seq.wrapping_add(2), Ordering::SeqCst);
        }
        Some(guarded.pixels)
    }
    pub fn snapshot(&self) -> Snapshot {
        let mut result = Snapshot {
            pid: std::process::id(),
            calls: self.calls.load(Ordering::Relaxed),
            retained: self.retained.load(Ordering::Relaxed),
            busy: self.busy.load(Ordering::Acquire),
            ..Snapshot::default()
        };
        if let Some(s) = self.selected() {
            result.generation = s.generation;
            result.revision = s.revision;
            result.profile = s.profile.clone();
            result.artifact = s.artifact.clone();
            result.inset = s.inset;
        }
        for _ in 0..4 {
            let first = self.sample_sequence.load(Ordering::SeqCst);
            if first == 0 || first & 1 != 0 {
                continue;
            }
            let v = self.sample.each_ref().map(|s| s.load(Ordering::SeqCst));
            if self.sample_sequence.load(Ordering::SeqCst) == first {
                let (width, height) = unpack(v[2]);
                let (r0, r1) = unpack(v[3]);
                let (r2, r3) = unpack(v[4]);
                let (x, y) = unpack(v[5]);
                let (w, h) = unpack(v[6]);
                result.last = Some(Computed {
                    generation: v[0],
                    revision: v[1],
                    width,
                    height,
                    rect_bits: [r0, r1, r2, r3],
                    pixels: Pixels {
                        x: x as i32,
                        y: y as i32,
                        width: w,
                        height: h,
                    },
                });
                break;
            }
        }
        result
    }
}
#[repr(C)]
#[derive(Clone, Copy, Eq, PartialEq)]
struct Guarded {
    before: u64,
    pixels: Pixels,
    after: u64,
}
impl Guarded {
    fn new() -> Self {
        Self {
            before: 0xaabbccdd,
            pixels: Pixels::default(),
            after: 0x11223344,
        }
    }
    fn valid(&self, width: u32, height: u32) -> bool {
        let p = self.pixels;
        self.before == 0xaabbccdd
            && self.after == 0x11223344
            && p.x >= 0
            && p.y >= 0
            && p.width > 0
            && p.height > 0
            && (p.x as u32)
                .checked_add(p.width)
                .is_some_and(|x| x <= width)
            && (p.y as u32)
                .checked_add(p.height)
                .is_some_and(|y| y <= height)
    }
}
fn probe(function: Project, inset: bool) -> Result<(), &'static str> {
    let mut v = Guarded::new();
    let status = unsafe { function(1920, 1080, 0.1, 0.2, 0.55, 0.5, &mut v.pixels, 16) };
    let expected = if inset {
        Pixels {
            x: 193,
            y: 217,
            width: 1054,
            height: 538,
        }
    } else {
        Pixels {
            x: 192,
            y: 216,
            width: 1056,
            height: 540,
        }
    };
    if status != 0 || !v.valid(1920, 1080) || v.pixels != expected {
        return Err("PROBE");
    }
    for (w, h, x, len) in [
        (0, 1, 0., 16),
        (1, 0, 0., 16),
        (1, 1, f32::NAN, 16),
        (1, 1, 0., 15),
        (1, 1, 0., 17),
    ] {
        let old = v;
        if unsafe { function(w, h, x, 0., 1., 1., &mut v.pixels, len) } == 0 || v != old {
            return Err("PROBE_REFUSAL");
        }
    }
    if unsafe { function(1, 1, 0., 0., 1., 1., std::ptr::null_mut(), 16) } == 0 {
        return Err("PROBE_NULL");
    }
    Ok(())
}
fn pack(a: u32, b: u32) -> u64 {
    (a as u64) << 32 | b as u64
}
fn unpack(v: u64) -> (u32, u32) {
    ((v >> 32) as u32, v as u32)
}
pub(crate) fn hex(v: &str) -> bool {
    v.len() == 64
        && v.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn fingerprint(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
static GLOBAL: OnceLock<Adapter> = OnceLock::new();
pub(crate) fn adapter() -> &'static Adapter {
    GLOBAL.get_or_init(Adapter::new)
}
pub(crate) fn project_if_enabled(width: u32, height: u32, rect: [f32; 4]) -> Option<Pixels> {
    GLOBAL.get().and_then(|v| v.project(width, height, rect))
}

#[cfg(target_os = "android")]
mod jni;
