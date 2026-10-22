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
