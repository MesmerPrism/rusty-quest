package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal object SpatialValidationCommandModule {
  const val MODULE_ID = "spatial-validation-command-route"
  const val DEFAULT_SELF_TEST_PARTICIPANT_ID = "codex-spatial-sdk-validation"
  const val DEFAULT_UI_COMMAND_PARTICIPANT_ID = "codex-spatial-ui-command"
  const val DEFAULT_SURFACE_TARGET_PARTICIPANT_ID = "codex-spatial-surface-target"
  const val DEFAULT_POLAR_LIVE_PARTICIPANT_ID = "codex-spatial-polar-live-validation"
  const val DEFAULT_SURFACE_TARGET_ID = "real-hands"

  fun remoteUiCommandSource(uiAction: String): String = "remote-ui-command-$uiAction"

  fun selfTestStartMarker(participantId: String, surfaceTargetId: String): String =
      "channel=validation status=self-test-start " +
          "participantId=${activityMarkerToken(participantId)} " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)}"

  fun selfTestBlockStartedMarker(
      participantId: String,
      surfaceTargetId: String,
      validationDriverProfileId: String,
  ): String =
      "channel=validation status=self-test-block-started " +
          "participantId=${activityMarkerToken(participantId)} " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "validationDriverProfileId=$validationDriverProfileId"

  fun selfTestCompleteMarker(
      participantId: String,
      surfaceTargetId: String,
      validationDriverProfileId: String,
  ): String =
      "channel=validation status=self-test-complete " +
          "participantId=${activityMarkerToken(participantId)} " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "validationDriverProfileId=$validationDriverProfileId"

  fun selfTestFailedMarker(error: String): String =
      "channel=validation status=self-test-failed error=${activityMarkerToken(error)}"

  fun uiCommandStartMarker(uiAction: String): String =
      "channel=validation status=ui-command-start uiAction=${activityMarkerToken(uiAction)} " +
          authorityMarkerFields()

  fun uiCommandCompleteMarker(
      uiAction: String,
      panelMode: String,
      workflowPanelVisible: Boolean,
      surfaceTargetId: String,
  ): String =
      "channel=validation status=ui-command-complete uiAction=${activityMarkerToken(uiAction)} " +
          "panelMode=$panelMode workflowPanelVisible=$workflowPanelVisible " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)}"

  fun uiCommandFailedMarker(uiAction: String, error: String): String =
      "channel=validation status=ui-command-failed uiAction=${activityMarkerToken(uiAction)} " +
          "error=${activityMarkerToken(error)}"

  fun surfaceTargetActivatedMarker(
      participantId: String,
      surfaceTargetId: String,
      validationDriverProfileId: String,
      panelMode: String,
  ): String =
      "channel=validation status=surface-target-activated " +
          "participantId=${activityMarkerToken(participantId)} " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "validationDriverProfileId=$validationDriverProfileId panelMode=$panelMode " +
          "leftInParticleView=true"

  fun surfaceTargetActivationFailedMarker(surfaceTargetId: String, error: String): String =
      "channel=validation status=surface-target-activation-failed " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "error=${activityMarkerToken(error)}"

  fun surfaceTargetActivationStartMarker(
      participantId: String,
      surfaceTargetId: String,
      source: String,
  ): String =
      "channel=validation status=surface-target-activation-start " +
          "participantId=${activityMarkerToken(participantId)} " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "source=${activityMarkerToken(source)} " +
          authorityMarkerFields()

  fun remoteParticipantCreatedMarker(source: String, participantId: String): String =
      "channel=validation status=remote-participant-created " +
          "source=${activityMarkerToken(source)} " +
          "participantId=${activityMarkerToken(participantId)}"

  fun polarLiveStartMarker(
      participantId: String,
      surfaceTargetId: String,
      scanSeconds: Long,
      connectDelaySeconds: Long,
      ecgSeconds: Long,
  ): String =
      "channel=polar-live-validation status=start " +
          "participantId=${activityMarkerToken(participantId)} " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "scanSeconds=$scanSeconds connectDelaySeconds=$connectDelaySeconds " +
          "ecgSeconds=$ecgSeconds " +
          authorityMarkerFields()

  fun polarPanelAutomationReadyMarker(participantId: String): String =
      "channel=polar-live-validation status=polar-panel-automation-ready " +
          "participantId=${activityMarkerToken(participantId)}"

  fun polarScanCommandIssuedMarker(participantId: String): String =
      "channel=polar-live-validation status=scan-command-issued " +
          "participantId=${activityMarkerToken(participantId)}"

  fun polarConnectRequestedMarker(discoveredDeviceCount: Int): String =
      "channel=polar-live-validation status=connect-requested " +
          "discoveredDeviceCount=$discoveredDeviceCount"

  fun polarStartEcgRequestedMarker(discoveredDeviceCount: Int): String =
      "channel=polar-live-validation status=start-ecg-requested " +
          "discoveredDeviceCount=$discoveredDeviceCount"

  fun polarCompleteMarker(
      ecgReceiving: Boolean,
      discoveredDeviceCount: Int,
      ecgStatus: String,
  ): String =
      "channel=polar-live-validation status=complete ecgReceiving=$ecgReceiving " +
          "discoveredDeviceCount=$discoveredDeviceCount " +
          "ecgStatus=${activityMarkerToken(ecgStatus)}"

  fun polarFailedMarker(error: String): String =
      "channel=polar-live-validation status=failed error=${activityMarkerToken(error)}"

  fun throwableErrorToken(throwable: Throwable): String =
      throwable.message ?: throwable.javaClass.simpleName

  private fun authorityMarkerFields(): String =
      "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel"
}
