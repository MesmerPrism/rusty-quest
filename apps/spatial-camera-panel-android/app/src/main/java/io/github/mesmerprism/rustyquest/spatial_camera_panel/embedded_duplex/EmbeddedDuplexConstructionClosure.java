package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Fixed Native-constructor custody writer, never a restore or media authority.
 * The caller is a fixed current Platform callback; ProcessHost earns the physical
 * gate from its retained actual owners before this writer is entered. */
final class EmbeddedDuplexConstructionClosure {
    static final String BODY="rusty.quest.embedded_duplex.partial_construction_closure.v1";
    static final String ACK="rusty.quest.embedded_duplex.partial_construction_closure_retained.v1";
    static final String NATIVE="rusty.quest.embedded_duplex.no_media_retirement.v3";
    static final String WHOLE="rusty.quest.embedded_duplex.no_media_whole_retirement.v3";
    private final File directory,file;
    EmbeddedDuplexConstructionClosure(Context context)throws Exception{
        this(new File(context.getNoBackupFilesDir(),"embedded-duplex-partial-construction-v1"));
    }
    // Same writer path for source-owned filesystem controls; no public/Binder API.
    EmbeddedDuplexConstructionClosure(File directory)throws Exception{
        this.directory=directory;
        if(!directory.exists()&&!directory.mkdir())throw new IllegalStateException("partial construction directory");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("partial construction directory type");
        Os.chmod(directory.getAbsolutePath(),0700);file=new File(directory,"closure.json");syncDirectory(directory.getParentFile());
    }
    private static void syncDirectory(File directory)throws Exception{
        java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
    static void fields(JSONObject body,String...expected)throws Exception{
        java.util.HashSet<String> keys=new java.util.HashSet<>();body.keys().forEachRemaining(keys::add);
        if(!keys.equals(new java.util.HashSet<>(java.util.Arrays.asList(expected))))throw new IllegalStateException("partial construction closed fields differ");
    }
    static void sameActor(JSONObject body,JSONObject actual)throws Exception{
        for(String key:new String[]{"app_process_generation","native_executor_generation"}){
            Object value=body.get(key),expected=actual.get(key);
            if(!(value instanceof Integer||value instanceof Long)||!(expected instanceof Integer||expected instanceof Long)
                    ||body.getLong(key)<=0||body.getLong(key)!=actual.getLong(key))throw new IllegalStateException("partial construction actual actor generation differs");
        }
        for(String key:new String[]{"app_record_sha256","config_sha256"})
            if(!(body.get(key)instanceof String)||!body.getString(key).equals(actual.getString(key)))throw new IllegalStateException("partial construction actual actor/config differs");
    }
    static void validate(JSONObject body)throws Exception{
        fields(body,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","source",
            "construction_disposition","new_media_starts","physical_source_terminal","old_outcomes_preserved","future_writable_restore");
        if(!BODY.equals(body.getString("$schema"))||!"not_committed".equals(body.getString("construction_disposition"))
                ||body.getLong("new_media_starts")!=0||!body.getBoolean("physical_source_terminal")||!body.getBoolean("old_outcomes_preserved")
                ||!"fenced_pending_supported_owner_reconciliation".equals(body.getString("future_writable_restore"))
                ||body.getLong("app_process_generation")<=0||body.getLong("native_executor_generation")<=0
                ||!body.getString("app_record_sha256").matches("[0-9a-f]{64}")||!body.getString("config_sha256").matches("[0-9a-f]{64}"))
            throw new IllegalStateException("partial construction closure schema");
        JSONObject source=body.getJSONObject("source");
        fields(source,"$schema","attempt","provider_entered","own_preparation","broker","provider","owner_dispatch_replay",
            "activation_replay","cleanup_replay","cleanup_preparations","host_published","new_owner_effects_dispatched");
        JSONObject broker=source.getJSONObject("broker");
        fields(broker,"$schema","factory_entered","peer_owner_created","broker_owner_created","peer_source","broker_source","control_lease_source","unpublished_source","writable_restore");
        if(!"rusty.quest.embedded_duplex.partial_construction_source.v1".equals(source.getString("$schema"))
                ||source.getLong("attempt")<=0||source.getBoolean("host_published")||source.getLong("new_owner_effects_dispatched")!=0
                ||source.getInt("own_preparation")<0||source.getInt("own_preparation")>3
                ||!source.getBoolean("provider_entered")
                ||!"rusty.quest.broker.construction_custody.v1".equals(broker.getString("$schema"))
                ||!"unsupported_preserve_actual_history".equals(broker.getString("writable_restore"))
                ||broker.getBoolean("peer_owner_created")==broker.isNull("peer_source")
                ||broker.getBoolean("broker_owner_created")==broker.isNull("broker_source"))
            throw new IllegalStateException("partial construction actual source disposition differs");
    }
    synchronized String load()throws Exception{
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(Files.exists(new File(directory,"closure.tmp").toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("partial construction pending durable write");
            return "{}";
        }
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("partial construction file type");
        byte[] bytes=Files.readAllBytes(file.toPath());String raw=new String(bytes,StandardCharsets.UTF_8);
        if(!java.util.Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)))throw new IllegalStateException("partial construction UTF8");
        validate(new JSONObject(raw));return raw;
    }
    synchronized String retain(String raw,JSONObject actual)throws Exception{
        JSONObject body=new JSONObject(raw);validate(body);
        sameActor(body,actual);
        String prior=load();
        if(!prior.equals("{}")){if(!prior.equals(raw))throw new IllegalStateException("partial construction immutable source drift");}
        else{
            File temporary=new File(directory,"closure.tmp");
            if(!temporary.createNewFile())throw new IllegalStateException("partial construction pending write");Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(raw.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }
        // Exact retry also earns fresh actual fsync + directory fsync + readback.
        java.io.FileDescriptor target=Os.open(file.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
        try{Os.fsync(target);}finally{Os.close(target);}syncDirectory(directory);
        if(!raw.equals(load()))throw new IllegalStateException("partial construction readback differs");
        JSONObject ack=new JSONObject().put("$schema",ACK)
            .put("body_sha256","sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(raw.getBytes(StandardCharsets.UTF_8)))
            .put("storage_identity","app-no-backup:partial-construction-v1:closure");
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})ack.put(key,body.get(key));
        return ack.toString();
    }
    static void requireNative(JSONObject proof,JSONObject actor)throws Exception{
        fields(proof,"$schema","native_runtime_binding","disposition","app_process_generation","app_record_sha256","native_executor_generation",
            "config_sha256","construction_disposition","closure_body_sha256","durable_closure","provider_entered","new_media_starts","old_outcomes_preserved","future_writable_restore");
        if(!NATIVE.equals(proof.getString("$schema"))||!"partial_construction".equals(proof.getString("native_runtime_binding"))
                ||!"partial_construction_closed".equals(proof.getString("disposition"))||!"not_committed".equals(proof.getString("construction_disposition"))
                ||proof.getLong("new_media_starts")!=0||!proof.getBoolean("old_outcomes_preserved")
                ||!proof.getString("closure_body_sha256").matches("sha256:[0-9a-f]{64}"))throw new IllegalStateException("partial native terminal schema");
        sameActor(proof,actor);
        if(proof.getBoolean("provider_entered")){
            if(!"fenced_pending_supported_owner_reconciliation".equals(proof.getString("future_writable_restore")))throw new IllegalStateException("partial native writable floor fence absent");
            JSONObject ack=proof.getJSONObject("durable_closure");fields(ack,"$schema","body_sha256","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","storage_identity");
            if(!ACK.equals(ack.getString("$schema"))||!ack.getString("body_sha256").equals(proof.getString("closure_body_sha256"))
                    ||!"app-no-backup:partial-construction-v1:closure".equals(ack.getString("storage_identity")))throw new IllegalStateException("partial native durable closure differs");
            sameActor(ack,proof);
        }else if(!proof.isNull("durable_closure")||!"owner_factory_not_entered".equals(proof.getString("future_writable_restore")))
            throw new IllegalStateException("unentered actual factory disposition differs");
    }
}
