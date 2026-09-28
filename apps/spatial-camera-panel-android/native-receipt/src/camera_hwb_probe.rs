use std::ffi::c_void;
#[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
use std::ffi::CString;
use std::os::raw::{c_float, c_int};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use ash::vk;
use ash::vk::Handle;

use crate::acamera_sys::{ANativeWindow, ANativeWindow_release as ACameraNativeWindow_release};
use crate::ahardware_buffer_vulkan::{
    import_ahb_sampled_image, query_ahb_vulkan_import_properties, AhbVulkanDevice,
    AhbVulkanFormatKey, AhbVulkanImportProperties, AhbVulkanSampledImage,
    AhbVulkanSampledImageCreateInfo,
};
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
#[cfg(rq_environment_depth_spatial_sdk_api_layer)]
use crate::camera_hwb_projection_freshness_runtime::record_vulkan_wsi_present_returned;
use crate::camera_hwb_projection_target::{
    camera_hwb_projection_marker_fields, current_projection_zone_compositor_settings,
    readback::ProjectionReadback, update_camera_hwb_projection_stereo_horizontal_offset_uv,
    update_camera_hwb_projection_target_live_scale,
    update_projection_zone_channel_dynamics_settings, update_projection_zone_compositor_settings,
    update_projection_zone_region_layout_settings,
};
use crate::camera_hwb_stream::{
    CameraProbeFrame, CameraProbeFrameSet, CameraProbeRuntime, CameraProbeStreamMode,
};
use crate::camera_hwb_timing::CameraHwbGpuTimestampTracker;
use crate::camera_hwb_wsi::{
    allocate_camera_hwb_probe_descriptor_set, choose_composite_alpha, choose_extent,
    choose_surface_format, create_camera_hwb_probe_resources, create_framebuffers,
    create_image_views, create_render_pass, record_camera_hwb_probe_command_buffer,
    select_camera_surface_device, update_camera_hwb_probe_descriptor_set, CameraHwbImportCache,
    CameraHwbImportPerformanceStats, CameraHwbProbeResources, CameraVulkanExtensionStatus,
};
use crate::camera_latency_diagnostics::{
    boottime_now_ns, camera_latency_strict_pair_decision, current_camera_latency_settings,
    current_camera_latency_stereo_reprojection, CameraLatencyCameraSyncMode,
    CameraLatencyFrameTiming, CameraLatencySettings, CameraLatencyStereoPolicy,
    CameraLatencyStrictPairDecision, CameraLatencyWindow, CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS,
};
use crate::camera_replay_capture::{
    configured_camera_replay_capture, CameraReplayCaptureRecorder, CameraReplayFrameMetadata,
};
use crate::camera_reprojection_guard_band::CameraReprojectionGuardBandController;
use crate::packed_sbs_normalizer::{PackedSbsNormalizer, NORMALIZED_EYE_FORMAT};
use crate::peer_projection_ingress::{
    packed_sbs_normalization_plan, PeerFrameFreshness, PeerFrameFreshnessDecision,
    PeerSessionClaimState,
};
use crate::peer_projection_runtime::PeerFrameWitness;
use crate::projection_surface_displacement::{
    current_projection_surface_displacement_settings,
    update_projection_surface_displacement_settings,
};
use crate::projection_surface_features::update_projection_surface_feature_settings;
use crate::rgb_channel_transform::update_rgb_channel_transform_settings;
use crate::spatial_public_multistack::{
    public_multistack_inactive_marker_fields, public_multistack_marker_fields,
};
use crate::spatial_public_multistack_runtime::{
    allocate_spatial_public_guide_targets, public_guide_targets_pending_marker_fields,
    update_spatial_public_depth_alignment, update_spatial_public_depth_layer_policy,
    update_spatial_public_guide_processing_policy,
    update_spatial_public_opaque_projection_layer_override,
    update_spatial_public_strength_cycle_speed_hz, SpatialPublicGuideTargets,
};
use crate::spatial_video_projection::{
    SpatialVideoProjectionFrameStats, SpatialVideoProjectionRenderer,
};
use crate::spatial_video_projection_native_stream::{
    latest_projection_peer_frame, latest_spatial_video_projection_frame,
    projection_peer_binding_matches, SpatialVideoProjectionFrame,
};
use crate::spatial_video_projection_qualification::record_presented_frame;
#[cfg(rq_environment_depth_spatial_sdk_api_layer)]
use crate::spatial_video_projection_settings::spatial_video_media_source_generation;
use crate::spatial_video_projection_settings::spatial_video_projection_settings;
use crate::{bool_token, marker_token};

const CAMERA_HWB_PROBE_WAIT_FRAME_MS: u64 = 5000;
const CAMERA_HWB_PROBE_MAX_FRAMES: u32 = 1800;

static STOP_CAMERA_HWB_PROBE: AtomicBool = AtomicBool::new(false);
static ACTIVE_LOCAL_CAMERA_WORKERS: AtomicUsize = AtomicUsize::new(0);
static ACTIVE_PEER_COMMON_GRAPH_WORKERS: AtomicUsize = AtomicUsize::new(0);
static LOCAL_CAMERA_START_OWNER: Mutex<
    Option<crate::peer_projection_runtime::LocalCameraStartPermit>,
> = Mutex::new(None);
static PEER_COMMON_GRAPH_SESSION: LazyLock<Mutex<PeerCommonGraphSessionOwner>> =
    LazyLock::new(|| Mutex::new(PeerCommonGraphSessionOwner::default()));
static NEXT_CAMERA_IMPORT_STREAM_GENERATION: AtomicU64 = AtomicU64::new(1);
static NEXT_SDK_SURFACE_GENERATION: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(1);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct LocalCameraOwnershipSnapshot {
    pub(crate) active_workers: usize,
    pub(crate) claimed_route_generation: Option<i64>,
    pub(crate) stop_requested: bool,
}

pub(crate) fn local_camera_ownership_snapshot() -> Option<LocalCameraOwnershipSnapshot> {
    let owner = LOCAL_CAMERA_START_OWNER.lock().ok()?;
    Some(LocalCameraOwnershipSnapshot {
        active_workers: ACTIVE_LOCAL_CAMERA_WORKERS.load(Ordering::Acquire),
        claimed_route_generation: (*owner).map(|permit| permit.route_generation),
        stop_requested: STOP_CAMERA_HWB_PROBE.load(Ordering::Acquire),
    })
}

pub(crate) fn local_camera_acquisition_quiescent() -> bool {
    local_camera_ownership_snapshot().is_some_and(|snapshot| {
        snapshot.active_workers == 0 && snapshot.claimed_route_generation.is_none()
    })
}

pub(crate) fn request_camera_hwb_probe_stop() {
    STOP_CAMERA_HWB_PROBE.store(true, Ordering::Release);
    stop_peer_common_graph_session();
    publish_acquisition_stopped_if_quiescent();
}

fn publish_acquisition_stopped_if_quiescent() {
    if ACTIVE_LOCAL_CAMERA_WORKERS.load(Ordering::Acquire) == 0
        && ACTIVE_PEER_COMMON_GRAPH_WORKERS.load(Ordering::Acquire) == 0
    {
        crate::peer_projection_runtime::record_current_acquisition_stopped_if_pending();
    }
}

fn claim_local_camera_start(
    permit: crate::peer_projection_runtime::LocalCameraStartPermit,
) -> bool {
    if !crate::peer_projection_runtime::local_camera_start_permit_is_current(permit) {
        return false;
    }
    let Ok(mut owner) = LOCAL_CAMERA_START_OWNER.lock() else {
        return false;
    };
    if owner.is_some() {
        return false;
    }
    *owner = Some(permit);
    true
}

fn release_local_camera_start(
    permit: Option<crate::peer_projection_runtime::LocalCameraStartPermit>,
) {
    let Some(permit) = permit else { return };
    if let Ok(mut owner) = LOCAL_CAMERA_START_OWNER.lock() {
        if *owner == Some(permit) {
            *owner = None;
        }
    }
}

#[derive(Default)]
struct PeerCommonGraphSessionOwner {
    claim: PeerSessionClaimState,
    cancellation: Option<Arc<AtomicBool>>,
    worker: Option<thread::JoinHandle<()>>,
    window_address: usize,
}

fn stop_peer_common_graph_session() {
    let owned = {
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let generation = owner.claim.generation();
        if let Some(cancel) = owner.cancellation.as_ref() {
            cancel.store(true, Ordering::Release);
        }
        generation.map(|generation| (generation, owner.worker.take()))
    };
    if let Some((generation, Some(worker))) = owned {
        let _ = worker.join();
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if owner.claim.generation() == Some(generation) {
            owner.cancellation = None;
            let released = owner.claim.release(generation);
            debug_assert!(released);
        }
        drop(owner);
        publish_acquisition_stopped_if_quiescent();
    }
}

#[derive(Clone, Copy)]
pub(crate) enum CameraHwbProbeMode {
    LumaChecker,
    RawColorProjection,
}

impl CameraHwbProbeMode {
    pub(crate) fn output_mode(self) -> &'static str {
        match self {
            Self::LumaChecker => "luma-checker",
            Self::RawColorProjection => "raw-color-target-rect",
        }
    }

    pub(crate) fn raw_projection_token(self) -> &'static str {
        match self {
            Self::LumaChecker => "false",
            Self::RawColorProjection => "true",
        }
    }

    fn requested_frames_marker(self, max_frames: u32) -> String {
        if matches!(self, Self::RawColorProjection) && max_frames == 0 {
            "unbounded".to_string()
        } else {
            max_frames.to_string()
        }
    }

    fn should_stream_latest_frame(self) -> bool {
        matches!(self, Self::RawColorProjection)
    }

    pub(crate) fn descriptor_binding_count(self) -> u32 {
        if matches!(self, Self::RawColorProjection) {
            2
        } else {
            1
        }
    }

    pub(crate) fn stereo_source(self) -> &'static str {
        match self {
            Self::LumaChecker => "mono-selected-camera",
            Self::RawColorProjection => "camera50-51",
        }
    }

    fn stream_mode(self) -> CameraProbeStreamMode {
        match self {
            Self::LumaChecker => CameraProbeStreamMode::MonoSelectedCamera,
            Self::RawColorProjection => CameraProbeStreamMode::StereoCamera50_51,
        }
    }

    pub(crate) fn public_multistack_marker_fields(self) -> String {
        match self {
            Self::LumaChecker => public_multistack_inactive_marker_fields().to_string(),
            Self::RawColorProjection => public_multistack_marker_fields(),
        }
    }

    pub(crate) fn projection_contract_marker_fields(self) -> String {
        match self {
            Self::LumaChecker => "monoDuplicated=false publicMultiStackActive=false".to_string(),
            Self::RawColorProjection => format!(
                "{} {}",
                camera_hwb_projection_marker_fields(),
                public_multistack_marker_fields()
            ),
        }
    }
}

fn camera_probe_frame_order_timestamp(frame: &CameraProbeFrame) -> i64 {
    if frame.timestamp_ns > 0 {
        frame.timestamp_ns
    } else {
        frame.callback_boottime_ns
    }
}

fn camera_probe_pair_delta_ns(left: &CameraProbeFrame, right: &CameraProbeFrame) -> u64 {
    camera_probe_frame_order_timestamp(left).abs_diff(camera_probe_frame_order_timestamp(right))
}

fn log_fence_held_frame_retirement(frame: &CameraProbeFrame, side: &str) {
    if frame.has_fence_held_image()
        && (frame.frame_index <= 4
            || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled())
    {
        log_marker(format!(
            "status=fence-held-frame-retired-after-gpu-fence side={} cameraId={} frameIndex={} hardwareBufferId={} cameraSyncActive=hold-image-until-gpu-fence frameFenceWaitComplete=true imageReleaseDeferredUntilFinalFrameReferenceDrop=true",
            side,
            marker_token(&frame.camera_id),
            frame.frame_index,
            frame.descriptor.hardware_buffer_id,
        ));
    }
}

fn log_camera_frame_import_skipped(
    frame: &CameraProbeFrame,
    side: &str,
    mode: CameraHwbProbeMode,
    error: &str,
) {
    log_marker(format!(
        "status=stream-frame-import-skipped side={} cameraId={} frameIndex={} hwbImportSequence={} error={} sampledCameraTexture=true outputMode={} rawCameraProjectionProbe=true runtimeCrash=false",
        side,
        marker_token(&frame.camera_id),
        frame.frame_index,
        frame.hwb_import_sequence,
        marker_token(error),
        mode.output_mode(),
    ));
}

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut vk::ANativeWindow;
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartCameraHwbProbe(
    env: *mut c_void,
    _thiz: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
    reader_max_images: c_int,
) -> i64 {
    start_camera_hwb_probe(
        env,
        surface,
        width,
        height,
        frame_count,
        reader_max_images,
        CameraHwbProbeMode::LumaChecker,
        None,
    )
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartCameraHwbProjectionProbe(
    env: *mut c_void,
    _thiz: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
    reader_max_images: c_int,
) -> i64 {
    start_camera_hwb_probe(
        env,
        surface,
        width,
        height,
        frame_count,
        reader_max_images,
        CameraHwbProbeMode::RawColorProjection,
        None,
    )
}

pub(crate) fn start_camera_hwb_projection_probe_for_route(
    env: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
    reader_max_images: c_int,
    permit: crate::peer_projection_runtime::LocalCameraStartPermit,
) -> i64 {
    start_camera_hwb_probe(
        env,
        surface,
        width,
        height,
        frame_count,
        reader_max_images,
        CameraHwbProbeMode::RawColorProjection,
        Some(permit),
    )
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateCameraHwbProjectionStereoOffsetUv(
    _env: *mut c_void,
    _thiz: *mut c_void,
    stereo_offset_uv: c_float,
) -> i64 {
    let applied_offset_uv =
        update_camera_hwb_projection_stereo_horizontal_offset_uv(stereo_offset_uv as f32);
    log_marker(format!(
        "status=projection-target-stereo-horizontal-offset-updated rawCameraProjectionProbe=true updateMask=1 projectionTargetStereoHorizontalOffsetUv={:.6} requestedProjectionTargetStereoHorizontalOffsetUv={:.6} {} runtimeCrash=false",
        applied_offset_uv,
        stereo_offset_uv as f32,
        camera_hwb_projection_marker_fields(),
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateCameraHwbProjectionTargetScale(
    _env: *mut c_void,
    _thiz: *mut c_void,
    target_scale: c_float,
) -> i64 {
    let applied_scale = update_camera_hwb_projection_target_live_scale(target_scale as f32);
    log_marker(format!(
        "status=projection-target-scale-updated rawCameraProjectionProbe=true updateMask=1 projectionTargetLiveScale={:.4} requestedProjectionTargetLiveScale={:.4} {} runtimeCrash=false",
        applied_scale,
        target_scale as f32,
        camera_hwb_projection_marker_fields(),
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdatePrivateLayerOverride(
    _env: *mut c_void,
    _thiz: *mut c_void,
    layer_override: c_float,
) -> i64 {
    let applied_layer_override =
        update_spatial_public_opaque_projection_layer_override(layer_override as f32);
    log_marker(format!(
        "status=private-layer-override-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true publicMultiStackOpaqueProjectionLayerOverride={:.3} requestedPublicMultiStackOpaqueProjectionLayerOverride={:.3} {} runtimeCrash=false",
        applied_layer_override,
        layer_override as f32,
        camera_hwb_projection_marker_fields(),
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdatePrivateLayerDepthAlignment(
    _env: *mut c_void,
    _thiz: *mut c_void,
    left_offset_x: c_float,
    left_offset_y: c_float,
    right_offset_x: c_float,
    right_offset_y: c_float,
    sample_scale: c_float,
    sample_scale_y: c_float,
    roll_degrees: c_float,
    metadata_auto_align: c_int,
) -> i64 {
    let applied_alignment = update_spatial_public_depth_alignment(
        left_offset_x as f32,
        left_offset_y as f32,
        right_offset_x as f32,
        right_offset_y as f32,
        sample_scale as f32,
        sample_scale_y as f32,
        roll_degrees as f32,
        metadata_auto_align != 0,
    );
    log_marker(format!(
        "status=private-layer-depth-alignment-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true publicMultiStackDepthAlignmentControl=true publicMultiStackDepthAlignmentLeftOffsetUv={:.6},{:.6} publicMultiStackDepthAlignmentRightOffsetUv={:.6},{:.6} publicMultiStackDepthAlignmentSampleScale={:.4} publicMultiStackDepthAlignmentSampleScaleY={:.4} publicMultiStackDepthAlignmentRollDegrees={:.3} publicMultiStackDepthMetadataAutoAlignRequested={} requestedPublicMultiStackDepthAlignmentLeftOffsetUv={:.6},{:.6} requestedPublicMultiStackDepthAlignmentRightOffsetUv={:.6},{:.6} requestedPublicMultiStackDepthAlignmentSampleScale={:.4} requestedPublicMultiStackDepthAlignmentSampleScaleY={:.4} requestedPublicMultiStackDepthAlignmentRollDegrees={:.3} requestedPublicMultiStackDepthMetadataAutoAlign={} {} runtimeCrash=false",
        applied_alignment.left_offset_uv[0],
        applied_alignment.left_offset_uv[1],
        applied_alignment.right_offset_uv[0],
        applied_alignment.right_offset_uv[1],
        applied_alignment.sample_scale,
        applied_alignment.sample_scale_y,
        applied_alignment.roll_degrees,
        bool_token(applied_alignment.metadata_auto_align),
        left_offset_x as f32,
        left_offset_y as f32,
        right_offset_x as f32,
        right_offset_y as f32,
        sample_scale as f32,
        sample_scale_y as f32,
        roll_degrees as f32,
        bool_token(metadata_auto_align != 0),
        camera_hwb_projection_marker_fields(),
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdatePrivateLayerDepthLayerPolicy(
    _env: *mut c_void,
    _thiz: *mut c_void,
    depth_layer_policy: c_int,
) -> i64 {
    let applied_policy = update_spatial_public_depth_layer_policy(depth_layer_policy.max(0) as u32);
    log_marker(format!(
        "status=private-layer-depth-layer-policy-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true publicMultiStackDepthLayerPolicy={} requestedPublicMultiStackDepthLayerPolicyCode={} publicMultiStackDepthLayerCompareMode={} publicMultiStackDepthLayerCompareEvidence={} {} runtimeCrash=false",
        applied_policy.marker_token(),
        depth_layer_policy,
        applied_policy.compare_mode_token(),
        if applied_policy.compare_mode_token() == "visual-shader" {
            "shader-samples-layer0-and-layer1-at-same-depth-uv"
        } else {
            "inactive"
        },
        camera_hwb_projection_marker_fields(),
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdatePrivateLayerGuideProcessing(
    _env: *mut c_void,
    _thiz: *mut c_void,
    preblur_kernel: c_int,
    preblur_input: c_int,
    postblur_kernel: c_int,
    camera_sampling: c_int,
) -> i64 {
    let applied = update_spatial_public_guide_processing_policy(
        preblur_kernel.max(0) as u32,
        preblur_input.max(0) as u32,
        postblur_kernel.max(0) as u32,
        camera_sampling.max(0) as u32,
    );
    log_marker(format!(
        "status=private-layer-guide-processing-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true {} requestedPublicGuidePreblurKernelCode={} requestedPublicGuidePreblurInputCode={} requestedPublicGuidePostblurKernelCode={} requestedPublicCameraSamplingCode={} runtimeCrash=false",
        applied.marker_fields(),
        preblur_kernel,
        preblur_input,
        postblur_kernel,
        camera_sampling,
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdatePrivateLayerZoneCompositor(
    _env: *mut c_void,
    _thiz: *mut c_void,
    coverage_mode: c_int,
    region_contract_version: c_int,
    buffer_geometry_mode: c_int,
    buffer_static_width_uv: c_float,
    buffer_fill_mode: c_int,
    stretch_extent_mode: c_int,
    stretch_source: c_int,
    debug_mode: c_int,
    outer_target_mode: c_int,
    stretch_mapping: c_int,
    projection_effect_edge_guard_enabled: c_int,
    stretch_option_flags: c_int,
    edge_inset_uv: c_float,
    max_inset_uv: c_float,
    stretch_curve: c_float,
    processed_mix: c_float,
    inner_signal: c_int,
    inner_width_uv: c_float,
    inner_curve: c_float,
    inner_threshold_r: c_float,
    inner_threshold_g: c_float,
    inner_threshold_b: c_float,
    inner_softness: c_float,
    inner_strength: c_float,
    inner_cycle_amplitude: c_float,
    inner_cycle_hz: c_float,
    inner_motion_gain: c_float,
    outer_signal: c_int,
    outer_width_uv: c_float,
    outer_curve: c_float,
    outer_threshold_r: c_float,
    outer_threshold_g: c_float,
    outer_threshold_b: c_float,
    outer_softness: c_float,
    outer_strength: c_float,
    outer_cycle_amplitude: c_float,
    outer_cycle_hz: c_float,
    outer_motion_gain: c_float,
) -> i64 {
    let applied = update_projection_zone_compositor_settings(
        coverage_mode.max(0) as u32,
        region_contract_version.max(0) as u32,
        buffer_geometry_mode.max(0) as u32,
        buffer_static_width_uv as f32,
        buffer_fill_mode.max(0) as u32,
        stretch_extent_mode.max(0) as u32,
        stretch_source.max(0) as u32,
        debug_mode.max(0) as u32,
        outer_target_mode.max(0) as u32,
        stretch_mapping.max(0) as u32,
        projection_effect_edge_guard_enabled != 0,
        stretch_option_flags.max(0) as u32,
        edge_inset_uv as f32,
        max_inset_uv as f32,
        stretch_curve as f32,
        processed_mix as f32,
        inner_signal.max(0) as u32,
        inner_width_uv as f32,
        inner_curve as f32,
        inner_threshold_r as f32,
        inner_threshold_g as f32,
        inner_threshold_b as f32,
        inner_softness as f32,
        inner_strength as f32,
        inner_cycle_amplitude as f32,
        inner_cycle_hz as f32,
        inner_motion_gain as f32,
        outer_signal.max(0) as u32,
        outer_width_uv as f32,
        outer_curve as f32,
        outer_threshold_r as f32,
        outer_threshold_g as f32,
        outer_threshold_b as f32,
        outer_softness as f32,
        outer_strength as f32,
        outer_cycle_amplitude as f32,
        outer_cycle_hz as f32,
        outer_motion_gain as f32,
    );
    log_marker(format!(
        "status=private-layer-zone-compositor-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true projectionZoneGeometryOrder=user-scale-then-dynamic-core projectionZoneVideoSampling=prepared-stereo-video-descriptor {} runtimeCrash=false",
        applied.marker_fields(),
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdatePrivateLayerRegionLayout(
    _env: *mut c_void,
    _thiz: *mut c_void,
    buffer_minimum_width_uv: c_float,
    buffer_maximum_width_uv: c_float,
    buffer_maximum_speed_meters_per_second: c_float,
    buffer_fill_mode: c_int,
    outer_content_mode: c_int,
    outer_stretch_source: c_int,
    outer_stretch_option_flags: c_int,
    outer_edge_inset_uv: c_float,
    outer_max_inset_uv: c_float,
    outer_stretch_curve: c_float,
    outer_processed_mix: c_float,
    center_content_mode: c_int,
    center_projection_mix: c_float,
    center_corner_radius_uv: c_float,
) -> i64 {
    let applied = update_projection_zone_region_layout_settings(
        buffer_minimum_width_uv as f32,
        buffer_maximum_width_uv as f32,
        buffer_maximum_speed_meters_per_second as f32,
        buffer_fill_mode.max(0) as u32,
        outer_content_mode.max(0) as u32,
        outer_stretch_source.max(0) as u32,
        outer_stretch_option_flags.max(0) as u32,
        outer_edge_inset_uv as f32,
        outer_max_inset_uv as f32,
        outer_stretch_curve as f32,
        outer_processed_mix as f32,
        center_content_mode.max(0) as u32,
        center_projection_mix as f32,
        center_corner_radius_uv as f32,
    );
    log_marker(format!(
        "status=private-layer-region-layout-updated rawCameraProjectionProbe=true updateMask=2 spatialPrivateLayerControlPanel=true {} runtimeCrash=false",
        applied.marker_fields(),
    ));
    2
}

#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdatePrivateLayerZoneChannelDynamics(
    _env: *mut c_void,
    _thiz: *mut c_void,
    inner_application_mode: c_int,
    inner_source_choice: c_int,
    inner_region_driver: c_int,
    inner_strength_r: c_float,
    inner_strength_g: c_float,
    inner_strength_b: c_float,
    inner_cycle_amplitude_r: c_float,
    inner_cycle_amplitude_g: c_float,
    inner_cycle_amplitude_b: c_float,
    inner_cycle_hz_r: c_float,
    inner_cycle_hz_g: c_float,
    inner_cycle_hz_b: c_float,
    inner_cycle_phase_r: c_float,
    inner_cycle_phase_g: c_float,
    inner_cycle_phase_b: c_float,
    outer_application_mode: c_int,
    outer_source_choice: c_int,
    outer_region_driver: c_int,
    outer_strength_r: c_float,
    outer_strength_g: c_float,
    outer_strength_b: c_float,
    outer_cycle_amplitude_r: c_float,
    outer_cycle_amplitude_g: c_float,
    outer_cycle_amplitude_b: c_float,
    outer_cycle_hz_r: c_float,
    outer_cycle_hz_g: c_float,
    outer_cycle_hz_b: c_float,
    outer_cycle_phase_r: c_float,
    outer_cycle_phase_g: c_float,
    outer_cycle_phase_b: c_float,
) -> i64 {
    let applied = update_projection_zone_channel_dynamics_settings(
        inner_application_mode.max(0) as u32,
        inner_source_choice.max(0) as u32,
        inner_region_driver.max(0) as u32,
        [
            inner_strength_r as f32,
            inner_strength_g as f32,
            inner_strength_b as f32,
        ],
        [
            inner_cycle_amplitude_r as f32,
            inner_cycle_amplitude_g as f32,
            inner_cycle_amplitude_b as f32,
        ],
        [
            inner_cycle_hz_r as f32,
            inner_cycle_hz_g as f32,
            inner_cycle_hz_b as f32,
        ],
        [
            inner_cycle_phase_r as f32,
            inner_cycle_phase_g as f32,
            inner_cycle_phase_b as f32,
        ],
        outer_application_mode.max(0) as u32,
        outer_source_choice.max(0) as u32,
        outer_region_driver.max(0) as u32,
        [
            outer_strength_r as f32,
            outer_strength_g as f32,
            outer_strength_b as f32,
        ],
        [
            outer_cycle_amplitude_r as f32,
            outer_cycle_amplitude_g as f32,
            outer_cycle_amplitude_b as f32,
        ],
        [
            outer_cycle_hz_r as f32,
            outer_cycle_hz_g as f32,
            outer_cycle_hz_b as f32,
        ],
        [
            outer_cycle_phase_r as f32,
            outer_cycle_phase_g as f32,
            outer_cycle_phase_b as f32,
        ],
    );
    log_marker(format!(
        "status=private-layer-zone-channel-dynamics-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true publicChannelTransport=true privateBlendFormulaOwnedByPrivateConsumer=true {} runtimeCrash=false",
        applied.marker_fields(),
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case, clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateRgbChannelTransform(
    _env: *mut c_void,
    _thiz: *mut c_void,
    mode: c_int,
    edge_mode: c_int,
    direction_noise_amount_turns: c_float,
    direction_noise_rate_hz: c_float,
    red_direction_turns: c_float,
    green_direction_turns: c_float,
    blue_direction_turns: c_float,
    red_direction_rate_hz: c_float,
    green_direction_rate_hz: c_float,
    blue_direction_rate_hz: c_float,
    red_displacement_strength_uv: c_float,
    green_displacement_strength_uv: c_float,
    blue_displacement_strength_uv: c_float,
    red_image_scale: c_float,
    green_image_scale: c_float,
    blue_image_scale: c_float,
    red_coverage_scale: c_float,
    green_coverage_scale: c_float,
    blue_coverage_scale: c_float,
) -> i64 {
    let applied = update_rgb_channel_transform_settings(
        mode.max(0) as u32,
        edge_mode.max(0) as u32,
        [
            red_direction_turns as f32,
            green_direction_turns as f32,
            blue_direction_turns as f32,
        ],
        [
            red_direction_rate_hz as f32,
            green_direction_rate_hz as f32,
            blue_direction_rate_hz as f32,
        ],
        direction_noise_amount_turns as f32,
        direction_noise_rate_hz as f32,
        [
            red_displacement_strength_uv as f32,
            green_displacement_strength_uv as f32,
            blue_displacement_strength_uv as f32,
        ],
        [
            red_image_scale as f32,
            green_image_scale as f32,
            blue_image_scale as f32,
        ],
        [
            red_coverage_scale as f32,
            green_coverage_scale as f32,
            blue_coverage_scale as f32,
        ],
    );
    log_marker(format!(
        "status=rgb-channel-transform-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true {} requestedRgbChannelTransformMode={} requestedRgbChannelTransformEdge={} runtimeCrash=false",
        applied.marker_fields(),
        mode,
        edge_mode,
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateStrengthCycleSpeedHz(
    _env: *mut c_void,
    _thiz: *mut c_void,
    requested_hz: c_float,
) -> i64 {
    let effective_hz = update_spatial_public_strength_cycle_speed_hz(requested_hz as f32);
    log_marker(format!(
        "status=strength-cycle-speed-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true requestedStrengthCycleSpeedHz={:.4} effectiveStrengthCycleSpeedHz={:.4} strengthCyclePhaseClock=frame-monotonic runtimeCrash=false",
        requested_hz as f32,
        effective_hz,
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateProjectionSurfaceDisplacement(
    _env: *mut c_void,
    _thiz: *mut c_void,
    enabled: c_int,
    max_displacement_m: c_float,
    reference_distance_m: c_float,
    polarity: c_float,
    edge_taper: c_float,
) -> i64 {
    let applied = update_projection_surface_displacement_settings(
        enabled != 0,
        max_displacement_m as f32,
        reference_distance_m as f32,
        polarity as f32,
        edge_taper as f32,
    );
    log_marker(format!(
        "status=projection-surface-displacement-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true {} requestedProjectionSurfaceDisplacementEnabled={} runtimeCrash=false",
        applied.marker_fields(crate::spatial_public_multistack::OPAQUE_PROJECTION_VERTEX_SHADER_COMPILED),
        enabled,
    ));
    1
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeUpdateProjectionSurfaceFeatures(
    _env: *mut c_void,
    _thiz: *mut c_void,
    tiling_enabled: c_int,
    topology: c_int,
    gap: c_float,
    depth_flexibility: c_float,
    scope: c_int,
    inner_alpha_enabled: c_int,
    inner_alpha_driver: c_int,
    threshold: c_float,
    softness: c_float,
    amount: c_float,
    invert: c_int,
    stretch_policy: c_int,
    stretch_obeys_projection_mask: c_int,
) -> i64 {
    let applied = update_projection_surface_feature_settings(
        tiling_enabled != 0,
        topology,
        gap as f32,
        depth_flexibility as f32,
        scope,
        inner_alpha_enabled != 0,
        inner_alpha_driver,
        threshold as f32,
        softness as f32,
        amount as f32,
        invert != 0,
        stretch_policy,
        stretch_obeys_projection_mask != 0,
    );
    let abi_supported =
        crate::spatial_public_multistack::PROJECTION_SURFACE_UNIFORM_ABI_VERSION >= 2;
    let tiling_supported =
        abi_supported && crate::spatial_public_multistack::OPAQUE_PROJECTION_VERTEX_SHADER_COMPILED;
    let inner_alpha_supported = abi_supported
        && crate::spatial_public_multistack::OPAQUE_PROJECTION_SHADER_COMPILED
        && crate::spatial_public_multistack::OPAQUE_PROJECTION_VIDEO_COMPOSITOR_SHADER_COMPILED;
    let update_mask = 1_i64
        | if tiling_supported { 1 << 1 } else { 0 }
        | if inner_alpha_supported { 1 << 2 } else { 0 }
        | if applied.tiling.effective(tiling_supported) {
            1 << 3
        } else {
            0
        }
        | if applied.inner_alpha.effective(inner_alpha_supported) {
            1 << 4
        } else {
            0
        };
    log_marker(format!(
        "status=projection-surface-features-updated rawCameraProjectionProbe=true updateMask={} spatialPrivateLayerControlPanel=true {} requestedProjectionSurfaceTilingEnabled={} requestedProjectionInnerAlphaEnabled={} runtimeCrash=false",
        update_mask,
        applied.marker_fields(
            current_projection_surface_displacement_settings(),
            tiling_supported,
            inner_alpha_supported,
            crate::spatial_public_multistack::PROJECTION_SURFACE_UNIFORM_ABI_VERSION,
        ),
        tiling_enabled,
        inner_alpha_enabled,
    ));
    update_mask
}

fn start_camera_hwb_probe(
    env: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
    reader_max_images: c_int,
    mode: CameraHwbProbeMode,
    local_permit: Option<crate::peer_projection_runtime::LocalCameraStartPermit>,
) -> i64 {
    let mut mask = 1_i64;
    if !surface.is_null() {
        mask |= 1 << 1;
    }
    if surface.is_null() || env.is_null() {
        log_marker(format!(
            "status=start-receipt startStatus=missing-env-or-surface startMask={} surfaceNonNull={} nativeWindowObtained=false renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi rawCameraProjectionProbe={} outputMode={} {} runtimeCrash=false",
            mask,
            bool_token(!surface.is_null()),
            mode.raw_projection_token(),
            mode.output_mode(),
            mode.public_multistack_marker_fields(),
        ));
        return mask;
    }

    if local_permit.is_none()
        && !crate::peer_projection_runtime::unfenced_local_camera_start_allowed()
    {
        log_marker(
            "status=start-receipt startStatus=unfenced-local-camera-start-rejected failClosed=true renderThreadSpawned=false runtimeCrash=false"
                .to_string(),
        );
        return mask;
    }

    if local_permit.is_some_and(|permit| !claim_local_camera_start(permit)) {
        log_marker(
            "status=start-receipt startStatus=stale-local-camera-permit failClosed=true renderThreadSpawned=false runtimeCrash=false"
                .to_string(),
        );
        return mask;
    }
    let window = unsafe { ANativeWindow_fromSurface(env, surface) };
    if window.is_null() {
        release_local_camera_start(local_permit);
        log_marker(format!(
            "status=start-receipt startStatus=native-window-null startMask={} surfaceNonNull=true nativeWindowObtained=false renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi rawCameraProjectionProbe={} outputMode={} {} runtimeCrash=false",
            mask,
            mode.raw_projection_token(),
            mode.output_mode(),
            mode.public_multistack_marker_fields(),
        ));
        return mask;
    }
    mask |= 1 << 2;
    if local_permit.is_some_and(|permit| {
        !crate::peer_projection_runtime::local_camera_start_permit_is_current(permit)
    }) {
        unsafe { ACameraNativeWindow_release(window.cast::<ANativeWindow>()) };
        release_local_camera_start(local_permit);
        return mask;
    }
    ACTIVE_LOCAL_CAMERA_WORKERS.fetch_add(1, Ordering::AcqRel);
    STOP_CAMERA_HWB_PROBE.store(false, Ordering::Release);
    if local_permit.is_some_and(|permit| {
        !crate::peer_projection_runtime::local_camera_start_permit_is_current(permit)
    }) {
        STOP_CAMERA_HWB_PROBE.store(true, Ordering::Release);
        unsafe { ACameraNativeWindow_release(window.cast::<ANativeWindow>()) };
        release_local_camera_start(local_permit);
        ACTIVE_LOCAL_CAMERA_WORKERS.fetch_sub(1, Ordering::AcqRel);
        publish_acquisition_stopped_if_quiescent();
        return mask;
    }

    let window_addr = window as usize;
    let width = width.max(64) as u32;
    let height = height.max(64) as u32;
    let max_frames = if matches!(mode, CameraHwbProbeMode::RawColorProjection) && frame_count <= 0 {
        0
    } else {
        (frame_count.max(1) as u32).min(CAMERA_HWB_PROBE_MAX_FRAMES)
    };
    let requested_frames_marker = mode.requested_frames_marker(max_frames);
    let reader_max_images = reader_max_images.clamp(3, 12);
    let spawn_result = thread::Builder::new()
        .name("spatial-camera-panel-hwb-probe".to_string())
        .spawn(move || {
            let window = window_addr as *mut vk::ANativeWindow;
            let started = Instant::now();
            let result = std::panic::catch_unwind(|| unsafe {
                if local_permit.is_some_and(|permit| {
                    !crate::peer_projection_runtime::local_camera_start_permit_is_current(permit)
                }) {
                    return Err("stale-local-camera-permit".to_string());
                }
                render_camera_hwb_probe(
                    window,
                    width,
                    height,
                    max_frames,
                    reader_max_images,
                    mode,
                    local_permit,
                )
            })
            .unwrap_or_else(|_| Err("panic".to_string()));
            unsafe {
                ACameraNativeWindow_release(window.cast::<ANativeWindow>());
            }
            ACTIVE_LOCAL_CAMERA_WORKERS.fetch_sub(1, Ordering::AcqRel);
            release_local_camera_start(local_permit);
            publish_acquisition_stopped_if_quiescent();
            match result {
                Ok(stats) => {
                    log_marker(format!(
                        "status=complete framesPresented={} requestedFrames={} frameLimit={} extent={}x{} leftCameraId={} rightCameraId={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} leftHwbImportSequence={} rightHwbImportSequence={} pairDeltaNs={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi vkGetAhbPropertiesResult=success sampledCameraTexture=true sampledLeftCameraTexture=true sampledRightCameraTexture={} samplerMode={} outputMode={} rawCameraProjectionProbe={} stereoSource={} monoDuplicated=false privateShaderStack=false customProjectionStack=false elapsedMs={} runtimeCrash=false {}",
                        stats.frames_presented,
                        requested_frames_marker,
                        if max_frames == 0 { "none" } else { "bounded" },
                        stats.extent.width,
                        stats.extent.height,
                        marker_token(&stats.left_camera_id),
                        marker_token(&stats.right_camera_id),
                        stats.left_frame_index,
                        stats.right_frame_index,
                        stats.left_hardware_buffer_id,
                        stats.right_hardware_buffer_id,
                        stats.left_hwb_import_sequence,
                        stats.right_hwb_import_sequence,
                        stats.pair_delta_ns,
                        bool_token(matches!(mode, CameraHwbProbeMode::RawColorProjection)),
                        stats.sampler_mode,
                        mode.output_mode(),
                        mode.raw_projection_token(),
                        mode.stereo_source(),
                        started.elapsed().as_millis(),
                        mode.projection_contract_marker_fields(),
                    ));
                }
                Err(error) => {
                    log_marker(format!(
                        "status=render-failed carrier=scenequadlayer-createAsAndroid-vulkan-wsi error={} sampledCameraTexture=false outputMode={} rawCameraProjectionProbe={} privateShaderStack=false customProjectionStack=false {} runtimeCrash=false",
                        marker_token(&error),
                        mode.output_mode(),
                        mode.raw_projection_token(),
                        mode.public_multistack_marker_fields(),
                    ));
                }
            }
        });

    match spawn_result {
        Ok(_) => {
            mask |= 1 << 3;
            log_marker(format!(
                "status=start-receipt startStatus=started startMask={} surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=true requestedWidthPx={} requestedHeightPx={} requestedFrames={} frameLimit={} readerMaxImages={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi outputMode={} rawCameraProjectionProbe={} stereoSource={} privateShaderStack=false customProjectionStack=false {} runtimeCrash=false",
                mask,
                width,
                height,
                mode.requested_frames_marker(max_frames),
                if max_frames == 0 { "none" } else { "bounded" },
                reader_max_images,
                mode.output_mode(),
                mode.raw_projection_token(),
                mode.stereo_source(),
                mode.public_multistack_marker_fields(),
            ));
        }
        Err(error) => {
            ACTIVE_LOCAL_CAMERA_WORKERS.fetch_sub(1, Ordering::AcqRel);
            release_local_camera_start(local_permit);
            publish_acquisition_stopped_if_quiescent();
            unsafe {
                ACameraNativeWindow_release(window.cast::<ANativeWindow>());
            }
            log_marker(format!(
                "status=start-receipt startStatus=thread-spawn-{} startMask={} surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi outputMode={} rawCameraProjectionProbe={} {} runtimeCrash=false",
                error.kind(),
                mask,
                mode.output_mode(),
                mode.raw_projection_token(),
                mode.public_multistack_marker_fields(),
            ));
        }
    }
    mask
}

pub(crate) unsafe fn start_peer_common_graph(
    window: *mut ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    frame_count: i32,
    route_generation: i64,
) -> i64 {
    if crate::own_stereo_capture_runtime::capture_route_selected() {
        let pending=crate::peer_projection_runtime::read_source(route_generation);
        if route_generation<=0 || pending.words[1]!=route_generation || pending.words[2]!=crate::peer_projection_runtime::SOURCE_PEER
            || pending.words[11]!=crate::peer_projection_runtime::RESULT_PENDING || pending.words[3]<=0 || pending.words[4]<=0
            || !projection_peer_binding_matches(route_generation as u64,pending.words[3] as u64,pending.words[4] as u64) {
            if !window.is_null(){ACameraNativeWindow_release(window);}return 0;
        }
        let result=start_source_set_common_graph(window,requested_width,requested_height,frame_count);
        if result<=0 {return result;}
        let attached=crate::peer_projection_runtime::record_peer_common_graph_attached(route_generation);
        return if attached.words[11]==crate::peer_projection_runtime::RESULT_PENDING {result} else {0};
    }
    if window.is_null() || route_generation <= 0 {
        return 0;
    }
    let max_frames = if frame_count <= 0 {
        0
    } else {
        (frame_count as u32).min(CAMERA_HWB_PROBE_MAX_FRAMES)
    };
    let window_address = window as usize;
    let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel(1);
    let cancellation = Arc::new(AtomicBool::new(false));
    {
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !owner.claim.claim(route_generation) {
            ACameraNativeWindow_release(window);
            return 0;
        }
        owner.cancellation = Some(cancellation.clone());
        let worker_cancellation = cancellation.clone();
        ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_add(1, Ordering::AcqRel);
        let spawn = thread::Builder::new()
            .name(format!("spatial-peer-common-graph-{route_generation}"))
            .spawn(move || {
                let window = window_address as *mut vk::ANativeWindow;
                let result = std::panic::catch_unwind(|| unsafe {
                    render_peer_common_graph(
                        window,
                        requested_width.max(64),
                        requested_height.max(64),
                        max_frames,
                        route_generation,
                        worker_cancellation,
                        ready_tx,
                    )
                })
                .unwrap_or_else(|_| Err("panic".to_string()));
                unsafe {
                    ACameraNativeWindow_release(window.cast::<ANativeWindow>());
                }
                ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_sub(1, Ordering::AcqRel);
                publish_acquisition_stopped_if_quiescent();
                if let Err(error) = result {
                    log_marker(format!(
                        "status=peer-common-graph-failed routeGeneration={} error={} source=peer-packed-stereo camera2Opened=false runtimeCrash=false",
                        route_generation,
                        marker_token(&error),
                    ));
                    crate::peer_projection_runtime::mark_route_lost(
                        route_generation,
                        crate::peer_projection_runtime::REASON_PROVIDER_REJECTED,
                    );
                }
            });
        match spawn {
            Ok(worker) => owner.worker = Some(worker),
            Err(_) => {
                ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_sub(1, Ordering::AcqRel);
                publish_acquisition_stopped_if_quiescent();
                owner.cancellation = None;
                let released = owner.claim.release(route_generation);
                debug_assert!(released);
                ACameraNativeWindow_release(window);
                return 0;
            }
        }
    }
    if matches!(
        ready_rx.recv_timeout(Duration::from_millis(CAMERA_HWB_PROBE_WAIT_FRAME_MS * 2)),
        Ok(Ok(()))
    ) {
        return 1;
    }
    let worker = {
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if owner.claim.generation() != Some(route_generation) {
            None
        } else {
            cancellation.store(true, Ordering::Release);
            owner.worker.take()
        }
    };
    if let Some(worker) = worker {
        let _ = worker.join();
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if owner.claim.generation() == Some(route_generation) {
            owner.cancellation = None;
            let released = owner.claim.release(route_generation);
            debug_assert!(released);
        }
    }
    crate::peer_projection_runtime::mark_route_lost(
        route_generation,
        crate::peer_projection_runtime::REASON_PROVIDER_REJECTED,
    );
    0
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopCameraHwbProbe(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    request_camera_hwb_probe_stop();
    log_marker(
        "status=stop-requested carrier=scenequadlayer-createAsAndroid-vulkan-wsi runtimeCrash=false"
            .to_string(),
    );
}

struct CameraHwbProbeStats {
    frames_presented: u32,
    extent: vk::Extent2D,
    left_camera_id: String,
    right_camera_id: String,
    left_frame_index: u64,
    right_frame_index: u64,
    left_hardware_buffer_id: u64,
    right_hardware_buffer_id: u64,
    left_hwb_import_sequence: u64,
    right_hwb_import_sequence: u64,
    pair_delta_ns: u64,
    sampler_mode: &'static str,
}

struct CameraHwbWsiParts {
    _entry: ash::Entry,
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    sdk_binding: crate::spatial_sdk_depth_handoff::SpatialDepthDeviceBindingV2,
    instance: ash::Instance,
    device: ash::Device,
    surface_loader: ash::khr::surface::Instance,
    surface: vk::SurfaceKHR,
    physical_device: vk::PhysicalDevice,
    queue_family_index: u32,
    extension_status: CameraVulkanExtensionStatus,
    foreign_queue_ownership_enabled: bool,
    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
    queue: vk::Queue,
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    _queue: vk::Queue,
    swapchain_loader: ash::khr::swapchain::Device,
    surface_format: vk::SurfaceFormatKHR,
    capabilities: vk::SurfaceCapabilitiesKHR,
    present_modes: Vec<vk::PresentModeKHR>,
    active_latency_launch_settings: CameraLatencySettings,
    present_mode: vk::PresentModeKHR,
    extent: vk::Extent2D,
    composite_alpha: vk::CompositeAlphaFlagsKHR,
    swapchain_usage: vk::ImageUsageFlags,
    swapchain: vk::SwapchainKHR,
    images: Vec<vk::Image>,
    image_views: Vec<vk::ImageView>,
    render_pass: vk::RenderPass,
    framebuffers: Vec<vk::Framebuffer>,
    command_pool: vk::CommandPool,
    command_buffers: Vec<vk::CommandBuffer>,
    image_available: vk::Semaphore,
    render_finished: vk::Semaphore,
    frame_fence: vk::Fence,
    gpu_timestamps: CameraHwbGpuTimestampTracker,
    memory_properties: vk::PhysicalDeviceMemoryProperties,
    ahb_device: AhbVulkanDevice,
}

impl CameraHwbWsiParts {
    unsafe fn create(
        window: *mut vk::ANativeWindow,
        requested_width: u32,
        requested_height: u32,
    ) -> Result<Self, String> {
        let entry = ash::Entry::load().map_err(|error| format!("vulkan-loader-{error}"))?;
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let sdk_binding = {
            let deadline = Instant::now() + Duration::from_secs(5);
            loop {
                if let Some(binding) =
                    crate::spatial_sdk_depth_handoff::spatial_depth_device_binding()
                {
                    break binding;
                }
                if Instant::now() >= deadline {
                    return Err("spatial-sdk-vulkan-binding-timeout".to_string());
                }
                thread::sleep(Duration::from_millis(10));
            }
        };
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let instance = ash::Instance::load(
            entry.static_fn(),
            vk::Instance::from_raw(sdk_binding.instance_handle),
        );
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let device = ash::Device::load(
            instance.fp_v1_0(),
            vk::Device::from_raw(sdk_binding.device_handle),
        );

        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let app_name = CString::new("rusty-quest-spatial-camera-panel").expect("static app name");
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let engine_name = CString::new("camera-hwb-spatial-probe").expect("static engine name");
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let app_info = vk::ApplicationInfo::default()
            .application_name(&app_name)
            .application_version(1)
            .engine_name(&engine_name)
            .engine_version(1)
            .api_version(vk::make_api_version(0, 1, 1, 0));
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let instance_extensions = [
            ash::khr::surface::NAME.as_ptr(),
            ash::khr::android_surface::NAME.as_ptr(),
        ];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let instance_info = vk::InstanceCreateInfo::default()
            .application_info(&app_info)
            .enabled_extension_names(&instance_extensions);
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let instance = entry
            .create_instance(&instance_info, None)
            .map_err(|error| format!("create-instance-{error:?}"))?;

        let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
        let android_surface_loader = ash::khr::android_surface::Instance::new(&entry, &instance);
        let surface_info = vk::AndroidSurfaceCreateInfoKHR::default().window(window);
        let surface = android_surface_loader
            .create_android_surface(&surface_info, None)
            .map_err(|error| {
                #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                instance.destroy_instance(None);
                format!("create-android-surface-{error:?}")
            })?;

        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let physical_devices = instance.enumerate_physical_devices().map_err(|error| {
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("enumerate-physical-devices-{error:?}")
        })?;
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let physical_devices = [vk::PhysicalDevice::from_raw(
            sdk_binding.physical_device_handle,
        )];
        let (physical_device, queue_family_index, extension_status) =
            select_camera_surface_device(&instance, &surface_loader, surface, &physical_devices)
                .ok_or_else(|| {
                    surface_loader.destroy_surface(surface, None);
                    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                    instance.destroy_instance(None);
                    "no-camera-hwb-vulkan-device".to_string()
                })?;

        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        if physical_device.as_raw() != sdk_binding.physical_device_handle
            || queue_family_index != sdk_binding.queue_family_index
            || sdk_binding.enabled_capability_mask & 0x0f != 0x0f
        {
            surface_loader.destroy_surface(surface, None);
            return Err(format!(
                "spatial-sdk-vulkan-binding-incompatible-physical-{}-queue-{}-capabilities-0x{:x}",
                physical_device.as_raw() == sdk_binding.physical_device_handle,
                queue_family_index == sdk_binding.queue_family_index,
                sdk_binding.enabled_capability_mask,
            ));
        }

        if !extension_status.external_hwb_extension_ready
            || !extension_status.sampler_ycbcr_extension_ready
            || !extension_status.sampler_ycbcr_feature_ready
        {
            surface_loader.destroy_surface(surface, None);
            #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
            instance.destroy_instance(None);
            return Err(format!(
                "vulkan-ahb-prereq-missing-externalHwb-{}-samplerYcbcrExt-{}-samplerYcbcrFeature-{}",
                extension_status.external_hwb_extension_ready,
                extension_status.sampler_ycbcr_extension_ready,
                extension_status.sampler_ycbcr_feature_ready,
            ));
        }

        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let queue_priorities = [1.0_f32];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let queue_info = [vk::DeviceQueueCreateInfo::default()
            .queue_family_index(queue_family_index)
            .queue_priorities(&queue_priorities)];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let foreign_queue_ownership_enabled = if crate::own_stereo_capture_runtime::capture_route_selected() {
            let supported=instance.enumerate_device_extension_properties(physical_device)
                .map_err(|e|format!("foreign-ownership-extension-query-{e:?}"))?.iter().any(|extension|
                    std::ffi::CStr::from_ptr(extension.extension_name.as_ptr()).to_bytes()==b"VK_EXT_queue_family_foreign");
            if !supported {return Err("own-source-foreign-ownership-extension-unavailable".into());}
            true
        } else {false};
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let mut device_extensions = vec![
            ash::khr::swapchain::NAME.as_ptr(),
            ash::android::external_memory_android_hardware_buffer::NAME.as_ptr(),
            ash::khr::sampler_ycbcr_conversion::NAME.as_ptr(),
        ];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        if foreign_queue_ownership_enabled {device_extensions.push(c"VK_EXT_queue_family_foreign".as_ptr());}
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let foreign_queue_ownership_enabled = sdk_binding.enabled_capability_mask &
            crate::spatial_sdk_depth_handoff::SPATIAL_DEPTH_CAP_FOREIGN_QUEUE_OWNERSHIP_V2 != 0;
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let mut sampler_ycbcr_enable = vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default()
            .sampler_ycbcr_conversion(true);
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let device_info = vk::DeviceCreateInfo::default()
            .queue_create_infos(&queue_info)
            .enabled_extension_names(&device_extensions)
            .push_next(&mut sampler_ycbcr_enable);
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let device = instance
            .create_device(physical_device, &device_info, None)
            .map_err(|error| {
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                format!("create-device-{error:?}")
            })?;
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let queue = device.get_device_queue(queue_family_index, 0);
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let _queue = vk::Queue::from_raw(sdk_binding.queue_handle);
        let swapchain_loader = ash::khr::swapchain::Device::new(&instance, &device);

        let surface_format = choose_surface_format(
            &surface_loader
                .get_physical_device_surface_formats(physical_device, surface)
                .map_err(|error| {
                    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                    device.destroy_device(None);
                    surface_loader.destroy_surface(surface, None);
                    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                    instance.destroy_instance(None);
                    format!("surface-formats-{error:?}")
                })?,
        );
        let capabilities = surface_loader
            .get_physical_device_surface_capabilities(physical_device, surface)
            .map_err(|error| {
                #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                device.destroy_device(None);
                surface_loader.destroy_surface(surface, None);
                #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                instance.destroy_instance(None);
                format!("surface-capabilities-{error:?}")
            })?;
        let present_modes = surface_loader
            .get_physical_device_surface_present_modes(physical_device, surface)
            .unwrap_or_default();
        let active_latency_launch_settings = current_camera_latency_settings();
        let present_mode = active_latency_launch_settings
            .present_mode
            .choose(&present_modes);
        let extent = choose_extent(&capabilities, requested_width, requested_height);
        let image_count = active_latency_launch_settings
            .image_count
            .choose(&capabilities);
        let composite_alpha = choose_composite_alpha(capabilities.supported_composite_alpha);
        let swapchain_usage = vk::ImageUsageFlags::COLOR_ATTACHMENT
            | if capabilities
                .supported_usage_flags
                .contains(vk::ImageUsageFlags::TRANSFER_SRC)
            {
                vk::ImageUsageFlags::TRANSFER_SRC
            } else {
                vk::ImageUsageFlags::empty()
            };
        let swapchain_info = vk::SwapchainCreateInfoKHR::default()
            .surface(surface)
            .min_image_count(image_count)
            .image_format(surface_format.format)
            .image_color_space(surface_format.color_space)
            .image_extent(extent)
            .image_array_layers(1)
            .image_usage(swapchain_usage)
            .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
            .pre_transform(capabilities.current_transform)
            .composite_alpha(composite_alpha)
            .present_mode(present_mode)
            .clipped(true);
        let swapchain = swapchain_loader
            .create_swapchain(&swapchain_info, None)
            .map_err(|error| {
                #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                device.destroy_device(None);
                surface_loader.destroy_surface(surface, None);
                #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                instance.destroy_instance(None);
                format!("create-swapchain-{error:?}")
            })?;
        let images = swapchain_loader
            .get_swapchain_images(swapchain)
            .map_err(|error| {
                swapchain_loader.destroy_swapchain(swapchain, None);
                #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                device.destroy_device(None);
                surface_loader.destroy_surface(surface, None);
                #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                instance.destroy_instance(None);
                format!("swapchain-images-{error:?}")
            })?;
        let image_views = create_image_views(&device, surface_format.format, &images)?;
        let render_pass = create_render_pass(&device, surface_format.format)?;
        let framebuffers = create_framebuffers(&device, render_pass, extent, &image_views)?;
        let command_pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(queue_family_index)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let command_pool = device
            .create_command_pool(&command_pool_info, None)
            .map_err(|error| format!("create-command-pool-{error:?}"))?;
        let command_buffers = device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(command_pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(images.len() as u32),
            )
            .map_err(|error| format!("allocate-command-buffers-{error:?}"))?;
        let semaphore_info = vk::SemaphoreCreateInfo::default();
        let image_available = device
            .create_semaphore(&semaphore_info, None)
            .map_err(|error| format!("create-image-semaphore-{error:?}"))?;
        let render_finished = device
            .create_semaphore(&semaphore_info, None)
            .map_err(|error| format!("create-render-semaphore-{error:?}"))?;
        let frame_fence = device
            .create_fence(
                &vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED),
                None,
            )
            .map_err(|error| format!("create-frame-fence-{error:?}"))?;
        let timestamp_valid_bits = instance
            .get_physical_device_queue_family_properties(physical_device)
            .get(queue_family_index as usize)
            .map(|family| family.timestamp_valid_bits)
            .unwrap_or(0);
        let timestamp_period_ns = instance
            .get_physical_device_properties(physical_device)
            .limits
            .timestamp_period as f64;
        let gpu_timestamps = CameraHwbGpuTimestampTracker::new(
            &device,
            images.len(),
            CameraHwbGpuTimestampTracker::requested_from_runtime(),
            timestamp_valid_bits,
            timestamp_period_ns,
        );
        let memory_properties = instance.get_physical_device_memory_properties(physical_device);
        let ahb_device = AhbVulkanDevice::new(&instance, &device);

        Ok(Self {
            _entry: entry,
            #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
            sdk_binding,
            instance,
            device,
            surface_loader,
            surface,
            physical_device,
            queue_family_index,
            extension_status,
            foreign_queue_ownership_enabled,
            #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
            queue,
            #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
            _queue,
            swapchain_loader,
            surface_format,
            capabilities,
            present_modes,
            active_latency_launch_settings,
            present_mode,
            extent,
            composite_alpha,
            swapchain_usage,
            swapchain,
            images,
            image_views,
            render_pass,
            framebuffers,
            command_pool,
            command_buffers,
            image_available,
            render_finished,
            frame_fence,
            gpu_timestamps,
            memory_properties,
            ahb_device,
        })
    }

    unsafe fn destroy_after_idle(self) {
        let Self {
            _entry,
            #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
            sdk_binding,
            instance,
            device,
            surface_loader,
            surface,
            physical_device: _,
            queue_family_index: _,
            extension_status: _,
            foreign_queue_ownership_enabled: _,
            #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
                queue: _,
            #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
                _queue: _,
            swapchain_loader,
            surface_format: _,
            capabilities: _,
            present_modes: _,
            active_latency_launch_settings: _,
            present_mode: _,
            extent: _,
            composite_alpha: _,
            swapchain_usage: _,
            swapchain,
            images: _,
            image_views,
            render_pass,
            framebuffers,
            command_pool,
            command_buffers: _,
            image_available,
            render_finished,
            frame_fence,
            mut gpu_timestamps,
            memory_properties: _,
            ahb_device: _,
        } = self;
        gpu_timestamps.destroy(&device);
        device.destroy_fence(frame_fence, None);
        device.destroy_semaphore(render_finished, None);
        device.destroy_semaphore(image_available, None);
        device.destroy_command_pool(command_pool, None);
        for framebuffer in framebuffers {
            device.destroy_framebuffer(framebuffer, None);
        }
        device.destroy_render_pass(render_pass, None);
        for view in image_views {
            device.destroy_image_view(view, None);
        }
        swapchain_loader.destroy_swapchain(swapchain, None);
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
            sdk_binding.session_generation,
        );
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        device.destroy_device(None);
        surface_loader.destroy_surface(surface, None);
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        instance.destroy_instance(None);
    }
}

struct PeerCommonGraphResources {
    wsi: Option<CameraHwbWsiParts>,
    normalizer: Option<PackedSbsNormalizer>,
    sampled_packed_image: Option<AhbVulkanSampledImage>,
    pending_camera_resources: Option<CameraHwbProbeResources>,
    pending_public_guide_targets: Option<SpatialPublicGuideTargets>,
    processing_graph: Option<CameraProcessingGraph>,
    projection_readback: Option<ProjectionReadback>,
    current_frame: Option<SpatialVideoProjectionFrame>,
    video_renderer: Option<SpatialVideoProjectionRenderer>,
}

impl PeerCommonGraphResources {
    fn new(wsi: CameraHwbWsiParts) -> Self {
        Self {
            wsi: Some(wsi),
            normalizer: None,
            sampled_packed_image: None,
            pending_camera_resources: None,
            pending_public_guide_targets: None,
            processing_graph: None,
            projection_readback: None,
            current_frame: None,
            video_renderer: None,
        }
    }

    unsafe fn teardown(mut self) -> Result<(), String> {
        let wsi = self.wsi.as_ref().expect("peer WSI remains owned");
        let idle_result = wsi
            .device
            .device_wait_idle()
            .map_err(|error| format!("peer-device-wait-idle-{error:?}"));
        if let Err(error)=idle_result {std::mem::forget(self);return Err(error);}
        if let Some(graph)=self.processing_graph.as_mut() {
            if let Some(targets)=graph.public_guide_targets.as_mut() {
                if let Err(error)=targets.retire_stereo_banks_after_fence(&wsi.device) {
                    std::mem::forget(self);return Err(error);
                }
            }
        }
        if let Some(mut renderer)=self.video_renderer.take(){renderer.destroy(&wsi.device);}
        if let Some(mut readback) = self.projection_readback.take() {
            readback.retire_after_fence(&wsi.device);
            readback.destroy(&wsi.device);
        }
        if let Some(graph) = self.processing_graph.take() {
            if let Some(targets) = graph.public_guide_targets {
                targets.destroy(&wsi.device);
            }
            graph.camera_resources.destroy(&wsi.device);
        }
        if let Some(targets) = self.pending_public_guide_targets.take() {
            targets.destroy(&wsi.device);
        }
        if let Some(resources) = self.pending_camera_resources.take() {
            resources.destroy(&wsi.device);
        }
        if let Some(image) = self.sampled_packed_image.take() {
            image.destroy(&wsi.device);
        }
        if let Some(normalizer) = self.normalizer.take() {
            normalizer.destroy(&wsi.device);
        }
        // AImage owns the imported AHardwareBuffer lease. It remains pinned until
        // every submitted command is terminal/cancelled and the device is idle.
        self.current_frame.take();
        self.wsi
            .take()
            .expect("peer WSI remains owned")
            .destroy_after_idle();
        idle_result
    }
}

struct LocalCameraStartup {
    camera_runtime: CameraProbeRuntime,
    initial_frames: CameraProbeFrameSet,
    camera_import_stream_generation: u64,
    camera_import_inactive_limit: usize,
    left_import_properties: AhbVulkanImportProperties,
    right_import_properties: AhbVulkanImportProperties,
    format_props: vk::AndroidHardwareBufferFormatPropertiesANDROID<'static>,
    format_key: AhbVulkanFormatKey,
}

struct CameraProcessingGraph {
    camera_resources: CameraHwbProbeResources,
    descriptor_set: vk::DescriptorSet,
    public_guide_targets: Option<SpatialPublicGuideTargets>,
    camera_replay_capture: Option<CameraReplayCaptureRecorder>,
    sampler_mode: &'static str,
    format_key: AhbVulkanFormatKey,
    format_props: vk::AndroidHardwareBufferFormatPropertiesANDROID<'static>,
}

struct LocalCameraProvider {
    camera_runtime: CameraProbeRuntime,
    current_left_frame: CameraProbeFrame,
    current_right_frame: CameraProbeFrame,
    pending_strict_left: Option<CameraProbeFrame>,
    pending_strict_right: Option<CameraProbeFrame>,
    sampled_left_image: AhbVulkanSampledImage,
    sampled_right_image: Option<AhbVulkanSampledImage>,
    left_camera_import_cache: CameraHwbImportCache,
    right_camera_import_cache: CameraHwbImportCache,
    left_camera_import_stats: CameraHwbImportPerformanceStats,
    right_camera_import_stats: CameraHwbImportPerformanceStats,
    camera_import_stream_generation: u64,
    last_polled_left_hwb_import_sequence: u64,
    last_polled_right_hwb_import_sequence: u64,
    strict_pair_rejections: u64,
    strict_unpaired_resets: u64,
    strict_pair_generation: u64,
    transition_left_camera_image: bool,
    transition_right_camera_image: bool,
    camera_reprojection_guard_band: CameraReprojectionGuardBandController,
    observed_latency_settings: CameraLatencySettings,
    freeze_frame_pending: bool,
    freeze_frame_latched: bool,
    latency_window: CameraLatencyWindow,
}

unsafe fn start_local_camera_input(
    ahb_device: &AhbVulkanDevice,
    reader_max_images: c_int,
    mode: CameraHwbProbeMode,
) -> Result<LocalCameraStartup, String> {
    if crate::own_stereo_capture_runtime::capture_route_selected() {
        return Err("legacy-local-camera-suppressed-own-capture-route".into());
    }
    let camera_runtime = CameraProbeRuntime::start(reader_max_images, mode.stream_mode())?;
    let camera_import_stream_generation =
        NEXT_CAMERA_IMPORT_STREAM_GENERATION.fetch_add(1, Ordering::AcqRel);
    let camera_import_inactive_limit = usize::try_from(reader_max_images)
        .unwrap_or(3)
        .saturating_add(1);
    let initial_frames = if matches!(mode, CameraHwbProbeMode::RawColorProjection) {
        camera_runtime
            .wait_for_first_stereo_frame(Duration::from_millis(CAMERA_HWB_PROBE_WAIT_FRAME_MS))
            .ok_or_else(|| "first-stereo-camera-frame-timeout".to_string())?
    } else {
        let frame = camera_runtime
            .wait_for_first_frame(Duration::from_millis(CAMERA_HWB_PROBE_WAIT_FRAME_MS))
            .ok_or_else(|| "first-camera-frame-timeout".to_string())?;
        CameraProbeFrameSet {
            left: frame.clone(),
            right: frame,
        }
    };

    let (left_import_properties, format_props) =
        query_ahb_vulkan_import_properties(ahb_device, &initial_frames.left.hardware_buffer)?;
    let (right_import_properties, _right_format_props) =
        query_ahb_vulkan_import_properties(ahb_device, &initial_frames.right.hardware_buffer)?;
    let format_key = left_import_properties.format_key;
    if right_import_properties.format_key != format_key {
        return Err(format!(
            "right-format-key-mismatch-left-external-{}-vk-{:?}-right-external-{}-vk-{:?}",
            format_key.external_format,
            format_key.format,
            right_import_properties.format_key.external_format,
            right_import_properties.format_key.format,
        ));
    }
    log_marker(format!(
        "status=ahb-properties leftCameraId={} rightCameraId={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} leftHwbImportSequence={} rightHwbImportSequence={} stereoSource={} pairDeltaNs={} vkGetAhbPropertiesResult=success externalFormat={} vkFormat={:?} leftAllocationSize={} rightAllocationSize={} leftMemoryTypeBits=0x{:x} rightMemoryTypeBits=0x{:x} formatFeaturesRaw=0x{:x} outputMode={} {}",
        marker_token(&initial_frames.left.camera_id),
        marker_token(&initial_frames.right.camera_id),
        initial_frames.left.frame_index,
        initial_frames.right.frame_index,
        initial_frames.left.descriptor.hardware_buffer_id,
        initial_frames.right.descriptor.hardware_buffer_id,
        initial_frames.left.hwb_import_sequence,
        initial_frames.right.hwb_import_sequence,
        mode.stereo_source(),
        initial_frames.pair_delta_ns(),
        format_key.external_format,
        format_key.format,
        left_import_properties.allocation_size,
        right_import_properties.allocation_size,
        left_import_properties.memory_type_bits,
        right_import_properties.memory_type_bits,
        format_props.format_features.as_raw(),
        mode.output_mode(),
        mode.projection_contract_marker_fields(),
    ));

    Ok(LocalCameraStartup {
        camera_runtime,
        initial_frames,
        camera_import_stream_generation,
        camera_import_inactive_limit,
        left_import_properties,
        right_import_properties,
        format_props,
        format_key,
    })
}

unsafe fn render_camera_hwb_probe(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    max_frames: u32,
    reader_max_images: c_int,
    mode: CameraHwbProbeMode,
    local_permit: Option<crate::peer_projection_runtime::LocalCameraStartPermit>,
) -> Result<CameraHwbProbeStats, String> {
    let CameraHwbWsiParts {
        _entry,
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        sdk_binding,
        instance,
        device,
        surface_loader,
        surface,
        physical_device,
        queue_family_index,
        extension_status,
        foreign_queue_ownership_enabled: _,
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        queue,
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        _queue,
        swapchain_loader,
        surface_format,
        capabilities,
        present_modes,
        active_latency_launch_settings,
        present_mode,
        extent,
        composite_alpha,
        swapchain_usage,
        swapchain,
        images,
        image_views,
        render_pass,
        framebuffers,
        command_pool,
        command_buffers,
        image_available,
        render_finished,
        frame_fence,
        mut gpu_timestamps,
        memory_properties,
        ahb_device,
    } = CameraHwbWsiParts::create(window, requested_width, requested_height)?;
    log_marker(format!(
        "status=gpu-timestamp-config {} runtimeCrash=false",
        gpu_timestamps.config_marker_fields(),
    ));
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    log_marker(format!(
        "status=spatial-sdk-vulkan-binding-accepted sameLogicalDevice=true samePhysicalDevice=true sameQueueFamily=true sameQueue=true queueFamilyIndex={} queueIndex={} appWsiOwned=true sdkDeviceOwned=true sdkQueueOpaqueOwnership=true appSubmissionAuthority=layer-broker consumerFencePolicy=nonblocking-poll perFrameHostFenceWait=false rawHandlesLogged=false enabledCapabilityMask=0x{:x} runtimeCrash=false",
        sdk_binding.queue_family_index,
        sdk_binding.queue_index,
        sdk_binding.enabled_capability_mask,
    ));

    log_marker(format!(
        "status=render-loop-ready carrier=scenequadlayer-createAsAndroid-vulkan-wsi producerPath=Camera2-AImageReader-AHardwareBuffer-Vulkan-WSI swapchainImages={} extent={}x{} surfaceFormat={:?} presentMode={:?} presentModesAvailable={} compositeAlpha={:?} swapchainUsage=0x{:x} projectionProducerReadbackTransferSrcAvailable={} externalHwbExtensionReady={} samplerYcbcrExtensionReady={} samplerYcbcrFeatureReady={} outputMode={} rawCameraProjectionProbe={} stereoSource={} privateShaderStack=false customProjectionStack=false dynamicCameraPoseMetadataUsed=false imageTimestampPoseAssociation=selected-by-camera-latency-reprojection-mode captureResultMetadataCallbacks=false runtimeCrash=false {} {}",
        images.len(),
        extent.width,
        extent.height,
        surface_format.format,
        present_mode,
        marker_token(&format!("{present_modes:?}")),
        composite_alpha,
        swapchain_usage.as_raw(),
        swapchain_usage.contains(vk::ImageUsageFlags::TRANSFER_SRC),
        extension_status.external_hwb_extension_ready,
        extension_status.sampler_ycbcr_extension_ready,
        extension_status.sampler_ycbcr_feature_ready,
        mode.output_mode(),
        mode.raw_projection_token(),
        mode.stereo_source(),
        mode.projection_contract_marker_fields(),
        active_latency_launch_settings.marker_fields(),
    ));
    log_marker(format!(
        "status=projection-producer-color-contract surfaceFormatSelected={:?} surfaceColorSpaceSelected={:?} compositeAlphaSelected={:?} androidDataspaceObserved=false systemLayerAlphaObserved=false colorContractEvidence=vulkan-swapchain-create-parameters runtimeCrash=false",
        surface_format.format, surface_format.color_space, composite_alpha,
    ));

    let LocalCameraStartup {
        camera_runtime,
        initial_frames,
        camera_import_stream_generation,
        camera_import_inactive_limit,
        left_import_properties,
        right_import_properties,
        format_props,
        format_key,
    } = start_local_camera_input(&ahb_device, reader_max_images, mode)?;

    let mut projection_readback = ProjectionReadback::new(
        surface_format,
        composite_alpha,
        extent,
        capabilities.supported_usage_flags,
        memory_properties,
    );
    let camera_resources =
        create_camera_hwb_probe_resources(&device, render_pass, format_key, &format_props, mode)?;
    let mut public_guide_targets = if matches!(mode, CameraHwbProbeMode::RawColorProjection) {
        match allocate_spatial_public_guide_targets(
            &device,
            &memory_properties,
            camera_resources.descriptor_set_layout,
            render_pass,
        ) {
            Ok(targets) => {
                log_marker(format!(
                    "status=public-multistack-guide-targets-ready outputMode={} rawCameraProjectionProbe=true stereoSource={} {}",
                    mode.output_mode(),
                    mode.stereo_source(),
                    targets.marker_fields(),
                ));
                log_marker(format!(
                    "status=public-multistack-contract-ready outputMode={} rawCameraProjectionProbe=true stereoSource={} {}",
                    mode.output_mode(),
                    mode.stereo_source(),
                    public_multistack_marker_fields(),
                ));
                Some(targets)
            }
            Err(error) => {
                log_marker(format!(
                    "status=public-multistack-guide-targets-skipped outputMode={} rawCameraProjectionProbe=true stereoSource={} error={} {}",
                    mode.output_mode(),
                    mode.stereo_source(),
                    marker_token(&error),
                    public_guide_targets_pending_marker_fields(&error),
                ));
                log_marker(format!(
                    "status=public-multistack-contract-ready outputMode={} rawCameraProjectionProbe=true stereoSource={} {}",
                    mode.output_mode(),
                    mode.stereo_source(),
                    public_multistack_marker_fields(),
                ));
                None
            }
        }
    } else {
        None
    };
    let video_settings = spatial_video_projection_settings();
    let mut video_renderer = if matches!(mode, CameraHwbProbeMode::RawColorProjection)
        && video_settings.active()
    {
        log_marker(format!(
            "status=spatial-video-projection-configured outputMode={} rawCameraProjectionProbe=true stereoSource={} {} runtimeCrash=false",
            mode.output_mode(),
            mode.stereo_source(),
            video_settings.marker_fields(),
        ));
        Some(SpatialVideoProjectionRenderer::new(
            &instance,
            &device,
            memory_properties,
            render_pass,
            true,
        ))
    } else {
        log_marker(format!(
            "status=spatial-video-projection-disabled-or-inactive outputMode={} rawCameraProjectionProbe={} stereoSource={} {} runtimeCrash=false",
            mode.output_mode(),
            mode.raw_projection_token(),
            mode.stereo_source(),
            video_settings.marker_fields(),
        ));
        None
    };

    let mut sampled_left_image = import_ahb_sampled_image(
        &device,
        &memory_properties,
        &initial_frames.left.hardware_buffer,
        AhbVulkanSampledImageCreateInfo {
            width: initial_frames.left.descriptor.width.max(1),
            height: initial_frames.left.descriptor.height.max(1),
            format_key,
            allocation_size: left_import_properties.allocation_size,
            memory_type_bits: left_import_properties.memory_type_bits,
            sampler_ycbcr_conversion: camera_resources.sampler_ycbcr_conversion,
            debug_label: "camera-hwb-spatial-probe-left",
        },
    )?;
    let mut sampled_right_image = if matches!(mode, CameraHwbProbeMode::RawColorProjection) {
        Some(import_ahb_sampled_image(
            &device,
            &memory_properties,
            &initial_frames.right.hardware_buffer,
            AhbVulkanSampledImageCreateInfo {
                width: initial_frames.right.descriptor.width.max(1),
                height: initial_frames.right.descriptor.height.max(1),
                format_key,
                allocation_size: right_import_properties.allocation_size,
                memory_type_bits: right_import_properties.memory_type_bits,
                sampler_ycbcr_conversion: camera_resources.sampler_ycbcr_conversion,
                debug_label: "camera-hwb-spatial-probe-right",
            },
        )?)
    } else {
        None
    };
    let descriptor_set = allocate_camera_hwb_probe_descriptor_set(
        &device,
        &camera_resources,
        sampled_left_image.image_view,
        sampled_right_image.as_ref().map(|image| image.image_view),
        mode,
    )?;
    let mut camera_replay_capture = if matches!(mode, CameraHwbProbeMode::RawColorProjection) {
        match configured_camera_replay_capture() {
            Some(config) => Some(CameraReplayCaptureRecorder::create(
                &device,
                &memory_properties,
                camera_resources.descriptor_set_layout,
                config,
            )?),
            None => None,
        }
    } else {
        None
    };
    let sampler_mode = if format_key.external_format != 0 {
        "external-format-ycbcr"
    } else {
        "concrete-vk-format"
    };
    log_marker(format!(
        "status=ahb-imported leftCameraId={} rightCameraId={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} leftHwbImportSequence={} rightHwbImportSequence={} sampledCameraTexture=true sampledLeftCameraTexture=true sampledRightCameraTexture={} samplerMode={} descriptorShape={} outputMode={} rawCameraProjectionProbe={} stereoSource={} privateShaderStack=false customProjectionStack=false {}",
        marker_token(&initial_frames.left.camera_id),
        marker_token(&initial_frames.right.camera_id),
        initial_frames.left.frame_index,
        initial_frames.right.frame_index,
        initial_frames.left.descriptor.hardware_buffer_id,
        initial_frames.right.descriptor.hardware_buffer_id,
        initial_frames.left.hwb_import_sequence,
        initial_frames.right.hwb_import_sequence,
        bool_token(matches!(mode, CameraHwbProbeMode::RawColorProjection)),
        sampler_mode,
        camera_resources.descriptor_shape,
        mode.output_mode(),
        mode.raw_projection_token(),
        mode.stereo_source(),
        mode.projection_contract_marker_fields(),
    ));

    let render_started = Instant::now();
    let surface_generation = NEXT_SDK_SURFACE_GENERATION.fetch_add(1, Ordering::AcqRel);
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut submitted_depth_lease: Option<
        crate::spatial_sdk_depth_handoff::SpatialDepthRenderLease,
    > = None;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut submitted_retirement: Option<
        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementState,
    > = None;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut submitted_video_qualification: Option<(SpatialVideoProjectionFrameStats, u64)> = None;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut submitted_broker_failure_observed_at: Option<Instant> = None;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut broker_terminal_consumed_total = 0_u64;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut submit_retired_total = 0_u64;
    let mut left_camera_import_cache = CameraHwbImportCache::new(
        camera_import_stream_generation,
        &initial_frames.left,
        camera_import_inactive_limit,
    );
    let mut right_camera_import_cache = CameraHwbImportCache::new(
        camera_import_stream_generation,
        &initial_frames.right,
        camera_import_inactive_limit,
    );
    log_marker(format!(
        "status=camera-import-cache-ready streamGeneration={} inactiveLimitPerEye={} totalImportBoundPerEye={} readerMaxImages={} framesInFlight=1 cacheIdentity=stream-generation-eye-camera-ahb-id-descriptor-vulkan-format removalPolicy=listener-tombstone-disable-on-overflow runtimeCrash=false",
        camera_import_stream_generation,
        camera_import_inactive_limit,
        camera_import_inactive_limit.saturating_add(1),
        reader_max_images,
    ));
    let mut current_left_frame = initial_frames.left;
    let mut current_right_frame = initial_frames.right;
    let mut last_polled_left_hwb_import_sequence = current_left_frame.hwb_import_sequence;
    let mut last_polled_right_hwb_import_sequence = current_right_frame.hwb_import_sequence;
    let mut pending_strict_left: Option<CameraProbeFrame> = None;
    let mut pending_strict_right: Option<CameraProbeFrame> = None;
    let mut strict_pair_rejections = 0_u64;
    let mut strict_unpaired_resets = 0_u64;
    let mut strict_pair_generation = 0_u64;
    let mut transition_left_camera_image = true;
    let mut transition_right_camera_image = matches!(mode, CameraHwbProbeMode::RawColorProjection);
    let mut frames_presented = 0_u32;
    let mut last_submitted_frame_slot: Option<usize> = None;
    let mut left_camera_import_stats =
        CameraHwbImportPerformanceStats::with_cache_inactive_limit(camera_import_inactive_limit);
    let mut right_camera_import_stats =
        CameraHwbImportPerformanceStats::with_cache_inactive_limit(camera_import_inactive_limit);
    let mut camera_reprojection_guard_band = CameraReprojectionGuardBandController::default();
    let mut spatial_video_projection_rendered_marker_logged = false;
    let mut last_projection_zone_render_stats = None;
    let mut public_multistack_depth_evidence_marker_logged = false;
    let observed_latency_settings = active_latency_launch_settings;
    let freeze_frame_pending = active_latency_launch_settings.enabled
        && active_latency_launch_settings.freeze_frame
        && active_latency_launch_settings.camera_sync_mode
            == CameraLatencyCameraSyncMode::HoldImageUntilGpuFence;
    let freeze_frame_latched = false;
    let (initial_left_published_frames, initial_right_published_frames) =
        camera_runtime.published_frame_counts();
    let latency_window = CameraLatencyWindow::new(
        current_left_frame.frame_index,
        current_right_frame.frame_index,
        initial_left_published_frames,
        initial_right_published_frames,
    );
    let mut processing_graph = CameraProcessingGraph {
        camera_resources,
        descriptor_set,
        public_guide_targets,
        camera_replay_capture,
        sampler_mode,
        format_key,
        format_props,
    };
    let mut local_provider = LocalCameraProvider {
        camera_runtime,
        current_left_frame,
        current_right_frame,
        pending_strict_left,
        pending_strict_right,
        sampled_left_image,
        sampled_right_image,
        left_camera_import_cache,
        right_camera_import_cache,
        left_camera_import_stats,
        right_camera_import_stats,
        camera_import_stream_generation,
        last_polled_left_hwb_import_sequence,
        last_polled_right_hwb_import_sequence,
        strict_pair_rejections,
        strict_unpaired_resets,
        strict_pair_generation,
        transition_left_camera_image,
        transition_right_camera_image,
        camera_reprojection_guard_band,
        observed_latency_settings,
        freeze_frame_pending,
        freeze_frame_latched,
        latency_window,
    };
    while (max_frames == 0 || frames_presented < max_frames)
        && !STOP_CAMERA_HWB_PROBE.load(Ordering::Acquire)
        && local_permit.map_or(true, |permit| {
            crate::peer_projection_runtime::local_camera_start_permit_is_current(permit)
        })
    {
        let loop_started = Instant::now();
        let requested_latency_settings = current_camera_latency_settings();
        if requested_latency_settings != local_provider.observed_latency_settings {
            let previous_latency_settings = local_provider.observed_latency_settings;
            let launch_settings_pending_restart = requested_latency_settings.present_mode
                != active_latency_launch_settings.present_mode
                || requested_latency_settings.image_count
                    != active_latency_launch_settings.image_count
                || requested_latency_settings.capture_fps
                    != active_latency_launch_settings.capture_fps
                || requested_latency_settings.capture_processing
                    != active_latency_launch_settings.capture_processing;
            local_provider.observed_latency_settings = requested_latency_settings;
            let (left_published_frames, right_published_frames) =
                local_provider.camera_runtime.published_frame_counts();
            local_provider.latency_window = CameraLatencyWindow::new(
                local_provider.current_left_frame.frame_index,
                local_provider.current_right_frame.frame_index,
                left_published_frames,
                right_published_frames,
            );
            local_provider.pending_strict_left = None;
            local_provider.pending_strict_right = None;
            if !local_provider.observed_latency_settings.enabled
                || !local_provider.observed_latency_settings.freeze_frame
            {
                if local_provider.freeze_frame_latched || local_provider.freeze_frame_pending {
                    log_marker(format!(
                        "status=camera-freeze-released cameraLatencyRevision={} runtimeCrash=false",
                        local_provider.observed_latency_settings.revision,
                    ));
                }
                local_provider.freeze_frame_pending = false;
                local_provider.freeze_frame_latched = false;
            } else if local_provider.observed_latency_settings.camera_sync_mode
                != CameraLatencyCameraSyncMode::HoldImageUntilGpuFence
            {
                local_provider.freeze_frame_pending = false;
                local_provider.freeze_frame_latched = false;
                log_marker(format!(
                    "status=camera-freeze-rejected reason=requires-hold-image-until-gpu-fence cameraLatencyRevision={} cameraSyncRequested={} runtimeCrash=false",
                    local_provider.observed_latency_settings.revision,
                    local_provider.observed_latency_settings.camera_sync_mode.marker_token(),
                ));
            } else if !previous_latency_settings.freeze_frame
                || previous_latency_settings.camera_sync_mode
                    != CameraLatencyCameraSyncMode::HoldImageUntilGpuFence
            {
                local_provider.freeze_frame_pending = true;
                local_provider.freeze_frame_latched = false;
                log_marker(format!(
                    "status=camera-freeze-armed latchPolicy=next-complete-fence-held-stereo-import cameraLatencyRevision={} runtimeCrash=false",
                    local_provider.observed_latency_settings.revision,
                ));
            }
            log_marker(format!(
                "status=latency-settings-observed launchSettingsPendingRestart={} activePresentMode={:?} activeSwapchainImages={} {}",
                bool_token(launch_settings_pending_restart),
                present_mode,
                images.len(),
                local_provider.observed_latency_settings.marker_fields(),
            ));
        }
        let mut frame_timing = CameraLatencyFrameTiming::default();
        let fence_wait_started = Instant::now();
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        device
            .wait_for_fences(&[frame_fence], true, u64::MAX)
            .map_err(|error| format!("wait-fence-{error:?}"))?;
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let fence_signaled = match device.get_fence_status(frame_fence) {
            Ok(signaled) => signaled,
            Err(error) => {
                if let Some(failed_lease) = submitted_depth_lease.take() {
                    let release_status =
                        crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease(
                            failed_lease,
                        );
                    log_marker(format!(
                        "status=spatial-sdk-submit-retirement-fence-error requestId={} fenceError={error:?} leaseReleasedAfterDeviceError={} releaseStatus={} runtimeCrash=false",
                        submitted_retirement.map(|state| state.request_id).unwrap_or(0),
                        bool_token(release_status == 0),
                        release_status,
                    ));
                }
                return Err(format!("poll-fence-{error:?}"));
            }
        };
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        if let Some(retirement) = submitted_retirement.as_mut() {
            if fence_signaled {
                retirement.observe_fence();
            }
            if retirement.broker_status.is_none() {
                match crate::spatial_sdk_depth_handoff::poll_spatial_submit_request(
                    retirement.request_id,
                ) {
                    Ok(result) if result.status == 0 || result.status < 0 => {
                        if retirement.observe_terminal(result) {
                            broker_terminal_consumed_total =
                                broker_terminal_consumed_total.saturating_add(1);
                            if retirement.broker_status.is_some_and(|status| status < 0) {
                                submitted_broker_failure_observed_at = Some(Instant::now());
                            }
                            let request_ordinal = retirement.request_id & u64::from(u32::MAX);
                            if request_ordinal <= 4
                                || request_ordinal % 300 == 0
                                || retirement.broker_status.is_some_and(|status| status < 0)
                                || retirement.terminal_consume_count != 1
                            {
                                log_marker(format!(
                                    "status=spatial-sdk-queue-broker-terminal-consumed requestId={} requestOrdinal={} brokerStatus={} vkResult={} queueSubmitAccepted={} fenceComplete={} terminalConsumeCount={} terminalConsumedTotal={} repeatedNotReadyCount={} staleRequestRepoll=false leasePinned=true markerPolicy=first4-periodic300-failure-immediate runtimeCrash=false",
                                    retirement.request_id,
                                    request_ordinal,
                                    retirement.broker_status.unwrap_or(1),
                                    retirement.broker_vk_result,
                                    bool_token(
                                        retirement.qualification_flags
                                            & crate::spatial_sdk_depth_handoff::QUALIFICATION_QUEUE_SUBMIT_ACCEPTED
                                            != 0,
                                    ),
                                    bool_token(retirement.fence_signaled),
                                    retirement.terminal_consume_count,
                                    broker_terminal_consumed_total,
                                    retirement.not_ready_count,
                                ));
                            }
                        }
                    }
                    Ok(_) => retirement.observe_not_ready(),
                    Err(status) if status == crate::spatial_sdk_depth_handoff::STATUS_NOT_READY => {
                        retirement.observe_not_ready();
                    }
                    Err(status) => {
                        if let Some(failed_lease) = submitted_depth_lease.take() {
                            let release_status =
                                crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease(
                                    failed_lease,
                                );
                            log_marker(format!(
                                "status=spatial-sdk-queue-broker-unsubmitted-failure requestId={} brokerStatus={} fenceComplete={} typedReleasePath=unsubmitted leaseReleased={} releaseStatus={} runtimeCrash=false",
                                retirement.request_id,
                                status,
                                bool_token(retirement.fence_signaled),
                                bool_token(release_status == 0),
                                release_status,
                            ));
                        }
                        return Err(format!("spatial-sdk-queue-broker-poll-{status}"));
                    }
                }
            }
            match retirement.action() {
                crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::Wait => {
                    if retirement.broker_status.is_some_and(|status| status < 0)
                        && submitted_broker_failure_observed_at
                            .is_some_and(|observed| observed.elapsed() >= Duration::from_secs(2))
                    {
                        crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                            sdk_binding.session_generation,
                        );
                        log_marker(format!(
                            "status=spatial-sdk-queue-broker-failure-fence-timeout requestId={} brokerStatus={} queueSubmitAccepted=true fenceComplete=false timeoutMs=2000 typedReleasePath=session-teardown-drain leaseReleased=false leasePinnedForSessionTeardown=true noUnsafeSlotReuse=true runtimeCrash=false",
                            retirement.request_id,
                            retirement.broker_status.unwrap_or(-7),
                        ));
                        return Err("spatial-sdk-queue-broker-failure-fence-timeout".to_string());
                    }
                    thread::yield_now();
                    continue;
                }
                crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseSuccess => {
                }
                failure_action => {
                    let request_id = retirement.request_id;
                    let broker_status = retirement.broker_status.unwrap_or(1);
                    let broker_vk_result = retirement.broker_vk_result;
                    let fence_complete = retirement.fence_signaled;
                    let release_status = submitted_depth_lease
                        .take()
                        .map(crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease)
                        .unwrap_or(0);
                    log_marker(format!(
                        "status=spatial-sdk-queue-broker-terminal-failure requestId={} brokerStatus={} vkResult={} fenceComplete={} typedReleasePath={} leaseReleased={} releaseStatus={} terminalConsumeCount={} runtimeCrash=false",
                        request_id,
                        broker_status,
                        broker_vk_result,
                        bool_token(fence_complete),
                        match failure_action {
                            crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseUnsubmittedFailure => "unsubmitted",
                            _ => "submitted-fence-complete",
                        },
                        bool_token(release_status == 0),
                        release_status,
                        retirement.terminal_consume_count,
                    ));
                    return Err(format!(
                        "spatial-sdk-queue-broker-completion-{broker_status}-vk-{broker_vk_result}"
                    ));
                }
            }
        } else if !fence_signaled {
            thread::yield_now();
            continue;
        }
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        if let Some(completed_retirement) = submitted_retirement.take() {
            submitted_broker_failure_observed_at = None;
            let request_ordinal = completed_retirement.request_id & u64::from(u32::MAX);
            let release_status = submitted_depth_lease
                .take()
                .map(crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease)
                .unwrap_or(0);
            submit_retired_total = submit_retired_total.saturating_add(1);
            let _ = record_vulkan_wsi_present_returned(request_ordinal);
            if let Some((video_stats, present_ordinal)) = submitted_video_qualification.take() {
                record_presented_frame(
                    video_stats.ready,
                    video_stats.rendered,
                    video_stats.frame_index,
                    video_stats.timestamp_ns,
                    present_ordinal,
                );
            }
            if request_ordinal <= 4
                || request_ordinal % 300 == 0
                || release_status != 0
                || completed_retirement.terminal_consume_count != 1
            {
                log_marker(format!(
                    "status=spatial-sdk-submit-retired requestId={} requestOrdinal={} brokerComplete=true fenceComplete=true fenceCompleteBeforeLeaseRelease=true terminalConsumeCount={} terminalConsumedTotal={} submitRetiredTotal={} staleRequestRepoll=false leaseReleased={} releaseStatus={} markerPolicy=first4-periodic300-failure-immediate runtimeCrash=false",
                    completed_retirement.request_id,
                    request_ordinal,
                    completed_retirement.terminal_consume_count,
                    broker_terminal_consumed_total,
                    submit_retired_total,
                    bool_token(release_status == 0),
                    release_status,
                ));
            }
            if release_status != 0 {
                return Err(format!("spatial-depth-lease-release-{release_status}"));
            }
        }
        let (left_removed, right_removed) = local_provider
            .camera_runtime
            .drain_removed_hardware_buffer_ids();
        local_provider
            .left_camera_import_cache
            .process_removed_hardware_buffer_ids(
                &device,
                &left_removed.0,
                left_removed.1,
                &mut local_provider.left_camera_import_stats,
            );
        local_provider
            .right_camera_import_cache
            .process_removed_hardware_buffer_ids(
                &device,
                &right_removed.0,
                right_removed.1,
                &mut local_provider.right_camera_import_stats,
            );
        if let Some(retired_frame_slot) = last_submitted_frame_slot.take() {
            if let Some(sample) = gpu_timestamps.read_retired_slot(&device, retired_frame_slot) {
                if sample.frame_id <= 4 || sample.frame_id % 300 == 0 {
                    log_marker(format!(
                        "status=gpu-timestamp-sample {} {} runtimeCrash=false",
                        sample.marker_fields(),
                        gpu_timestamps.summary_marker_fields(),
                    ));
                    if let Some(targets) = processing_graph.public_guide_targets.as_ref() {
                        log_marker(format!(
                            "status=cpu-import-sample sampleFrameId={} {} runtimeCrash=false",
                            sample.frame_id,
                            targets.uniform_upload_summary_marker_fields(),
                        ));
                    }
                    log_marker(format!(
                        "status=camera-import-performance-sample sampleFrameId={} {} policy=bounded-generation-aware-ahb-vulkan-import-cache telemetryAllocation=scalar telemetryPerFrameLog=false runtimeCrash=false",
                        sample.frame_id,
                        local_provider.left_camera_import_stats.marker_fields("left"),
                    ));
                    log_marker(format!(
                        "status=camera-import-performance-sample sampleFrameId={} {} policy=bounded-generation-aware-ahb-vulkan-import-cache telemetryAllocation=scalar telemetryPerFrameLog=false runtimeCrash=false",
                        sample.frame_id,
                        local_provider.right_camera_import_stats.marker_fields("right"),
                    ));
                }
            }
            crate::peer_projection_runtime::record_current_local_submission_retired(
                local_provider.camera_import_stream_generation,
                local_provider
                    .current_left_frame
                    .hwb_import_sequence
                    .max(local_provider.current_right_frame.hwb_import_sequence),
            );
        }
        if let Some(capture) = processing_graph.camera_replay_capture.as_mut() {
            capture.retire_completed(&device)?;
        }
        projection_readback.retire_after_fence(&device);
        if let Some(targets)=processing_graph.public_guide_targets.as_mut() {
            targets.retire_stereo_banks_after_fence(&device)?;
        }
        device
            .reset_fences(&[frame_fence])
            .map_err(|error| format!("reset-fence-{error:?}"))?;
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let current_depth_lease =
            crate::spatial_sdk_depth_handoff::acquire_spatial_depth_render_lease();
        frame_timing.fence_wait = fence_wait_started.elapsed();
        if let Some(renderer) = video_renderer.as_mut() {
            renderer.retire_completed_frame_handles();
        }
        let mut left_imported = false;
        let mut right_imported = false;
        if mode.should_stream_latest_frame()
            && !local_provider.freeze_frame_latched
            && local_provider
                .observed_latency_settings
                .should_adopt_camera_image(frames_presented)
        {
            let frame_wait = Duration::from_millis(
                local_provider
                    .observed_latency_settings
                    .effective_frame_wait_ms() as u64,
            );
            match local_provider.observed_latency_settings.stereo_policy {
                CameraLatencyStereoPolicy::IndependentLatest => {
                    let left_wait_started = Instant::now();
                    let next_left_frame = local_provider.camera_runtime.wait_for_left_frame_after(
                        local_provider.last_polled_left_hwb_import_sequence,
                        frame_wait,
                    );
                    frame_timing.camera_wait += left_wait_started.elapsed();
                    if let Some(next_frame) = next_left_frame {
                        local_provider.last_polled_left_hwb_import_sequence =
                            next_frame.hwb_import_sequence;
                        let import_started = Instant::now();
                        match local_provider.left_camera_import_cache.acquire(
                            &device,
                            &memory_properties,
                            &ahb_device,
                            &processing_graph.camera_resources,
                            processing_graph.format_key,
                            &next_frame,
                            &mut local_provider.left_camera_import_stats,
                        ) {
                            Ok(next_sampled_image) => {
                                update_camera_hwb_probe_descriptor_set(
                                    &device,
                                    &processing_graph.camera_resources,
                                    processing_graph.descriptor_set,
                                    next_sampled_image.image_view,
                                    local_provider
                                        .sampled_right_image
                                        .as_ref()
                                        .map(|image| image.image_view),
                                    mode,
                                );
                                log_fence_held_frame_retirement(
                                    &local_provider.current_left_frame,
                                    "left",
                                );
                                let previous_image = std::mem::replace(
                                    &mut local_provider.sampled_left_image,
                                    next_sampled_image,
                                );
                                local_provider.left_camera_import_cache.retire(
                                    &device,
                                    processing_graph.format_key,
                                    &local_provider.current_left_frame,
                                    previous_image,
                                    &mut local_provider.left_camera_import_stats,
                                )?;
                                local_provider.current_left_frame = next_frame;
                                local_provider.transition_left_camera_image = true;
                                left_imported = true;
                            }
                            Err(error) => {
                                log_camera_frame_import_skipped(&next_frame, "left", mode, &error)
                            }
                        }
                        frame_timing.camera_import += import_started.elapsed();
                    }
                    let right_wait_started = Instant::now();
                    let next_right_frame =
                        local_provider.camera_runtime.wait_for_right_frame_after(
                            local_provider.last_polled_right_hwb_import_sequence,
                            frame_wait,
                        );
                    frame_timing.camera_wait += right_wait_started.elapsed();
                    if let Some(next_frame) = next_right_frame {
                        local_provider.last_polled_right_hwb_import_sequence =
                            next_frame.hwb_import_sequence;
                        let import_started = Instant::now();
                        match local_provider.right_camera_import_cache.acquire(
                            &device,
                            &memory_properties,
                            &ahb_device,
                            &processing_graph.camera_resources,
                            processing_graph.format_key,
                            &next_frame,
                            &mut local_provider.right_camera_import_stats,
                        ) {
                            Ok(next_sampled_image) => {
                                update_camera_hwb_probe_descriptor_set(
                                    &device,
                                    &processing_graph.camera_resources,
                                    processing_graph.descriptor_set,
                                    local_provider.sampled_left_image.image_view,
                                    Some(next_sampled_image.image_view),
                                    mode,
                                );
                                log_fence_held_frame_retirement(
                                    &local_provider.current_right_frame,
                                    "right",
                                );
                                let previous_image = local_provider
                                    .sampled_right_image
                                    .replace(next_sampled_image)
                                    .expect("raw projection has right sampled image");
                                local_provider.right_camera_import_cache.retire(
                                    &device,
                                    processing_graph.format_key,
                                    &local_provider.current_right_frame,
                                    previous_image,
                                    &mut local_provider.right_camera_import_stats,
                                )?;
                                local_provider.current_right_frame = next_frame;
                                local_provider.transition_right_camera_image = true;
                                right_imported = true;
                            }
                            Err(error) => {
                                log_camera_frame_import_skipped(&next_frame, "right", mode, &error)
                            }
                        }
                        frame_timing.camera_import += import_started.elapsed();
                    }
                }
                CameraLatencyStereoPolicy::MonoDuplicateLeft => {
                    let left_wait_started = Instant::now();
                    let next_left_frame = local_provider.camera_runtime.wait_for_left_frame_after(
                        local_provider.last_polled_left_hwb_import_sequence,
                        frame_wait,
                    );
                    frame_timing.camera_wait += left_wait_started.elapsed();
                    if let Some(next_frame) = next_left_frame {
                        local_provider.last_polled_left_hwb_import_sequence =
                            next_frame.hwb_import_sequence;
                        let import_started = Instant::now();
                        match local_provider.left_camera_import_cache.acquire(
                            &device,
                            &memory_properties,
                            &ahb_device,
                            &processing_graph.camera_resources,
                            processing_graph.format_key,
                            &next_frame,
                            &mut local_provider.left_camera_import_stats,
                        ) {
                            Ok(next_sampled_image) => {
                                update_camera_hwb_probe_descriptor_set(
                                    &device,
                                    &processing_graph.camera_resources,
                                    processing_graph.descriptor_set,
                                    next_sampled_image.image_view,
                                    None,
                                    mode,
                                );
                                log_fence_held_frame_retirement(
                                    &local_provider.current_left_frame,
                                    "left-mono-source",
                                );
                                let previous_image = std::mem::replace(
                                    &mut local_provider.sampled_left_image,
                                    next_sampled_image,
                                );
                                local_provider.left_camera_import_cache.retire(
                                    &device,
                                    processing_graph.format_key,
                                    &local_provider.current_left_frame,
                                    previous_image,
                                    &mut local_provider.left_camera_import_stats,
                                )?;
                                local_provider.current_left_frame = next_frame;
                                local_provider.current_right_frame =
                                    local_provider.current_left_frame.clone();
                                local_provider.transition_left_camera_image = true;
                                left_imported = true;
                                right_imported = true;
                            }
                            Err(error) => log_camera_frame_import_skipped(
                                &next_frame,
                                "left-mono-source",
                                mode,
                                &error,
                            ),
                        }
                        frame_timing.camera_import += import_started.elapsed();
                    }
                }
                CameraLatencyStereoPolicy::StrictTimestampPair => {
                    if local_provider.pending_strict_left.is_none() {
                        let left_wait_started = Instant::now();
                        local_provider.pending_strict_left =
                            local_provider.camera_runtime.wait_for_left_frame_after(
                                local_provider.last_polled_left_hwb_import_sequence,
                                frame_wait,
                            );
                        frame_timing.camera_wait += left_wait_started.elapsed();
                        if let Some(frame) = local_provider.pending_strict_left.as_ref() {
                            local_provider.last_polled_left_hwb_import_sequence =
                                frame.hwb_import_sequence;
                        }
                    }
                    if local_provider.pending_strict_right.is_none() {
                        let right_wait_started = Instant::now();
                        local_provider.pending_strict_right =
                            local_provider.camera_runtime.wait_for_right_frame_after(
                                local_provider.last_polled_right_hwb_import_sequence,
                                frame_wait,
                            );
                        frame_timing.camera_wait += right_wait_started.elapsed();
                        if let Some(frame) = local_provider.pending_strict_right.as_ref() {
                            local_provider.last_polled_right_hwb_import_sequence =
                                frame.hwb_import_sequence;
                        }
                    }
                    if let (Some(left), Some(right)) = (
                        local_provider.pending_strict_left.as_ref(),
                        local_provider.pending_strict_right.as_ref(),
                    ) {
                        let pair_delta_ns = camera_probe_pair_delta_ns(left, right);
                        if camera_latency_strict_pair_decision(pair_delta_ns)
                            == CameraLatencyStrictPairDecision::Accept
                        {
                            let next_left = local_provider
                                .pending_strict_left
                                .take()
                                .expect("left checked");
                            let next_right = local_provider
                                .pending_strict_right
                                .take()
                                .expect("right checked");
                            let import_started = Instant::now();
                            let next_left_image = local_provider.left_camera_import_cache.acquire(
                                &device,
                                &memory_properties,
                                &ahb_device,
                                &processing_graph.camera_resources,
                                processing_graph.format_key,
                                &next_left,
                                &mut local_provider.left_camera_import_stats,
                            );
                            let next_right_image =
                                local_provider.right_camera_import_cache.acquire(
                                    &device,
                                    &memory_properties,
                                    &ahb_device,
                                    &processing_graph.camera_resources,
                                    processing_graph.format_key,
                                    &next_right,
                                    &mut local_provider.right_camera_import_stats,
                                );
                            match (next_left_image, next_right_image) {
                                (Ok(left_image), Ok(right_image)) => {
                                    update_camera_hwb_probe_descriptor_set(
                                        &device,
                                        &processing_graph.camera_resources,
                                        processing_graph.descriptor_set,
                                        left_image.image_view,
                                        Some(right_image.image_view),
                                        mode,
                                    );
                                    log_fence_held_frame_retirement(
                                        &local_provider.current_left_frame,
                                        "left-strict-pair",
                                    );
                                    log_fence_held_frame_retirement(
                                        &local_provider.current_right_frame,
                                        "right-strict-pair",
                                    );
                                    let previous_left_image = std::mem::replace(
                                        &mut local_provider.sampled_left_image,
                                        left_image,
                                    );
                                    let previous_right_image = local_provider
                                        .sampled_right_image
                                        .replace(right_image)
                                        .expect("raw projection has right sampled image");
                                    local_provider.left_camera_import_cache.retire(
                                        &device,
                                        processing_graph.format_key,
                                        &local_provider.current_left_frame,
                                        previous_left_image,
                                        &mut local_provider.left_camera_import_stats,
                                    )?;
                                    local_provider.right_camera_import_cache.retire(
                                        &device,
                                        processing_graph.format_key,
                                        &local_provider.current_right_frame,
                                        previous_right_image,
                                        &mut local_provider.right_camera_import_stats,
                                    )?;
                                    local_provider.current_left_frame = next_left;
                                    local_provider.current_right_frame = next_right;
                                    local_provider.transition_left_camera_image = true;
                                    local_provider.transition_right_camera_image = true;
                                    left_imported = true;
                                    right_imported = true;
                                    local_provider.strict_pair_generation =
                                        local_provider.strict_pair_generation.saturating_add(1);
                                }
                                (Ok(left_image), Err(error)) => {
                                    left_image.destroy(&device);
                                    log_camera_frame_import_skipped(
                                        &next_right,
                                        "right-strict-pair",
                                        mode,
                                        &error,
                                    );
                                }
                                (Err(error), Ok(right_image)) => {
                                    right_image.destroy(&device);
                                    log_camera_frame_import_skipped(
                                        &next_left,
                                        "left-strict-pair",
                                        mode,
                                        &error,
                                    );
                                }
                                (Err(left_error), Err(right_error)) => {
                                    log_camera_frame_import_skipped(
                                        &next_left,
                                        "left-strict-pair",
                                        mode,
                                        &left_error,
                                    );
                                    log_camera_frame_import_skipped(
                                        &next_right,
                                        "right-strict-pair",
                                        mode,
                                        &right_error,
                                    );
                                }
                            }
                            frame_timing.camera_import += import_started.elapsed();
                        } else {
                            local_provider.strict_pair_rejections =
                                local_provider.strict_pair_rejections.saturating_add(1);
                            local_provider.pending_strict_left = None;
                            local_provider.pending_strict_right = None;
                            if local_provider.strict_pair_rejections <= 4
                                || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled()
                            {
                                log_marker(format!(
                                    "status=strict-stereo-pair-rejected pairDeltaMs={:.3} maxPairDeltaMs={:.3} rejectedPairs={} policy=strict-timestamp-pair recoveryPolicy=discard-both-latest-candidates recoveryReason=prevent-one-source-period-chase runtimeCrash=false",
                                    pair_delta_ns as f64 / 1_000_000.0,
                                    CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS as f64 / 1_000_000.0,
                                    local_provider.strict_pair_rejections,
                                ));
                            }
                        }
                    }
                    if local_provider
                        .observed_latency_settings
                        .should_discard_unpaired_strict_latest_candidate()
                        && local_provider.pending_strict_left.is_some()
                            != local_provider.pending_strict_right.is_some()
                    {
                        local_provider.strict_unpaired_resets =
                            local_provider.strict_unpaired_resets.saturating_add(1);
                        local_provider.pending_strict_left = None;
                        local_provider.pending_strict_right = None;
                        if local_provider.strict_unpaired_resets <= 4
                            || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled()
                        {
                            log_marker(format!(
                                "status=strict-stereo-pair-unpaired-reset resets={} policy=strict-timestamp-pair adoptionCadence=display-aligned-45 recoveryPolicy=discard-unpaired-latest-candidate recoveryReason=next-45hz-poll-would-overshoot-missing-eye runtimeCrash=false",
                                local_provider.strict_unpaired_resets,
                            ));
                        }
                    }
                }
            }
        }
        if local_provider.freeze_frame_pending
            && local_provider.current_left_frame.has_fence_held_image()
            && local_provider.current_right_frame.has_fence_held_image()
        {
            local_provider.freeze_frame_pending = false;
            local_provider.freeze_frame_latched = true;
            log_marker(format!(
                "status=camera-freeze-latched cameraLatencyRevision={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} cameraSyncActive=hold-image-until-gpu-fence latchFenceWaitComplete=true callbacksContinue=true importsPaused=true runtimeCrash=false",
                local_provider.observed_latency_settings.revision,
                local_provider.current_left_frame.frame_index,
                local_provider.current_right_frame.frame_index,
                local_provider.current_left_frame.descriptor.hardware_buffer_id,
                local_provider.current_right_frame.descriptor.hardware_buffer_id,
            ));
        }
        let acquire_started = Instant::now();
        let image_index = match swapchain_loader.acquire_next_image(
            swapchain,
            u64::MAX,
            image_available,
            vk::Fence::null(),
        ) {
            Ok((image_index, _suboptimal)) => image_index,
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
            Err(error) => return Err(format!("acquire-next-image-{error:?}")),
        };
        frame_timing.acquire_swapchain = acquire_started.elapsed();
        let command_buffer = command_buffers[image_index as usize];
        let public_stack_elapsed_seconds = render_started.elapsed().as_secs_f32();
        let latest_video_frame = if video_settings.active() {
            latest_spatial_video_projection_frame()
        } else {
            None
        };
        let record_started = Instant::now();
        let camera_reprojection = current_camera_latency_stereo_reprojection(
            local_provider.current_left_frame.capture_viewer_basis,
            local_provider.current_right_frame.capture_viewer_basis,
        );
        let projection_zone_settings = current_projection_zone_compositor_settings();
        projection_readback.observe_control(projection_zone_settings);
        let projection_guard_band = {
            let camera_reprojection_guard_band = &mut local_provider.camera_reprojection_guard_band;
            camera_reprojection_guard_band.update_for_projection_buffer(
                local_provider.observed_latency_settings,
                projection_zone_settings.buffer_geometry_mode,
                projection_zone_settings.buffer_static_width_uv,
                projection_zone_settings.buffer_minimum_width_uv,
                projection_zone_settings.buffer_maximum_width_uv,
                projection_zone_settings.buffer_maximum_speed_meters_per_second,
                camera_reprojection,
                boottime_now_ns(),
            )
        };
        let presentation_pose = camera_reprojection.presentation;
        let record_result = record_camera_hwb_probe_command_buffer(
            &device,
            command_buffer,
            render_pass,
            framebuffers[image_index as usize],
            extent,
            &processing_graph.camera_resources,
            processing_graph.descriptor_set,
            local_provider.sampled_left_image.image,
            local_provider
                .sampled_right_image
                .as_ref()
                .map(|image| image.image),
            local_provider.transition_left_camera_image,
            local_provider.transition_right_camera_image,
            None,
            processing_graph.public_guide_targets.as_mut(),
            None,
            public_stack_elapsed_seconds,
            video_renderer.as_mut(),
            latest_video_frame.as_ref(),
            &video_settings,
            &mut gpu_timestamps,
            image_index as usize,
            u64::from(frames_presented) + 1,
            camera_reprojection,
            projection_guard_band,
            local_provider.observed_latency_settings,
            processing_graph.camera_replay_capture.as_mut(),
            boottime_now_ns().max(0) as u64,
            CameraReplayFrameMetadata {
                left_camera_id: local_provider.current_left_frame.camera_id.clone(),
                right_camera_id: local_provider.current_right_frame.camera_id.clone(),
                left_frame_index: local_provider.current_left_frame.frame_index,
                right_frame_index: local_provider.current_right_frame.frame_index,
                left_timestamp_ns: local_provider.current_left_frame.timestamp_ns,
                right_timestamp_ns: local_provider.current_right_frame.timestamp_ns,
                pair_delta_ns: local_provider
                    .current_left_frame
                    .timestamp_ns
                    .abs_diff(local_provider.current_right_frame.timestamp_ns),
            },
            composite_alpha,
            images[image_index as usize],
            surface_generation,
            &mut projection_readback,
        )?;
        frame_timing.record = record_started.elapsed();
        let projected_by_public_stack = record_result.projected_by_public_stack;
        if last_projection_zone_render_stats != Some(record_result.projection_zone_stats) {
            log_marker(format!(
                "status=projection-composition-options-recorded {} runtimeCrash=false",
                record_result
                    .projection_zone_stats
                    .composition_marker_fields(),
            ));
            log_marker(format!(
                "status=projection-zone-render-effective {} runtimeCrash=false",
                record_result
                    .projection_zone_stats
                    .readiness_marker_fields(),
            ));
            log_marker(format!(
                "status=projection-zone-configuration-effective {} runtimeCrash=false",
                record_result
                    .projection_zone_stats
                    .applied_configuration_marker_fields(),
            ));
            log_marker(format!(
                "status=projection-zone-raw-parity-effective {} runtimeCrash=false",
                record_result
                    .projection_zone_stats
                    .raw_parity_marker_fields(),
            ));
            last_projection_zone_render_stats = Some(record_result.projection_zone_stats);
        }
        local_provider.transition_left_camera_image = false;
        local_provider.transition_right_camera_image = false;
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let wait_semaphores = [image_available];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let signal_semaphores = [render_finished];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let submit_command_buffers = [command_buffer];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let submit_info = [vk::SubmitInfo::default()
            .wait_semaphores(&wait_semaphores)
            .wait_dst_stage_mask(&wait_stages)
            .command_buffers(&submit_command_buffers)
            .signal_semaphores(&signal_semaphores)];
        let submit_started = Instant::now();
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.mark_stereo_submission_entered()?;}
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        if let Err(error) = device.queue_submit(queue, &submit_info, frame_fence) {
            projection_readback.cancel_unsubmitted("queue-submit");
            return Err(format!("queue-submit-{error:?}"));
        }
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        {
            let request_id = (surface_generation << 32) | u64::from(frames_presented + 1);
            let lease_id = current_depth_lease
                .map(|lease| lease.snapshot.lease_id)
                .unwrap_or(0);
            let enqueue_status = crate::spatial_sdk_depth_handoff::enqueue_spatial_submit_present(
                sdk_binding,
                request_id,
                lease_id,
                surface_generation,
                spatial_video_media_source_generation(),
                command_buffer.as_raw(),
                image_available.as_raw(),
                render_finished.as_raw(),
                frame_fence.as_raw(),
                swapchain.as_raw(),
                vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT.as_raw(),
                image_index,
            );
            if enqueue_status != 3 && enqueue_status != 0 {
                projection_readback.cancel_unsubmitted("spatial-sdk-queue-broker-enqueue");
                if let Some(lease) = current_depth_lease {
                    let _ =
                        crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease(lease);
                }
                return Err(format!("spatial-sdk-queue-broker-enqueue-{enqueue_status}"));
            }
            submitted_depth_lease = current_depth_lease;
            submitted_retirement = Some(
                crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementState::new(request_id),
            );
            submitted_video_qualification = Some((
                record_result.video_stats.clone(),
                u64::from(frames_presented) + 1,
            ));
            submitted_broker_failure_observed_at = None;
        }
        last_submitted_frame_slot = Some(image_index as usize);
        frame_timing.submit = submit_started.elapsed();
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let swapchains = [swapchain];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let image_indices = [image_index];
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let present_info = vk::PresentInfoKHR::default()
            .wait_semaphores(&signal_semaphores)
            .swapchains(&swapchains)
            .image_indices(&image_indices);
        let present_started = Instant::now();
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        match swapchain_loader.queue_present(queue, &present_info) {
            Ok(_suboptimal) => {}
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
            Err(error) => return Err(format!("queue-present-{error:?}")),
        }
        frame_timing.present_call = present_started.elapsed();
        frames_presented = frames_presented.saturating_add(1);
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        record_presented_frame(
            record_result.video_stats.ready,
            record_result.video_stats.rendered,
            record_result.video_stats.frame_index,
            record_result.video_stats.timestamp_ns,
            u64::from(frames_presented),
        );
        frame_timing.loop_total = loop_started.elapsed();
        if frames_presented <= 4
            || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled()
        {
            let presentation_sequence = presentation_pose
                .basis
                .map(|basis| basis.sequence)
                .unwrap_or(0);
            let left_capture_sequence = local_provider
                .current_left_frame
                .capture_viewer_basis
                .map(|basis| basis.sequence)
                .unwrap_or(0);
            let right_capture_sequence = local_provider
                .current_right_frame
                .capture_viewer_basis
                .map(|basis| basis.sequence)
                .unwrap_or(0);
            log_marker(format!(
                "status=camera-presentation-pose presentOrdinal={} presentationPoseSource={} presentationPoseFallback={} presentationTargetTimestampNs={} presentationRequestedLeadMs={} presentationEffectiveLeadMs={:.3} latestScenePoseAgeMs={:.3} presentationPoseSequence={} leftCapturePoseSequence={} rightCapturePoseSequence={} leftCaptureToPresentationDeltaMs={:.3} rightCaptureToPresentationDeltaMs={:.3} leftReprojectionApplied={} rightReprojectionApplied={} projectionFootprintPolicy={} sourceOverscanPolicy=central-crop-real-camera-pixels sourceOverscanMode={} sourceOverscanUv={:.3} projectionFootprintScale={:.3} cameraAngularScalePolicy={} {} sourceCoverageExhaustionPolicy=discard-to-underlying-carrier outOfRangeUvPolicy=discard cameraCalibrationScope=independent-per-eye perEyeDraws=true displayFrameLoopAuthority=spatial-sdk sidecarXrWaitFrame=false sidecarXrBeginFrame=false sidecarXrEndFrame=false queuePresentTimeAuthority=cpu-call-return-not-photons runtimeCrash=false",
                frames_presented,
                presentation_pose.source,
                presentation_pose.fallback,
                presentation_pose.target_timestamp_ns,
                presentation_pose.requested_lead_ms,
                presentation_pose.effective_lead_ms,
                presentation_pose.latest_sample_age_ms,
                presentation_sequence,
                left_capture_sequence,
                right_capture_sequence,
                camera_reprojection.left.capture_to_presentation_delta_ms(),
                camera_reprojection.right.capture_to_presentation_delta_ms(),
                bool_token(camera_reprojection.left.applied()),
                bool_token(camera_reprojection.right.applied()),
                if projection_guard_band.footprint_scale < 1.0 {
                    "reduced-target-rect-with-full-surface-scissor"
                } else {
                    "fixed-target-rect-with-full-surface-scissor"
                },
                local_provider.observed_latency_settings
                    .reprojection_guard_band_mode
                    .marker_token(),
                projection_guard_band.source_overscan_uv,
                projection_guard_band.footprint_scale,
                if projection_guard_band.footprint_scale < 1.0 {
                    "preserve-original-source-to-target-scale"
                } else {
                    "zoom-to-fill-or-no-margin"
                },
                projection_guard_band.marker_fields(),
            ));
        }
        if local_provider.observed_latency_settings.stereo_policy
            == CameraLatencyStereoPolicy::StrictTimestampPair
            && left_imported
            && right_imported
            && (local_provider.strict_pair_generation <= 4
                || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled())
        {
            log_marker(format!(
                "status=strict-stereo-pair-presented pairGeneration={} presentOrdinal={} leftFrameIndex={} rightFrameIndex={} leftHwbImportSequence={} rightHwbImportSequence={} leftTimestampNs={} rightTimestampNs={} pairDeltaNs={} maxPairDeltaNs={} bothDescriptorBindingsUpdatedBeforeRecord=true bothCameraImagesTransitionedTogether=true packedEyesRecordedInSingleCommandBuffer=true singleQueuePresent=true runtimeCrash=false",
                local_provider.strict_pair_generation,
                frames_presented,
                local_provider.current_left_frame.frame_index,
                local_provider.current_right_frame.frame_index,
                local_provider.current_left_frame.hwb_import_sequence,
                local_provider.current_right_frame.hwb_import_sequence,
                local_provider.current_left_frame.timestamp_ns,
                local_provider.current_right_frame.timestamp_ns,
                local_provider.current_left_frame.timestamp_ns.abs_diff(local_provider.current_right_frame.timestamp_ns),
                CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS,
            ));
        }
        let (left_published_frames, right_published_frames) =
            local_provider.camera_runtime.published_frame_counts();
        local_provider.latency_window.record(
            frame_timing,
            left_imported,
            right_imported,
            local_provider.current_left_frame.frame_index,
            local_provider.current_right_frame.frame_index,
            left_published_frames,
            right_published_frames,
            local_provider.current_left_frame.source_delta_ns,
            local_provider.current_right_frame.source_delta_ns,
            local_provider.current_left_frame.callback_delta_ns,
            local_provider.current_right_frame.callback_delta_ns,
            record_result.camera_projection_visible,
        );
        if local_provider
            .latency_window
            .should_emit(local_provider.observed_latency_settings)
        {
            let present_call_boottime_ns = boottime_now_ns();
            local_provider.latency_window.emit_and_reset(
                local_provider.observed_latency_settings,
                present_mode,
                images.len() as u32,
                active_latency_launch_settings,
                local_provider
                    .current_left_frame
                    .timestamp_source
                    .marker_token(),
                local_provider
                    .current_right_frame
                    .timestamp_source
                    .marker_token(),
                local_provider.current_left_frame.callback_age_ns,
                local_provider.current_right_frame.callback_age_ns,
                local_provider
                    .current_left_frame
                    .sensor_age_at_boottime(present_call_boottime_ns),
                local_provider
                    .current_right_frame
                    .sensor_age_at_boottime(present_call_boottime_ns),
                local_provider
                    .current_left_frame
                    .timestamp_ns
                    .abs_diff(local_provider.current_right_frame.timestamp_ns),
            );
        }
        if mode.should_stream_latest_frame() && !public_multistack_depth_evidence_marker_logged {
            if let Some(depth_evidence) = processing_graph
                .public_guide_targets
                .as_ref()
                .and_then(|targets| targets.compact_depth_evidence_marker_fields())
            {
                log_marker(format!(
                    "status=public-multistack-depth-evidence framesPresented={} outputMode=raw-color-target-rect stereoSource=camera50-51 monoDuplicated=false {} runtimeCrash=false",
                    frames_presented,
                    depth_evidence,
                ));
                if let Some(alignment_evidence) = processing_graph
                    .public_guide_targets
                    .as_ref()
                    .and_then(|targets| targets.compact_depth_alignment_evidence_marker_fields())
                {
                    log_marker(format!(
                        "status=public-multistack-depth-alignment-evidence framesPresented={} {} runtimeCrash=false",
                        frames_presented,
                        alignment_evidence,
                    ));
                }
                if let Some(source_evidence) = processing_graph
                    .public_guide_targets
                    .as_ref()
                    .and_then(|targets| targets.compact_depth_source_evidence_marker_fields())
                {
                    log_marker(format!(
                        "status=public-multistack-depth-source-evidence framesPresented={} {} runtimeCrash=false",
                        frames_presented,
                        source_evidence,
                    ));
                }
                public_multistack_depth_evidence_marker_logged = true;
            }
        }
        if mode.should_stream_latest_frame() && frames_presented <= 4 {
            let public_stack_frame_marker = processing_graph
                .public_guide_targets
                .as_ref()
                .map(|targets| {
                    targets.frame_marker_fields(
                        projected_by_public_stack,
                        public_stack_elapsed_seconds,
                        projection_guard_band.footprint_scale,
                    )
                })
                .unwrap_or_else(|| public_guide_targets_pending_marker_fields("not allocated"));
            log_marker(format!(
                "status=public-multistack-frame-projected framesPresented={} outputMode=raw-color-target-rect stereoSource=camera50-51 monoDuplicated=false {} runtimeCrash=false",
                frames_presented,
                public_stack_frame_marker,
            ));
            if let Some(targets) = processing_graph.public_guide_targets.as_ref() {
                log_marker(format!(
                    "status=public-multistack-projection-evidence framesPresented={} outputMode=raw-color-target-rect stereoSource=camera50-51 monoDuplicated=false {} runtimeCrash=false",
                    frames_presented,
                    targets.compact_projection_evidence_marker_fields(
                        projected_by_public_stack,
                        public_stack_elapsed_seconds,
                        projection_guard_band.footprint_scale,
                        f32::from_bits(
                            record_result
                                .projection_zone_stats
                                .guide_layer_override_bits,
                        ),
                    ),
                ));
            }
        }
        let should_log_video_projection_frame = mode.should_stream_latest_frame()
            && video_settings.enabled
            && (frames_presented <= 4
                || (!spatial_video_projection_rendered_marker_logged
                    && record_result.video_stats.rendered));
        if should_log_video_projection_frame {
            log_marker(format!(
                "status=spatial-video-projection-frame-composed framesPresented={} outputMode=raw-color-target-rect stereoSource=camera50-51 videoComposedBeforeCamera=true sameSurfaceComposition=true cameraProjectionAlignmentPreserved=true videoProjectionRendered={} spatialVideoProjectionRendered={} videoProjectionGpuImportReady={} {} {} runtimeCrash=false",
                frames_presented,
                record_result.video_stats.rendered,
                record_result.video_stats.rendered,
                record_result.video_stats.ready,
                video_settings.marker_fields(),
                record_result.video_stats.marker_fields(),
            ));
            if record_result.video_stats.rendered {
                spatial_video_projection_rendered_marker_logged = true;
            }
        }
        if mode.should_stream_latest_frame()
            && record_result.video_stats.rendered
            && (frames_presented <= 4 || frames_presented % 300 == 0)
        {
            log_marker(format!(
                "status=spatial-video-import-performance-sample framesPresented={} videoProjectionFrameIndex={} videoProjectionImportCacheHits={} videoProjectionImportCacheMisses={} videoProjectionImportCacheEntries={} videoProjectionImportPropertyQueryCalls={} videoProjectionImportPropertyQueryTotalNs={} videoProjectionImportPropertyQueryMaxNs={} videoProjectionCacheHitsBeforePropertyQuery={} videoProjectionImportQueryPolicy=cache-hit-before-property-query videoProjectionImportTelemetryAllocation=scalar videoProjectionImportTelemetryPerFrameLog=false runtimeCrash=false",
                frames_presented,
                record_result.video_stats.frame_index,
                record_result.video_stats.import_cache_hits,
                record_result.video_stats.import_cache_misses,
                record_result.video_stats.import_cache_entries,
                record_result.video_stats.import_property_query_calls,
                record_result.video_stats.import_property_query_total_ns,
                record_result.video_stats.import_property_query_max_ns,
                record_result.video_stats.cache_hits_before_property_query,
            ));
        }
        if frames_presented == 1 {
            log_marker(format!(
                "status=first-camera-frame-presented leftCameraId={} rightCameraId={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} leftHwbImportSequence={} rightHwbImportSequence={} pairDeltaNs={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi vkGetAhbPropertiesResult=success sampledCameraTexture=true sampledLeftCameraTexture=true sampledRightCameraTexture={} samplerMode={} outputMode={} rawCameraProjectionProbe={} privateShaderStack=false customProjectionStack=false leftTimestampNs={} rightTimestampNs={} leftWidth={} leftHeight={} rightWidth={} rightHeight={} leftFormat={} rightFormat={} leftUsage=0x{:x} rightUsage=0x{:x} leftStride={} rightStride={} noRepeatedRawHwbSampling={} stereoSource={} runtimeCrash=false {}",
                marker_token(&local_provider.current_left_frame.camera_id),
                marker_token(&local_provider.current_right_frame.camera_id),
                local_provider.current_left_frame.frame_index,
                local_provider.current_right_frame.frame_index,
                local_provider.current_left_frame.descriptor.hardware_buffer_id,
                local_provider.current_right_frame.descriptor.hardware_buffer_id,
                local_provider.current_left_frame.hwb_import_sequence,
                local_provider.current_right_frame.hwb_import_sequence,
                local_provider.current_left_frame.timestamp_ns.abs_diff(local_provider.current_right_frame.timestamp_ns),
                bool_token(matches!(mode, CameraHwbProbeMode::RawColorProjection)),
                processing_graph.sampler_mode,
                mode.output_mode(),
                mode.raw_projection_token(),
                local_provider.current_left_frame.timestamp_ns,
                local_provider.current_right_frame.timestamp_ns,
                local_provider.current_left_frame.descriptor.width,
                local_provider.current_left_frame.descriptor.height,
                local_provider.current_right_frame.descriptor.width,
                local_provider.current_right_frame.descriptor.height,
                local_provider.current_left_frame.descriptor.format,
                local_provider.current_right_frame.descriptor.format,
                local_provider.current_left_frame.descriptor.usage,
                local_provider.current_right_frame.descriptor.usage,
                local_provider.current_left_frame.descriptor.stride,
                local_provider.current_right_frame.descriptor.stride,
                bool_token(!mode.should_stream_latest_frame()),
                mode.stereo_source(),
                mode.projection_contract_marker_fields(),
            ));
        } else if mode.should_stream_latest_frame() && frames_presented <= 4 {
            log_marker(format!(
                "status=raw-camera-frame-presented framesPresented={} leftCameraId={} rightCameraId={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} leftHwbImportSequence={} rightHwbImportSequence={} pairDeltaNs={} sampledCameraTexture=true sampledLeftCameraTexture=true sampledRightCameraTexture=true outputMode=raw-color-target-rect stereoSource=camera50-51 monoDuplicated=false {} runtimeCrash=false",
                frames_presented,
                marker_token(&local_provider.current_left_frame.camera_id),
                marker_token(&local_provider.current_right_frame.camera_id),
                local_provider.current_left_frame.frame_index,
                local_provider.current_right_frame.frame_index,
                local_provider.current_left_frame.descriptor.hardware_buffer_id,
                local_provider.current_right_frame.descriptor.hardware_buffer_id,
                local_provider.current_left_frame.hwb_import_sequence,
                local_provider.current_right_frame.hwb_import_sequence,
                local_provider.current_left_frame.timestamp_ns.abs_diff(local_provider.current_right_frame.timestamp_ns),
                mode.public_multistack_marker_fields(),
            ));
        }
    }

    device
        .device_wait_idle()
        .map_err(|error| format!("device-wait-idle-{error:?}"))?;
    if let Some(retired_frame_slot) = last_submitted_frame_slot.take() {
        if let Some(sample) = gpu_timestamps.read_retired_slot(&device, retired_frame_slot) {
            log_marker(format!(
                "status=gpu-timestamp-final-sample {} runtimeCrash=false",
                sample.marker_fields(),
            ));
        }
    }
    log_marker(format!(
        "status=gpu-timestamp-summary {} runtimeCrash=false",
        gpu_timestamps.summary_marker_fields(),
    ));
    if let Some(targets) = processing_graph.public_guide_targets.as_ref() {
        log_marker(format!(
            "status=cpu-import-summary {} runtimeCrash=false",
            targets.uniform_upload_summary_marker_fields(),
        ));
    }
    log_marker(format!(
        "status=camera-import-performance-summary {} policy=bounded-generation-aware-ahb-vulkan-import-cache runtimeCrash=false",
        local_provider.left_camera_import_stats.marker_fields("left"),
    ));
    log_marker(format!(
        "status=camera-import-performance-summary {} policy=bounded-generation-aware-ahb-vulkan-import-cache runtimeCrash=false",
        local_provider.right_camera_import_stats.marker_fields("right"),
    ));
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    if let Some(completed_lease) = submitted_depth_lease.take() {
        let _ =
            crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease(completed_lease);
    }
    if let Some(mut capture) = processing_graph.camera_replay_capture {
        capture.retire_completed(&device)?;
        capture.finish(if capture.is_complete() {
            "requested-frame-count-reached"
        } else {
            "camera-projection-stopped"
        })?;
        capture.destroy(&device);
    }
    if projection_readback.has_in_flight() {
        // A completed command buffer can leave the loop through a normal
        // surface break before the next frame starts. This teardown-only wait
        // is never part of the render cadence and prevents freeing staging
        // memory while the selected-image copy is still executing.
        device
            .wait_for_fences(&[frame_fence], true, u64::MAX)
            .map_err(|error| format!("readback-teardown-wait-fence-{error:?}"))?;
        projection_readback.retire_after_fence(&device);
    }
    projection_readback.destroy(&device);
    if let Some(sampled_right_image) = local_provider.sampled_right_image {
        sampled_right_image.destroy(&device);
    }
    local_provider.sampled_left_image.destroy(&device);
    local_provider.right_camera_import_cache.destroy(&device);
    local_provider.left_camera_import_cache.destroy(&device);
    // Join pending compositor builds before destroying their borrowed camera
    // descriptor layout. Video layout ownership is also pinned by each build.
    if let Some(public_guide_targets) = processing_graph.public_guide_targets {
        public_guide_targets.destroy(&device);
    }
    if let Some(mut video_renderer) = video_renderer {
        video_renderer.destroy(&device);
    }
    processing_graph.camera_resources.destroy(&device);
    gpu_timestamps.destroy(&device);
    device.destroy_fence(frame_fence, None);
    device.destroy_semaphore(render_finished, None);
    device.destroy_semaphore(image_available, None);
    device.destroy_command_pool(command_pool, None);
    for framebuffer in framebuffers {
        device.destroy_framebuffer(framebuffer, None);
    }
    device.destroy_render_pass(render_pass, None);
    for image_view in image_views {
        device.destroy_image_view(image_view, None);
    }
    swapchain_loader.destroy_swapchain(swapchain, None);
    drop(local_provider.camera_runtime);
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
        sdk_binding.session_generation,
    );
    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
    device.destroy_device(None);
    surface_loader.destroy_surface(surface, None);
    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
    instance.destroy_instance(None);

    Ok(CameraHwbProbeStats {
        frames_presented,
        extent,
        left_camera_id: local_provider.current_left_frame.camera_id,
        right_camera_id: local_provider.current_right_frame.camera_id,
        left_frame_index: local_provider.current_left_frame.frame_index,
        right_frame_index: local_provider.current_right_frame.frame_index,
        left_hardware_buffer_id: local_provider
            .current_left_frame
            .descriptor
            .hardware_buffer_id,
        right_hardware_buffer_id: local_provider
            .current_right_frame
            .descriptor
            .hardware_buffer_id,
        left_hwb_import_sequence: local_provider.current_left_frame.hwb_import_sequence,
        right_hwb_import_sequence: local_provider.current_right_frame.hwb_import_sequence,
        pair_delta_ns: local_provider
            .current_left_frame
            .timestamp_ns
            .abs_diff(local_provider.current_right_frame.timestamp_ns),
        sampler_mode: processing_graph.sampler_mode,
    })
}

unsafe fn render_peer_common_graph(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    max_frames: u32,
    route_generation: i64,
    cancellation: Arc<AtomicBool>,
    ready_tx: std::sync::mpsc::SyncSender<Result<(), String>>,
) -> Result<(), String> {
    if cancellation.load(Ordering::Acquire) {
        return Err("peer-session-cancelled-before-wsi".to_string());
    }
    let mut owned = PeerCommonGraphResources::new(CameraHwbWsiParts::create(
        window,
        requested_width,
        requested_height,
    )?);
    let run_result = (|| -> Result<(), String> {
        if cancellation.load(Ordering::Acquire) {
            return Err("peer-session-cancelled-after-wsi".to_string());
        }

        let deadline = Instant::now() + Duration::from_millis(CAMERA_HWB_PROBE_WAIT_FRAME_MS);
        let first_frame = loop {
            if cancellation.load(Ordering::Acquire) {
                return Err("peer-session-cancelled-during-first-frame".to_string());
            }
            if let Some(frame) = latest_projection_peer_frame().filter(|frame| {
                frame.route_generation == route_generation as u64
                    && frame.decoder_token > 0
                    && frame.reader_generation > 0
                    && frame.packed_pair.is_some()
            }) {
                break frame;
            }
            if Instant::now() >= deadline {
                return Err("peer-first-packed-frame-timeout".to_string());
            }
            thread::yield_now();
        };
        validate_peer_packed_frame(&first_frame, route_generation)?;
        if !projection_peer_binding_matches(
            first_frame.route_generation,
            first_frame.decoder_token,
            first_frame.reader_generation,
        ) {
            return Err("peer-exact-binding-lost-before-attach".to_string());
        }

        let wsi = owned.wsi.as_ref().expect("peer WSI initialized");
        let (source_import_properties, source_format_props) =
            query_ahb_vulkan_import_properties(&wsi.ahb_device, &first_frame.hardware_buffer)?;
        owned.normalizer = Some(PackedSbsNormalizer::create(
            &wsi.device,
            &wsi.memory_properties,
            first_frame.descriptor.width,
            first_frame.descriptor.height,
            source_import_properties.format_key,
            &source_format_props,
        )?);
        let normalizer = owned.normalizer.as_ref().expect("normalizer initialized");
        owned.sampled_packed_image = Some(import_ahb_sampled_image(
            &wsi.device,
            &wsi.memory_properties,
            &first_frame.hardware_buffer,
            AhbVulkanSampledImageCreateInfo {
                width: first_frame.descriptor.width,
                height: first_frame.descriptor.height,
                format_key: source_import_properties.format_key,
                allocation_size: source_import_properties.allocation_size,
                memory_type_bits: source_import_properties.memory_type_bits,
                sampler_ycbcr_conversion: normalizer.source_sampler_ycbcr_conversion(),
                debug_label: "peer-packed-sbs-source",
            },
        )?);
        owned
            .normalizer
            .as_mut()
            .expect("normalizer initialized")
            .update_source(
                &wsi.device,
                owned
                    .sampled_packed_image
                    .as_ref()
                    .expect("packed image initialized")
                    .image_view,
            );

        let normalized_format_properties = wsi
            .instance
            .get_physical_device_format_properties(wsi.physical_device, NORMALIZED_EYE_FORMAT);
        if !normalized_format_properties
            .optimal_tiling_features
            .contains(
                vk::FormatFeatureFlags::COLOR_ATTACHMENT | vk::FormatFeatureFlags::SAMPLED_IMAGE,
            )
        {
            return Err("peer-normalized-eye-format-unsupported".to_string());
        }
        let mut normalized_ahb_format_props =
            vk::AndroidHardwareBufferFormatPropertiesANDROID::default();
        normalized_ahb_format_props.format = NORMALIZED_EYE_FORMAT;
        normalized_ahb_format_props.format_features =
            normalized_format_properties.optimal_tiling_features;
        let normalized_format_key = AhbVulkanFormatKey {
            format: NORMALIZED_EYE_FORMAT,
            external_format: 0,
        };
        owned.pending_camera_resources = Some(create_camera_hwb_probe_resources(
            &wsi.device,
            wsi.render_pass,
            normalized_format_key,
            &normalized_ahb_format_props,
            CameraHwbProbeMode::RawColorProjection,
        )?);
        owned.pending_public_guide_targets = Some(allocate_spatial_public_guide_targets(
            &wsi.device,
            &wsi.memory_properties,
            owned
                .pending_camera_resources
                .as_ref()
                .expect("camera resources initialized")
                .descriptor_set_layout,
            wsi.render_pass,
        )?);
        let normalized_views = owned
            .normalizer
            .as_ref()
            .expect("normalizer initialized")
            .image_views();
        let descriptor_set = allocate_camera_hwb_probe_descriptor_set(
            &wsi.device,
            owned
                .pending_camera_resources
                .as_ref()
                .expect("camera resources initialized"),
            normalized_views[0],
            Some(normalized_views[1]),
            CameraHwbProbeMode::RawColorProjection,
        )?;
        owned.processing_graph = Some(CameraProcessingGraph {
            camera_resources: owned
                .pending_camera_resources
                .take()
                .expect("camera resources initialized"),
            descriptor_set,
            public_guide_targets: Some(
                owned
                    .pending_public_guide_targets
                    .take()
                    .expect("public guide targets initialized"),
            ),
            camera_replay_capture: None,
            sampler_mode: "normalized-rgba-full-eye",
            format_key: normalized_format_key,
            format_props: normalized_ahb_format_props,
        });
        owned.projection_readback = Some(ProjectionReadback::new(
            wsi.surface_format,
            wsi.composite_alpha,
            wsi.extent,
            wsi.capabilities.supported_usage_flags,
            wsi.memory_properties,
        ));
        let surface_generation = NEXT_SDK_SURFACE_GENERATION.fetch_add(1, Ordering::AcqRel);
        let pending_route = crate::peer_projection_runtime::read_source(route_generation);
        if pending_route.words[1] != route_generation
            || pending_route.words[2] != crate::peer_projection_runtime::SOURCE_PEER
            || pending_route.words[11] != crate::peer_projection_runtime::RESULT_PENDING
            || u64::try_from(pending_route.words[3]).ok() != Some(first_frame.decoder_token)
            || u64::try_from(pending_route.words[4]).ok() != Some(first_frame.reader_generation)
            || !projection_peer_binding_matches(
                first_frame.route_generation,
                first_frame.decoder_token,
                first_frame.reader_generation,
            )
        {
            let _ = ready_tx.send(Err("peer-exact-binding-lost-before-attach".to_string()));
            return Err("peer-exact-binding-lost-before-attach".to_string());
        }
        let attached =
            crate::peer_projection_runtime::record_peer_common_graph_attached(route_generation);
        if attached.words[11] != crate::peer_projection_runtime::RESULT_PENDING {
            let _ = ready_tx.send(Err("peer-common-graph-attachment-rejected".to_string()));
            return Err("peer-common-graph-attachment-rejected".to_string());
        }
        log_marker(format!(
        "status=peer-common-graph-attached routeGeneration={} decoderToken={} readerGeneration={} packedHardwareBufferId={} packedExtent={}x{} normalizedEyeExtent={}x{} packedBindingCount=1 commonGraphEyeBindingCount=2 normalizedEyeImagesDistinct=true source=peer-packed-stereo producerPath=peer-decoder-AImageReader-AHardwareBuffer-Vulkan-normalize-common-graph camera2Opened=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi compositorVideoPath=absent-cold-peer-limitation independentRendererStarted=false {} runtimeCrash=false",
        route_generation,
        first_frame.decoder_token,
        first_frame.reader_generation,
        first_frame.descriptor.hardware_buffer_id,
        first_frame.descriptor.width,
        first_frame.descriptor.height,
        owned.normalizer.as_ref().expect("normalizer initialized").extent().width,
        owned.normalizer.as_ref().expect("normalizer initialized").extent().height,
        public_multistack_marker_fields(),
    ));
        let _ = ready_tx.send(Ok(()));

        owned.current_frame = Some(first_frame);
        let PeerCommonGraphResources {
            wsi: Some(wsi),
            normalizer: Some(normalizer),
            sampled_packed_image: Some(sampled_packed_image),
            processing_graph: Some(processing_graph),
            projection_readback: Some(projection_readback),
            current_frame: Some(current_frame),
            ..
        } = &mut owned
        else {
            return Err("peer-common-graph-resource-owner-incomplete".to_string());
        };
        let device = &wsi.device;
        let ahb_device = &wsi.ahb_device;
        let memory_properties = wsi.memory_properties;
        let render_pass = wsi.render_pass;
        let framebuffers = &wsi.framebuffers;
        let extent = wsi.extent;
        let composite_alpha = wsi.composite_alpha;
        let images = &wsi.images;
        let command_buffers = &wsi.command_buffers;
        let frame_fence = wsi.frame_fence;
        let image_available = wsi.image_available;
        let render_finished = wsi.render_finished;
        let swapchain = wsi.swapchain;
        let swapchain_loader = &wsi.swapchain_loader;
        let gpu_timestamps = &mut wsi.gpu_timestamps;
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let queue = wsi.queue;
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let sdk_binding = wsi.sdk_binding;
        let mut transition_packed_source = true;
        let mut frames_presented = 0_u32;
        let mut guard_band = CameraReprojectionGuardBandController::default();
        let render_started = Instant::now();
        let mut freshness = PeerFrameFreshness::new(0, 0, 0, 1_000);
        let mut stereo_source_imports = if crate::own_stereo_capture_runtime::capture_route_selected() {
            if !source_bank_build::SOURCE_BANK_SHADER_COMPILED || !wsi.foreign_queue_ownership_enabled {
                return Err("selected-source-bank-provider-or-foreign-owner-unavailable".into());
            }
            let targets=processing_graph.public_guide_targets.as_mut().ok_or("source-bank-guide-owner-unavailable")?;
            targets.activate_stereo_banks(device,&wsi.instance,wsi.physical_device,&memory_properties,
                processing_graph.camera_resources.descriptor_set_layout,2,
                include_bytes!(concat!(env!("OUT_DIR"),"/spatial_source_bank.frag.spv")),
                include_bytes!(concat!(env!("OUT_DIR"),"/spatial_source_bank.vert.spv")))?;
            Some(crate::spatial_stereo_source_import::StereoSourceImports::create(device,
                &processing_graph.camera_resources,Some(wsi.queue_family_index))?)
        } else {None};
        while max_frames == 0 || frames_presented < max_frames {
            if cancellation.load(Ordering::Acquire) {
                break;
            }
            let route = crate::peer_projection_runtime::read_source(route_generation);
            if route.words[1] != route_generation
                || route.words[2] != crate::peer_projection_runtime::SOURCE_PEER
                || u64::try_from(route.words[3]).ok() != Some(current_frame.decoder_token)
                || u64::try_from(route.words[4]).ok() != Some(current_frame.reader_generation)
                || !matches!(
                    route.words[11],
                    crate::peer_projection_runtime::RESULT_PENDING
                        | crate::peer_projection_runtime::RESULT_EFFECTIVE
                )
            {
                break;
            }
            if !projection_peer_binding_matches(
                current_frame.route_generation,
                current_frame.decoder_token,
                current_frame.reader_generation,
            ) {
                return Err("peer-exact-binding-lost".to_string());
            }
            let next_frame = if frames_presented == 0 {
                Some((*current_frame).clone())
            } else {
                loop {
                    if cancellation.load(Ordering::Acquire) {
                        break None;
                    }
                    let candidate = latest_projection_peer_frame();
                    let identity = candidate.as_ref().and_then(|frame| {
                        frame
                            .packed_pair
                            .as_ref()
                            .map(|pair| (pair.pair_id, frame.import_sequence))
                    });
                    match freshness.observe(identity, render_started.elapsed().as_millis() as u64) {
                        PeerFrameFreshnessDecision::Fresh => break candidate,
                        PeerFrameFreshnessDecision::Lost => {
                            return Err("peer-input-freshness-timeout".to_string());
                        }
                        PeerFrameFreshnessDecision::Wait => thread::yield_now(),
                    }
                }
            };
            let Some(next_frame) = next_frame else {
                break;
            };
            device
                .wait_for_fences(&[frame_fence], true, u64::MAX)
                .map_err(|error| format!("peer-wait-fence-{error:?}"))?;
            let next = next_frame;
            validate_peer_packed_frame(&next, route_generation)?;
            if next.decoder_token != current_frame.decoder_token
                || next.reader_generation != current_frame.reader_generation
            {
                return Err("peer-hot-decoder-or-reader-switch-unsupported".to_string());
            }
            if next.import_sequence > current_frame.import_sequence {
                let (properties, _) =
                    query_ahb_vulkan_import_properties(&ahb_device, &next.hardware_buffer)?;
                if properties.format_key != source_import_properties.format_key
                    || next.descriptor.width != current_frame.descriptor.width
                    || next.descriptor.height != current_frame.descriptor.height
                {
                    return Err("peer-packed-source-format-or-extent-changed".to_string());
                }
                let next_image = import_ahb_sampled_image(
                    &device,
                    &memory_properties,
                    &next.hardware_buffer,
                    AhbVulkanSampledImageCreateInfo {
                        width: next.descriptor.width,
                        height: next.descriptor.height,
                        format_key: properties.format_key,
                        allocation_size: properties.allocation_size,
                        memory_type_bits: properties.memory_type_bits,
                        sampler_ycbcr_conversion: normalizer.source_sampler_ycbcr_conversion(),
                        debug_label: "peer-packed-sbs-source",
                    },
                )?;
                normalizer.update_source(&device, next_image.image_view);
                let previous = std::mem::replace(sampled_packed_image, next_image);
                previous.destroy(&device);
                *current_frame = next;
                transition_packed_source = true;
            }
            if !projection_peer_binding_matches(
                current_frame.route_generation,
                current_frame.decoder_token,
                current_frame.reader_generation,
            ) {
                return Err("peer-exact-binding-lost-before-submit".to_string());
            }
            if let Some(targets)=processing_graph.public_guide_targets.as_mut() {
                targets.retire_stereo_banks_after_fence(&device)?;
            }
            if let Some(imports)=stereo_source_imports.as_mut() {
                imports.retire_after_fence(device)?;
                crate::spatial_stereo_source_import::synchronize_peer_source()?;
                imports.refresh(device,&memory_properties,ahb_device,&processing_graph.camera_resources,
                    CameraHwbProbeMode::RawColorProjection,1_000_000_000)?;
            }
            device
                .reset_fences(&[frame_fence])
                .map_err(|error| format!("peer-reset-fence-{error:?}"))?;
            let image_index = match swapchain_loader.acquire_next_image(
                swapchain,
                u64::MAX,
                image_available,
                vk::Fence::null(),
            ) {
                Ok((index, _)) => index,
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
                Err(error) => return Err(format!("peer-acquire-next-image-{error:?}")),
            };
            let command_buffer = command_buffers[image_index as usize];
            let latency_settings = current_camera_latency_settings();
            let camera_reprojection = current_camera_latency_stereo_reprojection(None, None);
            let zone_settings = current_projection_zone_compositor_settings();
            projection_readback.observe_control(zone_settings);
            let projection_guard_band = guard_band.update_for_projection_buffer(
                latency_settings,
                zone_settings.buffer_geometry_mode,
                zone_settings.buffer_static_width_uv,
                zone_settings.buffer_minimum_width_uv,
                zone_settings.buffer_maximum_width_uv,
                zone_settings.buffer_maximum_speed_meters_per_second,
                camera_reprojection,
                boottime_now_ns(),
            );
            let normalized_images = normalizer.images();
            let video_settings = spatial_video_projection_settings();
            let stereo_inputs=if let Some(imports)=stereo_source_imports.as_mut() {
                let (words,revision)=crate::spatial_public_multistack_runtime::read_control_policy();
                let policy=crate::stereo_bank_transport_v1::StereoBankPolicyUniformV1 {
                    region_origins:[words[0],words[1],words[2],words[3]],
                    guide_origins:[words[4],words[5],0,0],source_state:[0,0,1,0] };
                // Conservative neutral demand covers every selected guide stage;
                // private effect demand may later reduce this prefix explicitly.
                let mut prefixes=[0usize;2];
                for &origin in &words[..4]{prefixes[origin as usize]=6;}
                for &origin in &words[4..]{if origin<2{prefixes[origin as usize]=6;}}
                Some(imports.recording_inputs(policy,revision,prefixes,frame_fence,u64::from(frames_presented)+1,surface_generation)?)
            } else {None};
            let record_result = record_camera_hwb_probe_command_buffer(
                &device,
                command_buffer,
                render_pass,
                framebuffers[image_index as usize],
                extent,
                &processing_graph.camera_resources,
                stereo_inputs.as_ref().and_then(|inputs|inputs.sources.iter().flatten().find(|source|inputs.demanded_prefixes[source.key.origin]>0))
                    .map_or(processing_graph.descriptor_set,|source|source.descriptor_set),
                normalized_images[0],
                Some(normalized_images[1]),
                false,
                false,
                if stereo_inputs.is_some(){None}else{Some((normalizer, sampled_packed_image, transition_packed_source))},
                processing_graph.public_guide_targets.as_mut(),
                stereo_inputs,
                render_started.elapsed().as_secs_f32(),
                None,
                None,
                &video_settings,
                gpu_timestamps,
                image_index as usize,
                u64::from(frames_presented) + 1,
                camera_reprojection,
                projection_guard_band,
                latency_settings,
                processing_graph.camera_replay_capture.as_mut(),
                boottime_now_ns().max(0) as u64,
                CameraReplayFrameMetadata {
                    left_camera_id: "peer-left".to_string(),
                    right_camera_id: "peer-right".to_string(),
                    left_frame_index: current_frame.frame_index,
                    right_frame_index: current_frame.frame_index,
                    left_timestamp_ns: current_frame.timestamp_ns,
                    right_timestamp_ns: current_frame.timestamp_ns,
                    pair_delta_ns: current_frame
                        .packed_pair
                        .as_ref()
                        .map(|pair| pair.pair_delta_ns as u64)
                        .unwrap_or(0),
                },
                composite_alpha,
                images[image_index as usize],
                surface_generation,
                projection_readback,
            )?;
            transition_packed_source = false;

            #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
            {
                let waits = [image_available];
                let stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
                let signals = [render_finished];
                let buffers = [command_buffer];
                let submits = [vk::SubmitInfo::default()
                    .wait_semaphores(&waits)
                    .wait_dst_stage_mask(&stages)
                    .command_buffers(&buffers)
                    .signal_semaphores(&signals)];
                if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.mark_stereo_submission_entered()?;}
                device
                    .queue_submit(queue, &submits, frame_fence)
                    .map_err(|error| format!("peer-queue-submit-{error:?}"))?;
                let swapchains = [swapchain];
                let indices = [image_index];
                match swapchain_loader.queue_present(
                    queue,
                    &vk::PresentInfoKHR::default()
                        .wait_semaphores(&signals)
                        .swapchains(&swapchains)
                        .image_indices(&indices),
                ) {
                    Ok(_) => {}
                    Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
                    Err(error) => return Err(format!("peer-queue-present-{error:?}")),
                }
            }
            #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
            {
                let request_id = (surface_generation << 32) | u64::from(frames_presented + 1);
                if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.mark_stereo_submission_entered()?;}
                let enqueue = crate::spatial_sdk_depth_handoff::enqueue_spatial_submit_present(
                    sdk_binding,
                    request_id,
                    0,
                    surface_generation,
                    0,
                    command_buffer.as_raw(),
                    image_available.as_raw(),
                    render_finished.as_raw(),
                    frame_fence.as_raw(),
                    swapchain.as_raw(),
                    vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT.as_raw(),
                    image_index,
                );
                if enqueue != 3 && enqueue != 0 {
                    projection_readback.cancel_unsubmitted("peer-spatial-sdk-enqueue");
                    return Err(format!("peer-spatial-sdk-enqueue-{enqueue}"));
                }
                let retirement_deadline = Instant::now() + Duration::from_secs(2);
                let mut retirement =
                    crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementState::new(request_id);
                let mut shutdown_reason = None;
                loop {
                    if shutdown_reason.is_none()
                        && (cancellation.load(Ordering::Acquire)
                            || Instant::now() >= retirement_deadline)
                    {
                        shutdown_reason = Some(if cancellation.load(Ordering::Acquire) {
                            "peer-session-cancelled-during-sdk-retirement"
                        } else {
                            "peer-spatial-sdk-retirement-timeout"
                        });
                        crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                            sdk_binding.session_generation,
                        );
                    }
                    if retirement.broker_status.is_none() {
                        match crate::spatial_sdk_depth_handoff::poll_spatial_submit_request(
                            request_id,
                        ) {
                            Ok(result) if result.status == 0 || result.status < 0 => {
                                if !retirement.observe_terminal(result) {
                                    crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                                        sdk_binding.session_generation,
                                    );
                                    return Err(
                                        "peer-spatial-sdk-invalid-terminal-result".to_string()
                                    );
                                }
                            }
                            Ok(_) => retirement.observe_not_ready(),
                            Err(status)
                                if status == crate::spatial_sdk_depth_handoff::STATUS_NOT_READY =>
                            {
                                retirement.observe_not_ready();
                            }
                            Err(status) => {
                                // The broker did not provide a typed submitted/unsubmitted
                                // terminal result. Cancel the owning SDK session and keep the
                                // AImage-backed source pinned until device-idle teardown.
                                crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                                    sdk_binding.session_generation,
                                );
                                return Err(format!("peer-spatial-sdk-poll-{status}"));
                            }
                        }
                    }
                    if retirement.accepted_submission_requires_fence() {
                        match device.get_fence_status(frame_fence) {
                            Ok(true) => retirement.observe_fence(),
                            Ok(false) => {}
                            Err(error) => {
                                crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                                    sdk_binding.session_generation,
                                );
                                return Err(format!("peer-poll-fence-{error:?}"));
                            }
                        }
                    }
                    match retirement.action() {
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::Wait => {}
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseSuccess => {
                            if let Some(reason) = shutdown_reason {
                                return Err(reason.to_string());
                            }
                            break;
                        }
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseUnsubmittedFailure => {
                            return Err(shutdown_reason.map(str::to_string).unwrap_or_else(|| {
                                format!(
                                    "peer-spatial-sdk-unsubmitted-{}-vk-{}",
                                    retirement.broker_status.unwrap_or(-1),
                                    retirement.broker_vk_result,
                                )
                            }));
                        }
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseSubmittedFailure => {
                            return Err(shutdown_reason.map(str::to_string).unwrap_or_else(|| {
                                format!(
                                    "peer-spatial-sdk-submitted-{}-vk-{}",
                                    retirement.broker_status.unwrap_or(-1),
                                    retirement.broker_vk_result,
                                )
                            }));
                        }
                    }
                    thread::yield_now();
                }
            }
            device
                .wait_for_fences(&[frame_fence], true, u64::MAX)
                .map_err(|error| format!("peer-retirement-fence-{error:?}"))?;
            projection_readback.retire_after_fence(&device);
            frames_presented = frames_presented.saturating_add(1);
            let pair_generation = current_frame
                .packed_pair
                .as_ref()
                .map(|pair| pair.pair_id)
                .unwrap_or(0);
            let effective = crate::peer_projection_runtime::record_peer_submission_retired(
                route_generation,
                PeerFrameWitness {
                    decoder_token: current_frame.decoder_token,
                    reader_generation: current_frame.reader_generation,
                    pair_generation,
                    import_generation: current_frame.import_sequence,
                },
            );
            if effective.words[11] != crate::peer_projection_runtime::RESULT_EFFECTIVE {
                return Err("peer-submission-retirement-rejected".to_string());
            }
            freshness.commit(
                pair_generation,
                current_frame.import_sequence,
                render_started.elapsed().as_millis() as u64,
            );
            if frames_presented <= 4 || frames_presented % 300 == 0 {
                log_marker(format!(
                "status=peer-common-graph-frame-retired routeGeneration={} framesPresented={} decoderToken={} readerGeneration={} pairGeneration={} importGeneration={} packedHardwareBufferId={} normalizedEyeImagesDistinct=true commonGraphSubmitted=true publicPrivateGuidePassesRetained=true zoneCompositorRetained=true wsiCarrierRetained=true camera2Opened=false videoProjectionRendered={} runtimeCrash=false",
                route_generation,
                frames_presented,
                current_frame.decoder_token,
                current_frame.reader_generation,
                pair_generation,
                current_frame.import_sequence,
                current_frame.descriptor.hardware_buffer_id,
                record_result.video_stats.rendered,
            ));
            }
        }
        if let Some(mut imports)=stereo_source_imports {
            device.wait_for_fences(&[frame_fence],true,u64::MAX).map_err(|e|format!("stereo-final-retire-{e:?}"))?;
            if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.retire_stereo_banks_after_fence(device)?;}
            imports.retire_after_fence(device)?;imports.destroy(device)?;
        }
        Ok(())
    })();

    let cleanup_result = owned.teardown();
    run_result.and(cleanup_result)
}

fn validate_peer_packed_frame(
    frame: &SpatialVideoProjectionFrame,
    route_generation: i64,
) -> Result<(), String> {
    let plan = packed_sbs_normalization_plan(frame.descriptor.width, frame.descriptor.height);
    if frame.route_generation != route_generation as u64
        || frame.decoder_token == 0
        || frame.reader_generation == 0
        || frame.import_sequence == 0
        || plan.is_none()
        || frame.configured_width != frame.descriptor.width as i32
        || frame.configured_height != frame.descriptor.height as i32
        || frame
            .packed_pair
            .as_ref()
            .is_none_or(|pair| pair.pair_id == 0)
    {
        return Err("peer-packed-frame-identity-or-layout-invalid".to_string());
    }
    Ok(())
}

mod source_bank_build {include!(concat!(env!("OUT_DIR"),"/spatial_source_bank_build.rs"));}

#[path="camera_hwb_source_set.rs"] mod source_set;
pub(crate) use source_set::start_source_set_common_graph;
