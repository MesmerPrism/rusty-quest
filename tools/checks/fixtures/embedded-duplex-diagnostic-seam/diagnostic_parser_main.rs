fn main() {
    let lines = std::fs::read_to_string(std::env::args().nth(1).expect("producer JSON path"))
        .expect("read actual Java getter output");
    let mut count = 0;
    for line in lines.lines() {
        let value = parse_owner_failure_diagnostic(line).expect("actual compiled producer must pass owner parser");
        assert!(value.get("provider_reason").is_some());
        count += 1;
        let mut legacy = value.clone();
        legacy.as_object_mut().unwrap().remove("provider_reason"); legacy.as_object_mut().unwrap().remove("owner"); legacy.as_object_mut().unwrap().remove("cause");
        assert!(parse_owner_failure_diagnostic(&legacy.to_string()).is_ok());
        for (key, replacement) in [
            ("provider_reason", "UNKNOWN_REASON"), ("stage", "UNKNOWN_STAGE"),
            ("sink_stage", "UNKNOWN_SINK"), ("action", "UNKNOWN_ACTION"), ("code", "UNKNOWN_CODE"),
        ] {
            let mut unknown = value.clone();
            unknown[key] = serde_json::json!(replacement);
            assert!(parse_owner_failure_diagnostic(&unknown.to_string()).is_err(), "unknown {key}");
        }
        if value.get("owner").is_some() {
            for (key,replacement) in [("owner",serde_json::json!("unknown")),("cause",serde_json::json!("private raw cause")),("owner",serde_json::json!(7))] {let mut bad=value.clone();bad[key]=replacement;assert!(parse_owner_failure_diagnostic(&bad.to_string()).is_err());}
            let mut bad=value.clone();bad.as_object_mut().unwrap().remove("cause");assert!(parse_owner_failure_diagnostic(&bad.to_string()).is_err());
            let mut bad=value.clone();bad["stage"]=serde_json::json!("REGISTRY_BINDING");bad["provider_reason"]=serde_json::json!("NONE");assert!(parse_owner_failure_diagnostic(&bad.to_string()).is_err());
        }
        let mut extra = value.clone();
        extra["private_exception"] = serde_json::json!("PRIVATE_PAYLOAD");
        assert!(parse_owner_failure_diagnostic(&extra.to_string()).is_err());
    }
    assert!(count >= 12, "one real Registry rejection and every compiled enum, preserving prior coverage");
    let mut first: serde_json::Value = serde_json::from_str(lines.lines().next().unwrap()).unwrap();
    assert_eq!(first["provider_reason"], "FOREIGN_READBACK");
    let valid = first.to_string();
    assert!(valid.len() < 256);
    assert!(parse_owner_failure_diagnostic(&format!("{valid}{}", " ".repeat(256 - valid.len()))).is_ok());
    assert!(parse_owner_failure_diagnostic(&format!("{valid}{}", " ".repeat(257 - valid.len()))).is_err());
    first["stage"] = serde_json::json!("NONE");
    first["code"] = serde_json::json!("NONE");
    assert!(parse_owner_failure_diagnostic(&first.to_string()).is_err());
    assert!(parse_owner_failure_diagnostic("{}").is_err());
    assert!(parse_owner_failure_diagnostic("invalid JSON").is_err());
    assert!(parse_owner_failure_diagnostic(&"x".repeat(257)).is_err());
    println!("PASS {count} actual Java JSONs -> actual owner Rust parser; legacy/closed values/extra keys/shape/bounds negatives; no JNI/device effects");
}
