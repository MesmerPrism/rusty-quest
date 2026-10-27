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
 static void refreshOutcome(org.json.JSONObject row)throws Exception{
  org.json.JSONObject out=new org.json.JSONObject();
  for(String key:new String[]{"original_id","operation","request_sha256","original_challenge","state","effect_state","actor_binding","native_setup_binding","response_sha256","failure"})out.put(key,row.get(key));
  row.put("outcome_sha256",EmbeddedDuplexCanonicalJson.sha256(out));
 }
 @SuppressWarnings("removal")
 static void savedLegacyWhole(String rowPath,String joinPath)throws Exception{
  byte[] sourceRow=Files.readAllBytes(java.nio.file.Path.of(rowPath)),sourceJoin=Files.readAllBytes(java.nio.file.Path.of(joinPath));
  org.json.JSONObject row=EmbeddedDuplexCanonicalJson.readOwned(sourceRow);String id=row.getString("original_id");
  File root=Files.createTempDirectory("legacy-fileonly-").toFile(),directory=new File(root,"operator-originals-v1");yes(directory.mkdir());
  File copiedRow=new File(directory,id+".json"),copiedJoin=new File(directory,id+".joined.json");Files.write(copiedRow.toPath(),sourceRow);Files.write(copiedJoin.toPath(),sourceJoin);
  SecurityManager previous=System.getSecurityManager();
  System.setSecurityManager(new SecurityManager(){
   public void checkPermission(java.security.Permission p){}
   public void checkWrite(String path){throw new AssertionError("FILEONLY write: "+path);}
   public void checkDelete(String path){throw new AssertionError("FILEONLY delete: "+path);}
   public void checkExec(String path){throw new AssertionError("FILEONLY exec");}
   public void checkConnect(String host,int port){throw new AssertionError("FILEONLY connect");}
  });
  try{
   org.json.JSONObject result=EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,row.getJSONObject("actor_binding").getString("package_id"));
   yes(result.getString("request_sha256").equals(row.getString("request_sha256")));yes(result.getString("outcome_sha256").equals(row.getString("outcome_sha256")));
   yes(result.getLong("prior_native_generation")==row.getJSONObject("actor_binding").getLong("native_generation"));
   yes(Arrays.equals(sourceRow,Files.readAllBytes(copiedRow.toPath())));yes(Arrays.equals(sourceJoin,Files.readAllBytes(copiedJoin.toPath())));
   denied(()->EmbeddedDuplexOriginals.closedWholeHandoff(root,id));
  }finally{System.setSecurityManager(previous);}
  yes(Arrays.equals(sourceRow,Files.readAllBytes(java.nio.file.Path.of(rowPath))));yes(Arrays.equals(sourceJoin,Files.readAllBytes(java.nio.file.Path.of(joinPath))));
  System.out.println("Saved legacy Whole FILEONLY reader passed; no current Native eligibility claimed.");
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
  // Neutral SDK2 transport-v2 record: preserve its old effect token in its outcome digest.
  File rowFile=new File(root,"operator-originals-v1/"+id+".json");
  org.json.JSONObject legacyRow=new org.json.JSONObject(new String(original,java.nio.charset.StandardCharsets.UTF_8));
  legacyRow.put("effect_state","settled");refreshOutcome(legacyRow);
  org.json.JSONObject legacyJoin=new org.json.JSONObject(join.toString()).put("outcome_sha256",legacyRow.getString("outcome_sha256"));
  Files.writeString(rowFile.toPath(),EmbeddedDuplexCanonicalJson.encode(legacyRow));Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(legacyJoin));
  byte[] legacyBytes=Files.readAllBytes(rowFile.toPath()),legacyJoinBytes=Files.readAllBytes(joined.toPath());
  org.json.JSONObject oldHandoff=EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"fixture.package");
  yes(oldHandoff.getLong("prior_native_generation")==9);
  yes(oldHandoff.getString("outcome_sha256").equals(legacyRow.getString("outcome_sha256")));
  denied(()->EmbeddedDuplexOriginals.closedWholeHandoff(root,id)); // The current codec stays strict.
  EmbeddedDuplexOriginals currentCodec=new EmbeddedDuplexOriginals(root,actor,()->{});
  denied(()->currentCodec.query(id,challenge));
  denied(()->currentCodec.acknowledge(id,challenge,legacyRow.getString("request_sha256"),legacyRow.getString("outcome_sha256"),hash));
  java.util.concurrent.atomic.AtomicInteger effects=new java.util.concurrent.atomic.AtomicInteger();
  denied(()->currentCodec.begin(id,"whole_app_close",challenge,request,()->{effects.incrementAndGet();return java.util.concurrent.CompletableFuture.completedFuture(response);}));yes(effects.get()==0);
  org.json.JSONObject bad=new org.json.JSONObject(legacyRow.toString()).put("request_sha256","5".repeat(64));refreshOutcome(bad);
  Files.writeString(rowFile.toPath(),EmbeddedDuplexCanonicalJson.encode(bad));
  Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(new org.json.JSONObject(legacyJoin.toString()).put("request_sha256",bad.getString("request_sha256")).put("outcome_sha256",bad.getString("outcome_sha256"))));
  denied(()->EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"fixture.package"));
  bad=new org.json.JSONObject(legacyRow.toString()).put("state","unknown").put("effect_state","unknown");refreshOutcome(bad);
  Files.writeString(rowFile.toPath(),EmbeddedDuplexCanonicalJson.encode(bad));
  Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(new org.json.JSONObject(legacyJoin.toString()).put("request_sha256",bad.getString("request_sha256")).put("outcome_sha256",bad.getString("outcome_sha256"))));
  denied(()->EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"fixture.package"));
  Files.write(rowFile.toPath(),legacyBytes);Files.write(joined.toPath(),legacyJoinBytes);denied(()->EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"other.package"));
  Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(new org.json.JSONObject(legacyJoin.toString()).put("outcome_sha256","4".repeat(64))));
  denied(()->EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"fixture.package"));Files.write(joined.toPath(),legacyJoinBytes);
  yes(Arrays.equals(legacyBytes,Files.readAllBytes(rowFile.toPath())));yes(Arrays.equals(legacyJoinBytes,Files.readAllBytes(joined.toPath())));
  org.json.JSONObject oldV4=EmbeddedDuplexOriginalsHostTest.freshContractLifecycle().put("action","whole_app_close")
    .put("app_process_generation",1).put("authority_clock_read",org.json.JSONObject.NULL).put("owner_transport_attempt",org.json.JSONObject.NULL);
  for(String field:new String[]{"peer_physical_cleanup","native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status"})oldV4.put(field,"terminal");
  org.json.JSONObject oldV4Row=new org.json.JSONObject(legacyRow.toString());
  org.json.JSONObject oldV4Response=new org.json.JSONObject(legacyRow.getJSONObject("response").toString());
  org.json.JSONObject oldV4Envelope=new org.json.JSONObject(oldV4Response.getString("receipt")).put("peer_lifecycle",oldV4);
  oldV4Response.put("receipt",EmbeddedDuplexCanonicalJson.encode(oldV4Envelope));oldV4Row.put("response",oldV4Response).put("response_sha256",EmbeddedDuplexCanonicalJson.sha256(oldV4Response));refreshOutcome(oldV4Row);
  org.json.JSONObject oldV4Join=new org.json.JSONObject(legacyJoin.toString()).put("outcome_sha256",oldV4Row.getString("outcome_sha256"))
    .put("physical_retirement_schema",oldV4.getString("$schema")).put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(oldV4));
  Files.writeString(rowFile.toPath(),EmbeddedDuplexCanonicalJson.encode(oldV4Row));Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(oldV4Join));
  yes(EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"fixture.package").getString("physical_retirement_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(oldV4)));
  org.json.JSONObject unsupportedV4=new org.json.JSONObject(oldV4.toString()).put("unexpected_diagnostic",org.json.JSONObject.NULL);
  oldV4Envelope.put("peer_lifecycle",unsupportedV4);oldV4Response.put("receipt",EmbeddedDuplexCanonicalJson.encode(oldV4Envelope));oldV4Row.put("response_sha256",EmbeddedDuplexCanonicalJson.sha256(oldV4Response));refreshOutcome(oldV4Row);
  Files.writeString(rowFile.toPath(),EmbeddedDuplexCanonicalJson.encode(oldV4Row));
  Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(new org.json.JSONObject(oldV4Join.toString()).put("outcome_sha256",oldV4Row.getString("outcome_sha256")).put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(unsupportedV4))));
  denied(()->EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"fixture.package"));
  for(String mode:new String[]{"missing_core","missing_legacy_slot","pending_cleanup","wrong_diagnostic_type"}){
   org.json.JSONObject malformed=new org.json.JSONObject(oldV4.toString());
   if(mode.equals("missing_core"))malformed.remove("broker_evidence");
   if(mode.equals("missing_legacy_slot"))malformed.remove("authority_clock_read");
   if(mode.equals("pending_cleanup"))malformed.put("java_renderer_cleanup","pending");
   if(mode.equals("wrong_diagnostic_type"))malformed.put("owner_transport_attempt","not an owned diagnostic object");
   org.json.JSONObject malformedEnvelope=new org.json.JSONObject(legacyRow.getJSONObject("response").getString("receipt")).put("peer_lifecycle",malformed);
   org.json.JSONObject malformedResponse=new org.json.JSONObject(legacyRow.getJSONObject("response").toString()).put("receipt",EmbeddedDuplexCanonicalJson.encode(malformedEnvelope));
   org.json.JSONObject malformedRow=new org.json.JSONObject(legacyRow.toString()).put("response",malformedResponse).put("response_sha256",EmbeddedDuplexCanonicalJson.sha256(malformedResponse));refreshOutcome(malformedRow);
   org.json.JSONObject malformedJoin=new org.json.JSONObject(oldV4Join.toString()).put("outcome_sha256",malformedRow.getString("outcome_sha256")).put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(malformed));
   Files.writeString(rowFile.toPath(),EmbeddedDuplexCanonicalJson.encode(malformedRow));Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(malformedJoin));
   denied(()->EmbeddedDuplexOriginals.closedLegacyV2WholeHandoff(root,id,"fixture.package"));
  }
  // Restore only this synthetic fixture for its existing current-format controls below.
  Files.write(rowFile.toPath(),original);Files.writeString(joined.toPath(),EmbeddedDuplexCanonicalJson.encode(join));
  org.json.JSONObject nativeStatus=new org.json.JSONObject().put("native_generation",9);
  EmbeddedDuplexProcessHost.requireLegacyWholeActor(physical,nativeStatus,5); // More than one idle generation.
  denied(()->EmbeddedDuplexProcessHost.requireLegacyWholeActor(physical,nativeStatus,0));
  denied(()->EmbeddedDuplexProcessHost.requireLegacyWholeActor(new org.json.JSONObject(physical.toString()).put("app_process_generation",6),nativeStatus,5));
  denied(()->EmbeddedDuplexProcessHost.requireLegacyWholeActor(physical,new org.json.JSONObject().put("native_generation",8),5));
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
 static void activityCacheControls()throws Exception{
  String config="a".repeat(64),enrollment="b".repeat(64);long attachment=7;
  org.json.JSONObject physical=new org.json.JSONObject().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1")
    .put("action","whole_app_close").put("peer_physical_cleanup","terminal").put("config_sha256",config)
    .put("app_process_generation",1).put("native_executor_generation",9);
  for(String key:new String[]{"native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status"})physical.put(key,"terminal");
  EmbeddedDuplexWholeCloseRollover proof=EmbeddedDuplexWholeCloseRollover.verified(physical,"app-process.1.fixture",1,9,attachment,config,enrollment);
  java.util.concurrent.CompletableFuture<EmbeddedDuplexRuntimeStatus> a=java.util.concurrent.CompletableFuture.completedFuture(new EmbeddedDuplexRuntimeStatus("bootstrapped_route_unverified",true,config,enrollment));
  java.util.concurrent.CompletableFuture<EmbeddedDuplexRuntimeStatus> b=java.util.concurrent.CompletableFuture.completedFuture(new EmbeddedDuplexRuntimeStatus("bootstrapped_route_unverified",true,config,enrollment));
  java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<EmbeddedDuplexRuntimeStatus>> cache=new java.util.concurrent.atomic.AtomicReference<>(a);
  Runnable delayedA=()->{if(proof.matchesCompletedRuntime(a,cache.get(),attachment))cache.set(null);};
  // Global run B may already be selected. This token owns only the exact A bookkeeping cache.
  delayedA.run();yes(cache.get()==null);
  cache.set(b);delayedA.run();yes(cache.get()==b);
  yes(!proof.matchesCompletedRuntime(a,a,attachment+1));yes(!proof.matchesCompletedRuntime(a,b,attachment));
  yes(!proof.matchesCompletedRuntime(a,new java.util.concurrent.CompletableFuture<>(),attachment));
  java.util.concurrent.CompletableFuture<?> failed=java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("A failed before effects"));
  yes(EmbeddedDuplexWholeCloseRollover.completedSelectionCacheMatches(failed,failed,7,7,7));
  yes(EmbeddedDuplexWholeCloseRollover.completedSelectionCacheMatches(failed,failed,7,0,0));
  yes(!EmbeddedDuplexWholeCloseRollover.completedSelectionCacheMatches(failed,b,7,7,0));
  yes(!EmbeddedDuplexWholeCloseRollover.completedSelectionCacheMatches(failed,failed,7,8,0));
  yes(!EmbeddedDuplexWholeCloseRollover.completedSelectionCacheMatches(new java.util.concurrent.CompletableFuture<>(),null,7,7,0));

  System.out.println("Delayed A Activity cache controls passed; no global B state authorizes clearing B.");
 }
 static void callerControls()throws Exception{
  File app=Files.createTempDirectory("run-caller").toFile();byte[] identity="retained key and enrollment fixture bytes".getBytes();
  File key=new File(app,"embedded-duplex-identity");yes(key.mkdir());File record=new File(key,"ed25519-identity.v1");Files.write(record.toPath(),identity);
  android.content.Context context=new android.content.Context(){public File getNoBackupFilesDir(){return app;}};
  String a="a".repeat(32),b="b".repeat(32),c="c".repeat(32),id="d".repeat(32),challenge="e".repeat(32);
  EmbeddedDuplexDiagnosticService.harnessRequire(context);yes(EmbeddedDuplexProcessHost.operationalContext(context).legacy());
  EmbeddedDuplexProcessHost host=EmbeddedDuplexProcessHost.forApplication(context);
  host.armDiagnosticChallenge(challenge);
  java.util.concurrent.ExecutorService privateDraft=java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"private-draft-control");thread.setDaemon(true);return thread;});
  java.util.concurrent.Future<java.util.concurrent.CompletableFuture<EmbeddedDuplexEnrollment>> provision=privateDraft.submit(()->host.provisionLocalDiagnosticFixture(challenge));
  yes(EmbeddedDuplexEnrollmentResolver.entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
  rejectFuture(EmbeddedDuplexRuntimeService.newRun(context,a,null));yes(EmbeddedDuplexRuntimeService.selectedRunId(context)==null);yes(host.fixtureWrites==0);
  EmbeddedDuplexEnrollmentResolver.release.countDown();java.util.concurrent.CompletableFuture<EmbeddedDuplexEnrollment> heldWrite=provision.get(5,java.util.concurrent.TimeUnit.SECONDS);
  yes(host.fixtureWrites==1&&!heldWrite.isDone());rejectFuture(EmbeddedDuplexRuntimeService.newRun(context,a,null));
  host.harnessFinishFixture();heldWrite.get();privateDraft.shutdown();yes(privateDraft.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));

  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.present=true;
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.paused=true;
  java.util.concurrent.CompletableFuture<?> failedA=java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("pre-effect A bootstrap failure"));
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.cached=failedA;
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.attachment=7;
  org.json.JSONObject selected=new org.json.JSONObject(EmbeddedDuplexRuntimeService.newRun(context,a,null).get());
  yes(io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.cached==failedA);
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.resume();
  yes(io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.cached==null);
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.present=false;

  denied(()->EmbeddedDuplexDiagnosticService.harnessRequire(context));
  denied(()->host.armDiagnosticChallenge(challenge));rejectFuture(host.provisionLocalDiagnosticFixture(challenge));
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
  EmbeddedDuplexPlatform platformA=new EmbeddedDuplexPlatform();
  yes(!host.harnessJoinWhole(platformA));rejectFuture(EmbeddedDuplexRuntimeService.newRun(context,b,null));
  platformA.retired.complete(null);host.harnessSubmit(()->null).get();yes(host.harnessJoinWhole(platformA));yes(platformA.retirementCalls==1);yes(platformA.controlCloses==1);
  // Native/physical leaves are modeled; the selector, service, Original, fence and filesystem are actual.
  EmbeddedDuplexNative.actualRun=a;EmbeddedDuplexNative.nativeGeneration=1L;
  host.harnessAttachment(0);
  java.util.concurrent.CompletableFuture<?> closedA=java.util.concurrent.CompletableFuture.completedFuture("actual modeled A service delivery");
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.present=true;
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.paused=true;
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.cached=closedA;
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.attachment=7;
  selected=new org.json.JSONObject(EmbeddedDuplexRuntimeService.newRun(context,b,null).get());
  yes(io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.cached==closedA);
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.resume();
  yes(io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.cached==null);
  yes(io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.attachment==0);
  io.github.mesmerprism.rustyquest.spatial_camera_panel.EmbeddedDuplexDiagnosticActivityGate.present=false;

  yes(selected.getString("run_id").equals(b));yes(selected.getJSONObject("native").getString("operational_run_id").equals(a));
  host.harnessAttachment(8);EmbeddedDuplexPlatform platformB=new EmbeddedDuplexPlatform();
  yes(!host.harnessJoinWhole(platformB));platformB.retired.complete(null);host.harnessSubmit(()->null).get();
  yes(host.harnessJoinWhole(platformB));yes(platformB.retirementCalls==1);yes(platformB.controlCloses==1);

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
  if(args.length>0&&args[0].equals("legacy-files")){savedLegacyWhole(args[1],args[2]);return;}
  if(args.length>0&&args[0].equals("caller")){legacyWitnessControls();callerControls();activityCacheControls();return;}
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
