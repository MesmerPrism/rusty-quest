//! Authenticated adapters may query saved recordings without touching the live writer.
//! One independent worker prepares bounded listings/manifests; chunk reads run on
//! the caller's export IO lane and hold no runtime/session/render lock.
use crate::{
    session_recording_clock::SessionTimestampKey,
    session_recording_recovery::{
        recording_stream_name, validate_finalized_core, validate_recording_root_no_links,
        RecordingFiles,
    },
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    fs::{self, File},
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        mpsc, Arc, Mutex,
    },
    thread,
};
pub(crate) const MAX_CHUNK: u64 = 32768;
pub(crate) const MAX_EXPORT_BYTES: u64 = 128 * 1024 * 1024;
const CORE: [&str; 8] = [
    "manifest.json",
    "final.json",
    "events.jsonl",
    "polar-acc.jsonl",
    "polar-ecg.jsonl",
    "polar-hr-rr.jsonl",
    "breath.jsonl",
    "radius.jsonl",
];
type Check = Arc<dyn Fn() -> bool + Send + Sync>;
fn denied() -> String {
    "recording-not-exportable-or-changed".into()
}
fn check_owner(check: &dyn Fn() -> bool) -> Result<(), String> {
    if check() {
        Ok(())
    } else {
        Err("recording-owner-retired".into())
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct Stamp {
    len: u64,
    modified: Option<std::time::SystemTime>,
    created: Option<std::time::SystemTime>,
    identity: (u64, u64),
    changed: (i64, i64),
}
fn stamp(file: &File, regular: bool) -> Result<Stamp, String> {
    let m = file.metadata().map_err(|_| denied())?;
    if regular && !m.is_file() || !regular && !m.is_dir() {
        return Err(denied());
    }
    #[cfg(unix)]
    let (identity, changed, links) = {
        use std::os::unix::fs::MetadataExt;
        ((m.dev(), m.ino()), (m.ctime(), m.ctime_nsec()), m.nlink())
    };
    #[cfg(windows)]
    let (identity, changed, links) = {
        use std::os::windows::io::AsRawHandle;
        #[repr(C)]
        #[derive(Default)]
        struct Info {
            attr: u32,
            creation: [u32; 2],
            access: [u32; 2],
            write: [u32; 2],
            volume: u32,
            size_high: u32,
            size_low: u32,
            links: u32,
            index_high: u32,
            index_low: u32,
        }
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn GetFileInformationByHandle(h: *mut std::ffi::c_void, i: *mut Info) -> i32;
            fn GetFileInformationByHandleEx(
                h: *mut std::ffi::c_void,
                class: i32,
                i: *mut std::ffi::c_void,
                size: u32,
            ) -> i32;
        }
        let mut i = Info::default();
        if unsafe { GetFileInformationByHandle(file.as_raw_handle(), &mut i) } == 0
            || i.attr & 0x400 != 0
        {
            return Err(denied());
        }
        #[repr(C)]
        #[derive(Default)]
        struct Basic {
            creation: i64,
            access: i64,
            write: i64,
            change: i64,
            attributes: u32,
        }
        let mut b = Basic::default();
        if unsafe {
            GetFileInformationByHandleEx(
                file.as_raw_handle(),
                0,
                (&mut b as *mut Basic).cast(),
                std::mem::size_of::<Basic>() as u32,
            )
        } == 0
        {
            return Err(denied());
        }
        (
            (
                u64::from(i.volume),
                (u64::from(i.index_high) << 32) | u64::from(i.index_low),
            ),
            (b.change, 0),
            u64::from(i.links),
        )
    };
    #[cfg(not(any(unix, windows)))]
    return Err("export-platform-identity-unavailable".into());
    if regular && links != 1 {
        return Err(denied());
    }
    Ok(Stamp {
        len: m.len(),
        modified: m.modified().ok(),
        created: m.created().ok(),
        identity,
        changed,
    })
}
#[derive(Clone, Copy)]
#[allow(
    dead_code,
    reason = "Inactive platform variants are covered by target-selection tests."
)]
enum ExportPlatform {
    Android,
    Linux,
    Windows,
    Unsupported,
}
#[cfg(target_os = "android")]
const BUILD_PLATFORM: ExportPlatform = ExportPlatform::Android;
#[cfg(target_os = "linux")]
const BUILD_PLATFORM: ExportPlatform = ExportPlatform::Linux;
#[cfg(windows)]
const BUILD_PLATFORM: ExportPlatform = ExportPlatform::Windows;
#[cfg(not(any(target_os = "android", target_os = "linux", windows)))]
const BUILD_PLATFORM: ExportPlatform = ExportPlatform::Unsupported;
const fn unix_open_flags(platform: ExportPlatform, regular: bool) -> i32 {
    match platform {
        // Android is a distinct Rust target_os despite sharing these Linux ABI flags.
        ExportPlatform::Android | ExportPlatform::Linux => {
            0x20000 | 0x80000 | 0x800 | if regular { 0 } else { 0x10000 }
        }
        _ => 0,
    }
}
// cargo check for the actual Android target must compile this exact selection.
#[cfg(target_os = "android")]
const _: () = assert!(
    matches!(BUILD_PLATFORM, ExportPlatform::Android)
        && unix_open_flags(BUILD_PLATFORM, true) & 0x20000 != 0
        && unix_open_flags(BUILD_PLATFORM, false) & 0x10000 != 0
        && unix_open_flags(BUILD_PLATFORM, true) & 0x800 != 0
);

fn open_checked(path: &Path, regular: bool) -> Result<(File, Stamp), String> {
    let mut options = fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        let flags = unix_open_flags(BUILD_PLATFORM, regular);
        if flags == 0 {
            return Err("export-platform-custody-unavailable".into());
        }
        options.custom_flags(flags);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.custom_flags(if regular { 0x00200000 } else { 0x02200000 });
        // Windows path-relative opens below are safe while retained directories
        // deny deletion/renaming. Final entries are opened as reparse points and
        // stamp() refuses every reparse type before any bytes are read.
        if !regular {
            options.share_mode(0x1 | 0x2);
        }
    }
    let file = options.open(path).map_err(|_| denied())?;
    let metadata = stamp(&file, regular)?;
    Ok((file, metadata))
}
const MAX_DIRECTORY_ENTRIES: usize = 65536;
fn collect_stream_inventory(
    mut entries: impl Iterator<Item = Result<(std::ffi::OsString, bool), String>>,
    check: &dyn Fn() -> bool,
) -> Result<BTreeSet<String>, String> {
    let mut streams = BTreeSet::new();
    let mut count = 0usize;
    loop {
        check_owner(check)?;
        let Some(entry) = entries.next() else {
            break;
        };
        count += 1;
        // Count every entry, including ignored checkpoint/index filenames.
        if count > MAX_DIRECTORY_ENTRIES {
            return Err("recording-directory-inventory-limit".into());
        }
        let (name, regular) = entry?;
        if let Some(name) = recording_stream_name(&name, regular) {
            streams.insert(name);
        }
    }
    check_owner(check)?;
    Ok(streams)
}
// A one-shot namespace witness supplements directory timestamps, which can
// alias across a quick insertion/removal. It is armed before inventory/pinning,
// never rearmed, and needs neither a background thread nor a caller buffer.
#[cfg(windows)]
struct DirectoryChanges {
    handle: isize,
}
#[cfg(windows)]
impl DirectoryChanges {
    fn new(directory: &Directory) -> Result<Self, String> {
        use std::os::windows::ffi::OsStrExt;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn FindFirstChangeNotificationW(
                path: *const u16,
                subtree: i32,
                filter: u32,
            ) -> *mut std::ffi::c_void;
        }
        let path: Vec<_> = directory
            .path
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect();
        // Retained directory handles deny replacement of every app-controlled
        // Windows component while this path-based registration is established.
        let handle =
            unsafe { FindFirstChangeNotificationW(path.as_ptr(), 0, 0x1 | 0x2 | 0x4) } as isize;
        if handle == 0 || handle == -1 {
            return Err(denied());
        }
        Ok(Self { handle })
    }
    fn unchanged(&self) -> Result<(), String> {
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn WaitForSingleObject(handle: *mut std::ffi::c_void, millis: u32) -> u32;
        }
        // The notification stays signalled until rearmed; we never rearm it.
        if unsafe { WaitForSingleObject(self.handle as *mut std::ffi::c_void, 0) } == 258 {
            Ok(())
        } else {
            Err(denied())
        }
    }
}
#[cfg(windows)]
impl Drop for DirectoryChanges {
    fn drop(&mut self) {
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn FindCloseChangeNotification(handle: *mut std::ffi::c_void) -> i32;
        }
        // No pending user buffer, cancellation wait or worker thread exists.
        unsafe {
            FindCloseChangeNotification(self.handle as *mut std::ffi::c_void);
        }
    }
}
#[cfg(any(target_os = "android", target_os = "linux"))]
struct DirectoryChanges {
    state: Mutex<(File, bool)>,
}
#[cfg(any(target_os = "android", target_os = "linux"))]
impl DirectoryChanges {
    fn new(directory: &Directory) -> Result<Self, String> {
        use std::os::{
            fd::{AsRawFd, FromRawFd},
            unix::ffi::OsStrExt,
        };
        unsafe extern "C" {
            fn inotify_init1(flags: std::ffi::c_int) -> std::ffi::c_int;
            fn inotify_add_watch(
                fd: std::ffi::c_int,
                path: *const std::ffi::c_char,
                mask: u32,
            ) -> std::ffi::c_int;
        }
        let fd = unsafe { inotify_init1(0x80000 | 0x800) };
        if fd < 0 {
            return Err(denied());
        }
        let file = unsafe { File::from_raw_fd(fd) };
        // The kernel-owned fd alias targets this retained directory, requiring
        // no system-ancestor enumeration or app-controlled symlink traversal.
        let path = std::ffi::CString::new(directory.listing_path().as_os_str().as_bytes())
            .map_err(|_| denied())?;
        // ATTRIB, CREATE/DELETE/MOVE, self retirement and ONLYDIR. Unmount,
        // queue overflow and ignored-watch events are delivered independently.
        let mask = 0x4 | 0x40 | 0x80 | 0x100 | 0x200 | 0x400 | 0x800 | 0x01000000;
        if unsafe { inotify_add_watch(file.as_raw_fd(), path.as_ptr(), mask) } < 0 {
            return Err(denied());
        }
        Ok(Self {
            state: Mutex::new((file, false)),
        })
    }
    fn unchanged(&self) -> Result<(), String> {
        let mut state = self.state.try_lock().map_err(|_| denied())?;
        if state.1 {
            return Err(denied());
        }
        let mut reader = &state.0;
        let mut events = [0u8; 512];
        match reader.read(&mut events) {
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => Ok(()),
            // Any event (including overflow, ignored or unmount), EOF or read
            // failure permanently retires this witness. No event is discarded.
            _ => {
                state.1 = true;
                Err(denied())
            }
        }
    }
}
#[cfg(not(any(windows, target_os = "android", target_os = "linux")))]
struct DirectoryChanges;
#[cfg(not(any(windows, target_os = "android", target_os = "linux")))]
impl DirectoryChanges {
    fn new(_directory: &Directory) -> Result<Self, String> {
        Err(denied())
    }
    fn unchanged(&self) -> Result<(), String> {
        Err(denied())
    }
}
struct Directory {
    file: File,
    path: PathBuf,
    stamp: Stamp,
    changes: Option<DirectoryChanges>,
}
impl Directory {
    fn absolute(path: &Path) -> Result<Self, String> {
        let (file, stamp) = open_checked(path, false)?;
        Ok(Self {
            file,
            path: path.into(),
            stamp,
            changes: None,
        })
    }
    fn child(&self, name: &str, regular: bool) -> Result<(File, Stamp), String> {
        if name.is_empty()
            || name == "."
            || name == ".."
            || !name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        {
            return Err(denied());
        }
        #[cfg(unix)]
        {
            use std::os::fd::{AsRawFd, FromRawFd};
            let flags = unix_open_flags(BUILD_PLATFORM, regular);
            if flags == 0 {
                return Err(denied());
            }
            let component = std::ffi::CString::new(name).map_err(|_| denied())?;
            unsafe extern "C" {
                fn openat(
                    fd: std::ffi::c_int,
                    name: *const std::ffi::c_char,
                    flags: std::ffi::c_int,
                    ...
                ) -> std::ffi::c_int;
            }
            // The retained directory and CString live across the borrowed call.
            let fd = unsafe { openat(self.file.as_raw_fd(), component.as_ptr(), flags) };
            if fd < 0 {
                return Err(denied());
            }
            // A successful openat returns one new owned descriptor.
            let file = unsafe { File::from_raw_fd(fd) };
            let metadata = stamp(&file, regular)?;
            Ok((file, metadata))
        }
        #[cfg(windows)]
        {
            open_checked(&self.path.join(name), regular)
        }
        #[cfg(not(any(unix, windows)))]
        {
            Err("export-platform-custody-unavailable".into())
        }
    }
    fn directory(&self, name: &str) -> Result<Self, String> {
        let (file, stamp) = self.child(name, false)?;
        Ok(Self {
            file,
            path: self.path.join(name),
            stamp,
            changes: None,
        })
    }
    fn inventory_unchanged(&self) -> Result<(), String> {
        if stamp(&self.file, false)? != self.stamp {
            return Err(denied());
        }
        self.changes.as_ref().ok_or_else(denied)?.unchanged()
    }
    fn stream_inventory(&self, check: &dyn Fn() -> bool) -> Result<BTreeSet<String>, String> {
        check_owner(check)?;
        self.inventory_unchanged()?;
        let entries = fs::read_dir(self.listing_path())
            .map_err(|_| denied())?
            .map(|entry| {
                let entry = entry.map_err(|_| denied())?;
                let regular = entry.file_type().map_err(|_| denied())?.is_file();
                Ok((entry.file_name(), regular))
            });
        let streams = collect_stream_inventory(entries, check)?;
        check_owner(check)?;
        self.inventory_unchanged()?;
        Ok(streams)
    }
    fn listing_path(&self) -> PathBuf {
        #[cfg(unix)]
        {
            use std::os::fd::AsRawFd;
            // This kernel-owned alias names the retained descriptor; no
            // app-controlled path component can redirect directory enumeration.
            PathBuf::from(format!("/proc/self/fd/{}", self.file.as_raw_fd()))
        }
        #[cfg(not(unix))]
        {
            self.path.clone()
        }
    }
}
struct Custody {
    parent: Directory,
    root: Directory,
    session: Directory,
    id: String,
}
impl Custody {
    fn root(root: &Path) -> Result<(Directory, Directory), String> {
        validate_recording_root_no_links(root).map_err(|_| denied())?;
        // Only app-private files is opened by absolute path. System ancestors
        // remain trusted aliases and need no read/enumeration permission.
        let parent = Directory::absolute(root.parent().ok_or_else(denied)?)?;
        let directory = parent.directory("viscereality-recordings")?;
        Ok((parent, directory))
    }
    fn open(root: &Path, id: &str) -> Result<Self, String> {
        let (parent, root) = Self::root(root)?;
        let mut session = root.directory(id)?;
        session.changes = Some(DirectoryChanges::new(&session)?);
        Ok(Self {
            parent,
            root,
            session,
            id: id.into(),
        })
    }
    fn root_current(parent: &Directory, root: &Directory) -> Result<(), String> {
        if Directory::absolute(&parent.path)?.stamp.identity != parent.stamp.identity
            || parent.directory("viscereality-recordings")?.stamp.identity != root.stamp.identity
        {
            return Err(denied());
        }
        Ok(())
    }
    fn unchanged(&self) -> Result<(), String> {
        Self::root_current(&self.parent, &self.root)?;
        if self.root.directory(&self.id)?.stamp != self.session.stamp
            || stamp(&self.session.file, false)? != self.session.stamp
        {
            return Err(denied());
        }
        self.session.inventory_unchanged()?;
        Ok(())
    }
}
struct Pinned {
    name: &'static str,
    file: File,
    stamp: Stamp,
    digest: String,
}
pub(crate) struct Prepared {
    pub(crate) manifest: Value,
    custody: Custody,
    files: Vec<Pinned>,
}
impl Prepared {
    fn unchanged(&self) -> Result<(), String> {
        self.custody.unchanged()?;
        for p in &self.files {
            if stamp(&p.file, true)? != p.stamp
                || self.custody.session.child(p.name, true)?.1 != p.stamp
            {
                return Err(denied());
            }
        }
        Ok(())
    }
    fn read(
        &self,
        manifest: &str,
        name: &str,
        offset: u64,
        length: u64,
        check: &dyn Fn() -> bool,
    ) -> Result<Value, String> {
        check_owner(check)?;
        if self.manifest["manifest_id"].as_str() != Some(manifest)
            || length == 0
            || length > MAX_CHUNK
        {
            return Err("recording-chunk-query-invalid".into());
        }
        let p = self
            .files
            .iter()
            .find(|p| p.name == name)
            .ok_or_else(denied)?;
        if offset > p.stamp.len || length > p.stamp.len.saturating_sub(offset) {
            return Err("recording-chunk-range-invalid".into());
        }
        self.unchanged()?;
        // Positional IO reads the original pinned handle. Concurrent chunk
        // requests cannot share a seek cursor or reopen a substituted path.
        let mut bytes = vec![0; length as usize];
        let mut read = 0usize;
        while read < bytes.len() {
            #[cfg(unix)]
            let n = {
                use std::os::unix::fs::FileExt;
                p.file.read_at(&mut bytes[read..], offset + read as u64)
            };
            #[cfg(windows)]
            let n = {
                use std::os::windows::fs::FileExt;
                p.file.seek_read(&mut bytes[read..], offset + read as u64)
            };
            #[cfg(not(any(unix, windows)))]
            let n: std::io::Result<usize> = Err(std::io::Error::new(
                std::io::ErrorKind::Unsupported,
                "export-platform-custody-unavailable",
            ));
            let n = n.map_err(|_| denied())?;
            if n == 0 {
                return Err(denied());
            }
            read += n;
            check_owner(check)?;
        }
        if stamp(&p.file, true)? != p.stamp {
            return Err(denied());
        }
        self.unchanged()?;
        check_owner(check)?;
        Ok(
            json!({"v":1,"state":"chunk","manifest_id":manifest,"file":name,"offset":offset,"length":length,"sha256":p.digest,"data":base64(&bytes)}),
        )
    }
}
struct PinnedFiles<'a> {
    streams: &'a BTreeSet<String>,
    files: &'a mut [Pinned],
    check: &'a dyn Fn() -> bool,
}
struct CheckedRead<'a> {
    file: &'a mut File,
    remaining: u64,
    check: &'a dyn Fn() -> bool,
}
impl Read for CheckedRead<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        let guard = || {
            if (self.check)() {
                Ok(())
            } else {
                Err(std::io::Error::new(
                    std::io::ErrorKind::Other,
                    "recording-owner-retired",
                ))
            }
        };
        guard()?;
        let max = buffer.len().min(self.remaining.min(32768) as usize);
        let n = self.file.read(&mut buffer[..max])?;
        self.remaining -= n as u64;
        guard()?;
        Ok(n)
    }
}
impl RecordingFiles for PinnedFiles<'_> {
    fn stream_names(&self) -> Result<BTreeSet<String>, String> {
        check_owner(self.check)?;
        Ok(self.streams.clone())
    }
    fn len(&self, name: &str) -> Result<u64, String> {
        check_owner(self.check)?;
        let p = self
            .files
            .iter()
            .find(|p| p.name == name)
            .ok_or_else(denied)?;
        if stamp(&p.file, true)? != p.stamp {
            return Err(denied());
        }
        Ok(p.stamp.len)
    }
    fn reader(&mut self, name: &str) -> Result<Box<dyn Read + '_>, String> {
        self.len(name)?;
        let p = self
            .files
            .iter_mut()
            .find(|p| p.name == name)
            .ok_or_else(denied)?;
        p.file.seek(SeekFrom::Start(0)).map_err(|_| denied())?;
        Ok(Box::new(CheckedRead {
            file: &mut p.file,
            remaining: p.stamp.len,
            check: self.check,
        }))
    }
}
fn validate_pinned_core(
    files: &mut [Pinned],
    streams: &BTreeSet<String>,
    key: SessionTimestampKey,
    check: &dyn Fn() -> bool,
) -> Result<(Value, crate::session_recording_recovery::RecoveredEntry), String> {
    validate_finalized_core(
        &mut PinnedFiles {
            files,
            streams,
            check,
        },
        key,
        1024 * 1024,
        MAX_EXPORT_BYTES,
    )
    .map_err(|_| denied())
}
fn prepare(root: &Path, id: &str, check: &dyn Fn() -> bool) -> Result<Prepared, String> {
    check_owner(check)?;
    let key = SessionTimestampKey::parse(id).map_err(|_| denied())?;
    let custody = Custody::open(root, id)?;
    let mut files = Vec::with_capacity(8);
    let mut total = 0u64;
    for name in CORE {
        let (file, stamp) = custody.session.child(name, true)?;
        total = total
            .checked_add(stamp.len)
            .filter(|n| *n <= MAX_EXPORT_BYTES)
            .ok_or_else(denied)?;
        files.push(Pinned {
            name,
            file,
            stamp,
            digest: String::new(),
        });
    }
    let streams = custody.session.stream_inventory(check)?;
    let mut prepared = Prepared {
        manifest: Value::Null,
        custody,
        files,
    };
    let (original, entry) = validate_pinned_core(&mut prepared.files, &streams, key, check)?;
    prepared.unchanged()?;
    for p in &mut prepared.files {
        check_owner(check)?;
        p.file.seek(SeekFrom::Start(0)).map_err(|_| denied())?;
        let mut hash = Sha256::new();
        let mut buffer = [0u8; 32768];
        let mut consumed = 0u64;
        loop {
            check_owner(check)?;
            let n = p.file.read(&mut buffer).map_err(|_| denied())?;
            if n == 0 {
                break;
            }
            consumed += n as u64;
            if consumed > p.stamp.len {
                return Err(denied());
            }
            hash.update(&buffer[..n]);
        }
        if consumed != p.stamp.len {
            return Err(denied());
        }
        p.digest = format!("{:x}", hash.finalize());
    }
    prepared.unchanged()?;
    check_owner(check)?;
    let file_values: Vec<_> = prepared
        .files
        .iter()
        .map(|p| json!({"name":p.name,"size":p.stamp.len,"sha256":p.digest}))
        .collect();
    let mut manifest = json!({"v":1,"schema":"rusty.quest.recording_export.v1","id":id,"condition":original["condition"],"experiment_identity":original.get("experiment_identity").cloned().unwrap_or(Value::Null),"disposition":entry.session.disposition.as_str(),"saved":true,"file_set_policy":"core8-original-no-checkpoints-or-index","total_bytes":total,"files":file_values});
    let digest = format!("{:x}", Sha256::digest(manifest.to_string().as_bytes()));
    manifest["manifest_id"] = json!(digest);
    if manifest.to_string().len() > 30000 {
        return Err(denied());
    }
    prepared.manifest = manifest;
    Ok(prepared)
}
fn base64(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for c in bytes.chunks(3) {
        let a = c[0];
        let b = *c.get(1).unwrap_or(&0);
        let d = *c.get(2).unwrap_or(&0);
        out.push(TABLE[(a >> 2) as usize] as char);
        out.push(TABLE[(((a & 3) << 4) | (b >> 4)) as usize] as char);
        out.push(if c.len() > 1 {
            TABLE[(((b & 15) << 2) | (d >> 6)) as usize] as char
        } else {
            '='
        });
        out.push(if c.len() > 2 {
            TABLE[(d & 63) as usize] as char
        } else {
            '='
        });
    }
    out
}
#[derive(Clone, Debug)]
enum Query {
    List {
        before: String,
        limit: usize,
    },
    Describe(String),
    Read {
        manifest: String,
        file: String,
        offset: u64,
        length: u64,
    },
}
fn parse(v: &Value) -> Result<Query, String> {
    let o = v.as_object().ok_or("recording-query-invalid")?;
    let s = |k| v[k].as_str().ok_or("recording-query-invalid");
    match s("query")? {
        "list" if o.len() == 3 => {
            let before = s("before")?;
            if !before.is_empty() {
                SessionTimestampKey::parse(before).map_err(|_| "recording-query-invalid")?;
            }
            let limit = v["limit"]
                .as_u64()
                .filter(|n| (1..=16).contains(n))
                .ok_or("recording-query-invalid")?;
            Ok(Query::List {
                before: before.into(),
                limit: limit as usize,
            })
        }
        "describe" if o.len() == 2 => {
            let id = s("id")?;
            SessionTimestampKey::parse(id).map_err(|_| "recording-query-invalid")?;
            Ok(Query::Describe(id.into()))
        }
        "read" if o.len() == 5 => {
            let manifest = s("manifest_id")?;
            if manifest.len() != 64
                || !manifest
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            {
                return Err("recording-query-invalid".into());
            }
            let file = s("file")?;
            if !CORE.contains(&file) {
                return Err("recording-query-invalid".into());
            }
            let offset = v["offset"]
                .as_u64()
                .filter(|n| *n <= MAX_EXPORT_BYTES)
                .ok_or("recording-query-invalid")?;
            let length = v["length"]
                .as_u64()
                .filter(|n| *n > 0 && *n <= MAX_CHUNK)
                .ok_or("recording-query-invalid")?;
            Ok(Query::Read {
                manifest: manifest.into(),
                file: file.into(),
                offset,
                length,
            })
        }
        _ => Err("recording-query-invalid".into()),
    }
}
fn list(
    root: &Path,
    before: &str,
    limit: usize,
    check: &dyn Fn() -> bool,
) -> Result<Value, String> {
    check_owner(check)?;
    validate_recording_root_no_links(root).map_err(|_| denied())?;
    let mut names = BTreeSet::new();
    let anchors = match fs::symlink_metadata(root) {
        Ok(_) => Some(Custody::root(root)?),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
        Err(_) => return Err(denied()),
    };
    if let Some((_, directory)) = &anchors {
        for entry in fs::read_dir(directory.listing_path()).map_err(|_| denied())? {
            check_owner(check)?;
            let entry = entry.map_err(|_| denied())?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if SessionTimestampKey::parse(&name).is_ok()
                && (before.is_empty() || name.as_str() < before)
            {
                names.insert(name);
                if names.len() > limit + 1 {
                    names.pop_first();
                }
            }
        }
    }
    let has_more = names.len() > limit;
    if has_more {
        names.pop_first();
    }
    let mut entries = Vec::new();
    for id in names.into_iter().rev() {
        check_owner(check)?;
        let eligible = prepare(root, &id, check);
        let entry = match eligible {
            Ok(p) => {
                json!({"id":id,"exportable":true,"disposition":p.manifest["disposition"],"condition":p.manifest["condition"],"total_bytes":p.manifest["total_bytes"]})
            }
            Err(_) => {
                let reason = if anchors
                    .as_ref()
                    .and_then(|(_, r)| r.directory(&id).ok())
                    .and_then(|d| d.child("final.json", true).ok())
                    .is_none()
                {
                    "not-finalized"
                } else {
                    "not-validated-saved"
                };
                json!({"id":id,"exportable":false,"disposition":reason,"condition":Value::Null})
            }
        };
        entries.push(entry);
    }
    if let Some((parent, root)) = &anchors {
        Custody::root_current(parent, root)?;
    }
    check_owner(check)?;
    Ok(
        json!({"v":1,"state":"ready","entries":entries,"next_before":if has_more{entries.last().and_then(|e|e["id"].as_str()).map(str::to_owned)}else{None}}),
    )
}
enum Product {
    Listing(Value),
    Recording(Arc<Prepared>),
}
#[derive(Default)]
struct State {
    revision: u64,
    busy: bool,
    key: String,
    binding: String,
    result: Option<Result<Product, String>>,
}
struct Job {
    root: PathBuf,
    query: Query,
    key: String,
    binding: String,
    check: Check,
}
pub(crate) struct Service {
    sender: mpsc::SyncSender<Job>,
    state: Arc<Mutex<State>>,
    revision: Arc<AtomicU64>,
}
impl Service {
    pub(crate) fn new() -> Result<Self, String> {
        let (sender, receiver) = mpsc::sync_channel::<Job>(1);
        let state = Arc::new(Mutex::new(State::default()));
        let worker = Arc::clone(&state);
        thread::Builder::new()
            .name("recording-export-prepare".into())
            .spawn(move || {
                while let Ok(job) = receiver.recv() {
                    let result = match job.query {
                        Query::List { before, limit } => {
                            list(&job.root, &before, limit, &*job.check).map(Product::Listing)
                        }
                        Query::Describe(id) => prepare(&job.root, &id, &*job.check)
                            .map(|p| Product::Recording(Arc::new(p))),
                        _ => Err("recording-query-invalid".into()),
                    };
                    if let Ok(mut s) = worker.lock() {
                        s.busy = false;
                        if s.key == job.key && s.binding == job.binding && (job.check)() {
                            s.result = Some(result);
                        } else {
                            s.result = None;
                        }
                    }
                }
            })
            .map_err(|_| "recording-export-worker-unavailable")?;
        Ok(Self {
            sender,
            state,
            revision: Arc::new(AtomicU64::new(1)),
        })
    }
    pub(crate) fn query(
        &self,
        root: &Path,
        request: &Value,
        binding: String,
        check: Check,
    ) -> Value {
        let revision = Arc::clone(&self.revision);
        let admitted = revision.load(Ordering::Acquire);
        let check: Check =
            Arc::new(move || revision.load(Ordering::Acquire) == admitted && check());
        match self.execute(root, request, binding, check) {
            Ok(v) => v,
            Err(reason) => json!({"v":1,"state":"unavailable","reason":reason}),
        }
    }
    fn execute(
        &self,
        root: &Path,
        request: &Value,
        binding: String,
        check: Check,
    ) -> Result<Value, String> {
        check_owner(&*check)?;
        let query = parse(request)?;
        let key = request.to_string();
        let prepared = {
            let mut s = self.state.try_lock().map_err(|_| "recording-export-busy")?;
            // Main-thread retirement must not wait for IO. Even when its
            // try_lock misses, the atomic revision makes prior results unreadable.
            let revision = self.revision.load(Ordering::Acquire);
            if s.revision != revision {
                s.result = None;
                s.key.clear();
                s.binding.clear();
                s.revision = revision;
            }
            if s.binding != binding {
                s.result = None;
                if s.busy {
                    return Ok(json!({"v":1,"state":"busy"}));
                }
                s.binding = binding.clone();
                s.key.clear();
            }
            if let Query::Read { .. } = &query {
                match &s.result {
                    Some(Ok(Product::Recording(p))) => Some(Arc::clone(p)),
                    _ => return Err("recording-manifest-unavailable".into()),
                }
            } else {
                if s.busy {
                    return Ok(json!({"v":1,"state":if s.key==key{"preparing"}else{"busy"}}));
                }
                if s.key == key {
                    if let Some(result) = &s.result {
                        check_owner(&*check)?;
                        return match result {
                            Ok(Product::Listing(v)) => Ok(v.clone()),
                            Ok(Product::Recording(p)) => {
                                p.unchanged()?;
                                Ok(json!({"v":1,"state":"ready","manifest":p.manifest}))
                            }
                            Err(e) => Err(e.clone()),
                        };
                    }
                }
                s.busy = true;
                s.key = key.clone();
                s.result = None;
                if self
                    .sender
                    .try_send(Job {
                        root: root.into(),
                        query: query.clone(),
                        key,
                        binding,
                        check,
                    })
                    .is_err()
                {
                    s.busy = false;
                    return Err("recording-export-busy".into());
                }
                return Ok(json!({"v":1,"state":"preparing"}));
            }
        };
        if let (
            Query::Read {
                manifest,
                file,
                offset,
                length,
            },
            Some(p),
        ) = (query, prepared)
        {
            p.read(&manifest, &file, offset, length, &*check)
        } else {
            Err("recording-query-invalid".into())
        }
    }
    pub(crate) fn retire(&self) {
        self.revision.fetch_add(1, Ordering::AcqRel);
        if let Ok(mut s) = self.state.try_lock() {
            s.result = None;
            s.binding.clear();
            s.key.clear();
        }
    }
}
#[cfg(test)]
mod tests;

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ExperimentSessionRecordingExport_nativeRetire(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) {
    crate::experiment_session_runtime::retire_recording_export();
}
