package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Executes the real durable transaction. External process absence is explicitly modeled. */
final class EmbeddedDuplexReplayScopeV5HostTest {
    static final String NAME="retained-cleanup.v2.json";
    static final String SCHEMA="rusty.quest.embedded_duplex.abandonment_authorization.v5";
    static void deny(EmbeddedDuplexKnownBirthAbandonmentHostTest.Attempt action)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IllegalStateException|org.json.JSONException expected){return;}
        throw new AssertionError("negative control accepted");
    }
    static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}
    static JSONObject peer(Path dir,String serial,int uid,String cleanup)throws Exception{
        JSONObject peer=EmbeddedDuplexKnownBirthAbandonmentHostTest.peer(dir,serial,uid);
        if(cleanup!=null)Files.writeString(dir.resolve(NAME),cleanup);
        peer.getJSONObject("preserved_records").put(NAME,cleanup==null?JSONObject.NULL:EmbeddedDuplexProcessFence.digest(cleanup));
        return peer;
    }
    static JSONObject auth(EmbeddedDuplexProcessFence current,JSONObject a,JSONObject b)throws Exception{
        return EmbeddedDuplexKnownBirthAbandonmentHostTest.auth(current,a,b).put("$schema",SCHEMA);
    }
    static void transaction(boolean present)throws Exception{
        Path dir=Files.createTempDirectory("replay-v5-a"),other=Files.createTempDirectory("replay-v5-b");
        // A pending old request is not upgraded to success: exact retired scope is nonaccepting.
        String old="{\"pending_request_sha256\":{\"old\":\""+"a".repeat(64)+"\"},\"terminal\":{}}";
        JSONObject a=peer(dir,"A",10001,present?old:null),b=peer(other,"B",10002,null);
        String nativeBefore=Files.readString(dir.resolve("native-process-fence.v1.lock"));
        Files.createDirectories(dir.resolve("originals"));Files.writeString(dir.resolve("originals/old.json"),"UNKNOWN exact outcome");
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            JSONObject good=auth(current,a,b);
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
            request.requireBoot(a.getString("boot_id"));
            deny(()->request.requireBoot("00000000-0000-0000-0000-000000000002"));
            deny(()->request.requireCurrent(900,10001,12346,current.generation()));
            deny(()->request.bind(b.getString("original_fence_record"),current.retainedRecord(),null,null));
            JSONObject legacy=copy(good).put("$schema",EmbeddedDuplexKnownBirthAbandonment.SCHEMA);
            deny(()->new EmbeddedDuplexAbandonmentRequest(legacy.toString()));
            JSONObject missing=copy(good);missing.getJSONObject("historical_peers").getJSONObject("peer_a").getJSONObject("preserved_records").remove(NAME);
            deny(()->new EmbeddedDuplexAbandonmentRequest(missing.toString()));
            JSONObject wrong=copy(good);wrong.getJSONObject("historical_peers").getJSONObject("peer_a").getJSONObject("preserved_records").put("owner-dispatch.v1.json",JSONObject.NULL);
            deny(()->new EmbeddedDuplexAbandonmentRequest(wrong.toString()));
            JSONObject cross=copy(good).put("current_boot_id","00000000-0000-0000-0000-000000000002");
            deny(()->new EmbeddedDuplexAbandonmentRequest(cross.toString()));
            JSONObject receipt=new JSONObject(current.abandon(null,null,request,(path,directory)->{}));
            Path archive=dir.resolve("abandoned-"+receipt.getString("scope_sha256"));
            if(receipt.getBoolean("old_cleanup_confirmed")||receipt.getBoolean("old_authority_transferred"))throw new AssertionError("old outcome upgraded");
            if(present){
                if(!old.equals(Files.readString(archive.resolve("preserved-"+NAME)))||!old.equals(Files.readString(dir.resolve(NAME))))throw new AssertionError("old bytes lost");
                JSONObject empty=new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old));
                if(empty.getJSONObject("terminal").length()!=0||empty.getJSONObject("pending_request_sha256").length()!=0)throw new AssertionError("retired replay still active");
            }else if(Files.exists(dir.resolve(NAME))||Files.exists(archive.resolve("preserved-"+NAME)))throw new AssertionError("absent file invented");
            String newer="{\"pending_request_sha256\":{\"current\":\""+"b".repeat(64)+"\"},\"terminal\":{}}";
            Files.writeString(dir.resolve(NAME),newer);
            if(!newer.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,newer)))throw new AssertionError("current obligations hidden");
            for(String suffix:new String[]{".new",".bak"}){
                Files.writeString(dir.resolve(NAME+suffix),newer);
                deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
                if(!newer.equals(Files.readString(dir.resolve(NAME+suffix))))throw new AssertionError("sidecar read mutated");
                Files.delete(dir.resolve(NAME+suffix));
            }
            if(present)Files.writeString(dir.resolve(NAME),old);else Files.delete(dir.resolve(NAME));
            if(!nativeBefore.equals(Files.readString(dir.resolve("native-process-fence.v1.lock")))||!nativeBefore.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),"native-process-fence.v1.lock",nativeBefore)))throw new AssertionError("native floor changed/hidden");
            if(!"UNKNOWN exact outcome".equals(Files.readString(dir.resolve("originals/old.json"))))throw new AssertionError("Original changed");
            if(!present){
                Files.writeString(archive.resolve("preserved-"+NAME),"fake");
                deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
                Files.delete(archive.resolve("preserved-"+NAME));
            }
            for(String suffix:new String[]{".new",".bak"}){
                Files.writeString(archive.resolve("preserved-"+NAME+suffix),"fake");
                deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
                Files.delete(archive.resolve("preserved-"+NAME+suffix));
            }
            String committed=Files.readString(archive.resolve("committed-fence.raw"));
            Files.delete(archive.resolve("committed-fence.raw"));
            deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
            Files.writeString(archive.resolve("committed-fence.raw"),committed);
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            current.requireFresh();EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME);
            if(present&&!new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old)).getJSONObject("pending_request_sha256").isEmpty())throw new AssertionError("committed projection lost");
        }
    }
    static void changedDuringArchive()throws Exception{
        Path dir=Files.createTempDirectory("v5-race"),other=Files.createTempDirectory("v5-race-peer");
        JSONObject a=peer(dir,"A",10001,null),b=peer(other,"B",10002,null);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
            deny(()->current.abandon(null,null,request,(path,directory)->{
                if(path.getName().startsWith("preserved-"))Files.writeString(dir.resolve(NAME+".bak"),"appeared while archiving");
            }));
            if(!current.recoveryOnly())throw new AssertionError("racing sidecar cleared fence");
        }
    }
    static void crashBeforeCommit(boolean present)throws Exception{
        Path dir=Files.createTempDirectory("v5-crash"),other=Files.createTempDirectory("v5-crash-peer");
        String old="{\"pending_request_sha256\":{},\"terminal\":{}}";
        JSONObject a=peer(dir,"A",10001,present?old:null),b=peer(other,"B",10002,null);
        int[] syncs={0};
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{
            if(++syncs[0]==3)throw new IllegalStateException("injected before fence commit");
        })){
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
            deny(()->current.abandon(null,null,request,(path,directory)->{}));
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            if(!current.recoveryOnly())throw new AssertionError("uncommitted v5 scope became fresh");
            deny(current::requireFresh);
            if(present&&!old.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old)))throw new AssertionError("uncommitted history hidden");
            if(!present&&Files.exists(dir.resolve(NAME)))throw new AssertionError("crash invented absent file");
        }
    }
    static void rejectedBeforeCommit(boolean present,String sidecar,boolean drift)throws Exception{
        Path dir=Files.createTempDirectory("v5-refuse"),other=Files.createTempDirectory("v5-refuse-peer");
        String old="{\"pending_request_sha256\":{},\"terminal\":{}}";
        JSONObject a=peer(dir,"A",10001,present?old:null),b=peer(other,"B",10002,null);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})){
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
            if(sidecar!=null)Files.writeString(dir.resolve(NAME+sidecar),old);
            if(drift)Files.writeString(dir.resolve(NAME),old+" ");
            deny(()->current.abandon(null,null,request,(path,directory)->{}));
            if(!current.recoveryOnly())throw new AssertionError("failed disposition cleared fence");
        }
    }
    public static void main(String[] args)throws Exception{
        transaction(true);transaction(false);
        changedDuringArchive();
        crashBeforeCommit(true);crashBeforeCommit(false);
        for(boolean present:new boolean[]{false,true}){
            rejectedBeforeCommit(present,".new",false);rejectedBeforeCommit(present,".bak",false);rejectedBeforeCommit(present,null,true);
        }
        System.out.println("v5 production custody/projection present+absent, current-scope/sidecar/drift/identity/schema refusal PASS; no native anti-replay or physical cleanup claim.");
    }
}
