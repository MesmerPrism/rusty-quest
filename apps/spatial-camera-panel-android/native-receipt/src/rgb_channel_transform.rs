//! Public, renderer-neutral RGB channel transform configuration.
//!
//! This module owns bounded low-rate parameters, identity behavior, transport
//! layout, and a CPU reference for channel UV transforms. It deliberately does
//! not define what an application-specific guide signal means or how a private
//! effect derives that signal.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{OnceLock, RwLock};

pub(crate) const RGB_CHANNEL_COUNT: usize = 3;
pub(crate) const RGB_CHANNEL_TRANSFORM_CONTRACT_ID: &str = "rusty.quest.rgb-channel-transform.v1";
pub(crate) const RGB_DIRECTION_RATE_MAX_HZ: f32 = 2.0;
pub(crate) const RGB_DIRECTION_NOISE_AMOUNT_MAX_TURNS: f32 = 0.125;
pub(crate) const RGB_DIRECTION_NOISE_RATE_MAX_HZ: f32 = 1.0;
pub(crate) const RGB_DISPLACEMENT_MAX_UV: f32 = 0.08;
pub(crate) const RGB_IMAGE_SCALE_MIN: f32 = 0.5;
pub(crate) const RGB_IMAGE_SCALE_MAX: f32 = 2.0;
pub(crate) const RGB_COVERAGE_SCALE_MIN: f32 = 0.5;
pub(crate) const RGB_COVERAGE_SCALE_MAX: f32 = 1.0;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum RgbChannelTransformMode {
    Bypass = 0,
    Independent = 1,
    Linked = 2,
}

impl RgbChannelTransformMode {
    fn from_code(code: u32) -> Self {
        match code {
            1 => Self::Independent,
            2 => Self::Linked,
            _ => Self::Bypass,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Bypass => "bypass",
            Self::Independent => "independent-rgb",
            Self::Linked => "linked-rgb",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub(crate) enum RgbChannelEdgeMode {
    Clamp = 0,
    Mirror = 1,
    Fade = 2,
}

impl RgbChannelEdgeMode {
    fn from_code(code: u32) -> Self {
        match code {
            1 => Self::Mirror,
            2 => Self::Fade,
            _ => Self::Clamp,
        }
    }

    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Clamp => "clamp",
            Self::Mirror => "mirror",
            Self::Fade => "fade",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct RgbChannelTransformSettings {
    pub(crate) mode: RgbChannelTransformMode,
    pub(crate) edge_mode: RgbChannelEdgeMode,
    pub(crate) direction_turns: [f32; RGB_CHANNEL_COUNT],
    pub(crate) direction_rate_hz: [f32; RGB_CHANNEL_COUNT],
    pub(crate) direction_noise_amount_turns: f32,
    pub(crate) direction_noise_rate_hz: f32,
    pub(crate) displacement_strength_uv: [f32; RGB_CHANNEL_COUNT],
    pub(crate) image_scale: [f32; RGB_CHANNEL_COUNT],
    pub(crate) coverage_scale: [f32; RGB_CHANNEL_COUNT],
    pub(crate) revision: u32,
}

impl Default for RgbChannelTransformSettings {
    fn default() -> Self {
        Self {
            mode: RgbChannelTransformMode::Bypass,
            edge_mode: RgbChannelEdgeMode::Clamp,
            direction_turns: [0.0; RGB_CHANNEL_COUNT],
            direction_rate_hz: [0.0; RGB_CHANNEL_COUNT],
            direction_noise_amount_turns: 0.0,
            direction_noise_rate_hz: 0.1,
            displacement_strength_uv: [0.0; RGB_CHANNEL_COUNT],
            image_scale: [1.0; RGB_CHANNEL_COUNT],
            coverage_scale: [1.0; RGB_CHANNEL_COUNT],
            revision: 0,
        }
    }
}

impl RgbChannelTransformSettings {
    pub(crate) fn normalized(mut self) -> Self {
        for value in &mut self.direction_turns {
            *value = finite_or(*value, 0.0).rem_euclid(1.0);
        }
        for value in &mut self.direction_rate_hz {
            *value =
                finite_or(*value, 0.0).clamp(-RGB_DIRECTION_RATE_MAX_HZ, RGB_DIRECTION_RATE_MAX_HZ);
        }
        self.direction_noise_amount_turns = finite_or(self.direction_noise_amount_turns, 0.0)
            .clamp(0.0, RGB_DIRECTION_NOISE_AMOUNT_MAX_TURNS);
        self.direction_noise_rate_hz = finite_or(self.direction_noise_rate_hz, 0.1)
            .clamp(0.0, RGB_DIRECTION_NOISE_RATE_MAX_HZ);
        for value in &mut self.displacement_strength_uv {
            *value = finite_or(*value, 0.0).clamp(0.0, RGB_DISPLACEMENT_MAX_UV);
        }
        for value in &mut self.image_scale {
            *value = finite_or(*value, 1.0).clamp(RGB_IMAGE_SCALE_MIN, RGB_IMAGE_SCALE_MAX);
        }
        for value in &mut self.coverage_scale {
            *value = finite_or(*value, 1.0).clamp(RGB_COVERAGE_SCALE_MIN, RGB_COVERAGE_SCALE_MAX);
        }
        if self.mode == RgbChannelTransformMode::Linked {
            self.direction_turns = [self.direction_turns[0]; RGB_CHANNEL_COUNT];
            self.direction_rate_hz = [self.direction_rate_hz[0]; RGB_CHANNEL_COUNT];
            self.displacement_strength_uv = [self.displacement_strength_uv[0]; RGB_CHANNEL_COUNT];
            self.image_scale = [self.image_scale[0]; RGB_CHANNEL_COUNT];
            self.coverage_scale = [self.coverage_scale[0]; RGB_CHANNEL_COUNT];
        }
        self
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "rgbChannelTransformContract={} rgbChannelTransformMode={} rgbChannelTransformEdge={} rgbChannelTransformRevision={} rgbDirectionTurns={:.4},{:.4},{:.4} rgbDirectionRateHz={:.4},{:.4},{:.4} rgbDirectionNoiseAmountTurns={:.5} rgbDirectionNoiseRateHz={:.4} rgbDisplacementStrengthUv={:.5},{:.5},{:.5} rgbImageScale={:.4},{:.4},{:.4} rgbCoverageScale={:.4},{:.4},{:.4}",
            RGB_CHANNEL_TRANSFORM_CONTRACT_ID,
            self.mode.marker_token(),
            self.edge_mode.marker_token(),
            self.revision,
            self.direction_turns[0],
            self.direction_turns[1],
            self.direction_turns[2],
            self.direction_rate_hz[0],
            self.direction_rate_hz[1],
            self.direction_rate_hz[2],
            self.direction_noise_amount_turns,
            self.direction_noise_rate_hz,
            self.displacement_strength_uv[0],
            self.displacement_strength_uv[1],
            self.displacement_strength_uv[2],
            self.image_scale[0],
            self.image_scale[1],
            self.image_scale[2],
            self.coverage_scale[0],
            self.coverage_scale[1],
            self.coverage_scale[2],
        )
    }

    pub(crate) fn uniform(self) -> RgbChannelTransformUniform {
        RgbChannelTransformUniform {
            mode: [
                self.mode as u32 as f32,
                self.edge_mode as u32 as f32,
                self.revision as f32,
                0.0,
            ],
            direction_turns: rgb_vec4(self.direction_turns, 0.0),
            direction_rate_hz: rgb_vec4(self.direction_rate_hz, 0.0),
            displacement_strength_uv: rgb_vec4(self.displacement_strength_uv, 0.0),
            image_scale: rgb_vec4(self.image_scale, 1.0),
            coverage_scale: rgb_vec4(self.coverage_scale, 1.0),
        }
    }

    pub(crate) fn uniform_at_elapsed_seconds(
        self,
        elapsed_seconds: f32,
    ) -> RgbChannelTransformUniform {
        let mut uniform = self.uniform();
        if self.mode == RgbChannelTransformMode::Bypass || self.direction_noise_amount_turns <= 0.0
        {
            return uniform;
        }
        if self.mode == RgbChannelTransformMode::Linked {
            let direction_turns = (self.direction_turns[0]
                + smooth_direction_noise_turns(
                    elapsed_seconds,
                    self.direction_noise_rate_hz,
                    self.direction_noise_amount_turns,
                    0.0,
                ))
            .rem_euclid(1.0);
            uniform.direction_turns[..RGB_CHANNEL_COUNT].fill(direction_turns);
            return uniform;
        }
        for (channel, seed) in [0.0, 0.381_966_02, 0.754_877_7].into_iter().enumerate() {
            uniform.direction_turns[channel] = (self.direction_turns[channel]
                + smooth_direction_noise_turns(
                    elapsed_seconds,
                    self.direction_noise_rate_hz,
                    self.direction_noise_amount_turns,
                    seed,
                ))
            .rem_euclid(1.0);
        }
        uniform
    }

    #[cfg(test)]
    fn channel_sample(
        self,
        channel: usize,
        uv: [f32; 2],
        centered_signal: f32,
        elapsed_seconds: f32,
    ) -> RgbChannelSample {
        if self.mode == RgbChannelTransformMode::Bypass {
            return RgbChannelSample {
                uv,
                coverage: 1.0,
                edge_alpha: 1.0,
            };
        }
        let channel = channel.min(RGB_CHANNEL_COUNT - 1);
        let elapsed_seconds = finite_or(elapsed_seconds, 0.0).max(0.0);
        let direction_turns = self
            .uniform_at_elapsed_seconds(elapsed_seconds)
            .direction_turns[channel];
        let angle = std::f32::consts::TAU
            * (direction_turns + elapsed_seconds * self.direction_rate_hz[channel]);
        let direction = [angle.cos(), angle.sin()];
        let scale = self.image_scale[channel];
        let signal = finite_or(centered_signal, 0.0).clamp(-0.5, 0.5);
        let displacement = signal * self.displacement_strength_uv[channel];
        let transformed = [
            0.5 + (uv[0] - 0.5) / scale + direction[0] * displacement,
            0.5 + (uv[1] - 0.5) / scale + direction[1] * displacement,
        ];
        let coverage = channel_coverage(uv, self.coverage_scale[channel]);
        let (uv, edge_alpha) = apply_edge_mode(transformed, self.edge_mode);
        RgbChannelSample {
            uv,
            coverage,
            edge_alpha,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct RgbChannelTransformUniform {
    pub(crate) mode: [f32; 4],
    pub(crate) direction_turns: [f32; 4],
    pub(crate) direction_rate_hz: [f32; 4],
    pub(crate) displacement_strength_uv: [f32; 4],
    pub(crate) image_scale: [f32; 4],
    pub(crate) coverage_scale: [f32; 4],
}

const _: () = assert!(std::mem::size_of::<RgbChannelTransformUniform>() == 96);

static RGB_CHANNEL_TRANSFORM_SETTINGS: OnceLock<RwLock<RgbChannelTransformSettings>> =
    OnceLock::new();
static RGB_CHANNEL_TRANSFORM_REVISION: AtomicU32 = AtomicU32::new(0);

fn settings_lock() -> &'static RwLock<RgbChannelTransformSettings> {
    RGB_CHANNEL_TRANSFORM_SETTINGS
        .get_or_init(|| RwLock::new(RgbChannelTransformSettings::default()))
}

pub(crate) fn current_rgb_channel_transform_settings() -> RgbChannelTransformSettings {
    *settings_lock()
        .read()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn update_rgb_channel_transform_settings(
    mode_code: u32,
    edge_mode_code: u32,
    direction_turns: [f32; RGB_CHANNEL_COUNT],
    direction_rate_hz: [f32; RGB_CHANNEL_COUNT],
    direction_noise_amount_turns: f32,
    direction_noise_rate_hz: f32,
    displacement_strength_uv: [f32; RGB_CHANNEL_COUNT],
    image_scale: [f32; RGB_CHANNEL_COUNT],
    coverage_scale: [f32; RGB_CHANNEL_COUNT],
) -> RgbChannelTransformSettings {
    let revision = RGB_CHANNEL_TRANSFORM_REVISION
        .fetch_add(1, Ordering::AcqRel)
        .saturating_add(1);
    let settings = RgbChannelTransformSettings {
        mode: RgbChannelTransformMode::from_code(mode_code),
        edge_mode: RgbChannelEdgeMode::from_code(edge_mode_code),
        direction_turns,
        direction_rate_hz,
        direction_noise_amount_turns,
        direction_noise_rate_hz,
        displacement_strength_uv,
        image_scale,
        coverage_scale,
        revision,
    }
    .normalized();
    *settings_lock()
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = settings;
    settings
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value
    } else {
        fallback
    }
}

fn smooth_direction_noise_turns(
    elapsed_seconds: f32,
    noise_rate_hz: f32,
    amount_turns: f32,
    seed: f32,
) -> f32 {
    let amount_turns =
        finite_or(amount_turns, 0.0).clamp(0.0, RGB_DIRECTION_NOISE_AMOUNT_MAX_TURNS);
    if amount_turns <= 0.0 {
        return 0.0;
    }
    let phase = finite_or(elapsed_seconds, 0.0).max(0.0)
        * finite_or(noise_rate_hz, 0.0).clamp(0.0, RGB_DIRECTION_NOISE_RATE_MAX_HZ);
    let cell = phase.floor();
    let blend = smoothstep(cell, cell + 1.0, phase);
    amount_turns
        * mix(
            unit_lattice_noise(cell, seed),
            unit_lattice_noise(cell + 1.0, seed),
            blend,
        )
}

fn unit_lattice_noise(cell: f32, seed: f32) -> f32 {
    let hashed = (cell * 12.9898 + finite_or(seed, 0.0) * 78.233 + 37.719).sin() * 43_758.547;
    (hashed - hashed.floor()) * 2.0 - 1.0
}

fn mix(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

fn rgb_vec4(values: [f32; RGB_CHANNEL_COUNT], alpha: f32) -> [f32; 4] {
    [values[0], values[1], values[2], alpha]
}

#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq)]
struct RgbChannelSample {
    uv: [f32; 2],
    coverage: f32,
    edge_alpha: f32,
}

#[cfg(test)]
fn channel_coverage(uv: [f32; 2], scale: f32) -> f32 {
    if scale >= 0.9999 {
        return 1.0;
    }
    let radius = (uv[0] - 0.5).abs().max((uv[1] - 0.5).abs());
    let outer = 0.5 * scale;
    let inner = (outer - 0.02).max(0.0);
    1.0 - smoothstep(inner, outer, radius)
}

#[cfg(test)]
fn apply_edge_mode(uv: [f32; 2], mode: RgbChannelEdgeMode) -> ([f32; 2], f32) {
    match mode {
        RgbChannelEdgeMode::Clamp => ([uv[0].clamp(0.001, 0.999), uv[1].clamp(0.001, 0.999)], 1.0),
        RgbChannelEdgeMode::Mirror => ([mirror_uv(uv[0]), mirror_uv(uv[1])], 1.0),
        RgbChannelEdgeMode::Fade => {
            let edge_alpha = edge_fade(uv[0]) * edge_fade(uv[1]);
            (
                [uv[0].clamp(0.001, 0.999), uv[1].clamp(0.001, 0.999)],
                edge_alpha,
            )
        }
    }
}

#[cfg(test)]
fn mirror_uv(value: f32) -> f32 {
    let wrapped = value.rem_euclid(2.0);
    if wrapped <= 1.0 {
        wrapped
    } else {
        2.0 - wrapped
    }
    .clamp(0.001, 0.999)
}

#[cfg(test)]
fn edge_fade(value: f32) -> f32 {
    smoothstep(-0.02, 0.0, value) * (1.0 - smoothstep(1.0, 1.02, value))
}

fn smoothstep(edge0: f32, edge1: f32, value: f32) -> f32 {
    let width = (edge1 - edge0).max(f32::EPSILON);
    let t = ((value - edge0) / width).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_exact_bypass_identity() {
        let settings = RgbChannelTransformSettings::default();
        for channel in 0..RGB_CHANNEL_COUNT {
            assert_eq!(
                settings.channel_sample(channel, [0.2, 0.8], 0.5, 123.0),
                RgbChannelSample {
                    uv: [0.2, 0.8],
                    coverage: 1.0,
                    edge_alpha: 1.0,
                }
            );
        }
    }

    #[test]
    fn independent_channels_use_distinct_rates_strengths_and_scales() {
        let settings = RgbChannelTransformSettings {
            mode: RgbChannelTransformMode::Independent,
            direction_turns: [0.0, 0.25, 0.5],
            direction_rate_hz: [0.0, 0.25, -0.25],
            displacement_strength_uv: [0.01, 0.02, 0.03],
            image_scale: [1.0, 1.25, 0.8],
            coverage_scale: [1.0, 0.9, 0.8],
            ..RgbChannelTransformSettings::default()
        }
        .normalized();
        let red = settings.channel_sample(0, [0.4, 0.4], 0.5, 1.0);
        let green = settings.channel_sample(1, [0.4, 0.4], 0.5, 1.0);
        let blue = settings.channel_sample(2, [0.4, 0.4], 0.5, 1.0);
        assert_ne!(red.uv, green.uv);
        assert_ne!(green.uv, blue.uv);
        assert_eq!(red.coverage, 1.0);
        assert!(green.coverage <= 1.0);
        assert!(blue.coverage <= green.coverage);
    }

    #[test]
    fn linked_mode_uses_red_as_the_single_parameter_authority() {
        let settings = RgbChannelTransformSettings {
            mode: RgbChannelTransformMode::Linked,
            direction_turns: [0.125, 0.5, 0.75],
            direction_rate_hz: [0.2, 0.4, 0.6],
            displacement_strength_uv: [0.02, 0.04, 0.06],
            image_scale: [1.1, 1.2, 1.3],
            coverage_scale: [0.9, 0.8, 0.7],
            ..RgbChannelTransformSettings::default()
        }
        .normalized();
        assert_eq!(settings.direction_turns, [0.125; 3]);
        assert_eq!(settings.direction_rate_hz, [0.2; 3]);
        assert_eq!(settings.displacement_strength_uv, [0.02; 3]);
        assert_eq!(settings.image_scale, [1.1; 3]);
        assert_eq!(settings.coverage_scale, [0.9; 3]);
    }

    #[test]
    fn damaged_values_are_finite_and_bounded() {
        let settings = RgbChannelTransformSettings {
            mode: RgbChannelTransformMode::Independent,
            direction_turns: [f32::NAN, -1.25, 2.5],
            direction_rate_hz: [f32::INFINITY, -9.0, 9.0],
            displacement_strength_uv: [-1.0, 1.0, f32::NAN],
            image_scale: [0.0, 9.0, f32::NAN],
            coverage_scale: [0.0, 9.0, f32::NAN],
            ..RgbChannelTransformSettings::default()
        }
        .normalized();
        assert_eq!(settings.direction_turns, [0.0, 0.75, 0.5]);
        assert_eq!(settings.direction_rate_hz, [0.0, -2.0, 2.0]);
        assert_eq!(settings.displacement_strength_uv, [0.0, 0.08, 0.0]);
        assert_eq!(settings.image_scale, [0.5, 2.0, 1.0]);
        assert_eq!(settings.coverage_scale, [0.5, 1.0, 1.0]);
        assert_eq!(settings.direction_noise_amount_turns, 0.0);
        assert_eq!(settings.direction_noise_rate_hz, 0.1);
        assert_eq!(std::mem::size_of::<RgbChannelTransformUniform>(), 96);
    }

    #[test]
    fn temporal_noise_is_deterministic_continuous_and_bounded() {
        let amount = 0.125;
        let first = smooth_direction_noise_turns(12.75, 0.4, amount, 0.381_966_02);
        let repeated = smooth_direction_noise_turns(12.75, 0.4, amount, 0.381_966_02);
        assert_eq!(first, repeated);
        assert!(first.abs() <= amount);
        let before_boundary = smooth_direction_noise_turns(2.499_99, 0.4, amount, 0.0);
        let after_boundary = smooth_direction_noise_turns(2.500_01, 0.4, amount, 0.0);
        assert!((before_boundary - after_boundary).abs() < 0.0001);
    }

    #[test]
    fn zero_noise_preserves_the_exact_uniform() {
        let settings = RgbChannelTransformSettings {
            mode: RgbChannelTransformMode::Independent,
            direction_turns: [0.1, 0.2, 0.3],
            direction_noise_amount_turns: 0.0,
            direction_noise_rate_hz: 1.0,
            ..RgbChannelTransformSettings::default()
        }
        .normalized();
        assert_eq!(
            settings.uniform_at_elapsed_seconds(123.0),
            settings.uniform()
        );
    }

    #[test]
    fn linked_mode_uses_one_noise_sample_and_independent_mode_uses_seeded_samples() {
        let linked = RgbChannelTransformSettings {
            mode: RgbChannelTransformMode::Linked,
            direction_turns: [0.25; RGB_CHANNEL_COUNT],
            direction_noise_amount_turns: 0.1,
            direction_noise_rate_hz: 0.4,
            ..RgbChannelTransformSettings::default()
        }
        .normalized()
        .uniform_at_elapsed_seconds(3.5);
        assert_eq!(linked.direction_turns[0], linked.direction_turns[1]);
        assert_eq!(linked.direction_turns[1], linked.direction_turns[2]);

        let independent = RgbChannelTransformSettings {
            mode: RgbChannelTransformMode::Independent,
            direction_turns: [0.25; RGB_CHANNEL_COUNT],
            direction_noise_amount_turns: 0.1,
            direction_noise_rate_hz: 0.4,
            ..RgbChannelTransformSettings::default()
        }
        .normalized()
        .uniform_at_elapsed_seconds(3.5);
        assert_ne!(
            independent.direction_turns[0],
            independent.direction_turns[1]
        );
        assert_ne!(
            independent.direction_turns[1],
            independent.direction_turns[2]
        );
    }

    #[test]
    fn channel_sample_uses_the_same_temporal_direction_as_the_uploaded_uniform() {
        let elapsed_seconds = 3.5;
        let settings = RgbChannelTransformSettings {
            mode: RgbChannelTransformMode::Independent,
            direction_turns: [0.25; RGB_CHANNEL_COUNT],
            direction_rate_hz: [0.2; RGB_CHANNEL_COUNT],
            direction_noise_amount_turns: 0.1,
            direction_noise_rate_hz: 0.4,
            displacement_strength_uv: [0.02; RGB_CHANNEL_COUNT],
            ..RgbChannelTransformSettings::default()
        }
        .normalized();
        let uploaded_turns = settings
            .uniform_at_elapsed_seconds(elapsed_seconds)
            .direction_turns[0];
        let angle = std::f32::consts::TAU
            * (uploaded_turns + elapsed_seconds * settings.direction_rate_hz[0]);
        let expected_uv = [0.4 + angle.cos() * 0.01, 0.4 + angle.sin() * 0.01];
        let sample = settings.channel_sample(0, [0.4, 0.4], 0.5, elapsed_seconds);
        assert!((sample.uv[0] - expected_uv[0]).abs() < 0.000_001);
        assert!((sample.uv[1] - expected_uv[1]).abs() < 0.000_001);
    }

    #[test]
    fn edge_policies_remain_inside_sample_domain() {
        for mode in [
            RgbChannelEdgeMode::Clamp,
            RgbChannelEdgeMode::Mirror,
            RgbChannelEdgeMode::Fade,
        ] {
            let (uv, alpha) = apply_edge_mode([-0.3, 1.4], mode);
            assert!((0.001..=0.999).contains(&uv[0]));
            assert!((0.001..=0.999).contains(&uv[1]));
            assert!((0.0..=1.0).contains(&alpha));
        }
    }
}
