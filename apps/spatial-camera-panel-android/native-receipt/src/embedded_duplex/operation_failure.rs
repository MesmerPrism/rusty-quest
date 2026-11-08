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

// These variants describe the actual failing call site, never its error text.
#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub(super) enum FreshStage {
    HostLookup,
    Clock,
    Input,
    Flight,
    Initiator,
    AllocateIdentity,
    CurrentPhase,
    StageSuccessor,
    ArchivePrior,
    Begin,
    BeginCapability,
    BeginEligibility,
    BeginPhysical,
    BeginBinding,
    BeginReservation,
    BeginAuthority,
    ViewExchange,
    Context,
    FinishPhase,
    ArchiveReceipt,
    ArchiveRenewal,
    ArchiveJava,
    ArchiveAck,
    ArchiveHistory,
    ArchiveLifetime,
    CurrentValidation,
}
impl FreshStage {
    fn owner(self) -> &'static str {
        match self {
            Self::ArchiveJava | Self::BeginPhysical => "quest_java",
            Self::ArchiveReceipt | Self::ArchiveHistory | Self::BeginAuthority => "manifold",
            _ => "quest_native",
        }
    }
}
#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub(super) enum FreshReason {
    OwnerRejected,
    GuardRefused,
    ReceiptMismatch,
    BindingMismatch,
}
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct FreshSubject {
    pub request_id: Option<String>,
    pub phase_id: Option<String>,
    pub original_id: Option<String>,
    pub app_generation: Option<u64>,
    pub native_generation: Option<u64>,
    pub app_record_sha256: Option<String>,
    pub config_sha256: Option<String>,
}
#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct FreshDiagnostic {
    owner: String,
    stage: FreshStage,
    reason: FreshReason,
    #[serde(flatten)]
    subject: FreshSubject,
}
#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct FreshFailure {
    #[serde(rename = "$schema")]
    schema: String,
    code: String,
    operation: String,
    diagnostic: FreshDiagnostic,
}
fn closed(value: &Value, keys: &[&str]) -> bool {
    value
        .as_object()
        .is_some_and(|o| o.len() == keys.len() && keys.iter().all(|k| o.contains_key(*k)))
}
fn hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
fn digest(value: &str) -> bool {
    hex(value.strip_prefix("sha256:").unwrap_or(value), 64)
}
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value.as_bytes()[0].is_ascii_lowercase()
        && value
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || b"_.-".contains(&c))
}
fn fresh_cause(raw: Value) -> Option<Value> {
    if !closed(&raw, &["$schema", "code", "operation", "diagnostic"])
        || !closed(
            &raw["diagnostic"],
            &[
                "owner",
                "stage",
                "reason",
                "request_id",
                "phase_id",
                "original_id",
                "app_generation",
                "native_generation",
                "app_record_sha256",
                "config_sha256",
            ],
        )
    {
        return None;
    }
    let value: FreshFailure = serde_json::from_value(raw).ok()?;
    let d = &value.diagnostic;
    let s = &d.subject;
    if value.schema != "rusty.quest.embedded_duplex.operation_failure.v2"
        || value.code != "fresh_setup_rejected"
        || value.operation != "fresh_pair_setup"
        || d.owner != d.stage.owner()
        || s.request_id.as_deref().is_some_and(|v| !identifier(v))
        || s.phase_id.as_deref().is_some_and(|v| !identifier(v))
        || s.original_id.as_deref().is_some_and(|v| !hex(v, 32))
        || s.app_generation
            .is_some_and(|v| v == 0 || v > i64::MAX as u64)
        || s.native_generation
            .is_some_and(|v| v == 0 || v > i64::MAX as u64)
        || s.app_record_sha256.as_deref().is_some_and(|v| !hex(v, 64))
        || s.config_sha256.as_deref().is_some_and(|v| !digest(v))
    {
        return None;
    }
    serde_json::to_value(value).ok()
}
pub(super) fn fresh_rejection(
    error: &str,
    stage: FreshStage,
    reason: FreshReason,
    subject: FreshSubject,
) -> String {
    // Keep an earlier precise rejection or the existing actual current-session receipt.
    if let Some(mut value) = current_cause(error) {
        if value["operation"] == "fresh_pair_setup" {
            // A caller may already own the exact validated Original while the
            // inner failure site knows only its native subject. Retain that
            // site's stage; enrich only the same complete request/actor tuple.
            if value["$schema"] == "rusty.quest.embedded_duplex.operation_failure.v2"
                && value["diagnostic"]["original_id"].is_null()
                && subject.original_id.is_some()
                && subject.request_id.is_some()
                && subject.native_generation.is_some()
            {
                if let Ok(known) = serde_json::to_value(&subject) {
                    if [
                        "request_id",
                        "phase_id",
                        "app_generation",
                        "native_generation",
                        "app_record_sha256",
                        "config_sha256",
                    ]
                    .iter()
                    .all(|key| value["diagnostic"][*key] == known[*key])
                    {
                        value["diagnostic"]["original_id"] = known["original_id"].clone();
                        if let Some(validated) = fresh_cause(value) {
                            return validated.to_string();
                        }
                    }
                }
            }
            return error.to_owned();
        }
    }
    let value = FreshFailure {
        schema: "rusty.quest.embedded_duplex.operation_failure.v2".into(),
        code: "fresh_setup_rejected".into(),
        operation: "fresh_pair_setup".into(),
        diagnostic: FreshDiagnostic {
            owner: stage.owner().into(),
            stage,
            reason,
            subject,
        },
    };
    let encoded = serde_json::to_string(&value).expect("fixed fresh failure serialization");
    if current_cause(&encoded).is_some() {
        encoded
    } else {
        jni_payload(Some("fresh_pair_setup"), "")
    }
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
    if raw.get("$schema").and_then(Value::as_str)
        == Some("rusty.quest.embedded_duplex.operation_failure.v2")
    {
        serde_json::from_str::<FreshFailure>(encoded).ok()?; // Reject duplicate typed fields too.
        return fresh_cause(raw);
    }
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
            return value.to_string(); // Only validated fields cross JNI, never shadowed input bytes.
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
    fn diagnostic_fixture() -> Value {
        json!({"$schema":"rusty.quest.embedded_duplex.operation_failure.v2",
            "code":"fresh_setup_rejected","operation":"fresh_pair_setup",
            "diagnostic":{"owner":"quest_native","stage":"stage_successor","reason":"owner_rejected",
                "request_id":"pair.finite.fixture","phase_id":null,"original_id":null,
                "app_generation":7,"native_generation":9,"app_record_sha256":"a".repeat(64),
                "config_sha256":"sha256:".to_owned()+&"b".repeat(64)}})
    }
    #[test]
    fn fresh_setup_diagnostic_survives_jni_without_raw_error_inference() {
        let value = diagnostic_fixture();
        assert_eq!(current_cause(&value.to_string()), Some(value.clone()));
        assert_eq!(
            serde_json::from_str::<Value>(&jni_payload(
                Some("fresh_pair_setup"),
                &value.to_string()
            ))
            .unwrap(),
            value
        );
        assert_eq!(
            serde_json::from_str::<Value>(&jni_payload(Some("pair_ceremony"), &value.to_string()))
                .unwrap()["code"],
            "rejected_unknown"
        );
        assert_eq!(
            serde_json::from_str::<Value>(&jni_payload(
                Some("fresh_pair_setup"),
                "stage_successor secret"
            ))
            .unwrap()["code"],
            "rejected_unknown"
        );
    }
    #[test]
    fn fresh_diagnostic_rejects_open_fields_invalid_identity_and_unbounded_input() {
        let original = diagnostic_fixture();
        for key in [
            "owner",
            "stage",
            "reason",
            "request_id",
            "phase_id",
            "original_id",
            "app_generation",
            "native_generation",
            "app_record_sha256",
            "config_sha256",
            "extra",
        ] {
            let mut value = original.clone();
            value["diagnostic"][key] = json!("secret/raw/invalid");
            assert!(current_cause(&value.to_string()).is_none(), "{key}");
        }
        let mut missing = original.clone();
        missing["diagnostic"]
            .as_object_mut()
            .unwrap()
            .remove("phase_id");
        assert!(current_cause(&missing.to_string()).is_none());
        let mut huge = original.clone();
        huge["diagnostic"]["request_id"] = json!("a".repeat(33000));
        assert!(current_cause(&huge.to_string()).is_none());
    }
    #[test]
    fn actual_site_constructor_preserves_first_stage_and_actual_current_receipt() {
        let encoded = fresh_rejection(
            "secret provider text",
            FreshStage::ArchiveJava,
            FreshReason::OwnerRejected,
            FreshSubject::default(),
        );
        let value = current_cause(&encoded).unwrap();
        assert_eq!(value["diagnostic"]["stage"], "archive_java");
        assert_eq!(value["diagnostic"]["owner"], "quest_java");
        assert!(value["diagnostic"]["request_id"].is_null());
        assert!(!encoded.contains("secret"));
        assert_eq!(
            fresh_rejection(
                &encoded,
                FreshStage::ArchivePrior,
                FreshReason::OwnerRejected,
                FreshSubject::default()
            ),
            encoded
        );
        let prior =
            session_not_current("fresh_pair_setup", "session.fixture", receipt("expired", 0))
                .unwrap();
        assert_eq!(
            fresh_rejection(
                &prior,
                FreshStage::FinishPhase,
                FreshReason::OwnerRejected,
                FreshSubject::default()
            ),
            prior
        );
    }
    #[test]
    fn diagnostic_cross_language_integer_and_duplicate_key_bounds_are_closed() {
        for n in [0u64, (i64::MAX as u64) + 1, u64::MAX] {
            let mut value = diagnostic_fixture();
            value["diagnostic"]["native_generation"] = json!(n);
            assert!(current_cause(&value.to_string()).is_none());
        }
        let raw = diagnostic_fixture()
            .to_string()
            .replace("\"owner\":", "\"owner\":\"quest_native\",\"owner\":");
        assert!(current_cause(&raw).is_none());
    }
    #[test]
    fn shadowed_v1_bytes_never_escape_the_validated_jni_boundary() {
        let original =
            session_not_current("fresh_pair_setup", "session.fixture", receipt("expired", 0))
                .unwrap();
        for (key, hidden) in [
            ("operation", "secret/address"),
            ("decision_id", "secret/provider"),
        ] {
            let needle = format!("\"{key}\":");
            let shadow = original.replacen(&needle, &format!("\"{key}\":\"{hidden}\",{needle}"), 1);
            let emitted = jni_payload(Some("fresh_pair_setup"), &shadow);
            assert!(!emitted.contains(hidden), "shadowed {key} escaped");
            assert!(current_cause(&emitted).is_some());
        }
    }
    #[test]
    fn caller_owned_original_enriches_only_the_same_exact_native_subject() {
        let encoded = diagnostic_fixture().to_string();
        let subject = FreshSubject {
            request_id: Some("pair.finite.fixture".into()),
            phase_id: None,
            original_id: Some("c".repeat(32)),
            app_generation: Some(7),
            native_generation: Some(9),
            app_record_sha256: Some("a".repeat(64)),
            config_sha256: Some("sha256:".to_owned() + &"b".repeat(64)),
        };
        let enriched = fresh_rejection(
            &encoded,
            FreshStage::ArchivePrior,
            FreshReason::OwnerRejected,
            subject.clone(),
        );
        let value = current_cause(&enriched).unwrap();
        assert_eq!(value["diagnostic"]["stage"], "stage_successor");
        assert_eq!(value["diagnostic"]["original_id"], json!("c".repeat(32)));
        let mut foreign = subject;
        foreign.native_generation = Some(10);
        assert!(current_cause(&fresh_rejection(
            &encoded,
            FreshStage::Begin,
            FreshReason::OwnerRejected,
            foreign
        ))
        .unwrap()["diagnostic"]["original_id"]
            .is_null());
    }
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
