//! Fullscreen stereo video settings for the native renderer.
//!
//! The runtime profile owns low-rate source metadata. Decoded video frames stay
//! on the Android media/GPU path and are never transported through JSON.

use crate::{
    native_renderer_properties::{
        PROP_VIDEO_PROJECTION_ENABLED, PROP_VIDEO_PROJECTION_FPS_CAP, PROP_VIDEO_PROJECTION_HEIGHT,
        PROP_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD, PROP_VIDEO_PROJECTION_LOOPING,
        PROP_VIDEO_PROJECTION_MAX_IMAGES, PROP_VIDEO_PROJECTION_OPACITY,
        PROP_VIDEO_PROJECTION_PATH, PROP_VIDEO_PROJECTION_SOURCE,
        PROP_VIDEO_PROJECTION_STEREO_LAYOUT, PROP_VIDEO_PROJECTION_TARGET,
        PROP_VIDEO_PROJECTION_WIDTH,
    },
    native_renderer_property_values::{
        bool_value, f32_clamped_value, normalized_property, u32_value,
    },
    projection_rect::TargetRect,
};

const DEFAULT_APP_PRIVATE_VIDEO_PATH: &str = "video/noodletest-sbs.mp4";

#[derive(Clone, Debug)]
pub(crate) struct NativeVideoProjectionSettings {
    pub(crate) enabled: bool,
    pub(crate) source: NativeVideoProjectionSource,
    pub(crate) path: String,
    pub(crate) stereo_layout: NativeVideoProjectionStereoLayout,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) max_images: u32,
    pub(crate) fps_cap: u32,
    pub(crate) looping: bool,
    pub(crate) target: NativeVideoProjectionTarget,
    pub(crate) opacity: f32,
    pub(crate) high_rate_json_payload: bool,
}

impl NativeVideoProjectionSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let path = lookup(PROP_VIDEO_PROJECTION_PATH)
            .map(|value| value.trim().to_owned())
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| DEFAULT_APP_PRIVATE_VIDEO_PATH.to_owned());
        Self {
            enabled: bool_value(lookup(PROP_VIDEO_PROJECTION_ENABLED), false),
            source: NativeVideoProjectionSource::from_property(lookup(
                PROP_VIDEO_PROJECTION_SOURCE,
            )),
            path,
            stereo_layout: NativeVideoProjectionStereoLayout::from_property(lookup(
                PROP_VIDEO_PROJECTION_STEREO_LAYOUT,
            )),
            width: u32_value(lookup(PROP_VIDEO_PROJECTION_WIDTH), 3840, 320, 4096),
            height: u32_value(lookup(PROP_VIDEO_PROJECTION_HEIGHT), 1920, 240, 4096),
            max_images: u32_value(lookup(PROP_VIDEO_PROJECTION_MAX_IMAGES), 3, 2, 6),
            fps_cap: u32_value(lookup(PROP_VIDEO_PROJECTION_FPS_CAP), 30, 1, 90),
            looping: bool_value(lookup(PROP_VIDEO_PROJECTION_LOOPING), true),
            target: NativeVideoProjectionTarget::from_property(lookup(
                PROP_VIDEO_PROJECTION_TARGET,
            )),
            opacity: f32_clamped_value(lookup(PROP_VIDEO_PROJECTION_OPACITY), 1.0, 0.0, 1.0),
            high_rate_json_payload: bool_value(
                lookup(PROP_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD),
                false,
            ),
        }
    }

    pub(crate) fn active(&self) -> bool {
        self.enabled && !self.high_rate_json_payload && !self.path.trim().is_empty()
    }

    pub(crate) fn marker_fields(&self) -> String {
        let left_uv = self.stereo_layout.source_uv_rect_for_eye(0);
        let right_uv = self.stereo_layout.source_uv_rect_for_eye(1);
        format!(
            "videoProjectionEnabled={} videoProjectionSource={} videoProjectionPath={} videoProjectionWidth={} videoProjectionHeight={} videoProjectionMaxImages={} videoProjectionFpsCap={} videoProjectionLooping={} videoProjectionStereoLayout={} videoProjectionTarget={} videoProjectionOpacity={:.3} videoProjectionHighRateJsonPayload={} videoProjectionStream=stereo_video videoProjectionSourceAuthority=android-mediacodec-surface-decoder videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer videoProjectionFramePlane=media-data-plane videoProjectionControlPlane=android-property-profile videoProjectionDecodePath=MediaCodec-to-Surface videoProjectionFormat=private videoProjectionLeftSourceUvRect={} videoProjectionRightSourceUvRect={} nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false highRateJsonPayload={} rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false",
            self.enabled,
            self.source.marker_value(),
            marker_token(&self.path),
            self.width,
            self.height,
            self.max_images,
            self.fps_cap,
            self.looping,
            self.stereo_layout.marker_value(),
            self.target.marker_value(),
            self.opacity,
            self.high_rate_json_payload,
            left_uv.as_xywh_token(),
            right_uv.as_xywh_token(),
            self.high_rate_json_payload
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeVideoProjectionSource {
    AppPrivateFile,
}

impl NativeVideoProjectionSource {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "app-private-file" | "app-file" | "file" | "" => Self::AppPrivateFile,
            _ => Self::AppPrivateFile,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::AppPrivateFile => "app-private-file",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeVideoProjectionStereoLayout {
    SideBySideLeftRight,
    Mono,
}

impl NativeVideoProjectionStereoLayout {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "mono" | "mono-full" | "monoscopic" => Self::Mono,
            "side-by-side-left-right" | "sbs-left-right" | "sbs" | "" => Self::SideBySideLeftRight,
            _ => Self::SideBySideLeftRight,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::SideBySideLeftRight => "side-by-side-left-right",
            Self::Mono => "mono",
        }
    }

    pub(crate) fn source_uv_rect_for_eye(self, eye_index: usize) -> TargetRect {
        match (self, eye_index) {
            (Self::SideBySideLeftRight, 0) => TargetRect::new(0.0, 0.0, 0.5, 1.0),
            (Self::SideBySideLeftRight, _) => TargetRect::new(0.5, 0.0, 0.5, 1.0),
            (Self::Mono, _) => TargetRect::UNIT,
        }
    }

    pub(crate) fn per_eye_aspect_ratio(self, width: u32, height: u32) -> f32 {
        let width = width.max(1) as f32;
        let height = height.max(1) as f32;
        match self {
            Self::SideBySideLeftRight => (width * 0.5) / height,
            Self::Mono => width / height,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeVideoProjectionTarget {
    FullEye,
}

impl NativeVideoProjectionTarget {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "full-eye" | "full-screen" | "fullscreen" | "" => Self::FullEye,
            _ => Self::FullEye,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::FullEye => "full-eye",
        }
    }
}

fn marker_token(value: &str) -> String {
    value
        .trim()
        .replace('\0', "")
        .replace(|character: char| character.is_whitespace(), "_")
        .replace(',', "_")
        .replace(';', "_")
}
