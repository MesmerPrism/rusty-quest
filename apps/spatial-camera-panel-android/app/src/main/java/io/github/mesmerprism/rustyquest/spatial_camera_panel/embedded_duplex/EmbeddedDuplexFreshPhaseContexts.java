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

/** Immutable context custody, not a signing or native authority API. */
final class EmbeddedDuplexFreshPhaseContexts {
    private final File directory;
    EmbeddedDuplexFreshPhaseContexts(Context context)throws Exception{
        directory=EmbeddedDuplexFreshClosure.activeDirectory(context.getNoBackupFilesDir(),"embedded-duplex-fresh-contexts-v1");
        if(!directory.exists() && !directory.mkdir())throw new IllegalStateException("phase contexts directory create");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("phase contexts directory type");
        Os.chmod(directory.getAbsolutePath(),0700);sync(directory.getParentFile());
    }
    synchronized void bindOperator(String nativeId,String phaseId,JSONObject binding,JSONObject actor)throws Exception{
        EmbeddedDuplexIncomingFreshPhases.id(nativeId);EmbeddedDuplexIncomingFreshPhases.id(phaseId);
        EmbeddedDuplexFreshHandoffStore.keys(binding,"kind","original_id");
        if(!"operator".equals(binding.getString("kind")) || !binding.getString("original_id").matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("phase context operator binding");
        JSONObject value=new JSONObject().put("$schema","rusty.quest.embedded_duplex.operator_phase_custody.v1")
            .put("native_request_id",nativeId).put("phase_request_id",phaseId).put("original_binding",binding);
        actor(value,actor);
        retain("operator-"+binding.getString("original_id"),value);
    }
    synchronized void requireOperator(String nativeId,String phaseId,JSONObject binding,JSONObject actor)throws Exception{
        JSONObject actual=read("operator-"+binding.getString("original_id"));
        JSONObject expected=new JSONObject().put("$schema","rusty.quest.embedded_duplex.operator_phase_custody.v1")
            .put("native_request_id",nativeId).put("phase_request_id",phaseId).put("original_binding",binding);
        actor(expected,actor);
        if(!EmbeddedDuplexCanonicalJson.encode(actual).equals(EmbeddedDuplexCanonicalJson.encode(expected)))
            throw new IllegalStateException("actual fsynced operator phase differs");
    }
    synchronized JSONObject retainContext(String nativeId,String phaseId,String transaction,String kind,String digest,
            JSONObject binding,JSONObject actor)throws Exception{
        EmbeddedDuplexIncomingFreshPhases.id(nativeId);EmbeddedDuplexIncomingFreshPhases.id(phaseId);
        EmbeddedDuplexIncomingFreshPhases.id(transaction);
        if(!java.util.Arrays.asList("enrollment_preparation","pair_setup").contains(kind)
                || !digest.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("actual phase context kind/hash");
        JSONObject row=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_phase_operation_binding.v1")
            .put("native_request_id",nativeId).put("phase_request_id",phaseId).put("manifold_transaction_id",transaction)
            .put("kind",kind).put("context_sha256",digest).put("original_binding",binding);
        actor(row,actor);
        // Identity excludes the digest: changing the context of the same actual
        // transaction is rejected instead of silently creating another row.
        retain("context-"+key(row),row);return new JSONObject(row.toString());
    }
    synchronized JSONObject requireContext(JSONObject supplied)throws Exception{
        EmbeddedDuplexFreshHandoffStore.keys(supplied,"$schema","native_request_id","phase_request_id","manifold_transaction_id",
            "kind","context_sha256","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","original_binding");
        if(!"rusty.quest.embedded_duplex.fresh_phase_operation_binding.v1".equals(supplied.getString("$schema")))
            throw new IllegalArgumentException("operation context schema");
        JSONObject actual=read("context-"+key(supplied));
        if(!EmbeddedDuplexCanonicalJson.encode(actual).equals(EmbeddedDuplexCanonicalJson.encode(supplied)))
            throw new IllegalStateException("operation context differs from fsynced producer row");
        return actual;
    }
    private static String key(JSONObject row)throws Exception{
        JSONObject identity=new JSONObject();
        for(String key:new String[]{"native_request_id","phase_request_id","manifold_transaction_id","kind","original_binding"})identity.put(key,row.get(key));
        return EmbeddedDuplexCanonicalJson.sha256(identity);
    }
    private static void actor(JSONObject row,JSONObject actual)throws Exception{
        if(actual.getLong("app_process_generation")<=0 || actual.getLong("native_executor_generation")<=0
                || !actual.getString("app_record_sha256").matches("[0-9a-f]{64}") || !actual.getString("config_sha256").matches("[0-9a-f]{64}"))
            throw new IllegalStateException("phase context actual actor absent");
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"})row.put(key,actual.get(key));
    }
    private JSONObject read(String key)throws Exception{
        File file=new File(directory,key+".json");
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("phase context retained source missing");
        byte[] bytes=Files.readAllBytes(file.toPath());JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!java.util.Arrays.equals(bytes,EmbeddedDuplexCanonicalJson.encode(value).getBytes(StandardCharsets.UTF_8)))
            throw new IllegalStateException("phase context retained bytes changed");
        return value;
    }
    private void retain(String key,JSONObject row)throws Exception{
        byte[] bytes=EmbeddedDuplexCanonicalJson.encode(row).getBytes(StandardCharsets.UTF_8);File target=new File(directory,key+".json");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS) || !java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))
                throw new IllegalStateException("phase context immutable identity rebind");
        }else{
            File tmp=new File(directory,key+".tmp");if(!tmp.createNewFile())throw new IllegalStateException("phase context pending write");
            Os.chmod(tmp.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(tmp)){output.write(bytes);output.getFD().sync();}
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(directory);
            if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("phase context durable readback differs");
        }
        java.io.FileDescriptor fd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}sync(directory);
        if(!java.util.Arrays.equals(bytes,Files.readAllBytes(target.toPath())))throw new IllegalStateException("context final durable bytes differ");
    }
    private static void sync(File directory)throws Exception{
        java.io.FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
}
