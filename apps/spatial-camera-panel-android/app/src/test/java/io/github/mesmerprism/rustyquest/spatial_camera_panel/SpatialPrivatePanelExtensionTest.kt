package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.runtime.Composable
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPrivatePanelExtensionTest {
  @Test
  fun publicBuildTreatsAbsentPrivateConnectionHubTargetAsUnavailable() {
    val markers = mutableListOf<String>()

    val target = SpatialConnectionHubSurfaceTargetLoader.load(markers::add)

    assertNull(target)
    assertTrue(markers.single().contains("status=not-present"))
  }

  @Test
  fun targetIsDerivedFromTheAlreadyLoadedExtensionInstance() {
    val expected =
        object : ConnectionHubSurfaceTarget {
          override fun hubSurfaceAvailable(): Boolean = false

          override fun hubSurfaceState(): JSONObject = JSONObject()

          override fun applyHubAuthorizedCommand(
              requestId: String,
              surfaceId: String,
              command: String,
              args: JSONObject,
              authorityReceipt: JSONObject,
          ): Long = error("not available")
        }
    val extension =
        object : SpatialPrivatePanelExtension {
          override val pageTitle = "Optional"
          override val pageSubtitle = "Optional private feature"
          override fun homeSummary(): String = "Optional"
          override fun inputLocked(): Boolean = false
          override fun connectionHubSurfaceTarget(): ConnectionHubSurfaceTarget = expected
          override fun handleLaunchOption(
              optionPresent: Boolean,
              optionId: String?,
              source: String,
          ): SpatialPrivatePanelLaunchResult =
              SpatialPrivatePanelLaunchResult("normal-launch", inputLocked = false)
          override fun tick(sceneReady: Boolean) = Unit
          override fun referencesProfile(profileId: String): Boolean = false
          override fun shutdown() = Unit

          @Composable
          override fun PanelContent(
              profileLibrary: () -> SpatialCameraPanelProfileLibrarySnapshot,
              onControlsApplied: (SpatialCameraPanelControlSnapshot) -> Unit,
          ) = Unit
        }
    val markers = mutableListOf<String>()

    val actual = SpatialConnectionHubSurfaceTargetLoader.load(markers::add, extension)

    assertSame(expected, actual)
    assertTrue(markers.single().contains("status=loaded"))
  }
}
