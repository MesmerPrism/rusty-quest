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
};
use crate::camera_hwb_stream::{CameraProbeFrameSet, CameraProbeRuntime, CameraProbeStreamMode};
use crate::camera_hwb_wsi::{
    allocate_camera_hwb_probe_descriptor_set, choose_composite_alpha, choose_extent,
    choose_image_count, choose_present_mode, choose_surface_format,
    create_camera_hwb_probe_resources, create_framebuffers, create_image_views, create_render_pass,
    import_replacement_camera_frame, record_camera_hwb_probe_command_buffer,
    select_camera_surface_device, update_camera_hwb_probe_descriptor_set,
};
use crate::spatial_public_multistack::{
    public_multistack_inactive_marker_fields, public_multistack_marker_fields,
};
use crate::spatial_public_multistack_runtime::{
    allocate_spatial_public_guide_targets, public_guide_targets_pending_marker_fields,
};
use crate::{bool_token, marker_token};

const CAMERA_HWB_PROBE_WAIT_FRAME_MS: u64 = 5000;
const CAMERA_HWB_PROBE_MAX_FRAMES: u32 = 1800;
const CAMERA_HWB_PROBE_LATEST_FRAME_WAIT_MS: u64 = 2;

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
    let present_mode = choose_present_mode(&present_modes);
    let extent = choose_extent(&capabilities, requested_width, requested_height);
    let image_count = choose_image_count(&capabilities);
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
        "status=render-loop-ready carrier=scenequadlayer-createAsAndroid-vulkan-wsi producerPath=Camera2-AImageReader-AHardwareBuffer-Vulkan-WSI swapchainImages={} extent={}x{} surfaceFormat={:?} presentMode={:?} compositeAlpha={:?} externalHwbExtensionReady={} samplerYcbcrExtensionReady={} samplerYcbcrFeatureReady={} outputMode={} rawCameraProjectionProbe={} stereoSource={} privateShaderStack=false customProjectionStack=false runtimeCrash=false {}",
        images.len(),
        extent.width,
        extent.height,
        surface_format.format,
        present_mode,
        composite_alpha,
        extension_status.external_hwb_extension_ready,
        extension_status.sampler_ycbcr_extension_ready,
        extension_status.sampler_ycbcr_feature_ready,
        mode.output_mode(),
        mode.raw_projection_token(),
        mode.stereo_source(),
        mode.projection_contract_marker_fields(),
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
    let mut current_left_hwb_import_sequence = current_left_frame.hwb_import_sequence;
    let mut current_right_hwb_import_sequence = current_right_frame.hwb_import_sequence;
    let mut transition_left_camera_image = true;
    let mut transition_right_camera_image = matches!(mode, CameraHwbProbeMode::RawColorProjection);
    let mut frames_presented = 0_u32;
    while (max_frames == 0 || frames_presented < max_frames)
        && !STOP_CAMERA_HWB_PROBE.load(Ordering::Acquire)
    {
        device
            .wait_for_fences(&[frame_fence], true, u64::MAX)
            .map_err(|error| format!("wait-fence-{error:?}"))?;
        device
            .reset_fences(&[frame_fence])
            .map_err(|error| format!("reset-fence-{error:?}"))?;
        if mode.should_stream_latest_frame() {
            if let Some(next_frame) = camera_runtime.wait_for_left_frame_after(
                current_left_hwb_import_sequence,
                Duration::from_millis(CAMERA_HWB_PROBE_LATEST_FRAME_WAIT_MS),
            ) {
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
                        sampled_left_image.destroy(&device);
                        sampled_left_image = next_sampled_image;
                        current_left_hwb_import_sequence = next_frame.hwb_import_sequence;
                        current_left_frame = next_frame;
                        transition_left_camera_image = true;
                    }
                    Err(error) => {
                        log_marker(format!(
                            "status=stream-frame-import-skipped side=left cameraId={} frameIndex={} hwbImportSequence={} error={} sampledLeftCameraTexture=true outputMode={} rawCameraProjectionProbe=true runtimeCrash=false",
                            marker_token(&next_frame.camera_id),
                            next_frame.frame_index,
                            next_frame.hwb_import_sequence,
                            marker_token(&error),
                            mode.output_mode(),
                        ));
                    }
                }
            }
            if let Some(next_frame) = camera_runtime.wait_for_right_frame_after(
                current_right_hwb_import_sequence,
                Duration::from_millis(CAMERA_HWB_PROBE_LATEST_FRAME_WAIT_MS),
            ) {
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
                        if let Some(previous) = sampled_right_image.take() {
                            previous.destroy(&device);
                        }
                        sampled_right_image = Some(next_sampled_image);
                        current_right_hwb_import_sequence = next_frame.hwb_import_sequence;
                        current_right_frame = next_frame;
                        transition_right_camera_image = true;
                    }
                    Err(error) => {
                        log_marker(format!(
                            "status=stream-frame-import-skipped side=right cameraId={} frameIndex={} hwbImportSequence={} error={} sampledRightCameraTexture=true outputMode={} rawCameraProjectionProbe=true runtimeCrash=false",
                            marker_token(&next_frame.camera_id),
                            next_frame.frame_index,
                            next_frame.hwb_import_sequence,
                            marker_token(&error),
                            mode.output_mode(),
                        ));
                    }
                }
            }
        }
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
        let command_buffer = command_buffers[image_index as usize];
        let public_stack_elapsed_seconds = render_started.elapsed().as_secs_f32();
        let projected_by_public_stack = record_camera_hwb_probe_command_buffer(
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
        )?;
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
        device
            .queue_submit(queue, &submit_info, frame_fence)
            .map_err(|error| format!("queue-submit-{error:?}"))?;
        let swapchains = [swapchain];
        let image_indices = [image_index];
        let present_info = vk::PresentInfoKHR::default()
            .wait_semaphores(&signal_semaphores)
            .swapchains(&swapchains)
            .image_indices(&image_indices);
        match swapchain_loader.queue_present(queue, &present_info) {
            Ok(_suboptimal) => {}
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
            Err(error) => return Err(format!("queue-present-{error:?}")),
        }
        frames_presented = frames_presented.saturating_add(1);
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
