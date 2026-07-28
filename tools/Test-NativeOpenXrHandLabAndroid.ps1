param(
    [switch]$Build,
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$OpenXrLoader = "S:\Work\tools\Quest\openxr-loader\libopenxr_loader.so",
    [string]$OutDir = "",
    [string]$Keystore = "",
    [string]$RecordedHandCaptureDir = $env:RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR,
    [int]$RecordedHandFrameLimit = 12,
    [switch]$RequireRecordedHandCapture
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
}
$repoRootPath = (Resolve-Path $RepoRoot).Path
$specPath = Join-Path $repoRootPath "fixtures\native-app-builds\native-openxr-hand-lab.app.json"
$resolverPath = Join-Path $repoRootPath "tools\Resolve-NativeAppBuild.ps1"
$staticCheckPath = Join-Path $repoRootPath "tools\checks\Test-NativeAppBuildStatic.ps1"
$buildPath = Join-Path $repoRootPath "tools\Build-NativeRendererAndroid.ps1"
$lockPath = Join-Path $repoRootPath "local-artifacts\native-app-builds\native_openxr_hand_lab\feature-lock.json"

foreach ($path in @($specPath, $resolverPath, $staticCheckPath, $buildPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing native OpenXR hand lab file: $path"
    }
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File $resolverPath -AppSpec $specPath -DryRun
if ($LASTEXITCODE -ne 0) {
    throw "Native OpenXR hand lab app spec resolution failed with exit code $LASTEXITCODE"
}

& $staticCheckPath -RepoRoot $repoRootPath
if ($LASTEXITCODE -ne 0) {
    throw "Native app-build static validation failed with exit code $LASTEXITCODE"
}

if (-not (Test-Path -LiteralPath $lockPath)) {
    throw "Native OpenXR hand lab feature lock was not generated: $lockPath"
}

if ($Build) {
    $buildArgs = @{
        AndroidHome = $AndroidHome
        JavaHome = $JavaHome
        NdkHome = $NdkHome
        OpenXrLoader = $OpenXrLoader
        AppBuildLock = $lockPath
        RecordedHandFrameLimit = $RecordedHandFrameLimit
    }
    if (-not [string]::IsNullOrWhiteSpace($OutDir)) {
        $buildArgs["OutDir"] = $OutDir
    }
    if (-not [string]::IsNullOrWhiteSpace($Keystore)) {
        $buildArgs["Keystore"] = $Keystore
    }
    if (-not [string]::IsNullOrWhiteSpace($RecordedHandCaptureDir)) {
        $buildArgs["RecordedHandCaptureDir"] = $RecordedHandCaptureDir
    }
    if ($RequireRecordedHandCapture) {
        $buildArgs["RequireRecordedHandCapture"] = $true
    }
    & $buildPath @buildArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Native OpenXR hand lab APK build failed with exit code $LASTEXITCODE"
    }
}

Write-Host "Native OpenXR hand lab Android validation passed"
