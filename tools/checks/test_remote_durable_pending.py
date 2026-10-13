"""Run actual remote correlation methods with inert platform/transport boundaries."""
import argparse
import pathlib
import subprocess
import tempfile


def extract(text, anchor, start=0):
    begin = text.index(anchor, start)
    opening = text.index("{", begin)
    depth = 0
    for i in range(opening, len(text)):
        depth += (text[i] == "{") - (text[i] == "}")
        if depth == 0:
            return text[begin:i + 1]
    raise AssertionError("unclosed production body: " + anchor)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2])
    parser.add_argument("--producer-receipt", type=pathlib.Path, help="optional private captured status receipt; offline replay only")
    args = parser.parse_args()
    panel = args.repo / "apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer"
    module = (panel / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    shell = (panel / "ExperimentSessionAndroidShell.java").read_text(encoding="utf-8")
    coordinator = (panel / "ExperimentSessionPanelCoordinator.java").read_text(encoding="utf-8")
    server = (panel / "ExperimentSessionBleServer.java").read_text(encoding="utf-8")
    sink_start = module.index("private static final ExperimentSessionAndroidShell.UiSink REMOTE_SINK")
    parts = {
        "pending": extract(module, "private static final class RemotePending"),
        "expire": extract(module, "private static void expireRemotePendingObservation("),
        "changed": extract(module, "static void experimentSessionBleOwnerChanged("),
        "readback": extract(module, "public void onExperimentSessionReadback(", sink_start),
        "terminal": extract(module, "public void onExperimentSessionTerminal(", sink_start),
        "arm": extract(coordinator, "static class ArmAcceptance"),
        "arm_adapter": extract(shell, "static final class ArmAcceptance"),
        "owner_start": extract(server, "synchronized boolean nativeOwnerStarted("),
        "owner_end": extract(server, "synchronized boolean nativeOwnerEnded("),
        "retain": extract(module, "public boolean retainsDurableCommand(", sink_start),
    }
    assert "expireRemotePendingObservation(now);" in module
    callback = extract(server, "public void onConnectionStateChange(")
    for marker in ("callbackStatus=", "nextState=", "callbackGeneration=", "serverGeneration=", "current="):
        assert marker in callback
    assert "device.getAddress" not in callback and '" + device' not in callback
    assert "retiredGeneration=" in extract(server, "private synchronized void stopWithReason(")
    harness = r'''
package io.github.mesmerprism.rustyquest.native_renderer;
import java.lang.ref.WeakReference;import android.os.SystemClock;
final class ExperimentSessionPanelState {
 enum Phase {ARMING,ARMED,RUNNING,PAUSED,IDLE} Phase phase=Phase.ARMED;boolean recording=true;
 long generation=1;String activeCondition="condition-a",pendingOperationId="";
}
final class ExperimentSessionPanelCoordinator {
 static class NativeReceipt {boolean accepted=true,durable=false,recording=true;long generation=1;String operationId="audio-later",activeCondition="condition-a";}
 __ARM__
 final ExperimentSessionPanelState state=new ExperimentSessionPanelState();
 ExperimentSessionPanelState snapshot(){return state;}
 void acceptNativeReadback(long epoch,NativeReceipt receipt,long read,long now,boolean accepted,ArmAcceptance arm){}
}
final class ExperimentSessionAndroidShell {
 long currentLaunchEpochForUi(){return 1L;}
 __ARM_ADAPTER__
 static class SessionReadback {ExperimentSessionPanelCoordinator.NativeReceipt receipt=new ExperimentSessionPanelCoordinator.NativeReceipt();
  ArmAcceptance armAcceptance=new ArmAcceptance("native-arm",1,3,"condition-a",true);
  long runtimeEpoch=3,statusReadCompletedNanos=1;String commandStatus="accepted",recordingResult="none";boolean pending;
  boolean commandPending(){return pending;}}
 static class TerminalResult {boolean shouldFinish,saved;TerminalResult(boolean finish,boolean saved){this.shouldFinish=finish;this.saved=saved;}}
}
final class ExperimentSessionBleServer {
 long serverGeneration=7,nativeOwnerIdentity=1,nativeOwnerEpoch=3,nativeOwnerHighWater=1;boolean active=true,commandGatewayAttached=false;
 int updates;String state="",id="";
 long currentGeneration(){return active?serverGeneration:0;}
 long currentNativeEpoch(){return nativeOwnerEpoch;}
 boolean acceptsStatusRead(long g,long e){return active&&g==serverGeneration&&e==nativeOwnerEpoch;}
 boolean updateReceiptForGeneration(long generation,String id,String state,String detail){if(generation!=currentGeneration())return false;updates++;this.id=id;this.state=state;return true;}
 void stopWithReason(String reason){active=false;serverGeneration++;}
 java.util.Map<String,Long> admissionTimes=new java.util.HashMap<>(),effectTimes=new java.util.HashMap<>();org.json.JSONObject latestOwnerReceipt;int recoveryAttempts;long recoveryToken;boolean recoveryScheduled;void scheduleRecovery(){}
 void startFromNativeOwner(){active=true;serverGeneration++;}
 __OWNER_START__
 __OWNER_END__
}
final class ExperimentSessionCommandGateway {static ExperimentSessionCommandGateway existing(){return null;} void nativeOwnerChanged(long i,long e,boolean l){}}
final class ControlPanelActivity {static int starts;static void startConditionAudio(long generation,String id,String condition){starts++;}}
final class BreathCompositionPanelModule {
 boolean experimentShellDestroyed;
 static WeakReference<BreathCompositionPanelModule> remotePanel=new WeakReference<>(null);
 static ExperimentSessionBleServer server=new ExperimentSessionBleServer();
 static final ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL=new ExperimentSessionPanelCoordinator();
 static final ExperimentSessionAndroidShell EXPERIMENT_SESSION_SHELL=new ExperimentSessionAndroidShell();
 static RemotePending remotePending;static RemoteStatusObserver remoteStatusObserver;static long lastRemoteStatusPollMs;
 static class Target {boolean retired;void retire(){retired=true;}}
 static class RemoteStatusObserver {final Target target=new Target();}
 static ExperimentSessionBleServer remoteServer(){return server;}
 __PENDING__
 __EXPIRE__
 __CHANGED__
 __READBACK__
 __RETAIN__
 __TERMINAL__
 static int checks;
 static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);checks++;}
 static void pending(String operation){SystemClock.now=1000;server=new ExperimentSessionBleServer();remotePending=new RemotePending("original-shell","native-arm",operation,1,"condition-a");EXPERIMENT_SESSION_PANEL.state.phase=ExperimentSessionPanelState.Phase.ARMED;EXPERIMENT_SESSION_PANEL.state.generation=1;EXPERIMENT_SESSION_PANEL.state.pendingOperationId="";}
 public static void main(String[]args){
  BreathCompositionPanelModule sink=new BreathCompositionPanelModule();
  for(String operation:new String[]{"arm","save-next","save-exit"}){pending(operation);RemotePending original=remotePending;expireRemotePendingObservation(31001);check(remotePending==original&&server.updates==0,"31s cannot abandon admitted "+operation);expireRemotePendingObservation(3600001);check(remotePending==original,"storage duration cannot abandon "+operation);}
  pending("return-vr");expireRemotePendingObservation(31000);check(remotePending!=null,"legacy window boundary preserved");expireRemotePendingObservation(31001);check(remotePending==null&&server.state.equals("outcome_unknown"),"bounded presentation observation remains separate");
  pending("arm");expireRemotePendingObservation(45000);ExperimentSessionAndroidShell.SessionReadback actual=new ExperimentSessionAndroidShell.SessionReadback();sink.onExperimentSessionReadback(actual);check(remotePending==null&&server.id.equals("original-shell")&&server.state.equals("confirmed"),"late durable original Arm confirms after later audio ID");int count=server.updates;sink.onExperimentSessionReadback(actual);check(server.updates==count,"duplicate callback cannot replay original outcome");
  for(int change=0;change<4;change++){pending("arm");actual=new ExperimentSessionAndroidShell.SessionReadback();actual.armAcceptance=new ExperimentSessionAndroidShell.ArmAcceptance("native-arm",change==0?2:1,change==1?4:3,change==2?"condition-b":"condition-a",change!=3);sink.onExperimentSessionReadback(actual);check(remotePending!=null&&server.updates==0,"changed Arm generation/epoch/condition/durable tuple denied");}
  pending("arm");actual=new ExperimentSessionAndroidShell.SessionReadback();actual.receipt.accepted=false;actual.receipt.operationId="native-arm";sink.onExperimentSessionReadback(actual);check(remotePending==null&&server.state.equals("rejected"),"actual correlated native rejection still terminal");
  pending("save-next");expireRemotePendingObservation(45000);actual=new ExperimentSessionAndroidShell.SessionReadback();actual.armAcceptance=null;actual.receipt.operationId="native-arm";actual.receipt.durable=true;actual.recordingResult="saved";EXPERIMENT_SESSION_PANEL.state.phase=ExperimentSessionPanelState.Phase.IDLE;sink.onExperimentSessionReadback(actual);check(remotePending==null&&server.state.equals("confirmed"),"late saved receipt confirms original SaveNext");
  pending("save-next");actual=new ExperimentSessionAndroidShell.SessionReadback();actual.armAcceptance=null;actual.receipt.operationId="native-arm";actual.receipt.accepted=false;sink.onExperimentSessionReadback(actual);check(remotePending==null&&server.state.equals("rejected"),"actual SaveNext failure remains rejection");
  pending("save-exit");expireRemotePendingObservation(45000);sink.onExperimentSessionTerminal(new ExperimentSessionAndroidShell.TerminalResult(true,true));check(remotePending==null&&server.state.equals("confirmed"),"late terminal save/shutdown confirms original SaveExit");
  pending("save-exit");sink.onExperimentSessionTerminal(new ExperimentSessionAndroidShell.TerminalResult(false,false));check(remotePending==null&&server.state.equals("outcome_unknown"),"actual uncertain shutdown remains unknown");
  pending("arm");RemotePending original=remotePending;server.stopWithReason("disabled");expireRemotePendingObservation(45000);check(remotePending==null&&server.updates==0,"actual retired carrier cannot adopt old result");
  pending("arm");original=remotePending;experimentSessionBleOwnerChanged(1,99,false);check(remotePending==original,"wrong owner epoch cannot retire original");RemoteStatusObserver observer=new RemoteStatusObserver();remoteStatusObserver=observer;experimentSessionBleOwnerChanged(1,3,false);check(remotePending==null&&observer.target.retired,"genuine native owner retirement clears mapping and observer");
  pending("arm");original=remotePending;experimentSessionBleOwnerChanged(1,3,true);check(remotePending==original,"replayed owner start cannot retire original");experimentSessionBleOwnerChanged(2,3,true);check(remotePending==null,"new admitted native owner retires original mapping");
  pending("arm");check(sink.retainsDurableCommand("native-arm",1),"original carrier mapping retains durable observation");check(!sink.retainsDurableCommand("foreign",1)&&!sink.retainsDurableCommand("native-arm",2),"wrong native ID or launch cannot extend polling");
  server.nativeOwnerEpoch++;check(!sink.retainsDurableCommand("native-arm",1),"retired native epoch cannot extend polling");
  pending("arm");server.serverGeneration++;check(!sink.retainsDurableCommand("native-arm",1),"retired carrier generation cannot extend polling");
  pending("arm");server.active=false;check(!sink.retainsDurableCommand("native-arm",1),"stopped carrier cannot extend polling");
  pending("return-vr");check(!sink.retainsDurableCommand("native-arm",1),"presentation command cannot extend polling");
  System.out.println("PASS "+checks+" extracted production durable remote mapping/late native success/error/carrier+owner retirement controls; inert platform transport");
 }
}
'''
    for name, body in parts.items():
        harness = harness.replace("__" + name.upper() + "__", body)
    assert "__" not in harness
    jars = sorted((pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1/org.json/json").glob("**/json-*.jar"))
    assert jars, "host org.json unavailable"
    with tempfile.TemporaryDirectory(prefix="rq-durable-remote-") as temporary:
        output = pathlib.Path(temporary)
        java = output / "BreathCompositionPanelModule.java"
        java.write_text(harness, encoding="utf-8")
        clock = output / "SystemClock.java"
        clock.write_text("package android.os;public final class SystemClock {public static long now;public static long elapsedRealtime(){return now;}public static long elapsedRealtimeNanos(){return now*1000000;}}", encoding="utf-8")
        subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-cp", str(jars[-1]), "-d", str(output), str(panel / "ExperimentSessionBleProtocol.java"), str(panel / "ExperimentSessionControlCapability.java"), str(clock), str(java)], check=True)
        import os
        subprocess.run(["java", "-cp", str(output) + os.pathsep + str(jars[-1]), "io.github.mesmerprism.rustyquest.native_renderer.BreathCompositionPanelModule"], check=True)

        # Use the real codec + coordinator, then the extracted actual remote callback.
        # Native responses follow the sparse producer shape; platform boundaries are inert.
        codec_start = module.index("    private static final class ExperimentSessionJsonCodec")
        codec_end = module.index("    private final class SliderControl {", codec_start)
        actual = r'''
package io.github.mesmerprism.rustyquest.native_renderer;
import java.lang.ref.WeakReference;import org.json.JSONObject;import android.os.SystemClock;
__SERVER__
final class ControlPanelActivity {
 static void startConditionAudio(long g,String id,String condition){}
 static String conditionBreathGuidanceReadiness(String condition){return "not-ready";}
}
final class ActualRemoteArmHarness {
 static ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL;
 static RemotePending remotePending;static ExperimentSessionBleServer server;
 static WeakReference<ActualRemoteArmHarness> remotePanel=new WeakReference<>(null);
 boolean experimentShellDestroyed;
 static ExperimentSessionBleServer remoteServer(){return server;}
 __PENDING__
 __CODEC__
 __READBACK__
 static int checks;
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
 static JSONObject response(String phase,Object condition,long generation,String operation)throws Exception{
  return new JSONObject().put("schema","rusty.quest.experiment_session.response.v1").put("command_status","accepted").put("reason_code","none")
   .put("readback",new JSONObject().put("runtime_epoch",3L).put("generation",generation).put("revision",7L)
    .put("phase",phase).put("condition_id",condition).put("control_state",phase.equals("idle")?"idle":"armed")
    .put("last_operation_id",operation).put("last_operation_status","accepted").put("initialization_status","ready")
    .put("inventory_status","ready").put("recording_root_status","ready").put("storage_status","ready"));
 }
 static JSONObject durable(String id,String control)throws Exception{
  JSONObject raw=response("active","condition-a",1L,"native-openxr-submitted-ready-later");
  raw.getJSONObject("readback").put("control_state",control).put("arm_operation_id",id).put("arm_generation",1L)
   .put("arm_runtime_epoch",3L).put("arm_condition","condition-a").put("arm_durable_checkpoint",true)
   .put("profile_identity_bound",true).put("audio_identity_bound",true).put("active_time_ms",30002L);
  return raw;
 }
 public static void main(String[]args)throws Exception{
  ActualRemoteArmHarness sink=new ActualRemoteArmHarness();ExperimentSessionJsonCodec codec=new ExperimentSessionJsonCodec();
  for(long at:new long[]{11270L,40000L})for(String control:new String[]{"armed","running","paused"}){
   SystemClock.now=1000;server=new ExperimentSessionBleServer();EXPERIMENT_SESSION_PANEL=new ExperimentSessionPanelCoordinator();
   ExperimentSessionPanelCoordinator.NativeCommand original=EXPERIMENT_SESSION_PANEL.arm("condition-a",100);
   remotePending=new RemotePending("original-browser",original.operationId,"arm",1,"condition-a");
   // First native epoch adoption can replace the Java pending condition with idle/none.
   JSONObject queued=response("idle",JSONObject.NULL,0L,original.operationId);queued.put("command_status","queued");
   sink.onExperimentSessionReadback(codec.parse(queued.toString(),original,true));
   check(EXPERIMENT_SESSION_PANEL.snapshot().activeCondition.equals("none"),"initial epoch/idle clears stale Java condition");
   check(remotePending!=null,"queued Arm preserves exact outer mapping");
   SystemClock.now=at;JSONObject raw=durable(original.operationId,control);
   check(!raw.getJSONObject("readback").has("recording"),"actual sparse producer omits recording");
   ExperimentSessionAndroidShell.SessionReadback observed=codec.parse(raw.toString(),null,false);
   check(observed.receipt.activeCondition.equals("condition-a"),"authoritative native condition replaces stale Java none");
   sink.onExperimentSessionReadback(observed);
   check(remotePending==null&&server.id.equals("original-browser")&&server.state.equals("confirmed"),"exact original browser Arm confirms from actual sparse proof");
   int updates=server.updates;sink.onExperimentSessionReadback(observed);check(server.updates==updates,"no duplicate outcome or command replay");
  }
  for(int change=0;change<8;change++){
   server=new ExperimentSessionBleServer();EXPERIMENT_SESSION_PANEL=new ExperimentSessionPanelCoordinator();EXPERIMENT_SESSION_PANEL.acceptRuntimeEpoch(3L);
   ExperimentSessionPanelCoordinator.NativeCommand original=EXPERIMENT_SESSION_PANEL.arm("condition-a",0);
   remotePending=new RemotePending("original-browser",original.operationId,"arm",1,"condition-a");
   JSONObject raw=durable(original.operationId,"armed");JSONObject p=raw.getJSONObject("readback");
   if(change==0)p.put("arm_operation_id","foreign-id");if(change==1)p.put("arm_generation",2L);
   if(change==2)p.put("arm_runtime_epoch",4L);if(change==3)p.put("arm_condition","condition-b");
   if(change==4)p.put("arm_durable_checkpoint",false);if(change==5)server.serverGeneration++;
   if(change==6)server.nativeOwnerEpoch++;if(change==7)p.put("generation",2L);
   sink.onExperimentSessionReadback(codec.parse(raw.toString(),null,false));
   check(remotePending!=null&&server.updates==0,"wrong original/native/carrier tuple cannot confirm");
  }
  for(Object invalid:new Object[]{JSONObject.NULL,7,true,new JSONObject(),"condition-c"}){
   JSONObject raw=durable("original","armed");raw.getJSONObject("readback").put("condition_id",invalid);
   boolean denied=false;try{codec.parse(raw.toString(),null,false);}catch(IllegalArgumentException expected){denied=true;}
   check(denied,"active condition cannot be null/coerced/foreign");
  }
  JSONObject missing=durable("original","armed");missing.getJSONObject("readback").remove("condition_id");
  boolean denied=false;try{codec.parse(missing.toString(),null,false);}catch(IllegalArgumentException expected){denied=true;}check(denied,"active native identity must be present");
  if(args.length==1){
   JSONObject captured=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8));
   JSONObject nativeResponse=captured.getJSONObject("native_response"),nativeState=nativeResponse.getJSONObject("readback");
   server=new ExperimentSessionBleServer();server.nativeOwnerEpoch=nativeState.getLong("runtime_epoch");
   EXPERIMENT_SESSION_PANEL=new ExperimentSessionPanelCoordinator();
   ExperimentSessionPanelCoordinator.NativeCommand original=EXPERIMENT_SESSION_PANEL.arm("condition-a",0);
   check(original.operationId.equals(nativeState.getString("arm_operation_id")),"captured native Arm matches original allocated owner ID");
   // Model the first authoritative epoch adoption that replaces the pending Java view.
   EXPERIMENT_SESSION_PANEL.acceptRuntimeEpoch(server.nativeOwnerEpoch);
   remotePending=new RemotePending(captured.getJSONObject("owner_receipt").getString("id"),original.operationId,"arm",original.expectedGeneration+1,"condition-a");
   check(EXPERIMENT_SESSION_PANEL.snapshot().activeCondition.equals("none"),"first epoch adoption exposes stale Java none");
   SystemClock.now=11270L;sink.onExperimentSessionReadback(codec.parse(nativeResponse.toString(),null,false));
   check(remotePending==null&&server.state.equals("confirmed")&&server.id.equals(captured.getJSONObject("owner_receipt").getString("id")),"private captured original sparse status settles same outer receipt without replay");
   System.out.println("PASS private actual sparse producer payload replay; original native/outer ID correlation; modeled first-epoch Java view; no device or transport effect");
  }
  System.out.println("PASS "+checks+" real sparse codec -> coordinator -> extracted original Arm receipt at11.27s and40s; condition/null/retirement exact joins; no transport or device effects");
 }
}
'''
        actual = actual.replace("__SERVER__", extract(harness, "final class ExperimentSessionBleServer"))
        actual = actual.replace("__PENDING__", parts["pending"])
        actual = actual.replace("__CODEC__", module[codec_start:codec_end])
        actual = actual.replace("__READBACK__", parts["readback"].replace("BreathCompositionPanelModule", "ActualRemoteArmHarness"))
        integrated = output / "ActualRemoteArmHarness.java"
        integrated.write_text(actual, encoding="utf-8")
        full = output / "actual"
        full.mkdir()
        owner = args.repo / "apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer"
        sources = [panel / (name + ".java") for name in ("ExperimentSessionPanelState", "ExperimentSessionPanelCoordinator", "ExperimentSessionPanelViewPolicy", "ExperimentSessionStatusObservation", "ExperimentSessionAndroidShell", "ExperimentSessionBleProtocol", "ExperimentSessionControlCapability")]
        subprocess.run(["javac", "--release", "8", "-cp", str(jars[-1]), "-d", str(full), *map(str, sources), str(owner / "NativeRendererWriterAcknowledgedExitPolicy.java"), str(clock), str(integrated)], check=True)
        subprocess.run(["java", "-cp", str(full) + os.pathsep + str(jars[-1]), "io.github.mesmerprism.rustyquest.native_renderer.ActualRemoteArmHarness", *([str(args.producer_receipt.resolve())] if args.producer_receipt else [])], check=True)


if __name__ == "__main__":
    main()
