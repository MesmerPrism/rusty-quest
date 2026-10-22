package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.util.Arrays;
import org.json.JSONObject;

/** Closed retained preparation metadata. References and phase certify no cleanup. */
final class EmbeddedDuplexPreparationDiagnostic {
    private EmbeddedDuplexPreparationDiagnostic() {}
    private static void require(boolean value) {
        if(!value)throw new IllegalArgumentException("preparation diagnostic binding unavailable");
    }
    enum Guard { CALLBACK_FENCE, TARGET_ACTOR, TARGET_FIELDS, TARGET_ORIGINAL_ACTOR, TARGET_METADATA,
        ACTOR_IDENTITY, ARM_OBSERVATION, ARM_VALIDATION, ORIGINAL_BINDING, PHYSICAL_OBSERVATION, CALLBACK_CLOSE }
    enum NativeGuard { CAPABILITY, LOCAL_SLOT, LOCAL_STATE, ACTIVATION_SLOT, ACTIVATION_FLIGHT,
        DISPATCH_SLOT, ACTIVATION_REPLAY_SLOT, CLEANUP_REPLAY_SLOT, INBOUND_PENDING,
        RECEIVER_RESTORED_HISTORY, RECEIVER_ACTIVATION_WITHOUT_HISTORY, RECEIVER_COMPLETE_HISTORY,
        RECEIVER_KEY, RECEIVER_RESPONSE_IDENTITY, RECEIVER_SIGNATURE, RECEIVER_EFFECT,
        RECEIVER_READBACK_DECODE, RECEIVER_READBACK_BINDING, RECEIVER_STATE, RECEIVER_REVISION,
        RECEIVER_LATEST_STOP, RECEIVER_ACTIVATION_IDENTITY, RECEIVER_ACTIVATION_SIGNATURE,
        CLEANUP_STATE, CLEANUP_PUBLIC_KEY, CLEANUP_SERIAL, CLEANUP_STATE_LOCK, CLEANUP_FORWARD_ORIGINAL,
        CLEANUP_PREPARED_TERMINAL, CLEANUP_REPLAY_SHAPE, CLEANUP_PENDING, CLEANUP_RESPONSE_DECODE,
        CLEANUP_RESPONSE_IDENTITY, CLEANUP_RESPONSE_COMPLETED, CLEANUP_RESPONSE_TERMINAL,
        CLEANUP_RESPONSE_GENERATION, CLEANUP_RESPONSE_STOP, CLEANUP_RESPONSE_SIGNATURE,
        PROVIDER_SLOT, PROVIDER_EVIDENCE, PROVIDER_DECODE, BROKER_PENDING }
    interface Call<T>{T run()throws Exception;}
    static final class EligibilityTrace {
        Guard guard=Guard.CALLBACK_FENCE;
        <T>T call(Guard next,Call<T> action)throws Exception{guard=next;return action.run();}
        String result(JSONObject value)throws Exception{return new JSONObject().put("value",value==null?JSONObject.NULL:value)
            .put("failure_guard",value==null?guard.name().toLowerCase(java.util.Locale.ROOT):JSONObject.NULL).toString();}
    }
    private static void keys(JSONObject value,String... names){
        java.util.HashSet<String> actual=new java.util.HashSet<>();
        java.util.Iterator<String> iterator=value.keys();
        while(iterator.hasNext())actual.add(iterator.next());
        require(actual.equals(new java.util.HashSet<>(Arrays.asList(names))));
    }
    static JSONObject read(String raw,JSONObject actor,String expectedOriginal,String phase,
            boolean platformPresent,boolean resourcesPresent,boolean commandPresent)throws Exception {
        JSONObject value=new JSONObject(raw);
        boolean v2="rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v2".equals(value.getString("$schema"));
        java.util.List<String> fields=new java.util.ArrayList<>(Arrays.asList("$schema","operation","app_process_generation","app_record_sha256",
            "native_executor_generation","config_sha256","state","attempt_sequence","original_id","original_bound",
            "stage","reason","arena_installed","authority_granted","java_failed_guard","physical_predicates"));
        if(v2){fields.add("native_failed_guard");fields.add("native_failed_reason");}
        keys(value,fields.toArray(new String[0]));
        require((v2||"rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v1".equals(value.getString("$schema")))
            &&"prepare_peer_start".equals(value.getString("operation"))&&Boolean.FALSE.equals(value.get("authority_granted")));
        for(String key:new String[]{"app_process_generation","native_executor_generation"})
            require((value.get(key) instanceof Long||value.get(key) instanceof Integer)&&value.getLong(key)>0&&value.getLong(key)==actor.getLong(key));
        for(String key:new String[]{"app_record_sha256","config_sha256"})
            require(value.get(key) instanceof String&&value.getString(key).matches("[0-9a-f]{64}")&&value.get(key).equals(actor.get(key)));
        require(value.get("original_bound") instanceof Boolean);
        boolean bound=value.getBoolean("original_bound");
        require(bound?!value.isNull("original_id")&&value.getString("original_id").matches("[0-9a-f]{32}"):value.isNull("original_id"));
        if(expectedOriginal!=null)require(bound&&expectedOriginal.equals(value.getString("original_id")));
        String state=value.getString("state");
        require(Arrays.asList("unobserved","entered","failed","succeeded").contains(state));
        if("unobserved".equals(state)) {
            require(!bound&&value.isNull("attempt_sequence")&&value.isNull("stage")&&value.isNull("reason")&&value.isNull("arena_installed"));
        }else{
            require((value.get("attempt_sequence") instanceof Integer||value.get("attempt_sequence") instanceof Long)&&value.getLong("attempt_sequence")>0&&value.get("arena_installed") instanceof Boolean);
            if(!value.isNull("stage"))require(Arrays.asList("capability","prior_archive","original_read","java_eligibility",
                "original_match","native_eligibility","physical_eligibility","resource_target","constructor_binding",
                "entropy","resource_target_digest","request_reservation","source_snapshot","arena_install","sdk_preparation").contains(value.getString("stage")));
            if("failed".equals(state))require(!value.isNull("stage")&&Arrays.asList("call_failed","predicate_rejected","java_guard_failed").contains(value.getString("reason")));
            else require(value.isNull("reason"));
            if("succeeded".equals(state))require(value.isNull("stage"));
        }
        if(!value.isNull("java_failed_guard")){
            require("failed".equals(state)&&"java_eligibility".equals(value.getString("stage"))&&"java_guard_failed".equals(value.getString("reason")));
            boolean known=false;for(Guard guard:Guard.values())if(guard.name().toLowerCase(java.util.Locale.ROOT).equals(value.getString("java_failed_guard")))known=true;
            require(known);
        }else require(!"java_guard_failed".equals(value.optString("reason")));
        if(v2){
            require(value.isNull("native_failed_guard")==value.isNull("native_failed_reason"));
            if(!value.isNull("native_failed_guard")){
                require("failed".equals(state)&&"native_eligibility".equals(value.getString("stage"))&&"call_failed".equals(value.getString("reason")));
                require(Arrays.asList("call_failed","predicate_rejected").contains(value.getString("native_failed_reason")));
                boolean known=false;for(NativeGuard guard:NativeGuard.values())if(guard.name().toLowerCase(java.util.Locale.ROOT).equals(value.getString("native_failed_guard")))known=true;
                require(known);
            }
        }
        if(!value.isNull("physical_predicates")){
            JSONObject facts=value.getJSONObject("physical_predicates");
            String[] fixed={"shape_matches","schema_matches","app_generation_matches","app_record_matches","native_generation_matches","config_matches","classification_matches","original_matches"};
            String[] nullable={"physical_eligible","unresolved_originals_zero","peer_resources_terminal","renderer_terminal","control_terminal"};
            java.util.List<String> all=new java.util.ArrayList<>(Arrays.asList(fixed));all.addAll(Arrays.asList(nullable));keys(facts,all.toArray(new String[0]));
            for(String key:fixed)require(facts.get(key) instanceof Boolean);
            for(String key:nullable)require(facts.isNull(key)||facts.get(key) instanceof Boolean);
        }
        value.put("java_census",new JSONObject().put("$schema","rusty.quest.embedded_duplex.preparation_reference_census.v1")
            .put("host_phase",phase).put("platform_reference_present",platformPresent)
            .put("resources_reference_present",resourcesPresent).put("command_original_present",commandPresent)
            .put("cleanup_proven",false));
        return value;
    }
}
