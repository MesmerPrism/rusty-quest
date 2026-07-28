[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$InputPath,

    [Parameter(Mandatory)]
    [string]$OutPath,

    [string]$AndroidHome = $env:ANDROID_HOME,

    [string]$JavaHome = $env:JAVA_HOME,

    [string]$GradleHome = $env:GRADLE_HOME,

    [string]$GradleVersion = "9.4.1"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$inputFile = (Resolve-Path -LiteralPath $InputPath).Path
$outputFile = [IO.Path]::GetFullPath($OutPath)
if ((Get-Item -LiteralPath $inputFile).Length -gt 65536) {
    throw "Hostess replay control state exceeds the 64 KiB limit."
}
if ([string]::Equals($inputFile, $outputFile, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Input and output paths must be distinct."
}
$outputDirectory = Split-Path -Parent $outputFile
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

$resolvedGradleHome = if ([string]::IsNullOrWhiteSpace($GradleHome)) {
    Join-Path $repoRoot "local-artifacts\tools\gradle-$GradleVersion"
} else {
    (Resolve-Path -LiteralPath $GradleHome -ErrorAction Stop).Path
}
if ((Split-Path -Leaf $resolvedGradleHome) -cne "gradle-$GradleVersion") {
    throw "GradleHome must name the exact gradle-$GradleVersion distribution directory."
}
$gradle = Join-Path $resolvedGradleHome "bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "Gradle $GradleVersion is not provisioned at $gradle. Supply -GradleHome or use the repository's Spatial Camera Panel build resolver."
}
if ([string]::IsNullOrWhiteSpace($AndroidHome) -or -not (Test-Path -LiteralPath $AndroidHome -PathType Container)) {
    throw "ANDROID_HOME or -AndroidHome must name a valid Android SDK directory."
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath $JavaHome -PathType Container)) {
    throw "JAVA_HOME or -JavaHome must name a valid JDK directory."
}
$previousAndroidHome = $env:ANDROID_HOME
$previousJavaHome = $env:JAVA_HOME
$previousAppBuildDir = $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR
$previousRootBuildDir = $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR
try {
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidHome).Path
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR =
        Join-Path $repoRoot "local-artifacts\spatial-camera-control-profile-converter\app"
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR =
        Join-Path $repoRoot "local-artifacts\spatial-camera-control-profile-converter\root"
    & $gradle `
        --no-daemon `
        --console=plain `
        -p (Join-Path $repoRoot "apps\spatial-camera-panel-android") `
        :app:convertHostessReplayControlState `
        "-PhostessControlStateInput=$inputFile" `
        "-PquestControlProfileOutput=$outputFile"
    if ($LASTEXITCODE -ne 0) {
        throw "Quest-owned Hostess control-state conversion failed."
    }
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    $env:JAVA_HOME = $previousJavaHome
    $env:RUSTY_QUEST_SPATIAL_APP_BUILD_DIR = $previousAppBuildDir
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = $previousRootBuildDir
}
if (-not (Test-Path -LiteralPath $outputFile -PathType Leaf)) {
    throw "Quest converter did not create the requested profile."
}
Write-Output $outputFile
