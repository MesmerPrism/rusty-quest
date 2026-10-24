//! Differential APK08 behavior and independent wire-contract vectors.
use rusty_quest_peer_wire::{
    checkpoint_reference, expand_renewal, is_projected, project_renewal, receipt_sha256,
};
use serde_json::{json, Value};

#[allow(dead_code)]
#[allow(clippy::all, clippy::pedantic)] // Frozen source is an exact historical oracle.
#[rustfmt::skip]
#[path = "support/renewal_projection_apk08.rs"]
mod baseline;

const FULL: &str = "rusty.quest.concurrent.authority_renewal_receipt.v3";
const CHECKPOINT: &str = "rusty.manifold.peer.concurrent_media_checkpoint.v2";
const PROJECTION: &str = "rusty.quest.concurrent.authority_renewal_projection.v1";
const REFERENCE: &str = "rusty.quest.concurrent.media_authority_reference.v1";

fn full() -> Value {
    let media = json!({"$schema":"modeled.media.v1","request_id":"request.1",
        "expires_at_ms":2000,"payload":"caf\u{e9} \u{1f980}"});
    json!({"$schema":FULL,"media_authority":media,"request_id":"request.1",
        "continuation_checkpoint":{"$schema":CHECKPOINT,"original":media,
            "baseline":media,"checkpoint_sequence":1}})
}

fn compare(value: &Value) {
    assert_eq!(
        project_renewal(value).map_err(|e| e.to_string()),
        baseline::project(value),
        "project: {value}"
    );
    assert_eq!(
        expand_renewal(value).map_err(|e| e.to_string()),
        baseline::expand(value),
        "expand: {value}"
    );
    assert_eq!(
        checkpoint_reference(value).map_err(|e| e.to_string()),
        baseline::checkpoint_reference(value),
        "checkpoint reference: {value}"
    );
    assert_eq!(is_projected(value), baseline::is_projected(value));
}

#[test]
fn known_canonical_digest_and_unicode_roundtrip() {
    let original = full();
    let wire = project_renewal(&original).unwrap();
    assert!(is_projected(&wire));
    assert_eq!(wire["$schema"], PROJECTION);
    assert_eq!(
        wire["receipt"]["continuation_checkpoint"]["original"],
        json!({"$schema":REFERENCE})
    );
    assert_eq!(expand_renewal(&wire).unwrap(), original);
    assert_eq!(
        project_renewal(&expand_renewal(&wire).unwrap()).unwrap(),
        wire
    );
    assert_eq!(
        receipt_sha256(&original).unwrap(),
        "sha256:b68231ac1f8546fb0ff4f525216230de12ed13fc007326db536500e3d6506131"
    );
    assert_eq!(wire["receipt_sha256"], receipt_sha256(&original).unwrap());
    let bytes = serde_json::to_vec(&original).unwrap();
    assert!(
        bytes.len()
            > String::from_utf8(bytes.clone())
                .unwrap()
                .encode_utf16()
                .count()
    );
    compare(&original);
    compare(&wire);
}

#[test]
fn both_one_and_no_equal_slots_preserve_exact_full_values() {
    for slots in 0..4 {
        let mut original = full();
        if slots & 1 != 0 {
            original["continuation_checkpoint"]["original"]["expires_at_ms"] = json!(1000);
        }
        if slots & 2 != 0 {
            original["continuation_checkpoint"]["baseline"]["expires_at_ms"] = json!(3000);
        }
        let wire = project_renewal(&original).unwrap();
        assert_eq!(is_projected(&wire), slots != 3);
        assert_eq!(expand_renewal(&wire).unwrap(), original);
        compare(&original);
        compare(&wire);
    }
}

#[test]
fn legacy_uncheckpointed_and_unequal_pass_through_do_not_gain_size_validation() {
    for version in 1..=3 {
        for checkpoint in [None, Some(Value::Null)] {
            let mut value = json!({"$schema":format!("rusty.quest.concurrent.authority_renewal_receipt.v{version}"),
                "unvalidated_extra":"x".repeat(1_048_576)});
            if let Some(checkpoint) = checkpoint {
                value["continuation_checkpoint"] = checkpoint;
            }
            assert_eq!(project_renewal(&value).unwrap(), value);
            assert_eq!(expand_renewal(&value).unwrap(), value);
            compare(&value);
        }
    }
    let mut value = full();
    value["extra"] = json!("x".repeat(1_048_576));
    value["continuation_checkpoint"]["original"]["expires_at_ms"] = json!(1);
    value["continuation_checkpoint"]["baseline"]["expires_at_ms"] = json!(2);
    assert_eq!(project_renewal(&value).unwrap(), value);
    compare(&value);
}

#[test]
fn complete_receipt_bound_is_exclusive_and_unchanged_on_projected_paths() {
    let mut value = full();
    value["padding"] = json!("");
    let overhead = serde_json::to_vec(&value).unwrap().len();
    value["padding"] = json!("x".repeat(1_048_575 - overhead));
    assert_eq!(serde_json::to_vec(&value).unwrap().len(), 1_048_575);
    assert!(project_renewal(&value).is_ok());
    assert!(receipt_sha256(&value).is_ok());
    value["padding"] = json!("x".repeat(1_048_576 - overhead));
    assert_eq!(serde_json::to_vec(&value).unwrap().len(), 1_048_576);
    assert_eq!(
        project_renewal(&value).unwrap_err().to_string(),
        "renewal projection complete receipt bounds"
    );
    assert_eq!(
        receipt_sha256(&value).unwrap_err().to_string(),
        "renewal projection complete receipt bounds"
    );
    compare(&value);
}

#[test]
fn malformed_inputs_and_error_precedence_match_original_algorithm() {
    let wire = baseline::project(&full()).unwrap();
    for value in [
        Value::Null,
        json!(1),
        json!([]),
        json!({}),
        json!({"$schema":true}),
        json!({"$schema":"rusty.quest.concurrent.authority_renewal_projection.v2"}),
    ] {
        compare(&value);
    }
    for mutation in 0..18 {
        let mut value = wire.clone();
        match mutation {
            0 => {
                value["extra"] = json!(true);
            }
            1 => {
                value.as_object_mut().unwrap().remove("receipt");
            }
            2 => {
                value.as_object_mut().unwrap().remove("receipt_sha256");
            }
            3 => {
                value["receipt_sha256"] = json!(true);
            }
            4 => {
                value["receipt_sha256"] = json!("sha256:".to_owned() + &"0".repeat(64));
            }
            5 => {
                value["receipt"]["$schema"] =
                    json!("rusty.quest.concurrent.authority_renewal_receipt.v2");
            }
            6 => {
                value["receipt"]["media_authority"] = Value::Null;
            }
            7 => {
                value["receipt"]["continuation_checkpoint"] = Value::Null;
            }
            8 => {
                value["receipt"]["continuation_checkpoint"]["baseline"]["extra"] = json!(true);
            }
            9 => {
                value["receipt"]["continuation_checkpoint"]["baseline"]["$schema"] =
                    json!("rusty.quest.concurrent.media_authority_reference.v2");
            }
            10 => {
                value["receipt"]["continuation_checkpoint"]
                    .as_object_mut()
                    .unwrap()
                    .remove("original");
            }
            11 => {
                value["receipt"]["continuation_checkpoint"]["original"] = json!(4);
            }
            12 => {
                value["receipt"]["continuation_checkpoint"]["$schema"] = json!("unknown");
            }
            13 => {
                value["receipt"]["continuation_checkpoint"]["original"] =
                    value["receipt"]["media_authority"].clone();
            }
            14 => {
                value["receipt"]["continuation_checkpoint"]["baseline"] =
                    value["receipt"]["media_authority"].clone();
            }
            15 => {
                value["receipt"] = full();
            }
            16 => {
                value["receipt"]["continuation_checkpoint"]["baseline"] = Value::Null;
            }
            _ => {
                value["receipt"]["media_authority"]["payload"] = json!("changed");
            }
        }
        compare(&value);
        assert!(expand_renewal(&value).is_err(), "mutation {mutation}");
    }
    compare(&wire["receipt"]);
}

#[test]
fn malformed_full_receipts_keep_original_validation_order() {
    for mutation in 0..8 {
        let mut value = full();
        match mutation {
            0 => {
                value["media_authority"] = Value::Null;
            }
            1 => {
                value["continuation_checkpoint"] = json!(true);
            }
            2 => {
                value["continuation_checkpoint"]["$schema"] = json!("unknown");
            }
            3 => {
                value["continuation_checkpoint"]
                    .as_object_mut()
                    .unwrap()
                    .remove("original");
            }
            4 => {
                value["continuation_checkpoint"]["baseline"] = Value::Null;
            }
            5 => {
                value["continuation_checkpoint"]["original"] = json!(1);
            }
            6 => {
                value["$schema"] = json!("unknown");
            }
            _ => {
                value["continuation_checkpoint"]["original"] = json!({"$schema":REFERENCE});
            }
        }
        compare(&value);
    }
}

#[test]
fn checkpoint_reference_and_unknown_reference_versions_keep_original_errors() {
    let original = full();
    let reference = checkpoint_reference(&original).unwrap();
    assert_eq!(reference.as_object().unwrap().len(), 2);
    assert_eq!(
        reference["receipt_sha256"],
        receipt_sha256(&original).unwrap()
    );
    assert_eq!(
        checkpoint_reference(
            &json!({"$schema":"rusty.quest.concurrent.authority_renewal_receipt.v1"})
        )
        .unwrap_err()
        .to_string(),
        "renewal checkpoint reference requires projected receipt"
    );
    for version in 1..=3 {
        for reference_version in 1..=2 {
            let value = json!({"$schema":format!("rusty.quest.concurrent.authority_renewal_receipt.v{version}"),
                "continuation_checkpoint":{"baseline":{"$schema":format!("rusty.quest.concurrent.media_authority_reference.v{reference_version}")}}});
            assert_eq!(
                expand_renewal(&value).unwrap_err().to_string(),
                "renewal projection unwrapped reference"
            );
            compare(&value);
        }
    }
}
