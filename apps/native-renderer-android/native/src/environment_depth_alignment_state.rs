//! Runtime UV alignment controls for sampling environment depth.
//!
//! This adjusts the depth sampler only. Projection target/video/camera target
//! rectangles remain owned by their existing modules.

use crate::native_renderer_property_values::{bool_value, f32_clamped_value};

pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_CONTROLS: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.controls";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_CONTROLS: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.joystick.controls";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_RATE_UV_PER_SECOND: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.joystick.rate_uv_per_second";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_MAX_OFFSET_UV: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.max_offset_uv";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_SCALE: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.scale";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_X_UV: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.x.uv";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_Y_UV: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.left.offset.y.uv";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_X_UV: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.x.uv";
pub(crate) const PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_Y_UV: &str =
    "debug.rustyquest.native_renderer.environment_depth.alignment.right.offset.y.uv";

const DEFAULT_JOYSTICK_RATE_UV_PER_SECOND: f32 = 0.08;
const DEFAULT_MAX_OFFSET_UV: f32 = 0.25;
const DEFAULT_SAMPLE_SCALE: f32 = 1.0;
const MIN_SAMPLE_SCALE: f32 = 0.25;
const MAX_SAMPLE_SCALE: f32 = 4.0;
const DEFAULT_JOYSTICK_DEADZONE: f32 = 0.18;

#[derive(Clone, Copy, Debug)]
pub(crate) struct EnvironmentDepthAlignmentSettings {
    pub(crate) controls_enabled: bool,
    pub(crate) joystick_controls_enabled: bool,
    pub(crate) joystick_rate_uv_per_second: f32,
    pub(crate) max_offset_uv: f32,
    pub(crate) sample_scale: f32,
    pub(crate) base_offset_uv_by_eye: [[f32; 2]; 2],
}

impl EnvironmentDepthAlignmentSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let max_offset_uv = f32_clamped_value(
            lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_MAX_OFFSET_UV),
            DEFAULT_MAX_OFFSET_UV,
            0.0,
            1.0,
        );
        Self {
            controls_enabled: bool_value(lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_CONTROLS), false),
            joystick_controls_enabled: bool_value(
                lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_CONTROLS),
                false,
            ),
            joystick_rate_uv_per_second: f32_clamped_value(
                lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_RATE_UV_PER_SECOND),
                DEFAULT_JOYSTICK_RATE_UV_PER_SECOND,
                0.0,
                1.0,
            ),
            max_offset_uv,
            sample_scale: sanitize_sample_scale(f32_clamped_value(
                lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_SCALE),
                DEFAULT_SAMPLE_SCALE,
                MIN_SAMPLE_SCALE,
                MAX_SAMPLE_SCALE,
            )),
            base_offset_uv_by_eye: [
                [
                    f32_clamped_value(
                        lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_X_UV),
                        0.0,
                        -1.0,
                        1.0,
                    ),
                    f32_clamped_value(
                        lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_LEFT_OFFSET_Y_UV),
                        0.0,
                        -1.0,
                        1.0,
                    ),
                ],
                [
                    f32_clamped_value(
                        lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_X_UV),
                        0.0,
                        -1.0,
                        1.0,
                    ),
                    f32_clamped_value(
                        lookup(PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_Y_UV),
                        0.0,
                        -1.0,
                        1.0,
                    ),
                ],
            ],
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "environmentDepthAlignmentControlsEnabled={} environmentDepthAlignmentJoystickControlsEnabled={} environmentDepthAlignmentJoystickRateUvPerSecond={:.4} environmentDepthAlignmentMaxOffsetUv={:.4} environmentDepthAlignmentSampleScale={:.4} environmentDepthAlignmentLeftBaseOffsetUv={:.6},{:.6} environmentDepthAlignmentRightBaseOffsetUv={:.6},{:.6} environmentDepthAlignmentInput=left-controller-thumbstick environmentDepthAlignmentAppliesTo=environment-depth-sampler-only environmentDepthAlignmentScaleAppliesTo=environment-depth-sampler-only",
            self.controls_enabled,
            self.joystick_controls_enabled,
            self.joystick_rate_uv_per_second,
            self.max_offset_uv,
            self.sample_scale,
            self.base_offset_uv_by_eye[0][0],
            self.base_offset_uv_by_eye[0][1],
            self.base_offset_uv_by_eye[1][0],
            self.base_offset_uv_by_eye[1][1],
        )
    }
}

impl Default for EnvironmentDepthAlignmentSettings {
    fn default() -> Self {
        Self::from_property_lookup(|_| None)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum EnvironmentDepthAlignmentInput {
    JoystickOffsetDelta {
        left_thumbstick_x: f32,
        left_thumbstick_y: f32,
        dt_seconds: f32,
    },
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct EnvironmentDepthAlignmentState {
    settings: EnvironmentDepthAlignmentSettings,
    manual_offset_uv: [f32; 2],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct EnvironmentDepthAlignmentEyeOffsets {
    pub(crate) base_offset_uv: [f32; 2],
    pub(crate) manual_offset_uv: [f32; 2],
    pub(crate) effective_offset_uv: [f32; 2],
    pub(crate) sample_scale: f32,
}

impl EnvironmentDepthAlignmentState {
    pub(crate) fn new(settings: EnvironmentDepthAlignmentSettings) -> Self {
        Self {
            settings,
            manual_offset_uv: [0.0, 0.0],
        }
    }

    pub(crate) fn apply_input(&mut self, input: EnvironmentDepthAlignmentInput) {
        if !self.settings.controls_enabled || !self.settings.joystick_controls_enabled {
            return;
        }
        match input {
            EnvironmentDepthAlignmentInput::JoystickOffsetDelta {
                left_thumbstick_x,
                left_thumbstick_y,
                dt_seconds,
            } => self.apply_joystick(left_thumbstick_x, left_thumbstick_y, dt_seconds),
        }
    }

    pub(crate) fn controls_enabled(&self) -> bool {
        self.settings.controls_enabled
    }

    pub(crate) fn offset_for_eye(&self, eye_index: usize) -> [f32; 2] {
        self.eye_offsets(eye_index).effective_offset_uv
    }

    pub(crate) fn eye_offsets(&self, eye_index: usize) -> EnvironmentDepthAlignmentEyeOffsets {
        let base = self
            .settings
            .base_offset_uv_by_eye
            .get(eye_index)
            .copied()
            .unwrap_or([0.0, 0.0]);
        let effective_offset_uv = [
            (base[0] + self.manual_offset_uv[0])
                .clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv),
            (base[1] + self.manual_offset_uv[1])
                .clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv),
        ];
        EnvironmentDepthAlignmentEyeOffsets {
            base_offset_uv: base,
            manual_offset_uv: self.manual_offset_uv,
            effective_offset_uv,
            sample_scale: self.settings.sample_scale,
        }
    }

    pub(crate) fn base_offset_for_eye(&self, eye_index: usize) -> [f32; 2] {
        self.settings
            .base_offset_uv_by_eye
            .get(eye_index)
            .copied()
            .unwrap_or([0.0, 0.0])
    }

    pub(crate) fn manual_offset_uv(&self) -> [f32; 2] {
        self.manual_offset_uv
    }

    pub(crate) fn sample_scale(&self) -> f32 {
        self.settings.sample_scale
    }

    pub(crate) fn set_effective_alignment(&mut self, offsets: [[f32; 2]; 2], sample_scale: f32) {
        self.settings.base_offset_uv_by_eye = [
            [
                offsets[0][0].clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv),
                offsets[0][1].clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv),
            ],
            [
                offsets[1][0].clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv),
                offsets[1][1].clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv),
            ],
        ];
        self.settings.sample_scale = sanitize_sample_scale(sample_scale);
        self.manual_offset_uv = [0.0, 0.0];
    }

    pub(crate) fn marker_fields(&self) -> String {
        let left = self.offset_for_eye(0);
        let right = self.offset_for_eye(1);
        format!(
            "{} environmentDepthAlignmentManualOffsetUv={:.6},{:.6} environmentDepthAlignmentLeftEffectiveOffsetUv={:.6},{:.6} environmentDepthAlignmentRightEffectiveOffsetUv={:.6},{:.6} environmentDepthAlignmentEffectiveSampleScale={:.4} environmentDepthAlignmentRuntimeAuthority=native-renderer",
            self.settings.marker_fields(),
            self.manual_offset_uv[0],
            self.manual_offset_uv[1],
            left[0],
            left[1],
            right[0],
            right[1],
            self.settings.sample_scale,
        )
    }

    fn apply_joystick(&mut self, left_thumbstick_x: f32, left_thumbstick_y: f32, dt_seconds: f32) {
        let x = joystick_after_deadzone(left_thumbstick_x);
        let y = joystick_after_deadzone(left_thumbstick_y);
        if x == 0.0 && y == 0.0 {
            return;
        }
        let dt_seconds = sanitize_dt(dt_seconds);
        let delta = self.settings.joystick_rate_uv_per_second * dt_seconds;
        self.manual_offset_uv[0] = (self.manual_offset_uv[0] + x * delta)
            .clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv);
        self.manual_offset_uv[1] = (self.manual_offset_uv[1] + y * delta)
            .clamp(-self.settings.max_offset_uv, self.settings.max_offset_uv);
    }
}

fn joystick_after_deadzone(value: f32) -> f32 {
    if !value.is_finite() || value.abs() <= DEFAULT_JOYSTICK_DEADZONE {
        return 0.0;
    }
    if value > 0.0 {
        value - DEFAULT_JOYSTICK_DEADZONE
    } else {
        value + DEFAULT_JOYSTICK_DEADZONE
    }
}

fn sanitize_dt(dt_seconds: f32) -> f32 {
    if dt_seconds.is_finite() && dt_seconds > 0.0 {
        dt_seconds.min(1.0)
    } else {
        0.0
    }
}

fn sanitize_sample_scale(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(MIN_SAMPLE_SCALE, MAX_SAMPLE_SCALE)
    } else {
        DEFAULT_SAMPLE_SCALE
    }
}

#[cfg(test)]
mod tests {
    use super::{
        EnvironmentDepthAlignmentInput, EnvironmentDepthAlignmentSettings,
        EnvironmentDepthAlignmentState, PROP_ENVIRONMENT_DEPTH_ALIGNMENT_CONTROLS,
        PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_CONTROLS,
        PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_RATE_UV_PER_SECOND,
        PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_X_UV,
        PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_Y_UV, PROP_ENVIRONMENT_DEPTH_ALIGNMENT_SCALE,
    };
    use std::collections::BTreeMap;

    fn settings_from(values: &[(&str, &str)]) -> EnvironmentDepthAlignmentSettings {
        let values = values.iter().copied().collect::<BTreeMap<_, _>>();
        EnvironmentDepthAlignmentSettings::from_property_lookup(|name| {
            values.get(name).map(|value| (*value).to_owned())
        })
    }

    #[test]
    fn left_thumbstick_accumulates_depth_sampler_offset_only() {
        let settings = settings_from(&[
            (PROP_ENVIRONMENT_DEPTH_ALIGNMENT_CONTROLS, "true"),
            (PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_CONTROLS, "true"),
            (
                PROP_ENVIRONMENT_DEPTH_ALIGNMENT_JOYSTICK_RATE_UV_PER_SECOND,
                "0.10",
            ),
            (PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_X_UV, "0.020"),
            (PROP_ENVIRONMENT_DEPTH_ALIGNMENT_RIGHT_OFFSET_Y_UV, "-0.010"),
            (PROP_ENVIRONMENT_DEPTH_ALIGNMENT_SCALE, "1.40"),
        ]);
        let mut state = EnvironmentDepthAlignmentState::new(settings);
        state.apply_input(EnvironmentDepthAlignmentInput::JoystickOffsetDelta {
            left_thumbstick_x: 1.0,
            left_thumbstick_y: -1.0,
            dt_seconds: 0.5,
        });

        let manual = state.manual_offset_uv();
        assert!(manual[0] > 0.040);
        assert!(manual[1] < -0.040);
        let right = state.offset_for_eye(1);
        assert!(right[0] > manual[0]);
        assert!(right[1] < manual[1]);
        assert!(state
            .marker_fields()
            .contains("environmentDepthAlignmentAppliesTo=environment-depth-sampler-only"));
        assert_eq!(state.eye_offsets(1).sample_scale, 1.40);
        assert!(state
            .marker_fields()
            .contains("environmentDepthAlignmentScaleAppliesTo=environment-depth-sampler-only"));
    }
}
