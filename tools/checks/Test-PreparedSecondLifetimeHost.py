#!/usr/bin/env python3
"""Whole production Receiver/Display/routing; modeled Android/SDK resources only."""
import sys
sys.dont_write_bytecode=True
import argparse,hashlib,json,os,pathlib,re,subprocess
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--input-spec',required=True);p.add_argument('--support-classes',required=True);p.add_argument('--output',required=True);p.add_argument('--baseline',action='store_true');a=p.parse_args()
 root=pathlib.Path(__file__).resolve().parents[2];out=pathlib.Path(a.output).resolve();out.relative_to(root/'target');out.mkdir(parents=True,exist_ok=False)
 spec=json.loads(pathlib.Path(a.input_spec).read_text());support=pathlib.Path(a.support_classes).resolve(strict=True)
 def bind(row):
  path=pathlib.Path(row['path']);assert hashlib.sha256(path.read_bytes()).hexdigest()==row['sha256'];return str(path)
 compiler=[bind(row) for row in spec['jvm']['compiler_jars']];libraries=[bind(row) for row in spec['jvm']['classpath']]
 for row in spec['support_pins']:bind(row)
 app=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel';java=app/'embedded_duplex'
 original=(root/'tools/checks/Test-ConcurrentPeerRetirementHost.ps1').read_text()
 # Reuse the maintained owner's exact platform/native/Own resource seams.
 seam_names={'looper':'Looper.java','native':'EmbeddedDuplexNative.java','seams':'SourceBoundary.kt','own':'OwnBoundary.kt'};seams={}
 for name,filename in seam_names.items():
  value=re.search(r'\$'+name+r"=WriteHost '[^']+' @'\r?\n(.*?)\r?\n'@",original,re.S).group(1)
  if name=='own':
   value=value.replace('class Capture {fun fresh()=true}', 'class Capture {fun fresh()=true;fun failure():Throwable?=null}')
   value=value.replace('fun phase()=Phase.Live', 'fun phase()=Phase.Live\n fun whenFresh(c:Capture,alive:java.util.function.BooleanSupplier)=java.util.concurrent.CompletableFuture.completedFuture<Void>(null)')
  path=out/filename;path.write_text(value,encoding='utf-8');seams[name]=path
 peer=out/'EmbeddedDuplexPeerRoots.java';peer.write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
class EmbeddedDuplexPeerRoots {static void keys(org.json.JSONObject v,String... names){java.util.Set<String>s=new java.util.HashSet<>();v.keys().forEachRemaining(s::add);if(!s.equals(new java.util.HashSet<>(java.util.Arrays.asList(names))))throw new IllegalArgumentException();}static void identity(org.json.JSONObject a,org.json.JSONObject b){} }
''',encoding='utf-8')
 classes=out/'classes';classes.mkdir();cp=os.pathsep.join([str(classes),str(support),*libraries,*compiler]);fixture=root/'apps/spatial-camera-panel-android/host-tests/embedded_duplex/PreparedSecondLifetimeHost.kt'
 production=[java/(name+'.java') for name in ['EmbeddedDuplexReceiver','EmbeddedDuplexDisplay','EmbeddedDuplexPreparedRenderer','EmbeddedDuplexOwnProjectionFailure','EmbeddedDuplexEstimate','EmbeddedDuplexCanonicalJson']]+[app/'SpatialVideoSourceRoutingCoordinator.kt',java/'EmbeddedDuplexDisplayCoordinator.kt']
 before=[{'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for path in [*production,fixture]]
 def run(name,argv):
  result=subprocess.run(argv,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE);(out/(name+'.stdout')).write_bytes(result.stdout);(out/(name+'.stderr')).write_bytes(result.stderr);return result.returncode
 code=run('java',[spec['tools']['javac'],'--release','17','-cp',cp,'-d',str(classes),str(seams['looper']),str(seams['native']),str(peer),*[str(x) for x in production[:6]]]);assert code==0,'Java compile'
 code=run('kotlin',[spec['tools']['java'],'-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes),str(seams['seams']),str(seams['own']),*[str(x) for x in production[6:]],str(fixture)]);assert code==0,'Kotlin compile'
 argv=[spec['tools']['java'],'-cp',cp,'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.PreparedSecondLifetimeHost'];argv+=['baseline'] if a.baseline else []
 code=run('controls',argv)
 result={'passed':code==0,'exit':code,'baseline':a.baseline,'source_pins':before,'input_spec':{'path':str(pathlib.Path(a.input_spec).resolve()),'sha256':hashlib.sha256(pathlib.Path(a.input_spec).read_bytes()).hexdigest()},'limits':__doc__}
 for row in before:bind(row)
 (out/'RESULT.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps({'passed':code==0,'exit':code}));return code
if __name__=='__main__':sys.exit(main())
