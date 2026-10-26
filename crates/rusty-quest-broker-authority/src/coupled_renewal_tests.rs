// Optional immutable vectors from the actual coupled production-owner seam.
// These are not device lifecycle or fresh Java/frame observations.
fn export_ongoing_owner_vector(name: &str, value: &serde_json::Value) {
    if let Ok(directory) = std::env::var("RQ_ONGOING_OWNER_VECTOR_OUTPUT") {
        use std::io::Write;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(std::path::Path::new(&directory).join(name))
            .unwrap();
        file.write_all(&serde_json::to_vec(value).unwrap()).unwrap();
    }
}

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
        proposal_id: authority
            .current_runtime_history_request_id(&id(&format!("proposal.{peer}.{ordinal}")))
            .unwrap(),
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
        request_id: authority
            .current_runtime_history_request_id(&id(&format!("request.enroll.{peer}.{ordinal}")))
            .unwrap(),
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
    run_coupled_owner_graph(false);
}

#[test]
fn retained_failed_start_uses_live_revoker_and_original_target_stop() {
    run_coupled_owner_graph(true);
}

fn run_coupled_owner_graph(retained_abort: bool) {
    run_coupled_owner_graph_with_projection_probe(retained_abort, false, 0, false);
}

#[test]
fn retained_prepare_sender_grant_and_requester_are_not_receiver_local_authority() {
    run_coupled_owner_graph_with_projection_probe(false, true, 0, false);
}

#[test]
fn retained_prepare_authenticates_original_after_coupled_renewal_and_expiry() {
    run_coupled_owner_graph_with_projection_probe(false, true, 2, false);
}

#[test]
fn retained_prepare_compact_22_coupled_cycles_then_expiry() {
    run_coupled_owner_graph_with_projection_probe(false, true, 22, false);
}

#[test]
fn retained_prepare_latest_start_after_long_session_prefix() {
    run_coupled_owner_graph_with_projection_probe(false, true, 16, true);
}

#[test]
fn retained_prepare_renewal_after_supported_media_restart() {
    run_coupled_owner_graph_with_projection_probe(false, true, 22, true);
}

#[test]
fn retained_prepare_32_renewals_requires_explicit_shared_revoker_trust() {
    run_coupled_owner_graph_with_revoker_policy(false, true, 32, false, false);
    run_coupled_owner_graph_with_revoker_policy(false, true, 32, false, true);
}

fn run_coupled_owner_graph_with_projection_probe(
    retained_abort: bool,
    cross_authority_probe: bool,
    renewed_probe: usize,
    restart_after_prefix: bool,
) {
    run_coupled_owner_graph_with_revoker_policy(
        retained_abort,
        cross_authority_probe,
        renewed_probe,
        restart_after_prefix,
        false,
    );
}
#[test]
fn retained_prepare_expired_enrollment_keeps_authenticated_cleanup_only() {
    run_coupled_owner_graph_with_credential_expiry(false, true, 0, false, false, true);
    run_coupled_owner_graph_with_credential_expiry(false, true, 32, false, false, true);
}

fn run_coupled_owner_graph_with_revoker_policy(
    retained_abort: bool,
    cross_authority_probe: bool,
    renewed_probe: usize,
    restart_after_prefix: bool,
    distinct_revokers: bool,
) {
    run_coupled_owner_graph_with_credential_expiry(
        retained_abort,
        cross_authority_probe,
        renewed_probe,
        restart_after_prefix,
        distinct_revokers,
        false,
    );
}
fn run_coupled_owner_graph_with_credential_expiry(
    retained_abort: bool,
    cross_authority_probe: bool,
    renewed_probe: usize,
    restart_after_prefix: bool,
    distinct_revokers: bool,
    expire_signer_before_cleanup: bool,
) {
    run_coupled_owner_graph_with_root_horizon(
        retained_abort,
        cross_authority_probe,
        renewed_probe,
        restart_after_prefix,
        distinct_revokers,
        expire_signer_before_cleanup,
        300_000,
    );
}

#[test]
fn finite_thirty_minute_setup_roots_complete_real_first_and_later_coupled_renewals() {
    run_coupled_owner_graph_with_root_horizon(false, true, 3, false, false, false, 1_800_000);
}

fn run_coupled_owner_graph_with_root_horizon(
    retained_abort: bool,
    cross_authority_probe: bool,
    renewed_probe: usize,
    restart_after_prefix: bool,
    distinct_revokers: bool,
    expire_signer_before_cleanup: bool,
    root_horizon_ms: u64,
) {
    run_coupled_owner_graph_with_continuation(
        retained_abort,
        cross_authority_probe,
        renewed_probe,
        restart_after_prefix,
        distinct_revokers,
        expire_signer_before_cleanup,
        root_horizon_ms,
        false,
    );
}

#[test]
fn ongoing_actual_67_coupled_cycles_checkpoint_without_media_restart() {
    run_coupled_owner_graph_with_continuation(false, false, 0, false, false, false, 300_000, true);
}

#[test]
fn ongoing_actual_67_renewals_preserve_signed_original_cleanup_ancestry() {
    run_coupled_owner_graph_with_continuation(false, true, 67, false, false, false, 300_000, true);
}

fn run_coupled_owner_graph_with_continuation(
    retained_abort: bool,
    cross_authority_probe: bool,
    renewed_probe: usize,
    restart_after_prefix: bool,
    distinct_revokers: bool,
    expire_signer_before_cleanup: bool,
    root_horizon_ms: u64,
    ongoing: bool,
) {
    run_coupled_owner_graph_with_history_mode(
        retained_abort,
        cross_authority_probe,
        renewed_probe,
        restart_after_prefix,
        distinct_revokers,
        expire_signer_before_cleanup,
        root_horizon_ms,
        ongoing,
        false,
    );
}

#[test]
fn ongoing_v2_actual_103_coupled_cycles_across_three_renewed_media_lifetimes() {
    run_coupled_owner_graph_with_history_mode(
        false, false, 0, false, false, false, 300_000, true, true,
    );
}

// Keep mutually exclusive host-fixture stages in separate frames. Production
// owner calls and all assertions stay unchanged; finite controls must retain
// the ordinary Rust test-thread stack contract.
#[inline(never)]
fn run_coupled_fixture_stage(stage: impl FnOnce()) {
    stage();
}

fn run_coupled_owner_graph_with_history_mode(
    retained_abort: bool,
    cross_authority_probe: bool,
    renewed_probe: usize,
    restart_after_prefix: bool,
    distinct_revokers: bool,
    expire_signer_before_cleanup: bool,
    root_horizon_ms: u64,
    ongoing: bool,
    generic_history: bool,
) {
    struct FailStopOnce {
        inner: DeterministicAndroidMediaOwnerExecutor,
        failed: bool,
        fail_per_lifetime: bool,
        retained_abort: bool,
        start_failed: bool,
        abort_failed: bool,
    }
    impl AndroidMediaOwnerExecutor for FailStopOnce {
        fn executor_generation(&self) -> u64 {
            self.inner.executor_generation()
        }
        fn execute(
            &mut self,
            ticket: &AndroidMediaExecutionTicket,
            mode: AndroidMediaExecutionMode,
        ) -> Result<AndroidMediaOwnerReadback, String> {
            if self.retained_abort
                && ticket.action_id.ends_with(".abort")
                && ticket.sequence == 2
                && !self.abort_failed
            {
                self.abort_failed = true;
                return Err("actual uncertain reverse owner readback".to_owned());
            }
            if self.fail_per_lifetime
                && ticket.operation == MediaStreamPlatformOperation::Start
                && ticket.sequence == 1
            {
                self.failed = false;
            }
            let readback = self.inner.execute(ticket, mode)?;
            if self.retained_abort
                && ticket.operation == MediaStreamPlatformOperation::Start
                && ticket.sequence == 4
                && !self.start_failed
            {
                self.start_failed = true;
                return Err("actual failed Start after owner side effect".to_owned());
            }

            if !self.retained_abort
                && ticket.operation == MediaStreamPlatformOperation::Stop
                && ticket.sequence == 3
                && mode == AndroidMediaExecutionMode::Execute
                && !self.failed
            {
                self.failed = true;
                return Err("actual owner side effect with uncertain Stop readback".to_owned());
            }
            Ok(readback)
        }
        fn verify(
            &self,
            ticket: &AndroidMediaExecutionTicket,
            readback: &AndroidMediaOwnerReadback,
        ) -> bool {
            self.inner.verify(ticket, readback)
        }
    }

    let mut runtime_config = config(
        QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
        vec![ManifoldBrokerFeature::MediaSession],
        "command.media.session.start",
        true,
    );
    runtime_config = runtime_config
        .with_embedded_duplex(QuestEmbeddedDuplexAuthorityConfig {
            fresh_setup_local_peer_id: None,
            fresh_setup_enrollment_selection: None,
            ongoing_media_policy: ongoing.then(|| if generic_history {
                rusty_manifold_peer_runtime_host::ManifoldOngoingMediaPolicy::active_checkpoint_v2()
            } else { rusty_manifold_peer_runtime_host::ManifoldOngoingMediaPolicy::active_checkpoint_v1() }),
            schema_id: crate::QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA.to_owned(),
            runtime_host_id: id("host.quest-a.media-runtime"),
            trusted_operator_ids: vec![id("operator.quest.peer-enrollment")],
            trusted_key_fingerprints: vec![
                id("fingerprint.peer.quest-a"),
                id("fingerprint.peer.quest-b"),
            ],
            trusted_adapter_ids: vec![id("adapter.quest.embedded-duplex")],
            trusted_media_revoker_ids: vec![id(if distinct_revokers {
                "operator.quest.media-revoker.a"
            } else {
                "operator.quest.media-revoker"
            })],
        })
        .expect("duplex config");
    let mut client: QuestBrokerClientLockSpec =
        serde_json::from_str(&runtime_config.packaged_authority.client_locks[0].client_lock_json)
            .unwrap();
    client
        .capabilities
        .push(id("capability.manifold.control_lease.renew"));
    client.capabilities.sort();
    let raw = serde_json::to_string(&client).unwrap();
    let digest = packaged_json_sha256(&raw);
    runtime_config.packaged_authority.client_locks[0].client_lock_json = raw;
    runtime_config.packaged_authority.client_locks[0].client_lock_sha256 = digest.clone();
    for grant in &mut runtime_config.admission.snapshot.grants {
        grant.client_lock_fingerprint = format!("sha256:{digest}");
        grant.capabilities = derive_grant_capabilities(&runtime_config.product_lock, &client);
        grant.expires_at_ms = 301000;
    }
    runtime_config.admission.snapshot.max_token_ttl_ms = 120000;
    for lease in &mut runtime_config.initial_leases {
        lease.expires_at_ms = 301000;
    }
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
    if root_horizon_ms != 300_000 {
        let grant = &mut runtime_config.admission.snapshot.grants[0];
        grant.expires_at_ms = 1000 + root_horizon_ms;
        runtime_config.admission.snapshot.root_renewal_policies = vec![
            rusty_manifold_admission::ManifoldAdmissionRootRenewalPolicy {
                grant_id: grant.grant_id.clone(),
                horizon_ms: root_horizon_ms,
            },
        ];
        for lease in &mut runtime_config.initial_leases {
            if lease.holder_id == grant.identity.client_id
                && lease.scope.as_str() == "lease.media.session"
            {
                lease.expires_at_ms = 1000 + root_horizon_ms;
            }
        }
    }
    let mut peer_b_config = runtime_config.clone();
    peer_b_config
        .embedded_duplex
        .as_mut()
        .expect("duplex config")
        .runtime_host_id = id("host.quest-b.media-runtime");
    if distinct_revokers {
        peer_b_config
            .embedded_duplex
            .as_mut()
            .unwrap()
            .trusted_media_revoker_ids = vec![id("operator.quest.media-revoker.b")];
    }
    if cross_authority_probe {
        // Independent product-owned admission grants, without mutating runtime state.
        let encoded = serde_json::to_string(&peer_b_config).unwrap();
        peer_b_config =
            serde_json::from_str(&encoded.replace("grant.quest.runtime", "grant.quest.peer-b"))
                .unwrap();
    }
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
    let request_a = renewal_reciprocal_request(
        &authority_a,
        "request.reciprocal.host-a",
        &alpha,
        &beta,
        1300,
        240000,
        1,
    );
    let request_b = renewal_reciprocal_request(
        &authority_b,
        "request.reciprocal.host-b",
        &alpha,
        &beta,
        1300,
        240000,
        5,
    );
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
        proposal_id: authority_a
            .current_runtime_history_request_id(&id(&format!("proposal.session.{suffix}")))
            .unwrap(),
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

    let c = caller();
    let mut provider_a = QuestBrokerRuntimeProvider {
        runtime: Some(runtime),
        ..Default::default()
    };
    let mut provider_b = QuestBrokerRuntimeProvider {
        runtime: Some(peer_b),
        ..Default::default()
    };
    for (index, provider) in [&mut provider_a, &mut provider_b].into_iter().enumerate() {
        let accepted = provider
            .apply_concurrent_peer_command(
                QuestConcurrentPeerCommand::Start,
                c.sending_uid,
                &c.package_name,
                &c.signing_certificate_sha256,
                4000,
                &"81".repeat(32),
            )
            .unwrap();
        assert!(accepted.mutation.accepted);
        provider
            .issue_concurrent_peer_route(&id("session.peer.quest-a-b"), 4050, &"82".repeat(32))
            .expect("initial actual concurrent route");
        if index == 0 {
            provider
                .install_media_owner_executor(Box::new(
                    DeterministicAndroidMediaOwnerExecutor::new(9).unwrap(),
                ))
                .unwrap();
        } else {
            provider
                .install_media_owner_executor(Box::new(FailStopOnce {
                    inner: DeterministicAndroidMediaOwnerExecutor::new(9).unwrap(),
                    failed: false,
                    fail_per_lifetime: generic_history,
                    retained_abort,
                    start_failed: false,
                    abort_failed: false,
                }))
                .unwrap();
        }
        let completed = provider
            .runtime
            .as_mut()
            .unwrap()
            .complete_media_session_action(
                &QuestBrokerMediaCompletionRequest {
                    client_id: identity().client_id,
                },
                4100,
                provider.media_owner_executor.as_mut().unwrap().as_mut(),
                &mut provider.next_media_execution_nonce,
            );
        if retained_abort && index == 1 {
            assert!(
                matches!(
                    completed,
                    Err(QuestBrokerRuntimeError::MediaStartAbortFailed { .. })
                ),
                "actual Start result: {completed:?}"
            );
        } else {
            completed.unwrap();
        }
    }
    let capture_original = |provider: &QuestBrokerRuntimeProvider,
                            authority: &QuestEmbeddedDuplexAuthority,
                            local: &str,
                            remote: &str,
                            observed: u64| {
        let grant = provider
            .runtime
            .as_ref()
            .unwrap()
            .peer_runtime_host
            .as_ref()
            .unwrap()
            .read()
            .unwrap()
            .snapshot()
            .pair_media_routes
            .routes
            .iter()
            .find(|route| {
                *route.lifecycle_status()
                    == rusty_manifold_peer::ManifoldPairMediaRouteLifecycleStatus::Current
            })
            .unwrap()
            .grant_id()
            .clone();
        let projection = authority
            .current_owner_projection(&grant, &id(local), &id(remote), observed)
            .unwrap();
        let recovery = provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id]
            .recovery_snapshot()
            .unwrap();
        let action = recovery.active_start_action.unwrap();
        let ticket = rusty_quest_media_stream_android::execution_ticket(
            &action,
            &action.owner_actions[0],
            1,
            9,
            format!("fixture.retained.{local}"),
        )
        .unwrap();
        (projection, ticket)
    };
    if cross_authority_probe {
        return run_coupled_fixture_stage(|| {
        // Two independently constructed production stores, signed by actual keys.
        let mut originals = Vec::new();
        for (provider, authority, local, remote) in [
            (&provider_a, &authority_a, "peer.quest-a", "peer.quest-b"),
            (&provider_b, &authority_b, "peer.quest-b", "peer.quest-a"),
        ] {
            originals.push(capture_original(provider, authority, local, remote, 4200));
        }
        let enrollment_witnesses = originals
            .iter()
            .enumerate()
            .map(|(index, original)| {
                let (receiver, key_id, signer) = if index == 0 {
                    (&authority_b, "key.peer.quest-a.1", &alpha)
                } else {
                    (&authority_a, "key.peer.quest-b.1", &beta)
                };
                receiver
                    .capture_retained_original_enrollment(
                        &original.0,
                        &original.1,
                        key_id,
                        &signer.verifying_key().to_bytes(),
                        4200,
                    )
                    .unwrap()
            })
            .collect::<Vec<_>>();
        let initial_originals = originals.clone();
        if renewed_probe > 0 {
            let mut current_a = topology_a.clone();
            let mut current_b = topology_b.clone();
            for round in 0..renewed_probe {
                let now = 64_000 + (round / 2) as u64 * 120_000 + (round % 2) as u64;
                for (index, authority, current) in [
                    (0, &authority_a, &mut current_a),
                    (1, &authority_b, &mut current_b),
                ] {
                    let first = renewal_reciprocal_request(
                        authority,
                        &format!("request.cleanup-renew.r{round}.h{index}.credentials"),
                        &alpha,
                        &beta,
                        now,
                        (now + 20_000).min(current.expires_at_ms),
                        10 + round as u64 * 2,
                    );
                    let proof = authority.apply_common_lan_reciprocal(&first, now).unwrap();
                    assert!(
                        proof.accepted,
                        "round{round} peer{index} proof rejected: {:?}",
                        proof.rejection_reason
                    );
                    authority
                        .refresh_concurrent_pair_credentials(&proof, now)
                        .unwrap();
                    let fresh = renewal_reciprocal_request(
                        authority,
                        &format!("request.cleanup-renew.r{round}.h{index}.session"),
                        &alpha,
                        &beta,
                        now + 1,
                        now + 235_000,
                        11 + round as u64 * 2,
                    );
                    let signed = authority
                        .apply_common_lan_reciprocal(&fresh, now + 1)
                        .unwrap();
                    let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
                    serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
                    let mut next = proposal(&format!("cleanup-renew.r{round}.h{index}"));
                    next.expected_authority_revision = snapshot.peer_sessions.authority_revision;
                    next.expires_at_ms = now + 235_000;
                    let renewal = authority
                        .apply_common_lan_session_renewal(&next, &signed, now + 1)
                        .unwrap();
                    let provider = if index == 0 {
                        &mut provider_a
                    } else {
                        &mut provider_b
                    };
                    let mut clock = provider
                        .runtime
                        .as_ref()
                        .unwrap()
                        .runtime
                        .read()
                        .unwrap()
                        .control_lease_authority_snapshot()
                        .clock_snapshot
                        .clone();
                    clock.sequence += 1;
                    clock.wall_unix_ms = (now + 1) as i64;
                    clock.monotonic_elapsed_ns = 1_000_000_000 + (now + 1 - 1000) * 1_000_000;
                    let actual = provider
                        .renew_concurrent_peer_authority(
                            &clock,
                            &renewal,
                            now + 1,
                            &format!("{:02x}", 70 + index + round as usize * 2).repeat(32),
                        )
                        .unwrap();
                    assert!(actual.media_authority.applied && actual.outer_lifecycle.applied);
                    assert!(actual.admission_grant.application.applied);
                    if let Ok(directory) = std::env::var("RQ_FINITE_ROOT_RECEIPT_TEST_OUTPUT") {
                        std::fs::write(
                            std::path::Path::new(&directory)
                                .join(format!("renewal-{root_horizon_ms}-{index}-{round}.json")),
                            serde_json::to_vec(&actual).unwrap(),
                        )
                        .unwrap();
                    }
                    assert_eq!(
                        actual.admission_grant.binding.root_renewal_horizon_ms,
                        root_horizon_ms
                    );
                    for permit in [&actual.grant_token_issue, &actual.lease_token_issue] {
                        let token = permit.token.as_ref().unwrap();
                        assert!(token.expires_at_ms - token.issued_at_ms <= 5000);
                    }
                    assert!(actual.media_authority.expires_at_ms <= now + 1 + 180000);
                    assert!(
                        actual.media_authority.expires_at_ms <= actual.accepted_pair.expires_at_ms
                    );
                    assert!(
                        actual.media_authority.renewed_inner_lease.expires_at_ms
                            <= now + 1 + 235000
                    );

                    assert_eq!(
                        actual.admission_grant.binding.renewed_grant.expires_at_ms,
                        now + 1 + root_horizon_ms
                    );
                    assert_eq!(actual.outer_lifecycle.outcome,rusty_manifold_broker_adapter::ManifoldBrokerControlLeaseLifecycleOutcome::AcceptedAndAdopted);
                    assert_eq!(
                        actual.product_adoption.prior_physical_graph_sha256,
                        actual.product_adoption.physical_graph_sha256
                    );

                    if restart_after_prefix && round == 16 {
                        let before = authority.snapshot_json().unwrap();
                        let actual:rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot=serde_json::from_str(&before).unwrap();
                        authority.restore_snapshot_json(&before).unwrap();
                        let mut missing_release = actual.clone();
                        let old_binding = &actual.concurrent_media_renewals[0]
                            .prior_inner_lease
                            .derivative_binding;
                        missing_release
                            .broker_lease_admissions
                            .iter_mut()
                            .find(|a| &a.runtime_lease.derivative_binding == old_binding)
                            .unwrap()
                            .released_at_ms = None;
                        let old_decision =
                            &actual.concurrent_media_renewals[0].prior_media.decision_id;
                        let mut missing_end = actual.clone();
                        missing_end
                            .media_sessions
                            .sessions
                            .iter_mut()
                            .find(|m| &m.decision_id == old_decision)
                            .unwrap()
                            .ended_at_ms = None;
                        let mut live_old_graph = actual.clone();
                        live_old_graph.media_sessions.sessions.iter_mut().find(|m|&m.decision_id==old_decision).unwrap().lifecycle_status=rusty_manifold_media_session::ManifoldMediaSessionLifecycleStatus::Current;
                        let mut pending_cleanup = actual.clone();
                        let old_route =
                            &actual.concurrent_media_renewals[0].prior_routes[0].grant_id;
                        for route in &mut pending_cleanup.pair_media_routes.routes {
                            if let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(route)=route {
                                if &route.grant_id==old_route { route.cleanup_status=rusty_manifold_peer::ManifoldPairMediaRouteCleanupStatus::Pending; }
                            }
                        }
                        let mut altered_old_lease = actual.clone();
                        altered_old_lease.concurrent_media_renewals[0]
                            .renewed_inner_lease
                            .expires_at_ms -= 1;
                        let mut lost_middle = actual.clone();
                        lost_middle.concurrent_media_renewals.remove(1);
                        let mut reordered = actual.clone();
                        reordered.concurrent_media_renewals.swap(0, 16);
                        let mut old_graph_damage = actual.clone();
                        old_graph_damage.concurrent_media_renewals[15]
                            .renewed_media
                            .accepted_at_ms += 1;
                        for damaged in [
                            missing_release,
                            missing_end,
                            live_old_graph,
                            pending_cleanup,
                            altered_old_lease,
                            lost_middle,
                            reordered,
                            old_graph_damage,
                        ] {
                            assert!(
                                authority
                                    .restore_snapshot_json(
                                        &serde_json::to_string(&damaged).unwrap()
                                    )
                                    .is_err(),
                                "Damaged released/new-admitted graph history must fail closed"
                            );
                            assert_eq!(authority.snapshot_json().unwrap(), before);
                        }
                        println!(
                            "ACTUAL_RESTART_HISTORY_CONTROLS peer{index} count=8 rejected=true unchanged=true"
                        );
                    }
                    *current = renewal.topology;
                    if restart_after_prefix && round == 15 {
                        // Genuine supported Stop/route completion and a fresh Start,
                        // not a substituted digest, establish a later forward anchor.
                        let token =
                            |ordinal: usize| format!("{:064x}", 10_000 + index * 100 + ordinal);
                        assert!(
                            provider
                                .apply_concurrent_peer_command(
                                    QuestConcurrentPeerCommand::Stop,
                                    c.sending_uid,
                                    &c.package_name,
                                    &c.signing_certificate_sha256,
                                    now + 2,
                                    &token(1)
                                )
                                .unwrap()
                                .mutation
                                .accepted
                        );
                        provider
                            .terminate_concurrent_peer_route(false, now + 3, &token(2))
                            .unwrap();
                        let mut effect = None;
                        for attempt in 0..8 {
                            match provider.complete_media_stop_for_cleanup_typed(
                                &identity().client_id,
                                now + 4 + attempt,
                            ) {
                                Ok(value) => {
                                    effect = value.stop_effect_receipt;
                                    break;
                                }
                                Err(QuestBrokerRuntimeError::MediaStopAttemptRetained(_)) => {}
                                Err(error) => panic!("unexpected modeled Stop failure: {error:?}"),
                            }
                        }
                        provider
                            .complete_concurrent_peer_route_cleanup(
                                &effect.unwrap(),
                                now + 13,
                                &token(3),
                            )
                            .unwrap();
                        assert!(
                            provider
                                .apply_concurrent_peer_command(
                                    QuestConcurrentPeerCommand::Start,
                                    c.sending_uid,
                                    &c.package_name,
                                    &c.signing_certificate_sha256,
                                    now + 14,
                                    &token(4)
                                )
                                .unwrap()
                                .mutation
                                .accepted
                        );
                        provider
                            .issue_concurrent_peer_route(
                                &id("session.peer.quest-a-b"),
                                now + 15,
                                &token(5),
                            )
                            .unwrap();
                        provider
                            .runtime
                            .as_mut()
                            .unwrap()
                            .complete_media_session_action(
                                &QuestBrokerMediaCompletionRequest {
                                    client_id: identity().client_id,
                                },
                                now + 16,
                                provider.media_owner_executor.as_mut().unwrap().as_mut(),
                                &mut provider.next_media_execution_nonce,
                            )
                            .unwrap();
                        let (local, remote) = if index == 0 {
                            ("peer.quest-a", "peer.quest-b")
                        } else {
                            ("peer.quest-b", "peer.quest-a")
                        };
                        originals[index] =
                            capture_original(provider, authority, local, remote, now + 17);
                    }
                }
            }
        }
        let late = if renewed_probe > 0 {
            64_000
                + ((renewed_probe - 1) / 2) as u64 * 120_000
                + if expire_signer_before_cleanup {
                    350_000
                } else {
                    266_000
                }
        } else if expire_signer_before_cleanup {
            350_000
        } else {
            250_000
        };
        let mut requests = Vec::new();
        for (index, provider) in [&mut provider_a, &mut provider_b].into_iter().enumerate() {
            let mut clock = provider
                .runtime
                .as_ref()
                .unwrap()
                .runtime
                .read()
                .unwrap()
                .control_lease_authority_snapshot()
                .clock_snapshot
                .clone();
            clock.sequence += 1;
            clock.wall_unix_ms = late as i64;
            clock.monotonic_elapsed_ns = 1_000_000_000 + (late - 1000) * 1_000_000;
            let adoption = provider
                .adopt_concurrent_peer_revoker(
                    &clock,
                    late,
                    &format!("{:02x}", 180 + index).repeat(32),
                )
                .unwrap();
            provider
                .prepare_concurrent_peer_revoker_cleanup(
                    &identity().client_id,
                    &adoption,
                    late,
                    &format!("{:02x}", 182 + index).repeat(32),
                )
                .unwrap();
            provider
                .terminate_concurrent_peer_route(
                    true,
                    late + 10,
                    &format!("{:02x}", 184 + index).repeat(32),
                )
                .unwrap();
            let stop = provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id]
                .pending_action()
                .unwrap();
            let position = stop
                .owner_actions
                .iter()
                .position(|owner| {
                    owner.selection.owner_id == originals[index].1.owner_id
                        && owner.selection.resource_id == originals[index].1.resource_id
                })
                .unwrap();
            let stop_ticket = rusty_quest_media_stream_android::execution_ticket(
                stop,
                &stop.owner_actions[position],
                (position + 1) as u32,
                9,
                format!("fixture.cleanup.{index}"),
            )
            .unwrap();
            let grant = provider
                .runtime
                .as_ref()
                .unwrap()
                .peer_runtime_host
                .as_ref()
                .unwrap()
                .read()
                .unwrap()
                .snapshot()
                .pair_media_routes
                .routes
                .iter()
                .find(|r| {
                    *r.cleanup_status()
                        == rusty_manifold_peer::ManifoldPairMediaRouteCleanupStatus::Pending
                })
                .unwrap()
                .grant_id()
                .clone();
            requests.push((
                grant,
                adoption.revoker_id,
                adoption.lease.lease_id,
                stop_ticket,
            ));
        }
        assert_ne!(requests[0].0, requests[1].0);
        assert_ne!(requests[0].2, requests[1].2);
        let before_a = authority_a.snapshot_json().unwrap();
        let before_b = authority_b.snapshot_json().unwrap();
        for (receiver, local, sender, receiver_peer, sender_peer) in [
            (
                &authority_a,
                &requests[0],
                &requests[1],
                "peer.quest-a",
                "peer.quest-b",
            ),
            (
                &authority_b,
                &requests[1],
                &requests[0],
                "peer.quest-b",
                "peer.quest-a",
            ),
        ] {
            receiver
                .retained_cleanup_projection(
                    &local.0,
                    &local.1,
                    &local.2,
                    &id(receiver_peer),
                    &id(sender_peer),
                    late + 10,
                )
                .unwrap();
            assert_eq!(
                receiver
                    .retained_cleanup_projection(
                        &sender.0,
                        &sender.1,
                        &sender.2,
                        &id(sender_peer),
                        &id(receiver_peer),
                        late + 10,
                    )
                    .unwrap_err(),
                "cleanup route is absent"
            );
            assert_eq!(
                receiver
                    .retained_cleanup_projection(
                        &local.0,
                        &sender.1,
                        &sender.2,
                        &id(sender_peer),
                        &id(receiver_peer),
                        late + 10,
                    )
                    .unwrap_err(),
                "cleanup requester lease is absent"
            );
        }
        assert_eq!(before_a, authority_a.snapshot_json().unwrap());
        assert_eq!(before_b, authority_b.snapshot_json().unwrap());
        // Signature authentication of complete native Prepare is separate;
        // these are real receiving-store/current-enrollment authority joins.
        for (receiver, sender, request, original, local, remote, key_id, signer) in [
            (
                &authority_b,
                &authority_a,
                &requests[0],
                &originals[0],
                "peer.quest-a",
                "peer.quest-b",
                "key.peer.quest-a.1",
                &alpha,
            ),
            (
                &authority_a,
                &authority_b,
                &requests[1],
                &originals[1],
                "peer.quest-b",
                "peer.quest-a",
                "key.peer.quest-b.1",
                &beta,
            ),
        ] {
            let index = if local == "peer.quest-a" { 0 } else { 1 };
            let cleanup = sender
                .retained_cleanup_projection(
                    &request.0,
                    &request.1,
                    &request.2,
                    &id(local),
                    &id(remote),
                    late + 10,
                )
                .unwrap();
            let key = signer.verifying_key().to_bytes();
            if renewed_probe > 0 {
                let source_before = sender.snapshot_json().unwrap();
                let actual: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
                    serde_json::from_str(&source_before).unwrap();
                let mut unapplied = actual.clone();
                unapplied.concurrent_session_renewals[0].applied = false;
                let mut missing_request = actual.clone();
                let request_id = &actual.concurrent_session_renewals[0].request_id;
                missing_request
                    .peer_sessions
                    .applied_proposal_ids
                    .retain(|id| id != request_id);
                let mut missing_reciprocal = actual.clone();
                let reciprocal = rusty_manifold_peer::ManifoldReciprocalEd25519ReceiptV3::CommonLan(
                    actual.concurrent_session_renewals[0].reciprocal.clone(),
                );
                missing_reciprocal
                    .reciprocal_ed25519
                    .accepted_receipts
                    .retain(|receipt| receipt != &reciprocal);
                for damaged in [unapplied, missing_request, missing_reciprocal] {
                    assert!(
                        sender
                            .restore_snapshot_json(&serde_json::to_string(&damaged).unwrap())
                            .is_err(),
                        "Unaccepted canonical source history must reject before projection"
                    );
                    assert_eq!(sender.snapshot_json().unwrap(), source_before);
                }
            }
            let ancestry = sender.retained_cleanup_renewal_ancestry(&cleanup).unwrap();
            if distinct_revokers {
                assert_eq!(ancestry.len(), 32);
                // Each sender is locally authorized; that never adds its revoker to foreign trust.
                let receiver_before = receiver.snapshot_json().unwrap();
                assert_eq!(
                    receiver.validate_remote_retained_cleanup_projection_with_renewals(
                        &cleanup,
                        &original.0,
                        &original.1,
                        key_id,
                        &key,
                        late + 10,
                        &ancestry
                    ),
                    Err("remote cleanup retained source differs".to_owned())
                );
                assert_eq!(receiver.snapshot_json().unwrap(), receiver_before);
                continue;
            }
            if renewed_probe > 0 {
                if restart_after_prefix && renewed_probe == 16 {
                    assert_eq!(
                        cleanup.signed_topology_sha256,
                        original.0.signed_topology_sha256
                    );
                } else {
                    assert_ne!(
                        cleanup.signed_topology_sha256,
                        original.0.signed_topology_sha256
                    );
                }
                if ongoing {
                    assert!(ancestry.len() <= 17);
                    assert!(ancestry[0].continuation_checkpoint.is_some());
                } else {
                    assert_eq!(ancestry.len(), renewed_probe.min(64));
                }
                let encoded = serde_json::to_vec(&ancestry).unwrap();
                assert!(encoded.len() <= 65_536);
                println!(
                    "ACTUAL_COMPACT_ANCESTRY peer{index} hops={} bytes={}",
                    ancestry.len(),
                    encoded.len()
                );
                if restart_after_prefix && renewed_probe == 16 {
                    assert_ne!(
                        original.1.action_id, initial_originals[index].1.action_id,
                        "Latest anchor must come from a genuine new Start"
                    );
                }
                let legacy = receiver.validate_remote_retained_cleanup_projection(
                    &cleanup,
                    &original.0,
                    &original.1,
                    key_id,
                    &key,
                    late + 10,
                );
                if restart_after_prefix && renewed_probe == 16 {
                    assert!(
                        legacy.is_ok(),
                        "A latest Start with no later renewals preserves direct-digest cleanup"
                    );
                } else {
                    assert!(
                        legacy.is_err(),
                        "Legacy no-ancestry receiver must not accept renewed topology"
                    );
                }
                if restart_after_prefix && renewed_probe > 16 {
                    receiver
                        .validate_remote_retained_cleanup_projection_with_renewals(
                            &cleanup,
                            &original.0,
                            &original.1,
                            key_id,
                            &key,
                            late + 10,
                            &ancestry[16..],
                        )
                        .unwrap();
                }
                let mut damaged = Vec::new();
                if !restart_after_prefix || renewed_probe > 16 {
                    damaged.push(Vec::new());
                }
                if !restart_after_prefix || renewed_probe > 16 {
                    damaged.push(ancestry[if restart_after_prefix { 17 } else { 1 }..].to_vec());
                }
                damaged.push(ancestry[..1].to_vec());
                let mut reordered = ancestry.clone();
                reordered.reverse();
                damaged.push(reordered);
                let mut repeated = ancestry.clone();
                repeated.push(ancestry[0].clone());
                damaged.push(repeated);
                let mut unrelated = ancestry.clone();
                unrelated[0].session_id = "session.foreign".into();
                damaged.push(unrelated);
                let mut altered = ancestry.clone();
                altered[0].route_configuration_sha256 = format!("sha256:{}", "f".repeat(64));
                damaged.push(altered);
                let mut unapplied = ancestry.clone();
                unapplied[0].receipt_sha256 = "invalid".into();
                damaged.push(unapplied);
                let mut unaccepted = ancestry.clone();
                unaccepted[0].reciprocal_expires_at_ms = unaccepted[0].expires_at_ms - 1;
                damaged.push(unaccepted);
                let mut at_expiry = ancestry.clone();
                at_expiry[0].observed_at_ms = at_expiry[0].prior_expires_at_ms;
                damaged.push(at_expiry);
                let mut past_expiry = ancestry.clone();
                past_expiry[0].observed_at_ms = past_expiry[0].prior_expires_at_ms + 1;
                damaged.push(past_expiry);
                let mut duplicate_request = ancestry.clone();
                duplicate_request[1].request_id = duplicate_request[0].request_id.clone();
                damaged.push(duplicate_request);
                damaged.push(vec![ancestry[0].clone(); 65]);
                let mut oversized = ancestry.clone();
                oversized[0].receipt_sha256 = "f".repeat(65_536);
                damaged.push(oversized);
                for changed in damaged {
                    assert!(
                        receiver
                            .validate_remote_retained_cleanup_projection_with_renewals(
                                &cleanup,
                                &original.0,
                                &original.1,
                                key_id,
                                &key,
                                late + 10,
                                &changed,
                            )
                            .is_err(),
                        "Missing, reordered, repeated, unrelated or damaged ancestry admitted"
                    );
                }
                if let Ok(directory) = std::env::var("RQ_RETAINED_ANCESTRY_TEST_OUTPUT") {
                    std::fs::write(std::path::Path::new(&directory).join(format!("ancestry-{renewed_probe}-{restart_after_prefix}-{local}.json")),
                        serde_json::to_vec(&serde_json::json!({"cleanup":cleanup,"ticket":original.1,"ancestry":ancestry})).unwrap()).unwrap();
                }
            }
            if expire_signer_before_cleanup {
                let witness = &enrollment_witnesses[index];
                let receiver_before = receiver.snapshot_json().unwrap();
                let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
                    serde_json::from_str(&receiver_before).unwrap();
                let credential = snapshot
                    .enrollment
                    .credentials
                    .iter()
                    .find(|credential| {
                        credential.peer_id.as_str() == original.0.authority_peer_id
                            && credential.key_id.as_str() == key_id
                    })
                    .unwrap();
                assert!(credential.expires_at_ms < late + 10);
                if renewed_probe == 0 {
                    // Preserve the actual retained grant and shorten only the live
                    // credential window in an independently validated receiver store.
                    let mut shorter = snapshot.clone();
                    shorter
                        .enrollment
                        .credentials
                        .iter_mut()
                        .find(|credential| {
                            credential.peer_id.as_str() == original.0.authority_peer_id
                                && credential.key_id.as_str() == key_id
                        })
                        .unwrap()
                        .expires_at_ms = original.0.expires_at_ms - 1;
                    receiver
                        .restore_snapshot_json(&serde_json::to_string(&shorter).unwrap())
                        .expect("Live shorter credential snapshot remains a valid authority store");
                    struct AdmissionClock;
                    impl rusty_quest_media_stream_android::OwnerDispatchClock for AdmissionClock {
                        fn now_ms(&self) -> Result<u64, String> {
                            Ok(4200)
                        }
                    }
                    let request = rusty_quest_media_stream_android::OwnerDispatchRequest {
                        schema_id: rusty_quest_media_stream_android::OWNER_DISPATCH_REQUEST_SCHEMA
                            .into(),
                        dispatch_id: "fixture.longer-grant-live-credential".into(),
                        sequence: 1,
                        issued_at_ms: 4200,
                        target_peer_id: original.0.executor_peer_id.clone(),
                        authority: original.0.clone(),
                        ticket: original.1.clone(),
                        mode: rusty_quest_media_stream_android::AndroidMediaExecutionMode::Execute,
                        signer_key_id: key_id.into(),
                        signature_base64: String::new(),
                    };
                    let signing_bytes =
                        rusty_quest_media_stream_android::request_signing_bytes(&request).unwrap();
                    let signature = ed25519_dalek::Signer::sign(signer, &signing_bytes).to_bytes();
                    let verifier = crate::QuestOwnerDispatchAuthorityVerifier::new(
                        receiver.clone(),
                        std::sync::Arc::new(AdmissionClock),
                    );
                    rusty_quest_media_stream_android::OwnerDispatchAuthorityVerifier::verify_current(
                        &verifier, &request, &signing_bytes, &signature,
                    ).expect("Formerly valid signed Start must remain admitted");
                    let shorter_witness = receiver
                        .capture_retained_original_enrollment(
                            &original.0,
                            &original.1,
                            key_id,
                            &key,
                            4200,
                        )
                        .expect("Live shorter credential must not add a new Start refusal");
                    receiver
                        .validate_remote_retained_cleanup_projection_with_original_enrollment(
                            &cleanup,
                            &original.0,
                            &original.1,
                            key_id,
                            &key,
                            late + 10,
                            &ancestry,
                            Some(&shorter_witness),
                        )
                        .expect("Acceptance in both intervals suffices after expiry");
                    receiver.restore_snapshot_json(&receiver_before).unwrap();
                }
                let encoded_witness = serde_json::to_value(witness).unwrap();
                if renewed_probe > 0 {
                    assert!(
                        credential.expires_at_ms
                            > encoded_witness["expires_at_ms"].as_u64().unwrap()
                    );
                    assert_eq!(
                        credential.valid_from_ms,
                        encoded_witness["valid_from_ms"].as_u64().unwrap()
                    );
                }
                assert_eq!(
                    receiver.validate_remote_retained_cleanup_projection_with_renewals(
                        &cleanup,
                        &original.0,
                        &original.1,
                        key_id,
                        &key,
                        late + 10,
                        &ancestry,
                    ),
                    Err("cleanup signer is not currently enrolled".into()),
                    "Legacy witnessless records must not gain authority"
                );
                receiver
                    .validate_remote_retained_cleanup_projection_with_original_enrollment(
                        &cleanup,
                        &original.0,
                        &original.1,
                        key_id,
                        &key,
                        late + 10,
                        &ancestry,
                        Some(witness),
                    )
                    .unwrap();
                for (field, value) in [
                    ("accepted_at_ms", serde_json::json!(0)),
                    ("accepted_at_ms", serde_json::json!(late + 11)),
                    (
                        "accepted_at_ms",
                        serde_json::json!(original.0.expires_at_ms),
                    ),
                    ("schema", serde_json::json!("unknown")),
                    ("key_id", serde_json::json!("key.foreign")),
                    ("credential_id", serde_json::json!("credential.foreign")),
                    ("key_generation", serde_json::json!(999)),
                    (
                        "public_key_sha256",
                        serde_json::json!(format!("sha256:{}", "f".repeat(64))),
                    ),
                    (
                        "original_sha256",
                        serde_json::json!(format!("sha256:{}", "f".repeat(64))),
                    ),
                    (
                        "ticket_sha256",
                        serde_json::json!(format!("sha256:{}", "f".repeat(64))),
                    ),
                    ("valid_from_ms", serde_json::json!(4201)),
                ] {
                    let mut changed = encoded_witness.clone();
                    changed[field] = value;
                    let changed = serde_json::from_value(changed).unwrap();
                    assert!(
                        receiver
                            .validate_remote_retained_cleanup_projection_with_original_enrollment(
                                &cleanup,
                                &original.0,
                                &original.1,
                                key_id,
                                &key,
                                late + 10,
                                &ancestry,
                                Some(&changed),
                            )
                            .is_err(),
                        "Changed retained enrollment witness admitted: {field}"
                    );
                }
                for damage in ["revoked", "replaced_key", "after_original", "shortened"] {
                    let mut changed = snapshot.clone();
                    let credential = changed
                        .enrollment
                        .credentials
                        .iter_mut()
                        .find(|credential| {
                            credential.peer_id.as_str() == original.0.authority_peer_id
                                && credential.key_id.as_str() == key_id
                        })
                        .unwrap();
                    match damage {
                        "revoked" => {
                            credential.status =
                                rusty_manifold_peer::ManifoldPeerCredentialStatus::Revoked
                        }
                        "replaced_key" => credential.public_key_hex = "00".repeat(32),
                        "after_original" => credential.valid_from_ms = 4201,
                        "shortened" => credential.expires_at_ms = original.0.expires_at_ms - 1,
                        _ => unreachable!(),
                    }
                    if receiver
                        .restore_snapshot_json(&serde_json::to_string(&changed).unwrap())
                        .is_ok()
                    {
                        assert!(receiver.validate_remote_retained_cleanup_projection_with_original_enrollment(
                            &cleanup, &original.0, &original.1, key_id, &key, late + 10, &ancestry, Some(witness),
                        ).is_err(), "Changed current enrollment admitted: {damage}");
                        receiver.restore_snapshot_json(&receiver_before).unwrap();
                    }
                    assert_eq!(receiver.snapshot_json().unwrap(), receiver_before);
                }
                for field in [
                    "requester_id",
                    "target_client_id",
                    "peer_session_id",
                    "provider_epoch_id",
                ] {
                    let mut changed = serde_json::to_value(&cleanup).unwrap();
                    changed[field] = serde_json::json!("foreign.identity");
                    let changed = serde_json::from_value(changed).unwrap();
                    assert!(
                        receiver
                            .validate_remote_retained_cleanup_projection_with_original_enrollment(
                                &changed,
                                &original.0,
                                &original.1,
                                key_id,
                                &key,
                                late + 10,
                                &ancestry,
                                Some(witness),
                            )
                            .is_err(),
                        "Changed cleanup authority admitted: {field}"
                    );
                }
                let mut expired = cleanup.clone();
                expired.requester_expires_at_ms = late;
                assert!(receiver
                    .validate_remote_retained_cleanup_projection_with_original_enrollment(
                        &expired,
                        &original.0,
                        &original.1,
                        key_id,
                        &key,
                        late + 10,
                        &ancestry,
                        Some(witness),
                    )
                    .is_err());
                assert!(receiver
                    .validate_remote_retained_cleanup_projection_with_original_enrollment(
                        &cleanup,
                        &original.0,
                        &original.1,
                        key_id,
                        &[0; 32],
                        late + 10,
                        &ancestry,
                        Some(witness),
                    )
                    .is_err());
                continue;
            }
            receiver
                .validate_remote_retained_cleanup_projection_with_renewals(
                    &cleanup,
                    &original.0,
                    &original.1,
                    key_id,
                    &key,
                    late + 10,
                    &ancestry,
                )
                .unwrap();
            receiver
                .validate_remote_retained_cleanup_projection_with_renewals(
                    &cleanup,
                    &original.0,
                    &original.1,
                    key_id,
                    &key,
                    late + 11,
                    &ancestry,
                )
                .unwrap();
            for changed in [
                {
                    let mut v = cleanup.clone();
                    v.target_client_id = "client.foreign".into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.target_runtime_lease_id = "lease.foreign".into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.provider_epoch_id = "epoch.foreign".into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.authority_peer_id = remote.into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.executor_peer_id = local.into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.peer_session_id = "session.foreign".into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.signed_topology_sha256 = format!("sha256:{}", "f".repeat(64));
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.route_grant_id = "grant.foreign".into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.requester_id = "revoker.untrusted".into();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.requester_runtime_lease_id = v.target_runtime_lease_id.clone();
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.expires_at_ms = late;
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.requester_expires_at_ms = late;
                    v
                },
                {
                    let mut v = cleanup.clone();
                    v.schema_id = "unknown".into();
                    v
                },
            ] {
                assert!(receiver
                    .validate_remote_retained_cleanup_projection_with_renewals(
                        &changed,
                        &original.0,
                        &original.1,
                        key_id,
                        &key,
                        late + 10,
                        &ancestry,
                    )
                    .is_err());
            }
            assert!(receiver
                .validate_remote_retained_cleanup_projection_with_renewals(
                    &cleanup,
                    &original.0,
                    &original.1,
                    "key.foreign",
                    &key,
                    late + 10,
                    &ancestry,
                )
                .is_err());
            assert!(receiver
                .validate_remote_retained_cleanup_projection_with_renewals(
                    &cleanup,
                    &original.0,
                    &original.1,
                    key_id,
                    &[0; 32],
                    late + 10,
                    &ancestry,
                )
                .is_err());
            assert!(receiver
                .validate_remote_retained_cleanup_projection_with_renewals(
                    &cleanup,
                    &original.0,
                    &original.1,
                    key_id,
                    &key,
                    cleanup.expires_at_ms,
                    &ancestry,
                )
                .is_err());
        }
        assert_eq!(before_a, authority_a.snapshot_json().unwrap());
        assert_eq!(before_b, authority_b.snapshot_json().unwrap());
        return; // No owner Stop completion or physical cleanup is modeled by this authority test.
        });
    }
    if retained_abort {
        return run_coupled_fixture_stage(|| {
        let provider = &mut provider_b;
        let client = identity().client_id;
        let original = provider.runtime.as_ref().unwrap().media_sessions[&client]
            .pending_action()
            .unwrap()
            .clone();
        assert_eq!(
            provider.runtime.as_ref().unwrap().media_sessions[&client]
                .pending_abort_completed_count(),
            Some(1)
        );
        assert!(provider
            .apply_concurrent_peer_command(
                QuestConcurrentPeerCommand::Stop,
                c.sending_uid,
                &c.package_name,
                &c.signing_certificate_sha256,
                4200,
                &"91".repeat(32)
            )
            .is_err());
        let late = 400_000;
        let mut clock = provider
            .runtime
            .as_ref()
            .unwrap()
            .runtime
            .read()
            .unwrap()
            .control_lease_authority_snapshot()
            .clock_snapshot
            .clone();
        clock.sequence += 1;
        clock.wall_unix_ms = late as i64;
        clock.monotonic_elapsed_ns = 1_000_000_000 + (late - 1000) * 1_000_000;
        let adoption = provider
            .adopt_concurrent_peer_revoker(&clock, late, &"92".repeat(32))
            .unwrap();
        assert!(
            provider
                .prepare_concurrent_peer_revoker_cleanup(&client, &adoption, late, &"93".repeat(32))
                .is_err(),
            "pending Start cannot become Stop"
        );
        let reviewed = provider
            .review_concurrent_peer_revoker_cleanup(&client, &adoption, late, &"94".repeat(32))
            .unwrap();
        assert!(reviewed.termination.applied);
        assert!(
            provider
                .terminate_concurrent_peer_route(true, late + 10, &"95".repeat(32))
                .unwrap()
                .applied
        );
        let mut damaged = reviewed.clone();
        damaged.request.session_id = id("session.other");
        assert!(provider
            .resume_revoked_media_start_abort_for_cleanup(&client, &damaged, late + 20)
            .is_err());
        assert_eq!(
            provider.runtime.as_ref().unwrap().media_sessions[&client]
                .pending_abort_completed_count(),
            Some(1)
        );
        assert!(
            provider
                .resume_revoked_media_start_abort_for_cleanup(
                    &client,
                    &reviewed,
                    adoption.lease.expires_at_ms
                )
                .is_err(),
            "expired requester cannot resume"
        );
        let result = provider
            .resume_revoked_media_start_abort_for_cleanup(&client, &reviewed, late + 20)
            .unwrap()
            .unwrap();
        let receipt: serde_json::Value = serde_json::from_str(&result).unwrap();
        assert_eq!(receipt["rollback_receipts"].as_array().unwrap().len(), 4);
        let snapshot = provider.runtime.as_ref().unwrap().media_sessions[&client]
            .recovery_snapshot()
            .unwrap();
        assert_eq!(
            snapshot.schema_id,
            rusty_quest_media_stream::MEDIA_STREAM_PRODUCT_ABORT_RECOVERY_SCHEMA
        );
        assert!(snapshot.active_client_authority.is_none());
        assert!(snapshot.active_start_action.is_none());
        assert_eq!(
            snapshot.lifecycle.phase,
            rusty_quest_media_stream::MediaStreamRuntimePhase::Planned
        );
        let record = snapshot.completed_start_abort.as_ref().unwrap();
        assert_eq!(record.original_action, original);
        assert_eq!(record.rollback_receipts.len(), 4);
        assert!(
            provider
                .resume_revoked_media_start_abort_for_cleanup(&client, &reviewed, late + 21)
                .unwrap()
                .is_none(),
            "completed rollback must not replay"
        );
        let prepared = provider
            .prepare_concurrent_peer_revoker_cleanup(
                &client,
                &adoption,
                late + 25,
                &"96".repeat(32),
            )
            .unwrap();
        assert_eq!(prepared.action.client_authority, original.client_authority);
        assert_eq!(prepared.action.owner_actions.len(), 7);
        // The modeled registry's ordinary Stop denies owners with no prior
        // active handle. Actual retained compensation must prove absence;
        // neither the partial Start cursor nor a missing handle is proof.
        let mut completed = None;
        for attempt in 0..8 {
            match provider.complete_media_stop_for_cleanup_typed(&client, late + 30 + attempt) {
                Ok(value) => {
                    completed = Some(value);
                    break;
                }
                Err(QuestBrokerRuntimeError::MediaStopAttemptRetained(_)) => {}
                Err(error) => panic!("unexpected actual Stop error: {error:?}"),
            }
        }
        let completed = completed.expect("all seven actual Stop/compensation readbacks");
        let effect = completed.stop_effect_receipt.unwrap();
        assert_eq!(effect.owner_receipt_ids.len(), 7);
        assert_eq!(
            provider
                .complete_concurrent_peer_route_cleanup(&effect, late + 35, &"97".repeat(32))
                .unwrap()
                .len(),
            1
        );
        println!(
            "ACTUAL_091_FAILED_START_CLEANUP: original target retained; independent live Revoke; verified reverse cursor then seven owner Stop readbacks"
        );
        return;
        });
    }
    run_coupled_fixture_stage(|| {
    if ongoing && !generic_history {
        for provider in [&mut provider_a, &mut provider_b] {
            provider.enable_ongoing_media_continuation(
                rusty_manifold_peer_runtime_host::ManifoldOngoingMediaPolicy::active_checkpoint_v1()).unwrap();
        }
    }
    let mut ongoing_originals = Vec::new();
    if generic_history {
        for (provider, authority, receiver, local, remote, key_id, signer) in [
            (
                &provider_a,
                &authority_a,
                &authority_b,
                "peer.quest-a",
                "peer.quest-b",
                "key.peer.quest-a.1",
                &alpha,
            ),
            (
                &provider_b,
                &authority_b,
                &authority_a,
                "peer.quest-b",
                "peer.quest-a",
                "key.peer.quest-b.1",
                &beta,
            ),
        ] {
            let original = capture_original(provider, authority, local, remote, 4200);
            let witness = receiver
                .capture_retained_original_enrollment(
                    &original.0,
                    &original.1,
                    key_id,
                    &signer.verifying_key().to_bytes(),
                    4200,
                )
                .unwrap();
            ongoing_originals.push((original, witness));
        }
    }
    let mut active_a = provider_a.runtime.as_ref().unwrap().media_sessions[&identity().client_id]
        .recovery_snapshot()
        .unwrap();
    let mut active_b = provider_b.runtime.as_ref().unwrap().media_sessions[&identity().client_id]
        .recovery_snapshot()
        .unwrap();
    let mut current_a = topology_a.clone();
    let mut current_b = topology_b.clone();
    if generic_history {
        for (index, provider, active) in [(0, &provider_a, &active_a), (1, &provider_b, &active_b)] {
            assert!(provider.current_concurrent_ongoing_validation(4200).is_err());
            export_ongoing_owner_vector(&format!("host{index}-start0.json"), &serde_json::json!({
                "stage":"initial_start_before_first_renewal", "current_owner_validation_available":false,
                "active_product_recovery":active, "cumulative_renewal_count":0,
                "current_lifetime_renewal_count":0
            }));
        }
    }
    let mut receipts = [Vec::new(), Vec::new()];
    let mut stale_restore_seals = [None, None];
    let cycle_count = if generic_history {
        103
    } else if ongoing {
        67
    } else {
        22
    };
    for cycle in 1..=cycle_count {
        let now = 64000u64 + ((cycle - 1) / 2) as u64 * 120000 + ((cycle - 1) % 2) as u64;
        for (index, authority, current) in [
            (0, &authority_a, &mut current_a),
            (1, &authority_b, &mut current_b),
        ] {
            let first = renewal_reciprocal_request(
                authority,
                &format!("request.renew.c{cycle}.h{index}.credentials"),
                &alpha,
                &beta,
                now,
                (now + 20000).min(current.expires_at_ms),
                1 + (cycle * 10 + index * 4) as u64,
            );
            let proof = authority.apply_common_lan_reciprocal(&first, now).unwrap();
            assert!(proof.accepted);
            let refresh = authority
                .refresh_concurrent_pair_credentials(&proof, now)
                .unwrap();
            assert!(refresh.applied);
            assert_eq!(refresh.expires_at_ms, now + 300000);
            assert_eq!(refresh.key_ids, proof.signer_key_ids);
            assert_eq!(
                authority
                    .refresh_concurrent_pair_credentials(&proof, now)
                    .unwrap(),
                refresh
            );
            let fresh = renewal_reciprocal_request(
                authority,
                &format!("request.renew.c{cycle}.h{index}.session"),
                &alpha,
                &beta,
                now + 1,
                now + 235000,
                3 + (cycle * 10 + index * 4) as u64,
            );
            let signed = authority
                .apply_common_lan_reciprocal(&fresh, now + 1)
                .unwrap();
            assert!(
                signed.accepted,
                "actual rejection cycle{cycle} h{index}: {:?}",
                signed.rejection_reason
            );
            let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
                serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
            let mut next = proposal(&format!("renew.c{cycle}.h{index}"));
            next.proposal_id = authority
                .current_runtime_history_request_id(&id(&format!(
                    "proposal.session.renew.c{cycle}.h{index}"
                )))
                .unwrap();
            next.expected_authority_revision = snapshot.peer_sessions.authority_revision;
            next.expires_at_ms = now + 235000;
            let mut changed = next.clone();
            changed.transport.route_configuration_sha256 = format!("sha256:{}", "f".repeat(64));
            assert!(authority
                .apply_common_lan_session_renewal(&changed, &signed, now + 1)
                .is_err());
            let renewal = authority
                .apply_common_lan_session_renewal(&next, &signed, now + 1)
                .unwrap();
            assert!(renewal.applied);
            assert_eq!(renewal.decision_id, current.decision_id);
            assert_eq!(renewal.prior_expires_at_ms, current.expires_at_ms);
            assert!(renewal.expires_at_ms > current.expires_at_ms);
            assert_eq!(
                authority
                    .apply_common_lan_session_renewal(&next, &signed, now + 1)
                    .unwrap(),
                renewal
            );
            let provider = if index == 0 {
                &mut provider_a
            } else {
                &mut provider_b
            };
            let prior_clock = provider
                .runtime
                .as_ref()
                .unwrap()
                .runtime
                .read()
                .unwrap()
                .control_lease_authority_snapshot()
                .clock_snapshot
                .clone();
            let mut clock = prior_clock.clone();
            clock.sequence += 1;
            clock.wall_unix_ms = (now + 1) as i64;
            clock.monotonic_elapsed_ns = 1_000_000_000 + (now + 1 - 1000) * 1_000_000;
            let mut partial_revisions = None;
            if ongoing && cycle == 16 && !generic_history {
                provider
                    .runtime
                    .as_mut()
                    .unwrap()
                    .concurrent_renewal
                    .pause_after_product_adoption = true;
                assert!(provider
                    .renew_concurrent_peer_authority(
                        &clock,
                        &renewal,
                        now + 1,
                        &format!("{:02x}", cycle).repeat(32)
                    )
                    .is_err());
                assert!(provider
                    .runtime
                    .as_ref()
                    .unwrap()
                    .concurrent_renewal
                    .has_pending());
                let broker = provider.runtime.as_ref().unwrap().runtime.read().unwrap();
                partial_revisions = Some((
                    broker.admission_snapshot().authority_revision,
                    broker.host_snapshot().authority_revision,
                    broker.control_lease_authority_snapshot().authority_revision,
                ));
            }
            if generic_history && cycle == 32 {
                provider
                    .runtime
                    .as_mut()
                    .unwrap()
                    .concurrent_renewal
                    .pause_after_history_commit = true;
                assert!(provider
                    .renew_concurrent_peer_authority(
                        &clock,
                        &renewal,
                        now + 1,
                        &format!("{:02x}", cycle).repeat(32)
                    )
                    .is_err());
                assert!(provider
                    .runtime
                    .as_ref()
                    .unwrap()
                    .concurrent_renewal
                    .has_pending());
                let broker = provider.runtime.as_ref().unwrap().runtime.read().unwrap();
                partial_revisions = Some((
                    broker.admission_snapshot().authority_revision,
                    broker.host_snapshot().authority_revision,
                    broker.control_lease_authority_snapshot().authority_revision,
                ));
            }
            let actual = provider
                .renew_concurrent_peer_authority(
                    &clock,
                    &renewal,
                    now + 1,
                    &format!("{:02x}", cycle).repeat(32),
                )
                .expect("actual full coupled owners");
            if let Some(revisions) = partial_revisions {
                let broker = provider.runtime.as_ref().unwrap().runtime.read().unwrap();
                assert_eq!(
                    revisions,
                    (
                        broker.admission_snapshot().authority_revision,
                        broker.host_snapshot().authority_revision,
                        broker.control_lease_authority_snapshot().authority_revision
                    ),
                    "exact pending retry must not repeat accepted owner uses"
                );
            }
            receipts[index].push(serde_json::to_value(&actual).unwrap());
            assert!(actual.media_authority.applied);
            assert!(actual.outer_lifecycle.applied);
            assert!(actual.admission_grant.application.applied);
            let retry = provider
                .renew_concurrent_peer_authority(
                    &clock,
                    &renewal,
                    now + 1,
                    &format!("{:02x}", cycle).repeat(32),
                )
                .unwrap();
            assert_eq!(
                serde_json::to_value(&actual).unwrap(),
                serde_json::to_value(&retry).unwrap()
            );
            if ongoing && actual.continuation_checkpoint.is_some() {
                let previous: rusty_manifold_peer_runtime_host::ManifoldConcurrentPairSessionRenewalReceipt =
                    serde_json::from_value(receipts[index][cycle as usize - 2]["accepted_pair"].clone()).unwrap();
                let before = authority.snapshot_json().unwrap();
                assert!(
                    provider
                        .renew_concurrent_peer_authority(
                            &clock,
                            &previous,
                            now + 1,
                            &"fa".repeat(32)
                        )
                        .is_err(),
                    "evicted exact signed proof cannot reopen a completed use"
                );
                let mut relabelled = previous;
                relabelled.request_id = id(&format!("request.relabelled.c{cycle}.h{index}"));
                assert!(
                    provider
                        .renew_concurrent_peer_authority(
                            &clock,
                            &relabelled,
                            now + 1,
                            &"fb".repeat(32)
                        )
                        .is_err(),
                    "relabelled outer identity is not a retained accepted proof"
                );
                assert_eq!(authority.snapshot_json().unwrap(), before);
            }
            let recovery = provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id]
                .recovery_snapshot()
                .unwrap();
            let initial = if index == 0 { &active_a } else { &active_b };
            let mut physical = serde_json::to_value(&recovery).unwrap();
            let mut original = serde_json::to_value(initial).unwrap();
            physical.as_object_mut().unwrap().remove("accepted_subject");
            original.as_object_mut().unwrap().remove("accepted_subject");
            assert_eq!(
                physical, original,
                "physical resource graph must remain unchanged"
            );
            *current = renewal.topology.clone();
            let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
                serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
            let broker = provider.runtime.as_ref().unwrap().runtime.read().unwrap();
            if ongoing {
                let shared = provider
                    .runtime
                    .as_ref()
                    .unwrap()
                    .peer_runtime_host
                    .as_ref()
                    .unwrap();
                let peer = shared.read().unwrap();
                assert_eq!(
                    peer.cumulative_concurrent_media_renewals().unwrap(),
                    cycle as u64
                );
                assert!(snapshot.concurrent_media_renewals.len() <= 18);
                assert!(snapshot.concurrent_session_renewals.len() <= 18);
                assert!(snapshot.concurrent_credential_refreshes.len() <= 16);
                if cycle == 16 && !generic_history {
                    // A frozen previously accepted owner is a negative restore
                    // fixture only; it is never installed or used for effects.
                    let frozen: &'static rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost =
                        Box::leak(Box::new(peer.clone()));
                    stale_restore_seals[index] = Some((
                        peer.snapshot_json().unwrap(),
                        frozen.seal_ongoing_media_snapshot(&broker).unwrap(),
                    ));
                }
                if cycle == 17 && !generic_history {
                    let (old_bytes, old_seal) = stale_restore_seals[index].take().unwrap();
                    assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::restore_ongoing_media_snapshot(&old_bytes, &old_seal, &broker).is_err(), "a real old seal cannot roll back after live Broker authority advances");
                }
                let seal = peer.seal_ongoing_media_snapshot(&broker).unwrap();
                let bytes = serde_json::to_string(&snapshot).unwrap();
                if generic_history {
                    rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::verify_ongoing_media_snapshot(&bytes, &seal, &broker).unwrap();
                    assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::restore_ongoing_media_snapshot(&bytes, &seal, &broker).is_err(), "v2 actual writer cannot be forked");
                    let lifetime_count = if cycle <= 36 {
                        cycle
                    } else if cycle <= 70 {
                        cycle - 36
                    } else {
                        cycle - 70
                    };
                    assert_eq!(
                        peer.current_lifetime_concurrent_media_renewals().unwrap(),
                        lifetime_count as u64
                    );
                    assert_eq!(
                        actual.current_lifetime_renewal_count,
                        Some(lifetime_count as u64)
                    );
                    assert!(actual.runtime_history_checkpoint.is_some());
                    let owner_read = provider
                        .current_concurrent_ongoing_validation(now + 1)
                        .unwrap();
                    if [1, 16, 32, 36, 37, 53, 70, 71, 87, 103].contains(&cycle) {
                        export_ongoing_owner_vector(&format!("host{index}-renewal{cycle}.json"), &serde_json::json!({
                            "coupled_receipt":actual, "current_owner_validation":owner_read,
                            "active_product_recovery":recovery
                        }));
                    }
                    assert_eq!(owner_read.cumulative_renewal_count, cycle as u64);
                    assert_eq!(
                        owner_read.current_lifetime_renewal_count,
                        lifetime_count as u64
                    );
                    assert_eq!(owner_read.owner_request_id, actual.request_id);
                    assert_eq!(
                        owner_read.current_start_action_id,
                        recovery.active_start_action.as_ref().unwrap().action_id
                    );
                    assert_eq!(
                        owner_read.resource_identity_sha256,
                        actual.product_adoption.physical_graph_sha256
                    );
                    assert!(provider
                        .current_concurrent_ongoing_validation(actual.media_authority.expires_at_ms)
                        .is_err());
                    let epochs = actual.owner_history_epochs.as_ref().unwrap();
                    assert_eq!(
                        epochs.peer_request_epoch,
                        snapshot
                            .runtime_history_checkpoint
                            .as_ref()
                            .unwrap()
                            .request_epoch
                    );
                    assert_eq!(
                        epochs.media_command_request_epoch,
                        snapshot
                            .media_command_runtime
                            .history_checkpoint
                            .as_ref()
                            .unwrap()
                            .request_epoch
                    );
                    assert_eq!(
                        epochs.reciprocal_request_epoch,
                        snapshot
                            .reciprocal_ed25519
                            .history_checkpoint
                            .as_ref()
                            .unwrap()
                            .request_epoch
                    );
                    assert_eq!(
                        epochs.admission_request_epoch,
                        broker
                            .admission_snapshot()
                            .history_checkpoint
                            .as_ref()
                            .unwrap()
                            .request_epoch
                    );
                    let evidence = broker.evidence();
                    assert_eq!(
                        epochs.control_lease_request_epoch,
                        evidence
                            .control_lease_authority
                            .history_checkpoint
                            .as_ref()
                            .unwrap()
                            .request_epoch
                    );
                    assert_eq!(
                        epochs.broker_host_request_epoch,
                        evidence
                            .host_snapshot
                            .history_checkpoint
                            .as_ref()
                            .unwrap()
                            .request_epoch
                    );
                    assert!(snapshot.audit_events.len() <= 128);
                    assert!(snapshot.reciprocal_ed25519.accepted_receipts.len() <= 40);
                    assert!(snapshot.enrollment.applied_request_ids.len() <= 17);
                    assert!(snapshot.peer_sessions.applied_proposal_ids.len() <= 20);
                } else {
                    let restored = rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::restore_ongoing_media_snapshot(&bytes, &seal, &broker).unwrap();
                    assert_eq!(restored.snapshot(), &snapshot);
                }
                if let Some(checkpoint) = &snapshot.concurrent_media_checkpoint {
                    let mut wrong = snapshot.clone();
                    wrong
                        .concurrent_media_checkpoint
                        .as_mut()
                        .unwrap()
                        .cumulative_renewals = u64::MAX;
                    assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::verify_ongoing_media_snapshot(&serde_json::to_string(&wrong).unwrap(), &seal, &broker).is_err());
                    if !generic_history {
                        assert_eq!(
                            checkpoint.checkpoint_sequence * 16,
                            checkpoint.cumulative_renewals
                        );
                    }
                }
                assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(snapshot.clone(),&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).is_err());
                let mut altered = snapshot.clone();
                altered.media_sessions.sessions[0].expires_at_ms += 1;
                assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::verify_ongoing_media_snapshot(&serde_json::to_string(&altered).unwrap(), &seal, &broker).is_err());
                assert_eq!(actual.cumulative_renewal_count, Some(cycle as u64));
                if generic_history {
                    assert_eq!(
                        actual.continuation_checkpoint.is_some(),
                        matches!(cycle, 16 | 32 | 37 | 53 | 69 | 71 | 87 | 103)
                    );
                } else if cycle % 16 == 0 {
                    assert!(actual.continuation_checkpoint.is_some());
                }
            } else {
                let restored=rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(snapshot.clone(),&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker);
                assert!(restored.is_ok(), "actual restore failed: {:?}", restored);
            }
            for field in 0..3 {
                let mut wrong = snapshot.clone();
                match field {
                    0 => wrong.media_command_runtime.leases[0].expires_at_ms += 1,
                    1 => wrong.media_sessions.sessions[0].expires_at_ms += 1,
                    _ => {
                        if let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(
                            route,
                        ) = &mut wrong.pair_media_routes.routes[0]
                        {
                            route.expires_at_ms += 1;
                        }
                    }
                }
                assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(wrong,&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).is_err(),"unadopted current deadline rejects");
            }
            let mut malformed = snapshot.clone();
            malformed
                .concurrent_session_renewals
                .last_mut()
                .unwrap()
                .renewed_session
                .decision_id = id("decision.forged");
            assert!(rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost::from_snapshot_with_live_broker_runtime(malformed,&snapshot.trust_policy,&snapshot.provider_epoch_id,&broker).is_err());
        }
        if generic_history && matches!(cycle, 16 | 32 | 36 | 37 | 53 | 69 | 70 | 71 | 87 | 103) {
            println!("ACTUAL_V2_COUPLED_CYCLE {cycle}");
        }
        if generic_history && matches!(cycle, 36 | 70) {
            let now = 64000u64 + ((cycle - 1) / 2) as u64 * 120000 + ((cycle - 1) % 2) as u64;
            for (index, provider) in [&mut provider_a, &mut provider_b].into_iter().enumerate() {
                let token = |part: u64| {
                    format!(
                        "{:064x}",
                        500_000 + cycle as u64 * 100 + index as u64 * 10 + part
                    )
                };
                let prior = provider.runtime.as_ref().unwrap().media_sessions
                    [&identity().client_id]
                    .recovery_snapshot()
                    .unwrap();
                assert!(
                    provider
                        .apply_concurrent_peer_command(
                            QuestConcurrentPeerCommand::Stop,
                            c.sending_uid,
                            &c.package_name,
                            &c.signing_certificate_sha256,
                            now + 2,
                            &token(1)
                        )
                        .unwrap()
                        .mutation
                        .accepted
                );
                assert!(
                    provider
                        .terminate_concurrent_peer_route(false, now + 3, &token(2))
                        .unwrap()
                        .applied
                );
                let mut completed = None;
                for attempt in 0..8 {
                    match provider.complete_media_stop_for_cleanup_typed(
                        &identity().client_id,
                        now + 4 + attempt,
                    ) {
                        Ok(value) => {
                            completed = Some(value);
                            break;
                        }
                        Err(QuestBrokerRuntimeError::MediaStopAttemptRetained(_)) => {}
                        Err(error) => panic!("actual retained Stop failed: {error:?}"),
                    }
                }
                let effect = completed.unwrap().stop_effect_receipt.unwrap();
                assert_eq!(effect.owner_receipt_ids.len(), 7);
                assert_eq!(
                    provider
                        .complete_concurrent_peer_route_cleanup(&effect, now + 20, &token(3))
                        .unwrap()
                        .len(),
                    1
                );
                assert!(
                    provider
                        .apply_concurrent_peer_command(
                            QuestConcurrentPeerCommand::Start,
                            c.sending_uid,
                            &c.package_name,
                            &c.signing_certificate_sha256,
                            now + 21,
                            &token(4)
                        )
                        .unwrap()
                        .mutation
                        .accepted
                );
                provider
                    .issue_concurrent_peer_route(&id("session.peer.quest-a-b"), now + 22, &token(5))
                    .unwrap();
                provider
                    .runtime
                    .as_mut()
                    .unwrap()
                    .complete_media_session_action(
                        &QuestBrokerMediaCompletionRequest {
                            client_id: identity().client_id,
                        },
                        now + 23,
                        provider.media_owner_executor.as_mut().unwrap().as_mut(),
                        &mut provider.next_media_execution_nonce,
                    )
                    .unwrap();
                let active = provider.runtime.as_ref().unwrap().media_sessions
                    [&identity().client_id]
                    .recovery_snapshot()
                    .unwrap();
                assert_ne!(
                    active.active_start_action.as_ref().unwrap().action_id,
                    prior.active_start_action.as_ref().unwrap().action_id
                );
                let peer = provider
                    .runtime
                    .as_ref()
                    .unwrap()
                    .peer_runtime_host
                    .as_ref()
                    .unwrap()
                    .read()
                    .unwrap();
                assert_eq!(
                    peer.cumulative_concurrent_media_renewals().unwrap(),
                    cycle as u64,
                    "global accepted count never resets on actual new Start"
                );
                assert_eq!(
                    peer.current_lifetime_concurrent_media_renewals().unwrap(),
                    0,
                    "new actual lifetime has no renewals yet"
                );
                assert!(
                    provider
                        .current_concurrent_ongoing_validation(now + 24)
                        .is_err(),
                    "old lifetime receipt cannot validate new current Start"
                );
                drop(peer);
                export_ongoing_owner_vector(&format!("host{index}-newstart-after{cycle}.json"), &serde_json::json!({
                    "stage":"new_start_before_first_renewal", "current_owner_validation_available":false,
                    "active_product_recovery":active, "cumulative_renewal_count":cycle,
                    "current_lifetime_renewal_count":0
                }));
                let (authority, receiver, local, remote, key_id, signer) = if index == 0 {
                    (
                        &authority_a,
                        &authority_b,
                        "peer.quest-a",
                        "peer.quest-b",
                        "key.peer.quest-a.1",
                        &alpha,
                    )
                } else {
                    (
                        &authority_b,
                        &authority_a,
                        "peer.quest-b",
                        "peer.quest-a",
                        "key.peer.quest-b.1",
                        &beta,
                    )
                };
                let original = capture_original(provider, authority, local, remote, now + 24);
                let witness = receiver
                    .capture_retained_original_enrollment(
                        &original.0,
                        &original.1,
                        key_id,
                        &signer.verifying_key().to_bytes(),
                        now + 24,
                    )
                    .unwrap();
                ongoing_originals[index] = (original, witness);
                if index == 0 {
                    active_a = active;
                } else {
                    active_b = active;
                }
            }
        }
    }
    for (index, actual) in receipts.iter().enumerate() {
        let provider = if index == 0 { &provider_a } else { &provider_b };
        println!(
            "ACTUAL_FULL_BROKER_EVIDENCE_BYTES host{index} {}",
            provider.evidence_json().unwrap().len()
        );
        let bytes = serde_json::to_vec(actual).unwrap();
        println!("ACTUAL_22_RECEIPTS_BYTES host{index} {}", bytes.len());
        if let Ok(directory) = std::env::var("RQ_RENEWAL_RECEIPT_TEST_OUTPUT") {
            std::fs::write(
                std::path::Path::new(&directory).join(format!("actual-renewals-{index}.json")),
                &bytes,
            )
            .unwrap();
        }
    }
    for provider in [&mut provider_a] {
        let base = if generic_history {
            6_185_000
        } else if ongoing {
            4_025_000
        } else {
            1_265_000
        };
        assert!(
            provider
                .apply_concurrent_peer_command(
                    QuestConcurrentPeerCommand::Stop,
                    c.sending_uid,
                    &c.package_name,
                    &c.signing_certificate_sha256,
                    base,
                    &"d3".repeat(32)
                )
                .unwrap()
                .mutation
                .accepted
        );
        assert!(
            provider
                .terminate_concurrent_peer_route(false, base + 10, &"d4".repeat(32))
                .unwrap()
                .applied
        );
        let effect = provider
            .complete_media_stop_for_cleanup_typed(&identity().client_id, base + 100)
            .unwrap()
            .stop_effect_receipt
            .unwrap();
        assert_eq!(effect.owner_receipt_ids.len(), 7);
        let clean = provider
            .complete_concurrent_peer_route_cleanup(&effect, base + 200, &"d5".repeat(32))
            .unwrap();
        assert_eq!(clean.len(), 1);
        assert_eq!(
            clean,
            provider
                .complete_concurrent_peer_route_cleanup(&effect, base + 210, &"d6".repeat(32))
                .unwrap()
        );
        assert!(
            provider
                .apply_concurrent_peer_command(
                    QuestConcurrentPeerCommand::Start,
                    c.sending_uid,
                    &c.package_name,
                    &c.signing_certificate_sha256,
                    base + 300,
                    &"d7".repeat(32)
                )
                .unwrap()
                .mutation
                .accepted
        );
        provider
            .issue_concurrent_peer_route(
                &id("session.peer.quest-a-b"),
                base + 350,
                &"d8".repeat(32),
            )
            .unwrap();
        provider
            .runtime
            .as_mut()
            .unwrap()
            .complete_media_session_action(
                &QuestBrokerMediaCompletionRequest {
                    client_id: identity().client_id,
                },
                base + 400,
                provider.media_owner_executor.as_mut().unwrap().as_mut(),
                &mut provider.next_media_execution_nonce,
            )
            .unwrap();
        let mut clock = provider
            .runtime
            .as_ref()
            .unwrap()
            .runtime
            .read()
            .unwrap()
            .control_lease_authority_snapshot()
            .clock_snapshot
            .clone();
        clock.sequence += 1;
        clock.wall_unix_ms = (base + 500) as i64;
        clock.monotonic_elapsed_ns += 1_500_000_000;
        provider
            .adopt_concurrent_peer_revoker(&clock, base + 500, &"d9".repeat(32))
            .unwrap();
        assert!(
            provider
                .apply_concurrent_peer_command(
                    QuestConcurrentPeerCommand::Stop,
                    c.sending_uid,
                    &c.package_name,
                    &c.signing_certificate_sha256,
                    base + 600,
                    &"da".repeat(32)
                )
                .unwrap()
                .mutation
                .accepted
        );
        assert!(
            provider
                .terminate_concurrent_peer_route(true, base + 610, &"db".repeat(32))
                .unwrap()
                .applied
        );
        let effect = provider
            .complete_media_stop_for_cleanup_typed(&identity().client_id, base + 700)
            .unwrap()
            .stop_effect_receipt
            .unwrap();
        assert_eq!(effect.owner_receipt_ids.len(), 7);
        assert_eq!(
            provider
                .complete_concurrent_peer_route_cleanup(&effect, base + 800, &"dc".repeat(32))
                .unwrap()
                .len(),
            1
        );
    }

    // Host B retains the original physical graph through expiry; no restart or authority reset.
    let provider = &mut provider_b;
    let late = if generic_history {
        6_600_000
    } else if ongoing {
        4_400_000
    } else {
        1_600_000
    };
    assert!(
        provider
            .apply_concurrent_peer_command(
                QuestConcurrentPeerCommand::Stop,
                c.sending_uid,
                &c.package_name,
                &c.signing_certificate_sha256,
                late,
                &"e3".repeat(32)
            )
            .is_err(),
        "expired ordinary authority cannot Stop"
    );
    let mut clock = provider
        .runtime
        .as_ref()
        .unwrap()
        .runtime
        .read()
        .unwrap()
        .control_lease_authority_snapshot()
        .clock_snapshot
        .clone();
    clock.sequence += 1;
    clock.wall_unix_ms = late as i64;
    clock.monotonic_elapsed_ns = 1_000_000_000 + (late - 1000) * 1_000_000;
    let adoption = provider
        .adopt_concurrent_peer_revoker(&clock, late, &"e4".repeat(32))
        .expect("real generic old revoker expiry and fresh adoption");
    assert!(provider
        .concurrent_peer_requires_revoker_cleanup(&identity().client_id, late)
        .unwrap());
    let recovery = provider
        .prepare_concurrent_peer_revoker_cleanup(
            &identity().client_id,
            &adoption,
            late,
            &"e8".repeat(32),
        )
        .expect("actual fixed provider trusted cleanup");
    assert!(recovery.recovery.termination.applied);
    assert!(recovery.action.trusted_revoker_cleanup.is_some());
    assert!(
        provider
            .terminate_concurrent_peer_route(true, late + 10, &"e5".repeat(32))
            .expect("real late route Revoke")
            .applied
    );
    assert!(
        provider
            .complete_media_stop_for_cleanup_typed(&identity().client_id, late + 100)
            .is_err(),
        "physical uncertainty stays Pending"
    );
    let retained = provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id]
        .pending_action()
        .unwrap()
        .clone();
    let retry_at = late + 31_000;
    let mut clock = provider
        .runtime
        .as_ref()
        .unwrap()
        .runtime
        .read()
        .unwrap()
        .control_lease_authority_snapshot()
        .clock_snapshot
        .clone();
    clock.sequence = provider.concurrent_revoker_clock().unwrap().sequence + 1;
    clock.wall_unix_ms = retry_at as i64;
    clock.monotonic_elapsed_ns = 1_000_000_000 + (retry_at - 1000) * 1_000_000;
    let fresh = provider
        .adopt_concurrent_peer_revoker(&clock, retry_at, &"e7".repeat(32))
        .expect("actual generic revoker expiry/adoption and fresh cleanup lease");
    let grant = provider
        .runtime
        .as_ref()
        .unwrap()
        .peer_runtime_host
        .as_ref()
        .unwrap()
        .read()
        .unwrap()
        .snapshot()
        .pair_media_routes
        .routes
        .iter()
        .find(|route| {
            *route.cleanup_status()
                == rusty_manifold_peer::ManifoldPairMediaRouteCleanupStatus::Pending
        })
        .unwrap()
        .grant_id()
        .clone();
    let projection = authority_b
        .retained_cleanup_projection(
            &grant,
            &fresh.revoker_id,
            &fresh.lease.lease_id,
            &id("peer.quest-b"),
            &id("peer.quest-a"),
            retry_at,
        )
        .expect("actual late two-principal cleanup projection");
    let local = authority_b
        .retained_local_cleanup_projection(
            &grant,
            &fresh.revoker_id,
            &fresh.lease.lease_id,
            &id("peer.quest-b"),
            retry_at,
        )
        .expect("actual local two-principal cleanup projection");
    if generic_history {
        let ((original, ticket), witness) = &ongoing_originals[1];
        let ancestry = authority_b
            .retained_cleanup_renewal_ancestry(&projection)
            .unwrap();
        let certificate = ancestry[0].continuation_checkpoint.as_ref().unwrap();
        assert_eq!(
            certificate.schema_id,
            "rusty.quest.retained_cleanup.continuation_checkpoint.v2"
        );
        assert_eq!(certificate.cumulative_renewals, 103);
        assert_eq!(certificate.current_lifetime_renewal_count, Some(33));
        export_ongoing_owner_vector("host1-third-lifetime-signed-cleanup.json", &serde_json::json!({
            "cleanup_projection":projection, "original_prepare":original,
            "original_start_ticket":ticket, "original_enrollment":witness,
            "renewal_ancestry":ancestry
        }));
        authority_a
            .validate_remote_retained_cleanup_projection_with_original_enrollment(
                &projection,
                original,
                ticket,
                "key.peer.quest-b.1",
                &beta.verifying_key().to_bytes(),
                retry_at,
                &ancestry,
                Some(witness),
            )
            .unwrap();
        for wrong in [103, 0] {
            let mut altered = ancestry.clone();
            altered[0]
                .continuation_checkpoint
                .as_mut()
                .unwrap()
                .current_lifetime_renewal_count = Some(wrong);
            assert!(authority_a
                .validate_remote_retained_cleanup_projection_with_original_enrollment(
                    &projection,
                    original,
                    ticket,
                    "key.peer.quest-b.1",
                    &beta.verifying_key().to_bytes(),
                    retry_at,
                    &altered,
                    Some(witness)
                )
                .is_err());
        }
        let mut relabelled = ancestry.clone();
        relabelled[0]
            .continuation_checkpoint
            .as_mut()
            .unwrap()
            .schema_id = "rusty.quest.retained_cleanup.continuation_checkpoint.v1".into();
        assert!(authority_a
            .validate_remote_retained_cleanup_projection_with_original_enrollment(
                &projection,
                original,
                ticket,
                "key.peer.quest-b.1",
                &beta.verifying_key().to_bytes(),
                retry_at,
                &relabelled,
                Some(witness)
            )
            .is_err());
        println!("ACTUAL_V2_THIRD_LIFETIME_SIGNED_CLEANUP global103 lifetime33");
    }
    assert_eq!(local.authority_peer_id, local.executor_peer_id);
    assert_eq!(local.target_client_id, retained.client_authority.client_id);
    assert!(
        authority_b
            .retained_cleanup_projection(
                &grant,
                &fresh.revoker_id,
                &fresh.lease.lease_id,
                &id("peer.quest-b"),
                &id("peer.quest-b"),
                retry_at
            )
            .is_err(),
        "remote scope remains distinct"
    );
    assert!(projection.trusted_revoker);
    assert_eq!(
        projection.target_client_id,
        retained.client_authority.client_id
    );
    assert_eq!(
        projection.target_runtime_lease_id,
        retained.client_authority.lease_id
    );
    assert_ne!(projection.requester_id, projection.target_client_id);
    assert!(projection.historical_topology_expires_at_ms < retry_at);
    assert!(projection.requester_expires_at_ms > retry_at);
    assert_eq!(
        provider.runtime.as_ref().unwrap().media_sessions[&identity().client_id]
            .pending_action()
            .unwrap(),
        &retained,
        "physical retry preserves original action and authentic Revoke proof"
    );
    let completed = provider
        .complete_media_stop_for_cleanup_typed(&identity().client_id, retry_at + 100)
        .expect("seven actual late Stop effects");
    let effect = completed.stop_effect_receipt.unwrap();
    assert_eq!(effect.owner_receipt_ids.len(), 7);
    assert_eq!(
        provider
            .complete_concurrent_peer_route_cleanup(&effect, retry_at + 200, &"e6".repeat(32))
            .expect("late actual route cleanup and lease release")
            .len(),
        1
    );
    });
}
