package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.math.abs

internal enum class SpatialImmersiveVideoSelectionDirection(val action: String) {
  Previous("previous"),
  Next("next"),
}

internal data class SpatialImmersiveVideoSelectionInputBindings(
    val selectionEnabled: () -> Boolean,
    val select: (SpatialImmersiveVideoSelectionDirection, String) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialImmersiveVideoSelectionInputCoordinator(
    private val bindings: SpatialImmersiveVideoSelectionInputBindings,
) {
  private var armed = true

  fun handleRightStick(rightX: Float, rightY: Float, inputSource: String): Boolean {
    val horizontalMagnitude = abs(rightX)
    if (horizontalMagnitude <= REARM_THRESHOLD) {
      armed = true
      return false
    }
    if (!bindings.selectionEnabled()) {
      return false
    }

    val horizontalDominant = horizontalMagnitude > abs(rightY)
    if (!horizontalDominant || horizontalMagnitude < OBSERVED_THRESHOLD) {
      return false
    }
    if (!armed || horizontalMagnitude < FLICK_THRESHOLD) {
      return true
    }

    armed = false
    val direction =
        if (rightX < 0.0f) {
          SpatialImmersiveVideoSelectionDirection.Previous
        } else {
          SpatialImmersiveVideoSelectionDirection.Next
        }
    bindings.select(direction, inputSource)
    bindings.marker(
        "channel=spatial-immersive-video status=controller-flick-selection " +
            "inputSource=${activityMarkerToken(inputSource)} " +
            "controllerJoystickMapping=right-stick-x-video-selection " +
            "direction=${direction.action} rightStickX=${activityMarkerFloat(rightX)} " +
            "rightStickY=${activityMarkerFloat(rightY)} " +
            "horizontalDominant=true requiresNeutralRearm=true"
    )
    return true
  }

  companion object {
    const val MODULE_ID = "spatial-immersive-video-selection-input-coordinator"
    const val FLICK_THRESHOLD = 0.72f
    const val REARM_THRESHOLD = 0.24f
    private const val OBSERVED_THRESHOLD = PANEL_HEADLOCK_JOYSTICK_DEADZONE
  }
}
