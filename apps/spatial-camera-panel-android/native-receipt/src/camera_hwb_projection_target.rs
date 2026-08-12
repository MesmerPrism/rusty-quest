#![cfg_attr(not(any(target_os = "android", test)), allow(dead_code))]

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{OnceLock, RwLock};

use crate::camera_latency_diagnostics::{
    current_camera_latency_settings, CameraLatencyRotationReprojection,
};
use crate::spatial_guide_processing::current_spatial_guide_processing_policy;
use crate::spatial_presentation_policy::presentation_projection_scale;
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

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ProjectionZoneCompositorSettings {
    pub(crate) coverage_mode: u32,
    pub(crate) region_contract_version: u32,
    pub(crate) buffer_geometry_mode: u32,
    pub(crate) buffer_static_width_uv: f32,
    pub(crate) buffer_fill_mode: u32,
    pub(crate) stretch_extent_mode: u32,
    pub(crate) stretch_source: u32,
    pub(crate) debug_mode: u32,
    pub(crate) outer_target_mode: u32,
    pub(crate) stretch_mapping: u32,
    pub(crate) projection_effect_edge_guard_enabled: bool,
    pub(crate) stretch_option_flags: u32,
    pub(crate) edge_inset_uv: f32,
    pub(crate) max_inset_uv: f32,
    pub(crate) stretch_curve: f32,
    pub(crate) processed_mix: f32,
    pub(crate) inner_signal: u32,
    pub(crate) inner_width_uv: f32,
    pub(crate) inner_curve: f32,
    pub(crate) inner_threshold_rgb: [f32; 3],
    pub(crate) inner_softness: f32,
    pub(crate) inner_strength: f32,
    pub(crate) inner_cycle_amplitude: f32,
    pub(crate) inner_cycle_hz: f32,
    pub(crate) inner_motion_gain: f32,
    pub(crate) inner_application_mode: u32,
    pub(crate) inner_source_choice: u32,
    pub(crate) inner_region_driver: u32,
    pub(crate) inner_strength_rgb: [f32; 3],
    pub(crate) inner_cycle_amplitude_rgb: [f32; 3],
    pub(crate) inner_cycle_hz_rgb: [f32; 3],
    pub(crate) inner_cycle_phase_rgb: [f32; 3],
    pub(crate) outer_signal: u32,
    pub(crate) outer_width_uv: f32,
    pub(crate) outer_curve: f32,
    pub(crate) outer_threshold_rgb: [f32; 3],
    pub(crate) outer_softness: f32,
    pub(crate) outer_strength: f32,
    pub(crate) outer_cycle_amplitude: f32,
    pub(crate) outer_cycle_hz: f32,
    pub(crate) outer_motion_gain: f32,
    pub(crate) outer_application_mode: u32,
    pub(crate) outer_source_choice: u32,
    pub(crate) outer_region_driver: u32,
    pub(crate) outer_strength_rgb: [f32; 3],
    pub(crate) outer_cycle_amplitude_rgb: [f32; 3],
    pub(crate) outer_cycle_hz_rgb: [f32; 3],
    pub(crate) outer_cycle_phase_rgb: [f32; 3],
}

impl Default for ProjectionZoneCompositorSettings {
    fn default() -> Self {
        Self {
            coverage_mode: 0,
            region_contract_version: 1,
            buffer_geometry_mode: 0,
            buffer_static_width_uv: 0.08,
            buffer_fill_mode: 0,
            stretch_extent_mode: 0,
            stretch_source: 1,
            debug_mode: 0,
            outer_target_mode: 0,
            stretch_mapping: 0,
            projection_effect_edge_guard_enabled: true,
            stretch_option_flags: 0,
            edge_inset_uv: 0.015,
            max_inset_uv: 0.14,
            stretch_curve: 1.6,
            processed_mix: 1.0,
            inner_signal: 0,
            inner_width_uv: 0.04,
            inner_curve: 1.6,
            inner_threshold_rgb: [0.5; 3],
            inner_softness: 0.12,
            inner_strength: 0.0,
            inner_cycle_amplitude: 0.0,
            inner_cycle_hz: 0.12,
            inner_motion_gain: 0.0,
            inner_application_mode: 0,
            inner_source_choice: 1,
            inner_region_driver: 3,
            inner_strength_rgb: [0.0; 3],
            inner_cycle_amplitude_rgb: [0.0; 3],
            inner_cycle_hz_rgb: [0.12, 0.17, 0.23],
            inner_cycle_phase_rgb: [0.0, 0.333333, 0.666667],
            outer_signal: 0,
            outer_width_uv: 0.04,
            outer_curve: 1.6,
            outer_threshold_rgb: [0.5; 3],
            outer_softness: 0.12,
            outer_strength: 0.0,
            outer_cycle_amplitude: 0.0,
            outer_cycle_hz: 0.10,
            outer_motion_gain: 0.0,
            outer_application_mode: 0,
            outer_source_choice: 1,
            outer_region_driver: 3,
            outer_strength_rgb: [0.0; 3],
            outer_cycle_amplitude_rgb: [0.0; 3],
            outer_cycle_hz_rgb: [0.10, 0.14, 0.19],
            outer_cycle_phase_rgb: [0.0, 0.333333, 0.666667],
        }
    }
}

impl ProjectionZoneCompositorSettings {
    pub(crate) fn active(self) -> bool {
        self.region_contract_version >= 2 || self.coverage_mode != 0
    }

    pub(crate) fn replaces_video(self) -> bool {
        if self.region_contract_version >= 2 {
            self.buffer_geometry_mode != 0
                && self.buffer_fill_mode == 2
                && self.stretch_extent_mode == 1
        } else {
            self.coverage_mode == 2
        }
    }

    pub(crate) fn synthetic_diagnostic(self) -> bool {
        self.debug_mode == 1
    }

    pub(crate) fn transparent_underlay_requested(self) -> bool {
        self.outer_target_mode == 1
    }

    pub(crate) fn transparent_underlay_supported(self) -> bool {
        self.transparent_underlay_requested()
            && (self.region_contract_version >= 2
                || self.coverage_mode == 0
                || (self.coverage_mode == 1
                    && self.debug_mode == 0
                    && self.outer_signal != 4
                    && self.outer_application_mode == 2
                    && self.outer_source_choice == 0))
    }

    pub(crate) fn suppresses_same_surface_video(self, projection_zone_ready: bool) -> bool {
        self.transparent_underlay_requested()
            || (projection_zone_ready && self.replaces_video())
    }

    pub(crate) fn marker_fields(self) -> String {
        let base = format!(
            "projectionZoneCompositorMode={} projectionRegionContract=v{} projectionBufferGeometry={} projectionBufferStaticWidthUv={:.4} projectionBufferFill={} projectionStretchExtent={} projectionZoneStretchSource={} projectionZoneStretchMapping={} projectionZoneEffectEdgeGuardEnabled={} projectionZoneStretchOptionFlags={} projectionZoneStretchParameterA={:.4} projectionZoneStretchParameterB={:.4} projectionZoneStretchParameterC={:.3} projectionZoneProcessedMix={:.3} projectionZoneInnerSignal={} projectionZoneInnerWidthUv={:.4} projectionZoneInnerThresholdRgb={:.3},{:.3},{:.3} projectionZoneInnerSoftness={:.3} projectionZoneInnerStrength={:.3} projectionZoneInnerCycleAmplitude={:.3} projectionZoneInnerCycleHz={:.3} projectionZoneInnerMotionGain={:.3} projectionZoneOuterSignal={} projectionZoneOuterWidthUv={:.4} projectionZoneOuterThresholdRgb={:.3},{:.3},{:.3} projectionZoneOuterSoftness={:.3} projectionZoneOuterStrength={:.3} projectionZoneOuterCycleAmplitude={:.3} projectionZoneOuterCycleHz={:.3} projectionZoneOuterMotionGain={:.3} projectionZoneDebugMode={}",
            coverage_mode_token(self.coverage_mode),
            self.region_contract_version,
            buffer_geometry_token(self.buffer_geometry_mode),
            self.buffer_static_width_uv,
            buffer_fill_token(self.buffer_fill_mode),
            stretch_extent_token(self.stretch_extent_mode),
            stretch_source_token(self.stretch_source),
            stretch_mapping_token(self.stretch_mapping),
            self.projection_effect_edge_guard_enabled,
            self.stretch_option_flags,
            self.edge_inset_uv,
            self.max_inset_uv,
            self.stretch_curve,
            self.processed_mix,
            blend_signal_token(self.inner_signal),
            self.inner_width_uv,
            self.inner_threshold_rgb[0],
            self.inner_threshold_rgb[1],
            self.inner_threshold_rgb[2],
            self.inner_softness,
            self.inner_strength,
            self.inner_cycle_amplitude,
            self.inner_cycle_hz,
            self.inner_motion_gain,
            blend_signal_token(self.outer_signal),
            self.outer_width_uv,
            self.outer_threshold_rgb[0],
            self.outer_threshold_rgb[1],
            self.outer_threshold_rgb[2],
            self.outer_softness,
            self.outer_strength,
            self.outer_cycle_amplitude,
            self.outer_cycle_hz,
            self.outer_motion_gain,
            debug_mode_token(self.debug_mode),
        );
        format!(
            "{base} projectionZoneInnerApplication={} projectionZoneInnerColorSource={} projectionZoneInnerRegionDriver={} projectionZoneInnerStrengthRgb={:.3},{:.3},{:.3} projectionZoneInnerCycleAmplitudeRgb={:.3},{:.3},{:.3} projectionZoneInnerCycleHzRgb={:.3},{:.3},{:.3} projectionZoneInnerCyclePhaseTurnsRgb={:.3},{:.3},{:.3} projectionZoneOuterApplication={} projectionZoneOuterColorSource={} projectionZoneOuterRegionDriver={} projectionZoneOuterStrengthRgb={:.3},{:.3},{:.3} projectionZoneOuterCycleAmplitudeRgb={:.3},{:.3},{:.3} projectionZoneOuterCycleHzRgb={:.3},{:.3},{:.3} projectionZoneOuterCyclePhaseTurnsRgb={:.3},{:.3},{:.3} projectionZoneOuterTarget={} projectionZoneOuterUnderlaySupported={} projectionZoneOuterAlphaDriver={} projectionZoneSyntheticSourceIsolation={} projectionZoneSyntheticDisplacementSuppressed={} projectionZoneUnsampledOuterData={}",
            blend_application_token(self.inner_application_mode),
            blend_source_choice_token(self.inner_source_choice),
            blend_region_driver_token(self.inner_region_driver),
            self.inner_strength_rgb[0],
            self.inner_strength_rgb[1],
            self.inner_strength_rgb[2],
            self.inner_cycle_amplitude_rgb[0],
            self.inner_cycle_amplitude_rgb[1],
            self.inner_cycle_amplitude_rgb[2],
            self.inner_cycle_hz_rgb[0],
            self.inner_cycle_hz_rgb[1],
            self.inner_cycle_hz_rgb[2],
            self.inner_cycle_phase_rgb[0],
            self.inner_cycle_phase_rgb[1],
            self.inner_cycle_phase_rgb[2],
            blend_application_token(self.outer_application_mode),
            blend_source_choice_token(self.outer_source_choice),
            blend_region_driver_token(self.outer_region_driver),
            self.outer_strength_rgb[0],
            self.outer_strength_rgb[1],
            self.outer_strength_rgb[2],
            self.outer_cycle_amplitude_rgb[0],
            self.outer_cycle_amplitude_rgb[1],
            self.outer_cycle_amplitude_rgb[2],
            self.outer_cycle_hz_rgb[0],
            self.outer_cycle_hz_rgb[1],
            self.outer_cycle_hz_rgb[2],
            self.outer_cycle_phase_rgb[0],
            self.outer_cycle_phase_rgb[1],
            self.outer_cycle_phase_rgb[2],
            outer_target_mode_token(self.outer_target_mode),
            self.transparent_underlay_supported(),
            blend_region_driver_token(self.outer_region_driver),
            self.synthetic_diagnostic(),
            self.synthetic_diagnostic(),
            self.transparent_underlay_requested(),
        )
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ProjectionZoneUniform {
    pub(crate) user_rects: [[f32; 4]; 2],
    pub(crate) carrier_rects: [[f32; 4]; 2],
    pub(crate) video_source_rects: [[f32; 4]; 2],
    pub(crate) zone: [f32; 4],
    pub(crate) stretch: [f32; 4],
    pub(crate) frame: [f32; 4],
    pub(crate) inner_threshold: [f32; 4],
    pub(crate) inner_dynamics: [f32; 4],
    pub(crate) inner_shape: [f32; 4],
    pub(crate) outer_threshold: [f32; 4],
    pub(crate) outer_dynamics: [f32; 4],
    pub(crate) outer_shape: [f32; 4],
    pub(crate) inner_channel_strength_mode: [f32; 4],
    pub(crate) inner_channel_amplitude_source: [f32; 4],
    pub(crate) inner_channel_hz_driver: [f32; 4],
    pub(crate) inner_channel_phase: [f32; 4],
    pub(crate) outer_channel_strength_mode: [f32; 4],
    pub(crate) outer_channel_amplitude_source: [f32; 4],
    pub(crate) outer_channel_hz_driver: [f32; 4],
    pub(crate) outer_channel_phase: [f32; 4],
}

const _: () = assert!(std::mem::size_of::<ProjectionZoneUniform>() == 368);

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct CameraHwbProjectionZoneFrame {
    pub(crate) settings: ProjectionZoneCompositorSettings,
    pub(crate) core_rects: [[f32; 4]; 2],
    pub(crate) user_rects: [[f32; 4]; 2],
    pub(crate) draw_rects: [[f32; 4]; 2],
    pub(crate) uniform: ProjectionZoneUniform,
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
const CAMERA_HWB_VIDEO_BORDER_INNER_BLEND_UV: f32 = 0.04;
const CAMERA_HWB_VIDEO_BORDER_BLEND_CURVE: f32 = 1.6;
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
static PROJECTION_ZONE_COMPOSITOR_SETTINGS: OnceLock<RwLock<ProjectionZoneCompositorSettings>> =
    OnceLock::new();

fn projection_zone_compositor_settings_lock() -> &'static RwLock<ProjectionZoneCompositorSettings> {
    PROJECTION_ZONE_COMPOSITOR_SETTINGS
        .get_or_init(|| RwLock::new(ProjectionZoneCompositorSettings::default()))
}

pub(crate) fn current_projection_zone_compositor_settings() -> ProjectionZoneCompositorSettings {
    projection_zone_compositor_settings_lock()
        .read()
        .map(|settings| *settings)
        .unwrap_or_default()
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn update_projection_zone_compositor_settings(
    coverage_mode: u32,
    region_contract_version: u32,
    buffer_geometry_mode: u32,
    buffer_static_width_uv: f32,
    buffer_fill_mode: u32,
    stretch_extent_mode: u32,
    stretch_source: u32,
    debug_mode: u32,
    outer_target_mode: u32,
    stretch_mapping: u32,
    projection_effect_edge_guard_enabled: bool,
    stretch_option_flags: u32,
    edge_inset_uv: f32,
    max_inset_uv: f32,
    stretch_curve: f32,
    processed_mix: f32,
    inner_signal: u32,
    inner_width_uv: f32,
    inner_curve: f32,
    inner_threshold_r: f32,
    inner_threshold_g: f32,
    inner_threshold_b: f32,
    inner_softness: f32,
    inner_strength: f32,
    inner_cycle_amplitude: f32,
    inner_cycle_hz: f32,
    inner_motion_gain: f32,
    outer_signal: u32,
    outer_width_uv: f32,
    outer_curve: f32,
    outer_threshold_r: f32,
    outer_threshold_g: f32,
    outer_threshold_b: f32,
    outer_softness: f32,
    outer_strength: f32,
    outer_cycle_amplitude: f32,
    outer_cycle_hz: f32,
    outer_motion_gain: f32,
) -> ProjectionZoneCompositorSettings {
    let (stretch_mapping, parameter_a, parameter_b, parameter_c) =
        normalize_projection_zone_edge_trail_parameters(
            stretch_mapping,
            edge_inset_uv,
            max_inset_uv,
            stretch_curve,
        );
    let settings = ProjectionZoneCompositorSettings {
        coverage_mode: coverage_mode.min(2),
        region_contract_version: region_contract_version.clamp(1, 2),
        buffer_geometry_mode: buffer_geometry_mode.min(2),
        buffer_static_width_uv: finite_or(buffer_static_width_uv, 0.08).clamp(0.0, 0.5),
        buffer_fill_mode: buffer_fill_mode.min(2),
        stretch_extent_mode: stretch_extent_mode.min(1),
        stretch_source: stretch_source.min(2),
        debug_mode: debug_mode.min(2),
        outer_target_mode: outer_target_mode.min(1),
        stretch_mapping,
        projection_effect_edge_guard_enabled,
        stretch_option_flags: stretch_option_flags & 0x1d,
        edge_inset_uv: parameter_a,
        max_inset_uv: parameter_b,
        stretch_curve: parameter_c,
        processed_mix: finite_or(processed_mix, 1.0).clamp(0.0, 1.0),
        inner_signal: inner_signal.min(4),
        inner_width_uv: finite_or(inner_width_uv, 0.04).clamp(0.0, 0.25),
        inner_curve: finite_or(inner_curve, 1.6).clamp(0.25, 6.0),
        inner_threshold_rgb: clamp_rgb([inner_threshold_r, inner_threshold_g, inner_threshold_b]),
        inner_softness: finite_or(inner_softness, 0.12).clamp(0.001, 0.5),
        inner_strength: finite_or(inner_strength, 0.0).clamp(0.0, 1.0),
        inner_cycle_amplitude: finite_or(inner_cycle_amplitude, 0.0).clamp(0.0, 0.5),
        inner_cycle_hz: finite_or(inner_cycle_hz, 0.12).clamp(0.0, 4.0),
        inner_motion_gain: finite_or(inner_motion_gain, 0.0).clamp(-0.5, 0.5),
        outer_signal: outer_signal.min(4),
        outer_width_uv: finite_or(outer_width_uv, 0.04).clamp(0.0, 0.25),
        outer_curve: finite_or(outer_curve, 1.6).clamp(0.25, 6.0),
        outer_threshold_rgb: clamp_rgb([outer_threshold_r, outer_threshold_g, outer_threshold_b]),
        outer_softness: finite_or(outer_softness, 0.12).clamp(0.001, 0.5),
        outer_strength: finite_or(outer_strength, 0.0).clamp(0.0, 1.0),
        outer_cycle_amplitude: finite_or(outer_cycle_amplitude, 0.0).clamp(0.0, 0.5),
        outer_cycle_hz: finite_or(outer_cycle_hz, 0.10).clamp(0.0, 4.0),
        outer_motion_gain: finite_or(outer_motion_gain, 0.0).clamp(-0.5, 0.5),
        ..current_projection_zone_compositor_settings()
    };
    if let Ok(mut current) = projection_zone_compositor_settings_lock().write() {
        *current = settings;
    }
    settings
}

fn normalize_projection_zone_edge_trail_parameters(
    mapping: u32,
    edge_inset_uv: f32,
    max_inset_uv: f32,
    curve: f32,
) -> (u32, f32, f32, f32) {
    if mapping != 0 {
        return (0, 0.015, 0.14, 1.6);
    }
    let edge_inset_uv = finite_or(edge_inset_uv, 0.015).clamp(0.0, 0.49);
    (
        0,
        edge_inset_uv,
        finite_or(max_inset_uv, 0.14).clamp(edge_inset_uv, 0.49),
        finite_or(curve, 1.6).clamp(0.25, 6.0),
    )
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn update_projection_zone_channel_dynamics_settings(
    inner_application_mode: u32,
    inner_source_choice: u32,
    inner_region_driver: u32,
    inner_strength_rgb: [f32; 3],
    inner_cycle_amplitude_rgb: [f32; 3],
    inner_cycle_hz_rgb: [f32; 3],
    inner_cycle_phase_rgb: [f32; 3],
    outer_application_mode: u32,
    outer_source_choice: u32,
    outer_region_driver: u32,
    outer_strength_rgb: [f32; 3],
    outer_cycle_amplitude_rgb: [f32; 3],
    outer_cycle_hz_rgb: [f32; 3],
    outer_cycle_phase_rgb: [f32; 3],
) -> ProjectionZoneCompositorSettings {
    let current = current_projection_zone_compositor_settings();
    let settings = ProjectionZoneCompositorSettings {
        inner_application_mode: inner_application_mode.min(2),
        inner_source_choice: inner_source_choice.min(2),
        inner_region_driver: inner_region_driver.min(4),
        inner_strength_rgb: clamp_triplet(inner_strength_rgb, [0.0; 3], 0.0, 1.0),
        inner_cycle_amplitude_rgb: clamp_triplet(inner_cycle_amplitude_rgb, [0.0; 3], 0.0, 0.5),
        inner_cycle_hz_rgb: clamp_triplet(inner_cycle_hz_rgb, [0.12, 0.17, 0.23], 0.0, 4.0),
        inner_cycle_phase_rgb: clamp_triplet(
            inner_cycle_phase_rgb,
            [0.0, 0.333333, 0.666667],
            -4.0,
            4.0,
        ),
        outer_application_mode: outer_application_mode.min(2),
        outer_source_choice: outer_source_choice.min(2),
        outer_region_driver: outer_region_driver.min(4),
        outer_strength_rgb: clamp_triplet(outer_strength_rgb, [0.0; 3], 0.0, 1.0),
        outer_cycle_amplitude_rgb: clamp_triplet(outer_cycle_amplitude_rgb, [0.0; 3], 0.0, 0.5),
        outer_cycle_hz_rgb: clamp_triplet(outer_cycle_hz_rgb, [0.10, 0.14, 0.19], 0.0, 4.0),
        outer_cycle_phase_rgb: clamp_triplet(
            outer_cycle_phase_rgb,
            [0.0, 0.333333, 0.666667],
            -4.0,
            4.0,
        ),
        ..current
    };
    if let Ok(mut applied) = projection_zone_compositor_settings_lock().write() {
        *applied = settings;
    }
    settings
}

fn clamp_rgb(rgb: [f32; 3]) -> [f32; 3] {
    [
        finite_or(rgb[0], 0.5).clamp(0.0, 1.0),
        finite_or(rgb[1], 0.5).clamp(0.0, 1.0),
        finite_or(rgb[2], 0.5).clamp(0.0, 1.0),
    ]
}

fn clamp_triplet(values: [f32; 3], fallbacks: [f32; 3], minimum: f32, maximum: f32) -> [f32; 3] {
    [
        finite_or(values[0], fallbacks[0]).clamp(minimum, maximum),
        finite_or(values[1], fallbacks[1]).clamp(minimum, maximum),
        finite_or(values[2], fallbacks[2]).clamp(minimum, maximum),
    ]
}

fn coverage_mode_token(mode: u32) -> &'static str {
    match mode {
        1 => "dynamic-buffer",
        2 => "replace-video",
        _ => "off",
    }
}

fn buffer_geometry_token(mode: u32) -> &'static str {
    match mode {
        1 => "static",
        2 => "dynamic",
        _ => "off",
    }
}

fn buffer_fill_token(mode: u32) -> &'static str {
    match mode {
        1 => "transparent-reveal",
        2 => "stretch",
        _ => "outer-continuation",
    }
}

fn stretch_extent_token(mode: u32) -> &'static str {
    if mode == 1 { "replace-outer" } else { "buffer-only" }
}

fn stretch_source_token(source: u32) -> &'static str {
    match source {
        0 => "raw-camera",
        2 => "mixed",
        _ => "processed-layer",
    }
}

fn stretch_mapping_token(_mapping: u32) -> &'static str {
    "graded-edge-trail-native"
}

fn blend_signal_token(signal: u32) -> &'static str {
    match signal {
        1 => "rgb",
        2 => "luma",
        3 => "chroma",
        4 => "difference",
        _ => "flat",
    }
}

fn blend_application_token(mode: u32) -> &'static str {
    match mode {
        1 => "component",
        2 => "region",
        _ => "legacy",
    }
}

fn blend_source_choice_token(source: u32) -> &'static str {
    match source {
        0 => "outgoing",
        2 => "incoming",
        _ => "midpoint",
    }
}

fn blend_region_driver_token(driver: u32) -> &'static str {
    match driver {
        0 => "red",
        1 => "green",
        2 => "blue",
        4 => "max",
        _ => "luma",
    }
}

fn debug_mode_token(mode: u32) -> &'static str {
    match mode {
        1 => "regions",
        2 => "sample-uv",
        _ => "off",
    }
}

fn outer_target_mode_token(mode: u32) -> &'static str {
    match mode {
        1 => "transparent-spatial-video",
        _ => "readable-color",
    }
}

#[allow(dead_code)]
pub(crate) fn update_camera_hwb_projection_target_live_scale(scale: f32) -> f32 {
    let applied = presentation_projection_scale(finite_or(
        scale,
        CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT,
    ))
    .clamp(
        CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
        CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
    );
    CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_BITS.store(applied.to_bits(), Ordering::Release);
    applied
}

pub(crate) fn current_camera_hwb_projection_target_live_scale() -> f32 {
    presentation_projection_scale(f32::from_bits(
        CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_BITS.load(Ordering::Acquire),
    ))
    .clamp(
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
            current_spatial_guide_processing_policy()
                .camera_sampling
                .raw_projection_push_code(),
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

pub(crate) fn camera_hwb_projection_zone_frame(
    footprint_scale: f32,
    source_overscan_uv: f32,
    elapsed_seconds: f32,
    video_source_rects: [[f32; 4]; 2],
) -> CameraHwbProjectionZoneFrame {
    let settings = current_projection_zone_compositor_settings();
    camera_hwb_projection_zone_frame_with_settings(
        footprint_scale,
        source_overscan_uv,
        elapsed_seconds,
        video_source_rects,
        settings,
    )
}

fn camera_hwb_projection_zone_frame_with_settings(
    footprint_scale: f32,
    source_overscan_uv: f32,
    elapsed_seconds: f32,
    video_source_rects: [[f32; 4]; 2],
    settings: ProjectionZoneCompositorSettings,
) -> CameraHwbProjectionZoneFrame {
    let (stretch_mapping, edge_inset_uv, max_inset_uv, stretch_curve) =
        normalize_projection_zone_edge_trail_parameters(
            settings.stretch_mapping,
            settings.edge_inset_uv,
            settings.max_inset_uv,
            settings.stretch_curve,
        );
    let settings = ProjectionZoneCompositorSettings {
        stretch_mapping,
        edge_inset_uv,
        max_inset_uv,
        stretch_curve,
        ..settings
    };
    let live_scale = current_camera_hwb_projection_target_live_scale();
    let stereo_horizontal_offset_uv = current_camera_hwb_projection_stereo_horizontal_offset_uv();
    let (left_user, right_user) =
        effective_target_rects_for_scale_and_stereo_offset(live_scale, stereo_horizontal_offset_uv);
    let left_core = effective_rect(left_user, footprint_scale, 0.0, 0.0);
    let right_core = effective_rect(right_user, footprint_scale, 0.0, 0.0);
    let user_rects = [
        packed_left_rect(left_user).as_push(),
        packed_right_rect(right_user).as_push(),
    ];
    let core_rects = [
        packed_left_rect(left_core).as_push(),
        packed_right_rect(right_core).as_push(),
    ];
    let carrier_rects = [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]];
    let buffer_rects = if settings.region_contract_version >= 2 {
        match settings.buffer_geometry_mode {
            1 => [
                expand_packed_rect_within(
                    core_rects[0],
                    settings.buffer_static_width_uv,
                    user_rects[0],
                ),
                expand_packed_rect_within(
                    core_rects[1],
                    settings.buffer_static_width_uv,
                    user_rects[1],
                ),
            ],
            2 => user_rects,
            _ => core_rects,
        }
    } else {
        user_rects
    };
    let draw_rects = if settings.region_contract_version >= 2 {
        if settings.replaces_video() {
            carrier_rects
        } else {
            let transition_width = if settings.buffer_geometry_mode == 0 {
                settings.inner_width_uv.max(settings.outer_width_uv)
            } else {
                settings.outer_width_uv
            };
            [
                expand_packed_rect_within(
                    buffer_rects[0],
                    transition_width,
                    carrier_rects[0],
                ),
                expand_packed_rect_within(
                    buffer_rects[1],
                    transition_width,
                    carrier_rects[1],
                ),
            ]
        }
    } else {
        match settings.coverage_mode {
            1 => [
                expand_packed_rect_within(user_rects[0], settings.outer_width_uv, carrier_rects[0]),
                expand_packed_rect_within(user_rects[1], settings.outer_width_uv, carrier_rects[1]),
            ],
            2 => carrier_rects,
            _ => core_rects,
        }
    };
    let motion_envelope = ((1.0 - footprint_scale.clamp(0.0, 1.0)) / 0.2)
        .max(source_overscan_uv.clamp(0.0, 0.2) / 0.2)
        .clamp(0.0, 1.0);
    let uniform = ProjectionZoneUniform {
        user_rects: if settings.region_contract_version >= 2 {
            buffer_rects
        } else {
            user_rects
        },
        carrier_rects,
        video_source_rects,
        zone: [
            settings.coverage_mode as f32,
            settings.stretch_source as f32,
            settings.debug_mode as f32,
            settings.processed_mix,
        ],
        stretch: [
            settings.edge_inset_uv,
            settings.max_inset_uv,
            settings.stretch_curve,
            (settings.stretch_mapping
                | settings.stretch_option_flags
                | if settings.projection_effect_edge_guard_enabled {
                    0
                } else {
                    1 << 1
                }
                | if settings.region_contract_version >= 2 {
                    (1 << 8)
                        | ((settings.buffer_geometry_mode & 0x3) << 9)
                        | ((settings.buffer_fill_mode & 0x3) << 11)
                        | ((settings.stretch_extent_mode & 0x1) << 13)
                } else {
                    0
                }) as f32,
        ],
        frame: [
            footprint_scale.clamp(0.0, 1.0),
            source_overscan_uv.clamp(0.0, 0.2),
            motion_envelope,
            elapsed_seconds.max(0.0),
        ],
        inner_threshold: [
            settings.inner_threshold_rgb[0],
            settings.inner_threshold_rgb[1],
            settings.inner_threshold_rgb[2],
            settings.inner_softness,
        ],
        inner_dynamics: [
            settings.inner_strength,
            settings.inner_cycle_amplitude,
            settings.inner_cycle_hz,
            settings.inner_motion_gain,
        ],
        inner_shape: [
            settings.inner_signal as f32,
            settings.inner_width_uv,
            settings.inner_curve,
            settings.buffer_static_width_uv,
        ],
        outer_threshold: [
            settings.outer_threshold_rgb[0],
            settings.outer_threshold_rgb[1],
            settings.outer_threshold_rgb[2],
            settings.outer_softness,
        ],
        outer_dynamics: [
            settings.outer_strength,
            settings.outer_cycle_amplitude,
            settings.outer_cycle_hz,
            settings.outer_motion_gain,
        ],
        outer_shape: [
            settings.outer_signal as f32,
            settings.outer_width_uv,
            settings.outer_curve,
            settings.outer_target_mode as f32,
        ],
        inner_channel_strength_mode: [
            settings.inner_strength_rgb[0],
            settings.inner_strength_rgb[1],
            settings.inner_strength_rgb[2],
            settings.inner_application_mode as f32,
        ],
        inner_channel_amplitude_source: [
            settings.inner_cycle_amplitude_rgb[0],
            settings.inner_cycle_amplitude_rgb[1],
            settings.inner_cycle_amplitude_rgb[2],
            settings.inner_source_choice as f32,
        ],
        inner_channel_hz_driver: [
            settings.inner_cycle_hz_rgb[0],
            settings.inner_cycle_hz_rgb[1],
            settings.inner_cycle_hz_rgb[2],
            settings.inner_region_driver as f32,
        ],
        inner_channel_phase: [
            settings.inner_cycle_phase_rgb[0],
            settings.inner_cycle_phase_rgb[1],
            settings.inner_cycle_phase_rgb[2],
            1.0,
        ],
        outer_channel_strength_mode: [
            settings.outer_strength_rgb[0],
            settings.outer_strength_rgb[1],
            settings.outer_strength_rgb[2],
            settings.outer_application_mode as f32,
        ],
        outer_channel_amplitude_source: [
            settings.outer_cycle_amplitude_rgb[0],
            settings.outer_cycle_amplitude_rgb[1],
            settings.outer_cycle_amplitude_rgb[2],
            settings.outer_source_choice as f32,
        ],
        outer_channel_hz_driver: [
            settings.outer_cycle_hz_rgb[0],
            settings.outer_cycle_hz_rgb[1],
            settings.outer_cycle_hz_rgb[2],
            settings.outer_region_driver as f32,
        ],
        outer_channel_phase: [
            settings.outer_cycle_phase_rgb[0],
            settings.outer_cycle_phase_rgb[1],
            settings.outer_cycle_phase_rgb[2],
            1.0,
        ],
    };
    CameraHwbProjectionZoneFrame {
        settings,
        core_rects,
        user_rects,
        draw_rects,
        uniform,
    }
}

fn expand_packed_rect_within(rect: [f32; 4], local_width_uv: f32, carrier: [f32; 4]) -> [f32; 4] {
    let expand_x = rect[2] * local_width_uv.clamp(0.0, 0.25);
    let expand_y = rect[3] * local_width_uv.clamp(0.0, 0.25);
    let min_x = (rect[0] - expand_x).max(carrier[0]);
    let min_y = (rect[1] - expand_y).max(carrier[1]);
    let max_x = (rect[0] + rect[2] + expand_x).min(carrier[0] + carrier[2]);
    let max_y = (rect[1] + rect[3] + expand_y).min(carrier[1] + carrier[3]);
    [min_x, min_y, max_x - min_x, max_y - min_y]
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
    + &format!(
        " projectionBlendPolicy=premultiplied-alpha-over-same-surface-video rawCustomProjectionBorderBlend=true opaqueProjectionBorderBlend=true videoBorderInnerBlendUv={:.3} videoBorderBlendCurve={:.3} publicCameraSampling={} publicCameraSamplingRadiusTexels={:.2} {}",
        CAMERA_HWB_VIDEO_BORDER_INNER_BLEND_UV,
        CAMERA_HWB_VIDEO_BORDER_BLEND_CURVE,
        current_spatial_guide_processing_policy()
            .camera_sampling
            .marker_token(),
        current_spatial_guide_processing_policy()
            .camera_sampling
            .radius_texels(),
        current_projection_zone_compositor_settings().marker_fields(),
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
        assert_eq!(push.params[0], 1.0);
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

    #[test]
    fn transparent_spatial_video_underlay_requires_outgoing_region_alpha() {
        let supported = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            debug_mode: 0,
            outer_target_mode: 1,
            outer_signal: 1,
            outer_application_mode: 2,
            outer_source_choice: 0,
            outer_region_driver: 4,
            ..ProjectionZoneCompositorSettings::default()
        };
        assert!(supported.transparent_underlay_requested());
        assert!(supported.transparent_underlay_supported());
        let unsupported = ProjectionZoneCompositorSettings {
            outer_application_mode: 1,
            outer_source_choice: 2,
            ..supported
        };
        assert!(unsupported.transparent_underlay_requested());
        assert!(!unsupported.transparent_underlay_supported());
        let marker = unsupported.marker_fields();
        assert!(marker.contains("projectionZoneOuterTarget=transparent-spatial-video"));
        assert!(marker.contains("projectionZoneOuterUnderlaySupported=false"));
        assert!(marker.contains("projectionZoneUnsampledOuterData=true"));
    }

    #[test]
    fn stretch_off_keeps_transparent_underlay_supported_without_an_outer_seam() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 0,
            outer_target_mode: 1,
            ..ProjectionZoneCompositorSettings::default()
        };
        assert!(!settings.active());
        assert!(settings.transparent_underlay_requested());
        assert!(settings.transparent_underlay_supported());
        let marker = settings.marker_fields();
        assert!(marker.contains("projectionZoneCompositorMode=off"));
        assert!(marker.contains("projectionZoneOuterTarget=transparent-spatial-video"));
        assert!(marker.contains("projectionZoneOuterUnderlaySupported=true"));
        assert!(settings.suppresses_same_surface_video(false));
    }

    #[test]
    fn readable_replacement_suppresses_video_only_when_its_pipeline_is_ready() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 2,
            ..ProjectionZoneCompositorSettings::default()
        };
        assert!(settings.suppresses_same_surface_video(true));
        assert!(!settings.suppresses_same_surface_video(false));
    }

    #[test]
    fn transparent_underlay_mode_is_packed_without_expanding_the_uniform_abi() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            outer_target_mode: 1,
            outer_signal: 1,
            outer_application_mode: 2,
            outer_source_choice: 0,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            1.0,
            0.0,
            0.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        assert_eq!(std::mem::size_of::<ProjectionZoneUniform>(), 23 * 16);
        assert_eq!(frame.uniform.outer_shape[3], 1.0);
    }

    #[test]
    fn projection_zone_applies_user_scale_before_dynamic_core_contraction() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            outer_width_uv: 0.05,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            0.8,
            0.04,
            3.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        for eye in 0..2 {
            let user = frame.user_rects[eye];
            let core = frame.core_rects[eye];
            assert!((core[2] - user[2] * 0.8).abs() < 0.000001);
            assert!((core[3] - user[3] * 0.8).abs() < 0.000001);
            assert!((core[0] + core[2] * 0.5 - (user[0] + user[2] * 0.5)).abs() < 0.000001);
            assert!((core[1] + core[3] * 0.5 - (user[1] + user[3] * 0.5)).abs() < 0.000001);
            assert!(frame.draw_rects[eye][2] > user[2]);
            assert!(frame.draw_rects[eye][3] > user[3]);
        }
        assert!((frame.uniform.frame[2] - 1.0).abs() < 0.000001);
        assert_eq!(frame.uniform.user_rects, frame.user_rects);
        assert_eq!(frame.uniform.stretch[3], 0.0);
        assert!(frame
            .settings
            .marker_fields()
            .contains("projectionZoneStretchMapping=graded-edge-trail-native"));
    }

    #[test]
    fn projection_zone_effect_edge_guard_disable_is_packed_without_expanding_the_uniform_abi() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            projection_effect_edge_guard_enabled: false,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            1.0,
            0.0,
            0.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        assert_eq!(frame.uniform.stretch[3], 2.0);
        assert!(frame
            .settings
            .marker_fields()
            .contains("projectionZoneEffectEdgeGuardEnabled=false"));
    }

    #[test]
    fn projection_zone_stretch_option_flags_are_packed_without_expanding_the_uniform_abi() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            stretch_option_flags: 0x1d,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            1.0,
            0.0,
            0.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        assert_eq!(frame.uniform.stretch[3], 29.0);
        assert!(frame
            .settings
            .marker_fields()
            .contains("projectionZoneStretchOptionFlags=29"));
    }

    #[test]
    fn independent_region_contract_packs_orthogonal_modes_without_expanding_uniform_abi() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            region_contract_version: 2,
            buffer_geometry_mode: 1,
            buffer_static_width_uv: 0.12,
            buffer_fill_mode: 2,
            stretch_extent_mode: 1,
            stretch_option_flags: 0x08,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            0.8,
            0.0,
            0.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        let expected_flags = (1 << 8) | (1 << 9) | (2 << 11) | (1 << 13) | 0x08;
        assert_eq!(std::mem::size_of::<ProjectionZoneUniform>(), 23 * 16);
        assert_eq!(frame.uniform.stretch[3], expected_flags as f32);
        assert_eq!(frame.uniform.inner_shape[3], 0.12);
        assert!(frame.settings.active());
        assert!(frame.settings.replaces_video());
        assert_eq!(frame.draw_rects[0], [0.0, 0.0, 0.5, 1.0]);
        assert_eq!(frame.draw_rects[1], [0.5, 0.0, 0.5, 1.0]);
        let marker = frame.settings.marker_fields();
        assert!(marker.contains("projectionRegionContract=v2"));
        assert!(marker.contains("projectionBufferGeometry=static"));
        assert!(marker.contains("projectionBufferFill=stretch"));
        assert!(marker.contains("projectionStretchExtent=replace-outer"));
    }

    #[test]
    fn independent_static_buffer_has_fixed_boundary_and_off_still_runs_direct_transition() {
        let static_settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            region_contract_version: 2,
            buffer_geometry_mode: 1,
            buffer_static_width_uv: 0.10,
            buffer_fill_mode: 0,
            ..ProjectionZoneCompositorSettings::default()
        };
        let static_frame = camera_hwb_projection_zone_frame_with_settings(
            0.8,
            0.0,
            0.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            static_settings,
        );
        for eye in 0..2 {
            assert!(static_frame.uniform.user_rects[eye][2] > static_frame.core_rects[eye][2]);
            assert!(static_frame.uniform.user_rects[eye][2] < static_frame.user_rects[eye][2]);
        }

        let off_settings = ProjectionZoneCompositorSettings {
            coverage_mode: 0,
            region_contract_version: 2,
            buffer_geometry_mode: 0,
            buffer_fill_mode: 2,
            stretch_extent_mode: 1,
            ..ProjectionZoneCompositorSettings::default()
        };
        let off_frame = camera_hwb_projection_zone_frame_with_settings(
            0.8,
            0.0,
            0.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            off_settings,
        );
        assert!(off_frame.settings.active());
        assert!(!off_frame.settings.replaces_video());
        assert_eq!(off_frame.uniform.user_rects, off_frame.core_rects);
    }

    #[test]
    fn projection_zone_legacy_lens_request_is_coerced_to_native_edge_trail() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            stretch_mapping: 1,
            edge_inset_uv: 0.16,
            max_inset_uv: 0.18,
            stretch_curve: 0.12,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            0.8,
            0.04,
            3.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        assert_eq!(frame.uniform.stretch, [0.015, 0.14, 1.6, 0.0]);
        assert!(frame
            .settings
            .marker_fields()
            .contains("projectionZoneStretchMapping=graded-edge-trail-native"));
        assert!(!frame
            .settings
            .marker_fields()
            .contains("mirrored-lens-native"));
    }

    #[test]
    fn projection_zone_replace_video_uses_full_per_eye_carrier() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 2,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            0.7,
            0.06,
            1.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        assert_eq!(frame.draw_rects[0], [0.0, 0.0, 0.5, 1.0]);
        assert_eq!(frame.draw_rects[1], [0.5, 0.0, 0.5, 1.0]);
        assert!(frame.settings.replaces_video());
    }

    #[test]
    fn projection_zone_uniform_layout_is_vec4_aligned() {
        assert_eq!(std::mem::size_of::<ProjectionZoneUniform>(), 23 * 16);
        assert_eq!(std::mem::align_of::<ProjectionZoneUniform>(), 4);
    }

    #[test]
    fn projection_zone_uniform_carries_independent_seam_channel_dynamics() {
        let settings = ProjectionZoneCompositorSettings {
            coverage_mode: 1,
            inner_application_mode: 1,
            inner_source_choice: 0,
            inner_region_driver: 2,
            inner_strength_rgb: [0.9, 0.5, 0.2],
            inner_cycle_amplitude_rgb: [0.14, 0.10, 0.07],
            inner_cycle_hz_rgb: [0.10, 0.17, 0.26],
            inner_cycle_phase_rgb: [0.0, 0.333333, 0.666667],
            outer_application_mode: 2,
            outer_source_choice: 2,
            outer_region_driver: 3,
            ..ProjectionZoneCompositorSettings::default()
        };
        let frame = camera_hwb_projection_zone_frame_with_settings(
            0.8,
            0.04,
            3.0,
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
            settings,
        );
        assert_eq!(
            frame.uniform.inner_channel_strength_mode,
            [0.9, 0.5, 0.2, 1.0]
        );
        assert_eq!(
            frame.uniform.inner_channel_amplitude_source,
            [0.14, 0.10, 0.07, 0.0]
        );
        assert_eq!(
            frame.uniform.inner_channel_hz_driver,
            [0.10, 0.17, 0.26, 2.0]
        );
        assert_eq!(
            frame.uniform.inner_channel_phase,
            [0.0, 0.333333, 0.666667, 1.0]
        );
        assert_eq!(frame.uniform.outer_channel_strength_mode[3], 2.0);
        assert_eq!(frame.uniform.outer_channel_amplitude_source[3], 2.0);
        assert_eq!(frame.uniform.outer_channel_hz_driver[3], 3.0);
    }
}
