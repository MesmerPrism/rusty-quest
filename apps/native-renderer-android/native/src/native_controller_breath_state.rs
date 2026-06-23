//! Native right-controller breath-state classifier.
//!
//! This mirrors the PMB fixed-controller-orientation state classifier without
//! depending on Manifold transport. It owns only phase assessment; projection
//! scale, reset, driver switching, and haptics stay in the projection-target
//! and OpenXR action layers.

use crate::projection_target_state::{ProjectionTargetBreathState, ProjectionTargetInput};

const DEFAULT_ORIENTATION_AXIS: [f32; 3] = [0.0, 0.0, -1.0];
const DEFAULT_INHALE_THRESHOLD: f32 = 0.001;
const DEFAULT_EXHALE_THRESHOLD: f32 = -0.00057;
const DEFAULT_ROTATION_GUARD_DEGREES: f32 = 0.5;
const DEFAULT_MOVING_AVERAGE_GUARD: f32 = 0.025;
const DEFAULT_SHORT_WINDOW_SAMPLES: u32 = 24;
const DEFAULT_LONG_WINDOW_SAMPLES: u32 = 180;
const DEFAULT_SHORT_WINDOW_SECONDS: f32 = 1.0 / 3.0;
const DEFAULT_LONG_WINDOW_SECONDS: f32 = 2.5;
const EPSILON: f64 = 1.0e-9;

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeControllerBreathSettings {
    pub(crate) orientation_axis: [f32; 3],
    pub(crate) inhale_threshold: f32,
    pub(crate) exhale_threshold: f32,
    pub(crate) rotation_guard_degrees: f32,
    pub(crate) moving_average_guard: f32,
    pub(crate) short_window_samples: u32,
    pub(crate) long_window_samples: u32,
    pub(crate) short_window_seconds: f32,
    pub(crate) long_window_seconds: f32,
}

impl NativeControllerBreathSettings {
    pub(crate) fn new(
        orientation_axis: [f32; 3],
        inhale_threshold: f32,
        exhale_threshold: f32,
        rotation_guard_degrees: f32,
        moving_average_guard: f32,
        short_window_samples: u32,
        long_window_samples: u32,
        short_window_seconds: f32,
        long_window_seconds: f32,
    ) -> Self {
        let mut settings = Self {
            orientation_axis,
            inhale_threshold,
            exhale_threshold,
            rotation_guard_degrees,
            moving_average_guard,
            short_window_samples: short_window_samples.max(1),
            long_window_samples: long_window_samples.max(short_window_samples.max(1)),
            short_window_seconds,
            long_window_seconds,
        };
        if normalized_axis_f32(settings.orientation_axis).is_none() {
            settings.orientation_axis = DEFAULT_ORIENTATION_AXIS;
        }
        if !settings.inhale_threshold.is_finite()
            || !settings.exhale_threshold.is_finite()
            || settings.exhale_threshold >= settings.inhale_threshold
        {
            settings.inhale_threshold = DEFAULT_INHALE_THRESHOLD;
            settings.exhale_threshold = DEFAULT_EXHALE_THRESHOLD;
        }
        if !settings.rotation_guard_degrees.is_finite() || settings.rotation_guard_degrees <= 0.0 {
            settings.rotation_guard_degrees = DEFAULT_ROTATION_GUARD_DEGREES;
        }
        if !settings.moving_average_guard.is_finite() || settings.moving_average_guard <= 0.0 {
            settings.moving_average_guard = DEFAULT_MOVING_AVERAGE_GUARD;
        }
        if !settings.short_window_seconds.is_finite() || settings.short_window_seconds <= 0.0 {
            settings.short_window_seconds = DEFAULT_SHORT_WINDOW_SECONDS;
        }
        if !settings.long_window_seconds.is_finite()
            || settings.long_window_seconds < settings.short_window_seconds
        {
            settings.long_window_seconds = settings.short_window_seconds;
        }
        settings
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "nativeControllerBreathMode=fixed-controller-orientation nativeControllerBreathAxis={:.4},{:.4},{:.4} nativeControllerBreathInhaleThreshold={:.6} nativeControllerBreathExhaleThreshold={:.6} nativeControllerBreathRotationGuardDegrees={:.4} nativeControllerBreathMovingAverageGuard={:.6} nativeControllerBreathShortWindowSamples={} nativeControllerBreathLongWindowSamples={} nativeControllerBreathShortWindowSeconds={:.6} nativeControllerBreathLongWindowSeconds={:.6}",
            self.orientation_axis[0],
            self.orientation_axis[1],
            self.orientation_axis[2],
            self.inhale_threshold,
            self.exhale_threshold,
            self.rotation_guard_degrees,
            self.moving_average_guard,
            self.short_window_samples,
            self.long_window_samples,
            self.short_window_seconds,
            self.long_window_seconds,
        )
    }
}

impl Default for NativeControllerBreathSettings {
    fn default() -> Self {
        Self {
            orientation_axis: DEFAULT_ORIENTATION_AXIS,
            inhale_threshold: DEFAULT_INHALE_THRESHOLD,
            exhale_threshold: DEFAULT_EXHALE_THRESHOLD,
            rotation_guard_degrees: DEFAULT_ROTATION_GUARD_DEGREES,
            moving_average_guard: DEFAULT_MOVING_AVERAGE_GUARD,
            short_window_samples: DEFAULT_SHORT_WINDOW_SAMPLES,
            long_window_samples: DEFAULT_LONG_WINDOW_SAMPLES,
            short_window_seconds: DEFAULT_SHORT_WINDOW_SECONDS,
            long_window_seconds: DEFAULT_LONG_WINDOW_SECONDS,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeControllerBreathPoseSample {
    pub(crate) sample_time_s: f64,
    pub(crate) position_m: [f32; 3],
    pub(crate) orientation_xyzw: [f32; 4],
    pub(crate) active: bool,
    pub(crate) tracked: bool,
}

impl NativeControllerBreathPoseSample {
    fn ready(self) -> bool {
        self.active
            && self.tracked
            && self.sample_time_s.is_finite()
            && self.position_m.iter().all(|value| value.is_finite())
            && self.orientation_xyzw.iter().all(|value| value.is_finite())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NativeControllerBreathPhase {
    Unknown,
    Inhale,
    Exhale,
    Pause,
    BadTracking,
}

impl NativeControllerBreathPhase {
    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Unknown => "unknown",
            Self::Inhale => "inhale",
            Self::Exhale => "exhale",
            Self::Pause => "pause",
            Self::BadTracking => "bad-tracking",
        }
    }

    fn projection_target_state(self) -> ProjectionTargetBreathState {
        match self {
            Self::Unknown => ProjectionTargetBreathState::Unknown,
            Self::Inhale => ProjectionTargetBreathState::Inhale,
            Self::Exhale => ProjectionTargetBreathState::Exhale,
            Self::Pause => ProjectionTargetBreathState::Pause,
            Self::BadTracking => ProjectionTargetBreathState::BadTracking,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct NativeControllerBreathSample {
    pub(crate) phase: NativeControllerBreathPhase,
    pub(crate) sequence_id: u64,
}

impl NativeControllerBreathSample {
    pub(crate) fn projection_target_input(self) -> ProjectionTargetInput {
        ProjectionTargetInput::BreathState {
            state: self.phase.projection_target_state(),
            sequence_id: Some(self.sequence_id),
        }
    }
}

#[derive(Debug)]
pub(crate) struct NativeControllerBreathStateEstimator {
    settings: NativeControllerBreathSettings,
    classifier: FixedControllerStateClassifier,
    sequence_id: u64,
    emitted_samples: u64,
    tracking_loss_samples: u64,
}

impl NativeControllerBreathStateEstimator {
    pub(crate) fn new(settings: NativeControllerBreathSettings) -> Self {
        Self {
            settings,
            classifier: FixedControllerStateClassifier::new(settings),
            sequence_id: 0,
            emitted_samples: 0,
            tracking_loss_samples: 0,
        }
    }

    pub(crate) fn push_pose_sample(
        &mut self,
        sample: Option<NativeControllerBreathPoseSample>,
    ) -> ProjectionTargetInput {
        self.push_breath_sample(sample).projection_target_input()
    }

    pub(crate) fn push_breath_sample(
        &mut self,
        sample: Option<NativeControllerBreathPoseSample>,
    ) -> NativeControllerBreathSample {
        let phase = match sample {
            Some(sample) if sample.ready() => self.classifier.push_sample(self.settings, sample),
            _ => {
                self.classifier.reset();
                self.tracking_loss_samples = self.tracking_loss_samples.saturating_add(1);
                NativeControllerBreathPhase::BadTracking
            }
        };
        self.sequence_id = self.sequence_id.saturating_add(1);
        self.emitted_samples = self.emitted_samples.saturating_add(1);
        NativeControllerBreathSample {
            phase,
            sequence_id: self.sequence_id,
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "nativeControllerBreathSamples={} nativeControllerBreathTrackingLossSamples={} nativeControllerBreathLastSequenceId={}",
            self.emitted_samples, self.tracking_loss_samples, self.sequence_id
        )
    }
}

#[derive(Clone, Copy, Debug)]
struct FixedControllerDeltaPoint {
    sample_time_s: f64,
    accumulator: f64,
}

#[derive(Debug)]
struct FixedControllerStateClassifier {
    orientation_axis: [f64; 3],
    last_position: Option<[f64; 3]>,
    last_orientation: [f64; 4],
    last_sample_time_s: Option<f64>,
    delta_accumulator: f64,
    delta_history: Vec<FixedControllerDeltaPoint>,
}

impl FixedControllerStateClassifier {
    fn new(settings: NativeControllerBreathSettings) -> Self {
        Self {
            orientation_axis: normalized_axis(to_f64_3(settings.orientation_axis))
                .unwrap_or([0.0, 0.0, -1.0]),
            last_position: None,
            last_orientation: [0.0, 0.0, 0.0, 1.0],
            last_sample_time_s: None,
            delta_accumulator: 0.0,
            delta_history: Vec::new(),
        }
    }

    fn reset(&mut self) {
        self.last_position = None;
        self.last_orientation = [0.0, 0.0, 0.0, 1.0];
        self.last_sample_time_s = None;
        self.delta_accumulator = 0.0;
        self.delta_history.clear();
    }

    fn push_sample(
        &mut self,
        settings: NativeControllerBreathSettings,
        sample: NativeControllerBreathPoseSample,
    ) -> NativeControllerBreathPhase {
        let position = to_f64_3(sample.position_m);
        let orientation = to_f64_4(sample.orientation_xyzw);
        let Some(last_position) = self.last_position else {
            self.last_position = Some(position);
            self.last_orientation = orientation;
            self.last_sample_time_s = Some(sample.sample_time_s);
            return NativeControllerBreathPhase::Pause;
        };

        let sample_time_s = fixed_controller_sample_time_s(
            sample.sample_time_s,
            self.last_sample_time_s,
            fixed_controller_fallback_sample_seconds(settings),
        );
        let rotation_delta = quat_angle_degrees(self.last_orientation, orientation);
        let axis_world = rotate_vec3_by_quat(
            self.orientation_axis,
            normalize_quat_or_identity(orientation),
        );
        let delta = dot3(sub3(position, last_position), axis_world);
        self.delta_accumulator += delta;
        push_timed_delta_point(
            &mut self.delta_history,
            FixedControllerDeltaPoint {
                sample_time_s,
                accumulator: self.delta_accumulator,
            },
            f64::from(settings.long_window_seconds),
        );
        let short_mean = mean_timed_delta_accumulator(
            &self.delta_history,
            sample_time_s,
            f64::from(settings.short_window_seconds),
        );
        let long_mean = mean_timed_delta_accumulator(
            &self.delta_history,
            sample_time_s,
            f64::from(settings.long_window_seconds),
        );
        let ma_diff = short_mean - long_mean;
        self.last_position = Some(position);
        self.last_orientation = orientation;
        self.last_sample_time_s = Some(sample_time_s);

        if rotation_delta > f64::from(settings.rotation_guard_degrees)
            || ma_diff.abs() > f64::from(settings.moving_average_guard)
        {
            return NativeControllerBreathPhase::BadTracking;
        }
        if ma_diff > f64::from(settings.inhale_threshold) {
            NativeControllerBreathPhase::Inhale
        } else if ma_diff < f64::from(settings.exhale_threshold) {
            NativeControllerBreathPhase::Exhale
        } else {
            NativeControllerBreathPhase::Pause
        }
    }
}

fn fixed_controller_fallback_sample_seconds(settings: NativeControllerBreathSettings) -> f64 {
    let fallback =
        f64::from(settings.short_window_seconds) / f64::from(settings.short_window_samples.max(1));
    if fallback.is_finite() && fallback > 0.0 {
        fallback
    } else {
        1.0 / 72.0
    }
}

fn fixed_controller_sample_time_s(
    sample_time_s: f64,
    last_sample_time_s: Option<f64>,
    fallback_sample_seconds: f64,
) -> f64 {
    match (sample_time_s.is_finite(), last_sample_time_s) {
        (true, Some(last)) if sample_time_s > last => sample_time_s,
        (true, None) => sample_time_s,
        _ => last_sample_time_s
            .map(|last| last + fallback_sample_seconds)
            .unwrap_or(0.0),
    }
}

fn push_timed_delta_point(
    history: &mut Vec<FixedControllerDeltaPoint>,
    point: FixedControllerDeltaPoint,
    long_window_s: f64,
) {
    if history
        .last()
        .is_some_and(|last| point.sample_time_s < last.sample_time_s)
    {
        history.clear();
    }
    history.push(point);
    let cutoff = point.sample_time_s - long_window_s.max(1.0e-3);
    let first_kept = history
        .iter()
        .position(|sample| sample.sample_time_s >= cutoff)
        .unwrap_or_else(|| history.len().saturating_sub(1));
    if first_kept > 0 {
        history.drain(0..first_kept);
    }
}

fn mean_timed_delta_accumulator(
    history: &[FixedControllerDeltaPoint],
    now_s: f64,
    window_s: f64,
) -> f64 {
    let cutoff = now_s - window_s.max(1.0e-3);
    let mut count = 0_u64;
    let mut sum = 0.0;
    for point in history.iter().rev() {
        if point.sample_time_s < cutoff {
            break;
        }
        count += 1;
        sum += point.accumulator;
    }
    if count == 0 {
        history
            .last()
            .map(|point| point.accumulator)
            .unwrap_or_default()
    } else {
        sum / count as f64
    }
}

fn quat_angle_degrees(left: [f64; 4], right: [f64; 4]) -> f64 {
    let left = normalize_quat_or_identity(left);
    let right = normalize_quat_or_identity(right);
    let dot = (left[0] * right[0] + left[1] * right[1] + left[2] * right[2] + left[3] * right[3])
        .abs()
        .clamp(-1.0, 1.0);
    (2.0 * dot.acos()).to_degrees()
}

fn normalize_quat_or_identity(value: [f64; 4]) -> [f64; 4] {
    if !value.iter().all(|value| value.is_finite()) {
        return [0.0, 0.0, 0.0, 1.0];
    }
    let length =
        (value[0] * value[0] + value[1] * value[1] + value[2] * value[2] + value[3] * value[3])
            .sqrt();
    if length <= EPSILON {
        [0.0, 0.0, 0.0, 1.0]
    } else {
        [
            value[0] / length,
            value[1] / length,
            value[2] / length,
            value[3] / length,
        ]
    }
}

fn rotate_vec3_by_quat(value: [f64; 3], quat_xyzw: [f64; 4]) -> [f64; 3] {
    let q = quat_xyzw;
    let u = [q[0], q[1], q[2]];
    let s = q[3];
    let uv = cross3(u, value);
    let uuv = cross3(u, uv);
    add3(value, add3(scale3(uv, 2.0 * s), scale3(uuv, 2.0)))
}

fn normalized_axis_f32(axis: [f32; 3]) -> Option<[f32; 3]> {
    normalized_axis(to_f64_3(axis)).map(|axis| [axis[0] as f32, axis[1] as f32, axis[2] as f32])
}

fn normalized_axis(axis: [f64; 3]) -> Option<[f64; 3]> {
    if !axis.iter().all(|value| value.is_finite()) {
        return None;
    }
    let length = axis.iter().map(|value| value * value).sum::<f64>().sqrt();
    if length <= EPSILON {
        None
    } else {
        Some([axis[0] / length, axis[1] / length, axis[2] / length])
    }
}

fn to_f64_3(value: [f32; 3]) -> [f64; 3] {
    [
        f64::from(value[0]),
        f64::from(value[1]),
        f64::from(value[2]),
    ]
}

fn to_f64_4(value: [f32; 4]) -> [f64; 4] {
    [
        f64::from(value[0]),
        f64::from(value[1]),
        f64::from(value[2]),
        f64::from(value[3]),
    ]
}

fn add3(left: [f64; 3], right: [f64; 3]) -> [f64; 3] {
    [left[0] + right[0], left[1] + right[1], left[2] + right[2]]
}

fn sub3(left: [f64; 3], right: [f64; 3]) -> [f64; 3] {
    [left[0] - right[0], left[1] - right[1], left[2] - right[2]]
}

fn scale3(value: [f64; 3], scale: f64) -> [f64; 3] {
    [value[0] * scale, value[1] * scale, value[2] * scale]
}

fn cross3(left: [f64; 3], right: [f64; 3]) -> [f64; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

fn dot3(left: [f64; 3], right: [f64; 3]) -> f64 {
    left[0] * right[0] + left[1] * right[1] + left[2] * right[2]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_at(t: f64, y: f32, tracked: bool) -> NativeControllerBreathPoseSample {
        NativeControllerBreathPoseSample {
            sample_time_s: t,
            position_m: [0.0, y, 0.0],
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
            active: tracked,
            tracked,
        }
    }

    fn test_settings() -> NativeControllerBreathSettings {
        NativeControllerBreathSettings::new(
            [0.0, 1.0, 0.0],
            0.0002,
            -0.0002,
            30.0,
            0.25,
            2,
            4,
            0.2,
            0.4,
        )
    }

    fn input_state(input: ProjectionTargetInput) -> ProjectionTargetBreathState {
        match input {
            ProjectionTargetInput::BreathState { state, .. } => state,
            _ => panic!("expected breath state input"),
        }
    }

    #[test]
    fn detects_inhale_and_exhale_like_pmb_fixed_controller_state() {
        let mut estimator = NativeControllerBreathStateEstimator::new(test_settings());
        let states: Vec<_> = [0.0, 0.002, 0.004, 0.006, 0.004, 0.002, 0.0, -0.002]
            .into_iter()
            .enumerate()
            .map(|(index, y)| {
                input_state(estimator.push_pose_sample(Some(sample_at(
                    index as f64 * 0.1,
                    y,
                    true,
                ))))
            })
            .collect();

        assert!(states.contains(&ProjectionTargetBreathState::Inhale));
        assert_eq!(states.last(), Some(&ProjectionTargetBreathState::Exhale));
    }

    #[test]
    fn tracking_loss_emits_bad_tracking_and_resets_motion_history() {
        let mut estimator = NativeControllerBreathStateEstimator::new(test_settings());
        assert_eq!(
            input_state(estimator.push_pose_sample(Some(sample_at(0.0, 0.0, true)))),
            ProjectionTargetBreathState::Pause
        );
        assert_eq!(
            input_state(estimator.push_pose_sample(Some(sample_at(0.1, 0.002, false)))),
            ProjectionTargetBreathState::BadTracking
        );
        assert_eq!(
            input_state(estimator.push_pose_sample(Some(sample_at(0.2, 0.004, true)))),
            ProjectionTargetBreathState::Pause
        );
    }

    #[test]
    fn uses_time_windows_across_pose_cadences() {
        fn states_for_hz(sample_hz: f64) -> Vec<ProjectionTargetBreathState> {
            let mut settings = test_settings();
            settings.short_window_seconds = 0.3;
            settings.long_window_seconds = 0.9;
            let mut estimator = NativeControllerBreathStateEstimator::new(settings);
            let dt = 1.0 / sample_hz;
            let sample_count = (2.0 * sample_hz).round() as usize;
            (0..=sample_count)
                .map(|index| {
                    let t = index as f64 * dt;
                    let y = if t <= 1.0 {
                        t as f32 * 0.004
                    } else {
                        (2.0 - t).max(0.0) as f32 * 0.004
                    };
                    input_state(estimator.push_pose_sample(Some(sample_at(t, y, true))))
                })
                .collect()
        }

        for states in [states_for_hz(10.0), states_for_hz(90.0)] {
            assert!(states.contains(&ProjectionTargetBreathState::Inhale));
            assert!(states.contains(&ProjectionTargetBreathState::Exhale));
        }
    }
}
