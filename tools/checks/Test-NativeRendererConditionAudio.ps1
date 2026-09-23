[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
$javaRoot = Join-Path $repo 'apps/native-renderer-android/panel-modules/condition-audio/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$testRoot = Join-Path $repo 'apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer'
$contract = Join-Path $javaRoot 'ConditionAudioContract.java'
$runtime = Join-Path $javaRoot 'ConditionAudioRuntime.java'
$androidFactory = Join-Path $javaRoot 'ConditionAudioAndroidMediaBackendFactory.java'
$test = Join-Path $testRoot 'ConditionAudioRuntimeTest.java'

foreach ($path in @($contract, $runtime, $androidFactory, $test)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing condition-audio source: $path"
    }
}

function Assert-Contains([string]$Path, [string]$Literal) {
    $text = [IO.File]::ReadAllText($Path)
    if (-not $text.Contains($Literal, [StringComparison]::Ordinal)) {
        throw "Missing required literal '$Literal' in $Path"
    }
}

function Assert-NotContains([string]$Path, [string]$Literal) {
    $text = [IO.File]::ReadAllText($Path)
    if ($text.Contains($Literal, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Forbidden condition-audio literal '$Literal' in $Path"
    }
}

Assert-Contains $contract 'packaged-native-app-provider'
Assert-Contains $contract 'rusty.quest.condition_audio.receipt.v1'
Assert-Contains $runtime 'Executors.newSingleThreadExecutor(new ThreadFactory()'
Assert-Contains $runtime 'candidate.prepare(false)'
Assert-Contains $runtime 'threshold-does-not-stop-audio'
Assert-Contains $runtime 'natural-end-silence-session-continues'
Assert-Contains $contract 'CLEANUP_FAILED'
Assert-Contains $runtime 'cleanup-stop-and-release-failed'
Assert-Contains $runtime 'final ReceiptContext admittedReceipt;'
Assert-Contains $runtime 'if (cleanup.success) {'
Assert-Contains $runtime 'if (appLifetime == ConditionAudioRuntime.this) appLifetime = null;'
Assert-Contains $runtime 'interface StartupPreloader'
Assert-Contains $runtime 'ConditionAudioContract.TrackReadiness trackReadiness'
Assert-Contains $runtime 'audio-track-not-ready'
Assert-Contains $androidFactory 'implements ConditionAudioRuntime.StartupPreloader'
Assert-Contains $androidFactory 'verifyPackagedAsset(provider)'
Assert-Contains $androidFactory 'assets.open(provider.logicalDestination'
Assert-Contains $androidFactory 'player.setLooping(false)'
Assert-Contains $test 'cleanupFailureSurvivesCloseAndReinstall'
Assert-Contains $test 'startupPreloadGatesSelectionAndRestart'
Assert-Contains $test 'startupPreloadFailureIsTyped'
Assert-Contains $test 'runNormalSuite();'
Assert-Contains $runtime 'appLifetimeReleasedForTest'
Assert-Contains $contract 'RESTART_TO_EXPERIMENTER'
Assert-Contains $contract 'SAVE_AND_EXIT'
Assert-NotContains $contract 'participant_id'
Assert-NotContains $contract 'device_id'
Assert-NotContains $runtime 'setLooping(true)'
Assert-NotContains $androidFactory 'Stalingrad'
Assert-NotContains $androidFactory 'Buxtehude'
Assert-NotContains $androidFactory 'runOnUiThread'

$javac = Get-Command javac -ErrorAction Stop
$java = Get-Command java -ErrorAction Stop
$output = Join-Path ([IO.Path]::GetTempPath()) ("rq-condition-audio-{0}" -f [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($output) | Out-Null
try {
    & $javac.Source '--release' '8' '-encoding' 'UTF-8' '-d' $output $contract $runtime $test
    if ($LASTEXITCODE -ne 0) { throw "Condition-audio javac failed: $LASTEXITCODE" }
    & $java.Source '-cp' $output 'io.github.mesmerprism.rustyquest.native_renderer.ConditionAudioRuntimeTest'
    if ($LASTEXITCODE -ne 0) { throw "Condition-audio test failed: $LASTEXITCODE" }
    & $java.Source '-cp' $output 'io.github.mesmerprism.rustyquest.native_renderer.ConditionAudioRuntimeTest' '--failed-app-lifetime-barrier'
    if ($LASTEXITCODE -ne 0) { throw "Condition-audio failed-barrier test failed: $LASTEXITCODE" }
} finally {
    if (Test-Path -LiteralPath $output) {
        Remove-Item -LiteralPath $output -Recurse -Force
    }
}

Write-Host 'Test-NativeRendererConditionAudio PASS'
