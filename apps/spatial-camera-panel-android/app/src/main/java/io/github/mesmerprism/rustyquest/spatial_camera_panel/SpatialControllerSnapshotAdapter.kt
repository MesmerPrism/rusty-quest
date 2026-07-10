package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Query
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType

internal object SpatialControllerSnapshotAdapter {
  const val MODULE_ID = "spatial-controller-snapshot-adapter"

  fun capture(scene: Scene): SpatialControllerPrimarySnapshot {
    val buttonABit = ButtonBits.ButtonA
    val buttonBBit = ButtonBits.ButtonB
    val rightTriggerBit = ButtonBits.ButtonTriggerR
    val leftThumbUpBit = ButtonBits.ButtonThumbLU
    val leftThumbDownBit = ButtonBits.ButtonThumbLD
    val rightThumbUpBit = ButtonBits.ButtonThumbRU
    val rightThumbDownBit = ButtonBits.ButtonThumbRD
    var componentCount = 0
    var controllerTypeCount = 0
    var allControllerChangedButtons = 0
    var allControllerButtonState = 0
    var localControllerCount = 0
    var localActiveControllerCount = 0
    var localRightControllerCount = 0
    var localRightControllerType = "none"
    var localRightControllerAttachmentType = "none"
    var localRightControllerActive = false
    var localRightControllerButtonState = 0
    var localRightControllerChangedButtons = 0
    var avatarBodyCount = 0
    var playerAvatarBodyCount = 0
    var playerAvatarBody: AvatarBody? = null
    val dataModel = scene.spatialInterface.dataModel
    Query.where { has(Controller.id) }
        .eval(dataModel)
        .forEach { entity ->
          val controller = entity.getComponent<Controller>()
          componentCount += 1
          val controllerType = controller.type == ControllerType.CONTROLLER
          if (controllerType) {
            controllerTypeCount += 1
            allControllerButtonState = allControllerButtonState or controller.buttonState
            allControllerChangedButtons = allControllerChangedButtons or controller.changedButtons
            val localController = runCatching { entity.isLocal() }.getOrDefault(false)
            if (localController) {
              localControllerCount += 1
              if (controller.isActive) {
                localActiveControllerCount += 1
              }
              val attachmentType = entity.tryGetComponent<AvatarAttachment>()?.type ?: "none"
              if (attachmentType == "right_controller") {
                localRightControllerCount += 1
                localRightControllerType = controller.type.name
                localRightControllerAttachmentType = attachmentType
                localRightControllerActive = localRightControllerActive || controller.isActive
                localRightControllerButtonState =
                    localRightControllerButtonState or controller.buttonState
                localRightControllerChangedButtons =
                    localRightControllerChangedButtons or controller.changedButtons
              }
            }
          }
        }
    Query.where { has(AvatarBody.id) }
        .eval(dataModel)
        .forEach { entity ->
          val avatarBody = entity.tryGetComponent<AvatarBody>() ?: return@forEach
          avatarBodyCount += 1
          if (entity.isLocal() && avatarBody.isPlayerControlled) {
            playerAvatarBodyCount += 1
            if (playerAvatarBody == null) {
              playerAvatarBody = avatarBody
            }
          }
        }
    val leftAvatarController = playerAvatarBody?.leftHand?.tryGetComponent<Controller>()
    val rightAvatarController = playerAvatarBody?.rightHand?.tryGetComponent<Controller>()
    val leftAvatarButtonState = leftAvatarController?.buttonState ?: 0
    val leftAvatarChangedButtons = leftAvatarController?.changedButtons ?: 0
    val rightAvatarButtonState = rightAvatarController?.buttonState ?: 0
    val rightAvatarChangedButtons = rightAvatarController?.changedButtons ?: 0
    val leftAvatarControllerUsable = leftAvatarController?.type == ControllerType.CONTROLLER
    val rightAvatarControllerUsable = rightAvatarController?.type == ControllerType.CONTROLLER
    val leftAvatarActive = leftAvatarController?.let { it.isActive } == true
    val rightAvatarActive = rightAvatarController?.let { it.isActive } == true
    val activeCount = (if (leftAvatarActive) 1 else 0) + (if (rightAvatarActive) 1 else 0)
    val leftInputButtonState = if (leftAvatarControllerUsable) leftAvatarButtonState else 0
    val leftInputChangedButtons =
        if (leftAvatarControllerUsable) leftAvatarChangedButtons else 0
    val rightInputButtonState =
        when {
          localRightControllerCount > 0 -> localRightControllerButtonState
          rightAvatarControllerUsable -> rightAvatarButtonState
          else -> allControllerButtonState
        }
    val rightInputChangedButtons =
        when {
          localRightControllerCount > 0 -> localRightControllerChangedButtons
          rightAvatarControllerUsable -> rightAvatarChangedButtons
          else -> allControllerChangedButtons
        }
    val rightInputSource =
        when {
          localRightControllerCount > 0 -> "spatial-sdk-controller-component"
          rightAvatarControllerUsable -> "spatial-sdk-avatar-body-controller"
          else -> "spatial-sdk-controller-component-fallback"
        }
    val buttonState = leftInputButtonState or rightInputButtonState
    val changedButtons = leftInputChangedButtons or rightInputChangedButtons
    val rightAvatarDown = (rightInputButtonState and buttonABit) != 0
    val rightAvatarPressed =
        rightAvatarDown && (rightInputChangedButtons and buttonABit) != 0
    val rightAvatarSecondaryDown = (rightInputButtonState and buttonBBit) != 0
    val rightAvatarSecondaryPressed =
        rightAvatarSecondaryDown && (rightInputChangedButtons and buttonBBit) != 0
    val rightTriggerDown = (rightInputButtonState and rightTriggerBit) != 0
    val rightTriggerPressed =
        rightTriggerDown && (rightInputChangedButtons and rightTriggerBit) != 0
    val leftAvatarThumbUp = (leftInputButtonState and leftThumbUpBit) != 0
    val leftAvatarThumbDown = (leftInputButtonState and leftThumbDownBit) != 0
    val leftAvatarThumbY =
        when {
          leftAvatarThumbUp && !leftAvatarThumbDown -> -1.0f
          leftAvatarThumbDown && !leftAvatarThumbUp -> 1.0f
          else -> 0.0f
        }
    val rightAvatarThumbUp = (rightInputButtonState and rightThumbUpBit) != 0
    val rightAvatarThumbDown = (rightInputButtonState and rightThumbDownBit) != 0
    val rightAvatarThumbY =
        when {
          rightAvatarThumbUp && !rightAvatarThumbDown -> -1.0f
          rightAvatarThumbDown && !rightAvatarThumbUp -> 1.0f
          else -> 0.0f
        }
    return SpatialControllerPrimarySnapshot(
        componentCount = componentCount,
        controllerTypeCount = controllerTypeCount,
        activeCount = activeCount,
        localControllerCount = localControllerCount,
        localActiveControllerCount = localActiveControllerCount,
        localRightControllerType = localRightControllerType,
        localRightControllerAttachmentType = localRightControllerAttachmentType,
        localRightControllerActive = localRightControllerActive,
        localRightControllerButtonState = localRightControllerButtonState,
        localRightControllerChangedButtons = localRightControllerChangedButtons,
        rightInputSource = rightInputSource,
        avatarBodyCount = avatarBodyCount,
        playerAvatarBodyCount = playerAvatarBodyCount,
        leftAvatarControllerType = leftAvatarController?.type?.name ?: "none",
        rightAvatarControllerType = rightAvatarController?.type?.name ?: "none",
        leftAvatarControllerActive = leftAvatarController?.isActive == true,
        rightAvatarControllerActive = rightAvatarController?.isActive == true,
        leftAvatarButtonState = leftAvatarButtonState,
        leftAvatarChangedButtons = leftAvatarChangedButtons,
        rightAvatarButtonState = rightAvatarButtonState,
        rightAvatarChangedButtons = rightAvatarChangedButtons,
        buttonState = buttonState,
        changedButtons = changedButtons,
        allControllerButtonState = allControllerButtonState,
        allControllerChangedButtons = allControllerChangedButtons,
        leftThumbUp = leftAvatarThumbUp,
        leftThumbDown = leftAvatarThumbDown,
        leftThumbY = leftAvatarThumbY,
        rightThumbUp = rightAvatarThumbUp,
        rightThumbDown = rightAvatarThumbDown,
        rightThumbY = rightAvatarThumbY,
        down = rightAvatarDown,
        pressed = rightAvatarPressed,
        secondaryDown = rightAvatarSecondaryDown,
        secondaryPressed = rightAvatarSecondaryPressed,
        triggerDown = rightTriggerDown,
        triggerPressed = rightTriggerPressed,
    )
  }
}
