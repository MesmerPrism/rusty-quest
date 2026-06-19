//! Native Vulkan proof path for environment-depth-style reference-space particles.

use std::{
    ffi::CString,
    mem,
    sync::atomic::{AtomicBool, Ordering},
};

use ash::vk::{self, Handle};

use crate::{
    gpu_hand_mesh_visual::HandMeshVisualEyeProjection,
    native_renderer_options::NativeEnvironmentDepthSettings,
    openxr_environment_depth::OpenXrEnvironmentDepthFrame,
};

const PARTICLE_VERTICES_PER_INSTANCE: u32 = 6;
const PARTICLE_COMPUTE_LOCAL_SIZE: u32 = 64;
const RUNTIME_DEPTH_COMPUTE_LOCAL_SIZE_X: u32 = 8;
const RUNTIME_DEPTH_COMPUTE_LOCAL_SIZE_Y: u32 = 8;
const PARTICLE_ROW_VEC4S: vk::DeviceSize = 4;
const PARTICLE_ROW_BYTES: vk::DeviceSize =
    PARTICLE_ROW_VEC4S * mem::size_of::<[f32; 4]>() as vk::DeviceSize;
const SCENE_PARTICLE_METADATA_WORDS_PER_SLOT: vk::DeviceSize = 4;
const SCENE_PARTICLE_METADATA_BYTES_PER_SLOT: vk::DeviceSize =
    SCENE_PARTICLE_METADATA_WORDS_PER_SLOT * mem::size_of::<u32>() as vk::DeviceSize;
const META_ENVIRONMENT_DEPTH_FORMAT: vk::Format = vk::Format::D16_UNORM;
const META_ENVIRONMENT_DEPTH_LAYER_COUNT: u32 = 2;
const META_ENVIRONMENT_DEPTH_DEPTH_VIEW_VALID_MASK: &str = "0x1";
const META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_LABEL: &str = "rotate0+flipY";
const META_ENVIRONMENT_DEPTH_RAY_UV_POLICY_LABEL: &str = "canonical-untransformed";
const META_ENVIRONMENT_DEPTH_SAMPLE_UV_POLICY_LABEL: &str = "texture-transformed";
const META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_FLAGS: f32 = 8.0;
const DEPTH_FLAG_INFINITE_FAR: u32 = 1;
const DEPTH_FLAG_SCENE_PARTICLE_MAP: u32 = 2;
const DEPTH_FLAG_SOURCE_LAYER1: u32 = 4;
const ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_U32_COUNT: usize = 19;
const ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_BYTES: vk::DeviceSize =
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
const SCENE_PARTICLE_CELL_METERS: f32 = 0.06;
const SCENE_PARTICLE_HASH_PROBE_COUNT: u32 = 8;
const SCENE_PARTICLE_STALE_FADE_START_FRAMES: u32 = 720;
const SCENE_PARTICLE_STALE_RETIRE_FRAMES: u32 = 1440;

#[derive(Clone, Copy, Debug)]
struct EnvironmentDepthRawDebugStats {
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
}

impl EnvironmentDepthRawDebugStats {
    fn unavailable() -> Self {
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
        }
    }

    fn pending() -> Self {
        Self {
            status: "pending-gpu-readback",
            ..Self::unavailable()
        }
    }

    fn from_raw(values: &[u32]) -> Self {
        let valid_sample_count = values[RAW_DEBUG_VALID_COUNT_INDEX];
        if valid_sample_count == 0 {
            return Self::pending();
        }
        let min_valid_inverse_mm = values[RAW_DEBUG_MIN_VALID_INVERSE_MM_INDEX];
        let min_valid_mm = if min_valid_inverse_mm == 0 {
            0
        } else {
            u32::MAX.saturating_sub(min_valid_inverse_mm)
        };
        Self {
            status: "readback",
            valid_sample_count,
            invalid_sample_count: values[RAW_DEBUG_INVALID_COUNT_INDEX],
            confidence_rejected_count: values[RAW_DEBUG_CONFIDENCE_REJECTED_COUNT_INDEX],
            center_d16: values[RAW_DEBUG_CENTER_D16_INDEX],
            center_reconstructed_m: values[RAW_DEBUG_CENTER_RECONSTRUCTED_MM_INDEX] as f32 / 1000.0,
            center_confidence: values[RAW_DEBUG_CENTER_CONFIDENCE_MILLI_INDEX] as f32 / 1000.0,
            center_window_median_d16: values[RAW_DEBUG_CENTER_MEDIAN_D16_INDEX],
            center_window_valid_count: values[RAW_DEBUG_CENTER_WINDOW_VALID_COUNT_INDEX],
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
        }
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
    raw_debug_stats: EnvironmentDepthRawDebugStats,
    pose_valid: bool,
    render_view_state_flags: &'static str,
    swapchain_index: Option<u32>,
    capture_time_ns: Option<i64>,
    display_time_ns: Option<i64>,
    capture_to_display_ms: f64,
    acquire_to_render_ms: f64,
    frame_age_ms: f64,
    near_m: f32,
    far_m: f32,
    frame_marker: f32,
    scene_particle_map: bool,
    retention_policy: &'static str,
    map_policy: &'static str,
    map_write_policy: &'static str,
    invalid_sample_policy: &'static str,
    free_space_correction: &'static str,
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

    fn synthetic(settings: NativeEnvironmentDepthSettings, capacity: u32) -> Self {
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

    fn runtime_depth(
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
        format!(
            "environmentDepthProviderState={} environmentDepthProviderAvailable={} environmentDepthRealProviderBound={} environmentDepthSupported={} environmentDepthAcquireStatus={} environmentDepthImageSize={}x{} environmentDepthFormat={} environmentDepthLayerCount={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthDepthUnitsPolicy={} environmentDepthRawToMetersPolicy={} environmentDepthDebugView={} environmentDepthDepthViewPoseValidMask={} environmentDepthDepthViewFovValidMask={} environmentDepthRenderViewStateFlags={} environmentDepthPoseValid={} environmentDepthSwapchainIndex={} environmentDepthCaptureTimeNs={} environmentDepthDisplayTimeNs={} environmentDepthCaptureToDisplayMs={:.3} environmentDepthAcquireToRenderMs={:.3} environmentDepthFrameAgeMs={:.3} environmentDepthTextureTransformLabel={} environmentDepthRayUvPolicy={} environmentDepthSampleUvPolicy={} environmentDepthNearM={:.3} environmentDepthFarM={:.3} environmentDepthMode={} environmentDepthParticleReady={} environmentDepthParticleVisible={} environmentDepthParticleCount={} environmentDepthParticleCapacity={} environmentDepthParticleSource={} environmentDepthParticleCoordinateSpace={} environmentDepthParticleReferenceSpace={} environmentDepthWorldSpaceReady={} environmentDepthParticleSourceDepthSamples={} environmentDepthParticleCpuUploadBytes=0 environmentDepthGpuBuffersResident={} environmentDepthParticleBufferMemory=device-local environmentDepthGpuReconstructPath={} environmentDepthGpuDrawPath={} environmentDepthParticleRetention={} environmentDepthParticleMapPolicy={} environmentDepthMapWritePolicy={} environmentDepthSceneParticleMap={} environmentDepthSceneCellMeters={:.3} environmentDepthSceneHashProbeCount={} environmentDepthSceneStaleFadeStartFrames={} environmentDepthSceneStaleRetireFrames={} environmentDepthInvalidSamplePolicy={} environmentDepthFreeSpaceCorrection={} environmentDepthRawStatsStatus={} environmentDepthRawCenterD16={} environmentDepthCenterReconstructedMeters={:.3} environmentDepthCenterConfidence={:.3} environmentDepthRawCenterWindowMedianD16={} environmentDepthRawCenterWindowValidCount={} environmentDepthMinValidReconstructedMeters={:.3} environmentDepthMaxValidReconstructedMeters={:.3} environmentDepthDebugValidSampleCount={} environmentDepthDebugInvalidSampleCount={} environmentDepthDebugConfidenceRejectedCount={} environmentDepthHashInsertSuccessCount={} environmentDepthHashMergeCount={} environmentDepthHashStaleReplaceCount={} environmentDepthHashProbeExhaustedCount={} environmentDepthFreeSpaceRetireAttemptCount={} environmentDepthFreeSpaceRetireSuccessCount={} environmentDepthHashOccupancyEstimate={} environmentDepthHashWriteConflictCount={} environmentDepthHashClaimFailedCount={} environmentDepthReadbackCadenceFrames=0 environmentDepthRawReadbackCadenceFrames=120",
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
        )
    }
}

fn environment_depth_particle_retention_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "scene-owned-spatial-particle-map"
    } else {
        "per-frame-depth-view-to-reference-space"
    }
}

fn environment_depth_particle_map_policy_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "spatial-hash-reference-space-cells"
    } else {
        "depth-raster-sample-slots"
    }
}

fn environment_depth_map_write_policy_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "atomic-slot-claim"
    } else {
        "per-sample-overwrite"
    }
}

fn environment_depth_invalid_sample_policy_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "preserve-existing-cells"
    } else {
        "clear-current-sample-slot"
    }
}

fn environment_depth_free_space_correction_marker(
    settings: NativeEnvironmentDepthSettings,
) -> &'static str {
    if settings.scene_particle_map_requested() {
        "visible-free-space-ray-clear"
    } else {
        "disabled-retained-particle-slots"
    }
}

pub(crate) struct GpuEnvironmentDepthParticleRenderer {
    descriptor_pool: vk::DescriptorPool,
    descriptor_set_layout: vk::DescriptorSetLayout,
    descriptor_sets: Vec<vk::DescriptorSet>,
    image_views: Vec<vk::ImageView>,
    sampler: vk::Sampler,
    pipeline_layout: vk::PipelineLayout,
    compute_pipeline: vk::Pipeline,
    graphics_pipeline: vk::Pipeline,
    particle_buffer: OwnedBuffer,
    raw_debug_buffer: Option<EnvironmentDepthRawDebugBuffer>,
    scene_metadata_buffer: Option<OwnedBuffer>,
    capacity: u32,
    source_kind: EnvironmentDepthParticleRendererSource,
    scene_map_initialized: AtomicBool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum EnvironmentDepthParticleRendererSource {
    SyntheticGpuProof,
    MetaEnvironmentDepth,
}

impl GpuEnvironmentDepthParticleRenderer {
    pub(crate) unsafe fn new_synthetic(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        settings: NativeEnvironmentDepthSettings,
    ) -> Result<Self, String> {
        if !settings.synthetic_gpu_proof_requested() {
            return Err(format!(
                "environment depth particle renderer requires source=synthetic-gpu-proof and particle mode, got source={} mode={}",
                settings.source_marker_value(),
                settings.mode_marker_value()
            ));
        }
        let capacity = settings.particle_capacity.max(64);
        let particle_buffer = OwnedBuffer::new(
            device,
            memory_properties,
            capacity as vk::DeviceSize * PARTICLE_ROW_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER,
            "environment depth particle output",
        )?;
        let bindings = [storage_binding(
            1,
            vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX,
        )];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                particle_buffer.destroy(device);
                return Err(format!(
                    "create environment depth particle descriptor layout: {error}"
                ));
            }
        };
        let pool_sizes = [vk::DescriptorPoolSize::default()
            .ty(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(1)];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(1),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                particle_buffer.destroy(device);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "create environment depth particle descriptor pool: {error}"
                ));
            }
        };
        let set_layouts = [descriptor_set_layout];
        let descriptor_sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&set_layouts),
        ) {
            Ok(sets) => sets,
            Err(error) => {
                particle_buffer.destroy(device);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "allocate environment depth particle descriptor set: {error}"
                ));
            }
        };
        update_storage_descriptors(device, descriptor_sets[0], 1, particle_buffer.descriptor());

        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX)
            .offset(0)
            .size(mem::size_of::<EnvironmentDepthParticlePush>() as u32)];
        let pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                particle_buffer.destroy(device);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(format!(
                    "create environment depth particle pipeline layout: {error}"
                ));
            }
        };
        let compute_pipeline = match create_compute_pipeline(
            device,
            pipeline_layout,
            include_bytes!(concat!(
                env!("OUT_DIR"),
                "/environment_depth_particles_synthetic.comp.spv"
            )),
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                particle_buffer.destroy(device);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };
        let graphics_pipeline = match create_graphics_pipeline(device, render_pass, pipeline_layout)
        {
            Ok(pipeline) => pipeline,
            Err(error) => {
                particle_buffer.destroy(device);
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                return Err(error);
            }
        };

        crate::marker(
            "environment-depth-particles",
            format!(
                "status=created environmentDepthParticlePath=synthetic-depth-view-gpu-reference-space-billboards environmentDepthParticleSource={} environmentDepthParticleCoordinateSpace=openxr-reference-space environmentDepthParticleReferenceSpace={} environmentDepthParticleCapacity={} environmentDepthParticleBufferBytes={} environmentDepthParticleCpuUploadBytes=0 environmentDepthGpuBuffersResident=true environmentDepthParticleBufferMemory={} environmentDepthProviderState=synthetic-gpu-proof environmentDepthRealProviderBound=false environmentDepthHighRateJsonPayload={}",
                settings.source_marker_value(),
                settings.reference_space_marker_value(),
                capacity,
                particle_buffer.bytes,
                particle_buffer.memory_marker(),
                settings.high_rate_json_payload
            ),
        );

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_sets,
            image_views: Vec::new(),
            sampler: vk::Sampler::null(),
            pipeline_layout,
            compute_pipeline,
            graphics_pipeline,
            particle_buffer,
            raw_debug_buffer: None,
            scene_metadata_buffer: None,
            capacity,
            source_kind: EnvironmentDepthParticleRendererSource::SyntheticGpuProof,
            scene_map_initialized: AtomicBool::new(false),
        })
    }

    pub(crate) unsafe fn new_runtime_depth(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        settings: NativeEnvironmentDepthSettings,
        depth_image_handles: &[u64],
        depth_width: u32,
        depth_height: u32,
    ) -> Result<Self, String> {
        if !settings.runtime_provider_requested() || !settings.mode_draws_particles() {
            return Err(format!(
                "environment depth particle renderer requires runtime provider particle mode, got source={} mode={}",
                settings.source_marker_value(),
                settings.mode_marker_value()
            ));
        }
        if depth_image_handles.is_empty() || depth_width == 0 || depth_height == 0 {
            return Err(
                "runtime environment depth renderer requires non-empty depth swapchain images"
                    .to_string(),
            );
        }

        let capacity = settings.particle_capacity.max(64);
        let particle_buffer = OwnedBuffer::new(
            device,
            memory_properties,
            capacity as vk::DeviceSize * PARTICLE_ROW_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            "runtime environment depth particle output",
        )?;
        let raw_debug_buffer = match EnvironmentDepthRawDebugBuffer::new(device, memory_properties)
        {
            Ok(buffer) => buffer,
            Err(error) => {
                particle_buffer.destroy(device);
                return Err(error);
            }
        };
        let scene_metadata_buffer = match OwnedBuffer::new(
            device,
            memory_properties,
            capacity as vk::DeviceSize * SCENE_PARTICLE_METADATA_BYTES_PER_SLOT,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            "runtime environment depth scene metadata",
        ) {
            Ok(buffer) => buffer,
            Err(error) => {
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                return Err(error);
            }
        };
        let sampler = match device.create_sampler(
            &vk::SamplerCreateInfo::default()
                .mag_filter(vk::Filter::NEAREST)
                .min_filter(vk::Filter::NEAREST)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .border_color(vk::BorderColor::FLOAT_OPAQUE_BLACK),
            None,
        ) {
            Ok(sampler) => sampler,
            Err(error) => {
                scene_metadata_buffer.destroy(device);
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                return Err(format!("create runtime environment depth sampler: {error}"));
            }
        };
        let bindings = [
            image_sampler_binding(0, vk::ShaderStageFlags::COMPUTE),
            storage_binding(
                1,
                vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX,
            ),
            storage_binding(2, vk::ShaderStageFlags::COMPUTE),
            storage_binding(3, vk::ShaderStageFlags::COMPUTE),
        ];
        let descriptor_set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                scene_metadata_buffer.destroy(device);
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                device.destroy_sampler(sampler, None);
                return Err(format!(
                    "create runtime environment depth descriptor layout: {error}"
                ));
            }
        };
        let descriptor_count = depth_image_handles.len() as u32;
        let pool_sizes = [
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(descriptor_count),
            vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(descriptor_count.saturating_mul(3)),
        ];
        let descriptor_pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .pool_sizes(&pool_sizes)
                .max_sets(descriptor_count),
            None,
        ) {
            Ok(pool) => pool,
            Err(error) => {
                scene_metadata_buffer.destroy(device);
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(format!(
                    "create runtime environment depth descriptor pool: {error}"
                ));
            }
        };

        let mut image_views = Vec::with_capacity(depth_image_handles.len());
        for (index, image_handle) in depth_image_handles.iter().copied().enumerate() {
            let image = vk::Image::from_raw(image_handle);
            match device.create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(image)
                    .view_type(vk::ImageViewType::TYPE_2D_ARRAY)
                    .format(META_ENVIRONMENT_DEPTH_FORMAT)
                    .subresource_range(vk::ImageSubresourceRange {
                        aspect_mask: vk::ImageAspectFlags::DEPTH,
                        base_mip_level: 0,
                        level_count: 1,
                        base_array_layer: 0,
                        layer_count: META_ENVIRONMENT_DEPTH_LAYER_COUNT,
                    }),
                None,
            ) {
                Ok(view) => image_views.push(view),
                Err(error) => {
                    for view in image_views {
                        device.destroy_image_view(view, None);
                    }
                    scene_metadata_buffer.destroy(device);
                    raw_debug_buffer.destroy(device);
                    particle_buffer.destroy(device);
                    device.destroy_descriptor_pool(descriptor_pool, None);
                    device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                    device.destroy_sampler(sampler, None);
                    return Err(format!(
                        "create runtime environment depth image view index={index}: {error}"
                    ));
                }
            }
        }

        let descriptor_set_layouts = vec![descriptor_set_layout; depth_image_handles.len()];
        let descriptor_sets = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(descriptor_pool)
                .set_layouts(&descriptor_set_layouts),
        ) {
            Ok(sets) => sets,
            Err(error) => {
                for view in image_views {
                    device.destroy_image_view(view, None);
                }
                scene_metadata_buffer.destroy(device);
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(format!(
                    "allocate runtime environment depth descriptor sets: {error}"
                ));
            }
        };
        for (descriptor_set, image_view) in descriptor_sets.iter().copied().zip(image_views.iter())
        {
            update_runtime_depth_descriptors(
                device,
                descriptor_set,
                sampler,
                *image_view,
                particle_buffer.descriptor(),
                raw_debug_buffer.descriptor(),
                scene_metadata_buffer.descriptor(),
            );
        }

        let set_layouts = [descriptor_set_layout];
        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::COMPUTE | vk::ShaderStageFlags::VERTEX)
            .offset(0)
            .size(mem::size_of::<EnvironmentDepthParticlePush>() as u32)];
        let pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&set_layouts)
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(layout) => layout,
            Err(error) => {
                for view in image_views {
                    device.destroy_image_view(view, None);
                }
                scene_metadata_buffer.destroy(device);
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(format!(
                    "create runtime environment depth pipeline layout: {error}"
                ));
            }
        };
        let compute_pipeline = match create_compute_pipeline(
            device,
            pipeline_layout,
            include_bytes!(concat!(
                env!("OUT_DIR"),
                "/environment_depth_particles_meta.comp.spv"
            )),
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                for view in image_views {
                    device.destroy_image_view(view, None);
                }
                scene_metadata_buffer.destroy(device);
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(error);
            }
        };
        let graphics_pipeline = match create_graphics_pipeline(device, render_pass, pipeline_layout)
        {
            Ok(pipeline) => pipeline,
            Err(error) => {
                for view in image_views {
                    device.destroy_image_view(view, None);
                }
                scene_metadata_buffer.destroy(device);
                raw_debug_buffer.destroy(device);
                particle_buffer.destroy(device);
                device.destroy_pipeline(compute_pipeline, None);
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_descriptor_pool(descriptor_pool, None);
                device.destroy_descriptor_set_layout(descriptor_set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(error);
            }
        };

        crate::marker(
            "environment-depth-particles",
            format!(
                "status=created environmentDepthParticlePath=meta-environment-depth-gpu-reference-space-billboards environmentDepthParticleSource={} environmentDepthParticleCoordinateSpace=openxr-reference-space environmentDepthParticleReferenceSpace={} environmentDepthParticleCapacity={} environmentDepthParticleBufferBytes={} environmentDepthParticleCpuUploadBytes=0 environmentDepthGpuBuffersResident=true environmentDepthParticleBufferMemory={} environmentDepthRawDebugBufferBytes={} environmentDepthRawDebugBufferMemory={} environmentDepthSceneMetadataBufferBytes={} environmentDepthSceneMetadataBufferMemory={} environmentDepthProviderState=provider-running environmentDepthRealProviderBound=true environmentDepthImageSize={}x{} environmentDepthFormat=VK_FORMAT_D16_UNORM environmentDepthLayerCount={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthDepthUnitsPolicy={} environmentDepthRawToMetersPolicy={} environmentDepthDebugView={} environmentDepthDepthViewPoseValidMask={} environmentDepthDepthViewFovValidMask={} environmentDepthTextureTransform=rotate0+flipY environmentDepthTextureTransformLabel={} environmentDepthRayUvPolicy={} environmentDepthSampleUvPolicy={} environmentDepthParticleRetention={} environmentDepthParticleMapPolicy={} environmentDepthMapWritePolicy={} environmentDepthSceneParticleMap={} environmentDepthSceneCellMeters={:.3} environmentDepthSceneHashProbeCount={} environmentDepthInvalidSamplePolicy={} environmentDepthFreeSpaceCorrection={} environmentDepthHighRateJsonPayload={}",
                settings.source_marker_value(),
                settings.reference_space_marker_value(),
                capacity,
                particle_buffer.bytes,
                particle_buffer.memory_marker(),
                raw_debug_buffer.bytes(),
                raw_debug_buffer.memory_marker(),
                scene_metadata_buffer.bytes,
                scene_metadata_buffer.memory_marker(),
                depth_width,
                depth_height,
                META_ENVIRONMENT_DEPTH_LAYER_COUNT,
                settings.source_view_count(),
                settings.sampled_layer_mask(),
                settings.layer_policy_marker_value(),
                settings.depth_units_policy_marker_value(),
                settings.raw_to_meters_policy_marker_value(),
                settings.debug_view_marker_value(),
                META_ENVIRONMENT_DEPTH_DEPTH_VIEW_VALID_MASK,
                META_ENVIRONMENT_DEPTH_DEPTH_VIEW_VALID_MASK,
                META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_LABEL,
                META_ENVIRONMENT_DEPTH_RAY_UV_POLICY_LABEL,
                META_ENVIRONMENT_DEPTH_SAMPLE_UV_POLICY_LABEL,
                environment_depth_particle_retention_marker(settings),
                environment_depth_particle_map_policy_marker(settings),
                environment_depth_map_write_policy_marker(settings),
                settings.scene_particle_map_requested(),
                if settings.scene_particle_map_requested() {
                    SCENE_PARTICLE_CELL_METERS
                } else {
                    0.0
                },
                if settings.scene_particle_map_requested() {
                    SCENE_PARTICLE_HASH_PROBE_COUNT
                } else {
                    0
                },
                environment_depth_invalid_sample_policy_marker(settings),
                environment_depth_free_space_correction_marker(settings),
                settings.high_rate_json_payload
            ),
        );

        Ok(Self {
            descriptor_pool,
            descriptor_set_layout,
            descriptor_sets,
            image_views,
            sampler,
            pipeline_layout,
            compute_pipeline,
            graphics_pipeline,
            particle_buffer,
            raw_debug_buffer: Some(raw_debug_buffer),
            scene_metadata_buffer: Some(scene_metadata_buffer),
            capacity,
            source_kind: EnvironmentDepthParticleRendererSource::MetaEnvironmentDepth,
            scene_map_initialized: AtomicBool::new(false),
        })
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if let Some(raw_debug_buffer) = self.raw_debug_buffer.take() {
            raw_debug_buffer.destroy(device);
        }
        if let Some(scene_metadata_buffer) = self.scene_metadata_buffer.take() {
            scene_metadata_buffer.destroy(device);
        }
        self.particle_buffer.destroy(device);
        device.destroy_pipeline(self.graphics_pipeline, None);
        device.destroy_pipeline(self.compute_pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
        for image_view in self.image_views.drain(..) {
            device.destroy_image_view(image_view, None);
        }
        if self.sampler != vk::Sampler::null() {
            device.destroy_sampler(self.sampler, None);
            self.sampler = vk::Sampler::null();
        }
    }

    pub(crate) unsafe fn record_compute_frame(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        settings: NativeEnvironmentDepthSettings,
        eye_projection: HandMeshVisualEyeProjection,
        frame_count: u64,
    ) -> GpuEnvironmentDepthParticleFrameStats {
        if self.source_kind != EnvironmentDepthParticleRendererSource::SyntheticGpuProof
            || !settings.synthetic_gpu_proof_requested()
        {
            return GpuEnvironmentDepthParticleFrameStats::unavailable(settings);
        }
        self.scene_map_initialized.store(false, Ordering::Release);
        let stats = GpuEnvironmentDepthParticleFrameStats::synthetic(settings, self.capacity);
        if !stats.ready {
            return stats;
        }
        let radius_m = ((settings.near_m + settings.far_m) * 0.0025).clamp(0.006, 0.018);
        let push = EnvironmentDepthParticlePush {
            params0: [
                stats.particle_count as f32,
                radius_m,
                frame_count as f32 / 72.0,
                0.88,
            ],
            params1: [
                settings.near_m,
                settings.far_m,
                1.0,
                settings.sample_stride_pixels as f32,
            ],
            eye_position: eye_projection.position,
            eye_orientation_xyzw: eye_projection.orientation_xyzw,
            fov_tangents: eye_projection.fov_tangents,
            depth_eye_position: eye_projection.position,
            depth_eye_orientation_xyzw: eye_projection.orientation_xyzw,
            depth_fov_tangents: eye_projection.fov_tangents,
        };
        let compute_write_barrier = [shader_to_compute_write_barrier(&self.particle_buffer)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::VERTEX_SHADER | vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &compute_write_barrier,
            &[],
        );
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, self.compute_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[self.descriptor_sets[0]],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.pipeline_layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            as_bytes(&push),
        );
        device.cmd_dispatch(
            cmd,
            stats.particle_count.div_ceil(PARTICLE_COMPUTE_LOCAL_SIZE),
            1,
            1,
        );
        let compute_to_vertex = [compute_write_to_shader_read_barrier(&self.particle_buffer)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::VERTEX_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &compute_to_vertex,
            &[],
        );

        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "environment-depth-particles",
                format!(
                    "status=compute frame={} {} environmentDepthGpuReconstructMs=pending-gpu-timestamp environmentDepthGpuMapUpdateMs=pending-gpu-timestamp environmentDepthGpuDrawMs=pending-gpu-timestamp",
                    frame_count,
                    stats.marker_fields(),
                ),
            );
        }
        stats
    }

    pub(crate) unsafe fn record_runtime_depth_frame(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        settings: NativeEnvironmentDepthSettings,
        frame: &OpenXrEnvironmentDepthFrame,
        frame_count: u64,
    ) -> GpuEnvironmentDepthParticleFrameStats {
        if self.source_kind != EnvironmentDepthParticleRendererSource::MetaEnvironmentDepth
            || !settings.runtime_provider_requested()
            || !settings.mode_draws_particles()
        {
            return GpuEnvironmentDepthParticleFrameStats::unavailable(settings);
        }
        let raw_debug_stats = self
            .raw_debug_buffer
            .as_ref()
            .map(|buffer| buffer.read_stats())
            .unwrap_or_else(EnvironmentDepthRawDebugStats::unavailable);
        let stats = GpuEnvironmentDepthParticleFrameStats::runtime_depth(
            settings,
            self.capacity,
            frame,
            frame_count,
            raw_debug_stats,
        );
        if !stats.ready {
            return stats;
        }
        let Some(descriptor_set) = self
            .descriptor_sets
            .get(frame.swapchain_index as usize)
            .copied()
        else {
            return GpuEnvironmentDepthParticleFrameStats::runtime_depth_not_acquired(
                settings,
                self.capacity,
            );
        };
        let far_m = if frame.far_z.is_finite() && frame.far_z > frame.near_z {
            frame.far_z
        } else {
            settings.far_m
        };
        let scene_particle_map = settings.scene_particle_map_requested();
        if scene_particle_map {
            if !self.scene_map_initialized.swap(true, Ordering::AcqRel) {
                device.cmd_fill_buffer(
                    cmd,
                    self.particle_buffer.buffer,
                    0,
                    self.particle_buffer.bytes,
                    0,
                );
                let mut clear_to_compute = vec![transfer_write_to_shader_write_barrier(
                    &self.particle_buffer,
                )];
                if let Some(scene_metadata_buffer) = self.scene_metadata_buffer.as_ref() {
                    device.cmd_fill_buffer(
                        cmd,
                        scene_metadata_buffer.buffer,
                        0,
                        scene_metadata_buffer.bytes,
                        0,
                    );
                    clear_to_compute.push(transfer_write_to_shader_write_barrier(
                        scene_metadata_buffer,
                    ));
                }
                device.cmd_pipeline_barrier(
                    cmd,
                    vk::PipelineStageFlags::TRANSFER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),
                    &[],
                    &clear_to_compute,
                    &[],
                );
            }
        } else {
            self.scene_map_initialized.store(false, Ordering::Release);
        }
        if let Some(raw_debug_buffer) = self.raw_debug_buffer.as_ref() {
            device.cmd_fill_buffer(
                cmd,
                raw_debug_buffer.buffer.buffer,
                0,
                raw_debug_buffer.buffer.bytes,
                0,
            );
            let debug_reset_to_compute = [transfer_write_to_shader_write_barrier(
                &raw_debug_buffer.buffer,
            )];
            device.cmd_pipeline_barrier(
                cmd,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[],
                &debug_reset_to_compute,
                &[],
            );
        }
        let depth_flags = (if frame.far_z.is_finite() {
            0
        } else {
            DEPTH_FLAG_INFINITE_FAR
        }) | (if scene_particle_map {
            DEPTH_FLAG_SCENE_PARTICLE_MAP
        } else {
            0
        }) | (if settings.source_view_index() == 1 {
            DEPTH_FLAG_SOURCE_LAYER1
        } else {
            0
        });
        let mut depth_eye_position = frame.depth_eye_position;
        depth_eye_position[3] = frame_count as f32;
        let push = EnvironmentDepthParticlePush {
            params0: [
                stats.particle_count as f32,
                ((frame.near_z + far_m) * 0.0018).clamp(0.002, 0.010),
                META_ENVIRONMENT_DEPTH_TEXTURE_TRANSFORM_FLAGS,
                0.94,
            ],
            params1: [
                frame.near_z.max(0.001),
                far_m.max(frame.near_z + 0.001),
                depth_flags as f32,
                settings.sample_stride_pixels as f32,
            ],
            eye_position: depth_eye_position,
            eye_orientation_xyzw: frame.depth_eye_orientation_xyzw,
            fov_tangents: frame.depth_fov_tangents,
            depth_eye_position,
            depth_eye_orientation_xyzw: frame.depth_eye_orientation_xyzw,
            depth_fov_tangents: frame.depth_fov_tangents,
        };
        let mut compute_write_barrier =
            vec![shader_to_compute_write_barrier(&self.particle_buffer)];
        if let Some(scene_metadata_buffer) = self.scene_metadata_buffer.as_ref() {
            compute_write_barrier.push(shader_to_compute_write_barrier(scene_metadata_buffer));
        }
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::VERTEX_SHADER | vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &compute_write_barrier,
            &[],
        );
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, self.compute_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::COMPUTE,
            self.pipeline_layout,
            0,
            &[descriptor_set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.pipeline_layout,
            vk::ShaderStageFlags::COMPUTE,
            0,
            as_bytes(&push),
        );
        let grid_width = runtime_depth_particle_grid_width(frame.depth_width, settings);
        let grid_height = runtime_depth_particle_grid_height(frame.depth_height, settings);
        device.cmd_dispatch(
            cmd,
            grid_width
                .div_ceil(RUNTIME_DEPTH_COMPUTE_LOCAL_SIZE_X)
                .max(1),
            grid_height
                .div_ceil(RUNTIME_DEPTH_COMPUTE_LOCAL_SIZE_Y)
                .max(1),
            1,
        );
        let compute_to_vertex = [compute_write_to_shader_read_barrier(&self.particle_buffer)];
        device.cmd_pipeline_barrier(
            cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::VERTEX_SHADER,
            vk::DependencyFlags::empty(),
            &[],
            &compute_to_vertex,
            &[],
        );
        if let Some(raw_debug_buffer) = self.raw_debug_buffer.as_ref() {
            let compute_to_host = [compute_write_to_host_read_barrier(&raw_debug_buffer.buffer)];
            device.cmd_pipeline_barrier(
                cmd,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[],
                &compute_to_host,
                &[],
            );
        }

        if frame_count == 0 || frame_count % 120 == 0 {
            crate::marker(
                "environment-depth-particles",
                format!(
                    "status=compute frame={} {} environmentDepthGpuReconstructMs=pending-gpu-timestamp environmentDepthGpuMapUpdateMs=pending-gpu-timestamp environmentDepthGpuDrawMs=pending-gpu-timestamp",
                    frame_count,
                    stats.marker_fields(),
                ),
            );
        }
        stats
    }

    pub(crate) unsafe fn record_overlay_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        extent: vk::Extent2D,
        eye_projection: HandMeshVisualEyeProjection,
        stats: &GpuEnvironmentDepthParticleFrameStats,
        settings: NativeEnvironmentDepthSettings,
    ) {
        if !stats.visible || stats.particle_count == 0 {
            return;
        }
        let mut eye_position = eye_projection.position;
        eye_position[3] = if settings.scene_particle_map_requested() {
            stats.frame_marker
        } else {
            0.0
        };
        let push = EnvironmentDepthParticlePush {
            params0: [
                stats.particle_count as f32,
                ((settings.near_m + settings.far_m) * 0.0025).clamp(0.006, 0.018),
                0.0,
                0.88,
            ],
            params1: [
                settings.near_m,
                settings.far_m,
                if settings.scene_particle_map_requested() {
                    DEPTH_FLAG_SCENE_PARTICLE_MAP as f32
                } else {
                    0.0
                },
                settings.sample_stride_pixels as f32,
            ],
            eye_position,
            eye_orientation_xyzw: eye_projection.orientation_xyzw,
            fov_tangents: eye_projection.fov_tangents,
            depth_eye_position: eye_projection.position,
            depth_eye_orientation_xyzw: eye_projection.orientation_xyzw,
            depth_fov_tangents: eye_projection.fov_tangents,
        };
        let descriptor_set = match self.source_kind {
            EnvironmentDepthParticleRendererSource::SyntheticGpuProof => self.descriptor_sets[0],
            EnvironmentDepthParticleRendererSource::MetaEnvironmentDepth => stats
                .swapchain_index
                .and_then(|index| self.descriptor_sets.get(index as usize).copied())
                .unwrap_or(self.descriptor_sets[0]),
        };
        let viewport = [vk::Viewport {
            x: 0.0,
            y: 0.0,
            width: extent.width as f32,
            height: extent.height as f32,
            min_depth: 0.0,
            max_depth: 1.0,
        }];
        let scissor = [vk::Rect2D {
            offset: vk::Offset2D::default(),
            extent,
        }];
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.graphics_pipeline);
        device.cmd_bind_descriptor_sets(
            cmd,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline_layout,
            0,
            &[descriptor_set],
            &[],
        );
        device.cmd_push_constants(
            cmd,
            self.pipeline_layout,
            vk::ShaderStageFlags::VERTEX,
            0,
            as_bytes(&push),
        );
        device.cmd_set_viewport(cmd, 0, &viewport);
        device.cmd_set_scissor(cmd, 0, &scissor);
        device.cmd_draw(
            cmd,
            PARTICLE_VERTICES_PER_INSTANCE,
            stats.particle_count,
            0,
            0,
        );
    }
}

unsafe fn create_compute_pipeline(
    device: &ash::Device,
    pipeline_layout: vk::PipelineLayout,
    spirv: &[u8],
) -> Result<vk::Pipeline, String> {
    let compute_words = spirv_words(spirv)?;
    let compute_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&compute_words),
            None,
        )
        .map_err(|error| format!("create environment depth particle compute shader: {error}"))?;
    let entry = CString::new("main").expect("static shader entry point is valid");
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE)
        .module(compute_module)
        .name(&entry);
    let create_info = [vk::ComputePipelineCreateInfo::default()
        .stage(stage)
        .layout(pipeline_layout)];
    let result = device.create_compute_pipelines(vk::PipelineCache::null(), &create_info, None);
    device.destroy_shader_module(compute_module, None);
    result
        .map(|mut pipelines| pipelines.remove(0))
        .map_err(|(_, error)| {
            format!("create environment depth particle compute pipeline: {error}")
        })
}

unsafe fn create_graphics_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vertex_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/environment_depth_particles.vert.spv"
    )))?;
    let fragment_words = spirv_words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/environment_depth_particles.frag.spv"
    )))?;
    let vertex_module = device
        .create_shader_module(
            &vk::ShaderModuleCreateInfo::default().code(&vertex_words),
            None,
        )
        .map_err(|error| format!("create environment depth particle vertex shader: {error}"))?;
    let fragment_module = match device.create_shader_module(
        &vk::ShaderModuleCreateInfo::default().code(&fragment_words),
        None,
    ) {
        Ok(module) => module,
        Err(error) => {
            device.destroy_shader_module(vertex_module, None);
            return Err(format!(
                "create environment depth particle fragment shader: {error}"
            ));
        }
    };

    let entry = CString::new("main").expect("static shader entry point is valid");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vertex_module)
            .name(&entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(fragment_module)
            .name(&entry),
    ];
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let color_blend_attachment = [particle_color_blend_attachment()];
    let color_blend =
        vk::PipelineColorBlendStateCreateInfo::default().attachments(&color_blend_attachment);
    let depth_stencil = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(false)
        .depth_write_enable(false)
        .depth_compare_op(vk::CompareOp::ALWAYS)
        .stencil_test_enable(false);
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let create_info = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .depth_stencil_state(&depth_stencil)
        .dynamic_state(&dynamic)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0)];
    let result = device.create_graphics_pipelines(vk::PipelineCache::null(), &create_info, None);
    device.destroy_shader_module(fragment_module, None);
    device.destroy_shader_module(vertex_module, None);
    result
        .map(|mut pipelines| pipelines.remove(0))
        .map_err(|(_, error)| {
            format!("create environment depth particle graphics pipeline: {error}")
        })
}

fn particle_color_blend_attachment() -> vk::PipelineColorBlendAttachmentState {
    vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::ONE)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA)
}

fn storage_binding(
    binding: u32,
    stage_flags: vk::ShaderStageFlags,
) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .descriptor_count(1)
        .stage_flags(stage_flags)
}

fn image_sampler_binding(
    binding: u32,
    stage_flags: vk::ShaderStageFlags,
) -> vk::DescriptorSetLayoutBinding<'static> {
    vk::DescriptorSetLayoutBinding::default()
        .binding(binding)
        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
        .descriptor_count(1)
        .stage_flags(stage_flags)
}

unsafe fn update_storage_descriptors(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    binding: u32,
    particle_buffer: vk::DescriptorBufferInfo,
) {
    let particle_info = [particle_buffer];
    let writes = [write_storage_descriptor(
        descriptor_set,
        binding,
        &particle_info,
    )];
    device.update_descriptor_sets(&writes, &[]);
}

unsafe fn update_runtime_depth_descriptors(
    device: &ash::Device,
    descriptor_set: vk::DescriptorSet,
    sampler: vk::Sampler,
    image_view: vk::ImageView,
    particle_buffer: vk::DescriptorBufferInfo,
    raw_debug_buffer: vk::DescriptorBufferInfo,
    scene_metadata_buffer: vk::DescriptorBufferInfo,
) {
    let image_info = [vk::DescriptorImageInfo::default()
        .sampler(sampler)
        .image_view(image_view)
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
    let particle_info = [particle_buffer];
    let raw_debug_info = [raw_debug_buffer];
    let scene_metadata_info = [scene_metadata_buffer];
    let writes = [
        vk::WriteDescriptorSet::default()
            .dst_set(descriptor_set)
            .dst_binding(0)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .image_info(&image_info),
        write_storage_descriptor(descriptor_set, 1, &particle_info),
        write_storage_descriptor(descriptor_set, 2, &raw_debug_info),
        write_storage_descriptor(descriptor_set, 3, &scene_metadata_info),
    ];
    device.update_descriptor_sets(&writes, &[]);
}

fn write_storage_descriptor<'a>(
    descriptor_set: vk::DescriptorSet,
    binding: u32,
    buffer_info: &'a [vk::DescriptorBufferInfo],
) -> vk::WriteDescriptorSet<'a> {
    vk::WriteDescriptorSet::default()
        .dst_set(descriptor_set)
        .dst_binding(binding)
        .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
        .buffer_info(buffer_info)
}

fn descriptor_info(buffer: vk::Buffer, bytes: vk::DeviceSize) -> vk::DescriptorBufferInfo {
    vk::DescriptorBufferInfo::default()
        .buffer(buffer)
        .offset(0)
        .range(bytes)
}

fn shader_to_compute_write_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_WRITE)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn compute_write_to_shader_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn compute_write_to_host_read_barrier(buffer: &OwnedBuffer) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
        .dst_access_mask(vk::AccessFlags::HOST_READ)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn transfer_write_to_shader_write_barrier(
    buffer: &OwnedBuffer,
) -> vk::BufferMemoryBarrier<'static> {
    vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .dst_access_mask(vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE)
        .buffer(buffer.buffer)
        .offset(0)
        .size(buffer.bytes)
}

fn runtime_depth_particle_grid_width(
    depth_width: u32,
    settings: NativeEnvironmentDepthSettings,
) -> u32 {
    (depth_width / settings.sample_stride_pixels.max(1)).max(1)
}

fn runtime_depth_particle_grid_height(
    depth_height: u32,
    settings: NativeEnvironmentDepthSettings,
) -> u32 {
    (depth_height / settings.sample_stride_pixels.max(1)).max(1)
}

struct EnvironmentDepthRawDebugBuffer {
    buffer: OwnedBuffer,
    mapped: *mut u32,
}

impl EnvironmentDepthRawDebugBuffer {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
    ) -> Result<Self, String> {
        let buffer = OwnedBuffer::new_with_memory_flags(
            device,
            memory_properties,
            ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_BYTES,
            vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            "runtime environment depth raw debug stats",
        )?;
        let mapped = match device.map_memory(
            buffer.memory,
            0,
            ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_BYTES,
            vk::MemoryMapFlags::empty(),
        ) {
            Ok(ptr) => ptr.cast::<u32>(),
            Err(error) => {
                buffer.destroy(device);
                return Err(format!(
                    "map runtime environment depth raw debug stats: {error}"
                ));
            }
        };
        std::ptr::write_bytes(mapped, 0, ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_U32_COUNT);
        Ok(Self { buffer, mapped })
    }

    fn descriptor(&self) -> vk::DescriptorBufferInfo {
        self.buffer.descriptor()
    }

    fn bytes(&self) -> vk::DeviceSize {
        self.buffer.bytes
    }

    fn memory_marker(&self) -> &'static str {
        self.buffer.memory_marker()
    }

    unsafe fn read_stats(&self) -> EnvironmentDepthRawDebugStats {
        let values =
            std::slice::from_raw_parts(self.mapped, ENVIRONMENT_DEPTH_RAW_DEBUG_STATS_U32_COUNT);
        EnvironmentDepthRawDebugStats::from_raw(values)
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        if !self.mapped.is_null() {
            device.unmap_memory(self.buffer.memory);
        }
        self.buffer.destroy(device);
    }
}

struct OwnedBuffer {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    bytes: vk::DeviceSize,
    memory_flags: vk::MemoryPropertyFlags,
}

impl OwnedBuffer {
    unsafe fn new(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        bytes: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
        label: &str,
    ) -> Result<Self, String> {
        Self::new_with_memory_flags(
            device,
            memory_properties,
            bytes,
            usage,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
            label,
        )
    }

    unsafe fn new_with_memory_flags(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        bytes: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
        required_memory_flags: vk::MemoryPropertyFlags,
        label: &str,
    ) -> Result<Self, String> {
        if bytes == 0 {
            return Err(format!("{label} buffer requires nonzero size"));
        }
        let buffer = device
            .create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(bytes)
                    .usage(usage)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|error| format!("create {label} buffer: {error}"))?;
        let requirements = device.get_buffer_memory_requirements(buffer);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            required_memory_flags,
        ) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                return Err(error);
            }
        };
        let memory = match device.allocate_memory(
            &vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_type_index),
            None,
        ) {
            Ok(memory) => memory,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                return Err(format!("allocate {label} memory: {error}"));
            }
        };
        if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return Err(format!("bind {label} memory: {error}"));
        }
        Ok(Self {
            buffer,
            memory,
            bytes,
            memory_flags: memory_properties.memory_types[memory_type_index as usize].property_flags,
        })
    }

    fn descriptor(&self) -> vk::DescriptorBufferInfo {
        descriptor_info(self.buffer, self.bytes)
    }

    fn memory_marker(&self) -> &'static str {
        if self
            .memory_flags
            .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
        {
            "device-local"
        } else if self
            .memory_flags
            .contains(vk::MemoryPropertyFlags::HOST_VISIBLE)
        {
            "host-visible"
        } else {
            "not-device-local"
        }
    }

    unsafe fn destroy(&self, device: &ash::Device) {
        if self.buffer != vk::Buffer::null() {
            device.destroy_buffer(self.buffer, None);
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
        }
    }
}

fn find_memory_type(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
    required: vk::MemoryPropertyFlags,
) -> Result<u32, String> {
    for index in 0..memory_properties.memory_type_count {
        let supported = (memory_type_bits & (1 << index)) != 0;
        let flags = memory_properties.memory_types[index as usize].property_flags;
        if supported && flags.contains(required) {
            return Ok(index);
        }
    }
    Err(format!(
        "no Vulkan memory type supports {required:?} for environment depth particle buffers"
    ))
}

fn spirv_words(bytes: &[u8]) -> Result<Vec<u32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("SPIR-V bytecode length is not word-aligned".to_string());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|chunk| u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
        .collect())
}

fn as_bytes<T>(value: &T) -> &[u8] {
    unsafe { std::slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) }
}

#[repr(C)]
struct EnvironmentDepthParticlePush {
    params0: [f32; 4],
    params1: [f32; 4],
    eye_position: [f32; 4],
    eye_orientation_xyzw: [f32; 4],
    fov_tangents: [f32; 4],
    depth_eye_position: [f32; 4],
    depth_eye_orientation_xyzw: [f32; 4],
    depth_fov_tangents: [f32; 4],
}
