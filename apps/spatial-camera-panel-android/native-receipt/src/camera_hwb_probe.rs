use std::ffi::{c_void, CString};
use std::os::raw::{c_float, c_int};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use ash::vk;

use crate::acamera_sys::{ANativeWindow, ANativeWindow_release as ACameraNativeWindow_release};
use crate::ahardware_buffer_vulkan::{
    import_ahb_sampled_image, query_ahb_vulkan_import_properties, AhbVulkanSampledImageCreateInfo,
};
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
use crate::camera_hwb_projection_target::{
    camera_hwb_projection_marker_fields, update_camera_hwb_projection_stereo_horizontal_offset_uv,
    update_camera_hwb_projection_target_live_scale,
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
    boottime_now_ns, current_camera_latency_rotation_reprojection, current_camera_latency_settings,
    CameraLatencyCameraSyncMode, CameraLatencyFrameTiming, CameraLatencyStereoPolicy,
    CameraLatencyWindow, CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS,
};
use crate::spatial_public_multistack::{
    public_multistack_inactive_marker_fields, public_multistack_marker_fields,
};
use crate::spatial_public_multistack_runtime::{
    allocate_spatial_public_guide_targets, public_guide_targets_pending_marker_fields,
    update_spatial_public_depth_alignment, update_spatial_public_depth_layer_policy,
    update_spatial_public_opaque_projection_layer_override,
};
use crate::spatial_video_projection::SpatialVideoProjectionRenderer;
use crate::spatial_video_projection_native_stream::latest_spatial_video_projection_frame;
use crate::spatial_video_projection_settings::spatial_video_projection_settings;
use crate::{bool_token, marker_token};

const CAMERA_HWB_PROBE_WAIT_FRAME_MS: u64 = 5000;
const CAMERA_HWB_PROBE_MAX_FRAMES: u32 = 1800;

static STOP_CAMERA_HWB_PROBE: AtomicBool = AtomicBool::new(false);

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
) -> i64 {
    let applied_alignment = update_spatial_public_depth_alignment(
        left_offset_x as f32,
        left_offset_y as f32,
        right_offset_x as f32,
        right_offset_y as f32,
        sample_scale as f32,
    );
    log_marker(format!(
        "status=private-layer-depth-alignment-updated rawCameraProjectionProbe=true updateMask=1 spatialPrivateLayerControlPanel=true publicMultiStackDepthAlignmentControl=true publicMultiStackDepthAlignmentLeftOffsetUv={:.6},{:.6} publicMultiStackDepthAlignmentRightOffsetUv={:.6},{:.6} publicMultiStackDepthAlignmentSampleScale={:.4} requestedPublicMultiStackDepthAlignmentLeftOffsetUv={:.6},{:.6} requestedPublicMultiStackDepthAlignmentRightOffsetUv={:.6},{:.6} requestedPublicMultiStackDepthAlignmentSampleScale={:.4} {} runtimeCrash=false",
        applied_alignment.left_offset_uv[0],
        applied_alignment.left_offset_uv[1],
        applied_alignment.right_offset_uv[0],
        applied_alignment.right_offset_uv[1],
        applied_alignment.sample_scale,
        left_offset_x as f32,
        left_offset_y as f32,
        right_offset_x as f32,
        right_offset_y as f32,
        sample_scale as f32,
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
    let app_name = CString::new("rusty-quest-spatial-camera-panel").expect("static app name");
    let engine_name = CString::new("camera-hwb-spatial-probe").expect("static engine name");
    let app_info = vk::ApplicationInfo::default()
        .application_name(&app_name)
        .application_version(1)
        .engine_name(&engine_name)
        .engine_version(1)
        .api_version(vk::make_api_version(0, 1, 1, 0));
    let instance_extensions = [
        ash::khr::surface::NAME.as_ptr(),
        ash::khr::android_surface::NAME.as_ptr(),
    ];
    let instance_info = vk::InstanceCreateInfo::default()
        .application_info(&app_info)
        .enabled_extension_names(&instance_extensions);
    let instance = entry
        .create_instance(&instance_info, None)
        .map_err(|error| format!("create-instance-{error:?}"))?;

    let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
    let android_surface_loader = ash::khr::android_surface::Instance::new(&entry, &instance);
    let surface_info = vk::AndroidSurfaceCreateInfoKHR::default().window(window);
    let surface = android_surface_loader
        .create_android_surface(&surface_info, None)
        .map_err(|error| {
            instance.destroy_instance(None);
            format!("create-android-surface-{error:?}")
        })?;

    let physical_devices = instance.enumerate_physical_devices().map_err(|error| {
        surface_loader.destroy_surface(surface, None);
        instance.destroy_instance(None);
        format!("enumerate-physical-devices-{error:?}")
    })?;
    let (physical_device, queue_family_index, extension_status) =
        select_camera_surface_device(&instance, &surface_loader, surface, &physical_devices)
            .ok_or_else(|| {
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                "no-camera-hwb-vulkan-device".to_string()
            })?;

    if !extension_status.external_hwb_extension_ready
        || !extension_status.sampler_ycbcr_extension_ready
        || !extension_status.sampler_ycbcr_feature_ready
    {
        surface_loader.destroy_surface(surface, None);
        instance.destroy_instance(None);
        return Err(format!(
            "vulkan-ahb-prereq-missing-externalHwb-{}-samplerYcbcrExt-{}-samplerYcbcrFeature-{}",
            extension_status.external_hwb_extension_ready,
            extension_status.sampler_ycbcr_extension_ready,
            extension_status.sampler_ycbcr_feature_ready,
        ));
    }

    let queue_priorities = [1.0_f32];
    let queue_info = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family_index)
        .queue_priorities(&queue_priorities)];
    let device_extensions = [
        ash::khr::swapchain::NAME.as_ptr(),
        ash::android::external_memory_android_hardware_buffer::NAME.as_ptr(),
        ash::khr::sampler_ycbcr_conversion::NAME.as_ptr(),
    ];
    let mut sampler_ycbcr_enable =
        vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default().sampler_ycbcr_conversion(true);
    let device_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_info)
        .enabled_extension_names(&device_extensions)
        .push_next(&mut sampler_ycbcr_enable);
    let device = instance
        .create_device(physical_device, &device_info, None)
        .map_err(|error| {
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("create-device-{error:?}")
        })?;
    let queue = device.get_device_queue(queue_family_index, 0);
    let swapchain_loader = ash::khr::swapchain::Device::new(&instance, &device);

    let surface_format = choose_surface_format(
        &surface_loader
            .get_physical_device_surface_formats(physical_device, surface)
            .map_err(|error| {
                device.destroy_device(None);
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                format!("surface-formats-{error:?}")
            })?,
    );
    let capabilities = surface_loader
        .get_physical_device_surface_capabilities(physical_device, surface)
        .map_err(|error| {
            device.destroy_device(None);
            surface_loader.destroy_surface(surface, None);
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
            device.destroy_device(None);
            surface_loader.destroy_surface(surface, None);
            instance.destroy_instance(None);
            format!("create-swapchain-{error:?}")
        })?;
    let images = swapchain_loader
        .get_swapchain_images(swapchain)
        .map_err(|error| {
            swapchain_loader.destroy_swapchain(swapchain, None);
            device.destroy_device(None);
            surface_loader.destroy_surface(surface, None);
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
    let mut current_left_frame = initial_frames.left;
    let mut current_right_frame = initial_frames.right;
    let mut last_polled_left_hwb_import_sequence = current_left_frame.hwb_import_sequence;
    let mut last_polled_right_hwb_import_sequence = current_right_frame.hwb_import_sequence;
    let mut pending_strict_left: Option<CameraProbeFrame> = None;
    let mut pending_strict_right: Option<CameraProbeFrame> = None;
    let mut strict_pair_rejections = 0_u64;
    let mut strict_pair_generation = 0_u64;
    let mut transition_left_camera_image = true;
    let mut transition_right_camera_image = matches!(mode, CameraHwbProbeMode::RawColorProjection);
    let mut frames_presented = 0_u32;
    let mut spatial_video_projection_rendered_marker_logged = false;
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
        device
            .wait_for_fences(&[frame_fence], true, u64::MAX)
            .map_err(|error| format!("wait-fence-{error:?}"))?;
        device
            .reset_fences(&[frame_fence])
            .map_err(|error| format!("reset-fence-{error:?}"))?;
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
                        if pair_delta_ns <= CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS {
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
                            let left_order = camera_probe_frame_order_timestamp(left);
                            let right_order = camera_probe_frame_order_timestamp(right);
                            if left_order <= right_order {
                                pending_strict_left = None;
                            } else {
                                pending_strict_right = None;
                            }
                            if strict_pair_rejections <= 4
                                || crate::camera_latency_diagnostics::camera_latency_per_frame_log_enabled()
                            {
                                log_marker(format!(
                                    "status=strict-stereo-pair-rejected pairDeltaMs={:.3} maxPairDeltaMs={:.3} rejectedPairs={} policy=strict-timestamp-pair runtimeCrash=false",
                                    pair_delta_ns as f64 / 1_000_000.0,
                                    CAMERA_LATENCY_STRICT_PAIR_MAX_DELTA_NS as f64 / 1_000_000.0,
                                    strict_pair_rejections,
                                ));
                            }
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
        let camera_reprojection =
            current_camera_latency_rotation_reprojection(current_left_frame.capture_viewer_basis);
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
            observed_latency_settings,
        )?;
        frame_timing.record = record_started.elapsed();
        let projected_by_public_stack = record_result.projected_by_public_stack;
        transition_left_camera_image = false;
        transition_right_camera_image = false;
        let wait_semaphores = [image_available];
        let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let signal_semaphores = [render_finished];
        let submit_command_buffers = [command_buffer];
        let submit_info = [vk::SubmitInfo::default()
            .wait_semaphores(&wait_semaphores)
            .wait_dst_stage_mask(&wait_stages)
            .command_buffers(&submit_command_buffers)
            .signal_semaphores(&signal_semaphores)];
        let submit_started = Instant::now();
        device
            .queue_submit(queue, &submit_info, frame_fence)
            .map_err(|error| format!("queue-submit-{error:?}"))?;
        frame_timing.submit = submit_started.elapsed();
        let swapchains = [swapchain];
        let image_indices = [image_index];
        let present_info = vk::PresentInfoKHR::default()
            .wait_semaphores(&signal_semaphores)
            .swapchains(&swapchains)
            .image_indices(&image_indices);
        let present_started = Instant::now();
        match swapchain_loader.queue_present(queue, &present_info) {
            Ok(_suboptimal) => {}
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
            Err(error) => return Err(format!("queue-present-{error:?}")),
        }
        frame_timing.present_call = present_started.elapsed();
        frames_presented = frames_presented.saturating_add(1);
        frame_timing.loop_total = loop_started.elapsed();
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
        if mode.should_stream_latest_frame() && frames_presented <= 4 {
            let public_stack_frame_marker = public_guide_targets
                .as_ref()
                .map(|targets| {
                    targets.frame_marker_fields(
                        projected_by_public_stack,
                        public_stack_elapsed_seconds,
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
    device.destroy_device(None);
    surface_loader.destroy_surface(surface, None);
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
