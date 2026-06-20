//! Metadata-owned projection contract for MediaProjection display-composite frames.
//!
//! This keeps stream geometry and sampling orientation with the display-composite
//! input instead of borrowing camera metadata or baking those assumptions into
//! the shader path.

use crate::{
    native_renderer_display_composite_options::NativeDisplayCompositeSettings,
    projection_rect::TargetRect,
};

const DISPLAY_COMPOSITE_METADATA_SCHEMA: &str =
    "rusty.quest.native_renderer.display_composite_projection_metadata.v1";
const DEFAULT_SOURCE_SAMPLING_MODE: &str = "display-composite-rgba-raster";
const DEFAULT_RASTER_ORIENTATION: &str = "top-left-origin-y-down";
const DEFAULT_ORIENTATION_KIND: &str = "android-display-composite";
const DEFAULT_UPRIGHT_MARKER: &str = "mediaprojection-native-upright";
const DEFAULT_METADATA_SOURCE: &str =
    "runtime-display-composite-settings-derived-projection-metadata";
const DEFAULT_SOURCE_SAMPLE_Y_FLIP_REASON: &str =
    "android-mediaprojection-rgba-display-composite-top-left-raster";
const DEFAULT_FEEDBACK_TARGET_MAX_WIDTH: f32 = 0.42;
const DEFAULT_FEEDBACK_TARGET_MAX_HEIGHT: f32 = 0.32;

#[derive(Clone, Debug)]
pub(crate) struct DisplayCompositeProjectionMetadata {
    pub(crate) requested_width: u32,
    pub(crate) requested_height: u32,
    pub(crate) content_aspect_ratio: f32,
    pub(crate) source_sampling_mode: &'static str,
    pub(crate) source_uv_rect: TargetRect,
    pub(crate) left_rect: TargetRect,
    pub(crate) right_rect: TargetRect,
    pub(crate) target_footprint_default: bool,
    pub(crate) source_sample_y_flip: f32,
    pub(crate) source_sample_transform: &'static str,
    pub(crate) source_sample_y_flip_reason: &'static str,
    pub(crate) raster_orientation: &'static str,
    pub(crate) orientation_kind: &'static str,
    pub(crate) upright_marker: &'static str,
    pub(crate) metadata_source: &'static str,
}

impl DisplayCompositeProjectionMetadata {
    pub(crate) fn from_settings(settings: NativeDisplayCompositeSettings) -> Self {
        let requested_width = settings.width.max(1);
        let requested_height = settings.height.max(1);
        let content_aspect_ratio = requested_width as f32 / requested_height as f32;
        let target_rect = default_target_rect_for_aspect(content_aspect_ratio);
        Self {
            requested_width,
            requested_height,
            content_aspect_ratio,
            source_sampling_mode: DEFAULT_SOURCE_SAMPLING_MODE,
            source_uv_rect: TargetRect::UNIT,
            left_rect: target_rect,
            right_rect: target_rect,
            target_footprint_default: true,
            source_sample_y_flip: 0.0,
            source_sample_transform: "identity-top-left-display-raster",
            source_sample_y_flip_reason: DEFAULT_SOURCE_SAMPLE_Y_FLIP_REASON,
            raster_orientation: DEFAULT_RASTER_ORIENTATION,
            orientation_kind: DEFAULT_ORIENTATION_KIND,
            upright_marker: DEFAULT_UPRIGHT_MARKER,
            metadata_source: DEFAULT_METADATA_SOURCE,
        }
    }

    pub(crate) fn rect_for_eye(&self, eye_index: usize) -> TargetRect {
        if eye_index == 0 {
            self.left_rect
        } else {
            self.right_rect
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "schema={} sourceSamplingMode={} projectionContentMappingMode=display-composite-raster sourceResolution={}x{} contentAspectRatio={:.6} sourceUvRect={} targetCoordinateSpace=display-eye-screen-uv leftTargetScreenUvRect={} rightTargetScreenUvRect={} targetClipPolicy=clip-to-visible-eye targetFootprintMetadataSource=display-composite-runtime-metadata targetFootprintDefault={} orientationKind={} rasterOrientation={} uprightMarker={} orientationMetadataSource={} sourceSampleYFlip={:.1} sourceSampleYFlipReason={} sourceSampleTransformStage=pre-texture-sample sourceSampleTransform={} rendererSurfaceUvOrigin=native-vulkan-fullscreen-triangle displayScreenUvOrigin=top-left-origin-y-down dataInputMetadataAuthority=display-composite-stream downstreamProjectionScaleAuthority=projection-target-state",
            DISPLAY_COMPOSITE_METADATA_SCHEMA,
            marker_token(self.source_sampling_mode),
            self.requested_width,
            self.requested_height,
            self.content_aspect_ratio,
            self.source_uv_rect.as_xywh_token(),
            self.left_rect.as_xywh_token(),
            self.right_rect.as_xywh_token(),
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

fn default_target_rect_for_aspect(aspect_ratio: f32) -> TargetRect {
    let aspect_ratio = aspect_ratio.clamp(0.25, 4.0);
    let max_width = DEFAULT_FEEDBACK_TARGET_MAX_WIDTH;
    let max_height = DEFAULT_FEEDBACK_TARGET_MAX_HEIGHT;
    let max_aspect = max_width / max_height;
    let (width, height) = if aspect_ratio >= max_aspect {
        (max_width, max_width / aspect_ratio)
    } else {
        (max_height * aspect_ratio, max_height)
    };
    TargetRect {
        x: (1.0 - width) * 0.5,
        y: (1.0 - height) * 0.5,
        width,
        height,
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
    use super::default_target_rect_for_aspect;

    #[test]
    fn derives_visible_target_from_display_aspect() {
        let rect = default_target_rect_for_aspect(16.0 / 9.0);
        assert!(rect.is_valid());
        assert!((rect.width / rect.height - 16.0 / 9.0).abs() < 0.001);
        assert!((rect.width - 0.42).abs() < 0.001);
        assert!((rect.height - 0.23625).abs() < 0.001);
        assert!((rect.x - 0.29).abs() < 0.001);
        assert!((rect.y - 0.381875).abs() < 0.001);
    }
}
