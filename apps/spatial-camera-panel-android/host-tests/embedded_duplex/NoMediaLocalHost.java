package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.function.*;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalRollbackRequestFence;
/** Production Local admission/retained proof bodies; modeled OS, resource and JNI leaves. */
public class NoMediaLocalHost {
 static final String CFG="a".repeat(64), RECORD="b".repeat(64), EPOCH="app-process.2.epoch";
 static int checks,nativeCalls;enum Phase{NEW,READY,CLOSING}
 final AtomicReference<Phase> phase=new AtomicReference<>(Phase.NEW);
 String terminalWholeReceipt,runtimeConfigSha256;boolean closeInFlight;Object platform,resources;
 RetainedNoMediaClose noMediaClose;JSONObject lastClosedActorProof;final Fence processFence=new Fence();
 static class Fence{long generation=2;String epoch=EPOCH;long generation(){return generation;}String epochId(){return epoch;}void requireLive(long g){if(g!=generation)throw new IllegalStateException("actor changed");}}
 <T>CompletableFuture<T> submit(Callable<T> c){try{return CompletableFuture.completedFuture(c.call());}catch(Throwable e){return CompletableFuture.failedFuture(e);}}
 /* ACTUAL_RETAINED */
 /* ACTUAL_SELECT */
 /* ACTUAL_HELPER */
 /* ACTUAL_REQUIRE */
 /* ACTUAL_VERIFY */
 static class EmbeddedDuplexPlatform{boolean joined=true;boolean actualOutboundExchangesJoined(){return joined;}}
 static class EmbeddedDuplexResources{}
 static class EmbeddedDuplexRuntimeStatus{}
 static class EmbeddedDuplexOriginals{static class Record{Object retainedCleanupOwner;long actualAppGeneration=2;JSONObject row=new JSONObject().put("operation","whole_app_close");}}
 static class EmbeddedDuplexRuntimeConfigurationBinding{
  static final String NATIVE_V2="rusty.quest.embedded_duplex.no_media_retirement.v2",WHOLE_V2="rusty.quest.embedded_duplex.no_media_whole_retirement.v2";
  /* ACTUAL_CONFIGURATION_CONTEXT */
  /* ACTUAL_CONFIGURATION_FIELDS */
  /* ACTUAL_CONFIGURATION_REQUIRE */
 }
 static class EmbeddedDuplexConstructionClosure{static final String NATIVE="rusty.quest.embedded_duplex.no_media_retirement.v3",WHOLE="rusty.quest.embedded_duplex.no_media_whole_retirement.v3";static void requireNative(JSONObject p,JSONObject i){throw new IllegalStateException("partial not selected");}}
 static class OwnStereoCaptureRuntime{static class CleanupObserverRegistration{}static final OwnStereoCaptureRuntime own=new OwnStereoCaptureRuntime();boolean terminal=true;static OwnStereoCaptureRuntime currentForApplication(){return own;}String physicalCleanupState(){return terminal?"terminal":"pending";}}
 static class EmbeddedDuplexNative{static boolean refuse;static String selectLocalAfterTerminal(String config){nativeCalls++;if(refuse)throw new IllegalStateException("native physical pending");return new JSONObject().put("schema","rusty.quest.local_rollback_native.v1").put("config_sha256",config).put("feature_enabled",false).put("physical_cleanup","terminal").put("scope","route-selection-only").toString();}}
 static void check(boolean b){if(!b)throw new AssertionError("control "+checks);checks++;}
 static NoMediaLocalHost ready()throws Exception{
  NoMediaLocalHost h=new NoMediaLocalHost();EmbeddedDuplexOriginals.Record o=new EmbeddedDuplexOriginals.Record();
  JSONObject binding=new JSONObject().put("$schema","rusty.quest.embedded_duplex.runtime_configuration_binding.v1").put("config_sha256",CFG).put("app_process_generation",2).put("app_record_sha256",RECORD).put("native_executor_generation",7).put("local_peer_id","peer.q").put("fresh_setup_build_policy",new JSONObject());
  RetainedNoMediaClose held=new RetainedNoMediaClose(CFG,RECORD,EPOCH,2,7,1,new EmbeddedDuplexPlatform(),new EmbeddedDuplexResources(),OwnStereoCaptureRuntime.own,()->{},new JSONObject(),o,new EmbeddedDuplexRuntimeConfigurationBinding.Context(binding));
  JSONObject nativeProof=new JSONObject().put("$schema",EmbeddedDuplexRuntimeConfigurationBinding.NATIVE_V2).put("app_process_generation",2).put("app_record_sha256",RECORD).put("native_executor_generation",7).put("config_sha256",CFG).put("native_runtime_binding","configured").put("disposition","runtime_config_closed").put("configuration_binding",binding).put("native_close",new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_closed.v1").put("config_sha256",CFG));
  held.nativeProof=nativeProof;held.proof=new JSONObject().put("$schema",EmbeddedDuplexRuntimeConfigurationBinding.WHOLE_V2).put("action","whole_app_close").put("config_sha256",CFG).put("process_epoch",EPOCH).put("app_process_generation",2).put("app_record_sha256",RECORD).put("native_executor_generation",7).put("native_retirement",nativeProof);
  for(String k:new String[]{"java_own_capture_cleanup","java_peer_resources_cleanup","java_renderer_cleanup","java_control_cleanup","java_callback_cleanup","command_lane_barrier","whole_app_physical_cleanup","app_process_fence_cleanup"})held.proof.put(k,"terminal");
  held.callbackMarker=CompletableFuture.completedFuture(null);held.completion.complete("no-media-closed");o.retainedCleanupOwner=held;h.noMediaClose=held;h.lastClosedActorProof=new JSONObject(held.proof.toString());return h;
 }
 static void select(NoMediaLocalHost h,boolean accept)throws Exception{int before=nativeCalls;LocalRollbackRequestFence f=new LocalRollbackRequestFence();var ticket=f.begin(4);try{String r=h.selectLocalAfterTerminal(f,ticket,()->4,()->true).join();check(accept);check(new JSONObject(r).getString("app_verified_config_sha256").equals(CFG));}catch(CompletionException e){if(accept)throw e;check(nativeCalls==before);} }
 public static void main(String[]args)throws Exception{
  if(args.length>0&&args[0].equals("red")){try{select(ready(),true);throw new AssertionError("base unexpectedly accepted");}catch(CompletionException e){check(e.getCause().getMessage().equals("verified whole-app cleanup required before Local"));System.out.println("EXPECTED_RED actual no-media completion rejected");return;}}
  select(ready(),true);NoMediaLocalHost normal=ready();normal.noMediaClose=null;normal.terminalWholeReceipt=new JSONObject().put("peer_lifecycle",new JSONObject().put("whole_app_physical_cleanup","terminal").put("config_sha256",CFG)).toString();select(normal,true);
  for(int i=0;i<18;i++){NoMediaLocalHost h=ready();switch(i){case 0:h.noMediaClose=null;break;case 1:h.noMediaClose.original.retainedCleanupOwner=null;break;case 2:h.noMediaClose.callbackMarker=new CompletableFuture<>();break;case 3:h.noMediaClose.callbackMarker=CompletableFuture.failedFuture(new Exception());break;case 4:h.noMediaClose.platform.joined=false;break;case 5:h.noMediaClose.proof.put("java_renderer_cleanup","pending");break;case 6:h.processFence.generation=3;break;case 7:h.processFence.epoch="other";break;case 8:h.lastClosedActorProof=new JSONObject();break;case 9:h.runtimeConfigSha256=CFG;break;case 10:h.resources=new Object();break;case 11:h.phase.set(Phase.CLOSING);break;case 12:h.noMediaClose.original.row.put("operation","pair_session");break;case 13:h.noMediaClose.completion.obtrudeException(new Exception());break;case 14:h.noMediaClose.pendingFresh=true;break;case 15:h.noMediaClose.proof.getJSONObject("native_retirement").getJSONObject("configuration_binding").put("local_peer_id","other");break;case 16:h.noMediaClose.proof.getJSONObject("native_retirement").put("native_executor_generation",8);break;case 17:h.noMediaClose.configurationContext=null;break;}select(h,false);}
  OwnStereoCaptureRuntime.own.terminal=false;select(ready(),false);OwnStereoCaptureRuntime.own.terminal=true;
  NoMediaLocalHost h=ready();LocalRollbackRequestFence f=new LocalRollbackRequestFence();var t=f.begin(4);int before=nativeCalls;f.begin(4);try{h.selectLocalAfterTerminal(f,t,()->4,()->true).join();throw new AssertionError();}catch(CompletionException e){check(nativeCalls==before);}
  t=f.begin(4);try{h.selectLocalAfterTerminal(f,t,()->5,()->true).join();throw new AssertionError();}catch(CompletionException e){check(nativeCalls==before);}
  t=f.begin(4);try{h.selectLocalAfterTerminal(f,t,()->4,()->false).join();throw new AssertionError();}catch(CompletionException e){check(nativeCalls==before);}
  System.out.println("PASS actual Java Local/no-media/normal/ticket controls="+checks);
 }
}
