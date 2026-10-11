[CmdletBinding()]
param([string]$RepoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererAppStatusBridge.psm1') -Force
$temp=Join-Path ([IO.Path]::GetTempPath()) ('rq-polar-cold-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $temp
try {
 foreach($selected in @($false,$true)) {
  $dir=Join-Path $temp ([string]$selected);$null=New-Item -ItemType Directory -Path $dir
  $stubs=@{
   'Context.java'='package android.content; public class Context {public Context getApplicationContext(){return this;} public String getPackageName(){return "test.app";}}'
   'ComponentName.java'='package android.content; public class ComponentName {public String getPackageName(){return "test.app";} public String getClassName(){return "android.app.NativeActivity";}}'
   'Intent.java'='package android.content; public class Intent {public static final String CATEGORY_LAUNCHER="android.intent.category.LAUNCHER";public String action="android.intent.action.MAIN";public String getAction(){return action;}public ComponentName getComponent(){return new ComponentName();}public java.util.Set<String> getCategories(){return java.util.Collections.singleton(CATEGORY_LAUNCHER);}public Object getData(){return null;}public Object getSelector(){return null;}}'
   'Activity.java'='package android.app; public class Activity extends android.content.Context {public android.content.Intent intent=new android.content.Intent(); public android.content.Intent getIntent(){return intent;}}'
   'SystemClock.java'='package android.os; public class SystemClock {public static long elapsedRealtimeNanos(){return 123;}}'
   'Looper.java'='package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}'
   'Handler.java'='package android.os; public class Handler {public static java.util.List<Runnable> queue=new java.util.ArrayList<>();public Handler(Looper l){}public boolean post(Runnable r){queue.add(r);return true;}public static void drain(){for(Runnable r:new java.util.ArrayList<Runnable>(queue))r.run();queue.clear();}}'
   'PolarSensorRuntime.java'='package io.github.mesmerprism.rustyquest.native_renderer; class PolarSensorRuntime {static int calls;static PolarSensorRuntime forApplication(android.content.Context c){return new PolarSensorRuntime();}void ensureAutoConnection(){calls++;}}'
  }
  $sources=@()
  foreach($name in $stubs.Keys){$p=Join-Path $dir $name;[IO.File]::WriteAllText($p,$stubs[$name]);$sources+=$p}
  $bridge=Join-Path $dir 'NativeRendererAppStatusBridge.java';[IO.File]::WriteAllText($bridge,(New-NativeRendererAppStatusBridgeSource -PolarSelected:$selected));$sources+=$bridge
  $root=Join-Path $RepoRoot 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
  $sources+=Join-Path $root 'NativeRendererExperimentLaunchAuthority.java';$sources+=Join-Path $root 'NativeRendererExperimentLauncherPolicy.java'
  $h=Join-Path $dir 'ColdTest.java'
  $code=@'
package io.github.mesmerprism.rustyquest.native_renderer;
public class ColdTest {public static void main(String[] args){
 boolean selected=Boolean.parseBoolean(args[0]);android.app.Activity a=new android.app.Activity();
 NativeRendererAppStatusBridge.prepare(a);
 if(PolarSensorRuntime.calls!=0||!android.os.Handler.queue.isEmpty())throw new AssertionError("preparation/read cannot request sensor");
 a.intent.action="internal";if(NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(a,false)!=0)throw new AssertionError("internal accepted");
 a.intent.action="android.intent.action.MAIN";
 if(NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(a,true)!=0)throw new AssertionError("recreation accepted");
 long epoch=NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(a,false);
 if(epoch<=0||PolarSensorRuntime.calls!=0)throw new AssertionError("admission/async main thread");
 if(NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(a,false)!=0)throw new AssertionError("cold route replay");
 NativeRendererAppStatusBridge.trustedColdLaunch(a,epoch);NativeRendererAppStatusBridge.trustedColdLaunch(a,0);
 android.os.Handler.drain();
 if(PolarSensorRuntime.calls!=(selected?1:0))throw new AssertionError("exact selected one cold request");
 System.out.println("Production admitted cold route / feature-off / async / replay controls PASS selected="+selected);
}}
'@
  [IO.File]::WriteAllText($h,$code);$sources+=$h
  & javac --release 8 -d $dir @sources;if($LASTEXITCODE){throw 'Cold launch production compile failed'}
  & java -cp $dir io.github.mesmerprism.rustyquest.native_renderer.ColdTest ([string]$selected).ToLowerInvariant();if($LASTEXITCODE){throw 'Cold launch production controls failed'}
 }
}finally {
 if([IO.Path]::GetFullPath((Split-Path $temp -Parent))-cne[IO.Path]::GetTempPath().TrimEnd('\','/')){throw 'Unexpected scratch root'}
 Remove-Item -LiteralPath $temp -Recurse -Force
}
