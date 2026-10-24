//! Explicit developer-only file/UI transport. The native frame thread owns all loaded code.
use crate::development_feedback_contract::{
    canonical_generation, canonical_i64, valid_request_id, valid_sha256,
};
use crate::development_feedback_core::{FeedbackHost, Snapshot};
use crate::development_feedback_shader::{self, Stage};
use serde_json::{json, Map, Value};
use std::{
    collections::{BTreeSet, VecDeque},
    fs,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
const COMMAND_LIMIT: usize = 512;
pub(crate) struct ShaderCandidate {
    pub(crate) generation: u64,
    pub(crate) vertex: Vec<u8>,
    pub(crate) fragment: Vec<u8>,
}
type ShaderActivator<'a> = dyn FnMut(ShaderCandidate) -> Result<(), String> + 'a;

pub(crate) fn selected() -> bool {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_DEVELOPMENT_FEEDBACK_ENABLED") == Some("true")
}

#[derive(Clone, Debug)]
pub(crate) struct Command {
    pub(crate) id: String,
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
            || !valid_request_id(expected_id)
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

// serde_json validates syntax above; this bounded lexical pass additionally rejects
// repeated decoded object keys instead of silently accepting a last-writer value.
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

#[cfg(any(native_meta_xr_operator,test))]
#[derive(Default)]
struct UiEvidence { queued:String, completed:String, count:u64, pending:BTreeSet<String> }
#[cfg(any(native_meta_xr_operator,test))]
impl UiEvidence {
    fn enqueue(&mut self,id:&str){self.queued=id.into();self.pending.insert(id.into());}
    fn complete(&mut self,id:&str,passed:bool){if self.pending.remove(id)&&passed {self.completed=id.into();self.count=self.count.saturating_add(1);}}
    fn project(&self,state:&mut Value){state["original_ui_queued_request_id"]=json!(self.queued);state["original_ui_completed_request_id"]=json!(self.completed);state["original_ui_completed_count"]=json!(self.count.to_string());}
}

struct UiQueue {
    #[cfg(native_meta_xr_operator)]
    evidence: UiEvidence,
    generation: u64,
    commands: VecDeque<Command>,
    sequence: u64,
    status: String,
}
static UI: OnceLock<Mutex<UiQueue>> = OnceLock::new();
fn ui() -> &'static Mutex<UiQueue> {
    UI.get_or_init(|| {
        Mutex::new(UiQueue {
            #[cfg(native_meta_xr_operator)]
            evidence: UiEvidence::default(),
            generation: 0,
            commands: VecDeque::new(),
            sequence: 0,
            status: "Native feedback not selected".into(),
        })
    })
}

pub(crate) fn enqueue_ui_compute(generation: u64) -> bool {
    if !selected() {
        return false;
    }
    let mut state = ui().lock().unwrap_or_else(|p| p.into_inner());
    if generation == 0
        || generation != state.generation
        || state.commands.len() >= 4
        || state.sequence >= COMMAND_LIMIT as u64
    {
        return false;
    }
    state.sequence += 1;
    let id = format!("ui-compute-{}", state.sequence);
    #[cfg(native_meta_xr_operator)]
    state.evidence.enqueue(&id);
    state.commands.push_back(Command {
        id,
        method: "native_compute".into(),
        arguments: Map::from_iter([("input".into(), Value::String("4".into()))]),
    });
    true
}
pub(crate) fn ui_status(generation: u64) -> String {
    let state = ui().lock().unwrap_or_else(|p| p.into_inner());
    if generation == 0 || generation != state.generation {
        "Native feedback owner unavailable".into()
    } else {
        state.status.clone()
    }
}

pub(crate) struct NativeDevelopmentFeedback {
    host: FeedbackHost,
    directory: PathBuf,
    session: String,
    owner: String,
    ui_generation: u64,
    seen: BTreeSet<String>,
    pending: Vec<(String, Value)>,
    next_poll: Instant,
    pub(crate) value: i64,
    pub(crate) generation: u64,
    completed: u64,
    pub(crate) visual_value: i64,
    visual_delta: String,
    visual_generation: u64,
    shader_generations: BTreeSet<u64>,
    shader_generation: u64,
    shader_vertex_sha256: String,
    shader_fragment_sha256: String,
    shader_staged_bytes: u64,
}
impl NativeDevelopmentFeedback {
    pub(crate) fn new(data: &Path, ui_generation: u64) -> Result<Self, String> {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "session clock")?
            .as_nanos();
        let session = format!("{}-{nanos:x}", std::process::id());
        let owner = format!("native-render-owner-{session}");
        let root = fs::canonicalize(data)
            .map_err(|e| e.to_string())?
            .join("development-feedback-native");
        fs::create_dir_all(&root).map_err(|e| e.to_string())?;
        let directory = root.join(&session);
        fs::create_dir(&directory).map_err(|e| e.to_string())?;
        atomic_json(
            &root,
            "current-session.json",
            &json!({"schema":"rusty.quest.hotload_current_session.v1","host_kind":"native-openxr","session_id":session,"owner_token":owner,"pid":std::process::id(),"directory":session}),
        )?;
        {
            let mut state = ui().lock().unwrap_or_else(|p| p.into_inner());
            *state = UiQueue {
                #[cfg(native_meta_xr_operator)]
                evidence: UiEvidence::default(),
                generation: ui_generation,
                commands: VecDeque::new(),
                sequence: 0,
                status: format!("Native CPU hotload ready; session {session}"),
            };
        }
        Ok(Self {
            host: FeedbackHost::new(),
            directory,
            session,
            owner,
            ui_generation,
            seen: BTreeSet::new(),
            pending: Vec::new(),
            next_poll: Instant::now(),
            value: 0,
            generation: 0,
            completed: 0,
            visual_value: 0,
            visual_delta: "0".into(),
            visual_generation: 0,
            shader_generations: BTreeSet::new(),
            shader_generation: 0,
            shader_vertex_sha256: String::new(),
            shader_fragment_sha256: String::new(),
            shader_staged_bytes: 0,
        })
    }
    pub(crate) fn tick(&mut self, frame: u64) {
        self.tick_with_shader(frame, &mut |_| {
            Err("shader replacement unavailable in this host".into())
        });
    }
    pub(crate) fn tick_with_shader(&mut self, frame: u64, shader: &mut ShaderActivator<'_>) {
        if self.seen.len() >= COMMAND_LIMIT {
            return;
        }
        let queued = {
            let mut state = ui().lock().unwrap_or_else(|p| p.into_inner());
            if state.generation == self.ui_generation {
                state.commands.pop_front()
            } else {
                None
            }
        };
        if let Some(command) = queued {
            self.execute(command, frame, shader);
            return;
        }
        if Instant::now() < self.next_poll {
            return;
        }
        self.next_poll = Instant::now() + Duration::from_millis(100);
        let Ok(entries) = fs::read_dir(&self.directory) else {
            return;
        };
        let mut files: Vec<_> = entries
            .take(2048)
            .filter_map(Result::ok)
            .filter_map(|entry| {
                let name = entry.file_name().into_string().ok()?;
                let id = name.strip_prefix("command-")?.strip_suffix(".json")?;
                if !valid_request_id(id) || self.seen.contains(id) {
                    return None;
                }
                Some((id.to_owned(), entry.path()))
            })
            .collect();
        files.sort_by(|a, b| a.0.cmp(&b.0));
        if let Some((id, path)) = files.into_iter().next() {
            // Consume malformed commands once too; no repeated side effects or error loops.
            self.seen.insert(id.clone());
            let parsed = fs::symlink_metadata(&path)
                .map_err(|_| "command metadata".to_owned())
                .and_then(|metadata| {
                    if !metadata.is_file()
                        || metadata.file_type().is_symlink()
                        || metadata.len() > 4096
                    {
                        return Err("command file type/size".into());
                    }
                    Command::parse(&fs::read(&path).map_err(|_| "command read")?, &id)
                });
            match parsed {
                Ok(command) => self.dispatch(command, frame, shader),
                Err(error) => self.record(id, Err(error), frame),
            }
        }
    }
    fn execute(&mut self, command: Command, frame: u64, shader: &mut ShaderActivator<'_>) {
        if !self.seen.insert(command.id.clone()) {
            return;
        }
        self.dispatch(command, frame, shader);
    }
    fn dispatch(&mut self, command: Command, frame: u64, shader: &mut ShaderActivator<'_>) {
        #[cfg(native_meta_xr_operator)]
        let status_request=command.method=="native_status";
        let result = (|| match command.method.as_str() {
            "native_status" => {
                command.exact(&[])?;
                self.host.snapshot().map_err(|e| e.to_string())
            }
            "native_compute" => {
                command.exact(&["input"])?;
                let input = canonical_i64(command.arg("input"))?;
                self.host.compute(input).map_err(|e| e.to_string())
            }
            "native_activate" => {
                command.exact(&["generation", "sha256", "probe_result"])?;
                let generation = canonical_generation(command.arg("generation"))?;
                let expected = canonical_i64(command.arg("probe_result"))?;
                if !valid_sha256(command.arg("sha256")) {
                    return Err("lowercase SHA256 required".into());
                }
                self.host
                    .activate_immutable(
                        &self.directory.join(format!("generation-{generation}.so")),
                        command.arg("sha256"),
                        generation,
                        expected,
                    )
                    .map_err(|e| e.to_string())
            }
            "native_shader_activate" => {
                command.exact(&["generation", "vertex_sha256", "fragment_sha256"])?;
                let generation = canonical_generation(command.arg("generation"))?;
                if self.shader_generations.len() >= 32 {
                    return Err("shader attempt limit; restart host".into());
                }
                if !self.shader_generations.insert(generation)
                    || generation <= self.shader_generation
                {
                    return Err("shader generation already attempted/stale".into());
                }
                let vertex = immutable_shader(
                    &self.directory.join(format!("shader-{generation}.vert.spv")),
                    command.arg("vertex_sha256"),
                )?;
                let fragment = immutable_shader(
                    &self.directory.join(format!("shader-{generation}.frag.spv")),
                    command.arg("fragment_sha256"),
                )?;
                let bytes = (vertex.len() + fragment.len()) as u64;
                self.shader_staged_bytes = self
                    .shader_staged_bytes
                    .checked_add(bytes)
                    .ok_or("shader byte overflow")?;
                if self.shader_staged_bytes > 16 * 1024 * 1024 {
                    return Err("shader staged byte limit; restart host".into());
                }
                development_feedback_shader::validate(&vertex, Stage::Vertex)?;
                development_feedback_shader::validate(&fragment, Stage::Fragment)?;
                let snapshot = self.host.snapshot().map_err(|e| e.to_string())?;
                shader(ShaderCandidate {
                    generation,
                    vertex,
                    fragment,
                })?;
                self.shader_generation = generation;
                self.shader_vertex_sha256 = command.arg("vertex_sha256").into();
                self.shader_fragment_sha256 = command.arg("fragment_sha256").into();
                Ok(snapshot)
            }
            _ => Err("method unsupported by native host".into()),
        })();
        self.record(command.id, result, frame);
        #[cfg(native_meta_xr_operator)]
        if status_request {
            if let Some((_,observation))=self.pending.last_mut(){observation["scene"]["operator_layer_loaded"]=json!(crate::native_input_geometry::operator_layer_loaded());}
        }
    }
    fn state(&self, snapshot: &Snapshot) -> Value {
        json!({"native_state":if snapshot.generation == 0 {"uninitialized"} else {"ready"},"native_session_id":self.session,"native_owner_token":self.owner,"native_generation":snapshot.generation.to_string(),"native_generation_sha256":snapshot.active_sha256,"native_completed_unsigned":snapshot.completed.to_string(),"native_value":snapshot.value.to_string(),"native_allocation":snapshot.allocation_token.to_string(),"native_buffer_hex":snapshot.buffer.iter().map(|b|format!("{b:02x}")).collect::<String>(),"native_retained_images":snapshot.retained_images.to_string(),"native_attempts":snapshot.attempts.to_string(),"native_staged_bytes":snapshot.staged_bytes.to_string(),"native_command_limit":COMMAND_LIMIT.to_string(),"native_commands_consumed":self.seen.len().to_string(),"native_restart_required":(self.seen.len()>=COMMAND_LIMIT).to_string()})
    }
    fn record(&mut self, id: String, result: Result<Snapshot, String>, frame: u64) {
        let error = result.as_ref().err().cloned();
        let snapshot = result.ok().or_else(|| self.host.snapshot().ok());
        let mut state = snapshot.as_ref().map(|s| self.state(s)).unwrap_or_else(|| json!({"native_state":"failed","native_session_id":self.session,"native_owner_token":self.owner}));
        for (key, value) in [
            (
                "native_shader_generation",
                self.shader_generation.to_string(),
            ),
            (
                "native_shader_vertex_sha256",
                self.shader_vertex_sha256.clone(),
            ),
            (
                "native_shader_fragment_sha256",
                self.shader_fragment_sha256.clone(),
            ),
            (
                "native_shader_attempts",
                self.shader_generations.len().to_string(),
            ),
            (
                "native_shader_staged_bytes",
                self.shader_staged_bytes.to_string(),
            ),
        ] {
            state[key] = Value::String(value);
        }
        if let Some(snapshot) = snapshot {
            if snapshot.completed > self.completed {
                let delta = i128::from(snapshot.value) - i128::from(self.value);
                self.visual_value = delta.rem_euclid(40) as i64;
                self.visual_delta = delta.to_string();
                self.visual_generation = snapshot.generation;
            }
            self.value = snapshot.value;
            self.generation = snapshot.generation;
            self.completed = snapshot.completed;
        }
        self.pending.push((id.clone(), json!({"schema":"rusty.quest.hotload_observation.v1","request_id":id,"host_kind":"native-openxr","status":if error.is_some(){"refused"}else{"pass"},"error":error,"state":state,"scene":{"owner":self.owner,"process_id":std::process::id(),"dispatch_frame":frame,"submitted":false}})));
    }
    pub(crate) fn submitted(&mut self, frame: u64) {
        for (id, mut observation) in self.pending.drain(..) {
            #[cfg(native_meta_xr_operator)]
            {
                let mut queue=ui().lock().unwrap_or_else(|p|p.into_inner());
                if queue.generation==self.ui_generation {
                    queue.evidence.complete(&id,observation["status"]=="pass");
                    queue.evidence.project(&mut observation["state"]);
                }
                if let Some(geometry)=crate::native_input_geometry::readback_current(&self.owner,&self.session){observation["scene"]["input_geometry"]=geometry;}
            }
            observation["scene"]["submitted"] = Value::Bool(true);
            observation["scene"]["evidence"] = json!("native-openxr-end-frame");
            observation["scene"]["submitted_frame"] = json!(frame);
            observation["scene"]["effective_generation"] = json!(self.visual_generation);
            observation["scene"]["effective_value"] = json!(self.value);
            observation["scene"]["effective_result_delta"] = json!(self.visual_delta);
            observation["scene"]["visual_value_mod40"] = json!(self.visual_value);
            observation["scene"]["effective_shader_generation"] = json!(self.shader_generation);
            let _result = atomic_json(
                &self.directory,
                &format!("observation-{id}.json"),
                &observation,
            );
            let mut state = ui().lock().unwrap_or_else(|p| p.into_inner());
            if state.generation == self.ui_generation {
                state.status = format!(
                    "{} • generation {} • value {} • frame {} • {}",
                    observation["status"].as_str().unwrap_or("error"),
                    self.generation,
                    self.value,
                    frame,
                    observation["state"]["native_completed_unsigned"]
                        .as_str()
                        .unwrap_or("?")
                );
            }
            #[cfg(target_os = "android")]
            crate::marker(
                "development-feedback",
                format!(
                    "request={} generation={} value={} submittedFrame={} observationWritten={}",
                    id,
                    self.generation,
                    self.value,
                    frame,
                    _result.is_ok()
                ),
            );
        }
    }
}
fn immutable_shader(path: &Path, expected: &str) -> Result<Vec<u8>, String> {
    if !valid_sha256(expected) {
        return Err("lowercase shader SHA256 required".into());
    }
    for parent in path.ancestors() {
        if fs::symlink_metadata(parent)
            .map_err(|_| "shader path metadata")?
            .file_type()
            .is_symlink()
        {
            return Err("shader path alias".into());
        }
    }
    let metadata = fs::symlink_metadata(path).map_err(|_| "shader file metadata")?;
    if !metadata.is_file()
        || !metadata.permissions().readonly()
        || metadata.len() == 0
        || metadata.len() > 256 * 1024
    {
        return Err("readonly regular shader within byte limit required".into());
    }
    let bytes = fs::read(path).map_err(|_| "shader read")?;
    if crate::digest::sha256(&bytes) != expected {
        return Err("shader SHA256".into());
    }
    Ok(bytes)
}
fn atomic_json(directory: &Path, name: &str, value: &Value) -> Result<(), String> {
    use std::io::Write;
    let temporary = directory.join(format!(".{name}.new"));
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .map_err(|e| e.to_string())?;
    file.write_all(&serde_json::to_vec(value).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    file.sync_all().map_err(|e| e.to_string())?;
    drop(file);
    fs::rename(temporary, directory.join(name)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_ui_evidence_requires_a_real_queue_entry_and_successful_completion() {
        let mut evidence=UiEvidence::default();
        evidence.complete("ui-compute-1",true);assert_eq!(evidence.count,0);
        evidence.enqueue("ui-compute-1");assert!(evidence.completed.is_empty());
        evidence.complete("ui-compute-1",true);assert_eq!(evidence.count,1);
        evidence.complete("ui-compute-1",true);assert_eq!(evidence.count,1);
        evidence.enqueue("ui-compute-2");evidence.complete("ui-compute-2",false);
        assert_eq!(evidence.completed,"ui-compute-1");assert_eq!(evidence.count,1);
    }

    #[test]
    fn closed_command_shape_rejects_extra_fields_and_nonstring_arguments() {
        let good = br#"{"schema":"rusty.quest.hotload_command.v1","request_id":"a-1","method":"native_compute","arguments":{"input":"4"}}"#;
        let command = Command::parse(good, "a-1").unwrap();
        assert!(command.exact(&["input"]).is_ok());
        assert!(Command::parse(good, "a-2").is_err());
        let bad = String::from_utf8(good.to_vec())
            .unwrap()
            .replace("\"4\"", "4");
        assert!(Command::parse(bad.as_bytes(), "a-1").is_err());
        assert!(command.exact(&[]).is_err());
        for duplicate in [
            r#"{"input":"1","input":"4"}"#,
            r#"{"input":"1","inpu\u0074":"4"}"#,
        ] {
            let bad = String::from_utf8(good.to_vec())
                .unwrap()
                .replace(r#"{"input":"4"}"#, duplicate);
            assert!(Command::parse(bad.as_bytes(), "a-1").is_err());
        }
    }

    #[test]
    fn fresh_session_refuses_unsupported_dispatch_and_never_replays_old_commands() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let data = std::env::temp_dir().join(format!(
            "rq-native-feedback-test-{}-{nonce}",
            std::process::id()
        ));
        fs::create_dir(&data).unwrap();
        let mut first = NativeDevelopmentFeedback::new(&data, 91).unwrap();
        let command = json!({"schema":"rusty.quest.hotload_command.v1","request_id":"unsupported-1","method":"native_init","arguments":{"sha256":"a".repeat(64)}});
        atomic_json(&first.directory, "command-unsupported-1.json", &command).unwrap();
        first.tick(1);
        assert_eq!(first.pending.len(), 1);
        first.submitted(1);
        let observation: Value = serde_json::from_slice(
            &fs::read(first.directory.join("observation-unsupported-1.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(observation["status"], "refused");
        assert_eq!(observation["state"]["native_completed_unsigned"], "0");
        assert_eq!(observation["scene"]["submitted_frame"], 1);
        first.next_poll = Instant::now();
        first.tick(2);
        assert!(first.pending.is_empty());
        let mut second = NativeDevelopmentFeedback::new(&data, 92).unwrap();
        assert_ne!(first.session, second.session);
        assert_ne!(first.directory, second.directory);
        second.tick(3);
        assert!(second.pending.is_empty());
        assert_eq!(second.host.snapshot().unwrap().completed, 0);
        let current: Value = serde_json::from_slice(
            &fs::read(data.join("development-feedback-native/current-session.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(current["session_id"], second.session);
        assert!(first
            .directory
            .join("observation-unsupported-1.json")
            .exists());
        // Delete only this test's freshly created, exact task-owned scratch directory.
        assert_eq!(data.parent(), Some(std::env::temp_dir().as_path()));
        assert!(data
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("rq-native-feedback-test-"));
        fs::remove_dir_all(&data).unwrap();
    }

    #[test]
    #[ignore = "requires explicitly prepared trusted host images and retained output"]
    fn real_native_generations_drive_visual_delta_and_preserve_resident_state() {
        let manifest=std::env::var("FEEDBACK_NATIVE_MANIFEST").expect("prepared host manifest");
        let output=PathBuf::from(std::env::var("FEEDBACK_TEST_OUTPUT").expect("retained host test output"));
        let nonce=SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let data=output.join(format!("native-adapter-{}-{nonce}",std::process::id()));
        fs::create_dir_all(&data).unwrap();
        let mut runtime=NativeDevelopmentFeedback::new(&data,501).unwrap();
        let session=runtime.session.clone();let owner=runtime.owner.clone();
        let initial=runtime.host.snapshot().unwrap();
        let rows:std::collections::BTreeMap<String,Vec<String>>=fs::read_to_string(manifest).unwrap().lines().map(|line|{
            let values:Vec<String>=line.splitn(4,'\t').map(str::to_owned).collect();
            assert_eq!(values.len(),4);(values[0].clone(),values)
        }).collect();
        for name in ["a1","b2","a3","wrong-version"] {
            let row=&rows[name];let path=runtime.directory.join(format!("generation-{}.so",row[1]));
            fs::copy(&row[3],&path).unwrap();let mut permissions=fs::metadata(&path).unwrap().permissions();permissions.set_readonly(true);fs::set_permissions(path,permissions).unwrap();
        }
        fn invoke(runtime:&mut NativeDevelopmentFeedback,id:&str,method:&str,args:Value,frame:u64)->Value{
            let bytes=serde_json::to_vec(&json!({"schema":"rusty.quest.hotload_command.v1","request_id":id,"method":method,"arguments":args})).unwrap();
            let command=Command::parse(&bytes,id).unwrap();runtime.execute(command,frame,&mut |_|Err("host test has no Vulkan device".into()));
            let observation=runtime.pending.last().unwrap().1.clone();
            runtime.pending.clear(); // CPU host test does not claim xrEndFrame or device pixels.
            observation
        }
        for (name,probe,value,delta,count) in [("a1","13",5,5,1),("b2","19",15,10,2),("a3","13",20,5,3)]{
            let row=&rows[name];
            let activated=invoke(&mut runtime,&format!("{name}-activate"),"native_activate",json!({"generation":row[1],"sha256":row[2],"probe_result":probe}),count*2);
            assert_eq!(activated["status"],"pass");
            let computed=invoke(&mut runtime,&format!("{name}-compute"),"native_compute",json!({"input":"4"}),count*2+1);
            assert_eq!(computed["status"],"pass");
            assert_eq!((runtime.value,runtime.visual_value,runtime.completed),(value,delta,count));
            let snapshot=runtime.host.snapshot().unwrap();assert_eq!(snapshot.allocation_token,initial.allocation_token);assert_eq!(snapshot.buffer,value.to_le_bytes());
            assert_eq!((&runtime.session,&runtime.owner),(&session,&owner));
            if name=="b2"{
                let row=&rows["wrong-version"];
                let refused=invoke(&mut runtime,"bad-abi","native_activate",json!({"generation":row[1],"sha256":row[2],"probe_result":"13"}),8);
                assert_eq!(refused["status"],"refused");assert_eq!((runtime.value,runtime.visual_value,runtime.completed),(15,10,2));
                assert_eq!(runtime.host.snapshot().unwrap().generation,2);
            }
        }
        fs::write(data.join("host-adapter-result.json"),serde_json::to_vec_pretty(&json!({"host_only":true,"device_tested":false,"session":session,"owner":owner,"completed":runtime.completed,"value":runtime.value,"visual_delta":runtime.visual_value,"bad_abi_refused":true})).unwrap()).unwrap();
        println!("retained exact host adapter result {}",data.display());
    }
}
