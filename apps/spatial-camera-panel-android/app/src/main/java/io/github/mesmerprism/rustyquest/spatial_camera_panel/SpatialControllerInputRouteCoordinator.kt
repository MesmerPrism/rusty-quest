package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent

internal data class SpatialControllerInputRouteSpec(
    val enabled: Boolean,
    val source: String,
)

internal data class SpatialControllerInputRouteBindings(
    val routeSpec: () -> SpatialControllerInputRouteSpec,
    val enableSpatialInput: () -> Boolean,
    val gameControllerDeviceIds: () -> List<Int>,
    val pinGameController: (Int, (MotionEvent?, KeyEvent?) -> Unit) -> Unit,
    val dispatchKeyEvent: (KeyEvent) -> Boolean,
    val dispatchMotionButtonEvent: (MotionEvent) -> Boolean,
    val dispatchJoystickMotion: (MotionEvent, String) -> Boolean,
    val marker: (String) -> Unit,
)

internal class SpatialControllerInputRouteCoordinator(
    private val bindings: SpatialControllerInputRouteBindings,
) {
  private val pinnedGameControllerIds = mutableSetOf<Int>()
  private var lastRouteMarkerMs = 0L

  fun ensureEnabled(reason: String, forceLog: Boolean) {
    val routeSpec = bindings.routeSpec()
    if (!routeSpec.enabled || routeSpec.source.isBlank()) {
      return
    }

    val now = SystemClock.elapsedRealtime()
    val enableResult = runCatching(bindings.enableSpatialInput).getOrElse { false }
    var newlyPinned = 0
    val gameControllerIds = bindings.gameControllerDeviceIds()
    gameControllerIds.forEach { deviceId ->
      if (pinnedGameControllerIds.add(deviceId)) {
        bindings.pinGameController(deviceId) { motionEvent, keyEvent ->
          keyEvent?.let(bindings.dispatchKeyEvent)
          motionEvent?.let { event ->
            if (!bindings.dispatchMotionButtonEvent(event)) {
              bindings.dispatchJoystickMotion(event, PINNED_CONTROLLER_INPUT_SOURCE)
            }
          }
        }
        newlyPinned += 1
      }
    }

    if (
        forceLog ||
            newlyPinned > 0 ||
            now - lastRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
    ) {
      lastRouteMarkerMs = now
      bindings.marker(
          SpatialControllerRoutingModule.spatialInputEnabledMarker(
              reason = reason,
              spatialInterfaceEnableInput = enableResult,
              gameControllerDeviceCount = gameControllerIds.size,
              pinnedGameControllerCount = pinnedGameControllerIds.size,
              newlyPinnedGameControllerCount = newlyPinned,
          )
      )
    }
  }

  companion object {
    const val MODULE_ID = "spatial-controller-input-route-coordinator"
    private const val PINNED_CONTROLLER_INPUT_SOURCE = "pinned-android-game-controller"
  }
}
