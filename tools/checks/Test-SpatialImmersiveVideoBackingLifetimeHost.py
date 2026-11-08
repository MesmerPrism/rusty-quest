"""Actual bounded backing registry and coordinator ownership methods; SDK leaves modeled."""
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
policy = base / 'SpatialImmersiveVideoBackingRegistry.kt'
assert policy.is_file(), 'Missing production backing lifetime policy/registry: diagnostic retention is not implemented'
(out / policy.name).write_bytes(policy.read_bytes())
text = (base / 'SpatialImmersiveVideoPanelCoordinator.kt').read_text(encoding='utf-8')
extracts = []
binding = text[text.index('  private val retainBackingEntities ='):text.index('  private val retainedBackdrops by lazy')].rstrip()
def method(signature):
    start = text.index(signature); brace = text.index('{', start); end = brace + 1; depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}'); end += 1
    body = text[start:end]
    if text[:start].rstrip().endswith('@Synchronized'): body = '  @Synchronized\n' + body
    extracts.append({'signature': signature, 'sha256': hashlib.sha256(body.encode()).hexdigest()})
    return body
initialization = method('  init {')
methods = '\n'.join(method(s) for s in [
    '  fun setBackgroundMode(', '  private fun spawnBlackBackdropIfReady(',
    '  private fun destroyBackdropEntity(', '  private fun hideRetainedBackdrop(',
    '  private fun hideRetainedBackdropEntry(',
    '  private fun releaseRetainedBackdrops(', '  private fun releaseRetainedBackdropEntry(',
    '  private fun destroySceneEntities(',
    '  private fun backingGeometryKey(', '  private fun retainedBackingGeometryConflict(',
    '  private fun acceptBlackBackdropPanel(', '  fun applyRefreshedCatalog(',
    '  fun retireScene(', '  fun destroy(',
    '  private fun emitResourceMarker(', '  private fun resourceMarkerFields(',
])
(out / 'Controls.kt').write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel
class Model{var deletes=0;var live=true}
class Visible(val enabled:Boolean)
class Transform(val pose:Pose)
data class Pose(val t:Int=0,val q:Int=0)
class Panel(val id:Int)
class Surface
class PanelSceneObject(val poisonSurface:Boolean=false){val handle=1L;val surface:Surface get(){check(!poisonSurface){"retired SDK Surface dereferenced"};return Surface()}}
var nextEntityId=1L
class Entity(val model:Model,val id:Long=nextEntityId++){override fun equals(other:Any?)=other is Entity&&other.id==id;override fun hashCode()=id.hashCode();var visible=true;var pose=Pose();var afterDelete:(()->Unit)?=null
 fun setComponent(c:Any){check(model.live);if(c is Visible)visible=c.enabled;if(c is Transform)pose=c.pose}
 fun destroy(){check(model.live);model.deletes++;afterDelete?.invoke()}
 companion object{lateinit var model:Model;fun create(vararg c:Any)=Entity(model).also{e->c.forEach(e::setComponent)}}}
enum class SpatialImmersiveVideoPresentationMode(val token:String){WorldAnchored("world-anchored"),HeadFixedBorder("head-fixed-border")}
enum class SpatialBackgroundMode(val token:String){Black("black"),Video("world-video"),Passthrough("passthrough")}
enum class SpatialImmersiveVideoBlackBackingShape{Quad,Equirect360}
class Pack(val packId:String);class Plain(val mediaId:String)
data class SpatialImmersiveVideoConfig(val path:String,val radiusMeters:Float=50f,val quadWidth:Float=4f,val quadHeight:Float=2f){val offlinePack:Pack?=null;val plainMedia:Plain?=null;fun markerFields()="fixture"}
class Quad(val widthMeters:Float,val heightMeters:Float)
class Presentation(val quadGeometry:Quad)
object SpatialImmersiveVideoSessionPolicy{fun directPanelPresentation(c:SpatialImmersiveVideoConfig,m:SpatialImmersiveVideoPresentationMode)=Presentation(Quad(c.quadWidth,c.quadHeight))}
object SpatialImmersiveVideoBlackBackingPolicy{fun shape(c:SpatialImmersiveVideoConfig,m:SpatialImmersiveVideoPresentationMode)=if(m==SpatialImmersiveVideoPresentationMode.WorldAnchored)SpatialImmersiveVideoBlackBackingShape.Equirect360 else SpatialImmersiveVideoBlackBackingShape.Quad;fun shapeToken(c:SpatialImmersiveVideoConfig,m:SpatialImmersiveVideoPresentationMode)=shape(c,m).name;fun radiusMeters(c:SpatialImmersiveVideoConfig)=c.radiusMeters+.025f}
class Handler{fun removeCallbacksAndMessages(a:Any?){}}
fun activityMarkerToken(s:String)=s
class Owner{val token="fixture"}
class Snapshot(val index:Int,val path:String?)
typealias SpatialImmersiveVideoSessionSnapshot=Snapshot
internal class SpatialImmersiveVideoPanelCoordinator(val model:Model,val retainBackingEntities:Boolean){
 val layerOwner=Owner();val sceneEpoch=1L;var videoEntityEpoch=0L;var backingEntityEpoch=0L;var directDecoderGeneration=0L
 var entity:Entity?=null;var blackBackdropEntity:Entity?=null;var panelSceneObject:PanelSceneObject?=null;var surface:Surface?=null
 var sceneRetired=false;var destroyed=false;var requested=true;var playbackEnabled=true;var directDecoderActive=false
 var activeIndex=0;var catalog=listOf(SpatialImmersiveVideoConfig("a"),SpatialImmersiveVideoConfig("b",radiusMeters=30f));val activeConfig get()=catalog.getOrNull(activeIndex)
 var presentationMode=SpatialImmersiveVideoPresentationMode.WorldAnchored;var backgroundMode=SpatialBackgroundMode.Black
 var spawnPose:Pose?=Pose();var latestViewerPose:Pose?=Pose();var transitionGeneration=0L;var transitionTargetIndex:Int?=null;var transitionSource="";var incomingFadeGeneration=-1L
 val transitionHandler=Handler();val MAX_SESSION_PACKS=2
 val blackBackdropRegistrationIds=mapOf(SpatialImmersiveVideoPresentationMode.WorldAnchored to listOf(10,11),SpatialImmersiveVideoPresentationMode.HeadFixedBorder to listOf(20,21))
 val retainedBackdrops=SpatialImmersiveVideoBackingRegistry<Int,Entity>(blackBackdropRegistrationIds.values.flatten().toSet())
 val markers=ArrayList<String>();fun emitMarker(s:String){markers+=s};fun sessionSnapshot()=Snapshot(activeIndex,activeConfig?.path)
 fun headFixedPose(p:Pose)=p;fun cancelSelectionTransition(s:String){};fun releasePlayer(s:String,quitThreadAfterRelease:Boolean=false){}
 fun spawnActiveEntity(s:String)=spawnBlackBackdropIfReady(s)
 fun catalogIdentity(c:SpatialImmersiveVideoConfig)=c.path
 fun spawn()=spawnBlackBackdropIfReady("fixture");fun detach()=destroyBackdropEntity()
 fun callback(i:Int,m:SpatialImmersiveVideoPresentationMode,e:Entity,p:PanelSceneObject=PanelSceneObject())=acceptBlackBackdropPanel(i,m,e,p)
 private fun SpatialBackgroundMode.usesOpaqueVideoBacking()=this!=SpatialBackgroundMode.Passthrough
''' + methods + '''
}
object BuildConfig{var DEBUG=true}
var propertyRaw="";var propertyReads=0
fun activityReadSystemProperty(name:String):String{check(name==IMMERSIVE_VIDEO_RETAIN_BACKINGS_PROPERTY);propertyReads++;return propertyRaw}
class ActualPropertyBinding{
 val markers=ArrayList<String>();val MAX_SESSION_PACKS=2;fun emitResourceMarker(s:String){markers+=s}
''' + binding + '''
''' + initialization + '''
 fun selected()=retainBackingEntities
}
fun main(){
 BuildConfig.DEBUG=true;propertyRaw="1";val bound=ActualPropertyBinding();propertyRaw="0";check(bound.selected()&&bound.markers.single().contains("backing-lifetime-policy-selected")){"construction-time selection changed after property write"};val offBinding=ActualPropertyBinding();check(!offBinding.selected()&&offBinding.markers.isEmpty())
 BuildConfig.DEBUG=false;propertyRaw="1";val priorReads=propertyReads;val releaseBinding=ActualPropertyBinding();check(!releaseBinding.selected()&&releaseBinding.markers.isEmpty()&&propertyReads==priorReads){"actual release binding read/adopted property"}

 var reads=0
 for(v in listOf("","0","true","01"," 1","1 ","2"))check(!SpatialImmersiveVideoBackingRetentionPolicy.selected(true){reads++;v}){"non-exact opt-in admitted: $v"}
 check(SpatialImmersiveVideoBackingRetentionPolicy.selected(true){"1"})
 check(!SpatialImmersiveVideoBackingRetentionPolicy.selected(true){error("unavailable")})
 val before=reads;check(!SpatialImmersiveVideoBackingRetentionPolicy.selected(false){reads++;"1"}&&reads==before){"release read/adopted debug property"}
 val model=Model();Entity.model=model;val on=SpatialImmersiveVideoPanelCoordinator(model,true);on.spawn();val first=on.blackBackdropEntity!!;val epoch=on.backingEntityEpoch
 on.setBackgroundMode(SpatialBackgroundMode.Passthrough,"off");check(!first.visible&&on.blackBackdropEntity==null&&model.deletes==0)
 on.callback(0,on.presentationMode,Entity(model,first.id));check(on.markers.last().contains("black-backing-panel-ready")){"hidden retained callback was rejected"}
 on.spawnPose=Pose(9,2);on.setBackgroundMode(SpatialBackgroundMode.Video,"on");check(on.blackBackdropEntity===first&&first.visible&&first.pose.t==9&&on.backingEntityEpoch==epoch&&on.retainedBackdrops.size==1){"return recreated entity/epoch or lost pose"}
 on.spawn();check(on.blackBackdropEntity===first&&on.retainedBackdrops.size==1)
 on.activeIndex=1;on.spawn();val second=on.blackBackdropEntity!!;check(second!==first&&!first.visible&&model.deletes==0&&on.retainedBackdrops.size==2)
 on.activeIndex=0;on.spawn();check(on.blackBackdropEntity===first&&on.backingEntityEpoch==epoch&&!second.visible)
 on.presentationMode=SpatialImmersiveVideoPresentationMode.HeadFixedBorder;on.spawn();val third=on.blackBackdropEntity!!;check(third!==first&&!first.visible&&on.retainedBackdrops.size==3)
 on.presentationMode=SpatialImmersiveVideoPresentationMode.WorldAnchored;on.spawn();check(on.blackBackdropEntity===first&&!third.visible&&on.backingEntityEpoch==epoch)
 val old=on.catalog;on.applyRefreshedCatalog(listOf(old[0].copy(quadWidth=5f),old[1]),"hidden-quad-changed");check(on.catalog===old&&on.markers.last().contains("catalog-refresh-retention-rejected")){"hidden retained quad geometry drift was accepted"}
 on.applyRefreshedCatalog(listOf(old[0].copy(radiusMeters=51f),old[1]),"changed");check(on.catalog===old&&on.activeIndex==0&&on.markers.last().contains("catalog-refresh-retention-rejected")){"changed geometry silently reused retained SDK shape"}
 on.applyRefreshedCatalog(listOf(old[0].copy(path="replacement"),old[1]),"same-geometry");check(on.catalog[0].path=="replacement"){"unchanged geometry refresh was unnecessarily rejected"}
 val markerCount=on.markers.size;on.callback(0,on.presentationMode,Entity(model));check(on.markers.last().contains("black-backing-callback-rejected")&&on.markers.size==markerCount+1)
 first.afterDelete={check(on.retainedBackdrops.size==0);on.destroy("reentrant")};on.retireScene("sdk");check(model.deletes==3&&on.retainedBackdrops.size==0);on.destroy("repeat");on.retireScene("repeat");check(model.deletes==3)
 val count=on.markers.size;on.callback(0,on.presentationMode,first,PanelSceneObject(true));check(on.markers.last().contains("black-backing-callback-rejected"));check(model.deletes==3)
 val offModel=Model();Entity.model=offModel;val off=SpatialImmersiveVideoPanelCoordinator(offModel,false);off.spawn();val oldOff=off.blackBackdropEntity!!;off.setBackgroundMode(SpatialBackgroundMode.Passthrough,"off");check(offModel.deletes==1)
 off.setBackgroundMode(SpatialBackgroundMode.Video,"on");check(off.blackBackdropEntity!==oldOff&&off.backingEntityEpoch==2L)
 val prior=off.catalog;off.applyRefreshedCatalog(listOf(prior[0].copy(radiusMeters=51f),prior[1]),"changed");check(off.catalog[0].radiusMeters==51f){"default refresh behavior changed"};off.destroy("done");check(off.markers.none{it.contains("retained-")||it.contains("backingRetentionDiagnostic=")}){"off path emitted retention-specific markers"}
 val r=SpatialImmersiveVideoBackingRegistry<Int,String>(setOf(1,2));var made=0;var hidden=0
 r.acquire(1,"one",{hidden++},{error("unexpected aborted creation")}){made++;"a"};check(runCatching{r.acquire(3,"three",{hidden++},{error("unexpected aborted creation")}){made++;"bad"}}.isFailure&&made==1&&hidden==0){"unregistered slot created or hid a backing"}
 r.acquire(2,"two",{hidden++},{error("unexpected aborted creation")}){made++;"b"};check(r.size==2&&hidden==1)
 var released=0;check(runCatching{r.retire{check(r.size==0);released++;if(released==1)error("modeled release failure")}}.isFailure&&released==2){"release failure skipped retained entity"};r.retire{released++};check(released==2)
 check(runCatching{r.acquire(1,"one",{},{error("unexpected aborted creation")}){"late"}}.isFailure){"retired registry reacquired SDK entity"}
 val createReentry=SpatialImmersiveVideoBackingRegistry<Int,String>(setOf(1));var createReentryReleased=0
 val createReentryResult=runCatching{createReentry.acquire(1,"fixed",{},{createReentryReleased++}){createReentry.retire{createReentryReleased++};"created"}}
 check(createReentryResult.isFailure&&createReentry.size==0&&createReentryReleased==1){"retirement during create published/leaked a newly created value"}
 val failedAbort=SpatialImmersiveVideoBackingRegistry<Int,String>(setOf(1));var abortedAttempts=0
 check(runCatching{failedAbort.acquire(1,"fixed",{},{abortedAttempts++;error("modeled aborted release failure")}){failedAbort.retire{};"created"}}.isFailure)
 failedAbort.retire{abortedAttempts++};check(failedAbort.size==0&&abortedAttempts==1){"aborted release failure published/released twice"}
 println("Actual retained backing policy/registry/coordinator controls passed: strict debug opt-in, default destroy/recreate, stable hide/reuse epochs and callbacks, fixed keys, geometry refresh rejection, reentrant/exactly-once release; modeled SDK leaves, no device proof.")
}
''')
libs = sorted(a.kotlin_lib.glob('*.jar')); stdlib = next(p for p in libs if p.name.startswith('kotlin-stdlib-'))
classes = out / 'classes';classes.mkdir()
(out/'extracts.json').write_text(json.dumps({'methods':extracts,'binding_sha256':hashlib.sha256(binding.encode()).hexdigest(),'registry_sha256':hashlib.sha256(policy.read_bytes()).hexdigest()},indent=2))
commands=[('compile',[str(a.java_home/'bin/java.exe'),'-cp',os.pathsep.join(map(str,libs)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',str(stdlib),'-d',str(classes),*map(str,out.glob('*.kt'))]),('controls',[str(a.java_home/'bin/java.exe'),'-cp',os.pathsep.join(map(str,[classes,stdlib])),'io.github.mesmerprism.rustyquest.spatial_camera_panel.ControlsKt'])]
for name, command in commands:
    child=subprocess.run(command,capture_output=True,timeout=60)
    (out/(name+'.stdout')).write_bytes(child.stdout);(out/(name+'.stderr')).write_bytes(child.stderr)
    (out/(name+'.process.json')).write_text(json.dumps(dict(argv=command,exit_code=child.returncode),indent=2))
    if child.returncode:
        sys.stdout.buffer.write(child.stdout);sys.stderr.buffer.write(child.stderr);raise SystemExit(child.returncode)
    if name=='controls':sys.stdout.buffer.write(child.stdout)
