package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.InputDevice
import android.view.MotionEvent
import com.meta.spatial.runtime.ButtonBits
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

internal data class SpatialJoystickArbitrationMarkerInput(
    val inputSource: String,
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
    val projectionScaleHandled: Boolean,
    val panelPlacementHandled: Boolean,
    val rightStickSwallowedAsIgnored: Boolean,
    val leftStickYDeliveredToPanelScroll: Boolean,
    val leftStickYPanelDistanceObserved: Boolean,
    val consumedByActivity: Boolean,
    val leftStickYPanelDistanceEnabled: Boolean,
    val privateLayerPanelVisible: Boolean,
    val panelMode: String,
    val projectionTargetLiveScale: Float,
    val headlockMarkerFields: String,
)

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

  fun joystickArbitrationMarker(input: SpatialJoystickArbitrationMarkerInput): String =
      "channel=spatial-panel status=joystick-input-arbitrated " +
          "inputSource=${activityMarkerToken(input.inputSource)} " +
          "leftStick=${activityMarkerFloat(input.leftX)};${activityMarkerFloat(input.leftY)} " +
          "rightStick=${activityMarkerFloat(input.rightX)};${activityMarkerFloat(input.rightY)} " +
          "projectionScaleHandled=${input.projectionScaleHandled} " +
          "panelPlacementHandled=${input.panelPlacementHandled} " +
          "rightStickSwallowedAsIgnored=${input.rightStickSwallowedAsIgnored} " +
          "leftStickYDeliveredToPanelScroll=${input.leftStickYDeliveredToPanelScroll} " +
          "leftStickYPanelDistanceObserved=${input.leftStickYPanelDistanceObserved} " +
          "consumedByActivity=${input.consumedByActivity} " +
          "leftStickYPanelDistanceEnabled=${input.leftStickYPanelDistanceEnabled} " +
          "leftStickYPanelScrollReserved=false " +
          "leftStickYProjectionHorizontalOffsetDisabled=true " +
          "rightStickYProjectionScaleEnabled=${!input.privateLayerPanelVisible} " +
          "rightStickYProjectionScaleSuppressedByPrivateLayerPanel=${input.privateLayerPanelVisible} " +
          "rightStickYPanelDistanceDisabled=true " +
          "rightStickXIgnored=true rightStickXPanelScaleDisabled=true " +
          "panelMode=${input.panelMode} " +
          "projectionTargetLiveScale=${activityMarkerFloat(input.projectionTargetLiveScale)} " +
          input.headlockMarkerFields

  fun headlockDistanceJoystickAdjustedMarker(
      inputSource: String,
      controllerJoystickMapping: String,
      detail: String,
      leftY: Float,
      dtSeconds: Float,
      distanceRate: Float,
      previousDistance: Float,
      leftStickYPanelDistanceEnabled: Boolean,
      panelDistanceControl: String,
      headlockMarkerFields: String,
  ): String =
      "channel=spatial-panel status=headlock-distance-joystick-adjusted " +
          "inputSource=${activityMarkerToken(inputSource)} " +
          "controllerJoystickMapping=${activityMarkerToken(controllerJoystickMapping)} " +
          "${detail.trim()} " +
          "leftThumbY=${activityMarkerFloat(leftY)} " +
          "dtSeconds=${activityMarkerFloat(dtSeconds)} " +
          "distanceRateMps=${activityMarkerFloat(distanceRate)} " +
          "previousHeadlockedPanelDistanceMeters=${activityMarkerFloat(previousDistance)} " +
          "leftStickUpIncreasesPanelDistance=true leftStickDownDecreasesPanelDistance=true " +
          "leftStickYPanelDistanceEnabled=$leftStickYPanelDistanceEnabled " +
          "leftStickYPanelScrollReserved=false " +
          "leftStickYProjectionHorizontalOffsetDisabled=true " +
          "panelDistanceControl=${activityMarkerToken(panelDistanceControl)} " +
          headlockMarkerFields

  fun privateLayerFreeTransformDistanceJoystickSkippedMarker(
      inputSource: String,
      detail: String,
      leftY: Float,
      headlockMarkerFields: String,
  ): String =
      "channel=spatial-panel status=private-layer-free-transform-distance-joystick-skipped " +
          "inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} " +
          "leftThumbY=${activityMarkerFloat(leftY)} " +
          "privateLayerPanelIsGrabbed=true " +
          "leftStickYPanelDistanceEnabled=false " +
          "panelDistanceControl=left-stick-y-free-transform-distance " +
          headlockMarkerFields

  fun privateLayerFreeTransformDistanceJoystickAdjustedMarker(
      inputSource: String,
      detail: String,
      leftY: Float,
      dtSeconds: Float,
      distanceRate: Float,
      previousDistance: Float,
      updatedDistance: Float,
      leftStickYPanelDistanceEnabled: Boolean,
      headlockMarkerFields: String,
  ): String =
      "channel=spatial-panel status=private-layer-free-transform-distance-joystick-adjusted " +
          "inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} " +
          "leftThumbY=${activityMarkerFloat(leftY)} " +
          "dtSeconds=${activityMarkerFloat(dtSeconds)} " +
          "distanceRateMps=${activityMarkerFloat(distanceRate)} " +
          "previousHeadlockedPanelDistanceMeters=${activityMarkerFloat(previousDistance)} " +
          "headlockedPanelDistanceMeters=${activityMarkerFloat(updatedDistance)} " +
          "leftStickUpIncreasesPanelDistance=true leftStickDownDecreasesPanelDistance=true " +
          "leftStickYPanelDistanceEnabled=$leftStickYPanelDistanceEnabled " +
          "leftStickYPanelScrollReserved=false " +
          "leftStickYProjectionHorizontalOffsetDisabled=true " +
          "panelDistanceControl=left-stick-y-free-transform-distance " +
          "privateLayerPanelDistancePersistsAcrossToggle=true " +
          "rightStickSideFlickPanelMoveDisabled=true " +
          headlockMarkerFields

  fun spatialInputEnabledMarker(
      reason: String,
      spatialInterfaceEnableInput: Boolean,
      gameControllerDeviceCount: Int,
      pinnedGameControllerCount: Int,
      newlyPinnedGameControllerCount: Int,
  ): String =
      "channel=spatial-panel status=spatial-input-enabled " +
          "reason=${activityMarkerToken(reason)} spatialInterfaceEnableInput=$spatialInterfaceEnableInput " +
          "gameControllerDeviceCount=$gameControllerDeviceCount " +
          "pinnedGameControllerCount=$pinnedGameControllerCount " +
          "newlyPinnedGameControllerCount=$newlyPinnedGameControllerCount " +
          "controllerInputRoutes=spatial-sdk-controller-component+spatial-sdk-avatar-body-controller+interaction-sdk-pointer+pinned-android-game-controller-fallback+native-openxr-diagnostic-opt-in"

  fun nativeControllerActionPollErrorMarker(
      controllerInput: String,
      error: String,
      message: String,
  ): String =
      "channel=spatial-controller-actions status=poll-error " +
          "nativeControllerActionBridge=true controllerInput=$controllerInput " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} " +
          "actionSetAttached=false"

  fun controllerInputRouteErrorMarker(error: String, message: String): String =
      "channel=spatial-panel status=controller-input-route-error " +
          "inputSource=spatial-sdk-avatar-body-controller " +
          "controllerInput=right-primary-button error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} debugOnly=true"

  fun controllerInputRouteReadyMarker(
      snapshot: SpatialControllerPrimarySnapshot,
      leftStickPanelDistanceMapping: String,
      leftStickYPanelDistanceEnabled: Boolean,
      spatialVrInputSystem: String,
  ): String =
      "channel=spatial-panel status=controller-input-route-ready " +
          "inputSource=${activityMarkerToken(snapshot.rightInputSource)} " +
          "controllerInput=right-primary-button+right-secondary-button-wall-toggle+right-trigger-particle-recenter+right-thumb-up-down-projection-scale+$leftStickPanelDistanceMapping " +
          "spatialVrInputSystem=$spatialVrInputSystem " +
          "controllerComponentCount=${snapshot.componentCount} " +
          "controllerTypeComponentCount=${snapshot.controllerTypeCount} " +
          "activeControllerComponentCount=${snapshot.activeCount} " +
          "localControllerComponentCount=${snapshot.localControllerCount} " +
          "localActiveControllerComponentCount=${snapshot.localActiveControllerCount} " +
          "localRightControllerType=${activityMarkerToken(snapshot.localRightControllerType)} " +
          "localRightControllerAttachmentType=${activityMarkerToken(snapshot.localRightControllerAttachmentType)} " +
          "localRightControllerActive=${snapshot.localRightControllerActive} " +
          "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
          "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
          "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
          "avatarBodyCount=${snapshot.avatarBodyCount} " +
          "playerAvatarBodyCount=${snapshot.playerAvatarBodyCount} " +
          "leftAvatarControllerType=${activityMarkerToken(snapshot.leftAvatarControllerType)} " +
          "leftAvatarControllerActive=${snapshot.leftAvatarControllerActive} " +
          "leftAvatarButtonState=${snapshot.leftAvatarButtonState} " +
          "leftAvatarChangedButtons=${snapshot.leftAvatarChangedButtons} " +
          "rightAvatarControllerType=${activityMarkerToken(snapshot.rightAvatarControllerType)} " +
          "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
          "rightControllerInactiveButtonStateAccepted=true " +
          "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
          "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
          "buttonABit=${ButtonBits.ButtonA} buttonADown=${snapshot.down} " +
          "buttonBBit=${ButtonBits.ButtonB} buttonBDown=${snapshot.secondaryDown} " +
          "rightTriggerBit=${ButtonBits.ButtonTriggerR} rightTriggerDown=${snapshot.triggerDown} " +
          "rightTriggerParticleRecenterEnabledForIcosphere=true " +
          "leftThumbUpBit=${ButtonBits.ButtonThumbLU} leftThumbDownBit=${ButtonBits.ButtonThumbLD} " +
          "leftThumbUp=${snapshot.leftThumbUp} leftThumbDown=${snapshot.leftThumbDown} " +
          "leftThumbYPanelDistanceEnabled=$leftStickYPanelDistanceEnabled " +
          "leftThumbYPanelScrollReserved=false " +
          "leftThumbYProjectionHorizontalOffsetDisabled=true " +
          "rightThumbUpBit=${ButtonBits.ButtonThumbRU} rightThumbDownBit=${ButtonBits.ButtonThumbRD} " +
          "rightThumbUp=${snapshot.rightThumbUp} rightThumbDown=${snapshot.rightThumbDown} " +
          "rightThumbY=${activityMarkerFloat(snapshot.rightThumbY)} " +
          "activeButtonState=${snapshot.buttonState} activeChangedButtons=${snapshot.changedButtons} " +
          "allControllerButtonState=${snapshot.allControllerButtonState} " +
          "allControllerChangedButtons=${snapshot.allControllerChangedButtons} " +
          "debugOnly=true"

  fun rightThumbProjectionScaleDetail(snapshot: SpatialControllerPrimarySnapshot): String =
      "rightThumbY=${activityMarkerFloat(snapshot.rightThumbY)} " +
          "rightThumbUp=${snapshot.rightThumbUp} rightThumbDown=${snapshot.rightThumbDown} " +
          "rightThumbUpBit=${ButtonBits.ButtonThumbRU} rightThumbDownBit=${ButtonBits.ButtonThumbRD} " +
          "rightAvatarControllerType=${activityMarkerToken(snapshot.rightAvatarControllerType)} " +
          "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
          "rightControllerInactiveButtonStateAccepted=true " +
          "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
          "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
          "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
          "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
          "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
          "allControllerButtonState=${snapshot.allControllerButtonState}"

  fun leftThumbPanelDistanceDetail(snapshot: SpatialControllerPrimarySnapshot): String =
      "leftThumbY=${activityMarkerFloat(snapshot.leftThumbY)} " +
          "leftThumbUp=${snapshot.leftThumbUp} leftThumbDown=${snapshot.leftThumbDown} " +
          "leftThumbUpBit=${ButtonBits.ButtonThumbLU} leftThumbDownBit=${ButtonBits.ButtonThumbLD} " +
          "leftAvatarControllerType=${activityMarkerToken(snapshot.leftAvatarControllerType)} " +
          "leftAvatarControllerActive=${snapshot.leftAvatarControllerActive} " +
          "leftAvatarButtonState=${snapshot.leftAvatarButtonState} " +
          "leftAvatarChangedButtons=${snapshot.leftAvatarChangedButtons} " +
          "allControllerButtonState=${snapshot.allControllerButtonState}"

  fun rightTriggerParticleRecenterDetail(snapshot: SpatialControllerPrimarySnapshot): String =
      "buttonTriggerRBit=${ButtonBits.ButtonTriggerR} buttonState=${snapshot.buttonState} " +
          "changedButtons=${snapshot.changedButtons} " +
          "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
          "localRightControllerType=${activityMarkerToken(snapshot.localRightControllerType)} " +
          "localRightControllerAttachmentType=${activityMarkerToken(snapshot.localRightControllerAttachmentType)} " +
          "localRightControllerActive=${snapshot.localRightControllerActive} " +
          "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
          "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
          "rightAvatarControllerType=${activityMarkerToken(snapshot.rightAvatarControllerType)} " +
          "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
          "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
          "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
          "controllerComponentCount=${snapshot.componentCount} " +
          "activeControllerComponentCount=${snapshot.activeCount}"

  fun rightSecondaryPlacementToggleDetail(snapshot: SpatialControllerPrimarySnapshot): String =
      "buttonBBit=${ButtonBits.ButtonB} buttonState=${snapshot.buttonState} " +
          "changedButtons=${snapshot.changedButtons} " +
          "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
          "localRightControllerType=${activityMarkerToken(snapshot.localRightControllerType)} " +
          "localRightControllerAttachmentType=${activityMarkerToken(snapshot.localRightControllerAttachmentType)} " +
          "localRightControllerActive=${snapshot.localRightControllerActive} " +
          "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
          "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
          "rightAvatarControllerType=${activityMarkerToken(snapshot.rightAvatarControllerType)} " +
          "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
          "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
          "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
          "controllerComponentCount=${snapshot.componentCount} " +
          "activeControllerComponentCount=${snapshot.activeCount}"

  fun rightPrimaryPanelToggleDetail(snapshot: SpatialControllerPrimarySnapshot): String =
      "buttonABit=${ButtonBits.ButtonA} buttonState=${snapshot.buttonState} " +
          "changedButtons=${snapshot.changedButtons} " +
          "localRightControllerPreferred=${snapshot.rightInputSource == "spatial-sdk-controller-component"} " +
          "localRightControllerType=${activityMarkerToken(snapshot.localRightControllerType)} " +
          "localRightControllerAttachmentType=${activityMarkerToken(snapshot.localRightControllerAttachmentType)} " +
          "localRightControllerActive=${snapshot.localRightControllerActive} " +
          "localRightControllerButtonState=${snapshot.localRightControllerButtonState} " +
          "localRightControllerChangedButtons=${snapshot.localRightControllerChangedButtons} " +
          "rightAvatarControllerType=${activityMarkerToken(snapshot.rightAvatarControllerType)} " +
          "rightAvatarControllerActive=${snapshot.rightAvatarControllerActive} " +
          "rightControllerInactiveButtonStateAccepted=true " +
          "rightAvatarButtonState=${snapshot.rightAvatarButtonState} " +
          "rightAvatarChangedButtons=${snapshot.rightAvatarChangedButtons} " +
          "controllerComponentCount=${snapshot.componentCount} " +
          "activeControllerComponentCount=${snapshot.activeCount}"

  fun controllerPrimaryToggledPanelMarker(
      inputSource: String,
      detail: String,
      panelToggleAction: SpatialControllerPanelToggleAction,
      panelMode: String,
      workflowPanelVisible: Boolean,
      privateLayerPanelVisible: Boolean,
      opensPrivateLayerPanel: Boolean,
  ): String =
      "channel=spatial-panel status=controller-primary-toggled-panel " +
          "controllerInput=right-primary-button inputSource=${activityMarkerToken(inputSource)} " +
          "${detail.trim()} " +
          "panelToggleAction=${activityMarkerToken(panelToggleAction.markerToken)} " +
          "panelMode=$panelMode workflowPanelVisible=$workflowPanelVisible " +
          "privateLayerPanelVisible=$privateLayerPanelVisible " +
          "opensPrivateLayerPanel=$opensPrivateLayerPanel " +
          "spatialPrivateLayerControlPanel=$opensPrivateLayerPanel " +
          "debugOnly=true"
}
