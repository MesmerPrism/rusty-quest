//! Narrow OpenXR-pose assessment adapter over the pure breath contract.
//!
//! OpenXR acquisition supplies only timestamps, pose values, and tracking
//! flags. This module owns the public controller assessment policy and keeps
//! rendering, application mappings, and downstream interpretation outside the
//! boundary.

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

use crate::native_controller_breath_state::{
    dot3, normalize_quat_or_identity, normalized_axis, quat_angle_degrees, rotate_vec3_by_quat,
    NativeControllerBreathPoseSample, NativeControllerBreathSample, NativeControllerBreathSettings,
    NativeControllerBreathStateEstimator,
};

/// Volume projection selected before calibration begins.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum ControllerVolumeProjection {
    /// Project translation onto the configured controller-local axis after
    /// rotating that axis by each admitted pose orientation.
    #[default]
    FixedOrientation,
    /// Fit the principal motion axis from admitted three-dimensional poses.
    DynamicMotionAxis,
}

impl ControllerVolumeProjection {
    pub(crate) const fn marker_value(self) -> &'static str {
        match self {
            Self::FixedOrientation => "fixed-orientation",
            Self::DynamicMotionAxis => "dynamic-motion-axis",
        }
    }
}

/// Validated controller-volume configuration.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ControllerVolumeSettings {
    projection: ControllerVolumeProjection,
    local_axis: [f64; 3],
    rotation_guard_degrees: f64,
    calibration: CalibrationConfiguration,
}

impl ControllerVolumeSettings {
    pub(crate) fn new(
        projection: ControllerVolumeProjection,
        local_axis: [f64; 3],
        rotation_guard_degrees: f64,
        mut calibration_parameters: CalibrationParameters,
    ) -> Result<Self, ControllerVolumeSettingsError> {
        let local_axis =
            normalized_axis(local_axis).ok_or(ControllerVolumeSettingsError::InvalidLocalAxis)?;
        if !rotation_guard_degrees.is_finite() || rotation_guard_degrees <= 0.0 {
            return Err(ControllerVolumeSettingsError::InvalidRotationGuard);
        }
        calibration_parameters.projection_space = match projection {
            ControllerVolumeProjection::FixedOrientation => CalibrationProjectionSpace::Full3d,
            ControllerVolumeProjection::DynamicMotionAxis => CalibrationProjectionSpace::Full3d,
        };
        let calibration = CalibrationConfiguration::new(calibration_parameters)
            .map_err(ControllerVolumeSettingsError::InvalidCalibration)?;
        Ok(Self {
            projection,
            local_axis,
            rotation_guard_degrees,
            calibration,
        })
    }
}

/// Typed configuration rejection at the platform adapter boundary.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum ControllerVolumeSettingsError {
    InvalidLocalAxis,
    InvalidRotationGuard,
    InvalidCalibration(rusty_quest_breath_contract::calibration::CalibrationConfigurationError),
}

/// One controller pose translated from an OpenXR action-space observation.
#[derive(Clone, Copy, Debug)]
pub(crate) struct OpenXrControllerPoseFrame {
    pub(crate) sequence_id: u64,
    pub(crate) sampled_at: BreathTimestampMicros,
    pub(crate) position_m: [f64; 3],
    pub(crate) orientation_xyzw: [f64; 4],
    pub(crate) active: bool,
    pub(crate) tracked: bool,
}

impl OpenXrControllerPoseFrame {
    pub(crate) fn from_native(
        sequence_id: u64,
        sampled_at: BreathTimestampMicros,
        sample: NativeControllerBreathPoseSample,
    ) -> Self {
        Self {
            sequence_id,
            sampled_at,
            position_m: sample.position_m.map(f64::from),
            orientation_xyzw: sample.orientation_xyzw.map(f64::from),
            active: sample.active,
            tracked: sample.tracked,
        }
    }

    fn native_sample(self) -> NativeControllerBreathPoseSample {
        NativeControllerBreathPoseSample {
            sample_time_s: self.sampled_at.get() as f64 / 1_000_000.0,
            position_m: self.position_m.map(|value| value as f32),
            orientation_xyzw: self.orientation_xyzw.map(|value| value as f32),
            active: self.active,
            tracked: self.tracked,
        }
    }
}

/// Explicit pose availability at one injected action time.
#[derive(Clone, Copy, Debug)]
pub(crate) enum OpenXrControllerPoseInput {
    Missing { sequence_id: u64 },
    Frame(OpenXrControllerPoseFrame),
}

/// Adapter-level rejection that leaves volume output inert.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum ControllerAssessmentRejection {
    Disabled,
    GenerationMismatch,
    MalformedPose,
    RotationGuard,
    MotionGuard,
    Calibration(CalibrationRejection),
    CalibrationFailure(rusty_quest_breath_contract::calibration::CalibrationFailure),
    Assessment(BreathAssessmentError),
}

/// One concurrent controller assessment result.
#[derive(Clone, Debug)]
pub(crate) struct ControllerAssessmentResult {
    pub(crate) assessment: Option<BreathAssessmentObservation>,
    pub(crate) phase_sample: Option<NativeControllerBreathSample>,
    pub(crate) calibration: CalibrationObservation,
    pub(crate) rejection: Option<ControllerAssessmentRejection>,
}

#[derive(Clone, Copy, Debug)]
struct AssessmentDisposition {
    at: BreathTimestampMicros,
    sampled_at: BreathTimestampMicros,
    sequence_id: u64,
    phase_sample: NativeControllerBreathSample,
    tracking: BreathTrackingState,
    rejection: Option<ControllerAssessmentRejection>,
}

/// Host-testable controller assessment owner.
#[derive(Debug)]
pub(crate) struct OpenXrControllerBreathAdapter {
    volume_settings: ControllerVolumeSettings,
    phase_estimator: NativeControllerBreathStateEstimator,
    calibration: AcceptedFrameCalibration,
    generation: Option<BreathGeneration>,
    next_runtime_generation: u64,
    last_orientation: Option<[f64; 4]>,
    emitted_samples: u64,
    rejected_samples: u64,
}

impl OpenXrControllerBreathAdapter {
    pub(crate) fn new(
        phase_settings: NativeControllerBreathSettings,
        volume_settings: ControllerVolumeSettings,
    ) -> Self {
        Self {
            volume_settings,
            phase_estimator: NativeControllerBreathStateEstimator::new(phase_settings),
            calibration: AcceptedFrameCalibration::new(),
            generation: None,
            next_runtime_generation: 1,
            last_orientation: None,
            emitted_samples: 0,
            rejected_samples: 0,
        }
    }

    pub(crate) fn configure(&mut self, at: BreathTimestampMicros) -> CalibrationObservation {
        self.phase_estimator.reset_history();
        self.last_orientation = None;
        self.generation = None;
        self.calibration
            .configure(at, Ok(self.volume_settings.calibration))
    }

    pub(crate) fn start(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
    ) -> CalibrationObservation {
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
            self.phase_estimator.reset_history();
            self.last_orientation = None;
        }
        observation
    }

    pub(crate) fn ensure_running(&mut self, at: BreathTimestampMicros) -> Option<BreathGeneration> {
        if self.generation.is_some() {
            return self.generation;
        }
        if self.calibration.snapshot().lifecycle == CalibrationLifecycle::Disabled {
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
            self.phase_estimator.reset_history();
            self.last_orientation = None;
        }
        observation
    }

    pub(crate) fn reset(&mut self, at: BreathTimestampMicros) -> CalibrationObservation {
        self.generation = None;
        self.phase_estimator.reset_history();
        self.last_orientation = None;
        self.emitted_samples = 0;
        self.rejected_samples = 0;
        self.calibration.reset(at)
    }

    pub(crate) fn observe(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
        input: OpenXrControllerPoseInput,
    ) -> ControllerAssessmentResult {
        if self.generation != Some(generation) {
            self.rejected_samples = self.rejected_samples.saturating_add(1);
            return ControllerAssessmentResult {
                assessment: None,
                phase_sample: None,
                calibration: self.calibration.snapshot(),
                rejection: Some(if self.generation.is_none() {
                    ControllerAssessmentRejection::Disabled
                } else {
                    ControllerAssessmentRejection::GenerationMismatch
                }),
            };
        }

        match input {
            OpenXrControllerPoseInput::Missing { sequence_id } => {
                self.phase_estimator.reset_history();
                self.last_orientation = None;
                let phase_sample = self.phase_estimator.push_breath_sample(None);
                let calibration =
                    self.calibration
                        .observe(at, generation, CalibrationInput::Missing);
                let (tracking, rejection) = if calibration.failure.is_some() {
                    classify_calibration(&calibration)
                } else {
                    (BreathTrackingState::Missing, None)
                };
                self.finish(
                    AssessmentDisposition {
                        at,
                        sampled_at: at,
                        sequence_id,
                        phase_sample,
                        tracking,
                        rejection,
                    },
                    calibration,
                )
            }
            OpenXrControllerPoseInput::Frame(frame) => self.observe_frame(at, generation, frame),
        }
    }

    fn observe_frame(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
        frame: OpenXrControllerPoseFrame,
    ) -> ControllerAssessmentResult {
        if !frame.active || !frame.tracked {
            self.phase_estimator.reset_history();
            self.last_orientation = None;
            let phase_sample = self.phase_estimator.push_breath_sample(None);
            let calibration = self
                .calibration
                .observe(at, generation, CalibrationInput::Missing);
            let (tracking, rejection) = if calibration.failure.is_some() {
                classify_calibration(&calibration)
            } else {
                (BreathTrackingState::Missing, None)
            };
            return self.finish(
                AssessmentDisposition {
                    at,
                    sampled_at: frame.sampled_at.min(at),
                    sequence_id: frame.sequence_id,
                    phase_sample,
                    tracking,
                    rejection,
                },
                calibration,
            );
        }
        if frame.sampled_at > at
            || !frame.position_m.iter().all(|value| value.is_finite())
            || !valid_quaternion(frame.orientation_xyzw)
        {
            return self.reject_pose(
                at,
                frame.sampled_at.min(at),
                frame.sequence_id,
                generation,
                BreathTrackingState::Malformed,
                ControllerAssessmentRejection::MalformedPose,
            );
        }

        let orientation = normalize_quat_or_identity(frame.orientation_xyzw);
        if self.last_orientation.is_some_and(|previous| {
            quat_angle_degrees(previous, orientation) > self.volume_settings.rotation_guard_degrees
        }) {
            self.last_orientation = None;
            return self.reject_pose(
                at,
                frame.sampled_at,
                frame.sequence_id,
                generation,
                BreathTrackingState::RejectedRotation,
                ControllerAssessmentRejection::RotationGuard,
            );
        }

        let mut phase_sample = self
            .phase_estimator
            .push_breath_sample(Some(frame.native_sample()));
        if phase_sample.phase == CommonBreathPhase::BadTracking {
            self.last_orientation = None;
            return self.reject_pose_with_phase(
                generation,
                AssessmentDisposition {
                    at,
                    sampled_at: frame.sampled_at,
                    sequence_id: frame.sequence_id,
                    phase_sample,
                    tracking: BreathTrackingState::RejectedMotion,
                    rejection: Some(ControllerAssessmentRejection::MotionGuard),
                },
            );
        }
        self.last_orientation = Some(orientation);

        let vector = match self.volume_settings.projection {
            ControllerVolumeProjection::FixedOrientation => {
                let axis = rotate_vec3_by_quat(self.volume_settings.local_axis, orientation);
                [dot3(frame.position_m, axis), 0.0, 0.0]
            }
            ControllerVolumeProjection::DynamicMotionAxis => frame.position_m,
        };
        let calibration = self.calibration.observe(
            at,
            generation,
            CalibrationInput::Frame(CalibrationMotionFrame {
                sequence_id: frame.sequence_id,
                sampled_at: frame.sampled_at,
                vector,
            }),
        );
        let (tracking, rejection) = classify_calibration(&calibration);
        if rejection.is_some() {
            self.phase_estimator.reset_history();
            self.last_orientation = None;
            phase_sample.phase = CommonBreathPhase::BadTracking;
        }
        self.finish(
            AssessmentDisposition {
                at,
                sampled_at: frame.sampled_at,
                sequence_id: frame.sequence_id,
                phase_sample,
                tracking,
                rejection,
            },
            calibration,
        )
    }

    fn reject_pose(
        &mut self,
        at: BreathTimestampMicros,
        sampled_at: BreathTimestampMicros,
        sequence_id: u64,
        generation: BreathGeneration,
        tracking: BreathTrackingState,
        rejection: ControllerAssessmentRejection,
    ) -> ControllerAssessmentResult {
        let phase_sample = self.phase_estimator.push_breath_sample(None);
        self.reject_pose_with_phase(
            generation,
            AssessmentDisposition {
                at,
                sampled_at,
                sequence_id,
                phase_sample,
                tracking,
                rejection: Some(rejection),
            },
        )
    }

    fn reject_pose_with_phase(
        &mut self,
        generation: BreathGeneration,
        disposition: AssessmentDisposition,
    ) -> ControllerAssessmentResult {
        let calibration =
            self.calibration
                .observe(disposition.at, generation, CalibrationInput::Missing);
        self.finish(disposition, calibration)
    }

    fn finish(
        &mut self,
        disposition: AssessmentDisposition,
        calibration: CalibrationObservation,
    ) -> ControllerAssessmentResult {
        let generation = self.generation.expect("active adapter retains generation");
        let quality01 = calibration
            .model
            .map_or(0.0, |model| model.axis_dominance01.clamp(0.0, 1.0));
        let volume01 = if disposition.tracking == BreathTrackingState::Valid {
            calibration.live.map(|live| live.volume01)
        } else {
            None
        };
        let assessment = BreathAssessmentObservation::new(BreathAssessmentFields {
            generation,
            sequence_id: disposition.sequence_id,
            sampled_at: disposition.sampled_at,
            observed_at: disposition.at,
            volume01,
            phase: disposition.phase_sample.phase,
            calibration: calibration.lifecycle,
            tracking: disposition.tracking,
            quality01,
        });
        let (assessment, rejection) = match assessment {
            Ok(assessment) => (Some(assessment), disposition.rejection),
            Err(error) => (None, Some(ControllerAssessmentRejection::Assessment(error))),
        };
        self.emitted_samples = self.emitted_samples.saturating_add(1);
        if rejection.is_some() {
            self.rejected_samples = self.rejected_samples.saturating_add(1);
        }
        ControllerAssessmentResult {
            assessment,
            phase_sample: Some(disposition.phase_sample),
            calibration,
            rejection,
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "nativeControllerAssessmentProjection={} nativeControllerAssessmentGeneration={} nativeControllerAssessmentEmittedSamples={} nativeControllerAssessmentRejectedSamples={}",
            self.volume_settings.projection.marker_value(),
            self.generation
                .map(|generation| generation.get().to_string())
                .unwrap_or_else(|| "none".to_owned()),
            self.emitted_samples,
            self.rejected_samples,
        )
    }
}

fn valid_quaternion(value: [f64; 4]) -> bool {
    value.iter().all(|value| value.is_finite())
        && value.iter().map(|value| value * value).sum::<f64>() > 1.0e-12
}

fn classify_calibration(
    observation: &CalibrationObservation,
) -> (BreathTrackingState, Option<ControllerAssessmentRejection>) {
    if let Some(failure) = observation.failure {
        return (
            BreathTrackingState::RejectedMotion,
            Some(ControllerAssessmentRejection::CalibrationFailure(failure)),
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
        Some(ControllerAssessmentRejection::Calibration(rejection)),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_quest_breath_contract::calibration::{CalibrationFailure, CalibrationWatchdogCause};

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
            motion_deadband: 0.0001,
            maximum_component_abs: 8.0,
            maximum_step: 2.0,
            minimum_span: 0.02,
            minimum_axis_dominance: 0.01,
            live_ema_alpha: 1.0,
            adaptive_expand_step: 0.02,
            adaptive_contract_step: 0.0,
            adaptive_maximum_expansion: 0.5,
            ..CalibrationParameters::default()
        }
    }

    fn phase_settings() -> NativeControllerBreathSettings {
        NativeControllerBreathSettings::new(
            [1.0, 0.0, 0.0],
            0.0002,
            -0.0002,
            30.0,
            1.0,
            2,
            4,
            0.2,
            0.4,
        )
    }

    fn adapter(projection: ControllerVolumeProjection) -> OpenXrControllerBreathAdapter {
        let volume =
            ControllerVolumeSettings::new(projection, [1.0, 0.0, 0.0], 20.0, test_parameters())
                .expect("test settings");
        OpenXrControllerBreathAdapter::new(phase_settings(), volume)
    }

    fn frame(
        sequence_id: u64,
        time_micros: u64,
        position_m: [f64; 3],
    ) -> OpenXrControllerPoseInput {
        OpenXrControllerPoseInput::Frame(OpenXrControllerPoseFrame {
            sequence_id,
            sampled_at: BreathTimestampMicros::new(time_micros),
            position_m,
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
            active: true,
            tracked: true,
        })
    }

    fn start(adapter: &mut OpenXrControllerBreathAdapter, at: u64, generation_id: u64) {
        let at = BreathTimestampMicros::new(at);
        assert_eq!(
            adapter.configure(at).lifecycle,
            CalibrationLifecycle::Configured
        );
        assert_eq!(
            adapter.start(at, generation(generation_id)).lifecycle,
            CalibrationLifecycle::Collecting
        );
    }

    #[test]
    fn adapter_configuration_rejects_invalid_axis_and_rotation_bounds() {
        assert!(matches!(
            ControllerVolumeSettings::new(
                ControllerVolumeProjection::FixedOrientation,
                [0.0; 3],
                20.0,
                test_parameters(),
            ),
            Err(ControllerVolumeSettingsError::InvalidLocalAxis)
        ));
        assert!(matches!(
            ControllerVolumeSettings::new(
                ControllerVolumeProjection::FixedOrientation,
                [1.0, 0.0, 0.0],
                0.0,
                test_parameters(),
            ),
            Err(ControllerVolumeSettingsError::InvalidRotationGuard)
        ));
    }

    fn calibrate_line(
        adapter: &mut OpenXrControllerBreathAdapter,
        generation_id: u64,
        axis: [f64; 3],
    ) -> ControllerAssessmentResult {
        let mut result = None;
        for index in 0..8_u64 {
            let value = index as f64 * 0.02;
            let position = axis.map(|component| component * value);
            result = Some(adapter.observe(
                BreathTimestampMicros::new(index * 100_000),
                generation(generation_id),
                frame(index + 1, index * 100_000, position),
            ));
        }
        result.expect("calibration result")
    }

    #[test]
    fn fixed_orientation_rotates_the_local_axis() {
        let mut adapter = adapter(ControllerVolumeProjection::FixedOrientation);
        start(&mut adapter, 0, 1);
        let half = std::f64::consts::FRAC_PI_4.sin();
        let rotation_z_90 = [0.0, 0.0, half, half];
        let mut last = None;
        for index in 0..8_u64 {
            let at = index * 100_000;
            last = Some(adapter.observe(
                BreathTimestampMicros::new(at),
                generation(1),
                OpenXrControllerPoseInput::Frame(OpenXrControllerPoseFrame {
                    sequence_id: index + 1,
                    sampled_at: BreathTimestampMicros::new(at),
                    position_m: [0.0, index as f64 * 0.02, 0.0],
                    orientation_xyzw: rotation_z_90,
                    active: true,
                    tracked: true,
                }),
            ));
        }
        let observation = last
            .and_then(|result| result.assessment)
            .expect("assessment");
        assert_eq!(observation.tracking, BreathTrackingState::Valid);
        assert!(observation.volume01.is_some());
    }

    #[test]
    fn openxr_translation_uses_the_injected_integer_timestamp() {
        let sampled_at = BreathTimestampMicros::new(123_456);
        let translated = OpenXrControllerPoseFrame::from_native(
            7,
            sampled_at,
            NativeControllerBreathPoseSample {
                sample_time_s: f64::NAN,
                position_m: [0.1, 0.2, 0.3],
                orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
                active: true,
                tracked: true,
            },
        );
        assert_eq!(translated.sampled_at, sampled_at);
        assert_eq!(translated.native_sample().sample_time_s, 0.123_456);
    }

    #[test]
    fn dynamic_axis_calibrates_rotated_translation_and_rejects_insufficient_travel() {
        let diagonal = [0.7071067811865476, 0.0, 0.7071067811865476];
        let mut dynamic = adapter(ControllerVolumeProjection::DynamicMotionAxis);
        start(&mut dynamic, 0, 1);
        let ready = calibrate_line(&mut dynamic, 1, diagonal);
        assert_eq!(ready.calibration.lifecycle, CalibrationLifecycle::Ready);
        let model = ready.calibration.model.expect("dynamic model");
        assert!(dot3(model.axis, diagonal).abs() > 0.99);

        let mut stationary = adapter(ControllerVolumeProjection::DynamicMotionAxis);
        start(&mut stationary, 0, 1);
        for index in 0..8_u64 {
            stationary.observe(
                BreathTimestampMicros::new(index * 100_000),
                generation(1),
                frame(index + 1, index * 100_000, [0.1, 0.0, 0.0]),
            );
        }
        let failed = stationary.observe(
            BreathTimestampMicros::new(1_500_001),
            generation(1),
            OpenXrControllerPoseInput::Missing { sequence_id: 9 },
        );
        assert_eq!(
            failed.calibration.failure,
            Some(CalibrationFailure::Watchdog {
                cause: CalibrationWatchdogCause::InsufficientMotion,
                accepted_frames: 1,
                target_frames: 8,
                elapsed_micros: 1_500_001,
            })
        );
        assert!(matches!(
            failed.rejection,
            Some(ControllerAssessmentRejection::CalibrationFailure(
                CalibrationFailure::Watchdog { .. }
            ))
        ));
        assert_eq!(
            failed.assessment.expect("failure assessment").tracking,
            BreathTrackingState::RejectedMotion
        );
        assert!(matches!(
            failed.rejection,
            Some(ControllerAssessmentRejection::CalibrationFailure(_))
        ));
    }

    #[test]
    fn rotation_guard_clears_both_outputs() {
        let mut adapter = adapter(ControllerVolumeProjection::FixedOrientation);
        start(&mut adapter, 0, 1);
        adapter.observe(
            BreathTimestampMicros::new(0),
            generation(1),
            frame(1, 0, [0.0; 3]),
        );
        let half = (30_f64.to_radians() / 2.0).sin();
        let rejected = adapter.observe(
            BreathTimestampMicros::new(100_000),
            generation(1),
            OpenXrControllerPoseInput::Frame(OpenXrControllerPoseFrame {
                sequence_id: 2,
                sampled_at: BreathTimestampMicros::new(100_000),
                position_m: [0.02, 0.0, 0.0],
                orientation_xyzw: [0.0, half, 0.0, (1.0 - half * half).sqrt()],
                active: true,
                tracked: true,
            }),
        );
        assert_eq!(
            rejected.rejection,
            Some(ControllerAssessmentRejection::RotationGuard)
        );
        assert_eq!(
            rejected.assessment.expect("guard observation").tracking,
            BreathTrackingState::RejectedRotation
        );
        assert!(rejected.calibration.live.is_none());
    }

    #[test]
    fn admission_is_fail_closed_for_disabled_stale_malformed_and_out_of_order() {
        let mut adapter = adapter(ControllerVolumeProjection::FixedOrientation);
        let disabled = adapter.observe(
            BreathTimestampMicros::new(0),
            generation(1),
            frame(1, 0, [0.0; 3]),
        );
        assert_eq!(
            disabled.rejection,
            Some(ControllerAssessmentRejection::Disabled)
        );
        assert!(disabled.assessment.is_none());

        start(&mut adapter, 0, 1);
        let missing = adapter.observe(
            BreathTimestampMicros::new(1),
            generation(1),
            OpenXrControllerPoseInput::Missing { sequence_id: 1 },
        );
        assert_eq!(
            missing.assessment.expect("missing observation").tracking,
            BreathTrackingState::Missing
        );
        let malformed = adapter.observe(
            BreathTimestampMicros::new(10),
            generation(1),
            frame(2, 10, [f64::NAN, 0.0, 0.0]),
        );
        assert_eq!(
            malformed
                .assessment
                .expect("malformed observation")
                .tracking,
            BreathTrackingState::Malformed
        );

        let stale = adapter.observe(
            BreathTimestampMicros::new(200_001),
            generation(1),
            frame(3, 100_000, [0.02, 0.0, 0.0]),
        );
        assert_eq!(
            stale.assessment.expect("stale observation").tracking,
            BreathTrackingState::Stale
        );

        let ordered = adapter.observe(
            BreathTimestampMicros::new(300_000),
            generation(1),
            frame(4, 300_000, [0.04, 0.0, 0.0]),
        );
        assert!(ordered.assessment.is_some());
        let out_of_order = adapter.observe(
            BreathTimestampMicros::new(400_000),
            generation(1),
            frame(4, 400_000, [0.06, 0.0, 0.0]),
        );
        assert_eq!(
            out_of_order
                .assessment
                .expect("ordering observation")
                .tracking,
            BreathTrackingState::OutOfOrder
        );
    }

    #[test]
    fn ready_volume_responds_before_the_next_analysis_tick() {
        let mut adapter = adapter(ControllerVolumeProjection::DynamicMotionAxis);
        start(&mut adapter, 0, 1);
        let ready = calibrate_line(&mut adapter, 1, [1.0, 0.0, 0.0]);
        let ready_volume = ready
            .assessment
            .and_then(|value| value.volume01)
            .expect("ready volume");
        let mut fast = None;
        for offset in 1..=3_u64 {
            let at = 700_000 + offset * 20_000;
            fast = Some(adapter.observe(
                BreathTimestampMicros::new(at),
                generation(1),
                frame(8 + offset, at, [-0.1, 0.0, 0.0]),
            ));
        }
        let fast = fast.expect("rapid live result");
        let fast_observation = fast.assessment.expect("fast live observation");
        assert!(fast_observation.volume01.expect("live volume") < ready_volume);
        assert!(
            !fast
                .calibration
                .live
                .expect("live result")
                .analysis_admitted
        );
    }

    #[test]
    fn reset_and_generation_switch_fence_old_pose_streams() {
        let mut adapter = adapter(ControllerVolumeProjection::FixedOrientation);
        start(&mut adapter, 0, 1);
        adapter.observe(
            BreathTimestampMicros::new(0),
            generation(1),
            frame(1, 0, [0.0; 3]),
        );
        assert_eq!(
            adapter
                .cancel(BreathTimestampMicros::new(10), generation(1))
                .lifecycle,
            CalibrationLifecycle::Cancelled
        );
        assert_eq!(
            adapter
                .start(BreathTimestampMicros::new(20), generation(2))
                .lifecycle,
            CalibrationLifecycle::Collecting
        );
        let old = adapter.observe(
            BreathTimestampMicros::new(30),
            generation(1),
            frame(2, 30, [0.02, 0.0, 0.0]),
        );
        assert_eq!(
            old.rejection,
            Some(ControllerAssessmentRejection::GenerationMismatch)
        );
        assert!(old.assessment.is_none());
        assert_eq!(
            adapter.reset(BreathTimestampMicros::new(40)).lifecycle,
            CalibrationLifecycle::Disabled
        );
        let disabled = adapter.observe(
            BreathTimestampMicros::new(50),
            generation(2),
            frame(3, 50, [0.04, 0.0, 0.0]),
        );
        assert_eq!(
            disabled.rejection,
            Some(ControllerAssessmentRejection::Disabled)
        );
    }

    #[test]
    fn phase_and_volume_are_emitted_concurrently() {
        let mut adapter = adapter(ControllerVolumeProjection::DynamicMotionAxis);
        start(&mut adapter, 0, 1);
        calibrate_line(&mut adapter, 1, [1.0, 0.0, 0.0]);
        let mut saw_directional_phase_with_volume = false;
        for index in 9..20_u64 {
            let at = 700_000 + (index - 7) * 50_000;
            let position = [0.14 + (index - 8) as f64 * 0.01, 0.0, 0.0];
            let result = adapter.observe(
                BreathTimestampMicros::new(at),
                generation(1),
                frame(index, at, position),
            );
            let observation = result.assessment.expect("concurrent observation");
            if observation.volume01.is_some()
                && matches!(
                    observation.phase,
                    CommonBreathPhase::Inhale | CommonBreathPhase::Exhale
                )
            {
                saw_directional_phase_with_volume = true;
            }
        }
        assert!(saw_directional_phase_with_volume);
    }
}
