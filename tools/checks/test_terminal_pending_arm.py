"""Execute original terminal routing/observation bodies with bounded carrier doubles.

The separate ExperimentSessionAndroidShellTest exercises the actual serialized shell.
These fixtures make no Android Service, native writer, audio or device claims.
"""
import argparse
import json
from pathlib import Path
import subprocess
from test_original_panel_preservation import methods, TOKEN

PKG = 'io.github.mesmerprism.rustyquest.native_renderer'


def block(source, marker):
    start = source.index(marker)
    begin = source.index('{', start)
    depth = 0
    for token in TOKEN.finditer(source, begin):
        if token.group() == '{':
            depth += 1
        elif token.group() == '}':
            depth -= 1
            if not depth:
                return source[start:token.end()]
    raise AssertionError('Unterminated ' + marker)


def fixture_nested_class(source, marker):
    """Expose only the copied declaration to this fixture's package-peer Harness.

    Production access and every copied constructor/method body remain unchanged.
    """
    copied = block(source, marker)
    declaration = marker.strip()
    if not declaration.startswith('private static final class '):
        raise AssertionError('Unexpected fixture nested declaration: ' + declaration)
    return copied.replace(declaration, declaration.removeprefix('private '), 1)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--json-jar', type=Path, required=True)
    p.add_argument('--javac', default='javac')
    p.add_argument('--java', default='java')
    p.add_argument('--damage', choices=('pending-arm-guard', 'action-ticket', 'terminal-pointer'))
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[2]
    module = root / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / PKG.replace('.', '/')
    read = lambda name: (module / (name + '.java')).read_text(encoding='utf-8')
    panel = read('BreathCompositionPanelModule')
    server = read('ExperimentSessionBleServer')
    shell = read('ExperimentSessionAndroidShell')
    application = (root / 'apps/native-renderer-android/src/main/java' / PKG.replace('.', '/') / 'NativeRendererSelfKioskApplication.java').read_text(encoding='utf-8')
    pm, sm, am = methods(panel), methods(server), methods(application)
    headers = 'package ' + PKG + ';\nimport org.json.*;\nimport java.util.*;\n'
    panel_signatures = {
        'remotePendingBlocks': 'static boolean remotePendingBlocks(String operation)',
        'dispatchRemoteSaveAndExit': 'static void dispatchRemoteSaveAndExit(ExperimentSessionBleServer.CommandRequest request,ExperimentSessionBleServer server)',
        'observeRemoteTerminal': 'static void observeRemoteTerminal(ExperimentSessionAndroidShell.TerminalResult result)',
        'observeInterruptedRemoteArm': 'static void observeInterruptedRemoteArm(ExperimentSessionAndroidShell.SessionReadback readback)',
        'onExperimentSessionTerminalAdmission': 'void onExperimentSessionTerminalAdmission(ExperimentSessionAndroidShell.TerminalAdmission admission)',
        'onExperimentSessionReadbackDuringTerminal': 'void onExperimentSessionReadbackDuringTerminal(ExperimentSessionAndroidShell.SessionReadback readback)',
    }
    server_signatures = {
        'markShellOperation': 'synchronized void markShellOperation(String id)',
        'ownerReceipt': 'synchronized JSONObject ownerReceipt()throws Exception',
        'beginTerminalObservation': 'synchronized ExperimentSessionTerminalCorrelation beginTerminalObservation(String id,long runtime)',
        'terminalObservationCurrent': 'synchronized boolean terminalObservationCurrent(ExperimentSessionTerminalCorrelation origin)',
        'abandonTerminalObservation': 'synchronized void abandonTerminalObservation(ExperimentSessionTerminalCorrelation origin)',
        'recordInterruptedArm': 'synchronized void recordInterruptedArm(ExperimentSessionTerminalCorrelation origin,String id,String nativeId,String state,String detail,boolean checkpointObserved)',
        'publishTerminalObservation': 'synchronized boolean publishTerminalObservation(ExperimentSessionTerminalCorrelation origin,ExperimentSessionAndroidShell.TerminalResult terminal)',
        'updateReceipt': 'synchronized void updateReceipt(String id,String state,String detail)',
        'safeDetail': 'private static String safeDetail(String detail)',
    }
    files = {'Harness.java': headers + '''
class Log {static void i(String a,String b){}static void w(String a,String b){}}
class SystemClock {static long elapsedRealtime(){return 100;}}
class Context {Context app=this;Context getApplicationContext(){return app;}}
class ExperimentSessionControlCapability {static class Fence {}}
class ExperimentSessionBleProtocol {static boolean isLowerHex(String id,int length){return id!=null&&id.matches("[0-9a-f]{"+length+"}");}}
class NativeRendererSoftKioskCoordinator {
 enum ActionKind {BEGIN_TERMINAL_EXIT,NONE}
 static class Action {final ActionKind kind;final long generation,homeEpisodeId;Action(ActionKind k,long g,long h){kind=k;generation=g;homeEpisodeId=h;}}
 static final NativeRendererSoftKioskCoordinator instance=new NativeRendererSoftKioskCoordinator();
 Action next=new Action(ActionKind.BEGIN_TERMINAL_EXIT,11,13);int requests;
 static NativeRendererSoftKioskCoordinator process(){return instance;}Action requestExplicitTerminalExit(){requests++;return next;}
}
class NativeRendererSelfKioskService {
 static int dispatches,resumes;static boolean resume=true;
 static void dispatchTerminal(Context c,NativeRendererSoftKioskCoordinator.Action action){dispatches++;}
 static boolean resumePendingTerminal(Context c){resumes++;return resume;}
}
class NativeRendererSelfKioskApplication extends Context {
 static boolean requestSaveAndExitFromRemote(Context context)
''' + am['requestSaveAndExitFromRemote'] + '''
 static boolean requestSaveAndExitFromRemote(Context context,java.util.function.Consumer<NativeRendererSoftKioskCoordinator.Action> observer)
''' + am['requestSaveAndExitFromRemote#2'] + '''
}
class ExperimentSessionAndroidShell {
''' + block(shell, '    static final class TerminalAdmission {') + '\n' + block(shell, '    static final class TerminalResult {') + '\n' + block(shell, '    static final class ArmAcceptance extends') + '''
 static String safe(String s){return s==null?"":s;}
 static class SessionReadback {ExperimentSessionPanelCoordinator.NativeReceipt receipt;ArmAcceptance armAcceptance;String commandStatus="accepted";long runtimeEpoch=9;}
 long launch=1;BreathCompositionPanelModule attached;TerminalAdmission delivered;
 long currentLaunchEpochForUi(){return launch;}
 boolean isCurrentTerminalAdmission(BreathCompositionPanelModule actual,TerminalAdmission value){return actual==attached&&value==delivered;}
}
class ExperimentSessionBleServer {
 static final String TAG="test";long serverGeneration=2,nativeOwnerIdentity=3,nativeOwnerEpoch=4;
 boolean available=true;String shellOperationId="";JSONObject latestOwnerReceipt,latestTerminalReceipt,interruptedArmReceipt;
 ExperimentSessionTerminalCorrelation terminalObservation;
 final Map<String,Long> admissionTimes=new HashMap<>(),effectTimes=new HashMap<>();
 final Map<Object,Peer> peers=new HashMap<>();
 static class Peer {String lastCommandId;byte[] receipt,receiptRead;}
 interface Listener {void receipt(JSONObject value);}Listener gatewayReceiptListener;
 static byte[] jsonBytes(String value){return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 boolean controlAvailable(){return available;}
 long currentGeneration(){return serverGeneration;}long currentNativeEpoch(){return nativeOwnerEpoch;}
 static class CommandRequest {String id,operation="save-exit";CommandRequest(String id){this.id=id;}}
''' + '\n'.join(signature + sm[name] for name, signature in server_signatures.items()) + '''
}
class BreathCompositionPanelModule {
 static final ExperimentSessionAndroidShell EXPERIMENT_SESSION_SHELL=new ExperimentSessionAndroidShell();
 static ExperimentSessionBleServer server;static RemotePending remotePending;static RemoteTerminalPending remoteTerminal;
 static String remoteSetupPendingId="";static Context remoteContext;
 static class Delegate {JSONObject sessionControlReadback()throws Exception{return new JSONObject().put("runtime_epoch",9).put("generation",0);}}
 static final Delegate REMOTE_DELEGATE=new Delegate();
 static ExperimentSessionBleServer remoteServer(){return server;}
 boolean live=true,experimentShellDestroyed;long experimentShellLaunchEpoch=1;
 boolean hostCurrent(){return live;}
''' + fixture_nested_class(panel, '    private static final class RemotePending {') + '\n' + fixture_nested_class(panel, '    private static final class RemoteTerminalPending {') + '\n' + '\n'.join(signature + pm[name] for name, signature in panel_signatures.items()) + '''
 static void queue(String id){if(remoteTerminal!=null||remotePendingBlocks("save-exit")){server.updateReceipt(id,"rejected","pending");return;}dispatchRemoteSaveAndExit(new ExperimentSessionBleServer.CommandRequest(id),server);}
 static void reset(){server=new ExperimentSessionBleServer();remoteTerminal=null;remoteSetupPendingId="";remoteContext=new NativeRendererSelfKioskApplication();remotePending=new RemotePending("1111111111111111","panel-arm-3","arm",2,"condition-b");EXPERIMENT_SESSION_SHELL.launch=1;}
}
public class Harness {
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static final String TERMINAL="2222222222222222";
 static void reset(){BreathCompositionPanelModule.reset();NativeRendererSelfKioskService.dispatches=0;NativeRendererSelfKioskService.resumes=0;NativeRendererSoftKioskCoordinator.process().next=new NativeRendererSoftKioskCoordinator.Action(NativeRendererSoftKioskCoordinator.ActionKind.BEGIN_TERMINAL_EXIT,11,13);}
 static ExperimentSessionAndroidShell.TerminalAdmission admission(long epoch,long guard,long home,long runtime,long gen,String op,String predecessor){return new ExperimentSessionAndroidShell.TerminalAdmission(epoch,guard,home,runtime,gen,op,predecessor);}
 static void deliver(BreathCompositionPanelModule actual,ExperimentSessionAndroidShell.TerminalAdmission value){BreathCompositionPanelModule.EXPERIMENT_SESSION_SHELL.attached=actual;BreathCompositionPanelModule.EXPERIMENT_SESSION_SHELL.delivered=value;actual.onExperimentSessionTerminalAdmission(value);}
 public static void main(String[] args)throws Exception {
  reset();BreathCompositionPanelModule.RemotePending arm=BreathCompositionPanelModule.remotePending;
  BreathCompositionPanelModule.server.markShellOperation(TERMINAL);BreathCompositionPanelModule.queue(TERMINAL);
  check(NativeRendererSelfKioskService.dispatches==1,"pending Arm must reach original terminal handler");
  check(BreathCompositionPanelModule.remotePending==arm,"terminal must preserve original Arm mapping");
  BreathCompositionPanelModule.RemoteTerminalPending pending=BreathCompositionPanelModule.remoteTerminal;
  check(pending.guardGeneration==11&&pending.homeEpisode==13,"original ticket captured before dispatch");
  check(BreathCompositionPanelModule.server.ownerReceipt().getString("id").equals(TERMINAL),"terminal receipt priority");
  BreathCompositionPanelModule.server.markShellOperation("3333333333333333");BreathCompositionPanelModule.queue("3333333333333333");check(NativeRendererSelfKioskService.dispatches==1,"second terminal cannot dispatch");
  check(BreathCompositionPanelModule.server.ownerReceipt().getString("id").equals(TERMINAL),"rejected second request cannot poison terminal");
  BreathCompositionPanelModule actual=new BreathCompositionPanelModule(); // Original real attachment may be created after transport.
  deliver(actual,admission(1,99,13,9,2,"panel-save-and-exit-7",""));
  ExperimentSessionAndroidShell.TerminalResult finalResult=new ExperimentSessionAndroidShell.TerminalResult(true,true,2,"panel-save-and-exit-7",1,"saved",9);
  BreathCompositionPanelModule.observeRemoteTerminal(finalResult);
  check(BreathCompositionPanelModule.remoteTerminal==pending,"wrong action ticket cannot settle terminal");
  deliver(actual,admission(1,11,13,9,2,"panel-save-and-exit-7",""));
  ExperimentSessionAndroidShell.SessionReadback status=new ExperimentSessionAndroidShell.SessionReadback();
  status.receipt=new ExperimentSessionPanelCoordinator.NativeReceipt("panel-hold-5",true,false,2,7,"active","arming","condition-b",true,false,"ready",true,0,0,0,0,true,true,false,0,0,0,"none",0,"none");
  status.armAcceptance=new ExperimentSessionAndroidShell.ArmAcceptance("panel-arm-3",2,9,"condition-b",true);
  actual.onExperimentSessionReadbackDuringTerminal(status);
  JSONObject interrupted=BreathCompositionPanelModule.server.ownerReceipt().getJSONObject("interrupted_operation");
  check(interrupted.getString("id").equals(arm.browserId)&&interrupted.getBoolean("durable_checkpoint_observed")&&interrupted.getString("state").equals("outcome_unknown"),"terminal status preserves first Arm ledger without claiming Armed");
  check(pending.origin.sessionGeneration==2,"authoritative admission generation overrides stale transport projection0");
  BreathCompositionPanelModule.observeRemoteTerminal(new ExperimentSessionAndroidShell.TerminalResult(true,true,2,"panel-save-and-exit-7",1,"saved",99));
  check(BreathCompositionPanelModule.remoteTerminal==pending,"stale runtime cannot settle current terminal");
  BreathCompositionPanelModule.observeRemoteTerminal(finalResult);
  JSONObject observed=BreathCompositionPanelModule.server.ownerReceipt();
  check(BreathCompositionPanelModule.remoteTerminal==null&&observed.getString("state").equals("confirmed")&&observed.getLong("session_generation")==2,"exact admitted final tuple settles");
  check(observed.getJSONObject("interrupted_operation").getString("id").equals(arm.browserId),"CLI-only actor retains prior original Arm ID without gateway");
  check(observed.getString("operator_origin").equals("shell-experiment-operator-v1"),"immutable shell origin survives rejected next request");
  reset();BreathCompositionPanelModule.queue(TERMINAL);pending=BreathCompositionPanelModule.remoteTerminal;
  actual=new BreathCompositionPanelModule();actual.live=false;deliver(actual,admission(1,11,13,9,2,"panel-save-and-exit-7",""));
  check(pending.origin.sessionGeneration==-1,"retired presentation cannot bind");actual.live=true;
  deliver(actual,admission(2,11,13,9,2,"panel-save-and-exit-7",""));check(pending.origin.sessionGeneration==-1,"replacement launch cannot bind");
  deliver(actual,admission(1,11,13,99,2,"panel-save-and-exit-7",""));check(pending.origin.sessionGeneration==-1,"replacement runtime cannot bind");
  ExperimentSessionBleServer server=BreathCompositionPanelModule.server;
  server.abandonTerminalObservation(pending.origin);ExperimentSessionTerminalCorrelation replacement=server.beginTerminalObservation("4444444444444444",9);server.updateReceipt(replacement.requestId,"pending","new");
  pending.origin.admit(9,2,"panel-save-and-exit-7","");
  check(!server.publishTerminalObservation(pending.origin,finalResult),"abandoned observation cannot publish into replacement");
  check(server.ownerReceipt().getString("id").equals(replacement.requestId),"old result cannot clone replacement receipt");
  check(replacement.admit(9,2,"panel-save-and-exit-8",""),"initial canonical command observed");
  check(!replacement.admit(9,2,"panel-save-and-exit-9",""),"unknown operation cannot silently rebind");
  check(replacement.admit(9,2,"panel-save-and-exit-9","panel-save-and-exit-8"),"proven original successor links predecessor");
  check(replacement.observe(2,3,4,9,2,"panel-save-and-exit-8",1,true,true)==null,"rejected predecessor cannot finalize successor");
  check(replacement.observe(2,3,4,9,2,"panel-save-and-exit-9",1,true,true)!=null,"exact successor can settle");
  reset();final int[] callbacks={0};
  check(NativeRendererSelfKioskApplication.requestSaveAndExitFromRemote(new NativeRendererSelfKioskApplication(),action->{callbacks[0]++;throw new IllegalStateException("observer");}),"observer failure cannot strand action");
  check(callbacks[0]==1&&NativeRendererSelfKioskService.dispatches==1,"existing Service still dispatches once");
  NativeRendererSoftKioskCoordinator.process().next=new NativeRendererSoftKioskCoordinator.Action(NativeRendererSoftKioskCoordinator.ActionKind.NONE,0,0);
  NativeRendererSelfKioskApplication.requestSaveAndExitFromRemote(new NativeRendererSelfKioskApplication(),action->{callbacks[0]++;});
  check(callbacks[0]==1&&NativeRendererSelfKioskService.resumes==1&&NativeRendererSelfKioskService.dispatches==1,"already pending action never guesses a new ticket");
  check(!NativeRendererSelfKioskApplication.requestSaveAndExitFromRemote(new Context()),"unselected Application rejects without effect");
  System.out.println("PASS "+checks);
 }
}
''', 'ExperimentSessionTerminalCorrelation.java': read('ExperimentSessionTerminalCorrelation'),
        'ExperimentSessionPanelCoordinator.java': read('ExperimentSessionPanelCoordinator'),
        'ExperimentSessionPanelState.java': read('ExperimentSessionPanelState')}
    if a.damage == 'pending-arm-guard':
        files['Harness.java'] = files['Harness.java'].replace('remotePending!=null&&!("save-exit".equals(operation)&&"arm".equals(remotePending.operation))', 'remotePending!=null')
    elif a.damage == 'action-ticket':
        files['Harness.java'] = files['Harness.java'].replace('value.guardGeneration==guardGeneration', 'value.guardGeneration>0')
    elif a.damage == 'terminal-pointer':
        files['Harness.java'] = files['Harness.java'].replace('origin==terminalObservation&&', '')
    paths = []
    for name, content in files.items():
        f = a.output / name
        f.write_text(content, encoding='utf-8')
        paths.append(str(f))
    classes = a.output / 'classes'
    compiled = subprocess.run([a.javac, '--release', '8', '-encoding', 'UTF-8', '-cp', str(a.json_jar), '-d', str(classes), *paths], capture_output=True, text=True)
    (a.output / 'compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
    if compiled.returncode:
        raise RuntimeError(compiled.stderr)
    result = subprocess.run([a.java, '-cp', str(classes) + ';' + str(a.json_jar), PKG + '.Harness'], capture_output=True, text=True)
    (a.output / 'fixture.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    expected = {'pending-arm-guard': 'pending Arm must reach original terminal handler', 'action-ticket': 'wrong action ticket cannot settle terminal', 'terminal-pointer': 'abandoned observation cannot publish into replacement'}
    passed = result.returncode == 0 if not a.damage else result.returncode != 0 and expected[a.damage] in result.stderr
    (a.output / 'RESULT.json').write_text(json.dumps({'passed': passed, 'negative_fixture': a.damage, 'output': result.stdout, 'actual_module_bodies': list(panel_signatures), 'actual_server_bodies': list(server_signatures), 'actual_application_overloads': 2, 'scope': 'Actual routing and observation bodies with bounded carrier/Service/shell identity doubles. Actual serialized shell is covered separately by ExperimentSessionAndroidShellTest. No Android lifecycle, native writer, audio or device proof.'}, indent=2), encoding='utf-8')
    if not passed:
        raise RuntimeError(result.stderr)
    print('negative rejected: ' + a.damage if a.damage else result.stdout.strip())


if __name__ == '__main__':
    main()
