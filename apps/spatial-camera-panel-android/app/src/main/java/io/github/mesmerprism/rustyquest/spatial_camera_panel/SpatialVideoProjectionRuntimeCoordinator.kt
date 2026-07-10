package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent

internal data class SpatialVideoProjectionRuntimeNativeState(
    val receiptLibraryLoaded: Boolean,
)

internal data class SpatialVideoProjectionRuntimeBindings(
    val nativeState: () -> SpatialVideoProjectionRuntimeNativeState,
    val configureNative: (SpatialVideoProjectionSettings) -> Long,
    val startPlayback: (SpatialVideoProjectionSettings) -> Unit,
    val stopPlayback: () -> Unit,
    val stopNativeProbe: () -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialVideoProjectionRuntimeCoordinator(
    private val bindings: SpatialVideoProjectionRuntimeBindings,
) {
  var settings = SpatialVideoProjectionSettings.disabled()
    private set

  var started = false
    private set

  fun resolveSettings(intent: Intent?): SpatialVideoProjectionSettings =
      SpatialVideoProjectionRouteModule.currentSettings(intent)

  fun markerFields(settings: SpatialVideoProjectionSettings): String =
      SpatialVideoProjectionRouteModule.markerFields(settings)

  fun adoptSettings(settings: SpatialVideoProjectionSettings) {
    this.settings = settings
  }

  fun configure(settings: SpatialVideoProjectionSettings, reason: String): Long {
    if (!bindings.nativeState().receiptLibraryLoaded) {
      bindings.marker(
          SpatialVideoProjectionRouteModule.nativeConfigureSkippedMarker(
              reason,
              settings,
          )
      )
      return 0L
    }
    val mask =
        runCatching { bindings.configureNative(settings) }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialVideoProjectionRouteModule.nativeConfigureFailedMarker(
                      reason = reason,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                      settings = settings,
                  )
              )
              return 0L
            }
    bindings.marker(SpatialVideoProjectionRouteModule.nativeConfiguredMarker(reason, mask, settings))
    return mask
  }

  fun start(settings: SpatialVideoProjectionSettings, reason: String) {
    if (!settings.active) {
      return
    }
    bindings.marker(SpatialVideoProjectionRouteModule.startRequestedMarker(reason, settings))
    bindings.startPlayback(settings)
    started = true
  }

  fun stop(reason: String) {
    if (!started && !settings.enabled) {
      return
    }
    val previousSettings = settings
    runCatching { bindings.stopPlayback() }
    if (bindings.nativeState().receiptLibraryLoaded) {
      runCatching { bindings.stopNativeProbe() }
      runCatching {
        bindings.configureNative(
            previousSettings.copy(
                enabled = false,
                path = "",
            )
        )
      }
    }
    started = false
    settings = SpatialVideoProjectionSettings.disabled()
    bindings.marker(SpatialVideoProjectionRouteModule.stoppedMarker(reason, previousSettings))
  }

  companion object {
    const val MODULE_ID = "spatial-video-projection-runtime-coordinator"
  }
}
