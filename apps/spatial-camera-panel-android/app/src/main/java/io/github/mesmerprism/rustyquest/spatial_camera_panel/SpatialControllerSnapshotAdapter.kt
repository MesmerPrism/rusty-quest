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
    val buttonXBit = ButtonBits.ButtonX
    val rightTriggerBit = ButtonBits.ButtonTriggerR
    val leftThumbUpBit = ButtonBits.ButtonThumbLU
    val leftThumbDownBit = ButtonBits.ButtonThumbLD
    val leftThumbLeftBit = ButtonBits.ButtonThumbLL
    val leftThumbRightBit = ButtonBits.ButtonThumbLR
    val rightThumbUpBit = ButtonBits.ButtonThumbRU
    val rightThumbDownBit = ButtonBits.ButtonThumbRD
    val rightThumbLeftBit = ButtonBits.ButtonThumbRL
    val rightThumbRightBit = ButtonBits.ButtonThumbRR
    var componentCount = 0
    var controllerTypeCount = 0
    var allControllerChangedButtons = 0
    var allControllerButtonState = 0
    var localControllerCount = 0
    var localActiveControllerCount = 0
    var localLeftControllerCount = 0
    var localLeftControllerType = "none"
    var localLeftControllerAttachmentType = "none"
    var localLeftControllerActive = false
    var localLeftControllerButtonState = 0
    var localLeftControllerChangedButtons = 0
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
              if (attachmentType == "left_controller") {
                localLeftControllerCount += 1
                localLeftControllerType = controller.type.name
                localLeftControllerAttachmentType = attachmentType
                localLeftControllerActive = localLeftControllerActive || controller.isActive
                localLeftControllerButtonState =
                    localLeftControllerButtonState or controller.buttonState
                localLeftControllerChangedButtons =
                    localLeftControllerChangedButtons or controller.changedButtons
              }
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
    val leftInputButtonState =
        when {
          localLeftControllerCount > 0 -> localLeftControllerButtonState
          leftAvatarControllerUsable -> leftAvatarButtonState
          else -> allControllerButtonState
        }
    val leftInputChangedButtons =
        when {
          localLeftControllerCount > 0 -> localLeftControllerChangedButtons
          leftAvatarControllerUsable -> leftAvatarChangedButtons
          else -> allControllerChangedButtons
        }
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
    val leftInputSource =
        when {
          localLeftControllerCount > 0 -> "spatial-sdk-controller-component"
          leftAvatarControllerUsable -> "spatial-sdk-avatar-body-controller"
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
    val leftPrimaryDown = (leftInputButtonState and buttonXBit) != 0
    val leftPrimaryPressed =
        leftPrimaryDown && (leftInputChangedButtons and buttonXBit) != 0
    val leftAvatarThumbUp = (leftInputButtonState and leftThumbUpBit) != 0
    val leftAvatarThumbDown = (leftInputButtonState and leftThumbDownBit) != 0
    val leftAvatarThumbLeft = (leftInputButtonState and leftThumbLeftBit) != 0
    val leftAvatarThumbRight = (leftInputButtonState and leftThumbRightBit) != 0
    val leftAvatarThumbX =
        when {
          leftAvatarThumbLeft && !leftAvatarThumbRight -> -1.0f
          leftAvatarThumbRight && !leftAvatarThumbLeft -> 1.0f
          else -> 0.0f
        }
    val leftAvatarThumbY =
        when {
          leftAvatarThumbUp && !leftAvatarThumbDown -> -1.0f
          leftAvatarThumbDown && !leftAvatarThumbUp -> 1.0f
          else -> 0.0f
        }
    val rightAvatarThumbUp = (rightInputButtonState and rightThumbUpBit) != 0
    val rightAvatarThumbDown = (rightInputButtonState and rightThumbDownBit) != 0
    val rightAvatarThumbLeft = (rightInputButtonState and rightThumbLeftBit) != 0
    val rightAvatarThumbRight = (rightInputButtonState and rightThumbRightBit) != 0
    val rightAvatarThumbX =
        when {
          rightAvatarThumbLeft && !rightAvatarThumbRight -> -1.0f
          rightAvatarThumbRight && !rightAvatarThumbLeft -> 1.0f
          else -> 0.0f
        }
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
        localLeftControllerType = localLeftControllerType,
        localLeftControllerAttachmentType = localLeftControllerAttachmentType,
        localLeftControllerActive = localLeftControllerActive,
        localLeftControllerButtonState = localLeftControllerButtonState,
        localLeftControllerChangedButtons = localLeftControllerChangedButtons,
        localRightControllerType = localRightControllerType,
        localRightControllerAttachmentType = localRightControllerAttachmentType,
        localRightControllerActive = localRightControllerActive,
        localRightControllerButtonState = localRightControllerButtonState,
        localRightControllerChangedButtons = localRightControllerChangedButtons,
        rightInputSource = rightInputSource,
        leftInputSource = leftInputSource,
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
        leftThumbLeft = leftAvatarThumbLeft,
        leftThumbRight = leftAvatarThumbRight,
        leftThumbX = leftAvatarThumbX,
        leftThumbY = leftAvatarThumbY,
        rightThumbUp = rightAvatarThumbUp,
        rightThumbDown = rightAvatarThumbDown,
        rightThumbLeft = rightAvatarThumbLeft,
        rightThumbRight = rightAvatarThumbRight,
        rightThumbX = rightAvatarThumbX,
        rightThumbY = rightAvatarThumbY,
        down = rightAvatarDown,
        pressed = rightAvatarPressed,
        secondaryDown = rightAvatarSecondaryDown,
        secondaryPressed = rightAvatarSecondaryPressed,
        triggerDown = rightTriggerDown,
        triggerPressed = rightTriggerPressed,
        leftPrimaryDown = leftPrimaryDown,
        leftPrimaryPressed = leftPrimaryPressed,
    )
  }
}
