"""One-process production Java owner lifecycle; explicit Android/JNI/codec/network leaves.

Uses the maintained receiver and settled-custody fixture seams. No APK, device,
real JNI or Manifold signature/authority execution is claimed.
"""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,importlib.util,json,os,pathlib,re,subprocess

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--input-spec',required=True);p.add_argument('--support-classes',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 def ref(f):
  f=pathlib.Path(f);b=f.read_bytes();return dict(path=str(f),bytes=len(b),sha256=hashlib.sha256(b).hexdigest())
 checks=[]
 def run(name,argv):
  r=subprocess.run(argv,cwd=root,capture_output=True,timeout=60);x=out/(name+'.stdout');y=out/(name+'.stderr');x.write_bytes(r.stdout);y.write_bytes(r.stderr);checks.append(dict(name=name,argv=argv,exit=r.returncode,stdout=ref(x),stderr=ref(y)));assert r.returncode==0,name
 spec=json.loads(pathlib.Path(a.input_spec).read_text());support=pathlib.Path(a.support_classes)
 # Existing whole custody regression also creates the maintained Android I/O leaves.
 run('settled-owner-regression',[sys.executable,'-B',str(root/'tools/checks/Test-PreparedSettledCustodyHost.py'),'--output',str(out/'settled'),'--json-jar',a.json_jar,'--java-home',str(pathlib.Path(spec['tools']['java']).parents[1])])
 loader=importlib.util.spec_from_file_location('owner_items',root/'tools/checks/Test-PreparedSdkHost.py');m=importlib.util.module_from_spec(loader);loader.loader.exec_module(m);item=m.item
 def bind(row):assert ref(row['path'])['sha256']==row['sha256'];return row['path']
 compilers=[bind(x)for x in spec['jvm']['compiler_jars']];libs=[bind(x)for x in spec['jvm']['classpath']]
 for x in spec['support_pins']:bind(x)
 app=root/'apps/spatial-camera-panel-android';java=app/'app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel';j=java/'embedded_duplex';fixtures=app/'host-tests/embedded_duplex'
 platform=(j/'EmbeddedDuplexPlatform.java').read_text();host=(j/'EmbeddedDuplexProcessHost.java').read_text();models=(fixtures/'PreparedSettledCustodyPlatform.java').read_text()
 # Fixture-only seam composition; production method/class bytes are never edited.
 for name in ['EmbeddedDuplexNative','EmbeddedDuplexIncomingFreshPhases','EmbeddedDuplexFreshPhaseArchive']:
  models=models.replace(item(models,'class '+name+' {'),'')
 old=item(models,'class EmbeddedDuplexProcessHost {')
 additions='''
 enum Phase{READY,CLOSING}static final EmbeddedDuplexProcessHost instance=new EmbeddedDuplexProcessHost();
 final java.util.concurrent.atomic.AtomicReference<Phase> phase=new java.util.concurrent.atomic.AtomicReference<>(Phase.READY);
 boolean closeInFlight,localFixture;EmbeddedDuplexPlatform platform;EmbeddedDuplexProcessFence processFence=new EmbeddedDuplexProcessFence();
 long nativeExecutorGeneration=73;String runtimeConfigSha256="b".repeat(64),nativeAppRecordSha256="a".repeat(64);
 static JSONObject freshPhysical(EmbeddedDuplexPlatform actual,JSONObject binding,boolean retirement)throws Exception{
  return new JSONObject(ACTOR.toString()).put("original_binding",binding).put("eligible",true);
 }
 '''+'\n'.join(item(host,n)for n in ['static void requireIncomingFreshActor(','static JSONObject freshClosureIdentity('])
 models=models.replace(old,old[:-1]+additions+'\n}')
 package='package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;\n'
 fields='''
 final io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint endpoint=new io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint();
 final EmbeddedDuplexIncomingFreshPhases incomingPhases=new EmbeddedDuplexIncomingFreshPhases();
 String incomingFreshRequestId;boolean incomingFreshDesiredStop,incomingFreshWholeStop;long incomingFreshStopSequence;
 private IncomingFreshSuccessor incomingFreshSuccessor;
 final java.util.concurrent.ConcurrentHashMap<io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint.ExchangeControl,OwnedExchange> ownedExchanges=new java.util.concurrent.ConcurrentHashMap<>();
 final String remotePeerId="peer.other";final java.net.InetAddress remoteControlAddress=null;final int remoteControlPort=1;
 EmbeddedDuplexProcessFence.CallbackLease enterProcessCallback(){return new EmbeddedDuplexProcessFence.CallbackLease();}void requireProcessCallback(){}boolean controlReady(){return true;}
 '''
 names=['public void stageIncomingCompletedFreshSetupSuccessor(','private boolean initialSuccessorAllowed(','public void assertIncomingFreshSetupInitialAllowed(','public void bindIncomingFreshSetupPhase(','void requestIncomingFreshStop(','public String freshSetupInitialPhysicalEligibilityJson(','public String freshSetupPhysicalEligibilityForIncomingJson(','private void requireIncomingPhysicalObservation(','public String archiveFreshSetupPhase(','public byte[] exchangeOwnerFrame(']
 body=fields+'\n'+item(platform,'private static final class IncomingFreshSuccessor')+'\n'+item(platform,'private static final class OwnedExchange')+'\n'+'\n'.join(item(platform,n)for n in names)
 callback=out/'EmbeddedDuplexPlatform.java';callback.write_text(package+'import org.json.*;import io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint;\nclass EmbeddedDuplexPlatform{'+body+'}\nclass EmbeddedDuplexProcessFence{long generation(){return 74;}void requireLive(long g){if(g!=74)throw new IllegalStateException();}static class CallbackLease implements AutoCloseable{public void close(){}}}\n')
 model=out/'LifecyclePlatform.java';model.write_text(models)
 build=out/'BuildConfig.java';build.write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel;public final class BuildConfig{public static final String EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="source-bound-v2";}')
 # Endpoint is a declared network primitive, retaining real task/owned-control shape.
 endpoint=out/'OwnerDispatchTcpEndpoint.java';endpoint.write_text('''package io.github.mesmerprism.rustyquest.media;
 public class OwnerDispatchTcpEndpoint{
 public static final ThreadLocal<RequestSpan>CURRENT=new ThreadLocal<>();public static int ownedCalls;
 public static class RequestSpan{public final OwnerDispatchTcpEndpoint owner;public boolean done;public RequestSpan(OwnerDispatchTcpEndpoint o){owner=o;}public boolean terminal(){return done;}}
 public static RequestSpan currentRequestSpan(){return CURRENT.get();}public boolean ownsRequestSpan(RequestSpan s){return s!=null&&s.owner==this&&!s.done;}
 public enum Phase{NONE,TARGET_BINDING}public enum Outcome{NONE,UNAVAILABLE,IN_PROGRESS,FAILED}public enum Failure{NONE,TARGET_UNAVAILABLE}
 public interface ExchangeObserver{void observe(Phase p,Outcome o,Failure f);}
 public static class ExchangeControl{boolean stop;final java.util.concurrent.CompletableFuture<Void>joined=new java.util.concurrent.CompletableFuture<>();public void requestStop(){stop=true;}public java.util.concurrent.CompletableFuture<Void>joined(){return joined;}}
 public static byte[] exchange(java.net.InetAddress a,int p,byte[]b){throw new AssertionError("unowned finite exchange");}
 public static byte[] exchange(java.net.InetAddress a,int p,byte[]b,ExchangeObserver o){throw new AssertionError("unowned exchange");}
 public static byte[] exchangeOwned(java.net.InetAddress a,int p,byte[]b,ExchangeObserver o,ExchangeControl c){try{if(c.stop)throw new IllegalStateException("modeled network primitive canceled");ownedCalls++;return b.clone();}finally{c.joined.complete(null);}}
 }''')
 original=(root/'tools/checks/Test-ConcurrentPeerRetirementHost.ps1').read_text();seams={}
 for key,name in [('looper','Looper.java'),('native','EmbeddedDuplexNative.java'),('seams','SourceBoundary.kt'),('own','OwnBoundary.kt')]:
  text=re.search(r'\$'+key+r"=WriteHost '[^']+' @'\r?\n(.*?)\r?\n'@",original,re.S).group(1)
  if key=='own':text=text.replace('class Capture {fun fresh()=true}','class Capture {fun fresh()=true;fun failure():Throwable?=null}').replace('fun phase()=Phase.Live','fun phase()=Phase.Live\n fun whenFresh(c:Capture,alive:java.util.function.BooleanSupplier)=java.util.concurrent.CompletableFuture.completedFuture<Void>(null)')
  if key=='native':
   klass=item(text,'class EmbeddedDuplexNative {');text=text.replace(klass,klass[:-1]+'static void requestFreshSetupStop(String id){}\n}')
  f=out/name;f.write_text(text);seams[key]=f
 classes=out/'classes';classes.mkdir();cp=os.pathsep.join([str(classes),str(support),a.json_jar,*libs,*compilers])
 names=['EmbeddedDuplexOriginals','EmbeddedDuplexCanonicalJson','EmbeddedDuplexNativeOperationFailure','EmbeddedDuplexPeerRoots','EmbeddedDuplexPreparedStart','EmbeddedDuplexPreparedArchive','EmbeddedDuplexPreparedRenderer','EmbeddedDuplexFreshLifecycle','EmbeddedDuplexIncomingFreshPhases','EmbeddedDuplexFreshPhaseArchive','EmbeddedDuplexReceiver','EmbeddedDuplexDisplay','EmbeddedDuplexOwnProjectionFailure','EmbeddedDuplexDisplaySlot']
 production=[j/(n+'.java')for n in names]+[java/'SpatialVideoSourceRoutingCoordinator.kt',j/'EmbeddedDuplexDisplayCoordinator.kt']
 before=[ref(x)for x in production+[j/'EmbeddedDuplexPlatform.java',j/'EmbeddedDuplexProcessHost.java',fixtures/'PreparedSettledCustodyHost.java',fixtures/'PreparedSettledCustodyPlatform.java',fixtures/'PreparedSecondLifetimeHost.kt',fixtures/'FreshPairLifecycleHost.kt',root/'tools/checks/Test-ConcurrentPeerRetirementHost.ps1',pathlib.Path(__file__)]]
 modelsources=list((out/'settled/platform').glob('*.java'))+[model,callback,endpoint,build,seams['native'],seams['looper'],fixtures/'PreparedSettledCustodyHost.java']
 run('whole-java-owners',[spec['tools']['javac'],'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),*[str(x)for x in production[:-2]+modelsources]])
 run('whole-kotlin-owners',[spec['tools']['java'],'-cp',os.pathsep.join(compilers),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes),*[str(x)for x in [seams['seams'],seams['own'],*production[-2:],fixtures/'PreparedSecondLifetimeHost.kt',fixtures/'FreshPairLifecycleHost.kt']]])
 run('complete-lifecycle',[spec['tools']['java'],'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.FreshPairLifecycleHost',str(out/'lifecycle-files'),str(out/'LIFECYCLE_RESULT.json')])
 for mode in ['failed-stop','concurrent-stop','archive-failure']:
  run(mode,[spec['tools']['java'],'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.FreshPairLifecycleHost',str(out/(mode+'-files')),str(out/(mode+'-RESULT.json')),mode])
 pins=[ref(x)for x in production+[j/'EmbeddedDuplexPlatform.java',j/'EmbeddedDuplexProcessHost.java',fixtures/'PreparedSettledCustodyHost.java',fixtures/'PreparedSettledCustodyPlatform.java',fixtures/'PreparedSecondLifetimeHost.kt',fixtures/'FreshPairLifecycleHost.kt',root/'tools/checks/Test-ConcurrentPeerRetirementHost.ps1',pathlib.Path(__file__)]]
 assert pins==before,"production source changed during campaign"
 result=dict(passed=True,source_unchanged=True,checks=checks,source_pins=pins,generated_source_pins=[ref(x)for x in [model,callback,endpoint,build,*seams.values()]],helper=ref(root/'tools/checks/Test-PreparedSdkHost.py'),json_jar=ref(a.json_jar),input_spec=ref(a.input_spec),lifecycle=ref(out/'LIFECYCLE_RESULT.json'),modeled_boundaries=['Android directory fsync/fence/ProcessHost scheduling and actor source','Native setup/authority receipts and eligibility primitive; no Manifold signature/realJNI call','Codec/socket/renderer-native resource primitives; real Receiver/Display/routing and SDK resource callback joins retained'],limits=__doc__)
 (out/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(ref(out/'RESULT.json')))
if __name__=='__main__':main()
