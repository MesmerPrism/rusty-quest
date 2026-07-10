package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal class SpatialPanelInteractionStateCoordinator {
  private var headlockMarkerCount = 0
  private var lastHeadlockMarkerMs = 0L
  private var lastHeadlockHotloadToken = ""
  private var lastJoystickInputMs = 0L
  private var lastJoystickMarkerMs = 0L
  private var lastPrivateLayerGrabbableState: Boolean? = null
  private var lastPrivateLayerGrabbableMarkerMs = 0L

  fun shouldEmitHeadlockPoseMarker(
      nowMs: Long,
      forceLog: Boolean,
      anyPanelVisible: Boolean,
  ): Boolean {
    val shouldEmit =
        forceLog ||
            (anyPanelVisible &&
                headlockMarkerCount < MAX_AUTOMATIC_HEADLOCK_MARKERS &&
                nowMs - lastHeadlockMarkerMs >= PANEL_HEADLOCK_MARKER_INTERVAL_MS)
    if (!shouldEmit) return false
    headlockMarkerCount += 1
    lastHeadlockMarkerMs = nowMs
    return true
  }

  fun consumeHeadlockHotloadToken(token: String): Boolean {
    if (token == lastHeadlockHotloadToken) return false
    lastHeadlockHotloadToken = token
    return true
  }

  fun joystickDeltaSeconds(nowMs: Long): Float {
    val deltaSeconds =
        if (lastJoystickInputMs <= 0L) {
          DEFAULT_JOYSTICK_DELTA_SECONDS
        } else {
          ((nowMs - lastJoystickInputMs).toFloat() / 1000.0f)
              .coerceIn(0.0f, MAX_JOYSTICK_DELTA_SECONDS)
        }
    lastJoystickInputMs = nowMs
    return deltaSeconds
  }

  fun shouldEmitJoystickMarker(nowMs: Long): Boolean {
    if (nowMs - lastJoystickMarkerMs < PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS) {
      return false
    }
    lastJoystickMarkerMs = nowMs
    return true
  }

  fun shouldEmitPrivateLayerGrabbableMarker(
      grabbed: Boolean,
      nowMs: Long,
      forceLog: Boolean,
  ): Boolean {
    val shouldEmit =
        forceLog ||
            lastPrivateLayerGrabbableState != grabbed ||
            nowMs - lastPrivateLayerGrabbableMarkerMs >=
                PRIVATE_LAYER_PANEL_GRABBABLE_MARKER_INTERVAL_MS
    if (!shouldEmit) return false
    lastPrivateLayerGrabbableState = grabbed
    lastPrivateLayerGrabbableMarkerMs = nowMs
    return true
  }

  companion object {
    const val MODULE_ID = "spatial-panel-interaction-state-coordinator"

    private const val MAX_AUTOMATIC_HEADLOCK_MARKERS = 4
    private const val DEFAULT_JOYSTICK_DELTA_SECONDS = 1.0f / 60.0f
    private const val MAX_JOYSTICK_DELTA_SECONDS = 0.08f
  }
}
