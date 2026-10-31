"""Execute actual debug journal and panel mode handler with inert Android/JNI/file I/O."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_gateway_dedupe import method
from test_experiment_remote_join import STUBS, PACKAGE

HARNESS = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;import java.lang.ref.WeakReference;import android.os.Looper;
final class ExperimentSessionCommandGateway {static ExperimentSessionCommandGateway existing(){return null;}JSONObject localOperatorEnrollmentStatus(){return new JSONObject();}interface LocalEnrollmentGuard{void check()throws Exception;}JSONObject provisionHeadsetIdentity(JSONObject r,LocalEnrollmentGuard g){return new JSONObject();}JSONObject enrollFromLocalOperator(JSONObject r,LocalEnrollmentGuard g){return new JSONObject();}}
final class NativeRendererActivityComponents {static boolean nativeHost=true;static boolean isNative(Object a){return nativeHost;}}
final class ExperimentSessionBleServer {long generation=1,epoch=7;long currentGeneration(){return generation;}long currentNativeEpoch(){return epoch;}}
final class NativeMeshUiProducer {
 static boolean desired=true,ack=false,admit=true;static int requests;static long revision=1;
 static boolean requestPanelVisibility(Object host,boolean visible){requests++;if(admit){desired=visible;revision++;ack=false;}return admit;}
 static JSONObject productPanelVisibility(Object host)throws Exception{return new JSONObject().put("schema","rusty.quest.native_mesh_ui.panel_visibility.v1").put("generation",1)
  .put("request_revision",revision).put("effective_revision",ack?revision:revision-1).put("status",ack?"applied":"pending")
  .put("evidence","native-submitted-frame").put("visible_confirmation",false).put("requested_visible",desired).put("effective_visible",desired);}
 __VISIBILITY__
}
final class ControlPanelActivity {
 static int experimentCommands;static boolean feature=true;static JSONObject session;
 static String applyExperimentSessionCommand(String wire)throws Exception {JSONObject c=new JSONObject(wire);if(!c.getString("operation").equals("status")){experimentCommands++;throw new AssertionError("experiment mutation from mode handler");}
 return new JSONObject().put("command_status","accepted").put("readback",new JSONObject(session.toString())).toString();}
 static String nativeReadBreathCompositionStatus()throws Exception{return new JSONObject().put("snapshot",new JSONObject().put("feature_lock_active",feature).put("activation_binding_matches",true).put("activation_binding_sha256",B)).toString();}
 static String conditionAudioReadiness(String c){return "track-ready";}static final String B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
}
final class BreathCompositionPanelModule {
 static final class NativeFixture implements ExperimentControlMode.NativeAccess {long value=21;int switches,notifications;
  public long initialize(boolean s){return value;}public long read(){return value;}public long select(long e,int m){if(e==value&&(value&3)!=m){value=((value>>>3)+1)*8+4+m;switches++;}return value;}}
 static NativeFixture nativeOwner=new NativeFixture();static ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 static WeakReference<BreathCompositionPanelModule> remotePanel;static ExperimentSessionBleServer carrier=new ExperimentSessionBleServer();
 String controlModeHostId="host-1";boolean live=true,nativePresentation=true;static int starts,polarRevokes;static Object polarOwner=new Object();
 static class Handoff{boolean active=true;boolean pending(){return active;}void cancel(){active=false;}}static Handoff uiControlHandoff=new Handoff();
 static class RemotePending {String operation="open-polar";}static RemotePending remotePending=new RemotePending();
 boolean hostCurrent(){return live;}boolean nativePresentation(){return nativePresentation;}Object hostActivity(){return this;}
 static BreathCompositionPanelModule controllerFor(Object a){return remotePanel.get()==a?(BreathCompositionPanelModule)a:null;}
 static ExperimentSessionBleServer remoteServer(){return carrier;}
 static JSONObject queueExperimentOperator(JSONObject r){throw new AssertionError("legacy dispatch used");}static JSONObject experimentOperatorOwnerReceipt(){return new JSONObject();}
 void replaceBreathCompositionPanelContent(String t){}void updateStatus(String s){}boolean rejectRemotePolarForMode(RemotePending r){polarRevokes++;return true;}
 void requestPolarStartupFromModeChoice(){starts++;} // Actual startup/idempotence owner has its separate production fixture.
 __METHODS__
 interface Checked{void run()throws Exception;}
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 static void denied(Checked c)throws Exception{try{c.run();}catch(SecurityException good){return;}throw new AssertionError("stale owner admitted");}
 static android.content.Context context=new android.content.Context();static int sequence;
 static JSONObject call(String op)throws Exception{return new JSONObject(ExperimentSessionDebugOperator.execute(context,new JSONObject().put("schema","rusty.quest.experiment_session.operator_command.v1").put("operation",op)));}
 static JSONObject request()throws Exception{
  JSONObject read=call("control-mode-status"),scope=read.getJSONObject("mode_result"),s=scope.getJSONObject("session");
  return new JSONObject().put("schema","rusty.quest.experiment_session.operator_command.v1").put("operation","set-control-mode").put("operation_id",String.format("%016x",++sequence))
   .put("requested_mode",scope.getString("mode").equals("direct")?"remote":"direct")
   .put("expected_pid",read.getLong("pid")).put("expected_uid",read.getLong("uid")).put("expected_process_start_ticks",read.getLong("process_start_ticks"))
   .put("expected_apk_sha256",read.getString("apk_sha256")).put("expected_binding_sha256",scope.getString("binding_sha256"))
   .put("expected_runtime_epoch",s.getLong("runtime_epoch")).put("expected_generation",s.getLong("generation"))
   .put("expected_session_key",s.getString("session_key")).put("expected_condition",s.getString("condition_id")).put("expected_profile_sha256",s.getString("non_audio_profile_sha256"))
   .put("expected_host_id",scope.getString("host_id")).put("expected_carrier_generation",scope.getLong("carrier_generation")).put("expected_native_owner_epoch",scope.getLong("native_owner_epoch"))
   .put("expected_mode_packed",scope.getLong("mode_packed")).put("expected_mode_revision",scope.getLong("mode_revision")).put("operator_origin","shell-experiment-operator-v1");
 }
 static JSONObject submit(JSONObject r)throws Exception{return new JSONObject(ExperimentSessionDebugOperator.execute(context,r)).getJSONObject("mode_result");}
 static JSONObject receipt(String id)throws Exception{return submit(new JSONObject().put("schema","rusty.quest.experiment_session.operator_command.v1").put("operation","control-mode-receipt").put("operation_id",id));}
 public static void main(String[] args)throws Exception{
  BreathCompositionPanelModule h=new BreathCompositionPanelModule();remotePanel=new WeakReference<>(h);
  controlMode.onChange(()->nativeOwner.notifications++);
  ControlPanelActivity.session=new JSONObject().put("runtime_epoch",7L).put("generation",4L).put("session_key","recording-key").put("condition_id","condition-a")
   .put("non_audio_profile_sha256","profile").put("phase","running").put("control_state","running").put("recording",true).put("condition_elapsed_ms",991);
  JSONObject before=new JSONObject(ControlPanelActivity.session.toString());Object polar=polarOwner;
  call("control-mode-status");check(nativeOwner.switches==0&&NativeMeshUiProducer.requests==0&&starts==0,"read had effects");
  JSONObject r=request();String id=r.getString("operation_id"),original=r.toString();JSONObject accepted=submit(r);
  check(accepted.getString("state").equals("accepted")&&nativeOwner.value==30&&!NativeMeshUiProducer.desired,"Remote not accepted through owner");
  check(!uiControlHandoff.active&&polarRevokes==1&&nativeOwner.notifications==1,"revocation listener or handoff lost");
  check(receipt(id).getJSONObject("presentation").getString("status").equals("pending"),"intent became rendered claim");
  NativeMeshUiProducer.ack=true;check(receipt(id).getJSONObject("presentation").getString("status").equals("acknowledged"),"actual frame receipt not reconciled");
  r.put("requested_mode","direct");submit(r);check(nativeOwner.switches==1&&receipt(id).getJSONObject("original_request").toString().equals(original),"duplicate changed original or replayed");
  check(receipt("ffffffffffffffff").getString("state").equals("outcome_unknown")&&nativeOwner.switches==1,"unknown receipt mutated");
  ControlPanelActivity.session.put("control_state","paused").put("phase","paused");JSONObject direct=request();submit(direct);
  check(nativeOwner.value==37&&NativeMeshUiProducer.desired&&ControlPanelActivity.session.getString("control_state").equals("paused"),"Direct lost paused state/presentation");
  check(ControlPanelActivity.experimentCommands==0&&ControlPanelActivity.session.getString("session_key").equals(before.getString("session_key"))&&ControlPanelActivity.session.getLong("generation")==4&&ControlPanelActivity.session.getLong("condition_elapsed_ms")==991&&polarOwner==polar,"mode changed session/recording/clock/Polar owner");
  for(String field:new String[]{"expected_pid","expected_uid","expected_process_start_ticks","expected_runtime_epoch","expected_generation","expected_mode_packed","expected_mode_revision","expected_carrier_generation","expected_native_owner_epoch"}){
   JSONObject stale=request();stale.put(field,stale.getLong(field)+1);int switches=nativeOwner.switches;check(submit(stale).getString("state").equals("rejected")&&nativeOwner.switches==switches,"stale integer fence: "+field);
  }
  for(String field:new String[]{"expected_apk_sha256","expected_binding_sha256","expected_session_key","expected_condition","expected_profile_sha256","expected_host_id"}){
   JSONObject stale=request();stale.put(field,"wrong");int switches=nativeOwner.switches;check(submit(stale).getString("state").equals("rejected")&&nativeOwner.switches==switches,"stale string fence: "+field);
  }
  // Deferred requests are frozen at Binder admission, including their requested mode.
  JSONObject frozen=request();String chosen=frozen.getString("requested_mode");Thread.currentThread().setName("network");submit(frozen);
  frozen.put("requested_mode",chosen.equals("remote")?"direct":"remote");Thread.currentThread().setName("main");android.os.Handler.queue.remove().run();
  check(receipt(frozen.getString("operation_id")).getString("state").equals("accepted")&&controlMode.readSnapshot().wireName().equals(chosen),"mutable caller changed queued original");
  // Actor birth and APK byte guards are evaluated after queueing, at actual execution.
  for(String changed:new String[]{"stat","apk"}){
   JSONObject pending=request();Thread.currentThread().setName("network");submit(pending);Thread.currentThread().setName("main");
   java.nio.file.Path file=java.nio.file.Paths.get(changed.equals("stat")?"__STAT__":"__APK__");byte[] bytes=java.nio.file.Files.readAllBytes(file);
   java.nio.file.Files.write(file,changed.equals("stat")?new String(bytes,"UTF-8").replace("456","457").getBytes("UTF-8"):new byte[]{1,2,3});
   android.os.Handler.queue.remove().run();java.nio.file.Files.write(file,bytes);
   check(receipt(pending.getString("operation_id")).getString("state").equals("rejected"),"deferred "+changed+" guard missing");
  }
  // Capture Binder request, then replace the actual host before its queued main-thread execution.
  JSONObject queued=request();Thread.currentThread().setName("network");submit(queued);queued.put("requested_mode","direct");Thread.currentThread().setName("main");h.controlModeHostId="replacement";
  android.os.Handler.queue.remove().run();check(receipt(queued.getString("operation_id")).getString("state").equals("rejected"),"queued retired owner mutated");
  h.nativePresentation=false;denied(()->call("control-mode-status"));h.nativePresentation=true;
  h.live=false;denied(()->call("control-mode-status"));h.live=true;
  NativeRendererActivityComponents.nativeHost=false;denied(()->call("control-mode-status"));NativeRendererActivityComponents.nativeHost=true;
  ControlPanelActivity.feature=false;denied(()->call("control-mode-status"));ControlPanelActivity.feature=true;
  nativeOwner.value=9;denied(()->call("control-mode-status"));nativeOwner.value=37;
  // If presentation rejects a new request, old acknowledged frames never confirm this switch.
  NativeMeshUiProducer.admit=false;JSONObject noRender=request();submit(noRender);NativeMeshUiProducer.ack=true;
  check(!receipt(noRender.getString("operation_id")).getJSONObject("presentation").getString("status").equals("acknowledged"),"old render receipt reused");
  check(ControlPanelActivity.experimentCommands==0,"experiment command during reconciliation");
  System.out.println("PASS actual production mode handler/debug journal: read/set/original, immutable queued requests, all fences, live selected native owner, CAS, no replay, render pending/ack and session preservation");
 }
}'''


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--json-jar',type=Path,required=True)
    p.add_argument('--jdk-bin',type=Path,required=True)
    a=p.parse_args()
    repo=Path(__file__).resolve().parents[2]
    root=repo/'apps/native-renderer-android/panel-modules/breath-composition/src/main/java'/PACKAGE
    panel=(root/'BreathCompositionPanelModule.java').read_text(encoding="utf-8")
    debug=(repo/'apps/native-renderer-android/src/debug/java'/PACKAGE/'ExperimentSessionDebugOperator.java').read_text(encoding="utf-8")
    producer=(repo/'apps/native-renderer-android/src/main/java'/PACKAGE/'NativeMeshUiProducer.java').read_text(encoding="utf-8")
    anchors=['static JSONObject experimentControlModeStatus(', 'private static boolean modeInteger(', 'static JSONObject selectExperimentOperatorMode(', 'private void selectExperimentControlMode(', 'private void hideRemoteControlPanel(']
    fixture=HARNESS.replace('__METHODS__','\n'.join(method(panel,m) for m in anchors)).replace('__VISIBILITY__',method(producer,'static final class VisibilityReadback'))
    with tempfile.TemporaryDirectory(prefix='rq-mode-runtime-') as t:
        out=Path(t);pkg=out/PACKAGE;pkg.mkdir(parents=True)
        # File paths are the only substituted bytes in the actual debug implementation.
        stat=out/'inert-stat';stat.write_text('1 (inert) '+' '.join(['0']*19+['456']))
        apk=out/'inert.apk';apk.write_bytes(b'inert apk fixture')
        debug=debug.replace('/proc/self/stat',stat.as_posix())
        fixture=fixture.replace('__STAT__',stat.as_posix()).replace('__APK__',apk.as_posix())
        for name,body in STUBS.items():
            f=out/name;f.parent.mkdir(parents=True,exist_ok=True)
            f.write_text(body.replace('inert.apk',apk.as_posix()))
        (pkg/'ExperimentSessionDebugOperator.java').write_text(debug)
        (pkg/'BreathCompositionPanelModule.java').write_text(fixture)
        sources=[root/(n+'.java') for n in ['ExperimentControlMode','ExperimentSessionBleProtocol','ExperimentSessionControlCapability','ExperimentSessionHeadsetIdentity']]
        subprocess.run([str(a.jdk_bin/'javac.exe'),'--release','8','-cp',str(a.json_jar),'-d',str(out),*map(str,sources),*map(str,out.rglob('*.java'))],check=True)
        subprocess.run([str(a.jdk_bin/'java.exe'),'-cp',str(out)+os.pathsep+str(a.json_jar),PACKAGE.replace('/','.')+'.BreathCompositionPanelModule'],check=True)


if __name__=='__main__':main()
