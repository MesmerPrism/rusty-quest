package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
import org.json.JSONArray;

/** Deliberate fresh-policy v4 projection. Existing v1/v3 consumers retain their own contracts. */
final class EmbeddedDuplexFreshLifecycle {
    static final String SCHEMA="rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4";
    static void require(JSONObject value)throws Exception{
        if(!SCHEMA.equals(value.getString("$schema")))throw new IllegalArgumentException("fresh lifecycle schema");
        java.util.HashSet<String> required=new java.util.HashSet<>(java.util.Arrays.asList(
            "$schema","action","config_sha256","native_executor_generation","app_process_generation","observed_at_ms",
            "status","authority_projection","broker_evidence","start_mutation","stop_mutation","route_receipt",
            "route_termination","route_cleanup","termination_action","revoker_adoption","media_completion",
            "media_stop_effect_receipt","owner_failure_diagnostic","owner_failure_diagnostic_status","owner_transport_attempt",
            "authority_clock_read","native_owner_dispatch_failure","activation","renewal_receipts","renewal_total_completed",
            "renewal_first_request_id","renewal_last_request_id","renewal_pending","peer_physical_cleanup",
            "native_host_physical_cleanup","whole_app_physical_cleanup","last_failure","last_failure_action",
            "ongoing_media_policy","current_lifetime_renewal_count","current_owner_validation","last_failure_cause","retired_lifetimes"));
        java.util.HashSet<String> allowed=new java.util.HashSet<>(required);
        allowed.addAll(java.util.Arrays.asList("continuation","remote_receiver_observation","java_own_capture_cleanup",
            "java_renderer_cleanup","java_control_cleanup","app_process_fence_cleanup"));
        java.util.HashSet<String> keys=new java.util.HashSet<>();value.keys().forEachRemaining(keys::add);
        if(!keys.containsAll(required) || !allowed.containsAll(keys))throw new IllegalArgumentException("fresh lifecycle closed fields");
        JSONObject policy=value.getJSONObject("ongoing_media_policy");
        if(policy.length()!=1 || !"rusty.manifold.peer.ongoing_media_policy.v2".equals(policy.getString("$schema")))
            throw new IllegalArgumentException("fresh lifecycle actual history policy");
        if(!value.getString("config_sha256").matches("[0-9a-f]{64}")
                || value.getLong("native_executor_generation")<=0 || value.getLong("app_process_generation")<=0
                || !nonnegative(value.get("observed_at_ms")) || !nonnegative(value.get("current_lifetime_renewal_count")))
            throw new IllegalArgumentException("fresh lifecycle actor");
        if(!value.isNull("last_failure_cause"))new EmbeddedDuplexNativeOperationFailure(
            value.getJSONObject("last_failure_cause").toString()).validatedFailure();
        requireArchiveCursor(value.getJSONObject("retired_lifetimes"),value);
    }
    static void requireArchiveCursor(JSONObject cursor,JSONObject actor)throws Exception{
        keys(cursor,"total","first","last","recent","history_sha256");
        if(!nonnegative(cursor.get("total")))throw new IllegalArgumentException("lifetime cursor total");
        long total=cursor.getLong("total");JSONArray recent=cursor.getJSONArray("recent");
        if(recent.length()>2 || total<recent.length())throw new IllegalArgumentException("lifetime cursor bounds");
        if(total==0){
            if(!cursor.isNull("first") || !cursor.isNull("last") || !cursor.isNull("history_sha256") || recent.length()!=0)
                throw new IllegalArgumentException("empty lifetime cursor");
            return;
        }
        if(recent.length()!=Math.min(2L,total) || !cursor.getString("history_sha256").matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("lifetime cursor history");
        JSONObject first=cursor.getJSONObject("first"),last=cursor.getJSONObject("last");
        requireArchiveAck(first,actor);requireArchiveAck(last,actor);
        if(!first.getString("app_record_sha256").equals(last.getString("app_record_sha256")))throw new IllegalArgumentException("lifetime archive app binding");
        for(int i=0;i<recent.length();i++){
            JSONObject ack=recent.getJSONObject(i);requireArchiveAck(ack,actor);
            if(!first.getString("app_record_sha256").equals(ack.getString("app_record_sha256")))throw new IllegalArgumentException("lifetime archive recent app binding");
        }
        if(!same(last,recent.getJSONObject(recent.length()-1)) || total==1 && !same(first,last)
                || total==2 && !same(first,recent.getJSONObject(0)))throw new IllegalArgumentException("lifetime cursor endpoints");
    }
    private static void requireArchiveAck(JSONObject ack,JSONObject actor)throws Exception{
        keys(ack,"$schema","body_sha256","app_process_generation","app_record_sha256","native_executor_generation",
            "config_sha256","prior_stop_original_id","storage_identity");
        if(!"rusty.quest.embedded_duplex.peer_lifetime_archive_retained.v1".equals(ack.getString("$schema"))
                || !ack.getString("body_sha256").matches("sha256:[0-9a-f]{64}")
                || !ack.getString("prior_stop_original_id").matches("[0-9a-f]{32}")
                || !ack.getString("app_record_sha256").matches("[0-9a-f]{64}")
                || ack.getString("storage_identity").isEmpty())throw new IllegalArgumentException("lifetime archive ACK");
        for(String key:new String[]{"app_process_generation","native_executor_generation","config_sha256"})
            if(!EmbeddedDuplexCanonicalJson.sameValue(ack.get(key),actor.get(key)))throw new IllegalArgumentException("lifetime archive actor");
    }
    private static boolean same(JSONObject a,JSONObject b)throws Exception{return EmbeddedDuplexCanonicalJson.encode(a).equals(EmbeddedDuplexCanonicalJson.encode(b));}
    private static boolean nonnegative(Object n){return (n instanceof Integer || n instanceof Long) && ((Number)n).longValue()>=0;}
    private static void keys(JSONObject value,String... expected){
        java.util.HashSet<String> actual=new java.util.HashSet<>();value.keys().forEachRemaining(actual::add);
        if(!actual.equals(new java.util.HashSet<>(java.util.Arrays.asList(expected))))throw new IllegalArgumentException("fresh lifecycle closed nested fields");
    }
}
