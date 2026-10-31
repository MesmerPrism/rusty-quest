fn spawn_guided_test_runtime(root: PathBuf) -> Result<ExperimentSessionRuntime, String> {
    ExperimentSessionRuntime::spawn(root, Some(guided_inventory()), |slot, loss| {
        let mut backend = DamageBackend::new(slot, loss);
        backend.guided_composition = Some(crate::breath_composition_runtime::isolated_guided_test_runtime());
        Box::new(backend)
    })
}

fn guided_inventory() -> TrustedExperimentInventory {
    let mut inventory = trusted_inventory();
    let text = json!({"schema":crate::breath_guidance::BREATH_GUIDANCE_SCHEMA,
        "timebase":crate::breath_guidance::BREATH_GUIDANCE_TIMEBASE,
        "audio_source_sha256":"22".repeat(32),
        "segments":[{"start_ms":0,"end_ms":30000,"phase":"inhale"}]}).to_string();
    let identity = BreathGuidanceAssetIdentity {
        logical_destination: "breath-guidance/condition-a.json".to_owned(),
        source_sha256: rusty_quest_broker_authority::packaged_json_sha256(&text),
        source_bytes: text.len() as u64, media_type:"application/json".to_owned(),
    };
    inventory.conditions[0].breath_guidance = Some(TrustedBreathGuidanceInventory {
        timeline: Arc::new(BreathGuidanceTimeline::parse_exact(text.as_bytes(), identity, &"22".repeat(32)).unwrap()),
        default_bias_percent:35,
    });
    inventory
}

fn arm_guided(runtime: &ExperimentSessionRuntime, root: &Path) -> Value {
    wait_for(runtime, "\"initialization_status\":\"ready\"");
    let arm = guided_arm_command(root);
    runtime.apply_command_json(&arm.to_string());
    wait_for(runtime,"\"arm_durable_checkpoint\":true");
    runtime.apply_command_json(&command("audio-prepared","audio-ready",1,2000));
    serde_json::from_str(&wait_for(runtime,"\"control_state\":\"armed\"")).unwrap()
}

fn guided_arm_command(root: &Path) -> Value {
    let mut arm: Value = serde_json::from_str(&start_command(root,"guided-arm")).unwrap();
    arm["operation"] = json!("arm");
    arm["breath_guidance_bias_percent"] = json!(35);
    arm["breath_guidance"] = guided_inventory().conditions[0].breath_guidance.as_ref().unwrap().timeline.identity.encoded_value();
    arm
}

fn bias_command(state: &Value, id: &str, bias: u8, at: u64) -> Value {
    json!({"schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,"operation":"operator-set-bias",
        "operation_id":id,"expected_generation":1,"elapsed_realtime_ns":at,"bias_percent":bias,
        "operator_origin":"shell-experiment-operator-v1","expected_runtime_epoch":1,
        "expected_session_key":state["readback"]["session_key"],"expected_condition":"condition-a",
        "expected_profile_sha256":"11".repeat(32),
        "expected_binding_sha256":serde_json::from_str::<Value>(&crate::breath_composition_runtime::status_json()).unwrap()["snapshot"]["activation_binding_sha256"]})
}

fn apply_and_read(runtime: &ExperimentSessionRuntime, command: &Value) -> Value {
    let previous:Value=serde_json::from_str(&runtime.status_json()).unwrap();
    let revision=previous["readback"]["revision"].as_u64().unwrap();
    runtime.apply_command_json(&command.to_string());
    for _ in 0..200 {
        let current:Value=serde_json::from_str(&runtime.status_json()).unwrap();
        if current["readback"]["revision"].as_u64().unwrap_or(0)>revision
            && current["readback"]["last_operation_id"]==command["operation_id"] {return current;}
        thread::sleep(Duration::from_millis(5));
    }
    panic!("operation not observed: {}",runtime.status_json())
}

#[test]
fn live_bias_changes_are_durable_fenced_replay_safe_and_manifest_immutable() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("live-bias");
    let runtime = spawn_guided_test_runtime(root.clone()).unwrap();
    let armed = arm_guided(&runtime,&root);
    let directory = root.join(armed["readback"]["session_key"].as_str().unwrap());
    let manifest_before = fs::read(directory.join("manifest.json")).unwrap();
    let manifest: Value = serde_json::from_slice(&manifest_before).unwrap();
    let tracking = &manifest["breath_tracking_configuration"];
    assert_eq!(tracking["source"],"polar-acc");
    assert_eq!(tracking["mapping"],"state");
    assert_eq!(tracking["arm_operation_id"],"guided-arm");
    assert_eq!(tracking["experiment_generation"],1);
    assert_eq!(tracking["tracking_claim"],"not-established");
    assert_eq!(apply_and_read(&runtime,&guided_arm_command(&root))["readback"]["last_operation_status"],"duplicate");
    let first = bias_command(&armed,"bias-first",60,3000);
    let applied = apply_and_read(&runtime,&first);
    assert_eq!(applied["readback"]["breath_guidance"]["bias_percent"],60);
    assert_eq!(applied["readback"]["breath_guidance"]["bias_revision"],1);
    assert_eq!(applied["readback"]["breath_guidance"]["arm_bias_percent"],35);
    assert_eq!(applied["readback"]["operator_operation_status"],"applied");
    assert!(fs::read_to_string(directory.join("events.jsonl")).unwrap().contains("bias-first"));
    for (i,(field,value)) in [("bias_percent",json!(101)),("bias_percent",json!(-1)),
        ("bias_percent",json!(1.5)),("expected_generation",json!(2)),("expected_runtime_epoch",json!(2)),
        ("elapsed_realtime_ns",json!(2500))].into_iter().enumerate() {
        let mut bad=first.clone(); bad["operation_id"]=json!(format!("invalid-bias-{i}")); bad[field]=value;
        let rejected=apply_and_read(&runtime,&bad);
        assert_eq!(rejected["readback"]["last_operation_status"],"rejected");
        assert_eq!(rejected["readback"]["breath_guidance"]["bias_revision"],1);
    }
    // Eviction of unrelated generic command history must not enable a bias replay.
    for i in 0..70 { apply_and_read(&runtime,&serde_json::from_str(&command("tick",&format!("tick-{i}"),1,3100+i)).unwrap()); }
    let duplicate=apply_and_read(&runtime,&first);
    assert_eq!(duplicate["readback"]["last_operation_status"],"duplicate");
    let mut conflict=first.clone(); conflict["bias_percent"]=json!(99);
    assert_eq!(apply_and_read(&runtime,&conflict)["readback"]["last_operation_status"],"rejected");
    runtime.note_submitted_frame(1,1);
    let mut presented:Value=serde_json::from_str(&command("presentation","present",1,4000)).unwrap();
    presented["presentation"]=json!("immersive-active"); apply_and_read(&runtime,&presented);
    // Guided sessions require real accepted acquisition even when this fixture
    // isolates the parameter-acknowledgement adapter for its bias assertions.
    polar_sample();
    apply_and_read(&runtime,&serde_json::from_str(&command("official-start","official",1,5000)).unwrap());
    let running=apply_and_read(&runtime,&bias_command(&armed,"bias-running",80,6000));
    assert_eq!(running["readback"]["control_state"],"running");
    let target=runtime.current_breath_guidance_target().unwrap();
    assert_eq!((target.bias_percent,target.bias_revision),(80,2));
    runtime.note_submitted_frame(1,2);
    let before:Value=serde_json::from_str(&runtime.status_json()).unwrap();
    thread::sleep(Duration::from_millis(20));
    let after:Value=serde_json::from_str(&runtime.apply_command_json(&json!({"schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,"operation":"status"}).to_string())).unwrap();
    assert!(after["readback"]["active_time_ms"].as_u64().unwrap()>before["readback"]["active_time_ms"].as_u64().unwrap());
    assert_eq!(after["readback"]["revision"],before["readback"]["revision"]);
    let record=SessionRecord::Breath(BreathObservation {
        assessment_source: Some("polar-acc"),
        tracking_status: Some("valid"),
        polar_source_provenance: Some(crate::polar_composition_adapters::PolarPresentationProvenance {
            policy:"latest-java-sample-smoothed-at-xr-target",java_before_ns:9_000_000_000,java_after_ns:9_000_001_000,
            xr_target_ns:6000,java_target_ns:9_000_000_500,
            source_left_sequence_id:1,source_right_sequence_id:1,source_left_host_ns:8_999_999_500,source_right_host_ns:8_999_999_500,
            source_left_sensor_ns:42,source_right_sensor_ns:42,
        }),
        source_sequence:1, sampled_at:SourceTimestamp{clock:SourceClock::OpenXrTime,value_ns:6000},
        observed_source:SourceTimestamp{clock:SourceClock::OpenXrTime,value_ns:6000},
        observed_at:MonotonicNanos::new(6500),phase:BreathPhase::Inhale,unbiased_phase:BreathPhase::Exhale,
        guidance_phase:Some(BreathPhase::Inhale),guidance_bias_percent:80,guidance_bias_revision:2,
        guidance_active_time_ms:Some(0),volume01:Some(0.5),quality01:1.0,settings_revision:9,
    });
    let SessionRecord::Breath(observation)=record.clone() else {unreachable!()};
    fn assert_copy<T: Copy>() {}
    assert_copy::<BreathObservation>();
    let expected_provenance = observation.polar_source_provenance.unwrap().encoded_value();
    let mut absent=observation.clone(); absent.polar_source_provenance=None;
    assert!(!SessionRecord::Breath(absent).floats_are_finite());
    let mut wrong_source=observation.clone(); wrong_source.assessment_source=Some("controller");
    assert!(!SessionRecord::Breath(wrong_source).floats_are_finite());
    let mut unrelated_pair=observation; unrelated_pair.sampled_at.value_ns+=1000;
    assert!(!SessionRecord::Breath(unrelated_pair).floats_are_finite());
    assert!(matches!(runtime.try_offer(record),OfferOutcome::Accepted{..}));
    apply_and_read(&runtime,&serde_json::from_str(&command("pause","pause",1,7000)).unwrap());
    let paused=apply_and_read(&runtime,&bias_command(&armed,"bias-paused",0,8000));
    assert_eq!(paused["readback"]["breath_guidance"]["bias_revision"],3);
    assert_eq!(paused["readback"]["control_state"],"paused");
    assert!(runtime.current_breath_guidance_target().is_none());
    assert_eq!(apply_and_read(&runtime,&bias_command(&armed,"bias-same-value",0,8500))["readback"]["breath_guidance"]["bias_revision"],4);
    apply_and_read(&runtime,&serde_json::from_str(&command("save-and-exit","save",1,9000)).unwrap());
    wait_for(&runtime,"\"shutdown_status\":\"complete\""); runtime.shutdown_for_test().unwrap();
    assert_eq!(fs::read(directory.join("manifest.json")).unwrap(),manifest_before);
    let final_value:Value=serde_json::from_slice(&fs::read(directory.join("final.json")).unwrap()).unwrap();
    assert_eq!(final_value["breath_guidance_state"]["bias_revision"],4);
    assert_eq!(final_value["breath_guidance_state"]["bias_percent"],0);
    assert_eq!(&final_value["breath_tracking_configuration"],tracking);
    let events_all:Vec<Value>=fs::read_to_string(directory.join("events.jsonl")).unwrap().lines()
        .map(|line|serde_json::from_str(line).unwrap()).collect();
    assert_eq!(&events_all.iter().find(|row|row["event"]=="started").unwrap()["breath_tracking_configuration"],tracking);
    let rows:Vec<Value>=fs::read_to_string(directory.join("events.jsonl")).unwrap().lines()
        .map(|s|serde_json::from_str(s).unwrap()).filter(|r:&Value|r["event"]=="breath-guidance-bias-changed").collect();
    assert_eq!(rows.len(),4);
    assert_eq!(rows[1]["old_bias_percent"],60);
    let recovery=crate::session_recording_recovery::recover_recordings(&root).unwrap();
    assert_eq!(recovery.counts.stopped_early,1);
    let breath:Value=serde_json::from_str(fs::read_to_string(directory.join("breath.jsonl")).unwrap().trim()).unwrap();
    assert_eq!(breath["unbiased_phase"],"exhale");
    assert_eq!(breath["guidance_bias_revision"],2);
    assert_eq!(breath["settings_revision"],9);
    assert_eq!(breath["assessment_source"],"polar-acc");
    assert!(breath["polar_source_provenance"].is_object());
    assert_eq!(breath["polar_source_provenance"], expected_provenance);
    assert_eq!(breath["sampled_clock"],tracking["sampled_clock"]);
    assert_eq!(breath["observed_source_clock"],tracking["observed_source_clock"]);
    let events=fs::read_to_string(directory.join("events.jsonl")).unwrap();
    let wrong_source=events.replace("\"source\":\"polar-acc\"","\"source\":\"controller\"");
    assert_ne!(events,wrong_source);
    fs::write(directory.join("events.jsonl"),wrong_source).unwrap();
    assert_eq!(crate::session_recording_recovery::recover_recordings(&root).unwrap().counts.errors,1);
    let damaged=events.replace("\"old_bias_percent\":60","\"old_bias_percent\":61");
    assert_ne!(events,damaged);
    fs::write(directory.join("events.jsonl"),damaged).unwrap();
    assert_eq!(crate::session_recording_recovery::recover_recordings(&root).unwrap().counts.errors,1);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

#[test]
fn guided_arm_configuration_failure_never_prepares_writer_and_does_not_replay() {
    let root=temp_root("guided-arm-unavailable");
    let entered=Arc::new(AtomicBool::new(false));
    let worker_entered=Arc::clone(&entered);
    let runtime=ExperimentSessionRuntime::spawn(root.clone(),Some(guided_inventory()),move |slot,loss| {
        let mut backend=DamageBackend::new(slot,loss);
        backend.guided_composition=Some(crate::breath_composition_runtime::BreathCompositionRuntime::new(Default::default()));
        backend.prepare_entered=Some(worker_entered);
        Box::new(backend)
    }).unwrap();
    wait_for(&runtime,"\"initialization_status\":\"ready\"");
    let arm=guided_arm_command(&root);
    let failed=apply_and_read(&runtime,&arm);
    assert_eq!(failed["readback"]["last_operation_status"],"failed");
    assert!(failed["readback"]["last_reason"].as_str().unwrap().starts_with("guided-breath-preparation:"));
    assert_eq!(failed["readback"]["arm_durable_checkpoint"],false);
    assert_eq!(failed["readback"]["polar_input"]["required"],false);
    assert!(!entered.load(Ordering::Acquire));
    assert!(!root.join(SessionTimestampKey::from_utc(UtcEpochNanos::new(1_725_000_000_000_000_000)).as_str()).exists());
    assert_eq!(apply_and_read(&runtime,&arm)["readback"]["last_operation_status"],"duplicate");
    assert!(!entered.load(Ordering::Acquire));
    runtime.shutdown_for_test().unwrap();
    // Rejection precedes writer creation, so the temporary root may be absent.
    if root.parent().unwrap().exists() {
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}

#[test]
fn failed_bias_checkpoint_never_applies_and_same_operation_never_replays() {
    let root=temp_root("bias-failure");
    let runtime=ExperimentSessionRuntime::spawn(root.clone(),Some(guided_inventory()),|slot,loss| {
        let mut backend=DamageBackend::new(slot,loss); backend.guided_composition=Some(crate::breath_composition_runtime::isolated_guided_test_runtime()); backend.fail_control_once.store(true,Ordering::Release); Box::new(backend)
    }).unwrap();
    let armed=arm_guided(&runtime,&root);
    let request=bias_command(&armed,"failed-bias",75,3000);
    let failed=apply_and_read(&runtime,&request);
    assert_eq!(failed["readback"]["last_operation_status"],"failed");
    assert_ne!(failed["readback"]["breath_guidance"]["bias_percent"],75);
    assert_eq!(failed["readback"]["recording_result"]["status"],"unsaved");
    assert_eq!(apply_and_read(&runtime,&request)["readback"]["last_operation_status"],"duplicate");
    runtime.shutdown_for_test().unwrap(); fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

#[test]
fn read_only_clocks_advance_after_completion_and_freeze_when_paused_or_unfocused() {
    use crate::experiment_session::ExperimentControlState;
    let mut projection=Projection::default();
    projection.phase=SessionPhase::Active;
    projection.recording_started_ns=Some(1_000_000_000);
    projection.control_state=ExperimentControlState::Armed;
    let read=|p:&Projection,now|serde_json::from_str::<Value>(&p.response_json_at("accepted","none",Some(now))).unwrap()["readback"].clone();
    assert_eq!(read(&projection,3_000_000_000)["active_time_ms"],0);
    assert_eq!(read(&projection,3_000_000_000)["preparation_elapsed_ms"],2000);
    projection.official_started_ns=Some(3_000_000_000);
    projection.control_state=ExperimentControlState::Running;
    projection.presentation=PresentationState::ImmersiveActive;
    projection.active_time_ns=30_000_000_000;
    projection.active_interval_started_ns=Some(33_000_000_000);
    projection.completion=CompletionProgress::Durable;
    assert_eq!(read(&projection,35_000_000_000)["active_time_ms"],32000);
    assert_eq!(read(&projection,36_000_000_000)["active_time_ms"],33000);
    assert_eq!(read(&projection,36_000_000_000)["recording_elapsed_ms"],35000);
    assert_eq!(read(&projection,36_000_000_000)["preparation_elapsed_ms"],2000);
    projection.control_state=ExperimentControlState::Paused;
    assert_eq!(read(&projection,36_000_000_000)["active_time_ms"],30000);
    projection.control_state=ExperimentControlState::Running;
    projection.presentation=PresentationState::Unfocused;
    assert_eq!(read(&projection,36_000_000_000)["active_time_ms"],30000);
    assert_eq!(projection.active_time_ns,30_000_000_000);
    assert_eq!(projection.revision,0);
    projection.presentation=PresentationState::ImmersiveActive;
    projection.generation=1;
    let capped=projection.observed_now(Some(40_000_000_000),1,10,34_000_000_000);
    assert_eq!(capped,Some(34_500_000_000));
    assert_eq!(projection.observed_now(Some(40_000_000_000),2,10,34_000_000_000),Some(33_000_000_000));
    let stalled:Value=serde_json::from_str(&projection.response_json_clocks("accepted","none",Some(40_000_000_000),capped)).unwrap();
    assert_eq!(stalled["readback"]["active_time_ms"],31500);
    assert_eq!(stalled["readback"]["recording_elapsed_ms"],39000);
}

fn start_guided_live_clock(runtime: &ExperimentSessionRuntime, root: &Path) -> (Value, u64, u64) {
    let armed = arm_guided(runtime, root);
    assert!(runtime.note_submitted_frame(1, 1));
    let mut presented: Value = serde_json::from_str(&command("presentation", "live-present", 1,
        runtime.current_elapsed_realtime_ns().unwrap())).unwrap();
    presented["presentation"] = json!("immersive-active");
    apply_and_read(runtime, &presented);
    let started = runtime.current_elapsed_realtime_ns().unwrap();
    polar_sample();
    let result = apply_and_read(runtime, &serde_json::from_str(&command("official-start", "live-start", 1, started)).unwrap());
    assert_eq!(result["readback"]["control_state"], "running");
    assert!(runtime.note_submitted_frame(1, 2));
    let deadline = runtime.submitted_frame_elapsed_ns.load(Ordering::Acquire) + SUBMITTED_FRAME_STALL_NS;
    (armed, started, deadline)
}

fn assert_stall_archive(root: &Path, armed: &Value, started: u64, deadline: u64, saved_at: u64) {
    let directory = root.join(armed["readback"]["session_key"].as_str().unwrap());
    let events: Vec<Value> = fs::read_to_string(directory.join("events.jsonl")).unwrap().lines()
        .map(|line| serde_json::from_str(line).unwrap()).collect();
    let start = events.iter().find(|row| row["event"] == "official-start").unwrap();
    let pauses: Vec<_> = events.iter().filter(|row| row["event"] == "immersive-paused").collect();
    assert_eq!(pauses.len(), 1);
    assert_eq!(start["observed_monotonic_ns"], started);
    assert_eq!(pauses[0]["observed_monotonic_ns"], deadline);
    assert_eq!(pauses[0]["observed_monotonic_ns"].as_u64().unwrap() - started, deadline - started);
    let final_value: Value = serde_json::from_slice(&fs::read(directory.join("final.json")).unwrap()).unwrap();
    assert_eq!(final_value["ended_monotonic_ns"], saved_at);
    assert_eq!(final_value["completed"], false);
    assert_eq!(final_value["saved"], true);
    let recovered = crate::session_recording_recovery::recover_recordings(root).unwrap();
    assert_eq!(recovered.counts.errors, 0);
    assert_eq!(recovered.counts.stopped_early, 1);
}

#[test]
fn blocked_bias_checkpoint_freezes_controller_and_archive_before_queued_pause_or_save() {
    for scenario in ["pause", "save-and-exit", "older-pause"] {
        let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
        let root = temp_root(&format!("blocked-bias-{scenario}"));
        let entered = Arc::new(AtomicBool::new(false));
        let release = Arc::new(AtomicBool::new(false));
        let entered_worker = Arc::clone(&entered);
        let release_worker = Arc::clone(&release);
        let runtime = ExperimentSessionRuntime::spawn(root.clone(), Some(guided_inventory()), move |slot, loss| {
            let mut backend = DamageBackend::new(slot, loss);
            backend.guided_composition = Some(crate::breath_composition_runtime::isolated_guided_test_runtime());
            backend.bias_entered = Some(entered_worker);
            backend.bias_release = Some(release_worker);
            Box::new(backend)
        }).unwrap();
        let (armed, started, deadline) = start_guided_live_clock(&runtime, &root);
        let bias = bias_command(&armed, "blocked-bias", 70, runtime.current_elapsed_realtime_ns().unwrap());
        runtime.apply_command_json(&bias.to_string());
        for _ in 0..200 {
            if entered.load(Ordering::Acquire) { break; }
            thread::sleep(Duration::from_millis(5));
        }
        let older = runtime.current_elapsed_realtime_ns().unwrap();
        thread::sleep(Duration::from_millis(600));
        let queued_at = if scenario == "older-pause" { older } else { runtime.current_elapsed_realtime_ns().unwrap() };
        let operation = if scenario == "save-and-exit" { scenario } else { "pause" };
        let queued = command(operation, "queued-after-bias", 1, queued_at);
        let receipt = runtime.apply_command_json(&queued);
        release.store(true, Ordering::Release);
        assert!(entered.load(Ordering::Acquire));
        assert!(receipt.contains("\"command_status\":\"queued\""));
        let completed: Value = serde_json::from_str(&wait_for(&runtime, "\"last_operation_id\":\"queued-after-bias\"")).unwrap();
        if scenario == "older-pause" {
            assert_eq!(completed["readback"]["last_operation_status"], "rejected");
            assert!(completed.to_string().contains("MonotonicTimeRegression"));
            assert_eq!(apply_and_read(&runtime, &serde_json::from_str(&queued).unwrap())["readback"]["last_operation_status"], "duplicate");
        } else {
            assert_eq!(completed["readback"]["last_operation_status"], "accepted");
        }
        // This is the authoritative reducer accumulator, not response_json's cap.
        assert_eq!(runtime.projection.read().unwrap().active_time_ns, deadline - started);
        let saved_at = if scenario == "save-and-exit" { queued_at } else {
            let at = runtime.current_elapsed_realtime_ns().unwrap();
            apply_and_read(&runtime, &serde_json::from_str(&command("save-and-exit", "final-save", 1, at)).unwrap());
            at
        };
        wait_for(&runtime, "\"shutdown_status\":\"complete\"");
        runtime.shutdown_for_test().unwrap();
        assert_stall_archive(&root, &armed, started, deadline, saved_at);
        let directory = root.join(armed["readback"]["session_key"].as_str().unwrap());
        let events = fs::read_to_string(directory.join("events.jsonl")).unwrap();
        assert_eq!(events.matches("breath-guidance-bias-changed").count(), 1);
        assert!(events.contains("\"new_bias_percent\":70"));
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}

#[test]
fn saturated_command_lane_reconciles_stall_before_first_queued_tick() {
    let _binding = crate::breath_composition_runtime::scoped_test_binding::install_polar();
    let root = temp_root("busy-lane-stall");
    let runtime = spawn_guided_test_runtime(root.clone()).unwrap();
    let (armed, started, deadline) = start_guided_live_clock(&runtime, &root);
    let (entered, entered_rx) = mpsc::sync_channel(1);
    let (resume, resume_rx) = mpsc::channel();
    runtime.command_sender.send(WorkerMessage::PauseForTest { entered, resume: resume_rx }).unwrap();
    entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    thread::sleep(Duration::from_millis(600));
    let at = runtime.current_elapsed_realtime_ns().unwrap();
    for i in 0..COMMAND_QUEUE_CAPACITY {
        assert!(runtime.apply_command_json(&command("tick", &format!("busy-tick-{i}"), 1, at))
            .contains("\"command_status\":\"queued\""));
    }
    assert!(runtime.apply_command_json(&command("tick", "queue-overflow", 1, at)).contains("command-queue-full"));
    resume.send(()).unwrap();
    wait_for(&runtime, &format!("\"last_operation_id\":\"busy-tick-{}\"", COMMAND_QUEUE_CAPACITY - 1));
    assert_eq!(runtime.projection.read().unwrap().active_time_ns, deadline - started);
    let saved_at = runtime.current_elapsed_realtime_ns().unwrap();
    apply_and_read(&runtime, &serde_json::from_str(&command("save-and-exit", "busy-save", 1, saved_at)).unwrap());
    wait_for(&runtime, "\"shutdown_status\":\"complete\"");
    runtime.shutdown_for_test().unwrap();
    assert_stall_archive(&root, &armed, started, deadline, saved_at);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
