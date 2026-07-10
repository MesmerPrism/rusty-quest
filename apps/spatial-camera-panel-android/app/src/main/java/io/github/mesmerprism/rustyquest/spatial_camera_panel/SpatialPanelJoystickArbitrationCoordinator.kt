package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.math.abs

internal data class SpatialPanelJoystickAxes(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
)

internal data class SpatialPanelJoystickArbitrationBindings(
    val applyProjectionScale: (Float) -> Boolean,
    val applyPanelPlacement: (SpatialPanelJoystickAxes, String) -> Boolean,
    val leftStickPanelDistanceEnabled: () -> Boolean,
    val privateLayerPanelVisible: () -> Boolean,
    val panelMode: () -> String,
    val projectionTargetScale: () -> Float,
    val headlockMarkerFields: () -> String,
    val elapsedRealtimeMs: () -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialPanelJoystickArbitrationCoordinator(
    private val bindings: SpatialPanelJoystickArbitrationBindings,
) {
  private var lastArbitrationMarkerMs = 0L

  fun handle(axes: SpatialPanelJoystickAxes, inputSource: String): Boolean {
    val observed =
        abs(axes.leftX) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(axes.leftY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(axes.rightX) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(axes.rightY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE
    if (!observed) return false

    val projectionScaleHandled = bindings.applyProjectionScale(axes.rightY)
    val panelPlacementHandled =
        if (projectionScaleHandled) {
          false
        } else {
          bindings.applyPanelPlacement(axes, inputSource)
        }
    val rightStickObserved =
        abs(axes.rightX) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE ||
            abs(axes.rightY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE
    val leftDistanceObserved = abs(axes.leftY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE
    val rightStickSwallowedAsIgnored =
        rightStickObserved &&
            !projectionScaleHandled &&
            !panelPlacementHandled &&
            !leftDistanceObserved
    val consumed = projectionScaleHandled || panelPlacementHandled || rightStickSwallowedAsIgnored
    val leftStickPanelDistanceEnabled = bindings.leftStickPanelDistanceEnabled()
    val privateLayerPanelVisible = bindings.privateLayerPanelVisible()
    val leftStickYDeliveredToPanelScroll =
        leftDistanceObserved &&
            privateLayerPanelVisible &&
            !leftStickPanelDistanceEnabled &&
            !consumed
    val now = bindings.elapsedRealtimeMs()
    if (now - lastArbitrationMarkerMs >= SPATIAL_JOYSTICK_ARBITRATION_MARKER_INTERVAL_MS) {
      lastArbitrationMarkerMs = now
      bindings.marker(
          SpatialControllerRoutingModule.joystickArbitrationMarker(
              SpatialJoystickArbitrationMarkerInput(
                  inputSource = inputSource,
                  leftX = axes.leftX,
                  leftY = axes.leftY,
                  rightX = axes.rightX,
                  rightY = axes.rightY,
                  projectionScaleHandled = projectionScaleHandled,
                  panelPlacementHandled = panelPlacementHandled,
                  rightStickSwallowedAsIgnored = rightStickSwallowedAsIgnored,
                  leftStickYDeliveredToPanelScroll = leftStickYDeliveredToPanelScroll,
                  leftStickYPanelDistanceObserved = leftDistanceObserved,
                  consumedByActivity = consumed,
                  leftStickYPanelDistanceEnabled = leftStickPanelDistanceEnabled,
                  privateLayerPanelVisible = privateLayerPanelVisible,
                  panelMode = bindings.panelMode(),
                  projectionTargetLiveScale = bindings.projectionTargetScale(),
                  headlockMarkerFields = bindings.headlockMarkerFields(),
              )
          )
      )
    }
    return consumed
  }

  companion object {
    const val MODULE_ID = "spatial-panel-joystick-arbitration-coordinator"
  }
}
