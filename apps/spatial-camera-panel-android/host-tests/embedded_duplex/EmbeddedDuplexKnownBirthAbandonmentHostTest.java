package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.file.Path;
import java.nio.file.Files;

/** Real durable production transaction; OS absence is an explicit synthetic attestation. */
final class EmbeddedDuplexKnownBirthAbandonmentHostTest {
    interface Attempt {void run() throws Exception;}
    static void deny(Attempt a) throws Exception {
        try {a.run();} catch(IllegalArgumentException|IllegalStateException expected) {return;}
        throw new AssertionError("negative control accepted");
    }
    static JSONObject clone(JSONObject a) {return new JSONObject(a.toString());}
    static JSONObject peer(Path dir,String serial,int uid) throws Exception {
        String epoch,record;
        try(EmbeddedDuplexProcessFence original=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            original.beforeRuntimeEffects(null,null);epoch=original.epochId();record=original.retainedRecord();
        }
        JSONObject arm=new JSONObject().put("schema","rusty.quest.stereo.concurrent_qualification_receipt.v1")
            .put("action","arm").put("challenge","1".repeat(32)).put("process_epoch_id",epoch)
            .put("arm_generation",1).put("native_process_generation",1234567)
            .put("runtime_config_sha256","d".repeat(64)).put("feature_lock_sha256","e".repeat(64)).put("apk_sha256","a".repeat(64));
        JSONArray words=new JSONArray();for(int i=0;i<160;i++) words.put(0L);
        words.put(0,1).put(1,160).put(2,1234567).put(3,Long.parseUnsignedLong("1".repeat(16),16))
            .put(4,Long.parseUnsignedLong("1".repeat(16),16)).put(5,1).put(6,1);arm.put("native_snapshot",words);
        JSONObject birth=new JSONObject().put("schema","rusty.quest.embedded_duplex.known_actor_birth.v1")
            .put("pid",123).put("start_ticks",456).put("uid",uid).put("app_process_generation",1)
            .put("process_epoch_id",epoch).put("physical_serial",serial)
            .put("boot_id","00000000-0000-0000-0000-000000000001").put("installed_apk_sha256","a".repeat(64));
        JSONObject preserved=new JSONObject();
        for(String name:EmbeddedDuplexKnownBirthAbandonment.RECORDS) {
            String body="retained exact "+name;Files.writeString(dir.resolve(name),body);
            preserved.put(name,EmbeddedDuplexProcessFence.digest(body));
        }
        return new JSONObject().put("package_id","test.product.app").put("installed_apk_sha256","a".repeat(64))
            .put("signer_sha256","b".repeat(64)).put("physical_serial",serial)
            .put("boot_id","00000000-0000-0000-0000-000000000001").put("user_id",0).put("package_uid",uid)
            .put("original_pid",123).put("original_start_ticks",456).put("original_birth_status","known_birth_recorded")
            .put("original_actor_receipt_json",arm.toString()).put("original_actor_receipt_sha256",EmbeddedDuplexProcessFence.digest(arm.toString()))
            .put("original_birth_receipt_json",birth.toString()).put("original_birth_receipt_sha256",EmbeddedDuplexProcessFence.digest(birth.toString()))
            .put("original_fence_record",record).put("original_fence_sha256",EmbeddedDuplexProcessFence.digest(record))
            .put("absence_scope","complete_package_uid_scope").put("complete_absence_confirmed",true).put("preserved_records",preserved);
    }
    static JSONObject auth(EmbeddedDuplexProcessFence current,JSONObject a,JSONObject b) throws Exception {
        JSONObject status=new JSONObject(current.recoveryStatus());
        return new JSONObject().put("$schema",EmbeddedDuplexKnownBirthAbandonment.SCHEMA).put("role_id","peer_a")
            .put("package_id","test.product.app").put("component_id","test.product.app/test.Activity")
            .put("installed_apk_sha256","c".repeat(64)).put("signer_sha256","b".repeat(64)).put("physical_serial","A")
            .put("boot_id","00000000-0000-0000-0000-000000000001").put("user_id",0).put("package_uid",10001)
            .put("expected_predecessor_sha256",status.getString("predecessor_sha256"))
            .put("expected_current_fence_sha256",status.getString("current_fence_sha256"))
            .put("authorize_abandonment",true).put("process_absence_evidence_sha256","f".repeat(64))
            .put("current_recovery_actor",new JSONObject().put("pid",900).put("start_ticks",12345).put("app_process_generation",current.generation()))
            .put("historical_peers",new JSONObject().put("peer_a",a).put("peer_b",b));
    }
    static void actualFixture(Path input) throws Exception {
        JSONObject fixture=new JSONObject(Files.readString(input));
        JSONObject peers=fixture.getJSONObject("peers");
        for(String role:new String[]{"peer_a","peer_b"}) {
            JSONObject selected=peers.getJSONObject(role);Path dir=Files.createTempDirectory("known-birth-retained");
            JSONObject records=fixture.getJSONObject("records").getJSONObject(role);
            JSONObject all=fixture.getJSONObject("full_owner_files").getJSONObject(role);
            for(String name:all.keySet()) {
                Path file=dir.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,all.getString(name));
            }
            Files.writeString(dir.resolve("process-fence.v1.lock"),selected.getString("original_fence_record"));
            try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
                JSONObject good=auth(current,peers.getJSONObject("peer_a"),peers.getJSONObject("peer_b"));
                good.put("role_id",role);
                good.getJSONObject("current_recovery_actor").put("start_ticks",selected.getLong("original_start_ticks")+1);
                for(String key:new String[]{"package_id","signer_sha256","physical_serial","boot_id","user_id","package_uid"})
                    good.put(key,selected.get(key));
                EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
                request.requireCurrent(900,selected.getInt("package_uid"),selected.getLong("original_start_ticks")+1,current.generation());
                request.bind(selected.getString("original_fence_record"),current.retainedRecord(),null,null);
                JSONObject receipt=new JSONObject(current.abandon(null,null,request,(path,directory)->{}));
                if(receipt.getBoolean("old_cleanup_confirmed")) throw new AssertionError("actual old cleanup fabricated");
                Path archive=dir.resolve("abandoned-"+receipt.getString("scope_sha256"));
                for(String name:EmbeddedDuplexKnownBirthAbandonment.RECORDS)
                    if(!records.getString(name).equals(Files.readString(archive.resolve("preserved-"+name))))
                        throw new AssertionError("actual archived body differs");
                for(String name:new String[]{"owner-dispatch.v1.json","product-activation.v1.json"})
                    if(new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),name,records.getString(name)))
                        .getJSONObject("terminal").length()!=0) throw new AssertionError("actual old replay remains");
                if(!EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),"native-process-fence.v1.lock",
                    records.getString("native-process-fence.v1.lock")).equals(records.getString("native-process-fence.v1.lock")))
                    throw new AssertionError("actual native counter hidden");
            }
        }
        System.out.println("Actual retained fixture production transaction: both peers archived, witness committed, exact old replay projected; host modeled current actor/absence, no device effects.");
    }
    public static void main(String[] argv) throws Exception {
        Path aDir=Files.createTempDirectory("known-birth-a"),bDir=Files.createTempDirectory("known-birth-b");
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
            for(String key:new String[]{"pid","start_ticks","uid","app_process_generation","process_epoch_id","physical_serial","boot_id","installed_apk_sha256"}) {
                JSONObject badBirth=clone(good);JSONObject peer=badBirth.getJSONObject("historical_peers").getJSONObject("peer_a");
                JSONObject birth=new JSONObject(peer.getString("original_birth_receipt_json"));
                Object old=birth.get(key);birth.put(key,old instanceof Number ? 999999 : "changed");
                peer.put("original_birth_receipt_json",birth.toString()).put("original_birth_receipt_sha256",EmbeddedDuplexProcessFence.digest(birth.toString()));
                deny(()->new EmbeddedDuplexAbandonmentRequest(badBirth.toString()));
            }
            for(Object value:new Object[]{"456",456.5,Boolean.TRUE,JSONObject.NULL}) {
                JSONObject wrongType=clone(good);wrongType.getJSONObject("historical_peers").getJSONObject("peer_a").put("original_start_ticks",value);
                deny(()->new EmbeddedDuplexAbandonmentRequest(wrongType.toString()));
            }
            JSONObject noBirth=clone(good);noBirth.getJSONObject("historical_peers").getJSONObject("peer_a").remove("original_birth_receipt_sha256");
            deny(()->new EmbeddedDuplexAbandonmentRequest(noBirth.toString()));
            JSONObject extraBirth=clone(good);extraBirth.getJSONObject("historical_peers").getJSONObject("peer_a").put("cleanup_success",true);
            deny(()->new EmbeddedDuplexAbandonmentRequest(extraBirth.toString()));
            deny(()->request.requireCurrent(900,10001,12345,current.generation()+1));
            JSONObject reuse=clone(good);reuse.getJSONObject("current_recovery_actor").put("pid",123);
            EmbeddedDuplexAbandonmentRequest reusedPid=new EmbeddedDuplexAbandonmentRequest(reuse.toString());
            reusedPid.requireCurrent(123,10001,12345,current.generation());
            reusedPid.bind(a.getString("original_fence_record"),current.retainedRecord(),null,null);
            for(long ticks:new long[]{455,456}) {
                JSONObject oldBirth=clone(good);oldBirth.getJSONObject("current_recovery_actor").put("start_ticks",ticks);
                deny(()->new EmbeddedDuplexAbandonmentRequest(oldBirth.toString()));
            }
            JSONObject bad=clone(good);bad.getJSONObject("current_recovery_actor").put("pid",123).put("start_ticks",456);
            final JSONObject reused=bad;deny(()->new EmbeddedDuplexAbandonmentRequest(reused.toString()));
            bad=clone(good);bad.getJSONObject("historical_peers").getJSONObject("peer_b").put("complete_absence_confirmed",false);
            final JSONObject missing=bad;deny(()->new EmbeddedDuplexAbandonmentRequest(missing.toString()));
            bad=clone(good);bad.getJSONObject("historical_peers").getJSONObject("peer_a").put("original_start_ticks",JSONObject.NULL);
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
            request.bind(a.getString("original_fence_record"),current.retainedRecord(),null,null);
            deny(()->request.bind(a.getString("original_fence_record"),current.retainedRecord(),"invented lineage",null));
            deny(()->request.bind(a.getString("original_fence_record"),current.retainedRecord(),null,"invented evidence"));
            deny(()->request.bind(b.getString("original_fence_record"),current.retainedRecord(),null,null));
            JSONObject receipt=new JSONObject(current.abandon(null,null,request,(path,directory)->{}));
            if(receipt.getBoolean("old_cleanup_confirmed") || receipt.getBoolean("old_authority_transferred")) throw new AssertionError("cleanup fabricated");
            Path archive=aDir.resolve("abandoned-"+receipt.getString("scope_sha256"));
            for(String name:EmbeddedDuplexKnownBirthAbandonment.RECORDS)
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
        Path crash=Files.createTempDirectory("known-birth-crash");JSONObject crashPeer=peer(crash,"A",10001);
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
        Path clearCrash=Files.createTempDirectory("known-birth-clear-before-witness");JSONObject clearPeer=peer(clearCrash,"A",10001);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(clearCrash.toFile(),null,null,()->{})) {
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,clearPeer,b).toString());
            deny(()->current.abandon(null,null,request,(path,directory)->{
                if(path.getName().equals("committed-fence.raw")) throw new IllegalStateException("injected witness failure");
            }));
        }
        deny(()->EmbeddedDuplexProcessFence.acquire(clearCrash.toFile(),null,null,()->{}));
        deny(()->EmbeddedDuplexProcessFence.acquire(clearCrash.toFile(),null,null,()->{}));
        Path drift=Files.createTempDirectory("known-birth-drift");JSONObject driftPeer=peer(drift,"A",10001);
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
        if(argv.length==1) actualFixture(Path.of(argv[0]));
        System.out.println("Known-birth no-journal abandonment controls PASS; synthetic external absence, no device cleanup proof.");
    }
}

