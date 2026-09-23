//! Authenticated, fail-closed contracts between the Rust media authority and
//! one process-local Android owner registry.

use rusty_quest_media_stream::{
    MediaStreamOwnerAction, MediaStreamOwnerActionKind, MediaStreamOwnerKind,
    MediaStreamOwnerProviderReadback, MediaStreamPlatformAction, MediaStreamPlatformOperation,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fmt,
};

/// Closed execution-ticket schema understood by the Android registry.
pub const ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA: &str =
    "rusty.quest.android.media.execution-ticket.v1";
/// Closed provider-readback schema returned by the Android registry.
pub const ANDROID_MEDIA_READBACK_SCHEMA: &str = "rusty.quest.android.media.readback.v1";

/// Whether the registry performs the requested owner transition or compensates
/// an attempt whose side effect is uncertain.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidMediaExecutionMode {
    /// Execute the exact owner transition in the ticket.
    Execute,
    /// Query and clean an owner attempt that may have taken effect.
    CompensateUncertain,
}

/// One capability-bound owner action issued only to the installed process-local registry.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidMediaExecutionTicket {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Opaque capability retained by the native in-flight execution.
    pub capability: String,
    /// Registry generation. Replacement invalidates all older tickets.
    pub executor_generation: u64,
    /// Exact prepared action identity.
    pub action_id: String,
    /// Live provider epoch.
    pub authority_epoch_id: String,
    /// Current media-acceptance authority revision.
    pub media_acceptance_authority_revision: u64,
    /// Runtime revision reviewed while preparing the action.
    pub expected_runtime_revision: u64,
    /// Exact admitted client.
    pub client_id: String,
    /// Exact current client lease.
    pub lease_id: String,
    /// One-based owner position in this action.
    pub sequence: u32,
    /// High-level Start or Stop operation.
    pub operation: MediaStreamPlatformOperation,
    /// Durable owner family.
    pub owner_kind: MediaStreamOwnerKind,
    /// Exact owner-local transition.
    pub action_kind: MediaStreamOwnerActionKind,
    /// Selected owner identity.
    pub owner_id: String,
    /// Selected concrete provider family.
    pub provider_kind: String,
    /// Selected resource identity.
    pub resource_id: String,
}

/// Android registry readback. All ticket fields are echoed and validated before
/// conversion to the generic serialization-only provider evidence.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidMediaOwnerReadback {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact native-owned in-flight capability.
    pub capability: String,
    /// Exact installed registry generation.
    pub executor_generation: u64,
    /// Exact prepared action identity.
    pub action_id: String,
    /// Live provider epoch.
    pub authority_epoch_id: String,
    /// Current media-acceptance authority revision.
    pub media_acceptance_authority_revision: u64,
    /// Runtime revision reviewed while preparing the action.
    pub expected_runtime_revision: u64,
    /// Exact admitted client.
    pub client_id: String,
    /// Exact current client lease.
    pub lease_id: String,
    /// One-based owner position in this action.
    pub sequence: u32,
    /// High-level Start or Stop operation.
    pub operation: MediaStreamPlatformOperation,
    /// Durable owner family.
    pub owner_kind: MediaStreamOwnerKind,
    /// Exact owner-local transition.
    pub action_kind: MediaStreamOwnerActionKind,
    /// Selected owner identity.
    pub owner_id: String,
    /// Selected concrete provider family.
    pub provider_kind: String,
    /// Selected resource identity.
    pub resource_id: String,
    /// Provider-owned non-empty handle.
    pub provider_handle_id: String,
    /// Strictly positive provider-local revision.
    pub provider_state_revision: u64,
    /// State observed from the provider registry.
    pub observed_state: String,
    /// Provider-authored non-empty receipt identity.
    pub receipt_id: String,
}

/// Process-local owner executor. Implementations must authenticate readback
/// against their own retained registry; caller JSON never implements this trait.
pub trait AndroidMediaOwnerExecutor: Send {
    /// Current nonzero registry generation.
    fn executor_generation(&self) -> u64;

    /// Executes or compensates one exact ticket and reads provider state back.
    ///
    /// # Errors
    ///
    /// Returns a provider-specific failure while preserving the caller's
    /// obligation to compensate any uncertain side effect.
    fn execute(
        &mut self,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AndroidMediaOwnerReadback, String>;

    /// Rechecks the receipt against the same retained registry instance.
    fn verify(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        readback: &AndroidMediaOwnerReadback,
    ) -> bool;
}

/// Deterministic in-memory executor available only to explicit test/conformance builds.
#[cfg(feature = "test-support")]
pub struct DeterministicAndroidMediaOwnerExecutor {
    generation: u64,
    revision: u64,
    handles: BTreeMap<(String, String), String>,
    verified: BTreeMap<String, AndroidMediaOwnerReadback>,
}

#[cfg(feature = "test-support")]
impl DeterministicAndroidMediaOwnerExecutor {
    /// Creates a deterministic executor under a nonzero generation.
    ///
    /// # Errors
    ///
    /// Rejects generation zero.
    pub fn new(generation: u64) -> Result<Self, AndroidMediaContractError> {
        if generation == 0 {
            return Err(AndroidMediaContractError::InvalidAuthority);
        }
        Ok(Self {
            generation,
            revision: 0,
            handles: BTreeMap::new(),
            verified: BTreeMap::new(),
        })
    }
}

#[cfg(feature = "test-support")]
impl AndroidMediaOwnerExecutor for DeterministicAndroidMediaOwnerExecutor {
    fn executor_generation(&self) -> u64 {
        self.generation
    }

    fn execute(
        &mut self,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AndroidMediaOwnerReadback, String> {
        if ticket.executor_generation != self.generation {
            return Err("stale executor generation".to_owned());
        }
        self.revision = self
            .revision
            .checked_add(1)
            .ok_or_else(|| "provider revision exhausted".to_owned())?;
        let key = (ticket.owner_id.clone(), ticket.resource_id.clone());
        let starting = matches!(
            ticket.action_kind,
            MediaStreamOwnerActionKind::ArmReceiver
                | MediaStreamOwnerActionKind::ArmCleanup
                | MediaStreamOwnerActionKind::Start
        ) && mode == AndroidMediaExecutionMode::Execute;
        let handle = if starting {
            let handle = format!("test-handle.{}.{}", ticket.owner_id, ticket.resource_id);
            if self.handles.insert(key.clone(), handle.clone()).is_some() {
                return Err("test handle already active".to_owned());
            }
            handle
        } else if mode == AndroidMediaExecutionMode::CompensateUncertain {
            self.handles.remove(&key).unwrap_or_else(|| {
                format!("test-handle.{}.{}", ticket.owner_id, ticket.resource_id)
            })
        } else {
            self.handles
                .remove(&key)
                .ok_or_else(|| "test handle absent".to_owned())?
        };
        let observed_state = match ticket.action_kind {
            MediaStreamOwnerActionKind::ArmReceiver => "receiver_armed",
            MediaStreamOwnerActionKind::ArmCleanup => "cleanup_armed",
            MediaStreamOwnerActionKind::Start => "started",
            MediaStreamOwnerActionKind::Stop => "stopped",
            MediaStreamOwnerActionKind::Cleanup => "cleaned",
        }
        .to_owned();
        let readback = AndroidMediaOwnerReadback {
            schema_id: ANDROID_MEDIA_READBACK_SCHEMA.to_owned(),
            capability: ticket.capability.clone(),
            executor_generation: ticket.executor_generation,
            action_id: ticket.action_id.clone(),
            authority_epoch_id: ticket.authority_epoch_id.clone(),
            media_acceptance_authority_revision: ticket.media_acceptance_authority_revision,
            expected_runtime_revision: ticket.expected_runtime_revision,
            client_id: ticket.client_id.clone(),
            lease_id: ticket.lease_id.clone(),
            sequence: ticket.sequence,
            operation: ticket.operation,
            owner_kind: ticket.owner_kind,
            action_kind: ticket.action_kind,
            owner_id: ticket.owner_id.clone(),
            provider_kind: ticket.provider_kind.clone(),
            resource_id: ticket.resource_id.clone(),
            provider_handle_id: handle,
            provider_state_revision: self.revision,
            observed_state,
            receipt_id: format!(
                "test-receipt.{}.{}.{}",
                ticket.action_id, ticket.sequence, self.revision
            ),
        };
        self.verified
            .insert(ticket.capability.clone(), readback.clone());
        Ok(readback)
    }

    fn verify(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        readback: &AndroidMediaOwnerReadback,
    ) -> bool {
        self.verified.get(&ticket.capability) == Some(readback)
    }
}

/// Closed contract validation error.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AndroidMediaContractError {
    /// Executor generation or capability is invalid.
    InvalidAuthority,
    /// Owner sequence is outside the exact action.
    InvalidSequence,
    /// Readback schema differs from the closed schema.
    SchemaMismatch,
    /// Readback does not echo every authoritative ticket field.
    TicketMismatch,
    /// Provider evidence is empty or otherwise structurally invalid.
    InvalidProviderEvidence,
    /// A submitted timestamp, eye, generation, lease, or release transition is invalid.
    FrameContractMismatch,
    /// A bounded pair expired before both exact submitted timestamps arrived.
    PairExpired,
}

impl fmt::Display for AndroidMediaContractError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::InvalidAuthority => "invalid Android media executor authority",
            Self::InvalidSequence => "invalid Android media owner sequence",
            Self::SchemaMismatch => "Android media contract schema mismatch",
            Self::TicketMismatch => "Android media readback does not match execution ticket",
            Self::InvalidProviderEvidence => "invalid Android media provider evidence",
            Self::FrameContractMismatch => "Android media frame contract mismatch",
            Self::PairExpired => "Android media stereo pair expired",
        })
    }
}

/// Eye identity in one strict stereo pair.
#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidMediaEye {
    /// Left eye.
    Left,
    /// Right eye.
    Right,
}

/// One submitted frame whose presentation timestamp is an exact correlation key.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidMediaFrameSubmission {
    /// Stream generation that owns the frame.
    pub generation: u64,
    /// Exact active lease.
    pub lease_id: String,
    /// Pair identity assigned before submission.
    pub pair_id: u64,
    /// Eye identity.
    pub eye: AndroidMediaEye,
    /// Exact submitted presentation timestamp.
    pub presentation_time_us: i64,
    /// Monotonic submission time used only for bounded expiry.
    pub submitted_at_ns: u64,
    /// Opaque frame handle released exactly once.
    pub frame_handle_id: String,
}

/// Completed exact stereo pair.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct AndroidMediaStereoPair {
    /// Pair identity.
    pub pair_id: u64,
    /// Exact left-eye frame.
    pub left: AndroidMediaFrameSubmission,
    /// Exact right-eye frame.
    pub right: AndroidMediaFrameSubmission,
}

/// Bounded exact-PTS association and exactly-once release authority.
pub struct AndroidMediaPairRuntime {
    generation: u64,
    lease_id: String,
    max_pair_age_ns: u64,
    pending_by_pts: BTreeMap<(AndroidMediaEye, i64), AndroidMediaFrameSubmission>,
    released_handles: BTreeSet<String>,
}

impl AndroidMediaPairRuntime {
    /// Creates a nonzero generation under one exact lease and expiry bound.
    ///
    /// # Errors
    ///
    /// Rejects zero generation/bound or an empty lease.
    pub fn new(
        generation: u64,
        lease_id: String,
        max_pair_age_ns: u64,
    ) -> Result<Self, AndroidMediaContractError> {
        if generation == 0 || lease_id.trim().is_empty() || max_pair_age_ns == 0 {
            return Err(AndroidMediaContractError::FrameContractMismatch);
        }
        Ok(Self {
            generation,
            lease_id,
            max_pair_age_ns,
            pending_by_pts: BTreeMap::new(),
            released_handles: BTreeSet::new(),
        })
    }

    /// Inserts one frame under its exact submitted PTS. No nearest-timestamp fallback exists.
    ///
    /// # Errors
    ///
    /// Rejects stale generation/lease, duplicate PTS or handle, empty handles, and expired input.
    pub fn submit(
        &mut self,
        frame: AndroidMediaFrameSubmission,
        now_ns: u64,
    ) -> Result<Option<AndroidMediaStereoPair>, AndroidMediaContractError> {
        if frame.generation != self.generation
            || frame.lease_id != self.lease_id
            || frame.frame_handle_id.trim().is_empty()
            || self.released_handles.contains(&frame.frame_handle_id)
            || self
                .pending_by_pts
                .contains_key(&(frame.eye, frame.presentation_time_us))
            || self.pending_by_pts.values().any(|pending| {
                pending.frame_handle_id == frame.frame_handle_id
                    || (pending.pair_id == frame.pair_id && pending.eye == frame.eye)
            })
            || self.pending_by_pts.len() >= 32
        {
            return Err(AndroidMediaContractError::FrameContractMismatch);
        }
        if now_ns.saturating_sub(frame.submitted_at_ns) > self.max_pair_age_ns {
            return Err(AndroidMediaContractError::PairExpired);
        }
        let counterpart = self.pending_by_pts.iter().find_map(|(key, pending)| {
            (pending.pair_id == frame.pair_id && pending.eye != frame.eye).then_some(*key)
        });
        if let Some(counterpart_key) = counterpart {
            if now_ns.saturating_sub(self.pending_by_pts[&counterpart_key].submitted_at_ns)
                > self.max_pair_age_ns
            {
                return Err(AndroidMediaContractError::PairExpired);
            }
            let other = self
                .pending_by_pts
                .remove(&counterpart_key)
                .ok_or(AndroidMediaContractError::FrameContractMismatch)?;
            let (left, right) = if frame.eye == AndroidMediaEye::Left {
                (frame, other)
            } else {
                (other, frame)
            };
            return Ok(Some(AndroidMediaStereoPair {
                pair_id: left.pair_id,
                left,
                right,
            }));
        }
        self.pending_by_pts
            .insert((frame.eye, frame.presentation_time_us), frame);
        Ok(None)
    }

    /// Cancels the current generation, returns every outstanding handle, and advances authority.
    ///
    /// # Errors
    ///
    /// Rejects a stale current generation or non-increasing replacement generation.
    pub fn cancel_generation(
        &mut self,
        generation: u64,
        replacement_generation: u64,
    ) -> Result<Vec<String>, AndroidMediaContractError> {
        if generation != self.generation || replacement_generation <= generation {
            return Err(AndroidMediaContractError::FrameContractMismatch);
        }
        let handles = self
            .pending_by_pts
            .values()
            .map(|frame| frame.frame_handle_id.clone())
            .collect();
        self.pending_by_pts.clear();
        self.generation = replacement_generation;
        Ok(handles)
    }

    /// Expires incomplete pairs by monotonic age and returns every handle whose
    /// owner must perform exact once-only release.
    #[must_use]
    pub fn expire_pairs(&mut self, now_ns: u64) -> Vec<String> {
        let expired: Vec<_> = self
            .pending_by_pts
            .iter()
            .filter_map(|(key, frame)| {
                (now_ns.saturating_sub(frame.submitted_at_ns) > self.max_pair_age_ns)
                    .then_some(*key)
            })
            .collect();
        expired
            .into_iter()
            .filter_map(|key| self.pending_by_pts.remove(&key))
            .map(|frame| frame.frame_handle_id)
            .collect()
    }

    /// Records exact once-only ownership release.
    ///
    /// # Errors
    ///
    /// Rejects an empty or previously released handle.
    pub fn release(&mut self, handle_id: &str) -> Result<(), AndroidMediaContractError> {
        if handle_id.trim().is_empty() || !self.released_handles.insert(handle_id.to_owned()) {
            return Err(AndroidMediaContractError::FrameContractMismatch);
        }
        Ok(())
    }
}

/// Deterministic camera-free/network-free host conformance report.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct AndroidMediaConformanceReport {
    /// Report schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact PTS and stereo pairing checks passed.
    pub exact_pair_contract_passed: bool,
    /// Stale generation and lease checks passed.
    pub generation_lease_contract_passed: bool,
    /// Exactly-once release checks passed.
    pub exactly_once_release_passed: bool,
    /// This host exercise used no camera or network.
    pub external_effects: Vec<String>,
}

/// Runs a deterministic in-memory conformance exercise without camera, network, or Android services.
///
/// # Errors
///
/// Returns the first strict-contract failure.
pub fn run_synthetic_android_conformance(
) -> Result<AndroidMediaConformanceReport, AndroidMediaContractError> {
    let mut runtime = AndroidMediaPairRuntime::new(7, "lease.conformance".to_owned(), 100)?;
    let frame = |eye: AndroidMediaEye, pts: i64, handle: &str| AndroidMediaFrameSubmission {
        generation: 7,
        lease_id: "lease.conformance".to_owned(),
        pair_id: 11,
        eye,
        presentation_time_us: pts,
        submitted_at_ns: 1_000,
        frame_handle_id: handle.to_owned(),
    };
    if runtime
        .submit(frame(AndroidMediaEye::Left, 40, "frame.left"), 1_050)?
        .is_some()
    {
        return Err(AndroidMediaContractError::FrameContractMismatch);
    }
    let pair = runtime
        .submit(frame(AndroidMediaEye::Right, 42, "frame.right"), 1_050)?
        .ok_or(AndroidMediaContractError::FrameContractMismatch)?;
    // Different exact PTS values are accepted only because pair_id and opposite eye bind them;
    // lookup itself never substitutes a neighboring timestamp.
    if pair.left.presentation_time_us != 40 || pair.right.presentation_time_us != 42 {
        return Err(AndroidMediaContractError::FrameContractMismatch);
    }
    runtime.release(&pair.left.frame_handle_id)?;
    runtime.release(&pair.right.frame_handle_id)?;
    if runtime.release(&pair.left.frame_handle_id).is_ok() {
        return Err(AndroidMediaContractError::FrameContractMismatch);
    }
    let expiring = AndroidMediaFrameSubmission {
        generation: 7,
        lease_id: "lease.conformance".to_owned(),
        pair_id: 12,
        eye: AndroidMediaEye::Left,
        presentation_time_us: 60,
        submitted_at_ns: 1_000,
        frame_handle_id: "frame.expiring".to_owned(),
    };
    runtime.submit(expiring, 1_050)?;
    let expired = runtime.expire_pairs(1_101);
    if expired != ["frame.expiring"] {
        return Err(AndroidMediaContractError::FrameContractMismatch);
    }
    runtime.release(&expired[0])?;
    runtime.cancel_generation(7, 8)?;
    if runtime
        .submit(frame(AndroidMediaEye::Left, 50, "frame.stale"), 1_050)
        .is_ok()
    {
        return Err(AndroidMediaContractError::FrameContractMismatch);
    }
    Ok(AndroidMediaConformanceReport {
        schema_id: "rusty.quest.android.media.conformance.v1".to_owned(),
        exact_pair_contract_passed: true,
        generation_lease_contract_passed: true,
        exactly_once_release_passed: true,
        external_effects: Vec::new(),
    })
}

impl std::error::Error for AndroidMediaContractError {}

/// Creates the exact ticket for one owner position.
///
/// # Errors
///
/// Rejects an empty capability, zero generation, invalid sequence, or an owner
/// action that is not byte-for-byte equal to the action's selected position.
pub fn execution_ticket(
    action: &MediaStreamPlatformAction,
    owner_action: &MediaStreamOwnerAction,
    sequence: u32,
    executor_generation: u64,
    capability: String,
) -> Result<AndroidMediaExecutionTicket, AndroidMediaContractError> {
    if executor_generation == 0 || capability.trim().is_empty() {
        return Err(AndroidMediaContractError::InvalidAuthority);
    }
    let index = usize::try_from(sequence)
        .ok()
        .and_then(|value| value.checked_sub(1))
        .ok_or(AndroidMediaContractError::InvalidSequence)?;
    if action.owner_actions.get(index) != Some(owner_action) {
        return Err(AndroidMediaContractError::InvalidSequence);
    }
    Ok(AndroidMediaExecutionTicket {
        schema_id: ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA.to_owned(),
        capability,
        executor_generation,
        action_id: action.action_id.clone(),
        authority_epoch_id: action.authority_epoch_id.clone(),
        media_acceptance_authority_revision: action.media_acceptance_authority_revision,
        expected_runtime_revision: action.expected_runtime_revision,
        client_id: action.client_authority.client_id.clone(),
        lease_id: action.client_authority.lease_id.clone(),
        sequence,
        operation: action.operation,
        owner_kind: owner_action.selection.owner_kind,
        action_kind: owner_action.action_kind,
        owner_id: owner_action.selection.owner_id.clone(),
        provider_kind: owner_action.selection.provider_kind.clone(),
        resource_id: owner_action.selection.resource_id.clone(),
    })
}

/// Validates an Android readback and converts it to the generic evidence type
/// accepted only through a trusted Rust owner provider callback.
///
/// # Errors
///
/// Rejects schema drift, any ticket-field mismatch, or empty/zero provider evidence.
pub fn validate_readback(
    ticket: &AndroidMediaExecutionTicket,
    readback: &AndroidMediaOwnerReadback,
) -> Result<MediaStreamOwnerProviderReadback, AndroidMediaContractError> {
    if ticket.schema_id != ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA
        || readback.schema_id != ANDROID_MEDIA_READBACK_SCHEMA
    {
        return Err(AndroidMediaContractError::SchemaMismatch);
    }
    if readback.capability != ticket.capability
        || readback.executor_generation != ticket.executor_generation
        || readback.action_id != ticket.action_id
        || readback.authority_epoch_id != ticket.authority_epoch_id
        || readback.media_acceptance_authority_revision
            != ticket.media_acceptance_authority_revision
        || readback.expected_runtime_revision != ticket.expected_runtime_revision
        || readback.client_id != ticket.client_id
        || readback.lease_id != ticket.lease_id
        || readback.sequence != ticket.sequence
        || readback.operation != ticket.operation
        || readback.owner_kind != ticket.owner_kind
        || readback.action_kind != ticket.action_kind
        || readback.owner_id != ticket.owner_id
        || readback.provider_kind != ticket.provider_kind
        || readback.resource_id != ticket.resource_id
    {
        return Err(AndroidMediaContractError::TicketMismatch);
    }
    if readback.provider_handle_id.trim().is_empty()
        || readback.provider_state_revision == 0
        || readback.observed_state.trim().is_empty()
        || readback.receipt_id.trim().is_empty()
    {
        return Err(AndroidMediaContractError::InvalidProviderEvidence);
    }
    Ok(MediaStreamOwnerProviderReadback {
        action_id: readback.action_id.clone(),
        authority_epoch_id: readback.authority_epoch_id.clone(),
        media_acceptance_authority_revision: readback.media_acceptance_authority_revision,
        client_id: readback.client_id.clone(),
        lease_id: readback.lease_id.clone(),
        provider_kind: readback.provider_kind.clone(),
        resource_id: readback.resource_id.clone(),
        provider_handle_id: readback.provider_handle_id.clone(),
        provider_state_revision: readback.provider_state_revision,
        observed_state: readback.observed_state.clone(),
        receipt_id: readback.receipt_id.clone(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_quest_media_stream::{
        MediaStreamClientAuthorityBinding, MediaStreamOwnerSelection,
        MEDIA_STREAM_PLATFORM_ACTION_SCHEMA,
    };

    fn action() -> MediaStreamPlatformAction {
        MediaStreamPlatformAction {
            schema_id: MEDIA_STREAM_PLATFORM_ACTION_SCHEMA.to_owned(),
            action_id: "action.test".to_owned(),
            authority_epoch_id: "epoch.test".to_owned(),
            operation: MediaStreamPlatformOperation::Start,
            client_authority: MediaStreamClientAuthorityBinding {
                client_id: "client.test".to_owned(),
                lease_id: "lease.test".to_owned(),
                product_id: "product.test".to_owned(),
                feature_lock_id: "lock.test".to_owned(),
                feature_lock_fingerprint: "sha256:test".to_owned(),
                session_capability_id: "cap.session".to_owned(),
                session_admission_grant_id: "grant.session".to_owned(),
                operation_capability_id: "cap.operation".to_owned(),
                operation_admission_grant_id: "grant.operation".to_owned(),
                operation_admission_use_request_id: "use.operation".to_owned(),
            },
            runtime_spec_id: "runtime.test".to_owned(),
            runtime_spec_canonical_sha256: "sha256:runtime".to_owned(),
            manifold_descriptor_canonical_sha256: "sha256:descriptor".to_owned(),
            manifold_decision_id: "decision.test".to_owned(),
            manifold_session_revision: 3,
            media_acceptance_authority_revision: 4,
            expected_runtime_revision: 5,
            owner_actions: vec![MediaStreamOwnerAction {
                selection: MediaStreamOwnerSelection {
                    owner_kind: MediaStreamOwnerKind::Source,
                    owner_id: "owner.source".to_owned(),
                    resource_id: "source.left".to_owned(),
                    lane_id: None,
                    provider_kind: "provider.camera".to_owned(),
                },
                action_kind: MediaStreamOwnerActionKind::Start,
            }],
        }
    }

    fn readback(ticket: &AndroidMediaExecutionTicket) -> AndroidMediaOwnerReadback {
        AndroidMediaOwnerReadback {
            schema_id: ANDROID_MEDIA_READBACK_SCHEMA.to_owned(),
            capability: ticket.capability.clone(),
            executor_generation: ticket.executor_generation,
            action_id: ticket.action_id.clone(),
            authority_epoch_id: ticket.authority_epoch_id.clone(),
            media_acceptance_authority_revision: ticket.media_acceptance_authority_revision,
            expected_runtime_revision: ticket.expected_runtime_revision,
            client_id: ticket.client_id.clone(),
            lease_id: ticket.lease_id.clone(),
            sequence: ticket.sequence,
            operation: ticket.operation,
            owner_kind: ticket.owner_kind,
            action_kind: ticket.action_kind,
            owner_id: ticket.owner_id.clone(),
            provider_kind: ticket.provider_kind.clone(),
            resource_id: ticket.resource_id.clone(),
            provider_handle_id: "handle.source".to_owned(),
            provider_state_revision: 1,
            observed_state: "started".to_owned(),
            receipt_id: "receipt.source".to_owned(),
        }
    }

    #[test]
    fn ticket_and_readback_bind_every_authority_field() {
        let action = action();
        let ticket = execution_ticket(
            &action,
            &action.owner_actions[0],
            1,
            7,
            "opaque.capability".to_owned(),
        )
        .expect("ticket");
        assert!(validate_readback(&ticket, &readback(&ticket)).is_ok());
        let mut stale = readback(&ticket);
        stale.expected_runtime_revision += 1;
        assert_eq!(
            validate_readback(&ticket, &stale),
            Err(AndroidMediaContractError::TicketMismatch)
        );
    }

    #[test]
    fn ticket_rejects_wrong_sequence_and_absent_authority() {
        let action = action();
        assert_eq!(
            execution_ticket(&action, &action.owner_actions[0], 2, 7, "cap".to_owned()),
            Err(AndroidMediaContractError::InvalidSequence)
        );
        assert_eq!(
            execution_ticket(&action, &action.owner_actions[0], 1, 0, String::new()),
            Err(AndroidMediaContractError::InvalidAuthority)
        );
    }

    #[test]
    fn synthetic_conformance_is_effect_free_and_strict() {
        let report = run_synthetic_android_conformance().expect("conformance");
        assert!(report.exact_pair_contract_passed);
        assert!(report.generation_lease_contract_passed);
        assert!(report.exactly_once_release_passed);
        assert!(report.external_effects.is_empty());
    }
}
