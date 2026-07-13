//! One process-local JNI boundary shared by Binder admission and broker mutation.

use rusty_quest_broker_authority::QuestBrokerRuntimeProvider;
use std::sync::{Mutex, OnceLock};

static RUNTIME_PROVIDER: OnceLock<Mutex<QuestBrokerRuntimeProvider>> = OnceLock::new();

fn provider() -> &'static Mutex<QuestBrokerRuntimeProvider> {
    RUNTIME_PROVIDER.get_or_init(|| Mutex::new(QuestBrokerRuntimeProvider::default()))
}

pub(crate) fn initialize(
    config_json: &str,
    expected_config_sha256: &str,
    epoch_entropy_hex: &str,
) -> Result<String, String> {
    let mut provider = provider()
        .lock()
        .map_err(|_| "broker runtime lock poisoned".to_owned())?;
    let status = provider
        .initialize(config_json, expected_config_sha256, epoch_entropy_hex)
        .map_err(|error| error.to_string())?;
    serde_json::to_string(&status).map_err(|error| error.to_string())
}

pub(crate) fn execute_admission(operation_json: &str) -> Result<String, String> {
    provider()
        .lock()
        .map_err(|_| "broker runtime lock poisoned".to_owned())?
        .execute_admission_json(operation_json)
        .map_err(|error| error.to_string())
}

pub(crate) fn mutate(mutation_json: &str, now_ms: u64) -> Result<String, String> {
    provider()
        .lock()
        .map_err(|_| "broker runtime lock poisoned".to_owned())?
        .handle_server_mutation_json(mutation_json, now_ms)
        .map_err(|error| error.to_string())
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) fn complete_media_action(completion_json: &str, now_ms: u64) -> Result<String, String> {
    provider()
        .lock()
        .map_err(|_| "broker runtime lock poisoned".to_owned())?
        .complete_media_action_json(completion_json, now_ms)
        .map_err(|error| error.to_string())
}

pub(crate) fn admission_snapshot() -> Result<String, String> {
    provider()
        .lock()
        .map_err(|_| "broker runtime lock poisoned".to_owned())?
        .admission_snapshot_json()
        .map_err(|error| error.to_string())
}

pub(crate) fn evidence() -> Result<String, String> {
    provider()
        .lock()
        .map_err(|_| "broker runtime lock poisoned".to_owned())?
        .evidence_json()
        .map_err(|error| error.to_string())
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
fn jni_initialize(
    mut env: jni::EnvUnowned,
    config_json: jni::objects::JString,
    expected_config_sha256: jni::objects::JString,
    epoch_entropy_hex: jni::objects::JString,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let config_json = config_json.try_to_string(env)?;
            let expected_config_sha256 = expected_config_sha256.try_to_string(env)?;
            let epoch_entropy_hex = epoch_entropy_hex.try_to_string(env)?;
            let response = initialize(&config_json, &expected_config_sha256, &epoch_entropy_hex)
                .unwrap_or_default();
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
fn jni_empty_string(
    mut env: jni::EnvUnowned,
    operation: impl FnOnce() -> Result<String, String>,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let response = operation().unwrap_or_default();
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
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldRuntimeAuthorityBridge_nativeInitialize(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    config_json: jni::objects::JString,
    expected_config_sha256: jni::objects::JString,
    epoch_entropy_hex: jni::objects::JString,
) -> jni::sys::jstring {
    jni_initialize(env, config_json, expected_config_sha256, epoch_entropy_hex)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldRuntimeAuthorityBridge_nativeMutate(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    mutation_json: jni::objects::JString,
    now_ms: jni::sys::jlong,
) -> jni::sys::jstring {
    jni_string(env, mutation_json, |json| {
        u64::try_from(now_ms)
            .map_err(|_| "negative mutation clock".to_owned())
            .and_then(|now| mutate(json, now))
    })
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldRuntimeAuthorityBridge_nativeCompleteMediaAction(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    completion_json: jni::objects::JString,
    now_ms: jni::sys::jlong,
) -> jni::sys::jstring {
    jni_string(env, completion_json, |json| {
        u64::try_from(now_ms)
            .map_err(|_| "negative media completion clock".to_owned())
            .and_then(|now| complete_media_action(json, now))
    })
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldRuntimeAuthorityBridge_nativeEvidence(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    jni_empty_string(env, evidence)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldAdmissionNativeBridge_nativeInitialize(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    config_json: jni::objects::JString,
    expected_config_sha256: jni::objects::JString,
    epoch_entropy_hex: jni::objects::JString,
) -> jni::sys::jstring {
    jni_initialize(env, config_json, expected_config_sha256, epoch_entropy_hex)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldAdmissionNativeBridge_nativeExecute(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    operation_json: jni::objects::JString,
) -> jni::sys::jstring {
    jni_string(env, operation_json, execute_admission)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustymanifold_broker_ManifoldAdmissionNativeBridge_nativeSnapshot(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    jni_empty_string(env, admission_snapshot)
}

#[cfg(test)]
mod tests {
    use super::{
        admission_snapshot, complete_media_action, evidence, execute_admission, initialize, mutate,
    };

    fn runtime_config() -> serde_json::Value {
        let manifold_root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../rusty-manifold");
        let quest_root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let product_spec_json = std::fs::read_to_string(
            manifold_root.join("fixtures/broker-product/media-session-standalone.json"),
        )
        .expect("product spec");
        let product_lock_json = std::fs::read_to_string(
            manifold_root.join("fixtures/broker-product/media-session-standalone.lock.json"),
        )
        .expect("product lock");
        let product_lock: serde_json::Value =
            serde_json::from_str(&product_lock_json).expect("product lock json");
        let media_binding_json = std::fs::read_to_string(
            quest_root
                .join("fixtures/media-runtime-products/spatial-camera-panel-display.binding.json"),
        )
        .expect("media binding");
        let media_binding: serde_json::Value =
            serde_json::from_str(&media_binding_json).expect("media binding json");
        let client_lock_json = std::fs::read_to_string(
            quest_root.join("fixtures/broker-clients/spatial-camera-panel.client.json"),
        )
        .expect("client lock");
        let client_lock: serde_json::Value =
            serde_json::from_str(&client_lock_json).expect("client lock json");
        let media_lifecycle_lock_json = std::fs::read_to_string(
            quest_root.join("fixtures/broker-clients/spatial-camera-panel.media-lifecycle.json"),
        )
        .expect("media lifecycle lock");
        let media_lifecycle_lock: serde_json::Value =
            serde_json::from_str(&media_lifecycle_lock_json).expect("media lifecycle lock json");
        let app_feature_lock_json = std::fs::read_to_string(quest_root.join(
            "apps/spatial-camera-panel-android/morphospace/conformance-locks/broker-media-client.feature.lock.json",
        ))
        .expect("app feature lock");
        let client_lock_sha256 =
            rusty_quest_broker_authority::packaged_json_sha256(&client_lock_json);
        let admission_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../rusty-manifold/fixtures/admission/initial-snapshot.json");
        let mut admission: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(admission_path).expect("admission"))
                .expect("admission json");
        admission["grants"][0]["identity"]["client_id"] = client_lock["client_id"].clone();
        admission["grants"][0]["identity"]["platform_subject"] =
            client_lock["package_name"].clone();
        admission["grants"][0]["client_lock_id"] = client_lock["feature_lock_id"].clone();
        admission["grants"][0]["client_lock_fingerprint"] =
            serde_json::json!(format!("sha256:{client_lock_sha256}"));
        admission["grants"][0]["capabilities"] = serde_json::json!([
            "capability.command.media.session.start",
            "capability.command.media.session.stop",
            "capability.command.session.list",
            "capability.media.session.observe",
            "capability.peer.session.observe",
            "capability.sink.spatial-sdk"
        ]);
        serde_json::json!({
            "$schema": "rusty.quest.broker.runtime_config.v1",
            "bridge_kind": "standalone_process_jni",
            "adapter_config": {
                "$schema": "rusty.manifold.broker.adapter_config.v2",
                "adapter_id": "adapter.quest.parity.standalone",
                "mode": "standalone",
                "product_lock_id": product_lock["lock_id"].clone(),
                "product_lock_fingerprint": product_lock["spec_fingerprint"].clone(),
                "product_lock_sha256": format!(
                    "sha256:{}",
                    rusty_quest_broker_authority::packaged_json_sha256(&product_lock_json)
                ),
                "authority_host_id": "host.quest.parity",
                "authority_owner_id": "module.runtime.host"
            },
            "product_lock": product_lock,
            "packaged_authority": {
                "product_spec_sha256": rusty_quest_broker_authority::packaged_json_sha256(&product_spec_json),
                "product_spec_json": product_spec_json,
                "product_lock_sha256": rusty_quest_broker_authority::packaged_json_sha256(&product_lock_json),
                "product_lock_json": product_lock_json,
                "client_locks": [{
                    "grant_id": "grant.quest.authorized",
                    "client_lock_sha256": client_lock_sha256,
                    "client_lock_json": client_lock_json,
                    "media_lifecycle_authority": {
                        "media_lifecycle_lock_sha256": rusty_quest_broker_authority::packaged_json_sha256(&media_lifecycle_lock_json),
                        "media_lifecycle_lock_json": media_lifecycle_lock_json,
                        "app_feature_lock_sha256": rusty_quest_broker_authority::packaged_json_sha256(&app_feature_lock_json),
                        "app_feature_lock_json": app_feature_lock_json,
                        "media_binding_sha256": rusty_quest_broker_authority::packaged_json_sha256(&media_binding_json),
                        "media_binding_json": media_binding_json
                    }
                }]
            },
            "initial_leases": [{
                "lease_id": media_lifecycle_lock["broker_runtime_lease_id"].clone(),
                "scope": "lease.media.session",
                "holder_id": client_lock["client_id"].clone(),
                "expires_at_ms": 60000
            }],
            "admission": {
                "$schema": "rusty.quest.broker.admission_config.v1",
                "snapshot": admission
            },
            "media_session": media_binding
        })
    }

    #[test]
    fn binder_and_server_entrypoint_share_state_and_rebind_continuity() {
        let config = runtime_config().to_string();
        let config_sha256 = rusty_quest_broker_authority::canonical_runtime_config_sha256(&config)
            .expect("config digest");
        let status: serde_json::Value = serde_json::from_str(
            &initialize(&config, &config_sha256, &"11".repeat(32)).expect("initialize"),
        )
        .expect("status");
        let epoch = status["provider_epoch_id"].as_str().expect("epoch");
        assert_eq!(status["existing_authority_preserved"], false);

        let issue = serde_json::json!({
            "operation": "issue_token",
            "$schema": "rusty.quest.broker.admission_operation.v1",
            "caller": {
                "sending_uid": 10123,
                "package_name": "io.github.mesmerprism.rustyquest.spatial_camera_panel",
                "signing_certificate_sha256": "a1".repeat(32)
            },
            "request_id": "request.native.issue",
            "expected_authority_revision": 1,
            "requested_capabilities": ["capability.command.media.session.start"],
            "requested_token_ttl_ms": 20000,
            "issued_at_ms": 2000,
            "expires_at_ms": 10000,
            "entropy_hex": "07".repeat(32)
        });
        let issue_response: serde_json::Value =
            serde_json::from_str(&execute_admission(&issue.to_string()).expect("issue"))
                .expect("issue response");
        let token = issue_response["receipt"]["token"]["token_id"]
            .as_str()
            .expect("token");
        assert_eq!(issue_response["provider_epoch_id"], epoch);
        assert_eq!(issue_response["runtime_host_revision"], 1);
        let use_ = serde_json::json!({
            "operation": "authorize_use",
            "$schema": "rusty.quest.broker.admission_operation.v1",
            "caller": issue["caller"].clone(),
            "request_id": "request.native.use",
            "expected_authority_revision": 2,
            "token_id": token,
            "capability_id": "capability.command.media.session.start",
            "issued_at_ms": 3000,
            "expires_at_ms": 9000
        });
        assert!(execute_admission(&use_.to_string())
            .expect("use")
            .contains("\"applied\":true"));

        let invocation_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../..")
            .join("fixtures/broker-authority/standalone-applied.invocation.json");
        let invocation: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(invocation_path).expect("invocation"))
                .expect("invocation json");
        let effect_params: rusty_quest_broker_authority::QuestBrokerEffectParams =
            serde_json::from_value(serde_json::json!({
                "$schema": "rusty.quest.broker.effect_params.v1",
                "command_id": "command.media.session.start",
                "values": {}
            }))
            .expect("effect params");
        let params_digest =
            rusty_quest_broker_authority::canonical_effect_params_digest(&effect_params)
                .expect("params digest");
        let mut command = invocation["request"].clone();
        command["requester_id"] = serde_json::json!("client.quest.spatial-camera-panel");
        command["lease_id"] =
            serde_json::json!("lease.broker.media-session.client.quest.spatial-camera-panel");
        command["params_digest"] = serde_json::to_value(params_digest).expect("params digest json");
        let mutation = serde_json::json!({
            "$schema": "rusty.quest.broker.server_mutation_request.v1",
            "bridge_kind": "standalone_process_jni",
            "provider_epoch_id": epoch,
            "admission_use_request_id": "request.native.use",
            "token_id": token,
            "expected_admission_authority_revision": 3,
            "command": command,
            "params": effect_params
        });
        let response = mutate(&mutation.to_string(), 4_000).expect("mutation");
        let response: serde_json::Value = serde_json::from_str(&response).expect("response json");
        assert_eq!(response["accepted"], true);
        assert_eq!(response["platform_effect_completed"], false);
        assert_eq!(
            response["platform_action"]["owner_actions"]
                .as_array()
                .map(Vec::len),
            Some(7)
        );
        let completion = complete_media_action(
            &serde_json::json!({"client_id": "client.quest.spatial-camera-panel"}).to_string(),
            5_000,
        )
        .expect("complete media action");
        let completion: serde_json::Value =
            serde_json::from_str(&completion).expect("completion json");
        assert_eq!(
            completion["$schema"],
            "rusty.quest.broker.media_completion_response.v1"
        );
        assert_eq!(completion["platform_effect_completed"], true);
        assert_eq!(
            completion["owner_receipts"].as_array().map(Vec::len),
            Some(7)
        );
        assert!(evidence()
            .expect("evidence")
            .contains("\"authority_revision\":2"));
        assert!(admission_snapshot()
            .expect("admission snapshot")
            .contains("\"authority_revision\":3"));

        let rebound: serde_json::Value = serde_json::from_str(
            &initialize(&config, &config_sha256, &"22".repeat(32)).expect("rebind"),
        )
        .expect("rebind status");
        assert_eq!(rebound["existing_authority_preserved"], true);
        assert_eq!(rebound["provider_epoch_id"], epoch);
        assert_eq!(rebound["runtime_host_revision"], 2);
    }
}
