package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.os.Bundle
import com.meta.spatial.core.AbstractSpatialFeature
import com.meta.spatial.core.ComponentRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SystemBase
import com.meta.spatial.isdk.ExternalControllerInputHandler
import com.meta.spatial.isdk.IsdkSystems

/**
 * Meta Interaction SDK input without VRFeature's unconditional LocomotionSystem.
 *
 * This retains pointing, panel interaction, and the grabbable/followable systems needed for grip
 * placement. It deliberately has no locomotion owner, so thumbsticks cannot create a teleport arc
 * or move the viewer. Thumbstick values remain observable by the app's explicit controller router.
 */
internal class SpatialInteractionInputOnlyFeature(
    context: Context,
    private val emitMarker: (String) -> Unit,
) : AbstractSpatialFeature() {
  private val isdkSystems = IsdkSystems(context)
  private val noLocomotionBridge =
      object : ExternalControllerInputHandler {
        override fun areControllersInUse(): Boolean = false

        override fun setControllerInputResult(entity: Entity, inputConsumed: Boolean) = Unit
      }

  override fun earlySystemsToRegister(): List<SystemBase> =
      isdkSystems.earlySystemsToRegister(noLocomotionBridge)

  override fun systemsToRegister(): List<SystemBase> = isdkSystems.systemsToRegister()

  override fun lateSystemsToRegister(): List<SystemBase> = emptyList()

  override fun componentsToRegister(): List<ComponentRegistration> =
      isdkSystems.componentsToRegister()

  override fun preRuntimeOnCreate(savedInstanceState: Bundle?) {
    loadLibrary("openxr_loader")
    loadLibrary("MetaSpatialSDKIsdk")
    emitMarker(
        "channel=spatial-input status=input-only-feature-created " +
            "interactionSdk=true locomotionSystemRegistered=false " +
            "teleportLocomotionEnabled=false joystickLocomotionEnabled=false " +
            "gripPanelGrabEnabled=true"
    )
  }
}
