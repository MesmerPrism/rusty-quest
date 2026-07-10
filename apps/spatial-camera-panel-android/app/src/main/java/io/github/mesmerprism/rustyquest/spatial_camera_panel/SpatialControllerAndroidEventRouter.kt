package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.KeyEvent
import android.view.MotionEvent

internal class SpatialControllerAndroidEventRouter(
    private val armSecondaryToggle: (String) -> Unit,
    private val toggleSecondary: (String, String) -> Boolean,
    private val recenterTrigger: (String, String) -> Boolean,
    private val openPrimary: (String, String) -> Boolean,
) {
  private var primaryKeyDown = false
  private var primaryMotionDown = false
  private var secondaryKeyDown = false
  private var secondaryMotionDown = false
  private var rightTriggerKeyDown = false
  private var rightTriggerMotionDown = false

  fun dispatchKeyEvent(event: KeyEvent): Boolean =
      handleSecondary(event) || handleTrigger(event) || handlePrimary(event)

  fun dispatchMotionButtonEvent(event: MotionEvent): Boolean =
      handleSecondary(event) || handleTrigger(event) || handlePrimary(event)

  private fun handleSecondary(event: KeyEvent): Boolean {
    val rightSecondary =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_2
    if (!rightSecondary) {
      return false
    }
    val pressedEdge =
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            val firstDown = !secondaryKeyDown && event.repeatCount == 0
            secondaryKeyDown = true
            firstDown
          }
          KeyEvent.ACTION_UP -> {
            secondaryKeyDown = false
            armSecondaryToggle("android-key-event")
            false
          }
          else -> false
        }
    if (!pressedEdge) {
      return false
    }
    return toggleSecondary(
        "android-key-event",
        "keyCode=${event.keyCode} keyAction=${event.action} repeatCount=${event.repeatCount}",
    )
  }

  private fun handleSecondary(event: MotionEvent): Boolean {
    if (!SpatialControllerRoutingModule.isJoystickEvent(event)) {
      return false
    }
    val action = event.actionMasked
    if (!isButtonAction(action)) {
      return false
    }
    val secondaryDown = (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
    val pressedEdge = secondaryDown && !secondaryMotionDown
    secondaryMotionDown = secondaryDown
    if (!secondaryDown) {
      armSecondaryToggle("android-generic-motion-button")
    }
    if (!pressedEdge) {
      return false
    }
    return toggleSecondary(
        "android-generic-motion-button",
        "motionAction=$action motionButtonState=${event.buttonState} " +
            "motionButtonBit=${MotionEvent.BUTTON_SECONDARY}",
    )
  }

  private fun handleTrigger(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_BUTTON_R2) {
      return false
    }
    val pressedEdge =
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            val firstDown = !rightTriggerKeyDown && event.repeatCount == 0
            rightTriggerKeyDown = true
            firstDown
          }
          KeyEvent.ACTION_UP -> {
            rightTriggerKeyDown = false
            false
          }
          else -> false
        }
    if (!pressedEdge) {
      return false
    }
    return recenterTrigger(
        "android-key-event",
        "keyCode=${event.keyCode} keyAction=${event.action} repeatCount=${event.repeatCount}",
    )
  }

  private fun handleTrigger(event: MotionEvent): Boolean {
    if (!SpatialControllerRoutingModule.isJoystickEvent(event)) {
      return false
    }
    val action = event.actionMasked
    if (!isButtonAction(action)) {
      return false
    }
    val rightTriggerValue =
        maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
    val triggerDown = rightTriggerValue >= CONTROLLER_TRIGGER_PRESS_THRESHOLD
    val pressedEdge = triggerDown && !rightTriggerMotionDown
    rightTriggerMotionDown = triggerDown
    if (!pressedEdge) {
      return false
    }
    return recenterTrigger(
        "android-generic-motion-trigger",
        "motionAction=$action motionButtonState=${event.buttonState} " +
            "rightTriggerAxis=${activityMarkerFloat(rightTriggerValue)} " +
            "rightTriggerThreshold=${activityMarkerFloat(CONTROLLER_TRIGGER_PRESS_THRESHOLD)}",
    )
  }

  private fun handlePrimary(event: KeyEvent): Boolean {
    val rightPrimary =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_1
    if (!rightPrimary) {
      return false
    }
    val pressedEdge =
        when (event.action) {
          KeyEvent.ACTION_DOWN -> {
            val firstDown = !primaryKeyDown && event.repeatCount == 0
            primaryKeyDown = true
            firstDown
          }
          KeyEvent.ACTION_UP -> {
            val releaseWithoutSeenDown = !primaryKeyDown
            primaryKeyDown = false
            releaseWithoutSeenDown
          }
          else -> false
        }
    if (!pressedEdge) {
      return false
    }
    return openPrimary(
        "android-key-event",
        "keyCode=${event.keyCode} keyAction=${event.action} repeatCount=${event.repeatCount}",
    )
  }

  private fun handlePrimary(event: MotionEvent): Boolean {
    if (!SpatialControllerRoutingModule.isJoystickEvent(event)) {
      return false
    }
    val action = event.actionMasked
    if (!isButtonAction(action)) {
      return false
    }
    val primaryDown = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
    val pressedEdge = primaryDown && !primaryMotionDown
    primaryMotionDown = primaryDown
    if (!pressedEdge) {
      return false
    }
    return openPrimary(
        "android-generic-motion-button",
        "motionAction=$action motionButtonState=${event.buttonState} " +
            "motionButtonBit=${MotionEvent.BUTTON_PRIMARY}",
    )
  }

  private fun isButtonAction(action: Int): Boolean =
      action == MotionEvent.ACTION_BUTTON_PRESS ||
          action == MotionEvent.ACTION_BUTTON_RELEASE ||
          action == MotionEvent.ACTION_MOVE

  companion object {
    const val MODULE_ID = "spatial-controller-android-event-router"
  }
}
