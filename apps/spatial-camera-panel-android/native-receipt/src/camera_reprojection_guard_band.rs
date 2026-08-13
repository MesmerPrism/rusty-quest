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
    pub(crate) linear_speed_meters_per_second: f32,
}

impl CameraReprojectionGuardBandFrame {
    pub(crate) fn marker_fields(self) -> String {
        format!(
            "guardBandMinimumUv={:.4} guardBandLeftRequiredUv={:.4} guardBandRightRequiredUv={:.4} guardBandRequestedUv={:.4} guardBandAppliedUv={:.4} guardBandFootprintScale={:.4} guardBandPhase={} guardBandSaturated={} guardBandSaturationCount={} captureToPresentationAngularDisplacementDegrees={:.3} captureToPresentationAngularSpeedDegreesPerSecond={:.3} headsetLinearSpeedMetersPerSecond={:.3}",
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
            self.linear_speed_meters_per_second,
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
    last_presentation_position: Option<[f32; 3]>,
    last_presentation_timestamp_ns: i64,
}

impl CameraReprojectionGuardBandController {
    pub(crate) fn update(
        &mut self,
        settings: CameraLatencySettings,
        reprojection: CameraLatencyStereoReprojection,
        now_ns: i64,
    ) -> CameraReprojectionGuardBandFrame {
        self.update_with_policy(
            settings.reprojection_source_overscan_uv(),
            MAX_MARGIN_UV,
            f32::NAN,
            0.0,
            settings.reprojection_guard_band_mode,
            reprojection,
            now_ns,
        )
    }

    /// Applies the product Buffer contract. Static uses one guard size; Dynamic interpolates
    /// between its minimum and maximum guard at the tracked speed threshold. In either mode the
    /// effective guard owns both the retained source border and visible projection contraction.
    pub(crate) fn update_for_projection_buffer(
        &mut self,
        settings: CameraLatencySettings,
        buffer_geometry_mode: u32,
        buffer_static_width_uv: f32,
        buffer_minimum_width_uv: f32,
        buffer_maximum_width_uv: f32,
        buffer_maximum_speed_meters_per_second: f32,
        reprojection: CameraLatencyStereoReprojection,
        now_ns: i64,
    ) -> CameraReprojectionGuardBandFrame {
        let static_width_uv = if buffer_static_width_uv.is_finite() {
            buffer_static_width_uv.clamp(0.0, MAX_MARGIN_UV)
        } else {
            0.08
        };
        let minimum_width_uv = if buffer_minimum_width_uv.is_finite() {
            buffer_minimum_width_uv.clamp(0.0, MAX_MARGIN_UV)
        } else {
            0.06
        };
        let maximum_width_uv = if buffer_maximum_width_uv.is_finite() {
            buffer_maximum_width_uv.clamp(minimum_width_uv, MAX_MARGIN_UV)
        } else {
            0.18
        };
        let maximum_speed_meters_per_second = if buffer_maximum_speed_meters_per_second.is_finite()
        {
            buffer_maximum_speed_meters_per_second.clamp(0.05, 3.0)
        } else {
            0.80
        };
        let linear_speed_meters_per_second = self.presentation_linear_speed(reprojection);
        let dynamic_margin_uv = minimum_width_uv
            + (maximum_width_uv - minimum_width_uv)
                * (linear_speed_meters_per_second / maximum_speed_meters_per_second)
                    .clamp(0.0, 1.0);
        let (minimum_margin_uv, maximum_margin_uv, requested_dynamic_margin_uv, mode) =
            match buffer_geometry_mode {
                0 => (
                    0.0,
                    0.0,
                    0.0,
                    CameraLatencyReprojectionGuardBandMode::ZoomToFill,
                ),
                1 => (
                    static_width_uv,
                    static_width_uv,
                    static_width_uv,
                    CameraLatencyReprojectionGuardBandMode::ReducedFootprint,
                ),
                2 => (
                    minimum_width_uv,
                    maximum_width_uv,
                    dynamic_margin_uv,
                    CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint,
                ),
                _ => {
                    let legacy = settings.reprojection_source_overscan_uv();
                    (
                        legacy,
                        MAX_MARGIN_UV,
                        legacy,
                        settings.reprojection_guard_band_mode,
                    )
                }
            };
        self.update_with_policy(
            minimum_margin_uv,
            maximum_margin_uv,
            requested_dynamic_margin_uv,
            linear_speed_meters_per_second,
            mode,
            reprojection,
            now_ns,
        )
    }

    fn update_with_policy(
        &mut self,
        minimum_margin_uv: f32,
        maximum_margin_uv: f32,
        requested_dynamic_margin_uv: f32,
        linear_speed_meters_per_second: f32,
        mode: CameraLatencyReprojectionGuardBandMode,
        reprojection: CameraLatencyStereoReprojection,
        now_ns: i64,
    ) -> CameraReprojectionGuardBandFrame {
        if mode != CameraLatencyReprojectionGuardBandMode::DynamicReducedFootprint {
            self.dynamic_active = false;
            self.applied_margin_uv = minimum_margin_uv;
            self.last_update_ns = now_ns;
            self.hold_until_ns = now_ns;
            let footprint_scale = match mode {
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
                linear_speed_meters_per_second,
            };
        }

        let left = required_margin_for_eye(reprojection.left);
        let right = required_margin_for_eye(reprojection.right);
        let requested_margin_uv = if requested_dynamic_margin_uv.is_finite() {
            requested_dynamic_margin_uv.clamp(minimum_margin_uv, maximum_margin_uv)
        } else {
            minimum_margin_uv
                .max(left.margin_uv)
                .max(right.margin_uv)
                .clamp(minimum_margin_uv, maximum_margin_uv)
        };
        let saturated = left.saturated
            || right.saturated
            || left.margin_uv > maximum_margin_uv
            || right.margin_uv > maximum_margin_uv;
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
            .clamp(minimum_margin_uv, maximum_margin_uv);
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
            linear_speed_meters_per_second,
        }
    }

    fn presentation_linear_speed(&mut self, reprojection: CameraLatencyStereoReprojection) -> f32 {
        let Some(basis) = reprojection.presentation.basis else {
            self.last_presentation_position = None;
            self.last_presentation_timestamp_ns = 0;
            return 0.0;
        };
        let speed = if let Some(previous) = self.last_presentation_position {
            let elapsed_ns = basis
                .timestamp_ns
                .saturating_sub(self.last_presentation_timestamp_ns);
            if elapsed_ns > 0 {
                let delta = [
                    basis.position[0] - previous[0],
                    basis.position[1] - previous[1],
                    basis.position[2] - previous[2],
                ];
                let meters =
                    (delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]).sqrt();
                (meters * 1_000_000_000.0 / elapsed_ns as f32).clamp(0.0, 10.0)
            } else {
                0.0
            }
        } else {
            0.0
        };
        self.last_presentation_position = Some(basis.position);
        self.last_presentation_timestamp_ns = basis.timestamp_ns;
        speed
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

    fn stereo_at_position(
        position: [f32; 3],
        timestamp_ns: i64,
    ) -> CameraLatencyStereoReprojection {
        let mut result = stereo(eye_with_yaw(0.0, 31.0), eye_with_yaw(0.0, 31.0));
        result.presentation.basis = Some(
            crate::camera_latency_diagnostics::CameraLatencyViewerBasis {
                timestamp_ns,
                sequence: timestamp_ns.max(0) as u64,
                position,
                right: [1.0, 0.0, 0.0],
                up: [0.0, 1.0, 0.0],
                forward: [0.0, 0.0, -1.0],
            },
        );
        result.presentation.target_timestamp_ns = timestamp_ns;
        result
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
    fn projection_buffer_guard_is_the_single_crop_and_footprint_authority() {
        let idle = stereo(eye_with_yaw(0.0, 31.0), eye_with_yaw(0.0, 31.0));
        let diagnostics = settings(CameraLatencyReprojectionGuardBandMode::ZoomToFill, 3);
        let mut controller = CameraReprojectionGuardBandController::default();

        let off = controller.update_for_projection_buffer(
            diagnostics,
            0,
            0.12,
            0.06,
            0.18,
            0.80,
            idle,
            1,
        );
        assert_eq!(off.source_overscan_uv, 0.0);
        assert_eq!(off.footprint_scale, 1.0);

        let fixed = controller.update_for_projection_buffer(
            diagnostics,
            1,
            0.12,
            0.06,
            0.18,
            0.80,
            idle,
            2,
        );
        assert_eq!(fixed.source_overscan_uv, 0.12);
        assert!((fixed.footprint_scale - 0.76).abs() < f32::EPSILON);
        assert_eq!(fixed.minimum_margin_uv, 0.12);

        let dynamic = controller.update_for_projection_buffer(
            diagnostics,
            2,
            0.12,
            0.06,
            0.18,
            0.80,
            idle,
            3,
        );
        assert_eq!(dynamic.source_overscan_uv, 0.06);
        assert!((dynamic.footprint_scale - 0.88).abs() < f32::EPSILON);
        assert_eq!(dynamic.phase, CameraReprojectionGuardBandPhase::Baseline);
    }

    #[test]
    fn dynamic_projection_buffer_reaches_maximum_at_configured_headset_speed() {
        let diagnostics = settings(CameraLatencyReprojectionGuardBandMode::ZoomToFill, 3);
        let mut controller = CameraReprojectionGuardBandController::default();
        let initial = controller.update_for_projection_buffer(
            diagnostics,
            2,
            0.08,
            0.06,
            0.18,
            0.80,
            stereo_at_position([0.0, 0.0, 0.0], 1_000_000_000),
            1_000_000_000,
        );
        assert_eq!(initial.source_overscan_uv, 0.06);

        let at_threshold = controller.update_for_projection_buffer(
            diagnostics,
            2,
            0.08,
            0.06,
            0.18,
            0.80,
            stereo_at_position([0.80, 0.0, 0.0], 2_000_000_000),
            2_000_000_000,
        );
        assert!((at_threshold.linear_speed_meters_per_second - 0.80).abs() < 0.000_01);
        assert_eq!(at_threshold.source_overscan_uv, 0.18);
        assert!((at_threshold.footprint_scale - 0.64).abs() < f32::EPSILON);
        assert_eq!(at_threshold.phase, CameraReprojectionGuardBandPhase::Attack);
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
