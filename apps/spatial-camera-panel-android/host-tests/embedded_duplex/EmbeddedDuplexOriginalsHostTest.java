package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Production store and source callback controls; OS and Native actor facts are models. */
public final class EmbeddedDuplexOriginalsHostTest {
    static final String ID="11".repeat(16), CHALLENGE="22".repeat(16), HASH="33".repeat(32);
    static int checks;
    interface Checked { void run() throws Exception; }
    static void yes(boolean value){checks++;if(!value)throw new AssertionError("control failed");}
    static void denied(Checked action)throws Exception{checks++;try{action.run();}catch(Exception expected){return;}throw new AssertionError("expected refusal");}
    static JSONObject observe(Bundle value)throws Exception{return new JSONObject(value.getString("observation_json"));}
    static Bundle response(String status)throws Exception{
        Bundle value=new Bundle();value.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");
        value.putString("status","observed");value.putString("challenge",CHALLENGE);
        value.putString("receipt",new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1")
                .put("action","start").put("challenge",CHALLENGE).put("peer_lifecycle",new JSONObject()
                    .put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3")
                    .put("action","start").put("status",status).put("renewal_pending",status.equals("pending"))).toString());return value;
    }
    static final class Actor implements EmbeddedDuplexOriginals.ActorSource {
        boolean retained=true;long generation=7;
        public JSONObject snapshot()throws Exception{return new JSONObject().put("package_id","fixture.package").put("app_record_sha256",HASH).put("native_generation",9);}
        public long appGeneration(){return generation;}
        public boolean settled(JSONObject actor,long captured)throws Exception{return retained&&captured==generation&&EmbeddedDuplexCanonicalJson.sameValue(snapshot(),actor);}
    }
    static JSONObject request()throws Exception{return EmbeddedDuplexOriginals.request("start",CHALLENGE,new Bundle());}
    static String outcome(Bundle query)throws Exception{return observe(query).getString("outcome_sha256");}
    static String requestHash(Bundle query)throws Exception{return observe(query).getString("request_sha256");}
    static JSONObject failedRow(Throwable failure)throws Exception{
        Actor actor=new Actor();EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(Files.createTempDirectory("original-failure-v2-").toFile(),actor,()->{});
        CompletableFuture<Bundle> actual=new CompletableFuture<>();store.begin(ID,"start",CHALLENGE,request(),()->actual);actual.completeExceptionally(failure);
        JSONObject row=observe(store.query(ID,CHALLENGE));yes(row.getString("state").equals("unknown")&&row.getString("effect_state").equals("uncertain"));return row;
    }
    static void failureV2Controls()throws Exception{
        Throwable[] failures={new java.util.concurrent.CompletionException(new IllegalArgumentException("secret session/key/payload")),new java.util.concurrent.ExecutionException(new java.io.IOException("secret")),new IllegalStateException("secret"),new IllegalArgumentException("secret"),new SecurityException("secret"),new java.util.concurrent.CancellationException("secret"),new InterruptedException("secret"),new java.util.concurrent.TimeoutException("secret"),new java.io.IOException("secret"),new LinkageError("secret"),new EmbeddedDuplexNativeOperationFailure("secret native payload"),new EmbeddedDuplexOwnProjectionFailure(EmbeddedDuplexOwnProjectionFailure.Reason.SUPERSEDED),new Exception("secret")};
        String[] families={"completion","execution","state","argument","security","cancelled","interrupted","timeout","io","linkage","native_operation","own_projection","other"};
        JSONObject example=null;
        for(int i=0;i<failures.length;i++){
            JSONObject row=failedRow(failures[i]),failure=row.getJSONObject("failure");example=row;
            yes(failure.keySet().equals(java.util.Set.of("schema","code","operation","original_id","stage","throwable_family","cause_category")));
            yes(failure.getString("schema").equals("rusty.quest.embedded_duplex.original_failure.v2")&&failure.getString("code").equals("actual_future_failed"));
            yes(failure.getString("operation").equals("start")&&failure.getString("original_id").equals(ID)&&failure.getString("stage").equals("actual_future_completion"));
            yes(failure.getString("throwable_family").equals(families[i]));
            yes(failure.getString("cause_category").equals(i==0?"argument":i==1?"io":families[i]));
            yes(!failure.toString().contains("secret")&&!failure.toString().contains("Exception")&&!failure.toString().contains("Error"));
        }
        Exception left=new Exception("sensitive left"),right=new Exception("sensitive right");left.initCause(right);right.initCause(left);
        yes(failedRow(left).getJSONObject("failure").getString("cause_category").equals("unavailable"));
        Throwable deep=new IllegalStateException("sensitive bottom");for(int i=0;i<16;i++)deep=new java.util.concurrent.CompletionException(deep);
        yes(failedRow(deep).getJSONObject("failure").getString("cause_category").equals("unavailable"));
        JSONObject legacy=new JSONObject(example.toString()).put("failure",new JSONObject().put("schema","rusty.quest.embedded_duplex.original_failure.v1").put("code","actual_future_failed"));
        File legacyRoot=Files.createTempDirectory("original-failure-v1-read-").toFile();rewriteFailureRow(legacyRoot,legacy);PathHolder legacyPath=new PathHolder(legacyRoot);
        byte[] exact=Files.readAllBytes(legacyPath.path);String outcome=legacy.getString("outcome_sha256");
        JSONObject restored=observe(new EmbeddedDuplexOriginals(legacyRoot,new Actor(),()->{}).query(ID,CHALLENGE));
        yes(restored.getJSONObject("failure").getString("schema").endsWith(".v1")&&restored.getString("outcome_sha256").equals(outcome)&&java.util.Arrays.equals(exact,Files.readAllBytes(legacyPath.path)));
        for(String damage:new String[]{"extra","operation","original_id","stage","throwable_family","cause_category"}){
            JSONObject bad=new JSONObject(example.toString()),body=bad.getJSONObject("failure");
            if(damage.equals("extra"))body.put("message","sensitive");else body.put(damage,damage.equals("operation")?"whole_app_close":damage.equals("original_id")?"9".repeat(32):"unrecognized");
            File root=Files.createTempDirectory("original-failure-v2-closed-").toFile();rewriteFailureRow(root,bad);denied(()->new EmbeddedDuplexOriginals(root,new Actor(),()->{}).query(ID,CHALLENGE));
        }
    }
    static final class PathHolder {final java.nio.file.Path path;PathHolder(File root){path=new File(root,"operator-originals-v1/"+ID+".json").toPath();}}
    static void rewriteFailureRow(File root,JSONObject row)throws Exception{
        new EmbeddedDuplexOriginals(root,new Actor(),()->{});java.lang.reflect.Method method=EmbeddedDuplexOriginals.class.getDeclaredMethod("outcome",JSONObject.class);method.setAccessible(true);row.put("outcome_sha256",EmbeddedDuplexCanonicalJson.sha256((JSONObject)method.invoke(null,row)));
        Files.writeString(new PathHolder(root).path,EmbeddedDuplexCanonicalJson.encode(row));
    }
    static void appAdmissionControls()throws Exception {
        EmbeddedDuplexAppAdmission.ProcessSource process=()->new EmbeddedDuplexProcessIncarnation.Identity("00112233-4455-6677-8899-aabbccddeeff",42,10042,123456);
        EmbeddedDuplexAppAdmission.Snapshot unavailable=EmbeddedDuplexAppAdmission.activity(true,false,false,true,0,true,true,true,null,null);
        EmbeddedDuplexAppAdmission.Snapshot open=EmbeddedDuplexAppAdmission.activity(true,false,false,true,7,true,true,true,null,null);
        yes(open.bootstrapGateOpen);yes(!unavailable.bootstrapGateOpen);denied(()->EmbeddedDuplexAppAdmission.refuse(open,process));
        for(String mode:new String[]{"queued","rejected"}) {
            Actor actor=new Actor();actor.retained=false; // No Native settlement is supplied by this local branch.
            File root=Files.createTempDirectory("original-app-admission-").toFile();
            EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});
            CompletableFuture<Bundle> result=new CompletableFuture<>();java.util.concurrent.atomic.AtomicReference<Runnable> posted=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<EmbeddedDuplexOriginals.Record> held=new java.util.concurrent.atomic.AtomicReference<>();
            AtomicInteger serviceEntries=new AtomicInteger();
            JSONObject request=EmbeddedDuplexOriginals.request("bootstrap_real_peer",CHALLENGE,new Bundle());
            store.begin(ID,"bootstrap_real_peer",CHALLENGE,request,()->{
                held.set(EmbeddedDuplexOriginals.capture());
                boolean accepted=EmbeddedDuplexOriginals.post(()->{
                    try{
                        result.completeExceptionally(EmbeddedDuplexAppAdmission.refuse(unavailable,process));
                        yes("pending".equals(store.queryJoin(ID,CHALLENGE).getString("status")));
                        denied(()->store.acknowledge(ID,CHALLENGE,requestHash(store.query(ID,CHALLENGE)),outcome(store.query(ID,CHALLENGE)),HASH));
                    }catch(Exception e){throw new IllegalStateException(e);}
                },callback->{if(mode.equals("queued")){posted.set(callback);return true;}return false;});
                if(!accepted){
                    yes(held.get().commands==0);yes(posted.get()==null);
                    try{result.completeExceptionally(EmbeddedDuplexAppAdmission.refuse(EmbeddedDuplexAppAdmission.dispatchRejected(),process));}
                    catch(Exception e){throw new IllegalStateException(e);}
                }
                return result;
            });
            if(posted.get()!=null){yes(held.get().commands==1);posted.get().run();}
            yes(serviceEntries.get()==0);yes(held.get().commands==0);
            Bundle observation=store.query(ID,CHALLENGE);JSONObject row=observe(observation);
            yes("observed".equals(observation.getString("status")));yes("not_entered".equals(row.getString("effect_state")));
            yes("app_refusal".equals(row.getJSONObject("response").getString("kind")));
            Bundle joined=store.queryJoin(ID,CHALLENGE);yes("joined".equals(joined.getString("status")));
            JSONObject proof=new JSONObject(joined.getString("join_observation_json"));
            yes(EmbeddedDuplexAppAdmission.JOIN.equals(proof.getString("schema")));yes(!proof.getBoolean("service_entered"));
            yes(!proof.has("physical_retirement_sha256"));yes(!held.get().boundActor);
            store.begin(ID,"bootstrap_real_peer",CHALLENGE,request,()->{throw new AssertionError("local refusal replayed");});
            denied(()->new EmbeddedDuplexOriginals(root,actor,()->{}).acknowledge(ID,CHALLENGE,requestHash(observation),outcome(observation),HASH));
            denied(()->store.acknowledge(ID,CHALLENGE,HASH,outcome(observation),HASH));
            store.acknowledge(ID,CHALLENGE,requestHash(observation),outcome(observation),HASH);
            yes("compacted".equals(store.query(ID,CHALLENGE).getString("status")));
            yes("joined".equals(new EmbeddedDuplexOriginals(root,actor,()->{}).queryJoin(ID,CHALLENGE).getString("status")));
        }
        // A throwing poster may have queued it; no refusal or reservation release is inferred.
        Actor actor=new Actor();File root=Files.createTempDirectory("original-post-ambiguous-").toFile();
        EmbeddedDuplexOriginals ambiguous=new EmbeddedDuplexOriginals(root,actor,()->{});
        java.util.concurrent.atomic.AtomicReference<Runnable> queued=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<EmbeddedDuplexOriginals.Record> held=new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger calls=new AtomicInteger();
        ambiguous.begin(ID,"bootstrap_real_peer",CHALLENGE,EmbeddedDuplexOriginals.request("bootstrap_real_peer",CHALLENGE,new Bundle()),()->{
            held.set(EmbeddedDuplexOriginals.capture());
            EmbeddedDuplexOriginals.post(()->calls.incrementAndGet(),callback->{queued.set(callback);throw new IllegalStateException("posted then threw");});
            throw new AssertionError("throwing poster returned");
        });
        yes(held.get().commands==1);yes("unknown".equals(ambiguous.query(ID,CHALLENGE).getString("status")));
        yes("pending".equals(ambiguous.queryJoin(ID,CHALLENGE).getString("status")));queued.get().run();
        yes(calls.get()==1&&held.get().commands==0);yes("unknown".equals(ambiguous.query(ID,CHALLENGE).getString("status")));
        // A response-shaped value alone is not the source-owned refusal capability.
        EmbeddedDuplexOriginals forged=new EmbeddedDuplexOriginals(Files.createTempDirectory("original-app-forged-").toFile(),actor,()->{});
        Bundle value=EmbeddedDuplexAppAdmission.refusalBundle(CHALLENGE,EmbeddedDuplexAppAdmission.refuse(unavailable,process));
        denied(()->forged.begin(ID,"bootstrap_real_peer",CHALLENGE,EmbeddedDuplexOriginals.request("bootstrap_real_peer",CHALLENGE,new Bundle()),()->CompletableFuture.completedFuture(value)));
        denied(()->forged.query(ID,CHALLENGE));
    }
    static void failureAndPendingControls() throws Exception {
        Actor actor=new Actor();
        for (String schema : new String[]{"v1","v2","v3"}) {
            for (boolean renewal : new boolean[]{false,true}) {
                File root=Files.createTempDirectory("original-nested-pending-").toFile();
                EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});
                Bundle result=response(renewal?"active":"pending");
                JSONObject wrapper=new JSONObject(result.getString("receipt"));
                wrapper.getJSONObject("peer_lifecycle").put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle."+schema)
                        .put("renewal_pending",renewal);
                result.putString("receipt",wrapper.toString());
                store.begin(ID,"start",CHALLENGE,request(),()->{
                    CompletableFuture<Bundle> done=new CompletableFuture<>();
                    EmbeddedDuplexOriginals.carry(()->{
                        try { EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),actor.appGeneration());done.complete(result); }
                        catch(Exception failure){throw new IllegalStateException(failure);}
                    }).run();return done;
                });
                Bundle observed=store.query(ID,CHALLENGE);
                yes("unknown".equals(observed.getString("status")));
                denied(()->store.acknowledge(ID,CHALLENGE,requestHash(observed),outcome(observed),HASH));
            }
        }
        File thrownRoot=Files.createTempDirectory("original-supplier-threw-").toFile();
        EmbeddedDuplexOriginals thrown=new EmbeddedDuplexOriginals(thrownRoot,actor,()->{});
        AtomicInteger attempts=new AtomicInteger();
        Bundle failure=thrown.begin(ID,"start",CHALLENGE,request(),()->{attempts.incrementAndGet();throw new IllegalStateException("modeled supplier failure");});
        yes("unknown".equals(failure.getString("status"))&&attempts.get()==1);
        yes("actual_future_failed".equals(observe(failure).getJSONObject("failure").getString("code")));
        denied(()->thrown.acknowledge(ID,CHALLENGE,requestHash(failure),outcome(failure),HASH));
        thrown.begin(ID,"start",CHALLENGE,request(),()->{throw new AssertionError("failed supplier replayed");});
        yes(attempts.get()==1);

        File cancelRoot=Files.createTempDirectory("original-future-cancelled-").toFile();
        EmbeddedDuplexOriginals cancelled=new EmbeddedDuplexOriginals(cancelRoot,actor,()->{});
        CompletableFuture<Bundle> future=new CompletableFuture<>();
        cancelled.begin(ID,"start",CHALLENGE,request(),()->future);future.cancel(false);
        Bundle cancellation=cancelled.query(ID,CHALLENGE);
        yes("unknown".equals(cancellation.getString("status")));
        denied(()->cancelled.acknowledge(ID,CHALLENGE,requestHash(cancellation),outcome(cancellation),HASH));

        java.util.concurrent.atomic.AtomicBoolean initialSyncFailure=new java.util.concurrent.atomic.AtomicBoolean();
        File admissionRoot=Files.createTempDirectory("original-predispatch-sync-").toFile();
        EmbeddedDuplexOriginals admission=new EmbeddedDuplexOriginals(admissionRoot,actor,()->{
            if(initialSyncFailure.get())throw new java.io.IOException("modeled registration fsync failure");
        });
        initialSyncFailure.set(true);AtomicInteger entered=new AtomicInteger();
        denied(()->admission.begin(ID,"start",CHALLENGE,request(),()->{entered.incrementAndGet();return new CompletableFuture<>();}));
        yes(entered.get()==0);

        java.util.concurrent.atomic.AtomicBoolean failSync=new java.util.concurrent.atomic.AtomicBoolean();
        File syncRoot=Files.createTempDirectory("original-publication-sync-").toFile();
        EmbeddedDuplexOriginals syncFailure=new EmbeddedDuplexOriginals(syncRoot,actor,()->{if(failSync.get())throw new java.io.IOException("modeled directory fsync failure");});
        CompletableFuture<Bundle> completion=new CompletableFuture<>();
        syncFailure.begin(ID,"start",CHALLENGE,request(),()->completion);
        failSync.set(true);completion.complete(response("active"));
        denied(()->syncFailure.query(ID,CHALLENGE));
        failSync.set(false);
        Bundle restored=new EmbeddedDuplexOriginals(syncRoot,actor,()->{}).query(ID,CHALLENGE);
        denied(()->new EmbeddedDuplexOriginals(syncRoot,actor,()->{}).acknowledge(ID,CHALLENGE,requestHash(restored),outcome(restored),HASH));

        for (String damage : new String[]{"response","outcome","schema","type"}) {
            File root=Files.createTempDirectory("original-corrupt-"+damage).toFile();
            EmbeddedDuplexOriginals original=new EmbeddedDuplexOriginals(root,actor,()->{});
            original.begin(ID,"start",CHALLENGE,request(),()->CompletableFuture.completedFuture(responseUnchecked("active")));
            File file=new File(root,"operator-originals-v1/"+ID+".json");
            JSONObject row=new JSONObject(Files.readString(file.toPath()));
            if(damage.equals("response"))row.put("response_sha256",HASH);
            if(damage.equals("outcome"))row.put("outcome_sha256",HASH);
            if(damage.equals("schema"))row.put("schema","foreign.schema");
            if(damage.equals("type"))row.put("state",new JSONObject());
            Files.writeString(file.toPath(),EmbeddedDuplexCanonicalJson.encode(row));
            denied(()->new EmbeddedDuplexOriginals(root,actor,()->{}).query(ID,CHALLENGE));
        }
    }
    static void configurationReceiptControls() throws Exception {
        Actor actor=new Actor();
        for(String operation:new String[]{"concurrent_arm","policy_update","blend_update"}) {
            File root=Files.createTempDirectory("original-config-"+operation).toFile();
            EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});
            JSONObject wrapper=new JSONObject().put("schema",operation.equals("blend_update")
                    ?"rusty.quest.stereo.neutral_mask_receipt.v1":"rusty.quest.stereo.concurrent_qualification_receipt.v1")
                    .put("action",operation.equals("concurrent_arm")?"arm":operation).put("challenge",CHALLENGE)
                    .put("process_epoch_id","app-process.7.fixture").put("runtime_config_sha256",HASH)
                    .put("feature_lock_sha256",HASH).put("installed_apk_sha256",HASH);
            Bundle actual=response("active");actual.putString("receipt",wrapper.toString());
            JSONObject input=EmbeddedDuplexOriginals.request(operation,CHALLENGE,new Bundle());
            Bundle completed=store.begin(ID,operation,CHALLENGE,input,()->{
                CompletableFuture<Bundle> result=new CompletableFuture<>();
                EmbeddedDuplexOriginals.carry(()->{
                    try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),actor.appGeneration());result.complete(actual);}
                    catch(Exception failure){throw new IllegalStateException(failure);}
                }).run();return result;
            });
            yes("observed".equals(completed.getString("status")));
            yes("compacted".equals(store.acknowledge(ID,CHALLENGE,requestHash(completed),outcome(completed),HASH).getString("status")));
        }
    }
    static Bundle responseUnchecked(String state) {
        try{return response(state);}catch(Exception failure){throw new IllegalStateException(failure);}
    }
    static void productionCallerControls() throws Exception {
        // Defined by the bounded runner from exact production submit/actor/facade bodies.
        java.nio.file.Path root=Files.createTempDirectory("original-real-caller-");
        Files.createDirectory(root.resolve("embedded-duplex-replay"));
        android.content.Context context=new android.content.Context(){public File getNoBackupFilesDir(){return root.toFile();}};
        EmbeddedDuplexProcessHost host=EmbeddedDuplexProcessHost.forApplication(context);
        host.harnessReady(HASH,9);
        java.util.concurrent.CountDownLatch enter=new java.util.concurrent.CountDownLatch(1), finish=new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        @SuppressWarnings("unchecked") CompletableFuture<Bundle>[] actual=new CompletableFuture[1];
        Bundle start=EmbeddedDuplexRuntimeService.beginOriginal(context,ID,"start",CHALLENGE,request(),()->{
            actual[0]=host.harnessSubmit(()->{
                calls.incrementAndGet();enter.countDown();
                if(!finish.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("caller release timed out");
                return response("active");
            });return actual[0];
        });
        yes(enter.await(5,java.util.concurrent.TimeUnit.SECONDS));
        yes("pending".equals(start.getString("status")));
        EmbeddedDuplexRuntimeService.beginOriginal(context,ID,"start",CHALLENGE,request(),()->{throw new AssertionError("facade duplicate replayed");});
        Bundle during=EmbeddedDuplexRuntimeService.queryOriginal(context,ID,CHALLENGE);
        yes(observe(during).getJSONObject("actor_binding").getLong("native_generation")==9);
        yes(observe(during).getJSONObject("actor_binding").getString("app_record_sha256").equals(HASH));
        denied(()->EmbeddedDuplexRuntimeService.acknowledgeOriginal(context,ID,CHALLENGE,requestHash(during),HASH,HASH));
        finish.countDown();actual[0].get(5,java.util.concurrent.TimeUnit.SECONDS);
        host.harnessSubmit(()->null).get(5,java.util.concurrent.TimeUnit.SECONDS); // Join carry's finally on the real single lane.
        Bundle completed=EmbeddedDuplexRuntimeService.queryOriginal(context,ID,CHALLENGE);
        yes("observed".equals(completed.getString("status"))&&calls.get()==1);
        host.harnessNativeGeneration(10);
        denied(()->EmbeddedDuplexRuntimeService.acknowledgeOriginal(context,ID,CHALLENGE,requestHash(completed),outcome(completed),HASH));
        host.harnessNativeGeneration(9);
        host.harnessDropResources();
        denied(()->EmbeddedDuplexRuntimeService.acknowledgeOriginal(context,ID,CHALLENGE,requestHash(completed),outcome(completed),HASH));
        host.harnessReady(HASH,9);
        java.nio.file.Path stat=java.nio.file.Path.of(System.getProperty("host.proc"),"42","stat");
        String birth=Files.readString(stat);
        java.nio.file.Path companion=root.resolve("embedded-duplex-replay/process-incarnation.v1.json");
        yes(Files.isRegularFile(companion)); // The actual carried submit must have stamped its process identity.
        byte[] bound=Files.readAllBytes(companion);
        Files.writeString(stat,birth.replace("123456","123457"));
        denied(()->EmbeddedDuplexRuntimeService.acknowledgeOriginal(context,ID,CHALLENGE,requestHash(completed),outcome(completed),HASH));
        yes(java.util.Arrays.equals(bound,Files.readAllBytes(companion))); // A refused ACK must never stamp a replacement birth.
        Files.writeString(stat,birth);
        Bundle ack=EmbeddedDuplexRuntimeService.acknowledgeOriginal(context,ID,CHALLENGE,requestHash(completed),outcome(completed),HASH);
        yes("compacted".equals(ack.getString("status")));
        host.harnessClose();
    }

 static JSONObject freshContractLifecycle()throws Exception{
  JSONObject v=new JSONObject("{\"$schema\":\"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4\",\"action\":\"peer_status\",\"config_sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"native_executor_generation\":9,\"app_process_generation\":7,\"observed_at_ms\":0,\"status\":\"active\",\"authority_projection\":null,\"broker_evidence\":null,\"start_mutation\":null,\"stop_mutation\":null,\"route_receipt\":null,\"route_termination\":null,\"route_cleanup\":[],\"termination_action\":null,\"revoker_adoption\":null,\"media_completion\":null,\"media_stop_effect_receipt\":null,\"owner_failure_diagnostic\":null,\"owner_failure_diagnostic_status\":null,\"native_owner_dispatch_failure\":null,\"activation\":null,\"renewal_receipts\":[],\"renewal_total_completed\":0,\"renewal_first_request_id\":null,\"renewal_last_request_id\":null,\"renewal_pending\":false,\"peer_physical_cleanup\":\"pending\",\"native_host_physical_cleanup\":\"pending\",\"whole_app_physical_cleanup\":\"pending\",\"last_failure\":null,\"last_failure_action\":null,\"ongoing_media_policy\":{\"$schema\":\"rusty.manifold.peer.ongoing_media_policy.v2\"},\"current_lifetime_renewal_count\":0,\"current_owner_validation\":null,\"last_failure_cause\":null,\"retired_lifetimes\":{\"total\":0,\"first\":null,\"last\":null,\"recent\":[],\"history_sha256\":null}}");
  return v;
 }

 static void freshContractControls()throws Exception{
  EmbeddedDuplexFreshLifecycle.require(freshContractLifecycle());checks++;
  denied(()->EmbeddedDuplexFreshLifecycle.require(freshContractLifecycle().put("native_executor_generation",0)));
  denied(()->EmbeddedDuplexFreshLifecycle.require(freshContractLifecycle().put("owner_transport_attempt",JSONObject.NULL)));
  denied(()->EmbeddedDuplexFreshLifecycle.require(freshContractLifecycle().put("authority_clock_read",JSONObject.NULL)));
  JSONObject noArchive=freshContractLifecycle();noArchive.remove("retired_lifetimes");denied(()->EmbeddedDuplexFreshLifecycle.require(noArchive));
  JSONObject wrongPolicy=freshContractLifecycle();wrongPolicy.getJSONObject("ongoing_media_policy").put("$schema","rusty.manifold.peer.ongoing_media_policy.v1");denied(()->EmbeddedDuplexFreshLifecycle.require(wrongPolicy));
  JSONObject falseArchive=freshContractLifecycle();falseArchive.getJSONObject("retired_lifetimes").put("total",1);denied(()->EmbeddedDuplexFreshLifecycle.require(falseArchive));
  String unknown="{\"$schema\":\"rusty.quest.embedded_duplex.operation_failure.v1\",\"code\":\"rejected_unknown\",\"operation\":\"fresh_pair_setup\",\"session_id\":null,\"observed_at_ms\":null,\"current_session\":null}";
  new EmbeddedDuplexNativeOperationFailure(unknown).validatedFailure();checks++;
  denied(()->new EmbeddedDuplexNativeOperationFailure(new JSONObject(unknown).put("session_id","invented").toString()).validatedFailure());
  denied(()->new EmbeddedDuplexNativeOperationFailure(new JSONObject(unknown).put("code","invented").toString()).validatedFailure());
 }
    static void ceremonyWaitWarningCompletion()throws Exception{
        for(String operation:new String[]{"pair_session","fresh_pair_continue"})for(Object warning:new Object[]{null,Boolean.TRUE,Boolean.FALSE,"true",1}){
            File root=Files.createTempDirectory("original-ceremony-warning-").toFile();Actor actor=new Actor();
            EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});Bundle response=new Bundle();
            response.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");response.putString("status","observed");response.putString("challenge",CHALLENGE);
            response.putString("pair_state","peer_session_current_route_unverified");response.put("local_session_current",true);response.put("remote_session_current",true);
            response.put("local_session_expires_at_ms",1000L);response.put("peer_route_proven",false);response.put("media_effect_proven",false);
            if(warning instanceof Boolean)response.put("ceremony_wait_warning",(Boolean)warning);else if(warning instanceof String)response.putString("ceremony_wait_warning",(String)warning);else if(warning instanceof Integer)response.put("ceremony_wait_warning",(Integer)warning);
            JSONObject request=EmbeddedDuplexOriginals.request(operation,CHALLENGE,new Bundle());boolean accepted=warning==null||warning instanceof Boolean;
            if(!accepted){denied(()->{store.begin(ID,operation,CHALLENGE,request,()->CompletableFuture.completedFuture(response));store.query(ID,CHALLENGE);});continue;}
            store.begin(ID,operation,CHALLENGE,request,()->CompletableFuture.completedFuture(response));JSONObject row=observe(store.query(ID,CHALLENGE));
            yes(row.getString("state").equals("succeeded"));
            if(accepted){yes(row.getJSONObject("response").has("ceremony_wait_warning")== (warning!=null));if(warning!=null)yes(row.getJSONObject("response").getBoolean("ceremony_wait_warning")==((Boolean)warning));}
        }
    }
    static Bundle modeResponse(String operation)throws Exception{
        Bundle value=response("active");
        value.putString("receipt",new JSONObject().put("schema",operation.equals("blend_update")?
                "rusty.quest.stereo.neutral_mask_receipt.v1":"rusty.quest.stereo.concurrent_qualification_receipt.v1")
                .put("action",operation).put("challenge",CHALLENGE).toString());return value;
    }
    static void completionObservationControls()throws Exception{
        for(String operation:new String[]{"policy_update","blend_update"}){
            File root=Files.createTempDirectory("original-completion-").toFile();Actor actor=new Actor();
            java.util.concurrent.atomic.AtomicBoolean failSync=new java.util.concurrent.atomic.AtomicBoolean();
            EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{if(failSync.get())throw new java.io.IOException("sync unavailable");});
            AtomicInteger effects=new AtomicInteger();CompletableFuture<Bundle> actual=new CompletableFuture<>();
            JSONObject request=EmbeddedDuplexOriginals.request(operation,CHALLENGE,new Bundle());
            Bundle registered=store.begin(ID,operation,CHALLENGE,request,()->{
                effects.incrementAndGet();
                try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),actor.generation);}
                catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
                return actual;});
            yes("pending".equals(registered.getString("status")));
            CompletableFuture<Bundle> observation=store.observeCompletion(ID,CHALLENGE);
            yes(!observation.isDone());
            try{observation.get(0,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("pending became terminal");}
            catch(java.util.concurrent.TimeoutException expected){}
            yes(!actual.isCancelled()&&effects.get()==1);
            actual.complete(modeResponse(operation));Bundle terminal=observation.get();
            yes("observed".equals(terminal.getString("status")));
            JSONObject disk=EmbeddedDuplexCanonicalJson.readOwned(Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath()));
            yes(EmbeddedDuplexCanonicalJson.sameValue(disk,observe(terminal)));
            store.begin(ID,operation,CHALLENGE,request,()->{throw new AssertionError("duplicate effect");});
            yes(effects.get()==1);
            // Observation is not consumer/ACK credit; actual ACK and independent query remain separate.
            actor.retained=false;denied(()->store.acknowledge(ID,CHALLENGE,requestHash(terminal),outcome(terminal),HASH));actor.retained=true;
            store.acknowledge(ID,CHALLENGE,requestHash(terminal),outcome(terminal),HASH);
            yes("compacted".equals(store.query(ID,CHALLENGE).getString("status")));

            File pendingRoot=Files.createTempDirectory("original-observer-cancel-").toFile();
            EmbeddedDuplexOriginals pending=new EmbeddedDuplexOriginals(pendingRoot,actor,()->{});
            CompletableFuture<Bundle> work=new CompletableFuture<>();pending.begin(ID,operation,CHALLENGE,request,()->work);
            CompletableFuture<Bundle> cancelled=pending.observeCompletion(ID,CHALLENGE);yes(cancelled.cancel(false));
            yes(!work.isCancelled());work.complete(modeResponse(operation));
            yes("observed".equals(pending.query(ID,CHALLENGE).getString("status")));

            EmbeddedDuplexOriginals uncertain=new EmbeddedDuplexOriginals(Files.createTempDirectory("original-actual-cancel-").toFile(),actor,()->{});
            CompletableFuture<Bundle> cancelledWork=new CompletableFuture<>();uncertain.begin(ID,operation,CHALLENGE,request,()->cancelledWork);
            CompletableFuture<Bundle> uncertainObservation=uncertain.observeCompletion(ID,CHALLENGE);cancelledWork.cancel(false);
            yes("unknown".equals(uncertainObservation.get().getString("status")));

            File restoredRoot=Files.createTempDirectory("original-completion-restored-").toFile();
            EmbeddedDuplexOriginals live=new EmbeddedDuplexOriginals(restoredRoot,actor,()->{});
            live.begin(ID,operation,CHALLENGE,request,CompletableFuture::new);
            yes("unknown".equals(new EmbeddedDuplexOriginals(restoredRoot,actor,()->{}).observeCompletion(ID,CHALLENGE).get().getString("status")));

            EmbeddedDuplexOriginals broken=new EmbeddedDuplexOriginals(Files.createTempDirectory("original-completion-sync-").toFile(),actor,
                ()->{if(failSync.get())throw new java.io.IOException("actual terminal sync unavailable");});
            CompletableFuture<Bundle> unsynced=new CompletableFuture<>();broken.begin(ID,operation,CHALLENGE,request,()->unsynced);
            CompletableFuture<Bundle> brokenObservation=broken.observeCompletion(ID,CHALLENGE);failSync.set(true);unsynced.complete(modeResponse(operation));
            denied(()->brokenObservation.get());denied(()->broken.query(ID,CHALLENGE));
        }
        System.out.println("PASS actual Original completion observation: persisted policy/mask outcomes, pending, timeout, restored uncertainty, cancellation, sync failure, actor/ACK and no replay; checks="+checks);
    }
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--completion-only")){completionObservationControls();return;}
        ceremonyWaitWarningCompletion();
        File root=Files.createTempDirectory("original-custody-").toFile();Actor actor=new Actor();
        EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});
        AtomicInteger calls=new AtomicInteger();CompletableFuture<Bundle> actual=new CompletableFuture<>();Runnable[] command=new Runnable[1];
        Bundle initial=store.begin(ID,"start",CHALLENGE,request(),()->{
            calls.incrementAndGet();command[0]=EmbeddedDuplexOriginals.carry(()->{
                try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),actor.appGeneration());actual.complete(response("active"));
                    Bundle mid=store.query(ID,CHALLENGE);denied(()->store.acknowledge(ID,CHALLENGE,requestHash(mid),outcome(mid),HASH));
                }catch(Exception failure){throw new IllegalStateException(failure);}
            });return actual;
        });
        yes("pending".equals(initial.getString("status"))&&calls.get()==1);
        store.begin(ID,"start",CHALLENGE,request(),()->{throw new AssertionError("duplicate executed");});
        denied(()->store.acknowledge(ID,CHALLENGE,requestHash(initial),HASH,HASH));
        command[0].run();Bundle completed=store.query(ID,CHALLENGE);yes("observed".equals(completed.getString("status")));
        actor.retained=false;denied(()->store.acknowledge(ID,CHALLENGE,requestHash(completed),outcome(completed),HASH));actor.retained=true;
        denied(()->store.acknowledge(ID,CHALLENGE,HASH,outcome(completed),HASH));
        Bundle ack=store.acknowledge(ID,CHALLENGE,requestHash(completed),outcome(completed),HASH);yes("compacted".equals(ack.getString("status")));
        yes("compacted".equals(store.acknowledge(ID,CHALLENGE,requestHash(completed),outcome(completed),HASH).getString("status")));
        denied(()->store.acknowledge(ID,CHALLENGE,requestHash(completed),outcome(completed),"44".repeat(32)));
        denied(()->store.begin(ID,"start",CHALLENGE,request().put("extras",new JSONObject().put("changed",true)),()->actual));
        denied(()->store.query("../bad",CHALLENGE));

        File pendingRoot=Files.createTempDirectory("original-pending-").toFile();EmbeddedDuplexOriginals pending=new EmbeddedDuplexOriginals(pendingRoot,actor,()->{});
        pending.begin(ID,"start",CHALLENGE,request(),()->{
            CompletableFuture<Bundle> done=new CompletableFuture<>();Runnable r=EmbeddedDuplexOriginals.carry(()->{
                try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),actor.appGeneration());done.complete(response("pending"));}catch(Exception e){throw new IllegalStateException(e);}
            });r.run();return done;
        });
        Bundle uncertain=pending.query(ID,CHALLENGE);yes("unknown".equals(uncertain.getString("status")));
        denied(()->pending.acknowledge(ID,CHALLENGE,requestHash(uncertain),outcome(uncertain),HASH));
        EmbeddedDuplexOriginals restored=new EmbeddedDuplexOriginals(pendingRoot,actor,()->{});
        restored.begin(ID,"start",CHALLENGE,request(),()->{throw new AssertionError("restored operation replayed");});
        yes("unknown".equals(restored.query(ID,CHALLENGE).getString("status")));

        File incompleteRoot=Files.createTempDirectory("original-incomplete-").toFile();EmbeddedDuplexOriginals incomplete=new EmbeddedDuplexOriginals(incompleteRoot,actor,()->{});
        incomplete.begin(ID,"start",CHALLENGE,request(),CompletableFuture::new);
        File row=new File(incompleteRoot,"operator-originals-v1/"+ID+".json");byte[] before=Files.readAllBytes(row.toPath());
        EmbeddedDuplexOriginals afterCrash=new EmbeddedDuplexOriginals(incompleteRoot,actor,()->{});
        yes("unknown".equals(afterCrash.query(ID,CHALLENGE).getString("status")));
        yes(java.util.Arrays.equals(before,Files.readAllBytes(row.toPath())));
        afterCrash.begin(ID,"start",CHALLENGE,request(),()->{throw new AssertionError("uncertain factory replayed");});
        Files.createFile(new File(row.getPath()+".tmp").toPath());denied(()->new EmbeddedDuplexOriginals(incompleteRoot,actor,()->{}).query(ID,CHALLENGE));
        Bundle floats=new Bundle();floats.putFloat("threshold",0.25f);floats.putString("original_id",ID);
        JSONObject hashed=EmbeddedDuplexOriginals.request("blend_update",CHALLENGE,floats);
        yes(hashed.getJSONObject("extras").getInt("threshold")==Float.floatToRawIntBits(0.25f));
        yes(!hashed.getJSONObject("extras").has("original_id"));
        // A current no-media whole close is observable but gains no new ACK shortcut.
        Actor noMediaActor = new Actor();
        EmbeddedDuplexOriginals noMedia = new EmbeddedDuplexOriginals(Files.createTempDirectory("original-no-media-").toFile(),noMediaActor,()->{});
        Bundle noMediaResponse = response("terminal");
        JSONObject wrapper = new JSONObject(noMediaResponse.getString("receipt"));
        wrapper.put("action","whole_app_close").put("peer_lifecycle",new JSONObject()
                .put("$schema","rusty.quest.embedded_duplex.concurrent_no_media_own_cleanup.v1")
                .put("action","whole_app_close").put("whole_app_physical_cleanup","terminal"));
        noMediaResponse.putString("receipt",wrapper.toString());
        JSONObject noMediaRequest = EmbeddedDuplexOriginals.request("whole_app_close",CHALLENGE,new Bundle());
        noMedia.begin(ID,"whole_app_close",CHALLENGE,noMediaRequest,()->{
            try { EmbeddedDuplexOriginals.bindActualActor(noMediaActor.snapshot(),noMediaActor.appGeneration()); }
            catch(Exception e){throw new IllegalStateException(e);}
            return CompletableFuture.completedFuture(noMediaResponse);
        });
        Bundle noMediaObservation = noMedia.query(ID,CHALLENGE);
        yes("unknown".equals(noMediaObservation.getString("status")));
        yes(!observe(noMediaObservation).isNull("response_sha256"));
        noMedia.begin(ID,"whole_app_close",CHALLENGE,noMediaRequest,()->{throw new AssertionError("no-media duplicate replayed");});
        denied(()->noMedia.acknowledge(ID,CHALLENGE,requestHash(noMediaObservation),outcome(noMediaObservation),HASH));
        freshContractControls();
        failureAndPendingControls();
        configurationReceiptControls();
        productionCallerControls();
        appAdmissionControls();
        failureV2Controls();
        System.out.println("Production Original custody controls passed: "+checks+"; exact Host submit/actor and RuntimeService Original facade bodies executed; OS/Native/resource leaves modeled.");
    }
}
