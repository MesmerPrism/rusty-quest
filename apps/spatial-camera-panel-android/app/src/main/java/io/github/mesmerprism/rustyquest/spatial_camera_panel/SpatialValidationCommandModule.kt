package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal object SpatialValidationCommandModule {
  const val MODULE_ID = "spatial-validation-command-route"
  const val DEFAULT_SURFACE_TARGET_ID = "real-hands"

  fun remoteUiCommandSource(uiAction: String): String = "remote-ui-command-$uiAction"

  fun uiCommandStartMarker(uiAction: String): String =
      "channel=validation status=ui-command-start uiAction=${activityMarkerToken(uiAction)} " +
          authorityMarkerFields()

  fun uiCommandCompleteMarker(
      uiAction: String,
      panelMode: String,
      privateLayerPanelVisible: Boolean,
      surfaceTargetId: String,
  ): String =
      "channel=validation status=ui-command-complete uiAction=${activityMarkerToken(uiAction)} " +
          "panelMode=$panelMode privateLayerPanelVisible=$privateLayerPanelVisible " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)}"

  fun uiCommandFailedMarker(uiAction: String, error: String): String =
      "channel=validation status=ui-command-failed uiAction=${activityMarkerToken(uiAction)} " +
          "error=${activityMarkerToken(error)}"

  fun surfaceTargetActivatedMarker(
      surfaceTargetId: String,
      panelMode: String,
  ): String =
      "channel=validation status=surface-target-activated " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} panelMode=$panelMode"

  fun surfaceTargetActivationFailedMarker(surfaceTargetId: String, error: String): String =
      "channel=validation status=surface-target-activation-failed " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "error=${activityMarkerToken(error)}"

  fun surfaceTargetActivationStartMarker(
      surfaceTargetId: String,
      source: String,
  ): String =
      "channel=validation status=surface-target-activation-start " +
          "surfaceTargetId=${activityMarkerToken(surfaceTargetId)} " +
          "source=${activityMarkerToken(source)} " +
          authorityMarkerFields()

  fun throwableErrorToken(throwable: Throwable): String =
      throwable.message ?: throwable.javaClass.simpleName

  private fun authorityMarkerFields(): String =
      "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel"
}
