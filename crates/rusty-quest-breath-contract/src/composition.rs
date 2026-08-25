//! Pure closed-world composition of source-neutral breath assessments.
//!
//! This module selects one direct source and one mapping independently. It
//! does not acquire sensor data, classify motion, open a panel, select a
//! transport, or interpret the selected signal as an application effect.

use crate::{
    assessment::{BreathAssessmentObservation, BreathTrackingState, CommonBreathPhase},
    calibration::CalibrationLifecycle,
    BreathGeneration, BreathTimestampMicros,
};

/// Stable low-rate composition snapshot schema.
pub const BREATH_COMPOSITION_SNAPSHOT_SCHEMA_ID: &str =
    "rusty.quest.breath_composition.snapshot.v1";

/// One packaged or runtime-supplied activation-binding input.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum BreathCompositionBinding {
    /// No binding was supplied.
    #[default]
    Missing,
    /// A supplied binding was not an exact non-zero SHA-256 digest.
    Malformed,
    /// One exact non-zero SHA-256 digest.
    Digest([u8; 32]),
}

impl BreathCompositionBinding {
    const fn digest_or_zero(self) -> [u8; 32] {
        match self {
            Self::Digest(value) => value,
            Self::Missing | Self::Malformed => [0; 32],
        }
    }
}

/// Direct assessment source selected inside one APK.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathCompositionSource {
    /// `OpenXR` controller-pose assessment.
    Controller,
    /// Polar acceleration assessment from the existing Android PMD/JNI owner.
    PolarAcc,
}

impl BreathCompositionSource {
    /// Stable neutral token.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Controller => "controller",
            Self::PolarAcc => "polar-acc",
        }
    }
}

/// Independent assessment-to-output mapping.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathCompositionMapping {
    /// Expose the calibrated bounded continuous value.
    Volume,
    /// Expose the common categorical phase.
    State,
}

impl BreathCompositionMapping {
    /// Stable neutral token.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Volume => "volume",
            Self::State => "state",
        }
    }
}

/// Controller projection policy owned by the generic adapter.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ControllerProjectionSelection {
    /// Project along a fixed controller-local orientation.
    FixedOrientation,
    /// Learn a principal motion axis from accepted frames.
    #[default]
    DynamicAxis,
}

impl ControllerProjectionSelection {
    /// Stable neutral token.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::FixedOrientation => "fixed-orientation",
            Self::DynamicAxis => "dynamic-axis",
        }
    }
}

/// Polar acceleration projection policy owned by the generic adapter.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum PolarProjectionSelection {
    /// Fit the principal axis in the XZ plane.
    #[default]
    Xz,
    /// Fit the principal axis in three dimensions.
    Full3d,
}

impl PolarProjectionSelection {
    /// Stable neutral token.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Xz => "xz",
            Self::Full3d => "3d",
        }
    }
}

/// One validated source/mapping request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BreathCompositionRequest {
    /// Direct source.
    pub source: BreathCompositionSource,
    /// Independent output mapping.
    pub mapping: BreathCompositionMapping,
    /// Controller volume projection.
    pub controller_projection: ControllerProjectionSelection,
    /// Polar calibration projection.
    pub polar_projection: PolarProjectionSelection,
    /// Reverse the source direction inside the generic estimator.
    pub inverted: bool,
}

/// Closed-world capability closure resolved from the exact feature lock.
#[allow(clippy::struct_excessive_bools)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BreathCompositionCapabilities {
    /// Controller assessment adapter is selected.
    pub controller_assessment: bool,
    /// Polar ACC assessment adapter is selected.
    pub polar_acc_assessment: bool,
    /// Continuous mapping is selected.
    pub volume_mapping: bool,
    /// Categorical mapping is selected.
    pub state_mapping: bool,
    /// Same-APK composition panel is selected.
    pub same_apk_panel: bool,
}

impl BreathCompositionCapabilities {
    fn supports(self, request: BreathCompositionRequest) -> bool {
        let source = match request.source {
            BreathCompositionSource::Controller => self.controller_assessment,
            BreathCompositionSource::PolarAcc => self.polar_acc_assessment,
        };
        let mapping = match request.mapping {
            BreathCompositionMapping::Volume => self.volume_mapping,
            BreathCompositionMapping::State => self.state_mapping,
        };
        source && mapping && self.same_apk_panel
    }
}

/// Stable composition status.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum BreathCompositionStatus {
    /// Feature activation is disabled or no request is selected.
    #[default]
    Disabled,
    /// A request could not be applied by the exact capability closure.
    RejectedUnavailable,
    /// A request is effective but not configured.
    Selected,
    /// The effective source is configured.
    Configured,
    /// One exact generation is running.
    Running,
    /// The exact running generation was cancelled.
    Cancelled,
}

impl BreathCompositionStatus {
    /// Stable neutral token.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::RejectedUnavailable => "rejected-unavailable",
            Self::Selected => "selected",
            Self::Configured => "configured",
            Self::Running => "running",
            Self::Cancelled => "cancelled",
        }
    }
}

/// Panel/lifecycle action accepted by the native composition authority.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathCompositionAction {
    /// Install source-specific estimator configuration.
    Configure,
    /// Start calibration and issue a fresh generation.
    Start,
    /// Cancel the exact active generation.
    Cancel(BreathGeneration),
    /// Clear source histories and retained calibration.
    Reset,
}

/// Typed fail-closed result reason.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathCompositionRejection {
    /// Activation was disabled or lacked an exact non-zero lock binding.
    InactiveFeatureLock,
    /// The APK did not package a resolver-derived expected binding.
    MissingPackagedBinding,
    /// The APK packaged a malformed or all-zero expected binding.
    MalformedPackagedBinding,
    /// The runtime property surface omitted the observed binding.
    MissingRuntimeBinding,
    /// The runtime property surface supplied a malformed or all-zero binding.
    MalformedRuntimeBinding,
    /// The observed runtime binding did not match the packaged expected digest.
    ActivationBindingMismatch,
    /// The requested source/mapping was outside the selected feature closure.
    UnavailableSelection,
    /// The action is invalid in the current composition state.
    InvalidAction,
    /// The assessment came from a source that is not effective.
    UnselectedSource,
    /// The assessment generation is stale or unrelated.
    GenerationMismatch,
    /// The assessment is stale at the injected observation time.
    StaleAssessment,
    /// Tracking/calibration did not permit the selected output.
    InputNotReady,
    /// The selected output was malformed or absent.
    MalformedAssessment,
}

impl BreathCompositionRejection {
    /// Stable reason code for synthetic tests and low-rate UI readback.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InactiveFeatureLock => "inactive-feature-lock",
            Self::MissingPackagedBinding => "missing-packaged-binding",
            Self::MalformedPackagedBinding => "malformed-packaged-binding",
            Self::MissingRuntimeBinding => "missing-runtime-binding",
            Self::MalformedRuntimeBinding => "malformed-runtime-binding",
            Self::ActivationBindingMismatch => "activation-binding-mismatch",
            Self::UnavailableSelection => "unavailable-selection",
            Self::InvalidAction => "invalid-action",
            Self::UnselectedSource => "unselected-source",
            Self::GenerationMismatch => "generation-mismatch",
            Self::StaleAssessment => "stale-assessment",
            Self::InputNotReady => "input-not-ready",
            Self::MalformedAssessment => "malformed-assessment",
        }
    }
}

/// One effective mapping result. Unselected dimensions remain absent.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BreathCompositionOutput {
    /// Selected source.
    pub source: BreathCompositionSource,
    /// Selected mapping.
    pub mapping: BreathCompositionMapping,
    /// Source sequence.
    pub sequence_id: u64,
    /// Source sample timestamp.
    pub sampled_at: BreathTimestampMicros,
    /// Bounded continuous result for volume mapping only.
    pub volume01: Option<f64>,
    /// Common categorical result for state mapping only.
    pub phase: Option<CommonBreathPhase>,
    /// Bounded source-neutral quality.
    pub quality01: f64,
}

/// Saturating neutral composition counters.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BreathCompositionTelemetry {
    /// Selection requests received.
    pub selection_request_count: u64,
    /// Effective selection changes.
    pub selection_change_count: u64,
    /// Mapping-only changes that retained the generation.
    pub mapping_change_count: u64,
    /// Source/projection/direction changes that hard-reset state.
    pub hard_reset_count: u64,
    /// Assessments received.
    pub received_assessment_count: u64,
    /// Assessments admitted to the selected mapping.
    pub accepted_assessment_count: u64,
    /// Assessments rejected by source, generation, age, or bounds.
    pub rejected_assessment_count: u64,
}

/// Complete native-effective low-rate snapshot.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BreathCompositionSnapshot {
    /// Stable schema identifier.
    pub schema_id: &'static str,
    /// Whether exact closed-world activation is valid.
    pub feature_lock_active: bool,
    /// Exact lock digest supplied by the resolver/application boundary.
    pub feature_lock_sha256: [u8; 32],
    /// Resolver-derived digest packaged into this specific native library.
    pub packaged_feature_lock_sha256: [u8; 32],
    /// Latest requested selection, including rejected requests.
    pub requested: Option<BreathCompositionRequest>,
    /// Native-effective selection.
    pub effective: Option<BreathCompositionRequest>,
    /// Composition state.
    pub status: BreathCompositionStatus,
    /// Current exact generation.
    pub generation: Option<BreathGeneration>,
    /// Latest effective output.
    pub output: Option<BreathCompositionOutput>,
    /// Latest exact-source assessment, including not-ready observations.
    pub latest_assessment: Option<BreathAssessmentObservation>,
    /// Latest typed rejection.
    pub rejection: Option<BreathCompositionRejection>,
    /// Neutral counters.
    pub telemetry: BreathCompositionTelemetry,
}

/// Pure single-owner composition authority.
#[derive(Clone, Debug)]
pub struct BreathCompositionAuthority {
    feature_lock_active: bool,
    feature_lock_sha256: [u8; 32],
    packaged_feature_lock_sha256: [u8; 32],
    activation_rejection: Option<BreathCompositionRejection>,
    capabilities: BreathCompositionCapabilities,
    requested: Option<BreathCompositionRequest>,
    effective: Option<BreathCompositionRequest>,
    status: BreathCompositionStatus,
    generation: Option<BreathGeneration>,
    next_generation: u64,
    output: Option<BreathCompositionOutput>,
    latest_assessment: Option<BreathAssessmentObservation>,
    rejection: Option<BreathCompositionRejection>,
    telemetry: BreathCompositionTelemetry,
    stale_after_micros: u64,
}

impl BreathCompositionAuthority {
    /// Create an inert or lock-bound authority.
    #[must_use]
    pub fn new(
        enabled: bool,
        packaged_binding: BreathCompositionBinding,
        runtime_binding: BreathCompositionBinding,
        capabilities: BreathCompositionCapabilities,
        stale_after_micros: u64,
    ) -> Self {
        let activation_rejection = if !enabled {
            None
        } else if stale_after_micros == 0 || stale_after_micros > crate::MAX_STALE_AFTER_MICROS {
            Some(BreathCompositionRejection::InactiveFeatureLock)
        } else {
            match (packaged_binding, runtime_binding) {
                (BreathCompositionBinding::Missing, _) => {
                    Some(BreathCompositionRejection::MissingPackagedBinding)
                }
                (BreathCompositionBinding::Malformed, _) => {
                    Some(BreathCompositionRejection::MalformedPackagedBinding)
                }
                (BreathCompositionBinding::Digest(value), _) if value == [0; 32] => {
                    Some(BreathCompositionRejection::MalformedPackagedBinding)
                }
                (_, BreathCompositionBinding::Missing) => {
                    Some(BreathCompositionRejection::MissingRuntimeBinding)
                }
                (_, BreathCompositionBinding::Malformed) => {
                    Some(BreathCompositionRejection::MalformedRuntimeBinding)
                }
                (_, BreathCompositionBinding::Digest(value)) if value == [0; 32] => {
                    Some(BreathCompositionRejection::MalformedRuntimeBinding)
                }
                (
                    BreathCompositionBinding::Digest(expected),
                    BreathCompositionBinding::Digest(observed),
                ) if expected != observed => {
                    Some(BreathCompositionRejection::ActivationBindingMismatch)
                }
                (BreathCompositionBinding::Digest(_), BreathCompositionBinding::Digest(_)) => None,
            }
        };
        let feature_lock_active = enabled && activation_rejection.is_none();
        let feature_lock_sha256 = runtime_binding.digest_or_zero();
        let packaged_feature_lock_sha256 = packaged_binding.digest_or_zero();
        Self {
            feature_lock_active,
            feature_lock_sha256,
            packaged_feature_lock_sha256,
            activation_rejection,
            capabilities,
            requested: None,
            effective: None,
            status: BreathCompositionStatus::Disabled,
            generation: None,
            next_generation: 1,
            output: None,
            latest_assessment: None,
            rejection: activation_rejection,
            telemetry: BreathCompositionTelemetry::default(),
            stale_after_micros,
        }
    }

    /// Return native-effective state without advancing virtual time.
    #[must_use]
    pub const fn snapshot(&self) -> BreathCompositionSnapshot {
        BreathCompositionSnapshot {
            schema_id: BREATH_COMPOSITION_SNAPSHOT_SCHEMA_ID,
            feature_lock_active: self.feature_lock_active,
            feature_lock_sha256: self.feature_lock_sha256,
            packaged_feature_lock_sha256: self.packaged_feature_lock_sha256,
            requested: self.requested,
            effective: self.effective,
            status: self.status,
            generation: self.generation,
            output: self.output,
            latest_assessment: self.latest_assessment,
            rejection: self.rejection,
            telemetry: self.telemetry,
        }
    }

    /// Validate and apply one independent source/mapping selection.
    pub fn select(
        &mut self,
        request: Option<BreathCompositionRequest>,
    ) -> BreathCompositionSnapshot {
        increment(&mut self.telemetry.selection_request_count);
        self.requested = request;
        self.rejection = None;
        let Some(request) = request else {
            if self.effective.is_some() {
                self.hard_reset();
            }
            self.effective = None;
            self.status = BreathCompositionStatus::Disabled;
            return self.snapshot();
        };
        if !self.feature_lock_active {
            self.effective = None;
            self.hard_reset();
            self.status = BreathCompositionStatus::Disabled;
            self.rejection = self
                .activation_rejection
                .or(Some(BreathCompositionRejection::InactiveFeatureLock));
            return self.snapshot();
        }
        if !self.capabilities.supports(request) {
            self.effective = None;
            self.hard_reset();
            self.status = BreathCompositionStatus::RejectedUnavailable;
            self.rejection = Some(BreathCompositionRejection::UnavailableSelection);
            return self.snapshot();
        }
        if let Some(previous) = self.effective {
            let same_assessment = previous.source == request.source
                && previous.controller_projection == request.controller_projection
                && previous.polar_projection == request.polar_projection
                && previous.inverted == request.inverted;
            if same_assessment && previous.mapping != request.mapping {
                increment(&mut self.telemetry.mapping_change_count);
                increment(&mut self.telemetry.selection_change_count);
                self.effective = Some(request);
                self.output = None;
                return self.snapshot();
            }
            if previous != request {
                self.hard_reset();
            }
        }
        if self.effective != Some(request) {
            increment(&mut self.telemetry.selection_change_count);
        }
        self.effective = Some(request);
        self.status = BreathCompositionStatus::Selected;
        self.snapshot()
    }

    /// Apply one lifecycle action after selection validation.
    pub fn action(&mut self, action: BreathCompositionAction) -> BreathCompositionSnapshot {
        self.rejection = None;
        if !self.feature_lock_active || self.effective.is_none() {
            self.rejection = self
                .activation_rejection
                .or(Some(BreathCompositionRejection::InactiveFeatureLock));
            return self.snapshot();
        }
        match action {
            BreathCompositionAction::Configure
                if matches!(
                    self.status,
                    BreathCompositionStatus::Selected
                        | BreathCompositionStatus::Cancelled
                        | BreathCompositionStatus::Configured
                ) =>
            {
                self.generation = None;
                self.output = None;
                self.status = BreathCompositionStatus::Configured;
            }
            BreathCompositionAction::Start
                if matches!(
                    self.status,
                    BreathCompositionStatus::Configured | BreathCompositionStatus::Cancelled
                ) =>
            {
                let Ok(generation) = BreathGeneration::new(self.next_generation) else {
                    self.rejection = Some(BreathCompositionRejection::InvalidAction);
                    return self.snapshot();
                };
                let Some(next) = self.next_generation.checked_add(1) else {
                    self.rejection = Some(BreathCompositionRejection::InvalidAction);
                    return self.snapshot();
                };
                self.next_generation = next;
                self.generation = Some(generation);
                self.output = None;
                self.status = BreathCompositionStatus::Running;
            }
            BreathCompositionAction::Cancel(generation)
                if self.status == BreathCompositionStatus::Running
                    && self.generation == Some(generation) =>
            {
                self.generation = None;
                self.output = None;
                self.status = BreathCompositionStatus::Cancelled;
            }
            BreathCompositionAction::Reset => {
                self.hard_reset();
                self.status = BreathCompositionStatus::Selected;
            }
            _ => self.rejection = Some(BreathCompositionRejection::InvalidAction),
        }
        self.snapshot()
    }

    /// Admit one common assessment from the exact selected source/generation.
    pub fn observe(
        &mut self,
        at: BreathTimestampMicros,
        source: BreathCompositionSource,
        assessment: BreathAssessmentObservation,
    ) -> BreathCompositionSnapshot {
        increment(&mut self.telemetry.received_assessment_count);
        self.output = None;
        self.latest_assessment = Some(assessment);
        self.rejection = None;
        let Some(selection) = self.effective else {
            return self.reject_assessment(BreathCompositionRejection::InactiveFeatureLock);
        };
        if self.status != BreathCompositionStatus::Running {
            return self.reject_assessment(BreathCompositionRejection::InvalidAction);
        }
        if selection.source != source {
            return self.reject_assessment(BreathCompositionRejection::UnselectedSource);
        }
        if self.generation != Some(assessment.generation) {
            return self.reject_assessment(BreathCompositionRejection::GenerationMismatch);
        }
        if assessment.sampled_at > at
            || at.get().saturating_sub(assessment.sampled_at.get()) > self.stale_after_micros
        {
            return self.reject_assessment(BreathCompositionRejection::StaleAssessment);
        }
        if assessment.tracking != BreathTrackingState::Valid {
            return self.reject_assessment(BreathCompositionRejection::InputNotReady);
        }
        let (volume01, phase) = match selection.mapping {
            BreathCompositionMapping::Volume => {
                if assessment.calibration != CalibrationLifecycle::Ready {
                    return self.reject_assessment(BreathCompositionRejection::InputNotReady);
                }
                let Some(volume) = assessment
                    .volume01
                    .filter(|value| value.is_finite() && (0.0..=1.0).contains(value))
                else {
                    return self.reject_assessment(BreathCompositionRejection::MalformedAssessment);
                };
                (Some(volume), None)
            }
            BreathCompositionMapping::State => {
                if matches!(
                    assessment.phase,
                    CommonBreathPhase::Unknown | CommonBreathPhase::BadTracking
                ) {
                    return self.reject_assessment(BreathCompositionRejection::InputNotReady);
                }
                (None, Some(assessment.phase))
            }
        };
        if !assessment.quality01.is_finite() || !(0.0..=1.0).contains(&assessment.quality01) {
            return self.reject_assessment(BreathCompositionRejection::MalformedAssessment);
        }
        self.output = Some(BreathCompositionOutput {
            source,
            mapping: selection.mapping,
            sequence_id: assessment.sequence_id,
            sampled_at: assessment.sampled_at,
            volume01,
            phase,
            quality01: assessment.quality01,
        });
        increment(&mut self.telemetry.accepted_assessment_count);
        self.snapshot()
    }

    fn hard_reset(&mut self) {
        self.generation = None;
        self.output = None;
        self.latest_assessment = None;
        increment(&mut self.telemetry.hard_reset_count);
    }

    fn reject_assessment(
        &mut self,
        reason: BreathCompositionRejection,
    ) -> BreathCompositionSnapshot {
        self.rejection = Some(reason);
        increment(&mut self.telemetry.rejected_assessment_count);
        self.snapshot()
    }
}

fn increment(counter: &mut u64) {
    *counter = counter.saturating_add(1);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        assessment::BreathAssessmentFields, calibration::CalibrationLifecycle, BreathLifecycle,
    };

    fn capabilities() -> BreathCompositionCapabilities {
        BreathCompositionCapabilities {
            controller_assessment: true,
            polar_acc_assessment: true,
            volume_mapping: true,
            state_mapping: true,
            same_apk_panel: true,
        }
    }

    fn request(
        source: BreathCompositionSource,
        mapping: BreathCompositionMapping,
    ) -> BreathCompositionRequest {
        BreathCompositionRequest {
            source,
            mapping,
            controller_projection: ControllerProjectionSelection::DynamicAxis,
            polar_projection: PolarProjectionSelection::Xz,
            inverted: false,
        }
    }

    fn authority() -> BreathCompositionAuthority {
        BreathCompositionAuthority::new(
            true,
            BreathCompositionBinding::Digest([0x5a; 32]),
            BreathCompositionBinding::Digest([0x5a; 32]),
            capabilities(),
            250_000,
        )
    }

    fn start(
        authority: &mut BreathCompositionAuthority,
        request: BreathCompositionRequest,
    ) -> BreathGeneration {
        authority.select(Some(request));
        authority.action(BreathCompositionAction::Configure);
        authority.action(BreathCompositionAction::Start);
        authority.snapshot().generation.expect("running generation")
    }

    fn assessment(
        generation: BreathGeneration,
        phase: CommonBreathPhase,
        volume01: Option<f64>,
    ) -> BreathAssessmentObservation {
        BreathAssessmentObservation::new(BreathAssessmentFields {
            generation,
            sequence_id: 7,
            sampled_at: BreathTimestampMicros::new(100_000),
            observed_at: BreathTimestampMicros::new(110_000),
            volume01,
            phase,
            calibration: CalibrationLifecycle::Ready,
            tracking: BreathTrackingState::Valid,
            quality01: 0.8,
        })
        .expect("bounded assessment")
    }

    #[test]
    fn exact_capability_closure_represents_the_four_way_matrix() {
        for source in [
            BreathCompositionSource::Controller,
            BreathCompositionSource::PolarAcc,
        ] {
            for mapping in [
                BreathCompositionMapping::Volume,
                BreathCompositionMapping::State,
            ] {
                let mut authority = authority();
                let snapshot = authority.select(Some(request(source, mapping)));
                assert_eq!(snapshot.effective, Some(request(source, mapping)));
                assert_eq!(snapshot.status, BreathCompositionStatus::Selected);
            }
        }
    }

    #[test]
    fn mapping_change_preserves_generation_but_source_change_hard_resets() {
        let mut authority = authority();
        let generation = start(
            &mut authority,
            request(
                BreathCompositionSource::Controller,
                BreathCompositionMapping::Volume,
            ),
        );
        let changed = authority.select(Some(request(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::State,
        )));
        assert_eq!(changed.generation, Some(generation));
        assert_eq!(changed.status, BreathCompositionStatus::Running);
        assert_eq!(changed.telemetry.mapping_change_count, 1);

        let switched = authority.select(Some(request(
            BreathCompositionSource::PolarAcc,
            BreathCompositionMapping::State,
        )));
        assert_eq!(switched.generation, None);
        assert_eq!(switched.status, BreathCompositionStatus::Selected);
        assert!(switched.telemetry.hard_reset_count >= 1);
    }

    #[test]
    fn disabled_and_unavailable_feature_closures_stay_inert() {
        let requested = request(
            BreathCompositionSource::PolarAcc,
            BreathCompositionMapping::State,
        );
        let mut disabled = BreathCompositionAuthority::new(
            false,
            BreathCompositionBinding::Missing,
            BreathCompositionBinding::Missing,
            capabilities(),
            250_000,
        );
        let snapshot = disabled.select(Some(requested));
        assert_eq!(snapshot.effective, None);
        assert_eq!(
            snapshot.rejection,
            Some(BreathCompositionRejection::InactiveFeatureLock)
        );

        let mut incomplete = capabilities();
        incomplete.state_mapping = false;
        let mut authority = BreathCompositionAuthority::new(
            true,
            BreathCompositionBinding::Digest([1; 32]),
            BreathCompositionBinding::Digest([1; 32]),
            incomplete,
            250_000,
        );
        let snapshot = authority.select(Some(requested));
        assert_eq!(snapshot.requested, Some(requested));
        assert_eq!(snapshot.effective, None);
        assert_eq!(
            snapshot.status,
            BreathCompositionStatus::RejectedUnavailable
        );
    }

    #[test]
    fn activation_requires_an_exact_packaged_runtime_binding_match() {
        let requested = request(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::Volume,
        );
        for (packaged, runtime, expected) in [
            (
                BreathCompositionBinding::Missing,
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionRejection::MissingPackagedBinding,
            ),
            (
                BreathCompositionBinding::Malformed,
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionRejection::MalformedPackagedBinding,
            ),
            (
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionBinding::Missing,
                BreathCompositionRejection::MissingRuntimeBinding,
            ),
            (
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionBinding::Malformed,
                BreathCompositionRejection::MalformedRuntimeBinding,
            ),
            (
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionBinding::Digest([2; 32]),
                BreathCompositionRejection::ActivationBindingMismatch,
            ),
        ] {
            let mut authority =
                BreathCompositionAuthority::new(true, packaged, runtime, capabilities(), 250_000);
            let snapshot = authority.select(Some(requested));
            assert!(!snapshot.feature_lock_active);
            assert_eq!(snapshot.effective, None);
            assert_eq!(snapshot.rejection, Some(expected));
        }

        let active = authority();
        assert!(active.snapshot().feature_lock_active);
        assert_eq!(
            active.snapshot().feature_lock_sha256,
            active.snapshot().packaged_feature_lock_sha256
        );
    }

    #[test]
    fn actions_are_ordered_and_generation_fenced() {
        let mut authority = authority();
        authority.select(Some(request(
            BreathCompositionSource::Controller,
            BreathCompositionMapping::Volume,
        )));
        assert_eq!(
            authority.action(BreathCompositionAction::Start).rejection,
            Some(BreathCompositionRejection::InvalidAction)
        );
        authority.action(BreathCompositionAction::Configure);
        let generation = authority
            .action(BreathCompositionAction::Start)
            .generation
            .expect("generation");
        let other = BreathGeneration::new(generation.get() + 1).expect("other generation");
        assert_eq!(
            authority
                .action(BreathCompositionAction::Cancel(other))
                .rejection,
            Some(BreathCompositionRejection::InvalidAction)
        );
        assert_eq!(
            authority
                .action(BreathCompositionAction::Cancel(generation))
                .status,
            BreathCompositionStatus::Cancelled
        );
    }

    #[test]
    fn selected_mapping_exposes_only_its_dimension_for_both_sources() {
        for source in [
            BreathCompositionSource::Controller,
            BreathCompositionSource::PolarAcc,
        ] {
            let mut volume = authority();
            let generation = start(
                &mut volume,
                request(source, BreathCompositionMapping::Volume),
            );
            let output = volume
                .observe(
                    BreathTimestampMicros::new(120_000),
                    source,
                    assessment(generation, CommonBreathPhase::Inhale, Some(0.65)),
                )
                .output
                .expect("volume output");
            assert_eq!(output.volume01, Some(0.65));
            assert_eq!(output.phase, None);

            let mut state = authority();
            let generation = start(&mut state, request(source, BreathCompositionMapping::State));
            let output = state
                .observe(
                    BreathTimestampMicros::new(120_000),
                    source,
                    assessment(generation, CommonBreathPhase::Hold, Some(0.5)),
                )
                .output
                .expect("state output");
            assert_eq!(output.volume01, None);
            assert_eq!(output.phase, Some(CommonBreathPhase::Hold));
        }
    }

    #[test]
    fn stale_malformed_and_unselected_assessments_fail_closed() {
        let mut authority = authority();
        let generation = start(
            &mut authority,
            request(
                BreathCompositionSource::Controller,
                BreathCompositionMapping::Volume,
            ),
        );
        let valid = assessment(generation, CommonBreathPhase::Inhale, Some(0.4));
        assert_eq!(
            authority
                .observe(
                    BreathTimestampMicros::new(120_000),
                    BreathCompositionSource::PolarAcc,
                    valid,
                )
                .rejection,
            Some(BreathCompositionRejection::UnselectedSource)
        );
        assert_eq!(
            authority
                .observe(
                    BreathTimestampMicros::new(500_001),
                    BreathCompositionSource::Controller,
                    valid,
                )
                .rejection,
            Some(BreathCompositionRejection::StaleAssessment)
        );
        let without_volume = assessment(generation, CommonBreathPhase::Inhale, None);
        assert_eq!(
            authority
                .observe(
                    BreathTimestampMicros::new(120_000),
                    BreathCompositionSource::Controller,
                    without_volume,
                )
                .rejection,
            Some(BreathCompositionRejection::MalformedAssessment)
        );
    }

    #[test]
    fn lifecycle_type_remains_source_neutral() {
        assert_eq!(BreathLifecycle::Running.as_str(), "running");
    }
}
