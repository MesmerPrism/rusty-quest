package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Actual complete/store/query code and exact Host predicates; physical observations are modeled. */
public final class EmbeddedDuplexWholePublicationHostTest {
    static final String ID="11".repeat(16), CHALLENGE="22".repeat(16), HASH="33".repeat(32);
    static int checks;
    static void yes(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Checked {void run()throws Exception;}
    static void denied(Checked action)throws Exception{checks++;try{action.run();}catch(Exception expected){return;}throw new AssertionError("expected refusal");}
    static JSONObject observation(Bundle value)throws Exception{return new JSONObject(value.getString("observation_json"));}
    static Bundle response(JSONObject receipt){
        Bundle value=new Bundle();value.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");
        value.putString("status","observed");value.putString("challenge",CHALLENGE);value.putString("receipt",receipt.toString());return value;
    }
    static JSONObject whole()throws Exception{
        JSONObject policy=new JSONObject("{\"$schema\":\"rusty.manifold.peer.fresh_pair_setup_policy.v2\",\"policy_id\":\"policy.quest.embedded-duplex.fresh-setup.v2\",\"revision\":1,\"authorization\":\"source_bound\",\"credential_validity_ms\":300000,\"initial_status\":{\"$schema\":\"rusty.manifold.peer.fresh_pair_setup_initial_status_template.v1\",\"trust_domain\":\"trust.morphospace.peer\",\"roles\":[\"observer\",\"rendezvous\"],\"capability_ids\":[\"capability.rendezvous.ble\",\"capability.route.rust-direct-p2p\",\"capability.topology.wifi-direct\"]}}");
        JSONObject binding=new JSONObject().put("$schema","rusty.quest.embedded_duplex.runtime_configuration_binding.v1")
            .put("config_sha256",HASH).put("app_process_generation",7).put("app_record_sha256",HASH)
            .put("native_executor_generation",9).put("local_peer_id","fixture.peer")
            .put("fresh_setup_build_policy",new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_setup_build_policy.v1")
                .put("selection","source-bound-v2").put("policy",policy).put("policy_sha256","sha256:"+HASH).put("enrollment_selection_sha256","sha256:"+HASH));
        JSONObject ack=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_setup_closure_retained.v1")
            .put("body_sha256","sha256:"+HASH).put("storage_identity","fixture.closure")
            .put("original_binding",new JSONObject().put("kind","operator").put("original_id","44".repeat(16)))
            .put("app_process_generation",7).put("app_record_sha256",HASH).put("native_executor_generation",9).put("config_sha256",HASH);
        JSONObject nativeReceipt=new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_retirement.v2")
            .put("native_runtime_binding","configured").put("disposition","pending_fresh_local_closed")
            .put("app_process_generation",7).put("app_record_sha256",HASH).put("native_executor_generation",9)
            .put("config_sha256",HASH).put("configuration_binding",binding).put("transaction_outcome","unknown")
            .put("durable_closure",ack).put("future_writable_restore","fenced_pending_supported_owner_reconciliation");
        JSONObject value=new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v2")
            .put("action","whole_app_close").put("app_process_generation",7).put("app_record_sha256",HASH)
            .put("process_epoch","app-process.7.fixture").put("native_retirement",nativeReceipt)
            .put("arm_observation",new JSONObject().put("binding","not_established"))
            .put("own_capture",new JSONObject().put("phase","NotConstructed").put("state","not_created").put("capture_ever_owned",false)).put("config_sha256",HASH).put("native_executor_generation",9);
        for(String key:new String[]{"java_own_capture_cleanup","java_peer_resources_cleanup","java_renderer_cleanup",
                "java_control_cleanup","java_callback_cleanup","command_lane_barrier","whole_app_physical_cleanup","app_process_fence_cleanup"})value.put(key,"terminal");
        return value;
    }
    static EmbeddedDuplexProcessHost.RetainedNoMediaClose held(JSONObject receipt)throws Exception{
        EmbeddedDuplexProcessHost.RetainedNoMediaClose held=new EmbeddedDuplexProcessHost.RetainedNoMediaClose();
        held.config=receipt.has("config_sha256")?HASH:null;held.appRecord=HASH;held.appGeneration=receipt.getLong("app_process_generation");held.nativeGeneration=9;
        held.pendingFresh=EmbeddedDuplexRuntimeConfigurationBinding.WHOLE_V2.equals(receipt.getString("$schema"));
        held.platform=new EmbeddedDuplexProcessHost.PhysicalPlatform();held.proof=new JSONObject(receipt.toString());
        // Fixture setup supplies constructor witness data. The production Context.require and requireNative execute unchanged.
        if(held.pendingFresh){
            var constructor=EmbeddedDuplexRuntimeConfigurationBinding.Context.class.getDeclaredConstructor(JSONObject.class);constructor.setAccessible(true);
            held.configurationContext=constructor.newInstance(receipt.getJSONObject("native_retirement").getJSONObject("configuration_binding"));
        }
        held.completion.complete("no-media-closed");held.callbackMarker=CompletableFuture.completedFuture(null);return held;
    }
    static void directWholeControls()throws Exception{
        JSONObject receipt=whole();File root=Files.createTempDirectory("whole-publication-").toFile();
        EmbeddedDuplexOriginalsHostTest.Actor actor=new EmbeddedDuplexOriginalsHostTest.Actor();
        EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});AtomicInteger effects=new AtomicInteger();
        CompletableFuture<Bundle> future=new CompletableFuture<>();EmbeddedDuplexOriginals.Record[] actual=new EmbeddedDuplexOriginals.Record[1];
        JSONObject request=EmbeddedDuplexOriginals.request("whole_app_close",CHALLENGE,new Bundle());
        store.begin(ID,"whole_app_close",CHALLENGE,request,()->{
            effects.incrementAndGet();actual[0]=EmbeddedDuplexOriginals.capture();
            try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),actor.appGeneration());actual[0].retainedCleanupOwner=held(receipt);}
            catch(Exception failure){throw new IllegalStateException(failure);}return future;
        });
        yes("pending".equals(store.query(ID,CHALLENGE).getString("status")),"actual future must remain pending before completion");
        future.complete(response(receipt));
        Bundle query=store.query(ID,CHALLENGE);JSONObject row=observation(query);
        yes("observed".equals(query.getString("status")),"joined direct v2 Whole must publish through complete");
        yes("succeeded".equals(row.getString("state")),"Whole observation must be persisted");
        yes("unknown".equals(new JSONObject(row.getJSONObject("response").getString("receipt")).getJSONObject("native_retirement").getString("transaction_outcome")),"physical Whole must preserve Pair uncertainty");
        yes(EmbeddedDuplexCanonicalJson.sha256(row.getJSONObject("response")).equals(row.getString("response_sha256")),"response bytes must remain bound");
        yes(actual[0].callbackJoined&&actual[0].persistenceFailure==null,"actual publication callback must join successfully");
        yes("observed".equals(new EmbeddedDuplexOriginals(root,actor,()->{}).query(ID,CHALLENGE).getString("status")),"published outcome must survive retrieval");
        store.begin(ID,"whole_app_close",CHALLENGE,request,()->{throw new AssertionError("Whole replayed");});
        yes(effects.get()==1,"query/repeat must preserve once semantics");
        // This file-only reader control supplies a modeled retained join. Actual
        // recovery/current callback joins execute in ColdPendingRecoveryHostTest.
        JSONObject join=new JSONObject().put("schema","rusty.quest.embedded_duplex.original_join.v2").put("original_id",ID).put("operation","whole_app_close")
            .put("request_sha256",row.getString("request_sha256")).put("outcome_sha256",row.getString("outcome_sha256")).put("actor_binding",row.getJSONObject("actor_binding"))
            .put("original_worker_terminal",true).put("original_callback_terminal",true).put("outbound_exchanges_joined",true).put("physical_retirement_schema",receipt.getString("$schema"))
            .put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(receipt)).put("app_process_generation",7).put("native_executor_generation",9).put("app_record_sha256",HASH)
            .put("remote_outcome_resolved",false).put("join_basis",new JSONObject().put("kind","actual_future_callback"));
        File joinFile=new File(root,"operator-originals-v1/"+ID+".joined.json");Files.writeString(joinFile.toPath(),EmbeddedDuplexCanonicalJson.encode(join));
        JSONObject handoff=EmbeddedDuplexOriginals.closedWholeHandoff(root,ID,"fixture.package");
        yes(handoff.getLong("prior_native_generation")==9,"direct configured Whole reader lost Native generation");
        yes(handoff.getString("physical_retirement_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(receipt)),"direct Whole handoff must hash the original complete proof");
        denied(()->EmbeddedDuplexOriginals.closedWholeHandoff(root,ID,"other.package"));
        JSONObject changed=new JSONObject(receipt.toString()).put("java_callback_cleanup","pending");
        JSONObject changedRow=new JSONObject(row.toString()),changedResponse=new JSONObject(row.getJSONObject("response").toString()).put("receipt",EmbeddedDuplexCanonicalJson.encode(changed));
        changedRow.put("response",changedResponse).put("response_sha256",EmbeddedDuplexCanonicalJson.sha256(changedResponse));
        JSONObject outcome=new JSONObject();for(String key:new String[]{"original_id","operation","request_sha256","original_challenge","state","effect_state","actor_binding","native_setup_binding","response_sha256","failure"})outcome.put(key,changedRow.get(key));changedRow.put("outcome_sha256",EmbeddedDuplexCanonicalJson.sha256(outcome));
        Files.writeString(new File(root,"operator-originals-v1/"+ID+".json").toPath(),EmbeddedDuplexCanonicalJson.encode(changedRow));
        join.put("outcome_sha256",changedRow.getString("outcome_sha256")).put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(changed));Files.writeString(joinFile.toPath(),EmbeddedDuplexCanonicalJson.encode(join));
        denied(()->EmbeddedDuplexOriginals.closedWholeHandoff(root,ID,"fixture.package"));
    }
    static void otherDirectFamilies()throws Exception{
        for(String family:new String[]{"v1","v3"})for(String damage:new String[]{"none","barrier_pending","unrecognized","native_actor"}){
            JSONObject receipt=whole();
            EmbeddedDuplexProcessHost.RetainedNoMediaClose owner=held(receipt);
            if(family.equals("v1")){
                receipt.put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v1").remove("config_sha256");
                receipt.put("native_retirement",new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_retirement.v1")
                    .put("native_runtime_binding","not_initialized").put("disposition","uninitialized_fence_closed")
                    .put("app_process_generation",7).put("app_record_sha256",HASH).put("native_executor_generation",9));
            }else{
                receipt.put("$schema",EmbeddedDuplexConstructionClosure.WHOLE);
                receipt.put("native_retirement",new JSONObject().put("$schema",EmbeddedDuplexConstructionClosure.NATIVE)
                    .put("native_runtime_binding","partial_construction").put("disposition","partial_construction_closed")
                    .put("app_process_generation",7).put("app_record_sha256",HASH).put("native_executor_generation",9)
                    .put("config_sha256",HASH).put("construction_disposition","not_committed").put("closure_body_sha256","sha256:"+HASH)
                    .put("durable_closure",JSONObject.NULL).put("provider_entered",false).put("new_media_starts",0)
                    .put("old_outcomes_preserved",true).put("future_writable_restore","owner_factory_not_entered"));
                owner.pendingFresh=false;owner.configurationContext=null;
            }
            if(damage.equals("barrier_pending"))receipt.put("whole_app_physical_cleanup","pending");
            if(damage.equals("unrecognized"))receipt.put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v99");
            // The v1 contract validates terminality only; the v3 retained owner also validates its Native actor.
            if(damage.equals("native_actor")){
                if(family.equals("v1"))continue;
                receipt.getJSONObject("native_retirement").put("native_executor_generation",10);
            }
            owner.proof=new JSONObject(receipt.toString());
            File root=Files.createTempDirectory("whole-"+family+"-").toFile();
            EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,new EmbeddedDuplexOriginalsHostTest.Actor(),()->{});
            CompletableFuture<Bundle> future=new CompletableFuture<>();EmbeddedDuplexOriginals.Record[] actual=new EmbeddedDuplexOriginals.Record[1];
            AtomicInteger effects=new AtomicInteger();JSONObject request=EmbeddedDuplexOriginals.request("whole_app_close",CHALLENGE,new Bundle());
            store.begin(ID,"whole_app_close",CHALLENGE,request,()->{
                effects.incrementAndGet();actual[0]=EmbeddedDuplexOriginals.capture();actual[0].retainedCleanupOwner=owner;return future;
            });
            byte[] pending=Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath());
            future.complete(response(receipt));
            if(!damage.equals("none")){
                denied(()->store.query(ID,CHALLENGE));
                yes(actual[0].persistenceFailure!=null&&actual[0].callbackJoined,"bad direct family must refuse publication: "+family+damage);
                yes(java.util.Arrays.equals(pending,Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath())),"failed publication rewrote pending checkpoint");
                continue;
            }
            Bundle query=store.query(ID,CHALLENGE);JSONObject row=observation(query);
            yes("observed".equals(query.getString("status"))&&"succeeded".equals(row.getString("state")),"direct "+family+" Whole must publish through production complete");
            yes(actual[0].persistenceFailure==null&&actual[0].callbackJoined,"direct "+family+" callback must publish and join");
            yes(EmbeddedDuplexCanonicalJson.sha256(row.getJSONObject("response")).equals(row.getString("response_sha256")),"direct receipt bytes must remain bound");
            byte[] published=Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath());
            yes("observed".equals(new EmbeddedDuplexOriginals(root,new EmbeddedDuplexOriginalsHostTest.Actor(),()->{}).query(ID,CHALLENGE).getString("status")),"direct family retrieval must survive process-local owner loss");
            yes(java.util.Arrays.equals(published,Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath())),"retrieval rewrote published checkpoint");
            store.begin(ID,"whole_app_close",CHALLENGE,request,()->{throw new AssertionError("Whole replayed");});
            yes(effects.get()==1,"direct family query/repeat must preserve once semantics");
        }
    }
    static void invalidDirectControls()throws Exception{
        for(String damage:new String[]{"owner_absent","callback_pending","callback_failed","outbound_pending","barrier_pending","native_actor","constructor_binding","unrecognized"}){
            JSONObject receipt=whole();EmbeddedDuplexProcessHost.RetainedNoMediaClose owner=held(receipt);
            if(damage.equals("callback_pending"))owner.callbackMarker=new CompletableFuture<>();
            if(damage.equals("callback_failed")){owner.callbackMarker=new CompletableFuture<>();owner.callbackMarker.completeExceptionally(new IllegalStateException("fixture"));}
            if(damage.equals("outbound_pending"))owner.platform.joined=false;
            if(damage.equals("barrier_pending"))receipt.put("java_renderer_cleanup","pending");
            if(damage.equals("native_actor"))receipt.getJSONObject("native_retirement").put("native_executor_generation",10);
            if(damage.equals("constructor_binding"))receipt.getJSONObject("native_retirement").getJSONObject("configuration_binding").put("local_peer_id","fixture.other");
            if(damage.equals("unrecognized"))receipt.put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v99");
            owner.proof=new JSONObject(receipt.toString());
            File root=Files.createTempDirectory("whole-invalid-").toFile();
            EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,new EmbeddedDuplexOriginalsHostTest.Actor(),()->{});
            CompletableFuture<Bundle> future=new CompletableFuture<>();EmbeddedDuplexOriginals.Record[] actual=new EmbeddedDuplexOriginals.Record[1];
            store.begin(ID,"whole_app_close",CHALLENGE,EmbeddedDuplexOriginals.request("whole_app_close",CHALLENGE,new Bundle()),()->{
                actual[0]=EmbeddedDuplexOriginals.capture();if(!damage.equals("owner_absent"))actual[0].retainedCleanupOwner=owner;return future;
            });future.complete(response(receipt));denied(()->store.query(ID,CHALLENGE));
            yes(actual[0].callbackJoined&&actual[0].persistenceFailure!=null,"unsupported/incomplete direct result must fail publication: "+damage);
            byte[] retained=Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath());
            Bundle restored=new EmbeddedDuplexOriginals(root,new EmbeddedDuplexOriginalsHostTest.Actor(),()->{}).query(ID,CHALLENGE);
            yes("unknown".equals(restored.getString("status")),"reloaded incomplete publication must remain UNKNOWN");
            yes(java.util.Arrays.equals(retained,Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath())),"readback must preserve failed publication bytes");
        }
    }
    static void wrapperControls()throws Exception{
        for(String state:new String[]{"pending","incomplete_whole","renewal_pending","terminal"}){
            JSONObject nativeReceipt=EmbeddedDuplexOriginalsHostTest.freshContractLifecycle()
                .put("action","whole_app_close").put("status",state.equals("pending")?"pending":"terminal")
                .put("renewal_pending",state.equals("renewal_pending")).put("whole_app_physical_cleanup",state.equals("incomplete_whole")?"pending":"terminal");
            JSONObject wrapper=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1")
                .put("action","whole_app_close").put("challenge",CHALLENGE).put("peer_lifecycle",nativeReceipt);
            EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(Files.createTempDirectory("whole-wrapper-").toFile(),new EmbeddedDuplexOriginalsHostTest.Actor(),()->{});
            store.begin(ID,"whole_app_close",CHALLENGE,EmbeddedDuplexOriginals.request("whole_app_close",CHALLENGE,new Bundle()),()->CompletableFuture.completedFuture(response(wrapper)));
            Bundle query=store.query(ID,CHALLENGE);
            yes((state.equals("terminal")?"observed":"unknown").equals(query.getString("status")),"wrapper pending/Whole checks must remain intact: "+state);
            if(!state.equals("terminal")){JSONObject row=observation(query);denied(()->store.acknowledge(ID,CHALLENGE,row.getString("request_sha256"),row.getString("outcome_sha256"),HASH));}
        }
    }
    static void noMediaAcknowledgementControls()throws Exception{
        for(String family:new String[]{"v1","v2","v3"}){
            File root=Files.createTempDirectory("whole-ack-"+family+"-").toFile();
            android.content.Context context=new android.content.Context(){public File getNoBackupFilesDir(){return root;}};
            var singleton=EmbeddedDuplexProcessHost.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
            EmbeddedDuplexProcessHost host=EmbeddedDuplexProcessHost.forApplication(context);
            try{
                host.harnessSubmit(()->null).get();long generation=EmbeddedDuplexProcessHost.currentAppGeneration();
                var fenceField=EmbeddedDuplexProcessHost.class.getDeclaredField("processFence");fenceField.setAccessible(true);
                ((EmbeddedDuplexProcessFence)fenceField.get(host)).bindIncarnation(
                    EmbeddedDuplexProcessIncarnation.current(android.os.Process.myPid(),android.os.Process.myUid()));
                JSONObject receipt=whole().put("app_process_generation",generation);
                JSONObject nativeProof=receipt.getJSONObject("native_retirement").put("app_process_generation",generation);
                nativeProof.getJSONObject("configuration_binding").put("app_process_generation",generation);
                nativeProof.getJSONObject("durable_closure").put("app_process_generation",generation);
                if(family.equals("v1")){
                    receipt.put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v1").remove("config_sha256");
                    receipt.put("native_retirement",new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_retirement.v1")
                        .put("native_runtime_binding","not_initialized").put("disposition","uninitialized_fence_closed")
                        .put("app_process_generation",generation).put("app_record_sha256",HASH).put("native_executor_generation",9));
                }else if(family.equals("v3")){
                    receipt.put("$schema",EmbeddedDuplexConstructionClosure.WHOLE);
                    receipt.put("native_retirement",new JSONObject().put("$schema",EmbeddedDuplexConstructionClosure.NATIVE)
                        .put("native_runtime_binding","partial_construction").put("disposition","partial_construction_closed")
                        .put("app_process_generation",generation).put("app_record_sha256",HASH).put("native_executor_generation",9)
                        .put("config_sha256",HASH).put("construction_disposition","not_committed").put("closure_body_sha256","sha256:"+HASH)
                        .put("durable_closure",JSONObject.NULL).put("provider_entered",false).put("new_media_starts",0)
                        .put("old_outcomes_preserved",true).put("future_writable_restore","owner_factory_not_entered"));
                }
                EmbeddedDuplexProcessHost.RetainedNoMediaClose owner=held(receipt);
                EmbeddedDuplexOriginalsHostTest.Actor source=new EmbeddedDuplexOriginalsHostTest.Actor();source.generation=generation;
                EmbeddedDuplexOriginals.ActorSource actor=new EmbeddedDuplexOriginals.ActorSource(){
                    public JSONObject snapshot()throws Exception{return source.snapshot();}public long appGeneration(){return generation;}
                    public boolean settled(JSONObject value,long captured)throws Exception{return EmbeddedDuplexProcessHost.originalActorSettled(value,captured);}
                };
                EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});CompletableFuture<Bundle> future=new CompletableFuture<>();
                store.begin(ID,"whole_app_close",CHALLENGE,EmbeddedDuplexOriginals.request("whole_app_close",CHALLENGE,new Bundle()),()->{
                    owner.original=EmbeddedDuplexOriginals.capture();owner.original.retainedCleanupOwner=owner;
                    try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),generation);}catch(Exception failure){throw new IllegalStateException(failure);}
                    host.harnessNoMedia(owner);return future;
                });future.complete(response(receipt));
                JSONObject row=observation(store.query(ID,CHALLENGE));JSONObject captured=actor.snapshot();
                yes(EmbeddedDuplexProcessHost.originalActorSettled(captured,generation),"same retained no-media owner must settle "+family);
                denied(()->{if(!EmbeddedDuplexProcessHost.originalActorSettled(captured,generation+1))throw new IllegalStateException();});
                yes(!EmbeddedDuplexProcessHost.originalActorSettled(new JSONObject(captured.toString()).put("native_generation",10),generation),"changed actor must not settle");
                byte[] published=Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath());
                for(String damage:new String[]{"generation","wrong_owner","completion","completion_failed","callback","callback_failed","barrier","native_binding"}){
                    EmbeddedDuplexProcessHost.RetainedNoMediaClose changed=held(receipt);changed.original=owner.original;owner.original.retainedCleanupOwner=changed;
                    if(damage.equals("generation"))changed.appGeneration++;
                    else if(damage.equals("wrong_owner"))owner.original.retainedCleanupOwner=owner;
                    else if(damage.startsWith("completion")){
                        CompletableFuture<String> completion=new CompletableFuture<>();if(damage.endsWith("failed"))completion.completeExceptionally(new IllegalStateException("fixture"));
                        var field=changed.getClass().getDeclaredField("completion");field.setAccessible(true);field.set(changed,completion);
                    }else if(damage.equals("callback"))changed.callbackMarker=new CompletableFuture<>();
                    else if(damage.equals("callback_failed"))changed.callbackMarker=CompletableFuture.failedFuture(new IllegalStateException("fixture"));
                    else if(damage.equals("barrier"))changed.proof.put("java_renderer_cleanup","pending");
                    else changed.proof.getJSONObject("native_retirement").put("native_executor_generation",10);
                    host.harnessNoMedia(changed);
                    denied(()->store.acknowledge(ID,CHALLENGE,row.getString("request_sha256"),row.getString("outcome_sha256"),HASH));
                    yes(java.util.Arrays.equals(published,Files.readAllBytes(new File(root,"operator-originals-v1/"+ID+".json").toPath())),"refused ACK changed durable Original");
                }
                owner.original.retainedCleanupOwner=owner;host.harnessNoMedia(owner);
                Bundle ack=store.acknowledge(ID,CHALLENGE,row.getString("request_sha256"),row.getString("outcome_sha256"),HASH);
                yes("compacted".equals(ack.getString("status")),"actual ACK must compact only earned no-media owner "+family);
            }finally{host.harnessClose();singleton.set(null,null);}
        }
    }
    static void lifecycleHandoff(JSONObject receipt,JSONObject sourceResponse,String packageId,boolean captured)throws Exception{
        String id="77".repeat(16),challenge=sourceResponse.getString("challenge");
        long generation=receipt.getLong("app_process_generation"),nativeGeneration=receipt.getLong("native_executor_generation");
        String appRecord=receipt.getString("app_record_sha256");
        File root=Files.createTempDirectory("whole-lifecycle-").toFile();
        EmbeddedDuplexOriginals.ActorSource actor=new EmbeddedDuplexOriginals.ActorSource(){
            public JSONObject snapshot()throws Exception{return new JSONObject().put("package_id",packageId).put("app_record_sha256",appRecord).put("native_generation",nativeGeneration);}
            public long appGeneration(){return generation;}
            public boolean settled(JSONObject value,long actual)throws Exception{return actual==generation&&EmbeddedDuplexCanonicalJson.sameValue(value,snapshot());}
        };
        EmbeddedDuplexOriginals store=new EmbeddedDuplexOriginals(root,actor,()->{});
        EmbeddedDuplexProcessHost.RetainedNoMediaClose owner=held(receipt);
        owner.appGeneration=generation;owner.nativeGeneration=nativeGeneration;owner.appRecord=appRecord;
        owner.config=receipt.has("config_sha256")?receipt.getString("config_sha256"):null;
        CompletableFuture<Bundle> future=new CompletableFuture<>();EmbeddedDuplexOriginals.Record[] original=new EmbeddedDuplexOriginals.Record[1];Runnable[] command=new Runnable[1];
        store.begin(id,"whole_app_close",challenge,EmbeddedDuplexOriginals.request("whole_app_close",challenge,new Bundle()),()->{
            original[0]=EmbeddedDuplexOriginals.capture();owner.original=original[0];original[0].retainedCleanupOwner=owner;
            try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),generation);}catch(Exception failure){throw new IllegalStateException(failure);}
            command[0]=EmbeddedDuplexOriginals.carry(()->{
                try{store.actualClosedAppOwnerJoined(generation,nativeGeneration,appRecord,receipt,owner);}catch(Exception failure){throw new IllegalStateException(failure);}
                Bundle response=new Bundle();for(String key:sourceResponse.keySet())response.putString(key,sourceResponse.getString(key));
                future.complete(response);
                yes(!new File(root,"operator-originals-v1/latest-whole.v1.json").exists(),"command still entered cannot publish Whole handoff");
            });return future;
        });
        command[0].run();
        yes(original[0].joinPublicationFailure==null,"actual pending callback must wait without poisoning join publication");
        yes(new File(root,"operator-originals-v1/"+id+".joined.json").isFile(),"real finite completion/command return must publish join");
        JSONObject handoff=EmbeddedDuplexOriginals.latestWholeHandoff(root,packageId);
        yes(handoff!=null&&handoff.getLong("prior_native_generation")==nativeGeneration,"latest Whole must survive file-only reload");
        yes(EmbeddedDuplexCanonicalJson.sameValue(handoff.getJSONObject("physical_retirement"),receipt),"complete captured physical DTO changed");
        yes(handoff.getString("physical_retirement_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(receipt)),"exact complete proof digest lost");
        JSONObject row=observation(store.query(id,challenge));
        store.acknowledge(id,challenge,row.getString("request_sha256"),row.getString("outcome_sha256"),HASH);
        yes(EmbeddedDuplexCanonicalJson.sameValue(handoff,EmbeddedDuplexOriginals.latestWholeHandoff(root,packageId)),"ACK must preserve joined handoff bytes");
        denied(()->EmbeddedDuplexOriginals.closedWholeHandoff(root,id,"other.package"));
        readerNegatives(root,id,packageId);
        if(captured){
            File exported=new File(System.getProperty("host.handoff.output"));
            Files.writeString(exported.toPath(),EmbeddedDuplexCanonicalJson.encode(handoff));
            System.out.println("Captured response exact physical receipt roundtrip passed; new modeled lifecycle only: "+exported);
        }
    }
    static void readerNegatives(File root,String id,String packageId)throws Exception{
        File rowFile=new File(root,"operator-originals-v1/"+id+".json"),joinFile=new File(root,"operator-originals-v1/"+id+".joined.json");
        byte[] rowBytes=Files.readAllBytes(rowFile.toPath()),joinBytes=Files.readAllBytes(joinFile.toPath());
        var outcome=EmbeddedDuplexOriginals.class.getDeclaredMethod("outcome",JSONObject.class);outcome.setAccessible(true);
        for(String damage:new String[]{"java_own_capture_cleanup","java_peer_resources_cleanup","java_renderer_cleanup","java_control_cleanup","java_callback_cleanup","command_lane_barrier","whole_app_physical_cleanup","app_process_fence_cleanup","schema","native_schema","native_owner","malformed"}){
            JSONObject row=new JSONObject(new String(rowBytes,java.nio.charset.StandardCharsets.UTF_8)),join=new JSONObject(new String(joinBytes,java.nio.charset.StandardCharsets.UTF_8));
            JSONObject response=row.getJSONObject("response"),physical=new JSONObject(response.getString("receipt"));
            if(damage.equals("schema"))physical.put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v99");
            else if(damage.equals("native_schema"))physical.getJSONObject("native_retirement").put("$schema","foreign");
            else if(damage.equals("native_owner"))physical.getJSONObject("native_retirement").put("native_executor_generation",99);
            else if(damage.equals("malformed"))physical.put("native_retirement",new JSONObject());
            else physical.put(damage,"pending");
            response.put("receipt",EmbeddedDuplexCanonicalJson.encode(physical));row.put("response_sha256",EmbeddedDuplexCanonicalJson.sha256(response));
            row.put("outcome_sha256",EmbeddedDuplexCanonicalJson.sha256((JSONObject)outcome.invoke(null,row)));
            join.put("outcome_sha256",row.getString("outcome_sha256")).put("physical_retirement_schema",physical.getString("$schema")).put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(physical));
            Files.writeString(rowFile.toPath(),EmbeddedDuplexCanonicalJson.encode(row));Files.writeString(joinFile.toPath(),EmbeddedDuplexCanonicalJson.encode(join));
            denied(()->EmbeddedDuplexOriginals.closedWholeHandoff(root,id,packageId));
        }
        Files.write(rowFile.toPath(),rowBytes);Files.write(joinFile.toPath(),joinBytes);
        yes(EmbeddedDuplexOriginals.latestWholeHandoff(root,packageId)!=null,"negative controls must restore exact test fixture");
    }
    static void lifecycleControls()throws Exception{
        JSONObject v2=whole();
        lifecycleHandoff(v2,new JSONObject(response(v2)),"fixture.package",false);
        JSONObject finite=new JSONObject(v2.toString()).put("$schema","rusty.quest.embedded_duplex.no_media_whole_retirement.v1");finite.remove("config_sha256");
        finite.put("native_retirement",new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_retirement.v1")
            .put("native_runtime_binding","not_initialized").put("disposition","uninitialized_fence_closed")
            .put("app_process_generation",7).put("app_record_sha256",HASH).put("native_executor_generation",9));
        lifecycleHandoff(finite,new JSONObject(response(finite)),"fixture.package",false);
        JSONObject finiteConfigured=new JSONObject(finite.toString()).put("config_sha256",HASH);
        finiteConfigured.getJSONObject("native_retirement").put("config_sha256",HASH).put("native_runtime_binding","configured").put("disposition","runtime_config_closed")
            .put("native_close",new JSONObject().put("$schema","rusty.quest.embedded_duplex.no_media_closed.v1").put("config_sha256",HASH).put("disposition","closed"));
        lifecycleHandoff(finiteConfigured,new JSONObject(response(finiteConfigured)),"fixture.package",false);
        String capturedPath=System.getProperty("host.captured.whole");
        if(capturedPath!=null){
            byte[] bytes=Files.readAllBytes(java.nio.file.Path.of(capturedPath));JSONObject captured=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            JSONObject row=captured.has("original")?captured.getJSONObject("original"):captured;
            JSONObject response=row.getJSONObject("response"),receipt=new JSONObject(response.getString("receipt"));
            lifecycleHandoff(receipt,response,row.getJSONObject("actor_binding").getString("package_id"),true);
            yes(java.util.Arrays.equals(bytes,Files.readAllBytes(java.nio.file.Path.of(capturedPath))),"captured Original evidence must remain byte exact");
        }
    }
    public static void main(String[] args)throws Exception{
        directWholeControls();otherDirectFamilies();invalidDirectControls();wrapperControls();noMediaAcknowledgementControls();lifecycleControls();
        System.out.println("Whole publication controls passed: "+checks+"; actual complete/store/retrieval and exact Host predicates; physical observations modeled.");
    }
}
