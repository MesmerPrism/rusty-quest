// Source-owned actual Broker/M writer controls, authored and unrun.
// Native ARM, physical eligibility and Android callbacks are explicitly modeled
// witness DATA here; these controls do not qualify a headset or JNI/renderer path.
mod source_bound_roots_controls {
    use super::*;
    use rusty_manifold_peer::*;
    use rusty_manifold_peer_runtime_host::{
        ManifoldFreshPairSetupReceipt, ManifoldSourceBoundRootNativeWitness,
    };
    use std::sync::atomic::{AtomicBool, Ordering};
    fn keys() -> [SigningKey; 2] {
        [
            SigningKey::from_bytes(&[41; 32]),
            SigningKey::from_bytes(&[42; 32]),
        ]
    }
    pub(super) fn root_config(
        local: &str,
        grant_expiry: u64,
        lease_expiry: u64,
    ) -> QuestBrokerRuntimeConfig {
        let mut c = config(
            QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
            vec![ManifoldBrokerFeature::MediaSession],
            "command.media.session.start",
            true,
        );
        let keys = keys();
        let selection = ManifoldFreshPairSetupEnrollmentSelection {
            schema_id: schema("rusty.manifold.peer.fresh_setup_enrollment_selection.v1"),
            operator_id: id("operator.quest.peer-enrollment"),
            keys: [
                ("peer.quest-a", "key.peer.quest-a.1"),
                ("peer.quest-b", "key.peer.quest-b.1"),
            ]
            .into_iter()
            .zip(keys.iter())
            .map(|((peer, key_id), key)| {
                let raw = key.verifying_key().to_bytes();
                let sha = sha256_hex(&raw);
                ManifoldFreshPairSetupEnrollmentKey {
                    schema_id: schema("rusty.manifold.peer.fresh_setup_enrollment_key.v1"),
                    peer_id: id(peer),
                    credential_id: id(&format!("credential.{peer}.1")),
                    trust_domain: id("trust.morphospace.peer"),
                    key_id: id(key_id),
                    key_generation: 1,
                    algorithm: ManifoldPeerCredentialAlgorithm::Ed25519,
                    public_key_hex: raw.iter().map(|b| format!("{b:02x}")).collect(),
                    public_key_sha256: format!("sha256:{sha}"),
                    key_fingerprint: id(&format!("fingerprint.{sha}")),
                }
            })
            .collect(),
        };
        let mut fingerprints: Vec<_> = selection
            .keys
            .iter()
            .map(|k| k.key_fingerprint.clone())
            .collect();
        fingerprints.sort();
        c=c.with_embedded_duplex(QuestEmbeddedDuplexAuthorityConfig{
        fresh_setup_local_peer_id:Some(id(local)),fresh_setup_enrollment_selection:Some(selection),
        ongoing_media_policy:Some(rusty_manifold_peer_runtime_host::ManifoldOngoingMediaPolicy::active_checkpoint_v2()),
        schema_id:crate::QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA.into(),runtime_host_id:id(&format!("host.{local}.runtime")),
        trusted_operator_ids:vec![id("operator.quest.peer-enrollment")],trusted_key_fingerprints:fingerprints,
        trusted_adapter_ids:vec![id("adapter.quest.embedded-duplex")],trusted_media_revoker_ids:vec![id("operator.quest.media-revoker")],
    }).unwrap();
        for g in &mut c.admission.snapshot.grants {
            g.expires_at_ms = grant_expiry;
        }
        for l in &mut c.initial_leases {
            l.expires_at_ms = lease_expiry;
        }
        c
    }
    fn construct(local: &str, g: u64, l: u64) -> QuestBrokerRuntimeProvider {
        initialize_fixture(&root_config(local, g, l), 1_000).unwrap()
    }
    fn enroll(authority: &QuestEmbeddedDuplexAuthority) {
        for (i, (peer, key_id)) in [
            ("peer.quest-a", "key.peer.quest-a.1"),
            ("peer.quest-b", "key.peer.quest-b.1"),
        ]
        .into_iter()
        .enumerate()
        {
            let key = &keys()[i];
            let fp = format!(
                "fingerprint.{}",
                sha256_hex(&key.verifying_key().to_bytes())
            );
            bootstrap_continuous_peer(authority, peer, key_id, &fp, key, (i + 1) as u64);
        }
    }
    fn new_pair(
        a: &QuestEmbeddedDuplexAuthority,
        b: &QuestEmbeddedDuplexAuthority,
        now: u64,
    ) -> (ManifoldFreshPairSetupReceipt, DottedId) {
        let peers = vec![id("peer.quest-a"), id("peer.quest-b")];
        let sha = format!("sha256:{}", "a1".repeat(32));
        let mut context = ManifoldFreshPairSetupContext {
            schema_id: schema("rusty.manifold.peer.fresh_pair_setup_context.v3"),
            request_id: id("ceremony.duplex.placeholder"),
            native_setup_request_id: id(&format!("ceremony.duplex.root-control-{now}")),
            native_phase_id: id(&format!("ceremony.duplex.root-phase-{now}")),
            policy: a.selected_fresh_pair_setup_policy().unwrap().unwrap(),
            peer_ids: peers.clone(),
            views: vec![
                a.fresh_pair_setup_view(&peers[0], &peers, &sha, &sha, now)
                    .unwrap(),
                b.fresh_pair_setup_view(&peers[1], &peers, &sha, &sha, now)
                    .unwrap(),
            ],
            issued_at_ms: now,
        };
        context.request_id = fresh_pair_setup_transaction_id(&context).unwrap();
        let bytes = fresh_pair_setup_signing_bytes(&context).unwrap();
        let request = ManifoldFreshPairSetupRequest {
            schema_id: schema("rusty.manifold.peer.fresh_pair_setup_request.v3"),
            context,
            signatures: [
                ("peer.quest-a", "key.peer.quest-a.1"),
                ("peer.quest-b", "key.peer.quest-b.1"),
            ]
            .into_iter()
            .zip(keys().iter())
            .map(|((p, k), key)| ManifoldFreshPairSetupSignature {
                schema_id: schema("rusty.manifold.peer.fresh_pair_setup_signature.v3"),
                signer_peer_id: id(p),
                signer_key_id: id(k),
                signature_hex: key
                    .sign(&bytes)
                    .to_bytes()
                    .iter()
                    .map(|b| format!("{b:02x}"))
                    .collect(),
            })
            .collect(),
        };
        let receipt = a.apply_fresh_pair_setup(&request, now).unwrap();
        b.apply_fresh_pair_setup(&request, now).unwrap();
        let [ka, kb] = keys();
        let common = renewal_reciprocal_request(
            a,
            &format!("request.root-control.common-{now}"),
            &ka,
            &kb,
            now + 1,
            now + 60_000,
            now.checked_add(91)
                .expect("fixture ceremony nonce overflow"),
        );
        let reciprocal = a.apply_common_lan_reciprocal(&common, now + 1).unwrap();
        assert!(reciprocal.accepted);
        let snapshot: serde_json::Value =
            serde_json::from_str(&a.snapshot_json().unwrap()).unwrap();
        let session = id(&format!("session.root-control.current-{now}"));
        let proposal:ManifoldCommonLanPeerSessionProposal=serde_json::from_value(serde_json::json!({"$schema":COMMON_LAN_PEER_SESSION_PROPOSAL_SCHEMA,
        "proposal_id":a.current_runtime_history_request_id(&id(&format!("proposal.root-control.current-{now}"))).unwrap(),"session_id":session,
        "expected_authority_revision":snapshot["peer_sessions"]["authority_revision"],"subject_peer_id":"peer.quest-a","candidate_peer_id":"peer.quest-b",
        "initiator_peer_id":"peer.quest-a","responder_peer_id":"peer.quest-b","requested_capability_ids":["capability.rendezvous.ble","capability.route.rust-direct-p2p","capability.topology.wifi-direct"],
        "transport":common_lan_transport('5'),"expires_at_ms":now+50_000})).unwrap();
        let (decision, topology) = a
            .apply_common_lan_session(&proposal, &reciprocal, now + 2)
            .unwrap();
        assert!(decision.applied && topology.authorized);
        (receipt, session)
    }
    fn witness(stopped: bool) -> ManifoldSourceBoundRootNativeWitness {
        let hash = Some(format!("sha256:{}", "c1".repeat(32)));
        ManifoldSourceBoundRootNativeWitness {
            request_id: id("request.root.actual-original"),
            native_setup_request_id: id("ceremony.duplex.root-control"),
            native_phase_id: id("ceremony.duplex.root-phase"),
            peer_disposition: if stopped {
                rusty_manifold_model::ManifoldSourceBoundPeerDisposition::FullyStopped
            } else {
                rusty_manifold_model::ManifoldSourceBoundPeerDisposition::NeverStarted
            },
            lifetime_ordinal: if stopped { 1 } else { 0 },
            prior_start_sha256: if stopped { hash.clone() } else { None },
            terminal_stop_sha256: if stopped { hash.clone() } else { None },
            native_lifecycle_sha256: hash.clone().unwrap(),
            native_actor_sha256: hash.clone().unwrap(),
            runtime_configuration_sha256: hash.clone().unwrap(),
            arm_binding_sha256: hash.unwrap(),
        }
    }
    fn recover(
        p: &QuestBrokerRuntimeProvider,
        a: &QuestEmbeddedDuplexAuthority,
        receipt: &ManifoldFreshPairSetupReceipt,
        session: &DottedId,
        now: u64,
        stop: &AtomicBool,
        stopped: bool,
    ) -> Result<
        rusty_manifold_peer_runtime_host::ManifoldPeerSourceBoundRootReestablishmentReceipt,
        String,
    > {
        let clock = p
            .read_concurrent_peer_clock(now as i64, 1_000_000_000 + (now - 1_000) * 1_000_000, 0)
            .unwrap();
        let mut native = witness(stopped);
        native.native_setup_request_id = receipt.request.context.native_setup_request_id.clone();
        native.native_phase_id = receipt.request.context.native_phase_id.clone();
        native.request_id = id(&format!("request.root.{}", native.native_phase_id));
        a.reestablish_peer_roots(native, receipt, session, clock, now, stop)
    }
    #[test]
    fn delayed_pair_and_broker_roots_expired_status_reads_do_not_sweep_then_real_source_bound_recovery(
    ) {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        let before = a.peer_root_projection().unwrap();
        // Actual status/currentness observations after both original root and key
        // validity elapsed: no snapshot edits, no expiry sweep, no wall sleeping.
        assert!(
            !a.current_common_lan_session(&id("session.absent.old"), 400_000)
                .unwrap()
                .current
        );
        assert_eq!(a.peer_root_projection().unwrap(), before);
        let (fresh, session) = new_pair(&a, &b, 400_000);
        let receipt = recover(
            &p,
            &a,
            &fresh,
            &session,
            400_003,
            &AtomicBool::new(false),
            false,
        )
        .unwrap();
        assert!(
            receipt.broker.admission.is_some()
                && receipt.broker.control_lease_transition.is_some()
                && receipt.broker.runtime_adoption.as_ref().unwrap().applied
        );
        let after = a.peer_root_projection().unwrap();
        assert_eq!(after["grant"]["grant_id"], before["grant"]["grant_id"]);
        assert_eq!(
            after["control_lease"]["lease_id"],
            before["control_lease"]["lease_id"]
        );
        assert_eq!(before["grant"]["expires_at_ms"], 301_000);
        assert!(after["grant"]["expires_at_ms"].as_u64().unwrap() > 400_003);
        // Same actual owner evidence closes versioned source-bound lineage.
        let e = p.runtime.as_ref().unwrap().evidence().unwrap();
        assert_eq!(
            e.runtime.schema_id.as_str(),
            "rusty.manifold.broker.runtime_evidence.v8"
        );
    }
    #[test]
    fn each_still_current_component_is_preserved_exact_and_only_expired_component_changes() {
        for grant_expiry in [250_000, 301_000] {
            let lease_expiry = if grant_expiry == 250_000 {
                301_000
            } else {
                250_000
            };
            let p = construct("peer.quest-a", grant_expiry, lease_expiry);
            let q = construct("peer.quest-b", 301_000, 301_000);
            let a = p.embedded_duplex_authority().unwrap();
            let b = q.embedded_duplex_authority().unwrap();
            enroll(&a);
            enroll(&b);
            let before = a.peer_root_projection().unwrap();
            let (fresh, session) = new_pair(&a, &b, 275_000);
            let result = recover(
                &p,
                &a,
                &fresh,
                &session,
                275_003,
                &AtomicBool::new(false),
                true,
            )
            .unwrap();
            let after = a.peer_root_projection().unwrap();
            if grant_expiry > 275_003 {
                assert!(result.broker.admission.is_none());
                assert_eq!(after["grant"], before["grant"]);
            } else {
                assert!(result.broker.admission.is_some());
                assert!(result.broker.control_lease_transition.is_none());
                assert!(result.broker.runtime_adoption.is_none());
                assert_eq!(after["control_lease"], before["control_lease"]);
                assert_eq!(after["runtime_lease"], before["runtime_lease"]);
            }
        }
    }
    #[test]
    fn source_bound_root_actual_stop_or_expired_new_pair_refuses_without_mutating_roots() {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        let (fresh, session) = new_pair(&a, &b, 400_000);
        let before = a.peer_root_projection().unwrap();
        assert!(recover(
            &p,
            &a,
            &fresh,
            &session,
            400_003,
            &AtomicBool::new(true),
            false
        )
        .is_err());
        assert_eq!(a.peer_root_projection().unwrap(), before);
        assert!(recover(
            &p,
            &a,
            &fresh,
            &session,
            500_000,
            &AtomicBool::new(false),
            false
        )
        .is_err());
        assert_eq!(a.peer_root_projection().unwrap(), before);
    }
    #[test]
    fn entered_start_material_can_outlive_pair_and_root_and_cannot_be_recovered_as_never_started() {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        let (fresh, session) = new_pair(&a, &b, 400_000);
        recover(
            &p,
            &a,
            &fresh,
            &session,
            400_003,
            &AtomicBool::new(false),
            false,
        )
        .unwrap();
        // Deliberate producer expiry: this proves the actual current witness stops
        // being usable after an explicitly advanced authoritative clock. It does
        // not claim a full Native/Android held-preparation test has executed.
        let pair = a.current_common_lan_session(&session, 500_000).unwrap();
        assert!(!pair.current);
        let roots = a.peer_root_projection().unwrap();
        assert!(roots["grant"]["expires_at_ms"].as_u64().unwrap() <= 500_000);
        assert!(recover(
            &p,
            &a,
            &fresh,
            &session,
            500_000,
            &AtomicBool::new(false),
            false
        )
        .is_err());
    }
    #[test]
    fn repeated_completed_no_start_roots_seal_real_owner_history_and_keep_one_live_suffix() {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        static ORDINAL: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let directory = std::env::temp_dir().join(format!(
            "rq-root-custody-{}-{}",
            std::process::id(),
            ORDINAL.fetch_add(1, Ordering::SeqCst)
        ));
        std::fs::create_dir(&directory).unwrap();
        let mut last: Option<Vec<u8>> = None;
        for cycle in 0..4u64 {
            let now = 400_000 + cycle * 400_000;
            // This storage boundary is an explicitly MODELED Java custodian. It
            // exercises actual file fsync/readback + actual M/Broker checkpoint;
            // the real Original success/UNKNOWN parser is owned by Java controls.
            if let Some(body) = last.as_ref() {
                use std::io::Write;
                let path = directory.join(format!("prior-{cycle}.json"));
                let mut file = std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(&path)
                    .unwrap();
                file.write_all(body).unwrap();
                file.sync_all().unwrap();
                drop(file);
                assert_eq!(std::fs::read(&path).unwrap(), *body);
                let prior_roots = a.peer_root_projection().unwrap();
                assert!(a.checkpoint_dormant_peer_roots(now).unwrap().is_some());
                assert_eq!(
                    a.peer_root_projection().unwrap(),
                    prior_roots,
                    "checkpoint preserves actual expired roots byte-exact"
                );
                assert!(p
                    .runtime
                    .as_ref()
                    .unwrap()
                    .runtime
                    .read()
                    .unwrap()
                    .evidence()
                    .source_bound_root_reestablishments
                    .is_empty());
            }
            let (fresh, session) = new_pair(&a, &b, now);
            let result = recover(
                &p,
                &a,
                &fresh,
                &session,
                now + 3,
                &AtomicBool::new(false),
                false,
            )
            .unwrap();
            assert_eq!(
                p.runtime
                    .as_ref()
                    .unwrap()
                    .runtime
                    .read()
                    .unwrap()
                    .evidence()
                    .source_bound_root_reestablishments
                    .len(),
                1
            );
            assert_eq!(result.authority.sequence, cycle + 1);
            last = Some(serde_json::to_vec(&result).unwrap());
        }
    }

    #[test]
    fn actual_latest_root_seed_survives_checkpoint_and_later_fresh_pair_without_relabeling_old_time(
    ) {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        let (fresh, session) = new_pair(&a, &b, 400_000);
        let seed = recover(
            &p,
            &a,
            &fresh,
            &session,
            400_003,
            &AtomicBool::new(false),
            false,
        )
        .unwrap();
        let old_seed = seed.clone();
        let before = a
            .current_root_lineage_observation(&seed, &session, 400_004)
            .unwrap();
        assert!(a.checkpoint_dormant_peer_roots(400_005).unwrap().is_some());
        let after = a
            .current_root_lineage_observation(&seed, &session, 400_006)
            .unwrap();
        assert_eq!(before.root_receipt_sha256, after.root_receipt_sha256);
        assert_eq!(before.grant, after.grant);
        assert_eq!(before.control_lease, after.control_lease);
        assert_eq!(before.runtime_lease, after.runtime_lease);
        assert!(
            after
                .broker_checkpoint
                .as_ref()
                .unwrap()
                .checkpoint_sequence
                > before
                    .broker_checkpoint
                    .as_ref()
                    .unwrap()
                    .checkpoint_sequence
        );
        let (_fresh2, session2) = new_pair(&a, &b, 401_000);
        let current = a
            .current_root_lineage_observation(&seed, &session2, 401_003)
            .unwrap();
        assert_ne!(
            current.current_pair.session_id,
            seed.current_pair.session_id
        );
        assert_eq!(current.root_receipt_sha256, before.root_receipt_sha256);
        assert_eq!(
            seed, old_seed,
            "historical pair time/floor/receipt must never be refreshed"
        );
        assert!(
            a.current_root_lineage_observation(&seed, &session2, 500_000)
                .is_err(),
            "actual expired pair/roots cannot be called current"
        );
    }
    #[test]
    fn actual_root_lineage_refuses_changed_receipt_foreign_owner_and_changed_scope() {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        let (fresh, session) = new_pair(&a, &b, 400_000);
        let seed = recover(
            &p,
            &a,
            &fresh,
            &session,
            400_003,
            &AtomicBool::new(false),
            false,
        )
        .unwrap();
        let mut changed = seed.clone();
        changed.broker.request.grant.expires_at_ms += 1;
        assert!(a
            .current_root_lineage_observation(&changed, &session, 400_004)
            .is_err());
        assert!(b
            .current_root_lineage_observation(&seed, &session, 400_004)
            .is_err());
        // Changed seed scope is rejected by its actual private commit digest.
        let mut changed = seed.clone();
        changed.broker.request.lease.prior_lease.scope = id("lease.foreign.scope");
        assert!(a
            .current_root_lineage_observation(&changed, &session, 400_004)
            .is_err());
    }

    #[test]
    fn actual_root_owner_result_and_current_checkpoint_vector_export() {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        let (fresh, session) = new_pair(&a, &b, 400_000);
        let original = "a1".repeat(16);
        let actor = serde_json::json!({"app_process_generation":17,"app_record_sha256":"b2".repeat(32),"native_executor_generation":5,"config_sha256":"c3".repeat(32)});
        let arm = serde_json::json!({"binding":"established","challenge":"fixture-arm-actual-context","arm_generation":1,"native_process":27,"process_epoch":"fixture-epoch","runtime_config_sha256":"c3".repeat(32)});
        let hash = |domain: &str, v: &serde_json::Value| {
            use sha2::{Digest, Sha256};
            let mut h = Sha256::new();
            h.update(domain.as_bytes());
            h.update([0]);
            h.update(fresh_history_canonical(v).unwrap());
            format!("sha256:{:x}", h.finalize())
        };
        let prior = a.peer_root_projection().unwrap();
        let mut witness = witness(false);
        witness.request_id = id(&format!("request.root.{original}"));
        witness.native_setup_request_id = fresh.request.context.native_setup_request_id.clone();
        witness.native_phase_id = fresh.request.context.native_phase_id.clone();
        witness.native_actor_sha256 =
            hash("rusty.quest.embedded_duplex.root_native_actor.v1", &actor);
        witness.arm_binding_sha256 = hash("rusty.quest.embedded_duplex.root_arm.v1", &arm);
        witness.runtime_configuration_sha256 = format!("sha256:{}", "c3".repeat(32));
        let clock = p
            .read_concurrent_peer_clock(400_003, 400_003_000_000, 0)
            .unwrap();
        let receipt = a
            .reestablish_peer_roots(
                witness,
                &fresh,
                &session,
                clock,
                400_003,
                &AtomicBool::new(false),
            )
            .unwrap();
        let native =
            crate::project_peer_root_reestablishment(&original, &actor, &arm, &prior, &receipt)
                .unwrap();
        let before = a
            .current_root_lineage_observation(&receipt, &session, 400_004)
            .unwrap();
        a.checkpoint_dormant_peer_roots(400_005).unwrap();
        let after = a
            .current_root_lineage_observation(&receipt, &session, 400_006)
            .unwrap();
        assert_eq!(before.root_receipt_sha256, after.root_receipt_sha256);
        if let Some(path) = std::env::var_os("RQ_SOURCE_BOUND_ROOT_VECTOR_OUTPUT") {
            let body = serde_json::json!({"$schema":"rusty.quest.source_bound_root_owner_vector.v1",
            "qualification":"source-owned actual M/Broker writers; Native actor/ARM modeled DATA; no SDK/device authority",
            "native_root":native,"owner_receipt":receipt,"before_checkpoint":before,"after_checkpoint":after,
            "current_broker_evidence":p.runtime.as_ref().unwrap().evidence().unwrap()});
            std::fs::write(path, serde_json::to_vec(&body).unwrap()).unwrap();
        }
    }

    #[test]
    fn actual_root_terminal_source_retains_expired_commit_and_refuses_changed_seed() {
        let p = construct("peer.quest-a", 301_000, 301_000);
        let q = construct("peer.quest-b", 301_000, 301_000);
        let a = p.embedded_duplex_authority().unwrap();
        let b = q.embedded_duplex_authority().unwrap();
        enroll(&a);
        enroll(&b);
        let (fresh, session) = new_pair(&a, &b, 400_000);
        let seed = recover(
            &p,
            &a,
            &fresh,
            &session,
            400_003,
            &AtomicBool::new(false),
            false,
        )
        .unwrap();
        let source = a.root_terminal_custody_source(&seed).unwrap();
        assert_eq!(source["current_roots"], a.peer_root_projection().unwrap());
        assert!(
            !a.current_common_lan_session(&session, 500_000)
                .unwrap()
                .current
        );
        assert_eq!(
            a.root_terminal_custody_source(&seed).unwrap(),
            source,
            "expired authority remains exact history, never current rights"
        );
        let mut bad = seed;
        bad.authority.sequence += 1;
        assert!(a.root_terminal_custody_source(&bad).is_err());
    }
}
