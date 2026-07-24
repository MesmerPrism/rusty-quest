//! Android JNI projection into the source-only Quest Fleet Agent contract.

use rusty_quest_fleet_agent::{
    produce_signed_checkin, QuestFleetAgentError, QuestFleetAgentProfile, QuestPlatformSnapshot,
};
use serde::Serialize;

#[derive(Serialize)]
struct NativeProductionResult {
    schema: &'static str,
    status: &'static str,
    envelope: Option<fleet_contracts_compat::SignedEnvelope>,
    error: Option<QuestFleetAgentError>,
}

mod fleet_contracts_compat {
    pub type SignedEnvelope = serde_json::Value;
}

/// Produces a JSON result for host tests and the JNI adapter.
pub fn produce_for_host_test(
    profile_json: &str,
    snapshot_json: &str,
    private_seed: &[u8],
    issued_at_ms: i64,
) -> String {
    let result = produce(profile_json, snapshot_json, private_seed, issued_at_ms);
    serde_json::to_string(&result).unwrap_or_else(|_| {
        "{\"schema\":\"rusty.quest.fleet_agent_native_result.v1\",\"status\":\"error\",\"envelope\":null,\"error\":{\"code\":\"result_serialization_failed\",\"message\":\"native result serialization failed\"}}".to_owned()
    })
}

fn produce(
    profile_json: &str,
    snapshot_json: &str,
    private_seed: &[u8],
    issued_at_ms: i64,
) -> NativeProductionResult {
    let result = (|| {
        let profile: QuestFleetAgentProfile =
            serde_json::from_str(profile_json).map_err(|error| QuestFleetAgentError {
                code: "profile_parse_failed".to_owned(),
                message: error.to_string(),
            })?;
        let snapshot: QuestPlatformSnapshot =
            serde_json::from_str(snapshot_json).map_err(|error| QuestFleetAgentError {
                code: "snapshot_parse_failed".to_owned(),
                message: error.to_string(),
            })?;
        let mut seed: [u8; 32] = private_seed.try_into().map_err(|_| QuestFleetAgentError {
            code: "invalid_private_seed".to_owned(),
            message: "app-private signing seed must be exactly 32 bytes".to_owned(),
        })?;
        let produced = produce_signed_checkin(&profile, &snapshot, &seed, issued_at_ms);
        seed.fill(0);
        let envelope = produced?;
        serde_json::to_value(envelope).map_err(|error| QuestFleetAgentError {
            code: "envelope_serialization_failed".to_owned(),
            message: error.to_string(),
        })
    })();

    match result {
        Ok(envelope) => NativeProductionResult {
            schema: "rusty.quest.fleet_agent_native_result.v1",
            status: "ok",
            envelope: Some(envelope),
            error: None,
        },
        Err(error) => NativeProductionResult {
            schema: "rusty.quest.fleet_agent_native_result.v1",
            status: "error",
            envelope: None,
            error: Some(error),
        },
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_fleetagent_FleetAgentNativeBridge_nativeProduce(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    profile_json: jni::objects::JString,
    snapshot_json: jni::objects::JString,
    private_seed: jni::objects::JByteArray,
    issued_at_ms: jni::sys::jlong,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let profile_json = profile_json.try_to_string(env)?;
            let snapshot_json = snapshot_json.try_to_string(env)?;
            let mut private_seed = env.convert_byte_array(private_seed)?;
            let result =
                produce_for_host_test(&profile_json, &snapshot_json, &private_seed, issued_at_ms);
            private_seed.fill(0);
            env.new_string(result).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_quest_fleet_agent::{derive_key_record, FLEET_AGENT_PACKAGE};
    use serde_json::{json, Value};

    fn profile(enabled: bool) -> String {
        let key = derive_key_record("key.quest.native-test.v1", &[7_u8; 32]);
        json!({
            "schema": "rusty.quest.fleet_agent_profile.v1",
            "enabled": enabled,
            "device_id": "quest.native-test.1",
            "display_name": "Quest Native Test",
            "model": "Quest 3",
            "hardware_class": "standalone_xr",
            "identity_revision": 1,
            "expected_authority_revision": 1,
            "status_revision": 7,
            "source_revision": 7,
            "source_epoch": "agent-native-test-1",
            "key_id": key.key_id,
            "key_fingerprint": key.key_fingerprint,
            "trust_domain": "trust.local",
            "checkin_ttl_ms": 60_000,
            "checkin_interval_ms": 15_000,
            "hub_endpoint": "http://192.168.1.10:8741/fleet/v1/checkins",
            "tags": {"fixture": "jni-host"}
        })
        .to_string()
    }

    fn snapshot() -> String {
        json!({
            "battery_percent": 71,
            "charging": false,
            "agent_lifecycle": "background",
            "participating_application": null
        })
        .to_string()
    }

    #[test]
    fn host_bridge_returns_signed_envelope_without_exposing_seed() {
        let output =
            produce_for_host_test(&profile(true), &snapshot(), &[7_u8; 32], 2_000_000_000_000);
        let value: Value = serde_json::from_str(&output).expect("native result");
        assert_eq!(value["status"], "ok");
        assert_eq!(
            value["envelope"]["claims"]["observation"]["agent"]["package_name"],
            FLEET_AGENT_PACKAGE
        );
        assert!(!output.contains("0707070707070707"));
    }

    #[test]
    fn host_bridge_preserves_inert_profile_and_seed_boundaries() {
        let disabled: Value = serde_json::from_str(&produce_for_host_test(
            &profile(false),
            &snapshot(),
            &[7_u8; 32],
            2_000_000_000_000,
        ))
        .expect("disabled result");
        assert_eq!(disabled["error"]["code"], "agent_disabled");

        let invalid_seed: Value = serde_json::from_str(&produce_for_host_test(
            &profile(true),
            &snapshot(),
            &[7_u8; 31],
            2_000_000_000_000,
        ))
        .expect("seed result");
        assert_eq!(invalid_seed["error"]["code"], "invalid_private_seed");
    }
}
