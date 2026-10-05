#[test]
fn concurrent_revoker_uses_distinct_retained_authority_and_exact_adoption() {
    let cfg=config(QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,vec![ManifoldBrokerFeature::MediaSession],"command.media.session.start",true)
        .with_embedded_duplex(QuestEmbeddedDuplexAuthorityConfig {
            schema_id:crate::QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA.to_owned(),runtime_host_id:id("host.quest-a.media-runtime"),
            trusted_operator_ids:vec![id("operator.quest.peer-enrollment")],trusted_key_fingerprints:vec![id("fingerprint.peer.quest-a"),id("fingerprint.peer.quest-b")],
            trusted_adapter_ids:vec![id("adapter.quest.embedded-duplex")],trusted_media_revoker_ids:vec![id("operator.quest.media-revoker")],
        }).expect("authenticated typed config");
    let runtime=QuestBrokerAuthorityRuntime::from_config(cfg,&"09".repeat(32),1000,1_000_000_000).expect("runtime");
    let mut provider=QuestBrokerRuntimeProvider{runtime:Some(runtime),..Default::default()};
    let original=provider.runtime.as_ref().unwrap().runtime.read().unwrap().control_lease_authority_snapshot().clone();
    let mut clock=original.clock_snapshot.clone();clock.sequence+=1;clock.wall_unix_ms=1100;clock.monotonic_elapsed_ns+=100_000_000;
    let receipt=provider.adopt_concurrent_peer_revoker(&clock,1100,&"21".repeat(32)).expect("actual generic issue and peer adoption");
    assert_eq!(receipt.lease.holder_id,id("operator.quest.media-revoker"));
    assert_eq!(provider.runtime.as_ref().unwrap().runtime.read().unwrap().control_lease_authority_snapshot(),&original);
    assert!(provider.concurrent_revoker_clock().is_some());
    let again=provider.adopt_concurrent_peer_revoker(&clock,1100,&"22".repeat(32)).expect("retained retry");
    assert_eq!(receipt,again);
    assert_eq!(provider.runtime.as_ref().unwrap().peer_runtime_host.as_ref().unwrap().read().unwrap().snapshot().media_command_runtime.leases.iter().filter(|l|l.holder_id==id("operator.quest.media-revoker")).count(),1);
    clock.clock_epoch_id=id("epoch.foreign");
    assert!(provider.adopt_concurrent_peer_revoker(&clock,1100,&"24".repeat(32)).is_err());
}
#[test]
    fn concurrent_actual_start_issues_source_bound_route() {
        let mut runtime_config = config(
            QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
            vec![ManifoldBrokerFeature::MediaSession],
            "command.media.session.start",
            true,
        );
        runtime_config = runtime_config
            .with_embedded_duplex(QuestEmbeddedDuplexAuthorityConfig {
                schema_id: crate::QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA.to_owned(),
                runtime_host_id: id("host.quest-a.media-runtime"),
                trusted_operator_ids: vec![id("operator.quest.peer-enrollment")],
                trusted_key_fingerprints: vec![
                    id("fingerprint.peer.quest-a"),
                    id("fingerprint.peer.quest-b"),
                ],
                trusted_adapter_ids: vec![id("adapter.quest.embedded-duplex")],
                trusted_media_revoker_ids: vec![id("operator.quest.media-revoker")],
            })
            .expect("duplex config");
        let placements = runtime_config
            .embedded_duplex_owner_placements(
                "runtime.media.display-example",
                "peer.quest-a",
                "peer.quest-a",
                &[
                    AndroidMediaDevicePeerPlacement {
                        device_id: "quest-a".to_owned(),
                        peer_id: "peer.quest-a".to_owned(),
                    },
                    AndroidMediaDevicePeerPlacement {
                        device_id: "pc-host".to_owned(),
                        peer_id: "peer.quest-b".to_owned(),
                    },
                ],
            )
            .expect("exact packaged placements");
        assert_eq!(placements.len(), 7);
        assert!(placements.iter().any(|placement| {
            placement.owner_kind == MediaStreamOwnerKind::Sink
                && matches!(
                    &placement.target,
                    rusty_quest_media_stream_android::AndroidMediaOwnerPlacementTarget::Remote {
                        peer_id
                    } if peer_id == "peer.quest-b"
                )
        }));
        assert!(placements.iter().any(|placement| {
            placement.owner_kind == MediaStreamOwnerKind::Cleanup
                && placement.target
                    == rusty_quest_media_stream_android::AndroidMediaOwnerPlacementTarget::Local
        }));
        let mut peer_b_config = runtime_config.clone();
        peer_b_config
            .embedded_duplex
            .as_mut()
            .expect("duplex config")
            .runtime_host_id = id("host.quest-b.media-runtime");
        let mut runtime = QuestBrokerAuthorityRuntime::from_config(
            runtime_config,
            &"09".repeat(32),
            1_000,
            1_000_000_000,
        )
        .expect("duplex runtime");
        let snapshot: serde_json::Value = serde_json::from_str(
            &runtime
                .embedded_duplex_authority()
                .expect("duplex authority")
                .snapshot_json()
                .expect("snapshot"),
        )
        .expect("snapshot json");
        assert_eq!(
            snapshot["trust_policy"]["enabled_authority_families"],
            serde_json::json!(["peer_status", "enrollment", "rendezvous", "media_session"])
        );
        assert_eq!(snapshot["host_id"], "host.quest-a.media-runtime");
        let peer_b = QuestBrokerAuthorityRuntime::from_config(
            peer_b_config,
            &"0a".repeat(32),
            1_000,
            1_000_000_000,
        )
        .expect("second duplex runtime");
        let peer_b_snapshot: serde_json::Value = serde_json::from_str(
            &peer_b
                .embedded_duplex_authority()
                .expect("second duplex authority")
                .snapshot_json()
                .expect("second snapshot"),
        )
        .expect("second snapshot json");
        assert_eq!(peer_b_snapshot["host_id"], "host.quest-b.media-runtime");
        assert_ne!(snapshot["host_id"], peer_b_snapshot["host_id"]);
        let authority_a = runtime.embedded_duplex_authority().expect("authority a");
        let authority_b = peer_b.embedded_duplex_authority().expect("authority b");
        let alpha = SigningKey::from_bytes(&[41; 32]);
        let beta = SigningKey::from_bytes(&[42; 32]);
        for authority in [&authority_a, &authority_b] {
            bootstrap_common_lan_peer(
                authority,
                "peer.quest-a",
                "key.peer.quest-a.1",
                "fingerprint.peer.quest-a",
                &alpha,
                1,
            );
            bootstrap_common_lan_peer(
                authority,
                "peer.quest-b",
                "key.peer.quest-b.1",
                "fingerprint.peer.quest-b",
                &beta,
                2,
            );
        }
        let request_a =
            reciprocal_request(&authority_a, "request.reciprocal.host-a", &alpha, &beta);
        let request_b =
            reciprocal_request(&authority_b, "request.reciprocal.host-b", &alpha, &beta);
        assert_ne!(
            request_a.context.runtime_host_id,
            request_b.context.runtime_host_id
        );
        assert_ne!(
            rusty_manifold_peer::common_lan_reciprocal_ed25519_context_sha256(&request_a.context),
            rusty_manifold_peer::common_lan_reciprocal_ed25519_context_sha256(&request_b.context)
        );
        let mut wrong_key = request_a.clone();
        let wrong_bytes =
            QuestEmbeddedDuplexAuthority::common_lan_context_signing_bytes(&wrong_key.context);
        wrong_key.responder_signature.signature_hex = alpha
            .sign(&wrong_bytes)
            .to_bytes()
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        assert!(
            !authority_a
                .apply_common_lan_reciprocal(&wrong_key, 1_400)
                .expect("typed wrong-key rejection")
                .accepted
        );
        let receipt_a = authority_a
            .apply_common_lan_reciprocal(&request_a, 1_400)
            .expect("reciprocal a");
        let receipt_b = authority_b
            .apply_common_lan_reciprocal(&request_b, 1_400)
            .expect("reciprocal b");
        let proposal = |suffix: &str| ManifoldCommonLanPeerSessionProposal {
            schema_id: schema(COMMON_LAN_PEER_SESSION_PROPOSAL_SCHEMA),
            proposal_id: id(&format!("proposal.session.{suffix}")),
            session_id: id("session.peer.quest-a-b"),
            expected_authority_revision: Revision::INITIAL,
            subject_peer_id: id("peer.quest-a"),
            candidate_peer_id: id("peer.quest-b"),
            initiator_peer_id: id("peer.quest-a"),
            responder_peer_id: id("peer.quest-b"),
            requested_capability_ids: vec![
                id("capability.rendezvous.ble"),
                id("capability.route.rust-direct-p2p"),
                id("capability.topology.wifi-direct"),
            ],
            transport: common_lan_transport('5'),
            expires_at_ms: 50_000,
        };
        let (_, topology_a) = authority_a
            .apply_common_lan_session(&proposal("host-a"), &receipt_a, 1_500)
            .expect("session a");
        let (_, topology_b) = authority_b
            .apply_common_lan_session(&proposal("host-b"), &receipt_b, 1_500)
            .expect("session b");
        assert_ne!(
            sha256_hex(&serde_json::to_vec(&topology_a).expect("topology a")),
            sha256_hex(&serde_json::to_vec(&topology_b).expect("topology b"))
        );
        let (use_id, token_id)=admit(&mut runtime,"command.media.session.start");
        let start=mutation(&runtime,QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,use_id,token_id,"command.media.session.start",Some("lease.broker.media-session.quest.runtime"));
        assert!(runtime.handle_server_mutation(&start,4000).expect("actual admitted Start").accepted);
        let mut provider=QuestBrokerRuntimeProvider{runtime:Some(runtime),..Default::default()};
        let route=provider.issue_concurrent_peer_route(&id("session.peer.quest-a-b"),4050,&"31".repeat(32)).expect("actual route issue");
        let first_grant=match route {rusty_manifold_peer::ManifoldPairMediaRouteReceiptV2::CommonLan(v)=>v.route.expect("accepted route").grant_id,_=>panic!("wrong transport")};
        provider.install_media_owner_executor(Box::new(DeterministicAndroidMediaOwnerExecutor::new(9).unwrap())).unwrap();
        provider.runtime.as_mut().unwrap().complete_media_session_action(&QuestBrokerMediaCompletionRequest{client_id:identity().client_id},4100,
            provider.media_owner_executor.as_mut().unwrap().as_mut(),&mut provider.next_media_execution_nonce).expect("seven owner Start completion");
        let c=caller();
        let stop=provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Stop,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,5000,&"32".repeat(32)).expect("admitted Stop");
        assert!(stop.mutation.accepted);
        let termination=provider.terminate_concurrent_peer_route(false,5010,&"33".repeat(32)).expect("actual route Stop");
        assert!(termination.applied);
        let route_state=provider.runtime.as_ref().unwrap().peer_runtime_host.as_ref().unwrap().read().unwrap().snapshot().pair_media_routes.clone();

        assert!(rusty_manifold_peer::pair_media_route_state_v2_is_well_formed(&route_state),"invalid immediately after termination");
        let host_snapshot=provider.runtime.as_ref().unwrap().peer_runtime_host.as_ref().unwrap().read().unwrap().snapshot().clone();
        let broker_guard=provider.runtime.as_ref().unwrap().runtime.read().unwrap();
        let restore=|candidate|rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(candidate,&host_snapshot.trust_policy,&host_snapshot.provider_epoch_id,&broker_guard);
        restore(host_snapshot.clone()).expect("actual derivative terminal snapshot restores");
        let mut malformed=host_snapshot.clone();
        if let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(v)=&mut malformed.pair_media_routes.routes[0]{
            v.termination_runtime_binding.as_mut().unwrap().runtime_lease.derivative_binding.as_mut().unwrap().upstream_control_lease_id=id("lease.foreign");
        }
        assert!(restore(malformed).is_err(),"no retained Broker admission joins substituted upstream");
        let mut malformed=host_snapshot.clone();
        if let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(v)=&mut malformed.pair_media_routes.routes[0]{
            v.termination_runtime_binding.as_mut().unwrap().runtime_lease.derivative_binding.as_mut().unwrap().binding_id=id("binding.foreign");
        }
        assert!(restore(malformed).is_err(),"malformed binding rejected");
        drop(broker_guard);
        let mut malformed=host_snapshot.clone();
        if let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(v)=&mut malformed.pair_media_routes.routes[0]{
            v.termination_runtime_binding.as_mut().unwrap().runtime_lease.derivative_binding.as_mut().unwrap().schema_id=schema("rusty.manifold.runtime_host.derivative_lease_binding.v2");
        }
        let broker_guard=provider.runtime.as_ref().unwrap().runtime.read().unwrap();
        assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(malformed,&host_snapshot.trust_policy,&host_snapshot.provider_epoch_id,&broker_guard).is_err(),"malformed derivative schema rejected");
        drop(broker_guard);
        let completed=provider.complete_media_stop_for_cleanup_typed(&identity().client_id,5100).expect("actual seven-owner Stop cleanup");
        let effect=completed.stop_effect_receipt.expect("actual native Stop effect");
        assert_eq!(effect.owner_receipt_ids.len(),7);
        let clean=provider.complete_concurrent_peer_route_cleanup(&effect,5200,&"34".repeat(32)).expect("route cleanup and inner lease release");
        assert_eq!(clean.len(),1);
        assert_eq!(clean,provider.complete_concurrent_peer_route_cleanup(&effect,5250,&"37".repeat(32)).expect("real retained cleanup on retry"));
        let restart_result=provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Start,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,6000,&"35".repeat(32));
        if restart_result.is_err(){println!("RESTART_STATE {}",provider.runtime.as_ref().unwrap().embedded_duplex_authority().unwrap().snapshot_json().unwrap());}
        let restart=restart_result.expect("admitted restart after physical cleanup");
        assert!(restart.mutation.accepted);
        let next=provider.issue_concurrent_peer_route(&id("session.peer.quest-a-b"),6050,&"36".repeat(32)).expect("new actual route");
        match next {rusty_manifold_peer::ManifoldPairMediaRouteReceiptV2::CommonLan(v)=>assert_ne!(v.route.unwrap().grant_id,first_grant),_=>panic!("wrong transport")};
        provider.runtime.as_mut().unwrap().complete_media_session_action(&QuestBrokerMediaCompletionRequest{client_id:identity().client_id},6100,
            provider.media_owner_executor.as_mut().unwrap().as_mut(),&mut provider.next_media_execution_nonce).expect("seven owner restarted Start");
        let snapshot=provider.runtime.as_ref().unwrap().peer_runtime_host.as_ref().unwrap().read().unwrap().snapshot().clone();
        let broker=provider.runtime.as_ref().unwrap().runtime.read().unwrap();
        rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(snapshot.clone(),&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).expect("readoption history restores");
        drop(broker);
        let mut incomplete=snapshot.clone();
        incomplete.broker_lease_admissions[0].released_at_ms=None;
        incomplete.broker_lease_admissions[0].release_id=None;
        let broker=provider.runtime.as_ref().unwrap().runtime.read().unwrap();
        assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(incomplete,&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).is_err(),"missing old release proof rejects");
        let mut incomplete=snapshot.clone();incomplete.pair_media_routes.cleanup_receipts.clear();
        assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(incomplete,&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).is_err(),"missing physical cleanup rejects");
        drop(broker);
        let mut clock=provider.runtime.as_ref().unwrap().runtime.read().unwrap().control_lease_authority_snapshot().clock_snapshot.clone();
        clock.sequence+=1;clock.wall_unix_ms=6500;clock.monotonic_elapsed_ns+=5_500_000_000;
        provider.adopt_concurrent_peer_revoker(&clock,6500,&"38".repeat(32)).expect("actual enrolled revoker");
        assert!(provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Stop,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,6600,&"39".repeat(32)).unwrap().mutation.accepted);
        assert!(provider.terminate_concurrent_peer_route(true,6610,&"40".repeat(32)).expect("second actual route Revoke").applied);
        let completion=provider.complete_media_stop_for_cleanup_typed(&identity().client_id,6700).expect("second seven-owner cleanup");
        assert_eq!(provider.complete_concurrent_peer_route_cleanup(&completion.stop_effect_receipt.unwrap(),6800,&"41".repeat(32)).expect("second retained cleanup").len(),1);


    }

#[test]
fn concurrent_renewal_capability_requires_selected_media_and_authenticated_client_lock() {
    let mut cfg = config(QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
        vec![ManifoldBrokerFeature::MediaSession], "command.media.session.start", true);
    let mut client: QuestBrokerClientLockSpec = serde_json::from_str(
        &cfg.packaged_authority.client_locks[0].client_lock_json).unwrap();
    let cap=id("capability.manifold.control_lease.renew");
    assert!(!derive_grant_capabilities(&cfg.product_lock,&client).contains(&cap));
    client.capabilities.push(cap.clone()); client.capabilities.sort();
    assert!(derive_grant_capabilities(&cfg.product_lock,&client).contains(&cap));
    let base = resolve_broker_product(&ManifoldBrokerProductSpec {
        schema_id:schema(BROKER_PRODUCT_SPEC_SCHEMA), product_id:id("broker.renewal.base"),
        standalone_enabled:true,embedded_enabled:false,requested_features:Vec::new(),
    }).unwrap();
    assert!(!derive_grant_capabilities(&base,&client).contains(&cap));
    // A client byte change cannot add authority to the old authenticated grant.
    cfg.packaged_authority.client_locks[0].client_lock_json=serde_json::to_string(&client).unwrap();
    assert!(QuestBrokerAuthorityRuntime::from_config(cfg,&"91".repeat(32),1000,1_000_000_000).is_err());
}
