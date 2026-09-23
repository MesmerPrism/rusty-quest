//! Process-owned composition of the reusable authority and platform registry.
//!
//! The slots are checked out before entering Java. Metadata/authority handles
//! remain readable, but a second mutation cannot reenter an active provider.

use super::java_bridge::JavaOwnerCallbacks;
use super::packaged_config::assemble_packaged_config_request_json;
use super::runtime_slot::Checkout;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;
use rusty_quest_broker_authority::{
    QuestBrokerAuthorityBridgeKind, QuestBrokerProductActivationMaterial, QuestBrokerRuntimeConfig,
    QuestBrokerRuntimeProvider, QuestEmbeddedDuplexAuthority, QuestEmbeddedDuplexProjectionSource,
    QuestOwnerDispatchAuthorityVerifier,
};
use rusty_quest_media_stream_android::{
    decode_product_activation_request, decode_product_activation_response,
    encode_product_activation_request, verify_product_activation_response,
    AndroidMediaDevicePeerPlacement, AndroidMediaExecutionMode, AndroidMediaExecutionTicket,
    CompositeAndroidMediaOwnerExecutor, CurrentOwnerProjectionSource,
    OwnerDispatchAuthorityProjection, OwnerDispatchClock, OwnerDispatchReplaySnapshot,
    OwnerDispatchServer, OwnerDispatchSigner, OwnerDispatchStatus, OwnerDispatchTransport,
    ProductActivationReplaySnapshot, ProductActivationRequest, ProductActivationResponse,
    ProductActivationServer, RemoteOwnerDispatchExecutor, PRODUCT_ACTIVATION_REQUEST_SCHEMA,
};
use serde::Deserialize;
use serde_json::json;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

type DispatchServer = OwnerDispatchServer<
    QuestOwnerDispatchAuthorityVerifier,
    JavaOwnerCallbacks,
    JavaOwnerCallbacks,
>;

type ActivationServer = ProductActivationServer<
    QuestOwnerDispatchAuthorityVerifier,
    JavaOwnerCallbacks,
    JavaOwnerCallbacks,
>;

struct PendingActivation {
    client_id: String,
    activation_id: String,
    material: QuestBrokerProductActivationMaterial,
    frame: Option<Vec<u8>>,
    response: Option<ProductActivationResponse>,
}

#[derive(Default)]
struct ActivationSender {
    pending: Option<PendingActivation>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Bootstrap {
    local_peer_id: String,
    remote_peer_id: String,
    local_key_id: String,
    remote_key_id: String,
    remote_public_key_hex: String,
    runtime_spec_id: String,
    incoming_runtime_spec_id: String,
    route_grant_id: String,
    route_configuration_sha256: String,
    executor_generation: u64,
    device_peers: Vec<AndroidMediaDevicePeerPlacement>,
    replay: OwnerDispatchReplaySnapshot,
    activation_replay: ProductActivationReplaySnapshot,
}

struct ConfiguredProjectionSource {
    authority: QuestEmbeddedDuplexProjectionSource,
    route_configuration_sha256: String,
}

impl CurrentOwnerProjectionSource for ConfiguredProjectionSource {
    fn current_projection(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        target_peer_id: &str,
        mode: AndroidMediaExecutionMode,
        now_ms: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String> {
        let current = self
            .authority
            .current_projection(ticket, target_peer_id, mode, now_ms)?;
        if current.route_configuration_sha256 != self.route_configuration_sha256 {
            return Err("current route differs from packaged control/media configuration".into());
        }
        Ok(current)
    }
}

#[derive(Clone)]
struct Host {
    provider: Arc<Mutex<Option<QuestBrokerRuntimeProvider>>>,
    server: Arc<Mutex<Option<DispatchServer>>>,
    activation_server: Arc<Mutex<Option<ActivationServer>>>,
    activation_sender: Arc<Mutex<Option<ActivationSender>>>,
    authority: QuestEmbeddedDuplexAuthority,
    clock: AuthorityClock,
    callbacks: JavaOwnerCallbacks,
    local_peer_id: String,
    remote_peer_id: String,
    route_grant_id: String,
    route_configuration_sha256: String,
    remote_key_id: String,
    remote_public_key: [u8; 32],
}

#[derive(Default)]
struct ProcessState {
    initializing: bool,
    host: Option<Host>,
}

static PROCESS: OnceLock<Mutex<ProcessState>> = OnceLock::new();

fn process() -> &'static Mutex<ProcessState> {
    PROCESS.get_or_init(|| Mutex::new(ProcessState::default()))
}

/// A bounded consistency check rejects a wall-clock jump during this epoch.
#[derive(Clone)]
struct AuthorityClock {
    wall_ms: u64,
    monotonic: Instant,
}

impl AuthorityClock {
    fn new() -> Result<Self, String> {
        Ok(Self {
            wall_ms: wall_ms()?,
            monotonic: Instant::now(),
        })
    }
}

impl OwnerDispatchClock for AuthorityClock {
    fn now_ms(&self) -> Result<u64, String> {
        let actual = wall_ms()?;
        let elapsed = u64::try_from(self.monotonic.elapsed().as_millis())
            .map_err(|_| "authority clock overflow")?;
        let expected = self
            .wall_ms
            .checked_add(elapsed)
            .ok_or("authority clock overflow")?;
        if actual.abs_diff(expected) > 5_000 {
            return Err("authority clock changed".into());
        }
        Ok(actual)
    }
}

fn wall_ms() -> Result<u64, String> {
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "authority clock")?;
    u64::try_from(elapsed.as_millis()).map_err(|_| "authority clock overflow".into())
}

fn monotonic_ns() -> Result<u64, String> {
    let mut time = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut time) } != 0 || time.tv_sec < 0 {
        return Err("monotonic clock unavailable".into());
    }
    (time.tv_sec as u64)
        .checked_mul(1_000_000_000)
        .and_then(|seconds| seconds.checked_add(time.tv_nsec as u64))
        .ok_or_else(|| "monotonic clock overflow".into())
}

fn host() -> Result<Host, String> {
    process()
        .lock()
        .map_err(|_| "process state poisoned")?
        .host
        .clone()
        .ok_or_else(|| "embedded runtime not initialized".into())
}

fn initialize(
    env: &mut JNIEnv<'_>,
    config_json: String,
    expected_sha: String,
    entropy: String,
    bootstrap_json: String,
    callback: JObject<'_>,
) -> Result<String, String> {
    {
        let mut state = process().lock().map_err(|_| "process state poisoned")?;
        if state.initializing || state.host.is_some() {
            return Err("embedded runtime already owned".into());
        }
        state.initializing = true;
    }
    let built = build_host(
        env,
        config_json,
        expected_sha,
        entropy,
        bootstrap_json,
        callback,
    );
    let mut state = process().lock().map_err(|_| "process state poisoned")?;
    state.initializing = false;
    let (host, result) = built?;
    state.host = Some(host);
    Ok(result)
}

fn build_host(
    env: &mut JNIEnv<'_>,
    config_json: String,
    expected_sha: String,
    entropy: String,
    bootstrap_json: String,
    callback: JObject<'_>,
) -> Result<(Host, String), String> {
    let config: QuestBrokerRuntimeConfig =
        serde_json::from_str(&config_json).map_err(safe_decode)?;
    if config.bridge_kind != QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni {
        return Err("embedded bridge required".into());
    }
    let bootstrap: Bootstrap = serde_json::from_str(&bootstrap_json).map_err(safe_decode)?;
    if bootstrap.local_peer_id == bootstrap.remote_peer_id
        || bootstrap.device_peers.len() != 2
        || !bootstrap
            .device_peers
            .iter()
            .any(|entry| entry.peer_id == bootstrap.local_peer_id)
        || !bootstrap
            .device_peers
            .iter()
            .any(|entry| entry.peer_id == bootstrap.remote_peer_id)
        || bootstrap.executor_generation == 0
        || bootstrap.executor_generation > i64::MAX as u64
        || bootstrap.route_configuration_sha256.len() != 71
        || !bootstrap.route_configuration_sha256.starts_with("sha256:")
        || !bootstrap.route_configuration_sha256.as_bytes()[7..]
            .iter()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(c))
    {
        return Err("invalid embedded pair binding".into());
    }
    let placements = config
        .embedded_duplex_owner_placements(
            &bootstrap.runtime_spec_id,
            &bootstrap.local_peer_id,
            &bootstrap.local_peer_id,
            &bootstrap.device_peers,
        )
        .map_err(|_| "packaged owner placement rejected")?;
    let incoming_placements = config
        .embedded_duplex_owner_placements(
            &bootstrap.incoming_runtime_spec_id,
            &bootstrap.local_peer_id,
            &bootstrap.remote_peer_id,
            &bootstrap.device_peers,
        )
        .map_err(|_| "packaged incoming placement rejected")?;
    let bindings = if config.media_sessions.is_empty() {
        config.media_session.iter().collect::<Vec<_>>()
    } else {
        config.media_sessions.iter().collect::<Vec<_>>()
    };
    let outgoing_spec = bindings
        .iter()
        .find(|binding| binding.quest.spec.runtime_spec_id == bootstrap.runtime_spec_id)
        .ok_or("outgoing runtime spec missing")?
        .quest
        .spec
        .clone();
    let incoming_spec = bindings
        .iter()
        .find(|binding| binding.quest.spec.runtime_spec_id == bootstrap.incoming_runtime_spec_id)
        .ok_or("incoming runtime spec missing")?
        .quest
        .spec
        .clone();
    let clock = AuthorityClock::new()?;
    let mut provider = QuestBrokerRuntimeProvider::default();
    let status = provider
        .initialize(
            &config_json,
            &expected_sha,
            &entropy,
            i64::try_from(clock.now_ms()?).map_err(|_| "authority clock overflow")?,
            monotonic_ns()?,
        )
        .map_err(|_| "packaged runtime initialization rejected")?;
    let authority = provider
        .embedded_duplex_authority()
        .map_err(|_| "embedded authority unavailable")?;
    let callbacks = JavaOwnerCallbacks::capture(
        env,
        callback,
        bootstrap.local_key_id,
        bootstrap.remote_peer_id.clone(),
    )?;
    let remote_public_key = decode_key(&bootstrap.remote_public_key_hex)?;
    let remote = RemoteOwnerDispatchExecutor::new(
        callbacks.clone(),
        callbacks.clone(),
        remote_public_key,
        bootstrap.remote_key_id.clone(),
    )?;
    let grant_id = serde_json::from_value(json!(bootstrap.route_grant_id)).map_err(safe_decode)?;
    let local_peer = serde_json::from_value(json!(bootstrap.local_peer_id)).map_err(safe_decode)?;
    let projections = ConfiguredProjectionSource {
        authority: QuestEmbeddedDuplexProjectionSource::new(
            authority.clone(),
            grant_id,
            local_peer,
        ),
        route_configuration_sha256: bootstrap.route_configuration_sha256.clone(),
    };
    let executor = CompositeAndroidMediaOwnerExecutor::new(
        bootstrap.executor_generation,
        bootstrap.local_peer_id.clone(),
        placements.clone(),
        Box::new(callbacks.clone()),
        Box::new(remote),
        Box::new(projections),
        Box::new(clock.clone()),
    )?;
    provider
        .install_media_owner_executor(Box::new(executor))
        .map_err(|_| "owner executor rejected")?;
    let verifier =
        QuestOwnerDispatchAuthorityVerifier::new(authority.clone(), Arc::new(clock.clone()));
    let server = OwnerDispatchServer::restore(
        bootstrap.local_peer_id.clone(),
        verifier.clone(),
        callbacks.clone(),
        callbacks.clone(),
        Box::new(clock.clone()),
        bootstrap.replay,
        Box::new(callbacks.clone()),
    )?;
    let activation_server = ProductActivationServer::restore(
        bootstrap.local_peer_id.clone(),
        verifier,
        callbacks.clone(),
        callbacks.clone(),
        Box::new(clock.clone()),
        bootstrap.activation_replay,
        Box::new(callbacks.clone()),
    )?;
    let result = json!({"$schema":"rusty.quest.embedded_duplex.runtime_initialized.v1",
        "runtime": status, "owner_placements": placements,
        "incoming_owner_placements": incoming_placements,
        "outgoing_runtime_spec": outgoing_spec, "incoming_runtime_spec": incoming_spec,
        "executor_generation": bootstrap.executor_generation})
    .to_string();
    Ok((
        Host {
            provider: Arc::new(Mutex::new(Some(provider))),
            server: Arc::new(Mutex::new(Some(server))),
            activation_server: Arc::new(Mutex::new(Some(activation_server))),
            activation_sender: Arc::new(Mutex::new(Some(ActivationSender::default()))),
            authority,
            clock,
            callbacks,
            local_peer_id: bootstrap.local_peer_id,
            remote_peer_id: bootstrap.remote_peer_id,
            route_grant_id: bootstrap.route_grant_id,
            route_configuration_sha256: bootstrap.route_configuration_sha256,
            remote_key_id: bootstrap.remote_key_id,
            remote_public_key,
        },
        result,
    ))
}

fn decode_key(value: &str) -> Result<[u8; 32], String> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err("peer public key encoding".into());
    }
    let mut key = [0; 32];
    for (index, byte) in key.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)
            .map_err(|_| "peer public key encoding")?;
    }
    Ok(key)
}

fn safe_decode(_: serde_json::Error) -> String {
    "invalid embedded input".into()
}

fn read_string(env: &mut JNIEnv<'_>, input: &JString<'_>, max: usize) -> Result<String, String> {
    let text: String = env
        .get_string(input)
        .map_err(|_| "embedded JNI string")?
        .into();
    if text.is_empty() || text.len() > max {
        return Err("embedded input bounds".into());
    }
    Ok(text)
}

fn return_string(env: &mut JNIEnv<'_>, result: Result<String, String>) -> jstring {
    match result {
        Ok(value) => match env.new_string(value) {
            Ok(text) => text.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(reason) => {
            // Fixed internal reasons only: never include config, tickets, keys, or raw provider JSON.
            let _ = env.throw_new("java/lang/IllegalStateException", reason);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_assemblePackagedConfig(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let result = read_string(&mut env, &request, 8 * 1024 * 1024)
        .and_then(|text| assemble_packaged_config_request_json(&text));
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_initializeRuntime(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
    sha: JString<'_>,
    entropy: JString<'_>,
    bootstrap: JString<'_>,
    callback: JObject<'_>,
) -> jstring {
    let result = (|| {
        let config = read_string(&mut env, &config, 2 * 1024 * 1024)?;
        let sha = read_string(&mut env, &sha, 64)?;
        let entropy = read_string(&mut env, &entropy, 128)?;
        let bootstrap = read_string(&mut env, &bootstrap, 33 * 1024 * 1024)?;
        initialize(&mut env, config, sha, entropy, bootstrap, callback)
    })();
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_handleOwnerFrame(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    frame: JByteArray<'_>,
) -> jbyteArray {
    let result = (|| {
        let length = env
            .get_array_length(&frame)
            .map_err(|_| "owner frame length")?;
        if length <= 0 || length > 128 * 1024 {
            return Err("owner frame bounds".into());
        }
        let bytes = env
            .convert_byte_array(&frame)
            .map_err(|_| "owner frame bytes")?;
        let host = host()?;
        if decode_product_activation_request(&bytes).is_ok() {
            Checkout::take(host.activation_server)?
                .get()
                .handle_frame(&bytes)
                .map_err(|_| "product activation rejected".to_owned())
        } else {
            Checkout::take(host.server)?
                .get()
                .handle_frame(&bytes)
                .map_err(|_| "owner dispatch rejected".to_owned())
        }
    })();
    match result {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map_or(std::ptr::null_mut(), |array| array.into_raw()),
        Err(reason) => {
            let _ = env.throw_new("java/lang/IllegalStateException", reason);
            std::ptr::null_mut()
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ClientInput {
    client_id: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct StartInput {
    client_id: String,
    activation_id: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RequestCommand<R, C> {
    request: R,
    command: C,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ProposalReciprocal<P, R> {
    proposal: P,
    reciprocal: R,
}

fn complete_start(host: &Host, input: &str) -> Result<String, String> {
    let request: StartInput = serde_json::from_str(input).map_err(safe_decode)?;
    if request.activation_id.is_empty() || request.activation_id.len() > 128 {
        return Err("activation identity bounds".into());
    }
    let client = serde_json::from_value(json!(request.client_id)).map_err(safe_decode)?;
    // This slot serializes Start/retry, but no mutex remains held across Java.
    let mut sender = Checkout::take(host.activation_sender.clone())?;
    if let Some(pending) = &sender.get().pending {
        if pending.client_id != request.client_id || pending.activation_id != request.activation_id
        {
            return Err("another activation is retained".into());
        }
    } else {
        let material = {
            let mut provider = Checkout::take(host.provider.clone())?;
            provider
                .get()
                .complete_media_start_for_activation(&client, host.clock.now_ms()?)
                .map_err(|_| "full product Start incomplete")?
        }; // Provider restored before signing, transport or graph callbacks.
        sender.get().pending = Some(PendingActivation {
            client_id: request.client_id,
            activation_id: request.activation_id,
            material,
            frame: None,
            response: None,
        });
    }
    let pending = sender
        .get()
        .pending
        .as_mut()
        .ok_or("activation state absent")?;
    if pending.frame.is_none() {
        let now = host.clock.now_ms()?;
        let grant = serde_json::from_value(json!(host.route_grant_id)).map_err(safe_decode)?;
        let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
        let remote = serde_json::from_value(json!(host.remote_peer_id)).map_err(safe_decode)?;
        let authority = host
            .authority
            .current_owner_projection(&grant, &local, &remote, now)?;
        let proof = &pending.material.proof;
        if authority.route_configuration_sha256 != host.route_configuration_sha256
            || authority.authority_provider_epoch_id != proof.provider_epoch_id
            || authority.authority_client_id != proof.client_id
            || authority.authority_runtime_lease_id != proof.lease_id
            || authority.expires_at_ms <= now
        {
            return Err("activation current route differs from completed Start".into());
        }
        let request = ProductActivationRequest {
            schema_id: PRODUCT_ACTIVATION_REQUEST_SCHEMA.to_owned(),
            activation_id: pending.activation_id.clone(),
            issued_at_ms: now,
            expires_at_ms: authority.expires_at_ms.min(now.saturating_add(30_000)),
            target_peer_id: host.remote_peer_id.clone(),
            authority,
            proof: proof.clone(),
            completion_json: pending.material.completion_json.clone(),
            signer_key_id: host.callbacks.key_id().to_owned(),
            signature_base64: String::new(),
        };
        pending.frame = Some(encode_product_activation_request(request, &host.callbacks)?);
    }
    if pending.response.is_none() {
        let frame = pending.frame.as_ref().ok_or("activation frame absent")?;
        let bytes = host.callbacks.clone().exchange(frame, 128 * 1024)?;
        let response = decode_product_activation_response(&bytes)?;
        verify_product_activation_response(
            &response,
            &pending.activation_id,
            frame,
            &host.remote_key_id,
            &host.remote_public_key,
        )?;
        if response.status == OwnerDispatchStatus::Completed
            && response.readback.as_ref().map_or(true, |readback| {
                readback.route_grant_id != host.route_grant_id
                    || readback.resulting_state_revision == 0
            })
        {
            return Err("activation acknowledgement route differs".into());
        }
        pending.response = Some(response);
    }
    // This is historical completion evidence. Current frame/route evidence is queried separately.
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.product_start_acknowledged.v1",
        "completion_json":pending.material.completion_json,
        "activation":pending.response})
        .to_string(),
    )
}

fn command(operation: &str, input: &str) -> Result<String, String> {
    let host = host()?;
    let now = host.clock.now_ms()?;
    match operation {
        "runtime_evidence" => Checkout::take(host.provider)?
            .get()
            .evidence_json()
            .map_err(|_| "runtime evidence unavailable".into()),
        "admission" => Checkout::take(host.provider)?
            .get()
            .execute_admission_json(input)
            .map_err(|_| "admission rejected".into()),
        "media_command" => Checkout::take(host.provider)?
            .get()
            .handle_server_mutation_json(input, now)
            .map_err(|_| "media command rejected".into()),
        "complete_media_start" => complete_start(&host, input),
        "complete_media_stop" => {
            let request: ClientInput = serde_json::from_str(input).map_err(safe_decode)?;
            let client = serde_json::from_value(json!(request.client_id)).map_err(safe_decode)?;
            Checkout::take(host.provider)?
                .get()
                .complete_media_stop_for_cleanup(&client, now)
                .map_err(|_| "media cleanup rejected".into())
        }
        "peer_snapshot" => host.authority.snapshot_json(),
        "peer_status" => {
            let proposal = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.review_peer_status(proposal, now)?)
                .map_err(safe_decode)
        }
        "enroll_peer" => {
            let request = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.review_enrollment(&request, now)?)
                .map_err(safe_decode)
        }
        "prepare_common_lan" => {
            let draft = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.prepare_common_lan_context(draft)?)
                .map_err(safe_decode)
        }
        "apply_reciprocal" => {
            let request = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.apply_common_lan_reciprocal(&request, now)?)
                .map_err(safe_decode)
        }
        "apply_session" => {
            let parts: ProposalReciprocal<_, _> =
                serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.apply_common_lan_session(
                &parts.proposal,
                &parts.reciprocal,
                now,
            )?)
            .map_err(safe_decode)
        }
        "issue_route" => {
            let parts: RequestCommand<_, _> = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.issue_common_lan_route(
                &parts.request,
                &parts.command,
                now,
            )?)
            .map_err(safe_decode)
        }
        "terminate_route" => {
            let parts: RequestCommand<_, _> = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.terminate_route(
                &parts.request,
                &parts.command,
                now,
            )?)
            .map_err(safe_decode)
        }
        "complete_route_cleanup" => {
            let parts: RequestCommand<_, _> = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.complete_route_cleanup(
                &parts.request,
                &parts.command,
                now,
            )?)
            .map_err(safe_decode)
        }
        "current_route" => {
            let grant = serde_json::from_str(input).map_err(safe_decode)?;
            serde_json::to_string(&host.authority.current_route(&grant, now)?).map_err(safe_decode)
        }
        _ => Err("unsupported embedded runtime operation".into()),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_runtimeCommand(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    operation: JString<'_>,
    input: JString<'_>,
) -> jstring {
    let result = (|| {
        let operation = read_string(&mut env, &operation, 64)?;
        let input = read_string(&mut env, &input, 2 * 1024 * 1024)?;
        command(&operation, &input).map_err(|_| "embedded runtime operation rejected".into())
    })();
    return_string(&mut env, result)
}
