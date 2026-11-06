"""Production remote setup owner methods, inert platform/Polar transport."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[2]
panel=root/'apps/native-renderer-android/panel-modules'
runtime=(panel/'polar/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/PolarSensorRuntime.java').read_text()
methods=runtime[runtime.index('    private long setupRevision,setupEpoch;'):runtime.index('    /** Read-only app-lifetime projection for the experimenter home. */')]
package='io.github.mesmerprism.rustyquest.native_renderer'
java=r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.*;import android.os.SystemClock;
class PolarSensorPanel {
 int effects;JSONObject state=new JSONObject("{\"p\":true,\"b\":true,\"c\":false,\"x\":\"\",\"a\":false,\"e\":false,\"d\":[\"111111111111\"],\"sc\":false}");
 JSONObject remoteSetupProjection()throws Exception{return new JSONObject(state.toString());}
 void selectRemoteCandidate(String d)throws Exception{effects++;state.put("x",d);}
 long remoteSetupEffectNs(String op){return 1000000;}
 void handleCommand(String op)throws Exception{effects++;if(op.equals("scan"))state.put("sc",true);}
}
class PolarSensorRuntime {
 static PolarSensorRuntime instance;boolean closed,nativeLibraryReady=true;
 String projectedOwnerEpoch="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";PolarSensorPanel panel=new PolarSensorPanel();
 __METHODS__
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 interface Attempt{void run()throws Exception;}
 static void deny(Attempt a)throws Exception{try{a.run();throw new AssertionError("denial missing");}catch(SecurityException expected){}}
 JSONObject command(String id,String op)throws Exception{
  JSONObject st=remoteSetupStatus(7);return new JSONObject().put("v",4).put("id",id).put("op",op).put("s",st.getString("s")).put("g",7).put("r",st.getLong("r")).put("d",op.equals("select")?"111111111111":"").put("n","bbbbbbbbbbbbbbbb");
 }
 public static void main(String[]args)throws Exception{
  PolarSensorRuntime o=new PolarSensorRuntime();instance=o;
  JSONObject scan=o.command("1111111111111111","scan");
  JSONObject stale=new JSONObject(scan.toString()).put("r",0);deny(()->o.dispatchRemoteSetup(stale,7));check(o.panel.effects==0,"stale fence has no effects");
  check(o.dispatchRemoteSetup(scan,7).getString("state").equals("pending"),"scan admission not completion");
  o.panel.state.put("sc",false);check(o.remoteSetupReceipt(scan.getString("id"),7).getString("state").equals("confirmed"),"scan observation confirms");
  check(o.dispatchRemoteSetup(scan,7).getString("state").equals("confirmed")&&o.panel.effects==1,"duplicate retained confirmation without replay");
  deny(()->o.dispatchRemoteSetup(new JSONObject(scan.toString()).put("op","connect"),7));
  JSONObject select=o.command("2222222222222222","select");check(o.dispatchRemoteSetup(select,7).getString("state").equals("confirmed"),"selected identity observed");
  final JSONObject disconnected=o.command("3333333333333333","start-acc");deny(()->o.dispatchRemoteSetup(disconnected,7));
  o.panel.state.put("c",true);JSONObject start=o.command("3333333333333333","start-acc");
  check(o.dispatchRemoteSetup(start,7).getString("state").equals("pending"),"connection alone cannot attest stream");
  o.panel.state.put("a",true);check(o.remoteSetupReceipt(start.getString("id"),7).getString("state").equals("confirmed"),"actual data confirms");
  o.panel.state.put("p",false);final JSONObject blocked=o.command("4444444444444444","scan");int prior=o.panel.effects;deny(()->o.dispatchRemoteSetup(blocked,7));check(o.panel.effects==prior,"permission cannot be bypassed");
  o.panel.state.put("p",true);JSONObject pending=o.command("5555555555555555","scan");o.dispatchRemoteSetup(pending,7);int beforeEpoch=o.panel.effects;
  o.remoteSetupStatus(8);check(o.remoteSetupReceipt(pending.getString("id"),8).getString("state").equals("outcome_unknown")&&o.panel.effects==beforeEpoch,"native owner change retires pending without replay");
  o.closed=true;deny(()->o.remoteSetupStatus(7));
  JSONObject wire=ExperimentSessionBleProtocol.parseSetup(scan);check(wire.getInt("v")==4,"production codec");
  try{ExperimentSessionBleProtocol.parseSetup(new JSONObject(scan.toString()).put("extra",true));throw new AssertionError("open fields");}catch(IllegalArgumentException expected){}
  check(!ExperimentSessionBleProtocol.validSetupFields("1111111111111111","disconnect","a".repeat(32),7,0,"","b".repeat(16)),"closed operations");
  String challenge="cccccccccccccccccccccccccccccccc",code="operator-code";
  String canonical="RQPS1|"+challenge+"|"+scan.getString("id")+"|scan|"+scan.getString("s")+"|7|"+scan.getLong("r")+"||"+scan.getString("n");
  javax.crypto.Mac signer=javax.crypto.Mac.getInstance("HmacSHA256");signer.init(new javax.crypto.spec.SecretKeySpec(code.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
  scan.put("mac",ExperimentSessionBleProtocol.hex(signer.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)),16));
  check(ExperimentSessionBleProtocol.verifySetup(code,challenge,scan),"production setup HMAC");
  check(!ExperimentSessionBleProtocol.verifySetup(code,challenge,new JSONObject(scan.toString()).put("g",8)),"epoch proof cannot be swapped");
  check(scan.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=240,"signed command bound");
  JSONObject base=new JSONObject().put("v",1).put("m","open").put("o",new JSONObject().put("v",1).put("s","observed").put("e","7").put("i","123456789012345").put("g",7).put("r",1).put("a",750))
   .put("p","IDLE").put("g",7).put("c","").put("st","ready").put("h","vr-preparation").put("pending",false)
   .put("a",new JSONArray().put("track-ready").put("track-ready")).put("b",new JSONArray().put("pattern-ready").put("pattern-ready"));
  JSONObject setup=new JSONObject(o.panel.state.toString()).put("s",o.projectedOwnerEpoch).put("g",7).put("r",1000).put("x","111111111111")
   .put("d",new JSONArray().put("111111111111").put("222222222222").put("333333333333").put("444444444444"));
  base.put("y",setup);byte[] projected=new PolarStopBleCapability().projectBoundedStatus(base,null);
  check(projected.length<=480&&new JSONObject(new String(projected,java.nio.charset.StandardCharsets.UTF_8)).getString("h").equals("vr-preparation"),"idle four candidate/readiness/presentation bounded projection");
  System.out.println("Setup signed command bytes="+scan.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+"; idle four-candidate status bytes="+projected.length);
  System.out.println("PASS extracted production setup stale/generation/permission fences, selection, actual data confirmation, exact ID no replay, closed owner and wire fields; inert transport");
 }
}
'''
# Java 8 host controls use a local repeat helper.
java=java.replace('"a".repeat(32)','"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"').replace('"b".repeat(16)','"bbbbbbbbbbbbbbbb"').replace('__METHODS__',methods)
jar=sorted((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('json-*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='rq-remote-setup-') as tmp:
 out=Path(tmp);(out/'PolarSensorRuntime.java').write_text(java)
 os=out/'android/os';os.mkdir(parents=True)
 (os/'Looper.java').write_text('package android.os;public class Looper {static final Looper main=new Looper();public static Looper myLooper(){return main;}public static Looper getMainLooper(){return main;}}')
 (os/'SystemClock.java').write_text('package android.os;public class SystemClock {public static long elapsedRealtime(){return 1000;}}')
 protocol=panel/'breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
 subprocess.run(['javac','--release','8','-cp',str(jar),'-d',tmp,str(out/'PolarSensorRuntime.java'),str(os/'Looper.java'),str(os/'SystemClock.java'),str(protocol/'ExperimentSessionBleProtocol.java'),str(protocol/'ExperimentSessionControlCapability.java')],check=True)
 subprocess.run(['java','-cp',tmp+';'+str(jar),package+'.PolarSensorRuntime'],check=True)
