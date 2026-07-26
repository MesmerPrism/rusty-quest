package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialImmersiveVideoSelectionInputCoordinatorTest {
  @Test
  fun rightAndLeftFlicksSelectOnceAndRequireNeutralRearm() {
    val selections = mutableListOf<SpatialImmersiveVideoSelectionDirection>()
    val coordinator = coordinator(selections)

    assertTrue(coordinator.handleRightStick(0.9f, 0.1f, "test"))
    assertEquals(listOf(SpatialImmersiveVideoSelectionDirection.Next), selections)
    assertTrue(coordinator.handleRightStick(1.0f, 0.0f, "test"))
    assertEquals(1, selections.size)

    assertFalse(coordinator.handleRightStick(0.0f, 0.0f, "test"))
    assertTrue(coordinator.handleRightStick(-0.85f, 0.0f, "test"))
    assertEquals(
        listOf(
            SpatialImmersiveVideoSelectionDirection.Next,
            SpatialImmersiveVideoSelectionDirection.Previous,
        ),
        selections,
    )
  }

  @Test
  fun verticalDominantMotionDoesNotSelectVideo() {
    val selections = mutableListOf<SpatialImmersiveVideoSelectionDirection>()
    val coordinator = coordinator(selections)

    assertFalse(coordinator.handleRightStick(0.8f, 0.95f, "test"))
    assertTrue(selections.isEmpty())
  }

  @Test
  fun disabledSelectionDoesNotConsumeHorizontalStick() {
    val selections = mutableListOf<SpatialImmersiveVideoSelectionDirection>()
    val coordinator =
        SpatialImmersiveVideoSelectionInputCoordinator(
            SpatialImmersiveVideoSelectionInputBindings(
                selectionEnabled = { false },
                select = { direction, _ -> selections += direction },
                marker = {},
            )
        )

    assertFalse(coordinator.handleRightStick(1.0f, 0.0f, "test"))
    assertTrue(selections.isEmpty())
  }

  private fun coordinator(
      selections: MutableList<SpatialImmersiveVideoSelectionDirection>
  ): SpatialImmersiveVideoSelectionInputCoordinator =
      SpatialImmersiveVideoSelectionInputCoordinator(
          SpatialImmersiveVideoSelectionInputBindings(
              selectionEnabled = { true },
              select = { direction, _ -> selections += direction },
              marker = {},
          )
      )
}
