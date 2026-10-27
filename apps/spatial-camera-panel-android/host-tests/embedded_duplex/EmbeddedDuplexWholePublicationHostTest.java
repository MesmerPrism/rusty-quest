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
        held.config=HASH;held.appRecord=HASH;held.appGeneration=7;held.nativeGeneration=9;held.pendingFresh=true;
        held.platform=new EmbeddedDuplexProcessHost.PhysicalPlatform();held.proof=new JSONObject(receipt.toString());
        // Fixture setup supplies constructor witness data. The production Context.require and requireNative execute unchanged.
        var constructor=EmbeddedDuplexRuntimeConfigurationBinding.Context.class.getDeclaredConstructor(JSONObject.class);constructor.setAccessible(true);
        held.configurationContext=constructor.newInstance(receipt.getJSONObject("native_retirement").getJSONObject("configuration_binding"));
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
    public static void main(String[] args)throws Exception{
        directWholeControls();invalidDirectControls();wrapperControls();
        System.out.println("Whole publication controls passed: "+checks+"; actual complete/store/retrieval and exact Host predicates; physical observations modeled.");
    }
}
