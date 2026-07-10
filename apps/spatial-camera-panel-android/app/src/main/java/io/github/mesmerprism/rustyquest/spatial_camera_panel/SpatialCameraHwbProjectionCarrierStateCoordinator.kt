package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.SystemClock

internal data class SpatialCameraHwbProjectionCarrierNativeState(
    val receiptLibraryLoaded: Boolean,
)

internal data class SpatialCameraHwbProjectionCarrierStateBindings(
    val resolveCarrierMode: () -> CameraHwbProjectionCarrierMode,
    val virtualRoomEnabled: () -> Boolean,
    val carrierTransportFromIntent: () -> Boolean,
    val routeActive: () -> Boolean,
    val secondaryToggleEnabled: () -> Boolean,
    val projectionEntityPresent: () -> Boolean,
    val resetPlacementMarkerCadence: () -> Unit,
    val updatePlacement: (String, Boolean) -> Unit,
    val nativeState: () -> SpatialCameraHwbProjectionCarrierNativeState,
    val privateLayerOverride: () -> Float,
    val reapplyPrivateLayerOverride: (Float) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionCarrierStateCoordinator(
    private val bindings: SpatialCameraHwbProjectionCarrierStateBindings,
) {
  private var placementMode = CameraHwbProjectionPlacementMode.ViewerLocked
  private var carrierMode = CameraHwbProjectionCarrierMode.SceneQuadLayerRoomObject
  private var lastPlacementToggleMs = 0L
  private var secondaryToggleArmed = false

  fun placementMode(): CameraHwbProjectionPlacementMode = placementMode

  fun carrierMode(): CameraHwbProjectionCarrierMode = carrierMode

  fun refreshCarrierMode() {
    carrierMode = bindings.resolveCarrierMode()
  }

  fun resetForLaunch() {
    refreshCarrierMode()
    secondaryToggleArmed = false
  }

  fun zIndexForPlacement(placementMode: CameraHwbProjectionPlacementMode): Int =
      CameraHwbProjectionModule.zIndexForPlacement(carrierMode, placementMode)

  fun displayRoleForPlacement(placementMode: CameraHwbProjectionPlacementMode): String =
      CameraHwbProjectionModule.displayRoleForPlacement(placementMode)

  fun scenePanelCarrierEnabled(): Boolean =
      CameraHwbProjectionModule.scenePanelCarrierEnabled(carrierMode)

  fun manualCustomMeshCarrierEnabled(): Boolean =
      CameraHwbProjectionModule.manualCustomMeshCarrierEnabled(carrierMode)

  fun panelRegistrationId(): String = CameraHwbProjectionModule.panelRegistrationId(carrierMode)

  fun carrierToken(): String = CameraHwbProjectionModule.carrierToken(carrierMode)

  fun roomRenderOrderToken(): String =
      CameraHwbProjectionModule.roomRenderOrderToken(bindings.virtualRoomEnabled(), carrierMode)

  fun startGateToken(): String =
      CameraHwbProjectionModule.startGateToken(bindings.virtualRoomEnabled())

  fun carrierTransportToken(): String =
      CameraHwbProjectionModule.carrierTransportToken(bindings.carrierTransportFromIntent())

  fun panelHittableToken(): String = CameraHwbProjectionModule.panelHittableToken(carrierMode)

  fun togglePlacementMode(inputSource: String, detail: String): Boolean {
    if (!bindings.routeActive()) {
      return false
    }
    val now = SystemClock.elapsedRealtime()
    if (!bindings.secondaryToggleEnabled()) {
      bindings.marker(
          CameraHwbProjectionModule.projectionPlacementToggleDisabledMarker(
              inputSource = inputSource,
              detail = detail,
              placementMode = placementMode,
          )
      )
      return true
    }
    if (!secondaryToggleArmed) {
      bindings.marker(
          CameraHwbProjectionModule.projectionPlacementToggleNotArmedMarker(
              inputSource = inputSource,
              detail = detail,
              placementMode = placementMode,
          )
      )
      return true
    }
    if (
        lastPlacementToggleMs > 0L &&
            now - lastPlacementToggleMs < CAMERA_HWB_PROJECTION_PLACEMENT_TOGGLE_DEBOUNCE_MS
    ) {
      bindings.marker(
          CameraHwbProjectionModule.projectionPlacementToggleDebouncedMarker(
              inputSource = inputSource,
              detail = detail,
              placementMode = placementMode,
          )
      )
      return true
    }
    lastPlacementToggleMs = now
    val previous = placementMode
    placementMode =
        when (previous) {
          CameraHwbProjectionPlacementMode.ViewerLocked ->
              CameraHwbProjectionPlacementMode.VirtualRoomWall
          CameraHwbProjectionPlacementMode.VirtualRoomWall ->
              CameraHwbProjectionPlacementMode.ViewerLocked
        }
    bindings.resetPlacementMarkerCadence()
    bindings.updatePlacement("controller-secondary-toggle", true)
    val nativeState = bindings.nativeState()
    val layerOverride = bindings.privateLayerOverride()
    val layerOverrideReapplyMask =
        if (nativeState.receiptLibraryLoaded) {
          runCatching { bindings.reapplyPrivateLayerOverride(layerOverride) }
              .getOrElse { throwable ->
                bindings.marker(
                    SpatialPanelPlacementModule.privateLayerPanelLayerOverrideReapplyFailedMarker(
                        placementMode = placementMode,
                        privateLayerOverride = layerOverride,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
                0L
              }
        } else {
          0L
        }
    bindings.marker(
        CameraHwbProjectionModule.projectionPlacementToggledMarker(
            inputSource = inputSource,
            detail = detail,
            previousPlacementMode = previous,
            placementMode = placementMode,
            projectionEntityPresent = bindings.projectionEntityPresent(),
            carrierMode = carrierMode,
            roomRenderOrderToken = roomRenderOrderToken(),
            layerOverrideReapplied =
                nativeState.receiptLibraryLoaded && layerOverrideReapplyMask != 0L,
            layerOverrideUpdateMask = layerOverrideReapplyMask,
            layerOverride = layerOverride,
        )
    )
    return true
  }

  fun armSecondaryToggle(inputSource: String) {
    if (!bindings.routeActive() || secondaryToggleArmed) {
      return
    }
    secondaryToggleArmed = true
    bindings.marker(CameraHwbProjectionModule.projectionPlacementToggleArmedMarker(inputSource))
  }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-carrier-state-coordinator"
  }
}
