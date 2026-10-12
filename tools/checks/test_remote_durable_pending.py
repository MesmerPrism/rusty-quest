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
 enum Phase {ARMING,ARMED,IDLE} Phase phase=Phase.ARMED;boolean recording=true;
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
 __ARM_ADAPTER__
 static class SessionReadback {ExperimentSessionPanelCoordinator.NativeReceipt receipt=new ExperimentSessionPanelCoordinator.NativeReceipt();
  ArmAcceptance armAcceptance=new ArmAcceptance("native-arm",1,3,"condition-a",true);
  long runtimeEpoch=3,statusReadCompletedNanos=1;String commandStatus="accepted",recordingResult="none";boolean pending;
  boolean commandPending(){return pending;}}
 static class TerminalResult {boolean shouldFinish,saved;TerminalResult(boolean finish,boolean saved){this.shouldFinish=finish;this.saved=saved;}}
}
final class ExperimentSessionBleServer {
 long serverGeneration=7,nativeOwnerIdentity=1,nativeOwnerEpoch=3,nativeOwnerHighWater=1;boolean active=true;
 int updates;String state="",id="";
 long currentGeneration(){return active?serverGeneration:0;}
 boolean updateReceiptForGeneration(long generation,String id,String state,String detail){if(generation!=currentGeneration())return false;updates++;this.id=id;this.state=state;return true;}
 void stopWithReason(String reason){active=false;serverGeneration++;}
 void startFromNativeOwner(){active=true;serverGeneration++;}
 __OWNER_START__
 __OWNER_END__
}
final class ControlPanelActivity {static int starts;static void startConditionAudio(long generation,String id,String condition){starts++;}}
final class BreathCompositionPanelModule {
 boolean experimentShellDestroyed;
 static WeakReference<BreathCompositionPanelModule> remotePanel=new WeakReference<>(null);
 static ExperimentSessionBleServer server=new ExperimentSessionBleServer();
 static final ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL=new ExperimentSessionPanelCoordinator();
 static RemotePending remotePending;static RemoteStatusObserver remoteStatusObserver;static long lastRemoteStatusPollMs;
 static class Target {boolean retired;void retire(){retired=true;}}
 static class RemoteStatusObserver {final Target target=new Target();}
 static ExperimentSessionBleServer remoteServer(){return server;}
 __PENDING__
 __EXPIRE__
 __CHANGED__
 __READBACK__
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


if __name__ == "__main__":
    main()
