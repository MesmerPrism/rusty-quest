#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::sync::{LazyLock, Mutex};

use crate::marker_token;
use crate::spatial_video_projection_marker::log_spatial_video_projection_marker as log_marker;

static SPATIAL_VIDEO_PROJECTION_SETTINGS: LazyLock<Mutex<SpatialVideoProjectionSettings>> =
    LazyLock::new(|| Mutex::new(SpatialVideoProjectionSettings::default()));

#[derive(Clone, Debug)]
pub(crate) struct SpatialVideoProjectionSettings {
    pub(crate) enabled: bool,
    pub(crate) path: String,
    pub(crate) stereo_layout: SpatialVideoProjectionStereoLayout,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) max_images: u32,
    pub(crate) fps_cap: u32,
    pub(crate) looping: bool,
    pub(crate) opacity: f32,
    pub(crate) high_rate_json_payload: bool,
}

impl Default for SpatialVideoProjectionSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            path: String::new(),
            stereo_layout: SpatialVideoProjectionStereoLayout::SideBySideLeftRight,
            width: 3840,
            height: 1920,
            max_images: 3,
            fps_cap: 30,
            looping: true,
            opacity: 1.0,
            high_rate_json_payload: false,
        }
    }
}

impl SpatialVideoProjectionSettings {
    pub(crate) fn active(&self) -> bool {
        self.enabled && !self.high_rate_json_payload && !self.path.trim().is_empty()
    }

    pub(crate) fn source_rect_for_eye(&self, eye_index: usize) -> [f32; 4] {
        self.stereo_layout.source_rect_for_eye(eye_index)
    }

    pub(crate) fn target_rect_for_eye(&self, eye_index: usize) -> [f32; 4] {
        if eye_index == 0 {
            [0.0, 0.0, 0.5, 1.0]
        } else {
            [0.5, 0.0, 0.5, 1.0]
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "videoProjectionEnabled={} spatialVideoProjectionEnabled={} videoProjectionSource=app-private-or-device-local-file videoProjectionPath={} videoProjectionPathProvided={} videoProjectionWidth={} videoProjectionHeight={} videoProjectionMaxImages={} videoProjectionFpsCap={} videoProjectionLooping={} videoProjectionStereoLayout={} videoProjectionTarget=packed-sbs-full-eye videoProjectionOpacity={:.3} videoProjectionHighRateJsonPayload={} videoProjectionStream=stereo_video videoProjectionSourceAuthority=android-mediacodec-surface-decoder videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer videoProjectionFramePlane=media-data-plane videoProjectionControlPlane=spatial-activity-runtime-property-or-intent-extra videoProjectionDecodePath=MediaCodec-to-Surface videoProjectionFormat=private videoProjectionLeftSourceUvRect={} videoProjectionRightSourceUvRect={} videoProjectionLeftTargetPackedUvRect={} videoProjectionRightTargetPackedUvRect={} spatialVideoProjectionSameSurfaceComposition=true videoProjectionComposedBeforeCamera=true cameraProjectionAlignmentPreserved=true nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false highRateJsonPayload={} rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false",
            self.enabled,
            self.enabled,
            marker_token(&self.path),
            !self.path.trim().is_empty(),
            self.width,
            self.height,
            self.max_images,
            self.fps_cap,
            self.looping,
            self.stereo_layout.marker_value(),
            self.opacity,
            self.high_rate_json_payload,
            rect_token(self.source_rect_for_eye(0)),
            rect_token(self.source_rect_for_eye(1)),
            rect_token(self.target_rect_for_eye(0)),
            rect_token(self.target_rect_for_eye(1)),
            self.high_rate_json_payload
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SpatialVideoProjectionStereoLayout {
    SideBySideLeftRight,
    Mono,
}

impl SpatialVideoProjectionStereoLayout {
    pub(crate) fn from_token(value: &str) -> Self {
        match normalized_token(value).as_str() {
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

    fn source_rect_for_eye(self, eye_index: usize) -> [f32; 4] {
        match (self, eye_index) {
            (Self::SideBySideLeftRight, 0) => [0.0, 0.0, 0.5, 1.0],
            (Self::SideBySideLeftRight, _) => [0.5, 0.0, 0.5, 1.0],
            (Self::Mono, _) => [0.0, 0.0, 1.0, 1.0],
        }
    }
}

pub(crate) fn spatial_video_projection_settings() -> SpatialVideoProjectionSettings {
    SPATIAL_VIDEO_PROJECTION_SETTINGS
        .lock()
        .map(|guard| guard.clone())
        .unwrap_or_default()
}

pub(crate) fn configure_spatial_video_projection(settings: SpatialVideoProjectionSettings) {
    if let Ok(mut guard) = SPATIAL_VIDEO_PROJECTION_SETTINGS.lock() {
        *guard = settings.clone();
    }
    log_marker(format!(
        "status=configured active={} {}",
        settings.active(),
        settings.marker_fields()
    ));
}

fn rect_token(rect: [f32; 4]) -> String {
    format!(
        "{:.6},{:.6},{:.6},{:.6}",
        rect[0], rect[1], rect[2], rect[3]
    )
}

fn normalized_token(value: &str) -> String {
    value.trim().to_ascii_lowercase().replace('_', "-")
}

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeConfigureSpatialVideoProjection(
    env: *mut jni::sys::JNIEnv,
    _thiz: jni::sys::jobject,
    enabled: jni::sys::jboolean,
    path: jni::sys::jstring,
    stereo_layout: jni::sys::jstring,
    width: jni::sys::jint,
    height: jni::sys::jint,
    max_images: jni::sys::jint,
    fps_cap: jni::sys::jint,
    looping: jni::sys::jboolean,
    opacity: jni::sys::jfloat,
    high_rate_json_payload: jni::sys::jboolean,
) -> i64 {
    let mut mask = 1_i64;
    let path = jstring_to_string(env, path);
    let stereo_layout_token = jstring_to_string(env, stereo_layout);
    if enabled != 0 {
        mask |= 1 << 1;
    }
    if !path.trim().is_empty() {
        mask |= 1 << 2;
    }
    let settings = SpatialVideoProjectionSettings {
        enabled: enabled != 0,
        path,
        stereo_layout: SpatialVideoProjectionStereoLayout::from_token(&stereo_layout_token),
        width: (width.max(320) as u32).min(4096),
        height: (height.max(240) as u32).min(4096),
        max_images: (max_images.max(2) as u32).min(6),
        fps_cap: (fps_cap.max(1) as u32).min(90),
        looping: looping != 0,
        opacity: (opacity as f32).clamp(0.0, 1.0),
        high_rate_json_payload: high_rate_json_payload != 0,
    };
    if settings.active() {
        mask |= 1 << 3;
    }
    configure_spatial_video_projection(settings);
    mask
}

#[cfg(target_os = "android")]
fn jstring_to_string(env: *mut jni::sys::JNIEnv, value: jni::sys::jstring) -> String {
    use jni::objects::JString;

    if env.is_null() || value.is_null() {
        return String::new();
    }
    let mut env = match unsafe { jni::JNIEnv::from_raw(env) } {
        Ok(env) => env,
        Err(_) => return String::new(),
    };
    let value = unsafe { JString::from_raw(value) };
    env.get_string(&value)
        .map(|text| text.to_string_lossy().into_owned())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_empty_path_is_not_active() {
        let settings = SpatialVideoProjectionSettings::default();
        assert!(!settings.active());
        assert!(settings
            .marker_fields()
            .contains("videoProjectionPathProvided=false"));
    }

    #[test]
    fn side_by_side_rects_match_packed_surface_contract() {
        let settings = SpatialVideoProjectionSettings {
            enabled: true,
            path: "video/staged-stereo-source".to_string(),
            ..SpatialVideoProjectionSettings::default()
        };
        assert!(settings.active());
        assert_eq!(settings.source_rect_for_eye(0), [0.0, 0.0, 0.5, 1.0]);
        assert_eq!(settings.source_rect_for_eye(1), [0.5, 0.0, 0.5, 1.0]);
        assert_eq!(settings.target_rect_for_eye(0), [0.0, 0.0, 0.5, 1.0]);
        assert_eq!(settings.target_rect_for_eye(1), [0.5, 0.0, 0.5, 1.0]);
        assert!(settings
            .marker_fields()
            .contains("cameraProjectionAlignmentPreserved=true"));
    }
}
