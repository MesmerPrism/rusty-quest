package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
/** Fixed Native owner observations; never media admission or whole cleanup. */
final class EmbeddedDuplexPreparedRenderer {
    static final String COLD="rusty.quest.spatial.prepared_peer_renderer.v2",OWN="rusty.quest.spatial.prepared_peer_renderer_own_reuse.v2",NONE="rusty.quest.spatial.prepared_peer_renderer_unentered.v2";
    static JSONObject parse(String raw)throws Exception{
        JSONObject v=new JSONObject(raw);String schema=v.getString("$schema");
        if(COLD.equals(schema))EmbeddedDuplexPeerRoots.keys(v,"$schema","preparation_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","request_sha256","resource_target_sha256","route_generation","launch_challenge","surface_generation","layer_switch_count","requested_width","requested_height","actual_width","actual_height","worker_ordinal","state","wsi_ready","frame_work_admitted","frame_import_count","present_count","cleanup_terminal","worker_joined","failure","source_hold_acquired","source_hold_released","prepare_calls_joined");
        else if(OWN.equals(schema))EmbeddedDuplexPeerRoots.keys(v,"$schema","preparation_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","request_sha256","resource_target_sha256","peer_route_generation","launch_challenge","surface_generation","layer_switch_count","own_claim_generation","own_process_generation","own_source_generation","own_construction_attempts","state","peer_gpu_worker_created","peer_frame_import_count","peer_present_count","peer_reservation_released","own_resources_retired","source_hold_acquired","source_hold_released","prepare_calls_joined");
        else if(NONE.equals(schema))EmbeddedDuplexPeerRoots.keys(v,"$schema","identity","disposition","cleanup_terminal","worker_created","source_hold_acquired","source_hold_released","prepare_calls_joined");
        else throw new IllegalStateException("closed actual renderer observation schema differs");return v;
    }
    static void identity(JSONObject value,JSONObject request,String requestSha)throws Exception{
        JSONObject v=parse(value.toString()),identity=NONE.equals(v.getString("$schema"))?v.getJSONObject("identity"):v;
        if(NONE.equals(v.getString("$schema")))EmbeddedDuplexPeerRoots.keys(identity,"preparation_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","request_sha256","resource_target_sha256","resource_target");
        EmbeddedDuplexPeerRoots.identity(identity,request);
        if(!identity.getString("preparation_id").equals(request.getString("preparation_id"))||!identity.getString("request_sha256").equals(requestSha)
            ||!identity.getString("resource_target_sha256").equals(request.getString("resource_target_sha256")))throw new IllegalStateException("actual renderer prepared owner identity differs");
        if(NONE.equals(v.getString("$schema"))&&!EmbeddedDuplexCanonicalJson.sameValue(identity.getJSONObject("resource_target"),request.getJSONObject("resource_target")))
            throw new IllegalStateException("actual unentered renderer target differs");
        if(OWN.equals(v.getString("$schema"))&&!request.getJSONObject("resource_target").getBoolean("own_capture_selected"))
            throw new IllegalStateException("actual Own renderer reuse without selected Own");
    }
    static boolean dormant(String raw,String id,long route)throws Exception{
        JSONObject v=parse(raw);String schema=v.getString("$schema");
        if(NONE.equals(schema))throw new IllegalStateException("renderer registration did not earn SDK readiness");
        if(!id.equals(v.getString("preparation_id"))||v.getLong(COLD.equals(schema)?"route_generation":"peer_route_generation")!=route)throw new IllegalStateException("renderer route identity changed");
        if(!Boolean.TRUE.equals(v.get("source_hold_acquired"))||!Boolean.FALSE.equals(v.get("source_hold_released"))
            ||!(v.get("prepare_calls_joined")instanceof Boolean))throw new IllegalStateException("actual dormant source hold differs");
        if(COLD.equals(schema)){
            if(!v.isNull("failure"))throw new IllegalStateException("actual retained renderer construction failed");
            if(!"ready".equals(v.getString("state")))return false;
            if(!v.getBoolean("wsi_ready")||v.getBoolean("frame_work_admitted")||v.getLong("frame_import_count")!=0||v.getLong("present_count")!=0
                ||v.getBoolean("cleanup_terminal")||v.getBoolean("worker_joined")||v.getLong("actual_width")<=0||v.getLong("actual_height")<=0)
                throw new IllegalStateException("actual cold WSI dormant witness differs");return true;
        }
        if(!"ready".equals(v.getString("state"))||v.getBoolean("peer_gpu_worker_created")||v.getLong("peer_frame_import_count")!=0||v.getLong("peer_present_count")!=0
            ||v.getBoolean("peer_reservation_released")||v.getBoolean("own_resources_retired")||v.getLong("own_process_generation")<=0||v.getLong("own_source_generation")<=0)
            throw new IllegalStateException("actual independent Own renderer witness differs");return true;
    }
    static boolean terminal(String raw,String id)throws Exception{
        JSONObject v=parse(raw);String schema=v.getString("$schema");JSONObject identity=NONE.equals(schema)?v.getJSONObject("identity"):v;
        if(!id.equals(identity.getString("preparation_id")))throw new IllegalStateException("renderer terminal ID differs");
        if(!Boolean.TRUE.equals(v.get("prepare_calls_joined")))return false;
        if(!(v.get("source_hold_acquired")instanceof Boolean)||!(v.get("source_hold_released")instanceof Boolean))
            throw new IllegalStateException("actual renderer source hold types differ");
        if(v.getBoolean("source_hold_acquired")&&!v.getBoolean("source_hold_released"))return false;
        if(COLD.equals(schema))return "terminal".equals(v.getString("state"))&&v.getBoolean("cleanup_terminal")&&v.getBoolean("worker_joined");
        if(OWN.equals(schema))return v.getBoolean("source_hold_acquired")&&v.getBoolean("source_hold_released")&&"released".equals(v.getString("state"))&&v.getBoolean("peer_reservation_released")&&!v.getBoolean("own_resources_retired");
        if(!java.util.Arrays.asList("registered_not_entered","registration_refused_before_callbacks","refused_before_worker").contains(v.getString("disposition")))throw new IllegalStateException("actual unentered renderer discriminator differs");
        return v.getBoolean("cleanup_terminal")&&!v.getBoolean("worker_created");
    }
}
