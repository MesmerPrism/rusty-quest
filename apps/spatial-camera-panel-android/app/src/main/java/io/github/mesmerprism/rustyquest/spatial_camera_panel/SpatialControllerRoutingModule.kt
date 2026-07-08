package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.InputDevice
import android.view.MotionEvent
import com.meta.spatial.vr.VrInputSystemType
import java.util.Locale
import kotlin.math.abs

internal const val SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS = 2500L
internal const val SPATIAL_JOYSTICK_ARBITRATION_MARKER_INTERVAL_MS = 350L
internal const val SPATIAL_VR_INPUT_SYSTEM_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.vr_input_system"
internal const val SPATIAL_VR_INPUT_SYSTEM_DEFAULT_TOKEN = "interaction_sdk"
internal const val SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.consume_left_right_input"
internal const val SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_DEFAULT = false
internal const val CONTROLLER_TRIGGER_PRESS_THRESHOLD = 0.65f
internal const val NATIVE_SPATIAL_CONTROLLER_ACTIONS_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.native_controller_actions.enabled"
internal const val NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED = false

internal enum class SpatialControllerPanelToggleAction(val markerToken: String) {
  ClosePrivateLayerPanel("close-private-layer-panel"),
  CloseWorkflowPanel("close-workflow-panel"),
  OpenPrivateLayerPanel("open-private-layer-panel"),
  OpenWorkflowPanel("open-workflow-panel"),
}

internal object SpatialControllerRoutingModule {
  fun spatialVrInputSystemToken(rawValue: String): String =
      when (rawValue.trim().lowercase(Locale.US)) {
        "simple", "simple-controller", "simple_controller" -> "simple_controller"
        "interaction", "interaction-sdk", "interaction_sdk", "isdk" -> "interaction_sdk"
        "default", "" -> SPATIAL_VR_INPUT_SYSTEM_DEFAULT_TOKEN
        else -> SPATIAL_VR_INPUT_SYSTEM_DEFAULT_TOKEN
      }

  fun spatialVrInputSystemType(token: String): VrInputSystemType =
      when (token) {
        "simple_controller" -> VrInputSystemType.SIMPLE_CONTROLLER
        else -> VrInputSystemType.INTERACTION_SDK
      }

  fun shouldConsumeLeftRightInput(rawValue: Boolean?): Boolean =
      rawValue ?: SPATIAL_SHOULD_CONSUME_LEFT_RIGHT_INPUT_DEFAULT

  fun panelHeadlockJoystickEnabled(rawValue: Boolean?): Boolean = rawValue ?: true

  fun leftStickPanelDistanceEnabled(
      joystickEnabled: Boolean,
      privateLayerPanelVisible: Boolean,
      privateLayerFreeTransform: Boolean,
      privateLayerGrabbed: Boolean,
      privateLayerHeadlocked: Boolean,
      workflowPanelVisible: Boolean,
      workflowPanelHeadlocked: Boolean,
  ): Boolean =
      joystickEnabled &&
          when {
            privateLayerPanelVisible ->
                if (privateLayerFreeTransform) !privateLayerGrabbed else privateLayerHeadlocked
            workflowPanelVisible -> workflowPanelHeadlocked
            else -> false
          }

  fun leftStickPanelDistanceMapping(
      privateLayerPanelVisible: Boolean,
      privateLayerFreeTransform: Boolean,
  ): String =
      if (privateLayerPanelVisible && privateLayerFreeTransform) {
        "left-stick-y-private-panel-free-transform-distance"
      } else {
        "left-stick-y-panel-distance"
      }

  fun isRightPrimaryPanelToggleSource(inputSource: String): Boolean =
      inputSource == "spatial-sdk-avatar-body-controller" ||
          inputSource == "spatial-sdk-controller-component" ||
          inputSource == "spatial-sdk-controller-component-fallback" ||
          inputSource == "android-key-event" ||
          inputSource == "android-generic-motion-button"

  fun panelToggleAction(
      privateLayerPanelVisible: Boolean,
      workflowPanelVisible: Boolean,
      opensPrivateLayerPanel: Boolean,
  ): SpatialControllerPanelToggleAction =
      when {
        privateLayerPanelVisible -> SpatialControllerPanelToggleAction.ClosePrivateLayerPanel
        workflowPanelVisible -> SpatialControllerPanelToggleAction.CloseWorkflowPanel
        opensPrivateLayerPanel -> SpatialControllerPanelToggleAction.OpenPrivateLayerPanel
        else -> SpatialControllerPanelToggleAction.OpenWorkflowPanel
      }

  fun isJoystickEvent(event: MotionEvent): Boolean =
      event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)

  fun joystickAxis(
      event: MotionEvent,
      primaryAxis: Int,
      fallbackAxis: Int? = null,
      deadzone: Float = PANEL_HEADLOCK_JOYSTICK_DEADZONE,
  ): Float {
    val primary = event.getAxisValue(primaryAxis)
    val value =
        if (abs(primary) >= deadzone || fallbackAxis == null) {
          primary
        } else {
          event.getAxisValue(fallbackAxis)
        }
    return if (abs(value) >= deadzone) value.coerceIn(-1.0f, 1.0f) else 0.0f
  }

  fun nativeSpatialControllerActionsEnabled(rawValue: Boolean?): Boolean =
      rawValue ?: NATIVE_SPATIAL_CONTROLLER_ACTIONS_DEFAULT_ENABLED
}
