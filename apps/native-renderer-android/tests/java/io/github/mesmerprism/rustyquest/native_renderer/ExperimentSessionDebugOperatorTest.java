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
  JSONObject bias=new JSONObject(good.toString()).put("operation","set-bias").put("bias_percent",0);ExperimentSessionDebugOperator.validateShape(bias);
  bias.put("bias_percent",100);ExperimentSessionDebugOperator.validateShape(bias);
  for(Object wrong:new Object[]{-1,101,50.5,true,"50",JSONObject.NULL}) {JSONObject bad=new JSONObject(bias.toString()).put("bias_percent",wrong);deny(()->ExperimentSessionDebugOperator.validateShape(bad));}
  JSONObject noBias=new JSONObject(bias.toString());noBias.remove("bias_percent");deny(()->ExperimentSessionDebugOperator.validateShape(noBias));
  JSONObject arm=new JSONObject(good.toString()).put("operation","arm").put("requested_condition","condition-b").put("requested_bias",100);ExperimentSessionDebugOperator.validateShape(arm);arm.put("requested_bias",101);deny(()->ExperimentSessionDebugOperator.validateShape(arm));arm.put("requested_bias",100.5);deny(()->ExperimentSessionDebugOperator.validateShape(arm));
  for(String op:new String[]{"save-next","save-exit","return-vr","open-polar"})ExperimentSessionDebugOperator.validateShape(new JSONObject(good.toString()).put("operation",op));
  JSONObject enroll=new JSONObject(good.toString()).put("operation","enroll-trusted-lan")
    .put("trusted_lan_consent",true).put("expected_native_owner_identity",1L)
    .put("expected_gateway_lifecycle_revision",2L).put("expected_enrollment_reference","aabbccddeeff00112233445566778899");
  ExperimentSessionDebugOperator.validateShape(enroll);
  for(Object value:new Object[]{false,"true",1,JSONObject.NULL}){
   JSONObject bad=new JSONObject(enroll.toString()).put("trusted_lan_consent",value);deny(()->ExperimentSessionDebugOperator.validateShape(bad));
  }
  for(String key:new String[]{"expected_native_owner_identity","expected_gateway_lifecycle_revision"}){
   for(Object value:new Object[]{0,-1,"1",1.5,true,JSONObject.NULL}){
    JSONObject bad=new JSONObject(enroll.toString()).put(key,value);deny(()->ExperimentSessionDebugOperator.validateShape(bad));
   }
  }
  JSONObject missingConsent=new JSONObject(enroll.toString());missingConsent.remove("trusted_lan_consent");deny(()->ExperimentSessionDebugOperator.validateShape(missingConsent));
  JSONObject remoteGrant=new JSONObject(enroll.toString()).put("browser_enrollment",true);deny(()->ExperimentSessionDebugOperator.validateShape(remoteGrant));
  enroll.put("expected_generation",0L).put("expected_session_key","-").put("expected_condition","").put("expected_profile_sha256","").put("expected_binding_sha256","b");
  ExperimentSessionDebugOperator.validateEnrollmentActorFacts(enroll,123,10191,456,"a");
  deny(()->ExperimentSessionDebugOperator.validateEnrollmentActorFacts(enroll,124,10191,456,"a"));
  deny(()->ExperimentSessionDebugOperator.validateEnrollmentActorFacts(enroll,123,10192,456,"a"));
  deny(()->ExperimentSessionDebugOperator.validateEnrollmentActorFacts(enroll,123,10191,457,"a"));
  deny(()->ExperimentSessionDebugOperator.validateEnrollmentActorFacts(enroll,123,10191,456,"changed-apk"));
  JSONObject current=new JSONObject().put("phase","idle").put("control_state","idle").put("session_key",JSONObject.NULL).put("condition_id",JSONObject.NULL).put("non_audio_profile_sha256",JSONObject.NULL).put("runtime_epoch",3L).put("generation",0L);
  JSONObject breath=new JSONObject().put("feature_lock_active",true).put("activation_binding_matches",true).put("activation_binding_sha256","b");
  ExperimentSessionDebugOperator.validateEnrollmentSession(enroll,current,breath);
  for(String key:new String[]{"phase","session_key","runtime_epoch","generation"}){
   JSONObject bad=new JSONObject(current.toString()).put(key,key.equals("phase")?"active":key.equals("session_key")?"active-key":4L);
   deny(()->ExperimentSessionDebugOperator.validateEnrollmentSession(enroll,bad,breath));
  }
  for(String key:new String[]{"runtime_epoch","generation"})for(Object value:new Object[]{"3",3.0,true,JSONObject.NULL}){
   JSONObject bad=new JSONObject(current.toString()).put(key,value);deny(()->ExperimentSessionDebugOperator.validateEnrollmentSession(enroll,bad,breath));
  }
  JSONObject wrongBinding=new JSONObject(breath.toString()).put("activation_binding_sha256","c");deny(()->ExperimentSessionDebugOperator.validateEnrollmentSession(enroll,current,wrongBinding));
  System.out.println("PASS actual debug owner closed shape/coercion denials and canonical operation inventory");
 }
}
