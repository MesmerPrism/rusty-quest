//! Narrow Polar-ACC assessment adapter over the pure breath contract.
//!
//! The existing Android PMD/JNI path remains the acquisition owner. This
//! module translates its typed, monotonic, milligravity measurements into the
//! source-neutral calibration contract. Application mappings and robust phase
//! classification remain outside this boundary.

use rusty_quest_breath_contract::{
    assessment::{
        BreathAssessmentError, BreathAssessmentFields, BreathAssessmentObservation,
        BreathTrackingState, CommonBreathPhase,
    },
    calibration::{
        AcceptedFrameCalibration, CalibrationConfiguration, CalibrationInput,
        CalibrationInputError, CalibrationLifecycle, CalibrationMotionFrame,
        CalibrationObservation, CalibrationParameters, CalibrationProjectionSpace,
        CalibrationRejection,
    },
    BreathGeneration, BreathTimestampMicros,
};

use crate::polar_composition_adapters::PolarAccMeasurement;

const NANOSECONDS_PER_MICROSECOND: u64 = 1_000;
const STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED: f64 = 9.806_65;

/// Coordinate subset used for Polar calibration and live projection.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum PolarAccProjection {
    /// Ignore the second coordinate while fitting and projecting.
    #[default]
    Xz,
    /// Fit and project all three coordinates.
    Full3d,
}

impl PolarAccProjection {
    pub(crate) const fn marker_value(self) -> &'static str {
        match self {
            Self::Xz => "xz",
            Self::Full3d => "full-3d",
        }
    }

    const fn calibration_space(self) -> CalibrationProjectionSpace {
        match self {
            Self::Xz => CalibrationProjectionSpace::Xz,
            Self::Full3d => CalibrationProjectionSpace::Full3d,
        }
    }
}

/// Physical unit carried by a typed acceleration frame.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PolarAccelerationUnit {
    /// One unit is one thousandth of standard gravity.
    Milligravity,
    /// One unit is standard gravity.
    StandardGravity,
    /// SI acceleration in meters per second squared.
    MetersPerSecondSquared,
}

impl PolarAccelerationUnit {
    const fn marker_value(self) -> &'static str {
        match self {
            Self::Milligravity => "mg",
            Self::StandardGravity => "g",
            Self::MetersPerSecondSquared => "mps2",
        }
    }
}

/// One typed, timed acceleration frame before unit normalization.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct TimedPolarAccFrame {
    pub(crate) sequence_id: u64,
    pub(crate) host_monotonic_time_ns: u64,
    pub(crate) sensor_monotonic_time_ns: u64,
    pub(crate) acceleration: [f64; 3],
    pub(crate) unit: PolarAccelerationUnit,
}

impl TimedPolarAccFrame {
    /// Translate the existing PMD/JNI ingress shape without changing its owner.
    pub(crate) fn from_pmd_measurement(measurement: PolarAccMeasurement) -> Self {
        Self {
            sequence_id: measurement.sequence_id,
            host_monotonic_time_ns: measurement.host_time_ns,
            sensor_monotonic_time_ns: measurement.sensor_time_ns,
            acceleration: measurement.xyz_mg.map(f64::from),
            unit: PolarAccelerationUnit::Milligravity,
        }
    }

    fn normalize(self) -> Result<NormalizedPolarAccFrame, PolarAccTranslationError> {
        if self.sequence_id == 0 {
            return Err(PolarAccTranslationError::ZeroSequence);
        }
        if self.host_monotonic_time_ns == 0 {
            return Err(PolarAccTranslationError::ZeroHostTimestamp);
        }
        if self.sensor_monotonic_time_ns == 0 {
            return Err(PolarAccTranslationError::ZeroSensorTimestamp);
        }
        let mut acceleration_g = [0.0; 3];
        for (index, value) in self.acceleration.into_iter().enumerate() {
            if !value.is_finite() {
                return Err(PolarAccTranslationError::NonFiniteAcceleration { index });
            }
            acceleration_g[index] = match self.unit {
                PolarAccelerationUnit::Milligravity => value / 1_000.0,
                PolarAccelerationUnit::StandardGravity => value,
                PolarAccelerationUnit::MetersPerSecondSquared => {
                    value / STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
                }
            };
            if !acceleration_g[index].is_finite() {
                return Err(PolarAccTranslationError::NonFiniteAcceleration { index });
            }
        }
        Ok(NormalizedPolarAccFrame {
            sequence_id: self.sequence_id,
            sampled_at: BreathTimestampMicros::new(
                self.host_monotonic_time_ns / NANOSECONDS_PER_MICROSECOND,
            ),
            sensor_monotonic_time_ns: self.sensor_monotonic_time_ns,
            acceleration_g,
            input_unit: self.unit,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct NormalizedPolarAccFrame {
    sequence_id: u64,
    sampled_at: BreathTimestampMicros,
    sensor_monotonic_time_ns: u64,
    acceleration_g: [f64; 3],
    input_unit: PolarAccelerationUnit,
}

/// Explicit Polar acceleration availability at one injected action time.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum PolarAccInput {
    Missing { sequence_id: u64 },
    Frame(TimedPolarAccFrame),
}

/// Validated Polar volume configuration.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct PolarAccVolumeSettings {
    enabled: bool,
    projection: PolarAccProjection,
    calibration: CalibrationConfiguration,
}

impl PolarAccVolumeSettings {
    pub(crate) fn new(
        enabled: bool,
        projection: PolarAccProjection,
        mut calibration_parameters: CalibrationParameters,
    ) -> Result<Self, PolarAccVolumeSettingsError> {
        calibration_parameters.projection_space = projection.calibration_space();
        let calibration = CalibrationConfiguration::new(calibration_parameters)
            .map_err(PolarAccVolumeSettingsError::InvalidCalibration)?;
        Ok(Self {
            enabled,
            projection,
            calibration,
        })
    }
}

/// Typed configuration rejection at the Polar adapter boundary.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum PolarAccVolumeSettingsError {
    InvalidCalibration(rusty_quest_breath_contract::calibration::CalibrationConfigurationError),
}

/// Typed rejection while translating PMD/JNI ingress into the pure contract.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PolarAccTranslationError {
    ZeroSequence,
    ZeroHostTimestamp,
    ZeroSensorTimestamp,
    NonFiniteAcceleration { index: usize },
    SensorTimestampOutOfOrder { submitted: u64, previous: u64 },
}

/// Adapter-level rejection that leaves calibrated volume inert.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum PolarAccAssessmentRejection {
    Disabled,
    GenerationMismatch,
    Translation(PolarAccTranslationError),
    Calibration(CalibrationRejection),
    CalibrationFailure(rusty_quest_breath_contract::calibration::CalibrationFailure),
    Assessment(BreathAssessmentError),
}

/// Saturating adapter telemetry; calibration telemetry remains in its owner.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct PolarAccAdapterTelemetry {
    pub(crate) received_input_count: u64,
    pub(crate) normalized_frame_count: u64,
    pub(crate) missing_input_count: u64,
    pub(crate) rejected_input_count: u64,
    pub(crate) milligravity_frame_count: u64,
    pub(crate) standard_gravity_frame_count: u64,
    pub(crate) meters_per_second_squared_frame_count: u64,
}

/// One calibrated Polar assessment result.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PolarAccAssessmentResult {
    pub(crate) assessment: Option<BreathAssessmentObservation>,
    pub(crate) calibration: CalibrationObservation,
    pub(crate) rejection: Option<PolarAccAssessmentRejection>,
    pub(crate) telemetry: PolarAccAdapterTelemetry,
}

/// Host-testable Polar acceleration assessment owner.
#[derive(Debug)]
pub(crate) struct PolarAccBreathAdapter {
    settings: PolarAccVolumeSettings,
    calibration: AcceptedFrameCalibration,
    generation: Option<BreathGeneration>,
    next_runtime_generation: u64,
    last_sensor_monotonic_time_ns: Option<u64>,
    telemetry: PolarAccAdapterTelemetry,
}

impl PolarAccBreathAdapter {
    pub(crate) fn new(settings: PolarAccVolumeSettings) -> Self {
        Self {
            settings,
            calibration: AcceptedFrameCalibration::new(),
            generation: None,
            next_runtime_generation: 1,
            last_sensor_monotonic_time_ns: None,
            telemetry: PolarAccAdapterTelemetry::default(),
        }
    }

    pub(crate) fn configure(&mut self, at: BreathTimestampMicros) -> CalibrationObservation {
        self.generation = None;
        self.last_sensor_monotonic_time_ns = None;
        if !self.settings.enabled {
            return self.calibration.snapshot();
        }
        self.calibration
            .configure(at, Ok(self.settings.calibration))
    }

    pub(crate) fn start(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
    ) -> CalibrationObservation {
        if !self.settings.enabled {
            return self.calibration.snapshot();
        }
        let observation = self.calibration.start(at, generation);
        if observation.generation == Some(generation)
            && matches!(
                observation.lifecycle,
                CalibrationLifecycle::Collecting | CalibrationLifecycle::Ready
            )
        {
            self.generation = Some(generation);
            self.next_runtime_generation = self
                .next_runtime_generation
                .max(generation.get().saturating_add(1));
            self.last_sensor_monotonic_time_ns = None;
        }
        observation
    }

    pub(crate) fn ensure_running(&mut self, at: BreathTimestampMicros) -> Option<BreathGeneration> {
        if !self.settings.enabled {
            return None;
        }
        let lifecycle = self.calibration.snapshot().lifecycle;
        if matches!(
            lifecycle,
            CalibrationLifecycle::Collecting | CalibrationLifecycle::Ready
        ) {
            return self.generation;
        }
        if lifecycle == CalibrationLifecycle::Disabled {
            let configured = self.configure(at);
            if configured.lifecycle != CalibrationLifecycle::Configured {
                return None;
            }
        }
        let generation = BreathGeneration::new(self.next_runtime_generation).ok()?;
        let started = self.start(at, generation);
        (started.generation == Some(generation)).then_some(generation)
    }

    pub(crate) fn cancel(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
    ) -> CalibrationObservation {
        let observation = self.calibration.cancel(at, generation);
        if observation.lifecycle == CalibrationLifecycle::Cancelled {
            self.generation = None;
            self.last_sensor_monotonic_time_ns = None;
        }
        observation
    }

    pub(crate) fn reset(&mut self, at: BreathTimestampMicros) -> CalibrationObservation {
        self.generation = None;
        self.last_sensor_monotonic_time_ns = None;
        self.telemetry = PolarAccAdapterTelemetry::default();
        self.calibration.reset(at)
    }

    pub(crate) fn observe(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
        input: PolarAccInput,
    ) -> PolarAccAssessmentResult {
        if self.generation != Some(generation) {
            return PolarAccAssessmentResult {
                assessment: None,
                calibration: self.calibration.snapshot(),
                rejection: Some(if self.generation.is_none() {
                    PolarAccAssessmentRejection::Disabled
                } else {
                    PolarAccAssessmentRejection::GenerationMismatch
                }),
                telemetry: self.telemetry,
            };
        }
        increment(&mut self.telemetry.received_input_count);
        match input {
            PolarAccInput::Missing { sequence_id } => {
                increment(&mut self.telemetry.missing_input_count);
                let calibration =
                    self.calibration
                        .observe(at, generation, CalibrationInput::Missing);
                let (tracking, rejection) = if calibration.failure.is_some() {
                    classify_calibration(&calibration)
                } else {
                    (BreathTrackingState::Missing, None)
                };
                self.finish(at, at, sequence_id, tracking, rejection, calibration)
            }
            PolarAccInput::Frame(frame) => self.observe_frame(at, generation, frame),
        }
    }

    fn observe_frame(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
        frame: TimedPolarAccFrame,
    ) -> PolarAccAssessmentResult {
        let sequence_id = frame.sequence_id;
        let fallback_sampled_at =
            BreathTimestampMicros::new(frame.host_monotonic_time_ns / NANOSECONDS_PER_MICROSECOND)
                .min(at);
        let normalized = match frame.normalize() {
            Ok(normalized) => normalized,
            Err(error) => {
                return self.reject_translation(
                    at,
                    fallback_sampled_at,
                    sequence_id,
                    generation,
                    error,
                );
            }
        };
        if let Some(previous) = self.last_sensor_monotonic_time_ns {
            if normalized.sensor_monotonic_time_ns <= previous {
                return self.reject_translation(
                    at,
                    normalized.sampled_at.min(at),
                    normalized.sequence_id,
                    generation,
                    PolarAccTranslationError::SensorTimestampOutOfOrder {
                        submitted: normalized.sensor_monotonic_time_ns,
                        previous,
                    },
                );
            }
        }
        increment(&mut self.telemetry.normalized_frame_count);
        match normalized.input_unit {
            PolarAccelerationUnit::Milligravity => {
                increment(&mut self.telemetry.milligravity_frame_count);
            }
            PolarAccelerationUnit::StandardGravity => {
                increment(&mut self.telemetry.standard_gravity_frame_count);
            }
            PolarAccelerationUnit::MetersPerSecondSquared => {
                increment(&mut self.telemetry.meters_per_second_squared_frame_count);
            }
        }
        let calibration = self.calibration.observe(
            at,
            generation,
            CalibrationInput::Frame(CalibrationMotionFrame {
                sequence_id: normalized.sequence_id,
                sampled_at: normalized.sampled_at,
                vector: normalized.acceleration_g,
            }),
        );
        if calibration_consumed_source_order(&calibration) {
            self.last_sensor_monotonic_time_ns = Some(normalized.sensor_monotonic_time_ns);
        }
        let (tracking, rejection) = classify_calibration(&calibration);
        self.finish(
            at,
            normalized.sampled_at.min(at),
            normalized.sequence_id,
            tracking,
            rejection,
            calibration,
        )
    }

    fn reject_translation(
        &mut self,
        at: BreathTimestampMicros,
        sampled_at: BreathTimestampMicros,
        sequence_id: u64,
        generation: BreathGeneration,
        error: PolarAccTranslationError,
    ) -> PolarAccAssessmentResult {
        let calibration = self
            .calibration
            .observe(at, generation, CalibrationInput::Missing);
        let tracking = if matches!(
            error,
            PolarAccTranslationError::SensorTimestampOutOfOrder { .. }
        ) {
            BreathTrackingState::OutOfOrder
        } else {
            BreathTrackingState::Malformed
        };
        self.finish(
            at,
            sampled_at,
            sequence_id,
            tracking,
            Some(PolarAccAssessmentRejection::Translation(error)),
            calibration,
        )
    }

    fn finish(
        &mut self,
        observed_at: BreathTimestampMicros,
        sampled_at: BreathTimestampMicros,
        sequence_id: u64,
        tracking: BreathTrackingState,
        rejection: Option<PolarAccAssessmentRejection>,
        calibration: CalibrationObservation,
    ) -> PolarAccAssessmentResult {
        let generation = self.generation.expect("active adapter retains generation");
        let volume01 = if tracking == BreathTrackingState::Valid {
            calibration.live.map(|live| live.volume01)
        } else {
            None
        };
        let quality01 = calibration
            .model
            .map_or(0.0, |model| model.axis_dominance01.clamp(0.0, 1.0));
        let phase = if matches!(
            tracking,
            BreathTrackingState::Malformed
                | BreathTrackingState::OutOfOrder
                | BreathTrackingState::RejectedMotion
        ) {
            CommonBreathPhase::BadTracking
        } else {
            CommonBreathPhase::Unknown
        };
        let assessment = BreathAssessmentObservation::new(BreathAssessmentFields {
            generation,
            sequence_id,
            sampled_at,
            observed_at,
            volume01,
            phase,
            calibration: calibration.lifecycle,
            tracking,
            quality01,
        });
        let (assessment, rejection) = match assessment {
            Ok(assessment) => (Some(assessment), rejection),
            Err(error) => (None, Some(PolarAccAssessmentRejection::Assessment(error))),
        };
        if rejection.is_some() {
            increment(&mut self.telemetry.rejected_input_count);
        }
        PolarAccAssessmentResult {
            assessment,
            calibration,
            rejection,
            telemetry: self.telemetry,
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "polarAccAssessmentEnabled={} polarAccAssessmentProjection={} polarAccAssessmentGeneration={} polarAccAssessmentReceivedInputs={} polarAccAssessmentNormalizedFrames={} polarAccAssessmentRejectedInputs={} polarAccAssessmentInputUnit=typed",
            self.settings.enabled,
            self.settings.projection.marker_value(),
            self.generation
                .map(|generation| generation.get().to_string())
                .unwrap_or_else(|| "none".to_owned()),
            self.telemetry.received_input_count,
            self.telemetry.normalized_frame_count,
            self.telemetry.rejected_input_count,
        )
    }
}

fn calibration_consumed_source_order(observation: &CalibrationObservation) -> bool {
    if observation.failure.is_some() {
        return false;
    }
    !matches!(
        observation.rejection,
        Some(CalibrationRejection::Input(
            CalibrationInputError::NonFiniteComponent { .. }
                | CalibrationInputError::ComponentOutOfBounds { .. }
                | CalibrationInputError::FutureTimestamp
                | CalibrationInputError::OutOfOrderTimestamp { .. }
                | CalibrationInputError::OutOfOrderSequence { .. }
        )) | Some(
            CalibrationRejection::InvalidConfiguration(_)
                | CalibrationRejection::InvalidLifecycle { .. }
                | CalibrationRejection::GenerationMismatch { .. }
                | CalibrationRejection::GenerationNotFresh { .. }
                | CalibrationRejection::TimeRegression { .. }
        )
    )
}

fn classify_calibration(
    observation: &CalibrationObservation,
) -> (BreathTrackingState, Option<PolarAccAssessmentRejection>) {
    if let Some(failure) = observation.failure {
        return (
            BreathTrackingState::RejectedMotion,
            Some(PolarAccAssessmentRejection::CalibrationFailure(failure)),
        );
    }
    let Some(rejection) = observation.rejection else {
        return (
            if observation.lifecycle == CalibrationLifecycle::Ready {
                BreathTrackingState::Valid
            } else {
                BreathTrackingState::Calibrating
            },
            None,
        );
    };
    let tracking = match rejection {
        CalibrationRejection::Input(CalibrationInputError::Stale { .. }) => {
            BreathTrackingState::Stale
        }
        CalibrationRejection::Input(
            CalibrationInputError::OutOfOrderTimestamp { .. }
            | CalibrationInputError::OutOfOrderSequence { .. },
        ) => BreathTrackingState::OutOfOrder,
        CalibrationRejection::Input(
            CalibrationInputError::NonFiniteComponent { .. }
            | CalibrationInputError::ComponentOutOfBounds { .. }
            | CalibrationInputError::FutureTimestamp,
        ) => BreathTrackingState::Malformed,
        CalibrationRejection::Input(
            CalibrationInputError::NotUsefulSignal { .. }
            | CalibrationInputError::StepOutOfBounds { .. },
        ) => BreathTrackingState::RejectedMotion,
        _ => BreathTrackingState::Malformed,
    };
    (
        tracking,
        Some(PolarAccAssessmentRejection::Calibration(rejection)),
    )
}

fn increment(counter: &mut u64) {
    *counter = counter.saturating_add(1);
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_quest_breath_contract::calibration::{CalibrationFailure, CalibrationWatchdogCause};

    fn timestamp(value: u64) -> BreathTimestampMicros {
        BreathTimestampMicros::new(value)
    }

    fn generation(value: u64) -> BreathGeneration {
        BreathGeneration::new(value).expect("non-zero generation")
    }

    fn test_parameters() -> CalibrationParameters {
        CalibrationParameters {
            accepted_frame_target: 8,
            analysis_interval_micros: 100_000,
            watchdog_micros: 1_500_000,
            stale_after_micros: 100_000,
            useful_signal_min_norm: 0.0,
            motion_deadband: 0.001,
            maximum_component_abs: 16.0,
            maximum_step: 4.0,
            minimum_span: 0.02,
            minimum_axis_dominance: 0.01,
            live_ema_alpha: 1.0,
            adaptive_expand_step: 0.02,
            adaptive_contract_step: 0.0,
            adaptive_maximum_expansion: 0.5,
            ..CalibrationParameters::default()
        }
    }

    fn adapter(projection: PolarAccProjection) -> PolarAccBreathAdapter {
        PolarAccBreathAdapter::new(
            PolarAccVolumeSettings::new(true, projection, test_parameters())
                .expect("valid settings"),
        )
    }

    fn start(adapter: &mut PolarAccBreathAdapter, at: u64, generation_id: u64) {
        assert_eq!(
            adapter.configure(timestamp(at)).lifecycle,
            CalibrationLifecycle::Configured
        );
        assert_eq!(
            adapter
                .start(timestamp(at), generation(generation_id))
                .lifecycle,
            CalibrationLifecycle::Collecting
        );
    }

    fn frame(
        sequence_id: u64,
        time_micros: u64,
        sensor_time_ns: u64,
        acceleration: [f64; 3],
        unit: PolarAccelerationUnit,
    ) -> PolarAccInput {
        PolarAccInput::Frame(TimedPolarAccFrame {
            sequence_id,
            host_monotonic_time_ns: time_micros.saturating_mul(NANOSECONDS_PER_MICROSECOND),
            sensor_monotonic_time_ns: sensor_time_ns,
            acceleration,
            unit,
        })
    }

    fn calibrate_line(
        adapter: &mut PolarAccBreathAdapter,
        generation_id: u64,
        start_micros: u64,
        axis: [f64; 3],
        bias_g: [f64; 3],
    ) -> PolarAccAssessmentResult {
        let mut result = None;
        for index in 0..8_u64 {
            let time = start_micros + index * 100_000;
            let displacement = index as f64 * 0.03;
            let acceleration =
                std::array::from_fn(|component| bias_g[component] + axis[component] * displacement);
            result = Some(adapter.observe(
                timestamp(time),
                generation(generation_id),
                frame(
                    index + 1,
                    time,
                    10_000 + index * 100_000_000,
                    acceleration,
                    PolarAccelerationUnit::StandardGravity,
                ),
            ));
        }
        result.expect("calibration result")
    }

    #[test]
    fn pmd_ingress_translation_normalizes_timestamp_and_unit() {
        let translated = TimedPolarAccFrame::from_pmd_measurement(PolarAccMeasurement {
            sequence_id: 7,
            host_time_ns: 123_456_789,
            sensor_time_ns: 987_654_321,
            xyz_mg: [1_000.0, -500.0, 250.0],
        })
        .normalize()
        .expect("typed PMD frame");
        assert_eq!(translated.sequence_id, 7);
        assert_eq!(translated.sampled_at, timestamp(123_456));
        assert_eq!(translated.sensor_monotonic_time_ns, 987_654_321);
        assert_eq!(translated.acceleration_g, [1.0, -0.5, 0.25]);

        let meters = TimedPolarAccFrame {
            sequence_id: 1,
            host_monotonic_time_ns: 1_000,
            sensor_monotonic_time_ns: 1,
            acceleration: [STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED, 0.0, 0.0],
            unit: PolarAccelerationUnit::MetersPerSecondSquared,
        }
        .normalize()
        .expect("SI frame");
        assert!((meters.acceleration_g[0] - 1.0).abs() < 1.0e-12);

        let gravity = TimedPolarAccFrame {
            sequence_id: 1,
            host_monotonic_time_ns: 1_000,
            sensor_monotonic_time_ns: 1,
            acceleration: [1.0, -0.5, 0.25],
            unit: PolarAccelerationUnit::StandardGravity,
        }
        .normalize()
        .expect("standard-gravity frame");
        assert_eq!(gravity.acceleration_g, translated.acceleration_g);
    }

    #[test]
    fn xz_is_default_and_full_3d_is_an_explicit_option() {
        assert_eq!(PolarAccProjection::default(), PolarAccProjection::Xz);
        let xz = PolarAccVolumeSettings::new(true, PolarAccProjection::Xz, test_parameters())
            .expect("XZ settings");
        let full = PolarAccVolumeSettings::new(true, PolarAccProjection::Full3d, test_parameters())
            .expect("3D settings");
        assert_eq!(
            xz.calibration.parameters().projection_space,
            CalibrationProjectionSpace::Xz
        );
        assert_eq!(
            full.calibration.parameters().projection_space,
            CalibrationProjectionSpace::Full3d
        );
    }

    #[test]
    fn xz_calibration_handles_gravity_bias_and_rotated_breath_motion() {
        let diagonal = [
            std::f64::consts::FRAC_1_SQRT_2,
            0.0,
            std::f64::consts::FRAC_1_SQRT_2,
        ];
        let mut adapter = adapter(PolarAccProjection::Xz);
        start(&mut adapter, 1_000_000, 1);
        let ready = calibrate_line(&mut adapter, 1, 1_000_000, diagonal, [0.18, 0.98, -0.12]);
        assert_eq!(ready.calibration.lifecycle, CalibrationLifecycle::Ready);
        let model = ready.calibration.model.expect("fitted model");
        let alignment = model.axis[0] * diagonal[0] + model.axis[2] * diagonal[2];
        assert!(alignment.abs() > 0.99);
        let observation = ready.assessment.expect("assessment");
        assert_eq!(observation.tracking, BreathTrackingState::Valid);
        assert_eq!(observation.phase, CommonBreathPhase::Unknown);
        assert!(observation
            .volume01
            .is_some_and(|value| (0.0..=1.0).contains(&value)));
        assert!(observation.quality01 > 0.9);
    }

    #[test]
    fn full_3d_calibration_accepts_a_rotated_axis_with_realistic_gravity() {
        let inv = 1.0 / 3.0_f64.sqrt();
        let diagonal = [inv, inv, inv];
        let mut adapter = adapter(PolarAccProjection::Full3d);
        start(&mut adapter, 1_000_000, 1);
        let ready = calibrate_line(&mut adapter, 1, 1_000_000, diagonal, [0.07, 0.99, -0.04]);
        let model = ready.calibration.model.expect("3D model");
        let alignment = model
            .axis
            .into_iter()
            .zip(diagonal)
            .map(|(left, right)| left * right)
            .sum::<f64>();
        assert!(alignment.abs() > 0.99);
        assert_eq!(
            ready.assessment.expect("assessment").tracking,
            BreathTrackingState::Valid
        );
    }

    #[test]
    fn ready_volume_is_bounded_and_responds_before_the_next_analysis_tick() {
        let mut adapter = adapter(PolarAccProjection::Xz);
        start(&mut adapter, 1_000_000, 1);
        let ready = calibrate_line(&mut adapter, 1, 1_000_000, [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]);
        let ready_volume = ready
            .assessment
            .and_then(|value| value.volume01)
            .expect("ready volume");
        let mut fast = None;
        for offset in 1..=3_u64 {
            let at = 1_700_000 + offset * 20_000;
            fast = Some(adapter.observe(
                timestamp(at),
                generation(1),
                frame(
                    8 + offset,
                    at,
                    800_000_000 + offset * 20_000_000,
                    [-0.20, 1.0, 0.0],
                    PolarAccelerationUnit::StandardGravity,
                ),
            ));
        }
        let fast = fast.expect("rapid live result");
        let live = fast.calibration.live.expect("live calibration");
        let volume = fast
            .assessment
            .expect("live assessment")
            .volume01
            .expect("volume");
        assert!(!live.analysis_admitted);
        assert!(volume < ready_volume);
        assert!((0.0..=1.0).contains(&volume));
    }

    #[test]
    fn adaptive_limits_remain_bounded_through_the_adapter() {
        let mut adapter = adapter(PolarAccProjection::Xz);
        start(&mut adapter, 1_000_000, 1);
        let ready = calibrate_line(&mut adapter, 1, 1_000_000, [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]);
        let initial = ready.calibration.model.expect("initial model");
        let mut updated = None;
        for index in 0..8_u64 {
            let at = 1_800_000 + index * 100_000;
            updated = Some(adapter.observe(
                timestamp(at),
                generation(1),
                frame(
                    9 + index,
                    at,
                    900_000_000 + index * 100_000_000,
                    [0.40, 1.0, 0.0],
                    PolarAccelerationUnit::StandardGravity,
                ),
            ));
        }
        let updated = updated.expect("adaptive update");
        let model = updated.calibration.model.expect("updated model");
        assert!(model.upper >= initial.upper);
        assert!(model.upper <= initial.initial_upper + 0.5 + 1.0e-12);
        assert!(updated
            .assessment
            .expect("bounded assessment")
            .volume01
            .is_some_and(|value| (0.0..=1.0).contains(&value)));
    }

    #[test]
    fn disabled_missing_stale_malformed_and_out_of_order_are_fail_closed() {
        let disabled_settings =
            PolarAccVolumeSettings::new(false, PolarAccProjection::Xz, test_parameters())
                .expect("disabled settings");
        let mut disabled = PolarAccBreathAdapter::new(disabled_settings);
        assert_eq!(disabled.ensure_running(timestamp(1_000_000)), None);
        let disabled_result = disabled.observe(
            timestamp(1_000_000),
            generation(1),
            frame(
                1,
                1_000_000,
                1,
                [0.0, 1.0, 0.0],
                PolarAccelerationUnit::StandardGravity,
            ),
        );
        assert_eq!(
            disabled_result.rejection,
            Some(PolarAccAssessmentRejection::Disabled)
        );
        assert_eq!(
            disabled_result.telemetry,
            PolarAccAdapterTelemetry::default()
        );

        let mut missing = adapter(PolarAccProjection::Xz);
        start(&mut missing, 1_000_000, 1);
        let missing_result = missing.observe(
            timestamp(1_000_001),
            generation(1),
            PolarAccInput::Missing { sequence_id: 1 },
        );
        assert_eq!(
            missing_result
                .assessment
                .expect("missing assessment")
                .tracking,
            BreathTrackingState::Missing
        );

        for error_frame in [
            TimedPolarAccFrame {
                sequence_id: 1,
                host_monotonic_time_ns: 0,
                sensor_monotonic_time_ns: 1,
                acceleration: [0.0, 1.0, 0.0],
                unit: PolarAccelerationUnit::StandardGravity,
            },
            TimedPolarAccFrame {
                sequence_id: 1,
                host_monotonic_time_ns: 1_000_000_000,
                sensor_monotonic_time_ns: 1,
                acceleration: [f64::NAN, 1.0, 0.0],
                unit: PolarAccelerationUnit::StandardGravity,
            },
            TimedPolarAccFrame {
                sequence_id: 1,
                host_monotonic_time_ns: 1_000_000_000,
                sensor_monotonic_time_ns: 1,
                acceleration: [17.0, 1.0, 0.0],
                unit: PolarAccelerationUnit::StandardGravity,
            },
        ] {
            let mut malformed = adapter(PolarAccProjection::Xz);
            start(&mut malformed, 1_000_000, 1);
            let result = malformed.observe(
                timestamp(1_000_000),
                generation(1),
                PolarAccInput::Frame(error_frame),
            );
            assert_eq!(
                result.assessment.expect("malformed assessment").tracking,
                BreathTrackingState::Malformed
            );
            assert!(result.rejection.is_some());
        }

        let mut stale = adapter(PolarAccProjection::Xz);
        start(&mut stale, 1_000_000, 1);
        let stale_result = stale.observe(
            timestamp(1_200_001),
            generation(1),
            frame(
                1,
                1_100_000,
                1,
                [0.0, 1.0, 0.0],
                PolarAccelerationUnit::StandardGravity,
            ),
        );
        assert_eq!(
            stale_result.assessment.expect("stale assessment").tracking,
            BreathTrackingState::Stale
        );

        let mut ordered = adapter(PolarAccProjection::Xz);
        start(&mut ordered, 1_000_000, 1);
        ordered.observe(
            timestamp(1_000_000),
            generation(1),
            frame(
                1,
                1_000_000,
                20,
                [0.0, 1.0, 0.0],
                PolarAccelerationUnit::StandardGravity,
            ),
        );
        let out_of_order = ordered.observe(
            timestamp(1_100_000),
            generation(1),
            frame(
                2,
                1_100_000,
                19,
                [0.02, 1.0, 0.0],
                PolarAccelerationUnit::StandardGravity,
            ),
        );
        let out_of_order_assessment = out_of_order.assessment.expect("ordering assessment");
        assert_eq!(
            out_of_order_assessment.tracking,
            BreathTrackingState::OutOfOrder
        );
        assert_eq!(out_of_order_assessment.volume01, None);
        assert!(matches!(
            out_of_order.rejection,
            Some(PolarAccAssessmentRejection::Translation(
                PolarAccTranslationError::SensorTimestampOutOfOrder { .. }
            ))
        ));
    }

    #[test]
    fn calibration_failure_can_retry_with_a_fresh_generation() {
        let mut adapter = adapter(PolarAccProjection::Xz);
        start(&mut adapter, 1_000_000, 1);
        adapter.observe(
            timestamp(1_000_000),
            generation(1),
            frame(
                1,
                1_000_000,
                1,
                [0.1, 1.0, 0.0],
                PolarAccelerationUnit::StandardGravity,
            ),
        );
        let failed = adapter.observe(
            timestamp(2_500_001),
            generation(1),
            PolarAccInput::Missing { sequence_id: 2 },
        );
        assert_eq!(
            failed.calibration.failure,
            Some(CalibrationFailure::Watchdog {
                cause: CalibrationWatchdogCause::InsufficientAcceptedFrames,
                accepted_frames: 1,
                target_frames: 8,
                elapsed_micros: 1_500_001,
            })
        );
        assert_eq!(
            adapter.start(timestamp(2_500_002), generation(2)).lifecycle,
            CalibrationLifecycle::Collecting
        );
        let ready = calibrate_line(&mut adapter, 2, 2_600_000, [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]);
        assert_eq!(ready.calibration.lifecycle, CalibrationLifecycle::Ready);
        assert_eq!(
            ready.assessment.expect("retry assessment").generation,
            generation(2)
        );
    }

    #[test]
    fn deterministic_action_replay_is_value_equal_in_memory() {
        fn run() -> Vec<PolarAccAssessmentResult> {
            let mut adapter = adapter(PolarAccProjection::Xz);
            start(&mut adapter, 1_000_000, 1);
            (0..8_u64)
                .map(|index| {
                    let at = 1_000_000 + index * 100_000;
                    adapter.observe(
                        timestamp(at),
                        generation(1),
                        frame(
                            index + 1,
                            at,
                            10_000 + index * 100_000_000,
                            [index as f64 * 0.03, 1.0, 0.0],
                            PolarAccelerationUnit::StandardGravity,
                        ),
                    )
                })
                .collect()
        }
        assert_eq!(run(), run());
    }

    #[test]
    fn reset_and_generation_switch_clear_timestamp_and_filter_history() {
        let mut adapter = adapter(PolarAccProjection::Xz);
        start(&mut adapter, 1_000_000, 1);
        adapter.observe(
            timestamp(1_000_000),
            generation(1),
            frame(
                1,
                1_000_000,
                100,
                [0.0, 1.0, 0.0],
                PolarAccelerationUnit::StandardGravity,
            ),
        );
        assert_eq!(
            adapter
                .cancel(timestamp(1_000_001), generation(1))
                .lifecycle,
            CalibrationLifecycle::Cancelled
        );
        assert_eq!(
            adapter.start(timestamp(1_000_002), generation(2)).lifecycle,
            CalibrationLifecycle::Collecting
        );
        let fresh_sensor_epoch = adapter.observe(
            timestamp(1_000_003),
            generation(2),
            frame(
                2,
                1_000_003,
                1,
                [0.02, 1.0, 0.0],
                PolarAccelerationUnit::StandardGravity,
            ),
        );
        assert_eq!(
            fresh_sensor_epoch
                .assessment
                .expect("fresh generation")
                .tracking,
            BreathTrackingState::Calibrating
        );
        assert_eq!(
            adapter.reset(timestamp(1_000_004)).lifecycle,
            CalibrationLifecycle::Disabled
        );
        assert_eq!(adapter.telemetry, PolarAccAdapterTelemetry::default());
    }

    #[test]
    fn marker_is_neutral_and_reports_typed_projection_state() {
        let mut adapter = adapter(PolarAccProjection::Xz);
        assert_eq!(
            adapter.ensure_running(timestamp(1_000_000)),
            Some(generation(1))
        );
        let marker = adapter.marker_fields();
        assert!(marker.contains("polarAccAssessmentProjection=xz"));
        assert!(marker.contains("polarAccAssessmentInputUnit=typed"));
        assert_eq!(PolarAccelerationUnit::Milligravity.marker_value(), "mg");
    }
}
