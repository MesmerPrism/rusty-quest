//! Optional trusted-development transport for only the pure strength-phase step.
//! File refresh/activation runs on the render owner before the phase mutex.
use crate::{feedback::Snapshot, strength_cycle_adapter::StepSource, strength_cycle_step};
use serde_json::{json, Map, Value};
use std::{
    cell::RefCell,
    collections::BTreeSet,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::OnceLock,
    thread::ThreadId,
    time::{Duration, Instant},
};

const COMMAND_LIMIT: usize = 512;
const ROOT: &str = "development-feedback-strength-phase";
#[derive(Clone)]
struct Configuration {
    debuggable: bool,
    data: PathBuf,
    session: String,
}
static CONFIGURATION: OnceLock<Configuration> = OnceLock::new();
static OWNER_THREAD: OnceLock<ThreadId> = OnceLock::new();
thread_local! { static OWNER: RefCell<Option<Result<Owner, String>>> = const { RefCell::new(None) }; }

// No Context/Activity, JNI global reference, authority or native handle is retained.
fn configure(debuggable: bool, data: PathBuf, session: String) -> Result<(), String> {
    if CONFIGURATION.get().is_some() {
        return Ok(());
    }
    if !data.is_absolute()
        || session.len() != 32
        || !session
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return Err("application setup shape".into());
    }
    let data = fs::canonicalize(data).map_err(|_| "application private directory")?;
    let _ = CONFIGURATION.set(Configuration {
        debuggable,
        data,
        session,
    });
    Ok(())
}

/// Selected separately from the JSON control profile and private input policy.
pub(crate) fn refresh(explicitly_selected: bool) {
    refresh_configuration(CONFIGURATION.get(), explicitly_selected);
}
fn refresh_configuration(config: Option<&Configuration>, explicitly_selected: bool) {
    let Some(config) = config.filter(|config| config.debuggable && explicitly_selected) else {
        strength_cycle_step::set_enabled(false);
        return;
    };
    let thread = std::thread::current().id();
    if *OWNER_THREAD.get_or_init(|| thread) != thread {
        strength_cycle_step::set_enabled(false);
        return;
    }
    OWNER.with(|slot| {
        let mut slot = slot.borrow_mut();
        let owner = slot.get_or_insert_with(|| Owner::new(config));
        match owner {
            Ok(owner) => {
                strength_cycle_step::set_enabled(true);
                owner.tick();
            }
            Err(_) => strength_cycle_step::set_enabled(false),
        }
    });
}

#[derive(Clone, Debug)]
struct Command {
    id: String,
    method: String,
    arguments: Map<String, Value>,
}
impl Command {
    fn parse(bytes: &[u8], expected_id: &str) -> Result<Self, String> {
        if bytes.is_empty() || bytes.len() > 4096 {
            return Err("command size".into());
        }
        let value: Value = serde_json::from_slice(bytes).map_err(|_| "command JSON")?;
        reject_duplicate_json_keys(bytes)?;
        let object = value.as_object().ok_or("command object")?;
        if object.len() != 4
            || object
                .keys()
                .any(|k| !["schema", "request_id", "method", "arguments"].contains(&k.as_str()))
        {
            return Err("command fields".into());
        }
        if value["schema"] != "rusty.quest.hotload_command.v1"
            || value["request_id"] != expected_id
            || !valid_id(expected_id)
        {
            return Err("command identity".into());
        }
        let method = value["method"].as_str().ok_or("command method")?.to_owned();
        let arguments = value["arguments"]
            .as_object()
            .ok_or("command arguments")?
            .clone();
        if arguments.values().any(|v| !v.is_string()) {
            return Err("string arguments required".into());
        }
        Ok(Self {
            id: expected_id.into(),
            method,
            arguments,
        })
    }
    fn exact(&self, names: &[&str]) -> Result<(), String> {
        if self.arguments.len() != names.len()
            || self.arguments.keys().any(|k| !names.contains(&k.as_str()))
        {
            return Err("exact method arguments required".into());
        }
        Ok(())
    }
    fn arg(&self, name: &str) -> &str {
        self.arguments[name].as_str().unwrap_or("")
    }
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}
fn canonical_generation(raw: &str) -> Result<u64, String> {
    let value = raw.parse::<u64>().map_err(|_| "generation")?;
    if value == 0 || value.to_string() != raw {
        return Err("canonical generation".into());
    }
    Ok(value)
}
fn canonical_i64(raw: &str) -> Result<i64, String> {
    let value = raw.parse::<i64>().map_err(|_| "probe result")?;
    if value.to_string() != raw {
        return Err("canonical probe result".into());
    }
    Ok(value)
}

// Same bounded duplicate-key admission as the existing app-owned hotload transport.
fn reject_duplicate_json_keys(bytes: &[u8]) -> Result<(), String> {
    let mut scopes: Vec<Option<BTreeSet<String>>> = Vec::new();
    let mut cursor = 0;
    while cursor < bytes.len() {
        match bytes[cursor] {
            b'{' => scopes.push(Some(BTreeSet::new())),
            b'[' => scopes.push(None),
            b'}' | b']' => {
                scopes.pop();
            }
            b'"' => {
                let start = cursor;
                cursor += 1;
                while cursor < bytes.len() {
                    if bytes[cursor] == b'\\' {
                        cursor += 2;
                        continue;
                    }
                    if bytes[cursor] == b'"' {
                        break;
                    }
                    cursor += 1;
                }
                let end = cursor + 1;
                let mut next = end;
                while next < bytes.len() && bytes[next].is_ascii_whitespace() {
                    next += 1;
                }
                if bytes.get(next) == Some(&b':') {
                    let key: String =
                        serde_json::from_slice(&bytes[start..end]).map_err(|_| "command key")?;
                    if let Some(Some(keys)) = scopes.last_mut() {
                        if !keys.insert(key) {
                            return Err("duplicate command key".into());
                        }
                    }
                }
            }
            _ => {}
        }
        cursor += 1;
    }
    Ok(())
}

struct Owner {
    directory: PathBuf,
    session: String,
    owner: String,
    seen: BTreeSet<String>,
    next_poll: Instant,
}
impl Owner {
    fn new(config: &Configuration) -> Result<Self, String> {
        let root = config.data.join(ROOT);
        fs::create_dir_all(&root).map_err(|_| "session root")?;
        if fs::symlink_metadata(&root)
            .map_err(|_| "session metadata")?
            .file_type()
            .is_symlink()
        {
            return Err("session alias".into());
        }
        let session = format!("{}-{}", std::process::id(), config.session);
        let directory = root.join(&session);
        fs::create_dir(&directory).map_err(|_| "fresh session directory")?;
        let owner = format!("strength-phase-render-owner-{session}");
        atomic_json(
            &root,
            "current-session.json",
            &json!({"schema":"rusty.quest.hotload_current_session.v1","host_kind":"bilateral-strength-phase","session_id":session,"owner_token":owner,"pid":std::process::id(),"directory":session}),
        )?;
        Ok(Self {
            directory,
            session,
            owner,
            seen: BTreeSet::new(),
            next_poll: Instant::now(),
        })
    }
    fn tick(&mut self) {
        if self.seen.len() >= COMMAND_LIMIT || Instant::now() < self.next_poll {
            return;
        }
        self.next_poll = Instant::now() + Duration::from_millis(100);
        let Ok(entries) = fs::read_dir(&self.directory) else {
            return;
        };
        let mut commands: Vec<_> = entries
            .take(2048)
            .filter_map(Result::ok)
            .filter_map(|entry| {
                let name = entry.file_name().into_string().ok()?;
                let id = name.strip_prefix("command-")?.strip_suffix(".json")?;
                if !valid_id(id) || self.seen.contains(id) {
                    return None;
                }
                Some((id.to_owned(), entry.path()))
            })
            .collect();
        commands.sort_by(|a, b| a.0.cmp(&b.0));
        if let Some((id, path)) = commands.into_iter().next() {
            self.seen.insert(id.clone()); // Malformed commands are consumed once too.
            if self
                .directory
                .join(format!("observation-{id}.json"))
                .exists()
            {
                return;
            }
            let parsed = (|| {
                let metadata = fs::symlink_metadata(&path).map_err(|_| "command metadata")?;
                if !metadata.is_file() || metadata.file_type().is_symlink() || metadata.len() > 4096
                {
                    return Err("command file type/size".into());
                }
                let mut bytes = Vec::new();
                fs::File::open(&path)
                    .map_err(|_| "command open")?
                    .take(4097)
                    .read_to_end(&mut bytes)
                    .map_err(|_| "command read")?;
                Command::parse(&bytes, &id)
            })();
            let result = parsed.and_then(|command| self.dispatch(command));
            let _ = atomic_json(
                &self.directory,
                &format!("observation-{id}.json"),
                &self.observation(&id, result),
            );
        }
    }
    fn dispatch(&self, command: Command) -> Result<Option<Snapshot>, String> {
        debug_assert!(self.seen.contains(&command.id));
        match command.method.as_str() {
            "native_status" => {
                command.exact(&[])?;
                Ok(strength_cycle_step::snapshot())
            }
            "native_activate" => {
                command.exact(&["generation", "sha256", "probe_result"])?;
                let generation = canonical_generation(command.arg("generation"))?;
                let probe = canonical_i64(command.arg("probe_result"))?;
                let sha = command.arg("sha256");
                if sha.len() != 64
                    || !sha
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                {
                    return Err("lowercase SHA256 required".into());
                }
                strength_cycle_step::activate(
                    &self.directory.join(format!("generation-{generation}.so")),
                    sha,
                    generation,
                    probe,
                )
                .map(Some)
            }
            _ => Err("method unsupported by strength phase host".into()),
        }
    }
    fn observation(&self, id: &str, result: Result<Option<Snapshot>, String>) -> Value {
        let (status, error, host) = match result {
            Ok(snapshot) => ("ok", None, snapshot),
            Err(error) => ("error", Some(error), strength_cycle_step::snapshot()),
        };
        let phase = strength_cycle_step::last_outcome();
        let source = match phase.source {
            StepSource::Module => "module",
            StepSource::NoHost => "no_host",
            StepSource::InvalidInput => "invalid_input",
            StepSource::HostError => "host_error",
            StepSource::InvalidOutput => "invalid_output",
        };
        let host = host.map(|s| json!({"generation":s.generation,"completed":s.completed,"value":s.value,"buffer":s.buffer,"allocation_token":s.allocation_token,"retained_images":s.retained_images,"attempts":s.attempts,"staged_bytes":s.staged_bytes,"active_sha256":s.active_sha256}));
        json!({"schema":"rusty.quest.bilateral_strength_phase_observation.v1","request_id":id,"session_id":self.session,"owner_token":self.owner,"pid":std::process::id(),"status":status,"error":error,"host":host,"phase":{"source":source,"effective_phase":phase.phase,"adopted_module_generation":strength_cycle_step::adopted_module_generation(),"phase_call_count":strength_cycle_step::phase_call_count()}})
    }
}
fn atomic_json(directory: &Path, name: &str, value: &Value) -> Result<(), String> {
    let temporary = directory.join(format!(".{name}.new"));
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .map_err(|_| "observation create")?;
    file.write_all(&serde_json::to_vec(value).map_err(|_| "observation JSON")?)
        .map_err(|_| "observation write")?;
    file.sync_all().map_err(|_| "observation sync")?;
    drop(file);
    fs::rename(temporary, directory.join(name)).map_err(|_| "observation publish".into())
}

/// Cache genuine app identity once; recreating an Activity never resets render state.
#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeConfigureStrengthPhaseHotload(
    mut env: jni::JNIEnv,
    _activity: jni::objects::JObject,
    context: jni::objects::JObject,
) {
    if CONFIGURATION.get().is_some() {
        return;
    }
    let result = (|| -> Result<(), String> {
        let context = env
            .call_method(
                context,
                "getApplicationContext",
                "()Landroid/content/Context;",
                &[],
            )
            .map_err(|_| "application context")?
            .l()
            .map_err(|_| "application context type")?;
        let info = env
            .call_method(
                &context,
                "getApplicationInfo",
                "()Landroid/content/pm/ApplicationInfo;",
                &[],
            )
            .map_err(|_| "application info")?
            .l()
            .map_err(|_| "application info type")?;
        let flags = env
            .get_field(info, "flags", "I")
            .map_err(|_| "application flags")?
            .i()
            .map_err(|_| "application flags type")?;
        let directory = env
            .call_method(&context, "getNoBackupFilesDir", "()Ljava/io/File;", &[])
            .map_err(|_| "private directory")?
            .l()
            .map_err(|_| "private directory type")?;
        let path = env
            .call_method(directory, "getAbsolutePath", "()Ljava/lang/String;", &[])
            .map_err(|_| "private path")?
            .l()
            .map_err(|_| "private path type")?;
        let path: String = env
            .get_string(&jni::objects::JString::from(path))
            .map_err(|_| "private path string")?
            .into();
        let uuid = env
            .call_static_method("java/util/UUID", "randomUUID", "()Ljava/util/UUID;", &[])
            .map_err(|_| "session random")?
            .l()
            .map_err(|_| "session random type")?;
        let uuid = env
            .call_method(uuid, "toString", "()Ljava/lang/String;", &[])
            .map_err(|_| "session string")?
            .l()
            .map_err(|_| "session string type")?;
        let session: String = env
            .get_string(&jni::objects::JString::from(uuid))
            .map_err(|_| "session encoding")?
            .into();
        configure(
            flags & 0x2 != 0,
            PathBuf::from(path),
            session.replace('-', ""),
        )
    })();
    if result.is_err() && env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}
#[cfg(test)]
#[path = "../../../../tools/checks/fixtures/strength_cycle_transport_tests.rs"]
mod tests;
