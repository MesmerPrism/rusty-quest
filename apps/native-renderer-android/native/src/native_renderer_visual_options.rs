//! Render-route, hand-visual, and private-layer option types for the native renderer.

use crate::{
    native_renderer_properties::{
        PROP_PRIVATE_LAYER_EFFECT0, PROP_PRIVATE_LAYER_EFFECT1, PROP_PRIVATE_LAYER_EFFECT2,
        PROP_PRIVATE_LAYER_EFFECT3, PROP_PRIVATE_LAYER_ENABLED, PROP_PRIVATE_LAYER_OVERRIDE,
        PROP_PRIVATE_LAYER_SECONDS,
    },
    native_renderer_property_values::{bool_value, f32_clamped_value},
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeRendererRenderMode {
    CustomStereoProjection,
    NativePassthroughStyleOnly,
    NativePassthroughMediaOnly,
    NativePassthroughGraftOnly,
    NativePassthroughStimulusVolume,
    SolidBlackHandsAndGrafts,
    SolidBlackOpenXrHandsAnchorParticles,
    SolidBlackPrivateParticles,
    SolidBlackStimulusVolume,
}

impl NativeRendererRenderMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        let normalized = value
            .as_deref()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        match normalized.as_str() {
            "native-passthrough-style-only"
            | "passthrough-style-only"
            | "meta-passthrough-style-only"
            | "native-passthrough-compositor-style" => Self::NativePassthroughStyleOnly,
            "native-passthrough-media-only"
            | "passthrough-media-only"
            | "native-passthrough-overlay-only"
            | "passthrough-overlay-only"
            | "native-passthrough-display-composite" => Self::NativePassthroughMediaOnly,
            "native-passthrough-graft-only"
            | "passthrough-graft-only"
            | "graft-only"
            | "native-passthrough" => Self::NativePassthroughGraftOnly,
            "native-passthrough-stimulus-volume"
            | "passthrough-stimulus-volume"
            | "native-stimulus-volume" => Self::NativePassthroughStimulusVolume,
            "solid-black-hands-and-grafts"
            | "black-hands-and-grafts"
            | "solid-black"
            | "black-background-hands-and-grafts" => Self::SolidBlackHandsAndGrafts,
            "solid-black-openxr-hands-anchor-particles"
            | "solid-black-openxr-hands"
            | "solid-black-default-hands-anchor-particles"
            | "solid-black-default-hands"
            | "black-background-openxr-hands-anchor-particles" => {
                Self::SolidBlackOpenXrHandsAnchorParticles
            }
            "solid-black-private-particles"
            | "solid-black-private-particle"
            | "black-background-private-particles" => Self::SolidBlackPrivateParticles,
            "solid-black-stimulus-volume"
            | "black-stimulus-volume"
            | "opaque-black-stimulus-volume" => Self::SolidBlackStimulusVolume,
            _ => Self::CustomStereoProjection,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "custom-stereo-projection",
            Self::NativePassthroughStyleOnly => "native-passthrough-style-only",
            Self::NativePassthroughMediaOnly => "native-passthrough-media-only",
            Self::NativePassthroughGraftOnly => "native-passthrough-graft-only",
            Self::NativePassthroughStimulusVolume => "native-passthrough-stimulus-volume",
            Self::SolidBlackHandsAndGrafts => "solid-black-hands-and-grafts",
            Self::SolidBlackOpenXrHandsAnchorParticles => {
                "solid-black-openxr-hands-anchor-particles"
            }
            Self::SolidBlackPrivateParticles => "solid-black-private-particles",
            Self::SolidBlackStimulusVolume => "solid-black-stimulus-volume",
        }
    }

    pub(crate) fn uses_custom_stereo_projection(self) -> bool {
        matches!(self, Self::CustomStereoProjection)
    }

    pub(crate) fn uses_native_passthrough(self) -> bool {
        matches!(
            self,
            Self::NativePassthroughStyleOnly
                | Self::NativePassthroughMediaOnly
                | Self::NativePassthroughGraftOnly
                | Self::NativePassthroughStimulusVolume
        )
    }

    pub(crate) fn uses_solid_black_background(self) -> bool {
        matches!(
            self,
            Self::SolidBlackHandsAndGrafts
                | Self::SolidBlackOpenXrHandsAnchorParticles
                | Self::SolidBlackPrivateParticles
                | Self::SolidBlackStimulusVolume
        )
    }

    pub(crate) fn uses_stimulus_volume(self) -> bool {
        matches!(
            self,
            Self::NativePassthroughStimulusVolume | Self::SolidBlackStimulusVolume
        )
    }

    pub(crate) fn projection_layer_alpha_blend(self) -> bool {
        self.uses_native_passthrough() && !self.uses_stimulus_volume()
    }

    pub(crate) fn requests_openxr_default_hand_visual(self) -> bool {
        matches!(self, Self::SolidBlackOpenXrHandsAnchorParticles)
    }

    pub(crate) fn requests_private_particle_recenter_input(self) -> bool {
        matches!(self, Self::SolidBlackPrivateParticles)
    }

    pub(crate) fn forces_graft_copies(self) -> bool {
        matches!(
            self,
            Self::NativePassthroughGraftOnly | Self::SolidBlackHandsAndGrafts
        )
    }

    pub(crate) fn forces_real_hand_meshes(self) -> bool {
        matches!(self, Self::SolidBlackHandsAndGrafts)
    }

    pub(crate) fn camera_runtime_mode(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "camera2-hwb",
            Self::NativePassthroughStyleOnly => "skipped-native-passthrough-style-only",
            Self::NativePassthroughMediaOnly => "skipped-native-passthrough-media-only",
            Self::NativePassthroughGraftOnly => "skipped-native-passthrough",
            Self::NativePassthroughStimulusVolume => "skipped-native-passthrough-stimulus-volume",
            Self::SolidBlackHandsAndGrafts => "skipped-solid-black-hands-and-grafts",
            Self::SolidBlackOpenXrHandsAnchorParticles => {
                "skipped-solid-black-openxr-hands-anchor-particles"
            }
            Self::SolidBlackPrivateParticles => "skipped-solid-black-private-particles",
            Self::SolidBlackStimulusVolume => "skipped-solid-black-stimulus-volume",
        }
    }

    pub(crate) fn disabled_camera_projection_path(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "metadata-target-direct-hwb-fallback",
            Self::NativePassthroughStyleOnly => "disabled-native-passthrough-style-only",
            Self::NativePassthroughMediaOnly => "disabled-native-passthrough-media-only",
            Self::NativePassthroughGraftOnly => "disabled-native-passthrough-graft-only",
            Self::NativePassthroughStimulusVolume => "disabled-native-passthrough-stimulus-volume",
            Self::SolidBlackHandsAndGrafts => "disabled-solid-black-hands-and-grafts",
            Self::SolidBlackOpenXrHandsAnchorParticles => {
                "disabled-solid-black-openxr-hands-anchor-particles"
            }
            Self::SolidBlackPrivateParticles => "disabled-solid-black-private-particles",
            Self::SolidBlackStimulusVolume => "disabled-solid-black-stimulus-volume",
        }
    }

    pub(crate) fn allows_sdf_visual(self) -> bool {
        matches!(self, Self::CustomStereoProjection)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CompactHandInputSourceMode {
    Auto,
    Disabled,
    RecordedReplay,
    LiveMeta,
}

impl CompactHandInputSourceMode {
    pub(crate) fn from_property(value: Option<String>, replay_visual_proof_enabled: bool) -> Self {
        let normalized = value
            .as_deref()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        match normalized.as_str() {
            "0" | "false" | "no" | "off" | "disabled" | "none" => Self::Disabled,
            "recorded" | "recorded-replay" | "replay" => Self::RecordedReplay,
            "live" | "live-meta" | "openxr" | "live-meta-openxr-hand-tracking" => Self::LiveMeta,
            "auto" => Self::Auto,
            _ if replay_visual_proof_enabled => Self::RecordedReplay,
            _ => Self::Auto,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Disabled => "disabled",
            Self::RecordedReplay => "recorded-replay",
            Self::LiveMeta => "live-meta-openxr-hand-tracking",
        }
    }

    pub(crate) fn selects_live_frame(self) -> bool {
        matches!(self, Self::Auto | Self::LiveMeta)
    }

    pub(crate) fn allows_recorded_fallback(self) -> bool {
        matches!(self, Self::Auto | Self::RecordedReplay | Self::LiveMeta)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct HandMeshVisualDiagnosticSettings {
    pub(crate) enabled: bool,
    pub(crate) offset_uv: [f32; 2],
    pub(crate) alpha: f32,
}

impl HandMeshVisualDiagnosticSettings {
    pub(crate) fn new(enabled: bool, offset_uv: [f32; 2], alpha: f32) -> Self {
        Self {
            enabled,
            offset_uv: [
                offset_uv[0].clamp(-0.45, 0.45),
                offset_uv[1].clamp(-0.45, 0.45),
            ],
            alpha: alpha.clamp(0.20, 1.0),
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "handMeshVisualDiagnosticEnabled={} handMeshVisualDiagnosticOffsetUv={:.3},{:.3} handMeshVisualDiagnosticAlpha={:.2} handMeshVisualDiagnosticScale=1.35",
            self.enabled, self.offset_uv[0], self.offset_uv[1], self.alpha
        )
    }

    pub(crate) fn push_params(&self) -> [f32; 4] {
        if self.enabled {
            [self.offset_uv[0], self.offset_uv[1], self.alpha, 1.0]
        } else {
            [0.0, 0.0, self.alpha, 0.0]
        }
    }
}

impl Default for HandMeshVisualDiagnosticSettings {
    fn default() -> Self {
        Self::new(false, [0.0, 0.0], 0.78)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativePrivateLayerSettings {
    pub(crate) enabled: bool,
    pub(crate) layer_seconds: f32,
    pub(crate) layer_override: f32,
    pub(crate) effect: [f32; 4],
}

impl NativePrivateLayerSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            enabled: bool_value(lookup(PROP_PRIVATE_LAYER_ENABLED), false),
            layer_seconds: f32_clamped_value(lookup(PROP_PRIVATE_LAYER_SECONDS), 5.0, 0.25, 60.0),
            layer_override: f32_clamped_value(lookup(PROP_PRIVATE_LAYER_OVERRIDE), -1.0, -1.0, 6.0),
            effect: [
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT0), 1.0, 0.0, 4.0),
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT1), 1.0, 0.0, 4.0),
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT2), 0.0, 0.0, 0.25),
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT3), 1.0, 0.0, 4.0),
            ],
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "privateLayerEnabled={} privateLayerSeconds={:.3} privateLayerOverride={:.1} privateLayerEffect0={:.3} privateLayerEffect1={:.3} privateLayerEffect2={:.5} privateLayerEffect3={:.3}",
            self.enabled,
            self.layer_seconds,
            self.layer_override,
            self.effect[0],
            self.effect[1],
            self.effect[2],
            self.effect[3]
        )
    }
}

impl Default for NativePrivateLayerSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            layer_seconds: 5.0,
            layer_override: -1.0,
            effect: [1.0, 1.0, 0.0, 1.0],
        }
    }
}
