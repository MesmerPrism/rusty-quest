//! Stateful JNI boundary for signature-scoped Binder admission.

use rusty_quest_broker_admission::QuestBrokerAdmissionRuntime;
use std::sync::{Mutex, OnceLock};

static ADMISSION_RUNTIME: OnceLock<Mutex<Option<QuestBrokerAdmissionRuntime>>> = OnceLock::new();

fn runtime_slot() -> &'static Mutex<Option<QuestBrokerAdmissionRuntime>> {
    ADMISSION_RUNTIME.get_or_init(|| Mutex::new(None))
}

pub(crate) fn initialize(config_json: &str) -> Result<String, String> {
    let mut slot = runtime_slot()
        .lock()
        .map_err(|_| "admission runtime lock poisoned".to_owned())?;
    let reused = slot.is_some();
    if !reused {
        let runtime = QuestBrokerAdmissionRuntime::from_config_json(config_json)
            .map_err(|error| error.to_string())?;
        *slot = Some(runtime);
    }
    Ok(serde_json::json!({
        "$schema": "rusty.quest.broker.admission_native_status.v1",
        "initialized": true,
        "existing_authority_preserved": reused,
        "decision_owner": "rusty.manifold.admission",
        "local_token_or_grant_policy": false
    })
    .to_string())
}

pub(crate) fn execute(operation_json: &str) -> Result<String, String> {
    let mut slot = runtime_slot()
        .lock()
        .map_err(|_| "admission runtime lock poisoned".to_owned())?;
    let runtime = slot
        .as_mut()
        .ok_or_else(|| "admission runtime not initialized".to_owned())?;
    runtime
        .execute_json(operation_json)
        .map_err(|error| error.to_string())
}

pub(crate) fn snapshot() -> Result<String, String> {
    let slot = runtime_slot()
        .lock()
        .map_err(|_| "admission runtime lock poisoned".to_owned())?;
    let runtime = slot
        .as_ref()
        .ok_or_else(|| "admission runtime not initialized".to_owned())?;
    runtime.snapshot_json().map_err(|error| error.to_string())
}

#[cfg(target_os = "android")]
fn jni_string(
    mut env: jni::EnvUnowned,
    input: jni::objects::JString,
    operation: impl FnOnce(&str) -> Result<String, String>,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let input = input.try_to_string(env)?;
            let response = operation(&input).unwrap_or_default();
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldAdmissionNativeBridge_nativeInitialize(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    config_json: jni::objects::JString,
) -> jni::sys::jstring {
    jni_string(env, config_json, initialize)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldAdmissionNativeBridge_nativeExecute(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    operation_json: jni::objects::JString,
) -> jni::sys::jstring {
    jni_string(env, operation_json, execute)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldAdmissionNativeBridge_nativeSnapshot(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let response = snapshot().unwrap_or_default();
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
    use super::{execute, initialize, snapshot};

    #[test]
    fn stateful_native_boundary_issues_and_persists() {
        let snapshot_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../rusty-manifold/fixtures/admission/initial-snapshot.json");
        let snapshot_json = std::fs::read_to_string(snapshot_path).expect("snapshot");
        let config = serde_json::json!({
            "$schema": "rusty.quest.broker.admission_config.v1",
            "snapshot": serde_json::from_str::<serde_json::Value>(&snapshot_json).expect("json")
        });
        initialize(&config.to_string()).expect("initialize");
        let operation = serde_json::json!({
            "operation": "issue_token",
            "$schema": "rusty.quest.broker.admission_operation.v1",
            "caller": {
                "sending_uid": 10123,
                "package_name": "io.github.mesmerprism.rustymanifold.admission.client",
                "signing_certificate_sha256": "a1".repeat(32)
            },
            "request_id": "request.native.issue",
            "expected_authority_revision": 1,
            "requested_capabilities": ["capability.command.session.list"],
            "requested_token_ttl_ms": 20000,
            "issued_at_ms": 2000,
            "expires_at_ms": 5000,
            "entropy_hex": "07".repeat(32)
        });
        let response = execute(&operation.to_string()).expect("execute");
        assert!(response.contains("\"applied\":true"));
        assert!(snapshot()
            .expect("snapshot")
            .contains("\"authority_revision\":2"));
        let repeated = initialize(&config.to_string()).expect("repeated initialize");
        assert!(repeated.contains("\"existing_authority_preserved\":true"));
        assert!(snapshot()
            .expect("snapshot")
            .contains("\"authority_revision\":2"));
    }
}
