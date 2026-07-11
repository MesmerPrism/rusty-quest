//! Quest process/JNI projection over the shared Manifold broker adapter path.

use rusty_manifold_broker_adapter::{
    ManifoldBrokerAdapter, ManifoldBrokerAdapterConfig, ManifoldBrokerAdapterError,
    ManifoldBrokerAdapterMode, ManifoldBrokerAdapterReceipt, RUNTIME_HOST_AUTHORITY_OWNER,
};
use rusty_manifold_broker_product::ManifoldBrokerProductLock;
use rusty_manifold_model::DottedId;
use rusty_manifold_runtime_host::{
    ManifoldRuntimeCommandRequest, ManifoldRuntimeHostSnapshot, ManifoldRuntimeLease,
};
use serde::{Deserialize, Serialize};
use std::fmt;

/// Trusted app-local bridge invocation schema.
pub const QUEST_BROKER_AUTHORITY_INVOCATION_SCHEMA: &str =
    "rusty.quest.broker.authority_invocation.v1";
/// Trusted app-local bridge response schema.
pub const QUEST_BROKER_AUTHORITY_RESPONSE_SCHEMA: &str = "rusty.quest.broker.authority_response.v1";

/// Android process boundary used to call the shared Rust authority path.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum QuestBrokerAuthorityBridgeKind {
    /// Dedicated broker app/process JNI boundary.
    StandaloneProcessJni,
    /// Same-app embedded JNI boundary.
    EmbeddedInProcessJni,
}

/// One trusted local invocation. Config and snapshots are product-owned inputs,
/// not client-supplied admission claims.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAuthorityInvocation {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Android bridge placement.
    pub bridge_kind: QuestBrokerAuthorityBridgeKind,
    /// Exact Manifold adapter binding.
    pub adapter_config: ManifoldBrokerAdapterConfig,
    /// Exact accepted Manifold product lock.
    pub product_lock: ManifoldBrokerProductLock,
    /// Durable accepted host state, absent only for first initialization.
    pub prior_snapshot: Option<ManifoldRuntimeHostSnapshot>,
    /// Initial accepted leases, used only when no prior snapshot exists.
    pub initial_leases: Vec<ManifoldRuntimeLease>,
    /// Typed command request.
    pub request: ManifoldRuntimeCommandRequest,
    /// Review clock value supplied by the trusted platform clock adapter.
    pub now_ms: u64,
}

/// Android-facing response that preserves the exact Manifold receipt and state.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAuthorityResponse {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Android bridge placement.
    pub bridge_kind: QuestBrokerAuthorityBridgeKind,
    /// Explicit proof that Java/JNI owns no acceptance rules.
    pub local_acceptance_rules: bool,
    /// Sole decision owner.
    pub decision_owner_id: DottedId,
    /// Exact shared adapter/host receipt.
    pub adapter_receipt: ManifoldBrokerAdapterReceipt,
    /// Durable next accepted host snapshot.
    pub next_snapshot: ManifoldRuntimeHostSnapshot,
}

/// Evaluates one trusted local bridge invocation through Manifold.
pub fn evaluate_authority_invocation(
    invocation: &QuestBrokerAuthorityInvocation,
) -> Result<QuestBrokerAuthorityResponse, QuestBrokerAuthorityError> {
    if invocation.schema_id != QUEST_BROKER_AUTHORITY_INVOCATION_SCHEMA {
        return Err(QuestBrokerAuthorityError::SchemaMismatch);
    }
    validate_bridge_mode(&invocation.bridge_kind, &invocation.adapter_config.mode)?;
    let mut adapter = if let Some(snapshot) = &invocation.prior_snapshot {
        let json = serde_json::to_string(snapshot).map_err(QuestBrokerAuthorityError::Serialize)?;
        ManifoldBrokerAdapter::restart_from_json(
            invocation.adapter_config.clone(),
            invocation.product_lock.clone(),
            &json,
        )
        .map_err(QuestBrokerAuthorityError::Adapter)?
    } else {
        ManifoldBrokerAdapter::new(
            invocation.adapter_config.clone(),
            invocation.product_lock.clone(),
            invocation.initial_leases.clone(),
        )
        .map_err(QuestBrokerAuthorityError::Adapter)?
    };
    let receipt = adapter.handle_command(&invocation.request, invocation.now_ms);
    Ok(QuestBrokerAuthorityResponse {
        schema_id: QUEST_BROKER_AUTHORITY_RESPONSE_SCHEMA.to_owned(),
        bridge_kind: invocation.bridge_kind.clone(),
        local_acceptance_rules: false,
        decision_owner_id: DottedId::new(RUNTIME_HOST_AUTHORITY_OWNER)
            .expect("static decision owner is valid"),
        adapter_receipt: receipt,
        next_snapshot: adapter.host_snapshot().clone(),
    })
}

/// JSON boundary used by JNI/process adapters.
pub fn evaluate_authority_json(json: &str) -> Result<String, QuestBrokerAuthorityError> {
    let invocation = serde_json::from_str(json).map_err(QuestBrokerAuthorityError::Deserialize)?;
    let response = evaluate_authority_invocation(&invocation)?;
    serde_json::to_string(&response).map_err(QuestBrokerAuthorityError::Serialize)
}

fn validate_bridge_mode(
    kind: &QuestBrokerAuthorityBridgeKind,
    mode: &ManifoldBrokerAdapterMode,
) -> Result<(), QuestBrokerAuthorityError> {
    let matches = matches!(
        (kind, mode),
        (
            QuestBrokerAuthorityBridgeKind::StandaloneProcessJni,
            ManifoldBrokerAdapterMode::Standalone
        ) | (
            QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
            ManifoldBrokerAdapterMode::Embedded
        )
    );
    if matches {
        Ok(())
    } else {
        Err(QuestBrokerAuthorityError::BridgeModeMismatch)
    }
}

/// Bridge decoding, binding, or authority evaluation failure.
#[derive(Debug)]
pub enum QuestBrokerAuthorityError {
    /// Invocation schema mismatch.
    SchemaMismatch,
    /// Android bridge kind differs from the product-lock mode.
    BridgeModeMismatch,
    /// JSON invocation decode failed.
    Deserialize(serde_json::Error),
    /// JSON response/snapshot encode failed.
    Serialize(serde_json::Error),
    /// Manifold adapter binding failed.
    Adapter(ManifoldBrokerAdapterError),
}

impl fmt::Display for QuestBrokerAuthorityError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::SchemaMismatch => write!(formatter, "Quest broker authority schema mismatch"),
            Self::BridgeModeMismatch => write!(formatter, "Quest broker bridge mode mismatch"),
            Self::Deserialize(error) => {
                write!(formatter, "authority invocation decode failed: {error}")
            }
            Self::Serialize(error) => {
                write!(formatter, "authority response encode failed: {error}")
            }
            Self::Adapter(error) => write!(formatter, "Manifold broker adapter failed: {error}"),
        }
    }
}

impl std::error::Error for QuestBrokerAuthorityError {}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_manifold_runtime_host::ManifoldRuntimeRejectionReason;

    fn fixture<T: serde::de::DeserializeOwned>(name: &str) -> T {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .join("fixtures/broker-authority")
            .join(name);
        serde_json::from_str(&std::fs::read_to_string(path).expect("fixture must load"))
            .expect("fixture must deserialize")
    }

    #[test]
    fn committed_standalone_and_embedded_responses_preserve_host_parity() {
        for suffix in ["applied", "unknown-rejected", "unleased-rejected"] {
            let standalone: QuestBrokerAuthorityResponse =
                fixture(&format!("standalone-{suffix}.response.json"));
            let embedded: QuestBrokerAuthorityResponse =
                fixture(&format!("embedded-{suffix}.response.json"));
            assert_eq!(
                standalone.adapter_receipt.dispatch, embedded.adapter_receipt.dispatch,
                "{suffix}"
            );
            assert_eq!(
                standalone.adapter_receipt.application, embedded.adapter_receipt.application,
                "{suffix}"
            );
            assert!(!standalone.local_acceptance_rules);
            assert!(!embedded.local_acceptance_rules);
            assert_eq!(
                standalone.decision_owner_id.as_str(),
                RUNTIME_HOST_AUTHORITY_OWNER
            );
        }
    }

    #[test]
    fn bridge_re_evaluates_committed_invocations_without_local_rules() {
        for name in [
            "standalone-applied.invocation.json",
            "embedded-applied.invocation.json",
            "standalone-unknown-rejected.invocation.json",
            "embedded-unleased-rejected.invocation.json",
        ] {
            let invocation: QuestBrokerAuthorityInvocation = fixture(name);
            let response = evaluate_authority_invocation(&invocation).expect("evaluation");
            assert!(!response.local_acceptance_rules, "{name}");
            assert_eq!(
                response.decision_owner_id.as_str(),
                RUNTIME_HOST_AUTHORITY_OWNER
            );
        }
    }

    #[test]
    fn bridge_mode_substitution_fails_before_host_mutation() {
        let mut invocation: QuestBrokerAuthorityInvocation =
            fixture("standalone-applied.invocation.json");
        invocation.bridge_kind = QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni;
        assert!(matches!(
            evaluate_authority_invocation(&invocation),
            Err(QuestBrokerAuthorityError::BridgeModeMismatch)
        ));
    }

    #[test]
    fn rejection_responses_keep_host_revision_unchanged() {
        let unknown: QuestBrokerAuthorityResponse =
            fixture("standalone-unknown-rejected.response.json");
        assert_eq!(
            unknown.adapter_receipt.application.rejection_reason,
            Some(ManifoldRuntimeRejectionReason::UnknownCommand)
        );
        assert_eq!(unknown.next_snapshot.authority_revision.get(), 1);
        let unleased: QuestBrokerAuthorityResponse =
            fixture("embedded-unleased-rejected.response.json");
        assert_eq!(
            unleased.adapter_receipt.application.rejection_reason,
            Some(ManifoldRuntimeRejectionReason::MissingLease)
        );
        assert_eq!(unleased.next_snapshot.authority_revision.get(), 1);
    }
}
