"""Actual retained completion/scope guards; modeled platform/native barriers."""
import argparse
import contextlib
import pathlib
import subprocess
import tempfile

root=pathlib.Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root',type=pathlib.Path,default=root)
parser.add_argument('--output',type=pathlib.Path)
parser.add_argument('--json-jar',type=pathlib.Path)
args=parser.parse_args()
root=args.root.resolve()
if args.output:args.output.mkdir(parents=True,exist_ok=False)
base=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
text=(base/'EmbeddedDuplexProcessHost.java').read_text()
def block(text,start):
    b=text.index(start);i=text.index('{',b)+1;depth=1
    while depth:
        depth+=(text[i]=='{')-(text[i]=='}');i+=1
    return text[b:i]
actual='\n'.join(block(text,s) for s in ['static final class RetainedWholeClose','CompletableFuture<String> peerLifecycle(','private CompletableFuture<String> peerLifecycleEntered(','private void retainWholeClose','private void releaseWholeCloseObserver','private void continueWholeClose','private boolean joinWholeCloseObserver','private String inCurrentWholeNativeClose(','static void requireCurrentOriginalStopActor('])
originals=(base/'EmbeddedDuplexOriginals.java').read_text()
scope='\n'.join(block(originals,s)for s in ['static Record capture()','static AutoCloseable enter(Record next)'])
prefix=r'''
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import org.json.*;
public class WholeCloseHarness {
 static WholeCloseHarness instance;
 static int checks,nativeChecks;static boolean ready,failNative;static void check(boolean b){if(!b)throw new AssertionError("control "+checks);checks++;}
 enum Phase {READY,CLOSING,NEW} final AtomicReference<Phase> phase=new AtomicReference<>(Phase.READY);
 static class Context {String getPackageName(){return "modeled.app";}}
 final Context applicationContext=new Context();final QueueExecutor commands=new QueueExecutor();
 String nativeAppRecordSha256="record";EmbeddedDuplexOriginals.Record activeWholeNativeOriginal;
 RetainedWholeClose retainedWholeClose;String terminalWholeReceipt,terminalWholeChallenge,retainedWholeNativeReceipt;
 String runtimeConfigSha256="cfg",ownFeatureLockSha256="feature";long nativeExecutorGeneration=7,attachmentGeneration=2;
 EmbeddedDuplexResources resources=new EmbeddedDuplexResources();EmbeddedDuplexPlatform platform=new EmbeddedDuplexPlatform();
 final Fence processFence=new Fence();final Digest installedApkDigest=new Digest();
 enum EmbeddedDuplexPeerAction {WHOLE_APP_CLOSE("whole_app_close"),STATUS("peer_status"),STOP("peer_stop"),REVOKE("peer_revoke"); final String action;EmbeddedDuplexPeerAction(String a){action=a;}}
 final Object attachmentGate=new Object();boolean localFixture,displayDetaching,closeInFlight;RetainedNoMediaClose noMediaClose;
 static class RetainedNoMediaClose{JSONObject proof;CompletableFuture<Void> completion=new CompletableFuture<>();}
 static class EmbeddedDuplexOriginals {
  static final ThreadLocal<Record> CURRENT=new ThreadLocal<>();static Record actual;
  static class Record {Object retainedCleanupOwner;long actualAppGeneration=3;JSONObject row=new JSONObject()
   .put("operation","whole_app_close").put("actor_binding",new JSONObject().put("package_id","modeled.app")
    .put("native_generation",7).put("app_record_sha256","record"));String id(){return "modeled-original";}}
  __ACTUAL_ORIGINAL_SCOPE__
 }
 static class EmbeddedDuplexOperatorResponses {static String receipt(String challenge,String receipt){return receipt;}}
 CompletableFuture<String> appOriginal(String action,String challenge,java.util.function.Supplier<CompletableFuture<String>> effect,java.util.function.Function<String,CompletableFuture<String>> result){throw new AssertionError("original ingress not exercised in owner continuation model");}
 <T>CompletableFuture<T> submit(Callable<T> c){try{return CompletableFuture.completedFuture(c.call());}catch(Exception e){return failed(e);}}
 CompletableFuture<Void> closeNoMediaAndDetach(long attachment){throw new AssertionError("no-media branch not modeled");}
 void requestDesiredStopOutsideCommandLane(boolean whole){check(whole);}
 static <T> CompletableFuture<T> failed(Throwable e){CompletableFuture<T> f=new CompletableFuture<>();f.completeExceptionally(e);return f;}
 CompletableFuture<String> concurrentQualificationOnLane(boolean a,String c,boolean p,long[] q,EmbeddedDuplexPeerAction action){if(retainedWholeClose==null)retainWholeClose(c,"epoch","apk","originalPeerTerminal");return CompletableFuture.completedFuture("initial");}
 void requireFreshProcess(){if(processFence.stale)throw new IllegalStateException("stale");}
 void finishWholeAppProof(JSONObject p,String config,boolean noMedia){check(!noMedia);if(!ready)return;if(!joinWholeCloseObserver(retainedWholeClose,OwnStereoCaptureRuntime.currentForApplication()))return;p.put("whole_app_physical_cleanup","terminal");phase.set(Phase.NEW);runtimeConfigSha256=null;resources=null;platform=null;nativeExecutorGeneration=0;attachmentGeneration=0;}
 static class QueueExecutor implements Executor {
  boolean reject;EmbeddedDuplexOriginals.Record ambient;List<Runnable> q=new ArrayList<>();
  public void execute(Runnable r){if(reject)throw new RejectedExecutionException("modeled actual lane rejection");q.add(r);}
  void drain(){
   ExecutorService worker=Executors.newSingleThreadExecutor();
   try {worker.submit(()->{
    check(EmbeddedDuplexOriginals.capture()==null);
    try(AutoCloseable ignored=EmbeddedDuplexOriginals.enter(ambient)){
     while(!q.isEmpty()){
      try {q.remove(0).run();}
      finally {check(EmbeddedDuplexOriginals.capture()==ambient);check(instance.activeWholeNativeOriginal==null);}
     }
    }catch(Exception failure){throw new RuntimeException(failure);}
    check(EmbeddedDuplexOriginals.capture()==null);
   }).get(5,TimeUnit.SECONDS);}
   catch(Exception failure){throw new RuntimeException(failure);}
   finally {worker.shutdownNow();}
  }
 }
 static class EmbeddedDuplexResources {}static class EmbeddedDuplexPlatform {}
 static class Fence {boolean stale;String epoch="epoch";long gen=3;long generation(){return gen;}String epochId(){return epoch;}void requireLive(long g){if(stale||g!=gen)throw new IllegalStateException("stale");}}
 static class Digest {String value="apk";String read(Object c){return value;}}
 static class OwnStereoCaptureRuntime {static OwnStereoCaptureRuntime instance=new OwnStereoCaptureRuntime();boolean terminal,failUnregister;CompletableFuture<Void> unregisterGate;Runnable callback;int releases;static OwnStereoCaptureRuntime currentForApplication(){return instance;}String physicalCleanupState(){return terminal?"terminal":"pending";}static class CleanupObserverRegistration implements AutoCloseable {final OwnStereoCaptureRuntime owner;final Runnable callback;CleanupObserverRegistration(OwnStereoCaptureRuntime o,Runnable r){owner=o;callback=r;}public void close(){if(owner.callback==callback){owner.callback=null;owner.releases++;}}}
 CleanupObserverRegistration observePhysicalCleanup(Runnable r){if(callback!=null)throw new IllegalStateException("occupied");callback=r;return new CleanupObserverRegistration(this,r);}
 CompletableFuture<Void> unregisterPhysicalCleanup(CleanupObserverRegistration r){if(failUnregister)throw new IllegalStateException("modeled actual unregister failure");r.close();return unregisterGate==null?CompletableFuture.completedFuture(null):unregisterGate;}void tick(){if(terminal&&callback!=null)callback.run();}}
 static class ConcurrentStereoQualification {static boolean currentArmActor(String e,String c,String s,long g){return true;}static boolean stale;static String status(String c,String e,String r,String f,String a){if(stale)throw new IllegalStateException("arm changed");return "observed";}static String lifecycle(String act,String c,String e,String r,String f,String a,String receipt){check(act.equals("whole_app_close"));return receipt;}}
 static class EmbeddedDuplexNative {
  static void beginOperationDiagnostics(String operation,String original){}static void endOperationDiagnostics(){}
  static String noMediaRetirementStatus(){return new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_retirement_status.v1").put("classification","media_owned_or_uncertain").toString();}
  static String completeRetainedWholeClose(String c,long x,long a){
   // Modeled Native retirement leaf verifies the actual Java scope at its ABI boundary.
   check(EmbeddedDuplexOriginals.capture()==instance.retainedWholeClose.original);
   check(instance.activeWholeNativeOriginal==instance.retainedWholeClose.original);
   nativeChecks++;if(failNative)throw new IllegalStateException("native failure");if(!ready)return null;
   return new JSONObject().put("native_host_physical_cleanup","terminal").put("whole_app_physical_cleanup","pending").toString();}
 }
 static WholeCloseHarness fresh(){OwnStereoCaptureRuntime.instance=new OwnStereoCaptureRuntime();ready=false;failNative=false;ConcurrentStereoQualification.stale=false;
  EmbeddedDuplexOriginals.actual=new EmbeddedDuplexOriginals.Record();EmbeddedDuplexOriginals.CURRENT.set(EmbeddedDuplexOriginals.actual);
  instance=new WholeCloseHarness();return instance;}
'''
prefix=prefix.replace('__ACTUAL_ORIGINAL_SCOPE__',scope)
prefix=prefix.replace('WholeCloseHarness','EmbeddedDuplexProcessHost')
suffix=r'''
 public static void main(String[] args)throws Exception {
  WholeCloseHarness h=fresh();h.retainWholeClose("challenge","epoch","apk","originalPeerTerminal");RetainedWholeClose held=h.retainedWholeClose;check(h.phase.get()==Phase.CLOSING&&!held.completion.isDone());
  OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.instance;own.tick();check(h.commands.q.isEmpty());
  own.terminal=true;for(int i=0;i<100;i++)own.tick();check(h.commands.q.size()==1);h.commands.drain();
  if(held.completion.isCompletedExceptionally())held.completion.join();
  check(!held.completion.isDone()&&h.resources==held.resources&&h.phase.get()==Phase.CLOSING);
  ready=true;own.tick();h.commands.drain();check(held.completion.isDone()&&!held.completion.isCompletedExceptionally()&&h.phase.get()==Phase.NEW);check(own.releases==1&&own.callback==null);int before=nativeChecks;h.continueWholeClose(held);check(nativeChecks==before);
  // Renderer may complete before Own. It cannot invoke native retirement early.
  h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;ready=true;before=nativeChecks;OwnStereoCaptureRuntime.instance.tick();check(nativeChecks==before&&!held.completion.isDone());OwnStereoCaptureRuntime.instance.terminal=true;OwnStereoCaptureRuntime.instance.tick();h.commands.drain();check(held.completion.isDone());
  for(int k=0;k<12;k++){
   h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;own=OwnStereoCaptureRuntime.instance;own.terminal=true;ready=true;
   switch(k){case 0:h.processFence.gen++;break;case 1:h.processFence.epoch="other";break;case 2:h.runtimeConfigSha256="other";break;case 3:h.ownFeatureLockSha256="other";break;case 4:h.nativeExecutorGeneration++;break;case 5:h.attachmentGeneration++;break;case 6:h.resources=new EmbeddedDuplexResources();break;case 7:h.platform=new EmbeddedDuplexPlatform();break;case 8:h.installedApkDigest.value="other";break;case 9:ConcurrentStereoQualification.stale=true;break;case 10:h.phase.set(Phase.READY);break;case 11:h.retainedWholeClose=null;break;}
   before=nativeChecks;own.tick();h.commands.drain();check(held.completion.isCompletedExceptionally()&&nativeChecks==before&&(k==10||h.phase.get()==Phase.CLOSING));check(own.callback==null);
  }
  h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;own=OwnStereoCaptureRuntime.instance;own.terminal=true;ready=true;failNative=true;Object resource=h.resources;own.tick();h.commands.drain();check(held.completion.isCompletedExceptionally()&&h.resources==resource&&h.phase.get()==Phase.CLOSING);check(held.initialReceipt.equals("original"));
  // Pending drain retains the original until the exact real marker resolves.
  h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;own=OwnStereoCaptureRuntime.instance;own.terminal=true;ready=true;own.unregisterGate=new CompletableFuture<>();own.tick();h.commands.drain();check(!held.completion.isDone()&&h.resources==held.resources);own.unregisterGate.complete(null);h.commands.drain();check(held.completion.isDone()&&!held.completion.isCompletedExceptionally());
  h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;own=OwnStereoCaptureRuntime.instance;own.terminal=true;ready=true;own.failUnregister=true;own.tick();h.commands.drain();check(held.completion.isCompletedExceptionally()&&h.resources==held.resources&&h.phase.get()==Phase.CLOSING);
  h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;own=OwnStereoCaptureRuntime.instance;own.terminal=true;ready=true;own.unregisterGate=new CompletableFuture<>();own.tick();h.commands.drain();own.unregisterGate.completeExceptionally(new IllegalStateException("actual modeled callback drain failure"));h.commands.drain();check(held.completion.isCompletedExceptionally()&&h.resources==held.resources&&h.phase.get()==Phase.CLOSING);
  h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;own=OwnStereoCaptureRuntime.instance;own.terminal=true;ready=true;own.unregisterGate=new CompletableFuture<>();own.tick();h.commands.drain();h.commands.reject=true;own.unregisterGate.complete(null);check(held.completion.isCompletedExceptionally()&&h.resources==held.resources&&h.phase.get()==Phase.CLOSING);
  h=fresh();OwnStereoCaptureRuntime.instance=null;h.retainWholeClose("challenge","epoch","apk","original");check(h.retainedWholeClose==null&&h.phase.get()==Phase.READY);
  h=fresh();CompletableFuture<String> caller=h.peerLifecycle(EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE,"challenge");held=h.retainedWholeClose;check(!caller.isDone());try{caller.get(1,TimeUnit.MILLISECONDS);throw new AssertionError("wait did not remain pending");}catch(TimeoutException expected){}check(!held.completion.isDone());caller.cancel(false);check(!held.completion.isCancelled());ready=true;OwnStereoCaptureRuntime.instance.terminal=true;OwnStereoCaptureRuntime.instance.tick();h.commands.drain();check(held.completion.isDone()&&!held.completion.isCompletedExceptionally());
  h=fresh();OwnStereoCaptureRuntime.instance.callback=()->{};try{h.retainWholeClose("challenge","epoch","apk","original");throw new AssertionError("occupied observer admitted");}catch(IllegalStateException expected){}check(h.retainedWholeClose.completion.isCompletedExceptionally()&&h.phase.get()==Phase.CLOSING);
  // Both the first observer hop and the unregister hop must restore a different
  // ambient Original on the worker, while carrying the exact retained Record.
  h=fresh();h.commands.ambient=new EmbeddedDuplexOriginals.Record();
  h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;
  ready=true;OwnStereoCaptureRuntime.instance.terminal=true;OwnStereoCaptureRuntime.instance.tick();
  h.commands.drain();check(held.completion.isDone()&&!held.completion.isCompletedExceptionally());
  check(EmbeddedDuplexOriginals.capture()==held.original&&h.activeWholeNativeOriginal==null);
  // Scope propagation cannot turn a foreign/missing actor into valid custody.
  for(int k=0;k<6;k++){
   h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;
   EmbeddedDuplexOriginals.Record record=held.original;
   switch(k){case 0:record.row.put("operation","peer_stop");break;
    case 1:record.actualAppGeneration=4;break;
    case 2:record.row.getJSONObject("actor_binding").put("package_id","foreign.app");break;
    case 3:record.row.getJSONObject("actor_binding").put("native_generation",8);break;
    case 4:record.row.getJSONObject("actor_binding").put("app_record_sha256","foreign");break;
    case 5:held.original=null;break;}
   before=nativeChecks;ready=true;OwnStereoCaptureRuntime.instance.terminal=true;
   OwnStereoCaptureRuntime.instance.tick();h.commands.drain();
   check(held.completion.isCompletedExceptionally()&&nativeChecks==before&&h.activeWholeNativeOriginal==null);
  }
  // A failed real retained Future remains failed: duplicate wake/read cannot
  // re-enter Native work, and all context is restored after the exception.
  h=fresh();h.retainWholeClose("challenge","epoch","apk","original");held=h.retainedWholeClose;
  ready=true;failNative=true;own=OwnStereoCaptureRuntime.instance;own.terminal=true;
  own.tick();h.commands.drain();before=nativeChecks;
  for(int i=0;i<20;i++)own.tick();h.commands.drain();
  check(h.peerLifecycle(EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE,"challenge")==held.completion);
  h.continueWholeClose(held);check(held.completion.isCompletedExceptionally()&&nativeChecks==before);
  check(EmbeddedDuplexOriginals.capture()==held.original&&h.activeWholeNativeOriginal==null);
  // The sole-current-Native guard itself still rejects overlapping entry.
  h=fresh();h.activeWholeNativeOriginal=new EmbeddedDuplexOriginals.Record();before=nativeChecks;
  try{h.inCurrentWholeNativeClose(()->{nativeChecks++;return "invalid";});throw new AssertionError("overlap admitted");}
  catch(IllegalStateException expected){check(nativeChecks==before);}
  System.out.println(checks+" actual Java retained completion controls PASS (modeled boundaries)");
 }
}
'''
jar=args.json_jar or next((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('json-*.jar'))
suffix=suffix.replace('WholeCloseHarness','EmbeddedDuplexProcessHost')
with contextlib.nullcontext(str(args.output))if args.output else tempfile.TemporaryDirectory(prefix='whole-close-host-') as out:
    p=pathlib.Path(out);java=p/'EmbeddedDuplexProcessHost.java';java.write_text(prefix+actual+suffix)
    subprocess.run(['javac','--release','17','-cp',str(jar),'-d',out,str(java)],check=True)
    subprocess.run(['java','-cp',out+';'+str(jar),'EmbeddedDuplexProcessHost'],check=True)
    native=(root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/peer_lifecycle.rs').read_text()
    fn=block(native,'fn require_retained_whole_close_binding(')
    rust=p/'binding.rs';rust.write_text(fn+'''\nfn main(){assert!(require_retained_whole_close_binding("c","c",7,3,7,3).is_ok());
    for (expected,executor,app,actual_executor,actual_app) in [("x",7,3,7,3),("c",0,3,7,3),("c",7,0,7,3),("c",-1,3,7,3),("c",7,-1,7,3),("c",7,3,8,3),("c",7,3,7,4)] {assert!(require_retained_whole_close_binding(expected,"c",executor,app,actual_executor,actual_app).is_err());} println!("8 actual Rust incarnation controls PASS");}''')
    exe=p/'binding.exe';subprocess.run(['rustc','--edition=2021',str(rust),'-o',str(exe)],check=True);subprocess.run([str(exe)],check=True)
    continuation=block(native,'fn complete_retained_whole_close(')
    assert 'state.whole_close_requested' in continuation and 'state.client.is_none()' in continuation and 'state.last_failure.is_some()' in continuation
    assert 'actual_java_cleanup(env)?' in continuation and 'close_host(value, host, checked)' in continuation
    assert 'stop(' not in continuation and 'operate(' not in continuation and 'apply_media(' not in continuation
    def has_whole_close_request(source):
        # rustfmt/EOL changes may alter only whitespace. The actual operation
        # word, exact field, value and closed one-assignment body stay strict.
        return 'ifword==6{state.whole_close_requested=true;}' in ''.join(source.split())
    assert has_whole_close_request(native)
    assert has_whole_close_request('if word == 6 { state.whole_close_requested = true; }')
    assert has_whole_close_request('if word == 6 {\n    state.whole_close_requested = true;\n}')
    assert not has_whole_close_request('if word == 5 { state.whole_close_requested = true; }')
    assert not has_whole_close_request('if word == 6 { state.peer_close_requested = true; }')
    assert not has_whole_close_request('if word == 6 { state.whole_close_requested = false; }')
    assert text.index('if (peerAction == EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE && retainedWholeClose != null)') < text.index('own.requestStopOwn();',text.index('private CompletableFuture<String> concurrentQualificationOnLane'))
    kotlin=(base/'OwnStereoCaptureRuntime.kt').read_text();assert 'if (phase == Phase.Idle) cleanupObserver?.invokeObserved()' in kotlin and 'if (cleanupObserver === registration) cleanupObserver = null' in kotlin
    assert '&& new JSONObject(nativeReceipt).isNull("last_failure")' in text
print('Native cleanup-only/selected-operation and original-effect suppression integration source gates PASS')


# Real callback, abort/context and callback lease implementations; only SDK/OS
# durable Original-registry and optional diagnostic-sink leaves are modeled.
platform=(base/'EmbeddedDuplexPlatform.java').read_text()
prepared=(base/'EmbeddedDuplexPreparedStart.java').read_text()
fence=(base/'EmbeddedDuplexProcessFence.java').read_text()
roots=(base/'EmbeddedDuplexPeerRoots.java').read_text()
fixture=(root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/EmbeddedDuplexPreparedAbortCallbackHostTest.java').read_text()
fragments={
    'ACTUAL_ENTER_CALLBACK':block(platform,'private EmbeddedDuplexProcessFence.CallbackLease enterProcessCallback('),
    'ACTUAL_REQUIRE_CALLBACK':block(platform,'private void requireProcessCallback('),
    'ACTUAL_ABORT_CALLBACK':block(platform,'public String abortPreparedPeerStartResources('),
    'ACTUAL_OWNED_ABORT':block(prepared,'synchronized JSONObject abort('),
    'ACTUAL_ABORT_CONTEXT':block(text,'static void requirePreparedAbortContext('),
    'ACTUAL_CALLBACK_FACTORY':block(fence,'CallbackGuard callbacks('),
    'ACTUAL_CALLBACK_GUARD':block(fence,'static final class CallbackGuard'),
    'ACTUAL_CALLBACK_LEASE':block(fence,'static final class CallbackLease'),
    'ACTUAL_CLOSED_KEYS':block(roots,'static void keys('),
    'ACTUAL_ACTOR_IDENTITY':block(roots,'static void identity('),
}
for name,actual in fragments.items():
    marker='/* '+name+' */';assert fixture.count(marker)==1;fixture=fixture.replace(marker,actual)
fixture+='''\nfinal class EmbeddedDuplexOperationDiagnostics {
    static void observe(String phase,String component,String boundary,Throwable failure) {}
}\n'''
with tempfile.TemporaryDirectory(prefix='prepared-abort-callback-host-') as directory:
    target=pathlib.Path(directory);java=target/'EmbeddedDuplexPreparedAbortCallbackHostTest.java'
    java.write_text(fixture,encoding='utf-8',newline='\n')
    subprocess.run(['javac','--release','17','-cp',str(jar),'-d',directory,str(java),str(base/'EmbeddedDuplexCanonicalJson.java'),str(base/'EmbeddedDuplexPreparedRenderer.java')],check=True)
    subprocess.run(['java','-cp',directory+';'+str(jar),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPreparedAbortCallbackHostTest'],check=True)
bridge=(root/'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/java_bridge.rs').read_text()
assert 'self.fresh_closure_callback("abortPreparedPeerStartResources", &body)?' in bridge
no_media=block(text,'private void continueNoMediaClose(')
assert no_media.index('EmbeddedDuplexNative.retirePreparedPeerStartMetadata(held.config)') < no_media.index('held.platform.retireProcessCallbacksAsync()')
assert 'requireCurrentHubAttachment' not in fragments['ACTUAL_ABORT_CALLBACK']
assert 'runtimeCommand' not in fragments['ACTUAL_ABORT_CALLBACK']
print('Fixed JNI abort callback String ABI, cleanup-before-callback-retirement ordering and actual owned terminal readback source join PASS; no JNI/SDK physical credit')
