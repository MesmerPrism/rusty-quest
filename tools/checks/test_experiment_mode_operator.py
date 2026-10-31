"""Real runtime mode owner/selection and debug journal; only platform I/O is inert."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from test_experiment_gateway_dedupe import method

PACKAGE = 'io/github/mesmerprism/rustyquest/native_renderer'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--json-jar', type=Path, required=True)
    p.add_argument('--jdk-bin', type=Path, required=True)
    p.add_argument('--android-jar', type=Path, required=True)
    p.add_argument('--source-ref', default='')
    a = p.parse_args()
    repo = Path(__file__).resolve().parents[2]
    root = repo / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / PACKAGE
    source = (root / 'BreathCompositionPanelModule.java').read_text(encoding='utf-8')
    if a.source_ref:
        source = subprocess.check_output(['git', 'show', a.source_ref+':'+(root/'BreathCompositionPanelModule.java').relative_to(repo).as_posix()], cwd=repo).decode('utf-8')
    fixture = r'''package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;
final class NativeMeshUiProducer {static int requests;static boolean desired;static boolean requestPanelVisibility(Object owner,boolean show){requests++;desired=show;return true;}}
final class ModeOperatorSelectionTest {
 static final class NativeFixture implements ExperimentControlMode.NativeAccess {
  long value=21;int selections;public long initialize(boolean s){return value;}public long read(){return value;}
  public long select(long expected,int mode){selections++;if(expected==value)value=((value>>>3)+1)*8+4+mode;return value;}}
 NativeFixture nativeOwner=new NativeFixture();ExperimentControlMode controlMode=new ExperimentControlMode(nativeOwner);
 static class Handoff {boolean active=true;void cancel(){active=false;}boolean pending(){return active;}}
 Handoff uiControlHandoff=new Handoff();static class RemotePending {String operation="open-polar";}RemotePending remotePending=new RemotePending();
 int polarRequests,revocations,rebuilds,experimentCommands;String session="recording-key",state="running";long generation=7,clock=991;boolean recording=true;
 Object hostActivity(){return this;}void replaceBreathCompositionPanelContent(String topic){rebuilds++;}void updateStatus(String message){}
 boolean rejectRemotePolarForMode(RemotePending p){revocations++;return true;}void requestPolarStartupFromModeChoice(){polarRequests++;}
 __SELECT__
 __HIDE__
 static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
 public static void main(String[] args){
  ModeOperatorSelectionTest h=new ModeOperatorSelectionTest();
  for(String state:new String[]{"running","paused"}){
   h.state=state;ExperimentControlMode.Snapshot original=h.controlMode.readSnapshot();
   h.selectExperimentControlMode(original,2);
   check(!NativeMeshUiProducer.desired,"Remote must request hide");
   check(!h.uiControlHandoff.active&&h.revocations>0,"handoff and pending Polar authority must retire");
   h.selectExperimentControlMode(h.controlMode.readSnapshot(),1);
   check(NativeMeshUiProducer.desired,"Direct must restore the actual local panel presentation path");
   check(h.experimentCommands==0&&h.session.equals("recording-key")&&h.state.equals(state)&&h.generation==7&&h.clock==991&&h.recording,"mode switch changed experiment state");
   int before=NativeMeshUiProducer.requests;h.selectExperimentControlMode(original,2);check(NativeMeshUiProducer.requests==before,"stale UI selection issued presentation effect");
  }
  System.out.println("PASS real shared UI mode selection: Direct/Remote presentation, handoff cancellation, stale CAS and session preservation");
 }
}'''.replace('__SELECT__', method(source, 'private void selectExperimentControlMode(')).replace('__HIDE__', method(source, 'private void hideRemoteControlPanel('))
    with tempfile.TemporaryDirectory(prefix='rq-mode-operator-') as t:
        out = Path(t)
        f = out / 'ModeOperatorSelectionTest.java'
        f.write_text(fixture)
        # Android stubs are compile-only: selected production adapter never invokes them.
        cp = os.pathsep.join((str(a.json_jar), str(a.android_jar)))
        subprocess.run([str(a.jdk_bin/'javac.exe'), '--release', '8', '-cp', cp, '-d', str(out), str(root/'ExperimentControlMode.java'), str(f)], check=True)
        subprocess.run([str(a.jdk_bin/'java.exe'), '-cp', str(out)+os.pathsep+cp, PACKAGE.replace('/','.')+'.ModeOperatorSelectionTest'], check=True)


if __name__ == '__main__':
    main()
