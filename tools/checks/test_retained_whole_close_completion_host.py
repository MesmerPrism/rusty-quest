"""Actual retained completion methods with modeled platform/native barriers."""
import pathlib
import subprocess
import tempfile

root=pathlib.Path(__file__).resolve().parents[2]
base=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
text=(base/'EmbeddedDuplexProcessHost.java').read_text()
def block(text,start):
    b=text.index(start);i=text.index('{',b)+1;depth=1
    while depth:
        depth+=(text[i]=='{')-(text[i]=='}');i+=1
    return text[b:i]
actual='\n'.join(block(text,s) for s in ['static final class RetainedWholeClose','CompletableFuture<String> peerLifecycle(','private CompletableFuture<String> peerLifecycleEntered(','private void retainWholeClose','private void releaseWholeCloseObserver','private void continueWholeClose','private boolean joinWholeCloseObserver'])
prefix=r'''
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import org.json.*;
public class WholeCloseHarness {
 static int checks,nativeChecks;static boolean ready,failNative;static void check(boolean b){if(!b)throw new AssertionError("control "+checks);checks++;}
 enum Phase {READY,CLOSING,NEW} final AtomicReference<Phase> phase=new AtomicReference<>(Phase.READY);
 final Object applicationContext=new Object();final QueueExecutor commands=new QueueExecutor();
 RetainedWholeClose retainedWholeClose;String terminalWholeReceipt,terminalWholeChallenge,retainedWholeNativeReceipt;
 String runtimeConfigSha256="cfg",ownFeatureLockSha256="feature";long nativeExecutorGeneration=7,attachmentGeneration=2;
 EmbeddedDuplexResources resources=new EmbeddedDuplexResources();EmbeddedDuplexPlatform platform=new EmbeddedDuplexPlatform();
 final Fence processFence=new Fence();final Digest installedApkDigest=new Digest();
 enum EmbeddedDuplexPeerAction {WHOLE_APP_CLOSE("whole_app_close"),STATUS("peer_status"),STOP("peer_stop"),REVOKE("peer_revoke"); final String action;EmbeddedDuplexPeerAction(String a){action=a;}}
 final Object attachmentGate=new Object();boolean localFixture,displayDetaching,closeInFlight;RetainedNoMediaClose noMediaClose;
 static class RetainedNoMediaClose{JSONObject proof;CompletableFuture<Void> completion=new CompletableFuture<>();}
 static class EmbeddedDuplexOriginals {static class Record{Object retainedCleanupOwner;}static final Record actual=new Record();static Record capture(){return actual;}static AutoCloseable enter(Record r){check(r==actual);return ()->{};}}
 static class EmbeddedDuplexOperatorResponses {static String receipt(String challenge,String receipt){return receipt;}}
 CompletableFuture<String> appOriginal(String action,String challenge,java.util.function.Supplier<CompletableFuture<String>> effect,java.util.function.Function<String,CompletableFuture<String>> result){throw new AssertionError("original ingress not exercised in owner continuation model");}
 <T>CompletableFuture<T> submit(Callable<T> c){try{return CompletableFuture.completedFuture(c.call());}catch(Exception e){return failed(e);}}
 CompletableFuture<Void> closeNoMediaAndDetach(long attachment){throw new AssertionError("no-media branch not modeled");}
 void requestDesiredStopOutsideCommandLane(boolean whole){check(whole);}
 String inCurrentWholeNativeClose(Callable<String> c)throws Exception{return c.call();}
 static <T> CompletableFuture<T> failed(Throwable e){CompletableFuture<T> f=new CompletableFuture<>();f.completeExceptionally(e);return f;}
 CompletableFuture<String> concurrentQualificationOnLane(boolean a,String c,boolean p,long[] q,EmbeddedDuplexPeerAction action){if(retainedWholeClose==null)retainWholeClose(c,"epoch","apk","originalPeerTerminal");return CompletableFuture.completedFuture("initial");}
 void requireFreshProcess(){if(processFence.stale)throw new IllegalStateException("stale");}
 void finishWholeAppProof(JSONObject p,String config,boolean noMedia){check(!noMedia);if(!ready)return;if(!joinWholeCloseObserver(retainedWholeClose,OwnStereoCaptureRuntime.currentForApplication()))return;p.put("whole_app_physical_cleanup","terminal");phase.set(Phase.NEW);runtimeConfigSha256=null;resources=null;platform=null;nativeExecutorGeneration=0;attachmentGeneration=0;}
 static class QueueExecutor implements Executor {boolean reject;List<Runnable> q=new ArrayList<>();public void execute(Runnable r){if(reject)throw new RejectedExecutionException("modeled actual lane rejection");q.add(r);}void drain(){while(!q.isEmpty())q.remove(0).run();}}
 static class EmbeddedDuplexResources {}static class EmbeddedDuplexPlatform {}
 static class Fence {boolean stale;String epoch="epoch";long gen=3;long generation(){return gen;}String epochId(){return epoch;}void requireLive(long g){if(stale||g!=gen)throw new IllegalStateException("stale");}}
 static class Digest {String value="apk";String read(Object c){return value;}}
 static class OwnStereoCaptureRuntime {static OwnStereoCaptureRuntime instance=new OwnStereoCaptureRuntime();boolean terminal,failUnregister;CompletableFuture<Void> unregisterGate;Runnable callback;int releases;static OwnStereoCaptureRuntime currentForApplication(){return instance;}String physicalCleanupState(){return terminal?"terminal":"pending";}static class CleanupObserverRegistration implements AutoCloseable {final OwnStereoCaptureRuntime owner;final Runnable callback;CleanupObserverRegistration(OwnStereoCaptureRuntime o,Runnable r){owner=o;callback=r;}public void close(){if(owner.callback==callback){owner.callback=null;owner.releases++;}}}
 CleanupObserverRegistration observePhysicalCleanup(Runnable r){if(callback!=null)throw new IllegalStateException("occupied");callback=r;return new CleanupObserverRegistration(this,r);}
 CompletableFuture<Void> unregisterPhysicalCleanup(CleanupObserverRegistration r){if(failUnregister)throw new IllegalStateException("modeled actual unregister failure");r.close();return unregisterGate==null?CompletableFuture.completedFuture(null):unregisterGate;}void tick(){if(terminal&&callback!=null)callback.run();}}
 static class ConcurrentStereoQualification {static boolean currentArmActor(String e,String c,String s,long g){return true;}static boolean stale;static String status(String c,String e,String r,String f,String a){if(stale)throw new IllegalStateException("arm changed");return "observed";}static String lifecycle(String act,String c,String e,String r,String f,String a,String receipt){check(act.equals("whole_app_close"));return receipt;}}
 static class EmbeddedDuplexNative {static String noMediaRetirementStatus(){return new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_retirement_status.v1").put("classification","media_owned_or_uncertain").toString();}static String completeRetainedWholeClose(String c,long x,long a){nativeChecks++;if(failNative)throw new IllegalStateException("native failure");if(!ready)return null;return new JSONObject().put("native_host_physical_cleanup","terminal").put("whole_app_physical_cleanup","pending").toString();}}
 static WholeCloseHarness fresh(){OwnStereoCaptureRuntime.instance=new OwnStereoCaptureRuntime();ready=false;failNative=false;ConcurrentStereoQualification.stale=false;return new WholeCloseHarness();}
'''
suffix=r'''
 public static void main(String[] args)throws Exception {
  WholeCloseHarness h=fresh();h.retainWholeClose("challenge","epoch","apk","originalPeerTerminal");RetainedWholeClose held=h.retainedWholeClose;check(h.phase.get()==Phase.CLOSING&&!held.completion.isDone());
  OwnStereoCaptureRuntime own=OwnStereoCaptureRuntime.instance;own.tick();check(h.commands.q.isEmpty());
  own.terminal=true;for(int i=0;i<100;i++)own.tick();check(h.commands.q.size()==1);h.commands.drain();check(!held.completion.isDone()&&h.resources==held.resources&&h.phase.get()==Phase.CLOSING);
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
  System.out.println(checks+" actual Java retained completion controls PASS (modeled boundaries)");
 }
}
'''
jar=next((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('json-*.jar'))
with tempfile.TemporaryDirectory(prefix='whole-close-host-') as out:
    p=pathlib.Path(out);java=p/'WholeCloseHarness.java';java.write_text(prefix+actual+suffix)
    subprocess.run(['javac','--release','17','-cp',str(jar),'-d',out,str(java)],check=True)
    subprocess.run(['java','-cp',out+';'+str(jar),'WholeCloseHarness'],check=True)
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
