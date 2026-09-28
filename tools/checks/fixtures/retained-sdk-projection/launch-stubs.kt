package io.github.mesmerprism.rustyquest.spatial_camera_panel
class SpatialVideoProjectionSettings(val source:String="local")
object SpatialImmersiveVideoSessionPolicy{const val CUSTOM_PROJECTION_SOURCE="custom"}
object SpatialNativeInteropProbe{class Probe{val openXrInstanceHandle=0L;val openXrSessionHandle=0L;val openXrGetInstanceProcAddrHandle=0L};fun capture(scene:com.meta.spatial.runtime.Scene)=Probe()}
class PrivateControlFixture{fun clearNativeLayerOverrideLifecycle(){};fun initializeDepthLayerPolicy(v:Int){};fun initializeGuideProcessing(v:Int){}}
class LatencyFixture{fun poll(reason:String,force:Boolean){};fun resetPoseCapture(reason:String){}}
class InteropFixture{val receiptLibraryLoaded=false}
class CarrierFixture{fun cleanup(reason:String)="destroyed";fun run(reader:Int,video:SpatialVideoProjectionSettings){}}
class VideoFixture{var adopted=0;fun adoptSettings(video:SpatialVideoProjectionSettings,pack:Any?){adopted++}}
class VideoPanelFixture{val activeOfflinePack:Any?=null}
class CarrierStateFixture{fun resetForLaunch(){};fun scenePanelCarrierEnabled()=false}
class TuningFixture{fun resetForLaunch(){}}
class PlacementFixture{fun resetMarkerCadence(){}}
class PanelFixture{fun setPrivateLayerVisibleFlag(v:Boolean){}}
