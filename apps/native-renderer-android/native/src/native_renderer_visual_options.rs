//! Render-route, hand-visual, and private-layer option types for the native renderer.

use crate::{
    native_renderer_properties::{
        PROP_HAND_MESH_VISUAL_MATERIAL_ALPHA, PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_B,
        PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_G, PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_R,
        PROP_HAND_MESH_VISUAL_MATERIAL_PROFILE, PROP_HAND_MESH_VISUAL_MATERIAL_RIM_STRENGTH,
        PROP_HAND_MESH_VISUAL_MESH_SOURCE, PROP_HAND_MESH_VISUAL_WIREFRAME_ENABLED,
        PROP_HAND_MESH_VISUAL_WIREFRAME_WIDTH_PX, PROP_PRIVATE_LAYER_EFFECT0,
        PROP_PRIVATE_LAYER_EFFECT1, PROP_PRIVATE_LAYER_EFFECT2, PROP_PRIVATE_LAYER_EFFECT3,
        PROP_PRIVATE_LAYER_ENABLED, PROP_PRIVATE_LAYER_OVERRIDE, PROP_PRIVATE_LAYER_SECONDS,
    },
    native_renderer_property_values::{bool_value, f32_clamped_value, normalized_property},
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum HandMeshVisualMeshSource {
    Auto,
    OpenXrFbMesh,
    CustomMesh,
}

impl HandMeshVisualMeshSource {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "openxr-fb-mesh"
            | "openxr-fb"
            | "xr-fb-mesh"
            | "xr-fb"
            | "fb"
            | "runtime-fb"
            | "meta-openxr-fb-hand-mesh" => Self::OpenXrFbMesh,
            "custom-mesh"
            | "custom"
            | "recorded-custom-mesh"
            | "embedded-custom-mesh"
            | "app-custom-mesh" => Self::CustomMesh,
            "auto" | "" => Self::Auto,
            _ => Self::Auto,
        }
    }

    pub(crate) fn from_property_lookup_or_default(
        mut lookup: impl FnMut(&str) -> Option<String>,
        default_value: Self,
    ) -> Self {
        match lookup(PROP_HAND_MESH_VISUAL_MESH_SOURCE) {
            Some(value) => Self::from_property(Some(value)),
            None => default_value,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::OpenXrFbMesh => "openxr-fb-mesh",
            Self::CustomMesh => "custom-mesh",
        }
    }
}

impl Default for HandMeshVisualMeshSource {
    fn default() -> Self {
        Self::Auto
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum HandMeshVisualMaterialProfile {
    UnityBasicReference,
    MintRim,
    FlatGray,
}

impl HandMeshVisualMaterialProfile {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "mint-rim" | "mint" | "native-mint" | "current-mint" => Self::MintRim,
            "flat-gray" | "flat-grey" | "gray" | "grey" | "legacy-gray" | "legacy-grey" => {
                Self::FlatGray
            }
            "unity-basic"
            | "unity-basic-reference"
            | "basic-hand-material"
            | "meta-basic-hand-material" => Self::UnityBasicReference,
            _ => Self::UnityBasicReference,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::UnityBasicReference => "unity-basic-reference",
            Self::MintRim => "mint-rim",
            Self::FlatGray => "flat-gray",
        }
    }

    fn defaults(self) -> ([f32; 3], f32, f32) {
        match self {
            Self::UnityBasicReference => ([0.78, 0.86, 0.83], 0.74, 0.20),
            Self::MintRim => ([0.70, 0.935, 0.87], 0.72, 0.32),
            Self::FlatGray => ([0.54, 0.54, 0.54], 1.0, 0.0),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct HandMeshVisualMaterialSettings {
    pub(crate) profile: HandMeshVisualMaterialProfile,
    pub(crate) base_color: [f32; 3],
    pub(crate) alpha: f32,
    pub(crate) rim_strength: f32,
    pub(crate) wireframe_enabled: bool,
    pub(crate) wireframe_width_px: f32,
}

impl HandMeshVisualMaterialSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let profile = HandMeshVisualMaterialProfile::from_property(lookup(
            PROP_HAND_MESH_VISUAL_MATERIAL_PROFILE,
        ));
        let (default_base_color, default_alpha, default_rim_strength) = profile.defaults();
        Self {
            profile,
            base_color: [
                f32_clamped_value(
                    lookup(PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_R),
                    default_base_color[0],
                    0.0,
                    1.0,
                ),
                f32_clamped_value(
                    lookup(PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_G),
                    default_base_color[1],
                    0.0,
                    1.0,
                ),
                f32_clamped_value(
                    lookup(PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_B),
                    default_base_color[2],
                    0.0,
                    1.0,
                ),
            ],
            alpha: f32_clamped_value(
                lookup(PROP_HAND_MESH_VISUAL_MATERIAL_ALPHA),
                default_alpha,
                0.05,
                1.0,
            ),
            rim_strength: f32_clamped_value(
                lookup(PROP_HAND_MESH_VISUAL_MATERIAL_RIM_STRENGTH),
                default_rim_strength,
                0.0,
                1.0,
            ),
            wireframe_enabled: bool_value(lookup(PROP_HAND_MESH_VISUAL_WIREFRAME_ENABLED), false),
            wireframe_width_px: f32_clamped_value(
                lookup(PROP_HAND_MESH_VISUAL_WIREFRAME_WIDTH_PX),
                1.35,
                0.50,
                4.00,
            ),
        }
    }

    pub(crate) fn with_hotload_wireframe_properties(
        self,
        mut lookup: impl FnMut(&str) -> Option<String>,
    ) -> Self {
        Self {
            wireframe_enabled: bool_value(
                lookup(PROP_HAND_MESH_VISUAL_WIREFRAME_ENABLED),
                self.wireframe_enabled,
            ),
            wireframe_width_px: f32_clamped_value(
                lookup(PROP_HAND_MESH_VISUAL_WIREFRAME_WIDTH_PX),
                self.wireframe_width_px,
                0.50,
                4.00,
            ),
            ..self
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "handMeshVisualMaterialProfile={} handMeshVisualMaterial=unity-basic-procedural-surface handMeshVisualMaterialSource=procedural-reference-not-unity-asset handMeshVisualUnityReference=BasicHandMaterial handMeshVisualUnityTextureReference=HandTracking_uvmap_2048 handMeshVisualTextureImported=false handMeshVisualMaterialBaseColor={:.3},{:.3},{:.3} handMeshVisualMaterialAlpha={:.2} handMeshVisualMaterialRimStrength={:.2} handMeshVisualFresnelApproximation=normal-facing-rim handMeshVisualDepthPolicy=overlay-no-depth handMeshVisualDepthTest=false handMeshVisualDepthWrite=false handMeshVisualWireframeAvailable=true handMeshVisualWireframeEnabled={} handMeshVisualWireframeWidthPx={:.2} handMeshVisualWireframeMode=shader-barycentric-triangle-edges handMeshVisualWireframeLinePath=fragment-derivative-anti-aliased handMeshVisualWireframeTopologySource=resident-selected-mesh-triangle-indices handMeshVisualWireframeHotloadProperties={},{} vulkanWideLinesRequired=false",
            self.profile.marker_value(),
            self.base_color[0],
            self.base_color[1],
            self.base_color[2],
            self.alpha,
            self.rim_strength,
            self.wireframe_enabled,
            self.wireframe_width_px,
            PROP_HAND_MESH_VISUAL_WIREFRAME_ENABLED,
            PROP_HAND_MESH_VISUAL_WIREFRAME_WIDTH_PX,
        )
    }

    pub(crate) fn push_material(self) -> [f32; 4] {
        [
            self.base_color[0],
            self.base_color[1],
            self.base_color[2],
            self.rim_strength,
        ]
    }

    pub(crate) fn push_params(
        self,
        diagnostic_settings: HandMeshVisualDiagnosticSettings,
    ) -> [f32; 4] {
        let mut params = diagnostic_settings.push_params();
        if !diagnostic_settings.enabled {
            params[2] = self.alpha;
        }
        params
    }

    pub(crate) fn push_wireframe_width_px(self) -> f32 {
        if self.wireframe_enabled {
            self.wireframe_width_px
        } else {
            0.0
        }
    }
}

impl Default for HandMeshVisualMaterialSettings {
    fn default() -> Self {
        let profile = HandMeshVisualMaterialProfile::UnityBasicReference;
        let (base_color, alpha, rim_strength) = profile.defaults();
        Self {
            profile,
            base_color,
            alpha,
            rim_strength,
            wireframe_enabled: false,
            wireframe_width_px: 1.35,
        }
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
