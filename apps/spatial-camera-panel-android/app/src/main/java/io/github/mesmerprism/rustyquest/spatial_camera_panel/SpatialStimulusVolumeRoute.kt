package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.Locale

internal const val SPATIAL_STIMULUS_VOLUME_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.stimulus_volume.enabled"
internal const val SPATIAL_STIMULUS_VOLUME_PROFILE_PROPERTY =
    "debug.rustyquest.spatial.stimulus_volume.profile_id"
internal const val SPATIAL_STIMULUS_VOLUME_PHASE_MODE_PROPERTY =
    "debug.rustyquest.spatial.stimulus_volume.phase_mode"
internal const val SPATIAL_STIMULUS_VOLUME_TEMPORAL_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.stimulus_volume.temporal_enabled"
internal const val SPATIAL_STIMULUS_VOLUME_AUTOSTART_PROPERTY =
    "debug.rustyquest.spatial.stimulus_volume.autostart"
internal const val SPATIAL_STIMULUS_VOLUME_SAFETY_ACK_PROPERTY =
    "debug.rustyquest.spatial.stimulus_volume.safety_acknowledged"
internal const val SPATIAL_STIMULUS_VOLUME_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.stimulus_volume.hold_ms"

internal const val SPATIAL_STIMULUS_VOLUME_PROFILE_ID =
    "stimulus.profile.volume_only_bright_interference"
internal const val SPATIAL_STIMULUS_VOLUME_DEFAULT_HOLD_MS = 20_000L
internal const val SPATIAL_STIMULUS_VOLUME_MIN_HOLD_MS = 2_000L
internal const val SPATIAL_STIMULUS_VOLUME_MAX_HOLD_MS = 30_000L
internal const val SPATIAL_STIMULUS_VOLUME_FRAGMENT_SAMPLES = 16
internal const val SPATIAL_STIMULUS_VOLUME_PROFILE_SAMPLES = 32

internal enum class SpatialStimulusVolumePhaseMode(val propertyValue: String) {
  FIXED_PHASE("fixed-phase");

  companion object {
    fun parse(raw: String): SpatialStimulusVolumePhaseMode? =
        entries.firstOrNull { it.propertyValue == raw.trim().lowercase(Locale.US) }
  }
}

internal data class SpatialStimulusVolumeConfig(
    val enabled: Boolean,
    val profileId: String,
    val phaseMode: SpatialStimulusVolumePhaseMode?,
    val temporalEnabled: Boolean,
    val autostart: Boolean,
    val safetyAcknowledged: Boolean,
    val holdMs: Long,
    val rejectionReason: String,
)

internal object SpatialStimulusVolumeRoute {
  const val MODULE_ID = "spatial-stimulus-volume-route"
  const val SHADER_NAME = "spatial_stimulus_volume"

  fun resolve(
      readProperty: (String) -> String = ::activityReadSystemProperty,
  ): SpatialStimulusVolumeConfig {
    val requestedEnable = parseBoolean(readProperty(SPATIAL_STIMULUS_VOLUME_ENABLED_PROPERTY))
    if (requestedEnable != true) {
      return inert(
          if (requestedEnable == false) "disabled" else "missing-or-invalid-enable"
      )
    }

    val profileId = readProperty(SPATIAL_STIMULUS_VOLUME_PROFILE_PROPERTY).trim()
    if (profileId != SPATIAL_STIMULUS_VOLUME_PROFILE_ID) {
      return inert("unsupported-profile", profileId = profileId)
    }

    val rawPhaseMode = readProperty(SPATIAL_STIMULUS_VOLUME_PHASE_MODE_PROPERTY)
    val phaseMode = SpatialStimulusVolumePhaseMode.parse(rawPhaseMode)
    if (phaseMode == null) {
      val reason =
          if (rawPhaseMode.trim().lowercase(Locale.US).contains("temporal") ||
              rawPhaseMode.trim().lowercase(Locale.US).contains("strobe")) {
            "temporal-phase-mode-forbidden"
          } else {
            "unsupported-phase-mode"
          }
      return inert(reason, profileId = profileId)
    }

    val temporalEnabled =
        parseBoolean(readProperty(SPATIAL_STIMULUS_VOLUME_TEMPORAL_ENABLED_PROPERTY))
    if (temporalEnabled != false) {
      return inert(
          if (temporalEnabled == true) {
            "temporal-modulation-forbidden"
          } else {
            "missing-or-invalid-temporal-enable"
          },
          profileId = profileId,
          phaseMode = phaseMode,
      )
    }

    val autostart = parseBoolean(readProperty(SPATIAL_STIMULUS_VOLUME_AUTOSTART_PROPERTY))
    if (autostart != false) {
      return inert(
          if (autostart == true) "autostart-forbidden" else "missing-or-invalid-autostart",
          profileId = profileId,
          phaseMode = phaseMode,
      )
    }

    val safetyAcknowledged =
        parseBoolean(readProperty(SPATIAL_STIMULUS_VOLUME_SAFETY_ACK_PROPERTY))
    if (safetyAcknowledged != true) {
      return inert(
          if (safetyAcknowledged == false) {
            "safety-acknowledgement-required"
          } else {
            "missing-or-invalid-safety-acknowledgement"
          },
          profileId = profileId,
          phaseMode = phaseMode,
      )
    }

    val holdMs =
        readProperty(SPATIAL_STIMULUS_VOLUME_HOLD_MS_PROPERTY)
            .trim()
            .toLongOrNull()
            ?.coerceIn(
                SPATIAL_STIMULUS_VOLUME_MIN_HOLD_MS,
                SPATIAL_STIMULUS_VOLUME_MAX_HOLD_MS,
            )
            ?: SPATIAL_STIMULUS_VOLUME_DEFAULT_HOLD_MS
    return SpatialStimulusVolumeConfig(
        enabled = true,
        profileId = profileId,
        phaseMode = phaseMode,
        temporalEnabled = false,
        autostart = false,
        safetyAcknowledged = true,
        holdMs = holdMs,
        rejectionReason = "none",
    )
  }

  fun inertMarker(reason: String, config: SpatialStimulusVolumeConfig): String =
      "channel=spatial-stimulus-volume status=inert reason=${activityMarkerToken(config.rejectionReason)} " +
          "trigger=${activityMarkerToken(reason)} enabled=false " +
          "effectiveMarker=rusty.quest.spatial_stimulus_volume.effective " +
          "temporalModulation=false autostart=false deviceLaunchAuthorized=false"

  fun startMarker(reason: String, config: SpatialStimulusVolumeConfig): String =
      "channel=spatial-stimulus-volume status=start reason=${activityMarkerToken(reason)} " +
          "profileId=${activityMarkerToken(config.profileId)} phaseMode=${config.phaseMode?.propertyValue} " +
          "shader=$SHADER_NAME fragmentSamples=$SPATIAL_STIMULUS_VOLUME_FRAGMENT_SAMPLES " +
          "profileRequestedSamples=$SPATIAL_STIMULUS_VOLUME_PROFILE_SAMPLES holdMs=${config.holdMs} " +
          "activationAdapter=android-system-property rendererAuthority=meta-spatial-sdk-custom-material"

  fun effectiveMarker(config: SpatialStimulusVolumeConfig): String =
      "channel=spatial-stimulus-volume status=effective " +
          "effectiveMarker=rusty.quest.spatial_stimulus_volume.effective enabled=${config.enabled} " +
          "profileAuthority=rusty-optics profileId=${activityMarkerToken(config.profileId)} " +
          "presentationMode=stereo-eye-field coverageIntent=full-viewport referenceSpace=view-locked " +
          "phaseMode=fixed-phase phaseSeconds=0 temporalModulation=false autostart=false " +
          "safetyClass=nonflashing-fixed-phase sourceSafetyClass=photosensitive-risk " +
          "sourceAcknowledgementRequired=true sourceAllowAutostart=false " +
          "fragmentSamples=$SPATIAL_STIMULUS_VOLUME_FRAGMENT_SAMPLES " +
          "profileRequestedSamples=$SPATIAL_STIMULUS_VOLUME_PROFILE_SAMPLES " +
          "carrierAdaptation=scene-tick-view-relative-opaque-quad deviceVisualProof=false"

  fun renderReadyMarker(): String =
      "channel=spatial-stimulus-volume status=render-ready carrierCreated=true " +
          "fixedPhase=true timeUniformPresent=false deviceVisualProof=false"

  fun completeMarker(sceneTicks: Long, viewLockUpdateFailures: Long): String =
      "channel=spatial-stimulus-volume status=complete sceneTicks=$sceneTicks " +
          "viewLockUpdateFailures=$viewLockUpdateFailures fixedPhase=true " +
          "humanVisibleCheckRequired=true deviceVisualProof=false"

  fun failureMarker(error: Throwable): String =
      "channel=spatial-stimulus-volume status=failed " +
          "error=${activityMarkerToken(error::class.java.simpleName)} " +
          "message=${activityMarkerToken(error.message ?: "none")}"

  fun cleanupMarker(reason: String, complete: Boolean): String =
      "channel=spatial-stimulus-volume status=cleanup reason=${activityMarkerToken(reason)} " +
          "cleanupComplete=$complete"

  private fun inert(
      rejectionReason: String,
      profileId: String = "none",
      phaseMode: SpatialStimulusVolumePhaseMode? = null,
  ): SpatialStimulusVolumeConfig =
      SpatialStimulusVolumeConfig(
          enabled = false,
          profileId = profileId.ifBlank { "none" },
          phaseMode = phaseMode,
          temporalEnabled = false,
          autostart = false,
          safetyAcknowledged = false,
          holdMs = SPATIAL_STIMULUS_VOLUME_DEFAULT_HOLD_MS,
          rejectionReason = rejectionReason,
      )

  private fun parseBoolean(raw: String): Boolean? =
      when (raw.trim().lowercase(Locale.US)) {
        "1", "true", "yes", "on", "enabled" -> true
        "0", "false", "no", "off", "disabled" -> false
        else -> null
      }
}
