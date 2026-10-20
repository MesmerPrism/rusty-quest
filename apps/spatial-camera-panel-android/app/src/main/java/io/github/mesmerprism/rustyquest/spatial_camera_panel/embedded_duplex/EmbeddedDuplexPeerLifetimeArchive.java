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

/** Durable custody of completed native lifetime metadata. It never restores a writable owner. */
final class EmbeddedDuplexPeerLifetimeArchive {
    private final Context application;
    private final File directory;
    EmbeddedDuplexPeerLifetimeArchive(Context context)throws Exception{
        application=context.getApplicationContext();directory=new File(application.getNoBackupFilesDir(),"embedded-duplex-lifetime-archives-v1");
        if(!directory.exists() && !directory.mkdir())throw new IllegalStateException("lifetime archive directory");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("lifetime archive directory type");
        Os.chmod(directory.getAbsolutePath(),0700);
        java.io.FileDescriptor parent=Os.open(directory.getParentFile().getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(parent);}finally{Os.close(parent);}
    }
    synchronized String retain(String exact,JSONObject currentIdentity)throws Exception{
        JSONObject body=new JSONObject(exact);java.util.HashSet<String> keys=new java.util.HashSet<>();body.keys().forEachRemaining(keys::add);
        if(!keys.equals(new java.util.HashSet<>(java.util.Arrays.asList("$schema","app_process_generation","app_record_sha256",
                "native_executor_generation","config_sha256","prior_stop_original_id","terminal_native_stop_projection",
                "native_lifetime_state","prior_archive_projection")))
                || !body.getString("$schema").equals("rusty.quest.embedded_duplex.peer_lifetime_archive.v1"))
            throw new IllegalStateException("lifetime archive closed schema");
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),currentIdentity.get(key)))throw new IllegalStateException("lifetime archive actor differs");
        body.getJSONObject("native_lifetime_state");body.getJSONObject("prior_archive_projection");
        EmbeddedDuplexOriginals.forApplication(application).requireArchivedStop(body.getString("prior_stop_original_id"),
            body.getJSONObject("terminal_native_stop_projection"),currentIdentity);
        String digest=EmbeddedDuplexProcessFence.digest(exact);
        File target=new File(directory,digest+".json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)
                    || !exact.equals(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8)))
                throw new IllegalStateException("lifetime archive bytes changed");
        }else{
            File temporary=new File(directory,digest+".tmp");
            if(!temporary.createNewFile())throw new IllegalStateException("lifetime archive retained pending write");
            Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){
                output.write(exact.getBytes(StandardCharsets.UTF_8));output.getFD().sync();
            }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
            java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
            try{Os.fsync(fd);}finally{Os.close(fd);}
        }
        JSONObject ack=new JSONObject().put("$schema","rusty.quest.embedded_duplex.peer_lifetime_archive_retained.v1")
            .put("body_sha256","sha256:"+digest).put("prior_stop_original_id",body.getString("prior_stop_original_id"))
            .put("storage_identity","app-no-backup:peer-lifetime-v1:"+digest);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            ack.put(key,currentIdentity.get(key));
        return ack.toString();
    }
}
