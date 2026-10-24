//! A distinct bilateral setup transaction. Never resets accepted pair/renewal history.
//! RQFS1 is deliberately disjoint from the original RQPC1 ceremony and renewal frames.
use super::*;
use rusty_manifold_peer::{
    fresh_pair_setup_digest, fresh_pair_setup_signing_bytes, verify_fresh_pair_setup_request,
    ManifoldFreshPairSetupContext, ManifoldFreshPairSetupPeerView, ManifoldFreshPairSetupRequest,
    ManifoldFreshPairSetupSignature,
};
use rusty_manifold_peer_runtime_host::ManifoldFreshPairSetupReceipt;

pub(super) const MAGIC: &[u8] = b"RQFS1";
const SCHEMA: &str = "rusty.quest.embedded_duplex.fresh_pair_setup_frame.v1";
const DOMAIN: &[u8] = b"rusty.quest.embedded_duplex.fresh_pair_setup_frame.v1\0";
pub(super) const MAGIC_V2: &[u8] = b"RQFS2";
const SCHEMA_V2: &str = "rusty.quest.embedded_duplex.fresh_pair_setup_frame.v2";
const DOMAIN_V2: &[u8] = b"rusty.quest.embedded_duplex.fresh_pair_setup_frame.v2\0";
#[path = "fresh_pair_continuation.rs"]
mod continuation;
#[path = "fresh_history_custody.rs"]
mod history_custody;
#[path = "fresh_pair_preparation.rs"]
mod preparation;
pub(super) fn continue_run(host: &Host) -> Result<String, String> {
    continuation::run(host)
}

#[derive(Clone, Default, Serialize)]
pub(super) struct FreshState {
    #[serde(skip)]
    singleflight: Arc<AtomicBool>,
    reservation: Option<Reservation>,
    prior_reservations: Vec<Reservation>,
    active: Option<Attempt>,
    // Prior bodies transfer only after an exact app-owned durable custody ACK.
    // This cursor is evidence, never a writable-owner restore capability.
    history: PhaseArchiveCursor,
    completed_setups: u64,
    continuation_selected: bool,
    pending_phase: Option<continuation::PhaseTicket>,
    pending_archive: Option<(String, String)>,
    preparation: Option<preparation::Preparation>,
    reservation_fence: Option<Value>,
    archive_reservation: Option<Reservation>,
    prepared_start: Option<String>,
}

#[derive(Clone, Serialize)]
struct Reservation {
    id: String,
    session: String,
    original_binding: Value,
    initial_original_binding: Value,
    authority_or_signature_entered: bool,
    declined_before_dispatch: bool,
    failure: Option<String>,
    prime_pending: Option<Value>,
    prime_progress: Vec<Value>,
    prime_origin: Option<Value>,
    prime_joined: Vec<Value>,
}

#[derive(Clone, Serialize)]
struct Attempt {
    id: String,
    session: String,
    phase_id: String,
    transaction_id: String,
    phase_fenced: bool,
    linked_predecessor: Option<Value>,
    fence_proof: Option<Value>,
    nonce_a: String,
    nonce_b: Option<String>,
    eligibility: Value,
    original_binding: Value,
    view: ManifoldFreshPairSetupPeerView,
    context: Option<ManifoldFreshPairSetupContext>,
    request: Option<ManifoldFreshPairSetupRequest>,
    local_setup: Option<ManifoldFreshPairSetupReceipt>,
    remote_setup: Option<ManifoldFreshPairSetupReceipt>,
    prepared: Option<(
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    )>,
    initiator_context_expiry: Option<u64>,
    remote_signing_context: Option<ManifoldCommonLanReciprocalEd25519Context>,
    local_current: Option<Value>,
    remote_current: Option<Value>,
    final_local_current: Option<Value>,
    prior_pair: Value,
    replies: Vec<(String, Value, String, Value)>,
    failure: Option<String>,
    completed: bool,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Body {
    #[serde(rename = "$schema")]
    schema: String,
    kind: String,
    request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    phase_request_id: Option<String>,
    sender_peer_id: String,
    receiver_peer_id: String,
    route_configuration_sha256: String,
    issued_at_ms: u64,
    payload: Value,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    body: Body,
    signature_hex: String,
}

struct Flight(Arc<AtomicBool>);
impl Drop for Flight {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}
fn flight(host: &Host) -> Result<Flight, String> {
    let flag = state(host)?.fresh.singleflight.clone();
    flag.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .map_err(|_| "fresh setup owned phase already executing")?;
    Ok(Flight(flag))
}

fn fields(kind: &str) -> Result<&'static [&'static str], String> {
    Ok(match kind {
        "prime_begin" => &["phase_id", "session_id", "nonce_a", "view"],
        "prime_begun" => &["view", "nonce_b", "prior_phase_id"],
        "prime_authorize" => &["context"],
        "prime_authorized" => &["signature"],
        "prime_commit" => &["request", "initiator_receipt"],
        "prime_committed" => &["receipt"],
        "prime_activate" => &["initiator_receipt", "responder_receipt", "ticket"],
        "prime_activated" => &["view", "ticket_sha256", "archive_ack"],
        "phase_query" => &[],
        "phase_queried" => &["observation"],
        "phase_fence" => &["ticket"],
        "phase_fenced" => &["ticket_sha256", "view", "nonce_b", "archive_ack"],
        "view" => &["session_id", "nonce_a", "view"],
        "viewed" => &["nonce_b", "view", "policy"],
        "authorize" => &["context"],
        "authorized" => &["signature"],
        "commit" => &["request", "initiator_receipt"],
        "committed" => &["receipt"],
        "prepare" => &["context", "signature"],
        "prepared" => &["signature", "context", "prepared_signature"],
        "finish" => &["signature", "initiator_current"],
        "finished" => &["current_session"],
        "complete" => &["local_current", "remote_current"],
        "completed" => &["session_id", "request_id", "current_session"],
        _ => return Err("fresh setup frame kind".into()),
    })
}

fn shape(body: &Body) -> Result<(), String> {
    if !matches!(
        ceremony_id::parse(&body.request_id)?,
        ceremony_id::CeremonyId::Finite { .. }
    ) {
        return Err("fresh setup requires canonical finite request identity".into());
    }
    let expected = fields(&body.kind)?;
    let actual = body
        .payload
        .as_object()
        .ok_or("fresh setup payload object")?;
    let protocol = match &body.phase_request_id {
        Some(phase) => {
            body.schema == SCHEMA_V2
                && matches!(
                    ceremony_id::parse(phase)?,
                    ceremony_id::CeremonyId::Finite { .. }
                )
        }
        None => body.schema == SCHEMA && !body.kind.starts_with("phase_"),
    };
    if !protocol
        || actual.len() != expected.len()
        || expected.iter().any(|key| !actual.contains_key(*key))
    {
        return Err("fresh setup closed frame shape".into());
    }
    Ok(())
}

fn bytes(body: &Body) -> Result<Vec<u8>, String> {
    let mut result = if body.phase_request_id.is_some() {
        DOMAIN_V2
    } else {
        DOMAIN
    }
    .to_vec();
    result.extend(serde_json::to_vec(body).map_err(safe_decode)?);
    Ok(result)
}

fn encode(host: &Host, kind: &str, id: &str, payload: Value) -> Result<Vec<u8>, String> {
    encode_inner(host, kind, id, payload, false, None)
}
fn encode_readonly(host: &Host, kind: &str, id: &str, payload: Value) -> Result<Vec<u8>, String> {
    if !matches!(kind, "phase_query" | "phase_queried") {
        return Err("readonly fresh frame kind differs".into());
    }
    encode_inner(host, kind, id, payload, true, None)
}
fn encode_inner(
    host: &Host,
    kind: &str,
    id: &str,
    payload: Value,
    readonly: bool,
    forced_phase: Option<&str>,
) -> Result<Vec<u8>, String> {
    // An already completed cached reply has no active setup reservation. It
    // still must not sign another frame after explicit exact-original Stop.
    if !readonly
        && process()
            .lock()
            .map_err(|_| "fresh frame stop gate poisoned")?
            .fresh_setup_stop_request
            .as_deref()
            == Some(id)
    {
        return Err("fresh frame refused after exact desired Stop".into());
    }
    let now = host.clock.now_ms()?;
    let current_phase = {
        let s = state(host)?;
        s.fresh
            .active
            .as_ref()
            .filter(|a| a.id == id)
            .map(|a| a.phase_id.clone())
            .or_else(|| s.fresh.preparation.as_ref().map(|p| p.phase_id.clone()))
            .unwrap_or_else(|| id.into())
    };
    let current_phase = forced_phase.map(str::to_owned).unwrap_or(current_phase);
    let v2 = true;
    let body = Body {
        schema: if v2 { SCHEMA_V2 } else { SCHEMA }.into(),
        kind: kind.into(),
        request_id: id.into(),
        phase_request_id: if v2 { Some(current_phase) } else { None },
        sender_peer_id: host.local_peer_id.clone(),
        receiver_peer_id: host.remote_peer_id.clone(),
        route_configuration_sha256: host.route_configuration_sha256.clone(),
        issued_at_ms: now.saturating_sub(ISSUE_BACKDATE_MS),
        payload,
    };
    shape(&body)?;
    let signature_hex = hex(&host.callbacks.sign_fresh_setup_frame(&bytes(&body)?)?);
    let mut result = if v2 { MAGIC_V2 } else { MAGIC }.to_vec();
    result.extend(
        serde_json::to_vec(&Envelope {
            body,
            signature_hex,
        })
        .map_err(safe_decode)?,
    );
    if result.len() > 128 * 1024 {
        return Err("fresh setup frame bounds".into());
    }
    Ok(result)
}

fn decode(host: &Host, input: &[u8]) -> Result<Body, String> {
    if !(input.starts_with(MAGIC) || input.starts_with(MAGIC_V2)) || input.len() > 128 * 1024 {
        return Err("fresh setup frame bounds".into());
    }
    let frame: Envelope = serde_json::from_slice(&input[MAGIC.len()..]).map_err(safe_decode)?;
    shape(&frame.body)?;
    if input.starts_with(MAGIC_V2) != frame.body.phase_request_id.is_some() {
        return Err("fresh frame wire/schema version differs".into());
    }
    let b = &frame.body;
    let now = host.clock.now_ms()?;
    if b.sender_peer_id != host.remote_peer_id
        || b.receiver_peer_id != host.local_peer_id
        || b.route_configuration_sha256 != host.route_configuration_sha256
        || b.issued_at_ms > now
        || b.schema != SCHEMA_V2
        || b.phase_request_id.is_none()
    {
        return Err("fresh setup frame actor route or time".into());
    }
    VerifyingKey::from_bytes(&host.remote_public_key)
        .map_err(|_| "fresh peer key")?
        .verify_strict(
            &bytes(b)?,
            &Signature::from_bytes(&decode_hex::<64>(&frame.signature_hex)?),
        )
        .map_err(|_| "fresh frame signature")?;
    Ok(frame.body)
}

fn exchange(
    host: &Host,
    kind: &str,
    response: &str,
    id: &str,
    payload: Value,
) -> Result<Value, String> {
    phase(host, id)?;
    let frame = encode(host, kind, id, payload)?;
    phase(host, id)?;
    let reply = host.callbacks.clone().exchange(&frame, 128 * 1024)?;
    phase(host, id)?;
    let b = decode(host, &reply)?;
    if b.kind != response || b.request_id != id {
        return Err("fresh setup reply binding".into());
    }
    let phase = active(host, id)?.phase_id;
    if b.phase_request_id.as_deref().unwrap_or(&b.request_id) != phase {
        return Err("fresh reply phase differs".into());
    }
    Ok(b.payload)
}

fn active(host: &Host, id: &str) -> Result<Attempt, String> {
    state(host)?
        .fresh
        .active
        .as_ref()
        .filter(|a| a.id == id)
        .cloned()
        .ok_or_else(|| "actual retained fresh setup absent".into())
}

fn update(host: &Host, id: &str, f: impl FnOnce(&mut Attempt)) -> Result<(), String> {
    let mut s = state(host)?;
    let a = s
        .fresh
        .active
        .as_mut()
        .filter(|a| a.id == id)
        .ok_or("fresh setup original changed")?;
    f(a);
    Ok(())
}

pub(super) fn mark_dispatch(host: &Host, id: &str) -> Result<(), String> {
    phase(host, id)?;
    let p = process()
        .lock()
        .map_err(|_| "fresh effect admission gate poisoned")?;
    if p.closing
        || p.fresh_setup_request.as_deref() != Some(id)
        || p.fresh_setup_stop_request.as_deref() == Some(id)
    {
        return Err("fresh exact effect admission refused after desired Stop".into());
    }
    let mut s = state(host)?;
    let r = s
        .fresh
        .reservation
        .as_mut()
        .filter(|r| r.id == id)
        .ok_or("fresh reservation journal absent")?;
    r.authority_or_signature_entered = true;
    Ok(())
}

// Each real prime writer has an immutable request before entry and its actual
// returned receipt retained before another fallible writer is admitted. A
// missing receipt never becomes permission to replay that entered request.
pub(super) fn prime_enter(host: &Host, step: &str, request: Value) -> Result<(), String> {
    let id = process()
        .lock()
        .map_err(|_| "prime process gate poisoned")?
        .fresh_setup_request
        .clone();
    let Some(id) = id else { return Ok(()) };
    let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
        serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
    let source_before =
        rusty_manifold_peer_runtime_host::fresh_prime_enrollment_source_sha256(&snapshot)
            .map_err(|_| "actual prime source projection invalid")?;
    let mut s = state(host)?;
    let r = s
        .fresh
        .reservation
        .as_mut()
        .filter(|r| r.id == id)
        .ok_or("prime reservation absent")?;
    if r.prime_pending.is_some() {
        return Err("prime original writer remains unresolved".into());
    }
    r.prime_pending = Some(
        json!({"step":step,"request":request,"entered":false,"source_before_sha256":source_before}),
    );
    Ok(())
}
pub(super) fn prime_dispatched(host: &Host, step: &str) -> Result<(), String> {
    let id = process()
        .lock()
        .map_err(|_| "prime process gate poisoned")?
        .fresh_setup_request
        .clone();
    let Some(id) = id else { return Ok(()) };
    let mut s = state(host)?;
    let r = s
        .fresh
        .reservation
        .as_mut()
        .filter(|r| r.id == id)
        .ok_or("prime reservation absent")?;
    let pending = r
        .prime_pending
        .as_mut()
        .filter(|p| p.get("step").and_then(Value::as_str) == Some(step))
        .ok_or("actual prime writer identity differs")?;
    pending["entered"] = json!(true);
    Ok(())
}
pub(super) fn prime_returned(host: &Host, step: &str, receipt: Value) -> Result<(), String> {
    let id = process()
        .lock()
        .map_err(|_| "prime process gate poisoned")?
        .fresh_setup_request
        .clone();
    let Some(id) = id else { return Ok(()) };
    let mut s = state(host)?;
    let r = s
        .fresh
        .reservation
        .as_mut()
        .filter(|r| r.id == id)
        .ok_or("prime reservation absent")?;
    let pending = r
        .prime_pending
        .as_ref()
        .filter(|p| {
            p.get("step").and_then(Value::as_str) == Some(step)
                && p.get("entered").and_then(Value::as_bool) == Some(true)
        })
        .ok_or("actual prime writer identity differs")?;
    let request = pending["request"].clone();
    r.prime_progress.push(
        json!({"step":step,"request":request,"receipt":receipt,"owner_snapshot_raw_sha256":null}),
    );
    r.prime_pending = None;
    // Receipt custody precedes this fallible read. If it fails, the real receipt
    // survives, but absent readback is never accepted as a source preimage.
    drop(s);
    let snapshot = host.authority.snapshot_json()?;
    let digest = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(&snapshot)
    );
    let mut s = state(host)?;
    let r = s
        .fresh
        .reservation
        .as_mut()
        .filter(|r| r.id == id)
        .ok_or("prime reservation changed")?;
    let row = r
        .prime_progress
        .last_mut()
        .filter(|p| p.get("step").and_then(Value::as_str) == Some(step))
        .ok_or("actual prime readback step differs")?;
    row["owner_snapshot_raw_sha256"] = json!(digest);
    Ok(())
}

fn record_failure(host: &Host, id: &str, error: &str) {
    if let Ok(mut s) = state(host) {
        let completed = s
            .fresh
            .active
            .as_ref()
            .is_some_and(|a| a.id == id && a.completed);
        if !completed {
            if let Some(r) = s.fresh.reservation.as_mut().filter(|r| r.id == id) {
                r.failure = Some(error.into());
            }
        }
        if let Some(a) = s
            .fresh
            .active
            .as_mut()
            .filter(|a| a.id == id && !a.completed)
        {
            a.failure = Some(error.into());
        }
    }
}

// This borrowed token has no public constructor and cannot outlive the actual HostLease.
// The process reservation excludes ordinary leases throughout Java/network callbacks.
struct FreshSetupPhase<'a> {
    host: &'a Host,
    request: &'a str,
}
fn phase<'a>(host: &'a Host, id: &'a str) -> Result<FreshSetupPhase<'a>, String> {
    host.capability.require_live()?;
    let p = process()
        .lock()
        .map_err(|_| "fresh process gate poisoned")?;
    if p.closing
        || p.initializing
        || p.lease_integrity_failed
        || p.fresh_setup_request.as_deref() != Some(id)
        || !p
            .host
            .as_ref()
            .is_some_and(|h| Arc::ptr_eq(&h.pair_state, &host.pair_state))
    {
        return Err("fresh setup actual owner reservation absent".into());
    }
    if p.fresh_setup_stop_request.as_deref() == Some(id) {
        return Err("fresh setup explicit desired Stop requested".into());
    }
    drop(p);
    Ok(FreshSetupPhase { host, request: id })
}

// Called only after a signed peer frame or verified signed request has joined
// this exact phase, or for the local constructor of those authenticated views.
fn authenticated_context_time(host: &Host, id: &str, issued: u64) -> Result<u64, String> {
    crate::embedded_duplex::preparation_clock::authenticated_context_time(
        issued,
        || host.clock.now_ms(),
        || phase(host, id).map(|_| ()),
    )
}
enum IncomingPhysicalScope<'a> {
    Initial,
    Registered,
    Preparation { prior: Option<&'a str> },
}
// The frame handler authenticates request/phase/route before this observation.
// Each Java route proves its real endpoint span; initial observation adopts nothing.
fn physical_observation(
    host: &Host,
    id: &str,
    phase_id: &str,
    scope: IncomingPhysicalScope<'_>,
) -> Result<Value, String> {
    if host.local_peer_id < host.remote_peer_id {
        return host.callbacks.fresh_setup_physical_eligibility();
    }
    let value = match scope {
        IncomingPhysicalScope::Initial => {
            host.callbacks.fresh_setup_initial_physical(id, phase_id)?
        }
        IncomingPhysicalScope::Registered => host
            .callbacks
            .fresh_setup_registered_physical(id, phase_id)?,
        IncomingPhysicalScope::Preparation { prior } => host
            .callbacks
            .fresh_setup_preparation_physical(id, prior, phase_id)?,
    };
    if value.get("original_binding")
        != Some(&json!({"kind":"incoming_fresh_setup","native_request_id":id}))
    {
        return Err("actual incoming physical binding differs".into());
    }
    Ok(value)
}

fn begin(host: &Host, id: &str, session: &str, nonce_a: &str) -> Result<(), String> {
    host.capability.require_live()?;
    let prepared = {
        let s = state(host)?;
        s.fresh.prepared_start.as_deref() == Some(id)
            && s.fresh.archive_reservation.is_none()
            && s.fresh.reservation.as_ref().is_some_and(|r| r.id == id)
    };
    // Preliminary reads have no Manifold/signature effect. They establish the
    // actual operator binding before taking a reservation which may need retirement.
    let local = peer_lifecycle::fresh_setup_eligibility(host)?;
    let physical = physical_observation(host, id, id, IncomingPhysicalScope::Initial)?;
    let original_binding = if host.local_peer_id < host.remote_peer_id {
        let binding = physical
            .get("original_binding")
            .ok_or("fresh operator Original binding absent")?
            .clone();
        let fields = binding
            .as_object()
            .ok_or("fresh operator Original binding object")?;
        let original = binding
            .get("original_id")
            .and_then(Value::as_str)
            .ok_or("fresh operator Original id absent")?;
        if fields.len() != 2
            || binding.get("kind").and_then(Value::as_str) != Some("operator")
            || original.len() != 32
            || !original
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err("fresh actual operator Original binding invalid".into());
        }
        binding
    } else {
        json!({"kind":"incoming_fresh_setup","native_request_id":id})
    };
    validate_physical(host, local, physical)?;
    let prior_pair = status_without_fresh(host)?;
    {
        let mut p = process()
            .lock()
            .map_err(|_| "fresh process gate poisoned")?;
        if p.closing
            || p.initializing
            || p.lease_integrity_failed
            || p.host_leases != 1
            || (p.fresh_setup_request.is_some()
                && !(prepared && p.fresh_setup_request.as_deref() == Some(id)))
        {
            return Err("fresh setup native owner busy or unresolved".into());
        }
        p.fresh_setup_request = Some(id.into());
    }
    {
        let mut s = state(host)?;
        if !prepared {
            if let Some(old) = s.fresh.reservation.take() {
                if !old.declined_before_dispatch {
                    return Err("prior native reservation lacks earned durable handoff".into());
                }
                s.fresh.prior_reservations.push(old);
                if s.fresh.prior_reservations.len() > 2 {
                    s.fresh.prior_reservations.remove(0);
                }
            }
            s.fresh.reservation = Some(Reservation {
                id: id.into(),
                session: session.into(),
                original_binding: original_binding.clone(),
                initial_original_binding: original_binding.clone(),
                authority_or_signature_entered: false,
                declined_before_dispatch: false,
                failure: None,
                prime_pending: None,
                prime_progress: Vec::new(),
                prime_origin: None,
                prime_joined: Vec::new(),
            });
        } else {
            s.fresh.prepared_start = None;
        }
    }
    let result = (|| -> Result<(), String> {
        if host.local_peer_id < host.remote_peer_id {
            host.callbacks.bind_fresh_setup_original(id)?;
        } else {
            host.callbacks
                .bind_incoming_fresh_setup_phase(id, None, id, "initial")?;
        }
        // Re-read after the exclusive process reservation. Before dispatch, a
        // failed prerequisite can release ONLY this effect-free reservation.
        let local = peer_lifecycle::fresh_setup_eligibility(host)?;
        let physical = physical_observation(host, id, id, IncomingPhysicalScope::Registered)?;
        if host.local_peer_id < host.remote_peer_id
            && physical.get("original_binding") != Some(&original_binding)
        {
            return Err("fresh actual operator Original changed".into());
        }
        let eligibility = validate_physical(host, local, physical)?;
        let initial_stage = state(host)?.stage;
        if initial_stage != Stage::Idle && initial_stage != Stage::Completed {
            return Err("fresh setup cannot replace an unresolved initial ceremony".into());
        }
        if pair_renewal::status(host)?
            .get("state")
            .and_then(Value::as_str)
            != Some("idle")
        {
            return Err("fresh setup cannot replace a retained renewal".into());
        }
        if initial_stage == Stage::Idle {
            let policy = host
                .authority
                .selected_fresh_pair_setup_policy()?
                .ok_or("fresh product policy absent")?;
            let raw = host.authority.snapshot_json()?;
            let source: Value = serde_json::from_str(&raw).map_err(safe_decode)?;
            if !source
                .pointer("/enrollment/credentials")
                .and_then(Value::as_array)
                .is_some_and(|v| v.is_empty())
                || !source
                    .pointer("/accepted_peers/peers")
                    .and_then(Value::as_array)
                    .is_some_and(|v| v.is_empty())
            {
                return Err("initial prime origin is not actual pristine enrollment/status".into());
            }
            let origin = json!({"$schema":"rusty.quest.embedded_duplex.fresh_prime_origin.v1",
                "native_request_id":id,"original_binding":original_binding,"config_sha256":host.config_sha256,
                "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
                "native_executor_generation":host.capability.generation,"observed_at_ms":host.clock.now_ms()?,
                "source_snapshot_raw_sha256":format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&raw)),
                "enrollment_selection":source.pointer("/trust_policy/fresh_setup_enrollment_selection").filter(|s|!s.is_null()).ok_or("actual constructor enrollment selection absent")?,
                "source_snapshot":source});
            state(host)?
                .fresh
                .reservation
                .as_mut()
                .ok_or("actual prime reservation absent")?
                .prime_origin = Some(origin);
            // Real ordinary initial enrollment uses its released300s validity.
            // Each actual writer journals admission/return before the next effect.
            prime_with_lifetime(
                host,
                host.clock.now_ms()?,
                id,
                policy.credential_validity_ms,
            )?;
        }
        let operation = fresh_pair_setup_digest(
            "rusty.quest.fresh_setup.native_operation.v1",
            &json!({"request_id":id,"session_id":session,"nonce_a":nonce_a,
                "local_peer_id":host.local_peer_id,"remote_peer_id":host.remote_peer_id,
                "config_sha256":host.config_sha256,"native_generation":host.capability.generation}),
        )?;
        let digest = fresh_pair_setup_digest(
            "rusty.quest.fresh_setup.native_eligibility.v1",
            &eligibility,
        )?;
        let local = serde_json::from_value(json!(host.local_peer_id)).map_err(safe_decode)?;
        let view = host.authority.fresh_pair_setup_view(
            &local,
            &peers(host)?,
            &operation,
            &digest,
            host.clock.now_ms()?,
        )?;
        state(host)?.fresh.active = Some(Attempt {
            id: id.into(),
            session: session.into(),
            phase_id: id.into(),
            transaction_id: String::new(),
            phase_fenced: false,
            linked_predecessor: None,
            fence_proof: None,
            nonce_a: nonce_a.into(),
            nonce_b: None,
            eligibility,
            original_binding: original_binding.clone(),
            view,
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
        });
        Ok(())
    })();
    if let Err(error) = &result {
        record_failure(host, id, error);
        let effect_free = {
            let mut s = state(host)?;
            let r = s
                .fresh
                .reservation
                .as_mut()
                .filter(|r| r.id == id)
                .ok_or("fresh reservation journal absent")?;
            if !r.authority_or_signature_entered {
                r.declined_before_dispatch = true;
                true
            } else {
                false
            }
        };
        if effect_free {
            let mut p = process()
                .lock()
                .map_err(|_| "fresh process gate poisoned")?;
            if p.fresh_setup_request.as_deref() != Some(id) {
                return Err("fresh effect-free reservation changed".into());
            }
            p.fresh_setup_request = None;
        }
    }
    result
}

fn peers(host: &Host) -> Result<Vec<rusty_manifold_model::DottedId>, String> {
    let mut ids = vec![host.local_peer_id.clone(), host.remote_peer_id.clone()];
    ids.sort();
    serde_json::from_value(json!(ids)).map_err(safe_decode)
}

fn validate_physical(host: &Host, local: Value, physical: Value) -> Result<Value, String> {
    if physical["$schema"] == "rusty.quest.embedded_duplex.fresh_setup_physical_eligibility.v2" {
        prepared_start::validate_physical(host, &local, &physical)?;
        return Ok(json!({"native":local,"java":physical}));
    }
    // The callback is a fixed app-owned typed producer. Its exact contract is joined,
    // not an operator boolean or an inferred absence from missing values.
    const FIELDS: [&str; 13] = [
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
    ];
    if !physical
        .as_object()
        .is_some_and(|o| o.len() == FIELDS.len() && FIELDS.iter().all(|k| o.contains_key(*k)))
        || physical.get("$schema").and_then(Value::as_str)
            != Some("rusty.quest.embedded_duplex.fresh_setup_physical_eligibility.v1")
        || physical
            .get("native_executor_generation")
            .and_then(Value::as_u64)
            != Some(host.capability.generation)
        || physical
            .get("app_process_generation")
            .and_then(Value::as_u64)
            != Some(host.capability.binding.generation)
        || physical.get("app_record_sha256").and_then(Value::as_str)
            != Some(host.capability.binding.record_sha256.as_str())
        || physical.get("config_sha256").and_then(Value::as_str)
            != Some(host.config_sha256.as_str())
        || physical.get("eligible").and_then(Value::as_bool) != Some(true)
        || physical
            .get("unresolved_original_count")
            .and_then(Value::as_u64)
            != Some(0)
        || physical
            .get("peer_resources_terminal")
            .and_then(Value::as_bool)
            != Some(true)
        || physical.get("renderer_terminal").and_then(Value::as_bool) != Some(true)
        || physical.get("control_terminal").and_then(Value::as_bool) != Some(true)
        || !matches!(
            physical.get("own_capture_state").and_then(Value::as_str),
            Some("live_independent" | "retired" | "not_created")
        )
        || physical.get("classification").and_then(Value::as_str)
            != local.pointer("/local/state").and_then(Value::as_str)
    {
        return Err("fresh setup actual Java physical/original proof unavailable".into());
    }
    Ok(json!({"native":local,"java":physical}))
}

fn sign_setup(
    token: &FreshSetupPhase<'_>,
    c: &ManifoldFreshPairSetupContext,
) -> Result<ManifoldFreshPairSetupSignature, String> {
    let host = token.host;
    let a = active(host, token.request)?;
    let policy = host
        .authority
        .selected_fresh_pair_setup_policy()?
        .ok_or("fresh product policy absent")?;
    let local = c
        .views
        .iter()
        .find(|v| v.local_peer_id.as_str() == host.local_peer_id)
        .ok_or("fresh local view absent")?;
    if c.schema_id.as_str() != "rusty.manifold.peer.fresh_pair_setup_context.v3"
        || c.request_id.as_str() != a.transaction_id
        || c.native_setup_request_id.as_str() != token.request
        || c.native_phase_id.as_str() != a.phase_id
        || rusty_manifold_peer::fresh_pair_setup_transaction_id(c)? != c.request_id
        || a.phase_fenced
        || c.peer_ids != peers(host)?
        || c.policy != policy
        || c.views.len() != 2
        || *local != a.view
    {
        return Err("fresh prepared context binding changed".into());
    }
    for (index, view) in c.views.iter().enumerate() {
        if view.local_peer_id != c.peer_ids[index]
            || view.observed_at_ms > c.issued_at_ms
            || view.prior_credentials.len() != 2
        {
            return Err("fresh setup view order or time".into());
        }
        for (j, credential) in view.prior_credentials.iter().enumerate() {
            let (key, public) = key_facts(host, c.peer_ids[j].as_str())?;
            if credential.peer_id != c.peer_ids[j]
                || credential.key_id.as_str() != key
                || credential.public_key_hex != public
            {
                return Err("fresh setup enrolled identity mismatch".into());
            }
        }
    }
    let now = authenticated_context_time(host, token.request, c.issued_at_ms)?;
    if c.issued_at_ms > now {
        return Err("fresh prepared context binding changed".into());
    }
    // Rejoin the exact actual owner bytes before signing, not merely the cached view.
    let actual = host.authority.fresh_pair_setup_view(
        &local.local_peer_id,
        &c.peer_ids,
        &local.native_operation_sha256,
        &local.native_eligibility_sha256,
        local.observed_at_ms,
    )?;
    if actual != *local {
        return Err("fresh source owner changed before signature".into());
    }
    let current_physical = validate_physical(
        host,
        peer_lifecycle::fresh_setup_eligibility(host)?,
        physical_observation(
            host,
            token.request,
            &a.phase_id,
            IncomingPhysicalScope::Registered,
        )?,
    )?;
    if current_physical != a.eligibility {
        return Err("fresh physical prerequisite changed before signing".into());
    }
    if a.context.as_ref().is_some_and(|old| old != c) {
        return Err("fresh retained context is immutable".into());
    }
    update(host, token.request, |a| a.context = Some(c.clone()))?;
    let validated = ValidatedFreshPairSetupSigning {
        signer_key_id: host.callbacks.key_id().to_owned(),
        signing_bytes: fresh_pair_setup_signing_bytes(c)?,
    };
    if host.local_peer_id < host.remote_peer_id {
        host.callbacks.bind_fresh_setup_context(
            token.request,
            &fresh_pair_setup_digest("rusty.manifold.peer.fresh_pair_setup_context.v3", c)?,
        )?;
    }
    let context_sha =
        fresh_pair_setup_digest("rusty.manifold.peer.fresh_pair_setup_context.v3", c)?;
    if host.local_peer_id < host.remote_peer_id {
        host.callbacks
            .bind_fresh_setup_operator_phase(token.request, &a.phase_id)?;
    }
    host.callbacks.bind_fresh_setup_phase_context(
        token.request,
        &a.phase_id,
        c.request_id.as_str(),
        "pair_setup",
        &context_sha,
    )?;
    mark_dispatch(host, token.request)?;
    let signature = host.callbacks.sign_validated_fresh_pair_setup(&validated)?;
    phase(host, token.request)?;
    serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_pair_setup_signature.v3",
        "signer_peer_id":host.local_peer_id,"signer_key_id":host.callbacks.key_id(),"signature_hex":hex(&signature)})).map_err(safe_decode)
}

fn apply_setup(
    host: &Host,
    id: &str,
    request: &ManifoldFreshPairSetupRequest,
) -> Result<ManifoldFreshPairSetupReceipt, String> {
    phase(host, id)?;
    verify_fresh_pair_setup_request(request)?;
    let a = active(host, id)?;
    if a.phase_fenced || a.context.as_ref() != Some(&request.context) {
        return Err("fresh setup exact signed context changed".into());
    }
    authenticated_context_time(host, id, request.context.issued_at_ms)?;
    let current_physical = validate_physical(
        host,
        peer_lifecycle::fresh_setup_eligibility(host)?,
        physical_observation(host, id, &a.phase_id, IncomingPhysicalScope::Registered)?,
    )?;
    if current_physical != a.eligibility {
        return Err("fresh physical prerequisite changed before apply".into());
    }
    if a.request.as_ref().is_some_and(|old| old != request) {
        return Err("fresh retained request changed".into());
    }
    if a.request.is_some() {
        let actual = host
            .authority
            .current_fresh_pair_setup_receipt(&request.context.request_id)?
            .ok_or("entered fresh request lacks owning receipt; no replay")?;
        if actual.request != *request {
            return Err("owning fresh receipt differs".into());
        }
        update(host, id, |a| a.local_setup = Some(actual.clone()))?;
        return Ok(actual);
    }
    update(host, id, |a| a.request = Some(request.clone()))?;
    mark_dispatch(host, id)?;
    let receipt = host
        .authority
        .apply_fresh_pair_setup(request, host.clock.now_ms()?)?;
    update(host, id, |a| {
        a.request = Some(request.clone());
        a.local_setup = Some(receipt.clone());
    })?;
    Ok(receipt)
}

pub(super) fn retained_closure(host: &Host) -> Result<Value, String> {
    let s = state(host)?;
    if s.fresh.singleflight.load(Ordering::SeqCst) {
        return Err("fresh original still executing".into());
    }
    let r = s
        .fresh
        .reservation
        .as_ref()
        .ok_or("fresh pending reservation absent")?;
    if r.declined_before_dispatch
        || s.fresh
            .active
            .as_ref()
            .is_some_and(|a| a.id == r.id && a.completed)
    {
        return Err("fresh original already terminal".into());
    }
    serde_json::to_value(&*s).map_err(safe_decode)
}

fn check_current(host: &Host, value: &Value, session: &str, expiry: u64) -> Result<(), String> {
    let receipt: rusty_manifold_peer::ManifoldPeerSessionCurrentReceiptV2 =
        serde_json::from_value(value.clone()).map_err(safe_decode)?;
    if receipt.schema_id.as_str() != "rusty.manifold.peer.session_current_receipt.v2"
        || !receipt.current
        || receipt.rejection_reason.is_some()
        || receipt.session_id.as_str() != session
        || receipt.expires_at_ms != Some(expiry)
        || expiry <= host.clock.now_ms()?
        || receipt.validated_at_ms > host.clock.now_ms()?
        || receipt.decision_id.is_none()
        || receipt.peer_ids != peers(host)?
        || receipt.topology_contract_id.as_ref().map(|id| id.as_str())
            != Some(COMMON_LAN_PAIR_TOPOLOGY_CONTRACT_ID)
    {
        return Err("fresh setup new current session receipt differs".into());
    }
    Ok(())
}

// Only authenticated peer-frame receipts (or their retained exact phase copy)
// reach these callsites. Their validated timestamp belongs to the peer's clock.
// Bind every non-time predicate before waiting, then rerun the unchanged strict
// validator with actual local time; no expiry, signature or authority is widened.
fn check_remote_current(
    host: &Host,
    id: &str,
    value: &Value,
    session: &str,
    expiry: u64,
) -> Result<(), String> {
    let receipt: rusty_manifold_peer::ManifoldPeerSessionCurrentReceiptV2 =
        serde_json::from_value(value.clone()).map_err(safe_decode)?;
    if receipt.schema_id.as_str() != "rusty.manifold.peer.session_current_receipt.v2"
        || !receipt.current
        || receipt.rejection_reason.is_some()
        || receipt.session_id.as_str() != session
        || receipt.expires_at_ms != Some(expiry)
        || expiry <= host.clock.now_ms()?
        || receipt.decision_id.is_none()
        || receipt.peer_ids != peers(host)?
        || receipt.topology_contract_id.as_ref().map(|id| id.as_str())
            != Some(COMMON_LAN_PAIR_TOPOLOGY_CONTRACT_ID)
    {
        return Err("fresh setup new current session receipt differs".into());
    }
    authenticated_context_time(host, id, receipt.validated_at_ms)?;
    check_current(host, value, session, expiry)
}

fn complete(host: &Host, id: &str) -> Result<Value, String> {
    phase(host, id)?;
    let a = active(host, id)?;
    if a.local_setup.is_none()
        || a.remote_setup.is_none()
        || a.local_current.is_none()
        || a.remote_current.is_none()
    {
        return Err("fresh setup bilateral result incomplete".into());
    }
    let expiry = a
        .local_current
        .as_ref()
        .and_then(|v| v.get("expires_at_ms"))
        .and_then(Value::as_u64)
        .ok_or("fresh local expiry absent")?;
    check_remote_current(
        host,
        id,
        a.remote_current
            .as_ref()
            .ok_or("fresh remote receipt absent")?,
        &a.session,
        expiry,
    )?;
    let session = serde_json::from_value(json!(a.session)).map_err(safe_decode)?;
    let actual = host
        .authority
        .current_common_lan_session(&session, host.clock.now_ms()?)?;
    if !actual.current {
        let encoded = super::super::operation_failure::session_not_current(
            "fresh_pair_setup",
            &a.session,
            serde_json::to_value(actual).map_err(safe_decode)?,
        )?;
        state(host)?.last_failure_cause = super::super::operation_failure::current_cause(&encoded);
        return Err(encoded);
    }
    let actual = serde_json::to_value(actual).map_err(safe_decode)?;
    check_current(host, &actual, &a.session, expiry)?;
    if actual.get("decision_id") != a.local_current.as_ref().and_then(|v| v.get("decision_id")) {
        return Err("fresh final actual session decision changed".into());
    }
    // Existing renewal ledger, counters, old receipts and old session authority remain.
    let mut p = process()
        .lock()
        .map_err(|_| "fresh process gate poisoned")?;
    if p.fresh_setup_request.as_deref() != Some(id)
        || p.fresh_setup_stop_request.as_deref() == Some(id)
    {
        return Err("fresh completion refused after desired Stop".into());
    }
    let mut s = state(host)?;
    s.stage = Stage::Completed;
    s.session_id = Some(a.session.clone());
    if !s.fresh.active.as_ref().is_some_and(|a| a.completed) {
        s.fresh.completed_setups = s
            .fresh
            .completed_setups
            .checked_add(1)
            .ok_or("fresh completed count exhausted")?;
    }
    if let Some(active) = s.fresh.active.as_mut() {
        active.completed = true;
        active.final_local_current = Some(actual.clone());
    }
    s.fresh.pending_phase = None;
    drop(s);
    p.fresh_setup_request = None;
    Ok(actual)
}

pub(super) fn run(host: &Host) -> Result<String, String> {
    let _flight = flight(host)?;
    if host.local_peer_id >= host.remote_peer_id {
        return Err("fresh setup initiator is lower packaged peer".into());
    }
    let id = ceremony_id::finite(&random_hex()?)?;
    let session = format!("session.duplex.{}", random_hex()?);
    let nonce_a = random_hex()?;
    {
        let mut s = state(host)?;
        if s.fresh.active.as_ref().is_some_and(|a| !a.completed) {
            return Err("fresh setup original unresolved".into());
        }
        drop(s);
        stage_completed_successor(host, &id, &session)?;
        archive_prior_completed(host).map_err(|error| {
            record_failure(host, &id, &error);
            error
        })?;
    }
    let result = (|| -> Result<String, String> {
        begin(host, &id, &session, &nonce_a)?;
        let a = active(host, &id)?;
        let viewed = exchange(
            host,
            "view",
            "viewed",
            &id,
            json!({"session_id":session,"nonce_a":nonce_a,"view":a.view}),
        )?;
        let nonce_b = viewed["nonce_b"]
            .as_str()
            .ok_or("fresh responder nonce absent")?
            .to_owned();
        decode_hex::<32>(&nonce_b)?;
        if nonce_a == nonce_b {
            return Err("fresh nonces collided".into());
        }
        let remote: ManifoldFreshPairSetupPeerView =
            serde_json::from_value(viewed["view"].clone()).map_err(safe_decode)?;
        let policy = host
            .authority
            .selected_fresh_pair_setup_policy()?
            .ok_or("fresh product policy absent")?;
        if serde_json::to_value(&policy).map_err(safe_decode)? != viewed["policy"] {
            return Err("fresh selected peer policy differs".into());
        }
        let mut views = vec![a.view, remote];
        views.sort_by(|a, b| a.local_peer_id.cmp(&b.local_peer_id));
        let observed = views
            .iter()
            .map(|view| view.observed_at_ms)
            .max()
            .ok_or("fresh views absent")?;
        let now = authenticated_context_time(host, &id, observed)?;
        let mut c:ManifoldFreshPairSetupContext=serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_pair_setup_context.v3",
            "request_id":id,"native_setup_request_id":id,"native_phase_id":id,"policy":policy,"peer_ids":peers(host)?,"views":views,"issued_at_ms":now})).map_err(safe_decode)?;
        c.request_id = rusty_manifold_peer::fresh_pair_setup_transaction_id(&c)?;
        update(host, &id, |a| {
            a.transaction_id = c.request_id.to_string();
            a.context = Some(c.clone());
            a.nonce_b = Some(nonce_b.clone());
        })?;
        finish_phase(host, &id, &session, &nonce_a, &nonce_b, &c)
    })();
    if let Err(error) = &result {
        record_failure(host, &id, error);
    }
    result
}

pub(super) fn handle_frame(host: &Host, input: &[u8]) -> Result<Vec<u8>, String> {
    let _flight = flight(host)?;
    let checked = decode(host, input)?;
    // Every authenticated phase/cached reply retains its ACTUAL endpoint task
    // span. A new view binds only after old completed custody is archived in
    // begin(), so it cannot be misclassified as an old request worker.
    let existing = {
        let s = state(host)?;
        s.fresh
            .active
            .as_ref()
            .is_some_and(|a| a.id == checked.request_id)
    };
    if checked.kind.starts_with("prime_") {
        let result = preparation::handle(host, checked.clone());
        if let Err(error) = &result {
            record_failure(host, &checked.request_id, error);
        }
        return result;
    }
    if checked.kind == "phase_query" || checked.kind == "phase_fence" {
        if checked.kind == "phase_query" {
            let (known, phase_id) = {
                let s = state(host)?;
                (
                    s.fresh.reservation.is_some(),
                    s.fresh
                        .active
                        .as_ref()
                        .map(|a| a.phase_id.clone())
                        .or_else(|| s.fresh.preparation.as_ref().map(|a| a.phase_id.clone()))
                        .unwrap_or_else(|| checked.request_id.clone()),
                )
            };
            host.callbacks.bind_incoming_fresh_setup_phase(
                &checked.request_id,
                None,
                &phase_id,
                if known { "current_phase" } else { "initial" },
            )?;
        }
        return continuation::handle(host, checked);
    }
    let phase = state(host)?
        .fresh
        .active
        .as_ref()
        .filter(|a| a.id == checked.request_id)
        .map(|a| (a.phase_id.clone(), a.phase_fenced));
    if let Some((phase, fenced)) = phase {
        if fenced
            || checked
                .phase_request_id
                .as_deref()
                .unwrap_or(&checked.request_id)
                != phase
        {
            return Err("old fresh phase is fenced".into());
        }
    }
    if checked.kind != "view" {
        active(host, &checked.request_id)?;
        host.callbacks.bind_incoming_fresh_setup_phase(
            &checked.request_id,
            None,
            &active(host, &checked.request_id)?.phase_id,
            "current_phase",
        )?;
    } else if existing {
        host.callbacks.bind_incoming_fresh_setup_phase(
            &checked.request_id,
            None,
            &active(host, &checked.request_id)?.phase_id,
            "current_phase",
        )?;
    }
    {
        let p = process()
            .lock()
            .map_err(|_| "fresh process gate poisoned")?;
        if p.fresh_setup_stop_request.as_deref() == Some(checked.request_id.as_str()) {
            return Err("fresh setup explicit desired Stop requested".into());
        }
    }
    let result = handle_frame_inner(host, input);
    if let Err(error) = &result {
        record_failure(host, &checked.request_id, error);
    }
    result
}
// A view precedes the two-view, content-addressed Manifold transaction. Its
// native ceremony ID must never be queried as a transaction/absence key.
// Instead join the signed sender view to the actual selected pair's current
// invalidation floor. Exact active retries are handled before this gate;
// unresolved/fenced phases and durable predecessor custody retain their own
// guards. The eventual signed request still validates the complete preimage.
fn require_current_view_floor(host: &Host, value: &Value) -> Result<(), String> {
    let remote: ManifoldFreshPairSetupPeerView =
        serde_json::from_value(value.clone()).map_err(safe_decode)?;
    if remote.schema_id.as_str() != "rusty.manifold.peer.fresh_pair_setup_peer_view.v2"
        || remote.local_peer_id.as_str() != host.remote_peer_id
    {
        return Err("fresh incoming view selected peer differs".into());
    }
    let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
        serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
    if !snapshot
        .trust_policy
        .fresh_setup_local_peer_id
        .as_ref()
        .is_some_and(|id| id.as_str() == host.local_peer_id)
    {
        return Err("fresh incoming view actual owner differs".into());
    }
    let pair = peers(host)?;
    let floors = snapshot
        .reciprocal_ed25519
        .fresh_setup_fences
        .iter()
        .filter(|floor| floor.peer_ids == pair)
        .collect::<Vec<_>>();
    if floors.len() > 1 {
        return Err("fresh incoming view actual pair floor ambiguous".into());
    }
    match (floors.first().copied(), remote.prior_fence.as_ref()) {
        (Some(actual), Some(observed))
            if observed.schema_id == actual.schema_id
                && observed.peer_ids == pair
                && remote.prior_setup_epoch == actual.setup_epoch
                && observed.setup_epoch == actual.setup_epoch
                && observed.setup_request_id == actual.setup_request_id
                && observed.context_sha256 == actual.context_sha256 =>
        {
            Ok(())
        }
        (None, None) if remote.prior_setup_epoch == 0 => {
            let setup = snapshot
                .fresh_pair_setup
                .as_ref()
                .ok_or("fresh incoming view actual setup state absent")?;
            // Positive actual empty-setup history, not a missing-receipt or
            // archive-lookup absence grant. Preparation-only archives may exist.
            if !setup.receipts.is_empty()
                || setup
                    .archive
                    .as_ref()
                    .is_some_and(|cursor| cursor.setup_receipts != 0)
            {
                return Err("fresh incoming view actual setup history is not pristine".into());
            }
            Ok(())
        }
        _ => Err("fresh incoming view current pair floor differs".into()),
    }
}

fn handle_frame_inner(host: &Host, input: &[u8]) -> Result<Vec<u8>, String> {
    let b = decode(host, input)?;
    let id = b.request_id.as_str();
    if host.local_peer_id <= host.remote_peer_id {
        return Err("fresh responder role differs".into());
    }
    // A retry returns the exact retained result, never re-signs/reapplies its phase.
    let cached = {
        let s = state(host)?;
        s.fresh
            .active
            .as_ref()
            .filter(|a| a.id == id)
            .and_then(|a| a.replies.iter().find(|(kind, _, _, _)| kind == &b.kind))
            .cloned()
    };
    if let Some((_, old, response, payload)) = cached {
        if old != b.payload {
            return Err("fresh retry changed original payload".into());
        }
        return encode(host, &response, id, payload);
    }
    let (response, payload) = match b.kind.as_str() {
        "view" => {
            let session = b.payload["session_id"]
                .as_str()
                .ok_or("fresh session absent")?;
            let suffix = session
                .strip_prefix("session.duplex.")
                .ok_or("fresh session namespace")?;
            decode_hex::<32>(suffix)?;
            let nonce_a = b.payload["nonce_a"].as_str().ok_or("fresh nonce absent")?;
            decode_hex::<32>(nonce_a)?;
            {
                let mut s = state(host)?;
                if s.fresh.active.as_ref().is_some_and(|a| !a.completed) {
                    return Err("fresh original unresolved".into());
                }
                drop(s);
                require_current_view_floor(host, &b.payload["view"])?;
                stage_completed_successor(host, id, session)?;
                archive_prior_completed(host).map_err(|error| {
                    record_failure(host, id, &error);
                    error
                })?;
            }
            begin(host, id, session, nonce_a)?;
            let nonce_b = random_hex()?;
            if nonce_a == nonce_b {
                return Err("fresh nonces collided".into());
            }
            update(host, id, |a| a.nonce_b = Some(nonce_b.clone()))?;
            (
                "viewed",
                json!({"nonce_b":nonce_b,"view":active(host,id)?.view,"policy":host.authority.selected_fresh_pair_setup_policy()?.ok_or("fresh policy absent")?}),
            )
        }
        "authorize" => {
            let c: ManifoldFreshPairSetupContext =
                serde_json::from_value(b.payload["context"].clone()).map_err(safe_decode)?;
            if c.native_setup_request_id.as_str() != id
                || c.native_phase_id.as_str() != active(host, id)?.phase_id
                || rusty_manifold_peer::fresh_pair_setup_transaction_id(&c)? != c.request_id
            {
                return Err("fresh exact content-addressed transaction differs".into());
            }
            update(host, id, |a| a.transaction_id = c.request_id.to_string())?;
            let signature = sign_setup(&phase(host, id)?, &c)?;
            update(host, id, |a| a.context = Some(c))?;
            ("authorized", json!({"signature":signature}))
        }
        "commit" => {
            let request: ManifoldFreshPairSetupRequest =
                serde_json::from_value(b.payload["request"].clone()).map_err(safe_decode)?;
            let remote: ManifoldFreshPairSetupReceipt =
                serde_json::from_value(b.payload["initiator_receipt"].clone())
                    .map_err(safe_decode)?;
            rusty_manifold_peer_runtime_host::validate_fresh_pair_setup_receipt(&remote)
                .map_err(|_| "fresh initiator setup receipt semantic proof")?;
            if remote.request != request || remote.local_peer_id.as_str() != host.remote_peer_id {
                return Err("fresh initiator setup receipt differs".into());
            }
            let receipt = apply_setup(host, id, &request)?;
            update(host, id, |a| a.remote_setup = Some(remote))?;
            ("committed", json!({"receipt":receipt}))
        }
        "prepare" => {
            phase(host, id)?;
            let a = active(host, id)?;
            if a.local_setup.is_none() || a.remote_setup.is_none() {
                return Err("fresh setup commit required".into());
            }
            let ca = parse_context(&b.payload["context"])?;
            let initiator_signature = parse_signature(&b.payload["signature"])?;
            if !remote_correlation_matches(
                host,
                ca.correlation_id.as_str(),
                format!("correlation.{}", a.transaction_id),
            )? || ca.initiator.device_nonce_hex != a.nonce_a
                || Some(&ca.responder.device_nonce_hex) != a.nonce_b.as_ref()
            {
                return Err("fresh initiator reciprocal challenge differs".into());
            }
            remember_remote_signing_context(host, id, &ca)?;
            let local_signature = sign(host, &ca)?;
            if !prepared_peer_signature_matches(
                &initiator_signature,
                &local_signature,
                &host.remote_peer_id,
                &host.remote_key_id,
            ) {
                return Err("fresh initiator signature differs".into());
            }
            let (cb, sb) = prepare(
                host,
                &a.transaction_id,
                a.nonce_b.as_deref().ok_or("fresh local nonce absent")?,
                &a.nonce_a,
            )?;
            update(host, id, |a| {
                a.prepared = Some((cb.clone(), sb.clone()));
                a.initiator_context_expiry = Some(ca.expires_at_ms);
            })?;
            (
                "prepared",
                json!({"signature":local_signature,"context":cb,"prepared_signature":sb}),
            )
        }
        "finish" => {
            phase(host, id)?;
            let a = active(host, id)?;
            let (context, local) = a.prepared.ok_or("fresh exact prepared context absent")?;
            let expiry = mutual_signed_session_expiry(
                a.initiator_context_expiry
                    .ok_or("fresh initiator expiry absent")?,
                context.expires_at_ms,
                host.clock.now_ms()?,
            )?;
            check_remote_current(
                host,
                id,
                &b.payload["initiator_current"],
                &a.session,
                expiry,
            )?;
            let remote = parse_signature(&b.payload["signature"])?;
            let current = apply(
                host,
                &a.transaction_id,
                &a.session,
                context,
                local,
                remote,
                expiry,
            )?;
            update(host, id, |a| {
                a.local_current = Some(current.clone());
                a.remote_current = Some(b.payload["initiator_current"].clone());
            })?;
            ("finished", json!({"current_session":current}))
        }
        "complete" => {
            let a = active(host, id)?;
            if a.local_current.as_ref() != Some(&b.payload["remote_current"])
                || a.remote_current.as_ref() != Some(&b.payload["local_current"])
            {
                return Err("fresh bilateral current originals differ".into());
            }
            let current = complete(host, id)?;
            (
                "completed",
                json!({"session_id":a.session,"request_id":id,"current_session":current}),
            )
        }
        _ => return Err("fresh request kind invalid".into()),
    };
    update(host, id, |a| {
        a.replies.push((
            b.kind.clone(),
            b.payload.clone(),
            response.into(),
            payload.clone(),
        ))
    })?;
    encode(host, response, id, payload)
}

fn status_without_fresh(host: &Host) -> Result<Value, String> {
    let s = state(host)?;
    Ok(
        json!({"stage":format!("{:?}",s.stage),"ceremony_id":s.ceremony_id,"session_id":s.session_id,
        "nonce_a":s.nonce_a,"nonce_b":s.nonce_b,"deadline_ms":s.deadline_ms,
        "last_step":s.last_step,"last_failure_code":s.last_failure_code,"last_failure_cause":s.last_failure_cause}),
    )
}

pub(super) fn status(host: &Host) -> Result<Value, String> {
    let s = state(host)?;
    let a = s.fresh.active.as_ref();
    let r = s.fresh.reservation.as_ref();
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_pair_setup_status.v2",
        "state":a.map_or_else(||if r.is_some_and(|r|!r.declined_before_dispatch){"pending_before_view"}else{"idle"},|a|if a.completed{"completed"}else{"pending"}),
        "request_id":a.map(|a|&a.id).or_else(||r.map(|r|&r.id)),
        "session_id":a.map(|a|&a.session).or_else(||r.map(|r|&r.session)),
        "reservation":r.map(reservation_projection).transpose()?,"failure":a.and_then(|a|a.failure.as_ref()).or_else(||r.and_then(|r|r.failure.as_ref())),
        "local_setup":a.and_then(|a|a.local_setup.as_ref()),"remote_setup":a.and_then(|a|a.remote_setup.as_ref()),
        "local_current":a.and_then(|a|a.local_current.as_ref()),"remote_current":a.and_then(|a|a.remote_current.as_ref()),
        "final_local_current":a.and_then(|a|a.final_local_current.as_ref()),
        "prior_pair":a.map(|a|&a.prior_pair),"eligibility":a.map(|a|&a.eligibility),
        "completed_setups":s.fresh.completed_setups,
        "phase_request_id":a.map(|a|&a.phase_id),"phase_fenced":a.map(|a|a.phase_fenced),
        "linked_predecessor":a.map(continuation::predecessor_projection).transpose()?.flatten(),
        "phase_archive":s.fresh.history,"pending_phase":s.fresh.pending_phase.as_ref().map(continuation::ticket_digest).transpose()?,
        "preparation":s.fresh.preparation.as_ref().map(preparation::projection).transpose()?}),
    )
}

#[derive(Clone, Default, Serialize)]
struct PhaseArchiveCursor {
    total: u64,
    first: Option<Value>,
    last: Option<Value>,
    recent: Vec<Value>,
    history_sha256: Option<String>,
}

fn archive_prior_completed(host: &Host) -> Result<(), String> {
    let (old, prior) = {
        let s = state(host)?;
        (s.fresh.active.clone(), s.fresh.history.clone())
    };
    let Some(old) = old else { return Ok(()) };
    if !old.completed {
        return Err("fresh prior phase remains unresolved".into());
    }
    let transaction = old
        .request
        .as_ref()
        .ok_or("completed fresh request absent")?
        .context
        .request_id
        .clone();
    let actual = host
        .authority
        .current_fresh_pair_setup_receipt(&transaction)?
        .ok_or("actual completed fresh transaction missing")?;
    if old.local_setup.as_ref() != Some(&actual) {
        return Err("actual completed fresh transaction changed".into());
    }
    let renewal_custody = pair_renewal::completed_for_archive(host, &old.session)?;
    let old_phase_state = serde_json::to_value(&old).map_err(safe_decode)?;
    let mut phase_state = old_phase_state.clone();
    if let Some(custody) = &renewal_custody {
        phase_state.as_object_mut().ok_or("completed phase metadata object")?
            .insert("completed_renewal_custody".into(),custody.clone());
    }
    let next_total = prior.total.checked_add(1).ok_or("fresh archived phase count exhausted")?;
    let authority: Value =
        serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
    let (native_reservation, preparation) = {
        let s = state(host)?;
        (
            s.fresh
                .archive_reservation
                .clone()
                .or_else(|| s.fresh.reservation.clone()),
            s.fresh.preparation.clone(),
        )
    };
    let body = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_phase_archive.v2",
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,
        "native_request_id":old.id,"phase_request_id":old.phase_id,"original_binding":old.original_binding,
        "phase_status":"completed","phase_state":phase_state,"authority_snapshot":authority,"prior_archive_projection":prior,
        "native_reservation":native_reservation,"preparation":preparation});
    let raw = body.to_string();
    let digest = format!(
        "sha256:{}",
        rusty_quest_broker_authority::packaged_json_sha256(&raw)
    );
    let ack = host.callbacks.archive_fresh_setup_phase(&raw)?;
    const FIELDS: [&str; 10] = [
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
        .is_some_and(|o| o.len() == FIELDS.len() && FIELDS.iter().all(|k| o.contains_key(*k)))
        || ack.get("$schema").and_then(Value::as_str)
            != Some("rusty.quest.embedded_duplex.fresh_setup_phase_archive_retained.v2")
        || ack.get("body_sha256").and_then(Value::as_str) != Some(digest.as_str())
        || ack.get("app_process_generation") != body.get("app_process_generation")
        || ack.get("app_record_sha256") != body.get("app_record_sha256")
        || ack.get("native_executor_generation") != body.get("native_executor_generation")
        || ack.get("config_sha256") != body.get("config_sha256")
        || ack.get("native_request_id") != body.get("native_request_id")
        || ack.get("phase_request_id") != body.get("phase_request_id")
        || ack.get("original_binding") != body.get("original_binding")
        || !ack
            .get("storage_identity")
            .and_then(Value::as_str)
            .is_some_and(|s| !s.is_empty())
    {
        return Err("actual fresh phase durable custody ACK differs".into());
    }
    host.capability.require_live()?;
    // No next phase is admitted if this actual owner archive cannot commit.
    // Exact Native phase bytes have already transferred before M compaction.
    let next = state(host)?
        .fresh
        .reservation
        .as_ref()
        .filter(|r| r.id != old.id)
        .map(|r| r.id.clone());
    if let Some(next) = next {
        mark_dispatch(host, &next)?;
    }
    history_custody::archive_setup(host, &old)?;
    // The exact retry cache remains live through both durable custody calls.
    // Only local in-memory metadata locks are held for the final atomic join.
    let _lifetime_guard = pair_renewal::archived_lifetime_guard(host,renewal_custody.as_ref())?;
    let mut s = state(host)?;
    let current_phase_state = s.fresh.active.as_ref().map(serde_json::to_value)
        .transpose().map_err(safe_decode)?;
    if s.fresh.history.total != prior.total
        || s.fresh.history.history_sha256 != prior.history_sha256
        || !s
            .fresh
            .active
            .as_ref()
            .is_some_and(|a| a.completed && a.id == old.id)
        || current_phase_state.as_ref() != Some(&old_phase_state)
    {
        return Err("fresh archive owner changed during handoff".into());
    }
    pair_renewal::finish_archived(&mut s,renewal_custody.as_ref())?;
    let history = &mut s.fresh.history;
    history.history_sha256=Some(format!("sha256:{}",rusty_quest_broker_authority::packaged_json_sha256(&json!({
        "$schema":"rusty.quest.embedded_duplex.fresh_setup_phase_archive_chain.v1","previous":prior.history_sha256,"archive":ack}).to_string())));
    history.total = next_total;
    if history.first.is_none() {
        history.first = Some(ack.clone());
    }
    history.last = Some(ack.clone());
    history.recent.push(ack);
    if history.recent.len() > 2 {
        history.recent.remove(0);
    }
    s.fresh.active = None;
    s.fresh.preparation = None;
    if s.fresh.reservation.as_ref().is_some_and(|r| r.id == old.id) {
        s.fresh.reservation = None;
    }
    s.fresh.archive_reservation = None;
    Ok(())
}

fn finish_phase(
    host: &Host,
    id: &str,
    session: &str,
    nonce_a: &str,
    nonce_b: &str,
    c: &ManifoldFreshPairSetupContext,
) -> Result<String, String> {
    let signed = exchange(host, "authorize", "authorized", &id, json!({"context":c}))?;
    let remote_signature: ManifoldFreshPairSetupSignature =
        serde_json::from_value(signed["signature"].clone()).map_err(safe_decode)?;
    let local_signature = sign_setup(&phase(host, &id)?, &c)?;
    let mut signatures = vec![local_signature, remote_signature];
    signatures.sort_by(|a, b| a.signer_peer_id.cmp(&b.signer_peer_id));
    let request:ManifoldFreshPairSetupRequest=serde_json::from_value(json!({"$schema":"rusty.manifold.peer.fresh_pair_setup_request.v3","context":c,"signatures":signatures})).map_err(safe_decode)?;
    let local_receipt = apply_setup(host, &id, &request)?;
    let committed = exchange(
        host,
        "commit",
        "committed",
        &id,
        json!({"request":request,"initiator_receipt":local_receipt}),
    )?;
    let remote_receipt: ManifoldFreshPairSetupReceipt =
        serde_json::from_value(committed["receipt"].clone()).map_err(safe_decode)?;
    rusty_manifold_peer_runtime_host::validate_fresh_pair_setup_receipt(&remote_receipt)
        .map_err(|_| "fresh remote setup receipt semantic proof")?;
    if remote_receipt.request != request
        || remote_receipt.local_peer_id.as_str() != host.remote_peer_id
    {
        return Err("fresh remote setup receipt differs".into());
    }
    update(host, &id, |a| a.remote_setup = Some(remote_receipt))?;
    let transaction = active(host, id)?.transaction_id;
    let (ca, sa) = prepare(host, &transaction, &nonce_a, &nonce_b)?;
    let prepared = exchange(
        host,
        "prepare",
        "prepared",
        &id,
        json!({"context":ca,"signature":sa}),
    )?;
    let sb_for_a = parse_signature(&prepared["signature"])?;
    let cb = parse_context(&prepared["context"])?;
    let sb = parse_signature(&prepared["prepared_signature"])?;
    if !remote_correlation_matches(
        host,
        cb.correlation_id.as_str(),
        format!("correlation.{transaction}"),
    )? || cb.initiator.device_nonce_hex != nonce_b
        || cb.responder.device_nonce_hex != nonce_a
    {
        return Err("fresh reciprocal peer challenge differs".into());
    }
    remember_remote_signing_context(host, id, &cb)?;
    let sa_for_b = sign(host, &cb)?;
    if !prepared_peer_signature_matches(&sb, &sa_for_b, &host.remote_peer_id, &host.remote_key_id) {
        return Err("fresh peer context signature differs".into());
    }
    let expiry =
        mutual_signed_session_expiry(ca.expires_at_ms, cb.expires_at_ms, host.clock.now_ms()?)?;
    let local_current = apply(host, &transaction, &session, ca, sa, sb_for_a, expiry)?;
    update(host, &id, |a| a.local_current = Some(local_current.clone()))?;
    let finished = exchange(
        host,
        "finish",
        "finished",
        &id,
        json!({"signature":sa_for_b,"initiator_current":local_current}),
    )?;
    let remote_current = finished["current_session"].clone();
    check_remote_current(host, id, &remote_current, &session, expiry)?;
    check_current(host, &local_current, &session, expiry)?;
    update(host, &id, |a| {
        a.remote_current = Some(remote_current.clone())
    })?;
    let done = exchange(
        host,
        "complete",
        "completed",
        &id,
        json!({"local_current":local_current,"remote_current":remote_current}),
    )?;
    if done["session_id"].as_str() != Some(session) || done["request_id"].as_str() != Some(id) {
        return Err("fresh completion original differs".into());
    }
    let final_remote = done["current_session"].clone();
    check_remote_current(host, id, &final_remote, &session, expiry)?;
    let final_local = complete(host, &id)?;
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.fresh_pair_setup_result.v2","request_id":id,"session_id":session,
            "phase_request_id":active(host,id)?.phase_id,"phase_archive":state(host)?.fresh.history,
            "state":"peer_session_current_route_unverified","local_current_session":local_current,"remote_current_session":remote_current,
            "final_local_current_session":final_local,"final_remote_current_session":final_remote,
            "route_current":false,"media_effect_proven":false}).to_string())
}

pub(super) fn is_actual_transaction(
    host: &Host,
    id: &str,
    transaction: &str,
) -> Result<bool, String> {
    Ok(state(host)?
        .fresh
        .active
        .as_ref()
        .is_some_and(|a| a.id == id && a.transaction_id == transaction && !a.phase_fenced))
}
fn reservation_projection(r: &Reservation) -> Result<Value, String> {
    let pending=r.prime_pending.as_ref().map(|p|json!({"step":p["step"],"entered":p["entered"],"request_id":p["request"]["request_id"],"source_before_sha256":p["source_before_sha256"]}));
    let rows=r.prime_progress.iter().map(|p|Ok(json!({"step":p["step"],"request_id":p["request"]["request_id"],
        "receipt_sha256":fresh_pair_setup_digest("rusty.quest.embedded_duplex.prime_receipt.v1",&p["receipt"])?,"owner_snapshot_raw_sha256":p["owner_snapshot_raw_sha256"]}))).collect::<Result<Vec<_>,String>>()?;
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_reservation_status.v1","request_id":r.id,"session_id":r.session,
        "current_original_binding":r.original_binding,"initial_original_binding":r.initial_original_binding,
        "authority_or_signature_entered":r.authority_or_signature_entered,"declined_before_dispatch":r.declined_before_dispatch,"failure":r.failure,
        "prime_pending":pending,"prime_progress":rows,"prime_origin_sha256":r.prime_origin.as_ref().map(|v|fresh_pair_setup_digest("rusty.quest.embedded_duplex.prime_origin_custody.v1",v)).transpose()?,
        "prime_joined_count":r.prime_joined.len(),"reservation_sha256":fresh_pair_setup_digest("rusty.quest.embedded_duplex.reservation_custody.v1",r)?}),
    )
}
fn reservation_authority_preimage(r: &Reservation) -> Value {
    // Diagnostic failure text is audit data, not a new admission condition.
    json!({"id":r.id,"session":r.session,"original_binding":r.original_binding,"initial_original_binding":r.initial_original_binding,
        "authority_or_signature_entered":r.authority_or_signature_entered,"declined_before_dispatch":r.declined_before_dispatch,
        "prime_pending":r.prime_pending,"prime_progress":r.prime_progress,"prime_origin":r.prime_origin,"prime_joined":r.prime_joined})
}

fn encode_at_phase(
    host: &Host,
    kind: &str,
    id: &str,
    payload: Value,
    phase_id: &str,
) -> Result<Vec<u8>, String> {
    let actual = {
        let s = state(host)?;
        s.fresh
            .preparation
            .as_ref()
            .is_some_and(|a| a.phase_id == phase_id)
            || s.fresh
                .active
                .as_ref()
                .is_some_and(|a| a.id == id && a.phase_id == phase_id)
    };
    if !actual {
        return Err("encoded phase lacks actual retained owner".into());
    }
    encode_inner(host, kind, id, payload, false, Some(phase_id))
}

// Stage a NEW setup's native custody before a fallible historical transfer.
// The old completed phase remains immutable in its separate single archive slot.
fn stage_completed_successor(host: &Host, id: &str, session: &str) -> Result<bool, String> {
    let completed = state(host)?
        .fresh
        .active
        .as_ref()
        .filter(|a| a.completed)
        .map(|a| a.id.clone());
    let Some(old_id) = completed else {
        return Ok(false);
    };
    if state(host)?
        .fresh
        .active
        .as_ref()
        .is_some_and(|a| a.id == id)
    {
        return Err("completed Native initial request replay".into());
    }
    if host.local_peer_id > host.remote_peer_id {
        host.callbacks
            .stage_incoming_completed_fresh_setup_successor(&old_id, id)?;
    }
    let physical = physical_observation(host, id, id, IncomingPhysicalScope::Initial)?;
    validate_physical(
        host,
        peer_lifecycle::fresh_setup_eligibility(host)?,
        physical.clone(),
    )?;
    let binding = if host.local_peer_id < host.remote_peer_id {
        let b = physical["original_binding"].clone();
        if !b.as_object().is_some_and(|o| {
            o.len() == 2
                && o.get("kind").and_then(Value::as_str) == Some("operator")
                && o.get("original_id")
                    .and_then(Value::as_str)
                    .is_some_and(|s| {
                        s.len() == 32
                            && s.bytes()
                                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                    })
        }) {
            return Err("new completed-setup Original binding absent".into());
        }
        b
    } else {
        json!({"kind":"incoming_fresh_setup","native_request_id":id})
    };
    {
        let mut p = process()
            .lock()
            .map_err(|_| "successor custody process gate poisoned")?;
        if p.closing
            || p.initializing
            || p.lease_integrity_failed
            || p.host_leases != 1
            || p.fresh_setup_request.is_some()
            || !p
                .host
                .as_ref()
                .is_some_and(|h| Arc::ptr_eq(&h.pair_state, &host.pair_state))
        {
            return Err("new completed-setup owner is busy".into());
        }
        let mut s = state(host)?;
        if s.fresh.archive_reservation.is_some() || s.fresh.prepared_start.is_some() {
            return Err("prior successor archive is pending".into());
        }
        let old = s
            .fresh
            .active
            .as_ref()
            .filter(|a| a.completed && a.id == old_id)
            .ok_or("completed Native phase changed")?;
        if !s.fresh.reservation.as_ref().is_some_and(|r| r.id == old.id) {
            return Err("completed reservation original missing".into());
        }
        s.fresh.archive_reservation = s.fresh.reservation.take();
        s.fresh.reservation = Some(Reservation {
            id: id.into(),
            session: session.into(),
            original_binding: binding.clone(),
            initial_original_binding: binding,
            authority_or_signature_entered: false,
            declined_before_dispatch: false,
            failure: None,
            prime_pending: None,
            prime_progress: Vec::new(),
            prime_origin: None,
            prime_joined: Vec::new(),
        });
        s.fresh.prepared_start = Some(id.into());
        p.fresh_setup_request = Some(id.into());
    }
    if host.local_peer_id < host.remote_peer_id {
        if let Err(error) = host.callbacks.bind_fresh_setup_original(id) {
            record_failure(host, id, &error);
            return Err(error);
        }
    }
    // Incoming NEW-ID binding deliberately waits until old completed phase
    // archive ACK; Java's real old-task/next-ID barrier is not bypassed.
    Ok(true)
}

// Actual retained fresh phase, not an elapsed-age waiver or a caller flag.
pub(super) fn signing_binding(
    host: &Host,
    c: &ManifoldCommonLanReciprocalEd25519Context,
    source: &str,
) -> Result<Option<Value>, String> {
    let a = { state(host)?.fresh.active.clone() };
    let Some(a) = a else { return Ok(None) };
    // Renewal is a different actual retained phase after setup completion.
    if a.completed {
        return Ok(None);
    }
    if a.phase_fenced || a.transaction_id.is_empty() {
        return Err("fresh signing phase fenced/unprepared".into());
    }
    let p = process().lock().map_err(|_| "fresh signing process lock")?;
    if p.fresh_setup_request.as_deref() != Some(a.id.as_str()) {
        return Err("fresh signing actual reservation differs".into());
    }
    drop(p);
    let local = a
        .local_setup
        .as_ref()
        .ok_or("fresh signing local actual commit absent")?;
    let remote = a
        .remote_setup
        .as_ref()
        .ok_or("fresh signing remote original commit absent")?;
    let transaction = serde_json::from_value(json!(a.transaction_id)).map_err(safe_decode)?;
    if host
        .authority
        .current_fresh_pair_setup_receipt(&transaction)?
        .as_ref()
        != Some(local)
    {
        return Err("fresh signing current actual transaction differs".into());
    }
    let snapshot: Value = serde_json::from_str(source).map_err(safe_decode)?;
    let fence = serde_json::to_value(&local.fence).map_err(safe_decode)?;
    if !snapshot
        .pointer("/reciprocal_ed25519/fresh_setup_fences")
        .and_then(Value::as_array)
        .is_some_and(|rows| rows.contains(&fence))
    {
        return Err("fresh signing current pair floor differs".into());
    }
    let own = c.initiator.peer_id.as_str() == host.local_peer_id;
    let (first, second, revision) = if own {
        if c.correlation_id.as_str()
            != signed_history_id(host, format!("correlation.{}", a.transaction_id))?
        {
            return Err("fresh local signing correlation differs".into());
        }
        if host.local_peer_id < host.remote_peer_id {
            (
                &a.nonce_a,
                a.nonce_b.as_ref(),
                &local.resulting_authority_revision,
            )
        } else {
            (
                a.nonce_b.as_ref().ok_or("fresh nonce absent")?,
                Some(&a.nonce_a),
                &local.resulting_authority_revision,
            )
        }
    } else {
        if a.remote_signing_context.as_ref() != Some(c)
            || !remote_correlation_matches(
                host,
                c.correlation_id.as_str(),
                format!("correlation.{}", a.transaction_id),
            )?
        {
            return Err("fresh remote signing exact retained context differs".into());
        }
        if host.local_peer_id < host.remote_peer_id {
            (
                a.nonce_b.as_ref().ok_or("fresh nonce absent")?,
                Some(&a.nonce_a),
                &remote.resulting_authority_revision,
            )
        } else {
            (
                &a.nonce_a,
                a.nonce_b.as_ref(),
                &remote.resulting_authority_revision,
            )
        }
    };
    if c.initiator.device_nonce_hex != *first
        || Some(&c.responder.device_nonce_hex) != second
        || &c.revisions.enrollment_authority_revision != revision
    {
        return Err("fresh signing nonce/enrollment revision differs".into());
    }
    Ok(Some(
        json!({"kind":"actual_fresh_phase","native_request_id":a.id,"phase_request_id":a.phase_id,
        "manifold_transaction_id":a.transaction_id,"floor":local.fence,"context":c}),
    ))
}

fn remember_remote_signing_context(
    host: &Host,
    id: &str,
    c: &ManifoldCommonLanReciprocalEd25519Context,
) -> Result<(), String> {
    let mut s = state(host)?;
    let a = s
        .fresh
        .active
        .as_mut()
        .filter(|a| a.id == id && !a.phase_fenced)
        .ok_or("fresh remote signing actual phase absent")?;
    if a.remote_signing_context
        .as_ref()
        .is_some_and(|old| old != c)
    {
        return Err("fresh remote signing context changed".into());
    }
    a.remote_signing_context = Some(c.clone());
    Ok(())
}

/// Borrowed only after exclusive process root-flight reservation. Pending or
/// fenced phases cannot be promoted from diagnostic JSON into current authority.
pub(super) fn root_basis(host: &Host) -> Result<(ManifoldFreshPairSetupReceipt, String), String> {
    let s = state(host)?;
    let a = s
        .fresh
        .active
        .as_ref()
        .ok_or("root fresh actual phase absent")?;
    if !a.completed
        || a.phase_fenced
        || s.fresh.pending_phase.is_some()
        || s.fresh.pending_archive.is_some()
        || s.fresh.singleflight.load(Ordering::SeqCst)
    {
        return Err("root fresh phase unresolved".into());
    }
    let receipt = a
        .local_setup
        .clone()
        .ok_or("root actual local commit absent")?;
    if receipt.request.context.native_setup_request_id.as_str() != a.id
        || receipt.request.context.native_phase_id.as_str() != a.phase_id
    {
        return Err("root actual phase source differs".into());
    }
    Ok((receipt, a.session.clone()))
}
