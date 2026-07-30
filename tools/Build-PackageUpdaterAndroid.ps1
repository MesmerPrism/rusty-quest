[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$GradlePath,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)]
    [string]$ManifestUrl,
    [Parameter(Mandatory = $true)]
    [string]$TrustedKeyId,
    [Parameter(Mandatory = $true)]
    [string]$TrustedPublicKeyBase64Url,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedHttpsOrigin,
    [string]$ExpectedPackageName = "io.github.mesmerprism.rustykiosk",
    [string]$ExpectedRolloutRing = "alpha",
    [Parameter(Mandatory = $true)]
    [string]$ExpectedSignerSha256,
    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,
    [Parameter(Mandatory = $true)]
    [securestring]$StorePassword,
    [Parameter(Mandatory = $true)]
    [string]$KeyAlias,
    [Parameter(Mandatory = $true)]
    [securestring]$KeyPassword,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
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

foreach ($requiredPath in @($GradlePath, $AndroidHome, $JavaHome, $KeystorePath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required Package Updater build path is missing: $requiredPath"
    }
}
if (-not $ManifestUrl.StartsWith("$ExpectedHttpsOrigin/")) {
    throw "Manifest URL must use the exact expected HTTPS origin."
}
if ($TrustedKeyId -notmatch "^[A-Za-z0-9._-]{1,96}$" -or
    $TrustedPublicKeyBase64Url -notmatch "^[A-Za-z0-9_-]{43}$" -or
    $ExpectedPackageName -notmatch "^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$" -or
    $ExpectedRolloutRing -notmatch "^[A-Za-z0-9._-]{1,32}$" -or
    $ExpectedSignerSha256 -notmatch "^sha256:[0-9a-f]{64}$") {
    throw "Package Updater build policy contains a noncanonical value."
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

$ndkRoot = Get-ChildItem -LiteralPath (Join-Path $AndroidHome "ndk") `
        -Directory -ErrorAction Stop |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
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
$manifest = [ordered]@{
    schema = "rusty.quest.package_updater_android.build_manifest.v1"
    source_revision = $sourceRevision
    package_name = "io.github.mesmerprism.rustyquest.packageupdater.alpha"
    version_code = 1
    version_name = "0.1.0"
    manifest_url = $ManifestUrl
    trusted_key_id = $TrustedKeyId
    expected_https_origin = $ExpectedHttpsOrigin
    expected_package_name = $ExpectedPackageName
    expected_rollout_ring = $ExpectedRolloutRing
    expected_signer_sha256 = $ExpectedSignerSha256
    native_verifier_sha256 = "sha256:$nativeVerifierHash"
    apk_sha256 = "sha256:$apkHash"
    apk_size_bytes = (Get-Item -LiteralPath $outputApk).Length
}
$manifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (
        Join-Path $OutDir "rusty-quest-package-updater.build-manifest.json"
    ) -Encoding utf8NoBOM

$manifest
