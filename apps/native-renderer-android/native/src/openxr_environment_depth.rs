//! OpenXR XR_META_environment_depth provider owner for native particle mapping.

use std::{ptr, time::Instant};

use openxr as xr;
use openxr::sys::Handle as _;

use crate::native_renderer_options::NativeEnvironmentDepthSettings;

const VIEW_COUNT: u32 = 2;
const DEPTH_FORMAT_LABEL: &str = "VK_FORMAT_D16_UNORM";
const DEPTH_TEXTURE_TRANSFORM_LABEL: &str = "rotate0+flipY";

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct OpenXrEnvironmentDepthProperties {
    pub(crate) extension_available: bool,
    pub(crate) supports_environment_depth: bool,
    pub(crate) supports_hand_removal: bool,
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct OpenXrEnvironmentDepthFrame {
    pub(crate) swapchain_index: u32,
    pub(crate) depth_width: u32,
    pub(crate) depth_height: u32,
    pub(crate) near_z: f32,
    pub(crate) far_z: f32,
    pub(crate) capture_time_ns: i64,
    pub(crate) depth_eye_position: [f32; 4],
    pub(crate) depth_eye_orientation_xyzw: [f32; 4],
    pub(crate) depth_fov_tangents: [f32; 4],
}

pub(crate) struct OpenXrEnvironmentDepthRuntime {
    extension: xr::raw::EnvironmentDepthMETA,
    provider: xr::sys::EnvironmentDepthProviderMETA,
    swapchain: xr::sys::EnvironmentDepthSwapchainMETA,
    depth_images: Vec<u64>,
    width: u32,
    height: u32,
    settings: NativeEnvironmentDepthSettings,
    supports_hand_removal: bool,
    hand_removal_enabled: bool,
    start_frame: u64,
    window_start: Instant,
    window_frame_count: u64,
    window_attempts: u64,
    window_acquired: u64,
    window_unavailable: u64,
    window_errors: u64,
    window_unique_capture_times: u64,
    window_acquire_cpu_ms: f64,
    total_attempts: u64,
    total_acquired: u64,
    total_unavailable: u64,
    total_errors: u64,
    total_unique_capture_times: u64,
    repeated_capture_time_count: u64,
    last_capture_time_ns: Option<i64>,
    last_acquired_frame: Option<u64>,
    last_swapchain_index: Option<u32>,
    last_near_z: f32,
    last_far_z: f32,
}

impl OpenXrEnvironmentDepthRuntime {
    pub(crate) fn query_properties(
        instance: &xr::Instance,
        system: xr::SystemId,
        settings: NativeEnvironmentDepthSettings,
    ) -> OpenXrEnvironmentDepthProperties {
        if instance.exts().meta_environment_depth.is_none() {
            return OpenXrEnvironmentDepthProperties::default();
        }

        let mut depth_properties = xr::sys::SystemEnvironmentDepthPropertiesMETA {
            ty: xr::sys::SystemEnvironmentDepthPropertiesMETA::TYPE,
            next: ptr::null_mut(),
            supports_environment_depth: false.into(),
            supports_hand_removal: false.into(),
        };
        let mut system_properties =
            xr::sys::SystemProperties::out(&mut depth_properties as *mut _ as *mut _);
        let result = unsafe {
            (instance.fp().get_system_properties)(
                instance.as_raw(),
                system,
                system_properties.as_mut_ptr(),
            )
        };
        if result.into_raw() < xr::sys::Result::SUCCESS.into_raw() {
            crate::android_log_error(format!(
                "Rusty Quest environment depth properties query failed result={result:?}"
            ));
            return OpenXrEnvironmentDepthProperties {
                extension_available: true,
                ..OpenXrEnvironmentDepthProperties::default()
            };
        }

        let properties = OpenXrEnvironmentDepthProperties {
            extension_available: true,
            supports_environment_depth: depth_properties.supports_environment_depth.into(),
            supports_hand_removal: depth_properties.supports_hand_removal.into(),
        };
        crate::marker(
            "environment-depth",
            format!(
                "status=properties environmentDepthExtensionAvailable={} environmentDepthSupported={} environmentDepthHandRemovalSupported={} environmentDepthFormat={} environmentDepthLayerCount={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthProviderAvailable={}",
                properties.extension_available,
                properties.supports_environment_depth,
                properties.supports_hand_removal,
                DEPTH_FORMAT_LABEL,
                VIEW_COUNT,
                settings.source_view_count(),
                settings.sampled_layer_mask(),
                settings.layer_policy_marker_value(),
                properties.supports_environment_depth
            ),
        );
        properties
    }

    pub(crate) fn create<G: xr::Graphics>(
        instance: &xr::Instance,
        session: &xr::Session<G>,
        settings: NativeEnvironmentDepthSettings,
        properties: OpenXrEnvironmentDepthProperties,
        start_frame: u64,
    ) -> Result<Self, String> {
        if !settings.runtime_provider_requested() || !settings.mode_enabled() {
            return Err(format!(
                "environment depth runtime provider not requested mode={} source={}",
                settings.mode_marker_value(),
                settings.source_marker_value()
            ));
        }
        if !properties.extension_available || instance.exts().meta_environment_depth.is_none() {
            return Err("XR_META_environment_depth is unavailable".to_string());
        }
        if !properties.supports_environment_depth {
            return Err(
                "XR_META_environment_depth system properties report unsupported".to_string(),
            );
        }

        let extension = *instance
            .exts()
            .meta_environment_depth
            .as_ref()
            .ok_or_else(|| "XR_META_environment_depth function table is unavailable".to_string())?;
        let provider_info = xr::sys::EnvironmentDepthProviderCreateInfoMETA {
            ty: xr::sys::EnvironmentDepthProviderCreateInfoMETA::TYPE,
            next: ptr::null(),
            create_flags: xr::sys::EnvironmentDepthProviderCreateFlagsMETA::EMPTY,
        };
        let mut provider = xr::sys::EnvironmentDepthProviderMETA::NULL;
        let result = unsafe {
            (extension.create_environment_depth_provider)(
                session.as_raw(),
                &provider_info,
                &mut provider,
            )
        };
        ensure_xr_success(result, "xrCreateEnvironmentDepthProviderMETA")?;

        let swapchain_info = xr::sys::EnvironmentDepthSwapchainCreateInfoMETA {
            ty: xr::sys::EnvironmentDepthSwapchainCreateInfoMETA::TYPE,
            next: ptr::null(),
            create_flags: xr::sys::EnvironmentDepthSwapchainCreateFlagsMETA::EMPTY,
        };
        let mut swapchain = xr::sys::EnvironmentDepthSwapchainMETA::NULL;
        let result = unsafe {
            (extension.create_environment_depth_swapchain)(
                provider,
                &swapchain_info,
                &mut swapchain,
            )
        };
        if let Err(error) = ensure_xr_success(result, "xrCreateEnvironmentDepthSwapchainMETA") {
            destroy_provider(&extension, provider);
            return Err(error);
        }

        let mut swapchain_state = xr::sys::EnvironmentDepthSwapchainStateMETA {
            ty: xr::sys::EnvironmentDepthSwapchainStateMETA::TYPE,
            next: ptr::null_mut(),
            width: 0,
            height: 0,
        };
        let result = unsafe {
            (extension.get_environment_depth_swapchain_state)(swapchain, &mut swapchain_state)
        };
        if let Err(error) = ensure_xr_success(result, "xrGetEnvironmentDepthSwapchainStateMETA") {
            destroy_swapchain(&extension, swapchain);
            destroy_provider(&extension, provider);
            return Err(error);
        }

        let depth_images =
            match unsafe { enumerate_environment_depth_swapchain_images(&extension, swapchain) } {
                Ok(images) => images,
                Err(error) => {
                    destroy_swapchain(&extension, swapchain);
                    destroy_provider(&extension, provider);
                    return Err(error);
                }
            };

        let hand_removal_enabled = false;
        let result = unsafe { (extension.start_environment_depth_provider)(provider) };
        if let Err(error) = ensure_xr_success(result, "xrStartEnvironmentDepthProviderMETA") {
            destroy_swapchain(&extension, swapchain);
            destroy_provider(&extension, provider);
            return Err(error);
        }

        crate::marker(
            "environment-depth",
            format!(
                "status=provider-created environmentDepthSource={} environmentDepthProviderState=provider-running environmentDepthProviderAvailable=true environmentDepthRealProviderBound=true environmentDepthSupported=true environmentDepthImageSize={}x{} environmentDepthFormat={} environmentDepthLayerCount={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthReferenceSpace={} environmentDepthHandRemovalSupported={} environmentDepthHandRemovalEnabled={} environmentDepthSwapchainImages={} environmentDepthTextureTransform={}",
                settings.source_marker_value(),
                swapchain_state.width,
                swapchain_state.height,
                DEPTH_FORMAT_LABEL,
                VIEW_COUNT,
                settings.source_view_count(),
                settings.sampled_layer_mask(),
                settings.layer_policy_marker_value(),
                settings.reference_space_marker_value(),
                properties.supports_hand_removal,
                hand_removal_enabled,
                depth_images.len(),
                DEPTH_TEXTURE_TRANSFORM_LABEL,
            ),
        );

        Ok(Self {
            extension,
            provider,
            swapchain,
            depth_images,
            width: swapchain_state.width,
            height: swapchain_state.height,
            settings,
            supports_hand_removal: properties.supports_hand_removal,
            hand_removal_enabled,
            start_frame,
            window_start: Instant::now(),
            window_frame_count: 0,
            window_attempts: 0,
            window_acquired: 0,
            window_unavailable: 0,
            window_errors: 0,
            window_unique_capture_times: 0,
            window_acquire_cpu_ms: 0.0,
            total_attempts: 0,
            total_acquired: 0,
            total_unavailable: 0,
            total_errors: 0,
            total_unique_capture_times: 0,
            repeated_capture_time_count: 0,
            last_capture_time_ns: None,
            last_acquired_frame: None,
            last_swapchain_index: None,
            last_near_z: 0.0,
            last_far_z: 0.0,
        })
    }

    pub(crate) fn depth_image_handles(&self) -> &[u64] {
        &self.depth_images
    }

    pub(crate) fn width(&self) -> u32 {
        self.width
    }

    pub(crate) fn height(&self) -> u32 {
        self.height
    }

    pub(crate) fn acquire(
        &mut self,
        acquire_space: &xr::Space,
        display_time: xr::Time,
        current_views: &[xr::View],
        frame_count: u64,
    ) -> Option<OpenXrEnvironmentDepthFrame> {
        self.window_frame_count = self.window_frame_count.saturating_add(1);
        self.window_attempts = self.window_attempts.saturating_add(1);
        self.total_attempts = self.total_attempts.saturating_add(1);

        let acquire_info = xr::sys::EnvironmentDepthImageAcquireInfoMETA {
            ty: xr::sys::EnvironmentDepthImageAcquireInfoMETA::TYPE,
            next: ptr::null(),
            space: acquire_space.as_raw(),
            display_time,
        };
        let mut timestamp = xr::sys::EnvironmentDepthImageTimestampMETA {
            ty: xr::sys::EnvironmentDepthImageTimestampMETA::TYPE,
            next: ptr::null(),
            capture_time: xr::Time::from_nanos(0),
        };
        let empty_view = xr::sys::EnvironmentDepthImageViewMETA {
            ty: xr::sys::EnvironmentDepthImageViewMETA::TYPE,
            next: ptr::null(),
            fov: xr::sys::Fovf::default(),
            pose: xr::sys::Posef::default(),
        };
        let mut image = xr::sys::EnvironmentDepthImageMETA {
            ty: xr::sys::EnvironmentDepthImageMETA::TYPE,
            next: &mut timestamp as *mut _ as *const _,
            swapchain_index: 0,
            near_z: 0.0,
            far_z: 0.0,
            views: [empty_view; 2],
        };

        let started = Instant::now();
        let result = unsafe {
            (self.extension.acquire_environment_depth_image)(
                self.provider,
                &acquire_info,
                &mut image,
            )
        };
        let acquire_ms = started.elapsed().as_secs_f64() * 1000.0;
        self.window_acquire_cpu_ms += acquire_ms;

        if result == xr::sys::Result::ENVIRONMENT_DEPTH_NOT_AVAILABLE_META {
            self.window_unavailable = self.window_unavailable.saturating_add(1);
            self.total_unavailable = self.total_unavailable.saturating_add(1);
            self.report_status_if_due(frame_count, acquire_ms, false);
            return None;
        }
        if result.into_raw() < xr::sys::Result::SUCCESS.into_raw() {
            self.window_errors = self.window_errors.saturating_add(1);
            self.total_errors = self.total_errors.saturating_add(1);
            crate::android_log_error(format!(
                "Rusty Quest environment depth acquire failed frame={} result={result:?}",
                frame_count
            ));
            self.report_status_if_due(frame_count, acquire_ms, false);
            return None;
        }

        self.window_acquired = self.window_acquired.saturating_add(1);
        self.total_acquired = self.total_acquired.saturating_add(1);
        let capture_time_ns = timestamp.capture_time.as_nanos();
        if self.last_capture_time_ns == Some(capture_time_ns) {
            self.repeated_capture_time_count = self.repeated_capture_time_count.saturating_add(1);
        } else {
            self.window_unique_capture_times = self.window_unique_capture_times.saturating_add(1);
            self.total_unique_capture_times = self.total_unique_capture_times.saturating_add(1);
            self.last_capture_time_ns = Some(capture_time_ns);
        }
        self.last_acquired_frame = Some(frame_count);
        self.last_swapchain_index = Some(image.swapchain_index);
        self.last_near_z = image.near_z;
        self.last_far_z = image.far_z;

        if self.total_acquired == 1 {
            crate::marker(
                "environment-depth",
                format!(
                    "status=first-frame environmentDepthSource=xr-meta-environment-depth environmentDepthAcquireStatus=acquired environmentDepthProviderState=provider-running environmentDepthProviderAvailable=true environmentDepthRealProviderBound=true environmentDepthSupported=true environmentDepthImageSize={}x{} environmentDepthFormat={} environmentDepthLayerCount={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthDepthViewPoseValidMask={} environmentDepthDepthViewFovValidMask={} environmentDepthSwapchainIndex={} environmentDepthPoseValid=true environmentDepthNearM={:.3} environmentDepthFarM={:.3} environmentDepthCaptureTimeNs={} environmentDepthTextureTransform={} confidenceSource=depth-discontinuity-or-none confidencePayload=false",
                    self.width,
                    self.height,
                    DEPTH_FORMAT_LABEL,
                    VIEW_COUNT,
                    self.settings.source_view_count(),
                    self.settings.sampled_layer_mask(),
                    self.settings.layer_policy_marker_value(),
                    self.settings.sampled_layer_mask(),
                    self.settings.sampled_layer_mask(),
                    image.swapchain_index,
                    image.near_z,
                    image.far_z,
                    capture_time_ns,
                    DEPTH_TEXTURE_TRANSFORM_LABEL
                ),
            );
        }
        self.report_status_if_due(frame_count, acquire_ms, true);

        let depth_view = image.views[self.settings.source_view_index()];
        let render_view = current_views.first().copied();
        let render_fov = render_view.map(|view| view.fov).unwrap_or(depth_view.fov);
        let render_pose = render_view.map(|view| view.pose).unwrap_or(depth_view.pose);
        Some(OpenXrEnvironmentDepthFrame {
            swapchain_index: image.swapchain_index,
            depth_width: self.width,
            depth_height: self.height,
            near_z: image.near_z,
            far_z: image.far_z,
            capture_time_ns,
            depth_eye_position: pose_position(depth_view.pose),
            depth_eye_orientation_xyzw: pose_orientation(depth_view.pose),
            depth_fov_tangents: fov_tangents(depth_view.fov),
        })
        .filter(|_| {
            depth_view.fov.angle_left.is_finite()
                && depth_view.fov.angle_right.is_finite()
                && depth_view.pose.orientation.w.is_finite()
                && render_fov.angle_left.is_finite()
                && render_pose.orientation.w.is_finite()
        })
    }

    fn report_status_if_due(&mut self, frame_count: u64, last_acquire_ms: f64, acquired: bool) {
        if frame_count != self.start_frame
            && frame_count % 120 != 0
            && !(acquired && self.total_acquired == 1)
        {
            return;
        }

        let elapsed = self.window_start.elapsed().as_secs_f64().max(0.001);
        let observed_openxr_fps = self.window_frame_count as f64 / elapsed;
        let observed_acquire_hz = self.window_attempts as f64 / elapsed;
        let observed_unique_depth_hz = self.window_unique_capture_times as f64 / elapsed;
        let avg_acquire_ms = if self.window_attempts > 0 {
            self.window_acquire_cpu_ms / self.window_attempts as f64
        } else {
            0.0
        };
        crate::marker(
            "environment-depth",
            format!(
                "status=runtime frame={} environmentDepthSource=xr-meta-environment-depth environmentDepthProviderState=provider-running environmentDepthProviderAvailable=true environmentDepthRealProviderBound=true environmentDepthSupported=true environmentDepthAcquireStatus={} environmentDepthImageSize={}x{} environmentDepthFormat={} environmentDepthLayerCount={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthDepthViewPoseValidMask={} environmentDepthDepthViewFovValidMask={} environmentDepthSwapchainIndex={} environmentDepthPoseValid={} openXrFrameCount={} observedOpenXrFps={:.1} acquireAttempts={} acquiredFrames={} unavailableFrames={} acquireErrors={} uniqueCaptureTimes={} repeatedCaptureTimes={} observedAcquireHz={:.1} observedDepthHz={:.1} lastAcquireCpuMs={:.3} avgAcquireCpuMs={:.3} captureTimeNs={} nearZ={:.3} farZ={:.3} handRemovalSupported={} handRemovalEnabled={} confidenceSource=depth-discontinuity-or-none confidencePayload=false textureTransform={}",
                frame_count,
                if acquired { "acquired" } else { "not-available" },
                self.width,
                self.height,
                DEPTH_FORMAT_LABEL,
                VIEW_COUNT,
                self.settings.source_view_count(),
                self.settings.sampled_layer_mask(),
                self.settings.layer_policy_marker_value(),
                if self.last_acquired_frame.is_some() {
                    self.settings.sampled_layer_mask()
                } else {
                    "0x0"
                },
                if self.last_acquired_frame.is_some() {
                    self.settings.sampled_layer_mask()
                } else {
                    "0x0"
                },
                self.last_swapchain_index
                    .map(|value| value.to_string())
                    .unwrap_or_else(|| "none".to_string()),
                self.last_acquired_frame.is_some(),
                frame_count,
                observed_openxr_fps,
                self.total_attempts,
                self.total_acquired,
                self.total_unavailable,
                self.total_errors,
                self.total_unique_capture_times,
                self.repeated_capture_time_count,
                observed_acquire_hz,
                observed_unique_depth_hz,
                last_acquire_ms,
                avg_acquire_ms,
                self.last_capture_time_ns
                    .map(|value| value.to_string())
                    .unwrap_or_else(|| "none".to_string()),
                self.last_near_z,
                self.last_far_z,
                self.supports_hand_removal,
                self.hand_removal_enabled,
                DEPTH_TEXTURE_TRANSFORM_LABEL
            ),
        );
        self.window_start = Instant::now();
        self.window_frame_count = 0;
        self.window_attempts = 0;
        self.window_acquired = 0;
        self.window_unavailable = 0;
        self.window_errors = 0;
        self.window_unique_capture_times = 0;
        self.window_acquire_cpu_ms = 0.0;
    }
}

impl Drop for OpenXrEnvironmentDepthRuntime {
    fn drop(&mut self) {
        unsafe {
            let stop_result = (self.extension.stop_environment_depth_provider)(self.provider);
            if stop_result.into_raw() < xr::sys::Result::SUCCESS.into_raw()
                && stop_result != xr::sys::Result::ERROR_HANDLE_INVALID
            {
                crate::android_log_error(format!(
                    "Rusty Quest environment depth provider stop failed result={stop_result:?}"
                ));
            }
            if self.swapchain != xr::sys::EnvironmentDepthSwapchainMETA::NULL {
                destroy_swapchain(&self.extension, self.swapchain);
                self.swapchain = xr::sys::EnvironmentDepthSwapchainMETA::NULL;
            }
            if self.provider != xr::sys::EnvironmentDepthProviderMETA::NULL {
                destroy_provider(&self.extension, self.provider);
                self.provider = xr::sys::EnvironmentDepthProviderMETA::NULL;
            }
        }
    }
}

unsafe fn enumerate_environment_depth_swapchain_images(
    extension: &xr::raw::EnvironmentDepthMETA,
    swapchain: xr::sys::EnvironmentDepthSwapchainMETA,
) -> Result<Vec<u64>, String> {
    let mut image_count = 0;
    ensure_xr_success(
        (extension.enumerate_environment_depth_swapchain_images)(
            swapchain,
            0,
            &mut image_count,
            ptr::null_mut(),
        ),
        "xrEnumerateEnvironmentDepthSwapchainImagesMETA(count)",
    )?;
    if image_count == 0 {
        return Err("environment depth swapchain returned no Vulkan images".to_string());
    }

    let mut images = vec![
        xr::sys::SwapchainImageVulkanKHR {
            ty: xr::sys::SwapchainImageVulkanKHR::TYPE,
            next: ptr::null_mut(),
            image: 0,
        };
        image_count as usize
    ];
    let mut enumerated = 0;
    ensure_xr_success(
        (extension.enumerate_environment_depth_swapchain_images)(
            swapchain,
            image_count,
            &mut enumerated,
            images.as_mut_ptr() as *mut xr::sys::SwapchainImageBaseHeader,
        ),
        "xrEnumerateEnvironmentDepthSwapchainImagesMETA",
    )?;
    images.truncate(enumerated as usize);
    if images.is_empty() {
        return Err(
            "environment depth swapchain image enumeration returned zero images".to_string(),
        );
    }
    for (index, image) in images.iter().enumerate() {
        if image.image == 0 {
            return Err(format!(
                "environment depth swapchain image {index} returned a null VkImage"
            ));
        }
        crate::marker(
            "environment-depth",
            format!(
                "status=swapchain-image index={} environmentDepthVkImage=0x{:x} environmentDepthFormat={} environmentDepthLayerCount={}",
                index,
                image.image,
                DEPTH_FORMAT_LABEL,
                VIEW_COUNT
            ),
        );
    }
    Ok(images.into_iter().map(|image| image.image).collect())
}

fn destroy_swapchain(
    extension: &xr::raw::EnvironmentDepthMETA,
    swapchain: xr::sys::EnvironmentDepthSwapchainMETA,
) {
    let result = unsafe { (extension.destroy_environment_depth_swapchain)(swapchain) };
    if result.into_raw() < xr::sys::Result::SUCCESS.into_raw() {
        crate::android_log_error(format!(
            "Rusty Quest environment depth swapchain destroy failed result={result:?}"
        ));
    }
}

fn destroy_provider(
    extension: &xr::raw::EnvironmentDepthMETA,
    provider: xr::sys::EnvironmentDepthProviderMETA,
) {
    let result = unsafe { (extension.destroy_environment_depth_provider)(provider) };
    if result.into_raw() < xr::sys::Result::SUCCESS.into_raw() {
        crate::android_log_error(format!(
            "Rusty Quest environment depth provider destroy failed result={result:?}"
        ));
    }
}

fn ensure_xr_success(result: xr::sys::Result, operation: &str) -> Result<(), String> {
    if result.into_raw() < xr::sys::Result::SUCCESS.into_raw() {
        return Err(format!("{operation} failed: {result:?}"));
    }
    Ok(())
}

fn fov_tangents(fov: xr::sys::Fovf) -> [f32; 4] {
    [
        fov.angle_left.tan(),
        fov.angle_right.tan(),
        fov.angle_down.tan(),
        fov.angle_up.tan(),
    ]
}

fn pose_position(pose: xr::sys::Posef) -> [f32; 4] {
    [pose.position.x, pose.position.y, pose.position.z, 0.0]
}

fn pose_orientation(pose: xr::sys::Posef) -> [f32; 4] {
    [
        pose.orientation.x,
        pose.orientation.y,
        pose.orientation.z,
        pose.orientation.w,
    ]
}
