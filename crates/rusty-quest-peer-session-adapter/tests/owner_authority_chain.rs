//! Host fixtures exercise actual Manifold owner decisions; keys are test-only.
use ed25519_dalek::{Signer, SigningKey};
use rusty_manifold_model::{DottedId, Revision, SchemaId};
use rusty_manifold_peer::*;
use rusty_quest_device_link::BleRendezvousPairReceipt;
use rusty_quest_peer_session_adapter::*;
use sha2::{Digest, Sha256};

const NOW: u64 = 1_000_000;
fn id(text: &str) -> DottedId {
    DottedId::new(text).unwrap()
}
fn schema(text: &str) -> SchemaId {
    SchemaId::new(text).unwrap()
}
fn rev(value: u64) -> Revision {
    Revision::new(value).unwrap()
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn pair() -> BleRendezvousPairReceipt {
    serde_json::from_str(include_str!(
        "../../../fixtures/device-link/ble-rendezvous-pair.pass.json"
    ))
    .unwrap()
}
fn setup() -> (QuestPeerOwnerPolicy, Vec<QuestPeerOwnerStep>) {
    let keys = [
        SigningKey::from_bytes(&[7; 32]),
        SigningKey::from_bytes(&[11; 32]),
    ];
    let peers = [id("peer.alpha"), id("peer.beta")];
    let key_ids = [id("key.alpha"), id("key.beta")];
    let fingerprints: Vec<_> = keys
        .iter()
        .map(|k| {
            id(&format!(
                "sha256.{:x}",
                Sha256::digest(k.verifying_key().to_bytes())
            ))
        })
        .collect();
    let policy = QuestPeerOwnerPolicy {
        trusted_operator_ids: vec![id("operator.test")],
        trusted_key_fingerprints: fingerprints.clone(),
        trusted_adapter_ids: vec![id("adapter.quest.ble-rendezvous")],
    };
    let mut steps = vec![];
    for index in 0..2 {
        steps.push(QuestPeerOwnerStep {
            now_ms: NOW,
            request: QuestPeerOwnerRequest::Peer(ManifoldPeerStatusProposal {
                schema_id: schema(PEER_PROPOSAL_SCHEMA),
                proposal_id: id(&format!("proposal.peer.{index}")),
                expected_authority_revision: rev(1 + index as u64),
                proposer_id: id("operator.test"),
                identity: ManifoldPeerIdentity {
                    schema_id: schema(PEER_IDENTITY_SCHEMA),
                    peer_id: peers[index].clone(),
                    key_fingerprint: fingerprints[index].clone(),
                    trust_domain: id("trust.test"),
                    roles: vec![ManifoldPeerRole::Observer, ManifoldPeerRole::Rendezvous],
                },
                status: ManifoldPeerStatus {
                    schema_id: schema(PEER_STATUS_SCHEMA),
                    peer_id: peers[index].clone(),
                    status_revision: Revision::INITIAL,
                    observed_at_ms: NOW,
                    expires_at_ms: NOW + 60_000,
                    availability: ManifoldPeerAvailability::Ready,
                    capability_ids: [
                        "capability.rendezvous.ble",
                        "capability.route.rust-direct-p2p",
                        "capability.topology.wifi-direct",
                    ]
                    .into_iter()
                    .map(id)
                    .collect(),
                },
                payload_class: ManifoldPeerPayloadClass::LowRateDescriptor,
            }),
        });
    }
    for index in 0..2 {
        let public = keys[index].verifying_key().to_bytes();
        steps.push(QuestPeerOwnerStep {
            now_ms: NOW,
            request: QuestPeerOwnerRequest::Enrollment(ManifoldPeerEnrollmentRequest {
                schema_id: schema(PEER_ENROLLMENT_REQUEST_SCHEMA),
                request_id: id(&format!("request.enroll.{index}")),
                expected_authority_revision: rev(1 + index as u64),
                operator_id: id("operator.test"),
                issued_at_ms: NOW,
                action: ManifoldPeerEnrollmentAction::Enroll {
                    credential: ManifoldPeerCredentialRecord {
                        schema_id: schema(PEER_CREDENTIAL_SCHEMA),
                        credential_id: id(&format!("credential.peer.{index}")),
                        peer_id: peers[index].clone(),
                        trust_domain: id("trust.test"),
                        key_id: key_ids[index].clone(),
                        key_generation: 1,
                        algorithm: ManifoldPeerCredentialAlgorithm::Ed25519,
                        public_key_hex: hex(&public),
                        public_key_sha256: format!("sha256:{:x}", Sha256::digest(public)),
                        valid_from_ms: NOW,
                        expires_at_ms: NOW + 300_000,
                        status: ManifoldPeerCredentialStatus::Active,
                        replaced_by_key_id: None,
                    },
                },
            }),
        });
    }
    let mut evidence: Vec<_> = (0..2)
        .map(|index| ManifoldSignedRendezvousEvidence {
            schema_id: schema(SIGNED_RENDEZVOUS_EVIDENCE_SCHEMA),
            evidence_id: id(&format!("evidence.peer.{index}")),
            signer_peer_id: peers[index].clone(),
            signer_key_id: key_ids[index].clone(),
            counterparty_peer_id: peers[1 - index].clone(),
            nonce_hex: "ab".repeat(32),
            coordinator_epoch: 1,
            role: if index == 0 {
                ManifoldRendezvousRole::GroupOwner
            } else {
                ManifoldRendezvousRole::Client
            },
            topology_contract_id: id(PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT),
            issued_at_ms: NOW,
            expires_at_ms: NOW + 60_000,
            signature_hex: "00".repeat(64),
        })
        .collect();
    for index in 0..2 {
        evidence[index].signature_hex = hex(&keys[index]
            .sign(&rendezvous_signing_bytes(&evidence[index]))
            .to_bytes());
    }
    steps.push(QuestPeerOwnerStep {
        now_ms: NOW,
        request: QuestPeerOwnerRequest::Rendezvous(ManifoldRendezvousReviewRequest {
            schema_id: schema(RENDEZVOUS_REVIEW_REQUEST_SCHEMA),
            request_id: id("request.rendezvous.1"),
            expected_authority_revision: Revision::INITIAL,
            expected_enrollment_authority_revision: rev(3),
            first: evidence[0].clone(),
            second: evidence[1].clone(),
        }),
    });
    steps.push(QuestPeerOwnerStep {
        now_ms: NOW,
        request: QuestPeerOwnerRequest::Session(QuestPeerSessionProjectionConfig {
            subject_peer_id: peers[0].clone(),
            candidate_peer_id: peers[1].clone(),
            group_owner_peer_id: peers[0].clone(),
            client_peer_id: peers[1].clone(),
            adapter_id: policy.trusted_adapter_ids[0].clone(),
            expected_authority_revision: Revision::INITIAL,
            now_ms: NOW,
            authorization_ttl_ms: 60_000,
        }),
    });
    (policy, steps)
}
fn review(
    policy: &QuestPeerOwnerPolicy,
    steps: &[QuestPeerOwnerStep],
) -> Result<QuestPeerOwnerTrace, String> {
    review_peer_owner_journal(
        &pair(),
        policy,
        steps,
        &"ab".repeat(32),
        &id("session.peer.pair-fixture-001"),
        NOW,
    )
}
#[test]
fn real_owner_chain_retains_all_decisions_and_signed_envelope() {
    let (policy, steps) = setup();
    let trace = review(&policy, &steps).unwrap();
    assert_eq!(trace.peers.peers.len(), 2);
    assert_eq!(trace.enrollment.credentials.len(), 2);
    assert_eq!(trace.rendezvous.accepted_receipts.len(), 1);
    assert_eq!(trace.sessions.sessions.len(), 1);
    assert_eq!(trace.decisions.len(), 6);
    assert_eq!(
        trace.decisions[5][1]["topology_authorization"]["authorized"],
        true
    );
    assert!(!trace.decisions[5][1]["rendezvous_receipt_id"].is_null());
}

#[test]
fn lineage_bounds_new_session_without_rewriting_prior_overshoot_rejection() {
    let (policy, mut steps) = setup();
    steps[5].now_ms = NOW + 1_567;
    if let QuestPeerOwnerRequest::Session(config) = &mut steps[5].request {
        config.now_ms = NOW + 1_567;
    }
    let prior = review_peer_owner_journal(
        &pair(),
        &policy,
        &steps,
        &"ab".repeat(32),
        &id("session.peer.pair-fixture-001"),
        NOW + 1_567,
    )
    .unwrap();
    assert_eq!(
        prior.decisions[5][0]["rejection_reason"],
        "signed_rendezvous_mismatch"
    );
    assert!(prior.sessions.sessions.is_empty());
    let mut repair = steps[5].clone();
    repair.now_ms = NOW + 2_000;
    if let QuestPeerOwnerRequest::Session(config) = &mut repair.request {
        config.now_ms = repair.now_ms;
    }
    steps.push(repair);
    let mut bounds = vec![false; steps.len()];
    bounds[6] = true;
    let repaired = review_peer_owner_journal_with_fact_nonces(
        &pair(),
        &policy,
        &steps,
        &vec!["ab".repeat(32); steps.len()],
        &bounds,
        &id("session.peer.pair-fixture-001"),
        NOW + 2_000,
    )
    .unwrap();
    assert_eq!(repaired.decisions[5], prior.decisions[5]);
    assert_eq!(
        repaired.decisions[6][1]["topology_authorization"]["authorized"],
        true
    );
    assert_eq!(
        repaired.sessions.sessions[0].proposal.expires_at_ms,
        NOW + 60_000
    );
    let mut wrong_bounds = bounds.clone();
    wrong_bounds[0] = true;
    assert!(review_peer_owner_journal_with_fact_nonces(
        &pair(),
        &policy,
        &steps,
        &vec!["ab".repeat(32); steps.len()],
        &wrong_bounds,
        &id("session.peer.pair-fixture-001"),
        NOW + 2_000
    )
    .is_err());
    let mut short_credentials = steps.clone();
    for entry in &mut short_credentials[2..4] {
        if let QuestPeerOwnerRequest::Enrollment(request) = &mut entry.request {
            if let ManifoldPeerEnrollmentAction::Enroll { credential } = &mut request.action {
                credential.expires_at_ms = NOW + 3_000;
            }
        }
    }
    let short = review_peer_owner_journal_with_fact_nonces(
        &pair(),
        &policy,
        &short_credentials,
        &vec!["ab".repeat(32); steps.len()],
        &bounds,
        &id("session.peer.pair-fixture-001"),
        NOW + 2_000,
    )
    .unwrap();
    assert_eq!(
        short.sessions.sessions[0].proposal.expires_at_ms,
        NOW + 3_000
    );
    let mut expired = steps;
    expired[6].now_ms = NOW + 59_001;
    if let QuestPeerOwnerRequest::Session(config) = &mut expired[6].request {
        config.now_ms = NOW + 59_001;
    }
    assert!(review_peer_owner_journal_with_fact_nonces(
        &pair(),
        &policy,
        &expired,
        &vec!["ab".repeat(32); expired.len()],
        &bounds,
        &id("session.peer.pair-fixture-001"),
        NOW + 59_001
    )
    .unwrap_err()
    .contains("below 1000ms"));
}

#[test]
fn lineage_authenticates_fresh_nonce_preserves_history_and_keeps_replay_denials() {
    let (policy, mut steps) = setup();
    steps[5].now_ms = NOW + 1_567;
    if let QuestPeerOwnerRequest::Session(config) = &mut steps[5].request {
        config.now_ms = NOW + 1_567;
    }
    let original = steps.clone();
    let renew_time = NOW + 65_000;
    for index in 0..2 {
        let mut renewal = steps[index].clone();
        renewal.now_ms = renew_time;
        if let QuestPeerOwnerRequest::Peer(proposal) = &mut renewal.request {
            proposal.proposal_id = id(&format!("proposal.peer.renew.{index}"));
            proposal.expected_authority_revision = rev(3 + index as u64);
            proposal.status.status_revision = rev(2);
            proposal.status.observed_at_ms = renew_time;
            proposal.status.expires_at_ms = renew_time + 60_000;
        }
        steps.push(renewal);
    }
    let keys = [
        SigningKey::from_bytes(&[7; 32]),
        SigningKey::from_bytes(&[11; 32]),
    ];
    let mut renewal = original[4].clone();
    renewal.now_ms = renew_time;
    if let QuestPeerOwnerRequest::Rendezvous(request) = &mut renewal.request {
        // Lexically earlier id tests selection by authenticated nonce, not sorted receipt .last().
        request.request_id = id("request.rendezvous.0renew");
        request.expected_authority_revision = rev(2);
        for (index, evidence) in [&mut request.first, &mut request.second]
            .into_iter()
            .enumerate()
        {
            evidence.evidence_id = id(&format!("evidence.renew.{index}"));
            evidence.nonce_hex = "cd".repeat(32);
            evidence.issued_at_ms = renew_time;
            evidence.expires_at_ms = renew_time + 60_000;
            evidence.signature_hex = hex(&keys[index]
                .sign(&rendezvous_signing_bytes(evidence))
                .to_bytes());
        }
    }
    steps.push(renewal.clone());
    let mut session = original[5].clone();
    session.now_ms = renew_time + 1_567;
    if let QuestPeerOwnerRequest::Session(config) = &mut session.request {
        config.now_ms = session.now_ms;
    }
    steps.push(session);
    let nonces: Vec<_> = (0..steps.len())
        .map(|i| {
            if i < original.len() {
                "ab".repeat(32)
            } else {
                "cd".repeat(32)
            }
        })
        .collect();
    let mut bounds = vec![false; steps.len()];
    *bounds.last_mut().unwrap() = true;
    let trace = review_peer_owner_journal_with_fact_nonces(
        &pair(),
        &policy,
        &steps,
        &nonces,
        &bounds,
        &id("session.peer.pair-fixture-001"),
        renew_time + 1_567,
    )
    .unwrap();
    assert_eq!(
        serde_json::to_value(&steps[..original.len()]).unwrap(),
        serde_json::to_value(&original).unwrap()
    );
    assert_eq!(trace.rendezvous.accepted_receipts.len(), 2);
    assert_eq!(
        trace.decisions.last().unwrap()[1]["topology_authorization"]["authorized"],
        true
    );
    assert_eq!(
        trace.sessions.sessions[0].proposal.expires_at_ms,
        renew_time + 60_000
    );
    let mut wrong = nonces.clone();
    *wrong.last_mut().unwrap() = "ef".repeat(32);
    assert!(review_peer_owner_journal_with_fact_nonces(
        &pair(),
        &policy,
        &steps,
        &wrong,
        &bounds,
        &id("session.peer.pair-fixture-001"),
        renew_time + 1_567
    )
    .is_err());
    renewal.now_ms = renew_time + 1_568;
    if let QuestPeerOwnerRequest::Rendezvous(request) = &mut renewal.request {
        request.expected_authority_revision = rev(3);
    }
    steps.push(renewal);
    let mut replay_nonces = nonces;
    replay_nonces.push("cd".repeat(32));
    bounds.push(false);
    let replay = review_peer_owner_journal_with_fact_nonces(
        &pair(),
        &policy,
        &steps,
        &replay_nonces,
        &bounds,
        &id("session.peer.pair-fixture-001"),
        renew_time + 1_568,
    )
    .unwrap();
    assert_eq!(replay.decisions.last().unwrap()["accepted"], false);
    assert_eq!(
        replay.decisions.last().unwrap()["rejection_reason"],
        "replay"
    );
}
#[test]
fn unsigned_or_altered_domain_bytes_never_accept_rendezvous() {
    for alter in 0..3 {
        let (policy, mut steps) = setup();
        steps.pop();
        if let QuestPeerOwnerRequest::Rendezvous(request) = &mut steps[4].request {
            match alter {
                0 => request.first.signature_hex = "00".repeat(64),
                1 => request.first.coordinator_epoch += 1,
                _ => request.first.evidence_id = id("evidence.altered"),
            }
        }
        let trace = review(&policy, &steps).unwrap();
        assert!(trace.rendezvous.accepted_receipts.is_empty());
        assert_eq!(trace.decisions[4]["accepted"], false);
    }
}
#[test]
fn changed_source_closure_session_and_pair_fail_before_authorization() {
    let (policy, steps) = setup();
    assert!(review_peer_owner_journal(
        &pair(),
        &policy,
        &steps,
        &"cd".repeat(32),
        &id("session.peer.pair-fixture-001"),
        NOW
    )
    .is_err());
    assert!(review_peer_owner_journal(
        &pair(),
        &policy,
        &steps,
        &"ab".repeat(32),
        &id("session.other"),
        NOW
    )
    .is_err());
    let mut changed = pair();
    changed.role_swap_completed = false;
    assert!(review_peer_owner_journal(
        &changed,
        &policy,
        &steps,
        &"ab".repeat(32),
        &id("session.peer.pair-fixture-001"),
        NOW
    )
    .is_err());
}
#[test]
fn retained_journal_rejects_actual_rendezvous_and_session_replay() {
    let (policy, mut steps) = setup();
    let mut replay = steps[4].clone();
    if let QuestPeerOwnerRequest::Rendezvous(request) = &mut replay.request {
        request.expected_authority_revision = rev(2);
    }
    steps.push(replay);
    let trace = review(&policy, &steps).unwrap();
    assert_eq!(trace.rendezvous.accepted_receipts.len(), 1);
    assert_eq!(trace.decisions[6]["accepted"], false);
    let mut replay = steps[5].clone();
    if let QuestPeerOwnerRequest::Session(config) = &mut replay.request {
        config.expected_authority_revision = rev(2);
    }
    steps.push(replay);
    let trace = review(&policy, &steps).unwrap();
    assert_eq!(trace.sessions.sessions.len(), 1);
    assert_eq!(
        trace.decisions[7][0]["rejection_reason"],
        "replayed_proposal"
    );
}
#[test]
fn real_key_revocation_invalidates_old_receipt_without_rewriting_history() {
    let (policy, mut steps) = setup();
    steps.pop();
    steps.push(QuestPeerOwnerStep {
        now_ms: NOW,
        request: QuestPeerOwnerRequest::Enrollment(ManifoldPeerEnrollmentRequest {
            schema_id: schema(PEER_ENROLLMENT_REQUEST_SCHEMA),
            request_id: id("request.revoke.alpha"),
            expected_authority_revision: rev(3),
            operator_id: id("operator.test"),
            issued_at_ms: NOW,
            action: ManifoldPeerEnrollmentAction::Revoke {
                key_id: id("key.alpha"),
                reason_id: id("reason.test"),
            },
        }),
    });
    steps.push(setup().1.pop().unwrap());
    let trace = review(&policy, &steps).unwrap();
    assert_eq!(trace.decisions[5]["applied"], true);
    assert!(trace.sessions.sessions.is_empty());
    assert_eq!(trace.rendezvous.accepted_receipts.len(), 1);
    assert_eq!(
        trace.decisions[6][1]["topology_authorization"]["authorized"],
        false
    );
}
#[test]
fn untrusted_peer_and_operator_cannot_manufacture_accepted_state() {
    let (mut policy, steps) = setup();
    policy.trusted_key_fingerprints.clear();
    assert!(review(&policy, &steps)
        .unwrap_err()
        .contains("no accepted peer proposal"));
    let (mut policy, mut steps) = setup();
    policy.trusted_operator_ids.clear();
    steps.truncate(4);
    let trace = review(&policy, &steps).unwrap();
    assert!(trace.enrollment.credentials.is_empty());
    assert_eq!(trace.decisions[2]["applied"], false);
}
#[test]
fn stale_final_time_and_session_revocation_are_actual_owner_outcomes() {
    let (policy, mut steps) = setup();
    assert!(review_peer_owner_journal(
        &pair(),
        &policy,
        &steps,
        &"ab".repeat(32),
        &id("session.peer.pair-fixture-001"),
        NOW + 1
    )
    .is_err());
    steps.push(QuestPeerOwnerStep {
        now_ms: NOW,
        request: QuestPeerOwnerRequest::Revoke(ManifoldPeerSessionRevocation {
            schema_id: schema(PEER_SESSION_REVOCATION_SCHEMA),
            revocation_id: id("revoke.session.1"),
            session_id: id("session.peer.pair-fixture-001"),
            expected_authority_revision: rev(2),
        }),
    });
    let trace = review(&policy, &steps).unwrap();
    assert!(trace.sessions.sessions[0].revoked);
    assert_eq!(trace.decisions[6]["authorized"], false);
}

#[test]
fn actual_rotation_invalidates_prior_signed_authority() {
    let (mut policy, mut steps) = setup();
    let session = steps.pop().unwrap();
    let replacement = SigningKey::from_bytes(&[13; 32]).verifying_key().to_bytes();
    let fingerprint = id(&format!("sha256.{:x}", Sha256::digest(replacement)));
    policy.trusted_key_fingerprints.push(fingerprint.clone());
    let mut peer = steps[0].clone();
    if let QuestPeerOwnerRequest::Peer(request) = &mut peer.request {
        request.proposal_id = id("proposal.peer.alpha.rotate");
        request.expected_authority_revision = rev(3);
        request.identity.key_fingerprint = fingerprint;
        request.status.status_revision = rev(2);
    }
    steps.push(peer);
    let mut credential = match &steps[2].request {
        QuestPeerOwnerRequest::Enrollment(request) => match &request.action {
            ManifoldPeerEnrollmentAction::Enroll { credential } => credential.clone(),
            _ => unreachable!(),
        },
        _ => unreachable!(),
    };
    credential.credential_id = id("credential.alpha.2");
    credential.key_id = id("key.alpha.2");
    credential.key_generation = 2;
    credential.public_key_hex = hex(&replacement);
    credential.public_key_sha256 = format!("sha256:{:x}", Sha256::digest(replacement));
    steps.push(QuestPeerOwnerStep {
        now_ms: NOW,
        request: QuestPeerOwnerRequest::Enrollment(ManifoldPeerEnrollmentRequest {
            schema_id: schema(PEER_ENROLLMENT_REQUEST_SCHEMA),
            request_id: id("request.rotate.alpha"),
            expected_authority_revision: rev(3),
            operator_id: id("operator.test"),
            issued_at_ms: NOW,
            action: ManifoldPeerEnrollmentAction::Rotate {
                prior_key_id: id("key.alpha"),
                credential,
            },
        }),
    });
    steps.push(session);
    let trace = review(&policy, &steps).unwrap();
    assert_eq!(trace.decisions[6]["applied"], true);
    assert_eq!(
        trace.enrollment.credentials[0].status,
        ManifoldPeerCredentialStatus::Rotated
    );
    assert!(trace.sessions.sessions.is_empty());
    assert_eq!(
        trace.decisions[7][1]["topology_authorization"]["authorized"],
        false
    );
}

#[test]
fn closed_enrollment_wire_matches_owner_serialization_and_rejects_ambiguity() {
    let (_, steps) = setup();
    let step = &steps[2];
    let bytes = serde_json::to_vec(step).unwrap();
    let parsed: QuestPeerOwnerStep = parse_peer_owner_json(&bytes).unwrap();
    assert_eq!(
        serde_json::to_value(parsed).unwrap(),
        serde_json::to_value(step).unwrap()
    );
    let reference = match &step.request {
        QuestPeerOwnerRequest::Enrollment(request) => serde_json::to_value(request).unwrap(),
        _ => unreachable!(),
    };
    assert_eq!(
        serde_json::to_value(&step.request).unwrap()["request"],
        reference
    );
    for (key, value) in [
        ("extra", serde_json::json!(true)),
        ("prior_key_id", serde_json::json!("key.unexpected")),
        ("action", serde_json::json!("unknown")),
    ] {
        let mut malformed = serde_json::to_value(step).unwrap();
        malformed["request"]["request"][key] = value;
        assert!(parse_peer_owner_json::<QuestPeerOwnerStep>(
            &serde_json::to_vec(&malformed).unwrap()
        )
        .is_err());
    }
    for bytes in [
        br#"{"outer":{"action":"enroll","action":"revoke"}}"#.as_slice(),
        br#"{"credential":{"peer_id":"peer.alpha","peer_id":"peer.beta"}}"#.as_slice(),
    ] {
        assert!(parse_peer_owner_json::<serde_json::Value>(bytes)
            .unwrap_err()
            .contains("duplicate JSON field"));
    }
    assert!(parse_peer_owner_json::<QuestPeerOwnerStep>(b"{\"request\":").is_err());
}

fn base64(bytes: &[u8]) -> String {
    let alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut result = String::new();
    for part in bytes.chunks(3) {
        let a = part[0];
        let b = *part.get(1).unwrap_or(&0);
        let c = *part.get(2).unwrap_or(&0);
        result.push(char::from(alphabet[usize::from(a >> 2)]));
        result.push(char::from(alphabet[usize::from(((a & 3) << 4) | (b >> 4))]));
        result.push(if part.len() > 1 {
            char::from(alphabet[usize::from(((b & 15) << 2) | (c >> 6))])
        } else {
            '='
        });
        result.push(if part.len() > 2 {
            char::from(alphabet[usize::from(c & 63)])
        } else {
            '='
        });
    }
    result
}
fn write_json(path: &std::path::Path, value: &impl serde::Serialize) -> String {
    let bytes = serde_json::to_vec_pretty(value).unwrap();
    std::fs::write(path, &bytes).unwrap();
    format!("{:x}", Sha256::digest(bytes))
}
fn cli(binary: &str, args: &[String]) -> std::process::Output {
    std::process::Command::new(binary)
        .args(args)
        .output()
        .unwrap()
}

#[test]
fn actual_cli_and_binary_helper_join_raw_inputs_and_reject_pin_changes() {
    run_actual_cli_case(false);
}

#[test]
fn actual_cli_joins_ip_carriers_to_canonical_hardware_and_rejects_wrong_inventory() {
    run_actual_cli_case(true);
}

fn run_actual_cli_case(use_ip: bool) {
    // Host-only fixture identity tags model the receipt shape, never on-device evidence.
    let root = std::env::temp_dir().join(format!(
        "quest-owner-chain-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir(&root).unwrap();
    let path = |name: &str| root.join(name).to_str().unwrap().to_owned();
    let (policy, mut steps) = setup();
    let policy_hash = write_json(&root.join("policy.json"), &policy);
    let endpoints = if use_ip {
        ["192.0.2.73:5555", "192.0.2.78:5555"]
    } else {
        ["quest-alpha", "quest-beta"]
    };
    let serials = if use_ip {
        ["TESTQUEST0000001A", "TESTQUEST0000002B"]
    } else {
        ["quest-alpha", "quest-beta"]
    };
    fn replace_serials(value: &mut serde_json::Value, endpoints: &[&str; 2]) {
        match value {
            serde_json::Value::String(text) => match text.as_str() {
                "quest-alpha" => *text = endpoints[0].into(),
                "quest-beta" => *text = endpoints[1].into(),
                _ => {}
            },
            serde_json::Value::Array(values) => {
                for value in values {
                    replace_serials(value, endpoints);
                }
            }
            serde_json::Value::Object(values) => {
                for value in values.values_mut() {
                    replace_serials(value, endpoints);
                }
            }
            _ => {}
        }
    }
    let mut pair_value = serde_json::to_value(pair()).unwrap();
    replace_serials(&mut pair_value, &endpoints);
    let pair_hash = write_json(&root.join("pair.json"), &pair_value);
    let source_hash = write_json(
        &root.join("source.json"),
        &serde_json::json!({ "fixture_source": "host-only-exact-byte-fixture" }),
    );
    let mut identities = vec![];
    for (index, seed) in [7_u8, 11].into_iter().enumerate() {
        let public = SigningKey::from_bytes(&[seed; 32])
            .verifying_key()
            .to_bytes();
        std::fs::write(path(&format!("key{index}")), [seed; 32]).unwrap();
        let serial = serials[index];
        let peer = if index == 0 {
            "peer.alpha"
        } else {
            "peer.beta"
        };
        let key = if index == 0 { "key.alpha" } else { "key.beta" };
        let receipt_hash = write_json(
            &root.join(format!("identity{index}.json")),
            &serde_json::json!({ "schema": "rusty.quest.peer_authority_identity.v1", "generation": "on-device", "run_id": "host-fixture", "serial": serial, "peer_id": peer, "key_id": key, "algorithm": "Ed25519", "public_key_ed25519_base64": base64(&public), "public_key_sha256": format!("sha256:{:x}", Sha256::digest(public)), "private_key_exported_to_host": false }),
        );
        let inventory_hash = write_json(
            &root.join(format!("inventory{index}.json")),
            &serde_json::json!([{ "arguments": ["-s", endpoints[index], "shell", "getprop", "ro.serialno"], "exit_code": 0, "stdout": format!("{serial}\r\n"), "stderr": "" }]),
        );
        identities.push(serde_json::json!({ "serial": serial, "endpoint": endpoints[index], "peer_id": peer, "key_id": key, "receipt": { "path": path(&format!("identity{index}.json")), "sha256": receipt_hash }, "inventory": { "path": path(&format!("inventory{index}.json")), "sha256": inventory_hash } }));
    }
    let facts_hash = write_json(
        &root.join("facts.json"),
        &serde_json::json!({ "session_id": "session.peer.pair-fixture-001", "pair": { "path": path("pair.json"), "sha256": pair_hash }, "identities": identities, "source_facts": [{ "path": path("source.json"), "sha256": source_hash }] }),
    );
    if let QuestPeerOwnerRequest::Rendezvous(request) = &mut steps[4].request {
        for (index, evidence) in [&mut request.first, &mut request.second]
            .into_iter()
            .enumerate()
        {
            evidence.nonce_hex = facts_hash.clone();
            evidence.signature_hex = "00".repeat(64);
            let evidence_hash = write_json(&root.join(format!("evidence{index}.json")), evidence);
            let prepare = cli(
                env!("CARGO_BIN_EXE_produce_peer_authority"),
                &[
                    "prepare-signing-bytes".into(),
                    path(&format!("evidence{index}.json")),
                    evidence_hash,
                    path(&format!("owner{index}.bin")),
                ],
            );
            assert!(
                prepare.status.success(),
                "{}",
                String::from_utf8_lossy(&prepare.stderr)
            );
            let bytes_hash = String::from_utf8(prepare.stdout).unwrap().trim().to_owned();
            let sign_args = vec![
                "sign-owner-bytes".into(),
                "session.peer.pair-fixture-001".into(),
                path(&format!("identity{index}.json")),
                path(&format!("key{index}")),
                path(&format!("owner{index}.bin")),
                bytes_hash,
                path(&format!("signature{index}.json")),
            ];
            let sign = cli(
                env!("CARGO_BIN_EXE_peer_authority_device_helper"),
                &sign_args,
            );
            assert!(
                sign.status.success(),
                "{}",
                String::from_utf8_lossy(&sign.stderr)
            );
            let signature: serde_json::Value =
                serde_json::from_slice(&std::fs::read(&sign_args[6]).unwrap()).unwrap();
            evidence.signature_hex = signature["signature_hex"].as_str().unwrap().to_owned();
            let mut bad = sign_args.clone();
            bad[5] = "00".repeat(32);
            bad[6] = path("bad-signature.json");
            assert!(
                !cli(env!("CARGO_BIN_EXE_peer_authority_device_helper"), &bad)
                    .status
                    .success()
            );
        }
    }
    let journal_hash = write_json(&root.join("journal.json"), &steps);
    let args = vec![
        "review".into(),
        path("policy.json"),
        policy_hash.clone(),
        path("journal.json"),
        journal_hash,
        path("facts.json"),
        facts_hash.clone(),
        NOW.to_string(),
        path("review.json"),
    ];
    let result = cli(env!("CARGO_BIN_EXE_produce_peer_authority"), &args);
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    let trace: serde_json::Value =
        serde_json::from_slice(&std::fs::read(path("review.json")).unwrap()).unwrap();
    assert_eq!(
        trace["trace"]["decisions"][5][1]["topology_authorization"]["authorized"],
        true
    );

    if use_ip {
        let old_facts: serde_json::Value =
            serde_json::from_slice(&std::fs::read(path("facts.json")).unwrap()).unwrap();
        let mut current_facts = old_facts.clone();
        let challenge_sha = write_json(
            &root.join("renewal-challenge.json"),
            &serde_json::json!({"purpose":"host-only renewal challenge", "generation":2}),
        );
        current_facts["source_facts"].as_array_mut().unwrap().push(
            serde_json::json!({"path":path("renewal-challenge.json"),"sha256":challenge_sha}),
        );
        let current_sha = write_json(&root.join("current-facts.json"), &current_facts);
        let lineage_value = serde_json::json!({"prior_facts":[{"path":path("facts.json"),"sha256":facts_hash}],"current_facts":{"path":path("current-facts.json"),"sha256":current_sha}});
        let lineage_sha = write_json(&root.join("lineage.json"), &lineage_value);
        let mut renewal = steps[4].clone();
        renewal.now_ms = NOW + 2_000;
        if let QuestPeerOwnerRequest::Rendezvous(request) = &mut renewal.request {
            request.request_id = id("request.rendezvous.0renew");
            request.expected_authority_revision = rev(2);
            for (index, evidence) in [&mut request.first, &mut request.second]
                .into_iter()
                .enumerate()
            {
                evidence.evidence_id = id(&format!("evidence.cli.renew.{index}"));
                evidence.nonce_hex = current_sha.clone();
                evidence.issued_at_ms = NOW + 2_000;
                evidence.expires_at_ms = NOW + 62_000;
                let key = SigningKey::from_bytes(&[if index == 0 { 7 } else { 11 }; 32]);
                evidence.signature_hex =
                    hex(&key.sign(&rendezvous_signing_bytes(evidence)).to_bytes());
            }
        }
        let mut lineage_steps:Vec<_>=steps.iter().map(|step|serde_json::json!({"facts_sha256":facts_hash,"bound_session_ttl":false,"step":step})).collect();
        lineage_steps.push(serde_json::json!({"facts_sha256":current_sha,"bound_session_ttl":false,"step":renewal}));
        let lineage_journal_sha = write_json(&root.join("lineage-journal.json"), &lineage_steps);
        let lineage_args = vec![
            "review-lineage".into(),
            path("policy.json"),
            policy_hash.clone(),
            path("lineage-journal.json"),
            lineage_journal_sha,
            path("lineage.json"),
            lineage_sha,
            (NOW + 2_000).to_string(),
            path("lineage-review.json"),
        ];
        let result = cli(env!("CARGO_BIN_EXE_produce_peer_authority"), &lineage_args);
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        let renewed: serde_json::Value =
            serde_json::from_slice(&std::fs::read(path("lineage-review.json")).unwrap()).unwrap();
        assert_eq!(
            renewed["trace"]["rendezvous"]["accepted_receipts"]
                .as_array()
                .unwrap()
                .len(),
            2
        );
        assert_eq!(
            renewed["trace"]["decisions"][5],
            trace["trace"]["decisions"][5]
        );
        let mut delayed = steps.clone();
        delayed[5].now_ms = NOW + 1_567;
        if let QuestPeerOwnerRequest::Session(config) = &mut delayed[5].request {
            config.now_ms = NOW + 1_567;
        }
        let mut repair = delayed[5].clone();
        repair.now_ms = NOW + 2_000;
        if let QuestPeerOwnerRequest::Session(config) = &mut repair.request {
            config.now_ms = NOW + 2_000;
        }
        delayed.push(repair);
        let bounded_rows:Vec<_>=delayed.iter().enumerate().map(|(index,step)|serde_json::json!({"facts_sha256":facts_hash,"bound_session_ttl":index==6,"step":step})).collect();
        let mut bounded_args = lineage_args.clone();
        bounded_args[3] = path("bounded-journal.json");
        bounded_args[4] = write_json(std::path::Path::new(&bounded_args[3]), &bounded_rows);
        bounded_args[5] = path("bounded-lineage.json");
        bounded_args[6] = write_json(
            std::path::Path::new(&bounded_args[5]),
            &serde_json::json!({"prior_facts":[],"current_facts":{"path":path("facts.json"),"sha256":facts_hash}}),
        );
        bounded_args[8] = path("bounded-review.json");
        let bounded = cli(env!("CARGO_BIN_EXE_produce_peer_authority"), &bounded_args);
        assert!(
            bounded.status.success(),
            "{}",
            String::from_utf8_lossy(&bounded.stderr)
        );
        let bounded: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&bounded_args[8]).unwrap()).unwrap();
        assert_eq!(
            bounded["trace"]["decisions"][5][0]["rejection_reason"],
            "signed_rendezvous_mismatch"
        );
        assert_eq!(
            bounded["trace"]["decisions"][6][1]["topology_authorization"]["authorized"],
            true
        );
        assert_eq!(
            bounded["trace"]["sessions"]["sessions"][0]["proposal"]["expires_at_ms"],
            NOW + 60_000
        );
        for damage in [
            "missing-frame",
            "backward",
            "wrong-current",
            "bound-nonsession",
            "duplicate-frame",
            "unknown-field",
            "changed-public-identity",
        ] {
            let mut damaged_steps = lineage_steps.clone();
            let mut damaged_lineage = lineage_value.clone();
            match damage {
                "missing-frame" => {
                    damaged_steps[6]["facts_sha256"] = serde_json::json!("00".repeat(32))
                }
                "backward" => {
                    damaged_steps[0]["facts_sha256"] = serde_json::json!(current_sha);
                }
                "wrong-current" => damaged_steps[6]["facts_sha256"] = serde_json::json!(facts_hash),
                "bound-nonsession" => {
                    damaged_steps[0]["bound_session_ttl"] = serde_json::json!(true)
                }
                "duplicate-frame" => {
                    damaged_lineage["prior_facts"][0] = damaged_lineage["current_facts"].clone()
                }
                "unknown-field" => damaged_steps[0]["accepted"] = serde_json::json!(true),
                _ => {
                    let mut altered = current_facts.clone();
                    altered["identities"][0]["serial"] = serde_json::json!("TESTQUESTCHANGED");
                    let altered_sha =
                        write_json(&root.join("damaged-current-facts.json"), &altered);
                    damaged_lineage["current_facts"] = serde_json::json!({"path":path("damaged-current-facts.json"),"sha256":altered_sha});
                    damaged_steps[6]["facts_sha256"] = serde_json::json!(altered_sha);
                }
            }
            let mut bad = lineage_args.clone();
            bad[3] = path(&format!("{damage}-journal.json"));
            bad[4] = write_json(std::path::Path::new(&bad[3]), &damaged_steps);
            bad[5] = path(&format!("{damage}-lineage.json"));
            bad[6] = write_json(std::path::Path::new(&bad[5]), &damaged_lineage);
            bad[8] = path(&format!("{damage}-review.json"));
            assert!(!cli(env!("CARGO_BIN_EXE_produce_peer_authority"), &bad)
                .status
                .success());
            assert!(!std::path::Path::new(&bad[8]).exists());
        }
    }
    // Existing outputs cannot be overwritten, even for an identical reviewed journal.
    assert!(!cli(env!("CARGO_BIN_EXE_produce_peer_authority"), &args)
        .status
        .success());
    let original_inventory: serde_json::Value =
        serde_json::from_slice(&std::fs::read(path("inventory0.json")).unwrap()).unwrap();
    let original_facts: serde_json::Value =
        serde_json::from_slice(&std::fs::read(path("facts.json")).unwrap()).unwrap();
    for alteration in [
        "swapped-endpoint",
        "wrong-hardware",
        "native-failure",
        "stderr-failure",
        "duplicate-call",
    ] {
        let mut inventory = original_inventory.clone();
        match alteration {
            "swapped-endpoint" => inventory[0]["arguments"][1] = serde_json::json!(endpoints[1]),
            "wrong-hardware" => {
                inventory[0]["stdout"] = serde_json::json!(format!("{}\r\n", serials[1]))
            }
            "native-failure" => inventory[0]["exit_code"] = serde_json::json!(1),
            "stderr-failure" => inventory[0]["stderr"] = serde_json::json!("actual native failure"),
            _ => inventory
                .as_array_mut()
                .unwrap()
                .push(original_inventory[0].clone()),
        }
        let inventory_hash = write_json(&root.join("inventory0.json"), &inventory);
        let mut facts = original_facts.clone();
        facts["identities"][0]["inventory"]["sha256"] = serde_json::json!(inventory_hash);
        let mut denied_args = args.clone();
        denied_args[6] = write_json(&root.join("facts.json"), &facts);
        denied_args[8] = path(&format!("denied-{alteration}.json"));
        let denied = cli(env!("CARGO_BIN_EXE_produce_peer_authority"), &denied_args);
        assert!(!denied.status.success());
        assert!(String::from_utf8_lossy(&denied.stderr)
            .contains("actual endpoint/canonical serial inventory join failed"));
        assert!(!std::path::Path::new(&denied_args[8]).exists());
    }
    write_json(&root.join("inventory0.json"), &original_inventory);
    write_json(&root.join("facts.json"), &original_facts);
    std::fs::write(path("source.json"), b"altered source fact").unwrap();
    let mut changed = args.clone();
    changed[8] = path("changed-source-review.json");
    let denied = cli(env!("CARGO_BIN_EXE_produce_peer_authority"), &changed);
    assert!(!denied.status.success());
    assert!(String::from_utf8_lossy(&denied.stderr).contains("raw file pin mismatch"));
    assert!(!root.join("changed-source-review.json").exists());
    if use_ip {
        let damaged = cli(
            env!("CARGO_BIN_EXE_produce_peer_authority"),
            &[
                "review-lineage".into(),
                path("policy.json"),
                policy_hash,
                path("lineage-journal.json"),
                format!(
                    "{:x}",
                    Sha256::digest(std::fs::read(path("lineage-journal.json")).unwrap())
                ),
                path("lineage.json"),
                format!(
                    "{:x}",
                    Sha256::digest(std::fs::read(path("lineage.json")).unwrap())
                ),
                (NOW + 2_000).to_string(),
                path("changed-source-lineage-review.json"),
            ],
        );
        assert!(!damaged.status.success());
        assert!(String::from_utf8_lossy(&damaged.stderr).contains("raw file pin mismatch"));
        assert!(!root.join("changed-source-lineage-review.json").exists());
    }
    // Task-owned fixture only; no public source or device evidence is removed.
    std::fs::remove_dir_all(root).unwrap();
}
