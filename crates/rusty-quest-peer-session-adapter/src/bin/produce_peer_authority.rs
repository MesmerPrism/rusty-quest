//! Bounded host producer for retained Manifold peer-owner decisions.

use rusty_manifold_model::DottedId;
use rusty_manifold_peer::{rendezvous_signing_bytes, ManifoldSignedRendezvousEvidence};
use rusty_quest_device_link::BleRendezvousPairReceipt;
use rusty_quest_peer_session_adapter::{
    review_peer_owner_journal, review_peer_owner_journal_with_fact_nonces, QuestPeerOwnerPolicy,
    QuestPeerOwnerStep,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{env, fs, io::Write, path::Path, process::ExitCode};

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Pin {
    path: String,
    sha256: String,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct IdentityPin {
    serial: String,
    endpoint: String,
    peer_id: DottedId,
    key_id: DottedId,
    receipt: Pin,
    inventory: Pin,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct InventoryCall {
    arguments: Vec<String>,
    exit_code: i32,
    stdout: String,
    stderr: String,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Facts {
    session_id: DottedId,
    pair: Pin,
    identities: [IdentityPin; 2],
    /// Exact reviewed source/provenance facts, never boolean authority flags.
    source_facts: Vec<Pin>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FactLineage {
    prior_facts: Vec<Pin>,
    current_facts: Pin,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct LineageStep {
    facts_sha256: String,
    /// Explicit requested-max TTL semantics for new sessions only; legacy history stays false.
    bound_session_ttl: bool,
    step: QuestPeerOwnerStep,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct IdentityReceipt {
    schema: String,
    generation: String,
    run_id: String,
    serial: String,
    peer_id: String,
    key_id: String,
    algorithm: String,
    public_key_ed25519_base64: String,
    public_key_sha256: String,
    private_key_exported_to_host: bool,
}

fn digest(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn read_pin(pin: &Pin) -> Result<Vec<u8>, String> {
    if pin.sha256.len() != 64
        || !pin
            .sha256
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err("pin must be canonical lowercase SHA-256".into());
    }
    let bytes = fs::read(&pin.path).map_err(|e| e.to_string())?;
    if digest(&bytes) != pin.sha256 {
        return Err(format!("raw file pin mismatch: {}", pin.path));
    }
    Ok(bytes)
}

fn json<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> Result<T, String> {
    rusty_quest_peer_session_adapter::parse_peer_owner_json(bytes)
}

fn create(path: &str, bytes: &[u8]) -> Result<(), String> {
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|e| e.to_string())?;
    file.write_all(bytes).map_err(|e| e.to_string())
}

fn run() -> Result<(), String> {
    let args: Vec<String> = env::args().collect();
    if args.get(1).map(String::as_str) == Some("prepare-signing-bytes") && args.len() == 5 {
        let evidence: ManifoldSignedRendezvousEvidence = json(&read_pin(&Pin {
            path: args[2].clone(),
            sha256: args[3].clone(),
        })?)?;
        let bytes = rendezvous_signing_bytes(&evidence);
        create(&args[4], &bytes)?;
        println!("{}", digest(&bytes));
        return Ok(());
    }
    if args.get(1).map(String::as_str) == Some("review-lineage") && args.len() == 10 {
        return review_lineage(&args);
    }
    if args.get(1).map(String::as_str) != Some("review") || args.len() != 10 {
        return Err("usage: produce_peer_authority prepare-signing-bytes <evidence-json> <sha256> <out-bin> | review <policy-json> <policy-sha256> <journal-json> <journal-sha256> <facts-json> <facts-sha256> <now-ms> <new-out-json>".into());
    }
    let policy_pin = Pin {
        path: args[2].clone(),
        sha256: args[3].clone(),
    };
    let journal_pin = Pin {
        path: args[4].clone(),
        sha256: args[5].clone(),
    };
    let facts_pin = Pin {
        path: args[6].clone(),
        sha256: args[7].clone(),
    };
    let policy: QuestPeerOwnerPolicy = json(&read_pin(&policy_pin)?)?;
    let steps: Vec<QuestPeerOwnerStep> = json(&read_pin(&journal_pin)?)?;
    let facts: Facts = json(&read_pin(&facts_pin)?)?;
    let pair = validate_facts(&policy, &steps, &facts)?;
    let now_ms: u64 = args[8]
        .parse()
        .map_err(|_| "now-ms must be explicit Unix milliseconds")?;
    let trace = review_peer_owner_journal(
        &pair,
        &policy,
        &steps,
        &facts_pin.sha256,
        &facts.session_id,
        now_ms,
    )?;
    // Re-read every raw input after owner evaluation; no effect occurs inside this CLI.
    for pin in [&policy_pin, &journal_pin, &facts_pin, &facts.pair] {
        read_pin(pin)?;
    }
    for identity in &facts.identities {
        read_pin(&identity.receipt)?;
        read_pin(&identity.inventory)?;
    }
    for pin in &facts.source_facts {
        read_pin(pin)?;
    }
    if Path::new(&args[9]).exists() {
        return Err("output already exists; historical evidence is immutable".into());
    }
    let output = serde_json::json!({ "schema": "rusty.quest.peer_owner_production_review.v1", "policy_sha256": policy_pin.sha256, "journal_sha256": journal_pin.sha256, "facts_sha256": facts_pin.sha256, "reviewed_at_ms": now_ms, "trace": trace });
    create(
        &args[9],
        &serde_json::to_vec_pretty(&output).map_err(|e| e.to_string())?,
    )
}

fn review_lineage(args: &[String]) -> Result<(), String> {
    let policy_pin = Pin {
        path: args[2].clone(),
        sha256: args[3].clone(),
    };
    let journal_pin = Pin {
        path: args[4].clone(),
        sha256: args[5].clone(),
    };
    let lineage_pin = Pin {
        path: args[6].clone(),
        sha256: args[7].clone(),
    };
    let policy: QuestPeerOwnerPolicy = json(&read_pin(&policy_pin)?)?;
    let journal: Vec<LineageStep> = json(&read_pin(&journal_pin)?)?;
    let lineage: FactLineage = json(&read_pin(&lineage_pin)?)?;
    if lineage.prior_facts.len() > 31 {
        return Err("fact lineage must contain 1..=32 frames".into());
    }
    let pins: Vec<&Pin> = lineage
        .prior_facts
        .iter()
        .chain([&lineage.current_facts])
        .collect();
    let mut frames: Vec<Facts> = Vec::new();
    for (index, pin) in pins.iter().enumerate() {
        if pins[..index]
            .iter()
            .any(|p| p.sha256 == pin.sha256 || p.path == pin.path)
        {
            return Err("fact frames must have unique raw digests and paths".into());
        }
        frames.push(json(&read_pin(pin)?)?);
    }
    let steps: Vec<QuestPeerOwnerStep> = journal.iter().map(|row| row.step.clone()).collect();
    let mut frame_indices = Vec::new();
    for row in &journal {
        if row.bound_session_ttl
            && !matches!(
                row.step.request,
                rusty_quest_peer_session_adapter::QuestPeerOwnerRequest::Session(_)
            )
        {
            return Err("bounded session TTL mode belongs only to a session request".into());
        }
        let index = pins
            .iter()
            .position(|p| p.sha256 == row.facts_sha256)
            .ok_or("request fact digest has no pinned lineage frame")?;
        if frame_indices.last().is_some_and(|prior| *prior > index) {
            return Err("request fact lineage moves backwards".into());
        }
        frame_indices.push(index);
    }
    if frame_indices.last() != Some(&(pins.len() - 1)) {
        return Err("final request must use current fact frame".into());
    }
    if (0..pins.len()).any(|index| !frame_indices.contains(&index)) {
        return Err("every declared fact frame must belong to retained requests".into());
    }
    let current = frames.last().ok_or("current fact frame absent")?;
    let pair = validate_facts(&policy, &steps, current)?;
    for frame in &frames {
        validate_facts(&policy, &steps, frame)?;
        if frame.pair.sha256 != current.pair.sha256
            || frame.session_id != current.session_id
            || frame
                .identities
                .iter()
                .zip(&current.identities)
                .any(|(old, new)| {
                    old.serial != new.serial
                        || old.endpoint != new.endpoint
                        || old.peer_id != new.peer_id
                        || old.key_id != new.key_id
                        || old.receipt.sha256 != new.receipt.sha256
                })
        {
            return Err("renewal lineage changes BLE/session/canonical public identity".into());
        }
    }
    let nonces: Vec<String> = journal.iter().map(|row| row.facts_sha256.clone()).collect();
    let bounds: Vec<bool> = journal.iter().map(|row| row.bound_session_ttl).collect();
    let now_ms = args[8]
        .parse()
        .map_err(|_| "now-ms must be explicit Unix milliseconds")?;
    let trace = review_peer_owner_journal_with_fact_nonces(
        &pair,
        &policy,
        &steps,
        &nonces,
        &bounds,
        &current.session_id,
        now_ms,
    )?;
    // Authenticate every historical and current closure again before emitting an immutable result.
    for pin in [&policy_pin, &journal_pin, &lineage_pin] {
        read_pin(pin)?;
    }
    for (pin, frame) in pins.iter().zip(&frames) {
        read_pin(pin)?;
        read_pin(&frame.pair)?;
        for identity in &frame.identities {
            read_pin(&identity.receipt)?;
            read_pin(&identity.inventory)?;
        }
        for source in &frame.source_facts {
            read_pin(source)?;
        }
    }
    let output = serde_json::json!({
        "schema":"rusty.quest.peer_owner_lineage_review.v1", "policy_sha256":policy_pin.sha256,
        "journal_sha256":journal_pin.sha256, "lineage_sha256":lineage_pin.sha256,
        "current_facts_sha256":lineage.current_facts.sha256, "reviewed_at_ms":now_ms, "trace":trace,
    });
    create(
        &args[9],
        &serde_json::to_vec_pretty(&output).map_err(|e| e.to_string())?,
    )
}

fn validate_facts(
    policy: &QuestPeerOwnerPolicy,
    steps: &[QuestPeerOwnerStep],
    facts: &Facts,
) -> Result<BleRendezvousPairReceipt, String> {
    if facts.source_facts.is_empty() || facts.source_facts.len() > 32 {
        return Err("require 1..=32 source provenance fact files".into());
    }
    if facts.identities[0].serial == facts.identities[1].serial
        || facts.identities[0].endpoint == facts.identities[1].endpoint
        || facts.identities[0].peer_id == facts.identities[1].peer_id
        || facts.identities[0].key_id == facts.identities[1].key_id
    {
        return Err("identities must be two distinct serial/peer/key tuples".into());
    }
    let pair: BleRendezvousPairReceipt = json(&read_pin(&facts.pair)?)?;
    if pair.primary_serial != facts.identities[0].endpoint
        || pair.secondary_serial != facts.identities[1].endpoint
    {
        return Err("BLE pair serials differ from identity facts".into());
    }
    for identity in &facts.identities {
        if identity.serial.is_empty()
            || identity.serial.len() > 64
            || !identity
                .serial
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
        {
            return Err("canonical hardware serial has an invalid shape".into());
        }
        let calls: Vec<InventoryCall> = json(&read_pin(&identity.inventory)?)?;
        if calls.is_empty() || calls.len() > 16 {
            return Err("inventory must retain 1..=16 actual native call records".into());
        }
        let expected = [
            "-s",
            identity.endpoint.as_str(),
            "shell",
            "getprop",
            "ro.serialno",
        ];
        let serial_calls: Vec<_> = calls
            .iter()
            .filter(|call| call.arguments.iter().map(String::as_str).eq(expected))
            .collect();
        if serial_calls.len() != 1
            || serial_calls[0].exit_code != 0
            || !serial_calls[0].stderr.is_empty()
            || serial_calls[0].stdout.trim_end_matches(['\r', '\n']) != identity.serial
        {
            return Err("actual endpoint/canonical serial inventory join failed".into());
        }
        let receipt: IdentityReceipt = json(&read_pin(&identity.receipt)?)?;
        if receipt.schema != "rusty.quest.peer_authority_identity.v1"
            || receipt.generation != "on-device"
            || receipt.algorithm != "Ed25519"
            || receipt.private_key_exported_to_host
            || receipt.run_id.is_empty()
            || receipt.serial != identity.serial
            || receipt.peer_id != identity.peer_id.as_str()
            || receipt.key_id != identity.key_id.as_str()
        {
            return Err("public identity receipt binding mismatch".into());
        }
        let public = decode_public_key(&receipt.public_key_ed25519_base64)?;
        if receipt.public_key_sha256 != format!("sha256:{}", digest(&public)) {
            return Err("public identity key hash mismatch".into());
        }
        let fingerprint = format!("sha256.{}", digest(&public));
        if !policy
            .trusted_key_fingerprints
            .iter()
            .any(|id| id.as_str() == fingerprint)
        {
            return Err("identity is outside pinned operator trust input".into());
        }
        let enrolled = steps.iter().any(|step| match &step.request {
            rusty_quest_peer_session_adapter::QuestPeerOwnerRequest::Enrollment(request) => {
                match &request.action {
                    rusty_manifold_peer::ManifoldPeerEnrollmentAction::Enroll { credential }
                    | rusty_manifold_peer::ManifoldPeerEnrollmentAction::Rotate {
                        credential,
                        ..
                    } => {
                        credential.peer_id == identity.peer_id
                            && credential.key_id == identity.key_id
                            && credential.public_key_sha256 == receipt.public_key_sha256
                            && credential.public_key_hex
                                == public
                                    .iter()
                                    .map(|b| format!("{b:02x}"))
                                    .collect::<String>()
                    }
                    _ => false,
                }
            }
            _ => false,
        });
        if !enrolled {
            return Err("journal has no exact public-identity credential request".into());
        }
    }
    for pin in &facts.source_facts {
        read_pin(pin)?;
    }
    for step in steps {
        match &step.request {
            rusty_quest_peer_session_adapter::QuestPeerOwnerRequest::Rendezvous(request) => {
                for evidence in [&request.first, &request.second] {
                    if !facts.identities.iter().any(|identity| {
                        identity.peer_id == evidence.signer_peer_id
                            && identity.key_id == evidence.signer_key_id
                    }) {
                        return Err(
                            "signed evidence peer/key differs from current identity facts".into(),
                        );
                    }
                }
            }
            rusty_quest_peer_session_adapter::QuestPeerOwnerRequest::Session(config) => {
                if config.subject_peer_id != facts.identities[0].peer_id
                    || config.candidate_peer_id != facts.identities[1].peer_id
                {
                    return Err("session peers differ from current identity facts".into());
                }
            }
            _ => {}
        }
    }
    Ok(pair)
}

fn decode_public_key(text: &str) -> Result<Vec<u8>, String> {
    if text.len() != 44 || !text.ends_with('=') {
        return Err("Ed25519 public key must be canonical 32-byte base64".into());
    }
    let mut output = Vec::new();
    let alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for chunk in text.as_bytes().chunks_exact(4) {
        let mut digits = [0_u8; 4];
        for (index, &byte) in chunk.iter().enumerate() {
            if byte == b'=' && index == 3 && output.len() == 30 {
                continue;
            }
            digits[index] = u8::try_from(
                alphabet
                    .iter()
                    .position(|&b| b == byte)
                    .ok_or("noncanonical base64")?,
            )
            .map_err(|e| e.to_string())?;
        }
        output.push((digits[0] << 2) | (digits[1] >> 4));
        output.push((digits[1] << 4) | (digits[2] >> 2));
        if chunk[3] != b'=' {
            output.push((digits[2] << 6) | digits[3]);
        } else if digits[2] & 3 != 0 {
            return Err("noncanonical base64 padding bits".into());
        }
    }
    if output.len() != 32 {
        return Err("public key length mismatch".into());
    }
    Ok(output)
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}
