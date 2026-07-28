param([string]$RepoRoot)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing camera replay capture file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )

    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

$module = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraReplayCaptureModule.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$native = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_replay_capture.rs"
$probe = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_probe.rs"
$wsi = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\camera_hwb_wsi.rs"
$shader = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\shaders\camera_replay_capture.frag.glsl"
$nativeLib = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\lib.rs"
$nativeBuild = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\build.rs"
$readme = Read-RequiredText "apps\spatial-camera-panel-android\README.md"

Assert-Contains "Kotlin capture module" $module 'CAMERA_REPLAY_CAPTURE_SCHEMA = "rusty.quest.camera_replay_capture.v1"'
Assert-Contains "Kotlin capture module" $module "context.getExternalFilesDir(null)"
Assert-Contains "Kotlin capture module" $module 'CAMERA_REPLAY_CAPTURE_ROOT = "camera-replay"'
Assert-Contains "Kotlin capture module" $module "DEFAULT_CAMERA_REPLAY_CAPTURE_FRAME_COUNT = 12"
Assert-Contains "Kotlin capture module" $module "MAX_CAMERA_REPLAY_CAPTURE_FRAME_COUNT = 120"
Assert-Contains "Kotlin capture module" $module "cameraReplayCaptureHighRateJsonPayload=false"
Assert-Contains "Activity" $activity "SpatialCameraReplayCaptureModule.resolve(this)"
Assert-Contains "Activity" $activity "nativeConfigureCameraReplayCapture("

Assert-Contains "Native capture" $native 'CAPTURE_SCHEMA: &str = "rusty.quest.camera_replay_capture.v1"'
Assert-Contains "Native capture" $native "MAX_CAPTURE_FRAMES: u32 = 120"
Assert-Contains "Native capture" $native 'let file = format!("frame-{index:04}.rgba")'
Assert-Contains "Native capture" $native '"capture.manifest.json"'
Assert-Contains "Native capture" $native '"eye_order": "left-right"'
Assert-Contains "Native capture" $native '"pixel_format": "rgba8-unorm"'
Assert-Contains "Native capture" $native "highRateJsonPayload=false"
Assert-Contains "Native capture" $native "camera-replay-capture-finished"
Assert-Contains "Native probe" $probe "configured_camera_replay_capture()"
Assert-Contains "Native probe" $probe "capture.retire_completed(&device)?"
Assert-Contains "Native probe" $probe "capture.finish(if capture.is_complete()"
Assert-Contains "Camera WSI" $wsi "capture.record_if_due("

Assert-Contains "Capture shader" $shader "layout(set = 0, binding = 0)"
Assert-Contains "Capture shader" $shader "layout(set = 0, binding = 1)"
Assert-Contains "Capture shader" $shader "u_camera_left"
Assert-Contains "Capture shader" $shader "u_camera_right"
Assert-Contains "Native lib" $nativeLib "mod camera_replay_capture"
Assert-Contains "Native build" $nativeBuild '"shaders/camera_replay_capture.frag.glsl"'
Assert-Contains "Native build" $nativeBuild '"camera_replay_capture.frag.spv"'
Assert-Contains "README" $readme "SpatialCameraReplayCaptureModule.kt"
Assert-Contains "README" $readme "rusty.quest.camera_replay_capture.v1"
Assert-Contains "README" $readme "local artifacts"

Write-Output "Spatial Camera Panel camera replay capture static checks passed."
