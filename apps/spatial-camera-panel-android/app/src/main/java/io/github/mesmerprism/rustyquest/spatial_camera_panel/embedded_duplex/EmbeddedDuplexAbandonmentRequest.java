package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Closed shell-authorized disposition. External absence is attested, not inferred by this app. */
public final class EmbeddedDuplexAbandonmentRequest {
    private static final Set<String> FIELDS=Set.of("$schema","role_id","package_id","component_id",
            "installed_apk_sha256","signer_sha256","physical_serial","boot_id","user_id","package_uid",
            "old_pid","old_start_ticks","process_absence_evidence_sha256","expected_predecessor_sha256",
            "expected_current_fence_sha256","authorize_abandonment");
    private final String exact;
    public EmbeddedDuplexAbandonmentRequest(String exact) throws Exception {
        if(exact==null || exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24*1024)
            throw new IllegalArgumentException("abandonment request bounds");
        JSONObject object=new JSONObject(exact);Set<String> keys=new HashSet<>();for(java.util.Iterator<String> iterator=object.keys();iterator.hasNext();) keys.add(iterator.next());
        if(EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(object.optString("$schema"))) {
            EmbeddedDuplexHistoricalAbandonment.shape(object);this.exact=exact;return;
        }
        if(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)
            throw new IllegalArgumentException("v1 abandonment request bounds");
        if(!keys.equals(FIELDS) || !"rusty.quest.embedded_duplex.abandonment_authorization.v1".equals(object.getString("$schema"))
                || !Boolean.TRUE.equals(object.get("authorize_abandonment"))
                || !Set.of("peer_a","peer_b").contains(object.getString("role_id")))
            throw new IllegalArgumentException("abandonment authorization shape");
        for(String field:Set.of("installed_apk_sha256","signer_sha256","process_absence_evidence_sha256",
                "expected_predecessor_sha256","expected_current_fence_sha256"))
            if(!(object.get(field) instanceof String) || !object.getString(field).matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("abandonment digest invalid");
        if(!object.getString("package_id").matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")
                || !object.getString("component_id").matches("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z][A-Za-z0-9_.]*")
                || !object.getString("physical_serial").matches("[A-Za-z0-9_-]{1,128}")
                || !object.getString("boot_id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("abandonment identity invalid");
        for(String field:Set.of("user_id","package_uid","old_pid","old_start_ticks")) {
            Object value=object.get(field);
            if(!(value instanceof Long || value instanceof Integer) || object.getLong(field)<(field.equals("user_id")?0:1))
                throw new IllegalArgumentException("abandonment number invalid");
        }
        if(object.getLong("old_pid")>Integer.MAX_VALUE || object.getLong("package_uid")>Integer.MAX_VALUE
                || object.getLong("package_uid")/100000!=object.getLong("user_id")
                || object.getLong("package_uid")%100000<10000)
            throw new IllegalArgumentException("abandonment UID invalid");
        this.exact=exact;
    }
    JSONObject json() throws Exception { return new JSONObject(exact); }
    String role() throws Exception { return json().getString("role_id"); }
    boolean historical() throws Exception {return EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(json().getString("$schema"));}
    void requireCurrent(int pid,int uid,long ticks,long generation) throws Exception {
        if(historical()) EmbeddedDuplexHistoricalAbandonment.requireCurrent(json(),pid,uid,ticks,generation);
    }
    void bind(String prior,String current,String checkpoint,String evidence) throws Exception {
        if(historical()) EmbeddedDuplexHistoricalAbandonment.bind(json(),prior,current,checkpoint,evidence);
    }
    void requireInstalled(String packageId,String signer,String apk,String component,int uid,int pid) throws Exception {
        JSONObject value=json();
        if(!packageId.equals(value.getString("package_id")) || !signer.equals(value.getString("signer_sha256"))
                || !apk.equals(value.getString("installed_apk_sha256")) || !component.equals(value.getString("component_id"))
                || uid!=value.getInt("package_uid") || (!historical() && pid==value.getInt("old_pid")))
            throw new IllegalStateException("abandonment selected installed identity differs");
    }
}
