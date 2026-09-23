//! JNI boundary for the synthetic Android media-stream conformance product.
//! The native code owns the shared contract execution; Java owns Activity lifecycle only.

/// Returns the shared contract report to this host's conformance Activity.
#[cfg(target_os = "android")]
// This host owns the one exported JNI symbol; shared crates remain unsafe-free.
#[allow(unsafe_code)]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_media_conformance_MediaStreamConformanceActivity_runNativeConformanceReport(
    mut environment: jni::EnvUnowned<'_>,
    _class: jni::objects::JClass<'_>,
) -> jni::sys::jstring {
    let report = run_conformance_report();
    environment
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            env.new_string(report).map(|value| value.into_raw())
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

/// Serializes the shared deterministic conformance report, including a valid
/// structured error report if the shared harness rejects a contract.
pub fn run_conformance_report() -> String {
    rusty_quest_media_stream_android::run_synthetic_android_conformance()
        .and_then(|report| {
            serde_json::to_string(&report).map_err(|_| {
                rusty_quest_media_stream_android::AndroidMediaContractError::InvalidProviderEvidence
            })
        })
        .unwrap_or_else(|error| {
            serde_json::json!({
                "$schema": "rusty.quest.android.media.conformance.v1",
                "result": "fail", "error": error.to_string()
            })
            .to_string()
        })
}

#[cfg(test)]
mod tests {
    use super::run_conformance_report;
    #[test]
    fn shared_conformance_report_is_a_successful_complete_report() {
        let report: serde_json::Value =
            serde_json::from_str(&run_conformance_report()).expect("JSON report");
        assert_eq!(
            report["$schema"],
            "rusty.quest.android.media.conformance.v1"
        );
        assert_eq!(report["exact_pair_contract_passed"], true);
        assert_eq!(report["generation_lease_contract_passed"], true);
        assert_eq!(report["exactly_once_release_passed"], true);
        assert_eq!(report["external_effects"], serde_json::json!([]));
    }
}
