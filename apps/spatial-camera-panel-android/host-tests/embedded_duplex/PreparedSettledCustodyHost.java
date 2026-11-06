package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.os.Bundle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Whole production owners with actual Future, ACK, immutable rows and archive I/O. */
public final class PreparedSettledCustodyHost {
    static final String CHALLENGE="d".repeat(32),PREP="1".repeat(32),STOP="2".repeat(32);
    static JSONObject request;static String requestSha;
    static int controls;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    interface Call {void run()throws Exception;}
    static void refused(Call call)throws Exception{try{call.run();throw new AssertionError("guard accepted invalid custody");}catch(IllegalStateException|IllegalArgumentException expected){}}
    static JSONObject copy(JSONObject v){return new JSONObject(v.toString());}
    static JSONObject identity(JSONObject value){
        JSONObject a=EmbeddedDuplexProcessHost.ACTOR;
        for(String k:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})value.put(k,a.get(k));
        return value;
    }
    static JSONObject arm(){return new JSONObject().put("binding","established").put("challenge",CHALLENGE)
        .put("arm_generation",1).put("native_process",73).put("process_epoch","modeled.epoch").put("runtime_config_sha256","b".repeat(64));}
    static JSONObject renderer(String id,boolean released){
        return identity(new JSONObject().put("$schema",EmbeddedDuplexPreparedRenderer.OWN).put("preparation_id",id)
            .put("request_sha256",requestSha).put("resource_target_sha256","sha256:"+"c".repeat(64)))
            .put("peer_route_generation",1).put("launch_challenge",1).put("surface_generation",1).put("layer_switch_count",1)
            .put("own_claim_generation",1).put("own_process_generation",74).put("own_source_generation",1).put("own_construction_attempts",1)
            .put("state",released?"released":"ready").put("peer_gpu_worker_created",false).put("peer_frame_import_count",0).put("peer_present_count",0)
            .put("peer_reservation_released",released).put("own_resources_retired",false).put("source_hold_acquired",true)
            .put("source_hold_released",true).put("prepare_calls_joined",true);
    }
    static JSONObject canonical(String operation)throws Exception{return EmbeddedDuplexOriginals.request(operation,CHALLENGE,new Bundle());}
    static EmbeddedDuplexOriginals.Record captureActor() {
      try {
        EmbeddedDuplexOriginals.Record actual=EmbeddedDuplexOriginals.capture();
        EmbeddedDuplexOriginals.bindActualActor(EmbeddedDuplexProcessHost.originalActor(),EmbeddedDuplexProcessHost.currentAppGeneration());
        return actual;
      }catch(Exception failure){throw new IllegalStateException(failure);}
    }
 static JSONObject lifecycle()throws Exception{
  JSONObject v=new JSONObject("{\"$schema\":\"rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4\",\"action\":\"peer_status\",\"config_sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"native_executor_generation\":9,\"app_process_generation\":7,\"observed_at_ms\":0,\"status\":\"active\",\"authority_projection\":null,\"broker_evidence\":null,\"start_mutation\":null,\"stop_mutation\":null,\"route_receipt\":null,\"route_termination\":null,\"route_cleanup\":[],\"termination_action\":null,\"revoker_adoption\":null,\"media_completion\":null,\"media_stop_effect_receipt\":null,\"owner_failure_diagnostic\":null,\"owner_failure_diagnostic_status\":null,\"native_owner_dispatch_failure\":null,\"activation\":null,\"renewal_receipts\":[],\"renewal_total_completed\":0,\"renewal_first_request_id\":null,\"renewal_last_request_id\":null,\"renewal_pending\":false,\"peer_physical_cleanup\":\"pending\",\"native_host_physical_cleanup\":\"pending\",\"whole_app_physical_cleanup\":\"pending\",\"last_failure\":null,\"last_failure_action\":null,\"ongoing_media_policy\":{\"$schema\":\"rusty.manifold.peer.ongoing_media_policy.v2\"},\"current_lifetime_renewal_count\":0,\"current_owner_validation\":null,\"last_failure_cause\":null,\"retired_lifetimes\":{\"total\":0,\"first\":null,\"last\":null,\"recent\":[],\"history_sha256\":null}}");
  return v;
 }

    static Bundle response(JSONObject receipt){Bundle b=new Bundle();b.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");b.putString("status","observed");b.putString("challenge",CHALLENGE);b.putString("receipt",receipt.toString());return b;}
    static JSONObject observation(Bundle b){return new JSONObject(b.getString("observation_json"));}
    static class Fixture {
        final Path root,originals;final Context context;final EmbeddedDuplexOriginals owner;
        final EmbeddedDuplexResources resources=new EmbeddedDuplexResources();
        final CompletableFuture<Bundle> sdk=new CompletableFuture<>();
        EmbeddedDuplexOriginals.Record actual;EmbeddedDuplexPreparedStart holder;JSONObject status;
        Fixture(Path root)throws Exception{this(root,true);}
        Fixture(Path root,boolean beginPrepare)throws Exception{
            this.root=Files.createDirectory(root);context=new Context(root.toFile());
            java.lang.reflect.Field singleton=EmbeddedDuplexOriginals.class.getDeclaredField("singleton");singleton.setAccessible(true);singleton.set(null,null);
            owner=EmbeddedDuplexOriginals.forApplication(context);originals=root.resolve("operator-originals-v1");EmbeddedDuplexProcessHost.exchanges=true;
            EmbeddedDuplexProcessHost.nativeActor=true;EmbeddedDuplexProcessHost.appRecord=true;EmbeddedDuplexProcessHost.appGeneration=74;
            if(beginPrepare)owner.begin(PREP,"prepare_peer_start",CHALLENGE,canonical("prepare_peer_start"),()->{
                actual=captureActor();return sdk;
            });
        }
        void prepare()throws Exception{
            request=identity(new JSONObject().put("$schema","rusty.quest.embedded_duplex.prepared_start_request.v1")
                .put("preparation_id","3".repeat(32)).put("original_id",PREP)).put("arm_binding",arm())
                .put("configuration_binding",new JSONObject().put("actual_constructor",true))
                .put("resource_target",resources.preparationTarget("b".repeat(64))).put("resource_target_sha256","sha256:"+"c".repeat(64));
            holder=new EmbeddedDuplexPreparedStart(resources,request,actual,EmbeddedDuplexProcessHost.ACTOR,arm(),new EmbeddedDuplexRuntimeConfigurationBinding.Context());
            JSONObject sender=holder.sender();requestSha=sender.getString("request_sha256");
            JSONObject decoderRequest=new JSONObject().put("$schema","rusty.quest.android.media.prepared_decoder_request.v1")
                .put("preparation_id",holder.id()).put("request_sha256",requestSha).put("peer_preparation_id","4".repeat(32))
                .put("peer_sender_receipt_sha256","sha256:"+"e".repeat(64)).put("peer_format",new JSONObject().put("modeled_format",true));
            JSONObject decoder=holder.decoder(decoderRequest);
            holder.finalizeTransport();check(holder.ready(),"production SDK holder was not ready");
            status=identity(new JSONObject().put("$schema","rusty.quest.embedded_duplex.prepared_peer_start_status.v1")
                .put("preparation_id",holder.id()).put("original_id",PREP)).put("request_sha256",requestSha)
                .put("resource_target_sha256","sha256:"+"c".repeat(64)).put("owner",new JSONObject()
                    .put("$schema","rusty.quest.android.media.prepared_start_owner.v1").put("request",request)
                    .put("sender_entered",true).put("decoder_entered",true).put("sender",sender)
                    .put("decoder_request",decoderRequest).put("decoder",decoder)).put("remote_decoder",decoder)
                .put("state","ready").put("local_start_consumed",false).put("media_authority_granted",false);
            holder.requireNativeStatus(status,true);
        }
        void complete()throws Exception{prepare();sdk.complete(response(status));check(actual.callbackTerminal.isDone(),"real callback not completed");}
        JSONObject ack()throws Exception{
            JSONObject q=observation(owner.query(PREP,CHALLENGE));
            return observation(owner.acknowledge(PREP,CHALLENGE,q.getString("request_sha256"),q.isNull("outcome_sha256")?"0".repeat(64):q.getString("outcome_sha256"),"f".repeat(64)));
        }
        Path settled(){return originals.resolve(PREP+".settled.json");}
        void joined()throws Exception{owner.joined(actual);}
        JSONObject stop()throws Exception{
            resources.opened=true; // Modeled successful entered media, then genuine terminal SDK leaf.
            resources.terminal=true;resources.opened=false;
            check(EmbeddedDuplexPreparedRenderer.terminal(resources.renderer(true).toString(),holder.id()),"real renderer parser did not earn terminal Own-reuse");
            JSONObject nativeStop=lifecycle().put("app_process_generation",74).put("native_executor_generation",73).put("config_sha256","b".repeat(64)).put("action","peer_stop").put("status","terminal")
                .put("peer_physical_cleanup","terminal").put("native_host_physical_cleanup","terminal");
            JSONObject wrapper=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action","peer_stop").put("challenge",CHALLENGE)
                .put("runtime_config_sha256","b".repeat(64)).put("peer_lifecycle",nativeStop);
            final EmbeddedDuplexOriginals.Record[] held=new EmbeddedDuplexOriginals.Record[1];
            owner.begin(STOP,"peer_stop",CHALLENGE,canonical("peer_stop"),()->{
                held[0]=captureActor();return CompletableFuture.completedFuture(response(wrapper));
            });
            owner.joined(held[0]);JSONObject q=observation(owner.query(STOP,CHALLENGE));
            owner.acknowledge(STOP,CHALLENGE,q.getString("request_sha256"),q.getString("outcome_sha256"),"e".repeat(64));
            return nativeStop;
        }
        JSONObject archiveBody(JSONObject stop)throws Exception{
            JSONObject current=copy(status).put("local_start_consumed",true);
            return identity(new JSONObject().put("$schema","rusty.quest.embedded_duplex.prepared_start_lifetime_archive.v1").put("original_id",PREP))
                .put("prepared_status",current).put("terminal_lifetime",new JSONObject().put("kind","normal_peer_stop").put("original_id",STOP).put("receipt",stop));
        }
    }
    static void wholeCustodyControls(Path out)throws Exception {
        Fixture pending=new Fixture(out.resolve("whole-live-unacknowledged"));pending.complete();pending.joined();
        refused(()->pending.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR));controls++;
        check(pending.actual.operationJoined&&pending.actual.future!=null,"live joined SDK was fabricated/released");
        pending.ack(); // Real producer writes settled custody then removes only its in-memory live entry.
        pending.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR);controls++;
        check(pending.actual.operationJoined,"held SDK Original lost its real join");
        byte[] settled=Files.readAllBytes(pending.settled());
        JSONObject otherApp=copy(EmbeddedDuplexProcessHost.ACTOR).put("app_process_generation",75);
        refused(()->pending.owner.requirePreparedWholeClosure(pending.status,otherApp));controls++;
        JSONObject otherNative=copy(EmbeddedDuplexProcessHost.ACTOR).put("native_executor_generation",99);
        refused(()->pending.owner.requirePreparedWholeClosure(pending.status,otherNative));controls++;
        Path hidden=pending.settled().resolveSibling("held-settled-test-only");Files.move(pending.settled(),hidden);
        try{refused(()->pending.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR));controls++;}
        finally{Files.move(hidden,pending.settled());}
        for(String field:new String[]{"app_process_generation","outcome_sha256","request_sha256","original_worker_terminal","original_callback_terminal","outbound_exchanges_joined"}){
            JSONObject bad=new JSONObject(new String(settled,java.nio.charset.StandardCharsets.UTF_8));
            bad.put(field,field.equals("app_process_generation")?75:field.endsWith("terminal")||field.endsWith("joined")?false:"0".repeat(64));
            Files.writeString(pending.settled(),bad.toString());
            try{refused(()->pending.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR));controls++;}
            finally{Files.write(pending.settled(),settled);}
        }
        for(Object generation:new Object[]{74.5,"74",true,0,-1}){
            JSONObject bad=new JSONObject(new String(settled,java.nio.charset.StandardCharsets.UTF_8)).put("app_process_generation",generation);
            Files.writeString(pending.settled(),bad.toString());
            try{refused(()->pending.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR));controls++;}
            finally{Files.write(pending.settled(),settled);}
        }
        Path row=pending.originals.resolve(PREP+".json");byte[] original=Files.readAllBytes(row);
        Files.writeString(row,new JSONObject(new String(original,java.nio.charset.StandardCharsets.UTF_8)).put("consumer_receipt_sha256",JSONObject.NULL).toString());
        try{refused(()->pending.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR));controls++;}
        finally{Files.write(row,original);}
        pending.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR);controls++;
        check(Arrays.equals(settled,Files.readAllBytes(pending.settled()))&&Arrays.equals(original,Files.readAllBytes(row)),"read-only whole custody changed durable data");
        Fixture uncertain=new Fixture(out.resolve("whole-unknown"));uncertain.sdk.completeExceptionally(new IllegalStateException("modeled retained failure"));uncertain.joined();refused(uncertain::ack);
        Files.write(uncertain.settled(),settled); // A succeeded proof cannot grant authority to an actual UNKNOWN Original.
        refused(()->uncertain.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR));controls++;
    }
    static void unknownRootControls(Path out)throws Exception {
        Fixture f=new Fixture(out.resolve("failed-root-custody"),false);
        String rootId="b".repeat(32),closeId="c".repeat(32);
        CompletableFuture<Bundle> rootFlight=new CompletableFuture<>(),closeFlight=new CompletableFuture<>();
        EmbeddedDuplexOriginals.Record[] root=new EmbeddedDuplexOriginals.Record[1],close=new EmbeddedDuplexOriginals.Record[1];
        f.owner.begin(rootId,"reestablish_peer_roots",CHALLENGE,canonical("reestablish_peer_roots"),()->{root[0]=captureActor();return rootFlight;});
        f.owner.begin(closeId,"whole_app_close",CHALLENGE,canonical("whole_app_close"),()->{close[0]=captureActor();return closeFlight;});
        JSONObject body=identity(new JSONObject().put("original_id",rootId));
        try(AutoCloseable ignored=EmbeddedDuplexOriginals.enter(close[0])) {
            UnknownRootSpanPredicate.span(close[0],true);
            refused(()->f.owner.requireUnknownPeerRootTerminal(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
            rootFlight.completeExceptionally(new IllegalStateException("modeled actual root transaction failure"));
            JSONObject q=observation(f.owner.query(rootId,CHALLENGE));byte[] durable=Files.readAllBytes(f.originals.resolve(rootId+".json"));
            check("unknown".equals(q.getString("state"))&&"uncertain".equals(q.getString("effect_state")),"actual failed root producer state changed");
            check(q.getString("outcome_sha256").equals(f.owner.requireUnknownPeerRootTerminal(body,EmbeddedDuplexProcessHost.ACTOR)),"actual failed root outcome not retained");controls++;
            UnknownRootSpanPredicate.span(close[0],false);
            refused(()->f.owner.requireUnknownPeerRootTerminal(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
            UnknownRootSpanPredicate.span(close[0],true);EmbeddedDuplexOriginals.commandStageEntered(root[0]);
            refused(()->f.owner.requireUnknownPeerRootTerminal(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
            EmbeddedDuplexOriginals.commandStageReturned(root[0]);EmbeddedDuplexProcessHost.exchanges=false;
            refused(()->f.owner.requireUnknownPeerRootTerminal(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
            EmbeddedDuplexProcessHost.exchanges=true;
            refused(()->f.owner.acknowledge(rootId,CHALLENGE,q.getString("request_sha256"),q.getString("outcome_sha256"),"f".repeat(64)));controls++;
            check(Arrays.equals(durable,Files.readAllBytes(f.originals.resolve(rootId+".json")))&&!Files.exists(f.originals.resolve(rootId+".settled.json")),"terminal metadata read converted UNKNOWN into settled/ACK success");
        }
    }
    public static void main(String[] args)throws Exception{
        Path out=Files.createDirectory(Path.of(args[0]));
        Fixture enrollment=new Fixture(out.resolve("unsupported-enrollment-original"),false);
        refused(()->enrollment.owner.begin("7".repeat(32),"confirm",CHALLENGE,canonical("confirm"),()->{throw new AssertionError("unsupported enrollment effect entered");}));
        check(!Files.exists(enrollment.originals.resolve("7".repeat(32)+".json")),"unsupported Original wrote row");controls++;
        Fixture good=new Fixture(out.resolve("actual-ack-archive"));good.complete();good.joined();JSONObject acknowledged=good.ack();
        check("compacted".equals(acknowledged.getString("retention")),"genuine successful owner did not compact");
        check(Files.isRegularFile(good.settled()),"baseline missing writer: successful ACK compacted without durable settled custody");controls++;
        byte[] stable=Files.readAllBytes(good.settled());good.ack();
        check(Arrays.equals(stable,Files.readAllBytes(good.settled())),"duplicate exact ACK rewrote settled custody");controls++;
        JSONObject stop=good.stop(),body=good.archiveBody(stop);
        Path stopCustody=good.originals.resolve(STOP+".settled.json");byte[] stopProof=Files.readAllBytes(stopCustody);
        Files.delete(stopCustody);
        refused(()->good.owner.requireArchivedStop(STOP,stop,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Files.write(stopCustody,stopProof);
        JSONObject damagedStop=new JSONObject(new String(stopProof,java.nio.charset.StandardCharsets.UTF_8)).put("outcome_sha256","0".repeat(64));
        Files.writeString(stopCustody,damagedStop.toString());
        refused(()->good.owner.requireArchivedStop(STOP,stop,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Files.write(stopCustody,stopProof);
        good.owner.requireArchivedStop(STOP,stop,EmbeddedDuplexProcessHost.ACTOR);controls++;
        EmbeddedDuplexPreparedArchive archive=new EmbeddedDuplexPreparedArchive(good.context);
        JSONObject retained=archive.retain(EmbeddedDuplexCanonicalJson.encode(body),EmbeddedDuplexProcessHost.ACTOR,good.holder);
        good.holder.retireArchivedMetadata(body);check(good.resources.retired,"real holder did not retire archived metadata");
        check(Arrays.equals(stable,Files.readAllBytes(good.settled())),"archive changed settled custody");controls++;
        check(EmbeddedDuplexCanonicalJson.sameValue(retained,archive.retainedExact(EmbeddedDuplexCanonicalJson.encode(body),EmbeddedDuplexProcessHost.ACTOR,retained)),"exact archive retry differs");controls++;
        JSONObject wrongActor=copy(EmbeddedDuplexProcessHost.ACTOR).put("native_executor_generation",99);
        refused(()->archive.retain(EmbeddedDuplexCanonicalJson.encode(body),wrongActor,good.holder));controls++;
        JSONObject wrong=copy(body).put("original_id","9".repeat(32));refused(()->archive.retain(wrong.toString(),EmbeddedDuplexProcessHost.ACTOR,good.holder));controls++;
        // A distinct next SDK holder is constructed only after the actual archive retired metadata.
        JSONObject second=copy(request).put("preparation_id","5".repeat(32)).put("original_id","6".repeat(32));
        CompletableFuture<Bundle> next=new CompletableFuture<>();final EmbeddedDuplexOriginals.Record[] nextRecord=new EmbeddedDuplexOriginals.Record[1];
        good.owner.begin("6".repeat(32),"prepare_peer_start",CHALLENGE,canonical("prepare_peer_start"),()->{nextRecord[0]=captureActor();return next;});
        EmbeddedDuplexPreparedStart nextHolder=new EmbeddedDuplexPreparedStart(new EmbeddedDuplexResources(),second,nextRecord[0],EmbeddedDuplexProcessHost.ACTOR,arm(),new EmbeddedDuplexRuntimeConfigurationBinding.Context());
        nextHolder.sender();check(!nextHolder.id().equals(good.holder.id()),"second holder reused preparation ID");controls++;

        Fixture ackFirst=new Fixture(out.resolve("future-and-callback-join"));
        refused(ackFirst::ack);check(!Files.exists(ackFirst.settled()),"pending Future earned settled custody");
        ackFirst.complete();check("compacted".equals(ackFirst.ack().getString("retention"))&&Files.exists(ackFirst.settled()),"actual Future/callback terminal did not permit ACK");controls++;
        Fixture pending=new Fixture(out.resolve("pending-worker"));refused(pending::joined);check(!Files.exists(pending.settled()),"live worker emitted settlement");controls++;
        Fixture missingActor=new Fixture(out.resolve("missing-prepared-native-actor"),false);
        EmbeddedDuplexProcessHost.nativeActor=false;EmbeddedDuplexProcessHost.appRecord=false;
        missingActor.owner.begin(PREP,"prepare_peer_start",CHALLENGE,canonical("prepare_peer_start"),()->{missingActor.actual=captureActor();return missingActor.sdk;});
        missingActor.complete();missingActor.joined();refused(missingActor::ack);
        check(!Files.exists(missingActor.settled())&&missingActor.actual.future!=null,"actorless prepared operation acquired proof or compacted");controls++;
        Object[][] malformed={
            {"numeric-hash","app_record_sha256",new java.math.BigInteger("1".repeat(64))},
            {"boolean-native","native_generation",true},{"float-native","native_generation",73.0},
            {"string-native","native_generation","73"},{"zero-native","native_generation",0},
            {"missing-hash","app_record_sha256",JSONObject.NULL},{"bad-hash","app_record_sha256","g".repeat(64)}};
        for(Object[] fault:malformed){
            Fixture invalid=new Fixture(out.resolve("malformed-"+fault[0]));invalid.complete();
            // Fault only the held producer tuple after genuine outcome/ACK publication.
            invalid.actual.row.getJSONObject("actor_binding").put((String)fault[1],fault[2]);
            refused(invalid::ack);
            check(!Files.exists(invalid.settled())&&invalid.actual.future!=null,"malformed tuple acquired native proof or released Future");controls++;
        }
        Fixture callback=new Fixture(out.resolve("callback-live"));callback.prepare();refused(callback::joined);check(!Files.exists(callback.settled()),"live callback emitted settlement");controls++;
        Fixture noAck=new Fixture(out.resolve("no-consumer-ack"));noAck.complete();noAck.joined();
        check(!Files.exists(noAck.settled()),"unacknowledged operation emitted settlement");refused(()->noAck.owner.requirePreparedArchive(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Fixture enteredStage=new Fixture(out.resolve("entered-command-stage-live"));enteredStage.complete();
        EmbeddedDuplexOriginals.commandStageEntered(enteredStage.actual);
        refused(enteredStage::ack);check(!Files.exists(enteredStage.settled()),"entered fresh command stage emitted settlement");
        EmbeddedDuplexOriginals.commandStageReturned(enteredStage.actual);enteredStage.ack();
        check(Files.exists(enteredStage.settled()),"joined fresh command stage failed settlement");controls++;
        Fixture ordinary=new Fixture(out.resolve("ordinary148-join-contract"),false);String ordinaryId="a".repeat(32);
        JSONObject ordinaryReceipt=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1")
            .put("action","start").put("challenge",CHALLENGE).put("peer_lifecycle",new JSONObject()
                .put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1").put("action","start").put("status","active").put("renewal_pending",false));
        ordinary.owner.begin(ordinaryId,"start",CHALLENGE,canonical("start"),()->{ordinary.actual=captureActor();return CompletableFuture.completedFuture(response(ordinaryReceipt));});
        EmbeddedDuplexOriginals.commandStageEntered(ordinary.actual);EmbeddedDuplexProcessHost.exchanges=false;
        JSONObject ordinaryQ=observation(ordinary.owner.query(ordinaryId,CHALLENGE));
        Bundle ordinaryAck=ordinary.owner.acknowledge(ordinaryId,CHALLENGE,ordinaryQ.getString("request_sha256"),ordinaryQ.getString("outcome_sha256"),"f".repeat(64));
        check("compacted".equals(ordinaryAck.getString("status"))&&!Files.exists(ordinary.originals.resolve(ordinaryId+".settled.json")),"ordinary148 ACK gained a fresh-only gate or proof");
        EmbeddedDuplexOriginals.commandStageReturned(ordinary.actual);EmbeddedDuplexProcessHost.exchanges=true;controls++;
        Fixture stages=new Fixture(out.resolve("command-stage-live"));stages.complete();
        Runnable command=EmbeddedDuplexOriginals.carryActual(stages.actual,()->{});
        refused(stages::ack);check(!Files.exists(stages.settled()),"pending carried command compacted/emitted settlement");
        command.run();stages.ack();check(Files.exists(stages.settled()),"actual carried command return did not permit settlement");controls++;
        Fixture exchange=new Fixture(out.resolve("exchange-live"));exchange.complete();EmbeddedDuplexProcessHost.exchanges=false;exchange.joined();
        refused(exchange::ack);check(!Files.exists(exchange.settled()),"live modeled actor/exchange acquired settlement");
        EmbeddedDuplexProcessHost.exchanges=true;exchange.ack();check(Files.exists(exchange.settled()),"joined exchange did not permit settlement");controls++;
        Fixture uncertain=new Fixture(out.resolve("unknown"));uncertain.sdk.completeExceptionally(new IllegalStateException("modeled retained failure"));uncertain.joined();refused(uncertain::ack);
        check(!Files.exists(uncertain.settled()),"UNKNOWN emitted succeeded settlement");refused(()->uncertain.owner.requirePreparedArchive(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Fixture nativePending=new Fixture(out.resolve("native-pending"));nativePending.prepare();nativePending.sdk.complete(response(copy(nativePending.status).put("state","preparing")));nativePending.joined();refused(nativePending::ack);
        check(!Files.exists(nativePending.settled()),"native Pending emitted succeeded settlement");refused(()->nativePending.owner.requirePreparedArchive(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Fixture canceled=new Fixture(out.resolve("cancelled-future"));canceled.sdk.cancel(false);canceled.joined();refused(canceled::ack);
        check(!Files.exists(canceled.settled()),"cancelled Future emitted succeeded settlement");controls++;
        Fixture disk=new Fixture(out.resolve("durability-failure"));disk.complete();disk.joined();android.system.Os.failSettled=true;
        try{disk.ack();throw new AssertionError("settled fsync failure compacted");}catch(java.io.IOException expected){}
        check(!"compacted".equals(observation(disk.owner.query(PREP,CHALLENGE)).getString("retention")),"failed settled write released live owner");
        check(disk.actual.future!=null,"failed custody write released actual Future");
        android.system.Os.failSettled=false;disk.ack();check(Files.exists(disk.settled()),"same immutable custody could not finish");controls++;
        byte[] before=Files.readAllBytes(disk.settled());JSONObject corrupted=new JSONObject(new String(before,java.nio.charset.StandardCharsets.UTF_8)).put("outcome_sha256","0".repeat(64));
        Files.writeString(disk.settled(),corrupted.toString());JSONObject diskBody=disk.archiveBody(disk.stop());
        refused(()->new EmbeddedDuplexPreparedArchive(disk.context).retain(diskBody.toString(),EmbeddedDuplexProcessHost.ACTOR,disk.holder));controls++;
        wholeCustodyControls(out);
        unknownRootControls(out);
        System.out.println("PASS "+controls+" whole production SDK holder/Original Future callback/join/ACK -> durable settled custody -> exact normal Stop/renderer terminal -> immutable archive -> distinct SDK holder; modeled platform/SDK/JNI leaves, no devices");
    }
}
