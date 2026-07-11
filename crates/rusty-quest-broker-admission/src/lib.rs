//! Android Binder caller projection over Manifold admission authority.

use rusty_manifold_admission::{
    ManifoldAdmissionAuthority, ManifoldAdmissionError, ManifoldAdmissionReceipt,
    ManifoldAdmissionRequest, ManifoldAdmissionRevocationRequest, ManifoldAdmissionSnapshot,
    ManifoldAdmissionUseRequest, ManifoldClientIdentity, ADMISSION_REQUEST_SCHEMA,
    ADMISSION_REVOCATION_REQUEST_SCHEMA, ADMISSION_USE_REQUEST_SCHEMA,
};
use rusty_manifold_model::{DottedId, Revision, SchemaId};
use serde::{Deserialize, Serialize};
use std::fmt;

/// Runtime initialization schema.
pub const QUEST_ADMISSION_CONFIG_SCHEMA: &str = "rusty.quest.broker.admission_config.v1";
/// Trusted Binder operation schema.
pub const QUEST_ADMISSION_OPERATION_SCHEMA: &str = "rusty.quest.broker.admission_operation.v1";
/// Quest projection response schema.
pub const QUEST_ADMISSION_RESPONSE_SCHEMA: &str = "rusty.quest.broker.admission_response.v1";
/// Selected platform adapter label.
pub const SIGNATURE_SCOPED_BINDER_ADAPTER: &str = "android_signature_scoped_binder";
/// Sole decision owner label.
pub const MANIFOLD_ADMISSION_OWNER: &str = "rusty.manifold.admission";

/// Product-owned initialization state.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAdmissionConfig {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Durable Manifold admission state.
    pub snapshot: ManifoldAdmissionSnapshot,
}

/// Identity evidence derived from the immediate Android Binder caller.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestAndroidBinderCaller {
    /// Binder sending UID.
    pub sending_uid: u32,
    /// Package resolved from that UID.
    pub package_name: String,
    /// SHA-256 of the PackageManager-reported signing certificate.
    pub signing_certificate_sha256: String,
}

/// Trusted JNI operation. Java supplies caller evidence and transport fields;
/// Manifold decides identity, grants, token state, and rejection.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(tag = "operation", rename_all = "snake_case", deny_unknown_fields)]
pub enum QuestBrokerAdmissionOperation {
    /// Issue a token.
    IssueToken {
        /// Schema identifier.
        #[serde(rename = "$schema")]
        schema_id: String,
        /// Binder caller evidence.
        caller: QuestAndroidBinderCaller,
        /// Request id.
        request_id: DottedId,
        /// Expected revision.
        expected_authority_revision: Revision,
        /// Requested capabilities.
        requested_capabilities: Vec<DottedId>,
        /// Requested token lifetime.
        requested_token_ttl_ms: u64,
        /// Request issue time.
        issued_at_ms: u64,
        /// Request expiry.
        expires_at_ms: u64,
        /// SecureRandom-provided 256-bit lowercase hex.
        entropy_hex: String,
    },
    /// Authorize one token use.
    AuthorizeUse {
        /// Schema identifier.
        #[serde(rename = "$schema")]
        schema_id: String,
        /// Binder caller evidence.
        caller: QuestAndroidBinderCaller,
        /// Request id.
        request_id: DottedId,
        /// Expected revision.
        expected_authority_revision: Revision,
        /// Token id.
        token_id: DottedId,
        /// Required capability.
        capability_id: DottedId,
        /// Request issue time.
        issued_at_ms: u64,
        /// Request expiry.
        expires_at_ms: u64,
    },
    /// Revoke one token.
    RevokeToken {
        /// Schema identifier.
        #[serde(rename = "$schema")]
        schema_id: String,
        /// Binder caller evidence.
        caller: QuestAndroidBinderCaller,
        /// Request id.
        request_id: DottedId,
        /// Expected revision.
        expected_authority_revision: Revision,
        /// Token id.
        token_id: DottedId,
        /// Stable reason.
        reason: DottedId,
    },
    /// Explicit token expiry cleanup.
    ExpireTokens {
        /// Schema identifier.
        #[serde(rename = "$schema")]
        schema_id: String,
        /// Sweep id.
        sweep_id: DottedId,
        /// Expected revision.
        expected_authority_revision: Revision,
        /// Trusted clock value.
        now_ms: u64,
    },
}

/// Quest adapter response preserving the exact Manifold receipt.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAdmissionResponse {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Platform adapter, not decision authority.
    pub platform_adapter: String,
    /// Sole decision owner.
    pub decision_owner: String,
    /// Explicit policy ownership proof.
    pub local_token_or_grant_policy: bool,
    /// Exact Manifold receipt.
    pub receipt: ManifoldAdmissionReceipt,
}

/// Stateful trusted runtime retained inside the broker process.
pub struct QuestBrokerAdmissionRuntime {
    authority: ManifoldAdmissionAuthority,
}

impl QuestBrokerAdmissionRuntime {
    /// Initializes from a product-generated config.
    pub fn from_config(config: QuestBrokerAdmissionConfig) -> Result<Self, QuestAdmissionError> {
        if config.schema_id != QUEST_ADMISSION_CONFIG_SCHEMA {
            return Err(QuestAdmissionError::SchemaMismatch);
        }
        Ok(Self {
            authority: ManifoldAdmissionAuthority::from_snapshot(config.snapshot)
                .map_err(QuestAdmissionError::Authority)?,
        })
    }

    /// Initializes from JSON.
    pub fn from_config_json(json: &str) -> Result<Self, QuestAdmissionError> {
        let config = serde_json::from_str(json).map_err(QuestAdmissionError::Deserialize)?;
        Self::from_config(config)
    }

    /// Executes a trusted JNI operation.
    pub fn execute(
        &mut self,
        operation: QuestBrokerAdmissionOperation,
    ) -> Result<QuestBrokerAdmissionResponse, QuestAdmissionError> {
        let receipt = match operation {
            QuestBrokerAdmissionOperation::IssueToken {
                schema_id,
                caller,
                request_id,
                expected_authority_revision,
                requested_capabilities,
                requested_token_ttl_ms,
                issued_at_ms,
                expires_at_ms,
                entropy_hex,
            } => {
                check_operation_schema(&schema_id)?;
                let now_ms = issued_at_ms;
                let identity = self.project_identity(&caller);
                let request = ManifoldAdmissionRequest {
                    schema_id: schema(ADMISSION_REQUEST_SCHEMA),
                    request_id,
                    expected_authority_revision,
                    identity,
                    requested_capabilities,
                    issued_at_ms,
                    expires_at_ms,
                    requested_token_ttl_ms,
                };
                self.authority
                    .issue_token(&request, parse_entropy(&entropy_hex)?, now_ms)
            }
            QuestBrokerAdmissionOperation::AuthorizeUse {
                schema_id,
                caller,
                request_id,
                expected_authority_revision,
                token_id,
                capability_id,
                issued_at_ms,
                expires_at_ms,
            } => {
                check_operation_schema(&schema_id)?;
                let request = ManifoldAdmissionUseRequest {
                    schema_id: schema(ADMISSION_USE_REQUEST_SCHEMA),
                    request_id,
                    expected_authority_revision,
                    token_id,
                    identity: self.project_identity(&caller),
                    capability_id,
                    issued_at_ms,
                    expires_at_ms,
                };
                self.authority.authorize_use(&request, issued_at_ms)
            }
            QuestBrokerAdmissionOperation::RevokeToken {
                schema_id,
                caller,
                request_id,
                expected_authority_revision,
                token_id,
                reason,
            } => {
                check_operation_schema(&schema_id)?;
                self.authority
                    .revoke_token(&ManifoldAdmissionRevocationRequest {
                        schema_id: schema(ADMISSION_REVOCATION_REQUEST_SCHEMA),
                        request_id,
                        expected_authority_revision,
                        token_id,
                        identity: self.project_identity(&caller),
                        reason,
                    })
            }
            QuestBrokerAdmissionOperation::ExpireTokens {
                schema_id,
                sweep_id,
                expected_authority_revision,
                now_ms,
            } => {
                check_operation_schema(&schema_id)?;
                self.authority
                    .expire_tokens(sweep_id, expected_authority_revision, now_ms)
            }
        };
        Ok(QuestBrokerAdmissionResponse {
            schema_id: QUEST_ADMISSION_RESPONSE_SCHEMA.to_owned(),
            platform_adapter: SIGNATURE_SCOPED_BINDER_ADAPTER.to_owned(),
            decision_owner: MANIFOLD_ADMISSION_OWNER.to_owned(),
            local_token_or_grant_policy: false,
            receipt,
        })
    }

    /// Executes JSON and returns JSON for JNI.
    pub fn execute_json(&mut self, json: &str) -> Result<String, QuestAdmissionError> {
        let operation = serde_json::from_str(json).map_err(QuestAdmissionError::Deserialize)?;
        let response = self.execute(operation)?;
        serde_json::to_string(&response).map_err(QuestAdmissionError::Serialize)
    }

    /// Returns accepted state JSON for restart/evidence.
    pub fn snapshot_json(&self) -> Result<String, QuestAdmissionError> {
        self.authority
            .snapshot_json()
            .map_err(QuestAdmissionError::Authority)
    }

    fn project_identity(&self, caller: &QuestAndroidBinderCaller) -> ManifoldClientIdentity {
        let fingerprint = normalize_fingerprint(&caller.signing_certificate_sha256);
        if let Some(grant) = self
            .authority
            .snapshot()
            .grants
            .iter()
            .find(|grant| grant.identity.platform_subject == caller.package_name)
        {
            ManifoldClientIdentity {
                client_id: grant.identity.client_id.clone(),
                platform_subject: caller.package_name.clone(),
                signing_fingerprint: fingerprint,
            }
        } else {
            ManifoldClientIdentity {
                client_id: DottedId::new(format!("client.android.uid_{}", caller.sending_uid))
                    .expect("derived client id"),
                platform_subject: caller.package_name.clone(),
                signing_fingerprint: fingerprint,
            }
        }
    }
}

fn check_operation_schema(value: &str) -> Result<(), QuestAdmissionError> {
    if value == QUEST_ADMISSION_OPERATION_SCHEMA {
        Ok(())
    } else {
        Err(QuestAdmissionError::SchemaMismatch)
    }
}

fn normalize_fingerprint(value: &str) -> String {
    let normalized = value.trim().to_ascii_lowercase().replace([':', ' '], "");
    if let Some(hex) = normalized.strip_prefix("sha256") {
        format!("sha256:{hex}")
    } else {
        format!("sha256:{normalized}")
    }
}

fn parse_entropy(value: &str) -> Result<[u8; 32], QuestAdmissionError> {
    let value = value.trim();
    if value.len() != 64 || !value.as_bytes().iter().all(u8::is_ascii_hexdigit) {
        return Err(QuestAdmissionError::InvalidEntropy);
    }
    let mut output = [0_u8; 32];
    for (index, byte) in output.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)
            .map_err(|_| QuestAdmissionError::InvalidEntropy)?;
    }
    Ok(output)
}

fn schema(value: &str) -> SchemaId {
    SchemaId::new(value).expect("static schema")
}

/// Quest admission bridge failure. Manifold rejections remain receipts.
#[derive(Debug)]
pub enum QuestAdmissionError {
    /// Config or operation schema mismatch.
    SchemaMismatch,
    /// SecureRandom entropy was not exactly 256 bits of hex.
    InvalidEntropy,
    /// JSON input decode failed.
    Deserialize(serde_json::Error),
    /// JSON output encode failed.
    Serialize(serde_json::Error),
    /// Manifold admission state failed validation.
    Authority(ManifoldAdmissionError),
}

impl fmt::Display for QuestAdmissionError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::SchemaMismatch => write!(formatter, "Quest admission schema mismatch"),
            Self::InvalidEntropy => {
                write!(formatter, "Quest admission entropy must be 256-bit hex")
            }
            Self::Deserialize(error) => {
                write!(formatter, "Quest admission JSON decode failed: {error}")
            }
            Self::Serialize(error) => {
                write!(formatter, "Quest admission JSON encode failed: {error}")
            }
            Self::Authority(error) => write!(formatter, "Manifold admission failed: {error}"),
        }
    }
}

impl std::error::Error for QuestAdmissionError {}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_manifold_admission::ManifoldAdmissionRejectionReason;

    fn config() -> QuestBrokerAdmissionConfig {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../rusty-manifold/fixtures/admission/initial-snapshot.json");
        QuestBrokerAdmissionConfig {
            schema_id: QUEST_ADMISSION_CONFIG_SCHEMA.to_owned(),
            snapshot: serde_json::from_str(&std::fs::read_to_string(path).expect("fixture"))
                .expect("snapshot"),
        }
    }

    fn caller(fingerprint: &str) -> QuestAndroidBinderCaller {
        QuestAndroidBinderCaller {
            sending_uid: 10_123,
            package_name: "io.github.mesmerprism.rustymanifold.admission.client".to_owned(),
            signing_certificate_sha256: fingerprint.to_owned(),
        }
    }

    #[test]
    fn binder_projection_issues_token_without_local_policy() {
        let mut runtime = QuestBrokerAdmissionRuntime::from_config(config()).expect("runtime");
        let response = runtime
            .execute(QuestBrokerAdmissionOperation::IssueToken {
                schema_id: QUEST_ADMISSION_OPERATION_SCHEMA.to_owned(),
                caller: caller(&"a1".repeat(32)),
                request_id: DottedId::new("request.quest.issue").expect("id"),
                expected_authority_revision: Revision::new(1).expect("revision"),
                requested_capabilities: vec![
                    DottedId::new("capability.command.session.list").expect("id")
                ],
                requested_token_ttl_ms: 20_000,
                issued_at_ms: 2_000,
                expires_at_ms: 5_000,
                entropy_hex: "07".repeat(32),
            })
            .expect("response");
        assert!(response.receipt.applied);
        assert!(!response.local_token_or_grant_policy);
        assert_eq!(response.decision_owner, MANIFOLD_ADMISSION_OWNER);
    }

    #[test]
    fn package_signing_substitution_reaches_manifold_as_identity_mismatch() {
        let mut runtime = QuestBrokerAdmissionRuntime::from_config(config()).expect("runtime");
        let response = runtime
            .execute(QuestBrokerAdmissionOperation::IssueToken {
                schema_id: QUEST_ADMISSION_OPERATION_SCHEMA.to_owned(),
                caller: caller(&"b2".repeat(32)),
                request_id: DottedId::new("request.quest.wrong-signature").expect("id"),
                expected_authority_revision: Revision::new(1).expect("revision"),
                requested_capabilities: vec![
                    DottedId::new("capability.command.session.list").expect("id")
                ],
                requested_token_ttl_ms: 20_000,
                issued_at_ms: 2_000,
                expires_at_ms: 5_000,
                entropy_hex: "08".repeat(32),
            })
            .expect("response");
        assert_eq!(
            response.receipt.rejection_reason,
            Some(ManifoldAdmissionRejectionReason::IdentityMismatch)
        );
    }

    #[test]
    fn invalid_entropy_and_bridge_schema_fail_before_authority_mutation() {
        let mut runtime = QuestBrokerAdmissionRuntime::from_config(config()).expect("runtime");
        let result = runtime.execute(QuestBrokerAdmissionOperation::IssueToken {
            schema_id: QUEST_ADMISSION_OPERATION_SCHEMA.to_owned(),
            caller: caller(&"a1".repeat(32)),
            request_id: DottedId::new("request.quest.bad-entropy").expect("id"),
            expected_authority_revision: Revision::new(1).expect("revision"),
            requested_capabilities: vec![
                DottedId::new("capability.command.session.list").expect("id")
            ],
            requested_token_ttl_ms: 20_000,
            issued_at_ms: 2_000,
            expires_at_ms: 5_000,
            entropy_hex: "bad".to_owned(),
        });
        assert!(matches!(result, Err(QuestAdmissionError::InvalidEntropy)));
        let snapshot: ManifoldAdmissionSnapshot =
            serde_json::from_str(&runtime.snapshot_json().expect("snapshot")).expect("snapshot");
        assert_eq!(snapshot.authority_revision.get(), 1);
    }
}
