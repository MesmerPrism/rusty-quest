package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import java.util.Locale

internal data class VrStrobeFeatureDecision(
    val enabled: Boolean,
    val reason: String,
)

internal object VrStrobeFeatureRoute {
  const val MODULE_ID = "spatial-vr-strobe-route"
  const val SHADER_NAME = "vr_strobe_interference"

  fun resolve(): VrStrobeFeatureDecision =
      VrStrobeFeatureDecision(true, "standalone-application-module")

  fun activationMarker(decision: VrStrobeFeatureDecision): String =
      "channel=spatial-vr-strobe status=${if (decision.enabled) "panel-enabled" else "inert"} " +
          "reason=${activityMarkerToken(decision.reason)} enabled=${decision.enabled} " +
          "effectiveMarker=rusty.quest.spatial_vr_strobe.effective activationAuthority=application-module " +
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

}

internal fun activityMarkerToken(value: String): String =
    value.trim().ifEmpty { "none" }.replace(Regex("[^A-Za-z0-9._:/-]+"), "_")
