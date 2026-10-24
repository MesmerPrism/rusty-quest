param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$HostJsonJar,[Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$out=[IO.Path]::GetFullPath($OutDir); $target=[IO.Path]::GetFullPath((Join-Path $root 'target'))
if(-not $out.StartsWith($target+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path $out)){throw 'Create-new repository target output required'}
$null=New-Item -ItemType Directory $out
$main=Join-Path $root 'crates/rusty-quest-media-stream-android/android/library/src/main/java'
$test=Join-Path $root 'crates/rusty-quest-media-stream-android/android/library/src/test/java/io/github/mesmerprism/rustyquest/media/PipelineWaitObservationMain.java'
$clock=Join-Path $out 'SystemClock.java'
[IO.File]::WriteAllText($clock,'package android.os; public final class SystemClock { public static volatile long fixedNs; public static long elapsedRealtimeNanos(){return fixedNs==0?System.nanoTime():fixedNs;} }')
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
function Get-Method([string]$Text,[string]$Signature) {
    $start=$Text.IndexOf($Signature,[StringComparison]::Ordinal)
    if($start-lt0){throw "Actual production method missing: $Signature"}
    $brace=$Text.IndexOf('{',$start);$depth=0
    for($i=$brace;$i-lt$Text.Length;$i++) {
        if($Text[$i]-eq'{'){$depth++};if($Text[$i]-eq'}'){$depth--;if($depth-eq0){return $Text.Substring($start,$i-$start+1)}}
    }
    throw "Unclosed production method: $Signature"
}
$package=Join-Path $main 'io/github/mesmerprism/rustyquest/media'
$gl=[IO.File]::ReadAllText((Join-Path $package 'PackedStereoGlCompositor.java'))
$capture=[IO.File]::ReadAllText((Join-Path $package 'PackedStereoCaptureOwner.java'))
$encoder=[IO.File]::ReadAllText((Join-Path $package 'PackedStereoEncoderWorker.java'))
$source=[IO.File]::ReadAllText((Join-Path $package 'PackedStereoMediaSourceRuntime.java'))
$header=@'
package io.github.mesmerprism.rustyquest.media;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.*;import org.json.*;import android.os.SystemClock;
public final class PipelineWaitHooksMain {
 static final RuntimeException SENTINEL=new RuntimeException("actual leaf failure");
 static final class Gate {
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);boolean fail;
  void call(){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}
   catch(InterruptedException e){throw new AssertionError(e);}if(fail)throw SENTINEL;}
 }
 interface Action {void run()throws Exception;}
 static long number(JSONArray row,int column){return Long.parseLong(row.getString(column),36);}
 static void blocked(Gate gate,PipelineWaitObservation observation,int row,long pair,Action action)throws Exception{
  AtomicReference<Throwable> error=new AtomicReference<>();Thread t=new Thread(()->{try{action.run();}catch(Throwable e){error.set(e);}});t.start();
  try{
   if(!gate.entered.await(5,TimeUnit.SECONDS))throw new AssertionError("production hook did not reach leaf");
   JSONArray r=observation.snapshot().getJSONArray("rows").getJSONArray(row);
   if(number(r,10)<=0||number(r,11)!=pair)throw new AssertionError("missing actual inflight identity");
  }finally{gate.release.countDown();t.join(5000);}
  if(t.isAlive()||error.get()!=(gate.fail?SENTINEL:null))throw new AssertionError("media result changed",error.get());
  JSONArray r=observation.snapshot().getJSONArray("rows").getJSONArray(row);
  if(number(r,1)!=1||number(r,2)!=(gate.fail?1:0)||number(r,10)!=0)throw new AssertionError("completion/finally lost");
 }
 static final class CaptureProbe {
  final Object subscriptionLock=new Object();boolean stopRequested;long encoderGeneration=73;
  final PipelineWaitObservation waits=new PipelineWaitObservation("capture","encoder_offer");
  interface EncoderConsumer{void offer(PackedStereoEncoderInput frame);}EncoderConsumer encoderConsumer;
'@
$hooks=$header+(Get-Method $capture 'public void offerEncoderFrame(')+@'
 }
 static final class GlState {final Gate gate;Object leftInput,rightInput;GlState(Gate gate){this.gate=gate;}void makePbufferCurrent(){gate.call();}}
 static final class CompositorProbe {
  final PipelineWaitObservation waits=new PipelineWaitObservation("capture_compositor","capture_iteration","capture_make_current","capture_pool_poll_ready");
  final Object signal=new Object();volatile boolean stopRequested;int leftPending,rightPending;
  long leftSurfaceFrames,rightSurfaceFrames;Object leftCorrelation,rightCorrelation;SyntheticRequest syntheticRequest;
  static final class SyntheticRequest{}
  CaptureFrameTrace leftFrameTrace,rightFrameTrace;
  void renderSynthetic(GlState g,Object request){}void consumeCameraFrame(GlState g,Object in,Object correlation,String eye,long epoch){}
'@+(Get-Method $gl 'private void observedMakeCurrent(')+(Get-Method $gl 'private void observedPollReady(')+
 'void loop(GlState gl,PackedStereoPoolExecutor.Pool pool)throws Exception{'+(Get-Method $gl 'while (!stopRequested)')+'}'+@'
 }
 static final class MediaCodec {
  static final class BufferInfo{long presentationTimeUs=95;}final Gate gate;MediaCodec(Gate gate){this.gate=gate;}
  int dequeueOutputBuffer(BufferInfo info,long timeout){if(timeout!=10000)throw new AssertionError("timeout changed");gate.call();return 84;}
 }
 static final class DrainProbe {
  final PipelineWaitObservation drainWaits=new PipelineWaitObservation("encoder_output","encoder_drain","encoder_dequeue_output");
  long captureConsumerGeneration=73;static final long ENCODER_DRAIN_TIMEOUT_US=10000;
'@+(Get-Method $source 'private int observedDequeue(')+@'
 }
 static final class EGL14 {
  static Gate current,swap;static boolean eglMakeCurrent(Object d,Object a,Object b,Object c){current.call();return true;}
  static boolean eglSwapBuffers(Object d,Object s){swap.call();return true;}
 }
 static final class EGLExt {static boolean eglPresentationTimeANDROID(Object d,Object w,long pts){if(pts!=95)throw new AssertionError("PTS changed");return true;}}
 static final class GLES20 {
  static final int GL_FRAMEBUFFER=0,GL_BLEND=0,GL_FLOAT=0,GL_TEXTURE0=0,GL_TEXTURE_2D=0,GL_TRIANGLE_STRIP=0,GL_NO_ERROR=0;
  static void glBindFramebuffer(int a,int b){}static void glViewport(int a,int b,int c,int d){}static void glDisable(int a){}
  static void glUseProgram(int a){}static int glGetAttribLocation(int a,String b){return 0;}
  static void glVertexAttribPointer(int a,int b,int c,boolean d,int e,FloatBuffer f){}static void glEnableVertexAttribArray(int a){}
  static void glActiveTexture(int a){}static void glBindTexture(int a,int b){}static void glUniform1i(int a,int b){}
  static int glGetUniformLocation(int a,String b){return 0;}static void glDrawArrays(int a,int b,int c){}static int glGetError(){return 0;}
 }
 static final class EncoderProbe {
  final PipelineWaitObservation waits=new PipelineWaitObservation("encoder_input","encoder_input_cycle","encoder_make_current","encoder_egl_swap");
  long generation=73;Object display,window,context;int program;FloatBuffer vertices=FloatBuffer.allocate(16);
  static void require(boolean actual,String message){if(!actual)throw new IllegalStateException(message);}
'@+(Get-Method $encoder 'void draw(PackedStereoEncoderInput.Imported')+@'
 }
 static Gate free(){Gate gate=new Gate();gate.release.countDown();return gate;}
 static PackedStereoEncoderInput input(){return new PackedStereoEncoderInput(){
  public PackedStereoPoolExecutor.PairIdentity pair(){return new PackedStereoPoolExecutor.PairIdentity(84,1,2,94,95);}
  public Imported importOnCurrentContext(){return new Imported(1,2,3);}public void finishAfterSubmission(){}
  public boolean pollRetired(){return true;}public void releaseUnsubmitted(){}
 };}
 public static void main(String[] args)throws Exception{
  for(boolean failure:new boolean[]{false,true}){
   Gate offer=new Gate();offer.fail=failure;CaptureProbe capture=new CaptureProbe();capture.encoderConsumer=frame->offer.call();
   blocked(offer,capture.waits,0,84,()->capture.offerEncoderFrame(input()));
   Gate current=new Gate();current.fail=failure;CompositorProbe compositor=new CompositorProbe();
   blocked(current,compositor.waits,1,0,()->compositor.observedMakeCurrent(new GlState(current)));
   Gate poll=new Gate();poll.fail=failure;
   PackedStereoPoolExecutor.Pool pool=new PackedStereoPoolExecutor.Pool(){
    public PackedStereoPoolExecutor.Write beginWrite(){return null;}public void finishWrite(PackedStereoPoolExecutor.Write w,PackedStereoPoolExecutor.PairIdentity p){}
    public void quarantineWrite(PackedStereoPoolExecutor.Write w,String r){}public void stopAccepting(){}
    public void pollReady(){poll.call();}public boolean retireStopped(){return true;}
   };
   blocked(poll,compositor.waits,2,0,()->compositor.observedPollReady(pool));
   Gate dequeue=new Gate();dequeue.fail=failure;DrainProbe drain=new DrainProbe();
   blocked(dequeue,drain.drainWaits,1,0,()->{if(drain.observedDequeue(new MediaCodec(dequeue),new MediaCodec.BufferInfo())!=84)throw new AssertionError("dequeue return changed");});
   Gate swap=new Gate();swap.fail=failure;EGL14.current=free();EGL14.swap=swap;EncoderProbe encoder=new EncoderProbe();
   blocked(swap,encoder.waits,2,84,()->encoder.draw(new PackedStereoEncoderInput.Imported(1,2,3),input().pair()));
   Gate loopPoll=new Gate();loopPoll.fail=failure;CompositorProbe loop=new CompositorProbe();
   PackedStereoPoolExecutor.Pool loopPool=new PackedStereoPoolExecutor.Pool(){
    public PackedStereoPoolExecutor.Write beginWrite(){return null;}public void finishWrite(PackedStereoPoolExecutor.Write w,PackedStereoPoolExecutor.PairIdentity p){}
    public void quarantineWrite(PackedStereoPoolExecutor.Write w,String r){}public void stopAccepting(){}
    public void pollReady(){loopPoll.call();loop.stopRequested=true;}public boolean retireStopped(){return true;}
   };
   blocked(loopPoll,loop.waits,2,0,()->loop.loop(new GlState(free()),loopPool));
   JSONArray loopRow=loop.waits.snapshot().getJSONArray("rows").getJSONArray(0);
   if(number(loopRow,1)!=1||number(loopRow,2)!=(failure?1:0))throw new AssertionError("actual render iteration completion changed");
  }
  CaptureProbe held=new CaptureProbe();AtomicReference<Throwable> error=new AtomicReference<>();held.encoderConsumer=frame->{};
  Thread callback=new Thread(()->{try{held.offerEncoderFrame(input());}catch(Throwable e){error.set(e);}});
  synchronized(held.subscriptionLock){
   callback.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean found=false;
   while(System.nanoTime()<deadline){if(number(held.waits.snapshot().getJSONArray("rows").getJSONArray(0),10)>0){found=true;break;}Thread.yield();}
   if(!found)throw new AssertionError("subscription lock wait hidden before operation entry");
  }
  callback.join(5000);if(callback.isAlive()||error.get()!=null)throw new AssertionError("subscription callback changed",error.get());
  System.out.println("PASS 13 exact production method schedules: capture offer, makeCurrent/pollReady, whole idle-wake render iteration, dequeue, encoder EGLswap, pre-subscription-lock entry; normal/throwing results unchanged");
 }
}
'@
$hooksPath=Join-Path $out 'PipelineWaitHooksMain.java';[IO.File]::WriteAllText($hooksPath,$hooks)
& $javac --release 8 -Xlint:all -Werror -cp $HostJsonJar -sourcepath $main -d (Join-Path $out 'host') $test $clock *> (Join-Path $out 'compile.log')
if($LASTEXITCODE){throw 'Pipeline observation host compilation failed'}
& $java -cp ((Join-Path $out 'host')+[IO.Path]::PathSeparator+$HostJsonJar) io.github.mesmerprism.rustyquest.media.PipelineWaitObservationMain *> (Join-Path $out 'test.log')
if($LASTEXITCODE){throw 'Pipeline observation controls failed'}
Get-Content (Join-Path $out 'test.log')
& $javac --release 8 -Xlint:all -Werror -cp ((Join-Path $out 'host')+[IO.Path]::PathSeparator+$HostJsonJar) -sourcepath $main -d (Join-Path $out 'host') $hooksPath *> (Join-Path $out 'hooks-compile.log')
if($LASTEXITCODE){throw 'Exact production hook compilation failed'}
& $java -cp ((Join-Path $out 'host')+[IO.Path]::PathSeparator+$HostJsonJar) io.github.mesmerprism.rustyquest.media.PipelineWaitHooksMain *> (Join-Path $out 'hooks-test.log')
if($LASTEXITCODE){throw 'Exact production blocked-leaf controls failed'}
Get-Content (Join-Path $out 'hooks-test.log')
& $javac --release 8 -Xlint:all -Werror -cp ($AndroidJar+[IO.Path]::PathSeparator+$HostJsonJar) -sourcepath $main -d (Join-Path $out 'android-typecheck') (Join-Path $package 'PackedStereoMediaSourceRuntime.java') *> (Join-Path $out 'android-typecheck.log')
if($LASTEXITCODE){throw 'Full production API34 media typecheck failed'}
$hostProcess=[IO.File]::ReadAllText((Join-Path $root 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexProcessHost.java'))
$ownSchema=$hostProcess.IndexOf('rusty.quest.embedded_duplex.own_capture_diagnostic.v1',[StringComparison]::Ordinal)
$ownStart=$hostProcess.LastIndexOf('JSONObject report = new JSONObject()',$ownSchema,[StringComparison]::Ordinal)
$ownEnd=$hostProcess.IndexOf('return exact;',$ownSchema,[StringComparison]::Ordinal)+'return exact;'.Length
if($ownStart-lt0-or$ownEnd-le$ownStart){throw 'Exact existing Own wrapper builder missing'}
$ownBuilder=$hostProcess.Substring($ownStart,$ownEnd-$ownStart)
$wrapper=@'
package io.github.mesmerprism.rustyquest.media;import org.json.*;
public final class PipelineOwnWrapper {
 static final class Fence {long generation(){return Long.MAX_VALUE;}}
 static String wrap(JSONObject source)throws Exception{
  String challenge=PipelineWaitMaximumMain.repeated('d',64),epoch=PipelineWaitMaximumMain.repeated('e',32);
  Fence processFence=new Fence();long sampleStartNs=Long.MAX_VALUE,sampleEndNs=Long.MAX_VALUE;
  JSONObject qualification=PipelineWaitMaximumMain.qualification();
  JSONObject armContext=new JSONObject().put("arm_entry_elapsed_ns",Long.MAX_VALUE).put("arm_exit_elapsed_ns",Long.MAX_VALUE);
'@+$ownBuilder+"`n}}"
$wrapperPath=Join-Path $out 'PipelineOwnWrapper.java';[IO.File]::WriteAllText($wrapperPath,$wrapper)
$aggregateStart=$source.IndexOf('JSONObject own = sharedCapture.captureDiagnosticSnapshot();',[StringComparison]::Ordinal)
$aggregateLast='json.put("own_capture_stage_diagnostic", own);'
$aggregateEnd=$source.IndexOf($aggregateLast,$aggregateStart,[StringComparison]::Ordinal)+$aggregateLast.Length
if($aggregateStart-lt0-or$aggregateEnd-le$aggregateStart){throw 'Actual source diagnostic aggregation missing'}
$aggregate=@'
package io.github.mesmerprism.rustyquest.media;import org.json.*;
public final class PipelineSourceAggregation{
 static JSONObject append(PackedStereoCaptureOwner sharedCapture,PipelineWaitObservation drainWaits,
  PipelineWaitObservation encoderWaits,PipelineWaitObservation packetWaits)throws Exception{
  long captureConsumerGeneration=Long.MAX_VALUE,connectionGeneration=Long.MAX_VALUE;JSONObject json=new JSONObject();
'@+$source.Substring($aggregateStart,$aggregateEnd-$aggregateStart)+"`nreturn json.getJSONObject(`"own_capture_stage_diagnostic`");}}"
$aggregatePath=Join-Path $out 'PipelineSourceAggregation.java';[IO.File]::WriteAllText($aggregatePath,$aggregate)
$maximum=Join-Path (Split-Path $test -Parent) 'PipelineWaitMaximumMain.java'
$maximumCp=(Join-Path $out 'host')+[IO.Path]::PathSeparator+(Join-Path $out 'android-typecheck')+[IO.Path]::PathSeparator+$HostJsonJar+[IO.Path]::PathSeparator+$AndroidJar
& $javac --release 8 -Xlint:all -Werror -cp $maximumCp -d (Join-Path $out 'host') $maximum $wrapperPath $aggregatePath *> (Join-Path $out 'maximum-compile.log')
if($LASTEXITCODE){throw 'Actual maximum Own serializer compilation failed'}
& $java -cp $maximumCp io.github.mesmerprism.rustyquest.media.PipelineWaitMaximumMain (Join-Path $out 'maximum-own.json') *> (Join-Path $out 'maximum-test.log')
if($LASTEXITCODE){throw 'Actual maximum Own serializer bounds failed'}
Get-Content (Join-Path $out 'maximum-test.log')
$jsonObject=Join-Path $out 'JSONObject.java';$jsonArray=Join-Path $out 'JSONArray.java'
[IO.File]::WriteAllText($jsonObject,'package org.json; public final class JSONObject { public static Runnable hook; public JSONObject put(String k,Object v){if(hook!=null)hook.run();return this;} public Object remove(String k){return null;} public String getString(String k){return "android_elapsed_realtime_ns";} }')
[IO.File]::WriteAllText($jsonArray,'package org.json; public final class JSONArray { public JSONArray put(Object v){return this;} }')
$monitor=@'
package io.github.mesmerprism.rustyquest.media;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;import org.json.*;
public final class ObserverMonitorMain{
 public static void main(String[] args)throws Exception{
  PipelineWaitObservation observation=new PipelineWaitObservation("monitor","leaf");
  CountDownLatch formatting=new CountDownLatch(1),release=new CountDownLatch(1),progress=new CountDownLatch(1);
  AtomicReference<Throwable> error=new AtomicReference<>();
  JSONObject.hook=()->{if(Thread.holdsLock(observation))throw new AssertionError("JSON under producer monitor");
   formatting.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("JSON timeout");}catch(InterruptedException e){throw new AssertionError(e);}};
  Thread reader=new Thread(()->{try{if(observation.snapshot()==null)throw new AssertionError("observer serialization failed");}catch(Throwable e){error.set(e);}});
  reader.start();
  try{
   if(!formatting.await(5,TimeUnit.SECONDS))throw new AssertionError("actual formatter not entered");
   Thread producer=new Thread(()->{long token=observation.begin(0,73,84,95,1);observation.end(0,token,true);progress.countDown();});producer.start();
   if(!progress.await(1,TimeUnit.SECONDS))throw new AssertionError("blocked JSON stalled actual recorder producer");producer.join();
  }finally{release.countDown();reader.join(5000);}
  if(reader.isAlive()||error.get()!=null)throw new AssertionError("snapshot thread failed",error.get());
  System.out.println("PASS actual production observer formats JSON outside monitor while concurrent producer progresses");
 }
}
'@
$monitorPath=Join-Path $out 'ObserverMonitorMain.java';[IO.File]::WriteAllText($monitorPath,$monitor)
& $javac --release 8 -Xlint:all -Werror -cp (Join-Path $out 'host') -d (Join-Path $out 'monitor') $jsonObject $jsonArray $monitorPath (Join-Path $package 'PipelineWaitObservation.java') *> (Join-Path $out 'monitor-compile.log')
if($LASTEXITCODE){throw 'Production observer monitor control compilation failed'}
& $java -cp ((Join-Path $out 'monitor')+[IO.Path]::PathSeparator+(Join-Path $out 'host')) io.github.mesmerprism.rustyquest.media.ObserverMonitorMain *> (Join-Path $out 'monitor-test.log')
if($LASTEXITCODE){throw 'Production observer JSON monitor control failed'}
Get-Content (Join-Path $out 'monitor-test.log')
$existingNames=@('MediaProtocolConformanceMain','MediaPrimitiveAdversarialMain')
$existingPaths=@($existingNames|ForEach-Object{Join-Path (Split-Path $test -Parent) ($_+'.java')})
$logLeaf=Join-Path $out 'Log.java'
[IO.File]::WriteAllText($logLeaf,'package android.util; public final class Log { public static int w(String tag,String message){return 0;} }')
& $javac --release 8 -Xlint:all -Werror -cp $HostJsonJar -sourcepath $main -d (Join-Path $out 'pure-existing') @existingPaths $logLeaf *> (Join-Path $out 'pure-existing-compile.log')
if($LASTEXITCODE){throw 'Existing media test compilation failed (only Log leaf modeled)'}
foreach($entry in $existingNames){
    & $java -cp ((Join-Path $out 'pure-existing')+[IO.Path]::PathSeparator+$HostJsonJar) ('io.github.mesmerprism.rustyquest.media.'+$entry) *> (Join-Path $out ($entry+'.log'))
    if($LASTEXITCODE){throw "Existing pure media regression failed: $entry"}
    Get-Content (Join-Path $out ($entry+'.log'))
}
$paths=@($test,$maximum,$PSCommandPath,$HostJsonJar,$AndroidJar,$javac,$java)+$existingPaths+@(Get-ChildItem -LiteralPath $package -Filter '*.java'|Where-Object{$_.Name-in@('PipelineWaitObservation.java','BoundedPacketPump.java','PackedStereoCaptureOwner.java','PackedStereoGlCompositor.java','PackedStereoEncoderWorker.java','PackedStereoMediaSourceRuntime.java')}|ForEach-Object{$_.FullName})
$pins=@($paths|ForEach-Object{@{path=$_;size_bytes=(Get-Item -LiteralPath $_).Length;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}})
@{status='passed';source_and_tool_pins=$pins;limits='Exact production Java methods and serializers; Android/platform leaves modeled. No APK, devices, camera, codec, GPU, LAN or performance qualification.';apk_build=$false;device_calls=0;android_execution=$false}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $out 'RESULT.json') -NoNewline
