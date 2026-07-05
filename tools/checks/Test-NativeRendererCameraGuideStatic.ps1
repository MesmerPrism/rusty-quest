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
$shaderRoot = Join-Path $nativeRoot "shaders"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer camera/guide static file ($Label): $Path"
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
            throw "Native renderer camera/guide static check failed for ${Label}: missing token: $token"
        }
    }
}

$nativeBuildRs = Read-RequiredText (Join-Path $nativeRoot "build.rs") "native build script"
$nativeLib = Read-RequiredText (Join-Path $srcRoot "lib.rs") "native lib"
$nativeCamera = Read-RequiredText (Join-Path $srcRoot "native_camera.rs") "native camera"
$nativeAhb = Read-RequiredText (Join-Path $srcRoot "android_hardware_buffer.rs") "shared Android hardware buffer helper"
$ahbVulkan = Read-RequiredText (Join-Path $srcRoot "ahardware_buffer_vulkan.rs") "shared Android hardware buffer Vulkan helper"
$nativeCameraMetadata = Read-RequiredText (Join-Path $srcRoot "native_camera_metadata.rs") "native camera metadata"
$nativeCameraProfiles = Read-RequiredText (Join-Path $srcRoot "native_camera_profiles.rs") "native camera profiles"
$nativeCameraReaderSelection = Read-RequiredText (Join-Path $srcRoot "native_camera_reader_selection.rs") "native camera reader selection"
$acameraSys = Read-RequiredText (Join-Path $srcRoot "acamera_sys.rs") "ACamera sys bindings"
$cameraProjection = Read-RequiredText (Join-Path $srcRoot "camera_projection.rs") "camera projection"
$cameraProjectionMetadata = Read-RequiredText (Join-Path $srcRoot "camera_projection_metadata.rs") "camera projection metadata"
$guideBlurGraph = Read-RequiredText (Join-Path $srcRoot "guide_blur_graph.rs") "guide blur graph"
$nativeRendererOptionSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_camera_options.rs") "native renderer camera options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_properties.rs") "native renderer properties"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_property_values.rs") "native renderer property values"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_environment_depth_options.rs") "native renderer environment-depth options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_hand_anchor_particle_options.rs") "native renderer hand-anchor particle options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_projection_border_stretch_options.rs") "native renderer projection border stretch options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_stimulus_volume_options.rs") "native renderer stimulus volume options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_visual_options.rs") "native renderer visual options"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options.rs") "native renderer options facade"),
    (Read-RequiredText (Join-Path $srcRoot "native_renderer_options_tests.rs") "native renderer options tests")
) -join "`n"
$cameraProjectionFragment = Read-RequiredText `
    (Join-Path $shaderRoot "camera_projection.frag.glsl") `
    "camera projection fragment shader"
$guideBlurDownsampleFragment = Read-RequiredText `
    (Join-Path $shaderRoot "guide_blur_downsample.frag.glsl") `
    "guide blur downsample shader"
$guideBlurFragment = Read-RequiredText `
    (Join-Path $shaderRoot "guide_blur_5tap.frag.glsl") `
    "guide blur 5-tap shader"
$guideProjectionFragment = Read-RequiredText `
    (Join-Path $shaderRoot "guide_projection.frag.glsl") `
    "guide projection fragment shader"
$guideVideoProjectionFragment = Read-RequiredText `
    (Join-Path $shaderRoot "guide_video_projection.frag.glsl") `
    "guide/video projection fragment shader"
$cameraLumaDiagnosticShader = Read-RequiredText `
    (Join-Path $shaderRoot "camera_luma_diagnostic.comp.glsl") `
    "camera luma diagnostic shader"
$xrVulkanSurface = @(
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan.rs") "xr_vulkan facade"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\replay_visual_stats.rs") "xr_vulkan replay visual stats"),
    (Read-RequiredText (Join-Path $srcRoot "xr_vulkan\scorecard.rs") "xr_vulkan scorecard")
) -join "`n"
$directHwbCameraQualityProfile = Read-RequiredText `
    (Join-Path $repoRootPath "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-camera-quality.profile.json") `
    "direct-HWB camera quality profile"
$directHwbLowNoiseRecord30Profile = Read-RequiredText `
    (Join-Path $repoRootPath "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-noise-record-30.profile.json") `
    "direct-HWB low-noise record profile"
$hwbPeripheralStretchProfile = Read-RequiredText `
    (Join-Path $repoRootPath "fixtures\runtime-profiles\quest-native-renderer-hwb-peripheral-stretch.profile.json") `
    "HWB peripheral stretch profile"
$hwbVideoBorderBlendProfile = Read-RequiredText `
    (Join-Path $repoRootPath "fixtures\runtime-profiles\quest-native-renderer-hwb-video-border-blend.profile.json") `
    "HWB video border blend profile"
$fixture = Read-RequiredText `
    (Join-Path $repoRootPath "fixtures\native-renderer\native-hwb-blur-sdf-public.plan.json") `
    "native HWB blur/SDF public plan fixture"

foreach ($token in @(
    'rusty\.quest\.native_renderer_plan\.v1',
    'quest-native-openxr-vulkan',
    'camera2-ahardwarebuffer-vulkan-external',
    'combined-immutable-sampler-ycbcr-conversion',
    'peripheral-stretch-border',
    'rusty\.optics\.peripheral_stretch_border\.layer\.v1'
)) {
    if ($fixture -notmatch $token -and $nativeLib -notmatch $token) {
        throw "Native renderer public plan path missing token: $token"
    }
}
if ($fixture -notmatch '"left": "50"' -or $fixture -notmatch '"right": "51"') {
    throw "Native renderer plan fixture does not name camera ids 50 and 51."
}

Assert-ContainsTokens "$nativeLib`n$cameraProjection`n$cameraProjectionMetadata`n$xrVulkanSurface" @(
    'camera_projection_metadata',
    'rusty\.quest\.native_renderer\.camera_projection_metadata\.v1',
    'rusty\.optics\.target_screen_footprint\.v1',
    'target-local-raster',
    'RUSTY_QUEST_NATIVE_RENDERER_CAMERA_LEFT_TARGET_SCREEN_UV_RECT',
    'debug\.rustyquest\.native_renderer\.camera\.left\.target\.screen\.uv\.rect',
    'targetCoordinateSpace=display-eye-screen-uv',
    'metadataDrivenTargetFootprint=true',
    'sourceSampleYFlip',
    'sourceSampleTransform',
    'sourceSampleTransformStage=post-homography-pre-texture-sample'
) "camera projection metadata route"

Assert-ContainsTokens $cameraProjectionFragment @(
    'target_rect',
    'local_uv',
    'mix\(local_uv\.y, 1\.0 - local_uv\.y, flip_y\)',
    'discard',
    'border_color'
) "camera projection shader metadata target"
if ($cameraProjectionFragment -match 'vec2\(v_uv\.x,\s*1\.0\s*-\s*v_uv\.y\)') {
    throw "Native camera projection shader must not hard-code full-screen source Y flip."
}

Assert-ContainsTokens "$nativeBuildRs`n$nativeLib`n$nativeCamera`n$cameraProjection`n$guideBlurGraph`n$nativeRendererOptionSurface`n$guideBlurDownsampleFragment`n$guideBlurFragment`n$guideProjectionFragment`n$xrVulkanSurface" @(
    'mod guide_blur_graph',
    'GuideBlurGraphRenderer',
    'GuideBlurGraphFrameStats',
    'guide_blur_downsample.frag.glsl',
    'guide_blur_5tap.frag.glsl',
    'guide_projection.frag.glsl',
    'NativeGuideGraphResolution',
    'PROP_GUIDE_RESOLUTION',
    'debug\.rustyquest\.native_renderer\.guide\.resolution',
    'guideResolutionProperty',
    'guideGraphResolutionPolicy',
    'guideGraphPath=',
    'low-resolution-two-phase-5tap-blur',
    'low-resolution-downsample-no-blur',
    'camera-resolution-two-phase-5tap-blur',
    'camera-resolution-downsample-no-blur',
    'guideGraphBlurEnabled',
    'guideGraphReady',
    'guideGraphDownsampleResolution=',
    'guideGraphHorizontalTaps',
    'guideGraphVerticalTaps',
    'guideGraphFinalProjectionSource=guide-texture',
    'guideGraphFinalExternalHwbSamples',
    'actualFinalExternalHwbSamples',
    'actualGuideTextureSamples',
    'cameraProjectionPath=metadata-target-guide-texture-final',
    'metadata-target-guide-texture-peripheral-stretch-final',
    'record_guide_graph_render',
    'record_guide_graph_cache_hit',
    'final_downsample_descriptor_set',
    'finalExternalHwbSamples=0',
    'guideTextureSamples=1'
) "guide blur graph route"

Assert-ContainsTokens $guideBlurDownsampleFragment @(
    'u_camera_left',
    'u_camera_right',
    'mix\(v_uv\.y, 1\.0 - v_uv\.y, flip_y\)'
) "guide downsample shader"

Assert-ContainsTokens "$nativeRendererOptionSurface`n$nativeCamera`n$nativeCameraMetadata`n$nativeCameraProfiles`n$nativeCameraReaderSelection`n$acameraSys`n$cameraProjection`n$cameraProjectionFragment`n$cameraLumaDiagnosticShader`n$nativeBuildRs`n$xrVulkanSurface`n$directHwbCameraQualityProfile`n$directHwbLowNoiseRecord30Profile" @(
    'PROP_CAMERA_OUTPUT_MODE',
    'PROP_CAMERA_YCBCR_MODE',
    'PROP_CAMERA_RESOLUTION_PROFILE',
    'PROP_CAMERA_READER_MAX_IMAGES',
    'PROP_CAMERA_QUALITY_PROFILE',
    'PROP_CAMERA_SYNC_MODE',
    'PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED',
    'PROP_CAMERA_STEREO_PAIRING',
    'PROP_SWAPCHAIN_COLOR_FORMAT_MODE',
    'PROP_CAMERA_DIRECT_BORDER_OPACITY',
    'debug\.rustyquest\.native_renderer\.camera\.output',
    'debug\.rustyquest\.native_renderer\.camera\.ycbcr\.mode',
    'debug\.rustyquest\.native_renderer\.camera\.resolution',
    'debug\.rustyquest\.native_renderer\.camera\.reader_max_images',
    'debug\.rustyquest\.native_renderer\.camera\.quality_profile',
    'debug\.rustyquest\.native_renderer\.camera\.sync_mode',
    'debug\.rustyquest\.native_renderer\.camera\.luma_diagnostic\.enabled',
    'debug\.rustyquest\.native_renderer\.camera\.stereo_pairing',
    'debug\.rustyquest\.native_renderer\.swapchain\.color_format',
    'debug\.rustyquest\.native_renderer\.camera\.direct_border\.opacity',
    'NativeCameraOutputMode',
    'NativeCameraYcbcrMode',
    'NativeCameraResolutionProfile',
    'camera_reader_max_images',
    'NativeCameraQualityProfile',
    'NativeCameraSyncMode',
    'NativeSwapchainColorFormatMode',
    'forced-bt601-limited-cpuyuv-reference',
    'effectiveYcbcrModel=',
    'formatFeaturesRaw=',
    'chromaFilter=',
    'camera-request-profile',
    'camera_luma_diagnostic\.comp\.glsl',
    'camera_luma_diagnostic\.comp\.spv',
    'camera-luma-diagnostic',
    'cameraLumaDiagnosticRequested=',
    'cameraLumaDiagnosticReady=',
    'cameraLumaDiagnosticPath=native-vulkan-compute-direct-hwb-luma-range',
    'leftLumaMean=',
    'rightLumaHighFrequencyRatio=',
    'AImage_getDataSpace',
    'imageDataspace=',
    'imageDataspaceStatus=',
    'leftImageDataspace=',
    'rightImageDataspaceStatus=',
    'TEMPLATE_RECORD',
    'direct-low-noise-record-30',
    'template=',
    'template=record',
    'selectedAeFpsRange=',
    'nearest-supported',
    'ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS',
    'privateOutputMinFrameDurations=',
    'readerSelectionReason=',
    'readerMinFrameDurationNs=',
    'readerTargetFpsFeasible=',
    'camera-capture-result',
    'ACAMERA_SENSOR_TIMESTAMP',
    'ACAMERA_CONTROL_AE_STATE',
    'ACAMERA_CONTROL_AWB_STATE',
    'resultSensorTimestampNs=',
    'aeState=',
    'awbState=',
    'captureResultCorrelationStatus=',
    'captureResultDeltaNs=',
    'cameraCaptureResultCorrelationReady=',
    'ResultCorrelationStatus=',
    'scorecard_marker_fields\("left"\)',
    'scorecard_marker_fields\("right"\)',
    'cameraResolutionProfile=',
    'readerMaxImages=',
    'cameraSyncActive=',
    'NativeCameraStereoPairingPolicy',
    'nearest-timestamp',
    'latest-latest',
    'stereoPairingPolicy=',
    'stereoPairingProperty=',
    'AImageReader_acquireLatestImageAsync',
    'AImage_deleteAsync',
    'active-diagnostic-sync-fd-observed-vulkan-semaphore-pending',
    'async-acquire-fence-observed-vulkan-semaphore-pending',
    'acquireFenceFdPresent=',
    'gpu-frame-lease-tracked',
    'gpu-frame-lease-retired',
    'cacheEvictionApplied=',
    'cacheEvictionDeferred=',
    'inFlightSkipCount=',
    'protectedSkipCount=',
    'openxr-swapchain-format',
    'border_opacity',
    'cameraOutputMode=',
    'metadata-target-direct-hwb-forced',
    'directHwbProjectionDiagnostic',
    'privateLayerProjectionEnabled=false',
    'guideProjectionEnabled=false',
    'quest-native-renderer-direct-hwb-camera-quality\.profile\.json'
) "direct-HWB camera quality diagnostic route"

Assert-ContainsTokens $cameraLumaDiagnosticShader @(
    'layout\(local_size_x = 8, local_size_y = 8',
    'atomicAdd\(out_stats\.eye_stats',
    'atomicMax\(out_stats\.eye_stats',
    'quantized_luma',
    'u_camera_left',
    'u_camera_right'
) "camera luma diagnostic shader"

Assert-ContainsTokens $guideBlurFragment @(
    'u_source',
    'v_uv - 2\.0 \* step_uv',
    'rgb \* 0\.2'
) "guide 5-tap blur shader"

Assert-ContainsTokens "$nativeLib`n$nativeRendererOptionSurface`n$guideBlurGraph`n$guideProjectionFragment`n$guideVideoProjectionFragment`n$xrVulkanSurface`n$hwbPeripheralStretchProfile`n$hwbVideoBorderBlendProfile" @(
    'NativeProjectionBorderStretchSettings',
    'debug\.rustyquest\.native_renderer\.processing\.layer',
    'debug\.rustyquest\.native_renderer\.video_border_blend\.mode',
    'debug\.rustyquest\.native_renderer\.projection\.border\.policy',
    'debug\.rustyquest\.native_renderer\.peripheral\.stretch\.inner\.blend\.uv',
    'peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band',
    'peripheralStretchProjectionExteriorMode=target-edge-stretch-with-inner-band-blend',
    'guideProjectionCoverage=full-eye-peripheral-stretch',
    'video-border-blend',
    'guideProjectionCoverage=full-eye-video-border-blend',
    'videoBorderBlendActive=true',
    'videoBorderBlendMode=crossfade',
    'videoBorderBlendCompositor=guide-video-shader-composite',
    'videoBorderBlendShaderCompositeActive=true',
    'videoBorderBlendFormula',
    'videoBorderBlendCostTier',
    'videoBorderBlendSamplePattern',
    'videoBorderBlendTemporalState',
    'peripheralStretchProjectionExteriorMode=video-background-with-inner-band-camera-blend',
    'videoBorderBlendSource=prepared-stereo-video-projection-background',
    'videoBorderBlendCameraSource=guide-texture',
    'cameraProjectionPath=metadata-target-guide-texture-video-border-blend-final',
    'video_border_blend_active',
    'record_video_composite_projection_eye',
    'guide-video-shader-composite',
    'full_extent_scissor',
    'projection_area_rect_edge_uv',
    'peripheral_stretch_blend_weight',
    'target_stretch_effect_region'
) "peripheral stretch border route"

Assert-ContainsTokens $guideVideoProjectionFragment @(
    'u_guide',
    'u_video_projection',
    'GuideVideoProjectionPush',
    'video_source_uv_rect',
    'linear_to_srgb',
    'luma_matched_camera_rgb',
    'chroma_luma_split_rgb',
    'soft_light_rgb',
    'overlay_rgb',
    'screen_rgb',
    'gradient_aware_rgb',
    'two_band_rgb',
    'transition_band_weight'
) "guide/video projection shader"

Assert-ContainsTokens $guideProjectionFragment @(
    'u_guide',
    'target_rect',
    'video_border_blend_active',
    'video_border_weight',
    'discard',
    'border_color'
) "guide projection shader"

Assert-ContainsTokens $nativeBuildRs @(
    'guide_blur_downsample\.frag\.glsl',
    'guide_blur_5tap\.frag\.glsl',
    'guide_projection\.frag\.glsl',
    'guide_video_projection\.frag\.glsl'
) "native shader build script guide entries"

foreach ($token in @(
    'mod ahardware_buffer_vulkan',
    'ACameraManager_create',
    'ACameraManager_getCameraIdList',
    'ACameraManager_openCamera',
    'AImageReader_newWithUsage',
    'AIMAGE_FORMAT_PRIVATE',
    'AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE',
    'AImageReader_setImageListener',
    'AImage_getHardwareBuffer',
    'AHardwareBuffer_acquire',
    'AHardwareBuffer_describe',
    'AHardwareBuffer_getId',
    'AHardwareBuffer_release',
    'ACameraCaptureSession_setRepeatingRequest',
    'plan\.camera_source\.camera_ids\.left',
    'plan\.camera_source\.camera_ids\.right',
    'hwb-frame-acquired',
    'textureUpdateCadence=on-camera-frame',
    'releaseRetireCount',
    'descriptorShape=combined-immutable-sampler-ycbcr-conversion'
)) {
    if ($nativeLib -notmatch $token -and $nativeCamera -notmatch $token -and $nativeAhb -notmatch $token -and $ahbVulkan -notmatch $token -and $acameraSys -notmatch $token) {
        throw "Rust native camera scaffold missing token: $token"
    }
}

Assert-ContainsTokens $cameraProjection @(
    'query_ahb_vulkan_import_properties',
    'import_ahb_sampled_image',
    'transition_ahb_sampled_image_to_shader_read',
    'AhbVulkanSampledImage',
    'REMOTE_BROKER_CAMERA_IMPORT_CACHE_LIMIT',
    'REMOTE_BROKER_CAMERA_STEREO_DESCRIPTOR_LIMIT',
    'remote-broker-mediacodec-bounded',
    'remoteBrokerCameraProjectionBoundedImportCache',
    'is_remote_broker_frame',
    'remote-mediacodec-surface'
) "camera projection shared AHB Vulkan import use"

Assert-ContainsTokens $ahbVulkan @(
    'AhbVulkanDevice',
    'AhbVulkanFormatKey',
    'AndroidHardwareBufferFormatPropertiesANDROID',
    'ExternalMemoryImageCreateInfo',
    'ImportAndroidHardwareBufferInfoANDROID',
    'MemoryDedicatedAllocateInfo',
    'SamplerYcbcrConversionInfo',
    'ANDROID_HARDWARE_BUFFER_ANDROID'
) "shared AHB Vulkan import helper"

Write-Host "Rusty Quest native renderer camera/guide static validation passed"
