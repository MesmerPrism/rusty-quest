param(
    [switch]$Build,
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PrivateLayerProfilePath = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE,
    [string]$OpaqueGuideShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER,
    [string]$OpaqueProjectionShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER,
    [string]$OpaqueProjectionEffect = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT,
    [string]$PrivateSurfaceParticleProfilePath = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE,
    [string]$PrivateSurfaceParticleShader = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER,
    [string]$PrivateSurfaceParticlePayloadDir = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR,
    [string]$PrivateSurfaceParticleMarkerPrefix = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX,
    [string]$HandMeshRigAssetDir = $env:RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR,
    [string]$AppId = $env:RUSTY_QUEST_SPATIAL_APP_ID,
    [string]$AppLabel = $env:RUSTY_QUEST_SPATIAL_APP_LABEL,
    [string]$ApkFileName = $env:RUSTY_QUEST_SPATIAL_APK_FILE_NAME,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path $RepoRoot
$workflowCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelWorkflowStatic.ps1"
$staticCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelAndroidStatic.ps1"
$cameraLatencyCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelCameraLatencyDiagnosticStatic.ps1"
$buildPath = Join-Path $PSScriptRoot "Build-SpatialCameraPanelAndroid.ps1"

if (-not (Test-Path -LiteralPath $workflowCheckPath)) {
    throw "Missing Spatial Camera Panel workflow check: $workflowCheckPath"
}
if (-not (Test-Path -LiteralPath $staticCheckPath)) {
    throw "Missing Spatial Camera Panel static check: $staticCheckPath"
}
if (-not (Test-Path -LiteralPath $cameraLatencyCheckPath)) {
    throw "Missing Spatial Camera Panel camera latency diagnostic check: $cameraLatencyCheckPath"
}
if (-not (Test-Path -LiteralPath $buildPath)) {
    throw "Missing Spatial Camera Panel build wrapper: $buildPath"
}

& $workflowCheckPath -RepoRoot $repoRootPath
& $staticCheckPath -RepoRoot $repoRootPath
& $cameraLatencyCheckPath -RepoRoot $repoRootPath
Push-Location -LiteralPath $repoRootPath
try {
    cargo test -p spatial-camera-panel-native-receipt surface_particle
    cargo test -p spatial-camera-panel-native-receipt camera_latency
} finally {
    Pop-Location
}

if ($Build) {
    & $buildPath `
        -RepoRoot $repoRootPath `
        -AndroidHome $AndroidHome `
        -JavaHome $JavaHome `
        -PrivateLayerProfilePath $PrivateLayerProfilePath `
        -OpaqueGuideShader $OpaqueGuideShader `
        -OpaqueProjectionShader $OpaqueProjectionShader `
        -OpaqueProjectionEffect $OpaqueProjectionEffect `
        -PrivateSurfaceParticleProfilePath $PrivateSurfaceParticleProfilePath `
        -PrivateSurfaceParticleShader $PrivateSurfaceParticleShader `
        -PrivateSurfaceParticlePayloadDir $PrivateSurfaceParticlePayloadDir `
        -PrivateSurfaceParticleMarkerPrefix $PrivateSurfaceParticleMarkerPrefix `
        -HandMeshRigAssetDir $HandMeshRigAssetDir `
        -AppId $AppId `
        -AppLabel $AppLabel `
        -ApkFileName $ApkFileName `
        -OutDir $OutDir
}

Write-Host "Spatial Camera Panel Android validation passed"
