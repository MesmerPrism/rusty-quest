//! Bounded host producer for retained Manifold peer-owner decisions.

use rusty_manifold_model::DottedId;
use rusty_manifold_peer::{rendezvous_signing_bytes, ManifoldSignedRendezvousEvidence};
use rusty_quest_device_link::BleRendezvousPairReceipt;
use rusty_quest_peer_session_adapter::{
    review_peer_owner_journal, QuestPeerOwnerPolicy, QuestPeerOwnerStep,
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
    peer_id: DottedId,
    key_id: DottedId,
    receipt: Pin,
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
    if facts.source_facts.is_empty() || facts.source_facts.len() > 32 {
        return Err("require 1..=32 source provenance fact files".into());
    }
    if facts.identities[0].serial == facts.identities[1].serial
        || facts.identities[0].peer_id == facts.identities[1].peer_id
        || facts.identities[0].key_id == facts.identities[1].key_id
    {
        return Err("identities must be two distinct serial/peer/key tuples".into());
    }
    let pair: BleRendezvousPairReceipt = json(&read_pin(&facts.pair)?)?;
    if pair.primary_serial != facts.identities[0].serial
        || pair.secondary_serial != facts.identities[1].serial
    {
        return Err("BLE pair serials differ from identity facts".into());
    }
    for identity in &facts.identities {
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
    for step in &steps {
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
