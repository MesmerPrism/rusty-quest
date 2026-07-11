//! Standalone Android JNI bridge to the shared Manifold authority evaluator.

/// Host-testable evaluator used by the JNI boundary.
pub fn evaluate_for_host_test(invocation_json: &str) -> Result<String, String> {
    rusty_quest_broker_authority::evaluate_authority_json(invocation_json)
        .map_err(|error| error.to_string())
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldRuntimeAuthorityBridge_nativeEvaluate(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    invocation_json: jni::objects::JString,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let invocation_json = invocation_json.try_to_string(env)?;
            let response = evaluate_for_host_test(&invocation_json).unwrap_or_default();
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::evaluate_for_host_test;

    #[test]
    fn standalone_native_boundary_preserves_manifold_response() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../..")
            .join("fixtures/broker-authority/standalone-applied.invocation.json");
        let invocation = std::fs::read_to_string(path).expect("fixture");
        let response = evaluate_for_host_test(&invocation).expect("response");
        assert!(response.contains("rusty.quest.broker.authority_response.v1"));
        assert!(response.contains("\"decision_owner_id\":\"module.runtime.host\""));
        assert!(response.contains("\"local_acceptance_rules\":false"));
    }
}
