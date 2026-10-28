use super::*;
use serde_json::{Value, json};
fn app_record(run: &str, generation: u64, phase: &str) -> String {
    let body = format!(
        "{APP_RUN_SCHEMA}\n{generation}\n01234567-89ab-cdef-0123-456789abcdef\n{phase}\n-\n-\n{run}\n"
    );
    format!("{body}{}\n", digest(&body))
}
fn physical(family: u8) -> Value {
    let hash = "a".repeat(64);
    let mut n = json!({"$schema":format!("rusty.quest.embedded_duplex.no_media_retirement.v{family}"),"app_process_generation":17,"app_record_sha256":hash,"native_executor_generation":6,
        "native_runtime_binding":"not_initialized","disposition":"uninitialized_fence_closed"});
    if family == 2 {
        n["native_runtime_binding"] = json!("configured");
        n["disposition"] = json!("runtime_config_closed");
        n["config_sha256"] = json!(hash);
        n["configuration_binding"] = json!({"$schema":"rusty.quest.embedded_duplex.runtime_configuration_binding.v1","app_process_generation":17,"app_record_sha256":hash,"native_executor_generation":6,"config_sha256":hash,"local_peer_id":"fixture.peer","fresh_setup_build_policy":{}});
        n["native_close"] = json!({"$schema":"rusty.quest.embedded_duplex.no_media_closed.v1","config_sha256":hash,"disposition":"closed"});
    } else if family == 3 {
        n["native_runtime_binding"] = json!("partial_construction");
        n["disposition"] = json!("partial_construction_closed");
        n["config_sha256"] = json!(hash);
        n["construction_disposition"] = json!("not_committed");
        n["closure_body_sha256"] = json!(format!("sha256:{hash}"));
        n["durable_closure"] = Value::Null;
        n["provider_entered"] = json!(false);
        n["new_media_starts"] = json!(0);
        n["old_outcomes_preserved"] = json!(true);
        n["future_writable_restore"] = json!("owner_factory_not_entered");
    }
    let mut p = json!({"$schema":format!("rusty.quest.embedded_duplex.no_media_whole_retirement.v{family}"),"action":"whole_app_close","app_process_generation":17,"app_record_sha256":hash,"native_executor_generation":6,
        "process_epoch":"app-process.17.fixture","native_retirement":n,"arm_observation":{"binding":"not_established"},"own_capture":{"phase":"NotConstructed"}});
    if family != 1 {
        p["config_sha256"] = json!(hash);
    }
    for k in [
        "java_own_capture_cleanup",
        "java_peer_resources_cleanup",
        "java_renderer_cleanup",
        "java_control_cleanup",
        "java_callback_cleanup",
        "command_lane_barrier",
        "whole_app_physical_cleanup",
        "app_process_fence_cleanup",
    ] {
        p[k] = json!("terminal");
    }
    p
}
fn envelope(p: Value, binding: &AppBinding) -> Value {
    json!({"$schema":"rusty.quest.embedded_duplex.operational_run_handoff.v1","operational_run_id":binding.operational_run_id,"app_record_sha256":binding.record_sha256,
        "whole_original_id":"1".repeat(32),"request_sha256":"2".repeat(64),"outcome_sha256":"3".repeat(64),"prior_native_generation":6,
        "physical_retirement_sha256":digest(&canonical_handoff_json(&p).unwrap()),"physical_retirement":p})
}
fn rehash(h: &mut Value) {
    h["physical_retirement_sha256"] = json!(digest(
        &canonical_handoff_json(&h["physical_retirement"]).unwrap()
    ));
}
#[test]
fn no_media_versions_dispatch_through_production_operational_validator() {
    let binding = AppBinding::parse(&app_record(&"b".repeat(32), 18, "pending")).unwrap();
    for family in [1, 2, 3] {
        let h = envelope(physical(family), &binding);
        assert!(
            validate_operational_run_handoff(&h.to_string(), &binding, true, 6).is_ok(),
            "v{family}"
        );
        for damage in [
            "schema",
            "owner",
            "native_owner",
            "barrier",
            "native_schema",
            "extra",
            "type",
            "hash",
            "counter",
            "run",
            "channel",
        ] {
            let mut wrong = h.clone();
            match damage {
                "schema" => {
                    wrong["physical_retirement"]["$schema"] =
                        json!("rusty.quest.embedded_duplex.no_media_whole_retirement.v99")
                }
                "owner" => wrong["physical_retirement"]["app_process_generation"] = json!(19),
                "native_owner" => {
                    wrong["physical_retirement"]["native_retirement"]["native_executor_generation"] =
                        json!(7)
                }
                "barrier" => {
                    wrong["physical_retirement"]["java_renderer_cleanup"] = json!("pending")
                }
                "native_schema" => {
                    wrong["physical_retirement"]["native_retirement"]["$schema"] = json!("foreign")
                }
                "extra" => {
                    wrong["physical_retirement"]["native_retirement"]["unexpected"] = json!(true)
                }
                "type" => wrong["physical_retirement"]["app_process_generation"] = json!("17"),
                "hash" => wrong["physical_retirement_sha256"] = json!("0".repeat(64)),
                "counter" => wrong["prior_native_generation"] = json!(5),
                "run" => wrong["operational_run_id"] = json!("c".repeat(32)),
                _ => wrong["app_record_sha256"] = json!("d".repeat(64)),
            }
            if damage != "hash" {
                rehash(&mut wrong);
            }
            assert!(
                validate_operational_run_handoff(&wrong.to_string(), &binding, true, 6).is_err(),
                "v{family} {damage}"
            );
        }
        assert!(validate_operational_run_handoff(&h.to_string(), &binding, true, 7).is_err());
    }
    assert!(validate_operational_run_handoff("{", &binding, true, 6).is_err());
}
#[test]
fn captured_java_persist_reload_handoff_keeps_exact_digest_and_native_floor() {
    let Ok(path) = std::env::var("CAPTURED_JAVA_HANDOFF") else {
        return;
    };
    let mut h: Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
    let proof = h["physical_retirement"].clone();
    let expected = h["physical_retirement_sha256"].clone();
    let binding = AppBinding::parse(&app_record(&"b".repeat(32), 19, "pending")).unwrap();
    h["$schema"] = json!("rusty.quest.embedded_duplex.operational_run_handoff.v1");
    h["operational_run_id"] = json!(binding.operational_run_id);
    h["app_record_sha256"] = json!(binding.record_sha256);
    assert_eq!(
        proof["$schema"],
        "rusty.quest.embedded_duplex.no_media_whole_retirement.v3"
    );
    assert_eq!(proof["native_executor_generation"], 6);
    assert_eq!(proof["app_process_generation"], 17);
    assert_eq!(
        expected,
        json!(digest(&canonical_handoff_json(&proof).unwrap()))
    );
    assert!(validate_operational_run_handoff(&h.to_string(), &binding, true, 6).is_ok());
    assert!(validate_operational_run_handoff(&h.to_string(), &binding, true, 5).is_err());
    assert!(validate_operational_run_handoff(&h.to_string(), &binding, true, 7).is_err());
    for barrier in [
        "java_own_capture_cleanup",
        "java_peer_resources_cleanup",
        "java_renderer_cleanup",
        "java_control_cleanup",
        "java_callback_cleanup",
        "command_lane_barrier",
        "whole_app_physical_cleanup",
        "app_process_fence_cleanup",
    ] {
        let mut wrong = h.clone();
        wrong["physical_retirement"][barrier] = json!("pending");
        rehash(&mut wrong);
        assert!(
            validate_operational_run_handoff(&wrong.to_string(), &binding, true, 6).is_err(),
            "{barrier}"
        );
    }
}

fn native_record(owner: &Value) -> String {
    let body = format!(
        "{NATIVE_OWNER_SCHEMA}\n{}\n",
        canonical_handoff_json(owner).unwrap()
    );
    format!("{body}{}\n", digest(&body))
}
fn cross_run_link() -> (String, String, Value) {
    let prior = "a".repeat(32);
    let current = "b".repeat(32);
    let current_identity = current_native_process_identity().unwrap();
    let old = json!({"boot_id":"11111111-1111-1111-1111-111111111111","pid":99,"uid":current_identity["uid"],"start_ticks":8});
    let app = app_record(&current, 19, "pending");
    let last = app_record(&prior, 17, "clear");
    let owner =
        json!({"counter":6,"app_record":app_record(&prior,17,"pending"),"process_identity":old});
    let native = native_record(&owner);
    let checksum = digest(&format!(
        "{}\n{}\n{}\n{}\n{}{}\nfalse\n-\nprocess_local_resources\n",
        old["boot_id"].as_str().unwrap(),
        99,
        old["uid"],
        8,
        last,
        digest(&last)
    ));
    let companion = json!({"$schema":"rusty.quest.embedded_duplex.process_incarnation.v1","original_owner":old,"effect_category":"process_local_resources","original_fence_record":last,"current_fence_sha256":digest(&last),"local_resources_terminated":false,"termination_receipt_sha256":"-","sha256":checksum});
    let link = json!({"$schema":"rusty.quest.embedded_duplex.cold_pending_owner_link.v2","recovery_original_id":"d".repeat(32),"run_id":current,"app_process_generation":19,"app_record_sha256":digest(&app),"prior_native_generation":6,"native_record_sha256":digest(&native),"native_owner":owner,"last_writable_record":last,"last_incarnation_json":canonical_handoff_json(&companion).unwrap(),"old_process_identity":old,"current_process_identity":current_native_process_identity().unwrap(),"old_process_retirement":"boot_changed","journal_snapshot":{"checkpoint_sha256":"-","evidence_sha256":"-","prior_checkpoint_sha256":"-","prior_evidence_sha256":"-"}});
    (app, native, link)
}
#[test]
fn retained_source_run_companion_is_checked_against_exact_unretired_native_owner() {
    let (app, native, link) = cross_run_link();
    let binding = AppBinding::parse(&app).unwrap();
    assert!(
        validate_cold_link(
            &canonical_handoff_json(&link).unwrap(),
            &binding,
            &native,
            6
        )
        .is_ok()
    );
    for damage in [
        "source_run",
        "source_generation",
        "source_nonce",
        "retired",
        "process",
        "companion",
        "counter",
        "current_run",
        "journal",
        "legacy",
    ] {
        let mut wrong = link.clone();
        let mut subject = native.clone();
        match damage {
            "source_run" => {
                wrong["native_owner"]["app_record"] =
                    json!(app_record(&"c".repeat(32), 17, "pending"))
            }
            "source_generation" => {
                wrong["native_owner"]["app_record"] =
                    json!(app_record(&"a".repeat(32), 16, "pending"))
            }
            "source_nonce" => {
                let raw = app_record(&"a".repeat(32), 17, "pending").replace(
                    "01234567-89ab-cdef-0123-456789abcdef",
                    "11234567-89ab-cdef-0123-456789abcdef",
                );
                let body = raw.lines().take(7).collect::<Vec<_>>().join("\n") + "\n";
                wrong["native_owner"]["app_record"] = json!(format!("{body}{}\n", digest(&body)));
            }
            "retired" => wrong["native_owner"]["retirement"] = json!({}),
            "process" => wrong["native_owner"]["process_identity"]["start_ticks"] = json!(9),
            "companion" => wrong["last_incarnation_json"] = json!("{}"),
            "counter" => wrong["prior_native_generation"] = json!(5),
            "current_run" => wrong["run_id"] = json!("c".repeat(32)),
            "journal" => {
                wrong["journal_snapshot"]["prior_checkpoint_sha256"] = json!("f".repeat(64))
            }
            _ => {
                let body = format!("{NATIVE_SCHEMA}\n6\n");
                subject = format!("{body}{}\n", digest(&body));
            }
        }
        if [
            "source_run",
            "source_generation",
            "source_nonce",
            "retired",
            "process",
        ]
        .contains(&damage)
        {
            subject = native_record(&wrong["native_owner"]);
        }
        wrong["native_record_sha256"] = json!(digest(&subject));
        assert!(
            validate_cold_link(
                &canonical_handoff_json(&wrong).unwrap(),
                &binding,
                &subject,
                6
            )
            .is_err(),
            "{damage}"
        );
    }
}

#[test]
fn configured_finite_pending_fresh_and_entered_partial_variants_keep_their_distinct_custody() {
    let binding = AppBinding::parse(&app_record(&"b".repeat(32), 18, "pending")).unwrap();
    let mut finite = physical(2);
    finite["$schema"] = json!("rusty.quest.embedded_duplex.no_media_whole_retirement.v1");
    finite["native_retirement"]["$schema"] =
        json!("rusty.quest.embedded_duplex.no_media_retirement.v1");
    finite["native_retirement"]
        .as_object_mut()
        .unwrap()
        .remove("configuration_binding");
    assert!(
        validate_operational_run_handoff(
            &envelope(finite, &binding).to_string(),
            &binding,
            true,
            6
        )
        .is_ok()
    );
    let mut fresh = physical(2);
    let native = &mut fresh["native_retirement"];
    native.as_object_mut().unwrap().remove("native_close");
    native["disposition"] = json!("pending_fresh_local_closed");
    native["transaction_outcome"] = json!("unknown");
    native["future_writable_restore"] = json!("fenced_pending_supported_owner_reconciliation");
    let mut ack = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_closure_retained.v1","body_sha256":format!("sha256:{}","a".repeat(64)),"storage_identity":"fixture.closure","original_binding":{"kind":"operator","original_id":"1".repeat(32)}});
    for key in [
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
    ] {
        ack[key] = native[key].clone();
    }
    native["durable_closure"] = ack;
    let h = envelope(fresh, &binding);
    assert!(validate_operational_run_handoff(&h.to_string(), &binding, true, 6).is_ok());
    let mut wrong = h.clone();
    wrong["physical_retirement"]["native_retirement"]["transaction_outcome"] = json!("succeeded");
    rehash(&mut wrong);
    assert!(validate_operational_run_handoff(&wrong.to_string(), &binding, true, 6).is_err());
    let mut partial = physical(3);
    let native = &mut partial["native_retirement"];
    native["provider_entered"] = json!(true);
    native["future_writable_restore"] = json!("fenced_pending_supported_owner_reconciliation");
    let mut ack = json!({"$schema":"rusty.quest.embedded_duplex.partial_construction_closure_retained.v1","body_sha256":native["closure_body_sha256"],"storage_identity":"app-no-backup:partial-construction-v1:closure"});
    for key in [
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
        "config_sha256",
    ] {
        ack[key] = native[key].clone();
    }
    native["durable_closure"] = ack;
    let h = envelope(partial, &binding);
    assert!(validate_operational_run_handoff(&h.to_string(), &binding, true, 6).is_ok());
    let mut wrong = h.clone();
    wrong["physical_retirement"]["native_retirement"]["durable_closure"]["native_executor_generation"] =
        json!(5);
    rehash(&mut wrong);
    assert!(validate_operational_run_handoff(&wrong.to_string(), &binding, true, 6).is_err());
}
