//! Closed native failure data. No string-message inference or transport authority.
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub(super) const EXCEPTION_CLASS: &str = "io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexNativeOperationFailure";
const SCHEMA: &str = "rusty.quest.embedded_duplex.operation_failure.v1";
const CURRENT_SCHEMA: &str = "rusty.manifold.peer.session_current_receipt.v2";

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Failure {
    #[serde(rename = "$schema")]
    schema: String,
    code: String,
    operation: String,
    session_id: Option<String>,
    observed_at_ms: Option<u64>,
    current_session: Option<Value>,
}

fn operation(value: &str) -> bool {
    matches!(
        value,
        "runtime_command"
            | "pair_ceremony"
            | "pair_status"
            | "renew_authority"
            | "fresh_pair_setup"
            | "fresh_pair_continue"
            | "reestablish_peer_roots"
            | "prepare_peer_start"
            | "abort_prepared_peer_start"
    )
}

fn valid(value: &Failure) -> bool {
    if value.schema != SCHEMA || !operation(&value.operation) {
        return false;
    }
    match value.code.as_str() {
        "rejected_unknown" => {
            value.session_id.is_none()
                && value.observed_at_ms.is_none()
                && value.current_session.is_none()
        }
        "pair_session_not_current" => {
            let Some(receipt) = value.current_session.as_ref() else {
                return false;
            };
            // The actual public owner's closed receipt decoder checks field shapes.
            if serde_json::from_value::<rusty_manifold_peer::ManifoldPeerSessionCurrentReceiptV2>(
                receipt.clone(),
            )
            .is_err()
            {
                return false;
            }
            receipt.get("$schema").and_then(Value::as_str) == Some(CURRENT_SCHEMA)
                && receipt.get("current").and_then(Value::as_bool) == Some(false)
                && value.session_id.as_deref() == receipt.get("session_id").and_then(Value::as_str)
                && value.session_id.is_some()
                && value.observed_at_ms == receipt.get("validated_at_ms").and_then(Value::as_u64)
                && matches!(
                    receipt.get("rejection_reason").and_then(Value::as_str),
                    Some(
                        "not_found"
                            | "revoked"
                            | "expired"
                            | "peer_not_current"
                            | "rendezvous_not_current"
                            | "topology_mismatch"
                    )
                )
                && matches!(
                    value.operation.as_str(),
                    "renew_authority"
                        | "fresh_pair_setup"
                        | "fresh_pair_continue"
                        | "reestablish_peer_roots"
                )
        }
        _ => false,
    }
}

pub(super) fn session_not_current(
    operation: &str,
    session: &str,
    receipt: Value,
) -> Result<String, String> {
    let value = Failure {
        schema: SCHEMA.into(),
        code: "pair_session_not_current".into(),
        operation: operation.into(),
        session_id: Some(session.into()),
        observed_at_ms: receipt.get("validated_at_ms").and_then(Value::as_u64),
        current_session: Some(receipt),
    };
    if !valid(&value) {
        return Err("typed current-session failure invalid".into());
    }
    serde_json::to_string(&value).map_err(|_| "typed failure encode rejected".into())
}

pub(super) fn current_cause(encoded: &str) -> Option<Value> {
    // Only this deliberately closed representation may cross the typed boundary.
    if encoded.len() > 32 * 1024 {
        return None;
    }
    let raw: Value = serde_json::from_str(encoded).ok()?;
    let fields = raw.as_object()?;
    if fields.len() != 6
        || [
            "$schema",
            "code",
            "operation",
            "session_id",
            "observed_at_ms",
            "current_session",
        ]
        .iter()
        .any(|key| !fields.contains_key(*key))
    {
        return None;
    }
    let value: Failure = serde_json::from_value(raw).ok()?;
    if !valid(&value) {
        return None;
    }
    serde_json::to_value(value).ok()
}

pub(super) fn jni_payload(requested_operation: Option<&str>, error: &str) -> String {
    let selected = requested_operation
        .filter(|value| operation(value))
        .unwrap_or("runtime_command");
    if let Some(value) = current_cause(error) {
        if value.get("operation").and_then(Value::as_str) == Some(selected) {
            return value.to_string();
        }
    }
    // No raw native/provider/config/exception text is included in the fallback.
    serde_json::to_string(&Failure {
        schema: SCHEMA.into(),
        code: "rejected_unknown".into(),
        operation: selected.into(),
        session_id: None,
        observed_at_ms: None,
        current_session: None,
    })
    .expect("fixed native failure serialization")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn receipt(reason: &str, time: u64) -> Value {
        json!({"$schema":CURRENT_SCHEMA,"session_id":"session.fixture","current":false,"rejection_reason":reason,"decision_id":null,"peer_ids":[],"topology_contract_id":null,"validated_at_ms":time,"expires_at_ms":null})
    }
    #[test]
    fn exact_native_receipt_reasons_and_original_time_survive_boundary() {
        for reason in [
            "not_found",
            "revoked",
            "expired",
            "peer_not_current",
            "rendezvous_not_current",
            "topology_mismatch",
        ] {
            for time in [0, 100, u64::MAX] {
                let original = receipt(reason, time);
                let error =
                    session_not_current("renew_authority", "session.fixture", original.clone())
                        .unwrap();
                let value: Value =
                    serde_json::from_str(&jni_payload(Some("renew_authority"), &error)).unwrap();
                assert_eq!(value["current_session"], original);
                assert_eq!(value["observed_at_ms"], json!(time));
            }
        }
    }
    #[test]
    fn ordinary_errors_and_foreign_operation_never_guess_expiry() {
        for error in [
            "expired",
            "provider revoked secret/address",
            "{\"code\":\"expired\"}",
        ] {
            let value: Value =
                serde_json::from_str(&jni_payload(Some("pair_ceremony"), error)).unwrap();
            assert_eq!(value["code"], "rejected_unknown");
            assert!(value["current_session"].is_null());
            assert!(value["observed_at_ms"].is_null());
            assert!(!value.to_string().contains("secret/address"));
        }
        let error =
            session_not_current("renew_authority", "session.fixture", receipt("expired", 17))
                .unwrap();
        assert!(
            serde_json::from_str::<Value>(&jni_payload(Some("pair_ceremony"), &error)).unwrap()
                ["current_session"]
                .is_null()
        );
    }
    #[test]
    fn typed_lookalikes_refuse_the_precise_schema_subject_reason_and_time() {
        let error =
            session_not_current("renew_authority", "session.fixture", receipt("expired", 17))
                .unwrap();
        let original: Value = serde_json::from_str(&error).unwrap();
        for path in ["schema", "subject", "reason", "time", "current", "extra"] {
            let mut damaged = original.clone();
            match path {
                "schema" => damaged["current_session"]["$schema"] = json!("foreign.v3"),
                "subject" => damaged["session_id"] = json!("session.foreign"),
                "reason" => {
                    damaged["current_session"]["rejection_reason"] =
                        json!("fresh_setup_fence_expired")
                }
                "time" => damaged["observed_at_ms"] = json!(18),
                "current" => damaged["current_session"]["current"] = json!(true),
                _ => damaged["unapproved"] = json!(true),
            }
            assert!(current_cause(&damaged.to_string()).is_none(), "{path}");
        }
    }
}
