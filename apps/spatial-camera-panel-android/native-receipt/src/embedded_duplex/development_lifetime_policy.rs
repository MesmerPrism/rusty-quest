//! Trusted compiled lifetime selection; config markers cannot opt in a finite build.
use rusty_manifold_admission::ManifoldDevelopmentLifetimePolicy as Policy;
use rusty_quest_broker_authority::{
    QuestEmbeddedDuplexAuthorityConfig, QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA,
    QUEST_EMBEDDED_DUPLEX_DEVELOPMENT_AUTHORITY_CONFIG_SCHEMA,
};

fn compiled_selection(value: Option<&str>) -> Result<Option<&'static Policy>, String> {
    match value {
        None | Some("finite") => Ok(None),
        Some("development-v1") => Ok(Some(Policy::source_owned_v1())),
        _ => Err("unsupported compiled lifetime selection".into()),
    }
}

pub(crate) fn for_config(
    config: &QuestEmbeddedDuplexAuthorityConfig,
) -> Result<Option<&'static Policy>, String> {
    match config.schema_id.as_str() {
        QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA => Ok(None),
        QUEST_EMBEDDED_DUPLEX_DEVELOPMENT_AUTHORITY_CONFIG_SCHEMA => compiled_selection(
            option_env!("RUSTY_QUEST_COMPILED_EMBEDDED_DUPLEX_LIFETIME_POLICY"),
        )?
        .map(Some)
        .ok_or_else(|| "development lifetime is not compiled".into()),
        _ => Err("unsupported lifetime config schema".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn actual_compiled_selection_is_closed_and_source_owned() {
        assert!(compiled_selection(None).unwrap().is_none());
        assert!(compiled_selection(Some("finite")).unwrap().is_none());
        assert_eq!(
            compiled_selection(Some("development-v1")).unwrap(),
            Some(Policy::source_owned_v1())
        );
        assert!(compiled_selection(Some("caller-override")).is_err());
    }
}
