"""Execute production route callback and terminal identity joins with bounded doubles.

No Android lifecycle, Vulkan submission, disk durability or pixels are simulated.
"""
import argparse
import json
from pathlib import Path
import subprocess
from test_original_panel_preservation import methods

PKG = 'io.github.mesmerprism.rustyquest.native_renderer'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--javac', default='javac')
    p.add_argument('--java', default='java')
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[2]
    module = root / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / PKG.replace('.', '/')
    source = (module / 'BreathCompositionPanelModule.java').read_text(encoding='utf-8')
    route = methods(source)['queueNativePanelIntent']
    terminal = methods(source)['observeRemoteTerminal']
    producer = (root / 'apps/native-renderer-android/src/main/java' / PKG.replace('.', '/') / 'NativeMeshUiProducer.java').read_text(encoding='utf-8')
    begin = producer.index('    static final class ProductRouteIdentity {')
    end = producer.index('    static Object captureProductRouteOwner', begin)
    identity = producer[begin:end]
    files = {
        'Intent.java': 'package '+PKG+'; class Intent { String value; Intent(String v){value=v;} Intent(Intent i){value=i.value;} }',
        'Activity.java': 'package '+PKG+'; class Activity { Runnable pending; void runOnUiThread(Runnable r){pending=r;} void drain(){Runnable r=pending;pending=null;r.run();} }',
        'NativeProductPanelActivity.java': 'package '+PKG+'; class NativeProductPanelActivity extends Activity {}',
        'Harness.java': 'package '+PKG+';\n'+'''
import java.lang.ref.WeakReference;
class Log {static void w(String t,String m){}}
class NativeRendererActivityComponents {
 static final String PRODUCT_NATIVE="io.github.mesmerprism.rustyquest.native_renderer.NativeProductPanelActivity";
 static boolean selected=true; static boolean isNative(Activity a){return selected;}
}
class NativeMeshUiProducer {
''' + identity + '''
 static Object producer=new Object(); static Activity host; static long generation=1; static boolean active=true; static int shown;
 static Object captureProductRouteOwner(Activity a,long g){return a==host&&g==generation?new ProductRouteIdentity(producer,a,g):null;}
 static boolean productRouteOwnerCurrent(Object v,Activity a){return active&&((ProductRouteIdentity)v).matches(producer,a,generation);}
 static boolean requestPanelVisibility(Activity a,boolean b){shown++;return true;}
}
class ExperimentSessionAndroidShell {
 static class TerminalResult {long runtimeEpoch,sessionGeneration;String operationId="panel-save-and-exit-7";long shutdownAckRevision=9;boolean shouldFinish=true,saved=true;TerminalResult(long e,long g){runtimeEpoch=e;sessionGeneration=g;}}
}
class ExperimentSessionBleServer {
 int published;
 boolean publishTerminalObservation(ExperimentSessionTerminalCorrelation c,ExperimentSessionAndroidShell.TerminalResult r){
  if(c==null||r==null)return false;
  if(c.observe(2,3,4,r.runtimeEpoch,r.sessionGeneration,r.operationId,r.shutdownAckRevision,r.shouldFinish,r.saved)==null)return false;
  published++;return true;
 }
}
class BreathCompositionPanelModule {
 static final String TAG="test"; static WeakReference<BreathCompositionPanelModule> remotePanel;
 static BreathCompositionPanelModule controllerFor(Activity a){return remotePanel.get();}
 boolean live=true; int callbacks; String value;
 boolean hostCurrent(){return live;} boolean nativePresentation(){return true;}
 void onNewIntent(Intent i){callbacks++;value=i.value;}
 static class RemotePending {String operation="save-exit";ExperimentSessionTerminalCorrelation terminalOrigin;RemotePending(ExperimentSessionTerminalCorrelation c){terminalOrigin=c;}}
 static RemotePending remotePending;static ExperimentSessionBleServer server=new ExperimentSessionBleServer();
 static ExperimentSessionBleServer remoteServer(){return server;}
 static void observeRemoteTerminal(ExperimentSessionAndroidShell.TerminalResult result)
''' + terminal + '''
 static int queueNativePanelIntent(Activity actual,Intent intent,long producerGeneration)
''' + route + '''
}
class Harness {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static ExperimentSessionTerminalCorrelation origin(String tag){return new ExperimentSessionTerminalCorrelation("0123456789abcdef",2,3,4,5,6,tag);}
 public static void main(String[] args){
  NativeProductPanelActivity host=new NativeProductPanelActivity();NativeMeshUiProducer.host=host;
  BreathCompositionPanelModule owner=new BreathCompositionPanelModule();BreathCompositionPanelModule.remotePanel=new WeakReference<>(owner);
  Intent original=new Intent("developer:route17");
  check(BreathCompositionPanelModule.queueNativePanelIntent(host,original,1)==1);original.value="changed";host.drain();
  check(owner.callbacks==1&&"developer:route17".equals(owner.value)&&NativeMeshUiProducer.shown==1);
  check(BreathCompositionPanelModule.queueNativePanelIntent(new Activity(),original,1)==0);
  check(BreathCompositionPanelModule.queueNativePanelIntent(host,original,99)==-1);
  BreathCompositionPanelModule.queueNativePanelIntent(host,original,1);NativeMeshUiProducer.producer=new Object();host.drain();check(owner.callbacks==1);
  BreathCompositionPanelModule.queueNativePanelIntent(host,original,1);NativeMeshUiProducer.generation=2;host.drain();check(owner.callbacks==1);
  BreathCompositionPanelModule.queueNativePanelIntent(host,original,2);owner.live=false;host.drain();check(owner.callbacks==1);owner.live=true;
  BreathCompositionPanelModule.queueNativePanelIntent(host,original,2);BreathCompositionPanelModule.remotePanel=new WeakReference<>(new BreathCompositionPanelModule());host.drain();check(owner.callbacks==1);
  BreathCompositionPanelModule.remotePanel=new WeakReference<>(owner);BreathCompositionPanelModule.queueNativePanelIntent(host,original,2);NativeMeshUiProducer.active=false;host.drain();check(owner.callbacks==1&&NativeMeshUiProducer.shown==1);
  ExperimentSessionTerminalCorrelation c=origin("shell-experiment-operator-v1");
  check(c.observe(2,3,4,5,6,"panel-save-and-exit-7",1,false,true)==null);
  check(c.observe(2,3,4,5,6,"panel-save-and-exit-7",0,true,true)==null);
  check(c.observe(2,3,4,5,6,"panel-save-and-exit-7",1,true,false)==null);
  for(int i=0;i<5;i++){long[] tuple={2,3,4,5,6};tuple[i]++;check(c.observe(tuple[0],tuple[1],tuple[2],tuple[3],tuple[4],"panel-save-and-exit-7",1,true,true)==null);}
  check(c.observe(2,3,4,5,6,"arbitrary",1,true,true)==null);
  ExperimentSessionTerminalCorrelation.Result result=c.observe(2,3,4,5,6,"panel-save-and-exit-7",9,true,true);
  check(result!=null&&result.shutdownAckRevision==9);check(c.observe(2,3,4,5,6,"panel-save-and-exit-7",9,true,true)==result);
  check(c.observe(2,3,4,5,6,"panel-save-and-exit-8",9,true,true)==null);
  check(c.observe(2,3,4,5,6,"panel-save-and-exit-7",10,true,true)==null);
  String subsequentlyRejectedShellId="ffffffffffffffff";check(!c.requestId.equals(subsequentlyRejectedShellId)&&"shell-experiment-operator-v1".equals(c.operatorOrigin));
  check(origin("").operatorOrigin.isEmpty());check(origin("untrusted-origin").operatorOrigin.isEmpty());
  BreathCompositionPanelModule.RemotePending pending=new BreathCompositionPanelModule.RemotePending(origin(""));BreathCompositionPanelModule.remotePending=pending;
  BreathCompositionPanelModule.observeRemoteTerminal(new ExperimentSessionAndroidShell.TerminalResult(99,6));
  check(BreathCompositionPanelModule.remotePending==pending&&BreathCompositionPanelModule.server.published==0);
  BreathCompositionPanelModule.observeRemoteTerminal(new ExperimentSessionAndroidShell.TerminalResult(0,6));
  check(BreathCompositionPanelModule.remotePending==pending&&BreathCompositionPanelModule.server.published==0);
  BreathCompositionPanelModule.observeRemoteTerminal(new ExperimentSessionAndroidShell.TerminalResult(5,6));
  check(BreathCompositionPanelModule.remotePending==null&&BreathCompositionPanelModule.server.published==1);
  BreathCompositionPanelModule.observeRemoteTerminal(new ExperimentSessionAndroidShell.TerminalResult(5,6));
  check(BreathCompositionPanelModule.server.published==1);
  System.out.println("PASS "+checks);
 }
}
''',
        'ExperimentSessionTerminalCorrelation.java': (module / 'ExperimentSessionTerminalCorrelation.java').read_text(encoding='utf-8'),
    }
    paths = []
    for name, content in files.items():
        f = a.output / name
        f.write_text(content, encoding='utf-8')
        paths.append(str(f))
    subprocess.run([a.javac, '-d', str(a.output / 'classes'), *paths], check=True)
    result = subprocess.run([a.java, '-cp', str(a.output / 'classes'), PKG+'.Harness'], check=True, capture_output=True, text=True)
    (a.output / 'RESULT.json').write_text(json.dumps({'passed': True, 'actual_production_bodies': ['queueNativePanelIntent','ProductRouteIdentity','ExperimentSessionTerminalCorrelation'], 'output': result.stdout.strip(), 'device_effects': 0}, indent=2), encoding='utf-8')
    print(result.stdout.strip())


if __name__ == '__main__':
    main()
