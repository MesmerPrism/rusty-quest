package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

internal const val VR_STROBE_BLACK_LEAD_IN_MS = 500L

internal enum class VrStrobeOutputKind { INTERFERENCE, TEMPORAL }

internal enum class VrStrobeSafetyState {
  FEATURE_DISABLED,
  READY,
  ARMED,
  BLACK_LEAD_IN,
  RUNNING,
}

internal data class VrStrobeSafetySnapshot(
    val state: VrStrobeSafetyState,
    val outputKind: VrStrobeOutputKind? = null,
    val profileId: String = "none",
    val profileTitle: String = "Stimulus",
    val elapsedSeconds: Float = 0f,
    val visualOutputActive: Boolean = false,
    val blackCarrierRequired: Boolean = false,
    val automaticTimeLimit: Boolean = false,
    val randomizeAvailable: Boolean = false,
    val distanceMeters: Float = VrStrobeDistancePolicy.DEFAULT_METERS,
    val curvedMode: Boolean = false,
    val concavity: Float = VrStrobeConcavityPolicy.DEFAULT,
    val carrierArcDegrees: Float = 0f,
    val selectedPresetIndex: Int = -1,
    val stimulusRevision: Long = 0L,
    val rejectionReason: String = "none",
)

internal class VrStrobeSafetyController(private val featureEnabled: Boolean) {
  private var state =
      if (featureEnabled) VrStrobeSafetyState.READY else VrStrobeSafetyState.FEATURE_DISABLED
  private var outputKind: VrStrobeOutputKind? = null
  private var profileId = "none"
  private var leadInEndsAtMs = 0L
  private var outputStartedAtMs = 0L
  private var rejectionReason = "none"
  private var warningAcknowledged = false

  fun acknowledgeWarning(acknowledged: Boolean): VrStrobeSafetySnapshot {
    if (!featureEnabled) return reject("feature-disabled")
    if (!acknowledged) {
      warningAcknowledged = false
      clearRun()
      state = VrStrobeSafetyState.READY
      rejectionReason = "none"
      return snapshot(0L)
    }
    if (state == VrStrobeSafetyState.ARMED) return snapshot(0L)
    if (state != VrStrobeSafetyState.READY) {
      return reject("acknowledgement-not-allowed-during-run")
    }
    warningAcknowledged = true
    clearRun()
    state = VrStrobeSafetyState.ARMED
    rejectionReason = "none"
    return snapshot(0L)
  }

  fun begin(
      requestedKind: VrStrobeOutputKind,
      requestedProfileId: String,
      nowMs: Long,
  ): VrStrobeSafetySnapshot {
    if (!featureEnabled) return reject("feature-disabled", nowMs)
    if (!warningAcknowledged) return reject("fresh-warning-acknowledgement-required", nowMs)
    if (state != VrStrobeSafetyState.ARMED) {
      return reject("stimulus-selection-requires-armed-session", nowMs)
    }
    if (requestedProfileId.isBlank()) return reject("profile-id-required", nowMs)
    outputKind = requestedKind
    profileId = requestedProfileId
    leadInEndsAtMs = nowMs + VR_STROBE_BLACK_LEAD_IN_MS
    outputStartedAtMs = leadInEndsAtMs
    rejectionReason = "none"
    state = VrStrobeSafetyState.BLACK_LEAD_IN
    return snapshot(nowMs)
  }

  fun tick(nowMs: Long): VrStrobeSafetySnapshot {
    if (state == VrStrobeSafetyState.BLACK_LEAD_IN && nowMs >= leadInEndsAtMs) {
      state = VrStrobeSafetyState.RUNNING
    }
    return snapshot(nowMs)
  }

  fun stop(reason: String = "explicit-stop", nowMs: Long = 0L): VrStrobeSafetySnapshot {
    clearRun()
    state =
        if (!featureEnabled) {
          VrStrobeSafetyState.FEATURE_DISABLED
        } else if (warningAcknowledged) {
          VrStrobeSafetyState.ARMED
        } else {
          VrStrobeSafetyState.READY
        }
    rejectionReason = reason
    return snapshot(nowMs)
  }

  fun focusLost(nowMs: Long): VrStrobeSafetySnapshot =
      invalidateWarningAcknowledgement("focus-lost", nowMs)

  fun invalidateWarningAcknowledgement(
      reason: String,
      nowMs: Long = 0L,
  ): VrStrobeSafetySnapshot {
    warningAcknowledged = false
    return stop(reason, nowMs)
  }

  fun snapshot(nowMs: Long): VrStrobeSafetySnapshot {
    val elapsedMs =
        if (state == VrStrobeSafetyState.RUNNING) {
          (nowMs - outputStartedAtMs).coerceAtLeast(0L)
        } else {
          0L
        }
    return VrStrobeSafetySnapshot(
        state = state,
        outputKind = outputKind,
        profileId = profileId,
        elapsedSeconds = elapsedMs / 1_000f,
        visualOutputActive = state == VrStrobeSafetyState.RUNNING,
        blackCarrierRequired = state == VrStrobeSafetyState.BLACK_LEAD_IN,
        rejectionReason = rejectionReason,
    )
  }

  private fun reject(reason: String, nowMs: Long = 0L): VrStrobeSafetySnapshot {
    rejectionReason = reason
    return snapshot(nowMs)
  }

  private fun clearRun() {
    outputKind = null
    profileId = "none"
    leadInEndsAtMs = 0L
    outputStartedAtMs = 0L
  }
}
