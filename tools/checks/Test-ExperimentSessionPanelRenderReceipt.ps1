[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if(Test-Path $OutputRoot){throw 'Create-new output required'}
$null=New-Item -ItemType Directory $OutputRoot
$panel=Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$module=Get-Content (Join-Path $panel 'BreathCompositionPanelModule.java') -Raw
$start=$module.IndexOf('    private boolean remotePanelResumed;')
$end=$module.IndexOf('    private void selectExperimenterPage(', $start)
if($start-lt0-or$end-lt0){throw 'Production observer extraction missing'}
$adapter=$module.Substring($start,$end-$start)
$sources=@{
'android/view/ViewTreeObserver.java'=@'
package android.view;
import java.util.*;
public class ViewTreeObserver {
 public interface OnDrawListener {void onDraw();}
 public final List<OnDrawListener> listeners=new ArrayList<>();
 public boolean isAlive(){return true;}
 public void addOnDrawListener(OnDrawListener l){listeners.add(l);}
 public void removeOnDrawListener(OnDrawListener l){listeners.remove(l);}
 public void draw(){for(OnDrawListener l:new ArrayList<>(listeners))l.onDraw();}
}
'@
'android/view/View.java'=@'
package android.view;
import java.util.*;
public class View {
 public static final int VISIBLE=0;
 public boolean attached=true,shown=true;public int visibility=0;
 public final ViewTreeObserver observer=new ViewTreeObserver();
 public final List<Runnable> posts=new ArrayList<>();
 public ViewTreeObserver getViewTreeObserver(){return observer;}
 public boolean post(Runnable r){posts.add(r);return true;}
 public boolean isAttachedToWindow(){return attached;}public boolean isShown(){return shown;}
 public int getWindowVisibility(){return visibility;}public void invalidate(){}
 public void drain(){for(Runnable r:new ArrayList<>(posts)){posts.remove(r);r.run();}}
}
'@
'android/view/ViewGroup.java'='package android.view; public class ViewGroup extends View {public View child=new View();public View getChildAt(int i){return child;}}'
'android/R.java'='package android; public class R {public static class id {public static int content=1;}}'
'android/os/SystemClock.java'='package android.os; public class SystemClock {public static long now=100;public static long elapsedRealtime(){return now;}}'
'BreathCompositionPanelModule.java'=(@'
package io.github.mesmerprism.rustyquest.native_renderer;
import android.view.*;import android.os.SystemClock;
class ExperimentSessionBleServer {
 long generation=1;int confirmations;String lastId,detail;
 long currentGeneration(){return generation;}
 boolean updateReceiptForGeneration(long g,String id,String state,String detail){if(g!=generation)return false;confirmations++;lastId=id;this.detail=detail;return true;}
}
class BreathCompositionPanelModule {
 static class RemotePending {String browserId="0123456789abcdef";long serverGeneration=1,startedAtElapsedMs=100;}
 RemotePending remotePending=new RemotePending();final ExperimentSessionBleServer server=new ExperimentSessionBleServer();
 boolean experimentShellDestroyed,finishing;long breathCompositionPanelNavigationEpoch=1;String breathCompositionPanelTopic="polar";
 final ViewGroup root=new ViewGroup();
 ExperimentSessionBleServer remoteServer(){return server;} View findViewById(int i){return root;} boolean isFinishing(){return finishing;}
 void begin(){remotePanelResumed=true;observeRemotePolarRender(remotePending);}
 void draw(){root.child.observer.draw();}void drain(){root.child.drain();}
 void pause(){remotePanelResumed=false;cancelRemotePolarRender();}
'@ + $adapter + "`n}")
'RenderReceiptHostTest.java'=@'
package io.github.mesmerprism.rustyquest.native_renderer;
import android.view.*;import android.os.SystemClock;
public class RenderReceiptHostTest {
 static int count;static void check(boolean v,String label){if(!v)throw new AssertionError(label);count++;}
 interface Drift {void apply(BreathCompositionPanelModule m);}
 public static void main(String[] args){
  BreathCompositionPanelModule m=new BreathCompositionPanelModule();m.begin();
  check(m.server.confirmations==0,"dispatch and observer registration not completion");m.draw();
  check(m.server.confirmations==0,"onDraw alone is not post-draw completion");m.drain();
  check(m.server.confirmations==1&&m.server.lastId.equals("0123456789abcdef"),"matching actual post-draw command completion without input-focus fact");
  check(m.server.detail.equals("Polar setup content rendered by the Quest app."),"render-only receipt detail");
  m.draw();m.drain();check(m.server.confirmations==1,"once completion");check(m.root.child.observer.listeners.isEmpty(),"completed listener removed");
  Drift[] drifts={x->x.server.generation++,x->x.remotePending=new BreathCompositionPanelModule.RemotePending(),x->x.breathCompositionPanelNavigationEpoch++,
   x->x.breathCompositionPanelTopic="home",x->x.root.child=new View(),x->x.pause(),x->x.root.child.attached=false,x->x.root.child.shown=false,
   x->x.root.child.visibility=4,x->x.experimentShellDestroyed=true,x->x.finishing=true,x->SystemClock.now=30200};
  for(int i=0;i<drifts.length;i++){SystemClock.now=100;BreathCompositionPanelModule x=new BreathCompositionPanelModule();x.begin();View old=x.root.child;x.draw();drifts[i].apply(x);old.drain();check(x.server.confirmations==0,"actual callback drift denied "+i);}
  SystemClock.now=100;BreathCompositionPanelModule old=new BreathCompositionPanelModule();old.begin();ViewTreeObserver.OnDrawListener listener=old.root.child.observer.listeners.get(0);
  old.pause();old.remotePending=new BreathCompositionPanelModule.RemotePending();old.begin();listener.onDraw();old.drain();check(old.server.confirmations==0,"retired listener cannot complete successor");
  Object panel=new Object(),view=new Object();
  for(int i=0;i<9;i++){ExperimentSessionPanelRenderReceipt r=new ExperimentSessionPanelRenderReceipt("0123456789abcdef",1,panel,view,1);
   boolean accepted=r.complete(i==0?"fedcba9876543210":"0123456789abcdef",i==1?2:1,i==2?new Object():panel,i==3?new Object():view,i==4?2:1,"polar",i!=5,i!=6,i!=7,true,true,i==8);
   check(!accepted,"exact token negative "+i);}
  ExperimentSessionPanelRenderReceipt r=new ExperimentSessionPanelRenderReceipt("0123456789abcdef",1,panel,view,1);r.cancel();check(!r.complete("0123456789abcdef",1,panel,view,1,"polar",true,true,true,true,true,false),"cancelled token denied");
  System.out.println("RenderReceiptHostTest PASS count="+count+" production observer extracted byte-exact; Android surfaces/draw loop modeled; no physical qualification");
 }
}
'@
}
$generated=@()
foreach($entry in $sources.GetEnumerator()){$path=Join-Path $OutputRoot $entry.Key;$null=New-Item -ItemType Directory (Split-Path $path) -Force;[IO.File]::WriteAllText($path,$entry.Value,[Text.UTF8Encoding]::new($false));$generated+=$path}
$policy=Join-Path $panel 'ExperimentSessionPanelRenderReceipt.java'
& javac --release 8 -encoding UTF-8 -d $OutputRoot $policy @generated
if($LASTEXITCODE){throw 'Production observer host compilation failed'}
& java -cp $OutputRoot io.github.mesmerprism.rustyquest.native_renderer.RenderReceiptHostTest
if($LASTEXITCODE){throw 'Production observer host controls failed'}
$feature=Get-Content (Join-Path $repo 'fixtures/native-app-features/ui/breath-composition-panel/ui.breath_composition_control_panel.feature.json') -Raw|ConvertFrom-Json
$path='apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/ExperimentSessionPanelRenderReceipt.java'
if(@($feature.panel_composition.modules[0].source_files|Where-Object{$_-ceq$path}).Count-ne1){throw 'Exact feature helper closure missing'}
@{passed=$true;production_observer_sha256=(Get-FileHash (Join-Path $panel 'BreathCompositionPanelModule.java')).Hash.ToLowerInvariant();helper_sha256=(Get-FileHash $policy).Hash.ToLowerInvariant();limits='Byte-exact extracted production observer; modeled Android surfaces; no APK/device/physical proof'}|ConvertTo-Json|Set-Content (Join-Path $OutputRoot 'RESULT.json')
