package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper

internal data class SpatialCameraHwbProjectionLaunchState(
    val enabled: Boolean,
    val sceneReady: Boolean,
    val virtualRoomEnabled: Boolean,
    val virtualRoomLoaded: Boolean,
)

internal data class SpatialCameraHwbProjectionLaunchRequest(
    val readerMaxImages: Int,
    val videoSettings: SpatialVideoProjectionSettings,
)

internal data class SpatialCameraHwbProjectionLaunchBindings(
    val state: () -> SpatialCameraHwbProjectionLaunchState,
    val prepareRequest: () -> SpatialCameraHwbProjectionLaunchRequest,
    val startGateToken: () -> String,
    val carrierToken: () -> String,
    val projectionMarkerFields: () -> String,
    val stereoMarkerFields: () -> String,
    val videoProjectionMarkerFields: (SpatialVideoProjectionSettings) -> String,
    val launch: (SpatialCameraHwbProjectionLaunchRequest) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionLaunchCoordinator(
    private val bindings: SpatialCameraHwbProjectionLaunchBindings,
) {
  var started = false
    private set

  fun runIfRequested(reason: String) {
    val state = bindings.state()
    if (started || !state.enabled) {
      return
    }
    if (!state.sceneReady) {
      bindings.marker(CameraHwbProjectionModule.rawProjectionStartDeferredForSceneMarker(reason))
      return
    }
    if (state.virtualRoomEnabled && !state.virtualRoomLoaded) {
      bindings.marker(
          CameraHwbProjectionModule.rawProjectionStartDeferredForVirtualRoomMarker(
              reason,
              state.sceneReady,
          )
      )
      return
    }
    started = true
    val request = bindings.prepareRequest()
    bindings.marker(
        CameraHwbProjectionModule.rawProjectionStartMarker(
            reason = reason,
            startGateToken = bindings.startGateToken(),
            readerMaxImages = request.readerMaxImages,
            carrier = bindings.carrierToken(),
            projectionMarkerFields = bindings.projectionMarkerFields(),
            stereoMarkerFields = bindings.stereoMarkerFields(),
            videoProjectionMarkerFields =
                bindings.videoProjectionMarkerFields(request.videoSettings),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
        )
    )
    Handler(Looper.getMainLooper()).post { bindings.launch(request) }
  }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-launch-coordinator"
  }
}
