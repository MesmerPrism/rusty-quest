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
    display_composite_feedback::{
        DisplayCompositeFeedbackRenderer, DisplayCompositeFrameStats,
        PreparedDisplayCompositeFeedback,
    },
    display_composite_projection_metadata::DisplayCompositeProjectionMetadata,
    environment_depth_alignment_state::{
        EnvironmentDepthAlignmentSettings, EnvironmentDepthAlignmentState,
    },
    gpu_environment_depth_particle_stats::GpuEnvironmentDepthParticleFrameStats,
    gpu_environment_depth_particles::GpuEnvironmentDepthParticleRenderer,
    gpu_hand_anchor_particles::{
        GpuHandAnchorParticleFrameSetStats, GpuHandAnchorParticleRenderer,
    },
    gpu_hand_mesh_visual::{
        GpuHandMeshVisualFrameSetStats, GpuHandMeshVisualFrameStats, GpuHandMeshVisualRenderer,
        HandMeshVisualEyeProjection,
    },
    gpu_mesh_replay::{GpuMeshReplayResources, GpuMeshReplayStats},
    gpu_private_particles::{GpuPrivateParticleFrameStats, GpuPrivateParticleRenderer},
    gpu_sdf_field::{GpuSdfFieldFrameStats, GpuSdfFieldRenderer},
    gpu_stimulus_volume::{GpuStimulusVolumeFrameStats, GpuStimulusVolumeRenderer},
    guide_blur_graph::{GuideBlurGraphFrameStats, GuideBlurGraphRenderer},
    live_hand_compact::{LiveHandCompactFrameSet, LiveHandCompactInput, LiveHandCompactStats},
    manifold_breath_bridge::ManifoldBreathBridge,
    native_camera::NativeCameraRuntime,
    native_renderer_display_composite_options::{
        NativeDisplayCompositeFeedbackProjection, NativeDisplayCompositeMode,
        NativeDisplayCompositeSettings,
    },
    native_renderer_options::{
        CompactHandInputSourceMode, HandMeshVisualDiagnosticSettings,
        HandMeshVisualMaterialSettings, NativeCameraOutputMode, NativeCameraQualityProfile,
        NativeCameraSyncMode, NativeEnvironmentDepthSettings, NativeFoveationLevel,
        NativeFoveationSettings, NativeGuideGraphResolution, NativeHandAnchorParticleOrderingMode,
        NativeHandAnchorParticleSettings, NativePassthroughStyleSettings,
        NativePrivateLayerSettings, NativeProjectionBorderStretchSettings,
        NativeProjectionSwapchainSettings, NativeRendererRenderMode, NativeRendererRuntimeOptions,
        NativeStimulusVolumeSettings, NativeSwapchainColorFormatMode,
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
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, PROP_HAND_MESH_VISUAL_MATERIAL_ALPHA,
        PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_B, PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_G,
        PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_R, PROP_HAND_MESH_VISUAL_MATERIAL_PROFILE,
        PROP_HAND_MESH_VISUAL_MATERIAL_RIM_STRENGTH, PROP_PROCESSING_LAYER, PROP_RENDER_MODE,
        PROP_REPLAY_VISUAL_PROOF_ENABLED, PROP_SWAPCHAIN_COLOR_FORMAT_MODE,
    },
    native_renderer_passthrough_style_options::NativePassthroughStyleAudioReactiveState,
    native_renderer_properties::PROP_PRIVATE_PARTICLES_WORLD_ANCHOR_SCALE_M,
    native_renderer_property_values::f32_clamped_value,
    native_renderer_timing::{
        elapsed_ms, FrameCpuTimings, GpuStageTimings, GpuTimestampStage, GpuTimestampTracker,
    },
    openxr_environment_depth::{
        OpenXrEnvironmentDepthFrame, OpenXrEnvironmentDepthProperties,
        OpenXrEnvironmentDepthRuntime,
    },
    openxr_stimulus_actions::StimulusVolumeActions,
    private_extension_slot::{PrivateExtensionSlotFrameStats, PrivateExtensionSlotRuntime},
    projection_target_state::{ProjectionTargetSettings, ProjectionTargetState},
    recorded_hand_replay::{
        RecordedHandReplaySet, RecordedHandReplaySummary, RecordedHandSkinningFrame,
    },
    video_projection::{
        PreparedVideoProjection, VideoProjectionFrameStats, VideoProjectionRenderer,
    },
    video_projection_metadata::VideoProjectionMetadata,
};

const VIEW_TYPE: xr::ViewConfigurationType = xr::ViewConfigurationType::PRIMARY_STEREO;
const VIEW_COUNT: u32 = 2;
const VIEW_COUNT_USIZE: usize = 2;
mod replay_visual_stats;
mod scorecard;

use replay_visual_stats::{EvidenceUvRect, ReplayVisualStats};

const PIPELINE_DEPTH: u32 = 2;
const PRIVATE_PARTICLE_WORLD_ANCHOR_DISTANCE_M: f32 = 1.70;
const PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_M: f32 = 0.46;
const PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MIN_M: f32 = 0.05;
const PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MAX_M: f32 = 4.0;
const PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_POLL_INTERVAL_FRAMES: u64 = 30;

#[derive(Clone, Debug, Default)]
pub(crate) struct XrVulkanReadiness {
    pub(crate) android_loader_ready: bool,
    pub(crate) openxr_instance_ready: bool,
    pub(crate) vulkan_instance_ready: bool,
    pub(crate) external_hwb_extension_ready: bool,
    pub(crate) sampler_ycbcr_extension_ready: bool,
    pub(crate) sampler_ycbcr_feature_ready: bool,
    pub(crate) fragment_density_map_extension_ready: bool,
    pub(crate) fragment_density_map2_extension_ready: bool,
    pub(crate) fragment_density_map_feature_ready: bool,
    pub(crate) fragment_density_map_format_ready: bool,
    pub(crate) vulkan_external_import_prereqs_ready: bool,
    pub(crate) live_hand_tracking_extension_available: bool,
    pub(crate) live_hand_tracking_extension_enabled: bool,
    pub(crate) live_hand_tracking_system_supported: bool,
}

impl XrVulkanReadiness {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "androidOpenxrLoaderReady={} openxrInstanceReady={} vulkanInstanceReady={} externalHwbExtensionReady={} samplerYcbcrExtensionReady={} samplerYcbcrFeatureReady={} fragmentDensityMapExtensionReady={} fragmentDensityMap2ExtensionReady={} fragmentDensityMapFeatureReady={} fragmentDensityMapFormatReady={} vulkanExternalImportPrereqsReady={} liveMetaHandTrackingExtensionAvailable={} liveMetaHandTrackingExtensionEnabled={} liveMetaHandTrackingSystemSupported={} openxrSubmitReady=false vulkanExternalImportReady=false",
            self.android_loader_ready,
            self.openxr_instance_ready,
            self.vulkan_instance_ready,
            self.external_hwb_extension_ready,
            self.sampler_ycbcr_extension_ready,
            self.sampler_ycbcr_feature_ready,
            self.fragment_density_map_extension_ready,
            self.fragment_density_map2_extension_ready,
            self.fragment_density_map_feature_ready,
            self.fragment_density_map_format_ready,
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
    fragment_density_map_extension_ready: bool,
    fragment_density_map2_extension_ready: bool,
    fragment_density_map_feature_ready: bool,
    fragment_density_map_format_ready: bool,
}

#[derive(Clone, Copy, Debug, Default)]
struct ProjectionFoveationVulkanSupport {
    extension_ready: bool,
    extension2_ready: bool,
    feature_ready: bool,
    deferred_feature_ready: bool,
    format_ready: bool,
    subsampled_swapchain_ready: bool,
    enabled: bool,
}

impl ProjectionFoveationVulkanSupport {
    fn fdm_ready(self) -> bool {
        self.extension_ready
            && self.extension2_ready
            && self.feature_ready
            && self.format_ready
            && self.subsampled_swapchain_ready
            && self.enabled
    }

    fn marker_fields(self) -> String {
        format!(
            "vulkanFragmentDensityMapExtensionReady={} vulkanFragmentDensityMap2ExtensionReady={} vulkanFragmentDensityMapFeatureReady={} vulkanFragmentDensityMapDeferredFeatureReady={} vulkanFragmentDensityMapFormatReady={} vulkanFragmentDensityMapSubsampledSwapchainReady={} vulkanFragmentDensityMapEnabled={}",
            self.extension_ready,
            self.extension2_ready,
            self.feature_ready,
            self.deferred_feature_ready,
            self.format_ready,
            self.subsampled_swapchain_ready,
            self.enabled
        )
    }
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
    let display_composite_projection_metadata = DisplayCompositeProjectionMetadata::from_settings(
        runtime_options.display_composite_settings,
    );
    let video_projection_metadata = VideoProjectionMetadata::from_settings(
        &runtime_options.video_projection_settings,
        &projection_metadata,
    );
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
    crate::marker(
        "display-composite-projection-metadata",
        format!(
            "status=loaded {}",
            display_composite_projection_metadata.marker_fields()
        ),
    );
    crate::marker(
        "video-projection-metadata",
        format!(
            "status=loaded {}",
            video_projection_metadata.marker_fields()
        ),
    );

    let started = Instant::now();
    let result = unsafe {
        run_projection_loop_inner(
            app,
            camera_runtime,
            runtime_options,
            replay_set,
            projection_metadata,
            display_composite_projection_metadata,
            video_projection_metadata,
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
    display_composite_projection_metadata: DisplayCompositeProjectionMetadata,
    video_projection_metadata: VideoProjectionMetadata,
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

    let native_passthrough_requested = runtime_options.render_mode.uses_native_passthrough()
        || runtime_options
            .environment_depth_settings
            .native_passthrough_required();
    let mut enabled_extensions = xr::ExtensionSet::default();
    enabled_extensions.khr_android_create_instance = true;
    enabled_extensions.khr_vulkan_enable2 = true;
    enabled_extensions.ext_hand_tracking = available_extensions.ext_hand_tracking;
    enabled_extensions.fb_passthrough =
        native_passthrough_requested && available_extensions.fb_passthrough;
    enabled_extensions.meta_environment_depth = runtime_options
        .environment_depth_settings
        .runtime_provider_requested()
        && available_extensions.meta_environment_depth;
    let foveation_requested = runtime_options.foveation_settings.requested();
    let foveation_vulkan_fdm_requested = runtime_options.foveation_settings.vulkan_fdm_requested();
    enabled_extensions.fb_foveation = foveation_requested && available_extensions.fb_foveation;
    enabled_extensions.fb_foveation_configuration =
        foveation_requested && available_extensions.fb_foveation_configuration;
    enabled_extensions.fb_foveation_vulkan =
        foveation_vulkan_fdm_requested && available_extensions.fb_foveation_vulkan;
    enabled_extensions.fb_swapchain_update_state =
        foveation_requested && available_extensions.fb_swapchain_update_state;
    enabled_extensions.fb_swapchain_update_state_vulkan =
        foveation_vulkan_fdm_requested && available_extensions.fb_swapchain_update_state_vulkan;
    enabled_extensions.meta_vulkan_swapchain_create_info =
        foveation_vulkan_fdm_requested && available_extensions.meta_vulkan_swapchain_create_info;
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
        runtime_options.private_particle_breath_state_driver_settings,
        runtime_options.environment_depth_alignment_settings,
        runtime_options
            .render_mode
            .requests_private_particle_recenter_input(),
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
            "status=config renderMode={} nativePassthroughRequested={} solidBlackBackground={} fbPassthroughAvailable={} fbPassthroughEnabled={} alphaBlendSupported={} environmentBlendModes={} environmentBlendMode={:?} projectionLayerAlphaBlend={} cameraRuntimeMode={} cameraProjectionPath={} {}",
            runtime_options.render_mode.marker_value(),
            native_passthrough_requested,
            runtime_options.render_mode.uses_solid_black_background(),
            available_extensions.fb_passthrough,
            enabled_extensions.fb_passthrough,
            alpha_blend_supported,
            environment_blend_modes_marker(&environment_blend_modes),
            environment_blend_mode,
            runtime_options.render_mode.projection_layer_alpha_blend(),
            runtime_options.render_mode.camera_runtime_mode(),
            runtime_options.render_mode.disabled_camera_projection_path(),
            runtime_options.passthrough_style_settings.marker_fields(),
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
    let fragment_density_map_extension_ready = physical_device_supports_extension(
        &vk_instance,
        vk_physical_device,
        ash::ext::fragment_density_map::NAME,
    )?;
    let fragment_density_map2_extension_ready = physical_device_supports_extension(
        &vk_instance,
        vk_physical_device,
        ash::ext::fragment_density_map2::NAME,
    )?;
    let mut sampler_ycbcr_features = vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
    let mut fragment_density_map_features =
        vk::PhysicalDeviceFragmentDensityMapFeaturesEXT::default();
    let mut fragment_density_map2_features =
        vk::PhysicalDeviceFragmentDensityMap2FeaturesEXT::default();
    let mut feature_query = vk::PhysicalDeviceFeatures2::default()
        .push_next(&mut sampler_ycbcr_features)
        .push_next(&mut fragment_density_map_features)
        .push_next(&mut fragment_density_map2_features);
    vk_instance.get_physical_device_features2(vk_physical_device, &mut feature_query);
    let sampler_ycbcr_feature_ready = sampler_ycbcr_features.sampler_ycbcr_conversion == vk::TRUE;
    let fragment_density_map_feature_ready =
        fragment_density_map_features.fragment_density_map == vk::TRUE;
    let fragment_density_map_deferred_feature_ready =
        fragment_density_map2_features.fragment_density_map_deferred == vk::TRUE;
    let fragment_density_map_format_ready = if fragment_density_map_extension_ready {
        vk_instance
            .get_physical_device_format_properties(vk_physical_device, vk::Format::R8G8_UNORM)
            .optimal_tiling_features
            .contains(vk::FormatFeatureFlags::FRAGMENT_DENSITY_MAP_EXT)
    } else {
        false
    };
    let vulkan_external_import_prereqs_ready = external_hwb_extension_ready
        && sampler_ycbcr_extension_ready
        && sampler_ycbcr_feature_ready;
    let fragment_density_map_enabled = foveation_vulkan_fdm_requested
        && enabled_extensions.fb_foveation_vulkan
        && enabled_extensions.meta_vulkan_swapchain_create_info
        && fragment_density_map_extension_ready
        && fragment_density_map2_extension_ready
        && fragment_density_map_feature_ready
        && fragment_density_map_format_ready;
    let projection_foveation_vulkan_support = ProjectionFoveationVulkanSupport {
        extension_ready: fragment_density_map_extension_ready,
        extension2_ready: fragment_density_map2_extension_ready,
        feature_ready: fragment_density_map_feature_ready,
        deferred_feature_ready: fragment_density_map_deferred_feature_ready,
        format_ready: fragment_density_map_format_ready,
        subsampled_swapchain_ready: enabled_extensions.meta_vulkan_swapchain_create_info,
        enabled: fragment_density_map_enabled,
    };

    let mut device_extension_ptrs = Vec::new();
    if external_hwb_extension_ready {
        device_extension_ptrs
            .push(ash::android::external_memory_android_hardware_buffer::NAME.as_ptr());
    }
    if sampler_ycbcr_extension_ready {
        device_extension_ptrs.push(ash::khr::sampler_ycbcr_conversion::NAME.as_ptr());
    }
    if fragment_density_map_enabled {
        device_extension_ptrs.push(ash::ext::fragment_density_map::NAME.as_ptr());
        device_extension_ptrs.push(ash::ext::fragment_density_map2::NAME.as_ptr());
    }
    let queue_priorities = [1.0_f32];
    let queue_infos = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family_index)
        .queue_priorities(&queue_priorities)];
    let mut sampler_ycbcr_enable = vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default()
        .sampler_ycbcr_conversion(sampler_ycbcr_feature_ready);
    let mut fragment_density_map_enable =
        vk::PhysicalDeviceFragmentDensityMapFeaturesEXT::default()
            .fragment_density_map(fragment_density_map_enabled);
    let mut fragment_density_map2_enable =
        vk::PhysicalDeviceFragmentDensityMap2FeaturesEXT::default().fragment_density_map_deferred(
            fragment_density_map_enabled && fragment_density_map_deferred_feature_ready,
        );
    let mut device_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_infos)
        .enabled_extension_names(&device_extension_ptrs)
        .push_next(&mut sampler_ycbcr_enable);
    if fragment_density_map_enabled {
        device_info = device_info.push_next(&mut fragment_density_map_enable);
        if fragment_density_map_deferred_feature_ready {
            device_info = device_info.push_next(&mut fragment_density_map2_enable);
        }
    }
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
    let mut native_passthrough = NativePassthroughRuntime::create(
        &session,
        runtime_options.render_mode,
        native_passthrough_requested,
        enabled_extensions.fb_passthrough,
        alpha_blend_supported,
        runtime_options.passthrough_style_settings,
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
    let render_pass = create_projection_render_pass(
        &vk_device,
        color_format,
        projection_foveation_vulkan_support.fdm_ready(),
    )?;
    let mut camera_projection_renderer = CameraProjectionRenderer::new(
        &vk_instance,
        &vk_device,
        memory_properties,
        render_pass,
        vulkan_external_import_prereqs_ready,
        runtime_options.camera_ycbcr_mode,
    );
    let mut display_composite_feedback_renderer = DisplayCompositeFeedbackRenderer::new(
        &vk_instance,
        &vk_device,
        memory_properties,
        render_pass,
        color_format,
        vulkan_external_import_prereqs_ready,
    );
    let mut video_projection_renderer = VideoProjectionRenderer::new(
        &vk_instance,
        &vk_device,
        memory_properties,
        render_pass,
        vulkan_external_import_prereqs_ready,
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
    let projection_swapchain_settings = runtime_options.projection_swapchain_settings;
    let replay_visual_proof_enabled = runtime_options.replay_visual_proof_enabled;
    let compact_hand_input_source_mode = runtime_options.compact_hand_input_source_mode;
    let sdf_visual_enabled = runtime_options.sdf_visual_enabled;
    let sdf_update_period_frames = runtime_options.sdf_update_period_frames;
    let hand_mesh_visual_diagnostic_settings = runtime_options.hand_mesh_visual_diagnostic_settings;
    let hand_mesh_visual_material_settings = runtime_options.hand_mesh_visual_material_settings;
    let hand_mesh_graft_copies_enabled = runtime_options.hand_mesh_graft_copies_enabled;
    let hand_mesh_graft_copy_scale = runtime_options.hand_mesh_graft_copy_scale;
    let hand_mesh_real_hands_visible = runtime_options.hand_mesh_real_hands_visible;
    let hand_anchor_particle_settings = runtime_options.hand_anchor_particle_settings;
    let environment_depth_settings = runtime_options.environment_depth_settings;
    let environment_depth_alignment_settings = runtime_options.environment_depth_alignment_settings;
    let display_composite_settings = runtime_options.display_composite_settings;
    let video_projection_settings = runtime_options.video_projection_settings.clone();
    let stimulus_volume_settings = runtime_options.stimulus_volume_settings;
    let projection_target_settings = runtime_options.projection_target_settings.clone();
    let projection_border_stretch_settings = runtime_options.projection_border_stretch_settings;
    let private_layer_settings = runtime_options.private_layer_settings;
    crate::marker(
        "recorded-replay-visual-proof",
        format!(
            "status=config renderModeProperty={} renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} solidBlackBackground={} openxrDefaultHandVisualRequested={} property={} enabled={} handMeshInputSourceProperty={} compactHandInputSourceMode={} selectsLiveFrame={} allowsRecordedFallback={} sdfVisualEnabled={} handMeshVisualDiagnosticEnabled={} {} handMeshGraftCopiesEnabled={} handMeshGraftScaleProperty={} handMeshGraftScaleMultiplier={:.2} realHandsProperty={} handMeshRealHandsVisible={} recordedReplayVisualAcceptance=pending-headset-screenshot liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof liveSdfVisualAcceptance=pending-repeat-headset-visual-proof",
            PROP_RENDER_MODE,
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            native_passthrough_requested,
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
            hand_mesh_visual_material_settings.marker_fields(),
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
            projection_border_stretch_settings.guide_projection_coverage(),
            projection_border_stretch_settings.marker_fields()
        ),
    );
    crate::marker(
        "projection-target",
        format!(
            "status=config renderMode={} customStereoProjectionEnabled={} {} startupDefaultsAuthority=runtime-profile finalStateAuthority=native-renderer pmbSourceAuthority={}",
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            projection_target_settings.marker_fields(),
            projection_target_settings
                .breath_bridge_mode
                .source_authority_marker(),
        ),
    );
    crate::marker(
        "environment-depth-alignment",
        format!(
            "status=config {} finalStateAuthority=native-renderer publicControlSurface=left-controller-thumbstick+same-apk-panel appliesTo=environment-depth-sampler-only",
            environment_depth_alignment_settings.marker_fields(),
        ),
    );
    crate::marker(
        "hand-mesh-visual-diagnostic",
        format!(
            "status=config renderMode={} solidBlackBackground={} openxrDefaultHandVisualRequested={} handMeshVisualDiagnosticPath=property-controlled-target-local-offset-tint enabledProperty={} offsetProperty={} alphaProperty={} materialProfileProperty={} materialAlphaProperty={} materialBaseColorRProperty={} materialBaseColorGProperty={} materialBaseColorBProperty={} materialRimStrengthProperty={} graftCopiesProperty={} graftScaleProperty={} realHandsProperty={} handMeshGraftCopiesEnabled={} handMeshGraftScaleMultiplier={:.2} handMeshRealHandsVisible={} nativePassthroughRealHandMeshVisible={} solidBlackRealHandMeshVisible={} {} {} liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof liveSdfVisualAcceptance=pending-repeat-headset-visual-proof",
            render_mode.marker_value(),
            render_mode.uses_solid_black_background(),
            render_mode.requests_openxr_default_hand_visual(),
            PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED,
            PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV,
            PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA,
            PROP_HAND_MESH_VISUAL_MATERIAL_PROFILE,
            PROP_HAND_MESH_VISUAL_MATERIAL_ALPHA,
            PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_R,
            PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_G,
            PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_B,
            PROP_HAND_MESH_VISUAL_MATERIAL_RIM_STRENGTH,
            PROP_HAND_MESH_GRAFT_COPIES_ENABLED,
            PROP_HAND_MESH_GRAFT_COPY_SCALE,
            PROP_HAND_MESH_REAL_HANDS_VISIBLE,
            hand_mesh_graft_copies_enabled,
            hand_mesh_graft_copy_scale,
            hand_mesh_real_hands_visible,
            native_passthrough_requested && hand_mesh_real_hands_visible,
            render_mode.uses_solid_black_background() && hand_mesh_real_hands_visible,
            hand_mesh_visual_diagnostic_settings.marker_fields(),
            hand_mesh_visual_material_settings.marker_fields(),
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
            native_passthrough_requested,
            environment_depth_settings.synthetic_gpu_proof_requested(),
            environment_depth_settings.runtime_provider_requested(),
            environment_depth_settings.marker_fields(),
        ),
    );
    let mut openxr_environment_depth_runtime: Option<OpenXrEnvironmentDepthRuntime> = None;
    if environment_depth_settings.runtime_provider_requested() {
        crate::marker(
            "environment-depth",
            format!(
                "status=provider-deferred reason=session-not-begun environmentDepthProviderAvailable={} environmentDepthRealProviderBound=false environmentDepthSupported={} environmentDepthAcquireStatus=not-attempted-session-not-running",
                environment_depth_properties.extension_available,
                environment_depth_properties.supports_environment_depth,
            ),
        );
    }
    let environment_depth_image_handles = Vec::new();
    let environment_depth_width = 0;
    let environment_depth_height = 0;
    let mut private_extension_slot_runtime = PrivateExtensionSlotRuntime::new(
        memory_properties,
        color_format,
        render_pass,
        &environment_depth_image_handles,
        environment_depth_width,
        environment_depth_height,
    );
    crate::marker(
        "private-extension-slot",
        format!(
            "status=config {}",
            PrivateExtensionSlotRuntime::config_marker_fields(private_layer_settings)
        ),
    );
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
        crate::marker(
            "environment-depth-particles",
            "status=provider-deferred reason=session-not-begun environmentDepthParticleReady=false environmentDepthRealProviderBound=false",
        );
        None
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
                queue,
                cmd_pool,
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
                queue,
                cmd_pool,
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
    let mut gpu_private_particle_renderer = match GpuPrivateParticleRenderer::new(
        &vk_device,
        &memory_properties,
        render_pass,
        queue,
        cmd_pool,
        runtime_options.private_particle_breath_state_driver_settings,
        runtime_options.manifold_scalar_driver_settings.clone(),
    ) {
        Ok(renderer) => renderer,
        Err(error) => {
            crate::marker(
                "private-particle-slot",
                format!(
                    "status=unavailable reason={} privateParticleReady=false",
                    crate::sanitize(&error)
                ),
            );
            None
        }
    };

    crate::marker(
        "openxr-projection",
        format!(
            "status=created renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} nativePassthroughLayerActive={} environmentBlendMode={:?} runtimeName={} runtimeVersion={} deviceName={} queueFamily={} colorFormat={:?} openxrSubmitReady=pending vulkanExternalImportPrereqsReady={} vulkanExternalImportReady=false recordedHandReplayVisible=pending gpuMeshPath=native-vulkan-storage-buffer",
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            native_passthrough_requested,
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
        &memory_properties,
        &cmds,
        &fences,
        &mut camera_projection_renderer,
        &mut display_composite_feedback_renderer,
        &mut video_projection_renderer,
        &mut guide_blur_graph_renderer,
        camera_runtime,
        render_mode,
        environment_blend_mode,
        native_passthrough_requested,
        native_passthrough.as_mut(),
        stimulus_actions.as_mut(),
        gpu_stimulus_volume_renderer.as_mut(),
        replay,
        secondary_replay,
        &gpu_mesh_stats,
        gpu_hand_mesh_visual_renderer.as_mut(),
        secondary_gpu_hand_mesh_visual_renderer.as_mut(),
        gpu_hand_anchor_particle_renderer.as_mut(),
        secondary_gpu_hand_anchor_particle_renderer.as_mut(),
        &mut openxr_environment_depth_runtime,
        &mut gpu_environment_depth_particle_renderer,
        gpu_private_particle_renderer.as_mut(),
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
        hand_mesh_visual_material_settings,
        hand_mesh_graft_copies_enabled,
        hand_mesh_graft_copy_scale,
        hand_mesh_real_hands_visible,
        hand_anchor_particle_settings,
        environment_depth_settings,
        environment_depth_alignment_settings,
        environment_depth_properties,
        display_composite_settings,
        video_projection_settings,
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
        &available_extensions,
        &enabled_extensions,
        runtime_options.foveation_settings,
        projection_swapchain_settings,
        projection_foveation_vulkan_support,
        projection_border_stretch_settings,
        private_layer_settings,
        &projection_metadata,
        &display_composite_projection_metadata,
        &video_projection_metadata,
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
    if let Some(renderer) = gpu_private_particle_renderer.as_mut() {
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
    video_projection_renderer.destroy(&vk_device);
    display_composite_feedback_renderer.destroy(&vk_device);
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
    readiness.fragment_density_map_extension_ready = vk_probe.fragment_density_map_extension_ready;
    readiness.fragment_density_map2_extension_ready =
        vk_probe.fragment_density_map2_extension_ready;
    readiness.fragment_density_map_feature_ready = vk_probe.fragment_density_map_feature_ready;
    readiness.fragment_density_map_format_ready = vk_probe.fragment_density_map_format_ready;
    readiness.vulkan_external_import_prereqs_ready = vk_probe.external_hwb_extension_ready
        && vk_probe.sampler_ycbcr_extension_ready
        && vk_probe.sampler_ycbcr_feature_ready;
    crate::marker(
        "vulkan-probe",
        format!(
            "status=ok deviceName={} apiVersion={} externalMemoryAndroidHardwareBuffer={} samplerYcbcrExtension={} samplerYcbcrFeature={} fragmentDensityMapExtensionReady={} fragmentDensityMap2ExtensionReady={} fragmentDensityMapFeatureReady={} fragmentDensityMapFormatReady={} descriptorShape=combined-immutable-sampler-ycbcr-conversion vulkanExternalImportPrereqsReady={} openxrSubmitReady=false vulkanExternalImportReady=false",
            crate::sanitize(&vk_probe.device_name),
            vk_probe.api_version,
            vk_probe.external_hwb_extension_ready,
            vk_probe.sampler_ycbcr_extension_ready,
            vk_probe.sampler_ycbcr_feature_ready,
            vk_probe.fragment_density_map_extension_ready,
            vk_probe.fragment_density_map2_extension_ready,
            vk_probe.fragment_density_map_feature_ready,
            vk_probe.fragment_density_map_format_ready,
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
        let fragment_density_map_extension_ready = physical_device_supports_extension(
            &vk_instance,
            vk_physical_device,
            ash::ext::fragment_density_map::NAME,
        )?;
        let fragment_density_map2_extension_ready = physical_device_supports_extension(
            &vk_instance,
            vk_physical_device,
            ash::ext::fragment_density_map2::NAME,
        )?;
        let mut sampler_ycbcr_features =
            vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
        let mut fragment_density_map_features =
            vk::PhysicalDeviceFragmentDensityMapFeaturesEXT::default();
        let mut feature_query = vk::PhysicalDeviceFeatures2::default()
            .push_next(&mut sampler_ycbcr_features)
            .push_next(&mut fragment_density_map_features);
        vk_instance.get_physical_device_features2(vk_physical_device, &mut feature_query);
        let sampler_ycbcr_feature_ready =
            sampler_ycbcr_features.sampler_ycbcr_conversion == vk::TRUE;
        let fragment_density_map_feature_ready =
            fragment_density_map_features.fragment_density_map == vk::TRUE;
        let fragment_density_map_format_ready = if fragment_density_map_extension_ready {
            vk_instance
                .get_physical_device_format_properties(vk_physical_device, vk::Format::R8G8_UNORM)
                .optimal_tiling_features
                .contains(vk::FormatFeatureFlags::FRAGMENT_DENSITY_MAP_EXT)
        } else {
            false
        };
        let device_name = CStr::from_ptr(properties.device_name.as_ptr())
            .to_string_lossy()
            .into_owned();
        Ok(VulkanProbe {
            device_name,
            api_version: properties.api_version,
            external_hwb_extension_ready,
            sampler_ycbcr_extension_ready,
            sampler_ycbcr_feature_ready,
            fragment_density_map_extension_ready,
            fragment_density_map2_extension_ready,
            fragment_density_map_feature_ready,
            fragment_density_map_format_ready,
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
unsafe fn ensure_openxr_environment_depth_runtime(
    xr_instance: &xr::Instance,
    vk_device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    render_pass: vk::RenderPass,
    session: &xr::Session<xr::Vulkan>,
    settings: NativeEnvironmentDepthSettings,
    properties: OpenXrEnvironmentDepthProperties,
    frame_count: u64,
    attempt: u32,
    runtime_slot: &mut Option<OpenXrEnvironmentDepthRuntime>,
    particle_renderer_slot: &mut Option<GpuEnvironmentDepthParticleRenderer>,
    private_extension_slot_runtime: &mut PrivateExtensionSlotRuntime,
) {
    if runtime_slot.is_some() {
        return;
    }
    crate::marker(
        "environment-depth",
        format!(
            "status=provider-create-attempt frame={} attempt={} environmentDepthProviderAvailable={} environmentDepthSupported={}",
            frame_count,
            attempt,
            properties.extension_available,
            properties.supports_environment_depth
        ),
    );
    match OpenXrEnvironmentDepthRuntime::create(
        xr_instance,
        session,
        settings,
        properties,
        frame_count,
    ) {
        Ok(runtime) => {
            let image_handles = runtime.depth_image_handles().to_vec();
            let width = runtime.width();
            let height = runtime.height();
            private_extension_slot_runtime.set_environment_depth_images(
                vk_device,
                &image_handles,
                width,
                height,
            );
            if settings.mode_draws_particles() && particle_renderer_slot.is_none() {
                match GpuEnvironmentDepthParticleRenderer::new_runtime_depth(
                    vk_device,
                    memory_properties,
                    render_pass,
                    settings,
                    &image_handles,
                    width,
                    height,
                ) {
                    Ok(renderer) => {
                        *particle_renderer_slot = Some(renderer);
                    }
                    Err(error) => {
                        crate::marker(
                            "environment-depth-particles",
                            format!(
                                "status=unavailable reason={} environmentDepthParticleReady=false environmentDepthRealProviderBound=true",
                                crate::sanitize(&error)
                            ),
                        );
                    }
                }
            }
            *runtime_slot = Some(runtime);
        }
        Err(error) => {
            crate::marker(
                "environment-depth",
                format!(
                    "status=unavailable reason={} environmentDepthProviderAvailable={} environmentDepthRealProviderBound=false environmentDepthSupported={} environmentDepthAcquireStatus=not-attempted-provider-not-bound attempt={}",
                    crate::sanitize(&error),
                    properties.extension_available,
                    properties.supports_environment_depth,
                    attempt
                ),
            );
        }
    }
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
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    cmds: &[vk::CommandBuffer],
    fences: &[vk::Fence],
    camera_projection_renderer: &mut CameraProjectionRenderer,
    display_composite_feedback_renderer: &mut DisplayCompositeFeedbackRenderer,
    video_projection_renderer: &mut VideoProjectionRenderer,
    guide_blur_graph_renderer: &mut GuideBlurGraphRenderer,
    camera_runtime: Option<&NativeCameraRuntime>,
    render_mode: NativeRendererRenderMode,
    environment_blend_mode: xr::EnvironmentBlendMode,
    native_passthrough_requested: bool,
    mut native_passthrough: Option<&mut NativePassthroughRuntime>,
    mut stimulus_actions: Option<&mut StimulusVolumeActions>,
    mut gpu_stimulus_volume_renderer: Option<&mut GpuStimulusVolumeRenderer>,
    replay: &RecordedHandReplaySummary,
    secondary_replay: &RecordedHandReplaySummary,
    gpu_mesh_stats: &GpuMeshReplayStats,
    mut gpu_hand_mesh_visual_renderer: Option<&mut GpuHandMeshVisualRenderer>,
    mut secondary_gpu_hand_mesh_visual_renderer: Option<&mut GpuHandMeshVisualRenderer>,
    mut gpu_hand_anchor_particle_renderer: Option<&mut GpuHandAnchorParticleRenderer>,
    mut secondary_gpu_hand_anchor_particle_renderer: Option<&mut GpuHandAnchorParticleRenderer>,
    openxr_environment_depth_runtime: &mut Option<OpenXrEnvironmentDepthRuntime>,
    gpu_environment_depth_particle_renderer: &mut Option<GpuEnvironmentDepthParticleRenderer>,
    mut gpu_private_particle_renderer: Option<&mut GpuPrivateParticleRenderer>,
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
    hand_mesh_visual_material_settings: HandMeshVisualMaterialSettings,
    hand_mesh_graft_copies_enabled: bool,
    hand_mesh_graft_copy_scale: f32,
    hand_mesh_real_hands_visible: bool,
    hand_anchor_particle_settings: NativeHandAnchorParticleSettings,
    environment_depth_settings: NativeEnvironmentDepthSettings,
    environment_depth_alignment_settings: EnvironmentDepthAlignmentSettings,
    environment_depth_properties: OpenXrEnvironmentDepthProperties,
    display_composite_settings: NativeDisplayCompositeSettings,
    video_projection_settings: crate::native_renderer_options::NativeVideoProjectionSettings,
    mut stimulus_volume_settings: NativeStimulusVolumeSettings,
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
    available_extensions: &xr::ExtensionSet,
    enabled_extensions: &xr::ExtensionSet,
    foveation_settings: NativeFoveationSettings,
    projection_swapchain_settings: NativeProjectionSwapchainSettings,
    projection_foveation_vulkan_support: ProjectionFoveationVulkanSupport,
    projection_border_stretch_settings: NativeProjectionBorderStretchSettings,
    mut private_layer_settings: NativePrivateLayerSettings,
    projection_metadata: &CameraProjectionMetadata,
    display_composite_projection_metadata: &DisplayCompositeProjectionMetadata,
    video_projection_metadata: &VideoProjectionMetadata,
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
    let mut private_particle_world_anchor = PrivateParticleWorldAnchor::new();
    let mut control_panel_command_poller =
        crate::native_renderer_panel_bridge::ControlPanelCommandPoller::default();
    let mut environment_depth_provider_attempts = 0_u32;
    let mut environment_depth_alignment_state =
        EnvironmentDepthAlignmentState::new(environment_depth_alignment_settings);
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
    crate::marker(
        "environment-depth-alignment",
        format!(
            "status=runtime-initialized {}",
            environment_depth_alignment_state.marker_fields()
        ),
    );
    crate::native_renderer_stimulus_panel::write_environment_depth_alignment_status(
        app,
        "applied",
        0,
        "none",
        &environment_depth_alignment_state,
    );

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

        if environment_depth_settings.runtime_provider_requested()
            && openxr_environment_depth_runtime.is_none()
            && environment_depth_provider_attempts < 3
        {
            environment_depth_provider_attempts =
                environment_depth_provider_attempts.saturating_add(1);
            ensure_openxr_environment_depth_runtime(
                xr_instance,
                vk_device,
                memory_properties,
                render_pass,
                session,
                environment_depth_settings,
                environment_depth_properties,
                frame_count,
                environment_depth_provider_attempts,
                openxr_environment_depth_runtime,
                gpu_environment_depth_particle_renderer,
                private_extension_slot_runtime,
            );
        }

        trace_startup_frame(frame_count, "before-xr-wait-frame");
        let frame_state = frame_wait
            .wait()
            .map_err(|error| format!("wait OpenXR frame: {error}"))?;
        trace_startup_frame(frame_count, "after-xr-wait-frame");
        trace_startup_frame(frame_count, "before-xr-begin-frame");
        frame_stream
            .begin()
            .map_err(|error| format!("begin OpenXR frame: {error}"))?;
        trace_startup_frame(frame_count, "after-xr-begin-frame");

        if !frame_state.should_render {
            trace_startup_frame(frame_count, "skip-frame-should-render-false");
            frame_stream
                .end(
                    frame_state.predicted_display_time,
                    environment_blend_mode,
                    &[],
                )
                .map_err(|error| format!("end skipped OpenXR frame: {error}"))?;
            continue;
        }

        trace_startup_frame(frame_count, "before-ensure-swapchain");
        let swapchain = ensure_projection_swapchain(
            xr_instance,
            system,
            vk_device,
            session,
            render_pass,
            color_format,
            available_extensions,
            enabled_extensions,
            foveation_settings,
            projection_swapchain_settings,
            projection_foveation_vulkan_support,
            &mut swapchain,
        )?;
        trace_startup_frame(frame_count, "after-ensure-swapchain");
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
        trace_startup_frame(frame_count, "after-locate-views");

        let frame_instant = Instant::now();
        let dt_seconds = (frame_instant - previous_frame_instant)
            .as_secs_f32()
            .clamp(0.0, 1.0);
        previous_frame_instant = frame_instant;
        let particle_sort_eye_projection = views
            .first()
            .map(hand_mesh_visual_eye_projection)
            .unwrap_or_default();
        if gpu_private_particle_renderer.is_some() {
            private_particle_world_anchor.refresh_scale_from_android_properties(frame_count);
            private_particle_world_anchor
                .capture_startup_if_needed(particle_sort_eye_projection, frame_count);
            if let Some(recenter) =
                crate::native_renderer_stimulus_panel::poll_spatial_camera_panel_session_icosphere_recenter(
                    frame_count,
                )
            {
                private_particle_world_anchor.recenter_at_headset(
                    particle_sort_eye_projection,
                    frame_count,
                    "spatial-camera-panel-session-icosphere-block-start",
                );
                crate::marker(
                    "driver-profile-session",
                    format!(
                        "status=icosphere-recenter-applied frame={} blockIndex={} blockNumber={} conditionId={} surfaceTargetId={} anchor=headset-position",
                        frame_count,
                        recenter.block_index,
                        recenter.block_number,
                        crate::sanitize(&recenter.condition_id),
                        crate::sanitize(&recenter.surface_target_id),
                    ),
                );
            }
        }
        control_panel_command_poller.poll_and_apply(app, frame_count);
        if let Some(open) =
            crate::native_renderer_stimulus_panel::poll_spatial_camera_panel_open(frame_count)
        {
            crate::native_renderer_panel_bridge::open_control_panel(
                app,
                frame_count,
                "spatial-camera-panel-session-block-complete",
            );
            crate::marker(
                "driver-profile-session",
                format!(
                    "status=block-panel-open-issued frame={} blockIndex={} blockNumber={} conditionId={} surfaceTargetId={} deadlineUnixMs={}",
                    frame_count,
                    open.block_index,
                    open.block_number,
                    crate::sanitize(&open.condition_id),
                    crate::sanitize(&open.surface_target_id),
                    open.deadline_unix_ms,
                ),
            );
        }
        if let Some(runtime) = native_passthrough.as_deref_mut() {
            runtime.update_audio_reactive_style(session, dt_seconds, frame_count);
        }

        if let Some(candidate) = crate::native_renderer_stimulus_panel::take_live_candidate() {
            apply_live_stimulus_candidate(
                app,
                vk_device,
                render_mode,
                &mut stimulus_volume_settings,
                gpu_stimulus_volume_renderer.as_deref_mut(),
                stimulus_actions.as_deref_mut(),
                candidate,
                frame_count,
            );
        }
        if let Some(selection) =
            crate::native_renderer_stimulus_panel::take_live_private_layer_selection()
        {
            apply_live_private_layer_selection(
                app,
                &mut private_layer_settings,
                selection,
                frame_count,
            );
        }
        if let Some(candidate) =
            crate::native_renderer_stimulus_panel::take_live_environment_depth_alignment()
        {
            apply_live_environment_depth_alignment(
                app,
                &mut environment_depth_alignment_state,
                candidate,
                frame_count,
            );
        }
        if let Some(candidate) =
            crate::native_renderer_stimulus_panel::take_live_private_particle_dynamics()
        {
            apply_live_private_particle_dynamics(
                app,
                gpu_private_particle_renderer.as_deref_mut(),
                &mut private_particle_world_anchor,
                candidate,
                frame_count,
            );
        }

        if let Some(actions) = stimulus_actions.as_deref_mut() {
            let controller_events = actions.sync_and_poll(
                session,
                reference_space,
                frame_state.predicted_display_time,
                frame_count,
                dt_seconds,
                projection_target_state.breath_haptics_enabled(),
            );
            for input in controller_events.projection_target_inputs {
                projection_target_state.apply_input(input);
            }
            for input in controller_events.environment_depth_alignment_inputs {
                environment_depth_alignment_state.apply_input(input);
            }
            if let Some(renderer) = gpu_private_particle_renderer.as_deref_mut() {
                renderer.update_breath_state_driver(
                    controller_events.native_controller_breath_sample,
                    dt_seconds,
                    frame_count,
                );
            }
            if controller_events.stimulus_randomize_triggered {
                if crate::native_renderer_panel_bridge::right_primary_opens_control_panel() {
                    crate::native_renderer_panel_bridge::open_control_panel(
                        app,
                        frame_count,
                        crate::native_renderer_panel_bridge::right_primary_control_panel_source(),
                    );
                } else if let Some(renderer) = gpu_stimulus_volume_renderer.as_deref_mut() {
                    renderer.randomize(stimulus_volume_settings, frame_count);
                }
            }
            if controller_events.panel_toggle_triggered {
                crate::native_renderer_panel_bridge::toggle_control_panel(app, frame_count);
            }
            if controller_events.private_particle_recenter_triggered
                && gpu_private_particle_renderer.is_some()
            {
                private_particle_world_anchor.recenter(particle_sort_eye_projection, frame_count);
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
            crate::marker(
                "environment-depth-alignment",
                format!(
                    "status=effective frame={} {}",
                    frame_count,
                    environment_depth_alignment_state.marker_fields(),
                ),
            );
            crate::native_renderer_stimulus_panel::write_environment_depth_alignment_status(
                app,
                "applied",
                0,
                "none",
                &environment_depth_alignment_state,
            );
        }

        trace_startup_frame(frame_count, "before-swapchain-acquire-image");
        let image_index = swapchain
            .handle
            .acquire_image()
            .map_err(|error| format!("acquire OpenXR swapchain image: {error}"))?;
        trace_startup_frame(frame_count, "after-swapchain-acquire-image");
        let cmd = cmds[frame_slot];
        trace_startup_frame(frame_count, "before-vulkan-wait-fence");
        vk_device
            .wait_for_fences(&[fences[frame_slot]], true, u64::MAX)
            .map_err(|error| format!("wait Vulkan fence: {error}"))?;
        trace_startup_frame(frame_count, "after-vulkan-wait-fence");
        let retired_image_leases =
            camera_projection_renderer.retire_completed_frame_leases(frame_slot);
        display_composite_feedback_renderer
            .collect_completed_diagnostic_exports(vk_device, frame_slot);
        display_composite_feedback_renderer.retire_completed_frame_handles(frame_slot);
        video_projection_renderer.retire_completed_frame_handles(frame_slot);
        let _completed_luma_diagnostic = camera_projection_renderer
            .collect_completed_luma_diagnostic(frame_slot, camera_luma_diagnostic_enabled);
        if let Some(renderer) = gpu_private_particle_renderer.as_deref_mut() {
            renderer.collect_completed_diagnostics(vk_device, frame_slot);
        }
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
        trace_startup_frame(frame_count, "before-vulkan-reset-fence");
        vk_device
            .reset_fences(&[fences[frame_slot]])
            .map_err(|error| format!("reset Vulkan fence: {error}"))?;
        trace_startup_frame(frame_count, "after-vulkan-reset-fence");
        vk_device
            .reset_command_buffer(cmd, vk::CommandBufferResetFlags::empty())
            .map_err(|error| format!("reset Vulkan command buffer: {error}"))?;
        trace_startup_frame(frame_count, "after-vulkan-reset-command-buffer");

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
        let remote_broker_camera_projection =
            video_projection_settings.remote_broker_camera_projection_active();
        let prepared_camera_projection = if render_mode.uses_custom_stereo_projection()
            && (camera_output_mode.camera_import_enabled() || remote_broker_camera_projection)
        {
            let stereo_frame = if camera_output_mode.camera_import_enabled() {
                camera_runtime.and_then(NativeCameraRuntime::latest_stereo_frame)
            } else {
                crate::remote_camera_projection_native_stream::latest_remote_stereo_frame()
            };
            match stereo_frame.map(|stereo_frame| {
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
                    if video_projection_settings.remote_broker_camera_projection_active() {
                        "remote-broker-camera-projection-inactive"
                    } else {
                        camera_output_mode.marker_value()
                    }
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
                            native_passthrough_requested,
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
        if let Some(prepared) = prepared_camera_projection.as_ref() {
            let hit_delta = prepared
                .stats
                .import_cache_hits
                .saturating_sub(last_camera_import_cache_hits);
            let miss_delta = prepared
                .stats
                .import_cache_misses
                .saturating_sub(last_camera_import_cache_misses);
            if let Some(runtime) = camera_runtime {
                for _ in 0..hit_delta {
                    runtime.record_hardware_buffer_cache_hit();
                }
                for _ in 0..miss_delta {
                    runtime.record_hardware_buffer_cache_miss();
                }
            }
            last_camera_import_cache_hits = prepared.stats.import_cache_hits;
            last_camera_import_cache_misses = prepared.stats.import_cache_misses;
            camera_projection_stats = prepared.stats.clone();
        } else {
            if camera_projection_stats.rendered && (frame_count == 0 || frame_count % 120 == 0) {
                crate::marker(
                    "camera-projection",
                    "status=missing-current-frame previousProjectionCleared=true cameraProjectionReady=false vulkanExternalImportReady=false",
                );
            }
            camera_projection_stats = CameraProjectionFrameStats::default();
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
        let selected_primary_live_hand = if selected_primary_live_hand_frame.is_some() {
            live_hand_frames
                .primary_handedness()
                .unwrap_or_else(|| handedness_label(&replay.handedness))
        } else if compact_hand_input_source_mode.allows_recorded_fallback() {
            handedness_label(&replay.handedness)
        } else {
            "none"
        };
        let selected_secondary_live_hand_frame = compact_hand_input_source_mode
            .selects_live_frame()
            .then(|| live_hand_frames.secondary_frame())
            .flatten();
        let selected_secondary_live_hand = if selected_secondary_live_hand_frame.is_some() {
            live_hand_frames
                .secondary_handedness()
                .unwrap_or_else(|| handedness_label(&secondary_replay.handedness))
        } else if compact_hand_input_source_mode.allows_recorded_fallback() {
            handedness_label(&secondary_replay.handedness)
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
        let secondary_gpu_sdf_stats = if let Some(renderer) =
            secondary_gpu_sdf_field_renderer.as_mut()
        {
            match renderer.record_compute_frame(
                vk_device,
                cmd,
                secondary_replay,
                frame_count,
                false,
                sdf_update_period_frames,
                selected_secondary_live_hand_frame,
                compact_hand_input_source_mode.allows_recorded_fallback(),
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
                hand_mesh_visual_material_settings,
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
                        hand_mesh_visual_material_settings,
                    )
                }
            }
        } else {
            GpuHandMeshVisualFrameStats::unavailable(
                replay,
                frame_count,
                selected_primary_live_hand,
                hand_mesh_visual_diagnostic_settings,
                hand_mesh_visual_material_settings,
            )
        };
        let mut secondary_hand_mesh_visual_stats = if let Some(renderer) =
            secondary_gpu_hand_mesh_visual_renderer.as_mut()
        {
            match renderer.record_frame(
                secondary_replay,
                frame_count,
                secondary_gpu_sdf_stats.skinning_ready,
                selected_secondary_live_hand_frame,
                compact_hand_input_source_mode.allows_recorded_fallback(),
                selected_secondary_live_hand,
                hand_mesh_visual_diagnostic_settings,
                hand_mesh_visual_material_settings,
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
                        hand_mesh_visual_material_settings,
                    )
                }
            }
        } else {
            GpuHandMeshVisualFrameStats::unavailable(
                secondary_replay,
                frame_count,
                selected_secondary_live_hand,
                hand_mesh_visual_diagnostic_settings,
                hand_mesh_visual_material_settings,
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
        let private_particle_stats =
            if let Some(renderer) = gpu_private_particle_renderer.as_deref_mut() {
                renderer.record_compute_frame(
                    vk_device,
                    cmd,
                    gpu_timestamp_tracker,
                    frame_slot,
                    particle_sort_eye_projection,
                    private_particle_world_anchor.world_center_scale(),
                    private_particle_world_anchor.scale_parameter_source(),
                    private_particle_world_anchor.world_forward_axis(),
                    frame_count,
                )
            } else {
                GpuPrivateParticleFrameStats::unavailable()
            };
        if private_particle_stats.half_res_offscreen_requested() {
            if let Some(renderer) = gpu_private_particle_renderer.as_deref_mut() {
                renderer.ensure_half_res_offscreen_resources(
                    vk_device,
                    memory_properties,
                    color_format,
                    swapchain.extent,
                    swapchain.buffers.len(),
                )?;
            }
        }
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
        gpu_timestamp_tracker.write_stage_start(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::StimulusVolumeCompute,
        );
        let stimulus_volume_stats =
            if let Some(renderer) = gpu_stimulus_volume_renderer.as_deref_mut() {
                renderer.record_compute_frame(vk_device, cmd, stimulus_volume_settings, frame_count)
            } else {
                GpuStimulusVolumeFrameStats::unavailable(stimulus_volume_settings)
            };
        gpu_timestamp_tracker.write_stage_end(
            vk_device,
            cmd,
            frame_slot,
            GpuTimestampStage::StimulusVolumeCompute,
        );
        if render_mode.uses_stimulus_volume() {
            gpu_timestamp_tracker.write_stage_start(
                vk_device,
                cmd,
                frame_slot,
                GpuTimestampStage::StimulusVolumeProjection,
            );
        } else {
            gpu_timestamp_tracker.write_stage_start(
                vk_device,
                cmd,
                frame_slot,
                GpuTimestampStage::StimulusVolumeProjection,
            );
            gpu_timestamp_tracker.write_stage_end(
                vk_device,
                cmd,
                frame_slot,
                GpuTimestampStage::StimulusVolumeProjection,
            );
        }
        let (prepared_video_projection, current_video_projection_stats) =
            if video_projection_requested(&video_projection_settings) {
                match crate::video_projection_native_stream::latest_video_projection_frame() {
                    Some(frame) => match video_projection_renderer.prepare_frame(
                        vk_device,
                        cmd,
                        frame_slot,
                        &frame,
                        &video_projection_settings,
                    ) {
                        Ok(Some(prepared)) => {
                            let stats = prepared.stats.clone();
                            (Some(prepared), stats)
                        }
                        Ok(None) => (
                            None,
                            VideoProjectionFrameStats::unavailable(
                                &video_projection_settings,
                                "renderer-inactive",
                            ),
                        ),
                        Err(error) => {
                            if frame_count == 0 || frame_count % 120 == 0 {
                                crate::marker(
                                    "video-projection",
                                    format!(
                                        "status=error reason={} videoProjectionReady=false videoProjectionRendered=false videoProjectionGpuImportReady=false videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image",
                                        crate::sanitize(&error)
                                    ),
                                );
                            }
                            (
                                None,
                                VideoProjectionFrameStats::unavailable(
                                    &video_projection_settings,
                                    "import-error",
                                ),
                            )
                        }
                    },
                    None => (
                        None,
                        VideoProjectionFrameStats::unavailable(
                            &video_projection_settings,
                            "no-latest-frame",
                        ),
                    ),
                }
            } else {
                (
                    None,
                    VideoProjectionFrameStats::unavailable(
                        &video_projection_settings,
                        video_projection_disabled_reason(&video_projection_settings),
                    ),
                )
            };
        let video_projection_stats = current_video_projection_stats;
        if frame_count == 0 || frame_count % 60 == 0 {
            crate::marker(
                "video-projection",
                format!(
                    "status={} renderFrame={} {}",
                    if video_projection_stats.rendered {
                        "rendered"
                    } else {
                        "pending"
                    },
                    frame_count,
                    video_projection_stats.marker_fields()
                ),
            );
        }
        let (prepared_display_composite_feedback, display_composite_feedback_stats) =
            if display_composite_feedback_requested(display_composite_settings) {
                let display_composite_xr_ready_marker_active =
                    !native_passthrough_requested || native_passthrough.is_some();
                match crate::display_composite_native_stream::latest_display_composite_frame() {
                    Some(frame) => match display_composite_feedback_renderer.prepare_frame(
                        vk_device,
                        cmd,
                        frame_slot,
                        frame_count,
                        display_composite_xr_ready_marker_active,
                        &frame,
                        display_composite_settings,
                        display_composite_projection_metadata,
                    ) {
                        Ok(Some(prepared)) => {
                            let stats = prepared.stats.clone();
                            (Some(prepared), stats)
                        }
                        Ok(None) => (
                            None,
                            DisplayCompositeFrameStats::unavailable(
                                display_composite_settings.feedback_projection,
                                "renderer-inactive",
                            ),
                        ),
                        Err(error) => {
                            if frame_count == 0 || frame_count % 120 == 0 {
                                crate::marker(
                                    "display-composite-feedback",
                                    format!(
                                        "status=error reason={} displayCompositeFeedbackReady=false displayCompositeFeedbackRendered=false displayCompositeGpuImportReady=false displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image",
                                        crate::sanitize(&error)
                                    ),
                                );
                            }
                            (
                                None,
                                DisplayCompositeFrameStats::unavailable(
                                    display_composite_settings.feedback_projection,
                                    "import-error",
                                ),
                            )
                        }
                    },
                    None => (
                        None,
                        DisplayCompositeFrameStats::unavailable(
                            display_composite_settings.feedback_projection,
                            "no-latest-frame",
                        ),
                    ),
                }
            } else {
                (
                    None,
                    DisplayCompositeFrameStats::unavailable(
                        display_composite_settings.feedback_projection,
                        display_composite_feedback_disabled_reason(display_composite_settings),
                    ),
                )
            };
        if frame_count == 0 || frame_count % 60 == 0 {
            crate::marker(
                "display-composite-feedback",
                format!(
                    "status={} renderFrame={} {}",
                    if display_composite_feedback_stats.rendered {
                        "rendered"
                    } else {
                        "pending"
                    },
                    frame_count,
                    display_composite_feedback_stats.marker_fields()
                ),
            );
        }
        let replay_visual_stats = record_projection_diagnostic(
            vk_device,
            cmd,
            gpu_timestamp_tracker,
            frame_slot,
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
            video_projection_renderer,
            prepared_video_projection.as_ref(),
            &video_projection_stats,
            &video_projection_settings,
            video_projection_metadata,
            display_composite_feedback_renderer,
            prepared_display_composite_feedback.as_ref(),
            &display_composite_feedback_stats,
            display_composite_settings,
            display_composite_projection_metadata,
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
            gpu_environment_depth_particle_renderer.as_ref(),
            &environment_depth_particle_stats,
            openxr_environment_depth_frame.as_ref(),
            environment_depth_settings,
            &environment_depth_alignment_state,
            gpu_private_particle_renderer.as_deref(),
            &private_particle_stats,
            private_particle_world_anchor.world_center_scale(),
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
        if render_mode.uses_stimulus_volume() {
            gpu_timestamp_tracker.write_stage_end(
                vk_device,
                cmd,
                frame_slot,
                GpuTimestampStage::StimulusVolumeProjection,
            );
        }
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
        trace_startup_frame(frame_count, "before-swapchain-wait-image");
        swapchain
            .handle
            .wait_image(xr::Duration::INFINITE)
            .map_err(|error| format!("wait OpenXR swapchain image: {error}"))?;
        trace_startup_frame(frame_count, "after-swapchain-wait-image");
        frame_timings.swapchain_wait_ms = elapsed_ms(stage_started);
        let submit_started = Instant::now();
        trace_startup_frame(frame_count, "before-vulkan-queue-submit");
        vk_device
            .queue_submit(
                queue,
                &[vk::SubmitInfo::default().command_buffers(&[cmd])],
                fences[frame_slot],
            )
            .map_err(|error| format!("submit Vulkan queue: {error}"))?;
        trace_startup_frame(frame_count, "after-vulkan-queue-submit");
        let submit_ms = submit_started.elapsed().as_secs_f64() * 1000.0;
        frame_timings.queue_submit_ms = submit_ms;
        trace_startup_frame(frame_count, "before-swapchain-release-image");
        swapchain
            .handle
            .release_image()
            .map_err(|error| format!("release OpenXR swapchain image: {error}"))?;
        trace_startup_frame(frame_count, "after-swapchain-release-image");

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
        let passthrough_layer = native_passthrough
            .as_ref()
            .map(|runtime| runtime.composition_layer_raw(reference_space));
        let mut layers: Vec<&xr::CompositionLayerBase<xr::Vulkan>> = Vec::with_capacity(2);
        if let Some(passthrough_layer) = passthrough_layer.as_ref() {
            layers.push(passthrough_layer_base(passthrough_layer));
        }
        layers.push(&projection_layer);
        let stage_started = Instant::now();
        trace_startup_frame(frame_count, "before-xr-end-frame");
        frame_stream
            .end(
                frame_state.predicted_display_time,
                environment_blend_mode,
                &layers,
            )
            .map_err(|error| format!("end OpenXR frame: {error}"))?;
        trace_startup_frame(frame_count, "after-xr-end-frame");
        frame_timings.openxr_end_frame_ms = elapsed_ms(stage_started);

        frame_count = frame_count.saturating_add(1);
        pacing_window_frames = pacing_window_frames.saturating_add(1);
        if let Some(runtime) = camera_runtime {
            runtime.record_xr_frame_submitted();
        }
        if frame_count == 1 || frame_count % 120 == 0 {
            let window_secs = pacing_window_start.elapsed().as_secs_f64().max(0.001);
            let observed_openxr_fps = pacing_window_frames as f64 / window_secs;
            scorecard::write_projection_scorecard(
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
                remote_broker_camera_projection,
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
                native_passthrough_requested,
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

fn trace_startup_frame(frame_count: u64, stage: &str) {
    if frame_count < 4 {
        crate::marker(
            "openxr-frame-trace",
            format!("frame={} stage={}", frame_count, crate::sanitize(stage)),
        );
    }
}

fn apply_live_stimulus_candidate(
    app: &android_activity::AndroidApp,
    vk_device: &ash::Device,
    render_mode: NativeRendererRenderMode,
    stimulus_volume_settings: &mut NativeStimulusVolumeSettings,
    gpu_stimulus_volume_renderer: Option<&mut GpuStimulusVolumeRenderer>,
    stimulus_actions: Option<&mut StimulusVolumeActions>,
    candidate: crate::native_renderer_stimulus_panel::StimulusPanelCandidate,
    frame_count: u64,
) {
    let revision = candidate.revision;
    if candidate.render_mode != render_mode {
        let reason = format!(
            "render_mode_requires_restart:{}->{}",
            render_mode.marker_value(),
            candidate.render_mode.marker_value()
        );
        reject_live_stimulus_candidate(app, frame_count, revision, &reason);
        return;
    }
    if candidate.settings.render_target != stimulus_volume_settings.render_target {
        let reason = format!(
            "render_target_requires_restart:{}->{}",
            stimulus_volume_settings.render_target.marker_value(),
            candidate.settings.render_target.marker_value()
        );
        reject_live_stimulus_candidate(app, frame_count, revision, &reason);
        return;
    }
    let Some(renderer) = gpu_stimulus_volume_renderer else {
        reject_live_stimulus_candidate(
            app,
            frame_count,
            revision,
            "stimulus_volume_renderer_unavailable",
        );
        return;
    };

    *stimulus_volume_settings = candidate.settings;
    match unsafe {
        renderer.apply_live_settings(vk_device, *stimulus_volume_settings, frame_count, revision)
    } {
        Ok(()) => {
            crate::marker(
                "stimulus-panel",
                format!(
                    "status=live-applied transport=jni-live-queue frame={} candidateRevision={} {}",
                    frame_count,
                    revision,
                    stimulus_volume_settings.marker_fields()
                ),
            );
            crate::native_renderer_stimulus_panel::write_live_status(
                app,
                "applied",
                revision,
                "none",
                Some(&*stimulus_volume_settings),
            );
            if let Some(actions) = stimulus_actions {
                actions.update_stimulus_settings(*stimulus_volume_settings, frame_count);
            }
        }
        Err(reason) => reject_live_stimulus_candidate(app, frame_count, revision, &reason),
    }
}

fn apply_live_private_layer_selection(
    app: &android_activity::AndroidApp,
    private_layer_settings: &mut NativePrivateLayerSettings,
    selection: crate::native_renderer_stimulus_panel::PrivateLayerPanelSelection,
    frame_count: u64,
) {
    let revision = selection.revision;
    if !private_layer_settings.enabled {
        reject_live_private_layer_selection(app, frame_count, revision, "private_layer_disabled");
        return;
    }
    private_layer_settings.layer_override = selection.layer_override;
    crate::marker(
        "private-layer-panel",
        format!(
            "status=live-applied transport=jni-live-queue frame={} candidateRevision={} privateLayerOverride={:.1} privateLayerActiveLayer={} {}",
            frame_count,
            revision,
            selection.layer_override,
            crate::sanitize(&selection.layer_label),
            private_layer_settings.marker_fields()
        ),
    );
    crate::native_renderer_stimulus_panel::write_private_layer_selection_status(
        app,
        "applied",
        revision,
        "none",
        Some(&selection),
    );
}

fn apply_live_environment_depth_alignment(
    app: &android_activity::AndroidApp,
    environment_depth_alignment_state: &mut EnvironmentDepthAlignmentState,
    candidate: crate::native_renderer_stimulus_panel::EnvironmentDepthAlignmentPanelCandidate,
    frame_count: u64,
) {
    let revision = candidate.revision;
    if !environment_depth_alignment_state.controls_enabled() {
        reject_live_environment_depth_alignment(
            app,
            environment_depth_alignment_state,
            frame_count,
            revision,
            "environment_depth_alignment_controls_disabled",
        );
        return;
    }
    environment_depth_alignment_state
        .set_effective_alignment(candidate.effective_offsets_uv, candidate.sample_scale);
    crate::marker(
        "environment-depth-alignment-panel",
        format!(
            "status=live-applied transport=jni-live-queue frame={} candidateRevision={} {}",
            frame_count,
            revision,
            environment_depth_alignment_state.marker_fields()
        ),
    );
    crate::native_renderer_stimulus_panel::write_environment_depth_alignment_status(
        app,
        "applied",
        revision,
        "none",
        environment_depth_alignment_state,
    );
}

fn apply_live_private_particle_dynamics(
    app: &android_activity::AndroidApp,
    gpu_private_particle_renderer: Option<&mut GpuPrivateParticleRenderer>,
    private_particle_world_anchor: &mut PrivateParticleWorldAnchor,
    candidate: crate::native_renderer_stimulus_panel::PrivateParticleDynamicsPanelCandidate,
    frame_count: u64,
) {
    let revision = candidate.revision;
    let Some(renderer) = gpu_private_particle_renderer else {
        reject_live_private_particle_dynamics(
            app,
            frame_count,
            revision,
            "private_particle_renderer_unavailable",
            Some(&candidate),
        );
        return;
    };
    let world_anchor_scale_m = private_particle_world_anchor
        .apply_panel_scale(candidate.world_anchor_scale_m, frame_count);
    let effective_settings =
        renderer.apply_panel_settings(candidate.panel_settings(), frame_count, revision);
    let effective =
        crate::native_renderer_stimulus_panel::PrivateParticleDynamicsPanelAppliedState {
            world_anchor_scale_m,
            world_anchor_scale_parameter_source: private_particle_world_anchor
                .scale_parameter_source(),
            settings: effective_settings,
        };
    crate::marker(
        "private-particle-panel",
        format!(
            "status=live-applied transport=jni-live-queue frame={} candidateRevision={} privateParticleVisualScale={:.3} privateParticleWorldAnchorScaleM={:.3} privateParticleDriver0Value01={:.3} privateParticleDriver1Value01={:.3} privateParticleDriverParameterSource={} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerDrawSlotsCapacity={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTracerParameterSource={} privateParticleTransparencyOpacity={:.3} privateParticleTransparencyOutputAlphaScale={:.3} privateParticleTransparencyDepthSuppressionStrength={:.3} privateParticleTransparencyRgbAlphaCoupling={:.3} privateParticleTransparencyParameterSource={} privateParticleColorFacingAttenuationStrength={:.3} privateParticleColorParameterSource={}",
            frame_count,
            revision,
            effective.settings.visual_scale,
            effective.world_anchor_scale_m,
            effective.settings.driver_values01[0],
            effective.settings.driver_values01[1],
            crate::sanitize(effective.settings.driver_parameter_source),
            effective.settings.tracer_draw_slots_per_oscillator,
            effective.settings.tracer_draw_slots_capacity,
            effective.settings.tracer_lifetime_seconds,
            effective.settings.tracer_copies_per_second,
            crate::sanitize(effective.settings.tracer_parameter_source),
            effective.settings.transparency_opacity,
            effective.settings.transparency_output_alpha_scale,
            effective.settings.transparency_depth_suppression_strength,
            effective.settings.transparency_rgb_alpha_coupling,
            crate::sanitize(effective.settings.transparency_parameter_source),
            effective.settings.color_facing_attenuation_strength,
            crate::sanitize(effective.settings.color_parameter_source)
        ),
    );
    crate::native_renderer_stimulus_panel::write_private_particle_dynamics_status(
        app,
        "applied",
        revision,
        "none",
        Some(&candidate),
        Some(&effective),
    );
}

fn reject_live_environment_depth_alignment(
    app: &android_activity::AndroidApp,
    environment_depth_alignment_state: &EnvironmentDepthAlignmentState,
    frame_count: u64,
    revision: i64,
    reason: &str,
) {
    crate::marker(
        "environment-depth-alignment-panel",
        format!(
            "status=live-rejected transport=jni-live-queue frame={} candidateRevision={} reason={} {}",
            frame_count,
            revision,
            crate::sanitize(reason),
            environment_depth_alignment_state.marker_fields()
        ),
    );
    crate::native_renderer_stimulus_panel::write_environment_depth_alignment_status(
        app,
        "rejected",
        revision,
        reason,
        environment_depth_alignment_state,
    );
}

fn reject_live_private_particle_dynamics(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    revision: i64,
    reason: &str,
    candidate: Option<
        &crate::native_renderer_stimulus_panel::PrivateParticleDynamicsPanelCandidate,
    >,
) {
    crate::marker(
        "private-particle-panel",
        format!(
            "status=live-rejected transport=jni-live-queue frame={} candidateRevision={} reason={}",
            frame_count,
            revision,
            crate::sanitize(reason)
        ),
    );
    crate::native_renderer_stimulus_panel::write_private_particle_dynamics_status(
        app, "rejected", revision, reason, candidate, None,
    );
}

fn reject_live_private_layer_selection(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    revision: i64,
    reason: &str,
) {
    crate::marker(
        "private-layer-panel",
        format!(
            "status=live-rejected transport=jni-live-queue frame={} candidateRevision={} reason={}",
            frame_count,
            revision,
            crate::sanitize(reason)
        ),
    );
    crate::native_renderer_stimulus_panel::write_private_layer_selection_status(
        app, "rejected", revision, reason, None,
    );
}

fn reject_live_stimulus_candidate(
    app: &android_activity::AndroidApp,
    frame_count: u64,
    revision: i64,
    reason: &str,
) {
    crate::marker(
        "stimulus-panel",
        format!(
            "status=live-rejected transport=jni-live-queue frame={} candidateRevision={} reason={}",
            frame_count,
            revision,
            crate::sanitize(reason)
        ),
    );
    crate::native_renderer_stimulus_panel::write_live_status(
        app, "rejected", revision, reason, None,
    );
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
    fragment_density_map_enabled: bool,
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
    let fragment_density_map_attachment = vk::AttachmentDescription {
        format: vk::Format::R8G8_UNORM,
        samples: vk::SampleCountFlags::TYPE_1,
        load_op: vk::AttachmentLoadOp::LOAD,
        store_op: vk::AttachmentStoreOp::DONT_CARE,
        initial_layout: vk::ImageLayout::FRAGMENT_DENSITY_MAP_OPTIMAL_EXT,
        final_layout: vk::ImageLayout::FRAGMENT_DENSITY_MAP_OPTIMAL_EXT,
        ..Default::default()
    };
    let color_refs = [vk::AttachmentReference {
        attachment: 0,
        layout: vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
    }];
    let fragment_density_ref = vk::AttachmentReference {
        attachment: 1,
        layout: vk::ImageLayout::FRAGMENT_DENSITY_MAP_OPTIMAL_EXT,
    };
    let subpasses = [vk::SubpassDescription::default()
        .color_attachments(&color_refs)
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)];
    let color_dependency = vk::SubpassDependency {
        src_subpass: vk::SUBPASS_EXTERNAL,
        dst_subpass: 0,
        src_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
        dst_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
        dst_access_mask: vk::AccessFlags::COLOR_ATTACHMENT_WRITE,
        ..Default::default()
    };
    let fragment_density_dependency = vk::SubpassDependency {
        src_subpass: vk::SUBPASS_EXTERNAL,
        dst_subpass: 0,
        src_stage_mask: vk::PipelineStageFlags::TOP_OF_PIPE,
        dst_stage_mask: vk::PipelineStageFlags::FRAGMENT_DENSITY_PROCESS_EXT,
        dst_access_mask: vk::AccessFlags::FRAGMENT_DENSITY_MAP_READ_EXT,
        ..Default::default()
    };
    let color_dependencies = [color_dependency];
    let fdm_dependencies = [fragment_density_dependency, color_dependency];
    let color_only_attachments = [color_attachment];
    let fdm_attachments = [color_attachment, fragment_density_map_attachment];
    let mut fragment_density_map_info = vk::RenderPassFragmentDensityMapCreateInfoEXT::default()
        .fragment_density_map_attachment(fragment_density_ref);
    let mut render_pass_info = vk::RenderPassCreateInfo::default()
        .attachments(if fragment_density_map_enabled {
            &fdm_attachments
        } else {
            &color_only_attachments
        })
        .subpasses(&subpasses)
        .dependencies(if fragment_density_map_enabled {
            &fdm_dependencies
        } else {
            &color_dependencies
        });
    if fragment_density_map_enabled {
        render_pass_info = render_pass_info.push_next(&mut fragment_density_map_info);
    }
    device
        .create_render_pass(&render_pass_info, None)
        .map_err(|error| format!("create Vulkan render pass: {error}"))
}

unsafe fn ensure_projection_swapchain<'a>(
    xr_instance: &xr::Instance,
    system: xr::SystemId,
    device: &ash::Device,
    session: &xr::Session<xr::Vulkan>,
    render_pass: vk::RenderPass,
    color_format: vk::Format,
    available_extensions: &xr::ExtensionSet,
    enabled_extensions: &xr::ExtensionSet,
    foveation_settings: NativeFoveationSettings,
    projection_swapchain_settings: NativeProjectionSwapchainSettings,
    projection_foveation_vulkan_support: ProjectionFoveationVulkanSupport,
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
        let recommended_extent = vk::Extent2D {
            width: views[0].recommended_image_rect_width,
            height: views[0].recommended_image_rect_height,
        };
        let extent = vk::Extent2D {
            width: projection_swapchain_settings.scaled_dimension(recommended_extent.width),
            height: projection_swapchain_settings.scaled_dimension(recommended_extent.height),
        };
        let fdm_requested = foveation_settings.requested()
            && enabled_extensions.fb_foveation
            && enabled_extensions.fb_foveation_vulkan
            && projection_foveation_vulkan_support.fdm_ready();
        let handle =
            create_projection_swapchain_handle(session, color_format, extent, fdm_requested)?;
        let foveation = apply_projection_swapchain_foveation(
            xr_instance,
            session,
            &handle,
            foveation_settings,
            available_extensions,
            enabled_extensions,
            projection_foveation_vulkan_support,
            fdm_requested,
        );
        let projection_images = enumerate_projection_swapchain_images(&handle, fdm_requested)?;
        let mut buffers = Vec::with_capacity(projection_images.len());
        for projection_image in projection_images {
            buffers.push(create_projection_swapchain_buffer(
                device,
                render_pass,
                color_format,
                projection_image.color_image,
                projection_image.foveation_image,
                extent,
                projection_foveation_vulkan_support,
            )?);
        }
        crate::marker(
            "openxr-swapchain",
            format!(
                "status=created width={} height={} recommendedWidth={} recommendedHeight={} projectionSwapchainResolutionScale={:.3} projectionSwapchainScaleApplied={} views={} images={} colorFormat={:?} renderPath=per-eye-array-layer-clear foveationSwapchainCreateFdmRequested={} foveationSwapchainCreateMetaSubsampledRequested={} {} openxrSubmitReady=pending",
                extent.width,
                extent.height,
                recommended_extent.width,
                recommended_extent.height,
                projection_swapchain_settings.resolution_scale,
                projection_swapchain_settings.scale_applied(),
                VIEW_COUNT,
                buffers.len(),
                color_format,
                fdm_requested,
                fdm_requested,
                foveation.marker_fields,
            ),
        );
        *swapchain = Some(ProjectionSwapchain {
            handle,
            buffers,
            extent,
            render_pass,
            _foveation_profile: foveation.profile,
        });
    }

    swapchain
        .as_mut()
        .ok_or_else(|| "projection swapchain was not initialized".to_string())
}

unsafe fn create_projection_swapchain_handle(
    session: &xr::Session<xr::Vulkan>,
    color_format: vk::Format,
    extent: vk::Extent2D,
    fragment_density_map_requested: bool,
) -> Result<xr::Swapchain<xr::Vulkan>, String> {
    let mut meta_vulkan_create_info = xr::sys::VulkanSwapchainCreateInfoMETA {
        ty: xr::sys::VulkanSwapchainCreateInfoMETA::TYPE,
        next: ptr::null(),
        additional_create_flags: vk::ImageCreateFlags::SUBSAMPLED_EXT.as_raw() as _,
        additional_usage_flags: 0,
    };
    let mut foveation_create_info = xr::sys::SwapchainCreateInfoFoveationFB {
        ty: xr::sys::SwapchainCreateInfoFoveationFB::TYPE,
        next: if fragment_density_map_requested {
            &mut meta_vulkan_create_info as *mut _ as *mut _
        } else {
            ptr::null_mut()
        },
        flags: xr::sys::SwapchainCreateFoveationFlagsFB::FRAGMENT_DENSITY_MAP,
    };
    let next = if fragment_density_map_requested {
        &mut foveation_create_info as *mut _ as *const _
    } else {
        ptr::null()
    };
    let create_info = xr::sys::SwapchainCreateInfo {
        ty: xr::sys::SwapchainCreateInfo::TYPE,
        next,
        create_flags: xr::SwapchainCreateFlags::EMPTY,
        usage_flags: xr::SwapchainUsageFlags::COLOR_ATTACHMENT | xr::SwapchainUsageFlags::SAMPLED,
        format: color_format.as_raw() as i64,
        sample_count: 1,
        width: extent.width,
        height: extent.height,
        face_count: 1,
        array_size: VIEW_COUNT,
        mip_count: 1,
    };
    let mut raw_swapchain = xr::sys::Swapchain::NULL;
    let result = (session.instance().fp().create_swapchain)(
        session.as_raw(),
        &create_info,
        &mut raw_swapchain,
    );
    ensure_xr_success(result, "xrCreateSwapchain")?;
    Ok(xr::Swapchain::from_raw(session.clone(), raw_swapchain))
}

#[derive(Clone, Copy, Debug)]
struct ProjectionSwapchainImage {
    color_image: vk::Image,
    foveation_image: Option<ProjectionFoveationImage>,
}

#[derive(Clone, Copy, Debug)]
struct ProjectionFoveationImage {
    image: vk::Image,
    extent: vk::Extent2D,
}

fn enumerate_projection_swapchain_images(
    handle: &xr::Swapchain<xr::Vulkan>,
    fragment_density_map_requested: bool,
) -> Result<Vec<ProjectionSwapchainImage>, String> {
    if !fragment_density_map_requested {
        return handle
            .enumerate_images()
            .map(|images| {
                images
                    .into_iter()
                    .map(|image| ProjectionSwapchainImage {
                        color_image: vk::Image::from_raw(image),
                        foveation_image: None,
                    })
                    .collect()
            })
            .map_err(|error| format!("enumerate OpenXR swapchain images: {error}"));
    }

    let mut image_count = 0;
    let first_result = unsafe {
        (handle.instance().fp().enumerate_swapchain_images)(
            handle.as_raw(),
            0,
            &mut image_count,
            ptr::null_mut(),
        )
    };
    ensure_xr_success(first_result, "xrEnumerateSwapchainImages(count)")?;
    let mut foveation_images = vec![
        xr::sys::SwapchainImageFoveationVulkanFB {
            ty: xr::sys::SwapchainImageFoveationVulkanFB::TYPE,
            next: ptr::null_mut(),
            image: 0,
            width: 0,
            height: 0,
        };
        image_count as usize
    ];
    let mut color_images = Vec::with_capacity(image_count as usize);
    for foveation_image in foveation_images.iter_mut() {
        color_images.push(xr::sys::SwapchainImageVulkanKHR {
            ty: xr::sys::SwapchainImageVulkanKHR::TYPE,
            next: foveation_image as *mut _ as *mut _,
            image: 0,
        });
    }
    let mut returned_count = image_count;
    let second_result = unsafe {
        (handle.instance().fp().enumerate_swapchain_images)(
            handle.as_raw(),
            image_count,
            &mut returned_count,
            color_images.as_mut_ptr() as *mut xr::sys::SwapchainImageBaseHeader,
        )
    };
    ensure_xr_success(second_result, "xrEnumerateSwapchainImages(images)")?;
    if returned_count != image_count {
        return Err(format!(
            "OpenXR returned {returned_count} swapchain images after reporting {image_count}"
        ));
    }
    Ok(color_images
        .into_iter()
        .zip(foveation_images)
        .map(|(color, foveation)| ProjectionSwapchainImage {
            color_image: vk::Image::from_raw(color.image as _),
            foveation_image: Some(ProjectionFoveationImage {
                image: vk::Image::from_raw(foveation.image as _),
                extent: vk::Extent2D {
                    width: foveation.width,
                    height: foveation.height,
                },
            }),
        })
        .collect())
}

struct ProjectionSwapchainFoveation {
    profile: Option<xr::FoveationProfileFB>,
    marker_fields: String,
}

fn apply_projection_swapchain_foveation(
    xr_instance: &xr::Instance,
    session: &xr::Session<xr::Vulkan>,
    handle: &xr::Swapchain<xr::Vulkan>,
    settings: NativeFoveationSettings,
    available_extensions: &xr::ExtensionSet,
    enabled_extensions: &xr::ExtensionSet,
    projection_foveation_vulkan_support: ProjectionFoveationVulkanSupport,
    fragment_density_map_requested: bool,
) -> ProjectionSwapchainFoveation {
    let extension_fields =
        projection_foveation_extension_fields(available_extensions, enabled_extensions);
    let base_fields = format!(
        "{} {} {} foveationVulkanFdmAttachment={} foveationSubsampledLayout={}",
        settings.marker_fields(),
        extension_fields,
        projection_foveation_vulkan_support.marker_fields(),
        fragment_density_map_requested,
        fragment_density_map_requested
    );
    if !settings.requested() {
        let marker_fields = format!(
            "foveationStatus=disabled foveationProfileApplied=false {}",
            base_fields
        );
        crate::marker("openxr-foveation", marker_fields.clone());
        return ProjectionSwapchainFoveation {
            profile: None,
            marker_fields,
        };
    }

    let mut missing = Vec::new();
    if !enabled_extensions.fb_foveation {
        missing.push("XR_FB_foveation");
    }
    if !enabled_extensions.fb_swapchain_update_state {
        missing.push("XR_FB_swapchain_update_state");
    }
    if !missing.is_empty() {
        let marker_fields = format!(
            "foveationStatus=unavailable foveationProfileApplied=false reason=missing-{} {}",
            missing.join("+"),
            base_fields
        );
        crate::marker("openxr-foveation", marker_fields.clone());
        return ProjectionSwapchainFoveation {
            profile: None,
            marker_fields,
        };
    }

    let profile = match session.create_foveation_profile(Some(xr::FoveationLevelProfile {
        level: xr_foveation_level(settings.level),
        vertical_offset: settings.vertical_offset,
        dynamic: if settings.dynamic {
            xr::FoveationDynamicFB::LEVEL_ENABLED
        } else {
            xr::FoveationDynamicFB::DISABLED
        },
    })) {
        Ok(profile) => profile,
        Err(error) => {
            let marker_fields = format!(
                "foveationStatus=profile-create-failed foveationProfileApplied=false reason={} {}",
                crate::sanitize(&format!("{error}")),
                base_fields
            );
            crate::marker("openxr-foveation", marker_fields.clone());
            return ProjectionSwapchainFoveation {
                profile: None,
                marker_fields,
            };
        }
    };

    let Some(update_swapchain) = xr_instance.exts().fb_swapchain_update_state.as_ref() else {
        let marker_fields = format!(
            "foveationStatus=update-extension-missing foveationProfileApplied=false {}",
            base_fields
        );
        crate::marker("openxr-foveation", marker_fields.clone());
        return ProjectionSwapchainFoveation {
            profile: None,
            marker_fields,
        };
    };

    let state = xr::sys::SwapchainStateFoveationFB {
        ty: xr::sys::StructureType::SWAPCHAIN_STATE_FOVEATION_FB,
        next: ptr::null_mut(),
        flags: xr::SwapchainStateFoveationFlagsFB::EMPTY,
        profile: profile.as_raw(),
    };
    let state_header = &state as *const xr::sys::SwapchainStateFoveationFB
        as *const xr::sys::SwapchainStateBaseHeaderFB;
    let update_result =
        unsafe { (update_swapchain.update_swapchain)(handle.as_raw(), state_header) };
    if update_result.into_raw() < 0 {
        let marker_fields = format!(
            "foveationStatus=swapchain-update-failed foveationProfileApplied=false xrResult={:?} {}",
            update_result,
            base_fields
        );
        crate::marker("openxr-foveation", marker_fields.clone());
        return ProjectionSwapchainFoveation {
            profile: None,
            marker_fields,
        };
    }

    let marker_fields = format!(
        "foveationStatus=applied foveationProfileApplied=true xrResult={:?} {}",
        update_result, base_fields
    );
    crate::marker("openxr-foveation", marker_fields.clone());
    ProjectionSwapchainFoveation {
        profile: Some(profile),
        marker_fields,
    }
}

fn projection_foveation_extension_fields(
    available_extensions: &xr::ExtensionSet,
    enabled_extensions: &xr::ExtensionSet,
) -> String {
    format!(
        "xrFbFoveationAvailable={} xrFbFoveationEnabled={} xrFbFoveationConfigurationAvailable={} xrFbFoveationConfigurationEnabled={} xrFbFoveationVulkanAvailable={} xrFbFoveationVulkanEnabled={} xrFbSwapchainUpdateStateAvailable={} xrFbSwapchainUpdateStateEnabled={} xrFbSwapchainUpdateStateVulkanAvailable={} xrFbSwapchainUpdateStateVulkanEnabled={} xrMetaVulkanSwapchainCreateInfoAvailable={} xrMetaVulkanSwapchainCreateInfoEnabled={}",
        available_extensions.fb_foveation,
        enabled_extensions.fb_foveation,
        available_extensions.fb_foveation_configuration,
        enabled_extensions.fb_foveation_configuration,
        available_extensions.fb_foveation_vulkan,
        enabled_extensions.fb_foveation_vulkan,
        available_extensions.fb_swapchain_update_state,
        enabled_extensions.fb_swapchain_update_state,
        available_extensions.fb_swapchain_update_state_vulkan,
        enabled_extensions.fb_swapchain_update_state_vulkan,
        available_extensions.meta_vulkan_swapchain_create_info,
        enabled_extensions.meta_vulkan_swapchain_create_info,
    )
}

fn xr_foveation_level(level: NativeFoveationLevel) -> xr::FoveationLevelFB {
    match level {
        NativeFoveationLevel::Low => xr::FoveationLevelFB::LOW,
        NativeFoveationLevel::Medium => xr::FoveationLevelFB::MEDIUM,
        NativeFoveationLevel::High => xr::FoveationLevelFB::HIGH,
    }
}

unsafe fn create_projection_swapchain_buffer(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    color_format: vk::Format,
    image: vk::Image,
    foveation_image: Option<ProjectionFoveationImage>,
    extent: vk::Extent2D,
    projection_foveation_vulkan_support: ProjectionFoveationVulkanSupport,
) -> Result<ProjectionSwapchainBuffer, String> {
    let mut eyes = Vec::with_capacity(VIEW_COUNT_USIZE);
    let fragment_density_map_enabled = projection_foveation_vulkan_support.fdm_ready();
    let foveation_view_flags = if fragment_density_map_enabled
        && projection_foveation_vulkan_support.deferred_feature_ready
    {
        vk::ImageViewCreateFlags::FRAGMENT_DENSITY_MAP_DEFERRED_EXT
    } else {
        vk::ImageViewCreateFlags::empty()
    };
    let foveation_view_flag_label = if foveation_view_flags
        .contains(vk::ImageViewCreateFlags::FRAGMENT_DENSITY_MAP_DEFERRED_EXT)
    {
        "deferred"
    } else {
        "none"
    };
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
        let foveation_view = if fragment_density_map_enabled {
            let Some(foveation_image) = foveation_image else {
                device.destroy_image_view(view, None);
                return Err(
                    "FDM render pass requested but OpenXR did not return a foveation image"
                        .to_string(),
                );
            };
            match device.create_image_view(
                &vk::ImageViewCreateInfo::default()
                    .flags(foveation_view_flags)
                    .image(foveation_image.image)
                    .view_type(vk::ImageViewType::TYPE_2D)
                    .format(vk::Format::R8G8_UNORM)
                    .subresource_range(vk::ImageSubresourceRange {
                        aspect_mask: vk::ImageAspectFlags::COLOR,
                        base_mip_level: 0,
                        level_count: 1,
                        base_array_layer: 0,
                        layer_count: 1,
                    }),
                None,
            ) {
                Ok(foveation_view) => Some(foveation_view),
                Err(error) => {
                    device.destroy_image_view(view, None);
                    return Err(format!(
                        "create Vulkan foveation image view {}x{}: {error}",
                        foveation_image.extent.width, foveation_image.extent.height
                    ));
                }
            }
        } else {
            None
        };
        if foveation_view.is_some() {
            crate::marker(
                "openxr-foveation-framebuffer",
                format!(
                    "stage=create eye={} colorViewType=2D colorBaseArrayLayer={} foveationImageWidth={} foveationImageHeight={} foveationViewType=2D foveationBaseArrayLayer=0 foveationViewFlags={} framebufferWidth={} framebufferHeight={} framebufferLayers=1",
                    eye_index,
                    eye_index,
                    foveation_image
                        .map(|image| image.extent.width)
                        .unwrap_or_default(),
                    foveation_image
                        .map(|image| image.extent.height)
                        .unwrap_or_default(),
                    foveation_view_flag_label,
                    extent.width,
                    extent.height,
                ),
            );
        }
        let framebuffer = if let Some(foveation_view) = foveation_view {
            let attachments = [view, foveation_view];
            device.create_framebuffer(
                &vk::FramebufferCreateInfo::default()
                    .render_pass(render_pass)
                    .attachments(&attachments)
                    .width(extent.width)
                    .height(extent.height)
                    .layers(1),
                None,
            )
        } else {
            let attachments = [view];
            device.create_framebuffer(
                &vk::FramebufferCreateInfo::default()
                    .render_pass(render_pass)
                    .attachments(&attachments)
                    .width(extent.width)
                    .height(extent.height)
                    .layers(1),
                None,
            )
        }
        .map_err(|error| {
            if let Some(foveation_view) = foveation_view {
                device.destroy_image_view(foveation_view, None);
            }
            device.destroy_image_view(view, None);
            format!("create Vulkan framebuffer: {error}")
        })?;
        eyes.push(ProjectionEyeTarget {
            view,
            foveation_view,
            framebuffer,
        });
    }
    Ok(ProjectionSwapchainBuffer { eyes })
}

unsafe fn record_projection_diagnostic(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    gpu_timestamp_tracker: &GpuTimestampTracker,
    frame_slot: usize,
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
    video_projection_renderer: &VideoProjectionRenderer,
    prepared_video_projection: Option<&PreparedVideoProjection>,
    video_projection_stats: &VideoProjectionFrameStats,
    video_projection_settings: &crate::native_renderer_options::NativeVideoProjectionSettings,
    video_projection_metadata: &VideoProjectionMetadata,
    display_composite_feedback_renderer: &DisplayCompositeFeedbackRenderer,
    prepared_display_composite_feedback: Option<&PreparedDisplayCompositeFeedback>,
    display_composite_feedback_stats: &DisplayCompositeFrameStats,
    display_composite_settings: NativeDisplayCompositeSettings,
    display_composite_projection_metadata: &DisplayCompositeProjectionMetadata,
    gpu_stimulus_volume_renderer: Option<&GpuStimulusVolumeRenderer>,
    stimulus_volume_stats: &GpuStimulusVolumeFrameStats,
    stimulus_volume_settings: NativeStimulusVolumeSettings,
    private_extension_slot_runtime: &mut PrivateExtensionSlotRuntime,
    private_extension_stats: &PrivateExtensionSlotFrameStats,
    private_layer_settings: NativePrivateLayerSettings,
    guide_blur_graph_renderer: &mut GuideBlurGraphRenderer,
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
    openxr_environment_depth_frame: Option<&OpenXrEnvironmentDepthFrame>,
    environment_depth_settings: NativeEnvironmentDepthSettings,
    environment_depth_alignment_state: &EnvironmentDepthAlignmentState,
    gpu_private_particle_renderer: Option<&GpuPrivateParticleRenderer>,
    private_particle_stats: &GpuPrivateParticleFrameStats,
    private_particle_world_center_scale: [f32; 4],
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
    let private_particle_ready = gpu_private_particle_renderer.is_some()
        && private_particle_stats.visible
        && private_particle_stats.draw_count > 0;
    let private_particle_half_res = gpu_private_particle_renderer
        .is_some_and(|renderer| renderer.half_res_offscreen_active(private_particle_stats));
    let private_particle_half_res_tracers_only =
        gpu_private_particle_renderer.is_some_and(|renderer| {
            renderer.half_res_offscreen_tracers_only_active(private_particle_stats)
        });
    if private_particle_ready {
        gpu_timestamp_tracker.write_stage_start(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleTileComposite,
        );
    } else {
        gpu_timestamp_tracker.write_disabled_stage(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleTileComposite,
        );
    }
    if private_particle_half_res {
        if !private_particle_half_res_tracers_only {
            for stage in [
                GpuTimestampStage::PrivateParticleDrawLeftEye,
                GpuTimestampStage::PrivateParticleDrawRightEye,
            ] {
                gpu_timestamp_tracker.write_disabled_stage(device, cmd, frame_slot, stage);
            }
        }
    } else {
        for stage in [
            GpuTimestampStage::PrivateParticleHalfResDrawLeftEye,
            GpuTimestampStage::PrivateParticleHalfResDrawRightEye,
            GpuTimestampStage::PrivateParticleHalfResCompositeLeftEye,
            GpuTimestampStage::PrivateParticleHalfResCompositeRightEye,
        ] {
            gpu_timestamp_tracker.write_disabled_stage(device, cmd, frame_slot, stage);
        }
        if !private_particle_ready {
            for stage in [
                GpuTimestampStage::PrivateParticleDrawLeftEye,
                GpuTimestampStage::PrivateParticleDrawRightEye,
            ] {
                gpu_timestamp_tracker.write_disabled_stage(device, cmd, frame_slot, stage);
            }
        }
    }
    for (eye_index, eye) in buffer.eyes.iter().enumerate() {
        let target_rect =
            projection_target_state.effective_rect(projection_metadata.rect_for_eye(eye_index));
        let eye_projection = views
            .get(eye_index)
            .map(hand_mesh_visual_eye_projection)
            .unwrap_or_default();
        if let Some(renderer) = gpu_private_particle_renderer {
            renderer.record_overlay_eye_sort(device, cmd, eye_projection, private_particle_stats);
        }
        if let Some(renderer) = gpu_hand_anchor_particle_renderer {
            renderer.record_sort_frame(
                device,
                cmd,
                &hand_anchor_particle_stats.primary,
                hand_anchor_particle_stats.settings,
                eye_projection,
                frame_count,
            );
        }
        if let Some(renderer) = secondary_gpu_hand_anchor_particle_renderer {
            renderer.record_sort_frame(
                device,
                cmd,
                &hand_anchor_particle_stats.secondary,
                hand_anchor_particle_stats.settings,
                eye_projection,
                frame_count,
            );
        }
        if private_particle_half_res {
            let half_res_draw_stage = match eye_index {
                0 => Some(GpuTimestampStage::PrivateParticleHalfResDrawLeftEye),
                1 => Some(GpuTimestampStage::PrivateParticleHalfResDrawRightEye),
                _ => None,
            };
            if let Some(stage) = half_res_draw_stage {
                gpu_timestamp_tracker.write_stage_start(device, cmd, frame_slot, stage);
            }
            if let Some(renderer) = gpu_private_particle_renderer {
                renderer.record_half_res_offscreen_eye(
                    device,
                    cmd,
                    image_index,
                    eye_index,
                    eye_projection,
                    private_particle_world_center_scale,
                    private_particle_stats,
                );
            }
            if let Some(stage) = half_res_draw_stage {
                gpu_timestamp_tracker.write_stage_end(device, cmd, frame_slot, stage);
            }
        }
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
        if video_projection_stats.rendered {
            if let Some(prepared) = prepared_video_projection {
                let video_target_rect = video_projection_metadata.target_rect_for_eye(eye_index);
                video_projection_renderer.record_video_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    eye_index,
                    video_projection_metadata,
                    video_target_rect,
                    video_projection_settings.opacity,
                    prepared,
                );
            }
        }
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
                    projection_settings,
                    environment_depth_settings,
                    openxr_environment_depth_frame,
                    environment_depth_alignment_state.eye_offsets(eye_index),
                );
            }
        } else if custom_stereo_projection
            && camera_output_mode.guide_projection_enabled()
            && guide_blur_stats.ready
        {
            let video_composite_rendered = if projection_settings
                .video_border_shader_composite_active()
                && video_projection_stats.rendered
            {
                if let Some(prepared_video) = prepared_video_projection {
                    let video_target_rect =
                        video_projection_metadata.target_rect_for_eye(eye_index);
                    guide_blur_graph_renderer.record_video_composite_projection_eye(
                        device,
                        cmd,
                        swapchain.extent,
                        eye_index,
                        target_rect,
                        video_target_rect,
                        projection_settings,
                        guide_blur_enabled,
                        video_projection_metadata,
                        video_projection_settings.opacity,
                        prepared_video,
                    )
                } else {
                    false
                }
            } else {
                false
            };
            if !video_composite_rendered {
                guide_blur_graph_renderer.record_projection_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    eye_index,
                    target_rect,
                    projection_settings,
                    guide_blur_enabled,
                );
            }
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
        if display_composite_feedback_stats.rendered
            && display_composite_settings.mode != NativeDisplayCompositeMode::GpuReadbackDiagnostic
        {
            if let Some(prepared) = prepared_display_composite_feedback {
                let display_base_rect = match display_composite_settings.feedback_projection {
                    NativeDisplayCompositeFeedbackProjection::MetadataTargetScreenUv => {
                        display_composite_projection_metadata.rect_for_eye(eye_index)
                    }
                    NativeDisplayCompositeFeedbackProjection::FullEyePeripheralStretch => {
                        TargetRect::UNIT
                    }
                };
                let display_target_rect = projection_target_state.effective_rect(display_base_rect);
                display_composite_feedback_renderer.record_feedback_eye(
                    device,
                    cmd,
                    swapchain.extent,
                    eye_index,
                    display_composite_projection_metadata,
                    display_target_rect,
                    prepared,
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
        if private_particle_ready {
            if let Some(renderer) = gpu_private_particle_renderer {
                if private_particle_half_res {
                    if private_particle_half_res_tracers_only {
                        let direct_draw_stage = match eye_index {
                            0 => Some(GpuTimestampStage::PrivateParticleDrawLeftEye),
                            1 => Some(GpuTimestampStage::PrivateParticleDrawRightEye),
                            _ => None,
                        };
                        if let Some(stage) = direct_draw_stage {
                            gpu_timestamp_tracker.write_stage_start(device, cmd, frame_slot, stage);
                        }
                        renderer.record_overlay_eye_main_particles(
                            device,
                            cmd,
                            swapchain.extent,
                            eye_projection,
                            private_particle_world_center_scale,
                            private_particle_stats,
                        );
                        if let Some(stage) = direct_draw_stage {
                            gpu_timestamp_tracker.write_stage_end(device, cmd, frame_slot, stage);
                        }
                    }
                    let composite_stage = match eye_index {
                        0 => Some(GpuTimestampStage::PrivateParticleHalfResCompositeLeftEye),
                        1 => Some(GpuTimestampStage::PrivateParticleHalfResCompositeRightEye),
                        _ => None,
                    };
                    if let Some(stage) = composite_stage {
                        gpu_timestamp_tracker.write_stage_start(device, cmd, frame_slot, stage);
                    }
                    renderer.record_half_res_composite_eye(
                        device,
                        cmd,
                        image_index,
                        eye_index,
                        swapchain.extent,
                        private_particle_stats,
                    );
                    if let Some(stage) = composite_stage {
                        gpu_timestamp_tracker.write_stage_end(device, cmd, frame_slot, stage);
                    }
                } else {
                    let direct_draw_stage = match eye_index {
                        0 => Some(GpuTimestampStage::PrivateParticleDrawLeftEye),
                        1 => Some(GpuTimestampStage::PrivateParticleDrawRightEye),
                        _ => None,
                    };
                    if let Some(stage) = direct_draw_stage {
                        gpu_timestamp_tracker.write_stage_start(device, cmd, frame_slot, stage);
                    }
                    renderer.record_overlay_eye(
                        device,
                        cmd,
                        swapchain.extent,
                        eye_projection,
                        private_particle_world_center_scale,
                        private_particle_stats,
                    );
                    if let Some(stage) = direct_draw_stage {
                        gpu_timestamp_tracker.write_stage_end(device, cmd, frame_slot, stage);
                    }
                }
            }
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
    if private_particle_ready {
        gpu_timestamp_tracker.write_stage_end(
            device,
            cmd,
            frame_slot,
            GpuTimestampStage::PrivateParticleTileComposite,
        );
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

fn display_composite_feedback_requested(settings: NativeDisplayCompositeSettings) -> bool {
    settings.enabled
        && settings.feedback_enabled
        && !settings.high_rate_json_payload
        && matches!(
            settings.mode,
            NativeDisplayCompositeMode::GpuFeedbackDiagnostic
                | NativeDisplayCompositeMode::GpuRecursiveFeedbackDiagnostic
                | NativeDisplayCompositeMode::GpuReadbackDiagnostic
        )
}

fn display_composite_feedback_disabled_reason(
    settings: NativeDisplayCompositeSettings,
) -> &'static str {
    if !settings.enabled {
        "display-composite-disabled"
    } else if !settings.feedback_enabled {
        "feedback-disabled"
    } else if settings.high_rate_json_payload {
        "high-rate-json-payload-forbidden"
    } else if !matches!(
        settings.mode,
        NativeDisplayCompositeMode::GpuFeedbackDiagnostic
            | NativeDisplayCompositeMode::GpuRecursiveFeedbackDiagnostic
            | NativeDisplayCompositeMode::GpuReadbackDiagnostic
    ) {
        "mode-not-gpu-feedback-diagnostic"
    } else {
        "unknown"
    }
}

fn video_projection_requested(
    settings: &crate::native_renderer_options::NativeVideoProjectionSettings,
) -> bool {
    settings.video_background_active()
}

fn video_projection_disabled_reason(
    settings: &crate::native_renderer_options::NativeVideoProjectionSettings,
) -> &'static str {
    if !settings.enabled {
        "video-projection-disabled"
    } else if settings.high_rate_json_payload {
        "high-rate-json-payload-forbidden"
    } else if settings.path.trim().is_empty() {
        "video-path-empty"
    } else if settings.remote_broker_camera_projection_active() {
        "remote-broker-camera-projection-source"
    } else {
        "unknown"
    }
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

#[derive(Clone, Copy, Debug)]
struct PrivateParticleWorldAnchor {
    center_scale: [f32; 4],
    forward_axis: [f32; 4],
    initialized: bool,
    scale_parameter_source: &'static str,
    last_scale_poll_frame: u64,
    panel_scale_override_m: Option<f32>,
}

impl PrivateParticleWorldAnchor {
    fn new() -> Self {
        Self {
            center_scale: [
                0.0,
                0.0,
                -PRIVATE_PARTICLE_WORLD_ANCHOR_DISTANCE_M,
                PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_M,
            ],
            forward_axis: [0.0, 0.0, -1.0, 1.0],
            initialized: false,
            scale_parameter_source: "particle-world-anchor-default",
            last_scale_poll_frame: u64::MAX,
            panel_scale_override_m: None,
        }
    }

    fn capture_startup_if_needed(
        &mut self,
        eye_projection: HandMeshVisualEyeProjection,
        frame_count: u64,
    ) {
        if !self.initialized {
            self.capture(eye_projection, frame_count, "startup");
        }
    }

    fn recenter(&mut self, eye_projection: HandMeshVisualEyeProjection, frame_count: u64) {
        self.capture(eye_projection, frame_count, "right-controller-primary");
    }

    fn recenter_at_headset(
        &mut self,
        eye_projection: HandMeshVisualEyeProjection,
        frame_count: u64,
        reason: &'static str,
    ) {
        self.capture_at_distance(eye_projection, frame_count, reason, 0.0);
    }

    fn world_center_scale(&self) -> [f32; 4] {
        self.center_scale
    }

    fn world_forward_axis(&self) -> [f32; 4] {
        self.forward_axis
    }

    fn scale_parameter_source(&self) -> &'static str {
        self.scale_parameter_source
    }

    fn apply_panel_scale(&mut self, scale_m: f32, frame_count: u64) -> f32 {
        let scale_m = scale_m.clamp(
            PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MIN_M,
            PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MAX_M,
        );
        self.panel_scale_override_m = Some(scale_m);
        let scale_changed = (self.center_scale[3] - scale_m).abs() > f32::EPSILON
            || self.scale_parameter_source != "same-apk-panel-live";
        self.center_scale[3] = scale_m;
        self.scale_parameter_source = "same-apk-panel-live";
        self.last_scale_poll_frame = frame_count;
        if scale_changed {
            crate::marker(
                "private-particle-anchor",
                format!(
                    "status=scale-panel-live-applied frame={} privateParticleWorldAnchorInitialized={} privateParticleWorldAnchorFollowCamera=false privateParticleWorldAnchorScaleM={:.3} privateParticleWorldAnchorScaleParameterSource={}",
                    frame_count,
                    self.initialized,
                    self.center_scale[3],
                    crate::sanitize(self.scale_parameter_source),
                ),
            );
        }
        self.center_scale[3]
    }

    fn refresh_scale_from_android_properties(&mut self, frame_count: u64) {
        let should_poll = self.last_scale_poll_frame == u64::MAX
            || frame_count.saturating_sub(self.last_scale_poll_frame)
                >= PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_POLL_INTERVAL_FRAMES;
        if !should_poll {
            return;
        }

        let (scale_m, scale_parameter_source) =
            if let Some(panel_scale_m) = self.panel_scale_override_m {
                (
                    panel_scale_m.clamp(
                        PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MIN_M,
                        PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MAX_M,
                    ),
                    "same-apk-panel-live",
                )
            } else {
                let property = private_particle_world_anchor_scale_property();
                let property_overridden = property.is_some();
                let scale_m = f32_clamped_value(
                    property,
                    PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_M,
                    PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MIN_M,
                    PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_MAX_M,
                );
                let scale_parameter_source = if property_overridden {
                    "runtime-hotload-android-property"
                } else {
                    "particle-world-anchor-default"
                };
                (scale_m, scale_parameter_source)
            };
        let scale_changed = (self.center_scale[3] - scale_m).abs() > f32::EPSILON
            || self.scale_parameter_source != scale_parameter_source;

        self.center_scale[3] = scale_m;
        self.scale_parameter_source = scale_parameter_source;
        self.last_scale_poll_frame = frame_count;

        if scale_changed {
            crate::marker(
                "private-particle-anchor",
                format!(
                    "status=scale-hotload-applied frame={} privateParticleWorldAnchorInitialized={} privateParticleWorldAnchorFollowCamera=false privateParticleWorldAnchorScaleM={:.3} privateParticleWorldAnchorScaleParameterSource={} privateParticleWorldAnchorScalePollIntervalFrames={}",
                    frame_count,
                    self.initialized,
                    self.center_scale[3],
                    crate::sanitize(self.scale_parameter_source),
                    PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_POLL_INTERVAL_FRAMES,
                ),
            );
        }
    }

    fn capture(
        &mut self,
        eye_projection: HandMeshVisualEyeProjection,
        frame_count: u64,
        reason: &'static str,
    ) {
        self.capture_at_distance(
            eye_projection,
            frame_count,
            reason,
            PRIVATE_PARTICLE_WORLD_ANCHOR_DISTANCE_M,
        );
    }

    fn capture_at_distance(
        &mut self,
        eye_projection: HandMeshVisualEyeProjection,
        frame_count: u64,
        reason: &'static str,
        distance_m: f32,
    ) {
        let forward_offset = rotate_by_quat(
            eye_projection.orientation_xyzw,
            [0.0, 0.0, -distance_m.max(0.0)],
        );
        let forward_axis = if distance_m.abs() <= f32::EPSILON {
            rotate_by_quat(eye_projection.orientation_xyzw, [0.0, 0.0, -1.0])
        } else {
            normalize3(forward_offset)
        };
        self.center_scale = [
            eye_projection.position[0] + forward_offset[0],
            eye_projection.position[1] + forward_offset[1],
            eye_projection.position[2] + forward_offset[2],
            self.center_scale[3],
        ];
        self.forward_axis = [forward_axis[0], forward_axis[1], forward_axis[2], 1.0];
        self.initialized = true;
        crate::marker(
            "private-particle-anchor",
            format!(
                "status=captured frame={} reason={} privateParticleWorldAnchorInitialized=true privateParticleWorldAnchorFollowCamera=false privateParticleWorldAnchorCenter={:.4},{:.4},{:.4} privateParticleWorldAnchorScaleM={:.3} privateParticleWorldAnchorScaleParameterSource={} privateParticleWorldAnchorScalePollIntervalFrames={} privateParticleWorldAnchorDistanceM={:.3} privateParticleWorldAnchorForwardAxis={:.4},{:.4},{:.4} privateParticleComputeFovTangentPayload=world-anchor-forward-axis",
                frame_count,
                reason,
                self.center_scale[0],
                self.center_scale[1],
                self.center_scale[2],
                self.center_scale[3],
                crate::sanitize(self.scale_parameter_source),
                PRIVATE_PARTICLE_WORLD_ANCHOR_SCALE_POLL_INTERVAL_FRAMES,
                distance_m.max(0.0),
                self.forward_axis[0],
                self.forward_axis[1],
                self.forward_axis[2],
            ),
        );
    }
}

#[cfg(target_os = "android")]
fn private_particle_world_anchor_scale_property() -> Option<String> {
    let mut property = android_properties::getprop(PROP_PRIVATE_PARTICLES_WORLD_ANCHOR_SCALE_M);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

#[cfg(not(target_os = "android"))]
fn private_particle_world_anchor_scale_property() -> Option<String> {
    None
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

fn normalize3(value: [f32; 3]) -> [f32; 3] {
    let length_sq = dot3(value, value).max(0.000000000001);
    let inv_length = 1.0 / length_sq.sqrt();
    [
        value[0] * inv_length,
        value[1] * inv_length,
        value[2] * inv_length,
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
    _foveation_profile: Option<xr::FoveationProfileFB>,
}

impl ProjectionSwapchain {
    unsafe fn destroy(self, device: &ash::Device) {
        for buffer in self.buffers {
            for eye in buffer.eyes {
                device.destroy_framebuffer(eye.framebuffer, None);
                if let Some(foveation_view) = eye.foveation_view {
                    device.destroy_image_view(foveation_view, None);
                }
                device.destroy_image_view(eye.view, None);
            }
        }
    }
}

struct NativePassthroughRuntime {
    _passthrough: xr::Passthrough,
    layer: xr::PassthroughLayerFB,
    passthrough_style_settings: NativePassthroughStyleSettings,
    audio_reactive_state: NativePassthroughStyleAudioReactiveState,
}

impl NativePassthroughRuntime {
    fn create(
        session: &xr::Session<xr::Vulkan>,
        render_mode: NativeRendererRenderMode,
        native_passthrough_requested: bool,
        fb_passthrough_enabled: bool,
        alpha_blend_supported: bool,
        passthrough_style_settings: NativePassthroughStyleSettings,
    ) -> Option<Self> {
        if !native_passthrough_requested {
            crate::marker(
                "native-passthrough",
                format!(
                    "status=disabled renderMode={} nativePassthroughRequested=false solidBlackBackground={} nativePassthroughLayerActive=false",
                    render_mode.marker_value(),
                    render_mode.uses_solid_black_background(),
                ),
            );
            crate::marker(
                "native-passthrough-style",
                format!(
                    "status=skipped reason=native-passthrough-not-requested xrPassthroughLayerSetStyleFB=false {}",
                    passthrough_style_settings.marker_fields()
                ),
            );
            return None;
        }
        if !fb_passthrough_enabled {
            crate::marker(
                "native-passthrough",
                "status=unavailable reason=XR_FB_passthrough-not-enabled nativePassthroughRequested=true fbPassthroughEnabled=false nativePassthroughLayerActive=false",
            );
            crate::marker(
                "native-passthrough-style",
                format!(
                    "status=skipped reason=XR_FB_passthrough-not-enabled xrPassthroughLayerSetStyleFB=false {}",
                    passthrough_style_settings.marker_fields()
                ),
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
        match crate::openxr_passthrough_style::apply_passthrough_layer_style(
            session,
            &layer,
            passthrough_style_settings,
        ) {
            Ok("disabled") => crate::marker(
                "native-passthrough-style",
                format!(
                    "status=disabled xrPassthroughLayerSetStyleFB=false nativePassthroughLayerActive=true {}",
                    passthrough_style_settings.marker_fields()
                ),
            ),
            Ok(applied_chain) => crate::marker(
                "native-passthrough-style",
                format!(
                    "status=applied xrPassthroughLayerSetStyleFB=true appliedStyleChain={} nativePassthroughLayerActive=true {}",
                    applied_chain,
                    passthrough_style_settings.marker_fields()
                ),
            ),
            Err(error) => crate::marker(
                "native-passthrough-style",
                format!(
                    "status=error stage=xrPassthroughLayerSetStyleFB reason={} xrPassthroughLayerSetStyleFB=false nativePassthroughLayerActive=true {}",
                    crate::sanitize(&error),
                    passthrough_style_settings.marker_fields()
                ),
            ),
        }
        crate::marker(
            "native-passthrough",
            format!(
                "status=active passthroughExtension=XR_FB_passthrough nativePassthroughLayerActive=true passthroughCompositionLayer=CompositionLayerPassthroughFB passthroughPurpose=RECONSTRUCTION environmentBlendModeAlphaSupported={} environmentBlendMode=OPAQUE projectionLayerAlphaBlend={} passthroughLayerAlphaBlend=true {}",
                alpha_blend_supported,
                render_mode.projection_layer_alpha_blend(),
                passthrough_style_settings.marker_fields(),
            ),
        );
        Some(Self {
            _passthrough: passthrough,
            layer,
            passthrough_style_settings,
            audio_reactive_state: NativePassthroughStyleAudioReactiveState::new(),
        })
    }

    fn update_audio_reactive_style(
        &mut self,
        session: &xr::Session<xr::Vulkan>,
        delta_seconds: f32,
        frame_count: u64,
    ) {
        let snapshot = self.audio_reactive_state.advance(
            self.passthrough_style_settings.audio_reactive,
            delta_seconds,
        );
        if !self
            .audio_reactive_state
            .should_update(self.passthrough_style_settings, delta_seconds)
        {
            return;
        }

        let effective_settings = self
            .passthrough_style_settings
            .with_audio_snapshot(snapshot);
        match crate::openxr_passthrough_style::apply_passthrough_layer_style(
            session,
            &self.layer,
            effective_settings,
        ) {
            Ok("disabled") => crate::marker(
                "native-passthrough-style",
                format!(
                    "status=audio-reactive-disabled frame={} xrPassthroughLayerSetStyleFB=false nativePassthroughLayerActive=true {} {}",
                    frame_count,
                    snapshot.marker_fields(),
                    effective_settings.marker_fields()
                ),
            ),
            Ok(applied_chain) => crate::marker(
                "native-passthrough-style",
                format!(
                    "status=audio-reactive-applied frame={} xrPassthroughLayerSetStyleFB=true appliedStyleChain={} nativePassthroughLayerActive=true {} {}",
                    frame_count,
                    applied_chain,
                    snapshot.marker_fields(),
                    effective_settings.marker_fields()
                ),
            ),
            Err(error) => crate::marker(
                "native-passthrough-style",
                format!(
                    "status=audio-reactive-error frame={} stage=xrPassthroughLayerSetStyleFB reason={} xrPassthroughLayerSetStyleFB=false nativePassthroughLayerActive=true {} {}",
                    frame_count,
                    crate::sanitize(&error),
                    snapshot.marker_fields(),
                    effective_settings.marker_fields()
                ),
            ),
        }
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
    foveation_view: Option<vk::ImageView>,
    framebuffer: vk::Framebuffer,
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
