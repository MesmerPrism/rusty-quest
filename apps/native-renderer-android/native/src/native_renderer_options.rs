//! Runtime property options for the native renderer.
//!
//! This module keeps Android property parsing separate from the OpenXR/Vulkan
//! frame loop so replay-proof, live-hand, and SDF visual modes stay testable.

pub(crate) const PROP_ENABLE_SDF_VISUAL: &str =
    "debug.rustyquest.native_renderer.sdf.visual.enabled";
pub(crate) const PROP_RENDER_MODE: &str = "debug.rustyquest.native_renderer.render.mode";
pub(crate) const PROP_SDF_UPDATE_PERIOD_FRAMES: &str =
    "debug.rustyquest.native_renderer.sdf.update_period_frames";
pub(crate) const PROP_REPLAY_VISUAL_PROOF_ENABLED: &str =
    "debug.rustyquest.native_renderer.replay.visual_proof.enabled";
pub(crate) const PROP_HAND_MESH_INPUT_SOURCE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.input.source";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha";
pub(crate) const PROP_HAND_MESH_GRAFT_COPIES_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled";
pub(crate) const PROP_HAND_MESH_GRAFT_COPY_SCALE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale";
pub(crate) const PROP_HAND_MESH_REAL_HANDS_VISIBLE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.real_hands.visible";
pub(crate) const PROP_PROCESSING_LAYER: &str = "debug.rustyquest.native_renderer.processing.layer";
pub(crate) const PROP_PROJECTION_BORDER_POLICY: &str =
    "debug.rustyquest.native_renderer.projection.border.policy";
pub(crate) const PROP_PROJECTION_BORDER_OPACITY: &str =
    "debug.rustyquest.native_renderer.projection.border.opacity";
pub(crate) const PROP_PROJECTION_AREA_OPACITY: &str =
    "debug.rustyquest.native_renderer.projection.area.opacity";
pub(crate) const PROP_PERIPHERAL_STRETCH_CORE_SCALE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.core.scale";
pub(crate) const PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.edge.inset.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_MAX_INSET_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.max.inset.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_CURVE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.curve";
pub(crate) const PROP_PERIPHERAL_STRETCH_INNER_BLEND_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.inner.blend.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_BLEND_CURVE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.blend.curve";
pub(crate) const PROP_PERIPHERAL_STRETCH_BLEND_MODE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.blend.mode";
pub(crate) const PROP_PERIPHERAL_STRETCH_DEBUG: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.debug";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeRendererRenderMode {
    CustomStereoProjection,
    NativePassthroughGraftOnly,
    SolidBlackHandsAndGrafts,
}

impl NativeRendererRenderMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        let normalized = value
            .as_deref()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        match normalized.as_str() {
            "native-passthrough-graft-only"
            | "passthrough-graft-only"
            | "graft-only"
            | "native-passthrough" => Self::NativePassthroughGraftOnly,
            "solid-black-hands-and-grafts"
            | "black-hands-and-grafts"
            | "solid-black"
            | "black-background-hands-and-grafts" => Self::SolidBlackHandsAndGrafts,
            _ => Self::CustomStereoProjection,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "custom-stereo-projection",
            Self::NativePassthroughGraftOnly => "native-passthrough-graft-only",
            Self::SolidBlackHandsAndGrafts => "solid-black-hands-and-grafts",
        }
    }

    pub(crate) fn uses_custom_stereo_projection(self) -> bool {
        matches!(self, Self::CustomStereoProjection)
    }

    pub(crate) fn uses_native_passthrough(self) -> bool {
        matches!(self, Self::NativePassthroughGraftOnly)
    }

    pub(crate) fn uses_solid_black_background(self) -> bool {
        matches!(self, Self::SolidBlackHandsAndGrafts)
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
            Self::NativePassthroughGraftOnly => "skipped-native-passthrough",
            Self::SolidBlackHandsAndGrafts => "skipped-solid-black-hands-and-grafts",
        }
    }

    pub(crate) fn disabled_camera_projection_path(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "metadata-target-direct-hwb-fallback",
            Self::NativePassthroughGraftOnly => "disabled-native-passthrough-graft-only",
            Self::SolidBlackHandsAndGrafts => "disabled-solid-black-hands-and-grafts",
        }
    }

    pub(crate) fn allows_sdf_visual(self) -> bool {
        matches!(self, Self::CustomStereoProjection)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CompactHandInputSourceMode {
    Auto,
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
            Self::RecordedReplay => "recorded-replay",
            Self::LiveMeta => "live-meta-openxr-hand-tracking",
        }
    }

    pub(crate) fn selects_live_frame(self) -> bool {
        !matches!(self, Self::RecordedReplay)
    }

    pub(crate) fn allows_recorded_fallback(self) -> bool {
        !matches!(self, Self::LiveMeta)
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeProjectionProcessingLayer {
    Blur,
    PeripheralStretch,
}

impl NativeProjectionProcessingLayer {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "stretch"
            | "peripheral-stretch"
            | "border-stretch"
            | "projection-border-stretch"
            | "edge-stretch" => Self::PeripheralStretch,
            _ => Self::Blur,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Blur => "blur",
            Self::PeripheralStretch => "peripheral-stretch",
        }
    }

    pub(crate) fn consumes_projection_exterior(self) -> bool {
        matches!(self, Self::PeripheralStretch)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeProjectionBorderPolicy {
    SolidRed,
    PassthroughUnderlay,
}

impl NativeProjectionBorderPolicy {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "passthrough" | "passthrough-underlay" | "underlay" => Self::PassthroughUnderlay,
            _ => Self::SolidRed,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::SolidRed => "solid-red",
            Self::PassthroughUnderlay => "passthrough-underlay",
        }
    }

    fn shader_code(self) -> f32 {
        match self {
            Self::SolidRed => 0.0,
            Self::PassthroughUnderlay => 1.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativePeripheralStretchBlendMode {
    Off,
    TargetInnerBand,
}

impl NativePeripheralStretchBlendMode {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "0" | "false" | "no" | "off" | "disabled" => Self::Off,
            _ => Self::TargetInnerBand,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::TargetInnerBand => "target-inner-band",
        }
    }

    fn shader_code(self) -> f32 {
        match self {
            Self::Off => 0.0,
            Self::TargetInnerBand => 1.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativePeripheralStretchDebug {
    Off,
    Regions,
    SampleUv,
}

impl NativePeripheralStretchDebug {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "1" | "true" | "yes" | "on" | "enabled" | "regions" | "region" => Self::Regions,
            "2" | "sample-uv" | "sampleuv" | "uv" => Self::SampleUv,
            _ => Self::Off,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Regions => "regions",
            Self::SampleUv => "sample-uv",
        }
    }

    fn shader_code(self) -> f32 {
        match self {
            Self::Off => 0.0,
            Self::Regions => 1.0,
            Self::SampleUv => 2.0,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeProjectionBorderStretchSettings {
    pub(crate) processing_layer: NativeProjectionProcessingLayer,
    pub(crate) border_policy: NativeProjectionBorderPolicy,
    pub(crate) projection_area_opacity: f32,
    pub(crate) projection_border_opacity: f32,
    pub(crate) core_scale: f32,
    pub(crate) edge_inset_uv: f32,
    pub(crate) max_inset_uv: f32,
    pub(crate) curve: f32,
    pub(crate) inner_blend_uv: f32,
    pub(crate) blend_curve: f32,
    pub(crate) blend_mode: NativePeripheralStretchBlendMode,
    pub(crate) debug: NativePeripheralStretchDebug,
}

impl NativeProjectionBorderStretchSettings {
    fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let edge_inset_uv = f32_clamped_value(
            lookup(PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV),
            0.015,
            0.0,
            0.49,
        );
        Self {
            processing_layer: NativeProjectionProcessingLayer::from_property(lookup(
                PROP_PROCESSING_LAYER,
            )),
            border_policy: NativeProjectionBorderPolicy::from_property(lookup(
                PROP_PROJECTION_BORDER_POLICY,
            )),
            projection_area_opacity: f32_clamped_value(
                lookup(PROP_PROJECTION_AREA_OPACITY),
                1.0,
                0.0,
                1.0,
            ),
            projection_border_opacity: f32_clamped_value(
                lookup(PROP_PROJECTION_BORDER_OPACITY),
                1.0,
                0.0,
                1.0,
            ),
            core_scale: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_CORE_SCALE),
                1.0,
                0.05,
                1.0,
            ),
            edge_inset_uv,
            max_inset_uv: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_MAX_INSET_UV),
                0.14,
                edge_inset_uv,
                0.49,
            ),
            curve: f32_clamped_value(lookup(PROP_PERIPHERAL_STRETCH_CURVE), 1.6, 0.25, 6.0),
            inner_blend_uv: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_INNER_BLEND_UV),
                0.040,
                0.0,
                0.25,
            ),
            blend_curve: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_BLEND_CURVE),
                1.6,
                0.25,
                6.0,
            ),
            blend_mode: NativePeripheralStretchBlendMode::from_property(lookup(
                PROP_PERIPHERAL_STRETCH_BLEND_MODE,
            )),
            debug: NativePeripheralStretchDebug::from_property(lookup(
                PROP_PERIPHERAL_STRETCH_DEBUG,
            )),
        }
    }

    pub(crate) fn peripheral_stretch_active(self) -> bool {
        self.processing_layer.consumes_projection_exterior()
    }

    pub(crate) fn transition_active(self) -> bool {
        self.blend_mode == NativePeripheralStretchBlendMode::TargetInnerBand
            && self.inner_blend_uv > 0.0001
    }

    pub(crate) fn marker_fields(self) -> String {
        let transition_active = self.transition_active();
        let (core_region, transition_region, transition_space, transition_semantics) =
            if transition_active {
                (
                    "target-footprint-minus-inner-transition-band",
                    "target-footprint-inner-edge-band",
                    "target-local-raster-uv",
                    "canonical-sample-to-stretch-sample-remap",
                )
            } else {
                (
                    "target-footprint",
                    "off",
                    "off",
                    "hard-edge-preblend-reference",
                )
            };
        let projection_exterior_mode = if self.peripheral_stretch_active() && transition_active {
            "target-edge-stretch-with-inner-band-blend"
        } else if self.peripheral_stretch_active() {
            "target-edge-stretch-hard-edge"
        } else {
            "projection-border-policy-fallback"
        };
        format!(
            "processingLayer={} projectionBorderPolicy={} projectionAreaOpacity={:.3} projectionBorderOpacity={:.3} peripheralStretchMode=edge-stretch peripheralStretchCoreScale={:.3} peripheralStretchEdgeInsetUv={:.3} peripheralStretchMaxInsetUv={:.3} peripheralStretchCurve={:.3} peripheralStretchInnerBlendUv={:.3} peripheralStretchBlendCurve={:.3} peripheralStretchBlendMode={} peripheralStretchCornerMode=target-footprint peripheralStretchDebug={} peripheralStretchActive={} peripheralStretchTransitionActive={} peripheralStretchConsumesProjectionExterior={} peripheralStretchCoreRegion={} peripheralStretchTransitionRegion={} peripheralStretchExteriorRegion=visible-render-surface-minus-target-footprint peripheralStretchTransitionSpace={} peripheralStretchTransitionSemantics={} peripheralStretchProjectionExteriorMode={} peripheralStretchMapping=mirrored-curved-target-footprint peripheralStretchDistanceCurve=mirrored-border-smoothstep-swirl peripheralStretchBorderSource=mirrored-projection-edge-trail peripheralStretchExteriorSource=curved-target-edge-sample peripheralStretchBlendSemantics=curved-sample-blends-through-inner-band peripheralStretchTargetLocalRasterRegionModel=projection-area-plus-single-border-region peripheralStretchSourceInvalidConsumesSolidRed=false peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band",
            self.processing_layer.marker_value(),
            self.border_policy.marker_value(),
            self.projection_area_opacity,
            self.projection_border_opacity,
            self.core_scale,
            self.edge_inset_uv,
            self.max_inset_uv,
            self.curve,
            self.inner_blend_uv,
            self.blend_curve,
            self.blend_mode.marker_value(),
            self.debug.marker_value(),
            self.peripheral_stretch_active(),
            transition_active,
            self.processing_layer.consumes_projection_exterior(),
            core_region,
            transition_region,
            transition_space,
            transition_semantics,
            projection_exterior_mode,
        )
    }

    pub(crate) fn push_params(self) -> NativeProjectionBorderStretchPush {
        NativeProjectionBorderStretchPush {
            params: [
                if self.peripheral_stretch_active() {
                    1.0
                } else {
                    0.0
                },
                self.border_policy.shader_code(),
                self.projection_area_opacity,
                self.projection_border_opacity,
            ],
            stretch0: [
                self.core_scale,
                self.edge_inset_uv,
                self.max_inset_uv,
                self.curve,
            ],
            stretch1: [
                self.inner_blend_uv,
                self.blend_curve,
                self.blend_mode.shader_code(),
                self.debug.shader_code(),
            ],
        }
    }
}

impl Default for NativeProjectionBorderStretchSettings {
    fn default() -> Self {
        Self {
            processing_layer: NativeProjectionProcessingLayer::Blur,
            border_policy: NativeProjectionBorderPolicy::SolidRed,
            projection_area_opacity: 1.0,
            projection_border_opacity: 1.0,
            core_scale: 1.0,
            edge_inset_uv: 0.015,
            max_inset_uv: 0.14,
            curve: 1.6,
            inner_blend_uv: 0.040,
            blend_curve: 1.6,
            blend_mode: NativePeripheralStretchBlendMode::TargetInnerBand,
            debug: NativePeripheralStretchDebug::Off,
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct NativeProjectionBorderStretchPush {
    pub(crate) params: [f32; 4],
    pub(crate) stretch0: [f32; 4],
    pub(crate) stretch1: [f32; 4],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeRendererRuntimeOptions {
    pub(crate) render_mode: NativeRendererRenderMode,
    pub(crate) replay_visual_proof_enabled: bool,
    pub(crate) compact_hand_input_source_mode: CompactHandInputSourceMode,
    pub(crate) sdf_visual_enabled: bool,
    pub(crate) sdf_update_period_frames: u64,
    pub(crate) hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings,
    pub(crate) hand_mesh_graft_copies_enabled: bool,
    pub(crate) hand_mesh_graft_copy_scale: f32,
    pub(crate) hand_mesh_real_hands_visible: bool,
    pub(crate) projection_border_stretch_settings: NativeProjectionBorderStretchSettings,
}

impl NativeRendererRuntimeOptions {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let render_mode = NativeRendererRenderMode::from_property(lookup(PROP_RENDER_MODE));
        let replay_visual_proof_enabled =
            bool_value(lookup(PROP_REPLAY_VISUAL_PROOF_ENABLED), false);
        let compact_hand_input_source_mode = CompactHandInputSourceMode::from_property(
            lookup(PROP_HAND_MESH_INPUT_SOURCE),
            replay_visual_proof_enabled,
        );
        let requested_sdf_visual =
            replay_visual_proof_enabled || bool_value(lookup(PROP_ENABLE_SDF_VISUAL), false);
        let sdf_visual_enabled = requested_sdf_visual && render_mode.allows_sdf_visual();
        let sdf_update_period_frames = u64_value(lookup(PROP_SDF_UPDATE_PERIOD_FRAMES), 2, 1, 120);
        let diagnostic_enabled = replay_visual_proof_enabled
            || bool_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED), false);
        let diagnostic_offset_uv = f32_pair_value(
            lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV),
            [0.12, -0.08],
        );
        let diagnostic_alpha = f32_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA), 0.86);
        let hand_mesh_graft_copies_enabled = render_mode.forces_graft_copies()
            || bool_value(lookup(PROP_HAND_MESH_GRAFT_COPIES_ENABLED), false);
        let hand_mesh_graft_copy_scale =
            f32_value(lookup(PROP_HAND_MESH_GRAFT_COPY_SCALE), 1.0).clamp(0.10, 2.0);
        let hand_mesh_real_hands_visible = render_mode.forces_real_hand_meshes()
            || bool_value(lookup(PROP_HAND_MESH_REAL_HANDS_VISIBLE), false);
        let projection_border_stretch_settings =
            NativeProjectionBorderStretchSettings::from_property_lookup(&mut lookup);

        Self {
            render_mode,
            replay_visual_proof_enabled,
            compact_hand_input_source_mode,
            sdf_visual_enabled,
            sdf_update_period_frames,
            hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings::new(
                diagnostic_enabled,
                diagnostic_offset_uv,
                diagnostic_alpha,
            ),
            hand_mesh_graft_copies_enabled,
            hand_mesh_graft_copy_scale,
            hand_mesh_real_hands_visible,
            projection_border_stretch_settings,
        }
    }

    #[cfg(target_os = "android")]
    pub(crate) fn load_from_android_properties() -> Self {
        Self::from_property_lookup(android_property)
    }
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

fn bool_value(value: Option<String>, default_value: bool) -> bool {
    value.map_or(default_value, |value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on"
        )
    })
}

fn u64_value(value: Option<String>, default_value: u64, min_value: u64, max_value: u64) -> u64 {
    value
        .and_then(|value| value.trim().parse::<u64>().ok())
        .filter(|value| *value >= min_value)
        .unwrap_or(default_value)
        .min(max_value)
}

fn f32_value(value: Option<String>, default_value: f32) -> f32 {
    value
        .and_then(|value| value.trim().parse::<f32>().ok())
        .unwrap_or(default_value)
}

fn f32_clamped_value(
    value: Option<String>,
    default_value: f32,
    min_value: f32,
    max_value: f32,
) -> f32 {
    f32_value(value, default_value).clamp(min_value, max_value)
}

fn f32_pair_value(value: Option<String>, default_value: [f32; 2]) -> [f32; 2] {
    let Some(value) = value else {
        return default_value;
    };
    let parts = value
        .split(|character: char| character == ',' || character == ';' || character.is_whitespace())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    if parts.len() != 2 {
        return default_value;
    }
    let Some(x) = parts[0].trim().parse::<f32>().ok() else {
        return default_value;
    };
    let Some(y) = parts[1].trim().parse::<f32>().ok() else {
        return default_value;
    };
    [x, y]
}

fn normalized_property(value: Option<String>) -> String {
    value
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .replace('_', "-")
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use super::{
        CompactHandInputSourceMode, NativeRendererRuntimeOptions, PROP_ENABLE_SDF_VISUAL,
        PROP_HAND_MESH_GRAFT_COPIES_ENABLED, PROP_HAND_MESH_GRAFT_COPY_SCALE,
        PROP_HAND_MESH_INPUT_SOURCE, PROP_HAND_MESH_REAL_HANDS_VISIBLE,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, PROP_PERIPHERAL_STRETCH_BLEND_MODE,
        PROP_PERIPHERAL_STRETCH_CORE_SCALE, PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV,
        PROP_PERIPHERAL_STRETCH_MAX_INSET_UV, PROP_PROCESSING_LAYER,
        PROP_PROJECTION_BORDER_OPACITY, PROP_PROJECTION_BORDER_POLICY, PROP_RENDER_MODE,
        PROP_REPLAY_VISUAL_PROOF_ENABLED, PROP_SDF_UPDATE_PERIOD_FRAMES,
    };

    fn options_from(values: &[(&str, &str)]) -> NativeRendererRuntimeOptions {
        let values = values.iter().copied().collect::<BTreeMap<_, _>>();
        NativeRendererRuntimeOptions::from_property_lookup(|name| {
            values.get(name).map(|value| (*value).to_owned())
        })
    }

    #[test]
    fn replay_visual_proof_forces_recorded_diagnostic_and_sdf() {
        let options = options_from(&[(PROP_REPLAY_VISUAL_PROOF_ENABLED, "true")]);
        assert!(options.replay_visual_proof_enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::RecordedReplay
        );
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert_eq!(
            options.render_mode.marker_value(),
            "custom-stereo-projection"
        );
        assert_eq!(options.hand_mesh_graft_copy_scale, 1.0);
        assert!(!options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn explicit_live_source_overrides_replay_proof_source_selection() {
        let options = options_from(&[
            (PROP_REPLAY_VISUAL_PROOF_ENABLED, "true"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
        ]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn canonical_live_source_value_selects_live_without_replay_fallback() {
        let options = options_from(&[(
            PROP_HAND_MESH_INPUT_SOURCE,
            "live-meta-openxr-hand-tracking",
        )]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn auto_mode_defaults_to_recorded_fallback_without_diagnostics() {
        let options = options_from(&[(PROP_HAND_MESH_INPUT_SOURCE, "auto")]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Auto
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn sdf_and_diagnostic_values_parse_and_clamp() {
        let options = options_from(&[
            (PROP_ENABLE_SDF_VISUAL, "on"),
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "999"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED, "yes"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "9.0,-9.0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "4.0"),
            (PROP_HAND_MESH_GRAFT_COPIES_ENABLED, "on"),
        ]);
        assert!(options.sdf_visual_enabled);
        assert_eq!(options.sdf_update_period_frames, 120);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.45, -0.45]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 1.0);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn native_passthrough_graft_only_forces_grafts_and_disables_sdf_visual() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-graft-only"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "native-passthrough-graft-only"
        );
        assert!(options.render_mode.uses_native_passthrough());
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.sdf_visual_enabled);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert_eq!(options.hand_mesh_graft_copy_scale, 0.85);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn native_passthrough_real_hand_mesh_visibility_is_explicit() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-graft-only"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_HAND_MESH_REAL_HANDS_VISIBLE, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert!(options.render_mode.uses_native_passthrough());
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(options.hand_mesh_real_hands_visible);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
    }

    #[test]
    fn solid_black_hands_and_grafts_forces_hand_visuals_without_camera_or_sdf() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "solid-black-hands-and-grafts"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "solid-black-hands-and-grafts"
        );
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.render_mode.uses_native_passthrough());
        assert!(options.render_mode.uses_solid_black_background());
        assert!(!options.sdf_visual_enabled);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(options.hand_mesh_real_hands_visible);
        assert_eq!(options.hand_mesh_graft_copy_scale, 0.85);
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-solid-black-hands-and-grafts"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-solid-black-hands-and-grafts"
        );
    }

    #[test]
    fn invalid_values_keep_defaults() {
        let options = options_from(&[
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "bad"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "bad"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "bad"),
        ]);
        assert_eq!(options.sdf_update_period_frames, 2);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.12, -0.08]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 0.86);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert_eq!(options.hand_mesh_graft_copy_scale, 1.0);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn peripheral_stretch_settings_match_hwb_reference_defaults() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "peripheral-stretch"),
            (PROP_PROJECTION_BORDER_POLICY, "passthrough-underlay"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(settings.peripheral_stretch_active());
        assert!(settings.transition_active());
        assert_eq!(settings.core_scale, 1.0);
        assert_eq!(settings.edge_inset_uv, 0.015);
        assert_eq!(settings.max_inset_uv, 0.14);
        assert_eq!(settings.inner_blend_uv, 0.040);

        let fields = settings.marker_fields();
        assert!(fields.contains("processingLayer=peripheral-stretch"));
        assert!(fields.contains("projectionBorderPolicy=passthrough-underlay"));
        assert!(fields.contains("peripheralStretchBlendMode=target-inner-band"));
        assert!(fields.contains("peripheralStretchTransitionActive=true"));
        assert!(fields.contains("peripheralStretchConsumesProjectionExterior=true"));
        assert!(fields.contains(
            "peripheralStretchProjectionExteriorMode=target-edge-stretch-with-inner-band-blend"
        ));
        assert!(fields
            .contains("peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band"));
    }

    #[test]
    fn peripheral_stretch_values_parse_and_clamp() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "edge_stretch"),
            (PROP_PERIPHERAL_STRETCH_CORE_SCALE, "0.001"),
            (PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV, "0.7"),
            (PROP_PERIPHERAL_STRETCH_MAX_INSET_UV, "0.1"),
            (PROP_PERIPHERAL_STRETCH_BLEND_MODE, "off"),
            (PROP_PROJECTION_BORDER_OPACITY, "-5"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(settings.peripheral_stretch_active());
        assert_eq!(settings.core_scale, 0.05);
        assert_eq!(settings.edge_inset_uv, 0.49);
        assert_eq!(settings.max_inset_uv, 0.49);
        assert_eq!(settings.projection_border_opacity, 0.0);
        assert!(!settings.transition_active());
    }
}
