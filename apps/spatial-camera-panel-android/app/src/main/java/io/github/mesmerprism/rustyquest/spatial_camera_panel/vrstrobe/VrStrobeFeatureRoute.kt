package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import io.github.mesmerprism.rustyquest.spatial_camera_panel.activityMarkerToken
import io.github.mesmerprism.rustyquest.spatial_camera_panel.activityReadSystemProperty
import java.util.Locale

internal const val VR_STROBE_FEATURE_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.vr_strobe.enabled"

internal data class VrStrobeFeatureDecision(
    val enabled: Boolean,
    val reason: String,
)

internal object VrStrobeFeatureRoute {
  const val MODULE_ID = "spatial-vr-strobe-route"
  const val SHADER_NAME = "vr_strobe_interference"

  fun resolve(
      readProperty: (String) -> String = ::activityReadSystemProperty,
  ): VrStrobeFeatureDecision {
    val parsed = parseBoolean(readProperty(VR_STROBE_FEATURE_ENABLED_PROPERTY))
    return when (parsed) {
      true -> VrStrobeFeatureDecision(true, "explicit-property-enable")
      false -> VrStrobeFeatureDecision(false, "disabled")
      null -> VrStrobeFeatureDecision(false, "missing-or-invalid-enable")
    }
  }

  fun activationMarker(decision: VrStrobeFeatureDecision): String =
      "channel=spatial-vr-strobe status=${if (decision.enabled) "panel-enabled" else "inert"} " +
          "reason=${activityMarkerToken(decision.reason)} enabled=${decision.enabled} " +
          "effectiveMarker=rusty.quest.spatial_vr_strobe.effective defaultActivation=disabled " +
          "autostart=false restoredStateMayStart=false warningScreenFirst=true " +
          "warningAcknowledgementScope=focused-app-session " +
          "presetSelectionIsBeginGesture=true currentRunBeginGestureRequired=true " +
          "automaticTimeLimit=false controllerShortcuts=true"

  fun safetyMarker(snapshot: VrStrobeSafetySnapshot, source: String): String =
      "channel=spatial-vr-strobe status=safety-state source=${activityMarkerToken(source)} " +
          "state=${snapshot.state.name.lowercase(Locale.US)} " +
          "profileId=${activityMarkerToken(snapshot.profileId)} " +
          "outputKind=${snapshot.outputKind?.name?.lowercase(Locale.US) ?: "none"} " +
          "visualOutputActive=${snapshot.visualOutputActive} blackCarrierRequired=${snapshot.blackCarrierRequired} " +
          "automaticTimeLimit=${snapshot.automaticTimeLimit} randomizeAvailable=${snapshot.randomizeAvailable} " +
          "distanceMeters=${"%.3f".format(Locale.US, snapshot.distanceMeters)} " +
          "selectedPresetIndex=${snapshot.selectedPresetIndex} stimulusRevision=${snapshot.stimulusRevision} " +
          "rejectionReason=${activityMarkerToken(snapshot.rejectionReason)} " +
          "elapsedSeconds=${"%.3f".format(Locale.US, snapshot.elapsedSeconds)}"

  private fun parseBoolean(raw: String): Boolean? =
      when (raw.trim().lowercase(Locale.US)) {
        "1", "true", "yes", "on", "enabled" -> true
        "0", "false", "no", "off", "disabled" -> false
        else -> null
      }
}
