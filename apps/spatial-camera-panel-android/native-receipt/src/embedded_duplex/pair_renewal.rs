//! Fresh signed renewal of the already accepted pair; no enrollment reset or media restart.
use super::*;
#[path = "pair_renewal_guard.rs"]
mod guard;
pub(super) use guard::CycleLedger;
use rusty_manifold_peer::ManifoldCommonLanReciprocalEd25519Receipt;

const CREDENTIAL: &str = "credential";
const SESSION: &str = "session";

#[derive(Clone, Default)]
struct Round {
    hello: Option<Value>,
    remote_signature: Option<ManifoldCommonLanReciprocalEd25519Signature>,
    remote_prepared: Option<(
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    )>,
    local_remote_signature: Option<ManifoldCommonLanReciprocalEd25519Signature>,
    nonce_a: Option<String>,
    nonce_b: Option<String>,
    signed_context_a_expires_at_ms: Option<u64>,
    local: Option<(
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    )>,
    reciprocal: Option<ManifoldCommonLanReciprocalEd25519Receipt>,
    local_receipt: Option<Value>,
    remote_receipt: Option<Value>,
}

#[derive(Default)]
pub(super) struct RenewalState {
    id: Option<String>,
    session: Option<String>,
    original_decision: Option<String>,
    peer_decision: Option<String>,
    initiator: bool,
    deadline_ms: u64,
    credential: Round,
    session_round: Round,
    // Fixed protocol: eight bounded request/reply pairs across two phases.
    replies: Vec<(String, Value, &'static str, Value)>,
    result: Option<Value>,
    last_failure: Option<String>,
}

fn round_mut<'a>(renewal: &'a mut RenewalState, phase: &str) -> Result<&'a mut Round, String> {
    match phase {
        CREDENTIAL => Ok(&mut renewal.credential),
        SESSION => Ok(&mut renewal.session_round),
        _ => Err("renewal phase invalid".into()),
    }
}

fn pair_current(host: &Host) -> Result<Value, String> {
    host.capability.require_live()?;
    let session = {
        let current = state(host)?;
        if current.stage != Stage::Completed {
            return Err("accepted pair ceremony required for renewal".into());
        }
        current
            .session_id
            .clone()
            .ok_or("accepted pair identity absent")?
    };
    let id = serde_json::from_value(json!(session)).map_err(safe_decode)?;
    let receipt = host
        .authority
        .current_common_lan_session(&id, host.clock.now_ms()?)?;
    if !receipt.current || receipt.decision_id.is_none() || receipt.expires_at_ms.is_none() {
        return Err("current accepted pair expired or revoked".into());
    }
    serde_json::to_value(receipt).map_err(safe_decode)
}

fn current_decision(current: &Value, session: &str) -> Result<String, String> {
    if current.get("current").and_then(Value::as_bool) != Some(true)
        || current.get("session_id").and_then(Value::as_str) != Some(session)
    {
        return Err("signed renewal pair identity differs".into());
    }
    current
        .get("decision_id")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or("renewal decision absent".into())
}

fn require_cycle(host: &Host, id: &str) -> Result<(), String> {
    let current = state(host)?;
    if current.renewal.id.as_deref() != Some(id)
        || current.renewal.deadline_ms <= host.clock.now_ms()?
    {
        return Err("renewal cycle unavailable or expired; original pair retained".into());
    }
    Ok(())
}

fn prepare_renewal(
    host: &Host,
    id: &str,
    phase: &str,
    role: &str,
    local_nonce: &str,
    remote_nonce: &str,
) -> Result<
    (
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    ),
    String,
> {
    let now = host.clock.now_ms()?;
    let current = pair_current(host)?;
    let old_expiry = current
        .get("expires_at_ms")
        .and_then(Value::as_u64)
        .ok_or("current renewal expiry absent")?;
    let expiry = if phase == CREDENTIAL {
        old_expiry.min(
            now.checked_add(FRAME_TTL_MS - ISSUE_BACKDATE_MS)
                .ok_or("renewal time overflow")?,
        )
    } else {
        now.checked_add(CONTEXT_TTL_MS - ISSUE_BACKDATE_MS)
            .ok_or("renewal time overflow")?
    };
    if expiry <= now {
        return Err("current credentials cannot authorize renewal".into());
    }
    let draft: QuestCommonLanContextDraft = serde_json::from_value(json!({
        "correlation_id":format!("correlation.{id}.{phase}.{role}"),
        "initiator":binding(host,&host.local_peer_id,local_nonce,"initiator")?,
        "responder":binding(host,&host.remote_peer_id,remote_nonce,"responder")?,
        "transport":transport(host)?,"coordinator_epoch":host.capability.generation,
        "issued_at_ms":now.saturating_sub(ISSUE_BACKDATE_MS),"expires_at_ms":expiry
    }))
    .map_err(safe_decode)?;
    let context = host.authority.prepare_common_lan_context(draft)?;
    let signature = sign(host, &context)?;
    Ok((context, signature))
}

fn verify_context(
    host: &Host,
    id: &str,
    phase: &str,
    role: &str,
    context: &ManifoldCommonLanReciprocalEd25519Context,
    nonce_a: &str,
    nonce_b: &str,
) -> Result<(), String> {
    let (initiator, responder, first, second) = if role == "a" {
        (&host.remote_peer_id, &host.local_peer_id, nonce_a, nonce_b)
    } else {
        (&host.remote_peer_id, &host.local_peer_id, nonce_b, nonce_a)
    };
    if context.correlation_id.as_str() != format!("correlation.{id}.{phase}.{role}")
        || context.initiator.peer_id.as_str() != initiator
        || context.responder.peer_id.as_str() != responder
        || context.initiator.device_nonce_hex != first
        || context.responder.device_nonce_hex != second
    {
        return Err("renewal context exact peer/challenge binding differs".into());
    }
    Ok(())
}

fn apply_round(
    host: &Host,
    id: &str,
    phase: &str,
    session: &str,
    prepared: (
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    ),
    remote_signature: ManifoldCommonLanReciprocalEd25519Signature,
    remote_context_expires_at_ms: Option<u64>,
) -> Result<Value, String> {
    require_cycle(host, id)?;
    let request: ManifoldCommonLanReciprocalEd25519ReviewRequest = serde_json::from_value(json!({
        "$schema":COMMON_LAN_RECIPROCAL_ED25519_REVIEW_SCHEMA,
        "request_id":format!("request.{id}.{phase}.reciprocal"),"context":prepared.0,
        "initiator_signature":prepared.1,"responder_signature":remote_signature
    }))
    .map_err(safe_decode)?;
    let now = host.clock.now_ms()?;
    let retained = { state(host)?.renewal.round(phase)?.reciprocal.clone() };
    let reciprocal = match retained {
        Some(receipt) => receipt,
        None => {
            let receipt: ManifoldCommonLanReciprocalEd25519Receipt =
                host.authority.apply_common_lan_reciprocal(&request, now)?;
            if !receipt.accepted {
                return Err("fresh renewal reciprocal authority rejected".into());
            }
            round_mut(&mut state(host)?.renewal, phase)?.reciprocal = Some(receipt.clone());
            receipt
        }
    };
    let value = if phase == CREDENTIAL {
        serde_json::to_value(
            host.authority
                .refresh_concurrent_pair_credentials(&reciprocal, now)?,
        )
        .map_err(safe_decode)?
    } else {
        let remote_expiry =
            remote_context_expires_at_ms.ok_or("mutual signed renewal deadline absent")?;
        let mutual_expiry =
            mutual_signed_session_expiry(reciprocal.expires_at_ms, remote_expiry, now)?;
        let snapshot = snapshot(host)?;
        // Reuse the exact accepted proposal's peer roles, capabilities and transport.
        let proposals = snapshot
            .pointer("/peer_sessions/sessions")
            .and_then(Value::as_array)
            .ok_or("accepted session proposal absent")?;
        let matches = proposals
            .iter()
            .filter(|entry| {
                entry.get("topology_kind").and_then(Value::as_str) == Some("common_lan")
                    && entry
                        .pointer("/record/proposal/session_id")
                        .and_then(Value::as_str)
                        == Some(session)
                    && entry.pointer("/record/revoked").and_then(Value::as_bool) == Some(false)
            })
            .collect::<Vec<_>>();
        if matches.len() != 1 {
            return Err("accepted renewal session cardinality differs".into());
        }
        let mut proposal = matches[0]
            .pointer("/record/proposal")
            .cloned()
            .ok_or("original renewal proposal absent")?;
        let fields = proposal
            .as_object_mut()
            .ok_or("original renewal proposal shape")?;
        fields.insert(
            "proposal_id".into(),
            json!(format!("proposal.{id}.session.renew")),
        );
        fields.insert(
            "expected_authority_revision".into(),
            json!(revision(&snapshot, "/peer_sessions/authority_revision")?),
        );
        fields.insert("expires_at_ms".into(), json!(mutual_expiry));
        let proposal: ManifoldCommonLanPeerSessionProposal =
            serde_json::from_value(proposal).map_err(safe_decode)?;
        serde_json::to_value(host.authority.apply_common_lan_session_renewal(
            &proposal,
            &reciprocal,
            now,
        )?)
        .map_err(safe_decode)?
    };
    if value.get("applied").and_then(Value::as_bool) != Some(true) {
        return Err("renewal owner operation rejected".into());
    }
    validate_receipt(host, phase, session, &value, false)?;
    Ok(value)
}

fn validate_receipt(
    host: &Host,
    phase: &str,
    session: &str,
    value: &Value,
    remote: bool,
) -> Result<(), String> {
    if value.get("applied").and_then(Value::as_bool) != Some(true) {
        return Err("signed renewal owner receipt rejected".into());
    }
    let expiry = value
        .get("expires_at_ms")
        .and_then(Value::as_u64)
        .ok_or("renewal owner expiry absent")?;
    let now = host.clock.now_ms()?;
    guard::bounded_expiry(
        now,
        expiry,
        if phase == CREDENTIAL {
            PAIR_CREDENTIAL_TTL_MS
        } else {
            CONTEXT_TTL_MS
        },
        if remote { ISSUE_BACKDATE_MS } else { 0 },
    )?;
    if phase == SESSION {
        let current = state(host)?;
        let decision = if remote {
            &current.renewal.peer_decision
        } else {
            &current.renewal.original_decision
        };
        if value.get("session_id").and_then(Value::as_str) != Some(session)
            || value.get("decision_id").and_then(Value::as_str) != decision.as_deref()
        {
            return Err("renewal changed original session or decision".into());
        }
    } else {
        let mut peers = value
            .get("peer_ids")
            .and_then(Value::as_array)
            .ok_or("refreshed peer keys absent")?
            .clone();
        peers.sort_by_key(Value::to_string);
        let mut expected = vec![json!(host.local_peer_id), json!(host.remote_peer_id)];
        expected.sort_by_key(Value::to_string);
        let mut keys = value
            .get("key_ids")
            .and_then(Value::as_array)
            .ok_or("refreshed key identities absent")?
            .clone();
        keys.sort_by_key(Value::to_string);
        let mut expected_keys = vec![json!(host.callbacks.key_id()), json!(host.remote_key_id)];
        expected_keys.sort_by_key(Value::to_string);
        if peers != expected || keys != expected_keys {
            return Err("renewal changed enrolled keys or peers".into());
        }
    }
    Ok(())
}

fn exchange_renewal(
    host: &Host,
    id: &str,
    phase: &str,
    kind: &str,
    response: &str,
    mut payload: Value,
) -> Result<Value, String> {
    require_cycle(host, id)?;
    payload
        .as_object_mut()
        .ok_or("renewal request shape")?
        .insert("phase".into(), json!(phase));
    let frame = encode(host, kind, id, payload)?;
    let reply = host.callbacks.clone().exchange(&frame, 128 * 1024)?;
    let body = decode(host, &reply, Some(response), Some(id))?;
    if body.payload.get("phase").and_then(Value::as_str) != Some(phase) {
        return Err("renewal response phase differs".into());
    }
    Ok(body.payload)
}

fn run_round(host: &Host, id: &str, phase: &str, session: &str) -> Result<(), String> {
    let nonce_a = {
        let mut current = state(host)?;
        let round = round_mut(&mut current.renewal, phase)?;
        if round.nonce_a.is_none() {
            round.nonce_a = Some(random_hex()?);
        }
        round.nonce_a.clone().ok_or("renewal nonce absent")?
    };
    let round_completed = {
        let current = state(host)?;
        let round = current.renewal.round(phase)?;
        round.local_receipt.is_some() && round.remote_receipt.is_some()
    };
    if phase == CREDENTIAL && round_completed {
        return Ok(());
    }
    let retained_hello = { state(host)?.renewal.round(phase)?.hello.clone() };
    let hello_request = match retained_hello {
        Some(value) => value,
        None => {
            let value = json!({"session_id":session,"nonce_a":nonce_a,"current_session":pair_current(host)?});
            round_mut(&mut state(host)?.renewal, phase)?.hello = Some(value.clone());
            value
        }
    };
    let hello = exchange_renewal(
        host,
        id,
        phase,
        "renew_hello",
        "renew_hello_ack",
        hello_request,
    )?;
    let nonce_b = hello
        .get("nonce_b")
        .and_then(Value::as_str)
        .ok_or("remote renewal nonce absent")?
        .to_owned();
    decode_hex::<32>(&nonce_b)?;
    if nonce_b == nonce_a {
        return Err("renewal nonces collided".into());
    }
    let peer_decision = current_decision(
        hello
            .get("current_session")
            .ok_or("remote original pair receipt absent")?,
        session,
    )?;
    {
        let mut current = state(host)?;
        if current
            .renewal
            .peer_decision
            .as_ref()
            .is_some_and(|old| old != &peer_decision)
        {
            return Err("remote original decision changed".into());
        }
        current.renewal.peer_decision = Some(peer_decision);
        round_mut(&mut current.renewal, phase)?.nonce_b = Some(nonce_b.clone());
    }
    let local = { state(host)?.renewal.round(phase)?.local.clone() };
    let local = match local {
        Some(value) => value,
        None => {
            let value = prepare_renewal(host, id, phase, "a", &nonce_a, &nonce_b)?;
            round_mut(&mut state(host)?.renewal, phase)?.local = Some(value.clone());
            value
        }
    };
    let retained_signature = { state(host)?.renewal.round(phase)?.remote_signature.clone() };
    let signature_remote = match retained_signature {
        Some(value) => value,
        None => {
            let signed = exchange_renewal(
                host,
                id,
                phase,
                "renew_sign_a",
                "renew_signed_a",
                json!({"context":local.0}),
            )?;
            let value = parse_signature(
                signed
                    .get("signature")
                    .ok_or("renewal remote signature absent")?,
            )?;
            round_mut(&mut state(host)?.renewal, phase)?.remote_signature = Some(value.clone());
            value
        }
    };
    let retained_remote = { state(host)?.renewal.round(phase)?.remote_prepared.clone() };
    let (context_remote, remote_prepared_signature) = match retained_remote {
        Some(value) => value,
        None => {
            let prepared = exchange_renewal(
                host,
                id,
                phase,
                "renew_prepare_b",
                "renew_prepared_b",
                json!({}),
            )?;
            let context = parse_context(
                prepared
                    .get("context")
                    .ok_or("renewal remote context absent")?,
            )?;
            verify_context(host, id, phase, "b", &context, &nonce_a, &nonce_b)?;
            let signature = parse_signature(
                prepared
                    .get("signature")
                    .ok_or("renewal prepared signature absent")?,
            )?;
            round_mut(&mut state(host)?.renewal, phase)?.remote_prepared =
                Some((context.clone(), signature.clone()));
            (context, signature)
        }
    };
    let retained_local_signature = {
        state(host)?
            .renewal
            .round(phase)?
            .local_remote_signature
            .clone()
    };
    let signature_local = match retained_local_signature {
        Some(value) => value,
        None => {
            let value = sign(host, &context_remote)?;
            if remote_prepared_signature.context_sha256 != value.context_sha256 {
                return Err("renewal context signature digest differs".into());
            }
            round_mut(&mut state(host)?.renewal, phase)?.local_remote_signature =
                Some(value.clone());
            value
        }
    };
    let retained = { state(host)?.renewal.round(phase)?.local_receipt.clone() };
    let local_receipt = match retained {
        Some(value) => value,
        None => {
            let value = apply_round(
                host,
                id,
                phase,
                session,
                local,
                signature_remote,
                (phase == SESSION).then_some(context_remote.expires_at_ms),
            )?;
            round_mut(&mut state(host)?.renewal, phase)?.local_receipt = Some(value.clone());
            value
        }
    };
    let finished = exchange_renewal(
        host,
        id,
        phase,
        "renew_finish_b",
        "renew_finished_b",
        json!({
        "signature":signature_local,"initiator_receipt":local_receipt}),
    )?;
    let remote = finished
        .get("receipt")
        .cloned()
        .ok_or("remote renewal owner receipt absent")?;
    validate_receipt(host, phase, session, &remote, true)?;
    if phase == SESSION
        && remote.get("expires_at_ms").and_then(Value::as_u64)
            != local_receipt.get("expires_at_ms").and_then(Value::as_u64)
    {
        return Err("mutual signed renewal deadlines differ".into());
    }
    current_decision(
        finished
            .get("current_session")
            .ok_or("remote renewed session receipt absent")?,
        session,
    )?;
    round_mut(&mut state(host)?.renewal, phase)?.remote_receipt = Some(remote);
    if phase == SESSION {
        let provider = finished
            .get("provider_renewal")
            .cloned()
            .filter(|v| !v.is_null())
            .ok_or("remote actual provider renewal absent")?;
        let current = state(host)?;
        let result = json!({"$schema":"rusty.quest.embedded_duplex.pair_renewal_result.v1","session_id":session,
            "renewal_id":id,"local_session_renewal":current.renewal.session_round.local_receipt,
            "remote_session_renewal":current.renewal.session_round.remote_receipt,"remote_provider_renewal":provider,
            "local_credential_refresh":current.renewal.credential.local_receipt,
            "remote_credential_refresh":current.renewal.credential.remote_receipt});
        drop(current);
        state(host)?.renewal.result = Some(result);
    }
    Ok(())
}

impl RenewalState {
    fn round(&self, phase: &str) -> Result<&Round, String> {
        match phase {
            CREDENTIAL => Ok(&self.credential),
            SESSION => Ok(&self.session_round),
            _ => Err("renewal phase invalid".into()),
        }
    }
}

pub(super) fn run(host: &Host) -> Result<String, String> {
    let current_pair = pair_current(host)?;
    let session = current_pair
        .get("session_id")
        .and_then(Value::as_str)
        .ok_or("renewal session absent")?
        .to_owned();
    let decision = current_decision(&current_pair, &session)?;
    let id = {
        let mut current = state(host)?;
        if let Some(result) = &current.renewal.result {
            if current.renewal.initiator {
                return Ok(result.to_string());
            }
            // A responder has already joined its provider before emitting the signed final reply.
            // Its next fixed local action may start a new independently signed cycle.
            let completed = current
                .renewal
                .id
                .clone()
                .ok_or("completed renewal identity absent")?;
            current.renewal_ledger.complete(&completed)?;
            current.renewal = RenewalState::default();
        }
        if current.renewal.id.is_none() {
            current.renewal = RenewalState {
                id: Some(format!("ceremony.duplex.{}", random_hex()?)),
                session: Some(session.clone()),
                original_decision: Some(decision),
                initiator: true,
                deadline_ms: host
                    .clock
                    .now_ms()?
                    .checked_add(CEREMONY_TTL_MS)
                    .ok_or("renewal deadline overflow")?,
                ..Default::default()
            };
        }
        if !current.renewal.initiator {
            return Err("remote signed renewal is in progress".into());
        }
        current.renewal.id.clone().ok_or("renewal cycle absent")?
    };
    let result = (|| {
        run_round(host, &id, CREDENTIAL, &session)?;
        run_round(host, &id, SESSION, &session)?;
        state(host)?
            .renewal
            .result
            .clone()
            .map(|v| v.to_string())
            .ok_or_else(|| "combined renewal proof absent".to_string())
    })();
    if let Err(error) = &result {
        state(host)?.renewal.last_failure = Some(error.clone());
    }
    result
}

pub(super) fn finish_cycle(host: &Host, id: &str) -> Result<(), String> {
    let mut current = state(host)?;
    if current.renewal.id.as_deref() != Some(id) || current.renewal.result.is_none() {
        return Err("accepted renewal cycle differs".into());
    }
    current.renewal_ledger.complete(id)?;
    current.renewal = RenewalState::default();
    Ok(())
}

pub(super) fn status(host: &Host) -> Result<Value, String> {
    let current = state(host)?;
    Ok(
        json!({"renewal_id":current.renewal.id,"session_id":current.renewal.session,
        "state":if current.renewal.result.is_some(){"pair_renewed_provider_join_pending"}else if current.renewal.id.is_some(){"pending"}else{"idle"},
        "last_failure":current.renewal.last_failure,"original_pair_retained":current.stage==Stage::Completed}),
    )
}

pub(super) fn handle_frame(host: &Host, frame: FrameBody) -> Result<Vec<u8>, String> {
    let phase = frame
        .payload
        .get("phase")
        .and_then(Value::as_str)
        .ok_or("renewal phase absent")?;
    if phase != CREDENTIAL && phase != SESSION {
        return Err("renewal phase invalid".into());
    }
    let id = frame.ceremony_id.as_str();
    let pair = pair_current(host)?;
    let session = pair
        .get("session_id")
        .and_then(Value::as_str)
        .ok_or("current renewal session absent")?
        .to_owned();
    let decision = current_decision(&pair, &session)?;
    let key = format!("{phase}.{}", frame.kind);
    {
        let current = state(host)?;
        current.renewal_ledger.require_fresh(id)?;
        if current.renewal.id.as_deref() == Some(id) {
            if let Some((kind, payload)) =
                guard::cached_reply(&current.renewal.replies, &key, &frame.payload)?
            {
                let payload = payload.clone();
                drop(current);
                require_cycle(host, id)?;
                return encode(host, kind, id, payload);
            }
        }
    }
    let (reply_kind, mut payload) = match frame.kind.as_str() {
        "renew_hello" => {
            super::super::peer_lifecycle::require_renewable_peer(host)?;
            if frame.payload.get("session_id").and_then(Value::as_str) != Some(session.as_str()) {
                return Err("renewal cannot replace accepted session".into());
            }
            let peer_decision = current_decision(
                frame
                    .payload
                    .get("current_session")
                    .ok_or("signed original pair absent")?,
                &session,
            )?;
            let nonce = frame
                .payload
                .get("nonce_a")
                .and_then(Value::as_str)
                .ok_or("renewal nonce absent")?;
            decode_hex::<32>(nonce)?;
            let nonce_b = random_hex()?;
            if nonce == nonce_b {
                return Err("renewal nonces collided".into());
            }
            let mut current = state(host)?;
            if current.renewal.id.as_deref() != Some(id) {
                let can_yield = current.renewal.initiator
                    && host.local_peer_id > host.remote_peer_id
                    && current.renewal.credential.local_receipt.is_none()
                    && current.renewal.session_round.local_receipt.is_none();
                if current.renewal.id.is_some() && current.renewal.result.is_none() && !can_yield {
                    return Err("another renewal remains pending".into());
                }
                if phase != CREDENTIAL {
                    return Err("credential-bound renewal proof required first".into());
                }
                if let Some(old) = current.renewal.id.clone() {
                    current.renewal_ledger.complete(&old)?;
                }
                current.renewal = RenewalState {
                    id: Some(id.into()),
                    session: Some(session.clone()),
                    original_decision: Some(decision),
                    peer_decision: Some(peer_decision),
                    deadline_ms: host
                        .clock
                        .now_ms()?
                        .checked_add(CEREMONY_TTL_MS)
                        .ok_or("renewal deadline overflow")?,
                    ..Default::default()
                };
            }
            if phase == SESSION
                && (current.renewal.credential.local_receipt.is_none()
                    || current.renewal.credential.remote_receipt.is_none())
            {
                return Err("both actual credential refresh receipts required".into());
            }
            if phase == SESSION
                && (current.renewal.credential.nonce_a.as_deref() == Some(nonce)
                    || current.renewal.credential.nonce_b.as_deref() == Some(nonce)
                    || current.renewal.credential.nonce_a.as_deref() == Some(nonce_b.as_str())
                    || current.renewal.credential.nonce_b.as_deref() == Some(nonce_b.as_str()))
            {
                return Err("renewal phases require independent fresh nonces".into());
            }
            let round = round_mut(&mut current.renewal, phase)?;
            round.nonce_a = Some(nonce.into());
            round.nonce_b = Some(nonce_b.clone());
            (
                "renew_hello_ack",
                json!({"nonce_b":nonce_b,"current_session":pair}),
            )
        }
        "renew_sign_a" => {
            require_cycle(host, id)?;
            let context = parse_context(
                frame
                    .payload
                    .get("context")
                    .ok_or("renewal context absent")?,
            )?;
            let (a, b) = {
                let current = state(host)?;
                let round = current.renewal.round(phase)?;
                (
                    round.nonce_a.clone().ok_or("renewal nonce absent")?,
                    round.nonce_b.clone().ok_or("renewal nonce absent")?,
                )
            };
            verify_context(host, id, phase, "a", &context, &a, &b)?;
            let signature = sign(host, &context)?;
            round_mut(&mut state(host)?.renewal, phase)?.signed_context_a_expires_at_ms =
                Some(context.expires_at_ms);
            ("renew_signed_a", json!({"signature":signature}))
        }
        "renew_prepare_b" => {
            require_cycle(host, id)?;
            let (a, b) = {
                let current = state(host)?;
                let round = current.renewal.round(phase)?;
                (
                    round.nonce_a.clone().ok_or("renewal nonce absent")?,
                    round.nonce_b.clone().ok_or("renewal nonce absent")?,
                )
            };
            let prepared = prepare_renewal(host, id, phase, "b", &b, &a)?;
            round_mut(&mut state(host)?.renewal, phase)?.local = Some(prepared.clone());
            (
                "renew_prepared_b",
                json!({"context":prepared.0,"signature":prepared.1}),
            )
        }
        "renew_finish_b" => {
            require_cycle(host, id)?;
            let initiator = frame
                .payload
                .get("initiator_receipt")
                .cloned()
                .ok_or("initiator actual owner receipt absent")?;
            validate_receipt(host, phase, &session, &initiator, true)?;
            let prepared = state(host)?
                .renewal
                .round(phase)?
                .local
                .clone()
                .ok_or("exact prepared renewal context absent")?;
            let retained = { state(host)?.renewal.round(phase)?.local_receipt.clone() };
            let remote_context_expires_at_ms = if phase == SESSION {
                Some(
                    state(host)?
                        .renewal
                        .round(phase)?
                        .signed_context_a_expires_at_ms
                        .ok_or("signed initiator renewal context deadline absent")?,
                )
            } else {
                None
            };
            let receipt = match retained {
                Some(value) => value,
                None => {
                    let value = apply_round(
                        host,
                        id,
                        phase,
                        &session,
                        prepared,
                        parse_signature(
                            frame
                                .payload
                                .get("signature")
                                .ok_or("renewal signature absent")?,
                        )?,
                        remote_context_expires_at_ms,
                    )?;
                    round_mut(&mut state(host)?.renewal, phase)?.local_receipt =
                        Some(value.clone());
                    value
                }
            };
            if phase == SESSION
                && initiator.get("expires_at_ms").and_then(Value::as_u64)
                    != receipt.get("expires_at_ms").and_then(Value::as_u64)
            {
                return Err("mutual signed renewal deadlines differ".into());
            }
            round_mut(&mut state(host)?.renewal, phase)?.remote_receipt = Some(initiator.clone());
            let provider = if phase == SESSION {
                let pair_proof = json!({"session_id":session,"renewal_id":id,"local_session_renewal":receipt,"remote_session_renewal":initiator});
                let provider =
                    super::super::peer_lifecycle::renew_after_signed_pair(host, &receipt)?;
                state(host)?.renewal.result = Some(pair_proof);
                provider
            } else {
                Value::Null
            };
            (
                "renew_finished_b",
                json!({"receipt":receipt,"current_session":pair_current(host)?,"provider_renewal":provider}),
            )
        }
        _ => return Err("unsupported renewal request".into()),
    };
    payload
        .as_object_mut()
        .ok_or("renewal response shape")?
        .insert("phase".into(), json!(phase));
    {
        let mut current = state(host)?;
        if current.renewal.id.as_deref() != Some(id) {
            return Err("renewal cycle replaced".into());
        }
        guard::remember_reply(
            &mut current.renewal.replies,
            key,
            frame.payload,
            reply_kind,
            payload.clone(),
        )?;
    }
    encode(host, reply_kind, id, payload)
}
