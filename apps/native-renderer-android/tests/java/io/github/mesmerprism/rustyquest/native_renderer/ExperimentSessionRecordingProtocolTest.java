package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
public final class ExperimentSessionRecordingProtocolTest {
 static final String C="11111111111111111111111111111111",K="3333333333333333333333333333333333333333333333333333333333333333",D="22222222222222222222222222222222";
 static JSONObject request(String action,int n)throws Exception {String nonce=String.format("%032x",n),p="{\"query\":\"list\",\"before\":\"\",\"limit\":16}";return new JSONObject().put("v",1).put("controller",C).put("epoch","42").put("nonce",nonce).put("action",action).put("payload",p).put("mac",ExperimentSessionGatewayProtocol.hmac(K,"RQG1|"+C+"|42|"+nonce+"|"+action+"|"+p));}
 interface Trial{void run()throws Exception;}static void denied(Trial t)throws Exception{try{t.run();throw new AssertionError("admitted");}catch(SecurityException|IllegalArgumentException expected){}}
 public static void main(String[] a)throws Exception {
  int[] count={0};ExperimentSessionGatewayProtocol p=new ExperimentSessionGatewayProtocol(C,K,D,42,(action,body)->{count[0]++;return new JSONObject().put("state","ready");});
  denied(()->p.exchange(request("recordings",1)));if(count[0]!=0)throw new AssertionError("relay reached recording IO");
  JSONObject r=p.exchangeLan(request("recordings",2));if(!r.getString("action").equals("recordings"))throw new AssertionError("LAN read unavailable");
  denied(()->p.exchangeLan(request("recordings",2)));denied(()->p.exchangeLan(request("recordings",3).put("mac","0")));p.retire();denied(()->p.exchangeLan(request("recordings",4)));
  ExperimentSessionGatewayProtocol huge=new ExperimentSessionGatewayProtocol(C,K,D,42,(x,b)->new JSONObject().put("bytes",new String(new char[65500]).replace('\0','x')));denied(()->huge.exchange(request("status",8)));
  System.out.println("PASS LAN-only authenticated recording action, replay/HMAC/retirement and complete signed envelope bound");
 }
}