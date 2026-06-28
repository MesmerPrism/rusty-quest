package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.toolkit.AvatarSystem

internal class SpatialAvatarHandVisualFeature(
    private val marker: (String) -> Unit,
) : SpatialFeature {
  override fun earlySystemsToRegister(): List<SystemBase> =
      listOf(SpatialAvatarHandVisualSuppressionSystem(marker))
}

private class SpatialAvatarHandVisualSuppressionSystem(
    private val marker: (String) -> Unit,
) : SystemBase() {
  private var pendingLogged = false
  private var disabledLogged = false

  override fun execute() {
    val avatarSystem =
        runCatching { systemManager.tryFindSystem(AvatarSystem::class) }.getOrNull()
    if (avatarSystem == null) {
      if (!pendingLogged) {
        pendingLogged = true
        marker(
            "channel=spatial-sdk-avatar-visual status=disable-pending " +
                "system=AvatarSystem systemFound=false suppressionSystem=SpatialAvatarHandVisualSuppressionSystem " +
                "builtInMetaHandVisualPolicy=pending nativeBaseHandMeshPolicy=explicit-only"
        )
      }
      return
    }

    avatarSystem.setShowHands(false)
    if (!disabledLogged) {
      disabledLogged = true
      marker(
          "channel=spatial-sdk-avatar-visual status=disabled " +
              "system=AvatarSystem systemFound=true suppressionSystem=SpatialAvatarHandVisualSuppressionSystem " +
              "showHands=false builtInMetaHandVisualPolicy=disabled " +
              "nativeBaseHandMeshPolicy=explicit-only"
      )
    }
  }
}
