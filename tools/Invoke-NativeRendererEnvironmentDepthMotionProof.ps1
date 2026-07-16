param(
    [string]$ApkPath = "target\native-renderer-android\rusty-quest-native-renderer.apk",
    [string]$ProfilePath = "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json",
    [string]$OutDir = "",
    [int]$RunSeconds = 12,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [int]$MinimumHeadMotionSamples = 120,
    [double]$MinimumYawDeg = 25.0,
    [double]$MinimumTranslationM = 0.0,
    [switch]$RequireEnvironmentDepthSurfaceSupport,
    [switch]$SkipInstall,
    [switch]$ClearLogcat,
    [switch]$StopAfterRun
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRoot "local-artifacts\native-renderer-envdepth-motion-proof-$stamp"
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
    "-MinimumEnvironmentDepthHeadMotionSamples", $MinimumHeadMotionSamples.ToString()
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
if ($MinimumYawDeg -gt 0.0) {
    $smokeArgs += @(
        "-MinimumEnvironmentDepthHeadMotionYawDeg",
        $MinimumYawDeg.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    )
}
if ($MinimumTranslationM -gt 0.0) {
    $smokeArgs += @(
        "-MinimumEnvironmentDepthHeadMotionTranslationM",
        $MinimumTranslationM.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    )
}
if ($RequireEnvironmentDepthSurfaceSupport) {
    $smokeArgs += "-RequireEnvironmentDepthSurfaceSupport"
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

& pwsh @smokeArgs
if ($LASTEXITCODE -ne 0) {
    throw "Environment-depth motion proof failed with exit code $LASTEXITCODE"
}
