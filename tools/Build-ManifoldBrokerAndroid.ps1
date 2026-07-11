param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = ""
)

$ErrorActionPreference = "Stop"

function Get-LatestDirectory {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Parent,
        [Parameter(Mandatory=$true)]
        [string]$Pattern
    )

    $directory = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $directory) {
        throw "No directory matching $Pattern under $Parent"
    }
    return $directory.FullName
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$File,
        [string[]]$Arguments = @()
    )

    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-FileSha256Hex {
    param([Parameter(Mandatory=$true)][string]$Path)

    $cmd = Get-Command Get-FileHash -ErrorAction SilentlyContinue
    if ($null -ne $cmd) {
        return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $hash = $sha.ComputeHash($stream)
            return -join ($hash | ForEach-Object { $_.ToString("x2") })
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required."
}

$appRoot = Resolve-Path (Join-Path $PSScriptRoot "..\apps\manifold-broker-android")
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "manifold-broker-android"
}

$buildTools = Get-LatestDirectory -Parent (Join-Path $AndroidHome "build-tools") -Pattern "*"
$platformRoot = Get-LatestDirectory -Parent (Join-Path $AndroidHome "platforms") -Pattern "android-*"
$platformJar = Join-Path $platformRoot "android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
$ndkRoot = Get-LatestDirectory -Parent (Join-Path $AndroidHome "ndk") -Pattern "*"
$ndkBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$androidClang = Join-Path $ndkBin "aarch64-linux-android29-clang.cmd"
$androidAr = Join-Path $ndkBin "llvm-ar.exe"

foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool, $androidClang, $androidAr)) {
    if (-not (Test-Path $tool)) {
        throw "Required tool not found: $tool"
    }
}

$resolvedOutParent = Split-Path -Parent $OutDir
New-Item -ItemType Directory -Force -Path $targetRoot, $resolvedOutParent | Out-Null
$resolvedTargetRoot = (Resolve-Path $targetRoot).Path.TrimEnd("\")
$resolvedOutFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $resolvedOutFull.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOutFull"
}
if (Test-Path $OutDir) {
    $resolvedOutDir = (Resolve-Path $OutDir).Path
    Remove-Item -LiteralPath $resolvedOutDir -Recurse -Force
}

$classesDir = Join-Path $OutDir "classes"
$dexDir = Join-Path $OutDir "dex"
$classesJar = Join-Path $OutDir "classes.jar"
$apkUnsigned = Join-Path $OutDir "rusty-manifold-broker-unsigned.apk"
$apkUnaligned = Join-Path $OutDir "rusty-manifold-broker-unaligned.apk"
$apkAligned = Join-Path $OutDir "rusty-manifold-broker-aligned.apk"
$apkSigned = Join-Path $OutDir "rusty-manifold-broker.apk"
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-manifold-broker-debug.keystore"
}

New-Item -ItemType Directory -Force -Path $classesDir, $dexDir | Out-Null

if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Keystore) | Out-Null
    Invoke-Checked "keytool" $keytool @(
        "-genkeypair",
        "-v",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Rusty Manifold Broker,O=Rusty Quest,C=US"
    )
}

$certificatePath = Join-Path $OutDir "broker-signing-certificate.der"
Invoke-Checked "keytool certificate export" $keytool @(
    "-exportcert",
    "-keystore", $Keystore,
    "-storepass", "android",
    "-alias", "androiddebugkey",
    "-file", $certificatePath
)
$certificateSha256 = Get-FileSha256Hex -Path $certificatePath
$admissionConfig = [ordered]@{
    '$schema' = "rusty.quest.broker.admission_config.v1"
    snapshot = [ordered]@{
        '$schema' = "rusty.manifold.admission.snapshot.v1"
        authority_id = "authority.admission.quest"
        authority_revision = 1
        grants = @(
            [ordered]@{
                grant_id = "grant.quest.authorized"
                identity = [ordered]@{
                    client_id = "client.quest.authorized"
                    platform_subject = "io.github.mesmerprism.rustymanifold.admission.client"
                    signing_fingerprint = "sha256:$certificateSha256"
                }
                capabilities = @("capability.command.session.list")
                expires_at_ms = 4102444800000
                revoked = $false
            },
            [ordered]@{
                grant_id = "grant.quest.native-renderer"
                identity = [ordered]@{
                    client_id = "client.quest.native-renderer"
                    platform_subject = "io.github.mesmerprism.rustyquest.native_renderer"
                    signing_fingerprint = "sha256:$certificateSha256"
                }
                capabilities = @("capability.command.session.list","capability.media.session.observe","capability.peer.session.observe","capability.sink.native-openxr")
                expires_at_ms = 4102444800000
                revoked = $false
            },
            [ordered]@{
                grant_id = "grant.quest.spatial-camera-panel"
                identity = [ordered]@{
                    client_id = "client.quest.spatial-camera-panel"
                    platform_subject = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
                    signing_fingerprint = "sha256:$certificateSha256"
                }
                capabilities = @("capability.command.session.list","capability.media.session.observe","capability.peer.session.observe","capability.sink.spatial-sdk")
                expires_at_ms = 4102444800000
                revoked = $false
            }
        )
        active_tokens = @()
        revoked_token_ids = @()
        consumed_request_ids = @()
        consumed_use_request_ids = @()
        audit_events = @()
        max_token_ttl_ms = 60000
    }
}
$admissionConfigJson = $admissionConfig | ConvertTo-Json -Depth 12 -Compress
$admissionConfigJava = $admissionConfigJson.Replace('\', '\\').Replace('"', '\"')
$generatedPackageDir = Join-Path $OutDir "generated\io\github\mesmerprism\rustymanifold\broker"
New-Item -ItemType Directory -Force -Path $generatedPackageDir | Out-Null
$generatedAdmissionConfigPath = Join-Path $generatedPackageDir "GeneratedAdmissionConfig.java"
$generatedAdmissionConfigSource = @"
package io.github.mesmerprism.rustymanifold.broker;

final class GeneratedAdmissionConfig {
    static final String JSON = "$admissionConfigJava";
    private GeneratedAdmissionConfig() {}
}
"@
[System.IO.File]::WriteAllText(
    $generatedAdmissionConfigPath,
    $generatedAdmissionConfigSource,
    (New-Object System.Text.UTF8Encoding($false)))

$sourceFiles = Get-ChildItem -Path (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
$sourceFiles = @($sourceFiles) + @($generatedAdmissionConfigPath)
if ($sourceFiles.Count -eq 0) {
    throw "No Java sources found under $appRoot"
}
$sourceList = Join-Path $OutDir "sources.rsp"
$sourceFiles | Set-Content -Encoding ASCII -Path $sourceList

Invoke-Checked "javac" $javac @("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-bootclasspath", $platformJar, "-d", $classesDir, "@$sourceList")
Invoke-Checked "jar class pack" $jar @("cf", $classesJar, "-C", $classesDir, ".")
Invoke-Checked "d8" $d8 @("--lib", $platformJar, "--output", $dexDir, $classesJar)

$previousLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$previousCc = $env:CC_aarch64_linux_android
$previousAr = $env:AR_aarch64_linux_android
try {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $androidClang
    $env:CC_aarch64_linux_android = $androidClang
    $env:AR_aarch64_linux_android = $androidAr
    Push-Location $repoRoot
    try {
        Invoke-Checked "standalone broker admission native" "cargo" @(
            "build",
            "--target", "aarch64-linux-android",
            "-p", "rusty-quest-manifold-broker-authority-native"
        )
    } finally {
        Pop-Location
    }
} finally {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $previousLinker
    $env:CC_aarch64_linux_android = $previousCc
    $env:AR_aarch64_linux_android = $previousAr
}
$nativeSoSource = Join-Path $repoRoot "target\aarch64-linux-android\debug\librusty_quest_manifold_broker_authority.so"
if (-not (Test-Path -LiteralPath $nativeSoSource -PathType Leaf)) {
    throw "Standalone broker authority native library not found: $nativeSoSource"
}
$nativeLibRoot = Join-Path $OutDir "native-package"
$nativeAbiDir = Join-Path $nativeLibRoot "lib\arm64-v8a"
New-Item -ItemType Directory -Force -Path $nativeAbiDir | Out-Null
$nativeSoPackaged = Join-Path $nativeAbiDir "librusty_quest_manifold_broker_authority.so"
Copy-Item -LiteralPath $nativeSoSource -Destination $nativeSoPackaged
Invoke-Checked "aapt2 link" $aapt2 @(
    "link",
    "-o", $apkUnsigned,
    "--manifest", (Join-Path $appRoot "AndroidManifest.xml"),
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "34",
    "--version-code", "1",
    "--version-name", "0.1.0"
)

Copy-Item $apkUnsigned $apkUnaligned
Invoke-Checked "jar dex update" $jar @("uf", $apkUnaligned, "-C", $dexDir, "classes.dex")
Invoke-Checked "jar native library update" $jar @("uf", $apkUnaligned, "-C", $nativeLibRoot, "lib")
Invoke-Checked "zipalign" $zipalign @("-f", "4", $apkUnaligned, $apkAligned)

Invoke-Checked "apksigner" $apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $apkSigned,
    $apkAligned
)

$sha256 = Get-FileSha256Hex -Path $apkSigned
$manifest = [ordered]@{
    '$schema' = "rusty.quest.manifold_broker_android.build_manifest.v1"
    package_name = "io.github.mesmerprism.rustymanifold.broker"
    activity = "io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity"
    authority = "rusty.manifold"
    endpoint_path = "/manifold/v1/events"
    broker_port = 8765
    admission_permission = "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION"
    admission_service = "io.github.mesmerprism.rustymanifold.broker/.ManifoldAdmissionService"
    admission_decision_owner = "rusty.manifold.admission"
    admission_client_signing_certificate_sha256 = $certificateSha256
    admission_native_library_sha256 = Get-FileSha256Hex -Path $nativeSoPackaged
    apk_path = $apkSigned
    apk_sha256 = $sha256
    live_stream_events_synthesized = $false
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

Write-Output $apkSigned
