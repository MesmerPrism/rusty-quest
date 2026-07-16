param(
    [string]$ApkPath = "target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$ProfilePath = "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json",
    [string]$OutDir = "",
    [int]$RunSeconds = 8,
    [double]$TargetDistanceMeters = 1.0,
    [double]$ToleranceMeters = 0.15,
    [double]$MinimumCenterConfidence = 0.5,
    [int]$MinimumCenterWindowValidCount = 5,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun
)

$ErrorActionPreference = "Stop"

if ($TargetDistanceMeters -le 0.0) {
    throw "TargetDistanceMeters must be positive."
}
if ($ToleranceMeters -le 0.0) {
    throw "ToleranceMeters must be positive."
}
if ($MinimumCenterConfidence -lt 0.0 -or $MinimumCenterConfidence -gt 1.0) {
    throw "MinimumCenterConfidence must be in 0..1."
}
if ($MinimumCenterWindowValidCount -lt 0) {
    throw "MinimumCenterWindowValidCount must be nonnegative."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $distanceLabel = $TargetDistanceMeters.ToString("0.###", [System.Globalization.CultureInfo]::InvariantCulture).Replace(".", "p")
    $OutDir = Join-Path $repoRoot "local-artifacts\native-renderer-envdepth-known-distance-${distanceLabel}m-$stamp"
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $repoRoot $OutDir
}

$smokeArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "Invoke-NativeRendererReplaySmoke.ps1"),
    "-ApkPath", $ApkPath,
    "-ProfilePath", $ProfilePath,
    "-EvidenceMode", "EnvironmentDepthParticles",
    "-OutDir", $OutDir,
    "-RunSeconds", $RunSeconds.ToString(),
    "-AllowFlatScreenshot",
    "-AllowPerformanceBudgetMiss",
    "-AllowLegacyLooseInputs",
    "-RequireEnvironmentDepthKnownDistance",
    "-ExpectedEnvironmentDepthCenterMeters",
    $TargetDistanceMeters.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    "-EnvironmentDepthCenterToleranceMeters",
    $ToleranceMeters.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    "-MinimumEnvironmentDepthCenterConfidence",
    $MinimumCenterConfidence.ToString([System.Globalization.CultureInfo]::InvariantCulture),
    "-MinimumEnvironmentDepthCenterWindowValidCount",
    $MinimumCenterWindowValidCount.ToString()
)

if (-not [string]::IsNullOrWhiteSpace($Adb)) {
    $smokeArgs += @("-Adb", $Adb)
}
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $smokeArgs += @("-Serial", $Serial)
}
if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) {
    $smokeArgs += @("-AdbServerPort", $AdbServerPort)
}
if ($SkipInstall) {
    $smokeArgs += "-SkipInstall"
}
if ($ClearLogcat) {
    $smokeArgs += "-ClearLogcat"
}
if ($StopAfterRun) {
    $smokeArgs += "-StopAfterRun"
}

& powershell @smokeArgs
if ($LASTEXITCODE -ne 0) {
    throw "Environment-depth known-distance proof failed with exit code $LASTEXITCODE"
}
