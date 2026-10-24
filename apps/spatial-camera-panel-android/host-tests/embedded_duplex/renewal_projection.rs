use super::*;
use serde_json::json;

fn emit(name: &str, value: &Value) {
    if let Some(directory) = std::env::var_os("QUEST_RENEWAL_PROJECTION_OUTPUT") {
        use std::io::Write;
        let path = std::path::PathBuf::from(directory).join(name);
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(path)
            .unwrap();
        file.write_all(&serde_json::to_vec(value).unwrap()).unwrap();
        file.sync_all().unwrap();
    }
}

fn completed() -> Value {
    let media = json!({"$schema":"modeled.actual.media.v1","request_id":"request.modeled.1",
        "expires_at_ms":2000,"applied":true,"payload":"x".repeat(4096)});
    json!({"$schema":"rusty.quest.concurrent.authority_renewal_receipt.v3",
        "media_authority":media,"request_id":"request.modeled.1","continuation_checkpoint":{
            "$schema":"rusty.manifold.peer.concurrent_media_checkpoint.v2",
            "original":media,"baseline":media,"checkpoint_sequence":1}})
}

#[test]
fn completed_checkpoint_round_trip_preserves_all_authority_and_order() {
    let full = completed();
    let encoded = project(&full).unwrap();
    assert!(serde_json::to_vec(&encoded).unwrap().len() < serde_json::to_vec(&full).unwrap().len());
    assert_eq!(expand(&encoded).unwrap(), full);
    assert_eq!(project(&expand(&encoded).unwrap()).unwrap(), encoded);
}

#[test]
fn unequal_original_is_retained_inline_not_replaced_by_baseline() {
    let mut full = completed();
    full["continuation_checkpoint"]["original"]["expires_at_ms"] = json!(1000);
    let encoded = project(&full).unwrap();
    assert_eq!(expand(&encoded).unwrap(), full);
    assert_eq!(
        encoded["receipt"]["continuation_checkpoint"]["original"],
        full["continuation_checkpoint"]["original"]
    );
}

#[test]
fn old_finite_and_uncheckpointed_exports_are_unchanged() {
    for schema in [
        "rusty.quest.concurrent.authority_renewal_receipt.v1",
        "rusty.quest.concurrent.authority_renewal_receipt.v2",
        "rusty.quest.concurrent.authority_renewal_receipt.v3",
    ] {
        let full = json!({"$schema":schema,"request_id":"request.modeled.1"});
        assert_eq!(project(&full).unwrap(), full);
        assert_eq!(expand(&full).unwrap(), full);
    }
}

#[test]
fn changed_digest_authority_reference_and_unknown_fields_refuse() {
    let encoded = project(&completed()).unwrap();
    for mutation in 0..8 {
        let mut changed = encoded.clone();
        match mutation {
            0 => changed["receipt_sha256"] = json!("sha256:".to_owned() + &"0".repeat(64)),
            1 => changed["receipt"]["media_authority"]["expires_at_ms"] = json!(3000),
            2 => {
                changed["receipt"]["continuation_checkpoint"]["baseline"]["path"] = json!("foreign")
            }
            3 => changed["extra"] = json!(true),
            4 => {
                changed["$schema"] = json!("rusty.quest.concurrent.authority_renewal_projection.v2")
            }
            5 => {
                changed["receipt"]["continuation_checkpoint"]["baseline"]["$schema"] =
                    json!("rusty.quest.concurrent.media_authority_reference.v2")
            }
            6 => {
                changed["receipt"]["$schema"] =
                    json!("rusty.quest.concurrent.authority_renewal_receipt.v2")
            }
            _ => changed["receipt_sha256"] = json!(true),
        }
        assert!(expand(&changed).is_err(), "mutation {mutation}");
    }
    assert!(
        expand(&encoded["receipt"]).is_err(),
        "reference requires its closed envelope"
    );
}

#[test]
fn checkpoint_reference_commits_complete_receipt_without_becoming_authority() {
    let full = completed();
    let encoded = project(&full).unwrap();
    let reference = checkpoint_reference(&full).unwrap();
    assert_eq!(reference["receipt_sha256"], encoded["receipt_sha256"]);
    assert_eq!(reference.as_object().unwrap().len(), 2);
    let mut changed = full.clone();
    changed["media_authority"]["expires_at_ms"] = json!(3000);
    changed["continuation_checkpoint"]["baseline"] = changed["media_authority"].clone();
    assert_ne!(checkpoint_reference(&changed).unwrap(), reference);
    assert!(checkpoint_reference(&json!({"$schema":"finite"})).is_err());
}

#[test]
fn measured_second_renewal_body_round_trip_stays_inside_unchanged_frame_cap() {
    let Some(path) = std::env::var_os("QUEST_RENEWAL_PROJECTION_BODY") else {
        return;
    };
    let body: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let full = &body["payload"]["provider_renewal"];
    let encoded = project(full).unwrap();
    assert_eq!(expand(&encoded).unwrap(), *full);
    let mut wire = body.clone();
    wire["payload"]["provider_renewal"] = encoded;
    let canonical = serde_json::to_vec(&wire).unwrap();
    assert!(canonical.len() + b"rusty.quest.embedded_duplex.pair_ceremony.v1\0".len() <= 131142);
    emit("PROJECTED_SIGNING_BODY.json", &wire);
    println!(
        "measured signing input after lossless projection: {} bytes",
        canonical.len() + 45
    );
    // Byte-sizing control only: this altered historical observation is not an
    // accepted owner receipt. It exercises the one-alias encoding branch.
    let mut unequal = full.clone();
    let observed = unequal["continuation_checkpoint"]["original"]["observed_at_ms"]
        .as_u64()
        .unwrap();
    unequal["continuation_checkpoint"]["original"]["observed_at_ms"] = json!(observed - 1);
    wire["payload"]["provider_renewal"] = project(&unequal).unwrap();
    assert_eq!(
        expand(&wire["payload"]["provider_renewal"]).unwrap(),
        unequal
    );
    let signing = serde_json::to_vec(&wire).unwrap().len() + 45;
    let encoded_frame =
        5 + serde_json::to_vec(&json!({"body":wire,"signature_hex":"0".repeat(128)}))
            .unwrap()
            .len();
    println!("modeled unequal historical anchor: signing {signing} bytes, complete frame {encoded_frame} bytes");
    assert!(signing <= 131142 && encoded_frame <= 131072);
    emit("MODELED_UNEQUAL_SIGNING_BODY.json", &wire);
}

#[test]
fn measured_status_restores_exact_full_owner_receipt_and_continuation() {
    let Some(path) = std::env::var_os("QUEST_RENEWAL_PROJECTION_STATUS") else {
        return;
    };
    let original: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let mut wire = original.clone();
    let latest = original["renewal_receipts"]
        .as_array()
        .unwrap()
        .last()
        .unwrap();
    for value in wire["renewal_receipts"].as_array_mut().unwrap() {
        *value = project(value).unwrap();
    }
    wire["continuation"]["$schema"] =
        json!("rusty.quest.embedded_duplex.ongoing_media_projection.v3");
    wire["continuation"]["continuation_checkpoint"] = checkpoint_reference(latest).unwrap();
    let bytes = serde_json::to_vec(&wire).unwrap();
    assert!(bytes.len() <= 262144);
    emit("PROJECTED_W_STATUS.json", &wire);
    emit("FULL_W_RENEWAL.json", latest);
    emit("PROJECTED_W_RENEWAL.json", &project(latest).unwrap());
    let mut unequal_full = latest.clone();
    let observed = unequal_full["continuation_checkpoint"]["original"]["observed_at_ms"]
        .as_u64()
        .unwrap();
    unequal_full["continuation_checkpoint"]["original"]["observed_at_ms"] = json!(observed - 1);
    let unequal_size = serde_json::to_vec(&project(&unequal_full).unwrap())
        .unwrap()
        .len();
    println!(
        "modeled unequal historical anchor: status {} bytes",
        bytes.len() + unequal_size - serde_json::to_vec(&project(latest).unwrap()).unwrap().len()
    );
    for value in wire["renewal_receipts"].as_array_mut().unwrap() {
        *value = expand(value).unwrap();
    }
    let restored = wire["renewal_receipts"]
        .as_array()
        .unwrap()
        .last()
        .unwrap()
        .clone();
    assert_eq!(
        checkpoint_reference(&restored).unwrap(),
        wire["continuation"]["continuation_checkpoint"]
    );
    wire["continuation"]["$schema"] =
        json!("rusty.quest.embedded_duplex.ongoing_media_projection.v2");
    wire["continuation"]["continuation_checkpoint"] = restored["continuation_checkpoint"].clone();
    assert_eq!(wire, original);
    assert_eq!(
        digest(&restored).unwrap(),
        original["current_owner_validation"]["owner_receipt_sha256"]
    );
    println!(
        "measured status after lossless projection: {} bytes",
        bytes.len()
    );
}
