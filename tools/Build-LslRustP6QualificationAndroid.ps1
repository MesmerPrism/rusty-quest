[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RustyLslRoot,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = ""
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-Checked([string]$Name, [string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}
function Get-LatestDirectory([string]$Parent) {
    $item = Get-ChildItem -LiteralPath $Parent -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $item) { throw "No tool directory under $Parent" }
    $item.FullName
}
function Get-GitIdentity([string]$Name, [string]$Path) {
    if (-not (Test-Path -LiteralPath (Join-Path $Path ".git"))) { throw "$Name must be a Git checkout: $Path" }
    $status = (& git -C $Path status --porcelain=v1 --untracked-files=all | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $status) { throw "$Name checkout must be exactly clean (tracked and untracked): $Path" }
    $head = (& git -C $Path rev-parse HEAD).Trim()
    $tree = (& git -C $Path rev-parse 'HEAD^{tree}').Trim()
    if ($head -notmatch '^[0-9a-f]{40}$' -or $tree -notmatch '^[0-9a-f]{40}$') { throw "$Name Git identity is invalid" }
    [ordered]@{ commit = $head; tree = $tree; clean = $true }
}
function Get-Sha256([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

if ($PSVersionTable.PSVersion -lt [version]'7.6') { throw "PowerShell 7.6 or newer is required." }
if ([string]::IsNullOrWhiteSpace($AndroidHome)) { throw "ANDROID_HOME or -AndroidHome is required." }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw "JAVA_HOME or -JavaHome is required." }
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$lsl = (Resolve-Path -LiteralPath $RustyLslRoot).Path
$questIdentity = Get-GitIdentity "Rusty Quest" $repo
$lslIdentity = Get-GitIdentity "Rusty LSL" $lsl
$lslCrate = Join-Path $lsl "crates\rusty-lsl"
if (-not (Test-Path -LiteralPath (Join-Path $lslCrate "Cargo.toml"))) { throw "Rusty LSL crate is missing: $lslCrate" }

$target = Join-Path $repo "target"
$address = "$($questIdentity.commit.Substring(0,12))-$($lslIdentity.commit.Substring(0,12))"
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $target "lsl-rust-p6-qualification-android\$address" }
$fullOut = [IO.Path]::GetFullPath($OutDir)
$fullTarget = [IO.Path]::GetFullPath($target).TrimEnd('\')
if (-not $fullOut.StartsWith($fullTarget + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "OutDir must be under target." }
if (Test-Path -LiteralPath $fullOut) { throw "Content-addressed output already exists: $fullOut" }

$app = Join-Path $repo "apps\lsl-rust-p6-qualification-android"
$classes = Join-Path $fullOut "classes"
$dex = Join-Path $fullOut "dex"
$generatedNative = Join-Path $fullOut "generated-native"
New-Item -ItemType Directory -Force $classes, $dex, (Join-Path $generatedNative "src") | Out-Null
Copy-Item -LiteralPath (Join-Path $app "native\src\lib.rs") -Destination (Join-Path $generatedNative "src\lib.rs")
$lslToml = $lslCrate.Replace('\', '/')
@"
[package]
name = "rusty-lsl-p6-qualification"
version = "0.1.0"
edition = "2024"
[workspace]
[lib]
crate-type = ["cdylib"]
[dependencies]
rusty-lsl = { path = "$lslToml" }
"@ | Set-Content -LiteralPath (Join-Path $generatedNative "Cargo.toml") -Encoding utf8

$ndk = Get-LatestDirectory (Join-Path $AndroidHome "ndk")
$linker = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd"
if (-not (Test-Path -LiteralPath $linker)) { throw "Missing Android linker: $linker" }
$priorLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
try {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $linker
    $cargoTarget = Join-Path $fullOut "cargo-target"
    Invoke-Checked "cargo lock" "cargo" @("generate-lockfile", "--manifest-path", (Join-Path $generatedNative "Cargo.toml"))
    Invoke-Checked "cargo Android build" "cargo" @("build", "--manifest-path", (Join-Path $generatedNative "Cargo.toml"), "--target", "aarch64-linux-android", "--release", "--target-dir", $cargoTarget, "--locked")
} finally { $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $priorLinker }
$so = Join-Path $fullOut "cargo-target\aarch64-linux-android\release\rusty_lsl_p6_qualification.dll"
if (-not (Test-Path -LiteralPath $so)) { $so = Join-Path $fullOut "cargo-target\aarch64-linux-android\release\librusty_lsl_p6_qualification.so" }
if (-not (Test-Path -LiteralPath $so)) { throw "Missing built native library" }

$buildTools = Get-LatestDirectory (Join-Path $AndroidHome "build-tools")
$platform = Get-LatestDirectory (Join-Path $AndroidHome "platforms")
$androidJar = Join-Path $platform "android.jar"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$sources = @(Get-ChildItem (Join-Path $app "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName)
$responseFile = Join-Path $fullOut "sources.rsp"
$sources | Set-Content -LiteralPath $responseFile -Encoding ascii
Invoke-Checked "javac" $javac @("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-bootclasspath", $androidJar, "-d", $classes, "@$responseFile")
$classesJar = Join-Path $fullOut "classes.jar"
Invoke-Checked "jar" $jar @("cf", $classesJar, "-C", $classes, ".")
Invoke-Checked "d8" (Join-Path $buildTools "d8.bat") @("--lib", $androidJar, "--output", $dex, $classesJar)
$manifestPath = Join-Path $app "AndroidManifest.xml"
$unsigned = Join-Path $fullOut "unsigned.apk"
$unaligned = Join-Path $fullOut "unaligned.apk"
$aligned = Join-Path $fullOut "aligned.apk"
$apk = Join-Path $fullOut "rusty-lsl-p6-qualification.apk"
Invoke-Checked "aapt2" (Join-Path $buildTools "aapt2.exe") @("link", "-o", $unsigned, "--manifest", $manifestPath, "-I", $androidJar, "--min-sdk-version", "29", "--target-sdk-version", "34", "--version-code", "1", "--version-name", "0.1.0")
Copy-Item -LiteralPath $unsigned -Destination $unaligned
Invoke-Checked "dex package" $jar @("uf", $unaligned, "-C", $dex, "classes.dex")
$libDir = Join-Path $fullOut "apk-lib\lib\arm64-v8a"
New-Item -ItemType Directory -Force $libDir | Out-Null
Copy-Item -LiteralPath $so -Destination (Join-Path $libDir "librusty_lsl_p6_qualification.so")
Invoke-Checked "native package" $jar @("uf", $unaligned, "-C", (Join-Path $fullOut "apk-lib"), "lib")
Invoke-Checked "zipalign" (Join-Path $buildTools "zipalign.exe") @("-f", "4", $unaligned, $aligned)
if ([string]::IsNullOrWhiteSpace($Keystore)) { $Keystore = Join-Path $fullOut "run-owned-debug.keystore" }
$keystoreFull = [IO.Path]::GetFullPath($Keystore)
if (-not $keystoreFull.StartsWith($fullTarget + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Keystore must be under target." }
if (-not (Test-Path -LiteralPath $keystoreFull)) {
    Invoke-Checked "keytool" (Join-Path $JavaHome "bin\keytool.exe") @("-genkeypair", "-keystore", $keystoreFull, "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Rusty LSL P6 Qualification,O=Rusty Quest,C=US")
}
$signer = Join-Path $buildTools "apksigner.bat"
Invoke-Checked "sign" $signer @("sign", "--ks", $keystoreFull, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", $apk, $aligned)
Invoke-Checked "verify" $signer @("verify", "--verbose", "--print-certs", $apk)
$buildManifest = [ordered]@{
    schema = "rusty.quest.lsl_rust_p6_qualification_build.v1"
    package = "io.github.mesmerprism.rustyquest.lslrustp6qualification"
    activity = ".P6QualificationActivity"
    version_code = 1
    rust_target = "aarch64-linux-android"
    quest_source = $questIdentity
    rusty_lsl_source = $lslIdentity
    android_manifest_sha256 = Get-Sha256 $manifestPath
    native_source_sha256 = Get-Sha256 (Join-Path $app "native\src\lib.rs")
    cargo_lock_sha256 = Get-Sha256 (Join-Path $generatedNative "Cargo.lock")
    native_library_sha256 = Get-Sha256 $so
    apk_file = (Split-Path -Leaf $apk)
    apk_sha256 = Get-Sha256 $apk
}
$buildManifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $fullOut "build-manifest.json") -Encoding utf8
$buildManifestSha = Get-Sha256 (Join-Path $fullOut "build-manifest.json")
[ordered]@{ schema = "rusty.quest.lsl_rust_p6_qualification_capsule.v1"; build_manifest_file = "build-manifest.json"; build_manifest_sha256 = $buildManifestSha; apk_file = (Split-Path -Leaf $apk); apk_sha256 = $buildManifest.apk_sha256; package = $buildManifest.package; activity = $buildManifest.activity } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $fullOut "run-capsule.json") -Encoding utf8
$apk
