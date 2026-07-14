package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class SpatialProjectionPanelStopReceipt(
    val nativeProjectionStopped: Boolean,
    val videoProjectionStopped: Boolean,
    val carrierCleanupStatus: String,
)

internal data class SpatialProjectionPanelVisibilityBindings(
    val projectionLaunchStarted: () -> Boolean,
    val currentVideoSettings: () -> SpatialVideoProjectionSettings,
    val markProjectionLaunchStopped: () -> Unit,
    val stopProjectionPanel: (String) -> SpatialProjectionPanelStopReceipt,
    val enableSystemPassthrough: () -> Boolean,
    val restartProjectionPanel: (SpatialVideoProjectionSettings, String) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialProjectionPanelVisibilityCoordinator(
    private val bindings: SpatialProjectionPanelVisibilityBindings,
) {
  var enabled: Boolean by mutableStateOf(true)
    private set

  private var resumeProjectionOnEnable = false
  private var resumeVideoSettings = SpatialVideoProjectionSettings.disabled()

  fun setEnabled(requestedEnabled: Boolean, source: String): Boolean {
    if (requestedEnabled == enabled) {
      bindings.marker(
          SpatialProjectionPanelVisibilityModule.unchangedMarker(
              enabled = enabled,
              source = source,
          )
      )
      return enabled
    }

    if (!requestedEnabled) {
      resumeProjectionOnEnable = bindings.projectionLaunchStarted()
      resumeVideoSettings = bindings.currentVideoSettings()
      enabled = false
      bindings.markProjectionLaunchStopped()
      val stopReceipt =
          runCatching { bindings.stopProjectionPanel("projection-panel-toggle-off") }
              .getOrElse { throwable ->
                SpatialProjectionPanelStopReceipt(
                    nativeProjectionStopped = false,
                    videoProjectionStopped = false,
                    carrierCleanupStatus = "failed-${throwable.javaClass.simpleName}",
                )
              }
      val systemPassthroughEnabled =
          runCatching { bindings.enableSystemPassthrough() }.getOrDefault(false)
      bindings.marker(
          SpatialProjectionPanelVisibilityModule.disabledMarker(
              source = source,
              resumeProjectionOnEnable = resumeProjectionOnEnable,
              videoWasActive = resumeVideoSettings.active,
              stopReceipt = stopReceipt,
              systemPassthroughEnabled = systemPassthroughEnabled,
          )
      )
      return enabled
    }

    enabled = true
    val systemPassthroughEnabled =
        runCatching { bindings.enableSystemPassthrough() }.getOrDefault(false)
    if (resumeProjectionOnEnable) {
      bindings.restartProjectionPanel(resumeVideoSettings, "projection-panel-toggle-on")
    }
    bindings.marker(
        SpatialProjectionPanelVisibilityModule.enabledMarker(
            source = source,
            projectionRestartRequested = resumeProjectionOnEnable,
            videoRestartRequested = resumeProjectionOnEnable && resumeVideoSettings.active,
            systemPassthroughEnabled = systemPassthroughEnabled,
        )
    )
    return enabled
  }

  companion object {
    const val MODULE_ID = "spatial-projection-panel-visibility-coordinator"
  }
}

internal object SpatialProjectionPanelVisibilityModule {
  const val MODULE_ID = "spatial-projection-panel-visibility"

  fun disabledMarker(
      source: String,
      resumeProjectionOnEnable: Boolean,
      videoWasActive: Boolean,
      stopReceipt: SpatialProjectionPanelStopReceipt,
      systemPassthroughEnabled: Boolean,
  ): String =
      "channel=projection-panel status=disabled " +
          "source=${activityMarkerToken(source)} projectionPanelEnabled=false " +
          "projectionCarrierVisible=false customProjectionEnabled=false " +
          "videoProjectionEnabled=false videoPlaybackStopped=${stopReceipt.videoProjectionStopped} " +
          "nativeProjectionStopped=${stopReceipt.nativeProjectionStopped} " +
          "carrierCleanupStatus=${activityMarkerToken(stopReceipt.carrierCleanupStatus)} " +
          "systemPassthroughEnabled=$systemPassthroughEnabled " +
          "resumeProjectionOnEnable=$resumeProjectionOnEnable videoWasActive=$videoWasActive " +
          "passthroughIsolationDiagnostic=true runtimeCrash=false"

  fun enabledMarker(
      source: String,
      projectionRestartRequested: Boolean,
      videoRestartRequested: Boolean,
      systemPassthroughEnabled: Boolean,
  ): String =
      "channel=projection-panel status=enabled " +
          "source=${activityMarkerToken(source)} projectionPanelEnabled=true " +
          "projectionCarrierVisible=true customProjectionEnabled=true " +
          "projectionRestartRequested=$projectionRestartRequested " +
          "videoRestartRequested=$videoRestartRequested " +
          "systemPassthroughEnabled=$systemPassthroughEnabled " +
          "passthroughIsolationDiagnostic=true runtimeCrash=false"

  fun unchangedMarker(enabled: Boolean, source: String): String =
      "channel=projection-panel status=unchanged " +
          "source=${activityMarkerToken(source)} projectionPanelEnabled=$enabled " +
          "passthroughIsolationDiagnostic=true runtimeCrash=false"
}
