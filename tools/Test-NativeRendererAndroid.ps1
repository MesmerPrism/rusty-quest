param(
    [switch]$Build,
    [switch]$SkipProfileMatrix,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$buildPath = Join-Path $PSScriptRoot "Build-NativeRendererAndroid.ps1"
$profileMatrixToolPath = Join-Path $PSScriptRoot "Test-NativeRendererProfileMatrix.ps1"
$runtimeEvidenceToolPath = Join-Path $PSScriptRoot "Test-NativeRendererRuntimeEvidence.ps1"
$propertyParityToolPath = Join-Path $PSScriptRoot "check_native_renderer_property_parity.py"
$androidScaffoldStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererAndroidScaffoldStatic.ps1"
$propertyManifestStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererPropertyManifestStatic.ps1"
$publicBoundaryStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererPublicBoundaryStatic.ps1"
$environmentDepthStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererEnvironmentDepthStatic.ps1"
$runtimeEvidenceStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererRuntimeEvidenceStatic.ps1"
$runtimeProfileStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererRuntimeProfileStatic.ps1"
$stimulusVolumeStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererStimulusVolumeStatic.ps1"
$projectionTargetStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererProjectionTargetStatic.ps1"
$handVisualStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererHandVisualStatic.ps1"
$gpuSdfStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererGpuSdfStatic.ps1"
$cameraGuideStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererCameraGuideStatic.ps1"
$openXrVulkanStaticCheckPath = Join-Path $PSScriptRoot "checks\Test-NativeRendererOpenXrVulkanStatic.ps1"
$nativeRendererPropertyManifestPath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-property-manifest.json"
$runtimeEvidenceFixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-replay-visual-proof.logcat.txt"
$liveHandDiagnosticPendingFixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-live-hand-visual-diagnostic-pending.logcat.txt"
$environmentDepthParticlesEvidenceFixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-meta-environment-depth-particles.logcat.txt"
$environmentDepthSurfaceSupportEvidenceFixturePath = Join-Path $repoRoot "fixtures\native-renderer\native-renderer-meta-environment-depth-surface-support.logcat.txt"
$runtimeEvidenceDamagedPath = Join-Path $repoRoot "fixtures\damaged\native-renderer-replay-visual-missing-mesh.logcat.txt"
$runtimeEvidenceDamagedPerformancePath = Join-Path $repoRoot "fixtures\damaged\native-renderer-replay-performance-budget-miss.logcat.txt"
$liveHandPrematureAcceptanceDamagedPath = Join-Path $repoRoot "fixtures\damaged\native-renderer-live-hand-visual-premature-acceptance.logcat.txt"

$requiredPaths = @(
    $buildPath, $profileMatrixToolPath,
    $runtimeEvidenceToolPath, $propertyParityToolPath,
    $androidScaffoldStaticCheckPath, $propertyManifestStaticCheckPath, $publicBoundaryStaticCheckPath,
    $environmentDepthStaticCheckPath, $runtimeEvidenceStaticCheckPath,
    $runtimeProfileStaticCheckPath, $stimulusVolumeStaticCheckPath,
    $projectionTargetStaticCheckPath, $handVisualStaticCheckPath, $gpuSdfStaticCheckPath,
    $cameraGuideStaticCheckPath, $openXrVulkanStaticCheckPath,
    $nativeRendererPropertyManifestPath, $runtimeEvidenceFixturePath, $liveHandDiagnosticPendingFixturePath,
    $environmentDepthParticlesEvidenceFixturePath, $environmentDepthSurfaceSupportEvidenceFixturePath,
    $runtimeEvidenceDamagedPath, $runtimeEvidenceDamagedPerformancePath,
    $liveHandPrematureAcceptanceDamagedPath
)

foreach ($path in $requiredPaths) {
    if (-not (Test-Path $path)) {
        throw "Missing native renderer Android file: $path"
    }
}

& $androidScaffoldStaticCheckPath -RepoRoot $repoRoot
& $propertyManifestStaticCheckPath `
    -ManifestPath $nativeRendererPropertyManifestPath `
    -PropertyParityToolPath $propertyParityToolPath

& $publicBoundaryStaticCheckPath -RepoRoot $repoRoot
& $environmentDepthStaticCheckPath -RepoRoot $repoRoot
& $runtimeEvidenceStaticCheckPath -RepoRoot $repoRoot
& $runtimeProfileStaticCheckPath -RepoRoot $repoRoot
& $stimulusVolumeStaticCheckPath -RepoRoot $repoRoot
& $projectionTargetStaticCheckPath -RepoRoot $repoRoot
& $handVisualStaticCheckPath -RepoRoot $repoRoot
& $gpuSdfStaticCheckPath -RepoRoot $repoRoot
& $cameraGuideStaticCheckPath -RepoRoot $repoRoot
& $openXrVulkanStaticCheckPath -RepoRoot $repoRoot

if (-not $SkipProfileMatrix) {
    & $profileMatrixToolPath | Out-Null
}
& $runtimeEvidenceToolPath `
    -LogcatPath $runtimeEvidenceFixturePath `
    -RequireCameraProjection `
    -RequireReplayVisualProof `
    -RequireGuideGraph `
    -RequireSdfVisual `
    -RequireGpuTimestampReady `
    -RequirePerformanceBudget `
    -RequirePrivateSlotNoPayload | Out-Null

& $runtimeEvidenceToolPath `
    -LogcatPath $environmentDepthParticlesEvidenceFixturePath `
    -RequireEnvironmentDepthParticles `
    -ExpectedEnvironmentDepthParticleCount 32768 `
    -MinimumEnvironmentDepthSourceDepthSamples 1 `
    -RequirePrivateSlotNoPayload | Out-Null

& $runtimeEvidenceToolPath `
    -LogcatPath $environmentDepthSurfaceSupportEvidenceFixturePath `
    -RequireEnvironmentDepthParticles `
    -RequireEnvironmentDepthSurfaceSupport `
    -ExpectedEnvironmentDepthParticleCount 32768 `
    -MinimumEnvironmentDepthSourceDepthSamples 1 `
    -RequirePrivateSlotNoPayload | Out-Null

try {
    & $runtimeEvidenceToolPath `
        -LogcatPath $runtimeEvidenceDamagedPath `
        -RequireReplayVisualProof | Out-Null
    throw "Damaged native renderer runtime evidence fixture was accepted."
} catch {
    if ($_.Exception.Message -eq "Damaged native renderer runtime evidence fixture was accepted.") {
        throw
    }
}

try {
    & $runtimeEvidenceToolPath `
        -LogcatPath $runtimeEvidenceDamagedPerformancePath `
        -RequirePerformanceBudget | Out-Null
    throw "Damaged native renderer performance fixture was accepted."
} catch {
    if ($_.Exception.Message -eq "Damaged native renderer performance fixture was accepted.") {
        throw
    }
}

& $runtimeEvidenceToolPath `
    -LogcatPath $liveHandDiagnosticPendingFixturePath `
    -RequireLiveVisualDiagnosticCaveat | Out-Null

try {
    & $runtimeEvidenceToolPath `
        -LogcatPath $liveHandPrematureAcceptanceDamagedPath `
        -RequireLiveVisualDiagnosticCaveat | Out-Null
    throw "Damaged native renderer live-hand visual acceptance fixture was accepted."
} catch {
    if ($_.Exception.Message -eq "Damaged native renderer live-hand visual acceptance fixture was accepted.") {
        throw
    }
}

if ($Build) {
    & (Join-Path $PSScriptRoot "Build-NativeRendererAndroid.ps1") -AndroidHome $AndroidHome -JavaHome $JavaHome -NdkHome $NdkHome | Out-Host
}

Write-Output "Rusty Quest native renderer Android validation passed"
