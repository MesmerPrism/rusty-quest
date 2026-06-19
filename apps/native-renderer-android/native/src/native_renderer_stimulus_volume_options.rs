//! Stimulus-volume property settings for the native renderer.
//!
//! This module owns the parsed runtime settings for the GPU stimulus-volume
//! route while `native_renderer_options` remains the caller-facing facade.

use crate::{
    native_renderer_properties::{
        PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION, PROP_STIMULUS_VOLUME_COMPOSITION,
        PROP_STIMULUS_VOLUME_ENABLED, PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING,
        PROP_STIMULUS_VOLUME_PATTERN_FAMILY, PROP_STIMULUS_VOLUME_PROFILE,
        PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED, PROP_STIMULUS_VOLUME_RANDOMIZE_MAX_HZ,
        PROP_STIMULUS_VOLUME_RANDOMIZE_MIN_HZ, PROP_STIMULUS_VOLUME_RAYMARCH_SAMPLES,
        PROP_STIMULUS_VOLUME_RENDER_TARGET, PROP_STIMULUS_VOLUME_SAFETY_ACK,
    },
    native_renderer_property_values::{
        bool_value, f32_clamped_value, normalized_property, u32_value,
    },
    native_renderer_visual_options::NativeRendererRenderMode,
};

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeStimulusVolumeSettings {
    pub(crate) enabled: bool,
    pub(crate) profile: NativeStimulusVolumeProfile,
    pub(crate) composition: NativeStimulusVolumeCompositionMode,
    pub(crate) render_target: NativeStimulusVolumeRenderTarget,
    pub(crate) color_mode: NativeStimulusVolumeColorMode,
    pub(crate) pattern_family: NativeStimulusVolumePatternFamily,
    pub(crate) raymarch_samples: u32,
    pub(crate) central_fov_fraction: f32,
    pub(crate) gradient_smoothing: f32,
    pub(crate) randomize_enabled: bool,
    pub(crate) randomize_min_hz: f32,
    pub(crate) randomize_max_hz: f32,
    pub(crate) safety_acknowledged: bool,
    pub(crate) safety_acknowledgement_required: bool,
    pub(crate) allow_autostart: bool,
    pub(crate) black_lead_in_seconds: f32,
    pub(crate) max_duration_seconds: f32,
    pub(crate) max_cycle_hz: f32,
}

impl NativeStimulusVolumeSettings {
    pub(crate) fn from_property_lookup(
        mut lookup: impl FnMut(&str) -> Option<String>,
        render_mode: NativeRendererRenderMode,
    ) -> Self {
        let profile =
            NativeStimulusVolumeProfile::from_property(lookup(PROP_STIMULUS_VOLUME_PROFILE));
        let max_cycle_hz = 40.0;
        let requested_min_hz = f32_clamped_value(
            lookup(PROP_STIMULUS_VOLUME_RANDOMIZE_MIN_HZ),
            3.0,
            3.0,
            max_cycle_hz,
        );
        let requested_max_hz = f32_clamped_value(
            lookup(PROP_STIMULUS_VOLUME_RANDOMIZE_MAX_HZ),
            40.0,
            requested_min_hz.max(0.001),
            max_cycle_hz,
        );
        let (randomize_min_hz, randomize_max_hz) = if requested_min_hz <= requested_max_hz {
            (requested_min_hz, requested_max_hz)
        } else {
            (3.0, 40.0)
        };
        Self {
            enabled: bool_value(
                lookup(PROP_STIMULUS_VOLUME_ENABLED),
                render_mode.uses_stimulus_volume(),
            ),
            profile,
            composition: NativeStimulusVolumeCompositionMode::from_property(lookup(
                PROP_STIMULUS_VOLUME_COMPOSITION,
            )),
            render_target: NativeStimulusVolumeRenderTarget::from_property(lookup(
                PROP_STIMULUS_VOLUME_RENDER_TARGET,
            )),
            color_mode: NativeStimulusVolumeColorMode::DepthRamp,
            pattern_family: NativeStimulusVolumePatternFamily::from_property(lookup(
                PROP_STIMULUS_VOLUME_PATTERN_FAMILY,
            )),
            raymarch_samples: u32_value(lookup(PROP_STIMULUS_VOLUME_RAYMARCH_SAMPLES), 6, 1, 48),
            central_fov_fraction: f32_clamped_value(
                lookup(PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION),
                0.78,
                0.45,
                1.0,
            ),
            gradient_smoothing: f32_clamped_value(
                lookup(PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING),
                0.65,
                0.0,
                1.0,
            ),
            randomize_enabled: bool_value(lookup(PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED), true),
            randomize_min_hz,
            randomize_max_hz,
            safety_acknowledged: bool_value(lookup(PROP_STIMULUS_VOLUME_SAFETY_ACK), false),
            safety_acknowledgement_required: true,
            allow_autostart: false,
            black_lead_in_seconds: 1.0,
            max_duration_seconds: 30.0,
            max_cycle_hz,
        }
    }

    pub(crate) fn active(self) -> bool {
        self.enabled && (!self.safety_acknowledgement_required || self.safety_acknowledged)
    }

    pub(crate) fn volume_only(self) -> bool {
        true
    }

    pub(crate) fn noise_model(self) -> &'static str {
        "value-fbm-mobile-2oct-v1"
    }

    pub(crate) fn oscillator_model(self) -> &'static str {
        "trevor-vocabulary-volume-fields-v1"
    }

    pub(crate) fn pattern_vocabulary(self) -> &'static str {
        "trevor-hewitt-inspired-browser-portable-v1"
    }

    pub(crate) fn emission_gain(self) -> f32 {
        2.65
    }

    pub(crate) fn black_threshold(self) -> f32 {
        0.24
    }

    pub(crate) fn depth_color_mix(self) -> f32 {
        1.0
    }

    pub(crate) fn depth_contrast(self) -> f32 {
        0.9
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "stimulusVolumeEnabled={} stimulusVolumeActive={} stimulusVolumeProfile={} renderPath=native-vulkan-stimulus-volume makepadRuntime=false hostessRuntime=false volumeOnly={} volumeColorMode={} volumeCompositing={} stimulusVolumeRenderTarget={} volumeResolutionTier={} volumeCentralFovFraction={:.2} volumeGradientSmoothing={:.2} volumeRaymarchSamples={} volumePatternVocabulary={} volumePatternFamily={} volumeNoiseModel={} volumeOscillatorModel={} volumeEmissionGain={:.2} volumeBlackThreshold={:.2} volumeDepthColorMix={:.1} volumeDepthContrast={:.1} stimulusRandomizeEnabled={} randomizeHzRange={:.3}-{:.3} stimulusSafetyClass=PhotosensitiveRisk stimulusSafetyAcknowledgementRequired={} stimulusSafetyAcknowledged={} stimulusAllowAutostart={} stimulusBlackLeadInSeconds={:.3} stimulusMaxDurationSeconds={:.3} stimulusMaxCycleHz={:.3} stimulusSafetyGate={}",
            self.enabled,
            self.active(),
            self.profile.marker_value(),
            self.volume_only(),
            self.color_mode.marker_value(),
            self.composition.marker_value(),
            self.render_target.marker_value(),
            self.render_target.resolution_tier_marker(),
            self.central_fov_fraction,
            self.gradient_smoothing,
            self.raymarch_samples,
            self.pattern_vocabulary(),
            self.pattern_family.marker_value(),
            self.noise_model(),
            self.oscillator_model(),
            self.emission_gain(),
            self.black_threshold(),
            self.depth_color_mix(),
            self.depth_contrast(),
            self.randomize_enabled,
            self.randomize_min_hz,
            self.randomize_max_hz,
            self.safety_acknowledgement_required,
            self.safety_acknowledged,
            self.allow_autostart,
            self.black_lead_in_seconds,
            self.max_duration_seconds,
            self.max_cycle_hz,
            if self.active() {
                "acknowledged-active"
            } else if self.enabled {
                "render-black-until-safety-ack"
            } else {
                "disabled"
            },
        )
    }
}

impl Default for NativeStimulusVolumeSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            profile: NativeStimulusVolumeProfile::VolumeOnlyBrightInterference,
            composition: NativeStimulusVolumeCompositionMode::OpaqueBlackProjection,
            render_target: NativeStimulusVolumeRenderTarget::Rgba16f512Stereo,
            color_mode: NativeStimulusVolumeColorMode::DepthRamp,
            pattern_family: NativeStimulusVolumePatternFamily::RandomizedTrevorVocabulary,
            raymarch_samples: 6,
            central_fov_fraction: 0.78,
            gradient_smoothing: 0.65,
            randomize_enabled: true,
            randomize_min_hz: 3.0,
            randomize_max_hz: 40.0,
            safety_acknowledged: false,
            safety_acknowledgement_required: true,
            allow_autostart: false,
            black_lead_in_seconds: 1.0,
            max_duration_seconds: 30.0,
            max_cycle_hz: 40.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeStimulusVolumePatternFamily {
    RandomizedTrevorVocabulary,
    TrevorMix,
    Stripes,
    Ripples,
    Rays,
    Checker,
    Spiral,
    NoiseField,
}

impl NativeStimulusVolumePatternFamily {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "trevor-mix" | "mixed" | "interference-mix" => Self::TrevorMix,
            "stripes" | "stripe" => Self::Stripes,
            "ripples" | "ripple" | "rings" => Self::Ripples,
            "rays" | "ray" | "radial-rays" => Self::Rays,
            "checker" | "checkerboard" | "checkers" => Self::Checker,
            "spiral" | "spirals" => Self::Spiral,
            "noise-field" | "noise" | "blobs" => Self::NoiseField,
            "randomized-trevor-vocabulary" | "randomized" | "random" | "trevor-vocabulary" => {
                Self::RandomizedTrevorVocabulary
            }
            _ => Self::RandomizedTrevorVocabulary,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::RandomizedTrevorVocabulary => "randomized-trevor-vocabulary",
            Self::TrevorMix => "trevor-mix",
            Self::Stripes => "stripes",
            Self::Ripples => "ripples",
            Self::Rays => "rays",
            Self::Checker => "checker",
            Self::Spiral => "spiral",
            Self::NoiseField => "noise-field",
        }
    }

    pub(crate) fn runtime_initial_family(self) -> Self {
        match self {
            Self::RandomizedTrevorVocabulary => Self::TrevorMix,
            family => family,
        }
    }

    pub(crate) fn randomizes_family(self) -> bool {
        matches!(self, Self::RandomizedTrevorVocabulary)
    }

    pub(crate) fn from_random_unit(value: f32) -> Self {
        match (value.clamp(0.0, 0.999_999) * 7.0) as u32 {
            0 => Self::TrevorMix,
            1 => Self::Stripes,
            2 => Self::Ripples,
            3 => Self::Rays,
            4 => Self::Checker,
            5 => Self::Spiral,
            _ => Self::NoiseField,
        }
    }

    pub(crate) fn shader_code(self) -> f32 {
        match self.runtime_initial_family() {
            Self::RandomizedTrevorVocabulary | Self::TrevorMix => 0.0,
            Self::Stripes => 1.0,
            Self::Ripples => 2.0,
            Self::Rays => 3.0,
            Self::Checker => 4.0,
            Self::Spiral => 5.0,
            Self::NoiseField => 6.0,
        }
    }
}

impl Default for NativeStimulusVolumePatternFamily {
    fn default() -> Self {
        Self::RandomizedTrevorVocabulary
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeStimulusVolumeProfile {
    VolumeOnlyBrightInterference,
}

impl NativeStimulusVolumeProfile {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "volume-only-bright-interference"
            | "stimulus.profile.volume-only-bright-interference"
            | "stimulus.profile.volume_only_bright_interference" => {
                Self::VolumeOnlyBrightInterference
            }
            _ => Self::VolumeOnlyBrightInterference,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::VolumeOnlyBrightInterference => "volume-only-bright-interference",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeStimulusVolumeCompositionMode {
    OpaqueBlackProjection,
    AlphaOverNativePassthrough,
}

impl NativeStimulusVolumeCompositionMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "alpha-over-native-passthrough" | "passthrough-alpha" => {
                Self::AlphaOverNativePassthrough
            }
            _ => Self::OpaqueBlackProjection,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::OpaqueBlackProjection => "opaque-black-projection",
            Self::AlphaOverNativePassthrough => "alpha-over-native-passthrough",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeStimulusVolumeColorMode {
    DepthRamp,
}

impl NativeStimulusVolumeColorMode {
    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::DepthRamp => "DepthRamp",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeStimulusVolumeRenderTarget {
    Rgba16f512Stereo,
    Rgba8Unorm512Stereo,
    Rgba16f768Stereo,
    Rgba16f1024Stereo,
}

impl NativeStimulusVolumeRenderTarget {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "512x512x2-rgba8" | "512x512x2-rgba8-unorm" | "rgba8" | "rgba8-unorm" => {
                Self::Rgba8Unorm512Stereo
            }
            "768x768x2-rgba16f" | "768x768" | "rgba16f-768" => Self::Rgba16f768Stereo,
            "1024x1024x2-rgba16f" | "1024x1024" | "rgba16f-1024" | "limit-1024" => {
                Self::Rgba16f1024Stereo
            }
            _ => Self::Rgba16f512Stereo,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Rgba16f512Stereo => "512x512x2-rgba16f",
            Self::Rgba8Unorm512Stereo => "512x512x2-rgba8-unorm",
            Self::Rgba16f768Stereo => "768x768x2-rgba16f",
            Self::Rgba16f1024Stereo => "1024x1024x2-rgba16f",
        }
    }

    pub(crate) fn extent(self) -> [u32; 2] {
        match self {
            Self::Rgba16f512Stereo | Self::Rgba8Unorm512Stereo => [512, 512],
            Self::Rgba16f768Stereo => [768, 768],
            Self::Rgba16f1024Stereo => [1024, 1024],
        }
    }

    pub(crate) fn requested_format_code(self) -> f32 {
        match self {
            Self::Rgba16f512Stereo | Self::Rgba16f768Stereo | Self::Rgba16f1024Stereo => 16.0,
            Self::Rgba8Unorm512Stereo => 8.0,
        }
    }

    pub(crate) fn resolution_tier_marker(self) -> &'static str {
        match self {
            Self::Rgba16f512Stereo | Self::Rgba8Unorm512Stereo => "baseline-512",
            Self::Rgba16f768Stereo => "balanced-768",
            Self::Rgba16f1024Stereo => "limit-1024",
        }
    }
}
