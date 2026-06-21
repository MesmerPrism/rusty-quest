//! Projection border and peripheral-stretch options for the native renderer.

use crate::native_renderer_properties::{
    PROP_PERIPHERAL_STRETCH_BLEND_CURVE, PROP_PERIPHERAL_STRETCH_BLEND_MODE,
    PROP_PERIPHERAL_STRETCH_CORE_SCALE, PROP_PERIPHERAL_STRETCH_CURVE,
    PROP_PERIPHERAL_STRETCH_DEBUG, PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV,
    PROP_PERIPHERAL_STRETCH_INNER_BLEND_UV, PROP_PERIPHERAL_STRETCH_MAX_INSET_UV,
    PROP_PROCESSING_LAYER, PROP_PROJECTION_AREA_OPACITY, PROP_PROJECTION_BORDER_OPACITY,
    PROP_PROJECTION_BORDER_POLICY, PROP_VIDEO_BORDER_BLEND_MODE,
};
use crate::native_renderer_property_values::{f32_clamped_value, normalized_property};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeProjectionProcessingLayer {
    Blur,
    PeripheralStretch,
    VideoBorderBlend,
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
            "video-border"
            | "video-border-blend"
            | "peripheral-video"
            | "peripheral-video-blend"
            | "projection-video-border" => Self::VideoBorderBlend,
            _ => Self::Blur,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Blur => "blur",
            Self::PeripheralStretch => "peripheral-stretch",
            Self::VideoBorderBlend => "video-border-blend",
        }
    }

    pub(crate) fn consumes_projection_exterior(self) -> bool {
        matches!(self, Self::PeripheralStretch)
    }

    pub(crate) fn video_border_blend(self) -> bool {
        matches!(self, Self::VideoBorderBlend)
    }

    fn shader_code(self) -> f32 {
        match self {
            Self::Blur => 0.0,
            Self::PeripheralStretch => 1.0,
            Self::VideoBorderBlend => 2.0,
        }
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeVideoBorderBlendMode {
    AlphaOver,
    Crossfade,
    LinearCrossfade,
    LumaMatch,
    ChromaLuma,
    SoftLight,
    Overlay,
    Screen,
    Multiply,
    GradientAware,
    TwoBand,
    TemporalStabilized,
}

impl NativeVideoBorderBlendMode {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "crossfade" | "shader-crossfade" | "composite" => Self::Crossfade,
            "linear" | "linear-crossfade" | "linear-light" | "linear-light-crossfade" => {
                Self::LinearCrossfade
            }
            "luma" | "luma-match" | "luma-matched" | "luminance-match" => Self::LumaMatch,
            "chroma-luma" | "luma-chroma" | "chroma-luma-split" | "luma-chroma-split"
            | "split-luma-chroma" => Self::ChromaLuma,
            "soft-light" | "softlight" => Self::SoftLight,
            "overlay" => Self::Overlay,
            "screen" => Self::Screen,
            "multiply" => Self::Multiply,
            "gradient-aware" | "edge-aware" | "edge-preserving" | "sharpness-aware" => {
                Self::GradientAware
            }
            "two-band" | "multi-band" | "multiband" | "frequency-split" => Self::TwoBand,
            "temporal" | "temporal-stabilized" | "temporal-stabilized-mask" => {
                Self::TemporalStabilized
            }
            _ => Self::AlphaOver,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::AlphaOver => "alpha-over",
            Self::Crossfade => "crossfade",
            Self::LinearCrossfade => "linear-crossfade",
            Self::LumaMatch => "luma-match",
            Self::ChromaLuma => "chroma-luma",
            Self::SoftLight => "soft-light",
            Self::Overlay => "overlay",
            Self::Screen => "screen",
            Self::Multiply => "multiply",
            Self::GradientAware => "gradient-aware",
            Self::TwoBand => "two-band",
            Self::TemporalStabilized => "temporal-stabilized",
        }
    }

    pub(crate) fn shader_code(self) -> f32 {
        match self {
            Self::AlphaOver => 0.0,
            Self::Crossfade => 1.0,
            Self::LinearCrossfade => 2.0,
            Self::LumaMatch => 3.0,
            Self::ChromaLuma => 4.0,
            Self::SoftLight => 5.0,
            Self::Overlay => 6.0,
            Self::Screen => 7.0,
            Self::Multiply => 8.0,
            Self::GradientAware => 9.0,
            Self::TwoBand => 10.0,
            Self::TemporalStabilized => 11.0,
        }
    }

    pub(crate) fn cost_tier(self) -> &'static str {
        match self {
            Self::AlphaOver => "baseline-fixed-function",
            Self::Crossfade => "low",
            Self::LinearCrossfade => "low-medium",
            Self::LumaMatch
            | Self::ChromaLuma
            | Self::SoftLight
            | Self::Overlay
            | Self::Screen
            | Self::Multiply => "medium",
            Self::GradientAware => "medium-high",
            Self::TwoBand => "high",
            Self::TemporalStabilized => "low-medium",
        }
    }

    pub(crate) fn sample_pattern(self) -> &'static str {
        match self {
            Self::AlphaOver => "fixed-function-alpha",
            Self::Crossfade
            | Self::LinearCrossfade
            | Self::LumaMatch
            | Self::ChromaLuma
            | Self::SoftLight
            | Self::Overlay
            | Self::Screen
            | Self::Multiply
            | Self::TemporalStabilized => "guide-and-video-single-sample",
            Self::GradientAware => "guide-and-video-single-sample-plus-derivatives",
            Self::TwoBand => "guide-and-video-five-tap-low-high-split",
        }
    }

    pub(crate) fn formula_marker(self) -> &'static str {
        match self {
            Self::AlphaOver => "premultiplied-alpha-over",
            Self::Crossfade => "srgb-crossfade",
            Self::LinearCrossfade => "linear-light-crossfade",
            Self::LumaMatch => "luma-matched-crossfade",
            Self::ChromaLuma => "chroma-luma-split",
            Self::SoftLight => "soft-light-band",
            Self::Overlay => "overlay-band",
            Self::Screen => "screen-band",
            Self::Multiply => "multiply-band",
            Self::GradientAware => "gradient-aware-weight-bias",
            Self::TwoBand => "two-band-low-high-split",
            Self::TemporalStabilized => "temporal-stabilized-mask-crossfade",
        }
    }

    pub(crate) fn temporal_state(self) -> &'static str {
        match self {
            Self::TemporalStabilized => "per-eye-target-rect-ema",
            _ => "none",
        }
    }

    pub(crate) fn uses_temporal_target_rect_state(self) -> bool {
        matches!(self, Self::TemporalStabilized)
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
    pub(crate) video_border_blend_mode: NativeVideoBorderBlendMode,
    pub(crate) debug: NativePeripheralStretchDebug,
}

impl NativeProjectionBorderStretchSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
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
            video_border_blend_mode: NativeVideoBorderBlendMode::from_property(lookup(
                PROP_VIDEO_BORDER_BLEND_MODE,
            )),
            debug: NativePeripheralStretchDebug::from_property(lookup(
                PROP_PERIPHERAL_STRETCH_DEBUG,
            )),
        }
    }

    pub(crate) fn peripheral_stretch_active(self) -> bool {
        self.processing_layer.consumes_projection_exterior()
    }

    pub(crate) fn video_border_blend_active(self) -> bool {
        self.processing_layer.video_border_blend()
    }

    pub(crate) fn guide_projection_coverage(self) -> &'static str {
        if self.peripheral_stretch_active() {
            "full-eye-peripheral-stretch"
        } else if self.video_border_blend_active() {
            "full-eye-video-border-blend"
        } else {
            "metadata-target-only"
        }
    }

    pub(crate) fn transition_active(self) -> bool {
        self.blend_mode == NativePeripheralStretchBlendMode::TargetInnerBand
            && self.inner_blend_uv > 0.0001
            && (self.peripheral_stretch_active() || self.video_border_blend_active())
    }

    pub(crate) fn diagnostic_edge_tint_active(self) -> bool {
        self.debug != NativePeripheralStretchDebug::Off
    }

    pub(crate) fn video_border_shader_composite_active(self) -> bool {
        self.video_border_blend_active()
            && self.video_border_blend_mode != NativeVideoBorderBlendMode::AlphaOver
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
        let projection_exterior_mode = if self.video_border_blend_active() && transition_active {
            "video-background-with-inner-band-camera-blend"
        } else if self.video_border_blend_active() {
            "video-background-hard-edge-camera-overlay"
        } else if self.peripheral_stretch_active() && transition_active {
            "target-edge-stretch-with-inner-band-blend"
        } else if self.peripheral_stretch_active() {
            "target-edge-stretch-hard-edge"
        } else {
            "projection-border-policy-fallback"
        };
        let exterior_source = if self.video_border_blend_active() {
            "video-projection-background"
        } else if self.peripheral_stretch_active() {
            "curved-target-edge-sample"
        } else {
            "projection-border-policy"
        };
        let blend_semantics = if self.video_border_blend_active() {
            if self.video_border_shader_composite_active() {
                "camera-guide-and-video-sampled-shader-composite-through-inner-band"
            } else {
                "camera-guide-alpha-fades-to-video-through-inner-band"
            }
        } else {
            "curved-sample-blends-through-inner-band"
        };
        let video_border_compositor = if self.video_border_shader_composite_active() {
            "guide-video-shader-composite"
        } else {
            "fixed-function-premultiplied-alpha"
        };
        format!(
            "processingLayer={} projectionBorderPolicy={} projectionAreaOpacity={:.3} projectionBorderOpacity={:.3} guideProjectionCoverage={} guideProjectionEdgeTint=diagnostic-debug-only guideProjectionEdgeTintActive={} peripheralStretchMode=edge-stretch peripheralStretchCoreScale={:.3} peripheralStretchEdgeInsetUv={:.3} peripheralStretchMaxInsetUv={:.3} peripheralStretchCurve={:.3} peripheralStretchInnerBlendUv={:.3} peripheralStretchBlendCurve={:.3} peripheralStretchBlendMode={} peripheralStretchCornerMode=target-footprint peripheralStretchDebug={} peripheralStretchActive={} videoBorderBlendActive={} videoBorderBlendMode={} videoBorderBlendCompositor={} videoBorderBlendShaderCompositeActive={} videoBorderBlendFormula={} videoBorderBlendCostTier={} videoBorderBlendSamplePattern={} videoBorderBlendTemporalState={} peripheralStretchTransitionActive={} peripheralStretchConsumesProjectionExterior={} videoBorderBlendConsumesProjectionExterior=false peripheralStretchCoreRegion={} peripheralStretchTransitionRegion={} peripheralStretchExteriorRegion=visible-render-surface-minus-target-footprint peripheralStretchTransitionSpace={} peripheralStretchTransitionSemantics={} peripheralStretchProjectionExteriorMode={} peripheralStretchMapping=mirrored-curved-target-footprint peripheralStretchDistanceCurve=mirrored-border-smoothstep-swirl peripheralStretchBorderSource=mirrored-projection-edge-trail peripheralStretchExteriorSource={} peripheralStretchBlendSemantics={} videoBorderBlendSource=prepared-stereo-video-projection-background videoBorderBlendCameraSource=guide-texture videoBorderBlendDrawOrder=video-background-then-camera-guide-overlay videoBorderBlendAlphaSemantics={} peripheralStretchTargetLocalRasterRegionModel=projection-area-plus-single-border-region peripheralStretchSourceInvalidConsumesSolidRed=false peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band videoBorderBlendReference=peripheral-stretch-inner-band-blend-over-video",
            self.processing_layer.marker_value(),
            self.border_policy.marker_value(),
            self.projection_area_opacity,
            self.projection_border_opacity,
            self.guide_projection_coverage(),
            self.diagnostic_edge_tint_active(),
            self.core_scale,
            self.edge_inset_uv,
            self.max_inset_uv,
            self.curve,
            self.inner_blend_uv,
            self.blend_curve,
            self.blend_mode.marker_value(),
            self.debug.marker_value(),
            self.peripheral_stretch_active(),
            self.video_border_blend_active(),
            self.video_border_blend_mode.marker_value(),
            video_border_compositor,
            self.video_border_shader_composite_active(),
            self.video_border_blend_mode.formula_marker(),
            self.video_border_blend_mode.cost_tier(),
            self.video_border_blend_mode.sample_pattern(),
            self.video_border_blend_mode.temporal_state(),
            transition_active,
            self.processing_layer.consumes_projection_exterior(),
            core_region,
            transition_region,
            transition_space,
            transition_semantics,
            projection_exterior_mode,
            exterior_source,
            blend_semantics,
            self.video_border_blend_mode.formula_marker(),
        )
    }

    pub(crate) fn push_params(self) -> NativeProjectionBorderStretchPush {
        NativeProjectionBorderStretchPush {
            params: [
                self.processing_layer.shader_code(),
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
            video_border_blend_mode: NativeVideoBorderBlendMode::AlphaOver,
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
