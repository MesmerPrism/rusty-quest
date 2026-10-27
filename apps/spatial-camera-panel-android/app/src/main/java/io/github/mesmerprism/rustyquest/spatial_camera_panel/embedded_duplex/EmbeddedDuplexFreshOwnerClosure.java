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

/** Tagged terminal owner custody; never a counterfeit UNKNOWN or a writable restored owner. */
final class EmbeddedDuplexFreshOwnerClosure {
    private final File directory,file;
    EmbeddedDuplexFreshOwnerClosure(Context context)throws Exception{
        directory=EmbeddedDuplexFreshClosure.activeDirectory(EmbeddedDuplexProcessHost.operationalRoot(context),"embedded-duplex-fresh-owner-closure-v1");
        if(!directory.exists() && !directory.mkdir())throw new IllegalStateException("fresh owner closure directory");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("fresh owner closure type");
        Os.chmod(directory.getAbsolutePath(),0700);file=new File(directory,"terminal-owner.json");
        java.io.FileDescriptor parent=Os.open(directory.getParentFile().getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(parent);}finally{Os.close(parent);}
    }
    synchronized String load()throws Exception{
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(Files.exists(new File(directory,"terminal-owner.tmp").toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("fresh owner closure pending durable write");
            return "{}";
        }
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("fresh owner closure file type");
        String exact=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);validate(new JSONObject(exact));return exact;
    }
    synchronized boolean retainedExact(String exact)throws Exception{
        return Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&load().equals(exact);
    }
    synchronized String retain(String exact,JSONObject actualIdentity)throws Exception{
        JSONObject body=new JSONObject(exact);validate(body);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),actualIdentity.get(key)))throw new IllegalStateException("fresh owner closure actor differs");
        String prior=Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)?load():"{}";
        if(!prior.equals("{}")){if(!prior.equals(exact))throw new IllegalStateException("fresh owner closure changed");}
        else{
            File temporary=new File(directory,"terminal-owner.tmp");
            byte[] bytes=exact.getBytes(StandardCharsets.UTF_8);
            if(!temporary.createNewFile()){
                if(!Files.isRegularFile(temporary.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("fresh owner closure pending type");
                byte[] prefix=Files.readAllBytes(temporary.toPath());
                if(prefix.length>bytes.length)throw new IllegalStateException("fresh owner closure pending drift");
                for(int i=0;i<prefix.length;i++)if(prefix[i]!=bytes[i])throw new IllegalStateException("fresh owner closure pending drift");
                try(FileOutputStream output=new FileOutputStream(temporary,true)){output.write(bytes,prefix.length,bytes.length-prefix.length);output.getFD().sync();}
            }else{
                Os.chmod(temporary.getAbsolutePath(),0600);
                try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            }
            if(!java.util.Arrays.equals(bytes,Files.readAllBytes(temporary.toPath())))throw new IllegalStateException("fresh owner closure pending readback");
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);
            java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
            try{Os.fsync(fd);}finally{Os.close(fd);}
        }
        // Re-earn durability even on exact-existing recovery after a lost callback result.
        java.io.FileDescriptor durable=Os.open(file.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(durable);}finally{Os.close(durable);}
        java.io.FileDescriptor parent=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(parent);}finally{Os.close(parent);}
        if(!exact.equals(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)))throw new IllegalStateException("fresh owner closure final durable readback");
        JSONObject ack=new JSONObject().put("$schema",body.getString("$schema").equals("rusty.quest.embedded_duplex.fresh_owner_retirement_closure.v2")
            ?"rusty.quest.embedded_duplex.fresh_owner_retirement_closure_retained.v2":"rusty.quest.embedded_duplex.fresh_owner_retirement_closure_retained.v1")
            .put("body_sha256","sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(exact.getBytes(StandardCharsets.UTF_8)))
            .put("storage_identity","app-no-backup:fresh-owner-v1:terminal-owner");
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})ack.put(key,body.get(key));
        return ack.toString();
    }
    private static void validate(JSONObject body)throws Exception{
        java.util.HashSet<String> keys=new java.util.HashSet<>();body.keys().forEachRemaining(keys::add);
        boolean prepared="rusty.quest.embedded_duplex.fresh_owner_retirement_closure.v2".equals(body.getString("$schema"));
        java.util.HashSet<String> expected=new java.util.HashSet<>(java.util.Arrays.asList("$schema","app_process_generation","app_record_sha256",
            "native_executor_generation","config_sha256","retirement_scope","close_proof","native_pair_state",
            "native_lifetime_state","authority_snapshot","future_writable_restore"));
        if(prepared)expected.add("prepared_start");
        if(!keys.equals(expected)
                || !prepared&&!"rusty.quest.embedded_duplex.fresh_owner_retirement_closure.v1".equals(body.getString("$schema"))
                || !java.util.Arrays.asList("normal_peer_whole_close","configured_no_media_close").contains(body.getString("retirement_scope"))
                || !"fenced_pending_supported_owner_reconciliation".equals(body.getString("future_writable_restore"))
                || body.getLong("app_process_generation")<=0 || body.getLong("native_executor_generation")<=0
                || !body.getString("app_record_sha256").matches("[0-9a-f]{64}") || !body.getString("config_sha256").matches("[0-9a-f]{64}"))
            throw new IllegalStateException("fresh owner terminal closure schema");
        if(prepared){
            JSONObject holder=body.getJSONObject("prepared_start");EmbeddedDuplexPeerRoots.keys(holder,"status","archive_cursor");
            if(!"rusty.quest.embedded_duplex.prepared_peer_start_status.v1".equals(holder.getJSONObject("status").getString("$schema")))
                throw new IllegalStateException("whole closure prepared Native status schema");
            EmbeddedDuplexPeerRoots.identity(holder.getJSONObject("status"),body);
            if(!holder.isNull("archive_cursor")){
                JSONObject cursor=holder.getJSONObject("archive_cursor");EmbeddedDuplexPeerRoots.keys(cursor,"$schema","ordinal","last","history_sha256");
                if(!"rusty.quest.embedded_duplex.prepared_start_archive_cursor.v1".equals(cursor.getString("$schema"))||cursor.getLong("ordinal")<=0
                    ||!cursor.getString("history_sha256").matches("sha256:[0-9a-f]{64}"))throw new IllegalStateException("whole closure prepared archive cursor differs");
            }
        }
        body.getJSONObject("close_proof");body.getJSONObject("native_pair_state");body.getJSONObject("native_lifetime_state");body.getJSONObject("authority_snapshot");
    }
}
