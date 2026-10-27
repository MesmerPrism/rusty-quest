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
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed Native callback custody writer. Never calls a runtime/Manifold owner. */
final class EmbeddedDuplexManifoldFreshArchive {
    static final class PreparedCustody {
        private final byte[] signing;
        private PreparedCustody(JSONObject context)throws Exception{
            byte[] prefix="rusty.manifold.peer.fresh_history_durable_custody.v1\0".getBytes(StandardCharsets.UTF_8);
            byte[] body=EmbeddedDuplexCanonicalJson.encode(context).getBytes(StandardCharsets.UTF_8);
            signing=new byte[prefix.length+body.length];System.arraycopy(prefix,0,signing,0,prefix.length);System.arraycopy(body,0,signing,prefix.length,body.length);
        }
        byte[] signingBytes(){return signing.clone();}
    }
    private final File directory;
    EmbeddedDuplexManifoldFreshArchive(Context app)throws Exception{
        directory=EmbeddedDuplexFreshClosure.activeDirectory(EmbeddedDuplexProcessHost.operationalRoot(app),"embedded-duplex-manifold-fresh-history-v1");
        if(!directory.exists() && !directory.mkdir())throw new IllegalStateException("Manifold custody directory create");
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Manifold custody directory type");
        Os.chmod(directory.getAbsolutePath(),0700);sync(directory.getParentFile());
    }
    synchronized String retain(String exact,JSONObject operation,JSONObject nativeArchive,
            EmbeddedDuplexIdentity.Identity selectedKey)throws Exception{
        JSONObject body=new JSONObject(exact);
        keys(body,"$schema","host_id","provider_epoch_id","local_peer_id","trust_policy_id","trust_policy_revision","policy","selection",
            "archive_ordinal","source_snapshot_sha256","operation_binding","prior_cursor","prime_origin","prime_enrollments","receipts",
            "phase_preparations","floor_checkpoint","credential_checkpoint","enrollment_authority_revision");
        if(!"rusty.manifold.peer.fresh_history_archive_body.v1".equals(body.getString("$schema"))
                || !exact.equals(EmbeddedDuplexCanonicalJson.encode(body)) || !same(body.getJSONObject("operation_binding"),operation))
            throw new IllegalArgumentException("Manifold custody exact canonical plan/operation");
        JSONObject snapshot=nativeArchive.getJSONObject("authority_snapshot");
        if(!digest("rusty.manifold.peer.fresh_history_archive_source.v1",snapshot).equals(body.getString("source_snapshot_sha256")))
            throw new IllegalStateException("Manifold custody actual Native source snapshot differs");
        JSONObject trust=snapshot.getJSONObject("trust_policy"),state=snapshot.getJSONObject("fresh_pair_setup"),enrollment=snapshot.getJSONObject("enrollment");
        equal(body,"host_id",snapshot,"host_id");equal(body,"provider_epoch_id",snapshot,"provider_epoch_id");
        equal(body,"local_peer_id",trust,"fresh_setup_local_peer_id");equal(body,"trust_policy_id",trust,"policy_id");equal(body,"trust_policy_revision",trust,"revision");
        equal(body,"policy",trust,"fresh_setup_policy");equal(body,"selection",trust,"fresh_setup_enrollment_selection");
        equal(body,"prior_cursor",state,"archive");equal(body,"prime_origin",state,"prime_origin");equal(body,"prime_enrollments",state,"prime_enrollments");
        equal(body,"receipts",state,"receipts");equal(body,"phase_preparations",state,"phase_preparations");
        equal(body,"floor_checkpoint",snapshot.getJSONObject("reciprocal_ed25519"),"fresh_setup_fences");
        equal(body,"enrollment_authority_revision",enrollment,"authority_revision");
        JSONArray selected=body.getJSONObject("selection").getJSONArray("keys"),credentials=enrollment.getJSONArray("credentials"),filtered=new JSONArray();
        JSONObject signingKey=null;
        for(int i=0;i<selected.length();i++){
            JSONObject key=selected.getJSONObject(i);
            if(key.getString("peer_id").equals(body.getString("local_peer_id"))){
                if(signingKey!=null)throw new IllegalStateException("Manifold custody duplicate local key");signingKey=key;
            }
        }
        if(signingKey==null || !selectedKey.keyId().equals(signingKey.getString("key_id"))
                || !hex(selectedKey.rawPublicKey()).equals(signingKey.getString("public_key_hex")))
            throw new IllegalStateException("Manifold custody actual selected signing key differs");
        for(int i=0;i<credentials.length();i++){
            JSONObject credential=credentials.getJSONObject(i);boolean keep=false;
            for(int j=0;j<selected.length();j++)if(credential.getString("key_id").equals(selected.getJSONObject(j).getString("key_id")))keep=true;
            if(keep)filtered.put(credential);
        }
        if(!same(filtered,body.getJSONArray("credential_checkpoint")))throw new IllegalStateException("Manifold custody credential checkpoint differs");
        long ordinal=positive(body,"archive_ordinal");JSONObject prior=body.isNull("prior_cursor")?null:body.getJSONObject("prior_cursor");
        long priorTotal=prior==null?0:positive(prior,"total");
        if(ordinal!=Math.addExact(priorTotal,1))throw new IllegalStateException("Manifold custody exact next ordinal differs");
        String bodyHash="sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(exact.getBytes(StandardCharsets.UTF_8));
        Object priorHistory=prior==null?JSONObject.NULL:prior.getString("history_sha256");
        String history=digest("rusty.manifold.peer.fresh_history_chain.v1",new JSONObject().put("prior_history_sha256",priorHistory)
            .put("archive_ordinal",ordinal).put("archive_body_sha256",bodyHash));
        JSONObject checkpoint=new JSONObject().put("total",ordinal)
            .put("setup_receipts",Math.addExact(prior==null?0:nonnegative(prior,"setup_receipts"),body.getJSONArray("receipts").length()))
            .put("phase_receipts",Math.addExact(prior==null?0:nonnegative(prior,"phase_receipts"),body.getJSONArray("phase_preparations").length()))
            .put("first_body_sha256",prior==null?bodyHash:prior.getString("first_body_sha256"))
            .put("last_body_sha256",bodyHash).put("history_sha256",history).put("floor_checkpoint",body.getJSONArray("floor_checkpoint"))
            .put("credential_checkpoint",body.getJSONArray("credential_checkpoint")).put("enrollment_authority_revision",body.get("enrollment_authority_revision"));
        String namespace=EmbeddedDuplexCanonicalJson.sha256(new JSONObject().put("host_id",body.get("host_id"))
            .put("provider_epoch_id",body.get("provider_epoch_id")).put("local_peer_id",body.get("local_peer_id")));
        String storage="app-no-backup:manifold-fresh-history-v1:"+namespace+":"+ordinal+":"+bodyHash.substring(7);
        // The ordinal index is immutable: even a self-consistent different body
        // cannot silently replace an already retained source-owner checkpoint.
        retainExact(namespace+"-"+ordinal+".body.json",exact.getBytes(StandardCharsets.UTF_8));
        JSONObject context=new JSONObject().put("$schema","rusty.manifold.peer.fresh_history_durable_context.v1")
            .put("archive_ordinal",ordinal).put("archive_body_sha256",bodyHash).put("prior_history_sha256",priorHistory)
            .put("history_sha256",history).put("checkpoint_sha256",digest("rusty.manifold.peer.fresh_history_cursor.v1",checkpoint))
            .put("host_id",body.get("host_id")).put("provider_epoch_id",body.get("provider_epoch_id")).put("local_peer_id",body.get("local_peer_id"))
            .put("source_snapshot_sha256",body.get("source_snapshot_sha256")).put("operation_binding",operation)
            .put("storage_identity",storage).put("signer_key_id",selectedKey.keyId());
        PreparedCustody sealed=new PreparedCustody(context);
        byte[] signature=EmbeddedDuplexIdentity.signRetainedManifoldFreshHistory(selectedKey,sealed);
        if(signature==null || signature.length!=64)throw new IllegalStateException("Manifold custody signature absent");
        JSONObject ack=new JSONObject().put("$schema","rusty.manifold.peer.fresh_history_durable_ack.v1")
            .put("context",context).put("signature_hex",hex(signature));
        String result=EmbeddedDuplexCanonicalJson.encode(ack);retainExact(namespace+"-"+ordinal+".ack.json",result.getBytes(StandardCharsets.UTF_8));return result;
    }
    private void retainExact(String name,byte[] raw)throws Exception{
        File target=new File(directory,name);
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS)||!java.util.Arrays.equals(raw,Files.readAllBytes(target.toPath())))
                throw new IllegalStateException("Manifold custody immutable retained bytes differ");
        }else{
            File tmp=new File(directory,name+".tmp");if(!tmp.createNewFile())throw new IllegalStateException("Manifold custody pending write");Os.chmod(tmp.getAbsolutePath(),0600);
            try(FileOutputStream output=new FileOutputStream(tmp)){output.write(raw);output.getFD().sync();}
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(directory);
            if(!java.util.Arrays.equals(raw,Files.readAllBytes(target.toPath())))throw new IllegalStateException("Manifold custody durable readback differs");
        }
        // A previous move may have succeeded before its directory fsync failed.
        // Exact-existing bytes alone do not earn a new durable custody ACK.
        java.io.FileDescriptor fd=Os.open(target.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}sync(directory);
        if(!java.util.Arrays.equals(raw,Files.readAllBytes(target.toPath())))throw new IllegalStateException("Manifold custody final durable bytes differ");
    }
    static String digest(String domain,Object value)throws Exception{
        byte[] prefix=(domain+"\0").getBytes(StandardCharsets.UTF_8),body=EmbeddedDuplexCanonicalJson.encode(value).getBytes(StandardCharsets.UTF_8),all=new byte[prefix.length+body.length];
        System.arraycopy(prefix,0,all,0,prefix.length);System.arraycopy(body,0,all,prefix.length,body.length);return "sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(all);
    }
    private static long nonnegative(JSONObject v,String key)throws Exception{Object n=v.get(key);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<0)throw new IllegalArgumentException("Manifold custody integer");return ((Number)n).longValue();}
    private static long positive(JSONObject v,String key)throws Exception{long n=nonnegative(v,key);if(n==0)throw new IllegalArgumentException("Manifold custody positive counter");return n;}
    private static void equal(JSONObject a,String ak,JSONObject b,String bk)throws Exception{if(!same(a.get(ak),b.get(bk)))throw new IllegalStateException("Manifold custody source field differs: "+ak);}
    private static boolean same(Object a,Object b)throws Exception{return EmbeddedDuplexCanonicalJson.encode(a).equals(EmbeddedDuplexCanonicalJson.encode(b));}
    private static void keys(JSONObject v,String... keys){EmbeddedDuplexFreshHandoffStore.keys(v,keys);}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return s.toString();}
    private static void sync(File d)throws Exception{java.io.FileDescriptor fd=Os.open(d.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);try{Os.fsync(fd);}finally{Os.close(fd);}}
}
