#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::sync::{
    atomic::{AtomicU64, Ordering},
    LazyLock, Mutex,
};

use crate::marker_token;
use crate::spatial_video_projection_marker::log_spatial_video_projection_marker as log_marker;

static SPATIAL_VIDEO_PROJECTION_SETTINGS: LazyLock<Mutex<SpatialVideoProjectionSettings>> =
    LazyLock::new(|| Mutex::new(SpatialVideoProjectionSettings::default()));
static SPATIAL_VIDEO_MEDIA_SOURCE_GENERATION: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Debug)]
pub(crate) struct SpatialVideoProjectionSettings {
    pub(crate) enabled: bool,
    pub(crate) source: SpatialVideoProjectionSource,
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
            source: SpatialVideoProjectionSource::FileBacked,
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
        self.enabled
            && !self.high_rate_json_payload
            && (self.source.stream_backed() || !self.path.trim().is_empty())
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
            "videoProjectionEnabled={} spatialVideoProjectionEnabled={} videoProjectionSource={} videoProjectionStreamBacked={} videoProjectionPath={} videoProjectionPathProvided={} videoProjectionWidth={} videoProjectionHeight={} videoProjectionMaxImages={} videoProjectionFpsCap={} videoProjectionLooping={} videoProjectionStereoLayout={} videoProjectionTarget=packed-sbs-full-eye videoProjectionOpacity={:.3} videoProjectionHighRateJsonPayload={} videoProjectionStream=stereo_video videoProjectionSourceAuthority=android-mediacodec-surface-decoder videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer videoProjectionFramePlane=media-data-plane videoProjectionControlPlane=spatial-activity-runtime-property-or-intent-extra videoProjectionDecodePath=MediaCodec-to-Surface videoProjectionFormat=private videoProjectionLeftSourceUvRect={} videoProjectionRightSourceUvRect={} videoProjectionLeftTargetPackedUvRect={} videoProjectionRightTargetPackedUvRect={} spatialVideoProjectionSameSurfaceComposition=true videoProjectionComposedBeforeCamera=true cameraProjectionAlignmentPreserved=true nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false highRateJsonPayload={} rawCamera=false passthroughTexture=false environmentDepth=false geometryWitness=false",
            self.enabled,
            self.enabled,
            self.source.marker_value(),
            self.source.stream_backed(),
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
pub(crate) enum SpatialVideoProjectionSource {
    FileBacked,
    BrokerRmanvid1,
    PeerPackedStereo,
}

impl SpatialVideoProjectionSource {
    fn from_token(value: &str) -> Self {
        match normalized_token(value).as_str() {
            "broker-rmanvid1" => Self::BrokerRmanvid1,
            "peer-packed-stereo" => Self::PeerPackedStereo,
            _ => Self::FileBacked,
        }
    }

    fn stream_backed(self) -> bool {
        matches!(self, Self::BrokerRmanvid1 | Self::PeerPackedStereo)
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::FileBacked => "app-private-or-device-local-file",
            Self::BrokerRmanvid1 => "broker-rmanvid1",
            Self::PeerPackedStereo => "peer-packed-stereo",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SpatialVideoProjectionStereoLayout {
    SideBySideLeftRight,
    TopBottom,
    Mono,
}

impl SpatialVideoProjectionStereoLayout {
    pub(crate) fn from_token(value: &str) -> Self {
        match normalized_token(value).as_str() {
            "mono" | "mono-full" | "monoscopic" => Self::Mono,
            "top-bottom" | "top-bottom-left-right" | "tb" | "over-under" => Self::TopBottom,
            "side-by-side-left-right" | "sbs-left-right" | "sbs" | "" => Self::SideBySideLeftRight,
            _ => Self::SideBySideLeftRight,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::SideBySideLeftRight => "side-by-side-left-right",
            Self::TopBottom => "top-bottom-left-right",
            Self::Mono => "mono",
        }
    }

    fn source_rect_for_eye(self, eye_index: usize) -> [f32; 4] {
        match (self, eye_index) {
            (Self::SideBySideLeftRight, 0) => [0.0, 0.0, 0.5, 1.0],
            (Self::SideBySideLeftRight, _) => [0.5, 0.0, 0.5, 1.0],
            (Self::TopBottom, 0) => [0.0, 0.0, 1.0, 0.5],
            (Self::TopBottom, _) => [0.0, 0.5, 1.0, 0.5],
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
        if guard.source != settings.source
            || guard.path != settings.path
            || guard.stereo_layout != settings.stereo_layout
            || guard.width != settings.width
            || guard.height != settings.height
        {
            SPATIAL_VIDEO_MEDIA_SOURCE_GENERATION.fetch_add(1, Ordering::AcqRel);
        }
        *guard = settings.clone();
    }
    log_marker(format!(
        "status=configured active={} {}",
        settings.active(),
        settings.marker_fields()
    ));
}

pub(crate) fn spatial_video_media_source_generation() -> u64 {
    SPATIAL_VIDEO_MEDIA_SOURCE_GENERATION.load(Ordering::Acquire)
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

pub(crate) fn spatial_video_projection_import_cache_limit(max_images: i32) -> usize {
    max_images.clamp(2, 6) as usize
}

pub(crate) fn should_log_spatial_video_projection_frame(frame_index: u64) -> bool {
    frame_index == 1 || frame_index % 60 == 0
}

pub(crate) fn should_log_spatial_video_projection_import(
    import_miss_count: u64,
    import_cache_limit: usize,
) -> bool {
    import_miss_count == 1
        || import_miss_count == import_cache_limit.saturating_add(1) as u64
        || import_miss_count % 60 == 0
}

const FPS_CAP_TIMESTAMP_ROUNDING_TOLERANCE_NS: i64 = 1_000;

pub(crate) fn should_drop_spatial_video_projection_timestamp(
    previous_timestamp_ns: i64,
    timestamp_ns: i64,
    fps_cap: i32,
) -> bool {
    if previous_timestamp_ns <= 0 || timestamp_ns <= 0 {
        return false;
    }
    let minimum_gap_ns = 1_000_000_000_i64 / i64::from(fps_cap.max(1));
    timestamp_ns
        .saturating_sub(previous_timestamp_ns)
        .saturating_add(FPS_CAP_TIMESTAMP_ROUNDING_TOLERANCE_NS)
        < minimum_gap_ns
}

#[cfg(target_os = "android")]
#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeConfigureSpatialVideoProjection(
    env: *mut jni::sys::JNIEnv,
    _thiz: jni::sys::jobject,
    enabled: jni::sys::jboolean,
    source: jni::sys::jstring,
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
    let source = SpatialVideoProjectionSource::from_token(&jstring_to_string(env, source));
    let path = jstring_to_string(env, path);
    let stereo_layout_token = jstring_to_string(env, stereo_layout);
    if enabled != 0 {
        mask |= 1 << 1;
    }
    if !path.trim().is_empty() {
        mask |= 1 << 2;
    }
    if source.stream_backed() {
        mask |= 1 << 4;
    }
    let settings = SpatialVideoProjectionSettings {
        enabled: enabled != 0,
        source,
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
    fn peer_stream_is_active_without_a_file_path() {
        let settings = SpatialVideoProjectionSettings {
            enabled: true,
            source: SpatialVideoProjectionSource::from_token("peer-packed-stereo"),
            ..SpatialVideoProjectionSettings::default()
        };
        assert!(settings.active());
        assert!(settings
            .marker_fields()
            .contains("videoProjectionSource=peer-packed-stereo"));
        assert!(settings
            .marker_fields()
            .contains("videoProjectionStreamBacked=true"));
    }

    #[test]
    fn unknown_source_without_a_file_path_stays_inactive() {
        let settings = SpatialVideoProjectionSettings {
            enabled: true,
            source: SpatialVideoProjectionSource::from_token("unknown-source"),
            ..SpatialVideoProjectionSettings::default()
        };
        assert!(!settings.active());
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

    #[test]
    fn top_bottom_rects_map_each_eye_into_the_packed_surface_contract() {
        let settings = SpatialVideoProjectionSettings {
            enabled: true,
            path: "video/staged-top-bottom-source".to_string(),
            stereo_layout: SpatialVideoProjectionStereoLayout::from_token("top-bottom"),
            ..SpatialVideoProjectionSettings::default()
        };
        assert!(settings.active());
        assert_eq!(settings.source_rect_for_eye(0), [0.0, 0.0, 1.0, 0.5]);
        assert_eq!(settings.source_rect_for_eye(1), [0.0, 0.5, 1.0, 0.5]);
        assert_eq!(settings.target_rect_for_eye(0), [0.0, 0.0, 0.5, 1.0]);
        assert_eq!(settings.target_rect_for_eye(1), [0.5, 0.0, 0.5, 1.0]);
        assert!(settings
            .marker_fields()
            .contains("videoProjectionStereoLayout=top-bottom-left-right"));
    }

    #[test]
    fn import_cache_never_retains_more_buffers_than_the_configured_reader_queue() {
        assert_eq!(spatial_video_projection_import_cache_limit(1), 2);
        assert_eq!(spatial_video_projection_import_cache_limit(2), 2);
        assert_eq!(spatial_video_projection_import_cache_limit(3), 3);
        assert_eq!(spatial_video_projection_import_cache_limit(6), 6);
        assert_eq!(spatial_video_projection_import_cache_limit(12), 6);
    }

    #[test]
    fn decoded_frame_receipts_keep_first_frame_and_one_periodic_witness() {
        assert!(should_log_spatial_video_projection_frame(1));
        assert!(!should_log_spatial_video_projection_frame(2));
        assert!(!should_log_spatial_video_projection_frame(59));
        assert!(should_log_spatial_video_projection_frame(60));
        assert!(should_log_spatial_video_projection_frame(120));
    }

    #[test]
    fn import_receipts_keep_first_import_first_eviction_and_periodic_witnesses() {
        assert!(should_log_spatial_video_projection_import(1, 8));
        assert!(!should_log_spatial_video_projection_import(8, 8));
        assert!(should_log_spatial_video_projection_import(9, 8));
        assert!(!should_log_spatial_video_projection_import(10, 8));
        assert!(should_log_spatial_video_projection_import(60, 8));
    }

    #[test]
    fn thirty_fps_microsecond_timestamps_are_not_dropped() {
        let timestamps = [
            1_000_000_000_i64,
            1_033_333_000,
            1_066_667_000,
            1_100_000_000,
        ];
        let mut previous = 0_i64;
        for timestamp in timestamps {
            assert!(!should_drop_spatial_video_projection_timestamp(
                previous, timestamp, 30,
            ));
            previous = timestamp;
        }
    }

    #[test]
    fn thirty_fps_cap_still_drops_intermediate_sixty_fps_timestamps() {
        let first = 1_000_000_000_i64;
        assert!(!should_drop_spatial_video_projection_timestamp(
            0, first, 30
        ));
        assert!(should_drop_spatial_video_projection_timestamp(
            first,
            1_016_667_000,
            30,
        ));
        assert!(!should_drop_spatial_video_projection_timestamp(
            first,
            1_033_333_000,
            30,
        ));
    }
}
