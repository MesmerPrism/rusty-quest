    fn bootstrap_continuous_peer(
        authority: &QuestEmbeddedDuplexAuthority,
        peer: &str,
        key_id: &str,
        fingerprint: &str,
        key: &SigningKey,
        ordinal: u64,
    ) {
        let status = ManifoldPeerStatusProposal {
            schema_id: schema(PEER_PROPOSAL_SCHEMA),
            proposal_id: id(&format!("proposal.{peer}.{ordinal}")),
            expected_authority_revision: Revision::new(ordinal).expect("peer revision"),
            proposer_id: id("adapter.quest.embedded-duplex"),
            identity: ManifoldPeerIdentity {
                schema_id: schema(PEER_IDENTITY_SCHEMA),
                peer_id: id(peer),
                key_fingerprint: id(fingerprint),
                trust_domain: id("trust.morphospace.peer"),
                roles: vec![ManifoldPeerRole::Observer, ManifoldPeerRole::Rendezvous],
            },
            status: ManifoldPeerStatus {
                schema_id: schema(PEER_STATUS_SCHEMA),
                peer_id: id(peer),
                status_revision: Revision::INITIAL,
                observed_at_ms: 1_000,
                expires_at_ms: 301000,
                availability: ManifoldPeerAvailability::Ready,
                capability_ids: vec![
                    id("capability.rendezvous.ble"),
                    id("capability.route.rust-direct-p2p"),
                    id("capability.topology.wifi-direct"),
                ],
            },
            payload_class: ManifoldPeerPayloadClass::LowRateDescriptor,
        };
        assert!(
            authority
                .review_peer_status(status, 1_100)
                .expect("status")
                .1
                .applied
        );
        let public = key.verifying_key().to_bytes();
        let public_hex = public
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        let request = ManifoldPeerEnrollmentRequest {
            schema_id: schema(PEER_ENROLLMENT_REQUEST_SCHEMA),
            request_id: id(&format!("request.enroll.{peer}.{ordinal}")),
            expected_authority_revision: Revision::new(ordinal).expect("enrollment revision"),
            operator_id: id("operator.quest.peer-enrollment"),
            issued_at_ms: 1_200,
            action: ManifoldPeerEnrollmentAction::Enroll {
                credential: ManifoldPeerCredentialRecord {
                    schema_id: schema(PEER_CREDENTIAL_SCHEMA),
                    credential_id: id(&format!("credential.{peer}.1")),
                    peer_id: id(peer),
                    trust_domain: id("trust.morphospace.peer"),
                    key_id: id(key_id),
                    key_generation: 1,
                    algorithm: ManifoldPeerCredentialAlgorithm::Ed25519,
                    public_key_hex: public_hex,
                    public_key_sha256: format!("sha256:{}", sha256_hex(&public)),
                    valid_from_ms: 1_000,
                    expires_at_ms: 301000,
                    status: ManifoldPeerCredentialStatus::Active,
                    replaced_by_key_id: None,
                },
            },
        };
        assert!(
            authority
                .review_enrollment(&request, 1_200)
                .expect("enrollment")
                .applied
        );
    }

#[test]
fn concurrent_actual_22_coupled_cycles_preserve_two_live_resource_graphs() {
    struct FailStopOnce {inner:DeterministicAndroidMediaOwnerExecutor,failed:bool}
    impl AndroidMediaOwnerExecutor for FailStopOnce {
        fn executor_generation(&self)->u64{self.inner.executor_generation()}
        fn execute(&mut self,ticket:&AndroidMediaExecutionTicket,mode:AndroidMediaExecutionMode)->Result<AndroidMediaOwnerReadback,String>{
            let readback=self.inner.execute(ticket,mode)?;
            if ticket.operation==MediaStreamPlatformOperation::Stop && ticket.sequence==3 && mode==AndroidMediaExecutionMode::Execute && !self.failed {self.failed=true;return Err("actual owner side effect with uncertain Stop readback".to_owned());}Ok(readback)
        }
        fn verify(&self,ticket:&AndroidMediaExecutionTicket,readback:&AndroidMediaOwnerReadback)->bool{self.inner.verify(ticket,readback)}
    }

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
        let mut client:QuestBrokerClientLockSpec=serde_json::from_str(&runtime_config.packaged_authority.client_locks[0].client_lock_json).unwrap();
        client.capabilities.push(id("capability.manifold.control_lease.renew"));client.capabilities.sort();
        let raw=serde_json::to_string(&client).unwrap();let digest=packaged_json_sha256(&raw);
        runtime_config.packaged_authority.client_locks[0].client_lock_json=raw;
        runtime_config.packaged_authority.client_locks[0].client_lock_sha256=digest.clone();
        for grant in &mut runtime_config.admission.snapshot.grants {grant.client_lock_fingerprint=format!("sha256:{digest}");grant.capabilities=derive_grant_capabilities(&runtime_config.product_lock,&client);grant.expires_at_ms=301000;}
        runtime_config.admission.snapshot.max_token_ttl_ms=120000;
        for lease in &mut runtime_config.initial_leases {lease.expires_at_ms=301000;}
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
        let mut peer_b = QuestBrokerAuthorityRuntime::from_config(
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
            bootstrap_continuous_peer(
                authority,
                "peer.quest-a",
                "key.peer.quest-a.1",
                "fingerprint.peer.quest-a",
                &alpha,
                1,
            );
            bootstrap_continuous_peer(
                authority,
                "peer.quest-b",
                "key.peer.quest-b.1",
                "fingerprint.peer.quest-b",
                &beta,
                2,
            );
        }
        let request_a =
            renewal_reciprocal_request(&authority_a, "request.reciprocal.host-a", &alpha, &beta,1300,240000,1);
        let request_b =
            renewal_reciprocal_request(&authority_b, "request.reciprocal.host-b", &alpha, &beta,1300,240000,5);
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
            expires_at_ms: 240000,
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

        let c=caller();
        let mut provider_a=QuestBrokerRuntimeProvider{runtime:Some(runtime),..Default::default()};
        let mut provider_b=QuestBrokerRuntimeProvider{runtime:Some(peer_b),..Default::default()};
        for (index,provider) in [&mut provider_a,&mut provider_b].into_iter().enumerate() {
            let accepted=provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Start,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,4000,&"81".repeat(32)).unwrap();assert!(accepted.mutation.accepted);
            assert!(provider.issue_concurrent_peer_route(&id("session.peer.quest-a-b"),4050,&"82".repeat(32)).is_ok());
            if index==0 {provider.install_media_owner_executor(Box::new(DeterministicAndroidMediaOwnerExecutor::new(9).unwrap())).unwrap();}else{provider.install_media_owner_executor(Box::new(FailStopOnce{inner:DeterministicAndroidMediaOwnerExecutor::new(9).unwrap(),failed:false})).unwrap();}
            provider.runtime.as_mut().unwrap().complete_media_session_action(&QuestBrokerMediaCompletionRequest{client_id:identity().client_id},4100,
                provider.media_owner_executor.as_mut().unwrap().as_mut(),&mut provider.next_media_execution_nonce).unwrap();
        }
        let active_a=provider_a.runtime.as_ref().unwrap().media_sessions[&identity().client_id].recovery_snapshot().unwrap();
        let active_b=provider_b.runtime.as_ref().unwrap().media_sessions[&identity().client_id].recovery_snapshot().unwrap();
        let mut current_a=topology_a.clone(); let mut current_b=topology_b.clone();
        let mut receipts=[Vec::new(),Vec::new()];
        for cycle in 1..=22 {
            let now=64000u64+((cycle-1)/2) as u64*120000+((cycle-1)%2) as u64;
            for (index,authority,current) in [(0,&authority_a,&mut current_a),(1,&authority_b,&mut current_b)] {
                let first=renewal_reciprocal_request(authority,&format!("request.renew.c{cycle}.h{index}.credentials"),&alpha,&beta,now,(now+20000).min(current.expires_at_ms),1+(cycle*10+index*4) as u8);
                let proof=authority.apply_common_lan_reciprocal(&first,now).unwrap(); assert!(proof.accepted);
                let refresh=authority.refresh_concurrent_pair_credentials(&proof,now).unwrap();
                assert!(refresh.applied); assert_eq!(refresh.expires_at_ms,now+300000);
                assert_eq!(refresh.key_ids,proof.signer_key_ids);
                assert_eq!(authority.refresh_concurrent_pair_credentials(&proof,now).unwrap(),refresh);
                let fresh=renewal_reciprocal_request(authority,&format!("request.renew.c{cycle}.h{index}.session"),&alpha,&beta,now+1,now+235000,3+(cycle*10+index*4) as u8);
                let signed=authority.apply_common_lan_reciprocal(&fresh,now+1).unwrap(); assert!(signed.accepted,"actual rejection cycle{cycle} h{index}: {:?}",signed.rejection_reason);
                let snapshot:rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot=serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
                let mut next=proposal(&format!("renew.c{cycle}.h{index}"));
                next.expected_authority_revision=snapshot.peer_sessions.authority_revision; next.expires_at_ms=now+235000;
                let mut changed=next.clone(); changed.transport.route_configuration_sha256=format!("sha256:{}","f".repeat(64));
                assert!(authority.apply_common_lan_session_renewal(&changed,&signed,now+1).is_err());
                let renewal=authority.apply_common_lan_session_renewal(&next,&signed,now+1).unwrap();
                assert!(renewal.applied); assert_eq!(renewal.decision_id,current.decision_id);
                assert_eq!(renewal.prior_expires_at_ms,current.expires_at_ms);
                assert!(renewal.expires_at_ms>current.expires_at_ms);
                assert_eq!(authority.apply_common_lan_session_renewal(&next,&signed,now+1).unwrap(),renewal);
                let provider=if index==0 {&mut provider_a} else {&mut provider_b};
                let prior_clock=provider.runtime.as_ref().unwrap().runtime.read().unwrap().control_lease_authority_snapshot().clock_snapshot.clone();
                let mut clock=prior_clock.clone();clock.sequence+=1;clock.wall_unix_ms=(now+1) as i64;clock.monotonic_elapsed_ns=1_000_000_000+(now+1-1000)*1_000_000;
                let actual=provider.renew_concurrent_peer_authority(&clock,&renewal,now+1,&format!("{:02x}",cycle).repeat(32)).expect("actual full coupled owners");
                receipts[index].push(serde_json::to_value(&actual).unwrap());
                assert!(actual.media_authority.applied);assert!(actual.outer_lifecycle.applied);assert!(actual.admission_grant.application.applied);
                let retry=provider.renew_concurrent_peer_authority(&clock,&renewal,now+1,&format!("{:02x}",cycle).repeat(32)).unwrap();
                assert_eq!(serde_json::to_value(&actual).unwrap(),serde_json::to_value(&retry).unwrap());
                let recovery=provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id].recovery_snapshot().unwrap();
                let initial=if index==0 {&active_a} else {&active_b};let mut physical=serde_json::to_value(&recovery).unwrap();let mut original=serde_json::to_value(initial).unwrap();physical.as_object_mut().unwrap().remove("accepted_subject");original.as_object_mut().unwrap().remove("accepted_subject");assert_eq!(physical,original,"physical resource graph must remain unchanged");
                *current=renewal.topology.clone();
                let snapshot:rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot=serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
                let broker=provider.runtime.as_ref().unwrap().runtime.read().unwrap();
                let restored=rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(snapshot.clone(),&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker); assert!(restored.is_ok(), "actual restore failed: {:?}",restored);
                for field in 0..3 {
                    let mut wrong=snapshot.clone();
                    match field {
                        0=>wrong.media_command_runtime.leases[0].expires_at_ms+=1,
                        1=>wrong.media_sessions.sessions[0].expires_at_ms+=1,
                        _=>if let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(route)=&mut wrong.pair_media_routes.routes[0]{route.expires_at_ms+=1;},
                    }
                    assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(wrong,&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).is_err(),"unadopted current deadline rejects");
                }
                let mut malformed=snapshot.clone(); malformed.concurrent_session_renewals.last_mut().unwrap().renewed_session.decision_id=id("decision.forged");
                assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(malformed,&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).is_err());
            }
        }
        for (index,actual) in receipts.iter().enumerate() {
            let provider=if index==0 {&provider_a} else {&provider_b};println!("ACTUAL_FULL_BROKER_EVIDENCE_BYTES host{index} {}",provider.evidence_json().unwrap().len());
            let bytes=serde_json::to_vec(actual).unwrap();println!("ACTUAL_22_RECEIPTS_BYTES host{index} {}",bytes.len());
            if let Ok(directory)=std::env::var("RQ_RENEWAL_RECEIPT_TEST_OUTPUT") {std::fs::write(std::path::Path::new(&directory).join(format!("actual-renewals-{index}.json")),&bytes).unwrap();}
        }
        for provider in [&mut provider_a] {
            let base=1_265_000;
            assert!(provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Stop,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,base,&"d3".repeat(32)).unwrap().mutation.accepted);
            assert!(provider.terminate_concurrent_peer_route(false,base+10,&"d4".repeat(32)).unwrap().applied);
            let effect=provider.complete_media_stop_for_cleanup_typed(&identity().client_id,base+100).unwrap().stop_effect_receipt.unwrap();
            assert_eq!(effect.owner_receipt_ids.len(),7);
            let clean=provider.complete_concurrent_peer_route_cleanup(&effect,base+200,&"d5".repeat(32)).unwrap();assert_eq!(clean.len(),1);
            assert_eq!(clean,provider.complete_concurrent_peer_route_cleanup(&effect,base+210,&"d6".repeat(32)).unwrap());
            assert!(provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Start,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,base+300,&"d7".repeat(32)).unwrap().mutation.accepted);
            provider.issue_concurrent_peer_route(&id("session.peer.quest-a-b"),base+350,&"d8".repeat(32)).unwrap();
            provider.runtime.as_mut().unwrap().complete_media_session_action(&QuestBrokerMediaCompletionRequest{client_id:identity().client_id},base+400,provider.media_owner_executor.as_mut().unwrap().as_mut(),&mut provider.next_media_execution_nonce).unwrap();
            let mut clock=provider.runtime.as_ref().unwrap().runtime.read().unwrap().control_lease_authority_snapshot().clock_snapshot.clone();clock.sequence+=1;clock.wall_unix_ms=(base+500) as i64;clock.monotonic_elapsed_ns+=1_500_000_000;
            provider.adopt_concurrent_peer_revoker(&clock,base+500,&"d9".repeat(32)).unwrap();
            assert!(provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Stop,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,base+600,&"da".repeat(32)).unwrap().mutation.accepted);
            assert!(provider.terminate_concurrent_peer_route(true,base+610,&"db".repeat(32)).unwrap().applied);
            let effect=provider.complete_media_stop_for_cleanup_typed(&identity().client_id,base+700).unwrap().stop_effect_receipt.unwrap();assert_eq!(effect.owner_receipt_ids.len(),7);
            assert_eq!(provider.complete_concurrent_peer_route_cleanup(&effect,base+800,&"dc".repeat(32)).unwrap().len(),1);
        }

        // Host B retains the original physical graph through expiry; no restart or authority reset.
        let provider=&mut provider_b;
        let late=1_600_000;
        assert!(provider.apply_concurrent_peer_command(QuestConcurrentPeerCommand::Stop,c.sending_uid,&c.package_name,&c.signing_certificate_sha256,late,&"e3".repeat(32)).is_err(),"expired ordinary authority cannot Stop");
        let mut clock=provider.runtime.as_ref().unwrap().runtime.read().unwrap().control_lease_authority_snapshot().clock_snapshot.clone();clock.sequence+=1;clock.wall_unix_ms=late as i64;clock.monotonic_elapsed_ns=1_000_000_000+(late-1000)*1_000_000;
        let adoption=provider.adopt_concurrent_peer_revoker(&clock,late,&"e4".repeat(32)).expect("real generic old revoker expiry and fresh adoption");
        assert!(provider.concurrent_peer_requires_revoker_cleanup(&identity().client_id,late).unwrap());
        let recovery=provider.prepare_concurrent_peer_revoker_cleanup(&identity().client_id,&adoption,late,&"e8".repeat(32)).expect("actual fixed provider trusted cleanup");
        assert!(recovery.recovery.termination.applied);assert!(recovery.action.trusted_revoker_cleanup.is_some());
        assert!(provider.terminate_concurrent_peer_route(true,late+10,&"e5".repeat(32)).expect("real late route Revoke").applied);
        assert!(provider.complete_media_stop_for_cleanup_typed(&identity().client_id,late+100).is_err(),"physical uncertainty stays Pending");
        let retained=provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id].pending_action().unwrap().clone();
        let retry_at=late+31_000;
        let mut clock=provider.runtime.as_ref().unwrap().runtime.read().unwrap().control_lease_authority_snapshot().clock_snapshot.clone();clock.sequence=provider.concurrent_revoker_clock().unwrap().sequence+1;clock.wall_unix_ms=retry_at as i64;clock.monotonic_elapsed_ns=1_000_000_000+(retry_at-1000)*1_000_000;
        let fresh=provider.adopt_concurrent_peer_revoker(&clock,retry_at,&"e7".repeat(32)).expect("actual generic revoker expiry/adoption and fresh cleanup lease");
        let grant=provider.runtime.as_ref().unwrap().peer_runtime_host.as_ref().unwrap().read().unwrap().snapshot().pair_media_routes.routes.iter().find(|route|*route.cleanup_status()==rusty_manifold_peer::ManifoldPairMediaRouteCleanupStatus::Pending).unwrap().grant_id().clone();
        let projection=authority_b.retained_cleanup_projection(&grant,&fresh.revoker_id,&fresh.lease.lease_id,&id("peer.quest-b"),&id("peer.quest-a"),retry_at).expect("actual late two-principal cleanup projection");
        let local=authority_b.retained_local_cleanup_projection(&grant,&fresh.revoker_id,&fresh.lease.lease_id,&id("peer.quest-b"),retry_at).expect("actual local two-principal cleanup projection");assert_eq!(local.authority_peer_id,local.executor_peer_id);assert_eq!(local.target_client_id,retained.client_authority.client_id);
        assert!(authority_b.retained_cleanup_projection(&grant,&fresh.revoker_id,&fresh.lease.lease_id,&id("peer.quest-b"),&id("peer.quest-b"),retry_at).is_err(),"remote scope remains distinct");
        assert!(projection.trusted_revoker);assert_eq!(projection.target_client_id,retained.client_authority.client_id);assert_eq!(projection.target_runtime_lease_id,retained.client_authority.lease_id);assert_ne!(projection.requester_id,projection.target_client_id);assert!(projection.historical_topology_expires_at_ms<retry_at);assert!(projection.requester_expires_at_ms>retry_at);
        assert_eq!(provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id].pending_action().unwrap(),&retained,"physical retry preserves original action and authentic Revoke proof");
        let completed=provider.complete_media_stop_for_cleanup_typed(&identity().client_id,retry_at+100).expect("seven actual late Stop effects");let effect=completed.stop_effect_receipt.unwrap();assert_eq!(effect.owner_receipt_ids.len(),7);
        assert_eq!(provider.complete_concurrent_peer_route_cleanup(&effect,retry_at+200,&"e6".repeat(32)).expect("late actual route cleanup and lease release").len(),1);

    }
