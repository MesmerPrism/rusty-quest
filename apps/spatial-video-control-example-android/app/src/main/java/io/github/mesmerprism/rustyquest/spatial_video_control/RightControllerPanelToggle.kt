package io.github.mesmerprism.rustyquest.spatial_video_control

import com.meta.spatial.core.Query
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType

internal data class RightControllerPrimaryState(
    val source: String,
    val down: Boolean,
)

/**
 * Collapses the known Spatial SDK and Android observations of the right
 * controller A button into one physical-edge authority. Each route owns only
 * its local down/up observation; this arbiter alone decides whether the app
 * performs a panel toggle.
 */
internal class RightControllerPanelToggleArbiter(
    private val elapsedRealtimeMs: () -> Long,
    private val onAcceptedPress: (String) -> Unit,
) {
  private val routeDown = mutableMapOf<String, Boolean>()
  private var lastAcceptedPressMs: Long? = null

  fun observe(source: String, down: Boolean): Boolean {
    val wasDown = routeDown[source] == true
    routeDown[source] = down
    if (!down || wasDown) return false

    val now = elapsedRealtimeMs()
    val previous = lastAcceptedPressMs
    if (previous != null && now - previous < CROSS_ROUTE_DEDUPLICATION_MS) {
      return true
    }
    lastAcceptedPressMs = now
    onAcceptedPress(source)
    return true
  }

  fun release(source: String) {
    routeDown[source] = false
  }

  private companion object {
    const val CROSS_ROUTE_DEDUPLICATION_MS = 350L
  }
}

/** Read-only projection of the local right Touch controller's A state. */
internal object RightControllerPrimaryStateReader {
  fun read(scene: Scene): RightControllerPrimaryState? {
    val dataModel = scene.spatialInterface.dataModel
    var attachedRightFound = false
    var attachedRightDown = false

    Query.where { has(Controller.id) }
        .eval(dataModel)
        .forEach { entity ->
          val controller = entity.tryGetComponent<Controller>() ?: return@forEach
          val attachment = entity.tryGetComponent<AvatarAttachment>()?.type
          if (
              entity.isLocal() &&
                  controller.type == ControllerType.CONTROLLER &&
                  attachment == "right_controller"
          ) {
            attachedRightFound = true
            attachedRightDown =
                attachedRightDown || (controller.buttonState and ButtonBits.ButtonA) != 0
          }
        }

    if (attachedRightFound) {
      return RightControllerPrimaryState(
          source = "spatial-sdk-right-controller-component",
          down = attachedRightDown,
      )
    }

    var avatarRightFound = false
    var avatarRightDown = false
    Query.where { has(AvatarBody.id) }
        .eval(dataModel)
        .forEach { entity ->
          val body = entity.tryGetComponent<AvatarBody>() ?: return@forEach
          if (!entity.isLocal() || !body.isPlayerControlled) return@forEach
          val controller = body.rightHand.tryGetComponent<Controller>() ?: return@forEach
          if (controller.type != ControllerType.CONTROLLER) return@forEach
          avatarRightFound = true
          avatarRightDown =
              avatarRightDown || (controller.buttonState and ButtonBits.ButtonA) != 0
        }

    return if (avatarRightFound) {
      RightControllerPrimaryState(
          source = "spatial-sdk-right-avatar-controller",
          down = avatarRightDown,
      )
    } else {
      null
    }
  }
}
