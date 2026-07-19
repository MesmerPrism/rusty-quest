package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

internal enum class VrStrobePanelScreen {
  WARNING,
  PORTAL,
  STORED,
  INTERFERENCE,
  TEMPORAL,
  ACTIVE,
}

internal object VrStrobePanelFlow {
  fun initialScreen(): VrStrobePanelScreen = VrStrobePanelScreen.WARNING

  fun afterWarningAcknowledgement(snapshot: VrStrobeSafetySnapshot): VrStrobePanelScreen =
      if (snapshot.state == VrStrobeSafetyState.ARMED) {
        VrStrobePanelScreen.PORTAL
      } else {
        VrStrobePanelScreen.WARNING
      }

  fun afterBegin(
      snapshot: VrStrobeSafetySnapshot,
      fallback: VrStrobePanelScreen,
  ): VrStrobePanelScreen =
      if (beginAccepted(snapshot)) {
        VrStrobePanelScreen.ACTIVE
      } else {
        fallback
      }

  fun beginAccepted(snapshot: VrStrobeSafetySnapshot): Boolean =
      snapshot.state == VrStrobeSafetyState.BLACK_LEAD_IN ||
          snapshot.state == VrStrobeSafetyState.RUNNING

  fun warningRequired(snapshot: VrStrobeSafetySnapshot): Boolean =
      snapshot.state == VrStrobeSafetyState.READY ||
          snapshot.state == VrStrobeSafetyState.FEATURE_DISABLED
}

internal object VrStrobePanelForegroundPolicy {
  fun carrierVisible(stimulusSelected: Boolean): Boolean = stimulusSelected
}
