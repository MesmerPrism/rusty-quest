//! Minimal OpenXR/Vulkan prerequisite probe for the Quest-native renderer.

use std::{
    ffi::{CStr, CString},
    ptr,
    time::{Duration, Instant},
};

use ash::vk::{self, Handle};
use openxr as xr;
use openxr::sys::Handle as _;

use crate::{
    camera_projection::{
        record_camera_projection_eye, CameraProjectionFrameStats, CameraProjectionRenderer,
        PreparedCameraProjection,
    },
    camera_projection_metadata::{CameraProjectionMetadata, TargetRect},
    gpu_environment_depth_particles::{
        GpuEnvironmentDepthParticleFrameStats, GpuEnvironmentDepthParticleRenderer,
    },
    gpu_hand_anchor_particles::{
        GpuHandAnchorParticleFrameSetStats, GpuHandAnchorParticleRenderer,
    },
    gpu_hand_mesh_visual::{
        GpuHandMeshVisualFrameSetStats, GpuHandMeshVisualFrameStats, GpuHandMeshVisualRenderer,
        HandMeshVisualEyeProjection,
    },
    gpu_mesh_replay::{GpuMeshReplayResources, GpuMeshReplayStats},
    gpu_sdf_field::{GpuSdfFieldFrameStats, GpuSdfFieldRenderer},
    gpu_stimulus_volume::{GpuStimulusVolumeFrameStats, GpuStimulusVolumeRenderer},
    guide_blur_graph::{GuideBlurGraphFrameStats, GuideBlurGraphRenderer},
    live_hand_compact::{LiveHandCompactFrameSet, LiveHandCompactInput, LiveHandCompactStats},
    manifold_breath_bridge::ManifoldBreathBridge,
    native_camera::NativeCameraRuntime,
    native_renderer_options::{
        CompactHandInputSourceMode, HandMeshVisualDiagnosticSettings, NativeCameraOutputMode,
        NativeCameraQualityProfile, NativeCameraSyncMode, NativeEnvironmentDepthSettings,
        NativeGuideGraphResolution, NativeHandAnchorParticleOrderingMode,
        NativeHandAnchorParticleSettings, NativePrivateLayerSettings,
        NativeProjectionBorderStretchSettings, NativeRendererRenderMode,
        NativeRendererRuntimeOptions, NativeStimulusVolumeSettings, NativeSwapchainColorFormatMode,
        PROP_CAMERA_DIRECT_BORDER_OPACITY, PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED,
        PROP_CAMERA_OUTPUT_MODE, PROP_CAMERA_QUALITY_PROFILE, PROP_CAMERA_READER_MAX_IMAGES,
        PROP_CAMERA_RESOLUTION_PROFILE, PROP_CAMERA_STEREO_PAIRING, PROP_CAMERA_SYNC_MODE,
        PROP_CAMERA_YCBCR_MODE, PROP_ENABLE_SDF_VISUAL, PROP_GUIDE_BLUR_ENABLED,
        PROP_GUIDE_RESOLUTION, PROP_HAND_ANCHOR_PARTICLES_ENABLED,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE, PROP_HAND_ANCHOR_PARTICLES_PER_HAND,
        PROP_HAND_ANCHOR_PARTICLES_RADIUS_M, PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
        PROP_HAND_MESH_GRAFT_COPIES_ENABLED, PROP_HAND_MESH_GRAFT_COPY_SCALE,
        PROP_HAND_MESH_INPUT_SOURCE, PROP_HAND_MESH_REAL_HANDS_VISIBLE,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, PROP_PROCESSING_LAYER, PROP_RENDER_MODE,
        PROP_REPLAY_VISUAL_PROOF_ENABLED, PROP_SWAPCHAIN_COLOR_FORMAT_MODE,
    },
    native_renderer_timing::{
        elapsed_ms, FrameCpuTimings, GpuStageTimings, GpuTimestampStage, GpuTimestampTracker,
    },
    openxr_environment_depth::OpenXrEnvironmentDepthRuntime,
    openxr_stimulus_actions::StimulusVolumeActions,
    private_extension_slot::{PrivateExtensionSlotFrameStats, PrivateExtensionSlotRuntime},
    projection_target_state::{ProjectionTargetSettings, ProjectionTargetState},
    recorded_hand_replay::{
        RecordedHandReplaySet, RecordedHandReplaySummary, RecordedHandSkinningFrame,
    },
};

const VIEW_TYPE: xr::ViewConfigurationType = xr::ViewConfigurationType::PRIMARY_STEREO;
const VIEW_COUNT: u32 = 2;
const VIEW_COUNT_USIZE: usize = 2;
const PIPELINE_DEPTH: u32 = 2;

#[derive(Clone, Debug, Default)]
pub(crate) struct XrVulkanReadiness {
    pub(crate) android_loader_ready: bool,
    pub(crate) openxr_instance_ready: bool,
    pub(crate) vulkan_instance_ready: bool,
    pub(crate) external_hwb_extension_ready: bool,
    pub(crate) sampler_ycbcr_extension_ready: bool,
    pub(crate) sampler_ycbcr_feature_ready: bool,
    pub(crate) vulkan_external_import_prereqs_ready: bool,
    pub(crate) live_hand_tracking_extension_available: bool,
    pub(crate) live_hand_tracking_extension_enabled: bool,
    pub(crate) live_hand_tracking_system_supported: bool,
}

impl XrVulkanReadiness {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "androidOpenxrLoaderReady={} openxrInstanceReady={} vulkanInstanceReady={} externalHwbExtensionReady={} samplerYcbcrExtensionReady={} samplerYcbcrFeatureReady={} vulkanExternalImportPrereqsReady={} liveMetaHandTrackingExtensionAvailable={} liveMetaHandTrackingExtensionEnabled={} liveMetaHandTrackingSystemSupported={} openxrSubmitReady=false vulkanExternalImportReady=false",
            self.android_loader_ready,
            self.openxr_instance_ready,
            self.vulkan_instance_ready,
            self.external_hwb_extension_ready,
            self.sampler_ycbcr_extension_ready,
            self.sampler_ycbcr_feature_ready,
            self.vulkan_external_import_prereqs_ready,
            self.live_hand_tracking_extension_available,
            self.live_hand_tracking_extension_enabled,
            self.live_hand_tracking_system_supported
        )
    }
}

#[derive(Clone, Debug)]
struct VulkanProbe {
    device_name: String,
    api_version: u32,
    external_hwb_extension_ready: bool,
    sampler_ycbcr_extension_ready: bool,
    sampler_ycbcr_feature_ready: bool,
}

pub(crate) fn probe(app: &android_activity::AndroidApp) -> XrVulkanReadiness {
    let started = Instant::now();
    let mut readiness = XrVulkanReadiness::default();
    match unsafe { probe_inner(app, &mut readiness) } {
        Ok(()) => {
            crate::marker(
                "xr-vulkan-probe",
                format!(
                    "status=ok elapsedMs={} {}",
                    started.elapsed().as_millis(),
                    readiness.marker_fields()
                ),
            );
        }
        Err(error) => {
            crate::marker(
                "xr-vulkan-probe",
                format!(
                    "status=error elapsedMs={} reason={} {}",
                    started.elapsed().as_millis(),
                    crate::sanitize(&error),
                    readiness.marker_fields()
                ),
            );
        }
    }
    readiness
}

pub(crate) fn run_projection_loop(
    app: &android_activity::AndroidApp,
    camera_runtime: Option<&NativeCameraRuntime>,
    runtime_options: NativeRendererRuntimeOptions,
) -> Result<(), String> {
    let replay_set = RecordedHandReplaySet::load()?;
    let projection_metadata = CameraProjectionMetadata::load();
    crate::marker(
        "recorded-hand-replay",
        format!(
            "status=loaded {} {} visualEffect=target-local-hand-mesh-overlay compactJointOverlayDefault=false animatedHandMeshVisualReady=pending dynamicSdfReady=pending gpuSdfFieldReady=pending cpuSdfPerFrame=false",
            replay_set.left.marker_fields(),
            replay_set.marker_fields()
        ),
    );
    crate::marker(
        "camera-projection-metadata",
        format!("status=loaded {}", projection_metadata.marker_fields()),
    );

    let started = Instant::now();
    let result = unsafe {
        run_projection_loop_inner(
            app,
            camera_runtime,
            runtime_options,
            replay_set,
            projection_metadata,
        )
    };
    if let Err(error) = &result {
        crate::marker(
            "openxr-projection",
            format!(
                "status=error elapsedMs={} reason={} openxrSubmitReady=false vulkanExternalImportReady=false",
                started.elapsed().as_millis(),
                crate::sanitize(error)
            ),
        );
    }
    result
}

unsafe fn run_projection_loop_inner(
    app: &android_activity::AndroidApp,
    camera_runtime: Option<&NativeCameraRuntime>,
    runtime_options: NativeRendererRuntimeOptions,
    replay_set: RecordedHandReplaySet,
    projection_metadata: CameraProjectionMetadata,
) -> Result<(), String> {
    let replay = &replay_set.left;
    let secondary_replay = &replay_set.right;
    let entry = xr::Entry::load().map_err(|error| format!("load OpenXR: {error}"))?;
    initialize_android_loader(&entry, app)?;

    let available_extensions = entry
        .enumerate_extensions()
        .map_err(|error| format!("enumerate OpenXR extensions: {error}"))?;
    if !available_extensions.khr_android_create_instance {
        return Err("OpenXR runtime does not expose XR_KHR_android_create_instance".to_string());
    }
    if !available_extensions.khr_vulkan_enable2 {
        return Err("OpenXR runtime does not expose XR_KHR_vulkan_enable2".to_string());
    }

    let mut enabled_extensions = xr::ExtensionSet::default();
    enabled_extensions.khr_android_create_instance = true;
    enabled_extensions.khr_vulkan_enable2 = true;
    enabled_extensions.ext_hand_tracking = available_extensions.ext_hand_tracking;
    enabled_extensions.fb_passthrough = runtime_options.render_mode.uses_native_passthrough()
        && available_extensions.fb_passthrough;
    enabled_extensions.meta_environment_depth = runtime_options
        .environment_depth_settings
        .runtime_provider_requested()
        && available_extensions.meta_environment_depth;
    if runtime_options
        .environment_depth_settings
        .runtime_provider_requested()
        && !available_extensions.meta_environment_depth
    {
        crate::marker(
            "environment-depth",
            "status=unavailable reason=XR_META_environment_depth-extension-missing environmentDepthProviderAvailable=false environmentDepthRealProviderBound=false environmentDepthSupported=false",
        );
    }
    let xr_instance = create_android_instance(
        &entry,
        app,
        &xr::ApplicationInfo {
            application_name: "Rusty Quest Native Renderer",
            application_version: 1,
            engine_name: "Rusty Quest",
            engine_version: 1,
            api_version: xr::Version::new(1, 0, 0),
        },
        &enabled_extensions,
        &[],
    )?;
    let mut stimulus_actions = match StimulusVolumeActions::new(
        &xr_instance,
        runtime_options.stimulus_volume_settings,
        runtime_options.projection_target_settings.clone(),
    ) {
        Ok(actions) => actions,
        Err(error) => {
            crate::marker(
                "stimulus-volume-input",
                format!(
                    "status=unavailable reason={} actionSetAttached=false rightControllerPrimaryButtonRandomize=false",
                    crate::sanitize(&error)
                ),
            );
            None
        }
    };

    let properties = xr_instance
        .properties()
        .map_err(|error| format!("read OpenXR properties: {error}"))?;
    let system = xr_instance
        .system(xr::FormFactor::HEAD_MOUNTED_DISPLAY)
        .map_err(|error| format!("get HMD system: {error}"))?;
    let environment_depth_properties = OpenXrEnvironmentDepthRuntime::query_properties(
        &xr_instance,
        system,
        runtime_options.environment_depth_settings,
    );
    let live_hand_tracking_system_supported = if enabled_extensions.ext_hand_tracking {
        xr_instance.supports_hand_tracking(system).unwrap_or(false)
    } else {
        false
    };
    let environment_blend_modes = xr_instance
        .enumerate_environment_blend_modes(system, VIEW_TYPE)
        .map_err(|error| format!("enumerate OpenXR environment blend modes: {error}"))?;
    let alpha_blend_supported =
        environment_blend_modes.contains(&xr::EnvironmentBlendMode::ALPHA_BLEND);
    let environment_blend_mode = xr::EnvironmentBlendMode::OPAQUE;
    crate::marker(
        "native-passthrough",
        format!(
            "status=config renderMode={} nativePassthroughRequested={} solidBlackBackground={} fbPassthroughAvailable={} fbPassthroughEnabled={} alphaBlendSupported={} environmentBlendModes={} environmentBlendMode={:?} projectionLayerAlphaBlend={} cameraRuntimeMode={} cameraProjectionPath={}",
            runtime_options.render_mode.marker_value(),
            runtime_options.render_mode.uses_native_passthrough(),
            runtime_options.render_mode.uses_solid_black_background(),
            available_extensions.fb_passthrough,
            enabled_extensions.fb_passthrough,
            alpha_blend_supported,
            environment_blend_modes_marker(&environment_blend_modes),
            environment_blend_mode,
            runtime_options.render_mode.projection_layer_alpha_blend(),
            runtime_options.render_mode.camera_runtime_mode(),
            runtime_options.render_mode.disabled_camera_projection_path(),
        ),
    );
    let requirements = xr_instance
        .graphics_requirements::<xr::Vulkan>(system)
        .map_err(|error| format!("read Vulkan graphics requirements: {error}"))?;
    let target_version_xr = xr::Version::new(1, 1, 0);
    if target_version_xr < requirements.min_api_version_supported
        || target_version_xr.major() > requirements.max_api_version_supported.major()
    {
        return Err(format!(
            "OpenXR runtime requires Vulkan >= {} and < {}.0.0",
            requirements.min_api_version_supported,
            requirements.max_api_version_supported.major() + 1
        ));
    }

    let vk_entry = ash::Entry::load().map_err(|error| format!("load Vulkan: {error}"))?;
    let vk_target_version = vk::make_api_version(0, 1, 1, 0);
    let vk_app_info = vk::ApplicationInfo::default()
        .application_version(1)
        .engine_version(1)
        .api_version(vk_target_version);
    let vk_instance = {
        let raw = xr_instance
            .create_vulkan_instance(
                system,
                std::mem::transmute(vk_entry.static_fn().get_instance_proc_addr),
                &vk::InstanceCreateInfo::default().application_info(&vk_app_info) as *const _
                    as *const _,
            )
            .map_err(|error| format!("OpenXR create Vulkan instance: {error}"))?
            .map_err(vk::Result::from_raw)
            .map_err(|error| format!("Vulkan create instance: {error}"))?;
        ash::Instance::load(vk_entry.static_fn(), vk::Instance::from_raw(raw as _))
    };

    let vk_physical_device = vk::PhysicalDevice::from_raw(
        xr_instance
            .vulkan_graphics_device(system, vk_instance.handle().as_raw() as _)
            .map_err(|error| format!("get OpenXR Vulkan graphics device: {error}"))? as _,
    );
    let device_properties = vk_instance.get_physical_device_properties(vk_physical_device);
    let memory_properties = vk_instance.get_physical_device_memory_properties(vk_physical_device);
    if device_properties.api_version < vk_target_version {
        vk_instance.destroy_instance(None);
        return Err("OpenXR-selected Vulkan device does not support Vulkan 1.1".to_string());
    }
    let device_name = CStr::from_ptr(device_properties.device_name.as_ptr())
        .to_string_lossy()
        .into_owned();
    let queue_family_properties =
        vk_instance.get_physical_device_queue_family_properties(vk_physical_device);
    let queue_family_index = queue_family_properties
        .iter()
        .enumerate()
        .find_map(|(index, info)| {
            info.queue_flags
                .contains(vk::QueueFlags::GRAPHICS | vk::QueueFlags::COMPUTE)
                .then_some(index as u32)
        })
        .ok_or_else(|| "OpenXR-selected Vulkan device has no graphics+compute queue".to_string())?;
    let selected_queue_family_properties = queue_family_properties[queue_family_index as usize];

    let external_hwb_extension_ready = physical_device_supports_extension(
        &vk_instance,
        vk_physical_device,
        ash::android::external_memory_android_hardware_buffer::NAME,
    )?;
    let sampler_ycbcr_extension_ready = physical_device_supports_extension(
        &vk_instance,
        vk_physical_device,
        ash::khr::sampler_ycbcr_conversion::NAME,
    )?;
    let mut sampler_ycbcr_features = vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
    let mut feature_query =
        vk::PhysicalDeviceFeatures2::default().push_next(&mut sampler_ycbcr_features);
    vk_instance.get_physical_device_features2(vk_physical_device, &mut feature_query);
    let sampler_ycbcr_feature_ready = sampler_ycbcr_features.sampler_ycbcr_conversion == vk::TRUE;
    let vulkan_external_import_prereqs_ready = external_hwb_extension_ready
        && sampler_ycbcr_extension_ready
        && sampler_ycbcr_feature_ready;

    let mut device_extension_ptrs = Vec::new();
    if external_hwb_extension_ready {
        device_extension_ptrs
            .push(ash::android::external_memory_android_hardware_buffer::NAME.as_ptr());
    }
    if sampler_ycbcr_extension_ready {
        device_extension_ptrs.push(ash::khr::sampler_ycbcr_conversion::NAME.as_ptr());
    }
    let queue_priorities = [1.0_f32];
    let queue_infos = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family_index)
        .queue_priorities(&queue_priorities)];
    let mut sampler_ycbcr_enable = vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default()
        .sampler_ycbcr_conversion(sampler_ycbcr_feature_ready);
    let device_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_infos)
        .enabled_extension_names(&device_extension_ptrs)
        .push_next(&mut sampler_ycbcr_enable);
    let vk_device = {
        let raw = xr_instance
            .create_vulkan_device(
                system,
                std::mem::transmute(vk_entry.static_fn().get_instance_proc_addr),
                vk_physical_device.as_raw() as _,
                &device_info as *const _ as *const _,
            )
            .map_err(|error| format!("OpenXR create Vulkan device: {error}"))?
            .map_err(vk::Result::from_raw)
            .map_err(|error| format!("Vulkan create device: {error}"))?;
        ash::Device::load(vk_instance.fp_v1_0(), vk::Device::from_raw(raw as _))
    };
    let queue = vk_device.get_device_queue(queue_family_index, 0);

    let (session, mut frame_wait, mut frame_stream) = xr_instance
        .create_session::<xr::Vulkan>(
            system,
            &xr::vulkan::SessionCreateInfo {
                instance: vk_instance.handle().as_raw() as _,
                physical_device: vk_physical_device.as_raw() as _,
                device: vk_device.handle().as_raw() as _,
                queue_family_index,
                queue_index: 0,
            },
        )
        .map_err(|error| format!("create OpenXR Vulkan session: {error}"))?;
    if let Some(actions) = stimulus_actions.as_mut() {
        if let Err(error) = actions.attach_session(&session) {
            crate::marker(
                "stimulus-volume-input",
                format!(
                    "status=unavailable reason={} actionSetAttached=false rightControllerPrimaryButtonRandomize=false",
                    crate::sanitize(&error)
                ),
            );
            stimulus_actions = None;
        }
    }
    let native_passthrough = NativePassthroughRuntime::create(
        &session,
        runtime_options.render_mode,
        enabled_extensions.fb_passthrough,
        alpha_blend_supported,
    );
    let mut live_hand_compact = LiveHandCompactInput::new(
        &xr_instance,
        system,
        &session,
        available_extensions.ext_hand_tracking,
        enabled_extensions.ext_hand_tracking,
    );
    let reference_space =
        create_projection_reference_space(&session, runtime_options.environment_depth_settings)?;
    let color_format =
        choose_swapchain_format(&session, runtime_options.swapchain_color_format_mode)?;
    let render_pass = create_projection_render_pass(&vk_device, color_format)?;
    let mut camera_projection_renderer = CameraProjectionRenderer::new(
        &vk_instance,
        &vk_device,
        memory_properties,
        render_pass,
        vulkan_external_import_prereqs_ready,
        runtime_options.camera_ycbcr_mode,
    );
    let mut guide_blur_graph_renderer = GuideBlurGraphRenderer::new(
        memory_properties,
        color_format,
        render_pass,
        runtime_options.guide_graph_resolution,
    );
    let render_mode = runtime_options.render_mode;
    let camera_output_mode = runtime_options.camera_output_mode;
    let guide_blur_enabled = runtime_options.guide_blur_enabled;
    let guide_graph_resolution = runtime_options.guide_graph_resolution;
    let camera_ycbcr_mode = runtime_options.camera_ycbcr_mode;
    let camera_resolution_profile = runtime_options.camera_resolution_profile;
    let camera_reader_max_images = runtime_options.camera_reader_max_images;
    let camera_quality_profile = runtime_options.camera_quality_profile;
    let camera_sync_mode = runtime_options.camera_sync_mode;
    let camera_luma_diagnostic_enabled = runtime_options.camera_luma_diagnostic_enabled;
    let camera_stereo_pairing_policy = runtime_options.camera_stereo_pairing_policy;
    let camera_direct_border_opacity = runtime_options.camera_direct_border_opacity;
    let swapchain_color_format_mode = runtime_options.swapchain_color_format_mode;
    let replay_visual_proof_enabled = runtime_options.replay_visual_proof_enabled;
    let compact_hand_input_source_mode = runtime_options.compact_hand_input_source_mode;
    let sdf_visual_enabled = runtime_options.sdf_visual_enabled;
    let sdf_update_period_frames = runtime_options.sdf_update_period_frames;
    let hand_mesh_visual_diagnostic_settings = runtime_options.hand_mesh_visual_diagnostic_settings;
    let hand_mesh_graft_copies_enabled = runtime_options.hand_mesh_graft_copies_enabled;
    let hand_mesh_graft_copy_scale = runtime_options.hand_mesh_graft_copy_scale;
    let hand_mesh_real_hands_visible = runtime_options.hand_mesh_real_hands_visible;
    let hand_anchor_particle_settings = runtime_options.hand_anchor_particle_settings;
    let environment_depth_settings = runtime_options.environment_depth_settings;
    let stimulus_volume_settings = runtime_options.stimulus_volume_settings;
    let projection_target_settings = runtime_options.projection_target_settings.clone();
    let projection_border_stretch_settings = runtime_options.projection_border_stretch_settings;
    let private_layer_settings = runtime_options.private_layer_settings;
    crate::marker(
        "recorded-replay-visual-proof",
        format!(
            "status=config renderModeProperty={} renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} solidBlackBackground={} openxrDefaultHandVisualRequested={} property={} enabled={} handMeshInputSourceProperty={} compactHandInputSourceMode={} selectsLiveFrame={} allowsRecordedFallback={} sdfVisualEnabled={} handMeshVisualDiagnosticEnabled={} handMeshGraftCopiesEnabled={} handMeshGraftScaleProperty={} handMeshGraftScaleMultiplier={:.2} realHandsProperty={} handMeshRealHandsVisible={} recordedReplayVisualAcceptance=pending-headset-screenshot liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof liveSdfVisualAcceptance=pending-repeat-headset-visual-proof",
            PROP_RENDER_MODE,
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            render_mode.uses_native_passthrough(),
            render_mode.uses_solid_black_background(),
            render_mode.requests_openxr_default_hand_visual(),
            PROP_REPLAY_VISUAL_PROOF_ENABLED,
            replay_visual_proof_enabled,
            PROP_HAND_MESH_INPUT_SOURCE,
            compact_hand_input_source_mode.marker_value(),
            compact_hand_input_source_mode.selects_live_frame(),
            compact_hand_input_source_mode.allows_recorded_fallback(),
            sdf_visual_enabled,
            hand_mesh_visual_diagnostic_settings.enabled,
            hand_mesh_graft_copies_enabled,
            PROP_HAND_MESH_GRAFT_COPY_SCALE,
            hand_mesh_graft_copy_scale,
            PROP_HAND_MESH_REAL_HANDS_VISIBLE,
            hand_mesh_real_hands_visible,
        ),
    );
    crate::marker(
        "camera-output",
        format!(
            "status=config property={} cameraOutputMode={} renderMode={} customStereoProjectionEnabled={} cameraImportEnabled={} privateLayerProjectionEnabled={} guideProjectionEnabled={} directHwbForced={} guideBlurProperty={} guideGraphBlurEnabled={} guideResolutionProperty={} guideGraphResolutionPolicy={} ycbcrProperty={} cameraYcbcrMode={} conversionMode={} resolutionProperty={} cameraResolutionProfile={} readerMaxImagesProperty={} readerMaxImages={} qualityProfileProperty={} cameraQualityProfile={} syncModeProperty={} cameraSyncRequested={} cameraSyncActive={} cameraSyncImplementation={} lumaDiagnosticProperty={} cameraLumaDiagnosticRequested={} stereoPairingProperty={} stereoPairingPolicy={} swapchainProperty={} swapchainColorFormatMode={} directBorderProperty={} directBorderOpacity={:.3} cameraQualityDiagnostic=raw-direct-hwb-baseline",
            PROP_CAMERA_OUTPUT_MODE,
            camera_output_mode.marker_value(),
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            camera_output_mode.camera_import_enabled(),
            camera_output_mode.private_layer_projection_enabled(),
            camera_output_mode.guide_projection_enabled(),
            camera_output_mode.direct_hwb_forced(),
            PROP_GUIDE_BLUR_ENABLED,
            guide_blur_enabled,
            PROP_GUIDE_RESOLUTION,
            guide_graph_resolution.marker_value(),
            PROP_CAMERA_YCBCR_MODE,
            camera_ycbcr_mode.marker_value(),
            camera_ycbcr_mode.conversion_mode(),
            PROP_CAMERA_RESOLUTION_PROFILE,
            camera_resolution_profile.marker_value(),
            PROP_CAMERA_READER_MAX_IMAGES,
            camera_reader_max_images,
            PROP_CAMERA_QUALITY_PROFILE,
            camera_quality_profile.marker_value(),
            PROP_CAMERA_SYNC_MODE,
            camera_sync_mode.marker_value(),
            camera_sync_mode.active_marker_value(),
            camera_sync_mode.implementation_status(),
            PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED,
            camera_luma_diagnostic_enabled,
            PROP_CAMERA_STEREO_PAIRING,
            camera_stereo_pairing_policy.marker_value(),
            PROP_SWAPCHAIN_COLOR_FORMAT_MODE,
            swapchain_color_format_mode.marker_value(),
            PROP_CAMERA_DIRECT_BORDER_OPACITY,
            camera_direct_border_opacity,
        ),
    );
    crate::marker(
        "projection-border-stretch",
        format!(
            "status=config property={} renderMode={} customStereoProjectionEnabled={} guideProjectionCoverage={} {}",
            PROP_PROCESSING_LAYER,
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            if projection_border_stretch_settings.peripheral_stretch_active() {
                "full-eye-peripheral-stretch"
            } else {
                "metadata-target-only"
            },
            projection_border_stretch_settings.marker_fields()
        ),
    );
    crate::marker(
        "projection-target",
        format!(
            "status=config renderMode={} customStereoProjectionEnabled={} {} startupDefaultsAuthority=runtime-profile finalStateAuthority=native-renderer pmbSourceAuthority=hostess-manifold",
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            projection_target_settings.marker_fields(),
        ),
    );
    crate::marker(
        "hand-mesh-visual-diagnostic",
        format!(
            "status=config renderMode={} solidBlackBackground={} openxrDefaultHandVisualRequested={} handMeshVisualDiagnosticPath=property-controlled-target-local-offset-tint enabledProperty={} offsetProperty={} alphaProperty={} graftCopiesProperty={} graftScaleProperty={} realHandsProperty={} handMeshGraftCopiesEnabled={} handMeshGraftScaleMultiplier={:.2} handMeshRealHandsVisible={} nativePassthroughRealHandMeshVisible={} solidBlackRealHandMeshVisible={} {} liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof liveSdfVisualAcceptance=pending-repeat-headset-visual-proof",
            render_mode.marker_value(),
            render_mode.uses_solid_black_background(),
            render_mode.requests_openxr_default_hand_visual(),
            PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED,
            PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV,
            PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA,
            PROP_HAND_MESH_GRAFT_COPIES_ENABLED,
            PROP_HAND_MESH_GRAFT_COPY_SCALE,
            PROP_HAND_MESH_REAL_HANDS_VISIBLE,
            hand_mesh_graft_copies_enabled,
            hand_mesh_graft_copy_scale,
            hand_mesh_real_hands_visible,
            render_mode.uses_native_passthrough() && hand_mesh_real_hands_visible,
            render_mode.uses_solid_black_background() && hand_mesh_real_hands_visible,
            hand_mesh_visual_diagnostic_settings.marker_fields(),
        ),
    );
    crate::marker(
        "hand-anchor-particles",
        format!(
            "status=config enabledProperty={} perHandProperty={} radiusProperty={} transparencyBlendProperty={} transparencyCompositionProperty={} transparencyDepthSuppressionProperty={} orderingModeProperty={} orderingImplementationProperty={} orderingIntervalProperty={} renderMode={} openxrDefaultHandVisualRequested={} customHandMeshVisualRequested={} compactHandInputSourceMode={} selectsLiveFrame={} {} handAnchorParticleVisualAcceptance=pending-headset-screenshot",
            PROP_HAND_ANCHOR_PARTICLES_ENABLED,
            PROP_HAND_ANCHOR_PARTICLES_PER_HAND,
            PROP_HAND_ANCHOR_PARTICLES_RADIUS_M,
            PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE,
            PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
            PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
            PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE,
            PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION,
            PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES,
            render_mode.marker_value(),
            render_mode.requests_openxr_default_hand_visual(),
            hand_mesh_real_hands_visible,
            compact_hand_input_source_mode.marker_value(),
            compact_hand_input_source_mode.selects_live_frame(),
            hand_anchor_particle_settings.marker_fields(),
        ),
    );
    crate::marker(
        "environment-depth-particles",
        format!(
            "status=config renderMode={} nativePassthroughRequested={} syntheticGpuProofRequested={} runtimeProviderRequested={} {} environmentDepthParticleVisualAcceptance=pending-headset-screenshot",
            render_mode.marker_value(),
            render_mode.uses_native_passthrough(),
            environment_depth_settings.synthetic_gpu_proof_requested(),
            environment_depth_settings.runtime_provider_requested(),
            environment_depth_settings.marker_fields(),
        ),
    );
    let mut openxr_environment_depth_runtime = if environment_depth_settings
        .runtime_provider_requested()
    {
        match OpenXrEnvironmentDepthRuntime::create(
            &xr_instance,
            &session,
            environment_depth_settings,
            environment_depth_properties,
            0,
        ) {
            Ok(runtime) => Some(runtime),
            Err(error) => {
                crate::marker(
                    "environment-depth",
                    format!(
                        "status=unavailable reason={} environmentDepthProviderAvailable={} environmentDepthRealProviderBound=false environmentDepthSupported={} environmentDepthAcquireStatus=not-attempted-provider-not-bound",
                        crate::sanitize(&error),
                        environment_depth_properties.extension_available,
                        environment_depth_properties.supports_environment_depth,
                    ),
                );
                None
            }
        }
    } else {
        None
    };
    let mut private_extension_slot_runtime =
        PrivateExtensionSlotRuntime::new(memory_properties, color_format, render_pass);
    crate::marker(
        "private-extension-slot",
        format!(
            "status=config {}",
            PrivateExtensionSlotRuntime::config_marker_fields(private_layer_settings)
        ),
    );
    let mut gpu_stimulus_volume_renderer = if stimulus_volume_settings.enabled {
        match GpuStimulusVolumeRenderer::new(
            &vk_device,
            &memory_properties,
            render_pass,
            stimulus_volume_settings,
        ) {
            Ok(renderer) => Some(renderer),
            Err(error) => {
                crate::marker(
                    "stimulus-volume",
                    format!(
                        "status=unavailable reason={} stimulusVolumeReady=false {}",
                        crate::sanitize(&error),
                        stimulus_volume_settings.marker_fields()
                    ),
                );
                None
            }
        }
    } else {
        None
    };
    let mut gpu_environment_depth_particle_renderer = if environment_depth_settings
        .synthetic_gpu_proof_requested()
    {
        match GpuEnvironmentDepthParticleRenderer::new_synthetic(
            &vk_device,
            &memory_properties,
            render_pass,
            environment_depth_settings,
        ) {
            Ok(renderer) => Some(renderer),
            Err(error) => {
                crate::marker(
                        "environment-depth-particles",
                        format!(
                            "status=unavailable reason={} environmentDepthParticleReady=false environmentDepthRealProviderBound=false",
                            crate::sanitize(&error)
                        ),
                    );
                None
            }
        }
    } else if environment_depth_settings.runtime_provider_requested()
        && environment_depth_settings.mode_draws_particles()
    {
        match openxr_environment_depth_runtime.as_ref().map(|runtime| {
            GpuEnvironmentDepthParticleRenderer::new_runtime_depth(
                &vk_device,
                &memory_properties,
                render_pass,
                environment_depth_settings,
                runtime.depth_image_handles(),
                runtime.width(),
                runtime.height(),
            )
        }) {
            Some(Ok(renderer)) => Some(renderer),
            Some(Err(error)) => {
                crate::marker(
                    "environment-depth-particles",
                    format!(
                        "status=unavailable reason={} environmentDepthParticleReady=false environmentDepthRealProviderBound=true",
                        crate::sanitize(&error)
                    ),
                );
                None
            }
            None => {
                crate::marker(
                    "environment-depth-particles",
                    "status=unavailable reason=no-openxr-environment-depth-runtime environmentDepthParticleReady=false environmentDepthRealProviderBound=false",
                );
                None
            }
        }
    } else {
        None
    };
    let mut gpu_sdf_field_renderer = if render_mode.uses_stimulus_volume() {
        crate::marker(
            "gpu-sdf-field",
            "status=disabled reason=stimulus-volume-route dynamicSdfReady=false sdfVisualEffectVisible=false gpuSdfFieldReady=false",
        );
        None
    } else {
        match GpuSdfFieldRenderer::new(&vk_device, &memory_properties, render_pass, replay) {
            Ok(renderer) => {
                if !sdf_visual_enabled {
                    crate::marker(
                        "gpu-sdf-field",
                        format!(
                            "status=skinning-active-sdf-overlay-deferred reason=property-disabled property={} dynamicSdfReady=false sdfVisualEffectVisible=false gpuSdfFieldReady=false gpuSdfOverlayVisible=false cpuSdfPerFrame=false meshToSdfKernel=false targetSpaceMeshToSdfKernelAvailable=true fullSkinnedMeshSdfReady=false compactJointSkinningKernel=true jointMatrixSkinningKernel=false jointMatrixUploadPerFrame=false compactJointPoseUploadPerFrame=true sourceMeshToSdfKernel=false",
                            PROP_ENABLE_SDF_VISUAL
                        ),
                    );
                }
                Some(renderer)
            }
            Err(error) => {
                crate::marker(
                    "gpu-sdf-field",
                    format!(
                        "status=error reason={} dynamicSdfReady=false sdfVisualEffectVisible=false sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field legacySdfComputePath=native-vulkan-compute-recorded-validation-mesh-sdf-field cpuSdfPerFrame=false meshToSdfKernel=false targetSpaceMeshToSdfKernelAvailable=true fullSkinnedMeshSdfReady=false compactJointSkinningKernel=false jointMatrixSkinningKernel=false jointMatrixUploadPerFrame=false compactJointPoseUploadPerFrame=false sourceMeshToSdfKernel=false",
                        crate::sanitize(&error)
                    ),
                );
                None
            }
        }
    };
    let mut gpu_hand_mesh_visual_renderer = if let Some(renderer) = gpu_sdf_field_renderer.as_ref()
    {
        let draw_resources = renderer.skinned_hand_mesh_draw_resources();
        match GpuHandMeshVisualRenderer::new(
            &vk_device,
            &memory_properties,
            render_pass,
            replay,
            draw_resources,
        ) {
            Ok(renderer) => Some(renderer),
            Err(error) => {
                crate::marker(
                    "hand-mesh-visual",
                    format!(
                        "status=unavailable reason={} animatedHandMeshVisualReady=false animatedHandMeshVisualVisible=false handMeshVisualPath=recorded-compact-joint-gpu-skinned-resident-triangle-draw gpuTriangleDraw=false cpuProjection=false validationMeshUploadPerFrame=false",
                        crate::sanitize(&error)
                    ),
                );
                None
            }
        }
    } else {
        crate::marker(
            "hand-mesh-visual",
            "status=unavailable reason=no-resident-skinned-mesh-runtime animatedHandMeshVisualReady=false animatedHandMeshVisualVisible=false handMeshVisualPath=recorded-compact-joint-gpu-skinned-resident-triangle-draw gpuTriangleDraw=false cpuProjection=false validationMeshUploadPerFrame=false",
        );
        None
    };
    let mut gpu_hand_anchor_particle_renderer = if hand_anchor_particle_settings.enabled {
        if let Some(renderer) = gpu_sdf_field_renderer.as_ref() {
            let draw_resources = renderer.skinned_hand_mesh_draw_resources();
            match GpuHandAnchorParticleRenderer::new(
                &vk_device,
                &memory_properties,
                render_pass,
                draw_resources,
                handedness_label(&replay.handedness),
                hand_anchor_particle_settings,
            ) {
                Ok(renderer) => Some(renderer),
                Err(error) => {
                    crate::marker(
                        "hand-anchor-particles",
                        format!(
                            "status=unavailable reason={} handAnchorParticleReady=false",
                            crate::sanitize(&error)
                        ),
                    );
                    None
                }
            }
        } else {
            crate::marker(
                    "hand-anchor-particles",
                    "status=unavailable reason=no-primary-resident-skinned-mesh-runtime handAnchorParticleReady=false",
                );
            None
        }
    } else {
        None
    };
    let mut secondary_gpu_sdf_field_renderer = if render_mode.uses_stimulus_volume() {
        None
    } else {
        match GpuSdfFieldRenderer::new(
            &vk_device,
            &memory_properties,
            render_pass,
            secondary_replay,
        ) {
            Ok(renderer) => {
                crate::marker(
                    "hand-mesh-visual",
                    "status=secondary-gpu-skinning-created handMeshVisualSecondaryPath=live-second-hand-gpu-skinned-resident-triangle-draw liveHandMeshVisualBothHandsPath=dual-resident-gpu-skinned-mesh-draw secondarySdfOverlay=false",
                );
                Some(renderer)
            }
            Err(error) => {
                crate::marker(
                    "hand-mesh-visual",
                    format!(
                        "status=secondary-unavailable reason={} liveHandMeshVisualBothHandsPath=single-hand-only secondarySdfOverlay=false",
                        crate::sanitize(&error)
                    ),
                );
                None
            }
        }
    };
    let mut secondary_gpu_hand_mesh_visual_renderer = if let Some(renderer) =
        secondary_gpu_sdf_field_renderer.as_ref()
    {
        let draw_resources = renderer.skinned_hand_mesh_draw_resources();
        match GpuHandMeshVisualRenderer::new(
            &vk_device,
            &memory_properties,
            render_pass,
            secondary_replay,
            draw_resources,
        ) {
            Ok(renderer) => Some(renderer),
            Err(error) => {
                crate::marker(
                        "hand-mesh-visual",
                        format!(
                            "status=secondary-visual-unavailable reason={} liveHandMeshVisualBothHandsPath=single-hand-only",
                            crate::sanitize(&error)
                        ),
                    );
                None
            }
        }
    } else {
        None
    };
    let mut secondary_gpu_hand_anchor_particle_renderer = if hand_anchor_particle_settings.enabled {
        if let Some(renderer) = secondary_gpu_sdf_field_renderer.as_ref() {
            let draw_resources = renderer.skinned_hand_mesh_draw_resources();
            match GpuHandAnchorParticleRenderer::new(
                &vk_device,
                &memory_properties,
                render_pass,
                draw_resources,
                handedness_label(&secondary_replay.handedness),
                hand_anchor_particle_settings,
            ) {
                Ok(renderer) => Some(renderer),
                Err(error) => {
                    crate::marker(
                            "hand-anchor-particles",
                            format!(
                                "status=secondary-unavailable reason={} handAnchorParticleSecondaryReady=false",
                                crate::sanitize(&error)
                            ),
                        );
                    None
                }
            }
        } else {
            None
        }
    } else {
        None
    };
    let mut gpu_mesh_replay = GpuMeshReplayResources::default();
    let gpu_mesh_stats = match gpu_mesh_replay.prepare_source_mesh(
        &vk_device,
        &memory_properties,
        replay,
    ) {
        Ok(stats) => {
            crate::marker(
                "gpu-mesh-replay",
                format!("status=prepared {}", stats.marker_fields()),
            );
            stats
        }
        Err(error) => {
            crate::marker(
                    "gpu-mesh-replay",
                    format!(
                        "status=error reason={} topologyVertexCount={} topologyTriangleCount={} topologyIndexCount={} sourceMeshToSdfKernel=false cpuSdfPerFrame=false",
                        crate::sanitize(&error),
                        replay.vertex_count,
                        replay.triangle_count,
                        replay.index_count
                    ),
                );
            GpuMeshReplayStats {
                topology_vertex_count: replay.vertex_count,
                topology_triangle_count: replay.triangle_count,
                topology_index_count: replay.index_count,
                cpu_sdf_per_frame: false,
                ..Default::default()
            }
        }
    };
    let cmd_pool = vk_device
        .create_command_pool(
            &vk::CommandPoolCreateInfo::default()
                .queue_family_index(queue_family_index)
                .flags(
                    vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER
                        | vk::CommandPoolCreateFlags::TRANSIENT,
                ),
            None,
        )
        .map_err(|error| format!("create Vulkan command pool: {error}"))?;
    let cmds = vk_device
        .allocate_command_buffers(
            &vk::CommandBufferAllocateInfo::default()
                .command_pool(cmd_pool)
                .command_buffer_count(PIPELINE_DEPTH),
        )
        .map_err(|error| format!("allocate Vulkan command buffers: {error}"))?;
    let fences = (0..PIPELINE_DEPTH)
        .map(|_| {
            vk_device.create_fence(
                &vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED),
                None,
            )
        })
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| format!("create Vulkan fences: {error}"))?;
    let mut gpu_timestamp_tracker = match GpuTimestampTracker::new(
        &vk_device,
        PIPELINE_DEPTH as usize,
        selected_queue_family_properties.timestamp_valid_bits,
        f64::from(device_properties.limits.timestamp_period),
    ) {
        Ok(tracker) => tracker,
        Err(error) => {
            crate::marker(
                "gpu-timestamp-timing",
                format!(
                    "status=disabled reason={} gpuTimestampQuerySupported=false gpuTimingScope=vulkan-timestamp-query",
                    crate::sanitize(&error)
                ),
            );
            GpuTimestampTracker::disabled(
                PIPELINE_DEPTH as usize,
                selected_queue_family_properties.timestamp_valid_bits,
                f64::from(device_properties.limits.timestamp_period),
            )
        }
    };
    crate::marker(
        "gpu-timestamp-timing",
        format!(
            "status=config {}",
            gpu_timestamp_tracker.config_marker_fields()
        ),
    );

    crate::marker(
        "openxr-projection",
        format!(
            "status=created renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} nativePassthroughLayerActive={} environmentBlendMode={:?} runtimeName={} runtimeVersion={} deviceName={} queueFamily={} colorFormat={:?} openxrSubmitReady=pending vulkanExternalImportPrereqsReady={} vulkanExternalImportReady=false recordedHandReplayVisible=pending gpuMeshPath=native-vulkan-storage-buffer",
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            render_mode.uses_native_passthrough(),
            native_passthrough.is_some(),
            environment_blend_mode,
            crate::sanitize(&properties.runtime_name),
            properties.runtime_version,
            crate::sanitize(&device_name),
            queue_family_index,
            color_format,
            vulkan_external_import_prereqs_ready
        ),
    );
    crate::marker(
        "openxr-live-hand",
        format!(
            "status=ready-check extensionAvailable={} extensionEnabled={} systemSupported={} liveMetaHandFrameSource=XR_EXT_hand_tracking liveMetaHandGpuInputPath=recorded-compatible-compact-joint-pose-tip-length",
            available_extensions.ext_hand_tracking,
            enabled_extensions.ext_hand_tracking,
            live_hand_tracking_system_supported
        ),
    );

    let loop_result = run_projection_frames(
        app,
        &xr_instance,
        system,
        &vk_device,
        queue,
        &session,
        &mut frame_wait,
        &mut frame_stream,
        &reference_space,
        render_pass,
        color_format,
        &cmds,
        &fences,
        &mut camera_projection_renderer,
        &mut guide_blur_graph_renderer,
        camera_runtime,
        render_mode,
        environment_blend_mode,
        native_passthrough.as_ref(),
        stimulus_actions.as_mut(),
        gpu_stimulus_volume_renderer.as_mut(),
        replay,
        secondary_replay,
        &gpu_mesh_stats,
        gpu_hand_mesh_visual_renderer.as_mut(),
        secondary_gpu_hand_mesh_visual_renderer.as_mut(),
        gpu_hand_anchor_particle_renderer.as_mut(),
        secondary_gpu_hand_anchor_particle_renderer.as_mut(),
        openxr_environment_depth_runtime.as_mut(),
        gpu_environment_depth_particle_renderer.as_mut(),
        gpu_sdf_field_renderer.as_mut(),
        secondary_gpu_sdf_field_renderer.as_mut(),
        &mut gpu_timestamp_tracker,
        &mut private_extension_slot_runtime,
        &mut live_hand_compact,
        compact_hand_input_source_mode,
        replay_visual_proof_enabled,
        sdf_visual_enabled,
        sdf_update_period_frames,
        hand_mesh_visual_diagnostic_settings,
        hand_mesh_graft_copies_enabled,
        hand_mesh_graft_copy_scale,
        hand_mesh_real_hands_visible,
        hand_anchor_particle_settings,
        environment_depth_settings,
        stimulus_volume_settings,
        projection_target_settings,
        camera_output_mode,
        guide_blur_enabled,
        guide_graph_resolution,
        camera_ycbcr_mode,
        camera_resolution_profile,
        camera_reader_max_images,
        camera_quality_profile,
        camera_sync_mode,
        camera_luma_diagnostic_enabled,
        swapchain_color_format_mode,
        camera_direct_border_opacity,
        projection_border_stretch_settings,
        private_layer_settings,
        &projection_metadata,
    );

    let _ = vk_device.device_wait_idle();
    if let Err(error) = vk_device.wait_for_fences(&fences, true, 1_000_000_000) {
        crate::marker(
            "vulkan-cleanup",
            format!("status=warning reason=wait_fences_failed error={error}"),
        );
    }
    for fence in fences {
        vk_device.destroy_fence(fence, None);
    }
    if let Some(renderer) = gpu_hand_mesh_visual_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    if let Some(renderer) = secondary_gpu_hand_mesh_visual_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    if let Some(renderer) = gpu_hand_anchor_particle_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    if let Some(renderer) = secondary_gpu_hand_anchor_particle_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    if let Some(renderer) = gpu_environment_depth_particle_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    if let Some(renderer) = gpu_stimulus_volume_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    if let Some(renderer) = gpu_sdf_field_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    if let Some(renderer) = secondary_gpu_sdf_field_renderer.as_mut() {
        renderer.destroy(&vk_device);
    }
    gpu_timestamp_tracker.destroy(&vk_device);
    private_extension_slot_runtime.destroy(&vk_device);
    guide_blur_graph_renderer.destroy(&vk_device);
    camera_projection_renderer.destroy(&vk_device);
    gpu_mesh_replay.destroy(&vk_device);
    vk_device.destroy_command_pool(cmd_pool, None);
    vk_device.destroy_render_pass(render_pass, None);
    drop(openxr_environment_depth_runtime);
    drop(native_passthrough);
    drop((session, frame_wait, frame_stream, reference_space));
    vk_device.destroy_device(None);
    vk_instance.destroy_instance(None);

    loop_result
}

unsafe fn probe_inner(
    app: &android_activity::AndroidApp,
    readiness: &mut XrVulkanReadiness,
) -> Result<(), String> {
    let entry = xr::Entry::load().map_err(|error| format!("load OpenXR: {error}"))?;
    initialize_android_loader(&entry, app)?;
    readiness.android_loader_ready = true;

    let available_extensions = entry
        .enumerate_extensions()
        .map_err(|error| format!("enumerate OpenXR extensions: {error}"))?;
    readiness.live_hand_tracking_extension_available = available_extensions.ext_hand_tracking;
    if !available_extensions.khr_android_create_instance {
        return Err("OpenXR runtime does not expose XR_KHR_android_create_instance".to_string());
    }
    if !available_extensions.khr_vulkan_enable2 {
        return Err("OpenXR runtime does not expose XR_KHR_vulkan_enable2".to_string());
    }

    let mut enabled_extensions = xr::ExtensionSet::default();
    enabled_extensions.khr_android_create_instance = true;
    enabled_extensions.khr_vulkan_enable2 = true;
    enabled_extensions.ext_hand_tracking = available_extensions.ext_hand_tracking;
    readiness.live_hand_tracking_extension_enabled = enabled_extensions.ext_hand_tracking;
    let xr_instance = create_android_instance(
        &entry,
        app,
        &xr::ApplicationInfo {
            application_name: "Rusty Quest Native Renderer",
            application_version: 1,
            engine_name: "Rusty Quest",
            engine_version: 1,
            api_version: xr::Version::new(1, 0, 0),
        },
        &enabled_extensions,
        &[],
    )?;
    readiness.openxr_instance_ready = true;

    let properties = xr_instance
        .properties()
        .map_err(|error| format!("read OpenXR properties: {error}"))?;
    let system = xr_instance
        .system(xr::FormFactor::HEAD_MOUNTED_DISPLAY)
        .map_err(|error| format!("get HMD system: {error}"))?;
    readiness.live_hand_tracking_system_supported = if enabled_extensions.ext_hand_tracking {
        xr_instance.supports_hand_tracking(system).unwrap_or(false)
    } else {
        false
    };
    let requirements = xr_instance
        .graphics_requirements::<xr::Vulkan>(system)
        .map_err(|error| format!("read Vulkan graphics requirements: {error}"))?;
    let target_version_xr = xr::Version::new(1, 1, 0);
    if target_version_xr < requirements.min_api_version_supported
        || target_version_xr.major() > requirements.max_api_version_supported.major()
    {
        return Err(format!(
            "OpenXR runtime requires Vulkan >= {} and < {}.0.0",
            requirements.min_api_version_supported,
            requirements.max_api_version_supported.major() + 1
        ));
    }
    crate::marker(
        "xr-vulkan-graphics-requirements",
        format!(
            "status=ok runtimeName={} runtimeVersion={} minApiVersion={} maxApiVersion={} targetApiVersion={} khrVulkanEnable2=true",
            crate::sanitize(&properties.runtime_name),
            properties.runtime_version,
            requirements.min_api_version_supported,
            requirements.max_api_version_supported,
            target_version_xr
        ),
    );

    let vk_probe = probe_vulkan(&xr_instance, system)?;
    readiness.vulkan_instance_ready = true;
    readiness.external_hwb_extension_ready = vk_probe.external_hwb_extension_ready;
    readiness.sampler_ycbcr_extension_ready = vk_probe.sampler_ycbcr_extension_ready;
    readiness.sampler_ycbcr_feature_ready = vk_probe.sampler_ycbcr_feature_ready;
    readiness.vulkan_external_import_prereqs_ready = vk_probe.external_hwb_extension_ready
        && vk_probe.sampler_ycbcr_extension_ready
        && vk_probe.sampler_ycbcr_feature_ready;
    crate::marker(
        "vulkan-probe",
        format!(
            "status=ok deviceName={} apiVersion={} externalMemoryAndroidHardwareBuffer={} samplerYcbcrExtension={} samplerYcbcrFeature={} descriptorShape=combined-immutable-sampler-ycbcr-conversion vulkanExternalImportPrereqsReady={} openxrSubmitReady=false vulkanExternalImportReady=false",
            crate::sanitize(&vk_probe.device_name),
            vk_probe.api_version,
            vk_probe.external_hwb_extension_ready,
            vk_probe.sampler_ycbcr_extension_ready,
            vk_probe.sampler_ycbcr_feature_ready,
            readiness.vulkan_external_import_prereqs_ready
        ),
    );

    Ok(())
}

fn initialize_android_loader(
    entry: &xr::Entry,
    app: &android_activity::AndroidApp,
) -> Result<(), String> {
    let loader_init = unsafe { xr::raw::LoaderInitKHR::load(entry, xr::sys::Instance::NULL) }
        .map_err(|error| format!("load Android OpenXR loader init: {error}"))?;
    let loader_info = xr::sys::LoaderInitInfoAndroidKHR {
        ty: xr::sys::LoaderInitInfoAndroidKHR::TYPE,
        next: ptr::null(),
        application_vm: app.vm_as_ptr(),
        application_context: app.activity_as_ptr(),
    };

    let result = unsafe { (loader_init.initialize_loader)(&loader_info as *const _ as _) };
    ensure_xr_success(result, "xrInitializeLoaderKHR")
}

unsafe fn create_android_instance(
    entry: &xr::Entry,
    app: &android_activity::AndroidApp,
    app_info: &xr::ApplicationInfo,
    required_extensions: &xr::ExtensionSet,
    layers: &[&str],
) -> Result<xr::Instance, String> {
    if app_info.application_name.len() >= xr::sys::MAX_APPLICATION_NAME_SIZE {
        return Err(format!(
            "OpenXR application name must be shorter than {} bytes",
            xr::sys::MAX_APPLICATION_NAME_SIZE
        ));
    }
    if app_info.engine_name.len() >= xr::sys::MAX_ENGINE_NAME_SIZE {
        return Err(format!(
            "OpenXR engine name must be shorter than {} bytes",
            xr::sys::MAX_ENGINE_NAME_SIZE
        ));
    }

    let extension_names = required_extensions.names();
    let extension_ptrs = extension_names
        .iter()
        .map(|name| name.as_ptr() as *const _)
        .collect::<Vec<_>>();
    let layer_names = layers
        .iter()
        .filter_map(|layer| CString::new(*layer).ok())
        .collect::<Vec<_>>();
    let layer_ptrs = layer_names
        .iter()
        .map(|layer| layer.as_ptr())
        .collect::<Vec<_>>();

    let android_info = xr::sys::InstanceCreateInfoAndroidKHR {
        ty: xr::sys::InstanceCreateInfoAndroidKHR::TYPE,
        next: ptr::null(),
        application_vm: app.vm_as_ptr(),
        application_activity: app.activity_as_ptr(),
    };
    let mut info = xr::sys::InstanceCreateInfo {
        ty: xr::sys::InstanceCreateInfo::TYPE,
        next: &android_info as *const _ as _,
        create_flags: Default::default(),
        application_info: xr::sys::ApplicationInfo {
            application_name: [0; xr::sys::MAX_APPLICATION_NAME_SIZE],
            application_version: app_info.application_version,
            engine_name: [0; xr::sys::MAX_ENGINE_NAME_SIZE],
            engine_version: app_info.engine_version,
            api_version: app_info.api_version,
        },
        enabled_api_layer_count: layer_ptrs.len() as _,
        enabled_api_layer_names: layer_ptrs.as_ptr(),
        enabled_extension_count: extension_ptrs.len() as _,
        enabled_extension_names: extension_ptrs.as_ptr(),
    };
    write_xr_string(
        &mut info.application_info.application_name,
        app_info.application_name,
    );
    write_xr_string(&mut info.application_info.engine_name, app_info.engine_name);

    let mut handle = xr::sys::Instance::NULL;
    let result = (entry.fp().create_instance)(&info, &mut handle);
    ensure_xr_success(result, "xrCreateInstance")?;

    let extensions = xr::InstanceExtensions::load(entry, handle, required_extensions)
        .map_err(|error| format!("load OpenXR instance extensions: {error}"))?;
    xr::Instance::from_raw(entry.clone(), handle, extensions)
        .map_err(|error| format!("wrap OpenXR instance: {error}"))
}

unsafe fn probe_vulkan(
    xr_instance: &xr::Instance,
    system: xr::SystemId,
) -> Result<VulkanProbe, String> {
    let vk_entry = ash::Entry::load().map_err(|error| format!("load Vulkan: {error}"))?;
    let vk_target_version = vk::make_api_version(0, 1, 1, 0);
    let vk_app_info = vk::ApplicationInfo::default()
        .application_version(1)
        .engine_version(1)
        .api_version(vk_target_version);
    let raw_instance = xr_instance
        .create_vulkan_instance(
            system,
            std::mem::transmute(vk_entry.static_fn().get_instance_proc_addr),
            &vk::InstanceCreateInfo::default().application_info(&vk_app_info) as *const _
                as *const _,
        )
        .map_err(|error| format!("OpenXR create Vulkan instance: {error}"))?
        .map_err(vk::Result::from_raw)
        .map_err(|error| format!("Vulkan create instance: {error}"))?;
    let vk_instance = ash::Instance::load(
        vk_entry.static_fn(),
        vk::Instance::from_raw(raw_instance as _),
    );

    let result = (|| -> Result<VulkanProbe, String> {
        let vk_physical_device = vk::PhysicalDevice::from_raw(
            xr_instance
                .vulkan_graphics_device(system, vk_instance.handle().as_raw() as _)
                .map_err(|error| format!("get OpenXR Vulkan graphics device: {error}"))?
                as _,
        );
        let properties = vk_instance.get_physical_device_properties(vk_physical_device);
        if properties.api_version < vk_target_version {
            return Err("OpenXR-selected Vulkan device does not support Vulkan 1.1".to_string());
        }

        let external_hwb_extension_ready = physical_device_supports_extension(
            &vk_instance,
            vk_physical_device,
            ash::android::external_memory_android_hardware_buffer::NAME,
        )?;
        let sampler_ycbcr_extension_ready = physical_device_supports_extension(
            &vk_instance,
            vk_physical_device,
            ash::khr::sampler_ycbcr_conversion::NAME,
        )?;
        let mut sampler_ycbcr_features =
            vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
        let mut feature_query =
            vk::PhysicalDeviceFeatures2::default().push_next(&mut sampler_ycbcr_features);
        vk_instance.get_physical_device_features2(vk_physical_device, &mut feature_query);
        let sampler_ycbcr_feature_ready =
            sampler_ycbcr_features.sampler_ycbcr_conversion == vk::TRUE;
        let device_name = CStr::from_ptr(properties.device_name.as_ptr())
            .to_string_lossy()
            .into_owned();
        Ok(VulkanProbe {
            device_name,
            api_version: properties.api_version,
            external_hwb_extension_ready,
            sampler_ycbcr_extension_ready,
            sampler_ycbcr_feature_ready,
        })
    })();

    vk_instance.destroy_instance(None);
    result
}

unsafe fn physical_device_supports_extension(
    instance: &ash::Instance,
    physical_device: vk::PhysicalDevice,
    extension_name: &CStr,
) -> Result<bool, String> {
    let extensions = instance
        .enumerate_device_extension_properties(physical_device)
        .map_err(|error| format!("enumerate Vulkan device extensions: {error}"))?;
    Ok(extensions
        .iter()
        .any(|extension| CStr::from_ptr(extension.extension_name.as_ptr()) == extension_name))
}

#[allow(clippy::too_many_arguments)]
unsafe fn run_projection_frames(
    app: &android_activity::AndroidApp,
    xr_instance: &xr::Instance,
    system: xr::SystemId,
    vk_device: &ash::Device,
    queue: vk::Queue,
    session: &xr::Session<xr::Vulkan>,
    frame_wait: &mut xr::FrameWaiter,
    frame_stream: &mut xr::FrameStream<xr::Vulkan>,
    reference_space: &xr::Space,
    render_pass: vk::RenderPass,
    color_format: vk::Format,
    cmds: &[vk::CommandBuffer],
    fences: &[vk::Fence],
    camera_projection_renderer: &mut CameraProjectionRenderer,
    guide_blur_graph_renderer: &mut GuideBlurGraphRenderer,
    camera_runtime: Option<&NativeCameraRuntime>,
    render_mode: NativeRendererRenderMode,
    environment_blend_mode: xr::EnvironmentBlendMode,
    native_passthrough: Option<&NativePassthroughRuntime>,
    mut stimulus_actions: Option<&mut StimulusVolumeActions>,
    mut gpu_stimulus_volume_renderer: Option<&mut GpuStimulusVolumeRenderer>,
    replay: &RecordedHandReplaySummary,
    secondary_replay: &RecordedHandReplaySummary,
    gpu_mesh_stats: &GpuMeshReplayStats,
    mut gpu_hand_mesh_visual_renderer: Option<&mut GpuHandMeshVisualRenderer>,
    mut secondary_gpu_hand_mesh_visual_renderer: Option<&mut GpuHandMeshVisualRenderer>,
    mut gpu_hand_anchor_particle_renderer: Option<&mut GpuHandAnchorParticleRenderer>,
    mut secondary_gpu_hand_anchor_particle_renderer: Option<&mut GpuHandAnchorParticleRenderer>,
    mut openxr_environment_depth_runtime: Option<&mut OpenXrEnvironmentDepthRuntime>,
    gpu_environment_depth_particle_renderer: Option<&mut GpuEnvironmentDepthParticleRenderer>,
    mut gpu_sdf_field_renderer: Option<&mut GpuSdfFieldRenderer>,
    mut secondary_gpu_sdf_field_renderer: Option<&mut GpuSdfFieldRenderer>,
    gpu_timestamp_tracker: &mut GpuTimestampTracker,
    private_extension_slot_runtime: &mut PrivateExtensionSlotRuntime,
    live_hand_compact: &mut LiveHandCompactInput,
    compact_hand_input_source_mode: CompactHandInputSourceMode,
    replay_visual_proof_enabled: bool,
    sdf_visual_enabled: bool,
    sdf_update_period_frames: u64,
    hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings,
    hand_mesh_graft_copies_enabled: bool,
    hand_mesh_graft_copy_scale: f32,
    hand_mesh_real_hands_visible: bool,
    hand_anchor_particle_settings: NativeHandAnchorParticleSettings,
    environment_depth_settings: NativeEnvironmentDepthSettings,
    stimulus_volume_settings: NativeStimulusVolumeSettings,
    projection_target_settings: ProjectionTargetSettings,
    camera_output_mode: NativeCameraOutputMode,
    guide_blur_enabled: bool,
    guide_graph_resolution: NativeGuideGraphResolution,
    camera_ycbcr_mode: crate::native_renderer_options::NativeCameraYcbcrMode,
    camera_resolution_profile: crate::native_renderer_options::NativeCameraResolutionProfile,
    camera_reader_max_images: u32,
    camera_quality_profile: NativeCameraQualityProfile,
    camera_sync_mode: NativeCameraSyncMode,
    camera_luma_diagnostic_enabled: bool,
    swapchain_color_format_mode: NativeSwapchainColorFormatMode,
    camera_direct_border_opacity: f32,
    projection_border_stretch_settings: NativeProjectionBorderStretchSettings,
    private_layer_settings: NativePrivateLayerSettings,
    projection_metadata: &CameraProjectionMetadata,
) -> Result<(), String> {
    let mut swapchain: Option<ProjectionSwapchain> = None;
    let mut event_storage = xr::EventDataBuffer::new();
    let mut session_running = false;
    let mut app_running = true;
    let mut frame_slot = 0_usize;
    let mut frame_count = 0_u64;
    let mut pacing_window_start = Instant::now();
    let mut pacing_window_frames = 0_u64;
    let mut camera_projection_stats = CameraProjectionFrameStats::default();
    let mut last_camera_import_cache_hits = 0_u64;
    let mut last_camera_import_cache_misses = 0_u64;
    let mut projection_target_state =
        ProjectionTargetState::new(projection_target_settings.clone());
    let mut breath_bridge = ManifoldBreathBridge::start(projection_target_settings);
    let mut previous_frame_instant = Instant::now();
    crate::marker(
        "projection-target",
        format!(
            "status=runtime-initialized {} bridgeStarted={}",
            projection_target_state.marker_fields(),
            breath_bridge.is_some()
        ),
    );
    if let Some(bridge) = breath_bridge.as_ref() {
        crate::marker(
            "projection-target",
            format!("status=bridge-config {}", bridge.marker_fields()),
        );
    }

    loop {
        crate::android_events::pump_activity_events(
            app,
            Duration::from_millis(0),
            &mut app_running,
        );
        if !app_running {
            match session.request_exit() {
                Ok(()) | Err(xr::sys::Result::ERROR_SESSION_NOT_RUNNING) => {}
                Err(error) => crate::marker(
                    "openxr-session",
                    format!("event=request-exit-error error={error}"),
                ),
            }
        }

        while let Some(event) = xr_instance
            .poll_event(&mut event_storage)
            .map_err(|error| format!("poll OpenXR event: {error}"))?
        {
            match event {
                xr::Event::SessionStateChanged(event) => {
                    crate::marker(
                        "openxr-session",
                        format!("event=state-changed state={:?}", event.state()),
                    );
                    match event.state() {
                        xr::SessionState::READY => {
                            session
                                .begin(VIEW_TYPE)
                                .map_err(|error| format!("begin OpenXR session: {error}"))?;
                            session_running = true;
                            crate::marker("openxr-session", "event=begin viewType=PRIMARY_STEREO");
                        }
                        xr::SessionState::STOPPING => {
                            session
                                .end()
                                .map_err(|error| format!("end OpenXR session: {error}"))?;
                            session_running = false;
                            crate::marker("openxr-session", "event=end");
                        }
                        xr::SessionState::EXITING | xr::SessionState::LOSS_PENDING => {
                            if let Some(swapchain) = swapchain.take() {
                                swapchain.destroy(vk_device);
                            }
                            return Ok(());
                        }
                        _ => {}
                    }
                }
                xr::Event::InstanceLossPending(_) => {
                    if let Some(swapchain) = swapchain.take() {
                        swapchain.destroy(vk_device);
                    }
                    return Ok(());
                }
                xr::Event::EventsLost(event) => crate::marker(
                    "openxr-session",
                    format!("event=events-lost count={}", event.lost_event_count()),
                ),
                _ => {}
            }
        }

        if !session_running {
            if !app_running {
                break;
            }
            std::thread::sleep(Duration::from_millis(25));
            continue;
        }

        let frame_state = frame_wait
            .wait()
            .map_err(|error| format!("wait OpenXR frame: {error}"))?;
        frame_stream
            .begin()
            .map_err(|error| format!("begin OpenXR frame: {error}"))?;

        if !frame_state.should_render {
            frame_stream
                .end(
                    frame_state.predicted_display_time,
                    environment_blend_mode,
                    &[],
                )
                .map_err(|error| format!("end skipped OpenXR frame: {error}"))?;
            continue;
        }

        let swapchain = ensure_projection_swapchain(
            xr_instance,
            system,
            vk_device,
            session,
            render_pass,
            color_format,
            &mut swapchain,
        )?;
        let (view_flags, views) = session
            .locate_views(
                VIEW_TYPE,
                frame_state.predicted_display_time,
                reference_space,
            )
            .map_err(|error| format!("locate OpenXR views: {error}"))?;
        if views.len() != VIEW_COUNT_USIZE
            || !view_flags.contains(xr::ViewStateFlags::ORIENTATION_VALID)
            || !view_flags.contains(xr::ViewStateFlags::POSITION_VALID)
        {
            if frame_count == 0 || frame_count % 120 == 0 {
                crate::marker(
                    "openxr-frame",
                    format!(
                        "event=skip reason=view-pose-invalid viewCount={} viewFlags={:?} openxrSubmitReady=false",
                        views.len(),
                        view_flags
                    ),
                );
            }
            frame_stream
                .end(
                    frame_state.predicted_display_time,
                    environment_blend_mode,
                    &[],
                )
                .map_err(|error| format!("end OpenXR frame without views: {error}"))?;
            frame_count = frame_count.saturating_add(1);
            continue;
        }

        let frame_instant = Instant::now();
        let dt_seconds = (frame_instant - previous_frame_instant)
            .as_secs_f32()
            .clamp(0.0, 1.0);
        previous_frame_instant = frame_instant;

        if let Some(actions) = stimulus_actions.as_deref_mut() {
            let controller_events = actions.sync_and_poll(
                session,
                reference_space,
                frame_state.predicted_display_time,
                frame_count,
                dt_seconds,
            );
            for input in controller_events.projection_target_inputs {
                projection_target_state.apply_input(input);
            }
            if controller_events.stimulus_randomize_triggered {
                if let Some(renderer) = gpu_stimulus_volume_renderer.as_deref_mut() {
                    renderer.randomize(stimulus_volume_settings, frame_count);
                }
            }
        }
        if let Some(bridge) = breath_bridge.as_mut() {
            if let Some(input) = bridge.poll_input(dt_seconds, frame_count) {
                projection_target_state.apply_input(input);
            }
        }
        projection_target_state.update_frame(dt_seconds);
        if frame_count == 0 || frame_count % 120 == 0 {
            let left_effective_rect =
                projection_target_state.effective_rect(projection_metadata.rect_for_eye(0));
            let right_effective_rect =
                projection_target_state.effective_rect(projection_metadata.rect_for_eye(1));
            crate::marker(
                "projection-target",
                format!(
                    "status=effective frame={} leftEffectiveTargetScreenUvRect={} rightEffectiveTargetScreenUvRect={} {}",
                    frame_count,
                    left_effective_rect.as_xywh_token(),
                    right_effective_rect.as_xywh_token(),
                    projection_target_state.marker_fields(),
                ),
            );
        }

        let image_index = swapchain
            .handle
            .acquire_image()
            .map_err(|error| format!("acquire OpenXR swapchain image: {error}"))?;
        let cmd = cmds[frame_slot];
        vk_device
            .wait_for_fences(&[fences[frame_slot]], true, u64::MAX)
            .map_err(|error| format!("wait Vulkan fence: {error}"))?;
        let retired_image_leases =
            camera_projection_renderer.retire_completed_frame_leases(frame_slot);
        let _completed_luma_diagnostic = camera_projection_renderer
            .collect_completed_luma_diagnostic(frame_slot, camera_luma_diagnostic_enabled);
        if retired_image_leases > 0 {
            crate::marker(
                "camera-sync",
                format!(
                    "status=gpu-frame-lease-retired frameSlot={} leaseCount={} cameraSyncActive=hold-image-until-gpu-fence producerConsumerSync=image-slot-held-until-vulkan-frame-fence",
                    frame_slot, retired_image_leases
                ),
            );
        }
        if let Some(runtime) = camera_runtime {
            let removed_hardware_buffer_ids = runtime.take_removed_hardware_buffer_ids();
            if !removed_hardware_buffer_ids.is_empty() {
                let evicted_count = camera_projection_renderer
                    .evict_removed_hardware_buffers(vk_device, &removed_hardware_buffer_ids);
                crate::marker(
                    "camera-projection-cache",
                    format!(
                        "status=buffer-removed-processed removedHardwareBufferIds={} cacheEvictionSignal=true cacheEvictionApplied={} evictedImportCount={}",
                        removed_hardware_buffer_ids
                            .iter()
                            .map(u64::to_string)
                            .collect::<Vec<_>>()
                            .join(","),
                        evicted_count > 0,
                        evicted_count
                    ),
                );
            }
        }
        let gpu_stage_timings = gpu_timestamp_tracker.read_frame(vk_device, frame_slot);
        vk_device
            .reset_fences(&[fences[frame_slot]])
            .map_err(|error| format!("reset Vulkan fence: {error}"))?;
        vk_device
            .reset_command_buffer(cmd, vk::CommandBufferResetFlags::empty())
            .map_err(|error| format!("reset Vulkan command buffer: {error}"))?;

        let record_started = Instant::now();
        let mut frame_timings = FrameCpuTimings::default();
        vk_device
            .begin_command_buffer(
                cmd,
                &vk::CommandBufferBeginInfo::default()
                    .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
            )
            .map_err(|error| format!("begin Vulkan command buffer: {error}"))?;
        gpu_timestamp_tracker.reset_frame(vk_device, cmd, frame_slot);
        let stage_started = Instant::now();
        gpu_timestamp_tracker.write_stage_start(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::CameraProjection,
        );
        let prepared_camera_projection = if render_mode.uses_custom_stereo_projection()
            && camera_output_mode.camera_import_enabled()
        {
            match camera_runtime
                .and_then(NativeCameraRuntime::latest_stereo_frame)
                .map(|stereo_frame| {
                    camera_projection_renderer.prepare_stereo_frame(
                        vk_device,
                        cmd,
                        frame_slot,
                        &stereo_frame,
                    )
                }) {
                Some(Ok(prepared)) => prepared,
                Some(Err(error)) => {
                    if frame_count == 0 || frame_count % 120 == 0 {
                        crate::marker(
                            "camera-projection",
                            format!(
                                "status=error reason={} cameraProjectionReady=false vulkanExternalImportReady=false",
                                crate::sanitize(&error)
                            ),
                        );
                    }
                    None
                }
                None => None,
            }
        } else {
            if frame_count == 0 {
                let disabled_reason = if !camera_output_mode.camera_import_enabled() {
                    camera_output_mode.marker_value()
                } else {
                    render_mode.marker_value()
                };
                crate::marker(
                        "camera-projection",
                        format!(
                            "status=disabled reason={} renderMode={} cameraOutputMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} solidBlackBackground={} cameraProjectionReady=false vulkanExternalImportReady=false finalExternalHwbSamples=0 guideTextureSamples=0",
                            disabled_reason,
                            render_mode.marker_value(),
                            camera_output_mode.marker_value(),
                            render_mode.uses_custom_stereo_projection(),
                            render_mode.uses_native_passthrough(),
                            render_mode.uses_solid_black_background(),
                        ),
                    );
            }
            None
        };
        if let Some(prepared) = prepared_camera_projection.as_ref() {
            if let Err(error) = camera_projection_renderer.record_luma_diagnostic(
                vk_device,
                cmd,
                frame_slot,
                prepared,
                camera_luma_diagnostic_enabled,
            ) {
                if frame_count == 0 || frame_count % 120 == 0 {
                    crate::marker(
                        "camera-luma-diagnostic",
                        format!(
                            "status=error reason={} cameraLumaDiagnosticReady=false",
                            crate::sanitize(&error)
                        ),
                    );
                }
            }
        }
        gpu_timestamp_tracker.write_stage_end(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::CameraProjection,
        );
        frame_timings.camera_acquire_import_ms = elapsed_ms(stage_started);
        if let (Some(runtime), Some(prepared)) =
            (camera_runtime, prepared_camera_projection.as_ref())
        {
            let hit_delta = prepared
                .stats
                .import_cache_hits
                .saturating_sub(last_camera_import_cache_hits);
            let miss_delta = prepared
                .stats
                .import_cache_misses
                .saturating_sub(last_camera_import_cache_misses);
            for _ in 0..hit_delta {
                runtime.record_hardware_buffer_cache_hit();
            }
            for _ in 0..miss_delta {
                runtime.record_hardware_buffer_cache_miss();
            }
            last_camera_import_cache_hits = prepared.stats.import_cache_hits;
            last_camera_import_cache_misses = prepared.stats.import_cache_misses;
            camera_projection_stats = prepared.stats.clone();
        }
        let stage_started = Instant::now();
        gpu_timestamp_tracker.write_stage_start(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::GuideGraph,
        );
        let guide_blur_stats = if render_mode.uses_custom_stereo_projection()
            && camera_output_mode.guide_graph_processing_enabled()
        {
            if let Some(prepared) = prepared_camera_projection.as_ref() {
                match guide_blur_graph_renderer.record_frame(
                    vk_device,
                    cmd,
                    prepared,
                    projection_metadata,
                    guide_blur_enabled,
                ) {
                    Ok(stats) => {
                        if let Some(runtime) = camera_runtime {
                            if stats.rendered {
                                runtime.record_guide_graph_render();
                            }
                            if stats.cache_hit {
                                runtime.record_guide_graph_cache_hit();
                            }
                        }
                        stats
                    }
                    Err(error) => {
                        if frame_count == 0 || frame_count % 120 == 0 {
                            crate::marker(
                                "guide-blur-graph",
                                GuideBlurGraphFrameStats::unavailable_with_options(
                                    guide_blur_enabled,
                                    guide_graph_resolution,
                                )
                                .marker_fields()
                                    + &format!(" status=error reason={}", crate::sanitize(&error)),
                            );
                        }
                        GuideBlurGraphFrameStats::unavailable_with_options(
                            guide_blur_enabled,
                            guide_graph_resolution,
                        )
                    }
                }
            } else {
                GuideBlurGraphFrameStats::unavailable_with_options(
                    guide_blur_enabled,
                    guide_graph_resolution,
                )
            }
        } else {
            GuideBlurGraphFrameStats::unavailable_with_options(
                guide_blur_enabled,
                guide_graph_resolution,
            )
        };
        gpu_timestamp_tracker.write_stage_end(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::GuideGraph,
        );
        frame_timings.guide_graph_ms = elapsed_ms(stage_started);
        let stage_started = Instant::now();
        let (live_hand_frames, live_hand_stats) =
            if compact_hand_input_source_mode.selects_live_frame() {
                live_hand_compact.locate_compact_frame(
                    reference_space,
                    frame_state.predicted_display_time,
                    replay.runtime_joint_count as usize,
                    replay.tip_length_count as usize,
                )
            } else {
                let mut stats = LiveHandCompactStats::default();
                stats.reason = if matches!(
                    compact_hand_input_source_mode,
                    CompactHandInputSourceMode::Disabled
                ) {
                    "compact-hand-input-source-disabled"
                } else {
                    "compact-hand-input-source-recorded-replay"
                };
                (LiveHandCompactFrameSet::default(), stats)
            };
        frame_timings.live_hand_ms = elapsed_ms(stage_started);
        let selected_primary_live_hand_frame = compact_hand_input_source_mode
            .selects_live_frame()
            .then(|| live_hand_frames.primary_frame())
            .flatten();
        let selected_primary_live_hand = if compact_hand_input_source_mode.selects_live_frame() {
            live_hand_frames.primary_handedness().unwrap_or("none")
        } else {
            "recorded"
        };
        let selected_secondary_live_hand_frame = compact_hand_input_source_mode
            .selects_live_frame()
            .then(|| live_hand_frames.secondary_frame())
            .flatten();
        let selected_secondary_live_hand = if compact_hand_input_source_mode.selects_live_frame() {
            live_hand_frames.secondary_handedness().unwrap_or("none")
        } else {
            "none"
        };
        let stage_started = Instant::now();
        gpu_timestamp_tracker.write_stage_start(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::HandSdf,
        );
        let gpu_sdf_stats = if let Some(renderer) = gpu_sdf_field_renderer.as_mut() {
            match renderer.record_compute_frame(
                vk_device,
                cmd,
                replay,
                frame_count,
                sdf_visual_enabled,
                sdf_update_period_frames,
                selected_primary_live_hand_frame,
                compact_hand_input_source_mode.allows_recorded_fallback(),
            ) {
                Ok(stats) => {
                    if stats.field_update_dispatched {
                        if let Some(runtime) = camera_runtime {
                            runtime.record_sdf_field_update();
                        }
                    }
                    stats
                }
                Err(error) => {
                    if frame_count == 0 || frame_count % 120 == 0 {
                        crate::marker(
                            "gpu-sdf-field",
                            format!(
                                "status=error reason={} dynamicSdfReady=false sdfVisualEffectVisible=false sdfComputePath=native-vulkan-compute-recorded-skinned-mesh-sdf-field legacySdfComputePath=native-vulkan-compute-recorded-validation-mesh-sdf-field cpuSdfPerFrame=false meshToSdfKernel=false targetSpaceMeshToSdfKernelAvailable=true fullSkinnedMeshSdfReady=false compactJointSkinningKernel=false jointMatrixSkinningKernel=false jointMatrixUploadPerFrame=false compactJointPoseUploadPerFrame=false sourceMeshToSdfKernel=false",
                                crate::sanitize(&error)
                            ),
                        );
                    }
                    GpuSdfFieldFrameStats::unavailable(replay, frame_count)
                }
            }
        } else {
            GpuSdfFieldFrameStats::unavailable(replay, frame_count)
        };
        let secondary_gpu_sdf_stats = if let (Some(renderer), Some(frame)) = (
            secondary_gpu_sdf_field_renderer.as_mut(),
            selected_secondary_live_hand_frame,
        ) {
            match renderer.record_compute_frame(
                vk_device,
                cmd,
                secondary_replay,
                frame_count,
                false,
                sdf_update_period_frames,
                Some(frame),
                false,
            ) {
                Ok(stats) => stats,
                Err(error) => {
                    if frame_count == 0 || frame_count % 120 == 0 {
                        crate::marker(
                            "hand-mesh-visual",
                            format!(
                                "status=secondary-skinning-error reason={} liveHandMeshVisualBothHandsPath=primary-only",
                                crate::sanitize(&error)
                            ),
                        );
                    }
                    GpuSdfFieldFrameStats::unavailable(secondary_replay, frame_count)
                }
            }
        } else {
            GpuSdfFieldFrameStats::unavailable(secondary_replay, frame_count)
        };
        gpu_timestamp_tracker.write_stage_end(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::HandSdf,
        );
        frame_timings.hand_sdf_prepare_ms = elapsed_ms(stage_started);
        let stage_started = Instant::now();
        gpu_timestamp_tracker.write_stage_start(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::HandMeshVisual,
        );
        let mut primary_hand_mesh_visual_stats = if let Some(renderer) =
            gpu_hand_mesh_visual_renderer.as_mut()
        {
            match renderer.record_frame(
                replay,
                frame_count,
                gpu_sdf_stats.skinning_ready,
                selected_primary_live_hand_frame,
                compact_hand_input_source_mode.allows_recorded_fallback(),
                selected_primary_live_hand,
                hand_mesh_visual_diagnostic_settings,
            ) {
                Ok(stats) => stats,
                Err(error) => {
                    if frame_count == 0 || frame_count % 120 == 0 {
                        crate::marker(
                                "hand-mesh-visual",
                                format!(
                                    "status=error reason={} animatedHandMeshVisualReady=false animatedHandMeshVisualVisible=false",
                                    crate::sanitize(&error)
                                ),
                            );
                    }
                    GpuHandMeshVisualFrameStats::unavailable(
                        replay,
                        frame_count,
                        selected_primary_live_hand,
                        hand_mesh_visual_diagnostic_settings,
                    )
                }
            }
        } else {
            GpuHandMeshVisualFrameStats::unavailable(
                replay,
                frame_count,
                selected_primary_live_hand,
                hand_mesh_visual_diagnostic_settings,
            )
        };
        let mut secondary_hand_mesh_visual_stats = if let (Some(renderer), Some(_frame)) = (
            secondary_gpu_hand_mesh_visual_renderer.as_mut(),
            selected_secondary_live_hand_frame,
        ) {
            match renderer.record_frame(
                secondary_replay,
                frame_count,
                secondary_gpu_sdf_stats.skinning_ready,
                selected_secondary_live_hand_frame,
                false,
                selected_secondary_live_hand,
                hand_mesh_visual_diagnostic_settings,
            ) {
                Ok(stats) => stats,
                Err(error) => {
                    if frame_count == 0 || frame_count % 120 == 0 {
                        crate::marker(
                            "hand-mesh-visual",
                            format!(
                                "status=secondary-visual-error reason={} liveHandMeshVisualBothHandsPath=primary-only",
                                crate::sanitize(&error)
                            ),
                        );
                    }
                    GpuHandMeshVisualFrameStats::unavailable(
                        secondary_replay,
                        frame_count,
                        selected_secondary_live_hand,
                        hand_mesh_visual_diagnostic_settings,
                    )
                }
            }
        } else {
            GpuHandMeshVisualFrameStats::unavailable(
                secondary_replay,
                frame_count,
                selected_secondary_live_hand,
                hand_mesh_visual_diagnostic_settings,
            )
        };
        if hand_mesh_graft_copies_enabled
            && primary_hand_mesh_visual_stats.ready
            && secondary_hand_mesh_visual_stats.ready
            && primary_hand_mesh_visual_stats.live_compact_input_frame
            && secondary_hand_mesh_visual_stats.live_compact_input_frame
        {
            if let (Some(renderer), Some(source_frame), Some(target_frame)) = (
                gpu_hand_mesh_visual_renderer.as_ref(),
                selected_primary_live_hand_frame,
                selected_secondary_live_hand_frame,
            ) {
                match renderer.prepare_graft_copies(
                    vk_device,
                    source_frame,
                    target_frame,
                    hand_mesh_graft_copy_scale,
                ) {
                    Ok(copy_count) => primary_hand_mesh_visual_stats.graft_copy_count = copy_count,
                    Err(error) => crate::marker(
                        "hand-mesh-visual",
                        format!(
                            "status=graft-primary-unavailable reason={} handMeshGraftCopiesEnabled=true handMeshGraftCopyCount=0",
                            crate::sanitize(&error)
                        ),
                    ),
                }
            }
            if let (Some(renderer), Some(source_frame), Some(target_frame)) = (
                secondary_gpu_hand_mesh_visual_renderer.as_ref(),
                selected_secondary_live_hand_frame,
                selected_primary_live_hand_frame,
            ) {
                match renderer.prepare_graft_copies(
                    vk_device,
                    source_frame,
                    target_frame,
                    hand_mesh_graft_copy_scale,
                ) {
                    Ok(copy_count) => secondary_hand_mesh_visual_stats.graft_copy_count = copy_count,
                    Err(error) => crate::marker(
                        "hand-mesh-visual",
                        format!(
                            "status=graft-secondary-unavailable reason={} handMeshGraftCopiesEnabled=true handMeshGraftCopyCount=0",
                            crate::sanitize(&error)
                        ),
                    ),
                }
            }
        }
        let hand_mesh_visual_stats = GpuHandMeshVisualFrameSetStats::new(
            primary_hand_mesh_visual_stats,
            secondary_hand_mesh_visual_stats,
            hand_mesh_graft_copies_enabled,
            hand_mesh_graft_copy_scale,
        );
        let hand_anchor_particle_stats = GpuHandAnchorParticleFrameSetStats::new(
            &hand_mesh_visual_stats,
            hand_anchor_particle_settings,
        );
        if let Some(renderer) = gpu_hand_anchor_particle_renderer.as_mut() {
            renderer.record_compute_frame(
                vk_device,
                cmd,
                &hand_anchor_particle_stats.primary,
                hand_anchor_particle_settings,
                frame_count,
            );
        }
        if let Some(renderer) = secondary_gpu_hand_anchor_particle_renderer.as_mut() {
            renderer.record_compute_frame(
                vk_device,
                cmd,
                &hand_anchor_particle_stats.secondary,
                hand_anchor_particle_settings,
                frame_count,
            );
        }
        let particle_sort_eye_projection = views
            .first()
            .map(hand_mesh_visual_eye_projection)
            .unwrap_or_default();
        let openxr_environment_depth_frame =
            if environment_depth_settings.runtime_provider_requested() {
                openxr_environment_depth_runtime
                    .as_mut()
                    .and_then(|runtime| {
                        runtime.acquire(
                            reference_space,
                            frame_state.predicted_display_time,
                            view_flags,
                            &views,
                            frame_count,
                        )
                    })
            } else {
                None
            };
        if let Some(renderer) = gpu_hand_anchor_particle_renderer.as_ref() {
            renderer.record_sort_frame(
                vk_device,
                cmd,
                &hand_anchor_particle_stats.primary,
                hand_anchor_particle_settings,
                particle_sort_eye_projection,
                frame_count,
            );
        }
        if let Some(renderer) = secondary_gpu_hand_anchor_particle_renderer.as_ref() {
            renderer.record_sort_frame(
                vk_device,
                cmd,
                &hand_anchor_particle_stats.secondary,
                hand_anchor_particle_settings,
                particle_sort_eye_projection,
                frame_count,
            );
        }
        let environment_depth_particle_stats =
            if let Some(renderer) = gpu_environment_depth_particle_renderer.as_ref() {
                if environment_depth_settings.synthetic_gpu_proof_requested() {
                    renderer.record_compute_frame(
                        vk_device,
                        cmd,
                        environment_depth_settings,
                        particle_sort_eye_projection,
                        frame_count,
                    )
                } else if let Some(depth_frame) = openxr_environment_depth_frame.as_ref() {
                    renderer.record_runtime_depth_frame(
                        vk_device,
                        cmd,
                        environment_depth_settings,
                        depth_frame,
                        frame_count,
                    )
                } else if environment_depth_settings.runtime_provider_requested()
                    && environment_depth_settings.mode_draws_particles()
                {
                    GpuEnvironmentDepthParticleFrameStats::runtime_depth_not_acquired(
                        environment_depth_settings,
                        environment_depth_settings.particle_capacity,
                    )
                } else {
                    GpuEnvironmentDepthParticleFrameStats::unavailable(environment_depth_settings)
                }
            } else {
                GpuEnvironmentDepthParticleFrameStats::unavailable(environment_depth_settings)
            };
        gpu_timestamp_tracker.write_stage_end(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::HandMeshVisual,
        );
        frame_timings.hand_mesh_visual_ms = elapsed_ms(stage_started);
        let private_extension_stats = if camera_output_mode.private_layer_projection_enabled() {
            let stats = private_extension_slot_runtime.record_frame(
                vk_device,
                cmd,
                frame_count,
                guide_blur_stats.ready,
                gpu_sdf_stats.ready,
                prepared_camera_projection.as_ref(),
                projection_metadata,
                private_layer_settings,
            );
            if let Some(runtime) = camera_runtime {
                runtime.record_private_layer_invocation();
            }
            stats
        } else {
            PrivateExtensionSlotFrameStats::default()
        };
        let stage_started = Instant::now();
        gpu_timestamp_tracker.write_stage_start(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::ProjectionComposite,
        );
        let stimulus_volume_stats =
            if let Some(renderer) = gpu_stimulus_volume_renderer.as_deref_mut() {
                renderer.record_compute_frame(vk_device, cmd, stimulus_volume_settings, frame_count)
            } else {
                GpuStimulusVolumeFrameStats::unavailable(stimulus_volume_settings)
            };
        let replay_visual_stats = record_projection_diagnostic(
            vk_device,
            cmd,
            swapchain,
            image_index as usize,
            frame_count,
            replay,
            render_mode,
            camera_output_mode,
            camera_direct_border_opacity,
            hand_mesh_real_hands_visible,
            replay_visual_proof_enabled,
            projection_border_stretch_settings,
            prepared_camera_projection.as_ref(),
            gpu_stimulus_volume_renderer.as_deref(),
            &stimulus_volume_stats,
            stimulus_volume_settings,
            private_extension_slot_runtime,
            &private_extension_stats,
            private_layer_settings,
            guide_blur_graph_renderer,
            &guide_blur_stats,
            guide_blur_enabled,
            gpu_hand_mesh_visual_renderer.as_deref(),
            secondary_gpu_hand_mesh_visual_renderer.as_deref(),
            &hand_mesh_visual_stats,
            gpu_hand_anchor_particle_renderer.as_deref(),
            secondary_gpu_hand_anchor_particle_renderer.as_deref(),
            &hand_anchor_particle_stats,
            gpu_environment_depth_particle_renderer.as_deref(),
            &environment_depth_particle_stats,
            environment_depth_settings,
            gpu_sdf_field_renderer.as_deref(),
            &gpu_sdf_stats,
            &live_hand_frames,
            &views,
            compact_hand_input_source_mode.selects_live_frame()
                && hand_mesh_visual_diagnostic_settings.enabled,
            hand_mesh_visual_diagnostic_settings,
            projection_metadata,
            &projection_target_state,
        );
        gpu_timestamp_tracker.write_stage_end(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::ProjectionComposite,
        );
        frame_timings.projection_composite_ms = elapsed_ms(stage_started);
        vk_device
            .end_command_buffer(cmd)
            .map_err(|error| format!("end Vulkan command buffer: {error}"))?;
        let record_ms = record_started.elapsed().as_secs_f64() * 1000.0;
        frame_timings.command_record_ms = record_ms;

        let stage_started = Instant::now();
        swapchain
            .handle
            .wait_image(xr::Duration::INFINITE)
            .map_err(|error| format!("wait OpenXR swapchain image: {error}"))?;
        frame_timings.swapchain_wait_ms = elapsed_ms(stage_started);
        let submit_started = Instant::now();
        vk_device
            .queue_submit(
                queue,
                &[vk::SubmitInfo::default().command_buffers(&[cmd])],
                fences[frame_slot],
            )
            .map_err(|error| format!("submit Vulkan queue: {error}"))?;
        let submit_ms = submit_started.elapsed().as_secs_f64() * 1000.0;
        frame_timings.queue_submit_ms = submit_ms;
        swapchain
            .handle
            .release_image()
            .map_err(|error| format!("release OpenXR swapchain image: {error}"))?;

        let rect = xr::Rect2Di {
            offset: xr::Offset2Di { x: 0, y: 0 },
            extent: xr::Extent2Di {
                width: swapchain.extent.width as _,
                height: swapchain.extent.height as _,
            },
        };
        let projection_views = [
            xr::CompositionLayerProjectionView::new()
                .pose(views[0].pose)
                .fov(views[0].fov)
                .sub_image(
                    xr::SwapchainSubImage::new()
                        .swapchain(&swapchain.handle)
                        .image_array_index(0)
                        .image_rect(rect),
                ),
            xr::CompositionLayerProjectionView::new()
                .pose(views[1].pose)
                .fov(views[1].fov)
                .sub_image(
                    xr::SwapchainSubImage::new()
                        .swapchain(&swapchain.handle)
                        .image_array_index(1)
                        .image_rect(rect),
                ),
        ];
        let projection_layer_flags = if render_mode.projection_layer_alpha_blend() {
            xr::CompositionLayerFlags::BLEND_TEXTURE_SOURCE_ALPHA
                | xr::CompositionLayerFlags::CORRECT_CHROMATIC_ABERRATION
        } else {
            xr::CompositionLayerFlags::CORRECT_CHROMATIC_ABERRATION
        };
        let projection_layer = xr::CompositionLayerProjection::new()
            .layer_flags(projection_layer_flags)
            .space(reference_space)
            .views(&projection_views);
        let passthrough_layer =
            native_passthrough.map(|runtime| runtime.composition_layer_raw(reference_space));
        let mut layers: Vec<&xr::CompositionLayerBase<xr::Vulkan>> = Vec::with_capacity(2);
        if let Some(passthrough_layer) = passthrough_layer.as_ref() {
            layers.push(passthrough_layer_base(passthrough_layer));
        }
        layers.push(&projection_layer);
        let stage_started = Instant::now();
        frame_stream
            .end(
                frame_state.predicted_display_time,
                environment_blend_mode,
                &layers,
            )
            .map_err(|error| format!("end OpenXR frame: {error}"))?;
        frame_timings.openxr_end_frame_ms = elapsed_ms(stage_started);

        frame_count = frame_count.saturating_add(1);
        pacing_window_frames = pacing_window_frames.saturating_add(1);
        if let Some(runtime) = camera_runtime {
            runtime.record_xr_frame_submitted();
        }
        if frame_count == 1 || frame_count % 120 == 0 {
            let window_secs = pacing_window_start.elapsed().as_secs_f64().max(0.001);
            let observed_openxr_fps = pacing_window_frames as f64 / window_secs;
            write_projection_scorecard(
                camera_runtime,
                frame_count,
                observed_openxr_fps,
                record_ms,
                submit_ms,
                frame_timings,
                gpu_stage_timings,
                swapchain.extent,
                replay,
                replay_visual_stats,
                gpu_mesh_stats,
                &hand_mesh_visual_stats,
                &hand_anchor_particle_stats,
                &environment_depth_particle_stats,
                environment_depth_settings,
                &stimulus_volume_stats,
                stimulus_volume_settings,
                &gpu_sdf_stats,
                &guide_blur_stats,
                private_extension_stats,
                &live_hand_stats,
                compact_hand_input_source_mode,
                render_mode,
                camera_output_mode,
                camera_ycbcr_mode,
                camera_resolution_profile,
                camera_reader_max_images,
                camera_quality_profile,
                camera_sync_mode,
                camera_luma_diagnostic_enabled,
                swapchain_color_format_mode,
                camera_direct_border_opacity,
                environment_blend_mode,
                native_passthrough.is_some(),
                hand_mesh_real_hands_visible,
                replay_visual_proof_enabled,
                &camera_projection_stats,
                projection_metadata,
                projection_border_stretch_settings,
            );
            pacing_window_start = Instant::now();
            pacing_window_frames = 0;
        }
        frame_slot = (frame_slot + 1) % PIPELINE_DEPTH as usize;
    }

    if let Some(swapchain) = swapchain.take() {
        swapchain.destroy(vk_device);
    }
    Ok(())
}

fn create_projection_reference_space(
    session: &xr::Session<xr::Vulkan>,
    environment_depth_settings: NativeEnvironmentDepthSettings,
) -> Result<xr::Space, String> {
    let prefer_stage = environment_depth_settings.reference_space_marker_value() == "openxr-stage";
    let first = if prefer_stage {
        xr::ReferenceSpaceType::STAGE
    } else {
        xr::ReferenceSpaceType::LOCAL
    };
    let second = if prefer_stage {
        xr::ReferenceSpaceType::LOCAL
    } else {
        xr::ReferenceSpaceType::STAGE
    };
    match session.create_reference_space(first, xr::Posef::IDENTITY) {
        Ok(space) => Ok(space),
        Err(first_error) => session
            .create_reference_space(second, xr::Posef::IDENTITY)
            .map_err(|second_error| {
                format!(
                    "create OpenXR reference space: first={first:?} failed with {first_error}; fallback={second:?} failed with {second_error}"
                )
            }),
    }
}

fn choose_swapchain_format(
    session: &xr::Session<xr::Vulkan>,
    format_mode: NativeSwapchainColorFormatMode,
) -> Result<vk::Format, String> {
    let supported = session
        .enumerate_swapchain_formats()
        .map_err(|error| format!("enumerate OpenXR swapchain formats: {error}"))?;
    let srgb_preferred = [
        vk::Format::R8G8B8A8_SRGB,
        vk::Format::B8G8R8A8_SRGB,
        vk::Format::R8G8B8A8_UNORM,
        vk::Format::B8G8R8A8_UNORM,
        vk::Format::A2B10G10R10_UNORM_PACK32,
    ];
    let unorm_preferred = [
        vk::Format::R8G8B8A8_UNORM,
        vk::Format::B8G8R8A8_UNORM,
        vk::Format::R8G8B8A8_SRGB,
        vk::Format::B8G8R8A8_SRGB,
        vk::Format::A2B10G10R10_UNORM_PACK32,
    ];
    let candidates = match format_mode {
        NativeSwapchainColorFormatMode::Auto | NativeSwapchainColorFormatMode::Srgb => {
            &srgb_preferred
        }
        NativeSwapchainColorFormatMode::Unorm => &unorm_preferred,
    };
    for candidate in candidates {
        if supported.contains(&(candidate.as_raw() as u32)) {
            crate::marker(
                "openxr-swapchain-format",
                format!(
                    "status=selected property={} swapchainColorFormatMode={} colorFormat={:?} selectionSource=preferred-list supportedFormatCount={} srgbPreferred={} unormPreferred={}",
                    PROP_SWAPCHAIN_COLOR_FORMAT_MODE,
                    format_mode.marker_value(),
                    candidate,
                    supported.len(),
                    matches!(
                        format_mode,
                        NativeSwapchainColorFormatMode::Auto | NativeSwapchainColorFormatMode::Srgb
                    ),
                    matches!(format_mode, NativeSwapchainColorFormatMode::Unorm),
                ),
            );
            return Ok(*candidate);
        }
    }
    let fallback = supported
        .first()
        .map(|format| vk::Format::from_raw(*format as i32))
        .ok_or_else(|| "OpenXR session returned no swapchain formats".to_string())?;
    crate::marker(
        "openxr-swapchain-format",
        format!(
            "status=selected property={} swapchainColorFormatMode={} colorFormat={:?} selectionSource=first-supported supportedFormatCount={} srgbPreferred={} unormPreferred={}",
            PROP_SWAPCHAIN_COLOR_FORMAT_MODE,
            format_mode.marker_value(),
            fallback,
            supported.len(),
            matches!(
                format_mode,
                NativeSwapchainColorFormatMode::Auto | NativeSwapchainColorFormatMode::Srgb
            ),
            matches!(format_mode, NativeSwapchainColorFormatMode::Unorm),
        ),
    );
    Ok(fallback)
}

unsafe fn create_projection_render_pass(
    device: &ash::Device,
    color_format: vk::Format,
) -> Result<vk::RenderPass, String> {
    let color_attachment = vk::AttachmentDescription {
        format: color_format,
        samples: vk::SampleCountFlags::TYPE_1,
        load_op: vk::AttachmentLoadOp::CLEAR,
        store_op: vk::AttachmentStoreOp::STORE,
        initial_layout: vk::ImageLayout::UNDEFINED,
        final_layout: vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
        ..Default::default()
    };
    let color_refs = [vk::AttachmentReference {
        attachment: 0,
        layout: vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
    }];
    let subpasses = [vk::SubpassDescription::default()
        .color_attachments(&color_refs)
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)];
    let dependencies = [vk::SubpassDependency {
        src_subpass: vk::SUBPASS_EXTERNAL,
        dst_subpass: 0,
        src_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
        dst_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
        dst_access_mask: vk::AccessFlags::COLOR_ATTACHMENT_WRITE,
        ..Default::default()
    }];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&[color_attachment])
                .subpasses(&subpasses)
                .dependencies(&dependencies),
            None,
        )
        .map_err(|error| format!("create Vulkan render pass: {error}"))
}

unsafe fn ensure_projection_swapchain<'a>(
    xr_instance: &xr::Instance,
    system: xr::SystemId,
    device: &ash::Device,
    session: &xr::Session<xr::Vulkan>,
    render_pass: vk::RenderPass,
    color_format: vk::Format,
    swapchain: &'a mut Option<ProjectionSwapchain>,
) -> Result<&'a mut ProjectionSwapchain, String> {
    if swapchain.is_none() {
        let views = xr_instance
            .enumerate_view_configuration_views(system, VIEW_TYPE)
            .map_err(|error| format!("enumerate OpenXR view configuration: {error}"))?;
        if views.len() != VIEW_COUNT_USIZE {
            return Err(format!(
                "expected {VIEW_COUNT} OpenXR views, got {}",
                views.len()
            ));
        }
        if views[0].recommended_image_rect_width != views[1].recommended_image_rect_width
            || views[0].recommended_image_rect_height != views[1].recommended_image_rect_height
        {
            return Err("native diagnostic swapchain expects matching eye dimensions".to_string());
        }
        let extent = vk::Extent2D {
            width: views[0].recommended_image_rect_width,
            height: views[0].recommended_image_rect_height,
        };
        let handle = session
            .create_swapchain(&xr::SwapchainCreateInfo {
                create_flags: xr::SwapchainCreateFlags::EMPTY,
                usage_flags: xr::SwapchainUsageFlags::COLOR_ATTACHMENT
                    | xr::SwapchainUsageFlags::SAMPLED,
                format: color_format.as_raw() as u32,
                sample_count: 1,
                width: extent.width,
                height: extent.height,
                face_count: 1,
                array_size: VIEW_COUNT,
                mip_count: 1,
            })
            .map_err(|error| format!("create OpenXR swapchain: {error}"))?;
        let color_images = handle
            .enumerate_images()
            .map_err(|error| format!("enumerate OpenXR swapchain images: {error}"))?;
        let mut buffers = Vec::with_capacity(color_images.len());
        for color_image in color_images {
            buffers.push(create_projection_swapchain_buffer(
                device,
                render_pass,
                color_format,
                vk::Image::from_raw(color_image),
                extent,
            )?);
        }
        crate::marker(
            "openxr-swapchain",
            format!(
                "status=created width={} height={} views={} images={} colorFormat={:?} renderPath=per-eye-array-layer-clear openxrSubmitReady=pending",
                extent.width,
                extent.height,
                VIEW_COUNT,
                buffers.len(),
                color_format
            ),
        );
        *swapchain = Some(ProjectionSwapchain {
            handle,
            buffers,
            extent,
            render_pass,
        });
    }

    swapchain
        .as_mut()
        .ok_or_else(|| "projection swapchain was not initialized".to_string())
}

unsafe fn create_projection_swapchain_buffer(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    color_format: vk::Format,
    image: vk::Image,
    extent: vk::Extent2D,
) -> Result<ProjectionSwapchainBuffer, String> {
    let mut eyes = Vec::with_capacity(VIEW_COUNT_USIZE);
    for eye_index in 0..VIEW_COUNT {
        let view = device
            .create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .image(image)
                    .view_type(vk::ImageViewType::TYPE_2D)
                    .format(color_format)
                    .subresource_range(vk::ImageSubresourceRange {
                        aspect_mask: vk::ImageAspectFlags::COLOR,
                        base_mip_level: 0,
                        level_count: 1,
                        base_array_layer: eye_index,
                        layer_count: 1,
                    }),
                None,
            )
            .map_err(|error| format!("create Vulkan swapchain image view: {error}"))?;
        let framebuffer = device
            .create_framebuffer(
                &vk::FramebufferCreateInfo::default()
                    .render_pass(render_pass)
                    .attachments(&[view])
                    .width(extent.width)
                    .height(extent.height)
                    .layers(1),
                None,
            )
            .map_err(|error| format!("create Vulkan framebuffer: {error}"))?;
        eyes.push(ProjectionEyeTarget { view, framebuffer });
    }
    Ok(ProjectionSwapchainBuffer { eyes })
}

unsafe fn record_projection_diagnostic(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    swapchain: &ProjectionSwapchain,
    image_index: usize,
    frame_count: u64,
    replay: &RecordedHandReplaySummary,
    render_mode: NativeRendererRenderMode,
    camera_output_mode: NativeCameraOutputMode,
    camera_direct_border_opacity: f32,
    hand_mesh_real_hands_visible: bool,
    draw_recorded_replay_overlay: bool,
    projection_settings: NativeProjectionBorderStretchSettings,
    prepared_camera_projection: Option<&PreparedCameraProjection>,
    gpu_stimulus_volume_renderer: Option<&GpuStimulusVolumeRenderer>,
    stimulus_volume_stats: &GpuStimulusVolumeFrameStats,
    stimulus_volume_settings: NativeStimulusVolumeSettings,
    private_extension_slot_runtime: &PrivateExtensionSlotRuntime,
    private_extension_stats: &PrivateExtensionSlotFrameStats,
    private_layer_settings: NativePrivateLayerSettings,
    guide_blur_graph_renderer: &GuideBlurGraphRenderer,
    guide_blur_stats: &GuideBlurGraphFrameStats,
    guide_blur_enabled: bool,
    gpu_hand_mesh_visual_renderer: Option<&GpuHandMeshVisualRenderer>,
    secondary_gpu_hand_mesh_visual_renderer: Option<&GpuHandMeshVisualRenderer>,
    hand_mesh_visual_stats: &GpuHandMeshVisualFrameSetStats,
    gpu_hand_anchor_particle_renderer: Option<&GpuHandAnchorParticleRenderer>,
    secondary_gpu_hand_anchor_particle_renderer: Option<&GpuHandAnchorParticleRenderer>,
    hand_anchor_particle_stats: &GpuHandAnchorParticleFrameSetStats,
    gpu_environment_depth_particle_renderer: Option<&GpuEnvironmentDepthParticleRenderer>,
    environment_depth_particle_stats: &GpuEnvironmentDepthParticleFrameStats,
    environment_depth_settings: NativeEnvironmentDepthSettings,
    gpu_sdf_field_renderer: Option<&GpuSdfFieldRenderer>,
    gpu_sdf_stats: &GpuSdfFieldFrameStats,
    live_hand_frames: &LiveHandCompactFrameSet,
    views: &[xr::View],
    draw_live_hand_target_overlay: bool,
    diagnostic_settings: HandMeshVisualDiagnosticSettings,
    projection_metadata: &CameraProjectionMetadata,
    projection_target_state: &ProjectionTargetState,
) -> ReplayVisualStats {
    let buffer = &swapchain.buffers[image_index];
    let mut visual_stats = ReplayVisualStats::default();
    let passthrough_alpha_clear = render_mode.projection_layer_alpha_blend();
    let stimulus_volume_route = render_mode.uses_stimulus_volume();
    let custom_stereo_projection = render_mode.uses_custom_stereo_projection();
    let draw_base_hand_meshes = should_draw_base_hand_meshes(
        hand_mesh_real_hands_visible,
        draw_recorded_replay_overlay,
        diagnostic_settings,
    );
    for (eye_index, eye) in buffer.eyes.iter().enumerate() {
        let target_rect =
            projection_target_state.effective_rect(projection_metadata.rect_for_eye(eye_index));
        let eye_projection = views
            .get(eye_index)
            .map(hand_mesh_visual_eye_projection)
            .unwrap_or_default();
        let background = if stimulus_volume_route || render_mode.uses_solid_black_background() {
            [0.0, 0.0, 0.0, 1.0]
        } else if passthrough_alpha_clear {
            [0.0, 0.0, 0.0, 0.0]
        } else if eye_index == 0 {
            [0.012, 0.030, 0.038, 1.0]
        } else {
            [0.034, 0.016, 0.050, 1.0]
        };
        if hand_mesh_visual_stats.graft_copies_visible() {
            if let Some(renderer) = gpu_hand_mesh_visual_renderer {
                if hand_mesh_visual_stats.primary.graft_copy_count > 0 {
                    renderer.record_graft_buffer_barrier(device, cmd);
                }
            }
            if let Some(renderer) = secondary_gpu_hand_mesh_visual_renderer {
                if hand_mesh_visual_stats.secondary.graft_copy_count > 0 {
                    renderer.record_graft_buffer_barrier(device, cmd);
                }
            }
        }
        let clear_values = [vk::ClearValue {
            color: vk::ClearColorValue {
                float32: background,
            },
        }];
        device.cmd_begin_render_pass(
            cmd,
            &vk::RenderPassBeginInfo::default()
                .render_pass(swapchain.render_pass)
                .framebuffer(eye.framebuffer)
                .render_area(vk::Rect2D {
                    offset: vk::Offset2D::default(),
                    extent: swapchain.extent,
                })
                .clear_values(&clear_values),
            vk::SubpassContents::INLINE,
        );
        if stimulus_volume_route {
            if let Some(renderer) = gpu_stimulus_volume_renderer {
                if stimulus_volume_stats.ready {
                    renderer.record_projection_eye(
                        device,
                        cmd,
                        swapchain.extent,
                        eye_index,
                        stimulus_volume_settings,
                    );
                }
            }
        }
        if custom_stereo_projection && camera_output_mode.direct_hwb_forced() {
            if let Some(prepared) = prepared_camera_projection {
                record_camera_projection_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    eye_index,
                    prepared,
                    projection_metadata,
                    camera_direct_border_opacity,
                );
            }
        } else if custom_stereo_projection
            && camera_output_mode.private_layer_projection_enabled()
            && private_extension_stats.ready
        {
            if let Some(prepared) = prepared_camera_projection {
                private_extension_slot_runtime.record_projection_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    eye_index,
                    target_rect,
                    prepared,
                    projection_metadata,
                    frame_count,
                    private_layer_settings,
                );
            }
        } else if custom_stereo_projection
            && camera_output_mode.guide_projection_enabled()
            && guide_blur_stats.ready
        {
            guide_blur_graph_renderer.record_projection_eye(
                device,
                cmd,
                swapchain.extent,
                eye_index,
                target_rect,
                projection_settings,
                guide_blur_enabled,
            );
        } else if custom_stereo_projection {
            if let Some(prepared) = prepared_camera_projection {
                record_camera_projection_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    eye_index,
                    prepared,
                    projection_metadata,
                    camera_direct_border_opacity,
                );
            }
        }
        if custom_stereo_projection {
            if let Some(renderer) = gpu_sdf_field_renderer {
                if gpu_sdf_stats.ready && gpu_sdf_stats.overlay_visible {
                    renderer.record_overlay_eye(device, cmd, swapchain.extent, target_rect);
                }
            }
        }
        if draw_base_hand_meshes {
            if let Some(renderer) = gpu_hand_mesh_visual_renderer {
                if hand_mesh_visual_stats.primary.ready && hand_mesh_visual_stats.primary.visible {
                    renderer.record_overlay_eye(
                        device,
                        cmd,
                        swapchain.extent,
                        target_rect,
                        eye_projection,
                        &hand_mesh_visual_stats.primary,
                    );
                }
            }
        }
        if draw_base_hand_meshes {
            if let Some(renderer) = secondary_gpu_hand_mesh_visual_renderer {
                if hand_mesh_visual_stats.secondary.ready
                    && hand_mesh_visual_stats.secondary.visible
                {
                    renderer.record_overlay_eye(
                        device,
                        cmd,
                        swapchain.extent,
                        target_rect,
                        eye_projection,
                        &hand_mesh_visual_stats.secondary,
                    );
                }
            }
        }
        for draw_target in
            hand_anchor_particle_draw_order(hand_anchor_particle_stats, eye_projection)
        {
            match draw_target {
                HandAnchorParticleDrawTarget::Primary => {
                    if let Some(renderer) = gpu_hand_anchor_particle_renderer {
                        renderer.record_overlay_eye(
                            device,
                            cmd,
                            swapchain.extent,
                            eye_projection,
                            &hand_anchor_particle_stats.primary,
                            hand_anchor_particle_stats.settings,
                        );
                    }
                }
                HandAnchorParticleDrawTarget::Secondary => {
                    if let Some(renderer) = secondary_gpu_hand_anchor_particle_renderer {
                        renderer.record_overlay_eye(
                            device,
                            cmd,
                            swapchain.extent,
                            eye_projection,
                            &hand_anchor_particle_stats.secondary,
                            hand_anchor_particle_stats.settings,
                        );
                    }
                }
            }
        }
        if let Some(renderer) = gpu_environment_depth_particle_renderer {
            renderer.record_overlay_eye(
                device,
                cmd,
                swapchain.extent,
                eye_projection,
                environment_depth_particle_stats,
                environment_depth_settings,
            );
        }
        if let Some(renderer) = gpu_hand_mesh_visual_renderer {
            if hand_mesh_visual_stats.primary.graft_copy_count > 0 {
                renderer.record_graft_overlay_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    target_rect,
                    eye_projection,
                    &hand_mesh_visual_stats.primary,
                );
            }
        }
        if let Some(renderer) = secondary_gpu_hand_mesh_visual_renderer {
            if hand_mesh_visual_stats.secondary.graft_copy_count > 0 {
                renderer.record_graft_overlay_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    target_rect,
                    eye_projection,
                    &hand_mesh_visual_stats.secondary,
                );
            }
        }
        if eye_index == 0 && !draw_recorded_replay_overlay {
            if let Some(eye_visual_stats) =
                live_gpu_mesh_visual_stats(hand_mesh_visual_stats, diagnostic_settings, target_rect)
            {
                visual_stats = eye_visual_stats;
            }
        }
        if custom_stereo_projection
            && draw_live_hand_target_overlay
            && !hand_mesh_visual_stats.any_ready()
        {
            let eye_visual_stats = record_live_hand_target_overlay(
                device,
                cmd,
                swapchain.extent,
                target_rect,
                live_hand_frames,
                diagnostic_settings,
            );
            if eye_index == 0 && eye_visual_stats.visual_point_count > 0 {
                visual_stats = eye_visual_stats;
            }
        }
        if custom_stereo_projection && draw_recorded_replay_overlay {
            let eye_visual_stats = record_recorded_hand_overlay(
                device,
                cmd,
                swapchain.extent,
                target_rect,
                frame_count,
                replay,
                hand_mesh_visual_stats.diagnostic_settings(),
            );
            if eye_index == 0 {
                visual_stats = eye_visual_stats;
            }
        }
        device.cmd_end_render_pass(cmd);
    }
    visual_stats
}

fn should_draw_base_hand_meshes(
    hand_mesh_real_hands_visible: bool,
    draw_recorded_replay_overlay: bool,
    diagnostic_settings: HandMeshVisualDiagnosticSettings,
) -> bool {
    hand_mesh_real_hands_visible || draw_recorded_replay_overlay || diagnostic_settings.enabled
}

#[derive(Clone, Copy, Debug)]
enum HandAnchorParticleDrawTarget {
    Primary,
    Secondary,
}

fn hand_anchor_particle_draw_order(
    stats: &GpuHandAnchorParticleFrameSetStats,
    eye_projection: HandMeshVisualEyeProjection,
) -> [HandAnchorParticleDrawTarget; 2] {
    use HandAnchorParticleDrawTarget::{Primary, Secondary};
    match stats.settings.ordering_mode {
        NativeHandAnchorParticleOrderingMode::SecondaryThenPrimary => [Secondary, Primary],
        NativeHandAnchorParticleOrderingMode::NearHandFirst => {
            if hand_forward_depth_m(stats.primary.center_position, eye_projection)
                <= hand_forward_depth_m(stats.secondary.center_position, eye_projection)
            {
                [Primary, Secondary]
            } else {
                [Secondary, Primary]
            }
        }
        NativeHandAnchorParticleOrderingMode::FarHandFirst
        | NativeHandAnchorParticleOrderingMode::PerParticleBackToFront => {
            if hand_forward_depth_m(stats.primary.center_position, eye_projection)
                >= hand_forward_depth_m(stats.secondary.center_position, eye_projection)
            {
                [Primary, Secondary]
            } else {
                [Secondary, Primary]
            }
        }
        NativeHandAnchorParticleOrderingMode::PrimaryThenSecondary => [Primary, Secondary],
    }
}

fn hand_forward_depth_m(
    center_position: [f32; 4],
    eye_projection: HandMeshVisualEyeProjection,
) -> f32 {
    let forward = rotate_by_quat(eye_projection.orientation_xyzw, [0.0, 0.0, -1.0]);
    let delta = [
        center_position[0] - eye_projection.position[0],
        center_position[1] - eye_projection.position[1],
        center_position[2] - eye_projection.position[2],
    ];
    dot3(delta, forward)
}

fn rotate_by_quat(quat: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let q = normalize_quat(quat);
    let uv = cross3([q[0], q[1], q[2]], vector);
    let uuv = cross3([q[0], q[1], q[2]], uv);
    [
        vector[0] + uv[0] * (2.0 * q[3]) + uuv[0] * 2.0,
        vector[1] + uv[1] * (2.0 * q[3]) + uuv[1] * 2.0,
        vector[2] + uv[2] * (2.0 * q[3]) + uuv[2] * 2.0,
    ]
}

fn normalize_quat(quat: [f32; 4]) -> [f32; 4] {
    let length_sq = dot4(quat, quat).max(0.000000000001);
    let inv_length = 1.0 / length_sq.sqrt();
    [
        quat[0] * inv_length,
        quat[1] * inv_length,
        quat[2] * inv_length,
        quat[3] * inv_length,
    ]
}

fn cross3(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

fn dot3(left: [f32; 3], right: [f32; 3]) -> f32 {
    left[0] * right[0] + left[1] * right[1] + left[2] * right[2]
}

fn dot4(left: [f32; 4], right: [f32; 4]) -> f32 {
    left[0] * right[0] + left[1] * right[1] + left[2] * right[2] + left[3] * right[3]
}

fn live_gpu_mesh_visual_stats(
    hand_mesh_visual_stats: &GpuHandMeshVisualFrameSetStats,
    diagnostic_settings: HandMeshVisualDiagnosticSettings,
    target_rect: TargetRect,
) -> Option<ReplayVisualStats> {
    let mut rects = Vec::new();
    let mut visual_point_count = 0_u64;
    let mut frame_index = 0_u32;
    let mut timestamp_ns = 0_u64;

    for stats in [
        &hand_mesh_visual_stats.primary,
        &hand_mesh_visual_stats.secondary,
    ] {
        if !stats.ready || !stats.visible || !stats.live_compact_input_frame {
            continue;
        }
        rects.push(live_gpu_mesh_local_evidence_rect(
            stats.handedness,
            diagnostic_settings,
        ));
        visual_point_count = visual_point_count.saturating_add(
            stats.drawn_vertex_count as u64 * u64::from(stats.graft_copy_count.saturating_add(1)),
        );
        if frame_index == 0 {
            frame_index = stats.frame_index;
            timestamp_ns = stats.timestamp_ns;
        }
    }

    let local_evidence_rect = EvidenceUvRect::union_all(&rects)?;
    crate::marker(
        "live-hand-mesh-target-proof",
        format!(
            "status=frame liveHandMeshTargetProofVisible=true liveHandMeshTargetProofPath=gpu-skinned-resident-triangle-fill liveHandMeshTargetProofPointCount={} liveHandMeshTargetProofScreenUvRect={} liveHandMeshJointOverlaySuppressed=true",
            visual_point_count,
            local_evidence_rect
                .to_screen_rect(target_rect)
                .marker_value()
        ),
    );
    Some(ReplayVisualStats {
        frame_index,
        timestamp_ns,
        visual_point_count,
        local_evidence_rect,
    })
}

fn live_gpu_mesh_local_evidence_rect(
    handedness: &'static str,
    diagnostic_settings: HandMeshVisualDiagnosticSettings,
) -> EvidenceUvRect {
    let base = EvidenceUvRect::from_bounds(0.08, 0.10, 0.92, 0.98);
    let diagnostic_scale = if diagnostic_settings.enabled {
        1.35
    } else {
        1.0
    };
    let mut offset = if diagnostic_settings.enabled {
        diagnostic_settings.offset_uv
    } else {
        [0.0, 0.0]
    };
    if diagnostic_settings.enabled {
        offset[0] += match handedness {
            "left" => -0.16,
            "right" => 0.16,
            _ => 0.0,
        };
    }
    base.scaled_about_center(diagnostic_scale, offset)
        .padded(0.025)
}

unsafe fn record_live_hand_target_overlay(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    target_rect: TargetRect,
    live_hand_frames: &LiveHandCompactFrameSet,
    diagnostic_settings: HandMeshVisualDiagnosticSettings,
) -> ReplayVisualStats {
    let mut stats = ReplayVisualStats::default();
    let mut rects = Vec::new();
    let mut point_count = 0_u64;
    if let Some(frame) = live_hand_frames.left.as_ref() {
        let points =
            live_hand_overlay_points(frame, LiveHandOverlaySlot::Left, diagnostic_settings);
        if !points.is_empty() {
            record_live_hand_points_and_bones(
                device,
                cmd,
                extent,
                target_rect,
                &points,
                [0.0, 1.0, 0.92, 1.0],
                [1.0, 0.06, 0.85, 1.0],
            );
            rects.push(EvidenceUvRect::from_bounds_for_points(&points).padded(0.045));
            point_count += points.len() as u64;
            stats.frame_index = frame.frame_index;
            stats.timestamp_ns = frame.timestamp_ns;
        }
    }
    if let Some(frame) = live_hand_frames.right.as_ref() {
        let points =
            live_hand_overlay_points(frame, LiveHandOverlaySlot::Right, diagnostic_settings);
        if !points.is_empty() {
            record_live_hand_points_and_bones(
                device,
                cmd,
                extent,
                target_rect,
                &points,
                [1.0, 0.90, 0.0, 1.0],
                [0.1, 0.85, 1.0, 1.0],
            );
            rects.push(EvidenceUvRect::from_bounds_for_points(&points).padded(0.045));
            point_count += points.len() as u64;
            if stats.frame_index == 0 {
                stats.frame_index = frame.frame_index;
                stats.timestamp_ns = frame.timestamp_ns;
            }
        }
    }
    if let Some(rect) = EvidenceUvRect::union_all(&rects) {
        stats.local_evidence_rect = rect;
        stats.visual_point_count = point_count;
        crate::marker(
            "live-hand-target-overlay",
            format!(
                "status=frame liveHandTargetOverlayVisible=true liveHandTargetOverlayPath=target-local-live-joint-mesh-proof liveHandTargetOverlayPointCount={} liveHandTargetOverlayScreenUvRect={}",
                point_count,
                rect.to_screen_rect(target_rect).marker_value()
            ),
        );
    }
    stats
}

#[derive(Clone, Copy)]
enum LiveHandOverlaySlot {
    Left,
    Right,
}

fn live_hand_overlay_points(
    frame: &RecordedHandSkinningFrame,
    slot: LiveHandOverlaySlot,
    diagnostic_settings: HandMeshVisualDiagnosticSettings,
) -> Vec<[f32; 2]> {
    let positions = frame
        .runtime_joint_poses
        .iter()
        .map(|pose| {
            [
                pose.translation_pad[0],
                pose.translation_pad[1],
                pose.translation_pad[2],
            ]
        })
        .collect::<Vec<_>>();
    let normalized = normalize_live_joint_points(&positions);
    let center_x = match slot {
        LiveHandOverlaySlot::Left => 0.34,
        LiveHandOverlaySlot::Right => 0.66,
    };
    let center_y = (0.55 + diagnostic_settings.offset_uv[1] * 0.45).clamp(0.32, 0.72);
    let scale_x = 0.46;
    let scale_y = 0.64;
    normalized
        .iter()
        .map(|[x, y]| {
            [
                (center_x + (*x - 0.5) * scale_x).clamp(0.035, 0.965),
                (center_y + (*y - 0.5) * scale_y).clamp(0.035, 0.965),
            ]
        })
        .collect()
}

fn normalize_live_joint_points(points: &[[f32; 3]]) -> Vec<[f32; 2]> {
    if points.is_empty() {
        return Vec::new();
    }
    let mut min_x = f32::INFINITY;
    let mut max_x = f32::NEG_INFINITY;
    let mut min_y = f32::INFINITY;
    let mut max_y = f32::NEG_INFINITY;
    for [x, y, _] in points.iter().copied() {
        min_x = min_x.min(x);
        max_x = max_x.max(x);
        min_y = min_y.min(y);
        max_y = max_y.max(y);
    }
    let width = (max_x - min_x).max(1.0e-5);
    let height = (max_y - min_y).max(1.0e-5);
    points
        .iter()
        .map(|[x, y, _]| {
            let normalized_x = ((*x - min_x) / width * 0.72 + 0.14).clamp(0.0, 1.0);
            let normalized_y = (1.0 - ((*y - min_y) / height * 0.72 + 0.14)).clamp(0.0, 1.0);
            [normalized_x, normalized_y]
        })
        .collect()
}

unsafe fn record_live_hand_points_and_bones(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    target_rect: TargetRect,
    points: &[[f32; 2]],
    bone_color: [f32; 4],
    joint_color: [f32; 4],
) {
    const BONES: &[(usize, usize)] = &[
        (0, 1),
        (0, 2),
        (2, 3),
        (3, 4),
        (0, 5),
        (5, 6),
        (6, 7),
        (7, 8),
        (0, 9),
        (9, 10),
        (10, 11),
        (11, 12),
        (0, 13),
        (13, 14),
        (14, 15),
        (15, 16),
        (0, 17),
        (17, 18),
        (18, 19),
        (19, 20),
        (5, 9),
        (9, 13),
        (13, 17),
    ];
    for (left, right) in BONES {
        if let (Some(a), Some(b)) = (points.get(*left), points.get(*right)) {
            clear_live_hand_segment_in_target(device, cmd, extent, target_rect, bone_color, *a, *b);
        }
    }
    for (index, point) in points.iter().copied().enumerate() {
        let size = if index == 0 || index == 1 {
            0.030
        } else {
            0.022
        };
        clear_rect_in_target(
            device,
            cmd,
            extent,
            target_rect,
            joint_color,
            point[0] - size * 0.5,
            point[1] - size * 0.5,
            size,
            size,
        );
    }
}

unsafe fn clear_live_hand_segment_in_target(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    target_rect: TargetRect,
    color: [f32; 4],
    a: [f32; 2],
    b: [f32; 2],
) {
    let thickness = 0.014_f32;
    let min_x = a[0].min(b[0]) - thickness * 0.5;
    let min_y = a[1].min(b[1]) - thickness * 0.5;
    let max_x = a[0].max(b[0]) + thickness * 0.5;
    let max_y = a[1].max(b[1]) + thickness * 0.5;
    clear_rect_in_target(
        device,
        cmd,
        extent,
        target_rect,
        color,
        min_x,
        min_y,
        (max_x - min_x).max(thickness),
        (max_y - min_y).max(thickness),
    );
}

unsafe fn record_recorded_hand_overlay(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    target_rect: TargetRect,
    frame_count: u64,
    replay: &RecordedHandReplaySummary,
    diagnostic_settings: HandMeshVisualDiagnosticSettings,
) -> ReplayVisualStats {
    let frame = replay.frame_for_count(frame_count);
    record_recorded_hand_bounds(device, cmd, extent, target_rect);
    ReplayVisualStats {
        frame_index: frame.frame_index,
        timestamp_ns: frame.timestamp_ns,
        visual_point_count: frame.normalized_points.len() as u64,
        local_evidence_rect: EvidenceUvRect::from_points(
            &frame.normalized_points,
            diagnostic_settings,
        ),
    }
}

unsafe fn record_recorded_hand_bounds(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    target_rect: TargetRect,
) {
    clear_rect_in_target(
        device,
        cmd,
        extent,
        target_rect,
        [0.0, 0.80, 1.0, 1.0],
        0.120,
        0.120,
        0.760,
        0.018,
    );
    clear_rect_in_target(
        device,
        cmd,
        extent,
        target_rect,
        [1.0, 0.72, 0.05, 1.0],
        0.120,
        0.862,
        0.760,
        0.018,
    );
    clear_rect_in_target(
        device,
        cmd,
        extent,
        target_rect,
        [0.0, 1.0, 0.82, 1.0],
        0.120,
        0.120,
        0.018,
        0.760,
    );
    clear_rect_in_target(
        device,
        cmd,
        extent,
        target_rect,
        [0.0, 1.0, 0.82, 1.0],
        0.862,
        0.120,
        0.018,
        0.760,
    );
}

unsafe fn clear_rect(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    color: [f32; 4],
    x: f32,
    y: f32,
    width: f32,
    height: f32,
) {
    let attachment = vk::ClearAttachment {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        color_attachment: 0,
        clear_value: vk::ClearValue {
            color: vk::ClearColorValue { float32: color },
        },
    };
    let rect = vk::ClearRect {
        rect: vk::Rect2D {
            offset: vk::Offset2D {
                x: (extent.width as f32 * x).round() as i32,
                y: (extent.height as f32 * y).round() as i32,
            },
            extent: vk::Extent2D {
                width: (extent.width as f32 * width).round().max(1.0) as u32,
                height: (extent.height as f32 * height).round().max(1.0) as u32,
            },
        },
        base_array_layer: 0,
        layer_count: 1,
    };
    device.cmd_clear_attachments(cmd, &[attachment], &[rect]);
}

unsafe fn clear_rect_in_target(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    extent: vk::Extent2D,
    target_rect: TargetRect,
    color: [f32; 4],
    x: f32,
    y: f32,
    width: f32,
    height: f32,
) {
    clear_rect(
        device,
        cmd,
        extent,
        color,
        target_rect.x + x * target_rect.width,
        target_rect.y + y * target_rect.height,
        width * target_rect.width,
        height * target_rect.height,
    );
}

fn write_projection_scorecard(
    camera_runtime: Option<&NativeCameraRuntime>,
    frame_count: u64,
    observed_openxr_fps: f64,
    record_ms: f64,
    submit_ms: f64,
    frame_timings: FrameCpuTimings,
    gpu_stage_timings: GpuStageTimings,
    extent: vk::Extent2D,
    replay: &RecordedHandReplaySummary,
    replay_visual_stats: ReplayVisualStats,
    gpu_mesh_stats: &GpuMeshReplayStats,
    hand_mesh_visual_stats: &GpuHandMeshVisualFrameSetStats,
    hand_anchor_particle_stats: &GpuHandAnchorParticleFrameSetStats,
    environment_depth_particle_stats: &GpuEnvironmentDepthParticleFrameStats,
    environment_depth_settings: NativeEnvironmentDepthSettings,
    stimulus_volume_stats: &GpuStimulusVolumeFrameStats,
    stimulus_volume_settings: NativeStimulusVolumeSettings,
    gpu_sdf_stats: &GpuSdfFieldFrameStats,
    guide_blur_stats: &GuideBlurGraphFrameStats,
    private_extension_stats: PrivateExtensionSlotFrameStats,
    live_hand_stats: &LiveHandCompactStats,
    compact_hand_input_source_mode: CompactHandInputSourceMode,
    render_mode: NativeRendererRenderMode,
    camera_output_mode: NativeCameraOutputMode,
    camera_ycbcr_mode: crate::native_renderer_options::NativeCameraYcbcrMode,
    camera_resolution_profile: crate::native_renderer_options::NativeCameraResolutionProfile,
    camera_reader_max_images: u32,
    camera_quality_profile: NativeCameraQualityProfile,
    camera_sync_mode: NativeCameraSyncMode,
    camera_luma_diagnostic_enabled: bool,
    swapchain_color_format_mode: NativeSwapchainColorFormatMode,
    camera_direct_border_opacity: f32,
    environment_blend_mode: xr::EnvironmentBlendMode,
    native_passthrough_layer_active: bool,
    hand_mesh_real_hands_visible: bool,
    replay_visual_proof_enabled: bool,
    camera_projection_stats: &CameraProjectionFrameStats,
    projection_metadata: &CameraProjectionMetadata,
    projection_settings: NativeProjectionBorderStretchSettings,
) {
    let (
        camera_frames_acquired,
        hardware_buffer_imports,
        hardware_buffer_cache_hits,
        hardware_buffer_cache_misses,
        guide_graph_renders,
        guide_graph_cache_hits,
        sdf_field_updates,
        private_layer_invocations,
        xr_frames_submitted,
        stale_frames,
        release_retire_count,
    ) = if let Some(runtime) = camera_runtime {
        let counters = runtime.counter_snapshot();
        (
            counters.camera_frames_acquired,
            counters.hardware_buffer_imports,
            counters.hardware_buffer_cache_hits,
            counters.hardware_buffer_cache_misses,
            counters.guide_graph_renders,
            counters.guide_graph_cache_hits,
            counters.sdf_field_updates,
            counters.private_layer_invocations,
            counters.xr_frames_submitted,
            counters.stale_frames,
            counters.release_retire_count,
        )
    } else {
        (0, 0, 0, 0, 0, 0, 0, 0, frame_count, 0, 0)
    };
    let custom_camera_output_enabled =
        render_mode.uses_custom_stereo_projection() && camera_output_mode.camera_import_enabled();
    let private_projection_active =
        camera_output_mode.private_layer_projection_enabled() && private_extension_stats.ready;
    let guide_projection_active =
        camera_output_mode.guide_projection_enabled() && guide_blur_stats.ready;
    let direct_projection_active = custom_camera_output_enabled
        && camera_projection_stats.rendered
        && (camera_output_mode.direct_hwb_forced()
            || (!private_projection_active && !guide_projection_active));
    let camera_projection_ready =
        direct_projection_active || guide_projection_active || private_projection_active;
    let direct_hwb_projection_diagnostic =
        direct_projection_active && camera_output_mode.direct_hwb_forced();
    let camera_projection_path = if render_mode.uses_native_passthrough() {
        render_mode.disabled_camera_projection_path()
    } else if render_mode.uses_solid_black_background() {
        render_mode.disabled_camera_projection_path()
    } else if !camera_output_mode.camera_import_enabled() {
        "disabled-camera-output"
    } else if camera_output_mode.direct_hwb_forced() {
        "metadata-target-direct-hwb-forced"
    } else if private_projection_active {
        "metadata-target-private-extension-slot-final"
    } else if guide_projection_active && projection_settings.peripheral_stretch_active() {
        "metadata-target-guide-texture-peripheral-stretch-final"
    } else if guide_projection_active {
        "metadata-target-guide-texture-final"
    } else {
        "metadata-target-direct-hwb-fallback"
    };
    let planned_final_external_hwb_samples = if private_projection_active {
        1
    } else if direct_projection_active {
        2
    } else {
        0
    };
    let planned_guide_texture_samples = if private_projection_active {
        5
    } else if guide_projection_active {
        1
    } else {
        0
    };
    let actual_final_external_hwb_samples = planned_final_external_hwb_samples;
    let actual_guide_texture_samples = planned_guide_texture_samples;
    let native_passthrough_real_hand_mesh_visible =
        render_mode.uses_native_passthrough() && hand_mesh_real_hands_visible;
    let solid_black_real_hand_mesh_visible =
        render_mode.uses_solid_black_background() && hand_mesh_real_hands_visible;
    let hand_mesh_visual_evidence_rects =
        replay_visual_stats.hand_mesh_screen_rect_marker_fields(projection_metadata);
    let sdf_visual_evidence_rects =
        replay_visual_stats.sdf_screen_rect_marker_fields(projection_metadata);
    let camera_capture_result_correlation_ready =
        camera_projection_stats.left_capture_result.ready()
            && camera_projection_stats.right_capture_result.ready();
    crate::marker(
        "timing-scorecard",
        format!(
            "frame={} renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} solidBlackBackground={} openxrDefaultHandVisualRequested={} nativePassthroughLayerActive={} environmentBlendMode={:?} projectionLayerAlphaBlend={} cameraRuntimeMode={} cameraOutputMode={} cameraYcbcrMode={} cameraYcbcrConversionMode={} cameraResolutionProfile={} readerMaxImages={} cameraQualityProfile={} cameraSyncRequested={} cameraSyncActive={} cameraSyncImplementation={} cameraLumaDiagnosticRequested={} swapchainColorFormatMode={} directHwbBorderOpacity={:.3} camera_frames_acquired={} hardware_buffer_imports={} hardware_buffer_cache_hits={} hardware_buffer_cache_misses={} guide_graph_renders={} guide_graph_cache_hits={} sdf_field_updates={} private_layer_invocations={} xr_frames_submitted={} stale_frames={} releaseRetireCount={} observedOpenXrFps={:.1} recordCpuMs={:.3} submitCpuMs={:.3} {} {} {} {} projectionExtent={}x{} openxrSubmitReady=true vulkanExternalImportReady={} cameraProjectionReady={} directHwbProjectionDiagnostic={} cameraProjectionPath={} metadataDrivenTargetFootprint={} guideProjectionCoverage={} {} {} plannedFinalExternalHwbSamples={} plannedGuideTextureSamples={} actualFinalExternalHwbSamples={} actualGuideTextureSamples={} leftCameraId={} rightCameraId={} leftImageDataspace={} leftImageDataspaceStatus={} rightImageDataspace={} rightImageDataspaceStatus={} leftSourceFrame={} rightSourceFrame={} leftHardwareBufferId={} rightHardwareBufferId={} leftImportSequence={} rightImportSequence={} stereoPairDeltaNs={} stereoPairingPolicy={} cameraCaptureResultCorrelationReady={} {} {} {} {} recordedHandReplayVisible={} recordedHandReplayTarget=metadata-target-screen-uv {} {} replayVisualFrame={} replayTimestampNs={} replayVisualPointCount={} compactJointOverlayVisible=false handMeshRealHandsVisible={} nativePassthroughRealHandMeshVisible={} solidBlackRealHandMeshVisible={} {} {} sdfTarget=metadata-target-screen-uv {} {} {} {} {} visualAcceptance=target-area-orientation-pending-screenshot projectionReady=true",
            frame_count,
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            render_mode.uses_native_passthrough(),
            render_mode.uses_solid_black_background(),
            render_mode.requests_openxr_default_hand_visual(),
            native_passthrough_layer_active,
            environment_blend_mode,
            render_mode.projection_layer_alpha_blend(),
            render_mode.camera_runtime_mode(),
            camera_output_mode.marker_value(),
            camera_ycbcr_mode.marker_value(),
            camera_ycbcr_mode.conversion_mode(),
            camera_resolution_profile.marker_value(),
            camera_reader_max_images,
            camera_quality_profile.marker_value(),
            camera_sync_mode.marker_value(),
            camera_sync_mode.active_marker_value(),
            camera_sync_mode.implementation_status(),
            camera_luma_diagnostic_enabled,
            swapchain_color_format_mode.marker_value(),
            camera_direct_border_opacity,
            camera_frames_acquired,
            hardware_buffer_imports,
            hardware_buffer_cache_hits,
            hardware_buffer_cache_misses,
            guide_graph_renders,
            guide_graph_cache_hits,
            sdf_field_updates,
            private_layer_invocations,
            xr_frames_submitted,
            stale_frames,
            release_retire_count,
            observed_openxr_fps,
            record_ms,
            submit_ms,
            frame_timings.marker_fields(),
            gpu_stage_timings.marker_fields(),
            stimulus_volume_settings.marker_fields(),
            stimulus_volume_stats.marker_fields(),
            extent.width,
            extent.height,
            camera_projection_stats.rendered,
            camera_projection_ready,
            direct_hwb_projection_diagnostic,
            camera_projection_path,
            render_mode.uses_custom_stereo_projection(),
            if projection_settings.peripheral_stretch_active() {
                "full-eye-peripheral-stretch"
            } else {
                "metadata-target-only"
            },
            projection_settings.marker_fields(),
            projection_metadata.marker_fields(),
            planned_final_external_hwb_samples,
            planned_guide_texture_samples,
            actual_final_external_hwb_samples,
            actual_guide_texture_samples,
            crate::sanitize(&camera_projection_stats.left_camera_id),
            crate::sanitize(&camera_projection_stats.right_camera_id),
            optional_i32_marker(camera_projection_stats.left_image_dataspace),
            crate::sanitize(&camera_projection_stats.left_image_dataspace_status),
            optional_i32_marker(camera_projection_stats.right_image_dataspace),
            crate::sanitize(&camera_projection_stats.right_image_dataspace_status),
            camera_projection_stats.left_source_frame,
            camera_projection_stats.right_source_frame,
            camera_projection_stats.left_hardware_buffer_id,
            camera_projection_stats.right_hardware_buffer_id,
            camera_projection_stats.left_import_sequence,
            camera_projection_stats.right_import_sequence,
            camera_projection_stats.pair_delta_ns,
            camera_projection_stats.stereo_pairing_policy,
            camera_capture_result_correlation_ready,
            camera_projection_stats
                .left_capture_result
                .scorecard_marker_fields("left"),
            camera_projection_stats
                .right_capture_result
                .scorecard_marker_fields("right"),
            camera_projection_stats.luma_diagnostic.marker_fields(),
            guide_blur_stats.marker_fields(),
            replay_visual_proof_enabled,
            replay.marker_fields(),
            live_hand_stats.marker_fields(),
            replay_visual_stats.frame_index,
            replay_visual_stats.timestamp_ns,
            replay_visual_stats.visual_point_count,
            hand_mesh_real_hands_visible,
            native_passthrough_real_hand_mesh_visible,
            solid_black_real_hand_mesh_visible,
            format!(
                "recordedReplayVisualProofEnabled={} compactHandInputSourceMode={} compactHandInputSelectsLiveFrame={} compactHandInputAllowsRecordedFallback={} recordedReplayVisualAcceptance={} {}",
                replay_visual_proof_enabled,
                compact_hand_input_source_mode.marker_value(),
                compact_hand_input_source_mode.selects_live_frame(),
                compact_hand_input_source_mode.allows_recorded_fallback(),
                if replay_visual_proof_enabled {
                    "pending-headset-screenshot"
                } else {
                    "not-requested"
                },
                hand_mesh_visual_stats.marker_fields()
            ),
            hand_mesh_visual_evidence_rects,
            gpu_sdf_stats.marker_fields(),
            sdf_visual_evidence_rects,
            gpu_mesh_stats.marker_fields(),
            private_extension_stats.marker_fields(),
            hand_anchor_particle_stats.marker_fields()
        ),
    );
    crate::marker(
        "hand-anchor-particles",
        format!(
            "status=frame frame={} observedOpenXrFps={:.1} compactHandInputSourceMode={} compactHandInputSelectsLiveFrame={} {}",
            frame_count,
            observed_openxr_fps,
            compact_hand_input_source_mode.marker_value(),
            compact_hand_input_source_mode.selects_live_frame(),
            hand_anchor_particle_stats.marker_fields()
        ),
    );
    crate::marker(
        "environment-depth-particles",
        format!(
            "status=frame frame={} observedOpenXrFps={:.1} renderMode={} nativePassthroughRequested={} {} {} environmentDepthGpuReconstructMs={:.3} environmentDepthGpuDrawMs={:.3}",
            frame_count,
            observed_openxr_fps,
            render_mode.marker_value(),
            render_mode.uses_native_passthrough(),
            environment_depth_settings.marker_fields(),
            environment_depth_particle_stats.marker_fields(),
            gpu_stage_timings.stage_ms(GpuTimestampStage::HandMeshVisual),
            gpu_stage_timings.stage_ms(GpuTimestampStage::ProjectionComposite),
        ),
    );
    crate::marker(
        "stimulus-volume",
        format!(
            "status=scorecard frame={} observedOpenXrFps={:.1} renderMode={} nativePassthroughRequested={} projectionLayerAlphaBlend={} stimulusVolumeGpuMs={:.3} {} {}",
            frame_count,
            observed_openxr_fps,
            render_mode.marker_value(),
            render_mode.uses_native_passthrough(),
            render_mode.projection_layer_alpha_blend(),
            gpu_stage_timings.stage_ms(GpuTimestampStage::ProjectionComposite),
            stimulus_volume_settings.marker_fields(),
            stimulus_volume_stats.marker_fields(),
        ),
    );
    crate::marker(
        "gpu-sdf-field",
        format!(
            "status=frame frame={} observedOpenXrFps={:.1} recordCpuMs={:.3} submitCpuMs={:.3} handSdfPrepareCpuMs={:.3} handSdfGpuMs={:.3} sdfTarget=metadata-target-screen-uv recordedReplayVisualProofEnabled={} compactHandInputSourceMode={} compactHandInputSelectsLiveFrame={} compactHandInputAllowsRecordedFallback={} {} {}",
            frame_count,
            observed_openxr_fps,
            record_ms,
            submit_ms,
            frame_timings.hand_sdf_prepare_ms,
            gpu_stage_timings.stage_ms(GpuTimestampStage::HandSdf),
            replay_visual_proof_enabled,
            compact_hand_input_source_mode.marker_value(),
            compact_hand_input_source_mode.selects_live_frame(),
            compact_hand_input_source_mode.allows_recorded_fallback(),
            gpu_sdf_stats.marker_fields(),
            sdf_visual_evidence_rects
        ),
    );
    crate::marker(
        "guide-blur-graph",
        format!(
            "status=frame frame={} observedOpenXrFps={:.1} recordCpuMs={:.3} submitCpuMs={:.3} guideGraphCpuMs={:.3} guideGraphGpuMs={:.3} guideTarget=metadata-target-screen-uv guideProjectionCoverage={} {} {}",
            frame_count,
            observed_openxr_fps,
            record_ms,
            submit_ms,
            frame_timings.guide_graph_ms,
            gpu_stage_timings.stage_ms(GpuTimestampStage::GuideGraph),
            if projection_settings.peripheral_stretch_active() {
                "full-eye-peripheral-stretch"
            } else {
                "metadata-target-only"
            },
            projection_settings.marker_fields(),
            guide_blur_stats.marker_fields()
        ),
    );
    crate::marker(
        "hand-mesh-visual",
        format!(
            "status=frame frame={} observedOpenXrFps={:.1} recordCpuMs={:.3} submitCpuMs={:.3} handMeshVisualCpuMs={:.3} handMeshVisualGpuMs={:.3} handTarget={} handMeshVisualWorldSpaceReady={} handMeshRealHandsVisible={} nativePassthroughRealHandMeshVisible={} solidBlackRealHandMeshVisible={} recordedReplayVisualProofEnabled={} compactHandInputSourceMode={} compactHandInputSelectsLiveFrame={} compactHandInputAllowsRecordedFallback={} {} {}",
            frame_count,
            observed_openxr_fps,
            record_ms,
            submit_ms,
            frame_timings.hand_mesh_visual_ms,
            gpu_stage_timings.stage_ms(GpuTimestampStage::HandMeshVisual),
            if hand_mesh_visual_stats.primary.live_compact_input_frame
                || hand_mesh_visual_stats.secondary.live_compact_input_frame
            {
                "openxr-eye-fov-world-space"
            } else {
                "metadata-target-screen-uv-diagnostic"
            },
            hand_mesh_visual_stats.any_ready(),
            hand_mesh_real_hands_visible,
            native_passthrough_real_hand_mesh_visible,
            solid_black_real_hand_mesh_visible,
            replay_visual_proof_enabled,
            compact_hand_input_source_mode.marker_value(),
            compact_hand_input_source_mode.selects_live_frame(),
            compact_hand_input_source_mode.allows_recorded_fallback(),
            hand_mesh_visual_stats.marker_fields(),
            hand_mesh_visual_evidence_rects
        ),
    );
    crate::marker(
        "gpu-timestamp-timing",
        format!(
            "status=frame frame={} observedOpenXrFps={:.1} {}",
            frame_count,
            observed_openxr_fps,
            gpu_stage_timings.marker_fields()
        ),
    );
    crate::marker(
        "private-extension-slot",
        format!(
            "status=frame frame={} observedOpenXrFps={:.1} {}",
            frame_count,
            observed_openxr_fps,
            private_extension_stats.marker_fields()
        ),
    );
    crate::marker(
        "gpu-mesh-checklist",
        format!(
            "status=frame frame={} gpuSkinnedVisual={} skinnedPositionBufferCoordinateSpace=openxr-reference-space liveHandMeshWorldSpaceProjection={} compactJointPoseUploadPerFrame=true jointMatrixUploadPerFrame=false liveMetaCompactPathReady={} liveMetaCompactFrameReady={} gpuNormalDepthComponentShading=true sdfTriangleBoundsReady={} sdfTileBinsReady={} sdfNarrowBandReady={} sdfUpdateCadenceFrames={} sdfFieldCacheHits={} sourceMeshBuffersResident={} derivedBuffersResident={}",
            frame_count,
            hand_mesh_visual_stats.any_ready(),
            hand_mesh_visual_stats.primary.live_compact_input_frame
                || hand_mesh_visual_stats.secondary.live_compact_input_frame,
            live_hand_stats.tracker_ready,
            live_hand_stats.frame_ready,
            gpu_sdf_stats.triangle_bounds_ready,
            gpu_sdf_stats.tile_bins_ready,
            gpu_sdf_stats.narrow_band_ready,
            gpu_sdf_stats.sdf_update_period_frames,
            gpu_sdf_stats.field_cache_hits,
            gpu_sdf_stats.source_mesh_buffers_resident,
            gpu_sdf_stats.derived_buffers_resident,
        ),
    );
}

fn hand_mesh_visual_eye_projection(view: &xr::View) -> HandMeshVisualEyeProjection {
    HandMeshVisualEyeProjection {
        position: [
            view.pose.position.x,
            view.pose.position.y,
            view.pose.position.z,
            0.0,
        ],
        orientation_xyzw: [
            view.pose.orientation.x,
            view.pose.orientation.y,
            view.pose.orientation.z,
            view.pose.orientation.w,
        ],
        fov_tangents: [
            view.fov.angle_left.tan(),
            view.fov.angle_right.tan(),
            view.fov.angle_down.tan(),
            view.fov.angle_up.tan(),
        ],
    }
}

fn handedness_label(value: &str) -> &'static str {
    if value.eq_ignore_ascii_case("right") {
        "right"
    } else {
        "left"
    }
}

struct ProjectionSwapchain {
    handle: xr::Swapchain<xr::Vulkan>,
    buffers: Vec<ProjectionSwapchainBuffer>,
    extent: vk::Extent2D,
    render_pass: vk::RenderPass,
}

impl ProjectionSwapchain {
    unsafe fn destroy(self, device: &ash::Device) {
        for buffer in self.buffers {
            for eye in buffer.eyes {
                device.destroy_framebuffer(eye.framebuffer, None);
                device.destroy_image_view(eye.view, None);
            }
        }
    }
}

struct NativePassthroughRuntime {
    _passthrough: xr::Passthrough,
    layer: xr::PassthroughLayerFB,
}

impl NativePassthroughRuntime {
    fn create(
        session: &xr::Session<xr::Vulkan>,
        render_mode: NativeRendererRenderMode,
        fb_passthrough_enabled: bool,
        alpha_blend_supported: bool,
    ) -> Option<Self> {
        if !render_mode.uses_native_passthrough() {
            crate::marker(
                "native-passthrough",
                format!(
                    "status=disabled renderMode={} nativePassthroughRequested=false solidBlackBackground={} nativePassthroughLayerActive=false",
                    render_mode.marker_value(),
                    render_mode.uses_solid_black_background(),
                ),
            );
            return None;
        }
        if !fb_passthrough_enabled {
            crate::marker(
                "native-passthrough",
                "status=unavailable reason=XR_FB_passthrough-not-enabled nativePassthroughRequested=true fbPassthroughEnabled=false nativePassthroughLayerActive=false",
            );
            return None;
        }

        let passthrough = match session
            .create_passthrough(xr::PassthroughFlagsFB::IS_RUNNING_AT_CREATION)
        {
            Ok(passthrough) => passthrough,
            Err(error) => {
                crate::marker(
                        "native-passthrough",
                        format!(
                            "status=error stage=create-passthrough reason={} nativePassthroughLayerActive=false",
                            crate::sanitize(&error.to_string())
                        ),
                    );
                return None;
            }
        };
        let layer = match session.create_passthrough_layer(
            &passthrough,
            xr::PassthroughFlagsFB::EMPTY,
            xr::PassthroughLayerPurposeFB::RECONSTRUCTION,
        ) {
            Ok(layer) => layer,
            Err(error) => {
                crate::marker(
                    "native-passthrough",
                    format!(
                        "status=error stage=create-layer reason={} nativePassthroughLayerActive=false",
                        crate::sanitize(&error.to_string())
                    ),
                );
                return None;
            }
        };
        if let Err(error) = layer.resume() {
            crate::marker(
                "native-passthrough",
                format!(
                    "status=error stage=resume-layer reason={} nativePassthroughLayerActive=false",
                    crate::sanitize(&error.to_string())
                ),
            );
            return None;
        }
        crate::marker(
            "native-passthrough",
            format!(
                "status=active passthroughExtension=XR_FB_passthrough nativePassthroughLayerActive=true passthroughCompositionLayer=CompositionLayerPassthroughFB passthroughPurpose=RECONSTRUCTION environmentBlendModeAlphaSupported={} environmentBlendMode=OPAQUE projectionLayerAlphaBlend={} passthroughLayerAlphaBlend=true",
                alpha_blend_supported,
                render_mode.projection_layer_alpha_blend(),
            ),
        );
        Some(Self {
            _passthrough: passthrough,
            layer,
        })
    }

    fn composition_layer_raw(&self, _space: &xr::Space) -> xr::sys::CompositionLayerPassthroughFB {
        xr::sys::CompositionLayerPassthroughFB {
            ty: xr::sys::StructureType::COMPOSITION_LAYER_PASSTHROUGH_FB,
            next: ptr::null(),
            flags: xr::CompositionLayerFlags::BLEND_TEXTURE_SOURCE_ALPHA,
            space: xr::sys::Space::NULL,
            layer_handle: self.layer.as_raw(),
        }
    }
}

fn passthrough_layer_base(
    layer: &xr::sys::CompositionLayerPassthroughFB,
) -> &xr::CompositionLayerBase<'_, xr::Vulkan> {
    unsafe { std::mem::transmute(layer) }
}

fn environment_blend_modes_marker(modes: &[xr::EnvironmentBlendMode]) -> String {
    modes
        .iter()
        .map(|mode| format!("{mode:?}"))
        .collect::<Vec<_>>()
        .join(",")
}

struct ProjectionSwapchainBuffer {
    eyes: Vec<ProjectionEyeTarget>,
}

struct ProjectionEyeTarget {
    view: vk::ImageView,
    framebuffer: vk::Framebuffer,
}

#[derive(Clone, Copy)]
struct ReplayVisualStats {
    frame_index: u32,
    timestamp_ns: u64,
    visual_point_count: u64,
    local_evidence_rect: EvidenceUvRect,
}

impl Default for ReplayVisualStats {
    fn default() -> Self {
        Self {
            frame_index: 0,
            timestamp_ns: 0,
            visual_point_count: 0,
            local_evidence_rect: EvidenceUvRect::default(),
        }
    }
}

impl ReplayVisualStats {
    fn hand_mesh_screen_rect_marker_fields(
        self,
        projection_metadata: &CameraProjectionMetadata,
    ) -> String {
        self.screen_rect_marker_fields(
            projection_metadata,
            "leftHandMeshVisualScreenUvRect",
            "rightHandMeshVisualScreenUvRect",
        )
    }

    fn sdf_screen_rect_marker_fields(
        self,
        projection_metadata: &CameraProjectionMetadata,
    ) -> String {
        self.screen_rect_marker_fields(
            projection_metadata,
            "leftSdfVisualScreenUvRect",
            "rightSdfVisualScreenUvRect",
        )
    }

    fn screen_rect_marker_fields(
        self,
        projection_metadata: &CameraProjectionMetadata,
        left_field: &str,
        right_field: &str,
    ) -> String {
        let left = self
            .local_evidence_rect
            .to_screen_rect(projection_metadata.rect_for_eye(0));
        let right = self
            .local_evidence_rect
            .to_screen_rect(projection_metadata.rect_for_eye(1));
        format!(
            "{left_field}={} {right_field}={}",
            left.marker_value(),
            right.marker_value()
        )
    }
}

#[derive(Clone, Copy)]
struct EvidenceUvRect {
    x: f32,
    y: f32,
    width: f32,
    height: f32,
}

impl EvidenceUvRect {
    fn from_points(
        points: &[[f32; 2]],
        diagnostic_settings: HandMeshVisualDiagnosticSettings,
    ) -> Self {
        if points.is_empty() {
            return Self::default();
        }

        let mut min_x = 1.0_f32;
        let mut min_y = 1.0_f32;
        let mut max_x = 0.0_f32;
        let mut max_y = 0.0_f32;
        let diagnostic_scale = if diagnostic_settings.enabled {
            1.35
        } else {
            1.0
        };
        let diagnostic_offset = if diagnostic_settings.enabled {
            diagnostic_settings.offset_uv
        } else {
            [0.0, 0.0]
        };
        for point in points {
            let x =
                (0.5 + (point[0] - 0.5) * diagnostic_scale + diagnostic_offset[0]).clamp(0.0, 1.0);
            let y =
                (0.5 + (point[1] - 0.5) * diagnostic_scale + diagnostic_offset[1]).clamp(0.0, 1.0);
            min_x = min_x.min(x);
            min_y = min_y.min(y);
            max_x = max_x.max(x);
            max_y = max_y.max(y);
        }

        Self::from_bounds(min_x, min_y, max_x, max_y).padded(0.035)
    }

    fn from_bounds(min_x: f32, min_y: f32, max_x: f32, max_y: f32) -> Self {
        let x = min_x.min(max_x).clamp(0.0, 1.0);
        let y = min_y.min(max_y).clamp(0.0, 1.0);
        let width = (max_x.max(min_x) - x).max(0.001).min(1.0 - x);
        let height = (max_y.max(min_y) - y).max(0.001).min(1.0 - y);
        Self {
            x,
            y,
            width,
            height,
        }
    }

    fn from_bounds_for_points(points: &[[f32; 2]]) -> Self {
        if points.is_empty() {
            return Self::default();
        }

        let mut min_x = 1.0_f32;
        let mut min_y = 1.0_f32;
        let mut max_x = 0.0_f32;
        let mut max_y = 0.0_f32;
        for [x, y] in points.iter().copied() {
            min_x = min_x.min(x);
            min_y = min_y.min(y);
            max_x = max_x.max(x);
            max_y = max_y.max(y);
        }

        Self::from_bounds(min_x, min_y, max_x, max_y)
    }

    fn union_all(rects: &[Self]) -> Option<Self> {
        let mut rects = rects.iter().copied();
        let first = rects.next()?;
        let mut min_x = first.x;
        let mut min_y = first.y;
        let mut max_x = first.x + first.width;
        let mut max_y = first.y + first.height;

        for rect in rects {
            min_x = min_x.min(rect.x);
            min_y = min_y.min(rect.y);
            max_x = max_x.max(rect.x + rect.width);
            max_y = max_y.max(rect.y + rect.height);
        }

        Some(Self::from_bounds(min_x, min_y, max_x, max_y))
    }

    fn padded(self, padding: f32) -> Self {
        let x = (self.x - padding).max(0.0);
        let y = (self.y - padding).max(0.0);
        let max_x = (self.x + self.width + padding).min(1.0);
        let max_y = (self.y + self.height + padding).min(1.0);
        Self::from_bounds(x, y, max_x, max_y)
    }

    fn scaled_about_center(self, scale: f32, offset: [f32; 2]) -> Self {
        let points = [
            [self.x, self.y],
            [self.x + self.width, self.y],
            [self.x, self.y + self.height],
            [self.x + self.width, self.y + self.height],
        ];
        let scaled = points.map(|[x, y]| {
            [
                (0.5 + (x - 0.5) * scale + offset[0]).clamp(0.0, 1.0),
                (0.5 + (y - 0.5) * scale + offset[1]).clamp(0.0, 1.0),
            ]
        });
        Self::from_bounds_for_points(&scaled)
    }

    fn to_screen_rect(self, target_rect: TargetRect) -> Self {
        Self::from_bounds(
            target_rect.x + self.x * target_rect.width,
            target_rect.y + self.y * target_rect.height,
            target_rect.x + (self.x + self.width) * target_rect.width,
            target_rect.y + (self.y + self.height) * target_rect.height,
        )
    }

    fn marker_value(self) -> String {
        format!(
            "{:.6},{:.6},{:.6},{:.6}",
            self.x, self.y, self.width, self.height
        )
    }
}

impl Default for EvidenceUvRect {
    fn default() -> Self {
        Self {
            x: 0.25,
            y: 0.25,
            width: 0.50,
            height: 0.50,
        }
    }
}

fn write_xr_string<const N: usize>(destination: &mut [std::os::raw::c_char; N], value: &str) {
    for (slot, byte) in destination.iter_mut().zip(value.bytes()) {
        *slot = byte as _;
    }
}

fn ensure_xr_success(result: xr::sys::Result, operation: &str) -> Result<(), String> {
    if result.into_raw() < xr::sys::Result::SUCCESS.into_raw() {
        return Err(format!("{operation} failed: {result:?}"));
    }

    Ok(())
}

fn optional_i32_marker(value: Option<i32>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "none".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base_hand_meshes_require_explicit_visual_mode() {
        let off = HandMeshVisualDiagnosticSettings::default();
        let diagnostic = HandMeshVisualDiagnosticSettings::new(true, [0.0, 0.0], 1.0);

        assert!(!should_draw_base_hand_meshes(false, false, off));
        assert!(should_draw_base_hand_meshes(true, false, off));
        assert!(should_draw_base_hand_meshes(false, true, off));
        assert!(should_draw_base_hand_meshes(false, false, diagnostic));
    }
}
