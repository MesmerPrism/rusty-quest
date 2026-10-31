// Production worker/controller/writer tests. No renderer or browser drives the watchdog.
#[test]
fn polar_required_start_rejects_absent_acquisition() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("polar-absent");
    let runtime = runtime(&root);
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    runtime.apply_command_json(&start_command(&root, "polar-missing-start"));
    let status: Value = serde_json::from_str(&wait_for(
        &runtime,
        "\"last_operation_id\":\"polar-missing-start\"",
    ))
    .unwrap();
    runtime.shutdown_for_test().unwrap();
    assert_eq!(status["readback"]["last_operation_status"], "rejected");
    assert_eq!(status["readback"]["last_reason"], "polar-input-not-fresh");
    assert_eq!(status["readback"]["generation"], 0);
    assert_eq!(status["readback"]["polar_input"]["required"], true);
    assert_eq!(status["readback"]["polar_input"]["fresh"], false);
    if root.parent().unwrap().exists() {
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}

#[test]
fn polar_worker_detects_loss_without_frames_or_browser_and_never_resumes() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("polar-worker-loss");
    let runtime = runtime(&root);
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    polar_sample();
    runtime.apply_command_json(&start_command(&root, "polar-fresh-start"));
    wait_for(&runtime, "\"phase\":\"active\"");
    thread::sleep(Duration::from_millis(1150));
    let lost: Value = serde_json::from_str(&runtime.status_json()).unwrap();
    polar_sample();
    thread::sleep(Duration::from_millis(30));
    let recovered: Value = serde_json::from_str(&runtime.status_json()).unwrap();
    runtime.shutdown_for_test().unwrap();
    assert_eq!(lost["readback"]["control_state"], "paused");
    assert_eq!(lost["readback"]["polar_input"]["pause_latched"], true);
    assert_eq!(lost["readback"]["polar_input"]["fresh"], false);
    assert_eq!(recovered["readback"]["control_state"], "paused");
    assert_eq!(recovered["readback"]["polar_input"]["fresh"], true);
    assert_eq!(
        lost["readback"]["active_time_ms"],
        recovered["readback"]["active_time_ms"]
    );
    let directory = fs::read_dir(&root)
        .unwrap()
        .filter_map(Result::ok)
        .find(|entry| entry.path().join("events.jsonl").is_file())
        .unwrap()
        .path();
    let events = fs::read_to_string(directory.join("events.jsonl")).unwrap();
    // The ordinary durable Pause event must be emitted exactly once.
    assert_eq!(
        events.matches("\"event\":\"experiment-paused\"").count(),
        1,
        "{events}"
    );
    if root.parent().unwrap().exists() {
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}

fn polar_sample() {
    let host = crate::polar_composition_adapters::latest_polar_acc_after(None)
        .map_or(1, |sample| sample.host_time_ns.saturating_add(1));
    crate::polar_composition_adapters::submit_polar_acc_measurement(host, host, [0.0, 0.0, 1000.0]);
}

#[test]
fn polar_unready_request_queued_before_recovery_cannot_start_after_recovery() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("polar-delayed-start");
    let runtime = runtime(&root);
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    let (entered, entered_rx) = mpsc::sync_channel(1);
    let (resume, resume_rx) = mpsc::sync_channel(1);
    runtime
        .command_sender
        .send(WorkerMessage::PauseForTest {
            entered,
            resume: resume_rx,
        })
        .unwrap();
    entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    runtime.apply_command_json(&start_command(&root, "queued-without-input"));
    polar_sample();
    resume.send(()).unwrap();
    let status: Value = serde_json::from_str(&wait_for(
        &runtime,
        "\"last_operation_id\":\"queued-without-input\"",
    ))
    .unwrap();
    runtime.shutdown_for_test().unwrap();
    assert_eq!(status["readback"]["last_operation_status"], "rejected");
    assert_eq!(status["readback"]["generation"], 0);
    if root.parent().unwrap().exists() {
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}

fn polar_action(runtime: &ExperimentSessionRuntime, operation: &str, id: &str) -> Value {
    runtime.apply_command_json(&command(
        operation,
        id,
        1,
        runtime.current_elapsed_realtime_ns().unwrap() + 1_000,
    ));
    serde_json::from_str(&wait_for(
        runtime,
        &format!("\"last_operation_id\":\"{id}\""),
    ))
    .unwrap()
}

fn polar_present(runtime: &ExperimentSessionRuntime, id: &str) {
    static FRAME: AtomicU64 = AtomicU64::new(1);
    assert!(runtime.note_submitted_frame(1, FRAME.fetch_add(1, Ordering::Relaxed)));
    runtime.apply_command_json(
        &json!({"schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
        "operation":"presentation","operation_id":id,"expected_generation":1,
        "elapsed_realtime_ns":runtime.current_elapsed_realtime_ns().unwrap() + 1_000,
        "presentation":"immersive-active"})
        .to_string(),
    );
    wait_for(runtime, &format!("\"last_operation_id\":\"{id}\""));
}

fn polar_selection(source: &str) {
    let response: Value =
        serde_json::from_str(&crate::breath_composition_runtime::apply_command_json(
            &json!({"schema":crate::breath_composition_runtime::COMMAND_SCHEMA_ID,
            "operation":"select","source":source,"mapping":"state",
            "controller_projection":"dynamic-axis","polar_projection":"xz","inverted":false})
            .to_string(),
        ))
        .unwrap();
    assert_eq!(response["command_status"], "accepted", "{response}");
}

fn polar_operator_resume(runtime: &ExperimentSessionRuntime, id: &str) -> Value {
    let snapshot: Value = serde_json::from_str(&runtime.status_json()).unwrap();
    json!({"schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
        "operation":"operator-resume","operation_id":id,"expected_generation":1,
        "elapsed_realtime_ns":runtime.current_elapsed_realtime_ns().unwrap()+1_000,
        "operator_origin":"shell-experiment-operator-v1","expected_runtime_epoch":1,
        "expected_session_key":snapshot["readback"]["session_key"],"expected_condition":"condition-a",
        "expected_profile_sha256":"11".repeat(32),
        "expected_binding_sha256":serde_json::from_str::<Value>(&crate::breath_composition_runtime::status_json()).unwrap()["snapshot"]["activation_binding_sha256"]})
}

#[test]
fn polar_native_and_authenticated_operator_controls_share_freshness_and_manual_resume_gate() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("polar-explicit-controls");
    let runtime = runtime(&root);
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    runtime.apply_command_json(
        &start_command(&root, "polar-arm")
            .replace("\"operation\":\"start\"", "\"operation\":\"arm\""),
    );
    wait_for(&runtime, "\"phase\":\"active\"");
    polar_action(&runtime, "audio-prepared", "polar-audio");
    polar_present(&runtime, "polar-present");
    let denied = polar_action(&runtime, "official-start", "polar-start-denied");
    assert_eq!(denied["readback"]["last_reason"], "polar-input-not-fresh");
    polar_sample();
    let started = polar_action(&runtime, "official-start", "polar-direct-start");
    assert_eq!(started["readback"]["control_state"], "running");
    // Read-only ambiguous/poor classification cannot replace acquisition truth.
    runtime.observe_breath_monitor("polar-acc", "invalid", "unknown", None, 0.0, 1);
    thread::sleep(Duration::from_millis(20));
    assert_eq!(
        serde_json::from_str::<Value>(&runtime.status_json()).unwrap()["readback"]["control_state"],
        "running"
    );

    // Hold the real worker while its native acquisition receipt crosses the
    // deadline. Renderer heartbeat remains current, so only Polar owns Pause.
    let (entered, entered_rx) = mpsc::sync_channel(1);
    let (release, release_rx) = mpsc::sync_channel(1);
    runtime
        .command_sender
        .send(WorkerMessage::PauseForTest {
            entered,
            resume: release_rx,
        })
        .unwrap();
    entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    crate::polar_input_health::test_support::age_sample(Duration::from_millis(1250));
    let current = runtime.current_elapsed_realtime_ns().unwrap() + 1_500_000_000;
    *runtime.monotonic_bridge.lock().unwrap() =
        Some((MonotonicNanos::new(current), Instant::now()));
    runtime
        .submitted_frame_elapsed_ns
        .store(current, Ordering::Release);
    // A busy command lane must not starve the watchdog or charge stale time.
    for index in 0..32 {
        runtime.apply_command_json(&command("tick", &format!("polar-busy-{index}"), 1, current));
    }
    release.send(()).unwrap();
    let paused: Value =
        serde_json::from_str(&wait_for(&runtime, "\"control_state\":\"paused\"")).unwrap();
    let frozen = paused["readback"]["active_time_ms"].as_u64().unwrap();
    assert!(
        (1200..1400).contains(&frozen),
        "clock must stop at acquisition deadline: {paused}"
    );
    let denied = polar_action(&runtime, "resume", "polar-resume-denied");
    assert_eq!(denied["readback"]["last_reason"], "polar-input-not-fresh");
    runtime.apply_command_json(
        &polar_operator_resume(&runtime, "polar-operator-stale-input").to_string(),
    );
    let denied: Value = serde_json::from_str(&wait_for(
        &runtime,
        "\"last_operation_id\":\"polar-operator-stale-input\"",
    ))
    .unwrap();
    assert_eq!(denied["readback"]["last_reason"], "polar-input-not-fresh");
    polar_sample();
    let ready: Value =
        serde_json::from_str(&wait_for(&runtime, "recovered-awaiting-resume")).unwrap();
    assert_eq!(ready["readback"]["active_time_ms"], frozen);
    let resumed = polar_action(&runtime, "resume", "polar-direct-resume");
    assert_eq!(resumed["readback"]["control_state"], "running");
    assert_eq!(resumed["readback"]["polar_input"]["pause_latched"], false);

    // Losing configuration/effective source cannot drop the recording's gate.
    polar_selection("controller");
    let missing: Value = serde_json::from_str(&wait_for(&runtime, "source-unavailable")).unwrap();
    assert_eq!(missing["readback"]["control_state"], "paused");
    polar_sample(); // Unselected samples cannot furnish readiness.
    assert_eq!(
        polar_action(&runtime, "resume", "unselected-resume")["readback"]["last_reason"],
        "polar-input-not-fresh"
    );
    polar_selection("polar-acc");
    polar_sample();
    wait_for(&runtime, "recovered-awaiting-resume");
    let mut operator = polar_operator_resume(&runtime, "polar-operator-resume");
    operator["expected_runtime_epoch"] = json!(2);
    runtime.apply_command_json(&operator.to_string());
    let stale: Value = serde_json::from_str(&wait_for(
        &runtime,
        "\"last_operation_id\":\"polar-operator-resume\"",
    ))
    .unwrap();
    assert_eq!(
        stale["readback"]["last_reason"],
        "operator-owner-fence-mismatch"
    );
    operator["expected_runtime_epoch"] = json!(1);
    operator["operation_id"] = json!("polar-authenticated-resume");
    runtime.apply_command_json(&operator.to_string());
    let remote: Value = serde_json::from_str(&wait_for(
        &runtime,
        "\"operator_operation_id\":\"polar-authenticated-resume\"",
    ))
    .unwrap();
    assert_eq!(remote["readback"]["control_state"], "running");
    assert_eq!(remote["readback"]["polar_input"]["pause_latched"], false);
    runtime.shutdown_for_test().unwrap();
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

#[test]
fn polar_composition_owner_replacement_invalidates_proof_and_rejected_samples_cannot_refresh_it() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("polar-owner-reset");
    let runtime = runtime(&root);
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    polar_sample();
    runtime.apply_command_json(&start_command(&root, "polar-owner-start"));
    wait_for(&runtime, "\"phase\":\"active\"");
    crate::breath_composition_runtime::reset_to_packaged_defaults();
    let status: Value =
        serde_json::from_str(&wait_for(&runtime, "\"control_state\":\"paused\"")).unwrap();
    assert_eq!(status["readback"]["polar_input"]["fresh"], false);
    let previous = crate::polar_composition_adapters::latest_polar_acc_after(None).unwrap();
    crate::polar_composition_adapters::submit_polar_acc_measurement(
        previous.host_time_ns,
        previous.sensor_time_ns,
        previous.xyz_mg,
    );
    crate::polar_composition_adapters::submit_polar_acc_measurement(
        previous.host_time_ns + 1,
        previous.sensor_time_ns + 1,
        [f32::NAN, 0.0, 0.0],
    );
    thread::sleep(Duration::from_millis(25));
    assert_eq!(
        serde_json::from_str::<Value>(&runtime.status_json()).unwrap()["readback"]["polar_input"]
            ["fresh"],
        false
    );
    polar_sample();
    let recovered: Value =
        serde_json::from_str(&wait_for(&runtime, "recovered-awaiting-resume")).unwrap();
    assert_eq!(recovered["readback"]["control_state"], "paused");
    runtime.shutdown_for_test().unwrap();
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

#[test]
fn polar_gap_recovered_while_worker_is_busy_still_requires_explicit_resume() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("polar-recovery-during-writer");
    let runtime = runtime(&root);
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    polar_sample();
    runtime.apply_command_json(&start_command(&root, "polar-before-busy"));
    wait_for(&runtime, "\"phase\":\"active\"");
    let (entered, entered_rx) = mpsc::sync_channel(1);
    let (release, release_rx) = mpsc::sync_channel(1);
    runtime
        .command_sender
        .send(WorkerMessage::PauseForTest {
            entered,
            resume: release_rx,
        })
        .unwrap();
    entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    crate::polar_input_health::test_support::age_sample(Duration::from_millis(1250));
    polar_sample();
    release.send(()).unwrap();
    thread::sleep(Duration::from_millis(40));
    let status: Value = serde_json::from_str(&runtime.status_json()).unwrap();
    runtime.shutdown_for_test().unwrap();
    assert_eq!(status["readback"]["control_state"], "paused");
    assert_eq!(status["readback"]["polar_input"]["fresh"], true);
    assert_eq!(status["readback"]["polar_input"]["pause_latched"], true);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

fn check_guided_source_changing_start(timing: &str) {
    use rusty_quest_breath_contract::{
        composition::BreathCompositionSource, BreathTimestampMicros,
    };
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install();
    polar_selection("controller");
    let root = temp_root(&format!("guided-source-gate-{timing}"));
    let runtime =
        ExperimentSessionRuntime::spawn_actual(root.clone(), Some(guided_inventory())).unwrap();
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    let mut start = guided_arm_command(&root);
    start["operation"] = json!("start");
    start["operation_id"] = json!("combined-guided-start");
    let mut release = None;
    if timing == "while-queued" {
        let (entered, entered_rx) = mpsc::sync_channel(1);
        let (sender, receiver) = mpsc::sync_channel(1);
        runtime
            .command_sender
            .send(WorkerMessage::PauseForTest {
                entered,
                resume: receiver,
            })
            .unwrap();
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        release = Some(sender);
    }
    runtime.apply_command_json(&start.to_string());
    if let Some(release) = release {
        polar_selection("polar-acc");
        polar_sample();
        release.send(()).unwrap();
    }
    let mut recovered = false;
    let mut status = Value::Null;
    for tick in 1..400 {
        let selected = crate::breath_composition_runtime::snapshot()
            .effective
            .is_some_and(|request| request.source == BreathCompositionSource::PolarAcc);
        if selected {
            if timing == "during-preparation" && !recovered {
                polar_sample();
                recovered = true;
            }
            // Service the real production composition/adapter boundary, rather
            // than the older isolated guided-backend fixture.
            crate::breath_composition_runtime::poll_polar(BreathTimestampMicros::new(
                tick * 10_000,
            ));
        }
        status = serde_json::from_str(&runtime.status_json()).unwrap();
        if status["readback"]["last_operation_id"] == "combined-guided-start"
            && status["readback"]["phase"] != "preparing"
        {
            break;
        }
        thread::sleep(Duration::from_millis(5));
    }
    runtime.shutdown_for_test().unwrap();
    assert_eq!(
        status["readback"]["last_operation_status"], "rejected",
        "{timing}: {status}"
    );
    assert_eq!(status["readback"]["generation"], 0, "{timing}: {status}");
    assert_eq!(status["readback"]["last_reason"], "guided-start-requires-arm");
    if root.parent().unwrap().exists() {
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}

#[test]
fn guided_combined_start_from_controller_rejects_absent_acc() {
    check_guided_source_changing_start("absent");
}
#[test]
fn guided_combined_start_cannot_use_acc_returning_during_preparation() {
    check_guided_source_changing_start("during-preparation");
}
#[test]
fn guided_combined_start_cannot_use_a_polar_selection_made_while_queued() {
    check_guided_source_changing_start("while-queued");
}

#[test]
fn guided_controller_arm_retains_real_two_step_direct_controls() {
    use rusty_quest_breath_contract::BreathTimestampMicros;
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install();
    polar_selection("controller");
    let root = temp_root("guided-two-step-real-composition");
    let runtime =
        ExperimentSessionRuntime::spawn_actual(root.clone(), Some(guided_inventory())).unwrap();
    wait_for(&runtime, "\"initialization_status\":\"ready\"");
    runtime.apply_command_json(&guided_arm_command(&root).to_string());
    for tick in 1..400 {
        crate::breath_composition_runtime::poll_polar(BreathTimestampMicros::new(tick * 10_000));
        let state: Value = serde_json::from_str(&runtime.status_json()).unwrap();
        if state["readback"]["arm_durable_checkpoint"] == true {
            break;
        }
        thread::sleep(Duration::from_millis(5));
    }
    let armed: Value = serde_json::from_str(&runtime.status_json()).unwrap();
    assert_eq!(armed["readback"]["arm_durable_checkpoint"], true);
    polar_action(&runtime, "audio-prepared", "guided-two-step-audio");
    polar_present(&runtime, "guided-two-step-present");
    assert_eq!(
        polar_action(&runtime, "official-start", "guided-two-step-missing")["readback"]
            ["last_reason"],
        "polar-input-not-fresh"
    );
    polar_sample();
    assert_eq!(
        polar_action(&runtime, "official-start", "guided-two-step-start")["readback"]
            ["control_state"],
        "running"
    );
    assert_eq!(
        polar_action(&runtime, "pause", "guided-two-step-pause")["readback"]["control_state"],
        "paused"
    );
    assert_eq!(
        polar_action(&runtime, "resume", "guided-two-step-resume")["readback"]["control_state"],
        "running"
    );
    runtime.shutdown_for_test().unwrap();
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
