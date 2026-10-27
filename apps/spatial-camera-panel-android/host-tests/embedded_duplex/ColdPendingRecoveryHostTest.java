package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.os.Bundle;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Actual app/Original/fence/companion callers and storage; Native/kernel/platform leaves are explicit models.
 * Separate Unix controls exercise actual counter/flock/owner/death/retirement and compile full JNI. */
public final class ColdPendingRecoveryHostTest {
    static String A="a".repeat(32),B="b".repeat(32),C="c".repeat(32),PRIOR="d".repeat(32),ID="e".repeat(32),CH="f".repeat(32);
    static int checks;
    interface Checked{void run()throws Exception;}
    static void yes(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void denied(Checked action)throws Exception{checks++;try{action.run();}catch(Exception expected){return;}throw new AssertionError("expected refusal");}
    static JSONObject json(Bundle value)throws Exception{return new JSONObject(value.getString("observation_json"));}
    static final class Fixture {
        File app,root,prior;Context context;EmbeddedDuplexProcessHost host;byte[] priorBytes,markerBytes;String sourceRecord;
        final Map<Path,byte[]> preserved=new LinkedHashMap<>();
    }
    static Fixture fresh()throws Exception{return fresh(false);}
    static Fixture fresh(boolean clear)throws Exception{
        Fixture f=new Fixture();f.app=Files.createTempDirectory("cold-actual-caller-").toFile();
        f.context=new Context(){public File getNoBackupFilesDir(){return f.app;}};
        EmbeddedDuplexRunSelectionStore selection=new EmbeddedDuplexRunSelectionStore(f.app,new EmbeddedDuplexRunSelectionStore.DirectorySync(){public void sync(File d){}public void privateMode(File p,boolean d){}});
        EmbeddedDuplexRunContext prior=selection.newRun(A);f.prior=prior.root;
        File global=new File(f.app,"embedded-duplex-replay");yes(global.mkdir(),"global replay");
        File priorReplay=new File(prior.root,"embedded-duplex-replay");yes(priorReplay.mkdir(),"A replay");
        for(int n=0;n<4;n++)try(EmbeddedDuplexProcessFence idle=EmbeddedDuplexProcessFence.acquire(global,null,null,()->{},A,priorReplay)){}
        EmbeddedDuplexProcessIncarnation.Identity old=new EmbeddedDuplexProcessIncarnation.Identity("00112233-4455-6677-8899-aabbccddeeff",55,10001,101);
        try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(global,null,null,()->{},A,priorReplay)){
            yes(fence.generation()==5,"A generation");fence.bindIncarnation(old);fence.beforeRuntimeEffects(null,null);f.sourceRecord=fence.nativeAdmissionRecord();
            EmbeddedDuplexOriginals.ActorSource actor=new EmbeddedDuplexOriginals.ActorSource(){
                public JSONObject snapshot()throws Exception{return new JSONObject().put("package_id","fixture.package").put("native_generation",3).put("app_record_sha256",EmbeddedDuplexProcessFence.digest(f.sourceRecord));}
                public long appGeneration(){return 5;}public boolean settled(JSONObject a,long g){return false;}
            };
            EmbeddedDuplexOriginals source=new EmbeddedDuplexOriginals(prior.root,actor,()->{});
            source.begin(PRIOR,"bootstrap_real_peer",CH,EmbeddedDuplexOriginals.request("bootstrap_real_peer",CH,new Bundle()),()->{
                try{EmbeddedDuplexOriginals.bindActualActor(actor.snapshot(),5);}catch(Exception failure){throw new IllegalStateException(failure);}
                return new CompletableFuture<>();
            });
            f.priorBytes=Files.readAllBytes(new File(prior.root,"operator-originals-v1/"+PRIOR+".json").toPath());
            // Explicit old cleanup leaf: the fixture deliberately creates no old Whole receipt.
            fence.afterVerifiedNoMediaCleanup(null,null);
        JSONObject inherited=new JSONObject().put("source_run_id",JSONObject.NULL).put("whole_original_id","1".repeat(32)).put("request_sha256","2".repeat(64)).put("outcome_sha256","3".repeat(64)).put("physical_retirement_sha256","4".repeat(64)).put("prior_native_generation",2);
        EmbeddedDuplexRunContext selected=selection.newRun(B,inherited.toString());f.root=selected.root;
        File replay=new File(selected.root,"embedded-duplex-replay");yes(replay.mkdir(),"B replay");
        fence.selectOperationalRun(B,replay,null,null);
            yes(fence.generation()==6,"B generation");fence.bindIncarnation(old);if(!clear)fence.beforeRuntimeEffects(null,null);
        }
        f.markerBytes=Files.readAllBytes(new File(f.root,"run.v1.json").toPath());
        EmbeddedDuplexNative.reset(f.sourceRecord,old.json());if(clear)EmbeddedDuplexNative.eligible=false;
        f.host=EmbeddedDuplexProcessHost.forApplication(f.context);return f;
    }
    static void close(Fixture f)throws Exception{
        f.host.harnessClose();java.lang.reflect.Field singleton=EmbeddedDuplexProcessHost.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        singleton=EmbeddedDuplexOriginals.class.getDeclaredField("singleton");singleton.setAccessible(true);singleton.set(null,null);
    }
    static Fixture populated()throws Exception{
        Fixture f=new Fixture();f.app=Files.createTempDirectory("cold-populated-native4-").toFile();
        f.context=new Context(){public File getNoBackupFilesDir(){return f.app;}};
        EmbeddedDuplexRunSelectionStore selection=new EmbeddedDuplexRunSelectionStore(f.app,new EmbeddedDuplexRunSelectionStore.DirectorySync(){public void sync(File d){}public void privateMode(File p,boolean d){}});
        f.root=selection.newRun(B).root;f.prior=f.root;
        File global=new File(f.app,"embedded-duplex-replay"),replay=new File(f.root,"embedded-duplex-replay");yes(global.mkdir()&&replay.mkdir(),"populated replay directories");
        for(int i=0;i<9;i++)try(EmbeddedDuplexProcessFence ignored=EmbeddedDuplexProcessFence.acquire(global,null,null,()->{},B,replay)){}
        EmbeddedDuplexProcessIncarnation.Identity old=new EmbeddedDuplexProcessIncarnation.Identity("00112233-4455-6677-8899-aabbccddeeff",55,10001,101);
        try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(global,null,null,()->{},B,replay)){
            yes(fence.generation()==10,"populated old app10");fence.bindIncarnation(old);fence.beforeRuntimeEffects(null,null);f.sourceRecord=fence.nativeAdmissionRecord();
            EmbeddedDuplexOriginals.ActorSource actor=new EmbeddedDuplexOriginals.ActorSource(){
                public JSONObject snapshot()throws Exception{return new JSONObject().put("package_id",f.context.getPackageName()).put("native_generation",4).put("app_record_sha256",EmbeddedDuplexProcessFence.digest(f.sourceRecord));}
                public long appGeneration(){return 10;}public boolean settled(JSONObject value,long generation){return false;}
            };
            EmbeddedDuplexOriginals source=new EmbeddedDuplexOriginals(f.root,actor,()->{});
            source.begin("2".repeat(32),"start",CH,EmbeddedDuplexOriginals.request("start",CH,new Bundle()),CompletableFuture::new);
            source.begin("3".repeat(32),"whole_app_close",CH,EmbeddedDuplexOriginals.request("whole_app_close",CH,new Bundle()),()->{
                CompletableFuture<Bundle> failure=new CompletableFuture<>();failure.completeExceptionally(new IllegalStateException("old result uncertain"));return failure;
            });
            JSONObject checkpoint=new JSONObject().put("$schema",EmbeddedDuplexStartJournal.SCHEMA).put("phase","pending_start").put("revision",7).put("lineage_sha256","8".repeat(64)).put("last_verified_receipt_sha256",JSONObject.NULL);
            JSONObject snapshots=new JSONObject();for(String key:new String[]{"broker_adapter","broker_runtime","peer_runtime","media_product","owner_progress","owner_dispatch_replay","product_activation_replay","request_receipt_ledger"})snapshots.put(key,new JSONObject().put("json","{}").put("sha256",EmbeddedDuplexProcessFence.digest("{}")));
            JSONObject evidence=new JSONObject().put("$schema",EmbeddedDuplexRecoveryEvidenceJournal.SCHEMA).put("revision",7).put("provider_epoch_id","fixture.provider").put("lineage_sha256","8".repeat(64)).put("snapshots",snapshots);
            String checkpointBytes=checkpoint.toString(2)+"\n",evidenceBytes=evidence.toString(2)+"\n";
            EmbeddedDuplexRecoveryEvidenceJournal.validateAgainstCheckpoint(checkpointBytes,evidenceBytes,"fixture.provider");
            Files.writeString(new File(replay,"start-checkpoint.v1.json").toPath(),checkpointBytes);Files.writeString(new File(replay,"recovery-evidence.v1.json").toPath(),evidenceBytes);
        }
        for(String name:new String[]{"embedded-duplex-replay/start-checkpoint.v1.json","embedded-duplex-replay/recovery-evidence.v1.json","operator-originals-v1/"+"2".repeat(32)+".json","operator-originals-v1/"+"3".repeat(32)+".json","run.v1.json"}){
            Path path=new File(f.root,name).toPath();f.preserved.put(path,Files.readAllBytes(path));
        }
        EmbeddedDuplexNative.reset(f.sourceRecord,old.json(),4L);EmbeddedDuplexNative.maintained=true;
        f.host=EmbeddedDuplexProcessHost.forApplication(f.context);return f;
    }
    static void requirePreserved(Fixture f)throws Exception{
        for(Map.Entry<Path,byte[]> entry:f.preserved.entrySet())yes(Arrays.equals(entry.getValue(),Files.readAllBytes(entry.getKey())),"retained source bytes changed: "+entry.getKey().getFileName());
    }
    static void savePreserved(File output,Fixture f)throws Exception{
        for(Map.Entry<Path,byte[]> entry:f.preserved.entrySet()){
            Path retained=output.toPath().resolve("retained").resolve(f.root.toPath().relativize(entry.getKey()));
            Files.createDirectories(retained.getParent());Files.write(retained,entry.getValue());
        }
    }
    static void populatedRecovery(File output)throws Exception{
        Fixture f=populated();JSONObject before=new JSONObject(EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS));
        yes(f.host.harnessFence().recoveryOnly(),"nonempty Pending drift escaped recovery quarantine");denied(()->f.host.harnessAdmission());
        recover(f,true);JSONObject row=json(await(f));yes("succeeded".equals(row.getString("state")),"Native4 populated recovery failed: "+row);
        JSONObject receipt=new JSONObject(row.getJSONObject("response").getString("receipt"));
        yes(receipt.getString("$schema").equals("rusty.quest.embedded_duplex.cold_pending_local_retirement.v2")&&receipt.getJSONObject("native_retirement").getString("$schema").equals("rusty.quest.embedded_duplex.cold_pending_native_retirement.v2"),"maintained retirement versions");
        yes(receipt.getLong("prior_native_generation")==4&&EmbeddedDuplexNative.nativeGeneration==4&&EmbeddedDuplexNative.retirements==1,"Native4 counter changed during retirement");
        yes(row.getJSONObject("actor_binding").isNull("native_generation"),"populated recovery invented old Native actor");
        requirePreserved(f);
        EmbeddedDuplexOriginals store=EmbeddedDuplexOriginals.forApplication(f.context);
        Bundle joined=null;for(int i=0;i<100;i++){joined=store.queryJoin(ID,CH);if(joined.getString("status").equals("joined"))break;Thread.sleep(10);}
        yes(joined.getString("status").equals("joined"),"new populated recovery actual callback did not join");
        for(String id:new String[]{"2".repeat(32),"3".repeat(32)}){
            JSONObject old=json(store.query(id,CH));yes(old.getString("state").equals("unknown")&&old.isNull("consumer_receipt_sha256"),"old Start/Whole was settled or ACKed");
        }
        yes(!new EmbeddedDuplexRecoveryCoordinator(f.context).freshBootstrapAllowed(),"retained Start was reopened in same run");denied(()->f.host.harnessAdmission());
        JSONObject legacy=new JSONObject(receipt.toString()).put("$schema","rusty.quest.embedded_duplex.cold_pending_local_retirement.v1");legacy.getJSONObject("native_retirement").put("$schema","rusty.quest.embedded_duplex.cold_pending_native_retirement.v1");EmbeddedDuplexColdRecovery.validateReceipt(legacy);
        JSONObject mixed=new JSONObject(legacy.toString());mixed.getJSONObject("native_retirement").put("$schema","rusty.quest.embedded_duplex.cold_pending_native_retirement.v2");denied(()->EmbeddedDuplexColdRecovery.validateReceipt(mixed));
        Path evidence=new File(f.root,"embedded-duplex-replay/recovery-evidence.v1.json").toPath();byte[] exact=Files.readAllBytes(evidence);Files.writeString(evidence,new String(exact,java.nio.charset.StandardCharsets.UTF_8)+" ");denied(()->EmbeddedDuplexColdRecovery.latestHandoff(f.root,f.context.getPackageName()));Files.write(evidence,exact);
        save(output,"before-status",before);save(output,"new-original",row);save(output,"receipt",receipt);save(output,"join",new JSONObject(joined.getString("join_observation_json")));
        save(output,"after-status",new JSONObject(EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS)));save(output,"legacy-v1-parser-input",legacy);
        save(output,"old-start-observation",json(store.query("2".repeat(32),CH)));save(output,"old-whole-observation",json(store.query("3".repeat(32),CH)));savePreserved(output,f);
        close(f);f.host=EmbeddedDuplexProcessHost.forApplication(f.context);
        yes(new JSONObject(EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS)).getString("app_fence_disposition").equals("clear"),"populated recovery restart lost clear handoff");requirePreserved(f);
        EmbeddedDuplexRuntimeService.newRun(f.context,C,null).get(5,TimeUnit.SECONDS);f.host.harnessAdmission();yes(EmbeddedDuplexNative.nativeGeneration==5,"populated recovery did not earn new-run4->5");requirePreserved(f);close(f);
    }
    static Bundle recover(Fixture f,boolean durable)throws Exception{
        Bundle extras=new Bundle();extras.putString("run_id",B);if(!durable){extras.putString("prior_run_id",A);extras.putString("prior_original_id",PRIOR);}
        JSONObject request=EmbeddedDuplexOriginals.request("recover_cold_pending",CH,extras);
        return EmbeddedDuplexRuntimeService.beginOriginal(f.context,ID,"recover_cold_pending",CH,request,()->
            EmbeddedDuplexRuntimeService.recoverColdPending(f.context,B,durable?null:A,durable?null:PRIOR).thenApply(raw->{
                Bundle response=new Bundle();response.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");response.putString("status","observed");response.putString("challenge",CH);response.putString("receipt",raw);return response;
            }).whenComplete((value,failure)->{if(failure!=null)failure.printStackTrace();}));
    }
    static Bundle await(Fixture f)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);Bundle value;
        do{value=EmbeddedDuplexRuntimeService.queryOriginal(f.context,B,ID,CH);if(Set.of("observed","unknown","compacted").contains(value.getString("status")))return value;Thread.sleep(10);}while(System.nanoTime()<until);
        throw new AssertionError("actual Original did not finish: "+value);
    }
    static void save(File output,String name,JSONObject value)throws Exception{Files.createDirectories(output.toPath());Files.writeString(new File(output,name+".json").toPath(),EmbeddedDuplexCanonicalJson.encode(value));}
    static void success(File output,boolean durable)throws Exception{success(output,durable,fresh());}
    static void success(File output,boolean durable,Fixture f)throws Exception{
        if(durable)EmbeddedDuplexNative.maintained=true;
        JSONObject before=new JSONObject(EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS));
        yes(before.getString("app_fence_disposition").equals("recovery_required"),"cold Pending inspection");
        if(!EmbeddedDuplexNative.eligible)yes(before.getJSONObject("native").getString("refusal_reason").equals("native_prior_process_owner_unreconciled"),"modeled Native refusal omitted actual producer reason");
        denied(()->f.host.harnessAdmission());
        Bundle dispatch=recover(f,durable);yes(dispatch.getString("schema").equals(EmbeddedDuplexOriginals.RUN_TRANSPORT),"outer v3");
        Bundle queried=await(f);JSONObject row=json(queried);yes(row.getString("state").equals("succeeded"),"actual completion failed: "+row);
        JSONObject receipt=new JSONObject(row.getJSONObject("response").getString("receipt"));
        yes(receipt.getLong("prior_native_generation")==3&&receipt.getString("old_process_retirement").equals("not_running"),"retirement subject");
        yes(row.getJSONObject("actor_binding").isNull("native_generation"),"invented live Native actor");
        EmbeddedDuplexOriginals store=EmbeddedDuplexOriginals.forApplication(f.context);Bundle joined=null;
        for(int i=0;i<100;i++){joined=store.queryJoin(ID,CH);if(joined.getString("status").equals("joined"))break;Thread.sleep(10);}
        yes(joined.getString("status").equals("joined"),"actual callback/current-local join unavailable: "+joined);
        JSONObject join=new JSONObject(joined.getString("join_observation_json"));yes(join.getString("schema").equals("rusty.quest.embedded_duplex.original_join.v2"),"join schema");
        yes(join.isNull("native_executor_generation")&&join.getJSONObject("join_basis").getString("kind").equals("actual_future_callback"),"join actor");
        yes(join.getString("physical_retirement_sha256").equals(EmbeddedDuplexCanonicalJson.sha256(receipt)),"whole receipt digest");
        JSONObject after=new JSONObject(EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS));
        yes(after.getString("app_fence_disposition").equals("clear"),"clear publication disposition");
        f.host.harnessFence().requireIncarnation(EmbeddedDuplexProcessIncarnation.current(android.os.Process.myPid(),android.os.Process.myUid()));
        yes(EmbeddedDuplexNative.retirements==1&&EmbeddedDuplexNative.nativeGeneration==3,"counter advanced during cleanup");
        recover(f,durable);yes(EmbeddedDuplexNative.retirements==1,"once replay");
        if(f.priorBytes!=null)yes(Arrays.equals(f.priorBytes,Files.readAllBytes(new File(f.prior,"operator-originals-v1/"+PRIOR+".json").toPath())),"old transaction changed");
        yes(Arrays.equals(f.markerBytes,Files.readAllBytes(new File(f.root,"run.v1.json").toPath())),"old release marker relabeled");
        JSONObject handoff=EmbeddedDuplexColdRecovery.latestHandoff(f.root,f.context.getPackageName());yes(handoff.getLong("prior_native_generation")==3,"new durable handoff missing");
        save(output,"before-status",before);save(output,"dispatch",new JSONObject(dispatch));save(output,"original",row);save(output,"receipt",receipt);save(output,"join",join);save(output,"after-status",after);
        Bundle ack=store.acknowledge(ID,CH,row.getString("request_sha256"),row.getString("outcome_sha256"),"9".repeat(64));yes(ack.getString("status").equals("compacted"),"actual ACK");
        close(f);f.host=EmbeddedDuplexProcessHost.forApplication(f.context);
        JSONObject restarted=new JSONObject(EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS));yes(restarted.getString("app_fence_disposition").equals("clear"),"restart after persisted completion");save(output,"restart-status",restarted);
        JSONObject selected=new JSONObject(EmbeddedDuplexRuntimeService.newRun(f.context,C,null).get(5,TimeUnit.SECONDS));
        JSONObject nextMarker=new JSONObject(Files.readString(new File(f.app,"embedded-duplex-runs-v1/"+C+"/run.v1.json").toPath()));
        yes(nextMarker.getJSONObject("release_source").getLong("prior_native_generation")==3,"selector inherited Native2 instead of actual3");
        f.host.harnessAdmission();yes(EmbeddedDuplexNative.nativeGeneration==4,"ordinary admission did not allocate successor4");
        save(output,"next-selection",selected);save(output,"next-marker",nextMarker);close(f);
    }
    public static void main(String[] args)throws Exception{
        File out=new File(args[0]);
        if(args.length==3){success(new File(out,args[2]),false,captured(new File(args[1]),args[2]));}
        else{
            success(new File(out,"legacy"),false);success(new File(out,"durable"),true);
            success(new File(out,"clear-legacy"),false,fresh(true));
            Fixture noPublication=fresh(true);Files.delete(new File(noPublication.prior,"operator-originals-v1/"+PRIOR+".json").toPath());noPublication.priorBytes=null;
            success(new File(out,"clear-durable-no-original"),true,noPublication);
            negativeControls();interruptedPublication(new File(out,"interrupted"));normalWholeAfterRecovery(new File(out,"normal-whole"));zeroNativeWholeRetainsJoinWithoutHandoff();
            populatedRecovery(new File(out,"populated-native4"));interruptedPublication(new File(out,"populated-interrupted"),populated(),true);populatedMutation(new File(out,"populated-mutation"));
            populatedCrashBeforePrepare(new File(out,"before-prepare-restart"),false);populatedCrashBeforePrepare(new File(out,"tampered-predecessor"),true);
        }
        System.out.println("Actual combined recovery/Original/query/join/ACK/restart/selector/admission controls: "+checks+" PASS; Native/kernel/platform leaves modeled, no hardware credit");
    }
    static void zeroNativeWholeRetainsJoinWithoutHandoff()throws Exception{
        Fixture f=fresh();long generation=f.host.harnessFence().generation();String hash="7".repeat(64),id="8".repeat(32);
        JSONObject actor=new JSONObject().put("package_id",f.context.getPackageName()).put("native_generation",JSONObject.NULL).put("app_record_sha256",hash);
        EmbeddedDuplexOriginals store=EmbeddedDuplexOriginals.forApplication(f.context);
        JSONObject physical=new JSONObject().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1").put("action","whole_app_close").put("config_sha256",hash).put("app_process_generation",generation).put("native_executor_generation",JSONObject.NULL).put("peer_physical_cleanup","terminal").put("renewal_pending",false);
        for(String key:new String[]{"native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status"})physical.put(key,"terminal");
        JSONObject wrapper=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action","whole_app_close").put("challenge",CH).put("peer_lifecycle",physical);CompletableFuture<Bundle> future=new CompletableFuture<>();
        store.begin(id,"whole_app_close",CH,EmbeddedDuplexOriginals.request("whole_app_close",CH,new Bundle()),()->{try{EmbeddedDuplexOriginals.bindActualActor(actor,generation);}catch(Exception failure){throw new IllegalStateException(failure);}return future;});
        store.actualClosedAppOwnerJoined(generation,0,hash,physical,new Object());Bundle response=new Bundle();response.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");response.putString("status","observed");response.putString("challenge",CH);response.putString("receipt",wrapper.toString());future.complete(response);
        yes("joined".equals(store.queryJoin(id,CH).getString("status")),"zero Native local join was lost");
        yes(EmbeddedDuplexOriginals.latestWholeHandoff(f.root,f.context.getPackageName())==null,"zero Native join poisoned the Native handoff pointer");close(f);
    }
    static void normalWholeAfterRecovery(File output)throws Exception{
        Fixture f=fresh();EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);recover(f,false);JSONObject recovery=json(await(f));
        EmbeddedDuplexOriginals store=EmbeddedDuplexOriginals.forApplication(f.context);
        for(int i=0;i<100&&!"joined".equals(store.queryJoin(ID,CH).getString("status"));i++)Thread.sleep(10);
        store.acknowledge(ID,CH,recovery.getString("request_sha256"),recovery.getString("outcome_sha256"),"9".repeat(64));
        EmbeddedDuplexRuntimeService.newRun(f.context,C,null).get(5,TimeUnit.SECONDS);f.root=new File(f.app,"embedded-duplex-runs-v1/"+C);store=EmbeddedDuplexOriginals.forApplication(f.context);
        f.host.harnessAdmission();yes(EmbeddedDuplexNative.nativeGeneration==4,"ordinary admission after recovery");
        yes(EmbeddedDuplexProcessIncarnation.readForRecovery(new File(f.root,"embedded-duplex-replay")).binding.owner.pid==42,"non-Original admission omitted durable companion");
        String wholeId="6".repeat(32);JSONObject actor=EmbeddedDuplexProcessHost.originalActor();long generation=f.host.harnessFence().generation();
        JSONObject physical=new JSONObject().put("$schema","rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1").put("action","whole_app_close").put("config_sha256","7".repeat(64)).put("app_process_generation",generation).put("native_executor_generation",4).put("peer_physical_cleanup","terminal").put("renewal_pending",false);
        for(String key:new String[]{"native_host_physical_cleanup","java_own_capture_cleanup","java_renderer_cleanup","java_control_cleanup","app_process_fence_cleanup","whole_app_physical_cleanup","status"})physical.put(key,"terminal");
        JSONObject wrapper=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1").put("action","whole_app_close").put("challenge",CH).put("peer_lifecycle",physical);
        Bundle extras=new Bundle();extras.putString("run_id",C);CompletableFuture<Bundle> future=new CompletableFuture<>();
        store.begin(wholeId,"whole_app_close",CH,EmbeddedDuplexOriginals.request("whole_app_close",CH,extras),()->{try{EmbeddedDuplexOriginals.bindActualActor(actor,generation);}catch(Exception failure){throw new IllegalStateException(failure);}return future;});
        f.host.harnessWholePhysicalLeaf(physical,actor,wrapper.toString());
        store.actualClosedAppOwnerJoined(generation,4,actor.getString("app_record_sha256"),physical,new Object());
        Bundle response=new Bundle();response.putString("schema","rusty.quest.embedded_duplex.operator_transport.v1");response.putString("status","observed");response.putString("challenge",CH);response.putString("receipt",wrapper.toString());future.complete(response);
        for(int i=0;i<100&&!"joined".equals(store.queryJoin(wholeId,CH).getString("status"));i++)Thread.sleep(10);
        yes(EmbeddedDuplexColdRecovery.latestCompletedHandoff(f.root,f.context.getPackageName()).getLong("prior_native_generation")==4,"new normal Whole lost to old recovery3");
        save(output,"normal-whole-original",json(store.query(wholeId,CH)));close(f);f.host=EmbeddedDuplexProcessHost.forApplication(f.context);
        EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);yes(new JSONObject(f.host.harnessNativeHandoff()).getLong("prior_native_generation")==4,"restart restored old pointer");
        EmbeddedDuplexRuntimeService.newRun(f.context,"9".repeat(32),null).get(5,TimeUnit.SECONDS);f.host.harnessAdmission();yes(EmbeddedDuplexNative.nativeGeneration==5,"normal Whole/restart successor admission");close(f);
    }
    static void negativeControls()throws Exception{
        Path proc=Path.of(System.getProperty("host.proc"));
        for(String damage:new String[]{"live_old","wrong_counter","missing_link","wrong_companion","wrong_source_actor"}){
            Fixture f=fresh();EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);
            Path oldStat=proc.resolve("55/stat");
            if(damage.equals("live_old")){Files.createDirectories(oldStat.getParent());Files.writeString(oldStat,"55 (old live process) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 101 21\n");}
            if(damage.equals("wrong_counter"))EmbeddedDuplexNative.nativeGeneration=4L;
            Path source=new File(f.prior,"operator-originals-v1/"+PRIOR+".json").toPath();
            if(damage.equals("missing_link"))Files.delete(source);
            if(damage.equals("wrong_companion")){Path companion=new File(f.root,"embedded-duplex-replay/process-incarnation.v1.json").toPath();JSONObject value=new JSONObject(Files.readString(companion));value.getJSONObject("original_owner").put("start_ticks",102);Files.writeString(companion,value.toString());}
            if(damage.equals("wrong_source_actor")){JSONObject row=new JSONObject(Files.readString(source));row.getJSONObject("actor_binding").put("app_record_sha256","1".repeat(64));EmbeddedDuplexRunContextHostTest.refreshOutcome(row);Files.writeString(source,EmbeddedDuplexCanonicalJson.encode(row));}
            recover(f,false);JSONObject row=json(await(f));yes(row.getString("state").equals("unknown"),"unsupported source succeeded: "+damage);
            yes(EmbeddedDuplexNative.retirements==0&&f.host.harnessFence().recoveryOnly(),"unsupported source changed fence/counter: "+damage);
            close(f);if(damage.equals("live_old")){Files.delete(oldStat);Files.delete(oldStat.getParent());}
        }
        Fixture f=fresh();EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);EmbeddedDuplexNative.renderer=false;
        Bundle dispatch=recover(f,false);Thread.sleep(150);
        yes(EmbeddedDuplexNative.retirements==0&&f.host.harnessFence().recoveryOnly(),"incomplete current physical cleanup admitted Native retirement");
        yes(json(EmbeddedDuplexRuntimeService.queryOriginal(f.context,B,ID,CH)).getString("state").equals("pending"),"unfinished physical cleanup lost Pending Original");
        EmbeddedDuplexNative.renderer=true;yes(json(await(f)).getString("state").equals("succeeded"),"retained current physical owner did not finish");close(f);
    }
    @SuppressWarnings("removal")
    static void interruptedPublication(File output)throws Exception{
        interruptedPublication(output,fresh(),false);
    }
    @SuppressWarnings("removal")
    static void interruptedPublication(File output,Fixture f,boolean durable)throws Exception{
        EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);
        SecurityManager previous=System.getSecurityManager();
        System.setSecurityManager(new SecurityManager(){public void checkPermission(java.security.Permission p){}public void checkWrite(String path){if(path.endsWith(ID+".json.new"))throw new SecurityException("fixture lost final recovery receipt publication");}});
        try{recover(f,durable);yes(json(await(f)).getString("state").equals("unknown"),"interrupted local receipt was declared success");}
        finally{System.setSecurityManager(previous);}
        yes(!f.host.harnessFence().effectsPending()&&EmbeddedDuplexNative.retirements==1,"interruption did not reach modeled post-clear boundary");
        yes(new JSONObject(EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS)).getString("app_fence_disposition").equals("recovery_required"),"interrupted publication was advertised clear");
        byte[] unknown=Files.readAllBytes(new File(f.root,"operator-originals-v1/"+ID+".json").toPath());String oldId=ID;close(f);
        // Process retirement is a fresh kernel leaf, not an old callback join.
        Path proc=Path.of(System.getProperty("host.proc"));String stat=Files.readString(proc.resolve("42/stat"));Files.delete(proc.resolve("42/stat"));Files.delete(proc.resolve("42"));Files.createDirectories(proc.resolve("43"));Files.writeString(proc.resolve("43/stat"),stat.replaceFirst("42","43"));System.setProperty("host.pid","43");ID="0".repeat(32);
        f.host=EmbeddedDuplexProcessHost.forApplication(f.context);recover(f,true);JSONObject row=json(await(f));yes(row.getString("state").equals("succeeded"),"source-owned interrupted publication recovery failed: "+row);
        yes(Arrays.equals(unknown,Files.readAllBytes(new File(f.root,"operator-originals-v1/"+oldId+".json").toPath())),"old UNKNOWN was relabeled");
        save(output,"new-original",row);save(output,"old-unknown",new JSONObject(new String(unknown,java.nio.charset.StandardCharsets.UTF_8)));close(f);ID=oldId;System.clearProperty("host.pid");
        requirePreserved(f);
        Files.createDirectories(proc.resolve("42"));Files.writeString(proc.resolve("42/stat"),stat);Files.delete(proc.resolve("43/stat"));Files.delete(proc.resolve("43"));
    }
    static void populatedMutation(File output)throws Exception{
        Fixture f=populated();EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);EmbeddedDuplexNative.renderer=false;
        recover(f,true);Thread.sleep(150);
        Path checkpoint=new File(f.root,"embedded-duplex-replay/start-checkpoint.v1.json").toPath();byte[] exact=Files.readAllBytes(checkpoint);Files.writeString(checkpoint,new String(exact,java.nio.charset.StandardCharsets.UTF_8)+" ");
        EmbeddedDuplexNative.renderer=true;JSONObject outcome=json(await(f));yes(outcome.getString("state").equals("unknown")&&EmbeddedDuplexNative.retirements==0&&f.host.harnessFence().recoveryOnly(),"journal mutation was allowed through actual token");save(output,"new-unknown-original",outcome);Files.write(checkpoint,exact);requirePreserved(f);close(f);
    }
    static void populatedCrashBeforePrepare(File output,boolean tamper)throws Exception{
        Fixture f=populated();EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);close(f);
        Path proc=Path.of(System.getProperty("host.proc"));String stat=Files.readString(proc.resolve("42/stat"));
        Files.delete(proc.resolve("42/stat"));Files.delete(proc.resolve("42"));Files.createDirectories(proc.resolve("43"));Files.writeString(proc.resolve("43/stat"),stat.replaceFirst("42","43"));System.setProperty("host.pid","43");
        try{
            if(tamper){Path witness=new File(f.root,"embedded-duplex-replay/process-incarnation.phase."+EmbeddedDuplexProcessFence.digest(f.sourceRecord)+".record").toPath();Files.writeString(witness,EmbeddedDuplexProcessFence.recordedPhase(f.sourceRecord,"clear"));}
            f.host=EmbeddedDuplexProcessHost.forApplication(f.context);EmbeddedDuplexRuntimeService.runStatus(f.context).get(5,TimeUnit.SECONDS);recover(f,true);
            JSONObject row=json(await(f));yes(row.getString("state").equals(tamper?"unknown":"succeeded"),"crash-before-prepare recovery result differs");
            yes(EmbeddedDuplexNative.retirements==(tamper?0:1)&&EmbeddedDuplexNative.nativeGeneration==4,"predecessor witness bypassed Native counter/retirement");requirePreserved(f);save(output,"new-original",row);
            if(!tamper)save(output,"receipt",new JSONObject(row.getJSONObject("response").getString("receipt")));close(f);
        }finally{
            System.clearProperty("host.pid");Files.createDirectories(proc.resolve("42"));Files.writeString(proc.resolve("42/stat"),stat);Files.delete(proc.resolve("43/stat"));Files.delete(proc.resolve("43"));
        }
    }
    static Fixture captured(File manifest,String peer)throws Exception{
        JSONObject data=new JSONObject(Files.readString(manifest.toPath())).getJSONObject(peer);A=data.getString("prior_run_id");B=data.getString("run_id");PRIOR=data.getString("prior_original_id");
        Fixture f=new Fixture();f.app=Files.createTempDirectory("cold-captured-"+peer).toFile();String packageId=data.getString("package_id");
        f.context=new Context(){public File getNoBackupFilesDir(){return f.app;}public String getPackageName(){return packageId;}};
        JSONObject files=data.getJSONObject("files");Iterator<String> paths=files.keys();while(paths.hasNext()){
            String destination=paths.next();JSONObject reference=files.getJSONObject(destination);byte[] raw=Files.readAllBytes(Path.of(reference.getString("path")));
            yes(EmbeddedDuplexCanonicalJson.sha256Bytes(raw).equals(reference.getString("sha256")),"captured input changed");Path target=f.app.toPath().resolve(destination);Files.createDirectories(target.getParent());Files.write(target,raw);
        }
        f.root=new File(f.app,"embedded-duplex-runs-v1/"+B);f.prior=new File(f.app,"embedded-duplex-runs-v1/"+A);Files.createDirectories(f.prior.toPath());
        Files.writeString(new File(f.prior,"run.v1.json").toPath(),EmbeddedDuplexCanonicalJson.encode(new JSONObject().put("schema","rusty.quest.embedded_duplex.operational_run.v1").put("run_id",A).put("created_from_generation",0).put("release_source",JSONObject.NULL)));
        JSONObject observation=new JSONObject(Files.readString(Path.of(data.getJSONObject("source_observation").getString("path"))));JSONObject row=observation.getJSONObject("original");
        File originals=new File(f.prior,"operator-originals-v1");yes(originals.mkdir(),"captured Original directory");Files.writeString(new File(originals,PRIOR+".json").toPath(),EmbeddedDuplexCanonicalJson.encode(row));f.priorBytes=Files.readAllBytes(new File(originals,PRIOR+".json").toPath());
        f.markerBytes=Files.readAllBytes(new File(f.root,"run.v1.json").toPath());
        EmbeddedDuplexProcessIncarnation.RecoveryBinding prior=EmbeddedDuplexProcessIncarnation.readForRecovery(new File(f.prior,"embedded-duplex-replay"));
        f.sourceRecord=EmbeddedDuplexProcessFence.recordedPhase(prior.binding.originalRecord,"pending");
        System.setProperty("host.uid",String.valueOf(prior.binding.owner.uid));Path proc=Path.of(System.getProperty("host.proc"));Files.writeString(proc.resolve("sys/kernel/random/boot_id"),prior.binding.owner.boot+"\n");
        EmbeddedDuplexNative.reset(f.sourceRecord,prior.binding.owner.json());EmbeddedDuplexNative.nativeDigest=EmbeddedDuplexCanonicalJson.sha256Bytes(Files.readAllBytes(new File(f.app,"embedded-duplex-replay/native-process-fence.v1.lock").toPath()));
        yes(row.getJSONObject("actor_binding").getString("app_record_sha256").equals(EmbeddedDuplexProcessFence.digest(f.sourceRecord)),"captured Native/Original/companion link differs");
        yes(!new File(f.prior,"embedded-duplex-fresh-handoff-v1").exists(),"fixture accidentally supplied historical closure");f.host=EmbeddedDuplexProcessHost.forApplication(f.context);return f;
    }
}

/** Deliberate Native leaf consuming actual Java token callbacks; real Rust behavior is tested separately. */
final class EmbeddedDuplexNative {
    static boolean eligible=true,renderer=true,maintained;static String actualRun;static Long nativeGeneration=3L;static int retirements;
    static JSONObject owner;static String sourceRecord,nativeDigest;
    static void reset(String record,JSONObject identity){reset(record,identity,3L);}
    static void reset(String record,JSONObject identity,long counter){sourceRecord=record;nativeDigest=null;owner=new JSONObject().put("counter",counter).put("app_record",record).put("process_identity",identity);maintained=false;nativeGeneration=counter;actualRun=null;eligible=true;renderer=true;retirements=0;}
    static String inspectOperationalRun(Object fence){JSONObject value=new JSONObject().put("$schema","rusty.quest.embedded_duplex.operational_run_native.v1").put("globally_eligible",eligible).put("operational_run_id",actualRun==null?JSONObject.NULL:actualRun).put("native_generation",nativeGeneration);if(!eligible)value.put("refusal_reason","native_prior_process_owner_unreconciled");return value.toString();}
    static String readColdPendingOwner(Object fence)throws Exception{return new JSONObject().put("$schema","rusty.quest.embedded_duplex.cold_pending_native_owner.v1").put("native_generation",nativeGeneration).put("native_record_sha256",nativeDigest==null?EmbeddedDuplexProcessFence.digest(sourceRecord):nativeDigest).put("owner",maintained?owner:JSONObject.NULL).toString();}
    static boolean localCameraQuiescent(){return true;}
    static String retireColdPendingLocal(Object source)throws Exception{
        EmbeddedDuplexProcessFence fence=(EmbeddedDuplexProcessFence)source;String record=fence.nativeColdRecoveryRecord(),exact=fence.nativeColdRecoveryEvidence();JSONObject link=new JSONObject(exact);
        if(!record.equals(fence.nativeColdRecoveryRecord()))throw new IllegalStateException("fence changed");
        JSONObject receipt=actualProjection(new JSONObject().put("record",record).put("link",exact).put("counter",nativeGeneration));
        owner.put("retirement",new JSONObject().put("receipt",receipt).put("current_process_identity",link.getJSONObject("current_process_identity")).put("app_record",record));maintained=true;eligible=true;retirements++;return receipt.toString();
    }
    static String claimNativeProcessFence(Object source)throws Exception{
        EmbeddedDuplexProcessFence fence=(EmbeddedDuplexProcessFence)source;JSONObject handoff=new JSONObject(fence.nativeOperationalRunHandoff());
        String record=fence.nativeAdmissionRecord();actualProjection(new JSONObject().put("kind","handoff").put("record",record).put("handoff",handoff.toString()).put("counter",nativeGeneration));
        sourceRecord=record;owner=new JSONObject().put("counter",nativeGeneration+1).put("app_record",record).put("process_identity",EmbeddedDuplexProcessIncarnation.current(android.os.Process.myPid(),android.os.Process.myUid()).json());maintained=true;return new JSONObject().put("$schema","rusty.quest.embedded_duplex.native_fence_admitted.v1").put("app_generation",fence.generation()).put("app_record_sha256",EmbeddedDuplexProcessFence.digest(record)).put("executor_generation",++nativeGeneration).toString();
    }
    static JSONObject actualProjection(JSONObject request)throws Exception{
        java.lang.Process producer=new ProcessBuilder("wsl.exe","--",System.getProperty("host.native.abi")).start();
        try(OutputStream input=producer.getOutputStream()){input.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        String raw=new String(producer.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(producer.waitFor()!=0)throw new IllegalStateException("actual Native ABI projection/validation failed: "+new String(producer.getErrorStream().readAllBytes()));
        return new JSONObject(raw);
    }
}
