package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;

/** Join Native's stored successful-constructor witness to ACTUAL Java constructor inputs.
 * Context is only expected source data, never a successful Native construction or cleanup proof. */
final class EmbeddedDuplexRuntimeConfigurationBinding {
    static final String SCHEMA="rusty.quest.embedded_duplex.runtime_configuration_binding.v1";
    static final String NATIVE_V2="rusty.quest.embedded_duplex.no_media_retirement.v2";
    static final String WHOLE_V2="rusty.quest.embedded_duplex.no_media_whole_retirement.v2";
    static final class Context {
        private final String expected;
        private Context(JSONObject value)throws Exception{expected=EmbeddedDuplexCanonicalJson.encode(value);}
        void require(JSONObject actual)throws Exception{
            fields(actual,"$schema","config_sha256","app_process_generation","app_record_sha256","native_executor_generation","local_peer_id","fresh_setup_build_policy");
            if(!expected.equals(EmbeddedDuplexCanonicalJson.encode(actual)))
                throw new IllegalStateException("actual constructor/source/compiled target differs");
        }
    }
    private static void fields(JSONObject value,String...names)throws Exception{
        HashSet<String> actual=new HashSet<>();value.keys().forEachRemaining(actual::add);
        if(!actual.equals(new HashSet<>(Arrays.asList(names))))throw new IllegalStateException("closed constructor binding fields differ");
    }
    static Context prepare(String exactConfig,String expectedSha,String actualLocalPeer,
            long actualApp,String actualRecord,long actualNative)throws Exception{
        if("none".equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY))return null; // Finite old branch unchanged.
        if(!"source-bound-v2".equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_SETUP_POLICY)
                ||actualApp<=0||actualNative<=0||!actualRecord.matches("[0-9a-f]{64}")
                ||!expectedSha.matches("[0-9a-f]{64}")
                ||!expectedSha.equals(EmbeddedDuplexCanonicalJson.sha256Bytes(exactConfig.getBytes(StandardCharsets.UTF_8))))
            throw new IllegalStateException("actual constructor source identity differs");
        JSONObject config=new JSONObject(exactConfig).getJSONObject("embedded_duplex");
        JSONObject ongoing=config.getJSONObject("ongoing_media_policy");fields(ongoing,"$schema");
        if(!"rusty.manifold.peer.ongoing_media_policy.v2".equals(ongoing.getString("$schema"))
                ||!actualLocalPeer.equals(config.getString("fresh_setup_local_peer_id")))
            throw new IllegalStateException("actual selected ongoing constructor peer differs");
        JSONObject policy=EmbeddedDuplexFreshSetupPolicy.policy();
        String policySha=EmbeddedDuplexFreshSetupPolicy.digest(EmbeddedDuplexFreshSetupPolicy.POLICY_DOMAIN,policy);
        if(!policySha.equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_POLICY_SHA256)
                ||!BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_TARGET_RAW_SHA256.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("actual compiled fresh policy differs");
        byte[] bytes=Base64.getDecoder().decode(BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_BASE64);
        String raw=new String(bytes,StandardCharsets.UTF_8);JSONObject selected=new JSONObject(raw);
        String canonical=EmbeddedDuplexCanonicalJson.encode(selected);
        if(!Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8))||!raw.equals(canonical)
                ||!canonical.equals(EmbeddedDuplexCanonicalJson.encode(config.getJSONObject("fresh_setup_enrollment_selection")))
                ||!EmbeddedDuplexFreshSetupPolicy.digest(EmbeddedDuplexFreshSetupPolicy.TARGET_DOMAIN,selected)
                    .equals(BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256))
            throw new IllegalStateException("actual compiled constructor enrollment target differs");
        JSONArray keys=selected.getJSONArray("keys");boolean local=false;
        for(int i=0;i<keys.length();i++)if(actualLocalPeer.equals(keys.getJSONObject(i).getString("peer_id")))local=true;
        if(!local)throw new IllegalStateException("actual constructor peer absent from compiled target");
        JSONObject projection=new JSONObject().put("$schema","rusty.quest.embedded_duplex.fresh_setup_build_policy.v1")
            .put("selection","source-bound-v2").put("policy",policy).put("policy_sha256",policySha)
            .put("enrollment_selection_sha256",BuildConfig.EMBEDDED_DUPLEX_FRESH_ENROLLMENT_SELECTION_SHA256);
        return new Context(new JSONObject().put("$schema",SCHEMA).put("config_sha256",expectedSha)
            .put("app_process_generation",actualApp).put("app_record_sha256",actualRecord)
            .put("native_executor_generation",actualNative).put("local_peer_id",actualLocalPeer)
            .put("fresh_setup_build_policy",projection));
    }
    static void requireNative(JSONObject proof,Context actual)throws Exception{
        if(!NATIVE_V2.equals(proof.getString("$schema"))||actual==null)
            throw new IllegalStateException("successful selected constructor witness unavailable");
        JSONObject binding=proof.getJSONObject("configuration_binding");actual.require(binding);
        for(String key:new String[]{"config_sha256","app_process_generation","app_record_sha256","native_executor_generation"})
            if(!proof.get(key).equals(binding.get(key)))throw new IllegalStateException("retiring native actor differs from constructor");
    }
    private EmbeddedDuplexRuntimeConfigurationBinding(){}
}
