param(
    [switch]$Build,
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleVersion = "9.4.1",
    [string]$PrivateLayerProfilePath = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_PRIVATE_LAYER_PROFILE,
    [string]$OpaqueGuideShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER,
    [string]$OpaqueProjectionShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER,
    [string]$OpaqueProjectionVertexShader = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_VERTEX_SHADER,
    [string]$OpaqueProjectionEffect = $env:RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT,
    [ValidateSet(1, 2)][int]$ProjectionSurfaceUniformAbiVersion = 1,
    [string]$PrivateSurfaceParticleProfilePath = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE,
    [string]$PrivateSurfaceParticleShader = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER,
    [string]$PrivateSurfaceParticlePayloadDir = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR,
    [string]$PrivateSurfaceParticleMarkerPrefix = $env:RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX,
    [string]$HandMeshRigAssetDir = $env:RUSTY_QUEST_SPATIAL_HAND_MESH_RIG_ASSET_DIR,
    [string]$ProductId = $env:RUSTY_QUEST_SPATIAL_PRODUCT_ID,
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
$rawProjectionFreshnessReducerCheckPath =
    Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelRawProjectionFreshnessReducer.ps1"
$rawProjectionFreshnessFinalizerCheckPath =
    Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelRawProjectionFreshnessFinalizer.ps1"
$immersiveVideoCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelImmersiveVideoStatic.ps1"
$rgbChannelTransformCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelRgbChannelTransformStatic.ps1"
$projectionSurfaceDisplacementCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelProjectionSurfaceDisplacementStatic.ps1"
$controlProfileCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelControlProfileStatic.ps1"
$controlProfileHostTestPath = Join-Path $PSScriptRoot "checks\Test-InstallSpatialCameraPanelControlProfileHost.ps1"
$cameraLatencyCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelCameraLatencyDiagnosticStatic.ps1"
$fragmentProbeCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialFragmentProbeStatic.ps1"
$vrStrobeCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialVrStrobeStatic.ps1"
$panelFacingCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialSdkPanelFacingStatic.ps1"
$productIsolationCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialProductIsolationStatic.ps1"
$buildPath = Join-Path $PSScriptRoot "Build-SpatialCameraPanelAndroid.ps1"

if (-not (Test-Path -LiteralPath $workflowCheckPath)) {
    throw "Missing Spatial Camera Panel workflow check: $workflowCheckPath"
}
if (-not (Test-Path -LiteralPath $staticCheckPath)) {
    throw "Missing Spatial Camera Panel static check: $staticCheckPath"
}
if (-not (Test-Path -LiteralPath $rawProjectionFreshnessReducerCheckPath)) {
    throw "Missing Spatial Camera Panel Raw Projection freshness reducer check: $rawProjectionFreshnessReducerCheckPath"
}
if (-not (Test-Path -LiteralPath $rawProjectionFreshnessFinalizerCheckPath)) {
    throw "Missing Spatial Camera Panel Raw Projection freshness finalizer check: $rawProjectionFreshnessFinalizerCheckPath"
}
if (-not (Test-Path -LiteralPath $immersiveVideoCheckPath)) {
    throw "Missing Spatial Camera Panel immersive-video static check: $immersiveVideoCheckPath"
}
if (-not (Test-Path -LiteralPath $rgbChannelTransformCheckPath)) {
    throw "Missing Spatial Camera Panel RGB channel transform static check: $rgbChannelTransformCheckPath"
}
if (-not (Test-Path -LiteralPath $projectionSurfaceDisplacementCheckPath)) {
    throw "Missing Spatial Camera Panel projection-surface displacement static check: $projectionSurfaceDisplacementCheckPath"
}
if (-not (Test-Path -LiteralPath $controlProfileCheckPath)) {
    throw "Missing Spatial Camera Panel control-profile static check: $controlProfileCheckPath"
}
if (-not (Test-Path -LiteralPath $controlProfileHostTestPath)) {
    throw "Missing Spatial Camera Panel control-profile host test: $controlProfileHostTestPath"
}
if (-not (Test-Path -LiteralPath $cameraLatencyCheckPath)) {
    throw "Missing Spatial Camera Panel camera latency diagnostic check: $cameraLatencyCheckPath"
}
if (-not (Test-Path -LiteralPath $fragmentProbeCheckPath)) {
    throw "Missing Spatial Camera Panel fragment probe check: $fragmentProbeCheckPath"
}
if (-not (Test-Path -LiteralPath $vrStrobeCheckPath)) {
    throw "Missing Spatial Camera Panel VR Strobe check: $vrStrobeCheckPath"
}
if (-not (Test-Path -LiteralPath $panelFacingCheckPath)) {
    throw "Missing Spatial SDK panel-facing check: $panelFacingCheckPath"
}
if (-not (Test-Path -LiteralPath $productIsolationCheckPath)) {
    throw "Missing Spatial product isolation check: $productIsolationCheckPath"
}
if (-not (Test-Path -LiteralPath $buildPath)) {
    throw "Missing Spatial Camera Panel build wrapper: $buildPath"
}

& $workflowCheckPath -RepoRoot $repoRootPath
& $staticCheckPath -RepoRoot $repoRootPath
& $rawProjectionFreshnessFinalizerCheckPath -RepoRoot $repoRootPath
& $rawProjectionFreshnessReducerCheckPath -RepoRoot $repoRootPath
& $immersiveVideoCheckPath -RepoRoot $repoRootPath
& $rgbChannelTransformCheckPath -RepoRoot $repoRootPath
& $projectionSurfaceDisplacementCheckPath -RepoRoot $repoRootPath
& $controlProfileCheckPath -RepoRoot $repoRootPath
& $controlProfileHostTestPath -RepoRoot $repoRootPath
& $cameraLatencyCheckPath -RepoRoot $repoRootPath
& $fragmentProbeCheckPath -RepoRoot $repoRootPath
& $vrStrobeCheckPath -RepoRoot $repoRootPath
& $panelFacingCheckPath -RepoRoot $repoRootPath
& $productIsolationCheckPath -RepoRoot $repoRootPath

$gradleBat = Join-Path $repoRootPath "local-artifacts\tools\gradle-$GradleVersion\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradleBat -PathType Leaf)) {
    throw "Gradle $GradleVersion is not provisioned at $gradleBat. Use the repository's Spatial Camera Panel build resolver."
}
if ([string]::IsNullOrWhiteSpace($AndroidHome) -or -not (Test-Path -LiteralPath $AndroidHome -PathType Container)) {
    throw "ANDROID_HOME or -AndroidHome must name a valid Android SDK directory for Spatial Camera Panel Kotlin compilation and JVM tests."
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath $JavaHome -PathType Container)) {
    throw "JAVA_HOME or -JavaHome must name a valid JDK directory for Spatial Camera Panel Kotlin compilation and JVM tests."
}
$previousAndroidHome = $env:ANDROID_HOME
$previousJavaHome = $env:JAVA_HOME
$previousGradleUserHome = $env:GRADLE_USER_HOME
$previousAppBuildDir = $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR
$previousRootBuildDir = $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR
try {
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidHome).Path
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:GRADLE_USER_HOME = Join-Path $repoRootPath "local-artifacts\gradle-user-home"
    $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR =
        Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-host\app"
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR =
        Join-Path $repoRootPath "local-artifacts\spatial-camera-panel-host\root"
    & $gradleBat `
        --no-daemon `
        --console=plain `
        -p (Join-Path $repoRootPath "apps\spatial-camera-panel-android") `
        :app:compileDebugKotlin `
        :app:testDebugUnitTest
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial Camera Panel Kotlin compilation or JVM unit tests failed."
    }
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleUserHome
    $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR = $previousAppBuildDir
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = $previousRootBuildDir
}

Push-Location -LiteralPath $repoRootPath
try {
    cargo test -p spatial-camera-panel-native-receipt surface_particle
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial Camera Panel surface-particle Rust tests failed."
    }
    cargo test -p spatial-camera-panel-native-receipt camera_latency
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial Camera Panel camera-latency Rust tests failed."
    }
    cargo test -p spatial-camera-panel-native-receipt camera_hwb_freshness
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial Camera Panel camera-HWB freshness Rust tests failed."
    }
    cargo test -p spatial-camera-panel-native-receipt projection_surface_displacement
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial Camera Panel projection-surface displacement Rust tests failed."
    }
    cargo test -p spatial-camera-panel-native-receipt projection_surface_features
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial Camera Panel projection-surface feature Rust tests failed."
    }
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
        -OpaqueProjectionVertexShader $OpaqueProjectionVertexShader `
        -OpaqueProjectionEffect $OpaqueProjectionEffect `
        -ProjectionSurfaceUniformAbiVersion $ProjectionSurfaceUniformAbiVersion `
        -PrivateSurfaceParticleProfilePath $PrivateSurfaceParticleProfilePath `
        -PrivateSurfaceParticleShader $PrivateSurfaceParticleShader `
        -PrivateSurfaceParticlePayloadDir $PrivateSurfaceParticlePayloadDir `
        -PrivateSurfaceParticleMarkerPrefix $PrivateSurfaceParticleMarkerPrefix `
        -HandMeshRigAssetDir $HandMeshRigAssetDir `
        -ProductId $ProductId `
        -AppId $AppId `
        -AppLabel $AppLabel `
        -ApkFileName $ApkFileName `
        -OutDir $OutDir
}

Write-Host "Spatial Camera Panel Android validation passed"
