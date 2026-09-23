package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.atomic.AtomicLong

internal enum class SpatialCameraHwbProjectionRawLayerState(
    val code: Int,
    val markerToken: String,
) {
  ABSENT(0, "absent"),
  RAW_SCENE_QUAD_ACTIVE(1, "raw-scene-quad-active"),
}

internal data class SpatialCameraHwbProjectionRawLaunchFence(
    val launchChallenge: Long,
    val layerGeneration: Long,
    val layerSwitchCount: Long,
    val layerState: SpatialCameraHwbProjectionRawLayerState,
) {
  fun markerFields(): String =
      "rawProjectionLaunchChallenge=$launchChallenge " +
          "rawProjectionLayerGeneration=$layerGeneration " +
          "rawProjectionLayerSwitchCount=$layerSwitchCount " +
          "rawProjectionLayerState=${layerState.markerToken}"
}

internal class SpatialCameraHwbProjectionRawLayerContinuity {
  private var activeLaunchChallenge = 0L
  private var layerGeneration = 0L
  private var layerSwitchCount = 0L
  private var layerState = SpatialCameraHwbProjectionRawLayerState.ABSENT

  @Synchronized
  fun beginLaunch(launchChallenge: Long) {
    require(launchChallenge > 0L) { "Raw Projection launch challenge must be positive." }
    activeLaunchChallenge = launchChallenge
    layerGeneration = 0L
    layerSwitchCount = 0L
    layerState = SpatialCameraHwbProjectionRawLayerState.ABSENT
  }

  @Synchronized
  fun recordLayerCreated(launchChallenge: Long): SpatialCameraHwbProjectionRawLaunchFence {
    require(launchChallenge == activeLaunchChallenge && activeLaunchChallenge > 0L) {
      "Raw Projection layer creation does not match the active launch challenge."
    }
    if (layerState != SpatialCameraHwbProjectionRawLayerState.ABSENT) {
      layerSwitchCount = Math.addExact(layerSwitchCount, 1L)
    }
    layerGeneration = Math.addExact(layerGeneration, 1L)
    layerState = SpatialCameraHwbProjectionRawLayerState.RAW_SCENE_QUAD_ACTIVE
    return snapshot()
  }

  @Synchronized
  fun recordLayerRemoved(): SpatialCameraHwbProjectionRawLaunchFence? {
    if (
        activeLaunchChallenge <= 0L ||
            layerState == SpatialCameraHwbProjectionRawLayerState.ABSENT
    ) {
      return null
    }
    layerSwitchCount = Math.addExact(layerSwitchCount, 1L)
    layerState = SpatialCameraHwbProjectionRawLayerState.ABSENT
    return snapshot()
  }

  private fun snapshot(): SpatialCameraHwbProjectionRawLaunchFence =
      SpatialCameraHwbProjectionRawLaunchFence(
          launchChallenge = activeLaunchChallenge,
          layerGeneration = layerGeneration,
          layerSwitchCount = layerSwitchCount,
          layerState = layerState,
      )
}

internal object SpatialCameraHwbProjectionRawLaunchChallengeSource {
  private val nextChallenge =
      AtomicLong((System.nanoTime() and Long.MAX_VALUE).coerceAtLeast(1L))

  fun next(): Long =
      nextChallenge.updateAndGet { current -> if (current == Long.MAX_VALUE) 1L else current + 1L }
}
