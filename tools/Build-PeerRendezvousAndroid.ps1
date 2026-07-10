param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = ""
)

$ErrorActionPreference = "Stop"

function Get-LatestDirectory {
    param([string]$Parent, [string]$Pattern)
    $directory = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $directory) {
        throw "No directory matching $Pattern under $Parent"
    }
    return $directory.FullName
}

function Invoke-Checked {
    param([string]$Name, [string]$File, [string[]]$Arguments = @())
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

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$appRoot = Join-Path $repoRoot "apps\peer-rendezvous-android"
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "peer-rendezvous-android"
}
$targetFull = [System.IO.Path]::GetFullPath($targetRoot).TrimEnd("\")
$outFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $outFull.StartsWith($targetFull + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $outFull"
}

$buildTools = Get-LatestDirectory (Join-Path $AndroidHome "build-tools") "*"
$platformRoot = Get-LatestDirectory (Join-Path $AndroidHome "platforms") "android-*"
$platformJar = Join-Path $platformRoot "android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required tool not found: $tool"
    }
}

if (Test-Path -LiteralPath $outFull) {
    $resolvedOut = (Resolve-Path -LiteralPath $outFull).Path
    if (-not $resolvedOut.StartsWith($targetFull + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove output outside target: $resolvedOut"
    }
    Remove-Item -LiteralPath $resolvedOut -Recurse -Force
}
$classesDir = Join-Path $outFull "classes"
$dexDir = Join-Path $outFull "dex"
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir | Out-Null

$sources = Get-ChildItem -Path (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) {
    throw "No Java sources found under $appRoot"
}
$sourceList = Join-Path $outFull "sources.rsp"
$sources | Set-Content -Encoding ASCII -Path $sourceList
$classesJar = Join-Path $outFull "classes.jar"
$unsignedApk = Join-Path $outFull "rusty-quest-peer-rendezvous-unsigned.apk"
$unalignedApk = Join-Path $outFull "rusty-quest-peer-rendezvous-unaligned.apk"
$alignedApk = Join-Path $outFull "rusty-quest-peer-rendezvous-aligned.apk"
$signedApk = Join-Path $outFull "rusty-quest-peer-rendezvous.apk"
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-quest-peer-rendezvous-debug.keystore"
}

Invoke-Checked "javac" $javac @(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8",
    "-bootclasspath", $platformJar, "-d", $classesDir, "@$sourceList")
Invoke-Checked "jar" $jar @("cf", $classesJar, "-C", $classesDir, ".")
Invoke-Checked "d8" $d8 @("--lib", $platformJar, "--output", $dexDir, $classesJar)
Invoke-Checked "aapt2" $aapt2 @(
    "link", "-o", $unsignedApk,
    "--debug-mode",
    "--manifest", (Join-Path $appRoot "AndroidManifest.xml"),
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "34",
    "--version-code", "1",
    "--version-name", "0.1.0")
Copy-Item -LiteralPath $unsignedApk -Destination $unalignedApk
Invoke-Checked "jar dex update" $jar @("uf", $unalignedApk, "-C", $dexDir, "classes.dex")
Invoke-Checked "zipalign" $zipalign @("-f", "4", $unalignedApk, $alignedApk)

if (-not (Test-Path -LiteralPath $Keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Keystore) | Out-Null
    Invoke-Checked "keytool" $keytool @(
        "-genkeypair", "-v",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Rusty Quest Peer Rendezvous,O=Rusty Quest,C=US")
}
Invoke-Checked "apksigner" $apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $signedApk,
    $alignedApk)

$manifest = [ordered]@{
    '$schema' = "rusty.quest.peer_rendezvous_android.build_manifest.v1"
    package_name = "io.github.mesmerprism.rustyquest.peer_rendezvous"
    activity = "io.github.mesmerprism.rustyquest.peer_rendezvous/.PeerRendezvousActivity"
    service = "io.github.mesmerprism.rustyquest.peer_rendezvous/.PeerRendezvousService"
    explicit_opt_in_action = "io.github.mesmerprism.rustyquest.peer_rendezvous.START"
    debuggable_evidence_build = $true
    wire_message_max_bytes = 220
    media_payload_allowed = $false
    wifi_direct_mutation_allowed = $false
    manifold_command_execution_allowed = $false
    apk_path = $signedApk
    apk_sha256 = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path (Join-Path $outFull "build-manifest.json")
Write-Output $signedApk
