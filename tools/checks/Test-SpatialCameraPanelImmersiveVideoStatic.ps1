param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing immersive-video integration file: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if ($Text.Contains($Needle)) {
        throw "$Label contains forbidden token: $Needle"
    }
}

$catalog = Read-RequiredText "apps\spatial-camera-panel-android\gradle\libs.versions.toml"
$appGradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$manifest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml"
$ids = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\res\values\ids.xml"
$route = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoRouteModule.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoPanelCoordinator.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$routeTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoRouteModuleTest.kt"
$stageTool = Read-RequiredText "tools\Stage-SpatialCameraPanelImmersiveVideo.ps1"
$buildTool = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"

Assert-Contains "Spatial SDK version catalog" $catalog 'spatialsdk = "0.13.2"'
Assert-Contains "Media3 version catalog" $catalog 'media3 = "1.4.1"'
Assert-Contains "App dependencies" $appGradle "libs.androidx.media3.exoplayer"
Assert-Contains "App release build" $appGradle 'getByName("release")'
Assert-Contains "App release build" $appGradle "signingConfig = signingConfigs.getByName(`"debug`")"
Assert-Contains "Panel ids" $ids "spatial_immersive_video_panel"
Assert-NotContains "Android manifest" $manifest 'android:debuggable="true"'
Assert-NotContains "Android manifest" $manifest "android.permission.READ_MEDIA_VIDEO"
Assert-NotContains "Android manifest" $manifest "android.permission.READ_EXTERNAL_STORAGE"

Assert-Contains "Immersive route" $route 'Equirect180("equirect-180")'
Assert-Contains "Immersive route" $route 'Equirect360("equirect-360")'
Assert-Contains "Immersive route" $route 'SideBySideLeftRight("side-by-side-left-right")'
Assert-Contains "Immersive route" $route 'TopBottom("top-bottom")'
Assert-Contains "Immersive route" $route "path-outside-app-scoped-media-root"
Assert-Contains "Immersive route" $route "content-uri-not-media-video"
Assert-Contains "Immersive route" $route "content-uri-not-readable"
Assert-Contains "Immersive route" $route "projection-shape-unknown"
Assert-Contains "Immersive route" $route "failClosed=true"
Assert-Contains "Immersive route" $route "directToSurface=true"

Assert-Contains "Immersive coordinator" $coordinator "VideoSurfacePanelRegistration("
Assert-Contains "Immersive coordinator" $coordinator "Equirect180ShapeOptions"
Assert-Contains "Immersive coordinator" $coordinator "Equirect360ShapeOptions"
Assert-Contains "Immersive coordinator" $coordinator "StereoMode.None"
Assert-Contains "Immersive coordinator" $coordinator "StereoMode.LeftRight"
Assert-Contains "Immersive coordinator" $coordinator "StereoMode.UpDown"
Assert-Contains "Immersive coordinator" $coordinator "PixelDisplayOptions(width = config.widthPx, height = config.heightPx)"
Assert-Contains "Immersive coordinator" $coordinator "status=first-frame-rendered"
Assert-Contains "Immersive coordinator" $coordinator "status=playback-progress"
Assert-Contains "Immersive coordinator" $coordinator "advancing=`${currentPositionMs > firstFramePositionMs}"
Assert-Contains "Immersive coordinator" $coordinator "status=decoded-video-size"
Assert-Contains "Immersive coordinator" $coordinator "clearVideoSurface()"
Assert-Contains "Immersive coordinator" $coordinator "context.getExternalFilesDir(null)"
Assert-Contains "Immersive coordinator" $coordinator '"immersive-video/" + requestedPath.removePrefix(matchingAlias)'
Assert-Contains "Immersive coordinator" $coordinator "context.contentResolver"
Assert-Contains "Immersive coordinator" $coordinator "granted-media-content-uri"
Assert-NotContains "Immersive coordinator" $coordinator "ReadableVideoSurfacePanelRegistration"

Assert-Contains "Activity" $activity "immersiveVideoPanelCoordinator.requested"
Assert-Contains "Activity" $activity "return listOfNotNull(immersiveVideoPanelCoordinator.panelRegistrationOrNull())"
Assert-Contains "Activity" $activity 'immersiveVideoPanelCoordinator.destroy("activity-destroy")'

Assert-Contains "Immersive route tests" $routeTest "routesEquirect180SideBySideStereo"
Assert-Contains "Immersive route tests" $routeTest "routesEquirect360MonoEvenWhenSourceMetadataWouldClaimStereo"
Assert-Contains "Immersive route tests" $routeTest "routesAReadableGrantedMediaContentUriWithoutBroadStorageAccess"
Assert-Contains "Immersive route tests" $routeTest "rejectsUnknownProjectionInsteadOfGuessingFromFilenameOrMetadata"

Assert-Contains "Immersive staging tool" $stageTool 'throw "-Serial or RUSTY_QUEST_SERIAL is required'
Assert-Contains "Immersive staging tool" $stageTool '@("-s", $script:Serial)'
Assert-Contains "Immersive staging tool" $stageTool "/sdcard/Movies/RustyMorphovision"
Assert-Contains "Immersive staging tool" $stageTool "content://media/external/video/media/`$mediaStoreId"
Assert-Contains "Immersive staging tool" $stageTool '"-f", "0x1"'
Assert-Contains "Immersive staging tool" $stageTool "single_uri_read_grant = `$true"
Assert-Contains "Immersive staging tool" $stageTool "broad_shared_storage_permission_required = `$false"
Assert-Contains "Immersive staging tool" $stageTool "videos_bundled_in_apk = `$false"
Assert-Contains "Immersive staging tool" $stageTool "metadata_autodetection_authority = `$false"
Assert-Contains "Immersive staging tool" $stageTool "transfer_verified"
Assert-Contains "Immersive staging tool" $stageTool '$markerOutput.Contains("advancing=true")'
Assert-Contains "Immersive staging tool" $stageTool '"--pid=$targetPid", "*:E"'
Assert-Contains "Immersive staging tool" $stageTool "target_process_alive_after_observation"
Assert-Contains "Immersive staging tool" $stageTool "target_fatal_detected"
Assert-Contains "Immersive staging tool" $stageTool "bounded_runtime_validation_passed"
Assert-Contains "Immersive staging tool" $stageTool '"RQSpatialCameraPanel:I"'
Assert-NotContains "Immersive staging tool" $stageTool "kill-server"
Assert-NotContains "Immersive staging tool" $stageTool "start-server"

Assert-Contains "Spatial Camera Panel build tool" $buildTool '[ValidateSet("Debug", "Release")]'
Assert-Contains "Spatial Camera Panel build tool" $buildTool '":app:assemble$BuildType"'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'spatial_sdk_version = "0.13.2"'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'media3_version = "1.4.1"'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'immersive_video_media_packaged = $false'

Write-Host "Spatial Camera Panel immersive-video static checks passed"
