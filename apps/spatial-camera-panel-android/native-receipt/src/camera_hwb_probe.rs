use std::ffi::c_void;
#[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
use std::ffi::CString;
use std::os::raw::{c_float, c_int};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use ash::vk;
use ash::vk::Handle;

use crate::acamera_sys::{ANativeWindow, ANativeWindow_release as ACameraNativeWindow_release};
use crate::ahardware_buffer_vulkan::{
    import_ahb_sampled_image, query_ahb_vulkan_import_properties, AhbVulkanSampledImageCreateInfo,
};
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
use crate::camera_hwb_projection_target::{
    camera_hwb_projection_marker_fields, current_projection_zone_compositor_settings,
    update_camera_hwb_projection_stereo_horizontal_offset_uv,
    update_camera_hwb_projection_target_live_scale,
    update_projection_zone_channel_dynamics_settings, update_projection_zone_compositor_settings,
};
use crate::camera_hwb_stream::{
    CameraProbeFrame, CameraProbeFrameSet, CameraProbeRuntime, CameraProbeStreamMode,
};
use crate::camera_hwb_wsi::{
    allocate_camera_hwb_probe_descriptor_set, choose_composite_alpha, choose_extent,
    choose_surface_format, create_camera_hwb_probe_resources, create_framebuffers,
    create_image_views, create_render_pass, import_replacement_camera_frame,
    record_camera_hwb_probe_command_buffer, select_camera_surface_device,
    update_camera_hwb_probe_descriptor_set,
};
use crate::camera_latency_diagnostics::{
    boottime_now_ns, camera_latency_strict_pair_decision, current_camera_latency_settings,
    current_camera_latency_stereo_reprojection, CameraLatencyCameraSyncMode,
    CameraLatencyFrameTiming, CameraLatencyStereoPolicy, CameraLatencyStrictPairDecision,
    CameraLatencyWindow, CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS,
};
use crate::camera_replay_capture::{
    configured_camera_replay_capture, CameraReplayCaptureRecorder, CameraReplayFrameMetadata,
};
use crate::camera_reprojection_guard_band::CameraReprojectionGuardBandController;
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
};
use crate::spatial_video_projection::SpatialVideoProjectionRenderer;
use crate::spatial_video_projection_native_stream::latest_spatial_video_projection_frame;
use crate::spatial_video_projection_settings::spatial_video_projection_settings;
#[cfg(rq_environment_depth_spatial_sdk_api_layer)]
use crate::spatial_video_projection_settings::spatial_video_media_source_generation;
use crate::{bool_token, marker_token};

const CAMERA_HWB_PROBE_WAIT_FRAME_MS: u64 = 5000;
const CAMERA_HWB_PROBE_MAX_FRAMES: u32 = 1800;

static STOP_CAMERA_HWB_PROBE: AtomicBool = AtomicBool::new(false);
#[cfg(rq_environment_depth_spatial_sdk_api_layer)]
static NEXT_SDK_SURFACE_GENERATION: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(1);

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

    let window = unsafe { ANativeWindow_fromSurface(env, surface) };
    if window.is_null() {
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
    STOP_CAMERA_HWB_PROBE.store(false, Ordering::Release);

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
                render_camera_hwb_probe(window, width, height, max_frames, reader_max_images, mode)
            })
            .unwrap_or_else(|_| Err("panic".to_string()));
            unsafe {
                ACameraNativeWindow_release(window.cast::<ANativeWindow>());
            }
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

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopCameraHwbProbe(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    STOP_CAMERA_HWB_PROBE.store(true, Ordering::Release);
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

unsafe fn render_camera_hwb_probe(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    max_frames: u32,
    reader_max_images: c_int,
    mode: CameraHwbProbeMode,
) -> Result<CameraHwbProbeStats, String> {
    let entry = ash::Entry::load().map_err(|error| format!("vulkan-loader-{error}"))?;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let sdk_binding = {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if let Some(binding) = crate::spatial_sdk_depth_handoff::spatial_depth_device_binding() {
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
    let physical_devices = [vk::PhysicalDevice::from_raw(sdk_binding.physical_device_handle)];
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
    let device_extensions = [
        ash::khr::swapchain::NAME.as_ptr(),
        ash::android::external_memory_android_hardware_buffer::NAME.as_ptr(),
        ash::khr::sampler_ycbcr_conversion::NAME.as_ptr(),
    ];
    #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
    let mut sampler_ycbcr_enable =
        vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default().sampler_ycbcr_conversion(true);
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
    let swapchain_info = vk::SwapchainCreateInfoKHR::default()
        .surface(surface)
        .min_image_count(image_count)
        .image_format(surface_format.format)
        .image_color_space(surface_format.color_space)
        .image_extent(extent)
        .image_array_layers(1)
        .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
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

    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    log_marker(format!(
        "status=spatial-sdk-vulkan-binding-accepted sameLogicalDevice=true samePhysicalDevice=true sameQueueFamily=true sameQueue=true queueFamilyIndex={} queueIndex={} appWsiOwned=true sdkDeviceOwned=true sdkQueueOpaqueOwnership=true appSubmissionAuthority=layer-broker consumerFencePolicy=nonblocking-poll perFrameHostFenceWait=false rawHandlesLogged=false enabledCapabilityMask=0x{:x} runtimeCrash=false",
        sdk_binding.queue_family_index,
        sdk_binding.queue_index,
        sdk_binding.enabled_capability_mask,
    ));

    log_marker(format!(
        "status=render-loop-ready carrier=scenequadlayer-createAsAndroid-vulkan-wsi producerPath=Camera2-AImageReader-AHardwareBuffer-Vulkan-WSI swapchainImages={} extent={}x{} surfaceFormat={:?} presentMode={:?} presentModesAvailable={} compositeAlpha={:?} externalHwbExtensionReady={} samplerYcbcrExtensionReady={} samplerYcbcrFeatureReady={} outputMode={} rawCameraProjectionProbe={} stereoSource={} privateShaderStack=false customProjectionStack=false dynamicCameraPoseMetadataUsed=false imageTimestampPoseAssociation=selected-by-camera-latency-reprojection-mode captureResultMetadataCallbacks=false runtimeCrash=false {} {}",
        images.len(),
        extent.width,
        extent.height,
        surface_format.format,
        present_mode,
        marker_token(&format!("{present_modes:?}")),
        composite_alpha,
        extension_status.external_hwb_extension_ready,
        extension_status.sampler_ycbcr_extension_ready,
        extension_status.sampler_ycbcr_feature_ready,
        mode.output_mode(),
        mode.raw_projection_token(),
        mode.stereo_source(),
        mode.projection_contract_marker_fields(),
        active_latency_launch_settings.marker_fields(),
    ));

    let camera_runtime = CameraProbeRuntime::start(reader_max_images, mode.stream_mode())?;
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

    let memory_properties = instance.get_physical_device_memory_properties(physical_device);
    let ahb_device =
        ash::android::external_memory_android_hardware_buffer::Device::new(&instance, &device);
    let (left_import_properties, format_props) =
        query_ahb_vulkan_import_properties(&ahb_device, &initial_frames.left.hardware_buffer)?;
    let (right_import_properties, _right_format_props) =
        query_ahb_vulkan_import_properties(&ahb_device, &initial_frames.right.hardware_buffer)?;
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
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
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
    let mut submitted_broker_failure_observed_at: Option<Instant> = None;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut broker_terminal_consumed_total = 0_u64;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    let mut submit_retired_total = 0_u64;
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
    let mut camera_reprojection_guard_band = CameraReprojectionGuardBandController::default();
    let mut spatial_video_projection_rendered_marker_logged = false;
    let mut last_projection_zone_render_stats = None;
    let mut public_multistack_depth_evidence_marker_logged = false;
    let mut observed_latency_settings = active_latency_launch_settings;
    let mut freeze_frame_pending = active_latency_launch_settings.enabled
        && active_latency_launch_settings.freeze_frame
        && active_latency_launch_settings.camera_sync_mode
            == CameraLatencyCameraSyncMode::HoldImageUntilGpuFence;
    let mut freeze_frame_latched = false;
    let (initial_left_published_frames, initial_right_published_frames) =
        camera_runtime.published_frame_counts();
    let mut latency_window = CameraLatencyWindow::new(
        current_left_frame.frame_index,
        current_right_frame.frame_index,
        initial_left_published_frames,
        initial_right_published_frames,
    );
    while (max_frames == 0 || frames_presented < max_frames)
        && !STOP_CAMERA_HWB_PROBE.load(Ordering::Acquire)
    {
        let loop_started = Instant::now();
        let requested_latency_settings = current_camera_latency_settings();
        if requested_latency_settings != observed_latency_settings {
            let previous_latency_settings = observed_latency_settings;
            let launch_settings_pending_restart = requested_latency_settings.present_mode
                != active_latency_launch_settings.present_mode
                || requested_latency_settings.image_count
                    != active_latency_launch_settings.image_count
                || requested_latency_settings.capture_fps
                    != active_latency_launch_settings.capture_fps
                || requested_latency_settings.capture_processing
                    != active_latency_launch_settings.capture_processing;
            observed_latency_settings = requested_latency_settings;
            let (left_published_frames, right_published_frames) =
                camera_runtime.published_frame_counts();
            latency_window = CameraLatencyWindow::new(
                current_left_frame.frame_index,
                current_right_frame.frame_index,
                left_published_frames,
                right_published_frames,
            );
            pending_strict_left = None;
            pending_strict_right = None;
            if !observed_latency_settings.enabled || !observed_latency_settings.freeze_frame {
                if freeze_frame_latched || freeze_frame_pending {
                    log_marker(format!(
                        "status=camera-freeze-released cameraLatencyRevision={} runtimeCrash=false",
                        observed_latency_settings.revision,
                    ));
                }
                freeze_frame_pending = false;
                freeze_frame_latched = false;
            } else if observed_latency_settings.camera_sync_mode
                != CameraLatencyCameraSyncMode::HoldImageUntilGpuFence
            {
                freeze_frame_pending = false;
                freeze_frame_latched = false;
                log_marker(format!(
                    "status=camera-freeze-rejected reason=requires-hold-image-until-gpu-fence cameraLatencyRevision={} cameraSyncRequested={} runtimeCrash=false",
                    observed_latency_settings.revision,
                    observed_latency_settings.camera_sync_mode.marker_token(),
                ));
            } else if !previous_latency_settings.freeze_frame
                || previous_latency_settings.camera_sync_mode
                    != CameraLatencyCameraSyncMode::HoldImageUntilGpuFence
            {
                freeze_frame_pending = true;
                freeze_frame_latched = false;
                log_marker(format!(
                    "status=camera-freeze-armed latchPolicy=next-complete-fence-held-stereo-import cameraLatencyRevision={} runtimeCrash=false",
                    observed_latency_settings.revision,
                ));
            }
            log_marker(format!(
                "status=latency-settings-observed launchSettingsPendingRestart={} activePresentMode={:?} activeSwapchainImages={} {}",
                bool_token(launch_settings_pending_restart),
                present_mode,
                images.len(),
                observed_latency_settings.marker_fields(),
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
                    Err(status)
                        if status
                            == crate::spatial_sdk_depth_handoff::STATUS_NOT_READY =>
                    {
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
                crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseSuccess => {}
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
            let release_status = submitted_depth_lease
                .take()
                .map(crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease)
                .unwrap_or(0);
            submit_retired_total = submit_retired_total.saturating_add(1);
            let request_ordinal = completed_retirement.request_id & u64::from(u32::MAX);
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
        if let Some(capture) = camera_replay_capture.as_mut() {
            capture.retire_completed(&device)?;
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
            && !freeze_frame_latched
            && observed_latency_settings.should_adopt_camera_image(frames_presented)
        {
            let frame_wait =
                Duration::from_millis(observed_latency_settings.effective_frame_wait_ms() as u64);
            match observed_latency_settings.stereo_policy {
                CameraLatencyStereoPolicy::IndependentLatest => {
                    let left_wait_started = Instant::now();
                    let next_left_frame = camera_runtime.wait_for_left_frame_after(
                        last_polled_left_hwb_import_sequence,
                        frame_wait,
                    );
                    frame_timing.camera_wait += left_wait_started.elapsed();
                    if let Some(next_frame) = next_left_frame {
                        last_polled_left_hwb_import_sequence = next_frame.hwb_import_sequence;
                        let import_started = Instant::now();
                        match import_replacement_camera_frame(
                            &device,
                            &memory_properties,
                            &ahb_device,
                            &camera_resources,
                            format_key,
                            &next_frame,
                        ) {
                            Ok(next_sampled_image) => {
                                update_camera_hwb_probe_descriptor_set(
                                    &device,
                                    &camera_resources,
                                    descriptor_set,
                                    next_sampled_image.image_view,
                                    sampled_right_image.as_ref().map(|image| image.image_view),
                                    mode,
                                );
                                log_fence_held_frame_retirement(&current_left_frame, "left");
                                sampled_left_image.destroy(&device);
                                sampled_left_image = next_sampled_image;
                                current_left_frame = next_frame;
                                transition_left_camera_image = true;
                                left_imported = true;
                            }
                            Err(error) => {
                                log_camera_frame_import_skipped(&next_frame, "left", mode, &error)
                            }
                        }
                        frame_timing.camera_import += import_started.elapsed();
                    }
                    let right_wait_started = Instant::now();
                    let next_right_frame = camera_runtime.wait_for_right_frame_after(
                        last_polled_right_hwb_import_sequence,
                        frame_wait,
                    );
                    frame_timing.camera_wait += right_wait_started.elapsed();
                    if let Some(next_frame) = next_right_frame {
                        last_polled_right_hwb_import_sequence = next_frame.hwb_import_sequence;
                        let import_started = Instant::now();
                        match import_replacement_camera_frame(
                            &device,
                            &memory_properties,
                            &ahb_device,
                            &camera_resources,
                            format_key,
                            &next_frame,
                        ) {
                            Ok(next_sampled_image) => {
                                update_camera_hwb_probe_descriptor_set(
                                    &device,
                                    &camera_resources,
                                    descriptor_set,
                                    sampled_left_image.image_view,
                                    Some(next_sampled_image.image_view),
                                    mode,
                                );
                                log_fence_held_frame_retirement(&current_right_frame, "right");
                                if let Some(previous) = sampled_right_image.take() {
                                    previous.destroy(&device);
                                }
                                sampled_right_image = Some(next_sampled_image);
                                current_right_frame = next_frame;
                                transition_right_camera_image = true;
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
                    let next_left_frame = camera_runtime.wait_for_left_frame_after(
                        last_polled_left_hwb_import_sequence,
                        frame_wait,
                    );
                    frame_timing.camera_wait += left_wait_started.elapsed();
                    if let Some(next_frame) = next_left_frame {
                        last_polled_left_hwb_import_sequence = next_frame.hwb_import_sequence;
                        let import_started = Instant::now();
                        match import_replacement_camera_frame(
                            &device,
                            &memory_properties,
                            &ahb_device,
                            &camera_resources,
                            format_key,
                            &next_frame,
                        ) {
                            Ok(next_sampled_image) => {
                                update_camera_hwb_probe_descriptor_set(
                                    &device,
                                    &camera_resources,
                                    descriptor_set,
                                    next_sampled_image.image_view,
                                    None,
                                    mode,
                                );
                                log_fence_held_frame_retirement(
                                    &current_left_frame,
                                    "left-mono-source",
                                );
                                sampled_left_image.destroy(&device);
                                sampled_left_image = next_sampled_image;
                                current_left_frame = next_frame;
                                current_right_frame = current_left_frame.clone();
                                transition_left_camera_image = true;
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
                    if pending_strict_left.is_none() {
                        let left_wait_started = Instant::now();
                        pending_strict_left = camera_runtime.wait_for_left_frame_after(
                            last_polled_left_hwb_import_sequence,
                            frame_wait,
                        );
                        frame_timing.camera_wait += left_wait_started.elapsed();
                        if let Some(frame) = pending_strict_left.as_ref() {
                            last_polled_left_hwb_import_sequence = frame.hwb_import_sequence;
                        }
                    }
                    if pending_strict_right.is_none() {
                        let right_wait_started = Instant::now();
                        pending_strict_right = camera_runtime.wait_for_right_frame_after(
                            last_polled_right_hwb_import_sequence,
                            frame_wait,
                        );
                        frame_timing.camera_wait += right_wait_started.elapsed();
                        if let Some(frame) = pending_strict_right.as_ref() {
                            last_polled_right_hwb_import_sequence = frame.hwb_import_sequence;
                        }
                    }
                    if let (Some(left), Some(right)) =
                        (pending_strict_left.as_ref(), pending_strict_right.as_ref())
                    {
                        let pair_delta_ns = camera_probe_pair_delta_ns(left, right);
                        if camera_latency_strict_pair_decision(pair_delta_ns)
                            == CameraLatencyStrictPairDecision::Accept
                        {
                            let next_left = pending_strict_left.take().expect("left checked");
                            let next_right = pending_strict_right.take().expect("right checked");
                            let import_started = Instant::now();
                            let next_left_image = import_replacement_camera_frame(
                                &device,
                                &memory_properties,
                                &ahb_device,
                                &camera_resources,
                                format_key,
                                &next_left,
                            );
                            let next_right_image = import_replacement_camera_frame(
                                &device,
                                &memory_properties,
                                &ahb_device,
                                &camera_resources,
                                format_key,
                                &next_right,
                            );
                            match (next_left_image, next_right_image) {
                                (Ok(left_image), Ok(right_image)) => {
                                    update_camera_hwb_probe_descriptor_set(
                                        &device,
                                        &camera_resources,
                                        descriptor_set,
                                        left_image.image_view,
                                        Some(right_image.image_view),
                                        mode,
                                    );
                                    log_fence_held_frame_retirement(
                                        &current_left_frame,
                                        "left-strict-pair",
                                    );
                                    log_fence_held_frame_retirement(
                                        &current_right_frame,
                                        "right-strict-pair",
                                    );
                                    sampled_left_image.destroy(&device);
                                    if let Some(previous) = sampled_right_image.take() {
                                        previous.destroy(&device);
                                    }
                                    sampled_left_image = left_image;
                                    sampled_right_image = Some(right_image);
                                    current_left_frame = next_left;
                                    current_right_frame = next_right;
                                    transition_left_camera_image = true;
                                    transition_right_camera_image = true;
                                    left_imported = true;
                                    right_imported = true;
                                    strict_pair_generation =
                                        strict_pair_generation.saturating_add(1);
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
                            strict_pair_rejections = strict_pair_rejections.saturating_add(1);
                            pending_strict_left = None;
                            pending_strict_right = None;
                            if strict_pair_rejections <= 4
                                || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled()
                            {
                                log_marker(format!(
                                    "status=strict-stereo-pair-rejected pairDeltaMs={:.3} maxPairDeltaMs={:.3} rejectedPairs={} policy=strict-timestamp-pair recoveryPolicy=discard-both-latest-candidates recoveryReason=prevent-one-source-period-chase runtimeCrash=false",
                                    pair_delta_ns as f64 / 1_000_000.0,
                                    CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS as f64 / 1_000_000.0,
                                    strict_pair_rejections,
                                ));
                            }
                        }
                    }
                    if observed_latency_settings.should_discard_unpaired_strict_latest_candidate()
                        && pending_strict_left.is_some() != pending_strict_right.is_some()
                    {
                        strict_unpaired_resets = strict_unpaired_resets.saturating_add(1);
                        pending_strict_left = None;
                        pending_strict_right = None;
                        if strict_unpaired_resets <= 4
                            || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled()
                        {
                            log_marker(format!(
                                "status=strict-stereo-pair-unpaired-reset resets={} policy=strict-timestamp-pair adoptionCadence=display-aligned-45 recoveryPolicy=discard-unpaired-latest-candidate recoveryReason=next-45hz-poll-would-overshoot-missing-eye runtimeCrash=false",
                                strict_unpaired_resets,
                            ));
                        }
                    }
                }
            }
        }
        if freeze_frame_pending
            && current_left_frame.has_fence_held_image()
            && current_right_frame.has_fence_held_image()
        {
            freeze_frame_pending = false;
            freeze_frame_latched = true;
            log_marker(format!(
                "status=camera-freeze-latched cameraLatencyRevision={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} cameraSyncActive=hold-image-until-gpu-fence latchFenceWaitComplete=true callbacksContinue=true importsPaused=true runtimeCrash=false",
                observed_latency_settings.revision,
                current_left_frame.frame_index,
                current_right_frame.frame_index,
                current_left_frame.descriptor.hardware_buffer_id,
                current_right_frame.descriptor.hardware_buffer_id,
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
            current_left_frame.capture_viewer_basis,
            current_right_frame.capture_viewer_basis,
        );
        let projection_zone_settings = current_projection_zone_compositor_settings();
        let projection_guard_band = camera_reprojection_guard_band.update_for_projection_buffer(
            observed_latency_settings,
            projection_zone_settings.buffer_geometry_mode,
            projection_zone_settings.buffer_static_width_uv,
            camera_reprojection,
            boottime_now_ns(),
        );
        let presentation_pose = camera_reprojection.presentation;
        let record_result = record_camera_hwb_probe_command_buffer(
            &device,
            command_buffer,
            render_pass,
            framebuffers[image_index as usize],
            extent,
            &camera_resources,
            descriptor_set,
            &sampled_left_image,
            sampled_right_image.as_ref(),
            transition_left_camera_image,
            transition_right_camera_image,
            public_guide_targets.as_mut(),
            public_stack_elapsed_seconds,
            video_renderer.as_mut(),
            latest_video_frame.as_ref(),
            &video_settings,
            image_index as usize,
            camera_reprojection,
            projection_guard_band,
            observed_latency_settings,
            camera_replay_capture.as_mut(),
            boottime_now_ns().max(0) as u64,
            CameraReplayFrameMetadata {
                left_camera_id: current_left_frame.camera_id.clone(),
                right_camera_id: current_right_frame.camera_id.clone(),
                left_frame_index: current_left_frame.frame_index,
                right_frame_index: current_right_frame.frame_index,
                left_timestamp_ns: current_left_frame.timestamp_ns,
                right_timestamp_ns: current_right_frame.timestamp_ns,
                pair_delta_ns: current_left_frame
                    .timestamp_ns
                    .abs_diff(current_right_frame.timestamp_ns),
            },
        )?;
        frame_timing.record = record_started.elapsed();
        let projected_by_public_stack = record_result.projected_by_public_stack;
        if last_projection_zone_render_stats != Some(record_result.projection_zone_stats) {
            log_marker(format!(
                "status=projection-zone-render-effective {} runtimeCrash=false",
                record_result.projection_zone_stats.marker_fields(),
            ));
            last_projection_zone_render_stats = Some(record_result.projection_zone_stats);
        }
        transition_left_camera_image = false;
        transition_right_camera_image = false;
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
        device
            .queue_submit(queue, &submit_info, frame_fence)
            .map_err(|error| format!("queue-submit-{error:?}"))?;
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
                if let Some(lease) = current_depth_lease {
                    let _ = crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease(
                        lease,
                    );
                }
                return Err(format!("spatial-sdk-queue-broker-enqueue-{enqueue_status}"));
            }
            submitted_depth_lease = current_depth_lease;
            submitted_retirement = Some(
                crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementState::new(request_id),
            );
            submitted_broker_failure_observed_at = None;
        }
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
        frame_timing.loop_total = loop_started.elapsed();
        if frames_presented <= 4
            || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled()
        {
            let presentation_sequence = presentation_pose
                .basis
                .map(|basis| basis.sequence)
                .unwrap_or(0);
            let left_capture_sequence = current_left_frame
                .capture_viewer_basis
                .map(|basis| basis.sequence)
                .unwrap_or(0);
            let right_capture_sequence = current_right_frame
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
                observed_latency_settings
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
        if observed_latency_settings.stereo_policy == CameraLatencyStereoPolicy::StrictTimestampPair
            && left_imported
            && right_imported
            && (strict_pair_generation <= 4
                || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled())
        {
            log_marker(format!(
                "status=strict-stereo-pair-presented pairGeneration={} presentOrdinal={} leftFrameIndex={} rightFrameIndex={} leftHwbImportSequence={} rightHwbImportSequence={} leftTimestampNs={} rightTimestampNs={} pairDeltaNs={} maxPairDeltaNs={} bothDescriptorBindingsUpdatedBeforeRecord=true bothCameraImagesTransitionedTogether=true packedEyesRecordedInSingleCommandBuffer=true singleQueuePresent=true runtimeCrash=false",
                strict_pair_generation,
                frames_presented,
                current_left_frame.frame_index,
                current_right_frame.frame_index,
                current_left_frame.hwb_import_sequence,
                current_right_frame.hwb_import_sequence,
                current_left_frame.timestamp_ns,
                current_right_frame.timestamp_ns,
                current_left_frame.timestamp_ns.abs_diff(current_right_frame.timestamp_ns),
                CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS,
            ));
        }
        let (left_published_frames, right_published_frames) =
            camera_runtime.published_frame_counts();
        latency_window.record(
            frame_timing,
            left_imported,
            right_imported,
            current_left_frame.frame_index,
            current_right_frame.frame_index,
            left_published_frames,
            right_published_frames,
            current_left_frame.source_delta_ns,
            current_right_frame.source_delta_ns,
            current_left_frame.callback_delta_ns,
            current_right_frame.callback_delta_ns,
            record_result.camera_projection_visible,
        );
        if latency_window.should_emit(observed_latency_settings) {
            let present_call_boottime_ns = boottime_now_ns();
            latency_window.emit_and_reset(
                observed_latency_settings,
                present_mode,
                images.len() as u32,
                active_latency_launch_settings,
                current_left_frame.timestamp_source.marker_token(),
                current_right_frame.timestamp_source.marker_token(),
                current_left_frame.callback_age_ns,
                current_right_frame.callback_age_ns,
                current_left_frame.sensor_age_at_boottime(present_call_boottime_ns),
                current_right_frame.sensor_age_at_boottime(present_call_boottime_ns),
                current_left_frame
                    .timestamp_ns
                    .abs_diff(current_right_frame.timestamp_ns),
            );
        }
        if mode.should_stream_latest_frame() && !public_multistack_depth_evidence_marker_logged {
            if let Some(depth_evidence) = public_guide_targets
                .as_ref()
                .and_then(|targets| targets.compact_depth_evidence_marker_fields())
            {
                log_marker(format!(
                    "status=public-multistack-depth-evidence framesPresented={} outputMode=raw-color-target-rect stereoSource=camera50-51 monoDuplicated=false {} runtimeCrash=false",
                    frames_presented,
                    depth_evidence,
                ));
                if let Some(alignment_evidence) = public_guide_targets
                    .as_ref()
                    .and_then(|targets| targets.compact_depth_alignment_evidence_marker_fields())
                {
                    log_marker(format!(
                        "status=public-multistack-depth-alignment-evidence framesPresented={} {} runtimeCrash=false",
                        frames_presented,
                        alignment_evidence,
                    ));
                }
                if let Some(source_evidence) = public_guide_targets
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
            let public_stack_frame_marker = public_guide_targets
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
            if let Some(targets) = public_guide_targets.as_ref() {
                log_marker(format!(
                    "status=public-multistack-projection-evidence framesPresented={} outputMode=raw-color-target-rect stereoSource=camera50-51 monoDuplicated=false {} runtimeCrash=false",
                    frames_presented,
                    targets.compact_projection_evidence_marker_fields(
                        projected_by_public_stack,
                        public_stack_elapsed_seconds,
                        projection_guard_band.footprint_scale,
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
        if frames_presented == 1 {
            log_marker(format!(
                "status=first-camera-frame-presented leftCameraId={} rightCameraId={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} leftHwbImportSequence={} rightHwbImportSequence={} pairDeltaNs={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi vkGetAhbPropertiesResult=success sampledCameraTexture=true sampledLeftCameraTexture=true sampledRightCameraTexture={} samplerMode={} outputMode={} rawCameraProjectionProbe={} privateShaderStack=false customProjectionStack=false leftTimestampNs={} rightTimestampNs={} leftWidth={} leftHeight={} rightWidth={} rightHeight={} leftFormat={} rightFormat={} leftUsage=0x{:x} rightUsage=0x{:x} leftStride={} rightStride={} noRepeatedRawHwbSampling={} stereoSource={} runtimeCrash=false {}",
                marker_token(&current_left_frame.camera_id),
                marker_token(&current_right_frame.camera_id),
                current_left_frame.frame_index,
                current_right_frame.frame_index,
                current_left_frame.descriptor.hardware_buffer_id,
                current_right_frame.descriptor.hardware_buffer_id,
                current_left_frame.hwb_import_sequence,
                current_right_frame.hwb_import_sequence,
                current_left_frame.timestamp_ns.abs_diff(current_right_frame.timestamp_ns),
                bool_token(matches!(mode, CameraHwbProbeMode::RawColorProjection)),
                sampler_mode,
                mode.output_mode(),
                mode.raw_projection_token(),
                current_left_frame.timestamp_ns,
                current_right_frame.timestamp_ns,
                current_left_frame.descriptor.width,
                current_left_frame.descriptor.height,
                current_right_frame.descriptor.width,
                current_right_frame.descriptor.height,
                current_left_frame.descriptor.format,
                current_right_frame.descriptor.format,
                current_left_frame.descriptor.usage,
                current_right_frame.descriptor.usage,
                current_left_frame.descriptor.stride,
                current_right_frame.descriptor.stride,
                bool_token(!mode.should_stream_latest_frame()),
                mode.stereo_source(),
                mode.projection_contract_marker_fields(),
            ));
        } else if mode.should_stream_latest_frame() && frames_presented <= 4 {
            log_marker(format!(
                "status=raw-camera-frame-presented framesPresented={} leftCameraId={} rightCameraId={} leftFrameIndex={} rightFrameIndex={} leftHardwareBufferId={} rightHardwareBufferId={} leftHwbImportSequence={} rightHwbImportSequence={} pairDeltaNs={} sampledCameraTexture=true sampledLeftCameraTexture=true sampledRightCameraTexture=true outputMode=raw-color-target-rect stereoSource=camera50-51 monoDuplicated=false {} runtimeCrash=false",
                frames_presented,
                marker_token(&current_left_frame.camera_id),
                marker_token(&current_right_frame.camera_id),
                current_left_frame.frame_index,
                current_right_frame.frame_index,
                current_left_frame.descriptor.hardware_buffer_id,
                current_right_frame.descriptor.hardware_buffer_id,
                current_left_frame.hwb_import_sequence,
                current_right_frame.hwb_import_sequence,
                current_left_frame.timestamp_ns.abs_diff(current_right_frame.timestamp_ns),
                mode.public_multistack_marker_fields(),
            ));
        }
    }

    device
        .device_wait_idle()
        .map_err(|error| format!("device-wait-idle-{error:?}"))?;
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    if let Some(completed_lease) = submitted_depth_lease.take() {
        let _ = crate::spatial_sdk_depth_handoff::release_spatial_depth_render_lease(
            completed_lease,
        );
    }
    if let Some(mut capture) = camera_replay_capture {
        capture.retire_completed(&device)?;
        capture.finish(if capture.is_complete() {
            "requested-frame-count-reached"
        } else {
            "camera-projection-stopped"
        })?;
        capture.destroy(&device);
    }
    if let Some(sampled_right_image) = sampled_right_image {
        sampled_right_image.destroy(&device);
    }
    sampled_left_image.destroy(&device);
    if let Some(mut video_renderer) = video_renderer {
        video_renderer.destroy(&device);
    }
    camera_resources.destroy(&device);
    if let Some(public_guide_targets) = public_guide_targets {
        public_guide_targets.destroy(&device);
    }
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
    drop(camera_runtime);
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
        left_camera_id: current_left_frame.camera_id,
        right_camera_id: current_right_frame.camera_id,
        left_frame_index: current_left_frame.frame_index,
        right_frame_index: current_right_frame.frame_index,
        left_hardware_buffer_id: current_left_frame.descriptor.hardware_buffer_id,
        right_hardware_buffer_id: current_right_frame.descriptor.hardware_buffer_id,
        left_hwb_import_sequence: current_left_frame.hwb_import_sequence,
        right_hwb_import_sequence: current_right_frame.hwb_import_sequence,
        pair_delta_ns: current_left_frame
            .timestamp_ns
            .abs_diff(current_right_frame.timestamp_ns),
        sampler_mode,
    })
}
