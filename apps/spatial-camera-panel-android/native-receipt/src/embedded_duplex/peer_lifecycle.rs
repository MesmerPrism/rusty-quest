//! Fixed installed-app operations. External callers select an enum word only;
//! all authority identity, revisions, time and randomness remain native-owned.
use super::super::preparation_native_guard::{
    FailureReason as NativeReason, Guard as NativeGuard, Trace as NativeTrace,
};
use super::*;
use rusty_quest_broker_authority::QuestConcurrentPeerCommand;
use serde::Serialize;
use std::fs::File;
use std::io::Read;

#[derive(Default, Serialize)]
pub(super) struct State {
    retired_lifetimes: RetiredLifetimes,
    terminal_stop_original_id: Option<String>,
    terminal_stop_projection: Option<Value>,
    last_failure_cause: Option<Value>,
    pub(super) client: Option<String>,
    activation_id: Option<String>,
    start_mutation: Option<Value>,
    activation: Option<Value>,
    stop_mutation: Option<Value>,
    stop_completion: Option<Value>,
    stop_effect: Option<rusty_quest_broker_authority::QuestBrokerMediaStopEffectReceipt>,
    route_receipt: Option<Value>,
    route_termination: Option<Value>,
    termination_action: Option<&'static str>,
    revoker_adoption: Option<Value>,
    route_cleanup: Option<Value>,
    renewals: Vec<Value>,
    renewal_count: u64,
    first_renewal_request_id: Option<String>,
    ongoing_mode: bool,
    ongoing_policy: Option<Value>,
    current_lifetime_renewal_count: u64,
    renewed_session_proofs: Vec<Value>,
    renewal_pending: bool,
    last_failure: Option<String>,
    last_failure_action: Option<&'static str>,
    whole_close_requested: bool,
}

// Optional read-only status projection. It grants no Stop/cleanup authority and
// cannot promote the existing local Peer or whole-app terminal fields.
fn remote_receiver_observation(host: &Host, state: &State) -> Result<Value, String> {
    if state.client.is_some() {
        return Err("local media scope present".into());
    }
    let broker = json_value(
        &Checkout::take(host.provider.clone())?
            .get()
            .evidence_json()
            .map_err(|_| "remote receiver broker observation unavailable")?,
    )?;
    let attempted = state.start_mutation.is_some()
        || state.stop_mutation.is_some()
        || state.stop_completion.is_some()
        || state.activation.is_some()
        || !broker
            .get("media_pending_action")
            .is_some_and(Value::is_null)
        || !broker.get("media_runtime_state").is_some_and(|value| {
            value.is_null()
                || value.get("phase").and_then(Value::as_str) == Some("planned")
                    && value
                        .get("applied_request_ids")
                        .and_then(Value::as_array)
                        .is_some_and(Vec::is_empty)
        });
    if !host.owner_effect_attempted.load(Ordering::SeqCst) {
        return Err("remote receiver history absent".into());
    }
    let cleanup = Checkout::take(host.cleanup_server.clone())?
        .get()
        .replay_snapshot();
    if !cleanup.pending_request_sha256.is_empty() || !cleanup.terminal.is_empty() {
        return Err("remote receiver retained cleanup present".into());
    }
    let history = super::super::remote_owner_close::receiver_observation_history(
        &Checkout::take(host.server.clone())?.get().replay_snapshot(),
        &Checkout::take(host.activation_server.clone())?
            .get()
            .replay_snapshot(),
        &host.local_public_key,
        host.callbacks.key_id(),
        host.capability.generation,
        host.restored_owner_replay,
        attempted,
    )?;
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.remote_receiver_observation.v1",
        "scope":"peer_receiver_only","status":"pending","physical_cleanup":"pending",
        "pending_reason":"java_physical_observation_required","config_sha256":host.config_sha256,
        "native_executor_generation":host.capability.generation,
        "app_process_generation":host.capability.binding.generation,
        "local_peer_id":host.local_peer_id,"remote_peer_id":host.remote_peer_id,"signed_history":history}),
    )
}

/// Actual native prerequisites for the private fresh setup phase. Historical
/// diagnostics never substitute for current absence/terminal proofs.
pub(super) fn fresh_setup_eligibility(host: &Host) -> Result<Value, String> {
    fresh_setup_eligibility_observed(host, &|_, _| {})
}
pub(super) fn fresh_setup_eligibility_observed(
    host: &Host,
    observe: &dyn Fn(NativeGuard, NativeReason),
) -> Result<Value, String> {
    let trace = NativeTrace::new(observe);
    trace.run(|| fresh_setup_eligibility_inner(host, &trace))
}
fn fresh_setup_eligibility_inner(host: &Host, trace: &NativeTrace<'_>) -> Result<Value, String> {
    trace.mark(NativeGuard::Capability, NativeReason::CallFailed);
    host.capability.require_live()?;
    trace.mark(NativeGuard::LocalSlot, NativeReason::CallFailed);
    let local = {
        let slot = host
            .peer_lifecycle
            .try_lock()
            .map_err(|_| "fresh local lifecycle busy")?;
        let state = slot.as_ref().ok_or("fresh local lifecycle checked out")?;
        trace.mark(NativeGuard::LocalState, NativeReason::PredicateRejected);
        if state.renewal_pending || state.whole_close_requested {
            return Err("fresh setup has pending renewal or whole close".into());
        }
        let never = state.client.is_none()
            && state.start_mutation.is_none()
            && state.activation.is_none()
            && state.stop_mutation.is_none()
            && state.stop_completion.is_none()
            && state.route_receipt.is_none()
            && state.route_termination.is_none()
            && state.route_cleanup.is_none()
            && !host.owner_effect_attempted.load(Ordering::SeqCst);
        let stopped = state.client.is_some()
            && state.stop_mutation.is_some()
            && media_stop_effect(state).is_some_and(|v| !v.is_null())
            && state.route_termination.is_some()
            && state.route_cleanup.is_some()
            && state.termination_action == Some("stop");
        if !never && !stopped {
            return Err(
                "fresh setup requires actual NeverStarted or earned normal FullyStopped".into(),
            );
        }
        json!({"state":if never{"never_started"}else{"fully_stopped"},
            "client_id":state.client,"start_mutation":state.start_mutation,"activation":state.activation,
            "stop_mutation":state.stop_mutation,"stop_completion":state.stop_completion,
            "stop_effect":state.stop_effect,"route_termination":state.route_termination,"route_cleanup":state.route_cleanup,
            "renewal_count":state.renewal_count,"renewal_total":state.renewals.len(),
            "current_lifetime_renewal_count":state.current_lifetime_renewal_count})
    };
    trace.mark(NativeGuard::ActivationSlot, NativeReason::CallFailed);
    let activation = host
        .activation_sender
        .try_lock()
        .map_err(|_| "fresh activation sender busy")?;
    let sender = activation
        .as_ref()
        .ok_or("fresh activation sender checked out")?;
    trace.mark(
        NativeGuard::ActivationFlight,
        NativeReason::PredicateRejected,
    );
    if let Some(pending) = sender.pending.as_ref() {
        // complete_start intentionally retains historical acknowledgement
        // material. It is not an unresolved flight after a genuine normal Stop.
        // Do not clear it: start() transfers the prior lifetime and then clears.
        let completed = pending
            .response
            .as_ref()
            .is_some_and(|r| r.status == OwnerDispatchStatus::Completed);
        if local["state"] != "fully_stopped"
            || !completed
            || local["client_id"] != pending.client_id
            || local.pointer("/activation/activation")
                != Some(&serde_json::to_value(&pending.response).map_err(safe_decode)?)
        {
            return Err("fresh activation request pending or terminal history differs".into());
        }
    }
    drop(activation);
    trace.mark(NativeGuard::DispatchSlot, NativeReason::CallFailed);
    let replay = {
        let slot = host
            .server
            .try_lock()
            .map_err(|_| "fresh dispatch slot busy")?;
        slot.as_ref()
            .ok_or("fresh dispatch slot checked out")?
            .replay_snapshot()
    };
    trace.mark(NativeGuard::ActivationReplaySlot, NativeReason::CallFailed);
    let activation = {
        let slot = host
            .activation_server
            .try_lock()
            .map_err(|_| "fresh activation slot busy")?;
        slot.as_ref()
            .ok_or("fresh activation slot checked out")?
            .replay_snapshot()
    };
    trace.mark(NativeGuard::CleanupReplaySlot, NativeReason::CallFailed);
    let cleanup = {
        let slot = host
            .cleanup_server
            .try_lock()
            .map_err(|_| "fresh cleanup slot busy")?;
        slot.as_ref()
            .ok_or("fresh cleanup slot checked out")?
            .replay_snapshot()
    };
    trace.mark(NativeGuard::InboundPending, NativeReason::PredicateRejected);
    if !replay.pending_request_sha256.is_empty()
        || !activation.pending_request_sha256.is_empty()
        || !cleanup.pending_request_sha256.is_empty()
    {
        return Err("fresh setup retained inbound original pending".into());
    }
    // Same signed receiver history validator, under a distinct scope that makes
    // no claim about absence of a local media transaction or physical cleanup.
    let receivers = super::super::remote_owner_close::fresh_setup_receiver_history_observed(
        &replay,
        &activation,
        &host.local_public_key,
        host.callbacks.key_id(),
        host.capability.generation,
        host.restored_owner_replay,
        trace,
    )?;
    host.cleanup
        .fresh_setup_quiescence_observed(&cleanup, trace)?;
    trace.mark(NativeGuard::ProviderSlot, NativeReason::CallFailed);
    let broker = {
        let slot = host
            .provider
            .try_lock()
            .map_err(|_| "fresh provider busy")?;
        let provider = slot.as_ref().ok_or("fresh provider checked out")?;
        trace.mark(NativeGuard::ProviderEvidence, NativeReason::CallFailed);
        let evidence = provider
            .evidence_json()
            .map_err(|_| "fresh broker evidence unavailable")?;
        trace.mark(NativeGuard::ProviderDecode, NativeReason::CallFailed);
        json_value(&evidence)?
    };
    trace.mark(NativeGuard::BrokerPending, NativeReason::PredicateRejected);
    if !broker
        .get("media_pending_action")
        .is_some_and(Value::is_null)
    {
        return Err("fresh broker effect pending".into());
    }
    // Manifold independently proves no live session/current route/pending cleanup
    // in fresh_pair_setup_view/apply, against its exact retained shared owner.
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_native_eligibility.v1",
        "local":local,"receivers":receivers,"cleanup_replay":cleanup,
        "native_executor_generation":host.capability.generation,
        "app_process_generation":host.capability.binding.generation,
        "app_record_sha256":host.capability.binding.record_sha256,
        "config_sha256":host.config_sha256,"broker_evidence":broker}),
    )
}

fn entropy() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut bytes))
        .map_err(|_| "concurrent lifecycle entropy unavailable")?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn json_value(json: &str) -> Result<Value, String> {
    serde_json::from_str(json).map_err(safe_decode)
}

fn projection(host: &Host) -> Result<Value, String> {
    let grant = serde_json::from_value(json!(host.current_grant_id()?)).map_err(safe_decode)?;
    let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
    let remote = serde_json::from_value(json!(host.remote_peer_id)).map_err(safe_decode)?;
    serde_json::to_value(host.authority.current_owner_projection(
        &grant,
        &local,
        &remote,
        host.clock.now_ms()?,
    )?)
    .map_err(safe_decode)
}

fn apply_media(host: &Host, action: QuestConcurrentPeerCommand) -> Result<Value, String> {
    let random = entropy()?;
    let mut provider = Checkout::take(host.provider.clone())?;
    let mutation = provider
        .get()
        .apply_concurrent_peer_command(
            action,
            unsafe { libc::getuid() },
            &host.packaged_route.package_name,
            &host.installed_signing_certificate_sha256,
            host.clock.now_ms()?,
            &random,
        )
        .map_err(|_| "concurrent admitted media mutation rejected")?;
    serde_json::to_value(mutation).map_err(safe_decode)
}

fn media_stop_effect(state: &State) -> Option<&Value> {
    state.stop_completion.as_ref()?.get("stop_effect_receipt")
}

fn current_broker_readback(evidence: &Value, lineage: Option<Value>) -> Result<Value, String> {
    let field = |path: &str| {
        evidence
            .pointer(path)
            .cloned()
            .ok_or_else(|| "actual current Broker readback field absent".to_owned())
    };
    let mut result = json!({"$schema":"rusty.quest.concurrent.broker_current_readback.v1",
        "provider_epoch_id":field("/runtime/provider_epoch_id")?,
        "decision_owner_id":field("/decision_owner_id")?,
        "local_acceptance_rules":field("/local_acceptance_rules")?,
        "host_authority_revision":field("/runtime/host_snapshot/authority_revision")?,
        "current_host_leases":field("/runtime/host_snapshot/leases")?,
        "current_control_lease_authority_revision":field("/runtime/control_lease_authority/current_authority_snapshot/authority_revision")?,
        "current_clock":field("/runtime/control_lease_authority/current_clock")?,
        "current_control_leases":field("/runtime/control_lease_authority/current_authority_snapshot/active_leases")?,
        "admission_authority_revision":field("/runtime/admission_snapshot/authority_revision")?,
        "current_admission_grants":field("/runtime/admission_snapshot/grants")?,
        "media_runtime_state":field("/media_runtime_state")?,
        "media_pending_action":field("/media_pending_action")?});
    if let Some(lineage) = lineage {
        result["$schema"] = json!("rusty.quest.concurrent.broker_current_readback.v2");
        result["root_lineage_observation"] = lineage;
    }
    Ok(result)
}

fn receipt(host: &Host, state: &State, action: &str) -> Result<Value, String> {
    let current = projection(host).ok();
    let observed_at_ms = host.clock.now_ms()?;
    let (broker, selected_policy, native_owner_validation) = {
        let mut provider = Checkout::take(host.provider.clone())?;
        let broker = json_value(
            &provider
                .get()
                .evidence_json()
                .map_err(|_| "concurrent lifecycle evidence unavailable")?,
        )?;
        let policy = provider
            .get()
            .ongoing_media_continuation_policy()
            .map_err(|_| "continuation policy unavailable")?
            .map(serde_json::to_value)
            .transpose()
            .map_err(safe_decode)?;
        let validation = if policy
            .as_ref()
            .and_then(|p| p.get("$schema"))
            .and_then(Value::as_str)
            == Some("rusty.manifold.peer.ongoing_media_policy.v2")
        {
            Some(
                match provider
                    .get()
                    .current_concurrent_ongoing_validation(observed_at_ms)
                {
                    Ok(value) => serde_json::to_value(value).map_err(safe_decode)?,
                    Err(_) => {
                        json!({"$schema":"rusty.quest.concurrent.current_ongoing_owner_validation.v1","status":"unavailable","validated_at_ms":observed_at_ms,"reason":"current_owner_validation_unavailable"})
                    }
                },
            )
        } else {
            None
        };
        (broker, policy, validation)
    };
    let root_seed_present = {
        let p = process()
            .lock()
            .map_err(|_| "root lineage availability gate")?;
        p.last_root_reestablishment.is_some()
    };
    let root_lineage = if root_seed_present {
        Some(
            peer_roots::current_lineage(host, observed_at_ms)
                .ok()
                .flatten()
                .unwrap_or(Value::Null),
        )
    } else {
        None
    };
    let physically_stopped =
        media_stop_effect(state).is_some_and(|v| !v.is_null()) && state.route_cleanup.is_some();
    let active = !state.renewal_pending
        && state.stop_mutation.is_none()
        && state.last_failure.is_none()
        && state.activation.as_ref().is_some_and(|v| {
            v.pointer("/activation/status").and_then(Value::as_str) == Some("completed")
        })
        && current.is_some();
    let (owner_diagnostic, owner_diagnostic_status) =
        owner_failure::diagnostic(host.callbacks.owner_failure_diagnostic());
    let mut value = json!({"$schema":"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1",
        "action":action,"config_sha256":host.config_sha256,
        "native_executor_generation":host.capability.generation,"app_process_generation":host.capability.binding.generation,
        "observed_at_ms":observed_at_ms,
        "status":if physically_stopped{"terminal"}else if active{"active"}else{"pending"},
        "authority_projection":current,"broker_evidence":current_broker_readback(&broker,
            root_lineage)?,
        "start_mutation":state.start_mutation,"stop_mutation":state.stop_mutation,
        "route_receipt":state.route_receipt,"route_termination":state.route_termination,"route_cleanup":state.route_cleanup,
        "termination_action":state.termination_action,"revoker_adoption":state.revoker_adoption,
        "media_completion":state.stop_completion,"media_stop_effect_receipt":media_stop_effect(state),
        "owner_failure_diagnostic":owner_diagnostic,
        "owner_failure_diagnostic_status":owner_diagnostic_status,
        "native_owner_dispatch_failure":*host.owner_dispatch_failure.lock().map_err(|_| "owner diagnostic state unavailable")?,
        "activation":state.activation,"renewal_receipts":&state.renewals[state.renewals.len().saturating_sub(2)..],
        "renewal_total_completed":state.renewals.len(),
        "renewal_first_request_id":state.renewals.first().and_then(|receipt|receipt.get("request_id")),
        "renewal_last_request_id":state.renewals.last().and_then(|receipt|receipt.get("request_id")),
        "renewal_pending":state.renewal_pending,
        "peer_physical_cleanup":if physically_stopped{"terminal"}else{"pending"},
        "native_host_physical_cleanup":"pending","whole_app_physical_cleanup":"pending",
        "last_failure":state.last_failure,"last_failure_action":state.last_failure_action});
    if selected_policy.is_some() {
        value["$schema"] = json!("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v2");
        value["ongoing_media_policy"] = json!(selected_policy);
        let history_v2 = selected_policy
            .as_ref()
            .and_then(|p| p.get("$schema"))
            .and_then(Value::as_str)
            == Some("rusty.manifold.peer.ongoing_media_policy.v2");
        if history_v2 {
            value["$schema"] = json!("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3");
            value["current_lifetime_renewal_count"] = json!(state.current_lifetime_renewal_count);
            value["current_owner_validation"] = json!(native_owner_validation);
        }
        value["renewal_total_completed"] = json!(state.renewal_count);
        value["renewal_first_request_id"] = json!(state.first_renewal_request_id);
        if let Some(latest) = state.renewals.last() {
            value["continuation"] = json!({
                "$schema":"rusty.quest.embedded_duplex.ongoing_media_projection.v1",
                "policy":latest.get("ongoing_media_policy"),
                "cumulative_renewal_count":latest.get("cumulative_renewal_count"),
                "checkpoint_sequence":latest.get("checkpoint_sequence"),
                "checkpointed_renewal_count":latest.get("checkpointed_renewal_count"),
                "suffix_renewal_count":latest.get("suffix_renewal_count"),
                "provider_epoch_id":latest.get("provider_epoch_id"),
                "owner_request_id":latest.get("request_id"),
                "physical_graph_sha256":latest.pointer("/product_adoption/physical_graph_sha256"),
                "partial_implementation":true,
                "generic_history_checkpointing_complete":false
            });
            if history_v2 {
                if latest.get("$schema").and_then(Value::as_str)
                    != Some("rusty.quest.concurrent.authority_renewal_receipt.v3")
                {
                    return Err("native ongoing receipt policy version differs".into());
                }
                value["continuation"]["$schema"] =
                    json!("rusty.quest.embedded_duplex.ongoing_media_projection.v2");
                value["continuation"]["current_lifetime_renewal_count"] =
                    json!(state.current_lifetime_renewal_count);
                value["continuation"]["runtime_history_checkpoint"] =
                    json!(latest.get("runtime_history_checkpoint"));
                value["continuation"]["owner_history_epochs"] =
                    json!(latest.get("owner_history_epochs"));
                value["continuation"]["continuation_checkpoint"] =
                    json!(latest.get("continuation_checkpoint"));
                value["continuation"]["generic_history_checkpointing_complete"] = json!(true);
                value["continuation"]["partial_implementation"] = json!(false);
            }
        }
    }
    if host.authority.selected_fresh_pair_setup_policy()?.is_some() {
        if value.get("$schema").and_then(Value::as_str)
            != Some("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3")
        {
            return Err("fresh lifecycle requires actual ongoing-v2 owner".into());
        }
        append_fresh_lifecycle_fields(&mut value, state)?;
    }
    Ok(value)
}

fn start(host: &Host, state: &mut State) -> Result<(), String> {
    if state.termination_action == Some("revoke") {
        return Err("revoked Peer requires a fresh authenticated authority boundary".into());
    }
    if state.stop_mutation.is_some() || state.stop_completion.is_some() {
        if state.route_cleanup.is_none() {
            return Err("previous Peer transaction has retained cleanup".into());
        }
        // Full physical Stop and owner route cleanup are verified before a
        // new action can replace the previous product activation transaction.
        Checkout::take(host.activation_sender.clone())?
            .get()
            .pending = None;
        let continuation = (
            state.ongoing_mode,
            state.renewal_count,
            state.first_renewal_request_id.clone(),
        );
        let retired = if host.authority.selected_fresh_pair_setup_policy()?.is_some() {
            archive_completed_lifetime(host, state)?
        } else {
            RetiredLifetimes::default()
        };
        *state = State::default();
        state.retired_lifetimes = retired;
        if continuation.0 {
            state.ongoing_mode = true;
            state.renewal_count = continuation.1;
            state.first_renewal_request_id = continuation.2;
        }
    }
    state.ongoing_policy = Checkout::take(host.provider.clone())?
        .get()
        .ongoing_media_continuation_policy()
        .map_err(|_| "continuation policy unavailable")?
        .map(serde_json::to_value)
        .transpose()
        .map_err(safe_decode)?;
    state.ongoing_mode = state.ongoing_policy.is_some();
    if state.renewal_pending {
        return Err("retained authority renewal requires its retry or physical Stop".into());
    }
    if state.start_mutation.is_none() {
        // All unbounded SDK/decoder/renderer preparation precedes this final
        // current pair/root admission. A received opposite-direction Start is
        // allowed; only this exact local preparation can be consumed once.
        prepared_start::require_ready(host, true)?;
        let mutation = apply_media(host, QuestConcurrentPeerCommand::Start)?;
        state.client = Some(
            mutation
                .get("client_id")
                .and_then(Value::as_str)
                .ok_or("native admitted client absent")?
                .to_owned(),
        );
        state.activation_id = Some(format!("activation.concurrent.{}", entropy()?));
        state.start_mutation = Some(mutation);
    }

    if state.route_receipt.is_none() {
        let random = entropy()?;
        let paired = json_value(&pair_ceremony::status(host)?)?;
        if paired
            .pointer("/native_current_session/current")
            .and_then(Value::as_bool)
            != Some(true)
        {
            return Err("current signed paired session absent".into());
        }
        let session = serde_json::from_value(
            paired
                .get("session_id")
                .cloned()
                .ok_or("native paired session identity absent")?,
        )
        .map_err(safe_decode)?;
        let route = Checkout::take(host.provider.clone())?
            .get()
            .issue_concurrent_peer_route(&session, host.clock.now_ms()?, &random)
            .map_err(|_| "concurrent current route issue rejected")?;
        let route = serde_json::to_value(route).map_err(safe_decode)?;
        let grant = route
            .pointer("/record/route/grant_id")
            .and_then(Value::as_str)
            .ok_or("accepted current route grant absent")?;
        let grant_typed = serde_json::from_value(json!(grant)).map_err(safe_decode)?;
        let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
        let remote = serde_json::from_value(json!(host.remote_peer_id)).map_err(safe_decode)?;
        let current = host.authority.current_owner_projection(
            &grant_typed,
            &local,
            &remote,
            host.clock.now_ms()?,
        )?;
        if current.route_configuration_sha256 != host.route_configuration_sha256
            || current.platform_runtime_spec_id
                != host.packaged_route.runtime_spec_ids[host.packaged_route.installed_peer_index]
        {
            return Err("issued route differs from authenticated installed product".into());
        }
        // Update the shared native projection binding only from the actual
        // accepted owner receipt. No transport string can replace this grant.
        *host
            .route_grant_id
            .lock()
            .map_err(|_| "current route binding poisoned")? = grant.to_owned();
        state.route_receipt = Some(route);
        projection(host)?;
    }
    if state.activation.is_none() {
        host.owner_effect_attempted.store(true, Ordering::SeqCst);
        let input =
            json!({"client_id":state.client,"activation_id":state.activation_id}).to_string();
        let acknowledged = complete_start(host, &input)?;
        let value = json_value(&acknowledged)?;
        state.activation = Some(value);
    }

    projection(host)?;
    Ok(())
}

fn require_renewable_media(state: &State) -> Result<(), String> {
    if state.client.is_none()
        || state.stop_mutation.is_some()
        || state.route_cleanup.is_some()
        || !state.activation.as_ref().is_some_and(|value| {
            value.pointer("/activation/status").and_then(Value::as_str) == Some("completed")
        })
    {
        return Err("authority renewal requires the same active media transaction".into());
    }
    if !state.ongoing_mode && state.renewals.len() >= 32 {
        return Err("bounded renewal receipt capacity reached".into());
    }
    Ok(())
}

pub(super) fn require_renewable_peer(host: &Host) -> Result<(), String> {
    host.capability.require_live()?;
    let enabled = {
        let mut provider = Checkout::take(host.provider.clone())?;
        provider
            .get()
            .ongoing_media_continuation_enabled()
            .map_err(|_| "continuation policy unavailable")?
    };
    let mut checked = Checkout::take(host.peer_lifecycle.clone())?;
    checked.get().ongoing_mode = enabled;
    require_renewable_media(checked.get())?;
    projection(host)?;
    Ok(())
}

fn renew_state_after_signed_pair(
    host: &Host,
    state: &mut State,
    pair_proof: &Value,
) -> Result<Value, String> {
    host.capability.require_live()?;
    if let Some(index) = state
        .renewed_session_proofs
        .iter()
        .position(|prior| prior == pair_proof)
    {
        return state
            .renewals
            .get(index)
            .cloned()
            .ok_or("retained renewal receipt missing".into());
    }
    require_renewable_media(state)?;
    let renewal = serde_json::from_value(pair_proof.clone()).map_err(safe_decode)?;
    let before = monotonic_ns()?;
    let wall = i64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "OS authority wall clock unavailable")?
            .as_millis(),
    )
    .map_err(|_| "OS authority wall clock overflow")?;
    let after = monotonic_ns()?;
    let uncertainty = after
        .checked_sub(before)
        .ok_or("OS authority clock reversed")?
        .checked_add(1_000_000)
        .ok_or("OS clock uncertainty overflow")?;
    let now = u64::try_from(wall).map_err(|_| "OS authority wall clock negative")?;
    let result = {
        let mut provider = Checkout::take(host.provider.clone())?;
        let clock = provider
            .get()
            .read_concurrent_peer_clock(wall, after, uncertainty)
            .map_err(|_| "current OS authority clock rejected")?;
        provider
            .get()
            .renew_concurrent_peer_authority(&clock, &renewal, now, &entropy()?)
            .map_err(|_| "current signed authority renewal retained Pending")?
    };
    let value = serde_json::to_value(result).map_err(safe_decode)?;
    host.capability.require_live()?;
    projection(host)?;
    let count = state
        .renewal_count
        .checked_add(1)
        .ok_or("native renewal count exhausted")?;
    if state.ongoing_mode {
        if value
            .get("cumulative_renewal_count")
            .and_then(Value::as_u64)
            != Some(count)
        {
            return Err("native continuation owner count differs".into());
        }
        if state
            .ongoing_policy
            .as_ref()
            .and_then(|p| p.get("$schema"))
            .and_then(Value::as_str)
            == Some("rusty.manifold.peer.ongoing_media_policy.v2")
        {
            let lifetime = value
                .get("current_lifetime_renewal_count")
                .and_then(Value::as_u64)
                .ok_or("native current lifetime count absent")?;
            if value.get("$schema").and_then(Value::as_str)
                != Some("rusty.quest.concurrent.authority_renewal_receipt.v3")
                || lifetime
                    != state
                        .current_lifetime_renewal_count
                        .checked_add(1)
                        .ok_or("native lifetime count exhausted")?
                || value.get("runtime_history_checkpoint").is_none()
            {
                return Err("native v2 ongoing owner fields differ".into());
            }
            state.current_lifetime_renewal_count = lifetime;
        }
        if value.get("continuation_checkpoint").is_some() {
            state.renewed_session_proofs.clear();
            state.renewals.clear();
        }
    }
    if state.first_renewal_request_id.is_none() {
        state.first_renewal_request_id = value
            .get("request_id")
            .and_then(Value::as_str)
            .map(str::to_owned);
    }
    state.renewal_count = count;
    state.renewed_session_proofs.push(pair_proof.clone());
    state.renewals.push(value.clone());
    Ok(value)
}

/// Called only after the signed pair child has applied its actual local owner
/// renewal. The responder performs no network exchange while holding this slot.
pub(super) fn renew_after_signed_pair(host: &Host, pair_proof: &Value) -> Result<Value, String> {
    let mut checked = Checkout::take(host.peer_lifecycle.clone())?;
    let state = checked.get();
    state.renewal_pending = true;
    let result = renew_state_after_signed_pair(host, state, pair_proof);
    match &result {
        Ok(_) => {
            state.renewal_pending = false;
            state.last_failure = None;
            state.last_failure_action = None;
        }
        Err(error) => {
            state.last_failure = Some(error.clone());
            state.last_failure_action = Some("renew_authority");
        }
    }
    result
}

fn capture_pair_renewal_result(
    state: &mut State,
    result: Result<String, String>,
) -> Result<String, String> {
    state.last_failure_cause = result
        .as_ref()
        .err()
        .and_then(|error| super::operation_failure::current_cause(error))
        .filter(|cause| cause.get("operation").and_then(Value::as_str) == Some("renew_authority"));
    result
}

fn renew(host: &Host, state: &mut State) -> Result<(), String> {
    require_renewable_media(state)?;
    projection(host)?;
    state.renewal_pending = true;
    // The actual fixed peerAction path returns Pending on errors. Capture the
    // producer's closed current-operation data before that Result is consumed;
    // never infer it from last_failure, a status reread, or a JVM exception.
    let pair = json_value(&capture_pair_renewal_result(
        state,
        pair_ceremony::renew(host),
    )?)?;
    let proof = pair
        .get("local_session_renewal")
        .ok_or("actual signed local session renewal absent")?;
    if pair.get("remote_session_renewal").is_none() || pair.get("remote_provider_renewal").is_none()
    {
        return Err("actual signed remote authority renewal absent".into());
    }
    renew_state_after_signed_pair(host, state, proof)?;
    let cycle = pair
        .get("renewal_id")
        .and_then(Value::as_str)
        .ok_or("retained signed renewal cycle identity absent")?;
    pair_ceremony::finish_renewal_cycle(host, cycle)?;
    state.renewal_pending = false;
    Ok(())
}

fn stop(host: &Host, state: &mut State, revoke: bool) -> Result<(), String> {
    if state.client.is_none() {
        return Err("Peer Start target absent; no terminal media claim".into());
    }
    if revoke
        && state
            .termination_action
            .is_some_and(|action| action != "revoke")
    {
        return Err("Peer already terminalized by another typed action".into());
    }
    if state.route_cleanup.is_some() {
        return Ok(());
    }
    let client = serde_json::from_value(json!(state.client)).map_err(safe_decode)?;
    let expired = Checkout::take(host.provider.clone())?
        .get()
        .concurrent_peer_requires_revoker_cleanup(&client, host.clock.now_ms()?)
        .map_err(|_| "actual Peer holder expiry readback unavailable")?;
    let retained_abort = Checkout::take(host.provider.clone())?
        .get()
        .has_retained_media_start_abort(&client)
        .map_err(|_| "retained failed Start observation unavailable")?;
    let trusted_cleanup = revoke || expired || retained_abort;
    if trusted_cleanup {
        // Read both real OS clocks. The capability epoch and sequence come
        // from the retained native authority, never an operator timestamp.
        let before = monotonic_ns()?;
        let wall = i64::try_from(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|_| "OS authority wall clock unavailable")?
                .as_millis(),
        )
        .map_err(|_| "OS authority wall clock overflow")?;
        let after = monotonic_ns()?;
        let uncertainty = after
            .checked_sub(before)
            .ok_or("OS authority clock reversed")?
            .checked_add(1_000_000)
            .ok_or("OS clock uncertainty overflow")?;
        let mut provider = Checkout::take(host.provider.clone())?;
        let clock = provider
            .get()
            .read_concurrent_peer_clock(wall, after, uncertainty)
            .map_err(|_| "current OS authority clock rejected")?;
        let adopted = provider
            .get()
            .adopt_concurrent_peer_revoker(
                &clock,
                u64::try_from(wall).map_err(|_| "OS authority wall clock negative")?,
                &entropy()?,
            )
            .map_err(|_| "authenticated current revoker lease adoption pending")?;
        state.revoker_adoption = Some(serde_json::to_value(adopted).map_err(safe_decode)?);
    }
    if retained_abort && state.stop_mutation.is_none() {
        let adoption = serde_json::from_value(
            state
                .revoker_adoption
                .clone()
                .ok_or("actual failed Start cleanup requester absent")?,
        )
        .map_err(safe_decode)?;
        let reviewed = Checkout::take(host.provider.clone())?
            .get()
            .review_concurrent_peer_revoker_cleanup(
                &client,
                &adoption,
                host.clock.now_ms()?,
                &entropy()?,
            )
            .map_err(|_| "actual retained failed Start Revoke review pending")?;
        if state.route_termination.is_none() {
            let route = Checkout::take(host.provider.clone())?
                .get()
                .terminate_concurrent_peer_route(true, host.clock.now_ms()?, &entropy()?)
                .map_err(|_| "retained failed Start route termination pending")?;
            state.route_termination = Some(serde_json::to_value(route).map_err(safe_decode)?);
            state.termination_action = Some("revoke");
        }
        let requester = reviewed
            .cleanup_requester_adoption
            .as_ref()
            .unwrap_or(&reviewed.adoption);
        install_retained_cleanup_requester(
            host,
            requester.revoker_id.as_str(),
            requester.lease.lease_id.as_str(),
        )?;
        Checkout::take(host.provider.clone())?
            .get()
            .resume_revoked_media_start_abort_for_cleanup(&client, &reviewed, host.clock.now_ms()?)
            .map_err(|error| {
                crate::embedded_duplex::cleanup_failure::describe(
                    crate::embedded_duplex::cleanup_failure::Stage::Media,
                    &error,
                )
            })?;
    }
    if state.stop_mutation.is_none() {
        let mutation = if trusted_cleanup {
            let adoption = serde_json::from_value(
                state
                    .revoker_adoption
                    .clone()
                    .ok_or("actual trusted revoker adoption absent")?,
            )
            .map_err(safe_decode)?;
            let prepared = Checkout::take(host.provider.clone())?
                .get()
                .prepare_concurrent_peer_revoker_cleanup(
                    &client,
                    &adoption,
                    host.clock.now_ms()?,
                    &entropy()?,
                )
                .map_err(|_| "actual trusted Revoke and physical Stop preparation pending")?;
            serde_json::to_value(prepared).map_err(safe_decode)?
        } else {
            apply_media(host, QuestConcurrentPeerCommand::Stop)?
        };
        if mutation.get("client_id")
            != state
                .start_mutation
                .as_ref()
                .and_then(|v| v.get("client_id"))
        {
            return Err("Stop media target differs".into());
        }
        state.stop_mutation = Some(mutation);
    }
    if state.route_termination.is_none() {
        let route = Checkout::take(host.provider.clone())?
            .get()
            .terminate_concurrent_peer_route(trusted_cleanup, host.clock.now_ms()?, &entropy()?)
            .map_err(|_| "concurrent route termination pending")?;
        state.route_termination = Some(serde_json::to_value(route).map_err(safe_decode)?);
        state.termination_action = Some(if trusted_cleanup { "revoke" } else { "stop" });
    }
    if trusted_cleanup {
        let adoption = state
            .revoker_adoption
            .as_ref()
            .ok_or("actual current cleanup requester adoption absent")?;
        let requester = adoption
            .get("revoker_id")
            .and_then(Value::as_str)
            .ok_or("actual cleanup requester identity absent")?;
        let lease = adoption
            .pointer("/lease/lease_id")
            .and_then(Value::as_str)
            .ok_or("actual cleanup requester lease absent")?;
        install_retained_cleanup_requester(host, requester, lease)?;
    }
    if state.stop_completion.is_none() {
        let client = serde_json::from_value(json!(state.client)).map_err(safe_decode)?;
        let completed = Checkout::take(host.provider.clone())?
            .get()
            .complete_media_stop_for_cleanup_typed(&client, host.clock.now_ms()?)
            .map_err(|error| {
                crate::embedded_duplex::cleanup_failure::describe(
                    crate::embedded_duplex::cleanup_failure::Stage::Media,
                    &error,
                )
            })?;
        state.stop_effect = completed.stop_effect_receipt.clone();
        state.stop_completion = Some(serde_json::to_value(completed).map_err(safe_decode)?);
    }
    if state.route_cleanup.is_none() {
        let effect = state
            .stop_effect
            .as_ref()
            .ok_or("verified seven-owner Stop effect absent")?;
        let route = Checkout::take(host.provider.clone())?
            .get()
            .complete_concurrent_peer_route_cleanup(effect, host.clock.now_ms()?, &entropy()?)
            .map_err(|error| {
                crate::embedded_duplex::cleanup_failure::describe(
                    crate::embedded_duplex::cleanup_failure::Stage::Route,
                    &error,
                )
            })?;
        state.route_cleanup = Some(serde_json::to_value(route).map_err(safe_decode)?);
    }

    Ok(())
}

fn actual_java_cleanup(env: &mut JNIEnv<'_>) -> Result<bool, String> {
    use jni::objects::JLongArray;
    let resources=env.call_static_method(
        "io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexProcessHost",
        "nativeProductResourcesTerminal","()Z",&[]).map_err(|_|"media resource observation unavailable")?
        .z().map_err(|_|"media resource observation type")?;
    if !resources {
        return Ok(false);
    }
    let own=env.call_static_method(
        "io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/OwnStereoCaptureRuntime",
        "currentForApplication","()Lio/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/OwnStereoCaptureRuntime;",&[])
        .map_err(|_|"Own capture cleanup observation unavailable")?.l().map_err(|_|"Own capture cleanup observation type")?;
    if own.is_null() {
        return Ok(false);
    }
    let cleanup = env
        .call_method(&own, "physicalCleanupState", "()Ljava/lang/String;", &[])
        .map_err(|_| "Own capture cleanup state unavailable")?
        .l()
        .map_err(|_| "Own capture cleanup state type")?;
    if cleanup.is_null() {
        return Ok(false);
    }
    let cleanup = JString::from(cleanup);
    let state: String = env
        .get_string(&cleanup)
        .map_err(|_| "Own capture cleanup encoding")?
        .into();
    if state != "terminal" {
        return Ok(false);
    }
    let array = env
        .call_static_method(
            "io/github/mesmerprism/rustyquest/spatial_camera_panel/StereoBankControls",
            "nativeReadConcurrentStereoQualification",
            "()[J",
            &[],
        )
        .map_err(|_| "renderer physical cleanup observation unavailable")?
        .l()
        .map_err(|_| "renderer cleanup type")?;
    if array.is_null() {
        return Ok(false);
    }
    let array = JLongArray::from(array);
    if env
        .get_array_length(&array)
        .map_err(|_| "renderer cleanup observation length")?
        != 160
    {
        return Ok(false);
    }
    let mut words = [0i64; 160];
    env.get_long_array_region(&array, 0, &mut words)
        .map_err(|_| "renderer cleanup observation unavailable")?;
    Ok(words[0] == 1
        && words[1] == 160
        && words[9] == 2
        && words[63] == 0
        && words[156] == 0
        && words[157] == 0)
}

fn close_host(
    mut value: Value,
    host: HostLease,
    mut checked: Checkout<State>,
) -> Result<String, String> {
    let expected = host.config_sha256.clone();
    host.capability.require_live()?;
    {
        let mut state = process().lock().map_err(|_| "process state poisoned")?;
        if state.initializing
            || state.closing && state.terminal_fresh_owner_body.is_none()
            || state.lease_integrity_failed
            || state.host_leases != 1
        {
            return Ok(value.to_string());
        }
        state.closing = true;
    }
    retain_terminal_fresh_owner(
        &host,
        "normal_peer_whole_close",
        value.clone(),
        Some(serde_json::to_value(checked.get()).map_err(safe_decode)?),
    )?;
    drop(checked);
    drop(host);
    let removed = {
        let mut state = process().lock().map_err(|_| "process state poisoned")?;
        if !state.closing || state.host_leases != 0 || state.lease_integrity_failed {
            return Err("native host close retained Pending".into());
        }
        let mut route = prepared_route()
            .lock()
            .map_err(|_| "packaged route slot poisoned")?;
        if route.as_ref().map(|(sha, _)| sha.as_str()) != Some(expected.as_str()) {
            return Err("native host close configuration changed".into());
        }
        let removed = state.host.take();
        state.terminal_fresh_owner_body = None;
        state.prepared_peer_start = None;
        route.take();
        state.last_closed_sha256 = Some(expected);
        state.closing = false;
        removed
    };
    drop(removed);
    // Only native references have now been released. Java closes its control
    // endpoint and verifies its own barriers before authoring app aggregate.
    value["native_host_physical_cleanup"] = json!("terminal");
    value["whole_app_physical_cleanup"] = json!("pending");
    value["status"] = json!("pending");
    let output = value.to_string();
    process()
        .lock()
        .map_err(|_| "process state poisoned")?
        .last_peer_close_receipt = Some(output.clone());
    Ok(output)
}

pub(super) fn operate(env: &mut JNIEnv<'_>, word: i32) -> Result<String, String> {
    let action = match word {
        1 => "start",
        2 => "renew_authority",
        3 => "peer_stop",
        4 => "peer_revoke",
        5 => "peer_status",
        6 => "whole_app_close",
        _ => return Err("fixed Peer lifecycle action rejected".into()),
    };
    if word == 6 {
        let state = process().lock().map_err(|_| "process state poisoned")?;
        if state.host.is_none() {
            if let Some(receipt) = &state.last_peer_close_receipt {
                return Ok(receipt.clone());
            }
        }
    }
    let host = if word == 6 {
        host_for_root_terminal_cleanup()?
    } else {
        host()?
    };
    host.capability.require_live()?;
    if !crate::own_stereo_capture_runtime::capture_route_selected() {
        return Err("authenticated concurrent source feature inactive".into());
    }
    let mut checked = Checkout::take(host.peer_lifecycle.clone())?;
    let state = checked.get();
    if word == 6 && state.client.is_none() {
        // A remote receiver is not a local seven-owner media transaction.
        // Never route its effect history through close_no_media.
        let broker = json_value(
            &Checkout::take(host.provider.clone())?
                .get()
                .evidence_json()
                .map_err(|_| "remote-owner local media observation unavailable")?,
        )?;
        let local_media_attempted = state.start_mutation.is_some()
            || state.stop_mutation.is_some()
            || state.stop_completion.is_some()
            || state.activation.is_some()
            || !broker
                .get("media_pending_action")
                .is_some_and(Value::is_null)
            || !broker.get("media_runtime_state").is_some_and(|value| {
                value.is_null()
                    || value.get("phase").and_then(Value::as_str) == Some("planned")
                        && value
                            .get("applied_request_ids")
                            .and_then(Value::as_array)
                            .is_some_and(Vec::is_empty)
            });
        if !host.owner_effect_attempted.load(Ordering::SeqCst) {
            return Err("remote-owner effect history absent; use scoped no-media closure".into());
        }
        let cleanup = Checkout::take(host.cleanup_server.clone())?
            .get()
            .replay_snapshot();
        if !cleanup.pending_request_sha256.is_empty() || !cleanup.terminal.is_empty() {
            return Err("retained cleanup history requires its typed owner recovery".into());
        }
        let proof = super::super::remote_owner_close::verified_receivers(
            &Checkout::take(host.server.clone())?.get().replay_snapshot(),
            &Checkout::take(host.activation_server.clone())?
                .get()
                .replay_snapshot(),
            &host.local_public_key,
            host.callbacks.key_id(),
            host.capability.generation,
            host.restored_owner_replay,
            local_media_attempted,
        )?;
        let value = json!({"$schema":"rusty.quest.embedded_duplex.remote_owner_cleanup.v1",
            "action":"whole_app_close","config_sha256":host.config_sha256,
            "native_executor_generation":host.capability.generation,
            "app_process_generation":host.capability.binding.generation,
            "local_peer_id":host.local_peer_id,"remote_peer_id":host.remote_peer_id,
            "remote_receiver_terminal_history":proof,"peer_physical_cleanup":"not_locally_started",
            "native_host_physical_cleanup":"pending","whole_app_physical_cleanup":"pending","status":"pending"});
        host.capability.require_live()?;
        if actual_java_cleanup(env)? {
            return close_host(value, host, checked);
        }
        return Ok(value.to_string());
    }
    if word == 6 {
        state.whole_close_requested = true;
    }
    if word != 5 {
        state.last_failure_cause = None;
    }
    let operation = match word {
        1 => start(&host, state),
        2 => renew(&host, state),
        3 | 6 => stop(&host, state, false),
        4 => stop(&host, state, true),
        5 => Ok(()),
        _ => unreachable!(),
    };
    // Errors after an admitted mutation retain every native credential and
    // report Pending. Status polling cannot turn uncertainty into cleanup.
    if word != 5 {
        state.last_failure = operation.err();
        if state.last_failure.is_none() {
            state.last_failure_action = None;
        } else if state.last_failure_action != Some("renew_authority") || word != 1 {
            state.last_failure_action = Some(action);
        }
    }
    let mut value = receipt(&host, state, action)?;
    if word == 3
        && host.authority.selected_fresh_pair_setup_policy()?.is_some()
        && value.get("status").and_then(Value::as_str) == Some("terminal")
    {
        // This is the exact original production return, not a later reread.
        state.terminal_stop_original_id = Some(host.callbacks.current_operator_original_id()?);
        state.terminal_stop_projection = Some(value.clone());
    }
    if word == 5 {
        value["remote_receiver_observation"] = remote_receiver_observation(&host, state)
            .unwrap_or_else(|_| {
                json!({"$schema":"rusty.quest.embedded_duplex.remote_receiver_observation.v1",
                "scope":"peer_receiver_only","status":"pending","physical_cleanup":"pending",
                "pending_reason":"current_signed_receiver_history_unavailable"})
            });
        // The optional projection must not make an otherwise supported fixed
        // lifecycle receipt exceed its existing 256 KiB serializer boundary.
        if value.to_string().len() > 256 * 1024 {
            value
                .as_object_mut()
                .ok_or("Peer lifecycle object absent")?
                .remove("remote_receiver_observation");
        }
    }
    host.capability.require_live()?;
    if word == 6
        && value.get("peer_physical_cleanup").and_then(Value::as_str) == Some("terminal")
        && actual_java_cleanup(env)?
    {
        return close_host(value, host, checked);
    }
    Ok(value.to_string())
}

/// Continue only host retirement for the already selected whole-close. This
/// never re-enters Stop, route termination, owner dispatch or projection stop.
fn require_retained_whole_close_binding(
    expected: &str,
    actual: &str,
    executor: i64,
    app: i64,
    actual_executor: u64,
    actual_app: u64,
) -> Result<(), String> {
    if executor <= 0
        || app <= 0
        || actual != expected
        || actual_executor != executor as u64
        || actual_app != app as u64
    {
        return Err("retained whole-close incarnation differs".into());
    }
    Ok(())
}

fn complete_retained_whole_close(
    env: &mut JNIEnv<'_>,
    expected: &str,
    executor: i64,
    app: i64,
) -> Result<Option<String>, String> {
    let host = host_for_retained_whole_cleanup()?;
    host.capability.require_live()?;
    require_retained_whole_close_binding(
        expected,
        &host.config_sha256,
        executor,
        app,
        host.capability.generation,
        host.capability.binding.generation,
    )?;
    let mut checked = Checkout::take(host.peer_lifecycle.clone())?;
    let state = checked.get();
    if !state.whole_close_requested || state.client.is_none() {
        return Err("retained whole-close request absent".into());
    }
    let value = receipt(&host, state, "whole_app_close")?;
    if value.get("peer_physical_cleanup").and_then(Value::as_str) != Some("terminal")
        || state.last_failure.is_some()
    {
        return Err("retained Peer cleanup is not terminal".into());
    }
    if !actual_java_cleanup(env)? {
        return Ok(None);
    }
    close_host(value, host, checked).map(Some)
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_completeRetainedWholeClose(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    expected: JString<'_>,
    executor: jni::sys::jlong,
    app: jni::sys::jlong,
) -> jstring {
    let result = read_string(&mut env, &expected, 64)
        .and_then(|expected| complete_retained_whole_close(&mut env, &expected, executor, app));
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
    match result {
        Ok(None) => std::ptr::null_mut(),
        Ok(Some(receipt)) => return_string(&mut env, Ok(receipt)),
        Err(reason) => return_string(&mut env, Err(reason)),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_peerLifecycle(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    word: jni::sys::jint,
) -> jstring {
    let result = operate(&mut env, word);
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
    return_string(&mut env, result)
}

// Fixed observation: retain a host lease and validate liveness, without any lifecycle operation.
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_ownerFailureDiagnosticRead(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
) -> jstring {
    let result = (|| {
        if let Some(failure) = super::initialization_failure_read()? {
            return Ok(failure.to_string());
        }
        let host = host()?;
        host.capability.require_live()?;
        let mut value = host.callbacks.owner_failure_diagnostic_read();
        host.capability.require_live()?;
        value["native_executor_generation"] = json!(host.capability.generation);
        value["app_process_generation"] = json!(host.capability.binding.generation);
        Ok(value.to_string())
    })();
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
    return_string(&mut env, result)
}

// Compact evidence cursor only. The actual app-owned immutable archive and
// Original journal own prior bodies; this creates no native restore authority.
#[derive(Default, Serialize)]
struct RetiredLifetimes {
    total: u64,
    first: Option<Value>,
    last: Option<Value>,
    recent: Vec<Value>,
    history_sha256: Option<String>,
}

fn archive_completed_lifetime(host: &Host, state: &State) -> Result<RetiredLifetimes, String> {
    if state.termination_action != Some("stop")
        || state.route_cleanup.is_none()
        || media_stop_effect(state).is_none_or(Value::is_null)
    {
        return Err("native lifetime has no earned normal Stop".into());
    }
    let original = state
        .terminal_stop_original_id
        .as_deref()
        .ok_or("actual prior Stop Original absent")?;
    let projection = state
        .terminal_stop_projection
        .as_ref()
        .ok_or("original terminal Stop projection absent")?;
    if projection.get("status").and_then(Value::as_str) != Some("terminal") {
        return Err("original Stop projection not terminal".into());
    }
    let mut native = serde_json::to_value(state).map_err(safe_decode)?;
    native
        .as_object_mut()
        .ok_or("native lifetime metadata object")?
        .remove("retired_lifetimes");
    let body = json!({"$schema":"rusty.quest.embedded_duplex.peer_lifetime_archive.v1",
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
        "prior_stop_original_id":original,"terminal_native_stop_projection":projection,"native_lifetime_state":native,
        "prior_archive_projection":state.retired_lifetimes});
    let raw = body.to_string();
    let digest = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(&raw)
    );
    let ack = host.callbacks.archive_completed_peer_lifetime(&raw)?;
    const FIELDS: [&str; 8] = [
        "$schema",
        "body_sha256",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
        "prior_stop_original_id",
        "storage_identity",
    ];
    if !ack
        .as_object()
        .is_some_and(|o| o.len() == FIELDS.len() && FIELDS.iter().all(|key| o.contains_key(*key)))
        || ack.get("$schema").and_then(Value::as_str)
            != Some("rusty.quest.embedded_duplex.peer_lifetime_archive_retained.v1")
        || ack.get("body_sha256").and_then(Value::as_str) != Some(digest.as_str())
        || ack.get("app_process_generation").and_then(Value::as_u64)
            != Some(host.capability.binding.generation)
        || ack.get("app_record_sha256").and_then(Value::as_str)
            != Some(host.capability.binding.record_sha256.as_str())
        || ack
            .get("native_executor_generation")
            .and_then(Value::as_u64)
            != Some(host.capability.generation)
        || ack.get("config_sha256").and_then(Value::as_str) != Some(host.config_sha256.as_str())
        || ack.get("prior_stop_original_id").and_then(Value::as_str) != Some(original)
        || !ack
            .get("storage_identity")
            .and_then(Value::as_str)
            .is_some_and(|s| !s.is_empty())
    {
        return Err("actual prior lifetime durable custody ACK differs".into());
    }
    host.capability.require_live()?;
    let old = &state.retired_lifetimes;
    let chain=format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&json!({
        "$schema":"rusty.quest.embedded_duplex.peer_lifetime_archive_chain.v1","previous":old.history_sha256,"archive":ack}).to_string()));
    let mut recent = old.recent.clone();
    recent.push(ack.clone());
    if recent.len() > 2 {
        recent.remove(0);
    }
    Ok(RetiredLifetimes {
        total: old
            .total
            .checked_add(1)
            .ok_or("native lifetime count exhausted")?,
        first: old.first.clone().or_else(|| Some(ack.clone())),
        last: Some(ack),
        recent,
        history_sha256: Some(chain),
    })
}

// This formatter has no selection authority. receipt() reaches it only after
// the actual shared-owner fresh policy and unchanged ongoing-v2 validation.
fn append_fresh_lifecycle_fields(value: &mut Value, state: &State) -> Result<(), String> {
    if value.get("$schema").and_then(Value::as_str)
        != Some("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3")
    {
        return Err("fresh lifecycle projection requires actual v3 base".into());
    }
    value["$schema"] = json!("rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4");
    value["last_failure_cause"] = json!(state.last_failure_cause);
    value["retired_lifetimes"] = json!(state.retired_lifetimes);
    Ok(())
}

#[cfg(test)]
mod fresh_lifecycle_controls {
    use super::*;
    #[test]
    fn real_renewal_result_consumption_retains_pending_typed_cause_without_exception() {
        let receipt = json!({"$schema":"rusty.manifold.peer.session_current_receipt.v2","session_id":"session.fixture",
            "current":false,"rejection_reason":"expired","decision_id":null,"peer_ids":[],
            "topology_contract_id":null,"validated_at_ms":0,"expires_at_ms":null});
        let error = super::super::operation_failure::session_not_current(
            "renew_authority",
            "session.fixture",
            receipt.clone(),
        )
        .unwrap();
        let mut state = State::default();
        state.renewal_pending = true;
        let result = capture_pair_renewal_result(&mut state, Err(error));
        assert!(result.is_err());
        state.last_failure = result.err();
        state.last_failure_action = Some("renew_authority");
        assert!(state.renewal_pending);
        let mut base = json!({"$schema":"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3",
            "status":"pending","renewal_pending":state.renewal_pending,"last_failure":state.last_failure});
        append_fresh_lifecycle_fields(&mut base, &state).unwrap();
        assert_eq!(base["status"], "pending");
        assert_eq!(base["last_failure_cause"]["current_session"], receipt);
        assert_eq!(base["last_failure_cause"]["observed_at_ms"], 0);
        capture_pair_renewal_result(&mut state, Err("expired".into())).unwrap_err();
        assert!(state.last_failure_cause.is_none());
    }
    #[test]
    fn formatter_requires_explicit_v3_base_and_never_relabels_finite_receipts() {
        for schema in [
            "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1",
            "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v2",
            "foreign",
        ] {
            let mut receipt = json!({"$schema":schema});
            let original = receipt.clone();
            assert!(append_fresh_lifecycle_fields(&mut receipt, &State::default()).is_err());
            assert_eq!(receipt, original);
        }
    }
}

// Read-only current route join. Safe while the caller holds the single Peer
// lifecycle checkout: this queries authority only and never re-checks out State.
pub(super) fn require_owned_renewal_route(host: &Host) -> Result<(), String> {
    let actual = projection(host)?;
    if actual
        .get("route_configuration_sha256")
        .and_then(Value::as_str)
        != Some(host.route_configuration_sha256.as_str())
    {
        return Err("ongoing renewal actual route source differs".into());
    }
    Ok(())
}

/// Actual immutable normal-Stop outcome retained by the native lifetime owner.
/// Hold this local metadata slot only during the final in-memory phase handoff.
/// No Java, authority call, network operation or wait runs under the returned guard.
pub(super) fn completed_renewal_archive_guard<'a>(
    host: &'a Host,
    proof: &Value,
) -> Result<(std::sync::MutexGuard<'a, Option<State>>, Value), String> {
    let slot = host
        .peer_lifecycle
        .try_lock()
        .map_err(|_| "renewal archive lifetime busy")?;
    let s = slot
        .as_ref()
        .ok_or("renewal archive lifetime checked out")?;
    if s.renewal_pending
        || s.whole_close_requested
        || s.last_failure.is_some()
        || s.termination_action != Some("stop")
        || s.route_cleanup.is_none()
        || media_stop_effect(s).is_none_or(Value::is_null)
    {
        return Err("renewal archive requires earned normal Stop without pending effects".into());
    }
    let original = s
        .terminal_stop_original_id
        .as_ref()
        .ok_or("renewal archive Stop Original absent")?;
    let terminal = s
        .terminal_stop_projection
        .as_ref()
        .ok_or("renewal archive terminal projection absent")?;
    let index = s
        .renewed_session_proofs
        .iter()
        .position(|r| r == proof)
        .ok_or("renewal archive actual local provider proof absent")?;
    let joined = s
        .renewals
        .get(index)
        .ok_or("renewal archive provider receipt absent")?;
    if index + 1 != s.renewed_session_proofs.len()
        || s.renewed_session_proofs.len() != s.renewals.len()
        || joined.get("accepted_pair") != Some(proof)
        || terminal.get("status").and_then(Value::as_str) != Some("terminal")
        || terminal.get("termination_action").and_then(Value::as_str) != Some("stop")
        || terminal.get("renewal_pending").and_then(Value::as_bool) != Some(false)
        || terminal.get("route_cleanup") != s.route_cleanup.as_ref()
        || terminal.get("media_stop_effect_receipt") != media_stop_effect(s)
        || terminal.get("renewal_last_request_id") != joined.get("request_id")
    {
        return Err("renewal archive provider/terminal lineage differs".into());
    }
    let custody = json!({"terminal_lifetime":{"kind":"normal_peer_stop","original_id":original,"receipt":terminal},
        "accepted_local_session_renewal":proof,"provider_renewal":joined,
        "renewal_count":s.renewal_count,"current_lifetime_renewal_count":s.current_lifetime_renewal_count});
    Ok((slot, custody))
}

/// Actual immutable normal-Stop outcome retained by the native lifetime owner.
pub(super) fn prepared_terminal_lifetime(host: &Host) -> Result<Value, String> {
    let slot = host
        .peer_lifecycle
        .try_lock()
        .map_err(|_| "prepared lifetime owner busy")?;
    let state = slot.as_ref().ok_or("prepared lifetime owner checked out")?;
    if state.termination_action != Some("stop")
        || state.route_cleanup.is_none()
        || media_stop_effect(state).is_none_or(Value::is_null)
    {
        return Err("prepared lifetime lacks actual normal Stop".into());
    }
    let original = state
        .terminal_stop_original_id
        .as_ref()
        .ok_or("prepared Stop actual Original absent")?;
    let receipt = state
        .terminal_stop_projection
        .as_ref()
        .ok_or("prepared Stop immutable projection absent")?;
    if receipt["status"] != "terminal" {
        return Err("prepared Stop not terminal".into());
    }
    Ok(json!({"kind":"normal_peer_stop","original_id":original,"receipt":receipt}))
}

/// Exact Native history input for the M source-bound permit. This is derived
/// privately while root-flight excludes new local and incoming owner dispatch.
pub(super) fn root_lifetime_witness(
    host: &Host,
) -> Result<
    (
        rusty_manifold_model::ManifoldSourceBoundPeerDisposition,
        u64,
        Option<String>,
        Option<String>,
        Value,
    ),
    String,
> {
    let facts = fresh_setup_eligibility(host)?;
    let slot = host
        .peer_lifecycle
        .try_lock()
        .map_err(|_| "root lifecycle busy")?;
    let s = slot.as_ref().ok_or("root lifecycle checked out")?;
    match facts.pointer("/local/state").and_then(Value::as_str) {
        Some("never_started") => Ok((
            rusty_manifold_model::ManifoldSourceBoundPeerDisposition::NeverStarted,
            0,
            None,
            None,
            facts,
        )),
        Some("fully_stopped") => {
            let start = s
                .start_mutation
                .as_ref()
                .ok_or("root genuine Start absent")?;
            let stop = s
                .terminal_stop_projection
                .as_ref()
                .ok_or("root original terminal Stop absent")?;
            if s.terminal_stop_original_id.is_none()
                || stop.get("status").and_then(Value::as_str) != Some("terminal")
            {
                return Err("root original Stop not terminal".into());
            }
            Ok((
                rusty_manifold_model::ManifoldSourceBoundPeerDisposition::FullyStopped,
                s.retired_lifetimes
                    .total
                    .checked_add(1)
                    .ok_or("root lifetime ordinal overflow")?,
                Some(super::peer_roots::digest(
                    "rusty.quest.embedded_duplex.root_prior_start.v1",
                    start,
                )?),
                Some(super::peer_roots::digest(
                    "rusty.quest.embedded_duplex.root_terminal_stop.v1",
                    stop,
                )?),
                facts,
            ))
        }
        _ => Err("root lifecycle is neither NeverStarted nor FullyStopped".into()),
    }
}
