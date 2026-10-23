package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real archive/commit/restart transaction; current OS actor and external absence are modeled. */
final class EmbeddedDuplexReplayScopeV6HostTest {
    static final String NAME="retained-cleanup.v2.json";
    static final String SCHEMA="rusty.quest.embedded_duplex.abandonment_authorization.v6";
    static final String BOOT="00000000-0000-0000-0000-000000000002";
    static final String OLD="{\"pending_request_sha256\":{\"old\":\""+"a".repeat(64)+"\"},\"terminal\":{}}";
    static final String NEW="{\"pending_request_sha256\":{\"current\":\""+"b".repeat(64)+"\"},\"terminal\":{}}";
    static void deny(EmbeddedDuplexKnownBirthAbandonmentHostTest.Attempt action)throws Exception {
        EmbeddedDuplexReplayScopeV5HostTest.deny(action);
    }
    static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}
    static JSONObject peer(Path dir,String serial,int uid,String cleanup)throws Exception {
        return EmbeddedDuplexReplayScopeV5HostTest.peer(dir,serial,uid,cleanup);
    }
    static JSONObject auth(EmbeddedDuplexProcessFence current,JSONObject a,JSONObject b)throws Exception {
        JSONObject value=EmbeddedDuplexKnownBirthAbandonmentHostTest.auth(current,a,b)
            .put("$schema",SCHEMA).put("current_boot_id",BOOT);
        // Linux ticks have no monotonic relation across boots.
        value.getJSONObject("current_recovery_actor").put("start_ticks",1);
        return value;
    }
    static void closedIdentity(JSONObject good,EmbeddedDuplexProcessFence current,JSONObject a,JSONObject b)throws Exception {
        EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
        if(!request.crossBoot()||!request.requiresBootObservation())throw new AssertionError("crossboot observation not required");
        request.requireBoot(BOOT);request.requireCurrent(900,10001,1,current.generation());
        deny(()->request.requireBoot(a.getString("boot_id")));
        deny(()->request.requireBoot("00000000-0000-0000-0000-000000000003"));
        deny(()->request.requireCurrent(900,10001,2,current.generation()));
        deny(()->request.requireCurrent(900,10001,1,current.generation()+1));
        deny(()->request.bind(b.getString("original_fence_record"),current.retainedRecord(),null,null));
        deny(()->request.bind(a.getString("original_fence_record"),current.retainedRecord(),"invented journal",null));
        deny(()->request.bind(a.getString("original_fence_record"),current.retainedRecord(),null,"invented evidence"));
        for(String key:new String[]{"current_boot_id","boot_id","historical_peers"}) {
            JSONObject bad=copy(good);bad.remove(key);deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));
        }
        for(String boot:new String[]{a.getString("boot_id"),"invalid","00000000-0000-0000-0000-00000000000A"}) {
            JSONObject bad=copy(good).put("current_boot_id",boot);deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));
        }
        JSONObject extra=copy(good).put("old_pid",123);deny(()->new EmbeddedDuplexAbandonmentRequest(extra.toString()));
        JSONObject unknown=copy(good).put("unexpected",true);deny(()->new EmbeddedDuplexAbandonmentRequest(unknown.toString()));
        JSONObject skipped=copy(good);skipped.getJSONObject("current_recovery_actor").put("app_process_generation",current.generation()+1);
        EmbeddedDuplexAbandonmentRequest skipRequest=new EmbeddedDuplexAbandonmentRequest(skipped.toString());
        deny(()->skipRequest.bind(a.getString("original_fence_record"),current.retainedRecord(),null,null));
        for(String role:new String[]{"peer_a","peer_b"}) {
            JSONObject missing=copy(good);missing.getJSONObject("historical_peers").getJSONObject(role).getJSONObject("preserved_records").remove(NAME);
            deny(()->new EmbeddedDuplexAbandonmentRequest(missing.toString()));
            JSONObject bad=copy(good);bad.getJSONObject("historical_peers").getJSONObject(role).getJSONObject("preserved_records").put("owner-dispatch.v1.json",JSONObject.NULL);
            deny(()->new EmbeddedDuplexAbandonmentRequest(bad.toString()));
            JSONObject field=copy(good);field.getJSONObject("historical_peers").getJSONObject(role).put("unknown",1);
            deny(()->new EmbeddedDuplexAbandonmentRequest(field.toString()));
            JSONObject historical=copy(good);historical.getJSONObject("historical_peers").getJSONObject(role).put("original_start_ticks",457);
            deny(()->new EmbeddedDuplexAbandonmentRequest(historical.toString()));
        }
    }
    static void transaction(String old)throws Exception {
        boolean present=old!=null;
        Path dir=Files.createTempDirectory("replay-v6-a"),other=Files.createTempDirectory("replay-v6-b");
        JSONObject a=peer(dir,"A",10001,old),b=peer(other,"B",10002,null);
        String nativeBefore=Files.readString(dir.resolve("native-process-fence.v1.lock"));
        Files.createDirectories(dir.resolve("originals"));Files.writeString(dir.resolve("originals/old.json"),"UNKNOWN exact outcome");
        Path archive;
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            JSONObject good=auth(current,a,b);closedIdentity(good,current,a,b);
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
            JSONObject receipt=new JSONObject(current.abandon(null,null,request,(path,directory)->{}));
            archive=dir.resolve("abandoned-"+receipt.getString("scope_sha256"));
            if(!SCHEMA.equals(receipt.getJSONObject("operator_authorization").getString("$schema"))
                ||receipt.getBoolean("old_cleanup_confirmed")||receipt.getBoolean("old_authority_transferred"))throw new AssertionError("disposition changed authority/outcome");
            if(!Files.readString(archive.resolve("committed-fence.raw")).contains("\nclear\n"))throw new AssertionError("commit witness absent");
            if(present) {
                if(!old.equals(Files.readString(dir.resolve(NAME)))||!old.equals(Files.readString(archive.resolve("preserved-"+NAME))))throw new AssertionError("exact cleanup bytes lost");
                JSONObject empty=new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old));
                if(!empty.getJSONObject("terminal").isEmpty()||!empty.getJSONObject("pending_request_sha256").isEmpty())throw new AssertionError("old crossboot cleanup remains visible");
            } else if(Files.exists(dir.resolve(NAME))||Files.exists(archive.resolve("preserved-"+NAME)))throw new AssertionError("absent cleanup invented");
            Files.writeString(dir.resolve(NAME),NEW);
            if(!NEW.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,NEW)))throw new AssertionError("current cleanup hidden");
            for(String suffix:new String[]{".new",".bak"}) {
                Files.writeString(dir.resolve(NAME+suffix),NEW);
                deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
                deny(()->EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,NEW));
                if(!NEW.equals(Files.readString(dir.resolve(NAME+suffix))))throw new AssertionError("live sidecar mutated");
                Files.delete(dir.resolve(NAME+suffix));
            }
            if(present)Files.writeString(dir.resolve(NAME),old);else Files.delete(dir.resolve(NAME));
            if(!nativeBefore.equals(Files.readString(dir.resolve("native-process-fence.v1.lock")))
                ||!nativeBefore.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),"native-process-fence.v1.lock",nativeBefore)))throw new AssertionError("native floor hidden/changed");
            if(!"UNKNOWN exact outcome".equals(Files.readString(dir.resolve("originals/old.json"))))throw new AssertionError("Original changed");
            for(String suffix:new String[]{".new",".bak"}) {
                Files.writeString(archive.resolve("preserved-"+NAME+suffix),"sidecar");
                deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
                Files.delete(archive.resolve("preserved-"+NAME+suffix));
            }
            Files.writeString(archive.resolve("preserved-"+NAME),"tampered archive");
            deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
            if(present)Files.writeString(archive.resolve("preserved-"+NAME),old);else Files.delete(archive.resolve("preserved-"+NAME));
            String witness=Files.readString(archive.resolve("committed-fence.raw"));
            Files.delete(archive.resolve("committed-fence.raw"));
            deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
            Files.writeString(archive.resolve("committed-fence.raw"),"tampered witness");
            deny(()->EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME));
            Files.writeString(archive.resolve("committed-fence.raw"),witness);
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            current.requireFresh();EmbeddedDuplexAbandonment.requireReplayRead(dir.toFile(),NAME);
            if(present&&!new JSONObject(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old)).getJSONObject("terminal").isEmpty())throw new AssertionError("restart restored retired cleanup");
            Files.writeString(dir.resolve(NAME),NEW);
            if(!NEW.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,NEW)))throw new AssertionError("restart hides current cleanup");
            if(present)Files.writeString(dir.resolve(NAME),old);else Files.delete(dir.resolve(NAME));
        }
        Files.writeString(archive.resolve("preserved-"+NAME+".new"),"sidecar");
        deny(()->EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{}));
    }
    static void refusal(boolean present,String suffix,boolean drift)throws Exception {
        Path dir=Files.createTempDirectory("v6-refuse"),other=Files.createTempDirectory("v6-refuse-peer");
        JSONObject a=peer(dir,"A",10001,present?OLD:null),b=peer(other,"B",10002,null);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
            if(suffix!=null)Files.writeString(dir.resolve(NAME+suffix),OLD);
            if(drift)Files.writeString(dir.resolve(NAME),OLD+" ");
            deny(()->current.abandon(null,null,request,(path,directory)->{}));
            if(!current.recoveryOnly())throw new AssertionError("failed disposition cleared fence");
        }
    }
    static void changedDuringArchive()throws Exception {
        for(boolean present:new boolean[]{false,true}) {
            Path dir=Files.createTempDirectory("v6-race"),other=Files.createTempDirectory("v6-race-peer");
            JSONObject a=peer(dir,"A",10001,present?OLD:null),b=peer(other,"B",10002,null);
            try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
                EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
                deny(()->current.abandon(null,null,request,(path,directory)->{
                    if(path.getName().startsWith("preserved-"))Files.writeString(dir.resolve(NAME),NEW);
                }));
                if(!current.recoveryOnly())throw new AssertionError("archive drift cleared fence");
            }
        }
    }
    static void crash(boolean present,boolean witnessFailure)throws Exception {
        Path dir=Files.createTempDirectory("v6-crash"),other=Files.createTempDirectory("v6-crash-peer");
        JSONObject a=peer(dir,"A",10001,present?OLD:null),b=peer(other,"B",10002,null);int[] syncs={0};
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{
            if(!witnessFailure&&++syncs[0]==3)throw new IllegalStateException("injected before fence commit");
        })) {
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth(current,a,b).toString());
            deny(()->current.abandon(null,null,request,(path,directory)->{
                if(witnessFailure&&path.getName().equals("committed-fence.raw"))throw new IllegalStateException("injected missing witness");
            }));
        }
        for(int i=0;i<2;i++) {
            if(witnessFailure)deny(()->EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{}));
            else try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
                if(!current.recoveryOnly())throw new AssertionError("precommit crash became fresh");deny(current::requireFresh);
                if(present&&!OLD.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,OLD)))throw new AssertionError("uncommitted scope hidden");
            }
        }
    }
    static void v4LeavesCleanupVisible(String old)throws Exception {
        Path dir=Files.createTempDirectory("v4-visible"),other=Files.createTempDirectory("v4-visible-peer");
        JSONObject a=EmbeddedDuplexKnownBirthAbandonmentHostTest.peer(dir,"A",10001),b=EmbeddedDuplexKnownBirthAbandonmentHostTest.peer(other,"B",10002);
        Files.writeString(dir.resolve(NAME),old);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            JSONObject good=auth(current,a,b).put("$schema",EmbeddedDuplexKnownBirthAbandonment.CROSS_BOOT_SCHEMA);
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());request.requireBoot(BOOT);
            current.abandon(null,null,request,(path,directory)->{});
            if(!old.equals(EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old)))throw new AssertionError("v4 meaning changed");
        }
    }
    static void replacesV5Marker(String old,boolean v6)throws Exception {
        Path dir=Files.createTempDirectory("v5-crossboot-next"),other=Files.createTempDirectory("v5-crossboot-peer");
        JSONObject a=peer(dir,"A",10001,old),b=peer(other,"B",10002,null);String pending,epoch,oldReceipt;
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(EmbeddedDuplexReplayScopeV5HostTest.auth(current,a,b).toString());
            oldReceipt=current.abandon(null,null,request,(path,directory)->{});
            current.beforeRuntimeEffects(null,null);pending=current.retainedRecord();epoch=current.epochId();
        }
        // Model the subsequently recorded known birth/ARM on the same old boot.
        JSONObject next=copy(a),arm=new JSONObject(next.getString("original_actor_receipt_json")),birth=new JSONObject(next.getString("original_birth_receipt_json"));
        arm.put("process_epoch_id",epoch);birth.put("process_epoch_id",epoch).put("app_process_generation",2);
        next.put("original_actor_receipt_json",arm.toString()).put("original_actor_receipt_sha256",EmbeddedDuplexProcessFence.digest(arm.toString()))
            .put("original_birth_receipt_json",birth.toString()).put("original_birth_receipt_sha256",EmbeddedDuplexProcessFence.digest(birth.toString()))
            .put("original_fence_record",pending).put("original_fence_sha256",EmbeddedDuplexProcessFence.digest(pending));
        if(!v6) {
            next.getJSONObject("preserved_records").remove(NAME);b.getJSONObject("preserved_records").remove(NAME);
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            JSONObject good=auth(current,next,b);if(!v6)good.put("$schema",EmbeddedDuplexKnownBirthAbandonment.CROSS_BOOT_SCHEMA);
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());request.requireBoot(BOOT);
            JSONObject receipt=new JSONObject(current.abandon(null,null,request,(path,directory)->{}));
            if(!good.getString("$schema").equals(new JSONObject(Files.readString(dir.resolve("abandoned-scope.v1.json"))).getJSONObject("operator_authorization").getString("$schema")))throw new AssertionError("new marker not active");
            String projected=EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old);
            if(v6?!new JSONObject(projected).getJSONObject("terminal").isEmpty():!old.equals(projected))throw new AssertionError("v5 successor cleanup projection differs");
            Path oldArchive=dir.resolve("abandoned-"+new JSONObject(oldReceipt).getString("scope_sha256"));
            if(!oldReceipt.equals(Files.readString(oldArchive.resolve("receipt.json")))||!old.equals(Files.readString(oldArchive.resolve("preserved-"+NAME))))throw new AssertionError("prior v5 archive rewritten");
            if(receipt.getLong("successor_generation")!=3)throw new AssertionError("successor skipped generation");
        }
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            current.requireFresh();String projected=EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old);
            if(v6?!new JSONObject(projected).getJSONObject("terminal").isEmpty():!old.equals(projected))throw new AssertionError("successor marker restart differs");
        }
    }
    static void externalFixture(Path input)throws Exception {
        JSONObject fixture=new JSONObject(Files.readString(input));String old=fixture.getString("cleanup");
        if(!EmbeddedDuplexProcessFence.digest(old).equals(fixture.getString("cleanup_sha256")))throw new AssertionError("fixture digest differs");
        JSONObject replay=new JSONObject(old),terminal=replay.getJSONObject("terminal");JSONArray generations=new JSONArray();
        for(String key:terminal.keySet()) {
            JSONArray bytes=terminal.getJSONObject(key).getJSONArray("response_bytes");byte[] raw=new byte[bytes.length()];
            for(int i=0;i<raw.length;i++)raw[i]=(byte)bytes.getInt(i);
            JSONObject response=new JSONObject(new String(raw,StandardCharsets.UTF_8));
            if(!"completed".equals(response.getString("status"))||!response.isNull("failure"))throw new AssertionError("fixture terminal differs");
            generations.put(response.getJSONObject("effect").getJSONObject("readback").getLong("executor_generation"));
        }
        java.util.List<Long> actual=new java.util.ArrayList<>(),expected=new java.util.ArrayList<>();
        for(int i=0;i<generations.length();i++)actual.add(generations.getLong(i));
        JSONArray wants=fixture.getJSONArray("terminal_generations");for(int i=0;i<wants.length();i++)expected.add(wants.getLong(i));
        java.util.Collections.sort(actual);java.util.Collections.sort(expected);
        if(!replay.getJSONObject("pending_request_sha256").isEmpty()||!actual.equals(expected))throw new AssertionError("fixture replay census differs");
        v4LeavesCleanupVisible(old);replacesV5Marker(old,false);
        System.out.println("External exact cleanup remains visible after v4 replaces active v5; generations="+actual+"; no source/schema change to legacy v4.");
        transaction(old);replacesV5Marker(old,true);
        System.out.println("External exact cleanup fixture v4-visible/v6-projected archive+restart PASS; terminal generations="+actual+"; OS actor/absence modeled.");
    }
    static JSONObject regularFiles(Path root,java.io.FileDescriptor heldFence)throws Exception {
        JSONObject result=new JSONObject();
        try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            for(Path path:(Iterable<Path>)paths::iterator) {
                if(Files.isSymbolicLink(path))throw new AssertionError("fixture snapshot link");
                if(Files.isRegularFile(path)) {
                    String name=root.relativize(path).toString().replace('\\','/');
                    // Windows forbids another descriptor from reading the held lock range.
                    // Read the real bytes through the host Os model on that descriptor.
                    byte[] raw;
                    if("no_backup/embedded-duplex-replay/process-fence.v1.lock".equals(name)) {
                        raw=new byte[(int)android.system.Os.fstat(heldFence).st_size];int offset=0;
                        while(offset<raw.length) {
                            int count=android.system.Os.pread(heldFence,raw,offset,raw.length-offset,offset);
                            if(count<=0)throw new AssertionError("held fence snapshot truncated");offset+=count;
                        }
                    } else raw=Files.readAllBytes(path);
                    result.put(name,java.util.Base64.getEncoder().encodeToString(raw));
                }
            }
        }
        return result;
    }
    static void emitTransaction(Path output,String old)throws Exception {
        if(Files.exists(output))throw new AssertionError("fixture output already exists");
        Files.createDirectories(output);
        Path root=Files.createTempDirectory("v6-production-transaction"),dir=root.resolve("no_backup/embedded-duplex-replay"),other=Files.createTempDirectory("v6-production-peer");
        Files.createDirectories(dir);
        JSONObject a=peer(dir,"A",10001,old),b=peer(other,"B",10002,null);
        Path original=root.resolve("no_backup/operator-originals-v1/"+"1".repeat(32)+".json");
        Files.createDirectories(original.getParent());Files.writeString(original,"{\"state\":\"UNKNOWN\",\"fixture\":\"modeled immutable historical Original\"}");
        String challenge="2".repeat(32);
        try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(dir.toFile(),null,null,()->{})) {
            java.io.FileDescriptor heldFence=android.system.Os.lastOpened;
            JSONObject good=auth(current,a,b),cold=new JSONObject(current.recoveryStatus()).put("challenge",challenge);
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(good.toString());
            request.requireBoot(BOOT);request.requireCurrent(900,10001,1,current.generation());
            JSONObject before=regularFiles(root,heldFence);
            String rawReceipt=current.abandon(null,null,request,(path,directory)->{});
            JSONObject receipt=new JSONObject(rawReceipt).put("challenge",challenge),after=new JSONObject(current.recoveryStatus()).put("challenge",challenge);
            JSONObject files=regularFiles(root,heldFence);
            Files.writeString(output.resolve("authorization.json"),good.toString());
            Files.writeString(output.resolve("receipt.raw.json"),rawReceipt);
            // Exactly the challenge envelope added by ProcessHost; its Android actor observation is modeled here.
            Files.writeString(output.resolve("receipt.json"),receipt.toString());
            Files.writeString(output.resolve("cold_recovery.json"),cold.toString());
            Files.writeString(output.resolve("after_recovery.json"),after.toString());
            Files.writeString(output.resolve("before.files.base64.json"),before.toString());
            Files.writeString(output.resolve("after.files.base64.json"),files.toString());
            JSONObject meta=new JSONObject().put("schema","rusty.quest.host_test.production_v6_transaction.v1")
                .put("challenge",challenge).put("current_boot_id",BOOT).put("actor_and_absence","modeled")
                .put("cleanup_sha256",old==null?JSONObject.NULL:EmbeddedDuplexProcessFence.digest(old))
                .put("complete_snapshot_root",root.toString()).put("native_or_device_or_media_claim",false);
            Files.writeString(output.resolve("metadata.json"),meta.toString());
            if(old!=null)Files.writeString(output.resolve("projected-cleanup.json"),EmbeddedDuplexAbandonment.visibleReplay(dir.toFile(),NAME,old));
            current.requireFresh();
        }
        System.out.println("Emitted exact production v6 transaction "+(old==null?"absent":"present")+" to caller-selected private output; OS actor/absence modeled.");
    }
    public static void main(String[] args)throws Exception {
        String output=System.getProperty("rusty.quest.replayV6.output");
        if(output!=null) {
            if(args.length!=1)throw new IllegalArgumentException("one external cleanup fixture required for emission");
            JSONObject fixture=new JSONObject(Files.readString(Path.of(args[0])));String old=fixture.getString("cleanup");
            if(!EmbeddedDuplexProcessFence.digest(old).equals(fixture.getString("cleanup_sha256")))throw new AssertionError("fixture digest differs");
            emitTransaction(Path.of(output).resolve("present"),old);emitTransaction(Path.of(output).resolve("absent"),null);return;
        }
        if(args.length==2&&"--fixture-only".equals(args[0])){externalFixture(Path.of(args[1]));return;}
        transaction(OLD);transaction(null);v4LeavesCleanupVisible(OLD);replacesV5Marker(OLD,false);replacesV5Marker(OLD,true);changedDuringArchive();
        for(boolean present:new boolean[]{false,true}) {
            refusal(present,".new",false);refusal(present,".bak",false);refusal(present,null,true);
            crash(present,false);crash(present,true);
        }
        if(args.length==1)externalFixture(Path.of(args[0]));
        System.out.println("v6 crossboot five-record/null custody, commit/restart, current-scope, boot/actor/schema/drift/sidecar/crash refusals PASS; no native/media/physical-cleanup claim.");
    }
}
