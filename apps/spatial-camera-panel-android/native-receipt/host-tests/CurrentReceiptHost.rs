#[test]
fn remote_current_receipt_production_controls() {
    // The actual selected authority produces and revalidates both peer receipts.
    // Q/W labels model receiver clocks only, never actual device observations.
    let p = construct("peer.quest-a", 3_000_000, 3_000_000);
    let q = construct("peer.quest-b", 3_000_000, 3_000_000);
    let a = p.embedded_duplex_authority().unwrap();
    let b = q.embedded_duplex_authority().unwrap();
    enroll(&a);
    enroll(&b);
    for now in [400_000u64, 800_000u64] {
        let (_fresh, session) = new_pair(&a, &b, now);
        let [ka, kb] = keys();
        let request = renewal_reciprocal_request(
            &b,
            &format!("request.current-repro.w-{now}"),
            &ka,
            &kb,
            now + 1,
            now + 60_000,
            now + 719,
        );
        let reciprocal = b.apply_common_lan_reciprocal(&request, now + 1).unwrap();
        assert!(reciprocal.accepted);
        let snapshot: serde_json::Value =
            serde_json::from_str(&b.snapshot_json().unwrap()).unwrap();
        let proposal:ManifoldCommonLanPeerSessionProposal=serde_json::from_value(serde_json::json!({"$schema":COMMON_LAN_PEER_SESSION_PROPOSAL_SCHEMA,
        "proposal_id":b.current_runtime_history_request_id(&id(&format!("proposal.current-repro.w-{now}"))).unwrap(),"session_id":session,
        "expected_authority_revision":snapshot["peer_sessions"]["authority_revision"],"subject_peer_id":"peer.quest-a","candidate_peer_id":"peer.quest-b",
        "initiator_peer_id":"peer.quest-a","responder_peer_id":"peer.quest-b","requested_capability_ids":["capability.rendezvous.ble","capability.route.rust-direct-p2p","capability.topology.wifi-direct"],
        "transport":common_lan_transport('5'),"expires_at_ms":now+50_000})).unwrap();
        let (decision, topology) = b
            .apply_common_lan_session(&proposal, &reciprocal, now + 2)
            .unwrap();
        assert!(decision.applied && topology.authorized);
        let w = b.current_common_lan_session(&session, now + 1200).unwrap();
        assert!(w.current && w.rejection_reason.is_none());
        let q_receipt = a.current_common_lan_session(&session, now + 100).unwrap();
        assert!(q_receipt.current);
        let w_json = serde_json::to_value(&w).unwrap();
        let q_json = serde_json::to_value(&q_receipt).unwrap();
        let expiry = w.expires_at_ms.unwrap();
        assert_eq!(w.expires_at_ms, q_receipt.expires_at_ms);
        // Same real receipt passes at its producer wall clock and on a later peer.
        actual_current_consumer::validate(&w_json, session.as_str(), expiry, now + 1200, true)
            .unwrap();
        actual_current_consumer::validate(&q_json, session.as_str(), expiry, now + 1200, false)
            .unwrap();
        // The production remote wrapper waits for actual clock samples; its strict
        // validator and the producer's timestamp remain unchanged.
        actual_current_consumer::remote_after_catchup(
            &w_json,
            session.as_str(),
            expiry,
            now + 100,
            now + 1200,
            true,
        )
        .unwrap();
        // Local current receipts remain strict, including the exact same future one.
        assert!(actual_current_consumer::validate(
            &w_json,
            session.as_str(),
            expiry,
            now + 100,
            true
        )
        .is_err());
        actual_current_consumer::remote_refusals(
            &w_json,
            session.as_str(),
            expiry,
            now + 100,
            true,
        );
        for damage in [
            "schema",
            "current",
            "rejection",
            "session",
            "expiry",
            "decision",
            "peers",
            "topology",
        ] {
            let mut bad = w_json.clone();
            match damage {
                "schema" => {
                    bad["$schema"] =
                        serde_json::json!("rusty.manifold.peer.session_current_receipt.v1")
                }
                "current" => bad["current"] = serde_json::json!(false),
                "rejection" => bad["rejection_reason"] = serde_json::json!("expired"),
                "session" => bad["session_id"] = serde_json::json!("session.foreign"),
                "expiry" => bad["expires_at_ms"] = serde_json::json!(expiry - 1),
                "decision" => bad["decision_id"] = serde_json::Value::Null,
                "peers" => bad["peer_ids"] = serde_json::json!(["peer.quest-a", "peer.foreign"]),
                _ => bad["topology_contract_id"] = serde_json::json!("topology.foreign"),
            };
            assert!(
                actual_current_consumer::validate(&bad, session.as_str(), expiry, now + 1200, true)
                    .is_err(),
                "{damage}"
            );
            actual_current_consumer::refuse_before_wait(
                &bad,
                session.as_str(),
                expiry,
                now + 100,
                true,
            );
        }
        assert!(
            actual_current_consumer::validate(&w_json, session.as_str(), expiry, expiry, true)
                .is_err()
        );
        assert!(
            !a.current_common_lan_session(&session, expiry)
                .unwrap()
                .current
        );
    }
    eprintln!("PASS actual selected Broker/Manifold bilateral producers in two successive sessions in the same authorities; strict local, remote catch-up, shape/subject/expiry/cancellation controls; modeled clocks/phase, no Android/JNI/device claim");
}
