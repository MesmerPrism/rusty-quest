package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class EmbeddedDuplexRunContextHostTest {
 static int checks;
 static void yes(boolean b){checks++;if(!b)throw new AssertionError();}
 static void denied(Throwing a)throws Exception{checks++;try{a.run();throw new AssertionError("not refused");}catch(IllegalStateException|IllegalArgumentException expected){}}
 interface Throwing{void run()throws Exception;}
 static void rejectFuture(java.util.concurrent.CompletableFuture<?> value)throws Exception{
  checks++;try{value.get(5,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("not refused");}
  catch(java.util.concurrent.ExecutionException expected){if(!(expected.getCause() instanceof IllegalStateException))throw expected;}
 }
 static void legacyWitnessControls()throws Exception{
  File root=Files.createTempDirectory("legacy-release").toFile();String id="9".repeat(32),challenge="8".repeat(32),hash="7".repeat(64);
  EmbeddedDuplexOriginalsHostTest.Actor actor=new EmbeddedDuplexOriginalsHostTest.Actor();actor.generation=1;
  EmbeddedDuplexOriginals owner=new EmbeddedDuplexOriginals(root,actor,()->{});
  org.json.JSONObject physical=new org.json.JSONObject().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1")
    .put("action","whole_app_close").put("config_sha256",hash).put("app_process_generation",1).put("native_executor_generation",9)
    .put("peer_physical_cleanup","terminal").put("renewal_pending",false);
  for(String key:new String[]{"native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status"})physical.put(key,"terminal");
  android.os.Bundle response=new android.os.Bundle();response.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");response.putString("status","observed");response.putString("challenge",challenge);
  response.putString("receipt",new org.json.JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action","whole_app_close").put("challenge",challenge).put("peer_lifecycle",physical).toString());
  org.json.JSONObject request=new org.json.JSONObject().put("schema",EmbeddedDuplexOriginals.TRANSPORT).put("method","whole_app_close").put("challenge",challenge).put("extras",new org.json.JSONObject());
  owner.begin(id,"whole_app_close",challenge,request,()->java.util.concurrent.CompletableFuture.completedFuture(response));
  org.json.JSONObject row=new org.json.JSONObject(owner.query(id,challenge).getString("observation_json"));
  org.json.JSONObject join=new org.json.JSONObject().put("schema","rusty.quest.embedded_duplex.original_join.v2").put("original_id",id).put("operation","whole_app_close")
    .put("request_sha256",row.getString("request_sha256")).put("outcome_sha256",row.getString("outcome_sha256")).put("actor_binding",row.getJSONObject("actor_binding"))
    .put("original_worker_terminal",true).put("original_callback_terminal",true).put("outbound_exchanges_joined",true).put("remote_outcome_resolved",false)
    .put("app_process_generation",1).put("native_executor_generation",9).put("app_record_sha256",actor.snapshot().getString("app_record_sha256"))
    .put("physical_retirement_schema",physical.getString("$schema")).put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(physical))
    .put("join_basis",new org.json.JSONObject().put("kind","actual_future_callback"));
  File joined=new File(root,"operator-originals-v1/"+id+".joined.json");Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(join));
  byte[] original=Files.readAllBytes(new File(root,"operator-originals-v1/"+id+".json").toPath());
  org.json.JSONObject handoff=EmbeddedDuplexOriginals.closedWholeHandoff(root,id);yes(handoff.getLong("prior_native_generation")==9);
  yes(handoff.getString("physical_retirement_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(physical)));
  String unknownId="6".repeat(32);org.json.JSONObject pendingPhysical=new org.json.JSONObject(physical.toString()).put("action","start").put("status","pending");
  android.os.Bundle pendingResponse=new android.os.Bundle(response);pendingResponse.putString("receipt",new org.json.JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action","start").put("challenge",challenge).put("peer_lifecycle",pendingPhysical).toString());
  owner.begin(unknownId,"start",challenge,new org.json.JSONObject(request.toString()).put("method","start"),()->java.util.concurrent.CompletableFuture.completedFuture(pendingResponse));
  org.json.JSONObject unknown=new org.json.JSONObject(owner.query(unknownId,challenge).getString("observation_json"));
  org.json.JSONObject unknownJoin=new org.json.JSONObject(join.toString()).put("original_id",unknownId).put("operation","start")
    .put("request_sha256",unknown.getString("request_sha256")).put("outcome_sha256",unknown.getString("outcome_sha256"));
  unknown.put("retention","compacted");File unknownFile=new File(root,"operator-originals-v1/"+unknownId+".json"),unknownJoined=new File(root,"operator-originals-v1/"+unknownId+".joined.json");
  Files.writeString(unknownFile.toPath(),EmbeddedDuplexCanonicalJson.encode(unknown));Files.writeString(unknownJoined.toPath(),EmbeddedDuplexCanonicalJson.encode(unknownJoin));
  byte[] unknownBytes=Files.readAllBytes(unknownFile.toPath());
  EmbeddedDuplexOriginals readonly=EmbeddedDuplexOriginals.retained(new EmbeddedDuplexRunContext(root,root,"a".repeat(32),0));
  yes("unknown".equals(readonly.query(unknownId,challenge).getString("status")));
  yes(Arrays.equals(unknownBytes,Files.readAllBytes(unknownFile.toPath())));
  denied(()->readonly.acknowledge(unknownId,challenge,unknown.getString("request_sha256"),unknown.getString("outcome_sha256"),hash));
  Files.delete(unknownJoined.toPath());denied(()->readonly.query(unknownId,challenge));
  File global=Files.createTempDirectory("legacy-handoff-fence").toFile(),companion=Files.createTempDirectory("legacy-handoff-companion").toFile();
  try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(global,null,null,()->{})){
    fence.selectOperationalRun("b".repeat(32),companion,null,null);
    fence.operationalHandoff("b".repeat(32),()->EmbeddedDuplexOriginals.closedWholeHandoff(root,id));
    org.json.JSONObject clear=new org.json.JSONObject(fence.nativeOperationalRunHandoff());
    yes(clear.getString("app_record_sha256").equals(EmbeddedDuplexProcessFence.digest(fence.nativeInspectionRecord())));
    fence.beforeRuntimeEffects(null,null);org.json.JSONObject pending=new org.json.JSONObject(fence.nativeOperationalRunHandoff());
    yes(!clear.getString("app_record_sha256").equals(pending.getString("app_record_sha256")));
    yes(pending.getString("app_record_sha256").equals(EmbeddedDuplexProcessFence.digest(fence.nativeAdmissionRecord())));
    join.put("physical_retirement_sha256","0".repeat(64));Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(join));
    denied(fence::nativeOperationalRunHandoff);
  }
  yes(Arrays.equals(original,Files.readAllBytes(new File(root,"operator-originals-v1/"+id+".json").toPath())));
  System.out.println("Legacy Original/join subjects modeled; actual source reader and dynamic held-channel handoff controls passed.");
 }
 static void callerControls()throws Exception{
  File app=Files.createTempDirectory("run-caller").toFile();byte[] identity="retained key and enrollment fixture bytes".getBytes();
  File key=new File(app,"embedded-duplex-identity");yes(key.mkdir());File record=new File(key,"ed25519-identity.v1");Files.write(record.toPath(),identity);
  android.content.Context context=new android.content.Context(){public File getNoBackupFilesDir(){return app;}};
  String a="a".repeat(32),b="b".repeat(32),c="c".repeat(32),id="d".repeat(32),challenge="e".repeat(32);
  org.json.JSONObject selected=new org.json.JSONObject(EmbeddedDuplexRuntimeService.newRun(context,a,null).get());
  yes(selected.getString("run_id").equals(a));yes(selected.getJSONObject("native").isNull("operational_run_id"));
  EmbeddedDuplexRunContext old=EmbeddedDuplexProcessHost.operationalContext(context);
  EmbeddedDuplexOriginals originals=EmbeddedDuplexOriginals.forApplication(context);
  android.os.Bundle fields=new android.os.Bundle();fields.putString("run_id",a);
  org.json.JSONObject request=EmbeddedDuplexOriginals.request("start",challenge,fields);
  yes(request.getString("schema").equals(EmbeddedDuplexOriginals.RUN_TRANSPORT));
  originals.begin(id,"start",challenge,request,()->java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("old closed failed action")));
  File oldRow=new File(old.root,"operator-originals-v1/"+id+".json");byte[] oldBytes=Files.readAllBytes(oldRow.toPath());
  yes("unknown".equals(originals.query(id,challenge).getString("status")));
  String pairId="f".repeat(32);
  originals.begin(pairId,"pair_session",challenge,EmbeddedDuplexOriginals.request("pair_session",challenge,fields),()->{
    try{EmbeddedDuplexOriginals.bindFreshSetupOriginal("setup.fixture");EmbeddedDuplexOriginals.bindFreshSetupContext("setup.fixture","sha256:"+"1".repeat(64));}
    catch(Exception e){return java.util.concurrent.CompletableFuture.failedFuture(e);}
    return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("old Pair unknown after actual native source binding"));
  });
  // Native/physical leaves are modeled; the selector, service, Original, fence and filesystem are actual.
  EmbeddedDuplexNative.actualRun=a;EmbeddedDuplexNative.nativeGeneration=1L;
  selected=new org.json.JSONObject(EmbeddedDuplexRuntimeService.newRun(context,b,null).get());
  yes(selected.getString("run_id").equals(b));yes(selected.getJSONObject("native").getString("operational_run_id").equals(a));
  yes(Arrays.equals(oldBytes,Files.readAllBytes(oldRow.toPath())));
  yes("unknown".equals(EmbeddedDuplexRuntimeService.queryOriginal(context,a,id,challenge).getString("status")));
  android.os.Bundle pairQuery=EmbeddedDuplexRuntimeService.queryOriginal(context,a,pairId,challenge);
  yes("unknown".equals(pairQuery.getString("status")));
  yes("setup.fixture".equals(new org.json.JSONObject(pairQuery.getString("observation_json")).getJSONObject("native_setup_binding").getString("setup_request_id")));
  EmbeddedDuplexOriginals nextOriginals=EmbeddedDuplexOriginals.forApplication(context);
  denied(()->nextOriginals.begin(id,"start",challenge,request,()->{throw new AssertionError("old run request dispatched");}));
  android.os.Bundle nextFields=new android.os.Bundle();nextFields.putString("run_id",b);
  android.os.Bundle next=nextOriginals.begin(id,"start",challenge,EmbeddedDuplexOriginals.request("start",challenge,nextFields),
    ()->java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("distinct B action")));
  yes(!new org.json.JSONObject(next.getString("observation_json")).getString("request_sha256").equals(new org.json.JSONObject(originals.query(id,challenge).getString("observation_json")).getString("request_sha256")));

  yes(new File(EmbeddedDuplexProcessHost.operationalRoot(context),"operator-originals-v1/"+id+".json").exists());
  denied(()->EmbeddedDuplexRuntimeService.withRunRequest(context,a,()->{throw new AssertionError("stale request entered");}));
  EmbeddedDuplexNative.eligible=false;rejectFuture(EmbeddedDuplexRuntimeService.newRun(context,c,null));
  yes(EmbeddedDuplexRuntimeService.selectedRunId(context).equals(b));EmbeddedDuplexNative.eligible=true;
  EmbeddedDuplexRuntimeService.withRunRequest(context,b,()->{rejectFuture(EmbeddedDuplexRuntimeService.newRun(context,c,null));return null;});
  yes(Arrays.equals(identity,Files.readAllBytes(record.toPath())));
  // Exact Resume selects retained A only; it cannot turn its unknown action into success/ACK.
  EmbeddedDuplexRuntimeService.resumeRun(context,a).get();yes(EmbeddedDuplexRuntimeService.selectedRunId(context).equals(a));
  yes(Arrays.equals(oldBytes,Files.readAllBytes(oldRow.toPath())));
  System.out.println("run caller checks="+checks+"; actual selector/store/fence/Original bodies, Native physical state modeled");
 }
 public static void main(String[] args)throws Exception{
  if(args.length>0&&args[0].equals("caller")){legacyWitnessControls();callerControls();return;}
  File app=Files.createTempDirectory("run-host").toFile();
  byte[] identity="real retained identity bytes".getBytes();Files.write(new File(app,"identity").toPath(),identity);
  EmbeddedDuplexRunSelectionStore store=new EmbeddedDuplexRunSelectionStore(app,new EmbeddedDuplexRunSelectionStore.DirectorySync(){public void sync(File d){} public void privateMode(File p,boolean d){/* Host Windows mode modeled; Android uses actual chmod. */}});
  yes(store.current().legacy());
  String a="a".repeat(32),b="b".repeat(32);
  EmbeddedDuplexRunContext first=store.newRun(a);yes(first.runId.equals(a));yes(first.root.isDirectory());
  Files.write(new File(first.root,"old-unknown.json").toPath(),"unknown".getBytes());
  long generation=first.selectionGeneration;
  yes(store.newRun(a).selectionGeneration==generation);
  EmbeddedDuplexRunContext second=store.newRun(b);yes(!first.root.equals(second.root));
  yes(!new File(second.root,"old-unknown.json").exists());
  denied(()->store.newRun(a));yes(store.resumeRun(a).runId.equals(a));
  yes(Arrays.equals(identity,Files.readAllBytes(new File(app,"identity").toPath())));
  yes("unknown".equals(Files.readString(new File(first.root,"old-unknown.json").toPath())));
  denied(()->store.newRun("../escape"));denied(()->store.resumeRun("c".repeat(32)));
  EmbeddedDuplexRunSelectionStore restart=new EmbeddedDuplexRunSelectionStore(app,new EmbeddedDuplexRunSelectionStore.DirectorySync(){public void sync(File d){} public void privateMode(File p,boolean d){/* Host Windows mode modeled; Android uses actual chmod. */}});yes(restart.current().runId.equals(a));
  // A torn publication never guesses the latest directory or selects another run.
  Files.writeString(new File(app,"embedded-duplex-runs-v1/selection.v1.json.new").toPath(),"torn");
  denied(()->new EmbeddedDuplexRunSelectionStore(app,new EmbeddedDuplexRunSelectionStore.DirectorySync(){public void sync(File d){} public void privateMode(File p,boolean d){/* Host Windows mode modeled; Android uses actual chmod. */}}).current());
  System.out.println("run-context checks="+checks+"; filesystem only, global physical guards separate");
 }
}
