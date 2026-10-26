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

/** Exact Native phase-adoption custody. Does not alter an Original outcome. */
final class EmbeddedDuplexFreshHandoffStore {
    private final File directory;
    EmbeddedDuplexFreshHandoffStore(Context context)throws Exception{
        directory=EmbeddedDuplexFreshClosure.activeDirectory(context.getNoBackupFilesDir(),"embedded-duplex-fresh-handoffs-v1");
        if(!directory.exists() && !directory.mkdir())throw new IllegalStateException("fresh handoff directory create");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("fresh handoff directory type");
        Os.chmod(directory.getAbsolutePath(),0700);sync(directory.getParentFile());
    }
    JSONObject retainOperator(String raw,JSONObject identity)throws Exception{
        JSONObject body=new JSONObject(raw);
        keys(body,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256",
            "native_request_id","prior_phase_id","phase_request_id","prior_original_id","prior_outcome_sha256",
            "current_original_id","ticket_sha256","local_archive","peer_archive");
        if(!"rusty.quest.embedded_duplex.fresh_setup_continuation_handoff.v1".equals(body.getString("$schema"))
                || !body.getString("prior_original_id").matches("[0-9a-f]{32}")
                || !body.getString("current_original_id").matches("[0-9a-f]{32}")
                || body.getString("prior_original_id").equals(body.getString("current_original_id"))
                || !body.getString("prior_outcome_sha256").matches("[0-9a-f]{64}"))throw new IllegalArgumentException("operator handoff identity");
        validate(body,identity);
        retain("operator-"+body.getString("current_original_id"),raw);
        retain("prior-"+body.getString("prior_original_id"),raw);
        JSONObject ack=ack(body,raw,"rusty.quest.embedded_duplex.fresh_setup_continuation_handoff_retained.v1");
        ack.put("prior_original_id",body.getString("prior_original_id")).put("current_original_id",body.getString("current_original_id"));
        return ack;
    }
    JSONObject retainIncoming(String raw,JSONObject identity)throws Exception{
        JSONObject body=new JSONObject(raw);
        keys(body,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256",
            "native_request_id","prior_phase_id","phase_request_id","ticket_sha256","local_archive");
        if(!"rusty.quest.embedded_duplex.fresh_setup_incoming_handoff.v1".equals(body.getString("$schema")))
            throw new IllegalArgumentException("incoming handoff schema");
        validate(body,identity);String storage=retain("incoming-"+EmbeddedDuplexCanonicalJson.sha256(
            new JSONObject().put("native_request_id",body.getString("native_request_id")).put("phase_request_id",body.getString("phase_request_id"))),raw);
        return ack(body,raw,"rusty.quest.embedded_duplex.fresh_setup_incoming_handoff_retained.v1")
            .put("native_request_id",body.getString("native_request_id")).put("prior_phase_id",body.getString("prior_phase_id"))
            .put("phase_request_id",body.getString("phase_request_id")).put("storage_identity",storage);
    }
    String operatorBody(String id)throws Exception{
        if(!id.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("operator handoff query ID");
        File target=new File(directory,"operator-"+id+".json");
        if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("operator handoff source type");
        return new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8);
    }
    String priorBody(String id)throws Exception{
        if(!id.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("prior custody ID");
        File target=new File(directory,"prior-"+id+".json");
        if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("prior custody retained type");
        String raw=new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8);JSONObject body=new JSONObject(raw);
        String current=operatorBody(body.getString("current_original_id"));
        if(!id.equals(body.getString("prior_original_id")) || !raw.equals(current))throw new IllegalStateException("prior custody exact successor bytes differ");
        return raw;
    }
    String incomingBody(String nativeId,String phaseId)throws Exception{
        EmbeddedDuplexIncomingFreshPhases.id(nativeId);EmbeddedDuplexIncomingFreshPhases.id(phaseId);
        String key="incoming-"+EmbeddedDuplexCanonicalJson.sha256(new JSONObject().put("native_request_id",nativeId).put("phase_request_id",phaseId));
        File target=new File(directory,key+".json");
        if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("incoming handoff source type");
        return new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8);
    }
    private static void validate(JSONObject body,JSONObject identity)throws Exception{
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),identity.get(key)))throw new IllegalStateException("fresh handoff actual source identity changed");
        EmbeddedDuplexIncomingFreshPhases.id(body.getString("native_request_id"));
        EmbeddedDuplexIncomingFreshPhases.id(body.getString("prior_phase_id"));EmbeddedDuplexIncomingFreshPhases.id(body.getString("phase_request_id"));
        if(body.getString("prior_phase_id").equals(body.getString("phase_request_id"))
                || !body.getString("ticket_sha256").matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("fresh handoff phase/ticket");
        body.getJSONObject("local_archive");if(body.has("peer_archive"))body.getJSONObject("peer_archive");
    }
    private synchronized String retain(String key,String raw)throws Exception{
        byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);File target=new File(directory,key+".json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)
                    || !java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("fresh handoff exact bytes changed");
        }else{
            File temporary=new File(directory,key+".tmp");
            if(!temporary.createNewFile())throw new IllegalStateException("fresh handoff pending write");
            Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(directory);
            if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("fresh handoff durable readback differs");
        }
        java.io.FileDescriptor fd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}sync(directory);
        if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("handoff final durable bytes differ");
        return "app-no-backup:fresh-handoff-v1:"+key;
    }
    static JSONObject ack(JSONObject body,String raw,String schema)throws Exception{
        JSONObject ack=new JSONObject().put("$schema",schema).put("body_sha256","sha256:"+
            EmbeddedDuplexCanonicalJson.sha256Bytes(raw.getBytes(StandardCharsets.UTF_8)));
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})ack.put(key,body.get(key));
        return ack;
    }
    private static void sync(File directory)throws Exception{
        java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
    static void keys(JSONObject value,String... expected){
        java.util.HashSet<String> actual=new java.util.HashSet<>();value.keys().forEachRemaining(actual::add);
        if(!actual.equals(new java.util.HashSet<>(java.util.Arrays.asList(expected))))throw new IllegalArgumentException("fresh handoff closed fields");
    }
}
