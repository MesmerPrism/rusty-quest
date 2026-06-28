//! Video-only Spatial SDK SceneQuadLayer probe.
//!
//! This proves the public MediaCodec -> AImageReader -> AHardwareBuffer ->
//! Vulkan path without starting the headset camera stream.

use std::ffi::{c_void, CString};
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Instant;

use ash::vk;

use crate::acamera_sys::{ANativeWindow, ANativeWindow_release as ACameraNativeWindow_release};
use crate::camera_hwb_wsi::{
    choose_composite_alpha, choose_extent, choose_image_count, choose_present_mode,
    choose_surface_format, create_framebuffers, create_image_views, create_render_pass,
    select_camera_surface_device,
};
use crate::marker_token;
use crate::spatial_video_projection::{
    SpatialVideoProjectionFrameStats, SpatialVideoProjectionRenderer,
};
use crate::spatial_video_projection_marker::log_spatial_video_projection_marker as log_marker;
use crate::spatial_video_projection_native_stream::latest_spatial_video_projection_frame;
use crate::spatial_video_projection_settings::{
    spatial_video_projection_settings, SpatialVideoProjectionSettings,
};

const SPATIAL_VIDEO_PROJECTION_PROBE_MAX_FRAMES: u32 = 1800;

static STOP_SPATIAL_VIDEO_PROJECTION_PROBE: AtomicBool = AtomicBool::new(false);

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut vk::ANativeWindow;
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSpatialVideoProjectionProbe(
    env: *mut c_void,
    _thiz: *mut c_void,
    surface: *mut c_void,
    width: c_int,
    height: c_int,
    frame_count: c_int,
) -> i64 {
    let mut mask = 1_i64;
    if !surface.is_null() {
        mask |= 1 << 1;
    }
    if surface.is_null() || env.is_null() {
        log_marker(format!(
            "status=start-receipt startStatus=missing-env-or-surface startMask={} surfaceNonNull={} nativeWindowObtained=false renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false runtimeCrash=false",
            mask,
            !surface.is_null(),
        ));
        return mask;
    }

    let window = unsafe { ANativeWindow_fromSurface(env, surface) };
    if window.is_null() {
        log_marker(format!(
            "status=start-receipt startStatus=native-window-null startMask={} surfaceNonNull=true nativeWindowObtained=false renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false runtimeCrash=false",
            mask,
        ));
        return mask;
    }
    mask |= 1 << 2;
    STOP_SPATIAL_VIDEO_PROJECTION_PROBE.store(false, Ordering::Release);

    let window_addr = window as usize;
    let width = width.max(64) as u32;
    let height = height.max(64) as u32;
    let max_frames = if frame_count <= 0 {
        0
    } else {
        (frame_count.max(1) as u32).min(SPATIAL_VIDEO_PROJECTION_PROBE_MAX_FRAMES)
    };
    let requested_frames_marker = if max_frames == 0 {
        "unbounded".to_string()
    } else {
        max_frames.to_string()
    };
    let render_requested_frames_marker = requested_frames_marker.clone();
    let spawn_result = thread::Builder::new()
        .name("spatial-video-projection-probe".to_string())
        .spawn(move || {
            let window = window_addr as *mut vk::ANativeWindow;
            let started = Instant::now();
            let result = std::panic::catch_unwind(|| unsafe {
                render_spatial_video_projection_probe(window, width, height, max_frames)
            })
            .unwrap_or_else(|_| Err("panic".to_string()));
            unsafe {
                ACameraNativeWindow_release(window.cast::<ANativeWindow>());
            }
            match result {
                Ok(stats) => {
                    log_marker(format!(
                        "status=complete framesPresented={} requestedFrames={} frameLimit={} extent={}x{} carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false elapsedMs={} {} runtimeCrash=false",
                        stats.frames_presented,
                        render_requested_frames_marker,
                        if max_frames == 0 { "none" } else { "bounded" },
                        stats.extent.width,
                        stats.extent.height,
                        started.elapsed().as_millis(),
                        stats.video_stats.marker_fields(),
                    ));
                }
                Err(error) => {
                    log_marker(format!(
                        "status=render-failed carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false error={} runtimeCrash=false",
                        marker_token(&error),
                    ));
                }
            }
        });

    match spawn_result {
        Ok(_) => {
            mask |= 1 << 3;
            log_marker(format!(
                "status=start-receipt startStatus=started startMask={} surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=true requestedWidthPx={} requestedHeightPx={} requestedFrames={} frameLimit={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false runtimeCrash=false",
                mask,
                width,
                height,
                requested_frames_marker,
                if max_frames == 0 { "none" } else { "bounded" },
            ));
        }
        Err(error) => {
            unsafe {
                ACameraNativeWindow_release(window.cast::<ANativeWindow>());
            }
            log_marker(format!(
                "status=start-receipt startStatus=thread-spawn-{} startMask={} surfaceNonNull=true nativeWindowObtained=true renderThreadSpawned=false carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false runtimeCrash=false",
                error.kind(),
                mask,
            ));
        }
    }
    mask
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStopSpatialVideoProjectionProbe(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    STOP_SPATIAL_VIDEO_PROJECTION_PROBE.store(true, Ordering::Release);
    log_marker(
        "status=stop-requested carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true runtimeCrash=false"
            .to_string(),
    );
}

struct SpatialVideoProjectionProbeStats {
    frames_presented: u32,
    extent: vk::Extent2D,
    video_stats: SpatialVideoProjectionFrameStats,
}

unsafe fn render_spatial_video_projection_probe(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    max_frames: u32,
) -> Result<SpatialVideoProjectionProbeStats, String> {
    let settings = spatial_video_projection_settings();
    if !settings.active() {
        return Err(format!(
            "video-settings-inactive-{}",
            settings.marker_fields()
        ));
    }

    let entry = ash::Entry::load().map_err(|error| format!("vulkan-loader-{error}"))?;
    let app_name = CString::new("rusty-quest-spatial-camera-panel").expect("static app name");
    let engine_name = CString::new("spatial-video-projection-probe").expect("static engine name");
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
                "no-spatial-video-vulkan-device".to_string()
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
        "status=render-loop-ready carrier=scenequadlayer-createAsAndroid-vulkan-wsi producerPath=MediaCodec-AImageReader-AHardwareBuffer-Vulkan-WSI swapchainImages={} extent={}x{} surfaceFormat={:?} presentMode={:?} compositeAlpha={:?} externalHwbExtensionReady={} samplerYcbcrExtensionReady={} samplerYcbcrFeatureReady={} videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false sameSurfaceComposition=true {} runtimeCrash=false",
        images.len(),
        extent.width,
        extent.height,
        surface_format.format,
        present_mode,
        composite_alpha,
        extension_status.external_hwb_extension_ready,
        extension_status.sampler_ycbcr_extension_ready,
        extension_status.sampler_ycbcr_feature_ready,
        settings.marker_fields(),
    ));

    let memory_properties = instance.get_physical_device_memory_properties(physical_device);
    let mut renderer = SpatialVideoProjectionRenderer::new(
        &instance,
        &device,
        memory_properties,
        render_pass,
        true,
    );
    let mut frames_presented = 0_u32;
    let mut rendered_frame_markers = 0_u32;
    let mut last_stats =
        SpatialVideoProjectionFrameStats::unavailable(&settings, "waiting-for-decoded-frame");

    while (max_frames == 0 || frames_presented < max_frames)
        && !STOP_SPATIAL_VIDEO_PROJECTION_PROBE.load(Ordering::Acquire)
    {
        device
            .wait_for_fences(&[frame_fence], true, u64::MAX)
            .map_err(|error| format!("wait-fence-{error:?}"))?;
        device
            .reset_fences(&[frame_fence])
            .map_err(|error| format!("reset-fence-{error:?}"))?;
        renderer.retire_completed_frame_handles();
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
        let latest_video_frame = latest_spatial_video_projection_frame();
        let video_stats = record_spatial_video_projection_probe_command_buffer(
            &device,
            command_buffers[image_index as usize],
            render_pass,
            framebuffers[image_index as usize],
            extent,
            &mut renderer,
            latest_video_frame.as_ref(),
            &settings,
            image_index as usize,
        )?;
        last_stats = video_stats;

        let wait_semaphores = [image_available];
        let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let signal_semaphores = [render_finished];
        let submit_command_buffers = [command_buffers[image_index as usize]];
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
        if frames_presented <= 4 || (last_stats.rendered && rendered_frame_markers < 4) {
            if last_stats.rendered {
                rendered_frame_markers = rendered_frame_markers.saturating_add(1);
            }
            log_marker(format!(
                "status=video-frame-presented framesPresented={} carrier=scenequadlayer-createAsAndroid-vulkan-wsi videoOnlySpatialProjection=true cameraRuntimeStarted=false rawCameraProjectionProbe=false sameSurfaceComposition=true videoProjectionRendered={} spatialVideoProjectionRendered={} videoProjectionGpuImportReady={} {} {} runtimeCrash=false",
                frames_presented,
                last_stats.rendered,
                last_stats.rendered,
                last_stats.ready,
                settings.marker_fields(),
                last_stats.marker_fields(),
            ));
        }
    }

    device
        .device_wait_idle()
        .map_err(|error| format!("device-wait-idle-{error:?}"))?;
    renderer.destroy(&device);
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
    device.destroy_device(None);
    surface_loader.destroy_surface(surface, None);
    instance.destroy_instance(None);

    Ok(SpatialVideoProjectionProbeStats {
        frames_presented,
        extent,
        video_stats: last_stats,
    })
}

unsafe fn record_spatial_video_projection_probe_command_buffer(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
    renderer: &mut SpatialVideoProjectionRenderer,
    video_frame: Option<
        &crate::spatial_video_projection_native_stream::SpatialVideoProjectionFrame,
    >,
    settings: &SpatialVideoProjectionSettings,
    frame_slot: usize,
) -> Result<SpatialVideoProjectionFrameStats, String> {
    device
        .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
        .map_err(|error| format!("reset-command-buffer-{error:?}"))?;
    device
        .begin_command_buffer(command_buffer, &vk::CommandBufferBeginInfo::default())
        .map_err(|error| format!("begin-command-buffer-{error:?}"))?;

    let mut video_stats = SpatialVideoProjectionFrameStats::unavailable(
        settings,
        if settings.active() {
            "waiting-for-decoded-frame"
        } else {
            "disabled"
        },
    );
    let prepared_video = if let Some(frame) = video_frame {
        match renderer.prepare_frame(device, command_buffer, frame_slot, frame, settings) {
            Ok(Some(prepared)) => {
                video_stats = prepared.stats.clone();
                Some(prepared)
            }
            Ok(None) => None,
            Err(error) => {
                log_marker(format!(
                    "status=video-frame-prepare-skipped error={} videoOnlySpatialProjection=true {} runtimeCrash=false",
                    marker_token(&error),
                    settings.marker_fields(),
                ));
                video_stats =
                    SpatialVideoProjectionFrameStats::unavailable(settings, "prepare-error");
                None
            }
        }
    } else {
        None
    };

    begin_spatial_video_projection_render_pass(
        device,
        command_buffer,
        render_pass,
        framebuffer,
        extent,
    );
    if let Some(prepared) = prepared_video.as_ref() {
        renderer.record_video_eye(device, command_buffer, extent, 0, settings, prepared);
        renderer.record_video_eye(device, command_buffer, extent, 1, settings, prepared);
    }
    device.cmd_end_render_pass(command_buffer);
    device
        .end_command_buffer(command_buffer)
        .map_err(|error| format!("end-command-buffer-{error:?}"))?;
    Ok(video_stats)
}

unsafe fn begin_spatial_video_projection_render_pass(
    device: &ash::Device,
    command_buffer: vk::CommandBuffer,
    render_pass: vk::RenderPass,
    framebuffer: vk::Framebuffer,
    extent: vk::Extent2D,
) {
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: [0.0, 0.0, 0.0, 0.0],
        },
    }];
    let render_area = vk::Rect2D {
        offset: vk::Offset2D { x: 0, y: 0 },
        extent,
    };
    let render_pass_info = vk::RenderPassBeginInfo::default()
        .render_pass(render_pass)
        .framebuffer(framebuffer)
        .render_area(render_area)
        .clear_values(&clear_values);
    device.cmd_begin_render_pass(
        command_buffer,
        &render_pass_info,
        vk::SubpassContents::INLINE,
    );
}
