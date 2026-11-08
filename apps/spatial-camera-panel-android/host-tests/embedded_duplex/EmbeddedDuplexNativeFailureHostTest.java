package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
/** Exact production carrier parser, no Android or modeled native authority. */
public final class EmbeddedDuplexNativeFailureHostTest {
    static int checks;
    static void yes(boolean v){checks++;if(!v)throw new AssertionError("native failure control");}
    static JSONObject fixture() throws Exception {
        return new JSONObject().put("$schema","rusty.quest.embedded_duplex.operation_failure.v2")
            .put("code","fresh_setup_rejected").put("operation","fresh_pair_setup")
            .put("diagnostic",new JSONObject().put("owner","quest_native").put("stage","stage_successor")
                .put("reason","owner_rejected").put("request_id","pair.finite.fixture").put("phase_id",JSONObject.NULL)
                .put("original_id",JSONObject.NULL).put("app_generation",7).put("native_generation",9)
                .put("app_record_sha256","a".repeat(64)).put("config_sha256","sha256:"+"b".repeat(64)));
    }
    public static void main(String[] args) throws Exception {
        JSONObject expected=fixture();
        JSONObject actual=new EmbeddedDuplexNativeOperationFailure(expected.toString()).validatedFailure();
        yes(actual.toString().equals(expected.toString()));
        for(String key:new String[]{"owner","stage","reason","request_id","phase_id","original_id","app_generation","native_generation","app_record_sha256","config_sha256","extra"}){
            JSONObject bad=fixture();bad.getJSONObject("diagnostic").put(key,"secret/raw/invalid");
            boolean refused=false;try{new EmbeddedDuplexNativeOperationFailure(bad.toString()).validatedFailure();}catch(Exception e){refused=true;}yes(refused);
        }
        for(String key:new String[]{"request_id","phase_id"})for(Object child:new Object[]{true,false,123,1.5}){
            JSONObject bad=fixture();bad.getJSONObject("diagnostic").put(key,child);
            boolean typedRefusal=false;try{new EmbeddedDuplexNativeOperationFailure(bad.toString()).validatedFailure();}catch(Exception e){typedRefusal=true;}yes(typedRefusal);
        }
        JSONObject missing=fixture();missing.getJSONObject("diagnostic").remove("phase_id");
        boolean refused=false;try{new EmbeddedDuplexNativeOperationFailure(missing.toString()).validatedFailure();}catch(Exception e){refused=true;}yes(refused);
        JSONObject unknown=new JSONObject().put("$schema","rusty.quest.embedded_duplex.operation_failure.v1")
            .put("code","rejected_unknown").put("operation","fresh_pair_setup")
            .put("session_id",JSONObject.NULL).put("observed_at_ms",JSONObject.NULL).put("current_session",JSONObject.NULL);
        yes(new EmbeddedDuplexNativeOperationFailure(unknown.toString()).validatedFailure().getString("code").equals("rejected_unknown"));
        JSONObject huge=fixture();huge.getJSONObject("diagnostic").put("request_id","a".repeat(33000));
        refused=false;try{new EmbeddedDuplexNativeOperationFailure(huge.toString()).validatedFailure();}catch(Exception e){refused=true;}yes(refused);
        System.out.println("Native failure controls passed: "+checks);
    }
}
