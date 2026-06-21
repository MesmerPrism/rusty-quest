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

$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$nativeCamera = Read-RequiredText (Join-Path $srcRoot "native_camera.rs") "native camera"
$nativeRendererTiming = Read-RequiredText (Join-Path $srcRoot "native_renderer_timing.rs") "native renderer timing"
$privateExtensionSlot = Read-RequiredText (Join-Path $srcRoot "private_extension_slot.rs") "private extension slot"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_camera_options.rs") "native renderer camera options"),
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
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "openxr_passthrough_style.rs") "OpenXR passthrough style helper"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\replay_visual_stats.rs") "xr_vulkan replay visual stats"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard")
) -join "`n"

Assert-ContainsTokens "$xrVulkanSurface`n$nativeRendererOptionSurface`n$nativeRendererTiming`n$privateExtensionSlot`n$nativeCamera" @(
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
    'handMeshVisualPath=recorded-compact-joint-gpu-skinned-resident-triangle-draw',
    'CompactHandInputSourceMode',
    'recorded-replay-visual-proof',
    'NativeRendererRenderMode',
    'debug.rustyquest.native_renderer.render.mode',
    'native-passthrough-style-only',
    'native-passthrough-media-only',
    'native-passthrough-graft-only',
    'solid-black-hands-and-grafts',
    'solid-black-openxr-hands-anchor-particles',
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
    'XR_FB_passthrough',
    'fb_passthrough',
    'NativePassthroughRuntime',
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
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled',
    'debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale',
    'debug.rustyquest.native_renderer.hand_mesh.real_hands.visible',
    'solidBlackRealHandMeshVisible',
    'liveHandMeshVisualAcceptance=pending-repeat-headset-visual-proof',
    'dynamicSdfReady=pending',
    'debug.rustyquest.native_renderer.sdf.visual.enabled',
    'status=skinning-active-sdf-overlay-deferred reason=property-disabled',
    'GpuSdfFieldRenderer',
    'GpuSdfFieldFrameStats',
    'cpuSdfPerFrame=false',
    'xr-vulkan-probe',
    'vulkan-probe'
) "OpenXR/Vulkan runtime route"

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
