package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONArray;
import org.json.JSONObject;

/** Deliberate compact fresh status v2. These diagnostics never grant effects. */
final class EmbeddedDuplexFreshStatus {
    static JSONObject require(JSONObject value)throws Exception{
        keys(value,"$schema","state","request_id","session_id","reservation","failure","local_setup","remote_setup","local_current",
            "remote_current","final_local_current","prior_pair","eligibility","completed_setups","phase_request_id","phase_fenced",
            "linked_predecessor","phase_archive","pending_phase","preparation");
        if(!"rusty.quest.embedded_duplex.fresh_pair_setup_status.v2".equals(value.getString("$schema"))
                || !java.util.Arrays.asList("idle","pending_before_view","pending","completed").contains(value.getString("state")))
            throw new IllegalArgumentException("fresh status v2 closed schema/state");
        count(value,"completed_setups");
        for(String key:new String[]{"request_id","session_id","phase_request_id"})if(!value.isNull(key))id(value.getString(key));
        if(!value.isNull("phase_fenced") && !(value.get("phase_fenced") instanceof Boolean))throw new IllegalArgumentException("fresh phase fence observation");
        if(!value.isNull("pending_phase"))sha(value.getString("pending_phase"));
        if(!value.isNull("reservation")){
            JSONObject r=value.getJSONObject("reservation");
            keys(r,"$schema","request_id","session_id","current_original_binding","initial_original_binding","authority_or_signature_entered",
                "declined_before_dispatch","failure","prime_pending","prime_progress","prime_origin_sha256","prime_joined_count","reservation_sha256");
            if(!"rusty.quest.embedded_duplex.fresh_setup_reservation_status.v1".equals(r.getString("$schema")))throw new IllegalArgumentException("fresh reservation projection schema");
            id(r.getString("request_id"));id(r.getString("session_id"));binding(r.getJSONObject("current_original_binding"));binding(r.getJSONObject("initial_original_binding"));
            bool(r,"authority_or_signature_entered");bool(r,"declined_before_dispatch");count(r,"prime_joined_count");sha(r.getString("reservation_sha256"));
            if(!r.isNull("prime_origin_sha256"))sha(r.getString("prime_origin_sha256"));
            if(!r.isNull("prime_pending")){
                JSONObject pending=r.getJSONObject("prime_pending");keys(pending,"step","entered","request_id","source_before_sha256");
                pending.getString("step");bool(pending,"entered");id(pending.getString("request_id"));sha(pending.getString("source_before_sha256"));
            }
            JSONArray rows=r.getJSONArray("prime_progress");if(rows.length()>2)throw new IllegalArgumentException("fresh compact prime history bound");
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);keys(row,"step","request_id","receipt_sha256","owner_snapshot_raw_sha256");
                row.getString("step");id(row.getString("request_id"));sha(row.getString("receipt_sha256"));
                if(!row.isNull("owner_snapshot_raw_sha256"))sha(row.getString("owner_snapshot_raw_sha256"));
            }
        }
        if(!value.isNull("preparation")){
            JSONObject p=value.getJSONObject("preparation");keys(p,"$schema","state","phase_request_id","manifold_transaction_id","context_sha256","local_receipt_sha256","remote_receipt_sha256","archive");
            if(!"rusty.quest.embedded_duplex.fresh_phase_preparation_status.v1".equals(p.getString("$schema"))
                    || !java.util.Arrays.asList("pending","entered_unknown","prepared","activated").contains(p.getString("state")))throw new IllegalArgumentException("fresh preparation projection");
            id(p.getString("phase_request_id"));
            for(String key:new String[]{"manifold_transaction_id","context_sha256","local_receipt_sha256","remote_receipt_sha256"})
                if(!p.isNull(key)){if(key.equals("manifold_transaction_id"))id(p.getString(key));else sha(p.getString(key));}
            if(!p.isNull("archive"))archive(p.getJSONObject("archive"));
        }
        if(!value.isNull("linked_predecessor")){
            JSONObject p=value.getJSONObject("linked_predecessor");String schema=p.getString("$schema");
            if("rusty.quest.embedded_duplex.fresh_setup_preparation_predecessor.v1".equals(schema)){
                keys(p,"$schema","prior_phase_id","phase_request_id","preparation_receipt_sha256","local_archive");
                id(p.getString("prior_phase_id"));id(p.getString("phase_request_id"));
                if(!p.isNull("preparation_receipt_sha256"))sha(p.getString("preparation_receipt_sha256"));
                archive(p.getJSONObject("local_archive"));
            }else{
                keys(p,"$schema","prior_phase_id","phase_request_id","ticket_sha256","local_archive","peer_archive","handoff_ack");
                if(!"rusty.quest.embedded_duplex.fresh_setup_phase_predecessor.v1".equals(schema))throw new IllegalArgumentException("fresh predecessor projection");
                id(p.getString("prior_phase_id"));id(p.getString("phase_request_id"));sha(p.getString("ticket_sha256"));archive(p.getJSONObject("local_archive"));
                if(!p.isNull("peer_archive"))archive(p.getJSONObject("peer_archive"));
                // Diagnostic only; full handoff consumer separately verifies custody.
                if(!p.isNull("handoff_ack"))p.getJSONObject("handoff_ack");
            }
        }
        cursor(value.getJSONObject("phase_archive"));return value;
    }
    static void cursor(JSONObject cursor)throws Exception{
        keys(cursor,"total","first","last","recent","history_sha256");long total=count(cursor,"total");JSONArray recent=cursor.getJSONArray("recent");
        if(total==0){if(!cursor.isNull("first")||!cursor.isNull("last")||!cursor.isNull("history_sha256")||recent.length()!=0)throw new IllegalArgumentException("empty phase archive cursor");return;}
        if(recent.length()!=Math.min(2L,total))throw new IllegalArgumentException("bounded phase archive cursor");sha(cursor.getString("history_sha256"));
        archive(cursor.getJSONObject("first"));archive(cursor.getJSONObject("last"));for(int i=0;i<recent.length();i++)archive(recent.getJSONObject(i));
        if(!same(cursor.getJSONObject("last"),recent.getJSONObject(recent.length()-1))
                || total==1&&!same(cursor.getJSONObject("first"),cursor.getJSONObject("last"))
                || total==2&&!same(cursor.getJSONObject("first"),recent.getJSONObject(0)))throw new IllegalArgumentException("phase archive cursor endpoints");
    }
    private static void archive(JSONObject ack)throws Exception{
        keys(ack,"$schema","body_sha256","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","native_request_id","phase_request_id","original_binding","storage_identity");
        if(!"rusty.quest.embedded_duplex.fresh_setup_phase_archive_retained.v2".equals(ack.getString("$schema"))
                || count(ack,"app_process_generation")==0||count(ack,"native_executor_generation")==0||ack.getString("storage_identity").isEmpty())throw new IllegalArgumentException("actual phase archive projection");
        sha(ack.getString("body_sha256"));rawSha(ack.getString("app_record_sha256"));rawSha(ack.getString("config_sha256"));id(ack.getString("native_request_id"));id(ack.getString("phase_request_id"));binding(ack.getJSONObject("original_binding"));
    }
    private static void binding(JSONObject v)throws Exception{
        if("operator".equals(v.getString("kind"))){keys(v,"kind","original_id");if(!v.getString("original_id").matches("[0-9a-f]{32}"))throw new IllegalArgumentException("phase operator binding");}
        else{keys(v,"kind","native_request_id");if(!"incoming_fresh_setup".equals(v.getString("kind")))throw new IllegalArgumentException("phase incoming binding");id(v.getString("native_request_id"));}
    }
    private static long count(JSONObject v,String key)throws Exception{Object n=v.get(key);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<0)throw new IllegalArgumentException("fresh status nonnegative integer");return ((Number)n).longValue();}
    private static void bool(JSONObject v,String key)throws Exception{if(!(v.get(key) instanceof Boolean))throw new IllegalArgumentException("fresh status Boolean");}
    private static void id(String v){EmbeddedDuplexIncomingFreshPhases.id(v);}
    private static void sha(String v){if(!v.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("fresh status typed digest");}
    private static void rawSha(String v){if(!v.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("fresh status raw digest");}
    private static void keys(JSONObject v,String... keys){EmbeddedDuplexFreshHandoffStore.keys(v,keys);}
    private static boolean same(Object a,Object b)throws Exception{return EmbeddedDuplexCanonicalJson.encode(a).equals(EmbeddedDuplexCanonicalJson.encode(b));}
}
