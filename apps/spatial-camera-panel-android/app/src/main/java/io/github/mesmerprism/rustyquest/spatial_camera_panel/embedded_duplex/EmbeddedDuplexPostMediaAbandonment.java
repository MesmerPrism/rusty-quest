package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Separate post-media nonaccepting scope. Absence is explicit operator attestation,
 * never physical-cleanup evidence. Recorded fences are not reconstructed from ARM. */
final class EmbeddedDuplexPostMediaAbandonment {
    static final String SCHEMA="rusty.quest.embedded_duplex.abandonment_authorization.v7";
    static final Set<String> RECORDS=Set.of("owner-dispatch.v1.json","product-activation.v1.json",
        "retained-cleanup-preparations.v1.json","native-process-fence.v1.lock","retained-cleanup.v2.json");
    private static final Set<String> ROOT=Set.of("$schema","scope_kind","role_id","package_id","component_id",
        "installed_apk_sha256","signer_sha256","physical_serial","boot_id","user_id","package_uid",
        "expected_predecessor_sha256","expected_current_fence_sha256","authorize_abandonment",
        "current_recovery_actor","historical_peers","process_absence_evidence_sha256");
    private static final Set<String> PEER=Set.of("package_id","installed_apk_sha256","signer_sha256",
        "physical_serial","boot_id","user_id","package_uid","original_pid","original_start_ticks",
        "original_birth_status","original_birth_receipt_json","original_birth_receipt_sha256",
        "original_fence_record","original_fence_sha256","checkpoint_sha256","evidence_sha256",
        "absence_scope","complete_absence_confirmed","preserved_records");
    static boolean supported(JSONObject auth){return SCHEMA.equals(auth.optString("$schema"));}
    static void shape(JSONObject auth)throws Exception{
        keys(auth,ROOT);
        if(!supported(auth)||!"post_media_nonaccepting".equals(auth.getString("scope_kind"))
                ||!Boolean.TRUE.equals(auth.get("authorize_abandonment"))
                ||!Set.of("peer_a","peer_b").contains(auth.getString("role_id")))deny("authorization shape");
        installed(auth);
        if(!auth.getString("component_id").matches("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z][A-Za-z0-9_.]*"))deny("component shape");
        for(String key:Set.of("expected_predecessor_sha256","expected_current_fence_sha256","process_absence_evidence_sha256"))sha(auth.getString(key));
        JSONObject current=auth.getJSONObject("current_recovery_actor");
        keys(current,Set.of("pid","start_ticks","app_process_generation"));
        for(String key:Set.of("pid","start_ticks","app_process_generation"))positive(current,key);
        if(current.getLong("pid")>Integer.MAX_VALUE)deny("current PID range");
        JSONObject peers=auth.getJSONObject("historical_peers");keys(peers,Set.of("peer_a","peer_b"));
        for(String role:Set.of("peer_a","peer_b"))peer(peers.getJSONObject(role));
        JSONObject a=peers.getJSONObject("peer_a"),b=peers.getJSONObject("peer_b");
        if(a.getString("physical_serial").equals(b.getString("physical_serial")))deny("peer device collision");
        for(String key:Set.of("package_id","installed_apk_sha256","signer_sha256"))
            if(!a.getString(key).equals(b.getString(key)))deny("old pair installed closure differs");
        JSONObject selected=peers.getJSONObject(auth.getString("role_id"));
        for(String key:Set.of("package_id","signer_sha256","physical_serial","boot_id","user_id","package_uid"))
            if(!auth.get(key).toString().equals(selected.get(key).toString()))deny("selected peer differs");
        if(current.getLong("start_ticks")<=selected.getLong("original_start_ticks"))deny("same-boot successor birth not later");
        if(!auth.getString("expected_predecessor_sha256").equals(selected.getString("original_fence_sha256")))deny("selected predecessor differs");
    }
    static void requireCurrent(JSONObject auth,int pid,int uid,long ticks,long generation)throws Exception{
        JSONObject current=auth.getJSONObject("current_recovery_actor");
        if(current.getLong("pid")!=pid||auth.getLong("package_uid")!=uid
                ||current.getLong("start_ticks")!=ticks||current.getLong("app_process_generation")!=generation)
            deny("current recovery incarnation differs");
    }
    static void requireBoot(JSONObject auth,String boot)throws Exception{
        if(!auth.getString("boot_id").equals(boot))deny("same recovery boot differs");
    }
    static void bind(JSONObject auth,String prior,String current,String checkpoint,String evidence)throws Exception{
        shape(auth);JSONObject selected=auth.getJSONObject("historical_peers").getJSONObject(auth.getString("role_id"));
        if(!prior.equals(selected.getString("original_fence_record"))
                ||!EmbeddedDuplexProcessFence.digest(current).equals(auth.getString("expected_current_fence_sha256")))deny("recorded fence chain differs");
        String[] old=EmbeddedDuplexOriginalScopeDisposition.fence(prior),next=EmbeddedDuplexOriginalScopeDisposition.fence(current);
        if(!"pending".equals(next[3])||Long.parseLong(next[1])!=Math.addExact(Long.parseLong(old[1]),1)
                ||Long.parseLong(next[1])!=auth.getJSONObject("current_recovery_actor").getLong("app_process_generation")
                ||old[2].equals(next[2]))deny("recovery successor differs");
        String[] journals={EmbeddedDuplexProcessFence.digest(checkpoint),EmbeddedDuplexProcessFence.digest(evidence)};
        for(int i=0;i<journals.length;i++)
            if(!journals[i].equals(old[4+i])||!journals[i].equals(next[4+i]))deny("recorded journal bytes differ");
    }
    private static void peer(JSONObject peer)throws Exception{
        keys(peer,PEER);installed(peer);positive(peer,"original_pid");positive(peer,"original_start_ticks");
        if(peer.getLong("original_pid")>Integer.MAX_VALUE||!"known_birth_recorded".equals(peer.getString("original_birth_status"))
                ||!"complete_package_uid_scope".equals(peer.getString("absence_scope"))
                ||!Boolean.TRUE.equals(peer.get("complete_absence_confirmed")))deny("complete known-birth absence required");
        JSONObject records=peer.getJSONObject("preserved_records");keys(records,RECORDS);
        for(String name:RECORDS){
            if("retained-cleanup.v2.json".equals(name)&&records.get(name)==JSONObject.NULL)continue;
            sha(records.getString(name));
        }
        String prior=peer.getString("original_fence_record");String[] fence=EmbeddedDuplexOriginalScopeDisposition.fence(prior);
        sha(peer.getString("original_fence_sha256"));
        if(!"pending".equals(fence[3])||!EmbeddedDuplexProcessFence.digest(prior).equals(peer.getString("original_fence_sha256")))deny("recorded pending fence differs");
        for(int i=0;i<2;i++){
            String value=peer.getString(i==0?"checkpoint_sha256":"evidence_sha256");
            if(!value.equals("-"))sha(value);
            if(!value.equals(fence[4+i]))deny("recorded fence journal digest differs");
        }
        String exact=peer.getString("original_birth_receipt_json");sha(peer.getString("original_birth_receipt_sha256"));
        if(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096
                ||!EmbeddedDuplexProcessFence.digest(exact).equals(peer.getString("original_birth_receipt_sha256")))deny("recorded birth bytes differ");
        JSONObject birth=new JSONObject(exact);
        keys(birth,Set.of("schema","pid","start_ticks","uid","app_process_generation","process_epoch_id","physical_serial","boot_id","installed_apk_sha256"));
        for(String key:Set.of("pid","start_ticks","uid","app_process_generation"))positive(birth,key);
        if(!"rusty.quest.embedded_duplex.known_actor_birth.v1".equals(birth.getString("schema"))
                ||birth.getLong("pid")!=peer.getLong("original_pid")||birth.getLong("start_ticks")!=peer.getLong("original_start_ticks")
                ||birth.getLong("uid")!=peer.getLong("package_uid")||birth.getLong("app_process_generation")!=Long.parseLong(fence[1])
                ||!birth.getString("process_epoch_id").equals("app-process."+fence[1]+"."+fence[2]))deny("recorded birth/fence differs");
        for(String key:Set.of("physical_serial","boot_id","installed_apk_sha256"))
            if(!birth.getString(key).equals(peer.getString(key)))deny("recorded birth installed scope differs");
    }
    private static void installed(JSONObject value)throws Exception{
        if(!value.getString("package_id").matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")
                ||!value.getString("physical_serial").matches("[A-Za-z0-9_-]{1,128}")
                ||!value.getString("boot_id").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))deny("installed identity shape");
        sha(value.getString("installed_apk_sha256"));sha(value.getString("signer_sha256"));
        Object user=value.get("user_id");if(!(user instanceof Integer||user instanceof Long)||value.getLong("user_id")<0)deny("user identity");
        positive(value,"package_uid");long uid=value.getLong("package_uid");
        if(uid>Integer.MAX_VALUE||uid/100000!=value.getLong("user_id")||uid%100000<10000)deny("installed UID");
    }
    private static void keys(JSONObject value,Set<String> expected){
        Set<String> actual=new HashSet<>();for(java.util.Iterator<String> i=value.keys();i.hasNext();)actual.add(i.next());
        if(!actual.equals(expected))deny("unknown or missing fields");
    }
    private static void positive(JSONObject value,String key)throws Exception{
        Object number=value.get(key);if(!(number instanceof Integer||number instanceof Long)||value.getLong(key)<=0)deny("positive integer identity");
    }
    private static void sha(String value){if(!value.matches("[0-9a-f]{64}"))deny("digest shape");}
    private static void deny(String why){throw new IllegalArgumentException("post-media abandonment: "+why);}
}
