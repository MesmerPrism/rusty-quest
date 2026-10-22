//! Source-bound SDK preparation, before the final expiring media admission.
//! RQPS1 carries signed format metadata only. Ordinary media tickets remain the
//! sole authority that can open the already-prepared delivery gates.
use super::preparation_diagnostic::{Actor, Attempt, JavaGuard, PhysicalPredicates, Reason, Stage};
use super::preparation_sdk_observation::{
    Direction as SdkDirection, FrameKind as SdkKind, Reason as SdkReason, Stage as SdkStage,
    Trace as SdkTrace,
};
use super::*;
use rusty_quest_media_stream_android::{
    prepared_start_canonical, prepared_start_digest, AndroidPreparedDecoderReadback,
    AndroidPreparedDecoderRequest, AndroidPreparedResourceExecutor, AndroidPreparedSenderReadback,
    AndroidPreparedStartOwner, AndroidPreparedStartRequest,
};
use std::{fs::File, io::Read, time::Duration};
pub(super) const MAGIC: &[u8] = b"RQPS1";

pub(super) struct Arena {
    pub(super) request: AndroidPreparedStartRequest,
    owner: Mutex<AndroidPreparedStartOwner>,
    view: Value,
    stop: Arc<AtomicBool>,
    remote_decoder: Mutex<Option<AndroidPreparedDecoderReadback>>,
    completed: AtomicBool,
    local_start_consumed: AtomicBool,
    failure: Mutex<Option<String>>,
    sdk: SdkTrace,
    terminal: Mutex<Option<Value>>,
    aborting: AtomicBool,
    archive_body: Mutex<Option<String>>,
}
/// Actual reservation identity, never a caller-provided preparation grant.
pub(crate) struct PreparedRendererIdentity {
    pub(crate) preparation_id: String,
    pub(crate) app_process_generation: u64,
    pub(crate) app_record_sha256: String,
    pub(crate) native_executor_generation: u64,
    pub(crate) config_sha256: String,
    pub(crate) request_sha256: String,
    pub(crate) resource_target_sha256: String,
    pub(crate) desired_stop: Arc<AtomicBool>,
    pub(crate) resource_target: Value,
}
pub(crate) fn renderer_identity(id: &str) -> Result<PreparedRendererIdentity, String> {
    let p = process()
        .lock()
        .map_err(|_| "prepared renderer current slot poisoned")?;
    let a = p
        .prepared_peer_start
        .as_ref()
        .filter(|a| a.request.preparation_id == id && !a.stop.load(Ordering::SeqCst))
        .ok_or("actual prepared renderer reservation absent")?;
    let h = p.host.as_ref().ok_or("prepared renderer Host absent")?;
    h.capability.require_live()?;
    if p.closing
        || p.initializing
        || a.request.config_sha256 != h.config_sha256
        || a.request.native_executor_generation != h.capability.generation
    {
        return Err("prepared renderer current actor changed".into());
    }
    Ok(PreparedRendererIdentity {
        preparation_id: id.into(),
        app_process_generation: a.request.app_process_generation,
        app_record_sha256: a.request.app_record_sha256.clone(),
        native_executor_generation: a.request.native_executor_generation,
        config_sha256: a.request.config_sha256.clone(),
        request_sha256: a.request.digest()?,
        resource_target_sha256: a.request.resource_target_sha256.clone(),
        desired_stop: a.stop.clone(),
        resource_target: a.request.resource_target.clone(),
    })
}
fn current(host: &Host) -> Result<Arc<Arena>, String> {
    host.capability.require_live()?;
    let p = process()
        .lock()
        .map_err(|_| "prepared current process poisoned")?;
    let a = p
        .prepared_peer_start
        .as_ref()
        .ok_or("actual prepared reservation absent")?;
    if p.closing
        || p.initializing
        || a.stop.load(Ordering::SeqCst)
        || a.request.config_sha256 != host.config_sha256
        || p.lease_integrity_failed
        || a.request.native_executor_generation != host.capability.generation
        || a.request.app_process_generation != host.capability.binding.generation
        || a.request.app_record_sha256 != host.capability.binding.record_sha256
    {
        return Err("prepared actual owner stopped/superseded".into());
    }
    Ok(a.clone())
}
fn source(host: &Host) -> Result<Value, String> {
    let policy = host
        .authority
        .selected_fresh_pair_setup_policy()?
        .ok_or("prepared requires actual source-bound ongoing owner")?;
    let selection = host
        .authority
        .selected_fresh_setup_enrollment_selection()?
        .ok_or("prepared immutable selected keys absent")?;
    let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
        serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
    for key in &selection.keys {
        if snapshot.enrollment.credentials.iter().any(|c| {
            c.peer_id == key.peer_id
                && (c.key_id != key.key_id
                    || c.status != rusty_manifold_peer::ManifoldPeerCredentialStatus::Active
                    || c.replaced_by_key_id.is_some())
        }) {
            return Err("prepared selected enrolled identity revoked/replaced".into());
        }
    }
    Ok(
        json!({"source_peer_snapshot_sha256":rusty_manifold_peer_runtime_host::fresh_history_source_sha256(&snapshot).map_err(|_|"prepared actual source digest")?,
        "selected_policy_sha256":rusty_manifold_peer::fresh_pair_setup_digest("rusty.manifold.peer.source_bound_root_policy.v1",&policy).map_err(|_|"prepared selected policy digest")?,
        "floor_sha256":prepared_start_digest("rusty.quest.embedded_duplex.prepared_start_floor.v1",&snapshot.reciprocal_ed25519.fresh_setup_fences)?}),
    )
}
fn check_source(host: &Host, a: &Arena) -> Result<(), String> {
    current(host)?;
    if source(host)? != a.view["source"] {
        return Err("prepared signed exact source changed before metadata completion".into());
    }
    Ok(())
}
fn id() -> Result<String, String> {
    let mut bytes = [0u8; 16];
    File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut bytes))
        .map_err(|_| "prepared native entropy unavailable")?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}
fn hex<const N: usize>(s: &str) -> Result<[u8; N], String> {
    if s.len() != N * 2
        || !s
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err("prepared canonical hex".into());
    }
    let mut a = [0; N];
    for (i, b) in a.iter_mut().enumerate() {
        *b = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).map_err(|_| "prepared hex")?;
    }
    Ok(a)
}
type Body = rusty_quest_media_stream_android::AndroidPreparedStartFrameBody;
fn signing(b: &Body) -> Result<Vec<u8>, String> {
    rusty_quest_media_stream_android::prepared_start_frame_signing_bytes(b)
}
fn shape(b: &Body) -> Result<(), String> {
    rusty_quest_media_stream_android::validate_prepared_start_frame(b)
}
fn encode(
    host: &Host,
    a: &Arena,
    kind: &str,
    request: &str,
    receiver: Option<String>,
    payload: Value,
) -> Result<Vec<u8>, String> {
    let direction = if matches!(kind, "viewed" | "decoder_prepared") {
        SdkDirection::Incoming
    } else {
        SdkDirection::Outbound
    };
    let frame_kind = SdkKind::parse(kind);
    a.sdk.call(
        direction,
        SdkStage::SourceCheck,
        frame_kind,
        SdkReason::CallFailed,
        || check_source(host, a),
    )?;
    let body = Body {
        schema: "rusty.quest.embedded_duplex.prepared_start_frame.v1".into(),
        kind: kind.into(),
        request_id: request.into(),
        sender_preparation_id: a.request.preparation_id.clone(),
        receiver_preparation_id: receiver,
        sender_peer_id: host.local_peer_id.clone(),
        receiver_peer_id: host.remote_peer_id.clone(),
        route_configuration_sha256: host.route_configuration_sha256.clone(),
        issued_at_ms: a.sdk.call(
            direction,
            SdkStage::FrameClock,
            frame_kind,
            SdkReason::CallFailed,
            || {
                host.clock.now_ms().map(|now| {
                    now.saturating_sub(
                        crate::embedded_duplex::pair_lifetime_policy::ISSUE_BACKDATE_MS,
                    )
                })
            },
        )?,
        payload,
    };
    a.sdk.call(
        direction,
        SdkStage::FrameShape,
        frame_kind,
        SdkReason::PredicateRejected,
        || shape(&body),
    )?;
    let signature = a.sdk.call(
        direction,
        SdkStage::FrameSign,
        frame_kind,
        SdkReason::CallFailed,
        || host.callbacks.sign_prepared_start_frame(&signing(&body)?),
    )?;
    let direction = if matches!(kind, "viewed" | "decoder_prepared") {
        SdkDirection::Incoming
    } else {
        SdkDirection::Outbound
    };
    let frame_kind = SdkKind::parse(kind);
    a.sdk.call(
        direction,
        SdkStage::SourceCheck,
        frame_kind,
        SdkReason::CallFailed,
        || check_source(host, a),
    )?;
    a.sdk.call(
        direction,
        SdkStage::FrameEncode,
        frame_kind,
        SdkReason::CallFailed,
        || rusty_quest_media_stream_android::encode_prepared_start_frame(body, signature),
    )
}
fn decode(host: &Host, raw: &[u8]) -> Result<Body, String> {
    rusty_quest_media_stream_android::decode_prepared_start_frame(
        raw,
        &host.remote_public_key,
        &host.remote_peer_id,
        &host.local_peer_id,
        &host.route_configuration_sha256,
        host.clock.now_ms()?,
    )
}

fn view_hash(v: &Value) -> Result<String, String> {
    prepared_start_digest("rusty.quest.embedded_duplex.prepared_start_view.v1", v)
}
fn remote_view(
    host: &Host,
    a: &Arena,
    value: &Value,
    id: &str,
) -> Result<AndroidPreparedStartRequest, String> {
    let expected = ["$schema", "request", "source"];
    let source = &value["source"];
    if !value
        .as_object()
        .is_some_and(|o| o.len() == 3 && expected.iter().all(|k| o.contains_key(*k)))
        || value["$schema"] != "rusty.quest.embedded_duplex.prepared_start_view.v1"
        || !source.as_object().is_some_and(|o| {
            o.len() == 3
                && [
                    "source_peer_snapshot_sha256",
                    "selected_policy_sha256",
                    "floor_sha256",
                ]
                .iter()
                .all(|k| o.contains_key(*k))
        })
        || [
            "source_peer_snapshot_sha256",
            "selected_policy_sha256",
            "floor_sha256",
        ]
        .iter()
        .any(|k| {
            source[*k].as_str().is_none_or(|s| {
                s.strip_prefix("sha256:")
                    .is_none_or(|v| hex::<32>(v).is_err())
            })
        })
        || source["selected_policy_sha256"] != a.view["source"]["selected_policy_sha256"]
    {
        return Err("prepared remote closed source view differs".into());
    }
    let request: AndroidPreparedStartRequest =
        serde_json::from_value(value["request"].clone()).map_err(safe_decode)?;
    request.validate()?;
    if request.preparation_id != id
        || request.configuration_binding["local_peer_id"] != host.remote_peer_id
        || request.configuration_binding["fresh_setup_build_policy"]
            != a.request.configuration_binding["fresh_setup_build_policy"]
        || request.resource_target["outgoing_runtime_spec_id"]
            != a.request.resource_target["incoming_runtime_spec_id"]
        || request.resource_target["incoming_runtime_spec_id"]
            != a.request.resource_target["outgoing_runtime_spec_id"]
    {
        return Err(
            "prepared signed remote constructor/policy/reciprocal runtime target differs".into(),
        );
    }
    Ok(request)
}
fn exchange(
    host: &Host,
    a: &Arena,
    kind: &str,
    response: &str,
    remote: Option<String>,
    payload: Value,
) -> Result<Body, String> {
    let frame = encode(
        host,
        a,
        kind,
        &a.request.preparation_id,
        remote.clone(),
        payload,
    )?;
    let kind = SdkKind::parse(kind);
    let reply = a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::FrameExchange,
        kind,
        SdkReason::CallFailed,
        || host.callbacks.clone().exchange(&frame, 128 * 1024),
    )?;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::SourceCheck,
        kind,
        SdkReason::CallFailed,
        || check_source(host, a),
    )?;
    let body = a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::FrameDecode,
        kind,
        SdkReason::PredicateRejected,
        || decode(host, &reply),
    )?;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::ResponseIdentity,
        kind,
        SdkReason::PredicateRejected,
        || {
            if body.kind != response
                || body.request_id != a.request.preparation_id
                || body.receiver_preparation_id.as_deref()
                    != Some(a.request.preparation_id.as_str())
                || remote
                    .as_ref()
                    .is_some_and(|r| r != &body.sender_preparation_id)
            {
                return Err("prepared exact response identity differs".into());
            }
            Ok(())
        },
    )?;
    Ok(body)
}
fn diagnostic_actor(host: &Host) -> Actor {
    Actor {
        app_generation: host.capability.binding.generation,
        app_record: host.capability.binding.record_sha256.clone(),
        native_generation: host.capability.generation,
        config: host.config_sha256.clone(),
    }
}
fn sdk_lane(value: &super::preparation_sdk_observation::Observation) -> Value {
    json!({"state":value.state.name(),"stage":value.stage.map(SdkStage::name),"frame_kind":value.frame_kind.map(SdkKind::name),"reason":value.reason.map(SdkReason::name)})
}
fn sdk_snapshot(value: &super::preparation_sdk_observation::Snapshot) -> Value {
    json!({"$schema":"rusty.quest.embedded_duplex.preparation_sdk_observation.v1","outbound":sdk_lane(&value.outbound),"incoming":sdk_lane(&value.incoming),"first_failure":value.first_failure.as_ref().map(|(direction,observed)|json!({"direction":direction.name(),"observation":sdk_lane(observed)}))})
}
pub(super) fn diagnostic(host: &Host, input: &str) -> Result<String, String> {
    host.capability.require_live()?;
    let fields: Value = serde_json::from_str(input).map_err(safe_decode)?;
    let map = fields
        .as_object()
        .ok_or("closed preparation diagnostic selector required")?;
    if map.len() > 1 || map.keys().any(|key| key != "original_id") {
        return Err("closed preparation diagnostic selector required".into());
    }
    let expected = if let Some(value) = map.get("original_id") {
        let id = value
            .as_str()
            .ok_or("typed preparation Original selector required")?;
        if id.len() != 32
            || !id
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err("canonical preparation Original selector required".into());
        }
        Some(id)
    } else {
        None
    };
    let actor = diagnostic_actor(host);
    let view = host
        .preparation_admission
        .lock()
        .map_err(|_| "preparation diagnostic unavailable")?
        .read(&actor, expected)
        .map_err(|_| "preparation diagnostic actor/Original unavailable")?;
    // Clone the exact retained observation, release PROCESS/Arena/journal locks,
    // then read the existing platform-latest transport snapshot. It is not an
    // Original-bound transport receipt and grants no completion/authentication.
    let retained = if let Some(observed) = view.as_ref().filter(|v| v.arena_installed) {
        let arena = held(host)?;
        if observed.original.as_deref() == Some(arena.request.original_id.as_str()) {
            Some(arena)
        } else {
            None
        }
    } else {
        None
    };
    let sdk = retained
        .as_ref()
        .map(|arena| {
            arena
                .sdk
                .read()
                .map_err(|_| "prepared SDK observation unavailable")
        })
        .transpose()?;
    let transport = if sdk.is_some() {
        host.callbacks.owner_transport_attempt_read()
    } else {
        Value::Null
    };
    host.capability.require_live()?;
    if let Some(arena) = retained {
        if !Arc::ptr_eq(&held(host)?, &arena) {
            return Err("prepared SDK diagnostic owner changed during read".into());
        }
    }
    let after = host
        .preparation_admission
        .lock()
        .map_err(|_| "preparation diagnostic unavailable")?
        .read(&actor, expected)
        .map_err(|_| "preparation diagnostic actor/Original unavailable")?;
    if after != view {
        return Err("preparation diagnostic changed during read".into());
    }
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v3",
        "operation":"prepare_peer_start","app_process_generation":actor.app_generation,
        "app_record_sha256":actor.app_record,"native_executor_generation":actor.native_generation,
        "config_sha256":actor.config,"state":view.as_ref().map_or("unobserved",|v|v.state.name()),
        "attempt_sequence":view.as_ref().map(|v|v.sequence),
        "original_id":view.as_ref().and_then(|v|v.original.as_deref()),
        "original_bound":view.as_ref().is_some_and(|v|v.original.is_some()),
        "stage":view.as_ref().and_then(|v|v.stage.map(Stage::name)),
        "reason":view.as_ref().and_then(|v|v.reason.map(Reason::name)),
        "arena_installed":view.as_ref().map(|v|v.arena_installed),
        "java_failed_guard":view.as_ref().and_then(|v|v.java_guard.map(JavaGuard::name)),
        "native_failed_guard":view.as_ref().and_then(|v|v.native_guard.map(|g|g.name())),
        "native_failed_reason":view.as_ref().and_then(|v|v.native_reason.map(|r|r.name())),
        "physical_predicates":view.as_ref().and_then(|v|v.physical.as_ref()).map(|p|json!({
            "shape_matches":p.shape,"schema_matches":p.schema,"app_generation_matches":p.app_generation,
            "app_record_matches":p.app_record,"native_generation_matches":p.native_generation,"config_matches":p.config,
            "classification_matches":p.classification,"original_matches":p.original,"physical_eligible":p.eligible,
            "unresolved_originals_zero":p.unresolved_zero,"peer_resources_terminal":p.peer,"renderer_terminal":p.renderer,"control_terminal":p.control})),
        "sdk_observation":sdk.as_ref().map(sdk_snapshot),"transport_attempt":transport,"transport_attempt_original_bound":false,
        "authority_granted":false}).to_string())
}
pub(super) fn run(host: &Host) -> Result<String, String> {
    let attempt = Attempt::begin(host.preparation_admission.clone(), diagnostic_actor(host));
    let result = run_observed(host, &attempt);
    attempt.finish(result.is_ok());
    result
}
fn run_observed(host: &Host, attempt: &Attempt) -> Result<String, String> {
    attempt.call(Stage::Capability, Reason::CallFailed, || {
        host.capability.require_live()
    })?;
    attempt.call(Stage::PriorArchive, Reason::CallFailed, || {
        archive_prior(host, None)
    })?;
    let original = attempt.call(Stage::OriginalRead, Reason::CallFailed, || {
        host.callbacks.current_operator_original_id()
    })?;
    attempt.bind_original(&original);
    let eligibility = attempt.call(Stage::JavaEligibility, Reason::CallFailed, || {
        host.callbacks.prepared_start_eligibility(attempt)
    })?;
    attempt.call(
        Stage::OriginalMatch,
        Reason::PredicateRejected,
        || -> Result<(), String> {
            if eligibility["original_binding"] != json!({"kind":"operator","original_id":original})
            {
                return Err("prepared actual wrapper Original differs".into());
            }
            Ok(())
        },
    )?;
    let native = attempt.call(Stage::NativeEligibility, Reason::CallFailed, || {
        peer_lifecycle::fresh_setup_eligibility_observed(host, &|guard, reason| {
            attempt.native_failure(guard, reason)
        })
    })?;
    const E: [&str; 8] = [
        "$schema",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
        "original_binding",
        "arm_binding",
        "physical_eligibility",
    ];
    let physical = &eligibility["physical_eligibility"];
    attempt.physical(PhysicalPredicates {
        shape: eligibility
            .as_object()
            .is_some_and(|o| o.len() == 8 && E.iter().all(|k| o.contains_key(*k))),
        schema: eligibility["$schema"]
            == "rusty.quest.embedded_duplex.peer_start_preparation_eligibility.v1",
        app_generation: eligibility["app_process_generation"] == host.capability.binding.generation,
        app_record: eligibility["app_record_sha256"] == host.capability.binding.record_sha256,
        native_generation: eligibility["native_executor_generation"] == host.capability.generation,
        config: eligibility["config_sha256"] == host.config_sha256,
        classification: physical["classification"] == native["local"]["state"],
        original: physical["original_binding"] == eligibility["original_binding"],
        eligible: physical["eligible"].as_bool(),
        unresolved_zero: physical["unresolved_original_count"]
            .as_u64()
            .map(|v| v == 0),
        peer: physical["peer_resources_terminal"].as_bool(),
        renderer: physical["renderer_terminal"].as_bool(),
        control: physical["control_terminal"].as_bool(),
    });
    attempt.call(
        Stage::PhysicalEligibility,
        Reason::PredicateRejected,
        || -> Result<(), String> {
            if !eligibility
                .as_object()
                .is_some_and(|o| o.len() == 8 && E.iter().all(|k| o.contains_key(*k)))
                || eligibility["$schema"]
                    != "rusty.quest.embedded_duplex.peer_start_preparation_eligibility.v1"
                || eligibility["app_process_generation"] != host.capability.binding.generation
                || eligibility["app_record_sha256"] != host.capability.binding.record_sha256
                || eligibility["native_executor_generation"] != host.capability.generation
                || eligibility["config_sha256"] != host.config_sha256
                || physical["eligible"] != true
                || physical["classification"] != native["local"]["state"]
                || physical["unresolved_original_count"] != 0
                || physical["original_binding"] != eligibility["original_binding"]
                || physical["peer_resources_terminal"] != true
                || physical["renderer_terminal"] != true
                || physical["control_terminal"] != true
            {
                return Err(
                    "actual preparation physical/Original/Native lifetime proof differs".into(),
                );
            }
            Ok(())
        },
    )?;
    let target = attempt.call(Stage::ResourceTarget, Reason::CallFailed, || {
        host.callbacks.peer_start_resource_target()
    })?;
    let request = AndroidPreparedStartRequest {
        schema_id: "rusty.quest.embedded_duplex.prepared_start_request.v1".into(),
        preparation_id: attempt.call(Stage::Entropy, Reason::CallFailed, id)?,
        original_id: original,
        app_process_generation: host.capability.binding.generation,
        app_record_sha256: host.capability.binding.record_sha256.clone(),
        native_executor_generation: host.capability.generation,
        config_sha256: host.config_sha256.clone(),
        arm_binding: eligibility["arm_binding"].clone(),
        configuration_binding: attempt.call(
            Stage::ConstructorBinding,
            Reason::CallFailed,
            || {
                host.runtime_configuration_binding
                    .clone()
                    .ok_or("prepared actual successful constructor absent")
            },
        )?,
        resource_target_sha256: attempt.call(
            Stage::ResourceTargetDigest,
            Reason::CallFailed,
            || {
                prepared_start_digest(
                    "rusty.quest.embedded_duplex.peer_start_resource_target.v1",
                    &target,
                )
            },
        )?,
        resource_target: target,
    };
    let mut callback = host.callbacks.clone();
    let owner = attempt.call(Stage::RequestReservation, Reason::CallFailed, || {
        AndroidPreparedStartOwner::reserve(&callback, request.clone())
    })?;
    let view = json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_view.v1","request":request,"source":attempt.call(Stage::SourceSnapshot,Reason::CallFailed,||source(host))?});
    let a = Arc::new(Arena {
        request,
        owner: Mutex::new(owner),
        view,
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
    let reserve_estimate = Instant::now() + Duration::from_secs(15);
    let mut reserve_warned = false;
    attempt.call(
        Stage::ArenaInstall,
        Reason::CallFailed,
        || -> Result<(), String> {
            loop {
                host.capability.require_live()?;
                let mut p = process()
                    .lock()
                    .map_err(|_| "prepared reservation process poisoned")?;
                if p.prepared_peer_start.is_some()
                    || p.root_reestablishment.is_some()
                    || p.fresh_setup_request.is_some()
                    || p.initializing
                    || p.closing
                    || p.lease_integrity_failed
                {
                    return Err(
                        "prepared requires same Native owner before resource effects".into(),
                    );
                }
                if p.host_leases == 1 {
                    p.prepared_peer_start = Some(a.clone());
                    break;
                }
                drop(p);
                if Instant::now() >= reserve_estimate && !reserve_warned {
                    reserve_warned = true;
                    estimated_warning::publish(
                "prepared reserve waits for actual authentication/read borrowers; same original",
            );
                }
                std::thread::sleep(Duration::from_millis(25));
            }
            Ok(())
        },
    )?;
    attempt.arena_installed();
    let result = attempt.call(Stage::SdkPreparation, Reason::CallFailed, || {
        sdk_prepare(host, &a, &mut callback)
    });
    a.sdk.finish(SdkDirection::Outbound, result.is_ok());
    if let Err(e) = &result {
        *a.failure
            .lock()
            .map_err(|_| "prepared failure custody poisoned")? = Some(e.clone());
    }
    result
}
fn sdk_prepare(
    host: &Host,
    a: &Arc<Arena>,
    callback: &mut JavaOwnerCallbacks,
) -> Result<String, String> {
    // Registers actual construction ownership before any callback may enter
    // renderer SDK allocation; absence of a renderer slot is never proof.
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::RendererBegin,
        None,
        SdkReason::CallFailed,
        || crate::camera_hwb_probe::begin_prepared_peer_renderer(&a.request.preparation_id),
    )?;
    let sender = a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::SenderPrepare,
        None,
        SdkReason::CallFailed,
        || {
            a.owner
                .lock()
                .map_err(|_| "prepared sender owner poisoned")?
                .prepare_sender(callback)
        },
    )?;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::SourceCheck,
        None,
        SdkReason::CallFailed,
        || check_source(host, a),
    )?;
    let viewed = exchange(host, &a, "view", "viewed", None, json!({"view":a.view}))?;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::ResponseIdentity,
        Some(SdkKind::Viewed),
        SdkReason::PredicateRejected,
        || {
            if viewed.payload["initiator_view_sha256"] != view_hash(&a.view)? {
                return Err("prepared initiator view changed".into());
            }
            Ok(())
        },
    )?;
    let remote_value = &viewed.payload["view"];
    let remote_id = viewed.sender_preparation_id.clone();
    let remote_request = a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::RemoteView,
        Some(SdkKind::Viewed),
        SdkReason::PredicateRejected,
        || remote_view(host, a, remote_value, &remote_id),
    )?;
    let prepared = exchange(
        host,
        &a,
        "decoder_prepare",
        "decoder_prepared",
        Some(remote_id.clone()),
        json!({"initiator_view":a.view,"responder_view":remote_value,"sender_receipt":sender}),
    )?;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::DecoderReceipt,
        Some(SdkKind::DecoderPrepared),
        SdkReason::PredicateRejected,
        || {
            if prepared.payload["initiator_view_sha256"] != view_hash(&a.view)?
                || prepared.payload["responder_view_sha256"] != view_hash(remote_value)?
            {
                return Err("prepared signed metadata views differ".into());
            }
            let decoder: AndroidPreparedDecoderReadback =
                serde_json::from_value(prepared.payload["decoder_receipt"].clone())
                    .map_err(safe_decode)?;
            let remote_decoder = AndroidPreparedDecoderRequest {
                schema_id: "rusty.quest.android.media.prepared_decoder_request.v1".into(),
                preparation_id: remote_id,
                request_sha256: remote_request.digest()?,
                peer_preparation_id: a.request.preparation_id.clone(),
                peer_sender_receipt_sha256: prepared_start_digest(
                    "rusty.quest.android.media.prepared_sender_readback.v1",
                    &sender,
                )?,
                peer_format: sender.outgoing_format,
            };
            decoder.validate(&remote_request, &remote_decoder)?;
            *a.remote_decoder
                .lock()
                .map_err(|_| "prepared remote receipt poisoned")? = Some(decoder);
            Ok(())
        },
    )?;
    let estimate = Instant::now() + Duration::from_secs(15);
    let mut warned = false;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::DecoderWait,
        None,
        SdkReason::CallFailed,
        || {
            loop {
                a.sdk.call(
                    SdkDirection::Outbound,
                    SdkStage::SourceCheck,
                    None,
                    SdkReason::CallFailed,
                    || check_source(host, a),
                )?;
                let ready = a
                    .owner
                    .try_lock()
                    .ok()
                    .is_some_and(|o| o.decoder().is_some());
                if ready {
                    break;
                }
                if Instant::now() >= estimate && !warned {
                    warned = true;
                    estimated_warning::publish(
                        "prepared decoder estimate elapsed; same original; no cancellation",
                    );
                }
                std::thread::sleep(Duration::from_millis(25));
            }
            Ok(())
        },
    )?;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::OwnerReady,
        None,
        SdkReason::CallFailed,
        || {
            a.owner
                .lock()
                .map_err(|_| "prepared final owner poisoned")?
                .require_ready(callback)
        },
    )?;
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::RendererDormant,
        None,
        SdkReason::CallFailed,
        || crate::camera_hwb_probe::prepared_peer_renderer_dormant_for(&a.request.preparation_id),
    )?;
    a.completed.store(true, Ordering::SeqCst);
    a.sdk.call(
        SdkDirection::Outbound,
        SdkStage::ReadyStatus,
        None,
        SdkReason::CallFailed,
        || status(host),
    )
}
pub(super) fn incoming_host() -> Result<HostLease, String> {
    let mut p = process()
        .lock()
        .map_err(|_| "prepared incoming process poisoned")?;
    if p.closing || p.initializing || p.root_reestablishment.is_some() {
        return Err("prepared incoming source closing".into());
    }
    let h = p.host.clone().ok_or("prepared incoming Host absent")?;
    p.host_leases = p
        .host_leases
        .checked_add(1)
        .ok_or("prepared incoming lease overflow")?;
    drop(p);
    let h = HostLease(h);
    h.capability.require_live()?;
    Ok(h)
}
pub(super) fn operation_host() -> Result<HostLease, String> {
    incoming_host()
}
fn cleanup_host() -> Result<HostLease, String> {
    let mut p = process()
        .lock()
        .map_err(|_| "prepared fixed cleanup gate poisoned")?;
    let pending_fresh = p.fresh_setup_request.is_some();
    if p.initializing
        || p.root_reestablishment.is_some()
        || p.host_leases != 0
        || p.lease_integrity_failed
        || p.closing
            && !((p.root_terminal_custody.is_some() && p.retired_fresh_owner_fenced)
                || pending_fresh)
    {
        return Err("prepared fixed cleanup actual originals not joined".into());
    }
    let h = p.host.clone().ok_or("prepared fixed cleanup Host absent")?;
    p.host_leases = 1;
    drop(p);
    let h = HostLease(h);
    h.capability.require_live()?;
    Ok(h)
}
/// Authenticate under a short actual lease, then release it while awaiting the
/// counterpart's local reservation. Holding it across that wait would reject
/// the sole-local-owner reserve and create a bilateral startup deadlock.
pub(super) fn handle_frame(raw: &[u8]) -> Result<Vec<u8>, String> {
    let (cap, callbacks, observed_host, authenticated) = {
        let h = incoming_host()?;
        let authenticated = decode(&h, raw)?;
        (
            h.capability.clone(),
            h.callbacks.clone(),
            h.0.clone(),
            authenticated,
        )
    };
    let stop_sequence = callbacks.incoming_prepared_start_stop_sequence()?;
    let estimate = Instant::now() + Duration::from_secs(15);
    let mut warned = false;
    loop {
        cap.require_live()?;
        let candidate = {
            let p = process()
                .lock()
                .map_err(|_| "prepared reservation wait gate poisoned")?;
            if p.closing || p.initializing || p.lease_integrity_failed {
                return Err("prepared source closed while awaiting actual reservation".into());
            }
            if p.host
                .as_ref()
                .is_none_or(|h| h.capability.generation != cap.generation)
            {
                return Err("prepared actual Host was removed/superseded".into());
            }
            p.prepared_peer_start.clone()
        };
        let retired = candidate
            .as_ref()
            .is_some_and(|a| a.local_start_consumed.load(Ordering::SeqCst))
            && peer_lifecycle::prepared_terminal_lifetime(&observed_host).is_ok();
        if callbacks.incoming_prepared_start_stop_requested_for(
            &authenticated.sender_preparation_id,
            stop_sequence,
            retired,
        )? {
            return Err("prepared incoming actual owner explicitly stopped".into());
        }
        let ready = if let Some(a) = candidate {
            if a.local_start_consumed.load(Ordering::SeqCst)
                || a.terminal
                    .lock()
                    .map_err(|_| "prepared incoming terminal gate poisoned")?
                    .is_some()
            {
                false
            } else {
                if a.stop.load(Ordering::SeqCst) {
                    return Err("prepared counterpart explicitly stopped".into());
                }
                if a.failure
                    .lock()
                    .map_err(|_| "prepared incoming failure gate poisoned")?
                    .is_some()
                {
                    return Err("actual unconsumed SDK preparation UNKNOWN; no replay".into());
                }
                true
            }
        } else {
            false
        };
        if ready {
            let h = incoming_host()?;
            return handle(&h, raw);
        }
        if Instant::now() >= estimate && !warned {
            warned = true;
            estimated_warning::publish("prepared counterpart reservation estimate elapsed; same authenticated frame; no cancellation");
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}
pub(super) fn handle(host: &Host, raw: &[u8]) -> Result<Vec<u8>, String> {
    let b = decode(host, raw)?;
    let a = current(host)?;
    let result = (|| {
        a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::SourceCheck,
            SdkKind::parse(&b.kind),
            SdkReason::CallFailed,
            || check_source(host, &a),
        )?;
        a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::IncomingBind,
            SdkKind::parse(&b.kind),
            SdkReason::CallFailed,
            || {
                host.callbacks.bind_incoming_prepared_start(
                    &b.sender_preparation_id,
                    &a.request.preparation_id,
                )
            },
        )?;
        if b.kind == "view" {
            a.sdk.call(
                SdkDirection::Incoming,
                SdkStage::RemoteView,
                Some(SdkKind::View),
                SdkReason::PredicateRejected,
                || remote_view(host, &a, &b.payload["view"], &b.sender_preparation_id),
            )?;
            return a.sdk.call(SdkDirection::Incoming,SdkStage::FrameEncode,Some(SdkKind::Viewed),SdkReason::CallFailed,||encode(
            host,
            &a,
            "viewed",
            &b.request_id,
            Some(b.sender_preparation_id),
            json!({"initiator_view_sha256":view_hash(&b.payload["view"])?,"view":a.view}),
        ));
        }
        a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::ResponseIdentity,
            SdkKind::parse(&b.kind),
            SdkReason::PredicateRejected,
            || {
                if b.kind != "decoder_prepare"
                    || b.receiver_preparation_id.as_deref()
                        != Some(a.request.preparation_id.as_str())
                    || b.payload["responder_view"] != a.view
                {
                    return Err("prepared incoming exact current target differs".into());
                }
                Ok(())
            },
        )?;
        let remote_value = &b.payload["initiator_view"];
        let remote = a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::RemoteView,
            Some(SdkKind::DecoderPrepare),
            SdkReason::PredicateRejected,
            || remote_view(host, &a, remote_value, &b.sender_preparation_id),
        )?;
        let sender = a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::IncomingSender,
            Some(SdkKind::DecoderPrepare),
            SdkReason::PredicateRejected,
            || {
                let sender: AndroidPreparedSenderReadback =
                    serde_json::from_value(b.payload["sender_receipt"].clone())
                        .map_err(safe_decode)?;
                sender.validate(&remote)?;
                Ok(sender)
            },
        )?;
        a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::IncomingBind,
            Some(SdkKind::DecoderPrepare),
            SdkReason::CallFailed,
            || {
                host.callbacks
                    .bind_incoming_prepared_start(&remote.preparation_id, &a.request.preparation_id)
            },
        )?;
        let receipt = a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::IncomingDecoder,
            Some(SdkKind::DecoderPrepare),
            SdkReason::CallFailed,
            || {
                let decoder_request = AndroidPreparedDecoderRequest {
                    schema_id: "rusty.quest.android.media.prepared_decoder_request.v1".into(),
                    preparation_id: a.request.preparation_id.clone(),
                    request_sha256: a.request.digest()?,
                    peer_preparation_id: remote.preparation_id,
                    peer_sender_receipt_sha256: prepared_start_digest(
                        "rusty.quest.android.media.prepared_sender_readback.v1",
                        &sender,
                    )?,
                    peer_format: sender.outgoing_format,
                };
                let receipt = a
                    .owner
                    .lock()
                    .map_err(|_| "prepared decoder owner poisoned")?
                    .prepare_decoder(&mut host.callbacks.clone(), decoder_request)?;
                Ok(receipt)
            },
        )?;
        a.sdk.call(
            SdkDirection::Incoming,
            SdkStage::SourceCheck,
            SdkKind::parse(&b.kind),
            SdkReason::CallFailed,
            || check_source(host, &a),
        )?;
        a.sdk.call(SdkDirection::Incoming,SdkStage::FrameEncode,Some(SdkKind::DecoderPrepared),SdkReason::CallFailed,||encode(
        host,
        &a,
        "decoder_prepared",
        &b.request_id,
        Some(b.sender_preparation_id),
        json!({"initiator_view_sha256":view_hash(remote_value)?,"responder_view_sha256":view_hash(&a.view)?,"decoder_receipt":receipt}),
    ))
    })();
    a.sdk.finish(SdkDirection::Incoming, result.is_ok());
    result
}
pub(super) fn status(host: &Host) -> Result<String, String> {
    let a = held(host)?;
    let owner = a.owner.try_lock().ok().map(|o| o.projection());
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.prepared_peer_start_status.v1","preparation_id":a.request.preparation_id,"original_id":a.request.original_id,
        "app_process_generation":a.request.app_process_generation,"app_record_sha256":a.request.app_record_sha256,"native_executor_generation":a.request.native_executor_generation,"config_sha256":a.request.config_sha256,
        "request_sha256":a.request.digest()?,"resource_target_sha256":a.request.resource_target_sha256,"owner":owner,"remote_decoder":*a.remote_decoder.lock().map_err(|_|"prepared remote status poisoned")?,
        "state":if a.terminal.lock().map_err(|_|"prepared terminal status poisoned")?.is_some(){"terminal"}else if a.aborting.load(Ordering::SeqCst){"aborting"}else if a.stop.load(Ordering::SeqCst){"stop_requested"}else if a.completed.load(Ordering::SeqCst){"ready"}else if a.failure.lock().map_err(|_|"prepared failure status poisoned")?.is_some(){"unknown"}else{"preparing"},
        "local_start_consumed":a.local_start_consumed.load(Ordering::SeqCst),"media_authority_granted":false}).to_string())
}
pub(super) fn require_ready(host: &Host, consume: bool) -> Result<(), String> {
    if host.authority.selected_fresh_pair_setup_policy()?.is_none() {
        return Ok(());
    }
    let a = current(host)?;
    if !a.completed.load(Ordering::SeqCst) {
        return Err("actual bilateral SDK preparation incomplete".into());
    }
    a.owner
        .lock()
        .map_err(|_| "prepared ready owner poisoned")?
        .require_ready(&host.callbacks)?;
    if consume
        && a.local_start_consumed
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
    {
        return Err("actual prepared local Start already entered".into());
    }
    Ok(())
}
pub(super) fn request_stop(original: &str) -> Result<String, String> {
    let cap = native_fence_jni::active()?;
    cap.require_live()?;
    let p = process()
        .lock()
        .map_err(|_| "prepared Stop gate poisoned")?;
    let a = p
        .prepared_peer_start
        .as_ref()
        .filter(|a| {
            a.request.original_id == original
                && a.request.native_executor_generation == cap.generation
        })
        .ok_or("prepared exact actual Original absent")?;
    a.stop.store(true, Ordering::SeqCst);
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_stop_requested.v1","original_id":original,"preparation_id":a.request.preparation_id,"desired_stop_latched":true,"physical_cleanup_claimed":false}).to_string())
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_requestPreparedPeerStartStop(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    original: JString<'_>,
) -> jstring {
    let result = read_string(&mut env, &original, 32).and_then(|id| request_stop(&id));
    return_string(&mut env, result)
}

pub(super) fn held(host: &Host) -> Result<Arc<Arena>, String> {
    host.capability.require_live()?;
    let p = process()
        .lock()
        .map_err(|_| "prepared held gate poisoned")?;
    let a = p
        .prepared_peer_start
        .as_ref()
        .ok_or("actual prepared owner absent")?;
    if p.initializing
        || p.lease_integrity_failed
        || a.request.config_sha256 != host.config_sha256
        || a.request.native_executor_generation != host.capability.generation
        || a.request.app_process_generation != host.capability.binding.generation
        || a.request.app_record_sha256 != host.capability.binding.record_sha256
    {
        return Err("actual prepared held actor differs".into());
    }
    Ok(a.clone())
}
impl Arena {
    pub(super) fn permits_general_operation(&self) -> bool {
        self.local_start_consumed.load(Ordering::SeqCst)
            || (self.completed.load(Ordering::SeqCst) && !self.stop.load(Ordering::SeqCst))
    }
    pub(super) fn no_local_start(&self) -> bool {
        !self.local_start_consumed.load(Ordering::SeqCst)
    }
}
/// Strict source-owned follow-up branch: preparation is not terminal SDK
/// absence. Current receipts must still be exactly held behind closed gates.
pub(super) fn require_uncommitted(host: &Host, java: &Value) -> Result<(), String> {
    let a = current(host)?;
    require_ready(host, false)?;
    if a.local_start_consumed.load(Ordering::SeqCst) {
        return Err("prepared local Start already entered".into());
    }
    let actual = host
        .callbacks
        .prepared_registry_status(&a.request.preparation_id)?;
    if java != &actual
        || actual["delivery_gate_closed"] != true
        || !matches!(actual["state"].as_str(), Some("decoder_ready" | "ready"))
    {
        return Err("actual SDK preparation is not gated/uncommitted".into());
    }
    crate::camera_hwb_probe::prepared_peer_renderer_dormant_for(&a.request.preparation_id)?;
    Ok(())
}
/// Deliberate v2 branch, never a relaxation of the legacy terminal proof.
pub(super) fn validate_physical(
    host: &Host,
    native: &Value,
    physical: &Value,
) -> Result<(), String> {
    const KEYS: [&str; 14] = [
        "$schema",
        "eligible",
        "classification",
        "own_capture_state",
        "peer_resources_terminal",
        "renderer_terminal",
        "control_terminal",
        "unresolved_original_count",
        "original_binding",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
        "prepared_start",
    ];
    let binding = &physical["original_binding"];
    if !physical
        .as_object()
        .is_some_and(|o| o.len() == 14 && KEYS.iter().all(|k| o.contains_key(*k)))
        || physical["$schema"] != "rusty.quest.embedded_duplex.fresh_setup_physical_eligibility.v2"
        || physical["eligible"] != true
        || physical["classification"] != "prepared_uncommitted"
        || physical["peer_resources_terminal"] != false
        || physical["renderer_terminal"].as_bool().is_none()
        || physical["control_terminal"] != true
        || physical["unresolved_original_count"] != 0
        || physical["app_process_generation"] != host.capability.binding.generation
        || physical["app_record_sha256"] != host.capability.binding.record_sha256
        || physical["native_executor_generation"] != host.capability.generation
        || physical["config_sha256"] != host.config_sha256
        || !matches!(
            physical["own_capture_state"].as_str(),
            Some("live_independent" | "retired" | "not_created")
        )
        || !matches!(
            native["local"]["state"].as_str(),
            Some("never_started" | "fully_stopped")
        )
        || !binding.is_object()
    {
        return Err("prepared actual v2 physical owner/actor/lifetime differs".into());
    }
    // The enclosing fresh/Root route validates its exact original_binding. The
    // nonclone arena plus fixed registry read is the resource-ownership proof.
    require_uncommitted(host, &physical["prepared_start"])
}
pub(super) fn abort(host: &Host) -> Result<String, String> {
    abort_impl(host, false)
}
fn abort_impl(host: &Host, whole_cleanup: bool) -> Result<String, String> {
    let a = held(host)?;
    {
        let p = process()
            .lock()
            .map_err(|_| "prepared abort sole-owner gate poisoned")?;
        if p.host_leases != 1
            || p.root_reestablishment.is_some()
            || p.fresh_setup_request.is_some() && !whole_cleanup
        {
            return Err(
                "prepared SDK abort requires actual original/callback/transport joins".into(),
            );
        }
    }
    if whole_cleanup
        && process()
            .lock()
            .map_err(|_| "prepared pending fresh gate poisoned")?
            .fresh_setup_request
            .is_some()
    {
        // Pending pure authority remains UNKNOWN and must be durably retired
        // afterwards. Its real reservation/worker joins permit only SDK abort.
        pair_ceremony::pending_fresh_closure(host)?;
    }
    if a.local_start_consumed.load(Ordering::SeqCst) {
        return Err("entered media Start requires actual Peer/whole cleanup".into());
    }
    // This Native proof includes genuine incoming receiver/cleanup history;
    // absence of a local Start alone cannot authorize aborting live Peer work.
    peer_lifecycle::fresh_setup_eligibility(host)?;
    let prior_terminal = a
        .terminal
        .lock()
        .map_err(|_| "prepared actual terminal custody poisoned")?
        .clone();
    if let Some(receipt) = prior_terminal {
        let actual = host
            .callbacks
            .prepared_registry_status(&a.request.preparation_id)?;
        if actual["state"] != "terminal" || actual["delivery_gate_closed"] != true {
            return Err("prepared prior abort actual terminal owner changed".into());
        }
        return Ok(receipt.to_string());
    }
    a.stop.store(true, Ordering::SeqCst);
    a.aborting.store(true, Ordering::SeqCst);
    let cleanup = host.callbacks.current_operator_original_id()?;
    let request = rusty_quest_media_stream_android::AndroidPreparedAbortRequest {
        schema_id: "rusty.quest.android.media.prepared_abort_request.v1".into(),
        preparation_id: a.request.preparation_id.clone(),
        cleanup_original_id: cleanup,
        app_process_generation: a.request.app_process_generation,
        app_record_sha256: a.request.app_record_sha256.clone(),
        native_executor_generation: a.request.native_executor_generation,
        config_sha256: a.request.config_sha256.clone(),
        request_sha256: a.request.digest()?,
        resource_target_sha256: a.request.resource_target_sha256.clone(),
    };
    let receipt = host.callbacks.abort_prepared_peer_start(&request)?;
    receipt.validate(&a.request, &request)?;
    crate::camera_hwb_probe::prepared_peer_renderer_terminal_for(&a.request.preparation_id)?;
    let actual = host
        .callbacks
        .prepared_registry_status(&a.request.preparation_id)?;
    if actual["preparation_id"] != a.request.preparation_id
        || actual["request_sha256"] != a.request.digest()?
        || actual["resource_target_sha256"] != a.request.resource_target_sha256
        || actual["state"] != "terminal"
        || actual["delivery_gate_closed"] != true
    {
        return Err("actual prepared abort terminal owner readback differs".into());
    }
    let receipt = serde_json::to_value(receipt).map_err(safe_decode)?;
    *a.terminal
        .lock()
        .map_err(|_| "prepared terminal receipt custody poisoned")? = Some(receipt.clone());
    a.aborting.store(false, Ordering::SeqCst);
    Ok(receipt.to_string())
}

/// Transfer immutable metadata to the actual app-owned durable journal before
/// replacing a completed/aborted preparation. Never replay a lost SDK return.
fn archive_prior(host: &Host, current_cleanup: Option<&str>) -> Result<(), String> {
    let old = {
        let p = process()
            .lock()
            .map_err(|_| "prepared archive gate poisoned")?;
        let Some(old) = p.prepared_peer_start.as_ref() else {
            return Ok(());
        };
        if p.host_leases != 1 {
            return Err(
                "prepared archive requires every original Native frame/SDK borrower joined".into(),
            );
        }
        old.clone()
    };
    let terminal = old
        .terminal
        .lock()
        .map_err(|_| "prepared terminal archive poisoned")?
        .clone();
    let lifetime = if let Some(receipt) = terminal {
        let id = receipt["cleanup_original_id"]
            .as_str()
            .ok_or("prepared abort Original missing")?;
        if current_cleanup.is_some_and(|c| c != id) {
            return Err("prepared current cleanup abort Original differs".into());
        }
        json!({"kind":if current_cleanup.is_some(){"current_cleanup_abort"}else{"explicit_prepared_abort"},"original_id":id,"receipt":receipt})
    } else {
        peer_lifecycle::prepared_terminal_lifetime(host)?
    };
    // Join the same registered renderer after its earned terminal lifetime.
    // Existing-Own reuse may be released without its metadata being closed;
    // this exact terminal join never aborts or retires independent Own.
    crate::camera_hwb_probe::prepared_peer_renderer_terminal_for(&old.request.preparation_id)?;
    let mut body = old
        .archive_body
        .lock()
        .map_err(|_| "prepared immutable archive body poisoned")?;
    if body.is_none() {
        let status: Value = serde_json::from_str(&status(host)?).map_err(safe_decode)?;
        if status["owner"].is_null() {
            return Err("prepared actual SDK owner still executing".into());
        }
        let value = json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_lifetime_archive.v1","original_id":old.request.original_id,
            "app_process_generation":old.request.app_process_generation,"app_record_sha256":old.request.app_record_sha256,
            "native_executor_generation":old.request.native_executor_generation,"config_sha256":old.request.config_sha256,
            "prepared_status":status,"terminal_lifetime":lifetime});
        *body = Some(
            String::from_utf8(prepared_start_canonical(&value)?)
                .map_err(|_| "prepared archive UTF8")?,
        );
    }
    let raw = body
        .as_ref()
        .ok_or("prepared archive retained bytes absent")?;
    let ack = host.callbacks.archive_completed_prepared_start(raw)?;
    const KEYS: [&str; 8] = [
        "$schema",
        "body_sha256",
        "original_id",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
        "storage_identity",
    ];
    let digest = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(raw)
    );
    if !ack
        .as_object()
        .is_some_and(|o| o.len() == 8 && KEYS.iter().all(|k| o.contains_key(*k)))
        || ack["$schema"] != "rusty.quest.embedded_duplex.prepared_start_lifetime_retained.v1"
        || ack["body_sha256"] != digest
        || ack["original_id"] != old.request.original_id
        || ack["app_process_generation"] != old.request.app_process_generation
        || ack["app_record_sha256"] != old.request.app_record_sha256
        || ack["native_executor_generation"] != old.request.native_executor_generation
        || ack["config_sha256"] != old.request.config_sha256
        || ack["storage_identity"].as_str().is_none_or(str::is_empty)
    {
        return Err("prepared actual durable metadata custody ACK differs".into());
    }
    host.capability.require_live()?;
    let mut p = process()
        .lock()
        .map_err(|_| "prepared archive final gate poisoned")?;
    if p.host_leases != 1
        || p.prepared_peer_start
            .as_ref()
            .is_none_or(|a| !Arc::ptr_eq(a, &old))
    {
        return Err("prepared archive actual owner changed before handoff".into());
    }
    let prior = p.prepared_archive_cursor.as_ref();
    let ordinal = prior
        .and_then(|v| v["ordinal"].as_u64())
        .unwrap_or(0)
        .checked_add(1)
        .ok_or("prepared archive ordinal exhausted")?;
    let chain = prepared_start_digest(
        "rusty.quest.embedded_duplex.prepared_start_archive_chain.v1",
        &json!({"previous":prior,"ack":ack}),
    )?;
    p.prepared_archive_cursor = Some(
        json!({"$schema":"rusty.quest.embedded_duplex.prepared_start_archive_cursor.v1","ordinal":ordinal,"last":ack,"history_sha256":chain}),
    );
    p.prepared_peer_start = None;
    Ok(())
}
pub(super) fn retire_metadata(config: &str) -> Result<String, String> {
    let host = cleanup_host()?;
    if config != host.config_sha256 {
        return Err("prepared cleanup actual config differs".into());
    }
    let id = held(&host)?.request.preparation_id.clone();
    // Fixed app callback enforces actual current whole_app_close Original and
    // joined old prepare tasks. This does not certify whole physical cleanup.
    let current = host.callbacks.current_operator_original_id()?;
    let prior_terminal = held(&host)?
        .terminal
        .lock()
        .map_err(|_| "prepared prior terminal poisoned")?
        .is_some();
    abort_impl(&host, true)?;
    archive_prior(&host, if prior_terminal { None } else { Some(&current) })?;
    let cursor = process()
        .lock()
        .map_err(|_| "prepared retired cursor poisoned")?
        .prepared_archive_cursor
        .clone()
        .ok_or("prepared retained archive cursor absent")?;
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.prepared_peer_start_metadata_retirement.v1",
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,"preparation_id":id,
        "archive_cursor":cursor,"metadata_retired":true,"physical_cleanup_claimed":false}).to_string())
}
pub(super) fn whole_close_custody(host: &Host, close: &Value) -> Result<Option<Value>, String> {
    let present = process()
        .lock()
        .map_err(|_| "prepared whole custody gate poisoned")?
        .prepared_peer_start
        .is_some();
    if !present {
        return Ok(None);
    }
    if close["peer_physical_cleanup"] != "terminal" {
        return Err("prepared whole custody lacks actual Peer terminal proof".into());
    }
    let a = held(host)?;
    let actual = host
        .callbacks
        .prepared_registry_status(&a.request.preparation_id)?;
    if actual["state"] != "terminal" || actual["delivery_gate_closed"] != true {
        return Err("prepared whole custody SDK owner not terminal".into());
    }
    crate::camera_hwb_probe::prepared_peer_renderer_terminal_for(&a.request.preparation_id)?;
    let status: Value = serde_json::from_str(&status(host)?).map_err(safe_decode)?;
    if status["owner"].is_null() {
        return Err("prepared whole custody has executing SDK original".into());
    }
    let cursor = process()
        .lock()
        .map_err(|_| "prepared whole cursor poisoned")?
        .prepared_archive_cursor
        .clone();
    Ok(Some(json!({"status":status,"archive_cursor":cursor})))
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_retirePreparedPeerStartMetadata(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jstring {
    let result = read_string(&mut env, &config, 64).and_then(|config| retire_metadata(&config));
    return_string(&mut env, result)
}
