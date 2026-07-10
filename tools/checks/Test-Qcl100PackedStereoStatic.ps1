param(
    [string]$RepoRoot = "."
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-RequiredFile {
    param([string]$RelativePath)
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing packed-stereo surface: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param([string]$Label, [string]$Text, [string]$Token)
    if (-not $Text.Contains($Token)) {
        throw "$Label is missing packed-stereo token: $Token"
    }
}

$model = Read-RequiredFile "crates\rusty-quest-remote-camera\src\model.rs"
$validation = Read-RequiredFile "crates\rusty-quest-remote-camera\src\validation.rs"
$packedStream = Read-RequiredFile "crates\rusty-quest-remote-camera\src\packed_stream.rs"
$pairer = Read-RequiredFile "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraStereoFramePairer.java"
$metadata = Read-RequiredFile "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraPackedStreamMetadata.java"
$compositor = Read-RequiredFile "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraStereoGlCompositor.java"
$source = Read-RequiredFile "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\RemoteCameraPackedStereoSourceRuntime.java"
$nativePlayback = Read-RequiredFile "apps\native-renderer-android\src\main\java\io\github\mesmerprism\rustyquest\native_renderer\StereoVideoPlayback.java"
$nativeStream = Read-RequiredFile "apps\native-renderer-android\native\src\remote_camera_projection_native_stream.rs"
$cameraProjectionMetadata = Read-RequiredFile "apps\native-renderer-android\native\src\camera_projection_metadata.rs"
$cameraProjection = Read-RequiredFile "apps\native-renderer-android\native\src\camera_projection.rs"
$cameraProjectionShader = Read-RequiredFile "apps\native-renderer-android\native\shaders\camera_projection.frag.glsl"
$spatialSettings = Read-RequiredFile "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialVideoProjectionSettings.kt"
$spatialPlayback = Read-RequiredFile "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialStereoVideoPlayback.java"
$spatialPacked = Read-RequiredFile "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPackedStereoBrokerPlayback.java"
$spatialNative = Read-RequiredFile "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_native_stream.rs"
$runner = Read-RequiredFile "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1"
$localHarness = Read-RequiredFile "tools\Invoke-Qcl100PackedStereoLocalLoopback.ps1"
$reducer = Read-RequiredFile "tools\Test-Qcl100PackedStereoRun.ps1"

Assert-Contains "Remote camera model" $model 'MEDIA_LAYOUT_SIDE_BY_SIDE_LEFT_RIGHT: &str = "side-by-side-left-right"'
Assert-Contains "Remote camera model" $model "packed stereo is never enabled implicitly"
Assert-Contains "Remote camera validation" $validation "must contain exactly one stereo lane"
Assert-Contains "Remote camera validation" $validation "must disable stale-eye reuse"
Assert-Contains "Packed RMANVID contract" $packedStream "RMANVID_PACKED_STEREO_SCHEMA_VERSION: u32 = 4"
Assert-Contains "Packed RMANVID contract" $packedStream "PACKED_STEREO_PAIR_EXTENSION_BYTES: usize = 48"
Assert-Contains "Packed RMANVID contract" $packedStream "validate_packed_pair_sequence"

Assert-Contains "Stereo frame pairer" $pairer "chooseNearest"
Assert-Contains "Stereo frame pairer" $pairer "staleEyeReuseCount"
Assert-Contains "Packed stream metadata" $metadata "RMANVID_SCHEMA_VERSION = 4"
Assert-Contains "Packed stream metadata" $metadata "PAIR_EXTENSION_BYTES = 48"
Assert-Contains "Stereo GL compositor" $compositor "gpuCompositorActive"
Assert-Contains "Packed source runtime" $source "CaptureResult.SENSOR_TIMESTAMP"
Assert-Contains "Packed source runtime" $source 'json.put("encoder_instance_count", encoder != null ? 1 : 0)'
Assert-Contains "Packed source runtime" $source 'json.put("cpu_pixel_copy", false)'
Assert-Contains "Packed source runtime" $source 'throw new IllegalArgumentException("packed source requires exactly one stereo port")'

Assert-Contains "Native packed playback" $nativePlayback "packedSocketCount=1 decoderInstanceCount=1 nativeImageReaderCount=1"
Assert-Contains "Native packed playback" $nativePlayback "packed stereo requires an explicit hardware H.264 decoder"
Assert-Contains "Native packed playback" $nativePlayback "PackedPairRecord.read"
Assert-Contains "Native packed stream" $nativeStream "remote-broker-packed-left-50"
Assert-Contains "Native packed stream" $nativeStream "remote-broker-packed-right-51"
Assert-Contains "Native packed stream" $nativeStream "remote-broker-packed-sbs-source-timestamp"
Assert-Contains "Native stereo source layout" $cameraProjectionMetadata "PackedSideBySideLeftRight"
Assert-Contains "Native stereo source layout" $cameraProjectionMetadata "TargetRect::new(0.0, 0.0, 0.5, 1.0)"
Assert-Contains "Native stereo source layout" $cameraProjectionMetadata "TargetRect::new(0.5, 0.0, 0.5, 1.0)"
Assert-Contains "Camera projection runtime" $cameraProjection "packed_source_visual_equivalence_ready"
Assert-Contains "Camera projection shader" $cameraProjectionShader "pc.source_uv_rect.xy + oriented_uv * pc.source_uv_rect.zw"
Assert-Contains "Camera projection shader" $cameraProjectionShader "uv = clamp(uv, source_min, source_max)"

Assert-Contains "Spatial packed settings" $spatialSettings '"broker-rmanvid1"'
Assert-Contains "Spatial packed settings" $spatialSettings "side-by-side-left-right"
Assert-Contains "Spatial packed playback facade" $spatialPlayback "SpatialPackedStereoBrokerPlayback.run"
Assert-Contains "Spatial packed adapter" $spatialPacked "packedSocketCount=1 decoderInstanceCount=1 nativeImageReaderCount=1"
Assert-Contains "Spatial packed adapter" $spatialPacked "Spatial packed stereo requires a hardware H.264 decoder"
Assert-Contains "Spatial native stream" $spatialNative "packed_pair"
Assert-Contains "Spatial native stream" $spatialNative "nativeImageReaderCount=1"

Assert-Contains "QCL100 runner" $runner '[ValidateSet("separate-eye-streams", "side-by-side-left-right")]'
Assert-Contains "QCL100 runner" $runner '$activeLaneCount = if ($packedMediaLayout) { 1 }'
Assert-Contains "QCL100 runner" $runner '[int]$PackedPerEyeWidth = 1280'
Assert-Contains "QCL100 runner" $runner '[int]$PackedPerEyeHeight = 1280'
Assert-Contains "QCL100 runner" $runner '[int]$PackedBitrate = 12000000'
Assert-Contains "Packed Camera2 source" $source "camera_output_size_exact_supported"
Assert-Contains "QCL100 runner" $runner '"stereo:${packedWidth}x${packedHeight}@${PackedFps}:${PackedBitrate}"'
Assert-Contains "Packed local harness" $localHarness "rusty.quest.qcl100_packed_stereo_local_loopback.v1"
Assert-Contains "Packed promotion reducer" $reducer "rusty.quest.qcl100_packed_stereo_acceptance.v1"
Assert-Contains "Packed promotion reducer" $reducer "packed_sbs_stereo_simultaneous_duplex"
Assert-Contains "Packed promotion reducer" $reducer "packed_eye_uv_split_equivalent"
Assert-Contains "Packed promotion reducer" $reducer "camera2_exact_1280_square_outputs"
Assert-Contains "Packed promotion reducer" $reducer "packed_2560x1280_decoder_output"

foreach ($script in @(
    "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirect.ps1",
    "tools\Invoke-Qcl100QuestToQuestNativeStereoProjectionWifiDirectMonitored.ps1",
    "tools\Invoke-Qcl100PackedStereoLocalLoopback.ps1",
    "tools\Test-Qcl100PackedStereoRun.ps1"
)) {
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $root $script),
        [ref]$null,
        [ref]$errors
    )
    if (@($errors).Count -ne 0) {
        throw "PowerShell parser rejected $script`: $($errors[0].Message)"
    }
}

Write-Host "QCL100 packed stereo static checks passed."
