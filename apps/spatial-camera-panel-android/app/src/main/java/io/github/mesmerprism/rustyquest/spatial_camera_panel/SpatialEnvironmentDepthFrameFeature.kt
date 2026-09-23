package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase

/**
 * Runs the current candidate environment-depth acquisition from Spatial SDK's early system.
 *
 * The native bridge deliberately owns neither xrWaitFrame nor xrBeginFrame/xrEndFrame. When no
 * active visual consumer owns the provider, [acquireFrame] is a Kotlin-side no-op and no JNI or
 * environment-depth work is submitted.
 */
internal class SpatialEnvironmentDepthFrameFeature(
    private val readFrameTiming: () -> SpatialEnvironmentDepthFrameTiming,
    private val readRecoveryPolicy: () -> SpatialEnvironmentDepthRecoveryPolicy,
    private val acquireFrame: (Long, SpatialEnvironmentDepthRecoveryPolicy) -> Unit,
) : SpatialFeature {
  override fun earlySystemsToRegister(): List<SystemBase> =
      listOf(
          SpatialEnvironmentDepthFrameSystem(
              readFrameTiming = readFrameTiming,
              readRecoveryPolicy = readRecoveryPolicy,
              acquireFrame = acquireFrame,
          )
      )
}

private class SpatialEnvironmentDepthFrameSystem(
    private val readFrameTiming: () -> SpatialEnvironmentDepthFrameTiming,
    private val readRecoveryPolicy: () -> SpatialEnvironmentDepthRecoveryPolicy,
    private val acquireFrame: (Long, SpatialEnvironmentDepthRecoveryPolicy) -> Unit,
) : SystemBase() {
  private val frameGate = SpatialEnvironmentDepthFrameGate()

  override fun execute() {
    val timing = readFrameTiming()
    val recoveryPolicy = readRecoveryPolicy()
    if (frameGate.shouldAcquire(timing, recoveryPolicy)) {
      acquireFrame(timing.predictedDisplayTimeNs, recoveryPolicy)
    }
  }
}

internal enum class SpatialEnvironmentDepthRecoveryPolicy(
    val markerToken: String,
    val panelLabel: String,
) {
  Bounded("bounded", "Bounded recovery"),
  Aggressive("aggressive", "Maximum freshness"),
}

internal data class SpatialEnvironmentDepthFrameTiming(
    val predictedDisplayTimeNs: Long,
    val waitFrameReturnTimeNs: Long,
)

/**
 * Suppresses ECS ticks that do not represent a newly waited OpenXR frame.
 *
 * Spatial SDK can advance predicted display time on an ECS tick for which no new xrWaitFrame has
 * returned. Treating predicted display time alone as frame identity caused alternating
 * XR_ERROR_CALL_ORDER_INVALID acquisitions when the OpenXR application frame rate was below the
 * ECS rate. The wait-frame return timestamp changes only with a newly waited application frame;
 * the matching predicted display time remains the exact time passed to the native acquire call.
 */
internal class SpatialEnvironmentDepthFrameGate {
  private var lastWaitFrameReturnTimeNs = 0L

  fun shouldAcquire(
      timing: SpatialEnvironmentDepthFrameTiming,
      recoveryPolicy: SpatialEnvironmentDepthRecoveryPolicy =
          SpatialEnvironmentDepthRecoveryPolicy.Bounded,
  ): Boolean {
    if (timing.predictedDisplayTimeNs <= 0L || timing.waitFrameReturnTimeNs <= 0L) {
      return false
    }
    if (recoveryPolicy == SpatialEnvironmentDepthRecoveryPolicy.Bounded &&
        timing.waitFrameReturnTimeNs == lastWaitFrameReturnTimeNs) {
      return false
    }
    lastWaitFrameReturnTimeNs = timing.waitFrameReturnTimeNs
    return true
  }
}
