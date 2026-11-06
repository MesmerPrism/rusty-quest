//! Explicit source-owned fixture probe. Never compiled into the application.
#[test]
#[ignore = "requires an explicitly selected external visual fixture"]
fn external_visual_configuration_is_accepted_by_native_parser() {
    let path =
        std::env::var("RUSTY_QUEST_TEST_VISUAL_CONFIGURATION").expect("explicit fixture required");
    let bytes = std::fs::read(path).expect("read selected fixture");
    assert!(bytes.len() <= 16384);
    let value: serde_json::Value = serde_json::from_slice(&bytes).expect("fixture JSON");
    super::validate_visual(&value).expect("complete native visual configuration");
}

#[test]
#[ignore = "requires explicitly selected trusted builtin migration metadata"]
fn external_builtin_migrations_are_accepted_by_native_parser() {
    let path =
        std::env::var("RUSTY_QUEST_TEST_BUILTIN_MIGRATIONS").expect("explicit fixture required");
    let text = std::fs::read_to_string(path).expect("read selected metadata");
    let trusted = super::parse_builtin_migrations(&text).expect("strict trusted metadata");
    assert!(!trusted.is_empty());
}
