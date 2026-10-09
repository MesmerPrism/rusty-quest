//! Current callback observation only. It cannot prove an accepted effect or cleanup.
use serde::Deserialize;
use serde_json::{json, Value};

const MAX_SEQUENCE: u64 = 9_007_199_254_740_991;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Attempt {
    attempt_sequence: u64,
    outcome: String,
    compensate: bool,
    stage: String,
    action: String,
    owner: String,
    cause: String,
    provider_reason: String,
}
fn parse(text: &str) -> Result<Value, &'static str> {
    if text.len() > 512 {
        return Err("BOUNDS");
    }
    // Deriving a typed struct also rejects duplicate fields and noninteger counters.
    let a: Attempt = serde_json::from_str(text).map_err(|_| "JSON_OR_SHAPE")?;
    let empty = a.stage == "NONE"
        && a.action == "NONE"
        && a.owner == "NONE"
        && a.cause == "NONE"
        && a.provider_reason == "NONE"
        && !a.compensate;
    let closed = super::owner_diagnostic_read::closed;
    if a.attempt_sequence > MAX_SEQUENCE
        || !closed("stage", &a.stage)
        || !closed("action", &a.action)
        || !closed("owner", &a.owner)
        || !closed("cause", &a.cause)
        || !closed("provider_reason", &a.provider_reason)
    {
        return Err("CLOSED_VALUES");
    }
    match a.outcome.as_str() {
        "NONE" if a.attempt_sequence == 0 && empty => {}
        "UNAVAILABLE" if a.attempt_sequence == MAX_SEQUENCE && empty => {}
        "IN_PROGRESS" | "SUCCEEDED" | "FAILED" if a.attempt_sequence > 0 && a.stage != "NONE" => {
            if (a.action == "BEFORE_TICKET") != (a.owner == "NONE")
                || a.action == "NONE"
                || (a.stage == "CALLBACK_FENCE" && a.action != "BEFORE_TICKET")
                || (a.action == "BEFORE_TICKET"
                    && !matches!(a.stage.as_str(), "CALLBACK_FENCE" | "PROJECTION_BINDING"))
                || (a.outcome != "FAILED" && (a.cause != "NONE" || a.provider_reason != "NONE"))
                || (a.cause != "NONE" && a.stage != "PROVIDER_EXECUTION")
                || (a.outcome == "FAILED"
                    && a.stage == "PROVIDER_EXECUTION"
                    && (a.cause == "NONE" || a.provider_reason == "NONE"))
                || (a.provider_reason.starts_with("INCOMING_ARM_")
                    && a.stage != "INCOMING_ARM_VERIFICATION")
                || (a.provider_reason != "NONE"
                    && !matches!(
                        a.stage.as_str(),
                        "PROVIDER_EXECUTION" | "INCOMING_ARM_VERIFICATION"
                    ))
                || (a.outcome == "SUCCEEDED"
                    && !matches!(
                        a.stage.as_str(),
                        "RECEIPT_VERIFICATION" | "INCOMING_ARM_VERIFICATION"
                    ))
            {
                return Err("CLOSED_VALUES");
            }
            if a.stage == "INCOMING_ARM_VERIFICATION"
                && a.provider_reason != "NONE"
                && (a.owner != "sink"
                    || a.cause != "NONE"
                    || !(a.action == "ARM_RECEIVER"
                        && matches!(
                            a.provider_reason.as_str(),
                            "INCOMING_ARM_ORDER"
                                | "INCOMING_ARM_PROJECTION"
                                | "INCOMING_ARM_EVIDENCE"
                                | "INCOMING_ARM_UNAVAILABLE"
                        )
                        || a.action == "STOP"
                            && matches!(
                                a.provider_reason.as_str(),
                                "INCOMING_ARM_PROJECTION" | "INCOMING_ARM_EVIDENCE"
                            )))
            {
                return Err("CLOSED_VALUES");
            }
        }
        _ => return Err("CLOSED_VALUES"),
    }
    Ok(
        json!({"attempt_sequence":a.attempt_sequence,"outcome":a.outcome,"compensate":a.compensate,
        "stage":a.stage,"action":a.action,"owner":a.owner,"cause":a.cause,"provider_reason":a.provider_reason}),
    )
}
pub(super) fn snapshot(text: &str) -> Value {
    match parse(text) {
        Ok(fields) => {
            json!({"schema":"rusty.quest.embedded_duplex.owner_latest_attempt_native_read.v1",
            "qualification_claimed":false,"strict_parser_status":"AVAILABLE","strict_parser_error":null,
            "closed_java_fields":fields})
        }
        Err(error) => {
            json!({"schema":"rusty.quest.embedded_duplex.owner_latest_attempt_native_read.v1",
            "qualification_claimed":false,"strict_parser_status":"REJECTED","strict_parser_error":error,
            "closed_java_fields":null})
        }
    }
}
pub(super) fn unavailable() -> Value {
    json!({"schema":"rusty.quest.embedded_duplex.owner_latest_attempt_native_read.v1",
        "qualification_claimed":false,"strict_parser_status":"UNAVAILABLE","strict_parser_error":"CALL_UNAVAILABLE",
        "closed_java_fields":null})
}

#[cfg(test)]
mod tests {
    use super::*;
    fn failed() -> Value {
        json!({"attempt_sequence":2,"outcome":"FAILED","compensate":true,
        "stage":"PROVIDER_EXECUTION","action":"STOP","owner":"sink","cause":"STATE","provider_reason":"OTHER"})
    }
    #[test]
    fn current_failure_and_compensation_success_are_observations() {
        let mut v = failed();
        assert_eq!(snapshot(&v.to_string())["closed_java_fields"], v);
        v["outcome"] = json!("SUCCEEDED");
        v["stage"] = json!("INCOMING_ARM_VERIFICATION");
        v["cause"] = json!("NONE");
        v["provider_reason"] = json!("NONE");
        let r = snapshot(&v.to_string());
        assert_eq!(r["closed_java_fields"], v);
        assert_eq!(r["qualification_claimed"], false);
    }
    #[test]
    fn empty_exhausted_and_in_progress_are_distinct() {
        let v = json!({"attempt_sequence":0,"outcome":"NONE","compensate":false,"stage":"NONE","action":"NONE","owner":"NONE","cause":"NONE","provider_reason":"NONE"});
        assert!(parse(&v.to_string()).is_ok());
        let mut v = v;
        v["attempt_sequence"] = json!(MAX_SEQUENCE);
        v["outcome"] = json!("UNAVAILABLE");
        assert!(parse(&v.to_string()).is_ok());
        let mut v = failed();
        v["outcome"] = json!("IN_PROGRESS");
        v["cause"] = json!("NONE");
        v["provider_reason"] = json!("NONE");
        assert!(parse(&v.to_string()).is_ok());
    }
    #[test]
    fn unknown_fields_types_duplicate_and_oversize_drop_all_payload() {
        let mut extra = failed();
        extra["secret"] = json!("SECRET_PAYLOAD");
        let duplicate = failed()
            .to_string()
            .replacen("{", "{\"attempt_sequence\":1,", 1);
        for text in [extra.to_string(), duplicate, "x".repeat(513), "[]".into()] {
            let r = snapshot(&text);
            assert!(r["closed_java_fields"].is_null());
            assert!(!r.to_string().contains("SECRET_PAYLOAD"));
        }
        for key in ["attempt_sequence", "compensate"] {
            let mut v = failed();
            v[key] = json!("SECRET_PAYLOAD");
            assert!(snapshot(&v.to_string())["closed_java_fields"].is_null());
        }
    }
    #[test]
    fn counter_and_closed_semantic_damage_denied() {
        for (key, value) in [
            ("attempt_sequence", json!(0)),
            ("attempt_sequence", json!(MAX_SEQUENCE + 1)),
            ("attempt_sequence", json!(-1)),
            ("attempt_sequence", json!(1.5)),
            ("outcome", json!("terminal")),
            ("owner", json!("SECRET_PAYLOAD")),
            ("stage", json!("NONE")),
            ("action", json!("BEFORE_TICKET")),
            ("stage", json!("PROJECTION_BINDING")),
        ] {
            let mut v = failed();
            v[key] = value;
            assert!(parse(&v.to_string()).is_err());
        }
    }
    #[test]
    fn incoming_stop_reason_combinations_closed() {
        let mut v = failed();
        v["stage"] = json!("INCOMING_ARM_VERIFICATION");
        v["cause"] = json!("NONE");
        v["provider_reason"] = json!("INCOMING_ARM_PROJECTION");
        assert!(parse(&v.to_string()).is_ok());
        for (key, value) in [
            ("action", "CLEANUP"),
            ("owner", "codec"),
            ("provider_reason", "INCOMING_ARM_ORDER"),
            ("cause", "STATE"),
        ] {
            let mut d = v.clone();
            d[key] = json!(value);
            assert!(parse(&d.to_string()).is_err());
        }
        assert_eq!(unavailable()["qualification_claimed"], false);
    }
}
