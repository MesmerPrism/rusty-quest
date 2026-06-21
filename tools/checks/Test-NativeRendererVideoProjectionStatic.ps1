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
        throw "Missing video-projection static input: $path"
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
        throw "Video-projection static check missing $Label token: $Needle"
    }
}

$nativeLib = Read-RepoText "apps\native-renderer-android\native\src\lib.rs"
$nativeOptions = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_video_projection_options.rs"
$nativeProperties = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_properties.rs"
$nativeStream = Read-RepoText "apps\native-renderer-android\native\src\video_projection_native_stream.rs"
$javaPlayback = Read-RepoText "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\StereoVideoPlayback.java"
$playerBridge = Read-RepoText "apps\native-renderer-android\native\src\video_projection_player_bridge.rs"
$projectionMetadata = Read-RepoText "apps\native-renderer-android\native\src\video_projection_metadata.rs"
$videoRenderer = Read-RepoText "apps\native-renderer-android\native\src\video_projection.rs"
$xrVulkan = Read-RepoText "apps\native-renderer-android\native\src\xr_vulkan.rs"
$guideProjectionShader = Read-RepoText "apps\native-renderer-android\native\shaders\guide_projection.frag.glsl"
$projectionBorderStretchOptions = Read-RepoText "apps\native-renderer-android\native\src\native_renderer_projection_border_stretch_options.rs"
$nativeBuild = Read-RepoText "apps\native-renderer-android\native\build.rs"
$vertexShader = Read-RepoText "apps\native-renderer-android\native\shaders\video_projection.vert.glsl"
$fragmentShader = Read-RepoText "apps\native-renderer-android\native\shaders\video_projection.frag.glsl"
$stageVideo = Read-RepoText "tools\Stage-NativeRendererVideo.ps1"
$videoProjectionDoc = Read-RepoText "docs\NATIVE_VIDEO_PROJECTION.md"
$propertyManifest = Read-RepoText "fixtures\native-renderer\native-renderer-property-manifest.json"
$validProfile = Read-RepoText "fixtures\runtime-profiles\quest-native-renderer-fullscreen-stereo-video.profile.json"
$profileMatrix = Read-RepoText "tools\Test-NativeRendererProfileMatrix.ps1"
$parityTool = Read-RepoText "tools\check_native_renderer_property_parity.py"

foreach ($token in @(
    "mod video_projection",
    "mod video_projection_metadata",
    "mod video_projection_native_stream",
    "mod video_projection_player_bridge",
    """video-projection""",
    "video_projection_settings.marker_fields()",
    "video_projection_player_bridge::start_if_enabled"
)) {
    Assert-Contains -Text $nativeLib -Needle $token -Label "native lib"
}

foreach ($token in @(
    "MediaCodec",
    "MediaExtractor",
    "Surface",
    "nativeCreateStereoVideoSurface",
    "nativeStopStereoVideoStream",
    "nativeStereoVideoLifecycleEvent",
    "decodeOnce",
    "EVENT_LOOP_RESTARTED",
    "extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)",
    "presentationOffsetUs",
    "video loop restart produced no sample",
    "releaseOutputBuffer",
    "resolvePath",
    "video/noodletest-sbs.mp4"
)) {
    Assert-Contains -Text $javaPlayback -Needle $token -Label "java playback"
}

foreach ($forbidden in @("ImageReader.newInstance", "getHardwareBuffer", "copyPixelsFromBuffer", "getPlanes(", "nativeStereoVideoHardwareBufferFrame")) {
    if ($javaPlayback.Contains($forbidden)) {
        throw "Stereo video playback must not use Java per-frame hardware-buffer or CPU pixel-copy token: $forbidden"
    }
}

foreach ($token in @(
    "StereoVideoPlayback",
    "start_if_enabled",
    "app-private-file",
    "mediacodec-surface-to-ndk-aimage-reader-ahardwarebuffer"
)) {
    Assert-Contains -Text ($playerBridge + $nativeOptions) -Needle $token -Label "player bridge/options"
}

foreach ($token in @(
    "AImageReader_newWithUsage",
    "AImageReader_getWindow",
    "ANativeWindow_toSurface",
    "AImageReader_acquireLatestImage",
    "AImage_getHardwareBuffer",
    "AIMAGE_FORMAT_PRIVATE",
    "AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT",
    "stream=stereo_video",
    "loop-restarted",
    "sourceAuthority=android-mediacodec-surface-decoder",
    "highRateJsonPayload=false",
    "nativeImageReader=true",
    "javaHardwareBufferBridge=false",
    "cpuPixelCopy=false",
    "latestFramePublished=true"
)) {
    Assert-Contains -Text $nativeStream -Needle $token -Label "native stream"
}

foreach ($token in @(
    "rusty.quest.native_renderer.video_projection_metadata.v1",
    "leftSourceUvRect",
    "rightSourceUvRect",
    "sourcePositionMode=camera-target-center-position-only",
    "leftSourcePositionOffsetUv",
    "rightSourcePositionOffsetUv",
    "videoProjectionTarget",
    "dataInputMetadataAuthority=video-projection-stream",
    "downstreamProjectionScaleAuthority=projection-target-state"
)) {
    Assert-Contains -Text $projectionMetadata -Needle $token -Label "projection metadata"
}

foreach ($token in @(
    "VideoProjectionRenderer",
    "latest_video_projection_frame",
    "query_ahb_vulkan_import_properties",
    "import_ahb_sampled_image",
    "transition_ahb_sampled_image_to_shader_read",
    "source_position_offset_for_eye",
    "video-projection-import",
    "video-projection-resources",
    "videoProjectionRendered",
    "videoProjectionGpuImportReady=true",
    "videoProjectionExternalFormatSampling",
    "videoProjectionSamplerYcbcrConversion",
    "combined-immutable-sampler-ycbcr-conversion",
    "videoProjectionGpuAdoptionPath=android-mediacodec-surface-aimage-reader-ahardwarebuffer-to-vulkan-sampled-image"
)) {
    Assert-Contains -Text ($videoRenderer + $xrVulkan) -Needle $token -Label "video renderer"
}

foreach ($token in @(
    "diagnostic_edge_tint",
    "border * 0.72 * (1.0 - stretch_active) * diagnostic_edge_tint",
    "guideProjectionEdgeTint=diagnostic-debug-only",
    "guideProjectionEdgeTintActive"
)) {
    Assert-Contains -Text ($guideProjectionShader + $projectionBorderStretchOptions) -Needle $token -Label "video border diagnostic edge tint"
}

foreach ($token in @(
    "video_projection.vert.glsl",
    "video_projection.frag.glsl",
    "video_projection.vert.spv",
    "video_projection.frag.spv"
)) {
    Assert-Contains -Text $nativeBuild -Needle $token -Label "shader build"
}

foreach ($token in @(
    "u_video_projection",
    "source_uv_rect",
    "target_rect",
    "flip_y",
    "source_position_offset_uv",
    "positioned_local_uv"
)) {
    Assert-Contains -Text ($vertexShader + $fragmentShader) -Needle $token -Label "shader"
}

foreach ($token in @(
    "Per-Eye Positioning",
    'Left source position offset: `0.046875,0.046875`',
    'Right source position offset: `-0.046875,0.054688`',
    "cannot bleed the left and right source halves",
    "no cyan/orange debug rim",
    "same dequeued input buffer"
)) {
    Assert-Contains -Text $videoProjectionDoc -Needle $token -Label "video projection doc"
}

foreach ($token in @(
    "v.mp4",
    '/sdcard/Android/data/$PackageName/files',
    "app_scoped_external_destination",
    "video_projection_path",
    "package-scoped-external-files",
    "max_android_property_value_length",
    "adb_serial_required",
    "device-scoped-adb",
    "rusty.quest.native_renderer.video_stage_receipt.v1",
    "broad_shared_storage_required"
)) {
    Assert-Contains -Text $stageVideo -Needle $token -Label "video staging wrapper"
}
if ($stageVideo.Contains("run-as")) {
    throw "Video staging wrapper must not require run-as; release APKs are not debuggable."
}

foreach ($propertyName in @(
    "debug.rustyquest.native_renderer.video_projection.enabled",
    "debug.rustyquest.native_renderer.video_projection.source",
    "debug.rustyquest.native_renderer.video_projection.path",
    "debug.rustyquest.native_renderer.video_projection.stereo_layout",
    "debug.rustyquest.native_renderer.video_projection.width",
    "debug.rustyquest.native_renderer.video_projection.height",
    "debug.rustyquest.native_renderer.video_projection.max_images",
    "debug.rustyquest.native_renderer.video_projection.fps_cap",
    "debug.rustyquest.native_renderer.video_projection.looping",
    "debug.rustyquest.native_renderer.video_projection.target",
    "debug.rustyquest.native_renderer.video_projection.opacity",
    "debug.rustyquest.native_renderer.video_projection.high_rate_json_payload"
)) {
    Assert-Contains -Text $nativeProperties -Needle $propertyName -Label "native property registry"
    Assert-Contains -Text $propertyManifest -Needle $propertyName -Label "property manifest"
    Assert-Contains -Text $validProfile -Needle $propertyName -Label "valid profile"
}

foreach ($token in @(
    "native_renderer_video_projection_options.rs",
    "profile.quest.native_renderer.fullscreen_stereo_video",
    "quest-native-renderer-fullscreen-stereo-video.profile.json",
    "side-by-side-left-right",
    "videoProjectionLeftSourceUvRect=0.000000,0.000000,0.500000,1.000000",
    "videoProjectionRightSourceUvRect=0.500000,0.000000,0.500000,1.000000",
    "high_rate_json_payload"
)) {
    Assert-Contains -Text ($propertyManifest + $validProfile + $profileMatrix + $parityTool) -Needle $token -Label "profile contract"
}

Write-Output "Native renderer video-projection static checks passed."
