//! Marker scorecard emission for the Quest-native OpenXR/Vulkan frame loop.

use ash::vk;
use openxr as xr;

use super::{
    CameraProjectionFrameStats, CameraProjectionMetadata, CompactHandInputSourceMode,
    FrameCpuTimings, GpuEnvironmentDepthParticleFrameStats, GpuHandAnchorParticleFrameSetStats,
    GpuHandMeshVisualFrameSetStats, GpuMeshReplayStats, GpuSdfFieldFrameStats, GpuStageTimings,
    GpuStimulusVolumeFrameStats, GpuTimestampStage, GuideBlurGraphFrameStats, LiveHandCompactStats,
    NativeCameraOutputMode, NativeCameraQualityProfile, NativeCameraRuntime, NativeCameraSyncMode,
    NativeEnvironmentDepthSettings, NativeProjectionBorderStretchSettings,
    NativeRendererRenderMode, NativeStimulusVolumeSettings, NativeSwapchainColorFormatMode,
    PrivateExtensionSlotFrameStats, RecordedHandReplaySummary, ReplayVisualStats,
};

pub(super) fn write_projection_scorecard(
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
    remote_broker_camera_projection_active: bool,
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
    native_passthrough_requested: bool,
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
    let custom_camera_output_enabled = render_mode.uses_custom_stereo_projection()
        && (camera_output_mode.camera_import_enabled() || remote_broker_camera_projection_active);
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
    } else if remote_broker_camera_projection_active {
        "metadata-target-remote-broker-camera2-h264"
    } else if !camera_output_mode.camera_import_enabled() {
        "disabled-camera-output"
    } else if camera_output_mode.direct_hwb_forced() {
        "metadata-target-direct-hwb-forced"
    } else if private_projection_active {
        "metadata-target-private-extension-slot-final"
    } else if guide_projection_active && projection_settings.peripheral_stretch_active() {
        "metadata-target-guide-texture-peripheral-stretch-final"
    } else if guide_projection_active && projection_settings.video_border_blend_active() {
        "metadata-target-guide-texture-video-border-blend-final"
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
        native_passthrough_requested && hand_mesh_real_hands_visible;
    let solid_black_real_hand_mesh_visible =
        render_mode.uses_solid_black_background() && hand_mesh_real_hands_visible;
    let hand_mesh_visual_evidence_rects =
        replay_visual_stats.hand_mesh_screen_rect_marker_fields(projection_metadata);
    let sdf_visual_evidence_rects =
        replay_visual_stats.sdf_screen_rect_marker_fields(projection_metadata);
    let stimulus_volume_scorecard_fields =
        if render_mode.uses_stimulus_volume() || stimulus_volume_settings.enabled {
            format!(
                "{} {}",
                stimulus_volume_settings.marker_fields(),
                stimulus_volume_stats.marker_fields()
            )
        } else {
            "stimulusVolumeRoute=false stimulusVolumeEnabled=false stimulusVolumeActive=false"
                .to_string()
        };
    let camera_capture_result_correlation_ready =
        camera_projection_stats.left_capture_result.ready()
            && camera_projection_stats.right_capture_result.ready();
    crate::marker(
        "camera-projection-scorecard",
        format!(
            "frame={} renderMode={} remoteBrokerCameraProjectionActive={} cameraProjectionReady={} openxrSubmitReady=true vulkanExternalImportReady={} projectionReady={} cameraProjectionPath={} leftCameraId={} rightCameraId={} leftSourceFrame={} rightSourceFrame={} leftHardwareBufferId={} rightHardwareBufferId={} leftImportSequence={} rightImportSequence={} stereoPairDeltaNs={} stereoPairingPolicy={} sourceLayout={} leftSourceUvRect={} rightSourceUvRect={} packedSourceVisualEquivalenceReady={} observedOpenXrFps={:.1} projectionExtent={}x{} sourceAuthority={} nativeImageReader=true javaHardwareBufferBridge=false cpuPixelCopy=false",
            frame_count,
            render_mode.marker_value(),
            remote_broker_camera_projection_active,
            camera_projection_ready,
            camera_projection_stats.rendered,
            camera_projection_ready,
            camera_projection_path,
            crate::sanitize(&camera_projection_stats.left_camera_id),
            crate::sanitize(&camera_projection_stats.right_camera_id),
            camera_projection_stats.left_source_frame,
            camera_projection_stats.right_source_frame,
            camera_projection_stats.left_hardware_buffer_id,
            camera_projection_stats.right_hardware_buffer_id,
            camera_projection_stats.left_import_sequence,
            camera_projection_stats.right_import_sequence,
            camera_projection_stats.pair_delta_ns,
            camera_projection_stats.stereo_pairing_policy,
            camera_projection_stats.source_layout,
            camera_projection_stats.left_source_uv_rect.as_xywh_token(),
            camera_projection_stats.right_source_uv_rect.as_xywh_token(),
            camera_projection_stats.packed_source_visual_equivalence_ready,
            observed_openxr_fps,
            extent.width,
            extent.height,
            if remote_broker_camera_projection_active {
                "manifold-broker-rmanvid1-camera2-h264"
            } else {
                "camera-runtime"
            }
        ),
    );
    crate::marker(
        "timing-scorecard",
        format!(
            "frame={} renderMode={} customStereoProjectionEnabled={} nativePassthroughRequested={} solidBlackBackground={} openxrDefaultHandVisualRequested={} nativePassthroughLayerActive={} environmentBlendMode={:?} projectionLayerAlphaBlend={} cameraRuntimeMode={} cameraOutputMode={} cameraYcbcrMode={} cameraYcbcrConversionMode={} cameraResolutionProfile={} readerMaxImages={} cameraQualityProfile={} cameraSyncRequested={} cameraSyncActive={} cameraSyncImplementation={} cameraLumaDiagnosticRequested={} swapchainColorFormatMode={} directHwbBorderOpacity={:.3} camera_frames_acquired={} hardware_buffer_imports={} hardware_buffer_cache_hits={} hardware_buffer_cache_misses={} guide_graph_renders={} guide_graph_cache_hits={} sdf_field_updates={} private_layer_invocations={} xr_frames_submitted={} stale_frames={} releaseRetireCount={} observedOpenXrFps={:.1} recordCpuMs={:.3} submitCpuMs={:.3} {} {} {} {} projectionExtent={}x{} openxrSubmitReady=true vulkanExternalImportReady={} cameraProjectionReady={} directHwbProjectionDiagnostic={} cameraProjectionPath={} metadataDrivenTargetFootprint={} guideProjectionCoverage={} {} {} plannedFinalExternalHwbSamples={} plannedGuideTextureSamples={} actualFinalExternalHwbSamples={} actualGuideTextureSamples={} leftCameraId={} rightCameraId={} leftImageDataspace={} leftImageDataspaceStatus={} rightImageDataspace={} rightImageDataspaceStatus={} leftSourceFrame={} rightSourceFrame={} leftHardwareBufferId={} rightHardwareBufferId={} leftImportSequence={} rightImportSequence={} stereoPairDeltaNs={} stereoPairingPolicy={} cameraCaptureResultCorrelationReady={} {} {} {} {} recordedHandReplayVisible={} recordedHandReplayTarget=metadata-target-screen-uv {} {} replayVisualFrame={} replayTimestampNs={} replayVisualPointCount={} compactJointOverlayVisible=false handMeshRealHandsVisible={} nativePassthroughRealHandMeshVisible={} solidBlackRealHandMeshVisible={} {} {} sdfTarget=metadata-target-screen-uv {} {} {} {} {} visualAcceptance=target-area-orientation-pending-screenshot projectionReady=true",
            frame_count,
            render_mode.marker_value(),
            render_mode.uses_custom_stereo_projection(),
            native_passthrough_requested,
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
            stimulus_volume_scorecard_fields,
            "",
            extent.width,
            extent.height,
            camera_projection_stats.rendered,
            camera_projection_ready,
            direct_hwb_projection_diagnostic,
            camera_projection_path,
            render_mode.uses_custom_stereo_projection(),
            projection_settings.guide_projection_coverage(),
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
            native_passthrough_requested,
            environment_depth_settings.marker_fields(),
            environment_depth_particle_stats.marker_fields(),
            gpu_stage_timings.stage_ms(GpuTimestampStage::HandMeshVisual),
            gpu_stage_timings.stage_ms(GpuTimestampStage::ProjectionComposite),
        ),
    );
    if render_mode.uses_stimulus_volume() || stimulus_volume_settings.enabled {
        crate::marker(
            "stimulus-volume",
            format!(
                "status=scorecard frame={} observedOpenXrFps={:.1} renderMode={} nativePassthroughRequested={} projectionLayerAlphaBlend={} stimulusVolumeGpuMs={:.3} stimulusVolumeComputeGpuMs={:.3} stimulusVolumeProjectionGpuMs={:.3} {} {}",
                frame_count,
                observed_openxr_fps,
                render_mode.marker_value(),
                native_passthrough_requested,
                render_mode.projection_layer_alpha_blend(),
                gpu_stage_timings.stage_ms(GpuTimestampStage::ProjectionComposite),
                gpu_stage_timings.stage_ms(GpuTimestampStage::StimulusVolumeCompute),
                gpu_stage_timings.stage_ms(GpuTimestampStage::StimulusVolumeProjection),
                stimulus_volume_settings.marker_fields(),
                stimulus_volume_stats.marker_fields(),
            ),
        );
    }
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
            projection_settings.guide_projection_coverage(),
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

fn optional_i32_marker(value: Option<i32>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "none".to_string())
}
