package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONObject;
/** Actual request/fence/archive paths; Android/kernel/remote absence and physical proof are modeled. */
final class EmbeddedDuplexMixedPostMediaAbandonmentHostTest {
    static final String SCHEMA="rusty.quest.embedded_duplex.abandonment_authorization.v8";
    static final String BOOT=EmbeddedDuplexPostMediaAbandonmentHostTest.BOOT;
    static final String[] RECORDS=EmbeddedDuplexPostMediaAbandonmentHostTest.RECORDS;
    static final String MAP=EmbeddedDuplexPostMediaAbandonmentHostTest.MAP;
    static int controls;
    interface Call{void run()throws Exception;}
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);controls++;}
    static void deny(Call action)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IllegalStateException|org.json.JSONException expected){controls++;return;}
        throw new AssertionError("invalid mixed scope accepted");
    }
    static JSONObject copy(JSONObject x){return new JSONObject(x.toString());}
    static JSONObject clearPeer(Path dir,String serial,int uid,String checkpoint,String evidence,String cleanup)throws Exception{
        String epoch,record;
        try(EmbeddedDuplexProcessFence old=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            old.beforeRuntimeEffects(checkpoint,evidence);
            // Physical-owner whole proof is a modeled leaf; actual fence owner earns the clear record.
            old.afterVerifiedWholeProductCleanup(checkpoint,evidence,"e".repeat(64));epoch=old.epochId();record=old.retainedRecord();
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
    static JSONObject auth(EmbeddedDuplexProcessFence current,JSONObject a,JSONObject b,String role)throws Exception{
        JSONObject selected=role.equals("peer_a")?a:b;
        JSONObject auth=EmbeddedDuplexKnownBirthAbandonmentHostTest.auth(current,a,b)
            .put("$schema",SCHEMA).put("scope_kind","single_pending_nonaccepting").put("role_id",role);
        for(String key:new String[]{"package_id","signer_sha256","physical_serial","boot_id","user_id","package_uid"})
            auth.put(key,selected.get(key));
        return auth;
    }
    static Map<String,String> snapshot(Path directory)throws Exception{
        Map<String,String> result=new TreeMap<>();
        try(java.util.stream.Stream<Path> files=Files.walk(directory)){
            for(Path p:files.filter(Files::isRegularFile).toList())result.put(directory.relativize(p).toString(),Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        }
        return result;
    }
    static final class Fixture implements AutoCloseable{
        Path selected,other;JSONObject pending,clear,a,b,authorization;EmbeddedDuplexProcessFence current;String checkpoint,evidence;
        Fixture(String role,boolean journals)throws Exception{this(role,journals,()->{});}
        Fixture(String role,boolean journals,EmbeddedDuplexProcessFence.DirectorySync sync)throws Exception{
            selected=Files.createTempDirectory("mixed-pending");other=Files.createTempDirectory("mixed-clear");
            boolean first=role.equals("peer_a");int uid=first?10001:10002;
            checkpoint=journals?"selected checkpoint":null;evidence=journals?"selected evidence":null;
            pending=EmbeddedDuplexPostMediaAbandonmentHostTest.peer(selected,first?"A":"B",uid,checkpoint,evidence,MAP);
            clear=clearPeer(other,first?"B":"A",first?10002:10001,journals?"clear exact terminal checkpoint":null,null,null);
            a=first?pending:clear;b=first?clear:pending;
            current=EmbeddedDuplexProcessFence.acquire(selected.toFile(),checkpoint,evidence,sync);
            authorization=auth(current,a,b,role);
        }
        public void close()throws Exception{current.close();}
    }
    static void transaction(String role,boolean journals)throws Exception{
        try(Fixture f=new Fixture(role,journals)){
            Map<String,String> otherBefore=snapshot(f.other);String unknown="{\"state\":\"unknown\",\"outcome\":\"retained\",\"consumer_receipt_sha256\":null}";
            Path original=f.selected.resolve("retained-original.json");Files.writeString(original,unknown);
            String floor=Files.readString(f.selected.resolve(RECORDS[3]));
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(f.authorization.toString());
            check(request.historical()&&request.requiresBootObservation()&&!request.crossBoot(),"v8 classification lost");
            request.requireCurrent(900,f.authorization.getInt("package_uid"),12345,f.current.generation());request.requireBoot(BOOT);
            request.requireInstalled("test.product.app","b".repeat(64),"c".repeat(64),"test.product.app/test.Activity",f.authorization.getInt("package_uid"),900);
            JSONObject receipt=new JSONObject(f.current.abandon(f.checkpoint,f.evidence,request,(p,d)->{}));
            check(receipt.getJSONObject("operator_authorization").toString().equals(f.authorization.toString()),"exact typed v8 authorization lost");
            check(!receipt.getBoolean("old_cleanup_confirmed")&&!receipt.getBoolean("old_authority_transferred"),"cleanup/transfer invented");
            String scope=receipt.getString("scope_sha256");Path archive=f.selected.resolve("abandoned-"+scope);
            check(scope.equals(f.pending.getString("original_fence_sha256")),"nonselected scope disposed");
            check(Files.isRegularFile(archive.resolve("committed-fence.raw")),"commit witness absent");
            check(EmbeddedDuplexOriginalScopeDisposition.disposed(f.selected.toFile(),scope,f.current.retainedRecord(),f.current.generation(),f.authorization.getInt("package_uid"),"test.product.app"),"selected committed scope not disposed");
            for(String name:RECORDS){
                if(Files.exists(f.selected.resolve(name)))check(Files.readString(f.selected.resolve(name)).equals(Files.readString(archive.resolve("preserved-"+name))),"raw retained bytes changed");
            }
            check(new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[0],MAP)).getJSONObject("pending_request_sha256").isEmpty(),"owner replay not projected");
            check(new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[2],"{\"originals\":{},\"prepared\":{}}")).isEmpty()==false,"preparation shape lost");
            check(floor.equals(EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[3],floor)),"native floor hidden");
            check((MAP+" ").equals(EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[0],MAP+" ")),"new current replay hidden");
            check(unknown.equals(Files.readString(original)),"retained Original changed");
            check(otherBefore.equals(snapshot(f.other)),"clear peer mutated");
            deny(()->f.current.abandon(f.checkpoint,f.evidence,request,(p,d)->{}));
            Path witness=archive.resolve("committed-fence.raw");String committed=Files.readString(witness);
            Files.delete(witness);deny(()->EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[0],MAP));
            Files.writeString(witness,"torn");deny(()->EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[0],MAP));Files.writeString(witness,committed);
            f.current.close();f.current=EmbeddedDuplexProcessFence.acquire(f.selected.toFile(),null,null,()->{});f.current.requireFresh();
            check(new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[0],MAP)).getJSONObject("pending_request_sha256").isEmpty(),"restart lost v8 projection");
        }
    }
    static void negativeControls()throws Exception{
        try(Fixture f=new Fixture("peer_a",true)){
            JSONObject good=f.authorization;
            deny(()->new EmbeddedDuplexAbandonmentRequest(copy(good).put("$schema",EmbeddedDuplexPostMediaAbandonment.SCHEMA).put("scope_kind","post_media_nonaccepting").toString()));
            List<Consumer<JSONObject>> edits=List.of(
                x->x.put("role_id","peer_b"),x->x.put("scope_kind","post_media_nonaccepting"),x->x.put("$schema",SCHEMA+"x"),
                x->x.put("authorize_abandonment",false),x->x.put("extra",true),x->x.remove("historical_peers"),
                x->x.put("expected_predecessor_sha256","d".repeat(64)),x->x.put("signer_sha256","d".repeat(64)),
                x->x.getJSONObject("current_recovery_actor").put("start_ticks",456),
                x->x.getJSONObject("current_recovery_actor").put("pid",true),x->x.getJSONObject("current_recovery_actor").put("app_process_generation",2.5),
                x->x.getJSONObject("historical_peers").put("extra",copy(f.clear)));
            for(Consumer<JSONObject> edit:edits){JSONObject bad=copy(good);edit.accept(bad);deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));}
            for(String role:new String[]{"peer_a","peer_b"}){
                List<Consumer<JSONObject>> peerEdits=List.of(
                    x->x.put("complete_absence_confirmed",false),x->x.put("absence_scope","pid_only"),x->x.put("extra",true),
                    x->x.put("original_start_ticks",457),x->x.put("original_pid",true),x->x.put("original_start_ticks",456.5),
                    x->x.put("package_uid","10001"),x->x.put("original_fence_sha256","d".repeat(64)),
                    x->x.put("original_fence_record",x.getString("original_fence_record")+"x"),
                    x->x.put("checkpoint_sha256","d".repeat(64)),x->x.put("original_birth_receipt_json","{}"),
                    x->x.put("original_birth_receipt_sha256","d".repeat(64)),x->x.put("installed_apk_sha256","d".repeat(64)),
                    x->x.getJSONObject("preserved_records").remove(RECORDS[0]),x->x.getJSONObject("preserved_records").put(RECORDS[0],JSONObject.NULL));
                for(Consumer<JSONObject> edit:peerEdits){JSONObject bad=copy(good);edit.accept(bad.getJSONObject("historical_peers").getJSONObject(role));deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));}
            }
            JSONObject twoPending=copy(good);twoPending.getJSONObject("historical_peers").put("peer_b",EmbeddedDuplexPostMediaAbandonmentHostTest.peer(Files.createTempDirectory("second-pending"),"B",10002,null,null,null));
            deny(()->new EmbeddedDuplexAbandonmentRequest(twoPending.toString()));
            JSONObject twoClear=copy(good);twoClear.getJSONObject("historical_peers").put("peer_a",clearPeer(Files.createTempDirectory("second-clear"),"A",10001,null,null,null));
            deny(()->new EmbeddedDuplexAbandonmentRequest(twoClear.toString()));
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
            deny(()->request.requireBoot("00000000-0000-0000-0000-000000000002"));
            deny(()->request.requireCurrent(901,10001,12345,f.current.generation()));deny(()->request.requireCurrent(900,10002,12345,f.current.generation()));
            deny(()->request.requireCurrent(900,10001,12346,f.current.generation()));deny(()->request.requireCurrent(900,10001,12345,f.current.generation()+1));
            deny(()->request.bind(f.pending.getString("original_fence_record"),f.current.retainedRecord(),"changed",f.evidence));
            deny(()->request.bind(f.clear.getString("original_fence_record"),f.current.retainedRecord(),f.checkpoint,f.evidence));
            String exact=Files.readString(f.selected.resolve(RECORDS[0]));Files.writeString(f.selected.resolve(RECORDS[0]),exact+" ");
            deny(()->f.current.abandon(f.checkpoint,f.evidence,request,(p,d)->{}));
        }
    }
    static void mirrorAndFailureControls()throws Exception{
        try(Fixture f=new Fixture("peer_b",false)){
            JSONObject twoPending=copy(f.authorization);twoPending.getJSONObject("historical_peers").put("peer_a",EmbeddedDuplexPostMediaAbandonmentHostTest.peer(Files.createTempDirectory("mirror-pending"),"A",10001,null,null,null));
            deny(()->new EmbeddedDuplexAbandonmentRequest(twoPending.toString()));
            JSONObject twoClear=copy(f.authorization);twoClear.getJSONObject("historical_peers").put("peer_b",clearPeer(Files.createTempDirectory("mirror-clear"),"B",10002,null,null,null));
            deny(()->new EmbeddedDuplexAbandonmentRequest(twoClear.toString()));
            deny(()->new EmbeddedDuplexAbandonmentRequest(copy(f.authorization).put("role_id","peer_a").toString()));
        }
        try(Fixture f=new Fixture("peer_a",false)){
            Files.writeString(f.selected.resolve(RECORDS[0]+".new"),"unresolved sidecar");
            deny(()->f.current.abandon(f.checkpoint,f.evidence,new EmbeddedDuplexAbandonmentRequest(f.authorization.toString()),(p,d)->{}));
        }
        for(boolean witnessFailure:new boolean[]{false,true}){
            int[] syncs={0};Fixture f=new Fixture("peer_a",false,()->{if(!witnessFailure&&++syncs[0]==3)throw new IllegalStateException("injected before clear commit");});
            try{
                deny(()->f.current.abandon(null,null,new EmbeddedDuplexAbandonmentRequest(f.authorization.toString()),(p,d)->{
                    if(witnessFailure&&p.getName().equals("committed-fence.raw"))throw new IllegalStateException("injected witness failure");
                }));
            }finally{f.close();}
            if(witnessFailure)deny(()->EmbeddedDuplexProcessFence.acquire(f.selected.toFile(),null,null,()->{}));
            else try(EmbeddedDuplexProcessFence next=EmbeddedDuplexProcessFence.acquire(f.selected.toFile(),null,null,()->{})){
                check(next.recoveryOnly(),"interrupted pending scope became fresh");
                check(MAP.equals(EmbeddedDuplexAbandonment.visibleReplay(f.selected.toFile(),RECORDS[0],MAP)),"uncommitted v8 replay hidden");
            }
        }
    }
    public static void main(String[]args)throws Exception{
        transaction("peer_a",false);transaction("peer_b",true);negativeControls();mirrorAndFailureControls();
        System.out.println("PASS mixed v8 controls="+controls+" real request/fence/archive/replay/restart, modeled Android/kernel/absence/physical-proof leaves; no devices");
    }
}
