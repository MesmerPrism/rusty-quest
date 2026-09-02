package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPrivatePanelLockedControllerCoordinatorTest {
  @Test
  fun forwardsOnlyHorizontalObservationAndRightPrimaryPressedEdgesWhenEnabled() {
    var enabled = true
    val horizontal = mutableListOf<Pair<Float, Float>>()
    var primaryCount = 0
    val coordinator =
        SpatialPrivatePanelLockedControllerCoordinator(
            SpatialPrivatePanelLockedControllerBindings(
                enabled = { enabled },
                applyHorizontalSelection = { x, y, _ ->
                  horizontal += x to y
                  true
                },
                dispatchPrimary = { _, _ -> primaryCount += 1; true },
            )
        )

    assertTrue(coordinator.handle(snapshot(rightX = -0.9f, rightY = 0.1f, down = true)))
    assertTrue(coordinator.handle(snapshot(rightX = 0.0f, rightY = 0.8f, down = true)))
    assertTrue(coordinator.handle(snapshot(rightX = 0.0f, rightY = 0.0f, down = false)))
    assertTrue(coordinator.handle(snapshot(rightX = 0.9f, rightY = 0.1f, down = true)))
    assertEquals(listOf(-0.9f to 0.1f, 0.0f to 0.8f, 0.0f to 0.0f, 0.9f to 0.1f), horizontal)
    assertEquals(2, primaryCount)

    enabled = false
    assertFalse(coordinator.handle(snapshot(rightX = -1.0f, rightY = 0.0f, down = false)))
    assertEquals(4, horizontal.size)
    assertEquals(2, primaryCount)
  }

  private fun snapshot(
      rightX: Float,
      rightY: Float,
      down: Boolean,
  ): SpatialControllerPrimarySnapshot =
      SpatialControllerPrimarySnapshot(
          componentCount = 1,
          controllerTypeCount = 1,
          activeCount = 1,
          localControllerCount = 1,
          localActiveControllerCount = 1,
          localLeftControllerType = "none",
          localLeftControllerAttachmentType = "none",
          localLeftControllerActive = false,
          localLeftControllerButtonState = 0,
          localLeftControllerChangedButtons = 0,
          localRightControllerType = "controller",
          localRightControllerAttachmentType = "right",
          localRightControllerActive = true,
          localRightControllerButtonState = if (down) 1 else 0,
          localRightControllerChangedButtons = 0,
          rightInputSource = "test-controller",
          leftInputSource = "none",
          avatarBodyCount = 0,
          playerAvatarBodyCount = 0,
          leftAvatarControllerType = "none",
          rightAvatarControllerType = "none",
          leftAvatarControllerActive = false,
          rightAvatarControllerActive = false,
          leftAvatarButtonState = 0,
          leftAvatarChangedButtons = 0,
          rightAvatarButtonState = 0,
          rightAvatarChangedButtons = 0,
          buttonState = if (down) 1 else 0,
          changedButtons = 0,
          allControllerButtonState = if (down) 1 else 0,
          allControllerChangedButtons = 0,
          leftThumbUp = false,
          leftThumbDown = false,
          leftThumbLeft = false,
          leftThumbRight = false,
          leftThumbX = 0.0f,
          leftThumbY = 0.0f,
          rightThumbUp = false,
          rightThumbDown = false,
          rightThumbLeft = rightX < 0.0f,
          rightThumbRight = rightX > 0.0f,
          rightThumbX = rightX,
          rightThumbY = rightY,
          down = down,
          pressed = false,
          secondaryDown = false,
          secondaryPressed = false,
          triggerDown = false,
          triggerPressed = false,
          leftPrimaryDown = false,
          leftPrimaryPressed = false,
      )
}
