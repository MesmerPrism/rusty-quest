"""Actual ordinary Close selection body; native/Android owners explicitly modeled."""
import argparse, pathlib, subprocess, os

def body(text,marker):
    start=text.index(marker);pos=text.index('{',start);depth=1;end=pos+1
    while depth:
        if text[end]=='{':depth+=1
        elif text[end]=='}':depth-=1
        end+=1
    return text[start:end]

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
    root=a.root.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    base=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    host=(base/'EmbeddedDuplexProcessHost.java').read_text()
    method=body(host,'    CompletableFuture<String> closeProduct(')
    jars=sorted((pathlib.Path(os.environ['USERPROFILE'])/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('json-*.jar'));assert jars
    file=out/'OrdinaryProductCloseHost.java';file.write_text(PREFIX+method+TESTS)
    for name,args in [('javac',['javac','--release','17','-cp',str(jars[-1]),'-d',str(out),str(base/'EmbeddedDuplexPeerAction.java'),str(file)]),
                      ('java',['java','-cp',str(out)+os.pathsep+str(jars[-1]),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.OrdinaryProductCloseHost'])]:
        result=subprocess.run(args,capture_output=True);(out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
        if result.returncode:raise RuntimeError(name+' failed: '+result.stderr.decode())
        print(result.stdout.decode())
    start=host.index('                requireFreshProcess();',host.index('private CompletableFuture<String> initializeOnCommandLane('))
    end=host.index('                String recordSha =',start)
    bootstrap=host[start:end]
    start=host.index('            processFence.beforeNativeAbsenceRetirement(',host.index('CompletableFuture<String> closeNoMediaAndDetach('))
    end=host.index('            final RetainedNoMediaClose held=',start)
    teardown=host[start:end]
    seam=out/'BootstrapFenceHost.java';seam.write_text(SEAM.replace('BOOTSTRAP_BODY',bootstrap).replace('TEARDOWN_BODY',teardown))
    sources=['EmbeddedDuplexProcessFence','EmbeddedDuplexProcessIncarnation','EmbeddedDuplexAbandonment','EmbeddedDuplexAbandonmentRequest','EmbeddedDuplexHistoricalAbandonment','EmbeddedDuplexKnownBirthAbandonment']
    for name,args in [('seam-javac',['javac','--release','17','-cp',str(jars[-1]),'-d',str(out),*[str(base/(s+'.java')) for s in sources],str(seam)]),
                      ('seam-java',['java','-cp',str(out)+os.pathsep+str(jars[-1]),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.BootstrapFenceHost'])]:
        result=subprocess.run(args,capture_output=True);(out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr)
        if result.returncode:raise RuntimeError(name+' failed: '+result.stderr.decode())
        print(result.stdout.decode())

PREFIX='''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.util.concurrent.*;import org.json.JSONObject;
public class OrdinaryProductCloseHost {
 final Object attachmentGate=new Object();long attachmentGeneration=7;boolean displayDetaching,localFixture;
 String runtimeConfigSha256="config";long nativeExecutorGeneration=9;
 RetainedWholeClose retainedWholeClose;RetainedNoMediaClose noMediaClose;
 Fence processFence=new Fence();int desiredStops,dispatches;String actualChallenge;EmbeddedDuplexPeerAction actualAction;
 CompletableFuture<String> owner=new CompletableFuture<>();
 static class RetainedWholeClose {String challenge="retained";}
 static class RetainedNoMediaClose {}
 static class Fence {String epochId(){return "epoch";}}
 static class ConcurrentStereoQualification {static boolean armed;
  static JSONObject retirementArmObservation(String e,String c,long g){return armed?new JSONObject().put("binding","established").put("challenge","actual-arm"):new JSONObject().put("binding","not_established");}}
 void requestDesiredStopOutsideCommandLane(boolean whole){if(!whole)throw new AssertionError();desiredStops++;}
 String internalChallenge(){return "new-no-media-challenge";}
 interface Work<T>{T run()throws Exception;}
 <T>CompletableFuture<T> submit(Work<T> work){try{return CompletableFuture.completedFuture(work.run());}catch(Exception failure){return failed(failure);}}
 static <T>CompletableFuture<T> failed(Throwable f){CompletableFuture<T> r=new CompletableFuture<>();r.completeExceptionally(f);return r;}
 CompletableFuture<String> peerLifecycle(EmbeddedDuplexPeerAction action,String challenge){dispatches++;actualAction=action;actualChallenge=challenge;return owner;}
'''
TESTS='''
 static void check(boolean value){if(!value)throw new AssertionError("ordinary Close selection");}
 static String terminal(boolean envelope){JSONObject p=new JSONObject().put("action","whole_app_close");
  for(String key:new String[]{"whole_app_physical_cleanup","app_process_fence_cleanup","java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup"})p.put(key,"terminal");
  return envelope?new JSONObject().put("peer_lifecycle",p).toString():p.toString();}
 public static void main(String[] args){
  for(boolean armed:new boolean[]{false,true}){
   ConcurrentStereoQualification.armed=armed;OrdinaryProductCloseHost h=new OrdinaryProductCloseHost();
   CompletableFuture<String> close=h.closeProduct(7);check(!close.isDone());check(h.desiredStops==1&&h.dispatches==1);
   check(h.actualAction==EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE);
   check((armed?"actual-arm":"new-no-media-challenge").equals(h.actualChallenge));
   String proof=terminal(armed);h.owner.complete(proof);check(proof.equals(close.join()));
   h.attachmentGeneration=0;check(h.closeProduct(7).isCompletedExceptionally());
  }
  OrdinaryProductCloseHost held=new OrdinaryProductCloseHost();held.retainedWholeClose=new RetainedWholeClose();
  held.closeProduct(7);check("retained".equals(held.actualChallenge));
  for(int denial=0;denial<4;denial++){
   OrdinaryProductCloseHost h=new OrdinaryProductCloseHost();if(denial==0)h.attachmentGeneration=8;if(denial==1)h.displayDetaching=true;if(denial==2)h.localFixture=true;
   check(h.closeProduct(denial==3?0:7).isCompletedExceptionally());check(h.desiredStops==0&&h.dispatches==0);
  }
  OrdinaryProductCloseHost fail=new OrdinaryProductCloseHost();CompletableFuture<String> closing=fail.closeProduct(7);
  fail.owner.completeExceptionally(new IllegalStateException("actual cleanup pending"));check(closing.isCompletedExceptionally());check(fail.attachmentGeneration==7);
  for(String payload:new String[]{"{}", "{\\"status\\":\\"error\\"}","{\\"action\\":\\"whole_app_close\\",\\"whole_app_physical_cleanup\\":\\"pending\\"}",
      "{\\"peer_lifecycle\\":{\\"action\\":\\"whole_app_close\\",\\"whole_app_physical_cleanup\\":\\"pending\\",\\"last_failure\\":\\"stop_failed\\"}}"}){
   OrdinaryProductCloseHost h=new OrdinaryProductCloseHost();int[] activityCleanup={0};CompletableFuture<String> close=h.closeProduct(7);
   close.whenComplete((receipt,failure)->{if(failure==null){h.attachmentGeneration=0;activityCleanup[0]++;}});
   h.owner.complete(payload);check(close.isCompletedExceptionally());check(h.attachmentGeneration==7&&activityCleanup[0]==0);
  }
  for(String key:new String[]{"whole_app_physical_cleanup","app_process_fence_cleanup","java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup"}){
   OrdinaryProductCloseHost h=new OrdinaryProductCloseHost();CompletableFuture<String> close=h.closeProduct(7);
   h.owner.complete(new JSONObject(terminal(false)).put(key,"pending").toString());check(close.isCompletedExceptionally());check(h.attachmentGeneration==7);
  }
  for(boolean envelope:new boolean[]{false,true}){
   OrdinaryProductCloseHost h=new OrdinaryProductCloseHost();CompletableFuture<String> close=h.closeProduct(7);
   JSONObject error=new JSONObject(terminal(false)).put("status","error");
   h.owner.complete(envelope?new JSONObject().put("peer_lifecycle",error).toString():error.toString());check(close.isCompletedExceptionally());check(h.attachmentGeneration==7);
  }
  System.out.println("Ordinary Close production selection PASS; physical/native owner completion modeled, never inferred.");
 }
}
'''
SEAM='''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;import java.nio.file.*;
public class BootstrapFenceHost {
 static Path root;static int claims;
 Object applicationContext=new Object(),nativeConfigurationContext;
 EmbeddedDuplexProcessFence processFence;
 void requireFreshProcess(){processFence.requireFresh();}
 String checkpointSnapshot(){return null;}String evidenceSnapshot(){return null;}
 static class EmbeddedDuplexRecoveryCoordinator {EmbeddedDuplexRecoveryCoordinator(Object c){}void requireFreshBootstrap(){}}
 static class EmbeddedDuplexNative {
  static String claimNativeProcessFence(EmbeddedDuplexProcessFence fence)throws Exception{
   claims++;check(fence.effectsPending());check(fence.nativeAdmissionRecord().contains("\\npending\\n"));
   check(Files.isRegularFile(root.resolve("process-incarnation.v1.json")));
   throw new IllegalStateException("modeled pre-native-claim failure");
  }
 }
 void bootstrap(String runtimeCopy,String startupCopy)throws Exception{BOOTSTRAP_BODY}
 void interruptedTeardown()throws Exception{TEARDOWN_BODY}
 static void check(boolean value){if(!value)throw new AssertionError("production bootstrap write-ahead seam");}
 public static void main(String[] args)throws Exception{
  EmbeddedDuplexProcessIncarnation.Identity old=new EmbeddedDuplexProcessIncarnation.Identity("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",101,10001,100);
  EmbeddedDuplexProcessIncarnation.Identity next=new EmbeddedDuplexProcessIncarnation.Identity("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",202,10001,200);
  for(boolean teardown:new boolean[]{false,true}){
   root=Files.createTempDirectory("bootstrap-incarnation-seam-");BootstrapFenceHost h=new BootstrapFenceHost();
   try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(root.toFile(),null,null,()->{},old,p->100L)){
    h.processFence=fence;
    if(teardown)h.interruptedTeardown();else{boolean failed=false;try{h.bootstrap("{}","{}");}catch(IllegalStateException expected){failed=true;}check(failed);}
    check(fence.effectsPending());
   }
   try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(root.toFile(),null,null,()->{},next,p->null)){fence.requireFresh();check(!fence.effectsPending());}
  }
  check(claims==1);System.out.println("Actual bootstrap/pre-native failure and interrupted never-started teardown fence seams PASS; OS/native observations modeled.");
 }
}
'''
if __name__=='__main__':main()
