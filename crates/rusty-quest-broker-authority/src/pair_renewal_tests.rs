    fn renewal_reciprocal_request(
        authority: &QuestEmbeddedDuplexAuthority,
        request_id: &str,
        alpha: &SigningKey,
        beta: &SigningKey,
        now: u64, expiry: u64, nonce: u8,
    ) -> ManifoldCommonLanReciprocalEd25519ReviewRequest {
        let draft = crate::embedded_duplex::QuestCommonLanContextDraft {
            correlation_id: id(request_id),
            initiator: ManifoldCommonLanReciprocalEd25519PeerBinding {
                peer_id: id("peer.quest-a"),
                key_id: id("key.peer.quest-a.1"),
                key_generation: 1,
                public_key_sha256: format!(
                    "sha256:{}",
                    sha256_hex(&alpha.verifying_key().to_bytes())
                ),
                role: ManifoldCommonLanPairRole::Initiator,
                device_nonce_hex: format!("d1{nonce:02x}").repeat(16),
            },
            responder: ManifoldCommonLanReciprocalEd25519PeerBinding {
                peer_id: id("peer.quest-b"),
                key_id: id("key.peer.quest-b.1"),
                key_generation: 1,
                public_key_sha256: format!(
                    "sha256:{}",
                    sha256_hex(&beta.verifying_key().to_bytes())
                ),
                role: ManifoldCommonLanPairRole::Responder,
                device_nonce_hex: format!("d2{:02x}",nonce+1).repeat(16),
            },
            transport: common_lan_transport('5'),
            coordinator_epoch: 1,
            issued_at_ms: now,
            expires_at_ms: expiry,
        };
        let context = authority
            .prepare_common_lan_context(draft)
            .expect("context");
        let bytes = QuestEmbeddedDuplexAuthority::common_lan_context_signing_bytes(&context);
        let digest = rusty_manifold_peer::common_lan_reciprocal_ed25519_context_sha256(&context);
        let signature = |peer: &str, key_id: &str, key: &SigningKey| {
            ManifoldCommonLanReciprocalEd25519Signature {
                schema_id: schema(COMMON_LAN_RECIPROCAL_ED25519_SIGNATURE_SCHEMA),
                signer_peer_id: id(peer),
                signer_key_id: id(key_id),
                context_sha256: digest.clone(),
                signature_hex: key
                    .sign(&bytes)
                    .to_bytes()
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect(),
            }
        };
        ManifoldCommonLanReciprocalEd25519ReviewRequest {
            schema_id: schema(COMMON_LAN_RECIPROCAL_ED25519_REVIEW_SCHEMA),
            request_id: id(request_id),
            context,
            initiator_signature: signature("peer.quest-a", "key.peer.quest-a.1", alpha),
            responder_signature: signature("peer.quest-b", "key.peer.quest-b.1", beta),
        }
    }

#[test]
fn concurrent_actual_current_keys_and_same_session_renew_without_restart() {
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

        let mut current_a=topology_a.clone(); let mut current_b=topology_b.clone();
        for cycle in 1..=22 {
            let now=2000u64+((cycle-1)/2) as u64*120000+((cycle-1)%2) as u64;
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
                *current=renewal.topology.clone();
                let snapshot:rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot=serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
                let restored=rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot(snapshot.clone(),&snapshot.trust_policy,&snapshot.provider_epoch_id); assert!(restored.is_ok(), "actual restore failed: {:?}",restored);
                let mut malformed=snapshot.clone(); malformed.concurrent_session_renewals.last_mut().unwrap().renewed_session.decision_id=id("decision.forged");
                assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot(malformed,&snapshot.trust_policy,&snapshot.provider_epoch_id).is_err());
            }
        }
    }
