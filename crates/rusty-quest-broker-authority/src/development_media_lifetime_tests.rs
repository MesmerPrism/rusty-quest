// One selected-development consumer control over the real coupled owners.
// Seven Android resource callbacks are modeled; no native PreparedArena or
// camera, codec, transport, headset, or fresh preparation claim follows.
#[test]
fn selected_development_media_survives_finite_cap_then_stops_and_accepts_fresh_owner_start() {
    let horizon = rusty_manifold_admission::ManifoldDevelopmentLifetimePolicy::source_owned_v1()
        .delegated_authority_horizon_ms;
    assert_eq!(horizon, 30 * 24 * 60 * 60 * 1000);
    run_coupled_owner_graph_with_selected_lifetime(
        false, false, 0, false, false, false, horizon, false, false, true,
    );
}

fn selected_development_media_lifetime_lifecycle(
    mut provider_a: QuestBrokerRuntimeProvider,
    mut provider_b: QuestBrokerRuntimeProvider,
    authority_a: QuestEmbeddedDuplexAuthority,
    authority_b: QuestEmbeddedDuplexAuthority,
    parent_expires_at_ms: u64,
) {
    let caller = caller();
    let late = 130_000; // Actual observation beyond Start's former 124000 cap.
    for (index, (provider, authority, local, remote)) in [
        (
            &mut provider_a,
            &authority_a,
            "peer.quest-a",
            "peer.quest-b",
        ),
        (
            &mut provider_b,
            &authority_b,
            "peer.quest-b",
            "peer.quest-a",
        ),
    ]
    .into_iter()
    .enumerate()
    {
        let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
            serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
        let [derived] = snapshot.media_command_runtime.leases.as_slice() else {
            panic!("one actual derived media lease expected");
        };
        let [media] = snapshot.media_sessions.sessions.as_slice() else {
            panic!("one actual accepted media session expected");
        };
        eprintln!(
            "SELECTED_DEVELOPMENT_MEDIA peer={index} parent={} derived={} media={}",
            parent_expires_at_ms, derived.expires_at_ms, media.expires_at_ms,
        );
        assert_eq!(
            derived.expires_at_ms, parent_expires_at_ms,
            "selected development consumer must retain the bounded admitted lifetime",
        );
        assert_eq!(media.expires_at_ms, parent_expires_at_ms);
        let route = snapshot
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
        assert!(authority
            .current_owner_projection(&route, &id(local), &id(remote), late)
            .is_ok());

        // Expiry remains an actual boundary. A failed lookup cannot rewrite the
        // live route or obtain a new outer command from an expired parent.
        let before = authority.snapshot_json().unwrap();
        assert!(authority
            .current_owner_projection(&route, &id(local), &id(remote), parent_expires_at_ms)
            .is_err());
        assert!(provider
            .apply_concurrent_peer_command(
                QuestConcurrentPeerCommand::Start,
                caller.sending_uid,
                &caller.package_name,
                &caller.signing_certificate_sha256,
                parent_expires_at_ms,
                &"d1".repeat(32),
            )
            .is_err());
        assert_eq!(authority.snapshot_json().unwrap(), before);

        let entropy = |step| format!("{:064x}", 20_000 + index * 100 + step);
        assert!(
            provider
                .apply_concurrent_peer_command(
                    QuestConcurrentPeerCommand::Stop,
                    caller.sending_uid,
                    &caller.package_name,
                    &caller.signing_certificate_sha256,
                    late,
                    &entropy(1),
                )
                .unwrap()
                .mutation
                .accepted
        );
        provider
            .terminate_concurrent_peer_route(false, late + 1, &entropy(2))
            .unwrap();
        let completed = provider
            .complete_media_stop_for_cleanup_typed(&identity().client_id, late + 2)
            .unwrap();
        provider
            .complete_concurrent_peer_route_cleanup(
                &completed.stop_effect_receipt.unwrap(),
                late + 3,
                &entropy(3),
            )
            .unwrap();
        assert!(authority
            .current_owner_projection(&route, &id(local), &id(remote), late + 4)
            .is_err());

        // New Start and a new route are accepted by the actual owners after
        // terminal Stop. SDK preparation eligibility itself remains modeled.
        assert!(
            provider
                .apply_concurrent_peer_command(
                    QuestConcurrentPeerCommand::Start,
                    caller.sending_uid,
                    &caller.package_name,
                    &caller.signing_certificate_sha256,
                    late + 5,
                    &entropy(4),
                )
                .unwrap()
                .mutation
                .accepted
        );
        provider
            .issue_concurrent_peer_route(&id("session.peer.quest-a-b"), late + 6, &entropy(5))
            .unwrap();
        provider
            .runtime
            .as_mut()
            .unwrap()
            .complete_media_session_action(
                &QuestBrokerMediaCompletionRequest {
                    client_id: identity().client_id,
                },
                late + 7,
                provider.media_owner_executor.as_mut().unwrap().as_mut(),
                &mut provider.next_media_execution_nonce,
            )
            .unwrap();
        let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
            serde_json::from_str(&authority.snapshot_json().unwrap()).unwrap();
        let fresh_route = snapshot
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
        assert_ne!(fresh_route, route);
        assert!(authority
            .current_owner_projection(&fresh_route, &id(local), &id(remote), late + 8)
            .is_ok());
        let before = authority.snapshot_json().unwrap();
        assert!(provider
            .terminate_concurrent_peer_route(true, late + 9, &entropy(6))
            .is_err());
        assert_eq!(authority.snapshot_json().unwrap(), before);
        let clock = provider
            .read_concurrent_peer_clock(
                (late + 9) as i64,
                1_000_000_000 + (late + 9 - 1000) * 1_000_000,
                0,
            )
            .unwrap();
        provider
            .adopt_concurrent_peer_revoker(&clock, late + 9, &entropy(7))
            .unwrap();
        provider
            .terminate_concurrent_peer_route(true, late + 10, &entropy(8))
            .unwrap();
        assert!(authority
            .current_owner_projection(&fresh_route, &id(local), &id(remote), late + 11)
            .is_err());
    }
}
