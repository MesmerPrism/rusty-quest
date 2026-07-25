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
        throw "Missing Spatial stimulus-volume file: $path"
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

$route = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialStimulusVolumeRoute.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialStimulusVolumeCoordinator.kt"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$vertex = Read-RequiredText "apps\spatial-camera-panel-android\app\src\shaders\spatial_stimulus_volume.vert"
$fragment = Read-RequiredText "apps\spatial-camera-panel-android\app\src\shaders\spatial_stimulus_volume.frag"
$gradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$projectSpec = Read-RequiredText "apps\spatial-camera-panel-android\morphospace\project.spec.json"
$featureLock = Read-RequiredText "apps\spatial-camera-panel-android\morphospace\feature.lock.json"
$readme = Read-RequiredText "apps\spatial-camera-panel-android\README.md"
$doc = Read-RequiredText "docs\SPATIAL_SDK_STIMULUS_VOLUME.md"

$null = $projectSpec | ConvertFrom-Json
$feature = $featureLock | ConvertFrom-Json
$stimulusFeature = @($feature.features | Where-Object { $_.feature_id -eq "spatial-stimulus-volume" })
if ($stimulusFeature.Count -ne 1 -or $stimulusFeature[0].enabled -ne $false) {
    throw "The Spatial stimulus-volume feature must be declared exactly once and disabled."
}

Assert-Contains $projectSpec '"parameter": "stimulus.profile-safety-presentation"' "The project spec must name stimulus authority."
Assert-Contains $projectSpec '"owner": "optics"' "Rusty Optics must remain stimulus profile/safety/presentation authority."
Assert-Contains $projectSpec '"module_id": "spatial-stimulus-volume"' "The app-local adapter module must be declared."
Assert-Contains $featureLock '"effective_marker": "rusty.quest.spatial_stimulus_volume.effective"' "The feature must require a consuming-runtime marker."
Assert-Contains $route '"debug.rustyquest.spatial.stimulus_volume.enabled"' "Activation must use an app-scoped property."
Assert-Contains $route 'FIXED_PHASE("fixed-phase")' "Only the fixed-phase mode may be supported."
Assert-Contains $route '"temporal-modulation-forbidden"' "Temporal enablement must fail closed."
Assert-Contains $route '"autostart-forbidden"' "Autostart must fail closed."
Assert-Contains $route '"safety-acknowledgement-required"' "Activation must require explicit acknowledgement."
Assert-Contains $route 'SPATIAL_STIMULUS_VOLUME_MAX_HOLD_MS = 30_000L' "The source safety maximum must be enforced."
Assert-Contains $route 'phaseSeconds=0 temporalModulation=false autostart=false' "The effective marker must state the fixed temporal posture."
Assert-Contains $route 'profileAuthority=rusty-optics' "The marker must name Optics authority."
Assert-Contains $route 'deviceVisualProof=false' "Markers must not claim device visual proof."

Assert-Contains $coordinator 'SceneMaterial.custom(' "The adapter must use a Spatial custom material."
Assert-Contains $coordinator 'setDepthTest(DepthTest.ALWAYS)' "The full-field carrier must be independent of scene depth."
Assert-Contains $coordinator 'setDepthWrite(DepthWrite.DISABLE)' "The view-relative carrier must not poison scene depth."
Assert-Contains $coordinator 'entity.setComponent(Transform(bindings.poseFromViewer(CARRIER_DISTANCE_METERS)))' "The carrier must follow the viewer on scene ticks."
Assert-Contains $coordinator 'destroy("bounded-hold-complete")' "The bounded hold must clean up owned resources."
Assert-Contains $activity 'spatialStimulusVolumeCoordinator.runIfRequested("vr-ready")' "The activity must resolve the route at VR readiness."
Assert-Contains $activity 'spatialStimulusVolumeCoordinator.onSceneTick()' "The activity must refresh view-relative placement."
Assert-Contains $activity 'spatialStimulusVolumeCoordinator.destroy("activity-destroy")' "The activity must clean up the adapter."
Assert-Contains $gradle 'sources.add(project.layout.projectDirectory.dir("src/shaders"))' "The Spatial shader directory must be compiled."

Assert-Contains $fragment 'const int SAMPLE_COUNT = 16;' "Fragment volume sampling must be statically bounded."
Assert-Contains $fragment 'getEyeCenter()' "Ray construction must use the current stereo eye."
Assert-Contains $fragment 'float fbm2(vec3 point)' "The two-octave value-noise path must be present."
Assert-Contains $fragment 'float fixedInterference(' "The fixed dual-source interference path must be present."
Assert-Contains $fragment 'vec3 depthRamp(float depth01)' "The near/mid/far depth ramp must be present."
if ($fragment -match '\bdiscard\b') {
    throw "The fixed-density volume path must not depend on SDF convergence/discard."
}
foreach ($shader in @($vertex, $fragment)) {
    if ($shader -match 'g_ViewUniform\.(time|moduloTime)|\bmoduloTime\b') {
        throw "The fixed-phase shaders must not consume a time uniform."
    }
}
if ($coordinator -match 'System\.nanoTime|setAttribute\([^\r\n]*phase') {
    throw "MOD-009 must not advance material phase on the CPU."
}

Assert-Contains $readme 'No temporal or headset launch belongs to MOD-009.' "The app README must preserve the source-only boundary."
Assert-Contains $doc 'device launch is forbidden in this unit' "The implementation note must preserve the device stop condition."

Write-Host "Spatial stimulus-volume static validation passed"
