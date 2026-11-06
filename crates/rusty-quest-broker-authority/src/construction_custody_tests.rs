// Included inside the existing owning runtime::tests fixture namespace.
// Actual factory/M/Broker owners; no platform/device or fake owner receipts.
#[test]
fn construction_actual_peer_survives_later_clock_failure() {
    let config = config(
        QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
        vec![ManifoldBrokerFeature::MediaSession],
        "command.media.session.start",
        true,
    );
    assert!(
        config.admission.snapshot.root_renewal_policies.is_empty(),
        "fixture must reach the later real clock validator"
    );
    let mut held = QuestBrokerConstructionCustody::default();
    held.entered = true;
    let result = QuestBrokerAuthorityRuntime::from_config_retaining(
        config,
        &"12".repeat(32),
        -1,
        1_000_000_000,
        Some(&mut held),
    );
    assert!(matches!(
        result,
        Err(QuestBrokerRuntimeError::InvalidAuthorityClock)
    ));
    let value: serde_json::Value =
        serde_json::from_str(&held.retained_source_json().unwrap()).unwrap();
    assert_eq!(value["peer_owner_created"], true);
    assert_eq!(value["broker_owner_created"], false);
    assert!(value["peer_source"].is_object());
    assert!(value["broker_source"].is_null());
    let exact =
        serde_json::to_value(held.peer.as_ref().unwrap().read().unwrap().snapshot()).unwrap();
    assert_eq!(value["peer_source"], exact);
}
#[test]
fn construction_custody_is_same_actual_gates_not_a_snapshot_owner() {
    let config = config(
        QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
        vec![ManifoldBrokerFeature::MediaSession],
        "command.media.session.start",
        true,
    );
    let mut held = QuestBrokerConstructionCustody::default();
    held.entered = true;
    let runtime = QuestBrokerAuthorityRuntime::from_config_retaining(
        config,
        &"12".repeat(32),
        1_000,
        1_000_000_000,
        Some(&mut held),
    )
    .unwrap();
    assert!(Arc::ptr_eq(
        held.peer.as_ref().unwrap(),
        runtime.peer_runtime_host.as_ref().unwrap()
    ));
    assert!(Arc::ptr_eq(held.broker.as_ref().unwrap(), &runtime.runtime));
    let prior = held.retained_source_json().unwrap();
    drop(runtime);
    assert_eq!(held.retained_source_json().unwrap(), prior);
    assert_eq!(Arc::strong_count(held.peer.as_ref().unwrap()), 1);
}
#[test]
fn malformed_factory_source_does_not_fabricate_created_owners() {
    let mut config = config(
        QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
        vec![ManifoldBrokerFeature::MediaSession],
        "command.media.session.start",
        true,
    );
    config.schema_id = "invalid".into();
    let mut held = QuestBrokerConstructionCustody::default();
    held.entered = true;
    assert!(QuestBrokerAuthorityRuntime::from_config_retaining(
        config,
        &"12".repeat(32),
        1_000,
        1_000_000_000,
        Some(&mut held)
    )
    .is_err());
    let value: serde_json::Value =
        serde_json::from_str(&held.retained_source_json().unwrap()).unwrap();
    assert_eq!(value["factory_entered"], true);
    assert_eq!(value["peer_owner_created"], false);
    assert_eq!(value["broker_owner_created"], false);
}
#[test]
fn selected_fresh_actual_initializer_preserves_same_owner_and_root_registration() {
    let config = source_bound_roots_controls::root_config("peer.quest-a", 301_000, 301_000);
    let raw = serde_json::to_string(&config).unwrap();
    let sha = sha256_hex(raw.as_bytes());
    let mut provider = QuestBrokerRuntimeProvider::default();
    let mut held = QuestBrokerConstructionCustody::default();
    let result = provider
        .initialize_retaining_construction(
            &raw,
            &sha,
            &"12".repeat(32),
            1_000,
            1_000_000_000,
            &mut held,
        )
        .unwrap();
    assert_eq!(result.config_sha256, sha);
    let actual = provider.runtime.as_ref().unwrap();
    assert!(Arc::ptr_eq(
        held.peer.as_ref().unwrap(),
        actual.peer_runtime_host.as_ref().unwrap()
    ));
    assert!(Arc::ptr_eq(held.broker.as_ref().unwrap(), &actual.runtime));
    assert!(provider
        .embedded_duplex_authority()
        .unwrap()
        .selected_fresh_pair_setup_policy()
        .unwrap()
        .is_some());
    let prior = held.retained_source_json().unwrap();
    assert!(provider
        .initialize_retaining_construction(
            &raw,
            &sha,
            &"12".repeat(32),
            1_000,
            1_000_000_000,
            &mut held
        )
        .is_err());
    assert_eq!(held.retained_source_json().unwrap(), prior);
}
#[test]
fn actual_admission_constructor_failure_retains_control_adapter_and_peer() {
    let mut config = source_bound_roots_controls::root_config("peer.quest-a", 301_000, 301_000);
    config.admission.snapshot.schema_id = schema("rusty.manifold.invalid.admission.snapshot.v1");
    let mut held = QuestBrokerConstructionCustody::default();
    held.entered = true;
    let result = QuestBrokerAuthorityRuntime::from_config_retaining(
        config,
        &"12".repeat(32),
        1_000,
        1_000_000_000,
        Some(&mut held),
    );
    assert!(matches!(
        result,
        Err(QuestBrokerRuntimeError::RuntimeState(_))
    ));
    let raw = held.retained_source_json().unwrap();
    let value: serde_json::Value = serde_json::from_str(&raw).unwrap();
    assert_eq!(value["peer_owner_created"], true);
    let actual = value["unpublished_source"]["parts"]
        .as_object()
        .expect("real uncommitted parts retained");
    assert!(actual["control_lease_authority"].is_object());
    assert!(actual["host_snapshot"].is_object());
    assert_eq!(
        actual["admission_snapshot"]["$schema"],
        "rusty.manifold.invalid.admission.snapshot.v1"
    );
    assert!(!held.unpublished.as_mut().unwrap().try_construct().is_ok());
    assert_eq!(held.retained_source_json().unwrap(), raw);
}
