//! Projection border and peripheral-stretch options for the native renderer.

use crate::native_renderer_properties::{
    PROP_PERIPHERAL_STRETCH_BLEND_CURVE, PROP_PERIPHERAL_STRETCH_BLEND_MODE,
    PROP_PERIPHERAL_STRETCH_CORE_SCALE, PROP_PERIPHERAL_STRETCH_CURVE,
    PROP_PERIPHERAL_STRETCH_DEBUG, PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV,
    PROP_PERIPHERAL_STRETCH_INNER_BLEND_UV, PROP_PERIPHERAL_STRETCH_MAX_INSET_UV,
    PROP_PROCESSING_LAYER, PROP_PROJECTION_AREA_OPACITY, PROP_PROJECTION_BORDER_OPACITY,
    PROP_PROJECTION_BORDER_POLICY,
};
use crate::native_renderer_property_values::{f32_clamped_value, normalized_property};

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
