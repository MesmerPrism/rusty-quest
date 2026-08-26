param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$appRoot = Join-Path $repoRootPath "apps\native-renderer-android"
$nativeRoot = Join-Path $appRoot "native"
$srcRoot = Join-Path $nativeRoot "src"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer OpenXR/Vulkan static file ($Label): $Path"
    }
    return Get-Content -Raw -Path $Path
}

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch $token) {
            throw "Native renderer OpenXR/Vulkan static check failed for ${Label}: missing token: $token"
        }
    }
}

function Assert-ContainsLiteralTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if (-not $Text.Contains($token)) {
            throw "Native renderer OpenXR/Vulkan static check failed for ${Label}: missing token: $token"
        }
    }
}

$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$nativeCamera = Read-RequiredText (Join-Path $srcRoot "native_camera.rs") "native camera"
$nativeRendererTiming = Read-RequiredText (Join-Path $srcRoot "native_renderer_timing.rs") "native renderer timing"
$privateExtensionSlot = Read-RequiredText (Join-Path $srcRoot "private_extension_slot.rs") "private extension slot"
$gpuPrivateParticles = Read-RequiredText (Join-Path $srcRoot "gpu_private_particles.rs") "private particle renderer"
$nativeBuildScript = Read-RequiredText (Join-Path $nativeRoot "build.rs") "native build script"
$privateParticlesVertex = Read-RequiredText (Join-Path $nativeRoot "shaders\private_particles.vert.glsl") "private particle vertex shader"
$privateParticlesFragment = Read-RequiredText (Join-Path $nativeRoot "shaders\private_particles.frag.glsl") "private particle fragment shader"
$privateParticlesOffscreenCompositeVertex = Read-RequiredText (Join-Path $nativeRoot "shaders\private_particles_offscreen_composite.vert.glsl") "private particle offscreen composite vertex shader"
$privateParticlesOffscreenCompositeFragment = Read-RequiredText (Join-Path $nativeRoot "shaders\private_particles_offscreen_composite.frag.glsl") "private particle offscreen composite fragment shader"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_camera_options.rs") "native renderer camera options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_display_refresh_options.rs") "native renderer display refresh options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_private_particle_heartbeat_orbit_request.rs") "private particle heartbeat orbit request options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_private_particle_visual_scale_request.rs") "private particle visual scale request options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_private_particle_material_request.rs") "private particle material request options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_private_particle_render_experiment_request.rs") "private particle render experiment request options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_properties.rs") "native renderer properties"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_property_values.rs") "native renderer property values"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_environment_depth_options.rs") "native renderer environment-depth options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_hand_anchor_particle_options.rs") "native renderer hand-anchor particle options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_passthrough_style_options.rs") "native renderer passthrough style options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_projection_border_stretch_options.rs") "native renderer projection border stretch options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_stimulus_volume_options.rs") "native renderer stimulus volume options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_visual_options.rs") "native renderer visual options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options.rs") "native renderer options facade"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options_tests.rs") "native renderer options tests")
) -join "`n"

Assert-ContainsTokens $privateExtensionSlot @(
    'PRIVATE_GUIDE_NATIVE_PHASE_RATE_HZ: f32 = 0\.5',
    'struct PrivateLayerGuidePush',
    'reprojection_row0: \[f32; 4\]',
    'reprojection_row1: \[f32; 4\]',
    'reprojection_row2: \[f32; 4\]',
    'reprojection_params: \[f32; 4\]',
    'size_of::<PrivateLayerGuidePush>\(\) == 112'
) "private guide push-constant ABI"
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "openxr_passthrough_style.rs") "OpenXR passthrough style helper"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\replay_visual_stats.rs") "xr_vulkan replay visual stats"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard")
) -join "`n"

Assert-ContainsTokens "$xrVulkanSurface`n$nativeRendererOptionSurface`n$nativeRendererTiming`n$privateExtensionSlot`n$nativeCamera`n$gpuPrivateParticles" @(
    'mod replay_visual_stats',
    'mod scorecard',
    'Replay/live visual evidence rectangle helpers for the Quest-native frame loop',
    'pub\(super\) struct ReplayVisualStats',
    'pub\(super\) struct EvidenceUvRect',
    'scorecard::write_projection_scorecard',
    'Marker scorecard emission for the Quest-native OpenXR/Vulkan frame loop',
    'pub\(super\) fn write_projection_scorecard',
    'fn optional_i32_marker',
    'xr::Entry::load',
    'LoaderInitKHR',
    'InstanceCreateInfoAndroidKHR',
    'khr_android_create_instance',
    'khr_vulkan_enable2',
    'graphics_requirements::<xr::Vulkan>',
    'ash::Entry::load',
    'create_vulkan_instance',
    'vulkan_graphics_device',
    'external_memory_android_hardware_buffer',
    'sampler_ycbcr_conversion',
    'PhysicalDeviceSamplerYcbcrConversionFeatures',
    'combined-immutable-sampler-ycbcr-conversion',
    'vulkanExternalImportPrereqsReady',
    'openxrSubmitReady=false',
    'openxrSubmitReady=true',
    'vulkanExternalImportReady=false',
    'create_session::<xr::Vulkan>',
    'create_swapchain',
    'CompositionLayerProjection',
    'FrameCpuTimings',
    'cameraAcquireImportCpuMs',
    'guideGraphCpuMs',
    'liveHandLocateCpuMs',
    'handSdfPrepareCpuMs',
    'handMeshVisualCpuMs',
    'projectionCompositeCpuMs',
    'swapchainWaitCpuMs',
    'queueSubmitCpuMs',
    'openxrEndFrameCpuMs',
    'cpuTimingScope=host-recording-and-submit',
    'GpuTimestampTracker',
    'cmd_reset_query_pool',
    'cmd_write_timestamp',
    'get_query_pool_results',
    'gpu-timestamp-timing',
    'gpuTimestampQuerySupported',
    'gpuTimestampQueryReady',
    'gpuTimestampFrameLag',
    'gpuTimestampSubmittedFrameId',
    'gpuTimestampMeasuredFrameId',
    'gpuTimestampMeasuredFrameLagFrames',
    'stage_active_mask',
    'write_compute_stage_start',
    'write_compute_stage_end',
    'privateParticleComputeDispatchGpuMs',
    'privateParticleComputeDispatchTimingScope=compute-shader-dispatch-stage',
    'privateParticleComputePostDispatchSyncGpuMs',
    'privateParticleComputePostDispatchSyncTimingScope=post-dispatch-visibility-and-diagnostic-host-sync-command-region',
    'privateParticleComputeStageScope=single-dispatch-and-post-compute-sync',
    'privateParticleWorkloadMarkerVersion=v1',
    'privateParticleComputeLogicalParticleCount=',
    'privateParticleComputeWorkgroupCount=',
    'privateParticleComputeLocalSizeX=',
    'privateParticleComputeLaunchedLaneCount=',
    'privateParticleTracerStateSlotsVisited=',
    'privateParticleDiagnosticSubmittedFrameId=',
    'privateParticleDiagnosticMeasuredFrameId=',
    'privateParticleDiagnosticMeasuredFrameLagFrames=',
    'cameraProjectionGpuMs',
    'guideGraphGpuMs',
    'handSdfGpuMs',
    'handMeshVisualGpuMs',
    'stimulusVolumeComputeGpuMs',
    'stimulusVolumeProjectionGpuMs',
    'projectionCompositeGpuMs',
    'gpuTimingScope=vulkan-timestamp-query',
    'PrivateExtensionSlotRuntime',
    'private-extension-slot',
    'record_private_layer_invocation',
    'private_layer_payload_config.rs',
    'PRIVATE_LAYER_PAYLOAD_LINKED',
    'PRIVATE_LAYER_IMPLEMENTATION_PATH',
    '!PRIVATE_LAYER_PAYLOAD_LINKED',
    'privateLayerOutput=\{\}',
    'identity-public-abi-resource',
    'privateLayerVisualAcceptance=\{\}',
    'not-applicable-public-noop',
    'recordedHandReplayVisible=\{\}',
    'animatedHandMeshVisualReady=pending',
    'compactJointOverlayDefault=false',
    'compactJointOverlayVisible=false',
    'handMeshVisualPath=compact-joint-gpu-skinned-resident-selected-mesh-triangle-draw',
    'CompactHandInputSourceMode',
    'recorded-replay-visual-proof',
    'NativeRendererRenderMode',
    'debug.rustyquest.native_renderer.render.mode',
    'native-passthrough-style-only',
    'native-passthrough-media-only',
    'native-passthrough-graft-only',
    'solid-black-hands-and-grafts',
    'solid-black-openxr-hands-anchor-particles',
    'solid-black-private-particles',
    'customStereoProjectionEnabled',
    'nativePassthroughRequested',
    'solidBlackBackground',
    'openxrDefaultHandVisualRequested',
    'requests_openxr_default_hand_visual',
    'customHandMeshVisualRequested',
    'cameraRuntimeMode=',
    'cameraProjectionPath=',
    'skipped-native-passthrough-style-only',
    'disabled-native-passthrough-style-only',
    'skipped-native-passthrough-media-only',
    'disabled-native-passthrough-media-only',
    'skipped-native-passthrough',
    'disabled-native-passthrough-graft-only',
    'skipped-solid-black-hands-and-grafts',
    'disabled-solid-black-hands-and-grafts',
    'skipped-solid-black-openxr-hands-anchor-particles',
    'disabled-solid-black-openxr-hands-anchor-particles',
    'skipped-solid-black-private-particles',
    'disabled-solid-black-private-particles',
    'requests_private_particle_recenter_input',
    'private-particle-anchor',
    'XR_FB_passthrough',
    'fb_passthrough',
    'NativePassthroughRuntime',
    'NativeDisplayRefreshSettings',
    'NativeDisplayRefreshRuntimeState',
    'PrivateParticleVisualScaleRequestState',
    'PrivateParticleVisualScaleRequestObservation',
    'PrivateParticleHeartbeatOrbitRequestState',
    'PrivateParticleHeartbeatOrbitRequestObservation',
    'PrivateParticleMaterialRequestState',
    'PrivateParticleMaterialRequestObservation',
    'PrivateParticleRenderExperimentRequestState',
    'PrivateParticleRenderExperimentRequestObservation',
    'debug\.rustyquest\.native_renderer\.private_particles\.visual\.scale_request\.v1',
    'debug\.rustyquest\.native_renderer\.private_particles\.heartbeat_orbit\.request\.v1',
    'debug\.rustyquest\.native_renderer\.private_particles\.material\.request\.v1',
    'debug\.rustyquest\.native_renderer\.private_particles\.render\.experiment_request\.v1',
    'private-particle-visual-scale-request',
    'private-particle-heartbeat-orbit-request',
    'private-particle-material-request',
    'status=session-ready',
    'status=accepted',
    'status=effective',
    'status=rejected',
    'privateParticleVisualScaleRequestSession=',
    'privateParticleVisualScaleRequestGeneration=',
    'privateParticleVisualScaleRequestId=',
    'privateParticleVisualScaleSubmittedFrame=',
    'privateParticleHeartbeatOrbitRequestSession=',
    'privateParticleHeartbeatOrbitRequestGeneration=',
    'privateParticleHeartbeatOrbitRequestId=',
    'privateParticleHeartbeatOrbitEffectiveEnabled=',
    'privateParticleHeartbeatOrbitSubmittedFrame=',
    'begin_runtime_session',
    'confirm_submitted_frame',
    'REQUESTED_DISPLAY_REFRESH_RATE_HZ_72: f32 = 72\.0',
    'REQUESTED_DISPLAY_REFRESH_RATE_HZ_90: f32 = 90\.0',
    'debug\.rustyquest\.native_renderer\.openxr\.display_refresh_rate_hz',
    'displayRefreshRequestedHz=unset',
    'displayRefreshSessionGeneration=',
    'displayRefreshSupportedHz=',
    'displayRefreshRequestResult=',
    'displayRefreshEffectiveHz=',
    'displayRefreshRateChangeFromHz=',
    'displayRefreshRateChangeToHz=',
    'displayRefreshPerformanceReady=',
    'XR_FB_display_refresh_rate',
    'fb_display_refresh_rate',
    'configure_requested_display_refresh_rate',
    'enumerate_display_refresh_rates',
    'request_display_refresh_rate',
    'get_display_refresh_rate',
    'DisplayRefreshRateChangedFB',
    'status=extension-unavailable',
    'status=request-accepted',
    'status=effective-readback',
    'status=rate-change-observed',
    'create_passthrough',
    'create_passthrough_layer',
    'native-passthrough-style',
    'NativePassthroughStyleSettings',
    'NativePassthroughStyleAudioReactiveState',
    'debug.rustyquest.native_renderer.passthrough.style.mode',
    'debug.rustyquest.native_renderer.passthrough.style.audio_reactive.enabled',
    'passthroughAudioReactiveEnabled',
    'status=audio-reactive-applied',
    'passthroughStyleSingleExtensionChain=true',
    'PassthroughStyleFB',
    'PassthroughColorMapMonoToRgbaFB',
    'PassthroughBrightnessContrastSaturationFB',
    'xrPassthroughLayerSetStyleFB',
    'CompositionLayerPassthroughFB',
    'PassthroughLayerPurposeFB::RECONSTRUCTION',
    'EnvironmentBlendMode::ALPHA_BLEND',
    'BLEND_TEXTURE_SOURCE_ALPHA',
    'nativePassthroughLayerActive',
    'projectionLayerAlphaBlend',
    'debug.rustyquest.native_renderer.replay.visual_proof.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.input.source',
    'compactHandInputSourceMode',
    'recordedReplayVisualProofEnabled',
    'recordedReplayVisualAcceptance=pending-headset-screenshot',
    'allowsRecordedFallback',
    'hand-mesh-visual-diagnostic',
    'leftHandMeshVisualScreenUvRect',
    'rightHandMeshVisualScreenUvRect',
    'leftSdfVisualScreenUvRect',
    'rightSdfVisualScreenUvRect',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv',
    'debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.visual.mesh_source',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.profile',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.alpha',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.r',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.g',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.b',
    'debug.rustyquest.native_renderer.hand_mesh.visual.material.rim_strength',
    'handMeshVisualMaterialProfile=',
    'handMeshVisualTextureImported=false',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'solidBlackRealHandMeshVisible',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'dynamicSdfReady=pending',
    'debug.rustyquest.native_renderer.sdf.field_visual.enabled',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'status=hand-mesh-skinning-active-sdf-field-visual-deferred reason=property-disabled',
    'handMeshSkinningReady',
    'GpuSdfFieldRenderer',
    'GpuSdfFieldFrameStats',
    'cpuSdfPerFrame=false',
    'xr-vulkan-probe',
    'vulkan-probe'
) "OpenXR/Vulkan runtime route"

foreach ($forbiddenToken in @(
    'privateParticleOscillatorDimensionCount=',
    'privateParticleCrossCouplingEvaluationsPerFrame=',
    'privateParticleActiveNeighborEdgeEvaluations='
)) {
    if ($gpuPrivateParticles.Contains($forbiddenToken)) {
        throw "Public private-particle workload marker must not expose private semantic token: $forbiddenToken"
    }
}
if ($gpuPrivateParticles -notmatch '(?s)gpu_timestamp_tracker\.write_compute_stage_start\(\s*device,\s*cmd,\s*frame_slot,\s*GpuTimestampStage::PrivateParticleComputePostDispatchSync,\s*\);\s*let compute_to_sort') {
    throw "Post-dispatch synchronization timestamp must begin at COMPUTE_SHADER"
}
if ($xrVulkanSurface -notmatch '(?s)\}\s*else\s*\{\s*for stage in \[\s*GpuTimestampStage::PrivateParticleCompute,\s*GpuTimestampStage::PrivateParticleComputeDispatch,\s*GpuTimestampStage::PrivateParticleComputePostDispatchSync,\s*\]\s*\{\s*gpu_timestamp_tracker\.write_disabled_stage\(vk_device,\s*cmd,\s*frame_slot,\s*stage\);\s*\}\s*GpuPrivateParticleFrameStats::unavailable\(\)') {
    throw "Renderer-unavailable path must explicitly write disabled compute, dispatch, and post-sync queries"
}
if ($xrVulkanSurface -notmatch '(?s)if let Some\(stage\) = main_full_res_draw_stage \{\s*gpu_timestamp_tracker\.write_stage_start\(device,\s*cmd,\s*frame_slot,\s*stage\);\s*\}\s*renderer\.record_overlay_eye_main_particles\(.*?if let Some\(stage\) = main_full_res_draw_stage \{\s*gpu_timestamp_tracker\.write_stage_end\(device,\s*cmd,\s*frame_slot,\s*stage\);\s*\}\s*if let Some\(stage\) = direct_draw_stage \{\s*gpu_timestamp_tracker\.write_stage_end\(device,\s*cmd,\s*frame_slot,\s*stage\);\s*\}') {
    throw "Main full-resolution inner timestamp must close before its outer draw span"
}
if ($xrVulkanSurface -notmatch '(?s)if let Some\(stage\) = tracer_half_res_draw_stage \{\s*gpu_timestamp_tracker\.write_stage_start\(device,\s*cmd,\s*frame_slot,\s*stage\);\s*\}\s*if let Some\(renderer\) = gpu_private_particle_renderer \{\s*renderer\.record_half_res_offscreen_eye\(.*?if let Some\(stage\) = tracer_half_res_draw_stage \{\s*gpu_timestamp_tracker\.write_stage_end\(device,\s*cmd,\s*frame_slot,\s*stage\);\s*\}\s*if let Some\(stage\) = half_res_draw_stage \{\s*gpu_timestamp_tracker\.write_stage_end\(device,\s*cmd,\s*frame_slot,\s*stage\);\s*\}') {
    throw "Tracer half-resolution inner timestamp must close before its outer draw span"
}
foreach ($requiredWorkloadExpression in @(
    'if self.anchor_echo_draw_echo_count == 0 {',
    'privateParticleDrawBudgetCapacityInstances=',
    'privateParticleDrawBudgetCapacityRows='
)) {
    if (-not $gpuPrivateParticles.Contains($requiredWorkloadExpression)) {
        throw "Effective-runtime workload marker is missing required capacity/visit expression: $requiredWorkloadExpression"
    }
}
if ($gpuPrivateParticles -notmatch '(?s)let tracer_output_slots_capacity = self\s*\.particle_count\s*\.saturating_mul\(self\.tracer_draw_slots_capacity\);') {
    throw "Tracer output capacity must be derived from allocated per-particle slots"
}

Assert-ContainsLiteralTokens "$gpuPrivateParticles`n$xrVulkanSurface`n$nativeRendererTiming`n$nativeRendererOptionSurface`n$nativeBuildScript`n$privateParticlesVertex`n$privateParticlesFragment`n$privateParticlesOffscreenCompositeVertex`n$privateParticlesOffscreenCompositeFragment" @(
    'private_particles_offscreen_composite.vert.glsl',
    'private_particles_offscreen_composite.frag.glsl',
    'private_particles_offscreen_composite.vert.spv',
    'private_particles_offscreen_composite.frag.spv',
    'record_half_res_offscreen_eye',
    'record_half_res_composite_eye',
    'record_overlay_eye_main_particles',
    'half_res_offscreen_tracers_only_active',
    'stats.tracer_draw_count, stats.particle_count',
    'first_instance',
    'PrivateParticleOffscreenResources',
    'create_offscreen_render_pass',
    'create_offscreen_composite_pipeline',
    'resources.particle_pipeline_additive',
    'resources.particle_pipeline_alpha_over',
    'resources.composite_pipeline_additive',
    'resources.composite_pipeline_alpha_over',
    'PrivateParticleTransparencyBlendMode',
    'PrivateParticleMaterialPreset',
    'PrivateParticleRenderExperimentPreset',
    'private_particle_material_effective_marker_fields',
    'private_particle_render_experiment_marker_fields',
    'privateParticleMaterialPresetEffective=',
    'privateParticleRenderExperimentPresetEffective=',
    'privateParticleRenderExperimentGeometryEffective=',
    'static-ring-annulus-12',
    'STATIC_RING_ANNULUS_SEGMENTS',
    'privateParticleMaterialBlendMode=',
    'privateParticleMaterialPipelines=additive,alpha-over',
    'material_blend_mode',
    'rgb *= coverage_alpha',
    'projection_render_pass',
    '.usage(vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::SAMPLED)',
    '.final_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)',
    '.dst_stage_mask(vk::PipelineStageFlags::FRAGMENT_SHADER)',
    '.dst_access_mask(vk::AccessFlags::SHADER_READ)',
    'vk::DescriptorType::COMBINED_IMAGE_SAMPLER',
    '.image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)',
    'vk::Filter::LINEAR',
    'width: full_extent.width.div_ceil(2).max(1)',
    'height: full_extent.height.div_ceil(2).max(1)',
    'privateParticleOffscreenMode={}',
    '"half-resolution"',
    'privateParticleRenderPath={}',
    '"half-resolution-offscreen-accumulation"',
    'privateParticleOffscreenResourceKind=half-resolution-color-targets',
    'privateParticleOffscreenHalfResTracersOnly=',
    'privateParticleOffscreenBillboardPolicy=',
    'tracers-half-res-main-full-res',
    'half-resolution-tracer-accumulation-main-full-resolution',
    'privateParticleOffscreenTargetExtent={}x{}',
    'PrivateParticleHalfResDrawLeftEye',
    'PrivateParticleHalfResDrawRightEye',
    'PrivateParticleHalfResCompositeLeftEye',
    'PrivateParticleHalfResCompositeRightEye',
    'PrivateParticleMainFullResDrawLeftEye',
    'PrivateParticleMainFullResDrawRightEye',
    'PrivateParticleTracerHalfResDrawLeftEye',
    'PrivateParticleTracerHalfResDrawRightEye',
    'privateParticleHalfResDrawGpuMs',
    'privateParticleHalfResCompositeGpuMs',
    'privateParticleMainFullResDrawGpuMs',
    'privateParticleMainFullResDrawTimingAvailable',
    'privateParticleMainFullResDrawTimingScope=mixed-path-main-particles-only-full-resolution-draw-command-region',
    'privateParticleTracerHalfResDrawGpuMs',
    'privateParticleTracerHalfResDrawTimingAvailable',
    'privateParticleTracerHalfResDrawTimingScope=mixed-path-tracer-instances-only-offscreen-half-resolution-draw-command-region',
    'privateParticleHalfResTimingScope=offscreen-render-pass-and-projection-composite',
    'layout(set = 0, binding = 0) uniform sampler2D u_private_particles_offscreen',
    'out_color = texture(u_private_particles_offscreen, v_uv)',
    'cmd_draw(cmd, 3, 1, 0, 0)'
) "private particle half-resolution offscreen accumulation"

foreach ($counter in @(
    'camera_frames_acquired',
    'hardware_buffer_imports',
    'hardware_buffer_cache_hits',
    'hardware_buffer_cache_misses',
    'guide_graph_renders',
    'guide_graph_cache_hits',
    'sdf_field_updates',
    'private_layer_invocations',
    'xr_frames_submitted',
    'stale_frames'
)) {
    if ($nativeCamera -notmatch $counter -or $nativeLib -notmatch $counter) {
        throw "Rust native timing scaffold missing counter: $counter"
    }
}

foreach ($token in @(
    'hwbNativeImportReady=true',
    'vulkanExternalImportReady=false',
    'finalExternalHwbSamples=0',
    'guideTextureSamples=1',
    'openxrProjectionLayer=runtime-submit',
    'openxrSubmitReady=false'
)) {
    if ($nativeLib -notmatch $token -and $nativeCamera -notmatch $token) {
        throw "Rust native renderer scaffold missing token: $token"
    }
}

Write-Host "Rusty Quest native renderer OpenXR/Vulkan static validation passed"
