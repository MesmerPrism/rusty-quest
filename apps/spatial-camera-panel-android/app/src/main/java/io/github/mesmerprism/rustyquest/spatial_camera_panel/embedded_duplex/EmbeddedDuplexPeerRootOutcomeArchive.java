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

/** Exact successful root outcome custody. It grants no authority or cleanup. */
final class EmbeddedDuplexPeerRootOutcomeArchive {
    private final Context application;
    private final File directory;
    EmbeddedDuplexPeerRootOutcomeArchive(Context context)throws Exception {
        application=context.getApplicationContext();
        directory=EmbeddedDuplexFreshClosure.activeDirectory(EmbeddedDuplexProcessHost.operationalRoot(application),"embedded-duplex-peer-root-outcomes-v1");
        if(!directory.exists() && !directory.mkdir())throw new IllegalStateException("root outcome directory");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("root outcome directory type");
        Os.chmod(directory.getAbsolutePath(),0700);
        java.io.FileDescriptor parent=Os.open(directory.getParentFile().getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(parent);}finally{Os.close(parent);}
    }
    synchronized String retain(String exact,JSONObject actor)throws Exception {
        JSONObject body=new JSONObject(exact);
        EmbeddedDuplexOriginals.forApplication(application).requireArchivedPeerRoots(exact,body,actor);
        byte[] bytes=exact.getBytes(StandardCharsets.UTF_8);
        String digest=EmbeddedDuplexCanonicalJson.sha256Bytes(bytes);
        File target=new File(directory,digest+".json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)
                || !java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))
                throw new IllegalStateException("root outcome archive changed");
        } else {
            File temporary=new File(directory,digest+".tmp");
            if(!temporary.createNewFile())throw new IllegalStateException("root outcome pending write");
            Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }
        // Exact-existing recovery must earn these barriers too: a previous
        // attempt may have moved its file and failed before directory fsync.
        java.io.FileDescriptor fileFd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fileFd);}finally{Os.close(fileFd);}
        java.io.FileDescriptor dirFd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(dirFd);}finally{Os.close(dirFd);}
        if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("root outcome durable readback differs");
        JSONObject ack=new JSONObject().put("$schema","rusty.quest.embedded_duplex.peer_root_outcome_custody.v1")
            .put("body_sha256","sha256:"+digest).put("original_id",body.getString("original_id"))
            .put("storage_identity","app-no-backup:peer-root-outcome-v1:"+digest);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})ack.put(key,body.get(key));
        return ack.toString();
    }
}
