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
