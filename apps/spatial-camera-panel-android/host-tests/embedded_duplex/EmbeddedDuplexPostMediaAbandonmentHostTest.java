package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Real production archive/fence/readers; kernel birth and complete absence are modeled. */
final class EmbeddedDuplexPostMediaAbandonmentHostTest {
    static final String SCHEMA="rusty.quest.embedded_duplex.abandonment_authorization.v7";
    static final String BOOT="00000000-0000-0000-0000-000000000001";
    static final String MAP="{\"pending_request_sha256\":{\"old\":\""+"a".repeat(64)+"\"},\"terminal\":{}}";
    static final String[] RECORDS={"owner-dispatch.v1.json","product-activation.v1.json",
        "retained-cleanup-preparations.v1.json","native-process-fence.v1.lock","retained-cleanup.v2.json"};
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}
    static void deny(EmbeddedDuplexKnownBirthAbandonmentHostTest.Attempt action)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IllegalStateException|org.json.JSONException expected){return;}
        throw new AssertionError("invalid post-media scope accepted");
    }
    static JSONObject peer(Path dir,String serial,int uid,String checkpoint,String evidence,String cleanup)throws Exception{
        String epoch,record;
        try(EmbeddedDuplexProcessFence old=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            old.beforeRuntimeEffects(checkpoint,evidence);epoch=old.epochId();record=old.retainedRecord();
        }
        if(checkpoint!=null)Files.writeString(dir.resolve("start-checkpoint.v1.json"),checkpoint);
        if(evidence!=null)Files.writeString(dir.resolve("recovery-evidence.v1.json"),evidence);
        JSONObject birth=new JSONObject().put("schema","rusty.quest.embedded_duplex.known_actor_birth.v1")
            .put("pid",123).put("start_ticks",456).put("uid",uid).put("app_process_generation",1)
            .put("process_epoch_id",epoch).put("physical_serial",serial).put("boot_id",BOOT)
            .put("installed_apk_sha256","a".repeat(64));
        JSONObject preserved=new JSONObject();
        for(String name:RECORDS){
            String body=name.equals("retained-cleanup.v2.json")?cleanup:
                name.equals("native-process-fence.v1.lock")?"retained native generation floor":
                name.equals("retained-cleanup-preparations.v1.json")?"{\"originals\":{},\"prepared\":{}}":MAP;
            if(body!=null)Files.writeString(dir.resolve(name),body);
            preserved.put(name,body==null?JSONObject.NULL:EmbeddedDuplexProcessFence.digest(body));
        }
        return new JSONObject().put("package_id","test.product.app").put("installed_apk_sha256","a".repeat(64))
            .put("signer_sha256","b".repeat(64)).put("physical_serial",serial).put("boot_id",BOOT)
            .put("user_id",0).put("package_uid",uid).put("original_pid",123).put("original_start_ticks",456)
            .put("original_birth_status","known_birth_recorded").put("original_birth_receipt_json",birth.toString())
            .put("original_birth_receipt_sha256",EmbeddedDuplexProcessFence.digest(birth.toString()))
            .put("original_fence_record",record).put("original_fence_sha256",EmbeddedDuplexProcessFence.digest(record))
            .put("checkpoint_sha256",EmbeddedDuplexProcessFence.digest(checkpoint))
            .put("evidence_sha256",EmbeddedDuplexProcessFence.digest(evidence))
            .put("absence_scope","complete_package_uid_scope").put("complete_absence_confirmed",true)
            .put("preserved_records",preserved);
    }
    static JSONObject auth(EmbeddedDuplexProcessFence current,JSONObject a,JSONObject b)throws Exception{
        return EmbeddedDuplexKnownBirthAbandonmentHostTest.auth(current,a,b).put("$schema",SCHEMA)
            .put("scope_kind","post_media_nonaccepting");
    }
    static void transaction(String checkpoint,String evidence,String cleanup)throws Exception{
        Path dir=Files.createTempDirectory("post-media-a"),other=Files.createTempDirectory("post-media-b");
        JSONObject a=peer(dir,"A",10001,checkpoint,evidence,cleanup),b=peer(other,"B",10002,null,null,null);
        Path originals=dir.resolveSibling(dir.getFileName()+"-originals");Files.createDirectory(originals);
        Path original=originals.resolve("1".repeat(32)+".json");String unknown="{\"state\":\"unknown\",\"outcome\":\"retained\",\"consumer_receipt_sha256\":null}";
        Files.writeString(original,unknown);String floor=Files.readString(dir.resolve(RECORDS[3]));
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),checkpoint,evidence,()->{})){
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
            request.requireBoot(BOOT);request.requireCurrent(900,10001,12345,current.generation());
            JSONObject receipt=new JSONObject(current.abandon(checkpoint,evidence,request,(path,directory)->{}));
            check(!receipt.getBoolean("old_cleanup_confirmed")&&!receipt.getBoolean("old_authority_transferred"),"false cleanup/transfer changed");
            String scope=receipt.getString("scope_sha256");Path archive=dir.resolve("abandoned-"+scope);
            check(Files.isRegularFile(archive.resolve("committed-fence.raw")),"commit witness absent");
            check(EmbeddedDuplexOriginalScopeDisposition.disposed(dir.toFile(),scope,current.retainedRecord(),current.generation(),10001,"test.product.app"),"committed old Original scope still blocks");
            check(!EmbeddedDuplexOriginalScopeDisposition.disposed(dir.toFile(),scope,current.retainedRecord(),current.generation(),10002,"test.product.app"),"foreign UID scope accepted");
            for(String name:new String[]{RECORDS[0],RECORDS[1]}){
                JSONObject projected=new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),name,MAP));
                check(projected.getJSONObject("pending_request_sha256").isEmpty()&&projected.getJSONObject("terminal").isEmpty(),"post-media replay still active");
            }
            check(floor.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[3],floor)),"native floor hidden");
            check(floor.equals(Files.readString(dir.resolve(RECORDS[3]))),"native floor changed");
            for(String name:RECORDS){
                if(name.equals(RECORDS[3]))continue;
                String newer=name.equals(RECORDS[2])?"{\"originals\":{\"new\":{}},\"prepared\":{}}":MAP+" ";
                check(newer.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),name,newer)),"new/changed current record hidden");
            }
            for(String name:RECORDS){
                if(Files.exists(dir.resolve(name)))check(Files.readString(dir.resolve(name)).equals(Files.readString(archive.resolve("preserved-"+name))),"raw native archive lost exact bytes");
            }
            Path witness=archive.resolve("committed-fence.raw");String clear=Files.readString(witness);
            Files.delete(witness);deny(()->EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[0],MAP));
            check(EmbeddedDuplexAbandonment.committedOriginalScope(dir.toFile(),scope,10001,"test.product.app")==null,"missing witness excluded Original");
            Files.writeString(witness,"torn");deny(()->EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[0],MAP));Files.writeString(witness,clear);
            Files.writeString(archive.resolve("preserved-"+RECORDS[0]+".bak"),"sidecar");
            deny(()->EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[0],MAP));Files.delete(archive.resolve("preserved-"+RECORDS[0]+".bak"));
            check(unknown.equals(Files.readString(original)),"UNKNOWN Original changed");current.requireFresh();
            String output=System.getProperty("rusty.quest.postMedia.output");
            if(output!=null&&checkpoint!=null&&cleanup!=null){
                Path target=Path.of(output);Files.createDirectory(target);
                Files.writeString(target.resolve("raw-owner.json"),MAP);Files.writeString(target.resolve("raw-activation.json"),MAP);
                Files.writeString(target.resolve("projected-owner.json"),EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[0],MAP));
                Files.writeString(target.resolve("projected-activation.json"),EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[1],MAP));
                Files.writeString(target.resolve("authorization.json"),request.json().toString());
                Files.writeString(target.resolve("receipt.json"),receipt.toString());
            }
        }
        try(EmbeddedDuplexProcessFence next=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            next.requireFresh();check(new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[0],MAP)).getJSONObject("pending_request_sha256").isEmpty(),"committed projection lost on restart");
        }
    }
    static void closedControls()throws Exception{
        Path dir=Files.createTempDirectory("post-media-closed"),other=Files.createTempDirectory("post-media-closed-peer");
        JSONObject a=peer(dir,"A",10001,"checkpoint","evidence",MAP),b=peer(other,"B",10002,null,null,null);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),"checkpoint","evidence",()->{})){
            JSONObject good=auth(current,a,b);EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
            check(request.requiresBootObservation()&&!request.crossBoot(),"same-boot verification omitted");
            deny(()->request.requireBoot("00000000-0000-0000-0000-000000000002"));
            deny(()->request.requireCurrent(900,10001,12346,current.generation()));
            deny(()->request.requireCurrent(900,10002,12345,current.generation()));
            deny(()->request.requireCurrent(900,10001,12345,current.generation()+1));
            request.requireInstalled("test.product.app","b".repeat(64),"c".repeat(64),"test.product.app/test.Activity",10001,900);
            deny(()->request.requireInstalled("test.product.app","d".repeat(64),"c".repeat(64),"test.product.app/test.Activity",10001,900));
            deny(()->request.bind(a.getString("original_fence_record"),current.retainedRecord(),"changed","evidence"));
            deny(()->request.bind(a.getString("original_fence_record"),current.retainedRecord(),"checkpoint",null));
            deny(()->request.bind(b.getString("original_fence_record"),current.retainedRecord(),"checkpoint","evidence"));
            java.util.List<java.util.function.Consumer<JSONObject>> edits=java.util.List.of(
                x->x.put("scope_kind","no_media"),x->x.put("authorize_abandonment",false),x->x.put("unexpected",true),
                x->x.put("current_boot_id",BOOT),x->x.put("old_pid",123),x->x.put("signer_sha256","d".repeat(64)),
                x->x.getJSONObject("current_recovery_actor").put("start_ticks",456),
                x->x.put("expected_predecessor_sha256","d".repeat(64)));
            for(java.util.function.Consumer<JSONObject> edit:edits){JSONObject bad=copy(good);edit.accept(bad);deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));}
            for(String role:new String[]{"peer_a","peer_b"}){
                java.util.List<java.util.function.Consumer<JSONObject>> peerEdits=java.util.List.of(
                    x->x.put("complete_absence_confirmed",false),x->x.put("absence_scope","pidof_only"),
                    x->x.put("original_start_ticks",457),x->x.put("original_fence_sha256","d".repeat(64)),
                    x->x.put("checkpoint_sha256","d".repeat(64)),x->x.put("original_actor_receipt_json","invented ARM"),
                    x->x.getJSONObject("preserved_records").remove(RECORDS[4]),
                    x->x.getJSONObject("preserved_records").put(RECORDS[0],JSONObject.NULL));
                for(java.util.function.Consumer<JSONObject> edit:peerEdits){JSONObject bad=copy(good);edit.accept(bad.getJSONObject("historical_peers").getJSONObject(role));deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));}
            }
            JSONObject skipped=copy(good);skipped.getJSONObject("current_recovery_actor").put("app_process_generation",current.generation()+1);
            deny(()->new EmbeddedDuplexAbandonmentRequest(skipped.toString()).bind(a.getString("original_fence_record"),current.retainedRecord(),"checkpoint","evidence"));
            JSONObject noMedia=copy(good).put("$schema",EmbeddedDuplexKnownBirthAbandonment.REPLAY_SCOPE_SCHEMA);
            deny(()->new EmbeddedDuplexAbandonmentRequest(noMedia.toString()));
        }
    }
    static void refusal(String name,String suffix,boolean drift,boolean duringArchive)throws Exception{
        Path dir=Files.createTempDirectory("post-media-fault"),other=Files.createTempDirectory("post-media-fault-peer");
        JSONObject a=peer(dir,"A",10001,"checkpoint","evidence",MAP),b=peer(other,"B",10002,null,null,null);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),"checkpoint","evidence",()->{})){
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
            if(suffix!=null)Files.writeString(dir.resolve(name+suffix),"sidecar");
            if(drift)Files.writeString(dir.resolve(name),"changed");
            deny(()->current.abandon("checkpoint","evidence",request,(path,directory)->{
                if(duringArchive&&path.getName().startsWith("preserved-"))Files.writeString(dir.resolve(name),"changed during archive");
            }));
            check(current.recoveryOnly(),"fault cleared recovery-only");
        }
    }
    static void interruption(boolean witnessFailure)throws Exception{
        Path dir=Files.createTempDirectory("post-media-crash"),other=Files.createTempDirectory("post-media-crash-peer");
        JSONObject a=peer(dir,"A",10001,"checkpoint","evidence",MAP),b=peer(other,"B",10002,null,null,null);int[] syncs={0};
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),"checkpoint","evidence",()->{
            if(!witnessFailure&&++syncs[0]==3)throw new IllegalStateException("injected before clear commit");
        })){
            deny(()->current.abandon("checkpoint","evidence",new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString()),(path,directory)->{
                if(witnessFailure&&path.getName().equals("committed-fence.raw"))throw new IllegalStateException("injected missing witness");
            }));
        }
        if(witnessFailure){deny(()->EmbeddedDuplexProcessFence.acquire(dir.toFile(),"checkpoint","evidence",()->{}));return;}
        try(EmbeddedDuplexProcessFence next=EmbeddedDuplexProcessFence.acquire(dir.toFile(),"checkpoint","evidence",()->{})){
            check(next.recoveryOnly(),"precommit interruption admitted fresh scope");deny(next::requireFresh);
            check(MAP.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),RECORDS[0],MAP)),"uncommitted replay hidden");
        }
    }
    public static void main(String[] args)throws Exception{
        transaction("recorded post-media checkpoint","recorded recovery lineage",MAP);
        transaction(null,null,null);transaction("checkpoint only",null,null);transaction(null,"evidence only",MAP);
        closedControls();interruption(false);interruption(true);
        for(String name:RECORDS){refusal(name,".new",false,false);refusal(name,".bak",false,false);refusal(name,null,true,false);refusal(name,null,false,true);}
        for(String name:new String[]{"start-checkpoint.v1.json","recovery-evidence.v1.json"}){
            refusal(name,".new",false,false);refusal(name,".bak",false,false);refusal(name,null,true,false);
        }
        System.out.println("Post-media v7 production archive/commit/restart, present/absent journals+cleanup, exact replay/new scope/native floor, Original preservation, closed identity/peer/journal, tamper/sidecar/drift/interruption controls PASS; actor/absence modeled.");
    }
}
