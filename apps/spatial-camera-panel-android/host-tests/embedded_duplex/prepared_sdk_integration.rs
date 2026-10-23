// Host-only environment around extracted, unchanged production coordinator,
// handler, current/held guards, four-kind codec and production seed signer.
use serde_json::{json, Value};
use std::cell::Cell;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc, Mutex, OnceLock,
};
use std::time::{Duration, Instant};
thread_local! {static ACTIVE:Cell<usize>=const{Cell::new(0)};}
static PEERS: OnceLock<[Mutex<Process>; 2]> = OnceLock::new();
fn process() -> &'static Mutex<Process> {
    &PEERS.get_or_init(|| {
        [
            Mutex::new(Process::default()),
            Mutex::new(Process::default()),
        ]
    })[ACTIVE.with(Cell::get)]
}
fn on<T>(peer: usize, f: impl FnOnce() -> T) -> T {
    ACTIVE.with(|a| {
        let old = a.replace(peer);
        let result = f();
        a.set(old);
        result
    })
}
fn safe_decode(_: serde_json::Error) -> String {
    "host decode".into()
}
#[derive(Clone)]
struct Binding {
    generation: u64,
    record_sha256: String,
}
#[derive(Clone)]
struct Capability {
    generation: u64,
    binding: Binding,
    live: Arc<AtomicBool>,
}
impl Capability {
    fn require_live(&self) -> Result<(), String> {
        if self.live.load(Ordering::SeqCst) {
            Ok(())
        } else {
            Err("modeled capability retired".into())
        }
    }
}
struct Clock(AtomicU64);
impl Clock {
    fn now_ms(&self) -> Result<u64, String> {
        Ok(self.0.load(Ordering::SeqCst))
    }
}
#[derive(Clone)]
struct Host {
    capability: Capability,
    config_sha256: String,
    local_peer_id: String,
    remote_peer_id: String,
    remote_public_key: [u8; 32],
    route_configuration_sha256: String,
    clock: Arc<Clock>,
    callbacks: JavaOwnerCallbacks,
    source: Arc<Mutex<Value>>,
    peer_lifecycle: Arc<Mutex<Option<Lifetime>>>,
    preparation_admission: Arc<Mutex<preparation_diagnostic::Journal>>,
}
#[derive(Default)]
struct Process {
    host: Option<Arc<Host>>,
    prepared_peer_start: Option<Arc<prepared::Arena>>,
    closing: bool,
    initializing: bool,
    lease_integrity_failed: bool,
    root_reestablishment: Option<()>,
    host_leases: u64,
    prepared_archive_cursor: Option<Value>,
}
struct HostLease(Arc<Host>);
impl std::ops::Deref for HostLease {
    type Target = Host;
    fn deref(&self) -> &Host {
        &self.0
    }
}
impl Drop for HostLease {
    fn drop(&mut self) {
        process().lock().unwrap().host_leases -= 1;
    }
}
fn source(h: &Host) -> Result<Value, String> {
    Ok(h.source.lock().map_err(|_| "host source poisoned")?.clone())
}
#[derive(Default)]
struct Calls {
    events: Vec<String>,
    sender: Option<AndroidPreparedSenderReadback>,
    decoder: Option<AndroidPreparedDecoderReadback>,
    fault: Option<String>,
    policy: Option<Arc<Mutex<Policy>>>,
    cold: bool,
    binding: Option<(String, String)>,
}
#[derive(Clone)]
struct JavaOwnerCallbacks {
    peer: usize,
    identity: Arc<()>,
    calls: Arc<Mutex<Calls>>,
}
impl JavaOwnerCallbacks {
    fn finalize_prepared_transport(&self, request: &AndroidPreparedStartRequest) -> Result<(), String> {
        self.event("transport_finalize")?;
        // This modeled Java leaf checks the actual extracted coordinator has
        // earned BOTH exact decoder receipts before entering the effect.
        let h = process().lock().unwrap().host.clone().unwrap();
        prepared::transport_inputs(&h, request)?;
        let fault = self.calls.lock().unwrap().fault.clone();
        if fault.as_deref() == Some("transport_source") {
            h.source.lock().unwrap()["floor_sha256"] =
                json!(format!("sha256:{}", "b".repeat(64)));
        }
        if fault.as_deref() == Some("transport_stop") {
            prepared::stop_during_transport(&h);
        }
        Ok(())
    }
    fn event(&self, name: &str) -> Result<(), String> {
        let mut c = self.calls.lock().unwrap();
        c.events.push(name.into());
        if c.fault.as_deref() == Some(name) {
            Err(format!("host injected {name}"))
        } else {
            Ok(())
        }
    }
    fn sign_prepared_start_frame(&self, b: &[u8]) -> Result<[u8; 64], String> {
        self.event("sign")?;
        identity::host_sign(&[0x37 + self.peer as u8; 32], b)
    }
    fn exchange(&mut self, b: &[u8], max: usize) -> Result<Vec<u8>, String> {
        self.event("exchange")?;
        assert!(b.len() <= max);
        let mut reply = on(1 - self.peer, || prepared::handle_frame(b))?;
        let fault = self.calls.lock().unwrap().fault.clone();
        if fault.as_deref() == Some("decode") {
            reply[0] = 0;
        }
        if matches!(fault.as_deref(), Some("identity" | "signature")) {
            let mut frame = decode_prepared_start_frame(
                &reply,
                &identity::host_public(&[0x37 + (1 - self.peer) as u8; 32]),
                &format!("peer.{}", 1 - self.peer),
                &format!("peer.{}", self.peer),
                &format!("sha256:{}", "5".repeat(64)),
                100,
            )?;
            if fault.as_deref() == Some("identity") {
                frame.request_id = "e".repeat(32);
                frame.receiver_preparation_id = Some("e".repeat(32));
            }
            let mut signature = identity::host_sign(
                &[0x37 + (1 - self.peer) as u8; 32],
                &prepared_start_frame_signing_bytes(&frame)?,
            )?;
            if fault.as_deref() == Some("signature") {
                signature[0] ^= 1;
            }
            reply = encode_prepared_start_frame(frame, signature)?;
        }
        Ok(reply)
    }
    fn owner_transport_attempt_read(&self) -> Value {
        let p = process()
            .try_lock()
            .expect("PROCESS held across read callback");
        let h = p.host.as_ref().unwrap();
        assert!(
            h.preparation_admission.try_lock().is_ok(),
            "journal held across callback"
        );
        drop(p);
        self.event("transport_read").unwrap();
        owner_transport_attempt_read::snapshot(
            r#"{"attempt_sequence":0,"phase":"NONE","outcome":"NONE","failure":"NONE"}"#,
        )
    }
    fn archive_completed_prepared_start(&self, raw: &str) -> Result<Value, String> {
        self.event("archive")?;
        if let Some(p) = self.calls.lock().unwrap().policy.clone() {
            if p.lock().unwrap().rpc("A") != "OK" {
                return Err("host Java archive refused".into());
            }
        }
        let body: Value = serde_json::from_str(raw).unwrap();
        let mut ack = json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_lifetime_retained.v1","body_sha256":format!("sha256:{}",packaged_json_sha256(raw)),"storage_identity":"host.modeled.durable"});
        for key in [
            "original_id",
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
        ] {
            ack[key] = body[key].clone();
        }
        if self.calls.lock().unwrap().fault.as_deref() == Some("archive_ack") {
            ack["original_id"] = json!("f".repeat(32));
        }
        Ok(ack)
    }
    fn incoming_prepared_start_stop_sequence(&self) -> Result<i64, String> {
        if let Some(p) = self.calls.lock().unwrap().policy.clone() {
            return p
                .lock()
                .unwrap()
                .rpc("G")
                .parse()
                .map_err(|_| "host stop sequence".into());
        }
        Ok(0)
    }
    fn incoming_prepared_start_stop_requested_for(
        &self,
        remote: &str,
        sequence: i64,
        retired: bool,
    ) -> Result<bool, String> {
        self.event("incoming_stop_read")?;
        if let Some(p) = self.calls.lock().unwrap().policy.clone() {
            let value = p
                .lock()
                .unwrap()
                .rpc(&format!("S {remote} {sequence} {retired}"));
            return match value.as_str() {
                "true" => Ok(true),
                "false" => Ok(false),
                _ => Err("host policy refused".into()),
            };
        }
        Ok(false)
    }
    fn bind_incoming_prepared_start(&self, remote: &str, local: &str) -> Result<(), String> {
        self.event("bind")?;
        {
            let mut state = self.calls.lock().unwrap();
            if state
                .binding
                .as_ref()
                .is_some_and(|prior| prior != &(remote.to_string(), local.to_string()))
            {
                return Err("host incoming group cannot rebind".into());
            }
            state.binding = Some((remote.into(), local.into()));
        }
        while self.calls.lock().unwrap().sender.is_none() {
            std::thread::sleep(Duration::from_millis(5));
        }
        Ok(())
    }
}
impl AndroidPreparedResourceExecutor for JavaOwnerCallbacks {
    fn preparation_identity(&self) -> &Arc<()> {
        &self.identity
    }
    fn prepare_sender(
        &mut self,
        r: &AndroidPreparedStartRequest,
    ) -> Result<AndroidPreparedSenderReadback, String> {
        self.event("sender")?;
        let v = AndroidPreparedSenderReadback {
            schema_id: "rusty.quest.android.media.prepared_sender_readback.v1".into(),
            preparation_id: r.preparation_id.clone(),
            original_id: r.original_id.clone(),
            executor_generation: r.native_executor_generation,
            request_sha256: r.digest()?,
            resource_target_sha256: r.resource_target_sha256.clone(),
            provider_handle_id: "host.sender".into(),
            provider_state_revision: 2,
            stage: "sender_ready".into(),
            outgoing_format: format(),
            delivery_gate_closed: true,
            peer_delivery_count: 0,
        };
        self.calls.lock().unwrap().sender = Some(v.clone());
        Ok(v)
    }
    fn verify_sender(
        &self,
        _: &AndroidPreparedStartRequest,
        v: &AndroidPreparedSenderReadback,
    ) -> Result<(), String> {
        self.event("verify_sender")?;
        if self.calls.lock().unwrap().sender.as_ref() != Some(v) {
            Err("host sender readback mismatch".into())
        } else {
            Ok(())
        }
    }
    fn prepare_decoder(
        &mut self,
        r: &AndroidPreparedStartRequest,
        d: &AndroidPreparedDecoderRequest,
    ) -> Result<AndroidPreparedDecoderReadback, String> {
        self.event("decoder")?;
        renderer::model_prepared(&r.preparation_id);
        let v = AndroidPreparedDecoderReadback {
            schema_id: "rusty.quest.android.media.prepared_decoder_readback.v1".into(),
            preparation_id: r.preparation_id.clone(),
            executor_generation: r.native_executor_generation,
            request_sha256: r.digest()?,
            resource_target_sha256: r.resource_target_sha256.clone(),
            provider_handle_id: "host.decoder".into(),
            provider_state_revision: 2,
            stage: "decoder_ready".into(),
            peer_sender_receipt_sha256: d.peer_sender_receipt_sha256.clone(),
            delivery_gate_closed: true,
            peer_delivery_count: 0,
        };
        self.calls.lock().unwrap().decoder = Some(v.clone());
        Ok(v)
    }
    fn verify_decoder(
        &self,
        _: &AndroidPreparedStartRequest,
        _: &AndroidPreparedDecoderRequest,
        v: &AndroidPreparedDecoderReadback,
    ) -> Result<(), String> {
        self.event("verify_decoder")?;
        if self.calls.lock().unwrap().decoder.as_ref() != Some(v) {
            Err("host decoder readback mismatch".into())
        } else {
            Ok(())
        }
    }
}
fn format() -> AndroidPreparedEncodedFormat {
    AndroidPreparedEncodedFormat {
        schema_id: "rusty.quest.android.media.prepared_encoded_format.v1".into(),
        mime: "video/avc".into(),
        width: 200,
        height: 100,
        frame_rate_hz: 30,
        csd_0_hex: "00000167".into(),
        csd_1_hex: Some("00000168".into()),
        profile: None,
        level: None,
    }
}
fn request(peer: usize) -> AndroidPreparedStartRequest {
    let providers:Vec<_>=(0..7).map(|i|json!({"owner_kind":format!("kind{i}"),"owner_id":format!("owner{i}"),"provider_kind":"host.sdk","resource_id":format!("resource{i}")})).collect();
    let config = if peer == 0 { "c" } else { "d" }.repeat(64);
    let target = json!({"$schema":"rusty.quest.embedded_duplex.peer_start_resource_target.v1","runtime_config_sha256":config,"executor_generation":5,"product_binding":{"$schema":"rusty.quest.android.media.preparation_target.v1","product_id":format!("product.{peer}"),"providers":providers},"source_dimensions":{"packed_width":200,"packed_height":100,"per_eye_width":100,"per_eye_height":100,"frame_rate_hz":30,"bitrate_bps":1000000,"left_camera_id":"left","right_camera_id":"right"},"own_capture_selected":true,"max_pair_delta_ns":30000000,"outgoing_runtime_spec_id":format!("product.{peer}"),"incoming_runtime_spec_id":format!("product.{}",1-peer)});
    let policy = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_build_policy.v1","selection":"source-bound-v2","policy":{},"policy_sha256":format!("sha256:{}","d".repeat(64)),"enrollment_selection_sha256":format!("sha256:{}","e".repeat(64))});
    AndroidPreparedStartRequest {
        schema_id: "rusty.quest.embedded_duplex.prepared_start_request.v1".into(),
        preparation_id: if peer == 0 { "1" } else { "2" }.repeat(32),
        original_id: if peer == 0 { "a" } else { "b" }.repeat(32),
        app_process_generation: 3,
        app_record_sha256: "a".repeat(64),
        native_executor_generation: 5,
        config_sha256: config.clone(),
        arm_binding: json!({"binding":"established","challenge":"f".repeat(32),"arm_generation":1,"native_process":27,"process_epoch":"1".repeat(32),"runtime_config_sha256":config}),
        configuration_binding: json!({"$schema":"rusty.quest.embedded_duplex.runtime_configuration_binding.v1","config_sha256":config,"app_process_generation":3,"app_record_sha256":"a".repeat(64),"native_executor_generation":5,"local_peer_id":format!("peer.{peer}"),"fresh_setup_build_policy":policy}),
        resource_target_sha256: prepared_start_digest(
            "rusty.quest.embedded_duplex.peer_start_resource_target.v1",
            &target,
        )
        .unwrap(),
        resource_target: target,
    }
}
struct Policy {
    child: std::process::Child,
    input: std::process::ChildStdin,
    output: std::io::BufReader<std::process::ChildStdout>,
}
impl Policy {
    fn new() -> Self {
        let mut child=std::process::Command::new(JAVA_EXE).args(["-cp",JAVA_POLICY_CP,"io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PreparedIngressPolicyHost","serve"]).stdin(std::process::Stdio::piped()).stdout(std::process::Stdio::piped()).spawn().unwrap();
        let input = child.stdin.take().unwrap();
        let output = std::io::BufReader::new(child.stdout.take().unwrap());
        Self {
            child,
            input,
            output,
        }
    }
    fn rpc(&mut self, command: &str) -> String {
        use std::io::{BufRead, Write};
        writeln!(self.input, "{command}").unwrap();
        self.input.flush().unwrap();
        let mut response = String::new();
        self.output.read_line(&mut response).unwrap();
        response.trim().to_string()
    }
}
impl Drop for Policy {
    fn drop(&mut self) {
        use std::io::Write;
        let _ = writeln!(self.input, "Q");
        let _ = self.input.flush();
        let _ = self.child.wait();
    }
}
struct Lifetime {
    termination_action: Option<&'static str>,
    route_cleanup: Option<Value>,
    media_stop: Option<Value>,
    terminal_stop_original_id: Option<String>,
    terminal_stop_projection: Option<Value>,
}
mod peer_lifecycle {
    use super::*;
    fn media_stop_effect(s: &Lifetime) -> Option<&Value> {
        s.media_stop.as_ref()
    }
    PRODUCTION_LIFETIME
}
mod peer_projection_runtime {
    pub fn reserve_renderer_source(_: &str) -> Result<(), String> {
        Ok(())
    }
    pub fn release_renderer_source(_: &str) -> Result<(), String> {
        Ok(())
    }
}
mod renderer {
    use super::*;
    use crate::prepared_renderer_gate::{Gate, Phase, PrepareCalls, SourceHold};
    use std::sync::{LazyLock, LockResult, MutexGuard};
    struct Ticket {
        id: String,
    }
    struct Job {
        ticket: Ticket,
        gate: Arc<Gate>,
        joined: bool,
        source_hold_released: bool,
    }
    struct OwnReuse {
        ticket: Ticket,
        released: bool,
    }
    #[derive(Default)]
    struct Store {
        job: Option<Job>,
        registration: Option<Registration>,
        reuse: Option<OwnReuse>,
    }
    struct Stores;
    static STORE: Stores = Stores;
    static BOTH: LazyLock<[Mutex<Store>; 2]> =
        LazyLock::new(|| [Mutex::new(Store::default()), Mutex::new(Store::default())]);
    impl Stores {
        fn lock(&self) -> LockResult<MutexGuard<'static, Store>> {
            BOTH[ACTIVE.with(Cell::get)].lock()
        }
    }
    fn local_camera_acquisition_quiescent() -> bool {
        true
    }
    fn reap(_: &str) {}
    fn read_reuse(_: &str) -> Result<Option<Value>, String> {
        Ok(Some(json!({"host_physical_readback_modeled":true})))
    }
    fn view(_: &Job, _: &Registration) -> Value {
        json!({"host_physical_readback_modeled":true})
    }
    PRODUCTION_RENDERER
    pub(super) fn reset() {
        for store in BOTH.iter() {
            *store.lock().unwrap() = Store::default();
        }
    }
    pub(super) fn model_prepared(id: &str) {
        let mut s = STORE.lock().unwrap();
        let r = s.registration.as_mut().filter(|r| r.id == id).unwrap();
        r.entered = true;
        let cold = process()
            .lock()
            .unwrap()
            .host
            .as_ref()
            .unwrap()
            .callbacks
            .calls
            .lock()
            .unwrap()
            .cold;
        if cold {
            let gate = Arc::new(Gate::new(r.stop.clone()));
            gate.ready((200, 100)).unwrap();
            s.job = Some(Job {
                ticket: Ticket { id: id.into() },
                gate,
                joined: false,
                source_hold_released: false,
            });
        } else {
            r.hold.released = true;
            s.reuse = Some(OwnReuse {
                ticket: Ticket { id: id.into() },
                released: false,
            });
        }
    }
    pub(super) fn model_stopped(id: &str) {
        let mut s = STORE.lock().unwrap();
        if let Some(own) = s.reuse.as_mut().filter(|r| r.ticket.id == id) {
            own.released = true;
        } else {
            let job = s.job.as_mut().filter(|r| r.ticket.id == id).unwrap();
            job.gate.terminal(None);
            job.joined = true;
        }
    }
    pub(super) fn closed(id: &str) -> bool {
        STORE
            .lock()
            .unwrap()
            .registration
            .as_ref()
            .is_some_and(|r| r.id == id && r.closed)
    }
}
mod camera_hwb_probe {
    pub(crate) fn begin_prepared_peer_renderer(id: &str) -> Result<(), String> {
        super::process()
            .lock()
            .unwrap()
            .host
            .as_ref()
            .unwrap()
            .callbacks
            .event("renderer_begin")?;
        super::renderer::begin(id)
    }
    pub(crate) fn prepared_peer_renderer_terminal_for(
        id: &str,
    ) -> Result<serde_json::Value, String> {
        super::renderer::terminal_for(id)
    }
    pub(crate) fn prepared_peer_renderer_dormant_for(_: &str) -> Result<(), String> {
        super::process()
            .lock()
            .unwrap()
            .host
            .as_ref()
            .unwrap()
            .callbacks
            .event("renderer_dormant")
    }
}
mod estimated_warning {
    pub fn publish(_: &str) {}
}
mod prepared {
    use super::preparation_sdk_observation::{
        Direction as SdkDirection, FrameKind as SdkKind, Reason as SdkReason, Stage as SdkStage,
        Trace as SdkTrace,
    };
    use super::*;
    use crate::preparation_diagnostic::{Actor, Attempt, JavaGuard, Reason, Stage};
    type Body = AndroidPreparedStartFrameBody;
    PRODUCTION_ITEMS
    pub(super) fn transport_inputs(
        h: &Host,
        request: &AndroidPreparedStartRequest,
    ) -> Result<(), String> {
        let a = held(h)?;
        if &a.request != request
            || a.owner
                .try_lock()
                .map_err(|_| "owner held across transport callback")?
                .decoder()
                .is_none()
            || a.remote_decoder.lock().unwrap().is_none()
        {
            return Err("transport before bilateral decoder receipts".into());
        }
        if a.stop.load(Ordering::SeqCst) {
            return Err("transport after Stop".into());
        }
        Ok(())
    }
    pub(super) fn stop_during_transport(h: &Host) {
        held(h).unwrap().stop.store(true, Ordering::SeqCst);
    }
    fn setup() -> [Arc<Host>; 2] {
        renderer::reset();
        let hosts = std::array::from_fn(|peer| {
            let r = request(peer);
            let callback = JavaOwnerCallbacks {
                peer,
                identity: Arc::new(()),
                calls: Arc::new(Mutex::new(Calls::default())),
            };
            let h = Arc::new(Host {
                capability: Capability {
                    generation: 5,
                    binding: Binding {
                        generation: 3,
                        record_sha256: "a".repeat(64),
                    },
                    live: Arc::new(AtomicBool::new(true)),
                },
                config_sha256: r.config_sha256.clone(),
                local_peer_id: format!("peer.{peer}"),
                remote_peer_id: format!("peer.{}", 1 - peer),
                remote_public_key: identity::host_public(&[0x37 + (1 - peer) as u8; 32]),
                route_configuration_sha256: format!("sha256:{}", "5".repeat(64)),
                clock: Arc::new(Clock(AtomicU64::new(100))),
                callbacks: callback,
                source: Arc::new(Mutex::new(
                    json!({"source_peer_snapshot_sha256":format!("sha256:{}","8".repeat(64)),"selected_policy_sha256":format!("sha256:{}","9".repeat(64)),"floor_sha256":format!("sha256:{}","a".repeat(64))}),
                )),
                peer_lifecycle: Arc::new(Mutex::new(None)),
                preparation_admission: Arc::new(Mutex::new(
                    preparation_diagnostic::Journal::default(),
                )),
            });
            let owner = AndroidPreparedStartOwner::reserve(&h.callbacks, r.clone()).unwrap();
            let a = Arc::new(Arena {
                view: json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_view.v1","request":r,"source":*h.source.lock().unwrap()}),
                request: r,
                owner: Mutex::new(owner),
                stop: Arc::new(AtomicBool::new(false)),
                remote_decoder: Mutex::new(None),
                completed: AtomicBool::new(false),
                local_start_consumed: AtomicBool::new(false),
                failure: Mutex::new(None),
                sdk: SdkTrace::default(),
                terminal: Mutex::new(None),
                aborting: AtomicBool::new(false),
                archive_body: Mutex::new(None),
            });
            on(peer, || {
                *process().lock().unwrap() = Process {
                    host: Some(h.clone()),
                    prepared_peer_start: Some(a),
                    ..Process::default()
                }
            });
            h
        });
        hosts
    }
    fn prime_sender(h: &Host) {
        let a = current(h).unwrap();
        camera_hwb_probe::begin_prepared_peer_renderer(&a.request.preparation_id).unwrap();
        a.owner
            .lock()
            .unwrap()
            .prepare_sender(&mut h.callbacks.clone())
            .unwrap();
    }
    fn execute(h: &Host) -> Result<String, String> {
        let a = current(h)?;
        let attempt = Attempt::begin(h.preparation_admission.clone(), diagnostic_actor(h));
        attempt.bind_original(&a.request.original_id);
        attempt.arena_installed();
        let result = attempt.call(Stage::SdkPreparation, Reason::CallFailed, || {
            sdk_prepare(h, &a, &mut h.callbacks.clone())
        });
        attempt.finish(result.is_ok());
        a.sdk.finish(SdkDirection::Outbound, result.is_ok());
        if let Err(e) = &result {
            *a.failure.lock().unwrap() = Some(e.clone());
        }
        result
    }
    #[test]
    fn paired_actual_coordinator_and_handlers_complete_all_four_kinds() {
        let hosts = setup();
        let workers: Vec<_> = hosts
            .into_iter()
            .enumerate()
            .map(|(peer, h)| {
                std::thread::spawn(move || {
                    on(peer, || {
                        let result = execute(&h);
                        let a = held(&h).unwrap();
                        (
                            result,
                            a.sdk.read().unwrap(),
                            h.callbacks.calls.lock().unwrap().events.clone(),
                        )
                    })
                })
            })
            .collect();
        for worker in workers {
            let (result, trace, events) = worker.join().unwrap();
            let status: Value = serde_json::from_str(&result.unwrap()).unwrap();
            assert_eq!(status["state"], "ready");
            assert_eq!(status["media_authority_granted"], false);
            assert_eq!(status["owner"]["sender"]["peer_delivery_count"], 0);
            assert_eq!(status["owner"]["decoder"]["delivery_gate_closed"], true);
            assert!(trace.first_failure.is_none());
            assert_eq!(events.iter().filter(|e| e.as_str() == "sender").count(), 1);
            assert_eq!(events.iter().filter(|e| e.as_str() == "decoder").count(), 1);
            assert_eq!(
                events.iter().filter(|e| e.as_str() == "transport_finalize").count(),
                1,
                "actual SDK coordinator must finalize transport after bilateral decoder receipts"
            );
            assert!(
                events.iter().position(|e| e == "decoder").unwrap()
                    < events.iter().position(|e| e == "transport_finalize").unwrap()
            );
            assert_eq!(events.iter().filter(|e| e.as_str() == "sign").count(), 4);
            assert_eq!(
                events.iter().filter(|e| e.as_str() == "exchange").count(),
                2
            );
        }
    }
    #[test]
    fn transport_failure_stop_and_source_change_keep_exact_stage_and_no_ready() {
        for fault in ["transport_finalize", "transport_source", "transport_stop"] {
            let hosts = setup();
            hosts[0].callbacks.calls.lock().unwrap().fault = Some(fault.into());
            let workers: Vec<_> = hosts.into_iter().enumerate().map(|(peer, h)| {
                std::thread::spawn(move || on(peer, || {
                    let result = execute(&h);
                    let a = held(&h).unwrap();
                    (
                        result,
                        a.sdk.read().unwrap(),
                        a.completed.load(Ordering::SeqCst),
                        a.local_start_consumed.load(Ordering::SeqCst),
                        h.callbacks.calls.lock().unwrap().events.clone(),
                    )
                }))
            }).collect();
            for (peer, worker) in workers.into_iter().enumerate() {
                let (result, trace, completed, consumed, events) = worker.join().unwrap();
                if peer == 0 {
                    assert!(result.is_err(), "{fault}");
                    assert!(!completed && !consumed);
                    assert_eq!(trace.outbound.stage, Some(SdkStage::TransportFinalize));
                    for event in ["transport_finalize", "decoder", "sender"] {
                        assert_eq!(events.iter().filter(|e| e.as_str() == event).count(), 1);
                    }
                } else {
                    assert!(result.is_ok(), "{fault}: {result:?}");
                }
            }
        }
    }
    #[test]
    fn production_failures_keep_first_stage_primary_error_and_no_replay() {
        for (fault, peer, stage) in [
            ("renderer_begin", 0, SdkStage::RendererBegin),
            ("sender", 0, SdkStage::SenderPrepare),
            ("sign", 0, SdkStage::FrameSign),
            ("exchange", 0, SdkStage::FrameExchange),
            ("decode", 0, SdkStage::FrameDecode),
            ("identity", 0, SdkStage::ResponseIdentity),
            ("signature", 0, SdkStage::FrameDecode),
            ("bind", 1, SdkStage::FrameExchange),
            ("decoder", 1, SdkStage::FrameExchange),
        ] {
            let hosts = setup();
            hosts[peer].callbacks.calls.lock().unwrap().fault = Some(fault.into());
            on(1, || prime_sender(&hosts[1]));
            on(0, || {
                let result = execute(&hosts[0]);
                assert!(result.is_err(), "{fault}");
                let a = held(&hosts[0]).unwrap();
                let observed = a.sdk.read().unwrap();
                assert_eq!(observed.outbound.stage, Some(stage), "{fault}");
                let before = a.sdk.read().unwrap();
                assert_eq!(before, a.sdk.read().unwrap());
                let events = hosts[0].callbacks.calls.lock().unwrap().events.clone();
                assert!(events.iter().filter(|e| e.as_str() == "sender").count() <= 1);
                assert!(events.iter().filter(|e| e.as_str() == "exchange").count() <= 2);
                assert!(!a.completed.load(Ordering::SeqCst));
                assert!(!a.local_start_consumed.load(Ordering::SeqCst));
                if fault == "decoder" {
                    assert_eq!(
                        on(1, || held(&hosts[1]).unwrap().sdk.read().unwrap())
                            .incoming
                            .stage,
                        Some(SdkStage::IncomingDecoder)
                    );
                }
            });
        }
    }
    #[test]
    fn actual_current_source_route_clock_and_remote_policy_refusals_precede_decoder() {
        for mode in ["source", "stale_actor", "clock", "route", "policy"] {
            let mut hosts = setup();
            on(1, || prime_sender(&hosts[1]));
            if mode == "source" {
                hosts[0].source.lock().unwrap()["floor_sha256"] =
                    json!(format!("sha256:{}", "b".repeat(64)));
            }
            if mode == "clock" {
                hosts[0].clock.0.store(100_000, Ordering::SeqCst);
                hosts[1].clock.0.store(94_999, Ordering::SeqCst);
            }
            if mode == "route" {
                on(1, || {
                    let mut p = process().lock().unwrap();
                    let mut h = (**p.host.as_ref().unwrap()).clone();
                    h.route_configuration_sha256 = format!("sha256:{}", "6".repeat(64));
                    p.host = Some(Arc::new(h));
                });
            }
            if mode == "policy" {
                on(1, || {
                    let mut p = process().lock().unwrap();
                    let a = Arc::get_mut(p.prepared_peer_start.as_mut().unwrap()).unwrap();
                    a.view["source"]["selected_policy_sha256"] =
                        json!(format!("sha256:{}", "b".repeat(64)));
                });
            }
            if mode == "stale_actor" {
                hosts[0].capability.live.store(false, Ordering::SeqCst);
            }
            on(0, || {
                assert!(execute(&hosts[0]).is_err(), "{mode}");
                assert!(hosts[0].callbacks.calls.lock().unwrap().decoder.is_none());
            });
        }
    }
    #[test]
    fn diagnostic_reads_exact_failed_arena_without_reentering_sdk_or_holding_locks() {
        let hosts = setup();
        hosts[0].callbacks.calls.lock().unwrap().fault = Some("exchange".into());
        on(0, || {
            assert!(execute(&hosts[0]).is_err());
            let a = held(&hosts[0]).unwrap();
            let before = a.sdk.read().unwrap();
            let projection = a.owner.lock().unwrap().projection();
            let observed: Value = serde_json::from_str(
                &diagnostic(
                    &hosts[0],
                    &json!({"original_id":a.request.original_id}).to_string(),
                )
                .unwrap(),
            )
            .unwrap();
            assert_eq!(
                observed["$schema"],
                "rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v3"
            );
            assert_eq!(
                observed["sdk_observation"]["outbound"]["stage"],
                "frame_exchange"
            );
            assert_eq!(observed["transport_attempt_original_bound"], false);
            assert_eq!(
                observed["transport_attempt"]["qualification_claimed"],
                false
            );
            assert_eq!(before, a.sdk.read().unwrap());
            assert_eq!(projection, a.owner.lock().unwrap().projection());
            let count = hosts[0].callbacks.calls.lock().unwrap().events.len();
            assert!(diagnostic(
                &hosts[0],
                &json!({"original_id":"f".repeat(32)}).to_string()
            )
            .is_err());
            assert_eq!(count, hosts[0].callbacks.calls.lock().unwrap().events.len());
            let mut wrong = (*hosts[0]).clone();
            wrong.config_sha256 = "e".repeat(64);
            assert!(diagnostic(&wrong, "{}").is_err());
            assert_eq!(count, hosts[0].callbacks.calls.lock().unwrap().events.len());
        });
    }

    #[test]
    fn paired_actual_one_second_clock_skew_completes_without_changing_clocks() {
        let hosts = setup();
        hosts[0].clock.0.store(100_000, Ordering::SeqCst);
        hosts[1].clock.0.store(99_000, Ordering::SeqCst);
        let workers: Vec<_> = hosts
            .into_iter()
            .enumerate()
            .map(|(peer, h)| std::thread::spawn(move || on(peer, || execute(&h))))
            .collect();
        let results: Vec<_> = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .collect();
        for result in results {
            assert!(result.is_ok(), "{result:?}");
        }
    }

    #[test]
    fn authenticated_successor_arrives_before_local_archive_and_new_stop_cancels_waiter() {
        for cancel in ["none", "peer", "whole", "foreign", "pending"] {
            let hosts = setup();
            let h = hosts[1].clone();
            let policy = Arc::new(Mutex::new(Policy::new()));
            h.callbacks.calls.lock().unwrap().policy = Some(policy.clone());
            let mut old_request = request(1);
            old_request.preparation_id = "b".repeat(32);
            let old = Arc::new(Arena {
                request: old_request.clone(),
                owner: Mutex::new(
                    AndroidPreparedStartOwner::reserve(&h.callbacks, old_request.clone()).unwrap(),
                ),
                view: json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_view.v1","request":old_request,"source":*h.source.lock().unwrap()}),
                stop: Arc::new(AtomicBool::new(false)),
                remote_decoder: Mutex::new(None),
                completed: AtomicBool::new(true),
                local_start_consumed: AtomicBool::new(false),
                failure: Mutex::new(None),
                sdk: SdkTrace::default(),
                terminal: Mutex::new(None),
                aborting: AtomicBool::new(false),
                archive_body: Mutex::new(None),
            });
            on(1, || {
                process().lock().unwrap().prepared_peer_start = Some(old.clone())
            });
            old.local_start_consumed.store(true, Ordering::SeqCst);
            old.stop.store(true, Ordering::SeqCst);
            *h.peer_lifecycle.lock().unwrap() = Some(Lifetime {
                termination_action: Some("stop"),
                route_cleanup: Some(json!({})),
                media_stop: Some(json!({})),
                terminal_stop_original_id: Some("e".repeat(32)),
                terminal_stop_projection: Some(json!({"status":"terminal"})),
            });
            if cancel == "foreign" {
                assert_eq!(policy.lock().unwrap().rpc("F"), "OK");
            }
            if cancel == "pending" {
                assert_eq!(policy.lock().unwrap().rpc("T"), "OK");
            }
            let frame = on(0, || {
                let a = held(&hosts[0]).unwrap();
                encode(
                    &hosts[0],
                    &a,
                    "view",
                    &a.request.preparation_id,
                    None,
                    json!({"view":a.view}),
                )
                .unwrap()
            });
            let worker = std::thread::spawn(move || on(1, || handle_frame(&frame)));
            std::thread::sleep(Duration::from_millis(100));
            if cancel == "none" {
                assert!(
                    !worker.is_finished(),
                    "new request should await explicit local owner"
                );
                assert_eq!(policy.lock().unwrap().rpc("A"), "OK");
                // Only the local explicit preparation may install this test reservation;
                // the incoming frame neither archives nor allocates it.
                let r = request(1);
                let callback = h.callbacks.clone();
                let a = Arc::new(Arena {
                    request: r.clone(),
                    owner: Mutex::new(
                        AndroidPreparedStartOwner::reserve(&callback, r.clone()).unwrap(),
                    ),
                    view: json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_view.v1","request":r,"source":*h.source.lock().unwrap()}),
                    stop: Arc::new(AtomicBool::new(false)),
                    remote_decoder: Mutex::new(None),
                    completed: AtomicBool::new(false),
                    local_start_consumed: AtomicBool::new(false),
                    failure: Mutex::new(None),
                    sdk: SdkTrace::default(),
                    terminal: Mutex::new(None),
                    aborting: AtomicBool::new(false),
                    archive_body: Mutex::new(None),
                });
                on(1, || {
                    process().lock().unwrap().prepared_peer_start = Some(a);
                    prime_sender(&h);
                });
                assert!(worker.join().unwrap().is_ok());
            } else {
                if cancel == "peer" {
                    assert_eq!(policy.lock().unwrap().rpc("P false"), "OK");
                }
                if cancel == "whole" {
                    assert_eq!(policy.lock().unwrap().rpc("P true"), "OK");
                }
                assert!(worker.join().unwrap().is_err(), "{cancel}");
            }
        }
    }

    #[test]
    fn exact_terminal_renderer_and_durable_ack_precede_native_metadata_replacement() {
        for bad_ack in [false, true] {
            let hosts = setup();
            on(0, || {
                let h = &hosts[0];
                let a = held(h).unwrap();
                renderer::begin(&a.request.preparation_id).unwrap();
                renderer::model_prepared(&a.request.preparation_id);
                renderer::model_stopped(&a.request.preparation_id);
                a.local_start_consumed.store(true, Ordering::SeqCst);
                a.stop.store(true, Ordering::SeqCst);
                *h.peer_lifecycle.lock().unwrap() = Some(Lifetime {
                    termination_action: Some("stop"),
                    route_cleanup: Some(json!({})),
                    media_stop: Some(json!({})),
                    terminal_stop_original_id: Some("e".repeat(32)),
                    terminal_stop_projection: Some(json!({"status":"terminal"})),
                });
                process().lock().unwrap().host_leases = 1;
                assert!(!renderer::closed(&a.request.preparation_id));
                if bad_ack {
                    h.callbacks.calls.lock().unwrap().fault = Some("archive_ack".into());
                }
                let result = archive_prior(h, None);
                assert!(renderer::closed(&a.request.preparation_id));
                assert_eq!(result.is_err(), bad_ack);
                let p = process().lock().unwrap();
                assert_eq!(p.prepared_peer_start.is_some(), bad_ack);
                assert_eq!(p.prepared_archive_cursor.is_none(), bad_ack);
            });
        }
    }

    #[test]
    fn two_actual_sdk_lifetimes_archive_before_replacing_own_or_cold_registration() {
        for cold in [false, true] {
            let hosts = setup();
            for h in &hosts {
                h.callbacks.calls.lock().unwrap().cold = cold;
            }
            for life in [0, 1] {
                let workers: Vec<_> = hosts
                    .iter()
                    .cloned()
                    .enumerate()
                    .map(|(peer, h)| std::thread::spawn(move || on(peer, || execute(&h))))
                    .collect();
                let results: Vec<_> = workers
                    .into_iter()
                    .map(|worker| worker.join().unwrap())
                    .collect();
                for result in results {
                    assert!(result.is_ok(), "{result:?}");
                }
                for (peer, h) in hosts.iter().enumerate() {
                    on(peer, || {
                        let a = held(h).unwrap();
                        a.local_start_consumed.store(true, Ordering::SeqCst);
                        a.stop.store(true, Ordering::SeqCst);
                        renderer::model_stopped(&a.request.preparation_id);
                        *h.peer_lifecycle.lock().unwrap() = Some(Lifetime {
                            termination_action: Some("stop"),
                            route_cleanup: Some(json!({})),
                            media_stop: Some(json!({})),
                            terminal_stop_original_id: Some("e".repeat(32)),
                            terminal_stop_projection: Some(json!({"status":"terminal"})),
                        });
                        process().lock().unwrap().host_leases = 1;
                        archive_prior(h, None).unwrap();
                        assert!(renderer::closed(&a.request.preparation_id));
                        assert_eq!(
                            process()
                                .lock()
                                .unwrap()
                                .prepared_archive_cursor
                                .as_ref()
                                .unwrap()["ordinal"],
                            life + 1
                        );
                        if life == 0 {
                            let mut r = request(peer);
                            r.preparation_id = if peer == 0 { "3" } else { "4" }.repeat(32);
                            r.original_id = if peer == 0 { "5" } else { "6" }.repeat(32);
                            let owner = AndroidPreparedStartOwner::reserve(&h.callbacks, r.clone())
                                .unwrap();
                            let next = Arc::new(Arena {
                                view: json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_view.v1","request":r,"source":*h.source.lock().unwrap()}),
                                request: r,
                                owner: Mutex::new(owner),
                                stop: Arc::new(AtomicBool::new(false)),
                                remote_decoder: Mutex::new(None),
                                completed: AtomicBool::new(false),
                                local_start_consumed: AtomicBool::new(false),
                                failure: Mutex::new(None),
                                sdk: SdkTrace::default(),
                                terminal: Mutex::new(None),
                                aborting: AtomicBool::new(false),
                                archive_body: Mutex::new(None),
                            });
                            let mut p = process().lock().unwrap();
                            p.prepared_peer_start = Some(next);
                            p.host_leases = 0;
                            let mut c = h.callbacks.calls.lock().unwrap();
                            c.sender = None;
                            c.decoder = None;
                            c.binding = None;
                        }
                    });
                }
            }
            for h in &hosts {
                let c = h.callbacks.calls.lock().unwrap();
                assert_eq!(
                    c.events.iter().filter(|v| v.as_str() == "sender").count(),
                    2
                );
                assert_eq!(
                    c.events.iter().filter(|v| v.as_str() == "decoder").count(),
                    2
                );
                assert_eq!(
                    c.events.iter().filter(|v| v.as_str() == "archive").count(),
                    2
                );
            }
        }
    }
}
