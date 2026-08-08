param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$sourceRoot = Join-Path $RepoRoot "crates\rusty-quest-broker-transport\android"
$testRoot = Join-Path $RepoRoot "crates\rusty-quest-broker-transport\tests\java"
$source = Join-Path $sourceRoot "io\github\mesmerprism\rustyquest\broker_transport\Rfc6455Codec.java"
$sessionSource = Join-Path $sourceRoot "io\github\mesmerprism\rustyquest\broker_transport\BoundedWebSocketSession.java"
$deadlineSource = Join-Path $sourceRoot "io\github\mesmerprism\rustyquest\broker_transport\DeadlineInputStream.java"
$test = Join-Path $testRoot "io\github\mesmerprism\rustyquest\broker_transport\Rfc6455CodecTest.java"
$sessionTest = Join-Path $testRoot "io\github\mesmerprism\rustyquest\broker_transport\BoundedWebSocketSessionTest.java"
$deadlineTest = Join-Path $testRoot "io\github\mesmerprism\rustyquest\broker_transport\DeadlineInputStreamTest.java"
$manifoldBuild = Join-Path $RepoRoot "tools\Build-ManifoldBrokerAndroid.ps1"
$nativeBuild = Join-Path $RepoRoot "tools\Build-NativeRendererAndroid.ps1"

foreach ($path in @($source, $sessionSource, $deadlineSource, $test, $sessionTest, $deadlineTest, $manifoldBuild, $nativeBuild)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing shared broker transport path: $path"
    }
}

$sourceText = Get-Content -LiteralPath $source -Raw
if ($sourceText -match '(?m)^import\s+(android\.|org\.json)') {
    throw "Shared broker transport core must remain pure Java and Android-compatible."
}
foreach ($buildPath in @($manifoldBuild, $nativeBuild)) {
    $buildText = Get-Content -LiteralPath $buildPath -Raw
    if ($buildText -notmatch 'crates\\rusty-quest-broker-transport\\android' -or
        $buildText -notmatch 'sharedBrokerTransportJava') {
        throw "Android build does not compile the shared broker transport core: $buildPath"
    }
}

$javacCommand = Get-Command javac -ErrorAction Stop
$javac = $javacCommand.Source
$java = Join-Path (Split-Path -Parent $javac) "java.exe"
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    $java = (Get-Command java -ErrorAction Stop).Source
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
$buildRoot = [IO.Path]::GetFullPath((Join-Path $tempBase (
    "rusty-quest-broker-transport-test-" + [guid]::NewGuid().ToString("N"))))
if (-not $buildRoot.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing an out-of-temp broker transport test path: $buildRoot"
}

try {
    $classes = Join-Path $buildRoot "classes"
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sourceList = Join-Path $buildRoot "sources.rsp"
    @($source, $sessionSource, $deadlineSource, $test, $sessionTest, $deadlineTest) |
        Set-Content -LiteralPath $sourceList -Encoding ASCII

    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -d $classes "@$sourceList"
    if ($LASTEXITCODE -ne 0) {
        throw "Shared broker transport javac failed with exit $LASTEXITCODE."
    }
    & $java -cp $classes io.github.mesmerprism.rustyquest.broker_transport.Rfc6455CodecTest
    if ($LASTEXITCODE -ne 0) {
        throw "Shared broker transport host test failed with exit $LASTEXITCODE."
    }
    & $java -cp $classes io.github.mesmerprism.rustyquest.broker_transport.BoundedWebSocketSessionTest
    if ($LASTEXITCODE -ne 0) {
        throw "Bounded broker session host test failed with exit $LASTEXITCODE."
    }
    & $java -cp $classes io.github.mesmerprism.rustyquest.broker_transport.DeadlineInputStreamTest
    if ($LASTEXITCODE -ne 0) {
        throw "Broker transport deadline host test failed with exit $LASTEXITCODE."
    }
} finally {
    if (Test-Path -LiteralPath $buildRoot) {
        $resolved = [IO.Path]::GetFullPath($buildRoot)
        if (-not $resolved.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean an out-of-temp broker transport test path: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

Write-Host "Rusty Quest shared broker transport host checks passed."
