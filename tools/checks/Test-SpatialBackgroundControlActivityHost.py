"""Compile actual neutral background binding methods; Android/SDK/media leaves modeled."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys

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
observation = base / 'SpatialBackgroundControl.kt'
assert observation.is_file(), 'Missing neutral cached background observation binding'
(out / observation.name).write_bytes(observation.read_bytes())
activity = (base / 'SpatialCameraPanelActivity.kt').read_text(encoding='utf-8')
coordinator = (base / 'SpatialImmersiveVideoPanelCoordinator.kt').read_text(encoding='utf-8')
extracts = []


def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    body = text[start:end]
    if text[:start].rstrip().endswith('@Synchronized'):
        body = '  @Synchronized\n' + body
    extracts.append(dict(signature=signature, sha256=hashlib.sha256(body.encode()).hexdigest()))
    return body


activity_methods = '\n'.join(method(activity, s) for s in [
    '  internal fun backgroundControlObservation(',
    '  internal fun selectBackgroundControlMode(',
    '  private fun refreshBackgroundControlObservation(',
    '  private fun backgroundControlObservationCallback(',
    '  private fun invalidateBackgroundControl(',
    '  private fun completeBackgroundControlSceneReady(',
    '  private fun completeBackgroundControlSceneTick(',
    '  private fun setSpatialBackgroundMode(',
    '  override fun onSceneTick(',
    '  override fun onVRReady(',
    '  override fun onVRPause(',
    '  override fun onPause(',
])
# These placement controls supplement the executable extracted methods below.
ready = method(activity, '  override fun onSceneReady(')
assert ready.rstrip().endswith('completeBackgroundControlSceneReady()\n  }')
for signature in ('  override fun onSpatialShutdown(', '  override fun onDestroy('):
    body = method(activity, signature)
    assert body[body.index('{') + 1:].lstrip().startswith('invalidateBackgroundControl(retired = true)')
pause = method(activity, '  override fun onPause(')
assert pause[pause.index('{') + 1:].lstrip().startswith('invalidateBackgroundControl()')
vr_pause = method(activity, '  override fun onVRPause(')
assert vr_pause[vr_pause.index('{') + 1:].lstrip().startswith('invalidateBackgroundControl()')
activity_fields = activity[activity.index('  @Volatile private var backgroundControlResources ='):activity.index('  private val storedProfileAuthority:')]
# Include the real lazy initializer: construction failure must not be retried by observation/finally.
coordinator_fields = coordinator[coordinator.index('  private var observedVideoEntityId ='):coordinator.index('  private val retainBackingEntities =')]
coordinator_accessor = '\n'.join(method(coordinator, s) for s in [
    '  fun backgroundControlObservation(', '  fun publishBackgroundControlObservation(',
])
marker_binding = coordinator[coordinator.index('  private val emitMarker: (String) -> Unit ='):coordinator.index('  private val textureRoute =')]
(out / 'Controls.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
object BuildConfig { var DEBUG=true }
object Looper { val main=Any(); var current:Any?=main; fun myLooper()=current;fun getMainLooper()=main }
enum class SpatialBackgroundMode(val token:String){Black("black"),Video("world-video"),Passthrough("passthrough")}
enum class SpatialImmersiveVideoLayerOwner {Background}
enum class SpatialImmersiveVideoPresentationMode {WorldAnchored}
class SpatialImmersiveVideoRouteResolution
class SpatialImmersiveVideoSessionSnapshot
class Entity(private val rawId:Long){var poison=false;val id:Long get(){check(!poison){"SDK Entity ID getter reached during observation"};return rawId}}
class Surface
class Resources {var id=1;var value=1;var lookups=0;var reads=0;var fail=false
 fun getIdentifier(name:String,kind:String,pkg:String):Int{check(name=="rusty_quest_spatial_background_diagnostic_enabled"&&kind=="integer"&&pkg=="fixture");lookups++;check(!fail);return id}
 fun getInteger(id:Int):Int{reads++;return value}}
class Pose
class PanelSceneObject
class Leaf {fun onSceneTick(){};fun onSpatialReady(){};fun ensureEnabled(s:String,forceLog:Boolean){};fun pollNativeInput(){};fun logProbe(phase:String,probeSurface:Boolean){};fun runIfRequested(s:String){};fun update(s:String,b:Boolean){} }
class PrivateExtension {var failTick=false;fun tick(ready:Boolean){check(!failTick){"modeled private baseline failure"}}}
class Policy {val cameraPanelRoutesEnabled=false}
object EmbeddedDuplexDiagnosticActivityGate {fun paused(a:Any){}}
open class Base {
 var beforeSuper:(()->Unit)?=null
 open fun onSceneTick(){};open fun onVRReady(){beforeSuper?.invoke()};open fun onVRPause(){beforeSuper?.invoke()};open fun onPause(){beforeSuper?.invoke()}
}
internal class ActualResources(emitMarker:(String)->Unit={},val onControlObservationChanged:((SpatialBackgroundControlObservation)->Unit)?=null) {
 val sceneEpoch=7L;var videoEntityEpoch=11L;var backingEntityEpoch=12L;var sceneRetired=false
 var directDecoderGeneration=0L;var surface:Surface?=null;var backgroundMode=SpatialBackgroundMode.Black
''' + marker_binding + coordinator_fields + coordinator_accessor + '''
 fun adopt(){entity=Entity(21);blackBackdropEntity=Entity(22);surface=Surface();directDecoderGeneration=4L;emitMarker("surface-ready")}
 fun poisonEntityGetters(){entity!!.poison=true;blackBackdropEntity!!.poison=true}
 fun sameMarker(original:(String)->Unit)=emitMarker===original
 fun adoptWithPoisonedGetters(){entity=Entity(31).also{it.poison=true};blackBackdropEntity=Entity(32).also{it.poison=true};emitMarker("unobserved")}
 fun asyncMarker(){emitMarker("worker")}
 fun publishMode(mode:SpatialBackgroundMode){synchronized(this){backgroundMode=mode;emitMarker("mode")}}
 fun release(){entity=null;blackBackdropEntity=null;surface=null;sceneRetired=true;emitMarker("retired")}
}
internal class SpatialImmersiveVideoPanelCoordinator(
 context:Any, resolution:SpatialImmersiveVideoRouteResolution, emitMarker:(String)->Unit,
 recenterAfterNewVideoLoad:(String,String)->Boolean,layerOwner:SpatialImmersiveVideoLayerOwner,
 initialPresentationMode:SpatialImmersiveVideoPresentationMode,initialBackgroundMode:SpatialBackgroundMode,
 directVideoConsumerInitiallyRequired:Boolean,diagnosticTextureRoute:Int,
 onControlObservationChanged:((SpatialBackgroundControlObservation)->Unit)?=null,
) {
 companion object{var constructions=0;var failConstruction=false}
 val resources=ActualResources(emitMarker,onControlObservationChanged)
 var failRoute=false;var calls=ArrayList<String>()
 init{constructions++;check(!failConstruction){"modeled construction failure"}}
 fun backgroundControlObservation()=resources.backgroundControlObservation()
 fun publishBackgroundControlObservation()=resources.publishBackgroundControlObservation()
 fun setDirectVideoConsumerRequired(enabled:Boolean,s:String){calls+="consumer:$enabled";check(!failRoute){"modeled route failure"}}
 fun setBackgroundMode(mode:SpatialBackgroundMode,s:String):SpatialImmersiveVideoSessionSnapshot{calls+="mode:${mode.token}";resources.backgroundMode=mode;return SpatialImmersiveVideoSessionSnapshot()}
 fun updateFromViewer(p:Pose){};fun resume(s:String){};fun pause(s:String){}
}
class Scene {fun getViewerPose()=Pose()}
internal class Activity:Base(){
 val resources=Resources();val packageName="fixture"
 val immersiveVideoRouteResolution=SpatialImmersiveVideoRouteResolution()
 fun marker(s:String){};fun recenterImmersiveVideo(a:String,b:String)=true;fun immersiveVideoDiagnosticTextureRoute()=0
''' + activity_fields + '''
 var spatialSceneReady=false;var locked=false;var effects=ArrayList<String>();var failEffects=false
 val privatePanelExtension=PrivateExtension();val productPolicy=Policy();val scene=Scene()
 val controllerInputRouteCoordinator=Leaf();val controllerPollingCoordinator=Leaf();val nativeInteropCoordinator=Leaf()
 val cameraHwbProjectionPlacementUpdateCoordinator=Leaf();val spatialFragmentProbeCoordinator=Leaf();val spatialStimulusVolumeCoordinator=Leaf()
 val externalSwapchainProbeCoordinator=Leaf();val sdkQuadSurfaceProbeCoordinator=Leaf();val sdkQuadVulkanProbeCoordinator=Leaf();val sdkQuadStereoAlphaProbeCoordinator=Leaf()
 val panelSurfaceMatrixProbeCoordinator=Leaf();val spatialVideoProjectionProbeCoordinator=Leaf();val cameraHwbProjectionLaunchCoordinator=Leaf();val cameraHwbProbeCoordinator=Leaf()
 val immersiveVideoPanelCoordinator get()=backgroundImmersiveVideoPanelCoordinator
 var ownResumeActive=false;var ownResumeGeneration=0L
 fun appControllerInputsEnabled()=false;fun privatePanelInputLocked()=locked;fun directImmersiveVideoPanelRequested()=false
 fun enforcePrivatePanelInputPolicy(){};fun updateLayerControlPanelPoseFromViewer(reason:String,forceLog:Boolean){};fun updateParticleLayerProjectionFromViewer(reason:String,forceLog:Boolean){}
 fun applySpatialBackgroundEffects(mode:SpatialBackgroundMode,source:String){effects+=mode.token;check(!failEffects){"modeled effect failure"}}
 fun ready(){spatialSceneReady=true;completeBackgroundControlSceneReady()}
 fun ordinary(mode:SpatialBackgroundMode)=setSpatialBackgroundMode(mode,"ordinary-ui")
 fun current()=backgroundImmersiveVideoPanelCoordinator
 fun retire(){invalidateBackgroundControl(retired=true)}
''' + activity_methods + '''
}
fun rejected(f:()->Unit){check(runCatching(f).isFailure){"operation unexpectedly admitted"}}
fun main(){
 val firstEntered=CountDownLatch(1);val releaseFirst=CountDownLatch(1);val laterAttempted=CountDownLatch(1);val laterCompleted=CountDownLatch(1)
 val firstCallback=AtomicBoolean(true);val latest=AtomicReference<SpatialBackgroundControlObservation>();val publicationFailure=AtomicReference<Throwable?>()
 val concurrent=ActualResources(onControlObservationChanged={ snapshot ->
   if(firstCallback.compareAndSet(true,false)){firstEntered.countDown();check(releaseFirst.await(5,TimeUnit.SECONDS)){"first publication was never released"}}
   latest.set(snapshot)
 })
 val earlier=Thread{try{concurrent.asyncMarker()}catch(t:Throwable){publicationFailure.set(t)}}
 val later=Thread{try{laterAttempted.countDown();concurrent.publishMode(SpatialBackgroundMode.Video);laterCompleted.countDown()}catch(t:Throwable){publicationFailure.set(t)}}
 earlier.start();check(firstEntered.await(2,TimeUnit.SECONDS));later.start();check(laterAttempted.await(2,TimeUnit.SECONDS))
 val overtook=try{laterCompleted.await(200,TimeUnit.MILLISECONDS)}finally{releaseFirst.countDown();earlier.join(2000);later.join(2000)}
 check(publicationFailure.get()==null&&!earlier.isAlive&&!later.isAlive){"publication threads failed: ${publicationFailure.get()}"}
 check(!overtook&&latest.get().mode=="world-video"){"later mode publication overtook held snapshot assignment and stale state won"}
 val original:(String)->Unit={};val defaultResources=ActualResources(original);check(defaultResources.sameMarker(original)){"disabled coordinator replaced original emitter with observer work"};defaultResources.adoptWithPoisonedGetters()
 val a=Activity();repeat(4){val s=a.backgroundControlObservation();check(!s.sceneReady&&!s.vrReady&&s.revision==0L)}
 check(SpatialImmersiveVideoPanelCoordinator.constructions==0&&a.resources.lookups==0){"observe constructed lazy media coordinator or resolved resource"}
 rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Black)}
 a.ordinary(SpatialBackgroundMode.Black)
 a.ready();a.onVRReady();check(!a.backgroundControlObservation().sceneReady){"scene-ready admitted before private baseline tick"}
 a.privatePanelExtension.failTick=true;rejected{a.onSceneTick()};check(!a.backgroundControlObservation().sceneReady)
 a.privatePanelExtension.failTick=false;a.onSceneTick();check(a.backgroundControlObservation().sceneReady&&a.backgroundControlObservation().vrReady)
 val c=a.current();val b=a.backgroundControlObservation();check(b.revision==1L)
 val selected=a.selectBackgroundControlMode(SpatialBackgroundMode.Video);check(selected.revision==2L&&selected.mode=="world-video")
 check(c.calls==listOf("consumer:false","mode:black","consumer:true","mode:world-video")&&a.effects==listOf("black","world-video")){"control bypassed ordinary background route"}
 a.selectBackgroundControlMode(SpatialBackgroundMode.Video);check(a.backgroundControlObservation().revision==3L){"same mode did not invalidate ticket"}
 a.failEffects=true;rejected{a.ordinary(SpatialBackgroundMode.Black)};check(a.backgroundControlObservation().revision==4L&&a.backgroundControlObservation().mode=="black"){"throwing entry lost revision or actual partial state"};a.failEffects=false
 c.failRoute=true;rejected{a.ordinary(SpatialBackgroundMode.Video)};check(a.backgroundControlObservation().revision==5L);c.failRoute=false
 c.resources.adopt();val adopted=a.backgroundControlObservation();check(adopted.sceneEpoch==7L&&adopted.videoEntityId==21L&&adopted.backingEntityId==22L&&adopted.videoEntityEpoch==11L&&adopted.backingEntityEpoch==12L&&adopted.surfaceIdentity!=0&&adopted.decoderGeneration==4L){"callback did not publish actual scalar identities"}
 c.resources.poisonEntityGetters();repeat(3){check(a.backgroundControlObservation().videoEntityId==21L&&c.resources.backgroundControlObservation().backingEntityId==22L)}
 BuildConfig.DEBUG=false;rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Black)};BuildConfig.DEBUG=true
 Looper.current=Any();rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Black)};Looper.current=Looper.main
 rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Passthrough)};a.locked=true;rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Black)};a.locked=false
 a.beforeSuper={check(!a.backgroundControlObservation().vrReady);rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Black)}}
 a.onPause();check(!a.backgroundControlObservation().vrReady);a.beforeSuper=null
 a.onSceneTick();rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Black)}
 a.onVRReady();check(a.backgroundControlObservation().vrGeneration==2L);a.onSceneTick();a.selectBackgroundControlMode(SpatialBackgroundMode.Black)
 a.beforeSuper={check(!a.backgroundControlObservation().vrReady)};a.onVRPause();a.beforeSuper=null;rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Video)}
 a.onVRReady();a.onSceneTick();a.retire();rejected{a.selectBackgroundControlMode(SpatialBackgroundMode.Black)};a.onVRReady();a.onSceneTick();check(a.backgroundControlObservation().sceneRetired&&!a.backgroundControlObservation().sceneReady)
 c.resources.release();val retired=a.backgroundControlObservation();check(retired.sceneRetired&&retired.videoEntityId==0L&&retired.backingEntityId==0L&&retired.surfaceIdentity==0)
 for(value in listOf(0,2,-1)){val off=Activity();off.resources.value=value;off.ordinary(SpatialBackgroundMode.Black);off.ready();off.onVRReady();repeat(3){off.onSceneTick()};off.current().resources.adoptWithPoisonedGetters();check(!off.backgroundControlObservation().sceneReady&&!off.backgroundControlObservation().vrReady&&off.backgroundControlObservation().sceneEpoch==0L);rejected{off.selectBackgroundControlMode(SpatialBackgroundMode.Video)}}
 for(missing in listOf(true,false)){val absent=Activity();if(missing)absent.resources.id=0 else absent.resources.fail=true;absent.ordinary(SpatialBackgroundMode.Black);absent.ready();absent.onVRReady();absent.onSceneTick();check(!absent.backgroundControlObservation().sceneReady&&absent.resources.reads==0);rejected{absent.selectBackgroundControlMode(SpatialBackgroundMode.Video)}}
 BuildConfig.DEBUG=false;val release=Activity();release.ordinary(SpatialBackgroundMode.Black);release.ready();release.onVRReady();release.onSceneTick();release.current().resources.adoptWithPoisonedGetters();check(release.resources.lookups==0&&release.resources.reads==0&&!release.backgroundControlObservation().sceneReady);rejected{release.selectBackgroundControlMode(SpatialBackgroundMode.Video)};BuildConfig.DEBUG=true
 val frozen=Activity();frozen.resources.value=0;frozen.ordinary(SpatialBackgroundMode.Black);frozen.resources.value=1;frozen.ready();frozen.onVRReady();frozen.onSceneTick();check(frozen.resources.lookups==1&&!frozen.backgroundControlObservation().sceneReady);rejected{frozen.selectBackgroundControlMode(SpatialBackgroundMode.Video)}
 SpatialImmersiveVideoPanelCoordinator.failConstruction=true;val f=Activity();val before=SpatialImmersiveVideoPanelCoordinator.constructions;rejected{f.ordinary(SpatialBackgroundMode.Black)}
 repeat(3){check(f.backgroundControlObservation().revision==1L)};check(SpatialImmersiveVideoPanelCoordinator.constructions==before+1){"finally or observation retried failed construction"}
 println("Actual background Activity controls passed: default/missing/zero/release inertness, integer-resource selection frozen at construction, pure observe, same handler, revision on unchanged/throwing entries, first successful tick, VR lifecycle rejection, callback scalars, failed lazy construction; modeled SDK/media leaves, no device proof.")
}
''', encoding='utf-8')
libs = sorted(a.kotlin_lib.glob('*.jar'))
stdlib = next(p for p in libs if p.name.startswith('kotlin-stdlib-'))
classes = out / 'classes'
classes.mkdir()
(out / 'extracts.json').write_text(json.dumps(extracts, indent=2))
commands = [
    ('compile', [str(a.java_home / 'bin/java.exe'), '-cp', os.pathsep.join(map(str, libs)),
                 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                 '-jvm-target', '17', '-classpath', str(stdlib), '-d', str(classes),
                 *map(str, out.glob('*.kt'))]),
    ('controls', [str(a.java_home / 'bin/java.exe'), '-cp', os.pathsep.join(map(str, [classes, stdlib])),
                  'io.github.mesmerprism.rustyquest.spatial_camera_panel.ControlsKt']),
]
for name, command in commands:
    child = subprocess.run(command, capture_output=True, timeout=60)
    (out / (name + '.stdout')).write_bytes(child.stdout)
    (out / (name + '.stderr')).write_bytes(child.stderr)
    (out / (name + '.process.json')).write_text(json.dumps(dict(argv=command, exit_code=child.returncode), indent=2))
    if child.returncode:
        sys.stdout.buffer.write(child.stdout)
        sys.stderr.buffer.write(child.stderr)
        raise SystemExit(child.returncode)
    if name == 'controls':
        sys.stdout.buffer.write(child.stdout)
