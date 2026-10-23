use std::{env, path::PathBuf};
fn main() {
    let anchors = [
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROFILE_SHA256",
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROVIDER_MANIFEST_SHA256",
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_INVENTORY_SHA256",
        "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256",
        "RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_DRIVER_EXPECTED_BINDING_SHA256",
        "RQ_SDK_SESSION_SETTINGS_SHA256",
    ];
    let android = env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android");
    for key in anchors {
        println!("cargo:rerun-if-env-changed={key}");
        let value = env::var(key).unwrap_or_default();
        if android {
            assert!(value.len() == 64 && value.bytes().all(|v| v.is_ascii_digit() || (b'a'..=b'f').contains(&v))
                && value != "0".repeat(64), "missing or malformed {key}");
        }
    }
    let policy = "RUSTY_QUEST_NATIVE_RENDERER_EXPERIMENT_KIOSK_REQUESTED";
    println!("cargo:rerun-if-env-changed={policy}");
    if android { assert_eq!(env::var(policy).as_deref(), Ok("false"), "SDK standalone requires false kiosk policy"); }
    println!("cargo:rerun-if-env-changed=RQ_SDK_SESSION_SETTINGS_FILE");
    let destination = PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("session-settings.json");
    if let Some(source) = env::var_os("RQ_SDK_SESSION_SETTINGS_FILE") {
        println!("cargo:rerun-if-changed={}", PathBuf::from(&source).display());
        std::fs::copy(source, destination).expect("copy exact selected settings");
    } else {
        assert!(!android, "missing RQ_SDK_SESSION_SETTINGS_FILE");
        std::fs::write(destination, "{}").unwrap();
    }
    println!("cargo:rerun-if-changed=src/observer_bridge.cpp");
    println!("cargo:rerun-if-changed=src/service_bridge.cpp");
    println!("cargo:rerun-if-changed=include/rq_experiment_services.h");
    println!("cargo:rerun-if-changed=../../../android/openxr-submission-observer/submission_observer.h");
    println!("cargo:rerun-if-changed=../../../android/openxr-submission-observer/submission_observer_status.h");
    if android {
        cc::Build::new().cpp(true).std("c++17").file("src/observer_bridge.cpp").file("src/service_bridge.cpp").include("include")
            .include("../../../android/openxr-submission-observer").compile("rq_session_observer_bridge");
        println!("cargo:rustc-link-lib=dl");
    }
}
