//! Exact package and evidence closure for independent Quest media consumers.
//!
//! The shared client SDK owns only immutable bindings and validation. Manifold
//! retains accepted session authority; each selected app-local Rust/NDK runtime
//! owns provider handles, rendering, stop, and cleanup. No Java completion
//! assertion is accepted here.

use std::{collections::BTreeSet, fs, path::Path};

use rusty_manifold_media_session::{
    ManifoldAcceptedMediaSession, ManifoldMediaSessionAcceptanceRejectionReason,
    ManifoldMediaSessionCurrentReceipt, ManifoldMediaSessionLifecycleStatus,
    MANIFOLD_ACCEPTED_MEDIA_SESSION_SCHEMA, MANIFOLD_MEDIA_SESSION_CURRENT_RECEIPT_SCHEMA,
};
use rusty_quest_broker_contracts::{
    validate_media_lifecycle_package, BrokerMediaLifecycleLock, BrokerMediaLifecyclePackageBinding,
    BrokerMediaProductBindingDocument,
};
use rusty_quest_media_stream::{
    MediaStreamClientAuthorityBinding, MediaStreamOwnerActionKind, MediaStreamOwnerSelection,
    MediaStreamPlatformAction, MediaStreamPlatformApplicationReceipt, MediaStreamPlatformOperation,
    MediaStreamRuntimePhase, MEDIA_STREAM_PLATFORM_ACTION_SCHEMA,
    MEDIA_STREAM_PLATFORM_APPLICATION_SCHEMA, MEDIA_STREAM_RUNTIME_AUTHORITY,
    MEDIA_STREAM_RUNTIME_DECISION_SCHEMA,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Typed full lifecycle evidence schema.
pub const BROKER_MEDIA_LIFECYCLE_EVIDENCE_SCHEMA: &str =
    "rusty.quest.broker_media_lifecycle_evidence.v1";
/// Derived accepted lifecycle receipt schema.
pub const BROKER_MEDIA_LIFECYCLE_RECEIPT_SCHEMA: &str =
    "rusty.quest.broker_media_lifecycle_receipt.v1";
const RUNTIME_HOST_AUTHORITY_OWNER: &str = "module.runtime.host";

/// One owner-issued start/stop handle lifecycle retained for release evidence.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaOwnerLifecycleReceipt {
    /// Exact selected owner tuple.
    pub selection: MediaStreamOwnerSelection,
    /// Exact start action.
    pub start_action_kind: MediaStreamOwnerActionKind,
    /// Owner-issued start receipt.
    pub start_receipt_id: String,
    /// Exact start completion sequence.
    pub start_completion_sequence: u32,
    /// Provider-owned handle established by start.
    pub provider_handle_id: String,
    /// Provider revision after start.
    pub start_provider_state_revision: u64,
    /// Provider state read back after start.
    pub start_observed_state: String,
    /// Exact stop action.
    pub stop_action_kind: MediaStreamOwnerActionKind,
    /// Owner-issued stop receipt.
    pub stop_receipt_id: String,
    /// Exact stop completion sequence.
    pub stop_completion_sequence: u32,
    /// Provider revision after stop/cleanup.
    pub stop_provider_state_revision: u64,
    /// Provider state read back after stop/cleanup.
    pub stop_observed_state: String,
}

/// Fresh platform media/render artifacts for one exact app/run.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaRenderEvidence {
    /// Exact accepted provider epoch.
    pub provider_epoch_id: String,
    /// Exact accepted session.
    pub session_id: String,
    /// Exact accepted stream.
    pub stream_id: String,
    /// Exact accepted app sink.
    pub render_sink_id: String,
    /// Exact app marker namespace.
    pub marker_namespace: String,
    /// Absolute fresh frame/scorecard artifact path.
    pub frame_evidence_path: String,
    /// SHA-256 of exact frame/scorecard artifact bytes.
    pub frame_evidence_sha256: String,
    /// Absolute fresh platform marker artifact path.
    pub marker_evidence_path: String,
    /// SHA-256 of exact marker artifact bytes.
    pub marker_evidence_sha256: String,
    /// Receiver-observed media bytes.
    pub receiver_observed_bytes: u64,
    /// App-rendered final-window frames.
    pub rendered_frame_count: u64,
}

/// Full independently validated app lifecycle, recovery, and cleanup evidence.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaLifecycleEvidence {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact client identity.
    pub client_id: String,
    /// Exact packaged product-lock digest.
    pub product_lock_sha256: String,
    /// Exact packaged broker client-lock digest.
    pub client_lock_sha256: String,
    /// Exact packaged app feature-lock digest.
    pub app_feature_lock_sha256: String,
    /// Exact packaged media lifecycle-lock digest.
    pub media_lifecycle_lock_sha256: String,
    /// Exact packaged media-binding digest.
    pub media_binding_sha256: String,
    /// Subject-current Manifold validation receipt, including global CAS
    /// revision and exact retained command/client/lease provenance.
    pub current_acceptance: ManifoldMediaSessionCurrentReceipt,
    /// Rust-prepared start action.
    pub start_action: MediaStreamPlatformAction,
    /// Rust-authored start application receipt.
    pub start_application: MediaStreamPlatformApplicationReceipt,
    /// Rust-prepared stop action.
    pub stop_action: MediaStreamPlatformAction,
    /// Rust-authored stop application receipt.
    pub stop_application: MediaStreamPlatformApplicationReceipt,
    /// Seven exact owner-issued start/stop handle lifecycles.
    pub owner_receipts: Vec<BrokerMediaOwnerLifecycleReceipt>,
    /// Status receipt identity.
    pub status_receipt_id: String,
    /// Stream-subscription receipt identity.
    pub subscription_receipt_id: String,
    /// Fresh platform media/render evidence.
    pub render: BrokerMediaRenderEvidence,
    /// Lease-release receipt identity.
    pub release_receipt_id: String,
    /// Exact lease was released.
    pub lease_released: bool,
    /// Client-process death was observed for this app.
    pub app_death_observed: bool,
    /// Same-provider rebind epoch.
    pub rebind_provider_epoch_id: String,
    /// Authority revision before app rebind.
    pub pre_rebind_authority_revision: u64,
    /// Authority revision after app rebind.
    pub post_rebind_authority_revision: u64,
    /// Fresh provider epoch after deliberate provider restart.
    pub restarted_provider_epoch_id: String,
    /// Old-epoch action/receipt was rejected after restart.
    pub old_epoch_rejected: bool,
    /// Product packages and platform resources were cleaned.
    pub cleanup_complete: bool,
    /// Package/native fatal count in the bounded run window.
    pub package_fatal_count: u32,
    /// System/platform fatal count in the bounded run window.
    pub system_fatal_count: u32,
}

/// Compact receipt emitted only after all package, lifecycle, and live gates.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaLifecycleReceipt {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact app/client identity.
    pub client_id: String,
    /// Exact retained decision identity.
    pub decision_id: String,
    /// Exact accepted session revision.
    pub session_authority_revision: u64,
    /// Exact media acceptance-state revision.
    pub media_acceptance_authority_revision: u64,
    /// Exact canonical descriptor digest.
    pub manifold_descriptor_canonical_sha256: String,
    /// Exact provider epoch.
    pub provider_epoch_id: String,
    /// Exact runtime specification.
    pub runtime_spec_id: String,
    /// Exact app render sink.
    pub render_sink_id: String,
    /// Exact package input digests in product/client/feature/media-lock/binding order.
    pub package_sha256: Vec<String>,
    /// Seven owner handle lifecycles passed.
    pub owner_lifecycle_count: u32,
    /// Receiver-observed bytes.
    pub receiver_observed_bytes: u64,
    /// Rendered frames.
    pub rendered_frame_count: u64,
    /// Client rebind preserved epoch and revision.
    pub rebind_continuity: bool,
    /// Provider restart produced a fresh epoch and rejected stale work.
    pub provider_restart_recovery: bool,
    /// Stop, release, and cleanup passed.
    pub cleanup_complete: bool,
    /// Bounded package/system fatal count.
    pub fatal_count: u32,
}

/// Runtime-owner completion response projected by the broker authority. This
/// crate duplicates only the source-neutral evidence shape so Android/Java can
/// remain a transport and never become lifecycle authority.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaLifecycleCompletionResponse {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact provider epoch.
    pub provider_epoch_id: String,
    /// Transport owns no local acceptance rule.
    pub local_acceptance_rules: bool,
    /// Sole decision/completion owner.
    pub decision_owner_id: String,
    /// Exact client identity.
    pub client_id: String,
    /// Retained current Manifold session receipt.
    pub current_acceptance: ManifoldMediaSessionCurrentReceipt,
    /// Prepared action completed by owner callbacks.
    pub action: MediaStreamPlatformAction,
    /// Exact owner-issued completion receipts.
    pub owner_receipts: Vec<BrokerMediaOwnerCompletionEvidence>,
    /// Rust-authored application receipt.
    pub application: MediaStreamPlatformApplicationReceipt,
    /// True only after Rust applied all owner completions.
    pub platform_effect_completed: bool,
}

/// JSON evidence mirror of a Rust-emitted owner completion. This is separate
/// from the runtime authority type so deserialized aggregates cannot be fed
/// back into `rusty-quest-media-stream` as trusted owner completions.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaOwnerCompletionEvidence {
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

/// App/runtime evidence that is not owned by the command-completion response
/// but must be bound before a NET-016 lifecycle receipt can be reduced.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct BrokerMediaLifecycleAssemblyEvidence {
    /// Status receipt identity.
    pub status_receipt_id: String,
    /// Stream-subscription receipt identity.
    pub subscription_receipt_id: String,
    /// Fresh platform media/render evidence.
    pub render: BrokerMediaRenderEvidence,
    /// Lease-release receipt identity.
    pub release_receipt_id: String,
    /// Exact lease was released.
    pub lease_released: bool,
    /// Client-process death was observed.
    pub app_death_observed: bool,
    /// Same-provider rebind epoch.
    pub rebind_provider_epoch_id: String,
    /// Authority revision before app rebind.
    pub pre_rebind_authority_revision: u64,
    /// Authority revision after app rebind.
    pub post_rebind_authority_revision: u64,
    /// Fresh provider epoch after deliberate provider restart.
    pub restarted_provider_epoch_id: String,
    /// Old-epoch action/receipt was rejected after restart.
    pub old_epoch_rejected: bool,
    /// Product packages and platform resources were cleaned.
    pub cleanup_complete: bool,
    /// Package/native fatal count in the bounded run window.
    pub package_fatal_count: u32,
    /// System/platform fatal count in the bounded run window.
    pub system_fatal_count: u32,
}

/// Builds the exact reducer input from Rust-owned start/stop completions plus
/// separately observed render/recovery evidence.
pub fn assemble_media_lifecycle_evidence(
    package: &BrokerMediaLifecyclePackageBinding,
    start: &BrokerMediaLifecycleCompletionResponse,
    stop: &BrokerMediaLifecycleCompletionResponse,
    assembly: BrokerMediaLifecycleAssemblyEvidence,
) -> Result<BrokerMediaLifecycleEvidence, Vec<String>> {
    let validated = validate_media_lifecycle_package(package)?;
    let lock = &validated.lifecycle;
    let mut errors = Vec::new();
    validate_completion(
        "start",
        lock,
        start,
        MediaStreamPlatformOperation::Start,
        &mut errors,
    );
    validate_completion(
        "stop",
        lock,
        stop,
        MediaStreamPlatformOperation::Stop,
        &mut errors,
    );
    if !same_retained_session_lifecycle(&start.current_acceptance, &stop.current_acceptance) {
        errors.push(
            "start/stop completions do not share the same retained current session".to_string(),
        );
    }
    if start.provider_epoch_id != stop.provider_epoch_id {
        errors.push("start/stop provider epoch mismatch".to_string());
    }
    let owner_receipts = owner_lifecycle_receipts(&start.owner_receipts, &stop.owner_receipts);
    if owner_receipts.len() != 7 {
        errors.push(
            "start/stop owner completion families did not pair to seven lifecycles".to_string(),
        );
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    let evidence = BrokerMediaLifecycleEvidence {
        schema_id: BROKER_MEDIA_LIFECYCLE_EVIDENCE_SCHEMA.to_string(),
        client_id: lock.client_id.clone(),
        product_lock_sha256: package.product_lock_sha256.clone(),
        client_lock_sha256: package.client_lock_sha256.clone(),
        app_feature_lock_sha256: package.app_feature_lock_sha256.clone(),
        media_lifecycle_lock_sha256: package.media_lifecycle_lock_sha256.clone(),
        media_binding_sha256: package.media_binding_sha256.clone(),
        current_acceptance: start.current_acceptance.clone(),
        start_action: start.action.clone(),
        start_application: start.application.clone(),
        stop_action: stop.action.clone(),
        stop_application: stop.application.clone(),
        owner_receipts,
        status_receipt_id: assembly.status_receipt_id,
        subscription_receipt_id: assembly.subscription_receipt_id,
        render: assembly.render,
        release_receipt_id: assembly.release_receipt_id,
        lease_released: assembly.lease_released,
        app_death_observed: assembly.app_death_observed,
        rebind_provider_epoch_id: assembly.rebind_provider_epoch_id,
        pre_rebind_authority_revision: assembly.pre_rebind_authority_revision,
        post_rebind_authority_revision: assembly.post_rebind_authority_revision,
        restarted_provider_epoch_id: assembly.restarted_provider_epoch_id,
        old_epoch_rejected: assembly.old_epoch_rejected,
        cleanup_complete: assembly.cleanup_complete,
        package_fatal_count: assembly.package_fatal_count,
        system_fatal_count: assembly.system_fatal_count,
    };
    validate_media_lifecycle_evidence(package, &evidence)?;
    Ok(evidence)
}

/// Validates one full app/run and emits a reducer-ready typed receipt.
pub fn validate_media_lifecycle_evidence(
    package: &BrokerMediaLifecyclePackageBinding,
    evidence: &BrokerMediaLifecycleEvidence,
) -> Result<BrokerMediaLifecycleReceipt, Vec<String>> {
    let validated = validate_media_lifecycle_package(package)?;
    let lock = &validated.lifecycle;
    let media = &validated.media;
    let mut errors = Vec::new();
    if evidence.schema_id != BROKER_MEDIA_LIFECYCLE_EVIDENCE_SCHEMA
        || evidence.client_id != lock.client_id
    {
        errors.push("media lifecycle evidence identity/schema mismatch".to_string());
    }
    for (label, actual, expected) in [
        (
            "product lock",
            evidence.product_lock_sha256.as_str(),
            package.product_lock_sha256.as_str(),
        ),
        (
            "client lock",
            evidence.client_lock_sha256.as_str(),
            package.client_lock_sha256.as_str(),
        ),
        (
            "app feature lock",
            evidence.app_feature_lock_sha256.as_str(),
            package.app_feature_lock_sha256.as_str(),
        ),
        (
            "media lifecycle lock",
            evidence.media_lifecycle_lock_sha256.as_str(),
            package.media_lifecycle_lock_sha256.as_str(),
        ),
        (
            "media binding",
            evidence.media_binding_sha256.as_str(),
            package.media_binding_sha256.as_str(),
        ),
    ] {
        if actual != expected {
            errors.push(format!("{label} evidence digest mismatch"));
        }
    }
    validate_current_session(lock, media, &evidence.current_acceptance, &mut errors);
    validate_actions_and_owner_receipts(lock, evidence, &mut errors);
    let accepted = evidence.current_acceptance.session.as_ref();
    if let Some(accepted) = accepted {
        validate_render(lock, accepted, &evidence.render, &mut errors);
    }
    if evidence.status_receipt_id.trim().is_empty()
        || evidence.subscription_receipt_id.trim().is_empty()
        || evidence.release_receipt_id.trim().is_empty()
        || !evidence.lease_released
        || !evidence.app_death_observed
    {
        errors.push("status/subscription/release/app-death evidence incomplete".to_string());
    }
    let epoch = accepted
        .map(|session| session.provider_epoch_id.as_str())
        .unwrap_or_default();
    if evidence.rebind_provider_epoch_id != epoch
        || evidence.pre_rebind_authority_revision == 0
        || evidence.pre_rebind_authority_revision != evidence.post_rebind_authority_revision
        || evidence.restarted_provider_epoch_id.trim().is_empty()
        || evidence.restarted_provider_epoch_id == epoch
        || !evidence.old_epoch_rejected
    {
        errors.push("client rebind/provider restart recovery evidence mismatch".to_string());
    }
    if !evidence.cleanup_complete
        || evidence.package_fatal_count != 0
        || evidence.system_fatal_count != 0
    {
        errors.push("cleanup/fatal gate failed".to_string());
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    let accepted = accepted.expect("validated current session exists");
    Ok(BrokerMediaLifecycleReceipt {
        schema_id: BROKER_MEDIA_LIFECYCLE_RECEIPT_SCHEMA.to_string(),
        client_id: lock.client_id.clone(),
        decision_id: accepted.decision_id.as_str().to_string(),
        session_authority_revision: accepted.session_authority_revision.get(),
        media_acceptance_authority_revision: evidence
            .current_acceptance
            .acceptance_state_authority_revision
            .get(),
        manifold_descriptor_canonical_sha256: accepted.product_descriptor_canonical_sha256.clone(),
        provider_epoch_id: accepted.provider_epoch_id.as_str().to_string(),
        runtime_spec_id: accepted.platform_runtime_spec_id.as_str().to_string(),
        render_sink_id: lock.render_sink_id.clone(),
        package_sha256: vec![
            evidence.product_lock_sha256.clone(),
            evidence.client_lock_sha256.clone(),
            evidence.app_feature_lock_sha256.clone(),
            evidence.media_lifecycle_lock_sha256.clone(),
            evidence.media_binding_sha256.clone(),
        ],
        owner_lifecycle_count: 7,
        receiver_observed_bytes: evidence.render.receiver_observed_bytes,
        rendered_frame_count: evidence.render.rendered_frame_count,
        rebind_continuity: true,
        provider_restart_recovery: true,
        cleanup_complete: true,
        fatal_count: 0,
    })
}

/// Rejects pair-level identity, lock, lease, session, stream, or sink bleed.
pub fn validate_media_lifecycle_package_pair(
    first: &BrokerMediaLifecyclePackageBinding,
    second: &BrokerMediaLifecyclePackageBinding,
) -> Result<(), Vec<String>> {
    let first = validate_media_lifecycle_package(first)?;
    let second = validate_media_lifecycle_package(second)?;
    let left = &first.lifecycle;
    let right = &second.lifecycle;
    let distinct = left.client_id != right.client_id
        && left.package_name != right.package_name
        && left.broker_client_lock_id != right.broker_client_lock_id
        && left.marker_namespace != right.marker_namespace
        && left.project_id != right.project_id
        && left.app_feature_lock_id != right.app_feature_lock_id
        && left.activation_effective_marker != right.activation_effective_marker
        && left.app_feature_lock_path != right.app_feature_lock_path
        && left.app_feature_lock_sha256 != right.app_feature_lock_sha256
        && left.media_binding_path != right.media_binding_path
        && left.broker_runtime_lease_id != right.broker_runtime_lease_id
        && left.media_runtime_lease_id != right.media_runtime_lease_id
        && left.session_id != right.session_id
        && left.stream_id != right.stream_id
        && left.render_sink_id != right.render_sink_id
        && left.render_sink_capability != right.render_sink_capability
        && left.runtime_spec_id != right.runtime_spec_id
        && left.runtime_spec_canonical_sha256 != right.runtime_spec_canonical_sha256
        && left.manifold_descriptor_canonical_sha256 != right.manifold_descriptor_canonical_sha256;
    if distinct {
        Ok(())
    } else {
        Err(vec![
            "independent app media packages contain copied identity/lock/lease/session/stream/sink authority"
                .to_string(),
        ])
    }
}

fn validate_completion(
    label: &str,
    lock: &BrokerMediaLifecycleLock,
    completion: &BrokerMediaLifecycleCompletionResponse,
    operation: MediaStreamPlatformOperation,
    errors: &mut Vec<String>,
) {
    if completion.schema_id != "rusty.quest.broker.media_completion_response.v1"
        || completion.local_acceptance_rules
        || completion.decision_owner_id != RUNTIME_HOST_AUTHORITY_OWNER
        || completion.client_id != lock.client_id
        || !completion.platform_effect_completed
        || completion.action.operation != operation
        || completion.action.client_authority.client_id != lock.client_id
        || completion.application.action_id != completion.action.action_id
        || !completion.application.platform_effect_completed
        || completion.owner_receipts.len() != 7
    {
        errors.push(format!(
            "{label} completion response is not a Rust-owned media lifecycle completion"
        ));
    }
    if completion.provider_epoch_id != completion.action.authority_epoch_id {
        errors.push(format!(
            "{label} completion provider epoch does not match action epoch"
        ));
    }
}

fn owner_lifecycle_receipts(
    start: &[BrokerMediaOwnerCompletionEvidence],
    stop: &[BrokerMediaOwnerCompletionEvidence],
) -> Vec<BrokerMediaOwnerLifecycleReceipt> {
    start
        .iter()
        .filter_map(|start| {
            let stop = stop
                .iter()
                .find(|candidate| candidate.selection == start.selection)?;
            Some(BrokerMediaOwnerLifecycleReceipt {
                selection: start.selection.clone(),
                start_action_kind: start.action_kind,
                start_receipt_id: start.receipt_id.clone(),
                start_completion_sequence: start.completion_sequence,
                provider_handle_id: start.provider_handle_id.clone(),
                start_provider_state_revision: start.provider_state_revision,
                start_observed_state: start.observed_state.clone(),
                stop_action_kind: stop.action_kind,
                stop_receipt_id: stop.receipt_id.clone(),
                stop_completion_sequence: stop.completion_sequence,
                stop_provider_state_revision: stop.provider_state_revision,
                stop_observed_state: stop.observed_state.clone(),
            })
        })
        .collect()
}

fn same_retained_session_lifecycle(
    start: &ManifoldMediaSessionCurrentReceipt,
    stop: &ManifoldMediaSessionCurrentReceipt,
) -> bool {
    let (Some(start_session), Some(stop_session)) = (start.session.as_ref(), stop.session.as_ref())
    else {
        return false;
    };
    let mut expected_stopped = start_session.clone();
    expected_stopped.lifecycle_status = ManifoldMediaSessionLifecycleStatus::Stopped;
    expected_stopped.ended_at_ms = stop_session.ended_at_ms;
    expected_stopped.ended_by_id = stop_session.ended_by_id.clone();

    start.schema_id == stop.schema_id
        && start.decision_id == stop.decision_id
        && start.current
        && !stop.current
        && start.rejection_reason.is_none()
        && stop.rejection_reason
            == Some(ManifoldMediaSessionAcceptanceRejectionReason::SessionNotCurrent)
        && start.acceptance_state_authority_revision <= stop.acceptance_state_authority_revision
        && start.validated_at_ms <= stop.validated_at_ms
        && start_session.lifecycle_status == ManifoldMediaSessionLifecycleStatus::Current
        && start_session.ended_at_ms.is_none()
        && stop_session.lifecycle_status == ManifoldMediaSessionLifecycleStatus::Stopped
        && stop_session.ended_at_ms.is_some()
        && *stop_session == expected_stopped
}

fn validate_current_session(
    lock: &BrokerMediaLifecycleLock,
    media: &BrokerMediaProductBindingDocument,
    current: &ManifoldMediaSessionCurrentReceipt,
    errors: &mut Vec<String>,
) {
    let Some(accepted) = current.session.as_ref() else {
        errors.push("retained Manifold current receipt omitted subject record".to_string());
        return;
    };
    let exact = accepted.schema_id.as_str() == MANIFOLD_ACCEPTED_MEDIA_SESSION_SCHEMA
        && current.schema_id.as_str() == MANIFOLD_MEDIA_SESSION_CURRENT_RECEIPT_SCHEMA
        && current.current
        && current.rejection_reason.is_none()
        && current.decision_id == accepted.decision_id
        && current.acceptance_state_authority_revision.get() > 0
        && accepted.product_binding == media.manifold
        && accepted.session_id.as_str() == lock.session_id
        && accepted.session_authority_revision
            == accepted.product_binding.descriptor.authority_revision
        && accepted.product_descriptor_canonical_sha256
            == lock.manifold_descriptor_canonical_sha256
        && accepted.platform_runtime_spec_id.as_str() == lock.runtime_spec_id
        && accepted.provider_epoch_id.as_str().trim().len() > 0
        && accepted.decision_id.as_str().trim().len() > 0
        && accepted.lifecycle_status == ManifoldMediaSessionLifecycleStatus::Current
        && lock
            .product_ids
            .iter()
            .any(|product_id| product_id == accepted.product_id.as_str())
        && accepted.feature_lock_id.as_str() == lock.app_feature_lock_id
        && accepted.feature_lock_fingerprint == lock.app_feature_lock_fingerprint
        && accepted.capability_id.as_str() == "capability.media.session.accept"
        && accepted.admission_grant_id.as_str().trim().len() > 0
        && accepted.runtime_client_id.as_str() == lock.client_id
        && accepted.runtime_lease_id.as_str() == lock.media_runtime_lease_id
        && accepted.accepted_at_ms <= current.validated_at_ms
        && accepted.expires_at_ms > current.validated_at_ms;
    if !exact {
        errors
            .push("retained Manifold accepted session does not match package closure".to_string());
    }
}

fn validate_actions_and_owner_receipts(
    lock: &BrokerMediaLifecycleLock,
    evidence: &BrokerMediaLifecycleEvidence,
    errors: &mut Vec<String>,
) {
    let Some(accepted) = evidence.current_acceptance.session.as_ref() else {
        errors.push("media action evidence omitted current accepted session".to_string());
        return;
    };
    for (operation, action, application) in [
        (
            MediaStreamPlatformOperation::Start,
            &evidence.start_action,
            &evidence.start_application,
        ),
        (
            MediaStreamPlatformOperation::Stop,
            &evidence.stop_action,
            &evidence.stop_application,
        ),
    ] {
        if action.schema_id != MEDIA_STREAM_PLATFORM_ACTION_SCHEMA
            || action.operation != operation
            || action.authority_epoch_id != accepted.provider_epoch_id.as_str()
            || action.client_authority.client_id != lock.client_id
            || action.client_authority.lease_id != lock.media_runtime_lease_id
            || action.client_authority.product_id != accepted.product_id.as_str()
            || action.client_authority.feature_lock_id != lock.app_feature_lock_id
            || action.client_authority.feature_lock_fingerprint != lock.app_feature_lock_fingerprint
            || action.client_authority.session_capability_id != accepted.capability_id.as_str()
            || action.client_authority.session_admission_grant_id
                != accepted.admission_grant_id.as_str()
            || action.client_authority.operation_capability_id
                != match operation {
                    MediaStreamPlatformOperation::Start => "capability.command.media.session.start",
                    MediaStreamPlatformOperation::Stop => "capability.command.media.session.stop",
                }
            || action
                .client_authority
                .operation_admission_grant_id
                .trim()
                .is_empty()
            || action.client_authority.operation_admission_grant_id
                != accepted.admission_grant_id.as_str()
            || action
                .client_authority
                .operation_admission_use_request_id
                .trim()
                .is_empty()
            || action.runtime_spec_id != lock.runtime_spec_id
            || action.runtime_spec_canonical_sha256 != lock.runtime_spec_canonical_sha256
            || action.manifold_descriptor_canonical_sha256
                != lock.manifold_descriptor_canonical_sha256
            || action.manifold_decision_id != accepted.decision_id.as_str()
            || action.manifold_session_revision != accepted.session_authority_revision.get()
            || match operation {
                MediaStreamPlatformOperation::Start => {
                    action.media_acceptance_authority_revision
                        != evidence
                            .current_acceptance
                            .acceptance_state_authority_revision
                            .get()
                }
                MediaStreamPlatformOperation::Stop => {
                    action.media_acceptance_authority_revision
                        <= evidence
                            .current_acceptance
                            .acceptance_state_authority_revision
                            .get()
                }
            }
            || action.owner_actions.len() != 7
        {
            errors.push(format!("{operation:?} action authority binding mismatch"));
        }
        if application.schema_id != MEDIA_STREAM_PLATFORM_APPLICATION_SCHEMA
            || application.action_id != action.action_id
            || application.authority_epoch_id != action.authority_epoch_id
            || application.media_acceptance_authority_revision
                < action.media_acceptance_authority_revision
            || !application.platform_effect_completed
        {
            errors.push(format!("{operation:?} Rust application receipt mismatch"));
        }
        validate_lifecycle_decisions(operation, action, application, errors);
    }
    if evidence.start_application.resulting_phase != MediaStreamRuntimePhase::SourcesStarted
        || evidence.stop_application.resulting_phase != MediaStreamRuntimePhase::Stopped
        || evidence.start_action.action_id == evidence.stop_action.action_id
        || !same_session_holder(
            &evidence.start_action.client_authority,
            &evidence.stop_action.client_authority,
        )
        || evidence
            .start_action
            .client_authority
            .operation_admission_use_request_id
            == evidence
                .stop_action
                .client_authority
                .operation_admission_use_request_id
    {
        errors.push("start/stop lifecycle phase or replay identity mismatch".to_string());
    }
    if evidence.owner_receipts.len() != 7 {
        errors.push("exactly seven owner lifecycle receipts are required".to_string());
        return;
    }
    let mut selections = BTreeSet::new();
    let mut unique_start_receipt_ids = BTreeSet::new();
    let mut unique_stop_receipt_ids = BTreeSet::new();
    let mut unique_provider_handles = BTreeSet::new();
    let mut start_ids = Vec::new();
    for (start_index, (action, receipt)) in evidence
        .start_action
        .owner_actions
        .iter()
        .zip(&evidence.owner_receipts)
        .enumerate()
    {
        let stop_position = evidence
            .stop_action
            .owner_actions
            .iter()
            .position(|stop| stop.selection == receipt.selection);
        let Some(stop_index) = stop_position else {
            errors.push("stop action omitted a selected owner".to_string());
            continue;
        };
        let stop = &evidence.stop_action.owner_actions[stop_index];
        if action.selection != receipt.selection
            || action.action_kind != receipt.start_action_kind
            || stop.action_kind != receipt.stop_action_kind
            || receipt.start_completion_sequence
                != u32::try_from(start_index + 1).unwrap_or(u32::MAX)
            || receipt.stop_completion_sequence != u32::try_from(stop_index + 1).unwrap_or(u32::MAX)
            || receipt.start_receipt_id.trim().is_empty()
            || receipt.stop_receipt_id.trim().is_empty()
            || receipt.provider_handle_id.trim().is_empty()
            || receipt.start_provider_state_revision == 0
            || receipt.stop_provider_state_revision <= receipt.start_provider_state_revision
            || receipt.start_observed_state != expected_state(action.action_kind)
            || receipt.stop_observed_state != expected_state(stop.action_kind)
            || !selections.insert(receipt.selection.clone())
            || !unique_start_receipt_ids.insert(receipt.start_receipt_id.clone())
            || !unique_stop_receipt_ids.insert(receipt.stop_receipt_id.clone())
            || !unique_provider_handles.insert(receipt.provider_handle_id.clone())
        {
            errors.push("owner start/stop handle lifecycle mismatch".to_string());
        }
        start_ids.push(receipt.start_receipt_id.clone());
    }
    let stop_ids = evidence
        .stop_action
        .owner_actions
        .iter()
        .filter_map(|action| {
            evidence
                .owner_receipts
                .iter()
                .find(|receipt| receipt.selection == action.selection)
                .map(|receipt| receipt.stop_receipt_id.clone())
        })
        .collect::<Vec<_>>();
    if evidence.start_application.owner_receipt_ids != start_ids
        || evidence.stop_application.owner_receipt_ids != stop_ids
    {
        errors.push("application receipt omitted/reordered owner-issued receipts".to_string());
    }
}

fn validate_lifecycle_decisions(
    operation: MediaStreamPlatformOperation,
    action: &MediaStreamPlatformAction,
    application: &MediaStreamPlatformApplicationReceipt,
    errors: &mut Vec<String>,
) {
    let expected = match operation {
        MediaStreamPlatformOperation::Start => vec![
            (
                format!("{}.arm_receivers", action.action_id),
                action.expected_runtime_revision,
                action.expected_runtime_revision + 1,
                MediaStreamRuntimePhase::ReceiversArmed,
            ),
            (
                format!("{}.start_sources", action.action_id),
                action.expected_runtime_revision + 1,
                action.expected_runtime_revision + 2,
                MediaStreamRuntimePhase::SourcesStarted,
            ),
        ],
        MediaStreamPlatformOperation::Stop => vec![(
            format!("{}.cleanup", action.action_id),
            action.expected_runtime_revision,
            action.expected_runtime_revision + 1,
            MediaStreamRuntimePhase::Stopped,
        )],
    };
    let exact = application.lifecycle_decisions.len() == expected.len()
        && application.lifecycle_decisions.iter().zip(&expected).all(
            |(decision, (request_id, prior, resulting, phase))| {
                decision.schema == MEDIA_STREAM_RUNTIME_DECISION_SCHEMA
                    && decision.request_id == *request_id
                    && decision.decision_owner == MEDIA_STREAM_RUNTIME_AUTHORITY
                    && decision.accepted
                    && decision.applied
                    && decision.rejection_reason.is_none()
                    && decision.prior_runtime_revision == *prior
                    && decision.resulting_runtime_revision == *resulting
                    && decision.resulting_phase == *phase
            },
        )
        && expected.last().is_some_and(|(_, _, resulting, phase)| {
            application.resulting_runtime_revision == *resulting
                && application.resulting_phase == *phase
        });
    if !exact {
        errors.push(format!(
            "{operation:?} lifecycle decision chain missing, reordered, or revision-damaged"
        ));
    }
}

fn same_session_holder(
    first: &MediaStreamClientAuthorityBinding,
    second: &MediaStreamClientAuthorityBinding,
) -> bool {
    first.client_id == second.client_id
        && first.lease_id == second.lease_id
        && first.product_id == second.product_id
        && first.feature_lock_id == second.feature_lock_id
        && first.feature_lock_fingerprint == second.feature_lock_fingerprint
        && first.session_capability_id == second.session_capability_id
        && first.session_admission_grant_id == second.session_admission_grant_id
}

fn validate_render(
    lock: &BrokerMediaLifecycleLock,
    accepted: &ManifoldAcceptedMediaSession,
    render: &BrokerMediaRenderEvidence,
    errors: &mut Vec<String>,
) {
    if render.provider_epoch_id != accepted.provider_epoch_id.as_str()
        || render.session_id != lock.session_id
        || render.stream_id != lock.stream_id
        || render.render_sink_id != lock.render_sink_id
        || render.marker_namespace != lock.marker_namespace
        || render.receiver_observed_bytes == 0
        || render.rendered_frame_count == 0
    {
        errors.push("platform media/render identity or counters mismatch".to_string());
    }
    for (label, path, digest) in [
        (
            "frame",
            render.frame_evidence_path.as_str(),
            render.frame_evidence_sha256.as_str(),
        ),
        (
            "marker",
            render.marker_evidence_path.as_str(),
            render.marker_evidence_sha256.as_str(),
        ),
    ] {
        let source = Path::new(path);
        let fixture_named = source.components().any(|component| {
            component
                .as_os_str()
                .to_string_lossy()
                .eq_ignore_ascii_case("fixtures")
        });
        match fs::read(source) {
            Ok(bytes) => {
                let actual = sha256(&bytes);
                if !source.is_absolute() || fixture_named || bytes.is_empty() || actual != digest {
                    errors.push(format!(
                        "fresh {label} artifact path/hash mismatch: absolute={} fixture={} bytes={} expected={} actual={actual}",
                        source.is_absolute(),
                        fixture_named,
                        bytes.len(),
                        digest
                    ));
                }
            }
            Err(error) => errors.push(format!(
                "fresh {label} artifact unreadable at {path}: {error}"
            )),
        }
    }
    if let Ok(marker) = fs::read_to_string(&render.marker_evidence_path) {
        for token in [
            lock.marker_namespace.as_str(),
            lock.session_id.as_str(),
            lock.stream_id.as_str(),
            lock.render_sink_id.as_str(),
            accepted.provider_epoch_id.as_str(),
        ] {
            if !marker.contains(token) {
                errors.push(format!("platform marker evidence missing {token}"));
            }
        }
    }
}

fn expected_state(action: MediaStreamOwnerActionKind) -> &'static str {
    match action {
        MediaStreamOwnerActionKind::ArmReceiver => "receiver_armed",
        MediaStreamOwnerActionKind::ArmCleanup => "cleanup_armed",
        MediaStreamOwnerActionKind::Start => "started",
        MediaStreamOwnerActionKind::Stop => "stopped",
        MediaStreamOwnerActionKind::Cleanup => "cleaned",
    }
}

fn sha256(bytes: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_quest_broker_contracts::BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA;
    use rusty_quest_media_stream::{
        MediaStreamClientAuthorityBinding, MediaStreamOwnerAction,
        MediaStreamOwnerCompletionReceipt, MediaStreamOwnerKind, MediaStreamSessionProductRuntime,
        MediaStreamTrustedOwnerProvider,
    };
    use std::sync::atomic::{AtomicU64, Ordering};

    const NATIVE_CLIENT: &str =
        include_str!("../../../fixtures/broker-clients/native-renderer.client.json");
    const PRODUCT_LOCK: &str = include_str!(
        "../../../../rusty-manifold/fixtures/broker-product/media-session-standalone.lock.json"
    );
    const NATIVE_LOCK: &str =
        include_str!("../../../fixtures/broker-clients/native-renderer.media-lifecycle.json");
    const NATIVE_FEATURE: &str = include_str!(
        "../../../apps/native-renderer-android/morphospace/conformance-locks/broker-media-client.feature.lock.json"
    );
    const NATIVE_MEDIA: &str = include_str!(
        "../../../fixtures/media-runtime-products/native-renderer-display.binding.json"
    );
    const SPATIAL_CLIENT: &str =
        include_str!("../../../fixtures/broker-clients/spatial-camera-panel.client.json");
    const SPATIAL_LOCK: &str =
        include_str!("../../../fixtures/broker-clients/spatial-camera-panel.media-lifecycle.json");
    const SPATIAL_FEATURE: &str = include_str!(
        "../../../apps/spatial-camera-panel-android/morphospace/conformance-locks/broker-media-client.feature.lock.json"
    );
    const SPATIAL_MEDIA: &str = include_str!(
        "../../../fixtures/media-runtime-products/spatial-camera-panel-display.binding.json"
    );
    static TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(1);

    fn package(
        client: &str,
        lock: &str,
        feature: &str,
        media: &str,
    ) -> BrokerMediaLifecyclePackageBinding {
        BrokerMediaLifecyclePackageBinding {
            schema_id: BROKER_MEDIA_LIFECYCLE_PACKAGE_SCHEMA.to_string(),
            product_lock_json: PRODUCT_LOCK.to_string(),
            product_lock_sha256: sha256(PRODUCT_LOCK.as_bytes()),
            client_lock_json: client.to_string(),
            client_lock_sha256: sha256(client.as_bytes()),
            media_lifecycle_lock_json: lock.to_string(),
            media_lifecycle_lock_sha256: sha256(lock.as_bytes()),
            app_feature_lock_json: feature.to_string(),
            app_feature_lock_sha256: sha256(feature.as_bytes()),
            media_binding_json: media.to_string(),
            media_binding_sha256: sha256(media.as_bytes()),
        }
    }

    fn native_package() -> BrokerMediaLifecyclePackageBinding {
        package(NATIVE_CLIENT, NATIVE_LOCK, NATIVE_FEATURE, NATIVE_MEDIA)
    }

    fn spatial_package() -> BrokerMediaLifecyclePackageBinding {
        package(SPATIAL_CLIENT, SPATIAL_LOCK, SPATIAL_FEATURE, SPATIAL_MEDIA)
    }

    fn current_acceptance(
        media: &BrokerMediaProductBindingDocument,
        lock: &BrokerMediaLifecycleLock,
        epoch: &str,
    ) -> ManifoldMediaSessionCurrentReceipt {
        let descriptor = &media.manifold.descriptor;
        let session = serde_json::json!({
            "$schema": MANIFOLD_ACCEPTED_MEDIA_SESSION_SCHEMA,
            "decision_id": "decision.media-session.net016-native",
            "request_id": "request.media-session.net016-native",
            "session_id": descriptor.session_id,
            "session_authority_revision": descriptor.authority_revision,
            "product_descriptor_canonical_sha256": media.manifold.descriptor_canonical_sha256,
            "provider_epoch_id": epoch,
            "platform_runtime_spec_id": descriptor.platform_runtime_spec_id,
            "product_id": "broker.media_session.standalone",
            "feature_lock_id": lock.app_feature_lock_id,
            "feature_lock_fingerprint": lock.app_feature_lock_fingerprint,
            "capability_id": "capability.media.session.accept",
            "admission_grant_id": "grant.quest.native-renderer.media-session",
            "runtime_authority_host_id": "host.quest.net016",
            "runtime_command_request_id": "request.runtime.media-session.net016-native",
            "runtime_command_id": "rusty.manifold.media.session.accept",
            "runtime_client_id": "client.quest.native-renderer",
            "runtime_lease_id": "lease.media.session.client.quest.native-renderer",
            "runtime_params_digest": {
                "$schema": "rusty.manifold.runtime_host.typed_params_digest.v1",
                "params_type_id": "rusty.manifold.media.session_acceptance_params.v1",
                "canonical_sha256": format!("sha256:{}", "33".repeat(32)),
                "canonical_size_bytes": 128
            },
            "runtime_dispatch_id": "dispatch.runtime.media-session.net016-native",
            "runtime_application_receipt_id": "receipt.runtime.media-session.net016-native",
            "runtime_resulting_authority_revision": 2,
            "lifecycle_status": "current",
            "accepted_at_ms": 1_000,
            "expires_at_ms": 60_000,
            "ended_at_ms": null,
            "ended_by_id": null,
            "product_binding": media.manifold
        });
        serde_json::from_value(serde_json::json!({
            "$schema": MANIFOLD_MEDIA_SESSION_CURRENT_RECEIPT_SCHEMA,
            "decision_id": session["decision_id"],
            "acceptance_state_authority_revision": 2,
            "current": true,
            "rejection_reason": null,
            "session": session,
            "validated_at_ms": 2_000
        }))
        .expect("accepted session")
    }

    struct TestProvider {
        kind: MediaStreamOwnerKind,
        handle: String,
        revision: u64,
        readback: Option<rusty_quest_media_stream::MediaStreamOwnerProviderReadback>,
    }

    impl MediaStreamTrustedOwnerProvider for TestProvider {
        fn owner_kind(&self) -> MediaStreamOwnerKind {
            self.kind
        }

        fn execute_and_readback(
            &mut self,
            action: &MediaStreamPlatformAction,
            owner: &MediaStreamOwnerAction,
        ) -> Result<rusty_quest_media_stream::MediaStreamOwnerProviderReadback, String> {
            let readback = rusty_quest_media_stream::MediaStreamOwnerProviderReadback {
                action_id: action.action_id.clone(),
                authority_epoch_id: action.authority_epoch_id.clone(),
                media_acceptance_authority_revision: action.media_acceptance_authority_revision,
                client_id: action.client_authority.client_id.clone(),
                lease_id: action.client_authority.lease_id.clone(),
                provider_kind: owner.selection.provider_kind.clone(),
                resource_id: owner.selection.resource_id.clone(),
                provider_handle_id: self.handle.clone(),
                provider_state_revision: self.revision,
                observed_state: expected_state(owner.action_kind).to_string(),
                receipt_id: format!("receipt.{}.{}", action.action_id, self.revision),
            };
            self.readback = Some(readback.clone());
            Ok(readback)
        }

        fn compensate_uncertain_attempt(
            &mut self,
            action: &MediaStreamPlatformAction,
            owner: &MediaStreamOwnerAction,
        ) -> Result<rusty_quest_media_stream::MediaStreamOwnerProviderReadback, String> {
            self.execute_and_readback(action, owner)
        }

        fn verify_readback(
            &self,
            _action: &MediaStreamPlatformAction,
            _owner: &MediaStreamOwnerAction,
            readback: &rusty_quest_media_stream::MediaStreamOwnerProviderReadback,
        ) -> bool {
            self.readback.as_ref() == Some(readback)
        }
    }

    fn execute_action(
        runtime: &mut MediaStreamSessionProductRuntime,
        action: &MediaStreamPlatformAction,
        revision_base: u64,
        now_ms: u64,
    ) -> Vec<MediaStreamOwnerCompletionReceipt> {
        action
            .owner_actions
            .iter()
            .enumerate()
            .map(|(index, owner)| {
                let mut provider = TestProvider {
                    kind: owner.selection.owner_kind,
                    handle: format!(
                        "handle.{}.{}",
                        owner.selection.owner_id, owner.selection.resource_id
                    ),
                    revision: revision_base + u64::try_from(index).expect("small fixture"),
                    readback: None,
                };
                match owner.selection.owner_kind {
                    MediaStreamOwnerKind::Source => runtime.complete_source(&mut provider, now_ms),
                    MediaStreamOwnerKind::Processor => {
                        runtime.complete_processor(&mut provider, now_ms)
                    }
                    MediaStreamOwnerKind::Route => runtime.complete_route(&mut provider, now_ms),
                    MediaStreamOwnerKind::Socket => runtime.complete_socket(&mut provider, now_ms),
                    MediaStreamOwnerKind::Codec => runtime.complete_codec(&mut provider, now_ms),
                    MediaStreamOwnerKind::Sink => runtime.complete_sink(&mut provider, now_ms),
                    MediaStreamOwnerKind::Cleanup => {
                        runtime.complete_cleanup(&mut provider, now_ms)
                    }
                }
                .expect("trusted provider executes")
            })
            .collect()
    }

    fn owner_lifecycles(
        start: &[MediaStreamOwnerCompletionReceipt],
        stop: &[MediaStreamOwnerCompletionReceipt],
    ) -> Vec<BrokerMediaOwnerLifecycleReceipt> {
        start
            .iter()
            .map(|start| {
                let stop = stop
                    .iter()
                    .find(|candidate| candidate.selection == start.selection)
                    .expect("same stop owner");
                BrokerMediaOwnerLifecycleReceipt {
                    selection: start.selection.clone(),
                    start_action_kind: start.action_kind,
                    start_receipt_id: start.receipt_id.clone(),
                    start_completion_sequence: start.completion_sequence,
                    provider_handle_id: start.provider_handle_id.clone(),
                    start_provider_state_revision: start.provider_state_revision,
                    start_observed_state: start.observed_state.clone(),
                    stop_action_kind: stop.action_kind,
                    stop_receipt_id: stop.receipt_id.clone(),
                    stop_completion_sequence: stop.completion_sequence,
                    stop_provider_state_revision: stop.provider_state_revision,
                    stop_observed_state: stop.observed_state.clone(),
                }
            })
            .collect()
    }

    fn full_native_evidence(
        package: &BrokerMediaLifecyclePackageBinding,
    ) -> BrokerMediaLifecycleEvidence {
        let validated = validate_media_lifecycle_package(package).expect("package");
        let lock = validated.lifecycle;
        let current = current_acceptance(&validated.media, &lock, "epoch.media.net016-native");
        let accepted = current.session.as_ref().expect("session").clone();
        let mut runtime = MediaStreamSessionProductRuntime::new_for_test(
            validated.media.quest,
            current.clone(),
            accepted.provider_epoch_id.as_str().to_string(),
        )
        .expect("runtime");
        let authority = MediaStreamClientAuthorityBinding {
            client_id: lock.client_id.clone(),
            lease_id: lock.media_runtime_lease_id.clone(),
            product_id: accepted.product_id.as_str().to_string(),
            feature_lock_id: lock.app_feature_lock_id.clone(),
            feature_lock_fingerprint: lock.app_feature_lock_fingerprint.clone(),
            session_capability_id: "capability.media.session.accept".to_string(),
            session_admission_grant_id: "grant.quest.native-renderer.media-session".to_string(),
            operation_capability_id: "capability.command.media.session.start".to_string(),
            operation_admission_grant_id: "grant.quest.native-renderer.media-session".to_string(),
            operation_admission_use_request_id: "use.operation.native-renderer.media-session.start"
                .to_string(),
        };
        let start_action = runtime
            .prepare(
                "action.net016.native.start".to_string(),
                MediaStreamPlatformOperation::Start,
                authority.clone(),
                2_000,
            )
            .expect("start action");
        let start_receipts = execute_action(&mut runtime, &start_action, 10, 2_000);
        let start_application = runtime
            .apply_recorded_owner_completions(2_000)
            .expect("start applies");
        let mut stop_authority = authority;
        stop_authority.operation_capability_id =
            "capability.command.media.session.stop".to_string();
        stop_authority.operation_admission_use_request_id =
            "use.operation.native-renderer.media-session.stop".to_string();
        let mut stop_action = runtime
            .prepare(
                "action.net016.native.stop".to_string(),
                MediaStreamPlatformOperation::Stop,
                stop_authority,
                2_000,
            )
            .expect("stop action");
        let stop_receipts = execute_action(&mut runtime, &stop_action, 100, 2_000);
        let mut stop_application = runtime
            .apply_recorded_owner_completions(2_000)
            .expect("stop applies");
        let stop_media_revision = current
            .acceptance_state_authority_revision
            .next()
            .expect("fixture stop advances authority revision")
            .get();
        stop_action.media_acceptance_authority_revision = stop_media_revision;
        stop_application.media_acceptance_authority_revision = stop_media_revision;

        let root = std::env::temp_dir().join(format!(
            "rusty-quest-net016-media-{}-{}",
            std::process::id(),
            TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&root).expect("temp root");
        let frame_path = root.join("native-frame-scorecard.bin");
        let marker_path = root.join("native-platform-marker.txt");
        fs::write(&frame_path, b"frame-scorecard:frames=120;bytes=1048576")
            .expect("frame evidence");
        fs::write(
            &marker_path,
            format!(
                "{} {} {} {} {}",
                lock.marker_namespace,
                lock.session_id,
                lock.stream_id,
                lock.render_sink_id,
                accepted.provider_epoch_id.as_str()
            ),
        )
        .expect("marker evidence");
        let frame_bytes = fs::read(&frame_path).expect("frame bytes");
        let marker_bytes = fs::read(&marker_path).expect("marker bytes");
        BrokerMediaLifecycleEvidence {
            schema_id: BROKER_MEDIA_LIFECYCLE_EVIDENCE_SCHEMA.to_string(),
            client_id: lock.client_id.clone(),
            product_lock_sha256: package.product_lock_sha256.clone(),
            client_lock_sha256: package.client_lock_sha256.clone(),
            app_feature_lock_sha256: package.app_feature_lock_sha256.clone(),
            media_lifecycle_lock_sha256: package.media_lifecycle_lock_sha256.clone(),
            media_binding_sha256: package.media_binding_sha256.clone(),
            current_acceptance: current,
            start_action,
            start_application,
            stop_action,
            stop_application,
            owner_receipts: owner_lifecycles(&start_receipts, &stop_receipts),
            status_receipt_id: "receipt.status.native".to_string(),
            subscription_receipt_id: "receipt.subscription.native".to_string(),
            render: BrokerMediaRenderEvidence {
                provider_epoch_id: accepted.provider_epoch_id.as_str().to_string(),
                session_id: lock.session_id,
                stream_id: lock.stream_id,
                render_sink_id: lock.render_sink_id,
                marker_namespace: lock.marker_namespace,
                frame_evidence_path: frame_path.to_string_lossy().to_string(),
                frame_evidence_sha256: sha256(&frame_bytes),
                marker_evidence_path: marker_path.to_string_lossy().to_string(),
                marker_evidence_sha256: sha256(&marker_bytes),
                receiver_observed_bytes: 1_048_576,
                rendered_frame_count: 120,
            },
            release_receipt_id: "receipt.release.native".to_string(),
            lease_released: true,
            app_death_observed: true,
            rebind_provider_epoch_id: accepted.provider_epoch_id.as_str().to_string(),
            pre_rebind_authority_revision: 4,
            post_rebind_authority_revision: 4,
            restarted_provider_epoch_id: "epoch.media.net016-native-restarted".to_string(),
            old_epoch_rejected: true,
            cleanup_complete: true,
            package_fatal_count: 0,
            system_fatal_count: 0,
        }
    }

    fn completion_evidence_from_lifecycle(
        evidence: &BrokerMediaLifecycleEvidence,
        start: bool,
    ) -> BrokerMediaLifecycleCompletionResponse {
        let action = if start {
            evidence.start_action.clone()
        } else {
            evidence.stop_action.clone()
        };
        let application = if start {
            evidence.start_application.clone()
        } else {
            evidence.stop_application.clone()
        };
        let owner_receipts = evidence
            .owner_receipts
            .iter()
            .map(|receipt| BrokerMediaOwnerCompletionEvidence {
                selection: receipt.selection.clone(),
                action_kind: if start {
                    receipt.start_action_kind
                } else {
                    receipt.stop_action_kind
                },
                receipt_id: if start {
                    receipt.start_receipt_id.clone()
                } else {
                    receipt.stop_receipt_id.clone()
                },
                completion_sequence: if start {
                    receipt.start_completion_sequence
                } else {
                    receipt.stop_completion_sequence
                },
                provider_handle_id: receipt.provider_handle_id.clone(),
                provider_state_revision: if start {
                    receipt.start_provider_state_revision
                } else {
                    receipt.stop_provider_state_revision
                },
                observed_state: if start {
                    receipt.start_observed_state.clone()
                } else {
                    receipt.stop_observed_state.clone()
                },
            })
            .collect();
        BrokerMediaLifecycleCompletionResponse {
            schema_id: "rusty.quest.broker.media_completion_response.v1".to_string(),
            provider_epoch_id: action.authority_epoch_id.clone(),
            local_acceptance_rules: false,
            decision_owner_id: "module.runtime.host".to_string(),
            client_id: evidence.client_id.clone(),
            current_acceptance: evidence.current_acceptance.clone(),
            action,
            owner_receipts,
            application,
            platform_effect_completed: true,
        }
    }

    #[test]
    fn native_and_spatial_packages_bind_distinct_exact_sink_closures() {
        let native = native_package();
        let spatial = spatial_package();
        validate_media_lifecycle_package_pair(&native, &spatial).expect("pair closes");
        let native = validate_media_lifecycle_package(&native).expect("native");
        let spatial = validate_media_lifecycle_package(&spatial).expect("spatial");
        assert_eq!(native.lifecycle.render_sink_id, "sink.native-openxr");
        assert_eq!(spatial.lifecycle.render_sink_id, "sink.spatial-sdk");
    }

    #[test]
    fn full_native_receipt_joins_authority_handles_render_and_cleanup() {
        let package = native_package();
        let evidence = full_native_evidence(&package);
        let receipt =
            validate_media_lifecycle_evidence(&package, &evidence).expect("lifecycle passes");
        assert_eq!(receipt.owner_lifecycle_count, 7);
        assert_eq!(receipt.render_sink_id, "sink.native-openxr");
        assert_eq!(receipt.fatal_count, 0);
    }

    #[test]
    fn lifecycle_assembler_accepts_only_rust_completion_responses() {
        let package = native_package();
        let expected = full_native_evidence(&package);
        let start = completion_evidence_from_lifecycle(&expected, true);
        let mut stop = completion_evidence_from_lifecycle(&expected, false);
        stop.current_acceptance.current = false;
        stop.current_acceptance.rejection_reason =
            Some(ManifoldMediaSessionAcceptanceRejectionReason::SessionNotCurrent);
        stop.current_acceptance.acceptance_state_authority_revision = stop
            .current_acceptance
            .acceptance_state_authority_revision
            .next()
            .expect("fixture revision advances");
        let stop_media_revision = stop
            .current_acceptance
            .acceptance_state_authority_revision
            .get();
        stop.action.media_acceptance_authority_revision = stop_media_revision;
        stop.application.media_acceptance_authority_revision = stop_media_revision;
        stop.current_acceptance.validated_at_ms += 100;
        {
            let session = stop
                .current_acceptance
                .session
                .as_mut()
                .expect("stop retains subject session");
            session.lifecycle_status = ManifoldMediaSessionLifecycleStatus::Stopped;
            session.ended_at_ms = Some(stop.current_acceptance.validated_at_ms);
            session.ended_by_id = Some(session.runtime_command_request_id.clone());
        }
        let assembly = BrokerMediaLifecycleAssemblyEvidence {
            status_receipt_id: expected.status_receipt_id.clone(),
            subscription_receipt_id: expected.subscription_receipt_id.clone(),
            render: expected.render.clone(),
            release_receipt_id: expected.release_receipt_id.clone(),
            lease_released: expected.lease_released,
            app_death_observed: expected.app_death_observed,
            rebind_provider_epoch_id: expected.rebind_provider_epoch_id.clone(),
            pre_rebind_authority_revision: expected.pre_rebind_authority_revision,
            post_rebind_authority_revision: expected.post_rebind_authority_revision,
            restarted_provider_epoch_id: expected.restarted_provider_epoch_id.clone(),
            old_epoch_rejected: expected.old_epoch_rejected,
            cleanup_complete: expected.cleanup_complete,
            package_fatal_count: expected.package_fatal_count,
            system_fatal_count: expected.system_fatal_count,
        };
        let assembled = assemble_media_lifecycle_evidence(&package, &start, &stop, assembly)
            .expect("assembled lifecycle evidence validates");
        assert_eq!(assembled.owner_receipts.len(), 7);
        assert_eq!(
            assembled.start_action.operation,
            MediaStreamPlatformOperation::Start
        );
        assert_eq!(
            assembled.stop_action.operation,
            MediaStreamPlatformOperation::Stop
        );

        let mut forged = start;
        forged.decision_owner_id = "java.local.policy".to_string();
        let errors = assemble_media_lifecycle_evidence(
            &package,
            &forged,
            &stop,
            BrokerMediaLifecycleAssemblyEvidence {
                status_receipt_id: expected.status_receipt_id,
                subscription_receipt_id: expected.subscription_receipt_id,
                render: expected.render,
                release_receipt_id: expected.release_receipt_id,
                lease_released: expected.lease_released,
                app_death_observed: expected.app_death_observed,
                rebind_provider_epoch_id: expected.rebind_provider_epoch_id,
                pre_rebind_authority_revision: expected.pre_rebind_authority_revision,
                post_rebind_authority_revision: expected.post_rebind_authority_revision,
                restarted_provider_epoch_id: expected.restarted_provider_epoch_id,
                old_epoch_rejected: expected.old_epoch_rejected,
                cleanup_complete: expected.cleanup_complete,
                package_fatal_count: expected.package_fatal_count,
                system_fatal_count: expected.system_fatal_count,
            },
        )
        .expect_err("local Java policy must reject");
        assert!(errors
            .iter()
            .any(|error| error.contains("Rust-owned media lifecycle completion")));
    }

    #[test]
    fn stale_feature_lock_cross_client_action_and_handle_damage_reject() {
        let mut stale = native_package();
        stale.app_feature_lock_sha256 = format!("sha256:{}", "00".repeat(32));
        assert!(validate_media_lifecycle_package(&stale).is_err());

        let package = native_package();
        let mut copied = full_native_evidence(&package);
        copied.start_action.client_authority.client_id =
            "client.quest.spatial-camera-panel".to_string();
        copied.owner_receipts[0].stop_provider_state_revision =
            copied.owner_receipts[0].start_provider_state_revision;
        let errors = validate_media_lifecycle_evidence(&package, &copied)
            .expect_err("copied action/stale handle rejects");
        assert!(errors
            .iter()
            .any(|error| error.contains("action authority binding")));
        assert!(errors
            .iter()
            .any(|error| error.contains("handle lifecycle")));

        for damage in 0..4 {
            let mut evidence = full_native_evidence(&package);
            match damage {
                0 => evidence.start_application.lifecycle_decisions.clear(),
                1 => evidence.start_application.lifecycle_decisions.reverse(),
                2 => {
                    let duplicate = evidence.start_application.lifecycle_decisions[0].clone();
                    evidence
                        .start_application
                        .lifecycle_decisions
                        .push(duplicate);
                }
                3 => {
                    evidence.start_application.lifecycle_decisions[0].resulting_runtime_revision +=
                        1;
                }
                _ => unreachable!(),
            }
            let errors = validate_media_lifecycle_evidence(&package, &evidence)
                .expect_err("damaged lifecycle decision chain rejects");
            assert!(errors
                .iter()
                .any(|error| error.contains("lifecycle decision chain")));
        }
    }
}
