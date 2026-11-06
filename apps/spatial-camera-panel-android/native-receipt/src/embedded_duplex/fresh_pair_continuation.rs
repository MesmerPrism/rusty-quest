//! RQFS2: a new wrapper effect continues the SAME native setup custody using a
//! distinct Manifold transaction. No old context/outcome/receipt is rewritten.
use super::*;

const BINDING_SCHEMA: &str = "rusty.quest.embedded_duplex.fresh_setup_continuation_binding.v1";
const OBSERVATION_SCHEMA: &str = "rusty.quest.embedded_duplex.fresh_setup_phase_observation.v1";
const TICKET_SCHEMA: &str = "rusty.quest.embedded_duplex.fresh_setup_phase_ticket.v1";

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Binding {
    #[serde(rename = "$schema")]
    schema: String,
    prior_original_id: String,
    prior_outcome_sha256: String,
    native_request_id: String,
    prior_context_sha256: Option<String>,
    current_original_id: String,
    app_process_generation: u64,
    app_record_sha256: String,
    native_executor_generation: u64,
    config_sha256: String,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Observation {
    #[serde(rename = "$schema")]
    schema: String,
    native_request_id: String,
    phase_request_id: String,
    context_sha256: Option<String>,
    receipt: Option<ManifoldFreshPairSetupReceipt>,
    view: ManifoldFreshPairSetupPeerView,
    eligibility: Value,
    phase_fenced: bool,
    pending_ticket_sha256: Option<String>,
    actual_original_binding: Value,
    reservation_sha256: String,
    prime_return: Option<Value>,
    observed_at_ms: u64,
    stop_sequence: u64,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct PhaseTicket {
    #[serde(rename = "$schema")]
    schema: String,
    native_request_id: String,
    prior_phase_id: String,
    phase_request_id: String,
    session_id: String,
    nonce_a: String,
    local_observation: Observation,
    remote_observation: Observation,
    current_original_binding: Value,
    issued_at_ms: u64,
}

fn digest<T: Serialize>(domain: &str, value: &T) -> Result<String, String> {
    fresh_pair_setup_digest(domain, value).map_err(str::to_owned)
}
pub(super) fn ticket_digest(ticket: &PhaseTicket) -> Result<String, String> {
    digest(TICKET_SCHEMA, ticket)
}
fn canonical(value: &str) -> Result<(), String> {
    if matches!(
        ceremony_id::parse(value)?,
        ceremony_id::CeremonyId::Finite { .. }
    ) {
        Ok(())
    } else {
        Err("continuation finite phase identity".into())
    }
}
fn exact_binding(host: &Host) -> Result<Binding, String> {
    let value: Binding = serde_json::from_value(host.callbacks.fresh_setup_continuation_binding()?)
        .map_err(safe_decode)?;
    if value.schema != BINDING_SCHEMA
        || value.prior_original_id.len() != 32
        || value.current_original_id.len() != 32
        || value.prior_original_id == value.current_original_id
        || ![&value.prior_original_id, &value.current_original_id]
            .iter()
            .all(|id| {
                id.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            })
        || decode_hex::<32>(&value.prior_outcome_sha256).is_err()
        || value.app_process_generation != host.capability.binding.generation
        || value.app_record_sha256 != host.capability.binding.record_sha256
        || value.native_executor_generation != host.capability.generation
        || value.config_sha256 != host.config_sha256
    {
        return Err("actual continuation predecessor/actor binding differs".into());
    }
    canonical(&value.native_request_id)?;
    if value.prior_context_sha256.as_ref().is_some_and(|hash| {
        !hash
            .strip_prefix("sha256:")
            .is_some_and(|h| decode_hex::<32>(h).is_ok())
    }) {
        return Err("actual continuation predecessor context digest".into());
    }
    Ok(value)
}

// Only the actual new Current Original may supersede the old scoped Stop
// admission. A new Stop racing this callback changes the sequence and refuses;
// later Stops still fence every next actual dispatch through phase().
fn admit_local(host: &Host, binding: &Binding) -> Result<(), String> {
    let stop_sequence = {
        let p = process()
            .lock()
            .map_err(|_| "continuation process gate poisoned")?;
        if p.closing
            || p.initializing
            || p.lease_integrity_failed
            || p.retired_fresh_owner_fenced
            || p.host_leases != 1
            || !p
                .host
                .as_ref()
                .is_some_and(|h| Arc::ptr_eq(&h.pair_state, &host.pair_state))
            || p.fresh_setup_request
                .as_ref()
                .is_some_and(|id| id != &binding.native_request_id)
        {
            return Err("continuation actual native owner busy or retired".into());
        }
        p.fresh_setup_stop_sequence
    };
    super::preparation::join_actual_prime_return(host, &binding.native_request_id)?;
    {
        let s = state(host)?;
        let r = s
            .fresh
            .reservation
            .as_ref()
            .ok_or("continuation retained reservation absent")?;
        if r.id != binding.native_request_id
            || r.original_binding.get("kind").and_then(Value::as_str) != Some("operator")
            || r.original_binding
                .get("original_id")
                .and_then(Value::as_str)
                != Some(binding.prior_original_id.as_str())
        {
            return Err("continuation actual retained predecessor differs".into());
        }
        if let Some(ticket) = s.fresh.pending_phase.as_ref() {
            if ticket
                .current_original_binding
                .get("original_id")
                .and_then(Value::as_str)
                != Some(binding.current_original_id.as_str())
            {
                return Err("pending asymmetric fence ticket cannot be rebound to a new wrapper; retain UNKNOWN for typed custody reconciliation or physical retirement".into());
            }
        }
        let a = s.fresh.active.as_ref().filter(|a| a.id == r.id);
        if r.prime_pending
            .as_ref()
            .is_some_and(|p| p.get("entered").and_then(Value::as_bool) == Some(true))
        {
            return Err("continuation prime writer lacks actual receipt; outcome unknown".into());
        }
        if let Some(expected) = &binding.prior_context_sha256 {
            let actual = a
                .and_then(|a| a.context.as_ref())
                .map(|c| digest("rusty.manifold.peer.fresh_pair_setup_context.v3", c))
                .transpose()?;
            if actual.as_ref() != Some(expected) {
                return Err("continuation original context differs from retained phase".into());
            }
        }
    }
    // The callback checks the NEW actual Original desired-Stop before binding.
    host.callbacks
        .bind_fresh_setup_original(&binding.native_request_id)?;
    let mut p = process()
        .lock()
        .map_err(|_| "continuation process gate poisoned")?;
    if p.closing
        || p.initializing
        || p.lease_integrity_failed
        || p.host_leases != 1
        || p.fresh_setup_stop_sequence != stop_sequence
    {
        return Err("continuation admission changed or new desired Stop arrived".into());
    }
    p.fresh_setup_request = Some(binding.native_request_id.clone());
    p.fresh_setup_stop_request = None;
    let mut s = state(host)?;
    let r = s
        .fresh
        .reservation
        .as_mut()
        .ok_or("continuation reservation changed")?;
    // The old wrapper itself remains immutable in the app-owned journal. This
    // native custody pointer moves only after its exact producer binding joins.
    r.original_binding = json!({"kind":"operator","original_id":binding.current_original_id});
    s.fresh.continuation_selected = true;
    Ok(())
}

fn observe(host: &Host, id: &str) -> Result<Observation, String> {
    host.capability.require_live()?;
    let (attempt, reservation, fence) = {
        let s = state(host)?;
        (
            s.fresh.active.clone(),
            s.fresh.reservation.clone(),
            s.fresh.reservation_fence.clone(),
        )
    };
    let r = reservation
        .filter(|r| r.id == id)
        .ok_or("actual phase reservation absent")?;
    let a = attempt.filter(|a| a.id == id);
    let prime_return = super::preparation::actual_prime_return(host, &r)?;
    let native = peer_lifecycle::fresh_setup_eligibility(host)?;
    let physical_phase = a
        .as_ref()
        .map(|a| a.phase_id.as_str())
        .unwrap_or(r.id.as_str());
    let java = physical_observation(host, id, physical_phase, IncomingPhysicalScope::Registered)?;
    let eligibility = validate_physical(host, native, java)?;
    let now = host.clock.now_ms()?;
    let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
    let reservation_sha256 = digest(
        "rusty.quest.embedded_duplex.fresh_reservation_preimage.v1",
        &reservation_authority_preimage(&r),
    )?;
    let phase_id = a
        .as_ref()
        .map_or_else(|| r.id.clone(), |a| a.phase_id.clone());
    let receipt = if let Some(a) = a.as_ref().filter(|a| !a.transaction_id.is_empty()) {
        host.authority.current_fresh_pair_setup_receipt(
            &serde_json::from_value(json!(a.transaction_id)).map_err(safe_decode)?,
        )?
    } else {
        None
    };
    if let Some(actual) = &receipt {
        rusty_manifold_peer_runtime_host::validate_fresh_pair_setup_receipt_with_lifetime_policy(
            actual,
            host.development_lifetime_policy.as_ref(),
        )
        .map_err(|_| "actual retained phase receipt invalid")?;
        let a = a
            .as_ref()
            .ok_or("actual phase receipt lacks native Attempt")?;
        if actual.request_id.as_str() != a.transaction_id
            || actual.local_peer_id.as_str() != host.local_peer_id
            || a.context
                .as_ref()
                .is_some_and(|context| context != &actual.request.context)
        {
            return Err("actual retained phase receipt differs".into());
        }
    }
    let (operation, physical) = if let Some(a) = a.as_ref() {
        (
            a.view.native_operation_sha256.clone(),
            a.view.native_eligibility_sha256.clone(),
        )
    } else {
        // A failed pre-Attempt path is represented by the actual reservation,
        // never by an invented old view/receipt or a reset initial ceremony.
        let current: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
            serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
        if current.fresh_pair_setup.as_ref().is_some_and(|s| {
            s.receipts
                .iter()
                .any(|receipt| receipt.request.context.native_setup_request_id.as_str() == id)
        }) {
            return Err("actual committed setup without Native Attempt requires exact receipt reconciliation".into());
        }
        (
            digest(
                "rusty.quest.fresh_setup.reservation_operation.v1",
                &json!({"native_request_id":id,"reservation_sha256":reservation_sha256,"prime_return":prime_return}),
            )?,
            digest(
                "rusty.quest.fresh_setup.native_eligibility.v1",
                &eligibility,
            )?,
        )
    };
    let view =
        host.authority
            .fresh_pair_setup_view(&local, &peers(host)?, &operation, &physical, now)?;
    let pending = state(host)?
        .fresh
        .pending_phase
        .as_ref()
        .map(ticket_digest)
        .transpose()?;
    let sequence = process()
        .lock()
        .map_err(|_| "phase query process gate poisoned")?
        .fresh_setup_stop_sequence;
    Ok(Observation {
        schema: OBSERVATION_SCHEMA.into(),
        native_request_id: id.into(),
        phase_request_id: phase_id,
        context_sha256: a
            .as_ref()
            .and_then(|a| a.context.as_ref())
            .map(|c| digest("rusty.manifold.peer.fresh_pair_setup_context.v3", c))
            .transpose()?,
        receipt,
        view,
        eligibility,
        phase_fenced: a.as_ref().is_some_and(|a| a.phase_fenced) || fence.is_some(),
        pending_ticket_sha256: pending,
        actual_original_binding: r.original_binding,
        reservation_sha256,
        prime_return,
        observed_at_ms: now,
        stop_sequence: sequence,
    })
}

fn validate_observation(
    host: &Host,
    id: &str,
    value: &Observation,
    peer: &str,
) -> Result<(), String> {
    if value.schema != OBSERVATION_SCHEMA
        || value.native_request_id != id
        || value.view.local_peer_id.as_str() != peer
        || value.observed_at_ms > host.clock.now_ms()?
        || value.view.observed_at_ms != value.observed_at_ms
    {
        return Err("signed current phase observation binding differs".into());
    }
    canonical(&value.phase_request_id)?;
    if let Some(receipt) = &value.receipt {
        rusty_manifold_peer_runtime_host::validate_fresh_pair_setup_receipt_with_lifetime_policy(
            receipt,
            host.development_lifetime_policy.as_ref(),
        )
        .map_err(|_| "signed retained phase receipt invalid")?;
        if receipt.request.context.native_phase_id.as_str() != value.phase_request_id
            || receipt.request.context.native_setup_request_id.as_str() != value.native_request_id
            || receipt.local_peer_id.as_str() != peer
        {
            return Err("signed retained phase receipt subject differs".into());
        }
    }
    Ok(())
}

fn query_peer(host: &Host, id: &str) -> Result<Option<Observation>, String> {
    let frame = encode_readonly(host, "phase_query", id, json!({}))?;
    let reply = host.callbacks.clone().exchange(&frame, 128 * 1024)?;
    let body = decode(host, &reply)?;
    if body.request_id != id || body.kind != "phase_queried" {
        return Err("phase query actual reply differs".into());
    }
    let observation = &body.payload["observation"];
    if observation["$schema"] == "rusty.quest.embedded_duplex.fresh_prime_required_observation.v1" {
        let now = host.clock.now_ms()?;
        const FIELDS: [&str; 5] = [
            "$schema",
            "native_request_id",
            "local_peer_id",
            "source_raw_sha256",
            "observed_at_ms",
        ];
        if !observation
            .as_object()
            .is_some_and(|o| o.len() == FIELDS.len() && FIELDS.iter().all(|k| o.contains_key(*k)))
            || observation["native_request_id"] != id
            || observation["local_peer_id"] != host.remote_peer_id
            || !observation["source_raw_sha256"].as_str().is_some_and(|h| {
                h.strip_prefix("sha256:")
                    .is_some_and(|h| decode_hex::<32>(h).is_ok())
            })
            || !observation["observed_at_ms"]
                .as_u64()
                .is_some_and(|t| t <= now)
        {
            return Err("signed peer preparation-required observation differs".into());
        }
        return Ok(None); // Branch selection only; no mutation/absence authority.
    }
    let value: Observation = serde_json::from_value(observation.clone()).map_err(safe_decode)?;
    validate_observation(host, id, &value, &host.remote_peer_id)?;
    if body.phase_request_id.as_deref() != Some(value.phase_request_id.as_str()) {
        return Err("phase query wire/owner phase differs".into());
    }
    Ok(Some(value))
}

pub(super) fn run(host: &Host) -> Result<String, String> {
    let _flight = flight(host)?;
    if host.local_peer_id >= host.remote_peer_id {
        return Err("continuation initiator is lower actual packaged peer".into());
    }
    let binding = exact_binding(host)?;
    let id = &binding.native_request_id;
    admit_local(host, &binding)?;
    let result = (|| -> Result<String, String> {
        if state(host)?.fresh.active.is_none() && super::preparation::requires_enrollment(host)? {
            return super::preparation::run(host, id, &binding.current_original_id);
        }
        let local = observe(host, id)?;
        let Some(remote) = query_peer(host, id)? else {
            return super::preparation::run(host, id, &binding.current_original_id);
        };
        let pending = state(host)?.fresh.pending_phase.clone();
        let ticket = if let Some(ticket) = pending {
            // A prior fence RPC can only be continued using its exact retained
            // ticket. A different remote phase/ticket stays UNKNOWN.
            if ticket.native_request_id != *id
                || !(remote.phase_request_id == ticket.prior_phase_id
                    || remote.phase_request_id == ticket.phase_request_id
                        && remote.pending_ticket_sha256.as_ref() == Some(&ticket_digest(&ticket)?))
            {
                return Err("remote phase cannot be joined to retained fence ticket".into());
            }
            ticket
        } else {
            if local.phase_request_id != remote.phase_request_id {
                return Err("asymmetric phase without retained ticket; unknown".into());
            }
            let ticket = PhaseTicket {
                schema: TICKET_SCHEMA.into(),
                native_request_id: id.clone(),
                prior_phase_id: local.phase_request_id.clone(),
                phase_request_id: ceremony_id::finite(&random_hex()?)?,
                session_id: format!("session.duplex.{}", random_hex()?),
                nonce_a: random_hex()?,
                local_observation: local,
                remote_observation: remote,
                current_original_binding: json!({"kind":"operator","original_id":binding.current_original_id}),
                issued_at_ms: host.clock.now_ms()?,
            };
            state(host)?.fresh.pending_phase = Some(ticket.clone());
            ticket
        };
        let frame = encode(host, "phase_fence", id, json!({"ticket":ticket}))?;
        let reply = host.callbacks.clone().exchange(&frame, 128 * 1024)?;
        let body = decode(host, &reply)?;
        if body.kind != "phase_fenced"
            || body.request_id != *id
            || body.phase_request_id.as_deref() != Some(ticket.phase_request_id.as_str())
            || body.payload["ticket_sha256"].as_str() != Some(ticket_digest(&ticket)?.as_str())
        {
            return Err("actual peer phase fence reply differs".into());
        }
        let remote_view: ManifoldFreshPairSetupPeerView =
            serde_json::from_value(body.payload["view"].clone()).map_err(safe_decode)?;
        let nonce_b = body.payload["nonce_b"]
            .as_str()
            .ok_or("phase fence peer nonce absent")?
            .to_owned();
        decode_hex::<32>(&nonce_b)?;
        let peer_ack = body.payload["archive_ack"].clone();
        let local_view = adopt(
            host,
            &ticket,
            Some(&body.payload),
            &binding.current_original_id,
        )?;
        let mut views = vec![local_view, remote_view];
        views.sort_by(|a, b| a.local_peer_id.cmp(&b.local_peer_id));
        let policy = host
            .authority
            .selected_fresh_pair_setup_policy()?
            .ok_or("actual continuation policy absent")?;
        let now = host.clock.now_ms()?;
        let mut context:ManifoldFreshPairSetupContext=serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_pair_setup_context.v3",
            "request_id":ticket.phase_request_id,"native_setup_request_id":id,"native_phase_id":ticket.phase_request_id,"policy":policy,"peer_ids":peers(host)?,"views":views,"issued_at_ms":now})).map_err(safe_decode)?;
        context.request_id = rusty_manifold_peer::fresh_pair_setup_transaction_id(&context)?;
        update(host, id, |a| {
            a.transaction_id = context.request_id.to_string();
            a.nonce_b = Some(nonce_b.clone());
            a.context = Some(context.clone());
        })?;
        // Explicit current-source handoff is not an old outcome rewrite. App
        // metadata may compact only after its own consumer ACK + real joins.
        handoff(host, &binding, &ticket, peer_ack)?;
        finish_phase(
            host,
            id,
            &ticket.session_id,
            &ticket.nonce_a,
            &nonce_b,
            &context,
        )
    })();
    if let Err(error) = &result {
        record_failure(host, id, error);
    }
    result.map_err(|error| {
        if let Some(cause) = super::super::super::operation_failure::current_cause(&error) {
            if cause.get("operation").and_then(Value::as_str) == Some("fresh_pair_setup") {
                if let (Some(session), Some(receipt)) = (
                    cause.get("session_id").and_then(Value::as_str),
                    cause.get("current_session"),
                ) {
                    return super::super::super::operation_failure::session_not_current(
                        "fresh_pair_continue",
                        session,
                        receipt.clone(),
                    )
                    .unwrap_or(error);
                }
            }
        }
        error
    })
}

fn observation_preimages_equal(left: &Observation, right: &Observation) -> bool {
    // Signed observation times remain unchanged in the ticket. A later real
    // observation may have a later audit timestamp; it never refreshes a receipt.
    let mut a = left.view.clone();
    a.observed_at_ms = right.view.observed_at_ms;
    left.native_request_id == right.native_request_id
        && left.phase_request_id == right.phase_request_id
        && left.context_sha256 == right.context_sha256
        && left.receipt == right.receipt
        && a == right.view
        && left.eligibility == right.eligibility
        && left.stop_sequence == right.stop_sequence
        && left.reservation_sha256 == right.reservation_sha256
        && left.prime_return == right.prime_return
}

fn validate_ticket(host: &Host, ticket: &PhaseTicket) -> Result<(), String> {
    canonical(&ticket.native_request_id)?;
    canonical(&ticket.prior_phase_id)?;
    canonical(&ticket.phase_request_id)?;
    let suffix = ticket
        .session_id
        .strip_prefix("session.duplex.")
        .ok_or("continuation session namespace")?;
    decode_hex::<32>(suffix)?;
    decode_hex::<32>(&ticket.nonce_a)?;
    if ticket.schema != TICKET_SCHEMA
        || ticket.prior_phase_id == ticket.phase_request_id
        || ticket.issued_at_ms > host.clock.now_ms()?
        || ticket.local_observation.phase_request_id != ticket.prior_phase_id
        || ticket.remote_observation.phase_request_id != ticket.prior_phase_id
    {
        return Err("continuation signed ticket shape differs".into());
    }
    let (lower, higher) = if host.local_peer_id < host.remote_peer_id {
        (&host.local_peer_id, &host.remote_peer_id)
    } else {
        (&host.remote_peer_id, &host.local_peer_id)
    };
    validate_observation(
        host,
        &ticket.native_request_id,
        &ticket.local_observation,
        lower,
    )?;
    validate_observation(
        host,
        &ticket.native_request_id,
        &ticket.remote_observation,
        higher,
    )?;
    let original = ticket
        .current_original_binding
        .as_object()
        .ok_or("continuation initiating Original object")?;
    let id = original
        .get("original_id")
        .and_then(Value::as_str)
        .ok_or("continuation initiating Original absent")?;
    if original.len() != 2
        || original.get("kind").and_then(Value::as_str) != Some("operator")
        || id.len() != 32
        || !id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err("continuation initiating Original malformed".into());
    }
    Ok(())
}

// Build the exact immutable body once, before the fallible storage callback.
// Retry reads the retained bytes. It cannot relabel a changed snapshot as the
// original phase or overwrite a prior UNKNOWN wrapper outcome.
fn archive_fenced_phase(host: &Host, ticket: &PhaseTicket) -> Result<Value, String> {
    let (old, reservation_fence) = {
        let s = state(host)?;
        (s.fresh.active.clone(), s.fresh.reservation_fence.clone())
    };
    let proof = serde_json::to_value(ticket).map_err(safe_decode)?;
    if old.as_ref().is_some_and(|old| {
        old.phase_id != ticket.prior_phase_id
            || !old.phase_fenced
            || old.fence_proof.as_ref() != Some(&proof)
    }) || old.is_none() && reservation_fence.as_ref() != Some(&proof)
    {
        return Err("actual old phase fence absent before durable custody".into());
    }
    let cached = state(host)?.fresh.pending_archive.clone();
    let raw = if let Some((phase, raw)) = cached {
        if phase != ticket.prior_phase_id {
            return Err("another phase archive remains pending".into());
        }
        raw
    } else {
        let authority: Value =
            serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
        let prior = state(host)?.fresh.history.clone();
        let (native_reservation, preparation) = {
            let s = state(host)?;
            (s.fresh.reservation.clone(), s.fresh.preparation.clone())
        };
        let original = old
            .as_ref()
            .map(|a| a.original_binding.clone())
            .or_else(|| {
                native_reservation
                    .as_ref()
                    .map(|r| r.initial_original_binding.clone())
            })
            .ok_or("actual prior original binding custody absent")?;
        let body = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_phase_archive.v2",
            "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
            "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
            "native_request_id":ticket.native_request_id,"phase_request_id":ticket.prior_phase_id,"original_binding":original,
            "phase_status":if old.is_some(){"fenced_unknown"}else if preparation.is_some(){"preparation_fenced_unknown"}else{"reservation_fenced_unknown"},"phase_state":old,"authority_snapshot":authority,"prior_archive_projection":prior,
            "native_reservation":native_reservation,"preparation":preparation});
        let raw = body.to_string();
        state(host)?.fresh.pending_archive = Some((ticket.prior_phase_id.clone(), raw.clone()));
        raw
    };
    let body: Value = serde_json::from_str(&raw).map_err(safe_decode)?;
    let ack = host.callbacks.archive_fresh_setup_phase(&raw)?;
    let sha = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(&raw)
    );
    const KEYS: [&str; 10] = [
        "$schema",
        "body_sha256",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
        "native_request_id",
        "phase_request_id",
        "original_binding",
        "storage_identity",
    ];
    if !ack
        .as_object()
        .is_some_and(|o| o.len() == KEYS.len() && KEYS.iter().all(|k| o.contains_key(*k)))
        || ack["$schema"] != "rusty.quest.embedded_duplex.fresh_setup_phase_archive_retained.v2"
        || ack["body_sha256"] != sha
        || ![
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
            "native_request_id",
            "phase_request_id",
            "original_binding",
        ]
        .iter()
        .all(|k| ack.get(*k) == body.get(*k))
        || !ack["storage_identity"]
            .as_str()
            .is_some_and(|s| !s.is_empty())
    {
        return Err("continuation exact phase custody ACK differs".into());
    }
    // Old Native bodies remain held if the actual Manifold archive refuses.
    // This changes its exact source and must finish before the new view exists.
    if let Some(old) = old.as_ref() {
        if history_custody::archive_setup(host, old)?.is_none() {
            let preparation = state(host)?.fresh.preparation.clone();
            if let Some(preparation) = preparation.as_ref() {
                history_custody::archive_preparation(host, preparation)?;
            }
        }
    }
    Ok(ack)
}

fn adopt(
    host: &Host,
    ticket: &PhaseTicket,
    peer_fence: Option<&Value>,
    current_original: &str,
) -> Result<ManifoldFreshPairSetupPeerView, String> {
    validate_ticket(host, ticket)?;
    let id = &ticket.native_request_id;
    let old = state(host)?.fresh.active.clone();
    let hash = ticket_digest(ticket)?;
    if let Some(old) = old
        .as_ref()
        .filter(|a| a.phase_id == ticket.phase_request_id)
    {
        if old
            .linked_predecessor
            .as_ref()
            .and_then(|p| p["ticket_sha256"].as_str())
            != Some(hash.as_str())
        {
            return Err("continuation phase retry changed ticket".into());
        }
        return Ok(old.view.clone());
    }
    if old.as_ref().map_or(id.as_str(), |a| a.phase_id.as_str()) != ticket.prior_phase_id {
        return Err("actual old phase differs from signed ticket".into());
    }
    let expected = if host.local_peer_id < host.remote_peer_id {
        &ticket.local_observation
    } else {
        &ticket.remote_observation
    };
    let retained_fence = if let Some(old) = old.as_ref() {
        old.fence_proof.clone()
    } else {
        state(host)?.fresh.reservation_fence.clone()
    };
    if retained_fence.is_none() {
        let actual = observe(host, id)?;
        if !observation_preimages_equal(&actual, expected) {
            return Err("actual source/receipt/Stop preimages changed before phase fence".into());
        }
        let mut p = process()
            .lock()
            .map_err(|_| "continuation fence process gate poisoned")?;
        if p.closing
            || p.initializing
            || p.lease_integrity_failed
            || p.host_leases != 1
            || p.fresh_setup_stop_sequence != expected.stop_sequence
        {
            return Err("continuation owner or Stop changed before fence".into());
        }
        p.fresh_setup_request = Some(id.clone());
        p.fresh_setup_stop_request = None;
        let mut s = state(host)?;
        let proof = serde_json::to_value(ticket).map_err(safe_decode)?;
        if let Some(a) = s.fresh.active.as_mut() {
            if a.id != *id || a.phase_id != ticket.prior_phase_id {
                return Err("continuation retained phase changed".into());
            }
            a.phase_fenced = true;
            a.fence_proof = Some(proof);
        } else {
            if !s.fresh.reservation.as_ref().is_some_and(|r| r.id == *id) {
                return Err("continuation actual reservation changed".into());
            }
            s.fresh.reservation_fence = Some(proof);
        }
        s.fresh.pending_phase = Some(ticket.clone());
    } else if retained_fence.as_ref() != Some(&serde_json::to_value(ticket).map_err(safe_decode)?) {
        return Err("old phase already fenced by another ticket".into());
    }
    if host.local_peer_id > host.remote_peer_id {
        // This new handler belongs to the NEW signed phase; all actual old
        // phase tasks must join before its archived predecessor may transfer.
        // Never bind this handler into the old group and then exclude it.
        host.callbacks.bind_incoming_fresh_setup_phase(
            id,
            Some(&ticket.prior_phase_id),
            &ticket.phase_request_id,
            "linked_fence",
        )?;
    }
    // All subsequent failures preserve the old fence and exact pending ticket.
    let ack = archive_fenced_phase(host, ticket)?;
    phase(host, id)?;
    let native = peer_lifecycle::fresh_setup_eligibility(host)?;
    let java = physical_observation(
        host,
        id,
        &ticket.phase_request_id,
        IncomingPhysicalScope::Registered,
    )?;
    let eligibility = validate_physical(host, native, java)?;
    let operation = digest(
        "rusty.quest.fresh_setup.native_operation.v2",
        &json!({"native_request_id":id,"phase_request_id":ticket.phase_request_id,
        "ticket_sha256":hash,"session_id":ticket.session_id,"nonce_a":ticket.nonce_a,"config_sha256":host.config_sha256,
        "native_generation":host.capability.generation}),
    )?;
    let eligibility_sha = digest(
        "rusty.quest.fresh_setup.native_eligibility.v1",
        &eligibility,
    )?;
    let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
    let view = host.authority.fresh_pair_setup_view(
        &local,
        &peers(host)?,
        &operation,
        &eligibility_sha,
        host.clock.now_ms()?,
    )?;
    let binding = if host.local_peer_id < host.remote_peer_id {
        if current_original.is_empty() {
            return Err("continuation current operator absent".into());
        }
        json!({"kind":"operator","original_id":current_original})
    } else {
        json!({"kind":"incoming_fresh_setup","native_request_id":id})
    };
    let prior_pair = if let Some(old) = old.as_ref() {
        old.prior_pair.clone()
    } else {
        status_without_fresh(host)?
    };
    let new = Attempt {
        id: id.clone(),
        session: ticket.session_id.clone(),
        phase_id: ticket.phase_request_id.clone(),
        transaction_id: String::new(),
        phase_fenced: false,
        fence_proof: None,
        linked_predecessor: Some(
            json!({"ticket_sha256":hash,"ticket":ticket,"local_archive":ack,"peer_fence":peer_fence}),
        ),
        nonce_a: ticket.nonce_a.clone(),
        nonce_b: None,
        eligibility,
        original_binding: binding,
        view: view.clone(),
        context: None,
        request: None,
        local_setup: None,
        remote_setup: None,
        prepared: None,
        initiator_context_expiry: None,
        remote_signing_context: None,
        local_current: None,
        remote_current: None,
        final_local_current: None,
        prior_pair,
        replies: Vec::new(),
        failure: None,
        completed: false,
    };
    let expected_proof = serde_json::to_value(ticket).map_err(safe_decode)?;
    let mut s = state(host)?;
    if s.fresh.active.as_ref().map_or_else(
        || s.fresh.reservation_fence.as_ref() != Some(&expected_proof),
        |a| !(a.id == *id && a.phase_id == ticket.prior_phase_id && a.phase_fenced),
    ) {
        return Err("fenced owner changed before adoption".into());
    }
    let prior = s.fresh.history.clone();
    let history = &mut s.fresh.history;
    history.history_sha256 = Some(digest(
        "rusty.quest.embedded_duplex.fresh_setup_phase_archive_chain.v1",
        &json!({"previous":prior.history_sha256,"archive":ack}),
    )?);
    history.total = history
        .total
        .checked_add(1)
        .ok_or("fresh archive count exhausted")?;
    if history.first.is_none() {
        history.first = Some(ack.clone());
    }
    history.last = Some(ack.clone());
    history.recent.push(ack);
    if history.recent.len() > 2 {
        history.recent.remove(0);
    }
    s.fresh.active = Some(new);
    s.fresh.reservation_fence = None;
    s.fresh.pending_archive = None;
    s.fresh.continuation_selected = true;
    // Keep this ticket until the bilateral fence response has been retained.
    s.fresh.pending_phase = Some(ticket.clone());
    Ok(view)
}

pub(super) fn handle(host: &Host, b: Body) -> Result<Vec<u8>, String> {
    if host.local_peer_id <= host.remote_peer_id {
        return Err("continuation receiver is higher packaged peer".into());
    }
    match b.kind.as_str() {
        "phase_query" => {
            if super::preparation::requires_enrollment(host)? {
                let actual = host.authority.snapshot_json()?;
                // This fixed current-owner observation reports a branch only.
                // The later explicit preparation effect rechecks all sources.
                return encode_query_reply(
                    host,
                    &b.request_id,
                    json!({"observation":{
                    "$schema":"rusty.quest.embedded_duplex.fresh_prime_required_observation.v1",
                    "native_request_id":b.request_id,"local_peer_id":host.local_peer_id,
                    "source_raw_sha256":format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&actual)),
                    "observed_at_ms":host.clock.now_ms()?}}),
                );
            }
            let observation = observe(host, &b.request_id)?;
            // Query observes current actual owner state. It never performs prime,
            // enrollment, setup apply, old-phase fencing, or adoption.
            encode_query_reply(host, &b.request_id, json!({"observation":observation}))
        }
        "phase_fence" => {
            let ticket: PhaseTicket =
                serde_json::from_value(b.payload["ticket"].clone()).map_err(safe_decode)?;
            if b.request_id != ticket.native_request_id
                || b.phase_request_id.as_deref() != Some(ticket.prior_phase_id.as_str())
            {
                return Err("continuation fence wire/ticket differs".into());
            }
            let old = state(host)?.fresh.active.clone();
            if let Some(old) = old
                .as_ref()
                .filter(|a| a.phase_id == ticket.phase_request_id)
            {
                let cached = old
                    .replies
                    .iter()
                    .find(|(kind, _, _, _)| kind == "phase_fence")
                    .ok_or("adopted phase fence response not retained")?;
                if cached.1 != b.payload {
                    return Err("continuation fence retry changed ticket".into());
                }
                host.callbacks.bind_incoming_fresh_setup_phase(
                    &b.request_id,
                    None,
                    &old.phase_id,
                    "current_phase",
                )?;
                incoming_handoff(host, &ticket, cached.3["archive_ack"].clone())?;
                return encode(host, "phase_fenced", &b.request_id, cached.3.clone());
            }
            let view = adopt(host, &ticket, None, "")?;
            let nonce = random_hex()?;
            decode_hex::<32>(&nonce)?;
            let a = active(host, &b.request_id)?;
            let ack = a
                .linked_predecessor
                .as_ref()
                .ok_or("adopted predecessor custody absent")?["local_archive"]
                .clone();
            let payload = json!({"ticket_sha256":ticket_digest(&ticket)?,"view":view,"nonce_b":nonce,"archive_ack":ack});
            update(host, &b.request_id, |a| {
                a.nonce_b = Some(nonce);
                a.replies.push((
                    "phase_fence".into(),
                    b.payload.clone(),
                    "phase_fenced".into(),
                    payload.clone(),
                ));
            })?;
            incoming_handoff(host, &ticket, payload["archive_ack"].clone())?;
            encode(host, "phase_fenced", &b.request_id, payload)
        }
        _ => Err("continuation request kind".into()),
    }
}

fn incoming_handoff(host: &Host, ticket: &PhaseTicket, local_archive: Value) -> Result<(), String> {
    phase(host, &ticket.native_request_id)?;
    let current = active(host, &ticket.native_request_id)?;
    if current.phase_id != ticket.phase_request_id || current.phase_fenced {
        return Err("incoming handoff lacks actual new-phase owner".into());
    }
    let body = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_incoming_handoff.v1",
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
        "native_request_id":ticket.native_request_id,"prior_phase_id":ticket.prior_phase_id,"phase_request_id":ticket.phase_request_id,
        "ticket_sha256":ticket_digest(ticket)?,"local_archive":local_archive});
    let raw = body.to_string();
    let ack = host.callbacks.complete_incoming_fresh_setup_handoff(&raw)?;
    const KEYS: [&str; 10] = [
        "$schema",
        "body_sha256",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
        "native_request_id",
        "prior_phase_id",
        "phase_request_id",
        "storage_identity",
    ];
    if !ack
        .as_object()
        .is_some_and(|o| o.len() == KEYS.len() && KEYS.iter().all(|k| o.contains_key(*k)))
        || ack["$schema"] != "rusty.quest.embedded_duplex.fresh_setup_incoming_handoff_retained.v1"
        || ack["body_sha256"]
            != format!(
                "sha256:{}",
                rusty_quest_broker_authority::packaged_json_sha256(&raw)
            )
        || ![
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
            "native_request_id",
            "prior_phase_id",
            "phase_request_id",
        ]
        .iter()
        .all(|k| ack.get(*k) == body.get(*k))
        || !ack["storage_identity"]
            .as_str()
            .is_some_and(|v| !v.is_empty())
    {
        return Err("incoming actual phase handoff ACK differs".into());
    }
    update(host, &ticket.native_request_id, |a| {
        if let Some(p) = a.linked_predecessor.as_mut() {
            p["incoming_handoff_ack"] = ack;
        }
    })?;
    Ok(())
}

fn encode_query_reply(host: &Host, id: &str, payload: Value) -> Result<Vec<u8>, String> {
    // An actual read-only owner query remains available under an old scoped Stop.
    // It signs no authority and changes no phase. New continuation admission/fence
    // still checks the exact monotonic Stop sequence and application intent.
    encode_readonly(host, "phase_queried", id, payload)
}

fn handoff(
    host: &Host,
    binding: &Binding,
    ticket: &PhaseTicket,
    peer_ack: Value,
) -> Result<(), String> {
    phase(host, &ticket.native_request_id)?;
    let a = active(host, &ticket.native_request_id)?;
    if a.phase_id != ticket.phase_request_id || a.phase_fenced {
        return Err("continuation handoff actual new phase absent".into());
    }
    let predecessor = a
        .linked_predecessor
        .as_ref()
        .ok_or("continuation handoff predecessor absent")?;
    let proof = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_continuation_handoff.v1",
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
        "native_request_id":ticket.native_request_id,"prior_phase_id":ticket.prior_phase_id,"phase_request_id":ticket.phase_request_id,
        "prior_original_id":binding.prior_original_id,"prior_outcome_sha256":binding.prior_outcome_sha256,
        "current_original_id":binding.current_original_id,"ticket_sha256":ticket_digest(ticket)?,
        "local_archive":predecessor["local_archive"],"peer_archive":peer_ack});
    let raw = proof.to_string();
    let ack = host
        .callbacks
        .complete_fresh_setup_continuation_handoff(&raw)?;
    const KEYS: [&str; 8] = [
        "$schema",
        "body_sha256",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
        "prior_original_id",
        "current_original_id",
    ];
    let expected = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(&raw)
    );
    if !ack
        .as_object()
        .is_some_and(|o| o.len() == KEYS.len() && KEYS.iter().all(|k| o.contains_key(*k)))
        || ack["$schema"]
            != "rusty.quest.embedded_duplex.fresh_setup_continuation_handoff_retained.v1"
        || ack["body_sha256"] != expected
        || ![
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
            "prior_original_id",
            "current_original_id",
        ]
        .iter()
        .all(|k| ack.get(*k) == proof.get(*k))
    {
        return Err("continuation current-owner handoff ACK differs".into());
    }
    update(host, &ticket.native_request_id, |a| {
        if let Some(value) = a.linked_predecessor.as_mut() {
            value["handoff_ack"] = ack;
        }
    })?;
    state(host)?.fresh.pending_phase = None;
    Ok(())
}

pub(super) fn predecessor_projection(a: &Attempt) -> Result<Option<Value>, String> {
    let Some(p) = a.linked_predecessor.as_ref() else {
        return Ok(None);
    };
    if p["kind"] == "enrollment_preparation" {
        // Deliberate typed branch: source-only completed prime has no invented
        // Manifold preparation receipt; its signed fresh context follows later.
        return Ok(Some(
            json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_preparation_predecessor.v1",
            "prior_phase_id":p["prior_phase_id"],"phase_request_id":a.phase_id,
            "preparation_receipt_sha256":if p["preparation_receipt"].is_null(){None}else{Some(fresh_pair_setup_digest("rusty.quest.embedded_duplex.preparation_receipt.v1",&p["preparation_receipt"])? )},
            "local_archive":p["local_archive"]}),
        ));
    }
    let t = &p["ticket"];
    Ok(Some(
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_phase_predecessor.v1",
        "prior_phase_id":t["prior_phase_id"],"phase_request_id":a.phase_id,"ticket_sha256":p["ticket_sha256"],
        "local_archive":p["local_archive"],"peer_archive":p.get("peer_fence").and_then(|v|v.get("archive_ack")),
        "handoff_ack":p.get("handoff_ack")}),
    ))
}
