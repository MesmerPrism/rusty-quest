param(
    [switch]$Build,
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = Resolve-Path $RepoRoot
$staticCheckPath = Join-Path $PSScriptRoot "checks\Test-KuramotoSpatialSdkAndroidStatic.ps1"
$buildPath = Join-Path $PSScriptRoot "Build-KuramotoSpatialSdkAndroid.ps1"

if (-not (Test-Path -LiteralPath $staticCheckPath)) {
    throw "Missing Kuramoto Spatial SDK static check: $staticCheckPath"
}
if (-not (Test-Path -LiteralPath $buildPath)) {
    throw "Missing Kuramoto Spatial SDK build wrapper: $buildPath"
}

& $staticCheckPath -RepoRoot $repoRootPath

if ($Build) {
    & $buildPath -RepoRoot $repoRootPath -AndroidHome $AndroidHome -JavaHome $JavaHome
}

Write-Host "Kuramoto Spatial SDK Android validation passed"
