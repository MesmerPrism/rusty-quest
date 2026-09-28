package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex; import org.json.*; import android.content.Context;
public class PartialInstallTest {public static String failure;public static boolean pipelinePending;public static void fail(String stage){if(stage.equals(failure))throw new IllegalStateException(stage);}static void check(boolean b){if(!b)throw new AssertionError();}
static JSONObject spec(){JSONObject source=new JSONObject().put("source_id","source").put("source_kind","camera2_mediacodec_surface").put("camera",new JSONObject().put("camera_ids",new JSONArray().put(new JSONObject().put("track_role","left").put("camera_id","0")).put(new JSONObject().put("track_role","right").put("camera_id","1"))));JSONObject media=new JSONObject().put("codec","h264").put("stream_framing","rmanvid-v4-packed-stereo").put("width",640).put("height",480).put("frame_rate_hz",30).put("bitrate_bps",1000000).put("max_packet_bytes",65536);JSONObject endpoint=new JSONObject().put("source_id","source").put("source_host","192.0.2.1").put("source_port",4000);JSONObject lane=new JSONObject().put("source_id","source").put("source_device_id","device").put("media",media).put("source",source).put("transport",new JSONObject().put("transport_kind","lan_tcp").put("endpoint",endpoint));return new JSONObject().put("runtime_spec_id","spec").put("plan",new JSONObject().put("session_id","session").put("sources",new JSONArray().put(source)).put("runtime_endpoints",new JSONArray().put(new JSONObject().put("device_id","device").put("source_bindings",new JSONArray().put(endpoint)))).put("lanes",new JSONArray().put(lane)));}
static JSONObject config(){JSONArray a=new JSONArray(),b=new JSONArray();String[] kinds={"source","processor","route","socket","codec","cleanup","sink"};for(String k:kinds){JSONObject p=new JSONObject().put("owner_kind",k).put("owner_id",k).put("provider_kind",k).put("resource_id",k);a.put(new JSONObject().put("owner_kind",k).put("owner_id",k).put("provider_kind",k).put("resource_id",k).put("target",new JSONObject().put("placement",k.equals("sink")?"peer":"local")));b.put(p.put("target",new JSONObject().put("placement",k.equals("sink")?"local":"peer")));}return new JSONObject().put("$schema","rusty.quest.embedded_duplex.runtime_initialized.v1").put("executor_generation",1).put("own_stereo_capture_enabled",true).put("outgoing_runtime_spec",spec()).put("incoming_runtime_spec",spec()).put("owner_placements",a).put("incoming_owner_placements",b);}
public static void main(String[] args)throws Exception {EmbeddedDuplexDisplay display=new EmbeddedDuplexDisplay(){public void activateOwnProjection(){fail("display");}public void activatePeerProjection(long a,long b,long c){}public long[] currentProjection(long g){return new long[0];}};
for(String stage:new String[]{"capture","display","pipeline","owner_set","binding"}){failure=stage;OwnStereoCaptureRuntime.OWN.live=false;EmbeddedDuplexResources retained=new EmbeddedDuplexResources(new Context(),display,config(),1000000);check(!OwnStereoCaptureRuntime.OWN.live);try{retained.install();throw new AssertionError();}catch(IllegalStateException expected){check(expected.getMessage().equals(stage));}check(OwnStereoCaptureRuntime.OWN.live);retained.closeUnstartedAndVerify();check(retained.productResourcesTerminal());check(OwnStereoCaptureRuntime.OWN.live);try{retained.install();throw new AssertionError();}catch(IllegalStateException expected){}System.out.println(stage+" failure retained: PASS");}
failure="binding";EmbeddedDuplexResources retained=new EmbeddedDuplexResources(new Context(),display,config(),1000000);pipelinePending=true;try{retained.install();throw new AssertionError();}catch(IllegalStateException expected){}check(!retained.productResourcesTerminal());try{retained.closeUnstartedAndVerify();throw new AssertionError();}catch(IllegalStateException expected){}pipelinePending=false;retained.closeUnstartedAndVerify();check(retained.productResourcesTerminal());System.out.println("physical Pending retained until actual retry; Own never stopped by Peer close: PASS");
// Complete motivating sequence at the retained Java-owner boundary. Native route/Whole receipts
// below are fixtures; they do NOT assert physical native cleanup or execute the full Host JNI path.
java.nio.file.Path path=java.nio.file.Paths.get(args[0]);java.nio.file.Files.createDirectory(path);
EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(path.toFile(),null,null,()->{});
fence.beforeRuntimeEffects(null,null);
failure="binding";pipelinePending=true;
EmbeddedDuplexResources partial=new EmbeddedDuplexResources(new Context(),display,config(),1000000);
try{partial.install();throw new AssertionError();}catch(IllegalStateException expected){}
boolean nativeUnusedHostPresent=true, ownFallbackRecorded=false;
// Whole close is Pending because no Peer Start target exists; retained partial Java owner survives.
check(nativeUnusedHostPresent && !ownFallbackRecorded && OwnStereoCaptureRuntime.OWN.live);
// Native unused-host close succeeds, then actual Java physical barrier rejects (same partial side effect).
nativeUnusedHostPresent=false;
try{partial.closeUnstartedAndVerify();throw new AssertionError();}catch(IllegalStateException expected){}
check(!nativeUnusedHostPresent && !ownFallbackRecorded && fence.effectsPending());
// Another Whole dispatch cannot find the removed host, and still lacks recorded Own fallback.
check(!nativeUnusedHostPresent && !ownFallbackRecorded);
// Exact no-media retry must retain and finish the SAME real partial Resources owner, not treat null as terminal.
pipelinePending=false;partial.closeUnstartedAndVerify();check(partial.productResourcesTerminal());
ownFallbackRecorded=true;fence.afterVerifiedNoMediaCleanup(null,null);
// Existing Own fallback has no PeerStart dependency. Pending Own is independently accounted for.
fence.beforeRuntimeEffects(null,null);check(ownFallbackRecorded&&OwnStereoCaptureRuntime.OWN.live&&fence.effectsPending());
// No terminal permission is conferred by Peer resources or removed native host.
check(partial.productResourcesTerminal()&&fence.effectsPending());fence.close();
System.out.println("Failed install -> Whole Pending -> unused-native-close/Java rejection -> missing-host Whole -> exact Java retry -> Own-only fallback Pending: PASS (native receipt fixtures; no physical terminal claim)");
}
}