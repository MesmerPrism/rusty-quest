//! Explicit RQFS2 preparation effect for the exact retained initial prime.
//! No query enters enrollment, and no missing receipt is invented from absence.
use super::*;
use rusty_manifold_peer::{
    fresh_phase_prepare_signing_bytes, verify_fresh_phase_prepare_request,
    ManifoldFreshPhasePrepareContext, ManifoldFreshPhasePrepareRequest,
    ManifoldFreshPhasePrepareView,
};
use rusty_manifold_peer_runtime_host::{
    ManifoldFreshPhasePrepareReceipt, ManifoldPeerRuntimeHostSnapshot,
};

#[derive(Clone, Serialize)]
pub(super) struct Preparation {
    pub phase_id: String,
    pub transaction_id: String,
    pub session_id: String,
    pub nonce_a: String,
    pub nonce_b: Option<String>,
    pub original_binding: Value,
    pub eligibility: Value,
    pub view: ManifoldFreshPhasePrepareView,
    pub context: Option<ManifoldFreshPhasePrepareContext>,
    pub request: Option<ManifoldFreshPhasePrepareRequest>,
    pub local_receipt: Option<ManifoldFreshPhasePrepareReceipt>,
    pub remote_receipt: Option<ManifoldFreshPhasePrepareReceipt>,
    pub prior_phase_archive: Option<Value>,
    pub prior_phase_id: String,
    pub archive_pending: Option<String>,
    pub apply_entered: bool,
    pub peer_view: Option<ManifoldFreshPairSetupPeerView>,
    pub failure: Option<String>,
    pub activated: bool,
    pub peer_preparation_view: Option<ManifoldFreshPhasePrepareView>,
    pub activation_ticket: Option<Value>,
    pub prior_reservation: Option<Reservation>,
    pub cached_replies: Vec<(String, Value, String, Value)>,
}
pub(super) fn projection(a: &Preparation) -> Result<Value, String> {
    let state = if a.activated {
        "activated"
    } else if a.apply_entered && a.local_receipt.is_none() {
        "entered_unknown"
    } else if a.local_receipt.is_some() && a.remote_receipt.is_some() {
        "prepared"
    } else {
        "pending"
    };
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_phase_preparation_status.v1","state":state,"phase_request_id":a.phase_id,
        "manifold_transaction_id":if a.transaction_id.is_empty(){None}else{Some(&a.transaction_id)},
        "context_sha256":a.context.as_ref().map(|c|fresh_pair_setup_digest("rusty.manifold.peer.fresh_phase_prepare_context.v2",c)).transpose()?,
        "local_receipt_sha256":a.local_receipt.as_ref().map(|r|fresh_pair_setup_digest("rusty.quest.embedded_duplex.preparation_receipt.v1",r)).transpose()?,
        "remote_receipt_sha256":a.remote_receipt.as_ref().map(|r|fresh_pair_setup_digest("rusty.quest.embedded_duplex.preparation_receipt.v1",r)).transpose()?,
        "archive":a.prior_phase_archive}),
    )
}

fn snapshot(host: &Host) -> Result<ManifoldPeerRuntimeHostSnapshot, String> {
    serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)
}
pub(super) fn requires_enrollment(host: &Host) -> Result<bool, String> {
    let snapshot = snapshot(host)?;
    let selection = host
        .authority
        .selected_fresh_setup_enrollment_selection()?
        .ok_or("actual enrollment selection absent")?;
    Ok(selection.keys.iter().any(|k| {
        !snapshot
            .enrollment
            .credentials
            .iter()
            .any(|c| c.peer_id == k.peer_id)
    }))
}

/// A query may read this actual writer return without adopting or clearing any
/// pending step. None is permitted only when no entered step exists.
pub(super) fn actual_prime_return(host: &Host, r: &Reservation) -> Result<Option<Value>, String> {
    let Some(pending) = r.prime_pending.as_ref().filter(|p| p["entered"] == true) else {
        return Ok(None);
    };
    if !pending["step"]
        .as_str()
        .is_some_and(|s| s.starts_with("enrollment:"))
    {
        return Err("entered non-enrollment step lacks owning receipt lookup".into());
    }
    let request: ManifoldPeerEnrollmentRequest =
        serde_json::from_value(pending["request"].clone()).map_err(safe_decode)?;
    let entry = host
        .authority
        .current_fresh_prime_enrollment_receipt(&request.request_id)?
        .ok_or("entered prime return remains UNKNOWN")?;
    if entry.request != request
        || !entry.receipt.applied
        || entry.source_before_sha256
            != pending["source_before_sha256"]
                .as_str()
                .ok_or("entered prime source preimage absent")?
    {
        return Err("actual prime return differs from retained entered request".into());
    }
    Ok(Some(serde_json::to_value(entry).map_err(safe_decode)?))
}
fn preparation(host: &Host, id: &str) -> Result<Preparation, String> {
    let s = state(host)?;
    if s.fresh.reservation.as_ref().is_none_or(|r| r.id != id) {
        return Err("preparation actual native reservation absent".into());
    }
    s.fresh
        .preparation
        .clone()
        .ok_or("actual preparation phase absent".into())
}
fn update_preparation(
    host: &Host,
    id: &str,
    f: impl FnOnce(&mut Preparation),
) -> Result<(), String> {
    let mut s = state(host)?;
    if s.fresh.reservation.as_ref().is_none_or(|r| r.id != id) {
        return Err("preparation native reservation changed".into());
    }
    f(s.fresh
        .preparation
        .as_mut()
        .ok_or("preparation phase absent")?);
    Ok(())
}

/// An entered request may be joined only to the actual selected owner's exact
/// writer entry. A matching real return is new reconciliation evidence; its
/// original application time and request bytes remain unchanged.
pub(super) fn join_actual_prime_return(host: &Host, id: &str) -> Result<(), String> {
    let pending = state(host)?
        .fresh
        .reservation
        .as_ref()
        .filter(|r| r.id == id)
        .and_then(|r| r.prime_pending.clone());
    let Some(pending) = pending else {
        return Ok(());
    };
    if pending["entered"] != true {
        return Ok(());
    };
    if !pending["step"]
        .as_str()
        .is_some_and(|s| s.starts_with("enrollment:"))
    {
        return Err("entered non-enrollment prime step lacks an owning lookup".into());
    }
    let request: ManifoldPeerEnrollmentRequest =
        serde_json::from_value(pending["request"].clone()).map_err(safe_decode)?;
    let entry = host
        .authority
        .current_fresh_prime_enrollment_receipt(&request.request_id)?
        .ok_or("entered prime has no exact actual-owner receipt; outcome remains UNKNOWN")?;
    if entry.request != request
        || entry.receipt.request_id != request.request_id
        || !entry.receipt.applied
        || entry.source_before_sha256
            != pending["source_before_sha256"]
                .as_str()
                .ok_or("prime entered source pin absent")?
    {
        return Err("actual prime writer entry differs from exact retained pending step".into());
    }
    let raw = host.authority.snapshot_json()?;
    let joined = json!({"$schema":"rusty.quest.embedded_duplex.fresh_prime_return_join.v1",
        "pending":pending,"actual_owner_entry":entry,"observed_at_ms":host.clock.now_ms()?,
        "current_source_raw_sha256":format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&raw))});
    let mut s = state(host)?;
    let r = s
        .fresh
        .reservation
        .as_mut()
        .filter(|r| r.id == id)
        .ok_or("prime reservation changed")?;
    if r.prime_pending.as_ref() != joined.get("pending") {
        return Err("prime pending changed during actual lookup".into());
    }
    r.prime_joined.push(joined);
    r.prime_pending = None;
    Ok(())
}

fn eligibility(host: &Host) -> Result<Value, String> {
    validate_physical(
        host,
        peer_lifecycle::fresh_setup_eligibility(host)?,
        host.callbacks.fresh_setup_physical_eligibility()?,
    )
}
fn view(
    host: &Host,
    id: &str,
    phase_id: &str,
    session: &str,
    nonce: &str,
) -> Result<(ManifoldFreshPhasePrepareView, Value), String> {
    let current = snapshot(host)?;
    let selection = host
        .authority
        .selected_fresh_setup_enrollment_selection()?
        .ok_or("actual immutable enrollment selection absent")?;
    let missing = selection
        .keys
        .iter()
        .filter(|k| {
            !current
                .enrollment
                .credentials
                .iter()
                .any(|c| c.peer_id == k.peer_id)
        })
        .map(|k| k.peer_id.clone())
        .collect::<Vec<_>>();
    let guard = {
        let s = state(host)?;
        let r = s
            .fresh
            .reservation
            .as_ref()
            .filter(|r| r.id == id)
            .ok_or("native initial reservation absent")?;
        if r.prime_origin.is_none() && (!r.prime_progress.is_empty() || r.prime_pending.is_some()) {
            return Err("initial native source custody absent".into());
        }
        if r.prime_pending
            .as_ref()
            .is_some_and(|p| p["entered"] == true)
        {
            return Err("entered original remains unresolved".into());
        }
        for row in &r.prime_progress {
            if row["receipt"]["applied"] == true
                && row["request"]["credential"]["peer_id"]
                    .as_str()
                    .is_some_and(|p| missing.iter().any(|m| m.as_str() == p))
            {
                return Err(
                    "actual accepted prime receipt contradicts current missing peer".into(),
                );
            }
        }
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_prime_guard.v1","native_request_id":id,
            "prime_origin":r.prime_origin,"prime_pending":r.prime_pending,"prime_progress":r.prime_progress,"prime_joined":r.prime_joined})
    };
    let eligible = eligibility(host)?;
    let operation = fresh_pair_setup_digest(
        "rusty.quest.fresh_setup.phase_prepare_operation.v1",
        &json!({"native_request_id":id,"phase_request_id":phase_id,"session_id":session,"nonce_a":nonce,
            "config_sha256":host.config_sha256,"native_executor_generation":host.capability.generation,"prime_guard":guard}),
    )?;
    let physical = fresh_pair_setup_digest(
        "rusty.quest.fresh_setup.phase_prepare_eligibility.v1",
        &eligible,
    )?;
    let actual = host.authority.fresh_phase_prepare_view(
        &missing,
        &operation,
        &physical,
        host.clock.now_ms()?,
    )?;
    Ok((actual, eligible))
}

fn reserve_receiver(
    host: &Host,
    id: &str,
    phase_id: &str,
    session: &str,
    nonce: &str,
) -> Result<(), String> {
    // Only a verified, role/route-bound new effect enters this function. The
    // readonly phase query never calls it.
    let native = peer_lifecycle::fresh_setup_eligibility(host)?;
    let java = host.callbacks.fresh_setup_physical_eligibility()?;
    validate_physical(host, native, java)?;
    let old = state(host)?.fresh.reservation.clone();
    let first_source = if old.is_none() {
        let raw = host.authority.snapshot_json()?;
        let snapshot: ManifoldPeerRuntimeHostSnapshot =
            serde_json::from_str(&raw).map_err(safe_decode)?;
        let fresh = snapshot
            .fresh_pair_setup
            .as_ref()
            .ok_or("first preparation selected history absent")?;
        if !snapshot.enrollment.credentials.is_empty()
            || !snapshot.accepted_peers.peers.is_empty()
            || !fresh.prime_enrollments.is_empty()
            || !fresh.receipts.is_empty()
            || !fresh.phase_preparations.is_empty()
            || fresh.archive.is_some()
        {
            return Err(
                "first preparation actual source is not unentered selected enrollment".into(),
            );
        }
        Some((raw, host.clock.now_ms()?))
    } else {
        None
    };
    {
        let mut p = process()
            .lock()
            .map_err(|_| "preparation receiver owner poisoned")?;
        if p.closing
            || p.initializing
            || p.lease_integrity_failed
            || p.host_leases != 1
            || !p
                .host
                .as_ref()
                .is_some_and(|h| Arc::ptr_eq(&h.pair_state, &host.pair_state))
            || p.fresh_setup_request.as_ref().is_some_and(|old| old != id)
        {
            return Err("preparation receiver native owner busy".into());
        }
        if p.fresh_setup_stop_request.as_deref() == Some(id) {
            return Err("preparation receiver desired Stop remains current".into());
        }
        p.fresh_setup_request = Some(id.into());
        if old.is_none() {
            // Custody exists before any fallible source read or callback after
            // entering the persistent process reservation. No prime is replayed.
            state(host)?.fresh.reservation = Some(Reservation {
                id: id.into(),
                session: session.into(),
                original_binding: json!({"kind":"incoming_fresh_setup","native_request_id":id}),
                initial_original_binding: json!({"kind":"incoming_fresh_setup","native_request_id":id}),
                authority_or_signature_entered: false,
                declined_before_dispatch: false,
                failure: None,
                prime_pending: None,
                prime_progress: Vec::new(),
                prime_origin: None,
                prime_joined: Vec::new(),
            });
        }
    }
    if let Some(old) = old {
        if old.id != id {
            return Err("preparation receiver has a different retained original".into());
        }
        join_actual_prime_return(host, id)?;
        let prior = active_phase(host, id)?;
        // Native revocation is monotonic even if the following Java task join
        // fails. An old frame must not regain write access after that failure.
        if let Some(a) = state(host)?.fresh.active.as_mut() {
            a.phase_fenced = true;
        }
        host.callbacks.bind_incoming_fresh_setup_phase(
            id,
            Some(&prior),
            phase_id,
            "linked_fence",
        )?;
    } else {
        let (raw, observed_at) = first_source.ok_or("actual first preparation source absent")?;
        if host.authority.snapshot_json()? != raw {
            return Err("first preparation actual source changed before reservation".into());
        }
        let first = json!({"$schema":"rusty.quest.embedded_duplex.fresh_first_preparation_origin.v1",
            "native_request_id":id,"phase_request_id":phase_id,"app_process_generation":host.capability.binding.generation,
            "app_record_sha256":host.capability.binding.record_sha256,"native_executor_generation":host.capability.generation,
            "config_sha256":host.config_sha256,"observed_at_ms":observed_at,
            "source_snapshot_raw_sha256":format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&raw)),
            "source_snapshot":serde_json::from_str::<Value>(&raw).map_err(safe_decode)?});
        let origin = json!({"$schema":"rusty.quest.embedded_duplex.fresh_prime_origin.v1","native_request_id":id,
            "original_binding":{"kind":"incoming_fresh_setup","native_request_id":id},"config_sha256":host.config_sha256,
            "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
            "native_executor_generation":host.capability.generation,"observed_at_ms":host.clock.now_ms()?,
            "source_snapshot_raw_sha256":format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&raw)),
            "enrollment_selection":host.authority.selected_fresh_setup_enrollment_selection()?.ok_or("actual selection absent")?,
            "source_snapshot":serde_json::from_str::<Value>(&raw).map_err(safe_decode)?,"first_preparation":first});
        state(host)?
            .fresh
            .reservation
            .as_mut()
            .filter(|r| r.id == id)
            .ok_or("preparation receiver reservation changed")?
            .prime_origin = Some(origin);
        // This is the FIRST actual incoming task, owned by the NEW phase.
        // Never register it as an invented old root task and then self-join.
        host.callbacks.bind_incoming_fresh_setup_phase(
            id,
            None,
            phase_id,
            "initial_preparation",
        )?;
    }
    let prior_reservation = state(host)?
        .fresh
        .reservation
        .clone()
        .ok_or("preparation prior reservation absent")?;
    let (local, eligible) = view(host, id, phase_id, session, nonce)?;
    let mut s = state(host)?;
    if s.fresh.preparation.is_some() {
        return Err("a preparation phase is already retained".into());
    }
    s.fresh.preparation = Some(Preparation {
        phase_id: phase_id.into(),
        transaction_id: String::new(),
        session_id: session.into(),
        nonce_a: nonce.into(),
        nonce_b: Some(random_hex()?),
        original_binding: json!({"kind":"incoming_fresh_setup","native_request_id":id}),
        eligibility: eligible,
        view: local,
        context: None,
        request: None,
        local_receipt: None,
        remote_receipt: None,
        prior_phase_archive: None,
        prior_phase_id: active_phase_from(&s, id),
        archive_pending: None,
        apply_entered: false,
        peer_view: None,
        peer_preparation_view: None,
        activation_ticket: None,
        prior_reservation: Some(prior_reservation),
        failure: None,
        activated: false,
        cached_replies: Vec::new(),
    });
    Ok(())
}
fn active_phase_from(s: &PairState, id: &str) -> String {
    s.fresh
        .active
        .as_ref()
        .filter(|a| a.id == id)
        .map(|a| a.phase_id.clone())
        .unwrap_or_else(|| id.into())
}
fn active_phase(host: &Host, id: &str) -> Result<String, String> {
    Ok(active_phase_from(&*state(host)?, id))
}

fn sign(
    host: &Host,
    id: &str,
    context: &ManifoldFreshPhasePrepareContext,
) -> Result<ManifoldFreshPairSetupSignature, String> {
    phase(host, id)?;
    let a = preparation(host, id)?;
    let selection = host
        .authority
        .selected_fresh_setup_enrollment_selection()?
        .ok_or("actual enrollment selection absent")?;
    let local = context
        .views
        .iter()
        .find(|v| v.local_peer_id.as_str() == host.local_peer_id)
        .ok_or("preparation local view absent")?;
    if context.schema_id.as_str() != "rusty.manifold.peer.fresh_phase_prepare_context.v2"
        || context.setup_request_id.as_str() != id
        || context.native_phase_id.as_str() != a.phase_id
        || rusty_manifold_peer::fresh_phase_prepare_transaction_id(context)? != context.request_id
        || context.selection != selection
        || context.peer_ids != peers(host)?
        || context.views.len() != 2
        || context.policy
            != host
                .authority
                .selected_fresh_pair_setup_policy()?
                .ok_or("actual fresh policy absent")?
        || local != &a.view
    {
        return Err("preparation exact native selected context differs".into());
    }
    // The signed frame and exact phase/local view joined above. Preserve actual
    // observation/issue bytes and wait before the unchanged strict owner gate.
    let now = authenticated_context_time(host, id, context.issued_at_ms)?;
    if context.issued_at_ms > now {
        return Err("preparation exact native selected context differs".into());
    }
    let actual = host.authority.fresh_phase_prepare_view(
        &local.unentered_peer_ids,
        &local.native_operation_sha256,
        &local.native_eligibility_sha256,
        local.observed_at_ms,
    )?;
    if actual != *local || eligibility(host)? != a.eligibility {
        return Err("preparation actual source or physical prerequisite changed".into());
    }
    let sha = fresh_pair_setup_digest(
        "rusty.manifold.peer.fresh_phase_prepare_context.v2",
        context,
    )?;
    if host.local_peer_id < host.remote_peer_id {
        host.callbacks
            .bind_fresh_setup_operator_phase(id, &a.phase_id)?;
    }
    host.callbacks.bind_fresh_setup_phase_context(
        id,
        &a.phase_id,
        context.request_id.as_str(),
        "enrollment_preparation",
        &sha,
    )?;
    mark_dispatch(host, id)?;
    let validated = ValidatedFreshPhasePrepareSigning {
        signer_key_id: host.callbacks.key_id().to_owned(),
        signing_bytes: fresh_phase_prepare_signing_bytes(context)?,
    };
    let signature = host
        .callbacks
        .sign_validated_fresh_phase_prepare(&validated)?;
    phase(host, id)?;
    serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_pair_setup_signature.v3","signer_peer_id":host.local_peer_id,
        "signer_key_id":host.callbacks.key_id(),"signature_hex":hex(&signature)})).map_err(safe_decode)
}
fn apply(
    host: &Host,
    id: &str,
    request: &ManifoldFreshPhasePrepareRequest,
) -> Result<ManifoldFreshPhasePrepareReceipt, String> {
    phase(host, id)?;
    verify_fresh_phase_prepare_request(request)?;
    let a = preparation(host, id)?;
    if a.context.as_ref() != Some(&request.context) {
        return Err("preparation exact retained context changed".into());
    }
    authenticated_context_time(host, id, request.context.issued_at_ms)?;
    // Actual owning lookup is reconciliation, never a blind re-dispatch.
    let old = host
        .authority
        .current_fresh_phase_prepare_receipt(&request.context.request_id)?;
    if let Some(receipt) = old {
        if receipt.request != *request {
            return Err("actual preparation receipt changed original bytes".into());
        }
        update_preparation(host, id, |a| {
            a.request = Some(request.clone());
            a.local_receipt = Some(receipt.clone());
        })?;
        return Ok(receipt);
    }
    if a.apply_entered {
        return Err("entered preparation lacks owning receipt; no replay granted".into());
    }
    update_preparation(host, id, |a| a.request = Some(request.clone()))?;
    mark_dispatch(host, id)?;
    update_preparation(host, id, |a| a.apply_entered = true)?;
    let receipt = host
        .authority
        .apply_fresh_phase_prepare(request, host.clock.now_ms()?)?;
    update_preparation(host, id, |a| a.local_receipt = Some(receipt.clone()))?;
    Ok(receipt)
}

fn exchange_prepare(
    host: &Host,
    id: &str,
    kind: &str,
    expected: &str,
    payload: Value,
) -> Result<Value, String> {
    phase(host, id)?;
    let a = preparation(host, id)?;
    let frame = encode_at_phase(host, kind, id, payload, &a.phase_id)?;
    let bytes = host.callbacks.clone().exchange(&frame, 128 * 1024)?;
    let body = decode(host, &bytes)?;
    if body.kind != expected
        || body.request_id != id
        || body.phase_request_id.as_deref() != Some(a.phase_id.as_str())
    {
        return Err("preparation exact response identity differs".into());
    }
    phase(host, id)?;
    Ok(body.payload)
}

fn validate_receipt(
    host: &Host,
    id: &str,
    request: &ManifoldFreshPhasePrepareRequest,
    receipt: &ManifoldFreshPhasePrepareReceipt,
    peer: &str,
) -> Result<(), String> {
    rusty_manifold_peer_runtime_host::validate_fresh_phase_prepare_receipt(receipt)
        .map_err(|_| "actual preparation receipt invalid")?;
    let a = preparation(host, id)?;
    if receipt.request != *request
        || receipt.local_peer_id.as_str() != peer
        || request.context.setup_request_id.as_str() != id
        || request.context.native_phase_id.as_str() != a.phase_id
    {
        return Err("preparation actual receipt source/phase differs".into());
    }
    Ok(())
}

/// Called only by the explicit NEW continuation Original after its exact prior
/// UNKNOWN has joined. The old operation is never dispatched again.
pub(super) fn run(host: &Host, id: &str, current_original: &str) -> Result<String, String> {
    join_actual_prime_return(host, id)?;
    let phase_id = ceremony_id::finite(&random_hex()?)?;
    let session = format!("session.duplex.{}", random_hex()?);
    let nonce = random_hex()?;
    let prior = active_phase(host, id)?;
    let prior_reservation = state(host)?
        .fresh
        .reservation
        .clone()
        .ok_or("preparation prior original absent")?;
    if let Some(a) = state(host)?.fresh.active.as_mut() {
        a.phase_fenced = true;
    }
    host.callbacks
        .bind_fresh_setup_operator_phase(id, &phase_id)?;
    let (local, eligible) = view(host, id, &phase_id, &session, &nonce)?;
    {
        let mut s = state(host)?;
        if s.fresh.preparation.is_some() {
            return Err("retained preparation must be reconciled, not replaced".into());
        }
        s.fresh.preparation = Some(Preparation {
            phase_id: phase_id.clone(),
            transaction_id: String::new(),
            session_id: session.clone(),
            nonce_a: nonce.clone(),
            nonce_b: None,
            original_binding: json!({"kind":"operator","original_id":current_original}),
            eligibility: eligible,
            view: local.clone(),
            context: None,
            request: None,
            local_receipt: None,
            remote_receipt: None,
            prior_phase_archive: None,
            prior_phase_id: prior,
            archive_pending: None,
            apply_entered: false,
            peer_view: None,
            peer_preparation_view: None,
            activation_ticket: None,
            prior_reservation: Some(prior_reservation),
            failure: None,
            activated: false,
            cached_replies: Vec::new(),
        });
    }
    let result = (|| {
        let begun = exchange_prepare(
            host,
            id,
            "prime_begin",
            "prime_begun",
            json!({"phase_id":phase_id,"session_id":session,"nonce_a":nonce,"view":local}),
        )?;
        let remote: ManifoldFreshPhasePrepareView =
            serde_json::from_value(begun["view"].clone()).map_err(safe_decode)?;
        if begun["prior_phase_id"] != preparation(host, id)?.prior_phase_id {
            return Err("bilateral preparation actual prior phases differ".into());
        }
        let nonce_b = begun["nonce_b"]
            .as_str()
            .ok_or("preparation peer nonce absent")?
            .to_owned();
        decode_hex::<32>(&nonce_b)?;
        if nonce_b == nonce || remote.local_peer_id.as_str() != host.remote_peer_id {
            return Err("preparation peer identity/challenge differs".into());
        }
        update_preparation(host, id, |a| {
            a.peer_preparation_view = Some(remote.clone());
            a.nonce_b = Some(nonce_b.clone());
        })?;
        // Both completed-prime views can be present after a later native read
        // failed before installing Attempt. There is then NO enrollment effect
        // to execute and no fabricated Manifold preparation receipt.
        if local.unentered_peer_ids.is_empty() && remote.unentered_peer_ids.is_empty() {
            return activate_bilateral(host, id);
        }
        let mut views = vec![local, remote];
        views.sort_by(|a, b| a.local_peer_id.cmp(&b.local_peer_id));
        let observed = views
            .iter()
            .map(|view| view.observed_at_ms)
            .max()
            .ok_or("preparation views absent")?;
        let issued = authenticated_context_time(host, id, observed)?;
        let mut context:ManifoldFreshPhasePrepareContext=serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_phase_prepare_context.v2",
            "request_id":phase_id,"setup_request_id":id,"native_phase_id":phase_id,
            "policy":host.authority.selected_fresh_pair_setup_policy()?.ok_or("actual preparation policy absent")?,
            "selection":host.authority.selected_fresh_setup_enrollment_selection()?.ok_or("actual preparation selection absent")?,
            "peer_ids":peers(host)?,"views":views,"issued_at_ms":issued})).map_err(safe_decode)?;
        context.request_id = rusty_manifold_peer::fresh_phase_prepare_transaction_id(&context)?;
        update_preparation(host, id, |a| {
            a.transaction_id = context.request_id.to_string();
            a.context = Some(context.clone());
            a.nonce_b = Some(nonce_b.clone());
        })?;
        let authorized = exchange_prepare(
            host,
            id,
            "prime_authorize",
            "prime_authorized",
            json!({"context":context}),
        )?;
        let remote_signature: ManifoldFreshPairSetupSignature =
            serde_json::from_value(authorized["signature"].clone()).map_err(safe_decode)?;
        let local_signature = sign(host, id, &context)?;
        let mut signatures = vec![local_signature, remote_signature];
        signatures.sort_by(|a, b| a.signer_peer_id.cmp(&b.signer_peer_id));
        let request:ManifoldFreshPhasePrepareRequest=serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_phase_prepare_request.v2","context":context,"signatures":signatures})).map_err(safe_decode)?;
        let local_receipt = apply(host, id, &request)?;
        let committed = exchange_prepare(
            host,
            id,
            "prime_commit",
            "prime_committed",
            json!({"request":request,"initiator_receipt":local_receipt}),
        )?;
        let remote_receipt: ManifoldFreshPhasePrepareReceipt =
            serde_json::from_value(committed["receipt"].clone()).map_err(safe_decode)?;
        validate_receipt(host, id, &request, &remote_receipt, &host.remote_peer_id)?;
        update_preparation(host, id, |a| {
            a.remote_receipt = Some(remote_receipt.clone())
        })?;
        activate_bilateral(host, id)
    })();
    if let Err(error) = &result {
        let _ = update_preparation(host, id, |a| a.failure = Some(error.clone()));
        record_failure(host, id, error);
    }
    result
}

fn finish_prepared(
    host: &Host,
    id: &str,
    local: ManifoldFreshPairSetupPeerView,
    remote: ManifoldFreshPairSetupPeerView,
) -> Result<String, String> {
    let a = preparation(host, id)?;
    if local.local_peer_id.as_str() != host.local_peer_id
        || remote.local_peer_id.as_str() != host.remote_peer_id
    {
        return Err("prepared setup view actor differs".into());
    }
    let mut views = vec![local, remote];
    views.sort_by(|a, b| a.local_peer_id.cmp(&b.local_peer_id));
    let observed = views
        .iter()
        .map(|view| view.observed_at_ms)
        .max()
        .ok_or("prepared setup views absent")?;
    let issued = authenticated_context_time(host, id, observed)?;
    let mut setup:ManifoldFreshPairSetupContext=serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_pair_setup_context.v3",
        "request_id":a.phase_id,"native_setup_request_id":id,"native_phase_id":a.phase_id,
        "policy":host.authority.selected_fresh_pair_setup_policy()?.ok_or("actual setup policy absent")?,
        "peer_ids":peers(host)?,"views":views,"issued_at_ms":issued})).map_err(safe_decode)?;
    setup.request_id = rusty_manifold_peer::fresh_pair_setup_transaction_id(&setup)?;
    update(host, id, |a| {
        a.transaction_id = setup.request_id.to_string();
        a.context = Some(setup.clone());
    })?;
    finish_phase(
        host,
        id,
        &a.session_id,
        &a.nonce_a,
        a.nonce_b.as_deref().ok_or("actual peer nonce absent")?,
        &setup,
    )
}

fn archive_previous(host: &Host, id: &str) -> Result<Value, String> {
    let a = preparation(host, id)?;
    if let Some(ack) = a.prior_phase_archive {
        return Ok(ack);
    }
    let raw = if let Some(raw) = a.archive_pending {
        raw
    } else {
        let (old, history) = {
            let s = state(host)?;
            (s.fresh.active.clone(), s.fresh.history.clone())
        };
        let r = a.prior_reservation.clone();
        let ticket = a
            .activation_ticket
            .as_ref()
            .ok_or("preparation prior phase ticket absent")?;
        if old.as_ref().is_some_and(|old| {
            !old.phase_fenced
                || old.phase_id != a.prior_phase_id
                || old.fence_proof.as_ref() != Some(ticket)
        }) || old.is_none() && state(host)?.fresh.reservation_fence.as_ref() != Some(ticket)
        {
            return Err("preparation prior phase lacks exact actual fence".into());
        }
        let original = old
            .as_ref()
            .map(|a| a.original_binding.clone())
            .or_else(|| r.as_ref().map(|r| r.initial_original_binding.clone()))
            .ok_or("initial original custody binding absent")?;
        let source: Value =
            serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
        let body = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_phase_archive.v2",
            "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
            "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
            "native_request_id":id,"phase_request_id":a.prior_phase_id,"original_binding":original,
            "phase_status":if old.is_some(){"fenced_unknown"}else{"reservation_fenced_unknown"},"phase_state":old,"authority_snapshot":source,"prior_archive_projection":history,
            "native_reservation":r,"preparation":null});
        let raw = body.to_string();
        update_preparation(host, id, |a| a.archive_pending = Some(raw.clone()))?;
        raw
    };
    let body: Value = serde_json::from_str(&raw).map_err(safe_decode)?;
    let ack = host.callbacks.archive_fresh_setup_phase(&raw)?;
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
            "phase_request_id",
            "original_binding",
        ]
        .iter()
        .all(|k| ack.get(*k) == body.get(*k))
        || !ack["storage_identity"]
            .as_str()
            .is_some_and(|s| !s.is_empty())
    {
        return Err("preparation exact prior custody ACK differs".into());
    }
    update_preparation(host, id, |a| {
        a.prior_phase_archive = Some(ack.clone());
        a.archive_pending = None;
    })?;
    Ok(ack)
}

fn activate(host: &Host, id: &str) -> Result<ManifoldFreshPairSetupPeerView, String> {
    phase(host, id)?;
    let a = preparation(host, id)?;
    if let Some(request) = a.request.as_ref() {
        let local = a
            .local_receipt
            .as_ref()
            .ok_or("preparation local actual receipt absent")?;
        let remote = a
            .remote_receipt
            .as_ref()
            .ok_or("preparation remote actual receipt absent")?;
        validate_receipt(host, id, request, local, &host.local_peer_id)?;
        validate_receipt(host, id, request, remote, &host.remote_peer_id)?;
        let actual = host
            .authority
            .current_fresh_phase_prepare_receipt(&request.context.request_id)?
            .ok_or("actual preparation receipt missing")?;
        if &actual != local {
            return Err("actual preparation result changed".into());
        }
    } else {
        let peer = a
            .peer_preparation_view
            .as_ref()
            .ok_or("signed peer source-only preparation view absent")?;
        if !a.view.unentered_peer_ids.is_empty()
            || !peer.unentered_peer_ids.is_empty()
            || a.context.is_some()
            || a.apply_entered
        {
            return Err("missing preparation receipt is not source-only phase completion".into());
        }
        let actual = host.authority.fresh_phase_prepare_view(
            &[],
            &a.view.native_operation_sha256,
            &a.view.native_eligibility_sha256,
            a.view.observed_at_ms,
        )?;
        if actual != a.view {
            return Err("completed-prime source changed before phase activation".into());
        }
    }
    if a.activated {
        return Ok(active(host, id)?.view);
    }
    let ticket = a
        .activation_ticket
        .as_ref()
        .ok_or("preparation activation ticket absent")?;
    if ticket != &activation_ticket(host, id)? {
        return Err("actual preparation activation preimages changed".into());
    }
    let ack = archive_previous(host, id)?;
    let eligible = eligibility(host)?;
    let operation = fresh_pair_setup_digest(
        "rusty.quest.fresh_setup.prepared_operation.v1",
        &json!({"native_request_id":id,"phase_request_id":a.phase_id,"preparation_request":a.request,"local_receipt":a.local_receipt,"remote_receipt":a.remote_receipt,"source_only_views":[a.view,a.peer_preparation_view]}),
    )?;
    let digest =
        fresh_pair_setup_digest("rusty.quest.fresh_setup.native_eligibility.v1", &eligible)?;
    let peer = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
    let view = host.authority.fresh_pair_setup_view(
        &peer,
        &peers(host)?,
        &operation,
        &digest,
        host.clock.now_ms()?,
    )?;
    let prior_pair = status_without_fresh(host)?;
    let attempt = Attempt {
        id: id.into(),
        session: a.session_id.clone(),
        phase_id: a.phase_id.clone(),
        transaction_id: String::new(),
        phase_fenced: false,
        linked_predecessor: Some(
            json!({"kind":"enrollment_preparation","prior_phase_id":a.prior_phase_id,"phase_request_id":a.phase_id,"preparation_receipt":a.local_receipt,"local_archive":ack,"activation_ticket":ticket}),
        ),
        fence_proof: None,
        nonce_a: a.nonce_a.clone(),
        nonce_b: a.nonce_b.clone(),
        eligibility: eligible,
        original_binding: a.original_binding,
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
    let mut s = state(host)?;
    let prior = s.fresh.history.clone();
    let history = &mut s.fresh.history;
    let next_hash = fresh_pair_setup_digest(
        "rusty.quest.embedded_duplex.fresh_setup_phase_archive_chain.v1",
        &json!({"previous":prior.history_sha256,"archive":ack}),
    )?;
    let next_total = history
        .total
        .checked_add(1)
        .ok_or("fresh preparation archive count exhausted")?;
    history.history_sha256 = Some(next_hash);
    history.total = next_total;
    if history.first.is_none() {
        history.first = Some(ack.clone());
    }
    history.last = Some(ack.clone());
    history.recent.push(ack);
    if history.recent.len() > 2 {
        history.recent.remove(0);
    }
    s.fresh.active = Some(attempt);
    s.fresh.reservation_fence = None;
    s.fresh
        .preparation
        .as_mut()
        .ok_or("preparation disappeared")?
        .activated = true;
    Ok(view)
}

pub(super) fn handle(host: &Host, b: Body) -> Result<Vec<u8>, String> {
    if host.local_peer_id <= host.remote_peer_id {
        return Err("preparation receiver role differs".into());
    }
    let id = b.request_id.as_str();
    let phase_id = b
        .phase_request_id
        .as_deref()
        .ok_or("preparation phase absent")?;
    let old = state(host)?.fresh.preparation.clone();
    if let Some(a) = old.as_ref() {
        if a.phase_id != phase_id {
            return Err("different preparation phase remains retained".into());
        }
        if let Some((_, prior, response, payload)) = a
            .cached_replies
            .iter()
            .find(|(kind, _, _, _)| kind == &b.kind)
        {
            if prior != &b.payload {
                return Err("preparation retry changed exact bytes".into());
            }
            host.callbacks
                .bind_incoming_fresh_setup_phase(id, None, phase_id, "current_phase")?;
            if b.kind == "prime_activate" {
                complete_preparation_handoff(host, id, None)?;
            }
            return encode_at_phase(host, response, id, payload.clone(), phase_id);
        }
    }
    let (kind, payload) = match b.kind.as_str() {
        "prime_begin" => {
            if old.is_some() {
                return Err("preparation begin already entered without retained reply".into());
            }
            if b.payload["phase_id"] != phase_id {
                return Err("preparation begin phase mismatch".into());
            }
            let session = b.payload["session_id"]
                .as_str()
                .ok_or("preparation session absent")?;
            let nonce = b.payload["nonce_a"]
                .as_str()
                .ok_or("preparation challenge absent")?;
            decode_hex::<32>(nonce)?;
            let peer: ManifoldFreshPhasePrepareView =
                serde_json::from_value(b.payload["view"].clone()).map_err(safe_decode)?;
            if peer.local_peer_id.as_str() != host.remote_peer_id {
                return Err("preparation initiating view peer differs".into());
            }
            reserve_receiver(host, id, phase_id, session, nonce)?;
            update_preparation(host, id, |a| a.peer_preparation_view = Some(peer))?;
            let a = preparation(host, id)?;
            (
                "prime_begun",
                json!({"view":a.view,"nonce_b":a.nonce_b,"prior_phase_id":a.prior_phase_id}),
            )
        }
        "prime_authorize" => {
            host.callbacks
                .bind_incoming_fresh_setup_phase(id, None, phase_id, "current_phase")?;
            let context: ManifoldFreshPhasePrepareContext =
                serde_json::from_value(b.payload["context"].clone()).map_err(safe_decode)?;
            let signature = sign(host, id, &context)?;
            update_preparation(host, id, |a| {
                a.transaction_id = context.request_id.to_string();
                a.context = Some(context);
            })?;
            ("prime_authorized", json!({"signature":signature}))
        }
        "prime_commit" => {
            host.callbacks
                .bind_incoming_fresh_setup_phase(id, None, phase_id, "current_phase")?;
            let request: ManifoldFreshPhasePrepareRequest =
                serde_json::from_value(b.payload["request"].clone()).map_err(safe_decode)?;
            let remote: ManifoldFreshPhasePrepareReceipt =
                serde_json::from_value(b.payload["initiator_receipt"].clone())
                    .map_err(safe_decode)?;
            validate_receipt(host, id, &request, &remote, &host.remote_peer_id)?;
            let local = apply(host, id, &request)?;
            update_preparation(host, id, |a| a.remote_receipt = Some(remote))?;
            ("prime_committed", json!({"receipt":local}))
        }
        "prime_activate" => {
            host.callbacks
                .bind_incoming_fresh_setup_phase(id, None, phase_id, "current_phase")?;
            let a = preparation(host, id)?;
            if serde_json::to_value(&a.local_receipt).map_err(safe_decode)?
                != b.payload["responder_receipt"]
                || serde_json::to_value(&a.remote_receipt).map_err(safe_decode)?
                    != b.payload["initiator_receipt"]
            {
                return Err("preparation bilateral result mismatch".into());
            }
            retain_activation_ticket(host, id, &b.payload["ticket"])?;
            let view = activate(host, id)?;
            let a = preparation(host, id)?;
            let payload = json!({"view":view,"ticket_sha256":activation_ticket_digest(host,id)?,"archive_ack":a.prior_phase_archive});
            // Preserve actual adoption before a fallible durable handoff callback.
            update_preparation(host, id, |a| {
                a.cached_replies.push((
                    b.kind.clone(),
                    b.payload.clone(),
                    "prime_activated".into(),
                    payload.clone(),
                ))
            })?;
            complete_preparation_handoff(host, id, None)?;
            return encode_at_phase(host, "prime_activated", id, payload, phase_id);
        }
        _ => return Err("preparation operation differs".into()),
    };
    update_preparation(host, id, |a| {
        a.cached_replies
            .push((b.kind, b.payload, kind.into(), payload.clone()))
    })?;
    encode_at_phase(host, kind, id, payload, phase_id)
}

// The phase ticket transfers actual Native custody; it is not an invented
// Manifold predecessor view, receipt, or resolution of the old UNKNOWN.
fn activation_ticket(host: &Host, id: &str) -> Result<Value, String> {
    let a = preparation(host, id)?;
    let peer = a
        .peer_preparation_view
        .as_ref()
        .ok_or("actual preparation peer view absent")?;
    let (initiator, responder, initiator_receipt, responder_receipt) =
        if host.local_peer_id < host.remote_peer_id {
            (&a.view, peer, &a.local_receipt, &a.remote_receipt)
        } else {
            (peer, &a.view, &a.remote_receipt, &a.local_receipt)
        };
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_preparation_ticket.v1",
        "native_request_id":id,"prior_phase_id":a.prior_phase_id,"phase_request_id":a.phase_id,"session_id":a.session_id,
        "nonce_a":a.nonce_a,"nonce_b":a.nonce_b.as_deref().ok_or("preparation peer challenge absent")?,
        "initiator_view":initiator,"responder_view":responder,"request":a.request,
        "initiator_receipt":initiator_receipt,"responder_receipt":responder_receipt}),
    )
}
fn activation_ticket_digest(host: &Host, id: &str) -> Result<String, String> {
    fresh_pair_setup_digest(
        "rusty.quest.embedded_duplex.fresh_setup_preparation_ticket.v1",
        &activation_ticket(host, id)?,
    )
    .map_err(str::to_owned)
}
fn retain_activation_ticket(host: &Host, id: &str, ticket: &Value) -> Result<(), String> {
    let a = preparation(host, id)?;
    if ticket != &activation_ticket(host, id)?
        || a.prior_phase_id == a.phase_id
        || a.activation_ticket
            .as_ref()
            .is_some_and(|old| old != ticket)
    {
        return Err("preparation exact bilateral activation ticket differs".into());
    }
    let mut s = state(host)?;
    if let Some(old) = s.fresh.active.as_mut() {
        if old.id != id || old.phase_id != a.prior_phase_id {
            return Err("preparation old actual phase changed".into());
        }
        old.phase_fenced = true;
        old.fence_proof = Some(ticket.clone());
    } else {
        s.fresh.reservation_fence = Some(ticket.clone());
    }
    s.fresh
        .preparation
        .as_mut()
        .ok_or("preparation absent")?
        .activation_ticket = Some(ticket.clone());
    Ok(())
}
fn validate_peer_archive(host: &Host, id: &str, ack: &Value) -> Result<(), String> {
    let a = preparation(host, id)?;
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
        || ack["native_request_id"] != id
        || ack["phase_request_id"] != a.prior_phase_id
        || ack["original_binding"] != json!({"kind":"incoming_fresh_setup","native_request_id":id})
        || !ack["body_sha256"].as_str().is_some_and(|h| {
            h.strip_prefix("sha256:")
                .is_some_and(|v| decode_hex::<32>(v).is_ok())
        })
        || !ack["storage_identity"]
            .as_str()
            .is_some_and(|s| !s.is_empty())
    {
        return Err("signed peer preparation archive differs from exact prior phase".into());
    }
    Ok(())
}
fn activate_bilateral(host: &Host, id: &str) -> Result<String, String> {
    let ticket = activation_ticket(host, id)?;
    retain_activation_ticket(host, id, &ticket)?;
    let a = preparation(host, id)?;
    let reply = exchange_prepare(
        host,
        id,
        "prime_activate",
        "prime_activated",
        json!({"initiator_receipt":a.local_receipt,"responder_receipt":a.remote_receipt,"ticket":ticket}),
    )?;
    if reply["ticket_sha256"] != activation_ticket_digest(host, id)? {
        return Err("signed preparation adoption ticket differs".into());
    }
    validate_peer_archive(host, id, &reply["archive_ack"])?;
    let remote = serde_json::from_value(reply["view"].clone()).map_err(safe_decode)?;
    let local = activate(host, id)?;
    complete_preparation_handoff(host, id, Some(reply["archive_ack"].clone()))?;
    finish_prepared(host, id, local, remote)
}
fn complete_preparation_handoff(
    host: &Host,
    id: &str,
    peer_archive: Option<Value>,
) -> Result<(), String> {
    phase(host, id)?;
    let p = preparation(host, id)?;
    let a = active(host, id)?;
    if !p.activated || a.phase_id != p.phase_id || a.phase_fenced || p.activation_ticket.is_none() {
        return Err("preparation actual adopted owner absent".into());
    }
    let local = p
        .prior_phase_archive
        .as_ref()
        .ok_or("preparation actual old custody absent")?;
    let ticket = activation_ticket_digest(host, id)?;
    let operator = host.local_peer_id < host.remote_peer_id;
    let raw = if operator {
        let binding = host.callbacks.fresh_setup_continuation_binding()?;
        let r = state(host)?
            .fresh
            .reservation
            .clone()
            .ok_or("preparation actual reservation absent")?;
        let current = a.original_binding["original_id"]
            .as_str()
            .ok_or("preparation current operator absent")?;
        // The fixed callback authenticates the actual Current/predecessor and
        // joined workers. Native rejoins that evidence to its own reservation.
        if binding["$schema"] != "rusty.quest.embedded_duplex.fresh_setup_continuation_binding.v1"
            || binding["native_request_id"] != id
            || binding["current_original_id"] != current
            || binding["prior_original_id"] != local["original_binding"]["original_id"]
            || r.original_binding != a.original_binding
            || binding["app_process_generation"] != host.capability.binding.generation
            || binding["app_record_sha256"] != host.capability.binding.record_sha256
            || binding["native_executor_generation"] != host.capability.generation
            || binding["config_sha256"] != host.config_sha256
        {
            return Err("preparation actual Current/predecessor Original changed".into());
        }
        let peer = peer_archive.ok_or("preparation actual peer custody absent")?;
        validate_peer_archive(host, id, &peer)?;
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_continuation_handoff.v1",
            "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
            "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
            "native_request_id":id,"prior_phase_id":p.prior_phase_id,"phase_request_id":p.phase_id,
            "prior_original_id":binding["prior_original_id"],"prior_outcome_sha256":binding["prior_outcome_sha256"],
            "current_original_id":current,"ticket_sha256":ticket,"local_archive":local,"peer_archive":peer}).to_string()
    } else {
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_incoming_handoff.v1",
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
        "native_request_id":id,"prior_phase_id":p.prior_phase_id,"phase_request_id":p.phase_id,
        "ticket_sha256":ticket,"local_archive":local}).to_string()
    };
    let body: Value = serde_json::from_str(&raw).map_err(safe_decode)?;
    let ack = if operator {
        host.callbacks
            .complete_fresh_setup_continuation_handoff(&raw)?
    } else {
        host.callbacks.complete_incoming_fresh_setup_handoff(&raw)?
    };
    let keys = if operator {
        vec![
            "$schema",
            "body_sha256",
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
            "prior_original_id",
            "current_original_id",
        ]
    } else {
        vec![
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
        ]
    };
    let schema = if operator {
        "rusty.quest.embedded_duplex.fresh_setup_continuation_handoff_retained.v1"
    } else {
        "rusty.quest.embedded_duplex.fresh_setup_incoming_handoff_retained.v1"
    };
    if !ack
        .as_object()
        .is_some_and(|o| o.len() == keys.len() && keys.iter().all(|k| o.contains_key(*k)))
        || ack["$schema"] != schema
        || ack["body_sha256"]
            != format!(
                "sha256:{}",
                rusty_quest_broker_authority::packaged_json_sha256(&raw)
            )
        || !keys
            .iter()
            .filter(|k| !["$schema", "body_sha256", "storage_identity"].contains(k))
            .all(|k| ack.get(*k) == body.get(*k))
        || !operator
            && !ack["storage_identity"]
                .as_str()
                .is_some_and(|s| !s.is_empty())
    {
        return Err("preparation actual adoption handoff ACK differs".into());
    }
    update(host, id, |a| {
        if let Some(predecessor) = a.linked_predecessor.as_mut() {
            predecessor["handoff_ack"] = ack;
        }
    })?;
    // Exact old Native reservation is now durably owned by its archive. The
    // current reservation/authority gate remain actual and are not compacted.
    update_preparation(host, id, |p| p.prior_reservation = None)?;
    Ok(())
}
