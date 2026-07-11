//! Deterministic platform media-session runtime authority.
//!
//! Manifold accepts the session and stream references. This runtime owns only
//! Quest-side lifecycle adoption after that decision; it does not open sockets,
//! codecs, capture APIs, or sinks itself.

use std::collections::{BTreeMap, BTreeSet};

use rusty_quest_device_link::{validate_direct_p2p_socket_route, DirectP2pSocketRoute};
use serde::{Deserialize, Serialize};

use crate::{validate_media_stream_session, MediaStreamSessionPlan, ValidationError};

/// Runtime-spec schema.
pub const MEDIA_STREAM_RUNTIME_SPEC_SCHEMA: &str = "rusty.quest.media_stream_runtime_spec.v1";
/// Runtime-state schema.
pub const MEDIA_STREAM_RUNTIME_STATE_SCHEMA: &str = "rusty.quest.media_stream_runtime_state.v1";
/// Runtime decision schema.
pub const MEDIA_STREAM_RUNTIME_DECISION_SCHEMA: &str =
    "rusty.quest.media_stream_runtime_decision.v1";
/// Platform lifecycle authority id. Manifold remains accepted session authority.
pub const MEDIA_STREAM_RUNTIME_AUTHORITY: &str = "rusty_quest_media_stream_session_runtime";

/// Runtime composition selected after a Manifold session decision.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamRuntimeSpec {
    /// Schema id.
    pub schema: String,
    /// Stable runtime spec id.
    pub runtime_spec_id: String,
    /// Accepted Manifold decision that authorized adoption.
    pub manifold_decision_id: String,
    /// Accepted Manifold session authority revision.
    pub manifold_session_revision: u64,
    /// Source-neutral session plan.
    pub plan: MediaStreamSessionPlan,
    /// Explicit processors selected for this product.
    pub processors: Vec<MediaStreamProcessorDescriptor>,
    /// Explicit sinks selected for this product.
    pub sinks: Vec<MediaStreamSinkDescriptor>,
    /// Per-lane processor/sink composition.
    pub lane_bindings: Vec<MediaStreamLaneRuntimeBinding>,
    /// Product-provider direct-P2P route evidence, when selected.
    pub direct_p2p_routes: Vec<MediaStreamDirectP2pRouteBinding>,
}

/// Processor descriptor. Processors transform existing frames but do not own codecs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamProcessorDescriptor {
    /// Stable processor id.
    pub processor_id: String,
    /// `passthrough_h264`, `dual_lane_independent`, or `packed_sbs_left_right`.
    pub processor_kind: String,
    /// Required input roles.
    pub input_track_roles: Vec<String>,
    /// Produced output roles.
    pub output_track_roles: Vec<String>,
    /// Must remain false; the existing codec adapter owns encode/decode.
    pub owns_codec: bool,
    /// Packed processing must not require CPU pixel copies.
    pub cpu_pixel_copy: bool,
    /// Application policy is forbidden in reusable processor descriptors.
    #[serde(default)]
    pub application_policy_fields: Vec<String>,
}

/// Sink descriptor selected independently from source/processor defaults.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamSinkDescriptor {
    /// Stable sink id.
    pub sink_id: String,
    /// Device that hosts the sink.
    pub device_id: String,
    /// Sink adapter family.
    pub sink_kind: String,
    /// Permissions required only by this sink.
    #[serde(default)]
    pub required_permissions: Vec<String>,
    /// Application policy is forbidden in the reusable sink descriptor.
    #[serde(default)]
    pub application_policy_fields: Vec<String>,
}

/// Runtime composition for one plan lane.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamLaneRuntimeBinding {
    /// Plan lane id.
    pub lane_id: String,
    /// Ordered processor ids.
    pub processor_ids: Vec<String>,
    /// Selected sink id.
    pub sink_id: String,
}

/// One lane bound to a validated product direct-P2P route contract.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamDirectP2pRouteBinding {
    /// Plan lane id.
    pub lane_id: String,
    /// Route consumed by the external Rust socket provider.
    pub route: DirectP2pSocketRoute,
}

/// Source conformance output for independent consumer tests.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamSourceConformanceReceipt {
    /// Source identity.
    pub source_id: String,
    /// Concrete source kind.
    pub source_kind: String,
    /// Capture authority.
    pub capture_authority: String,
    /// Whether camera permission belongs to this source.
    pub camera_permission_required: bool,
    /// Whether operator capture consent belongs to this source.
    pub consent_required: bool,
    /// Reusable source descriptor contains no unrelated permission/profile policy.
    pub permission_or_profile_bleed: bool,
}

/// Processor conformance output.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamProcessorConformanceReceipt {
    /// Processor identity.
    pub processor_id: String,
    /// Processor family.
    pub processor_kind: String,
    /// Whether role shape passed.
    pub role_shape_valid: bool,
    /// Codec ownership remains outside the processor.
    pub codec_owner_external: bool,
    /// No application policy entered the reusable descriptor.
    pub application_policy_absent: bool,
}

/// Platform runtime lifecycle phase.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaStreamRuntimePhase {
    /// Validated plan has not started platform adapters.
    Planned,
    /// Every receiver reported readiness.
    ReceiversArmed,
    /// Source/codec adapters reported start after receiver readiness.
    SourcesStarted,
    /// A sink observed at least one high-rate media frame.
    Streaming,
    /// Adapters and routes reported complete cleanup.
    Stopped,
}

/// Revisioned Quest-side lifecycle state.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamRuntimeState {
    /// Schema id.
    pub schema: String,
    /// Runtime spec id.
    pub runtime_spec_id: String,
    /// Quest lifecycle revision.
    pub runtime_revision: u64,
    /// Current phase.
    pub phase: MediaStreamRuntimePhase,
    /// Applied request ids for replay rejection.
    pub applied_request_ids: Vec<String>,
}

/// Requested lifecycle action.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaStreamRuntimeAction {
    /// Accept receiver readiness evidence.
    ArmReceivers,
    /// Accept source startup after receiver readiness.
    StartSources,
    /// Accept sink-observed media evidence.
    ConfirmStreaming,
    /// Accept terminal cleanup.
    Stop,
}

/// Adapter evidence used by lifecycle review.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamRuntimeEvidence {
    /// All selected receivers are armed.
    pub receivers_armed: bool,
    /// All selected sources are started.
    pub sources_started: bool,
    /// Frames observed by selected sinks.
    pub received_media_frames: u64,
    /// Route, source, processor, and sink cleanup completed.
    pub cleanup_complete: bool,
}

/// Revision-bound lifecycle request.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamRuntimeRequest {
    /// Replay-protected request id.
    pub request_id: String,
    /// Expected Quest lifecycle revision.
    pub expected_runtime_revision: u64,
    /// Requested transition.
    pub action: MediaStreamRuntimeAction,
    /// Adapter evidence for the transition.
    pub evidence: MediaStreamRuntimeEvidence,
}

/// Review/application decision.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStreamRuntimeDecision {
    /// Schema id.
    pub schema: String,
    /// Reviewed request id.
    pub request_id: String,
    /// Quest lifecycle authority; never Manifold session acceptance authority.
    pub decision_owner: String,
    /// Whether review accepted the request.
    pub accepted: bool,
    /// Whether the accepted request advanced state.
    pub applied: bool,
    /// Stable rejection reason.
    pub rejection_reason: Option<String>,
    /// Prior Quest lifecycle revision.
    pub prior_runtime_revision: u64,
    /// Resulting Quest lifecycle revision.
    pub resulting_runtime_revision: u64,
    /// Resulting phase.
    pub resulting_phase: MediaStreamRuntimePhase,
}

/// Stateful deterministic lifecycle evaluator.
pub struct MediaStreamSessionRuntime {
    spec: MediaStreamRuntimeSpec,
    state: MediaStreamRuntimeState,
}

impl MediaStreamSessionRuntime {
    /// Construct a runtime only from a fully validated spec.
    pub fn new(spec: MediaStreamRuntimeSpec) -> Result<Self, Vec<ValidationError>> {
        validate_media_stream_runtime_spec(&spec)?;
        Ok(Self {
            state: MediaStreamRuntimeState {
                schema: MEDIA_STREAM_RUNTIME_STATE_SCHEMA.to_string(),
                runtime_spec_id: spec.runtime_spec_id.clone(),
                runtime_revision: 1,
                phase: MediaStreamRuntimePhase::Planned,
                applied_request_ids: Vec::new(),
            },
            spec,
        })
    }

    /// Read the accepted platform lifecycle state.
    pub fn state(&self) -> &MediaStreamRuntimeState {
        &self.state
    }

    /// Read the immutable selected runtime spec.
    pub fn spec(&self) -> &MediaStreamRuntimeSpec {
        &self.spec
    }

    /// Review and, only when accepted, apply one lifecycle transition.
    pub fn execute(&mut self, request: &MediaStreamRuntimeRequest) -> MediaStreamRuntimeDecision {
        let mut decision = review_media_stream_runtime_request(&self.state, request);
        if decision.accepted {
            self.state.runtime_revision = decision.resulting_runtime_revision;
            self.state.phase = decision.resulting_phase;
            self.state
                .applied_request_ids
                .push(request.request_id.clone());
            decision.applied = true;
        }
        decision
    }
}

/// Validate product composition without starting adapters.
pub fn validate_media_stream_runtime_spec(
    spec: &MediaStreamRuntimeSpec,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if spec.schema != MEDIA_STREAM_RUNTIME_SPEC_SCHEMA {
        errors.push(ValidationError::new(
            "unsupported media runtime spec schema",
        ));
    }
    if spec.runtime_spec_id.trim().is_empty()
        || spec.manifold_decision_id.trim().is_empty()
        || spec.manifold_session_revision == 0
    {
        errors.push(ValidationError::new(
            "runtime spec requires identity and an accepted Manifold decision revision",
        ));
    }
    if let Err(plan_errors) = validate_media_stream_session(&spec.plan) {
        errors.extend(plan_errors);
    }

    let processor_map = collect_unique(
        spec.processors
            .iter()
            .map(|value| value.processor_id.as_str()),
        "processor_id",
        &mut errors,
    );
    for processor in &spec.processors {
        if validate_media_stream_processor(processor).is_err() {
            errors.push(ValidationError::new(format!(
                "processor {} failed conformance",
                processor.processor_id
            )));
        }
    }
    let sink_map = collect_unique(
        spec.sinks.iter().map(|value| value.sink_id.as_str()),
        "sink_id",
        &mut errors,
    );
    let plan_devices = spec
        .plan
        .devices
        .iter()
        .map(|value| value.device_id.as_str())
        .collect::<BTreeSet<_>>();
    for sink in &spec.sinks {
        if sink.sink_kind.trim().is_empty() || !plan_devices.contains(sink.device_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "sink {} must name a plan device and sink kind",
                sink.sink_id
            )));
        }
        if !sink.application_policy_fields.is_empty() {
            errors.push(ValidationError::new(format!(
                "sink {} contains application policy",
                sink.sink_id
            )));
        }
    }
    let lane_map = spec
        .plan
        .lanes
        .iter()
        .map(|lane| (lane.lane_id.as_str(), lane))
        .collect::<BTreeMap<_, _>>();
    let mut bound_lanes = BTreeSet::new();
    for binding in &spec.lane_bindings {
        if !bound_lanes.insert(binding.lane_id.as_str())
            || !lane_map.contains_key(binding.lane_id.as_str())
        {
            errors.push(ValidationError::new(format!(
                "lane runtime binding {} is duplicate or unknown",
                binding.lane_id
            )));
        }
        if !sink_map.contains(binding.sink_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "lane {} references unknown sink",
                binding.lane_id
            )));
        }
        for processor_id in &binding.processor_ids {
            if !processor_map.contains(processor_id.as_str()) {
                errors.push(ValidationError::new(format!(
                    "lane {} references unknown processor",
                    binding.lane_id
                )));
            }
        }
    }
    if bound_lanes.len() != lane_map.len() {
        errors.push(ValidationError::new(
            "every media lane requires exactly one runtime binding",
        ));
    }

    let route_map = spec
        .plan
        .transport_routes
        .iter()
        .map(|route| (route.lane_id.as_str(), route))
        .collect::<BTreeMap<_, _>>();
    let mut direct_lanes = BTreeSet::new();
    for binding in &spec.direct_p2p_routes {
        if !direct_lanes.insert(binding.lane_id.as_str()) {
            errors.push(ValidationError::new(
                "direct-P2P lane binding is duplicated",
            ));
        }
        match route_map.get(binding.lane_id.as_str()) {
            Some(plan_route)
                if plan_route.connect_host == binding.route.peer_host
                    && plan_route.connect_port == binding.route.peer_port => {}
            _ => errors.push(ValidationError::new(format!(
                "direct-P2P route for lane {} must match the plan peer endpoint",
                binding.lane_id
            ))),
        }
        if let Err(route_errors) = validate_direct_p2p_socket_route(&binding.route) {
            errors.extend(
                route_errors
                    .into_iter()
                    .map(|error| ValidationError::new(error.message)),
            );
        }
    }
    if spec.direct_p2p_routes.is_empty() {
        errors.push(ValidationError::new(
            "runtime spec requires an explicit direct-P2P route reference",
        ));
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn collect_unique<'a>(
    values: impl Iterator<Item = &'a str>,
    label: &str,
    errors: &mut Vec<ValidationError>,
) -> BTreeSet<&'a str> {
    let mut seen = BTreeSet::new();
    for value in values {
        if value.trim().is_empty() || !seen.insert(value) {
            errors.push(ValidationError::new(format!(
                "{label} must be nonempty and unique"
            )));
        }
    }
    seen
}

/// Validate one reusable processor and return its conformance receipt.
pub fn validate_media_stream_processor(
    processor: &MediaStreamProcessorDescriptor,
) -> Result<MediaStreamProcessorConformanceReceipt, ValidationError> {
    let role_shape_valid = match processor.processor_kind.as_str() {
        "passthrough_h264" => {
            processor.input_track_roles == processor.output_track_roles
                && processor.input_track_roles.len() == 1
        }
        "dual_lane_independent" => {
            processor.input_track_roles == ["left", "right"]
                && processor.output_track_roles == ["left", "right"]
        }
        "packed_sbs_left_right" => {
            processor.input_track_roles == ["left", "right"]
                && processor.output_track_roles == ["stereo"]
                && !processor.cpu_pixel_copy
        }
        _ => false,
    };
    if !role_shape_valid || processor.owns_codec || !processor.application_policy_fields.is_empty()
    {
        return Err(ValidationError::new(
            "processor ownership or role shape is invalid",
        ));
    }
    Ok(MediaStreamProcessorConformanceReceipt {
        processor_id: processor.processor_id.clone(),
        processor_kind: processor.processor_kind.clone(),
        role_shape_valid,
        codec_owner_external: true,
        application_policy_absent: true,
    })
}

/// Validate one source independently from sink and app policy.
pub fn validate_media_stream_source_conformance(
    plan: &MediaStreamSessionPlan,
    source_id: &str,
) -> Result<MediaStreamSourceConformanceReceipt, Vec<ValidationError>> {
    validate_media_stream_session(plan)?;
    let Some(source) = plan
        .sources
        .iter()
        .find(|source| source.source_id == source_id)
    else {
        return Err(vec![ValidationError::new(
            "source conformance id is unknown",
        )]);
    };
    Ok(MediaStreamSourceConformanceReceipt {
        source_id: source.source_id.clone(),
        source_kind: source.source_kind.clone(),
        capture_authority: source.capture_authority.clone(),
        camera_permission_required: source.camera.is_some(),
        consent_required: source.consent_required,
        permission_or_profile_bleed: false,
    })
}

/// Pure review of a revisioned lifecycle request.
pub fn review_media_stream_runtime_request(
    state: &MediaStreamRuntimeState,
    request: &MediaStreamRuntimeRequest,
) -> MediaStreamRuntimeDecision {
    let rejection = if request.request_id.trim().is_empty() {
        Some("missing_request_id")
    } else if state.runtime_revision == u64::MAX {
        Some("runtime_revision_exhausted")
    } else if request.expected_runtime_revision != state.runtime_revision {
        Some("stale_runtime_revision")
    } else if state
        .applied_request_ids
        .iter()
        .any(|id| id == &request.request_id)
    {
        Some("replayed_request")
    } else {
        match (state.phase, request.action) {
            (MediaStreamRuntimePhase::Planned, MediaStreamRuntimeAction::ArmReceivers)
                if request.evidence.receivers_armed =>
            {
                None
            }
            (MediaStreamRuntimePhase::ReceiversArmed, MediaStreamRuntimeAction::StartSources)
                if request.evidence.sources_started =>
            {
                None
            }
            (
                MediaStreamRuntimePhase::SourcesStarted,
                MediaStreamRuntimeAction::ConfirmStreaming,
            ) if request.evidence.received_media_frames > 0 => None,
            (phase, MediaStreamRuntimeAction::Stop)
                if phase != MediaStreamRuntimePhase::Stopped
                    && request.evidence.cleanup_complete =>
            {
                None
            }
            (_, MediaStreamRuntimeAction::StartSources) => Some("receivers_not_armed"),
            (_, MediaStreamRuntimeAction::ConfirmStreaming) => Some("media_not_observed"),
            (_, MediaStreamRuntimeAction::Stop) => Some("cleanup_incomplete"),
            _ => Some("invalid_phase_transition"),
        }
    };
    let resulting_phase = if rejection.is_some() {
        state.phase
    } else {
        match request.action {
            MediaStreamRuntimeAction::ArmReceivers => MediaStreamRuntimePhase::ReceiversArmed,
            MediaStreamRuntimeAction::StartSources => MediaStreamRuntimePhase::SourcesStarted,
            MediaStreamRuntimeAction::ConfirmStreaming => MediaStreamRuntimePhase::Streaming,
            MediaStreamRuntimeAction::Stop => MediaStreamRuntimePhase::Stopped,
        }
    };
    MediaStreamRuntimeDecision {
        schema: MEDIA_STREAM_RUNTIME_DECISION_SCHEMA.to_string(),
        request_id: request.request_id.clone(),
        decision_owner: MEDIA_STREAM_RUNTIME_AUTHORITY.to_string(),
        accepted: rejection.is_none(),
        applied: false,
        rejection_reason: rejection.map(str::to_string),
        prior_runtime_revision: state.runtime_revision,
        resulting_runtime_revision: state.runtime_revision + u64::from(rejection.is_none()),
        resulting_phase,
    }
}
