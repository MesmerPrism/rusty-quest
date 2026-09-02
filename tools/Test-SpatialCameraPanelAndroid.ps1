param(
    [switch]$Build,
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$NdkVersion = "27.2.12479018",
    [string]$BuildToolsVersion = "36.0.0",
    [string]$GradleVersion = "9.4.1",
    [ValidateSet("DevFast", "Candidate")]
    [string]$BuildMode = "DevFast",
    [ValidateSet("Static", "Dynamic")]
    [string]$RustStdLinkage = "Static",
    [string]$BuildCacheRoot = $env:RUSTY_QUEST_BUILD_CACHE_ROOT,
    [string]$PrivateFeatureSourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR,
    [string]$PrivateFeatureResourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR,
    [string]$PrivateFeatureTestSourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_SRC_DIR,
    [string]$PrivateFeatureTestResourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_RES_DIR,
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
    [string]$Keystore = "",
    [string]$ExpectedSignerSha256 = "",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$buildLaneMutex = $null
$buildLaneMutexOwned = $false

trap {
    $caughtValidationError = $_
    if ($buildLaneMutexOwned -and $null -ne $buildLaneMutex) {
        try { $buildLaneMutex.ReleaseMutex() } catch { }
        $buildLaneMutexOwned = $false
    }
    if ($null -ne $buildLaneMutex) {
        try { $buildLaneMutex.Dispose() } catch { }
        $buildLaneMutex = $null
    }
    throw $caughtValidationError
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path $RepoRoot
$workspaceDrive = [System.IO.Path]::GetPathRoot([string]$repoRootPath).TrimEnd([char[]]@('\', '/'))
if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    $AndroidHome = Join-Path $workspaceDrive "Work\tools\Android\windows-sdk"
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $workspaceDrive "Work\tools\Java\temurin-17"
}
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    $NdkHome = Join-Path $AndroidHome "ndk\$NdkVersion"
}
if ([string]::IsNullOrWhiteSpace($BuildCacheRoot)) {
    $BuildCacheRoot = Join-Path $workspaceDrive "b\mv"
}
$BuildCacheRoot = [System.IO.Path]::GetFullPath($BuildCacheRoot).TrimEnd([char[]]@('\', '/'))
if ($BuildCacheRoot.Length -gt 64) {
    throw "BuildCacheRoot must remain deliberately short (64 characters or fewer)."
}
$cacheRootHashAlgorithm = [System.Security.Cryptography.SHA256]::Create()
try {
    $cacheRootHashBytes = $cacheRootHashAlgorithm.ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes($BuildCacheRoot.ToLowerInvariant()))
    $cacheRootHash = ([System.BitConverter]::ToString($cacheRootHashBytes)).Replace("-", "").ToLowerInvariant()
} finally {
    $cacheRootHashAlgorithm.Dispose()
}
$buildLaneMutexName = "Local\RustyQuestSpatialBuild-$($cacheRootHash.Substring(0, 16))"
$buildLaneMutex = [System.Threading.Mutex]::new($false, $buildLaneMutexName)
try {
    $buildLaneMutexOwned = $buildLaneMutex.WaitOne([TimeSpan]::FromMinutes(30))
} catch [System.Threading.AbandonedMutexException] {
    $buildLaneMutexOwned = $true
}
if (-not $buildLaneMutexOwned) {
    throw "Timed out waiting for the serialized stable Spatial Camera Panel build cache lane."
}
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
$profileLibraryCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelProfileLibraryStatic.ps1"
$cameraLatencyCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelCameraLatencyDiagnosticStatic.ps1"
$fragmentProbeCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialFragmentProbeStatic.ps1"
$vrStrobeCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialVrStrobeStatic.ps1"
$panelFacingCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialSdkPanelFacingStatic.ps1"
$productIsolationCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialProductIsolationStatic.ps1"
$bufferHubControlsCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelBufferHubControlsStatic.ps1"
$sharedMediaQuiescenceCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelSharedMediaQuiescenceStatic.ps1"
$buildWorkflowCheckPath = Join-Path $PSScriptRoot "checks\Test-SpatialCameraPanelBuildWorkflowStatic.ps1"
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
if (-not (Test-Path -LiteralPath $profileLibraryCheckPath)) {
    throw "Missing Spatial Camera Panel profile-library static check: $profileLibraryCheckPath"
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
if (-not (Test-Path -LiteralPath $bufferHubControlsCheckPath)) {
    throw "Missing Spatial Camera Panel Buffer/Hub controls check: $bufferHubControlsCheckPath"
}
if (-not (Test-Path -LiteralPath $sharedMediaQuiescenceCheckPath)) {
    throw "Missing Spatial Camera Panel shared-media quiescence check: $sharedMediaQuiescenceCheckPath"
}
if (-not (Test-Path -LiteralPath $buildWorkflowCheckPath)) {
    throw "Missing Spatial Camera Panel fast build workflow check: $buildWorkflowCheckPath"
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
& $profileLibraryCheckPath -RepoRoot $repoRootPath
& $cameraLatencyCheckPath -RepoRoot $repoRootPath
& $fragmentProbeCheckPath -RepoRoot $repoRootPath
& $vrStrobeCheckPath -RepoRoot $repoRootPath
& $panelFacingCheckPath -RepoRoot $repoRootPath
& $productIsolationCheckPath -RepoRoot $repoRootPath
& $bufferHubControlsCheckPath -RepoRoot $repoRootPath
& $sharedMediaQuiescenceCheckPath -RepoRoot $repoRootPath
& $buildWorkflowCheckPath -RepoRoot $repoRootPath

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
$previousPrivateFeatureSourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR
$previousPrivateFeatureResourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR
$previousPrivateFeatureTestSourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_SRC_DIR
$previousPrivateFeatureTestResourceDir = $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_RES_DIR
try {
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidHome).Path
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:GRADLE_USER_HOME = Join-Path $BuildCacheRoot "gu"
    $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR =
        Join-Path $BuildCacheRoot "g\host\a"
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR =
        Join-Path $BuildCacheRoot "g\host\r"
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR = $PrivateFeatureSourceDir
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR = $PrivateFeatureResourceDir
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_SRC_DIR = $PrivateFeatureTestSourceDir
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_RES_DIR = $PrivateFeatureTestResourceDir
    $gradleArguments = @(
        $(if ($BuildMode -eq "DevFast") { "--daemon" } else { "--no-daemon" }),
        "--console=plain",
        "--build-cache",
        "--project-cache-dir", (Join-Path $BuildCacheRoot "gp"),
        "-p", (Join-Path $repoRootPath "apps\spatial-camera-panel-android"),
        ":app:compileDebugKotlin",
        ":app:testDebugUnitTest"
    )
    & $gradleBat @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Spatial Camera Panel Kotlin compilation or JVM unit tests failed."
    }
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleUserHome
    $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR = $previousAppBuildDir
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = $previousRootBuildDir
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_SRC_DIR = $previousPrivateFeatureSourceDir
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_RES_DIR = $previousPrivateFeatureResourceDir
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_SRC_DIR = $previousPrivateFeatureTestSourceDir
    $env:RUSTY_QUEST_SPATIAL_PRIVATE_FEATURE_TEST_RES_DIR = $previousPrivateFeatureTestResourceDir
}

Push-Location -LiteralPath $repoRootPath
try {
    $previousCargoTargetDir = $env:CARGO_TARGET_DIR
    $env:CARGO_TARGET_DIR = Join-Path $BuildCacheRoot "c\host"
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
    if ($null -eq $previousCargoTargetDir) {
        Remove-Item Env:\CARGO_TARGET_DIR -ErrorAction SilentlyContinue
    } else {
        $env:CARGO_TARGET_DIR = $previousCargoTargetDir
    }
    Pop-Location
}

if ($Build) {
    & $buildPath `
        -RepoRoot $repoRootPath `
        -AndroidHome $AndroidHome `
        -JavaHome $JavaHome `
        -NdkHome $NdkHome `
        -NdkVersion $NdkVersion `
        -BuildToolsVersion $BuildToolsVersion `
        -BuildMode $BuildMode `
        -RustStdLinkage $RustStdLinkage `
        -BuildCacheRoot $BuildCacheRoot `
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
        -PrivateFeatureSourceDir $PrivateFeatureSourceDir `
        -PrivateFeatureResourceDir $PrivateFeatureResourceDir `
        -ProductId $ProductId `
        -AppId $AppId `
        -AppLabel $AppLabel `
        -ApkFileName $ApkFileName `
        -Keystore $Keystore `
        -ExpectedSignerSha256 $ExpectedSignerSha256 `
        -OutDir $OutDir
}

$buildLaneMutex.ReleaseMutex()
$buildLaneMutexOwned = $false
$buildLaneMutex.Dispose()
$buildLaneMutex = $null
Write-Host "Spatial Camera Panel Android validation passed"
