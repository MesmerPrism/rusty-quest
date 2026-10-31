"""Execute the production gateway with inert Android, carrier and socket boundaries.

No device, network, credentials or native library is used by this harness.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_gateway_dedupe import method

PACKAGE = 'io/github/mesmerprism/rustyquest/native_renderer'
STUBS = {
'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences {public final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public boolean contains(String k){return values.containsKey(k);} public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
 public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public SharedPreferences edit(){return this;}
 public SharedPreferences putString(String k,String v){values.put(k,v);return this;}public SharedPreferences putBoolean(String k,boolean v){values.put(k,v);return this;}
 public SharedPreferences remove(String k){values.remove(k);return this;}public boolean commit(){return true;}}''',
'android/content/Context.java': '''package android.content;
public class Context {public static final int MODE_PRIVATE=0;public final SharedPreferences prefs=new SharedPreferences();
 public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String k,int mode){return prefs;}
 public String getPackageName(){return "synthetic.app";}public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public android.content.pm.PackageManager.Info getApplicationInfo(){return new android.content.pm.PackageManager.Info();}}''',
'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager {public static final int GET_META_DATA=128;public Info getApplicationInfo(String p,int f){return new Info();}
 public static class Info {public String sourceDir="inert.apk";public android.os.Bundle metaData=new android.os.Bundle();}}''',
'android/os/Bundle.java': '''package android.os;public class Bundle {public boolean getBoolean(String k,boolean d){return k.equals("rusty.quest.experiment.command_gateway.enabled");}public String getString(String k,String d){return k.endsWith(".origins")?"https://controller.example":d;}}''',
'android/os/Looper.java': '''package android.os;public class Looper {static final Looper MAIN=new Looper();public static Looper getMainLooper(){return MAIN;}public static Looper myLooper(){return Thread.currentThread().getName().equals("network")?null:MAIN;}}''',
'android/os/Handler.java': '''package android.os;public class Handler {public static final java.util.concurrent.BlockingQueue<Runnable> queue=new java.util.concurrent.LinkedBlockingQueue<>();public Handler(Looper l){}public boolean post(Runnable r){if(Looper.myLooper()==null)queue.add(r);else r.run();return true;}}''',
'android/os/SystemClock.java': '''package android.os;public class SystemClock {public static long now=1000;public static long elapsedRealtime(){return now;}public static long elapsedRealtimeNanos(){return now*1000000;}}''',
'android/os/Process.java': '''package android.os;public class Process {public static int myPid(){return 123;}public static int myUid(){return 10191;}}''',
'android/os/Build.java': '''package android.os;public class Build {public static String SERIAL="unknown";public static String getSerial(){throw new SecurityException();}public static class VERSION {public static int SDK_INT=35;}}''',
}
BOUNDARIES = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class ExperimentControlMode {static final int CHOOSE=0,DIRECT=1,REMOTE=2;
 static final ExperimentControlMode INSTANCE=new ExperimentControlMode();static ExperimentControlMode process(){return INSTANCE;}
 long packed=30;void onChange(Runnable listener){}Snapshot readSnapshot(){return new Snapshot(packed);}__SNAPSHOT__}
final class ExperimentSessionBleServer {
 interface Delegate {JSONObject snapshot()throws Exception;JSONObject sessionControlReadback()throws Exception;JSONObject polarSetupReadback()throws Exception;JSONObject polarStopReadback()throws Exception;void dispatch(CommandRequest r);}
 interface GatewayReceiptListener {void receipt(JSONObject v);boolean known(JSONObject b)throws Exception;boolean reserve(JSONObject b)throws Exception;JSONObject original(String id)throws Exception;}
 static class CommandRequest {Object setupRequest;String id;CommandRequest(String i,String o,String c,int b){id=i;}CommandRequest(String i,String o,String c,int b,Object s){id=i;}CommandRequest(String i,String o,String c,int b,Object s,Object p,JSONObject f){id=i;}}
 static final ExperimentSessionBleServer INSTANCE=new ExperimentSessionBleServer();static ExperimentSessionBleServer process(android.content.Context c,Delegate d){return INSTANCE;}
 boolean live=true;GatewayReceiptListener listener;boolean acceptsNativeOwner(long i,long e){return live&&i>0&&e>0;}void attachCommandGateway(boolean b){}
 void gatewayBinding(GatewayReceiptListener l,String d,String e){listener=l;}long currentGeneration(){return 1;}
 void updateReceipt(String i,String s,String d){try{listener.receipt(new JSONObject().put("id",i).put("state",s).put("detail",d));}catch(Exception x){throw new RuntimeException(x);}}
}
final class ExperimentSessionGatewayHttpServer {static int starts,closes;ExperimentSessionGatewayHttpServer(ExperimentSessionCommandGateway g,int p){}void start(){starts++;}void close(){closes++;}}
final class ExperimentSessionDirectNetwork {ExperimentSessionDirectNetwork(android.content.Context c){}static boolean featurePackaged(android.content.Context c){return false;}JSONObject snapshot(){return new JSONObject();}JSONObject localJoinDetails(){return new JSONObject();}void start(String m){}void close(){}}
final class ExperimentSessionRelayClient {interface Endpoint{JSONObject identity()throws Exception;JSONObject exchange(JSONObject r)throws Exception;}static class Config{Config(String a,String b,String c,String d,long e){}}ExperimentSessionRelayClient(Config c,Endpoint e){}JSONObject snapshot(){return new JSONObject();}void start(){}void close(){}}
final class ControlPanelActivity {static String applyExperimentSessionCommand(String s){return "{}";}static String nativeReadBreathCompositionStatus(){return "{}";}static String conditionAudioReadiness(String s){return "unavailable";}}
final class BreathCompositionPanelModule {static JSONObject queueExperimentOperator(JSONObject s){return new JSONObject();}static JSONObject experimentOperatorOwnerReceipt(){return new JSONObject();}}
'''
HARNESS = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;import java.lang.reflect.*;
final class RemoteJoinHarness {
 static int checks,dispatches;static boolean idle=true;interface Checked{void run()throws Exception;}
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void deny(Checked c)throws Exception{checks++;try{c.run();}catch(Exception expected){Throwable cause=expected;while(cause.getCause()!=null)cause=cause.getCause();if(cause instanceof SecurityException)return;throw expected;}throw new AssertionError("unexpected admission");}
 static JSONObject join(ExperimentSessionCommandGateway g,JSONObject identity)throws Exception{return g.exchangeLan(new JSONObject().put("v",1).put("action","connect").put("device",identity.getString("device")).put("epoch",identity.getString("epoch")));}
 static JSONObject auth(JSONObject c,String action,JSONObject p)throws Exception {
  String nonce=ExperimentSessionBleProtocol.newHex(16),payload=p.toString(),controller=c.getString("controller"),epoch=c.getString("epoch");
  return new JSONObject().put("v",1).put("controller",controller).put("epoch",epoch).put("nonce",nonce).put("action",action).put("payload",payload)
   .put("mac",ExperimentSessionGatewayProtocol.hmac(c.getString("key"),"RQG1|"+controller+"|"+epoch+"|"+nonce+"|"+action+"|"+payload));
 }
 public static void main(String[] args)throws Exception {
  android.content.Context context=new android.content.Context();ExperimentControlMode mode=ExperimentControlMode.process();
  ExperimentSessionCommandGateway g=ExperimentSessionCommandGateway.process(context,new ExperimentSessionBleServer.Delegate(){
   public JSONObject snapshot()throws Exception{return new JSONObject().put("pending",false).put("p","IDLE");}
   public JSONObject sessionControlReadback()throws Exception{return new JSONObject().put("phase",idle?"idle":"active").put("control_state","idle").put("session_key",JSONObject.NULL);}
   public JSONObject polarSetupReadback(){return null;}public JSONObject polarStopReadback(){return null;}
   public void dispatch(ExperimentSessionBleServer.CommandRequest r){dispatches++;ExperimentSessionBleServer.INSTANCE.updateReceipt(r.id,"confirmed","synthetic owner callback");}
  });
  g.nativeOwnerChanged(1,7,true);JSONObject identity=g.identity();
  deny(()->g.exchange(new JSONObject().put("v",1).put("action","connect").put("device",identity.getString("device")).put("epoch",identity.getString("epoch"))));
  JSONObject localConnect=new JSONObject().put("v",1).put("action","connect").put("device",identity.getString("device")).put("epoch",identity.getString("epoch"));
  HttpRemoteJoinBoundary http=new HttpRemoteJoinBoundary(g);
  check(http.request(localConnect,"https://controller.example").startsWith("HTTP/1.1 200"),"production HTTP cannot admit local join");
  check(http.request(localConnect,"https://wrong.example").startsWith("HTTP/1.1 403")&&dispatches==0,"HTTP Origin bypass or join effect");
  JSONObject first=join(g,identity);check(first.getString("device").equals(identity.getString("device")),"no-enrollment Remote join unavailable");
  check(dispatches==0,"join dispatched command");
  mode.packed=37;deny(()->join(g,identity));deny(()->g.discoveryMetadata());mode.packed=44;deny(()->join(g,identity));deny(()->g.discoveryMetadata());mode.packed=54;
  JSONObject stale=new JSONObject(identity.toString()).put("epoch","999");deny(()->join(g,stale));
  JSONObject second=join(g,identity);check(second.length()==6&&second.getString("key").matches("[0-9a-f]{64}")&&second.getString("controller").matches("[0-9a-f]{32}"),"closed credentials");
  check(!first.getString("controller").equals(second.getString("controller")),"distinct guests");
  deny(()->g.exchange(auth(first,"status",new JSONObject()))); // ABA invalidates the original guest even without a hook.
  JSONObject third=join(g,identity);JSONObject signed=auth(third,"status",new JSONObject());g.exchange(signed);deny(()->g.exchange(signed));
  g.exchange(auth(second,"status",new JSONObject()));
  deny(()->g.exchange(auth(second,"status",new JSONObject()).put("mac",new String(new char[64]).replace('\0','0'))));
  JSONObject command=new JSONObject().put("v",1).put("id","1111111111111111").put("op","ping").put("condition","").put("bias",0).put("nonce","1111111111111111");
  g.exchange(auth(second,"command",command));g.exchange(auth(third,"command",command));check(dispatches==1,"guests lost shared original journal");
  mode.packed=61;g.controlModeChanged();check(ExperimentSessionGatewayHttpServer.closes>0,"Direct retained un-enrolled listener");deny(()->g.exchange(auth(second,"status",new JSONObject())));
  mode.packed=70;g.controlModeChanged();JSONObject after=join(g,identity);g.exchange(auth(after,"command",command));check(dispatches==1,"mode transition lost receipt");
  check(g.identity().getString("serial").equals("")&&g.identity().getBoolean("remote_available"),"unavailable serial honesty");
  JSONObject metadata=g.discoveryMetadata();check(metadata.getString("device").equals(identity.getString("device"))&&metadata.getString("serial").equals("")&&metadata.getString("epoch").equals(identity.getString("epoch")),"metadata binding");
  java.util.Map<String,java.util.List<String>> addresses=new java.util.LinkedHashMap<>();addresses.put("eth0",java.util.Arrays.asList("192.168.0.2","172.16.0.2","10.1.0.2","10.2.0.2","8.8.8.8"));addresses.put("wlan0",java.util.Arrays.asList("10.0.0.2","10.0.0.2","999.0.0.1","127.0.0.1"));
  String[] ordered=ExperimentSessionCommandGateway.orderedLocalAddresses(addresses).split("\n");check(ordered.length==4&&ordered[0].equals("http://10.0.0.2:8766/v1/gateway"),"bounded private endpoints prioritize Wi-Fi");
  deny(()->g.exchangeLan(new JSONObject().put("v",1).put("action","connect").put("device",identity.getString("device")).put("epoch",identity.getString("epoch")).put("serial","extra")));
  deny(()->join(g,new JSONObject(identity.toString()).put("device","00000000000000000000000000000000")));
  // Queue an authenticated exchange, then perform a full Remote/Direct/Remote ABA before running it.
  JSONObject queued=auth(after,"command",new JSONObject(command.toString()).put("id","2222222222222222"));Throwable[] failure={null};
  Thread network=new Thread(()->{try{g.exchange(queued);}catch(Throwable e){failure[0]=e;}},"network");network.start();
  Runnable task=android.os.Handler.queue.poll(2,java.util.concurrent.TimeUnit.SECONDS);check(task!=null,"not queued on actual owner boundary");
  mode.packed=77;g.controlModeChanged();mode.packed=86;g.controlModeChanged();task.run();network.join(2000);check(!network.isAlive()&&failure[0]!=null&&dispatches==1,"queued guest crossed mode revision");
  JSONObject expiring=join(g,identity);android.os.SystemClock.now+=119999;g.exchange(auth(expiring,"status",new JSONObject()));android.os.SystemClock.now+=119999;g.exchange(auth(expiring,"status",new JSONObject()));
  android.os.SystemClock.now+=120000;deny(()->g.exchange(auth(expiring,"status",new JSONObject())));
  for(int i=0;i<8;i++)join(g,identity);deny(()->join(g,identity));android.os.SystemClock.now+=120000;join(g,identity);
  JSONObject old=join(g,identity);g.nativeOwnerChanged(2,8,true);deny(()->g.exchange(auth(old,"status",new JSONObject())));deny(()->join(g,identity));
  JSONObject liveIdentity=g.identity();
  JSONObject provision=new JSONObject().put("hardware_serial","SYNTHETIC-ONLY").put("expected_native_owner_identity",2).put("expected_runtime_epoch",8).put("expected_gateway_lifecycle_revision",2);
  ExperimentSessionCommandGateway.LocalEnrollmentGuard good=()->{};
  for(String field:new String[]{"expected_native_owner_identity","expected_runtime_epoch","expected_gateway_lifecycle_revision"})deny(()->g.provisionHeadsetIdentity(new JSONObject(provision.toString()).put(field,99L),good));
  idle=false;deny(()->g.provisionHeadsetIdentity(provision,good));idle=true;
  deny(()->g.provisionHeadsetIdentity(provision,()->{throw new SecurityException("wrong actor");}));
  deny(()->g.provisionHeadsetIdentity(new JSONObject(provision.toString()).put("hardware_serial","unknown"),good));
  g.provisionHeadsetIdentity(provision,good);check(g.identity().getString("serial").equals("SYNTHETIC-ONLY")&&g.discoveryMetadata().getString("serial").equals("SYNTHETIC-ONLY"),"provision readback binding");
  deny(()->g.provisionHeadsetIdentity(new JSONObject(provision.toString()).put("hardware_serial","OTHER"),good));
  context.prefs.putString("controller","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").putString("key","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").putBoolean("trusted-lan",true);
  String enrolled=context.prefs.getString("key","");g.nativeOwnerChanged(3,9,true);JSONObject enrolledIdentity=g.identity();join(g,enrolledIdentity);
  g.provisionHeadsetIdentity(new JSONObject(provision.toString()).put("expected_native_owner_identity",3).put("expected_runtime_epoch",9).put("expected_gateway_lifecycle_revision",3),good);
  JSONObject enrolledClient=new JSONObject().put("controller",context.prefs.getString("controller","")).put("key",enrolled).put("epoch",enrolledIdentity.getString("epoch"));g.exchange(auth(enrolledClient,"status",new JSONObject()));
  check(context.prefs.getString("key","").equals(enrolled)&&context.prefs.getBoolean("trusted-lan",false),"join rotated legacy enrollment");
  JSONObject retiring=join(g,enrolledIdentity);g.nativeOwnerChanged(3,9,false);deny(()->g.exchange(auth(retiring,"status",new JSONObject())));deny(()->join(g,enrolledIdentity));check(!g.identity().getBoolean("remote_available")&&dispatches==1,"retired owner available or invalid joins dispatched");
  System.out.println("PASS "+checks+" production Remote join/authentication, exact shape, shared receipts, HMAC/replay, expiry/capacity, owner/mode ABA/queued revocation, serial/provisioning and legacy enrollment; inert Android/network");
 }
}
'''

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[2]);parser.add_argument('--json-jar',type=Path,required=True);a=parser.parse_args()
    root=a.repo/'apps/native-renderer-android/panel-modules/breath-composition/src/main/java'/PACKAGE
    with tempfile.TemporaryDirectory(prefix='rq-remote-join-') as tmp:
        out=Path(tmp)
        for name,source in STUBS.items():
            path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
        package=out/PACKAGE;package.mkdir(parents=True)
        snapshot=method((root/'ExperimentControlMode.java').read_text(),'static final class Snapshot')
        (package/'Boundaries.java').write_text(BOUNDARIES.replace('__SNAPSHOT__',snapshot))
        (package/'RemoteJoinHarness.java').write_text(HARNESS)
        http=(root/'ExperimentSessionGatewayHttpServer.java').read_text()
        http_fixture=r'''package io.github.mesmerprism.rustyquest.native_renderer;
import java.io.*;import java.net.Socket;import java.util.*;import java.nio.charset.StandardCharsets;import org.json.JSONObject;
final class HttpRemoteJoinBoundary {
 final ExperimentSessionCommandGateway owner;HttpRemoteJoinBoundary(ExperimentSessionCommandGateway g){owner=g;}
 static final class InertSocket extends Socket {
  final ByteArrayInputStream input;final ByteArrayOutputStream output=new ByteArrayOutputStream();
  InertSocket(String raw){input=new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));}
  @Override public InputStream getInputStream(){return input;}@Override public OutputStream getOutputStream(){return output;}@Override public void close(){}
 }
 String request(JSONObject request,String origin){String body=request.toString();InertSocket socket=new InertSocket("POST /v1/gateway HTTP/1.1\r\nHost: local\r\nOrigin: "+origin+"\r\nContent-Type: application/json\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+body);serve(socket);return new String(socket.output.toByteArray(),StandardCharsets.UTF_8);}
 __HTTP__
}
'''
        (package/'HttpRemoteJoinBoundary.java').write_text(http_fixture.replace('__HTTP__','\n'.join(method(http,anchor) for anchor in ['private void serve(', 'private static String line(', 'private static void reply('])))
        debug=(a.repo/'apps/native-renderer-android/src/debug/java'/PACKAGE/'ExperimentSessionDebugOperator.java').read_text()
        (package/'ExperimentSessionDebugOperator.java').write_text(debug)
        (package/'ExperimentSessionDebugOperatorTest.java').write_text((a.repo/'apps/native-renderer-android/tests/java'/PACKAGE/'ExperimentSessionDebugOperatorTest.java').read_text())
        names=['ExperimentSessionCommandGateway','ExperimentSessionGatewayProtocol','ExperimentSessionCommandJournal','ExperimentSessionBleProtocol','ExperimentSessionControlCapability']
        if (root/'ExperimentSessionHeadsetIdentity.java').exists():names.append('ExperimentSessionHeadsetIdentity')
        if (root/'ExperimentSessionGuestRegistry.java').exists():names.append('ExperimentSessionGuestRegistry')
        subprocess.run(['javac','--release','8','-encoding','UTF-8','-cp',str(a.json_jar),'-d',str(out),*[str(root/(n+'.java')) for n in names],*map(str,out.rglob('*.java'))],check=True)
        subprocess.run(['java','-cp',str(out)+os.pathsep+str(a.json_jar),PACKAGE.replace('/','.')+'.RemoteJoinHarness'],check=True)
        subprocess.run(['java','-cp',str(out)+os.pathsep+str(a.json_jar),PACKAGE.replace('/','.')+'.ExperimentSessionDebugOperatorTest'],check=True)
if __name__=='__main__':main()
