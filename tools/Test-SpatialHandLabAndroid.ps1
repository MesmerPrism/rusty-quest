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
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = (Resolve-Path $RepoRoot).Path
$delegatePath = Join-Path $repoRootPath "tools\Test-SpatialCameraPanelAndroid.ps1"
if (-not (Test-Path -LiteralPath $delegatePath)) {
    throw "Missing Spatial Camera Panel validation wrapper: $delegatePath"
}

$envNames = @(
    "RUSTY_QUEST_SPATIAL_APP_ID",
    "RUSTY_QUEST_SPATIAL_APP_LABEL",
    "RUSTY_QUEST_SPATIAL_APK_FILE_NAME",
    "RUSTY_QUEST_SPATIAL_START_IN_PARTICLE_VIEW_DEFAULT",
    "RUSTY_QUEST_SPATIAL_PANEL_LAUNCHER_VISIBLE_DEFAULT",
    "RUSTY_QUEST_SPATIAL_PARTICLE_LAYER_CARRIER_DEFAULT"
)
$previousEnv = @{}
foreach ($name in $envNames) {
    $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $env:RUSTY_QUEST_SPATIAL_APP_ID = "io.github.mesmerprism.rustyquest.spatial_hand_lab"
    $env:RUSTY_QUEST_SPATIAL_APP_LABEL = "Rusty Quest Spatial Hand Lab"
    $env:RUSTY_QUEST_SPATIAL_APK_FILE_NAME = "rusty-quest-spatial-hand-lab.apk"
    $env:RUSTY_QUEST_SPATIAL_START_IN_PARTICLE_VIEW_DEFAULT = "true"
    $env:RUSTY_QUEST_SPATIAL_PANEL_LAUNCHER_VISIBLE_DEFAULT = "false"
    $env:RUSTY_QUEST_SPATIAL_PARTICLE_LAYER_CARRIER_DEFAULT = "manual-panel-scene-object-custom-mesh"

    $delegateArgs = @{
        RepoRoot = $repoRootPath
        AndroidHome = $AndroidHome
        JavaHome = $JavaHome
        PrivateLayerProfilePath = $PrivateLayerProfilePath
        OpaqueGuideShader = $OpaqueGuideShader
        OpaqueProjectionShader = $OpaqueProjectionShader
        OpaqueProjectionEffect = $OpaqueProjectionEffect
        PrivateSurfaceParticleProfilePath = $PrivateSurfaceParticleProfilePath
        PrivateSurfaceParticleShader = $PrivateSurfaceParticleShader
        PrivateSurfaceParticlePayloadDir = $PrivateSurfaceParticlePayloadDir
        PrivateSurfaceParticleMarkerPrefix = $PrivateSurfaceParticleMarkerPrefix
        AppId = $env:RUSTY_QUEST_SPATIAL_APP_ID
        AppLabel = $env:RUSTY_QUEST_SPATIAL_APP_LABEL
        ApkFileName = $env:RUSTY_QUEST_SPATIAL_APK_FILE_NAME
        OutDir = $OutDir
    }
    if ($Build) {
        $delegateArgs["Build"] = $true
    }
    & $delegatePath @delegateArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial hand lab Android validation failed with exit code $LASTEXITCODE"
    }
} finally {
    foreach ($name in $envNames) {
        if ($null -eq $previousEnv[$name]) {
            Remove-Item "Env:\$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, [string]$previousEnv[$name], "Process")
        }
    }
}

Write-Host "Spatial hand lab Android validation passed"
