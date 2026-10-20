//! Compile-selected policy and actual typed config/factory must agree before M construction.
use super::*;
fn check(
    selection: &str,
    policy: &str,
    target: &str,
    actual: Option<&Value>,
) -> Result<(), String> {
    match selection {
        "none" if policy.is_empty() && target.is_empty() && actual.is_none() => Ok(()),
        "source-bound-v2" => {
            let actual = actual.ok_or("compiled fresh policy requires actual selected config")?;
            let digest = |v: &str| {
                v.len() == 71
                    && v.starts_with("sha256:")
                    && v[7..]
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            };
            if !digest(policy)
                || !digest(target)
                || actual.get("$schema").and_then(Value::as_str)
                    != Some("rusty.quest.embedded_duplex.fresh_setup_build_policy.v1")
                || actual.get("selection").and_then(Value::as_str) != Some(selection)
                || actual.get("policy_sha256").and_then(Value::as_str) != Some(policy)
                || actual
                    .get("enrollment_selection_sha256")
                    .and_then(Value::as_str)
                    != Some(target)
            {
                return Err("actual native build/config fresh policy pin differs".into());
            }
            Ok(())
        }
        _ => Err("compiled fresh selection invalid or config unexpectedly selected".into()),
    }
}
pub(super) fn require(
    config: &rusty_quest_broker_authority::QuestBrokerRuntimeConfig,
) -> Result<(), String> {
    let actual = config
        .embedded_duplex
        .as_ref()
        .map(|c| c.fresh_setup_build_policy_projection())
        .transpose()?
        .flatten();
    check(
        option_env!("RUSTY_QUEST_COMPILED_EMBEDDED_DUPLEX_FRESH_SETUP_POLICY").unwrap_or("none"),
        option_env!("RUSTY_QUEST_COMPILED_EMBEDDED_DUPLEX_FRESH_POLICY_SHA256").unwrap_or(""),
        option_env!("RUSTY_QUEST_COMPILED_EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256")
            .unwrap_or(""),
        actual.as_ref(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn absent_and_changed_compile_config_projections_refuse() {
        assert!(check("none", "", "", None).is_ok());
        let a = "sha256:".to_owned() + &"a".repeat(64);
        let b = "sha256:".to_owned() + &"b".repeat(64);
        let actual = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_build_policy.v1","selection":"source-bound-v2","policy_sha256":a,"enrollment_selection_sha256":b});
        assert!(check("source-bound-v2", &a, &b, Some(&actual)).is_ok());
        assert!(check("none", "", "", Some(&actual)).is_err());
        assert!(check("source-bound-v2", &a, &b, None).is_err());
        assert!(check("source-bound-v2", &b, &a, Some(&actual)).is_err());
        assert!(check("Source-bound-v2", &a, &b, Some(&actual)).is_err());
    }
}
