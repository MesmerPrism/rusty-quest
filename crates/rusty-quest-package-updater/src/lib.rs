//! Signed, source-only package-update admission for Quest updater sidecars.
//!
//! This crate deliberately performs no network access, APK parsing, package
//! installation, Android calls, or Fleet command admission. A caller supplies
//! the exact policy, trusted release keys, current rollback state, clock, and
//! post-download APK observations. The contract returns deterministic receipts
//! and advances rollback state only after the observed APK matches the admitted
//! signed manifest.

use std::collections::{BTreeMap, BTreeSet};

use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Envelope schema identifier.
pub const ENVELOPE_SCHEMA: &str = "rusty.quest.package_update_manifest_envelope.v1";
/// Signed manifest schema identifier.
pub const MANIFEST_SCHEMA: &str = "rusty.quest.package_update_manifest.v1";
/// Receipt schema identifier.
pub const RECEIPT_SCHEMA: &str = "rusty.quest.package_update_receipt.v1";
/// Rollback state schema identifier.
pub const ROLLBACK_STATE_SCHEMA: &str = "rusty.quest.package_update_rollback_state.v1";
/// Only accepted signature algorithm token.
pub const SIGNATURE_ALGORITHM: &str = "Ed25519";
/// Domain separator prepended to the JCS signed payload.
pub const SIGNATURE_DOMAIN: &[u8] = b"rusty.quest.package_update_manifest.v1\0";
/// Largest integer exactly interoperable with the RFC 8785 / I-JSON number model.
pub const MAX_JCS_SAFE_INTEGER: u64 = 9_007_199_254_740_991;

/// Strict v1 signed envelope. Exactly one APK artifact is carried.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct PackageUpdateManifestEnvelope {
    /// Envelope schema identifier.
    pub schema: String,
    /// Release-key identifier resolved only from the caller's registry.
    pub key_id: String,
    /// Exact signature algorithm token.
    pub algorithm: String,
    /// Canonical unpadded base64url Ed25519 signature.
    pub signature: String,
    /// The single signed v1 update manifest.
    pub signed: PackageUpdateManifest,
}

/// Strict one-APK v1 update manifest.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct PackageUpdateManifest {
    /// Signed manifest schema identifier.
    pub schema: String,
    /// Stable release-manifest identity.
    pub manifest_id: String,
    /// Monotonic sequence within the package and rollout ring.
    pub sequence: u64,
    /// Manifest issue time in Unix milliseconds.
    pub issued_at_ms: u64,
    /// Manifest expiry time in Unix milliseconds.
    pub expires_at_ms: u64,
    /// Exact closed release channel.
    pub channel: String,
    /// Exact rollout ring selected by policy.
    pub rollout_ring: String,
    /// The sole APK artifact.
    pub artifact: PackageUpdateArtifact,
}

/// Signed identity and download facts for one APK.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct PackageUpdateArtifact {
    /// Exact Android application package name.
    pub package_name: String,
    /// Strictly increasing Android version code.
    pub version_code: u64,
    /// Display version retained for operator receipts.
    pub version_name: String,
    /// HTTPS download URL under the policy's exact origin.
    pub apk_url: String,
    /// `sha256:` followed by exactly 64 lowercase hexadecimal digits.
    pub apk_sha256: String,
    /// Exact positive APK byte size.
    pub apk_size_bytes: u64,
    /// SHA-256 identity of the APK signing certificate.
    pub signer_sha256: String,
}

/// One known Ed25519 release verification key.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ReleaseKeyRecord {
    /// Stable key identifier used by envelopes.
    pub key_id: String,
    /// Canonical unpadded base64url raw 32-byte Ed25519 public key.
    pub public_key: String,
}

/// Closed registry of release verification keys.
#[derive(Clone, Debug, Default)]
pub struct ReleaseKeyRegistry {
    keys: BTreeMap<String, VerifyingKey>,
}

impl ReleaseKeyRegistry {
    /// Builds a closed registry, rejecting malformed or duplicate records.
    ///
    /// # Errors
    ///
    /// Returns a stable validation error for a malformed identifier, non-
    /// canonical base64url key, wrong key length, duplicate id, or invalid key.
    pub fn from_records(
        records: impl IntoIterator<Item = ReleaseKeyRecord>,
    ) -> Result<Self, PackageUpdateError> {
        let mut keys = BTreeMap::new();
        let mut key_material = BTreeSet::new();
        for record in records {
            validate_token("key_id", &record.key_id, 1, 96)?;
            let bytes = decode_base64url_canonical(&record.public_key)?;
            let key_bytes: [u8; 32] = bytes.try_into().map_err(|_| {
                PackageUpdateError::new(
                    "invalid_release_key",
                    "release public key must contain exactly 32 bytes",
                )
            })?;
            let key = VerifyingKey::from_bytes(&key_bytes).map_err(|_| {
                PackageUpdateError::new(
                    "invalid_release_key",
                    "release public key is not a valid Ed25519 key",
                )
            })?;
            if keys.contains_key(&record.key_id) {
                return Err(PackageUpdateError::new(
                    "duplicate_release_key",
                    "release key identifiers must be unique",
                ));
            }
            if !key_material.insert(key_bytes) {
                return Err(PackageUpdateError::new(
                    "duplicate_release_key_material",
                    "one release public key must not be registered under multiple identifiers",
                ));
            }
            keys.insert(record.key_id, key);
        }
        if keys.is_empty() {
            return Err(PackageUpdateError::new(
                "empty_release_key_registry",
                "at least one known release key is required",
            ));
        }
        Ok(Self { keys })
    }

    fn get(&self, key_id: &str) -> Option<&VerifyingKey> {
        self.keys.get(key_id)
    }
}

/// Caller-owned exact update policy.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct PackageUpdatePolicy {
    /// Exact closed release channel packaged into the updater.
    pub expected_channel: String,
    /// Canonical HTTPS origin, without path, query, fragment, or userinfo.
    pub expected_https_origin: String,
    /// Exact package identity this policy may update.
    pub expected_package_name: String,
    /// Exact rollout ring this device consumes.
    pub expected_rollout_ring: String,
    /// Exact allowed signing-certificate SHA-256.
    pub expected_signer_sha256: String,
    /// Exact manifest verification key id packaged into the updater.
    pub expected_key_id: String,
    /// Exact canonical public key packaged into the updater.
    pub expected_public_key: String,
    /// Current installed version; candidates must be strictly newer.
    pub installed_version_code: u64,
    /// Smallest target version admitted by this policy.
    pub minimum_target_version_code: u64,
    /// Largest target version admitted by this policy.
    pub maximum_target_version_code: u64,
    /// Largest admitted APK.
    pub maximum_apk_size_bytes: u64,
    /// Largest signed validity window.
    pub maximum_manifest_validity_ms: u64,
    /// Maximum tolerated issue time ahead of the verifier clock.
    pub maximum_future_issue_skew_ms: u64,
}

impl PackageUpdatePolicy {
    fn validate(&self) -> Result<(), PackageUpdateError> {
        validate_https_origin(&self.expected_https_origin)?;
        validate_token("channel", &self.expected_channel, 1, 32)?;
        validate_package_name(&self.expected_package_name)?;
        validate_token("rollout_ring", &self.expected_rollout_ring, 1, 32)?;
        validate_sha256_identity(&self.expected_signer_sha256)?;
        validate_token("key_id", &self.expected_key_id, 1, 96)?;
        let public_key = decode_base64url_canonical(&self.expected_public_key)?;
        if public_key.len() != 32 {
            return Err(PackageUpdateError::new(
                "invalid_release_key",
                "policy release public key must contain exactly 32 bytes",
            ));
        }
        if self.minimum_target_version_code == 0
            || self.minimum_target_version_code > self.maximum_target_version_code
            || self.maximum_apk_size_bytes == 0
            || self.maximum_manifest_validity_ms == 0
            || [
                self.installed_version_code,
                self.minimum_target_version_code,
                self.maximum_target_version_code,
                self.maximum_apk_size_bytes,
                self.maximum_manifest_validity_ms,
                self.maximum_future_issue_skew_ms,
            ]
            .into_iter()
            .any(|value| value > MAX_JCS_SAFE_INTEGER)
        {
            return Err(PackageUpdateError::new(
                "invalid_policy",
                "version, size, and validity policy bounds must be positive and ordered",
            ));
        }
        Ok(())
    }
}

/// Persisted anti-rollback checkpoint for one package and ring.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct PackageUpdateCheckpoint {
    /// Exact closed release channel.
    pub channel: String,
    /// Exact package identity.
    pub package_name: String,
    /// Exact rollout ring.
    pub rollout_ring: String,
    /// APK signer identity in the closed rollback tuple.
    pub signer_sha256: String,
    /// Manifest verification key id in the closed rollback tuple.
    pub key_id: String,
    /// Manifest verification public key in the closed rollback tuple.
    pub public_key: String,
    /// Artifact HTTPS origin in the closed rollback tuple.
    pub https_origin: String,
    /// Highest successfully installed signed manifest sequence.
    pub sequence: u64,
    /// Highest successfully installed version code.
    pub version_code: u64,
    /// Canonical SHA-256 of the successfully installed signed manifest.
    pub signed_manifest_sha256: String,
}

/// Caller-persisted anti-rollback state.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct PackageUpdateRollbackState {
    /// State schema identifier.
    pub schema: String,
    /// Canonically sorted checkpoints, one per package/ring tuple.
    pub checkpoints: Vec<PackageUpdateCheckpoint>,
}

impl Default for PackageUpdateRollbackState {
    fn default() -> Self {
        Self {
            schema: ROLLBACK_STATE_SCHEMA.to_owned(),
            checkpoints: Vec::new(),
        }
    }
}

impl PackageUpdateRollbackState {
    fn validate(&self) -> Result<(), PackageUpdateError> {
        if self.schema != ROLLBACK_STATE_SCHEMA {
            return Err(PackageUpdateError::new(
                "wrong_rollback_state_schema",
                "rollback state schema is not supported",
            ));
        }
        let mut prior_key: Option<(String, String, String, String, String, String, String)> = None;
        let mut seen = BTreeSet::new();
        for checkpoint in &self.checkpoints {
            validate_package_name(&checkpoint.package_name)?;
            validate_token("channel", &checkpoint.channel, 1, 32)?;
            validate_token("rollout_ring", &checkpoint.rollout_ring, 1, 32)?;
            validate_sha256_identity(&checkpoint.signer_sha256)?;
            validate_token("key_id", &checkpoint.key_id, 1, 96)?;
            if decode_base64url_canonical(&checkpoint.public_key)?.len() != 32 {
                return Err(PackageUpdateError::new(
                    "invalid_rollback_checkpoint",
                    "rollback public key must contain exactly 32 bytes",
                ));
            }
            validate_https_origin(&checkpoint.https_origin)?;
            validate_sha256_identity(&checkpoint.signed_manifest_sha256)?;
            if checkpoint.sequence == 0 || checkpoint.version_code == 0 {
                return Err(PackageUpdateError::new(
                    "invalid_rollback_checkpoint",
                    "rollback sequence and version must be positive",
                ));
            }
            validate_jcs_integers([checkpoint.sequence, checkpoint.version_code])?;
            let key = (
                checkpoint.channel.clone(),
                checkpoint.package_name.clone(),
                checkpoint.rollout_ring.clone(),
                checkpoint.signer_sha256.clone(),
                checkpoint.key_id.clone(),
                checkpoint.public_key.clone(),
                checkpoint.https_origin.clone(),
            );
            if prior_key.as_ref().is_some_and(|prior| prior >= &key) || !seen.insert(key.clone()) {
                return Err(PackageUpdateError::new(
                    "noncanonical_rollback_state",
                    "rollback checkpoints must be a strict full-tuple sorted set",
                ));
            }
            prior_key = Some(key);
        }
        Ok(())
    }

    fn checkpoint(
        &self,
        channel: &str,
        package_name: &str,
        rollout_ring: &str,
        signer_sha256: &str,
        key_id: &str,
        public_key: &str,
        https_origin: &str,
    ) -> Option<&PackageUpdateCheckpoint> {
        self.checkpoints.iter().find(|checkpoint| {
            checkpoint.channel == channel
                && checkpoint.package_name == package_name
                && checkpoint.rollout_ring == rollout_ring
                && checkpoint.signer_sha256 == signer_sha256
                && checkpoint.key_id == key_id
                && checkpoint.public_key == public_key
                && checkpoint.https_origin == https_origin
        })
    }

    fn apply(&mut self, checkpoint: PackageUpdateCheckpoint) {
        if let Some(existing) = self.checkpoints.iter_mut().find(|existing| {
            existing.package_name == checkpoint.package_name
                && existing.channel == checkpoint.channel
                && existing.rollout_ring == checkpoint.rollout_ring
                && existing.signer_sha256 == checkpoint.signer_sha256
                && existing.key_id == checkpoint.key_id
                && existing.public_key == checkpoint.public_key
                && existing.https_origin == checkpoint.https_origin
        }) {
            *existing = checkpoint;
        } else {
            self.checkpoints.push(checkpoint);
            self.checkpoints.sort_by(|left, right| {
                (
                    &left.channel,
                    &left.package_name,
                    &left.rollout_ring,
                    &left.signer_sha256,
                    &left.key_id,
                    &left.public_key,
                    &left.https_origin,
                )
                    .cmp(&(
                        &right.channel,
                        &right.package_name,
                        &right.rollout_ring,
                        &right.signer_sha256,
                        &right.key_id,
                        &right.public_key,
                        &right.https_origin,
                    ))
            });
        }
    }
}

/// Downloaded APK facts supplied by a platform-specific inspector.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ObservedApk {
    /// APK package identity observed from the archive.
    pub package_name: String,
    /// APK version code observed from the archive.
    pub version_code: u64,
    /// APK signing-certificate SHA-256 observed from the archive.
    pub signer_sha256: String,
    /// SHA-256 of the exact downloaded APK bytes.
    pub apk_sha256: String,
    /// Exact downloaded APK byte size.
    pub apk_size_bytes: u64,
}

/// Stable receipt decision.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PackageUpdateDecision {
    /// The stage passed.
    Accepted,
    /// The stage failed closed.
    Rejected,
}

/// Stable receipt stage.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PackageUpdateReceiptStage {
    /// Signed manifest admission before download or installation.
    ManifestAdmission,
    /// Observed APK commit after the platform installer reports success.
    InstallCommit,
}

/// Deterministic package-update receipt.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct PackageUpdateReceipt {
    /// Receipt schema identifier.
    pub schema: String,
    /// Receipt stage.
    pub stage: PackageUpdateReceiptStage,
    /// Stable decision.
    pub decision: PackageUpdateDecision,
    /// Stable result code.
    pub code: String,
    /// Caller-supplied observation time.
    pub observed_at_ms: u64,
    /// SHA-256 of the exact envelope input bytes, if this is an admission.
    pub envelope_sha256: Option<String>,
    /// SHA-256 of the JCS signed manifest, when parsing succeeded.
    pub signed_manifest_sha256: Option<String>,
    /// Release key id, when parsing succeeded.
    pub key_id: Option<String>,
    /// Manifest id, when parsing succeeded.
    pub manifest_id: Option<String>,
    /// Closed release channel, when available.
    pub channel: Option<String>,
    /// Package identity, when available.
    pub package_name: Option<String>,
    /// Rollout ring, when available.
    pub rollout_ring: Option<String>,
    /// Candidate sequence, when available.
    pub sequence: Option<u64>,
    /// Candidate version, when available.
    pub version_code: Option<u64>,
    /// Prior persisted checkpoint.
    pub prior_checkpoint: Option<PackageUpdateCheckpoint>,
    /// Checkpoint produced by a successful install commit.
    pub accepted_checkpoint: Option<PackageUpdateCheckpoint>,
    /// Whether the caller should persist a changed rollback state.
    pub state_changed: bool,
}

/// A verified manifest and its deterministic admission receipt.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ManifestAdmission {
    /// Admission receipt.
    pub receipt: PackageUpdateReceipt,
    /// Verified manifest, present only when admission passed.
    pub verified: Option<VerifiedPackageUpdate>,
}

/// Signed manifest facts safe to carry into download and install verification.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct VerifiedPackageUpdate {
    /// Release key id that verified the signature.
    key_id: String,
    /// Canonical public key bound by the closed channel tuple.
    public_key: String,
    /// Canonical HTTPS origin bound by the closed channel tuple.
    https_origin: String,
    /// Canonical SHA-256 of the signed manifest.
    signed_manifest_sha256: String,
    /// Verified signed manifest.
    manifest: PackageUpdateManifest,
}

impl VerifiedPackageUpdate {
    /// Returns the release key id that verified this manifest.
    #[must_use]
    pub fn key_id(&self) -> &str {
        &self.key_id
    }

    /// Returns the canonical signed-manifest SHA-256.
    #[must_use]
    pub fn signed_manifest_sha256(&self) -> &str {
        &self.signed_manifest_sha256
    }

    /// Returns the verified signed manifest.
    #[must_use]
    pub fn manifest(&self) -> &PackageUpdateManifest {
        &self.manifest
    }
}

/// Stable contract error.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PackageUpdateError {
    /// Stable error code.
    pub code: &'static str,
    /// Static diagnostic detail.
    pub message: &'static str,
}

impl PackageUpdateError {
    const fn new(code: &'static str, message: &'static str) -> Self {
        Self { code, message }
    }
}

impl std::fmt::Display for PackageUpdateError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for PackageUpdateError {}

/// Produces RFC 8785 JCS bytes for a signed manifest.
///
/// # Errors
///
/// Returns `canonicalization_failed` if serialization fails.
pub fn canonical_signed_manifest(
    manifest: &PackageUpdateManifest,
) -> Result<Vec<u8>, PackageUpdateError> {
    serde_jcs::to_vec(manifest).map_err(|_| {
        PackageUpdateError::new(
            "canonicalization_failed",
            "signed manifest could not be JCS canonicalized",
        )
    })
}

/// Produces the exact domain-separated Ed25519 signing input.
///
/// # Errors
///
/// Returns `canonicalization_failed` if JCS serialization fails.
pub fn manifest_signing_bytes(
    manifest: &PackageUpdateManifest,
) -> Result<Vec<u8>, PackageUpdateError> {
    let canonical = canonical_signed_manifest(manifest)?;
    let mut signing = Vec::with_capacity(SIGNATURE_DOMAIN.len() + canonical.len());
    signing.extend_from_slice(SIGNATURE_DOMAIN);
    signing.extend_from_slice(&canonical);
    Ok(signing)
}

/// Verifies and policy-admits one signed one-APK manifest without changing
/// rollback state.
#[must_use]
pub fn verify_manifest(
    envelope_json: &[u8],
    keys: &ReleaseKeyRegistry,
    policy: &PackageUpdatePolicy,
    rollback_state: &PackageUpdateRollbackState,
    now_ms: u64,
) -> ManifestAdmission {
    let envelope_sha256 = sha256_identity(envelope_json);
    if now_ms > MAX_JCS_SAFE_INTEGER {
        return ManifestAdmission {
            receipt: rejected_admission(
                "observation_time_out_of_range",
                0,
                envelope_sha256,
                None,
                None,
            ),
            verified: None,
        };
    }
    let envelope: PackageUpdateManifestEnvelope = match serde_json::from_slice(envelope_json) {
        Ok(envelope) => envelope,
        Err(_) => {
            return ManifestAdmission {
                receipt: rejected_admission(
                    "malformed_envelope",
                    now_ms,
                    envelope_sha256,
                    None,
                    None,
                ),
                verified: None,
            };
        }
    };

    let canonical = canonical_signed_manifest(&envelope.signed).ok();
    let signed_digest = canonical.as_deref().map(sha256_identity);
    let prior = rollback_state
        .checkpoint(
            &envelope.signed.channel,
            &envelope.signed.artifact.package_name,
            &envelope.signed.rollout_ring,
            &envelope.signed.artifact.signer_sha256,
            &envelope.key_id,
            &policy.expected_public_key,
            &policy.expected_https_origin,
        )
        .cloned();
    let base = receipt_base(
        PackageUpdateReceiptStage::ManifestAdmission,
        now_ms,
        Some(envelope_sha256),
        signed_digest.clone(),
        Some(envelope.key_id.clone()),
        Some(&envelope.signed),
        prior,
    );

    let outcome = verify_parsed_envelope(
        &envelope,
        canonical.as_deref(),
        keys,
        policy,
        rollback_state,
        now_ms,
    );
    match outcome {
        Ok(()) => ManifestAdmission {
            receipt: accepted_receipt(base, "manifest_accepted", None),
            verified: Some(VerifiedPackageUpdate {
                key_id: envelope.key_id,
                public_key: policy.expected_public_key.clone(),
                https_origin: policy.expected_https_origin.clone(),
                signed_manifest_sha256: signed_digest
                    .expect("successful canonicalization always has a digest"),
                manifest: envelope.signed,
            }),
        },
        Err(error) => ManifestAdmission {
            receipt: rejected_receipt(base, error.code),
            verified: None,
        },
    }
}

/// Commits a successfully installed APK observation and advances anti-rollback
/// state only when it exactly matches the admitted signed manifest.
#[must_use]
pub fn commit_installed_apk(
    verified: &VerifiedPackageUpdate,
    observed: &ObservedApk,
    rollback_state: &mut PackageUpdateRollbackState,
    observed_at_ms: u64,
) -> PackageUpdateReceipt {
    let manifest = &verified.manifest;
    let prior = rollback_state
        .checkpoint(
            &manifest.channel,
            &manifest.artifact.package_name,
            &manifest.rollout_ring,
            &manifest.artifact.signer_sha256,
            &verified.key_id,
            &verified.public_key,
            &verified.https_origin,
        )
        .cloned();
    if observed_at_ms > MAX_JCS_SAFE_INTEGER {
        return rejected_receipt(
            receipt_base(
                PackageUpdateReceiptStage::InstallCommit,
                0,
                None,
                Some(verified.signed_manifest_sha256.clone()),
                Some(verified.key_id.clone()),
                Some(manifest),
                prior,
            ),
            "observation_time_out_of_range",
        );
    }
    let base = receipt_base(
        PackageUpdateReceiptStage::InstallCommit,
        observed_at_ms,
        None,
        Some(verified.signed_manifest_sha256.clone()),
        Some(verified.key_id.clone()),
        Some(manifest),
        prior.clone(),
    );

    let validation = rollback_state
        .validate()
        .and_then(|()| {
            if observed_at_ms >= manifest.expires_at_ms {
                Err(PackageUpdateError::new(
                    "manifest_expired",
                    "manifest expired before the installed APK was committed",
                ))
            } else {
                Ok(())
            }
        })
        .and_then(|()| validate_observed_apk(&manifest.artifact, observed))
        .and_then(|()| validate_sequence(manifest, prior.as_ref()));
    if let Err(error) = validation {
        return rejected_receipt(base, error.code);
    }

    let checkpoint = PackageUpdateCheckpoint {
        channel: manifest.channel.clone(),
        package_name: manifest.artifact.package_name.clone(),
        rollout_ring: manifest.rollout_ring.clone(),
        signer_sha256: manifest.artifact.signer_sha256.clone(),
        key_id: verified.key_id.clone(),
        public_key: verified.public_key.clone(),
        https_origin: verified.https_origin.clone(),
        sequence: manifest.sequence,
        version_code: manifest.artifact.version_code,
        signed_manifest_sha256: verified.signed_manifest_sha256.clone(),
    };
    rollback_state.apply(checkpoint.clone());
    accepted_receipt(base, "install_committed", Some(checkpoint))
}

fn verify_parsed_envelope(
    envelope: &PackageUpdateManifestEnvelope,
    canonical: Option<&[u8]>,
    keys: &ReleaseKeyRegistry,
    policy: &PackageUpdatePolicy,
    rollback_state: &PackageUpdateRollbackState,
    now_ms: u64,
) -> Result<(), PackageUpdateError> {
    policy.validate()?;
    rollback_state.validate()?;
    if envelope.schema != ENVELOPE_SCHEMA || envelope.signed.schema != MANIFEST_SCHEMA {
        return Err(PackageUpdateError::new(
            "wrong_schema",
            "envelope or signed manifest schema is not supported",
        ));
    }
    if envelope.algorithm != SIGNATURE_ALGORITHM {
        return Err(PackageUpdateError::new(
            "unsupported_algorithm",
            "only exact Ed25519 signatures are supported",
        ));
    }
    validate_token("key_id", &envelope.key_id, 1, 96)?;
    validate_manifest_fields(&envelope.signed)?;

    let key = keys.get(&envelope.key_id).ok_or_else(|| {
        PackageUpdateError::new(
            "unknown_release_key",
            "manifest key id is absent from the known-key registry",
        )
    })?;
    if envelope.key_id != policy.expected_key_id {
        return Err(PackageUpdateError::new(
            "key_id_mismatch",
            "manifest key id does not match the closed channel tuple",
        ));
    }
    if encode_base64url(&key.to_bytes()) != policy.expected_public_key {
        return Err(PackageUpdateError::new(
            "public_key_mismatch",
            "manifest verification key does not match the closed channel tuple",
        ));
    }
    let signature_bytes = decode_base64url_canonical(&envelope.signature)?;
    let signature_array: [u8; 64] = signature_bytes.try_into().map_err(|_| {
        PackageUpdateError::new(
            "invalid_signature_encoding",
            "Ed25519 signature must contain exactly 64 bytes",
        )
    })?;
    let signature = Signature::from_bytes(&signature_array);
    let canonical = canonical.ok_or_else(|| {
        PackageUpdateError::new(
            "canonicalization_failed",
            "signed manifest could not be JCS canonicalized",
        )
    })?;
    let mut signing = Vec::with_capacity(SIGNATURE_DOMAIN.len() + canonical.len());
    signing.extend_from_slice(SIGNATURE_DOMAIN);
    signing.extend_from_slice(canonical);
    key.verify_strict(&signing, &signature).map_err(|_| {
        PackageUpdateError::new(
            "signature_verification_failed",
            "manifest signature does not verify under the selected release key",
        )
    })?;

    validate_time(&envelope.signed, policy, now_ms)?;
    validate_policy(&envelope.signed, policy)?;
    validate_sequence(
        &envelope.signed,
        rollback_state.checkpoint(
            &envelope.signed.channel,
            &envelope.signed.artifact.package_name,
            &envelope.signed.rollout_ring,
            &envelope.signed.artifact.signer_sha256,
            &envelope.key_id,
            &policy.expected_public_key,
            &policy.expected_https_origin,
        ),
    )
}

fn validate_manifest_fields(manifest: &PackageUpdateManifest) -> Result<(), PackageUpdateError> {
    validate_token("manifest_id", &manifest.manifest_id, 1, 128)?;
    validate_token("channel", &manifest.channel, 1, 32)?;
    validate_token("rollout_ring", &manifest.rollout_ring, 1, 32)?;
    if manifest.sequence == 0 {
        return Err(PackageUpdateError::new(
            "invalid_sequence",
            "manifest sequence must be positive",
        ));
    }
    validate_package_name(&manifest.artifact.package_name)?;
    validate_token("version_name", &manifest.artifact.version_name, 1, 64)?;
    if manifest.artifact.version_code == 0 {
        return Err(PackageUpdateError::new(
            "invalid_version",
            "target version code must be positive",
        ));
    }
    validate_sha256_identity(&manifest.artifact.apk_sha256)?;
    validate_sha256_identity(&manifest.artifact.signer_sha256)?;
    if manifest.artifact.apk_size_bytes == 0 {
        return Err(PackageUpdateError::new(
            "invalid_apk_size",
            "APK size must be positive",
        ));
    }
    validate_jcs_integers([
        manifest.sequence,
        manifest.issued_at_ms,
        manifest.expires_at_ms,
        manifest.artifact.version_code,
        manifest.artifact.apk_size_bytes,
    ])?;
    validate_https_url(&manifest.artifact.apk_url)
}

fn validate_time(
    manifest: &PackageUpdateManifest,
    policy: &PackageUpdatePolicy,
    now_ms: u64,
) -> Result<(), PackageUpdateError> {
    let validity = manifest
        .expires_at_ms
        .checked_sub(manifest.issued_at_ms)
        .ok_or_else(|| {
            PackageUpdateError::new(
                "invalid_validity_window",
                "manifest expiry must be later than issue time",
            )
        })?;
    if validity == 0 || validity > policy.maximum_manifest_validity_ms {
        return Err(PackageUpdateError::new(
            "invalid_validity_window",
            "manifest validity exceeds the policy maximum",
        ));
    }
    if manifest.expires_at_ms <= now_ms {
        return Err(PackageUpdateError::new(
            "manifest_expired",
            "manifest has expired",
        ));
    }
    let latest_issue = now_ms.saturating_add(policy.maximum_future_issue_skew_ms);
    if manifest.issued_at_ms > latest_issue {
        return Err(PackageUpdateError::new(
            "manifest_from_future",
            "manifest issue time exceeds allowed clock skew",
        ));
    }
    Ok(())
}

fn validate_policy(
    manifest: &PackageUpdateManifest,
    policy: &PackageUpdatePolicy,
) -> Result<(), PackageUpdateError> {
    let artifact = &manifest.artifact;
    if manifest.channel != policy.expected_channel {
        return Err(PackageUpdateError::new(
            "channel_mismatch",
            "manifest channel does not match the closed channel tuple",
        ));
    }
    if artifact.package_name != policy.expected_package_name {
        return Err(PackageUpdateError::new(
            "package_mismatch",
            "manifest package does not match the exact policy package",
        ));
    }
    if manifest.rollout_ring != policy.expected_rollout_ring {
        return Err(PackageUpdateError::new(
            "rollout_ring_mismatch",
            "manifest rollout ring does not match the exact policy ring",
        ));
    }
    if artifact.signer_sha256 != policy.expected_signer_sha256 {
        return Err(PackageUpdateError::new(
            "signer_mismatch",
            "APK signer does not match the exact policy signer",
        ));
    }
    if artifact.version_code <= policy.installed_version_code
        || artifact.version_code < policy.minimum_target_version_code
        || artifact.version_code > policy.maximum_target_version_code
    {
        return Err(PackageUpdateError::new(
            "version_policy_rejected",
            "target version is not a strictly newer version inside policy bounds",
        ));
    }
    if artifact.apk_size_bytes > policy.maximum_apk_size_bytes {
        return Err(PackageUpdateError::new(
            "apk_size_policy_rejected",
            "APK exceeds the policy size limit",
        ));
    }
    if !url_has_exact_origin(&artifact.apk_url, &policy.expected_https_origin) {
        return Err(PackageUpdateError::new(
            "origin_mismatch",
            "APK URL does not use the exact configured HTTPS origin",
        ));
    }
    Ok(())
}

fn validate_sequence(
    manifest: &PackageUpdateManifest,
    prior: Option<&PackageUpdateCheckpoint>,
) -> Result<(), PackageUpdateError> {
    if let Some(prior) = prior {
        if manifest.sequence <= prior.sequence {
            return Err(PackageUpdateError::new(
                "sequence_rollback",
                "manifest sequence does not advance the persisted checkpoint",
            ));
        }
        if manifest.artifact.version_code <= prior.version_code {
            return Err(PackageUpdateError::new(
                "version_rollback",
                "target version does not advance the persisted checkpoint",
            ));
        }
    }
    Ok(())
}

fn validate_observed_apk(
    expected: &PackageUpdateArtifact,
    observed: &ObservedApk,
) -> Result<(), PackageUpdateError> {
    validate_package_name(&observed.package_name)?;
    validate_sha256_identity(&observed.signer_sha256)?;
    validate_sha256_identity(&observed.apk_sha256)?;
    validate_jcs_integers([observed.version_code, observed.apk_size_bytes])?;
    if observed.package_name != expected.package_name {
        return Err(PackageUpdateError::new(
            "observed_package_mismatch",
            "observed APK package differs from the signed manifest",
        ));
    }
    if observed.version_code != expected.version_code {
        return Err(PackageUpdateError::new(
            "observed_version_mismatch",
            "observed APK version differs from the signed manifest",
        ));
    }
    if observed.signer_sha256 != expected.signer_sha256 {
        return Err(PackageUpdateError::new(
            "observed_signer_mismatch",
            "observed APK signer differs from the signed manifest",
        ));
    }
    if observed.apk_sha256 != expected.apk_sha256 {
        return Err(PackageUpdateError::new(
            "observed_hash_mismatch",
            "observed APK hash differs from the signed manifest",
        ));
    }
    if observed.apk_size_bytes != expected.apk_size_bytes {
        return Err(PackageUpdateError::new(
            "observed_size_mismatch",
            "observed APK size differs from the signed manifest",
        ));
    }
    Ok(())
}

fn validate_jcs_integers(values: impl IntoIterator<Item = u64>) -> Result<(), PackageUpdateError> {
    if values.into_iter().any(|value| value > MAX_JCS_SAFE_INTEGER) {
        return Err(PackageUpdateError::new(
            "integer_out_of_jcs_range",
            "signed and receipt integers must fit the RFC 8785 I-JSON safe range",
        ));
    }
    Ok(())
}

fn validate_token(
    field: &'static str,
    value: &str,
    minimum: usize,
    maximum: usize,
) -> Result<(), PackageUpdateError> {
    if value.len() < minimum
        || value.len() > maximum
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        let (code, message) = match field {
            "key_id" => ("invalid_key_id", "key id is not a bounded canonical token"),
            "manifest_id" => (
                "invalid_manifest_id",
                "manifest id is not a bounded canonical token",
            ),
            "rollout_ring" => (
                "invalid_rollout_ring",
                "rollout ring is not a bounded canonical token",
            ),
            "version_name" => (
                "invalid_version_name",
                "version name is not a bounded canonical token",
            ),
            _ => ("invalid_token", "value is not a bounded canonical token"),
        };
        return Err(PackageUpdateError::new(code, message));
    }
    Ok(())
}

fn validate_package_name(value: &str) -> Result<(), PackageUpdateError> {
    if value.len() > 255
        || value.len() < 3
        || !value.contains('.')
        || value.split('.').any(|part| {
            part.is_empty()
                || !part
                    .bytes()
                    .next()
                    .is_some_and(|byte| byte.is_ascii_lowercase())
                || !part
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
        })
    {
        return Err(PackageUpdateError::new(
            "invalid_package_name",
            "package name is not a canonical Android application id",
        ));
    }
    Ok(())
}

fn validate_sha256_identity(value: &str) -> Result<(), PackageUpdateError> {
    let hex = value.strip_prefix("sha256:").ok_or_else(|| {
        PackageUpdateError::new(
            "invalid_sha256",
            "SHA-256 identity must use the sha256: lowercase hexadecimal form",
        )
    })?;
    if hex.len() != 64
        || !hex
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(PackageUpdateError::new(
            "invalid_sha256",
            "SHA-256 identity must contain exactly 64 lowercase hexadecimal digits",
        ));
    }
    Ok(())
}

fn validate_https_origin(origin: &str) -> Result<(), PackageUpdateError> {
    if canonical_https_origin(origin).as_deref() != Some(origin) {
        return Err(PackageUpdateError::new(
            "invalid_https_origin",
            "origin must be canonical HTTPS scheme, host, and optional non-default port only",
        ));
    }
    Ok(())
}

fn validate_https_url(url: &str) -> Result<(), PackageUpdateError> {
    if url.len() > 2048
        || !url.is_ascii()
        || url.contains('\\')
        || url.contains('#')
        || url
            .bytes()
            .any(|byte| byte.is_ascii_control() || byte == b' ')
        || !url.starts_with("https://")
        || canonical_https_origin(url).is_none()
    {
        return Err(PackageUpdateError::new(
            "invalid_apk_url",
            "APK URL must be a bounded absolute HTTPS URL without fragment or userinfo",
        ));
    }
    let after_scheme = &url["https://".len()..];
    let path_start = after_scheme.find(['/', '?', '#']).ok_or_else(|| {
        PackageUpdateError::new(
            "invalid_apk_url",
            "APK URL must contain an absolute nonempty path",
        )
    })?;
    if after_scheme.as_bytes()[path_start] != b'/' {
        return Err(PackageUpdateError::new(
            "invalid_apk_url",
            "APK URL must contain an absolute path before any query",
        ));
    }
    let path_and_query = &after_scheme[path_start..];
    let path_end = path_and_query.find('?').unwrap_or(path_and_query.len());
    let path = &path_and_query[..path_end];
    if path == "/"
        || path.starts_with("//")
        || path
            .split('/')
            .any(|segment| segment == "." || segment == "..")
    {
        return Err(PackageUpdateError::new(
            "invalid_apk_url",
            "APK URL path must be nonempty and must not traverse",
        ));
    }
    Ok(())
}

fn canonical_https_origin(url: &str) -> Option<String> {
    if !url.is_ascii() || !url.starts_with("https://") || url.contains('\\') {
        return None;
    }
    let rest = &url["https://".len()..];
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..end];
    if authority.is_empty() || authority.contains('@') {
        return None;
    }
    let (host, port) = if authority.starts_with('[') {
        let close = authority.find(']')?;
        let host = &authority[..=close];
        let port = match authority.get(close + 1..) {
            Some("") => None,
            Some(suffix) => Some(suffix.strip_prefix(':')?),
            None => None,
        };
        (host, port)
    } else if let Some((host, port)) = authority.rsplit_once(':') {
        if host.contains(':') {
            return None;
        }
        (host, Some(port))
    } else {
        (authority, None)
    };
    if host.is_empty()
        || host != host.to_ascii_lowercase()
        || (!host.starts_with('[')
            && host.split('.').any(|label| {
                label.is_empty()
                    || label.starts_with('-')
                    || label.ends_with('-')
                    || !label
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
            }))
    {
        return None;
    }
    let port = match port {
        None => None,
        Some("443") | Some("") => return None,
        Some(value) => {
            let parsed: u16 = value.parse().ok()?;
            if parsed == 0 || value.starts_with('0') {
                return None;
            }
            Some(parsed)
        }
    };
    Some(match port {
        Some(port) => format!("https://{host}:{port}"),
        None => format!("https://{host}"),
    })
}

fn url_has_exact_origin(url: &str, expected_origin: &str) -> bool {
    canonical_https_origin(url).as_deref() == Some(expected_origin)
}

/// Decodes canonical unpadded base64url.
///
/// # Errors
///
/// Returns `noncanonical_base64url` for padding, a non-URL-safe alphabet,
/// impossible length, nonzero unused bits, or any non-unique encoding.
pub fn decode_base64url_canonical(value: &str) -> Result<Vec<u8>, PackageUpdateError> {
    if value.is_empty()
        || value.contains('=')
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
        || value.len() % 4 == 1
    {
        return Err(PackageUpdateError::new(
            "noncanonical_base64url",
            "value must be canonical unpadded base64url",
        ));
    }
    let mut output = Vec::with_capacity((value.len() * 3) / 4);
    let mut accumulator = 0_u32;
    let mut bits = 0_u8;
    for byte in value.bytes() {
        let sextet = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'-' => 62,
            b'_' => 63,
            _ => unreachable!("alphabet was checked"),
        };
        accumulator = (accumulator << 6) | u32::from(sextet);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            output.push(((accumulator >> bits) & 0xff) as u8);
        }
    }
    if bits > 0 && (accumulator & ((1_u32 << bits) - 1)) != 0 {
        return Err(PackageUpdateError::new(
            "noncanonical_base64url",
            "unused base64url bits must be zero",
        ));
    }
    if encode_base64url(&output) != value {
        return Err(PackageUpdateError::new(
            "noncanonical_base64url",
            "value must use the unique unpadded base64url encoding",
        ));
    }
    Ok(output)
}

/// Encodes bytes as canonical unpadded base64url.
#[must_use]
pub fn encode_base64url(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut output = String::with_capacity((bytes.len() * 4).div_ceil(3));
    for chunk in bytes.chunks(3) {
        let first = u32::from(chunk[0]);
        let second = chunk.get(1).copied().map(u32::from).unwrap_or(0);
        let third = chunk.get(2).copied().map(u32::from).unwrap_or(0);
        let word = (first << 16) | (second << 8) | third;
        output.push(ALPHABET[((word >> 18) & 63) as usize] as char);
        output.push(ALPHABET[((word >> 12) & 63) as usize] as char);
        if chunk.len() >= 2 {
            output.push(ALPHABET[((word >> 6) & 63) as usize] as char);
        }
        if chunk.len() == 3 {
            output.push(ALPHABET[(word & 63) as usize] as char);
        }
    }
    output
}

fn sha256_identity(bytes: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(bytes))
}

fn receipt_base(
    stage: PackageUpdateReceiptStage,
    observed_at_ms: u64,
    envelope_sha256: Option<String>,
    signed_manifest_sha256: Option<String>,
    key_id: Option<String>,
    manifest: Option<&PackageUpdateManifest>,
    prior_checkpoint: Option<PackageUpdateCheckpoint>,
) -> PackageUpdateReceipt {
    PackageUpdateReceipt {
        schema: RECEIPT_SCHEMA.to_owned(),
        stage,
        decision: PackageUpdateDecision::Rejected,
        code: "unresolved".to_owned(),
        observed_at_ms,
        envelope_sha256,
        signed_manifest_sha256,
        key_id,
        manifest_id: manifest.map(|value| value.manifest_id.clone()),
        channel: manifest.map(|value| value.channel.clone()),
        package_name: manifest.map(|value| value.artifact.package_name.clone()),
        rollout_ring: manifest.map(|value| value.rollout_ring.clone()),
        sequence: manifest.map(|value| value.sequence),
        version_code: manifest.map(|value| value.artifact.version_code),
        prior_checkpoint,
        accepted_checkpoint: None,
        state_changed: false,
    }
}

fn rejected_admission(
    code: &str,
    observed_at_ms: u64,
    envelope_sha256: String,
    signed_manifest_sha256: Option<String>,
    key_id: Option<String>,
) -> PackageUpdateReceipt {
    rejected_receipt(
        receipt_base(
            PackageUpdateReceiptStage::ManifestAdmission,
            observed_at_ms,
            Some(envelope_sha256),
            signed_manifest_sha256,
            key_id,
            None,
            None,
        ),
        code,
    )
}

fn rejected_receipt(mut receipt: PackageUpdateReceipt, code: &str) -> PackageUpdateReceipt {
    receipt.decision = PackageUpdateDecision::Rejected;
    receipt.code = code.to_owned();
    receipt
}

fn accepted_receipt(
    mut receipt: PackageUpdateReceipt,
    code: &str,
    checkpoint: Option<PackageUpdateCheckpoint>,
) -> PackageUpdateReceipt {
    receipt.decision = PackageUpdateDecision::Accepted;
    receipt.code = code.to_owned();
    receipt.state_changed = checkpoint.is_some();
    receipt.accepted_checkpoint = checkpoint;
    receipt
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    const FIXTURE: &[u8] =
        include_bytes!("../../../fixtures/package-updater/manifest-envelope.fixed-vector.json");

    fn policy() -> PackageUpdatePolicy {
        serde_json::from_str(include_str!(
            "../../../fixtures/package-updater/policy.valid.json"
        ))
        .expect("policy fixture")
    }

    fn rollback() -> PackageUpdateRollbackState {
        serde_json::from_str(include_str!(
            "../../../fixtures/package-updater/rollback-state.valid.json"
        ))
        .expect("rollback fixture")
    }

    fn registry() -> ReleaseKeyRegistry {
        let vector: serde_json::Value = serde_json::from_slice(FIXTURE).expect("vector JSON");
        ReleaseKeyRegistry::from_records([ReleaseKeyRecord {
            key_id: vector["key_id"].as_str().expect("key id").to_owned(),
            public_key: vector["public_key"]
                .as_str()
                .expect("public key")
                .to_owned(),
        }])
        .expect("known key")
    }

    fn envelope_bytes() -> Vec<u8> {
        let vector: serde_json::Value = serde_json::from_slice(FIXTURE).expect("vector JSON");
        serde_json::to_vec(&vector["envelope"]).expect("envelope bytes")
    }

    fn resign(mut envelope: serde_json::Value) -> Vec<u8> {
        let vector: serde_json::Value = serde_json::from_slice(FIXTURE).expect("vector JSON");
        let seed_bytes = decode_base64url_canonical(
            vector["test_private_seed"]
                .as_str()
                .expect("test-only seed"),
        )
        .expect("seed encoding");
        let seed: [u8; 32] = seed_bytes.try_into().expect("seed length");
        let signed: PackageUpdateManifest =
            serde_json::from_value(envelope["signed"].clone()).expect("signed manifest");
        let signature =
            SigningKey::from_bytes(&seed).sign(&manifest_signing_bytes(&signed).expect("signing"));
        envelope["signature"] = serde_json::Value::String(encode_base64url(&signature.to_bytes()));
        serde_json::to_vec(&envelope).expect("envelope bytes")
    }

    fn assert_rejected(envelope: &[u8], expected_code: &str) {
        let admission = verify_manifest(
            envelope,
            &registry(),
            &policy(),
            &rollback(),
            2_000_000_000_000,
        );
        assert_eq!(admission.receipt.decision, PackageUpdateDecision::Rejected);
        assert_eq!(admission.receipt.code, expected_code);
        assert!(admission.verified.is_none());
    }

    #[test]
    fn fixed_vector_jcs_domain_signature_and_admission_are_stable() {
        let vector: serde_json::Value = serde_json::from_slice(FIXTURE).expect("vector JSON");
        let envelope: PackageUpdateManifestEnvelope =
            serde_json::from_value(vector["envelope"].clone()).expect("envelope");
        let canonical = canonical_signed_manifest(&envelope.signed).expect("JCS");
        assert_eq!(
            String::from_utf8(canonical.clone()).expect("UTF-8"),
            vector["expected_jcs"].as_str().expect("expected JCS")
        );
        assert_eq!(
            sha256_identity(&manifest_signing_bytes(&envelope.signed).expect("signing bytes")),
            vector["signing_input_sha256"]
                .as_str()
                .expect("input digest")
        );
        assert_eq!(
            sha256_identity(&canonical),
            vector["signed_manifest_sha256"]
                .as_str()
                .expect("manifest digest")
        );

        let admission = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &rollback(),
            2_000_000_000_000,
        );
        assert_eq!(admission.receipt.decision, PackageUpdateDecision::Accepted);
        assert_eq!(admission.receipt.code, "manifest_accepted");
        assert!(!admission.receipt.state_changed);
        assert!(admission.verified.is_some());
        let expected_receipt: PackageUpdateReceipt = serde_json::from_str(include_str!(
            "../../../fixtures/package-updater/manifest-admission-receipt.fixed.json"
        ))
        .expect("fixed receipt");
        assert_eq!(admission.receipt, expected_receipt);
    }

    #[test]
    fn jcs_is_independent_of_envelope_object_order() {
        let mut value: serde_json::Value =
            serde_json::from_slice(&envelope_bytes()).expect("envelope");
        let signed = value["signed"].take();
        let reordered = serde_json::json!({
            "signed": signed,
            "signature": value["signature"].take(),
            "algorithm": value["algorithm"].take(),
            "key_id": value["key_id"].take(),
            "schema": value["schema"].take()
        });
        let admission = verify_manifest(
            &serde_json::to_vec_pretty(&reordered).expect("pretty envelope"),
            &registry(),
            &policy(),
            &rollback(),
            2_000_000_000_000,
        );
        assert_eq!(admission.receipt.decision, PackageUpdateDecision::Accepted);
    }

    #[test]
    fn successful_observed_apk_commit_is_the_only_state_advance() {
        let mut state = rollback();
        let admission = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &state,
            2_000_000_000_000,
        );
        let verified = admission.verified.expect("verified");
        assert_eq!(state.checkpoints[0].sequence, 40);
        let observed = ObservedApk {
            package_name: verified.manifest.artifact.package_name.clone(),
            version_code: verified.manifest.artifact.version_code,
            signer_sha256: verified.manifest.artifact.signer_sha256.clone(),
            apk_sha256: verified.manifest.artifact.apk_sha256.clone(),
            apk_size_bytes: verified.manifest.artifact.apk_size_bytes,
        };
        let receipt = commit_installed_apk(&verified, &observed, &mut state, 2_000_000_000_100);
        assert_eq!(receipt.decision, PackageUpdateDecision::Accepted);
        assert_eq!(receipt.code, "install_committed");
        assert!(receipt.state_changed);
        assert_eq!(state.checkpoints[0].sequence, 41);
        assert_eq!(state.checkpoints[0].version_code, 101);
    }

    #[test]
    fn damaged_observed_apk_does_not_advance_state() {
        let mut state = rollback();
        let admission = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &state,
            2_000_000_000_000,
        );
        let verified = admission.verified.expect("verified");
        let mut observed = ObservedApk {
            package_name: verified.manifest.artifact.package_name.clone(),
            version_code: verified.manifest.artifact.version_code,
            signer_sha256: verified.manifest.artifact.signer_sha256.clone(),
            apk_sha256: verified.manifest.artifact.apk_sha256.clone(),
            apk_size_bytes: verified.manifest.artifact.apk_size_bytes,
        };
        observed.apk_sha256 = format!("sha256:{}", "00".repeat(32));
        let before = state.clone();
        let receipt = commit_installed_apk(&verified, &observed, &mut state, 2_000_000_000_100);
        assert_eq!(receipt.decision, PackageUpdateDecision::Rejected);
        assert_eq!(receipt.code, "observed_hash_mismatch");
        assert!(!receipt.state_changed);
        assert_eq!(state, before);
    }

    #[test]
    fn every_damaged_fixture_fails_with_its_expected_code() {
        let damaged = [
            (
                "../../../fixtures/damaged/package-updater-unknown-field.json",
                include_bytes!("../../../fixtures/damaged/package-updater-unknown-field.json")
                    .as_slice(),
            ),
            (
                "../../../fixtures/damaged/package-updater-bad-signature.json",
                include_bytes!("../../../fixtures/damaged/package-updater-bad-signature.json")
                    .as_slice(),
            ),
            (
                "../../../fixtures/damaged/package-updater-padded-signature.json",
                include_bytes!("../../../fixtures/damaged/package-updater-padded-signature.json")
                    .as_slice(),
            ),
            (
                "../../../fixtures/damaged/package-updater-origin-confusion.json",
                include_bytes!("../../../fixtures/damaged/package-updater-origin-confusion.json")
                    .as_slice(),
            ),
            (
                "../../../fixtures/damaged/package-updater-expired.json",
                include_bytes!("../../../fixtures/damaged/package-updater-expired.json").as_slice(),
            ),
            (
                "../../../fixtures/damaged/package-updater-sequence-rollback.json",
                include_bytes!("../../../fixtures/damaged/package-updater-sequence-rollback.json")
                    .as_slice(),
            ),
        ];
        for (path, bytes) in damaged {
            let fixture: serde_json::Value = serde_json::from_slice(bytes).expect(path);
            let expected = fixture["expected_code"].as_str().expect("expected code");
            let envelope = serde_json::to_vec(&fixture["envelope"]).expect("envelope");
            let admission = verify_manifest(
                &envelope,
                &registry(),
                &policy(),
                &rollback(),
                2_000_000_000_000,
            );
            assert_eq!(
                admission.receipt.decision,
                PackageUpdateDecision::Rejected,
                "{path}"
            );
            assert_eq!(admission.receipt.code, expected, "{path}");
            assert!(admission.verified.is_none(), "{path}");
        }
    }

    #[test]
    fn signatures_are_domain_separated() {
        let vector: serde_json::Value = serde_json::from_slice(FIXTURE).expect("vector JSON");
        let seed_bytes = decode_base64url_canonical(
            vector["test_private_seed"]
                .as_str()
                .expect("test-only seed"),
        )
        .expect("seed encoding");
        let seed: [u8; 32] = seed_bytes.try_into().expect("seed length");
        let envelope: PackageUpdateManifestEnvelope =
            serde_json::from_value(vector["envelope"].clone()).expect("envelope");
        let canonical = canonical_signed_manifest(&envelope.signed).expect("JCS");
        let wrong_signature = SigningKey::from_bytes(&seed).sign(&canonical);
        assert_ne!(
            encode_base64url(&wrong_signature.to_bytes()),
            envelope.signature
        );
    }

    #[test]
    fn key_registry_rejects_noncanonical_base64url_and_duplicates() {
        let error = ReleaseKeyRegistry::from_records([ReleaseKeyRecord {
            key_id: "release-a".to_owned(),
            public_key: format!("{}=", "A".repeat(43)),
        }])
        .expect_err("padding is forbidden");
        assert_eq!(error.code, "noncanonical_base64url");

        let vector: serde_json::Value = serde_json::from_slice(FIXTURE).expect("vector JSON");
        let record = ReleaseKeyRecord {
            key_id: "release-a".to_owned(),
            public_key: vector["public_key"].as_str().expect("key").to_owned(),
        };
        let error = ReleaseKeyRegistry::from_records([record.clone(), record])
            .expect_err("duplicates are forbidden");
        assert_eq!(error.code, "duplicate_release_key");

        let public_key = vector["public_key"].as_str().expect("key").to_owned();
        let error = ReleaseKeyRegistry::from_records([
            ReleaseKeyRecord {
                key_id: "release-a".to_owned(),
                public_key: public_key.clone(),
            },
            ReleaseKeyRecord {
                key_id: "release-b".to_owned(),
                public_key,
            },
        ])
        .expect_err("key aliases are forbidden");
        assert_eq!(error.code, "duplicate_release_key_material");
    }

    #[test]
    fn deterministic_receipts_repeat_byte_for_byte() {
        let left = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &rollback(),
            2_000_000_000_000,
        );
        let right = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &rollback(),
            2_000_000_000_000,
        );
        assert_eq!(
            serde_jcs::to_vec(&left.receipt).expect("left receipt"),
            serde_jcs::to_vec(&right.receipt).expect("right receipt")
        );
    }

    #[test]
    fn every_exact_policy_dimension_fails_closed() {
        let base: serde_json::Value = serde_json::from_slice(&envelope_bytes()).expect("envelope");

        let mut package = base.clone();
        package["signed"]["artifact"]["package_name"] =
            serde_json::json!("io.github.example.other");
        assert_rejected(&resign(package), "package_mismatch");

        let mut ring = base.clone();
        ring["signed"]["rollout_ring"] = serde_json::json!("beta");
        assert_rejected(&resign(ring), "rollout_ring_mismatch");

        let mut version = base.clone();
        version["signed"]["artifact"]["version_code"] = serde_json::json!(100);
        assert_rejected(&resign(version), "version_policy_rejected");

        let mut signer = base.clone();
        signer["signed"]["artifact"]["signer_sha256"] =
            serde_json::json!(format!("sha256:{}", "22".repeat(32)));
        assert_rejected(&resign(signer), "signer_mismatch");

        let mut hash = base.clone();
        hash["signed"]["artifact"]["apk_sha256"] =
            serde_json::json!(format!("sha256:{}", "AA".repeat(32)));
        assert_rejected(&resign(hash), "invalid_sha256");

        let mut unsafe_integer = base.clone();
        unsafe_integer["signed"]["sequence"] =
            serde_json::json!(MAX_JCS_SAFE_INTEGER.saturating_add(1));
        assert_rejected(&resign(unsafe_integer), "integer_out_of_jcs_range");

        let mut size = base.clone();
        size["signed"]["artifact"]["apk_size_bytes"] = serde_json::json!(104_857_601_u64);
        assert_rejected(&resign(size), "apk_size_policy_rejected");

        let mut long_validity = base.clone();
        long_validity["signed"]["expires_at_ms"] = serde_json::json!(2_000_086_400_001_u64);
        assert_rejected(&resign(long_validity), "invalid_validity_window");

        let mut future = base;
        future["signed"]["issued_at_ms"] = serde_json::json!(2_000_000_300_001_u64);
        future["signed"]["expires_at_ms"] = serde_json::json!(2_000_000_900_001_u64);
        assert_rejected(&resign(future), "manifest_from_future");
    }

    #[test]
    fn algorithm_and_known_key_registry_are_closed() {
        let mut algorithm: serde_json::Value =
            serde_json::from_slice(&envelope_bytes()).expect("envelope");
        algorithm["algorithm"] = serde_json::json!("Ed25519ph");
        assert_rejected(
            &serde_json::to_vec(&algorithm).expect("algorithm envelope"),
            "unsupported_algorithm",
        );

        let mut unknown_key: serde_json::Value =
            serde_json::from_slice(&envelope_bytes()).expect("envelope");
        unknown_key["key_id"] = serde_json::json!("release-not-registered");
        assert_rejected(
            &serde_json::to_vec(&unknown_key).expect("unknown-key envelope"),
            "unknown_release_key",
        );
    }

    #[test]
    fn unsafe_observation_times_fail_closed_without_invalid_receipts() {
        let admission = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &rollback(),
            MAX_JCS_SAFE_INTEGER + 1,
        );
        assert_eq!(admission.receipt.decision, PackageUpdateDecision::Rejected);
        assert_eq!(admission.receipt.code, "observation_time_out_of_range");
        assert_eq!(admission.receipt.observed_at_ms, 0);
        assert!(admission.verified.is_none());

        let admitted = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &rollback(),
            2_000_000_000_000,
        );
        let verified = admitted.verified.expect("verified");
        let observed = ObservedApk {
            package_name: verified.manifest.artifact.package_name.clone(),
            version_code: verified.manifest.artifact.version_code,
            signer_sha256: verified.manifest.artifact.signer_sha256.clone(),
            apk_sha256: verified.manifest.artifact.apk_sha256.clone(),
            apk_size_bytes: verified.manifest.artifact.apk_size_bytes,
        };
        let mut state = rollback();
        let before = state.clone();
        let receipt =
            commit_installed_apk(&verified, &observed, &mut state, MAX_JCS_SAFE_INTEGER + 1);
        assert_eq!(receipt.decision, PackageUpdateDecision::Rejected);
        assert_eq!(receipt.code, "observation_time_out_of_range");
        assert_eq!(receipt.observed_at_ms, 0);
        assert_eq!(state, before);
    }

    #[test]
    fn url_requires_path_before_query_and_package_names_are_lowercase() {
        let base: serde_json::Value = serde_json::from_slice(&envelope_bytes()).expect("envelope");

        let mut query_before_path = base.clone();
        query_before_path["signed"]["artifact"]["apk_url"] =
            serde_json::json!("https://updates.mesmerprism.com?next=/rusty-kiosk.apk");
        assert_rejected(&resign(query_before_path), "invalid_apk_url");

        let mut uppercase_package = base;
        uppercase_package["signed"]["artifact"]["package_name"] =
            serde_json::json!("io.github.mesmerprism.RustyKiosk");
        assert_rejected(&resign(uppercase_package), "invalid_package_name");
    }

    #[test]
    fn expiry_is_exclusive_at_admission_and_install_commit() {
        let mut at_expiry: serde_json::Value =
            serde_json::from_slice(&envelope_bytes()).expect("envelope");
        at_expiry["signed"]["expires_at_ms"] = serde_json::json!(2_000_000_000_000_u64);
        assert_rejected(&resign(at_expiry), "manifest_expired");

        let mut state = rollback();
        let admission = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &state,
            2_000_000_000_000,
        );
        let verified = admission.verified.expect("verified");
        let observed = ObservedApk {
            package_name: verified.manifest.artifact.package_name.clone(),
            version_code: verified.manifest.artifact.version_code,
            signer_sha256: verified.manifest.artifact.signer_sha256.clone(),
            apk_sha256: verified.manifest.artifact.apk_sha256.clone(),
            apk_size_bytes: verified.manifest.artifact.apk_size_bytes,
        };
        let before = state.clone();
        let receipt = commit_installed_apk(&verified, &observed, &mut state, 2_000_000_500_000);
        assert_eq!(receipt.decision, PackageUpdateDecision::Rejected);
        assert_eq!(receipt.code, "manifest_expired");
        assert_eq!(state, before);
    }

    #[test]
    fn published_schemas_remain_valid_json() {
        let envelope_schema: serde_json::Value = serde_json::from_str(include_str!(
            "../../../schemas/rusty.quest.package_update_manifest_envelope.v1.schema.json"
        ))
        .expect("envelope schema JSON");
        let receipt_schema: serde_json::Value = serde_json::from_str(include_str!(
            "../../../schemas/rusty.quest.package_update_receipt.v1.schema.json"
        ))
        .expect("receipt schema JSON");
        assert_eq!(
            envelope_schema["$id"],
            "https://github.com/MesmerPrism/rusty-quest/schemas/rusty.quest.package_update_manifest_envelope.v1.schema.json"
        );
        assert_eq!(
            receipt_schema["$id"],
            "https://github.com/MesmerPrism/rusty-quest/schemas/rusty.quest.package_update_receipt.v1.schema.json"
        );
    }

    #[test]
    fn install_commit_rechecks_rollback_state_for_races() {
        let mut state = rollback();
        let admission = verify_manifest(
            &envelope_bytes(),
            &registry(),
            &policy(),
            &state,
            2_000_000_000_000,
        );
        let verified = admission.verified.expect("verified");
        state.apply(PackageUpdateCheckpoint {
            channel: verified.manifest.channel.clone(),
            package_name: verified.manifest.artifact.package_name.clone(),
            rollout_ring: verified.manifest.rollout_ring.clone(),
            signer_sha256: verified.manifest.artifact.signer_sha256.clone(),
            key_id: verified.key_id.clone(),
            public_key: verified.public_key.clone(),
            https_origin: verified.https_origin.clone(),
            sequence: verified.manifest.sequence,
            version_code: verified.manifest.artifact.version_code,
            signed_manifest_sha256: verified.signed_manifest_sha256.clone(),
        });
        let observed = ObservedApk {
            package_name: verified.manifest.artifact.package_name.clone(),
            version_code: verified.manifest.artifact.version_code,
            signer_sha256: verified.manifest.artifact.signer_sha256.clone(),
            apk_sha256: verified.manifest.artifact.apk_sha256.clone(),
            apk_size_bytes: verified.manifest.artifact.apk_size_bytes,
        };
        let before = state.clone();
        let receipt = commit_installed_apk(&verified, &observed, &mut state, 2_000_000_000_100);
        assert_eq!(receipt.decision, PackageUpdateDecision::Rejected);
        assert_eq!(receipt.code, "sequence_rollback");
        assert_eq!(state, before);
    }
}
