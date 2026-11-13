//! Host execution of production config, presentation writer and witness policy.
//! Only the unrelated Android publisher is replaced by its scalar completion type.
#![allow(dead_code)]
#[path = "../../../native/src/meta_input_diagnostic_state.rs"]
mod meta_input_diagnostic_state;
#[path = "../../../native/src/presentation_diagnostic.rs"]
mod presentation_diagnostic;
#[path = "../../../native/src/presentation_geometry.rs"]
mod presentation_geometry;
static MARKERS: std::sync::Mutex<Vec<String>> = std::sync::Mutex::new(Vec::new());
fn marker(label: &str, value: impl AsRef<str>) {
    MARKERS
        .lock()
        .unwrap()
        .push(format!("{label} {}", value.as_ref()));
}
mod meta_input_diagnostic {
    pub(crate) fn enabled() -> bool {
        true
    }
    #[derive(Clone, Copy)]
    pub(crate) struct Completion {
        pub(crate) generation: u64,
        pub(crate) sequence: u64,
        pub(crate) mode: u32,
        pub(crate) render_frame: u64,
        pub(crate) input_frame: u64,
        pub(crate) timestamp_ns: i64,
    }
}
#[test]
fn live_only_startup_blocks_actual_presentation_writer_before_filesystem() {
    let root = std::env::temp_dir().join(format!(
        "rusty-quest-no-save-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir(&root).unwrap();
    let config = root.join("presentation-diagnostic.json");
    std::fs::write(&config, br#"{"schema":"private.meta_input.presentation.v1","mode":"stretch","save_witnesses":false}"#).unwrap();
    presentation_diagnostic::initialize(Some(&root))
        .expect("explicit no-save startup must be accepted");
    assert!(
        MARKERS
            .lock()
            .unwrap()
            .iter()
            .any(|m| m.contains("saveWitnesses=false") && m.contains("noPixelWitnesses=true")),
        "actual effective no-save marker required"
    );
    let policy: serde_json::Value = serde_json::from_slice(
        &std::fs::read(root.join("presentation-policy.json"))
            .expect("actual bounded policy receipt required before admission"),
    )
    .unwrap();
    assert_eq!(policy["process_id"], std::process::id());
    assert_eq!(policy["save_witnesses"], false);
    assert_eq!(policy["pixel_witness_policy"], "disabled-no-save");
    use sha2::{Digest, Sha256};
    let expected_sha = format!("{:x}", Sha256::digest(std::fs::read(&config).unwrap()));
    assert_eq!(policy["startup_config_sha256"], expected_sha);
    assert_eq!(
        policy["startup_config_bytes"],
        std::fs::metadata(&config).unwrap().len()
    );
    assert!(
        MARKERS
            .lock()
            .unwrap()
            .iter()
            .any(|m| m.contains(&format!("startupConfigSha256={expected_sha}")))
    );
    let completion = meta_input_diagnostic::Completion {
        generation: 7,
        sequence: 0,
        mode: 0,
        render_frame: 200,
        input_frame: 17,
        timestamp_ns: 50,
    };
    let timing = meta_input_diagnostic_state::WitnessTiming {
        elapsed_ms: 1000,
        input_advances: 3,
        first_timestamp_ns: 40,
    };
    let result = presentation_diagnostic::write_witness(
        completion,
        timing,
        0,
        ash::vk::Format::R8G8B8A8_UNORM,
        &serde_json::json!({}),
        &[0; 4],
    );
    assert!(
        result.is_err(),
        "defensive actual writer must reject disabled pixels"
    );
    assert_eq!(
        std::fs::read_dir(&root).unwrap().count(),
        2,
        "startup config and bounded scalar policy receipt only; no witness directory or pixels"
    );
    std::fs::remove_file(root.join("presentation-policy.json")).unwrap();
    std::fs::remove_file(config).unwrap();
    std::fs::remove_dir(root).unwrap();
}
