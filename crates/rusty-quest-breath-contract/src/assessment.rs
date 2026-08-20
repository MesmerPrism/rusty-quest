//! Source-neutral concurrent volume and phase observation boundary.

use crate::{calibration::CalibrationLifecycle, BreathGeneration, BreathTimestampMicros};

/// Schema identifier for a common volume-and-phase observation.
pub const BREATH_ASSESSMENT_OBSERVATION_SCHEMA_ID: &str =
    "rusty.quest.breath_assessment.observation.v1";

/// Minimal common phase vocabulary.
///
/// This boundary normalizes existing classifiers. It deliberately does not
/// define hysteresis, derivative, dwell, or endpoint policy.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum CommonBreathPhase {
    /// No phase has been established.
    #[default]
    Unknown,
    /// Existing classifier reports inward progression.
    Inhale,
    /// Existing classifier reports outward progression.
    Exhale,
    /// Existing classifier reports no directional progression.
    Hold,
    /// Input guards rejected tracking for phase assessment.
    BadTracking,
}

impl CommonBreathPhase {
    /// Stable neutral token for telemetry and fixtures.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Unknown => "unknown",
            Self::Inhale => "inhale",
            Self::Exhale => "exhale",
            Self::Hold => "hold",
            Self::BadTracking => "bad-tracking",
        }
    }
}

/// Source-neutral input and guard state.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum BreathTrackingState {
    /// No assessment is selected or active.
    #[default]
    Disabled,
    /// A finite, current input passed all active guards.
    Valid,
    /// Calibration is collecting accepted motion frames.
    Calibrating,
    /// No input was available.
    Missing,
    /// Input exceeded its age bound.
    Stale,
    /// Input shape or value was malformed.
    Malformed,
    /// Input timestamp or sequence was not strictly ordered.
    OutOfOrder,
    /// Motion guards rejected the input.
    RejectedMotion,
    /// Rotation guards rejected the input.
    RejectedRotation,
}

impl BreathTrackingState {
    /// Stable neutral token for telemetry and fixtures.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Valid => "valid",
            Self::Calibrating => "calibrating",
            Self::Missing => "missing",
            Self::Stale => "stale",
            Self::Malformed => "malformed",
            Self::OutOfOrder => "out-of-order",
            Self::RejectedMotion => "rejected-motion",
            Self::RejectedRotation => "rejected-rotation",
        }
    }
}

/// Common concurrent volume and phase observation.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BreathAssessmentObservation {
    /// Stable schema identifier.
    pub schema_id: &'static str,
    /// Exact active generation.
    pub generation: BreathGeneration,
    /// Strictly increasing source sequence.
    pub sequence_id: u64,
    /// Source timestamp.
    pub sampled_at: BreathTimestampMicros,
    /// Observation timestamp.
    pub observed_at: BreathTimestampMicros,
    /// Bounded live volume when calibration is ready.
    pub volume01: Option<f64>,
    /// Minimal normalized phase from the active classifier.
    pub phase: CommonBreathPhase,
    /// Current calibration lifecycle.
    pub calibration: CalibrationLifecycle,
    /// Current input/guard state.
    pub tracking: BreathTrackingState,
    /// Bounded neutral assessment quality.
    pub quality01: f64,
}

/// Candidate fields validated into a common assessment observation.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BreathAssessmentFields {
    /// Exact active generation.
    pub generation: BreathGeneration,
    /// Strictly increasing source sequence.
    pub sequence_id: u64,
    /// Source timestamp.
    pub sampled_at: BreathTimestampMicros,
    /// Observation timestamp.
    pub observed_at: BreathTimestampMicros,
    /// Bounded live volume when calibration is ready.
    pub volume01: Option<f64>,
    /// Minimal normalized phase from the active classifier.
    pub phase: CommonBreathPhase,
    /// Current calibration lifecycle.
    pub calibration: CalibrationLifecycle,
    /// Current input/guard state.
    pub tracking: BreathTrackingState,
    /// Bounded neutral assessment quality.
    pub quality01: f64,
}

impl BreathAssessmentObservation {
    /// Construct an observation after checking bounded fields and timestamps.
    ///
    /// # Errors
    ///
    /// Returns a typed error for non-finite/out-of-range values or a source
    /// timestamp later than its observation timestamp.
    pub fn new(fields: BreathAssessmentFields) -> Result<Self, BreathAssessmentError> {
        if fields.sampled_at > fields.observed_at {
            return Err(BreathAssessmentError::FutureTimestamp);
        }
        if fields.volume01.is_some_and(|value| !value.is_finite()) {
            return Err(BreathAssessmentError::NonFiniteVolume);
        }
        if fields
            .volume01
            .is_some_and(|value| !(0.0..=1.0).contains(&value))
        {
            return Err(BreathAssessmentError::VolumeOutOfBounds);
        }
        if !fields.quality01.is_finite() {
            return Err(BreathAssessmentError::NonFiniteQuality);
        }
        if !(0.0..=1.0).contains(&fields.quality01) {
            return Err(BreathAssessmentError::QualityOutOfBounds);
        }
        Ok(Self {
            schema_id: BREATH_ASSESSMENT_OBSERVATION_SCHEMA_ID,
            generation: fields.generation,
            sequence_id: fields.sequence_id,
            sampled_at: fields.sampled_at,
            observed_at: fields.observed_at,
            volume01: fields.volume01,
            phase: fields.phase,
            calibration: fields.calibration,
            tracking: fields.tracking,
            quality01: fields.quality01,
        })
    }
}

/// Typed common-observation construction failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BreathAssessmentError {
    /// Source timestamp was later than observation time.
    FutureTimestamp,
    /// Volume was NaN or infinite.
    NonFiniteVolume,
    /// Volume was outside `[0, 1]`.
    VolumeOutOfBounds,
    /// Quality was NaN or infinite.
    NonFiniteQuality,
    /// Quality was outside `[0, 1]`.
    QualityOutOfBounds,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn generation() -> BreathGeneration {
        BreathGeneration::new(1).expect("non-zero generation")
    }

    #[test]
    fn common_observation_bounds_fail_closed() {
        let fields = BreathAssessmentFields {
            generation: generation(),
            sequence_id: 1,
            sampled_at: BreathTimestampMicros::new(10),
            observed_at: BreathTimestampMicros::new(11),
            volume01: Some(0.5),
            phase: CommonBreathPhase::Hold,
            calibration: CalibrationLifecycle::Ready,
            tracking: BreathTrackingState::Valid,
            quality01: 0.75,
        };
        let valid = BreathAssessmentObservation::new(fields).expect("valid observation");
        assert_eq!(valid.schema_id, BREATH_ASSESSMENT_OBSERVATION_SCHEMA_ID);

        assert_eq!(
            BreathAssessmentObservation::new(BreathAssessmentFields {
                sampled_at: BreathTimestampMicros::new(12),
                ..fields
            }),
            Err(BreathAssessmentError::FutureTimestamp)
        );
        assert_eq!(
            BreathAssessmentObservation::new(BreathAssessmentFields {
                volume01: Some(f64::NAN),
                ..fields
            }),
            Err(BreathAssessmentError::NonFiniteVolume)
        );
        assert_eq!(
            BreathAssessmentObservation::new(BreathAssessmentFields {
                volume01: Some(1.1),
                ..fields
            }),
            Err(BreathAssessmentError::VolumeOutOfBounds)
        );
        assert_eq!(
            BreathAssessmentObservation::new(BreathAssessmentFields {
                quality01: f64::INFINITY,
                ..fields
            }),
            Err(BreathAssessmentError::NonFiniteQuality)
        );
        assert_eq!(
            BreathAssessmentObservation::new(BreathAssessmentFields {
                quality01: -0.01,
                ..fields
            }),
            Err(BreathAssessmentError::QualityOutOfBounds)
        );
    }

    #[test]
    fn phase_and_tracking_tokens_are_stable() {
        assert_eq!(CommonBreathPhase::Hold.as_str(), "hold");
        assert_eq!(CommonBreathPhase::BadTracking.as_str(), "bad-tracking");
        assert_eq!(
            BreathTrackingState::RejectedRotation.as_str(),
            "rejected-rotation"
        );
    }
}
