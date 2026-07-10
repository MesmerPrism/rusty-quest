package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.SystemClock
import kotlin.math.abs

internal data class SpatialNativeControllerPollingState(
    val featureEnabled: Boolean,
    val receiptLibraryLoaded: Boolean,
    val actionsStarted: Boolean,
    val actionStartMask: Long,
)

internal data class SpatialControllerPollingBindings(
    val nativeState: () -> SpatialNativeControllerPollingState,
    val disableNativeActions: () -> Unit,
    val pollNativeLeftThumbstickY: () -> Float,
    val pollNativeRightThumbstickY: () -> Float,
    val pollNativeRightButtonB: () -> Boolean,
    val captureSpatialSnapshot: () -> SpatialControllerPrimarySnapshot,
    val currentLeftStickPanelDistanceMapping: () -> String,
    val currentLeftStickPanelDistanceEnabled: () -> Boolean,
    val currentSpatialVrInputSystemToken: () -> String,
    val applyProjectionScale: (Float, String, String, String) -> Unit,
    val applyPanelDistance: (Float, String, String, String) -> Unit,
    val recenterParticleSphere: (String, String) -> Boolean,
    val armSecondaryToggle: (String) -> Unit,
    val toggleSecondary: (String, String) -> Unit,
    val openPrimary: (String, String) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialControllerPollingCoordinator(
    private val bindings: SpatialControllerPollingBindings,
) {
  private var spatialPrimaryDown = false
  private var spatialRouteLogged = false
  private var lastSpatialRouteMarkerMs = 0L
  private var lastSpatialComponentCount = -1
  private var lastSpatialActiveCount = -1
  private var lastSpatialControllerTypeCount = -1
  private var lastSpatialAllButtonState = -1
  private var spatialSecondaryDown = false
  private var spatialRightTriggerDown = false
  private var nativeSecondaryDown = false

  fun pollNativeInput() {
    val state = bindings.nativeState()
    if (!state.featureEnabled || !state.receiptLibraryLoaded || !state.actionsStarted) {
      return
    }

    val leftY =
        runCatching(bindings.pollNativeLeftThumbstickY)
            .getOrElse { throwable ->
              bindings.disableNativeActions()
              bindings.marker(
                  SpatialControllerRoutingModule.nativeControllerActionPollErrorMarker(
                      controllerInput = "left-thumbstick-y",
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              Float.NaN
            }
    if (leftY.isFinite() && abs(leftY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      bindings.applyPanelDistance(
          leftY,
          "native-openxr-action",
          bindings.currentLeftStickPanelDistanceMapping(),
          "leftThumbstickY=${activityMarkerFloat(leftY)} " +
              "nativeControllerActionStartMask=${state.actionStartMask}",
      )
    }

    val rightY =
        runCatching(bindings.pollNativeRightThumbstickY)
            .getOrElse { throwable ->
              bindings.disableNativeActions()
              bindings.marker(
                  SpatialControllerRoutingModule.nativeControllerActionPollErrorMarker(
                      controllerInput = "right-thumbstick-y",
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              Float.NaN
            }
    if (rightY.isFinite() && abs(rightY) >= PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      bindings.applyProjectionScale(
          rightY,
          "native-openxr-action",
          "right-thumbstick-y-projection-target-scale",
          "rightThumbstickY=${activityMarkerFloat(rightY)} " +
              "nativeControllerActionStartMask=${state.actionStartMask}",
      )
    }

    val rightButtonBDown =
        runCatching(bindings.pollNativeRightButtonB)
            .getOrElse { throwable ->
              bindings.disableNativeActions()
              nativeSecondaryDown = false
              bindings.marker(
                  SpatialControllerRoutingModule.nativeControllerActionPollErrorMarker(
                      controllerInput = "right-button-b",
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              false
            }
    val rightButtonBPressedEdge = rightButtonBDown && !nativeSecondaryDown
    nativeSecondaryDown = rightButtonBDown
    if (!rightButtonBDown) {
      bindings.armSecondaryToggle("native-openxr-action")
    }
    if (rightButtonBPressedEdge) {
      bindings.toggleSecondary(
          "native-openxr-action",
          "rightButtonBDown=true nativeRightButtonBAction=true " +
              "nativeControllerActionStartMask=${state.actionStartMask}",
      )
    }
  }

  fun pollSpatialInput() {
    val now = SystemClock.elapsedRealtime()
    val snapshot =
        runCatching(bindings.captureSpatialSnapshot)
            .getOrElse { throwable ->
              spatialPrimaryDown = false
              spatialSecondaryDown = false
              spatialRightTriggerDown = false
              if (
                  !spatialRouteLogged ||
                      now - lastSpatialRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
              ) {
                spatialRouteLogged = true
                lastSpatialRouteMarkerMs = now
                bindings.marker(
                    SpatialControllerRoutingModule.controllerInputRouteErrorMarker(
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
              return
            }

    val shouldLogRoute =
        !spatialRouteLogged ||
            snapshot.componentCount != lastSpatialComponentCount ||
            snapshot.activeCount != lastSpatialActiveCount ||
            snapshot.controllerTypeCount != lastSpatialControllerTypeCount ||
            snapshot.allControllerButtonState != lastSpatialAllButtonState ||
            now - lastSpatialRouteMarkerMs >= SPATIAL_CONTROLLER_ROUTE_MARKER_INTERVAL_MS
    if (shouldLogRoute) {
      spatialRouteLogged = true
      lastSpatialRouteMarkerMs = now
      lastSpatialComponentCount = snapshot.componentCount
      lastSpatialActiveCount = snapshot.activeCount
      lastSpatialControllerTypeCount = snapshot.controllerTypeCount
      lastSpatialAllButtonState = snapshot.allControllerButtonState
      bindings.marker(
          SpatialControllerRoutingModule.controllerInputRouteReadyMarker(
              snapshot = snapshot,
              leftStickPanelDistanceMapping =
                  bindings.currentLeftStickPanelDistanceMapping(),
              leftStickYPanelDistanceEnabled =
                  bindings.currentLeftStickPanelDistanceEnabled(),
              spatialVrInputSystem = bindings.currentSpatialVrInputSystemToken(),
          )
      )
    }

    if (snapshot.rightThumbY != 0.0f) {
      bindings.applyProjectionScale(
          snapshot.rightThumbY,
          snapshot.rightInputSource,
          "right-thumb-up-down-projection-target-scale",
          SpatialControllerRoutingModule.rightThumbProjectionScaleDetail(snapshot),
      )
    }
    if (snapshot.leftThumbY != 0.0f) {
      bindings.applyPanelDistance(
          snapshot.leftThumbY,
          "spatial-sdk-avatar-body-controller",
          bindings.currentLeftStickPanelDistanceMapping(),
          SpatialControllerRoutingModule.leftThumbPanelDistanceDetail(snapshot),
      )
    }

    val triggerPressedEdge =
        snapshot.triggerPressed || (snapshot.triggerDown && !spatialRightTriggerDown)
    spatialRightTriggerDown = snapshot.triggerDown
    if (
        triggerPressedEdge &&
            bindings.recenterParticleSphere(
                snapshot.rightInputSource,
                SpatialControllerRoutingModule.rightTriggerParticleRecenterDetail(snapshot),
            )
    ) {
      return
    }

    val secondaryPressedEdge =
        snapshot.secondaryPressed || (snapshot.secondaryDown && !spatialSecondaryDown)
    spatialSecondaryDown = snapshot.secondaryDown
    if (!snapshot.secondaryDown) {
      bindings.armSecondaryToggle(snapshot.rightInputSource)
    }
    if (secondaryPressedEdge) {
      bindings.toggleSecondary(
          snapshot.rightInputSource,
          SpatialControllerRoutingModule.rightSecondaryPlacementToggleDetail(snapshot),
      )
      return
    }

    val primaryPressedEdge = snapshot.pressed || (snapshot.down && !spatialPrimaryDown)
    spatialPrimaryDown = snapshot.down
    if (!primaryPressedEdge) {
      return
    }
    bindings.openPrimary(
        snapshot.rightInputSource,
        SpatialControllerRoutingModule.rightPrimaryPanelToggleDetail(snapshot),
    )
  }

  companion object {
    const val MODULE_ID = "spatial-controller-polling-coordinator"
  }
}
