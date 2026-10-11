package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
public final class ExperimentSessionControlCapabilityTest {
 static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
 interface Checked {void run() throws Exception;}
 static void deny(Checked f,String why)throws Exception{try{f.run();}catch(IllegalArgumentException|SecurityException e){return;}throw new AssertionError(why);}
 static JSONObject read()throws Exception{return new JSONObject().put("recording",true).put("profile_identity_bound",true).put("audio_identity_bound",true).put("generation",7L).put("runtime_epoch",3L).put("session_key","utc-ns-00000000000000000042").put("condition_id","condition-a").put("non_audio_profile_sha256",repeat("1",64)).put("activation_binding_sha256",repeat("2",64)).put("control_state","armed");}
 static String repeat(String s,int n){StringBuilder b=new StringBuilder();while(n-->0)b.append(s);return b.toString();}
 public static void main(String[]args)throws Exception{
  ExperimentSessionControlCapability c=new ExperimentSessionControlCapability();JSONObject r=read();JSONObject status=new JSONObject().put("g",7L).put("p","ARMED").put("pending",false);
  JSONObject q=c.offer(status,r,9,11,2,1000);String ref=q.getString("s");require(q.getLong("g")==7,"actual generation");
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
