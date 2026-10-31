package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
public final class ExperimentSessionControlCapabilityTest {
 static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
 interface Checked {void run() throws Exception;}
 static void deny(Checked f,String why)throws Exception{try{f.run();}catch(IllegalArgumentException|SecurityException e){return;}throw new AssertionError(why);}
 static JSONObject read()throws Exception{return new JSONObject().put("phase","active").put("profile_identity_bound",true).put("audio_identity_bound",true).put("generation",7L).put("runtime_epoch",3L).put("session_key","utc-ns-00000000000000000042").put("condition_id","condition-a").put("non_audio_profile_sha256",repeat("1",64)).put("activation_binding_sha256",repeat("2",64)).put("control_state","armed");}
 static String repeat(String s,int n){StringBuilder b=new StringBuilder();while(n-->0)b.append(s);return b.toString();}
 static void armObservationTransition()throws Exception {
  ExperimentSessionControlCapability cap=new ExperimentSessionControlCapability();
  JSONObject nativeArmed=read().put("generation",1L).put("runtime_epoch",1L);
  JSONObject observed=new JSONObject().put("g",1L).put("p","ARMED").put("pending",false);
  String prior=cap.offer(observed,nativeArmed,9,11,1,1000).getString("s");
  // Arm settles synchronously before the independent status observer catches up.
  observed.put("g",0L).put("p","IDLE");
  require(cap.offer(observed,nativeArmed,9,11,1,1002)==null,"cached IDLE generation zero remains readable after native Arm settles");
  deny(()->cap.admit(prior,1,"start",1003,9,11,1),"unaligned observer retires any previous session grant");
  observed.put("pending",true);
  require(cap.offer(observed,nativeArmed,9,11,1,1003)==null,"pending Arm omits session grant");
  observed.put("pending",false);
  JSONObject bounded=new JSONObject(new String(new PolarStopBleCapability().projectBoundedStatus(observed,null),StandardCharsets.UTF_8));
  require(bounded.getLong("g")==0&&bounded.getString("p").equals("IDLE")&&!bounded.has("q"),"transition publishes observed state without command capability");
  observed.put("g",1L).put("p","ARMED");
  JSONObject aligned=cap.offer(observed,nativeArmed,9,11,1,1004);
  require(aligned!=null&&!prior.equals(aligned.getString("s")),"caught-up observer receives a fresh grant");
  ExperimentSessionControlCapability.Fence fence=cap.admit(aligned.getString("s"),1,"start",1005,9,11,1);
  require(fence.generation==1&&fence.epoch==1&&fence.operation.equals("start"),"fresh aligned grant keeps exact native fence");
  deny(()->cap.admit(prior,1,"start",1005,9,11,1),"old grant cannot revive after observer catches up");
  for(Object malformed:new Object[]{0L,-1L,1.0,"1",Boolean.TRUE}){
   observed.put("g",malformed);
   deny(()->cap.offer(observed,nativeArmed,9,11,1,1006),"aligned phase still rejects malformed projected generation");
  }
  observed.put("g",1L);
  deny(()->cap.offer(observed,new JSONObject(nativeArmed.toString()).put("generation",1.0),9,11,1,1007),"native generation validation remains strict");
  System.out.println("PASS Arm native/observer transition: IDLE g0 with native armed g1 omits optional grant, retires old reference, preserves status budget and restores only a fresh exact grant after catch-up");
 }
 static JSONObject polar()throws Exception{return new JSONObject().put("closed",false).put("acc_requested",true).put("ecg_requested",false)
  .put("acc_pmd_ack_running",true).put("ecg_pmd_ack_running",false).put("acc_data_receiving_observed",true).put("ecg_data_receiving_observed",false)
  .put("pid",42).put("runtime_generation",1).put("owner_epoch",repeat("a",32))
  .put("action_revision",0).put("selected_sensor","polar-111111111111").put("connected_sensor","polar-111111111111")
  .put("acc_revision",0).put("ecg_revision",0).put("stop_acc_operation_id",repeat("b",32)).put("stop_ecg_operation_id",repeat("c",32));}
 static void polarControls()throws Exception{
  PolarStopBleCapability p=new PolarStopBleCapability();final JSONObject projection=polar(),nativeStatus=read();
  JSONObject offered=p.offer(projection,nativeStatus,9,11,2,900);final String idleRef=offered.getString("s");
  JSONObject idle=read().put("profile_identity_bound",false).put("non_audio_profile_sha256",JSONObject.NULL);
  require(p.offer(projection,idle,9,11,2,901)==null,"idle null profile omits optional Polar grant without throwing");
  deny(()->p.admit(idleRef,0,"stop-acc",902,9,11,2),"idle replacement retires old Polar capability");
  JSONObject idleBase=new JSONObject().put("v",1).put("p","IDLE").put("g",0).put("m","open");
  require(new JSONObject(new String(p.projectBoundedStatus(idleBase,null),StandardCharsets.UTF_8)).getString("p").equals("IDLE"),"idle status remains deliverable");
  final JSONObject malformedBound=read().put("non_audio_profile_sha256",JSONObject.NULL);
  deny(()->p.offer(projection,malformedBound,9,11,2,903),"bound grant still requires valid profile identity");
  offered=p.offer(projection,nativeStatus,9,11,2,1000);String ref=offered.getString("s");
  deny(()->p.admit(ref,1,"stop-acc",1100,9,11,2),"wrong Polar revision");
  deny(()->p.admit(ref,0,"start",1100,9,11,2),"session cannot use Polar grant");
  deny(()->p.admit(ref,0,"stop-acc",2501,9,11,2),"stale Polar grant");
  deny(()->p.admit(ref,0,"stop-acc",999,9,11,2),"regressed clock");
  deny(()->p.admit(ref,0,"stop-acc",1100,10,11,2),"wrong carrier");
  deny(()->p.admit(ref,0,"stop-acc",1100,9,12,2),"wrong native owner");
  deny(()->p.admit(ref,0,"stop-acc",1100,9,11,3),"wrong native owner epoch");
  JSONObject command=p.admit(ref,0,"stop-acc",1100,9,11,2);
  require(command.getString("operation_id").equals(repeat("b",32)),"owner offered action ID preserved");
  require(command.getString("selected_sensor").equals("polar-111111111111"),"actual sensor retained");
  deny(()->p.admit(ref,0,"stop-acc",1101,9,11,2),"single consumption");
  require(p.offer(projection,nativeStatus,9,11,2,1200)==null,"same consumed projection cannot reoffer");
  projection.put("action_revision",1);String next=p.offer(projection,nativeStatus,9,11,2,1300).getString("s");
  require(!ref.equals(next),"new action revision rotates");
  projection.put("acc_revision",1);String changed=p.offer(projection,nativeStatus,9,11,2,1400).getString("s");
  require(!next.equals(changed),"stream revision rotates");
  projection.put("selected_sensor","polar-222222222222");String sensor=p.offer(projection,nativeStatus,9,11,2,1450).getString("s");
  require(!changed.equals(sensor),"sensor rotates");
  projection.put("pid",true);deny(()->p.offer(projection,nativeStatus,9,11,2,1500),"Boolean PID denied");
  deny(()->p.admit(sensor,1,"stop-acc",1501,9,11,2),"malformed clears retained capability");
  final JSONObject drift=polar();drift.put("action_revision",0.0);
  deny(()->p.offer(drift,nativeStatus,9,11,2,1600),"float revision denied");
  JSONObject fresh=polar().put("action_revision",2);JSONObject cap=p.offer(fresh,nativeStatus,9,11,2,1700);
  JSONObject base=new JSONObject().put("v",1).put("p","ARMED").put("g",7).put("m","open");
  byte[] status=p.projectBoundedStatus(base,cap);require(status.length<=480&&new JSONObject(new String(status,StandardCharsets.UTF_8)).has("z"),"fits native status budget");
  base.put("padding",repeat("x",400));status=p.projectBoundedStatus(base,cap);
  require(!new JSONObject(new String(status,StandardCharsets.UTF_8)).has("z"),"no grant when budget full");
  final String omitted=cap.getString("s");deny(()->p.admit(omitted,2,"stop-acc",1701,9,11,2),"omitted grant not admissible");
  base.put("padding",repeat("x",500));final JSONObject huge=base;
  deny(()->p.projectBoundedStatus(huge,null),"oversized base never truncated");
  String id=repeat("a",16),nonce=repeat("b",16),reference=repeat("c",32),challenge=repeat("d",32),code="ABCDEFGHJKLM";
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
  String signature=ExperimentSessionBleProtocol.hex(mac.doFinal(("RQPC1|"+challenge+"|"+id+"|stop-acc|"+reference+"|0|"+nonce).getBytes(StandardCharsets.UTF_8)),16);
  require(ExperimentSessionBleProtocol.verifyPolar(code,challenge,id,"stop-acc",reference,0,nonce,signature),"Polar signed production codec");
  require(!ExperimentSessionBleProtocol.verifySession(code,challenge,id,"start",reference,1,nonce,signature),"session domain cannot reuse Polar proof");
  require(!ExperimentSessionBleProtocol.verifyPolar(code,challenge,id,"stop-ecg",reference,0,nonce,signature),"operation authenticated");
  require(!ExperimentSessionBleProtocol.verifyPolar(code,challenge,id,"stop-acc",reference,1,nonce,signature),"revision authenticated");
  JSONObject wire=new JSONObject().put("v",3).put("id",id).put("op","stop-ecg").put("s",reference).put("r",9007199254740991L).put("n",nonce).put("mac",signature);
  int wireBytes=wire.toString().getBytes(StandardCharsets.UTF_8).length;require(wireBytes<=240,"maximum signed Polar command fits240");
  require(PolarStopBleCapability.parse(wire).revision==9007199254740991L,"actual production parser maximum revision");
  for(final JSONObject invalid:new JSONObject[]{new JSONObject(wire.toString()).put("extra",1),new JSONObject(wire.toString()).put("v",true),
      new JSONObject(wire.toString()).put("r",true),new JSONObject(wire.toString()).put("r",0.0),
      new JSONObject(wire.toString()).put("op","pause"),new JSONObject(wire.toString()).put("mac","bad")})
    deny(()->PolarStopBleCapability.parse(invalid),"closed production wire negative");
  final JSONObject missing=new JSONObject(wire.toString());missing.remove("n");deny(()->PolarStopBleCapability.parse(missing),"missing actual parser field");
  JSONObject ns=read();PolarStopBleCapability sensorCap=new PolarStopBleCapability();ExperimentSessionControlCapability sessionCap=new ExperimentSessionControlCapability();
  JSONObject ps=polar(),po=sensorCap.offer(ps,ns,9,11,2,2000);
  JSONObject sessionStatus=new JSONObject().put("g",7).put("p","ARMED").put("pending",false);
  JSONObject qo=sessionCap.offer(sessionStatus,ns,9,11,2,2000,sensorCap.bindingTuple());
  sensorCap.shareReference(qo.getString("s"));po.put("s",qo.getString("s"));
  sessionStatus.put("q",qo);byte[] shared=sensorCap.projectBoundedStatus(sessionStatus,po);
  JSONObject published=new JSONObject(new String(shared,StandardCharsets.UTF_8));
  require(published.has("q")&&!published.getJSONObject("z").has("s"),"independent shared offering retains session control");
  JSONObject representative=new JSONObject("{\"o\":{\"v\":1,\"s\":\"observed\",\"e\":\"1\",\"i\":\"10000000000000\",\"g\":7,\"r\":0,\"a\":741},\"f\":\"panel\",\"p\":\"ARMED\",\"g\":7,\"r\":0,\"c\":\"condition-a\",\"rec\":true,\"co\":\"NOT_REACHED\",\"ms\":0,\"st\":\"ready\",\"k\":\"starting\",\"bt\":\"ON\",\"po\":\"CONNECTED\",\"pf\":true,\"done\":0,\"early\":0,\"a\":[\"track-ready\",\"track-ready\"],\"b\":[\"pattern-ready\",\"pattern-ready\"],\"pending\":false,\"v\":1,\"m\":\"open\"}");
  representative.put("q",qo);byte[] full=sensorCap.projectBoundedStatus(representative,po);
  JSONObject complete=new JSONObject(new String(full,StandardCharsets.UTF_8));
  require(full.length<=480&&complete.has("q")&&complete.has("z"),"full representative armed/audio/Polar connected producer state keeps both grants");
  JSONObject withInput=new JSONObject(representative.toString()).put("pi",new JSONObject().put("r",true).put("f",false).put("h",true));
  JSONObject compatible=new JSONObject(new String(sensorCap.projectBoundedStatus(withInput,po),StandardCharsets.UTF_8));
  require(compatible.has("q")&&compatible.has("z"),"optional pi cannot evict previously fitting command grants");
  final JSONObject savedNativeFence=sensorCap.nativeFence();
  for(String field:new String[]{"o","f","p","g","r","c","rec","co","ms","st","k","bt","po","pf","done","early","a","b","pending","v","m"})
    require(complete.get(field).toString().equals(representative.get(field).toString()),"mandatory producer field preserved:"+field);
  JSONObject maximum=new JSONObject(representative.toString());maximum.remove("q");
  for(String field:new String[]{"g","r","ms","done","early"})maximum.put(field,9007199254740991L);
  maximum.getJSONObject("o").put("e","9223372036854775807").put("i","9223372036854775807")
      .put("g",9007199254740991L).put("r",9007199254740991L).put("a",9007199254740991L);
  final JSONObject maxShape=maximum;int maximumBaseBytes=maximum.toString().getBytes(StandardCharsets.UTF_8).length;
  require(maximumBaseBytes>480,"full maximum numeric representation exceeds original status budget");
  deny(()->sensorCap.projectBoundedStatus(maxShape,po),"original maximum base remains unavailable, no limit raise/truncation");
  JSONObject optionalOverflow=new JSONObject().put("v",1).put("padding",repeat("x",420))
      .put("q",new JSONObject().put("v",2).put("s",repeat("a",32)).put("g",9007199254740991L).put("o",new org.json.JSONArray().put("resume")));
  require(!new JSONObject(new String(sensorCap.projectBoundedStatus(optionalOverflow,null),StandardCharsets.UTF_8)).has("q"),"original optional session overflow fallback retained");
  require(PolarStopBleCapability.matchesNativeFence(savedNativeFence,ns),"actual queued native tuple matches");
  require(!PolarStopBleCapability.matchesNativeFence(savedNativeFence,read().put("runtime_epoch",4)),"queued native epoch change denied");
  require(!PolarStopBleCapability.matchesNativeFence(savedNativeFence,read().put("activation_binding_sha256",repeat("9",64))),"queued binding change denied");
  String old=qo.getString("s");ps.put("ecg_revision",1);sensorCap.offer(ps,ns,9,11,2,2100);
  JSONObject newer=sessionCap.offer(sessionStatus,ns,9,11,2,2100,sensorCap.bindingTuple());require(!old.equals(newer.getString("s")),"sensor change rotates shared offering");
  deny(()->sessionCap.admit(old,7,"start",2101,9,11,2),"old shared session record retired");
  deny(()->sensorCap.admit(old,0,"stop-acc",2101,9,11,2),"old shared sensor record retired");
  System.out.println("PASS production Polar capability/codec: max signed command="+wireBytes+"B; full representative armed/audio/connected status with independent session+Polar grants="+full.length+"B; full safe-integer maximum base="+maximumBaseBytes+"B remains unavailable; strict parser/shared native/sensor/revision/domain negatives");
 }
 static void liveBias()throws Exception {
  ExperimentSessionControlCapability cap=new ExperimentSessionControlCapability();
  JSONObject n=read().put("breath_guidance",new JSONObject().put("identity_bound",true).put("bias_percent",20));
  JSONObject status=new JSONObject().put("g",7).put("p","ARMED").put("pending",false).put("b",new org.json.JSONArray().put("pattern-ready").put("no-pattern"));
  for(String state:new String[]{"armed","running","paused"}) {
   n.put("control_state",state);status.put("p",state.toUpperCase(java.util.Locale.ROOT));
   JSONObject offer=cap.offer(status,n,9,11,2,1000);
   require(offer.getJSONArray("o").toString().contains("set-bias"),"live bias offered in "+state);
   ExperimentSessionControlCapability.Fence fence=cap.admit(offer.getString("s"),7,"set-bias",1100,9,11,2);
   JSONObject cmd=fence.nativeCommand("bias-test",123).put("bias_percent",75);
   require(cmd.getString("operation").equals("operator-set-bias")&&cmd.getString("expected_session_key").equals(n.getString("session_key")),"same native owner and session fence");
   deny(()->cap.admit(offer.getString("s"),7,"set-bias",2501,9,11,2),"stale bias capability");
  }
  for(Object invalid:new Object[]{-1,101,1.5,true,"50"})deny(()->ExperimentSessionBleProtocol.sessionBias(new JSONObject().put("op","set-bias").put("b",invalid)),"closed bias integer");
  deny(()->ExperimentSessionBleProtocol.sessionBias(new JSONObject().put("op","set-bias")),"missing bias");
  deny(()->ExperimentSessionBleProtocol.sessionBias(new JSONObject().put("op","pause").put("b",50)),"bias is not generic field");
  String code="ABCDEFGH2345",challenge=repeat("a",32),id=repeat("b",16),ref=repeat("c",32),nonce=repeat("d",16);
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
  String sig=ExperimentSessionBleProtocol.hex(mac.doFinal(("RQEC2|"+challenge+"|"+id+"|set-bias|"+ref+"|7|"+nonce+"|75").getBytes(StandardCharsets.UTF_8)),16);
  require(ExperimentSessionBleProtocol.verifySession(code,challenge,id,"set-bias",ref,7,nonce,sig,75),"bias proof exact");
  require(!ExperimentSessionBleProtocol.verifySession(code,challenge,id,"set-bias",ref,7,nonce,sig,74),"bias tamper denied");
  JSONObject base=new JSONObject("{\"v\":1,\"m\":\"open\",\"p\":\"RUNNING\",\"g\":7,\"r\":5,\"c\":\"condition-a\",\"rec\":true,\"co\":\"DURABLE\",\"ms\":125000,\"st\":\"ready\",\"k\":\"ready\",\"pending\":false}");
  base.put("bg",new org.json.JSONArray().put(75).put(2)).put("q",cap.offer(status,n,9,11,2,1200));
  JSONObject projected=new JSONObject(new String(new PolarStopBleCapability().projectBoundedStatus(base,null),StandardCharsets.UTF_8));
  require(projected.has("q")&&projected.has("bg")&&projected.getLong("ms")==125000,"live bias grant/effective readback/clock retained");
  System.out.println("PASS live bias exact session/state capability, integer shape, HMAC percent tamper and bounded readback");
 }
 static void polarInputReadiness()throws Exception {
  JSONObject status=new JSONObject().put("p","PAUSED"),nativeStatus=read();
  ExperimentSessionBleProtocol.projectPolarInput(status,nativeStatus);
  require(!status.has("pi"),"legacy native owner omits readiness capability");
  for(boolean fresh:new boolean[]{false,true}) {
   nativeStatus.put("polar_input",new JSONObject().put("required",true).put("fresh",fresh).put("pause_latched",true));
   ExperimentSessionBleProtocol.projectPolarInput(status,nativeStatus);
   JSONObject pi=status.getJSONObject("pi");
   require(pi.getBoolean("r")&&pi.getBoolean("f")==fresh&&pi.getBoolean("h"),"exact native input projection preserves recovered hold");
  }
  status.put("padding",repeat("x",430));
  byte[] bounded=new PolarStopBleCapability().projectBoundedStatus(status,null);
  require(bounded.length<=480&&!new JSONObject(new String(bounded,StandardCharsets.UTF_8)).has("pi"),"optional input projection yields to original BLE480 bound");
  nativeStatus.getJSONObject("polar_input").put("fresh","true");
  ExperimentSessionBleProtocol.projectPolarInput(status,nativeStatus);
  require(!status.has("pi"),"malformed readiness cannot manufacture Boolean capability");
  System.out.println("PASS native Polar input projection: legacy absence, loss/recovery hold, exact booleans and480-byte optional fallback");
 }
 public static void main(String[]args)throws Exception{
  polarInputReadiness();
  armObservationTransition();
  liveBias();
  polarControls();
  ExperimentSessionControlCapability c=new ExperimentSessionControlCapability();JSONObject r=read();JSONObject status=new JSONObject().put("g",7L).put("p","ARMED").put("pending",false);
  JSONObject q=c.offer(status,r,9,11,2,1000);String ref=q.getString("s");require(q.getLong("g")==7,"actual generation");
  require(!r.has("recording"),"actual sparse native producer has phase, no recording member");
  for(String phase:new String[]{"idle","failed","unknown"})require(new ExperimentSessionControlCapability().offer(status,read().put("phase",phase).put("recording",true),9,11,2,1000)==null,"synthetic recording cannot override actual phase");
  ExperimentSessionControlCapability.Fence f=c.admit(ref,7,"start",1100,9,11,2);JSONObject command=f.nativeCommand("native-op",123456);require(command.getString("expected_binding_sha256").equals(repeat("2",64)),"binding retained");
  deny(()->c.admit(ref,8,"start",1100,9,11,2),"wrong generation");deny(()->c.admit(ref,7,"pause",1100,9,11,2),"wrong state op");deny(()->c.admit(ref,7,"start",2501,9,11,2),"expired");deny(()->c.admit(ref,7,"start",999,9,11,2),"clock regression");deny(()->c.admit(ref,7,"start",1100,10,11,2),"carrier");deny(()->c.admit(ref,7,"start",1100,9,12,2),"owner");deny(()->c.admit(ref,7,"start",1100,9,11,3),"owner epoch");
  r.put("runtime_epoch",4L);String next=c.offer(status,r,9,11,2,1200).getString("s");require(!ref.equals(next),"epoch rotates");deny(()->c.admit(ref,7,"start",1201,9,11,2),"old reference retired");
  r.put("activation_binding_sha256",repeat("3",64));String changed=c.offer(status,r,9,11,2,1300).getString("s");require(!changed.equals(next),"source rotates");
  status.put("pending",true);require(c.offer(status,r,9,11,2,1400)==null,"pending no cap");deny(()->c.admit(changed,7,"start",1401,9,11,2),"pending retires");
  status.put("pending",false);r.put("generation",7.0);deny(()->c.offer(status,r,9,11,2,1500),"coerced double");r.put("generation",7L);status.put("g",8L);require(c.offer(status,r,9,11,2,1600)==null,"generation projection mismatch");
  String id=repeat("a",16),nonce=repeat("b",16),session=repeat("c",32),challenge=repeat("d",32),code="ABCDEFGHJKLM";
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String signature=ExperimentSessionBleProtocol.hex(mac.doFinal(("RQEC2|"+challenge+"|"+id+"|start|"+session+"|7|"+nonce).getBytes(StandardCharsets.UTF_8)),16);
  require(ExperimentSessionBleProtocol.verifySession(code,challenge,id,"start",session,7,nonce,signature),"production HMAC");require(!ExperimentSessionBleProtocol.verifySession(code,challenge,id,"start",session,8,nonce,signature),"signed generation");require(!ExperimentSessionBleProtocol.verifySession(code,challenge,id,"pause",session,7,nonce,signature),"signed operation");require(!ExperimentSessionBleProtocol.verifySession(code,challenge,id,"start",repeat("e",32),7,nonce,signature),"signed session");
  JSONObject wire=new JSONObject().put("v",2).put("id",id).put("op","resume").put("s",session).put("g",9007199254740991L).put("n",nonce).put("mac",signature);require(wire.toString().getBytes(StandardCharsets.UTF_8).length<=240,"wire fits budget");require(!ExperimentSessionBleProtocol.validSessionFields(id,"save-exit",session,7,nonce),"closed operation");require(!ExperimentSessionBleProtocol.validSessionFields(id,"start",session,9007199254740992L,nonce),"unsafe generation");
  System.out.println("PASS production capability fences, retirement, RQEC2 signed tuple and maximum command budget");
 }
}
