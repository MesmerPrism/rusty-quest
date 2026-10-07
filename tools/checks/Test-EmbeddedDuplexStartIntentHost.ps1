[CmdletBinding()]
param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference='Stop'
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
$package='io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
$app=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/app'
$main=Join-Path $app ('src/main/java/'+$package)
$test=Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/EmbeddedDuplexStartIntentSlotHostTest.java'
$jsonJar=Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json') -Recurse -Filter 'json-*.jar' | Sort-Object FullName -Descending | Select-Object -First 1
if(-not$jsonJar){throw 'Host org.json test dependency unavailable'}
$output=Join-Path ([IO.Path]::GetTempPath()) ('duplex-start-intent-'+[guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $output)
$javac=(Get-Command javac -ErrorAction Stop).Source
$java=Join-Path (Split-Path $javac -Parent) 'java.exe'
$hostSource=Get-Content -LiteralPath (Join-Path $main 'EmbeddedDuplexProcessHost.java') -Raw
$liveStart=$hostSource.IndexOf('    boolean preflightLive(')
$liveEnd=$hostSource.IndexOf('    private EmbeddedDuplexRuntimeStatus runtimeStatusOnCommandLane()', $liveStart)
if($liveStart-lt0-or$liveEnd-le$liveStart){throw 'Actual preflightLive method unavailable'}
$liveMethod=$hostSource.Substring($liveStart,$liveEnd-$liveStart)
$harness=@'
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.util.concurrent.atomic.AtomicReference;
final class PreflightLiveHarness {
 final Object attachmentGate = new Object();
 final EmbeddedDuplexStartIntentSlot startIntent = new EmbeddedDuplexStartIntentSlot();
 enum Phase { READY, FAILED }
 final AtomicReference<Phase> phase = new AtomicReference<>(Phase.READY);
 boolean localFixture, displayDetaching, closeInFlight;
 long attachmentGeneration = 7;
 String runtimeConfigSha256 = "b".repeat(64), enrollmentRecordSha256 = "c".repeat(64);
 Fence processFence = new Fence();
 static final class Fence {
  boolean recovery, stale;
  long generation() { return 1; }
  void requireLive(long generation) { if(stale) throw new IllegalStateException("stale modeled fence"); }
  boolean recoveryOnly() { return recovery; }
 }
'@
$harnessPath=Join-Path $output 'PreflightLiveHarness.java'
[IO.File]::WriteAllText($harnessPath,($harness+"`n"+$liveMethod+"`n}"),[Text.UTF8Encoding]::new($false))
$sources=@('EmbeddedDuplexPairStatus.java','EmbeddedDuplexStartPreflight.java','EmbeddedDuplexStartIntentSlot.java'|ForEach-Object{Join-Path $main $_})
& $javac '--release' '17' '-encoding' 'UTF-8' '-cp' $jsonJar.FullName '-d' $output @sources $test $harnessPath
if($LASTEXITCODE-ne0){throw 'Start intent Java compilation failed'}
& $java '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexStartIntentSlotHostTest'
if($LASTEXITCODE-ne0){throw 'Start intent lifecycle regressions failed'}
$dispatch=$hostSource.IndexOf('startIntent.dispatch(starting);')
$native=$hostSource.IndexOf('String nativeReceipt = noMediaFallback ?')
$wrapped=$hostSource.IndexOf('String receipt = ConcurrentStereoQualification.lifecycle(')
$ack=$hostSource.IndexOf('startIntent.acknowledge(starting, nativeReceipt);')
if($dispatch-lt0-or$native-le$dispatch-or$wrapped-le$native-or$ack-le$wrapped){throw 'Actual host Start consume/receipt ordering differs'}
foreach($required in @('!preflightLive(starting)','return startIntent.prepare(next);','startIntent.pending() == observed','System.currentTimeMillis() < observed.sessionExpiresAtMs','processFence.afterVerifiedWholeProductCleanup','afterVerifiedNoMediaCleanup')){
 if(-not$hostSource.Contains($required)){throw "Actual host guard missing: $required"}
}
if(([regex]::Matches($hostSource,'startIntent.afterVerifiedCleanup\(\);')).Count-ne2){throw 'Intent cleanup integration differs'}
Write-Output 'Actual host integration ordering/expiry/cleanup source gates PASS; no APK or Android API qualification.'
