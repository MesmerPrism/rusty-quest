//! Native writer lease and per-installation capability. No lease operation is a
//! provider teardown receipt. The native inode is separate from Java fcntl state.
use rusty_quest_broker_authority::packaged_json_sha256 as digest;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

const APP_SCHEMA: &str = "rusty.quest.embedded_duplex.app_process_fence.v1";
const APP_RUN_SCHEMA: &str = "rusty.quest.embedded_duplex.app_process_fence.v2";
const NATIVE_SCHEMA: &str = "rusty.quest.embedded_duplex.native_process_fence.v1";
const LOCK_NAME: &str = "native-process-fence.v1.lock";
const WITNESS_NAME: &str = "native-process-fence.v1.initialized";

/// Internal live app-channel reader, never an operator-supplied assertion.
pub(super) trait AppFenceSource: Send + Sync {
    fn record(&self) -> Result<String, String>;
    fn inspection_record(&self) -> Result<String, String> {
        self.record()
    }
    fn operational_run_handoff(&self) -> Result<Option<String>, String> {
        Ok(None)
    }
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct OperationalRunHandoff {
    #[serde(rename = "$schema")]
    schema: String,
    operational_run_id: String,
    app_record_sha256: String,
    prior_native_generation: u64,
    whole_original_id: String,
    request_sha256: String,
    outcome_sha256: String,
    physical_retirement_sha256: String,
    physical_retirement: serde_json::Value,
}

/// Same integral, ASCII-keyed canonical JSON used by the app's joined custody.
fn canonical_handoff_json(value: &serde_json::Value) -> Result<String, String> {
    use serde_json::Value;
    Ok(match value {
        Value::Null => "null".into(),
        Value::Bool(v) => v.to_string(),
        Value::String(v) => serde_json::to_string(v).map_err(|_| "handoff string encoding")?,
        Value::Number(v) if v.as_i64().is_some() => v.to_string(),
        Value::Array(items) => format!(
            "[{}]",
            items
                .iter()
                .map(canonical_handoff_json)
                .collect::<Result<Vec<_>, _>>()?
                .join(",")
        ),
        Value::Object(fields) => {
            let mut keys = fields.keys().collect::<Vec<_>>();
            keys.sort();
            let mut values = Vec::with_capacity(keys.len());
            for key in keys {
                if key.is_empty() || !key.bytes().all(|b| (0x20..=0x7e).contains(&b)) {
                    return Err("handoff canonical key invalid".into());
                }
                values.push(format!(
                    "{}:{}",
                    serde_json::to_string(key).map_err(|_| "handoff key encoding")?,
                    canonical_handoff_json(&fields[key])?
                ));
            }
            format!("{{{}}}", values.join(","))
        }
        _ => return Err("handoff canonical value is not an integral app value".into()),
    })
}

/// Validation is read-only, never a serialized capability. Only the fixed app
/// callback may supply the source-owned Original/join projection to its caller.
pub(super) fn validate_operational_run_handoff(
    raw: &str,
    binding: &AppBinding,
    pending: bool,
    counter: u64,
) -> Result<String, String> {
    if raw.is_empty() || raw.len() > 256 * 1024 {
        return Err("operational handoff bounds".into());
    }
    let h: OperationalRunHandoff =
        serde_json::from_str(raw).map_err(|_| "operational handoff closed shape invalid")?;
    let hex = |v: &str, n: usize| {
        v.len() == n
            && v.bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    };
    if h.schema != "rusty.quest.embedded_duplex.operational_run_handoff.v1"
        || !hex(&h.operational_run_id, 32)
        || !hex(&h.whole_original_id, 32)
        || ![
            &h.app_record_sha256,
            &h.request_sha256,
            &h.outcome_sha256,
            &h.physical_retirement_sha256,
        ]
        .iter()
        .all(|s| hex(s, 64))
        || h.app_record_sha256 != binding.record_sha256
        || (pending && binding.operational_run_id.as_deref() != Some(h.operational_run_id.as_str()))
        || counter == 0
        || counter > i64::MAX as u64
        || h.prior_native_generation != counter
    {
        return Err("operational handoff run/channel/counter subject differs".into());
    }
    let p = &h.physical_retirement;
    let schema = p["$schema"].as_str().ok_or("handoff Whole schema absent")?;
    let local = match schema {
        "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1" => true,
        "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3"
        | "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4" => {
            if p["ongoing_media_policy"]
                != serde_json::json!({"$schema":"rusty.manifold.peer.ongoing_media_policy.v2"})
            {
                return Err("handoff Whole policy differs".into());
            }
            true
        }
        "rusty.quest.embedded_duplex.remote_owner_cleanup.v1" => false,
        _ => return Err("handoff Whole schema unsupported".into()),
    };
    let app = p["app_process_generation"]
        .as_u64()
        .filter(|v| *v > 0)
        .ok_or("handoff Whole app generation invalid")?;
    if p["action"] != "whole_app_close"
        || p["peer_physical_cleanup"]
            != if local {
                "terminal"
            } else {
                "not_locally_started"
            }
        || p["native_executor_generation"].as_u64() != Some(counter)
        || app > binding.generation
        || (pending && app == binding.generation)
        || !p["config_sha256"].as_str().is_some_and(|s| hex(s, 64))
    {
        return Err("handoff Whole actor/config subject differs".into());
    }
    for key in [
        "native_host_physical_cleanup",
        "java_own_capture_cleanup",
        "java_renderer_cleanup",
        "java_control_cleanup",
        "app_process_fence_cleanup",
        "whole_app_physical_cleanup",
        "status",
    ] {
        if p[key] != "terminal" {
            return Err("handoff Whole physical owner remains pending".into());
        }
    }
    if digest(&canonical_handoff_json(p)?) != h.physical_retirement_sha256 {
        return Err("handoff Whole joined digest differs".into());
    }
    // Closed scalar fields cannot contain newlines; this is an unambiguous
    // in-memory retry identity bound to the actual resulting Native capability.
    Ok(digest(&format!(
        "{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n",
        h.schema,
        h.operational_run_id,
        h.app_record_sha256,
        counter,
        h.whole_original_id,
        h.request_sha256,
        h.outcome_sha256,
        h.physical_retirement_sha256
    )))
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct AppBinding {
    pub generation: u64,
    pub record_sha256: String,
    pub operational_run_id: Option<String>,
}
impl AppBinding {
    pub(super) fn parse(record: &str) -> Result<Self, String> {
        let (binding, pending) = Self::inspect(record)?;
        if !pending {
            return Err("native admission requires pending app fence".into());
        }
        Ok(binding)
    }

    /// Observation accepts clear, but never turns it into execution admission.
    pub(super) fn inspect(record: &str) -> Result<(Self, bool), String> {
        let fields: Vec<_> = record.split('\n').collect();
        let explicit = fields.first() == Some(&APP_RUN_SCHEMA);
        let checksum = if explicit { 7 } else { 6 };
        let hex = |v: &str| {
            v.len() == 64
                && v.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        };
        let uuid = |v: &str| {
            v.len() == 36
                && v.bytes().enumerate().all(|(i, b)| {
                    if [8, 13, 18, 23].contains(&i) {
                        b == b'-'
                    } else {
                        b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
                    }
                })
        };
        if record.len() > 512
            || fields.len() != checksum + 2
            || (!explicit && fields[0] != APP_SCHEMA)
            || fields[1].starts_with('0')
            || !fields[1].bytes().all(|b| b.is_ascii_digit())
            || !uuid(fields[2])
            || !matches!(fields[3], "pending" | "clear")
            || ![fields[4], fields[5]].iter().all(|v| *v == "-" || hex(v))
            || (explicit
                && (fields[6].len() != 32
                    || !fields[6]
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))))
            || !hex(fields[checksum])
            || fields[checksum] != digest(&(fields[..checksum].join("\n") + "\n"))
            || !fields[checksum + 1].is_empty()
        {
            return Err("native app fence binding invalid or recovery unsupported".into());
        }
        let generation: u64 = fields[1]
            .parse()
            .map_err(|_| "native app generation invalid")?;
        if generation == 0 || generation > i64::MAX as u64 {
            return Err("native app generation invalid".into());
        }
        Ok((
            Self {
                generation,
                record_sha256: digest(record),
                operational_run_id: explicit.then(|| fields[6].to_owned()),
            },
            fields[3] == "pending",
        ))
    }
}

fn private_metadata(path: &Path, directory: bool) -> Result<fs::Metadata, String> {
    let metadata = fs::symlink_metadata(path).map_err(|_| "native fence path unavailable")?;
    let expected = if directory { 0o700 } else { 0o600 };
    if metadata.file_type().is_symlink()
        || metadata.uid() != unsafe { libc::geteuid() }
        || metadata.mode() & 0o777 != expected
        || if directory {
            !metadata.is_dir()
        } else {
            !metadata.is_file() || metadata.nlink() != 1
        }
    {
        return Err("native fence private path invalid".into());
    }
    Ok(metadata)
}
fn same_inode(a: &fs::Metadata, b: &fs::Metadata) -> bool {
    a.dev() == b.dev() && a.ino() == b.ino()
}
fn read_bounded(file: &mut File) -> Result<String, String> {
    let size = file
        .metadata()
        .map_err(|_| "native fence stat failed")?
        .len();
    if size == 0 || size > 512 {
        return Err("native fence state missing or corrupt".into());
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|_| "native fence seek failed")?;
    let mut bytes = vec![0; size as usize];
    file.read_exact(&mut bytes)
        .map_err(|_| "native fence read failed")?;
    String::from_utf8(bytes).map_err(|_| "native fence state encoding invalid".into())
}

struct Writer {
    directory_path: PathBuf,
    directory: File,
    file: Mutex<File>,
    app_source: Arc<dyn AppFenceSource>,
    app_generation: AtomicU64,
    counter: AtomicU64,
    poisoned: AtomicBool,
}
impl Writer {
    fn open(
        directory_path: &Path,
        app_source: Arc<dyn AppFenceSource>,
        binding: &AppBinding,
    ) -> Result<Arc<Self>, String> {
        let metadata = private_metadata(directory_path, true)?;
        let directory = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(directory_path)
            .map_err(|_| "native fence directory open failed")?;
        if !same_inode(
            &metadata,
            &directory
                .metadata()
                .map_err(|_| "native fence directory stat failed")?,
        ) {
            return Err("native fence directory changed".into());
        }
        let path = directory_path.join(LOCK_NAME);
        let witness = directory_path.join(WITNESS_NAME);
        let witnessed = witness
            .try_exists()
            .map_err(|_| "native witness stat failed")?;
        if witnessed {
            let metadata = private_metadata(&witness, false)?;
            if metadata.len() > 128
                || fs::read_to_string(&witness).map_err(|_| "native witness read failed")?
                    != format!("{NATIVE_SCHEMA}\n")
            {
                return Err("native fence initialization witness corrupt".into());
            }
            if !path.exists() {
                return Err("native fence state missing".into());
            }
        }
        let (mut file, created) = match OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(&path)
        {
            Ok(file) => (file, true),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => (
                OpenOptions::new()
                    .read(true)
                    .write(true)
                    .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
                    .open(&path)
                    .map_err(|_| "native fence file open failed")?,
                false,
            ),
            Err(_) => return Err("native fence file creation failed".into()),
        };
        let path_metadata = private_metadata(&path, false)?;
        if !same_inode(
            &path_metadata,
            &file
                .metadata()
                .map_err(|_| "native fence file stat failed")?,
        ) {
            return Err("native fence inode changed".into());
        }
        // flock is attached to this open file description. Never clone this
        // descriptor into Java or open the Java fcntl lock inode here.
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            return Err("native process writer already held".into());
        }
        let counter = if created {
            if witnessed {
                return Err("native fence state missing".into());
            }
            let mut marker = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
                .open(&witness)
                .map_err(|_| "native witness create failed")?;
            marker
                .write_all(format!("{NATIVE_SCHEMA}\n").as_bytes())
                .map_err(|_| "native witness write failed")?;
            marker
                .sync_all()
                .map_err(|_| "native witness sync failed")?;
            directory
                .sync_all()
                .map_err(|_| "native fence directory sync failed")?;
            0
        } else {
            if !witnessed {
                return Err("native initialization witness missing".into());
            }
            parse_counter(&read_bounded(&mut file)?)?
        };
        Ok(Arc::new(Self {
            directory_path: directory_path.to_owned(),
            directory,
            file: Mutex::new(file),
            app_source,
            app_generation: AtomicU64::new(binding.generation),
            counter: AtomicU64::new(counter),
            poisoned: AtomicBool::new(false),
        }))
    }
    fn observe(&self) -> Result<(), String> {
        if self.poisoned.load(Ordering::SeqCst) {
            return Err("native writer poisoned".into());
        }
        let result = (|| {
            let directory = private_metadata(&self.directory_path, true)?;
            if !same_inode(
                &directory,
                &self
                    .directory
                    .metadata()
                    .map_err(|_| "native directory stat failed")?,
            ) {
                return Err("native fence directory changed".into());
            }
            let witness = self.directory_path.join(WITNESS_NAME);
            if private_metadata(&witness, false)?.len() > 128
                || fs::read_to_string(witness).map_err(|_| "native witness read failed")?
                    != format!("{NATIVE_SCHEMA}\n")
            {
                return Err("native fence witness changed".into());
            }
            let path = private_metadata(&self.directory_path.join(LOCK_NAME), false)?;
            let mut file = self
                .file
                .lock()
                .map_err(|_| "native writer mutex poisoned")?;
            if !same_inode(
                &path,
                &file.metadata().map_err(|_| "native file stat failed")?,
            ) || unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0
            {
                return Err("native writer lease unavailable".into());
            }
            if self.counter.load(Ordering::SeqCst) > 0
                && parse_counter(&read_bounded(&mut file)?)? != self.counter.load(Ordering::SeqCst)
            {
                return Err("native incarnation journal differs".into());
            }
            Ok(())
        })();
        if result.is_err() {
            self.poisoned.store(true, Ordering::SeqCst);
        }
        result
    }
    fn allocate(
        self: &Arc<Self>,
        binding: AppBinding,
        operational_handoff_sha256: Option<String>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.observe()?;
        if binding.generation != self.app_generation.load(Ordering::SeqCst) {
            return Err("native app generation differs".into());
        }
        let next = self
            .counter
            .load(Ordering::SeqCst)
            .checked_add(1)
            .filter(|v| *v <= i64::MAX as u64)
            .ok_or("native executor generation exhausted")?;
        let body = format!("{NATIVE_SCHEMA}\n{next}\n");
        let record = format!("{body}{}\n", digest(&body));
        let committed = (|| {
            let mut file = self
                .file
                .lock()
                .map_err(|_| "native writer mutex poisoned")?;
            file.seek(SeekFrom::Start(0))
                .map_err(|_| "native generation seek failed")?;
            file.write_all(record.as_bytes())
                .map_err(|_| "native generation write failed")?;
            file.set_len(record.len() as u64)
                .map_err(|_| "native generation truncate failed")?;
            file.sync_all()
                .map_err(|_| "native generation sync failed")?;
            self.directory
                .sync_all()
                .map_err(|_| "native generation directory sync failed")?;
            Ok::<(), String>(())
        })();
        if let Err(reason) = committed {
            self.poisoned.store(true, Ordering::SeqCst);
            return Err(reason);
        }
        self.counter.store(next, Ordering::SeqCst);
        Ok(Arc::new(NativeCapability {
            writer: self.clone(),
            binding,
            generation: next,
            retired: AtomicBool::new(false),
            operational_handoff_sha256,
        }))
    }
}
fn parse_counter(record: &str) -> Result<u64, String> {
    let fields: Vec<_> = record.split('\n').collect();
    if fields.len() != 4
        || fields[0] != NATIVE_SCHEMA
        || fields[1].starts_with('0')
        || !fields[1].bytes().all(|b| b.is_ascii_digit())
        || fields[3] != ""
        || fields[2] != digest(&(fields[..2].join("\n") + "\n"))
    {
        return Err("native incarnation journal corrupt".into());
    }
    fields[1]
        .parse::<u64>()
        .ok()
        .filter(|v| *v > 0 && *v <= i64::MAX as u64)
        .ok_or("native incarnation journal invalid".into())
}

pub(super) struct NativeCapability {
    writer: Arc<Writer>,
    pub binding: AppBinding,
    pub generation: u64,
    retired: AtomicBool,
    operational_handoff_sha256: Option<String>,
}
impl NativeCapability {
    pub fn require_live(&self) -> Result<(), String> {
        if self.retired.load(Ordering::SeqCst)
            || self.writer.counter.load(Ordering::SeqCst) != self.generation
        {
            return Err("native executor capability retired".into());
        }
        self.writer.observe()?;
        // No native mutex is held across the live app-channel callback.
        let observed = self
            .writer
            .app_source
            .record()
            .and_then(|record| AppBinding::parse(&record));
        if observed.as_ref() != Ok(&self.binding) {
            self.writer.poisoned.store(true, Ordering::SeqCst);
            return Err("native app record or lease changed".into());
        }
        if self.retired.load(Ordering::SeqCst) {
            return Err("native executor capability retired".into());
        }
        Ok(())
    }
    /// This only retires execution authority; callers must already prove native
    /// no-media closure and the app's Java/display barriers. It clears no journal.
    pub fn retire(&self) -> Result<(), String> {
        self.require_live()?;
        self.retired.store(true, Ordering::SeqCst);
        Ok(())
    }
}
#[derive(Default)]
struct RegistryState {
    busy: bool,
    writer: Option<Arc<Writer>>,
    active: Option<Arc<NativeCapability>>,
    absence_retired: Option<AppBinding>,
}
#[derive(Default)]
pub(super) struct NativeFenceRegistry {
    state: Mutex<RegistryState>,
}

pub(super) struct OperationalRunInspection {
    pub actual_binding: Option<AppBinding>,
    pub native_generation: Option<u64>,
    pub refusal: Option<&'static str>,
}

/// Read-only pre-admission check. Never initialize either permanent inode.
fn inspect_unclaimed_counter(directory_path: &Path) -> Result<Option<u64>, String> {
    private_metadata(directory_path, true)?;
    let path = directory_path.join(LOCK_NAME);
    let witness = directory_path.join(WITNESS_NAME);
    let exists = |path: &Path| -> Result<bool, String> {
        match fs::symlink_metadata(path) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(_) => Err("native inspection path unavailable".into()),
        }
    };
    if !exists(&path)? && !exists(&witness)? {
        return Ok(None);
    }
    let metadata = private_metadata(&path, false)?;
    if private_metadata(&witness, false)?.len() > 128
        || fs::read_to_string(&witness).map_err(|_| "native witness read failed")?
            != format!("{NATIVE_SCHEMA}\n")
    {
        return Err("native inspection witness corrupt".into());
    }
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
        .open(&path)
        .map_err(|_| "native inspection file unavailable")?;
    if !same_inode(
        &metadata,
        &file
            .metadata()
            .map_err(|_| "native inspection stat failed")?,
    ) || unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0
    {
        return Err("native inspection writer unavailable".into());
    }
    let counter = parse_counter(&read_bounded(&mut file)?)?;
    if !same_inode(&metadata, &private_metadata(&path, false)?) {
        return Err("native inspection inode changed".into());
    }
    Ok(Some(counter))
}

impl NativeFenceRegistry {
    /// Readback of an already accepted exact claim; never allocate/reset on retry.
    pub fn retry_selected_run(
        &self,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Option<Arc<NativeCapability>>, String> {
        let binding = AppBinding::parse(&source.record()?)?;
        if binding.operational_run_id.is_none() {
            return Ok(None);
        }
        let cap = {
            let s = self
                .state
                .lock()
                .map_err(|_| "native retry registry poisoned")?;
            if s.busy {
                return Err("native_registry_busy".into());
            }
            s.active.clone()
        };
        let Some(cap) = cap else { return Ok(None) };
        if cap.binding != binding || cap.retired.load(Ordering::SeqCst) {
            return Ok(None);
        }
        if let Some(expected) = &cap.operational_handoff_sha256 {
            let raw = source
                .operational_run_handoff()?
                .ok_or("accepted handoff source unavailable on retry")?;
            let prior = cap
                .generation
                .checked_sub(1)
                .ok_or("native retry generation invalid")?;
            if validate_operational_run_handoff(&raw, &binding, true, prior)? != *expected {
                return Err("native handoff retry source changed".into());
            }
        }
        cap.require_live()?;
        {
            let s = self
                .state
                .lock()
                .map_err(|_| "native retry registry poisoned")?;
            if s.busy
                || !s
                    .active
                    .as_ref()
                    .is_some_and(|active| Arc::ptr_eq(active, &cap))
            {
                return Err("native retry owner changed".into());
            }
        }
        Ok(Some(cap))
    }

    pub fn inspect_operational_run(
        &self,
        directory: &Path,
        observed: &AppBinding,
    ) -> Result<OperationalRunInspection, String> {
        let (busy, writer, cap, closed) = {
            let s = self
                .state
                .lock()
                .map_err(|_| "native registry inspection poisoned")?;
            (
                s.busy,
                s.writer.clone(),
                s.active.clone(),
                s.absence_retired.clone(),
            )
        };
        let native_generation = if let Some(writer) = &writer {
            writer.observe()?;
            let generation = writer.app_generation.load(Ordering::SeqCst);
            let next_run = observed.operational_run_id.is_some()
                && cap
                    .as_ref()
                    .map(|c| &c.binding)
                    .or(closed.as_ref())
                    .is_some_and(|b| b.operational_run_id != observed.operational_run_id);
            if (observed.generation < generation
                || (observed.generation != generation && !next_run))
                || !same_inode(
                    &private_metadata(directory, true)?,
                    &writer
                        .directory
                        .metadata()
                        .map_err(|_| "native inspection directory stat failed")?,
                )
            {
                return Err("native inspection app owner or directory changed".into());
            }
            Some(writer.counter.load(Ordering::SeqCst)).filter(|v| *v > 0)
        } else {
            inspect_unclaimed_counter(directory)?
        };
        let refusal = if busy {
            Some("native_registry_busy")
        } else if observed.operational_run_id.is_some()
            && writer.is_none()
            && cap.is_none()
            && closed.is_none()
            && native_generation.is_some()
        {
            // A counter has no durable run/closure mapping. A recreated
            // registry cannot authenticate the prior process owner.
            Some("native_prior_process_owner_unreconciled")
        } else if cap
            .as_ref()
            .is_some_and(|c| !c.retired.load(Ordering::SeqCst))
        {
            Some("native_capability_owned")
        } else if writer.is_some() && cap.is_none() {
            Some("native_writer_without_capability")
        } else {
            None
        };
        let s = self
            .state
            .lock()
            .map_err(|_| "native registry inspection poisoned")?;
        if s.busy != busy
            || s.writer.as_ref().map(Arc::as_ptr) != writer.as_ref().map(Arc::as_ptr)
            || s.active.as_ref().map(Arc::as_ptr) != cap.as_ref().map(Arc::as_ptr)
            || s.absence_retired != closed
        {
            return Err("native registry changed during inspection".into());
        }
        Ok(OperationalRunInspection {
            actual_binding: cap.map(|c| c.binding.clone()).or(closed),
            native_generation,
            refusal,
        })
    }

    /// Typed observation only. Missing/failed capability validation never becomes absence.
    pub fn ownership_status(&self) -> Result<serde_json::Value, String> {
        let (busy, writer, cap, closed) = {
            let s = self
                .state
                .lock()
                .map_err(|_| "native registry status poisoned")?;
            (
                s.busy,
                s.writer.is_some(),
                s.active.clone(),
                s.absence_retired.clone(),
            )
        };
        if busy {
            return Ok(serde_json::json!({"state":"busy"}));
        }
        if let Some(cap) = cap {
            let retired = cap.retired.load(Ordering::SeqCst);
            if !retired {
                cap.require_live()?;
            }
            return Ok(
                serde_json::json!({"state":if retired{"retired"}else{"admitted"},
                "app_process_generation":cap.binding.generation,"app_record_sha256":cap.binding.record_sha256,
                "native_executor_generation":cap.generation}),
            );
        }
        if let Some(binding) = closed {
            return Ok(serde_json::json!({"state":"absence_retired",
            "app_process_generation":binding.generation,"app_record_sha256":binding.record_sha256}));
        }
        Ok(serde_json::json!({"state":if writer{"writer_without_capability"}else{"never_claimed"}}))
    }

    /// Process-local absence, authenticated by the same live app owner channel.
    /// This allocates no native capability, opens no native writer, and changes no counter.
    pub fn retire_never_claimed(
        &self,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<AppBinding, String> {
        let first = AppBinding::parse(&source.record()?)?;
        {
            let mut s = self
                .state
                .lock()
                .map_err(|_| "native absence registry poisoned")?;
            if s.busy || s.writer.is_some() || s.active.is_some() || s.absence_retired.is_some() {
                return Err("native never-claimed absence unavailable".into());
            }
            s.busy = true;
        }
        // No registry mutex is held across the actual app-channel callback.
        // A failed callback leaves the gate busy/unknown rather than enabling a claim.
        let second = AppBinding::parse(&source.record()?)?;
        if first != second {
            return Err("native absence app owner changed".into());
        }
        let mut s = self
            .state
            .lock()
            .map_err(|_| "native absence registry poisoned")?;
        if !s.busy || s.writer.is_some() || s.active.is_some() || s.absence_retired.is_some() {
            return Err("native absence registry changed".into());
        }
        s.absence_retired = Some(first.clone());
        s.busy = false;
        Ok(first)
    }

    pub fn claim(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.claim_inner(directory, source, false, false)
    }

    /// The app/JNI owner must first check the real process/resource guards.
    /// This changes only completed registry metadata, never its retained writer.
    pub fn claim_selected_run(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.claim_inner(directory, source, true, false)
    }

    /// Only after the Native process/resource owner has validated completed close.
    pub fn claim_selected_run_after_completed_close(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.claim_inner(directory, source, true, true)
    }

    fn claim_inner(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
        selected_run: bool,
        completed_owner: bool,
    ) -> Result<Arc<NativeCapability>, String> {
        // Never call Java while holding the Native registry mutex.
        let binding = AppBinding::parse(&source.record()?)?;
        if selected_run && binding.operational_run_id.is_none() {
            return Err("explicit native run binding absent".into());
        }
        let (writer, completed_cap, completed_transition, unbound_run) = {
            let mut state = self
                .state
                .lock()
                .map_err(|_| "native fence registry poisoned")?;
            let prior = state
                .active
                .as_ref()
                .map(|c| &c.binding)
                .or(state.absence_retired.as_ref());
            let completed_transition = selected_run
                && prior.is_some_and(|p| p.operational_run_id != binding.operational_run_id);
            if (state.absence_retired.is_some() && !completed_transition)
                || state.busy
                || state.active.as_ref().is_some_and(|cap| {
                    !cap.retired.load(Ordering::SeqCst)
                        && !(completed_owner && completed_transition)
                })
            {
                return Err("native executor already owned".into());
            }
            if selected_run && prior.is_some() && !completed_transition {
                return Err("completed native run cannot be reinitialized".into());
            }
            let unbound_run = selected_run && prior.is_none() && state.writer.is_none();
            state.busy = true;
            (
                state.writer.clone(),
                state.active.clone(),
                completed_transition,
                unbound_run,
            )
        };
        let mut retained_writer = writer.clone();
        let result = (|| {
            if AppBinding::parse(&source.record()?)? != binding {
                return Err("native claim app binding changed".into());
            }
            let needs_handoff = unbound_run && inspect_unclaimed_counter(directory)?.is_some();
            let writer = match writer {
                Some(writer) => {
                    if !same_inode(
                        &private_metadata(directory, true)?,
                        &writer
                            .directory
                            .metadata()
                            .map_err(|_| "native claim directory stat failed")?,
                    ) || AppBinding::parse(&writer.app_source.record()?)? != binding
                    {
                        return Err("native claim retained writer binding differs".into());
                    }
                    writer
                }
                None => Writer::open(directory, source.clone(), &binding)?,
            };
            let handoff_sha256 = if needs_handoff {
                let raw = source
                    .operational_run_handoff()?
                    .ok_or("native_prior_process_owner_unreconciled")?;
                // Writer::open owns the original Native flock and re-read the
                // permanent record. A stale inspection counter cannot authorize
                // allocation after another writer advanced it.
                writer.observe()?;
                let hash = validate_operational_run_handoff(
                    &raw,
                    &binding,
                    true,
                    writer.counter.load(Ordering::SeqCst),
                )?;
                if AppBinding::parse(&source.record()?)? != binding {
                    return Err("handoff current app channel changed".into());
                }
                writer.observe()?;
                Some(hash)
            } else {
                None
            };
            retained_writer = Some(writer.clone());
            writer.observe()?;
            if completed_transition {
                if binding.generation < writer.app_generation.load(Ordering::SeqCst) {
                    return Err("native app generation regressed".into());
                }
                writer
                    .app_generation
                    .store(binding.generation, Ordering::SeqCst);
            }
            if completed_owner {
                if let Some(cap) = completed_cap {
                    // The live app channel has intentionally moved from the old
                    // completed admission to the new run's pending record. Its
                    // old capability must not be revalidated against new bytes.
                    cap.retired.store(true, Ordering::SeqCst);
                }
            }
            writer.allocate(binding, handoff_sha256)
        })();
        let mut state = self
            .state
            .lock()
            .map_err(|_| "native fence registry poisoned")?;
        state.busy = false;
        if retained_writer.is_some() {
            state.writer = retained_writer;
        }
        if let Ok(cap) = &result {
            state.writer = Some(cap.writer.clone());
            state.active = Some(cap.clone());
            if selected_run {
                state.absence_retired = None;
            }
        }
        result
    }
    pub fn active(&self) -> Result<Arc<NativeCapability>, String> {
        let cap = self
            .state
            .lock()
            .map_err(|_| "native fence registry poisoned")?
            .active
            .clone()
            .ok_or("native process fence not admitted")?;
        cap.require_live()?;
        Ok(cap)
    }
}

#[cfg(test)]
#[path = "process_fence_tests.rs"]
mod tests;

#[cfg(test)]
mod operational_run_tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    struct Source(Mutex<String>);
    impl AppFenceSource for Source {
        fn record(&self) -> Result<String, String> {
            Ok(self.0.lock().unwrap().clone())
        }
    }

    fn directory() -> PathBuf {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "rq-operational-fence-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        path
    }

    fn run_record(run: &str, phase: &str) -> String {
        let body = format!(
            "rusty.quest.embedded_duplex.app_process_fence.v2\n1\n01234567-89ab-cdef-0123-456789abcdef\n{phase}\n-\n-\n{run}\n"
        );
        format!("{body}{}\n", digest(&body))
    }

    #[test]
    fn v2_pending_is_an_actual_run_binding_and_clear_cannot_admit() {
        let run = "ab".repeat(16);
        assert_eq!(
            AppBinding::parse(&run_record(&run, "pending"))
                .unwrap()
                .operational_run_id
                .as_deref(),
            Some(run.as_str())
        );
        assert!(AppBinding::parse(&run_record(&run, "clear")).is_err());
        for invalid in ["AB".repeat(16), "a".repeat(31), "../a".into(), "".into()] {
            assert!(AppBinding::parse(&run_record(&invalid, "pending")).is_err());
        }
        let original = run_record(&run, "pending");
        assert!(AppBinding::parse(&original.replace(&run, &"cd".repeat(16))).is_err());
    }

    #[test]
    fn clear_inspection_never_claims_or_fabricates_a_native_run() {
        let path = directory();
        let registry = NativeFenceRegistry::default();
        let (binding, pending) =
            AppBinding::inspect(&run_record(&"ab".repeat(16), "clear")).unwrap();
        assert!(!pending);
        let observation = registry.inspect_operational_run(&path, &binding).unwrap();
        assert!(observation.actual_binding.is_none());
        assert!(observation.native_generation.is_none());
        assert!(observation.refusal.is_none());
        assert!(!path.join(LOCK_NAME).exists());
        assert!(!path.join(WITNESS_NAME).exists());
    }

    #[test]
    fn new_run_preserves_writer_inode_and_counter_but_never_rebinds_old_callback() {
        let path = directory();
        let a = "ab".repeat(16);
        let b = "cd".repeat(16);
        let source = Arc::new(Source(Mutex::new(run_record(&a, "pending"))));
        let registry = NativeFenceRegistry::default();
        let first = registry.claim_selected_run(&path, source.clone()).unwrap();
        let inode = fs::metadata(path.join(LOCK_NAME)).unwrap().ino();
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        first.retire().unwrap();
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        *source.0.lock().unwrap() = run_record(&b, "clear");
        let (binding, _) = AppBinding::inspect(&source.record().unwrap()).unwrap();
        let observed = registry.inspect_operational_run(&path, &binding).unwrap();
        assert_eq!(
            observed
                .actual_binding
                .unwrap()
                .operational_run_id
                .as_deref(),
            Some(a.as_str())
        );
        assert_eq!(observed.native_generation, Some(first.generation));
        assert!(observed.refusal.is_none());
        *source.0.lock().unwrap() = run_record(&b, "pending");
        let second = registry.claim_selected_run(&path, source.clone()).unwrap();
        assert_eq!(
            second.binding.operational_run_id.as_deref(),
            Some(b.as_str())
        );
        assert_eq!(second.generation, first.generation + 1);
        assert_eq!(fs::metadata(path.join(LOCK_NAME)).unwrap().ino(), inode);
        assert!(first.require_live().is_err());
        second.require_live().unwrap();
        assert!(registry.claim_selected_run(&directory(), source).is_err());
    }

    #[test]
    fn actual_busy_or_poisoned_registry_cannot_be_hidden_by_another_run_id() {
        let path = directory();
        let source = Arc::new(Source(Mutex::new(run_record(&"ab".repeat(16), "pending"))));
        let registry = NativeFenceRegistry::default();
        let cap = registry.claim_selected_run(&path, source.clone()).unwrap();
        let (binding, _) = AppBinding::inspect(&source.record().unwrap()).unwrap();
        assert_eq!(
            registry
                .inspect_operational_run(&path, &binding)
                .unwrap()
                .refusal,
            Some("native_capability_owned")
        );
        cap.retire().unwrap();
        fs::write(path.join(LOCK_NAME), "corrupt").unwrap();
        assert!(registry.inspect_operational_run(&path, &binding).is_err());
        *source.0.lock().unwrap() = run_record(&"cd".repeat(16), "pending");
        assert!(registry.claim_selected_run(&path, source).is_err());
    }

    #[test]
    fn completed_absence_can_move_only_to_an_explicit_different_run() {
        let path = directory();
        let a = "ab".repeat(16);
        let source = Arc::new(Source(Mutex::new(run_record(&a, "pending"))));
        let registry = NativeFenceRegistry::default();
        registry.retire_never_claimed(source.clone()).unwrap();
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        *source.0.lock().unwrap() = run_record(&"cd".repeat(16), "pending");
        let actual = registry.claim_selected_run(&path, source).unwrap();
        assert_eq!(actual.generation, 1);
    }

    #[test]
    fn actual_completed_owner_transition_advances_app_generation_without_replacing_inode() {
        let path = directory();
        let a = "ab".repeat(16);
        let b = "cd".repeat(16);
        let source = Arc::new(Source(Mutex::new(run_record(&a, "pending"))));
        let registry = NativeFenceRegistry::default();
        let old = registry.claim_selected_run(&path, source.clone()).unwrap();
        let inode = fs::metadata(path.join(LOCK_NAME)).unwrap().ino();
        // Model only the Java owner's checked rebind, using the same held source.
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{b}\n"
        );
        *source.0.lock().unwrap() = format!("{body}{}\n", digest(&body));
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        let next = registry
            .claim_selected_run_after_completed_close(&path, source)
            .unwrap();
        assert_eq!(next.binding.generation, 2);
        assert_eq!(next.binding.operational_run_id.as_deref(), Some(b.as_str()));
        assert_eq!(next.generation, old.generation + 1);
        assert_eq!(fs::metadata(path.join(LOCK_NAME)).unwrap().ino(), inode);
        assert!(old.require_live().is_err());
        next.require_live().unwrap();
    }

    #[test]
    fn restarted_v2_counter_without_actual_owner_binding_refuses_inspection_and_claim() {
        let path = directory();
        let source = Arc::new(Source(Mutex::new(run_record(&"ab".repeat(16), "pending"))));
        {
            let registry = NativeFenceRegistry::default();
            let old = registry.claim_selected_run(&path, source.clone()).unwrap();
            old.retire().unwrap();
        }
        let original = fs::read(path.join(LOCK_NAME)).unwrap();
        let restarted = NativeFenceRegistry::default();
        let (binding, _) = AppBinding::inspect(&source.record().unwrap()).unwrap();
        assert_eq!(
            restarted
                .inspect_operational_run(&path, &binding)
                .unwrap()
                .refusal,
            Some("native_prior_process_owner_unreconciled")
        );
        assert!(restarted.claim_selected_run(&path, source.clone()).is_err());
        assert!(restarted
            .claim_selected_run_after_completed_close(&path, source)
            .is_err());
        assert_eq!(fs::read(path.join(LOCK_NAME)).unwrap(), original);
    }

    struct HandoffSource {
        record: Mutex<String>,
        handoff: Mutex<Option<String>>,
    }
    impl AppFenceSource for HandoffSource {
        fn record(&self) -> Result<String, String> {
            Ok(self.record.lock().unwrap().clone())
        }
        fn operational_run_handoff(&self) -> Result<Option<String>, String> {
            Ok(self.handoff.lock().unwrap().clone())
        }
    }

    fn handoff(binding: &AppBinding, counter: u64) -> serde_json::Value {
        let proof = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.remote_owner_cleanup.v1",
            "action":"whole_app_close","config_sha256":"a".repeat(64),"app_process_generation":1,
            "native_executor_generation":counter,"peer_physical_cleanup":"not_locally_started",
            "native_host_physical_cleanup":"terminal","java_own_capture_cleanup":"terminal",
            "java_renderer_cleanup":"terminal","java_control_cleanup":"terminal",
            "app_process_fence_cleanup":"terminal","whole_app_physical_cleanup":"terminal","status":"terminal"});
        serde_json::json!({"$schema":"rusty.quest.embedded_duplex.operational_run_handoff.v1",
            "operational_run_id":binding.operational_run_id,"app_record_sha256":binding.record_sha256,
            "prior_native_generation":counter,"whole_original_id":"1".repeat(32),
            "request_sha256":"2".repeat(64),"outcome_sha256":"3".repeat(64),
            "physical_retirement_sha256":digest(&canonical_handoff_json(&proof).unwrap()),"physical_retirement":proof})
    }

    #[test]
    fn source_whole_handoff_restarts_once_and_exact_retry_preserves_actual_capability() {
        let path = directory();
        let old_body =
            format!("{APP_SCHEMA}\n1\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n");
        let legacy = Arc::new(Source(Mutex::new(format!(
            "{old_body}{}\n",
            digest(&old_body)
        ))));
        {
            let registry = NativeFenceRegistry::default();
            registry.claim(&path, legacy).unwrap().retire().unwrap();
        }
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
            "ab".repeat(16)
        );
        let record = format!("{body}{}\n", digest(&body));
        let binding = AppBinding::parse(&record).unwrap();
        let witness = handoff(&binding, 1).to_string();
        let source = Arc::new(HandoffSource {
            record: Mutex::new(record),
            handoff: Mutex::new(Some(witness.clone())),
        });
        let registry = NativeFenceRegistry::default();
        let cap = registry.claim_selected_run(&path, source.clone()).unwrap();
        assert_eq!(cap.generation, 2);
        assert_eq!(cap.binding, binding);
        let retry = registry
            .retry_selected_run(source.clone())
            .unwrap()
            .unwrap();
        assert!(Arc::ptr_eq(&cap, &retry));
        assert_eq!(retry.generation, 2);
        assert_eq!(
            registry
                .inspect_operational_run(&path, &binding)
                .unwrap()
                .native_generation,
            Some(2)
        );
        *source.handoff.lock().unwrap() = Some(witness.replace(&"2".repeat(64), &"4".repeat(64)));
        assert!(registry.retry_selected_run(source).is_err());
    }

    #[test]
    fn handoff_refuses_wrong_subjects_terminal_fields_and_counter_advance() {
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
            "ab".repeat(16)
        );
        let binding = AppBinding::parse(&format!("{body}{}\n", digest(&body))).unwrap();
        let valid = handoff(&binding, 1);
        assert!(validate_operational_run_handoff(&valid.to_string(), &binding, true, 1).is_ok());
        for (key, bad) in [
            ("operational_run_id", serde_json::json!("cd".repeat(16))),
            ("app_record_sha256", serde_json::json!("f".repeat(64))),
            ("prior_native_generation", serde_json::json!(2)),
            (
                "physical_retirement_sha256",
                serde_json::json!("0".repeat(64)),
            ),
        ] {
            let mut wrong = valid.clone();
            wrong[key] = bad;
            assert!(
                validate_operational_run_handoff(&wrong.to_string(), &binding, true, 1).is_err(),
                "{key}"
            );
        }
        let mut wrong = valid.clone();
        wrong["physical_retirement"]["java_renderer_cleanup"] = serde_json::json!("pending");
        wrong["physical_retirement_sha256"] = serde_json::json!(digest(
            &canonical_handoff_json(&wrong["physical_retirement"]).unwrap()
        ));
        assert!(validate_operational_run_handoff(&wrong.to_string(), &binding, true, 1).is_err());
        assert!(validate_operational_run_handoff(&valid.to_string(), &binding, true, 2).is_err());
        let mut other_target = valid.clone();
        other_target["operational_run_id"] = serde_json::json!("cd".repeat(16));
        assert!(
            validate_operational_run_handoff(&other_target.to_string(), &binding, false, 1).is_ok()
        );
        let mut current_app = valid.clone();
        current_app["physical_retirement"]["app_process_generation"] = serde_json::json!(2);
        current_app["physical_retirement_sha256"] = serde_json::json!(digest(
            &canonical_handoff_json(&current_app["physical_retirement"]).unwrap()
        ));
        assert!(
            validate_operational_run_handoff(&current_app.to_string(), &binding, false, 1).is_ok()
        );
        assert!(
            validate_operational_run_handoff(&current_app.to_string(), &binding, true, 1).is_err()
        );
    }

    #[test]
    fn restarted_v2_owner_accepts_only_its_current_exact_source_release() {
        let path = directory();
        let old = Arc::new(Source(Mutex::new(run_record(&"cd".repeat(16), "pending"))));
        {
            let registry = NativeFenceRegistry::default();
            registry
                .claim_selected_run(&path, old)
                .unwrap()
                .retire()
                .unwrap();
        }
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
            "ab".repeat(16)
        );
        let record = format!("{body}{}\n", digest(&body));
        let binding = AppBinding::parse(&record).unwrap();
        let source = Arc::new(HandoffSource {
            record: Mutex::new(record),
            handoff: Mutex::new(Some(handoff(&binding, 1).to_string())),
        });
        let registry = NativeFenceRegistry::default();
        assert_eq!(
            registry
                .claim_selected_run(&path, source)
                .unwrap()
                .generation,
            2
        );
    }

    #[test]
    fn invalid_source_handoff_never_advances_permanent_counter_or_retains_a_writer() {
        for subject in ["run", "channel", "counter", "witness", "advanced"] {
            let path = directory();
            let old_body =
                format!("{APP_SCHEMA}\n1\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n");
            {
                let old = Arc::new(Source(Mutex::new(format!(
                    "{old_body}{}\n",
                    digest(&old_body)
                ))));
                let registry = NativeFenceRegistry::default();
                registry.claim(&path, old).unwrap().retire().unwrap();
            }
            let body = format!(
                "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
                "ab".repeat(16)
            );
            let record = format!("{body}{}\n", digest(&body));
            let binding = AppBinding::parse(&record).unwrap();
            let mut witness = handoff(&binding, 1);
            match subject {
                "run" => witness["operational_run_id"] = serde_json::json!("cd".repeat(16)),
                "channel" => witness["app_record_sha256"] = serde_json::json!("f".repeat(64)),
                "counter" => witness["prior_native_generation"] = serde_json::json!(2),
                "witness" => {
                    witness["physical_retirement_sha256"] = serde_json::json!("0".repeat(64))
                }
                _ => {
                    let newer = format!("{NATIVE_SCHEMA}\n2\n");
                    fs::write(path.join(LOCK_NAME), format!("{newer}{}\n", digest(&newer)))
                        .unwrap();
                }
            }
            let bytes = fs::read(path.join(LOCK_NAME)).unwrap();
            let source = Arc::new(HandoffSource {
                record: Mutex::new(record),
                handoff: Mutex::new(Some(witness.to_string())),
            });
            let registry = NativeFenceRegistry::default();
            assert!(
                registry.claim_selected_run(&path, source.clone()).is_err(),
                "{subject}"
            );
            assert_eq!(fs::read(path.join(LOCK_NAME)).unwrap(), bytes);
            assert!(registry.state.lock().unwrap().writer.is_none());
            if subject != "advanced" {
                *source.handoff.lock().unwrap() = Some(handoff(&binding, 1).to_string());
                assert_eq!(
                    registry
                        .claim_selected_run(&path, source)
                        .unwrap()
                        .generation,
                    2
                );
            }
        }
    }
}
