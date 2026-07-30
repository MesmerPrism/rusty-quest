//! Host-only signer for one-artifact Rusty Quest package-update manifests.

use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::io::{self, Write};
use std::path::PathBuf;

use ed25519_dalek::{Signer, SigningKey};
use rusty_quest_package_updater::{
    decode_base64url_canonical, encode_base64url, manifest_signing_bytes, verify_manifest,
    PackageUpdateArtifact, PackageUpdateDecision, PackageUpdateManifest,
    PackageUpdateManifestEnvelope, PackageUpdatePolicy, PackageUpdateRollbackState,
    ReleaseKeyRecord, ReleaseKeyRegistry, ENVELOPE_SCHEMA, MANIFEST_SCHEMA, SIGNATURE_ALGORITHM,
};

const SEED_ENV: &str = "RUSTY_QUEST_UPDATE_SIGNING_SEED_BASE64URL";

#[derive(Clone, Debug, Eq, PartialEq)]
struct SignerArgs {
    channel: String,
    key_id: String,
    expected_public_key: String,
    package_name: String,
    rollout_ring: String,
    expected_https_origin: String,
    apk_url: String,
    signer_sha256: String,
    apk_sha256: String,
    apk_size_bytes: u64,
    version_code: u64,
    version_name: String,
    sequence: u64,
    issued_at_ms: u64,
    expires_at_ms: u64,
    manifest_id: String,
    output: String,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("package-update manifest signing failed: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let arguments: Vec<String> = env::args().skip(1).collect();
    if arguments.iter().any(|argument| argument == "--help") {
        print_usage();
        return Ok(());
    }
    let args = parse_args(arguments)?;
    let seed_value = env::var(SEED_ENV)
        .map_err(|_| format!("{SEED_ENV} must contain the raw 32-byte seed as base64url"))?;
    let seed_bytes = decode_base64url_canonical(&seed_value).map_err(|error| error.to_string())?;
    let seed: [u8; 32] = seed_bytes.try_into().map_err(|_| {
        format!("{SEED_ENV} must decode to exactly 32 bytes; the seed is never printed")
    })?;

    let output = build_and_self_verify(&args, &seed)?;
    if args.output == "-" {
        let mut stdout = io::stdout().lock();
        stdout
            .write_all(&output)
            .and_then(|()| stdout.write_all(b"\n"))
            .map_err(|error| format!("could not write envelope to stdout: {error}"))?;
    } else {
        fs::write(PathBuf::from(&args.output), output)
            .map_err(|error| format!("could not write envelope output: {error}"))?;
    }
    Ok(())
}

fn build_and_self_verify(args: &SignerArgs, seed: &[u8; 32]) -> Result<Vec<u8>, String> {
    let validity = args
        .expires_at_ms
        .checked_sub(args.issued_at_ms)
        .filter(|value| *value > 0)
        .ok_or_else(|| "expires-at-ms must be later than issued-at-ms".to_owned())?;
    let installed_version_code = args
        .version_code
        .checked_sub(1)
        .ok_or_else(|| "version-code must be positive".to_owned())?;
    let manifest = PackageUpdateManifest {
        schema: MANIFEST_SCHEMA.to_owned(),
        manifest_id: args.manifest_id.clone(),
        sequence: args.sequence,
        issued_at_ms: args.issued_at_ms,
        expires_at_ms: args.expires_at_ms,
        channel: args.channel.clone(),
        rollout_ring: args.rollout_ring.clone(),
        artifact: PackageUpdateArtifact {
            package_name: args.package_name.clone(),
            version_code: args.version_code,
            version_name: args.version_name.clone(),
            apk_url: args.apk_url.clone(),
            apk_sha256: args.apk_sha256.clone(),
            apk_size_bytes: args.apk_size_bytes,
            signer_sha256: args.signer_sha256.clone(),
        },
    };
    let signing_bytes = manifest_signing_bytes(&manifest).map_err(|error| error.to_string())?;
    let signing_key = SigningKey::from_bytes(seed);
    let derived_public_key = encode_base64url(&signing_key.verifying_key().to_bytes());
    if derived_public_key != args.expected_public_key {
        return Err("signing seed does not match the explicitly trusted public key".to_owned());
    }
    let signature = signing_key.sign(&signing_bytes);
    let envelope = PackageUpdateManifestEnvelope {
        schema: ENVELOPE_SCHEMA.to_owned(),
        key_id: args.key_id.clone(),
        algorithm: SIGNATURE_ALGORITHM.to_owned(),
        signature: encode_base64url(&signature.to_bytes()),
        signed: manifest,
    };
    let output = serde_json::to_vec_pretty(&envelope)
        .map_err(|error| format!("could not serialize signed envelope: {error}"))?;

    let public_key = signing_key.verifying_key().to_bytes();
    let registry = ReleaseKeyRegistry::from_records([ReleaseKeyRecord {
        key_id: args.key_id.clone(),
        public_key: encode_base64url(&public_key),
    }])
    .map_err(|error| error.to_string())?;
    let policy = PackageUpdatePolicy {
        expected_channel: args.channel.clone(),
        expected_https_origin: args.expected_https_origin.clone(),
        expected_package_name: args.package_name.clone(),
        expected_rollout_ring: args.rollout_ring.clone(),
        expected_signer_sha256: args.signer_sha256.clone(),
        expected_key_id: args.key_id.clone(),
        expected_public_key: args.expected_public_key.clone(),
        installed_version_code,
        minimum_target_version_code: args.version_code,
        maximum_target_version_code: args.version_code,
        maximum_apk_size_bytes: args.apk_size_bytes,
        maximum_manifest_validity_ms: validity,
        maximum_future_issue_skew_ms: 0,
    };
    let admission = verify_manifest(
        &output,
        &registry,
        &policy,
        &PackageUpdateRollbackState::default(),
        args.issued_at_ms,
    );
    if admission.receipt.decision != PackageUpdateDecision::Accepted {
        return Err(format!(
            "new envelope failed immediate self-verification: {}",
            admission.receipt.code
        ));
    }
    Ok(output)
}

fn parse_args(arguments: Vec<String>) -> Result<SignerArgs, String> {
    if arguments.is_empty() {
        return Err("missing arguments; use --help for the exact interface".to_owned());
    }
    if arguments.len() % 2 != 0 {
        return Err("every option requires exactly one value".to_owned());
    }
    let mut values = BTreeMap::new();
    for pair in arguments.chunks_exact(2) {
        let option = pair[0].as_str();
        if !option.starts_with("--") {
            return Err(format!("unexpected positional argument: {option}"));
        }
        if !KNOWN_OPTIONS.contains(&option) {
            return Err(format!("unknown option: {option}"));
        }
        if values
            .insert(option.to_owned(), pair[1].to_owned())
            .is_some()
        {
            return Err(format!("duplicate option: {option}"));
        }
    }

    Ok(SignerArgs {
        channel: required(&values, "--channel")?,
        key_id: required(&values, "--key-id")?,
        expected_public_key: required(&values, "--expected-public-key")?,
        package_name: required(&values, "--package")?,
        rollout_ring: required(&values, "--ring")?,
        expected_https_origin: required(&values, "--origin")?,
        apk_url: required(&values, "--apk-url")?,
        signer_sha256: required(&values, "--signer-sha256")?,
        apk_sha256: required(&values, "--apk-sha256")?,
        apk_size_bytes: required_u64(&values, "--apk-size")?,
        version_code: required_u64(&values, "--version-code")?,
        version_name: required(&values, "--version-name")?,
        sequence: required_u64(&values, "--sequence")?,
        issued_at_ms: required_u64(&values, "--issued-at-ms")?,
        expires_at_ms: required_u64(&values, "--expires-at-ms")?,
        manifest_id: required(&values, "--manifest-id")?,
        output: required(&values, "--out")?,
    })
}

fn required(values: &BTreeMap<String, String>, option: &str) -> Result<String, String> {
    values
        .get(option)
        .filter(|value| !value.is_empty())
        .cloned()
        .ok_or_else(|| format!("missing required option {option}"))
}

fn required_u64(values: &BTreeMap<String, String>, option: &str) -> Result<u64, String> {
    required(values, option)?
        .parse()
        .map_err(|_| format!("{option} must be an unsigned decimal integer"))
}

fn print_usage() {
    println!(
        "\
sign_package_update_manifest
  --channel <closed-channel>

The signing seed is read only from {SEED_ENV} as canonical unpadded base64url.

Required options:
  --key-id <id>
  --expected-public-key <canonical-base64url-raw-ed25519-key>
  --manifest-id <id>
  --package <android.package>
  --ring <rollout-ring>
  --origin <https://canonical-origin>
  --apk-url <https://exact-origin/path.apk>
  --signer-sha256 <sha256:lowercase-hex>
  --apk-sha256 <sha256:lowercase-hex>
  --apk-size <bytes>
  --version-code <positive-integer>
  --version-name <token>
  --sequence <positive-integer>
  --issued-at-ms <unix-ms>
  --expires-at-ms <unix-ms>
  --out <path-or-dash>

Use --out - for stdout. The seed is never emitted."
    );
}

const KNOWN_OPTIONS: &[&str] = &[
    "--key-id",
    "--channel",
    "--expected-public-key",
    "--manifest-id",
    "--package",
    "--ring",
    "--origin",
    "--apk-url",
    "--signer-sha256",
    "--apk-sha256",
    "--apk-size",
    "--version-code",
    "--version-name",
    "--sequence",
    "--issued-at-ms",
    "--expires-at-ms",
    "--out",
];

#[cfg(test)]
mod tests {
    use super::*;

    fn arguments() -> Vec<String> {
        [
            "--channel",
            "alpha",
            "--key-id",
            "release-test-2026-a",
            "--expected-public-key",
            "6kpsY-KcUgq-9VB7Ey7F-ZVHdq6-vnuSQh7qaRRG0iw",
            "--manifest-id",
            "rusty-kiosk.alpha.101",
            "--package",
            "io.github.mesmerprism.rustykiosk",
            "--ring",
            "alpha",
            "--origin",
            "https://updates.mesmerprism.com",
            "--apk-url",
            "https://updates.mesmerprism.com/rusty-kiosk/alpha/app.apk",
            "--signer-sha256",
            "sha256:23bb7bb81143a81f216118af35960aaee2468e9880b94e07574cac0a9239dcf6",
            "--apk-sha256",
            "sha256:1111111111111111111111111111111111111111111111111111111111111111",
            "--apk-size",
            "123456",
            "--version-code",
            "101",
            "--version-name",
            "0.1.1",
            "--sequence",
            "41",
            "--issued-at-ms",
            "1999999900000",
            "--expires-at-ms",
            "2000000500000",
            "--out",
            "-",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect()
    }

    #[test]
    fn explicit_arguments_build_and_self_verify() {
        let args = parse_args(arguments()).expect("arguments");
        let output = build_and_self_verify(&args, &[7_u8; 32]).expect("signed envelope");
        let envelope: PackageUpdateManifestEnvelope =
            serde_json::from_slice(&output).expect("envelope JSON");
        assert_eq!(envelope.key_id, "release-test-2026-a");
        assert_eq!(
            envelope.signed.artifact.package_name,
            "io.github.mesmerprism.rustykiosk"
        );
        assert_eq!(envelope.signed.sequence, 41);
    }

    #[test]
    fn signing_seed_must_match_explicit_trusted_public_key() {
        let mut args = parse_args(arguments()).expect("arguments");
        args.expected_public_key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".to_owned();
        assert!(build_and_self_verify(&args, &[7_u8; 32])
            .expect_err("mismatched trusted key")
            .contains("does not match"));
    }

    #[test]
    fn parser_rejects_missing_duplicate_unknown_and_nonnumeric_options() {
        let mut missing = arguments();
        missing.drain(2..4);
        assert!(parse_args(missing)
            .expect_err("missing key id")
            .contains("--key-id"));

        let mut duplicate = arguments();
        duplicate.extend(["--key-id".to_owned(), "other".to_owned()]);
        assert!(parse_args(duplicate)
            .expect_err("duplicate key id")
            .contains("duplicate"));

        let mut unknown = arguments();
        unknown.extend(["--secret-on-command-line".to_owned(), "no".to_owned()]);
        assert!(parse_args(unknown)
            .expect_err("unknown option")
            .contains("unknown option"));

        let mut nonnumeric = arguments();
        let index = nonnumeric
            .iter()
            .position(|value| value == "--sequence")
            .expect("sequence option");
        nonnumeric[index + 1] = "forty-one".to_owned();
        assert!(parse_args(nonnumeric)
            .expect_err("nonnumeric sequence")
            .contains("unsigned decimal integer"));
    }

    #[test]
    fn self_verification_rejects_origin_confusion_and_invalid_expiry() {
        let mut origin = parse_args(arguments()).expect("arguments");
        origin.apk_url = "https://updates.mesmerprism.com.evil.example/app.apk".to_owned();
        assert!(build_and_self_verify(&origin, &[7_u8; 32])
            .expect_err("origin confusion")
            .contains("origin_mismatch"));

        let mut expiry = parse_args(arguments()).expect("arguments");
        expiry.expires_at_ms = expiry.issued_at_ms;
        assert!(build_and_self_verify(&expiry, &[7_u8; 32])
            .expect_err("invalid expiry")
            .contains("later than"));
    }
}
