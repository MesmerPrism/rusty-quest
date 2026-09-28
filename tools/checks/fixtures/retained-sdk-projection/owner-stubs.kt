package io.github.mesmerprism.rustyquest.spatial_camera_panel
import com.meta.spatial.core.*
import com.meta.spatial.runtime.*
fun Vector3.activityNormalizedOr(fallback:Vector3)=this
const val CAMERA_HWB_PROJECTION_MARKER_INTERVAL_MS=1L
enum class CameraHwbProjectionPlacementMode{ViewerLocked}
class CameraHwbProjectionPlane(val pose:Pose){val projectionWidthMeters=1f;val projectionHeightMeters=1f;val placementMode=CameraHwbProjectionPlacementMode.ViewerLocked}
object SpatialPublicMultiStack{fun markerFields()="fixture"}
object CameraHwbProjectionModule{
 fun rawProjectionPlaneUpdatedMarker(reason:String,plane:CameraHwbProjectionPlane,projectionMarkerFields:String,stereoMarkerFields:String,videoProjectionMarkerFields:String,publicMultiStackMarkerFields:String,layerUpdateStatus:String,panelCarrierUpdateStatus:String,nativePanelPoseUpdateMask:Long)="fixture"
 fun rawProjectionLayerUpdateFailedMarker(reason:String,plane:CameraHwbProjectionPlane,error:String,message:String)="fixture"
 fun nativePanelPoseUpdateSkippedMarker(reason:String,error:String)="fixture"
 fun nativePanelPoseUpdateFailedMarker(reason:String,plane:CameraHwbProjectionPlane,error:String,message:String)="fixture"
}
object SpatialDiagnosticProbeRouteModule{
 fun sdkQuadSurfaceProbeSceneAnchorDestroyedMarker(reason:String,layerDestroyed:Boolean,sceneObjectDestroyed:Boolean,anchorMeshDestroyed:Boolean,anchorMaterialDestroyed:Boolean,cleanupStatus:String)="fixture"
 fun sdkQuadSurfaceProbeDestroyedMarker(reason:String,sceneCleanupStatus:String,swapchainDestroyed:Boolean,cleanupStatus:String)="fixture"
}
