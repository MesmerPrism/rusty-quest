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
room = '\n'.join(method('SpatialVirtualRoomModule.kt', s, optional=o) for s, o in [
    ('  fun destroy(', False), ('  fun retireScene(', True), ('  private fun handleLoaded(', False),
])
staged = '\n'.join(method('SpatialStagedAssetModule.kt', s, optional=o) for s, o in [
    ('  fun destroy(', False), ('  fun retireScene(', True),
    ('  fun startIfRequested(', False),
])
carrier = '\n'.join(method('SpatialCameraHwbProjectionPanelCarrierCoordinator.kt', s, optional=o) for s, o in [
    ('  fun cleanup(', False), ('  fun retireScene(', True),
    ('  private fun releaseSceneHandles(', True), ('  private fun adoptSurface(', True), ('  private fun adoptPanel(', True),
])
carrier_text=(base/'SpatialCameraHwbProjectionPanelCarrierCoordinator.kt').read_text()
carrier_end=carrier_text.index('  fun run(')
if '  private fun adoptSurface(' in carrier_text:
    carrier_end=carrier_text.rfind('  @Synchronized',0,carrier_text.index('  private fun adoptSurface('))
carrier_callbacks=carrier_text[carrier_text.index('  fun videoPanelBindings()'):carrier_end]
(out / 'Shutdown.kt').write_text(shutdown)
(out / 'Latency.kt').write_text((base / 'SpatialCameraLatencyDiagnosticModule.kt').read_text())
(out / 'Clock.kt').write_text('''package android.os
object SystemClock {fun elapsedRealtime()=System.nanoTime()/1000000;fun elapsedRealtimeNanos()=System.nanoTime()}
''')
(out / 'Controls.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
import java.util.concurrent.*
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.*
class Model { var live=true; var deletes=0;var invalidNativeCalls=0;fun requireLive(){if(!live)invalidNativeCalls++;check(live){"DataModel native pointer null"}} }
class Entity(val model:Model) { var onDelete:(()->Unit)?=null;var beforeMutation:(()->Unit)?=null;fun destroy(){model.requireLive();model.deletes++;onDelete?.invoke()};fun setComponent(x:Any){beforeMutation?.invoke();model.requireLive()};inline fun <reified T> tryGetComponent():T?{model.requireLive();return null};companion object{lateinit var creationModel:Model;fun create(vararg c:Any)=Entity(creationModel).also{creationModel.requireLive()}} }
class Mesh(val uri:Any?=null,val hittable:Any?=null,val shader:String="")
class Intent(val enabled:Boolean=true)
object Uri{fun parse(s:String)=s}
class Vector3(val x:Float)
fun Vector3(x:Float,y:Float,z:Float)=Vector3(x)
class Quaternion(val x:Float,val y:Float,val z:Float)
class Scale(val v:Vector3)
class Visible(val enabled:Boolean)
class Grabbable(val type:GrabbableType)
enum class GrabbableType{PIVOT_Y}
object MeshCollision{val NoCollision=Any()}
object SceneMaterial{const val UNLIT_SHADER="fixture"}
class StagedDecision(val applied:Boolean){val lockRevision=1;val lockSha256="fixture";fun markerFields()="fixture"}
object SpatialAdapterNativeAuthority{var calls=0;fun resolveAsset(enabled:Boolean)=StagedDecision(enabled).also{calls++}}
class SpatialStagedAssetConfig(val enabled:Boolean){val meshUri="fixture";val grabbable=false;val positionX=0f;val positionY=0f;val positionZ=0f;val rotationXDegrees=0f;val rotationYDegrees=0f;val rotationZDegrees=0f;val scale=1f;fun activationInput()=enabled;fun isRawFbxUri()=false;fun identityKey()="fixture";fun labelToken()="fixture";fun meshUriSchemeToken()="fixture";fun sourceFormatToken()="fixture";companion object{fun from(i:Intent?)=SpatialStagedAssetConfig(i?.enabled==true)}}
class ModelResource(val model:Model) {fun destroy(){model.requireLive()}}
class Scene(val model:Model) {fun destroyObject(o:ModelResource){o.destroy()};fun destroyObject(o:PanelSceneObject){o.destroy()}}
class Job {var cancelled=false;fun cancel(){cancelled=true}}
class GLXFInfo(val model:Model) {fun getNodeByName(name:String):Node{model.requireLive();return Node(Entity(model))}}
class Node(val entity:Entity)
class Surface(val isValid:Boolean=true)
class Pose(val t:Int=0,val q:Int=0){constructor(v:Vector3,q:Quaternion):this()}
class Transform(val pose:Pose)
class PanelSceneObject(val surface:Surface=Surface(),val model:Model?=null){fun destroy(){model?.requireLive()}}
typealias AndroidSurface=Surface
data class CameraHwbProjectionPlane(val center:Vector3=Vector3(0f),val targetDistanceMeters:Float=1f,val projectionWidthMeters:Float=1f,val projectionHeightMeters:Float=1f)
fun activityVectorMarker(v:Vector3)="fixture"
class SpatialVideoProjectionSettings
class CameraHwbProjectionVideoPanelBindings(val adoptSurface:(Surface)->Unit,val settings:(Any?)->Unit,val adoptPanel:(PanelSceneObject)->Unit,val planeForPlacement:()->CameraHwbProjectionPlane,val updateLayer:(CameraHwbProjectionPlane)->String,val currentProjectionMarkerFields:()->String,val projectionMarkerFields:(CameraHwbProjectionPlane?)->String,val stereoMarkerFields:()->String,val videoProjectionMarkerFields:()->String,val startCarrier:(String)->Unit,val emitMarker:(String)->Unit)
object CameraHwbProjectionModule {fun scenePanelCarrierDestroyedMarker(reason:String,nativeStopped:Boolean,entityDestroyed:Boolean,sceneObjectDestroyed:Boolean,carrier:String,cleanupStatus:String)="fixture"}
class CarrierNativeState(val receiptLibraryLoaded:Boolean=true)
class CarrierBindings(val model:Model) {
 val scene=Scene(model);var projected:Entity?=null;var stops=0;var failStop=false;var libraryLoaded=true
 val stopEntered=CountDownLatch(1);val releaseStop=CountDownLatch(1);var blockStop=false
 fun nativeState()=CarrierNativeState(libraryLoaded);fun stopNative(){stops++;stopEntered.countDown();if(blockStop)releaseStop.await();check(!failStop){"modeled Native stop failure"}}
 fun manualCustomMeshEnabled()=true;fun projectionEntity()=projected;fun setProjectionEntity(e:Entity?){projected=e}
 fun carrierToken()="fixture";val marker:(String)->Unit={}
 fun panelMediaSettings(){};val projectionPlane:()->CameraHwbProjectionPlane={CameraHwbProjectionPlane()}
 val projectionMarkerFields:(CameraHwbProjectionPlane?)->String={"fixture"};val stereoMarkerFields:()->String={"fixture"}
 fun videoSettings()=SpatialVideoProjectionSettings();fun videoProjectionMarkerFields(s:SpatialVideoProjectionSettings)="fixture"
}
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
class SpatialVirtualRoomModule(val model:Model,room:Boolean=false,skybox:Boolean=false) {
 var roomEntity:Entity?=if(room)Entity(model) else null;var skyboxEntity:Entity?=if(skybox)Entity(model) else null
 var skyboxSceneObject:ModelResource?=if(skybox)ModelResource(model) else null
 var skyboxMesh:ModelResource?=if(skybox)ModelResource(model) else null
 var skyboxMaterial:ModelResource?=if(skybox)ModelResource(model) else null
 var skyboxTexture:ModelResource?=if(skybox)ModelResource(model) else null
 var sceneRetired=false;var configured=room||skybox;var loaded=room;var loadJob:Job?=if(room)Job() else null
 val scene=Scene(model);val MODULE_ID="fixture";val SCENE_URI="fixture";val ENVIRONMENT_NODE="fixture"
 fun marker(s:String){}
 fun lateLoaded(after:()->Unit)=handleLoaded(GLXFInfo(model),after)
''' + room + '''
}
class SpatialStagedAssetModule(val model:Model,enabled:Boolean=false) {
 var activeEntity:Entity?=if(enabled)Entity(model) else null;var activeKey="fixture";var sceneRetired=false
 val MODULE_ID="fixture";fun markerToken(s:String)=s;fun marker(s:String){}
 val ACTIVATION_RECEIPT_SCHEMA="fixture";val ACTIVATION_EFFECTIVE_MARKER="fixture";fun formatFloat(f:Float)=f.toString()
''' + staged + '''
}
class SpatialCameraHwbProjectionPanelCarrierCoordinator(val bindings:CarrierBindings,enabled:Boolean=false) {
 var panelEntity:Entity?=if(enabled)Entity(bindings.model) else null
 var panelSceneObject:PanelSceneObject?=if(enabled)PanelSceneObject(model=bindings.model) else null
 var panelSurface:Surface?=if(enabled)Surface() else null;var surfaceConsumerCalled=enabled;var panelReady=enabled;var nativeStarted=enabled;var startMask=0L
 var sceneRetired=false;var sceneRetirementResult:Pair<Boolean,Boolean>?=null
 init{bindings.projected=panelEntity}
 fun updateLayer(p:CameraHwbProjectionPlane,s:String)="fixture";fun startIfReady(s:String){}
''' + carrier + '\n' + carrier_callbacks + '''
}
object EmbeddedDuplexDiagnosticActivityGate {fun paused(x:Any){}}
object BuildConfig {const val EMBEDDED_DUPLEX_FRESH_SETUP_POLICY="source-bound-v2"}
open class ModelSdkActivity(val model:Model) {
 open fun onSpatialShutdown(){}
 open fun onDestroy(){onSpatialShutdown();model.live=false}
 fun finishingStop(){onSpatialShutdown();model.live=false}
}
class SpatialCameraPanelActivity(model:Model,room:Boolean=false,skybox:Boolean=false,staged:Boolean=false,carrier:Boolean=false):ModelSdkActivity(model) {
 private val cameraLatencyDiagnosticModule=SpatialCameraLatencyDiagnosticModule(SpatialCameraLatencyDiagnosticBindings(
   readSystemProperty={when(it){CAMERA_LATENCY_ENABLED_PROPERTY->"true";CAMERA_LATENCY_PRESENTATION_POSE_MODE_PROPERTY->"openxr-locate-views";else->""}},
   applyNative={0L},recordViewerPose={_,_->0L},marker={},
   configureOpenXr={model.requireLive();0L},releaseOpenXr={_,_->model.requireLive();1L}))
 init{cameraLatencyDiagnosticModule.onSpatialReady();cameraLatencyDiagnosticModule.poll("fixture",true)}
 var embeddedDuplexActivityDestroying=false;var embeddedDuplexDiagnosticFuture:CompletableFuture<Unit>?=null
 var embeddedDuplexAttachmentGeneration=0L;var embeddedDuplexRuntimeFuture:CompletableFuture<Unit>?=null
 var embeddedDuplexDiagnosticFinished=false
 val immersiveVideoPanelCoordinator=SpatialImmersiveVideoPanelCoordinator(model)
 val backgroundImmersiveVideoPanelCoordinator=SpatialImmersiveVideoPanelCoordinator(model)
 val spatialVirtualRoomModule=SpatialVirtualRoomModule(model,room,skybox)
 val stagedAssetModule=SpatialStagedAssetModule(model,staged)
 val cameraHwbProjectionPanelCarrierCoordinator=SpatialCameraHwbProjectionPanelCarrierCoordinator(CarrierBindings(model),carrier)
 val executor=Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon=true } }
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
   try {immersiveVideoPanelCoordinator.destroy("activity-destroy");backgroundImmersiveVideoPanelCoordinator.destroy("activity-destroy");cameraHwbProjectionPanelCarrierCoordinator.cleanup("activity-destroy");stagedAssetModule.destroy("activity-destroy");spatialVirtualRoomModule.destroy("activity-destroy");failure.complete(null)}
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
 for((room,skybox,staged) in listOf(Triple(true,false,false),Triple(false,true,false),Triple(false,false,true),Triple(true,true,true))){
   val optional=SpatialCameraPanelActivity(Model(),room,skybox,staged);optional.block();val roomJob=optional.spatialVirtualRoomModule.loadJob
   optional.onDestroy();optional.release.countDown();check(optional.failure.get(2,TimeUnit.SECONDS)==null)
   check(optional.executor.awaitTermination(2,TimeUnit.SECONDS));check(optional.model.invalidNativeCalls==0){"optional scene owner used invalid model: room=$room skybox=$skybox staged=$staged"}
   val count=optional.model.deletes;optional.onSpatialShutdown();optional.spatialVirtualRoomModule.destroy("repeat");optional.stagedAssetModule.destroy("repeat")
   check(optional.model.deletes==count&&optional.model.invalidNativeCalls==0)
   if(room)check(roomJob!!.cancelled)
   var callback=false;optional.spatialVirtualRoomModule.lateLoaded{callback=true}
   check(!callback&&optional.model.invalidNativeCalls==0){"late room callback reentered SDK or Activity"}
 }
 val panel=SpatialCameraPanelActivity(Model(),carrier=true);panel.block();val owner=panel.cameraHwbProjectionPanelCarrierCoordinator;val heldSurface=owner.panelSurface
 owner.bindings.blockStop=true;panel.onDestroy()
 check(owner.bindings.stops==0&&owner.nativeStarted&&owner.panelSurface===heldSurface){"SDK hook moved Native stop or dropped its retained Surface"}
 check(owner.panelEntity==null&&owner.panelSceneObject==null&&owner.bindings.projected==null){"retained panel SDK handles escaped the pre-model handoff"}
 val cb=owner.videoPanelBindings();cb.adoptSurface(Surface());cb.adoptPanel(PanelSceneObject());check(owner.panelSurface===heldSurface&&owner.panelSceneObject==null)
 panel.release.countDown();check(owner.bindings.stopEntered.await(2,TimeUnit.SECONDS));panel.onSpatialShutdown()
 check(owner.panelSurface===heldSurface&&owner.nativeStarted){"in-flight Native stop lost Surface custody"}
 owner.bindings.releaseStop.countDown();check(panel.failure.get(2,TimeUnit.SECONDS)==null);check(panel.executor.awaitTermination(2,TimeUnit.SECONDS))
 check(owner.panelSurface==null&&!owner.nativeStarted&&owner.bindings.stops==1&&panel.model.invalidNativeCalls==0)
 val failedOwner=SpatialCameraHwbProjectionPanelCarrierCoordinator(CarrierBindings(Model()),true);failedOwner.retireScene("sdk");val failedSurface=failedOwner.panelSurface;failedOwner.bindings.failStop=true
 check(failedOwner.cleanup("failed-stop")=="incomplete"&&failedOwner.panelSurface===failedSurface&&failedOwner.nativeStarted){"failed Native stop lost custody"}
 failedOwner.bindings.failStop=false;failedOwner.bindings.libraryLoaded=false;val stoppedBefore=failedOwner.bindings.stops
 check(failedOwner.cleanup("unavailable-stop")=="incomplete"&&failedOwner.panelSurface===failedSurface&&failedOwner.nativeStarted&&failedOwner.bindings.stops==stoppedBefore){"unavailable Native callback lost custody"}
 failedOwner.bindings.libraryLoaded=true;check(failedOwner.cleanup("joined-stop")=="destroyed"&&failedOwner.panelSurface==null&&!failedOwner.nativeStarted)
 val assetModel=Model();Entity.creationModel=assetModel;val asset=SpatialStagedAssetModule(assetModel)
 check(asset.startIfRequested(Intent(),"live"));check(asset.activeEntity!=null)
 asset.retireScene("sdk");assetModel.live=false;val nativeCalls=SpatialAdapterNativeAuthority.calls
 check(!asset.startIfRequested(Intent(),"late")&&asset.activeEntity==null&&assetModel.invalidNativeCalls==0&&SpatialAdapterNativeAuthority.calls==nativeCalls){"retired staged callback attempted activation or Entity creation"}
 println("Actual Activity scene ownership: video, room/skybox, staged asset and panel carrier teardown; repeat/reentry, late callbacks and blocked/failed Native stop passed; no device or SDK runtime proof.")
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
