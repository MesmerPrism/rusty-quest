//! One pre-Start, two-host signed Common-LAN ceremony. The installed APK and
//! private enrollment select every peer, key, endpoint and trust root; the
//! operator supplies only the fixed `pair_ceremony` action.

use super::*;
use ed25519_dalek::{Signature, VerifyingKey};
use rusty_manifold_peer::{
    ManifoldCommonLanPeerSessionProposal, ManifoldCommonLanReciprocalEd25519Context,
    ManifoldCommonLanReciprocalEd25519ReviewRequest, ManifoldCommonLanReciprocalEd25519Signature,
    ManifoldPeerEnrollmentRequest, ManifoldPeerStatusProposal,
    COMMON_LAN_PAIR_TOPOLOGY_CONTRACT_ID, COMMON_LAN_PEER_SESSION_PROPOSAL_SCHEMA,
    COMMON_LAN_RECIPROCAL_ED25519_REVIEW_SCHEMA, COMMON_LAN_TCP_TRANSPORT_CONTRACT_ID,
    PEER_CREDENTIAL_SCHEMA, PEER_ENROLLMENT_REQUEST_SCHEMA, PEER_IDENTITY_SCHEMA,
    PEER_PROPOSAL_SCHEMA, PEER_STATUS_SCHEMA,
};
use rusty_quest_broker_authority::QuestCommonLanContextDraft;
use serde::Serialize;
use serde_json::{json, Value};
use std::fs::File;
use std::io::Read;

pub(super) const FRAME_MAGIC: &[u8] = b"RQPC1";
const FRAME_SCHEMA: &str = "rusty.quest.embedded_duplex.pair_ceremony_frame.v1";
const SIGN_DOMAIN: &[u8] = b"rusty.quest.embedded_duplex.pair_ceremony.v1\0";
const FRAME_TTL_MS: u64 = 30_000;
const CEREMONY_TTL_MS: u64 = 60_000;
const CONTEXT_TTL_MS: u64 = 120_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Stage {
    Idle,
    Initiating,
    AwaitSignA,
    AwaitPrepareB,
    AwaitFinishB,
    Completed,
    Failed,
}

pub(super) struct PairState {
    stage: Stage,
    ceremony_id: Option<String>,
    session_id: Option<String>,
    nonce_a: Option<String>,
    nonce_b: Option<String>,
    deadline_ms: u64,
    prepared_b: Option<(
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    )>,
}

impl Default for PairState {
    fn default() -> Self {
        Self {
            stage: Stage::Idle,
            ceremony_id: None,
            session_id: None,
            nonce_a: None,
            nonce_b: None,
            deadline_ms: 0,
            prepared_b: None,
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct FrameBody {
    #[serde(rename = "$schema")]
    schema_id: String,
    kind: String,
    ceremony_id: String,
    sender_peer_id: String,
    receiver_peer_id: String,
    route_configuration_sha256: String,
    issued_at_ms: u64,
    expires_at_ms: u64,
    payload: Value,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct SignedFrame {
    body: FrameBody,
    signature_hex: String,
}

pub(super) fn require_empty_input(input: &str) -> Result<(), String> {
    let value: Value = serde_json::from_str(input).map_err(safe_decode)?;
    if value.as_object().is_some_and(|fields| fields.is_empty()) {
        Ok(())
    } else {
        Err("typed pair operation accepts no inputs".into())
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn decode_hex<const N: usize>(value: &str) -> Result<[u8; N], String> {
    if value.len() != N * 2
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err("pair hex bounds".into());
    }
    let mut result = [0u8; N];
    for (index, byte) in result.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)
            .map_err(|_| "pair hex decoding")?;
    }
    Ok(result)
}

fn random_hex() -> Result<String, String> {
    let mut value = [0u8; 32];
    File::open("/dev/urandom")
        .and_then(|mut source| source.read_exact(&mut value))
        .map_err(|_| "pair entropy unavailable")?;
    Ok(hex(&value))
}

fn signed_bytes(body: &FrameBody) -> Result<Vec<u8>, String> {
    let json = serde_json::to_vec(body).map_err(safe_decode)?;
    let mut bytes = Vec::with_capacity(SIGN_DOMAIN.len() + json.len());
    bytes.extend_from_slice(SIGN_DOMAIN);
    bytes.extend_from_slice(&json);
    Ok(bytes)
}

fn encode(host: &Host, kind: &str, ceremony_id: &str, payload: Value) -> Result<Vec<u8>, String> {
    let now = host.clock.now_ms()?;
    let body = FrameBody {
        schema_id: FRAME_SCHEMA.into(),
        kind: kind.into(),
        ceremony_id: ceremony_id.into(),
        sender_peer_id: host.local_peer_id.clone(),
        receiver_peer_id: host.remote_peer_id.clone(),
        route_configuration_sha256: host.route_configuration_sha256.clone(),
        issued_at_ms: now.saturating_sub(5_000),
        expires_at_ms: now
            .checked_add(FRAME_TTL_MS - 5_000)
            .ok_or("pair time overflow")?,
        payload,
    };
    let signature_hex = hex(&host.callbacks.sign_pair_ceremony(&signed_bytes(&body)?)?);
    let mut encoded = FRAME_MAGIC.to_vec();
    encoded.extend(
        serde_json::to_vec(&SignedFrame {
            body,
            signature_hex,
        })
        .map_err(safe_decode)?,
    );
    if encoded.len() > 128 * 1024 {
        return Err("pair frame bounds".into());
    }
    Ok(encoded)
}

fn decode(
    host: &Host,
    bytes: &[u8],
    expected_kind: Option<&str>,
    expected_id: Option<&str>,
) -> Result<FrameBody, String> {
    if !bytes.starts_with(FRAME_MAGIC) || bytes.len() > 128 * 1024 {
        return Err("pair frame bounds".into());
    }
    let frame: SignedFrame =
        serde_json::from_slice(&bytes[FRAME_MAGIC.len()..]).map_err(safe_decode)?;
    let body = &frame.body;
    let now = host.clock.now_ms()?;
    if body.schema_id != FRAME_SCHEMA
        || body.sender_peer_id != host.remote_peer_id
        || body.receiver_peer_id != host.local_peer_id
        || body.route_configuration_sha256 != host.route_configuration_sha256
        || body.issued_at_ms > now
        || body.expires_at_ms <= now
        || body.expires_at_ms.saturating_sub(body.issued_at_ms) > FRAME_TTL_MS
        || !body.ceremony_id.starts_with("ceremony.duplex.")
        || expected_kind.is_some_and(|kind| kind != body.kind)
        || expected_id.is_some_and(|id| id != body.ceremony_id)
    {
        return Err("pair frame binding or time mismatch".into());
    }
    decode_hex::<32>(&body.ceremony_id["ceremony.duplex.".len()..])?;
    let signature = Signature::from_bytes(&decode_hex::<64>(&frame.signature_hex)?);
    let key = VerifyingKey::from_bytes(&host.remote_public_key)
        .map_err(|_| "enrolled peer key invalid")?;
    key.verify_strict(&signed_bytes(body)?, &signature)
        .map_err(|_| "pair frame signature invalid")?;
    let expected = match body.kind.as_str() {
        "hello" => &["session_id", "nonce_a"][..],
        "hello_ack" => &["nonce_b"][..],
        "sign_a" => &["context"][..],
        "signed_a" => &["signature"][..],
        "prepare_b" => &[][..],
        "prepared_b" => &["context", "signature"][..],
        "finish_b" => &["signature"][..],
        "finished_b" => &["current_session"][..],
        _ => return Err("pair frame kind invalid".into()),
    };
    let fields = body
        .payload
        .as_object()
        .ok_or("pair frame payload invalid")?;
    if fields.len() != expected.len() || expected.iter().any(|key| !fields.contains_key(*key)) {
        return Err("pair frame payload fields invalid".into());
    }
    Ok(frame.body)
}

fn exchange(
    host: &Host,
    kind: &str,
    response_kind: &str,
    ceremony_id: &str,
    payload: Value,
) -> Result<Value, String> {
    let frame = encode(host, kind, ceremony_id, payload)?;
    let reply = host.callbacks.clone().exchange(&frame, 128 * 1024)?;
    Ok(decode(host, &reply, Some(response_kind), Some(ceremony_id))?.payload)
}

fn snapshot(host: &Host) -> Result<Value, String> {
    serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)
}

fn revision(snapshot: &Value, path: &str) -> Result<u64, String> {
    snapshot
        .pointer(path)
        .and_then(Value::as_u64)
        .filter(|value| *value > 0)
        .ok_or_else(|| format!("pair authority revision unavailable: {path}"))
}

fn key_facts(host: &Host, peer_id: &str) -> Result<(String, String), String> {
    let (key_id, public) = if peer_id == host.local_peer_id {
        (host.callbacks.key_id().to_owned(), host.local_public_key)
    } else if peer_id == host.remote_peer_id {
        (host.remote_key_id.clone(), host.remote_public_key)
    } else {
        return Err("foreign pair peer".into());
    };
    let digest = key_id.strip_prefix("ed25519.").ok_or("pair key identity")?;
    decode_hex::<32>(digest)?;
    Ok((key_id, hex(&public)))
}

fn prime(host: &Host, now: u64, suffix: &str) -> Result<(), String> {
    let start = snapshot(host)?;
    let expected_host = host.packaged_route.runtime_host_id(&host.local_peer_id)?;
    if start.get("host_id").and_then(Value::as_str) != Some(expected_host.as_str()) {
        return Err("pair Runtime Host differs from the packaged peer".into());
    }
    if start
        .pointer("/enrollment/credentials")
        .and_then(Value::as_array)
        .is_none_or(|items| !items.is_empty())
        || start
            .pointer("/accepted_peers/peers")
            .and_then(Value::as_array)
            .is_none_or(|items| !items.is_empty())
    {
        return Err("pair authority already initialized; close before retry".into());
    }
    let operator = start
        .pointer("/trust_policy/trusted_operator_ids/0")
        .and_then(Value::as_str)
        .ok_or("pair operator policy absent")?;
    let adapter = start
        .pointer("/trust_policy/trusted_adapter_ids/0")
        .and_then(Value::as_str)
        .ok_or("pair adapter policy absent")?;
    let mut peers = host.packaged_route.peers.iter().collect::<Vec<_>>();
    peers.sort_by(|a, b| a.peer_id.cmp(&b.peer_id));
    for peer in &peers {
        let current = snapshot(host)?;
        let (key_id, public) = key_facts(host, &peer.peer_id)?;
        let digest = key_id.strip_prefix("ed25519.").ok_or("pair key identity")?;
        let request: ManifoldPeerEnrollmentRequest = serde_json::from_value(json!({
            "$schema": PEER_ENROLLMENT_REQUEST_SCHEMA,
            "request_id": format!("request.duplex.enroll.{suffix}.{}", peer.peer_id),
            "expected_authority_revision": revision(&current, "/enrollment/authority_revision")?,
            "operator_id": operator, "issued_at_ms": now, "action": "enroll",
            "credential": {"$schema": PEER_CREDENTIAL_SCHEMA,
                "credential_id": format!("credential.duplex.{}.1", peer.peer_id),
                "peer_id": peer.peer_id, "trust_domain": "trust.morphospace.peer",
                "key_id": key_id, "key_generation": 1, "algorithm": "ed25519",
                "public_key_hex": public, "public_key_sha256": format!("sha256:{digest}"),
                "valid_from_ms": now.saturating_sub(1000),
                "expires_at_ms": now.checked_add(180_000).ok_or("pair time overflow")?,
                "status": "active", "replaced_by_key_id": null}
        }))
        .map_err(safe_decode)?;
        if !host.authority.review_enrollment(&request, now)?.applied {
            return Err("pair enrollment rejected".into());
        }
    }
    for peer in &peers {
        let current = snapshot(host)?;
        let (key_id, _) = key_facts(host, &peer.peer_id)?;
        let digest = key_id.strip_prefix("ed25519.").ok_or("pair key identity")?;
        let proposal: ManifoldPeerStatusProposal = serde_json::from_value(json!({
            "$schema": PEER_PROPOSAL_SCHEMA,
            "proposal_id": format!("proposal.duplex.status.{suffix}.{}", peer.peer_id),
            "expected_authority_revision": revision(&current, "/accepted_peers/authority_revision")?,
            "proposer_id": adapter,
            "identity": {"$schema": PEER_IDENTITY_SCHEMA, "peer_id": peer.peer_id,
                "key_fingerprint": format!("fingerprint.{digest}"),
                "trust_domain": "trust.morphospace.peer", "roles": ["observer", "rendezvous"]},
            "status": {"$schema": PEER_STATUS_SCHEMA, "peer_id": peer.peer_id,
                "status_revision": 1, "observed_at_ms": now,
                "expires_at_ms": now.checked_add(120_000).ok_or("pair time overflow")?,
                "availability": "ready", "capability_ids": [
                    "capability.rendezvous.ble", "capability.route.rust-direct-p2p",
                    "capability.topology.wifi-direct"]},
            "payload_class": "low_rate_descriptor"
        })).map_err(safe_decode)?;
        if !host.authority.review_peer_status(proposal, now)?.1.applied {
            return Err("pair status rejected".into());
        }
    }
    Ok(())
}

fn binding(host: &Host, peer_id: &str, nonce: &str, role: &str) -> Result<Value, String> {
    decode_hex::<32>(nonce)?;
    let (key_id, _) = key_facts(host, peer_id)?;
    let digest = key_id.strip_prefix("ed25519.").ok_or("pair key identity")?;
    Ok(
        json!({"peer_id": peer_id, "key_id": key_id, "key_generation": 1,
        "public_key_sha256": format!("sha256:{digest}"), "role": role,
        "device_nonce_hex": nonce}),
    )
}

fn transport(host: &Host) -> Result<Value, String> {
    let mut peers = host.packaged_route.peers.iter().collect::<Vec<_>>();
    peers.sort_by(|a, b| a.peer_id.cmp(&b.peer_id));
    let endpoints = peers
        .iter()
        .map(|peer| -> Result<Value, String> {
            Ok(json!({"peer_id": peer.peer_id,
            "endpoint_id": host.packaged_route.media_endpoint_id(&peer.peer_id)?,
            "listen_ip_address": peer.media.host, "listen_port": peer.media.port}))
        })
        .collect::<Result<Vec<_>, _>>()?;
    Ok(
        json!({"topology_contract_id": COMMON_LAN_PAIR_TOPOLOGY_CONTRACT_ID,
        "transport_contract_id": COMMON_LAN_TCP_TRANSPORT_CONTRACT_ID,
        "network_scope_id": host.packaged_route.network_scope_id,
        "endpoints": endpoints,
        "route_configuration_sha256": host.route_configuration_sha256}),
    )
}

fn prepare(
    host: &Host,
    ceremony_id: &str,
    nonce_local: &str,
    nonce_remote: &str,
) -> Result<
    (
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    ),
    String,
> {
    let now = host.clock.now_ms()?;
    let draft: QuestCommonLanContextDraft = serde_json::from_value(json!({
        "correlation_id": format!("correlation.{}", ceremony_id),
        "initiator": binding(host, &host.local_peer_id, nonce_local, "initiator")?,
        "responder": binding(host, &host.remote_peer_id, nonce_remote, "responder")?,
        "transport": transport(host)?, "coordinator_epoch": 1,
        "issued_at_ms": now.saturating_sub(5_000),
        "expires_at_ms": now.checked_add(CONTEXT_TTL_MS - 5_000).ok_or("pair time overflow")?
    }))
    .map_err(safe_decode)?;
    let context = host.authority.prepare_common_lan_context(draft)?;
    let signature = sign(host, &context)?;
    Ok((context, signature))
}

fn sign(
    host: &Host,
    context: &ManifoldCommonLanReciprocalEd25519Context,
) -> Result<ManifoldCommonLanReciprocalEd25519Signature, String> {
    let input = serde_json::to_string(context).map_err(safe_decode)?;
    serde_json::from_str(&sign_common_lan(host, &input, host.clock.now_ms()?)?).map_err(safe_decode)
}

fn apply(
    host: &Host,
    ceremony_id: &str,
    session_id: &str,
    context: ManifoldCommonLanReciprocalEd25519Context,
    local_signature: ManifoldCommonLanReciprocalEd25519Signature,
    remote_signature: ManifoldCommonLanReciprocalEd25519Signature,
) -> Result<Value, String> {
    let request: ManifoldCommonLanReciprocalEd25519ReviewRequest = serde_json::from_value(json!({
        "$schema": COMMON_LAN_RECIPROCAL_ED25519_REVIEW_SCHEMA,
        "request_id": format!("request.{}.reciprocal", ceremony_id),
        "context": context, "initiator_signature": local_signature,
        "responder_signature": remote_signature
    }))
    .map_err(safe_decode)?;
    let now = host.clock.now_ms()?;
    let reciprocal = host.authority.apply_common_lan_reciprocal(&request, now)?;
    if !reciprocal.accepted {
        return Err("pair reciprocal authority rejected".into());
    }
    let current = snapshot(host)?;
    let proposal: ManifoldCommonLanPeerSessionProposal = serde_json::from_value(json!({
        "$schema": COMMON_LAN_PEER_SESSION_PROPOSAL_SCHEMA,
        "proposal_id": format!("proposal.{}.session", ceremony_id),
        "session_id": session_id,
        "expected_authority_revision": revision(&current, "/peer_sessions/authority_revision")?,
        "subject_peer_id": host.local_peer_id,
        "candidate_peer_id": host.remote_peer_id,
        "initiator_peer_id": host.local_peer_id,
        "responder_peer_id": host.remote_peer_id,
        "requested_capability_ids": ["capability.rendezvous.ble",
            "capability.route.rust-direct-p2p", "capability.topology.wifi-direct"],
        "transport": transport(host)?,
        "expires_at_ms": reciprocal.expires_at_ms
    }))
    .map_err(safe_decode)?;
    let (decision, topology) =
        host.authority
            .apply_common_lan_session(&proposal, &reciprocal, now)?;
    if !decision.applied || !topology.authorized || topology.session_id != proposal.session_id {
        return Err("pair session authority rejected".into());
    }
    let session_id = serde_json::from_value(json!(session_id)).map_err(safe_decode)?;
    let current = host
        .authority
        .current_common_lan_session(&session_id, host.clock.now_ms()?)?;
    if !current.current {
        return Err("pair session not current after acceptance".into());
    }
    serde_json::to_value(current).map_err(safe_decode)
}

fn parse_context(value: &Value) -> Result<ManifoldCommonLanReciprocalEd25519Context, String> {
    serde_json::from_value(value.clone()).map_err(safe_decode)
}

fn parse_signature(value: &Value) -> Result<ManifoldCommonLanReciprocalEd25519Signature, String> {
    serde_json::from_value(value.clone()).map_err(safe_decode)
}

fn state(host: &Host) -> Result<std::sync::MutexGuard<'_, PairState>, String> {
    host.pair_state
        .lock()
        .map_err(|_| "pair state poisoned".into())
}

fn checked_state(host: &Host, ceremony_id: &str, expected: Stage) -> Result<(), String> {
    let current = state(host)?;
    if current.stage != expected
        || current.ceremony_id.as_deref() != Some(ceremony_id)
        || current.deadline_ms <= host.clock.now_ms()?
    {
        return Err("pair ceremony state unavailable or expired".into());
    }
    Ok(())
}

fn advance(host: &Host, ceremony_id: &str, expected: Stage, next: Stage) -> Result<(), String> {
    checked_state(host, ceremony_id, expected)?;
    let mut current = state(host)?;
    if current.stage != expected || current.ceremony_id.as_deref() != Some(ceremony_id) {
        return Err("pair ceremony changed".into());
    }
    current.stage = next;
    Ok(())
}

fn fail(host: &Host) {
    if let Ok(mut current) = state(host) {
        current.stage = Stage::Failed;
    }
}

pub(super) fn run(host: &Host) -> Result<String, String> {
    if host.local_peer_id >= host.remote_peer_id {
        return Err("pair ceremony initiator is the lower packaged peer id".into());
    }
    let ceremony_id = format!("ceremony.duplex.{}", random_hex()?);
    let session_id = format!("session.duplex.{}", random_hex()?);
    let nonce_a = random_hex()?;
    let now = host.clock.now_ms()?;
    {
        let mut current = state(host)?;
        if current.stage != Stage::Idle {
            return Err("pair ceremony requires a fresh process".into());
        }
        current.stage = Stage::Initiating;
        current.ceremony_id = Some(ceremony_id.clone());
        current.session_id = Some(session_id.clone());
        current.nonce_a = Some(nonce_a.clone());
        current.deadline_ms = now
            .checked_add(CEREMONY_TTL_MS)
            .ok_or("pair time overflow")?;
    }
    let result = (|| {
        prime(host, now, &ceremony_id)?;
        let hello = exchange(
            host,
            "hello",
            "hello_ack",
            &ceremony_id,
            json!({"session_id": session_id, "nonce_a": nonce_a}),
        )?;
        let nonce_b = hello
            .get("nonce_b")
            .and_then(Value::as_str)
            .ok_or("pair nonce absent")?;
        decode_hex::<32>(nonce_b)?;
        if nonce_b == nonce_a {
            return Err("pair nonces collided".into());
        }
        state(host)?.nonce_b = Some(nonce_b.into());
        let (context_a, signature_a) = prepare(host, &ceremony_id, &nonce_a, nonce_b)?;
        let signed_a = exchange(
            host,
            "sign_a",
            "signed_a",
            &ceremony_id,
            json!({"context": context_a}),
        )?;
        let signature_b =
            parse_signature(signed_a.get("signature").ok_or("peer signature absent")?)?;
        let current_a = apply(
            host,
            &ceremony_id,
            &session_id,
            context_a,
            signature_a,
            signature_b,
        )?;
        let prepared_b = exchange(host, "prepare_b", "prepared_b", &ceremony_id, json!({}))?;
        let context_b = parse_context(prepared_b.get("context").ok_or("peer context absent")?)?;
        if context_b.correlation_id.as_str() != format!("correlation.{ceremony_id}") {
            return Err("peer context correlation differs".into());
        }
        let signature_b =
            parse_signature(prepared_b.get("signature").ok_or("peer signature absent")?)?;
        let signature_a = sign(host, &context_b)?;
        if signature_b.signer_peer_id.as_str() != host.remote_peer_id
            || signature_b.signer_key_id.as_str() != host.remote_key_id
            || signature_b.context_sha256 != signature_a.context_sha256
        {
            return Err("peer prepared signature binding differs".into());
        }
        let completed_b = exchange(
            host,
            "finish_b",
            "finished_b",
            &ceremony_id,
            json!({"signature": signature_a}),
        )?;
        let current_b = completed_b
            .get("current_session")
            .ok_or("peer current session absent")?;
        if current_b.get("current").and_then(Value::as_bool) != Some(true)
            || current_b.get("session_id").and_then(Value::as_str) != Some(session_id.as_str())
        {
            return Err("peer current session receipt differs".into());
        }
        let session = serde_json::from_value(json!(session_id)).map_err(safe_decode)?;
        let reread = host
            .authority
            .current_common_lan_session(&session, host.clock.now_ms()?)?;
        if !reread.current {
            return Err("initiator session no longer current".into());
        }
        Ok(
            json!({"$schema":"rusty.quest.embedded_duplex.pair_ceremony_result.v1",
            "state":"peer_session_current_route_unverified", "ceremony_id":ceremony_id,
            "session_id":session_id, "local_current_session":current_a,
            "remote_current_session":current_b, "route_current":false,
            "media_effect_proven":false})
            .to_string(),
        )
    })();
    match result {
        Ok(value) => {
            state(host)?.stage = Stage::Completed;
            Ok(value)
        }
        Err(error) => {
            fail(host);
            Err(error)
        }
    }
}

pub(super) fn handle_frame(host: &Host, bytes: &[u8]) -> Result<Vec<u8>, String> {
    let frame = decode(host, bytes, None, None)?;
    let id = frame.ceremony_id.as_str();
    let result = match frame.kind.as_str() {
        "hello" => {
            if host.local_peer_id <= host.remote_peer_id {
                return Err("pair responder role differs".into());
            }
            let session_id = frame
                .payload
                .get("session_id")
                .and_then(Value::as_str)
                .ok_or("pair session id absent")?;
            if !session_id.starts_with("session.duplex.") {
                return Err("pair session id invalid".into());
            }
            decode_hex::<32>(&session_id["session.duplex.".len()..])?;
            let nonce_a = frame
                .payload
                .get("nonce_a")
                .and_then(Value::as_str)
                .ok_or("pair nonce absent")?;
            decode_hex::<32>(nonce_a)?;
            let nonce_b = random_hex()?;
            {
                let mut current = state(host)?;
                if current.stage != Stage::Idle {
                    return Err("pair ceremony requires fresh process".into());
                }
                current.stage = Stage::AwaitSignA;
                current.ceremony_id = Some(id.into());
                current.session_id = Some(session_id.into());
                current.nonce_a = Some(nonce_a.into());
                current.nonce_b = Some(nonce_b.clone());
                current.deadline_ms = host
                    .clock
                    .now_ms()?
                    .checked_add(CEREMONY_TTL_MS)
                    .ok_or("pair time overflow")?;
            }
            encode(host, "hello_ack", id, json!({"nonce_b": nonce_b}))
        }
        "sign_a" => {
            advance(host, id, Stage::AwaitSignA, Stage::AwaitPrepareB)?;
            let result = (|| {
                let context =
                    parse_context(frame.payload.get("context").ok_or("pair context absent")?)?;
                if context.correlation_id.as_str() != format!("correlation.{id}") {
                    return Err("pair context correlation differs".into());
                }
                let current = state(host)?;
                if context.initiator.device_nonce_hex != current.nonce_a.as_deref().unwrap_or("")
                    || context.responder.device_nonce_hex
                        != current.nonce_b.as_deref().unwrap_or("")
                {
                    return Err("pair challenge nonces differ".into());
                }
                drop(current);
                prime(host, host.clock.now_ms()?, id)?;
                let signature = sign(host, &context)?;
                encode(host, "signed_a", id, json!({"signature": signature}))
            })();
            if result.is_err() {
                fail(host);
            }
            result
        }
        "prepare_b" => {
            advance(host, id, Stage::AwaitPrepareB, Stage::AwaitFinishB)?;
            let result = (|| {
                if frame
                    .payload
                    .as_object()
                    .is_none_or(|fields| !fields.is_empty())
                {
                    return Err("pair prepare payload invalid".into());
                }
                let current = state(host)?;
                let nonce_b = current.nonce_b.clone().ok_or("local pair nonce absent")?;
                let nonce_a = current.nonce_a.clone().ok_or("remote pair nonce absent")?;
                drop(current);
                let (context, signature) = prepare(host, id, &nonce_b, &nonce_a)?;
                state(host)?.prepared_b = Some((context.clone(), signature.clone()));
                encode(
                    host,
                    "prepared_b",
                    id,
                    json!({"context":context,"signature":signature}),
                )
            })();
            if result.is_err() {
                fail(host);
            }
            result
        }
        "finish_b" => {
            advance(host, id, Stage::AwaitFinishB, Stage::Failed)?;
            let result = (|| {
                let signature_a = parse_signature(
                    frame
                        .payload
                        .get("signature")
                        .ok_or("pair signature absent")?,
                )?;
                // Reconstructing a context here would change signed revisions.
                // The exact prepared context/signature are held until finish.
                let current = state(host)?;
                let prepared = current_prepared(&current)?;
                let session_id = current.session_id.clone().ok_or("pair session absent")?;
                drop(current);
                let proof = apply(host, id, &session_id, prepared.0, prepared.1, signature_a)?;
                state(host)?.stage = Stage::Completed;
                encode(host, "finished_b", id, json!({"current_session":proof}))
            })();
            if result.is_err() {
                fail(host);
            }
            result
        }
        _ => Err("unsupported pair ceremony frame".into()),
    };
    result
}

fn current_prepared(
    state: &PairState,
) -> Result<
    (
        ManifoldCommonLanReciprocalEd25519Context,
        ManifoldCommonLanReciprocalEd25519Signature,
    ),
    String,
> {
    state
        .prepared_b
        .clone()
        .ok_or("pair prepared context unavailable".into())
}

pub(super) fn status(host: &Host) -> Result<String, String> {
    let current = state(host)?;
    let stage = current.stage;
    let session_id = current.session_id.clone();
    let expired = current.deadline_ms != 0 && current.deadline_ms <= host.clock.now_ms()?;
    drop(current);
    let native_current = if stage == Stage::Completed {
        let id = session_id.as_ref().ok_or("pair session absent")?;
        let id = serde_json::from_value(json!(id)).map_err(safe_decode)?;
        Some(
            host.authority
                .current_common_lan_session(&id, host.clock.now_ms()?)?,
        )
    } else {
        None
    };
    let current = native_current
        .as_ref()
        .is_some_and(|receipt| receipt.current);
    Ok(
        json!({"$schema":"rusty.quest.embedded_duplex.pair_status.v1",
        "state":if current { "peer_session_current_route_unverified" }
            else if stage == Stage::Failed || stage == Stage::Completed || expired { "cleanup_pending" }
            else if stage == Stage::Idle { "not_started" } else { "in_progress" },
        "session_id":session_id, "native_current_session":native_current,
        "route_current":false,"media_effect_proven":false})
        .to_string(),
    )
}
