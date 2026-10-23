[CmdletBinding()]
param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'), [string]$KnownBirthFixture, [string]$ReplayScopeV6Fixture, [string]$AndroidHome=$env:ANDROID_HOME, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$package = 'io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
$app = Join-Path $RepoRoot 'apps/spatial-camera-panel-android/app'
$main = Join-Path $app "src/main/java/$package"
$test = Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/EmbeddedDuplexProcessFenceHostTest.java'
$historicalTest=Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/EmbeddedDuplexHistoricalAbandonmentHostTest.java'
$knownBirthTest=Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/EmbeddedDuplexKnownBirthAbandonmentHostTest.java'
$v5Test=Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/EmbeddedDuplexReplayScopeV5HostTest.java'
$v6Test=Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/EmbeddedDuplexReplayScopeV6HostTest.java'
$incarnationTest=Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/EmbeddedDuplexProcessIncarnationHostTest.java'
$output = if($OutputDirectory){[IO.Path]::GetFullPath($OutputDirectory)}else{Join-Path ([IO.Path]::GetTempPath()) ('duplex-fence-classes-' + [guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $output){throw 'Process fence host output directory already exists'}
[void](New-Item -ItemType Directory -Path $output)
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = Join-Path (Split-Path $javac -Parent) 'java.exe'
$jsonJar=Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json') -Recurse -Filter 'json-*.jar' | Sort-Object FullName -Descending | Select-Object -First 1
if(-not $jsonJar){throw 'Host org.json test dependency unavailable'}
$hostOs=Join-Path (Split-Path $app -Parent) 'host-tests/embedded_duplex/android-system-host/android/system'
$hostOsSources=@((Join-Path $hostOs 'Os.java'),(Join-Path $hostOs 'OsConstants.java'),(Join-Path $hostOs 'StructStat.java'))
& $javac '--release' '17'  '-encoding' 'UTF-8' '-cp' $jsonJar.FullName '-d' $output (Join-Path $main 'EmbeddedDuplexOriginalScopeDisposition.java') (Join-Path $main 'EmbeddedDuplexProcessFence.java') (Join-Path $main 'EmbeddedDuplexProcessIncarnation.java') (Join-Path $main 'EmbeddedDuplexAbandonment.java') (Join-Path $main 'EmbeddedDuplexAbandonmentRequest.java') (Join-Path $main 'EmbeddedDuplexHistoricalAbandonment.java') (Join-Path $main 'EmbeddedDuplexKnownBirthAbandonment.java') $test $historicalTest $knownBirthTest $incarnationTest @hostOsSources
if ($LASTEXITCODE -ne 0) { throw 'Process fence host compilation failed' }
& $javac '--release' '17' '-encoding' 'UTF-8' '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) '-d' $output $v5Test $v6Test
if($LASTEXITCODE-ne0){throw 'Replay v5/v6 host compilation failed'}
& $java '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexReplayScopeV5HostTest'
if($LASTEXITCODE-ne0){throw 'Replay v5 host regressions failed'}
$v6Args=@();if($ReplayScopeV6Fixture){$v6Args=@((Resolve-Path -LiteralPath $ReplayScopeV6Fixture).Path)}
& $java '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexReplayScopeV6HostTest' @v6Args
if($LASTEXITCODE-ne0){throw 'Replay v6 host regressions failed'}
& $java '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexProcessIncarnationHostTest'
if ($LASTEXITCODE -ne 0) { throw 'Process incarnation host regressions failed' }
if($AndroidHome){
    $androidJar=Join-Path $AndroidHome 'platforms/android-34/android.jar'
    $apiOutput=Join-Path $output 'api34';[void](New-Item -ItemType Directory -Path $apiOutput)
    & $javac -source 8 -target 8 -bootclasspath $androidJar -encoding UTF8 -d $apiOutput (Join-Path $main 'EmbeddedDuplexOriginalScopeDisposition.java') (Join-Path $main 'EmbeddedDuplexProcessFence.java') (Join-Path $main 'EmbeddedDuplexProcessIncarnation.java') (Join-Path $main 'EmbeddedDuplexAbandonment.java') (Join-Path $main 'EmbeddedDuplexAbandonmentRequest.java') (Join-Path $main 'EmbeddedDuplexHistoricalAbandonment.java') (Join-Path $main 'EmbeddedDuplexKnownBirthAbandonment.java')
    if($LASTEXITCODE -ne 0){throw 'Process incarnation genuine API34 typecheck failed'}
    Write-Output 'Process fence and incarnation companion genuine Android API34 typecheck passed; no APK/device qualification.'
}
& $java '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexProcessFenceHostTest'
if ($LASTEXITCODE -ne 0) { throw 'Process fence host regressions failed' }
& $java '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexHistoricalAbandonmentHostTest'
if ($LASTEXITCODE -ne 0) {throw 'Historical abandonment host regressions failed'}
$knownBirthArgs=@(); if($KnownBirthFixture){$knownBirthArgs=@((Resolve-Path -LiteralPath $KnownBirthFixture).Path)}
& $java '-cp' ($output+[IO.Path]::PathSeparator+$jsonJar.FullName) 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexKnownBirthAbandonmentHostTest' @knownBirthArgs
if ($LASTEXITCODE -ne 0) {throw 'Known-birth abandonment host regressions failed'}
$hostSource = Get-Content -Raw -LiteralPath (Join-Path $main 'EmbeddedDuplexProcessHost.java')
# Execute the production predicate; platform observations are explicitly modeled.
$begin=$hostSource.IndexOf('    private void requireAbandonmentEffectFree() {')
$end=$hostSource.IndexOf('    CompletableFuture<String> recoveryStatus(', $begin)
if($begin-lt0-or$end-le$begin){throw 'Production abandonment effect predicate missing'}
$guard=$hostSource.Substring($begin,$end-$begin)
$harness=@'
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.util.concurrent.atomic.AtomicReference;
final class AbandonmentEffectHost {
 enum Phase { NEW, READY, CLOSING, FAILED }
 AtomicReference<Phase> phase=new AtomicReference<>(Phase.NEW);
 long attachmentGeneration,nativeExecutorGeneration;
 boolean displayDetaching,closeInFlight;
 Object platform,resources,pendingReview;
 String runtimeConfigSha256;
 public static void main(String[] argv) {
  new AbandonmentEffectHost().requireAbandonmentEffectFree();
  java.util.List<java.util.function.Consumer<AbandonmentEffectHost>> edits=java.util.List.of(
   h->h.phase.set(Phase.READY),h->h.phase.set(Phase.CLOSING),h->h.phase.set(Phase.FAILED),
   h->h.attachmentGeneration=1,h->h.displayDetaching=true,h->h.closeInFlight=true,
   h->h.platform=new Object(),h->h.resources=new Object(),h->h.runtimeConfigSha256="retained",
   h->h.nativeExecutorGeneration=1,h->h.pendingReview=new Object());
  for(java.util.function.Consumer<AbandonmentEffectHost> edit:edits) {
   AbandonmentEffectHost h=new AbandonmentEffectHost();edit.accept(h);
   try{h.requireAbandonmentEffectFree();throw new AssertionError("live effect admitted");}
   catch(IllegalStateException expected){}
  }
  OwnStereoCaptureRuntime.current=new OwnStereoCaptureRuntime();
  try{new AbandonmentEffectHost().requireAbandonmentEffectFree();throw new AssertionError("Own capture admitted");}
  catch(IllegalStateException expected){}
  OwnStereoCaptureRuntime.current.stopped=true;new AbandonmentEffectHost().requireAbandonmentEffectFree();
  EmbeddedDuplexNative.idle=false;
  try{new AbandonmentEffectHost().requireAbandonmentEffectFree();throw new AssertionError("native host admitted");}
  catch(IllegalStateException expected){}
  System.out.println("Production effect-free predicate: 13 denials and 2 positives PASS; platform observations modeled.");
 }
'@
$tail=@'
}
final class OwnStereoCaptureRuntime {
 static OwnStereoCaptureRuntime current;boolean stopped;
 static OwnStereoCaptureRuntime currentForApplication(){return current;}
 boolean pollStopped(){return stopped;}
}
final class EmbeddedDuplexNative {static boolean idle=true;static boolean processIdleForEnrollment(){return idle;}}
'@
$harnessPath=Join-Path $output 'AbandonmentEffectHost.java'
[IO.File]::WriteAllText($harnessPath,$harness+"`n"+$guard+"`n"+$tail,[Text.UTF8Encoding]::new($false))
& $javac '--release' '17' '-encoding' 'UTF-8' '-d' $output $harnessPath
if($LASTEXITCODE-ne0){throw 'Abandonment production predicate compilation failed'}
& $java '-cp' $output 'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.AbandonmentEffectHost'
if($LASTEXITCODE-ne0){throw 'Abandonment production predicate controls failed'}
$platformSource = Get-Content -Raw -LiteralPath (Join-Path $main 'EmbeddedDuplexPlatform.java')
foreach ($required in @('ensureProcessFence();', 'result.complete(action.run())', 'beforeRuntimeEffects(checkpointSnapshot(), evidenceSnapshot())', 'callbacks.bindProcessFence(processFence)', 'afterVerifiedWholeProductCleanup(', 'platform.retireProcessCallbacksAsync()')) {
    if (-not $hostSource.Contains($required)) { throw "App host fence integration missing: $required" }
}
if (-not $platformSource.Contains('guard.requireLive()') -or -not $platformSource.Contains('guard.retire()')) { throw 'App callback fence integration missing' }
if(-not $platformSource.Contains('EmbeddedDuplexAbandonment.visibleReplay(replayDirectory,file.getBaseFile().getName(),')) {throw 'Historical replay reader projection integration missing'}
$readerBegin=$platformSource.IndexOf('    private String loadReplay(AtomicFile file)')
$preflight=$platformSource.IndexOf('EmbeddedDuplexAbandonment.requireReplayRead(', $readerBegin)
$atomicRead=$platformSource.IndexOf('file.openRead()', $readerBegin)
if($readerBegin-lt0-or$preflight-lt$readerBegin-or$atomicRead-le$preflight) {throw 'Historical sidecar preflight must precede AtomicFile.openRead'}
Write-Output 'App startup/callback source gate passed. No native process-fence or physical-cleanup attestation.'
& python -B (Join-Path $PSScriptRoot 'Test-OriginalScopeDispositionHost.py') --output (Join-Path $output 'original-scopes') --json-jar $jsonJar.FullName
if($LASTEXITCODE-ne0){throw 'Actual Original scope disposition and counter controls failed'}
