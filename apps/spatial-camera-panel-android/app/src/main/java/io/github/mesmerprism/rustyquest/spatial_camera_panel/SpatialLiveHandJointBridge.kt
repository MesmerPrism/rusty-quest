package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt

object SpatialLiveHandJointBridge {
  const val ROW_COUNT: Int = 52
  const val FLOATS_PER_ROW: Int = 12
  const val EXPECTED_FLOAT_COUNT: Int = ROW_COUNT * FLOATS_PER_ROW
  const val VIEW_DIAGNOSTIC_FLOAT_COUNT: Int = 18
  const val VIEWER_WORLD_MAPPING_PROFILE_MIRROR_X = "mirror-x-origin-registration"
  const val VIEWER_WORLD_MAPPING_PROFILE_ROLLBACK = "viewer-world-basis-registration"
  const val VIEWER_WORLD_MAPPING_PROFILE_ACCEPTED = VIEWER_WORLD_MAPPING_PROFILE_MIRROR_X
  const val HAND_ADAPTER_ENABLED_PROPERTY =
      "debug.rustyquest.spatial_camera_panel.hand_adapter.enabled"
  const val HAND_ADAPTER_PROFILE_ID_PROPERTY =
      "debug.rustyquest.spatial_camera_panel.hand_adapter.profile_id"
  const val HAND_ADAPTER_PROJECT_ID_PROPERTY =
      "debug.rustyquest.spatial_camera_panel.hand_adapter.project_id"
  const val HAND_ADAPTER_FEATURE_ID_PROPERTY =
      "debug.rustyquest.spatial_camera_panel.hand_adapter.feature_id"
  const val HAND_ADAPTER_LOCK_REVISION_PROPERTY =
      "debug.rustyquest.spatial_camera_panel.hand_adapter.lock_revision"
  const val HAND_ADAPTER_LOCK_SHA256_PROPERTY =
      "debug.rustyquest.spatial_camera_panel.hand_adapter.lock_sha256"
  private const val NATIVE_RECEIPT_LIBRARY = "spatial_camera_panel_native_receipt"
  private const val VIEWER_WORLD_MAPPING_MODE_ACCEPTED = 2
  private const val VIEWER_WORLD_MAPPING_MODE_MIRROR_X = 3

  private var loadAttempted = false
  private var loaded = false
  private var startedKey = ""
  private var lastStartMask = 0L
  private var lastActivationApplied = false
  private val activationDecisionCache =
      SpatialAdapterDecisionCache { input -> handAdapterActivationDecision(input) }

  fun ensureStarted(probe: SpatialNativeInteropProbe): Long {
    val activationInput = handAdapterRuntimeInput()
    val activationDecision = currentHandAdapterActivationDecision()
    val key =
        "${probe.openXrInstanceHandle}|${probe.openXrSessionHandle}|" +
            "${probe.openXrGetInstanceProcAddrHandle}|${activationInput.profileId}|" +
            "${activationInput.projectId}|${activationInput.featureId}|" +
            "${activationInput.lockRevision}|${activationInput.lockSha256}|" +
            activationInput.enabled
    if (!activationDecision.applied) {
      if (loaded && lastStartMask != 0L) {
        nativeStopLiveHandJoints()
      }
      startedKey = key
      lastStartMask = 0L
      return 0L
    }
    if (!ensureLoaded()) {
      return 0L
    }
    if (key != startedKey || lastStartMask == 0L) {
      lastStartMask =
          nativeStartLiveHandJoints(
              probe.openXrInstanceHandle,
              probe.openXrSessionHandle,
              probe.openXrGetInstanceProcAddrHandle,
              activationInput.enabled,
              activationInput.profileId,
              activationInput.projectId,
              activationInput.featureId,
              activationInput.lockRevision,
              activationInput.lockSha256,
          )
      startedKey = key
    }
    return lastStartMask
  }

  fun updateViewerBasis(viewerPose: Pose, targetDistanceMeters: Float): Long {
    if (!currentHandAdapterActivationDecision().applied) {
      return 0L
    }
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
    if (!currentHandAdapterActivationDecision().applied) {
      return 0L
    }
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

  fun updateSpatialViewerWorldBasis(
      viewerPose: Pose,
      mappingProfile: String = VIEWER_WORLD_MAPPING_PROFILE_ACCEPTED,
  ): Long {
    if (!currentHandAdapterActivationDecision().applied) {
      return 0L
    }
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
        if (normalizeViewerWorldMappingProfile(mappingProfile) ==
            VIEWER_WORLD_MAPPING_PROFILE_MIRROR_X) {
          VIEWER_WORLD_MAPPING_MODE_MIRROR_X
        } else {
          VIEWER_WORLD_MAPPING_MODE_ACCEPTED
        },
        true,
    )
  }

  fun normalizeViewerWorldMappingProfile(value: String?): String =
      when (value?.trim()?.lowercase()) {
        VIEWER_WORLD_MAPPING_PROFILE_ROLLBACK -> VIEWER_WORLD_MAPPING_PROFILE_ROLLBACK
        else -> VIEWER_WORLD_MAPPING_PROFILE_ACCEPTED
      }

  fun pollRows(): FloatArray? {
    if (!currentHandAdapterActivationDecision().applied) {
      return null
    }
    if (!ensureLoaded()) {
      return null
    }
    val rows = nativePollLiveHandJointRows() ?: return null
    return rows.takeIf { it.size == EXPECTED_FLOAT_COUNT }
  }

  fun pollViewDiagnostics(): FloatArray? {
    if (!currentHandAdapterActivationDecision().applied) {
      return null
    }
    if (!ensureLoaded()) {
      return null
    }
    val values = nativePollLiveHandViewDiagnostics() ?: return null
    return values.takeIf { it.size == VIEW_DIAGNOSTIC_FLOAT_COUNT }
  }

  fun stop() {
    if (ensureLoaded()) {
      nativeStopLiveHandJoints()
    }
    startedKey = ""
    lastStartMask = 0L
    lastActivationApplied = false
    activationDecisionCache.clear()
  }

  fun loadedMarker(): String =
      "liveHandJointBridgeLoaded=$loaded liveHandJointBridgeLoadAttempted=$loadAttempted " +
          "liveHandJointBridgeStartMask=$lastStartMask"

  internal fun handAdapterRuntimeInput(): SpatialAdapterRuntimeInput =
      SpatialAdapterRuntimeInput(
          enabled =
              activityReadOptionalBooleanSystemProperty(HAND_ADAPTER_ENABLED_PROPERTY) == true,
          profileId = activityReadSystemProperty(HAND_ADAPTER_PROFILE_ID_PROPERTY),
          projectId = activityReadSystemProperty(HAND_ADAPTER_PROJECT_ID_PROPERTY),
          featureId = activityReadSystemProperty(HAND_ADAPTER_FEATURE_ID_PROPERTY),
          lockRevision =
              activityReadSystemProperty(HAND_ADAPTER_LOCK_REVISION_PROPERTY).toLongOrNull()
                  ?: 0L,
          lockSha256 = activityReadSystemProperty(HAND_ADAPTER_LOCK_SHA256_PROPERTY),
      )

  internal fun handAdapterActivationDecision(
      input: SpatialAdapterRuntimeInput
  ): SpatialAdapterLockDecision =
      SpatialAdapterNativeAuthority.resolveHand(input)

  @Synchronized
  internal fun currentHandAdapterActivationDecision(): SpatialAdapterLockDecision {
    val decision = activationDecisionCache.decisionFor(handAdapterRuntimeInput())
    if (lastActivationApplied && !decision.applied) {
      if (loaded && lastStartMask != 0L) {
        nativeStopLiveHandJoints()
      }
      startedKey = ""
      lastStartMask = 0L
    }
    lastActivationApplied = decision.applied
    return decision
  }

  internal fun handAdapterActivationMarker(decision: SpatialAdapterLockDecision): String =
      "channel=hand-adapter status=${if (decision.applied) "accepted" else "rejected"} " +
          "handAdapterEnabled=${decision.applied} handAdapterRuntimeMode=explicit-live-hand-bridge-start " +
          decision.markerFields()

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
      runtimeEnabled: Boolean,
      runtimeProfileId: String,
      runtimeProjectId: String,
      runtimeFeatureId: String,
      runtimeLockRevision: Long,
      runtimeLockSha256: String,
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
      mappingMode: Int,
      valid: Boolean,
  ): Long

  @JvmStatic private external fun nativePollLiveHandJointRows(): FloatArray?

  @JvmStatic private external fun nativePollLiveHandViewDiagnostics(): FloatArray?

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
