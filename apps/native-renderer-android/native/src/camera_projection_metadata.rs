//! Metadata-owned target footprint and source-orientation contract.

const TARGET_SCREEN_FOOTPRINT_SCHEMA: &str = "rusty.optics.target_screen_footprint.v1";
const DEFAULT_GEOMETRY_PROFILE: &str = "camera-projection";
const DEFAULT_SOURCE_SAMPLING_MODE: &str = "target-local-raster";
const DEFAULT_LEFT_TARGET_RECT: &str = "0.171875;0.21875;0.75;0.65625";
const DEFAULT_RIGHT_TARGET_RECT: &str = "0.078125;0.21875;0.75;0.671875";
const DEFAULT_RASTER_ORIENTATION: &str = "top-left-origin-y-down";
const DEFAULT_ORIENTATION_KIND: &str = "camera-frame";
const DEFAULT_UPRIGHT_MARKER: &str = "camera-native-upright";
const DEFAULT_METADATA_SOURCE: &str = "generated-direct-camera2-stimulus-metadata";
const DEFAULT_SOURCE_SAMPLE_Y_FLIP_REASON: &str =
    "direct-camera2-generated-stimulus-top-left-raster-matches-native-video-sampler-origin";

const ENV_TARGET_RECT: &str = "RUSTY_QUEST_NATIVE_RENDERER_CAMERA_TARGET_SCREEN_UV_RECT";
const ENV_LEFT_TARGET_RECT: &str = "RUSTY_QUEST_NATIVE_RENDERER_CAMERA_LEFT_TARGET_SCREEN_UV_RECT";
const ENV_RIGHT_TARGET_RECT: &str =
    "RUSTY_QUEST_NATIVE_RENDERER_CAMERA_RIGHT_TARGET_SCREEN_UV_RECT";
const ENV_SOURCE_SAMPLING_MODE: &str = "RUSTY_QUEST_NATIVE_RENDERER_CAMERA_SOURCE_SAMPLING_MODE";
const ENV_GEOMETRY_PROFILE: &str = "RUSTY_QUEST_NATIVE_RENDERER_CAMERA_PROJECTION_GEOMETRY_PROFILE";
const ENV_SOURCE_SAMPLE_Y_FLIP: &str = "RUSTY_QUEST_NATIVE_RENDERER_SOURCE_SAMPLE_Y_FLIP";

const PROP_TARGET_RECT: &str = "debug.rustyquest.native_renderer.camera.target.screen.uv.rect";
const PROP_LEFT_TARGET_RECT: &str =
    "debug.rustyquest.native_renderer.camera.left.target.screen.uv.rect";
const PROP_RIGHT_TARGET_RECT: &str =
    "debug.rustyquest.native_renderer.camera.right.target.screen.uv.rect";
const PROP_SOURCE_SAMPLING_MODE: &str =
    "debug.rustyquest.native_renderer.camera.source.sampling.mode";
const PROP_GEOMETRY_PROFILE: &str =
    "debug.rustyquest.native_renderer.camera.projection.geometry.profile";
const PROP_SOURCE_SAMPLE_Y_FLIP: &str = "debug.rustyquest.native_renderer.source.sample.y.flip";

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct TargetRect {
    pub(crate) x: f32,
    pub(crate) y: f32,
    pub(crate) width: f32,
    pub(crate) height: f32,
}

impl TargetRect {
    const UNIT: Self = Self {
        x: 0.0,
        y: 0.0,
        width: 1.0,
        height: 1.0,
    };

    const fn new(x: f32, y: f32, width: f32, height: f32) -> Self {
        Self {
            x,
            y,
            width,
            height,
        }
    }

    fn parse(text: &str) -> Option<Self> {
        let parts = text
            .split(|character| matches!(character, ',' | ';' | ' ' | '\t'))
            .filter(|part| !part.trim().is_empty())
            .filter_map(|part| part.trim().parse::<f32>().ok())
            .collect::<Vec<_>>();
        if parts.len() != 4 {
            return None;
        }
        let rect = Self::new(parts[0], parts[1], parts[2], parts[3]);
        rect.is_valid().then_some(rect)
    }

    pub(crate) fn as_xywh_token(self) -> String {
        format!(
            "{:.6},{:.6},{:.6},{:.6}",
            self.x, self.y, self.width, self.height
        )
    }

    pub(crate) fn is_valid(self) -> bool {
        self.x.is_finite()
            && self.y.is_finite()
            && self.width.is_finite()
            && self.height.is_finite()
            && self.x >= 0.0
            && self.y >= 0.0
            && self.width > 0.0
            && self.height > 0.0
            && self.x + self.width <= 1.0
            && self.y + self.height <= 1.0
    }
}

#[derive(Clone, Debug)]
pub(crate) struct CameraProjectionMetadata {
    pub(crate) projection_geometry_profile: String,
    pub(crate) source_sampling_mode: String,
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

impl CameraProjectionMetadata {
    pub(crate) fn load() -> Self {
        let shared_rect = read_text(PROP_TARGET_RECT, ENV_TARGET_RECT);
        let left_text = read_text(PROP_LEFT_TARGET_RECT, ENV_LEFT_TARGET_RECT)
            .or_else(|| shared_rect.clone())
            .unwrap_or_else(|| DEFAULT_LEFT_TARGET_RECT.to_string());
        let right_text = read_text(PROP_RIGHT_TARGET_RECT, ENV_RIGHT_TARGET_RECT)
            .or(shared_rect)
            .unwrap_or_else(|| DEFAULT_RIGHT_TARGET_RECT.to_string());
        let left_rect = TargetRect::parse(&left_text)
            .or_else(|| TargetRect::parse(DEFAULT_LEFT_TARGET_RECT))
            .unwrap_or(TargetRect::UNIT);
        let right_rect = TargetRect::parse(&right_text)
            .or_else(|| TargetRect::parse(DEFAULT_RIGHT_TARGET_RECT))
            .unwrap_or(TargetRect::UNIT);
        let source_sample_y_flip = read_text(PROP_SOURCE_SAMPLE_Y_FLIP, ENV_SOURCE_SAMPLE_Y_FLIP)
            .and_then(|value| parse_bool_float(&value))
            .unwrap_or(0.0);

        Self {
            projection_geometry_profile: read_text(PROP_GEOMETRY_PROFILE, ENV_GEOMETRY_PROFILE)
                .filter(|value| !value.trim().is_empty())
                .unwrap_or_else(|| DEFAULT_GEOMETRY_PROFILE.to_string()),
            source_sampling_mode: read_text(PROP_SOURCE_SAMPLING_MODE, ENV_SOURCE_SAMPLING_MODE)
                .filter(|value| !value.trim().is_empty())
                .unwrap_or_else(|| DEFAULT_SOURCE_SAMPLING_MODE.to_string()),
            left_rect,
            right_rect,
            target_footprint_default: left_text == DEFAULT_LEFT_TARGET_RECT
                && right_text == DEFAULT_RIGHT_TARGET_RECT,
            source_sample_y_flip,
            source_sample_transform: if source_sample_y_flip >= 0.5 {
                "metadata-raster-y-flip"
            } else {
                "identity-top-left-camera-raster"
            },
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
            "schema=rusty.quest.native_renderer.camera_projection_metadata.v1 projectionGeometryProfile={} sourceSamplingMode={} projectionContentMappingMode=target-local-raster targetFootprintSchema={} targetCoordinateSpace=display-eye-screen-uv leftTargetScreenUvRect={} rightTargetScreenUvRect={} targetClipPolicy=clip-to-visible-eye targetFootprintMetadataSource=native-direct-camera-target-screen-uv-runtime targetFootprintDefault={} orientationKind={} rasterOrientation={} uprightMarker={} orientationMetadataSource={} orientationDefault=false sourceSampleYFlip={:.1} sourceSampleYFlipReason={} sourceSampleTransformStage=post-homography-pre-texture-sample sourceSampleTransform={} rendererSurfaceUvOrigin=native-vulkan-fullscreen-triangle displayScreenUvOrigin=top-left-origin-y-down",
            marker_token(&self.projection_geometry_profile),
            marker_token(&self.source_sampling_mode),
            TARGET_SCREEN_FOOTPRINT_SCHEMA,
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

fn parse_bool_float(value: &str) -> Option<f32> {
    match value.trim().to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" | "on" | "flip" | "flipped" => Some(1.0),
        "0" | "false" | "no" | "off" | "identity" | "none" => Some(0.0),
        value => value
            .parse::<f32>()
            .ok()
            .filter(|value| value.is_finite())
            .map(|value| value.clamp(0.0, 1.0)),
    }
}

fn read_text(property_name: &str, env_name: &str) -> Option<String> {
    android_property(property_name)
        .or_else(|| std::env::var(env_name).ok())
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    let value = property.value()?.trim().to_string();
    (!value.is_empty()).then_some(value)
}

#[cfg(not(target_os = "android"))]
fn android_property(_name: &str) -> Option<String> {
    None
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
    use super::{parse_bool_float, TargetRect, DEFAULT_LEFT_TARGET_RECT};

    #[test]
    fn parses_reference_default_target_rect() {
        let rect = TargetRect::parse(DEFAULT_LEFT_TARGET_RECT).expect("rect parses");
        assert!((rect.x - 0.171875).abs() < 0.000_001);
        assert!((rect.y - 0.21875).abs() < 0.000_001);
        assert!((rect.width - 0.75).abs() < 0.000_001);
        assert!((rect.height - 0.65625).abs() < 0.000_001);
    }

    #[test]
    fn rejects_out_of_bounds_target_rect() {
        assert!(TargetRect::parse("0.5;0.5;0.75;0.75").is_none());
        assert!(TargetRect::parse("0.1;0.1;0.0;0.3").is_none());
    }

    #[test]
    fn parses_flip_values_as_metadata() {
        assert_eq!(parse_bool_float("true"), Some(1.0));
        assert_eq!(parse_bool_float("identity"), Some(0.0));
        assert_eq!(parse_bool_float("0.25"), Some(0.25));
    }
}
