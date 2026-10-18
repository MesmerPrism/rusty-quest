"""Production bootstrap trace/install bodies, with modeled Android/media owners.

No Android, camera, JNI, APK, device or physical cleanup claim. The harness
extracts the actual resource install method and closed enum/trace code, then
injects owner failures at each exact production call boundary.
"""
import argparse,hashlib,json,pathlib,shutil,subprocess

def block(text,marker):
 start=text.index(marker);opening=text.index('{',start);depth=0
 for index in range(opening,len(text)):
  if text[index]=='{':depth+=1
  elif text[index]=='}':
   depth-=1
   if depth==0:return text[start:index+1]
 raise ValueError('Production block incomplete')

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--root',type=pathlib.Path,required=True);parser.add_argument('--output',type=pathlib.Path,required=True)
 parser.add_argument('--javac',default=shutil.which('javac'));parser.add_argument('--java',default=shutil.which('java'));a=parser.parse_args()
 root=a.root.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
 base=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
 bootstrap=(base/'EmbeddedDuplexBootstrap.java').read_text();resources=(base/'EmbeddedDuplexResources.java').read_text();host=(base/'EmbeddedDuplexProcessHost.java').read_text();coordinator=(base/'EmbeddedDuplexDisplayCoordinator.kt').read_text()
 # The provider/status schema is unchanged; production wiring must retain the
 # exact trace across install and capture the original before phase handling.
 assert 'installed.install(trace);' in host and 'trace.captureFailure(failure);\n                lastBootstrapFailure = trace.failure();' in host
 assert host.index('Failure.RESOURCE_CONSTRUCT')<host.index('EmbeddedDuplexResources installed =')<host.index('resources = installed;')<host.index('installed.install(trace);')<host.index('Failure.RESOURCE_REGISTRY')<host.index('callbacks.installResources(installed);')
 for reason in ('CAPTURE_UNAVAILABLE','FRESHNESS_PENDING','CARRIER_NOT_READY','SUPERSEDED','INTERRUPTED'):assert 'EmbeddedDuplexOwnProjectionFailure.Reason.'+reason in coordinator
 assert 'TimeUnit.SECONDS.toNanos(10)' in coordinator and 'return result.get(30, TimeUnit.SECONDS)' in coordinator
 actual='\n'.join(block(bootstrap,marker) for marker in ('    enum Failure {','    static final class Trace {','    static void mark('))
 install=block(resources,'    void install(EmbeddedDuplexBootstrap.Trace trace)')
 # Replace only the modeled owner class's qualified spelling; production
 # install control flow, calls, guards and cleanup bodies remain exact.
 install=install.replace('io.github.mesmerprism.rustyquest.media.PackedStereoCaptureOwner','PackedStereoCaptureOwner')
 source=PREFIX.replace('TRACE_BODY',actual).replace('INSTALL_BODY',install)+SUFFIX
 harness=out/'BootstrapResourceFailureHarness.java';harness.write_text(source,encoding='utf-8')
 for label,argv in [('javac',[a.javac,'--release','17','-encoding','UTF-8','-d',str(out),str(base/'EmbeddedDuplexOwnProjectionFailure.java'),str(harness)]),('java',[a.java,'-cp',str(out),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.BootstrapResourceFailureHarness'])]:
  result=subprocess.run(argv,capture_output=True,timeout=60);(out/(label+'.stdout')).write_bytes(result.stdout);(out/(label+'.stderr')).write_bytes(result.stderr)
  (out/(label+'.result.json')).write_text(json.dumps(dict(argv=argv,exit_code=result.returncode)))
  if result.returncode:raise RuntimeError(label+' failed; exact streams retained')
 pins=[]
 for path in (base/'EmbeddedDuplexBootstrap.java',base/'EmbeddedDuplexResources.java',base/'EmbeddedDuplexProcessHost.java',base/'EmbeddedDuplexDisplayCoordinator.kt',base/'EmbeddedDuplexOwnProjectionFailure.java',pathlib.Path(__file__).resolve()):
  raw=path.read_bytes();pins.append(dict(path=str(path),bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
 value=dict(status='PASS',source_pins=pins,platform_facts='MODELED Android/media owner failures',native_or_device_effects=0,android_qualified=False,physical_cleanup_claimed=False)
 (out/'RESULT.json').write_text(json.dumps(value,indent=2));print(json.dumps(value))

PREFIX=r'''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.util.concurrent.*;
public final class BootstrapResourceFailureHarness {
 static String fault="";static boolean cleanupFault;static int effects,cleanupCalls,checks;
 static void hit(String stage){effects++;if(fault.equals(stage))throw new IllegalStateException("MODELED SECRET "+stage);}
 static void check(boolean value){checks++;if(!value)throw new AssertionError("host control failed");}
 static class EmbeddedDuplexBootstrap { TRACE_BODY }
 static class PackedStereoCaptureOwner {}
 static class OwnStereoCaptureRuntime {
  static class Configuration{Configuration(Object...args){}}
  PackedStereoCaptureOwner startAccepted(Configuration c){hit("capture");return new PackedStereoCaptureOwner();}
 }
 static class Display {
  EmbeddedDuplexOwnProjectionFailure.Reason reason;
  void activateOwnProjection()throws Exception{
   hit("projection");if(reason!=null)throw new ExecutionException(new EmbeddedDuplexOwnProjectionFailure(reason));
  }
 }
 static class Pipeline {
  void configureAcceptedLanRoute(Object...args){hit("route");}
  void close(){cleanupCalls++;if(cleanupFault)throw new IllegalStateException("MODELED cleanup secret");}
 }
 static class PackedStereoMediaSourceRuntime {
  static Pipeline createSharedCapturePipeline(Object...args){hit("pipeline");return new Pipeline();}
  static Pipeline createPipeline(Object...args){hit("pipeline");return new Pipeline();}
 }
 static class Incoming{void closeUnstartedAndVerify(){cleanupCalls++;if(cleanupFault)throw new IllegalStateException("MODELED cleanup secret");}}
 static class PackedStereoMediaOwnerSet {
  PackedStereoMediaOwnerSet(Object...args){hit("outgoing");}Object provider(String kind){return new Object();}
 }
 static class MediaProductBinding {
  static class Builder {
   Builder(String id){hit("binding_create");}void bind(Object...args){hit("bindings");}MediaProductBinding build(){hit("binding_build");return new MediaProductBinding();}
  }
 }
 static class JSONObject {
  String group="outgoing";int index;boolean target;
  JSONObject(){}JSONObject(String group,int index,boolean target){this.group=group;this.index=index;this.target=target;}
  String getString(String key){
   if(key.equals("placement"))return index<(group.equals("outgoing")?6:1)?"local":"remote";
   if(key.equals("owner_kind"))return group.equals("incoming")?"sink":"source";
   return "MODELED";
  }
  int getInt(String key){return 1;}JSONObject getJSONObject(String key){return new JSONObject(group,index,true);}
  JSONArray getJSONArray(String key){hit("bindings");return new JSONArray(key.startsWith("incoming")?"incoming":"outgoing");}
 }
 static class JSONArray{String group;JSONArray(String group){this.group=group;}int length(){return 7;}JSONObject getJSONObject(int i){return new JSONObject(group,i,false);}}
 static class Lane{
  int width=2,height=1,fps=60,bitrate=12;JSONObject plan=new JSONObject(),source=new JSONObject(),endpoint=new JSONObject();
  String sourceTransportHost="model",sinkTransportHost="model";int sinkTransportPort=1;
 }
 static class Resources{
  long generation=1,maxPairDeltaNs=1;Object context=new Object();OwnStereoCaptureRuntime ownCapture=new OwnStereoCaptureRuntime();Display display=new Display();Pipeline pipeline;PackedStereoMediaOwnerSet outgoing;Incoming incoming=new Incoming();MediaProductBinding binding;JSONObject outgoingSpec=new JSONObject(),nativeInitialization=new JSONObject();Lane source=new Lane();String leftCamera="50",rightCamera="51";boolean installationAttempted;
 INSTALL_BODY
 }
'''
SUFFIX=r'''
 static EmbeddedDuplexBootstrap.Trace run(String failure,EmbeddedDuplexOwnProjectionFailure.Reason reason,boolean cleanup)throws Exception{
  fault=failure;cleanupFault=cleanup;effects=cleanupCalls=0;Resources r=new Resources();r.display.reason=reason;
  EmbeddedDuplexBootstrap.Trace trace=new EmbeddedDuplexBootstrap.Trace();trace.mark(EmbeddedDuplexBootstrap.Failure.RESOURCE_INSTALL);
  try{r.install(trace);check(failure.isEmpty()&&reason==null);}catch(Exception original){trace.captureFailure(original);check(!failure.isEmpty()||reason!=null);check(!trace.failure().code.contains("SECRET"));check(cleanupCalls>0);}
  return trace;
 }
 public static void main(String[]args)throws Exception{
  String[][] stages={{"capture","own_capture_start_failed"},{"projection","own_projection_failed"},{"pipeline","pipeline_create_failed"},{"route","pipeline_route_failed"},{"outgoing","outgoing_owner_failed"},{"binding_create","binding_create_failed"},{"bindings","owner_bindings_failed"},{"binding_build","binding_build_failed"}};
  for(String[] stage:stages){check(run(stage[0],null,false).failure().code.equals(stage[1]));check(run(stage[0],null,true).failure().code.equals(stage[1]));}
  String[] codes={"own_capture_unavailable","own_capture_freshness_pending","own_carrier_not_ready","own_projection_superseded","own_projection_interrupted"};int i=0;
  for(EmbeddedDuplexOwnProjectionFailure.Reason reason:EmbeddedDuplexOwnProjectionFailure.Reason.values())check(run("",reason,true).failure().code.equals(codes[i++]));
  EmbeddedDuplexBootstrap.Trace t=new EmbeddedDuplexBootstrap.Trace();t.mark(EmbeddedDuplexBootstrap.Failure.RESOURCE_OWN_PROJECTION);t.captureFailure(new CompletionException(new ExecutionException(new TimeoutException("SECRET"))));check(t.failure().code.equals("own_projection_dispatch_timeout"));
  t.mark(EmbeddedDuplexBootstrap.Failure.RESOURCE_OWN_PROJECTION);t.captureFailure(new InterruptedException("SECRET"));check(t.failure().code.equals("own_projection_interrupted"));
  t.mark(EmbeddedDuplexBootstrap.Failure.CONTROL_ENDPOINT);t.captureFailure(new EmbeddedDuplexOwnProjectionFailure(EmbeddedDuplexOwnProjectionFailure.Reason.CARRIER_NOT_READY));check(t.failure()==EmbeddedDuplexBootstrap.Failure.CONTROL_ENDPOINT);
  t.mark(EmbeddedDuplexBootstrap.Failure.RESOURCE_OWN_PROJECTION);t.captureFailure(new IllegalStateException("own_carrier_not_ready SECRET"));check(t.failure()==EmbeddedDuplexBootstrap.Failure.RESOURCE_OWN_PROJECTION);
  run("",null,false);check(cleanupCalls==0);
  Resources r=new Resources();r.install(null);int before=effects;try{r.install(null);throw new AssertionError("repeated install accepted");}catch(IllegalStateException expected){check(before==effects);}
  for(EmbeddedDuplexBootstrap.Failure f:EmbeddedDuplexBootstrap.Failure.values())check(f.stage.matches("[a-z_]+")&&f.code.matches("[a-z_]+"));
  System.out.println("PASS production install/trace controls="+checks+"; Android/media owners MODELED; no device effects");
 }
}
'''
if __name__=='__main__':main()
