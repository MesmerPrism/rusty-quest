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
    public static void main(String[] args)throws Exception{
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
        failureAndPendingControls();
        configurationReceiptControls();
        productionCallerControls();
        System.out.println("Production Original custody controls passed: "+checks+"; exact Host submit/actor and RuntimeService Original facade bodies executed; OS/Native/resource leaves modeled.");
    }
}
