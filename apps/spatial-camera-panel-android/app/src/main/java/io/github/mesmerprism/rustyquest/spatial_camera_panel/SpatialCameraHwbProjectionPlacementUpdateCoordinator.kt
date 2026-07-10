package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.SystemClock
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector2
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialCameraHwbProjectionPlacementNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialCameraHwbProjectionPlacementUpdateBindings(
    val resources: SpatialSdkQuadResourceCoordinator,
    val routeActive: () -> Boolean,
    val projectionEntity: () -> Entity?,
    val scenePanelCarrierEnabled: () -> Boolean,
    val projectionPlane: () -> CameraHwbProjectionPlane,
    val updatePanelCarrierLayer: (CameraHwbProjectionPlane, String) -> String,
    val layerZIndex: (CameraHwbProjectionPlacementMode) -> Int,
    val nativeState: () -> SpatialCameraHwbProjectionPlacementNativeState,
    val updateNativePanelPose: (CameraHwbProjectionPlane) -> Long,
    val projectionMarkerFields: (CameraHwbProjectionPlane) -> String,
    val stereoMarkerFields: () -> String,
    val videoProjectionMarkerFields: () -> String,
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionPlacementUpdateCoordinator(
    private val bindings: SpatialCameraHwbProjectionPlacementUpdateBindings,
) {
  private var markerCount = 0
  private var lastMarkerMs = 0L

  fun resetMarkerCadence() {
    markerCount = 0
    lastMarkerMs = 0L
  }

  fun update(reason: String, forceLog: Boolean) {
    if (!bindings.routeActive()) {
      return
    }
    val entity = bindings.projectionEntity() ?: return
    val plane = bindings.projectionPlane()
    entity.setComponent(Transform(plane.pose))
    if (bindings.scenePanelCarrierEnabled()) {
      entity.setComponent(
          PanelDimensions(Vector2(plane.projectionWidthMeters, plane.projectionHeightMeters))
      )
      entity.setComponent(Hittable(MeshCollision.NoCollision))
    }
    entity.setComponent(Visible(true))
    val layerUpdateStatus = updateRawLayer(plane, reason)
    val panelCarrierUpdateStatus = bindings.updatePanelCarrierLayer(plane, reason)
    val nativePanelPoseUpdateMask = updateNativePanelPose(plane, reason, forceLog)
    val now = SystemClock.elapsedRealtime()
    val shouldLog =
        forceLog ||
            (markerCount < CAMERA_HWB_PROJECTION_MARKER_LIMIT &&
                now - lastMarkerMs >= CAMERA_HWB_PROJECTION_MARKER_INTERVAL_MS)
    if (!shouldLog) {
      return
    }
    markerCount += 1
    lastMarkerMs = now
    bindings.marker(
        CameraHwbProjectionModule.rawProjectionPlaneUpdatedMarker(
            reason = reason,
            plane = plane,
            projectionMarkerFields = bindings.projectionMarkerFields(plane),
            stereoMarkerFields = bindings.stereoMarkerFields(),
            videoProjectionMarkerFields = bindings.videoProjectionMarkerFields(),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
            layerUpdateStatus = layerUpdateStatus,
            panelCarrierUpdateStatus = panelCarrierUpdateStatus,
            nativePanelPoseUpdateMask = nativePanelPoseUpdateMask,
        )
    )
  }

  private fun updateRawLayer(plane: CameraHwbProjectionPlane, reason: String): String =
      bindings.resources.withLayer { layer ->
        runCatching {
              layer.updateLayer(
                  plane.projectionWidthMeters,
                  plane.projectionHeightMeters,
                  0.5f,
                  0.5f,
                  StereoMode.LeftRight.ordinal,
              )
              layer.setZIndex(bindings.layerZIndex(plane.placementMode))
              "updated-existing-scene-anchor"
            }
            .getOrElse { throwable ->
              bindings.marker(
                  CameraHwbProjectionModule.rawProjectionLayerUpdateFailedMarker(
                      reason = reason,
                      plane = plane,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              "failed-${throwable.javaClass.simpleName}"
            }
      } ?: "layer-missing"

  private fun updateNativePanelPose(
      plane: CameraHwbProjectionPlane,
      reason: String,
      forceLog: Boolean,
  ): Long {
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      if (forceLog) {
        bindings.marker(
            CameraHwbProjectionModule.nativePanelPoseUpdateSkippedMarker(
                reason = reason,
                error = nativeState.receiptLibraryError,
            )
        )
      }
      return 0L
    }
    return runCatching { bindings.updateNativePanelPose(plane) }
        .getOrElse { throwable ->
          if (forceLog) {
            bindings.marker(
                CameraHwbProjectionModule.nativePanelPoseUpdateFailedMarker(
                    reason = reason,
                    plane = plane,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
          0L
        }
  }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-placement-update-coordinator"
    private const val CAMERA_HWB_PROJECTION_MARKER_LIMIT = 4
  }
}
