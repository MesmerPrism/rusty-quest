// Included inside the actual no_media_close_tests owner fixture namespace.
// These controls exercise actual process and NativeCapability gates, not Java
// physical retirement or an invented successful constructor witness.
#[test]
fn partial_failure_retains_actual_arena_and_refuses_legacy_close_and_reinitialize() {
    let _serial = TEST_GATE.lock().unwrap();
    let fence = NativeFenceFixture::new();
    reset_with_staged_route();
    let result: Result<(), String> = initialize_with_capability(
        &fence.capability,
        SHA,
        || {
            partial_construction::begin(fence.capability.clone(), SHA)?;
            Err("controlled post-register failure".into())
        },
        |_, _: ()| panic!("failed constructor cannot publish"),
    );
    assert!(result.is_err());
    let actual = partial_construction::current().unwrap().unwrap();
    assert!(!process().lock().unwrap().initializing);
    assert!(process().lock().unwrap().host.is_none());
    assert_eq!(
        fence.close(SHA).unwrap_err(),
        "partial constructor requires exact retained whole retirement"
    );
    let retry: Result<(), String> = initialize_with_capability(
        &fence.capability,
        SHA,
        || panic!("must not rerun"),
        |_, _: ()| Ok(()),
    );
    assert!(retry.is_err());
    assert!(Arc::ptr_eq(
        &actual,
        &partial_construction::current().unwrap().unwrap()
    ));
    assert!(prepared_route().lock().unwrap().is_some());
}
#[test]
fn partial_final_capability_failure_preserves_same_arena() {
    let _serial = TEST_GATE.lock().unwrap();
    let fence = NativeFenceFixture::new();
    reset_with_staged_route();
    let original = fence.source.0.lock().unwrap().clone();
    let result: Result<(), String> = initialize_with_capability(
        &fence.capability,
        SHA,
        || {
            partial_construction::begin(fence.capability.clone(), SHA)?;
            *fence.source.0.lock().unwrap() = "changed-real-app-source".into();
            Ok(())
        },
        |_, _: ()| panic!("stale capability cannot publish"),
    );
    assert!(result.is_err());
    assert!(partial_construction::current().unwrap().is_some());
    assert!(!process().lock().unwrap().initializing);
    assert!(process().lock().unwrap().host.is_none());
    *fence.source.0.lock().unwrap() = original;
    assert!(fence.capability.require_live().is_ok());
    assert!(fence.close(SHA).is_err());
}
#[test]
fn partial_panic_releases_only_initialization_gate_never_arena() {
    let _serial = TEST_GATE.lock().unwrap();
    let fence = NativeFenceFixture::new();
    reset_with_staged_route();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _: Result<(), String> = initialize_with_capability(
            &fence.capability,
            SHA,
            || {
                partial_construction::begin(fence.capability.clone(), SHA).unwrap();
                panic!("controlled constructor unwind");
            },
            |_, _: ()| Ok(()),
        );
    }));
    assert!(result.is_err());
    assert!(!process().lock().unwrap().initializing);
    assert!(partial_construction::current().unwrap().is_some());
    assert!(fence.close(SHA).is_err());
}
