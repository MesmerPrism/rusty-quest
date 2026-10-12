package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Set;
import java.util.HashSet;

/** Closed known-birth no-journal disposition. Complete package absence remains operator-attested.
 * Original live actor evidence and the next recovery fence are separate exact scopes. */
final class EmbeddedDuplexKnownBirthAbandonment {
    static final String SCHEMA="rusty.quest.embedded_duplex.abandonment_authorization.v3";
    private static final Set<String> ROOT=Set.of("$schema","role_id","package_id","component_id",
        "installed_apk_sha256","signer_sha256","physical_serial","boot_id","user_id","package_uid",
        "expected_predecessor_sha256","expected_current_fence_sha256","authorize_abandonment",
        "current_recovery_actor","historical_peers","process_absence_evidence_sha256");
    private static final Set<String> PEER=Set.of("package_id","installed_apk_sha256","signer_sha256",
        "physical_serial","boot_id","user_id","package_uid","original_pid","original_start_ticks",
        "original_birth_status","original_actor_receipt_json","original_actor_receipt_sha256",
        "original_birth_receipt_json","original_birth_receipt_sha256","original_fence_record","original_fence_sha256",
        "absence_scope","complete_absence_confirmed","preserved_records");
    static final Set<String> RECORDS=Set.of("owner-dispatch.v1.json","product-activation.v1.json",
        "retained-cleanup-preparations.v1.json","native-process-fence.v1.lock");
    private static final Set<String> ARM=Set.of("schema","action","challenge","process_epoch_id",
        "arm_generation","native_process_generation","runtime_config_sha256","feature_lock_sha256",
        "apk_sha256","native_snapshot");
    static void shape(JSONObject auth) throws Exception {
        keys(auth,ROOT);
        if(!SCHEMA.equals(auth.getString("$schema")) || !Boolean.TRUE.equals(auth.get("authorize_abandonment"))
            || !Set.of("peer_a","peer_b").contains(auth.getString("role_id"))) deny("authorization shape");
        for(String key:Set.of("installed_apk_sha256","signer_sha256","expected_predecessor_sha256",
            "expected_current_fence_sha256","process_absence_evidence_sha256")) sha(auth.getString(key));
        installedShape(auth);
        JSONObject current=auth.getJSONObject("current_recovery_actor");
        keys(current,Set.of("pid","start_ticks","app_process_generation"));
        for(String key:Set.of("pid","start_ticks","app_process_generation")) positive(current,key);
        if(current.getLong("pid")>Integer.MAX_VALUE) deny("current PID range");
        JSONObject peers=auth.getJSONObject("historical_peers");keys(peers,Set.of("peer_a","peer_b"));
        for(String role:Set.of("peer_a","peer_b")) peer(peers.getJSONObject(role));
        JSONObject a=peers.getJSONObject("peer_a"),b=peers.getJSONObject("peer_b");
        if(a.getString("physical_serial").equals(b.getString("physical_serial"))) deny("peer device collision");
        for(String key:Set.of("package_id","signer_sha256","installed_apk_sha256"))
            if(!a.getString(key).equals(b.getString(key))) deny("historical pair closure differs");
        if(!new JSONObject(a.getString("original_actor_receipt_json")).getString("feature_lock_sha256")
            .equals(new JSONObject(b.getString("original_actor_receipt_json")).getString("feature_lock_sha256"))) deny("historical pair feature closure differs");
        JSONObject selected=peers.getJSONObject(auth.getString("role_id"));
        for(String key:Set.of("package_id","signer_sha256","physical_serial","boot_id","user_id","package_uid"))
            if(!auth.get(key).toString().equals(selected.get(key).toString())) deny("selected historical peer differs");
        // Upgrade APK identity is intentionally distinct from the preserved original APK.
        if(current.getLong("start_ticks")<=selected.getLong("original_start_ticks"))
            deny("current recovery birth is not later on selected boot");
        if(!auth.getString("component_id").matches("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z][A-Za-z0-9_.]*")) deny("component shape");
    }
    static void requireCurrent(JSONObject auth,int pid,int uid,long ticks,long generation) throws Exception {
        JSONObject current=auth.getJSONObject("current_recovery_actor");
        if(current.getLong("pid")!=pid || auth.getLong("package_uid")!=uid
            || current.getLong("start_ticks")!=ticks || current.getLong("app_process_generation")!=generation)
            deny("current recovery incarnation differs");
    }
    static void bind(JSONObject auth,String prior,String current,String checkpoint,String evidence) throws Exception {
        shape(auth);
        // This bounded branch has no Start lineage to invent. Journal-bearing cases retain v1.
        if(checkpoint!=null || evidence!=null) deny("historical disposition requires absent Start journals");
        JSONObject selected=auth.getJSONObject("historical_peers").getJSONObject(auth.getString("role_id"));
        if(!prior.equals(selected.getString("original_fence_record"))
            || !EmbeddedDuplexProcessFence.digest(prior).equals(auth.getString("expected_predecessor_sha256"))
            || !EmbeddedDuplexProcessFence.digest(current).equals(auth.getString("expected_current_fence_sha256")))
            deny("selected fence chain differs");
        String[] now=fence(current);
        if(Long.parseLong(now[1])!=auth.getJSONObject("current_recovery_actor").getLong("app_process_generation")
            || Long.parseLong(now[1])!=Long.parseLong(fence(prior)[1])+1) deny("recovery successor generation differs");
    }
    private static void peer(JSONObject p) throws Exception {
        keys(p,PEER);installedShape(p);
        JSONObject records=p.getJSONObject("preserved_records");keys(records,RECORDS);
        for(String name:RECORDS) sha(records.getString(name));
        positive(p,"original_pid");
        if(p.getLong("original_pid")>Integer.MAX_VALUE) deny("original PID range");
        positive(p,"original_start_ticks");
        if(!"known_birth_recorded".equals(p.getString("original_birth_status"))
            || !"complete_package_uid_scope".equals(p.getString("absence_scope"))
            || !Boolean.TRUE.equals(p.get("complete_absence_confirmed"))) deny("historical absence scope incomplete");
        for(String key:Set.of("installed_apk_sha256","signer_sha256","original_actor_receipt_sha256",
            "original_birth_receipt_sha256","original_fence_sha256")) sha(p.getString(key));
        String exact=p.getString("original_actor_receipt_json");
        if(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192
            || !EmbeddedDuplexProcessFence.digest(exact).equals(p.getString("original_actor_receipt_sha256"))) deny("actor receipt bytes differ");
        JSONObject arm=new JSONObject(exact);keys(arm,ARM);
        if(!"rusty.quest.stereo.concurrent_qualification_receipt.v1".equals(arm.getString("schema"))
            || !"arm".equals(arm.getString("action")) || !arm.getString("apk_sha256").equals(p.getString("installed_apk_sha256")))
            deny("original actor receipt differs");
        sha(arm.getString("runtime_config_sha256"));sha(arm.getString("feature_lock_sha256"));
        positive(arm,"arm_generation");positive(arm,"native_process_generation");
        String challenge=arm.getString("challenge");
        if(!challenge.matches("[0-9a-f]{32}") || challenge.equals("0".repeat(32))) deny("actor challenge invalid");
        JSONArray nativeWords=arm.getJSONArray("native_snapshot");
        if(nativeWords.length()!=160) deny("actor native snapshot length");
        for(int i=0;i<160;i++) integer(nativeWords.get(i));
        if(nativeWords.getLong(0)!=1 || nativeWords.getLong(1)!=160 || nativeWords.getLong(2)!=arm.getLong("native_process_generation")
            || nativeWords.getLong(3)!=Long.parseUnsignedLong(challenge.substring(0,16),16)
            || nativeWords.getLong(4)!=Long.parseUnsignedLong(challenge.substring(16),16)
            || nativeWords.getLong(5)!=arm.getLong("arm_generation") || nativeWords.getLong(6)!=1) deny("actor native arm join differs");
        String epoch=arm.getString("process_epoch_id");
        if(!epoch.matches("app-process\\.[1-9][0-9]{0,18}\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) deny("actor epoch invalid");
        String[] parts=epoch.split("\\.");long generation=Long.parseLong(parts[1]);
        String body="rusty.quest.embedded_duplex.app_process_fence.v1\n"+generation+"\n"+parts[2]+"\npending\n-\n-\n";
        // Explicit reconstruction from producer epoch and absent journals; never invented OS birth.
        String original=body+EmbeddedDuplexProcessFence.digest(body)+"\n";
        String originalFence=p.getString("original_fence_record");
        if(!original.equals(originalFence) || !EmbeddedDuplexProcessFence.digest(originalFence)
            .equals(p.getString("original_fence_sha256"))) deny("original pending fence bytes differ");
        String birthExact=p.getString("original_birth_receipt_json");
        if(birthExact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096
            || !EmbeddedDuplexProcessFence.digest(birthExact).equals(p.getString("original_birth_receipt_sha256")))
            deny("original birth receipt bytes differ");
        JSONObject birth=new JSONObject(birthExact);
        keys(birth,Set.of("schema","pid","start_ticks","uid","app_process_generation","process_epoch_id",
            "physical_serial","boot_id","installed_apk_sha256"));
        for(String key:Set.of("pid","start_ticks","uid","app_process_generation")) positive(birth,key);
        if(!"rusty.quest.embedded_duplex.known_actor_birth.v1".equals(birth.getString("schema"))
            || birth.getLong("pid")!=p.getLong("original_pid")
            || birth.getLong("start_ticks")!=p.getLong("original_start_ticks")
            || birth.getLong("uid")!=p.getLong("package_uid")
            || birth.getLong("app_process_generation")!=generation
            || !birth.getString("process_epoch_id").equals(epoch)) deny("original birth and ARM differ");
        for(String key:Set.of("physical_serial","boot_id","installed_apk_sha256"))
            if(!birth.getString(key).equals(p.getString(key))) deny("original birth installed scope differs");

    }
    private static String[] fence(String record) throws Exception {
        String[] f=record.split("\n",-1);
        if(record.length()>512 || f.length!=8 || !"rusty.quest.embedded_duplex.app_process_fence.v1".equals(f[0])
            || !f[1].matches("[1-9][0-9]{0,18}") || !f[2].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            || !"pending".equals(f[3]) || !"-".equals(f[4]) || !"-".equals(f[5]) || !f[7].isEmpty()
            || !EmbeddedDuplexProcessFence.digest(String.join("\n",java.util.Arrays.copyOf(f,6))+"\n").equals(f[6])) deny("historical fence invalid");
        return f;
    }
    private static void installedShape(JSONObject p) throws Exception {
        if(!p.getString("package_id").matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")
            || !p.getString("physical_serial").matches("[A-Za-z0-9_-]{1,128}")
            || !p.getString("boot_id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) deny("installed shape");
        integer(p.get("user_id"));positive(p,"package_uid");
        if(p.getLong("user_id")<0 || p.getLong("package_uid")>Integer.MAX_VALUE
            || p.getLong("package_uid")/100000!=p.getLong("user_id") || p.getLong("package_uid")%100000<10000) deny("installed UID");
    }
    private static void keys(JSONObject o,Set<String> expected) {
        Set<String> actual=new HashSet<>();for(java.util.Iterator<String> iterator=o.keys();iterator.hasNext();) actual.add(iterator.next());
        if(!actual.equals(expected)) deny("unknown or missing fields");
    }
    private static void integer(Object n) {if(!(n instanceof Integer || n instanceof Long)) deny("integer type");}
    private static void positive(JSONObject o,String key) throws Exception {integer(o.get(key));if(o.getLong(key)<=0) deny("positive identity");}
    private static void sha(String s) {if(!s.matches("[0-9a-f]{64}")) deny("digest shape");}
    private static void deny(String why) {throw new IllegalArgumentException("known-birth abandonment: "+why);}
}
