package io.github.mesmerprism.rustyquest.spatial_camera_panel
import com.meta.spatial.core.Entity
import java.util.concurrent.CompletableFuture
class RawCarrierFixture{val context=Any();var removed=0;var starts=0;fun run(reader:Int,video:SpatialVideoProjectionSettings){starts++};fun sourceCarrierContext():Any?=if(removed==0)context else null;fun recordLayerRemoved(reason:String){removed++}}
internal class CleanupActivityFixture(val sdkQuadResourceCoordinator:SpatialSdkQuadResourceCoordinator,var cameraHwbProjectionEntity:Entity?) {
 val cameraHwbProjectionRawCarrierCoordinator=RawCarrierFixture()
 val queue=java.util.concurrent.LinkedBlockingQueue<()->Unit>()
 fun runOnUiThread(action:()->Unit){queue.add(action)}
 fun cleanup(reason:String)=cleanupSdkProjectionResourcesOnUiThread(reason)
 val scene=com.meta.spatial.runtime.Scene()
 val privateLayerControlCoordinator=PrivateControlFixture()
 val cameraLatencyDiagnosticModule=LatencyFixture()
 val nativeInteropCoordinator=InteropFixture()
 val cameraHwbProjectionPanelCarrierCoordinator=CarrierFixture()
 val cameraHwbProjectionRawCarrierCoordinatorForLaunch=CarrierFixture()
 val spatialVideoProjectionRuntimeCoordinator=VideoFixture()
 val immersiveVideoPanelCoordinator=VideoPanelFixture()
 val cameraHwbProjectionCarrierStateCoordinator=CarrierStateFixture()
 val cameraHwbProjectionTuningCoordinator=TuningFixture()
 val cameraHwbProjectionPlacementUpdateCoordinator=PlacementFixture()
 val panelPlacementStateCoordinator=PanelFixture()
 fun cleanupSdkQuadSurfaceProbe(reason:String)=cleanup(reason)
 fun nativeConfigureCameraLatencyOpenXrHandles(a:Long,b:Long,c:Long)=0L
 fun marker(value:String){}
 fun initialPrivateLayerDepthLayerPolicy()=0
 fun initialPrivateLayerGuideProcessing()=0
 fun suppressParticleLayerForCameraStack(reason:String){}
 fun setPrivateLayerPanelVisible(a:Boolean,focus:Boolean,source:String){}
 fun pollPendingControlProfileAfterProjectionStart(){}
 fun newLaunch()=runCameraHwbProjectionProbe(1,SpatialVideoProjectionSettings())
 // The runner appends the exact selected Activity UI cleanup method here.
