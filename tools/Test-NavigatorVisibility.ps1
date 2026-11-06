[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$AndroidJar,
    [string]$JdkBin = '',
    [string]$OutputRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$libraryRoot = Join-Path $repoRoot 'apps/navigator-visibility-android'
if (-not (Test-Path -LiteralPath $AndroidJar -PathType Leaf)) { throw 'AndroidJar must name an installed SDK android.jar.' }
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot ('local-artifacts/navigator-visibility/' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $OutputRoot) { throw 'OutputRoot must be a new task-owned directory.' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path

function Get-JavaTool([string]$Name) {
    if ($JdkBin) {
        $suffix = if ($IsWindows) { '.exe' } else { '' }
        $path = Join-Path $JdkBin ($Name + $suffix)
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing JDK tool: $Name" }
        return $path
    }
    return (Get-Command $Name -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
}
$javac = Get-JavaTool 'javac'
$java = Get-JavaTool 'java'
$jar = Get-JavaTool 'jar'
$separator = [IO.Path]::PathSeparator
function Invoke-Checked([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Tool failed with exit code $LASTEXITCODE : $File" }
}
function Compile-Java([string]$SourceRoot, [string]$Destination, [string]$Classpath = '') {
    New-Item -ItemType Directory -Path $Destination | Out-Null
    $sources = @(Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName)
    if ($sources.Count -eq 0) { throw "No Java sources: $SourceRoot" }
    $arguments = @('--release', '8', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', $Destination)
    if ($Classpath) { $arguments += @('-classpath', $Classpath) }
    Invoke-Checked $javac ($arguments + $sources)
}

$signatures = Join-Path $OutputRoot 'compile-only'
$production = Join-Path $OutputRoot 'production'
$androidDoubles = Join-Path $OutputRoot 'android-doubles'
$vendorDoubles = Join-Path $OutputRoot 'vendor-doubles'
$consumer = Join-Path $OutputRoot 'consumer'
Compile-Java (Join-Path $libraryRoot 'compile-only') $signatures
Compile-Java (Join-Path $libraryRoot 'src/main/java') $production ($AndroidJar + $separator + $signatures)
Compile-Java (Join-Path $libraryRoot 'host-tests/android') $androidDoubles
Compile-Java (Join-Path $libraryRoot 'host-tests/vendor') $vendorDoubles
$hostClasspath = @($production, $androidDoubles, $vendorDoubles, $consumer) -join $separator
Compile-Java (Join-Path $libraryRoot 'host-tests/consumer') $consumer $hostClasspath
Invoke-Checked $java @('-cp', $hostClasspath, 'VisibilityConformanceMain')
$unavailableClasspath = @($production, $androidDoubles, $consumer) -join $separator
Invoke-Checked $java @('-cp', $unavailableClasspath, 'VisibilityUnavailableMain')

$staging = Join-Path $OutputRoot 'aar'
New-Item -ItemType Directory -Path $staging | Out-Null
$classesJar = Join-Path $staging 'classes.jar'
Invoke-Checked $jar @('cf', $classesJar, '-C', $production, '.')
$entries = @(& $jar tf $classesJar)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect classes.jar.' }
if (@($entries | Where-Object { $_ -match '^(android/|horizonos/)|Conformance|UnavailableMain' }).Count -ne 0) {
    throw 'Runtime classes.jar contains compile-only or host-test payloads.'
}
Copy-Item -LiteralPath (Join-Path $libraryRoot 'src/main/AndroidManifest.xml') -Destination $staging
Copy-Item -LiteralPath (Join-Path $libraryRoot 'consumer-rules.pro') -Destination (Join-Path $staging 'proguard.txt')
[IO.File]::WriteAllText((Join-Path $staging 'R.txt'), '')
$aar = Join-Path $OutputRoot 'rusty-quest-navigator-visibility.aar'
Invoke-Checked $jar @('cf', $aar, '-C', $staging, '.')
[ordered]@{
    schema = 'rusty.quest.navigator_visibility.host_conformance.v1'
    result = 'passed'
    production_sources_compiled = $true
    simulated_vendor_conformance = $true
    missing_vendor_linkage = $true
    compile_only_classes_excluded = $true
    device_tested = $false
    apk_built = $false
    aar = $aar
    aar_sha256 = (Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash.ToLowerInvariant()
    android_jar_sha256 = (Get-FileHash -LiteralPath $AndroidJar -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json | Tee-Object -FilePath (Join-Path $OutputRoot 'result.json')
