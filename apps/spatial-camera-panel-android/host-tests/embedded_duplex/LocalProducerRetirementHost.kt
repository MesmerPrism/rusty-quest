package io.github.mesmerprism.rustyquest.spatial_camera_panel
import android.content.Intent
import android.os.Handler
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexActivityAttachment
data class Cadence(val nativeFallbackFps:Int=30)
data class SpatialVideoProjectionSettings(val fpsCap:Int=30,val cadenceMode:Cadence=Cadence(),val active:Boolean=true,val source:String="peer-packed-stereo")
object OwnPackedPoolNative {var selected=true;var retirementAllowed=true;fun captureRouteSelected()=selected;fun nativeRetirePeerSource(r:Long,d:Long,i:Long):Boolean{check(r>0&&d==42L&&i==43L);return retirementAllowed}}
class Carrier {var refreshes=0;fun refreshCarrierMode(){refreshes++}}
internal class Raw {fun sourceCarrierContext():SpatialVideoSourceCarrierContext?=null}
class Video {fun resolveSettings(i:Intent?)=SpatialVideoProjectionSettings()}
class Presentation {fun videoSettings(s:SpatialVideoProjectionSettings)=s}
class Dispatcher {val tasks=mutableListOf<()->Unit>();fun dispatch(a:()->Unit):Boolean{tasks.add(a);return true};fun drain(){while(tasks.isNotEmpty())tasks.removeAt(0)()}}
object CameraHwbProjectionModule {fun rawProjectionStartDeferredForSceneMarker(r:String)="deferred-scene";fun rawProjectionStartDeferredForVirtualRoomMarker(r:String,s:Boolean)="deferred-room";fun rawProjectionStartMarker(reason:String,startGateToken:String,readerMaxImages:Int,carrier:String,projectionMarkerFields:String,stereoMarkerFields:String,videoProjectionMarkerFields:String,publicMultiStackMarkerFields:String)="launch"}
object SpatialPublicMultiStack{fun markerFields()="model"}
fun activityMarkerToken(s:String)=s

internal class NativeLeaf:SpatialVideoSourceExecutionAdapter {
 lateinit var owner:SpatialVideoSourceRoutingCoordinator;var localSelections=0
 override fun producerState()=owner.snapshot().producerState
 override fun requestProducerCleanup(g:Long,p:SpatialProjectionProducerState.ProducerActive):SpatialProjectionProducerState=SpatialProjectionProducerState.CleanupPending(p.session,p.epoch,g)
 override fun readProducerCleanup(p:SpatialProjectionProducerState.CleanupPending):SpatialProjectionProducerState?=null
 override fun selectNativeProvider(r:SpatialVideoSourceNativeRequest,c:SpatialVideoSourceCarrierContext):SpatialVideoSourceNativeReadback{
  if(r.source==SpatialVideoSource.Local)localSelections++
  return SpatialVideoSourceNativeReadback(r.routeGeneration,r.source,0,17,c.launchChallenge,c.surfaceGeneration,0,18,if(r.source==SpatialVideoSource.Disabled)1000 else 0,if(r.source==SpatialVideoSource.Disabled)SpatialVideoSourceStage.AcquisitionStopped else SpatialVideoSourceStage.ProviderSelected,if(r.source==SpatialVideoSource.Disabled)SpatialVideoSourceResult.Inactive else SpatialVideoSourceResult.Pending,SpatialVideoSourceReason.None,r.cameraStartAllowed,0,0)
 }
 override fun readNativeSource(g:Long):SpatialVideoSourceNativeReadback?=null
 override fun stagePeerSettings(s:SpatialVideoProjectionSettings,g:Long)=true
 override fun replacePeerSettings(s:SpatialVideoProjectionSettings,g:Long,r:String)=true
 override fun preparePeerDecoderOwnership(g:Long,r:String)=true
 override fun stopSourceAcquisition(s:SpatialVideoSource,g:Long,r:String)=true
 override fun updateSourceOwnerDemand(b:Boolean,r:String){}
}

internal class Host {
 val localRollbackRequestFence=LocalRollbackRequestFence();val execution=NativeLeaf();val spatialVideoSourceRoutingCoordinator=SpatialVideoSourceRoutingCoordinator(execution){1000L}.also{execution.owner=it};var embeddedDuplexActivityDestroying=false;var localRollbackConfigSha256:String?=null
 val cameraHwbProjectionCarrierStateCoordinator=Carrier();val cameraHwbProjectionRawCarrierCoordinator=Raw();val videoLifecycleShutdownCoordinator=Dispatcher();val spatialVideoProjectionRuntimeCoordinator=Video();var videoCadenceModeOverride:Cadence?=null;val presentationPolicy=Presentation()
 var enabled=true;var sceneReady=true;var roomEnabled=false;var roomLoaded=true;var launches=0;val markers=mutableListOf<String>()
 val cameraHwbProjectionLaunchCoordinator=SpatialCameraHwbProjectionLaunchCoordinator(SpatialCameraHwbProjectionLaunchBindings(state={SpatialCameraHwbProjectionLaunchState(enabled,sceneReady,roomEnabled,roomLoaded)},prepareRequest={currentCameraHwbProjectionLaunchRequest(currentProjectionVideoSettings())},startGateToken={"model"},carrierToken={"model"},projectionMarkerFields={""},stereoMarkerFields={""},videoProjectionMarkerFields={""},launch={launches++},marker={markers.add(it)}))
 fun runOnUiThread(a:()->Unit)=a();fun marker(s:String){markers.add(s)};fun recordProjectionSourceRouteTransition(s:SpatialVideoSourceRoutingState,r:String){};fun currentProjectionVideoSettings()=SpatialVideoProjectionSettings();fun currentCameraHwbProjectionLaunchRequest(s:SpatialVideoProjectionSettings)=SpatialCameraHwbProjectionLaunchRequest(5,s)
 fun initial(){cameraHwbProjectionLaunchCoordinator.runIfRequested("initial");Handler.drain();check(launches==1)}
 fun local()=requestProjectionSource(SpatialVideoSource.Local,null,"activity-new-intent")
  /* ACTUAL_ACTIVITY_REQUEST_METHOD */

}

internal class RetirementDisplay(val routing:SpatialVideoSourceRoutingCoordinator,var ownedPeerGeneration:Long) {
 var ownedDecoderToken=42L;var ownedReaderGeneration=43L;var retirementGeneration=0L
 var dormantPeerRoute:Any?=null;var preparedRendererId:String?=null
 fun concurrentOwn()=true
 fun <T> serialized(action:()->T):T=action()
 fun abortDormantPeerRenderer(id:String)="modeled-terminal"
 fun carrier()=SpatialVideoSourceCarrierContext(31,7)
 fun awaitSource(g:Long,s:SpatialVideoSource){}
 /* ACTUAL_DISPLAY_RETIRE_METHOD */
}

/** Real source coordinators and frontdoor; JNI, SDK resources and Android queues are modeled. */
internal object LocalProducerRetirementHost {
 private val carrier=SpatialVideoSourceCarrierContext(31,7)
 private fun fixture(session:Long=0,epoch:Long=0):Host {
  OwnPackedPoolNative.selected=true;OwnPackedPoolNative.retirementAllowed=true
  val h=Host();h.initial();val c=h.spatialVideoSourceRoutingCoordinator
  c.beginProjectionSourceRequest(SpatialVideoSource.Local,null)
  c.requestProjectionSource(SpatialVideoSource.Disabled,null,carrier,"owned-handoff")
  peer(c,session,epoch);return h
 }
 private fun peer(c:SpatialVideoSourceRoutingCoordinator,session:Long=0,epoch:Long=0,effective:Boolean=true):Long {
  val request=c.concurrentPeerAdmissionRequest()
  val proof=SpatialConcurrentPeerAdmission.observed(request,carrier,longArrayOf(request.nativeGeneration,31,7,17,2))
  val p=c.beginEmbeddedConcurrentProjectionPeerRequest(proof)
  if(effective){
   c.executeRequest(p.generation,carrier,"embedded-sink")
   c.reportNativeReadback(SpatialVideoSourceNativeReadback(p.generation,SpatialVideoSource.Peer,42,43,31,7,13,14,900,SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Peer),SpatialVideoSourceResult.Effective,SpatialVideoSourceReason.None,false,session,epoch))
  }
  return p.generation
 }
 private fun retire(h:Host,g:Long=h.spatialVideoSourceRoutingCoordinator.snapshot().generation)=RetirementDisplay(h.spatialVideoSourceRoutingCoordinator,g).retirePeerProjection()
 private fun denied(h:Host){
  val s=h.spatialVideoSourceRoutingCoordinator.requestProjectionSource(SpatialVideoSource.Local,null,carrier,"unqualified-local")
  check(s.failed==SpatialVideoSource.Local&&s.failureReason==SpatialVideoSourceReason.ProducerCleanupDenied)
  check(h.execution.localSelections==0)
 }
 @JvmStatic fun main(args:Array<String>){
  when(args.single()){
   "qualified"->{
    val h=fixture();val c=h.spatialVideoSourceRoutingCoordinator;retire(h)
    h.local();io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexActivityAttachment.finish();Handler.drain();h.videoLifecycleShutdownCoordinator.drain()
    val s=c.resumePending(SpatialVideoSourceCarrierContext(44,8),"raw-carrier-ready")
    val acquisitionSource=s.requested.takeIf{s.failed==null}?:SpatialVideoSource.Disabled
    println("Local generation=${s.generation} reason=${s.failureReason} producer=${s.producerState} providerCalls=${h.execution.localSelections} acquisitionSource=$acquisitionSource launchPosts=${h.launches}")
    check(s.generation==4L&&s.failed==null&&h.execution.localSelections==1&&acquisitionSource==SpatialVideoSource.Local&&h.launches==2){"retired embedded producer cache blocks qualified Local before Native provider admission"}
   }
   "live-unknown"->{val h=fixture();denied(h)}
   "generic-unknown"->{
    val h=Host();val c=h.spatialVideoSourceRoutingCoordinator
    val p=c.requestProjectionSource(SpatialVideoSource.Peer,SpatialVideoProjectionSettings(),carrier,"generic-peer")
    c.reportNativeReadback(SpatialVideoSourceNativeReadback(p.generation,SpatialVideoSource.Peer,42,43,31,7,13,14,900,SpatialVideoSourceStage.requiredFor(SpatialVideoSource.Peer),SpatialVideoSourceResult.Effective,SpatialVideoSourceReason.None,false,0,0))
    val before=c.snapshot();check(runCatching{c.retireEmbeddedConcurrentProjectionPeerRequest(p.generation)}.isFailure);check(c.snapshot()==before);denied(h)
   }
   "native-denied"->{val h=fixture();val before=h.spatialVideoSourceRoutingCoordinator.snapshot();OwnPackedPoolNative.retirementAllowed=false;check(runCatching{retire(h)}.isFailure);check(h.spatialVideoSourceRoutingCoordinator.snapshot()==before);denied(h)}
   "stale-retirement"->{val h=fixture();val c=h.spatialVideoSourceRoutingCoordinator;val old=c.snapshot().generation;c.beginProjectionSourceRequest(SpatialVideoSource.Local,null);val before=c.snapshot();check(runCatching{retire(h,old)}.isFailure);check(c.snapshot()==before);denied(h)}
   "foreign-unknown"->{
    val h=fixture();val c=h.spatialVideoSourceRoutingCoordinator
    c.requestProjectionSource(SpatialVideoSource.Disabled,null,carrier,"superseding-stop")
    val foreign=c.snapshot().producerState;peer(c,effective=false);retire(h);check(c.snapshot().producerState==foreign);denied(h)
   }
   "active-producer"->{
    val h=fixture(81,4);val c=h.spatialVideoSourceRoutingCoordinator;val active=c.snapshot().producerState;retire(h);check(c.snapshot().producerState==active)
    val s=c.requestProjectionSource(SpatialVideoSource.Local,null,carrier,"active-producer-local");check(s.pending==SpatialVideoSource.Local&&s.failureReason==SpatialVideoSourceReason.ProducerCleanupRequired&&s.producerState is SpatialProjectionProducerState.CleanupPending);check(h.execution.localSelections==0)
   }
   "cleanup-pending"->{
    val h=fixture(81,4);val c=h.spatialVideoSourceRoutingCoordinator;c.requestProjectionSource(SpatialVideoSource.Local,null,carrier,"producer-cleanup");val pending=c.snapshot().producerState
    check(pending is SpatialProjectionProducerState.CleanupPending);peer(c,effective=false);retire(h);check(c.snapshot().producerState==pending)
    val s=c.requestProjectionSource(SpatialVideoSource.Local,null,carrier,"pending-producer-local");check(s.pending==SpatialVideoSource.Local&&s.failureReason==SpatialVideoSourceReason.ProducerCleanupRequired);check(h.execution.localSelections==0)
   }
   else->error("fixed case required")
  }
  println("PASS ${args.single()}; camera/pixels not claimed")
 }
}
