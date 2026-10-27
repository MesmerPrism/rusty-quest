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

/** Metadata custody only, after actual SDK and Original joins. Never a media grant. */
final class EmbeddedDuplexPreparedArchive {
    private final Context application;
    private final File directory;
    EmbeddedDuplexPreparedArchive(Context context)throws Exception {
        application=context.getApplicationContext();directory=EmbeddedDuplexFreshClosure.activeDirectory(EmbeddedDuplexProcessHost.operationalRoot(application),"embedded-duplex-prepared-lifetimes-v1");
        if(!directory.exists()&&!directory.mkdir())throw new IllegalStateException("prepared archive directory");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("prepared archive directory type");
        Os.chmod(directory.getAbsolutePath(),0700);sync(directory.getParentFile());
    }
    synchronized JSONObject retain(String exact,JSONObject actor,EmbeddedDuplexPreparedStart holder)throws Exception {
        JSONObject body=new JSONObject(exact);
        EmbeddedDuplexPeerRoots.keys(body,"$schema","original_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","prepared_status","terminal_lifetime");
        if(!"rusty.quest.embedded_duplex.prepared_start_lifetime_archive.v1".equals(body.getString("$schema"))||holder==null)
            throw new IllegalStateException("actual prepared archive schema/holder absent");
        EmbeddedDuplexPeerRoots.identity(body,actor);holder.requireArchiveBody(body);
        EmbeddedDuplexOriginals.forApplication(application).requirePreparedArchive(body,actor);
        byte[] bytes=exact.getBytes(StandardCharsets.UTF_8);String digest=EmbeddedDuplexCanonicalJson.sha256Bytes(bytes);
        File target=new File(directory,digest+".json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))
                throw new IllegalStateException("prepared immutable archive changed");
        } else {
            File temporary=new File(directory,digest+".tmp");
            if(!temporary.createNewFile()) {
                if(!Files.isRegularFile(temporary.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("prepared pending archive type");
                byte[] prefix=Files.readAllBytes(temporary.toPath());
                if(prefix.length>bytes.length)throw new IllegalStateException("prepared pending archive changed");
                for(int i=0;i<prefix.length;i++)if(prefix[i]!=bytes[i])throw new IllegalStateException("prepared pending archive changed");
                // Fixed Native retains these exact bytes. Complete only this owned prefix; never replace drift.
                try(FileOutputStream out=new FileOutputStream(temporary,true)){out.write(bytes,prefix.length,bytes.length-prefix.length);out.getFD().sync();}
            } else {
                Os.chmod(temporary.getAbsolutePath(),0600);
                try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
            }
            if(!java.util.Arrays.equals(bytes,Files.readAllBytes(temporary.toPath())))throw new IllegalStateException("prepared archive pre-move readback");
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }
        java.io.FileDescriptor fd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}sync(directory);
        if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("prepared durable archive readback");
        JSONObject ack=new JSONObject().put("$schema","rusty.quest.embedded_duplex.prepared_start_lifetime_retained.v1")
            .put("body_sha256","sha256:"+digest).put("original_id",body.getString("original_id"))
            .put("storage_identity","app-no-backup:prepared-lifetime-v1:"+digest);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})ack.put(key,body.get(key));
        return ack;
    }
    synchronized JSONObject retainedExact(String exact,JSONObject actor,JSONObject expectedAck)throws Exception {
        JSONObject body=new JSONObject(exact);EmbeddedDuplexPeerRoots.identity(body,actor);
        byte[] bytes=exact.getBytes(StandardCharsets.UTF_8);String digest=EmbeddedDuplexCanonicalJson.sha256Bytes(bytes);
        if(expectedAck==null||!expectedAck.getString("body_sha256").equals("sha256:"+digest)
            ||!expectedAck.getString("original_id").equals(body.getString("original_id"))
            ||!expectedAck.getString("storage_identity").equals("app-no-backup:prepared-lifetime-v1:"+digest))
            throw new IllegalStateException("actual released prepared archive identity differs");
        EmbeddedDuplexPeerRoots.identity(expectedAck,actor);
        File target=new File(directory,digest+".json");
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))
            throw new IllegalStateException("actual released prepared archive readback differs");
        java.io.FileDescriptor fd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}sync(directory);
        if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("prepared retry durable readback");
        return new JSONObject(expectedAck.toString());
    }
    private static void sync(File directory)throws Exception {
        java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
}
