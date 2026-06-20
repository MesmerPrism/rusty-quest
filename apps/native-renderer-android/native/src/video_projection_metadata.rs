//! Metadata-owned projection contract for fullscreen stereo video frames.
//!
//! The decoded media stream carries its source resolution, stereo layout, and
//! source UV mapping. Downstream projection scale can still alter the target
//! rectangle without rewriting the media source contract.

use crate::{
    native_renderer_video_projection_options::NativeVideoProjectionSettings,
    projection_rect::TargetRect,
};

const VIDEO_PROJECTION_METADATA_SCHEMA: &str =
    "rusty.quest.native_renderer.video_projection_metadata.v1";
const DEFAULT_SOURCE_SAMPLING_MODE: &str = "mediacodec-decoded-video-private-raster";
const DEFAULT_RASTER_ORIENTATION: &str = "top-left-origin-y-down";
const DEFAULT_ORIENTATION_KIND: &str = "android-mediacodec-surface";
const DEFAULT_UPRIGHT_MARKER: &str = "mediacodec-surface-native-upright";
const DEFAULT_METADATA_SOURCE: &str = "runtime-video-projection-settings-derived-metadata";
const DEFAULT_SOURCE_SAMPLE_Y_FLIP_REASON: &str = "android-mediacodec-decoder-output-surface";

#[derive(Clone, Debug)]
pub(crate) struct VideoProjectionMetadata {
    pub(crate) requested_width: u32,
    pub(crate) requested_height: u32,
    pub(crate) full_source_aspect_ratio: f32,
    pub(crate) per_eye_aspect_ratio: f32,
    pub(crate) source_sampling_mode: &'static str,
    pub(crate) left_source_uv_rect: TargetRect,
    pub(crate) right_source_uv_rect: TargetRect,
    pub(crate) left_target_rect: TargetRect,
    pub(crate) right_target_rect: TargetRect,
    pub(crate) target_footprint_default: bool,
    pub(crate) source_sample_y_flip: f32,
    pub(crate) source_sample_transform: &'static str,
    pub(crate) source_sample_y_flip_reason: &'static str,
    pub(crate) raster_orientation: &'static str,
    pub(crate) orientation_kind: &'static str,
    pub(crate) upright_marker: &'static str,
    pub(crate) metadata_source: &'static str,
    pub(crate) stereo_layout: &'static str,
    pub(crate) target: &'static str,
}

impl VideoProjectionMetadata {
    pub(crate) fn from_settings(settings: &NativeVideoProjectionSettings) -> Self {
        let requested_width = settings.width.max(1);
        let requested_height = settings.height.max(1);
        Self {
            requested_width,
            requested_height,
            full_source_aspect_ratio: requested_width as f32 / requested_height as f32,
            per_eye_aspect_ratio: settings
                .stereo_layout
                .per_eye_aspect_ratio(requested_width, requested_height),
            source_sampling_mode: DEFAULT_SOURCE_SAMPLING_MODE,
            left_source_uv_rect: settings.stereo_layout.source_uv_rect_for_eye(0),
            right_source_uv_rect: settings.stereo_layout.source_uv_rect_for_eye(1),
            left_target_rect: TargetRect::UNIT,
            right_target_rect: TargetRect::UNIT,
            target_footprint_default: true,
            source_sample_y_flip: 0.0,
            source_sample_transform: "identity-top-left-video-raster",
            source_sample_y_flip_reason: DEFAULT_SOURCE_SAMPLE_Y_FLIP_REASON,
            raster_orientation: DEFAULT_RASTER_ORIENTATION,
            orientation_kind: DEFAULT_ORIENTATION_KIND,
            upright_marker: DEFAULT_UPRIGHT_MARKER,
            metadata_source: DEFAULT_METADATA_SOURCE,
            stereo_layout: settings.stereo_layout.marker_value(),
            target: settings.target.marker_value(),
        }
    }

    pub(crate) fn source_rect_for_eye(&self, eye_index: usize) -> TargetRect {
        if eye_index == 0 {
            self.left_source_uv_rect
        } else {
            self.right_source_uv_rect
        }
    }

    pub(crate) fn target_rect_for_eye(&self, eye_index: usize) -> TargetRect {
        if eye_index == 0 {
            self.left_target_rect
        } else {
            self.right_target_rect
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "schema={} sourceSamplingMode={} projectionContentMappingMode=stereo-video-raster sourceResolution={}x{} fullSourceAspectRatio={:.6} perEyeAspectRatio={:.6} stereoLayout={} leftSourceUvRect={} rightSourceUvRect={} targetCoordinateSpace=display-eye-screen-uv videoProjectionTarget={} leftTargetScreenUvRect={} rightTargetScreenUvRect={} targetClipPolicy=clip-to-visible-eye targetFootprintMetadataSource=video-projection-runtime-metadata targetFootprintDefault={} orientationKind={} rasterOrientation={} uprightMarker={} orientationMetadataSource={} sourceSampleYFlip={:.1} sourceSampleYFlipReason={} sourceSampleTransformStage=pre-texture-sample sourceSampleTransform={} rendererSurfaceUvOrigin=native-vulkan-fullscreen-triangle videoScreenUvOrigin=top-left-origin-y-down dataInputMetadataAuthority=video-projection-stream downstreamProjectionScaleAuthority=projection-target-state",
            VIDEO_PROJECTION_METADATA_SCHEMA,
            marker_token(self.source_sampling_mode),
            self.requested_width,
            self.requested_height,
            self.full_source_aspect_ratio,
            self.per_eye_aspect_ratio,
            marker_token(self.stereo_layout),
            self.left_source_uv_rect.as_xywh_token(),
            self.right_source_uv_rect.as_xywh_token(),
            marker_token(self.target),
            self.left_target_rect.as_xywh_token(),
            self.right_target_rect.as_xywh_token(),
            self.target_footprint_default,
            marker_token(self.orientation_kind),
            marker_token(self.raster_orientation),
            marker_token(self.upright_marker),
            marker_token(self.metadata_source),
            self.source_sample_y_flip,
            marker_token(self.source_sample_y_flip_reason),
            marker_token(self.source_sample_transform),
        )
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

#[cfg(test)]
mod tests {
    use crate::{
        native_renderer_options::{
            NativeVideoProjectionSettings, NativeVideoProjectionSource,
            NativeVideoProjectionStereoLayout, NativeVideoProjectionTarget,
        },
        video_projection_metadata::VideoProjectionMetadata,
    };

    #[test]
    fn derives_sbs_source_rects_from_video_settings() {
        let metadata = VideoProjectionMetadata::from_settings(&NativeVideoProjectionSettings {
            enabled: true,
            source: NativeVideoProjectionSource::AppPrivateFile,
            path: "video/noodletest-sbs.mp4".to_string(),
            stereo_layout: NativeVideoProjectionStereoLayout::SideBySideLeftRight,
            width: 3840,
            height: 1920,
            max_images: 3,
            fps_cap: 30,
            looping: true,
            target: NativeVideoProjectionTarget::FullEye,
            opacity: 1.0,
            high_rate_json_payload: false,
        });

        assert_eq!(
            metadata.source_rect_for_eye(0).as_xywh_token(),
            "0.000000,0.000000,0.500000,1.000000"
        );
        assert_eq!(
            metadata.source_rect_for_eye(1).as_xywh_token(),
            "0.500000,0.000000,0.500000,1.000000"
        );
        assert_eq!(
            metadata.target_rect_for_eye(0).as_xywh_token(),
            "0.000000,0.000000,1.000000,1.000000"
        );
        assert!((metadata.per_eye_aspect_ratio - 1.0).abs() < 0.001);
    }
}
