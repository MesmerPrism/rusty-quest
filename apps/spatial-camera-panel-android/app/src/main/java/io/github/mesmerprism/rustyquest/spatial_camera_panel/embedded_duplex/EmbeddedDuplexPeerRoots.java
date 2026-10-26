package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Exact owning Native root DTO boundary; not a substitute for full media validation. */
final class EmbeddedDuplexPeerRoots {
    static final String RESULT="rusty.quest.embedded_duplex.peer_root_reestablishment.v2";
    static final String STATUS="rusty.quest.embedded_duplex.peer_root_status.v1";
    private EmbeddedDuplexPeerRoots(){}
    static void keys(JSONObject object,String... names)throws Exception{
        java.util.HashSet<String> actual=new java.util.HashSet<>();object.keys().forEachRemaining(actual::add);
        if(!actual.equals(new java.util.HashSet<>(java.util.Arrays.asList(names))))throw new IllegalArgumentException("closed native peer roots fields");
    }
    static void identity(JSONObject body,JSONObject actual)throws Exception{
        for(String key:new String[]{"app_process_generation","app_record_sha256","native_executor_generation","config_sha256"}){
            if(!body.has(key)||!EmbeddedDuplexCanonicalJson.encode(new JSONObject().put(key,body.get(key))).equals(
                EmbeddedDuplexCanonicalJson.encode(new JSONObject().put(key,actual.get(key)))))
                throw new IllegalArgumentException("native peer roots actual actor/config differs");
        }
    }
    static void arm(JSONObject value)throws Exception{
        keys(value,"binding","challenge","arm_generation","native_process","process_epoch","runtime_config_sha256");
        if(!"established".equals(value.getString("binding")) || !value.getString("challenge").matches("[0-9a-f]{32}")
            || value.getLong("arm_generation")<=0 || value.getLong("native_process")<=0)
            throw new IllegalArgumentException("actual established ARM required for root reestablishment");
    }
    static void roots(JSONObject value)throws Exception{
        keys(value,"grant","control_lease","runtime_lease");
        for(String key:new String[]{"grant","control_lease","runtime_lease"})value.getJSONObject(key);
    }
    static void pair(JSONObject value,boolean current)throws Exception{
        keys(value,"$schema","session_id","current","rejection_reason","decision_id","peer_ids","topology_contract_id","validated_at_ms","expires_at_ms");
        if(!"rusty.manifold.peer.session_current_receipt.v2".equals(value.getString("$schema"))
            || !(value.get("current") instanceof Boolean) || current && !value.getBoolean("current"))
            throw new IllegalArgumentException("actual root pair observation differs");
    }
    static String result(String raw,JSONObject actor,String original,JSONObject actualArm)throws Exception{
        JSONObject body=new JSONObject(raw);
        keys(body,"$schema","request_id","original_id","app_process_generation","app_record_sha256","native_executor_generation","config_sha256",
            "arm_binding","source_bound_authority","current_pair","prior_roots","resulting_roots","admission_receipt","control_lease_transition",
            "runtime_adoption","root_binding_sha256","applied_at_ms","applied","owner_receipt");
        if(!RESULT.equals(body.getString("$schema")) || !original.matches("[0-9a-f]{32}") || !original.equals(body.getString("original_id"))
            || body.getString("request_id").isEmpty() || !(body.get("applied") instanceof Boolean) || !body.getBoolean("applied")
            || !(body.get("applied_at_ms") instanceof Long || body.get("applied_at_ms") instanceof Integer) || body.getLong("applied_at_ms")<0)
            throw new IllegalArgumentException("actual applied peer root Original differs");
        identity(body,actor);arm(body.getJSONObject("arm_binding"));arm(actualArm);
        if(!EmbeddedDuplexCanonicalJson.encode(body.getJSONObject("arm_binding")).equals(EmbeddedDuplexCanonicalJson.encode(actualArm)))
            throw new IllegalArgumentException("peer roots retained ARM differs");
        body.getJSONObject("source_bound_authority");pair(body.getJSONObject("current_pair"),true);
        JSONObject prior=body.getJSONObject("prior_roots"),next=body.getJSONObject("resulting_roots");roots(prior);roots(next);
        boolean grantSame=EmbeddedDuplexCanonicalJson.encode(prior.getJSONObject("grant")).equals(EmbeddedDuplexCanonicalJson.encode(next.getJSONObject("grant")));
        boolean leaseSame=EmbeddedDuplexCanonicalJson.encode(prior.getJSONObject("control_lease")).equals(EmbeddedDuplexCanonicalJson.encode(next.getJSONObject("control_lease")))
            && EmbeddedDuplexCanonicalJson.encode(prior.getJSONObject("runtime_lease")).equals(EmbeddedDuplexCanonicalJson.encode(next.getJSONObject("runtime_lease")));
        if(grantSame && leaseSame || body.isNull("admission_receipt")!=grantSame
            || body.isNull("control_lease_transition")!=leaseSame || body.isNull("runtime_adoption")!=leaseSame)
            throw new IllegalArgumentException("peer root changed/retained component receipt differs");
        if(!grantSame)body.getJSONObject("admission_receipt");
        if(!leaseSame){body.getJSONObject("control_lease_transition");body.getJSONObject("runtime_adoption");}
        ownerProjection(body);
        String claimed=body.getString("root_binding_sha256");JSONObject projection=new JSONObject(body.toString());projection.remove("root_binding_sha256");
        byte[] prefix=("rusty.quest.embedded_duplex.peer_root_binding.v1"+'\0').getBytes(StandardCharsets.UTF_8);
        byte[] value=EmbeddedDuplexCanonicalJson.encode(projection).getBytes(StandardCharsets.UTF_8);
        byte[] bytes=new byte[prefix.length+value.length];System.arraycopy(prefix,0,bytes,0,prefix.length);System.arraycopy(value,0,bytes,prefix.length,value.length);
        if(!claimed.equals("sha256:"+EmbeddedDuplexCanonicalJson.sha256Bytes(bytes)))throw new IllegalArgumentException("native root binding digest differs");
        return raw; // Preserve exact returned native bytes; never synthesize a receipt.
    }
    static void ownerProjection(JSONObject body)throws Exception {
        JSONObject owner=body.getJSONObject("owner_receipt");keys(owner,"$schema","authority","current_pair","broker");
        JSONObject broker=owner.getJSONObject("broker");keys(broker,"$schema","request","provider_epoch_id","admission","control_lease_transition","runtime_adoption","observed_at_ms");
        JSONObject request=broker.getJSONObject("request");keys(request,"$schema","request_id","grant","lease");
        if(!"rusty.manifold.peer.source_bound_root_reestablishment_receipt.v1".equals(owner.getString("$schema"))
            || !"rusty.manifold.broker.source_bound_root_reestablishment_receipt.v1".equals(broker.getString("$schema"))
            || !"rusty.manifold.broker.source_bound_root_reestablishment_request.v1".equals(request.getString("$schema"))
            || !request.getString("request_id").equals(body.getString("request_id")))throw new IllegalArgumentException("actual root owner receipt header differs");
        for(String[] pair:new String[][]{{"authority","source_bound_authority"},{"current_pair","current_pair"}})
            if(!EmbeddedDuplexCanonicalJson.sameValue(owner.get(pair[0]),body.get(pair[1])))throw new IllegalArgumentException("actual root owner projection differs");
        for(String[] pair:new String[][]{{"admission","admission_receipt"},{"control_lease_transition","control_lease_transition"},{"runtime_adoption","runtime_adoption"},{"observed_at_ms","applied_at_ms"}})
            if(!EmbeddedDuplexCanonicalJson.sameValue(broker.get(pair[0]),body.get(pair[1])))throw new IllegalArgumentException("actual root Broker projection differs");
        JSONObject prior=body.getJSONObject("prior_roots"),next=body.getJSONObject("resulting_roots");
        JSONObject grant=request.getJSONObject("grant"),lease=request.getJSONObject("lease");
        if(!EmbeddedDuplexCanonicalJson.sameValue(grant.get("prior_grant"),prior.get("grant"))
            || !EmbeddedDuplexCanonicalJson.sameValue(lease.get("prior_lease"),prior.get("control_lease")))throw new IllegalArgumentException("actual root owner preimage differs");
        JSONObject expected=new JSONObject(prior.toString());
        if(!broker.isNull("admission"))expected.put("grant",broker.getJSONObject("admission").getJSONObject("binding").getJSONObject("resulting_grant"));
        if(!broker.isNull("control_lease_transition")){
            Object expires=lease.get("expires_at_ms");expected.getJSONObject("control_lease").put("expires_at_ms",expires);
            expected.getJSONObject("runtime_lease").put("expires_at_ms",expires);
        }
        if(!EmbeddedDuplexCanonicalJson.sameValue(expected,next))throw new IllegalArgumentException("actual root owner result differs");
    }
    static String status(String raw,JSONObject actor)throws Exception{
        JSONObject body=new JSONObject(raw);
        keys(body,"$schema","app_process_generation","app_record_sha256","native_executor_generation","config_sha256","arm_binding_sha256",
            "state","observed_at_ms","grant","control_lease","runtime_lease","reestablishment","root_binding_sha256","current_pair");
        if(!STATUS.equals(body.getString("$schema")) || !java.util.Arrays.asList("current","expired","revoked","absent","pending").contains(body.getString("state"))
            || !(body.get("observed_at_ms") instanceof Integer || body.get("observed_at_ms") instanceof Long) || body.getLong("observed_at_ms")<0)
            throw new IllegalArgumentException("native root status schema/state differs");
        identity(body,actor);pair(body.getJSONObject("current_pair"),false);
        for(String key:new String[]{"grant","control_lease","runtime_lease"})if(!body.isNull(key))body.getJSONObject(key);
        if(body.isNull("reestablishment")){
            if(!body.isNull("root_binding_sha256") || !body.isNull("arm_binding_sha256"))throw new IllegalArgumentException("unearned peer root binding");
        }else{
            JSONObject established=body.getJSONObject("reestablishment");
            if(!RESULT.equals(established.getString("$schema")) || !established.getString("root_binding_sha256").equals(body.getString("root_binding_sha256"))
                || !body.getString("arm_binding_sha256").matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("retained root status binding differs");
            identity(established,actor);ownerProjection(established);
        }
        return raw;
    }
}
