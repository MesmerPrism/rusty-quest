param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
} else {
    $RepoRoot = Resolve-Path $RepoRoot
}

function Read-RepoText {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path $path)) {
        throw "Missing display-composite static input: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle,
        [Parameter(Mandatory=$true)][string]$Label
    )
    if (-not $Text.Contains($Needle)) {
        throw "Display-composite static check missing $Label token: $Needle"
    }
}

$manifest = Read-RepoText "apps\native-renderer-android\AndroidManifest.xml"
$activity = Read-RepoText "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\ControlPanelActivity.java"
$service = Read-RepoText "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\DisplayCompositeProjectionService.java"
$nativeLib = Read-RepoText "apps\native-renderer-android\native\src\lib.rs"
$nativeAhb = Read-RepoText "apps\native-renderer-android\native\src\android_hardware_buffer.rs"
$ahbVulkan = Read-RepoText "apps\native-renderer-android\native\src\ahardware_buffer_vulkan.rs"
$nativeOptions = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_display_composite_options.rs"
$nativeStream = Read-RepoText "apps\native-renderer-android\native\src\display_composite_native_stream.rs"
$projectionMetadata = Read-RepoText "apps\native-renderer-android\native\src\display_composite_projection_metadata.rs"
$captureExport = Read-RepoText "apps\native-renderer-android\native\src\display_composite_capture_export.rs"
$feedbackRenderer = Read-RepoText "apps\native-renderer-android\native\src\display_composite_feedback.rs"
$xrVulkan = Read-RepoText "apps\native-renderer-android\native\src\xr_vulkan.rs"
$nativeBuild = Read-RepoText "apps\native-renderer-android\native\build.rs"
$feedbackVertexShader = Read-RepoText "apps\native-renderer-android\native\shaders\display_composite_feedback.vert.glsl"
$feedbackFragmentShader = Read-RepoText "apps\native-renderer-android\native\shaders\display_composite_feedback.frag.glsl"
$recursiveFeedbackFragmentShader = Read-RepoText "apps\native-renderer-android\native\shaders\display_composite_recursive_feedback.frag.glsl"
$nativeProperties = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_properties.rs"
$projectionTargetState = Read-RepoText "apps\native-renderer-android\native\src\projection_target_state.rs"
$propertyManifest = Read-RepoText "fixtures\native-renderer\native-renderer-property-manifest.json"
$validProfile = Read-RepoText "fixtures\runtime-profiles\quest-native-renderer-display-composite-feedback.profile.json"
$captureOnlyProfile = Read-RepoText "fixtures\runtime-profiles\quest-native-renderer-display-composite-capture-only.profile.json"
$damagedProfile = Read-RepoText "fixtures\damaged\native-renderer-display-composite-invalid-mode.profile.json"
$grantScript = Read-RepoText "tools\Grant-NativeRendererPermissions.ps1"
$smokeWrapper = Read-RepoText "tools\Invoke-NativeRendererDisplayCompositeSmoke.ps1"
$profileMatrix = Read-RepoText "tools\Test-NativeRendererProfileMatrix.ps1"
$parityTool = Read-RepoText "tools\check_native_renderer_property_parity.py"

foreach ($token in @(
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android:foregroundServiceType=""mediaProjection""",
    ".DisplayCompositeProjectionService"
)) {
    Assert-Contains -Text $manifest -Needle $token -Label "manifest"
}

foreach ($token in @(
    "ACTION_REQUEST_DISPLAY_COMPOSITE_CAPTURE",
    "MediaProjectionManager",
    "createScreenCaptureIntent",
    "REQUEST_DISPLAY_COMPOSITE_CAPTURE",
    "startActivityForResult",
    "startForegroundService",
    "launchImmersiveRenderer",
    "android.app.NativeActivity",
    "display_composite_request_token"
)) {
    Assert-Contains -Text $activity -Needle $token -Label "activity"
}

foreach ($token in @(
    "nativeCreateDisplayCompositeSurface",
    "nativeStopDisplayCompositeStream",
    "Surface",
    "VirtualDisplay",
    "VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR",
    "nativeDisplayCompositeLifecycleEvent",
    "FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"
)) {
    Assert-Contains -Text $service -Needle $token -Label "service"
}

foreach ($forbidden in @("ImageReader.newInstance", "getHardwareBuffer", "copyPixelsFromBuffer", "ByteBuffer", "getPlanes(", "nativeDisplayCompositeHardwareBufferFrame")) {
    if ($service.Contains($forbidden)) {
        throw "Display-composite service must not use CPU pixel-copy path token: $forbidden"
    }
}

foreach ($token in @(
    "mod ahardware_buffer_vulkan",
    "mod android_hardware_buffer",
    "mod display_composite_native_stream",
    "mod display_composite_projection_metadata",
    "mod display_composite_feedback",
    "mod display_composite_capture_export",
    "mod native_renderer_display_composite_options",
    """display-composite""",
    "display_composite_settings.marker_fields()"
)) {
    Assert-Contains -Text $nativeLib -Needle $token -Label "native lib"
}

foreach ($token in @(
    "displayCompositeStream=display_composite",
    "displayCompositeCaptureAuthority=android-mediaprojection",
    "displayCompositeRawCamera=false",
    "displayCompositePassthroughTexture=false",
    "displayCompositeEnvironmentDepth=false",
    "displayCompositeGeometryWitness=false",
    "displayCompositeHighRateJsonPayload={}",
    "displayCompositeTransport=ndk-aimage-reader-ahardwarebuffer",
    "nativeImageReader=true",
    "javaHardwareBufferBridge=false",
    "cpuPixelCopy=false",
    "displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image"
)) {
    Assert-Contains -Text $nativeOptions -Needle $token -Label "native options"
}

foreach ($token in @(
    "display-composite-capture-export",
    "media-projection-frame.rgba",
    "gpu-sampled-frame.rgba",
    "AHardwareBuffer_lock",
    "AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN",
    "renderedIntoView=false",
    "write_gpu_sampled_frame"
)) {
    Assert-Contains -Text ($captureExport + $nativeStream + $feedbackRenderer + $xrVulkan) -Needle $token -Label "display-composite capture export"
}

foreach ($token in @(
    "AImageReader_newWithUsage",
    "AImageReader_getWindow",
    "ANativeWindow_toSurface",
    "AImageReader_acquireLatestImage",
    "AImage_getHardwareBuffer",
    "AIMAGE_FORMAT_RGBA_8888",
    "AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT",
    "stream=display_composite",
    "sourceAuthority=android-mediaprojection",
    "rawCamera=false",
    "passthroughTexture=false",
    "environmentDepth=false",
    "geometryWitness=false",
    "highRateJsonPayload=false",
    "nativeImageReader=true",
    "javaHardwareBufferBridge=false",
    "latestFramePublished=true"
)) {
    Assert-Contains -Text $nativeStream -Needle $token -Label "native stream"
}

foreach ($token in @(
    "AndroidHardwareBufferHandle",
    "AHardwareBuffer_acquire",
    "AHardwareBuffer_release",
    "AHardwareBuffer_describe",
    "AHardwareBuffer_getId"
)) {
    Assert-Contains -Text $nativeAhb -Needle $token -Label "shared AHardwareBuffer helper"
}

foreach ($token in @(
    "AhbVulkanDevice",
    "AhbVulkanFormatKey",
    "query_ahb_vulkan_import_properties",
    "import_ahb_sampled_image",
    "transition_ahb_sampled_image_to_shader_read",
    "AndroidHardwareBufferFormatPropertiesANDROID",
    "ExternalMemoryImageCreateInfo",
    "ImportAndroidHardwareBufferInfoANDROID",
    "MemoryDedicatedAllocateInfo",
    "create_ahb_sampler_ycbcr_conversion",
    "ExternalFormatANDROID",
    "SamplerYcbcrConversionCreateInfo",
    "SamplerYcbcrConversionInfo",
    "ANDROID_HARDWARE_BUFFER_ANDROID"
)) {
    Assert-Contains -Text $ahbVulkan -Needle $token -Label "shared AHardwareBuffer Vulkan import helper"
}

foreach ($token in @(
    "rusty.quest.native_renderer.display_composite_projection_metadata.v1",
    "sourceResolution={}x{}",
    "contentAspectRatio",
    "sourceUvRect",
    "leftTargetScreenUvRect",
    "rightTargetScreenUvRect",
    "dataInputMetadataAuthority=display-composite-stream",
    "downstreamProjectionScaleAuthority=projection-target-state"
)) {
    Assert-Contains -Text $projectionMetadata -Needle $token -Label "display-composite projection metadata"
}

foreach ($token in @(
    "DisplayCompositeFeedbackRenderer",
    "latest_display_composite_frame",
    "query_ahb_vulkan_import_properties",
    "import_ahb_sampled_image",
    "transition_ahb_sampled_image_to_shader_read",
    "display-composite-feedback-import",
    "display-composite-feedback-resources",
    "display-composite-recursive-feedback-resources",
    "displayCompositeFeedbackRendered",
    "displayCompositeGpuImportReady=true",
    "displayCompositeExternalFormatSampling=true",
    "displayCompositeSamplerYcbcrConversion=true",
    "displayCompositeRecursiveFeedbackSource=media-projection-current-frame-clean",
    "displayCompositeRecursiveFeedbackPreviousBlend=false",
    "displayCompositeRecursiveFeedbackBorderOpacity=",
    "displayCompositeFinalBorderOpacityLeft=",
    "displayCompositeFinalPlaneOpacity=",
    "displayCompositeFinalAlphaMode=premultiplied-openxr-projection-layer",
    "displayCompositeCompositorRecaptureAssumed=false",
    "combined-immutable-sampler-ycbcr-conversion",
    "displayCompositeGpuAdoptionPath=android-mediaprojection-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image"
)) {
    Assert-Contains -Text ($feedbackRenderer + $nativeLib + $xrVulkan) -Needle $token -Label "display-composite feedback renderer"
}

foreach ($token in @(
    "display_composite_feedback.vert.glsl",
    "display_composite_feedback.frag.glsl",
    "display_composite_recursive_feedback.frag.glsl",
    "display_composite_feedback.vert.spv",
    "display_composite_feedback.frag.spv",
    "display_composite_recursive_feedback.frag.spv"
)) {
    Assert-Contains -Text $nativeBuild -Needle $token -Label "display-composite shader build"
}

foreach ($token in @(
    "u_display_composite",
    "source_uv_rect",
    "target_rect",
    "flip_y"
)) {
    Assert-Contains -Text ($feedbackVertexShader + $feedbackFragmentShader) -Needle $token -Label "display-composite feedback shader"
}

foreach ($token in @(
    "u_display_composite",
    "u_previous_feedback",
    "source_uv_rect",
    "inset_scale",
    "previous_alpha"
)) {
    Assert-Contains -Text $recursiveFeedbackFragmentShader -Needle $token -Label "display-composite recursive feedback shader"
}

foreach ($propertyName in @(
    "debug.rustyquest.native_renderer.render.mode",
    "debug.rustyquest.native_renderer.camera.output",
    "debug.rustyquest.native_renderer.guide.blur.enabled",
    "debug.rustyquest.native_renderer.hand_mesh.input.source",
    "debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled",
    "debug.rustyquest.native_renderer.hand_mesh.real_hands.visible",
    "debug.rustyquest.native_renderer.hand_anchor_particles.enabled",
    "debug.rustyquest.native_renderer.sdf.visual.enabled",
    "debug.rustyquest.native_renderer.environment_depth.mode",
    "debug.rustyquest.native_renderer.stimulus_volume.enabled",
    "debug.rustyquest.native_renderer.private_layer.enabled",
    "debug.rustyquest.native_renderer.projection.target.controls",
    "debug.rustyquest.native_renderer.projection.target.scale",
    "debug.rustyquest.native_renderer.projection.target.offset.x.uv",
    "debug.rustyquest.native_renderer.projection.target.offset.y.uv",
    "debug.rustyquest.native_renderer.projection.target.joystick.controls",
    "debug.rustyquest.native_renderer.projection.target.breath.bridge.mode",
    "debug.rustyquest.native_renderer.display_composite.enabled",
    "debug.rustyquest.native_renderer.display_composite.source",
    "debug.rustyquest.native_renderer.display_composite.mode",
    "debug.rustyquest.native_renderer.display_composite.width",
    "debug.rustyquest.native_renderer.display_composite.height",
    "debug.rustyquest.native_renderer.display_composite.max_images",
    "debug.rustyquest.native_renderer.display_composite.fps_cap",
    "debug.rustyquest.native_renderer.display_composite.feedback.enabled",
    "debug.rustyquest.native_renderer.display_composite.feedback.projection",
    "debug.rustyquest.native_renderer.display_composite.high_rate_json_payload"
)) {
    $registryText = if ($propertyName.Contains(".projection.target.")) {
        $projectionTargetState
    } else {
        $nativeProperties
    }
    Assert-Contains -Text $registryText -Needle $propertyName -Label "native property registry"
    Assert-Contains -Text $propertyManifest -Needle $propertyName -Label "property manifest"
    Assert-Contains -Text $validProfile -Needle $propertyName -Label "valid profile"
}

foreach ($token in @(
    "native_renderer_display_composite_options.rs",
    "profile.quest.native_renderer.display_composite_feedback",
    "native-passthrough-media-only",
    "cameraOutputMode=disabled",
    "guideGraphBlurEnabled=false",
    "cameraRuntimeMode=skipped-native-passthrough-media-only",
    "cameraProjectionPath=disabled-native-passthrough-media-only",
    "handMeshGraftCopiesEnabled=false",
    "environmentDepthMode=disabled",
    "stimulusVolumeEnabled=false",
    "gpu-feedback-diagnostic",
    "gpu-recursive-feedback-diagnostic",
    "gpu-readback-diagnostic",
    "metadata-target-screen-uv",
    "metadata-target-guide-texture",
    "cpu-rgba-json",
    "quest-native-renderer-display-composite-feedback.profile.json",
    "quest-native-renderer-display-composite-capture-only.profile.json",
    "native-renderer-display-composite-invalid-mode.profile.json"
)) {
    Assert-Contains -Text ($propertyManifest + $validProfile + $captureOnlyProfile + $damagedProfile + $profileMatrix + $parityTool) -Needle $token -Label "profile contract"
}

foreach ($token in @(
    "PROJECT_MEDIA",
    "cmd",
    "appops",
    "set",
    "allow",
    "default",
    '"-s"'
)) {
    Assert-Contains -Text $grantScript -Needle $token -Label "permission pregrant"
}

foreach ($token in @(
    "Invoke-NativeRendererDisplayCompositeSmoke.ps1",
    "display_composite_request_token",
    "GrantMediaProjectionAppOp",
    "ResetMediaProjectionAppOp",
    "nativePassthroughLayerActive=true",
    "cameraRuntimeMode=skipped-native-passthrough-media-only",
    "io.github.mesmerprism.rustyquest.native_renderer.action.REQUEST_DISPLAY_COMPOSITE_CAPTURE",
    "display-composite-native-stream",
    "display-composite-ahardware-buffer",
    "display-composite-projection-metadata",
    "display-composite-feedback",
    "Assert-LogcatContains",
    "nativeImageReader=true",
    "javaHardwareBufferBridge=false",
    "cpuPixelCopy=false",
    "screencap"
)) {
    Assert-Contains -Text $smokeWrapper -Needle $token -Label "display-composite smoke wrapper"
}

Write-Output "Native renderer display-composite static checks passed."
