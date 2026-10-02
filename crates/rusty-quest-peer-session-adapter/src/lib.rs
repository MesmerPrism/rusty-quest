//! Projection of validated Quest BLE pair evidence into Manifold peer sessions.

mod peer_mesh;

pub use peer_mesh::*;

use rusty_manifold_model::{DottedId, Revision, SchemaId};
use rusty_manifold_peer::{
    review_and_apply_peer_session, review_and_apply_signed_peer_session, revoke_peer_session,
    ManifoldAcceptedPeer, ManifoldAcceptedPeerState, ManifoldPeerAvailability,
    ManifoldPeerEnrollmentState, ManifoldPeerIdentity, ManifoldPeerRole,
    ManifoldPeerSessionDecision, ManifoldPeerSessionProposal, ManifoldPeerSessionRejectionReason,
    ManifoldPeerSessionReviewCase, ManifoldPeerSessionRevocation, ManifoldPeerSessionState,
    ManifoldPeerStatus, ManifoldPeerTopologyAuthorization, ManifoldRendezvousAuthorityState,
    ManifoldRendezvousReceipt, ManifoldSignedPeerSessionReviewCase,
    PeerRendezvousAuthenticationEvidence, PeerRendezvousTransport, PEER_IDENTITY_SCHEMA,
    PEER_SESSION_PROPOSAL_SCHEMA, PEER_SESSION_REVIEW_SCHEMA, PEER_SESSION_REVOCATION_SCHEMA,
    PEER_SESSION_SNAPSHOT_SCHEMA, PEER_SNAPSHOT_SCHEMA, PEER_STATUS_SCHEMA,
    PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT, SIGNED_PEER_SESSION_REVIEW_SCHEMA,
};
use rusty_quest_device_link::{validate_ble_rendezvous_pair_receipt, BleRendezvousPairReceipt};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Adapter configuration supplied by accepted product state, not BLE.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPeerSessionProjectionConfig {
    /// Stable subject peer id.
    pub subject_peer_id: DottedId,
    /// Stable candidate peer id.
    pub candidate_peer_id: DottedId,
    /// Peer assigned group-owner role.
    pub group_owner_peer_id: DottedId,
    /// Peer assigned client role.
    pub client_peer_id: DottedId,
    /// Trusted adapter id.
    pub adapter_id: DottedId,
    /// Expected peer-session authority revision.
    pub expected_authority_revision: Revision,
    /// Review time.
    pub now_ms: u64,
    /// Bounded authorization lifetime.
    pub authorization_ttl_ms: u64,
}

/// Current Manifold authority evidence required for product topology acceptance.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPeerSessionAuthorityEvidence {
    /// Current operator enrollment authority.
    pub current_enrollment: ManifoldPeerEnrollmentState,
    /// Current signed-rendezvous authority retaining the accepted receipt.
    pub current_rendezvous_state: ManifoldRendezvousAuthorityState,
    /// Accepted reciprocal signed-rendezvous receipt for this pair/session.
    pub rendezvous_receipt: ManifoldRendezvousReceipt,
}

/// Complete host-testable decision bundle used by product integration gates.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPeerSessionDecisionBundle {
    /// Bundle schema.
    pub schema: String,
    /// Accepted decision.
    pub accepted_decision: ManifoldPeerSessionDecision,
    /// Fresh authorizing receipt.
    pub accepted_authorization: ManifoldPeerTopologyAuthorization,
    /// Unauthenticated proposal rejection.
    pub unauthenticated_decision: ManifoldPeerSessionDecision,
    /// Non-authorizing receipt before acceptance.
    pub unauthenticated_authorization: ManifoldPeerTopologyAuthorization,
    /// Replay rejection after acceptance.
    pub replay_decision: ManifoldPeerSessionDecision,
    /// Peer-change rejection while the prior session is active.
    pub peer_change_decision: ManifoldPeerSessionDecision,
    /// State after explicit revocation.
    pub revoked_state: ManifoldPeerSessionState,
    /// Non-authorizing revocation receipt.
    pub revoked_authorization: ManifoldPeerTopologyAuthorization,
}

/// Project a validated pair artifact and execute the Manifold lifecycle matrix.
pub fn evaluate_ble_pair_for_peer_session(
    pair: &BleRendezvousPairReceipt,
    config: &QuestPeerSessionProjectionConfig,
) -> Result<QuestPeerSessionDecisionBundle, String> {
    let _ = pair;
    let _ = config;
    Err(
        "signed Manifold enrollment/rendezvous authority evidence is required; use evaluate_signed_ble_pair_for_peer_session"
            .to_string(),
    )
}

/// Project a validated pair artifact plus retained Manifold authority evidence.
pub fn evaluate_signed_ble_pair_for_peer_session(
    pair: &BleRendezvousPairReceipt,
    config: &QuestPeerSessionProjectionConfig,
    authority: &QuestPeerSessionAuthorityEvidence,
) -> Result<QuestPeerSessionDecisionBundle, String> {
    validate_ble_rendezvous_pair_receipt(pair).map_err(|errors| {
        errors
            .into_iter()
            .map(|error| error.message)
            .collect::<Vec<_>>()
            .join("; ")
    })?;
    if !(1_000..=120_000).contains(&config.authorization_ttl_ms) {
        return Err("authorization_ttl_ms must be 1000..=120000".to_string());
    }
    let peer_state = accepted_peers(config, false);
    let initial_state = ManifoldPeerSessionState {
        schema_id: schema(PEER_SESSION_SNAPSHOT_SCHEMA),
        authority_revision: config.expected_authority_revision,
        sessions: Vec::new(),
        applied_proposal_ids: Vec::new(),
        revoked_session_ids: Vec::new(),
    };
    let proposal = proposal(pair, config)?;

    let mut unauthenticated = proposal.clone();
    unauthenticated.proposal_id = dotted(format!("{}.unauthenticated", proposal.proposal_id))?;
    unauthenticated.authentication.authenticated = false;
    let unauthenticated_case = review(
        peer_state.clone(),
        initial_state.clone(),
        unauthenticated,
        config,
    );
    let (unauthenticated_decision, unauthenticated_authorization) =
        review_and_apply_peer_session(&unauthenticated_case);
    if unauthenticated_decision.rejection_reason
        != Some(ManifoldPeerSessionRejectionReason::AuthenticationFailed)
    {
        return Err("unauthenticated matrix row did not reject".to_string());
    }

    let accepted_case = signed_review(
        review(peer_state.clone(), initial_state, proposal.clone(), config),
        authority,
    )?;
    let (accepted_decision, accepted_signed_authorization) =
        review_and_apply_signed_peer_session(&accepted_case);
    let accepted_authorization = accepted_signed_authorization.topology_authorization;
    let accepted_state = accepted_decision.accepted_state.clone().ok_or_else(|| {
        format!(
            "authenticated BLE proposal was not accepted: {:?}",
            accepted_decision.rejection_reason
        )
    })?;

    let mut replay = proposal.clone();
    replay.expected_authority_revision = accepted_state.authority_revision;
    let replay_case = review(peer_state, accepted_state.clone(), replay, config);
    let (replay_decision, _) = review_and_apply_peer_session(&replay_case);
    if replay_decision.rejection_reason
        != Some(ManifoldPeerSessionRejectionReason::ReplayedProposal)
    {
        return Err("replay matrix row did not reject".to_string());
    }

    let mut peer_change = proposal.clone();
    peer_change.proposal_id = dotted(format!("{}.peer-change", proposal.proposal_id))?;
    peer_change.session_id = dotted(format!("{}.peer-change", proposal.session_id))?;
    peer_change.expected_authority_revision = accepted_state.authority_revision;
    peer_change.candidate_peer_id = dotted("peer.gamma")?;
    peer_change.client_peer_id = dotted("peer.gamma")?;
    let peer_change_case = review(
        accepted_peers(config, true),
        accepted_state.clone(),
        peer_change,
        config,
    );
    let (peer_change_decision, _) = review_and_apply_peer_session(&peer_change_case);
    if peer_change_decision.rejection_reason
        != Some(ManifoldPeerSessionRejectionReason::PeerChangedWithoutRevocation)
    {
        return Err("peer-change matrix row did not reject".to_string());
    }

    let revocation = ManifoldPeerSessionRevocation {
        schema_id: schema(PEER_SESSION_REVOCATION_SCHEMA),
        revocation_id: dotted(format!("revoke.{}", proposal.session_id))?,
        session_id: proposal.session_id,
        expected_authority_revision: accepted_state.authority_revision,
    };
    let (revoked_state, revoked_authorization) =
        revoke_peer_session(&accepted_state, &revocation, config.now_ms + 1)?;

    Ok(QuestPeerSessionDecisionBundle {
        schema: "rusty.quest.peer_session_decision_bundle.v1".to_string(),
        accepted_decision,
        accepted_authorization,
        unauthenticated_decision,
        unauthenticated_authorization,
        replay_decision,
        peer_change_decision,
        revoked_state,
        revoked_authorization,
    })
}

fn proposal(
    pair: &BleRendezvousPairReceipt,
    config: &QuestPeerSessionProjectionConfig,
) -> Result<ManifoldPeerSessionProposal, String> {
    let pair_bytes = serde_json::to_vec(pair).map_err(|error| error.to_string())?;
    let digest = Sha256::digest(pair_bytes);
    let digest_id = dotted(format!("sha256.{digest:x}"))?;
    let authenticated_messages = pair
        .phases
        .iter()
        .map(|phase| {
            phase.server_receipt.authenticated_messages
                + phase.client_receipt.authenticated_messages
        })
        .sum();
    let reconnects_completed = pair
        .phases
        .iter()
        .map(|phase| {
            phase.server_receipt.reconnects_completed + phase.client_receipt.reconnects_completed
        })
        .sum();
    let safe_run = pair.run_id.to_ascii_lowercase();
    let expires_at_ms = config.now_ms.saturating_add(config.authorization_ttl_ms);
    Ok(ManifoldPeerSessionProposal {
        schema_id: schema(PEER_SESSION_PROPOSAL_SCHEMA),
        proposal_id: dotted(format!("proposal.peer-session.{safe_run}"))?,
        session_id: dotted(format!("session.peer.{safe_run}"))?,
        expected_authority_revision: config.expected_authority_revision,
        subject_peer_id: config.subject_peer_id.clone(),
        candidate_peer_id: config.candidate_peer_id.clone(),
        group_owner_peer_id: config.group_owner_peer_id.clone(),
        client_peer_id: config.client_peer_id.clone(),
        requested_capability_ids: capability_ids(),
        topology_contract_id: dotted(PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT)?,
        expires_at_ms,
        authentication: PeerRendezvousAuthenticationEvidence {
            adapter_id: config.adapter_id.clone(),
            transport: PeerRendezvousTransport::BleGattAuthenticated,
            evidence_digest: digest_id,
            authenticated: true,
            authenticated_messages,
            authentication_failures: 0,
            role_swap_completed: pair.role_swap_completed,
            reconnects_completed,
            observed_at_ms: config.now_ms,
            expires_at_ms,
        },
    })
}

/// Project a validated BLE observation without inventing accepted peers or state.
/// The returned proposal remains non-authoritative until Manifold reviews it.
pub fn project_observed_ble_peer_session(
    pair: &BleRendezvousPairReceipt,
    config: &QuestPeerSessionProjectionConfig,
) -> Result<ManifoldPeerSessionProposal, String> {
    validate_ble_rendezvous_pair_receipt(pair).map_err(|errors| {
        errors
            .into_iter()
            .map(|error| error.message)
            .collect::<Vec<_>>()
            .join("; ")
    })?;
    if !(1_000..=60_000).contains(&config.authorization_ttl_ms) {
        return Err("signed rendezvous lifetime must be 1000..=60000 ms".into());
    }
    proposal(pair, config)
}

/// Explicit operator trust input, pinned separately from untrusted requests.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPeerOwnerPolicy {
    /// Operator-authorized enrollment routes.
    pub trusted_operator_ids: Vec<DottedId>,
    /// Operator-reviewed public-key fingerprint identities.
    pub trusted_key_fingerprints: Vec<DottedId>,
    /// Accepted BLE projection adapter routes.
    pub trusted_adapter_ids: Vec<DottedId>,
}

/// One retained request with its original review time. No accepted state input.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPeerOwnerStep {
    /// Original owner review time, retained during journal replay.
    pub now_ms: u64,
    /// Actual typed owner request.
    pub request: QuestPeerOwnerRequest,
}

/// Decode raw owner inputs while rejecting duplicate JSON fields at every depth.
/// This check precedes typed decoding, including the closed enrollment adapter.
pub fn parse_peer_owner_json<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> Result<T, String> {
    struct Unique;
    impl<'de> Deserialize<'de> for Unique {
        fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
            struct Visitor;
            impl<'de> serde::de::Visitor<'de> for Visitor {
                type Value = Unique;
                fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                    f.write_str("JSON without duplicate object fields")
                }
                fn visit_bool<E: serde::de::Error>(self, _: bool) -> Result<Unique, E> {
                    Ok(Unique)
                }
                fn visit_i64<E: serde::de::Error>(self, _: i64) -> Result<Unique, E> {
                    Ok(Unique)
                }
                fn visit_u64<E: serde::de::Error>(self, _: u64) -> Result<Unique, E> {
                    Ok(Unique)
                }
                fn visit_f64<E: serde::de::Error>(self, _: f64) -> Result<Unique, E> {
                    Ok(Unique)
                }
                fn visit_str<E: serde::de::Error>(self, _: &str) -> Result<Unique, E> {
                    Ok(Unique)
                }
                fn visit_unit<E: serde::de::Error>(self) -> Result<Unique, E> {
                    Ok(Unique)
                }
                fn visit_seq<A: serde::de::SeqAccess<'de>>(
                    self,
                    mut sequence: A,
                ) -> Result<Unique, A::Error> {
                    while sequence.next_element::<Unique>()?.is_some() {}
                    Ok(Unique)
                }
                fn visit_map<A: serde::de::MapAccess<'de>>(
                    self,
                    mut map: A,
                ) -> Result<Unique, A::Error> {
                    use serde::de::Error;
                    let mut keys = std::collections::BTreeSet::new();
                    while let Some(key) = map.next_key::<String>()? {
                        if !keys.insert(key.clone()) {
                            return Err(A::Error::custom(format!("duplicate JSON field: {key}")));
                        }
                        map.next_value::<Unique>()?;
                    }
                    Ok(Unique)
                }
            }
            deserializer.deserialize_any(Visitor)
        }
    }
    let mut parser = serde_json::Deserializer::from_slice(bytes);
    Unique::deserialize(&mut parser).map_err(|e| e.to_string())?;
    parser.end().map_err(|e| e.to_string())?;
    serde_json::from_slice(bytes).map_err(|e| e.to_string())
}

/// Supported owner requests; every decision is computed by Manifold.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(
    tag = "operation",
    content = "request",
    rename_all = "snake_case",
    deny_unknown_fields
)]
pub enum QuestPeerOwnerRequest {
    /// Apply advisory peer status through the peer owner.
    Peer(rusty_manifold_peer::ManifoldPeerStatusProposal),
    /// Enroll, rotate or revoke a credential through the enrollment owner.
    Enrollment(
        #[serde(with = "owner_enrollment_wire")] rusty_manifold_peer::ManifoldPeerEnrollmentRequest,
    ),
    /// Review reciprocal signatures and consume their nonce through the owner.
    Rendezvous(rusty_manifold_peer::ManifoldRendezvousReviewRequest),
    /// Review the validated observation against retained current authority.
    Session(QuestPeerSessionProjectionConfig),
    /// Explicit session revocation through the owner.
    Revoke(ManifoldPeerSessionRevocation),
}

// The owner's flattened action plus deny_unknown_fields cannot round-trip via
// derive. Decode the same closed wire fields separately, preserving the actual
// typed owner request and rejecting all extra fields in either component.
mod owner_enrollment_wire {
    use super::*;
    use rusty_manifold_peer::{ManifoldPeerEnrollmentAction, ManifoldPeerEnrollmentRequest};
    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Header {
        #[serde(rename = "$schema")]
        schema_id: SchemaId,
        request_id: DottedId,
        expected_authority_revision: Revision,
        operator_id: DottedId,
        issued_at_ms: u64,
    }
    pub fn serialize<S: serde::Serializer>(
        request: &ManifoldPeerEnrollmentRequest,
        serializer: S,
    ) -> Result<S::Ok, S::Error> {
        request.serialize(serializer)
    }
    pub fn deserialize<'de, D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> Result<ManifoldPeerEnrollmentRequest, D::Error> {
        use serde::de::Error;
        let value = serde_json::Value::deserialize(deserializer)?;
        let mut header = value
            .as_object()
            .cloned()
            .ok_or_else(|| D::Error::custom("enrollment request must be an object"))?;
        let action = header
            .remove("action")
            .ok_or_else(|| D::Error::custom("missing enrollment action"))?;
        let fields: &[&str] = match action.as_str() {
            Some("enroll") => &["credential"],
            Some("rotate") => &["prior_key_id", "credential"],
            Some("revoke") => &["key_id", "reason_id"],
            _ => return Err(D::Error::custom("unknown enrollment action")),
        };
        let mut payload = serde_json::Map::new();
        payload.insert("action".into(), action);
        for field in fields {
            payload.insert(
                (*field).into(),
                header
                    .remove(*field)
                    .ok_or_else(|| D::Error::custom(format!("missing enrollment {field}")))?,
            );
        }
        let header: Header =
            serde_json::from_value(serde_json::Value::Object(header)).map_err(D::Error::custom)?;
        let action: ManifoldPeerEnrollmentAction =
            serde_json::from_value(serde_json::Value::Object(payload)).map_err(D::Error::custom)?;
        Ok(ManifoldPeerEnrollmentRequest {
            schema_id: header.schema_id,
            request_id: header.request_id,
            expected_authority_revision: header.expected_authority_revision,
            operator_id: header.operator_id,
            issued_at_ms: header.issued_at_ms,
            action,
        })
    }
}

/// Recomputed owner states and exact decisions for a retained request journal.
#[derive(Clone, Debug, Serialize)]
pub struct QuestPeerOwnerTrace {
    /// Trace schema; this is an adapter projection, not a new owner schema.
    pub schema: String,
    /// Actual peer owner result.
    pub peers: ManifoldAcceptedPeerState,
    /// Actual enrollment owner result.
    pub enrollment: ManifoldPeerEnrollmentState,
    /// Actual signed rendezvous owner result.
    pub rendezvous: ManifoldRendezvousAuthorityState,
    /// Actual session owner result.
    pub sessions: ManifoldPeerSessionState,
    /// Exact owner decision and receipt tuples, including rejected attempts.
    pub decisions: Vec<serde_json::Value>,
}

/// Replay a pinned journal from empty owner states, then review its final request.
/// `nonce_hex` is the digest of the independently re-read pinned fact closure.
/// Callers must retain the journal and CAS its raw digest before any append;
/// resetting or truncating a journal cannot establish durable replay protection.
pub fn review_peer_owner_journal(
    pair: &BleRendezvousPairReceipt,
    policy: &QuestPeerOwnerPolicy,
    steps: &[QuestPeerOwnerStep],
    nonce_hex: &str,
    session_id: &DottedId,
    now_ms: u64,
) -> Result<QuestPeerOwnerTrace, String> {
    if steps.is_empty() || steps.len() > 256 {
        return Err("journal must have 1..=256 requests and a fresh final review time".into());
    }
    review_peer_owner_journal_impl(
        pair,
        policy,
        steps,
        &vec![nonce_hex.to_owned(); steps.len()],
        &vec![false; steps.len()],
        session_id,
        now_ms,
    )
}

/// Reviews the unchanged owner requests with one authenticated raw fact nonce per step.
/// File/identity/lineage authentication belongs to the host input producer. Historical
/// steps keep their original digest and review time; current signed session uses the
/// nonce of its actual retained rendezvous receipt. This grants no lifetime extension.
/// `bound_session_steps` marks only explicit new session requests whose TTL is a
/// requested maximum. Imported historical requests keep false and original semantics.
pub fn review_peer_owner_journal_with_fact_nonces(
    pair: &BleRendezvousPairReceipt,
    policy: &QuestPeerOwnerPolicy,
    steps: &[QuestPeerOwnerStep],
    fact_nonces: &[String],
    bound_session_steps: &[bool],
    session_id: &DottedId,
    now_ms: u64,
) -> Result<QuestPeerOwnerTrace, String> {
    review_peer_owner_journal_impl(
        pair,
        policy,
        steps,
        fact_nonces,
        bound_session_steps,
        session_id,
        now_ms,
    )
}

fn review_peer_owner_journal_impl(
    pair: &BleRendezvousPairReceipt,
    policy: &QuestPeerOwnerPolicy,
    steps: &[QuestPeerOwnerStep],
    fact_nonces: &[String],
    bound_session_steps: &[bool],
    session_id: &DottedId,
    now_ms: u64,
) -> Result<QuestPeerOwnerTrace, String> {
    use rusty_manifold_peer::{
        review_and_apply_peer_enrollment, review_and_apply_peer_proposal,
        review_and_apply_signed_rendezvous, ManifoldPeerDecisionOutcome, ManifoldPeerReviewCase,
        PEER_REVIEW_CASE_SCHEMA,
    };
    if steps.is_empty() || steps.len() > 256 || steps.last().map(|s| s.now_ms) != Some(now_ms) {
        return Err("journal must have 1..=256 requests and a fresh final review time".into());
    }
    if fact_nonces.len() != steps.len()
        || bound_session_steps.len() != steps.len()
        || fact_nonces.iter().any(|nonce_hex| {
            nonce_hex.len() != 64
                || !nonce_hex
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
    {
        return Err("fact closure nonce must be canonical 32-byte lowercase hex".into());
    }
    let mut trace = QuestPeerOwnerTrace {
        schema: "rusty.quest.peer_owner_trace.v1".into(),
        peers: ManifoldAcceptedPeerState {
            schema_id: schema(PEER_SNAPSHOT_SCHEMA),
            authority_revision: Revision::INITIAL,
            peers: vec![],
            applied_proposal_ids: vec![],
        },
        enrollment: ManifoldPeerEnrollmentState::empty(),
        rendezvous: ManifoldRendezvousAuthorityState::empty(),
        sessions: ManifoldPeerSessionState {
            schema_id: schema(PEER_SESSION_SNAPSHOT_SCHEMA),
            authority_revision: Revision::INITIAL,
            sessions: vec![],
            applied_proposal_ids: vec![],
            revoked_session_ids: vec![],
        },
        decisions: vec![],
    };
    let mut prior_time = 0;
    for ((step, nonce_hex), bound_session_ttl) in
        steps.iter().zip(fact_nonces).zip(bound_session_steps)
    {
        if *bound_session_ttl && !matches!(step.request, QuestPeerOwnerRequest::Session(_)) {
            return Err("bounded session TTL mode belongs only to a session request".into());
        }
        if step.now_ms < prior_time || step.now_ms > now_ms {
            return Err("journal review times must be monotonic and not future".into());
        }
        prior_time = step.now_ms;
        let decision = match &step.request {
            QuestPeerOwnerRequest::Peer(request) => {
                let (decision, receipt) = review_and_apply_peer_proposal(&ManifoldPeerReviewCase {
                    schema_id: schema(PEER_REVIEW_CASE_SCHEMA),
                    case_id: request.proposal_id.clone(),
                    current_state: trace.peers.clone(),
                    proposal: request.clone(),
                    trusted_key_fingerprints: policy.trusted_key_fingerprints.clone(),
                    now_ms: step.now_ms,
                    expected_outcome: ManifoldPeerDecisionOutcome::Accepted,
                });
                if let Some(state) = &decision.accepted_state {
                    trace.peers = state.clone();
                }
                serde_json::to_value((decision, receipt))
            }
            QuestPeerOwnerRequest::Enrollment(request) => {
                // A credential cannot substitute for a missing accepted peer proposal.
                match &request.action {
                    rusty_manifold_peer::ManifoldPeerEnrollmentAction::Enroll { credential }
                    | rusty_manifold_peer::ManifoldPeerEnrollmentAction::Rotate {
                        credential,
                        ..
                    } => {
                        let peer = trace
                            .peers
                            .peers
                            .iter()
                            .find(|p| p.identity.peer_id == credential.peer_id)
                            .ok_or("credential peer has no accepted peer proposal")?;
                        if peer.identity.trust_domain != credential.trust_domain
                            || peer.identity.key_fingerprint.as_str()
                                != format!(
                                    "sha256.{}",
                                    credential.public_key_sha256.trim_start_matches("sha256:")
                                )
                        {
                            return Err("credential differs from accepted public identity".into());
                        }
                    }
                    rusty_manifold_peer::ManifoldPeerEnrollmentAction::Revoke { .. } => {}
                }
                let (state, receipt) = review_and_apply_peer_enrollment(
                    &trace.enrollment,
                    request,
                    &policy.trusted_operator_ids,
                    step.now_ms,
                );
                trace.enrollment = state;
                serde_json::to_value(receipt)
            }
            QuestPeerOwnerRequest::Rendezvous(request) => {
                if request.first.nonce_hex != *nonce_hex || request.second.nonce_hex != *nonce_hex {
                    return Err("signed rendezvous differs from the current fact closure".into());
                }
                let (state, receipt) = review_and_apply_signed_rendezvous(
                    &trace.rendezvous,
                    &trace.enrollment,
                    request,
                    step.now_ms,
                );
                trace.rendezvous = state;
                serde_json::to_value(receipt)
            }
            QuestPeerOwnerRequest::Session(config) => {
                if config.now_ms != step.now_ms {
                    return Err("session projection review time mismatch".into());
                }
                let mut proposal = project_observed_ble_peer_session(pair, config)?;
                if &proposal.session_id != session_id {
                    return Err("session differs from fact closure".into());
                }
                let nonce_bytes = (0..64)
                    .step_by(2)
                    .map(|index| u8::from_str_radix(&nonce_hex[index..index + 2], 16))
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|e| e.to_string())?;
                let nonce_digest = format!("sha256:{:x}", Sha256::digest(nonce_bytes));
                let receipt = trace
                    .rendezvous
                    .accepted_receipts
                    .iter()
                    .find(|receipt| receipt.nonce_sha256 == nonce_digest)
                    .ok_or("no owner-accepted signed rendezvous receipt")?
                    .clone();
                if *bound_session_ttl {
                    let mut expiry = receipt.expires_at_ms;
                    for peer_id in [&config.group_owner_peer_id, &config.client_peer_id] {
                        let peer = trace
                            .peers
                            .peers
                            .iter()
                            .find(|p| &p.identity.peer_id == peer_id)
                            .ok_or("session has no accepted current peer")?;
                        expiry = expiry.min(peer.status.expires_at_ms);
                    }
                    for key_id in &receipt.signer_key_ids {
                        let key = trace
                            .enrollment
                            .credentials
                            .iter()
                            .find(|k| &k.key_id == key_id)
                            .ok_or("session has no retained signing credential")?;
                        expiry = expiry.min(key.expires_at_ms);
                    }
                    let ttl = expiry
                        .saturating_sub(step.now_ms)
                        .min(config.authorization_ttl_ms);
                    if ttl < 1_000 {
                        return Err(
                            "current signed/peer/credential session lifetime below 1000ms".into(),
                        );
                    }
                    let mut bounded = config.clone();
                    bounded.authorization_ttl_ms = ttl;
                    proposal = project_observed_ble_peer_session(pair, &bounded)?;
                }
                let (decision, authorization) =
                    review_and_apply_signed_peer_session(&ManifoldSignedPeerSessionReviewCase {
                        schema_id: schema(SIGNED_PEER_SESSION_REVIEW_SCHEMA),
                        session_review: ManifoldPeerSessionReviewCase {
                            schema_id: schema(PEER_SESSION_REVIEW_SCHEMA),
                            accepted_peers: trace.peers.clone(),
                            current_state: trace.sessions.clone(),
                            proposal,
                            trusted_adapter_ids: policy.trusted_adapter_ids.clone(),
                            now_ms: step.now_ms,
                        },
                        rendezvous_receipt: receipt,
                        current_enrollment: trace.enrollment.clone(),
                        current_rendezvous_state: trace.rendezvous.clone(),
                    });
                if let Some(state) = &decision.accepted_state {
                    trace.sessions = state.clone();
                }
                // Retain the signed authority envelope, not only its inner projection.
                serde_json::to_value((decision, authorization))
            }
            QuestPeerOwnerRequest::Revoke(request) => {
                let (state, authorization) =
                    revoke_peer_session(&trace.sessions, request, step.now_ms)?;
                trace.sessions = state;
                serde_json::to_value(authorization)
            }
        }
        .map_err(|error| error.to_string())?;
        trace.decisions.push(decision);
    }
    Ok(trace)
}

fn review(
    accepted_peers: ManifoldAcceptedPeerState,
    current_state: ManifoldPeerSessionState,
    proposal: ManifoldPeerSessionProposal,
    config: &QuestPeerSessionProjectionConfig,
) -> ManifoldPeerSessionReviewCase {
    ManifoldPeerSessionReviewCase {
        schema_id: schema(PEER_SESSION_REVIEW_SCHEMA),
        accepted_peers,
        current_state,
        proposal,
        trusted_adapter_ids: vec![config.adapter_id.clone()],
        now_ms: config.now_ms,
    }
}

fn signed_review(
    session_review: ManifoldPeerSessionReviewCase,
    authority: &QuestPeerSessionAuthorityEvidence,
) -> Result<ManifoldSignedPeerSessionReviewCase, String> {
    Ok(ManifoldSignedPeerSessionReviewCase {
        schema_id: schema(SIGNED_PEER_SESSION_REVIEW_SCHEMA),
        session_review,
        rendezvous_receipt: authority.rendezvous_receipt.clone(),
        current_enrollment: authority.current_enrollment.clone(),
        current_rendezvous_state: authority.current_rendezvous_state.clone(),
    })
}

fn accepted_peers(
    config: &QuestPeerSessionProjectionConfig,
    include_gamma: bool,
) -> ManifoldAcceptedPeerState {
    let mut ids = vec![
        config.subject_peer_id.clone(),
        config.candidate_peer_id.clone(),
    ];
    if include_gamma {
        ids.push(DottedId::new("peer.gamma").expect("static id"));
    }
    ManifoldAcceptedPeerState {
        schema_id: schema(PEER_SNAPSHOT_SCHEMA),
        authority_revision: Revision::INITIAL,
        peers: ids
            .into_iter()
            .map(|peer_id| accepted_peer(peer_id, config.now_ms))
            .collect(),
        applied_proposal_ids: Vec::new(),
    }
}

fn accepted_peer(peer_id: DottedId, now_ms: u64) -> ManifoldAcceptedPeer {
    let fingerprint =
        DottedId::new(format!("fingerprint.{}", peer_id.as_str())).expect("derived fingerprint");
    ManifoldAcceptedPeer {
        identity: ManifoldPeerIdentity {
            schema_id: schema(PEER_IDENTITY_SCHEMA),
            peer_id: peer_id.clone(),
            key_fingerprint: fingerprint,
            trust_domain: DottedId::new("trust.morphospace.peer").expect("static id"),
            roles: vec![ManifoldPeerRole::Observer, ManifoldPeerRole::Rendezvous],
        },
        status: ManifoldPeerStatus {
            schema_id: schema(PEER_STATUS_SCHEMA),
            peer_id,
            status_revision: Revision::INITIAL,
            observed_at_ms: now_ms,
            expires_at_ms: now_ms + 120_000,
            availability: ManifoldPeerAvailability::Ready,
            capability_ids: capability_ids(),
        },
    }
}

fn capability_ids() -> Vec<DottedId> {
    [
        "capability.rendezvous.ble",
        "capability.route.rust-direct-p2p",
        "capability.topology.wifi-direct",
    ]
    .into_iter()
    .map(|value| DottedId::new(value).expect("static capability id"))
    .collect()
}

fn schema(value: &str) -> SchemaId {
    SchemaId::new(value).expect("static schema")
}

fn dotted(value: impl Into<String>) -> Result<DottedId, String> {
    DottedId::new(value).map_err(|error| error.to_string())
}

#[cfg(test)]
fn id(value: &str) -> Result<DottedId, String> {
    DottedId::new(value).map_err(|error| error.to_string())
}

#[cfg(test)]
fn hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(char::from(HEX[usize::from(byte >> 4)]));
        output.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::SigningKey;
    use rusty_manifold_peer::{
        ManifoldPeerCredentialAlgorithm, ManifoldPeerCredentialRecord,
        ManifoldPeerCredentialStatus, PEER_CREDENTIAL_SCHEMA, PEER_ENROLLMENT_STATE_SCHEMA,
        RENDEZVOUS_AUTHORITY_STATE_SCHEMA, RENDEZVOUS_RECEIPT_SCHEMA,
    };

    #[test]
    fn sanitized_pair_fixture_runs_full_decision_matrix() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let pair: BleRendezvousPairReceipt = serde_json::from_str(
            &std::fs::read_to_string(
                root.join("fixtures/device-link/ble-rendezvous-pair.pass.json"),
            )
            .expect("fixture"),
        )
        .expect("pair");
        let config = QuestPeerSessionProjectionConfig {
            subject_peer_id: dotted("peer.alpha").expect("id"),
            candidate_peer_id: dotted("peer.beta").expect("id"),
            group_owner_peer_id: dotted("peer.alpha").expect("id"),
            client_peer_id: dotted("peer.beta").expect("id"),
            adapter_id: dotted("adapter.quest.ble-rendezvous").expect("id"),
            expected_authority_revision: Revision::INITIAL,
            now_ms: 1_000_000,
            authorization_ttl_ms: 60_000,
        };
        let authority = authority_fixture(&config);
        let bundle =
            evaluate_signed_ble_pair_for_peer_session(&pair, &config, &authority).expect("bundle");
        assert!(bundle.accepted_authorization.authorized);
        assert!(!bundle.unauthenticated_authorization.authorized);
        assert!(!bundle.revoked_authorization.authorized);
        assert_eq!(bundle.revoked_state.authority_revision.get(), 3);
    }

    #[test]
    fn unsigned_projection_requires_manifold_authority_evidence() {
        let pair = pair_fixture();
        let config = default_config();
        let error = evaluate_ble_pair_for_peer_session(&pair, &config).expect_err("must reject");
        assert!(error.contains("signed Manifold enrollment/rendezvous authority evidence"));
    }

    fn authority_fixture(
        config: &QuestPeerSessionProjectionConfig,
    ) -> QuestPeerSessionAuthorityEvidence {
        let mut peer_ids = vec![
            config.group_owner_peer_id.clone(),
            config.client_peer_id.clone(),
        ];
        peer_ids.sort();
        let enrollment_revision = Revision::new(3).expect("revision");
        let credentials = [
            (
                config.group_owner_peer_id.clone(),
                id("key.quest.peer.primary").expect("id"),
                7_u8,
            ),
            (
                config.client_peer_id.clone(),
                id("key.quest.peer.secondary").expect("id"),
                11_u8,
            ),
        ]
        .into_iter()
        .map(|(peer_id, key_id, seed)| {
            let public_key = SigningKey::from_bytes(&[seed; 32])
                .verifying_key()
                .to_bytes();
            ManifoldPeerCredentialRecord {
                schema_id: schema(PEER_CREDENTIAL_SCHEMA),
                credential_id: DottedId::new(format!("credential.{peer_id}.1"))
                    .expect("derived id"),
                peer_id,
                trust_domain: id("trust.morphospace.peer").expect("id"),
                key_id,
                key_generation: 1,
                algorithm: ManifoldPeerCredentialAlgorithm::Ed25519,
                public_key_hex: hex(&public_key),
                public_key_sha256: format!("sha256:{}", hex(&Sha256::digest(public_key))),
                valid_from_ms: 1,
                expires_at_ms: config.now_ms + 300_000,
                status: ManifoldPeerCredentialStatus::Active,
                replaced_by_key_id: None,
            }
        })
        .collect::<Vec<_>>();
        let current_enrollment = ManifoldPeerEnrollmentState {
            schema_id: schema(PEER_ENROLLMENT_STATE_SCHEMA),
            authority_revision: enrollment_revision,
            credentials,
            applied_request_ids: Vec::new(),
        };
        let receipt = ManifoldRendezvousReceipt {
            schema_id: schema(RENDEZVOUS_RECEIPT_SCHEMA),
            receipt_id: id("receipt.peer.rendezvous.quest-pair.001").expect("id"),
            request_id: id("request.peer.rendezvous.quest-pair.001").expect("id"),
            accepted: true,
            rejection_reason: None,
            peer_ids,
            group_owner_peer_id: Some(config.group_owner_peer_id.clone()),
            client_peer_id: Some(config.client_peer_id.clone()),
            signer_key_ids: vec![
                id("key.quest.peer.primary").expect("id"),
                id("key.quest.peer.secondary").expect("id"),
            ],
            evidence_ids: vec![
                id("evidence.quest.peer.primary").expect("id"),
                id("evidence.quest.peer.secondary").expect("id"),
            ],
            nonce_sha256: format!("sha256:{}", "a1".repeat(32)),
            coordinator_epoch: 1,
            topology_contract_id: dotted(PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT).expect("id"),
            enrollment_authority_revision: enrollment_revision,
            prior_authority_revision: Revision::INITIAL,
            resulting_authority_revision: Revision::new(2).expect("revision"),
            expires_at_ms: config.now_ms + config.authorization_ttl_ms,
        };
        let current_rendezvous_state = ManifoldRendezvousAuthorityState {
            schema_id: schema(RENDEZVOUS_AUTHORITY_STATE_SCHEMA),
            authority_revision: receipt.resulting_authority_revision,
            applied_request_ids: vec![receipt.request_id.clone()],
            consumed_evidence_ids: receipt.evidence_ids.clone(),
            consumed_nonce_sha256: vec![receipt.nonce_sha256.clone()],
            accepted_receipts: vec![receipt.clone()],
        };
        QuestPeerSessionAuthorityEvidence {
            current_enrollment,
            current_rendezvous_state,
            rendezvous_receipt: receipt,
        }
    }

    fn pair_fixture() -> BleRendezvousPairReceipt {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        serde_json::from_str(
            &std::fs::read_to_string(
                root.join("fixtures/device-link/ble-rendezvous-pair.pass.json"),
            )
            .expect("fixture"),
        )
        .expect("pair")
    }

    fn default_config() -> QuestPeerSessionProjectionConfig {
        QuestPeerSessionProjectionConfig {
            subject_peer_id: dotted("peer.alpha").expect("id"),
            candidate_peer_id: dotted("peer.beta").expect("id"),
            group_owner_peer_id: dotted("peer.alpha").expect("id"),
            client_peer_id: dotted("peer.beta").expect("id"),
            adapter_id: dotted("adapter.quest.ble-rendezvous").expect("id"),
            expected_authority_revision: Revision::INITIAL,
            now_ms: 1_000_000,
            authorization_ttl_ms: 60_000,
        }
    }
}
