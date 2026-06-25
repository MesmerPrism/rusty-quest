param(
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleVersion = "9.4.1",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$File,
        [string[]]$Arguments = @()
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Invoke-DownloadFile {
    param(
        [Parameter(Mandatory=$true)][string]$Uri,
        [Parameter(Mandatory=$true)][string]$OutFile
    )
    $client = [System.Net.WebClient]::new()
    try {
        $client.DownloadFile($Uri, $OutFile)
    } finally {
        $client.Dispose()
    }
}

function Invoke-DownloadText {
    param([Parameter(Mandatory=$true)][string]$Uri)
    $client = [System.Net.WebClient]::new()
    try {
        return $client.DownloadString($Uri)
    } finally {
        $client.Dispose()
    }
}

function Resolve-Gradle {
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string]$Version
    )
    $localRoot = Join-Path $RepoRoot "local-artifacts"
    $toolsRoot = Join-Path $localRoot "tools"
    $downloadsRoot = Join-Path $localRoot "downloads"
    $gradleHome = Join-Path $toolsRoot "gradle-$Version"
    $gradleBat = Join-Path $gradleHome "bin\gradle.bat"
    if (Test-Path -LiteralPath $gradleBat) {
        return $gradleBat
    }

    New-Item -ItemType Directory -Force -Path $toolsRoot, $downloadsRoot | Out-Null
    $zipPath = Join-Path $downloadsRoot "gradle-$Version-bin.zip"
    $distributionUrl = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
    if (-not (Test-Path -LiteralPath $zipPath)) {
        Invoke-DownloadFile -Uri $distributionUrl -OutFile $zipPath
    }

    $expectedSha = (Invoke-DownloadText -Uri "$distributionUrl.sha256").Trim().Split()[0].ToLowerInvariant()
    $actualSha = Get-FileSha256 -Path $zipPath
    if ($expectedSha -ne $actualSha) {
        throw "Gradle distribution SHA-256 mismatch for $zipPath. Expected $expectedSha but found $actualSha."
    }

    Expand-Archive -LiteralPath $zipPath -DestinationPath $toolsRoot -Force
    if (-not (Test-Path -LiteralPath $gradleBat)) {
        throw "Gradle distribution did not provide expected executable: $gradleBat"
    }
    return $gradleBat
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required. Activate the Quest/Android toolchain first."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required. Activate the Quest/Android toolchain first."
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Join-Path $PSScriptRoot ".."
}
$repoRoot = Resolve-Path $RepoRoot
$appRoot = Resolve-Path (Join-Path $repoRoot "apps\kuramoto-spatial-sdk-android")
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "kuramoto-spatial-sdk-android"
}

New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
$resolvedTargetRoot = (Resolve-Path $targetRoot).Path.TrimEnd("\")
$resolvedOutFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $resolvedOutFull.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOutFull"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$gradleBat = Resolve-Gradle -RepoRoot ([string]$repoRoot) -Version $GradleVersion
$gradleUserHome = Join-Path $repoRoot "local-artifacts\gradle-user-home"
New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null

$previousAndroidHome = $env:ANDROID_HOME
$previousJavaHome = $env:JAVA_HOME
$previousGradleUserHome = $env:GRADLE_USER_HOME
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $gradleUserHome
    Invoke-Checked "Kuramoto Spatial SDK Gradle build" $gradleBat @(
        "--no-daemon",
        "--console=plain",
        "-p", ([string]$appRoot),
        ":app:assembleDebug"
    )
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    $env:JAVA_HOME = $previousJavaHome
    if ($null -eq $previousGradleUserHome) {
        Remove-Item Env:\GRADLE_USER_HOME -ErrorAction SilentlyContinue
    } else {
        $env:GRADLE_USER_HOME = $previousGradleUserHome
    }
}

$apkSource = Join-Path $appRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apkSource)) {
    throw "Gradle build did not produce expected APK: $apkSource"
}

$apkOut = Join-Path $OutDir "rusty-quest-kuramoto-spatial-sdk.apk"
Copy-Item -LiteralPath $apkSource -Destination $apkOut -Force
$sha256 = Get-FileSha256 -Path $apkOut

$manifest = [ordered]@{
    '$schema' = "rusty.quest.kuramoto_spatial_sdk_android.build_manifest.v1"
    package_name = "io.github.mesmerprism.rustyquest.kuramoto_spatial"
    activity = "io.github.mesmerprism.rustyquest.kuramoto_spatial/.KuramotoSpatialActivity"
    app_lane = "kuramoto-spatial-sdk-android"
    authority = "rusty.quest.kuramoto_spatial_sdk_panel"
    target_runtime = "quest-spatial-sdk-appsystemactivity-panel"
    spatial_sdk_version = "0.13.1"
    android_gradle_plugin_version = "8.11.1"
    kotlin_version = "2.1.0"
    gradle_version = $GradleVersion
    native_renderer_package_preserved = "io.github.mesmerprism.rustyquest.native_renderer"
    native_renderer_spatial_sdk_packaged = $false
    panel_registration_id = "kuramoto_experiment_panel"
    panel_shape_meters = [ordered]@{
        width = 1.24
        height = 0.86
    }
    panel_display = [ordered]@{
        option = "DpPerMeterDisplayOptions"
        dp_per_meter = 720
    }
    panel_transform_runtime_controls = @("Transform(Pose(Vector3))", "Scale(Vector3)")
    questionnaire_schema = "rusty.kuramoto.mesh.experiment_questionnaire.v1"
    high_rate_json_payload = $false
    hand_rendering_expected = $false
    apk_path = $apkOut
    apk_sha256 = $sha256
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

Write-Output $apkOut
