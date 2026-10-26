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

/** Terminal metadata custody for an UNKNOWN root. Never writable restore or cleanup. */
final class EmbeddedDuplexUnknownPeerRootCustody {
    static final String BODY="rusty.quest.embedded_duplex.unknown_peer_root_terminal_custody.v1";
    static final String ACK="rusty.quest.embedded_duplex.unknown_peer_root_terminal_custody_retained.v1";
    static final String RESTORE="fenced_pending_supported_owner_reconciliation";
    private final Context application;
    private final File directory;
    EmbeddedDuplexUnknownPeerRootCustody(Context context)throws Exception {
        application=context.getApplicationContext();directory=EmbeddedDuplexFreshClosure.activeDirectory(application.getNoBackupFilesDir(),"embedded-duplex-unknown-root-custody-v1");
        if(!directory.exists()&&!directory.mkdir())throw new IllegalStateException("unknown root custody directory");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("unknown root custody directory type");
        Os.chmod(directory.getAbsolutePath(),0700);syncDirectory(directory.getParentFile());
    }
    private static void body(JSONObject body)throws Exception {
        EmbeddedDuplexPeerRoots.keys(body,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","original_id",
            "committed_owner_receipt","authority_snapshot","broker_evidence","current_roots","observed_at_ms","transaction_outcome","physical_cleanup_claimed","future_writable_restore");
        if(!BODY.equals(body.getString("$schema"))||!body.getString("original_id").matches("[0-9a-f]{32}")
            ||!"unknown".equals(body.getString("transaction_outcome"))||!Boolean.FALSE.equals(body.get("physical_cleanup_claimed"))
            ||!RESTORE.equals(body.getString("future_writable_restore")))throw new IllegalArgumentException("unknown root custody closed state");
        Object clock=body.get("observed_at_ms");if(!(clock instanceof Integer||clock instanceof Long)||((Number)clock).longValue()<0)
            throw new IllegalArgumentException("unknown root actual clock");
        JSONObject receipt=body.getJSONObject("committed_owner_receipt");EmbeddedDuplexPeerRoots.keys(receipt,"$schema","authority","current_pair","broker");
        if(!"rusty.manifold.peer.source_bound_root_reestablishment_receipt.v1".equals(receipt.getString("$schema")))throw new IllegalArgumentException("unknown root actual committed owner receipt");
        body.getJSONObject("authority_snapshot");body.getJSONObject("broker_evidence");EmbeddedDuplexPeerRoots.roots(body.getJSONObject("current_roots"));
        EmbeddedDuplexCanonicalJson.encode(body); // Strict exact integer/string types; not a policy authority check.
    }
    synchronized String retain(String exact,JSONObject actor)throws Exception {
        JSONObject value=new JSONObject(exact);body(value);EmbeddedDuplexPeerRoots.identity(value,actor);
        String outcome=EmbeddedDuplexOriginals.forApplication(application).requireUnknownPeerRootTerminal(value,actor);
        byte[] bytes=exact.getBytes(StandardCharsets.UTF_8);String digest=EmbeddedDuplexCanonicalJson.sha256Bytes(bytes);
        retainExact(new File(directory,digest+".json"),bytes);
        JSONObject ack=new JSONObject().put("$schema",ACK).put("body_sha256","sha256:"+digest).put("original_id",value.getString("original_id"))
            .put("outcome_sha256",outcome).put("storage_identity","app-no-backup:unknown-peer-root-v1:"+digest);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})ack.put(key,value.get(key));
        // Permanent marker is earned before ACK. Ordinary retention ACK and a
        // later Bootstrap never remove or reinterpret this terminal decision.
        retainExact(new File(directory,"TERMINAL.json"),EmbeddedDuplexCanonicalJson.encode(ack).getBytes(StandardCharsets.UTF_8));
        return ack.toString();
    }
    synchronized String load()throws Exception {
        File marker=new File(directory,"TERMINAL.json");if(!Files.exists(marker.toPath(),LinkOption.NOFOLLOW_LINKS)){
            File[] pending=directory.listFiles();
            if(pending==null || pending.length!=0)throw new IllegalStateException("incomplete unknown root custody fences bootstrap");
            return "{}";
        }
        if(!Files.isRegularFile(marker.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("unknown root marker type");
        JSONObject ack=new JSONObject(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8));
        EmbeddedDuplexPeerRoots.keys(ack,"$schema","body_sha256","original_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","storage_identity","outcome_sha256");
        if(!ACK.equals(ack.getString("$schema"))||!ack.getString("body_sha256").matches("sha256:[0-9a-f]{64}")
            ||!ack.getString("outcome_sha256").matches("[0-9a-f]{64}"))throw new IllegalStateException("unknown root retained marker schema");
        String digest=ack.getString("body_sha256").substring(7);File target=new File(directory,digest+".json");
        if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("unknown root retained body type");
        byte[] bytes=Files.readAllBytes(target.toPath());String exact=new String(bytes,StandardCharsets.UTF_8);
        if(!digest.equals(EmbeddedDuplexCanonicalJson.sha256Bytes(bytes))
            ||!("app-no-backup:unknown-peer-root-v1:"+digest).equals(ack.getString("storage_identity")))throw new IllegalStateException("unknown root retained body changed");
        JSONObject body=new JSONObject(exact);body(body);EmbeddedDuplexPeerRoots.identity(body,ack);
        if(!body.getString("original_id").equals(ack.getString("original_id")))throw new IllegalStateException("unknown root retained Original differs");
        return exact; // A fence only. It reconstructs no live Native/M owner.
    }
    synchronized JSONObject requireMetadataResult(String raw,JSONObject actor)throws Exception {
        JSONObject result=new JSONObject(raw);EmbeddedDuplexPeerRoots.keys(result,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256",
            "original_id","body_sha256","custody","transaction_outcome","metadata_retired","physical_cleanup_claimed","future_writable_restore");
        if(!"rusty.quest.embedded_duplex.unknown_peer_root_metadata_retirement.v1".equals(result.getString("$schema"))
            ||!"unknown".equals(result.getString("transaction_outcome"))||!Boolean.TRUE.equals(result.get("metadata_retired"))
            ||!Boolean.FALSE.equals(result.get("physical_cleanup_claimed"))||!RESTORE.equals(result.getString("future_writable_restore")))
            throw new IllegalStateException("unknown root metadata result state differs");
        EmbeddedDuplexPeerRoots.identity(result,actor);String exact=load();
        if(exact.equals("{}"))throw new IllegalStateException("unknown root actual durable marker absent");
        JSONObject body=new JSONObject(exact),ack=new JSONObject(new String(Files.readAllBytes(new File(directory,"TERMINAL.json").toPath()),StandardCharsets.UTF_8));
        if(!result.getString("original_id").equals(body.getString("original_id"))
            ||!result.getString("body_sha256").equals(ack.getString("body_sha256"))
            ||!EmbeddedDuplexCanonicalJson.sameValue(result.getJSONObject("custody"),ack))
            throw new IllegalStateException("unknown root actual metadata custody differs");
        return result;
    }
    private void retainExact(File target,byte[] bytes)throws Exception {
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))
                throw new IllegalStateException("unknown root immutable custody changed");
        }else{
            File tmp=new File(directory,target.getName()+".tmp");if(!tmp.createNewFile())throw new IllegalStateException("unknown root pending write");
            Os.chmod(tmp.getAbsolutePath(),0600);try(FileOutputStream output=new FileOutputStream(tmp)){output.write(bytes);output.getFD().sync();}
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }
        java.io.FileDescriptor fd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}syncDirectory(directory);
        if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("unknown root durable readback changed");
    }
    private static void syncDirectory(File directory)throws Exception {
        java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
}
