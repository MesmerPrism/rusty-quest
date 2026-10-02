//! On-device helper for NET-017 peer-authority evidence.

use ed25519_dalek::{Signer, SigningKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    env, fs,
    io::{Read, Write},
    path::Path,
    process::ExitCode,
};

#[derive(Deserialize, Serialize)]
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

#[derive(Serialize)]
struct SignatureReceipt {
    schema: &'static str,
    run_id: String,
    peer_id: String,
    peer_serial: String,
    algorithm: &'static str,
    context_sha256: String,
    signature_base64: String,
}

fn main() -> ExitCode {
    match run() {
        Ok(path) => {
            println!("{path}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<String, String> {
    let args = env::args().collect::<Vec<_>>();
    match args.get(1).map(String::as_str) {
        Some("identity") => identity(&args[2..]),
        Some("sign") => sign(&args[2..]),
        Some("sign-owner-bytes") => sign_owner_bytes(&args[2..]),
        _ => Err(
            "usage: peer_authority_device_helper identity <run-id> <serial> <peer-id> <key-path> <out-json> | sign <run-id> <peer-id> <peer-serial> <key-path> <context-json> <out-json>"
                .to_string(),
        ),
    }
}

fn identity(args: &[String]) -> Result<String, String> {
    if args.len() != 5 {
        return Err("identity requires <run-id> <serial> <peer-id> <key-path> <out-json>".into());
    }
    let run_id = safe(&args[0]);
    let serial = safe(&args[1]);
    let peer_id = safe(&args[2]);
    let key_path = &args[3];
    let out = &args[4];
    let mut seed = [0_u8; 32];
    fs::File::open("/dev/urandom")
        .map_err(|error| error.to_string())?
        .read_exact(&mut seed)
        .map_err(|error| error.to_string())?;
    fs::write(key_path, seed).map_err(|error| error.to_string())?;
    let signing_key = SigningKey::from_bytes(&seed);
    let public = signing_key.verifying_key().to_bytes();
    let receipt = IdentityReceipt {
        schema: "rusty.quest.peer_authority_identity.v1".into(),
        generation: "on-device".into(),
        run_id: run_id.clone(),
        serial,
        peer_id: peer_id.clone(),
        key_id: format!("key.{peer_id}.{run_id}"),
        algorithm: "Ed25519".into(),
        public_key_ed25519_base64: base64(&public),
        public_key_sha256: format!("sha256:{}", hex(&Sha256::digest(public))),
        private_key_exported_to_host: false,
    };
    write_json(out, &receipt)?;
    Ok(out.clone())
}

fn sign(args: &[String]) -> Result<String, String> {
    if args.len() != 6 {
        return Err(
            "sign requires <run-id> <peer-id> <peer-serial> <key-path> <context-json> <out-json>"
                .into(),
        );
    }
    let run_id = safe(&args[0]);
    let peer_id = safe(&args[1]);
    let peer_serial = safe(&args[2]);
    let key_path = &args[3];
    let context_path = &args[4];
    let out = &args[5];
    let seed = fs::read(key_path).map_err(|error| error.to_string())?;
    let seed: [u8; 32] = seed
        .try_into()
        .map_err(|_| "stored Ed25519 seed is not 32 bytes".to_string())?;
    let context = fs::read(context_path).map_err(|error| error.to_string())?;
    let signing_key = SigningKey::from_bytes(&seed);
    let signature = signing_key.sign(&context).to_bytes();
    let receipt = SignatureReceipt {
        schema: "rusty.quest.peer_authority_signature.v1",
        run_id,
        peer_id,
        peer_serial,
        algorithm: "Ed25519",
        context_sha256: format!("sha256:{}", hex(&Sha256::digest(&context))),
        signature_base64: base64(&signature),
    };
    write_json(out, &receipt)?;
    Ok(out.clone())
}

/// Signs exactly the owner's binary domain preimage, never context JSON.
fn sign_owner_bytes(args: &[String]) -> Result<String, String> {
    if args.len() != 6 {
        return Err("sign-owner-bytes requires <session-id> <identity-json> <key-path> <owner-bytes> <sha256> <new-out-json>".into());
    }
    let session_id = rusty_manifold_model::DottedId::new(&args[0]).map_err(|e| e.to_string())?;
    let identity: IdentityReceipt =
        serde_json::from_slice(&fs::read(&args[1]).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
    let seed: [u8; 32] = fs::read(&args[2])
        .map_err(|e| e.to_string())?
        .try_into()
        .map_err(|_| "stored Ed25519 seed is not 32 bytes")?;
    let bytes = fs::read(&args[3]).map_err(|e| e.to_string())?;
    let sha256 = hex(&Sha256::digest(&bytes));
    if sha256 != args[4]
        || !bytes.starts_with(b"rusty.manifold.peer.signed_rendezvous_evidence.v1\0")
    {
        return Err("owner signing byte domain or exact hash mismatch".into());
    }
    let key = SigningKey::from_bytes(&seed);
    let public = key.verifying_key().to_bytes();
    if identity.schema != "rusty.quest.peer_authority_identity.v1"
        || identity.generation != "on-device"
        || identity.algorithm != "Ed25519"
        || identity.private_key_exported_to_host
        || identity.public_key_ed25519_base64 != base64(&public)
        || identity.public_key_sha256 != format!("sha256:{}", hex(&Sha256::digest(public)))
    {
        return Err("identity receipt differs from the retained device key".into());
    }
    let signature = key.sign(&bytes).to_bytes();
    let receipt = serde_json::json!({
        "schema": "rusty.quest.peer_owner_binary_signature.v1",
        "session_id": session_id, "run_id": identity.run_id, "serial": identity.serial,
        "peer_id": identity.peer_id, "key_id": identity.key_id, "algorithm": "Ed25519",
        "public_key_sha256": identity.public_key_sha256,
        "signed_bytes_sha256": format!("sha256:{sha256}"),
        "signature_hex": hex(&signature), "signature_base64": base64(&signature),
    });
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&args[5])
        .map_err(|e| e.to_string())?;
    file.write_all(
        serde_json::to_vec_pretty(&receipt)
            .map_err(|e| e.to_string())?
            .as_slice(),
    )
    .map_err(|e| e.to_string())?;
    Ok(args[5].clone())
}

fn write_json<T: Serialize>(path: &str, value: &T) -> Result<(), String> {
    if let Some(parent) = Path::new(path).parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let mut file = fs::File::create(path).map_err(|error| error.to_string())?;
    let text = serde_json::to_string_pretty(value).map_err(|error| error.to_string())?;
    file.write_all(text.as_bytes())
        .map_err(|error| error.to_string())
}

fn safe(value: &str) -> String {
    value
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
                character
            } else {
                '_'
            }
        })
        .collect()
}

fn hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(char::from(HEX[usize::from(byte >> 4)]));
        output.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    output
}

fn base64(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::with_capacity(bytes.len().div_ceil(3) * 4);
    let mut index = 0;
    while index < bytes.len() {
        let b0 = bytes[index];
        let b1 = *bytes.get(index + 1).unwrap_or(&0);
        let b2 = *bytes.get(index + 2).unwrap_or(&0);
        output.push(char::from(TABLE[usize::from(b0 >> 2)]));
        output.push(char::from(
            TABLE[usize::from(((b0 & 0x03) << 4) | (b1 >> 4))],
        ));
        if index + 1 < bytes.len() {
            output.push(char::from(
                TABLE[usize::from(((b1 & 0x0f) << 2) | (b2 >> 6))],
            ));
        } else {
            output.push('=');
        }
        if index + 2 < bytes.len() {
            output.push(char::from(TABLE[usize::from(b2 & 0x3f)]));
        } else {
            output.push('=');
        }
        index += 3;
    }
    output
}
