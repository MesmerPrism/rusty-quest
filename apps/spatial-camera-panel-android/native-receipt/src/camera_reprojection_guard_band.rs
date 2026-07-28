use crate::camera_latency_diagnostics::{
    CameraLatencyReprojectionGuardBandMode, CameraLatencyRotationReprojection,
    CameraLatencySettings, CameraLatencyStereoReprojection,
};

const MAX_MARGIN_UV: f32 = 0.20;
const MARGIN_QUANTUM_UV: f32 = 1.0 / 1024.0;
const COVERAGE_SEARCH_STEPS: usize = 24;
const CAPTURE_RAY_MIN_FORWARD: f32 = 0.01;
const DYNAMIC_HOLD_NS: i64 = 100_000_000;
const DYNAMIC_RELEASE_UV_PER_SECOND: f32 = 0.15;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraReprojectionGuardBandPhase {
    Fixed,
    Baseline,
    Attack,
    Hold,
    Release,
}

impl CameraReprojectionGuardBandPhase {
    pub(crate) fn marker_token(self) -> &'static str {
        match self {
            Self::Fixed => "fixed",
            Self::Baseline => "baseline",
            Self::Attack => "attack",
            Self::Hold => "hold",
            Self::Release => "release",
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct CameraReprojectionGuardBandFrame {
    pub(crate) source_overscan_uv: f32,
    pub(crate) footprint_scale: f32,
    pub(crate) minimum_margin_uv: f32,
    pub(crate) left_required_margin_uv: f32,
    pub(crate) right_required_margin_uv: f32,
    pub(crate) requested_margin_uv: f32,
    pub(crate) phase: CameraReprojectionGuardBandPhase,
    pub(crate) saturated: bool,
    pub(crate) saturation_count: u64,
    pub(crate) angular_displacement_degrees: f32,
    pub(crate) angular_speed_degrees_per_second: f32,
}

impl CameraReprojectionGuardBandFrame {
    pub(crate) fn marker_fields(self) -> String {
        format!(
            "guardBandMinimumUv={:.4} guardBandLeftRequiredUv={:.4} guardBandRightRequiredUv={:.4} guardBandRequestedUv={:.4} guardBandAppliedUv={:.4} guardBandFootprintScale={:.4} guardBandPhase={} guardBandSaturated={} guardBandSaturationCount={} captureToPresentationAngularDisplacementDegrees={:.3} captureToPresentationAngularSpeedDegreesPerSecond={:.3}",
            self.minimum_margin_uv,
            self.left_required_margin_uv,
            self.right_required_margin_uv,
            self.requested_margin_uv,
            self.source_overscan_uv,
            self.footprint_scale,
            self.phase.marker_token(),
            crate::bool_token(self.saturated),
            self.saturation_count,
            self.angular_displacement_degrees,
            self.angular_speed_degrees_per_second,
        )
    }
}

#[derive(Debug, Default)]
pub(crate) struct CameraReprojectionGuardBandController {
    dynamic_active: bool,
    applied_margin_uv: f32,
    last_update_ns: i64,
    hold_until_ns: i64,
    saturation_count: u64,
}

impl CameraReprojectionGuardBandController {
    pub(crate) fn update(
        &mut self,
        settings: CameraLatencySettings,
        reprojection: CameraLatencyStereoReprojection,
        now_ns: i64,
    ) -> CameraReprojectionGuardBandFrame {
        let minimum_margin_uv = settings.reprojection_source_overscan_uv();
        if settings.reprojection_guard_band_mode
            != CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint
        {
            self.dynamic_active = false;
            self.applied_margin_uv = minimum_margin_uv;
            self.last_update_ns = now_ns;
            self.hold_until_ns = now_ns;
            let footprint_scale = match settings.reprojection_guard_band_mode {
                CameraLatencyReprojectionGuardBandMode::ZoomToFill => 1.0,
                CameraLatencyReprojectionGuardBandMode::ReducedFootprint
                | CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint => {
                    footprint_scale_for_margin(minimum_margin_uv)
                }
            };
            return CameraReprojectionGuardBandFrame {
                source_overscan_uv: minimum_margin_uv,
                footprint_scale,
                minimum_margin_uv,
                left_required_margin_uv: minimum_margin_uv,
                right_required_margin_uv: minimum_margin_uv,
                requested_margin_uv: minimum_margin_uv,
                phase: CameraReprojectionGuardBandPhase::Fixed,
                saturated: false,
                saturation_count: self.saturation_count,
                angular_displacement_degrees: stereo_angular_displacement_degrees(reprojection),
                angular_speed_degrees_per_second: stereo_angular_speed_degrees_per_second(
                    reprojection,
                ),
            };
        }

        let left = required_margin_for_eye(reprojection.left);
        let right = required_margin_for_eye(reprojection.right);
        let requested_margin_uv = minimum_margin_uv.max(left.margin_uv).max(right.margin_uv);
        let saturated = left.saturated || right.saturated;
        if saturated {
            self.saturation_count = self.saturation_count.saturating_add(1);
        }

        let elapsed_seconds =
            if self.dynamic_active && now_ns > self.last_update_ns && self.last_update_ns > 0 {
                (now_ns - self.last_update_ns) as f32 / 1_000_000_000.0
            } else {
                0.0
            };
        if !self.dynamic_active {
            self.dynamic_active = true;
            self.applied_margin_uv = minimum_margin_uv;
        }

        let phase = if requested_margin_uv > self.applied_margin_uv {
            self.applied_margin_uv = requested_margin_uv;
            self.hold_until_ns = now_ns.saturating_add(DYNAMIC_HOLD_NS);
            CameraReprojectionGuardBandPhase::Attack
        } else if requested_margin_uv < self.applied_margin_uv && now_ns < self.hold_until_ns {
            CameraReprojectionGuardBandPhase::Hold
        } else if requested_margin_uv < self.applied_margin_uv {
            let released_margin =
                self.applied_margin_uv - DYNAMIC_RELEASE_UV_PER_SECOND * elapsed_seconds;
            self.applied_margin_uv = if released_margin <= requested_margin_uv {
                requested_margin_uv
            } else {
                quantize_margin_outward(released_margin)
            };
            CameraReprojectionGuardBandPhase::Release
        } else {
            CameraReprojectionGuardBandPhase::Baseline
        };

        self.applied_margin_uv = self
            .applied_margin_uv
            .clamp(minimum_margin_uv, MAX_MARGIN_UV);
        self.last_update_ns = now_ns;
        CameraReprojectionGuardBandFrame {
            source_overscan_uv: self.applied_margin_uv,
            footprint_scale: footprint_scale_for_margin(self.applied_margin_uv),
            minimum_margin_uv,
            left_required_margin_uv: left.margin_uv,
            right_required_margin_uv: right.margin_uv,
            requested_margin_uv,
            phase,
            saturated,
            saturation_count: self.saturation_count,
            angular_displacement_degrees: stereo_angular_displacement_degrees(reprojection),
            angular_speed_degrees_per_second: stereo_angular_speed_degrees_per_second(reprojection),
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct RequiredMargin {
    margin_uv: f32,
    saturated: bool,
}

fn required_margin_for_eye(reprojection: CameraLatencyRotationReprojection) -> RequiredMargin {
    if !reprojection.applied() || eye_crop_is_covered(reprojection, 0.0) {
        return RequiredMargin {
            margin_uv: 0.0,
            saturated: false,
        };
    }
    if !eye_crop_is_covered(reprojection, MAX_MARGIN_UV) {
        return RequiredMargin {
            margin_uv: MAX_MARGIN_UV,
            saturated: true,
        };
    }

    let mut unsafe_margin = 0.0_f32;
    let mut safe_margin = MAX_MARGIN_UV;
    for _ in 0..COVERAGE_SEARCH_STEPS {
        let candidate = (unsafe_margin + safe_margin) * 0.5;
        if eye_crop_is_covered(reprojection, candidate) {
            safe_margin = candidate;
        } else {
            unsafe_margin = candidate;
        }
    }
    RequiredMargin {
        margin_uv: quantize_margin_outward(safe_margin),
        saturated: false,
    }
}

fn eye_crop_is_covered(reprojection: CameraLatencyRotationReprojection, margin_uv: f32) -> bool {
    if !reprojection.applied() {
        return true;
    }
    if reprojection
        .row0
        .iter()
        .chain(reprojection.row1.iter())
        .chain(reprojection.row2.iter())
        .chain(reprojection.params.iter())
        .any(|value| !value.is_finite())
    {
        return false;
    }
    let margin_uv = margin_uv.clamp(0.0, MAX_MARGIN_UV);
    let far_uv = 1.0 - margin_uv;
    [
        (margin_uv, margin_uv),
        (far_uv, margin_uv),
        (margin_uv, far_uv),
        (far_uv, far_uv),
    ]
    .into_iter()
    .all(|(u, v)| reprojected_uv_is_covered(reprojection, u, v))
}

fn reprojected_uv_is_covered(
    reprojection: CameraLatencyRotationReprojection,
    presentation_u: f32,
    presentation_v: f32,
) -> bool {
    let tan_half_horizontal_fov = reprojection.params[1].max(0.01);
    let tan_half_vertical_fov = reprojection.params[2].max(0.01);
    let principal_u = reprojection.row0[3];
    let principal_v = reprojection.row1[3];
    let current_ray = [
        (presentation_u - principal_u) * 2.0 * tan_half_horizontal_fov,
        (principal_v - presentation_v) * 2.0 * tan_half_vertical_fov,
        1.0,
    ];
    let capture_ray = [
        dot_row(reprojection.row0, current_ray),
        dot_row(reprojection.row1, current_ray),
        dot_row(reprojection.row2, current_ray),
    ];
    if capture_ray.iter().any(|value| !value.is_finite())
        || capture_ray[2] <= CAPTURE_RAY_MIN_FORWARD
    {
        return false;
    }
    let sample_u = principal_u + capture_ray[0] / (capture_ray[2] * 2.0 * tan_half_horizontal_fov);
    let sample_v = principal_v - capture_ray[1] / (capture_ray[2] * 2.0 * tan_half_vertical_fov);
    sample_u.is_finite()
        && sample_v.is_finite()
        && (0.0..=1.0).contains(&sample_u)
        && (0.0..=1.0).contains(&sample_v)
}

fn dot_row(row: [f32; 4], vector: [f32; 3]) -> f32 {
    row[0] * vector[0] + row[1] * vector[1] + row[2] * vector[2]
}

fn quantize_margin_outward(margin_uv: f32) -> f32 {
    ((margin_uv.clamp(0.0, MAX_MARGIN_UV) / MARGIN_QUANTUM_UV).ceil() * MARGIN_QUANTUM_UV)
        .min(MAX_MARGIN_UV)
}

fn footprint_scale_for_margin(margin_uv: f32) -> f32 {
    (1.0 - 2.0 * margin_uv).clamp(0.6, 1.0)
}

fn stereo_angular_displacement_degrees(reprojection: CameraLatencyStereoReprojection) -> f32 {
    angular_displacement_degrees(reprojection.left)
        .max(angular_displacement_degrees(reprojection.right))
}

fn stereo_angular_speed_degrees_per_second(reprojection: CameraLatencyStereoReprojection) -> f32 {
    angular_speed_degrees_per_second(reprojection.left)
        .max(angular_speed_degrees_per_second(reprojection.right))
}

fn angular_displacement_degrees(reprojection: CameraLatencyRotationReprojection) -> f32 {
    if !reprojection.applied() {
        return 0.0;
    }
    let trace = reprojection.row0[0] + reprojection.row1[1] + reprojection.row2[2];
    (((trace - 1.0) * 0.5).clamp(-1.0, 1.0).acos()).to_degrees()
}

fn angular_speed_degrees_per_second(reprojection: CameraLatencyRotationReprojection) -> f32 {
    let delta_ms = reprojection.capture_to_presentation_delta_ms().abs();
    if delta_ms < 0.01 {
        0.0
    } else {
        angular_displacement_degrees(reprojection) * 1000.0 / delta_ms
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::camera_latency_diagnostics::{
        CameraLatencyPresentationViewerBasis, CameraLatencyReprojectionGuardBandMode,
    };

    fn eye_with_yaw(degrees: f32, delta_ms: f32) -> CameraLatencyRotationReprojection {
        let radians = degrees.to_radians();
        let (sin, cos) = radians.sin_cos();
        CameraLatencyRotationReprojection {
            row0: [cos, 0.0, sin, 0.5],
            row1: [0.0, 1.0, 0.0, 0.5],
            row2: [-sin, 0.0, cos, 0.0],
            params: [1.0, 1.0, 1.0, delta_ms],
        }
    }

    fn eye_with_pitch(degrees: f32, delta_ms: f32) -> CameraLatencyRotationReprojection {
        let radians = degrees.to_radians();
        let (sin, cos) = radians.sin_cos();
        CameraLatencyRotationReprojection {
            row0: [1.0, 0.0, 0.0, 0.5],
            row1: [0.0, cos, -sin, 0.5],
            row2: [0.0, sin, cos, 0.0],
            params: [1.0, 1.0, 1.0, delta_ms],
        }
    }

    fn eye_with_roll(degrees: f32, delta_ms: f32) -> CameraLatencyRotationReprojection {
        let radians = degrees.to_radians();
        let (sin, cos) = radians.sin_cos();
        CameraLatencyRotationReprojection {
            row0: [cos, -sin, 0.0, 0.5],
            row1: [sin, cos, 0.0, 0.5],
            row2: [0.0, 0.0, 1.0, 0.0],
            params: [1.0, 1.0, 1.0, delta_ms],
        }
    }

    fn stereo(
        left: CameraLatencyRotationReprojection,
        right: CameraLatencyRotationReprojection,
    ) -> CameraLatencyStereoReprojection {
        CameraLatencyStereoReprojection {
            left,
            right,
            presentation: CameraLatencyPresentationViewerBasis {
                basis: None,
                target_timestamp_ns: 0,
                requested_lead_ms: 0,
                effective_lead_ms: 0.0,
                latest_sample_age_ms: 0.0,
                source: "test",
                fallback: "none",
            },
        }
    }

    fn settings(
        mode: CameraLatencyReprojectionGuardBandMode,
        minimum_percent: u32,
    ) -> CameraLatencySettings {
        CameraLatencySettings {
            enabled: true,
            reprojection_source_overscan_percent: minimum_percent,
            reprojection_guard_band_mode: mode,
            ..CameraLatencySettings::default()
        }
    }

    #[test]
    fn camera_reprojection_guard_band_fixed_modes_preserve_existing_geometry() {
        let reprojection = stereo(eye_with_yaw(12.0, 31.0), eye_with_yaw(12.0, 31.0));
        let mut controller = CameraReprojectionGuardBandController::default();
        let zoom = controller.update(
            settings(CameraLatencyReprojectionGuardBandMode::ZoomToFill, 10),
            reprojection,
            1,
        );
        assert_eq!(zoom.source_overscan_uv, 0.1);
        assert_eq!(zoom.footprint_scale, 1.0);
        let reduced = controller.update(
            settings(CameraLatencyReprojectionGuardBandMode::ReducedFootprint, 10),
            reprojection,
            2,
        );
        assert_eq!(reduced.source_overscan_uv, 0.1);
        assert!((reduced.footprint_scale - 0.8).abs() < f32::EPSILON);
        assert_eq!(reduced.phase, CameraReprojectionGuardBandPhase::Fixed);
    }

    #[test]
    fn camera_reprojection_guard_band_uses_shared_worst_eye_margin() {
        let mut controller = CameraReprojectionGuardBandController::default();
        let frame = controller.update(
            settings(
                CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint,
                10,
            ),
            stereo(eye_with_yaw(3.0, 31.0), eye_with_yaw(12.0, 31.0)),
            1_000_000_000,
        );
        assert!(frame.right_required_margin_uv > frame.left_required_margin_uv);
        assert_eq!(frame.source_overscan_uv, frame.requested_margin_uv);
        assert_eq!(frame.phase, CameraReprojectionGuardBandPhase::Attack);
        assert!(frame.footprint_scale < 0.8);
    }

    #[test]
    fn camera_reprojection_guard_band_keeps_configured_baseline_exact_at_rest() {
        let idle = stereo(eye_with_yaw(0.0, 31.0), eye_with_yaw(0.0, 31.0));
        let mut controller = CameraReprojectionGuardBandController::default();
        let frame = controller.update(
            settings(
                CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint,
                10,
            ),
            idle,
            1_000_000_000,
        );
        assert_eq!(frame.source_overscan_uv, 0.1);
        assert!((frame.footprint_scale - 0.8).abs() < f32::EPSILON);
        assert_eq!(frame.phase, CameraReprojectionGuardBandPhase::Baseline);
    }

    #[test]
    fn camera_reprojection_guard_band_covers_pitch_and_roll() {
        for eye in [eye_with_pitch(10.0, 31.0), eye_with_roll(10.0, 31.0)] {
            let required = required_margin_for_eye(eye);
            assert!(required.margin_uv > 0.0);
            assert!(eye_crop_is_covered(eye, required.margin_uv));
        }
    }

    #[test]
    fn camera_reprojection_guard_band_rounds_computed_coverage_outward() {
        let eye = eye_with_yaw(7.0, 31.0);
        let required = required_margin_for_eye(eye);
        let quantized_steps = required.margin_uv / MARGIN_QUANTUM_UV;
        assert!((quantized_steps - quantized_steps.round()).abs() < 0.0001);
        assert!(eye_crop_is_covered(eye, required.margin_uv));
        assert!(!eye_crop_is_covered(
            eye,
            (required.margin_uv - MARGIN_QUANTUM_UV).max(0.0)
        ));
    }

    #[test]
    fn camera_reprojection_guard_band_attacks_holds_and_releases() {
        let dynamic = settings(
            CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint,
            10,
        );
        let moving = stereo(eye_with_yaw(12.0, 31.0), eye_with_yaw(12.0, 31.0));
        let idle = stereo(eye_with_yaw(0.0, 31.0), eye_with_yaw(0.0, 31.0));
        let mut controller = CameraReprojectionGuardBandController::default();
        let attack = controller.update(dynamic, moving, 1_000_000_000);
        let hold = controller.update(dynamic, idle, 1_050_000_000);
        let release = controller.update(dynamic, idle, 1_200_000_000);
        assert_eq!(attack.phase, CameraReprojectionGuardBandPhase::Attack);
        assert_eq!(hold.phase, CameraReprojectionGuardBandPhase::Hold);
        assert_eq!(hold.source_overscan_uv, attack.source_overscan_uv);
        assert_eq!(release.phase, CameraReprojectionGuardBandPhase::Release);
        assert!(release.source_overscan_uv < hold.source_overscan_uv);
        assert!(release.source_overscan_uv >= 0.1);
    }

    #[test]
    fn camera_reprojection_guard_band_fails_closed_at_real_source_limit() {
        let impossible = CameraLatencyRotationReprojection {
            row0: [1.0, 0.0, 3.0, 0.5],
            row1: [0.0, 1.0, 0.0, 0.5],
            row2: [0.0, 0.0, 1.0, 0.0],
            params: [1.0, 1.0, 1.0, 20.0],
        };
        let mut controller = CameraReprojectionGuardBandController::default();
        let frame = controller.update(
            settings(
                CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint,
                10,
            ),
            stereo(impossible, impossible),
            1_000_000_000,
        );
        assert!(frame.saturated);
        assert_eq!(frame.source_overscan_uv, MAX_MARGIN_UV);
        assert_eq!(frame.footprint_scale, 0.6);
        assert_eq!(frame.saturation_count, 1);
    }

    #[test]
    fn camera_reprojection_guard_band_rejects_non_finite_reprojection() {
        let mut invalid = eye_with_yaw(0.0, 20.0);
        invalid.row0[0] = f32::NAN;
        let mut controller = CameraReprojectionGuardBandController::default();
        let frame = controller.update(
            settings(
                CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint,
                10,
            ),
            stereo(invalid, invalid),
            1_000_000_000,
        );
        assert!(frame.saturated);
        assert_eq!(frame.source_overscan_uv, MAX_MARGIN_UV);
    }
}
