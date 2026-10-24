use super::*;
fn data(name: &str) -> PathBuf {
    let nonce = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let path = std::env::temp_dir().join(format!(
        "strength-phase-{name}-{}-{nonce}",
        std::process::id()
    ));
    fs::create_dir(&path).unwrap();
    path
}
fn command(id: &str, method: &str, args: Value) -> Value {
    json!({"schema":"rusty.quest.hotload_command.v1","request_id":id,"method":method,"arguments":args})
}
fn poll_now() {
    OWNER.with(|slot| {
        if let Some(Ok(owner)) = slot.borrow_mut().as_mut() {
            owner.next_poll = Instant::now();
        }
    });
    refresh(true);
}
#[test]
fn command_admission_is_closed_bounded_and_rejects_decoded_duplicate_keys() {
    let good = serde_json::to_vec(&command(
        "a-1",
        "native_activate",
        json!({"generation":"1","sha256":"a".repeat(64),"probe_result":"1048576005"}),
    ))
    .unwrap();
    assert!(Command::parse(&good, "a-1")
        .unwrap()
        .exact(&["generation", "sha256", "probe_result"])
        .is_ok());
    assert!(Command::parse(&good, "a-2").is_err());
    assert!(Command::parse(&[b' '; 4097], "a-1").is_err());
    for raw in [
        r#"{"schema":"rusty.quest.hotload_command.v1","request_id":"a-1","method":"native_status","arguments":{},"extra":"x"}"#,
        r#"{"schema":"rusty.quest.hotload_command.v1","request_id":"a-1","method":"native_activate","arguments":{"generation":1}}"#,
        r#"{"schema":"rusty.quest.hotload_command.v1","request_id":"a-1","method":"native_activate","arguments":{"generation":"1","generatio\u006e":"2"}}"#,
    ] {
        assert!(Command::parse(raw.as_bytes(), "a-1").is_err());
    }
    for raw in ["0", "01", "+1", "-1"] {
        assert!(canonical_generation(raw).is_err());
    }
    assert!(canonical_i64("+5").is_err());
    assert!(!valid_id("../escape"));
}
#[test]
fn actual_queue_activation_phase_status_once_and_repeated_setup_preserve_render_owner() {
    let root = data("queue");
    // No app setup and explicit selection alone must never create a host/session.
    refresh(true);
    assert!(strength_cycle_step::snapshot().is_none());
    configure(true, root.clone(), "a".repeat(32)).unwrap();
    refresh(false);
    assert!(!root.join(ROOT).exists());
    refresh(true);
    let descriptor: Value =
        serde_json::from_slice(&fs::read(root.join(ROOT).join("current-session.json")).unwrap())
            .unwrap();
    assert_eq!(descriptor.as_object().unwrap().len(), 6);
    assert_eq!(
        descriptor["schema"],
        "rusty.quest.hotload_current_session.v1"
    );
    assert_eq!(descriptor["host_kind"], "bilateral-strength-phase");
    assert_eq!(descriptor["pid"], std::process::id());
    let directory = root
        .join(ROOT)
        .join(descriptor["directory"].as_str().unwrap());
    let libraries: Vec<PathBuf> = std::env::split_paths(
        &std::env::var_os("RQ_STRENGTH_PHASE_TEST_LIBRARIES").expect("real fixture modules"),
    )
    .collect();
    fs::copy(&libraries[0], directory.join("generation-1.so")).unwrap();
    let mut permissions = fs::metadata(directory.join("generation-1.so"))
        .unwrap()
        .permissions();
    permissions.set_readonly(true);
    fs::set_permissions(directory.join("generation-1.so"), permissions).unwrap();
    let sha =
        crate::feedback::digest::sha256(&fs::read(directory.join("generation-1.so")).unwrap());
    atomic_json(&directory,"command-activate-1.json",&command("activate-1","native_activate",json!({"generation":"1","sha256":sha,"probe_result":crate::strength_cycle_adapter::PROBE_A.to_string()}))).unwrap();
    poll_now();
    let activated: Value =
        serde_json::from_slice(&fs::read(directory.join("observation-activate-1.json")).unwrap())
            .unwrap();
    assert_eq!(activated["status"], "ok");
    assert_eq!(activated["host"]["generation"], 1);
    assert_eq!(activated["phase"]["adopted_module_generation"], 0); // Activation alone is not effective phase adoption.
    assert_eq!(strength_cycle_step::step(0.0, 0.25), 0.25);
    atomic_json(
        &directory,
        "command-status-1.json",
        &command("status-1", "native_status", json!({})),
    )
    .unwrap();
    poll_now();
    let observed: Value =
        serde_json::from_slice(&fs::read(directory.join("observation-status-1.json")).unwrap())
            .unwrap();
    assert_eq!(observed["phase"]["source"], "module");
    assert_eq!(observed["phase"]["effective_phase"], 0.25);
    assert_eq!(observed["phase"]["adopted_module_generation"], 1);
    assert_eq!(observed["phase"]["phase_call_count"], 1);
    if let Some(output) = std::env::var_os("RQ_STRENGTH_PHASE_TEST_EVIDENCE") {
        let output = PathBuf::from(output);
        fs::create_dir(&output).unwrap();
        for (name, value) in [
            ("DESCRIPTOR.json", &descriptor),
            ("ACTIVATED.json", &activated),
            ("OBSERVED.json", &observed),
        ] {
            fs::write(output.join(name), serde_json::to_vec(value).unwrap()).unwrap();
        }
    }
    let allocation = strength_cycle_step::snapshot().unwrap().allocation_token;
    let calls = strength_cycle_step::phase_call_count();
    // Re-read once/consumed commands never invokes activation again.
    poll_now();
    assert_eq!(strength_cycle_step::snapshot().unwrap().attempts, 1);
    let other = data("recreated-activity");
    configure(false, other.clone(), "b".repeat(32)).unwrap();
    refresh(true);
    assert!(!other.join(ROOT).exists());
    assert_eq!(
        strength_cycle_step::snapshot().unwrap().allocation_token,
        allocation
    );
    assert_eq!(strength_cycle_step::phase_call_count(), calls);
    // Optional selection off keeps images/state but never dispatches their code.
    refresh(false);
    assert_eq!(strength_cycle_step::step(0.25, 0.25), 0.5);
    assert_eq!(
        strength_cycle_step::last_outcome().source,
        StepSource::NoHost
    );
    assert_eq!(strength_cycle_step::snapshot().unwrap().completed, 1);
    refresh(true);
    // A foreign render thread never takes/moves this thread's raw library handle.
    std::thread::spawn(|| {
        refresh(true);
        assert!(strength_cycle_step::snapshot().is_none());
        assert_eq!(strength_cycle_step::step(0.0, 0.25), 0.25);
        assert_eq!(
            strength_cycle_step::last_outcome().source,
            StepSource::NoHost
        );
    })
    .join()
    .unwrap();
    // Unsupported methods and malformed commands produce one honest error; no compute or lifecycle API is exposed.
    atomic_json(
        &directory,
        "command-unsafe-1.json",
        &command("unsafe-1", "native_compute", json!({"input":"4"})),
    )
    .unwrap();
    poll_now();
    let refused: Value =
        serde_json::from_slice(&fs::read(directory.join("observation-unsafe-1.json")).unwrap())
            .unwrap();
    assert_eq!(refused["status"], "error");
    assert_eq!(strength_cycle_step::snapshot().unwrap().completed, 1);
    fs::write(directory.join("command-bad-1.json"), b"{}").unwrap();
    poll_now();
    let first = fs::read(directory.join("observation-bad-1.json")).unwrap();
    poll_now();
    assert_eq!(
        first,
        fs::read(directory.join("observation-bad-1.json")).unwrap()
    );
    // A preexisting observation is conserved and prevents any associated command execution.
    fs::write(directory.join("observation-held-1.json"), b"held").unwrap();
    atomic_json(
        &directory,
        "command-held-1.json",
        &command(
            "held-1",
            "native_activate",
            json!({"generation":"2","sha256":"a".repeat(64),"probe_result":"0"}),
        ),
    )
    .unwrap();
    poll_now();
    assert_eq!(
        fs::read(directory.join("observation-held-1.json")).unwrap(),
        b"held"
    );
    assert_eq!(strength_cycle_step::snapshot().unwrap().attempts, 1);
    // All emitted top-level and phase fields have one closed actual source shape.
    assert_eq!(observed.as_object().unwrap().len(), 9);
    assert_eq!(observed["phase"].as_object().unwrap().len(), 4);
}
#[test]
fn nondebuggable_configuration_is_never_selected() {
    let config = Configuration {
        debuggable: false,
        data: data("nondebuggable"),
        session: "c".repeat(32),
    };
    std::thread::scope(|scope| {
        scope
            .spawn(|| {
                refresh_configuration(Some(&config), true);
                assert!(strength_cycle_step::snapshot().is_none());
                assert_eq!(strength_cycle_step::step(0.0, 0.25), 0.25);
                assert_eq!(
                    strength_cycle_step::last_outcome().source,
                    StepSource::NoHost
                );
                let mut debug = config.clone();
                debug.debuggable = true;
                refresh_configuration(Some(&debug), false);
                assert!(strength_cycle_step::snapshot().is_none());
                refresh_configuration(None, true);
                assert!(strength_cycle_step::snapshot().is_none());
            })
            .join()
            .unwrap();
    });
    assert!(!config.data.join(ROOT).exists());
}
