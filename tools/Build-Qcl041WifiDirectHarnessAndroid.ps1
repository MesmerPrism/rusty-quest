param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$AndroidNdkHome = $env:ANDROID_NDK_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = "",
    [string]$LslAndroidNativeLibDir = "",
    [switch]$SkipQcl081LslNative
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

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required."
}
if ([string]::IsNullOrWhiteSpace($AndroidNdkHome)) {
    $defaultNdk = Join-Path $AndroidHome "ndk\27.2.12479018"
    if (Test-Path $defaultNdk) {
        $AndroidNdkHome = $defaultNdk
    }
}
if ([string]::IsNullOrWhiteSpace($LslAndroidNativeLibDir)) {
    $LslAndroidNativeLibDir = "S:\Work\repos\reference\legacy-c-source-20260523\AndroidPhoneQuestCompanion\app\src\main\jniLibs\arm64-v8a"
}

$appRoot = Resolve-Path (Join-Path $PSScriptRoot "..\apps\qcl041-wifi-direct-harness-android")
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "qcl041-wifi-direct-harness-android"
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
$clang = ""
if (-not $SkipQcl081LslNative) {
    if ([string]::IsNullOrWhiteSpace($AndroidNdkHome) -or -not (Test-Path $AndroidNdkHome)) {
        throw "ANDROID_NDK_HOME, -AndroidNdkHome, or $defaultNdk is required for QCL-081 native bridge packaging."
    }
}
if (-not $SkipQcl081LslNative) {
    $clang = Join-Path $AndroidNdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang++.cmd"
}

foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool)) {
    if (-not (Test-Path $tool)) {
        throw "Required tool not found: $tool"
    }
}
if (-not $SkipQcl081LslNative) {
    foreach ($tool in @($clang)) {
        if (-not (Test-Path $tool)) {
            throw "Required native build tool not found: $tool"
        }
    }
    foreach ($library in @("liblsl.so", "libc++_shared.so")) {
        $libraryPath = Join-Path $LslAndroidNativeLibDir $library
        if (-not (Test-Path $libraryPath)) {
            throw "QCL-081 Android LSL native library not found: $libraryPath"
        }
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
$nativeOutDir = Join-Path $OutDir "native\arm64-v8a"
$nativePackRoot = Join-Path $OutDir "native-apk"
$nativePackLibDir = Join-Path $nativePackRoot "lib\arm64-v8a"
$classesJar = Join-Path $OutDir "classes.jar"
$apkUnsigned = Join-Path $OutDir "rusty-quest-qcl041-wifi-direct-harness-unsigned.apk"
$apkUnaligned = Join-Path $OutDir "rusty-quest-qcl041-wifi-direct-harness-unaligned.apk"
$apkAligned = Join-Path $OutDir "rusty-quest-qcl041-wifi-direct-harness-aligned.apk"
$apkSigned = Join-Path $OutDir "rusty-quest-qcl041-wifi-direct-harness.apk"
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-quest-qcl041-wifi-direct-harness-debug.keystore"
}

New-Item -ItemType Directory -Force -Path $classesDir, $dexDir, $nativeOutDir, $nativePackLibDir | Out-Null

$sourceFiles = Get-ChildItem -Path (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
if ($sourceFiles.Count -eq 0) {
    throw "No Java sources found under $appRoot"
}
$sourceList = Join-Path $OutDir "sources.rsp"
$sourceFiles | Set-Content -Encoding ASCII -Path $sourceList

Invoke-Checked "javac" $javac @("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-bootclasspath", $platformJar, "-d", $classesDir, "@$sourceList")
Invoke-Checked "jar class pack" $jar @("cf", $classesJar, "-C", $classesDir, ".")
Invoke-Checked "d8" $d8 @("--lib", $platformJar, "--output", $dexDir, $classesJar)
if (-not $SkipQcl081LslNative) {
    $nativeSource = Join-Path $appRoot "src\main\cpp\qcl081_lsl_outlet_bridge.cpp"
    $bridgeSo = Join-Path $nativeOutDir "libqcl081_lsl_outlet_bridge.so"
    Invoke-Checked "QCL-081 LSL native bridge" $clang @(
        "-shared",
        "-fPIC",
        "-std=c++17",
        "-I", (Join-Path $appRoot "src\main\cpp"),
        $nativeSource,
        "-L", $LslAndroidNativeLibDir,
        "-llsl",
        "-llog",
        "-Wl,-z,max-page-size=16384",
        "-Wl,-z,common-page-size=16384",
        "-o", $bridgeSo
    )
    Copy-Item -LiteralPath (Join-Path $LslAndroidNativeLibDir "liblsl.so") -Destination (Join-Path $nativePackLibDir "liblsl.so") -Force
    Copy-Item -LiteralPath (Join-Path $LslAndroidNativeLibDir "libc++_shared.so") -Destination (Join-Path $nativePackLibDir "libc++_shared.so") -Force
    Copy-Item -LiteralPath $bridgeSo -Destination (Join-Path $nativePackLibDir "libqcl081_lsl_outlet_bridge.so") -Force
}
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
if (-not $SkipQcl081LslNative) {
    Invoke-Checked "jar native library update" $jar @("uf", $apkUnaligned, "-C", $nativePackRoot, "lib")
}
Invoke-Checked "zipalign" $zipalign @("-f", "4", $apkUnaligned, $apkAligned)

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
        "-dname", "CN=Rusty Quest QCL041 Wi-Fi Direct Harness,O=Rusty Quest,C=US"
    )
}

Invoke-Checked "apksigner" $apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $apkSigned,
    $apkAligned
)

$sha256 = (Get-FileHash -Algorithm SHA256 -Path $apkSigned).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    '$schema' = "rusty.quest.qcl041_wifi_direct_harness_android.build_manifest.v1"
    package_name = "io.github.mesmerprism.rustyquest.qcl041"
    activity = "io.github.mesmerprism.rustyquest.qcl041/.Qcl041WifiDirectHarnessActivity"
    probe_id = "QCL-041"
    peer_class = "windows"
    lifecycle_schema = "rusty.quest.connectivity_wifi_direct_lifecycle.v1"
    target_sdk = 34
    min_sdk = 29
    apk_path = $apkSigned
    apk_sha256 = $sha256
    qcl081_lsl_native_packaged = (-not $SkipQcl081LslNative)
    qcl081_lsl_native_lib_source = if ($SkipQcl081LslNative) { "" } else { $LslAndroidNativeLibDir }
    live_evidence_synthesized = $false
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

Write-Output $apkSigned
