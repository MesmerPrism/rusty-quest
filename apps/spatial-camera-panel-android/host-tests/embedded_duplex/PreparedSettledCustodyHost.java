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
            if(beginPrepare)owner.begin(PREP,"prepare_peer_start",CHALLENGE,new JSONObject().put("actual_call","prepare_peer_start"),()->{
                actual=EmbeddedDuplexOriginals.capture();return sdk;
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
        void complete()throws Exception{prepare();sdk.complete(response(status));check(actual.callbackJoined.isDone(),"real callback not completed");}
        JSONObject ack()throws Exception{
            JSONObject q=observation(owner.query(PREP,CHALLENGE));
            return observation(owner.acknowledge(PREP,CHALLENGE,q.getString("request_sha256"),q.getString("outcome_sha256"),"f".repeat(64)));
        }
        Path settled(){return originals.resolve(PREP+".settled.json");}
        void joined()throws Exception{owner.joined(actual);}
        JSONObject stop()throws Exception{
            resources.opened=true; // Modeled successful entered media, then genuine terminal SDK leaf.
            resources.terminal=true;resources.opened=false;
            check(EmbeddedDuplexPreparedRenderer.terminal(resources.renderer(true).toString(),holder.id()),"real renderer parser did not earn terminal Own-reuse");
            JSONObject nativeStop=new JSONObject().put("action","peer_stop").put("status","terminal").put("peer_physical_cleanup","terminal").put("renewal_pending",false)
                .put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1");
            JSONObject wrapper=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action","peer_stop")
                .put("runtime_config_sha256","b".repeat(64)).put("peer_lifecycle",nativeStop);
            final EmbeddedDuplexOriginals.Record[] held=new EmbeddedDuplexOriginals.Record[1];
            owner.begin(STOP,"peer_stop",CHALLENGE,new JSONObject().put("actual_call","peer_stop"),()->{
                held[0]=EmbeddedDuplexOriginals.capture();return CompletableFuture.completedFuture(response(wrapper));
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
        check(pending.actual.operationJoined&&pending.actual.original!=null,"live joined SDK was fabricated/released");
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
        Fixture uncertain=new Fixture(out.resolve("whole-unknown"));uncertain.sdk.completeExceptionally(new IllegalStateException("modeled retained failure"));uncertain.joined();uncertain.ack();
        Files.write(uncertain.settled(),settled); // A succeeded proof cannot grant authority to an actual UNKNOWN Original.
        refused(()->uncertain.owner.requirePreparedWholeClosure(pending.status,EmbeddedDuplexProcessHost.ACTOR));controls++;
    }
    public static void main(String[] args)throws Exception{
        Path out=Files.createDirectory(Path.of(args[0]));
        Fixture enrollment=new Fixture(out.resolve("actorless-confirm"),false);
        EmbeddedDuplexProcessHost.nativeActor=false;EmbeddedDuplexProcessHost.appRecord=false;EmbeddedDuplexProcessHost.appGeneration=0;
        Bundle confirmation=new Bundle();confirmation.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");
        confirmation.putString("status","observed");confirmation.putString("challenge",CHALLENGE);
        confirmation.putString("review_sha256","7".repeat(64));confirmation.putString("role_id","peer_a");
        confirmation.putLong("revision",1);confirmation.putString("record_sha256","8".repeat(64));confirmation.putBoolean("local_fixture",false);
        final EmbeddedDuplexOriginals.Record[] confirmed=new EmbeddedDuplexOriginals.Record[1];String confirmId="7".repeat(32);
        enrollment.owner.begin(confirmId,"confirm",CHALLENGE,new JSONObject().put("actual_call","confirm"),()->{
            confirmed[0]=EmbeddedDuplexOriginals.capture();return CompletableFuture.completedFuture(confirmation);
        });
        enrollment.owner.joined(confirmed[0]);JSONObject confirmQuery=observation(enrollment.owner.query(confirmId,CHALLENGE));
        JSONObject confirmedAck=observation(enrollment.owner.acknowledge(confirmId,CHALLENGE,confirmQuery.getString("request_sha256"),confirmQuery.getString("outcome_sha256"),"9".repeat(64)));
        check("succeeded".equals(confirmedAck.getString("state"))&&"compacted".equals(confirmedAck.getString("retention")),"actorless confirmation lost ordinary successful ACK compaction");
        check(!Files.exists(enrollment.originals.resolve(confirmId+".settled.json")),"actorless confirmation invented native settled custody");controls++;
        Fixture noMedia=new Fixture(out.resolve("never-native-close"),false);
        EmbeddedDuplexProcessHost.nativeActor=false; // Existing app/fence may close before any native claim.
        Bundle close=new Bundle();close.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");close.putString("status","observed");close.putString("challenge",CHALLENGE);
        close.putString("runtime_state","closed");close.putBoolean("display_attached",false);close.putBoolean("peer_route_proven",false);close.putBoolean("media_effect_proven",false);close.putString("close_disposition","never-native");
        final EmbeddedDuplexOriginals.Record[] closed=new EmbeddedDuplexOriginals.Record[1];String closeId="8".repeat(32);
        noMedia.owner.begin(closeId,"close_no_media",CHALLENGE,new JSONObject().put("actual_call","close_no_media"),()->{closed[0]=EmbeddedDuplexOriginals.capture();return CompletableFuture.completedFuture(close);});
        noMedia.owner.joined(closed[0]);JSONObject closeQuery=observation(noMedia.owner.query(closeId,CHALLENGE));
        JSONObject closeAck=observation(noMedia.owner.acknowledge(closeId,CHALLENGE,closeQuery.getString("request_sha256"),closeQuery.getString("outcome_sha256"),"9".repeat(64)));
        check("compacted".equals(closeAck.getString("retention"))&&!Files.exists(noMedia.originals.resolve(closeId+".settled.json")),"non-native app close invented native custody or failed compaction");controls++;
        Fixture good=new Fixture(out.resolve("actual-ack-archive"));good.complete();good.joined();JSONObject acknowledged=good.ack();
        check("compacted".equals(acknowledged.getString("retention")),"genuine successful owner did not compact");
        check(Files.isRegularFile(good.settled()),"baseline missing writer: successful ACK compacted without durable settled custody");controls++;
        byte[] stable=Files.readAllBytes(good.settled());good.ack();
        check(Arrays.equals(stable,Files.readAllBytes(good.settled())),"duplicate exact ACK rewrote settled custody");controls++;
        JSONObject stop=good.stop(),body=good.archiveBody(stop);
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
        good.owner.begin("6".repeat(32),"prepare_peer_start",CHALLENGE,new JSONObject().put("actual_call","second_prepare"),()->{nextRecord[0]=EmbeddedDuplexOriginals.capture();return next;});
        EmbeddedDuplexPreparedStart nextHolder=new EmbeddedDuplexPreparedStart(new EmbeddedDuplexResources(),second,nextRecord[0],EmbeddedDuplexProcessHost.ACTOR,arm(),new EmbeddedDuplexRuntimeConfigurationBinding.Context());
        nextHolder.sender();check(!nextHolder.id().equals(good.holder.id()),"second holder reused preparation ID");controls++;

        Fixture ackFirst=new Fixture(out.resolve("ack-before-join"));ackFirst.complete();
        check(!"compacted".equals(ackFirst.ack().getString("retention"))&&!Files.exists(ackFirst.settled()),"ACK alone claimed worker join");
        ackFirst.joined();check(Files.isRegularFile(ackFirst.settled())&&"compacted".equals(observation(ackFirst.owner.query(PREP,CHALLENGE)).getString("retention")),"genuine later join failed to retain custody");controls++;
        Fixture pending=new Fixture(out.resolve("pending-worker"));refused(pending::joined);check(!Files.exists(pending.settled()),"live worker emitted settlement");controls++;
        Fixture missingActor=new Fixture(out.resolve("missing-prepared-native-actor"));
        try(AutoCloseable ignored=EmbeddedDuplexOriginals.enter(missingActor.actual)){
            EmbeddedDuplexOriginals.bindActualActor(new JSONObject().put("package_id","host.fixture").put("native_generation",JSONObject.NULL).put("app_record_sha256",JSONObject.NULL));
        }
        missingActor.complete();missingActor.joined();refused(missingActor::ack);
        check(!Files.exists(missingActor.settled())&&missingActor.actual.original!=null,"malformed prepared actor acquired proof or compacted");controls++;
        Object[][] malformed={
            {"numeric-hash","app_record_sha256",new java.math.BigInteger("1".repeat(64))},
            {"boolean-native","native_generation",true},{"float-native","native_generation",73.0},
            {"string-native","native_generation","73"},{"zero-native","native_generation",0},
            {"missing-hash","app_record_sha256",JSONObject.NULL},{"bad-hash","app_record_sha256","g".repeat(64)}};
        for(Object[] fault:malformed){
            Fixture invalid=new Fixture(out.resolve("malformed-"+fault[0]));invalid.complete();invalid.ack();
            // Fault only the held producer tuple after genuine outcome/ACK publication.
            invalid.actual.row.getJSONObject("actor_binding").put((String)fault[1],fault[2]);
            try{invalid.joined();throw new AssertionError("malformed native custody tuple accepted");}
            catch(IllegalStateException expected){check("actual settled operation actor absent".equals(expected.getMessage()),"wrong native identity guard");}
            check(!Files.exists(invalid.settled())&&invalid.actual.original!=null,"malformed tuple acquired native proof or released Future");controls++;
        }
        Fixture callback=new Fixture(out.resolve("callback-live"));callback.prepare();refused(callback::joined);check(!Files.exists(callback.settled()),"live callback emitted settlement");controls++;
        Fixture noAck=new Fixture(out.resolve("no-consumer-ack"));noAck.complete();noAck.joined();
        check(!Files.exists(noAck.settled()),"unacknowledged operation emitted settlement");refused(()->noAck.owner.requirePreparedArchive(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Fixture stages=new Fixture(out.resolve("command-stage-live"));stages.complete();EmbeddedDuplexOriginals.commandStageEntered(stages.actual);stages.joined();
        check(!"compacted".equals(stages.ack().getString("retention"))&&!Files.exists(stages.settled()),"pending command stage compacted/emitted settlement");
        EmbeddedDuplexOriginals.commandStageReturned(stages.actual);stages.ack();check(Files.exists(stages.settled()),"actual stage return did not permit settlement");controls++;
        Fixture exchange=new Fixture(out.resolve("exchange-live"));exchange.complete();EmbeddedDuplexProcessHost.exchanges=false;exchange.joined();
        check(!"compacted".equals(exchange.ack().getString("retention"))&&!Files.exists(exchange.settled()),"live exchange compacted/emitted settlement");
        EmbeddedDuplexProcessHost.exchanges=true;exchange.ack();check(Files.exists(exchange.settled()),"joined exchange did not permit settlement");controls++;
        Fixture uncertain=new Fixture(out.resolve("unknown"));uncertain.sdk.completeExceptionally(new IllegalStateException("modeled retained failure"));uncertain.joined();uncertain.ack();
        check(!Files.exists(uncertain.settled()),"UNKNOWN emitted succeeded settlement");refused(()->uncertain.owner.requirePreparedArchive(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Fixture nativePending=new Fixture(out.resolve("native-pending"));nativePending.prepare();nativePending.sdk.complete(response(copy(nativePending.status).put("state","preparing")));nativePending.joined();nativePending.ack();
        check(!Files.exists(nativePending.settled()),"native Pending emitted succeeded settlement");refused(()->nativePending.owner.requirePreparedArchive(body,EmbeddedDuplexProcessHost.ACTOR));controls++;
        Fixture canceled=new Fixture(out.resolve("cancelled-future"));canceled.sdk.cancel(false);canceled.joined();canceled.ack();
        check(!Files.exists(canceled.settled()),"cancelled Future emitted succeeded settlement");controls++;
        Fixture disk=new Fixture(out.resolve("durability-failure"));disk.complete();disk.joined();android.system.Os.failSettled=true;
        try{disk.ack();throw new AssertionError("settled fsync failure compacted");}catch(java.io.IOException expected){}
        check(!"compacted".equals(observation(disk.owner.query(PREP,CHALLENGE)).getString("retention")),"failed settled write released live owner");
        check(disk.actual.original!=null,"failed custody write released actual Future");
        android.system.Os.failSettled=false;disk.ack();check(Files.exists(disk.settled()),"same immutable custody could not finish");controls++;
        byte[] before=Files.readAllBytes(disk.settled());JSONObject corrupted=new JSONObject(new String(before,java.nio.charset.StandardCharsets.UTF_8)).put("outcome_sha256","0".repeat(64));
        Files.writeString(disk.settled(),corrupted.toString());JSONObject diskBody=disk.archiveBody(disk.stop());
        refused(()->new EmbeddedDuplexPreparedArchive(disk.context).retain(diskBody.toString(),EmbeddedDuplexProcessHost.ACTOR,disk.holder));controls++;
        wholeCustodyControls(out);
        System.out.println("PASS "+controls+" whole production SDK holder/Original Future callback/join/ACK -> durable settled custody -> exact normal Stop/renderer terminal -> immutable archive -> distinct SDK holder; modeled platform/SDK/JNI leaves, no devices");
    }
}
