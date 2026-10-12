//! Closed local wire observation only. Returned bytes are not authenticated owner completion.
use serde::Deserialize;
use serde_json::{json, Value};
const MAX_SEQUENCE: u64 = 9_007_199_254_740_991;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Attempt {
    attempt_sequence: u64,
    phase: String,
    outcome: String,
    failure: String,
}
fn parse(text: &str) -> Result<Value, &'static str> {
    if text.len() > 256 {
        return Err("BOUNDS");
    }
    let a: Attempt = serde_json::from_str(text).map_err(|_| "JSON_OR_SHAPE")?;
    if a.attempt_sequence > MAX_SEQUENCE
        || !matches!(
            a.phase.as_str(),
            "NONE"
                | "TARGET_BINDING"
                | "INPUT_BOUNDS"
                | "CONNECT"
                | "WRITE_REQUEST"
                | "READ_RESPONSE_HEADER"
                | "READ_RESPONSE_BODY"
                | "RESPONSE_RETURNED"
        )
    {
        return Err("CLOSED_VALUES");
    }
    let failed = match a.failure.as_str() {
        "TARGET_UNAVAILABLE" => a.phase == "TARGET_BINDING",
        "INPUT_BOUNDS" => a.phase == "INPUT_BOUNDS",
        "CONNECT_FAILURE" => a.phase == "CONNECT",
        "EOF" => matches!(
            a.phase.as_str(),
            "READ_RESPONSE_HEADER" | "READ_RESPONSE_BODY"
        ),
        "FRAME_BOUNDS" => a.phase == "READ_RESPONSE_HEADER",
        "DEADLINE" | "IO" => matches!(
            a.phase.as_str(),
            "CONNECT"
                | "WRITE_REQUEST"
                | "READ_RESPONSE_HEADER"
                | "READ_RESPONSE_BODY"
                | "RESPONSE_RETURNED"
        ),
        "CALLBACK_REJECTED" => a.phase != "NONE" && a.phase != "RESPONSE_RETURNED",
        _ => false,
    };
    let valid = match a.outcome.as_str() {
        "NONE" => a.attempt_sequence == 0 && a.phase == "NONE" && a.failure == "NONE",
        "UNAVAILABLE" => {
            a.attempt_sequence == MAX_SEQUENCE && a.phase == "NONE" && a.failure == "NONE"
        }
        "IN_PROGRESS" => {
            a.attempt_sequence > 0
                && a.phase != "NONE"
                && a.phase != "RESPONSE_RETURNED"
                && a.failure == "NONE"
        }
        "RETURNED" => {
            a.attempt_sequence > 0 && a.phase == "RESPONSE_RETURNED" && a.failure == "NONE"
        }
        "FAILED" => a.attempt_sequence > 0 && failed,
        _ => false,
    };
    if !valid {
        return Err("CLOSED_VALUES");
    }
    Ok(
        json!({"attempt_sequence":a.attempt_sequence,"phase":a.phase,"outcome":a.outcome,"failure":a.failure}),
    )
}
pub(super) fn snapshot(text: &str) -> Value {
    match parse(text) {
        Ok(fields) => {
            json!({"schema":"rusty.quest.embedded_duplex.owner_transport_attempt_native_read.v1",
            "qualification_claimed":false,"response_authentication_claimed":false,
            "strict_parser_status":"AVAILABLE","strict_parser_error":null,"closed_java_fields":fields})
        }
        Err(error) => {
            json!({"schema":"rusty.quest.embedded_duplex.owner_transport_attempt_native_read.v1",
            "qualification_claimed":false,"response_authentication_claimed":false,
            "strict_parser_status":"REJECTED","strict_parser_error":error,"closed_java_fields":null})
        }
    }
}
pub(super) fn unavailable() -> Value {
    json!({"schema":"rusty.quest.embedded_duplex.owner_transport_attempt_native_read.v1",
        "qualification_claimed":false,"response_authentication_claimed":false,
        "strict_parser_status":"UNAVAILABLE","strict_parser_error":"CALL_UNAVAILABLE","closed_java_fields":null})
}
#[cfg(test)]
mod tests {
    use super::*;
    fn v(phase: &str, outcome: &str, failure: &str) -> Value {
        json!({"attempt_sequence":1,"phase":phase,"outcome":outcome,"failure":failure})
    }
    #[test]
    fn finite_transport_outcomes_are_not_completion() {
        for fields in [
            v("CONNECT", "FAILED", "CONNECT_FAILURE"),
            v("READ_RESPONSE_HEADER", "FAILED", "EOF"),
            v("READ_RESPONSE_BODY", "FAILED", "EOF"),
            v("WRITE_REQUEST", "FAILED", "DEADLINE"),
            v("READ_RESPONSE_HEADER", "FAILED", "FRAME_BOUNDS"),
            v("RESPONSE_RETURNED", "RETURNED", "NONE"),
        ] {
            let r = snapshot(&fields.to_string());
            assert_eq!(r["closed_java_fields"], fields);
            assert_eq!(r["qualification_claimed"], false);
            assert_eq!(r["response_authentication_claimed"], false);
        }
    }
    #[test]
    fn unknown_duplicate_and_oversize_never_export_payload() {
        let mut extra = v("CONNECT", "FAILED", "CONNECT_FAILURE");
        extra["secret"] = json!("SECRET_PAYLOAD");
        let duplicate = extra
            .to_string()
            .replacen("{", "{\"attempt_sequence\":2,", 1);
        for t in [extra.to_string(), duplicate, "x".repeat(257), "[]".into()] {
            let r = snapshot(&t);
            assert!(r["closed_java_fields"].is_null());
            assert!(!r.to_string().contains("SECRET_PAYLOAD"));
        }
    }
    #[test]
    fn impossible_combinations_and_counter_damage_rejected() {
        for fields in [
            v("CONNECT", "FAILED", "EOF"),
            v("WRITE_REQUEST", "FAILED", "CONNECT_FAILURE"),
            v("RESPONSE_RETURNED", "FAILED", "EOF"),
            v("CONNECT", "RETURNED", "NONE"),
            v("CONNECT", "FAILED", "SECRET_PAYLOAD"),
        ] {
            assert!(parse(&fields.to_string()).is_err());
        }
        for value in [
            json!(0),
            json!(MAX_SEQUENCE + 1),
            json!(-1),
            json!(1.5),
            json!("1"),
        ] {
            let mut d = v("CONNECT", "FAILED", "IO");
            d["attempt_sequence"] = value;
            assert!(parse(&d.to_string()).is_err());
        }
    }
    #[test]
    fn initial_and_exhausted_are_observations_only() {
        let mut d = v("NONE", "NONE", "NONE");
        d["attempt_sequence"] = json!(0);
        assert!(parse(&d.to_string()).is_ok());
        d["attempt_sequence"] = json!(MAX_SEQUENCE);
        d["outcome"] = json!("UNAVAILABLE");
        assert!(parse(&d.to_string()).is_ok());
        assert!(unavailable()["closed_java_fields"].is_null());
    }
}
