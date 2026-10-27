"""Actual Activity/video teardown methods; SDK, Android and codec leaves modeled."""
import argparse, hashlib, json, os, pathlib, subprocess, sys
sys.dont_write_bytecode = True

parser = argparse.ArgumentParser()
for name in ('repo-root', 'output-root', 'java-home', 'kotlin-lib'):
    parser.add_argument('--' + name, type=pathlib.Path, required=True)
a = parser.parse_args()
root, out = a.repo_root.resolve(), a.output_root.resolve()
if out.exists() or not out.is_relative_to(root / 'target'):
    raise ValueError('Fresh fixture-owned target required')
out.mkdir(parents=True)
base = root / 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel'
extracts = []

def method(filename, signature, optional=False):
    text = (base / filename).read_text()
    start = text.find(signature)
    if start < 0:
        if optional: return ''
        raise ValueError('Actual method absent: ' + signature)
    brace = text.index('{', start)
    depth = 1; i = brace + 1
    # These selected methods have balanced braces in their strings/comments too.
    while depth:
        depth += (text[i] == '{') - (text[i] == '}'); i += 1
    body = text[start:i]
    # Keep synchronization annotations that belong to the selected actual method.
    before = text[:start].rstrip()
    if before.endswith('@Synchronized'): body = '  @Synchronized\n' + body
    extracts.append(dict(source=filename, signature=signature,
                         sha256=hashlib.sha256(body.encode()).hexdigest()))
    return body

coordinator = '\n'.join(method('SpatialImmersiveVideoPanelCoordinator.kt', s, optional=o) for s, o in [
    ('  fun destroy(', False), ('  fun retireScene(', True),
    ('  private fun destroySceneEntities(', True),
    ('  private fun destroyVideoEntity(', True), ('  private fun destroyBackdropEntity(', True),
    ('  private fun acceptVideoSurface(', True), ('  private fun acceptVideoPanel(', True),
    ('  fun spawnAtViewer(', False), ('  fun updateFromViewer(', False),
    ('  private fun dispatchPendingVideoLoadRecenter(', False),
])
# Include the actual registration callback bodies, not a second callback policy.
text = (base / 'SpatialImmersiveVideoPanelCoordinator.kt').read_text()
registration_end = text.index('  private fun blackBackdropPanelRegistration(')
if '  private fun acceptVideoSurface(' in text:
    registration_end = text.rfind('  @Synchronized', 0, text.index('  private fun acceptVideoSurface('))
registrations = text[text.index('  fun panelRegistrations()'):registration_end].rstrip()
extracts.append(dict(source='SpatialImmersiveVideoPanelCoordinator.kt', signature='panelRegistrations', sha256=hashlib.sha256(registrations.encode()).hexdigest()))
activity = '\n'.join(method('SpatialCameraPanelActivity.kt', s, optional=o) for s, o in [
    ('  override fun onDestroy()', False), ('  override fun onSpatialShutdown()', True),
    ('  private fun scheduleOrderedActivityDestroyCleanup()', False),
])
shutdown = (base / 'SpatialVideoLifecycleShutdownCoordinator.kt').read_text()
(out / 'Shutdown.kt').write_text(shutdown)
(out / 'Controls.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
import java.util.concurrent.*
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.*
class Model { var live=true; var deletes=0 }
class Entity(val model:Model) { var onDelete:(()->Unit)?=null;var beforeMutation:(()->Unit)?=null;fun destroy(){check(model.live){"DataModel native pointer null"};model.deletes++;onDelete?.invoke()};fun setComponent(x:Any){beforeMutation?.invoke();check(model.live){"late Entity mutation"}} }
class Surface(val isValid:Boolean=true)
class Pose(val t:Int=0,val q:Int=0)
class Transform(val pose:Pose)
class PanelSceneObject(val surface:Surface=Surface())
class Config { val path="fixture";fun markerFields()="fixture" }
typealias SpatialImmersiveVideoConfig=Config
class RecenterRequest(val detail:String="fixture",val selectionSource:String="fixture")
class RecenterGate {fun consume(path:String,route:String)=RecenterRequest()}
class Presentation(val value:Int=0) { val token="head-fixed";companion object {val HeadFixedBorder=Presentation();val entries=listOf(HeadFixedBorder)} }
typealias SpatialImmersiveVideoPresentationMode=Presentation
object SpatialImmersiveVideoSessionPolicy {fun directPanelPresentation(c:Config,p:Presentation)=Config()}
class VideoSurfacePanelRegistration(val id:Int,val surfaceConsumer:(Any?,Surface)->Unit,val settingsCreator:()->Unit,val panelSetup:(PanelSceneObject,Any?)->Unit)
typealias PanelRegistration=VideoSurfacePanelRegistration
class Handler { var removed=false;fun removeCallbacksAndMessages(x:Any?){removed=true} }
fun activityMarkerToken(x:String)=x
class SpatialImmersiveVideoPanelCoordinator(val model:Model) {
 var entity:Entity?=Entity(model);var blackBackdropEntity:Entity?=Entity(model)
 var panelSceneObject:PanelSceneObject?=PanelSceneObject();var surface:Surface?=Surface()
 var spawnPose:Pose?=null;var latestViewerPose:Pose?=null
 var sceneRetired=false;var destroyed=false;var releaseCount=0;var starts=0;var spawns=0
 var directVideoConsumerRequired=true;var playbackEnabled=true
 val autoRecenterGate=RecenterGate();var recenterCalls=0
 val recenterAfterNewVideoLoad:(String,String)->Boolean={_,_->recenterCalls++;true}
 fun lateRecenter()=dispatchPendingVideoLoadRecenter(Config(),"fixture")
 var activeIndex=0;var presentationMode=Presentation.HeadFixedBorder
 var transitionTargetIndex:Int?=null;var transitionGeneration=0L;var transitionSource="";var incomingFadeGeneration=-1L
 val transitionHandler=Handler();val requested=true;val catalog=listOf(Config());val MAX_SESSION_PACKS=1
 val panelRegistrationIds=mapOf(Presentation.HeadFixedBorder to listOf(1))
 val activeConfig:Config? get()=catalog.getOrNull(activeIndex)
 fun emitMarker(s:String){}
 fun cancelSelectionTransition(s:String){transitionGeneration++;transitionTargetIndex=null}
 fun releasePlayer(s:String,quitThreadAfterRelease:Boolean=false){releaseCount++}
 fun startPlayer(c:Config,s:Surface){starts++}
 fun spawnActiveEntity(s:String){spawns++;entity=Entity(model)}
 fun headFixedPose(p:Pose)=p
 fun blackBackdropPanelRegistration(i:Int,p:Presentation)=VideoSurfacePanelRegistration(0,{_,_->},{},{_,_->})
 fun mediaPanelSettings(c:Config,p:Presentation){}
 fun applyDirectLayerOpacity(f:Float)=true
''' + coordinator + '\n' + registrations + '''
}
object EmbeddedDuplexDiagnosticActivityGate {fun paused(x:Any){}}
object BuildConfig {const val EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="source-bound-v2"}
open class ModelSdkActivity(val model:Model) {
 open fun onSpatialShutdown(){}
 open fun onDestroy(){onSpatialShutdown();model.live=false}
 fun finishingStop(){onSpatialShutdown();model.live=false}
}
class SpatialCameraPanelActivity(model:Model):ModelSdkActivity(model) {
 var embeddedDuplexActivityDestroying=false;var embeddedDuplexDiagnosticFuture:CompletableFuture<Unit>?=null
 var embeddedDuplexAttachmentGeneration=0L;var embeddedDuplexRuntimeFuture:CompletableFuture<Unit>?=null
 var embeddedDuplexDiagnosticFinished=false
 val immersiveVideoPanelCoordinator=SpatialImmersiveVideoPanelCoordinator(model)
 val backgroundImmersiveVideoPanelCoordinator=SpatialImmersiveVideoPanelCoordinator(model)
 val executor=Executors.newSingleThreadExecutor()
 private val videoLifecycleShutdownCoordinator=SpatialVideoLifecycleShutdownCoordinator(executor)
 val entered=CountDownLatch(1);val release=CountDownLatch(1)
 val failure=CompletableFuture<Throwable?>()
 val spatialPeerProjectionRuntimeCoordinator=Peer()
 class Peer {fun retireForActivityDestroy(s:String)=true}
 fun detachUninitializedEmbeddedDuplexDisplay(){}
 fun stopProjectionSourceLifecyclePolling(){}
 fun runOnUiThread(f:()->Unit)=f()
 fun marker(s:String){}
 fun finishActivityDestroyCleanup(retired:Boolean){
   try {immersiveVideoPanelCoordinator.destroy("activity-destroy");backgroundImmersiveVideoPanelCoordinator.destroy("activity-destroy");failure.complete(null)}
   catch(t:Throwable){failure.complete(t)}
 }
 fun block(){videoLifecycleShutdownCoordinator.dispatch{entered.countDown();release.await()};check(entered.await(2,TimeUnit.SECONDS))}
''' + activity + '''
}
fun main(){
 val model=Model();val a=SpatialCameraPanelActivity(model);a.block()
 a.onDestroy();check(!model.live);a.release.countDown()
 val error=a.failure.get(2,TimeUnit.SECONDS)
 check(error==null){"actual queued destroy used retired SDK model: $error"}
 check(a.executor.awaitTermination(2,TimeUnit.SECONDS))
 val after=model.deletes;a.onSpatialShutdown();a.immersiveVideoPanelCoordinator.destroy("repeat")
 check(model.deletes==after){"duplicate Entity deletion"}
 val c=a.immersiveVideoPanelCoordinator;val beforeSpawns=c.spawns;c.spawnAtViewer(Pose());c.updateFromViewer(Pose())
 check(c.spawns==beforeSpawns){"retired coordinator respawned"}
 val starts=c.starts
 for(reg in c.panelRegistrations()){reg.surfaceConsumer(null,Surface());reg.panelSetup(PanelSceneObject(),null)}
 check(c.starts==starts&&c.surface==null&&c.panelSceneObject==null){"late SDK callback rebound retired handles"}
 check(!c.lateRecenter()&&c.recenterCalls==0){"retired video callback reentered Activity scene recenter"}
 val finishing=SpatialCameraPanelActivity(Model());finishing.block();finishing.finishingStop();finishing.onDestroy();finishing.release.countDown()
 check(finishing.failure.get(2,TimeUnit.SECONDS)==null);check(finishing.executor.awaitTermination(2,TimeUnit.SECONDS))
 val live=Model();val coordinator=SpatialImmersiveVideoPanelCoordinator(live)
 coordinator.entity!!.onDelete={coordinator.destroy("reentrant")};coordinator.destroy("first");check(live.deletes==2){"reentrant destroy did not take both handles first"}
 val replacement=SpatialImmersiveVideoPanelCoordinator(Model());replacement.spawnAtViewer(Pose());check(replacement.spawns==0){"existing live carrier should be retained"}
 replacement.entity=null;replacement.blackBackdropEntity=null;replacement.spawnAtViewer(Pose());check(replacement.spawns==1){"fresh coordinator could not spawn"}
 for(reg in replacement.panelRegistrations()){reg.surfaceConsumer(null,Surface());reg.panelSetup(PanelSceneObject(),null)}
 check(replacement.starts==1&&replacement.panelSceneObject!=null){"live callbacks were suppressed"}
 replacement.destroy("replacement")
 val concurrent=SpatialCameraPanelActivity(Model());val mutationEntered=CountDownLatch(1);val mutationRelease=CountDownLatch(1)
 concurrent.immersiveVideoPanelCoordinator.entity!!.beforeMutation={mutationEntered.countDown();mutationRelease.await()}
 val mutation=CompletableFuture.runAsync{concurrent.immersiveVideoPanelCoordinator.updateFromViewer(Pose())}
 check(mutationEntered.await(2,TimeUnit.SECONDS));val sdkEntered=CountDownLatch(1)
 val sdk=CompletableFuture.runAsync{sdkEntered.countDown();concurrent.finishingStop()}
 check(sdkEntered.await(2,TimeUnit.SECONDS));Thread.sleep(30)
 check(concurrent.model.live){"SDK teardown overtook an entered coordinator scene mutation"}
 mutationRelease.countDown();mutation.get(2,TimeUnit.SECONDS);sdk.get(2,TimeUnit.SECONDS)
 concurrent.onDestroy();check(concurrent.failure.get(2,TimeUnit.SECONDS)==null);check(concurrent.executor.awaitTermination(2,TimeUnit.SECONDS))
 println("Actual Activity/video teardown: blocked worker, finishing onStop, repeated/reentrant destroy, in-flight mutation and live/late SDK callbacks passed; no device or SDK runtime proof.")
}
''')
(out / 'Leaves.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex
import java.util.concurrent.CompletableFuture
object OwnStereoCaptureRuntime {fun currentForApplication():OwnStereoCaptureRuntime?=null;fun requestStopOwn(){}}
object EmbeddedDuplexRuntimeService {fun closeNoMedia(a:Any,g:Long)=CompletableFuture.completedFuture(Unit);fun closeProduct(a:Any,g:Long)=CompletableFuture.completedFuture(Unit)}
object EmbeddedDuplexActivityAttachment {fun retryLocalDiagnosticCleanup(a:Any,g:Long)=CompletableFuture.completedFuture(Unit)}
''')
libs = sorted(a.kotlin_lib.glob('*.jar'))
stdlib = next(p for p in libs if p.name.startswith('kotlin-stdlib-'))
classes = out / 'classes'; classes.mkdir()
commands = [('compile', [str(a.java_home/'bin/java.exe'), '-cp', os.pathsep.join(map(str,libs)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib), '-d', str(classes), *map(str,out.glob('*.kt'))]),
            ('controls', [str(a.java_home/'bin/java.exe'), '-cp', os.pathsep.join(map(str,[classes,stdlib])), 'io.github.mesmerprism.rustyquest.spatial_camera_panel.ControlsKt'])]
(out/'extracts.json').write_text(json.dumps(extracts,indent=2))
for name, command in commands:
    child=subprocess.run(command,capture_output=True,timeout=60)
    (out/(name+'.stdout')).write_bytes(child.stdout);(out/(name+'.stderr')).write_bytes(child.stderr)
    (out/(name+'.process.json')).write_text(json.dumps(dict(argv=command,exit_code=child.returncode),indent=2))
    if child.returncode:
        sys.stdout.buffer.write(child.stdout);sys.stderr.buffer.write(child.stderr);raise SystemExit(child.returncode)
    if name=='controls':sys.stdout.buffer.write(child.stdout)
