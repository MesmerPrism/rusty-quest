//! Process-owned composition of the reusable authority and platform registry.
//!
//! The slots are checked out before entering Java. Metadata/authority handles
//! remain readable, but a second mutation cannot reenter an active provider.

use super::common_lan_signing::{
    signing_authority_from_live_snapshot, validate_common_lan_context_for_signing,
    CommonLanSigningPolicy,
};
use super::java_bridge::JavaOwnerCallbacks;
use super::native_fence_jni;
use super::owner_failure::{self, Stage as OwnerFailureStage};
use super::packaged_config::assemble_packaged_config_request_json;
use super::packaged_route::PackagedDuplexRoute;
use super::process_fence::NativeCapability;
use super::runtime_slot::Checkout;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use rusty_manifold_peer::{
    ManifoldCommonLanReciprocalEd25519Context, COMMON_LAN_RECIPROCAL_ED25519_SIGNATURE_SCHEMA,
};
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
use serde_json::{json, Value};
use std::ops::Deref;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

#[path = "pair_ceremony.rs"]
mod pair_ceremony;
#[path = "peer_lifecycle.rs"]
mod peer_lifecycle;
#[path = "retained_cleanup_host.rs"]
mod retained_cleanup_host;

type DispatchServer = OwnerDispatchServer<
    QuestOwnerDispatchAuthorityVerifier,
    retained_cleanup_host::RetainingRegistry,
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
    device_peers: Vec<AndroidMediaDevicePeerPlacement>,
    replay: OwnerDispatchReplaySnapshot,
    activation_replay: ProductActivationReplaySnapshot,
}

struct ConfiguredProjectionSource {
    authority: QuestEmbeddedDuplexAuthority,
    route_grant_id: Arc<Mutex<String>>,
    authority_peer_id: String,
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
        let grant = self
            .route_grant_id
            .lock()
            .map_err(|_| "current route binding poisoned")?
            .clone();
        let source = QuestEmbeddedDuplexProjectionSource::new(
            self.authority.clone(),
            serde_json::from_value(json!(grant)).map_err(safe_decode)?,
            serde_json::from_value(json!(self.authority_peer_id)).map_err(safe_decode)?,
        );
        let current = source.current_projection(ticket, target_peer_id, mode, now_ms)?;
        if current.route_configuration_sha256 != self.route_configuration_sha256 {
            return Err("current route differs from packaged control/media configuration".into());
        }
        Ok(current)
    }
}

#[derive(Clone)]
struct Host {
    capability: Arc<NativeCapability>,
    provider: Arc<Mutex<Option<QuestBrokerRuntimeProvider>>>,
    server: Arc<Mutex<Option<DispatchServer>>>,
    activation_server: Arc<Mutex<Option<ActivationServer>>>,
    cleanup_server: Arc<Mutex<Option<retained_cleanup_host::Server>>>,
    cleanup: retained_cleanup_host::Cleanup,
    activation_sender: Arc<Mutex<Option<ActivationSender>>>,
    authority: QuestEmbeddedDuplexAuthority,
    clock: AuthorityClock,
    callbacks: JavaOwnerCallbacks,
    local_peer_id: String,
    remote_peer_id: String,
    route_grant_id: Arc<Mutex<String>>,
    route_configuration_sha256: String,
    packaged_route: PackagedDuplexRoute,
    remote_key_id: String,
    remote_public_key: [u8; 32],
    local_public_key: [u8; 32],
    pair_state: Arc<Mutex<pair_ceremony::PairState>>,
    config_sha256: String,
    installed_signing_certificate_sha256: String,
    peer_lifecycle: Arc<Mutex<Option<peer_lifecycle::State>>>,
    owner_effect_attempted: Arc<AtomicBool>,
    owner_dispatch_failure: Arc<Mutex<Option<&'static str>>>,
    restored_owner_replay: bool,
}

impl Host {
    fn current_grant_id(&self) -> Result<String, String> {
        self.route_grant_id
            .lock()
            .map(|id| id.clone())
            .map_err(|_| "current route binding poisoned".into())
    }
}

#[derive(Default)]
struct ProcessState {
    initializing: bool,
    closing: bool,
    host_leases: usize,
    lease_integrity_failed: bool,
    host: Option<Host>,
    last_closed_sha256: Option<String>,
    last_peer_close_receipt: Option<String>,
}

struct HostLease(Host);

impl Deref for HostLease {
    type Target = Host;

    fn deref(&self) -> &Host {
        &self.0
    }
}

impl Drop for HostLease {
    fn drop(&mut self) {
        // A poisoned process gate is already unusable for a successful close,
        // but return this lease so the in-flight count remains truthful.
        let mut state = process()
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        if let Some(remaining) = state.host_leases.checked_sub(1) {
            state.host_leases = remaining;
        } else {
            state.lease_integrity_failed = true;
            state.closing = true;
        }
    }
}

static PROCESS: OnceLock<Mutex<ProcessState>> = OnceLock::new();
static PREPARED_ROUTE: OnceLock<Mutex<Option<(String, PackagedDuplexRoute)>>> = OnceLock::new();

fn process() -> &'static Mutex<ProcessState> {
    PROCESS.get_or_init(|| Mutex::new(ProcessState::default()))
}

fn prepared_route() -> &'static Mutex<Option<(String, PackagedDuplexRoute)>> {
    PREPARED_ROUTE.get_or_init(|| Mutex::new(None))
}

fn stage_packaged_route(config_sha256: String, route: PackagedDuplexRoute) -> Result<(), String> {
    let capability = native_fence_jni::active()?;
    let state = process().lock().map_err(|_| "process state poisoned")?;
    if state.initializing || state.closing || state.host.is_some() {
        return Err("packaged route cannot replace a process authority".into());
    }
    let mut slot = prepared_route()
        .lock()
        .map_err(|_| "packaged route slot poisoned")?;
    if slot.as_ref().is_some_and(|(prior_sha, prior_route)| {
        prior_sha != &config_sha256 || prior_route != &route
    }) {
        return Err("another packaged route is already staged".into());
    }
    *slot = Some((config_sha256, route));
    drop(state);
    drop(slot);
    capability.require_live()?;
    Ok(())
}

fn exact_staged_route(config_sha256: &str) -> Result<PackagedDuplexRoute, String> {
    let slot = prepared_route()
        .lock()
        .map_err(|_| "packaged route slot poisoned")?;
    let (prior_sha, route) = slot.as_ref().ok_or("validated packaged route absent")?;
    if prior_sha != config_sha256 {
        return Err("packaged route differs from runtime configuration".into());
    }
    Ok(route.clone())
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

fn host() -> Result<HostLease, String> {
    let mut state = process().lock().map_err(|_| "process state poisoned")?;
    if state.closing {
        return Err("embedded runtime closing".into());
    }
    let host = state
        .host
        .clone()
        .ok_or("embedded runtime not initialized")?;
    state.host_leases = state
        .host_leases
        .checked_add(1)
        .ok_or("runtime host lease overflow")?;
    drop(state);
    let lease = HostLease(host);
    lease.capability.require_live()?;
    Ok(lease)
}

fn slot_present<T>(slot: &Arc<Mutex<Option<T>>>) -> Result<(), String> {
    let value = slot.try_lock().map_err(|_| "runtime slot busy")?;
    if value.is_none() {
        return Err("runtime slot busy".into());
    }
    Ok(())
}

fn close_no_media_runtime(expected_sha: &str) -> Result<String, String> {
    let capability = native_fence_jni::active()?;
    close_no_media_runtime_with_capability(expected_sha, &capability)
}

fn close_no_media_runtime_with_capability(
    expected_sha: &str,
    capability: &NativeCapability,
) -> Result<String, String> {
    capability.require_live()?;
    if expected_sha.len() != 64
        || !expected_sha
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err("runtime configuration identity bounds".into());
    }
    let host = {
        let mut state = process().lock().map_err(|_| "process state poisoned")?;
        if state.initializing {
            return Err("runtime initialization busy".into());
        }
        if let Some(host) = &state.host {
            if host.config_sha256 != expected_sha {
                return Err("runtime configuration identity differs".into());
            }
        }
        let route = prepared_route()
            .lock()
            .map_err(|_| "packaged route slot poisoned")?;
        match route.as_ref() {
            Some((sha, _)) if sha == expected_sha => {}
            Some(_) => return Err("packaged route identity differs".into()),
            None if state.host.is_some() => return Err("packaged route absent during close".into()),
            None if state.last_closed_sha256.as_deref() == Some(expected_sha) => {
                return Ok(no_media_close_receipt(expected_sha, "already_closed").to_string());
            }
            None => return Err("packaged route absent during close".into()),
        }
        state.closing = true;
        if state.host_leases != 0 || state.lease_integrity_failed {
            return Err("runtime host busy".into());
        }
        state.host.clone()
    };

    if let Some(host) = &host {
        if host.owner_effect_attempted.load(Ordering::SeqCst) || host.restored_owner_replay {
            return Err("owner effect history requires typed cleanup".into());
        }
        slot_present(&host.provider)?;
        slot_present(&host.server)?;
        slot_present(&host.activation_server)?;
        slot_present(&host.cleanup_server)?;
        slot_present(&host.activation_sender)?;
        if host
            .activation_sender
            .lock()
            .map_err(|_| "activation sender poisoned")?
            .as_ref()
            .is_some_and(|sender| sender.pending.is_some())
        {
            return Err("activation pending".into());
        }
        let server = host.server.lock().map_err(|_| "dispatch slot poisoned")?;
        let replay = server
            .as_ref()
            .ok_or("dispatch slot busy")?
            .replay_snapshot();
        if !replay.pending_request_sha256.is_empty() || !replay.terminal.is_empty() {
            return Err("owner dispatch history requires typed cleanup".into());
        }
        drop(server);
        let provider = host.provider.lock().map_err(|_| "provider slot poisoned")?;
        let evidence_json = provider
            .as_ref()
            .ok_or("provider slot busy")?
            .evidence_json()
            .map_err(|_| "runtime evidence unavailable")?;
        let evidence: serde_json::Value =
            serde_json::from_str(&evidence_json).map_err(|_| "runtime evidence invalid")?;
        if !evidence
            .get("media_pending_action")
            .is_some_and(|value| value.is_null())
        {
            return Err("media action pending".into());
        }
        if let Some(value) = evidence
            .get("media_runtime_state")
            .filter(|value| !value.is_null())
        {
            if value.get("phase").and_then(|v| v.as_str()) != Some("planned")
                || value
                    .get("applied_request_ids")
                    .and_then(|v| v.as_array())
                    .is_none_or(|requests| !requests.is_empty())
            {
                return Err("media runtime is not unused".into());
            }
        } else if !evidence
            .get("media_runtime_state")
            .is_some_and(|value| value.is_null())
        {
            return Err("media runtime evidence absent".into());
        }
    }
    drop(host);
    let removed = {
        let mut state = process().lock().map_err(|_| "process state poisoned")?;
        if !state.closing || state.host_leases != 0 || state.lease_integrity_failed {
            return Err("runtime host busy".into());
        }
        let mut route = prepared_route()
            .lock()
            .map_err(|_| "packaged route slot poisoned")?;
        if route.as_ref().map(|(sha, _)| sha.as_str()) != Some(expected_sha) {
            return Err("packaged route identity differs".into());
        }
        let removed = state.host.take();
        route.take();
        state.last_closed_sha256 = Some(expected_sha.to_owned());
        state.closing = false;
        removed
    };
    let disposition = if removed.is_some() {
        "host_closed"
    } else {
        "staged_route_closed"
    };
    drop(removed);
    capability.require_live()?;
    Ok(no_media_close_receipt(expected_sha, disposition).to_string())
}

fn no_media_close_receipt(expected_sha: &str, disposition: &str) -> serde_json::Value {
    let mut receipt = json!({"$schema":"rusty.quest.embedded_duplex.no_media_closed.v1",
     "config_sha256":expected_sha,"disposition":disposition});
    if crate::own_stereo_capture_runtime::capture_route_selected() {
        receipt["cleanup_scope"] = json!("peer_subscription_only");
        receipt["own_app_capture_retained"] =
            json!(crate::own_stereo_capture_runtime::capture_claimed());
    }
    receipt
}

fn process_idle_for_enrollment() -> bool {
    let Ok(state) = process().lock() else {
        return false;
    };
    if state.initializing
        || state.closing
        || state.host.is_some()
        || state.host_leases != 0
        || state.lease_integrity_failed
    {
        return false;
    }
    let Ok(route) = prepared_route().lock() else {
        return false;
    };
    route.is_none()
}

fn initialize(
    env: &mut JNIEnv<'_>,
    config_json: String,
    expected_sha: String,
    entropy: String,
    bootstrap_json: String,
    callback: JObject<'_>,
) -> Result<String, String> {
    let capability = native_fence_jni::active()?;
    initialize_with_capability(
        &capability,
        || {
            build_host(
                env,
                config_json,
                expected_sha,
                entropy,
                bootstrap_json,
                callback,
            )
        },
        |state, (host, result)| {
            state.host = Some(host);
            state.last_closed_sha256 = None;
            state.last_peer_close_receipt = None;
            Ok(result)
        },
    )
}

// Initialization ownership must unwind even when construction or its final
// capability check fails. This releases only the process gate, never effects,
// the staged route, the native capability, or durable cleanup obligations.
struct InitializationGuard;

impl Drop for InitializationGuard {
    fn drop(&mut self) {
        process()
            .lock()
            .unwrap_or_else(|poison| poison.into_inner())
            .initializing = false;
    }
}

fn initialize_with_capability<T, R>(
    capability: &NativeCapability,
    build: impl FnOnce() -> Result<T, String>,
    publish: impl FnOnce(&mut ProcessState, T) -> Result<R, String>,
) -> Result<R, String> {
    capability.require_live()?;
    {
        let mut state = process().lock().map_err(|_| "process state poisoned")?;
        if state.initializing || state.closing || state.host.is_some() {
            return Err("embedded runtime already owned".into());
        }
        state.initializing = true;
    }
    let _initialization = InitializationGuard;
    let built = build();
    capability.require_live()?;
    let mut state = process().lock().map_err(|_| "process state poisoned")?;
    publish(&mut state, built?)
}

fn authenticate_own_capture_lock(
    config: &QuestBrokerRuntimeConfig,
) -> Result<Option<String>, String> {
    use rusty_quest_feature_activation::{inspect_feature_lock_v2, FeatureLockV2Effect};
    let compiled = crate::own_stereo_capture_runtime::own_capture_provider_requested();
    let mut admitted: Option<String> = None;
    for authority in config
        .packaged_authority
        .client_locks
        .iter()
        .filter_map(|client| client.media_lifecycle_authority.as_ref())
    {
        let lock: Value =
            serde_json::from_str(&authority.app_feature_lock_json).map_err(safe_decode)?;
        let rows: Vec<_> = lock
            .get("features")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter(|row| {
                row.get("module_id").and_then(Value::as_str) == Some("quest-stereo-input-set")
            })
            .collect();
        if rows.is_empty() {
            continue;
        }
        if rows.len() != 1 {
            return Err("Own feature module duplicated".into());
        }
        let row = rows[0];
        if row.get("selected").and_then(Value::as_bool) != Some(true) {
            if compiled {
                return Err("compiled Own provider feature unselected".into());
            }
            continue;
        }
        if !compiled {
            return Err("selected Own feature provider unavailable".into());
        }
        let feature = row
            .get("feature_id")
            .and_then(Value::as_str)
            .ok_or("Own feature identity absent")?;
        let listed = lock
            .get("selected_features")
            .and_then(Value::as_array)
            .is_some_and(|values| {
                values
                    .iter()
                    .filter(|v| v.as_str() == Some(feature))
                    .count()
                    == 1
            });
        if !listed
            || row.get("owner_lane").and_then(Value::as_str) != Some("quest-adapter")
            || row.get("run_activation_default").and_then(Value::as_str) != Some("disabled")
            || row.pointer("/activation/rule").and_then(Value::as_str)
                != Some("selected-lock-and-runtime-input")
        {
            return Err("Own feature activation scope rejected".into());
        }
        let inspected = inspect_feature_lock_v2(
            &authority.app_feature_lock_json,
            &authority.app_feature_lock_sha256,
            feature,
        )
        .map_err(|_| "Own feature exact lock authentication rejected")?;
        if inspected.module_id != "quest-stereo-input-set"
            || inspected.receipt_schema != "rusty.quest.stereo_input_set.activation_receipt.v1"
            || !inspected
                .runtime_inputs
                .iter()
                .any(|input| input == "quest.stereo.concurrent-inputs")
            || !inspected
                .selected_has_effect(FeatureLockV2Effect::Input, "quest.stereo.concurrent-inputs")
            || !inspected
                .union_has_effect(FeatureLockV2Effect::Input, "quest.stereo.concurrent-inputs")
        {
            return Err("Own feature runtime input rejected".into());
        }
        if admitted
            .as_ref()
            .is_some_and(|sha| sha != &inspected.raw_sha256)
        {
            return Err("Own feature client locks disagree".into());
        }
        admitted = Some(inspected.raw_sha256);
    }
    if compiled && admitted.is_none() {
        return Err("compiled Own provider authenticated feature absent".into());
    }
    Ok(admitted)
}

fn build_host(
    env: &mut JNIEnv<'_>,
    config_json: String,
    expected_sha: String,
    entropy: String,
    bootstrap_json: String,
    callback: JObject<'_>,
) -> Result<(Host, String), String> {
    let capability = native_fence_jni::active()?;
    let config: QuestBrokerRuntimeConfig =
        serde_json::from_str(&config_json).map_err(safe_decode)?;
    let own_capture_lock = authenticate_own_capture_lock(&config)?;
    let installed_signing_certificate_sha256 = {
        let grants: Vec<_> = config
            .admission
            .snapshot
            .grants
            .iter()
            .filter(|g| {
                g.identity.platform_subject
                    == exact_staged_route(&expected_sha)
                        .map(|r| r.package_name)
                        .unwrap_or_default()
            })
            .collect();
        let [grant] = grants.as_slice() else {
            return Err("installed product identity ambiguous".into());
        };
        let fingerprint = grant
            .identity
            .signing_fingerprint
            .strip_prefix("sha256:")
            .ok_or("installed signing certificate prefix")?;
        if fingerprint.len() != 64
            || !fingerprint
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err("installed signing certificate bounds".into());
        }
        fingerprint.to_owned()
    };

    if config.bridge_kind != QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni {
        return Err("embedded bridge required".into());
    }
    let bootstrap: Bootstrap = serde_json::from_str(&bootstrap_json).map_err(safe_decode)?;
    let restored_owner_replay = !bootstrap.replay.pending_request_sha256.is_empty()
        || !bootstrap.replay.terminal.is_empty()
        || !bootstrap
            .activation_replay
            .pending_request_sha256
            .is_empty()
        || !bootstrap.activation_replay.terminal.is_empty();
    let route = exact_staged_route(&expected_sha)?;
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
        || bootstrap.route_configuration_sha256.len() != 71
        || !bootstrap.route_configuration_sha256.starts_with("sha256:")
        || !bootstrap.route_configuration_sha256.as_bytes()[7..]
            .iter()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(c))
        || route.route_configuration_sha256 != bootstrap.route_configuration_sha256
        || route.local_peer().peer_id != bootstrap.local_peer_id
        || route.peers[1 - route.installed_peer_index].peer_id != bootstrap.remote_peer_id
        || route.runtime_spec_ids[route.installed_peer_index] != bootstrap.runtime_spec_id
        || route.runtime_spec_ids[1 - route.installed_peer_index]
            != bootstrap.incoming_runtime_spec_id
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
        capability.clone(),
    )?;
    let remote_public_key = decode_key(&bootstrap.remote_public_key_hex)?;
    let local_public_key = callbacks.local_public_key()?;
    let owner_dispatch_failure = Arc::new(Mutex::new(None));
    let cleanup = retained_cleanup_host::Cleanup::new(
        authority.clone(),
        callbacks.clone(),
        clock.clone(),
        bootstrap.local_peer_id.clone(),
        bootstrap.remote_peer_id.clone(),
        bootstrap.remote_key_id.clone(),
        remote_public_key,
        capability.generation,
        owner_dispatch_failure.clone(),
    )?;
    let cleanup_replay = serde_json::from_str(&callbacks.load_retained_cleanup_replay()?)
        .map_err(|_| "retained cleanup replay decode")?;
    let cleanup_server = retained_cleanup_host::Server::restore(
        bootstrap.local_peer_id.clone(),
        callbacks.key_id().to_owned(),
        cleanup.clone(),
        callbacks.clone(),
        callbacks.clone(),
        clock.clone(),
        cleanup_replay,
        callbacks.clone(),
    )?;
    let remote = RemoteOwnerDispatchExecutor::new(
        callbacks.clone(),
        callbacks.clone(),
        remote_public_key,
        bootstrap.remote_key_id.clone(),
    )?;
    let route_grant_binding = Arc::new(Mutex::new(bootstrap.route_grant_id.clone()));
    let projections = ConfiguredProjectionSource {
        authority: authority.clone(),
        route_grant_id: route_grant_binding.clone(),
        authority_peer_id: bootstrap.local_peer_id.clone(),
        route_configuration_sha256: bootstrap.route_configuration_sha256.clone(),
    };
    let executor = CompositeAndroidMediaOwnerExecutor::new(
        capability.generation,
        bootstrap.local_peer_id.clone(),
        placements.clone(),
        Box::new(retained_cleanup_host::RetainingRegistry {
            cleanup: cleanup.clone(),
            callbacks: callbacks.clone(),
        }),
        Box::new(remote),
        Box::new(projections),
        Box::new(clock.clone()),
    )?;
    let executor = retained_cleanup_host::Executor::new(
        executor,
        cleanup.clone(),
        placements.clone(),
        route_grant_binding.clone(),
    );
    provider
        .install_media_owner_executor(Box::new(executor))
        .map_err(|_| "owner executor rejected")?;
    let verifier =
        QuestOwnerDispatchAuthorityVerifier::new(authority.clone(), Arc::new(clock.clone()));
    let server = OwnerDispatchServer::restore(
        bootstrap.local_peer_id.clone(),
        verifier.clone(),
        retained_cleanup_host::RetainingRegistry {
            cleanup: cleanup.clone(),
            callbacks: callbacks.clone(),
        },
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
    let own_capture_enabled = if own_capture_lock.is_some() {
        crate::own_stereo_capture_runtime::prepare_capture_bootstrap()?
    } else {
        false
    };
    let mut result = json!({"$schema":"rusty.quest.embedded_duplex.runtime_initialized.v1",
        "runtime": status, "owner_placements": placements,
        "incoming_owner_placements": incoming_placements,
        "outgoing_runtime_spec": outgoing_spec, "incoming_runtime_spec": incoming_spec,
        "executor_generation": capability.generation,
        "app_process_generation":capability.binding.generation,
        "app_record_sha256":capability.binding.record_sha256});
    if own_capture_enabled {
        result["own_stereo_capture_enabled"] = json!(true);
        result["own_capture_bootstrap"] = json!({"scope":"app_owned_capture_bootstrap","app_feature_lock_sha256":own_capture_lock,
              "runtime_input":"quest.stereo.concurrent-inputs","renderer_effective":false});
    }
    let result = result.to_string();
    Ok((
        Host {
            capability,
            provider: Arc::new(Mutex::new(Some(provider))),
            server: Arc::new(Mutex::new(Some(server))),
            activation_server: Arc::new(Mutex::new(Some(activation_server))),
            cleanup_server: Arc::new(Mutex::new(Some(cleanup_server))),
            cleanup,
            activation_sender: Arc::new(Mutex::new(Some(ActivationSender::default()))),
            authority,
            clock,
            callbacks,
            local_peer_id: bootstrap.local_peer_id,
            remote_peer_id: bootstrap.remote_peer_id,
            route_grant_id: route_grant_binding,
            route_configuration_sha256: bootstrap.route_configuration_sha256,
            packaged_route: route,
            remote_key_id: bootstrap.remote_key_id,
            remote_public_key,
            local_public_key,
            pair_state: Arc::new(Mutex::new(pair_ceremony::PairState::default())),
            config_sha256: expected_sha,
            installed_signing_certificate_sha256,
            peer_lifecycle: Arc::new(Mutex::new(Some(peer_lifecycle::State::default()))),
            owner_effect_attempted: Arc::new(AtomicBool::new(false)),
            owner_dispatch_failure,
            restored_owner_replay,
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

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_selectLocalAfterTerminal(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    expected_sha: JString<'_>,
) -> jstring {
    let result = (|| {
        let sha = read_string(&mut env, &expected_sha, 64)?;
        let state = process().lock().map_err(|_| "process state poisoned")?;
        let closed = !state.initializing
            && !state.closing
            && !state.lease_integrity_failed
            && state.host.is_none()
            && state.host_leases == 0
            && state.last_closed_sha256.as_deref() == Some(sha.as_str());
        crate::own_stereo_capture_runtime::select_local_after_terminal(closed)?;
        Ok(
            serde_json::json!({"schema":"rusty.quest.local_rollback_native.v1","config_sha256":sha,
   "feature_enabled":false,"physical_cleanup":"terminal","scope":"route-selection-only"})
            .to_string(),
        )
    })();
    return_string(&mut env, result)
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
        .and_then(|text| {
            native_fence_jni::active()?;
            assemble_packaged_config_request_json(&text)
        })
        .and_then(|(result, config_sha256, route)| {
            stage_packaged_route(config_sha256, route)?;
            Ok(result)
        });
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
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_closeNoMediaRuntime(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    expected_sha: JString<'_>,
) -> jstring {
    let result =
        read_string(&mut env, &expected_sha, 64).and_then(|sha| close_no_media_runtime(&sha));
    return_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_finishNativeNoMediaCleanup(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    generation: jni::sys::jlong,
    expected_sha: JString<'_>,
) {
    let result = (|| {
        let sha = read_string(&mut env, &expected_sha, 64)?;
        let cap = native_fence_jni::active()?;
        if generation <= 0 || cap.generation != generation as u64 {
            return Err("native cleanup incarnation differs".into());
        }
        {
            let state = process().lock().map_err(|_| "process state poisoned")?;
            if state.initializing
                || state.closing
                || state.host.is_some()
                || state.host_leases != 0
                || state.lease_integrity_failed
                || state.last_closed_sha256.as_deref() != Some(sha.as_str())
            {
                return Err("native no-media closure not retained".into());
            }
            let route = prepared_route()
                .lock()
                .map_err(|_| "packaged route slot poisoned")?;
            if route.is_some() {
                return Err("native route cleanup pending".into());
            }
        }
        cap.retire()
    })();
    if let Err(reason) = result {
        let _ = env.throw_new("java/lang/IllegalStateException", reason);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_processIdleForEnrollment(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    if process_idle_for_enrollment() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
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
        if bytes.starts_with(pair_ceremony::FRAME_MAGIC) {
            let result = pair_ceremony::handle_frame(&host, &bytes);
            host.capability.require_live()?;
            return result;
        }
        if bytes.starts_with(retained_cleanup_host::PREPARE_MAGIC) {
            let result = owner_failure::observe(
                host.cleanup.prepare_frame(&bytes),
                &host.owner_dispatch_failure,
                OwnerFailureStage::Prepare,
            );
            owner_failure::observe(
                host.capability.require_live(),
                &host.owner_dispatch_failure,
                OwnerFailureStage::Capability,
            )?;
            return result;
        }
        if let Ok(request) = serde_json::from_slice::<
            rusty_quest_media_stream_android::RetainedCleanupDispatchRequest,
        >(&bytes)
        {
            host.owner_effect_attempted.store(true, Ordering::SeqCst);
            let result = owner_failure::observe(
                (|| {
                    Checkout::take(host.cleanup_server.clone())?
                        .get()
                        .handle(&request)
                })(),
                &host.owner_dispatch_failure,
                OwnerFailureStage::Dispatch,
            );
            owner_failure::observe(
                host.capability.require_live(),
                &host.owner_dispatch_failure,
                OwnerFailureStage::Capability,
            )?;
            return result;
        }
        host.owner_effect_attempted.store(true, Ordering::SeqCst);
        let result = if decode_product_activation_request(&bytes).is_ok() {
            Checkout::take(host.activation_server.clone())?
                .get()
                .handle_frame(&bytes)
                .map_err(|_| "product activation rejected".to_owned())
        } else {
            Checkout::take(host.server.clone())?
                .get()
                .handle_frame(&bytes)
                .map_err(|error| {
                    let stage = match error.as_str() {
                        "dispatch frame bounds"|"dispatch frame bounds or magic"|"dispatch frame lengths"|
                        "metadata length"|"payload length"|"payload digest mismatch"|"invalid dispatch request metadata"|
                        "invalid ticket payload"|"invalid dispatch request" => "FRAME_DECODE",
                        "dispatch request binding mismatch" => "TICKET_TARGET_BINDING",
                        "dispatch request is not currently fresh"|"owner dispatch request is not currently fresh" => "FRESHNESS",
                        "dispatch signer is not currently enrolled"|"dispatch signature"|"request signature"|
                        "invalid enrolled key"|"invalid Ed25519 signature base64 length"|
                        "invalid Ed25519 signature base64 padding"|"invalid Ed25519 signature base64 bytes"|
                        "non-canonical Ed25519 signature base64"|"invalid Ed25519 signature base64" => "SIGNER_ADMISSION",
                        "projected signed topology is absent"|"owner projection is stale or altered"|
                        "owner projection expired or malformed"|"owner projection schema mismatch"|
                        "owner projection schema/signing mismatch"|"projection schema"|"peer authority lock poisoned" => "CURRENT_AUTHORITY",
                        "dispatch replay identity collision"|"dispatch replay capacity"|"dispatch replay byte capacity"|
                        "invalid dispatch replay state" => "REPLAY_BINDING",
                        "verified readback JSON is invalid"|"verified owner effect mismatch"|
                        "completed response omitted raw readback"|"invalid dispatch response metadata"|
                        "invalid readback payload"|"readback payload UTF-8"|"unexpected response payload"|
                        "invalid dispatch response"|"response metadata encoding" => "RECEIPT_PROOF",
                        _ if error.starts_with("dispatch pending persistence:")||error.starts_with("java_bridge.replay") => "REPLAY_PERSISTENCE",
                        _ if error.starts_with("java_bridge.sign") => "RECEIPT_SIGNATURE",
                        _ => "FRAME_OR_DISPATCH_BOUNDARY",
                    };
                    if let Ok(mut failed)=host.owner_dispatch_failure.lock(){if failed.is_none(){*failed=Some(stage);}}
                    crate::camera_hwb_marker::log_camera_hwb_marker(format!("status=owner-dispatch-rejected stage={stage} code=PRE_EFFECT_OR_RECEIPT_REJECTED"));
                    "owner dispatch rejected".to_owned()
                })
        };
        host.capability.require_live()?;
        result
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
struct AbortInput {
    client_id: String,
    lease_id: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CleanupTargetInput {
    requester_id: String,
    requester_lease_id: String,
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
                .map_err(|error| {
                    crate::embedded_duplex::cleanup_failure::describe(
                        crate::embedded_duplex::cleanup_failure::Stage::Start,
                        &error,
                    )
                })?
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
        let grant = serde_json::from_value(json!(host.current_grant_id()?)).map_err(safe_decode)?;
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
        let expected_grant = host.current_grant_id()?;
        if response.status == OwnerDispatchStatus::Completed
            && response.readback.as_ref().map_or(true, |readback| {
                readback.route_grant_id != expected_grant || readback.resulting_state_revision == 0
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

fn sign_common_lan(host: &Host, input: &str, now: u64) -> Result<String, String> {
    let context: ManifoldCommonLanReciprocalEd25519Context =
        serde_json::from_str(input).map_err(safe_decode)?;
    let snapshot = host.authority.snapshot_json()?;
    let live = signing_authority_from_live_snapshot(&snapshot, &host.packaged_route, now)?;
    let validated = validate_common_lan_context_for_signing(
        &host.packaged_route,
        &live.enrolled,
        &context,
        CommonLanSigningPolicy {
            local_peer_id: &host.local_peer_id,
            trust_policy_id: &live.trust_policy_id,
            trust_policy_revision: live.trust_policy_revision,
            network_scope_id: &host.packaged_route.network_scope_id,
            now_ms: now,
            max_context_age_ms: 30_000,
            max_future_skew_ms: 0,
            max_context_ttl_ms: 240_000,
        },
    )?;
    // Snapshot and route are owned local values. No host, provider, peer, or
    // process lock remains held when Java enters the private-key callback.
    let signature = host.callbacks.sign_validated_common_lan(&validated)?;
    let signature_hex = signature
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    Ok(json!({
        "$schema": COMMON_LAN_RECIPROCAL_ED25519_SIGNATURE_SCHEMA,
        "signer_peer_id": validated.signer_peer_id,
        "signer_key_id": validated.signer_key_id,
        "context_sha256": validated.context_sha256,
        "signature_hex": signature_hex,
    })
    .to_string())
}

fn command(operation: &str, input: &str) -> Result<String, String> {
    let host = host()?;
    if matches!(
        operation,
        "media_command"
            | "complete_media_start"
            | "complete_media_stop"
            | "resume_media_start_abort"
    ) {
        host.owner_effect_attempted.store(true, Ordering::SeqCst);
    }
    let now = host.clock.now_ms()?;
    let result = match operation {
        "runtime_evidence" => Checkout::take(host.provider.clone())?
            .get()
            .evidence_json()
            .map_err(|_| "runtime evidence unavailable".into()),
        "admission" => Checkout::take(host.provider.clone())?
            .get()
            .execute_admission_json(input)
            .map_err(|_| "admission rejected".into()),
        "media_command" => Checkout::take(host.provider.clone())?
            .get()
            .handle_server_mutation_json(input, now)
            .map_err(|_| "media command rejected".into()),
        "complete_media_start" => complete_start(&host, input),
        "complete_media_stop" => {
            let request: ClientInput = serde_json::from_str(input).map_err(safe_decode)?;
            let client = serde_json::from_value(json!(request.client_id)).map_err(safe_decode)?;
            Checkout::take(host.provider.clone())?
                .get()
                .complete_media_stop_for_cleanup(&client, now)
                .map_err(|_| "media cleanup rejected".into())
        }
        "resume_media_start_abort" => {
            let request: AbortInput = serde_json::from_str(input).map_err(safe_decode)?;
            let grant =
                serde_json::from_value(json!(host.current_grant_id()?)).map_err(safe_decode)?;
            let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
            let remote = serde_json::from_value(json!(host.remote_peer_id)).map_err(safe_decode)?;
            let current = host
                .authority
                .current_owner_projection(&grant, &local, &remote, now)?;
            if current.route_configuration_sha256 != host.route_configuration_sha256
                || current.authority_client_id != request.client_id
                || current.authority_runtime_lease_id != request.lease_id
                || current.expires_at_ms <= now
            {
                return Err("failed-Start cleanup authority is not current".into());
            }
            let client = serde_json::from_value(json!(request.client_id)).map_err(safe_decode)?;
            Checkout::take(host.provider.clone())?
                .get()
                .resume_media_start_abort_for_cleanup(&client, &request.lease_id)
                .map_err(|_| "failed-Start cleanup resume rejected".into())
        }
        "inspect_retained_cleanup_target" => {
            let request: CleanupTargetInput = serde_json::from_str(input).map_err(safe_decode)?;
            let grant =
                serde_json::from_value(json!(host.current_grant_id()?)).map_err(safe_decode)?;
            let requester =
                serde_json::from_value(json!(request.requester_id)).map_err(safe_decode)?;
            let lease =
                serde_json::from_value(json!(request.requester_lease_id)).map_err(safe_decode)?;
            serde_json::to_string(
                &host
                    .authority
                    .retained_cleanup_target(&grant, &requester, &lease, now)?,
            )
            .map_err(safe_decode)
        }
        "peer_snapshot" => host.authority.snapshot_json(),
        "pair_ceremony" => {
            pair_ceremony::require_empty_input(input)?;
            pair_ceremony::run(&host)
        }
        "pair_status" => {
            pair_ceremony::require_empty_input(input)?;
            pair_ceremony::status(&host)
        }
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
        "sign_common_lan" => sign_common_lan(&host, input, now),
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
    };
    host.capability.require_live()?;
    result
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

#[cfg(test)]
mod no_media_close_tests {
    use super::*;
    use crate::embedded_duplex::packaged_route::{
        PackagedEndpoint, PackagedPeer, PackedStereoProfile,
    };
    use crate::embedded_duplex::process_fence::{AppFenceSource, NativeFenceRegistry};
    use std::os::unix::fs::PermissionsExt;

    static TEST_GATE: Mutex<()> = Mutex::new(());
    const SHA: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const OTHER_SHA: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    struct AppFenceFixture(Mutex<String>);

    impl AppFenceSource for AppFenceFixture {
        fn record(&self) -> Result<String, String> {
            self.0
                .lock()
                .map(|value| value.clone())
                .map_err(|_| "fixture poisoned".into())
        }
    }

    struct NativeFenceFixture {
        capability: Arc<NativeCapability>,
        source: Arc<AppFenceFixture>,
    }

    impl NativeFenceFixture {
        fn new() -> Self {
            static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
            let path = std::env::temp_dir().join(format!(
                "rq-runtime-fence-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::SeqCst)
            ));
            std::fs::create_dir(&path).unwrap();
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700)).unwrap();
            let body = "rusty.quest.embedded_duplex.app_process_fence.v1\n1\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n";
            let source = Arc::new(AppFenceFixture(Mutex::new(format!(
                "{body}{}\n",
                rusty_quest_broker_authority::packaged_json_sha256(body)
            ))));
            let capability = NativeFenceRegistry::default()
                .claim(&path, source.clone())
                .unwrap();
            Self { capability, source }
        }

        fn close(&self, sha: &str) -> Result<String, String> {
            close_no_media_runtime_with_capability(sha, &self.capability)
        }
    }

    fn route() -> PackagedDuplexRoute {
        let endpoint = PackagedEndpoint {
            protocol: "tcp".into(),
            host: "127.0.0.1".into(),
            port: 1,
        };
        let peer = PackagedPeer {
            peer_id: "test.peer".into(),
            installed_role_id: "test.role".into(),
            device_id: "test.device".into(),
            left_camera_id: "left".into(),
            right_camera_id: "right".into(),
            media: endpoint.clone(),
            control: endpoint,
        };
        PackagedDuplexRoute {
            product_id: "test.product".into(),
            package_name: "test.package".into(),
            route_configuration_sha256: format!("sha256:{SHA}"),
            network_scope_id: "test.network".into(),
            profile: PackedStereoProfile {
                schema: "test.profile".into(),
                profile_id: "test".into(),
                rmanvid_schema_version: 1,
                frame_layout: "side_by_side".into(),
                eye_order: vec!["left".into(), "right".into()],
                codec: "h264".into(),
                packed_width: 2,
                packed_height: 1,
                per_eye_width: 1,
                per_eye_height: 1,
                frame_rate_hz: 1,
                bitrate_bps: 1,
                max_packet_bytes: 1,
                pair_timestamp_source: "test".into(),
                pairing_policy: "test".into(),
                max_pair_delta_ns: 0,
                stale_eye_reuse_allowed: false,
                cpu_pixel_copy: false,
            },
            peers: [peer.clone(), peer],
            installed_peer_index: 0,
            runtime_spec_ids: ["test.out".into(), "test.in".into()],
        }
    }

    fn reset_with_staged_route() {
        *process().lock().unwrap() = ProcessState::default();
        *prepared_route().lock().unwrap() = Some((SHA.into(), route()));
    }

    #[test]
    fn construction_failure_unwinds_and_allows_exact_retry_without_clearing_route() {
        let _serial = TEST_GATE.lock().unwrap();
        let fence = NativeFenceFixture::new();
        reset_with_staged_route();
        let result: Result<(), String> = initialize_with_capability(
            &fence.capability,
            || Err::<(), _>("construction failed".into()),
            |_, _| panic!("failed construction must not publish"),
        );
        assert_eq!(result, Err("construction failed".into()));
        assert!(!process().lock().unwrap().initializing);
        assert!(process().lock().unwrap().host.is_none());
        assert!(process().lock().unwrap().last_closed_sha256.is_none());
        assert_eq!(prepared_route().lock().unwrap().as_ref().unwrap().0, SHA);
        assert!(fence.capability.require_live().is_ok());
        let result = initialize_with_capability(
            &fence.capability,
            || Ok(7_u8),
            |state, value| {
                assert!(state.initializing);
                Ok(value)
            },
        );
        assert_eq!(result, Ok(7));
        assert!(!process().lock().unwrap().initializing);
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&fence.close(SHA).unwrap()).unwrap()
                ["disposition"],
            "staged_route_closed"
        );
    }

    #[test]
    fn failed_postcheck_discards_candidate_and_retains_cleanup_without_publishing() {
        let _serial = TEST_GATE.lock().unwrap();
        let fence = NativeFenceFixture::new();
        reset_with_staged_route();
        let result: Result<(), String> = initialize_with_capability(
            &fence.capability,
            || {
                *fence.source.0.lock().unwrap() = "damaged".into();
                Ok(())
            },
            |_, _| panic!("invalid capability must not install constructed host"),
        );
        assert!(result.is_err());
        assert!(!process().lock().unwrap().initializing);
        assert!(process().lock().unwrap().host.is_none());
        assert!(process().lock().unwrap().last_closed_sha256.is_none());
        assert_eq!(prepared_route().lock().unwrap().as_ref().unwrap().0, SHA);
        assert!(fence.close(SHA).is_err());
        assert!(prepared_route().lock().unwrap().is_some());
        let retry: Result<(), String> = initialize_with_capability(
            &fence.capability,
            || panic!("poisoned capability must reject before reconstruction"),
            |_, _: ()| Ok(()),
        );
        assert!(retry.is_err());
        assert!(!process().lock().unwrap().initializing);
    }

    #[test]
    fn invalid_capability_rejects_before_construction_without_claiming_teardown() {
        let _serial = TEST_GATE.lock().unwrap();
        let fence = NativeFenceFixture::new();
        reset_with_staged_route();
        *fence.source.0.lock().unwrap() = "damaged".into();
        let result: Result<(), String> = initialize_with_capability(
            &fence.capability,
            || panic!("invalid capability must reject before construction"),
            |_, _: ()| Ok(()),
        );
        assert!(result.is_err());
        assert!(!process().lock().unwrap().initializing);
        assert!(process().lock().unwrap().last_closed_sha256.is_none());
        assert!(prepared_route().lock().unwrap().is_some());
        assert!(fence.close(SHA).is_err());
    }

    #[test]
    fn construction_unwind_releases_only_initialization_gate() {
        let _serial = TEST_GATE.lock().unwrap();
        let fence = NativeFenceFixture::new();
        reset_with_staged_route();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _: Result<(), String> = initialize_with_capability(
                &fence.capability,
                || panic!("construction unwind"),
                |_, _: ()| Ok(()),
            );
        }));
        assert!(result.is_err());
        assert!(!process().lock().unwrap().initializing);
        assert!(process().lock().unwrap().host.is_none());
        assert!(process().lock().unwrap().last_closed_sha256.is_none());
        assert!(prepared_route().lock().unwrap().is_some());
        assert!(fence.capability.require_live().is_ok());
        assert!(fence.close(SHA).is_ok());
    }

    #[test]
    fn failed_initialize_staged_route_requires_exact_sha_then_closes_idempotently() {
        let _serial = TEST_GATE.lock().unwrap();
        let fence = NativeFenceFixture::new();
        reset_with_staged_route();
        assert!(fence.close(OTHER_SHA).is_err());
        assert_eq!(prepared_route().lock().unwrap().as_ref().unwrap().0, SHA);
        let first: serde_json::Value = serde_json::from_str(&fence.close(SHA).unwrap()).unwrap();
        assert_eq!(first["disposition"], "staged_route_closed");
        assert_eq!(first["config_sha256"], SHA);
        assert!(prepared_route().lock().unwrap().is_none());
        let retry: serde_json::Value = serde_json::from_str(&fence.close(SHA).unwrap()).unwrap();
        assert_eq!(retry["disposition"], "already_closed");
        assert!(fence.close(OTHER_SHA).is_err());
    }

    #[test]
    fn busy_lease_refuses_close_and_exact_retry_finishes() {
        let _serial = TEST_GATE.lock().unwrap();
        let fence = NativeFenceFixture::new();
        reset_with_staged_route();
        process().lock().unwrap().host_leases = 1;
        assert_eq!(fence.close(SHA), Err("runtime host busy".into()));
        assert!(process().lock().unwrap().closing);
        assert!(prepared_route().lock().unwrap().is_some());
        process().lock().unwrap().host_leases = 0;
        assert!(fence.close(SHA).is_ok());
        assert!(!process().lock().unwrap().closing);
    }

    #[test]
    fn checked_out_and_locked_slots_are_never_treated_as_idle() {
        let slot = Arc::new(Mutex::new(Some(1_u8)));
        assert!(slot_present(&slot).is_ok());
        let checkout = Checkout::take(slot.clone()).unwrap();
        assert_eq!(slot_present(&slot), Err("runtime slot busy".into()));
        drop(checkout);
        let held = slot.lock().unwrap();
        assert_eq!(slot_present(&slot), Err("runtime slot busy".into()));
        drop(held);
        assert!(slot_present(&slot).is_ok());
    }

    #[test]
    fn missing_route_and_lease_integrity_failure_never_close() {
        let _serial = TEST_GATE.lock().unwrap();
        let fence = NativeFenceFixture::new();
        *process().lock().unwrap() = ProcessState::default();
        *prepared_route().lock().unwrap() = None;
        assert!(fence.close(SHA).is_err());
        reset_with_staged_route();
        process().lock().unwrap().lease_integrity_failed = true;
        assert!(fence.close(SHA).is_err());
        assert!(prepared_route().lock().unwrap().is_some());
    }

    #[test]
    fn enrollment_idle_requires_no_staged_route_or_process_lease() {
        let _serial = TEST_GATE.lock().unwrap();
        *process().lock().unwrap() = ProcessState::default();
        *prepared_route().lock().unwrap() = None;
        assert!(process_idle_for_enrollment());
        reset_with_staged_route();
        assert!(!process_idle_for_enrollment());
        *prepared_route().lock().unwrap() = None;
        process().lock().unwrap().host_leases = 1;
        assert!(!process_idle_for_enrollment());
        process().lock().unwrap().host_leases = 0;
        assert!(process_idle_for_enrollment());
    }
}

/// Typed caller is the process-held native Peer cleanup operation; IDs are never shell inputs.
fn install_retained_cleanup_requester(
    host: &Host,
    requester_id: &str,
    requester_lease_id: &str,
) -> Result<(), String> {
    host.capability.require_live()?;
    let grant = host.current_grant_id()?;
    // Derive actual local cleanup authority before installing this source-bound requester.
    host.authority.retained_local_cleanup_projection(
        &serde_json::from_value(json!(grant)).map_err(safe_decode)?,
        &serde_json::from_value(json!(requester_id)).map_err(safe_decode)?,
        &serde_json::from_value(json!(requester_lease_id)).map_err(safe_decode)?,
        &serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?,
        host.clock.now_ms()?,
    )?;
    *host
        .cleanup
        .requester
        .lock()
        .map_err(|_| "cleanup requester poisoned")? = Some(retained_cleanup_host::Requester {
        id: requester_id.to_owned(),
        lease: requester_lease_id.to_owned(),
    });
    Ok(())
}
