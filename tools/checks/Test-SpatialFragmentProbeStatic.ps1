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
        throw "Missing Spatial fragment probe file: $path"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Text.Contains($Needle)) {
        throw $Message
    }
}

$appGradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$route = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialFragmentProbeRoute.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialFragmentProbeCoordinator.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$flatVertex = Read-RequiredText "apps\spatial-camera-panel-android\app\src\shaders\spatial_fragment_probe_nodepth.vert"
$flatFragment = Read-RequiredText "apps\spatial-camera-panel-android\app\src\shaders\spatial_fragment_probe_nodepth.frag"
$depthVertex = Read-RequiredText "apps\spatial-camera-panel-android\app\src\shaders\spatial_fragment_probe_depth.vert"
$depthFragment = Read-RequiredText "apps\spatial-camera-panel-android\app\src\shaders\spatial_fragment_probe_depth.frag"
$smoke = Read-RequiredText "tools\Invoke-SpatialCameraPanelFragmentProbeSmoke.ps1"
$build = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"

Assert-Contains $appGradle 'sources.add(project.layout.projectDirectory.dir("src/shaders"))' "Meta Spatial Gradle shader compilation must include app/src/shaders."
Assert-Contains $appGradle 'ndkVersion = spatialNdkVersion.get()' "The Meta plugin must receive the resolved NDK version."
Assert-Contains $build '$env:RUSTY_QUEST_ANDROID_NDK_VERSION = Split-Path -Leaf $NdkHome' "The build wrapper must project its resolved NDK into Gradle."
Assert-Contains $route '"flat-2d"' "The probe must expose the static 2D control."
Assert-Contains $route '"raymarch"' "The probe must expose the raymarch mode."
Assert-Contains $route 'fragment_probe.fragment_depth' "Fragment-depth activation must have a separate adapter property."
Assert-Contains $route 'activationEffectiveMarker=rusty.quest.spatial_fragment_probe.effective' "The consuming runtime must emit an effective marker."
Assert-Contains $route 'temporalModulation=false photosensitiveSafetyMode=static-only' "The probe must declare its static-only safety posture."
Assert-Contains $route 'visibleEvidenceRequired=true' "Markers alone must not be treated as GPU execution proof."
Assert-Contains $coordinator 'SceneMaterial.custom(' "The probe must use the Spatial SDK custom-material path."
Assert-Contains $coordinator 'setDepthTest(DepthTest.LESS_OR_EQUAL)' "The probe must keep ordinary scene depth testing enabled."
Assert-Contains $coordinator 'setDepthWrite(DepthWrite.ENABLE)' "The probe must make depth-buffer behavior observable."
Assert-Contains $coordinator 'spatial_fragment_probe_foreground_occluder' "The probe must include a foreground depth control."
Assert-Contains $coordinator 'spatial_fragment_probe_depth_discriminator' "The probe must include a proxy-versus-hit-depth discriminator."
Assert-Contains $activity 'spatialFragmentProbeCoordinator.runIfRequested("vr-ready")' "The activity must consume the opt-in at VR readiness."
Assert-Contains $activity 'spatialFragmentProbeCoordinator.onSceneTick()' "The activity must emit render-window liveness evidence."
Assert-Contains $activity 'spatialFragmentProbeCoordinator.destroy("activity-destroy")' "The activity must destroy probe-owned resources."

foreach ($shader in @($flatVertex, $flatFragment, $depthVertex, $depthFragment)) {
    if ($shader -match 'g_ViewUniform\.(time|moduloTime)|\bmoduloTime\b') {
        throw "Spatial fragment probe shaders must not use time-dependent uniforms."
    }
}
foreach ($fragment in @($flatFragment, $depthFragment)) {
    Assert-Contains $fragment 'const int RAYMARCH_STEPS = 12;' "Raymarch work must remain statically bounded to twelve steps."
    Assert-Contains $fragment 'getEyeCenter()' "Ray construction must use the current stereo eye."
}
if ($flatFragment.Contains("gl_FragDepth")) {
    throw "The no-depth shader must not write gl_FragDepth."
}
Assert-Contains $depthFragment 'gl_FragDepth = clamp(clipHit.z / clipHit.w, 0.0, 1.0);' "The depth shader must write projected hit depth."
Assert-Contains $smoke 'adb-explicit-serial' "The smoke must declare serial-scoped ADB routing."
Assert-Contains $smoke 'prior_properties' "The smoke must snapshot the complete property manifest."
Assert-Contains $smoke 'restore_verified' "The smoke must verify exact property restoration."
Assert-Contains $smoke 'exec-out screencap -p' "The smoke must capture screenshots without device-side temporary files."
Assert-Contains $smoke '"debug.rustyquest.spatial.camera_hwb_projection_probe" = "false"' "The fragment probe smoke must isolate the custom camera projection."
Assert-Contains $smoke '"debug.rustyquest.spatial.camera_hwb_projection_probe.video.enabled" = "false"' "The fragment probe smoke must isolate the decoded video layer."
Assert-Contains $smoke '"debug.rustyquest.spatial.video_projection_probe" = "false"' "The fragment probe smoke must isolate the video-only route."
Assert-Contains $smoke 'unrelated camera/video layer became active' "The smoke must fail if an unrelated projection layer starts."

Write-Host "Spatial fragment probe static validation passed"
