#![cfg_attr(not(any(target_os = "android", test)), allow(dead_code))]

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct CameraTargetRect {
    pub(crate) x: f32,
    pub(crate) y: f32,
    pub(crate) width: f32,
    pub(crate) height: f32,
}

impl CameraTargetRect {
    pub(crate) fn marker_token(self) -> String {
        format!(
            "{:.6};{:.6};{:.6};{:.6}",
            self.x, self.y, self.width, self.height
        )
    }

    fn as_push(self) -> [f32; 4] {
        [self.x, self.y, self.width, self.height]
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(crate) struct CameraHwbProjectionPush {
    pub(crate) left_rect: [f32; 4],
    pub(crate) right_rect: [f32; 4],
    pub(crate) params: [f32; 4],
}

pub(crate) const CAMERA_HWB_LEFT_CAMERA_ID: &str = "50";
pub(crate) const CAMERA_HWB_RIGHT_CAMERA_ID: &str = "51";
const CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE: f32 = 1.0;
const CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE: f32 = 0.25;
const CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE: f32 = 1.80;
const CAMERA_HWB_PROJECTION_TARGET_OFFSET_X: f32 = 0.0;
const CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y: f32 = 0.0;
const CAMERA_HWB_PROJECTION_BORDER_OPACITY: f32 = 0.0;
const CAMERA_HWB_LEFT_TARGET_RECT: CameraTargetRect = CameraTargetRect {
    x: 0.171875,
    y: 0.21875,
    width: 0.75,
    height: 0.65625,
};
const CAMERA_HWB_RIGHT_TARGET_RECT: CameraTargetRect = CameraTargetRect {
    x: 0.078125,
    y: 0.21875,
    width: 0.75,
    height: 0.671875,
};

pub(crate) fn effective_rect(
    base: CameraTargetRect,
    scale: f32,
    offset_x: f32,
    offset_y: f32,
) -> CameraTargetRect {
    let scale = scale.max(0.0001);
    let width = (base.width * scale).clamp(0.0001, 1.0);
    let height = (base.height * scale).clamp(0.0001, 1.0);
    let center_x = base.x + base.width * 0.5 + offset_x;
    let center_y = base.y + base.height * 0.5 + offset_y;
    let x = (center_x - width * 0.5).clamp(0.0, 1.0 - width);
    let y = (center_y - height * 0.5).clamp(0.0, 1.0 - height);
    CameraTargetRect {
        x,
        y,
        width,
        height,
    }
}

pub(crate) fn packed_left_rect(rect: CameraTargetRect) -> CameraTargetRect {
    CameraTargetRect {
        x: 0.5 * rect.x,
        y: rect.y,
        width: 0.5 * rect.width,
        height: rect.height,
    }
}

pub(crate) fn packed_right_rect(rect: CameraTargetRect) -> CameraTargetRect {
    CameraTargetRect {
        x: 0.5 + 0.5 * rect.x,
        y: rect.y,
        width: 0.5 * rect.width,
        height: rect.height,
    }
}

pub(crate) fn camera_hwb_projection_push() -> CameraHwbProjectionPush {
    let left_effective = left_effective_target_rect();
    let right_effective = right_effective_target_rect();
    CameraHwbProjectionPush {
        left_rect: packed_left_rect(left_effective).as_push(),
        right_rect: packed_right_rect(right_effective).as_push(),
        params: [CAMERA_HWB_PROJECTION_BORDER_OPACITY, 0.0, 0.0, 0.0],
    }
}

pub(crate) fn camera_hwb_projection_marker_fields() -> String {
    let left_effective = left_effective_target_rect();
    let right_effective = right_effective_target_rect();
    format!(
        "stereoSource=camera50-51 leftCameraId={} rightCameraId={} leftTargetScreenUvRect={} rightTargetScreenUvRect={} leftEffectiveTargetScreenUvRect={} rightEffectiveTargetScreenUvRect={} leftPackedEffectiveTargetScreenUvRect={} rightPackedEffectiveTargetScreenUvRect={} projectionTargetControlsEnabled=true projectionTargetLiveScale={:.4} projectionTargetTunedMaxScale={:.4} projectionTargetMinScale={:.4} projectionTargetMaxScale={:.4} projectionTargetOffsetUv={:.6},{:.6} borderOpacity={:.1} targetClipPolicy=clip-to-visible-eye projectionContentMappingMode=target-local-raster monoDuplicated=false",
        CAMERA_HWB_LEFT_CAMERA_ID,
        CAMERA_HWB_RIGHT_CAMERA_ID,
        CAMERA_HWB_LEFT_TARGET_RECT.marker_token(),
        CAMERA_HWB_RIGHT_TARGET_RECT.marker_token(),
        left_effective.marker_token(),
        right_effective.marker_token(),
        packed_left_rect(left_effective).marker_token(),
        packed_right_rect(right_effective).marker_token(),
        CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_X,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
        CAMERA_HWB_PROJECTION_BORDER_OPACITY,
    )
}

fn left_effective_target_rect() -> CameraTargetRect {
    effective_rect(
        CAMERA_HWB_LEFT_TARGET_RECT,
        CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_X,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
    )
}

fn right_effective_target_rect() -> CameraTargetRect {
    effective_rect(
        CAMERA_HWB_RIGHT_TARGET_RECT,
        CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_X,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_rect_close(actual: CameraTargetRect, expected: CameraTargetRect) {
        let epsilon = 0.000001;
        assert!((actual.x - expected.x).abs() <= epsilon, "x {actual:?}");
        assert!((actual.y - expected.y).abs() <= epsilon, "y {actual:?}");
        assert!(
            (actual.width - expected.width).abs() <= epsilon,
            "width {actual:?}"
        );
        assert!(
            (actual.height - expected.height).abs() <= epsilon,
            "height {actual:?}"
        );
    }

    #[test]
    fn default_effective_rects_match_native_targets() {
        assert_rect_close(
            left_effective_target_rect(),
            CameraTargetRect {
                x: 0.171875,
                y: 0.21875,
                width: 0.75,
                height: 0.65625,
            },
        );
        assert_rect_close(
            right_effective_target_rect(),
            CameraTargetRect {
                x: 0.078125,
                y: 0.21875,
                width: 0.75,
                height: 0.671875,
            },
        );
    }

    #[test]
    fn packed_left_right_rects_map_per_eye_rects_into_sbs_surface() {
        assert_rect_close(
            packed_left_rect(left_effective_target_rect()),
            CameraTargetRect {
                x: 0.0859375,
                y: 0.21875,
                width: 0.375,
                height: 0.65625,
            },
        );
        assert_rect_close(
            packed_right_rect(right_effective_target_rect()),
            CameraTargetRect {
                x: 0.5390625,
                y: 0.21875,
                width: 0.375,
                height: 0.671875,
            },
        );
    }

    #[test]
    fn effective_rect_applies_scale_offset_and_clamps_to_eye() {
        let base = CameraTargetRect {
            x: 0.2,
            y: 0.25,
            width: 0.4,
            height: 0.5,
        };
        assert_rect_close(
            effective_rect(base, 0.5, 0.1, -0.1),
            CameraTargetRect {
                x: 0.4,
                y: 0.275,
                width: 0.2,
                height: 0.25,
            },
        );
        assert_rect_close(
            effective_rect(base, 10.0, 1.0, -1.0),
            CameraTargetRect {
                x: 0.0,
                y: 0.0,
                width: 1.0,
                height: 1.0,
            },
        );
    }

    #[test]
    fn push_constant_layout_matches_shader_contract() {
        assert_eq!(std::mem::size_of::<CameraHwbProjectionPush>(), 48);
        let push = camera_hwb_projection_push();
        assert_eq!(push.params, [0.0, 0.0, 0.0, 0.0]);
    }

    #[test]
    fn marker_fields_keep_acceptance_tokens() {
        let fields = camera_hwb_projection_marker_fields();
        assert!(fields.contains("stereoSource=camera50-51"));
        assert!(fields.contains("leftCameraId=50"));
        assert!(fields.contains("rightCameraId=51"));
        assert!(
            fields.contains("leftEffectiveTargetScreenUvRect=0.171875;0.218750;0.750000;0.656250")
        );
        assert!(
            fields.contains("rightEffectiveTargetScreenUvRect=0.078125;0.218750;0.750000;0.671875")
        );
        assert!(fields
            .contains("leftPackedEffectiveTargetScreenUvRect=0.085938;0.218750;0.375000;0.656250"));
        assert!(fields.contains(
            "rightPackedEffectiveTargetScreenUvRect=0.539062;0.218750;0.375000;0.671875"
        ));
        assert!(fields.contains("projectionTargetLiveScale=1.0000"));
        assert!(fields.contains("projectionTargetMinScale=0.2500"));
        assert!(fields.contains("projectionTargetMaxScale=1.8000"));
        assert!(fields.contains("targetClipPolicy=clip-to-visible-eye"));
        assert!(fields.contains("projectionContentMappingMode=target-local-raster"));
        assert!(fields.contains("monoDuplicated=false"));
    }
}
