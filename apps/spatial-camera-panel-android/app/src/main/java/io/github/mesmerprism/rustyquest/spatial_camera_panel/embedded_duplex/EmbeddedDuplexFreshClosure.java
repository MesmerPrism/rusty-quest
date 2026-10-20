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

/** A retained UNKNOWN authority transaction permanently fences writable bootstrap until a typed reconciliation exists. */
final class EmbeddedDuplexFreshClosure {
    private final File directory;
    private final File file;
    EmbeddedDuplexFreshClosure(Context context) throws Exception {
        directory = new File(context.getNoBackupFilesDir(), "embedded-duplex-fresh-closure-v1");
        if (!directory.exists() && !directory.mkdir()) throw new IllegalStateException("fresh closure directory");
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("fresh closure directory type");
        Os.chmod(directory.getAbsolutePath(),0700);
        java.io.FileDescriptor parent=Os.open(directory.getParentFile().getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(parent);}finally{Os.close(parent);}
        file = new File(directory,"pending-authority-closure.json");
    }
    synchronized String load() throws Exception {
        if (!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(new File(directory,"pending-authority-closure.tmp").toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("fresh closure incomplete durable handoff");
            return "{}";
        }
        if (!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("fresh closure file type");
        String exact = new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
        validate(new JSONObject(exact)); return exact;
    }
    synchronized String retain(String exact, JSONObject actualBinding) throws Exception {
        JSONObject body = new JSONObject(exact); validate(body);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(body.get(key),actualBinding.get(key))) throw new IllegalStateException("fresh closure current actor differs");
        JSONObject binding=body.getJSONObject("original_binding");
        if(binding.getString("kind").equals("operator")) {
            JSONObject original=EmbeddedDuplexOriginals.forApplication(EmbeddedDuplexProcessHost.application()).requireTerminalUnknown(binding.getString("original_id"));
            JSONObject actor=original.getJSONObject("actor_binding");
            if(!EmbeddedDuplexCanonicalJson.sameValue(actor.get("native_generation"),body.get("native_executor_generation"))
                    || !actor.get("app_record_sha256").equals(body.get("app_record_sha256")))
                throw new IllegalStateException("fresh closure original actor differs");
        }else EmbeddedDuplexProcessHost.requireIncomingFreshRetirementBinding(binding.getString("native_request_id"));
        String prior=load();
        if(!prior.equals("{}")) {
            if(!prior.equals(exact)) throw new IllegalStateException("fresh closure already retained with different bytes");
        } else {
            File temporary=new File(directory,"pending-authority-closure.tmp");
            if(!temporary.createNewFile()) throw new IllegalStateException("fresh closure pending write");
            Os.chmod(temporary.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(temporary)) {
                output.write(exact.getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);
            java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
            try { Os.fsync(fd); } finally { Os.close(fd); }
        }
        JSONObject ack=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_setup_closure_retained.v1")
            .put("body_sha256","sha256:"+EmbeddedDuplexProcessFence.digest(exact))
            .put("storage_identity","app-no-backup:fresh-closure-v1:pending-authority-closure")
            .put("original_binding",binding);
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})
            ack.put(key,body.get(key));
        return ack.toString();
    }
    private static void validate(JSONObject body) throws Exception {
        java.util.HashSet<String> keys=new java.util.HashSet<>();body.keys().forEachRemaining(keys::add);
        if(!keys.equals(new java.util.HashSet<>(java.util.Arrays.asList("$schema","app_process_generation","app_record_sha256",
                "native_executor_generation","config_sha256","original_binding","original_state","transaction_outcome","pair_state",
                "authority_snapshot","native_eligibility","physical_retirement","future_writable_restore")))
                || !body.getString("$schema").equals("rusty.quest.embedded_duplex.fresh_setup_retirement_closure.v1")
                || !body.getString("original_state").equals("UNKNOWN") || !body.getString("transaction_outcome").equals("unknown")
                || !body.getString("future_writable_restore").equals("fenced_pending_supported_owner_reconciliation")
                || body.getLong("app_process_generation")<=0 || body.getLong("native_executor_generation")<=0
                || !body.getString("app_record_sha256").matches("[0-9a-f]{64}")
                || !body.getString("config_sha256").matches("[0-9a-f]{64}"))
            throw new IllegalStateException("fresh closure schema/identity");
        JSONObject binding=body.getJSONObject("original_binding"); java.util.HashSet<String> fields=new java.util.HashSet<>();
        binding.keys().forEachRemaining(fields::add);
        if(binding.getString("kind").equals("operator")) {
            if(!fields.equals(new java.util.HashSet<>(java.util.Arrays.asList("kind","original_id")))
                    || !binding.getString("original_id").matches("[0-9a-f]{32}")) throw new IllegalStateException("fresh operator binding");
        } else if(binding.getString("kind").equals("incoming_fresh_setup")) {
            if(!fields.equals(new java.util.HashSet<>(java.util.Arrays.asList("kind","native_request_id")))
                    || !binding.getString("native_request_id").matches("[a-z][a-z0-9_.-]{0,255}"))
                throw new IllegalStateException("fresh incoming request binding");
        } else throw new IllegalStateException("fresh original binding kind");
        body.getJSONObject("pair_state");body.getJSONObject("authority_snapshot");
        body.getJSONObject("native_eligibility");body.getJSONObject("physical_retirement");
    }
}
