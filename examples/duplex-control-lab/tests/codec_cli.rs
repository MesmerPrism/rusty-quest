//! Exercise the actual independent consumer process and structured refusals.
use serde_json::{json, Value};
use std::{
    fs,
    process::{Command, Output},
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT: AtomicU64 = AtomicU64::new(0);

fn run(bytes: &[u8]) -> Output {
    let directory = std::env::temp_dir().join(format!(
        "peer-wire-cli-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    fs::create_dir(&directory).unwrap();
    let path = directory.join("input.json");
    fs::write(&path, bytes).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_duplex-control-lab"))
        .arg("codec")
        .arg("--input")
        .arg(&path)
        .output()
        .unwrap();
    fs::remove_file(path).unwrap();
    fs::remove_dir(directory).unwrap();
    output
}

fn full() -> Value {
    let media = json!({"$schema":"modeled.media.v1","payload":"caf\u{e9} \u{1f980}"});
    json!({"$schema":"rusty.quest.concurrent.authority_renewal_receipt.v3","media_authority":media,
        "continuation_checkpoint":{"$schema":"rusty.manifold.peer.concurrent_media_checkpoint.v2","original":media,"baseline":media}})
}

#[test]
fn full_and_projected_inputs_use_the_production_codec_and_report_actual_sizes() {
    let original = full();
    let projected = rusty_quest_peer_wire::project_renewal(&original).unwrap();
    for input in [&original, &projected] {
        let result = run(&serde_json::to_vec(input).unwrap());
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stdout)
        );
        assert!(result.stderr.is_empty());
        let value: Value = serde_json::from_slice(&result.stdout).unwrap();
        assert_eq!(value["status"], "ok");
        assert_eq!(value["restored"], original);
        assert_eq!(value["projected"], projected);
        assert_eq!(
            value["receipt_sha256"],
            rusty_quest_peer_wire::receipt_sha256(&original).unwrap()
        );
        let canonical = serde_json::to_string(&original).unwrap();
        assert_eq!(value["sizes"]["receipt"]["utf8_bytes"], canonical.len());
        assert_eq!(
            value["sizes"]["receipt"]["utf16_units"],
            canonical.encode_utf16().count()
        );
        assert!(canonical.len() > canonical.encode_utf16().count());
    }
}

#[test]
fn malformed_and_oversize_inputs_refuse_without_echoing_payloads() {
    for (bytes, code) in [
        (b"{private-invalid".to_vec(), "input_json"),
        (vec![b'x'; 4 * 1024 * 1024 + 1], "input_bounds"),
        (
            serde_json::to_vec(&json!({"$schema":"unknown","private":"secret"})).unwrap(),
            "receipt_schema",
        ),
    ] {
        let result = run(&bytes);
        assert!(!result.status.success());
        assert!(result.stderr.is_empty());
        let value: Value = serde_json::from_slice(&result.stdout).unwrap();
        assert_eq!(value["status"], "refused");
        assert_eq!(value["error"]["code"], code);
        let text = String::from_utf8(result.stdout).unwrap();
        assert!(!text.contains("private"));
        assert!(!text.contains("secret"));
    }
}

#[test]
fn usage_refusal_is_structured() {
    let result = Command::new(env!("CARGO_BIN_EXE_duplex-control-lab"))
        .output()
        .unwrap();
    assert!(!result.status.success());
    let value: Value = serde_json::from_slice(&result.stdout).unwrap();
    assert_eq!(value["error"]["code"], "usage");
}
