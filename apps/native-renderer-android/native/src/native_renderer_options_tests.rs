#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::native_renderer_options::{
        CompactHandInputSourceMode, NativeCameraOutputMode, NativeCameraQualityProfile,
        NativeCameraResolutionProfile, NativeCameraStereoPairingPolicy, NativeCameraSyncMode,
        NativeCameraYcbcrMode, NativeDisplayCompositeFeedbackProjection,
        NativeDisplayCompositeMode, NativeDisplayCompositeSource, NativeEnvironmentDepthDebugView,
        NativeEnvironmentDepthDepthUnitsPolicy, NativeEnvironmentDepthLayerPolicy,
        NativeEnvironmentDepthMode, NativeEnvironmentDepthReferenceSpace,
        NativeEnvironmentDepthSource, NativeEnvironmentDepthSurfaceComponentMode,
        NativeEnvironmentDepthSurfaceFreeSpaceDecay, NativeEnvironmentDepthSurfaceModel,
        NativeEnvironmentDepthSurfaceNormalCoherence, NativeEnvironmentDepthSurfaceNormalSource,
        NativeEnvironmentDepthSurfaceSmallComponentPolicy, NativeGuideGraphResolution,
        NativePassthroughStyleMode, NativeRendererRuntimeOptions, NativeSwapchainColorFormatMode,
        NativeVideoBorderBlendMode, NativeVideoProjectionSource, NativeVideoProjectionStereoLayout,
        NativeVideoProjectionTarget, PROP_CAMERA_DIRECT_BORDER_OPACITY,
        PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED, PROP_CAMERA_OUTPUT_MODE, PROP_CAMERA_QUALITY_PROFILE,
        PROP_CAMERA_READER_MAX_IMAGES, PROP_CAMERA_RESOLUTION_PROFILE, PROP_CAMERA_STEREO_PAIRING,
        PROP_CAMERA_SYNC_MODE, PROP_CAMERA_YCBCR_MODE, PROP_DISPLAY_COMPOSITE_ENABLED,
        PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED, PROP_DISPLAY_COMPOSITE_FEEDBACK_PROJECTION,
        PROP_DISPLAY_COMPOSITE_FPS_CAP, PROP_DISPLAY_COMPOSITE_HEIGHT,
        PROP_DISPLAY_COMPOSITE_HIGH_RATE_JSON_PAYLOAD, PROP_DISPLAY_COMPOSITE_MAX_IMAGES,
        PROP_DISPLAY_COMPOSITE_MODE, PROP_DISPLAY_COMPOSITE_SOURCE, PROP_DISPLAY_COMPOSITE_WIDTH,
        PROP_ENABLE_SDF_VISUAL, PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW,
        PROP_ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY, PROP_ENVIRONMENT_DEPTH_FAR_M,
        PROP_ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED, PROP_ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD,
        PROP_ENVIRONMENT_DEPTH_LAYER_POLICY, PROP_ENVIRONMENT_DEPTH_MODE,
        PROP_ENVIRONMENT_DEPTH_NEAR_M, PROP_ENVIRONMENT_DEPTH_PARTICLE_CAPACITY,
        PROP_ENVIRONMENT_DEPTH_REFERENCE_SPACE, PROP_ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS,
        PROP_ENVIRONMENT_DEPTH_SOURCE, PROP_ENVIRONMENT_DEPTH_SURFACE_MODEL,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY, PROP_GUIDE_BLUR_ENABLED,
        PROP_GUIDE_RESOLUTION, PROP_HAND_ANCHOR_PARTICLES_DYNAMICS,
        PROP_HAND_ANCHOR_PARTICLES_ENABLED, PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE, PROP_HAND_ANCHOR_PARTICLES_PER_HAND,
        PROP_HAND_ANCHOR_PARTICLES_RADIUS_M, PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
        PROP_HAND_MESH_GRAFT_COPIES_ENABLED, PROP_HAND_MESH_GRAFT_COPY_SCALE,
        PROP_HAND_MESH_INPUT_SOURCE, PROP_HAND_MESH_REAL_HANDS_VISIBLE,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, PROP_PASSTHROUGH_STYLE_BRIGHTNESS,
        PROP_PASSTHROUGH_STYLE_COLOR_AMPLITUDE, PROP_PASSTHROUGH_STYLE_COLOR_PHASE,
        PROP_PASSTHROUGH_STYLE_CONTRAST, PROP_PASSTHROUGH_STYLE_EDGE_COLOR_A,
        PROP_PASSTHROUGH_STYLE_EDGE_COLOR_B, PROP_PASSTHROUGH_STYLE_EDGE_COLOR_G,
        PROP_PASSTHROUGH_STYLE_EDGE_COLOR_R, PROP_PASSTHROUGH_STYLE_MODE,
        PROP_PASSTHROUGH_STYLE_OPACITY, PROP_PASSTHROUGH_STYLE_SATURATION,
        PROP_PERIPHERAL_STRETCH_BLEND_MODE, PROP_PERIPHERAL_STRETCH_CORE_SCALE,
        PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV, PROP_PERIPHERAL_STRETCH_MAX_INSET_UV,
        PROP_PROCESSING_LAYER, PROP_PROJECTION_BORDER_OPACITY, PROP_PROJECTION_BORDER_POLICY,
        PROP_RENDER_MODE, PROP_REPLAY_VISUAL_PROOF_ENABLED, PROP_SDF_UPDATE_PERIOD_FRAMES,
        PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION, PROP_STIMULUS_VOLUME_COMPOSITION,
        PROP_STIMULUS_VOLUME_ENABLED, PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING,
        PROP_STIMULUS_VOLUME_PATTERN_FAMILY, PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED,
        PROP_STIMULUS_VOLUME_RANDOMIZE_MAX_HZ, PROP_STIMULUS_VOLUME_RANDOMIZE_MIN_HZ,
        PROP_STIMULUS_VOLUME_RAYMARCH_SAMPLES, PROP_STIMULUS_VOLUME_RENDER_TARGET,
        PROP_STIMULUS_VOLUME_SAFETY_ACK, PROP_SWAPCHAIN_COLOR_FORMAT_MODE,
        PROP_VIDEO_BORDER_BLEND_MODE, PROP_VIDEO_PROJECTION_ENABLED, PROP_VIDEO_PROJECTION_FPS_CAP,
        PROP_VIDEO_PROJECTION_HEIGHT, PROP_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD,
        PROP_VIDEO_PROJECTION_LOOPING, PROP_VIDEO_PROJECTION_MAX_IMAGES,
        PROP_VIDEO_PROJECTION_OPACITY, PROP_VIDEO_PROJECTION_PATH, PROP_VIDEO_PROJECTION_SOURCE,
        PROP_VIDEO_PROJECTION_STEREO_LAYOUT, PROP_VIDEO_PROJECTION_TARGET,
        PROP_VIDEO_PROJECTION_WIDTH,
    };
    use crate::native_renderer_stimulus_volume_options::{
        NativeStimulusVolumeCompositionMode, NativeStimulusVolumePatternFamily,
        NativeStimulusVolumeRenderTarget,
    };
    use crate::projection_target_state::{
        BreathBridgeMode, PROP_PROJECTION_TARGET_BREATH_BRIDGE_MODE,
        PROP_PROJECTION_TARGET_CONTROLS, PROP_PROJECTION_TARGET_JOYSTICK_CONTROLS,
        PROP_PROJECTION_TARGET_SCALE, PROP_PROJECTION_TARGET_TUNED_MAX_SCALE,
    };

    fn options_from(values: &[(&str, &str)]) -> NativeRendererRuntimeOptions {
        let values = values.iter().copied().collect::<BTreeMap<_, _>>();
        NativeRendererRuntimeOptions::from_property_lookup(|name| {
            values.get(name).map(|value| (*value).to_owned())
        })
    }

    #[test]
    fn replay_visual_proof_forces_recorded_diagnostic_and_sdf() {
        let options = options_from(&[(PROP_REPLAY_VISUAL_PROOF_ENABLED, "true")]);
        assert!(options.replay_visual_proof_enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::RecordedReplay
        );
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert_eq!(
            options.render_mode.marker_value(),
            "custom-stereo-projection"
        );
        assert_eq!(options.hand_mesh_graft_copy_scale, 1.0);
        assert!(!options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn explicit_live_source_overrides_replay_proof_source_selection() {
        let options = options_from(&[
            (PROP_REPLAY_VISUAL_PROOF_ENABLED, "true"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
        ]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn projection_target_settings_load_from_native_renderer_properties() {
        let options = options_from(&[
            (PROP_PROJECTION_TARGET_CONTROLS, "true"),
            (PROP_PROJECTION_TARGET_JOYSTICK_CONTROLS, "true"),
            (PROP_PROJECTION_TARGET_SCALE, "1.0"),
            (PROP_PROJECTION_TARGET_TUNED_MAX_SCALE, "1.35"),
            (PROP_PROJECTION_TARGET_BREATH_BRIDGE_MODE, "manifold-state"),
        ]);
        assert!(options.projection_target_settings.controls_enabled);
        assert!(options.projection_target_settings.joystick_controls_enabled);
        assert_eq!(
            options.projection_target_settings.breath_bridge_mode,
            BreathBridgeMode::ManifoldState
        );
        assert!(options
            .projection_target_settings
            .marker_fields()
            .contains("projectionTargetTunedMaxScale=1.3500"));
    }

    #[test]
    fn stimulus_volume_render_mode_suppresses_stale_projection_target_controls() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "solid-black-stimulus-volume"),
            (PROP_STIMULUS_VOLUME_ENABLED, "true"),
            (PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED, "true"),
            (PROP_PROJECTION_TARGET_CONTROLS, "true"),
            (PROP_PROJECTION_TARGET_JOYSTICK_CONTROLS, "true"),
            (PROP_PROJECTION_TARGET_SCALE, "1.0"),
            (PROP_PROJECTION_TARGET_TUNED_MAX_SCALE, "1.35"),
            (PROP_PROJECTION_TARGET_BREATH_BRIDGE_MODE, "manifold-state"),
        ]);

        assert_eq!(
            options.render_mode.marker_value(),
            "solid-black-stimulus-volume"
        );
        assert!(options.stimulus_volume_settings.enabled);
        assert!(options.stimulus_volume_settings.randomize_enabled);
        assert!(!options.projection_target_settings.controls_enabled);
        assert!(!options.projection_target_settings.joystick_controls_enabled);
        assert_eq!(
            options.projection_target_settings.breath_bridge_mode,
            BreathBridgeMode::Disabled
        );
        assert!(options
            .projection_target_settings
            .marker_fields()
            .contains("projectionTargetControlsEnabled=false"));
    }

    #[test]
    fn canonical_live_source_value_selects_live_without_replay_fallback() {
        let options = options_from(&[(
            PROP_HAND_MESH_INPUT_SOURCE,
            "live-meta-openxr-hand-tracking",
        )]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn auto_mode_defaults_to_recorded_fallback_without_diagnostics() {
        let options = options_from(&[(PROP_HAND_MESH_INPUT_SOURCE, "auto")]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Auto
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn disabled_hand_input_source_selects_no_hand_frames() {
        let options = options_from(&[(PROP_HAND_MESH_INPUT_SOURCE, "disabled")]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Disabled
        );
        assert!(!options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert_eq!(
            options.compact_hand_input_source_mode.marker_value(),
            "disabled"
        );
    }

    #[test]
    fn camera_output_mode_defaults_to_auto_and_parses_diagnostics() {
        let options = options_from(&[]);
        assert_eq!(options.camera_output_mode, NativeCameraOutputMode::Auto);
        assert!(options.guide_blur_enabled);
        assert_eq!(
            options.guide_graph_resolution,
            NativeGuideGraphResolution::Low384
        );
        assert_eq!(options.camera_output_mode.marker_value(), "auto");
        assert!(options.camera_output_mode.camera_import_enabled());
        assert!(options
            .camera_output_mode
            .private_layer_projection_enabled());
        assert!(options.camera_output_mode.guide_projection_enabled());
        assert_eq!(
            options.camera_ycbcr_mode,
            NativeCameraYcbcrMode::AndroidSuggested
        );
        assert_eq!(
            options.camera_resolution_profile,
            NativeCameraResolutionProfile::Square1280
        );
        assert_eq!(
            options.camera_resolution_profile.marker_value(),
            "1280x1280"
        );
        assert_eq!(options.camera_reader_max_images, 4);
        assert_eq!(
            options.camera_quality_profile,
            NativeCameraQualityProfile::DirectBaseline
        );
        assert_eq!(
            options.camera_sync_mode,
            NativeCameraSyncMode::EarlyDeleteAhbRetained
        );
        assert!(!options.camera_luma_diagnostic_enabled);
        assert_eq!(
            options.camera_stereo_pairing_policy,
            NativeCameraStereoPairingPolicy::LatestLatest
        );
        assert_eq!(
            options.swapchain_color_format_mode,
            NativeSwapchainColorFormatMode::Auto
        );
        assert_eq!(options.camera_direct_border_opacity, 0.72);

        let direct = options_from(&[
            (PROP_CAMERA_OUTPUT_MODE, "raw_hwb"),
            (PROP_GUIDE_BLUR_ENABLED, "false"),
            (PROP_GUIDE_RESOLUTION, "camera-native"),
            (PROP_CAMERA_YCBCR_MODE, "cpuyuv-reference"),
            (PROP_CAMERA_RESOLUTION_PROFILE, "1280x960"),
            (PROP_CAMERA_READER_MAX_IMAGES, "8"),
            (PROP_CAMERA_QUALITY_PROFILE, "low-noise-30"),
            (PROP_CAMERA_SYNC_MODE, "delete-async"),
            (PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED, "true"),
            (PROP_CAMERA_STEREO_PAIRING, "nearest-timestamp"),
            (PROP_SWAPCHAIN_COLOR_FORMAT_MODE, "unorm"),
            (PROP_CAMERA_DIRECT_BORDER_OPACITY, "0"),
        ]);
        assert_eq!(direct.camera_output_mode, NativeCameraOutputMode::DirectHwb);
        assert!(!direct.guide_blur_enabled);
        assert_eq!(
            direct.guide_graph_resolution,
            NativeGuideGraphResolution::Camera1280
        );
        assert_eq!(direct.guide_graph_resolution.extent(), [1280, 1280]);
        assert!(direct.camera_output_mode.camera_import_enabled());
        assert!(direct.camera_output_mode.direct_hwb_forced());
        assert!(!direct.camera_output_mode.private_layer_projection_enabled());
        assert!(!direct.camera_output_mode.guide_graph_processing_enabled());
        assert_eq!(
            direct.camera_ycbcr_mode,
            NativeCameraYcbcrMode::ForcedBt601Narrow
        );
        assert_eq!(
            direct.camera_ycbcr_mode.conversion_mode(),
            "forced-bt601-limited-cpuyuv-reference"
        );
        assert_eq!(
            direct.camera_resolution_profile,
            NativeCameraResolutionProfile::Wide1280x960
        );
        assert_eq!(
            direct.camera_resolution_profile.requested_size(),
            Some([1280, 960])
        );
        assert_eq!(direct.camera_reader_max_images, 8);
        assert_eq!(
            direct.camera_quality_profile,
            NativeCameraQualityProfile::DirectLowNoise30
        );
        assert_eq!(
            direct.camera_quality_profile.marker_value(),
            "direct-low-noise-30"
        );
        let record_template = options_from(&[(PROP_CAMERA_QUALITY_PROFILE, "record-low-noise-30")]);
        assert_eq!(
            record_template.camera_quality_profile,
            NativeCameraQualityProfile::DirectLowNoiseRecord30
        );
        assert_eq!(
            record_template.camera_quality_profile.marker_value(),
            "direct-low-noise-record-30"
        );
        assert_eq!(
            direct.camera_sync_mode,
            NativeCameraSyncMode::DeleteAsyncReleaseFence
        );
        assert_eq!(
            direct.camera_sync_mode.marker_value(),
            "delete-async-release-fence"
        );
        assert_eq!(
            direct.camera_sync_mode.active_marker_value(),
            "delete-async-release-fence"
        );
        assert_eq!(
            direct.camera_sync_mode.implementation_status(),
            "active-diagnostic-sync-fd-observed-vulkan-semaphore-pending"
        );
        assert!(direct.camera_luma_diagnostic_enabled);
        assert_eq!(
            direct.camera_stereo_pairing_policy,
            NativeCameraStereoPairingPolicy::NearestTimestamp
        );
        assert_eq!(
            direct.camera_stereo_pairing_policy.marker_value(),
            "nearest-timestamp"
        );
        assert_eq!(
            direct.swapchain_color_format_mode,
            NativeSwapchainColorFormatMode::Unorm
        );
        assert_eq!(direct.camera_direct_border_opacity, 0.0);

        let hold_sync = options_from(&[
            (PROP_CAMERA_RESOLUTION_PROFILE, "closest"),
            (PROP_CAMERA_READER_MAX_IMAGES, "99"),
            (PROP_CAMERA_SYNC_MODE, "hold-image"),
        ]);
        assert_eq!(
            hold_sync.camera_resolution_profile,
            NativeCameraResolutionProfile::ClosestSupported
        );
        assert_eq!(
            hold_sync.camera_sync_mode,
            NativeCameraSyncMode::HoldImageUntilGpuFence
        );
        assert_eq!(
            hold_sync.camera_sync_mode.active_marker_value(),
            "hold-image-until-gpu-fence"
        );
        assert_eq!(
            hold_sync.camera_sync_mode.implementation_status(),
            "active-diagnostic"
        );
        assert_eq!(hold_sync.camera_reader_max_images, 12);

        let guide = options_from(&[(PROP_CAMERA_OUTPUT_MODE, "public-guide")]);
        assert_eq!(
            guide.camera_output_mode,
            NativeCameraOutputMode::GuidePublic
        );
        assert!(guide.camera_output_mode.guide_projection_enabled());
        assert!(!guide.camera_output_mode.private_layer_projection_enabled());

        let disabled = options_from(&[(PROP_CAMERA_OUTPUT_MODE, "off")]);
        assert_eq!(
            disabled.camera_output_mode,
            NativeCameraOutputMode::Disabled
        );
        assert!(!disabled.camera_output_mode.camera_import_enabled());
    }

    #[test]
    fn sdf_and_diagnostic_values_parse_and_clamp() {
        let options = options_from(&[
            (PROP_ENABLE_SDF_VISUAL, "on"),
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "999"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED, "yes"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "9.0,-9.0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "4.0"),
            (PROP_HAND_MESH_GRAFT_COPIES_ENABLED, "on"),
        ]);
        assert!(options.sdf_visual_enabled);
        assert_eq!(options.sdf_update_period_frames, 120);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.45, -0.45]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 1.0);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn native_passthrough_graft_only_forces_grafts_and_disables_sdf_visual() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-graft-only"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "native-passthrough-graft-only"
        );
        assert!(options.render_mode.uses_native_passthrough());
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.sdf_visual_enabled);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert_eq!(options.hand_mesh_graft_copy_scale, 0.85);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn native_passthrough_media_only_keeps_visual_extras_explicitly_disabled() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-media-only"),
            (PROP_CAMERA_OUTPUT_MODE, "disabled"),
            (PROP_GUIDE_BLUR_ENABLED, "false"),
            (PROP_HAND_MESH_INPUT_SOURCE, "disabled"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPIES_ENABLED, "false"),
            (PROP_HAND_MESH_REAL_HANDS_VISIBLE, "false"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "native-passthrough-media-only"
        );
        assert!(options.render_mode.uses_native_passthrough());
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-native-passthrough-media-only"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-native-passthrough-media-only"
        );
        assert_eq!(options.camera_output_mode, NativeCameraOutputMode::Disabled);
        assert!(!options.guide_blur_enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Disabled
        );
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn native_passthrough_style_only_is_isolated_from_visual_extras() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-style-only"),
            (PROP_CAMERA_OUTPUT_MODE, "disabled"),
            (PROP_GUIDE_BLUR_ENABLED, "false"),
            (PROP_HAND_MESH_INPUT_SOURCE, "disabled"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPIES_ENABLED, "false"),
            (PROP_HAND_MESH_REAL_HANDS_VISIBLE, "false"),
            (PROP_PASSTHROUGH_STYLE_MODE, "mono-to-rgba"),
            (PROP_PASSTHROUGH_STYLE_OPACITY, "0.82"),
            (PROP_PASSTHROUGH_STYLE_EDGE_COLOR_R, "0.1"),
            (PROP_PASSTHROUGH_STYLE_EDGE_COLOR_G, "0.9"),
            (PROP_PASSTHROUGH_STYLE_EDGE_COLOR_B, "1.0"),
            (PROP_PASSTHROUGH_STYLE_EDGE_COLOR_A, "0.35"),
            (PROP_PASSTHROUGH_STYLE_COLOR_PHASE, "1.18"),
            (PROP_PASSTHROUGH_STYLE_COLOR_AMPLITUDE, "0.75"),
        ]);

        assert_eq!(
            options.render_mode.marker_value(),
            "native-passthrough-style-only"
        );
        assert!(options.render_mode.uses_native_passthrough());
        assert!(options.render_mode.projection_layer_alpha_blend());
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-native-passthrough-style-only"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-native-passthrough-style-only"
        );
        assert_eq!(options.camera_output_mode, NativeCameraOutputMode::Disabled);
        assert!(!options.guide_blur_enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Disabled
        );
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert!(!options.hand_mesh_real_hands_visible);
        assert_eq!(
            options.passthrough_style_settings.mode,
            NativePassthroughStyleMode::MonoToRgba
        );
        assert_eq!(options.passthrough_style_settings.opacity, 0.82);
        assert_eq!(
            options.passthrough_style_settings.edge_color_rgba,
            [0.1, 0.9, 1.0, 0.35]
        );
        assert!((options.passthrough_style_settings.color_phase - 0.18).abs() < 0.0001);
        assert_eq!(options.passthrough_style_settings.color_amplitude, 0.75);
        assert!(options
            .passthrough_style_settings
            .marker_fields()
            .contains("passthroughStyleExtensionChain=PassthroughColorMapMonoToRgbaFB"));
    }

    #[test]
    fn native_passthrough_bcs_style_values_parse_separately_from_color_map() {
        let options = options_from(&[
            (PROP_PASSTHROUGH_STYLE_MODE, "bcs"),
            (PROP_PASSTHROUGH_STYLE_BRIGHTNESS, "-0.35"),
            (PROP_PASSTHROUGH_STYLE_CONTRAST, "1.65"),
            (PROP_PASSTHROUGH_STYLE_SATURATION, "0.45"),
        ]);
        let settings = options.passthrough_style_settings;

        assert_eq!(
            settings.mode,
            NativePassthroughStyleMode::BrightnessContrastSaturation
        );
        assert_eq!(settings.brightness, -0.35);
        assert_eq!(settings.contrast, 1.65);
        assert_eq!(settings.saturation, 0.45);
        assert!(settings
            .marker_fields()
            .contains("passthroughStyleSingleExtensionChain=true"));
    }

    #[test]
    fn native_passthrough_real_hand_mesh_visibility_is_explicit() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-graft-only"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_HAND_MESH_REAL_HANDS_VISIBLE, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert!(options.render_mode.uses_native_passthrough());
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(options.hand_mesh_real_hands_visible);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
    }

    #[test]
    fn solid_black_hands_and_grafts_forces_hand_visuals_without_camera_or_sdf() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "solid-black-hands-and-grafts"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "solid-black-hands-and-grafts"
        );
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.render_mode.uses_native_passthrough());
        assert!(options.render_mode.uses_solid_black_background());
        assert!(!options.sdf_visual_enabled);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(options.hand_mesh_real_hands_visible);
        assert_eq!(options.hand_mesh_graft_copy_scale, 0.85);
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-solid-black-hands-and-grafts"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-solid-black-hands-and-grafts"
        );
    }

    #[test]
    fn solid_black_openxr_hands_anchor_particles_keeps_custom_mesh_visual_off() {
        let options = options_from(&[
            (
                PROP_RENDER_MODE,
                "solid-black-openxr-hands-anchor-particles",
            ),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPIES_ENABLED, "false"),
            (PROP_HAND_MESH_REAL_HANDS_VISIBLE, "false"),
            (PROP_HAND_ANCHOR_PARTICLES_ENABLED, "true"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "solid-black-openxr-hands-anchor-particles"
        );
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.render_mode.uses_native_passthrough());
        assert!(options.render_mode.uses_solid_black_background());
        assert!(options.render_mode.requests_openxr_default_hand_visual());
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert!(!options.hand_mesh_real_hands_visible);
        assert!(options.hand_anchor_particle_settings.enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-solid-black-openxr-hands-anchor-particles"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-solid-black-openxr-hands-anchor-particles"
        );
    }

    #[test]
    fn native_passthrough_stimulus_volume_enables_opaque_volume_route() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-stimulus-volume"),
            (PROP_STIMULUS_VOLUME_ENABLED, "true"),
            (PROP_STIMULUS_VOLUME_SAFETY_ACK, "true"),
        ]);
        let settings = options.stimulus_volume_settings;

        assert_eq!(
            options.render_mode.marker_value(),
            "native-passthrough-stimulus-volume"
        );
        assert!(options.render_mode.uses_native_passthrough());
        assert!(options.render_mode.uses_stimulus_volume());
        assert!(!options.render_mode.projection_layer_alpha_blend());
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.sdf_visual_enabled);
        assert!(settings.enabled);
        assert!(settings.active());
        assert_eq!(settings.raymarch_samples, 6);
        assert_eq!(settings.central_fov_fraction, 0.78);
        assert_eq!(settings.gradient_smoothing, 0.65);
        assert_eq!(
            settings.pattern_family,
            NativeStimulusVolumePatternFamily::RandomizedTrevorVocabulary
        );
        assert_eq!(settings.randomize_min_hz, 3.0);
        assert_eq!(settings.randomize_max_hz, 40.0);
        assert_eq!(
            settings.composition,
            NativeStimulusVolumeCompositionMode::OpaqueBlackProjection
        );
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-native-passthrough-stimulus-volume"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-native-passthrough-stimulus-volume"
        );

        let fields = settings.marker_fields();
        assert!(fields.contains("renderPath=native-vulkan-stimulus-volume"));
        assert!(fields.contains("makepadRuntime=false"));
        assert!(fields.contains("hostessRuntime=false"));
        assert!(fields.contains("volumeOnly=true"));
        assert!(fields.contains("volumeColorMode=DepthRamp"));
        assert!(fields.contains("volumeCompositing=opaque-black-projection"));
        assert!(fields.contains("volumeResolutionTier=baseline-512"));
        assert!(fields.contains("volumeCentralFovFraction=0.78"));
        assert!(fields.contains("volumeGradientSmoothing=0.65"));
        assert!(
            fields.contains("volumePatternVocabulary=trevor-hewitt-inspired-browser-portable-v1")
        );
        assert!(fields.contains("volumePatternFamily=randomized-trevor-vocabulary"));
        assert!(fields.contains("randomizeHzRange=3.000-40.000"));
        assert!(fields.contains("stimulusSafetyAcknowledged=true"));
    }

    #[test]
    fn solid_black_stimulus_volume_keeps_passthrough_disabled() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "solid-black-stimulus-volume"),
            (PROP_STIMULUS_VOLUME_ENABLED, "true"),
            (PROP_STIMULUS_VOLUME_SAFETY_ACK, "true"),
            (PROP_STIMULUS_VOLUME_RENDER_TARGET, "1024x1024x2-rgba16f"),
            (PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION, "0.72"),
            (PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING, "0.80"),
            (PROP_STIMULUS_VOLUME_PATTERN_FAMILY, "spiral"),
            (
                PROP_STIMULUS_VOLUME_COMPOSITION,
                "alpha-over-native-passthrough",
            ),
        ]);
        let settings = options.stimulus_volume_settings;

        assert_eq!(
            options.render_mode.marker_value(),
            "solid-black-stimulus-volume"
        );
        assert!(options.render_mode.uses_solid_black_background());
        assert!(options.render_mode.uses_stimulus_volume());
        assert!(!options.render_mode.uses_native_passthrough());
        assert_eq!(
            settings.render_target,
            NativeStimulusVolumeRenderTarget::Rgba16f1024Stereo
        );
        assert_eq!(settings.render_target.extent(), [1024, 1024]);
        assert_eq!(
            settings.render_target.resolution_tier_marker(),
            "limit-1024"
        );
        assert_eq!(settings.central_fov_fraction, 0.72);
        assert_eq!(settings.gradient_smoothing, 0.80);
        assert_eq!(
            settings.pattern_family,
            NativeStimulusVolumePatternFamily::Spiral
        );
        assert_eq!(
            settings.composition,
            NativeStimulusVolumeCompositionMode::AlphaOverNativePassthrough
        );
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-solid-black-stimulus-volume"
        );
    }

    #[test]
    fn stimulus_volume_safety_gate_and_randomize_values_clamp() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-stimulus-volume"),
            (PROP_STIMULUS_VOLUME_ENABLED, "true"),
            (PROP_STIMULUS_VOLUME_RAYMARCH_SAMPLES, "99"),
            (PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION, "9.0"),
            (PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING, "-2"),
            (PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED, "false"),
            (PROP_STIMULUS_VOLUME_RANDOMIZE_MIN_HZ, "-2"),
            (PROP_STIMULUS_VOLUME_RANDOMIZE_MAX_HZ, "99"),
        ]);
        let settings = options.stimulus_volume_settings;

        assert!(settings.enabled);
        assert!(!settings.active());
        assert_eq!(settings.raymarch_samples, 48);
        assert_eq!(settings.central_fov_fraction, 1.0);
        assert_eq!(settings.gradient_smoothing, 0.0);
        assert!(!settings.randomize_enabled);
        assert_eq!(settings.randomize_min_hz, 3.0);
        assert_eq!(settings.randomize_max_hz, 40.0);
        let fields = settings.marker_fields();
        assert!(fields.contains("stimulusSafetyAcknowledged=false"));
        assert!(fields.contains("stimulusSafetyGate=render-black-until-safety-ack"));
        assert!(fields.contains("stimulusMaxCycleHz=40.000"));
    }

    #[test]
    fn invalid_values_keep_defaults() {
        let options = options_from(&[
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "bad"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "bad"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "bad"),
        ]);
        assert_eq!(options.sdf_update_period_frames, 2);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.12, -0.08]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 0.86);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert_eq!(options.hand_mesh_graft_copy_scale, 1.0);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn hand_anchor_particle_settings_parse_and_clamp() {
        let options = options_from(&[
            (PROP_HAND_ANCHOR_PARTICLES_ENABLED, "on"),
            (PROP_HAND_ANCHOR_PARTICLES_PER_HAND, "99999"),
            (PROP_HAND_ANCHOR_PARTICLES_RADIUS_M, "0.2"),
            (PROP_HAND_ANCHOR_PARTICLES_DYNAMICS, "private-gpu-payload"),
            (
                PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE,
                "legacy-additive-multiply",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
                "approximate-depth-suppressed",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
                "99",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE,
                "main-and-cpu-tracers-back-to-front",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION,
                "gpu-index-remap",
            ),
            (PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES, "99"),
        ]);

        assert!(options.hand_anchor_particle_settings.enabled);
        assert_eq!(
            options.hand_anchor_particle_settings.particles_per_hand,
            4096
        );
        assert_eq!(options.hand_anchor_particle_settings.radius_m, 0.040);
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .transparency_blend_mode
                .marker_value(),
            "legacy-additive-multiply"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .transparency_composition_mode
                .marker_value(),
            "approximate-depth-suppressed"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .transparency_depth_suppression_strength,
            8.0
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .ordering_mode
                .marker_value(),
            "per-particle-back-to-front"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .ordering_implementation
                .marker_value(),
            "gpu-index-remap"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .ordering_interval_frames,
            8
        );
        assert!(options
            .hand_anchor_particle_settings
            .private_gpu_payload_requested());
        let fields = options.hand_anchor_particle_settings.marker_fields();
        assert!(fields.contains("handAnchorParticleDynamics=private-gpu-payload"));
        assert!(
            fields.contains("handAnchorParticleOrderingStatus=resident-gpu-index-remap-requested")
        );
        assert!(fields.contains("handAnchorParticleOrderingCpuExpandedUploadPerFrame=false"));
        assert!(fields.contains("handAnchorParticleCoordinateSpace=openxr-reference-space"));
        assert!(fields.contains("handAnchorParticleCpuExpandedUploadPerFrame=false"));
        assert!(options
            .hand_anchor_particle_settings
            .resident_gpu_particle_sort_requested());
    }

    #[test]
    fn environment_depth_settings_default_disabled_status_surface() {
        let options = options_from(&[]);
        let settings = options.environment_depth_settings;

        assert_eq!(settings.mode, NativeEnvironmentDepthMode::Disabled);
        assert_eq!(
            settings.source,
            NativeEnvironmentDepthSource::RuntimeProvider
        );
        assert_eq!(
            settings.layer_policy,
            NativeEnvironmentDepthLayerPolicy::MonoLayer0
        );
        assert_eq!(
            settings.depth_units_policy,
            NativeEnvironmentDepthDepthUnitsPolicy::ProjectedDepthFromNearFar
        );
        assert_eq!(settings.debug_view, NativeEnvironmentDepthDebugView::Normal);
        assert_eq!(
            settings.reference_space,
            NativeEnvironmentDepthReferenceSpace::OpenXrLocal
        );
        assert!(!settings.hand_removal_requested);
        assert_eq!(settings.particle_capacity, 32_768);
        assert_eq!(settings.sample_stride_pixels, 12);
        assert!(!settings.high_rate_json_payload);
        assert_eq!(
            settings.surface_support_normal_source,
            NativeEnvironmentDepthSurfaceNormalSource::Off
        );

        let fields = settings.marker_fields();
        assert!(fields.contains("environmentDepthMode=disabled"));
        assert!(fields.contains("environmentDepthSource=runtime-provider"));
        assert!(fields.contains("environmentDepthShaderLayerPolicy=mono-layer0"));
        assert!(fields.contains("environmentDepthDepthUnitsPolicy=projected-depth-from-near-far"));
        assert!(fields.contains("environmentDepthRawToMetersPolicy=projected-depth-from-near-far"));
        assert!(fields.contains("environmentDepthDebugView=normal"));
        assert!(fields.contains("environmentDepthProviderState=not-requested"));
        assert!(fields.contains("environmentDepthHandRemovalRequested=false"));
        assert!(fields.contains("environmentDepthHandRemovalEnabled=false"));
        assert!(fields.contains("environmentDepthHighRateJsonPayload=false"));
        assert!(fields.contains("environmentDepthSurfaceNormalSource=off"));
        assert!(fields.contains("environmentDepthSurfaceNormalStatus=disabled"));
        assert!(fields.contains("environmentDepthGpuReconstructMs=0.000"));
    }

    #[test]
    fn environment_depth_settings_parse_status_and_bounds() {
        let options = options_from(&[
            (PROP_ENVIRONMENT_DEPTH_MODE, "status-only"),
            (PROP_ENVIRONMENT_DEPTH_SOURCE, "synthetic-gpu-proof"),
            (PROP_ENVIRONMENT_DEPTH_LAYER_POLICY, "mono-layer1"),
            (
                PROP_ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY,
                "projected-depth-from-near-far",
            ),
            (PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW, "raw-d16"),
            (PROP_ENVIRONMENT_DEPTH_REFERENCE_SPACE, "stage"),
            (PROP_ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED, "true"),
            (PROP_ENVIRONMENT_DEPTH_PARTICLE_CAPACITY, "999999"),
            (PROP_ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS, "0"),
            (PROP_ENVIRONMENT_DEPTH_NEAR_M, "0.50"),
            (PROP_ENVIRONMENT_DEPTH_FAR_M, "0.40"),
            (PROP_ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD, "true"),
        ]);
        let settings = options.environment_depth_settings;

        assert_eq!(settings.mode, NativeEnvironmentDepthMode::StatusOnly);
        assert_eq!(
            settings.source,
            NativeEnvironmentDepthSource::SyntheticGpuProof
        );
        assert_eq!(
            settings.layer_policy,
            NativeEnvironmentDepthLayerPolicy::MonoLayer1
        );
        assert_eq!(settings.source_view_index(), 1);
        assert_eq!(settings.sampled_layer_mask(), "0x2");
        assert_eq!(
            settings.depth_units_policy,
            NativeEnvironmentDepthDepthUnitsPolicy::ProjectedDepthFromNearFar
        );
        assert_eq!(settings.debug_view, NativeEnvironmentDepthDebugView::RawD16);
        assert_eq!(
            settings.reference_space,
            NativeEnvironmentDepthReferenceSpace::OpenXrStage
        );
        assert!(settings.hand_removal_requested);
        assert_eq!(settings.particle_capacity, 262_144);
        assert_eq!(settings.sample_stride_pixels, 12);
        assert_eq!(settings.near_m, 0.50);
        assert!(settings.far_m > settings.near_m);
        assert!(settings.high_rate_json_payload);

        let fields = settings.marker_fields();
        assert!(fields.contains("environmentDepthMode=status-only"));
        assert!(fields.contains("environmentDepthSource=synthetic-gpu-proof"));
        assert!(fields.contains("environmentDepthShaderLayerPolicy=mono-layer1"));
        assert!(fields.contains("environmentDepthDepthUnitsPolicy=projected-depth-from-near-far"));
        assert!(fields.contains("environmentDepthDebugView=raw-d16"));
        assert!(fields.contains("environmentDepthProviderState=status-only-skeleton"));
        assert!(fields.contains("environmentDepthReferenceSpace=openxr-stage"));
        assert!(fields.contains("environmentDepthHandRemovalRequested=true"));
        assert!(fields.contains("environmentDepthParticleCapacity=262144"));
        assert!(fields.contains("environmentDepthSampleStridePixels=12"));
    }

    #[test]
    fn environment_depth_debug_view_modes_parse_for_particle_diagnostics() {
        let cases = [
            (
                "confidence",
                NativeEnvironmentDepthDebugView::Confidence,
                "confidence",
                1.0,
            ),
            ("age", NativeEnvironmentDepthDebugView::Age, "age", 2.0),
            (
                "source-layer",
                NativeEnvironmentDepthDebugView::SourceLayer,
                "source-layer",
                3.0,
            ),
            (
                "hash-probe",
                NativeEnvironmentDepthDebugView::HashProbe,
                "hash-probe",
                4.0,
            ),
            (
                "free-space-state",
                NativeEnvironmentDepthDebugView::FreeSpaceState,
                "free-space-state",
                5.0,
            ),
            (
                "surface-support",
                NativeEnvironmentDepthDebugView::SurfaceSupport,
                "surface-support",
                6.0,
            ),
            (
                "normal-coherence",
                NativeEnvironmentDepthDebugView::NormalCoherence,
                "normal-coherence",
                7.0,
            ),
            (
                "support-count",
                NativeEnvironmentDepthDebugView::SupportCount,
                "support-count",
                8.0,
            ),
            (
                "surface-residual",
                NativeEnvironmentDepthDebugView::SurfaceResidual,
                "surface-residual",
                9.0,
            ),
        ];
        for (property_value, expected, marker, code) in cases {
            let options = options_from(&[(PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW, property_value)]);
            let debug_view = options.environment_depth_settings.debug_view;
            assert_eq!(debug_view, expected);
            assert_eq!(debug_view.marker_value(), marker);
            assert_eq!(debug_view.particle_debug_color_mode(), marker);
            assert_eq!(debug_view.particle_debug_color_code(), code);
        }
    }

    #[test]
    fn environment_depth_surface_support_settings_are_non_enforcing_markers() {
        let options = options_from(&[
            (PROP_ENVIRONMENT_DEPTH_SURFACE_MODEL, "hybrid"),
            (PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS, "2"),
            (PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS, "4"),
            (PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS, "3"),
            (
                PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS,
                "1",
            ),
            (
                PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS,
                "16",
            ),
            (
                PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE,
                "connected-labels",
            ),
            (
                PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE,
                "depth-neighborhood",
            ),
            (
                PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE,
                "loose",
            ),
            (
                PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY,
                "hide",
            ),
            (
                PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY,
                "hard",
            ),
            (PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW, "surface-support"),
        ]);
        let settings = options.environment_depth_settings;

        assert_eq!(
            settings.surface_model,
            NativeEnvironmentDepthSurfaceModel::Hybrid
        );
        assert_eq!(settings.surface_support_radius_cells, 2);
        assert_eq!(settings.surface_support_min_neighbors, 4);
        assert_eq!(settings.surface_support_min_observations, 3);
        assert_eq!(settings.surface_support_min_source_layers, 1);
        assert_eq!(settings.surface_support_component_min_cells, 16);
        assert_eq!(
            settings.surface_support_component_mode,
            NativeEnvironmentDepthSurfaceComponentMode::ConnectedLabels
        );
        assert_eq!(
            settings.surface_support_normal_source,
            NativeEnvironmentDepthSurfaceNormalSource::DepthNeighborhood
        );
        assert_eq!(
            settings.surface_support_normal_coherence,
            NativeEnvironmentDepthSurfaceNormalCoherence::Loose
        );
        assert_eq!(
            settings.surface_support_small_component_policy,
            NativeEnvironmentDepthSurfaceSmallComponentPolicy::Hide
        );
        assert_eq!(
            settings.surface_support_free_space_decay,
            NativeEnvironmentDepthSurfaceFreeSpaceDecay::Hard
        );
        assert_eq!(
            settings.debug_view,
            NativeEnvironmentDepthDebugView::SurfaceSupport
        );
        assert!(settings.surface_support_requested());

        let fields = settings.marker_fields();
        assert!(fields.contains("environmentDepthSurfaceModel=hybrid"));
        assert!(fields.contains("environmentDepthSurfaceSupportRequested=true"));
        assert!(fields.contains("environmentDepthSurfaceSupportEnforced=false"));
        assert!(fields.contains("environmentDepthSurfaceSupportMode=hybrid"));
        assert!(fields.contains("environmentDepthSurfaceSupportRadiusCells=2"));
        assert!(fields.contains("environmentDepthSurfaceMinNeighborCount=4"));
        assert!(fields.contains("environmentDepthSurfaceMinObservationCount=3"));
        assert!(fields.contains("environmentDepthSurfaceComponentMinCells=16"));
        assert!(fields.contains("environmentDepthSurfaceComponentMode=connected-labels"));
        assert!(fields.contains("environmentDepthSurfaceSmallComponentPolicy=hide"));
        assert!(fields.contains("environmentDepthSurfaceSmallComponentRejectedCells=0"));
        assert!(fields.contains("environmentDepthSurfaceComponentCandidateCells=0"));
        assert!(fields.contains("environmentDepthSurfaceConfirmedComponentCells=0"));
        assert!(fields.contains("environmentDepthSurfaceNormalSource=depth-neighborhood"));
        assert!(fields.contains("environmentDepthSurfaceNormalCoherence=loose"));
        assert!(fields.contains("environmentDepthSurfaceNormalValidCells=0"));
        assert!(fields.contains("environmentDepthSurfaceNormalInvalidCells=0"));
        assert!(fields.contains("environmentDepthSurfaceNormalRejectedCells=0"));
        assert!(fields.contains("environmentDepthSurfaceNormalStatus=configured-counters-pending"));
        assert!(fields.contains("environmentDepthSurfaceFreeSpaceDecay=hard"));
        assert!(fields.contains("environmentDepthSurfaceSupportStatus=pending-gpu-support-pass"));
        assert!(fields.contains("environmentDepthSurfaceLifecycleStatus=pending-runtime-support"));
        assert!(fields.contains("environmentDepthSurfaceCandidateCells=0"));
        assert!(fields.contains("environmentDepthSurfaceConfirmedCells=0"));
        assert!(fields.contains("environmentDepthSurfacePromotedCells=0"));
        assert!(fields.contains("environmentDepthSurfaceCandidateRetiredCells=0"));
    }

    #[test]
    fn environment_depth_synthetic_particle_profile_enables_gpu_proof() {
        let options = options_from(&[
            (PROP_ENVIRONMENT_DEPTH_MODE, "retained-particles"),
            (PROP_ENVIRONMENT_DEPTH_SOURCE, "synthetic-gpu-proof"),
        ]);
        let settings = options.environment_depth_settings;

        assert_eq!(settings.mode, NativeEnvironmentDepthMode::RetainedParticles);
        assert_eq!(
            settings.source,
            NativeEnvironmentDepthSource::SyntheticGpuProof
        );
        assert!(settings.synthetic_gpu_proof_requested());

        let fields = settings.marker_fields();
        assert!(fields.contains("environmentDepthProviderState=synthetic-gpu-proof"));
        assert!(fields.contains("environmentDepthAcquireStatus=not-attempted-synthetic-gpu-proof"));
    }

    #[test]
    fn environment_depth_scene_particle_map_mode_is_distinct() {
        let options = options_from(&[
            (PROP_ENVIRONMENT_DEPTH_MODE, "scene-particle-map"),
            (PROP_ENVIRONMENT_DEPTH_SOURCE, "xr-meta-environment-depth"),
        ]);
        let settings = options.environment_depth_settings;

        assert_eq!(settings.mode, NativeEnvironmentDepthMode::SceneParticleMap);
        assert_eq!(
            settings.source,
            NativeEnvironmentDepthSource::MetaEnvironmentDepth
        );
        assert!(settings.mode_draws_particles());
        assert!(settings.runtime_provider_requested());
        assert!(settings.scene_particle_map_requested());

        let fields = settings.marker_fields();
        assert!(fields.contains("environmentDepthMode=scene-particle-map"));
        assert!(fields.contains("environmentDepthSource=xr-meta-environment-depth"));
    }

    #[test]
    fn display_composite_settings_keep_mediaprojection_as_hardware_buffer_source() {
        let options = options_from(&[
            (PROP_DISPLAY_COMPOSITE_ENABLED, "true"),
            (PROP_DISPLAY_COMPOSITE_SOURCE, "android-mediaprojection"),
            (PROP_DISPLAY_COMPOSITE_MODE, "gpu-feedback-diagnostic"),
            (PROP_DISPLAY_COMPOSITE_WIDTH, "1920"),
            (PROP_DISPLAY_COMPOSITE_HEIGHT, "1080"),
            (PROP_DISPLAY_COMPOSITE_MAX_IMAGES, "4"),
            (PROP_DISPLAY_COMPOSITE_FPS_CAP, "45"),
            (PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED, "true"),
            (
                PROP_DISPLAY_COMPOSITE_FEEDBACK_PROJECTION,
                "full-eye-peripheral-stretch",
            ),
            (PROP_DISPLAY_COMPOSITE_HIGH_RATE_JSON_PAYLOAD, "false"),
        ]);
        let settings = options.display_composite_settings;

        assert!(settings.enabled);
        assert_eq!(
            settings.source,
            NativeDisplayCompositeSource::AndroidMediaProjection
        );
        assert_eq!(
            settings.mode,
            NativeDisplayCompositeMode::GpuFeedbackDiagnostic
        );
        assert_eq!(settings.width, 1920);
        assert_eq!(settings.height, 1080);
        assert_eq!(settings.max_images, 4);
        assert_eq!(settings.fps_cap, 45);
        assert!(settings.feedback_enabled);
        assert_eq!(
            settings.feedback_projection,
            NativeDisplayCompositeFeedbackProjection::FullEyePeripheralStretch
        );
        assert!(!settings.high_rate_json_payload);

        let fields = settings.marker_fields();
        assert!(fields.contains("displayCompositeStream=display_composite"));
        assert!(fields.contains("sourceAuthority=android-mediaprojection"));
        assert!(fields.contains("rawCamera=false"));
        assert!(fields.contains("passthroughTexture=false"));
        assert!(fields.contains("environmentDepth=false"));
        assert!(fields.contains("geometryWitness=false"));
        assert!(fields.contains("highRateJsonPayload=false"));
        assert!(fields.contains("displayCompositeTransport=ndk-aimage-reader-ahardwarebuffer"));
        assert!(fields.contains("nativeImageReader=true"));
        assert!(fields.contains("javaHardwareBufferBridge=false"));
        assert!(fields.contains(
            "displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image"
        ));
    }

    #[test]
    fn display_composite_readback_mode_enables_capture_export() {
        let options = options_from(&[
            (PROP_DISPLAY_COMPOSITE_ENABLED, "true"),
            (PROP_DISPLAY_COMPOSITE_MODE, "gpu-readback-diagnostic"),
            (PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED, "true"),
            (PROP_DISPLAY_COMPOSITE_HIGH_RATE_JSON_PAYLOAD, "false"),
        ]);
        let settings = options.display_composite_settings;

        assert_eq!(
            settings.mode,
            NativeDisplayCompositeMode::GpuReadbackDiagnostic
        );
        assert!(settings.capture_export_enabled());
        assert!(settings
            .marker_fields()
            .contains("displayCompositeMode=gpu-readback-diagnostic"));
    }

    #[test]
    fn display_composite_recursive_mode_enables_level_capture_export() {
        let options = options_from(&[
            (PROP_DISPLAY_COMPOSITE_ENABLED, "true"),
            (
                PROP_DISPLAY_COMPOSITE_MODE,
                "gpu-recursive-feedback-diagnostic",
            ),
            (PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED, "true"),
            (PROP_DISPLAY_COMPOSITE_HIGH_RATE_JSON_PAYLOAD, "false"),
        ]);
        let settings = options.display_composite_settings;

        assert_eq!(
            settings.mode,
            NativeDisplayCompositeMode::GpuRecursiveFeedbackDiagnostic
        );
        assert!(settings.capture_export_enabled());
        assert!(settings
            .marker_fields()
            .contains("displayCompositeMode=gpu-recursive-feedback-diagnostic"));
    }

    #[test]
    fn video_projection_settings_map_side_by_side_halves_to_eyes() {
        let options = options_from(&[
            (PROP_VIDEO_PROJECTION_ENABLED, "true"),
            (PROP_VIDEO_PROJECTION_SOURCE, "app-private-file"),
            (PROP_VIDEO_PROJECTION_PATH, "video/noodletest-sbs.mp4"),
            (
                PROP_VIDEO_PROJECTION_STEREO_LAYOUT,
                "side-by-side-left-right",
            ),
            (PROP_VIDEO_PROJECTION_WIDTH, "3840"),
            (PROP_VIDEO_PROJECTION_HEIGHT, "1920"),
            (PROP_VIDEO_PROJECTION_MAX_IMAGES, "3"),
            (PROP_VIDEO_PROJECTION_FPS_CAP, "30"),
            (PROP_VIDEO_PROJECTION_LOOPING, "true"),
            (PROP_VIDEO_PROJECTION_TARGET, "full-eye"),
            (PROP_VIDEO_PROJECTION_OPACITY, "1.0"),
            (PROP_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD, "false"),
        ]);
        let settings = options.video_projection_settings;

        assert!(settings.active());
        assert_eq!(settings.source, NativeVideoProjectionSource::AppPrivateFile);
        assert_eq!(
            settings.stereo_layout,
            NativeVideoProjectionStereoLayout::SideBySideLeftRight
        );
        assert_eq!(settings.width, 3840);
        assert_eq!(settings.height, 1920);
        assert_eq!(settings.target, NativeVideoProjectionTarget::FullEye);
        assert!((settings.stereo_layout.per_eye_aspect_ratio(3840, 1920) - 1.0).abs() < 0.001);
        assert_eq!(
            settings
                .stereo_layout
                .source_uv_rect_for_eye(0)
                .as_xywh_token(),
            "0.000000,0.000000,0.500000,1.000000"
        );
        assert_eq!(
            settings
                .stereo_layout
                .source_uv_rect_for_eye(1)
                .as_xywh_token(),
            "0.500000,0.000000,0.500000,1.000000"
        );

        let fields = settings.marker_fields();
        assert!(fields.contains("videoProjectionStream=stereo_video"));
        assert!(
            fields.contains("videoProjectionSourceAuthority=android-mediacodec-surface-decoder")
        );
        assert!(fields.contains(
            "videoProjectionTransport=mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer"
        ));
        assert!(fields.contains("videoProjectionStereoLayout=side-by-side-left-right"));
        assert!(fields.contains("videoProjectionTarget=full-eye"));
        assert!(fields.contains("nativeImageReader=true"));
        assert!(fields.contains("javaHardwareBufferBridge=false"));
        assert!(fields.contains("cpuPixelCopy=false"));
        assert!(fields.contains("highRateJsonPayload=false"));
    }

    #[test]
    fn peripheral_stretch_settings_match_hwb_reference_defaults() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "peripheral-stretch"),
            (PROP_PROJECTION_BORDER_POLICY, "passthrough-underlay"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(settings.peripheral_stretch_active());
        assert!(settings.transition_active());
        assert_eq!(settings.core_scale, 1.0);
        assert_eq!(settings.edge_inset_uv, 0.015);
        assert_eq!(settings.max_inset_uv, 0.14);
        assert_eq!(settings.inner_blend_uv, 0.040);

        let fields = settings.marker_fields();
        assert!(fields.contains("processingLayer=peripheral-stretch"));
        assert!(fields.contains("projectionBorderPolicy=passthrough-underlay"));
        assert!(fields.contains("peripheralStretchBlendMode=target-inner-band"));
        assert!(fields.contains("peripheralStretchTransitionActive=true"));
        assert!(fields.contains("peripheralStretchConsumesProjectionExterior=true"));
        assert!(fields.contains("videoBorderBlendActive=false"));
        assert!(fields.contains(
            "peripheralStretchProjectionExteriorMode=target-edge-stretch-with-inner-band-blend"
        ));
        assert!(fields
            .contains("peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band"));
    }

    #[test]
    fn video_border_blend_reuses_stretch_inner_band_over_video_background() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "video-border-blend"),
            (PROP_PROJECTION_BORDER_POLICY, "passthrough-underlay"),
            (PROP_VIDEO_PROJECTION_ENABLED, "true"),
            (PROP_VIDEO_PROJECTION_PATH, "video/noodletest-sbs.mp4"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(!settings.peripheral_stretch_active());
        assert!(settings.video_border_blend_active());
        assert!(settings.transition_active());
        assert_eq!(
            settings.video_border_blend_mode,
            NativeVideoBorderBlendMode::AlphaOver
        );
        assert!(!settings.video_border_shader_composite_active());
        assert_eq!(
            settings.guide_projection_coverage(),
            "full-eye-video-border-blend"
        );

        let fields = settings.marker_fields();
        assert!(fields.contains("processingLayer=video-border-blend"));
        assert!(fields.contains("guideProjectionCoverage=full-eye-video-border-blend"));
        assert!(fields.contains("guideProjectionEdgeTint=diagnostic-debug-only"));
        assert!(fields.contains("guideProjectionEdgeTintActive=false"));
        assert!(fields.contains("videoBorderBlendActive=true"));
        assert!(fields.contains("videoBorderBlendMode=alpha-over"));
        assert!(fields.contains("videoBorderBlendCompositor=fixed-function-premultiplied-alpha"));
        assert!(fields.contains("videoBorderBlendShaderCompositeActive=false"));
        assert!(fields.contains("peripheralStretchConsumesProjectionExterior=false"));
        assert!(fields.contains("videoBorderBlendConsumesProjectionExterior=false"));
        assert!(fields.contains(
            "peripheralStretchProjectionExteriorMode=video-background-with-inner-band-camera-blend"
        ));
        assert!(fields.contains("peripheralStretchExteriorSource=video-projection-background"));
        assert!(fields.contains(
            "peripheralStretchBlendSemantics=camera-guide-alpha-fades-to-video-through-inner-band"
        ));
        assert!(
            fields.contains("videoBorderBlendSource=prepared-stereo-video-projection-background")
        );
        assert!(fields.contains("videoBorderBlendCameraSource=guide-texture"));
    }

    #[test]
    fn video_border_blend_crossfade_uses_shader_composite() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "video-border-blend"),
            (PROP_VIDEO_BORDER_BLEND_MODE, "crossfade"),
            (PROP_PROJECTION_BORDER_POLICY, "passthrough-underlay"),
            (PROP_VIDEO_PROJECTION_ENABLED, "true"),
            (PROP_VIDEO_PROJECTION_PATH, "video/noodletest-sbs.mp4"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(settings.video_border_blend_active());
        assert_eq!(
            settings.video_border_blend_mode,
            NativeVideoBorderBlendMode::Crossfade
        );
        assert!(settings.video_border_shader_composite_active());

        let fields = settings.marker_fields();
        assert!(fields.contains("videoBorderBlendMode=crossfade"));
        assert!(fields.contains("videoBorderBlendCompositor=guide-video-shader-composite"));
        assert!(fields.contains("videoBorderBlendShaderCompositeActive=true"));
        assert!(fields.contains(
            "peripheralStretchBlendSemantics=camera-guide-and-video-sampled-shader-composite-through-inner-band"
        ));
    }

    #[test]
    fn peripheral_stretch_values_parse_and_clamp() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "edge_stretch"),
            (PROP_PERIPHERAL_STRETCH_CORE_SCALE, "0.001"),
            (PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV, "0.7"),
            (PROP_PERIPHERAL_STRETCH_MAX_INSET_UV, "0.1"),
            (PROP_PERIPHERAL_STRETCH_BLEND_MODE, "off"),
            (PROP_PROJECTION_BORDER_OPACITY, "-5"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(settings.peripheral_stretch_active());
        assert_eq!(settings.core_scale, 0.05);
        assert_eq!(settings.edge_inset_uv, 0.49);
        assert_eq!(settings.max_inset_uv, 0.49);
        assert_eq!(settings.projection_border_opacity, 0.0);
        assert!(!settings.transition_active());
    }
}
