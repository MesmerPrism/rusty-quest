[CmdletBinding()]
param([string]$RepoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererAppStatusBridge.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererBootHostFixtures.psm1') -Force
$nativeBridge = [IO.File]::ReadAllText((Join-Path $RepoRoot 'apps/native-renderer-android/native/src/native_renderer_panel_bridge.rs'))
$remoteBegin = $nativeBridge.IndexOf('fn consume_remote_preparation_launch(')
if ($remoteBegin -lt 0) { throw 'Remote preparation JNI boundary missing.' }
$remoteEnd = $nativeBridge.IndexOf('#[cfg(target_os = "android")]', $remoteBegin)
if ($remoteEnd -lt 0) { throw 'Remote preparation JNI boundary missing.' }
$remoteBinding = $nativeBridge.Substring($remoteBegin, $remoteEnd-$remoteBegin)
$remoteClass = [regex]::Match($remoteBinding, '\.new_string\(\s*"io\.github\.mesmerprism\.rustyquest\.native_renderer\.([A-Za-z0-9_]+)"\s*\)')
if (-not $remoteClass.Success) { throw 'Remote preparation Java owner missing.' }
$remotePreparationOwner = $remoteClass.Groups[1].Value
$temp=Join-Path ([IO.Path]::GetTempPath()) ('rq-polar-cold-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $temp
try {
 foreach($selected in @($false,$true)) { foreach($product in @($false,$true)) {
  $dir=Join-Path $temp ("$selected-$product");$null=New-Item -ItemType Directory -Path $dir
  $sources=@(New-NativeRendererBootHostFixtures -Directory $dir)
  $polar=Join-Path $dir 'PolarSensorRuntime.java'
  [IO.File]::WriteAllText($polar,'package io.github.mesmerprism.rustyquest.native_renderer; class PolarSensorRuntime {static int calls;static PolarSensorRuntime forApplication(android.content.Context c){return new PolarSensorRuntime();}void ensureAutoConnection(){calls++;}}')
  $sources+=$polar
  $bridge=Join-Path $dir 'NativeRendererAppStatusBridge.java';[IO.File]::WriteAllText($bridge,(New-NativeRendererAppStatusBridgeSource -PolarSelected:$selected));$sources+=$bridge
  $root=Join-Path $RepoRoot 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
  $sources+=Join-Path $root 'NativeRendererExperimentLaunchAuthority.java';$sources+=Join-Path $root 'NativeRendererExperimentLauncherPolicy.java';$sources+=Join-Path $root 'NativeRendererSoftKioskCoordinator.java';$sources+=Join-Path $root 'NativeRendererForegroundGuardPolicy.java'
  $sources+=Join-Path $root 'NativeRendererActivityComponents.java';$sources+=Join-Path $root 'NativeRendererBootObservation.java';$sources+=Join-Path $root 'NativeRendererBootLaunchPolicy.java';$sources+=Join-Path $root 'NativeRendererSelfKioskApplication.java'
  $remoteSource = Join-Path $root ($remotePreparationOwner+'.java')
  if ($sources -notcontains $remoteSource) { $sources += $remoteSource }
  $h=Join-Path $dir 'ColdTest.java'
  $code=@'
package io.github.mesmerprism.rustyquest.native_renderer;
public class ColdTest {public static void main(String[] args){
 boolean selected=Boolean.parseBoolean(args[0]),product=Boolean.parseBoolean(args[1]);
 android.content.pm.PackageManager.metadata.put("rusty.quest.native_renderer.original_panel_native_host",product);
 android.content.pm.PackageManager.metadata.put("rusty.quest.experiment.boot_autostart",true);
 NativeRendererSelfKioskApplication app=new NativeRendererSelfKioskApplication();android.content.Context.application=app;app.onCreate();
 android.app.Activity a=product?new NativeProductPanelActivity():new android.app.NativeActivity();
 a.intent.setClassName("test.app",a.getClass().getName());
 android.app.Activity wrong=product?new android.app.NativeActivity():new NativeProductPanelActivity();
 wrong.intent.setClassName("test.app",wrong.getClass().getName());
 if(NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(wrong,false)!=0)throw new AssertionError("unselected physical host accepted");
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
 if(REMOTE_PREPARATION_OWNER.consumeRemotePreparation(new android.app.NativeActivity(),epoch,1))throw new AssertionError("foreign handle accepted");
 if(REMOTE_PREPARATION_OWNER.consumeRemotePreparation(a,epoch,0))throw new AssertionError("missing XR generation accepted");
 if(REMOTE_PREPARATION_OWNER.consumeRemotePreparation(a,epoch+1,1))throw new AssertionError("wrong launch epoch accepted");
 android.content.Context.application=new android.app.Application();
 if(REMOTE_PREPARATION_OWNER.consumeRemotePreparation(a,epoch,1))throw new AssertionError("unselected application accepted");
 if(NativeRendererExperimentLaunchAuthority.pendingEpochForImmersiveStartup()!=epoch||android.content.Context.serviceStarts!=0)throw new AssertionError("unselected application consumed or dispatched");
 android.content.Context.application=app;
 if(!REMOTE_PREPARATION_OWNER.consumeRemotePreparation(a,epoch,1))throw new AssertionError("remote launch denied");
 if(android.content.Context.serviceStarts!=1)throw new AssertionError("service not dispatched within current submitted-frame callback");
 if(REMOTE_PREPARATION_OWNER.consumeRemotePreparation(a,epoch,1))throw new AssertionError("remote replay");
 android.os.Handler.drain();
 if(android.content.Context.serviceStarts!=1||!NativeRendererSoftKioskCoordinator.process().snapshot().armed)throw new AssertionError("remote guard not armed");
 NativeRendererExperimentLaunchAuthority.invalidatePending();
 android.app.Activity next=product?new NativeProductPanelActivity():new android.app.NativeActivity();next.intent.setClassName("test.app",next.getClass().getName());long nextEpoch=NativeRendererExperimentLaunchAuthority.issueFromNativeActivity(next,false);
 NativeRendererExperimentLaunchAuthority.invalidatePending();
 if(REMOTE_PREPARATION_OWNER.consumeRemotePreparation(next,nextEpoch,2))throw new AssertionError("retired actor accepted");
 android.os.Handler.drain();
 if(android.content.Context.serviceStarts!=1)throw new AssertionError("retired actor armed");
 System.out.println("Production admitted cold route / feature-off / async / replay controls PASS selected="+selected+" product="+product);
}}
'@
  $code=$code.Replace('REMOTE_PREPARATION_OWNER',$remotePreparationOwner)
  [IO.File]::WriteAllText($h,$code);$sources+=$h
  & javac --release 8 -d $dir @sources;if($LASTEXITCODE){throw 'Cold launch production compile failed'}
  & java -cp $dir io.github.mesmerprism.rustyquest.native_renderer.ColdTest ([string]$selected).ToLowerInvariant() ([string]$product).ToLowerInvariant();if($LASTEXITCODE){throw 'Cold launch production controls failed'}
 } }
}finally {
 if([IO.Path]::GetFullPath((Split-Path $temp -Parent))-cne[IO.Path]::GetTempPath().TrimEnd('\','/')){throw 'Unexpected scratch root'}
 Remove-Item -LiteralPath $temp -Recurse -Force
}
