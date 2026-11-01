//! Authenticated adapters may query saved recordings without touching the live writer.
//! One independent worker prepares bounded listings/manifests; chunk reads run on
//! the caller's export IO lane and hold no runtime/session/render lock.
use crate::{
    session_recording_clock::SessionTimestampKey,
    session_recording_contract::SESSION_STREAM_FILES,
    session_recording_recovery::{
        recover_directory, validate_recording_root_no_links, RecoveryDisposition,
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
fn open_checked(path: &Path, regular: bool) -> Result<(File, Stamp), String> {
    let m = fs::symlink_metadata(path).map_err(|_| denied())?;
    if m.file_type().is_symlink() || regular && !m.is_file() || !regular && !m.is_dir() {
        return Err(denied());
    }
    let mut options = fs::OpenOptions::new();
    options.read(true);
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(0x20000);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.custom_flags(if regular { 0x00200000 } else { 0x02200000 });
    }
    let file = options.open(path).map_err(|_| denied())?;
    let s = stamp(&file, regular)?;
    Ok((file, s))
}
struct Pinned {
    name: &'static str,
    path: PathBuf,
    file: File,
    stamp: Stamp,
    digest: String,
}
pub(crate) struct Prepared {
    pub(crate) manifest: Value,
    directory: PathBuf,
    root: PathBuf,
    root_stamp: Stamp,
    directory_stamp: Stamp,
    files: Vec<Pinned>,
}
impl Prepared {
    fn unchanged(&self) -> Result<(), String> {
        validate_recording_root_no_links(&self.root).map_err(|_| denied())?;
        if open_checked(&self.root, false)?.1.identity != self.root_stamp.identity
            || open_checked(&self.directory, false)?.1.identity != self.directory_stamp.identity
        {
            return Err(denied());
        }
        for p in &self.files {
            if stamp(&p.file, true)? != p.stamp || open_checked(&p.path, true)?.1 != p.stamp {
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
        // Reopening avoids shared seek cursors across concurrent authenticated requests.
        let (mut file, s) = open_checked(&p.path, true)?;
        if s != p.stamp {
            return Err(denied());
        }
        file.seek(SeekFrom::Start(offset)).map_err(|_| denied())?;
        let mut bytes = vec![0; length as usize];
        file.read_exact(&mut bytes).map_err(|_| denied())?;
        if stamp(&file, true)? != p.stamp {
            return Err(denied());
        }
        self.unchanged()?;
        check_owner(check)?;
        Ok(
            json!({"v":1,"state":"chunk","manifest_id":manifest,"file":name,"offset":offset,"length":length,"sha256":p.digest,"data":base64(&bytes)}),
        )
    }
}
fn prepare(root: &Path, id: &str, check: &dyn Fn() -> bool) -> Result<Prepared, String> {
    check_owner(check)?;
    let key = SessionTimestampKey::parse(id).map_err(|_| denied())?;
    validate_recording_root_no_links(root).map_err(|_| denied())?;
    let directory = root.join(id);
    let root_stamp = open_checked(root, false)?.1;
    let directory_stamp = open_checked(&directory, false)?.1;
    let mut files = Vec::with_capacity(8);
    let mut total = 0u64;
    for name in CORE {
        let path = directory.join(name);
        let (file, stamp) = open_checked(&path, true)?;
        total = total
            .checked_add(stamp.len)
            .filter(|n| *n <= MAX_EXPORT_BYTES)
            .ok_or_else(denied)?;
        files.push(Pinned {
            name,
            path,
            file,
            stamp,
            digest: String::new(),
        });
    }
    let mut prepared = Prepared {
        manifest: Value::Null,
        directory,
        root: root.to_path_buf(),
        root_stamp,
        directory_stamp,
        files,
    };
    // Read small JSON metadata through the pinned handle; no unbounded read-to-end.
    let mut metadata = |index: usize| -> Result<Value, String> {
        let p = &mut prepared.files[index];
        if p.stamp.len > 1024 * 1024 {
            return Err(denied());
        }
        let mut bytes = Vec::with_capacity(p.stamp.len as usize);
        (&mut p.file)
            .take(p.stamp.len + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| denied())?;
        serde_json::from_slice(&bytes).map_err(|_| denied())
    };
    let original = metadata(0)?;
    let terminal = metadata(1)?;
    if terminal["saved"] != true {
        return Err(denied());
    }
    check_owner(check)?;
    let entry = recover_directory(&prepared.directory, key);
    if entry.session.error.is_some()
        || !matches!(
            entry.session.disposition,
            RecoveryDisposition::FinalizedCompleted | RecoveryDisposition::FinalizedEarly
        )
    {
        return Err(denied());
    }
    for name in SESSION_STREAM_FILES {
        let p = prepared.files.iter().find(|p| p.name == name).unwrap();
        if entry.session.durable_stream_lengths.get(name) != Some(&p.stamp.len) {
            return Err(denied());
        }
    }
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
    if root.exists() {
        for entry in fs::read_dir(root).map_err(|_| denied())? {
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
        let directory = root.join(&id);
        let eligible = prepare(root, &id, check);
        let entry = match eligible {
            Ok(p) => {
                json!({"id":id,"exportable":true,"disposition":p.manifest["disposition"],"condition":p.manifest["condition"],"total_bytes":p.manifest["total_bytes"]})
            }
            Err(_) => {
                let reason = if !directory.join("final.json").is_file() {
                    "not-finalized"
                } else {
                    "not-validated-saved"
                };
                json!({"id":id,"exportable":false,"disposition":reason,"condition":Value::Null})
            }
        };
        entries.push(entry);
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
