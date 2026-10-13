"""Host execution of exact production local-enrollment methods with inert Android effects."""
import argparse,os,subprocess,tempfile
from pathlib import Path
from test_experiment_gateway_dedupe import method

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[2]);parser.add_argument('--json-jar',type=Path,required=True);a=parser.parse_args()
 root=a.repo/'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
 source=(root/'ExperimentSessionCommandGateway.java').read_text()
 anchors=['JSONObject enrollFromHeadset(', 'private JSONObject enrollLocalController(', 'JSONObject localOperatorEnrollmentStatus(', 'static boolean localEnrollmentIdle(', 'private void clearLocalEnrollmentOffer(', 'private void requireLocalEnrollmentOwner(', 'JSONObject enrollFromLocalOperator(', 'void revokeFromHeadset(']
 production='\n'.join(method(source,x) for x in anchors)
 harness=r'''package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.*;import org.json.JSONObject;
final class LocalEnrollmentHarness {
 interface LocalEnrollmentGuard {void check()throws Exception;}
 interface Checked {void run()throws Exception;}
 static int checks;static long clock=1000;
 static class SystemClock {static long elapsedRealtime(){return clock;}}
 Object context=new Object();boolean packaged=true,live=true;long identity=1,epoch=7,gatewayEpoch=2,lifecycleRevision=3,localEnrollmentObserved,localEnrollmentRevision;
 String localEnrollmentReference="",routeState="disabled";Set<String> localEnrollmentIds=new HashSet<>();
 int rotations,retirements;Prefs preferences=new Prefs();Carrier carrier=new Carrier();Delegate delegate=new Delegate();
 class Carrier{boolean acceptsNativeOwner(long i,long e){return live&&i==identity&&e==epoch;}}
 class Delegate{
  JSONObject current=new JSONObject(),status=new JSONObject();
  Delegate(){try{current.put("phase","idle").put("control_state","idle").put("session_key",JSONObject.NULL);status.put("pending",false);}catch(Exception x){throw new RuntimeException(x);}}
  JSONObject sessionControlReadback(){return current;}JSONObject snapshot(){return status;}
 }
 class Prefs {
  Map<String,Object> values=new HashMap<>();Prefs(){values.put("device","device");}
  Prefs edit(){return this;}Prefs putBoolean(String k,boolean v){values.put(k,v);return this;}
  Prefs putString(String k,String v){values.put(k,v);return this;}Prefs remove(String k){values.remove(k);return this;}
  String getString(String k,String d){return values.containsKey(k)?(String)values.get(k):d;}boolean commit(){return true;}
 }
 void requireMain(){}boolean featurePackaged(Object c){return packaged;}String relayOrigin(){return "https://example.invalid";}
 JSONObject onMain(java.util.concurrent.Callable<JSONObject> action)throws Exception{return action.call();}
 void retire(){retirements++;clearLocalEnrollmentOffer();routeState="disabled";}
 void reconcile(){rotations++;gatewayEpoch++;routeState="trusted-lan-listening";}
 __PRODUCTION__
 JSONObject request(String id)throws Exception{
  JSONObject offer=localOperatorEnrollmentStatus();
  return new JSONObject().put("operation_id",id).put("trusted_lan_consent",true)
   .put("expected_native_owner_identity",identity).put("expected_runtime_epoch",epoch)
   .put("expected_gateway_lifecycle_revision",lifecycleRevision).put("expected_enrollment_reference",offer.getString("reference"));
 }
 static void check(boolean yes){checks++;if(!yes)throw new AssertionError("check "+checks);}
 static void deny(Checked c)throws Exception{checks++;try{c.run();}catch(SecurityException expected){return;}throw new AssertionError("admitted "+checks);}
 public static void main(String[]args)throws Exception{
  LocalEnrollmentHarness h=new LocalEnrollmentHarness();LocalEnrollmentGuard good=()->{};
  JSONObject first=h.request("1111111111111111");JSONObject result=h.enrollFromLocalOperator(first,good);
  String key=result.getJSONObject("credentials").getString("key");check(key.length()==64&&h.rotations==1&&h.retirements==1);check(key.equals(h.preferences.getString("key","")));
  deny(()->h.enrollFromLocalOperator(first,good));check(h.rotations==1);
  JSONObject second=h.request("2222222222222222");JSONObject next=h.enrollFromLocalOperator(second,good);
  check(!key.equals(next.getJSONObject("credentials").getString("key"))&&h.rotations==2);
  JSONObject replay=h.request("1111111111111111");deny(()->h.enrollFromLocalOperator(replay,good));
  String before=h.preferences.getString("key","");JSONObject ui=h.enrollFromHeadset(true);check(!before.equals(ui.getString("key"))&&h.rotations==3);check(ui.getString("key").equals(h.preferences.getString("key","")));
  h.revokeFromHeadset();check(h.preferences.getString("key","").isEmpty()&&h.routeState.equals("disabled"));
  for(String field:new String[]{"expected_native_owner_identity","expected_runtime_epoch","expected_gateway_lifecycle_revision"}){
   JSONObject bad=h.request("3333333333333333").put(field,99L);deny(()->h.enrollFromLocalOperator(bad,good));
  }
  JSONObject consent=h.request("3333333333333333").put("trusted_lan_consent",false);deny(()->h.enrollFromLocalOperator(consent,good));
  JSONObject stale=h.request("3333333333333333");clock+=5001;deny(()->h.enrollFromLocalOperator(stale,good));
  JSONObject future=h.request("3333333333333333");clock--;deny(()->h.enrollFromLocalOperator(future,good));clock++;
  h.delegate.status.put("pending",true);check(!h.localOperatorEnrollmentStatus().getBoolean("available"));h.delegate.status.remove("pending");check(!h.localOperatorEnrollmentStatus().getBoolean("available"));h.delegate.status.put("pending",false);
  h.delegate.current.put("phase","active");check(!h.localOperatorEnrollmentStatus().getBoolean("available"));h.delegate.current.put("phase","idle").put("session_key","live");check(!h.localOperatorEnrollmentStatus().getBoolean("available"));h.delegate.current.put("session_key",JSONObject.NULL);
  JSONObject pending=h.request("4444444444444444");h.delegate.status.put("pending",true);deny(()->h.enrollFromLocalOperator(pending,good));h.delegate.status.put("pending",false);
  JSONObject retired=h.request("5555555555555555");int count=h.rotations;deny(()->h.enrollFromLocalOperator(retired,()->{h.lifecycleRevision++;}));check(count==h.rotations);
  JSONObject race=h.request("6666666666666666");int[] calls={0};deny(()->h.enrollFromLocalOperator(race,()->{if(++calls[0]==2)h.lifecycleRevision++;}));check(h.preferences.getString("key","").isEmpty()&&h.routeState.equals("disabled"));
  JSONObject sessionRace=h.request("7777777777777777");int[] calls2={0};deny(()->h.enrollFromLocalOperator(sessionRace,()->{if(++calls2[0]==2)h.delegate.current.put("phase","active");}));check(h.preferences.getString("key","").isEmpty());
  h.delegate.current.put("phase","idle");JSONObject denied=h.request("8888888888888888");deny(()->h.enrollFromLocalOperator(denied,()->{throw new SecurityException("actor changed");}));
  JSONObject slow=h.request("9999999999999999");count=h.rotations;deny(()->h.enrollFromLocalOperator(slow,()->{clock+=5001;}));check(h.rotations==count);
  JSONObject slowAfter=h.request("aaaaaaaaaaaaaaaa");int[] calls3={0};deny(()->h.enrollFromLocalOperator(slowAfter,()->{if(++calls3[0]==2)clock+=5001;}));check(h.preferences.getString("key","").isEmpty());
  h.delegate.current.put("control_state","running");check(!h.localOperatorEnrollmentStatus().getBoolean("available"));h.delegate.current.put("control_state","idle");
  check(!ExperimentSessionBleProtocol.supportedOperation("enroll-trusted-lan"));
  System.out.println("PASS "+checks+" production enrollment host checks: shared UI/operator rotation, fresh idle, consent, replay, stale owner/epoch/revision, pending work and post-effect lifetime rollback; no Android/network effects");
 }
}
'''.replace('__PRODUCTION__',production)
 with tempfile.TemporaryDirectory(prefix='rq-local-enrollment-') as tmp:
  out=Path(tmp);fixture=out/'LocalEnrollmentHarness.java';fixture.write_text(harness)
  subprocess.run(['javac','--release','8','-encoding','UTF-8','-cp',str(a.json_jar),'-d',str(out),str(root/'ExperimentSessionBleProtocol.java'),str(root/'ExperimentSessionControlCapability.java'),str(fixture)],check=True)
  subprocess.run(['java','-cp',str(out)+os.pathsep+str(a.json_jar),'io.github.mesmerprism/rustyquest/native_renderer/LocalEnrollmentHarness'.replace('/','.')],check=True)
if __name__=='__main__':main()
