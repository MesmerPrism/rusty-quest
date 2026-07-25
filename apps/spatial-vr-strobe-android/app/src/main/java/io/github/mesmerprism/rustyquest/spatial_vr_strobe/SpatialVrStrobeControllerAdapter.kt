package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType

internal class SpatialVrStrobeControllerPollingFeature(
    private val poll: () -> Unit,
) : SpatialFeature {
  override fun lateSystemsToRegister(): List<SystemBase> = listOf(PollingSystem(poll))

  private class PollingSystem(private val poll: () -> Unit) : SystemBase() {
    override fun execute() = poll()
  }
}

internal data class SpatialVrStrobeControllerSample(
    val axes: VrStrobeControllerAxes,
    val primaryDown: Boolean,
    val secondaryDown: Boolean,
    val storeDown: Boolean,
    val localRightControllerType: String,
    val localRightAttachmentType: String,
    val rightAvatarControllerType: String,
    val localLeftControllerType: String,
    val localLeftAttachmentType: String,
    val leftAvatarControllerType: String,
    val rightInputSource: String,
    val leftInputSource: String,
)

internal object SpatialVrStrobeControllerAdapter {
  fun capture(scene: Scene): SpatialVrStrobeControllerSample {
    var localLeftState = 0
    var localRightState = 0
    var fallbackState = 0
    var localLeftType = "none"
    var localRightType = "none"
    var localLeftAttachment = "none"
    var localRightAttachment = "none"
    var localLeftFound = false
    var localRightFound = false

    val dataModel = scene.spatialInterface.dataModel
    Query.where { has(Controller.id) }
        .eval(dataModel)
        .forEach { entity ->
          val controller = entity.tryGetComponent<Controller>() ?: return@forEach
          if (controller.type != ControllerType.CONTROLLER) return@forEach
          fallbackState = fallbackState or controller.buttonState
          if (!runCatching { entity.isLocal() }.getOrDefault(false)) return@forEach
          val attachment = entity.tryGetComponent<AvatarAttachment>()?.type ?: "none"
          when (attachment) {
            "left_controller" -> {
              localLeftFound = true
              localLeftState = localLeftState or controller.buttonState
              localLeftType = controller.type.name
              localLeftAttachment = attachment
            }
            "right_controller" -> {
              localRightFound = true
              localRightState = localRightState or controller.buttonState
              localRightType = controller.type.name
              localRightAttachment = attachment
            }
          }
        }

    var playerBody: AvatarBody? = null
    Query.where { has(AvatarBody.id) }
        .eval(dataModel)
        .forEach { entity ->
          val body = entity.tryGetComponent<AvatarBody>() ?: return@forEach
          if (playerBody == null && entity.isLocal() && body.isPlayerControlled) playerBody = body
        }
    val leftAvatar = playerBody?.leftHand?.tryGetComponent<Controller>()
    val rightAvatar = playerBody?.rightHand?.tryGetComponent<Controller>()
    val leftAvatarUsable = leftAvatar?.type == ControllerType.CONTROLLER
    val rightAvatarUsable = rightAvatar?.type == ControllerType.CONTROLLER

    val leftState =
        when {
          localLeftFound -> localLeftState
          leftAvatarUsable -> leftAvatar?.buttonState ?: 0
          else -> fallbackState
        }
    val rightState =
        when {
          localRightFound -> localRightState
          rightAvatarUsable -> rightAvatar?.buttonState ?: 0
          else -> fallbackState
        }

    return SpatialVrStrobeControllerSample(
        axes =
            VrStrobeControllerAxes(
                leftX = signedAxis(leftState, ButtonBits.ButtonThumbLL, ButtonBits.ButtonThumbLR),
                leftY = signedAxis(leftState, ButtonBits.ButtonThumbLU, ButtonBits.ButtonThumbLD),
                rightX = signedAxis(rightState, ButtonBits.ButtonThumbRL, ButtonBits.ButtonThumbRR),
                rightY = signedAxis(rightState, ButtonBits.ButtonThumbRU, ButtonBits.ButtonThumbRD),
            ),
        primaryDown = rightState and ButtonBits.ButtonA != 0,
        secondaryDown = rightState and ButtonBits.ButtonB != 0,
        storeDown = leftState and ButtonBits.ButtonX != 0,
        localRightControllerType = localRightType,
        localRightAttachmentType = localRightAttachment,
        rightAvatarControllerType = rightAvatar?.type?.name ?: "none",
        localLeftControllerType = localLeftType,
        localLeftAttachmentType = localLeftAttachment,
        leftAvatarControllerType = leftAvatar?.type?.name ?: "none",
        rightInputSource =
            if (localRightFound) "spatial-sdk-controller-component"
            else if (rightAvatarUsable) "spatial-sdk-avatar-body-controller"
            else "spatial-sdk-controller-component-fallback",
        leftInputSource =
            if (localLeftFound) "spatial-sdk-controller-component"
            else if (leftAvatarUsable) "spatial-sdk-avatar-body-controller"
            else "spatial-sdk-controller-component-fallback",
    )
  }

  private fun signedAxis(state: Int, negativeBit: Int, positiveBit: Int): Float {
    val negative = state and negativeBit != 0
    val positive = state and positiveBit != 0
    return when {
      negative && !positive -> -1.0f
      positive && !negative -> 1.0f
      else -> 0.0f
    }
  }
}
