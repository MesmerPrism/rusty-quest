package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialProjectionPanelVisibilityCoordinatorTest {
  @Test
  fun explicitEnableStartsCameraProjectionWhenProductDefaultWasOff() {
    val restarts = mutableListOf<Pair<SpatialVideoProjectionSettings, String>>()
    val markers = mutableListOf<String>()
    val coordinator =
        SpatialProjectionPanelVisibilityCoordinator(
            bindings = bindings(restarts = restarts, markers = markers),
            initiallyEnabled = false,
        )

    assertTrue(coordinator.setEnabled(true, "wearer-toggle"))
    assertEquals(1, restarts.size)
    assertFalse(restarts.single().first.active)
    assertEquals("projection-panel-toggle-on", restarts.single().second)
    assertTrue(markers.single().contains("projectionRestartRequested=true"))
    assertTrue(markers.single().contains("videoRestartRequested=false"))
  }

  @Test
  fun disableThenEnableRestartsPriorVideoSelection() {
    var launchStarted = true
    val selectedVideo =
        SpatialVideoProjectionSettings.disabled().copy(
            enabled = true,
            source = "file",
            path = "/test/video.mp4",
        )
    assertTrue(selectedVideo.active)
    val restarts = mutableListOf<Pair<SpatialVideoProjectionSettings, String>>()
    val coordinator =
        SpatialProjectionPanelVisibilityCoordinator(
            bindings =
                bindings(
                    projectionLaunchStarted = { launchStarted },
                    currentVideoSettings = { selectedVideo },
                    restarts = restarts,
                ),
            initiallyEnabled = true,
        )

    assertFalse(coordinator.setEnabled(false, "wearer-toggle"))
    launchStarted = false
    assertTrue(coordinator.setEnabled(true, "wearer-toggle"))
    assertEquals(selectedVideo, restarts.single().first)
  }

  private fun bindings(
      projectionLaunchStarted: () -> Boolean = { false },
      currentVideoSettings: () -> SpatialVideoProjectionSettings = {
        SpatialVideoProjectionSettings.disabled()
      },
      restarts: MutableList<Pair<SpatialVideoProjectionSettings, String>>,
      markers: MutableList<String> = mutableListOf(),
  ) =
      SpatialProjectionPanelVisibilityBindings(
          projectionLaunchStarted = projectionLaunchStarted,
          currentVideoSettings = currentVideoSettings,
          markProjectionLaunchStopped = {},
          stopProjectionPanel = {
            SpatialProjectionPanelStopReceipt(
                nativeProjectionStopped = true,
                videoProjectionStopped = true,
                carrierCleanupStatus = "stopped",
            )
          },
          directImmersiveVideoActive = { false },
          enableSystemPassthrough = { false },
          restartProjectionPanel = { settings, reason -> restarts += settings to reason },
          marker = markers::add,
      )
}
