package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.SystemClock
import kotlin.math.abs

internal data class SpatialCameraHwbProjectionTuningBindings(
    val routeActive: () -> Boolean,
    val projectionEntityPresent: () -> Boolean,
    val privateLayerPanelVisible: () -> Boolean,
    val workflowPanelVisible: () -> Boolean,
    val initialTargetScale: () -> Float,
    val targetScaleJoystickRate: () -> Float,
    val targetDistanceMeters: () -> Float,
    val updatePlacement: (String, Boolean) -> Unit,
    val submitNativeStereoOffset: (Float) -> Long,
    val submitNativeTargetScale: (Float) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionTuningCoordinator(
    private val bindings: SpatialCameraHwbProjectionTuningBindings,
) {
  private var targetScale = CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT
  private var stereoHorizontalOffsetUv =
      CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV
  private var lastScaleJoystickMs = 0L
  private var lastScaleJoystickMarkerMs = 0L

  fun resetForLaunch() {
    targetScale = bindings.initialTargetScale()
    resetStereoOffset()
    lastScaleJoystickMs = 0L
    lastScaleJoystickMarkerMs = 0L
  }

  fun resetStereoOffset() {
    stereoHorizontalOffsetUv = CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_DEFAULT_UV
  }

  fun targetScale(): Float =
      targetScale.coerceIn(
          CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
          CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
      )

  fun stereoHorizontalOffsetUv(): Float =
      stereoHorizontalOffsetUv.coerceIn(
          CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MIN_UV,
          CAMERA_HWB_PROJECTION_STEREO_HORIZONTAL_OFFSET_MAX_UV,
      )

  fun targetScaleJoystickRate(): Float = bindings.targetScaleJoystickRate()

  fun leftEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.leftEffectiveTargetRectMarker(
          targetScale(),
          stereoHorizontalOffsetUv(),
      )

  fun rightEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.rightEffectiveTargetRectMarker(
          targetScale(),
          stereoHorizontalOffsetUv(),
      )

  fun leftPackedEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.leftPackedEffectiveTargetRectMarker(
          targetScale(),
          stereoHorizontalOffsetUv(),
      )

  fun rightPackedEffectiveTargetRectMarker(): String =
      CameraHwbProjectionModule.rightPackedEffectiveTargetRectMarker(
          targetScale(),
          stereoHorizontalOffsetUv(),
      )

  fun applyScaleInput(
      rightY: Float,
      inputSource: String,
      controllerJoystickMapping: String,
      detail: String,
  ): Boolean {
    if (bindings.privateLayerPanelVisible()) {
      return false
    }
    if (!bindings.routeActive() || !bindings.projectionEntityPresent()) {
      return false
    }
    if (abs(rightY) < PANEL_HEADLOCK_JOYSTICK_DEADZONE) {
      return false
    }

    val now = SystemClock.elapsedRealtime()
    val dtSeconds =
        if (lastScaleJoystickMs <= 0L) {
          1.0f / 60.0f
        } else {
          ((now - lastScaleJoystickMs).toFloat() / 1000.0f).coerceIn(0.0f, 0.08f)
        }
    lastScaleJoystickMs = now
    val scaleRate = bindings.targetScaleJoystickRate()
    val previousScale = targetScale()
    val signedInput =
        if (rightY > 0.0f) {
          rightY - PANEL_HEADLOCK_JOYSTICK_DEADZONE
        } else {
          rightY + PANEL_HEADLOCK_JOYSTICK_DEADZONE
        }
    val updatedScale =
        (previousScale + signedInput * scaleRate * dtSeconds)
            .coerceIn(
                CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
                CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
            )
    if (abs(updatedScale - previousScale) < 0.00001f) {
      return false
    }
    targetScale = updatedScale
    updateNativeTargetScale("right-stick-projection-target-scale", false)
    bindings.updatePlacement("right-stick-projection-target-scale", false)

    if (
        now - lastScaleJoystickMarkerMs >=
            CAMERA_HWB_PROJECTION_TARGET_SCALE_JOYSTICK_MARKER_INTERVAL_MS
    ) {
      lastScaleJoystickMarkerMs = now
      bindings.marker(
          CameraHwbProjectionModule.targetScaleJoystickAdjustedMarker(
              inputSource = inputSource,
              controllerJoystickMapping = controllerJoystickMapping,
              detail = detail,
              dtSeconds = dtSeconds,
              scaleRate = scaleRate,
              panelVisible = bindings.workflowPanelVisible(),
              previousScale = previousScale,
              updatedScale = updatedScale,
              targetDistanceMeters = bindings.targetDistanceMeters(),
              stereoHorizontalOffsetUv = stereoHorizontalOffsetUv(),
          )
      )
    }
    return true
  }

  fun updateTargetScaleFromPanel(requestedScale: Float, source: String): Float {
    val previousScale = targetScale()
    targetScale =
        requestedScale.coerceIn(
            CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
            CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
        )
    val updatedScale = targetScale()
    updateNativeTargetScale(source, false)
    bindings.updatePlacement(source, false)
    bindings.marker(
        CameraHwbProjectionModule.targetScalePanelAdjustedMarker(
            source = source,
            previousScale = previousScale,
            updatedScale = updatedScale,
            stereoHorizontalOffsetUv = stereoHorizontalOffsetUv(),
        )
    )
    return updatedScale
  }

  fun updateNativeStereoOffset(reason: String, forceLog: Boolean) {
    val stereoOffsetUv = stereoHorizontalOffsetUv()
    val updateMask =
        runCatching { bindings.submitNativeStereoOffset(stereoOffsetUv) }
            .getOrElse { throwable ->
              if (forceLog) {
                bindings.marker(
                    CameraHwbProjectionModule.targetStereoHorizontalOffsetUpdateFailedMarker(
                        reason = reason,
                        stereoOffsetUv = stereoOffsetUv,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
              0L
            }
    if (forceLog) {
      bindings.marker(
          CameraHwbProjectionModule.targetStereoHorizontalOffsetNativeUpdatedMarker(
              reason = reason,
              updateMask = updateMask,
              targetScale = targetScale(),
              stereoOffsetUv = stereoOffsetUv,
          )
      )
    }
  }

  fun updateNativeTargetScale(reason: String, forceLog: Boolean) {
    val scale = targetScale()
    val updateMask =
        runCatching { bindings.submitNativeTargetScale(scale) }
            .getOrElse { throwable ->
              if (forceLog) {
                bindings.marker(
                    CameraHwbProjectionModule.targetScaleUpdateFailedMarker(
                        reason = reason,
                        targetScale = scale,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
              0L
            }
    if (forceLog) {
      bindings.marker(
          CameraHwbProjectionModule.targetScaleNativeUpdatedMarker(
              reason = reason,
              updateMask = updateMask,
              targetScale = scale,
              stereoHorizontalOffsetUv = stereoHorizontalOffsetUv(),
          )
      )
    }
  }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-tuning-coordinator"
  }
}
