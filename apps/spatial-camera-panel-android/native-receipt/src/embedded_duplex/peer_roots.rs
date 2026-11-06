//! One actual same-incarnation root action; no tokens, ARM or Peer reset.
use super::*;
use rusty_manifold_model::{DottedId, ManifoldSourceBoundPeerDisposition};
use rusty_manifold_peer::fresh_history_canonical;
use rusty_manifold_peer_runtime_host::ManifoldSourceBoundRootNativeWitness;
use serde::Serialize;
use sha2::{Digest, Sha256};

pub(super) struct RootFlight {
    original: String,
    config: String,
    generation: u64,
    stop: Arc<AtomicBool>,
    committed_flag: Arc<AtomicBool>,
    // An entered commit is retained before any fallible formatting/readback.
    committed: Option<Value>,
    terminal_body: Option<String>,
}
impl RootFlight {
    pub(super) fn is_committed_for(&self, h: &Host) -> bool {
        self.generation == h.capability.generation
            && self.config == h.config_sha256
            && self.committed_flag.load(Ordering::SeqCst)
            && self.committed.is_some()
    }
}
struct FlightGuard {
    original: String,
}
impl Drop for FlightGuard {
    fn drop(&mut self) {
        let mut p = process().lock().unwrap_or_else(|e| e.into_inner());
        if p.root_reestablishment.as_ref().is_some_and(|f| {
            f.original == self.original && !f.committed_flag.load(Ordering::SeqCst)
        }) {
            p.root_reestablishment = None;
        }
    }
}
pub(super) fn digest<T: Serialize>(domain: &str, value: &T) -> Result<String, String> {
    let value = serde_json::to_value(value).map_err(safe_decode)?;
    let bytes = fresh_history_canonical(&value).map_err(|_| "root canonical data rejected")?;
    let mut h = Sha256::new();
    h.update(domain.as_bytes());
    h.update([0]);
    h.update(bytes);
    Ok(format!("sha256:{:x}", h.finalize()))
}
fn actor(host: &Host) -> Value {
    json!({"app_process_generation":host.capability.binding.generation,
    "app_record_sha256":host.capability.binding.record_sha256,"native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256})
}
fn actor_matches(host: &Host, value: &Value) -> bool {
    let expected = actor(host);
    [
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
    ]
    .iter()
    .all(|k| value.get(*k) == expected.get(*k))
}
fn exact(value: &Value, keys: &[&str]) -> bool {
    value
        .as_object()
        .is_some_and(|o| o.len() == keys.len() && keys.iter().all(|k| o.contains_key(*k)))
}
fn classify(roots: &Value, now: u64) -> &'static str {
    let g = &roots["grant"];
    let c = &roots["control_lease"];
    let r = &roots["runtime_lease"];
    if g.is_null() || c.is_null() || r.is_null() {
        return "absent";
    }
    if g.get("revoked").and_then(Value::as_bool) != Some(false)
        || c.get("state").and_then(Value::as_str) != Some("active")
    {
        return "revoked";
    }
    if [g, c, r].iter().any(|v| {
        v.get("expires_at_ms")
            .and_then(Value::as_u64)
            .is_none_or(|t| t <= now)
    }) {
        "expired"
    } else {
        "current"
    }
}
fn physical(host: &Host, native: &Value, proof: &Value, original: &str) -> Result<Value, String> {
    if !exact(
        proof,
        &[
            "$schema",
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
            "original_binding",
            "arm_binding",
            "physical_eligibility",
        ],
    ) || proof["$schema"]
        != "rusty.quest.embedded_duplex.peer_root_reestablishment_eligibility.v1"
        || !actor_matches(host, proof)
        || proof["original_binding"] != json!({"kind":"operator","original_id":original})
    {
        return Err("root actual app Original/actor proof differs".into());
    }
    let arm = &proof["arm_binding"];
    if !exact(
        arm,
        &[
            "binding",
            "challenge",
            "arm_generation",
            "native_process",
            "process_epoch",
            "runtime_config_sha256",
        ],
    ) || arm["binding"] != "established"
        || arm["runtime_config_sha256"] != host.config_sha256
        || arm["native_process"].as_u64().is_none_or(|n| n == 0)
        || arm["process_epoch"].as_str().is_none_or(|s| s.is_empty())
        || arm["arm_generation"].as_u64().is_none_or(|n| n == 0)
        || arm["challenge"].as_str().is_none_or(|s| s.is_empty())
    {
        return Err("root same actual ARM missing".into());
    }
    let p = &proof["physical_eligibility"];
    if p["$schema"] == "rusty.quest.embedded_duplex.fresh_setup_physical_eligibility.v2" {
        if p["original_binding"] != proof["original_binding"] {
            return Err("prepared root actual Original differs".into());
        }
        prepared_start::validate_physical(host, native, p)?;
        return Ok(arm.clone());
    }
    if !exact(
        p,
        &[
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
        ],
    ) || p["$schema"] != "rusty.quest.embedded_duplex.fresh_setup_physical_eligibility.v1"
        || !actor_matches(host, p)
        || p["original_binding"] != proof["original_binding"]
        || p["eligible"] != true
        || p["unresolved_original_count"] != 0
        || p["peer_resources_terminal"] != true
        || p["renderer_terminal"] != true
        || p["control_terminal"] != true
        || p["classification"] != native["local"]["state"]
        || !matches!(
            p["own_capture_state"].as_str(),
            Some("live_independent" | "retired" | "not_created")
        )
    {
        return Err("root actual Peer/Own physical producer not eligible".into());
    }
    Ok(arm.clone())
}
/// Root-status is the sole readonly Host borrower while a root flight is live.
/// It never enters a provider checkout or changes source/clock/root state.
pub(super) fn observation_host() -> Result<HostLease, String> {
    let mut p = process()
        .lock()
        .map_err(|_| "root observation process gate poisoned")?;
    if p.closing {
        return Err("root observation runtime closing".into());
    }
    let h = p.host.clone().ok_or("root observation owner absent")?;
    p.host_leases = p
        .host_leases
        .checked_add(1)
        .ok_or("root observer lease overflow")?;
    drop(p);
    let h = HostLease(h);
    h.capability.require_live()?;
    Ok(h)
}
/// Deliberate current-owner observation; unavailable current authority is no
/// proof of media admission or cleanup. Historical Root19 remains unchanged.
pub(super) fn current_lineage(host: &Host, now: u64) -> Result<Option<Value>, String> {
    let seed = {
        let p = process()
            .lock()
            .map_err(|_| "root lineage process poisoned")?;
        p.last_root_reestablishment
            .as_ref()
            .filter(|r| actor_matches(host, r))
            .cloned()
    };
    let Some(seed) = seed else { return Ok(None) };
    let seed = serde_json::from_value(
        seed.get("owner_receipt")
            .cloned()
            .ok_or("root lineage full seed absent")?,
    )
    .map_err(safe_decode)?;
    let session = pair_ceremony::current_session_identity(host)?;
    serde_json::to_value(host.authority.current_root_lineage_observation(
        &seed,
        &DottedId::new(session).map_err(|_| "root lineage session")?,
        now,
    )?)
    .map(Some)
    .map_err(safe_decode)
}
pub(super) fn status(host: &Host) -> Result<String, String> {
    host.capability.require_live()?;
    let now = host.clock.now_ms()?;
    let (active, previous, fresh) = {
        let p = process()
            .lock()
            .map_err(|_| "root status process poisoned")?;
        (
            p.root_reestablishment.is_some(),
            p.last_root_reestablishment
                .as_ref()
                .filter(|r| actor_matches(host, r))
                .cloned(),
            p.fresh_setup_request.is_some(),
        )
    };
    let roots = host.authority.peer_root_projection()?;
    let pair = pair_ceremony::status(host)?;
    let pair: Value = serde_json::from_str(&pair).map_err(safe_decode)?;
    // Current pair comes from actual M, not the old completed receipt time.
    let session = pair.get("session_id").and_then(Value::as_str);
    let current_pair = if let Some(session) = session {
        Some(host.authority.current_common_lan_session(
            &DottedId::new(session).map_err(|_| "root status session identity")?,
            now,
        )?)
    } else {
        None
    };
    let lifecycle_pending = peer_lifecycle::fresh_setup_eligibility(host).is_err();
    let pending = active || fresh || lifecycle_pending;
    let value = rusty_quest_broker_authority::project_peer_root_status(
        &actor(host),
        &roots,
        previous.as_ref(),
        current_pair.as_ref(),
        pending,
        now,
    )?;
    Ok(value.to_string())
}
pub(super) fn run(host: &Host) -> Result<String, String> {
    host.capability.require_live()?;
    if host.runtime_configuration_binding.is_none() {
        return Err("root effect requires selected successful source-bound constructor".into());
    }
    let original = host.callbacks.current_operator_original_id()?;
    let stop = Arc::new(AtomicBool::new(false));
    let committed_flag = Arc::new(AtomicBool::new(false));
    {
        let mut p = process()
            .lock()
            .map_err(|_| "root reservation process poisoned")?;
        if p.initializing
            || p.closing
            || p.host_leases != 1
            || p.lease_integrity_failed
            || p.root_reestablishment.is_some()
            || p.fresh_setup_request.is_some()
            || p.host.as_ref().is_none_or(|h| {
                h.config_sha256 != host.config_sha256
                    || h.capability.generation != host.capability.generation
            })
        {
            return Err("root effect requires sole same live Host borrower".into());
        }
        // Retain before any further fallible callback or transaction work.
        p.root_reestablishment = Some(RootFlight {
            original: original.clone(),
            config: host.config_sha256.clone(),
            generation: host.capability.generation,
            stop: stop.clone(),
            committed_flag: committed_flag.clone(),
            committed: None,
            terminal_body: None,
        });
    }
    let _flight = FlightGuard {
        original: original.clone(),
    };
    let (disposition, ordinal, start, terminal, mut native) =
        peer_lifecycle::root_lifetime_witness(host)?;
    let proof = host.callbacks.peer_root_eligibility()?;
    let arm = physical(host, &native, &proof, &original)?;
    let (fresh, session) = pair_ceremony::fresh_root_basis(host)?;
    let now_before = host.clock.now_ms()?;
    let actual_pair = host.authority.current_common_lan_session(
        &DottedId::new(session.clone()).map_err(|_| "root session identity")?,
        now_before,
    )?;
    if !actual_pair.current {
        return Err(operation_failure::session_not_current(
            "reestablish_peer_roots",
            &session,
            serde_json::to_value(actual_pair).map_err(safe_decode)?,
        )?);
    }
    let prior = host.authority.peer_root_projection()?;
    // Closed canonical DATA qualification precedes any owner commit.
    fresh_history_canonical(&actor(host)).map_err(|_| "root actor canonical data")?;
    fresh_history_canonical(&arm).map_err(|_| "root ARM canonical data")?;
    fresh_history_canonical(&prior).map_err(|_| "root prior roots canonical data")?;
    if classify(&prior, host.clock.now_ms()?) != "expired" {
        return Err(
            "root action requires actual expired retained component; current roots use status"
                .into(),
        );
    }
    // Seal prior completed outcome in its actual Java Original journal before
    // dropping owner suffix bodies into an earned same-owner checkpoint.
    let previous = {
        let p = process()
            .lock()
            .map_err(|_| "root prior custody gate poisoned")?;
        p.last_root_reestablishment
            .as_ref()
            .filter(|r| actor_matches(host, r))
            .cloned()
    };
    if let Some(previous) = previous {
        let body = previous.to_string();
        let ack = host.callbacks.retain_completed_peer_root_outcome(&body)?;
        let hash = format!(
            "sha256:{}",
            rusty_quest_broker_authority::packaged_json_sha256(&body)
        );
        if !exact(
            &ack,
            &[
                "$schema",
                "body_sha256",
                "original_id",
                "app_process_generation",
                "app_record_sha256",
                "native_executor_generation",
                "config_sha256",
                "storage_identity",
            ],
        ) || ack["$schema"] != "rusty.quest.embedded_duplex.peer_root_outcome_custody.v1"
            || !actor_matches(host, &ack)
            || ack["body_sha256"] != hash
            || ack["original_id"] != previous["original_id"]
            || ack["storage_identity"]
                .as_str()
                .is_none_or(|s| s.is_empty())
        {
            return Err("root prior actual Original durable custody missing".into());
        }
        if host
            .authority
            .checkpoint_dormant_peer_roots(host.clock.now_ms()?)?
            .is_some()
        {
            let (_, _, _, _, refreshed) = peer_lifecycle::root_lifetime_witness(host)?;
            let mut before = native.clone();
            let mut after = refreshed.clone();
            before
                .as_object_mut()
                .ok_or("root native proof object")?
                .remove("broker_evidence");
            after
                .as_object_mut()
                .ok_or("root native proof object")?
                .remove("broker_evidence");
            if before != after {
                return Err("root lifecycle changed during its exact history checkpoint".into());
            }
            // Only the earned checkpoint's expected Broker history projection
            // changes. Current roots/M obligations were validated unchanged.
            native = refreshed;
        }
    }
    if host.callbacks.peer_root_desired_stop_requested()? {
        stop.store(true, Ordering::SeqCst);
    }
    if stop.load(Ordering::SeqCst) {
        return Err("root explicit DesiredStop".into());
    }
    // Rejoin physical and Native facts after callbacks, before owner mutation.
    let (_, _, _, _, current) = peer_lifecycle::root_lifetime_witness(host)?;
    if current != native {
        return Err("root actual lifecycle/source changed before commit".into());
    }
    let (clock, now) = {
        let mut provider = Checkout::take(host.provider.clone())?;
        let before = monotonic_ns()?;
        let wall = host.clock.now_ms()?;
        let after = monotonic_ns()?;
        let wall_i = i64::try_from(wall).map_err(|_| "root OS clock overflow")?;
        let c = provider
            .get()
            .read_concurrent_peer_clock(
                wall_i,
                after,
                after.checked_sub(before).ok_or("root OS clock reversed")?,
            )
            .map_err(|_| "root actual OS authority clock refused")?;
        (c, wall)
    };
    let request_id = DottedId::new(format!("request.root.{original}"))
        .map_err(|_| "root actual Original request identity")?;
    let witness = ManifoldSourceBoundRootNativeWitness {
        request_id: request_id.clone(),
        native_setup_request_id: fresh.request.context.native_setup_request_id.clone(),
        native_phase_id: fresh.request.context.native_phase_id.clone(),
        peer_disposition: disposition,
        lifetime_ordinal: ordinal,
        prior_start_sha256: start,
        terminal_stop_sha256: terminal,
        native_lifecycle_sha256: digest(
            "rusty.quest.embedded_duplex.root_native_lifecycle.v1",
            &native,
        )?,
        native_actor_sha256: digest(
            "rusty.quest.embedded_duplex.root_native_actor.v1",
            &actor(host),
        )?,
        runtime_configuration_sha256: format!("sha256:{}", host.config_sha256),
        arm_binding_sha256: digest("rusty.quest.embedded_duplex.root_arm.v1", &arm)?,
    };
    let receipt = host.authority.reestablish_peer_roots(
        witness,
        &fresh,
        &DottedId::new(session).map_err(|_| "root session identity")?,
        clock,
        now,
        &stop,
    )?;
    committed_flag.store(true, Ordering::SeqCst);
    let receipt_value = serde_json::to_value(&receipt).map_err(safe_decode)?;
    // Preserve actual returned commit before any later observation/format work.
    {
        let mut p = process()
            .lock()
            .map_err(|_| "root committed custody poisoned")?;
        let f = p
            .root_reestablishment
            .as_mut()
            .filter(|f| f.original == original)
            .ok_or("root committed flight absent")?;
        f.committed = Some(receipt_value);
    }
    // Derive only the exact returned typed owner delta, never a later query
    // with a refreshed observation time. The actual status reader rejoins it.
    let result = rusty_quest_broker_authority::project_peer_root_reestablishment(
        &original,
        &actor(host),
        &arm,
        &prior,
        &receipt,
    )?;
    {
        let mut p = process()
            .lock()
            .map_err(|_| "root final custody poisoned")?;
        if !p
            .root_reestablishment
            .as_ref()
            .is_some_and(|f| f.original == original && f.committed.is_some())
        {
            return Err("root actual commit custody absent".into());
        }
        p.last_root_reestablishment = Some(result.clone());
        p.root_reestablishment = None;
    }
    Ok(result.to_string())
}
fn request_stop(id: &str) -> Result<String, String> {
    let cap = native_fence_jni::active()?;
    cap.require_live()?;
    let p = process().lock().map_err(|_| "root Stop gate poisoned")?;
    let f = p
        .root_reestablishment
        .as_ref()
        .filter(|f| f.original == id && f.generation == cap.generation)
        .ok_or("root Stop exact entered Original absent")?;
    f.stop.store(true, Ordering::SeqCst);
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.peer_root_stop_requested.v1","original_id":id,
        "config_sha256":f.config,"app_process_generation":cap.binding.generation,"app_record_sha256":cap.binding.record_sha256,
        "native_executor_generation":cap.generation,"desired_stop_latched":true,"original_joined":false,
        "physical_cleanup_claimed":false,"transaction_outcome":"unknown"}).to_string())
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_requestPeerRootStop(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let result = read_string(&mut env, &request, 64).and_then(|id| request_stop(&id));
    match result {
        Ok(value) => env
            .new_string(value)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "root Stop exact Original refused",
            );
            std::ptr::null_mut()
        }
    }
}

/// A terminal metadata handoff, never resolution of the old UNKNOWN outcome.
/// No Host/provider/authority lock is held across the actual app fsync callback.
fn retire_unknown_root_metadata(expected: &str) -> Result<String, String> {
    let cap = native_fence_jni::active()?;
    cap.require_live()?;
    let (host, original, committed) = {
        let mut p = process()
            .lock()
            .map_err(|_| "terminal root process poisoned")?;
        if let Some(proof) = &p.root_terminal_custody {
            if proof["config_sha256"] == expected {
                return Ok(proof.to_string());
            }
            return Err("terminal root custody belongs to another config".into());
        }
        if p.initializing
            || p.host_leases != 0
            || p.lease_integrity_failed
            || p.fresh_setup_request.is_some()
        {
            return Err("terminal root actual native workers not joined".into());
        }
        let h = p.host.clone().ok_or("terminal root Host absent")?;
        let f = p
            .root_reestablishment
            .as_ref()
            .filter(|f| f.is_committed_for(&h))
            .ok_or("terminal root exact retained commit absent")?;
        if h.config_sha256 != expected
            || h.capability.generation != cap.generation
            || h.capability.binding != cap.binding
        {
            return Err("terminal root actor/config differs".into());
        }
        let values = (
            h,
            f.original.clone(),
            f.committed.clone().ok_or("terminal root receipt absent")?,
        );
        // Explicit terminal intent immediately fences every ordinary borrower.
        // Failure preserves flight/closing; retry retains this SAME metadata.
        p.closing = true;
        values
    };
    let seed = serde_json::from_value(committed.clone()).map_err(safe_decode)?;
    let source = host.authority.root_terminal_custody_source(&seed)?;
    let mut body = actor(&host);
    body.as_object_mut().ok_or("terminal root actor")?.extend(
        serde_json::from_value::<serde_json::Map<String,Value>>(json!({"$schema":"rusty.quest.embedded_duplex.unknown_peer_root_terminal_custody.v1",
            "original_id":original,"committed_owner_receipt":committed,"authority_snapshot":source["authority_snapshot"],
            "broker_evidence":source["broker_evidence"],"current_roots":source["current_roots"],"observed_at_ms":host.clock.now_ms()?,
            "transaction_outcome":"unknown","physical_cleanup_claimed":false,
            "future_writable_restore":"fenced_pending_supported_owner_reconciliation"})).map_err(safe_decode)?);
    let proposed = body.to_string();
    let raw = {
        let mut p = process()
            .lock()
            .map_err(|_| "terminal root body custody gate")?;
        let f = p
            .root_reestablishment
            .as_mut()
            .filter(|f| f.original == original && f.is_committed_for(&host))
            .ok_or("terminal root body flight changed")?;
        if let Some(raw) = &f.terminal_body {
            raw.clone()
        } else {
            f.terminal_body = Some(proposed.clone());
            proposed
        }
    };
    let retained: Value = serde_json::from_str(&raw).map_err(safe_decode)?;
    if retained["original_id"] != original
        || !actor_matches(&host, &retained)
        || retained["committed_owner_receipt"] != committed
        || retained["authority_snapshot"] != source["authority_snapshot"]
        || retained["broker_evidence"] != source["broker_evidence"]
        || retained["current_roots"] != source["current_roots"]
    {
        return Err("terminal root retained source changed; no rewritten retry".into());
    }
    let body_sha = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(&raw)
    );
    let ack = host.callbacks.retain_unknown_peer_root_flight(&raw)?;
    if !exact(
        &ack,
        &[
            "$schema",
            "body_sha256",
            "original_id",
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
            "storage_identity",
            "outcome_sha256",
        ],
    ) || ack["$schema"]
        != "rusty.quest.embedded_duplex.unknown_peer_root_terminal_custody_retained.v1"
        || !actor_matches(&host, &ack)
        || ack["body_sha256"] != body_sha
        || ack["original_id"] != original
        || ack["storage_identity"]
            .as_str()
            .is_none_or(|s| s.is_empty())
        || ack["outcome_sha256"].as_str().is_none_or(|s| {
            s.len() != 64
                || !s
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
    {
        return Err("terminal root actual UNKNOWN custody ACK differs".into());
    }
    // App callback has joined its actual old Original/Future/callback/socket;
    // current Native source is independently rechecked before data release.
    if host.authority.root_terminal_custody_source(&seed)? != source {
        return Err("terminal root source changed during custody".into());
    }
    let mut proof = actor(&host);
    proof.as_object_mut().ok_or("terminal root proof actor")?.extend(
        serde_json::from_value::<serde_json::Map<String,Value>>(json!({"$schema":"rusty.quest.embedded_duplex.unknown_peer_root_metadata_retirement.v1",
            "original_id":original,"body_sha256":body_sha,"custody":ack,"transaction_outcome":"unknown","metadata_retired":true,
            "physical_cleanup_claimed":false,"future_writable_restore":"fenced_pending_supported_owner_reconciliation"})).map_err(safe_decode)?);
    let mut p = process()
        .lock()
        .map_err(|_| "terminal root final process poisoned")?;
    if !p.closing
        || p.initializing
        || p.host_leases != 0
        || p.lease_integrity_failed
        || !p
            .host
            .as_ref()
            .is_some_and(|h| Arc::ptr_eq(&h.pair_state, &host.pair_state))
        || !p.root_reestablishment.as_ref().is_some_and(|f| {
            f.original == original
                && f.is_committed_for(&host)
                && f.committed.as_ref() == Some(&committed)
        })
    {
        return Err("terminal root actual flight changed during custody".into());
    }
    p.root_terminal_custody = Some(proof.clone());
    p.retired_fresh_owner_fenced = true;
    p.root_reestablishment = None; // DATA only; prior Original remains UNKNOWN
    Ok(proof.to_string())
}
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_retireUnknownPeerRootMetadata(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    expected: JString<'_>,
) -> jstring {
    let result =
        read_string(&mut env, &expected, 64).and_then(|sha| retire_unknown_root_metadata(&sha));
    return_string(&mut env, result)
}
