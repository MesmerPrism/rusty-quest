"""Execute exact app admission methods with actual Original custody; Android/service leaves modeled."""
import argparse, hashlib, importlib.util, json, os, pathlib, subprocess, sys, uuid

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def classpath_pins(classpath):
    pins=[]
    for value in filter(None,classpath.split(os.pathsep)):
        path=pathlib.Path(value)
        if path.is_file():pins.append({'path':str(path),'sha256':sha(path)})
        elif path.is_dir():
            entries=[str(p.relative_to(path)).replace('\\','/')+'\0'+sha(p) for p in sorted(path.rglob('*.class'))]
            pins.append({'path':str(path),'class_count':len(entries),'inventory_sha256':hashlib.sha256('\n'.join(entries).encode()).hexdigest()})
        else:raise ValueError('Selected support path absent: '+value)
    return pins
def kotlin_controls(root,out,java,jar,support,manifest):
    if not manifest: raise ValueError('Select --kotlin-toolchain-manifest or RUSTY_QUEST_KOTLIN_TOOLCHAIN_MANIFEST')
    manifest=pathlib.Path(manifest).resolve(); manifest_hash=sha(manifest);selected=json.loads(manifest.read_text(encoding='utf-8-sig')); compiler=selected['jvm']['compiler_jars']
    if not compiler or len({pathlib.Path(p['path']).resolve() for p in compiler})!=len(compiler): raise ValueError('Select a nonempty unique Kotlin compiler manifest')
    json_dependencies=[pin for pin in selected['jvm']['classpath'] if pin['sha256']==sha(jar)]
    for pin in compiler+json_dependencies:
        if sha(pathlib.Path(pin['path']))!=pin['sha256']: raise ValueError('Toolchain dependency hash differs: '+pin['path'])
    if not json_dependencies: raise ValueError('Selected JSON jar bytes absent from toolchain manifest')
    work=out/'kotlin';work.mkdir();classes=work/'classes';classes.mkdir()
    pkg='io.github.mesmerprism.rustyquest.spatial_camera_panel'; epkg=pkg+'.embedded_duplex'
    base=root/'apps/spatial-camera-panel-android/app/src/main/java'/pkg.replace('.','/')
    names=['SpatialCameraPanelActivity.kt','EmbeddedDuplexDiagnosticActivityGate.kt','SpatialCameraHwbProjectionRawCarrierCoordinator.kt','embedded_duplex/EmbeddedDuplexRuntimeService.java']
    before={str(base/n):sha(base/n) for n in names}
    before[str(base/'embedded_duplex/EmbeddedDuplexRuntimeStatus.java')]=sha(base/'embedded_duplex/EmbeddedDuplexRuntimeStatus.java')
    spec=importlib.util.spec_from_file_location('actual_original_controls',root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    extracts=[]
    def write(n,b):
        p=work/'src'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b.encode() if isinstance(b,str) else b);return p
    def take(n,t):
        raw=(base/n).read_bytes();text=raw.decode()
        if t=='  private fun postOriginal(':
            m=text[text.index(t):text.index('  fun resumed(',text.index(t))].rstrip()
        else:
            m=mod.extract_method(text,t)
            if t.startswith('  fun request'):
                start=text.index(t);annotation=text.rfind('  @JvmStatic',0,start)
                if annotation<0 or text[annotation:start].strip()!='@JvmStatic':raise ValueError('Actual static annotation unavailable')
                m=text[annotation:start]+m
        b=m.encode()
        if b not in raw: raise ValueError('Extraction changed actual bytes')
        extracts.append({'source':n,'signature':t,'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b)});write('extracts/'+str(len(extracts))+'.txt',b);return m
    activity=[take(names[0],t) for t in ['  fun embeddedDuplexBootstrapReadiness(','  fun runEmbeddedDuplexRealPeerBootstrap(']]
    gate=[take(names[1],t) for t in ['  private fun postOriginal(','  fun resumed(','  fun paused(','  fun requestRealPeerBootstrap(','  fun requestBootstrapReadiness(']]
    carrier=take(names[2],'  fun sourceCarrierReadiness(')
    service=[take(names[3],t) for t in ['    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeerCommand(','    public static CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeerFromActivity(','    public static CompletableFuture<String> bootstrapReadiness(']]
    write('Activity.kt','package '+pkg+'\nimport java.util.concurrent.CompletableFuture\nimport '+epkg+'''.*
class SpatialCameraPanelActivity(val directory:java.io.File):android.content.Context(){
 override fun getNoBackupFilesDir()=directory
 var embeddedDuplexRuntimeFuture:CompletableFuture<EmbeddedDuplexRuntimeStatus>?=null
 var embeddedDuplexDiagnosticFuture:CompletableFuture<String>?=null
 var embeddedDuplexAttachmentGeneration=0L
 var embeddedDuplexDiagnosticFinished=false
 var embeddedDuplexActivityDestroying=false
 var spatialSceneReady=true
 val cameraHwbProjectionRawCarrierCoordinator=SpatialCameraHwbProjectionRawCarrierCoordinator()
 fun marker(s:String){}
 fun runOnUiThread(action:Runnable){android.os.Handler.queue.add(action)}
'''+ '\n'.join(activity)+'\n}\n')
    write('Gate.kt','package '+pkg+'\nimport android.os.Handler\nimport android.os.Looper\nimport java.lang.ref.WeakReference\nimport java.util.concurrent.CompletableFuture\nimport '+epkg+'''.*
object BuildConfig {const val DEBUG=true;@JvmField var EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=true}
object EmbeddedDuplexDiagnosticActivityGate {
 @Volatile private var resumed:WeakReference<SpatialCameraPanelActivity>?=null
 @Volatile private var bookkeeping:WeakReference<SpatialCameraPanelActivity>?=null
 private val main=Handler(Looper.getMainLooper())
'''+ '\n'.join(gate)+'\n}\n')
    write('Carrier.kt','package '+pkg+'''
object OwnPackedPoolNative {var selected=true;var reads=0;fun captureRouteSelected():Boolean{reads++;return selected}}
class ModelSurface(var isValid:Boolean)
class SpatialCameraHwbProjectionRawCarrierCoordinator {
 val rawLayerPublicationMonitor=Any()
 var fieldReads=0
 var activeLayerFence:Any?=null
  get(){fieldReads++;return field}
 var activeRenderSurface:ModelSurface?=null
  get(){fieldReads++;return field}
'''+carrier+'\n}\n')
    write('Handler.java','''package android.os;
public final class Handler {
 public static final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>();
 public static boolean accepted=true,throwAfterQueue=false;
 public Handler(Looper ignored){}
 public boolean post(Runnable action){if(!accepted)return false;queue.add(action);if(throwAfterQueue)throw new IllegalStateException("modeled post queued then threw");return true;}
 public static void next(){queue.remove().run();}
 public static void drain(){while(!queue.isEmpty())next();}
}''')
    write('Looper.java','package android.os;public final class Looper {public static Looper getMainLooper(){return new Looper();}}')
    write('EmbeddedDuplexRuntimeStatus.java',(base/'embedded_duplex/EmbeddedDuplexRuntimeStatus.java').read_bytes())
    old=(out/'originals/models'/epkg.replace('.','/')/'EmbeddedDuplexRuntimeService.java').read_text();old=old[old.index('public static Bundle beginOriginal('):old.rindex('}')]
    write('EmbeddedDuplexRuntimeService.java','package '+epkg+''';
import android.content.Context;import android.os.Bundle;import org.json.JSONObject;import java.util.concurrent.CompletableFuture;import java.util.function.Supplier;
public final class EmbeddedDuplexRuntimeService {
 public static int calls;static EmbeddedDuplexOriginals.Record entered;
 public static CompletableFuture<EmbeddedDuplexRuntimeStatus> serviceResult;
 public static CompletableFuture<EmbeddedDuplexRuntimeStatus> bootstrapRealPeer(Context context,long generation){calls++;entered=EmbeddedDuplexOriginals.capture();if(entered==null)throw new AssertionError("service lost actual Original");entered.enteredCommandStages.incrementAndGet();return serviceResult;}
'''+ '\n'.join(service)+'\n'+old+'\n}\n')
    write('Bridge.java','package '+epkg+''';
import java.util.*;import java.util.concurrent.*;import org.json.JSONObject;
public final class Bridge {
 static EmbeddedDuplexOriginals.Record latest;
 public static void fresh()throws Exception{for(Class<?> c:new Class<?>[]{EmbeddedDuplexOriginals.class,EmbeddedDuplexProcessHost.class}){java.lang.reflect.Field f=c.getDeclaredField(c==EmbeddedDuplexOriginals.class?"singleton":"instance");f.setAccessible(true);f.set(null,null);}latest=null;}
 public static void capture(android.content.Context c)throws Exception{
  EmbeddedDuplexOriginals owner=EmbeddedDuplexOriginals.forApplication(c);java.lang.reflect.Field f=EmbeddedDuplexOriginals.class.getDeclaredField("live");f.setAccessible(true);
  latest=((HashMap<String,EmbeddedDuplexOriginals.Record>)f.get(owner)).values().stream().reduce((a,b)->b).orElseThrow();
 }
 public static int commands(){return latest.commands;}public static boolean callback(){return latest.callbackJoined;}
 public static boolean noService(){return latest.enteredCommandStages.get()==0;}public static boolean sameService(){return latest==EmbeddedDuplexRuntimeService.entered;}
 public static JSONObject row()throws Exception{return new JSONObject(latest.owner.query(latest.id(),"c".repeat(32)).getString("observation_json"));}
 public static String joinStatus()throws Exception{return latest.owner.queryJoin(latest.id(),"c".repeat(32)).getString("status");}
 public static void ack()throws Exception{JSONObject r=row();latest.owner.acknowledge(latest.id(),"c".repeat(32),r.getString("request_sha256"),r.getString("outcome_sha256"),"d".repeat(64));}
 public static boolean sameRefusal(Throwable failure){EmbeddedDuplexAppAdmission.Refusal r=EmbeddedDuplexAppAdmission.refusal(failure);return r!=null&&r.original==latest;}
 public static boolean hostAbsent()throws Exception{java.lang.reflect.Field f=EmbeddedDuplexProcessHost.class.getDeclaredField("instance");f.setAccessible(true);return f.get(null)==null;}
 public static boolean originalAbsent()throws Exception{java.lang.reflect.Field f=EmbeddedDuplexOriginals.class.getDeclaredField("singleton");f.setAccessible(true);return f.get(null)==null;}
 public static void select(android.content.Context context,String id)throws Exception{new EmbeddedDuplexRunSelectionStore(context.getNoBackupFilesDir(),new EmbeddedDuplexRunSelectionStore.DirectorySync(){public void sync(java.io.File ignored){}public void privateMode(java.io.File ignored,boolean directory){}}).newRun(id);}
 public static EmbeddedDuplexRuntimeStatus ready(){return new EmbeddedDuplexRuntimeStatus("bootstrapped_route_unverified",true,"a".repeat(64),"b".repeat(64));}
 public static String kind()throws Exception{return row().getJSONObject("response").getString("kind");}
 static byte[] originalStat;
 public static void advanceProcess()throws Exception{java.nio.file.Path p=java.nio.file.Paths.get(System.getProperty("host.proc"),"42","stat");originalStat=java.nio.file.Files.readAllBytes(p);String v=new String(originalStat,java.nio.charset.StandardCharsets.UTF_8);java.nio.file.Files.write(p,v.replace("123456","123457").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 public static void breakProcessObservation()throws Exception{java.nio.file.Path p=java.nio.file.Paths.get(System.getProperty("host.proc"),"42","stat");originalStat=java.nio.file.Files.readAllBytes(p);java.nio.file.Files.writeString(p,"unavailable actor observation");}
 public static void restoreProcess()throws Exception{java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("host.proc"),"42","stat"),originalStat);}
 public static void advanceOwnerRun(android.content.Context c)throws Exception{EmbeddedDuplexProcessHost host=EmbeddedDuplexProcessHost.forApplication(c);EmbeddedDuplexRunContext before=EmbeddedDuplexProcessHost.selectedContext();java.lang.reflect.Field f=EmbeddedDuplexProcessHost.class.getDeclaredField("runContext");f.setAccessible(true);f.set(host,new EmbeddedDuplexRunContext(before.applicationRoot,new java.io.File(before.applicationRoot,"modeled-next-run"),"f".repeat(32),before.selectionGeneration+1));}
 public static void corruptJoin()throws Exception{java.lang.reflect.Field f=EmbeddedDuplexOriginals.class.getDeclaredField("directory");f.setAccessible(true);java.nio.file.Path path=new java.io.File((java.io.File)f.get(latest.owner),latest.id()+".joined.json").toPath();JSONObject proof=new JSONObject(java.nio.file.Files.readString(path));proof.put("service_entered",true);java.nio.file.Files.writeString(path,EmbeddedDuplexCanonicalJson.encode(proof));}

 public static void requireServiceUnion()throws Exception{android.os.Bundle b=EmbeddedDuplexAppAdmission.serviceBundle("a".repeat(32),ready());JSONObject value=new JSONObject();for(String k:b.keySet())value.put(k,b.get(k));EmbeddedDuplexAppAdmission.requireResult("bootstrap_real_peer","a".repeat(32),value);}
 public static boolean noNativeJoin()throws Exception{try{ack();return false;}catch(IllegalStateException expected){return true;}}
}''')
    write('Controls.kt','package '+pkg+'\nimport java.util.concurrent.CompletableFuture\nimport '+epkg+'''.*
import org.json.JSONObject
import android.os.Handler
object AdmissionControls {
 var cases=0
 fun test(name:String,body:()->Unit){body();cases++;println("PASS "+name)}
 fun activity():SpatialCameraPanelActivity {
  Handler.drain();Bridge.fresh();Handler.accepted=true;Handler.throwAfterQueue=false
  EmbeddedDuplexRuntimeService.calls=0;EmbeddedDuplexRuntimeService.serviceResult=CompletableFuture.completedFuture(Bridge.ready())
  BuildConfig.EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=true;OwnPackedPoolNative.selected=true;OwnPackedPoolNative.reads=0
  val dir=java.nio.file.Files.createTempDirectory("admission-").toFile()
  return SpatialCameraPanelActivity(dir).apply{embeddedDuplexAttachmentGeneration=7;EmbeddedDuplexDiagnosticActivityGate.resumed(this)}
 }
 fun failure(f:CompletableFuture<*>):Throwable=try{f.join();error("failure required")}catch(e:java.util.concurrent.CompletionException){e}
 fun refusal(f:CompletableFuture<*>,reason:String){
  check(Bridge.sameRefusal(failure(f)));val r=Bridge.row()
  check(r.getString("state")=="succeeded"&&r.getString("effect_state")=="not_entered")
  check(r.getJSONObject("response").getString("kind")=="app_refusal")
  val payload=JSONObject(r.getJSONObject("response").getString("receipt"))
  check(payload.getString("reason")==reason&&!payload.getBoolean("service_entered"))
  check(Bridge.commands()==0&&Bridge.noService()&&Bridge.callback()&&Bridge.joinStatus()=="joined")
  check(EmbeddedDuplexRuntimeService.calls==0);Bridge.ack();check(Bridge.row().getString("retention")=="compacted")
 }
 @JvmStatic fun main(args:Array<String>){
  test("actual read-only Service/Gate constructs no ProcessHost and creates no selector files"){
   val a=activity();val before=a.directory.walkTopDown().filter{it.isFile}.map{it.relativeTo(a.directory).path}.toList()
   check(Bridge.hostAbsent()&&Bridge.originalAbsent());val f=EmbeddedDuplexRuntimeService.bootstrapReadiness(a,null);check(!f.isDone);Handler.drain()
   check(JSONObject(f.join()).getJSONObject("activity").getString("bootstrap_gate_reason")=="open")
   check(Bridge.hostAbsent()&&Bridge.originalAbsent()&&a.directory.walkTopDown().filter{it.isFile}.map{it.relativeTo(a.directory).path}.toList()==before&&EmbeddedDuplexRuntimeService.calls==0)
  }
  test("actual readiness reads existing selector bytes without constructing either owner"){
   val a=activity();val id="a".repeat(32);Bridge.select(a,id)
   fun bytes()=a.directory.walkTopDown().filter{it.isFile}.associate{it.relativeTo(a.directory).path to java.util.Base64.getEncoder().encodeToString(it.readBytes())}
   val before=bytes();val f=EmbeddedDuplexRuntimeService.bootstrapReadiness(a,id);Handler.drain()
   check(JSONObject(f.join()).getString("selector_run_id")==id&&before==bytes()&&Bridge.hostAbsent()&&Bridge.originalAbsent())
  }
  test("actual readiness rejects selector changed while Activity observation was queued"){
   val a=activity();Bridge.select(a,"a".repeat(32));val f=EmbeddedDuplexRuntimeService.bootstrapReadiness(a,"a".repeat(32));Bridge.select(a,"b".repeat(32));Handler.drain();failure(f);check(Bridge.hostAbsent()&&Bridge.originalAbsent())
  }
  for(reason in listOf("attachment_absent","activity_destroying","diagnostic_future_retained","runtime_future_retained"))test("actual Activity refusal "+reason){
   val a=activity();when(reason){"attachment_absent"->a.embeddedDuplexAttachmentGeneration=0;"activity_destroying"->a.embeddedDuplexActivityDestroying=true;"diagnostic_future_retained"->a.embeddedDuplexDiagnosticFuture=CompletableFuture();else->a.embeddedDuplexRuntimeFuture=CompletableFuture()}
   val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a)
   check(Bridge.commands()==1&&!f.isDone&&EmbeddedDuplexRuntimeService.calls==0&&Bridge.joinStatus()=="pending")
   val inside=java.util.concurrent.atomic.AtomicBoolean()
   f.whenComplete{_,_->inside.set(Bridge.commands()==1&&Bridge.joinStatus()=="pending")}
   Handler.next();check(inside.get());check(OwnPackedPoolNative.reads==0&&a.cameraHwbProjectionRawCarrierCoordinator.fieldReads==0);refusal(f,reason)
  }
  test("human direct Activity shares source pre-entry Original wrapper"){
   val a=activity();a.embeddedDuplexAttachmentGeneration=0;val f=a.runEmbeddedDuplexRealPeerBootstrap();Bridge.capture(a);refusal(f,"attachment_absent")
  }
  test("actual Gate no resumed Activity"){
   val a=activity();EmbeddedDuplexDiagnosticActivityGate.paused(a);val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);Handler.next();refusal(f,"resumed_activity_absent")
  }
  test("unavailable actor evidence completes UNKNOWN without escaping the actual callback"){
   val a=activity();val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);EmbeddedDuplexDiagnosticActivityGate.paused(a);Bridge.breakProcessObservation()
   try { Handler.drain();failure(f);check(Bridge.row().getString("state")=="unknown"&&Bridge.commands()==0&&Bridge.joinStatus()=="pending"&&Bridge.noNativeJoin()&&EmbeddedDuplexRuntimeService.calls==0) }finally{Bridge.restoreProcess()}
  }
  test("local app ACK rejects a changed actual process identity"){
   val a=activity();a.embeddedDuplexAttachmentGeneration=0;val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);Handler.drain();failure(f)
   check(Bridge.kind()=="app_refusal"&&Bridge.joinStatus()=="joined");Bridge.advanceProcess()
   try { try { Bridge.ack();error("changed process ACK accepted") }catch(expected:IllegalStateException){} }finally{Bridge.restoreProcess()}
  }
  test("local app ACK rejects a changed selected owner run"){
   val a=activity();a.embeddedDuplexAttachmentGeneration=0;val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);Handler.drain();failure(f)
   check(Bridge.joinStatus()=="joined");Bridge.advanceOwnerRun(a)
   try{Bridge.ack();error("stale run ACK accepted")}catch(expected:IllegalStateException){}
  }
  test("local app ACK rejects changed retained join bytes"){
   val a=activity();a.embeddedDuplexAttachmentGeneration=0;val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);Handler.drain();failure(f)
   check(Bridge.joinStatus()=="joined");Bridge.corruptJoin()
   try{Bridge.ack();error("corrupt joined proof ACK accepted")}catch(expected:IllegalStateException){}
  }
  test("actual post false releases exact reservation and runs no action"){
   val a=activity();Handler.accepted=false;val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);check(Handler.queue.isEmpty());refusal(f,"activity_dispatch_rejected");Handler.accepted=true
  }
  test("pause after enqueue rechecks resumed Activity despite open prior readiness"){
   val a=activity();check(a.embeddedDuplexBootstrapReadiness().bootstrapGateOpen);val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);EmbeddedDuplexDiagnosticActivityGate.paused(a);Handler.next();refusal(f,"resumed_activity_absent")
  }
  test("replacement resumed Activity rechecked at actual dispatch"){
   val a=activity();val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);val b=SpatialCameraPanelActivity(a.directory);EmbeddedDuplexDiagnosticActivityGate.paused(a);EmbeddedDuplexDiagnosticActivityGate.resumed(b);Handler.next();refusal(f,"attachment_absent")
  }
  test("accepted actual guard enters service once under same Original and service_result union"){
   val a=activity();val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);Handler.next();check(f.join().state=="bootstrapped_route_unverified"&&EmbeddedDuplexRuntimeService.calls==1&&Bridge.sameService());check(Bridge.kind()=="service_result"&&Bridge.noNativeJoin());Bridge.requireServiceUnion();Handler.drain()
  }
  test("generic entered service failure remains UNKNOWN uncertain"){
   val a=activity();EmbeddedDuplexRuntimeService.serviceResult=CompletableFuture.failedFuture(IllegalStateException("modeled service failure"));val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);Handler.next();failure(f);check(Bridge.row().getString("state")=="unknown"&&Bridge.row().getString("effect_state")=="uncertain"&&Bridge.noNativeJoin());Handler.drain()
  }
  test("predecessor generic pre-entry failure remains UNKNOWN without invented refusal"){
   val a=activity();val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerFromActivity(a){CompletableFuture.failedFuture<EmbeddedDuplexRuntimeStatus>(IllegalStateException("modeled predecessor generic Activity refusal"))};Bridge.capture(a);failure(f)
   check(Bridge.row().getString("state")=="unknown"&&Bridge.row().getString("effect_state")=="uncertain"&&Bridge.noService()&&EmbeddedDuplexRuntimeService.calls==0&&Bridge.joinStatus()=="pending"&&Bridge.noNativeJoin())
  }
  test("outer cancellation is not source no-entry proof"){
   val a=activity();val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);check(f.cancel(false));check(Bridge.joinStatus()=="pending");Handler.next();check(EmbeddedDuplexRuntimeService.calls==1&&Bridge.sameService()&&Bridge.kind()=="service_result"&&Bridge.noNativeJoin());Handler.drain()
  }
  test("queued then throwing Handler keeps UNKNOWN and held reservation until genuine task exit"){
   val a=activity();Handler.throwAfterQueue=true;val f=EmbeddedDuplexRuntimeService.bootstrapRealPeerCommand(a);Bridge.capture(a);failure(f);check(Bridge.row().getString("state")=="unknown"&&Bridge.commands()==1&&Bridge.joinStatus()=="pending");Handler.throwAfterQueue=false;Handler.drain();check(Bridge.commands()==0&&Bridge.row().getString("state")=="unknown"&&Bridge.noNativeJoin())
  }
  for(reason in listOf("product_inputs_disabled","activity_destroying","local_diagnostic_finished","capture_route_not_selected","already_attached","scene_not_ready","carrier_fence_absent","carrier_surface_invalid","open"))test("actual attachment gate "+reason){
   val a=activity();a.embeddedDuplexAttachmentGeneration=0;val c=a.cameraHwbProjectionRawCarrierCoordinator;c.activeLayerFence=Any();c.activeRenderSurface=ModelSurface(true)
   when(reason){"product_inputs_disabled"->BuildConfig.EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=false;"activity_destroying"->a.embeddedDuplexActivityDestroying=true;"local_diagnostic_finished"->a.embeddedDuplexDiagnosticFinished=true;"capture_route_not_selected"->OwnPackedPoolNative.selected=false;"already_attached"->a.embeddedDuplexAttachmentGeneration=7;"scene_not_ready"->a.spatialSceneReady=false;"carrier_fence_absent"->c.activeLayerFence=null;"carrier_surface_invalid"->c.activeRenderSurface!!.isValid=false}
   check(a.embeddedDuplexBootstrapReadiness().attachmentReason==reason&&EmbeddedDuplexRuntimeService.calls==0)
  }
  test("actual carrier monitor returns distinct fence and Surface facts"){
   val c=activity().cameraHwbProjectionRawCarrierCoordinator;check(!c.sourceCarrierReadiness().fencePresent&&!c.sourceCarrierReadiness().surfaceValid);c.activeRenderSurface=ModelSurface(true);check(!c.sourceCarrierReadiness().fencePresent&&c.sourceCarrierReadiness().surfaceValid);c.activeLayerFence=Any();c.activeRenderSurface!!.isValid=false;check(c.sourceCarrierReadiness().fencePresent&&!c.sourceCarrierReadiness().surfaceValid)
  }
  println("CASES "+cases)
 }
}''')
    stdlib=next(x['path'] for x in compiler if pathlib.Path(x['path']).name.startswith('kotlin-stdlib-'))
    support_before=classpath_pins(support)
    cp=os.pathsep.join([str(classes),str(out/'originals/classes'),str(jar),stdlib,support]);checks=[]
    def run(name,args):
        r=subprocess.run(list(map(str,args)),capture_output=True,timeout=90);(work/(name+'.stdout')).write_bytes(r.stdout);(work/(name+'.stderr')).write_bytes(r.stderr);checks.append({'name':name,'exit':r.returncode})
        if r.returncode: raise RuntimeError(name+': '+r.stdout.decode(errors='replace')+r.stderr.decode(errors='replace'))
    kt=list((work/'src').glob('*.kt'));js=list((work/'src').glob('*.java'))
    run('kotlinc',[java/'bin/java.exe','-cp',os.pathsep.join(x['path'] for x in compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',classes,*kt,*js])
    run('javac',[java/'bin/javac.exe','--release','17','-encoding','UTF-8','-cp',cp,'-d',classes,*js])
    run('controls',[java/'bin/java.exe','-Djava.io.tmpdir='+str(out/'originals/runtime'),'-Dhost.proc='+str(out/'originals/proc'),'-cp',cp,pkg+'.AdmissionControls'])
    after={p:sha(pathlib.Path(p)) for p in before}
    if before!=after: raise ValueError('Source changed during exact Kotlin controls')
    if sha(manifest)!=manifest_hash:raise ValueError('Toolchain manifest changed during controls')
    if classpath_pins(support)!=support_before:raise ValueError('Support classes changed during controls')
    return {'status':'passed','case_count':(work/'controls.stdout').read_text().count('PASS '),'checks':checks,'source_before':before,'source_after':after,'exact_extractions':extracts,'toolchain_manifest':{'path':str(manifest),'sha256':sha(manifest)},'compiler_jars':compiler,'classpath_dependencies':json_dependencies,'support_inputs':support_before,'tools':[{'path':str(java/'bin'/name),'sha256':sha(java/'bin'/name)} for name in ('java.exe','javac.exe')],'generated_sources':{str(p.relative_to(work)):sha(p) for p in (work/'src').rglob('*') if p.is_file()},'modeled_leaves':['Activity field container/UI marker queue','Handler queue/post(false)/queued-throw and Looper','Surface validity/source fence presence','Own capture route Boolean','low-level service counter/completion under actual held Original; no Native action','modeled predecessor generic Activity throwable (actual current wrapper/Original handles it)','isolated per-case app Context/static fixture reset; modeled selector directory sync/permission leaves'],'limits':['Actual production Original/store/AppAdmission and RuntimeStatus plus exact Activity2/Gate5/Carrier1/Service3 methods execute.','Provider/parser composition checked separately by actual Android/JUnit.','No APK/device/private/hardware/Native/physical cleanup claim.']}

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[2]);p.add_argument('--output',type=pathlib.Path);p.add_argument('--java-home',type=pathlib.Path);p.add_argument('--json-jar',type=pathlib.Path);p.add_argument('--support-classpath',default=os.environ.get('RUSTY_QUEST_APP_TEST_CLASSPATH',''));p.add_argument('--kotlin-toolchain-manifest',default=os.environ.get('RUSTY_QUEST_KOTLIN_TOOLCHAIN_MANIFEST'));p.add_argument('--smoke',action='store_true');a=p.parse_args()
    root=a.root.resolve();out=(a.output or root/'target'/('app-admission-'+uuid.uuid4().hex)).resolve()
    if not out.is_relative_to(root/'target') or out.exists():raise ValueError('Create-new target output required')
    out.mkdir(parents=True);java=a.java_home or pathlib.Path(os.environ['JAVA_HOME']);jar=a.json_jar or next((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').rglob('*.jar'))
    before={str(x.relative_to(root)):sha(x) for x in [pathlib.Path(__file__).resolve(),root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py']}
    cmd=[sys.executable,str(root/'tools/checks/Test-EmbeddedDuplexOriginalsHost.py'),'--root',str(root),'--output',str(out/'originals'),'--java-home',str(java),'--json-jar',str(jar),'--support-classpath',a.support_classpath]
    r=subprocess.run(cmd,capture_output=True,timeout=120);(out/'originals.stdout').write_bytes(r.stdout);(out/'originals.stderr').write_bytes(r.stderr)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    kotlin=kotlin_controls(root,out,java,jar,a.support_classpath,a.kotlin_toolchain_manifest)
    original=json.loads((out/'originals/RESULT.json').read_text())
    for rel,digest in {**before,**original['sources']}.items():
        if sha(root/rel)!=digest:raise ValueError('Source changed during complete host controls: '+rel)
    (out/'RESULT.json').write_text(json.dumps({'status':'passed','runner_sources':before,'originals':str(out/'originals/RESULT.json'),'kotlin':kotlin,'limits':['Actual methods with declared Android/Surface/Native-service leaves; no APK/device/physical closure claim.']},indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':'passed','kotlin_cases':kotlin['case_count'],'output':str(out)}))
if __name__=='__main__':main()
