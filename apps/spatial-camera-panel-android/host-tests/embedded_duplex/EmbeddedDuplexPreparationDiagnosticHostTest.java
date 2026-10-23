package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import org.json.JSONObject;
public final class EmbeddedDuplexPreparationDiagnosticHostTest {
    static int controls;
    static final String ID="c".repeat(32), SHA="a".repeat(64), CONFIG="b".repeat(64);
    static JSONObject actor()throws Exception{return new JSONObject().put("app_process_generation",7L).put("app_record_sha256",SHA).put("native_executor_generation",19L).put("config_sha256",CONFIG);}
    static JSONObject failed()throws Exception{return new JSONObject(actor().toString())
        .put("$schema","rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v1")
        .put("operation","prepare_peer_start").put("state","failed").put("attempt_sequence",1L)
        .put("original_id",ID).put("original_bound",true).put("stage","java_eligibility")
        .put("reason","call_failed").put("arena_installed",false).put("authority_granted",false)
        .put("java_failed_guard",JSONObject.NULL).put("physical_predicates",JSONObject.NULL);}
    static JSONObject read(JSONObject value,JSONObject actor,String expected)throws Exception{
        return EmbeddedDuplexPreparationDiagnostic.read(value.toString(),actor,expected,"READY",true,true,false);
    }
    interface Action{void run()throws Exception;}
    static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){controls++;return;}throw new AssertionError("negative accepted");}
    static void check(boolean value){if(!value)throw new AssertionError();controls++;}
    public static void main(String[] args)throws Exception{
        JSONObject lane=new JSONObject().put("state","failed").put("stage","frame_shape").put("frame_kind","view").put("reason","predicate_rejected");
        JSONObject unobserved=new JSONObject().put("state","unobserved").put("stage",JSONObject.NULL).put("frame_kind",JSONObject.NULL).put("reason",JSONObject.NULL);
        JSONObject sdk=new JSONObject().put("$schema","rusty.quest.embedded_duplex.preparation_sdk_observation.v1").put("outbound",lane).put("incoming",unobserved)
            .put("first_failure",new JSONObject().put("direction","outbound").put("observation",new JSONObject(lane.toString())));
        JSONObject transport=new JSONObject().put("schema","rusty.quest.embedded_duplex.owner_transport_attempt_native_read.v1")
            .put("qualification_claimed",false).put("response_authentication_claimed",false).put("strict_parser_status","AVAILABLE").put("strict_parser_error",JSONObject.NULL)
            .put("closed_java_fields",new JSONObject().put("attempt_sequence",0).put("phase","NONE").put("outcome","NONE").put("failure","NONE"));
        JSONObject v3=failed().put("$schema","rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v3").put("stage","sdk_preparation").put("arena_installed",true)
            .put("native_failed_guard",JSONObject.NULL).put("native_failed_reason",JSONObject.NULL).put("sdk_observation",sdk).put("transport_attempt",transport).put("transport_attempt_original_bound",false);
        check(read(v3,actor(),ID).getJSONObject("sdk_observation").getJSONObject("outbound").getString("stage").equals("frame_shape"));
        for(String stage:new String[]{"renderer_begin","sender_prepare","source_check","frame_clock","frame_shape","frame_sign","frame_encode","frame_exchange","frame_decode","response_identity","remote_view","decoder_receipt","decoder_wait","transport_finalize","owner_ready","renderer_dormant","incoming_bind","incoming_sender","incoming_decoder","ready_status"})for(String reason:new String[]{"call_failed","predicate_rejected","source_changed","callback_exception","callback_unavailable","frame_binding_rejected","frame_signature_rejected","frame_shape_rejected","frame_wire_rejected"}){
            JSONObject current=new JSONObject(v3.toString());JSONObject observed=current.getJSONObject("sdk_observation");observed.getJSONObject("outbound").put("stage",stage).put("reason",reason);
            observed.getJSONObject("first_failure").put("observation",new JSONObject(observed.getJSONObject("outbound").toString()));
            check(read(current,actor(),ID).getJSONObject("sdk_observation").getJSONObject("outbound").getString("reason").equals(reason));
        }
        for(String field:new String[]{"state","stage","reason","frame_kind"}){
            JSONObject current=new JSONObject(v3.toString());current.getJSONObject("sdk_observation").getJSONObject("outbound").put(field,"private error text");reject(()->read(current,actor(),ID));
        }
        reject(()->read(new JSONObject(v3.toString()).put("transport_attempt_original_bound",true),actor(),ID));
        JSONObject authority=new JSONObject(v3.toString());authority.getJSONObject("transport_attempt").put("qualification_claimed",true);reject(()->read(authority,actor(),ID));
        for(String damage:new String[]{"phase","failure","attempt_sequence"}){
            JSONObject impossible=new JSONObject(v3.toString());JSONObject facts=impossible.getJSONObject("transport_attempt").getJSONObject("closed_java_fields");
            if(damage.equals("phase"))facts.put("phase","CONNECT").put("outcome","RETURNED").put("attempt_sequence",1);
            if(damage.equals("failure"))facts.put("phase","CONNECT").put("outcome","FAILED").put("attempt_sequence",1).put("failure","EOF");
            if(damage.equals("attempt_sequence"))facts.put("attempt_sequence",0.5);
            reject(()->read(impossible,actor(),ID));
        }
        JSONObject unavailable=new JSONObject(v3.toString());unavailable.getJSONObject("transport_attempt").put("strict_parser_status","UNAVAILABLE").put("strict_parser_error","CALL_UNAVAILABLE").put("closed_java_fields",JSONObject.NULL);
        check(read(unavailable,actor(),ID).getJSONObject("transport_attempt").getString("strict_parser_status").equals("UNAVAILABLE"));
        JSONObject extraSdk=new JSONObject(v3.toString());extraSdk.getJSONObject("sdk_observation").put("raw_error","secret text");reject(()->read(extraSdk,actor(),ID));
        JSONObject wrongFirst=new JSONObject(v3.toString());wrongFirst.getJSONObject("sdk_observation").getJSONObject("first_failure").put("direction","incoming");reject(()->read(wrongFirst,actor(),ID));
        reject(()->read(new JSONObject(v3.toString()).put("arena_installed",false),actor(),ID));
        check(read(new JSONObject(v3.toString()).put("arena_installed",false).put("sdk_observation",JSONObject.NULL).put("transport_attempt",JSONObject.NULL),actor(),ID).isNull("sdk_observation"));
        JSONObject nativeV2=failed().put("$schema","rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v2")
            .put("stage","native_eligibility").put("native_failed_guard","receiver_restored_history").put("native_failed_reason","predicate_rejected");
        check(read(nativeV2,actor(),ID).getString("native_failed_guard").equals("receiver_restored_history"));
        for(EmbeddedDuplexPreparationDiagnostic.NativeGuard guard:EmbeddedDuplexPreparationDiagnostic.NativeGuard.values())for(String reason:new String[]{"call_failed","predicate_rejected"}){
            JSONObject observed=new JSONObject(nativeV2.toString()).put("native_failed_guard",guard.name().toLowerCase(java.util.Locale.ROOT)).put("native_failed_reason",reason);
            String beforeRead=observed.toString();check(read(observed,actor(),ID).getString("native_failed_reason").equals(reason));check(beforeRead.equals(observed.toString()));
        }
        for(String field:new String[]{"native_failed_guard","native_failed_reason"}){
            JSONObject raw=new JSONObject(nativeV2.toString()).put(field,"private exception text");reject(()->read(raw,actor(),ID));
            JSONObject partial=new JSONObject(nativeV2.toString()).put(field,JSONObject.NULL);reject(()->read(partial,actor(),ID));
        }
        reject(()->read(new JSONObject(nativeV2.toString()).put("stage","java_eligibility"),actor(),ID));
        reject(()->read(new JSONObject(nativeV2.toString()).put("reason","predicate_rejected"),actor(),ID));
        reject(()->read(new JSONObject(nativeV2.toString()).put("$schema","rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v1"),actor(),ID));
        JSONObject preNative=failed().put("$schema","rusty.quest.embedded_duplex.prepared_peer_start_diagnostic.v2")
            .put("native_failed_guard",JSONObject.NULL).put("native_failed_reason",JSONObject.NULL);
        check(read(preNative,actor(),ID).isNull("native_failed_guard"));
        JSONObject original=failed();String before=original.toString();
        JSONObject value=read(original,actor(),ID);check(before.equals(original.toString()));
        check(!value.getJSONObject("java_census").getBoolean("cleanup_proven"));
        check(value.getString("stage").equals("java_eligibility"));
        for(String key:new String[]{"app_process_generation","native_executor_generation","app_record_sha256","config_sha256"}){
            JSONObject wrong=actor();wrong.put(key,key.endsWith("generation")?21L:"d".repeat(64));reject(()->read(failed(),wrong,ID));
        }
        reject(()->read(failed(),actor(),"d".repeat(32)));
        JSONObject unknown=failed().put("original_id",JSONObject.NULL).put("original_bound",false);
        check(read(unknown,actor(),null).isNull("original_id"));reject(()->read(unknown,actor(),ID));
        reject(()->read(failed().put("stage","raw exception text"),actor(),ID));
        reject(()->read(failed().put("reason","raw exception text"),actor(),ID));
        reject(()->read(failed().put("authority_granted",true),actor(),ID));
        reject(()->read(failed().put("original_bound","true"),actor(),ID));
        reject(()->read(failed().put("extra_command","start"),actor(),ID));
        JSONObject absent=failed().put("state","unobserved").put("attempt_sequence",JSONObject.NULL)
            .put("original_id",JSONObject.NULL).put("original_bound",false).put("stage",JSONObject.NULL)
            .put("reason",JSONObject.NULL).put("arena_installed",JSONObject.NULL);
        check(read(absent,actor(),null).getString("state").equals("unobserved"));
        reject(()->read(absent,actor(),ID));
        for(EmbeddedDuplexPreparationDiagnostic.Guard guard:EmbeddedDuplexPreparationDiagnostic.Guard.values()){
            EmbeddedDuplexPreparationDiagnostic.EligibilityTrace trace=new EmbeddedDuplexPreparationDiagnostic.EligibilityTrace();
            Exception primary=new Exception("private exception text");int[] calls={0};
            try{trace.call(guard,()->{calls[0]++;throw primary;});throw new AssertionError();}catch(Exception actual){check(actual==primary);}
            String result=trace.result(null);check(calls[0]==1&&!result.contains("private exception"));
            JSONObject typed=new JSONObject(result);check(typed.getString("failure_guard").equals(guard.name().toLowerCase(java.util.Locale.ROOT)));
            JSONObject diagnostic=failed().put("java_failed_guard",typed.getString("failure_guard")).put("reason","java_guard_failed");
            check(read(diagnostic,actor(),ID).getString("java_failed_guard").equals(typed.getString("failure_guard")));
        }
        System.out.println("Preparation diagnostic Java controls passed: "+controls+"; no runtime/device calls");
    }
}
