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
const NATIVE_SCHEMA: &str = "rusty.quest.embedded_duplex.native_process_fence.v1";
const LOCK_NAME: &str = "native-process-fence.v1.lock";
const WITNESS_NAME: &str = "native-process-fence.v1.initialized";

/// Internal live app-channel reader, never an operator-supplied assertion.
pub(super) trait AppFenceSource: Send + Sync {
    fn record(&self) -> Result<String, String>;
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct AppBinding {
    pub generation: u64,
    pub record_sha256: String,
}
impl AppBinding {
    fn parse(record: &str) -> Result<Self, String> {
        let fields: Vec<_> = record.split('\n').collect();
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
            || fields.len() != 8
            || fields[0] != APP_SCHEMA
            || fields[1].starts_with('0')
            || !fields[1].bytes().all(|b| b.is_ascii_digit())
            || !uuid(fields[2])
            || fields[3] != "pending"
            || ![fields[4], fields[5]].iter().all(|v| *v == "-" || hex(v))
            || !hex(fields[6])
            || fields[6] != digest(&(fields[..6].join("\n") + "\n"))
            || !fields[7].is_empty()
        {
            return Err("native app fence binding invalid or recovery unsupported".into());
        }
        let generation: u64 = fields[1]
            .parse()
            .map_err(|_| "native app generation invalid")?;
        if generation == 0 || generation > i64::MAX as u64 {
            return Err("native app generation invalid".into());
        }
        Ok(Self {
            generation,
            record_sha256: digest(record),
        })
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
    app_generation: u64,
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
            app_generation: binding.generation,
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
    fn allocate(self: &Arc<Self>, binding: AppBinding) -> Result<Arc<NativeCapability>, String> {
        self.observe()?;
        if binding.generation != self.app_generation {
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
}
#[derive(Default)]
pub(super) struct NativeFenceRegistry {
    state: Mutex<RegistryState>,
}
impl NativeFenceRegistry {
    pub fn claim(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Arc<NativeCapability>, String> {
        let writer = {
            let mut state = self
                .state
                .lock()
                .map_err(|_| "native fence registry poisoned")?;
            if state.busy
                || state
                    .active
                    .as_ref()
                    .is_some_and(|cap| !cap.retired.load(Ordering::SeqCst))
            {
                return Err("native executor already owned".into());
            }
            state.busy = true;
            state.writer.clone()
        };
        let mut retained_writer = writer.clone();
        let result = (|| {
            let binding = AppBinding::parse(&source.record()?)?;
            let writer = match writer {
                Some(writer) => writer,
                None => Writer::open(directory, source, &binding)?,
            };
            retained_writer = Some(writer.clone());
            writer.allocate(binding)
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
