//! Low-rate Meta passthrough compositor style settings.
//!
//! The OpenXR passthrough layer remains the runtime owner. These properties are
//! startup-effective inputs to one XR_FB_passthrough style call after the layer
//! is created.

use std::f32::consts::TAU;

use crate::{
    native_renderer_properties::{
        PROP_PASSTHROUGH_STYLE_BRIGHTNESS, PROP_PASSTHROUGH_STYLE_COLOR_AMPLITUDE,
        PROP_PASSTHROUGH_STYLE_COLOR_PHASE, PROP_PASSTHROUGH_STYLE_CONTRAST,
        PROP_PASSTHROUGH_STYLE_EDGE_COLOR_A, PROP_PASSTHROUGH_STYLE_EDGE_COLOR_B,
        PROP_PASSTHROUGH_STYLE_EDGE_COLOR_G, PROP_PASSTHROUGH_STYLE_EDGE_COLOR_R,
        PROP_PASSTHROUGH_STYLE_MODE, PROP_PASSTHROUGH_STYLE_OPACITY,
        PROP_PASSTHROUGH_STYLE_SATURATION,
    },
    native_renderer_property_values::{f32_clamped_value, f32_value, normalized_property},
};

pub(crate) const PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES: usize = 256;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativePassthroughStyleMode {
    Disabled,
    EdgeAndOpacity,
    BrightnessContrastSaturation,
    MonoToRgba,
}

impl NativePassthroughStyleMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "edge" | "edge-and-opacity" | "opacity-edge" | "base" => Self::EdgeAndOpacity,
            "bcs" | "brightness-contrast-saturation" | "brightness_contrast_saturation" => {
                Self::BrightnessContrastSaturation
            }
            "mono-to-rgba" | "mono-rgba" | "color-map" | "color-map-mono-to-rgba" => {
                Self::MonoToRgba
            }
            _ => Self::Disabled,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::EdgeAndOpacity => "edge-and-opacity",
            Self::BrightnessContrastSaturation => "brightness-contrast-saturation",
            Self::MonoToRgba => "mono-to-rgba",
        }
    }

    pub(crate) fn extension_chain_marker(self) -> &'static str {
        match self {
            Self::Disabled => "none",
            Self::EdgeAndOpacity => "PassthroughStyleFB",
            Self::BrightnessContrastSaturation => "PassthroughBrightnessContrastSaturationFB",
            Self::MonoToRgba => "PassthroughColorMapMonoToRgbaFB",
        }
    }

    pub(crate) fn requests_style_call(self) -> bool {
        !matches!(self, Self::Disabled)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativePassthroughStyleSettings {
    pub(crate) mode: NativePassthroughStyleMode,
    pub(crate) opacity: f32,
    pub(crate) edge_color_rgba: [f32; 4],
    pub(crate) brightness: f32,
    pub(crate) contrast: f32,
    pub(crate) saturation: f32,
    pub(crate) color_phase: f32,
    pub(crate) color_amplitude: f32,
}

impl NativePassthroughStyleSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let mode = NativePassthroughStyleMode::from_property(lookup(PROP_PASSTHROUGH_STYLE_MODE));
        let color_phase =
            normalized_unit_phase(f32_value(lookup(PROP_PASSTHROUGH_STYLE_COLOR_PHASE), 0.0));

        Self {
            mode,
            opacity: f32_clamped_value(lookup(PROP_PASSTHROUGH_STYLE_OPACITY), 1.0, 0.0, 1.0),
            edge_color_rgba: [
                f32_clamped_value(lookup(PROP_PASSTHROUGH_STYLE_EDGE_COLOR_R), 0.0, 0.0, 1.0),
                f32_clamped_value(lookup(PROP_PASSTHROUGH_STYLE_EDGE_COLOR_G), 0.0, 0.0, 1.0),
                f32_clamped_value(lookup(PROP_PASSTHROUGH_STYLE_EDGE_COLOR_B), 0.0, 0.0, 1.0),
                f32_clamped_value(lookup(PROP_PASSTHROUGH_STYLE_EDGE_COLOR_A), 0.0, 0.0, 1.0),
            ],
            brightness: f32_clamped_value(
                lookup(PROP_PASSTHROUGH_STYLE_BRIGHTNESS),
                0.0,
                -1.0,
                1.0,
            ),
            contrast: f32_clamped_value(lookup(PROP_PASSTHROUGH_STYLE_CONTRAST), 1.0, 0.0, 4.0),
            saturation: f32_clamped_value(lookup(PROP_PASSTHROUGH_STYLE_SATURATION), 1.0, 0.0, 4.0),
            color_phase,
            color_amplitude: f32_clamped_value(
                lookup(PROP_PASSTHROUGH_STYLE_COLOR_AMPLITUDE),
                0.85,
                0.0,
                1.0,
            ),
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "passthroughStyleMode={} passthroughStyleEnabled={} passthroughStyleExtensionChain={} passthroughStyleSingleExtensionChain=true passthroughOpacity={:.3} passthroughEdgeColorRgba={:.3},{:.3},{:.3},{:.3} passthroughBrightness={:.3} passthroughContrast={:.3} passthroughSaturation={:.3} passthroughColorPhase={:.3} passthroughColorAmplitude={:.3} passthroughColorMapEntries={}",
            self.mode.marker_value(),
            self.mode.requests_style_call(),
            self.mode.extension_chain_marker(),
            self.opacity,
            self.edge_color_rgba[0],
            self.edge_color_rgba[1],
            self.edge_color_rgba[2],
            self.edge_color_rgba[3],
            self.brightness,
            self.contrast,
            self.saturation,
            self.color_phase,
            self.color_amplitude,
            PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES,
        )
    }

    pub(crate) fn mono_to_rgba_color_map(self) -> [[f32; 4]; PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES] {
        let mut map = [[0.0; 4]; PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES];
        for (index, color) in map.iter_mut().enumerate() {
            let luma = index as f32 / (PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES as f32 - 1.0);
            let phase = luma + self.color_phase;
            let wave_r = unit_sine(phase);
            let wave_g = unit_sine(phase + 1.0 / 3.0);
            let wave_b = unit_sine(phase + 2.0 / 3.0);
            let amplitude = self.color_amplitude;
            *color = [
                luma * (1.0 - amplitude) + wave_r * amplitude,
                luma * (1.0 - amplitude) + wave_g * amplitude,
                luma * (1.0 - amplitude) + wave_b * amplitude,
                1.0,
            ];
        }
        map
    }
}

impl Default for NativePassthroughStyleSettings {
    fn default() -> Self {
        Self {
            mode: NativePassthroughStyleMode::Disabled,
            opacity: 1.0,
            edge_color_rgba: [0.0, 0.0, 0.0, 0.0],
            brightness: 0.0,
            contrast: 1.0,
            saturation: 1.0,
            color_phase: 0.0,
            color_amplitude: 0.85,
        }
    }
}

fn normalized_unit_phase(value: f32) -> f32 {
    if value.is_finite() {
        value.rem_euclid(1.0)
    } else {
        0.0
    }
}

fn unit_sine(phase: f32) -> f32 {
    (0.5 + 0.5 * (phase * TAU).sin()).clamp(0.0, 1.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn passthrough_style_mode_aliases_parse() {
        assert_eq!(
            NativePassthroughStyleMode::from_property(Some("color-map".to_owned())),
            NativePassthroughStyleMode::MonoToRgba
        );
        assert_eq!(
            NativePassthroughStyleMode::from_property(Some("bcs".to_owned())),
            NativePassthroughStyleMode::BrightnessContrastSaturation
        );
        assert_eq!(
            NativePassthroughStyleMode::from_property(Some("edge".to_owned())),
            NativePassthroughStyleMode::EdgeAndOpacity
        );
        assert_eq!(
            NativePassthroughStyleMode::from_property(Some("off".to_owned())),
            NativePassthroughStyleMode::Disabled
        );
    }

    #[test]
    fn passthrough_style_values_clamp_and_wrap() {
        let settings = NativePassthroughStyleSettings::from_property_lookup(|name| match name {
            PROP_PASSTHROUGH_STYLE_MODE => Some("mono-to-rgba".to_owned()),
            PROP_PASSTHROUGH_STYLE_OPACITY => Some("2".to_owned()),
            PROP_PASSTHROUGH_STYLE_EDGE_COLOR_R => Some("-1".to_owned()),
            PROP_PASSTHROUGH_STYLE_EDGE_COLOR_G => Some("0.4".to_owned()),
            PROP_PASSTHROUGH_STYLE_EDGE_COLOR_B => Some("4".to_owned()),
            PROP_PASSTHROUGH_STYLE_EDGE_COLOR_A => Some("0.25".to_owned()),
            PROP_PASSTHROUGH_STYLE_BRIGHTNESS => Some("-4".to_owned()),
            PROP_PASSTHROUGH_STYLE_CONTRAST => Some("5".to_owned()),
            PROP_PASSTHROUGH_STYLE_SATURATION => Some("3".to_owned()),
            PROP_PASSTHROUGH_STYLE_COLOR_PHASE => Some("1.25".to_owned()),
            PROP_PASSTHROUGH_STYLE_COLOR_AMPLITUDE => Some("2".to_owned()),
            _ => None,
        });

        assert_eq!(settings.mode, NativePassthroughStyleMode::MonoToRgba);
        assert_eq!(settings.opacity, 1.0);
        assert_eq!(settings.edge_color_rgba, [0.0, 0.4, 1.0, 0.25]);
        assert_eq!(settings.brightness, -1.0);
        assert_eq!(settings.contrast, 4.0);
        assert_eq!(settings.saturation, 3.0);
        assert!((settings.color_phase - 0.25).abs() < f32::EPSILON);
        assert_eq!(settings.color_amplitude, 1.0);
    }

    #[test]
    fn passthrough_color_map_is_full_size_rgba() {
        let settings = NativePassthroughStyleSettings {
            mode: NativePassthroughStyleMode::MonoToRgba,
            color_phase: 0.18,
            color_amplitude: 0.85,
            ..Default::default()
        };
        let map = settings.mono_to_rgba_color_map();

        assert_eq!(map.len(), PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES);
        assert_eq!(map[0][3], 1.0);
        assert_eq!(map[255][3], 1.0);
        assert_ne!(map[0][0], map[128][0]);
    }
}
