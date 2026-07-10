package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.math.abs

internal data class SpatialPanelDistanceActuationBindings(
    val workflowPlacement: () -> PanelPlacement,
    val privateLayerPlacement: () -> PanelPlacement,
    val privateLayerPanelVisible: () -> Boolean,
    val panelHeadlockJoystickEnabled: () -> Boolean,
    val privateLayerFreeTransform: () -> Boolean,
    val privateLayerPanelGrabbed: () -> Boolean,
    val privateLayerPanelResourceAvailable: () -> Boolean,
    val syncPrivateLayerPlacement: (String) -> Unit,
    val elapsedRealtimeMs: () -> Long,
    val joystickDeltaSeconds: (Long) -> Float,
    val shouldEmitJoystickMarker: (Long) -> Boolean,
    val distanceRateMetersPerSecond: () -> Float,
    val replaceWorkflowPlacement: (PanelPlacement) -> Unit,
    val replacePrivateLayerPlacement: (PanelPlacement) -> Unit,
    val applyPanelPlacement: (Boolean) -> Unit,
    val applyPrivateLayerPanelPose: () -> Unit,
    val persistHeadlockTuning: (String) -> Unit,
    val leftStickPanelDistanceEnabled: () -> Boolean,
    val leftStickPanelDistanceMapping: () -> String,
    val headlockMarkerFields: () -> String,
    val marker: (String) -> Unit,
)

internal class SpatialPanelDistanceActuationCoordinator(
    private val bindings: SpatialPanelDistanceActuationBindings,
) {
  fun apply(
      leftY: Float,
      inputSource: String,
      controllerJoystickMapping: String,
      detail: String,
  ): Boolean {
    if (bindings.privateLayerPanelVisible() && bindings.privateLayerFreeTransform()) {
      return applyPrivateLayerFreeTransform(leftY, inputSource, detail)
    }
    if (bindings.privateLayerPanelVisible()) {
      bindings.syncPrivateLayerPlacement("controller-joystick-distance")
    }
    val privateLayerPanelVisible = bindings.privateLayerPanelVisible()
    val workflowPlacement = bindings.workflowPlacement()
    val placement =
        if (privateLayerPanelVisible) bindings.privateLayerPlacement() else workflowPlacement
    if (
        (!workflowPlacement.visible && !privateLayerPanelVisible) ||
            !placement.headlocked ||
            !bindings.panelHeadlockJoystickEnabled()
    ) {
      return false
    }
    if (abs(leftY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE) return false

    val now = bindings.elapsedRealtimeMs()
    val dtSeconds = bindings.joystickDeltaSeconds(now)
    val distanceRate = bindings.distanceRateMetersPerSecond()
    val previousDistance = placement.zMeters
    val updatedDistance =
        integrateDistance(
            previousDistance = previousDistance,
            leftY = leftY,
            distanceRate = distanceRate,
            dtSeconds = dtSeconds,
            minimumDistance =
                if (privateLayerPanelVisible) PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS
                else PANEL_HEADLOCK_DISTANCE_MIN_METERS,
        )
    if (abs(updatedDistance - previousDistance) < DISTANCE_CHANGE_EPSILON_METERS) return true

    if (privateLayerPanelVisible) {
      bindings.replacePrivateLayerPlacement(
          SpatialPanelPlacementModule.coercePrivateLayerPanelPlacement(
              bindings.privateLayerPlacement().copy(zMeters = updatedDistance)
          )
      )
    } else {
      bindings.replaceWorkflowPlacement(workflowPlacement.copy(zMeters = updatedDistance))
    }
    bindings.applyPanelPlacement(privateLayerPanelVisible)
    bindings.persistHeadlockTuning("controller-joystick-distance")
    if (bindings.shouldEmitJoystickMarker(now)) {
      bindings.marker(
          SpatialControllerRoutingModule.headlockDistanceJoystickAdjustedMarker(
              inputSource = inputSource,
              controllerJoystickMapping = controllerJoystickMapping,
              detail = detail,
              leftY = leftY,
              dtSeconds = dtSeconds,
              distanceRate = distanceRate,
              previousDistance = previousDistance,
              leftStickYPanelDistanceEnabled = bindings.leftStickPanelDistanceEnabled(),
              panelDistanceControl = bindings.leftStickPanelDistanceMapping(),
              headlockMarkerFields = bindings.headlockMarkerFields(),
          )
      )
    }
    return true
  }

  private fun applyPrivateLayerFreeTransform(
      leftY: Float,
      inputSource: String,
      detail: String,
  ): Boolean {
    if (
        !bindings.privateLayerPanelVisible() || !bindings.panelHeadlockJoystickEnabled()
    ) {
      return false
    }
    if (abs(leftY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE) return false
    if (bindings.privateLayerPanelGrabbed()) {
      val now = bindings.elapsedRealtimeMs()
      if (bindings.shouldEmitJoystickMarker(now)) {
        bindings.marker(
            SpatialControllerRoutingModule.privateLayerFreeTransformDistanceJoystickSkippedMarker(
                inputSource = inputSource,
                detail = detail,
                leftY = leftY,
                headlockMarkerFields = bindings.headlockMarkerFields(),
            )
        )
      }
      return true
    }
    if (!bindings.privateLayerPanelResourceAvailable()) return false

    val privateLayerPlacement = bindings.privateLayerPlacement()
    val previousDistance =
        privateLayerPlacement.zMeters.coerceIn(
            PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS,
            PANEL_HEADLOCK_DISTANCE_MAX_METERS,
        )
    val now = bindings.elapsedRealtimeMs()
    val dtSeconds = bindings.joystickDeltaSeconds(now)
    val distanceRate = bindings.distanceRateMetersPerSecond()
    val updatedDistance =
        integrateDistance(
            previousDistance = previousDistance,
            leftY = leftY,
            distanceRate = distanceRate,
            dtSeconds = dtSeconds,
            minimumDistance = PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS,
        )
    if (abs(updatedDistance - previousDistance) < DISTANCE_CHANGE_EPSILON_METERS) return true

    bindings.replacePrivateLayerPlacement(
        SpatialPanelPlacementModule.coercePrivateLayerPanelPlacement(
            privateLayerPlacement.copy(
                visible = true,
                headlocked = false,
                zMeters = updatedDistance,
            )
        )
    )
    bindings.applyPrivateLayerPanelPose()
    bindings.persistHeadlockTuning("controller-joystick-private-free-transform-distance")
    if (bindings.shouldEmitJoystickMarker(now)) {
      bindings.marker(
          SpatialControllerRoutingModule.privateLayerFreeTransformDistanceJoystickAdjustedMarker(
              inputSource = inputSource,
              detail = detail,
              leftY = leftY,
              dtSeconds = dtSeconds,
              distanceRate = distanceRate,
              previousDistance = previousDistance,
              updatedDistance = updatedDistance,
              leftStickYPanelDistanceEnabled = bindings.leftStickPanelDistanceEnabled(),
              headlockMarkerFields = bindings.headlockMarkerFields(),
          )
      )
    }
    return true
  }

  private fun integrateDistance(
      previousDistance: Float,
      leftY: Float,
      distanceRate: Float,
      dtSeconds: Float,
      minimumDistance: Float,
  ): Float {
    val signedInput =
        if (leftY > 0.0f) {
          leftY - PANEL_HEADLOCK_JOYSTICK_DEADZONE
        } else {
          leftY + PANEL_HEADLOCK_JOYSTICK_DEADZONE
        }
    return (previousDistance - signedInput * distanceRate * dtSeconds)
        .coerceIn(minimumDistance, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
  }

  companion object {
    const val MODULE_ID = "spatial-panel-distance-actuation-coordinator"
    private const val DISTANCE_CHANGE_EPSILON_METERS = 0.00001f
  }
}
