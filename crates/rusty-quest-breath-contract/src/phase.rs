//! Pure timestamp-aware classification of a normalized breathing signal.
//!
//! The classifier owns derivative filtering, hysteresis, confirmation, dwell,
//! and fail-closed history reset. It has no clock, source, transport, or
//! platform dependency; callers inject all timestamps and reset causes.

use crate::{assessment::CommonBreathPhase, BreathTimestampMicros};

/// Stable schema identifier for phase observations.
pub const COMMON_PHASE_OBSERVATION_SCHEMA_ID: &str = "rusty.quest.breath_phase.observation.v1";

const MAX_DERIVATIVE_PER_SECOND: f64 = 1_000.0;
const MAX_PHASE_INTERVAL_MICROS: u64 = 10_000_000;

/// Unvalidated, source-neutral classifier parameters.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CommonPhaseParameters {
    /// Absolute filtered derivative required to enter a directional candidate.
    pub enter_derivative_per_second: f64,
    /// Absolute derivative below which a directional phase may exit.
    pub exit_derivative_per_second: f64,
    /// EMA weight assigned to the newest timestamp-normalized derivative.
    pub derivative_filter_alpha: f64,
    /// Required continuous directional-candidate confirmation.
    pub directional_confirmation_micros: u64,
    /// Required continuous quiet-candidate confirmation before `Hold`.
    pub hold_confirmation_micros: u64,
    /// Minimum committed-phase dwell before another transition.
    pub minimum_phase_dwell_micros: u64,
    /// Maximum age of an input at observation time.
    pub stale_after_micros: u64,
    /// Maximum gap between accepted source timestamps.
    pub discontinuity_after_micros: u64,
    /// Reverse directional interpretation without changing the input value.
    pub inverted: bool,
}

impl Default for CommonPhaseParameters {
    fn default() -> Self {
        Self {
            enter_derivative_per_second: 0.10,
            exit_derivative_per_second: 0.035,
            derivative_filter_alpha: 0.45,
            directional_confirmation_micros: 100_000,
            hold_confirmation_micros: 140_000,
            minimum_phase_dwell_micros: 160_000,
            stale_after_micros: 250_000,
            discontinuity_after_micros: 1_500_000,
            inverted: false,
        }
    }
}

/// Validated immutable phase configuration.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CommonPhaseConfiguration(CommonPhaseParameters);

impl CommonPhaseConfiguration {
    /// Validate all signal and temporal bounds.
    ///
    /// # Errors
    ///
    /// Returns a typed error for non-finite, inverted, zero, or over-limit
    /// values.
    pub fn new(parameters: CommonPhaseParameters) -> Result<Self, CommonPhaseConfigurationError> {
        if !parameters.enter_derivative_per_second.is_finite()
            || parameters.enter_derivative_per_second <= 0.0
            || parameters.enter_derivative_per_second > MAX_DERIVATIVE_PER_SECOND
        {
            return Err(CommonPhaseConfigurationError::InvalidEnterDerivative);
        }
        if !parameters.exit_derivative_per_second.is_finite()
            || parameters.exit_derivative_per_second < 0.0
            || parameters.exit_derivative_per_second >= parameters.enter_derivative_per_second
        {
            return Err(CommonPhaseConfigurationError::InvalidExitDerivative);
        }
        if !parameters.derivative_filter_alpha.is_finite()
            || !(0.0..=1.0).contains(&parameters.derivative_filter_alpha)
            || parameters.derivative_filter_alpha == 0.0
        {
            return Err(CommonPhaseConfigurationError::InvalidFilterAlpha);
        }
        if parameters.directional_confirmation_micros == 0
            || parameters.directional_confirmation_micros > MAX_PHASE_INTERVAL_MICROS
        {
            return Err(CommonPhaseConfigurationError::InvalidDirectionalConfirmation);
        }
        if parameters.hold_confirmation_micros == 0
            || parameters.hold_confirmation_micros > MAX_PHASE_INTERVAL_MICROS
        {
            return Err(CommonPhaseConfigurationError::InvalidHoldConfirmation);
        }
        if parameters.minimum_phase_dwell_micros > MAX_PHASE_INTERVAL_MICROS {
            return Err(CommonPhaseConfigurationError::InvalidMinimumDwell);
        }
        if parameters.stale_after_micros == 0
            || parameters.stale_after_micros > crate::MAX_STALE_AFTER_MICROS
        {
            return Err(CommonPhaseConfigurationError::InvalidStaleInterval);
        }
        if parameters.discontinuity_after_micros <= parameters.stale_after_micros
            || parameters.discontinuity_after_micros > crate::MAX_DISCONTINUITY_AFTER_MICROS
        {
            return Err(CommonPhaseConfigurationError::InvalidDiscontinuityInterval);
        }
        Ok(Self(parameters))
    }

    /// Return the validated parameters.
    #[must_use]
    pub const fn parameters(self) -> CommonPhaseParameters {
        self.0
    }
}

/// Typed phase-configuration failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CommonPhaseConfigurationError {
    /// Directional entry derivative is non-finite, non-positive, or too large.
    InvalidEnterDerivative,
    /// Exit derivative is non-finite, negative, or not below entry.
    InvalidExitDerivative,
    /// Derivative EMA alpha is not finite and in `(0, 1]`.
    InvalidFilterAlpha,
    /// Directional confirmation is zero or over its public bound.
    InvalidDirectionalConfirmation,
    /// Hold confirmation is zero or over its public bound.
    InvalidHoldConfirmation,
    /// Minimum phase dwell exceeds its public bound.
    InvalidMinimumDwell,
    /// Stale interval is zero or exceeds the common contract bound.
    InvalidStaleInterval,
    /// Discontinuity interval is not greater than stale or exceeds its bound.
    InvalidDiscontinuityInterval,
}

/// One explicit phase-classifier input.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CommonPhaseInput {
    /// No source value is available at this action time.
    Missing,
    /// One normalized, strictly ordered source value.
    Sample {
        /// Strictly increasing source sequence.
        sequence_id: u64,
        /// Source timestamp in the injected monotonic domain.
        sampled_at: BreathTimestampMicros,
        /// Finite normalized signal in `[0, 1]`.
        value01: f64,
    },
}

/// Explicit reason that classification history was cleared.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CommonPhaseResetReason {
    /// A caller explicitly cleared retained history.
    Explicit,
    /// Input source selection changed.
    SourceChanged,
    /// Calibration is not ready or restarted.
    CalibrationChanged,
    /// No input was available.
    Missing,
    /// Input exceeded its maximum age.
    Stale,
    /// Input was malformed.
    Malformed,
    /// Source sequence or timestamp was not strictly increasing.
    OutOfOrder,
    /// Injected action time regressed.
    TimeRegression,
    /// Accepted source timestamps had an excessive forward gap.
    TimeDiscontinuity,
    /// The owning lifecycle stopped or changed generation.
    LifecycleChanged,
}

impl CommonPhaseResetReason {
    /// Stable neutral telemetry token.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Explicit => "explicit",
            Self::SourceChanged => "source-changed",
            Self::CalibrationChanged => "calibration-changed",
            Self::Missing => "missing",
            Self::Stale => "stale",
            Self::Malformed => "malformed",
            Self::OutOfOrder => "out-of-order",
            Self::TimeRegression => "time-regression",
            Self::TimeDiscontinuity => "time-discontinuity",
            Self::LifecycleChanged => "lifecycle-changed",
        }
    }
}

/// Stable result classification for one action.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CommonPhaseStatus {
    /// First valid sample established timestamp/value history.
    Primed,
    /// A different phase candidate is awaiting confirmation or dwell.
    Confirming,
    /// The committed phase remains stable.
    Stable,
    /// A fully confirmed candidate became the committed phase.
    Transitioned,
    /// History was cleared for the typed reason.
    Reset(CommonPhaseResetReason),
}

/// Saturating source-neutral phase telemetry.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct CommonPhaseTelemetry {
    /// Explicit observations received.
    pub received_input_count: u64,
    /// Structurally valid, current, ordered samples accepted.
    pub accepted_sample_count: u64,
    /// Candidate changes after derivative classification.
    pub candidate_change_count: u64,
    /// Confirmed committed-phase changes.
    pub phase_transition_count: u64,
    /// Confirmed transitions into `Hold`.
    pub hold_transition_count: u64,
    /// Missing input resets.
    pub missing_reset_count: u64,
    /// Stale input resets.
    pub stale_reset_count: u64,
    /// Malformed input resets.
    pub malformed_reset_count: u64,
    /// Out-of-order input resets.
    pub out_of_order_reset_count: u64,
    /// Action-time regression resets.
    pub time_regression_reset_count: u64,
    /// Forward-discontinuity resets.
    pub discontinuity_reset_count: u64,
    /// Caller-requested reset count.
    pub explicit_reset_count: u64,
}

/// Complete deterministic phase snapshot.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CommonPhaseObservation {
    /// Stable schema identifier.
    pub schema_id: &'static str,
    /// Latest action disposition.
    pub status: CommonPhaseStatus,
    /// Committed normalized phase.
    pub phase: CommonBreathPhase,
    /// Current uncommitted or maintained phase candidate.
    pub candidate: CommonBreathPhase,
    /// Latest accepted sequence, if history is primed.
    pub sequence_id: Option<u64>,
    /// Latest accepted source timestamp, if history is primed.
    pub sampled_at: Option<BreathTimestampMicros>,
    /// Current injected action timestamp.
    pub observed_at: BreathTimestampMicros,
    /// Timestamp-normalized derivative before filtering.
    pub raw_derivative_per_second: Option<f64>,
    /// Timestamp-normalized derivative after filtering.
    pub filtered_derivative_per_second: Option<f64>,
    /// Continuous age of the current candidate.
    pub candidate_age_micros: Option<u64>,
    /// Dwell of the currently committed phase.
    pub phase_dwell_micros: Option<u64>,
    /// Saturating classifier telemetry.
    pub telemetry: CommonPhaseTelemetry,
}

/// Pure timestamp-aware phase owner.
#[derive(Clone, Debug)]
pub struct CommonPhaseClassifier {
    configuration: CommonPhaseConfiguration,
    phase: CommonBreathPhase,
    phase_started_at: Option<BreathTimestampMicros>,
    candidate: CommonBreathPhase,
    candidate_started_at: Option<BreathTimestampMicros>,
    last_sequence_id: Option<u64>,
    last_sampled_at: Option<BreathTimestampMicros>,
    last_value01: Option<f64>,
    filtered_derivative_per_second: Option<f64>,
    last_observed_at: Option<BreathTimestampMicros>,
    telemetry: CommonPhaseTelemetry,
}

impl CommonPhaseClassifier {
    /// Construct a classifier from validated parameters.
    #[must_use]
    pub const fn new(configuration: CommonPhaseConfiguration) -> Self {
        Self {
            configuration,
            phase: CommonBreathPhase::Unknown,
            phase_started_at: None,
            candidate: CommonBreathPhase::Unknown,
            candidate_started_at: None,
            last_sequence_id: None,
            last_sampled_at: None,
            last_value01: None,
            filtered_derivative_per_second: None,
            last_observed_at: None,
            telemetry: CommonPhaseTelemetry {
                received_input_count: 0,
                accepted_sample_count: 0,
                candidate_change_count: 0,
                phase_transition_count: 0,
                hold_transition_count: 0,
                missing_reset_count: 0,
                stale_reset_count: 0,
                malformed_reset_count: 0,
                out_of_order_reset_count: 0,
                time_regression_reset_count: 0,
                discontinuity_reset_count: 0,
                explicit_reset_count: 0,
            },
        }
    }

    /// Observe one explicit input at an injected action timestamp.
    #[must_use]
    pub fn observe(
        &mut self,
        observed_at: BreathTimestampMicros,
        input: CommonPhaseInput,
    ) -> CommonPhaseObservation {
        increment(&mut self.telemetry.received_input_count);
        if self
            .last_observed_at
            .is_some_and(|previous| observed_at < previous)
        {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::TimeRegression);
        }
        self.last_observed_at = Some(observed_at);
        let CommonPhaseInput::Sample {
            sequence_id,
            sampled_at,
            value01,
        } = input
        else {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::Missing);
        };
        if sequence_id == 0
            || sampled_at > observed_at
            || !value01.is_finite()
            || !(0.0..=1.0).contains(&value01)
        {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::Malformed);
        }
        let parameters = self.configuration.parameters();
        if observed_at.get().saturating_sub(sampled_at.get()) > parameters.stale_after_micros {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::Stale);
        }
        if self
            .last_sequence_id
            .is_some_and(|previous| sequence_id <= previous)
            || self
                .last_sampled_at
                .is_some_and(|previous| sampled_at <= previous)
        {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::OutOfOrder);
        }
        if self.last_sampled_at.is_some_and(|previous| {
            sampled_at.get().saturating_sub(previous.get()) > parameters.discontinuity_after_micros
        }) {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::TimeDiscontinuity);
        }
        self.accept_sample(observed_at, sequence_id, sampled_at, value01, parameters)
    }

    fn accept_sample(
        &mut self,
        observed_at: BreathTimestampMicros,
        sequence_id: u64,
        sampled_at: BreathTimestampMicros,
        value01: f64,
        parameters: CommonPhaseParameters,
    ) -> CommonPhaseObservation {
        increment(&mut self.telemetry.accepted_sample_count);
        let (Some(previous_sampled_at), Some(previous_value01)) =
            (self.last_sampled_at, self.last_value01)
        else {
            self.prime(sequence_id, sampled_at, value01);
            return self.snapshot(observed_at, CommonPhaseStatus::Primed, None, None);
        };
        let elapsed_micros = sampled_at.get() - previous_sampled_at.get();
        let elapsed_as_f64 = f64::from(u32::try_from(elapsed_micros).unwrap_or(u32::MAX));
        let direction = if parameters.inverted { -1.0 } else { 1.0 };
        let raw_derivative =
            direction * (value01 - previous_value01) * 1_000_000.0 / elapsed_as_f64;
        let filtered_derivative =
            self.filtered_derivative_per_second
                .map_or(raw_derivative, |old| {
                    parameters.derivative_filter_alpha.mul_add(
                        raw_derivative,
                        (1.0 - parameters.derivative_filter_alpha) * old,
                    )
                });
        self.last_sequence_id = Some(sequence_id);
        self.last_sampled_at = Some(sampled_at);
        self.last_value01 = Some(value01);
        self.filtered_derivative_per_second = Some(filtered_derivative);

        let candidate = classify_candidate(self.phase, filtered_derivative, parameters);
        if candidate != self.candidate {
            self.candidate = candidate;
            self.candidate_started_at = Some(sampled_at);
            increment(&mut self.telemetry.candidate_change_count);
        } else if self.candidate_started_at.is_none() {
            self.candidate_started_at = Some(sampled_at);
        }
        let candidate_age = sampled_at
            .get()
            .saturating_sub(self.candidate_started_at.expect("candidate is timed").get());
        let phase_dwell = self
            .phase_started_at
            .map(|started| sampled_at.get().saturating_sub(started.get()));
        let confirmation = if candidate == CommonBreathPhase::Hold {
            parameters.hold_confirmation_micros
        } else {
            parameters.directional_confirmation_micros
        };
        let dwell_satisfied = self.phase == CommonBreathPhase::Unknown
            || phase_dwell.unwrap_or_default() >= parameters.minimum_phase_dwell_micros;
        let status = if candidate != self.phase && candidate_age >= confirmation && dwell_satisfied
        {
            self.phase = candidate;
            self.phase_started_at = Some(sampled_at);
            increment(&mut self.telemetry.phase_transition_count);
            if candidate == CommonBreathPhase::Hold {
                increment(&mut self.telemetry.hold_transition_count);
            }
            CommonPhaseStatus::Transitioned
        } else if candidate != self.phase {
            CommonPhaseStatus::Confirming
        } else {
            CommonPhaseStatus::Stable
        };
        self.snapshot(
            observed_at,
            status,
            Some(raw_derivative),
            Some(filtered_derivative),
        )
    }

    /// Clear all signal and classification history for an explicit boundary.
    #[must_use]
    pub fn reset_history(
        &mut self,
        at: BreathTimestampMicros,
        reason: CommonPhaseResetReason,
    ) -> CommonPhaseObservation {
        self.last_observed_at = Some(at);
        self.reset_recorded(at, reason)
    }

    /// Clear signal history and telemetry for a full owner reset.
    #[must_use]
    pub fn reset(&mut self, at: BreathTimestampMicros) -> CommonPhaseObservation {
        self.telemetry = CommonPhaseTelemetry::default();
        self.last_observed_at = Some(at);
        self.reset_internal(at, CommonPhaseResetReason::Explicit)
    }

    /// Return the current phase without changing state.
    #[must_use]
    pub const fn phase(&self) -> CommonBreathPhase {
        self.phase
    }

    /// Return current saturating telemetry.
    #[must_use]
    pub const fn telemetry(&self) -> CommonPhaseTelemetry {
        self.telemetry
    }

    /// Whether one accepted sample currently anchors derivative history.
    #[must_use]
    pub const fn is_primed(&self) -> bool {
        self.last_sampled_at.is_some()
    }

    fn prime(&mut self, sequence_id: u64, sampled_at: BreathTimestampMicros, value01: f64) {
        self.last_sequence_id = Some(sequence_id);
        self.last_sampled_at = Some(sampled_at);
        self.last_value01 = Some(value01);
    }

    fn reset_internal(
        &mut self,
        observed_at: BreathTimestampMicros,
        reason: CommonPhaseResetReason,
    ) -> CommonPhaseObservation {
        self.phase = CommonBreathPhase::Unknown;
        self.phase_started_at = None;
        self.candidate = CommonBreathPhase::Unknown;
        self.candidate_started_at = None;
        self.last_sequence_id = None;
        self.last_sampled_at = None;
        self.last_value01 = None;
        self.filtered_derivative_per_second = None;
        self.snapshot(observed_at, CommonPhaseStatus::Reset(reason), None, None)
    }

    fn reset_recorded(
        &mut self,
        observed_at: BreathTimestampMicros,
        reason: CommonPhaseResetReason,
    ) -> CommonPhaseObservation {
        match reason {
            CommonPhaseResetReason::Missing => increment(&mut self.telemetry.missing_reset_count),
            CommonPhaseResetReason::Stale => increment(&mut self.telemetry.stale_reset_count),
            CommonPhaseResetReason::Malformed => {
                increment(&mut self.telemetry.malformed_reset_count);
            }
            CommonPhaseResetReason::OutOfOrder => {
                increment(&mut self.telemetry.out_of_order_reset_count);
            }
            CommonPhaseResetReason::TimeRegression => {
                increment(&mut self.telemetry.time_regression_reset_count);
            }
            CommonPhaseResetReason::TimeDiscontinuity => {
                increment(&mut self.telemetry.discontinuity_reset_count);
            }
            CommonPhaseResetReason::Explicit
            | CommonPhaseResetReason::SourceChanged
            | CommonPhaseResetReason::CalibrationChanged
            | CommonPhaseResetReason::LifecycleChanged => {
                increment(&mut self.telemetry.explicit_reset_count);
            }
        }
        self.reset_internal(observed_at, reason)
    }

    fn snapshot(
        &self,
        observed_at: BreathTimestampMicros,
        status: CommonPhaseStatus,
        raw_derivative_per_second: Option<f64>,
        filtered_derivative_per_second: Option<f64>,
    ) -> CommonPhaseObservation {
        let sampled_at = self.last_sampled_at;
        CommonPhaseObservation {
            schema_id: COMMON_PHASE_OBSERVATION_SCHEMA_ID,
            status,
            phase: self.phase,
            candidate: self.candidate,
            sequence_id: self.last_sequence_id,
            sampled_at,
            observed_at,
            raw_derivative_per_second,
            filtered_derivative_per_second,
            candidate_age_micros: sampled_at
                .zip(self.candidate_started_at)
                .map(|(sampled, started)| sampled.get().saturating_sub(started.get())),
            phase_dwell_micros: sampled_at
                .zip(self.phase_started_at)
                .map(|(sampled, started)| sampled.get().saturating_sub(started.get())),
            telemetry: self.telemetry,
        }
    }
}

fn classify_candidate(
    phase: CommonBreathPhase,
    derivative: f64,
    parameters: CommonPhaseParameters,
) -> CommonBreathPhase {
    match phase {
        CommonBreathPhase::Inhale if derivative >= parameters.exit_derivative_per_second => {
            CommonBreathPhase::Inhale
        }
        CommonBreathPhase::Exhale if derivative <= -parameters.exit_derivative_per_second => {
            CommonBreathPhase::Exhale
        }
        _ if derivative >= parameters.enter_derivative_per_second => CommonBreathPhase::Inhale,
        _ if derivative <= -parameters.enter_derivative_per_second => CommonBreathPhase::Exhale,
        _ => CommonBreathPhase::Hold,
    }
}

fn increment(counter: &mut u64) {
    *counter = counter.saturating_add(1);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn configuration() -> CommonPhaseConfiguration {
        CommonPhaseConfiguration::new(CommonPhaseParameters {
            enter_derivative_per_second: 0.2,
            exit_derivative_per_second: 0.08,
            derivative_filter_alpha: 1.0,
            directional_confirmation_micros: 100_000,
            hold_confirmation_micros: 100_000,
            minimum_phase_dwell_micros: 100_000,
            stale_after_micros: 200_000,
            discontinuity_after_micros: 800_000,
            inverted: false,
        })
        .expect("valid phase configuration")
    }

    fn sample(sequence_id: u64, at: u64, value01: f64) -> CommonPhaseInput {
        CommonPhaseInput::Sample {
            sequence_id,
            sampled_at: BreathTimestampMicros::new(at),
            value01,
        }
    }

    #[test]
    fn configuration_rejects_unbounded_or_inverted_values() {
        let valid = CommonPhaseParameters::default();
        assert_eq!(
            CommonPhaseConfiguration::new(CommonPhaseParameters {
                enter_derivative_per_second: f64::NAN,
                ..valid
            }),
            Err(CommonPhaseConfigurationError::InvalidEnterDerivative)
        );
        assert_eq!(
            CommonPhaseConfiguration::new(CommonPhaseParameters {
                exit_derivative_per_second: valid.enter_derivative_per_second,
                ..valid
            }),
            Err(CommonPhaseConfigurationError::InvalidExitDerivative)
        );
        assert_eq!(
            CommonPhaseConfiguration::new(CommonPhaseParameters {
                derivative_filter_alpha: 0.0,
                ..valid
            }),
            Err(CommonPhaseConfigurationError::InvalidFilterAlpha)
        );
    }

    #[test]
    fn missing_stale_malformed_order_and_time_fail_closed() {
        let mut classifier = CommonPhaseClassifier::new(configuration());
        let at = BreathTimestampMicros::new(100_000);
        assert_eq!(
            classifier.observe(at, sample(1, 100_000, 0.5)).status,
            CommonPhaseStatus::Primed
        );
        assert_eq!(
            classifier
                .observe(
                    BreathTimestampMicros::new(110_000),
                    CommonPhaseInput::Missing
                )
                .status,
            CommonPhaseStatus::Reset(CommonPhaseResetReason::Missing)
        );
        assert_eq!(
            classifier
                .observe(BreathTimestampMicros::new(400_000), sample(2, 100_000, 0.5))
                .status,
            CommonPhaseStatus::Reset(CommonPhaseResetReason::Stale)
        );
        assert_eq!(
            classifier
                .observe(BreathTimestampMicros::new(410_000), sample(0, 410_000, 0.5))
                .status,
            CommonPhaseStatus::Reset(CommonPhaseResetReason::Malformed)
        );
        let _ = classifier.observe(BreathTimestampMicros::new(420_000), sample(3, 420_000, 0.5));
        assert_eq!(
            classifier
                .observe(BreathTimestampMicros::new(430_000), sample(3, 430_000, 0.6))
                .status,
            CommonPhaseStatus::Reset(CommonPhaseResetReason::OutOfOrder)
        );
        let _ = classifier.observe(BreathTimestampMicros::new(440_000), sample(4, 440_000, 0.5));
        assert_eq!(
            classifier
                .observe(
                    BreathTimestampMicros::new(1_300_001),
                    sample(5, 1_300_001, 0.6)
                )
                .status,
            CommonPhaseStatus::Reset(CommonPhaseResetReason::TimeDiscontinuity)
        );
        assert_eq!(
            classifier
                .observe(
                    BreathTimestampMicros::new(1_300_000),
                    sample(6, 1_300_000, 0.6)
                )
                .status,
            CommonPhaseStatus::Reset(CommonPhaseResetReason::TimeRegression)
        );
        assert_eq!(classifier.phase(), CommonBreathPhase::Unknown);
    }

    #[test]
    fn hysteresis_confirmation_and_dwell_produce_genuine_hold() {
        let mut classifier = CommonPhaseClassifier::new(configuration());
        let values = [0.50, 0.53, 0.56, 0.59, 0.59, 0.591, 0.591, 0.56, 0.53, 0.50];
        let phases: Vec<_> = values
            .into_iter()
            .enumerate()
            .map(|(index, value)| {
                let at = index as u64 * 100_000 + 1;
                classifier
                    .observe(
                        BreathTimestampMicros::new(at),
                        sample(index as u64 + 1, at, value),
                    )
                    .phase
            })
            .collect();
        assert!(phases.contains(&CommonBreathPhase::Inhale));
        assert!(phases.contains(&CommonBreathPhase::Hold));
        assert_eq!(phases.last(), Some(&CommonBreathPhase::Exhale));
        assert!(classifier.telemetry().hold_transition_count >= 1);
    }

    #[test]
    fn full_reset_clears_phase_filter_sequence_candidate_and_telemetry() {
        let mut classifier = CommonPhaseClassifier::new(configuration());
        let _ = classifier.observe(BreathTimestampMicros::new(100_000), sample(1, 100_000, 0.4));
        let _ = classifier.observe(BreathTimestampMicros::new(200_000), sample(2, 200_000, 0.5));
        let reset = classifier.reset(BreathTimestampMicros::new(210_000));
        assert_eq!(reset.phase, CommonBreathPhase::Unknown);
        assert_eq!(reset.candidate, CommonBreathPhase::Unknown);
        assert_eq!(reset.sequence_id, None);
        assert_eq!(reset.filtered_derivative_per_second, None);
        assert_eq!(reset.candidate_age_micros, None);
        assert_eq!(reset.phase_dwell_micros, None);
        assert_eq!(reset.telemetry, CommonPhaseTelemetry::default());
        assert!(!classifier.is_primed());
    }
}
