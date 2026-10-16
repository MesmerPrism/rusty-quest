param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = ""
)

$ErrorActionPreference = "Stop"

function Get-LatestDirectory {
    param([string]$Parent, [string]$Pattern)
    $result = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $result) {
        throw "No directory matching $Pattern under $Parent"
    }
    return $result.FullName
}

function Invoke-Checked {
    param([string]$Name, [string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$appRoot = Join-Path $repoRoot "apps\display-stream-android"
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "display-stream-android"
}
$fullOutDir = [System.IO.Path]::GetFullPath($OutDir)
$fullTargetRoot = [System.IO.Path]::GetFullPath($targetRoot) + [System.IO.Path]::DirectorySeparatorChar
if (-not $fullOutDir.StartsWith($fullTargetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must remain under the repository target directory: $fullOutDir"
}
if (Test-Path -LiteralPath $fullOutDir) {
    Remove-Item -LiteralPath $fullOutDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $fullOutDir | Out-Null

if ([string]::IsNullOrWhiteSpace($AndroidHome) -or [string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "ANDROID_HOME and JAVA_HOME are required"
}
$buildTools = Get-LatestDirectory (Join-Path $AndroidHome "build-tools") "*"
$platform = Get-LatestDirectory (Join-Path $AndroidHome "platforms") "android-*"
$platformJar = Join-Path $platform "android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android build tool not found: $tool"
    }
}

$classes = Join-Path $fullOutDir "classes"
$dex = Join-Path $fullOutDir "dex"
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) {
    throw "No display-stream Java sources were found"
}
$sourceList = Join-Path $fullOutDir "sources.rsp"
$sources | Set-Content -LiteralPath $sourceList -Encoding ASCII
$classesJar = Join-Path $fullOutDir "display-stream-classes.jar"
Invoke-Checked "javac" $javac @(
    "-encoding", "UTF-8", "--release", "8",
    "-classpath", $platformJar, "-d", $classes, "@$sourceList")
Invoke-Checked "class jar" $jar @("cf", $classesJar, "-C", $classes, ".")
Invoke-Checked "d8" $d8 @("--lib", $platformJar, "--output", $dex, $classesJar)

$shellJar = Join-Path $fullOutDir "rusty-quest-display-mirror-shell.jar"
Invoke-Checked "shell dex jar" $jar @("cf", $shellJar, "-C", $dex, "classes.dex")

$unsigned = Join-Path $fullOutDir "display-stream-unsigned.apk"
$unaligned = Join-Path $fullOutDir "display-stream-unaligned.apk"
$aligned = Join-Path $fullOutDir "display-stream-aligned.apk"
$signed = Join-Path $fullOutDir "rusty-quest-display-stream.apk"
Invoke-Checked "aapt2 link" $aapt2 @(
    "link", "-o", $unsigned,
    "--manifest", (Join-Path $appRoot "AndroidManifest.xml"),
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "34",
    "--version-code", "1",
    "--version-name", "0.1.0")
Copy-Item -LiteralPath $unsigned -Destination $unaligned
Invoke-Checked "APK dex update" $jar @("uf", $unaligned, "-C", $dex, "classes.dex")
Invoke-Checked "zipalign" $zipalign @("-f", "4", $unaligned, $aligned)

if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "display-stream-debug.keystore"
}
if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
    Invoke-Checked "debug keystore" $keytool @(
        "-genkeypair", "-keystore", $Keystore,
        "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Android Debug,O=Rusty Morphospace,C=DE")
}
Invoke-Checked "APK sign" $apksigner @(
    "sign", "--ks", $Keystore,
    "--ks-pass", "pass:android", "--key-pass", "pass:android",
    "--out", $signed, $aligned)
Invoke-Checked "APK verify" $apksigner @("verify", "--verbose", $signed)

$receipt = [ordered]@{
    schema = "rusty.quest.display_stream.build_receipt.v1"
    package = "io.github.mesmerprism.rustyquest.displaystream"
    apk = $signed
    apk_sha256 = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash.ToLowerInvariant()
    shell_jar = $shellJar
    shell_jar_sha256 = (Get-FileHash -LiteralPath $shellJar -Algorithm SHA256).Hash.ToLowerInvariant()
    source_modes = @("media_projection", "shell_display_mirror")
    validation_surfaces = @("decoded_frame_freshness_stimulus")
    wire_protocol = "RMANVID1"
}
$receiptPath = Join-Path $fullOutDir "build-receipt.json"
[System.IO.File]::WriteAllText(
    $receiptPath,
    ($receipt | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
Write-Output $receiptPath
