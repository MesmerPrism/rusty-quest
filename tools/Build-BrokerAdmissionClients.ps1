param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$BrokerKeystore = ""
)

$ErrorActionPreference = "Stop"

function Get-LatestDirectory([string]$Parent, [string]$Pattern) {
    $directory = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $directory) { throw "No directory matching $Pattern under $Parent" }
    $directory.FullName
}

function Invoke-Checked([string]$Name, [string]$File, [string[]]$Arguments = @()) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) { throw "ANDROID_HOME or -AndroidHome is required." }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw "JAVA_HOME or -JavaHome is required." }

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$appRoot = Resolve-Path (Join-Path $repoRoot "apps\broker-admission-client-android")
$targetRoot = Join-Path $repoRoot "target"
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $targetRoot "broker-admission-clients" }
if ([string]::IsNullOrWhiteSpace($BrokerKeystore)) { $BrokerKeystore = Join-Path $targetRoot "rusty-manifold-broker-debug.keystore" }
if (-not (Test-Path -LiteralPath $BrokerKeystore -PathType Leaf)) {
    throw "Broker keystore must be created by Build-ManifoldBrokerAndroid.ps1 first: $BrokerKeystore"
}

$resolvedTargetRoot = [System.IO.Path]::GetFullPath($targetRoot).TrimEnd("\")
$resolvedOut = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $resolvedOut.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOut"
}
if (Test-Path -LiteralPath $OutDir) { Remove-Item -LiteralPath $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

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
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required tool not found: $tool" }
}

$classesDir = Join-Path $OutDir "classes"
$dexDir = Join-Path $OutDir "dex"
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir | Out-Null
$sourceFiles = Get-ChildItem -LiteralPath (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object FullName
$sourceList = Join-Path $OutDir "sources.rsp"
$sourceFiles | Set-Content -Encoding ASCII -LiteralPath $sourceList
Invoke-Checked "admission client javac" $javac @("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-bootclasspath", $platformJar, "-d", $classesDir, "@$sourceList")
$classesJar = Join-Path $OutDir "classes.jar"
Invoke-Checked "admission client jar" $jar @("cf", $classesJar, "-C", $classesDir, ".")
Invoke-Checked "admission client d8" $d8 @("--lib", $platformJar, "--output", $dexDir, $classesJar)

$untrustedKeystore = Join-Path $targetRoot "rusty-manifold-admission-untrusted.keystore"
if (-not (Test-Path -LiteralPath $untrustedKeystore)) {
    Invoke-Checked "untrusted client keytool" $keytool @(
        "-genkeypair", "-v",
        "-keystore", $untrustedKeystore,
        "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Untrusted Admission Client,O=Rusty Quest Test,C=US"
    )
}

function Build-Variant([string]$Name, [string]$ManifestPath, [string]$Keystore) {
    $unsigned = Join-Path $OutDir "$Name-unsigned.apk"
    $unaligned = Join-Path $OutDir "$Name-unaligned.apk"
    $aligned = Join-Path $OutDir "$Name-aligned.apk"
    $signed = Join-Path $OutDir "$Name.apk"
    Invoke-Checked "$Name aapt2" $aapt2 @(
        "link", "-o", $unsigned,
        "--manifest", $ManifestPath,
        "-I", $platformJar,
        "--min-sdk-version", "29",
        "--target-sdk-version", "34",
        "--version-code", "1",
        "--version-name", "0.1.0"
    )
    Copy-Item -LiteralPath $unsigned -Destination $unaligned
    Invoke-Checked "$Name dex" $jar @("uf", $unaligned, "-C", $dexDir, "classes.dex")
    Invoke-Checked "$Name zipalign" $zipalign @("-f", "4", $unaligned, $aligned)
    Invoke-Checked "$Name sign" $apksigner @(
        "sign", "--ks", $Keystore,
        "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--out", $signed, $aligned
    )
    $signed
}

$authorizedApk = Build-Variant `
    "rusty-manifold-admission-authorized" `
    (Join-Path $appRoot "authorized.AndroidManifest.xml") `
    $BrokerKeystore
$unauthorizedApk = Build-Variant `
    "rusty-manifold-admission-unauthorized" `
    (Join-Path $appRoot "unauthorized.AndroidManifest.xml") `
    $untrustedKeystore

$manifest = [ordered]@{
    '$schema' = "rusty.quest.broker.admission_client_builds.v1"
    authorized = [ordered]@{
        package_name = "io.github.mesmerprism.rustymanifold.admission.client"
        signing_relation = "same_as_broker"
        apk_path = $authorizedApk
    }
    unauthorized = [ordered]@{
        package_name = "io.github.mesmerprism.rustymanifold.admission.untrusted"
        signing_relation = "different_from_broker"
        apk_path = $unauthorizedApk
    }
    admission_permission = "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION"
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $manifestPath
Write-Output $manifestPath
