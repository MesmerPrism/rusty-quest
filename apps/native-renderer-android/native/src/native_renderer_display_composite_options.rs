//! Display-composite MediaProjection settings for the native renderer.
//!
//! This module deliberately treats Android MediaProjection as display-composite
//! evidence only. It can feed a future Vulkan sampled-image path through
//! HardwareBuffer import, but it is not raw camera, passthrough, depth, or
//! geometry authority.

use crate::{
    native_renderer_properties::{
        PROP_DISPLAY_COMPOSITE_ENABLED, PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED,
        PROP_DISPLAY_COMPOSITE_FEEDBACK_PROJECTION, PROP_DISPLAY_COMPOSITE_FPS_CAP,
        PROP_DISPLAY_COMPOSITE_HEIGHT, PROP_DISPLAY_COMPOSITE_HIGH_RATE_JSON_PAYLOAD,
        PROP_DISPLAY_COMPOSITE_MAX_IMAGES, PROP_DISPLAY_COMPOSITE_MODE,
        PROP_DISPLAY_COMPOSITE_SOURCE, PROP_DISPLAY_COMPOSITE_WIDTH,
    },
    native_renderer_property_values::{bool_value, normalized_property, u32_value},
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct NativeDisplayCompositeSettings {
    pub(crate) enabled: bool,
    pub(crate) source: NativeDisplayCompositeSource,
    pub(crate) mode: NativeDisplayCompositeMode,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) max_images: u32,
    pub(crate) fps_cap: u32,
    pub(crate) feedback_enabled: bool,
    pub(crate) feedback_projection: NativeDisplayCompositeFeedbackProjection,
    pub(crate) high_rate_json_payload: bool,
}

impl NativeDisplayCompositeSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            enabled: bool_value(lookup(PROP_DISPLAY_COMPOSITE_ENABLED), false),
            source: NativeDisplayCompositeSource::from_property(lookup(
                PROP_DISPLAY_COMPOSITE_SOURCE,
            )),
            mode: NativeDisplayCompositeMode::from_property(lookup(PROP_DISPLAY_COMPOSITE_MODE)),
            width: u32_value(lookup(PROP_DISPLAY_COMPOSITE_WIDTH), 1280, 320, 4096),
            height: u32_value(lookup(PROP_DISPLAY_COMPOSITE_HEIGHT), 720, 240, 4096),
            max_images: u32_value(lookup(PROP_DISPLAY_COMPOSITE_MAX_IMAGES), 3, 2, 6),
            fps_cap: u32_value(lookup(PROP_DISPLAY_COMPOSITE_FPS_CAP), 30, 1, 90),
            feedback_enabled: bool_value(lookup(PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED), false),
            feedback_projection: NativeDisplayCompositeFeedbackProjection::from_property(lookup(
                PROP_DISPLAY_COMPOSITE_FEEDBACK_PROJECTION,
            )),
            high_rate_json_payload: bool_value(
                lookup(PROP_DISPLAY_COMPOSITE_HIGH_RATE_JSON_PAYLOAD),
                false,
            ),
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "displayCompositeEnabled={} displayCompositeSource={} displayCompositeMode={} displayCompositeWidth={} displayCompositeHeight={} displayCompositeMaxImages={} displayCompositeFpsCap={} displayCompositeFeedbackEnabled={} displayCompositeFeedbackProjection={} displayCompositeStream=display_composite displayCompositeCaptureAuthority=android-mediaprojection displayCompositeRawCamera=false displayCompositePassthroughTexture=false displayCompositeEnvironmentDepth=false displayCompositeGeometryWitness=false displayCompositeHighRateJsonPayload={} displayCompositeTransport=ndk-aimage-reader-ahardwarebuffer displayCompositeFramePlane=media-data-plane displayCompositeControlPlane=android-property-profile displayCompositeFormat=rgba8888 displayCompositePrivateFormatFastPathCandidate=true nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false displayCompositeGpuImportReady=false displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image highRateJsonPayload={} sourceAuthority=android-mediaprojection rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false",
            self.enabled,
            self.source.marker_value(),
            self.mode.marker_value(),
            self.width,
            self.height,
            self.max_images,
            self.fps_cap,
            self.feedback_enabled,
            self.feedback_projection.marker_value(),
            self.high_rate_json_payload,
            self.high_rate_json_payload,
        )
    }

    pub(crate) fn capture_export_enabled(self) -> bool {
        self.enabled
            && self.feedback_enabled
            && !self.high_rate_json_payload
            && matches!(
                self.mode,
                NativeDisplayCompositeMode::GpuReadbackDiagnostic
                    | NativeDisplayCompositeMode::GpuRecursiveFeedbackDiagnostic
            )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeDisplayCompositeSource {
    AndroidMediaProjection,
}

impl NativeDisplayCompositeSource {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "android-mediaprojection" | "" => Self::AndroidMediaProjection,
            _ => Self::AndroidMediaProjection,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::AndroidMediaProjection => "android-mediaprojection",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeDisplayCompositeMode {
    HardwareBufferWitness,
    GpuFeedbackDiagnostic,
    GpuRecursiveFeedbackDiagnostic,
    GpuReadbackDiagnostic,
}

impl NativeDisplayCompositeMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "gpu-feedback-diagnostic" => Self::GpuFeedbackDiagnostic,
            "gpu-recursive-feedback-diagnostic" | "gpu-recursive-feedback" => {
                Self::GpuRecursiveFeedbackDiagnostic
            }
            "gpu-readback-diagnostic" | "gpu-capture-readback-diagnostic" => {
                Self::GpuReadbackDiagnostic
            }
            "hardware-buffer-witness" | "" => Self::HardwareBufferWitness,
            _ => Self::HardwareBufferWitness,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::HardwareBufferWitness => "hardware-buffer-witness",
            Self::GpuFeedbackDiagnostic => "gpu-feedback-diagnostic",
            Self::GpuRecursiveFeedbackDiagnostic => "gpu-recursive-feedback-diagnostic",
            Self::GpuReadbackDiagnostic => "gpu-readback-diagnostic",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeDisplayCompositeFeedbackProjection {
    MetadataTargetScreenUv,
    FullEyePeripheralStretch,
}

impl NativeDisplayCompositeFeedbackProjection {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "full-eye-peripheral-stretch" => Self::FullEyePeripheralStretch,
            "metadata-target-screen-uv" | "metadata-target-guide-texture" | "" => {
                Self::MetadataTargetScreenUv
            }
            _ => Self::MetadataTargetScreenUv,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::MetadataTargetScreenUv => "metadata-target-screen-uv",
            Self::FullEyePeripheralStretch => "full-eye-peripheral-stretch",
        }
    }
}
