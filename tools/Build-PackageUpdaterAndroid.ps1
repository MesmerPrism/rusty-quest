[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$GradlePath,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [Parameter(Mandatory = $true)]
    [string]$AndroidBuildToolsDirectory,
    [string]$AndroidNdkDirectory = "",
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)]
    [string]$ManifestUrl,
    [Parameter(Mandatory = $true)]
    [string]$TrustedKeyId,
    [Parameter(Mandatory = $true)]
    [string]$TrustedPublicKeyBase64Url,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedHttpsOrigin,
    [string]$ExpectedPackageName = "io.github.mesmerprism.rustykiosk.labs",
    [string]$ExpectedRolloutRing = "labs",
    [Parameter(Mandatory = $true)]
    [string]$ExpectedSignerSha256,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedUpdaterSignerSha256,
    [ValidateRange(1, 2147483647)]
    [int]$VersionCode = 1,
    [ValidatePattern("^0\.1\.0(?:-alpha\.[1-9][0-9]*)?$")]
    [string]$VersionName = "0.1.0",
    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,
    [Parameter(Mandatory = $true)]
    [securestring]$StorePassword,
    [Parameter(Mandatory = $true)]
    [string]$KeyAlias,
    [Parameter(Mandatory = $true)]
    [securestring]$KeyPassword,
    [string]$OutDir = "",
    [string]$InspectE2eApkPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "package_updater\BuildArtifactContract.ps1")
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Package Updater builds require PowerShell 7.6 Core or newer."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$projectRoot = Join-Path $repoRoot "apps\package-updater-android"
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $repoRoot "target\package-updater-android"
}
$OutDir = [System.IO.Path]::GetFullPath($OutDir)

foreach ($requiredPath in @(
        $GradlePath,
        $AndroidHome,
        $AndroidBuildToolsDirectory,
        $JavaHome,
        $KeystorePath
    )) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required Package Updater build path is missing: $requiredPath"
    }
}
Assert-PackageUpdaterManifestUrl `
    -ManifestUrl $ManifestUrl `
    -ExpectedHttpsOrigin $ExpectedHttpsOrigin
if ($TrustedKeyId -notmatch "^[A-Za-z0-9._-]{1,96}$" -or
    $TrustedPublicKeyBase64Url -notmatch "^[A-Za-z0-9_-]{43}$" -or
    $ExpectedPackageName -notmatch "^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$" -or
    $ExpectedRolloutRing -notmatch "^[A-Za-z0-9._-]{1,32}$" -or
    $ExpectedSignerSha256 -notmatch "^sha256:[0-9a-f]{64}$" -or
    $ExpectedUpdaterSignerSha256 -notmatch "^sha256:[0-9a-f]{64}$") {
    throw "Package Updater build policy contains a noncanonical value."
}
if ($ExpectedPackageName -cne
        "io.github.mesmerprism.rustykiosk.labs" -or
    $ExpectedRolloutRing -cne "labs") {
    throw "Rusty Package Updater Labs may target only Kiosk Labs on the exact labs rollout ring."
}

& pwsh -NoProfile -ExecutionPolicy Bypass -File (
    Join-Path $repoRoot "tools\checks\Test-PackageUpdaterAndroidStatic.ps1"
) -RepoRoot $repoRoot
if ($LASTEXITCODE -ne 0) {
    throw "Package Updater Android static validation failed."
}

$sourceRevision = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -notmatch "^[0-9a-f]{40}$") {
    throw "Could not resolve the exact Rusty Quest source revision."
}
if (-not [string]::IsNullOrWhiteSpace((& git -C $repoRoot status --porcelain))) {
    throw "Package Updater release build requires a clean Rusty Quest worktree."
}

$ndkRoot = if ([string]::IsNullOrWhiteSpace($AndroidNdkDirectory)) {
    Get-ChildItem -LiteralPath (Join-Path $AndroidHome "ndk") `
            -Directory -ErrorAction Stop |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
} else {
    Get-Item -LiteralPath (
        Resolve-Path -LiteralPath $AndroidNdkDirectory
    ).Path -ErrorAction Stop
}
if ($null -eq $ndkRoot) {
    throw "Package Updater native verifier requires an Android NDK."
}
$androidClang = Join-Path $ndkRoot.FullName `
    "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android34-clang.cmd"
if (-not (Test-Path -LiteralPath $androidClang -PathType Leaf)) {
    throw "Package Updater Android linker is missing: $androidClang"
}
$rustup = (Get-Command "rustup" -ErrorAction Stop).Source
& $rustup target add aarch64-linux-android
if ($LASTEXITCODE -ne 0) {
    throw "Could not provision the Package Updater Rust Android target."
}

$plainStorePassword =
    [System.Net.NetworkCredential]::new("", $StorePassword).Password
$plainKeyPassword =
    [System.Net.NetworkCredential]::new("", $KeyPassword).Password
$environment = @{
    ANDROID_HOME = $AndroidHome
    ANDROID_SDK_ROOT = $AndroidHome
    ANDROID_NDK_HOME = $ndkRoot.FullName
    JAVA_HOME = $JavaHome
    RUSTY_QUEST_PACKAGE_UPDATER_MANIFEST_URL = $ManifestUrl
    RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_KEY_ID = $TrustedKeyId
    RUSTY_QUEST_PACKAGE_UPDATER_TRUSTED_PUBLIC_KEY_BASE64 =
        $TrustedPublicKeyBase64Url
    RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_HTTPS_ORIGIN = $ExpectedHttpsOrigin
    RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_PACKAGE_NAME = $ExpectedPackageName
    RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_ROLLOUT_RING = $ExpectedRolloutRing
    RUSTY_QUEST_PACKAGE_UPDATER_EXPECTED_SIGNER_SHA256 = $ExpectedSignerSha256
    RUSTY_QUEST_PACKAGE_UPDATER_VERSION_CODE = "$VersionCode"
    RUSTY_QUEST_PACKAGE_UPDATER_VERSION_NAME = $VersionName
    RUSTY_QUEST_PACKAGE_UPDATER_KEYSTORE_PATH =
        [System.IO.Path]::GetFullPath($KeystorePath)
    RUSTY_QUEST_PACKAGE_UPDATER_KEYSTORE_PASSWORD = $plainStorePassword
    RUSTY_QUEST_PACKAGE_UPDATER_KEY_ALIAS = $KeyAlias
    RUSTY_QUEST_PACKAGE_UPDATER_KEY_PASSWORD = $plainKeyPassword
}

$process = Start-Process `
    -FilePath $GradlePath `
    -ArgumentList @(
        "-p",
        $projectRoot,
        ":app:assembleRelease",
        "--rerun-tasks",
        "--no-configuration-cache",
        "--no-daemon"
    ) `
    -WorkingDirectory $projectRoot `
    -Environment $environment `
    -NoNewWindow `
    -Wait `
    -PassThru
if ($process.ExitCode -ne 0) {
    throw "Package Updater release build failed with exit code $($process.ExitCode)."
}

$builtApk = Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $builtApk -PathType Leaf)) {
    throw "Package Updater release APK was not produced."
}
$AndroidBuildToolsDirectory = (
    Resolve-Path -LiteralPath $AndroidBuildToolsDirectory
).Path
$buildToolsVersion = Split-Path -Leaf $AndroidBuildToolsDirectory
$aapt2 = Join-Path $AndroidBuildToolsDirectory "aapt2.exe"
$apkSigner = Join-Path $AndroidBuildToolsDirectory "apksigner.bat"
foreach ($tool in @($aapt2, $apkSigner)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Pinned Android build tool is missing: $tool"
    }
}
$releaseBadging = @(& $aapt2 dump badging $builtApk 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Pinned aapt2 could not inspect release APK badging."
}
$releasePermissions = @(& $aapt2 dump permissions $builtApk 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Pinned aapt2 could not inspect release APK permissions."
}
$releaseManifestTree = @(
    & $aapt2 dump xmltree --file AndroidManifest.xml $builtApk 2>&1
)
if ($LASTEXITCODE -ne 0) {
    throw "Pinned aapt2 could not inspect the merged release manifest."
}
$releaseIdentity = Assert-PackageUpdaterReleaseArtifact `
    -Badging $releaseBadging `
    -Permissions $releasePermissions `
    -ManifestTree $releaseManifestTree `
    -ExpectedPackageName $ExpectedPackageName `
    -ExpectedVersionCode $VersionCode `
    -ExpectedVersionName $VersionName
$releaseSignerOutput = @(
    & $apkSigner verify --verbose --print-certs $builtApk 2>&1
)
if ($LASTEXITCODE -ne 0) {
    throw "Pinned apksigner rejected the Package Updater release APK."
}
$releaseSignerSha256 = ConvertFrom-PackageUpdaterSignerCertificates `
    -Lines $releaseSignerOutput
if ($releaseSignerSha256 -cne $ExpectedUpdaterSignerSha256) {
    throw "Package Updater release signer differs from the protected expected certificate."
}
if (-not [string]::IsNullOrWhiteSpace($InspectE2eApkPath)) {
    $InspectE2eApkPath = (Resolve-Path -LiteralPath $InspectE2eApkPath).Path
    $e2eBadging = @(& $aapt2 dump badging $InspectE2eApkPath 2>&1)
    $e2eManifestTree = @(
        & $aapt2 dump xmltree --file AndroidManifest.xml $InspectE2eApkPath 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Pinned aapt2 could not inspect the E2E APK."
    }
    Assert-PackageUpdaterE2eArtifact `
        -Badging $e2eBadging `
        -ManifestTree $e2eManifestTree | Out-Null
}
$packagedNativeLibrary = Join-Path $projectRoot `
    "app\build\generated\rustJniLibs\arm64-v8a\librusty_quest_package_updater_android.so"
if (-not (Test-Path -LiteralPath $packagedNativeLibrary -PathType Leaf)) {
    throw "Gradle did not produce the Package Updater native verifier."
}
$nativeVerifierHash = (
    Get-FileHash -LiteralPath $packagedNativeLibrary -Algorithm SHA256
).Hash.ToLowerInvariant()

$archive = [System.IO.Compression.ZipFile]::OpenRead($builtApk)
try {
    $nativeEntry = $archive.GetEntry(
        "lib/arm64-v8a/librusty_quest_package_updater_android.so"
    )
    if ($null -eq $nativeEntry) {
        throw "Release APK does not contain the native verifier."
    }
    $nativeStream = $nativeEntry.Open()
    try {
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try {
            $packagedNativeHash = [Convert]::ToHexString(
                $sha256.ComputeHash($nativeStream)
            ).ToLowerInvariant()
        } finally {
            $sha256.Dispose()
        }
    } finally {
        $nativeStream.Dispose()
    }
} finally {
    $archive.Dispose()
}
if ($packagedNativeHash -ne $nativeVerifierHash) {
    throw "Release APK native verifier hash does not match the Gradle output."
}
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$outputApk = Join-Path $OutDir "rusty-quest-package-updater.apk"
Copy-Item -LiteralPath $builtApk -Destination $outputApk -Force

$apkHash = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).
    Hash.ToLowerInvariant()
$aapt2Hash = "sha256:" + (
    Get-FileHash -LiteralPath $aapt2 -Algorithm SHA256
).Hash.ToLowerInvariant()
$apkSignerHash = "sha256:" + (
    Get-FileHash -LiteralPath $apkSigner -Algorithm SHA256
).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    schema = "rusty.quest.package_updater_android.build_manifest.v1"
    source_revision = $sourceRevision
    package_name = $releaseIdentity.package_name
    version_code = $releaseIdentity.version_code
    version_name = $releaseIdentity.version_name
    manifest_url = $ManifestUrl
    trusted_key_id = $TrustedKeyId
    expected_https_origin = $ExpectedHttpsOrigin
    expected_package_name = $ExpectedPackageName
    expected_rollout_ring = $ExpectedRolloutRing
    expected_signer_sha256 = $ExpectedSignerSha256
    expected_updater_signer_sha256 = $ExpectedUpdaterSignerSha256
    updater_signer_sha256 = $releaseSignerSha256
    native_verifier_sha256 = "sha256:$nativeVerifierHash"
    apk_sha256 = "sha256:$apkHash"
    apk_size_bytes = (Get-Item -LiteralPath $outputApk).Length
    artifact_inspection = [ordered]@{
        tool = Get-PublicPackageUpdaterBuildTool `
            -BuildToolsVersion $buildToolsVersion `
            -Aapt2Path $aapt2 `
            -Aapt2Sha256 $aapt2Hash `
            -ApkSignerPath $apkSigner `
            -ApkSignerSha256 $apkSignerHash
        release_permissions = @(
            "android.permission.INTERNET",
            "android.permission.REQUEST_INSTALL_PACKAGES"
        )
        release_components = @(
            "PackageUpdaterActivity",
            "PackageInstallCallbackReceiver"
        )
        e2e_inspected = -not [string]::IsNullOrWhiteSpace($InspectE2eApkPath)
    }
}
$manifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (
        Join-Path $OutDir "rusty-quest-package-updater.build-manifest.json"
    ) -Encoding utf8NoBOM

$manifest
