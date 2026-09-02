//! Polar-ACC-specific breath-phase classification.
//!
//! The generic common classifier remains unchanged for controller and other
//! sources.  This owner adds only the asymmetric thresholds, time-constant
//! smoothing, endpoint-exit hysteresis, and motion admission needed by the
//! Polar acceleration assessment lane.

use rusty_quest_breath_contract::{
    assessment::CommonBreathPhase,
    phase::{
        CommonPhaseObservation, CommonPhaseResetReason, CommonPhaseStatus, CommonPhaseTelemetry,
        COMMON_PHASE_OBSERVATION_SCHEMA_ID,
    },
    BreathTimestampMicros,
};

const MAX_DERIVATIVE_PER_SECOND: f64 = 1_000.0;
const MAX_INTERVAL_MICROS: u64 = 10_000_000;
const MAX_MOTION_ADMISSION_MG: f64 = 1_000.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarAccPhaseParameters {
    pub(crate) inhale_entry_per_second: f64,
    pub(crate) exhale_entry_per_second: f64,
    pub(crate) hold_band_per_second: f64,
    pub(crate) smoothing_tau_micros: u64,
    pub(crate) confirmation_micros: u64,
    pub(crate) minimum_dwell_micros: u64,
    pub(crate) stale_after_micros: u64,
    pub(crate) discontinuity_after_micros: u64,
    pub(crate) motion_admission_mg: f64,
    pub(crate) leave_full_contraction_per_second: f64,
    pub(crate) leave_full_expansion_per_second: f64,
    pub(crate) late_sample_window_micros: u64,
    pub(crate) inverted: bool,
}

impl Default for PolarAccPhaseParameters {
    fn default() -> Self {
        Self {
            inhale_entry_per_second: 0.10,
            exhale_entry_per_second: 0.10,
            hold_band_per_second: 0.035,
            smoothing_tau_micros: 20_000,
            confirmation_micros: 140_000,
            minimum_dwell_micros: 160_000,
            stale_after_micros: 250_000,
            discontinuity_after_micros: 1_500_000,
            motion_admission_mg: 0.0,
            leave_full_contraction_per_second: 0.10,
            leave_full_expansion_per_second: 0.10,
            late_sample_window_micros: 100_000,
            inverted: false,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PolarAccPhaseConfigurationError {
    InvalidInhaleEntry,
    InvalidExhaleEntry,
    InvalidHoldBand,
    InvalidSmoothing,
    InvalidConfirmation,
    InvalidDwell,
    InvalidStale,
    InvalidDiscontinuity,
    InvalidMotionAdmission,
    InvalidLeaveFullContraction,
    InvalidLeaveFullExpansion,
    InvalidLateSampleWindow,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarAccPhaseConfiguration(PolarAccPhaseParameters);

impl PolarAccPhaseConfiguration {
    pub(crate) fn new(
        parameters: PolarAccPhaseParameters,
    ) -> Result<Self, PolarAccPhaseConfigurationError> {
        if !valid_positive_derivative(parameters.inhale_entry_per_second) {
            return Err(PolarAccPhaseConfigurationError::InvalidInhaleEntry);
        }
        if !valid_positive_derivative(parameters.exhale_entry_per_second) {
            return Err(PolarAccPhaseConfigurationError::InvalidExhaleEntry);
        }
        if !parameters.hold_band_per_second.is_finite()
            || parameters.hold_band_per_second < 0.0
            || parameters.hold_band_per_second >= parameters.inhale_entry_per_second
            || parameters.hold_band_per_second >= parameters.exhale_entry_per_second
        {
            return Err(PolarAccPhaseConfigurationError::InvalidHoldBand);
        }
        if parameters.smoothing_tau_micros > MAX_INTERVAL_MICROS {
            return Err(PolarAccPhaseConfigurationError::InvalidSmoothing);
        }
        if parameters.confirmation_micros == 0
            || parameters.confirmation_micros > MAX_INTERVAL_MICROS
        {
            return Err(PolarAccPhaseConfigurationError::InvalidConfirmation);
        }
        if parameters.minimum_dwell_micros > MAX_INTERVAL_MICROS {
            return Err(PolarAccPhaseConfigurationError::InvalidDwell);
        }
        if parameters.stale_after_micros == 0
            || parameters.stale_after_micros > rusty_quest_breath_contract::MAX_STALE_AFTER_MICROS
        {
            return Err(PolarAccPhaseConfigurationError::InvalidStale);
        }
        if parameters.discontinuity_after_micros <= parameters.stale_after_micros
            || parameters.discontinuity_after_micros
                > rusty_quest_breath_contract::MAX_DISCONTINUITY_AFTER_MICROS
        {
            return Err(PolarAccPhaseConfigurationError::InvalidDiscontinuity);
        }
        if !parameters.motion_admission_mg.is_finite()
            || !(0.0..=MAX_MOTION_ADMISSION_MG).contains(&parameters.motion_admission_mg)
        {
            return Err(PolarAccPhaseConfigurationError::InvalidMotionAdmission);
        }
        if !valid_positive_derivative(parameters.leave_full_contraction_per_second) {
            return Err(PolarAccPhaseConfigurationError::InvalidLeaveFullContraction);
        }
        if !valid_positive_derivative(parameters.leave_full_expansion_per_second) {
            return Err(PolarAccPhaseConfigurationError::InvalidLeaveFullExpansion);
        }
        if parameters.late_sample_window_micros > MAX_INTERVAL_MICROS {
            return Err(PolarAccPhaseConfigurationError::InvalidLateSampleWindow);
        }
        Ok(Self(parameters))
    }

    pub(crate) const fn parameters(self) -> PolarAccPhaseParameters {
        self.0
    }
}

fn valid_positive_derivative(value: f64) -> bool {
    value.is_finite() && value > 0.0 && value <= MAX_DERIVATIVE_PER_SECOND
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum EndpointLatch {
    #[default]
    None,
    FullContraction,
    FullExpansion,
}

#[derive(Clone, Debug)]
pub(crate) struct PolarAccPhaseClassifier {
    configuration: PolarAccPhaseConfiguration,
    phase: CommonBreathPhase,
    phase_started_at: Option<BreathTimestampMicros>,
    candidate: CommonBreathPhase,
    candidate_started_at: Option<BreathTimestampMicros>,
    last_sequence_id: Option<u64>,
    last_sampled_at: Option<BreathTimestampMicros>,
    last_value01: Option<f64>,
    filtered_derivative_per_second: Option<f64>,
    last_observed_at: Option<BreathTimestampMicros>,
    endpoint_latch: EndpointLatch,
    telemetry: CommonPhaseTelemetry,
}

impl PolarAccPhaseClassifier {
    pub(crate) const fn new(configuration: PolarAccPhaseConfiguration) -> Self {
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
            endpoint_latch: EndpointLatch::None,
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

    pub(crate) const fn configuration(&self) -> PolarAccPhaseConfiguration {
        self.configuration
    }

    pub(crate) fn observe_sample(
        &mut self,
        observed_at: BreathTimestampMicros,
        sequence_id: u64,
        sampled_at: BreathTimestampMicros,
        value01: f64,
        motion_delta_mg: f64,
    ) -> CommonPhaseObservation {
        increment(&mut self.telemetry.received_input_count);
        if self.last_observed_at.is_some_and(|old| observed_at < old) {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::TimeRegression);
        }
        self.last_observed_at = Some(observed_at);
        if sequence_id == 0
            || sampled_at > observed_at
            || !value01.is_finite()
            || !(0.0..=1.0).contains(&value01)
            || !motion_delta_mg.is_finite()
            || motion_delta_mg < 0.0
        {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::Malformed);
        }
        let parameters = self.configuration.parameters();
        if observed_at.get().saturating_sub(sampled_at.get()) > parameters.stale_after_micros {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::Stale);
        }
        if self.last_sequence_id.is_some_and(|old| sequence_id <= old)
            || self.last_sampled_at.is_some_and(|old| sampled_at <= old)
        {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::OutOfOrder);
        }
        if self.last_sampled_at.is_some_and(|old| {
            sampled_at.get().saturating_sub(old.get()) > parameters.discontinuity_after_micros
        }) {
            return self.reset_recorded(observed_at, CommonPhaseResetReason::TimeDiscontinuity);
        }
        self.accept_sample(
            observed_at,
            sequence_id,
            sampled_at,
            value01,
            motion_delta_mg,
            parameters,
        )
    }

    fn accept_sample(
        &mut self,
        observed_at: BreathTimestampMicros,
        sequence_id: u64,
        sampled_at: BreathTimestampMicros,
        value01: f64,
        motion_delta_mg: f64,
        parameters: PolarAccPhaseParameters,
    ) -> CommonPhaseObservation {
        increment(&mut self.telemetry.accepted_sample_count);
        let (Some(previous_sampled_at), Some(previous_value01)) =
            (self.last_sampled_at, self.last_value01)
        else {
            self.prime(sequence_id, sampled_at, value01);
            return self.snapshot(observed_at, CommonPhaseStatus::Primed, None, None);
        };
        let elapsed_micros = sampled_at.get() - previous_sampled_at.get();
        let dt_seconds = elapsed_micros as f64 / 1_000_000.0;
        let direction = if parameters.inverted { -1.0 } else { 1.0 };
        let admitted = motion_delta_mg >= parameters.motion_admission_mg;
        let raw_derivative = if admitted {
            direction * (value01 - previous_value01) / dt_seconds
        } else {
            0.0
        };
        let alpha = if parameters.smoothing_tau_micros == 0 {
            1.0
        } else {
            elapsed_micros as f64
                / (parameters
                    .smoothing_tau_micros
                    .saturating_add(elapsed_micros) as f64)
        };
        let filtered = self
            .filtered_derivative_per_second
            .map_or(raw_derivative, |old| old + alpha * (raw_derivative - old));
        self.last_sequence_id = Some(sequence_id);
        self.last_sampled_at = Some(sampled_at);
        self.last_value01 = Some(value01);
        self.filtered_derivative_per_second = Some(filtered);

        if value01 <= f64::EPSILON {
            self.endpoint_latch = EndpointLatch::FullContraction;
        } else if value01 >= 1.0 - f64::EPSILON {
            self.endpoint_latch = EndpointLatch::FullExpansion;
        }
        let candidate = self.classify_candidate(filtered, parameters);
        if candidate != self.candidate {
            self.candidate = candidate;
            self.candidate_started_at = Some(sampled_at);
            increment(&mut self.telemetry.candidate_change_count);
        } else if self.candidate_started_at.is_none() {
            self.candidate_started_at = Some(sampled_at);
        }
        let candidate_age = sampled_at.get().saturating_sub(
            self.candidate_started_at
                .expect("candidate timestamp")
                .get(),
        );
        let phase_dwell = self
            .phase_started_at
            .map(|started| sampled_at.get().saturating_sub(started.get()));
        let dwell_satisfied = self.phase == CommonBreathPhase::Unknown
            || phase_dwell.unwrap_or_default() >= parameters.minimum_dwell_micros;
        let status = if candidate != self.phase
            && candidate_age >= parameters.confirmation_micros
            && dwell_satisfied
        {
            self.phase = candidate;
            self.phase_started_at = Some(sampled_at);
            if matches!(
                (self.endpoint_latch, candidate),
                (EndpointLatch::FullContraction, CommonBreathPhase::Inhale)
                    | (EndpointLatch::FullExpansion, CommonBreathPhase::Exhale)
            ) {
                self.endpoint_latch = EndpointLatch::None;
            }
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
        self.snapshot(observed_at, status, Some(raw_derivative), Some(filtered))
    }

    fn classify_candidate(
        &self,
        derivative: f64,
        parameters: PolarAccPhaseParameters,
    ) -> CommonBreathPhase {
        let inhale_entry = if self.endpoint_latch == EndpointLatch::FullContraction {
            parameters.leave_full_contraction_per_second
        } else {
            parameters.inhale_entry_per_second
        };
        let exhale_entry = if self.endpoint_latch == EndpointLatch::FullExpansion {
            parameters.leave_full_expansion_per_second
        } else {
            parameters.exhale_entry_per_second
        };
        if derivative >= inhale_entry {
            CommonBreathPhase::Inhale
        } else if derivative <= -exhale_entry {
            CommonBreathPhase::Exhale
        } else if derivative.abs() <= parameters.hold_band_per_second {
            CommonBreathPhase::Hold
        } else {
            self.phase
        }
    }

    pub(crate) fn reset_history(
        &mut self,
        at: BreathTimestampMicros,
        reason: CommonPhaseResetReason,
    ) -> CommonPhaseObservation {
        self.last_observed_at = Some(at);
        self.reset_recorded(at, reason)
    }

    pub(crate) fn reset(&mut self, at: BreathTimestampMicros) -> CommonPhaseObservation {
        self.telemetry = CommonPhaseTelemetry::default();
        self.last_observed_at = Some(at);
        self.reset_internal(at, CommonPhaseResetReason::Explicit)
    }

    pub(crate) const fn telemetry(&self) -> CommonPhaseTelemetry {
        self.telemetry
    }

    fn prime(&mut self, sequence_id: u64, sampled_at: BreathTimestampMicros, value01: f64) {
        self.last_sequence_id = Some(sequence_id);
        self.last_sampled_at = Some(sampled_at);
        self.last_value01 = Some(value01);
        self.endpoint_latch = if value01 <= f64::EPSILON {
            EndpointLatch::FullContraction
        } else if value01 >= 1.0 - f64::EPSILON {
            EndpointLatch::FullExpansion
        } else {
            EndpointLatch::None
        };
    }

    fn reset_recorded(
        &mut self,
        at: BreathTimestampMicros,
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
        self.reset_internal(at, reason)
    }

    fn reset_internal(
        &mut self,
        at: BreathTimestampMicros,
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
        self.endpoint_latch = EndpointLatch::None;
        self.snapshot(at, CommonPhaseStatus::Reset(reason), None, None)
    }

    fn snapshot(
        &self,
        observed_at: BreathTimestampMicros,
        status: CommonPhaseStatus,
        raw_derivative_per_second: Option<f64>,
        filtered_derivative_per_second: Option<f64>,
    ) -> CommonPhaseObservation {
        CommonPhaseObservation {
            schema_id: COMMON_PHASE_OBSERVATION_SCHEMA_ID,
            status,
            phase: self.phase,
            candidate: self.candidate,
            sequence_id: self.last_sequence_id,
            sampled_at: self.last_sampled_at,
            observed_at,
            raw_derivative_per_second,
            filtered_derivative_per_second,
            candidate_age_micros: self
                .last_sampled_at
                .zip(self.candidate_started_at)
                .map(|(sampled, started)| sampled.get().saturating_sub(started.get())),
            phase_dwell_micros: self
                .last_sampled_at
                .zip(self.phase_started_at)
                .map(|(sampled, started)| sampled.get().saturating_sub(started.get())),
            telemetry: self.telemetry,
        }
    }
}

fn increment(counter: &mut u64) {
    *counter = counter.saturating_add(1);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(value: u64) -> BreathTimestampMicros {
        BreathTimestampMicros::new(value)
    }

    fn tuned() -> PolarAccPhaseConfiguration {
        PolarAccPhaseConfiguration::new(PolarAccPhaseParameters {
            inhale_entry_per_second: 0.030,
            exhale_entry_per_second: 0.030,
            hold_band_per_second: 0.025,
            smoothing_tau_micros: 400_000,
            confirmation_micros: 400_000,
            minimum_dwell_micros: 400_000,
            stale_after_micros: 500_000,
            discontinuity_after_micros: 2_000_000,
            motion_admission_mg: 2.0,
            leave_full_contraction_per_second: 0.040,
            leave_full_expansion_per_second: 0.040,
            late_sample_window_micros: 120_000,
            inverted: true,
        })
        .expect("valid tuned parameters")
    }

    #[test]
    fn validation_rejects_nonfinite_and_inverted_hysteresis() {
        for parameters in [
            PolarAccPhaseParameters {
                inhale_entry_per_second: f64::NAN,
                ..tuned().parameters()
            },
            PolarAccPhaseParameters {
                hold_band_per_second: 0.031,
                ..tuned().parameters()
            },
            PolarAccPhaseParameters {
                motion_admission_mg: -1.0,
                ..tuned().parameters()
            },
        ] {
            assert!(PolarAccPhaseConfiguration::new(parameters).is_err());
        }
    }

    #[test]
    fn motion_admission_and_asymmetric_thresholds_produce_real_hold() {
        let mut classifier = PolarAccPhaseClassifier::new(tuned());
        classifier.observe_sample(at(1_000_000), 1, at(1_000_000), 0.5, 3.0);
        let quiet = classifier.observe_sample(at(1_100_000), 2, at(1_100_000), 0.51, 1.0);
        assert_eq!(quiet.filtered_derivative_per_second, Some(0.0));
        let mut last = quiet;
        for index in 0..8_u64 {
            let time = 1_200_000 + index * 100_000;
            last = classifier.observe_sample(
                at(time),
                3 + index,
                at(time),
                0.51 - index as f64 * 0.02,
                3.0,
            );
        }
        assert_eq!(last.phase, CommonBreathPhase::Inhale);
    }

    #[test]
    fn endpoint_exit_thresholds_apply_only_to_the_latched_endpoint() {
        let mut parameters = tuned().parameters();
        parameters.inverted = false;
        parameters.smoothing_tau_micros = 0;
        parameters.confirmation_micros = 1;
        parameters.minimum_dwell_micros = 0;
        parameters.inhale_entry_per_second = 0.02;
        parameters.hold_band_per_second = 0.01;
        parameters.leave_full_contraction_per_second = 0.20;
        let mut classifier = PolarAccPhaseClassifier::new(
            PolarAccPhaseConfiguration::new(parameters).expect("valid endpoint configuration"),
        );
        classifier.observe_sample(at(1_000_000), 1, at(1_000_000), 0.0, 3.0);
        let blocked = classifier.observe_sample(at(1_100_000), 2, at(1_100_000), 0.01, 3.0);
        assert_ne!(blocked.candidate, CommonBreathPhase::Inhale);
        let accepted = classifier.observe_sample(at(1_200_000), 3, at(1_200_000), 0.05, 3.0);
        assert_eq!(accepted.candidate, CommonBreathPhase::Inhale);

        classifier.reset(at(2_000_000));
        classifier.observe_sample(at(2_100_000), 4, at(2_100_000), 0.5, 3.0);
        let ordinary = classifier.observe_sample(at(2_200_000), 5, at(2_200_000), 0.51, 3.0);
        assert_eq!(ordinary.candidate, CommonBreathPhase::Inhale);
    }

    #[test]
    fn session_reset_clears_endpoint_and_derivative_history() {
        let mut classifier = PolarAccPhaseClassifier::new(tuned());
        classifier.observe_sample(at(1_000_000), 1, at(1_000_000), 0.0, 3.0);
        let reset =
            classifier.reset_history(at(2_000_000), CommonPhaseResetReason::LifecycleChanged);
        assert_eq!(reset.phase, CommonBreathPhase::Unknown);
        let primed = classifier.observe_sample(at(2_100_000), 1, at(2_100_000), 0.5, 3.0);
        assert_eq!(primed.status, CommonPhaseStatus::Primed);
    }
}
