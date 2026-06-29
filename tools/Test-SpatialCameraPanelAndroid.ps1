param(
    [switch]$Build,
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PrivateLayerProfilePath = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE,
    [string]$OpaqueGuideShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER,
    [string]$OpaqueProjectionShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER,
    [string]$OpaqueProjectionEffect = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path $RepoRoot
$staticCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelAndroidStatic.ps1"
$buildPath = Join-Path $PSScriptRoot "Build-SpatialCameraPanelAndroid.ps1"

if (-not (Test-Path -LiteralPath $staticCheckPath)) {
    throw "Missing Spatial Camera Panel static check: $staticCheckPath"
}
if (-not (Test-Path -LiteralPath $buildPath)) {
    throw "Missing Spatial Camera Panel build wrapper: $buildPath"
}

& $staticCheckPath -RepoRoot $repoRootPath

if ($Build) {
    & $buildPath `
        -RepoRoot $repoRootPath `
        -AndroidHome $AndroidHome `
        -JavaHome $JavaHome `
        -PrivateLayerProfilePath $PrivateLayerProfilePath `
        -OpaqueGuideShader $OpaqueGuideShader `
        -OpaqueProjectionShader $OpaqueProjectionShader `
        -OpaqueProjectionEffect $OpaqueProjectionEffect
}

Write-Host "Spatial Camera Panel Android validation passed"
