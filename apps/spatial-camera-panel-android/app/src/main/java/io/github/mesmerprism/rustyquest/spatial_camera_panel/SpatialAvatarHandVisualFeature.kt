package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.toolkit.AvatarSystem

internal class SpatialAvatarHandVisualFeature(
    private val marker: (String) -> Unit,
) : SpatialFeature {
  override fun earlySystemsToRegister(): List<SystemBase> =
      listOf(SpatialAvatarHandVisualPolicySystem(marker))
}

internal class SpatialControllerInputLateFeature(
    private val pollControllerInput: () -> Unit,
) : SpatialFeature {
  override fun lateSystemsToRegister(): List<SystemBase> =
      listOf(SpatialControllerInputLateSystem(pollControllerInput))
}

private class SpatialControllerInputLateSystem(
    private val pollControllerInput: () -> Unit,
) : SystemBase() {
  override fun execute() {
    pollControllerInput()
  }
}

internal const val AVATAR_HANDS_VISIBLE_PROPERTY = "debug.rustyquest.spatial.avatar_hands.visible"
internal const val AVATAR_CONTROLLERS_VISIBLE_PROPERTY =
    "debug.rustyquest.spatial.avatar_controllers.visible"

private val androidSystemPropertyGetMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
  runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
      }
      .getOrNull()
}

private class SpatialAvatarHandVisualPolicySystem(
    private val marker: (String) -> Unit,
) : SystemBase() {
  private var pendingLogged = false
  private var lastLoggedShowHands: Boolean? = null
  private var lastLoggedShowControllers: Boolean? = null

  override fun execute() {
    val showHands = readBooleanSystemProperty(AVATAR_HANDS_VISIBLE_PROPERTY) ?: false
    val showControllers = readBooleanSystemProperty(AVATAR_CONTROLLERS_VISIBLE_PROPERTY) ?: false
    val avatarSystem =
        runCatching { systemManager.tryFindSystem(AvatarSystem::class) }.getOrNull()
    if (avatarSystem == null) {
      if (!pendingLogged) {
        pendingLogged = true
        marker(
            "channel=spatial-sdk-avatar-visual status=policy-pending " +
                "system=AvatarSystem systemFound=false policySystem=SpatialAvatarHandVisualPolicySystem " +
                "avatarHandsVisibleProperty=$AVATAR_HANDS_VISIBLE_PROPERTY " +
                "avatarHandsVisibleDefault=false requestedShowHands=$showHands " +
                "avatarControllersVisibleProperty=$AVATAR_CONTROLLERS_VISIBLE_PROPERTY " +
                "avatarControllersVisibleDefault=false requestedShowControllers=$showControllers " +
                "builtInMetaHandVisualPolicy=pending builtInMetaHandMaterialPolicy=sdk-owned-no-public-material-surface " +
                "nativeBaseHandMeshPolicy=explicit-only"
        )
      }
      return
    }

    avatarSystem.setShowHands(showHands)
    avatarSystem.setShowControllers(showControllers)
    if (lastLoggedShowHands != showHands || lastLoggedShowControllers != showControllers) {
      lastLoggedShowHands = showHands
      lastLoggedShowControllers = showControllers
      val handPolicy = if (showHands) "enabled" else "disabled"
      val controllerPolicy = if (showControllers) "enabled" else "disabled"
      marker(
          "channel=spatial-sdk-avatar-visual status=policy-applied " +
              "system=AvatarSystem systemFound=true policySystem=SpatialAvatarHandVisualPolicySystem " +
              "showHands=$showHands showControllers=$showControllers " +
              "builtInMetaHandVisualPolicy=$handPolicy builtInMetaControllerVisualPolicy=$controllerPolicy " +
              "avatarHandsVisibleProperty=$AVATAR_HANDS_VISIBLE_PROPERTY " +
              "avatarControllersVisibleProperty=$AVATAR_CONTROLLERS_VISIBLE_PROPERTY " +
              "builtInMetaHandMaterialPolicy=sdk-owned-no-public-material-surface " +
              "nativeBaseHandMeshPolicy=explicit-only"
      )
    }
  }

  private fun readBooleanSystemProperty(name: String): Boolean? {
    val raw =
        runCatching { androidSystemPropertyGetMethod?.invoke(null, name, "") as? String }
            .getOrNull()
            ?.trim()
            ?.lowercase()
    return when (raw) {
      "1", "true", "yes", "on" -> true
      "0", "false", "no", "off" -> false
      else -> null
    }
  }
}
