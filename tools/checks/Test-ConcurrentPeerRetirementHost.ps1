param(
 [Parameter(Mandatory)][string]$InputSpecPath,
 [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$InputSpecSha256,
 [Parameter(Mandatory)][string]$SupportClassesRoot,
 [Parameter(Mandatory)][string]$OutputRoot,
 [switch]$Baseline
)
$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if((Get-FileHash $InputSpecPath).Hash.ToLowerInvariant()-cne$InputSpecSha256){throw 'Tool spec changed'}
if(Test-Path $OutputRoot){throw 'CreateNew output required'}
$spec=Get-Content $InputSpecPath -Raw|ConvertFrom-Json -Depth 30
function Bind($row){if((Get-FileHash $row.path).Hash.ToLowerInvariant()-cne$row.sha256){throw 'Dependency changed'};return [string]$row.path}
$compiler=@($spec.jvm.compiler_jars|ForEach-Object{Bind $_})
$libraries=@($spec.jvm.classpath|Sort-Object @{Expression={if($_.path -match 'json-'){0}else{1}}}|Where-Object {$_.path -match 'android.jar|json-|kotlin-stdlib'}|ForEach-Object{Bind $_})
if(!$libraries){throw 'Android/JSON host classpath unavailable'}
$classes=Join-Path $OutputRoot 'classes'; New-Item -ItemType Directory $classes|Out-Null
function WriteHost($name,$text){$p=Join-Path $OutputRoot $name;[IO.File]::WriteAllText($p,$text,[Text.UTF8Encoding]::new($false));return $p}
$cp=(@($classes,$SupportClassesRoot)+$libraries+$compiler)-join [IO.Path]::PathSeparator
$supportFiles=@(Get-ChildItem (Join-Path $SupportClassesRoot 'io/github/mesmerprism/rustyquest/media') -File -Filter '*.class')
$supportFiles+=@(Get-ChildItem (Join-Path $SupportClassesRoot 'io/github/mesmerprism/rustyquest/spatial_camera_panel') -File -Filter 'SpatialStereoVideoPlayback*.class')
$supportPins=@($supportFiles|ForEach-Object{[ordered]@{path=$_.FullName;sha256=(Get-FileHash $_.FullName).Hash.ToLowerInvariant()}})
$toolPins=@(@($spec.tools.java,$spec.tools.javac)+$libraries+$compiler|Select-Object -Unique|ForEach-Object{[ordered]@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant()}})
function Run($name,$exe,$arguments){
 $p=[Diagnostics.ProcessStartInfo]::new();$p.FileName=$exe;$p.UseShellExecute=$false;$p.CreateNoWindow=$true;$p.RedirectStandardOutput=$true;$p.RedirectStandardError=$true
 foreach($a in $arguments){[void]$p.ArgumentList.Add([string]$a)}
 $child=[Diagnostics.Process]::Start($p);$out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync()
 if(!$child.WaitForExit(60000)){$child.Kill($true);throw "Host $name exceeded 60 seconds"}
 [IO.File]::WriteAllText((Join-Path $OutputRoot "$name.stdout.txt"),$out.GetAwaiter().GetResult())
 [IO.File]::WriteAllText((Join-Path $OutputRoot "$name.stderr.txt"),$err.GetAwaiter().GetResult())
 if($child.ExitCode){throw "Host $name failed; retained streams"}
}
$looper=WriteHost 'Looper.java' @'
package android.os;
public final class Looper {private static final Looper main=new Looper(); public static Looper myLooper(){return null;} public static Looper getMainLooper(){return main;}}
'@
$native=WriteHost 'EmbeddedDuplexNative.java' @'
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
public final class EmbeddedDuplexNative {
 public static final int FRAME_EVIDENCE_VERSION=2,ACQUIRED_TIMED_OBSERVATION_WORDS=19,EFFECTIVE_TIMED_OBSERVATION_WORDS=21,FRAME_OBSERVATION_WORDS=17,FRAME_TIMED_OBSERVATION_WORDS=19;
 public static boolean localCameraQuiescent(){return true;}
 public static boolean registerReceiverFrame(long[] x){throw new AssertionError("unexpected frame adoption");}
 public static boolean recordReceiverFrameRendered(long[] x){throw new AssertionError("unexpected render");}
 public static void retireReceiverConnection(long g,long c){}
 public static void retireReceiverGeneration(long g){}
 public static long[] currentReceiverAcquiredFrameTimed(long... x){return null;}
 public static long[] currentReceiverEffectiveFrameTimed(long... x){return null;}
 public static long[] currentReceiverFrame(long... x){return null;}
 public static long[] currentReceiverFrameTimed(long... x){return null;}
}
'@
$seams=WriteHost 'SourceBoundary.kt' @'
package io.github.mesmerprism.rustyquest.spatial_camera_panel
internal fun activityMarkerToken(x: String) = x
internal data class SpatialVideoProjectionSettings(val active:Boolean=false,val source:String="")
internal data class SpatialPeerProjectionDecoderIdentity(val routeGeneration:Long,val decoderToken:Long,val readerGeneration:Long)
internal object OwnPackedPoolNative {
 var retirementAllowed=true
 fun captureRouteSelected()=true
 fun concurrentPeerAdmission(r:Long,c:Long,s:Long)=longArrayOf(r,c,s,17,1)
 fun nativeRetirePeerSource(r:Long,d:Long,i:Long):Boolean {check(r>0&&d==42L&&i==43L);return retirementAllowed}
}
'@
$own=WriteHost 'OwnBoundary.kt' @'
package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex
internal class OwnStereoCaptureRuntime {
 enum class Phase {Live}
 class Capture {fun fresh()=true}
 private val capture=Capture()
 fun retainedCapture()=capture
 fun phase()=Phase.Live
 companion object {private val value=OwnStereoCaptureRuntime();fun currentForApplication()=value}
}
'@
$app=Join-Path $root 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
$test=Join-Path $root 'apps/spatial-camera-panel-android/host-tests/embedded_duplex/ConcurrentPeerRetirementHost.kt'
$production=@((Join-Path $app 'SpatialVideoSourceRoutingCoordinator.kt'),(Join-Path $app 'embedded_duplex/EmbeddedDuplexDisplayCoordinator.kt'),(Join-Path $app 'embedded_duplex/EmbeddedDuplexReceiver.java'),(Join-Path $app 'embedded_duplex/EmbeddedDuplexDisplay.java'))
$before=@($production+$test+$PSCommandPath|ForEach-Object{[ordered]@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant()}})
if($Baseline){
 foreach($i in 0..1){
  $relative=[IO.Path]::GetRelativePath($root,$production[$i]).Replace('\','/')
  $bytesText=(& git -C $root show "HEAD:$relative") -join "`n"
  if($LASTEXITCODE){throw 'Original Git source unavailable'}
  $production[$i]=WriteHost ([IO.Path]::GetFileName($production[$i])) $bytesText
 }
}
$executed=@($production+$test|ForEach-Object{[ordered]@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant()}})
Run 'java-production' $spec.tools.javac (@('-encoding','UTF-8','-cp',$cp,'-d',$classes,$looper,$native)+$production[2..3])
$kt=@('-cp',($compiler-join[IO.Path]::PathSeparator),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',$cp,'-d',$classes,$seams,$own)+$production[0..1]+$test
Run 'kotlin-production' $spec.tools.java $kt
$runArgs=@('-cp',$cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.ConcurrentPeerRetirementHost')
if($Baseline){$runArgs+= 'baseline'}
Run 'production-chain' $spec.tools.java $runArgs
foreach($p in @($before)+@($executed)+@($supportPins)+@($toolPins)){if((Get-FileHash $p.path).Hash.ToLowerInvariant()-cne$p.sha256){throw 'Source/dependency drift'}}
[ordered]@{schema='rusty.quest.concurrent_peer_retirement_host.v1';status='passed';baseline=[bool]$Baseline;source_pins=$executed;candidate_preservation_pins=$before;support_class_pins=$supportPins;tool_dependency_pins=$toolPins;input_spec=@{path=$InputSpecPath;sha256=$InputSpecSha256};does_not_prove=@('device retirement','camera/GPU/codec readiness','full-product device restart')}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $OutputRoot 'result.json') -Encoding utf8NoBOM
Get-Content (Join-Path $OutputRoot 'production-chain.stdout.txt')
