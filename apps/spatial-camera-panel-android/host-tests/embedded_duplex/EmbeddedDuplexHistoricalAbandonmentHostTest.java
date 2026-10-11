package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.file.Path;
import java.nio.file.Files;

/** Real durable production transaction; OS absence is an explicit synthetic attestation. */
final class EmbeddedDuplexHistoricalAbandonmentHostTest {
    interface Attempt {void run() throws Exception;}
    static void deny(Attempt a) throws Exception {
        try {a.run();} catch(IllegalArgumentException|IllegalStateException expected) {return;}
        throw new AssertionError("negative control accepted");
    }
    static JSONObject clone(JSONObject a) {return new JSONObject(a.toString());}
    static JSONObject peer(Path dir,String serial,int uid) throws Exception {
        String epoch;
        try(EmbeddedDuplexProcessFence original=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            original.beforeRuntimeEffects(null,null);epoch=original.epochId();
        }
        JSONObject arm=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1")
            .put("action","arm").put("challenge","1".repeat(32)).put("process_epoch_id",epoch)
            .put("arm_generation",1).put("native_process_generation",1234567)
            .put("runtime_config_sha256","d".repeat(64)).put("feature_lock_sha256","e".repeat(64)).put("apk_sha256","a".repeat(64));
        JSONArray words=new JSONArray();for(int i=0;i<160;i++) words.put(0L);
        words.put(0,1).put(1,160).put(2,1234567).put(3,Long.parseUnsignedLong("1".repeat(16),16))
            .put(4,Long.parseUnsignedLong("1".repeat(16),16)).put(5,1).put(6,1);arm.put("native_snapshot",words);
        String status,record;
        try(EmbeddedDuplexProcessFence recovery=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            status=new JSONObject(recovery.recoveryStatus()).put("challenge","2".repeat(32)).toString();record=recovery.retainedRecord();
        }
        JSONObject preserved=new JSONObject();
        for(String name:EmbeddedDuplexHistoricalAbandonment.RECORDS) {
            String body="retained exact "+name;Files.writeString(dir.resolve(name),body);
            preserved.put(name,EmbeddedDuplexProcessFence.digest(body));
        }
        return new JSONObject().put("package_id","test.product.app").put("installed_apk_sha256","a".repeat(64))
            .put("signer_sha256","b".repeat(64)).put("physical_serial",serial)
            .put("boot_id","00000000-0000-0000-0000-000000000001").put("user_id",0).put("package_uid",uid)
            .put("original_pid",123).put("original_start_ticks",JSONObject.NULL).put("original_birth_status","historical_birth_not_recorded")
            .put("original_actor_receipt_json",arm.toString()).put("original_actor_receipt_sha256",EmbeddedDuplexProcessFence.digest(arm.toString()))
            .put("recovery_status_json",status).put("recovery_status_sha256",EmbeddedDuplexProcessFence.digest(status))
            .put("recovery_fence_record",record).put("recovery_fence_sha256",EmbeddedDuplexProcessFence.digest(record))
            .put("absence_scope","complete_package_uid_scope").put("complete_absence_confirmed",true).put("preserved_records",preserved);
    }
    static JSONObject auth(EmbeddedDuplexProcessFence current,JSONObject a,JSONObject b) throws Exception {
        JSONObject status=new JSONObject(current.recoveryStatus());
        return new JSONObject().put("$schema",EmbeddedDuplexHistoricalAbandonment.SCHEMA).put("role_id","peer_a")
            .put("package_id","test.product.app").put("component_id","test.product.app/test.Activity")
            .put("installed_apk_sha256","c".repeat(64)).put("signer_sha256","b".repeat(64)).put("physical_serial","A")
            .put("boot_id","00000000-0000-0000-0000-000000000001").put("user_id",0).put("package_uid",10001)
            .put("expected_predecessor_sha256",status.getString("predecessor_sha256"))
            .put("expected_current_fence_sha256",status.getString("current_fence_sha256"))
            .put("authorize_abandonment",true).put("process_absence_evidence_sha256","f".repeat(64))
            .put("current_recovery_actor",new JSONObject().put("pid",900).put("start_ticks",12345).put("app_process_generation",current.generation()))
            .put("historical_peers",new JSONObject().put("peer_a",a).put("peer_b",b));
    }
    public static void main(String[] argv) throws Exception {
        Path aDir=Files.createTempDirectory("historical-a"),bDir=Files.createTempDirectory("historical-b");
        JSONObject a=peer(aDir,"A",10001),b=peer(bDir,"B",10002);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(aDir.toFile(),null,null,()->{})) {
            JSONObject good=auth(current,a,b);
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
            request.requireInstalled("test.product.app","b".repeat(64),"c".repeat(64),"test.product.app/test.Activity",10001,900);
            request.requireCurrent(900,10001,12345,current.generation());
            deny(()->request.requireCurrent(900,10001,999,current.generation()));
            deny(()->request.requireCurrent(123,10001,12345,current.generation()));
            deny(()->request.requireInstalled("test.product.app","b".repeat(64),"a".repeat(64),"test.product.app/test.Activity",10001,900));
            for(String key:new String[]{"grant","old_start_ticks"}) {
                JSONObject bad=clone(good).put(key,"unknown");deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));
            }
            JSONObject bad=clone(good);bad.getJSONObject("current_recovery_actor").put("pid",123);
            final JSONObject reused=bad;deny(()->new EmbeddedDuplexAbandonmentRequest(reused.toString()));
            bad=clone(good);bad.getJSONObject("historical_peers").getJSONObject("peer_b").put("complete_absence_confirmed",false);
            final JSONObject missing=bad;deny(()->new EmbeddedDuplexAbandonmentRequest(missing.toString()));
            bad=clone(good);bad.getJSONObject("historical_peers").getJSONObject("peer_a").put("original_start_ticks",456);
            final JSONObject guessed=bad;deny(()->new EmbeddedDuplexAbandonmentRequest(guessed.toString()));
            // Swap real old actor receipt while keeping the authentic original fence/status chain.
            bad=clone(good);JSONObject target=bad.getJSONObject("historical_peers").getJSONObject("peer_a");
            target.put("original_actor_receipt_json",b.getString("original_actor_receipt_json"))
                .put("original_actor_receipt_sha256",b.getString("original_actor_receipt_sha256"));
            final JSONObject swapped=bad;deny(()->new EmbeddedDuplexAbandonmentRequest(swapped.toString()));
            bad=clone(good);target=bad.getJSONObject("historical_peers").getJSONObject("peer_a");
            JSONObject changed=new JSONObject(target.getString("original_actor_receipt_json")).put("native_process_generation",42);
            target.put("original_actor_receipt_json",changed.toString()).put("original_actor_receipt_sha256",EmbeddedDuplexProcessFence.digest(changed.toString()));
            final JSONObject nativeMismatch=bad;deny(()->new EmbeddedDuplexAbandonmentRequest(nativeMismatch.toString()));
            request.bind(a.getString("recovery_fence_record"),current.retainedRecord(),null,null);
            deny(()->request.bind(a.getString("recovery_fence_record"),current.retainedRecord(),"invented lineage",null));
            deny(()->request.bind(a.getString("recovery_fence_record"),current.retainedRecord(),null,"invented evidence"));
            deny(()->request.bind(b.getString("recovery_fence_record"),current.retainedRecord(),null,null));
            JSONObject receipt=new JSONObject(current.abandon(null,null,request,(path,directory)->{}));
            if(receipt.getBoolean("old_cleanup_confirmed") || receipt.getBoolean("old_authority_transferred")) throw new AssertionError("cleanup fabricated");
            Path archive=aDir.resolve("abandoned-"+receipt.getString("scope_sha256"));
            for(String name:EmbeddedDuplexHistoricalAbandonment.RECORDS)
                if(!Files.readString(aDir.resolve(name)).equals(Files.readString(archive.resolve("preserved-"+name)))) throw new AssertionError("durable records lost");
            for(String name:new String[]{"owner-dispatch.v1.json","product-activation.v1.json","retained-cleanup-preparations.v1.json"}) {
                String old=Files.readString(aDir.resolve(name));
                String projected=EmbeddedDuplexAbandonment.visibleReplay(aDir.toFile(),name,old);
                JSONObject empty=new JSONObject(projected);
                if(name.equals("retained-cleanup-preparations.v1.json")) {
                    if(empty.getJSONObject("originals").length()!=0 || empty.getJSONObject("prepared").length()!=0) throw new AssertionError("old cleanup restored");
                } else if(empty.getJSONObject("terminal").length()!=0 || empty.getJSONObject("pending_request_sha256").length()!=0) throw new AssertionError("old replay restored");
                String newer="{\"pending_request_sha256\":{},\"terminal\":{\"fresh_scope\":1}}";
                Files.writeString(aDir.resolve(name),newer);
                if(!newer.equals(EmbeddedDuplexAbandonment.visibleReplay(aDir.toFile(),name,newer))) throw new AssertionError("new scope hidden");
                Files.writeString(aDir.resolve(name),old);
                Files.writeString(aDir.resolve(name+".bak"),old);
                deny(()->EmbeddedDuplexAbandonment.requireReplayRead(aDir.toFile(),name));
                deny(()->EmbeddedDuplexAbandonment.visibleReplay(aDir.toFile(),name,old));
                deny(()->EmbeddedDuplexAbandonment.visibleReplay(aDir.toFile(),name,newer));
                if(!Files.readString(aDir.resolve(name)).equals(old) || !Files.readString(aDir.resolve(name+".bak")).equals(old)) throw new AssertionError("sidecar read mutated files");
                Files.delete(aDir.resolve(name+".bak"));
                Files.writeString(aDir.resolve(name+".new"),old);
                deny(()->EmbeddedDuplexAbandonment.requireReplayRead(aDir.toFile(),name));
                Files.delete(aDir.resolve(name+".new"));
            }
            String nativeCounter=Files.readString(aDir.resolve("native-process-fence.v1.lock"));
            if(!nativeCounter.equals(EmbeddedDuplexAbandonment.visibleReplay(aDir.toFile(),"native-process-fence.v1.lock",nativeCounter))) throw new AssertionError("native counter hidden");
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(aDir.toFile(),null,null,()->{})) {
            current.requireFresh();
            String old=Files.readString(aDir.resolve("owner-dispatch.v1.json"));
            if(new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(aDir.toFile(),"owner-dispatch.v1.json",old)).getJSONObject("terminal").length()!=0) throw new AssertionError("committed projection lost on restart");
            // Actual fresh app fence effect/verified close transition; teardown itself modeled by owner.
            current.beforeRuntimeEffects(null,null);current.afterVerifiedNoMediaCleanup(null,null);current.requireFresh();
        }
        Path crash=Files.createTempDirectory("historical-crash");JSONObject crashPeer=peer(crash,"A",10001);
        // Fail after the archive/marker sync, before fence commit. Recovery stays Pending.
        final int[] syncs={0};
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(crash.toFile(),null,null,()->{
            if(++syncs[0]==3) throw new IllegalStateException("injected precommit crash");
        })) {
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,crashPeer,b).toString());
            deny(()->current.abandon(null,null,request,(path,directory)->{}));
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(crash.toFile(),null,null,()->{})) {
            if(!current.recoveryOnly()) throw new AssertionError("crash cleared pending");deny(current::requireFresh);
            String old=Files.readString(crash.resolve("owner-dispatch.v1.json"));
            if(!old.equals(EmbeddedDuplexAbandonment.visibleReplay(crash.toFile(),"owner-dispatch.v1.json",old))) throw new AssertionError("uncommitted replay hidden");
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(crash.toFile(),null,null,()->{})) {
            if(!current.recoveryOnly()) throw new AssertionError("repeated restart laundered pending");deny(current::requireFresh);
            String old=Files.readString(crash.resolve("owner-dispatch.v1.json"));
            if(!old.equals(EmbeddedDuplexAbandonment.visibleReplay(crash.toFile(),"owner-dispatch.v1.json",old))) throw new AssertionError("repeated restart hid uncommitted replay");
        }
        Path clearCrash=Files.createTempDirectory("historical-clear-before-witness");JSONObject clearPeer=peer(clearCrash,"A",10001);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(clearCrash.toFile(),null,null,()->{})) {
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,clearPeer,b).toString());
            deny(()->current.abandon(null,null,request,(path,directory)->{
                if(path.getName().equals("committed-fence.raw")) throw new IllegalStateException("injected witness failure");
            }));
        }
        deny(()->EmbeddedDuplexProcessFence.acquire(clearCrash.toFile(),null,null,()->{}));
        deny(()->EmbeddedDuplexProcessFence.acquire(clearCrash.toFile(),null,null,()->{}));
        Path drift=Files.createTempDirectory("historical-drift");JSONObject driftPeer=peer(drift,"A",10001);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(drift.toFile(),null,null,()->{})) {
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,driftPeer,b).toString());
            Files.writeString(drift.resolve("owner-dispatch.v1.json"),"changed bytes");
            deny(()->current.abandon(null,null,request,(path,directory)->{}));deny(current::requireFresh);
        }
        String stat="900 (name with ) delimiter) S "+"0 ".repeat(18)+"12345 0";
        if(EmbeddedDuplexHistoricalAbandonment.startTicks(stat)!=12345) throw new AssertionError("proc birth parser");
        Path archive=aDir.resolve("abandoned-"+new JSONObject(Files.readString(aDir.resolve("abandoned-scope.v1.json"))).getString("scope_sha256"));
        String witness=Files.readString(archive.resolve("committed-fence.raw"));
        Files.writeString(archive.resolve("committed-fence.raw"),"tampered witness");
        deny(()->EmbeddedDuplexProcessFence.acquire(aDir.toFile(),null,null,()->{}));
        Files.writeString(archive.resolve("committed-fence.raw"),witness);
        Files.writeString(archive.resolve("preserved-product-activation.v1.json"),"tampered archive");
        deny(()->EmbeddedDuplexProcessFence.acquire(aDir.toFile(),null,null,()->{}));
        System.out.println("Historical no-journal abandonment controls PASS; synthetic external absence, no device cleanup proof.");
    }
}
