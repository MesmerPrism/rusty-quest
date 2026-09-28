package io.github.mesmerprism.rustyquest.spatial_camera_panel
import java.lang.reflect.Proxy
internal class RetirementCarrier {
 fun sourceCarrierContext()=SpatialVideoSourceCarrierContext(10,20)
 fun selectNativeProvider(q:SpatialVideoSourceNativeRequest,c:SpatialVideoSourceCarrierContext):SpatialVideoSourceNativeReadback=checkNotNull(SpatialVideoSourceNativeAbi.decodeReadback(OwnPackedPoolNative.fixtureRequestSource(SpatialVideoSourceNativeAbi.encodeRequest(q))))
 fun readNativeSource(route:Long)=SpatialVideoSourceNativeAbi.decodeReadback(OwnPackedPoolNative.fixtureReadSource(route))
}
internal class ActualActivityLocalStop {
 var localSourceRetirementGeneration=0L
 val cameraHwbProjectionRawCarrierCoordinator=RetirementCarrier()
 fun stop(routeGeneration:Long):Boolean {return run {ACTUAL_ACTIVITY_LOCAL_STOP}}
}
internal class DefaultLocalRetirementFixture {
 companion object {
 @JvmStatic fun reproduce():SpatialVideoSourceRoutingCoordinator {
  val stop=ActualActivityLocalStop();val carrier=stop.cameraHwbProjectionRawCarrierCoordinator;var now=1L
  val execution=Proxy.newProxyInstance(DefaultLocalRetirementFixture::class.java.classLoader,arrayOf(SpatialVideoSourceExecutionAdapter::class.java)){_,method,args->when(method.name){
   "updateSourceOwnerDemand"->null
   "producerState"->SpatialProjectionProducerState.NoProducerOwned
   "selectNativeProvider"->carrier.selectNativeProvider(args[0] as SpatialVideoSourceNativeRequest,args[1] as SpatialVideoSourceCarrierContext)
   "readNativeSource"->carrier.readNativeSource(args[0] as Long)
   "stopSourceAcquisition"->{check(args[0]==SpatialVideoSource.Local);stop.stop(args[1] as Long)}
   else->error("unexpected owner operation ${method.name}")
  }} as SpatialVideoSourceExecutionAdapter
  val routing=SpatialVideoSourceRoutingCoordinator(execution){now};routing.beginProjectionSourceRequest(SpatialVideoSource.Local,null);routing.executeRequest(1,carrier.sourceCarrierContext(),"raw-carrier-ready-resume"); val priorRequest=routing.concurrentPeerAdmissionRequest();val priorProof=SpatialConcurrentPeerAdmission.observed(priorRequest,carrier.sourceCarrierContext(),OwnPackedPoolNative.concurrentPeerAdmission(priorRequest.nativeGeneration,10,20));now=2_000_000_002L
  val failed=routing.pollActive(2_000_000_000L);check(failed.generation==1L && failed.failed==SpatialVideoSource.Local && failed.failureReason==SpatialVideoSourceReason.AcquisitionStopFailed)
  try{routing.beginEmbeddedConcurrentProjectionPeerRequest(priorProof);error("stale native counter accepted")}catch(e:IllegalStateException){check(e.message=="concurrent Peer reservation superseded")};check(routing.snapshot().generation==1L)
  val currentRequest=routing.concurrentPeerAdmissionRequest();val wrongWords=OwnPackedPoolNative.concurrentPeerAdmission(currentRequest.nativeGeneration,10,20).clone();wrongWords[0]=currentRequest.routingGeneration;try{SpatialConcurrentPeerAdmission.observed(currentRequest,carrier.sourceCarrierContext(),wrongWords);error("intent substituted for native generation")}catch(e:IllegalStateException){check(e.message=="concurrent Own native admission unavailable")}
  val old=checkNotNull(carrier.readNativeSource(1));val actual=checkNotNull(carrier.readNativeSource(2));check(old.result==SpatialVideoSourceResult.Unavailable && old.reason==SpatialVideoSourceReason.ReceiptForeign && old.launchChallenge==0L && old.surfaceGeneration==0L);check(actual.source==SpatialVideoSource.Disabled && actual.launchChallenge==10L && actual.surfaceGeneration==20L)
  println("PASS actual default Local1 -> stale poll -> extracted Activity Disabled retirement2; native source1 is Foreign/zero carrier while source2 retains exact carrier")
  return routing
 }
 }
}
