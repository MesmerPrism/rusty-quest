//! Marker policy and frame/readback statistics for environment-depth particles.

use std::mem;

use ash::vk;

use crate::{
    native_renderer_options::NativeEnvironmentDepthSettings,
    openxr_environment_depth::{
        OpenXrEnvironmentDepthFrame, OpenXrEnvironmentDepthHeadMotionStats,
    },
};

pub(crate) const META_ENVIRONMENT_DEPTH_FORMAT: vk::Format = vk::Format::D16_UNORM;
pub(crate) const META_ENVIRONMENT_DEPTH_LAYER_COUNT: u32 = 2;
pub(crate) const META_ENVIRONMENT_DEPTH_DEPTH_VIEW_VALID_MASK: &str = "0x1";
pub(crate) const META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_LABEL: &str = "rotate0+flipY";
pub(crate) const META_ENVIRONMENT_DEPTH_RAY_UV_POLICY_LABEL: &str = "canonical-untransformed";
pub(crate) const META_ENVIRONMENT_DEPTH_SAMPLE_UV_POLICY_LABEL: &str = "texture-transformed";
const META_ENVIRONMENT_DEPTH_CONFIDENCE_FILTER_LABEL: &str =
    "edge-aware-4tap-discontinuity-isolated-reject-v1";
const META_ENVIRONMENT_DEPTH_FREE_SPACE_RANGE_POLICY_LABEL: &str = "near-plus-cell-step-cap";
const META_ENVIRONMENT_DEPTH_SCENE_CONFIDENCE_THRESHOLD: f32 = 0.58;
const META_ENVIRONMENT_DEPTH_FREE_SPACE_CONFIDENCE_THRESHOLD: f32 = 0.78;
pub(crate) const META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_FLAGS: f32 = 8.0;
pub(crate) const DEPTH_FLAG_INFINITE_FAR: u32 = 1;
pub(crate) const DEPTH_FLAG_SCENE_PARTICLE_MAP: u32 = 2;
pub(crate) const DEPTH_FLAG_SOURCE_LAYER1: u32 = 4;
const DEPTH_FLAG_SURFACE_SUPPORT_ENFORCED: u32 = 8;
const DEPTH_FLAG_SURFACE_SUPPORT_LOCAL: u32 = 16;
const DEPTH_FLAG_SURFACE_SUPPORT_GLOBAL: u32 = 32;
const DEPTH_FLAG_SURFACE_SUPPORT_HYBRID: u32 = 64;
const DEPTH_FLAG_SURFACE_SUPPORT_MIN_SOURCE_LAYERS_TWO: u32 = 128;
const DEPTH_FLAG_SURFACE_SUPPORT_MIN_NEIGHBOR_SHIFT: u32 = 8;
const DEPTH_FLAG_SURFACE_SUPPORT_RADIUS_SHIFT: u32 = 16;
const DEPTH_FLAG_SURFACE_SUPPORT_MIN_OBSERVATION_SHIFT: u32 = 20;
pub(crate) const ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_U32_COUNT: usize = 42;
pub(crate) const ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_BYTES: vk::DeviceSize =
    (ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_U32_COUNT * mem::size_of::<u32>()) as vk::DeviceSize;
const RAW_DEBUG_VALID_COUNT_INDEX: usize = 0;
const RAW_DEBUG_INVALID_COUNT_INDEX: usize = 1;
const RAW_DEBUG_CONFIDENCE_REJECTED_COUNT_INDEX: usize = 2;
const RAW_DEBUG_CENTER_D16_INDEX: usize = 3;
const RAW_DEBUG_CENTER_RECONSTRUCTED_MM_INDEX: usize = 4;
const RAW_DEBUG_CENTER_CONFIDENCE_MILLI_INDEX: usize = 5;
const RAW_DEBUG_CENTER_MEDIAN_D16_INDEX: usize = 6;
const RAW_DEBUG_MIN_VALID_INVERSE_MM_INDEX: usize = 7;
const RAW_DEBUG_MAX_VALID_MM_INDEX: usize = 8;
const RAW_DEBUG_CENTER_WINDOW_VALID_COUNT_INDEX: usize = 9;
const RAW_DEBUG_HASH_INSERT_SUCCESS_COUNT_INDEX: usize = 10;
const RAW_DEBUG_HASH_MERGE_COUNT_INDEX: usize = 11;
const RAW_DEBUG_HASH_STALE_REPLACE_COUNT_INDEX: usize = 12;
const RAW_DEBUG_HASH_PROBE_EXHAUSTED_COUNT_INDEX: usize = 13;
const RAW_DEBUG_FREE_SPACE_RETIRE_ATTEMPT_COUNT_INDEX: usize = 14;
const RAW_DEBUG_FREE_SPACE_RETIRE_SUCCESS_COUNT_INDEX: usize = 15;
const RAW_DEBUG_HASH_OCCUPANCY_ESTIMATE_INDEX: usize = 16;
const RAW_DEBUG_HASH_WRITE_CONFLICT_COUNT_INDEX: usize = 17;
const RAW_DEBUG_HASH_CLAIM_FAILED_COUNT_INDEX: usize = 18;
const RAW_DEBUG_FREE_SPACE_CONFIDENCE_SKIPPED_COUNT_INDEX: usize = 19;
const RAW_DEBUG_SURFACE_SUPPORTED_CELLS_INDEX: usize = 20;
const RAW_DEBUG_SURFACE_REJECTED_ISOLATED_CELLS_INDEX: usize = 21;
const RAW_DEBUG_SURFACE_CANDIDATE_CELLS_INDEX: usize = 22;
const RAW_DEBUG_SURFACE_CONFIRMED_CELLS_INDEX: usize = 23;
const RAW_DEBUG_SURFACE_PROMOTED_CELLS_INDEX: usize = 24;
const RAW_DEBUG_SURFACE_CANDIDATE_RETIRED_CELLS_INDEX: usize = 25;
const RAW_DEBUG_SOURCE_LAYER_AGREEMENT_CELLS_INDEX: usize = 26;
const RAW_DEBUG_SINGLE_LAYER_ONLY_CELLS_INDEX: usize = 27;
const RAW_DEBUG_SURFACE_NORMAL_VALID_CELLS_INDEX: usize = 28;
const RAW_DEBUG_SURFACE_NORMAL_INVALID_CELLS_INDEX: usize = 29;
const RAW_DEBUG_SURFACE_NORMAL_REJECTED_CELLS_INDEX: usize = 30;
const RAW_DEBUG_SURFACE_COMPONENT_LARGEST_CELLS_INDEX: usize = 31;
const RAW_DEBUG_SURFACE_COMPONENT_SMALL_REJECTED_CELLS_INDEX: usize = 32;
const RAW_DEBUG_SURFACE_COMPONENT_CANDIDATE_CELLS_INDEX: usize = 33;
const RAW_DEBUG_SURFACE_COMPONENT_CONFIRMED_CELLS_INDEX: usize = 34;
const RAW_DEBUG_RAW_SAMPLE_COUNT_INDEX: usize = 35;
const RAW_DEBUG_RAW_ZERO_D16_COUNT_INDEX: usize = 36;
const RAW_DEBUG_RAW_MAX_D16_COUNT_INDEX: usize = 37;
const RAW_DEBUG_RAW_MIDDLE_D16_COUNT_INDEX: usize = 38;
const RAW_DEBUG_RAW_MIN_INVERSE_D16_INDEX: usize = 39;
const RAW_DEBUG_RAW_MAX_D16_INDEX: usize = 40;
const RAW_DEBUG_RAW_CENTER_D16_INDEX: usize = 41;
pub(crate) const SCENE_PARTICLE_CELL_METERS: f32 = 0.06;
pub(crate) const SCENE_PARTICLE_HASH_PROBE_COUNT: u32 = 8;
pub(crate) const SCENE_PARTICLE_STALE_FADE_START_FRAMES: u32 = 720;
pub(crate) const SCENE_PARTICLE_STALE_RETIRE_FRAMES: u32 = 1440;
const SURFACE_SUPPORT_RUNTIME_STATUS: &str =
    "enforced-local-depth-neighborhood-component-local-hint";
const SURFACE_LIFECYCLE_RUNTIME_STATUS: &str = "candidate-confirmed-local-depth-neighborhood";

#[derive(Clone, Copy, Debug)]
pub(crate) struct EnvironmentDepthRawDebugStats {
    status: &'static str,
    valid_sample_count: u32,
    invalid_sample_count: u32,
    confidence_rejected_count: u32,
    center_d16: u32,
    center_reconstructed_m: f32,
    center_confidence: f32,
    center_window_median_d16: u32,
    center_window_valid_count: u32,
    min_valid_reconstructed_m: f32,
    max_valid_reconstructed_m: f32,
    hash_insert_success_count: u32,
    hash_merge_count: u32,
    hash_stale_replace_count: u32,
    hash_probe_exhausted_count: u32,
    free_space_retire_attempt_count: u32,
    free_space_retire_success_count: u32,
    hash_occupancy_estimate: u32,
    hash_write_conflict_count: u32,
    hash_claim_failed_count: u32,
    free_space_confidence_skipped_count: u32,
    surface_supported_cells: u32,
    surface_rejected_isolated_cells: u32,
    surface_candidate_cells: u32,
    surface_confirmed_cells: u32,
    surface_promoted_cells: u32,
    surface_candidate_retired_cells: u32,
    source_layer_agreement_cells: u32,
    single_layer_only_cells: u32,
    surface_normal_valid_cells: u32,
    surface_normal_invalid_cells: u32,
    surface_normal_rejected_cells: u32,
    surface_component_largest_cells: u32,
    surface_component_small_rejected_cells: u32,
    surface_component_candidate_cells: u32,
    surface_component_confirmed_cells: u32,
    raw_sample_count: u32,
    raw_zero_d16_count: u32,
    raw_max_d16_count: u32,
    raw_middle_d16_count: u32,
    raw_min_d16: u32,
    raw_max_d16: u32,
    raw_center_d16: u32,
}

impl EnvironmentDepthRawDebugStats {
    pub(crate) fn unavailable() -> Self {
        Self {
            status: "unavailable",
            valid_sample_count: 0,
            invalid_sample_count: 0,
            confidence_rejected_count: 0,
            center_d16: 0,
            center_reconstructed_m: 0.0,
            center_confidence: 0.0,
            center_window_median_d16: 0,
            center_window_valid_count: 0,
            min_valid_reconstructed_m: 0.0,
            max_valid_reconstructed_m: 0.0,
            hash_insert_success_count: 0,
            hash_merge_count: 0,
            hash_stale_replace_count: 0,
            hash_probe_exhausted_count: 0,
            free_space_retire_attempt_count: 0,
            free_space_retire_success_count: 0,
            hash_occupancy_estimate: 0,
            hash_write_conflict_count: 0,
            hash_claim_failed_count: 0,
            free_space_confidence_skipped_count: 0,
            surface_supported_cells: 0,
            surface_rejected_isolated_cells: 0,
            surface_candidate_cells: 0,
            surface_confirmed_cells: 0,
            surface_promoted_cells: 0,
            surface_candidate_retired_cells: 0,
            source_layer_agreement_cells: 0,
            single_layer_only_cells: 0,
            surface_normal_valid_cells: 0,
            surface_normal_invalid_cells: 0,
            surface_normal_rejected_cells: 0,
            surface_component_largest_cells: 0,
            surface_component_small_rejected_cells: 0,
            surface_component_candidate_cells: 0,
            surface_component_confirmed_cells: 0,
            raw_sample_count: 0,
            raw_zero_d16_count: 0,
            raw_max_d16_count: 0,
            raw_middle_d16_count: 0,
            raw_min_d16: 0,
            raw_max_d16: 0,
            raw_center_d16: 0,
        }
    }

    fn pending() -> Self {
        Self {
            status: "pending-gpu-readback",
            ..Self::unavailable()
        }
    }

    pub(crate) fn from_raw(values: &[u32]) -> Self {
        let valid_sample_count = values[RAW_DEBUG_VALID_COUNT_INDEX];
        let invalid_sample_count = values[RAW_DEBUG_INVALID_COUNT_INDEX];
        let confidence_rejected_count = values[RAW_DEBUG_CONFIDENCE_REJECTED_COUNT_INDEX];
        let center_d16 = values[RAW_DEBUG_CENTER_D16_INDEX];
        let center_window_valid_count = values[RAW_DEBUG_CENTER_WINDOW_VALID_COUNT_INDEX];
        let raw_sample_count = values[RAW_DEBUG_RAW_SAMPLE_COUNT_INDEX];
        if valid_sample_count == 0
            && invalid_sample_count == 0
            && confidence_rejected_count == 0
            && center_d16 == 0
            && center_window_valid_count == 0
            && raw_sample_count == 0
        {
            return Self::pending();
        }
        let min_valid_inverse_mm = values[RAW_DEBUG_MIN_VALID_INVERSE_MM_INDEX];
        let min_valid_mm = if valid_sample_count == 0 || min_valid_inverse_mm == 0 {
            0
        } else {
            u32::MAX.saturating_sub(min_valid_inverse_mm)
        };
        let raw_min_inverse_d16 = values[RAW_DEBUG_RAW_MIN_INVERSE_D16_INDEX];
        let raw_min_d16 = if raw_sample_count == 0 {
            0
        } else {
            65535u32.saturating_sub(raw_min_inverse_d16.min(65535))
        };
        Self {
            status: if valid_sample_count == 0 {
                "readback-no-valid-depth"
            } else {
                "readback"
            },
            valid_sample_count,
            invalid_sample_count,
            confidence_rejected_count,
            center_d16,
            center_reconstructed_m: values[RAW_DEBUG_CENTER_RECONSTRUCTED_MM_INDEX] as f32 / 1000.0,
            center_confidence: values[RAW_DEBUG_CENTER_CONFIDENCE_MILLI_INDEX] as f32 / 1000.0,
            center_window_median_d16: values[RAW_DEBUG_CENTER_MEDIAN_D16_INDEX],
            center_window_valid_count,
            min_valid_reconstructed_m: min_valid_mm as f32 / 1000.0,
            max_valid_reconstructed_m: values[RAW_DEBUG_MAX_VALID_MM_INDEX] as f32 / 1000.0,
            hash_insert_success_count: values[RAW_DEBUG_HASH_INSERT_SUCCESS_COUNT_INDEX],
            hash_merge_count: values[RAW_DEBUG_HASH_MERGE_COUNT_INDEX],
            hash_stale_replace_count: values[RAW_DEBUG_HASH_STALE_REPLACE_COUNT_INDEX],
            hash_probe_exhausted_count: values[RAW_DEBUG_HASH_PROBE_EXHAUSTED_COUNT_INDEX],
            free_space_retire_attempt_count: values
                [RAW_DEBUG_FREE_SPACE_RETIRE_ATTEMPT_COUNT_INDEX],
            free_space_retire_success_count: values
                [RAW_DEBUG_FREE_SPACE_RETIRE_SUCCESS_COUNT_INDEX],
            hash_occupancy_estimate: values[RAW_DEBUG_HASH_OCCUPANCY_ESTIMATE_INDEX],
            hash_write_conflict_count: values[RAW_DEBUG_HASH_WRITE_CONFLICT_COUNT_INDEX],
            hash_claim_failed_count: values[RAW_DEBUG_HASH_CLAIM_FAILED_COUNT_INDEX],
            free_space_confidence_skipped_count: values
                [RAW_DEBUG_FREE_SPACE_CONFIDENCE_SKIPPED_COUNT_INDEX],
            surface_supported_cells: values[RAW_DEBUG_SURFACE_SUPPORTED_CELLS_INDEX],
            surface_rejected_isolated_cells: values
                [RAW_DEBUG_SURFACE_REJECTED_ISOLATED_CELLS_INDEX],
            surface_candidate_cells: values[RAW_DEBUG_SURFACE_CANDIDATE_CELLS_INDEX],
            surface_confirmed_cells: values[RAW_DEBUG_SURFACE_CONFIRMED_CELLS_INDEX],
            surface_promoted_cells: values[RAW_DEBUG_SURFACE_PROMOTED_CELLS_INDEX],
            surface_candidate_retired_cells: values
                [RAW_DEBUG_SURFACE_CANDIDATE_RETIRED_CELLS_INDEX],
            source_layer_agreement_cells: values[RAW_DEBUG_SOURCE_LAYER_AGREEMENT_CELLS_INDEX],
            single_layer_only_cells: values[RAW_DEBUG_SINGLE_LAYER_ONLY_CELLS_INDEX],
            surface_normal_valid_cells: values[RAW_DEBUG_SURFACE_NORMAL_VALID_CELLS_INDEX],
            surface_normal_invalid_cells: values[RAW_DEBUG_SURFACE_NORMAL_INVALID_CELLS_INDEX],
            surface_normal_rejected_cells: values[RAW_DEBUG_SURFACE_NORMAL_REJECTED_CELLS_INDEX],
            surface_component_largest_cells: values
                [RAW_DEBUG_SURFACE_COMPONENT_LARGEST_CELLS_INDEX],
            surface_component_small_rejected_cells: values
                [RAW_DEBUG_SURFACE_COMPONENT_SMALL_REJECTED_CELLS_INDEX],
            surface_component_candidate_cells: values
                [RAW_DEBUG_SURFACE_COMPONENT_CANDIDATE_CELLS_INDEX],
            surface_component_confirmed_cells: values
                [RAW_DEBUG_SURFACE_COMPONENT_CONFIRMED_CELLS_INDEX],
            raw_sample_count,
            raw_zero_d16_count: values[RAW_DEBUG_RAW_ZERO_D16_COUNT_INDEX],
            raw_max_d16_count: values[RAW_DEBUG_RAW_MAX_D16_COUNT_INDEX],
            raw_middle_d16_count: values[RAW_DEBUG_RAW_MIDDLE_D16_COUNT_INDEX],
            raw_min_d16,
            raw_max_d16: values[RAW_DEBUG_RAW_MAX_D16_INDEX],
            raw_center_d16: values[RAW_DEBUG_RAW_CENTER_D16_INDEX],
        }
    }

    pub(crate) fn range_marker_fields(self) -> String {
        format!(
            "environmentDepthRawStatsStatus={} environmentDepthRawCenterD16={} environmentDepthRawCenterD16Unfiltered={} environmentDepthCenterReconstructedMeters={:.3} environmentDepthRawCenterWindowMedianD16={} environmentDepthRawCenterWindowValidCount={} environmentDepthMinValidReconstructedMeters={:.3} environmentDepthMaxValidReconstructedMeters={:.3} environmentDepthDebugValidSampleCount={} environmentDepthDebugInvalidSampleCount={} environmentDepthDebugConfidenceRejectedCount={} environmentDepthRawSampleCount={} environmentDepthRawZeroD16Count={} environmentDepthRawMaxD16Count={} environmentDepthRawMiddleD16Count={} environmentDepthRawMinD16={} environmentDepthRawMaxD16={}",
            self.status,
            self.center_d16,
            self.raw_center_d16,
            self.center_reconstructed_m,
            self.center_window_median_d16,
            self.center_window_valid_count,
            self.min_valid_reconstructed_m,
            self.max_valid_reconstructed_m,
            self.valid_sample_count,
            self.invalid_sample_count,
            self.confidence_rejected_count,
            self.raw_sample_count,
            self.raw_zero_d16_count,
            self.raw_max_d16_count,
            self.raw_middle_d16_count,
            self.raw_min_d16,
            self.raw_max_d16,
        )
    }
}

impl Default for EnvironmentDepthRawDebugStats {
    fn default() -> Self {
        Self::unavailable()
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct GpuEnvironmentDepthParticleFrameStats {
    pub(crate) ready: bool,
    pub(crate) visible: bool,
    pub(crate) particle_count: u32,
    pub(crate) capacity: u32,
    pub(crate) source_depth_samples: u32,
    pub(crate) source: &'static str,
    pub(crate) mode: &'static str,
    pub(crate) reference_space: &'static str,
    pub(crate) coordinate_space: &'static str,
    pub(crate) gpu_buffers_resident: bool,
    provider_state: &'static str,
    provider_available: bool,
    real_provider_bound: bool,
    supported: bool,
    acquire_status: &'static str,
    image_width: u32,
    image_height: u32,
    image_format: &'static str,
    layer_count: u32,
    source_view_count: u32,
    sampled_layer_mask: &'static str,
    shader_layer_policy: &'static str,
    depth_units_policy: &'static str,
    raw_to_meters_policy: &'static str,
    debug_view: &'static str,
    particle_debug_color_mode: &'static str,
    surface_model: &'static str,
    surface_support_requested: bool,
    surface_support_enforced: bool,
    surface_support_mode: &'static str,
    surface_support_radius_cells: u32,
    surface_support_min_neighbors: u32,
    surface_support_min_observations: u32,
    surface_support_min_source_layers: u32,
    surface_support_component_min_cells: u32,
    surface_support_component_mode: &'static str,
    surface_normal_source: &'static str,
    surface_support_normal_coherence: &'static str,
    surface_support_small_component_policy: &'static str,
    surface_normal_status: &'static str,
    surface_support_free_space_decay: &'static str,
    surface_support_status: &'static str,
    surface_lifecycle_status: &'static str,
    raw_debug_stats: EnvironmentDepthRawDebugStats,
    pose_valid: bool,
    render_view_state_flags: &'static str,
    pub(crate) swapchain_index: Option<u32>,
    capture_time_ns: Option<i64>,
    display_time_ns: Option<i64>,
    capture_to_display_ms: f64,
    acquire_to_render_ms: f64,
    frame_age_ms: f64,
    near_m: f32,
    far_m: f32,
    pub(crate) frame_marker: f32,
    scene_particle_map: bool,
    retention_policy: &'static str,
    map_policy: &'static str,
    map_write_policy: &'static str,
    invalid_sample_policy: &'static str,
    free_space_correction: &'static str,
    head_motion: OpenXrEnvironmentDepthHeadMotionStats,
}

impl GpuEnvironmentDepthParticleFrameStats {
    pub(crate) fn unavailable(settings: NativeEnvironmentDepthSettings) -> Self {
        Self {
            capacity: settings.particle_capacity,
            source: settings.source_marker_value(),
            mode: settings.mode_marker_value(),
            reference_space: settings.reference_space_marker_value(),
            coordinate_space: "openxr-reference-space",
            provider_state: settings.provider_state_marker_value(),
            acquire_status: settings.acquire_status_marker_value(),
            image_format: "none",
            source_view_count: settings.source_view_count(),
            sampled_layer_mask: settings.sampled_layer_mask(),
            shader_layer_policy: settings.layer_policy_marker_value(),
            depth_units_policy: settings.depth_units_policy_marker_value(),
            raw_to_meters_policy: settings.raw_to_meters_policy_marker_value(),
            debug_view: settings.debug_view_marker_value(),
            particle_debug_color_mode: settings.debug_view.particle_debug_color_mode(),
            surface_model: settings.surface_model.marker_value(),
            surface_support_requested: settings.surface_support_requested(),
            surface_support_enforced: false,
            surface_support_mode: settings.surface_model.support_mode_marker_value(),
            surface_support_radius_cells: settings.surface_support_radius_cells,
            surface_support_min_neighbors: settings.surface_support_min_neighbors,
            surface_support_min_observations: settings.surface_support_min_observations,
            surface_support_min_source_layers: settings.surface_support_min_source_layers,
            surface_support_component_min_cells: settings.surface_support_component_min_cells,
            surface_support_component_mode: settings.surface_support_component_mode.marker_value(),
            surface_normal_source: settings.surface_support_normal_source.marker_value(),
            surface_support_normal_coherence: settings
                .surface_support_normal_coherence
                .marker_value(),
            surface_support_small_component_policy: settings
                .surface_support_small_component_policy
                .marker_value(),
            surface_normal_status: settings.surface_normal_status_marker(),
            surface_support_free_space_decay: settings
                .surface_support_free_space_decay
                .marker_value(),
            surface_support_status: settings.surface_support_status_marker(),
            surface_lifecycle_status: environment_depth_surface_lifecycle_status_marker(settings),
            near_m: settings.near_m,
            far_m: settings.far_m,
            scene_particle_map: settings.scene_particle_map_requested(),
            retention_policy: environment_depth_particle_retention_marker(settings),
            map_policy: environment_depth_particle_map_policy_marker(settings),
            map_write_policy: environment_depth_map_write_policy_marker(settings),
            invalid_sample_policy: environment_depth_invalid_sample_policy_marker(settings),
            free_space_correction: environment_depth_free_space_correction_marker(settings),
            ..Self::default()
        }
    }

    pub(crate) fn synthetic(settings: NativeEnvironmentDepthSettings, capacity: u32) -> Self {
        let particle_count = settings.particle_capacity.min(capacity);
        Self {
            ready: particle_count > 0,
            visible: particle_count > 0,
            particle_count,
            capacity,
            source_depth_samples: particle_count,
            source: settings.source_marker_value(),
            mode: settings.mode_marker_value(),
            reference_space: settings.reference_space_marker_value(),
            coordinate_space: "openxr-reference-space",
            gpu_buffers_resident: true,
            provider_state: "synthetic-gpu-proof",
            acquire_status: "not-attempted-synthetic-gpu-proof",
            image_format: "synthetic-depth-view",
            source_view_count: settings.source_view_count(),
            sampled_layer_mask: settings.sampled_layer_mask(),
            shader_layer_policy: settings.layer_policy_marker_value(),
            depth_units_policy: settings.depth_units_policy_marker_value(),
            raw_to_meters_policy: settings.raw_to_meters_policy_marker_value(),
            debug_view: settings.debug_view_marker_value(),
            particle_debug_color_mode: settings.debug_view.particle_debug_color_mode(),
            surface_model: settings.surface_model.marker_value(),
            surface_support_requested: settings.surface_support_requested(),
            surface_support_enforced: false,
            surface_support_mode: settings.surface_model.support_mode_marker_value(),
            surface_support_radius_cells: settings.surface_support_radius_cells,
            surface_support_min_neighbors: settings.surface_support_min_neighbors,
            surface_support_min_observations: settings.surface_support_min_observations,
            surface_support_min_source_layers: settings.surface_support_min_source_layers,
            surface_support_component_min_cells: settings.surface_support_component_min_cells,
            surface_support_component_mode: settings.surface_support_component_mode.marker_value(),
            surface_normal_source: settings.surface_support_normal_source.marker_value(),
            surface_support_normal_coherence: settings
                .surface_support_normal_coherence
                .marker_value(),
            surface_support_small_component_policy: settings
                .surface_support_small_component_policy
                .marker_value(),
            surface_normal_status: environment_depth_surface_normal_status_marker(settings),
            surface_support_free_space_decay: settings
                .surface_support_free_space_decay
                .marker_value(),
            surface_support_status: settings.surface_support_status_marker(),
            surface_lifecycle_status: environment_depth_surface_lifecycle_status_marker(settings),
            pose_valid: true,
            near_m: settings.near_m,
            far_m: settings.far_m,
            scene_particle_map: settings.scene_particle_map_requested(),
            retention_policy: environment_depth_particle_retention_marker(settings),
            map_policy: environment_depth_particle_map_policy_marker(settings),
            map_write_policy: environment_depth_map_write_policy_marker(settings),
            invalid_sample_policy: environment_depth_invalid_sample_policy_marker(settings),
            free_space_correction: environment_depth_free_space_correction_marker(settings),
            ..Self::default()
        }
    }

    pub(crate) fn runtime_depth(
        settings: NativeEnvironmentDepthSettings,
        capacity: u32,
        frame: &OpenXrEnvironmentDepthFrame,
        frame_count: u64,
        raw_debug_stats: EnvironmentDepthRawDebugStats,
    ) -> Self {
        let grid_width = runtime_depth_particle_grid_width(frame.depth_width, settings);
        let grid_height = runtime_depth_particle_grid_height(frame.depth_height, settings);
        let source_depth_samples = grid_width.saturating_mul(grid_height);
        let particle_count = if settings.scene_particle_map_requested() {
            settings.particle_capacity.min(capacity)
        } else {
            settings
                .particle_capacity
                .min(capacity)
                .min(source_depth_samples)
        };
        Self {
            ready: particle_count > 0,
            visible: particle_count > 0,
            particle_count,
            capacity,
            source_depth_samples,
            source: settings.source_marker_value(),
            mode: settings.mode_marker_value(),
            reference_space: settings.reference_space_marker_value(),
            coordinate_space: "openxr-reference-space",
            gpu_buffers_resident: true,
            provider_state: "provider-running",
            provider_available: true,
            real_provider_bound: true,
            supported: true,
            acquire_status: "acquired",
            image_width: frame.depth_width,
            image_height: frame.depth_height,
            image_format: "VK_FORMAT_D16_UNORM",
            layer_count: META_ENVIRONMENT_DEPTH_LAYER_COUNT,
            source_view_count: settings.source_view_count(),
            sampled_layer_mask: settings.sampled_layer_mask(),
            shader_layer_policy: settings.layer_policy_marker_value(),
            depth_units_policy: settings.depth_units_policy_marker_value(),
            raw_to_meters_policy: settings.raw_to_meters_policy_marker_value(),
            debug_view: settings.debug_view_marker_value(),
            particle_debug_color_mode: settings.debug_view.particle_debug_color_mode(),
            surface_model: settings.surface_model.marker_value(),
            surface_support_requested: settings.surface_support_requested(),
            surface_support_enforced: environment_depth_surface_support_runtime_enforced(settings),
            surface_support_mode: settings.surface_model.support_mode_marker_value(),
            surface_support_radius_cells: settings.surface_support_radius_cells,
            surface_support_min_neighbors: settings.surface_support_min_neighbors,
            surface_support_min_observations: settings.surface_support_min_observations,
            surface_support_min_source_layers: settings.surface_support_min_source_layers,
            surface_support_component_min_cells: settings.surface_support_component_min_cells,
            surface_support_component_mode: settings.surface_support_component_mode.marker_value(),
            surface_normal_source: settings.surface_support_normal_source.marker_value(),
            surface_support_normal_coherence: settings
                .surface_support_normal_coherence
                .marker_value(),
            surface_support_small_component_policy: settings
                .surface_support_small_component_policy
                .marker_value(),
            surface_normal_status: environment_depth_surface_normal_status_marker(settings),
            surface_support_free_space_decay: settings
                .surface_support_free_space_decay
                .marker_value(),
            surface_support_status: environment_depth_surface_support_runtime_status_marker(
                settings,
            ),
            surface_lifecycle_status: environment_depth_surface_lifecycle_status_marker(settings),
            raw_debug_stats,
            pose_valid: true,
            render_view_state_flags: frame.render_view_state_flags_marker,
            swapchain_index: Some(frame.swapchain_index),
            capture_time_ns: Some(frame.capture_time_ns),
            display_time_ns: Some(frame.display_time_ns),
            capture_to_display_ms: frame.capture_to_display_ms,
            acquire_to_render_ms: frame.acquire_completed_at.elapsed().as_secs_f64() * 1000.0,
            frame_age_ms: frame.frame_age_ms,
            near_m: frame.near_z,
            far_m: if frame.far_z.is_finite() {
                frame.far_z
            } else {
                settings.far_m
            },
            frame_marker: frame_count as f32,
            scene_particle_map: settings.scene_particle_map_requested(),
            retention_policy: environment_depth_particle_retention_marker(settings),
            map_policy: environment_depth_particle_map_policy_marker(settings),
            map_write_policy: environment_depth_map_write_policy_marker(settings),
            invalid_sample_policy: environment_depth_invalid_sample_policy_marker(settings),
            free_space_correction: environment_depth_free_space_correction_marker(settings),
            head_motion: frame.head_motion,
        }
    }

    pub(crate) fn runtime_depth_not_acquired(
        settings: NativeEnvironmentDepthSettings,
        capacity: u32,
    ) -> Self {
        Self {
            capacity,
            source: settings.source_marker_value(),
            mode: settings.mode_marker_value(),
            reference_space: settings.reference_space_marker_value(),
            coordinate_space: "openxr-reference-space",
            provider_state: "provider-running",
            provider_available: true,
            real_provider_bound: true,
            supported: true,
            acquire_status: "not-available",
            image_format: "VK_FORMAT_D16_UNORM",
            layer_count: META_ENVIRONMENT_DEPTH_LAYER_COUNT,
            source_view_count: settings.source_view_count(),
            sampled_layer_mask: settings.sampled_layer_mask(),
            shader_layer_policy: settings.layer_policy_marker_value(),
            depth_units_policy: settings.depth_units_policy_marker_value(),
            raw_to_meters_policy: settings.raw_to_meters_policy_marker_value(),
            debug_view: settings.debug_view_marker_value(),
            particle_debug_color_mode: settings.debug_view.particle_debug_color_mode(),
            surface_model: settings.surface_model.marker_value(),
            surface_support_requested: settings.surface_support_requested(),
            surface_support_enforced: false,
            surface_support_mode: settings.surface_model.support_mode_marker_value(),
            surface_support_radius_cells: settings.surface_support_radius_cells,
            surface_support_min_neighbors: settings.surface_support_min_neighbors,
            surface_support_min_observations: settings.surface_support_min_observations,
            surface_support_min_source_layers: settings.surface_support_min_source_layers,
            surface_support_component_min_cells: settings.surface_support_component_min_cells,
            surface_support_component_mode: settings.surface_support_component_mode.marker_value(),
            surface_normal_source: settings.surface_support_normal_source.marker_value(),
            surface_support_normal_coherence: settings
                .surface_support_normal_coherence
                .marker_value(),
            surface_support_small_component_policy: settings
                .surface_support_small_component_policy
                .marker_value(),
            surface_normal_status: settings.surface_normal_status_marker(),
            surface_support_free_space_decay: settings
                .surface_support_free_space_decay
                .marker_value(),
            surface_support_status: settings.surface_support_status_marker(),
            surface_lifecycle_status: environment_depth_surface_lifecycle_status_marker(settings),
            near_m: settings.near_m,
            far_m: settings.far_m,
            scene_particle_map: settings.scene_particle_map_requested(),
            retention_policy: environment_depth_particle_retention_marker(settings),
            map_policy: environment_depth_particle_map_policy_marker(settings),
            map_write_policy: environment_depth_map_write_policy_marker(settings),
            invalid_sample_policy: environment_depth_invalid_sample_policy_marker(settings),
            free_space_correction: environment_depth_free_space_correction_marker(settings),
            ..Self::default()
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        let marker_fields = format!(
            "environmentDepthProviderState={} environmentDepthProviderAvailable={} environmentDepthRealProviderBound={} environmentDepthSupported={} environmentDepthAcquireStatus={} environmentDepthImageSize={}x{} environmentDepthFormat={} environmentDepthLayerCount={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthDepthUnitsPolicy={} environmentDepthRawToMetersPolicy={} environmentDepthDebugView={} environmentDepthDepthViewPoseValidMask={} environmentDepthDepthViewFovValidMask={} environmentDepthRenderViewStateFlags={} environmentDepthPoseValid={} environmentDepthSwapchainIndex={} environmentDepthCaptureTimeNs={} environmentDepthDisplayTimeNs={} environmentDepthCaptureToDisplayMs={:.3} environmentDepthAcquireToRenderMs={:.3} environmentDepthFrameAgeMs={:.3} environmentDepthTextureTransformLabel={} environmentDepthRayUvPolicy={} environmentDepthSampleUvPolicy={} environmentDepthNearM={:.3} environmentDepthFarM={:.3} environmentDepthMode={} environmentDepthParticleReady={} environmentDepthParticleVisible={} environmentDepthParticleCount={} environmentDepthParticleCapacity={} environmentDepthParticleSource={} environmentDepthParticleCoordinateSpace={} environmentDepthParticleReferenceSpace={} environmentDepthWorldSpaceReady={} environmentDepthWorldSpaceMotionEvidence={} environmentDepthHeadMotionPoseSource={} environmentDepthHeadMotionSamples={} environmentDepthHeadMotionYawDeltaDeg={:.3} environmentDepthHeadMotionMaxYawDeltaDeg={:.3} environmentDepthHeadMotionTranslationDeltaM={:.4} environmentDepthHeadMotionMaxTranslationDeltaM={:.4} environmentDepthParticleSourceDepthSamples={} environmentDepthParticleCpuUploadBytes=0 environmentDepthGpuBuffersResident={} environmentDepthParticleBufferMemory=device-local environmentDepthGpuReconstructPath={} environmentDepthGpuDrawPath={} environmentDepthParticleRetention={} environmentDepthParticleMapPolicy={} environmentDepthMapWritePolicy={} environmentDepthSceneParticleMap={} environmentDepthSceneCellMeters={:.3} environmentDepthSceneHashProbeCount={} environmentDepthSceneStaleFadeStartFrames={} environmentDepthSceneStaleRetireFrames={} environmentDepthInvalidSamplePolicy={} environmentDepthFreeSpaceCorrection={} environmentDepthSurfaceModel={} environmentDepthSurfaceSupportRequested={} environmentDepthSurfaceSupportEnforced={} environmentDepthSurfaceSupportMode={} environmentDepthSurfaceSupportRadiusCells={} environmentDepthSurfaceMinNeighborCount={} environmentDepthSurfaceMinObservationCount={} environmentDepthSurfaceMinSourceLayerCount={} environmentDepthSourceLayerAgreementRequired={} environmentDepthSourceLayerAgreementCells={} environmentDepthSingleLayerOnlyCells={} environmentDepthSurfaceComponentMinCells={} environmentDepthSurfaceComponentMode={} environmentDepthSurfaceSmallComponentPolicy={} environmentDepthSurfaceSmallComponentRejectedCells={} environmentDepthSurfaceComponentCandidateCells={} environmentDepthSurfaceConfirmedComponentCells={} environmentDepthSurfaceNormalSource={} environmentDepthSurfaceNormalCoherence={} environmentDepthSurfaceNormalValidCells={} environmentDepthSurfaceNormalInvalidCells={} environmentDepthSurfaceNormalRejectedCells={} environmentDepthSurfaceNormalStatus={} environmentDepthSurfaceFreeSpaceDecay={} environmentDepthSurfaceSupportedCells={} environmentDepthSurfaceRejectedIsolatedCells={} environmentDepthSurfaceLargestComponentCells={} environmentDepthSurfaceSupportStatus={} environmentDepthSurfaceLifecycleStatus={} environmentDepthSurfaceCandidateCells={} environmentDepthSurfaceConfirmedCells={} environmentDepthSurfacePromotedCells={} environmentDepthSurfaceCandidateRetiredCells={} environmentDepthRawStatsStatus={} environmentDepthRawCenterD16={} environmentDepthCenterReconstructedMeters={:.3} environmentDepthCenterConfidence={:.3} environmentDepthRawCenterWindowMedianD16={} environmentDepthRawCenterWindowValidCount={} environmentDepthMinValidReconstructedMeters={:.3} environmentDepthMaxValidReconstructedMeters={:.3} environmentDepthDebugValidSampleCount={} environmentDepthDebugInvalidSampleCount={} environmentDepthDebugConfidenceRejectedCount={} environmentDepthHashInsertSuccessCount={} environmentDepthHashMergeCount={} environmentDepthHashStaleReplaceCount={} environmentDepthHashProbeExhaustedCount={} environmentDepthFreeSpaceRetireAttemptCount={} environmentDepthFreeSpaceRetireSuccessCount={} environmentDepthHashOccupancyEstimate={} environmentDepthHashWriteConflictCount={} environmentDepthHashClaimFailedCount={} environmentDepthReadbackCadenceFrames=0 environmentDepthRawReadbackCadenceFrames=120",
            self.provider_state,
            self.provider_available,
            self.real_provider_bound,
            self.supported,
            self.acquire_status,
            self.image_width,
            self.image_height,
            self.image_format,
            self.layer_count,
            self.source_view_count,
            self.sampled_layer_mask,
            self.shader_layer_policy,
            self.depth_units_policy,
            self.raw_to_meters_policy,
            self.debug_view,
            if self.pose_valid {
                META_ENVIRONMENT_DEPTH_DEPTH_VIEW_VALID_MASK
            } else {
                "0x0"
            },
            if self.pose_valid {
                META_ENVIRONMENT_DEPTH_DEPTH_VIEW_VALID_MASK
            } else {
                "0x0"
            },
            self.render_view_state_flags,
            self.pose_valid,
            self.swapchain_index
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".to_string()),
            self.capture_time_ns
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".to_string()),
            self.display_time_ns
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".to_string()),
            self.capture_to_display_ms,
            self.acquire_to_render_ms,
            self.frame_age_ms,
            META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_LABEL,
            META_ENVIRONMENT_DEPTH_RAY_UV_POLICY_LABEL,
            META_ENVIRONMENT_DEPTH_SAMPLE_UV_POLICY_LABEL,
            self.near_m,
            self.far_m,
            self.mode,
            self.ready,
            self.visible,
            self.particle_count,
            self.capacity,
            self.source,
            self.coordinate_space,
            self.reference_space,
            self.ready && self.coordinate_space == "openxr-reference-space",
            self.head_motion.evidence,
            self.head_motion.pose_source,
            self.head_motion.sample_count,
            self.head_motion.yaw_delta_deg,
            self.head_motion.max_yaw_delta_deg,
            self.head_motion.translation_delta_m,
            self.head_motion.max_translation_delta_m,
            self.source_depth_samples,
            self.gpu_buffers_resident,
            if self.ready {
                "native-vulkan-compute-depth-view-to-reference-space"
            } else {
                "unavailable"
            },
            if self.visible {
                "native-vulkan-reference-space-billboard-overlay"
            } else {
                "unavailable"
            },
            self.retention_policy,
            self.map_policy,
            self.map_write_policy,
            self.scene_particle_map,
            if self.scene_particle_map {
                SCENE_PARTICLE_CELL_METERS
            } else {
                0.0
            },
            if self.scene_particle_map {
                SCENE_PARTICLE_HASH_PROBE_COUNT
            } else {
                0
            },
            if self.scene_particle_map {
                SCENE_PARTICLE_STALE_FADE_START_FRAMES
            } else {
                0
            },
            if self.scene_particle_map {
                SCENE_PARTICLE_STALE_RETIRE_FRAMES
            } else {
                0
            },
            self.invalid_sample_policy,
            self.free_space_correction,
            self.surface_model,
            self.surface_support_requested,
            self.surface_support_enforced,
            self.surface_support_mode,
            self.surface_support_radius_cells,
            self.surface_support_min_neighbors,
            self.surface_support_min_observations,
            self.surface_support_min_source_layers,
            self.surface_support_min_source_layers > 1,
            self.raw_debug_stats.source_layer_agreement_cells,
            self.raw_debug_stats.single_layer_only_cells,
            self.surface_support_component_min_cells,
            self.surface_support_component_mode,
            self.surface_support_small_component_policy,
            self.raw_debug_stats.surface_component_small_rejected_cells,
            self.raw_debug_stats.surface_component_candidate_cells,
            self.raw_debug_stats.surface_component_confirmed_cells,
            self.surface_normal_source,
            self.surface_support_normal_coherence,
            self.raw_debug_stats.surface_normal_valid_cells,
            self.raw_debug_stats.surface_normal_invalid_cells,
            self.raw_debug_stats.surface_normal_rejected_cells,
            self.surface_normal_status,
            self.surface_support_free_space_decay,
            self.raw_debug_stats.surface_supported_cells,
            self.raw_debug_stats.surface_rejected_isolated_cells,
            self.raw_debug_stats.surface_component_largest_cells,
            self.surface_support_status,
            self.surface_lifecycle_status,
            self.raw_debug_stats.surface_candidate_cells,
            self.raw_debug_stats.surface_confirmed_cells,
            self.raw_debug_stats.surface_promoted_cells,
            self.raw_debug_stats.surface_candidate_retired_cells,
            self.raw_debug_stats.status,
            self.raw_debug_stats.center_d16,
            self.raw_debug_stats.center_reconstructed_m,
            self.raw_debug_stats.center_confidence,
            self.raw_debug_stats.center_window_median_d16,
            self.raw_debug_stats.center_window_valid_count,
            self.raw_debug_stats.min_valid_reconstructed_m,
            self.raw_debug_stats.max_valid_reconstructed_m,
            self.raw_debug_stats.valid_sample_count,
            self.raw_debug_stats.invalid_sample_count,
            self.raw_debug_stats.confidence_rejected_count,
            self.raw_debug_stats.hash_insert_success_count,
            self.raw_debug_stats.hash_merge_count,
            self.raw_debug_stats.hash_stale_replace_count,
            self.raw_debug_stats.hash_probe_exhausted_count,
            self.raw_debug_stats.free_space_retire_attempt_count,
            self.raw_debug_stats.free_space_retire_success_count,
            self.raw_debug_stats.hash_occupancy_estimate,
            self.raw_debug_stats.hash_write_conflict_count,
            self.raw_debug_stats.hash_claim_failed_count,
        );
        format!(
            "{} environmentDepthParticleDebugColorMode={} environmentDepthConfidenceFilter={} environmentDepthSceneConfidenceThreshold={:.3} environmentDepthFreeSpaceConfidenceThreshold={:.3} environmentDepthFreeSpaceRangePolicy={} environmentDepthFreeSpaceConfidenceSkippedCount={}",
            marker_fields,
            self.particle_debug_color_mode,
            META_ENVIRONMENT_DEPTH_CONFIDENCE_FILTER_LABEL,
            META_ENVIRONMENT_DEPTH_SCENE_CONFIDENCE_THRESHOLD,
            META_ENVIRONMENT_DEPTH_FREE_SPACE_CONFIDENCE_THRESHOLD,
            META_ENVIRONMENT_DEPTH_FREE_SPACE_RANGE_POLICY_LABEL,
            self.raw_debug_stats.free_space_confidence_skipped_count,
        )
    }
}

pub(crate) fn environment_depth_particle_retention_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "scene-owned-spatial-particle-map"
    } else {
        "per-frame-depth-view-to-reference-space"
    }
}

pub(crate) fn environment_depth_particle_map_policy_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "spatial-hash-reference-space-cells"
    } else {
        "depth-raster-sample-slots"
    }
}

pub(crate) fn environment_depth_map_write_policy_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "atomic-slot-claim"
    } else {
        "per-sample-overwrite"
    }
}

pub(crate) fn environment_depth_invalid_sample_policy_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "preserve-existing-cells"
    } else {
        "clear-current-sample-slot"
    }
}

pub(crate) fn environment_depth_free_space_correction_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "confidence-gated-visible-free-space-ray-clear"
    } else {
        "disabled-retained-particle-slots"
    }
}

fn environment_depth_surface_support_runtime_enforced(
    settings: NativeEnvironmentDepthSettings,
) -> bool {
    settings.scene_particle_map_requested()
        && settings.runtime_provider_requested()
        && settings.surface_support_requested()
        && settings.surface_support_min_neighbors > 0
}

fn environment_depth_surface_support_runtime_status_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if environment_depth_surface_support_runtime_enforced(settings) {
        if settings.surface_support_component_mode.marker_value() == "connected-labels" {
            "enforced-local-depth-neighborhood-connected-labels-pending"
        } else {
            SURFACE_SUPPORT_RUNTIME_STATUS
        }
    } else {
        settings.surface_support_status_marker()
    }
}

fn environment_depth_surface_lifecycle_status_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if environment_depth_surface_support_runtime_enforced(settings) {
        SURFACE_LIFECYCLE_RUNTIME_STATUS
    } else if settings.surface_support_requested() {
        "pending-runtime-support"
    } else {
        "disabled"
    }
}

fn environment_depth_surface_normal_status_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if environment_depth_surface_support_runtime_enforced(settings)
        && settings.surface_depth_neighborhood_normals_requested()
    {
        "depth-neighborhood-gpu-readback"
    } else {
        settings.surface_normal_status_marker()
    }
}

fn environment_depth_surface_support_mode_flag(settings: NativeEnvironmentDepthSettings) -> u32 {
    match settings.surface_model.support_mode_marker_value() {
        "local-surfels" => DEPTH_FLAG_SURFACE_SUPPORT_LOCAL,
        "global-surfaces" => DEPTH_FLAG_SURFACE_SUPPORT_GLOBAL,
        "hybrid" => DEPTH_FLAG_SURFACE_SUPPORT_HYBRID,
        _ => 0,
    }
}

pub(crate) fn environment_depth_surface_support_depth_flags(
    settings: NativeEnvironmentDepthSettings,
) -> u32 {
    if !environment_depth_surface_support_runtime_enforced(settings) {
        return 0;
    }
    let min_neighbors = settings.surface_support_min_neighbors.min(26);
    let radius_cells = settings.surface_support_radius_cells.clamp(1, 8);
    let min_observations = settings.surface_support_min_observations.clamp(1, 15);
    let min_source_layers = if settings.surface_support_min_source_layers >= 2 {
        DEPTH_FLAG_SURFACE_SUPPORT_MIN_SOURCE_LAYERS_TWO
    } else {
        0
    };
    DEPTH_FLAG_SURFACE_SUPPORT_ENFORCED
        | environment_depth_surface_support_mode_flag(settings)
        | min_source_layers
        | (min_neighbors << DEPTH_FLAG_SURFACE_SUPPORT_MIN_NEIGHBOR_SHIFT)
        | (radius_cells << DEPTH_FLAG_SURFACE_SUPPORT_RADIUS_SHIFT)
        | (min_observations << DEPTH_FLAG_SURFACE_SUPPORT_MIN_OBSERVATION_SHIFT)
}

pub(crate) fn runtime_depth_particle_grid_width(
    depth_width: u32,
    settings: NativeEnvironmentDepthSettings,
) -> u32 {
    (depth_width / settings.sample_stride_pixels.max(1)).max(1)
}

pub(crate) fn runtime_depth_particle_grid_height(
    depth_height: u32,
    settings: NativeEnvironmentDepthSettings,
) -> u32 {
    (depth_height / settings.sample_stride_pixels.max(1)).max(1)
}
