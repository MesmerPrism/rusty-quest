//! Runtime property options for the native renderer.
//!
//! This module keeps Android property parsing separate from the OpenXR/Vulkan
//! frame loop so replay-proof, live-hand, and SDF visual modes stay testable.

use crate::native_renderer_property_values::{
    bool_value, f32_clamped_value, f32_pair_value, f32_value, u32_value, u64_value,
};
use crate::projection_target_state::ProjectionTargetSettings;

pub(crate) use crate::native_renderer_camera_options::{
    NativeCameraOutputMode, NativeCameraQualityProfile, NativeCameraResolutionProfile,
    NativeCameraStereoPairingPolicy, NativeCameraSyncMode, NativeCameraYcbcrMode,
    NativeGuideGraphResolution, NativeSwapchainColorFormatMode,
};
pub(crate) use crate::native_renderer_display_composite_options::NativeDisplayCompositeSettings;
#[cfg(test)]
#[allow(unused_imports)]
pub(crate) use crate::native_renderer_display_composite_options::{
    NativeDisplayCompositeFeedbackProjection, NativeDisplayCompositeMode,
    NativeDisplayCompositeSource,
};
pub(crate) use crate::native_renderer_environment_depth_options::NativeEnvironmentDepthSettings;
#[cfg(test)]
#[allow(unused_imports)]
pub(crate) use crate::native_renderer_environment_depth_options::{
    NativeEnvironmentDepthDebugView, NativeEnvironmentDepthDepthUnitsPolicy,
    NativeEnvironmentDepthLayerPolicy, NativeEnvironmentDepthMode,
    NativeEnvironmentDepthReferenceSpace, NativeEnvironmentDepthSource,
    NativeEnvironmentDepthSurfaceComponentMode, NativeEnvironmentDepthSurfaceFreeSpaceDecay,
    NativeEnvironmentDepthSurfaceModel, NativeEnvironmentDepthSurfaceNormalCoherence,
    NativeEnvironmentDepthSurfaceNormalSource, NativeEnvironmentDepthSurfaceSmallComponentPolicy,
};
pub(crate) use crate::native_renderer_hand_anchor_particle_options::NativeHandAnchorParticleSettings;
#[cfg(any(target_os = "android", test))]
#[allow(unused_imports)]
pub(crate) use crate::native_renderer_hand_anchor_particle_options::{
    NativeHandAnchorParticleDynamics, NativeHandAnchorParticleOrderingImplementation,
    NativeHandAnchorParticleOrderingMode, NativeHandAnchorParticleTransparencyBlendMode,
    NativeHandAnchorParticleTransparencyCompositionMode,
};
pub(crate) use crate::native_renderer_projection_border_stretch_options::NativeProjectionBorderStretchSettings;
#[cfg(any(target_os = "android", test))]
#[allow(unused_imports)]
pub(crate) use crate::native_renderer_projection_border_stretch_options::{
    NativePeripheralStretchBlendMode, NativePeripheralStretchDebug, NativeProjectionBorderPolicy,
    NativeProjectionBorderStretchPush, NativeProjectionProcessingLayer,
};
pub(crate) use crate::native_renderer_properties::*;
pub(crate) use crate::native_renderer_stimulus_volume_options::NativeStimulusVolumeSettings;
#[cfg(any(target_os = "android", test))]
#[allow(unused_imports)]
pub(crate) use crate::native_renderer_stimulus_volume_options::{
    NativeStimulusVolumeColorMode, NativeStimulusVolumeCompositionMode,
    NativeStimulusVolumePatternFamily, NativeStimulusVolumeProfile,
    NativeStimulusVolumeRenderTarget,
};
pub(crate) use crate::native_renderer_video_projection_options::NativeVideoProjectionSettings;
#[cfg(test)]
#[allow(unused_imports)]
pub(crate) use crate::native_renderer_video_projection_options::{
    NativeVideoProjectionSource, NativeVideoProjectionStereoLayout, NativeVideoProjectionTarget,
};
pub(crate) use crate::native_renderer_visual_options::{
    CompactHandInputSourceMode, HandMeshVisualDiagnosticSettings, NativePrivateLayerSettings,
    NativeRendererRenderMode,
};

#[derive(Clone, Debug)]
pub(crate) struct NativeRendererRuntimeOptions {
    pub(crate) render_mode: NativeRendererRenderMode,
    pub(crate) camera_output_mode: NativeCameraOutputMode,
    pub(crate) guide_blur_enabled: bool,
    pub(crate) guide_graph_resolution: NativeGuideGraphResolution,
    pub(crate) camera_ycbcr_mode: NativeCameraYcbcrMode,
    pub(crate) camera_resolution_profile: NativeCameraResolutionProfile,
    pub(crate) camera_reader_max_images: u32,
    pub(crate) camera_quality_profile: NativeCameraQualityProfile,
    pub(crate) camera_sync_mode: NativeCameraSyncMode,
    pub(crate) camera_luma_diagnostic_enabled: bool,
    pub(crate) camera_stereo_pairing_policy: NativeCameraStereoPairingPolicy,
    pub(crate) camera_direct_border_opacity: f32,
    pub(crate) swapchain_color_format_mode: NativeSwapchainColorFormatMode,
    pub(crate) replay_visual_proof_enabled: bool,
    pub(crate) compact_hand_input_source_mode: CompactHandInputSourceMode,
    pub(crate) sdf_visual_enabled: bool,
    pub(crate) sdf_update_period_frames: u64,
    pub(crate) hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings,
    pub(crate) hand_mesh_graft_copies_enabled: bool,
    pub(crate) hand_mesh_graft_copy_scale: f32,
    pub(crate) hand_mesh_real_hands_visible: bool,
    pub(crate) hand_anchor_particle_settings: NativeHandAnchorParticleSettings,
    pub(crate) environment_depth_settings: NativeEnvironmentDepthSettings,
    pub(crate) display_composite_settings: NativeDisplayCompositeSettings,
    pub(crate) video_projection_settings: NativeVideoProjectionSettings,
    pub(crate) stimulus_volume_settings: NativeStimulusVolumeSettings,
    pub(crate) projection_target_settings: ProjectionTargetSettings,
    pub(crate) projection_border_stretch_settings: NativeProjectionBorderStretchSettings,
    pub(crate) private_layer_settings: NativePrivateLayerSettings,
}

impl NativeRendererRuntimeOptions {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let render_mode = NativeRendererRenderMode::from_property(lookup(PROP_RENDER_MODE));
        let camera_output_mode =
            NativeCameraOutputMode::from_property(lookup(PROP_CAMERA_OUTPUT_MODE));
        let guide_blur_enabled = bool_value(lookup(PROP_GUIDE_BLUR_ENABLED), true);
        let guide_graph_resolution =
            NativeGuideGraphResolution::from_property(lookup(PROP_GUIDE_RESOLUTION));
        let camera_ycbcr_mode =
            NativeCameraYcbcrMode::from_property(lookup(PROP_CAMERA_YCBCR_MODE));
        let camera_resolution_profile =
            NativeCameraResolutionProfile::from_property(lookup(PROP_CAMERA_RESOLUTION_PROFILE));
        let camera_reader_max_images = u32_value(lookup(PROP_CAMERA_READER_MAX_IMAGES), 4, 3, 12);
        let camera_quality_profile =
            NativeCameraQualityProfile::from_property(lookup(PROP_CAMERA_QUALITY_PROFILE));
        let camera_sync_mode = NativeCameraSyncMode::from_property(lookup(PROP_CAMERA_SYNC_MODE));
        let camera_luma_diagnostic_enabled =
            bool_value(lookup(PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED), false);
        let camera_stereo_pairing_policy =
            NativeCameraStereoPairingPolicy::from_property(lookup(PROP_CAMERA_STEREO_PAIRING));
        let camera_direct_border_opacity =
            f32_clamped_value(lookup(PROP_CAMERA_DIRECT_BORDER_OPACITY), 0.72, 0.0, 1.0);
        let swapchain_color_format_mode =
            NativeSwapchainColorFormatMode::from_property(lookup(PROP_SWAPCHAIN_COLOR_FORMAT_MODE));
        let replay_visual_proof_enabled =
            bool_value(lookup(PROP_REPLAY_VISUAL_PROOF_ENABLED), false);
        let compact_hand_input_source_mode = CompactHandInputSourceMode::from_property(
            lookup(PROP_HAND_MESH_INPUT_SOURCE),
            replay_visual_proof_enabled,
        );
        let requested_sdf_visual =
            replay_visual_proof_enabled || bool_value(lookup(PROP_ENABLE_SDF_VISUAL), false);
        let sdf_visual_enabled = requested_sdf_visual && render_mode.allows_sdf_visual();
        let sdf_update_period_frames = u64_value(lookup(PROP_SDF_UPDATE_PERIOD_FRAMES), 2, 1, 120);
        let diagnostic_enabled = replay_visual_proof_enabled
            || bool_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED), false);
        let diagnostic_offset_uv = f32_pair_value(
            lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV),
            [0.12, -0.08],
        );
        let diagnostic_alpha = f32_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA), 0.86);
        let hand_mesh_graft_copies_enabled = render_mode.forces_graft_copies()
            || bool_value(lookup(PROP_HAND_MESH_GRAFT_COPIES_ENABLED), false);
        let hand_mesh_graft_copy_scale =
            f32_value(lookup(PROP_HAND_MESH_GRAFT_COPY_SCALE), 1.0).clamp(0.10, 2.0);
        let hand_mesh_real_hands_visible = render_mode.forces_real_hand_meshes()
            || bool_value(lookup(PROP_HAND_MESH_REAL_HANDS_VISIBLE), false);
        let hand_anchor_particle_settings =
            NativeHandAnchorParticleSettings::from_property_lookup(&mut lookup);
        let environment_depth_settings =
            NativeEnvironmentDepthSettings::from_property_lookup(&mut lookup);
        let display_composite_settings =
            NativeDisplayCompositeSettings::from_property_lookup(&mut lookup);
        let video_projection_settings =
            NativeVideoProjectionSettings::from_property_lookup(&mut lookup);
        let stimulus_volume_settings =
            NativeStimulusVolumeSettings::from_property_lookup(&mut lookup, render_mode);
        let projection_target_settings = if render_mode.uses_stimulus_volume() {
            ProjectionTargetSettings::disabled_for_volume_only_route()
        } else {
            ProjectionTargetSettings::from_property_lookup(&mut lookup)
        };
        let projection_border_stretch_settings =
            NativeProjectionBorderStretchSettings::from_property_lookup(&mut lookup);
        let private_layer_settings = NativePrivateLayerSettings::from_property_lookup(&mut lookup);

        Self {
            render_mode,
            camera_output_mode,
            guide_blur_enabled,
            guide_graph_resolution,
            camera_ycbcr_mode,
            camera_resolution_profile,
            camera_reader_max_images,
            camera_quality_profile,
            camera_sync_mode,
            camera_luma_diagnostic_enabled,
            camera_stereo_pairing_policy,
            camera_direct_border_opacity,
            swapchain_color_format_mode,
            replay_visual_proof_enabled,
            compact_hand_input_source_mode,
            sdf_visual_enabled,
            sdf_update_period_frames,
            hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings::new(
                diagnostic_enabled,
                diagnostic_offset_uv,
                diagnostic_alpha,
            ),
            hand_mesh_graft_copies_enabled,
            hand_mesh_graft_copy_scale,
            hand_mesh_real_hands_visible,
            hand_anchor_particle_settings,
            environment_depth_settings,
            display_composite_settings,
            video_projection_settings,
            stimulus_volume_settings,
            projection_target_settings,
            projection_border_stretch_settings,
            private_layer_settings,
        }
    }

    #[cfg(target_os = "android")]
    pub(crate) fn load_from_android_properties() -> Self {
        Self::from_property_lookup(android_property)
    }
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}
