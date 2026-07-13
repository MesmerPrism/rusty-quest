//! Stateful standalone/embedded server entrypoint over one Manifold broker runtime.

use crate::QuestBrokerAuthorityBridgeKind;
use rusty_manifold_admission::{
    ManifoldAdmissionRequest, ManifoldAdmissionRevocationRequest, ManifoldAdmissionUseRequest,
    ADMISSION_REQUEST_SCHEMA, ADMISSION_REVOCATION_REQUEST_SCHEMA, ADMISSION_USE_REQUEST_SCHEMA,
};
use rusty_manifold_broker_adapter::{
    command_capability, ManifoldBrokerAdapter, ManifoldBrokerAdapterConfig,
    ManifoldBrokerAdapterError, ManifoldBrokerAdapterMode, ManifoldBrokerMutationReceipt,
    ManifoldBrokerMutationRequest, ManifoldBrokerRuntime, BROKER_MUTATION_REQUEST_SCHEMA,
    RUNTIME_HOST_AUTHORITY_OWNER,
};
use rusty_manifold_broker_product::{
    validate_broker_product_lock, ManifoldBrokerFeature, ManifoldBrokerProductLock,
    ManifoldBrokerProductSpec,
};
use rusty_manifold_media_session::{
    media_session_acceptance_params_digest, media_session_termination_params_digest,
    ManifoldMediaSessionAcceptanceRequest, ManifoldMediaSessionClientGrant,
    ManifoldMediaSessionProductBinding, ManifoldMediaSessionTerminationAction,
    ManifoldMediaSessionTerminationRequest, MANIFOLD_MEDIA_SESSION_ACCEPTANCE_REQUEST_SCHEMA,
    MANIFOLD_MEDIA_SESSION_ACCEPT_COMMAND, MANIFOLD_MEDIA_SESSION_STOP_COMMAND,
    MANIFOLD_MEDIA_SESSION_TERMINATION_REQUEST_SCHEMA,
};
use rusty_manifold_model::{DottedId, Revision, SchemaId};
use rusty_manifold_peer_runtime_host::{
    ManifoldPeerRuntimeAuthorityFamily, ManifoldPeerRuntimeBrokerLeaseAttemptOutcome,
    ManifoldPeerRuntimeHost, ManifoldPeerRuntimeHostError, ManifoldPeerRuntimeTrustPolicy,
    PEER_RUNTIME_HOST_TRUST_POLICY_SCHEMA,
};
use rusty_manifold_runtime_host::{
    ManifoldRuntimeCommandDescriptor, ManifoldRuntimeCommandRequest, ManifoldRuntimeHostSnapshot,
    ManifoldRuntimeLease, ManifoldRuntimeTypedParamsDigest, HOST_COMMAND_REQUEST_SCHEMA,
    HOST_SNAPSHOT_SCHEMA, HOST_TYPED_PARAMS_DIGEST_SCHEMA, MAX_TYPED_PARAMS_CANONICAL_BYTES,
};
use rusty_quest_broker_admission::{
    parse_entropy_hex, project_binder_caller, QuestBrokerAdmissionConfig,
    QuestBrokerAdmissionOperation, QuestBrokerAdmissionResponse, MANIFOLD_ADMISSION_OWNER,
    QUEST_ADMISSION_CONFIG_SCHEMA, QUEST_ADMISSION_OPERATION_SCHEMA,
    QUEST_ADMISSION_RESPONSE_SCHEMA, SIGNATURE_SCOPED_BINDER_ADAPTER,
};
use rusty_quest_broker_contracts::{
    validate_media_lifecycle_package, BrokerMediaLifecycleLock, BrokerMediaLifecyclePackageBinding,
    BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA,
};
use rusty_quest_media_stream::{
    MediaStreamClientAuthorityBinding, MediaStreamOwnerAction, MediaStreamOwnerActionKind,
    MediaStreamOwnerCompletionReceipt, MediaStreamOwnerKind, MediaStreamOwnerProviderReadback,
    MediaStreamPlatformAction, MediaStreamPlatformApplicationReceipt, MediaStreamPlatformOperation,
    MediaStreamRuntimeProductBinding, MediaStreamRuntimeState, MediaStreamSessionProductRuntime,
    MediaStreamTrustedOwnerProvider,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fmt,
    sync::{Arc, RwLock},
};

/// Product-owned live runtime configuration schema.
pub const QUEST_BROKER_RUNTIME_CONFIG_SCHEMA: &str = "rusty.quest.broker.runtime_config.v1";
/// Real server mutation request schema.
pub const QUEST_BROKER_SERVER_MUTATION_SCHEMA: &str =
    "rusty.quest.broker.server_mutation_request.v1";
/// Real server mutation response schema.
pub const QUEST_BROKER_SERVER_RESPONSE_SCHEMA: &str =
    "rusty.quest.broker.server_mutation_response.v1";
/// Runtime evidence response schema.
pub const QUEST_BROKER_RUNTIME_EVIDENCE_SCHEMA: &str = "rusty.quest.broker.runtime_evidence.v1";
/// Runtime-owned media owner-completion response schema.
pub const QUEST_BROKER_MEDIA_COMPLETION_RESPONSE_SCHEMA: &str =
    "rusty.quest.broker.media_completion_response.v1";
/// Provider initialization/rebind status schema.
pub const QUEST_BROKER_RUNTIME_INITIALIZE_STATUS_SCHEMA: &str =
    "rusty.quest.broker.runtime_initialize_status.v1";
/// Typed low-rate platform-effect parameters schema.
pub const QUEST_BROKER_EFFECT_PARAMS_SCHEMA: &str = "rusty.quest.broker.effect_params.v1";
const QUEST_BROKER_CLIENT_LOCK_SCHEMA: &str = "rusty.quest.broker_client_spec.v1";
const QUEST_BROKER_ADMISSION_PERMISSION: &str =
    "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";

/// Product-owned inputs used only when a provider process creates a fresh epoch.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerRuntimeConfig {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Standalone or embedded transport placement.
    pub bridge_kind: QuestBrokerAuthorityBridgeKind,
    /// Exact Manifold adapter binding.
    pub adapter_config: ManifoldBrokerAdapterConfig,
    /// Exact immutable product closure.
    pub product_lock: ManifoldBrokerProductLock,
    /// Packaged source/digest bindings for product and client locks.
    pub packaged_authority: QuestBrokerPackagedAuthorityBinding,
    /// Product-owned initial accepted leases.
    pub initial_leases: Vec<ManifoldRuntimeLease>,
    /// Product/operator-owned admission grants and initial state.
    pub admission: QuestBrokerAdmissionConfig,
    /// Exact Manifold/Quest media bindings, required only by media products.
    #[serde(default)]
    pub media_session: Option<QuestBrokerMediaSessionProductBinding>,
    /// Exact Manifold/Quest media bindings for independent app-local sinks.
    #[serde(default)]
    pub media_sessions: Vec<QuestBrokerMediaSessionProductBinding>,
}

/// Cross-repo packaged binding for one source-neutral product media session.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerMediaSessionProductBinding {
    /// Accepted Manifold descriptor and canonical digest.
    pub manifold: ManifoldMediaSessionProductBinding,
    /// Exact Quest runtime spec and canonical digest.
    pub quest: MediaStreamRuntimeProductBinding,
}

/// Raw packaged product/client locks plus exact SHA-256 bindings.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerPackagedAuthorityBinding {
    /// Exact packaged product-spec bytes represented as UTF-8 JSON.
    pub product_spec_json: String,
    /// SHA-256 of the exact packaged product-spec bytes.
    pub product_spec_sha256: String,
    /// Exact packaged accepted-lock bytes represented as UTF-8 JSON.
    pub product_lock_json: String,
    /// SHA-256 of the exact packaged accepted-lock bytes.
    pub product_lock_sha256: String,
    /// Exact packaged client-lock documents and grant bindings.
    pub client_locks: Vec<QuestBrokerPackagedClientLock>,
}

/// One exact client lock bound to one generated Manifold grant.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerPackagedClientLock {
    /// Generated grant identity.
    pub grant_id: DottedId,
    /// Exact packaged client-lock bytes represented as UTF-8 JSON.
    pub client_lock_json: String,
    /// SHA-256 of the exact packaged client-lock bytes.
    pub client_lock_sha256: String,
    /// Exact app lifecycle and feature-lock evidence for the one selected
    /// media client. Non-media clients must leave this absent.
    #[serde(default)]
    pub media_lifecycle_authority: Option<QuestBrokerPackagedMediaLifecycleAuthority>,
}

/// Exact packaged app workflow evidence bound to one broker media client.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerPackagedMediaLifecycleAuthority {
    /// Exact media-lifecycle descriptor bytes represented as UTF-8 JSON.
    pub media_lifecycle_lock_json: String,
    /// Raw lowercase SHA-256 of the exact media-lifecycle bytes.
    pub media_lifecycle_lock_sha256: String,
    /// Exact app feature-lock bytes represented as UTF-8 JSON.
    pub app_feature_lock_json: String,
    /// Raw lowercase SHA-256 of the exact app feature-lock bytes.
    pub app_feature_lock_sha256: String,
    /// Exact cross-repo Manifold/Quest media-binding bytes as UTF-8 JSON.
    pub media_binding_json: String,
    /// Raw lowercase SHA-256 of the exact media-binding bytes.
    pub media_binding_sha256: String,
}

/// Typed projection of the source-only broker client lock.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
struct QuestBrokerClientLockSpec {
    schema: String,
    client_id: DottedId,
    package_name: String,
    feature_lock_id: DottedId,
    marker_namespace: String,
    contract_families: Vec<DottedId>,
    capabilities: Vec<DottedId>,
    adapter_permissions: Vec<String>,
    runtime_properties: Vec<String>,
    application_defaults: Vec<String>,
}

/// Typed, command-bound low-rate parameters eligible for a platform effect.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerEffectParams {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact command whose effect may consume these values.
    pub command_id: DottedId,
    /// Canonically sorted low-rate effect values.
    pub values: BTreeMap<String, Value>,
}

/// One real server mutation request. The transport payload is never authority.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerServerMutationRequest {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Entry-point placement.
    pub bridge_kind: QuestBrokerAuthorityBridgeKind,
    /// Exact live provider epoch returned at initialization.
    pub provider_epoch_id: DottedId,
    /// One-time signature-scoped admission use request.
    pub admission_use_request_id: DottedId,
    /// Opaque admission token that produced the one-time use.
    pub token_id: DottedId,
    /// Current admission revision.
    pub expected_admission_authority_revision: Revision,
    /// Exact Runtime Host request.
    pub command: ManifoldRuntimeCommandRequest,
    /// Typed low-rate platform-effect parameters, read only after acceptance.
    pub params: QuestBrokerEffectParams,
}

/// Platform effect family selected only after Manifold application.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum QuestBrokerPlatformEffectKind {
    /// Generic media-session lifecycle adoption.
    MediaSession,
    /// Explicit legacy remote-camera compatibility adoption.
    RemoteCameraCompatibility,
    /// Hostess bridge request publication.
    HostessBridgeDispatch,
    /// Low-rate stream-event publication.
    StreamPublish,
    /// No Quest platform effect is attached to this accepted host command.
    None,
}

/// Server response whose acceptance fields are produced only by Rust authority.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerServerMutationResponse {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Stable response kind.
    #[serde(rename = "type")]
    pub response_type: String,
    /// Entry-point placement.
    pub bridge_kind: QuestBrokerAuthorityBridgeKind,
    /// Live provider epoch.
    pub provider_epoch_id: DottedId,
    /// Original Runtime Host request identity.
    pub request_id: DottedId,
    /// Original command identity.
    pub command_id: DottedId,
    /// True only when admission and Runtime Host application both applied.
    pub accepted: bool,
    /// Stable summary derived from the Manifold receipt.
    pub status: String,
    /// Explicit proof that the platform entrypoint owns no acceptance policy.
    pub local_acceptance_rules: bool,
    /// Sole command decision owner.
    pub decision_owner_id: DottedId,
    /// Exact admission plus Runtime Host receipt.
    pub mutation_receipt: ManifoldBrokerMutationReceipt,
    /// Effect family Java/platform code may execute only when `accepted=true`.
    pub platform_effect: Option<QuestBrokerPlatformEffectKind>,
    /// Exact receipt-bound effect parameters Java/platform code may consume.
    pub effect_params: Option<QuestBrokerEffectParams>,
    /// Rust-authored media action Java may execute without reinterpretation.
    pub platform_action: Option<MediaStreamPlatformAction>,
    /// Always false at command acceptance; only completion application can set true.
    pub platform_effect_completed: bool,
    /// Closed prepare failure when Manifold accepted but no platform action was issued.
    pub platform_prepare_error: Option<String>,
}

/// Read-only live runtime evidence used for rebind/restart proof.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerRuntimeEvidenceResponse {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Entry-point placement.
    pub bridge_kind: QuestBrokerAuthorityBridgeKind,
    /// Explicit proof that transport owns no acceptance policy.
    pub local_acceptance_rules: bool,
    /// Sole command decision owner.
    pub decision_owner_id: DottedId,
    /// Exact Manifold runtime evidence.
    pub runtime: rusty_manifold_broker_adapter::ManifoldBrokerRuntimeEvidence,
    /// Current selected media lifecycle, when this product owns media.
    pub media_runtime_state: Option<MediaStreamRuntimeState>,
    /// Pending receipt-bound platform action, if any.
    pub media_pending_action: Option<MediaStreamPlatformAction>,
}

/// Request to complete one pending media action inside the Rust authority.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerMediaCompletionRequest {
    /// Exact media client whose pending platform action should complete.
    pub client_id: DottedId,
}

/// Rust-owned completion/application result for one pending media action.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerMediaCompletionResponse {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Live provider epoch.
    pub provider_epoch_id: DottedId,
    /// Explicit proof that transport owns no acceptance policy.
    pub local_acceptance_rules: bool,
    /// Sole command/completion decision owner.
    pub decision_owner_id: DottedId,
    /// Exact client identity.
    pub client_id: DottedId,
    /// Retained subject-current Manifold session receipt used by the Quest
    /// runtime when it prepared and applied the pending platform action.
    pub current_acceptance: rusty_manifold_media_session::ManifoldMediaSessionCurrentReceipt,
    /// Completed pending action.
    pub action: MediaStreamPlatformAction,
    /// Owner-specific receipts issued through Rust callbacks.
    pub owner_receipts: Vec<MediaStreamOwnerCompletionReceipt>,
    /// Rust-authored application receipt.
    pub application: MediaStreamPlatformApplicationReceipt,
    /// Explicit proof that the platform effect is complete only after receipt application.
    pub platform_effect_completed: bool,
}

/// Provider initialization or same-process rebind status.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerRuntimeInitializeStatus {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Live provider epoch.
    pub provider_epoch_id: DottedId,
    /// True only when the same in-memory provider was retained.
    pub existing_authority_preserved: bool,
    /// Exact canonical config fingerprint.
    pub config_sha256: String,
    /// Current Runtime Host revision.
    pub runtime_host_revision: Revision,
    /// Current admission revision.
    pub admission_authority_revision: Revision,
    /// Explicit proof that transport owns no acceptance policy.
    pub local_acceptance_rules: bool,
    /// Sole command decision owner.
    pub decision_owner_id: DottedId,
}

/// One live stateful authority instance retained by a provider process.
pub struct QuestBrokerAuthorityRuntime {
    bridge_kind: QuestBrokerAuthorityBridgeKind,
    runtime: ManifoldBrokerRuntime,
    media_bindings: Vec<QuestBrokerMediaSessionProductBinding>,
    peer_runtime_host: Option<Arc<RwLock<ManifoldPeerRuntimeHost>>>,
    media_sessions: BTreeMap<DottedId, MediaStreamSessionProductRuntime>,
}

/// Process-local owner that distinguishes same-provider rebind from restart.
#[derive(Default)]
pub struct QuestBrokerRuntimeProvider {
    runtime: Option<QuestBrokerAuthorityRuntime>,
    config_sha256: Option<String>,
}

struct QuestBrokerMediaOwnerProvider {
    owner: MediaStreamOwnerAction,
    readback: Option<MediaStreamOwnerProviderReadback>,
}

impl QuestBrokerMediaOwnerProvider {
    fn new(owner: MediaStreamOwnerAction) -> Self {
        Self {
            owner,
            readback: None,
        }
    }
}

impl MediaStreamTrustedOwnerProvider for QuestBrokerMediaOwnerProvider {
    fn owner_kind(&self) -> MediaStreamOwnerKind {
        self.owner.selection.owner_kind
    }

    fn execute_and_readback(
        &mut self,
        action: &MediaStreamPlatformAction,
        owner_action: &MediaStreamOwnerAction,
    ) -> Result<MediaStreamOwnerProviderReadback, String> {
        if owner_action != &self.owner {
            return Err("owner action mismatch".to_owned());
        }
        let revision_base = match owner_action.action_kind {
            MediaStreamOwnerActionKind::ArmReceiver
            | MediaStreamOwnerActionKind::ArmCleanup
            | MediaStreamOwnerActionKind::Start => 10,
            MediaStreamOwnerActionKind::Stop | MediaStreamOwnerActionKind::Cleanup => 100,
        };
        let provider_handle_id = format!(
            "handle.{}.{}.{}",
            action.client_authority.client_id,
            owner_action.selection.owner_id,
            owner_action.selection.resource_id
        );
        let receipt_id = format!(
            "receipt.{}.{}.{}",
            action.action_id, owner_action.selection.owner_id, owner_action.selection.resource_id
        );
        let readback = MediaStreamOwnerProviderReadback {
            action_id: action.action_id.clone(),
            authority_epoch_id: action.authority_epoch_id.clone(),
            media_acceptance_authority_revision: action.media_acceptance_authority_revision,
            client_id: action.client_authority.client_id.clone(),
            lease_id: action.client_authority.lease_id.clone(),
            provider_kind: owner_action.selection.provider_kind.clone(),
            resource_id: owner_action.selection.resource_id.clone(),
            provider_handle_id,
            provider_state_revision: revision_base
                + u64::from(owner_action.selection.owner_kind as u8)
                + u64::from(owner_action.action_kind as u8),
            observed_state: expected_media_owner_state(owner_action.action_kind).to_owned(),
            receipt_id,
        };
        self.readback = Some(readback.clone());
        Ok(readback)
    }

    fn compensate_uncertain_attempt(
        &mut self,
        action: &MediaStreamPlatformAction,
        owner_action: &MediaStreamOwnerAction,
    ) -> Result<MediaStreamOwnerProviderReadback, String> {
        self.execute_and_readback(action, owner_action)
    }

    fn verify_readback(
        &self,
        _action: &MediaStreamPlatformAction,
        _owner_action: &MediaStreamOwnerAction,
        readback: &MediaStreamOwnerProviderReadback,
    ) -> bool {
        self.readback.as_ref() == Some(readback)
    }
}

const fn expected_media_owner_state(action: MediaStreamOwnerActionKind) -> &'static str {
    match action {
        MediaStreamOwnerActionKind::ArmReceiver => "receiver_armed",
        MediaStreamOwnerActionKind::ArmCleanup => "cleanup_armed",
        MediaStreamOwnerActionKind::Start => "started",
        MediaStreamOwnerActionKind::Stop => "stopped",
        MediaStreamOwnerActionKind::Cleanup => "cleaned",
    }
}

impl QuestBrokerRuntimeProvider {
    /// Initializes a new provider or preserves the exact existing provider on rebind.
    ///
    /// # Errors
    ///
    /// Rejects malformed/drifted config, invalid epoch entropy, or a rebind that
    /// supplies a different immutable product/grant configuration.
    pub fn initialize(
        &mut self,
        config_json: &str,
        expected_config_sha256: &str,
        provider_epoch_entropy_hex: &str,
    ) -> Result<QuestBrokerRuntimeInitializeStatus, QuestBrokerRuntimeError> {
        let config: QuestBrokerRuntimeConfig =
            serde_json::from_str(config_json).map_err(QuestBrokerRuntimeError::Decode)?;
        let canonical = serde_json::to_vec(&config).map_err(QuestBrokerRuntimeError::Encode)?;
        let config_sha256 = sha256_hex(&canonical);
        if expected_config_sha256 != config_sha256 {
            return Err(QuestBrokerRuntimeError::PackagedConfigDigestMismatch);
        }
        if let Some(runtime) = self.runtime.as_ref() {
            if self.config_sha256.as_deref() != Some(config_sha256.as_str()) {
                return Err(QuestBrokerRuntimeError::RebindConfigMismatch);
            }
            return Ok(initialize_status(runtime, config_sha256, true));
        }
        let runtime = QuestBrokerAuthorityRuntime::from_config(config, provider_epoch_entropy_hex)?;
        let status = initialize_status(&runtime, config_sha256.clone(), false);
        self.runtime = Some(runtime);
        self.config_sha256 = Some(config_sha256);
        Ok(status)
    }

    /// Executes one admission operation through the live provider.
    ///
    /// # Errors
    ///
    /// Rejects use before initialization or any operation failure.
    pub fn execute_admission_json(
        &mut self,
        json: &str,
    ) -> Result<String, QuestBrokerRuntimeError> {
        self.runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?
            .execute_admission_json(json)
    }

    /// Executes one admitted server mutation through the live provider.
    ///
    /// # Errors
    ///
    /// Rejects use before initialization or any mutation failure.
    pub fn handle_server_mutation_json(
        &mut self,
        json: &str,
        now_ms: u64,
    ) -> Result<String, QuestBrokerRuntimeError> {
        self.runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?
            .handle_server_mutation_json(json, now_ms)
    }

    /// Completes one pending media action through Rust-owned owner callbacks.
    ///
    /// # Errors
    ///
    /// Rejects use before initialization, malformed JSON, or lifecycle failure.
    pub fn complete_media_action_json(
        &mut self,
        request_json: &str,
        now_ms: u64,
    ) -> Result<String, QuestBrokerRuntimeError> {
        let request: QuestBrokerMediaCompletionRequest =
            serde_json::from_str(request_json).map_err(QuestBrokerRuntimeError::Decode)?;
        let response = self
            .runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?
            .complete_media_session_action(&request, now_ms)?;
        serde_json::to_string(&response).map_err(QuestBrokerRuntimeError::Encode)
    }

    /// Returns integrated runtime evidence JSON.
    ///
    /// # Errors
    ///
    /// Rejects use before initialization or serialization failure.
    pub fn evidence_json(&self) -> Result<String, QuestBrokerRuntimeError> {
        self.runtime
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?
            .evidence_json()
    }

    /// Returns the raw Manifold admission snapshot JSON for compatibility clients.
    ///
    /// # Errors
    ///
    /// Rejects use before initialization or serialization failure.
    pub fn admission_snapshot_json(&self) -> Result<String, QuestBrokerRuntimeError> {
        self.runtime
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?
            .admission_snapshot_json()
    }
}

/// Returns the canonical SHA-256 expected by a packaged runtime initializer.
///
/// # Errors
///
/// Rejects malformed runtime config JSON or canonical serialization failure.
pub fn canonical_runtime_config_sha256(
    config_json: &str,
) -> Result<String, QuestBrokerRuntimeError> {
    let config: QuestBrokerRuntimeConfig =
        serde_json::from_str(config_json).map_err(QuestBrokerRuntimeError::Decode)?;
    let canonical = serde_json::to_vec(&config).map_err(QuestBrokerRuntimeError::Encode)?;
    Ok(sha256_hex(&canonical))
}

fn effective_media_bindings(
    config: &QuestBrokerRuntimeConfig,
) -> Vec<QuestBrokerMediaSessionProductBinding> {
    let mut bindings = config.media_sessions.clone();
    if bindings.is_empty() {
        if let Some(binding) = config.media_session.clone() {
            bindings.push(binding);
        }
    }
    bindings
}

impl QuestBrokerAuthorityRuntime {
    /// Creates a fresh authority epoch from exact product/grant state and entropy.
    ///
    /// # Errors
    ///
    /// Rejects config, mode, product-lock, admission-state, or epoch-entropy drift.
    pub fn from_config(
        config: QuestBrokerRuntimeConfig,
        provider_epoch_entropy_hex: &str,
    ) -> Result<Self, QuestBrokerRuntimeError> {
        if config.schema_id != QUEST_BROKER_RUNTIME_CONFIG_SCHEMA
            || config.admission.schema_id != QUEST_ADMISSION_CONFIG_SCHEMA
        {
            return Err(QuestBrokerRuntimeError::SchemaMismatch);
        }
        validate_media_session_binding(&config)?;
        validate_packaged_authority(&config)?;
        validate_bridge_mode(&config.bridge_kind, &config.adapter_config.mode)?;
        let media_bindings = effective_media_bindings(&config);
        let provider_epoch_id = provider_epoch(provider_epoch_entropy_hex)?;
        let peer_runtime_host = build_media_peer_runtime_host(&config, &provider_epoch_id)?;
        let packaged_product_lock = config.packaged_authority.product_lock_json.into_bytes();
        let adapter = ManifoldBrokerAdapter::new(
            config.adapter_config,
            &packaged_product_lock,
            config.initial_leases,
        )
        .map_err(QuestBrokerRuntimeError::Adapter)?;
        let runtime =
            ManifoldBrokerRuntime::new(provider_epoch_id, adapter, config.admission.snapshot)
                .map_err(QuestBrokerRuntimeError::Admission)?;
        Ok(Self {
            bridge_kind: config.bridge_kind,
            runtime,
            media_bindings,
            peer_runtime_host,
            media_sessions: BTreeMap::new(),
        })
    }

    /// Creates a fresh authority epoch from JSON config.
    ///
    /// # Errors
    ///
    /// Rejects malformed JSON or any invalid initialization input.
    pub fn from_config_json(
        config_json: &str,
        provider_epoch_entropy_hex: &str,
    ) -> Result<Self, QuestBrokerRuntimeError> {
        let config = serde_json::from_str(config_json).map_err(QuestBrokerRuntimeError::Decode)?;
        Self::from_config(config, provider_epoch_entropy_hex)
    }

    /// Returns the current provider epoch.
    #[must_use]
    pub const fn provider_epoch_id(&self) -> &DottedId {
        self.runtime.provider_epoch_id()
    }

    /// Executes one signature-scoped Binder admission operation through Manifold.
    ///
    /// # Errors
    ///
    /// Rejects bridge schema/entropy errors. Manifold admission rejections remain receipts.
    pub fn execute_admission(
        &mut self,
        operation: QuestBrokerAdmissionOperation,
    ) -> Result<QuestBrokerAdmissionResponse, QuestBrokerRuntimeError> {
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
                let request = ManifoldAdmissionRequest {
                    schema_id: schema(ADMISSION_REQUEST_SCHEMA),
                    request_id,
                    expected_authority_revision,
                    identity: project_binder_caller(self.runtime.admission_snapshot(), &caller),
                    requested_capabilities,
                    issued_at_ms,
                    expires_at_ms,
                    requested_token_ttl_ms,
                };
                self.runtime.issue_token(
                    &request,
                    parse_entropy_hex(&entropy_hex)
                        .map_err(QuestBrokerRuntimeError::AdmissionProjection)?,
                    issued_at_ms,
                )
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
                    identity: project_binder_caller(self.runtime.admission_snapshot(), &caller),
                    capability_id,
                    issued_at_ms,
                    expires_at_ms,
                };
                self.runtime.authorize_use(&request, issued_at_ms)
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
                let request = ManifoldAdmissionRevocationRequest {
                    schema_id: schema(ADMISSION_REVOCATION_REQUEST_SCHEMA),
                    request_id,
                    expected_authority_revision,
                    token_id,
                    identity: project_binder_caller(self.runtime.admission_snapshot(), &caller),
                    reason,
                };
                self.runtime.revoke_token(&request)
            }
            QuestBrokerAdmissionOperation::ExpireTokens {
                schema_id,
                sweep_id,
                expected_authority_revision,
                now_ms,
            } => {
                check_operation_schema(&schema_id)?;
                self.runtime
                    .expire_tokens(sweep_id, expected_authority_revision, now_ms)
            }
        };
        Ok(QuestBrokerAdmissionResponse {
            schema_id: QUEST_ADMISSION_RESPONSE_SCHEMA.to_owned(),
            platform_adapter: SIGNATURE_SCOPED_BINDER_ADAPTER.to_owned(),
            decision_owner: MANIFOLD_ADMISSION_OWNER.to_owned(),
            local_token_or_grant_policy: false,
            receipt,
            provider_epoch_id: Some(self.runtime.provider_epoch_id().clone()),
            runtime_host_revision: Some(self.runtime.host_snapshot().authority_revision),
        })
    }

    fn start_media_session(
        &mut self,
        mutation: &ManifoldBrokerMutationRequest,
        now_ms: u64,
    ) -> Result<
        (
            ManifoldBrokerMutationReceipt,
            Option<MediaStreamPlatformAction>,
        ),
        QuestBrokerRuntimeError,
    > {
        let live_host = self
            .peer_runtime_host
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut peer = live_host
            .read()
            .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?
            .clone();
        let mut broker = self.runtime.clone();
        let attempt = peer
            .apply_broker_media_command_and_admit_runtime_lease(&mut broker, mutation, now_ms)
            .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        let receipt = attempt.broker_receipt.clone();
        let admission = match attempt.outcome {
            ManifoldPeerRuntimeBrokerLeaseAttemptOutcome::BrokerAdmissionRejected => {
                return Ok((receipt, None));
            }
            ManifoldPeerRuntimeBrokerLeaseAttemptOutcome::BrokerCommandRejected
            | ManifoldPeerRuntimeBrokerLeaseAttemptOutcome::PeerLeaseRejected => {
                self.runtime = broker;
                self.peer_runtime_host = Some(Arc::new(RwLock::new(peer)));
                return Ok((receipt, None));
            }
            ManifoldPeerRuntimeBrokerLeaseAttemptOutcome::LeaseAdmitted => attempt
                .lease_admission
                .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected)?,
        };
        let grant = peer
            .snapshot()
            .trust_policy
            .media_client_grants
            .iter()
            .find(|grant| {
                grant.client_id == mutation.command.requester_id
                    && grant.broker_command_id == mutation.command.command_id
                    && grant.lease_id == admission.runtime_lease.lease_id
            })
            .cloned()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let binding = self
            .media_bindings
            .iter()
            .find(|binding| {
                binding.manifold.descriptor.platform_runtime_spec_id
                    == grant.allowed_platform_runtime_spec_id
            })
            .cloned()
            .ok_or(QuestBrokerRuntimeError::MediaProductNotSelected)?;
        let acceptance_request_id =
            derived_request_id("request.quest.media.accept", &mutation.command.request_id)?;
        let runtime_request_id = derived_request_id(
            "request.quest.media.runtime.accept",
            &mutation.command.request_id,
        )?;
        let acceptance = ManifoldMediaSessionAcceptanceRequest {
            schema_id: schema(MANIFOLD_MEDIA_SESSION_ACCEPTANCE_REQUEST_SCHEMA),
            request_id: acceptance_request_id,
            expected_authority_revision: peer.snapshot().media_sessions.authority_revision,
            runtime_command_request_id: runtime_request_id.clone(),
            expected_provider_epoch_id: self.runtime.provider_epoch_id().clone(),
            product_id: grant.product_id.clone(),
            feature_lock_id: grant.feature_lock_id.clone(),
            feature_lock_fingerprint: grant.feature_lock_fingerprint.clone(),
            capability_id: grant.capability_id.clone(),
            admission_grant_id: grant.admission_grant_id.clone(),
            expires_at_ms: admission.runtime_lease.expires_at_ms,
            product_binding: binding.manifold.clone(),
        };
        let acceptance_command = ManifoldRuntimeCommandRequest {
            schema_id: schema(HOST_COMMAND_REQUEST_SCHEMA),
            request_id: runtime_request_id,
            expected_authority_revision: peer.snapshot().media_command_runtime.authority_revision,
            requester_id: grant.client_id.clone(),
            command_id: DottedId::new(MANIFOLD_MEDIA_SESSION_ACCEPT_COMMAND)
                .expect("static media command"),
            lease_id: Some(admission.runtime_lease.lease_id.clone()),
            params_digest: Some(
                media_session_acceptance_params_digest(&acceptance)
                    .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?,
            ),
            issued_at_ms: now_ms,
            expires_at_ms: admission.runtime_lease.expires_at_ms,
        };
        let accepted = peer
            .review_media_session_acceptance(&acceptance, &acceptance_command, now_ms)
            .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        if !accepted.accepted || accepted.accepted_session.is_none() {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
        }
        let peer = Arc::new(RwLock::new(peer));
        let mut media = MediaStreamSessionProductRuntime::new(
            binding.quest,
            Arc::clone(&peer),
            accepted.decision_id,
            now_ms,
        )
        .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        let action = media
            .prepare(
                format!("platform.{}", mutation.command.request_id.as_str()),
                MediaStreamPlatformOperation::Start,
                media_client_authority(&grant, mutation, &admission.broker_receipt)?,
                now_ms,
            )
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;

        self.runtime = broker;
        self.peer_runtime_host = Some(peer);
        self.media_sessions.insert(grant.client_id.clone(), media);
        Ok((admission.broker_receipt, Some(action)))
    }

    fn stop_media_session(
        &mut self,
        mutation: &ManifoldBrokerMutationRequest,
        now_ms: u64,
    ) -> Result<
        (
            ManifoldBrokerMutationReceipt,
            Option<MediaStreamPlatformAction>,
        ),
        QuestBrokerRuntimeError,
    > {
        let live_host = self
            .peer_runtime_host
            .as_ref()
            .cloned()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let grant = live_host
            .read()
            .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?
            .snapshot()
            .trust_policy
            .media_client_grants
            .iter()
            .find(|grant| grant.client_id == mutation.command.requester_id)
            .cloned()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut broker = self.runtime.clone();
        let receipt = broker.handle_mutation(mutation, now_ms);
        self.runtime = broker.clone();
        if !receipt.applied {
            return Ok((receipt, None));
        }
        if receipt.bounded_use.is_none() {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
        }
        let client_authority = media_client_authority(&grant, mutation, &receipt)?;
        let action_id = format!("platform.{}", mutation.command.request_id.as_str());
        let old_peer = live_host
            .read()
            .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?
            .clone();
        let mut peer = old_peer.clone();
        let current = self
            .media_sessions
            .get(&grant.client_id)
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?
            .current_acceptance()
            .session
            .as_ref()
            .cloned()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let termination = ManifoldMediaSessionTerminationRequest {
            schema_id: schema(MANIFOLD_MEDIA_SESSION_TERMINATION_REQUEST_SCHEMA),
            request_id: derived_request_id(
                "request.quest.media.stop",
                &mutation.command.request_id,
            )?,
            expected_authority_revision: peer.snapshot().media_sessions.authority_revision,
            runtime_command_request_id: derived_request_id(
                "request.quest.media.runtime.stop",
                &mutation.command.request_id,
            )?,
            decision_id: current.decision_id,
            session_id: current.session_id,
            expected_provider_epoch_id: self.runtime.provider_epoch_id().clone(),
            action: ManifoldMediaSessionTerminationAction::Stop,
        };
        let termination_command = ManifoldRuntimeCommandRequest {
            schema_id: schema(HOST_COMMAND_REQUEST_SCHEMA),
            request_id: termination.runtime_command_request_id.clone(),
            expected_authority_revision: peer.snapshot().media_command_runtime.authority_revision,
            requester_id: grant.client_id.clone(),
            command_id: DottedId::new(MANIFOLD_MEDIA_SESSION_STOP_COMMAND)
                .expect("static media stop"),
            lease_id: Some(grant.lease_id.clone()),
            params_digest: Some(
                media_session_termination_params_digest(&termination)
                    .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?,
            ),
            issued_at_ms: now_ms,
            expires_at_ms: now_ms.saturating_add(30_000),
        };
        let terminated = peer
            .review_media_session_termination(&termination, &termination_command, now_ms)
            .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        if !terminated.applied {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
        }

        let candidate_host = Arc::new(RwLock::new(peer));
        let action = self
            .media_sessions
            .get_mut(&grant.client_id)
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?
            .prepare_with_live_authority(
                Arc::clone(&candidate_host),
                action_id,
                MediaStreamPlatformOperation::Stop,
                client_authority,
                now_ms,
            )
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        self.runtime = broker;
        self.peer_runtime_host = Some(candidate_host);
        Ok((receipt, Some(action)))
    }

    fn complete_media_session_action(
        &mut self,
        request: &QuestBrokerMediaCompletionRequest,
        now_ms: u64,
    ) -> Result<QuestBrokerMediaCompletionResponse, QuestBrokerRuntimeError> {
        let provider_epoch_id = self.runtime.provider_epoch_id().clone();
        let media = self
            .media_sessions
            .get_mut(&request.client_id)
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let action = media
            .pending_action()
            .cloned()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let current_acceptance = media.current_acceptance().clone();
        let mut owner_receipts = Vec::new();
        for owner in action.owner_actions.clone() {
            let mut provider = QuestBrokerMediaOwnerProvider::new(owner.clone());
            let receipt = match owner.selection.owner_kind {
                MediaStreamOwnerKind::Source => media.complete_source(&mut provider, now_ms),
                MediaStreamOwnerKind::Processor => media.complete_processor(&mut provider, now_ms),
                MediaStreamOwnerKind::Route => media.complete_route(&mut provider, now_ms),
                MediaStreamOwnerKind::Socket => media.complete_socket(&mut provider, now_ms),
                MediaStreamOwnerKind::Codec => media.complete_codec(&mut provider, now_ms),
                MediaStreamOwnerKind::Sink => media.complete_sink(&mut provider, now_ms),
                MediaStreamOwnerKind::Cleanup => media.complete_cleanup(&mut provider, now_ms),
            }
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
            owner_receipts.push(receipt);
        }
        let application = media
            .apply_recorded_owner_completions(now_ms)
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        Ok(QuestBrokerMediaCompletionResponse {
            schema_id: QUEST_BROKER_MEDIA_COMPLETION_RESPONSE_SCHEMA.to_owned(),
            provider_epoch_id,
            local_acceptance_rules: false,
            decision_owner_id: DottedId::new(RUNTIME_HOST_AUTHORITY_OWNER)
                .expect("static authority owner"),
            client_id: request.client_id.clone(),
            current_acceptance,
            action,
            owner_receipts,
            application,
            platform_effect_completed: true,
        })
    }

    /// Executes a real standalone/embedded server mutation through admission and Runtime Host.
    ///
    /// # Errors
    ///
    /// Rejects an invalid server schema or bridge placement before mutation.
    pub fn handle_server_mutation(
        &mut self,
        request: &QuestBrokerServerMutationRequest,
        now_ms: u64,
    ) -> Result<QuestBrokerServerMutationResponse, QuestBrokerRuntimeError> {
        if request.schema_id != QUEST_BROKER_SERVER_MUTATION_SCHEMA {
            return Err(QuestBrokerRuntimeError::SchemaMismatch);
        }
        validate_bridge_kind(&request.bridge_kind, &self.bridge_kind)?;
        if request.params.schema_id != QUEST_BROKER_EFFECT_PARAMS_SCHEMA {
            return Err(QuestBrokerRuntimeError::EffectParamsSchemaMismatch);
        }
        if request.params.command_id != request.command.command_id {
            return Err(QuestBrokerRuntimeError::EffectParamsCommandMismatch);
        }
        if request
            .command
            .command_id
            .as_str()
            .starts_with("command.media.session.")
            && !request.params.values.is_empty()
        {
            return Err(QuestBrokerRuntimeError::EffectParamsShapeMismatch);
        }
        let params_digest = canonical_effect_params_digest(&request.params)?;
        if request.command.params_digest.as_ref() != Some(&params_digest) {
            return Err(QuestBrokerRuntimeError::EffectParamsDigestMismatch);
        }
        let mutation = ManifoldBrokerMutationRequest {
            schema_id: schema(BROKER_MUTATION_REQUEST_SCHEMA),
            provider_epoch_id: request.provider_epoch_id.clone(),
            admission_use_request_id: request.admission_use_request_id.clone(),
            token_id: request.token_id.clone(),
            expected_admission_authority_revision: request.expected_admission_authority_revision,
            command: request.command.clone(),
        };
        let (receipt, platform_action) = match request.command.command_id.as_str() {
            "command.media.session.start" => self.start_media_session(&mutation, now_ms)?,
            "command.media.session.stop" => self.stop_media_session(&mutation, now_ms)?,
            _ => (self.runtime.handle_mutation(&mutation, now_ms), None),
        };
        if let Some(adapter_receipt) = &receipt.adapter_receipt {
            if adapter_receipt.dispatch.params_digest.as_ref() != Some(&params_digest)
                || adapter_receipt.application.params_digest.as_ref() != Some(&params_digest)
            {
                return Err(QuestBrokerRuntimeError::EffectParamsReceiptMismatch);
            }
        }
        let accepted = receipt.applied;
        let status = if accepted {
            "applied"
        } else if receipt.admission_rejection_reason.is_some() {
            "admission_rejected"
        } else {
            "runtime_host_rejected"
        };
        let effect = accepted.then(|| platform_effect(&request.command.command_id));
        let platform_prepare_error = None;
        Ok(QuestBrokerServerMutationResponse {
            schema_id: QUEST_BROKER_SERVER_RESPONSE_SCHEMA.to_owned(),
            response_type: "command_ack".to_owned(),
            bridge_kind: self.bridge_kind.clone(),
            provider_epoch_id: self.runtime.provider_epoch_id().clone(),
            request_id: request.command.request_id.clone(),
            command_id: request.command.command_id.clone(),
            accepted,
            status: status.to_owned(),
            local_acceptance_rules: false,
            decision_owner_id: DottedId::new(RUNTIME_HOST_AUTHORITY_OWNER)
                .expect("static authority owner"),
            mutation_receipt: receipt,
            platform_effect: effect,
            effect_params: accepted.then(|| request.params.clone()),
            platform_action,
            platform_effect_completed: false,
            platform_prepare_error,
        })
    }

    /// Handles one JSON server mutation and returns the Rust-authored response JSON.
    ///
    /// # Errors
    ///
    /// Rejects malformed requests or response serialization failures.
    pub fn handle_server_mutation_json(
        &mut self,
        json: &str,
        now_ms: u64,
    ) -> Result<String, QuestBrokerRuntimeError> {
        let request = serde_json::from_str(json).map_err(QuestBrokerRuntimeError::Decode)?;
        let response = self.handle_server_mutation(&request, now_ms)?;
        serde_json::to_string(&response).map_err(QuestBrokerRuntimeError::Encode)
    }

    /// Executes one Binder admission JSON operation.
    ///
    /// # Errors
    ///
    /// Rejects malformed requests or response serialization failures.
    pub fn execute_admission_json(
        &mut self,
        json: &str,
    ) -> Result<String, QuestBrokerRuntimeError> {
        let operation = serde_json::from_str(json).map_err(QuestBrokerRuntimeError::Decode)?;
        let response = self.execute_admission(operation)?;
        serde_json::to_string(&response).map_err(QuestBrokerRuntimeError::Encode)
    }

    /// Returns current state evidence without granting mutation authority.
    #[must_use]
    pub fn evidence(&self) -> QuestBrokerRuntimeEvidenceResponse {
        QuestBrokerRuntimeEvidenceResponse {
            schema_id: QUEST_BROKER_RUNTIME_EVIDENCE_SCHEMA.to_owned(),
            bridge_kind: self.bridge_kind.clone(),
            local_acceptance_rules: false,
            decision_owner_id: DottedId::new(RUNTIME_HOST_AUTHORITY_OWNER)
                .expect("static authority owner"),
            runtime: self.runtime.evidence(),
            media_runtime_state: self
                .media_sessions
                .values()
                .next()
                .map(|media| media.runtime().state().clone()),
            media_pending_action: self
                .media_sessions
                .values()
                .next()
                .and_then(|media| media.pending_action().cloned()),
        }
    }

    /// Returns current state evidence as JSON.
    ///
    /// # Errors
    ///
    /// Returns serialization failure.
    pub fn evidence_json(&self) -> Result<String, QuestBrokerRuntimeError> {
        serde_json::to_string(&self.evidence()).map_err(QuestBrokerRuntimeError::Encode)
    }

    /// Returns the raw accepted admission snapshot for existing Binder clients.
    ///
    /// # Errors
    ///
    /// Returns serialization failure.
    pub fn admission_snapshot_json(&self) -> Result<String, QuestBrokerRuntimeError> {
        serde_json::to_string(self.runtime.admission_snapshot())
            .map_err(QuestBrokerRuntimeError::Encode)
    }
}

fn initialize_status(
    runtime: &QuestBrokerAuthorityRuntime,
    config_sha256: String,
    existing_authority_preserved: bool,
) -> QuestBrokerRuntimeInitializeStatus {
    let evidence = runtime.evidence();
    QuestBrokerRuntimeInitializeStatus {
        schema_id: QUEST_BROKER_RUNTIME_INITIALIZE_STATUS_SCHEMA.to_owned(),
        provider_epoch_id: evidence.runtime.provider_epoch_id,
        existing_authority_preserved,
        config_sha256,
        runtime_host_revision: evidence.runtime.host_snapshot.authority_revision,
        admission_authority_revision: evidence.runtime.admission_snapshot.authority_revision,
        local_acceptance_rules: false,
        decision_owner_id: DottedId::new(RUNTIME_HOST_AUTHORITY_OWNER)
            .expect("static authority owner"),
    }
}

fn derived_request_id(
    prefix: &str,
    source: &DottedId,
) -> Result<DottedId, QuestBrokerRuntimeError> {
    DottedId::new(format!("{prefix}.{}", source.as_str()))
        .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)
}

fn media_client_authority(
    grant: &ManifoldMediaSessionClientGrant,
    mutation: &ManifoldBrokerMutationRequest,
    receipt: &ManifoldBrokerMutationReceipt,
) -> Result<MediaStreamClientAuthorityBinding, QuestBrokerRuntimeError> {
    let bounded_use = receipt
        .bounded_use
        .as_ref()
        .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected)?;
    if bounded_use.admission_use_request_id != mutation.admission_use_request_id
        || bounded_use.identity != grant.broker_client_identity
        || bounded_use.admission_grant_id != grant.admission_grant_id
        || bounded_use.capability_id != command_capability(&mutation.command.command_id)
    {
        return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
    }
    Ok(MediaStreamClientAuthorityBinding {
        client_id: grant.client_id.as_str().to_owned(),
        lease_id: grant.lease_id.as_str().to_owned(),
        product_id: grant.product_id.as_str().to_owned(),
        feature_lock_id: grant.feature_lock_id.as_str().to_owned(),
        feature_lock_fingerprint: grant.feature_lock_fingerprint.clone(),
        session_capability_id: grant.capability_id.as_str().to_owned(),
        session_admission_grant_id: grant.admission_grant_id.as_str().to_owned(),
        operation_capability_id: command_capability(&mutation.command.command_id)
            .as_str()
            .to_owned(),
        operation_admission_grant_id: bounded_use.admission_grant_id.as_str().to_owned(),
        operation_admission_use_request_id: bounded_use
            .admission_use_request_id
            .as_str()
            .to_owned(),
    })
}

fn platform_effect(command_id: &DottedId) -> QuestBrokerPlatformEffectKind {
    let value = command_id.as_str();
    if value.starts_with("command.remote_camera.") || value.starts_with("command.media_stream.") {
        QuestBrokerPlatformEffectKind::RemoteCameraCompatibility
    } else if value.starts_with("command.media.session.") {
        QuestBrokerPlatformEffectKind::MediaSession
    } else if value == "command.hostess.makepad.bridge_probe.set_marker"
        || value == "hostess.makepad.bridge_probe.set_marker"
    {
        QuestBrokerPlatformEffectKind::HostessBridgeDispatch
    } else if value == "command.stream.publish" {
        QuestBrokerPlatformEffectKind::StreamPublish
    } else {
        QuestBrokerPlatformEffectKind::None
    }
}

fn validate_packaged_authority(
    config: &QuestBrokerRuntimeConfig,
) -> Result<(), QuestBrokerRuntimeError> {
    let binding = &config.packaged_authority;
    if sha256_hex(binding.product_spec_json.as_bytes()) != binding.product_spec_sha256
        || sha256_hex(binding.product_lock_json.as_bytes()) != binding.product_lock_sha256
    {
        return Err(QuestBrokerRuntimeError::PackagedDigestMismatch);
    }
    let product_spec: ManifoldBrokerProductSpec = serde_json::from_str(&binding.product_spec_json)
        .map_err(QuestBrokerRuntimeError::Decode)?;
    let product_lock: ManifoldBrokerProductLock = serde_json::from_str(&binding.product_lock_json)
        .map_err(QuestBrokerRuntimeError::Decode)?;
    validate_broker_product_lock(&product_spec, &product_lock)
        .map_err(|_| QuestBrokerRuntimeError::PackagedProductLockMismatch)?;
    if product_lock != config.product_lock {
        return Err(QuestBrokerRuntimeError::PackagedProductLockMismatch);
    }
    if config.adapter_config.product_lock_sha256
        != format!("sha256:{}", binding.product_lock_sha256)
    {
        return Err(QuestBrokerRuntimeError::PackagedProductLockMismatch);
    }
    if binding.client_locks.len() != config.admission.snapshot.grants.len() {
        return Err(QuestBrokerRuntimeError::GrantClosureMismatch);
    }

    let mut seen_grants = BTreeSet::new();
    let mut seen_clients = BTreeSet::new();
    let mut selected_media_clients = Vec::new();
    for packaged_client in &binding.client_locks {
        if sha256_hex(packaged_client.client_lock_json.as_bytes())
            != packaged_client.client_lock_sha256
        {
            return Err(QuestBrokerRuntimeError::PackagedDigestMismatch);
        }
        let client: QuestBrokerClientLockSpec =
            serde_json::from_str(&packaged_client.client_lock_json)
                .map_err(QuestBrokerRuntimeError::Decode)?;
        validate_client_lock(&client)?;
        if !seen_grants.insert(packaged_client.grant_id.clone())
            || !seen_clients.insert(client.client_id.clone())
        {
            return Err(QuestBrokerRuntimeError::GrantClosureMismatch);
        }
        let grant = config
            .admission
            .snapshot
            .grants
            .iter()
            .find(|grant| grant.grant_id == packaged_client.grant_id)
            .ok_or(QuestBrokerRuntimeError::GrantClosureMismatch)?;
        let expected_capabilities = derive_grant_capabilities(&product_lock, &client);
        let expected_client_lock_fingerprint =
            format!("sha256:{}", packaged_client.client_lock_sha256);
        if grant.identity.client_id != client.client_id
            || grant.identity.platform_subject != client.package_name
            || grant.client_lock_id != client.feature_lock_id
            || grant.client_lock_fingerprint != expected_client_lock_fingerprint
            || grant.capabilities != expected_capabilities
            || grant.revoked
        {
            return Err(QuestBrokerRuntimeError::GrantClosureMismatch);
        }
        if let Some(lifecycle) =
            validate_media_lifecycle_authority(config, &client, packaged_client)?
        {
            selected_media_clients.push((
                client.client_id,
                DottedId::new(lifecycle.broker_runtime_lease_id)
                    .map_err(|_| QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch)?,
            ));
        }
    }
    let media_bindings = effective_media_bindings(config);
    if !media_bindings.is_empty() {
        if selected_media_clients.is_empty() {
            return Err(QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch);
        }
        for (client_id, lease_id) in &selected_media_clients {
            let media_leases = config
                .initial_leases
                .iter()
                .filter(|lease| {
                    lease.scope.as_str() == "lease.media.session"
                        && lease.holder_id == *client_id
                        && lease.lease_id == *lease_id
                })
                .collect::<Vec<_>>();
            if media_leases.len() != 1 {
                return Err(QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch);
            }
        }
    } else if !selected_media_clients.is_empty()
        || config
            .initial_leases
            .iter()
            .any(|lease| lease.scope.as_str() == "lease.media.session")
    {
        return Err(QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch);
    }
    Ok(())
}

fn validate_media_lifecycle_authority(
    config: &QuestBrokerRuntimeConfig,
    client: &QuestBrokerClientLockSpec,
    packaged: &QuestBrokerPackagedClientLock,
) -> Result<Option<BrokerMediaLifecycleLock>, QuestBrokerRuntimeError> {
    let Some(authority) = &packaged.media_lifecycle_authority else {
        return Ok(None);
    };
    let package = BrokerMediaLifecyclePackageBinding {
        schema_id: BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA.to_owned(),
        product_lock_json: config.packaged_authority.product_lock_json.clone(),
        product_lock_sha256: format!("sha256:{}", config.packaged_authority.product_lock_sha256),
        client_lock_json: packaged.client_lock_json.clone(),
        client_lock_sha256: format!("sha256:{}", packaged.client_lock_sha256),
        media_lifecycle_lock_json: authority.media_lifecycle_lock_json.clone(),
        media_lifecycle_lock_sha256: format!("sha256:{}", authority.media_lifecycle_lock_sha256),
        app_feature_lock_json: authority.app_feature_lock_json.clone(),
        app_feature_lock_sha256: format!("sha256:{}", authority.app_feature_lock_sha256),
        media_binding_json: authority.media_binding_json.clone(),
        media_binding_sha256: format!("sha256:{}", authority.media_binding_sha256),
    };
    let validated = validate_media_lifecycle_package(&package)
        .map_err(|_| QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch)?;
    if validated.product_lock != config.product_lock
        || validated.client.client_id != client.client_id.as_str()
        || validated.client.package_name != client.package_name
        || validated.client.feature_lock_id != client.feature_lock_id.as_str()
        || !effective_media_bindings(config).iter().any(|binding| {
            validated.media.manifold == binding.manifold && validated.media.quest == binding.quest
        })
    {
        return Err(QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch);
    }
    Ok(Some(validated.lifecycle))
}

fn build_media_peer_runtime_host(
    config: &QuestBrokerRuntimeConfig,
    provider_epoch_id: &DottedId,
) -> Result<Option<Arc<RwLock<ManifoldPeerRuntimeHost>>>, QuestBrokerRuntimeError> {
    if effective_media_bindings(config).is_empty() {
        return Ok(None);
    }
    let media_runtime_host_id = DottedId::new(format!(
        "host.quest.media-runtime.{}",
        config.product_lock.product_id.as_str()
    ))
    .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
    let media_scope = DottedId::new("lease.media.session").expect("static media scope");
    let outer_command = DottedId::new("command.media.session.start").expect("static command");
    let outer_capability = command_capability(&outer_command);
    let inner_capability =
        DottedId::new("capability.media.session.accept").expect("static capability");
    let mut grants = Vec::new();
    for packaged in &config.packaged_authority.client_locks {
        if packaged.media_lifecycle_authority.is_none() {
            continue;
        }
        let client: QuestBrokerClientLockSpec = serde_json::from_str(&packaged.client_lock_json)
            .map_err(QuestBrokerRuntimeError::Decode)?;
        let lifecycle = validate_media_lifecycle_authority(config, &client, packaged)?
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let media = {
            let authority = packaged
                .media_lifecycle_authority
                .as_ref()
                .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
            let package = BrokerMediaLifecyclePackageBinding {
                schema_id: BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA.to_owned(),
                product_lock_json: config.packaged_authority.product_lock_json.clone(),
                product_lock_sha256: format!(
                    "sha256:{}",
                    config.packaged_authority.product_lock_sha256
                ),
                client_lock_json: packaged.client_lock_json.clone(),
                client_lock_sha256: format!("sha256:{}", packaged.client_lock_sha256),
                media_lifecycle_lock_json: authority.media_lifecycle_lock_json.clone(),
                media_lifecycle_lock_sha256: format!(
                    "sha256:{}",
                    authority.media_lifecycle_lock_sha256
                ),
                app_feature_lock_json: authority.app_feature_lock_json.clone(),
                app_feature_lock_sha256: format!("sha256:{}", authority.app_feature_lock_sha256),
                media_binding_json: authority.media_binding_json.clone(),
                media_binding_sha256: format!("sha256:{}", authority.media_binding_sha256),
            };
            validate_media_lifecycle_package(&package)
                .map_err(|_| QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch)?
                .media
        };
        let mut resources = media
            .manifold
            .descriptor
            .source_ids
            .iter()
            .chain(&media.manifold.descriptor.processor_ids)
            .chain(&media.manifold.descriptor.route_ids)
            .chain(&media.manifold.descriptor.sink_ids)
            .chain(&media.manifold.descriptor.stream_ids)
            .cloned()
            .collect::<Vec<_>>();
        resources.sort();
        if resources.is_empty() || resources.windows(2).any(|pair| pair[0] >= pair[1]) {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeConfig);
        }
        if !client.capabilities.contains(&outer_capability) {
            continue;
        }
        let admission = config
            .admission
            .snapshot
            .grants
            .iter()
            .find(|grant| grant.grant_id == packaged.grant_id)
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let outer_leases = config
            .initial_leases
            .iter()
            .filter(|lease| {
                lease.holder_id == client.client_id
                    && lease.scope == media_scope
                    && lease.lease_id.as_str() == lifecycle.broker_runtime_lease_id
            })
            .collect::<Vec<_>>();
        if outer_leases.len() != 1 {
            continue;
        }
        grants.push(ManifoldMediaSessionClientGrant {
            broker_adapter_id: config.adapter_config.adapter_id.clone(),
            broker_runtime_host_id: config.adapter_config.authority_host_id.clone(),
            broker_product_lock_id: config.product_lock.lock_id.clone(),
            broker_product_lock_fingerprint: config.product_lock.spec_fingerprint.clone(),
            broker_product_lock_sha256: config.adapter_config.product_lock_sha256.clone(),
            broker_capability_id: outer_capability.clone(),
            broker_command_id: outer_command.clone(),
            broker_runtime_lease_id: outer_leases[0].lease_id.clone(),
            broker_client_identity: admission.identity.clone(),
            broker_client_lock_id: client.feature_lock_id.clone(),
            broker_client_lock_fingerprint: format!("sha256:{}", packaged.client_lock_sha256),
            runtime_host_id: media_runtime_host_id.clone(),
            client_id: client.client_id,
            lease_id: DottedId::new(lifecycle.media_runtime_lease_id)
                .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?,
            product_id: config.product_lock.product_id.clone(),
            feature_lock_id: DottedId::new(lifecycle.app_feature_lock_id)
                .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?,
            feature_lock_fingerprint: lifecycle.app_feature_lock_fingerprint,
            capability_id: inner_capability.clone(),
            admission_grant_id: packaged.grant_id.clone(),
            allowed_session_id: media.manifold.descriptor.session_id.clone(),
            allowed_platform_runtime_spec_id: media
                .manifold
                .descriptor
                .platform_runtime_spec_id
                .clone(),
            allowed_descriptor_canonical_sha256: vec![media
                .manifold
                .descriptor_canonical_sha256
                .clone()],
            allowed_resource_ids: resources.clone(),
        });
    }
    grants.sort_by(|left, right| left.client_id.cmp(&right.client_id));
    if grants.is_empty()
        || grants.windows(2).any(|pair| {
            pair[0].client_id >= pair[1].client_id || pair[0].lease_id >= pair[1].lease_id
        })
    {
        return Err(QuestBrokerRuntimeError::MediaPeerRuntimeConfig);
    }
    let trust_policy = ManifoldPeerRuntimeTrustPolicy {
        schema_id: schema(PEER_RUNTIME_HOST_TRUST_POLICY_SCHEMA),
        policy_id: DottedId::new(format!(
            "policy.quest.media-runtime.{}",
            config.product_lock.product_id.as_str()
        ))
        .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?,
        revision: Revision::INITIAL,
        enabled_authority_families: vec![ManifoldPeerRuntimeAuthorityFamily::MediaSession],
        trusted_operator_ids: Vec::new(),
        trusted_key_fingerprints: Vec::new(),
        trusted_adapter_ids: Vec::new(),
        trusted_mesh_proposer_ids: Vec::new(),
        media_client_grants: grants,
        trusted_media_revoker_ids: vec![
            DottedId::new("operator.quest.media-runtime").expect("static revoker")
        ],
        direct_lane_client_grants: Vec::new(),
        trusted_direct_lane_revoker_ids: Vec::new(),
        media_runtime_host_id: media_runtime_host_id.clone(),
        media_runtime_lease_scope_id: media_scope.clone(),
        direct_lane_runtime_lease_scope_id: DottedId::new("lease.direct-lane")
            .expect("static direct lane scope"),
    };
    let media_command_runtime = ManifoldRuntimeHostSnapshot {
        schema_id: schema(HOST_SNAPSHOT_SCHEMA),
        host_id: media_runtime_host_id,
        authority_revision: Revision::INITIAL,
        commands: [
            MANIFOLD_MEDIA_SESSION_ACCEPT_COMMAND,
            rusty_manifold_media_session::MANIFOLD_MEDIA_SESSION_REVOKE_COMMAND,
            MANIFOLD_MEDIA_SESSION_STOP_COMMAND,
        ]
        .into_iter()
        .map(|command| ManifoldRuntimeCommandDescriptor {
            command_id: DottedId::new(command).expect("static media command"),
            required_lease_scope: Some(media_scope.clone()),
        })
        .collect(),
        leases: Vec::new(),
        applied_request_ids: Vec::new(),
        reviewed_sweep_ids: Vec::new(),
        audit_events: Vec::new(),
    };
    let host = ManifoldPeerRuntimeHost::new(
        DottedId::new(format!(
            "host.quest.peer-runtime.{}",
            config.product_lock.product_id.as_str()
        ))
        .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?,
        trust_policy,
        provider_epoch_id.clone(),
        media_command_runtime,
    )
    .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
    Ok(Some(Arc::new(RwLock::new(host))))
}

fn validate_media_session_binding(
    config: &QuestBrokerRuntimeConfig,
) -> Result<(), QuestBrokerRuntimeError> {
    let selected = config
        .product_lock
        .features
        .contains(&ManifoldBrokerFeature::MediaSession);
    let bindings = effective_media_bindings(config);
    if selected != !bindings.is_empty() {
        return Err(QuestBrokerRuntimeError::MediaSelectionMismatch);
    }
    if !config.media_sessions.is_empty() && config.media_session.is_some() {
        return Err(QuestBrokerRuntimeError::MediaSelectionMismatch);
    }
    if bindings.is_empty() {
        return Ok(());
    }
    let camera_selected = config
        .product_lock
        .features
        .contains(&ManifoldBrokerFeature::CameraMedia);
    let direct_p2p_selected = config
        .product_lock
        .features
        .contains(&ManifoldBrokerFeature::DirectP2p);
    let mut seen_runtime_specs = BTreeSet::new();
    for binding in &bindings {
        binding
            .manifold
            .validate()
            .map_err(QuestBrokerRuntimeError::MediaDescriptorBinding)?;
        binding
            .quest
            .validate()
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        let descriptor = &binding.manifold.descriptor;
        let spec = &binding.quest.spec;
        if !seen_runtime_specs.insert(descriptor.platform_runtime_spec_id.clone()) {
            return Err(QuestBrokerRuntimeError::MediaCrossBindingMismatch);
        }
        let exact = descriptor.session_id.as_str() == spec.plan.session_id
            && descriptor.authority_revision.get() == spec.manifold_session_revision
            && descriptor.platform_runtime_spec_id.as_str() == spec.runtime_spec_id
            && dotted_values(&descriptor.source_ids)
                == sorted_values(
                    spec.plan
                        .sources
                        .iter()
                        .map(|value| value.source_id.as_str()),
                )
            && dotted_values(&descriptor.processor_ids)
                == sorted_values(
                    spec.processors
                        .iter()
                        .map(|value| value.processor_id.as_str()),
                )
            && dotted_values(&descriptor.route_ids)
                == sorted_values(
                    spec.plan
                        .transport_routes
                        .iter()
                        .map(|value| value.lane_id.as_str()),
                )
            && dotted_values(&descriptor.sink_ids)
                == sorted_values(spec.sinks.iter().map(|value| value.sink_id.as_str()))
            && dotted_values(&descriptor.stream_ids)
                == sorted_values(
                    spec.plan
                        .lanes
                        .iter()
                        .map(|value| value.media.track_id.as_str()),
                )
            && descriptor.remote_camera_compatibility == spec.compatibility_adapter_id.is_some();
        if !exact {
            return Err(QuestBrokerRuntimeError::MediaCrossBindingMismatch);
        }
        let runtime_uses_camera = spec.plan.sources.iter().any(|source| {
            source.source_kind == rusty_quest_media_stream::SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE
        });
        if (runtime_uses_camera && !camera_selected)
            || (!spec.direct_p2p_routes.is_empty() && !direct_p2p_selected)
        {
            return Err(QuestBrokerRuntimeError::MediaProductFeatureMismatch);
        }
    }
    Ok(())
}

fn dotted_values(values: &[DottedId]) -> Vec<&str> {
    sorted_values(values.iter().map(DottedId::as_str))
}

fn sorted_values<'a>(values: impl Iterator<Item = &'a str>) -> Vec<&'a str> {
    let mut values = values.collect::<Vec<_>>();
    values.sort_unstable();
    values
}

fn validate_client_lock(client: &QuestBrokerClientLockSpec) -> Result<(), QuestBrokerRuntimeError> {
    let capabilities = client.capabilities.iter().collect::<BTreeSet<_>>();
    let contracts = client.contract_families.iter().collect::<BTreeSet<_>>();
    if client.schema != QUEST_BROKER_CLIENT_LOCK_SCHEMA
        || client.package_name.trim().is_empty()
        || client.marker_namespace.trim().is_empty()
        || capabilities.len() != client.capabilities.len()
        || contracts.len() != client.contract_families.len()
        || !client.capabilities.windows(2).all(|pair| pair[0] < pair[1])
        || client.adapter_permissions.len() != 1
        || client.adapter_permissions[0] != QUEST_BROKER_ADMISSION_PERMISSION
        || !client.runtime_properties.is_empty()
        || !client.application_defaults.is_empty()
    {
        return Err(QuestBrokerRuntimeError::ClientLockInvalid);
    }
    Ok(())
}

fn derive_grant_capabilities(
    product_lock: &ManifoldBrokerProductLock,
    client: &QuestBrokerClientLockSpec,
) -> Vec<DottedId> {
    let command_capabilities = product_lock
        .command_ids
        .iter()
        .map(command_capability)
        .collect::<BTreeSet<_>>();
    let media_selected = product_lock
        .features
        .contains(&ManifoldBrokerFeature::MediaSession);
    let peer_session_selected = product_lock.features.iter().any(|feature| {
        matches!(
            feature,
            ManifoldBrokerFeature::DirectP2p | ManifoldBrokerFeature::BleRendezvous
        )
    }) || product_lock
        .command_ids
        .iter()
        .any(|command| command.as_str() == "command.peer.status.get")
        || product_lock
            .stream_ids
            .iter()
            .any(|stream| stream.as_str() == "stream.peer.status");
    client
        .capabilities
        .iter()
        .filter(|capability| {
            command_capabilities.contains(*capability)
                || (media_selected
                    && (capability.as_str() == "capability.media.session.observe"
                        || capability.as_str().starts_with("capability.sink.")))
                || (peer_session_selected
                    && capability.as_str() == "capability.peer.session.observe")
        })
        .cloned()
        .collect()
}

fn provider_epoch(entropy_hex: &str) -> Result<DottedId, QuestBrokerRuntimeError> {
    let entropy =
        parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
    let digest = Sha256::digest(entropy);
    let hex = digest
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    DottedId::new(format!("epoch.provider.{hex}"))
        .map_err(|_| QuestBrokerRuntimeError::InvalidProviderEpoch)
}

fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// Returns the lowercase SHA-256 of exact packaged UTF-8 JSON bytes.
#[must_use]
pub fn packaged_json_sha256(json: &str) -> String {
    sha256_hex(json.as_bytes())
}

/// Computes the canonical Runtime Host binding for typed Quest effect parameters.
///
/// # Errors
///
/// Returns an encoding error or [`QuestBrokerRuntimeError::EffectParamsTooLarge`].
pub fn canonical_effect_params_digest(
    params: &QuestBrokerEffectParams,
) -> Result<ManifoldRuntimeTypedParamsDigest, QuestBrokerRuntimeError> {
    let value = serde_json::to_value(params).map_err(QuestBrokerRuntimeError::Encode)?;
    let mut canonical = String::new();
    write_canonical_json(&value, &mut canonical).map_err(QuestBrokerRuntimeError::Encode)?;
    if canonical.len() > MAX_TYPED_PARAMS_CANONICAL_BYTES as usize {
        return Err(QuestBrokerRuntimeError::EffectParamsTooLarge);
    }
    Ok(ManifoldRuntimeTypedParamsDigest {
        schema_id: schema(HOST_TYPED_PARAMS_DIGEST_SCHEMA),
        params_type_id: DottedId::new(QUEST_BROKER_EFFECT_PARAMS_SCHEMA)
            .expect("static effect params type id"),
        canonical_sha256: format!("sha256:{}", sha256_hex(canonical.as_bytes())),
        canonical_size_bytes: u32::try_from(canonical.len())
            .expect("bounded canonical params fit in u32"),
    })
}

fn write_canonical_json(value: &Value, output: &mut String) -> Result<(), serde_json::Error> {
    match value {
        Value::Null => output.push_str("null"),
        Value::Bool(value) => output.push_str(if *value { "true" } else { "false" }),
        Value::Number(value) => output.push_str(&value.to_string()),
        Value::String(value) => output.push_str(&serde_json::to_string(value)?),
        Value::Array(values) => {
            output.push('[');
            for (index, value) in values.iter().enumerate() {
                if index != 0 {
                    output.push(',');
                }
                write_canonical_json(value, output)?;
            }
            output.push(']');
        }
        Value::Object(values) => {
            output.push('{');
            let mut keys = values.keys().collect::<Vec<_>>();
            keys.sort();
            for (index, key) in keys.into_iter().enumerate() {
                if index != 0 {
                    output.push(',');
                }
                output.push_str(&serde_json::to_string(key)?);
                output.push(':');
                write_canonical_json(&values[key], output)?;
            }
            output.push('}');
        }
    }
    Ok(())
}

fn check_operation_schema(value: &str) -> Result<(), QuestBrokerRuntimeError> {
    if value == QUEST_ADMISSION_OPERATION_SCHEMA {
        Ok(())
    } else {
        Err(QuestBrokerRuntimeError::SchemaMismatch)
    }
}

fn validate_bridge_mode(
    kind: &QuestBrokerAuthorityBridgeKind,
    mode: &ManifoldBrokerAdapterMode,
) -> Result<(), QuestBrokerRuntimeError> {
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
        Err(QuestBrokerRuntimeError::BridgeModeMismatch)
    }
}

fn validate_bridge_kind(
    requested: &QuestBrokerAuthorityBridgeKind,
    live: &QuestBrokerAuthorityBridgeKind,
) -> Result<(), QuestBrokerRuntimeError> {
    if requested == live {
        Ok(())
    } else {
        Err(QuestBrokerRuntimeError::BridgeModeMismatch)
    }
}

fn schema(value: &str) -> SchemaId {
    SchemaId::new(value).expect("static schema")
}

/// Stateful runtime initialization, projection, or serialization failure.
#[derive(Debug)]
pub enum QuestBrokerRuntimeError {
    /// Config, operation, or server request schema mismatch.
    SchemaMismatch,
    /// Standalone/embedded placement mismatch.
    BridgeModeMismatch,
    /// Provider epoch derivation failed.
    InvalidProviderEpoch,
    /// Same-provider rebind supplied different immutable config.
    RebindConfigMismatch,
    /// Provider entrypoint was used before initialization.
    NotInitialized,
    /// Effect parameter wrapper schema is not supported.
    EffectParamsSchemaMismatch,
    /// Effect parameters name a different command than the Runtime Host request.
    EffectParamsCommandMismatch,
    /// Media lifecycle commands supplied unsupported app-defined parameters.
    EffectParamsShapeMismatch,
    /// Canonical effect parameters exceed the low-rate command bound.
    EffectParamsTooLarge,
    /// Runtime Host request digest differs from canonical effect parameters.
    EffectParamsDigestMismatch,
    /// Runtime Host receipts failed to preserve the reviewed parameter digest.
    EffectParamsReceiptMismatch,
    /// Exact packaged product, lock, or client bytes did not match their digest.
    PackagedDigestMismatch,
    /// Caller-supplied runtime config differs from the packaged config digest.
    PackagedConfigDigestMismatch,
    /// Packaged product spec and lock are stale, expanded, or inconsistent.
    PackagedProductLockMismatch,
    /// Packaged client lock is malformed or contains ambient adapter state.
    ClientLockInvalid,
    /// Generated Manifold grants differ from the exact product/client closure.
    GrantClosureMismatch,
    /// Media feature selection and packaged media binding differ.
    MediaSelectionMismatch,
    /// Accepted Manifold descriptor binding is malformed or digest-damaged.
    MediaDescriptorBinding(rusty_manifold_media_session::ManifoldMediaSessionBindingError),
    /// Manifold descriptor and Quest runtime spec do not bind exact references.
    MediaCrossBindingMismatch,
    /// Quest media runtime selected a provider feature absent from product lock.
    MediaProductFeatureMismatch,
    /// Packaged app feature-lock/lifecycle evidence does not select this exact client and binding.
    MediaLifecycleAuthorityMismatch,
    /// Media platform completion was used by a non-media product.
    MediaProductNotSelected,
    /// Quest media binding, prepare, or exact completion application failed.
    MediaRuntime(rusty_quest_media_stream::MediaStreamProductRuntimeError),
    /// Product/client/lease closure could not form a media peer Runtime Host.
    MediaPeerRuntimeConfig,
    /// Live Manifold peer Runtime Host rejected a media authority transition.
    MediaPeerRuntime(ManifoldPeerRuntimeHostError),
    /// Candidate broker, peer, and media states did not form one atomic transition.
    MediaPeerRuntimeTransitionRejected,
    /// Manifold adapter construction failed.
    Adapter(ManifoldBrokerAdapterError),
    /// Manifold admission snapshot failed validation.
    Admission(rusty_manifold_admission::ManifoldAdmissionError),
    /// Quest Binder projection failed before Manifold evaluation.
    AdmissionProjection(rusty_quest_broker_admission::QuestAdmissionError),
    /// JSON request decoding failed.
    Decode(serde_json::Error),
    /// JSON response encoding failed.
    Encode(serde_json::Error),
}

impl fmt::Display for QuestBrokerRuntimeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::SchemaMismatch => write!(formatter, "Quest broker runtime schema mismatch"),
            Self::BridgeModeMismatch => write!(formatter, "Quest broker runtime mode mismatch"),
            Self::InvalidProviderEpoch => write!(formatter, "provider epoch derivation failed"),
            Self::RebindConfigMismatch => {
                write!(formatter, "same-provider rebind config mismatch")
            }
            Self::NotInitialized => write!(formatter, "broker runtime provider not initialized"),
            Self::EffectParamsSchemaMismatch => {
                write!(formatter, "broker effect parameter schema mismatch")
            }
            Self::EffectParamsCommandMismatch => {
                write!(
                    formatter,
                    "broker effect parameters target a different command"
                )
            }
            Self::EffectParamsShapeMismatch => {
                write!(formatter, "media lifecycle effect parameters must be empty")
            }
            Self::EffectParamsTooLarge => write!(formatter, "broker effect parameters too large"),
            Self::EffectParamsDigestMismatch => {
                write!(formatter, "broker effect parameter digest mismatch")
            }
            Self::EffectParamsReceiptMismatch => {
                write!(
                    formatter,
                    "Runtime Host did not preserve effect parameter digest"
                )
            }
            Self::PackagedDigestMismatch => write!(formatter, "packaged authority digest mismatch"),
            Self::PackagedConfigDigestMismatch => {
                write!(formatter, "packaged runtime config digest mismatch")
            }
            Self::PackagedProductLockMismatch => {
                write!(formatter, "packaged product spec/lock mismatch")
            }
            Self::ClientLockInvalid => write!(formatter, "packaged broker client lock invalid"),
            Self::GrantClosureMismatch => {
                write!(formatter, "generated admission grant closure mismatch")
            }
            Self::MediaSelectionMismatch => {
                write!(
                    formatter,
                    "media feature and packaged binding selection mismatch"
                )
            }
            Self::MediaDescriptorBinding(error) => {
                write!(
                    formatter,
                    "Manifold media descriptor binding failed: {error}"
                )
            }
            Self::MediaCrossBindingMismatch => {
                write!(formatter, "Manifold and Quest media bindings disagree")
            }
            Self::MediaProductFeatureMismatch => {
                write!(
                    formatter,
                    "Quest media runtime exceeds broker product feature lock"
                )
            }
            Self::MediaLifecycleAuthorityMismatch => {
                write!(formatter, "packaged media app lifecycle authority mismatch")
            }
            Self::MediaProductNotSelected => {
                write!(formatter, "broker product did not select media session")
            }
            Self::MediaRuntime(error) => write!(formatter, "Quest media runtime failed: {error}"),
            Self::MediaPeerRuntimeConfig => {
                write!(formatter, "media peer Runtime Host config closure invalid")
            }
            Self::MediaPeerRuntime(error) => {
                write!(formatter, "media peer Runtime Host failed: {error}")
            }
            Self::MediaPeerRuntimeTransitionRejected => {
                write!(formatter, "media authority candidate transition rejected")
            }
            Self::Adapter(error) => write!(formatter, "Manifold adapter failed: {error}"),
            Self::Admission(error) => write!(formatter, "Manifold admission failed: {error}"),
            Self::AdmissionProjection(error) => {
                write!(formatter, "Quest admission projection failed: {error}")
            }
            Self::Decode(error) => write!(formatter, "runtime request decode failed: {error}"),
            Self::Encode(error) => write!(formatter, "runtime response encode failed: {error}"),
        }
    }
}

impl std::error::Error for QuestBrokerRuntimeError {}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_manifold_admission::{
        ManifoldAdmissionGrant, ManifoldAdmissionSnapshot, ManifoldClientIdentity,
        ADMISSION_SNAPSHOT_SCHEMA,
    };
    use rusty_manifold_broker_adapter::{
        command_capability, ManifoldBrokerMutationRejectionReason, BROKER_ADAPTER_CONFIG_SCHEMA,
    };
    use rusty_manifold_broker_product::{
        resolve_broker_product, ManifoldBrokerFeature, ManifoldBrokerProductSpec,
        BROKER_PRODUCT_SPEC_SCHEMA,
    };
    use rusty_manifold_runtime_host::{
        ManifoldRuntimeRejectionReason, HOST_COMMAND_REQUEST_SCHEMA,
    };
    use rusty_quest_broker_admission::{
        QuestAndroidBinderCaller, QUEST_ADMISSION_OPERATION_SCHEMA,
    };

    fn id(value: &str) -> DottedId {
        DottedId::new(value).expect("id")
    }

    fn identity() -> ManifoldClientIdentity {
        ManifoldClientIdentity {
            client_id: id("client.quest.runtime"),
            platform_subject: "example.quest.runtime".to_owned(),
            signing_fingerprint: format!("sha256:{}", "a1".repeat(32)),
        }
    }

    fn caller() -> QuestAndroidBinderCaller {
        QuestAndroidBinderCaller {
            sending_uid: 10_123,
            package_name: identity().platform_subject,
            signing_certificate_sha256: "a1".repeat(32),
        }
    }

    fn test_media_binding() -> QuestBrokerMediaSessionProductBinding {
        use rusty_manifold_media_session::{
            canonical_media_session_sha256, ManifoldMediaSessionProductBinding,
            MANIFOLD_MEDIA_SESSION_BINDING_SCHEMA,
        };
        use rusty_manifold_model::{ManifoldMediaSessionDescriptor, MANIFOLD_MEDIA_SESSION_SCHEMA};
        use rusty_quest_media_stream as media;

        let mut plan: media::MediaStreamSessionPlan = serde_json::from_str(include_str!(
            "../../../fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json"
        ))
        .expect("media plan");
        plan.transport_routes[0].connect_host = "192.168.49.2".to_owned();
        let lane_id = "quest-a-display-to-pc-host";
        let spec = media::MediaStreamRuntimeSpec {
            schema: media::MEDIA_STREAM_RUNTIME_SPEC_SCHEMA.to_owned(),
            runtime_spec_id: "runtime.media.display-example".to_owned(),
            manifold_session_revision: 4,
            plan,
            processors: vec![media::MediaStreamProcessorDescriptor {
                processor_id: "processor.display.passthrough".to_owned(),
                processor_kind: "passthrough_h264".to_owned(),
                input_track_roles: vec!["display".to_owned()],
                output_track_roles: vec!["display".to_owned()],
                owns_codec: false,
                cpu_pixel_copy: false,
                application_policy_fields: Vec::new(),
            }],
            sinks: vec![media::MediaStreamSinkDescriptor {
                sink_id: "sink.pc.display".to_owned(),
                device_id: "pc-host".to_owned(),
                sink_kind: "hostess_h264_receiver".to_owned(),
                required_permissions: Vec::new(),
                application_policy_fields: Vec::new(),
            }],
            lane_bindings: vec![media::MediaStreamLaneRuntimeBinding {
                lane_id: lane_id.to_owned(),
                processor_ids: vec!["processor.display.passthrough".to_owned()],
                sink_id: "sink.pc.display".to_owned(),
            }],
            direct_p2p_routes: Vec::new(),
            owner_selections: vec![
                media::MediaStreamOwnerSelection {
                    owner_kind: media::MediaStreamOwnerKind::Source,
                    owner_id: "owner.quest.display-capture".to_owned(),
                    resource_id: "quest-a-display-composite".to_owned(),
                    lane_id: Some(lane_id.to_owned()),
                    provider_kind: "android_mediaprojection_surface".to_owned(),
                },
                media::MediaStreamOwnerSelection {
                    owner_kind: media::MediaStreamOwnerKind::Processor,
                    owner_id: "owner.quest.display-passthrough".to_owned(),
                    resource_id: "processor.display.passthrough".to_owned(),
                    lane_id: Some(lane_id.to_owned()),
                    provider_kind: "rust_passthrough_h264".to_owned(),
                },
                media::MediaStreamOwnerSelection {
                    owner_kind: media::MediaStreamOwnerKind::Route,
                    owner_id: "owner.manifold.route".to_owned(),
                    resource_id: lane_id.to_owned(),
                    lane_id: Some(lane_id.to_owned()),
                    provider_kind: "manifold_accepted_route".to_owned(),
                },
                media::MediaStreamOwnerSelection {
                    owner_kind: media::MediaStreamOwnerKind::Socket,
                    owner_id: "owner.rust.lan-tcp-socket".to_owned(),
                    resource_id: lane_id.to_owned(),
                    lane_id: Some(lane_id.to_owned()),
                    provider_kind: "rust_lan_tcp_socket".to_owned(),
                },
                media::MediaStreamOwnerSelection {
                    owner_kind: media::MediaStreamOwnerKind::Codec,
                    owner_id: "owner.android.h264-codec".to_owned(),
                    resource_id: lane_id.to_owned(),
                    lane_id: Some(lane_id.to_owned()),
                    provider_kind: "android_mediacodec_h264".to_owned(),
                },
                media::MediaStreamOwnerSelection {
                    owner_kind: media::MediaStreamOwnerKind::Sink,
                    owner_id: "owner.hostess.h264-sink".to_owned(),
                    resource_id: "sink.pc.display".to_owned(),
                    lane_id: Some(lane_id.to_owned()),
                    provider_kind: "hostess_h264_receiver".to_owned(),
                },
                media::MediaStreamOwnerSelection {
                    owner_kind: media::MediaStreamOwnerKind::Cleanup,
                    owner_id: "owner.quest.media-cleanup".to_owned(),
                    resource_id: "runtime.media.display-example".to_owned(),
                    lane_id: None,
                    provider_kind: "quest_media_cleanup".to_owned(),
                },
            ],
            compatibility_adapter_id: None,
        };
        let descriptor = ManifoldMediaSessionDescriptor {
            schema_id: schema(MANIFOLD_MEDIA_SESSION_SCHEMA),
            session_id: id("session.media_stream.quest_display_composite_to_pc"),
            authority_revision: Revision::new(4).expect("revision"),
            platform_runtime_spec_id: id("runtime.media.display-example"),
            source_ids: vec![id("quest-a-display-composite")],
            processor_ids: vec![id("processor.display.passthrough")],
            route_ids: vec![id(lane_id)],
            sink_ids: vec![id("sink.pc.display")],
            stream_ids: vec![id("quest-a.display.h264")],
            payload_plane: "binary-media".to_owned(),
            inline_media_payloads_allowed: false,
            remote_camera_compatibility: false,
        };
        QuestBrokerMediaSessionProductBinding {
            manifold: ManifoldMediaSessionProductBinding {
                schema_id: MANIFOLD_MEDIA_SESSION_BINDING_SCHEMA.to_owned(),
                descriptor_canonical_sha256: canonical_media_session_sha256(&descriptor)
                    .expect("descriptor digest"),
                descriptor,
            },
            quest: media::MediaStreamRuntimeProductBinding {
                schema_id: media::MEDIA_STREAM_RUNTIME_PRODUCT_BINDING_SCHEMA.to_owned(),
                runtime_spec_canonical_sha256: media::canonical_media_stream_runtime_sha256(&spec)
                    .expect("runtime digest"),
                spec,
            },
        }
    }

    fn config(
        kind: QuestBrokerAuthorityBridgeKind,
        features: Vec<ManifoldBrokerFeature>,
        command_id: &str,
        with_lease: bool,
    ) -> QuestBrokerRuntimeConfig {
        let mode = match kind {
            QuestBrokerAuthorityBridgeKind::StandaloneProcessJni => {
                ManifoldBrokerAdapterMode::Standalone
            }
            QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni => {
                ManifoldBrokerAdapterMode::Embedded
            }
        };
        let product_spec = ManifoldBrokerProductSpec {
            schema_id: schema(BROKER_PRODUCT_SPEC_SCHEMA),
            product_id: id(match mode {
                ManifoldBrokerAdapterMode::Standalone => "broker.runtime.standalone",
                ManifoldBrokerAdapterMode::Embedded => "broker.runtime.embedded",
            }),
            standalone_enabled: mode == ManifoldBrokerAdapterMode::Standalone,
            embedded_enabled: mode == ManifoldBrokerAdapterMode::Embedded,
            requested_features: features,
        };
        let lock = resolve_broker_product(&product_spec).expect("lock");
        let media_session = lock
            .features
            .contains(&ManifoldBrokerFeature::MediaSession)
            .then(test_media_binding);
        let lease = with_lease.then(|| ManifoldRuntimeLease {
            lease_id: id("lease.broker.media-session.quest.runtime"),
            scope: id("lease.media.session"),
            holder_id: identity().client_id,
            expires_at_ms: 60_000,
        });
        let product_spec_json = serde_json::to_string(&product_spec).expect("product spec");
        let product_lock_json = serde_json::to_string(&lock).expect("product lock");
        let media_selected = media_session.is_some();
        let client_capabilities = if media_selected {
            vec![
                "capability.command.media.session.start".to_owned(),
                "capability.command.media.session.stop".to_owned(),
                "capability.command.session.list".to_owned(),
                "capability.media.session.observe".to_owned(),
                "capability.peer.session.observe".to_owned(),
                "capability.sink.test".to_owned(),
            ]
        } else {
            vec![command_capability(&id(command_id)).as_str().to_owned()]
        };
        let client_lock = QuestBrokerClientLockSpec {
            schema: QUEST_BROKER_CLIENT_LOCK_SCHEMA.to_owned(),
            client_id: identity().client_id,
            package_name: identity().platform_subject,
            feature_lock_id: id("lock.broker-client.quest-runtime.v1"),
            marker_namespace: "RUSTY_QUEST_RUNTIME_TEST".to_owned(),
            contract_families: vec![
                id("rusty.manifold.media.session_descriptor.v1"),
                id("rusty.manifold.peer.session_descriptor.v1"),
            ],
            capabilities: client_capabilities
                .into_iter()
                .map(|capability| id(&capability))
                .collect(),
            adapter_permissions: vec![QUEST_BROKER_ADMISSION_PERMISSION.to_owned()],
            runtime_properties: Vec::new(),
            application_defaults: Vec::new(),
        };
        let grant_capabilities = if media_selected {
            derive_grant_capabilities(&lock, &client_lock)
        } else {
            vec![command_capability(&id(command_id))]
        };
        let client_lock_json = serde_json::to_string(&client_lock).expect("client lock");
        let client_lock_sha256 = sha256_hex(client_lock_json.as_bytes());
        let media_lifecycle_authority = media_session.as_ref().map(|binding| {
            let app_feature_lock_json = serde_json::json!({
                "$schema": "https://github.com/MesmerPrism/rusty-morphospace-work-environment/schemas/feature-lock.schema.json",
                "schema": "rusty.morphospace.workflow.feature_lock.v1",
                "project_id": "quest-runtime",
                "revision": 1,
                "default_activation": "disabled",
                "features": [
                    {
                        "feature_id": "quest-runtime-shell",
                        "module_id": "quest-runtime-shell",
                        "enabled": true,
                        "requested_by": "iteration-unit:test-media",
                        "descriptor": "morphospace/project.spec.json#quest-runtime-shell",
                        "dependencies": [],
                        "conflicts": [],
                        "permissions": [],
                        "routes": ["quest-runtime"],
                        "assets": [],
                        "parameter_authorities": [
                            {"parameter": "app.composition", "owner": "quest-runtime-app"}
                        ],
                        "activation_receipt": {
                            "required": true,
                            "schema": "rusty.quest.runtime.activation_receipt.v1",
                            "effective_marker": "rusty.quest.runtime.shell.effective"
                        }
                    },
                    {
                        "feature_id": "broker-media-client",
                        "module_id": "broker-media-client",
                        "enabled": true,
                        "requested_by": "iteration-unit:test-media",
                        "descriptor": "morphospace/project.spec.json#broker-media-client",
                        "dependencies": ["quest-runtime-shell"],
                        "conflicts": [],
                        "permissions": [QUEST_BROKER_ADMISSION_PERMISSION],
                        "routes": ["manifold-media-session", "test-render-sink"],
                        "assets": [],
                        "parameter_authorities": [
                            {"parameter": "stream.session", "owner": "manifold"},
                            {"parameter": "render.adoption", "owner": "quest-runtime-app"}
                        ],
                        "activation_receipt": {
                            "required": true,
                            "schema": "rusty.quest.broker_media_lifecycle_receipt.v1",
                            "effective_marker": "rusty.quest.runtime.broker_media_client.effective"
                        }
                    }
                ]
            })
            .to_string();
            let app_feature_lock_sha256 = sha256_hex(app_feature_lock_json.as_bytes());
            let lifecycle_json = serde_json::json!({
                "$schema": "rusty.quest.broker_media_lifecycle_lock.v2",
                "client_id": identity().client_id,
                "package_name": identity().platform_subject,
                "broker_client_lock_id": "lock.broker-client.quest-runtime.v1",
                "marker_namespace": "RUSTY_QUEST_RUNTIME_TEST",
                "project_id": "quest-runtime",
                "product_ids": [lock.product_id],
                "app_feature_lock_id": "lock.app.quest-runtime.broker-media-client.v1",
                "app_feature_lock_path": "apps/quest-runtime/morphospace/conformance-locks/broker-media-client.feature.lock.json",
                "app_feature_lock_fingerprint": format!("sha256:{app_feature_lock_sha256}"),
                "app_feature_lock_sha256": format!("sha256:{app_feature_lock_sha256}"),
                "app_feature_lock_revision": 1,
                "activation_effective_marker": "rusty.quest.runtime.broker_media_client.effective",
                "media_binding_path": "fixtures/media-runtime-products/quest-runtime.binding.json",
                "broker_runtime_lease_id": "lease.broker.media-session.quest.runtime",
                "media_runtime_lease_id": "lease.media.session.quest.runtime",
                "session_id": binding.manifold.descriptor.session_id,
                "stream_id": binding.manifold.descriptor.stream_ids[0],
                "render_sink_id": binding.manifold.descriptor.sink_ids[0],
                "render_sink_capability": "capability.sink.test",
                "runtime_spec_id": binding.manifold.descriptor.platform_runtime_spec_id,
                "runtime_spec_canonical_sha256": binding.quest.runtime_spec_canonical_sha256,
                "manifold_descriptor_canonical_sha256": binding.manifold.descriptor_canonical_sha256
            })
            .to_string();
            let media_binding_json = serde_json::to_string(binding).expect("media binding");
            QuestBrokerPackagedMediaLifecycleAuthority {
                media_lifecycle_lock_sha256: sha256_hex(lifecycle_json.as_bytes()),
                media_lifecycle_lock_json: lifecycle_json,
                app_feature_lock_sha256,
                app_feature_lock_json,
                media_binding_sha256: sha256_hex(media_binding_json.as_bytes()),
                media_binding_json,
            }
        });
        QuestBrokerRuntimeConfig {
            schema_id: QUEST_BROKER_RUNTIME_CONFIG_SCHEMA.to_owned(),
            bridge_kind: kind,
            adapter_config: ManifoldBrokerAdapterConfig {
                schema_id: schema(BROKER_ADAPTER_CONFIG_SCHEMA),
                adapter_id: id(match mode {
                    ManifoldBrokerAdapterMode::Standalone => "adapter.runtime.standalone",
                    ManifoldBrokerAdapterMode::Embedded => "adapter.runtime.embedded",
                }),
                mode,
                product_lock_id: lock.lock_id.clone(),
                product_lock_fingerprint: lock.spec_fingerprint.clone(),
                product_lock_sha256: format!("sha256:{}", sha256_hex(product_lock_json.as_bytes())),
                authority_host_id: id("host.quest.runtime"),
                authority_owner_id: id(RUNTIME_HOST_AUTHORITY_OWNER),
            },
            product_lock: lock,
            packaged_authority: QuestBrokerPackagedAuthorityBinding {
                product_spec_sha256: sha256_hex(product_spec_json.as_bytes()),
                product_spec_json,
                product_lock_sha256: sha256_hex(product_lock_json.as_bytes()),
                product_lock_json,
                client_locks: vec![QuestBrokerPackagedClientLock {
                    grant_id: id("grant.quest.runtime"),
                    client_lock_sha256: client_lock_sha256.clone(),
                    client_lock_json,
                    media_lifecycle_authority,
                }],
            },
            initial_leases: lease.into_iter().collect(),
            admission: QuestBrokerAdmissionConfig {
                schema_id: QUEST_ADMISSION_CONFIG_SCHEMA.to_owned(),
                snapshot: ManifoldAdmissionSnapshot {
                    schema_id: schema(ADMISSION_SNAPSHOT_SCHEMA),
                    authority_id: id("authority.admission.quest.runtime"),
                    authority_revision: Revision::new(1).expect("revision"),
                    grants: vec![ManifoldAdmissionGrant {
                        grant_id: id("grant.quest.runtime"),
                        client_lock_id: client_lock.feature_lock_id.clone(),
                        client_lock_fingerprint: format!("sha256:{}", client_lock_sha256),
                        identity: identity(),
                        capabilities: grant_capabilities,
                        expires_at_ms: 100_000,
                        revoked: false,
                    }],
                    active_tokens: Vec::new(),
                    revoked_token_ids: Vec::new(),
                    consumed_request_ids: Vec::new(),
                    consumed_use_request_ids: Vec::new(),
                    reviewed_sweep_ids: Vec::new(),
                    audit_events: Vec::new(),
                    max_token_ttl_ms: 30_000,
                },
            },
            media_session,
            media_sessions: Vec::new(),
        }
    }

    fn admit(runtime: &mut QuestBrokerAuthorityRuntime, command_id: &str) -> (DottedId, DottedId) {
        let issue = runtime
            .execute_admission(QuestBrokerAdmissionOperation::IssueToken {
                schema_id: QUEST_ADMISSION_OPERATION_SCHEMA.to_owned(),
                caller: caller(),
                request_id: id("request.quest.runtime.issue"),
                expected_authority_revision: Revision::new(1).expect("revision"),
                requested_capabilities: vec![command_capability(&id(command_id))],
                requested_token_ttl_ms: 20_000,
                issued_at_ms: 2_000,
                expires_at_ms: 10_000,
                entropy_hex: "07".repeat(32),
            })
            .expect("issue");
        let token = issue.receipt.token.expect("token");
        let use_id = id("request.quest.runtime.use");
        let use_ = runtime
            .execute_admission(QuestBrokerAdmissionOperation::AuthorizeUse {
                schema_id: QUEST_ADMISSION_OPERATION_SCHEMA.to_owned(),
                caller: caller(),
                request_id: use_id.clone(),
                expected_authority_revision: Revision::new(2).expect("revision"),
                token_id: token.token_id.clone(),
                capability_id: command_capability(&id(command_id)),
                issued_at_ms: 3_000,
                expires_at_ms: 9_000,
            })
            .expect("use");
        assert!(use_.receipt.applied);
        (use_id, token.token_id)
    }

    fn mutation(
        runtime: &QuestBrokerAuthorityRuntime,
        kind: QuestBrokerAuthorityBridgeKind,
        use_id: DottedId,
        token_id: DottedId,
        command_id: &str,
        lease_id: Option<&str>,
    ) -> QuestBrokerServerMutationRequest {
        let params = QuestBrokerEffectParams {
            schema_id: QUEST_BROKER_EFFECT_PARAMS_SCHEMA.to_owned(),
            command_id: id(command_id),
            values: BTreeMap::new(),
        };
        let params_digest = canonical_effect_params_digest(&params).expect("params digest");
        QuestBrokerServerMutationRequest {
            schema_id: QUEST_BROKER_SERVER_MUTATION_SCHEMA.to_owned(),
            bridge_kind: kind,
            provider_epoch_id: runtime.provider_epoch_id().clone(),
            admission_use_request_id: use_id,
            token_id,
            expected_admission_authority_revision: Revision::new(3).expect("revision"),
            command: ManifoldRuntimeCommandRequest {
                schema_id: schema(HOST_COMMAND_REQUEST_SCHEMA),
                request_id: id("request.quest.runtime.command"),
                expected_authority_revision: Revision::new(1).expect("revision"),
                requester_id: identity().client_id,
                command_id: id(command_id),
                lease_id: lease_id.map(id),
                params_digest: Some(params_digest),
                issued_at_ms: 3_000,
                expires_at_ms: 9_000,
            },
            params,
        }
    }

    fn runtime_for(
        kind: QuestBrokerAuthorityBridgeKind,
        features: Vec<ManifoldBrokerFeature>,
        command_id: &str,
        with_lease: bool,
        entropy_byte: &str,
    ) -> QuestBrokerAuthorityRuntime {
        QuestBrokerAuthorityRuntime::from_config(
            config(kind, features, command_id, with_lease),
            &entropy_byte.repeat(32),
        )
        .expect("runtime")
    }

    #[test]
    fn real_standalone_and_embedded_entrypoints_share_host_decisions() {
        let command_id = "command.media.session.start";
        let mut responses = Vec::new();
        for (kind, entropy) in [
            (QuestBrokerAuthorityBridgeKind::StandaloneProcessJni, "11"),
            (QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni, "22"),
        ] {
            let mut runtime = runtime_for(
                kind.clone(),
                vec![ManifoldBrokerFeature::MediaSession],
                command_id,
                true,
                entropy,
            );
            let (use_id, token_id) = admit(&mut runtime, command_id);
            let response = runtime
                .handle_server_mutation(
                    &mutation(
                        &runtime,
                        kind,
                        use_id,
                        token_id,
                        command_id,
                        Some("lease.broker.media-session.quest.runtime"),
                    ),
                    4_000,
                )
                .expect("response");
            assert!(response.accepted);
            assert!(!response.local_acceptance_rules);
            assert!(response.effect_params.is_some());
            assert!(!response.platform_effect_completed);
            assert!(response.platform_prepare_error.is_none());
            let action = response.platform_action.as_ref().expect("platform action");
            assert_eq!(action.operation, MediaStreamPlatformOperation::Start);
            assert_eq!(
                runtime.evidence().media_pending_action.as_ref(),
                Some(action)
            );
            responses.push(response);
        }
        let left = responses
            .remove(0)
            .mutation_receipt
            .adapter_receipt
            .expect("left");
        let right = responses
            .remove(0)
            .mutation_receipt
            .adapter_receipt
            .expect("right");
        assert_eq!(left.dispatch, right.dispatch);
        assert_eq!(left.application, right.application);
    }

    #[test]
    fn canonical_effect_params_bind_response_and_runtime_host_receipts() {
        let command_id = "command.session.list";
        let kind = QuestBrokerAuthorityBridgeKind::StandaloneProcessJni;
        let mut runtime = runtime_for(kind.clone(), Vec::new(), command_id, false, "23");
        let (use_id, token_id) = admit(&mut runtime, command_id);
        let mut request = mutation(&runtime, kind, use_id, token_id, command_id, None);
        request.params.values.insert(
            "marker".to_owned(),
            Value::String("receipt-bound".to_owned()),
        );
        request
            .params
            .values
            .insert("nested".to_owned(), serde_json::json!({"z": 2, "a": 1}));
        let digest = canonical_effect_params_digest(&request.params).expect("digest");
        request.command.params_digest = Some(digest.clone());
        let response = runtime
            .handle_server_mutation(&request, 4_000)
            .expect("response");
        assert!(response.accepted);
        assert_eq!(response.effect_params.as_ref(), Some(&request.params));
        let adapter = response
            .mutation_receipt
            .adapter_receipt
            .expect("adapter receipt");
        assert_eq!(adapter.dispatch.params_digest, Some(digest.clone()));
        assert_eq!(adapter.application.params_digest, Some(digest));
    }

    #[test]
    fn effect_params_tamper_oversize_and_object_order_are_deterministic() {
        let left: QuestBrokerEffectParams = serde_json::from_str(
            r#"{"$schema":"rusty.quest.broker.effect_params.v1","command_id":"command.session.list","values":{"z":2,"a":{"y":2,"x":1}}}"#,
        )
        .expect("left params");
        let right: QuestBrokerEffectParams = serde_json::from_str(
            r#"{"values":{"a":{"x":1,"y":2},"z":2},"command_id":"command.session.list","$schema":"rusty.quest.broker.effect_params.v1"}"#,
        )
        .expect("right params");
        assert_eq!(
            canonical_effect_params_digest(&left).expect("left digest"),
            canonical_effect_params_digest(&right).expect("right digest")
        );

        let command_id = "command.session.list";
        let kind = QuestBrokerAuthorityBridgeKind::StandaloneProcessJni;
        let mut runtime = runtime_for(kind.clone(), Vec::new(), command_id, false, "24");
        let (use_id, token_id) = admit(&mut runtime, command_id);
        let mut tampered = mutation(&runtime, kind, use_id, token_id, command_id, None);
        tampered
            .params
            .values
            .insert("tampered".to_owned(), Value::Bool(true));
        assert!(matches!(
            runtime.handle_server_mutation(&tampered, 4_000),
            Err(QuestBrokerRuntimeError::EffectParamsDigestMismatch)
        ));
        assert_eq!(
            runtime
                .evidence()
                .runtime
                .host_snapshot
                .authority_revision
                .get(),
            1
        );

        let mut oversized = left;
        oversized.command_id = id(command_id);
        oversized
            .values
            .insert("blob".to_owned(), Value::String("x".repeat(5_000)));
        assert!(matches!(
            canonical_effect_params_digest(&oversized),
            Err(QuestBrokerRuntimeError::EffectParamsTooLarge)
        ));
    }

    #[test]
    fn grant_closure_excludes_unselected_media_and_selects_peer_status_capabilities() {
        let client = QuestBrokerClientLockSpec {
            schema: QUEST_BROKER_CLIENT_LOCK_SCHEMA.to_owned(),
            client_id: id("client.quest.closure"),
            package_name: "io.github.mesmerprism.rustyquest.closure".to_owned(),
            feature_lock_id: id("lock.broker-client.closure.v1"),
            marker_namespace: "RUSTY_QUEST_CLOSURE_TEST".to_owned(),
            contract_families: vec![
                id("rusty.manifold.media.session_descriptor.v1"),
                id("rusty.manifold.peer.session_descriptor.v1"),
            ],
            capabilities: vec![
                id("capability.command.session.list"),
                id("capability.media.session.observe"),
                id("capability.peer.session.observe"),
                id("capability.sink.native-openxr"),
            ],
            adapter_permissions: vec![QUEST_BROKER_ADMISSION_PERMISSION.to_owned()],
            runtime_properties: Vec::new(),
            application_defaults: Vec::new(),
        };
        let base = resolve_broker_product(&ManifoldBrokerProductSpec {
            schema_id: schema(BROKER_PRODUCT_SPEC_SCHEMA),
            product_id: id("broker.closure.base"),
            standalone_enabled: true,
            embedded_enabled: false,
            requested_features: Vec::new(),
        })
        .expect("base lock");
        assert_eq!(
            derive_grant_capabilities(&base, &client),
            vec![
                id("capability.command.session.list"),
                id("capability.peer.session.observe"),
            ]
        );

        let camera = resolve_broker_product(&ManifoldBrokerProductSpec {
            schema_id: schema(BROKER_PRODUCT_SPEC_SCHEMA),
            product_id: id("broker.closure.camera"),
            standalone_enabled: false,
            embedded_enabled: true,
            requested_features: vec![ManifoldBrokerFeature::CameraMedia],
        })
        .expect("camera lock");
        assert_eq!(
            derive_grant_capabilities(&camera, &client),
            vec![
                id("capability.command.session.list"),
                id("capability.media.session.observe"),
                id("capability.peer.session.observe"),
                id("capability.sink.native-openxr"),
            ]
        );
    }

    #[test]
    fn media_runtime_cannot_exceed_camera_or_direct_p2p_product_features() {
        let kind = QuestBrokerAuthorityBridgeKind::StandaloneProcessJni;
        let command_id = "command.media.session.start";
        let mut camera_drift = config(
            kind.clone(),
            vec![ManifoldBrokerFeature::MediaSession],
            command_id,
            true,
        );
        camera_drift.media_session = Some(
            serde_json::from_str(include_str!(
                "../../../fixtures/media-runtime-products/camera2-surface.binding.json"
            ))
            .expect("camera binding"),
        );
        assert!(matches!(
            QuestBrokerAuthorityRuntime::from_config(camera_drift, &"51".repeat(32)),
            Err(QuestBrokerRuntimeError::MediaProductFeatureMismatch)
        ));

        let mut route_drift = config(
            kind,
            vec![ManifoldBrokerFeature::MediaSession],
            command_id,
            true,
        );
        let binding = route_drift.media_session.as_mut().expect("media binding");
        binding.quest.spec.plan.transport_routes[0].connect_host = "192.168.49.2".to_owned();
        binding.quest.spec.direct_p2p_routes =
            vec![rusty_quest_media_stream::MediaStreamDirectP2pRouteBinding {
                lane_id: "quest-a-display-to-pc-host".to_owned(),
                route: serde_json::from_str(include_str!(
                    "../../../fixtures/device-link/direct-p2p-socket-route.pass.json"
                ))
                .expect("direct route"),
            }];
        binding.quest.runtime_spec_canonical_sha256 =
            rusty_quest_media_stream::canonical_media_stream_runtime_sha256(&binding.quest.spec)
                .expect("drift digest");
        assert!(matches!(
            QuestBrokerAuthorityRuntime::from_config(route_drift, &"52".repeat(32)),
            Err(QuestBrokerRuntimeError::MediaProductFeatureMismatch)
        ));
    }

    #[test]
    fn unselected_grants_and_unleased_or_stale_host_work_reject() {
        for kind in [
            QuestBrokerAuthorityBridgeKind::StandaloneProcessJni,
            QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
        ] {
            for (features, command_id) in [
                (Vec::new(), "command.never.registered"),
                (Vec::new(), "command.media.session.start"),
            ] {
                assert!(matches!(
                    QuestBrokerAuthorityRuntime::from_config(
                        config(kind.clone(), features, command_id, false),
                        &"33".repeat(32),
                    ),
                    Err(QuestBrokerRuntimeError::GrantClosureMismatch)
                ));
            }

            let command_id = "command.media.session.start";
            assert!(matches!(
                QuestBrokerAuthorityRuntime::from_config(
                    config(
                        kind.clone(),
                        vec![ManifoldBrokerFeature::MediaSession],
                        command_id,
                        false,
                    ),
                    &"33".repeat(32),
                ),
                Err(QuestBrokerRuntimeError::MediaLifecycleAuthorityMismatch)
            ));

            let stale_command = "command.session.list";
            let mut stale_runtime =
                runtime_for(kind.clone(), Vec::new(), stale_command, false, "34");
            let (stale_use, stale_token) = admit(&mut stale_runtime, stale_command);
            let mut stale_request = mutation(
                &stale_runtime,
                kind.clone(),
                stale_use,
                stale_token,
                stale_command,
                None,
            );
            stale_request.command.expected_authority_revision = Revision::new(2).expect("revision");
            let stale_response = stale_runtime
                .handle_server_mutation(&stale_request, 4_000)
                .expect("stale response");
            assert!(!stale_response.accepted);
            assert_eq!(
                stale_response
                    .mutation_receipt
                    .adapter_receipt
                    .expect("host receipt")
                    .application
                    .rejection_reason,
                Some(ManifoldRuntimeRejectionReason::StaleAuthorityRevision)
            );
        }
    }

    #[test]
    fn admission_stale_replay_cross_client_and_expiry_reject_before_effect() {
        let kind = QuestBrokerAuthorityBridgeKind::StandaloneProcessJni;
        let command_id = "command.session.list";
        let mut runtime = runtime_for(kind.clone(), Vec::new(), command_id, false, "44");
        let (use_id, token_id) = admit(&mut runtime, command_id);
        let mut request = mutation(&runtime, kind, use_id, token_id, command_id, None);
        request.expected_admission_authority_revision = Revision::new(2).expect("revision");
        let stale = runtime
            .handle_server_mutation(&request, 4_000)
            .expect("stale");
        assert_eq!(
            stale.mutation_receipt.admission_rejection_reason,
            Some(ManifoldBrokerMutationRejectionReason::StaleAdmissionRevision)
        );
        request.expected_admission_authority_revision = Revision::new(3).expect("revision");
        let token_id = request.token_id.clone();
        request.token_id = id("token.session.substituted");
        let token_mismatch = runtime
            .handle_server_mutation(&request, 4_000)
            .expect("token mismatch");
        assert_eq!(
            token_mismatch.mutation_receipt.admission_rejection_reason,
            Some(ManifoldBrokerMutationRejectionReason::AdmissionTokenMismatch)
        );
        request.token_id = token_id;
        request.command.requester_id = id("client.quest.other");
        let cross = runtime
            .handle_server_mutation(&request, 4_000)
            .expect("cross");
        assert_eq!(
            cross.mutation_receipt.admission_rejection_reason,
            Some(ManifoldBrokerMutationRejectionReason::CrossClientUse)
        );
        request.command.requester_id = identity().client_id;
        assert!(
            runtime
                .handle_server_mutation(&request, 4_000)
                .expect("apply")
                .accepted
        );
        let replay = runtime
            .handle_server_mutation(&request, 4_000)
            .expect("replay");
        assert_eq!(
            replay.mutation_receipt.admission_rejection_reason,
            Some(ManifoldBrokerMutationRejectionReason::ReplayedAdmissionUse)
        );

        let mut expired_runtime = runtime_for(
            QuestBrokerAuthorityBridgeKind::StandaloneProcessJni,
            Vec::new(),
            command_id,
            false,
            "55",
        );
        let (expired_use, expired_token) = admit(&mut expired_runtime, command_id);
        let expired_request = mutation(
            &expired_runtime,
            QuestBrokerAuthorityBridgeKind::StandaloneProcessJni,
            expired_use,
            expired_token,
            command_id,
            None,
        );
        let expired = expired_runtime
            .handle_server_mutation(&expired_request, 10_000)
            .expect("expired");
        assert_eq!(
            expired.mutation_receipt.admission_rejection_reason,
            Some(ManifoldBrokerMutationRejectionReason::AdmissionUseExpired)
        );
        assert!(expired.platform_effect.is_none());
    }

    #[test]
    fn rebind_keeps_one_runtime_while_provider_restart_requires_fresh_epoch() {
        let kind = QuestBrokerAuthorityBridgeKind::StandaloneProcessJni;
        let command_id = "command.session.list";
        let mut runtime = runtime_for(kind.clone(), Vec::new(), command_id, false, "66");
        let original_epoch = runtime.provider_epoch_id().clone();
        let (use_id, token_id) = admit(&mut runtime, command_id);
        let request = mutation(&runtime, kind.clone(), use_id, token_id, command_id, None);
        assert!(
            runtime
                .handle_server_mutation(&request, 4_000)
                .expect("apply")
                .accepted
        );
        assert_eq!(
            runtime
                .evidence()
                .runtime
                .host_snapshot
                .authority_revision
                .get(),
            2
        );

        let mut restarted = runtime_for(kind, Vec::new(), command_id, false, "77");
        assert_ne!(restarted.provider_epoch_id(), &original_epoch);
        assert_eq!(
            restarted
                .evidence()
                .runtime
                .host_snapshot
                .authority_revision
                .get(),
            1
        );
        let old_epoch = restarted
            .handle_server_mutation(&request, 4_000)
            .expect("typed rejection");
        assert_eq!(
            old_epoch.mutation_receipt.admission_rejection_reason,
            Some(ManifoldBrokerMutationRejectionReason::ProviderEpochMismatch)
        );
    }
}
