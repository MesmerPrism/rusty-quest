package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
public final class ExperimentSessionDebugOperatorTest {
 interface Checked {void run()throws Exception;}
 static void deny(Checked c)throws Exception{try{c.run();}catch(SecurityException e){return;}throw new AssertionError("wrong request shape admitted");}
 static JSONObject request()throws Exception{return new JSONObject().put("schema","rusty.quest.experiment_session.operator_command.v1").put("operation","pause").put("operation_id","0123456789abcdef").put("expected_pid",123).put("expected_uid",10191).put("expected_process_start_ticks",456L).put("expected_apk_sha256","a").put("expected_binding_sha256","b").put("expected_runtime_epoch",3L).put("expected_generation",7L).put("expected_session_key","s").put("expected_condition","condition-a").put("expected_profile_sha256","p").put("operator_origin","shell-experiment-operator-v1");}
 public static void main(String[]a)throws Exception{
  JSONObject good=request();ExperimentSessionDebugOperator.validateShape(good);
  for(String key:new String[]{"expected_pid","expected_uid","expected_process_start_ticks","expected_runtime_epoch","expected_generation"}){
   for(Object wrong:new Object[]{"7",7.5,true,JSONObject.NULL,-1}){JSONObject bad=new JSONObject(good.toString()).put(key,wrong);deny(()->ExperimentSessionDebugOperator.validateShape(bad));}
  }
  for(String key:new String[]{"operation_id","expected_session_key","expected_profile_sha256","operator_origin"}){JSONObject bad=new JSONObject(good.toString()).put(key,7);deny(()->ExperimentSessionDebugOperator.validateShape(bad));}
  JSONObject extra=new JSONObject(good.toString()).put("shell","anything");deny(()->ExperimentSessionDebugOperator.validateShape(extra));
  JSONObject missing=new JSONObject(good.toString());missing.remove("expected_condition");deny(()->ExperimentSessionDebugOperator.validateShape(missing));
  JSONObject unknown=new JSONObject(good.toString()).put("operation","inject-tick");deny(()->ExperimentSessionDebugOperator.validateShape(unknown));
  JSONObject arm=new JSONObject(good.toString()).put("operation","arm").put("requested_condition","condition-b").put("requested_bias",100);ExperimentSessionDebugOperator.validateShape(arm);arm.put("requested_bias",101);deny(()->ExperimentSessionDebugOperator.validateShape(arm));arm.put("requested_bias",100.5);deny(()->ExperimentSessionDebugOperator.validateShape(arm));
  for(String op:new String[]{"save-next","save-exit","return-vr","open-polar"})ExperimentSessionDebugOperator.validateShape(new JSONObject(good.toString()).put("operation",op));
  System.out.println("PASS actual debug owner closed shape/coercion denials and canonical operation inventory");
 }
}
