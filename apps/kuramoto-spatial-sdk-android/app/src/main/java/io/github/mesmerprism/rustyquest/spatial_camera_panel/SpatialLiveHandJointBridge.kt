package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt

object SpatialLiveHandJointBridge {
  const val ROW_COUNT: Int = 52
  const val FLOATS_PER_ROW: Int = 12
  const val EXPECTED_FLOAT_COUNT: Int = ROW_COUNT * FLOATS_PER_ROW

  private const val NATIVE_RECEIPT_LIBRARY = "kuramoto_spatial_native_receipt"

  private var loadAttempted = false
  private var loaded = false
  private var startedKey = ""
  private var lastStartMask = 0L

  fun ensureStarted(probe: SpatialNativeInteropProbe): Long {
    if (!ensureLoaded()) {
      return 0L
    }
    val key =
        "${probe.openXrInstanceHandle}|${probe.openXrSessionHandle}|${probe.openXrGetInstanceProcAddrHandle}"
    if (key != startedKey || lastStartMask == 0L) {
      lastStartMask =
          nativeStartLiveHandJoints(
              probe.openXrInstanceHandle,
              probe.openXrSessionHandle,
              probe.openXrGetInstanceProcAddrHandle,
          )
      startedKey = key
    }
    return lastStartMask
  }

  fun updateViewerBasis(viewerPose: Pose, targetDistanceMeters: Float): Long {
    if (!ensureLoaded()) {
      return 0L
    }
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = forward.cross(up).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val center = viewerPose.t + forward * targetDistanceMeters.coerceIn(0.20f, 2.0f)
    return nativeUpdateLiveHandPanelBasis(
        center.x,
        center.y,
        center.z,
        right.x,
        right.y,
        right.z,
        up.x,
        up.y,
        up.z,
        targetDistanceMeters.coerceIn(0.20f, 2.0f),
        true,
    )
  }

  fun clearViewerBasis(): Long {
    if (!ensureLoaded()) {
      return 0L
    }
    return nativeUpdateLiveHandPanelBasis(
        0.0f,
        1.22f,
        -0.72f,
        1.0f,
        0.0f,
        0.0f,
        0.0f,
        1.0f,
        0.0f,
        0.72f,
        false,
    )
  }

  fun updateSpatialViewerWorldBasis(viewerPose: Pose): Long {
    if (!ensureLoaded()) {
      return 0L
    }
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = forward.cross(up).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    return nativeUpdateLiveHandSpatialViewerWorldBasis(
        viewerPose.t.x,
        viewerPose.t.y,
        viewerPose.t.z,
        right.x,
        right.y,
        right.z,
        up.x,
        up.y,
        up.z,
        true,
    )
  }

  fun pollRows(): FloatArray? {
    if (!ensureLoaded()) {
      return null
    }
    val rows = nativePollLiveHandJointRows() ?: return null
    return rows.takeIf { it.size == EXPECTED_FLOAT_COUNT }
  }

  fun stop() {
    if (ensureLoaded()) {
      nativeStopLiveHandJoints()
    }
    startedKey = ""
    lastStartMask = 0L
  }

  fun loadedMarker(): String =
      "liveHandJointBridgeLoaded=$loaded liveHandJointBridgeLoadAttempted=$loadAttempted " +
          "liveHandJointBridgeStartMask=$lastStartMask"

  private fun ensureLoaded(): Boolean {
    if (loaded) {
      return true
    }
    if (!loadAttempted) {
      loadAttempted = true
      loaded = runCatching { System.loadLibrary(NATIVE_RECEIPT_LIBRARY) }.isSuccess
    }
    return loaded
  }

  @JvmStatic
  private external fun nativeStartLiveHandJoints(
      openXrInstanceHandle: Long,
      openXrSessionHandle: Long,
      openXrGetInstanceProcAddrHandle: Long,
  ): Long

  @JvmStatic
  private external fun nativeUpdateLiveHandPanelBasis(
      centerX: Float,
      centerY: Float,
      centerZ: Float,
      rightX: Float,
      rightY: Float,
      rightZ: Float,
      upX: Float,
      upY: Float,
      upZ: Float,
      targetDistanceMeters: Float,
      valid: Boolean,
  ): Long

  @JvmStatic
  private external fun nativeUpdateLiveHandSpatialViewerWorldBasis(
      centerX: Float,
      centerY: Float,
      centerZ: Float,
      rightX: Float,
      rightY: Float,
      rightZ: Float,
      upX: Float,
      upY: Float,
      upZ: Float,
      valid: Boolean,
  ): Long

  @JvmStatic private external fun nativePollLiveHandJointRows(): FloatArray?

  @JvmStatic private external fun nativeStopLiveHandJoints(): Long
}

private fun Vector3.normalizedOr(fallback: Vector3): Vector3 {
  val lengthSquared = x * x + y * y + z * z
  if (lengthSquared <= 1.0e-8f) {
    return fallback
  }
  val invLength = 1.0f / sqrt(lengthSquared)
  return Vector3(x * invLength, y * invLength, z * invLength)
}

