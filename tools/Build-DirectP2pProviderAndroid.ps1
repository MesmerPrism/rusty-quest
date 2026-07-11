param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$AndroidNdkHome = $env:ANDROID_NDK_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = ""
)

$ErrorActionPreference = "Stop"

function Latest([string]$Parent, [string]$Pattern) {
    $item = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $item) { throw "No $Pattern under $Parent" }
    $item.FullName
}

function Checked([string]$Name, [string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) { throw "ANDROID_HOME is required" }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw "JAVA_HOME is required" }
if ([string]::IsNullOrWhiteSpace($AndroidNdkHome)) {
    $AndroidNdkHome = Join-Path $AndroidHome "ndk\27.2.12479018"
}
if (-not (Test-Path -LiteralPath $AndroidNdkHome)) { throw "Android NDK not found: $AndroidNdkHome" }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$appRoot = Join-Path $repoRoot "apps\direct-p2p-provider-android"
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $targetRoot "direct-p2p-provider-android" }
$resolved = [System.IO.Path]::GetFullPath($OutDir)
if (-not $resolved.StartsWith(([System.IO.Path]::GetFullPath($targetRoot)).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must remain under the repo target directory"
}
if (Test-Path -LiteralPath $OutDir) { Remove-Item -LiteralPath $OutDir -Recurse -Force }

$buildTools = Latest (Join-Path $AndroidHome "build-tools") "*"
$platformRoot = Latest (Join-Path $AndroidHome "platforms") "android-*"
$platformJar = Join-Path $platformRoot "android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
$androidClang = Join-Path $AndroidNdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
$androidAr = Join-Path $AndroidNdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-ar.exe"
foreach ($tool in @($platformJar,$aapt2,$d8,$zipalign,$apksigner,$javac,$jar,$keytool,$androidClang,$androidAr)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required tool not found: $tool" }
}

$classesDir = Join-Path $OutDir "classes"
$dexDir = Join-Path $OutDir "dex"
$nativeRoot = Join-Path $OutDir "native-package"
$nativeAbi = Join-Path $nativeRoot "lib\arm64-v8a"
New-Item -ItemType Directory -Force -Path $classesDir,$dexDir,$nativeAbi | Out-Null
$sources = Get-ChildItem -LiteralPath (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
$sourceList = Join-Path $OutDir "sources.rsp"
$sources | Set-Content -Encoding ASCII -LiteralPath $sourceList
Checked "javac" $javac @("-encoding","UTF-8","-source","1.8","-target","1.8","-bootclasspath",$platformJar,"-d",$classesDir,"@$sourceList")
$classesJar = Join-Path $OutDir "classes.jar"
Checked "jar" $jar @("cf",$classesJar,"-C",$classesDir,".")
Checked "d8" $d8 @("--lib",$platformJar,"--output",$dexDir,$classesJar)

$oldLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$oldCc = $env:CC_aarch64_linux_android
$oldAr = $env:AR_aarch64_linux_android
try {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $androidClang
    $env:CC_aarch64_linux_android = $androidClang
    $env:AR_aarch64_linux_android = $androidAr
    Push-Location $repoRoot
    try { Checked "Rust direct P2P provider" "cargo" @("build","--target","aarch64-linux-android","-p","rusty-quest-direct-p2p-provider-native") }
    finally { Pop-Location }
} finally {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $oldLinker
    $env:CC_aarch64_linux_android = $oldCc
    $env:AR_aarch64_linux_android = $oldAr
}
$builtSo = Join-Path $repoRoot "target\aarch64-linux-android\debug\librusty_quest_direct_p2p_provider.so"
if (-not (Test-Path -LiteralPath $builtSo)) { throw "Native provider library missing: $builtSo" }
Copy-Item -LiteralPath $builtSo -Destination (Join-Path $nativeAbi "librusty_quest_direct_p2p_provider.so")

$unsigned = Join-Path $OutDir "rusty-quest-direct-p2p-provider-unsigned.apk"
$unaligned = Join-Path $OutDir "rusty-quest-direct-p2p-provider-unaligned.apk"
$aligned = Join-Path $OutDir "rusty-quest-direct-p2p-provider-aligned.apk"
$signed = Join-Path $OutDir "rusty-quest-direct-p2p-provider.apk"
Checked "aapt2" $aapt2 @("link","-o",$unsigned,"--manifest",(Join-Path $appRoot "AndroidManifest.xml"),"-I",$platformJar,"--min-sdk-version","29","--target-sdk-version","34","--version-code","1","--version-name","0.1.0")
Copy-Item -LiteralPath $unsigned -Destination $unaligned
Checked "dex package" $jar @("uf",$unaligned,"-C",$dexDir,"classes.dex")
Checked "native package" $jar @("uf",$unaligned,"-C",$nativeRoot,"lib")
Checked "zipalign" $zipalign @("-f","4",$unaligned,$aligned)
if ([string]::IsNullOrWhiteSpace($Keystore)) { $Keystore = Join-Path $targetRoot "rusty-quest-direct-p2p-provider-debug.keystore" }
if (-not (Test-Path -LiteralPath $Keystore)) {
    Checked "keytool" $keytool @("-genkeypair","-keystore",$Keystore,"-storepass","android","-keypass","android","-alias","androiddebugkey","-keyalg","RSA","-keysize","2048","-validity","10000","-dname","CN=Rusty Quest Direct P2P Provider,O=Rusty Quest,C=US")
}
Checked "apksigner" $apksigner @("sign","--ks",$Keystore,"--ks-pass","pass:android","--key-pass","pass:android","--out",$signed,$aligned)
$buildManifest = [ordered]@{
    schema = "rusty.quest.direct_p2p_provider_android.build.v1"
    package_name = "io.github.mesmerprism.rustyquest.directp2p"
    activity = "io.github.mesmerprism.rustyquest.directp2p/.DirectP2pProviderActivity"
    apk_path = $signed
    apk_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $signed).Hash.ToLowerInvariant()
    native_socket_authority = "rust_direct_socket_provider"
    media_enabled = $false
}
$buildManifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $OutDir "build-manifest.json")
Write-Output $signed
