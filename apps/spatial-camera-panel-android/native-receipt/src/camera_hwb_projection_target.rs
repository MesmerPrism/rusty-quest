#![cfg_attr(not(any(target_os = "android", test)), allow(dead_code))]

use std::sync::atomic::{AtomicU32, Ordering};

use crate::camera_latency_diagnostics::{
    current_camera_latency_settings, CameraLatencyRotationReprojection,
};
use crate::spatial_public_multistack_runtime::current_spatial_public_opaque_projection_layer_override;

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
pub(crate) struct CameraHwbProjectionEyePush {
    pub(crate) target_rect: [f32; 4],
    pub(crate) params: [f32; 4],
    pub(crate) reprojection_row0: [f32; 4],
    pub(crate) reprojection_row1: [f32; 4],
    pub(crate) reprojection_row2: [f32; 4],
    pub(crate) reprojection_params: [f32; 4],
}

#[derive(Clone, Copy)]
pub(crate) struct CameraHwbProjectionTargetRects {
    pub(crate) left_rect: [f32; 4],
    pub(crate) right_rect: [f32; 4],
    pub(crate) params: [f32; 4],
}

pub(crate) const CAMERA_HWB_LEFT_CAMERA_ID: &str = "50";
pub(crate) const CAMERA_HWB_RIGHT_CAMERA_ID: &str = "51";
const CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT: f32 = 1.0;
const CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE: f32 = 0.25;
const CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE: f32 = 1.80;
const CAMERA_HWB_PROJECTION_TARGET_OFFSET_X: f32 = 0.0;
const CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y: f32 = 0.0;
const CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV: f32 = 0.046320;
const CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV: f32 = -0.12;
const CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV: f32 = 0.12;
const CAMERA_HWB_PROJECTION_BORDER_OPACITY: f32 = 0.0;
const CAMERA_HWB_PROJECTION_CARRIER_WIDTH_METERS: f32 = 5.40;
const CAMERA_HWB_PROJECTION_CARRIER_HEIGHT_METERS: f32 = 4.00;
const CAMERA_HWB_PROJECTION_ACCEPTED_SQUARE_TARGET_WIDTH_UV: f32 = 0.75;
const CAMERA_HWB_PROJECTION_TARGET_ASPECT_COMPENSATION: f32 =
    CAMERA_HWB_PROJECTION_CARRIER_HEIGHT_METERS / CAMERA_HWB_PROJECTION_CARRIER_WIDTH_METERS;
const CAMERA_HWB_PROJECTION_COMPENSATED_TARGET_WIDTH_UV: f32 =
    CAMERA_HWB_PROJECTION_ACCEPTED_SQUARE_TARGET_WIDTH_UV
        * CAMERA_HWB_PROJECTION_TARGET_ASPECT_COMPENSATION;
const CAMERA_HWB_LEFT_TARGET_CENTER_X: f32 = 0.546875;
const CAMERA_HWB_RIGHT_TARGET_CENTER_X: f32 = 0.453125;
const CAMERA_HWB_LEFT_TARGET_RECT: CameraTargetRect = CameraTargetRect {
    x: CAMERA_HWB_LEFT_TARGET_CENTER_X - CAMERA_HWB_PROJECTION_COMPENSATED_TARGET_WIDTH_UV * 0.5,
    y: 0.21875,
    width: CAMERA_HWB_PROJECTION_COMPENSATED_TARGET_WIDTH_UV,
    height: 0.65625,
};
const CAMERA_HWB_RIGHT_TARGET_RECT: CameraTargetRect = CameraTargetRect {
    x: CAMERA_HWB_RIGHT_TARGET_CENTER_X - CAMERA_HWB_PROJECTION_COMPENSATED_TARGET_WIDTH_UV * 0.5,
    y: 0.21875,
    width: CAMERA_HWB_PROJECTION_COMPENSATED_TARGET_WIDTH_UV,
    height: 0.671875,
};
static CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_BITS: AtomicU32 =
    AtomicU32::new(CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV.to_bits());
static CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_BITS: AtomicU32 =
    AtomicU32::new(CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT.to_bits());

#[allow(dead_code)]
pub(crate) fn update_camera_hwb_projection_target_live_scale(scale: f32) -> f32 {
    let applied = finite_or(scale, CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT).clamp(
        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
    );
    CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_BITS.store(applied.to_bits(), Ordering::Release);
    applied
}

pub(crate) fn current_camera_hwb_projection_target_live_scale() -> f32 {
    f32::from_bits(CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_BITS.load(Ordering::Acquire)).clamp(
        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
    )
}

#[allow(dead_code)]
pub(crate) fn update_camera_hwb_projection_stereo_horizontal_offset_uv(offset_uv: f32) -> f32 {
    let applied = finite_or(offset_uv, 0.0).clamp(
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV,
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV,
    );
    CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_BITS.store(applied.to_bits(), Ordering::Release);
    applied
}

fn current_camera_hwb_projection_stereo_horizontal_offset_uv() -> f32 {
    f32::from_bits(CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_BITS.load(Ordering::Acquire))
        .clamp(
            CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV,
            CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV,
        )
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value
    } else {
        fallback
    }
}

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

pub(crate) fn camera_hwb_projection_push(footprint_scale: f32) -> CameraHwbProjectionTargetRects {
    camera_hwb_projection_target_rects(footprint_scale)
}

pub(crate) fn camera_hwb_projection_eye_push(
    eye_index: usize,
    reprojection: CameraLatencyRotationReprojection,
    source_overscan_uv: f32,
    footprint_scale: f32,
) -> CameraHwbProjectionEyePush {
    let targets = camera_hwb_projection_target_rects(footprint_scale);
    CameraHwbProjectionEyePush {
        target_rect: if eye_index == 0 {
            targets.left_rect
        } else {
            targets.right_rect
        },
        params: [
            targets.params[0],
            targets.params[1],
            eye_index as f32,
            source_overscan_uv.clamp(0.0, 0.2),
        ],
        reprojection_row0: reprojection.row0,
        reprojection_row1: reprojection.row1,
        reprojection_row2: reprojection.row2,
        reprojection_params: reprojection.params,
    }
}

fn camera_hwb_projection_target_rects(footprint_scale: f32) -> CameraHwbProjectionTargetRects {
    let live_scale = current_camera_hwb_projection_target_live_scale();
    let stereo_horizontal_offset_uv = current_camera_hwb_projection_stereo_horizontal_offset_uv();
    let (left_base_effective, right_base_effective) =
        effective_target_rects_for_scale_and_stereo_offset(live_scale, stereo_horizontal_offset_uv);
    let left_effective = effective_rect(left_base_effective, footprint_scale, 0.0, 0.0);
    let right_effective = effective_rect(right_base_effective, footprint_scale, 0.0, 0.0);
    CameraHwbProjectionTargetRects {
        left_rect: packed_left_rect(left_effective).as_push(),
        right_rect: packed_right_rect(right_effective).as_push(),
        params: [
            CAMERA_HWB_PROJECTION_BORDER_OPACITY,
            current_spatial_public_opaque_projection_layer_override(),
            1.0,
            0.0,
        ],
    }
}

pub(crate) fn camera_hwb_projection_marker_fields() -> String {
    let latency_settings = current_camera_latency_settings();
    let footprint_scale = latency_settings.reprojection_footprint_scale();
    let live_scale = current_camera_hwb_projection_target_live_scale();
    let stereo_horizontal_offset_uv = current_camera_hwb_projection_stereo_horizontal_offset_uv();
    let (left_base_effective, right_base_effective) =
        effective_target_rects_for_scale_and_stereo_offset(live_scale, stereo_horizontal_offset_uv);
    let left_effective = effective_rect(left_base_effective, footprint_scale, 0.0, 0.0);
    let right_effective = effective_rect(right_base_effective, footprint_scale, 0.0, 0.0);
    format!(
        "stereoSource=camera50-51 leftCameraId={} rightCameraId={} leftTargetScreenUvRect={} rightTargetScreenUvRect={} leftBaseEffectiveTargetScreenUvRect={} rightBaseEffectiveTargetScreenUvRect={} leftEffectiveTargetScreenUvRect={} rightEffectiveTargetScreenUvRect={} leftPackedEffectiveTargetScreenUvRect={} rightPackedEffectiveTargetScreenUvRect={} projectionTargetControlsEnabled=true projectionTargetLiveScale={:.4} projectionTargetTunedMaxScale={:.4} projectionTargetMinScale={:.4} projectionTargetMaxScale={:.4} projectionTargetPresentationFootprintScale={:.4} projectionTargetGuardBandMode={} projectionTargetAngularScalePolicy={} projectionTargetOffsetUv={:.6},{:.6} projectionTargetStereoHorizontalOffsetUv={:.6} projectionTargetStereoHorizontalOffsetDefaultUv={:.6} projectionTargetStereoHorizontalOffsetRangeUv={:.6}..{:.6} projectionTargetLeftOffsetUv={:.6},{:.6} projectionTargetRightOffsetUv={:.6},{:.6} projectionTargetStereoHorizontalOffsetSign=positive-increases-separation projectionCarrierWidthMeters={:.2} projectionCarrierHeightMeters={:.2} projectionCarrierAspect={:.6} projectionTargetAcceptedSquareWidthUv={:.6} projectionTargetAspectCompensation={:.6} projectionGeometryOwner=custom-camera-target-rect videoCarrierGeometryPreserved=true borderOpacity={:.1} fallbackProjectionLayerOverrideDiagnostic=true fallbackProjectionLayerOverride={:.3} targetClipPolicy=clip-to-visible-eye projectionContentMappingMode=target-local-raster monoDuplicated=false",
        CAMERA_HWB_LEFT_CAMERA_ID,
        CAMERA_HWB_RIGHT_CAMERA_ID,
        CAMERA_HWB_LEFT_TARGET_RECT.marker_token(),
        CAMERA_HWB_RIGHT_TARGET_RECT.marker_token(),
        left_base_effective.marker_token(),
        right_base_effective.marker_token(),
        left_effective.marker_token(),
        right_effective.marker_token(),
        packed_left_rect(left_effective).marker_token(),
        packed_right_rect(right_effective).marker_token(),
        live_scale,
        live_scale,
        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
        footprint_scale,
        latency_settings.reprojection_guard_band_mode.marker_token(),
        if footprint_scale < 1.0 {
            "preserve-original-source-to-target-scale"
        } else {
            "zoom-to-fill-or-no-margin"
        },
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_X,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
        stereo_horizontal_offset_uv,
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV,
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV,
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV,
        -stereo_horizontal_offset_uv,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
        stereo_horizontal_offset_uv,
        CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
        CAMERA_HWB_PROJECTION_CARRIER_WIDTH_METERS,
        CAMERA_HWB_PROJECTION_CARRIER_HEIGHT_METERS,
        CAMERA_HWB_PROJECTION_CARRIER_WIDTH_METERS
            / CAMERA_HWB_PROJECTION_CARRIER_HEIGHT_METERS,
        CAMERA_HWB_PROJECTION_ACCEPTED_SQUARE_TARGET_WIDTH_UV,
        CAMERA_HWB_PROJECTION_TARGET_ASPECT_COMPENSATION,
        CAMERA_HWB_PROJECTION_BORDER_OPACITY,
        current_spatial_public_opaque_projection_layer_override(),
    )
}

#[cfg(test)]
fn left_effective_target_rect() -> CameraTargetRect {
    effective_target_rects_for_stereo_offset(
        current_camera_hwb_projection_stereo_horizontal_offset_uv(),
    )
    .0
}

#[cfg(test)]
fn right_effective_target_rect() -> CameraTargetRect {
    effective_target_rects_for_stereo_offset(
        current_camera_hwb_projection_stereo_horizontal_offset_uv(),
    )
    .1
}

#[cfg(test)]
fn effective_target_rects_for_stereo_offset(
    stereo_horizontal_offset_uv: f32,
) -> (CameraTargetRect, CameraTargetRect) {
    effective_target_rects_for_scale_and_stereo_offset(
        current_camera_hwb_projection_target_live_scale(),
        stereo_horizontal_offset_uv,
    )
}

fn effective_target_rects_for_scale_and_stereo_offset(
    scale: f32,
    stereo_horizontal_offset_uv: f32,
) -> (CameraTargetRect, CameraTargetRect) {
    let scale = finite_or(scale, CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT).clamp(
        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
    );
    let stereo_horizontal_offset_uv = finite_or(stereo_horizontal_offset_uv, 0.0).clamp(
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV,
        CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV,
    );
    (
        effective_rect(
            CAMERA_HWB_LEFT_TARGET_RECT,
            scale,
            CAMERA_HWB_PROJECTION_TARGET_OFFSET_X - stereo_horizontal_offset_uv,
            CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
        ),
        effective_rect(
            CAMERA_HWB_RIGHT_TARGET_RECT,
            scale,
            CAMERA_HWB_PROJECTION_TARGET_OFFSET_X + stereo_horizontal_offset_uv,
            CAMERA_HWB_PROJECTION_TARGET_OFFSET_Y,
        ),
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
    fn default_effective_rects_match_tuned_stereo_offset_targets() {
        assert_rect_close(
            left_effective_target_rect(),
            CameraTargetRect {
                x: 0.22277722,
                y: 0.21875,
                width: 0.5555556,
                height: 0.65625,
            },
        );
        assert_rect_close(
            right_effective_target_rect(),
            CameraTargetRect {
                x: 0.22166723,
                y: 0.21875,
                width: 0.5555556,
                height: 0.671875,
            },
        );
    }

    #[test]
    fn packed_left_right_rects_map_per_eye_rects_into_sbs_surface() {
        assert_rect_close(
            packed_left_rect(left_effective_target_rect()),
            CameraTargetRect {
                x: 0.11138861,
                y: 0.21875,
                width: 0.2777778,
                height: 0.65625,
            },
        );
        assert_rect_close(
            packed_right_rect(right_effective_target_rect()),
            CameraTargetRect {
                x: 0.61083364,
                y: 0.21875,
                width: 0.2777778,
                height: 0.671875,
            },
        );
    }

    #[test]
    fn stereo_horizontal_offset_moves_eye_targets_oppositely() {
        let (left, right) = effective_target_rects_for_stereo_offset(0.05);
        assert_rect_close(
            left,
            CameraTargetRect {
                x: 0.2190972,
                y: 0.21875,
                width: 0.5555556,
                height: 0.65625,
            },
        );
        assert_rect_close(
            right,
            CameraTargetRect {
                x: 0.22534722,
                y: 0.21875,
                width: 0.5555556,
                height: 0.671875,
            },
        );
        assert_rect_close(
            packed_left_rect(left),
            CameraTargetRect {
                x: 0.1095486,
                y: 0.21875,
                width: 0.2777778,
                height: 0.65625,
            },
        );
        assert_rect_close(
            packed_right_rect(right),
            CameraTargetRect {
                x: 0.61267364,
                y: 0.21875,
                width: 0.2777778,
                height: 0.671875,
            },
        );
    }

    #[test]
    fn scaled_projection_target_keeps_eye_center_and_clamps() {
        let (left, right) = effective_target_rects_for_scale_and_stereo_offset(0.5, 0.0);
        assert_rect_close(
            left,
            CameraTargetRect {
                x: 0.4079861,
                y: 0.3828125,
                width: 0.2777778,
                height: 0.328125,
            },
        );
        assert_rect_close(
            right,
            CameraTargetRect {
                x: 0.3142361,
                y: 0.38671875,
                width: 0.2777778,
                height: 0.3359375,
            },
        );

        let (left_max, right_max) = effective_target_rects_for_scale_and_stereo_offset(10.0, 0.0);
        assert_rect_close(
            left_max,
            CameraTargetRect {
                x: 0.0,
                y: 0.0,
                width: 1.0,
                height: 1.0,
            },
        );
        assert_rect_close(
            right_max,
            CameraTargetRect {
                x: 0.0,
                y: 0.0,
                width: 1.0,
                height: 1.0,
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
        assert_eq!(std::mem::size_of::<CameraHwbProjectionEyePush>(), 96);
        assert!(std::mem::size_of::<CameraHwbProjectionEyePush>() <= 128);
        let push = camera_hwb_projection_eye_push(
            0,
            CameraLatencyRotationReprojection::disabled(),
            0.10,
            1.0,
        );
        assert_eq!(push.params[0], 0.0);
        assert!((-1.0..=6.0).contains(&push.params[1]));
        assert_eq!(push.params[2], 0.0);
        assert_eq!(push.params[3], 0.10);
        let right = camera_hwb_projection_eye_push(
            1,
            CameraLatencyRotationReprojection::disabled(),
            0.10,
            1.0,
        );
        assert_eq!(right.params[2], 1.0);
        assert_eq!(right.params[3], 0.10);
        assert_ne!(push.target_rect, right.target_rect);
    }

    #[test]
    fn reduced_footprint_scales_each_eye_about_its_existing_center() {
        let base = camera_hwb_projection_push(1.0);
        let guard_band = camera_hwb_projection_push(0.8);
        for (base_rect, guard_rect) in [
            (base.left_rect, guard_band.left_rect),
            (base.right_rect, guard_band.right_rect),
        ] {
            assert!((guard_rect[2] - base_rect[2] * 0.8).abs() < 0.000001);
            assert!((guard_rect[3] - base_rect[3] * 0.8).abs() < 0.000001);
            assert!(
                (guard_rect[0] + guard_rect[2] * 0.5 - (base_rect[0] + base_rect[2] * 0.5)).abs()
                    < 0.000001
            );
            assert!(
                (guard_rect[1] + guard_rect[3] * 0.5 - (base_rect[1] + base_rect[3] * 0.5)).abs()
                    < 0.000001
            );
        }
    }

    #[test]
    fn custom_target_compensates_for_wide_video_carrier_without_resizing_it() {
        let compensated_physical_width = CAMERA_HWB_PROJECTION_COMPENSATED_TARGET_WIDTH_UV
            * CAMERA_HWB_PROJECTION_CARRIER_WIDTH_METERS;
        let accepted_square_physical_width = CAMERA_HWB_PROJECTION_ACCEPTED_SQUARE_TARGET_WIDTH_UV
            * CAMERA_HWB_PROJECTION_CARRIER_HEIGHT_METERS;
        assert!((compensated_physical_width - accepted_square_physical_width).abs() < 0.000001);
        assert!((CAMERA_HWB_PROJECTION_COMPENSATED_TARGET_WIDTH_UV - 0.5555556).abs() < 0.000001);
    }

    #[test]
    fn marker_fields_keep_acceptance_tokens() {
        let fields = camera_hwb_projection_marker_fields();
        let (left_effective, right_effective) = effective_target_rects_for_stereo_offset(
            CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV,
        );
        let left_effective_token = left_effective.marker_token();
        let right_effective_token = right_effective.marker_token();
        let left_packed_token = packed_left_rect(left_effective).marker_token();
        let right_packed_token = packed_right_rect(right_effective).marker_token();
        assert!(fields.contains("stereoSource=camera50-51"));
        assert!(fields.contains("leftCameraId=50"));
        assert!(fields.contains("rightCameraId=51"));
        assert!(fields.contains(&format!(
            "leftEffectiveTargetScreenUvRect={left_effective_token}"
        )));
        assert!(fields.contains(&format!(
            "rightEffectiveTargetScreenUvRect={right_effective_token}"
        )));
        assert!(fields.contains(&format!(
            "leftPackedEffectiveTargetScreenUvRect={left_packed_token}"
        )));
        assert!(fields.contains(&format!(
            "rightPackedEffectiveTargetScreenUvRect={right_packed_token}"
        )));
        assert!(fields.contains("projectionTargetLiveScale=1.0000"));
        assert!(fields.contains("projectionTargetMinScale=0.2500"));
        assert!(fields.contains("projectionTargetMaxScale=1.8000"));
        assert!(fields.contains("projectionTargetStereoHorizontalOffsetUv=0.046320"));
        assert!(fields.contains("projectionTargetStereoHorizontalOffsetDefaultUv=0.046320"));
        assert!(fields.contains("projectionTargetLeftOffsetUv=-0.046320,0.000000"));
        assert!(fields.contains("projectionTargetRightOffsetUv=0.046320,0.000000"));
        assert!(fields.contains("projectionCarrierWidthMeters=5.40"));
        assert!(fields.contains("projectionCarrierHeightMeters=4.00"));
        assert!(fields.contains("projectionTargetAspectCompensation=0.740741"));
        assert!(fields.contains("projectionGeometryOwner=custom-camera-target-rect"));
        assert!(fields.contains("videoCarrierGeometryPreserved=true"));
        assert!(fields.contains("fallbackProjectionLayerOverrideDiagnostic=true"));
        assert!(fields.contains("fallbackProjectionLayerOverride="));
        assert!(fields.contains("targetClipPolicy=clip-to-visible-eye"));
        assert!(fields.contains("projectionContentMappingMode=target-local-raster"));
        assert!(fields.contains("monoDuplicated=false"));
    }
}
