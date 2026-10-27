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

/** Immutable native phase metadata custody. Actual original/task joins are separately required. */
final class EmbeddedDuplexFreshPhaseArchive {
    private final File directory;
    EmbeddedDuplexFreshPhaseArchive(Context context)throws Exception{
        directory=EmbeddedDuplexFreshClosure.activeDirectory(EmbeddedDuplexProcessHost.operationalRoot(context),"embedded-duplex-fresh-phase-archives-v1");
        if(!directory.exists() && !directory.mkdir())throw new IllegalStateException("fresh phase archive directory");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("fresh phase archive type");
        Os.chmod(directory.getAbsolutePath(),0700);
        java.io.FileDescriptor parent=Os.open(directory.getParentFile().getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(parent);}finally{Os.close(parent);}
    }
    synchronized JSONObject retain(String exact,JSONObject actualIdentity)throws Exception{
        JSONObject body=new JSONObject(exact);
        keys(body,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256",
            "native_request_id","phase_request_id","original_binding","phase_status","phase_state","authority_snapshot","prior_archive_projection","native_reservation","preparation");
        if(!"rusty.quest.embedded_duplex.fresh_setup_phase_archive.v2".equals(body.getString("$schema"))
                || !java.util.Arrays.asList("completed","fenced_unknown","preparation_completed","preparation_fenced_unknown","reservation_fenced_unknown").contains(body.getString("phase_status"))
                || !body.getString("native_request_id").matches("[a-z][a-z0-9_.-]{0,255}")
                || !body.getString("phase_request_id").matches("[a-z][a-z0-9_.-]{0,255}"))
            throw new IllegalArgumentException("fresh phase archive schema");
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),actualIdentity.get(key)))throw new IllegalStateException("fresh phase archive actor differs");
        boolean preparationOnly=body.getString("phase_status").startsWith("preparation_");
        if(preparationOnly){if(!body.isNull("phase_state"))throw new IllegalArgumentException("preparation archive invents Attempt");body.getJSONObject("preparation");}
        else if("reservation_fenced_unknown".equals(body.getString("phase_status"))){
            if(!body.isNull("phase_state") || !body.isNull("preparation"))throw new IllegalArgumentException("reservation archive invents Attempt/preparation");
        }else body.getJSONObject("phase_state");
        body.getJSONObject("native_reservation");
        if(!body.isNull("preparation"))body.getJSONObject("preparation");
        body.getJSONObject("authority_snapshot");body.getJSONObject("prior_archive_projection");
        String digest=EmbeddedDuplexCanonicalJson.sha256Bytes(exact.getBytes(StandardCharsets.UTF_8));
        File target=new File(directory,digest+".json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)
                    || !exact.equals(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8)))
                throw new IllegalStateException("fresh phase archive bytes changed");
        }else{
            File temporary=new File(directory,digest+".tmp");
            if(!temporary.createNewFile())throw new IllegalStateException("fresh phase archive pending write");
            Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(exact.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
            java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
            try{Os.fsync(fd);}finally{Os.close(fd);}
        }
        java.io.FileDescriptor retainedFd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(retainedFd);}finally{Os.close(retainedFd);}
        java.io.FileDescriptor directoryFd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(directoryFd);}finally{Os.close(directoryFd);}
        if(!java.util.Arrays.equals(exact.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(target.toPath())))
            throw new IllegalStateException("fresh phase archive fsync readback differs");
        JSONObject ack=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_setup_phase_archive_retained.v2")
            .put("body_sha256","sha256:"+digest).put("native_request_id",body.getString("native_request_id"))
            .put("phase_request_id",body.getString("phase_request_id")).put("original_binding",body.getJSONObject("original_binding"))
            .put("storage_identity","app-no-backup:fresh-phase-v2:"+digest);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})ack.put(key,body.get(key));
        retainIndex(body,ack);
        return ack;
    }
    private String phaseKey(JSONObject body)throws Exception{
        return EmbeddedDuplexCanonicalJson.sha256(new JSONObject().put("native_request_id",body.getString("native_request_id"))
            .put("phase_request_id",body.getString("phase_request_id")).put("original_binding",body.getJSONObject("original_binding")));
    }
    private void retainIndex(JSONObject body,JSONObject ack)throws Exception{
        File target=new File(directory,"phase-"+phaseKey(body)+".json");byte[] raw=EmbeddedDuplexCanonicalJson.encode(ack).getBytes(StandardCharsets.UTF_8);
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||!java.util.Arrays.equals(raw,Files.readAllBytes(target.toPath())))
                throw new IllegalStateException("phase archive source index changed");
        }else{
            File tmp=new File(directory,target.getName()+".tmp");if(!tmp.createNewFile())throw new IllegalStateException("phase index pending write");
            Os.chmod(tmp.getAbsolutePath(),0600);try(FileOutputStream output=new FileOutputStream(tmp)){output.write(raw);output.getFD().sync();}
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
            java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);try{Os.fsync(fd);}finally{Os.close(fd);}
            if(!java.util.Arrays.equals(raw,Files.readAllBytes(target.toPath())))throw new IllegalStateException("phase index durable readback differs");
        }
        java.io.FileDescriptor retainedFd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(retainedFd);}finally{Os.close(retainedFd);}
        java.io.FileDescriptor directoryFd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(directoryFd);}finally{Os.close(directoryFd);}
    }
    synchronized boolean archivedIncoming(String nativeId,String phaseId,JSONObject actualIdentity)throws Exception{
        JSONObject identity=new JSONObject().put("native_request_id",nativeId).put("phase_request_id",phaseId)
            .put("original_binding",new JSONObject().put("kind","incoming_fresh_setup").put("native_request_id",nativeId));
        File index=new File(directory,"phase-"+phaseKey(identity)+".json");
        if(!Files.exists(index.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
        if(!Files.isRegularFile(index.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("incoming replay archive index type");
        JSONObject ack=new JSONObject(new String(Files.readAllBytes(index.toPath()),StandardCharsets.UTF_8));
        JSONObject body=requireRetained(ack,actualIdentity);
        if(!nativeId.equals(body.getString("native_request_id")) || !phaseId.equals(body.getString("phase_request_id")))
            throw new IllegalStateException("incoming replay archive index identity changed");
        return true;
    }
    synchronized JSONObject requireOperationSource(JSONObject operation,JSONObject actualIdentity)throws Exception{
        File target=new File(directory,"phase-"+phaseKey(operation)+".json");
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("actual native phase archive index missing");
        JSONObject ack=new JSONObject(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
        return requireRetained(ack,actualIdentity);
    }
    synchronized JSONObject requireRetained(JSONObject ack,JSONObject actualIdentity)throws Exception{
        keys(ack,"$schema","body_sha256","app_process_generation","app_record_sha256","native_executor_generation","config_sha256",
            "native_request_id","phase_request_id","original_binding","storage_identity");
        if(!"rusty.quest.embedded_duplex.fresh_setup_phase_archive_retained.v2".equals(ack.getString("$schema"))
                || !ack.getString("body_sha256").matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("fresh phase retained ACK schema");
        String digest=ack.getString("body_sha256").substring(7);
        File target=new File(directory,digest+".json");
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("fresh phase retained source missing");
        String raw=new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8);
        JSONObject recomputed=retain(raw,actualIdentity);
        if(!EmbeddedDuplexCanonicalJson.encode(recomputed).equals(EmbeddedDuplexCanonicalJson.encode(ack)))
            throw new IllegalStateException("fresh phase retained source ACK differs");
        return new JSONObject(raw);
    }
    private static void keys(JSONObject value,String... expected){
        java.util.HashSet<String> actual=new java.util.HashSet<>();value.keys().forEachRemaining(actual::add);
        if(!actual.equals(new java.util.HashSet<>(java.util.Arrays.asList(expected))))throw new IllegalArgumentException("fresh phase archive closed fields");
    }
}
