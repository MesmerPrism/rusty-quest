//! Thin Quest projection from live rendezvous plus advisory three-peer inputs.

use rusty_manifold_model::{DottedId, Revision, SchemaId};
use rusty_manifold_peer::{
    expire_peer_mesh_members, review_and_apply_peer_mesh, revoke_peer_mesh_member,
    ManifoldAcceptedMeshMember, ManifoldAcceptedPeer, ManifoldAcceptedPeerState,
    ManifoldPeerAvailability, ManifoldPeerIdentity, ManifoldPeerMeshDecision,
    ManifoldPeerMeshMutationReceipt, ManifoldPeerMeshPairEvidence, ManifoldPeerMeshProposal,
    ManifoldPeerMeshRejectionReason, ManifoldPeerMeshReviewCase, ManifoldPeerMeshRevocation,
    ManifoldPeerMeshRouteCandidate, ManifoldPeerMeshRouteClass, ManifoldPeerMeshState,
    ManifoldPeerRole, ManifoldPeerStatus, ADVISORY_STATUS_ROUTE_CONTRACT,
    DIRECT_P2P_ROUTE_CONTRACT, PEER_IDENTITY_SCHEMA, PEER_MESH_PROPOSAL_SCHEMA,
    PEER_MESH_REVIEW_SCHEMA, PEER_MESH_STATE_SCHEMA, PEER_SNAPSHOT_SCHEMA, PEER_STATUS_SCHEMA,
    PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT,
};
use rusty_quest_device_link::{validate_ble_rendezvous_pair_receipt, BleRendezvousPairReceipt};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Exact product-owned parameters for projecting a configured third peer.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPeerMeshProjectionConfig {
    /// First live Quest peer.
    pub subject_peer_id: DottedId,
    /// Second live Quest peer.
    pub candidate_peer_id: DottedId,
    /// Configured low-rate third peer.
    pub configured_peer_id: DottedId,
    /// Trusted projection adapter id.
    pub proposer_id: DottedId,
    /// Review time.
    pub now_ms: u64,
    /// Live peer status lifetime.
    pub live_status_ttl_ms: u64,
    /// Configured peer status lifetime.
    pub configured_status_ttl_ms: u64,
    /// Route observation lifetime.
    pub route_ttl_ms: u64,
    /// Live direct-pair observed latency.
    pub live_pair_latency_ms: u32,
}

/// Host/device scorecard bundle for the three-peer projection.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPeerMeshDecisionBundle {
    /// Bundle schema.
    pub schema: String,
    /// Termux profile remained source/privacy only.
    pub termux_source_privacy_only: bool,
    /// Sidecar plan remained proposal-only.
    pub sidecar_advisory_only: bool,
    /// Accepted three-peer decision.
    pub accepted_decision: ManifoldPeerMeshDecision,
    /// Replay rejection.
    pub replay_decision: ManifoldPeerMeshDecision,
    /// Same-epoch split-brain rejection.
    pub split_brain_decision: ManifoldPeerMeshDecision,
    /// Advisory-to-direct/media substitution rejection.
    pub advisory_media_decision: ManifoldPeerMeshDecision,
    /// State after configured-peer expiry.
    pub expired_state: ManifoldPeerMeshState,
    /// Expiry receipt.
    pub expiry_receipt: ManifoldPeerMeshMutationReceipt,
    /// State after explicit configured-peer revocation.
    pub revoked_state: ManifoldPeerMeshState,
    /// Revocation receipt.
    pub revocation_receipt: ManifoldPeerMeshMutationReceipt,
}

/// Evaluate a live authenticated pair plus one configured advisory peer.
pub fn evaluate_configured_n_peer_mesh(
    pair: &BleRendezvousPairReceipt,
    termux_profile: &Value,
    sidecar_plan: &Value,
    config: &QuestPeerMeshProjectionConfig,
) -> Result<QuestPeerMeshDecisionBundle, String> {
    validate_ble_rendezvous_pair_receipt(pair).map_err(|errors| {
        errors
            .into_iter()
            .map(|error| error.message)
            .collect::<Vec<_>>()
            .join("; ")
    })?;
    validate_config(config)?;
    validate_termux_profile(termux_profile)?;
    validate_sidecar_plan(sidecar_plan)?;

    let case = review_case(config)?;
    let accepted_decision = review_and_apply_peer_mesh(&case);
    let accepted = accepted_decision
        .accepted_state
        .clone()
        .ok_or_else(|| "configured N-peer proposal was not accepted".to_string())?;
    if accepted.members.len() != 3 || accepted.selected_routes.len() != 1 {
        return Err("accepted N-peer shape drifted".to_string());
    }

    let mut replay = case.clone();
    replay.current_state = accepted.clone();
    replay.proposal.expected_authority_revision = accepted.authority_revision;
    let replay_decision = review_and_apply_peer_mesh(&replay);
    require_rejection(
        &replay_decision,
        ManifoldPeerMeshRejectionReason::ReplayedProposal,
        "replay",
    )?;

    let mut split = case.clone();
    split.current_state = accepted.clone();
    split.current_state.coordinator_peer_id = Some(config.candidate_peer_id.clone());
    split.proposal.proposal_id = id("proposal.peer-mesh.configured.split-brain")?;
    split.proposal.expected_authority_revision = accepted.authority_revision;
    let split_brain_decision = review_and_apply_peer_mesh(&split);
    require_rejection(
        &split_brain_decision,
        ManifoldPeerMeshRejectionReason::SplitBrain,
        "split-brain",
    )?;

    let mut media = case.clone();
    media.proposal.proposal_id = id("proposal.peer-mesh.configured.media-gossip")?;
    let advisory = media
        .proposal
        .route_candidates
        .iter_mut()
        .find(|route| route.route_class == ManifoldPeerMeshRouteClass::AdvisoryStatusOnly)
        .ok_or_else(|| "advisory route missing".to_string())?;
    advisory.route_contract_id = id(DIRECT_P2P_ROUTE_CONTRACT)?;
    let advisory_media_decision = review_and_apply_peer_mesh(&media);
    require_rejection(
        &advisory_media_decision,
        ManifoldPeerMeshRejectionReason::MediaGossipForbidden,
        "advisory media",
    )?;

    let configured_expiry = config
        .now_ms
        .saturating_add(config.configured_status_ttl_ms);
    let (expired_state, expiry_receipt) = expire_peer_mesh_members(
        &accepted,
        id("sweep.peer-mesh.configured-expiry")?,
        configured_expiry,
    )?;
    if !expiry_receipt
        .removed_peer_ids
        .contains(&config.configured_peer_id)
        || expiry_receipt.mesh_active
        || !expired_state.members.is_empty()
    {
        return Err("configured peer expiry did not close the undersized mesh".to_string());
    }

    let (revoked_state, revocation_receipt) = revoke_peer_mesh_member(
        &accepted,
        &ManifoldPeerMeshRevocation {
            revocation_id: id("revoke.peer-mesh.configured-peer")?,
            peer_id: config.configured_peer_id.clone(),
            expected_authority_revision: accepted.authority_revision,
        },
    )?;

    Ok(QuestPeerMeshDecisionBundle {
        schema: "rusty.quest.peer_mesh_decision_bundle.v1".to_string(),
        termux_source_privacy_only: true,
        sidecar_advisory_only: true,
        accepted_decision,
        replay_decision,
        split_brain_decision,
        advisory_media_decision,
        expired_state,
        expiry_receipt,
        revoked_state,
        revocation_receipt,
    })
}

fn validate_config(config: &QuestPeerMeshProjectionConfig) -> Result<(), String> {
    let members = BTreeMembers::new([
        config.subject_peer_id.clone(),
        config.candidate_peer_id.clone(),
        config.configured_peer_id.clone(),
    ]);
    if members.values.len() != 3 {
        return Err("three distinct peer ids are required".to_string());
    }
    if config.now_ms == 0
        || !(10_000..=300_000).contains(&config.live_status_ttl_ms)
        || !(1_000..config.live_status_ttl_ms).contains(&config.configured_status_ttl_ms)
        || !(1_000..=120_000).contains(&config.route_ttl_ms)
        || config.live_pair_latency_ms == 0
    {
        return Err("peer mesh timing configuration is invalid".to_string());
    }
    Ok(())
}

fn validate_termux_profile(profile: &Value) -> Result<(), String> {
    if profile["schema"] != "quest-termux-lab.peer-workflow-source-profile.v1"
        || profile["owner"] != "quest-termux-lab"
        || profile["contributes_phases"] != serde_json::json!(["source", "privacy"])
        || profile["authority_boundary"]["runtime_authority"] != false
        || profile["authority_boundary"]["accepted_state_owner"] != "rusty.manifold"
        || profile["authority_boundary"]["command_authority"] != false
        || profile["privacy_boundary"]["allows_high_rate_payloads"] != false
    {
        return Err("Termux source/privacy boundary is invalid".to_string());
    }
    let has_topology = profile["source_artifacts"]
        .as_array()
        .is_some_and(|artifacts| {
            artifacts.iter().any(|artifact| {
                artifact["schema"] == "quest-termux-lab.peer-topology-report.v1"
                    && artifact["role"] == "n_peer_advisory_topology"
            })
        });
    if !has_topology {
        return Err("Termux profile lacks sanitized N-peer topology evidence".to_string());
    }
    Ok(())
}

fn validate_sidecar_plan(plan: &Value) -> Result<(), String> {
    if plan["schema"] != "rusty.quest.sidecar.configured_peer_rehearsal_plan.v1"
        || plan["rehearsal_scope"]["peer_count"] != 3
        || plan["rehearsal_scope"]["status_payload_class"] != "low_rate_advisory_status"
        || plan["authority"]["sidecar_role"] != "observer_proposer"
        || plan["authority"]["acceptance_owner"] != "rusty.manifold"
        || plan["authority"]["sidecar_device_action_authority"] != "forbidden"
        || plan["authority"]["sidecar_command_authority"] != "forbidden"
        || plan["transport_policy"]["status_payload_only"] != true
        || plan["transport_policy"]["commands_allowed"] != false
        || plan["transport_policy"]["adb_allowed"] != false
        || plan["transport_policy"]["fixture_contains_endpoint_values"] != false
        || plan["evidence_policy"]["contains_pairing_material"] != false
        || plan["evidence_policy"]["contains_raw_logs"] != false
    {
        return Err("sidecar configured-peer boundary is invalid".to_string());
    }
    Ok(())
}

fn review_case(
    config: &QuestPeerMeshProjectionConfig,
) -> Result<ManifoldPeerMeshReviewCase, String> {
    let members = BTreeMembers::new([
        config.subject_peer_id.clone(),
        config.candidate_peer_id.clone(),
        config.configured_peer_id.clone(),
    ]);
    let coordinator = members.values[0].clone();
    let live_expiry = config.now_ms.saturating_add(config.live_status_ttl_ms);
    let configured_expiry = config
        .now_ms
        .saturating_add(config.configured_status_ttl_ms);
    let route_expiry = config.now_ms.saturating_add(config.route_ttl_ms);
    Ok(ManifoldPeerMeshReviewCase {
        schema_id: schema(PEER_MESH_REVIEW_SCHEMA),
        accepted_peers: ManifoldAcceptedPeerState {
            schema_id: schema(PEER_SNAPSHOT_SCHEMA),
            authority_revision: Revision::new(4)
                .ok_or_else(|| "accepted peer revision must be non-zero".to_string())?,
            peers: vec![
                peer(config.subject_peer_id.clone(), config.now_ms, live_expiry),
                peer(config.candidate_peer_id.clone(), config.now_ms, live_expiry),
                peer(
                    config.configured_peer_id.clone(),
                    config.now_ms,
                    configured_expiry,
                ),
            ],
            applied_proposal_ids: Vec::new(),
        },
        accepted_pair_evidence: vec![pair_evidence(config, route_expiry)?],
        current_state: ManifoldPeerMeshState {
            schema_id: schema(PEER_MESH_STATE_SCHEMA),
            authority_revision: Revision::INITIAL,
            mesh_id: None,
            authority_epoch: 0,
            coordinator_peer_id: None,
            members: Vec::<ManifoldAcceptedMeshMember>::new(),
            selected_routes: Vec::new(),
            applied_proposal_ids: Vec::new(),
            revoked_peer_ids: Vec::new(),
        },
        proposal: ManifoldPeerMeshProposal {
            schema_id: schema(PEER_MESH_PROPOSAL_SCHEMA),
            proposal_id: id("proposal.peer-mesh.configured.001")?,
            mesh_id: id("mesh.quest.configured-low-rate.001")?,
            expected_authority_revision: Revision::INITIAL,
            proposer_id: config.proposer_id.clone(),
            authority_epoch: 1,
            coordinator_peer_id: coordinator,
            member_peer_ids: members.values,
            route_candidates: vec![
                ManifoldPeerMeshRouteCandidate {
                    candidate_id: id("route.live-quest-pair.direct")?,
                    source_peer_id: config.subject_peer_id.clone(),
                    target_peer_id: config.candidate_peer_id.clone(),
                    route_class: ManifoldPeerMeshRouteClass::DirectPairwise,
                    route_contract_id: id(DIRECT_P2P_ROUTE_CONTRACT)?,
                    pair_evidence_receipt_id: Some(id("receipt.peer.rendezvous.quest-pair.001")?),
                    observed_latency_ms: config.live_pair_latency_ms,
                    hop_count: 1,
                    evidence_expires_at_ms: route_expiry,
                },
                advisory_route(
                    "route.configured-peer.advisory.subject",
                    &config.subject_peer_id,
                    &config.configured_peer_id,
                    route_expiry,
                )?,
                advisory_route(
                    "route.configured-peer.advisory.candidate",
                    &config.candidate_peer_id,
                    &config.configured_peer_id,
                    route_expiry,
                )?,
            ],
        },
        trusted_proposer_ids: vec![config.proposer_id.clone()],
        now_ms: config.now_ms,
    })
}

fn advisory_route(
    candidate_id: &str,
    first: &DottedId,
    second: &DottedId,
    expiry: u64,
) -> Result<ManifoldPeerMeshRouteCandidate, String> {
    Ok(ManifoldPeerMeshRouteCandidate {
        candidate_id: id(candidate_id)?,
        source_peer_id: first.clone(),
        target_peer_id: second.clone(),
        route_class: ManifoldPeerMeshRouteClass::AdvisoryStatusOnly,
        route_contract_id: id(ADVISORY_STATUS_ROUTE_CONTRACT)?,
        pair_evidence_receipt_id: None,
        observed_latency_ms: 50,
        hop_count: 1,
        evidence_expires_at_ms: expiry,
    })
}

fn pair_evidence(
    config: &QuestPeerMeshProjectionConfig,
    expires_at_ms: u64,
) -> Result<ManifoldPeerMeshPairEvidence, String> {
    let pair = BTreeMembers::new([
        config.subject_peer_id.clone(),
        config.candidate_peer_id.clone(),
    ]);
    Ok(ManifoldPeerMeshPairEvidence {
        receipt_id: id("receipt.peer.rendezvous.quest-pair.001")?,
        peer_ids: pair.values.clone(),
        signer_key_ids: vec![
            id("key.quest.peer.primary")?,
            id("key.quest.peer.secondary")?,
        ],
        evidence_sha256: format!("sha256:{}", "ab".repeat(32)),
        pair_authority_revision: Revision::INITIAL,
        pair_authority_epoch: 1,
        topology_contract_id: id(PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT)?,
        expires_at_ms,
    })
}

fn peer(peer_id: DottedId, now_ms: u64, expires_at_ms: u64) -> ManifoldAcceptedPeer {
    ManifoldAcceptedPeer {
        identity: ManifoldPeerIdentity {
            schema_id: schema(PEER_IDENTITY_SCHEMA),
            key_fingerprint: DottedId::new(format!("fingerprint.{peer_id}"))
                .expect("derived fingerprint"),
            peer_id: peer_id.clone(),
            trust_domain: DottedId::new("trust.morphospace.peer").expect("trust id"),
            roles: vec![ManifoldPeerRole::Observer, ManifoldPeerRole::Rendezvous],
        },
        status: ManifoldPeerStatus {
            schema_id: schema(PEER_STATUS_SCHEMA),
            peer_id,
            status_revision: Revision::INITIAL,
            observed_at_ms: now_ms,
            expires_at_ms,
            availability: ManifoldPeerAvailability::Ready,
            capability_ids: vec![DottedId::new("capability.peer.mesh.status").expect("capability")],
        },
    }
}

fn require_rejection(
    decision: &ManifoldPeerMeshDecision,
    reason: ManifoldPeerMeshRejectionReason,
    label: &str,
) -> Result<(), String> {
    if decision.rejection_reason != Some(reason) || decision.applied {
        return Err(format!("{label} matrix row did not fail closed"));
    }
    Ok(())
}

struct BTreeMembers {
    values: Vec<DottedId>,
}

impl BTreeMembers {
    fn new<const N: usize>(values: [DottedId; N]) -> Self {
        Self {
            values: values
                .into_iter()
                .collect::<std::collections::BTreeSet<_>>()
                .into_iter()
                .collect(),
        }
    }
}

fn schema(value: &str) -> SchemaId {
    SchemaId::new(value).expect("static schema")
}

fn id(value: &str) -> Result<DottedId, String> {
    DottedId::new(value).map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> BleRendezvousPairReceipt {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        serde_json::from_str(
            &std::fs::read_to_string(
                root.join("fixtures/device-link/ble-rendezvous-pair.pass.json"),
            )
            .expect("pair fixture"),
        )
        .expect("pair")
    }

    fn profile() -> Value {
        serde_json::json!({
            "schema":"quest-termux-lab.peer-workflow-source-profile.v1","owner":"quest-termux-lab","contributes_phases":["source","privacy"],
            "source_artifacts":[{"schema":"quest-termux-lab.peer-topology-report.v1","role":"n_peer_advisory_topology"}],
            "privacy_boundary":{"allows_high_rate_payloads":false},
            "authority_boundary":{"runtime_authority":false,"accepted_state_owner":"rusty.manifold","command_authority":false}
        })
    }

    fn plan() -> Value {
        serde_json::json!({
            "schema":"rusty.quest.sidecar.configured_peer_rehearsal_plan.v1",
            "rehearsal_scope":{"peer_count":3,"status_payload_class":"low_rate_advisory_status"},
            "authority":{"sidecar_role":"observer_proposer","acceptance_owner":"rusty.manifold","sidecar_device_action_authority":"forbidden","sidecar_command_authority":"forbidden"},
            "transport_policy":{"status_payload_only":true,"commands_allowed":false,"adb_allowed":false,"fixture_contains_endpoint_values":false},
            "evidence_policy":{"contains_pairing_material":false,"contains_raw_logs":false}
        })
    }

    fn config() -> QuestPeerMeshProjectionConfig {
        QuestPeerMeshProjectionConfig {
            subject_peer_id: id("peer.alpha").expect("id"),
            candidate_peer_id: id("peer.beta").expect("id"),
            configured_peer_id: id("peer.gamma").expect("id"),
            proposer_id: id("adapter.quest.peer-mesh").expect("id"),
            now_ms: 1_000_000,
            live_status_ttl_ms: 120_000,
            configured_status_ttl_ms: 30_000,
            route_ttl_ms: 60_000,
            live_pair_latency_ms: 12,
        }
    }

    #[test]
    fn live_pair_plus_configured_peer_runs_full_authority_matrix() {
        let bundle = evaluate_configured_n_peer_mesh(&pair(), &profile(), &plan(), &config())
            .expect("bundle");
        let accepted = bundle.accepted_decision.accepted_state.expect("state");
        assert_eq!(accepted.members.len(), 3);
        assert_eq!(accepted.selected_routes.len(), 1);
        assert!(bundle
            .expiry_receipt
            .removed_peer_ids
            .contains(&id("peer.gamma").expect("id")));
        assert!(!bundle.expiry_receipt.mesh_active);
        assert!(bundle
            .revoked_state
            .revoked_peer_ids
            .contains(&id("peer.gamma").expect("id")));
    }

    #[test]
    fn termux_and_sidecar_authority_or_media_bleed_rejects() {
        let mut bad_profile = profile();
        bad_profile["authority_boundary"]["runtime_authority"] = Value::Bool(true);
        assert!(
            evaluate_configured_n_peer_mesh(&pair(), &bad_profile, &plan(), &config()).is_err()
        );
        let mut bad_plan = plan();
        bad_plan["transport_policy"]["commands_allowed"] = Value::Bool(true);
        assert!(
            evaluate_configured_n_peer_mesh(&pair(), &profile(), &bad_plan, &config()).is_err()
        );
    }
}
