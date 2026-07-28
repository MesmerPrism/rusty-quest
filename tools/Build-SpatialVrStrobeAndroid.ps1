param(
    [string]$RepoRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$GradleVersion = "9.4.1",
    [string]$Keystore = "",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"

function Get-FileSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Join-Path $PSScriptRoot ".."
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$gradleRoot = Join-Path $repoRootPath "apps\spatial-camera-panel-android"
$strobeModule = Join-Path $repoRootPath "apps\spatial-vr-strobe-android\app"
$targetRoot = Join-Path $repoRootPath "target"
$productBuildRoot = Join-Path $targetRoot "spatial-product-builds\spatial-vr-strobe"
$strobeBuildDir = Join-Path $productBuildRoot "gradle-strobe-app"
$rootBuildDir = Join-Path $productBuildRoot "gradle-root"
$projectCacheDir = Join-Path $productBuildRoot "gradle-project-cache"

foreach ($required in @($gradleRoot, $strobeModule)) {
    if (-not (Test-Path -LiteralPath $required -PathType Container)) {
        throw "Missing Spatial VR Strobe build path: $required"
    }
}
if ([string]::IsNullOrWhiteSpace($AndroidHome) -or -not (Test-Path -LiteralPath $AndroidHome)) {
    throw "AndroidHome must point to an installed Android SDK."
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath $JavaHome)) {
    throw "JavaHome must point to a Java 17 installation."
}
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    $NdkHome = Get-ChildItem -LiteralPath (Join-Path $AndroidHome "ndk") -Directory |
        Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($NdkHome) -or -not (Test-Path -LiteralPath $NdkHome)) {
    throw "NdkHome must point to an installed Android NDK."
}

$gradleBat = Join-Path $repoRootPath "local-artifacts\tools\gradle-$GradleVersion\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradleBat -PathType Leaf)) {
    throw "Managed Gradle $GradleVersion is missing: $gradleBat. Run the Camera build bootstrap once or install that managed distribution."
}
if (-not [string]::IsNullOrWhiteSpace($Keystore)) {
    if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
        throw "Keystore not found: $Keystore"
    }
    $Keystore = (Resolve-Path -LiteralPath $Keystore).Path
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "spatial-vr-strobe-android"
}
$resolvedTargetRoot = [System.IO.Path]::GetFullPath($targetRoot).TrimEnd([char[]]@('\', '/'))
$resolvedOutDir = [System.IO.Path]::GetFullPath($OutDir).TrimEnd([char[]]@('\', '/'))
if (-not $resolvedOutDir.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must remain under the repo target directory: $resolvedOutDir"
}
New-Item -ItemType Directory -Force -Path $resolvedOutDir, $strobeBuildDir, $rootBuildDir, $projectCacheDir | Out-Null

$previous = @{
    ANDROID_HOME = $env:ANDROID_HOME
    ANDROID_NDK_HOME = $env:ANDROID_NDK_HOME
    JAVA_HOME = $env:JAVA_HOME
    RUSTY_QUEST_ANDROID_NDK_VERSION = $env:RUSTY_QUEST_ANDROID_NDK_VERSION
    RUSTY_QUEST_SPATIAL_STROBE_BUILD_DIR = $env:RUSTY_QUEST_SPATIAL_STROBE_BUILD_DIR
    RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR
    RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE = $env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE
}
try {
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_NDK_HOME = $NdkHome
    $env:JAVA_HOME = $JavaHome
    $env:RUSTY_QUEST_ANDROID_NDK_VERSION = Split-Path -Leaf $NdkHome
    $env:RUSTY_QUEST_SPATIAL_STROBE_BUILD_DIR = $strobeBuildDir
    $env:RUSTY_QUEST_SPATIAL_ROOT_BUILD_DIR = $rootBuildDir
    if ([string]::IsNullOrWhiteSpace($Keystore)) {
        Remove-Item Env:\RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE -ErrorAction SilentlyContinue
    } else {
        $env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE = $Keystore
    }

    & $gradleBat `
        --no-daemon `
        --console=plain `
        --project-cache-dir $projectCacheDir `
        -p $gradleRoot `
        :spatial-sdk-shared:testDebugUnitTest `
        :strobe-app:testDebugUnitTest `
        :strobe-app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Standalone Spatial VR Strobe Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    foreach ($name in $previous.Keys) {
        $value = $previous[$name]
        if ($null -eq $value) {
            Remove-Item "Env:\$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item "Env:\$name" $value
        }
    }
}

$apkSource = Join-Path $strobeBuildDir "outputs\apk\debug\strobe-app-debug.apk"
if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) {
    throw "Strobe module did not produce its expected APK: $apkSource"
}
$apkOut = Join-Path $resolvedOutDir "rusty-quest-spatial-vr-strobe.apk"
Copy-Item -LiteralPath $apkSource -Destination $apkOut -Force

$aapt2 = Get-ChildItem -LiteralPath (Join-Path $AndroidHome "build-tools") -Recurse -Filter "aapt2.exe" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if ([string]::IsNullOrWhiteSpace($aapt2)) {
    throw "aapt2 was not found under the Android SDK."
}
$badging = (& $aapt2 dump badging $apkOut) -join "`n"
if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='io\.github\.mesmerprism\.rustyquest\.spatial_vr_strobe'") {
    throw "Built APK does not have the standalone Strobe package identity."
}
if ($badging -match "uses-permission: name='(android\.permission\.CAMERA|horizonos\.permission\.(HEADSET_CAMERA|SPATIAL_CAMERA))'") {
    throw "Standalone Strobe APK unexpectedly declares camera permissions."
}

$receipt = [ordered]@{
    schema = "rusty.quest.spatial_vr_strobe.build_receipt.v1"
    product_id = "spatial-vr-strobe"
    gradle_module = ":strobe-app"
    package_name = "io.github.mesmerprism.rustyquest.spatial_vr_strobe"
    activity = "io.github.mesmerprism.rustyquest.spatial_vr_strobe.SpatialVrStrobeActivity"
    camera_permissions_declared = $false
    apk = $apkOut
    apk_sha256 = Get-FileSha256 -Path $apkOut
    apk_size_bytes = (Get-Item -LiteralPath $apkOut).Length
    built_at_utc = [DateTime]::UtcNow.ToString("o")
}
$receiptPath = Join-Path $resolvedOutDir "build-receipt.json"
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding utf8

Write-Host "Spatial VR Strobe standalone build passed"
Write-Host "APK: $apkOut"
Write-Host "Receipt: $receiptPath"
