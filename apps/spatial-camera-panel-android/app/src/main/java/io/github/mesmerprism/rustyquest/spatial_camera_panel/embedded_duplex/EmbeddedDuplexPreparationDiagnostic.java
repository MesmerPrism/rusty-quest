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
    private static final String[] SDK_STAGES={"renderer_begin","sender_prepare","source_check","frame_clock","frame_shape","frame_sign","frame_encode","frame_exchange","frame_decode","response_identity","remote_view","decoder_receipt","decoder_wait","owner_ready","renderer_dormant","incoming_bind","incoming_sender","incoming_decoder","ready_status"};
    private static void sdkLane(JSONObject value)throws Exception{
        keys(value,"state","stage","frame_kind","reason");String state=value.getString("state");
        require(Arrays.asList("unobserved","entered","failed","succeeded").contains(state));
        if("unobserved".equals(state)||"succeeded".equals(state))require(value.isNull("stage")&&value.isNull("frame_kind")&&value.isNull("reason"));
        else require(!value.isNull("stage")&&Arrays.asList(SDK_STAGES).contains(value.getString("stage")));
        if(!value.isNull("frame_kind"))require(Arrays.asList("view","viewed","decoder_prepare","decoder_prepared").contains(value.getString("frame_kind")));
        if("failed".equals(state))require(!value.isNull("reason")&&Arrays.asList("call_failed","predicate_rejected","source_changed","callback_exception","callback_unavailable","frame_binding_rejected","frame_signature_rejected","frame_shape_rejected","frame_wire_rejected").contains(value.getString("reason")));
        else require(value.isNull("reason"));
    }
    private static void sdk(JSONObject value)throws Exception{
        keys(value,"$schema","outbound","incoming","first_failure");require("rusty.quest.embedded_duplex.preparation_sdk_observation.v1".equals(value.getString("$schema")));
        JSONObject outbound=value.getJSONObject("outbound"),incoming=value.getJSONObject("incoming");sdkLane(outbound);sdkLane(incoming);
        if(value.isNull("first_failure"))require(!"failed".equals(outbound.getString("state"))&&!"failed".equals(incoming.getString("state")));
        else{
            JSONObject first=value.getJSONObject("first_failure");keys(first,"direction","observation");String direction=first.getString("direction");require(Arrays.asList("outbound","incoming").contains(direction));
            JSONObject observation=first.getJSONObject("observation"),lane=value.getJSONObject(direction);sdkLane(observation);require("failed".equals(observation.getString("state")));
            for(String key:new String[]{"state","stage","frame_kind","reason"})require(observation.get(key).equals(lane.get(key)));
        }
    }
    private static void transport(JSONObject value)throws Exception{
        keys(value,"schema","qualification_claimed","response_authentication_claimed","strict_parser_status","strict_parser_error","closed_java_fields");
        require("rusty.quest.embedded_duplex.owner_transport_attempt_native_read.v1".equals(value.getString("schema"))&&Boolean.FALSE.equals(value.get("qualification_claimed"))&&Boolean.FALSE.equals(value.get("response_authentication_claimed")));
        String status=value.getString("strict_parser_status");require(Arrays.asList("AVAILABLE","REJECTED","UNAVAILABLE").contains(status));
        if("AVAILABLE".equals(status)){
            require(value.isNull("strict_parser_error"));JSONObject facts=value.getJSONObject("closed_java_fields");keys(facts,"attempt_sequence","phase","outcome","failure");
            require((facts.get("attempt_sequence") instanceof Integer||facts.get("attempt_sequence") instanceof Long)&&facts.getLong("attempt_sequence")>=0&&facts.getLong("attempt_sequence")<=9007199254740991L);
            require(Arrays.asList("NONE","TARGET_BINDING","INPUT_BOUNDS","CONNECT","WRITE_REQUEST","READ_RESPONSE_HEADER","READ_RESPONSE_BODY","RESPONSE_RETURNED").contains(facts.getString("phase")));
            require(Arrays.asList("NONE","UNAVAILABLE","IN_PROGRESS","RETURNED","FAILED").contains(facts.getString("outcome")));
            require(Arrays.asList("NONE","TARGET_UNAVAILABLE","INPUT_BOUNDS","CONNECT_FAILURE","EOF","FRAME_BOUNDS","DEADLINE","IO","CALLBACK_REJECTED").contains(facts.getString("failure")));
            long sequence=facts.getLong("attempt_sequence");String phase=facts.getString("phase"),outcome=facts.getString("outcome"),failure=facts.getString("failure");
            boolean failed=("TARGET_UNAVAILABLE".equals(failure)&&"TARGET_BINDING".equals(phase))
                ||("INPUT_BOUNDS".equals(failure)&&"INPUT_BOUNDS".equals(phase))
                ||("CONNECT_FAILURE".equals(failure)&&"CONNECT".equals(phase))
                ||("EOF".equals(failure)&&Arrays.asList("READ_RESPONSE_HEADER","READ_RESPONSE_BODY").contains(phase))
                ||("FRAME_BOUNDS".equals(failure)&&"READ_RESPONSE_HEADER".equals(phase))
                ||(Arrays.asList("DEADLINE","IO").contains(failure)&&Arrays.asList("CONNECT","WRITE_REQUEST","READ_RESPONSE_HEADER","READ_RESPONSE_BODY","RESPONSE_RETURNED").contains(phase))
                ||("CALLBACK_REJECTED".equals(failure)&&!Arrays.asList("NONE","RESPONSE_RETURNED").contains(phase));
            require(("NONE".equals(outcome)&&sequence==0&&"NONE".equals(phase)&&"NONE".equals(failure))
                ||("UNAVAILABLE".equals(outcome)&&sequence==9007199254740991L&&"NONE".equals(phase)&&"NONE".equals(failure))
                ||("IN_PROGRESS".equals(outcome)&&sequence>0&&!Arrays.asList("NONE","RESPONSE_RETURNED").contains(phase)&&"NONE".equals(failure))
                ||("RETURNED".equals(outcome)&&sequence>0&&"RESPONSE_RETURNED".equals(phase)&&"NONE".equals(failure))
                ||("FAILED".equals(outcome)&&sequence>0&&failed));
        }else{
            require(value.isNull("closed_java_fields"));require(("UNAVAILABLE".equals(status)&&"CALL_UNAVAILABLE".equals(value.getString("strict_parser_error")))||("REJECTED".equals(status)&&Arrays.asList("BOUNDS","JSON_OR_SHAPE","CLOSED_VALUES").contains(value.getString("strict_parser_error"))));
        }
    }
    static JSONObject read(String raw,JSONObject actor,String expectedOriginal,String phase,
            boolean platformPresent,boolean resourcesPresent,boolean commandPresent)throws Exception {
        JSONObject value=new JSONObject(raw);
        boolean v3="rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v3".equals(value.getString("$schema"));
        boolean v2=v3||"rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v2".equals(value.getString("$schema"));
        java.util.List<String> fields=new java.util.ArrayList<>(Arrays.asList("$schema","operation","app_process_generation","app_record_sha256",
            "native_executor_generation","config_sha256","state","attempt_sequence","original_id","original_bound",
            "stage","reason","arena_installed","authority_granted","java_failed_guard","physical_predicates"));
        if(v2){fields.add("native_failed_guard");fields.add("native_failed_reason");}
        if(v3){fields.add("sdk_observation");fields.add("transport_attempt");fields.add("transport_attempt_original_bound");}
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
        if(v3){
            require(Boolean.FALSE.equals(value.get("transport_attempt_original_bound")));
            require(value.isNull("sdk_observation")==value.isNull("transport_attempt"));
            if(!value.isNull("sdk_observation")){
                require(bound&&Boolean.TRUE.equals(value.get("arena_installed")));sdk(value.getJSONObject("sdk_observation"));transport(value.getJSONObject("transport_attempt"));
            }
        }
        value.put("java_census",new JSONObject().put("$schema","rusty.quest.embedded_duplex.preparation_reference_census.v1")
            .put("host_phase",phase).put("platform_reference_present",platformPresent)
            .put("resources_reference_present",resourcesPresent).put("command_original_present",commandPresent)
            .put("cleanup_proven",false));
        return value;
    }
}
