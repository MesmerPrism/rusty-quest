//! Product binding and two-phase platform adoption for generic media sessions.
//!
//! Runtime Host acceptance authorizes an action but never proves that Android
//! performed it. This module binds one exact runtime spec to one exact Manifold
//! descriptor and advances lifecycle state only after every independently
//! selected owner returns a matching completion receipt.

use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Arc, RwLock},
};

use rusty_manifold_media_session::{
    ManifoldAcceptedMediaSession, ManifoldMediaSessionCurrentReceipt,
    ManifoldMediaSessionLifecycleStatus, MANIFOLD_ACCEPTED_MEDIA_SESSION_SCHEMA,
    MANIFOLD_MEDIA_SESSION_CURRENT_RECEIPT_SCHEMA,
};
use rusty_manifold_model::DottedId;
use rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHost;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{
    validate_media_stream_runtime_spec, MediaStreamRuntimeAction, MediaStreamRuntimeDecision,
    MediaStreamRuntimeEvidence, MediaStreamRuntimePhase, MediaStreamRuntimeRequest,
    MediaStreamRuntimeSpec, MediaStreamSessionRuntime, ValidationError,
};

/// Canonical packaged Quest runtime-spec binding schema.
pub const MEDIA_STREAM_RUNTIME_PRODUCT_BINDING_SCHEMA: &str =
    "rusty.quest.media_stream_runtime_product_binding.v1";
/// Receipt-bound platform action schema.
pub const MEDIA_STREAM_PLATFORM_ACTION_SCHEMA: &str = "rusty.quest.media_stream_platform_action.v1";
/// Android/platform owner completion schema.
pub const MEDIA_STREAM_PLATFORM_COMPLETION_SCHEMA: &str =
    "rusty.quest.media_stream_platform_completion.v1";
/// Rust-authored action application receipt schema.
pub const MEDIA_STREAM_PLATFORM_APPLICATION_SCHEMA: &str =
    "rusty.quest.media_stream_platform_application.v1";
/// Rust-authored partial-start rollback receipt schema.
pub const MEDIA_STREAM_PLATFORM_ABORT_SCHEMA: &str = "rusty.quest.media_stream_platform_abort.v1";
/// Only compatibility identity accepted for legacy remote-camera projections.
pub const REMOTE_CAMERA_COMPATIBILITY_ADAPTER: &str = "remote_camera_compatibility";

/// Durable owner families selected independently by a product.
#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaStreamOwnerKind {
    /// Capture or externally supplied source.
    Source,
    /// Source-neutral frame/layout processor.
    Processor,
    /// Transport route selection.
    Route,
    /// Socket provider and binding authority.
    Socket,
    /// Encoder/decoder provider.
    Codec,
    /// Receiver/sink provider.
    Sink,
    /// Terminal release and cleanup authority.
    Cleanup,
}

/// Exact resource-to-owner selection retained in a runtime spec.
#[derive(Clone, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MediaStreamOwnerSelection {
    /// Durable owner family.
    pub owner_kind: MediaStreamOwnerKind,
    /// Stable selected owner identity.
    pub owner_id: String,
    /// Exact source/processor/lane/sink/runtime resource identity.
    pub resource_id: String,
    /// Lane identity for lane-scoped owners.
    pub lane_id: Option<String>,
    /// Concrete provider family selected by the product.
    pub provider_kind: String,
}

/// One immutable runtime spec and its canonical typed digest.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MediaStreamRuntimeProductBinding {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact selected runtime spec.
    pub spec: MediaStreamRuntimeSpec,
    /// `sha256:<lowercase hex>` of canonical typed runtime-spec JSON.
    pub runtime_spec_canonical_sha256: String,
}

impl MediaStreamRuntimeProductBinding {
    /// Validates the spec and exact canonical digest.
    pub fn validate(&self) -> Result<(), MediaStreamProductRuntimeError> {
        if self.schema_id != MEDIA_STREAM_RUNTIME_PRODUCT_BINDING_SCHEMA {
            return Err(MediaStreamProductRuntimeError::BindingSchemaMismatch);
        }
        validate_media_stream_runtime_spec(&self.spec)
            .map_err(MediaStreamProductRuntimeError::RuntimeSpecInvalid)?;
        let expected = canonical_media_stream_runtime_sha256(&self.spec)?;
        if self.runtime_spec_canonical_sha256 != expected {
            return Err(MediaStreamProductRuntimeError::RuntimeSpecDigestMismatch);
        }
        Ok(())
    }
}

/// High-level platform operation authorized after Manifold command acceptance.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaStreamPlatformOperation {
    /// Arm receivers/cleanup and then start route, socket, codec, processor, source.
    Start,
    /// Stop all active owners and complete terminal cleanup last.
    Stop,
}

/// Owner-local action; ordering is part of receiver-first/cleanup evidence.
#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaStreamOwnerActionKind {
    /// Prepare a receiver before any source starts.
    ArmReceiver,
    /// Prepare terminal cleanup tracking before source startup.
    ArmCleanup,
    /// Start a selected non-sink owner.
    Start,
    /// Stop/release a selected non-cleanup owner.
    Stop,
    /// Prove terminal cleanup after every other owner stopped.
    Cleanup,
}

/// Exact admitted client and Runtime Host lease bound to a media action.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MediaStreamClientAuthorityBinding {
    /// Exact requester/client id from the applied Runtime Host command.
    pub client_id: String,
    /// Exact media-session lease consumed by that command.
    pub lease_id: String,
    /// Exact admitted broker product.
    pub product_id: String,
    /// Exact selected app feature lock.
    pub feature_lock_id: String,
    /// Exact selected feature-lock fingerprint.
    pub feature_lock_fingerprint: String,
    /// Capability that accepted the current media session.
    pub session_capability_id: String,
    /// Admission grant that accepted the current media session.
    pub session_admission_grant_id: String,
    /// Exact capability authorizing this start/stop action.
    pub operation_capability_id: String,
    /// Exact grant authorizing this start/stop action.
    pub operation_admission_grant_id: String,
    /// One-time bounded-use request consumed for this start/stop action.
    pub operation_admission_use_request_id: String,
}

/// One exact owner action emitted by Rust.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MediaStreamOwnerAction {
    /// Exact selected owner/resource/provider tuple.
    pub selection: MediaStreamOwnerSelection,
    /// Required owner-local transition.
    pub action_kind: MediaStreamOwnerActionKind,
}

/// Rust-authored action that platform code may execute, but not reinterpret.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MediaStreamPlatformAction {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Replay-protected action identity.
    pub action_id: String,
    /// Live provider epoch; restart invalidates old actions/completions.
    pub authority_epoch_id: String,
    /// Requested high-level lifecycle operation.
    pub operation: MediaStreamPlatformOperation,
    /// Exact admitted client and lease that authorized this action.
    pub client_authority: MediaStreamClientAuthorityBinding,
    /// Exact selected runtime spec identity.
    pub runtime_spec_id: String,
    /// Exact canonical Quest runtime-spec digest.
    pub runtime_spec_canonical_sha256: String,
    /// Exact canonical Manifold descriptor digest.
    pub manifold_descriptor_canonical_sha256: String,
    /// Exact accepted Manifold decision identity.
    pub manifold_decision_id: String,
    /// Exact accepted Manifold session revision.
    pub manifold_session_revision: u64,
    /// Current global media acceptance revision observed for this subject.
    pub media_acceptance_authority_revision: u64,
    /// Quest lifecycle revision reviewed during prepare.
    pub expected_runtime_revision: u64,
    /// Ordered independent owner actions.
    pub owner_actions: Vec<MediaStreamOwnerAction>,
}

/// Provider-state readback accepted only through the owner-specific Rust
/// callback matching the pending action. This is never an aggregate platform
/// completion and cannot itself advance lifecycle state.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct MediaStreamOwnerProviderReadback {
    /// Exact prepared action identity.
    pub action_id: String,
    /// Exact live provider epoch.
    pub authority_epoch_id: String,
    /// Current global media acceptance revision revalidated before execution.
    pub media_acceptance_authority_revision: u64,
    /// Exact admitted client whose owner adapter produced the readback.
    pub client_id: String,
    /// Exact current media-session lease held by that client.
    pub lease_id: String,
    /// Concrete provider family observed by its owner adapter.
    pub provider_kind: String,
    /// Exact selected resource observed by that provider.
    pub resource_id: String,
    /// Provider-owned non-empty live handle identity.
    pub provider_handle_id: String,
    /// Monotonic provider-local state revision.
    pub provider_state_revision: u64,
    /// Action-specific provider state read back after the operation.
    pub observed_state: String,
    /// Stable owner-issued receipt identity.
    pub receipt_id: String,
}

/// Trusted Rust/NDK owner implementation selected by the product runtime.
///
/// Implementations must execute the exact supplied action against their own
/// platform/native handle registry and verify the returned state from that
/// registry. This trait is intentionally not projected through JSON/JNI.
pub trait MediaStreamTrustedOwnerProvider {
    /// Durable owner family implemented by this provider.
    fn owner_kind(&self) -> MediaStreamOwnerKind;

    /// Executes one exact action and reads provider-owned state back.
    fn execute_and_readback(
        &mut self,
        action: &MediaStreamPlatformAction,
        owner_action: &MediaStreamOwnerAction,
    ) -> Result<MediaStreamOwnerProviderReadback, String>;

    /// Idempotently compensates an owner attempt whose call may have produced
    /// a side effect but did not yield a trustworthy readback. This path must
    /// query/clean the provider's own registry; absence is also returned as a
    /// positive stopped/cleaned readback, never inferred by the caller.
    fn compensate_uncertain_attempt(
        &mut self,
        abort_action: &MediaStreamPlatformAction,
        owner_action: &MediaStreamOwnerAction,
    ) -> Result<MediaStreamOwnerProviderReadback, String>;

    /// Independently rechecks the returned handle/state against provider state.
    fn verify_readback(
        &self,
        action: &MediaStreamPlatformAction,
        owner_action: &MediaStreamOwnerAction,
        readback: &MediaStreamOwnerProviderReadback,
    ) -> bool;
}

/// One exact Rust-validated provider completion for one owner action.
///
/// This type is serialization-only evidence. Callers cannot deserialize an
/// aggregate array and feed it back as authority.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct MediaStreamOwnerCompletionReceipt {
    /// Exact selected owner/resource/provider tuple.
    pub selection: MediaStreamOwnerSelection,
    /// Exact action that completed.
    pub action_kind: MediaStreamOwnerActionKind,
    /// Stable provider-authored receipt identity.
    pub receipt_id: String,
    /// Monotonic order within this platform completion.
    pub completion_sequence: u32,
    /// Provider-owned live handle used for state readback.
    pub provider_handle_id: String,
    /// Monotonic provider-local state revision.
    pub provider_state_revision: u64,
    /// Action-specific provider state observed after the operation.
    pub observed_state: String,
}

/// Rust-aggregated completion evidence after all owner-specific callbacks.
///
/// This type is serialization-only and is never accepted from Java/JNI JSON.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct MediaStreamPlatformCompletion {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact prepared action identity.
    pub action_id: String,
    /// Exact live provider epoch.
    pub authority_epoch_id: String,
    /// Exact high-level operation.
    pub operation: MediaStreamPlatformOperation,
    /// Exact client and lease copied from the prepared action.
    pub client_authority: MediaStreamClientAuthorityBinding,
    /// Exact runtime spec identity.
    pub runtime_spec_id: String,
    /// Exact canonical Quest runtime-spec digest.
    pub runtime_spec_canonical_sha256: String,
    /// Exact canonical Manifold descriptor digest.
    pub manifold_descriptor_canonical_sha256: String,
    /// Exact owner completions.
    pub owner_receipts: Vec<MediaStreamOwnerCompletionReceipt>,
}

/// Rust-authored proof that exact platform completion advanced lifecycle state.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MediaStreamPlatformApplicationReceipt {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Applied action identity.
    pub action_id: String,
    /// Exact live provider epoch.
    pub authority_epoch_id: String,
    /// Current media acceptance revision observed before owner execution.
    pub media_acceptance_authority_revision: u64,
    /// Platform effect completed only after all owner receipts applied.
    pub platform_effect_completed: bool,
    /// Resulting Quest lifecycle revision.
    pub resulting_runtime_revision: u64,
    /// Resulting Quest lifecycle phase.
    pub resulting_phase: MediaStreamRuntimePhase,
    /// Rust lifecycle decisions used to apply the owner completion.
    pub lifecycle_decisions: Vec<MediaStreamRuntimeDecision>,
    /// Exact owner receipt identities retained for audit.
    pub owner_receipt_ids: Vec<String>,
}

/// Rust-authored proof that every observed partial-start side effect was
/// reversed before the pending action was released.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct MediaStreamPlatformAbortReceipt {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Original start action whose partial effects were reversed.
    pub action_id: String,
    /// Derived rollback action identity used by every owner callback.
    pub abort_action_id: String,
    /// Live provider epoch retained by the original action.
    pub authority_epoch_id: String,
    /// Exact client/lease provenance retained by the original action.
    pub client_authority: MediaStreamClientAuthorityBinding,
    /// Rollback receipts in exact reverse completion order.
    pub rollback_receipts: Vec<MediaStreamOwnerCompletionReceipt>,
    /// Number of attempted owners cleaned without trusting their failed or
    /// damaged original readback.
    pub uncertain_attempts_compensated: u32,
    /// True only when every observed partial-start owner was reversed.
    pub cleanup_complete: bool,
    /// A partial start is never reported as a completed platform effect.
    pub platform_effect_completed: bool,
    /// Runtime phase remains unchanged because start was never applied.
    pub resulting_phase: MediaStreamRuntimePhase,
    /// Runtime revision remains unchanged because start was never applied.
    pub resulting_runtime_revision: u64,
}

/// Stateful packaged media adoption authority.
pub struct MediaStreamSessionProductRuntime {
    binding: MediaStreamRuntimeProductBinding,
    current_acceptance: ManifoldMediaSessionCurrentReceipt,
    authority: MediaStreamAuthoritySource,
    authority_epoch_id: String,
    runtime: MediaStreamSessionRuntime,
    pending_action: Option<MediaStreamPlatformAction>,
    pending_owner_receipts: Vec<MediaStreamOwnerCompletionReceipt>,
    pending_uncertain_owner: Option<MediaStreamOwnerAction>,
    pending_abort_receipts: Vec<MediaStreamOwnerCompletionReceipt>,
    abort_in_progress: bool,
    active_provider_handles: BTreeMap<MediaStreamOwnerSelection, (String, u64)>,
    active_client_authority: Option<MediaStreamClientAuthorityBinding>,
    applied_action_ids: BTreeSet<String>,
    aborted_action_ids: BTreeSet<String>,
}

enum MediaStreamAuthoritySource {
    Live {
        host: Arc<RwLock<ManifoldPeerRuntimeHost>>,
        decision_id: DottedId,
    },
    #[cfg(any(test, feature = "test-support"))]
    Test {
        current: Arc<RwLock<ManifoldMediaSessionCurrentReceipt>>,
    },
}

impl MediaStreamSessionProductRuntime {
    /// Creates one runtime bound to a live in-process Manifold peer Runtime Host.
    pub fn new(
        binding: MediaStreamRuntimeProductBinding,
        authority: Arc<RwLock<ManifoldPeerRuntimeHost>>,
        decision_id: DottedId,
        now_ms: u64,
    ) -> Result<Self, MediaStreamProductRuntimeError> {
        binding.validate()?;
        let (current_acceptance, authority_epoch_id) = {
            let host = authority
                .read()
                .map_err(|_| MediaStreamProductRuntimeError::AuthorityProviderUnavailable)?;
            let current = host.validate_media_session(&decision_id, now_ms);
            let epoch: String = host.snapshot().provider_epoch_id.as_str().to_owned();
            (current, epoch)
        };
        validate_current_acceptance(
            &binding,
            &current_acceptance,
            &authority_epoch_id,
            now_ms,
            true,
            None,
        )?;
        let runtime = MediaStreamSessionRuntime::new(binding.spec.clone())
            .map_err(MediaStreamProductRuntimeError::RuntimeSpecInvalid)?;
        Ok(Self {
            binding,
            current_acceptance,
            authority: MediaStreamAuthoritySource::Live {
                host: authority,
                decision_id,
            },
            authority_epoch_id,
            runtime,
            pending_action: None,
            pending_owner_receipts: Vec::new(),
            pending_uncertain_owner: None,
            pending_abort_receipts: Vec::new(),
            abort_in_progress: false,
            active_provider_handles: BTreeMap::new(),
            active_client_authority: None,
            applied_action_ids: BTreeSet::new(),
            aborted_action_ids: BTreeSet::new(),
        })
    }

    /// Constructs a receipt-backed runtime only for unit/damaged tests.
    #[cfg(any(test, feature = "test-support"))]
    pub fn new_for_test(
        binding: MediaStreamRuntimeProductBinding,
        current_acceptance: ManifoldMediaSessionCurrentReceipt,
        live_provider_epoch_id: String,
    ) -> Result<Self, MediaStreamProductRuntimeError> {
        binding.validate()?;
        validate_current_acceptance(
            &binding,
            &current_acceptance,
            &live_provider_epoch_id,
            current_acceptance.validated_at_ms,
            true,
            None,
        )?;
        let runtime = MediaStreamSessionRuntime::new(binding.spec.clone())
            .map_err(MediaStreamProductRuntimeError::RuntimeSpecInvalid)?;
        Ok(Self {
            binding,
            authority: MediaStreamAuthoritySource::Test {
                current: Arc::new(RwLock::new(current_acceptance.clone())),
            },
            current_acceptance,
            authority_epoch_id: live_provider_epoch_id,
            runtime,
            pending_action: None,
            pending_owner_receipts: Vec::new(),
            pending_uncertain_owner: None,
            pending_abort_receipts: Vec::new(),
            abort_in_progress: false,
            active_provider_handles: BTreeMap::new(),
            active_client_authority: None,
            applied_action_ids: BTreeSet::new(),
            aborted_action_ids: BTreeSet::new(),
        })
    }

    /// Returns the immutable packaged runtime binding.
    #[must_use]
    pub const fn binding(&self) -> &MediaStreamRuntimeProductBinding {
        &self.binding
    }

    /// Returns current Quest lifecycle state.
    #[must_use]
    pub const fn runtime(&self) -> &MediaStreamSessionRuntime {
        &self.runtime
    }

    /// Returns the most recently revalidated subject-current Manifold receipt.
    #[must_use]
    pub const fn current_acceptance(&self) -> &ManifoldMediaSessionCurrentReceipt {
        &self.current_acceptance
    }

    /// Atomically prepares against a candidate live Manifold authority. The
    /// original lifecycle remains untouched when candidate validation fails;
    /// no clonable/forked effect authority is exposed to callers.
    pub fn prepare_with_live_authority(
        &mut self,
        authority: Arc<RwLock<ManifoldPeerRuntimeHost>>,
        action_id: String,
        operation: MediaStreamPlatformOperation,
        client_authority: MediaStreamClientAuthorityBinding,
        now_ms: u64,
    ) -> Result<MediaStreamPlatformAction, MediaStreamProductRuntimeError> {
        let decision_id = match &self.authority {
            MediaStreamAuthoritySource::Live { decision_id, .. } => decision_id.clone(),
            #[cfg(any(test, feature = "test-support"))]
            MediaStreamAuthoritySource::Test { .. } => {
                return Err(MediaStreamProductRuntimeError::AuthorityProviderUnavailable)
            }
        };
        let mut candidate = Self {
            binding: self.binding.clone(),
            current_acceptance: self.current_acceptance.clone(),
            authority: MediaStreamAuthoritySource::Live {
                host: authority,
                decision_id,
            },
            authority_epoch_id: self.authority_epoch_id.clone(),
            runtime: self.runtime.clone(),
            pending_action: self.pending_action.clone(),
            pending_owner_receipts: self.pending_owner_receipts.clone(),
            pending_uncertain_owner: self.pending_uncertain_owner.clone(),
            pending_abort_receipts: self.pending_abort_receipts.clone(),
            abort_in_progress: self.abort_in_progress,
            active_provider_handles: self.active_provider_handles.clone(),
            active_client_authority: self.active_client_authority.clone(),
            applied_action_ids: self.applied_action_ids.clone(),
            aborted_action_ids: self.aborted_action_ids.clone(),
        };
        let action = candidate.prepare(action_id, operation, client_authority, now_ms)?;
        *self = candidate;
        Ok(action)
    }

    fn refresh_authority(
        &mut self,
        now_ms: u64,
        require_current: bool,
    ) -> Result<(), MediaStreamProductRuntimeError> {
        let current = match &self.authority {
            MediaStreamAuthoritySource::Live { host, decision_id } => {
                let host = host
                    .read()
                    .map_err(|_| MediaStreamProductRuntimeError::AuthorityProviderUnavailable)?;
                if host.snapshot().provider_epoch_id.as_str() != self.authority_epoch_id {
                    return Err(MediaStreamProductRuntimeError::AcceptedProviderEpochMismatch);
                }
                host.validate_media_session(decision_id, now_ms)
            }
            #[cfg(any(test, feature = "test-support"))]
            MediaStreamAuthoritySource::Test { current } => {
                let mut current = current
                    .read()
                    .map_err(|_| MediaStreamProductRuntimeError::AuthorityProviderUnavailable)?
                    .clone();
                current.validated_at_ms = now_ms;
                current
            }
        };
        validate_current_acceptance(
            &self.binding,
            &current,
            &self.authority_epoch_id,
            now_ms,
            require_current,
            Some(&self.current_acceptance),
        )?;
        self.current_acceptance = current;
        Ok(())
    }

    /// Returns a pending platform action, if Android has not yet completed it.
    #[must_use]
    pub const fn pending_action(&self) -> Option<&MediaStreamPlatformAction> {
        self.pending_action.as_ref()
    }

    /// Prepares one exact platform action without claiming platform completion.
    pub fn prepare(
        &mut self,
        action_id: String,
        operation: MediaStreamPlatformOperation,
        client_authority: MediaStreamClientAuthorityBinding,
        now_ms: u64,
    ) -> Result<MediaStreamPlatformAction, MediaStreamProductRuntimeError> {
        self.review_prepare(&action_id, operation, &client_authority, now_ms)?;
        let accepted = self
            .current_acceptance
            .session
            .as_ref()
            .ok_or(MediaStreamProductRuntimeError::AcceptedSessionMismatch)?;
        let action = MediaStreamPlatformAction {
            schema_id: MEDIA_STREAM_PLATFORM_ACTION_SCHEMA.to_owned(),
            action_id,
            authority_epoch_id: self.authority_epoch_id.clone(),
            operation,
            client_authority,
            runtime_spec_id: self.binding.spec.runtime_spec_id.clone(),
            runtime_spec_canonical_sha256: self.binding.runtime_spec_canonical_sha256.clone(),
            manifold_descriptor_canonical_sha256: self
                .current_acceptance
                .session
                .as_ref()
                .expect("validated current session")
                .product_descriptor_canonical_sha256
                .clone(),
            manifold_decision_id: accepted.decision_id.as_str().to_owned(),
            manifold_session_revision: accepted.session_authority_revision.get(),
            media_acceptance_authority_revision: self
                .current_acceptance
                .acceptance_state_authority_revision
                .get(),
            expected_runtime_revision: self.runtime.state().runtime_revision,
            owner_actions: ordered_owner_actions(&self.binding.spec.owner_selections, operation),
        };
        self.pending_owner_receipts.clear();
        self.pending_uncertain_owner = None;
        self.pending_abort_receipts.clear();
        self.abort_in_progress = false;
        self.pending_action = Some(action.clone());
        Ok(action)
    }

    /// Deterministically checks that an accepted Runtime Host command can be
    /// paired with one platform action before any host authority mutates.
    pub fn review_prepare(
        &mut self,
        action_id: &str,
        operation: MediaStreamPlatformOperation,
        client_authority: &MediaStreamClientAuthorityBinding,
        now_ms: u64,
    ) -> Result<(), MediaStreamProductRuntimeError> {
        self.refresh_authority(now_ms, operation == MediaStreamPlatformOperation::Start)?;
        if action_id.trim().is_empty() {
            return Err(MediaStreamProductRuntimeError::MissingActionId);
        }
        if self.applied_action_ids.contains(action_id)
            || self.aborted_action_ids.contains(action_id)
        {
            return Err(MediaStreamProductRuntimeError::ReplayedAction);
        }
        if self.pending_action.is_some() {
            return Err(MediaStreamProductRuntimeError::PendingActionExists);
        }
        if client_authority.client_id.trim().is_empty()
            || client_authority.lease_id.trim().is_empty()
            || client_authority.product_id.trim().is_empty()
            || client_authority.feature_lock_id.trim().is_empty()
            || validate_sha256(&client_authority.feature_lock_fingerprint).is_err()
            || client_authority.session_capability_id.trim().is_empty()
            || client_authority
                .session_admission_grant_id
                .trim()
                .is_empty()
            || client_authority
                .operation_admission_grant_id
                .trim()
                .is_empty()
            || client_authority
                .operation_admission_use_request_id
                .trim()
                .is_empty()
        {
            return Err(MediaStreamProductRuntimeError::MissingClientAuthority);
        }
        let accepted = self
            .current_acceptance
            .session
            .as_ref()
            .ok_or(MediaStreamProductRuntimeError::AcceptedSessionMismatch)?;
        if accepted.runtime_client_id.as_str() != client_authority.client_id
            || accepted.runtime_lease_id.as_str() != client_authority.lease_id
            || accepted.product_id.as_str() != client_authority.product_id
            || accepted.feature_lock_id.as_str() != client_authority.feature_lock_id
            || accepted.feature_lock_fingerprint != client_authority.feature_lock_fingerprint
            || accepted.capability_id.as_str() != client_authority.session_capability_id
            || accepted.admission_grant_id.as_str() != client_authority.session_admission_grant_id
            || accepted.admission_grant_id.as_str() != client_authority.operation_admission_grant_id
            || client_authority.operation_capability_id
                != match operation {
                    MediaStreamPlatformOperation::Start => "capability.command.media.session.start",
                    MediaStreamPlatformOperation::Stop => "capability.command.media.session.stop",
                }
        {
            return Err(MediaStreamProductRuntimeError::ClientAuthorityMismatch);
        }
        match (self.runtime.state().phase, operation) {
            (MediaStreamRuntimePhase::Planned, MediaStreamPlatformOperation::Start) => {}
            (MediaStreamRuntimePhase::Stopped, _) => {
                return Err(MediaStreamProductRuntimeError::TerminalRuntime)
            }
            (_, MediaStreamPlatformOperation::Start) => {
                return Err(MediaStreamProductRuntimeError::InvalidOperationPhase)
            }
            (_, MediaStreamPlatformOperation::Stop)
                if self
                    .active_client_authority
                    .as_ref()
                    .is_some_and(|active| same_session_holder(active, client_authority)) => {}
            (_, MediaStreamPlatformOperation::Stop) => {
                return Err(MediaStreamProductRuntimeError::ClientAuthorityMismatch)
            }
        }
        Ok(())
    }

    /// Records source-provider state through the source-only callback.
    pub fn complete_source<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        self.complete_owner(MediaStreamOwnerKind::Source, provider, now_ms)
    }

    /// Records processor-provider state through the processor-only callback.
    pub fn complete_processor<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        self.complete_owner(MediaStreamOwnerKind::Processor, provider, now_ms)
    }

    /// Records route-provider state through the route-only callback.
    pub fn complete_route<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        self.complete_owner(MediaStreamOwnerKind::Route, provider, now_ms)
    }

    /// Records socket-provider state through the socket-only callback.
    pub fn complete_socket<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        self.complete_owner(MediaStreamOwnerKind::Socket, provider, now_ms)
    }

    /// Records codec-provider state through the codec-only callback.
    pub fn complete_codec<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        self.complete_owner(MediaStreamOwnerKind::Codec, provider, now_ms)
    }

    /// Records sink-provider state through the sink-only callback.
    pub fn complete_sink<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        self.complete_owner(MediaStreamOwnerKind::Sink, provider, now_ms)
    }

    /// Records cleanup-provider state through the cleanup-only callback.
    pub fn complete_cleanup<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        self.complete_owner(MediaStreamOwnerKind::Cleanup, provider, now_ms)
    }

    fn complete_owner<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        owner_kind: MediaStreamOwnerKind,
        provider: &mut P,
        now_ms: u64,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        if self.abort_in_progress {
            return Err(MediaStreamProductRuntimeError::AbortInProgress);
        }
        if self.pending_uncertain_owner.is_some() {
            return Err(MediaStreamProductRuntimeError::UncertainOwnerAttemptPending);
        }
        let require_current = self
            .pending_action
            .as_ref()
            .is_some_and(|action| action.operation == MediaStreamPlatformOperation::Start);
        self.refresh_authority(now_ms, require_current)?;
        let action = self
            .pending_action
            .as_ref()
            .ok_or(MediaStreamProductRuntimeError::NoPendingAction)?
            .clone();
        let sequence = self.pending_owner_receipts.len();
        let expected = action
            .owner_actions
            .get(sequence)
            .ok_or(MediaStreamProductRuntimeError::OwnerCallbackOrderMismatch)?
            .clone();
        if expected.selection.owner_kind != owner_kind {
            return Err(MediaStreamProductRuntimeError::OwnerCallbackOrderMismatch);
        }
        if provider.owner_kind() != owner_kind {
            return Err(MediaStreamProductRuntimeError::OwnerProviderMismatch);
        }
        self.pending_uncertain_owner = Some(expected.clone());
        let readback = provider
            .execute_and_readback(&action, &expected)
            .map_err(|_| MediaStreamProductRuntimeError::OwnerProviderFailed)?;
        if readback.action_id != action.action_id
            || readback.authority_epoch_id != action.authority_epoch_id
            || readback.media_acceptance_authority_revision
                != action.media_acceptance_authority_revision
            || readback.client_id != action.client_authority.client_id
            || readback.lease_id != action.client_authority.lease_id
            || readback.provider_kind != expected.selection.provider_kind
            || readback.resource_id != expected.selection.resource_id
            || readback.provider_handle_id.trim().is_empty()
            || readback.provider_state_revision == 0
            || readback.observed_state != expected_provider_state(expected.action_kind)
            || readback.receipt_id.trim().is_empty()
            || !provider.verify_readback(&action, &expected, &readback)
            || self.pending_owner_receipts.iter().any(|receipt| {
                receipt.receipt_id == readback.receipt_id
                    || receipt.provider_handle_id == readback.provider_handle_id
            })
        {
            return Err(MediaStreamProductRuntimeError::OwnerReadbackMismatch);
        }
        match action.operation {
            MediaStreamPlatformOperation::Start => {
                if self
                    .active_provider_handles
                    .contains_key(&expected.selection)
                {
                    return Err(MediaStreamProductRuntimeError::OwnerHandleLifecycleMismatch);
                }
            }
            MediaStreamPlatformOperation::Stop => {
                let Some((active_handle, active_revision)) =
                    self.active_provider_handles.get(&expected.selection)
                else {
                    return Err(MediaStreamProductRuntimeError::OwnerHandleLifecycleMismatch);
                };
                if active_handle != &readback.provider_handle_id
                    || *active_revision >= readback.provider_state_revision
                {
                    return Err(MediaStreamProductRuntimeError::OwnerHandleLifecycleMismatch);
                }
            }
        }
        let receipt = MediaStreamOwnerCompletionReceipt {
            selection: expected.selection.clone(),
            action_kind: expected.action_kind,
            receipt_id: readback.receipt_id,
            completion_sequence: u32::try_from(sequence + 1)
                .map_err(|_| MediaStreamProductRuntimeError::OwnerReadbackMismatch)?,
            provider_handle_id: readback.provider_handle_id,
            provider_state_revision: readback.provider_state_revision,
            observed_state: readback.observed_state,
        };
        self.pending_owner_receipts.push(receipt.clone());
        self.pending_uncertain_owner = None;
        Ok(receipt)
    }

    /// Begins authority-independent rollback for a prepared Start action.
    ///
    /// This transition deliberately does not query current session authority:
    /// revocation, provider rebind, or authority loss must never strand local
    /// resources that were already observed as started/armed.
    pub fn begin_partial_start_abort(
        &mut self,
    ) -> Result<MediaStreamPlatformAction, MediaStreamProductRuntimeError> {
        let action = self
            .pending_action
            .as_ref()
            .ok_or(MediaStreamProductRuntimeError::NoPendingAction)?;
        if action.operation != MediaStreamPlatformOperation::Start {
            return Err(MediaStreamProductRuntimeError::AbortRequiresPendingStart);
        }
        if self.abort_in_progress {
            return Err(MediaStreamProductRuntimeError::AbortInProgress);
        }
        self.abort_in_progress = true;
        self.pending_abort_receipts.clear();
        Ok(partial_start_abort_action(
            action,
            &self.pending_owner_receipts,
            self.pending_uncertain_owner.as_ref(),
        ))
    }

    /// Returns the exact rollback action while partial-start cleanup is active.
    #[must_use]
    pub fn pending_abort_action(&self) -> Option<MediaStreamPlatformAction> {
        self.abort_in_progress.then(|| {
            partial_start_abort_action(
                self.pending_action
                    .as_ref()
                    .expect("abort retains pending action"),
                &self.pending_owner_receipts,
                self.pending_uncertain_owner.as_ref(),
            )
        })
    }

    /// Reverses the next observed partial-start owner in exact reverse order.
    /// The provider must return the same live handle at a strictly newer state
    /// revision, so retry/death recovery cannot silently substitute resources.
    pub fn complete_next_abort_owner<P: MediaStreamTrustedOwnerProvider>(
        &mut self,
        provider: &mut P,
    ) -> Result<MediaStreamOwnerCompletionReceipt, MediaStreamProductRuntimeError> {
        if !self.abort_in_progress {
            return Err(MediaStreamProductRuntimeError::AbortRequiresPendingStart);
        }
        let sequence = self.pending_abort_receipts.len();
        let uncertain = self.pending_uncertain_owner.clone();
        let uncertain_offset = usize::from(uncertain.is_some());
        let completed_sequence = sequence.saturating_sub(uncertain_offset);
        let started = if sequence < uncertain_offset {
            None
        } else {
            let start_index = self
                .pending_owner_receipts
                .len()
                .checked_sub(completed_sequence + 1)
                .ok_or(MediaStreamProductRuntimeError::AbortCallbacksIncomplete)?;
            Some(self.pending_owner_receipts[start_index].clone())
        };
        let action = partial_start_abort_action(
            self.pending_action
                .as_ref()
                .expect("abort retains pending action"),
            &self.pending_owner_receipts,
            uncertain.as_ref(),
        );
        let expected = action
            .owner_actions
            .get(sequence)
            .ok_or(MediaStreamProductRuntimeError::AbortCallbacksIncomplete)?;
        if provider.owner_kind() != expected.selection.owner_kind
            || started
                .as_ref()
                .is_some_and(|started| expected.selection != started.selection)
        {
            return Err(MediaStreamProductRuntimeError::OwnerProviderMismatch);
        }
        let readback = if started.is_some() {
            provider.execute_and_readback(&action, expected)
        } else {
            provider.compensate_uncertain_attempt(&action, expected)
        }
        .map_err(|_| MediaStreamProductRuntimeError::OwnerProviderFailed)?;
        if readback.action_id != action.action_id
            || readback.authority_epoch_id != action.authority_epoch_id
            || readback.media_acceptance_authority_revision
                != action.media_acceptance_authority_revision
            || readback.client_id != action.client_authority.client_id
            || readback.lease_id != action.client_authority.lease_id
            || readback.provider_kind != expected.selection.provider_kind
            || readback.resource_id != expected.selection.resource_id
            || started.as_ref().is_some_and(|started| {
                readback.provider_handle_id != started.provider_handle_id
                    || readback.provider_state_revision <= started.provider_state_revision
            })
            || readback.provider_handle_id.trim().is_empty()
            || readback.provider_state_revision == 0
            || readback.observed_state != expected_provider_state(expected.action_kind)
            || readback.receipt_id.trim().is_empty()
            || !provider.verify_readback(&action, expected, &readback)
            || self.pending_abort_receipts.iter().any(|receipt| {
                receipt.receipt_id == readback.receipt_id || receipt.selection == expected.selection
            })
        {
            return Err(MediaStreamProductRuntimeError::OwnerReadbackMismatch);
        }
        let receipt = MediaStreamOwnerCompletionReceipt {
            selection: expected.selection.clone(),
            action_kind: expected.action_kind,
            receipt_id: readback.receipt_id,
            completion_sequence: u32::try_from(sequence + 1)
                .map_err(|_| MediaStreamProductRuntimeError::OwnerReadbackMismatch)?,
            provider_handle_id: readback.provider_handle_id,
            provider_state_revision: readback.provider_state_revision,
            observed_state: readback.observed_state,
        };
        self.pending_abort_receipts.push(receipt.clone());
        Ok(receipt)
    }

    /// Releases a pending Start only after every observed side effect supplied
    /// an exact reverse-order cleanup readback.
    pub fn finalize_partial_start_abort(
        &mut self,
    ) -> Result<MediaStreamPlatformAbortReceipt, MediaStreamProductRuntimeError> {
        if !self.abort_in_progress {
            return Err(MediaStreamProductRuntimeError::AbortRequiresPendingStart);
        }
        let uncertain_attempt_count = usize::from(self.pending_uncertain_owner.is_some());
        let uncertain_attempts =
            u32::try_from(uncertain_attempt_count).expect("at most one uncertain owner attempt");
        if self.pending_abort_receipts.len()
            != self.pending_owner_receipts.len() + uncertain_attempt_count
        {
            return Err(MediaStreamProductRuntimeError::AbortCallbacksIncomplete);
        }
        let action = self
            .pending_action
            .as_ref()
            .ok_or(MediaStreamProductRuntimeError::NoPendingAction)?
            .clone();
        let rollback_receipts = self.pending_abort_receipts.clone();
        self.aborted_action_ids.insert(action.action_id.clone());
        self.pending_action = None;
        self.pending_owner_receipts.clear();
        self.pending_uncertain_owner = None;
        self.pending_abort_receipts.clear();
        self.abort_in_progress = false;
        Ok(MediaStreamPlatformAbortReceipt {
            schema_id: MEDIA_STREAM_PLATFORM_ABORT_SCHEMA.to_owned(),
            abort_action_id: format!("{}.abort", action.action_id),
            action_id: action.action_id,
            authority_epoch_id: action.authority_epoch_id,
            client_authority: action.client_authority,
            rollback_receipts,
            uncertain_attempts_compensated: uncertain_attempts,
            cleanup_complete: true,
            platform_effect_completed: false,
            resulting_phase: self.runtime.state().phase,
            resulting_runtime_revision: self.runtime.state().runtime_revision,
        })
    }

    /// Applies only the complete ordered set produced by owner-specific
    /// callbacks and only then advances lifecycle state.
    pub fn apply_recorded_owner_completions(
        &mut self,
        now_ms: u64,
    ) -> Result<MediaStreamPlatformApplicationReceipt, MediaStreamProductRuntimeError> {
        if self.abort_in_progress {
            return Err(MediaStreamProductRuntimeError::AbortInProgress);
        }
        let require_current = self
            .pending_action
            .as_ref()
            .is_some_and(|action| action.operation == MediaStreamPlatformOperation::Start);
        self.refresh_authority(now_ms, require_current)?;
        let action = self
            .pending_action
            .as_ref()
            .ok_or(MediaStreamProductRuntimeError::NoPendingAction)?;
        if self.pending_owner_receipts.len() != action.owner_actions.len() {
            return Err(MediaStreamProductRuntimeError::OwnerCallbacksIncomplete);
        }
        validate_owner_completions(action, &self.pending_owner_receipts)?;

        let mut decisions = Vec::new();
        match action.operation {
            MediaStreamPlatformOperation::Start => {
                let arm = self.runtime.execute(&MediaStreamRuntimeRequest {
                    request_id: format!("{}.arm_receivers", action.action_id),
                    expected_runtime_revision: action.expected_runtime_revision,
                    action: MediaStreamRuntimeAction::ArmReceivers,
                    evidence: MediaStreamRuntimeEvidence {
                        receivers_armed: true,
                        ..Default::default()
                    },
                });
                if !arm.accepted || !arm.applied {
                    return Err(MediaStreamProductRuntimeError::LifecycleRejected);
                }
                decisions.push(arm);
                let start = self.runtime.execute(&MediaStreamRuntimeRequest {
                    request_id: format!("{}.start_sources", action.action_id),
                    expected_runtime_revision: action.expected_runtime_revision + 1,
                    action: MediaStreamRuntimeAction::StartSources,
                    evidence: MediaStreamRuntimeEvidence {
                        sources_started: true,
                        ..Default::default()
                    },
                });
                if !start.accepted || !start.applied {
                    return Err(MediaStreamProductRuntimeError::LifecycleRejected);
                }
                decisions.push(start);
            }
            MediaStreamPlatformOperation::Stop => {
                let stop = self.runtime.execute(&MediaStreamRuntimeRequest {
                    request_id: format!("{}.cleanup", action.action_id),
                    expected_runtime_revision: action.expected_runtime_revision,
                    action: MediaStreamRuntimeAction::Stop,
                    evidence: MediaStreamRuntimeEvidence {
                        cleanup_complete: true,
                        ..Default::default()
                    },
                });
                if !stop.accepted || !stop.applied {
                    return Err(MediaStreamProductRuntimeError::LifecycleRejected);
                }
                decisions.push(stop);
            }
        }

        let owner_receipt_ids = self
            .pending_owner_receipts
            .iter()
            .map(|receipt| receipt.receipt_id.clone())
            .collect();
        let action_id = action.action_id.clone();
        let applied_operation = action.operation;
        let applied_client_authority = action.client_authority.clone();
        self.applied_action_ids.insert(action_id.clone());
        match applied_operation {
            MediaStreamPlatformOperation::Start => {
                for receipt in &self.pending_owner_receipts {
                    self.active_provider_handles.insert(
                        receipt.selection.clone(),
                        (
                            receipt.provider_handle_id.clone(),
                            receipt.provider_state_revision,
                        ),
                    );
                }
                self.active_client_authority = Some(applied_client_authority);
            }
            MediaStreamPlatformOperation::Stop => {
                self.active_provider_handles.clear();
                self.active_client_authority = None;
            }
        }
        self.pending_action = None;
        self.pending_owner_receipts.clear();
        Ok(MediaStreamPlatformApplicationReceipt {
            schema_id: MEDIA_STREAM_PLATFORM_APPLICATION_SCHEMA.to_owned(),
            action_id,
            authority_epoch_id: self.authority_epoch_id.clone(),
            media_acceptance_authority_revision: self
                .current_acceptance
                .acceptance_state_authority_revision
                .get(),
            platform_effect_completed: true,
            resulting_runtime_revision: self.runtime.state().runtime_revision,
            resulting_phase: self.runtime.state().phase,
            lifecycle_decisions: decisions,
            owner_receipt_ids,
        })
    }
}

fn same_session_holder(
    active: &MediaStreamClientAuthorityBinding,
    candidate: &MediaStreamClientAuthorityBinding,
) -> bool {
    active.client_id == candidate.client_id
        && active.lease_id == candidate.lease_id
        && active.product_id == candidate.product_id
        && active.feature_lock_id == candidate.feature_lock_id
        && active.feature_lock_fingerprint == candidate.feature_lock_fingerprint
        && active.session_capability_id == candidate.session_capability_id
        && active.session_admission_grant_id == candidate.session_admission_grant_id
}

fn validate_current_acceptance(
    binding: &MediaStreamRuntimeProductBinding,
    current: &ManifoldMediaSessionCurrentReceipt,
    live_provider_epoch_id: &str,
    now_ms: u64,
    require_current: bool,
    prior: Option<&ManifoldMediaSessionCurrentReceipt>,
) -> Result<(), MediaStreamProductRuntimeError> {
    if current.schema_id.as_str() != MANIFOLD_MEDIA_SESSION_CURRENT_RECEIPT_SCHEMA
        || current.acceptance_state_authority_revision.get() == 0
        || current.validated_at_ms != now_ms
    {
        return Err(MediaStreamProductRuntimeError::AcceptedSessionNotCurrent);
    }
    let accepted = current
        .session
        .as_ref()
        .ok_or(MediaStreamProductRuntimeError::AcceptedSessionNotCurrent)?;
    if current.decision_id != accepted.decision_id
        || accepted.provider_epoch_id.as_str() != live_provider_epoch_id
        || accepted.accepted_at_ms > now_ms
    {
        return Err(MediaStreamProductRuntimeError::AcceptedSessionNotCurrent);
    }
    if require_current
        && (!current.current
            || current.rejection_reason.is_some()
            || accepted.lifecycle_status != ManifoldMediaSessionLifecycleStatus::Current
            || accepted.expires_at_ms <= now_ms)
    {
        return Err(MediaStreamProductRuntimeError::AcceptedSessionNotCurrent);
    }
    if let Some(prior) = prior {
        let prior_session = prior
            .session
            .as_ref()
            .ok_or(MediaStreamProductRuntimeError::AcceptedSessionSuperseded)?;
        if current.acceptance_state_authority_revision < prior.acceptance_state_authority_revision
            || current.decision_id != prior.decision_id
            || !same_accepted_subject(accepted, prior_session)
        {
            return Err(MediaStreamProductRuntimeError::AcceptedSessionSuperseded);
        }
    }
    accepted
        .product_binding
        .validate()
        .map_err(|_| MediaStreamProductRuntimeError::AcceptedSessionMismatch)?;
    validate_sha256(&accepted.product_descriptor_canonical_sha256)?;
    let descriptor = &accepted.product_binding.descriptor;
    let spec = &binding.spec;
    let exact = accepted.schema_id.as_str() == MANIFOLD_ACCEPTED_MEDIA_SESSION_SCHEMA
        && !accepted.decision_id.as_str().trim().is_empty()
        && accepted.session_id == descriptor.session_id
        && accepted.session_id.as_str() == spec.plan.session_id
        && accepted.session_authority_revision == descriptor.authority_revision
        && accepted.session_authority_revision.get() == spec.manifold_session_revision
        && accepted.product_descriptor_canonical_sha256
            == accepted.product_binding.descriptor_canonical_sha256
        && accepted.platform_runtime_spec_id == descriptor.platform_runtime_spec_id
        && accepted.platform_runtime_spec_id.as_str() == spec.runtime_spec_id
        && accepted.provider_epoch_id.as_str() == live_provider_epoch_id
        && accepted.runtime_client_id.as_str().trim().len() > 0
        && accepted.runtime_lease_id.as_str().trim().len() > 0
        && descriptor
            .source_ids
            .iter()
            .map(|id| id.as_str())
            .collect::<Vec<_>>()
            == spec
                .plan
                .sources
                .iter()
                .map(|source| source.source_id.as_str())
                .collect::<Vec<_>>()
        && descriptor
            .processor_ids
            .iter()
            .map(|id| id.as_str())
            .collect::<Vec<_>>()
            == spec
                .processors
                .iter()
                .map(|processor| processor.processor_id.as_str())
                .collect::<Vec<_>>()
        && descriptor
            .route_ids
            .iter()
            .map(|id| id.as_str())
            .collect::<Vec<_>>()
            == spec
                .plan
                .transport_routes
                .iter()
                .map(|route| route.lane_id.as_str())
                .collect::<Vec<_>>()
        && descriptor
            .sink_ids
            .iter()
            .map(|id| id.as_str())
            .collect::<Vec<_>>()
            == spec
                .sinks
                .iter()
                .map(|sink| sink.sink_id.as_str())
                .collect::<Vec<_>>()
        && descriptor
            .stream_ids
            .iter()
            .map(|id| id.as_str())
            .collect::<Vec<_>>()
            == spec
                .plan
                .lanes
                .iter()
                .map(|lane| lane.media.track_id.as_str())
                .collect::<Vec<_>>();
    if exact {
        Ok(())
    } else {
        Err(MediaStreamProductRuntimeError::AcceptedSessionMismatch)
    }
}

fn same_accepted_subject(
    current: &ManifoldAcceptedMediaSession,
    prior: &ManifoldAcceptedMediaSession,
) -> bool {
    current.schema_id == prior.schema_id
        && current.decision_id == prior.decision_id
        && current.request_id == prior.request_id
        && current.session_id == prior.session_id
        && current.session_authority_revision == prior.session_authority_revision
        && current.product_descriptor_canonical_sha256 == prior.product_descriptor_canonical_sha256
        && current.provider_epoch_id == prior.provider_epoch_id
        && current.platform_runtime_spec_id == prior.platform_runtime_spec_id
        && current.product_id == prior.product_id
        && current.feature_lock_id == prior.feature_lock_id
        && current.feature_lock_fingerprint == prior.feature_lock_fingerprint
        && current.capability_id == prior.capability_id
        && current.admission_grant_id == prior.admission_grant_id
        && current.runtime_authority_host_id == prior.runtime_authority_host_id
        && current.runtime_command_request_id == prior.runtime_command_request_id
        && current.runtime_command_id == prior.runtime_command_id
        && current.runtime_client_id == prior.runtime_client_id
        && current.runtime_lease_id == prior.runtime_lease_id
        && current.runtime_params_digest == prior.runtime_params_digest
        && current.runtime_dispatch_id == prior.runtime_dispatch_id
        && current.runtime_application_receipt_id == prior.runtime_application_receipt_id
        && current.runtime_resulting_authority_revision
            == prior.runtime_resulting_authority_revision
        && current.accepted_at_ms == prior.accepted_at_ms
        && current.expires_at_ms == prior.expires_at_ms
        && current.product_binding == prior.product_binding
}

/// Returns the canonical typed runtime-spec digest.
pub fn canonical_media_stream_runtime_sha256(
    spec: &MediaStreamRuntimeSpec,
) -> Result<String, MediaStreamProductRuntimeError> {
    let canonical = serde_json::to_vec(spec).map_err(MediaStreamProductRuntimeError::Encode)?;
    Ok(format!("sha256:{:x}", Sha256::digest(canonical)))
}

pub(crate) fn validate_media_stream_owner_selections(
    spec: &MediaStreamRuntimeSpec,
    errors: &mut Vec<ValidationError>,
) {
    if !spec
        .owner_selections
        .windows(2)
        .all(|pair| pair[0] < pair[1])
    {
        errors.push(ValidationError::new(
            "owner selections must be a strict canonical sorted set",
        ));
    }
    let expected = expected_owner_resources(spec);
    let mut selected = BTreeMap::<MediaStreamOwnerKind, BTreeSet<&str>>::new();
    let mut selected_resources = BTreeSet::new();
    for selection in &spec.owner_selections {
        if selection.owner_id.trim().is_empty()
            || selection.resource_id.trim().is_empty()
            || selection.provider_kind.trim().is_empty()
        {
            errors.push(ValidationError::new(
                "owner selections require owner, resource, and provider identities",
            ));
        }
        selected
            .entry(selection.owner_kind)
            .or_default()
            .insert(selection.resource_id.as_str());
        if !selected_resources.insert((selection.owner_kind, selection.resource_id.as_str())) {
            errors.push(ValidationError::new(
                "each owner kind may select a product resource exactly once",
            ));
        }
        if selection.owner_kind == MediaStreamOwnerKind::Cleanup
            && (selection.resource_id != spec.runtime_spec_id || selection.lane_id.is_some())
        {
            errors.push(ValidationError::new(
                "cleanup owner must bind the runtime spec and no lane",
            ));
        }
        if selection.owner_kind != MediaStreamOwnerKind::Cleanup
            && selection.lane_id.as_deref().is_some_and(str::is_empty)
        {
            errors.push(ValidationError::new("owner lane id must not be empty"));
        }
        let remote_camera_named = selection.owner_id.contains("remote_camera")
            || selection.owner_id.contains("remote-camera")
            || selection.provider_kind.contains("remote_camera")
            || selection.provider_kind.contains("remote-camera");
        if spec.compatibility_adapter_id.is_none() && remote_camera_named {
            errors.push(ValidationError::new(
                "generic media owner selections must not inherit remote-camera identities",
            ));
        }
    }
    for (kind, resources) in expected {
        if selected.get(&kind) != Some(&resources) {
            errors.push(ValidationError::new(format!(
                "{kind:?} owner resources do not exactly cover the selected product"
            )));
        }
    }
    if selected.len() != 7 {
        errors.push(ValidationError::new(
            "source, processor, route, socket, codec, sink, and cleanup owners are required",
        ));
    }
    if let Some(adapter) = &spec.compatibility_adapter_id {
        if adapter != REMOTE_CAMERA_COMPATIBILITY_ADAPTER {
            errors.push(ValidationError::new(
                "unknown media compatibility adapter identity",
            ));
        }
    }
}

fn expected_owner_resources(
    spec: &MediaStreamRuntimeSpec,
) -> BTreeMap<MediaStreamOwnerKind, BTreeSet<&str>> {
    BTreeMap::from([
        (
            MediaStreamOwnerKind::Source,
            spec.plan
                .sources
                .iter()
                .map(|value| value.source_id.as_str())
                .collect(),
        ),
        (
            MediaStreamOwnerKind::Processor,
            spec.processors
                .iter()
                .map(|value| value.processor_id.as_str())
                .collect(),
        ),
        (
            MediaStreamOwnerKind::Route,
            spec.plan
                .transport_routes
                .iter()
                .map(|value| value.lane_id.as_str())
                .collect(),
        ),
        (
            MediaStreamOwnerKind::Socket,
            spec.plan
                .transport_routes
                .iter()
                .map(|value| value.lane_id.as_str())
                .collect(),
        ),
        (
            MediaStreamOwnerKind::Codec,
            spec.plan
                .lanes
                .iter()
                .map(|value| value.lane_id.as_str())
                .collect(),
        ),
        (
            MediaStreamOwnerKind::Sink,
            spec.sinks
                .iter()
                .map(|value| value.sink_id.as_str())
                .collect(),
        ),
        (
            MediaStreamOwnerKind::Cleanup,
            BTreeSet::from([spec.runtime_spec_id.as_str()]),
        ),
    ])
}

fn ordered_owner_actions(
    selections: &[MediaStreamOwnerSelection],
    operation: MediaStreamPlatformOperation,
) -> Vec<MediaStreamOwnerAction> {
    let mut actions = selections
        .iter()
        .cloned()
        .map(|selection| {
            let action_kind = match (operation, selection.owner_kind) {
                (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Sink) => {
                    MediaStreamOwnerActionKind::ArmReceiver
                }
                (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Cleanup) => {
                    MediaStreamOwnerActionKind::ArmCleanup
                }
                (MediaStreamPlatformOperation::Start, _) => MediaStreamOwnerActionKind::Start,
                (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Cleanup) => {
                    MediaStreamOwnerActionKind::Cleanup
                }
                (MediaStreamPlatformOperation::Stop, _) => MediaStreamOwnerActionKind::Stop,
            };
            MediaStreamOwnerAction {
                selection,
                action_kind,
            }
        })
        .collect::<Vec<_>>();
    actions.sort_by_key(|action| {
        let priority = match (operation, action.selection.owner_kind) {
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Cleanup) => 0,
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Sink) => 1,
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Route) => 2,
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Socket) => 3,
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Codec) => 4,
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Processor) => 5,
            (MediaStreamPlatformOperation::Start, MediaStreamOwnerKind::Source) => 6,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Source) => 0,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Processor) => 1,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Codec) => 2,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Socket) => 3,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Route) => 4,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Sink) => 5,
            (MediaStreamPlatformOperation::Stop, MediaStreamOwnerKind::Cleanup) => 6,
        };
        (priority, action.selection.clone())
    });
    actions
}

fn partial_start_abort_action(
    action: &MediaStreamPlatformAction,
    completed: &[MediaStreamOwnerCompletionReceipt],
    uncertain: Option<&MediaStreamOwnerAction>,
) -> MediaStreamPlatformAction {
    let mut abort = action.clone();
    abort.action_id = format!("{}.abort", action.action_id);
    abort.owner_actions = uncertain
        .into_iter()
        .map(|attempt| MediaStreamOwnerAction {
            selection: attempt.selection.clone(),
            action_kind: if attempt.action_kind == MediaStreamOwnerActionKind::ArmCleanup {
                MediaStreamOwnerActionKind::Cleanup
            } else {
                MediaStreamOwnerActionKind::Stop
            },
        })
        .chain(
            completed
                .iter()
                .rev()
                .map(|receipt| MediaStreamOwnerAction {
                    selection: receipt.selection.clone(),
                    action_kind: if receipt.action_kind == MediaStreamOwnerActionKind::ArmCleanup {
                        MediaStreamOwnerActionKind::Cleanup
                    } else {
                        MediaStreamOwnerActionKind::Stop
                    },
                }),
        )
        .collect();
    abort
}

const fn expected_provider_state(action: MediaStreamOwnerActionKind) -> &'static str {
    match action {
        MediaStreamOwnerActionKind::ArmReceiver => "receiver_armed",
        MediaStreamOwnerActionKind::ArmCleanup => "cleanup_armed",
        MediaStreamOwnerActionKind::Start => "started",
        MediaStreamOwnerActionKind::Stop => "stopped",
        MediaStreamOwnerActionKind::Cleanup => "cleaned",
    }
}

fn validate_owner_completions(
    action: &MediaStreamPlatformAction,
    receipts: &[MediaStreamOwnerCompletionReceipt],
) -> Result<(), MediaStreamProductRuntimeError> {
    if receipts.len() != action.owner_actions.len() {
        return Err(MediaStreamProductRuntimeError::OwnerCompletionMismatch);
    }
    let mut receipt_ids = BTreeSet::new();
    let mut provider_handles = BTreeSet::new();
    for (index, (expected, receipt)) in action.owner_actions.iter().zip(receipts).enumerate() {
        if receipt.selection != expected.selection
            || receipt.action_kind != expected.action_kind
            || receipt.receipt_id.trim().is_empty()
            || receipt.completion_sequence != u32::try_from(index + 1).unwrap_or(u32::MAX)
            || receipt.provider_handle_id.trim().is_empty()
            || receipt.provider_state_revision == 0
            || receipt.observed_state != expected_provider_state(expected.action_kind)
            || !receipt_ids.insert(receipt.receipt_id.as_str())
            || !provider_handles.insert(receipt.provider_handle_id.as_str())
        {
            return Err(MediaStreamProductRuntimeError::OwnerCompletionMismatch);
        }
    }
    match action.operation {
        MediaStreamPlatformOperation::Start => {
            let source_first = receipts
                .iter()
                .filter(|receipt| receipt.selection.owner_kind == MediaStreamOwnerKind::Source)
                .map(|receipt| receipt.completion_sequence)
                .min()
                .ok_or(MediaStreamProductRuntimeError::OwnerCompletionMismatch)?;
            let required_before_source = receipts
                .iter()
                .filter(|receipt| receipt.selection.owner_kind != MediaStreamOwnerKind::Source)
                .map(|receipt| receipt.completion_sequence)
                .max()
                .ok_or(MediaStreamProductRuntimeError::OwnerCompletionMismatch)?;
            if required_before_source >= source_first {
                return Err(MediaStreamProductRuntimeError::ReceiverFirstViolation);
            }
        }
        MediaStreamPlatformOperation::Stop => {
            let cleanup = receipts
                .iter()
                .find(|receipt| receipt.selection.owner_kind == MediaStreamOwnerKind::Cleanup)
                .ok_or(MediaStreamProductRuntimeError::OwnerCompletionMismatch)?
                .completion_sequence;
            let last_stop = receipts
                .iter()
                .filter(|receipt| receipt.selection.owner_kind != MediaStreamOwnerKind::Cleanup)
                .map(|receipt| receipt.completion_sequence)
                .max()
                .ok_or(MediaStreamProductRuntimeError::OwnerCompletionMismatch)?;
            if cleanup <= last_stop {
                return Err(MediaStreamProductRuntimeError::CleanupOrderViolation);
            }
        }
    }
    Ok(())
}

fn validate_sha256(value: &str) -> Result<(), MediaStreamProductRuntimeError> {
    let Some(hex) = value.strip_prefix("sha256:") else {
        return Err(MediaStreamProductRuntimeError::InvalidDescriptorDigest);
    };
    if hex.len() != 64
        || !hex
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(MediaStreamProductRuntimeError::InvalidDescriptorDigest);
    }
    Ok(())
}

/// Closed product binding, prepare, or application failure.
#[derive(Debug)]
pub enum MediaStreamProductRuntimeError {
    /// Packaged binding schema differs from v1.
    BindingSchemaMismatch,
    /// Runtime spec failed closed validation.
    RuntimeSpecInvalid(Vec<ValidationError>),
    /// Canonical typed runtime spec serialization failed.
    Encode(serde_json::Error),
    /// Packaged runtime spec digest differs from canonical typed JSON.
    RuntimeSpecDigestMismatch,
    /// Manifold descriptor digest is not a canonical SHA-256 identity.
    InvalidDescriptorDigest,
    /// Retained Manifold acceptance does not close over the Quest runtime spec.
    AcceptedSessionMismatch,
    /// Retained acceptance belongs to another/stale provider process epoch.
    AcceptedProviderEpochMismatch,
    /// Manifold subject receipt is absent, ended, revoked, expired, or stale.
    AcceptedSessionNotCurrent,
    /// Current-state refresh no longer represents the same subject decision.
    AcceptedSessionSuperseded,
    /// Live authority epoch is absent.
    MissingAuthorityEpoch,
    /// Live peer Runtime Host authority could not be queried.
    AuthorityProviderUnavailable,
    /// Action identity is absent.
    MissingActionId,
    /// Exact requesting client or media lease is absent.
    MissingClientAuthority,
    /// Stop/cleanup authority differs from the client/lease that started handles.
    ClientAuthorityMismatch,
    /// Action identity already applied.
    ReplayedAction,
    /// One prior prepared action remains pending.
    PendingActionExists,
    /// A partial-start rollback is active and must finish before other work.
    AbortInProgress,
    /// A provider call may have produced an unverified side effect and must be compensated.
    UncertainOwnerAttemptPending,
    /// Rollback is valid only for one pending Start action.
    AbortRequiresPendingStart,
    /// Not every observed partial-start owner supplied reverse cleanup proof.
    AbortCallbacksIncomplete,
    /// No action exists for this completion.
    NoPendingAction,
    /// Runtime is terminal and cannot restart.
    TerminalRuntime,
    /// Operation is invalid for current lifecycle phase.
    InvalidOperationPhase,
    /// Completion schema differs from v1.
    CompletionSchemaMismatch,
    /// Completion does not bind the exact action/epoch/spec/descriptor.
    CompletionBindingMismatch,
    /// Owner receipts are partial, reordered, duplicate, damaged, or mismatched.
    OwnerCompletionMismatch,
    /// Owner-specific callbacks were invoked out of the exact emitted order.
    OwnerCallbackOrderMismatch,
    /// A provider readback did not bind the exact action/epoch/resource/state.
    OwnerReadbackMismatch,
    /// Selected Rust/NDK provider implements another owner family.
    OwnerProviderMismatch,
    /// Selected Rust/NDK provider failed to execute or read back state.
    OwnerProviderFailed,
    /// Start/stop did not preserve the same provider handle at a newer revision.
    OwnerHandleLifecycleMismatch,
    /// Not every selected owner has supplied a trusted readback.
    OwnerCallbacksIncomplete,
    /// Source completion preceded another required owner.
    ReceiverFirstViolation,
    /// Cleanup completed before another owner stopped.
    CleanupOrderViolation,
    /// Internal revisioned lifecycle review rejected after exact completion.
    LifecycleRejected,
}

impl std::fmt::Display for MediaStreamProductRuntimeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let message = match self {
            Self::BindingSchemaMismatch => "media runtime product binding schema mismatch",
            Self::RuntimeSpecInvalid(_) => "media runtime spec invalid",
            Self::Encode(_) => "media runtime canonical encoding failed",
            Self::RuntimeSpecDigestMismatch => "media runtime spec canonical digest mismatch",
            Self::InvalidDescriptorDigest => "Manifold descriptor digest invalid",
            Self::AcceptedSessionMismatch => {
                "retained Manifold media-session acceptance does not match Quest runtime"
            }
            Self::AcceptedProviderEpochMismatch => {
                "retained Manifold media-session acceptance provider epoch is stale"
            }
            Self::AcceptedSessionNotCurrent => {
                "retained Manifold media-session subject is not current"
            }
            Self::AcceptedSessionSuperseded => {
                "retained Manifold media-session subject was superseded"
            }
            Self::MissingAuthorityEpoch => "media authority epoch missing",
            Self::AuthorityProviderUnavailable => "media authority provider unavailable",
            Self::MissingActionId => "media action id missing",
            Self::MissingClientAuthority => "media client or lease authority missing",
            Self::ClientAuthorityMismatch => {
                "media stop client/lease differs from active start authority"
            }
            Self::ReplayedAction => "media action replayed",
            Self::PendingActionExists => "media platform action already pending",
            Self::AbortInProgress => "media partial-start rollback already in progress",
            Self::UncertainOwnerAttemptPending => {
                "media owner attempt is uncertain and requires compensation"
            }
            Self::AbortRequiresPendingStart => "media rollback requires one pending start action",
            Self::AbortCallbacksIncomplete => {
                "media rollback lacks reverse cleanup for every observed owner"
            }
            Self::NoPendingAction => "media platform completion has no pending action",
            Self::TerminalRuntime => "media runtime is terminal",
            Self::InvalidOperationPhase => "media operation invalid for runtime phase",
            Self::CompletionSchemaMismatch => "media platform completion schema mismatch",
            Self::CompletionBindingMismatch => "media platform completion binding mismatch",
            Self::OwnerCompletionMismatch => "media owner completion mismatch",
            Self::OwnerCallbackOrderMismatch => "media owner callback order mismatch",
            Self::OwnerReadbackMismatch => "media owner provider readback mismatch",
            Self::OwnerProviderMismatch => "media owner provider family mismatch",
            Self::OwnerProviderFailed => "media owner provider execution/readback failed",
            Self::OwnerHandleLifecycleMismatch => "media owner provider handle lifecycle mismatch",
            Self::OwnerCallbacksIncomplete => "media owner callbacks incomplete",
            Self::ReceiverFirstViolation => "media source started before every prerequisite owner",
            Self::CleanupOrderViolation => "media cleanup completion order violated",
            Self::LifecycleRejected => "media lifecycle rejected exact platform completion",
        };
        formatter.write_str(message)
    }
}

impl std::error::Error for MediaStreamProductRuntimeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Encode(error) => Some(error),
            _ => None,
        }
    }
}
