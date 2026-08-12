package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent

internal data class SpatialVideoProjectionRuntimeNativeState(
    val receiptLibraryLoaded: Boolean,
)

internal data class SpatialVideoProjectionRuntimeBindings(
    val nativeState: () -> SpatialVideoProjectionRuntimeNativeState,
    val configureNative: (SpatialVideoProjectionSettings) -> Long,
    val startPlayback: (SpatialVideoProjectionSettings, OfflineImmersiveMediaPack?) -> Boolean,
    val stopPlayback: () -> Boolean,
    val stopNativeProbe: () -> Unit,
    val marker: (String) -> Unit,
)

internal data class SpatialVideoProjectionSourceSwitchResult(
    val applied: Boolean,
    val decoderStarted: Boolean,
)

internal class SpatialVideoProjectionRuntimeCoordinator(
    private val bindings: SpatialVideoProjectionRuntimeBindings,
) {
  var settings = SpatialVideoProjectionSettings.disabled()
    private set

  var started = false
    private set
  private var offlinePack: OfflineImmersiveMediaPack? = null
  private var readableVideoConsumerRequired = true

  fun resolveSettings(intent: Intent?): SpatialVideoProjectionSettings =
      SpatialVideoProjectionRouteModule.currentSettings(intent)

  fun markerFields(settings: SpatialVideoProjectionSettings): String =
      SpatialVideoProjectionRouteModule.markerFields(settings)

  fun adoptSettings(
      settings: SpatialVideoProjectionSettings,
      offlinePack: OfflineImmersiveMediaPack? = null,
  ) {
    this.settings = settings
    this.offlinePack = offlinePack
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
    if (!readableVideoConsumerRequired) {
      started = false
      bindings.marker(
          "channel=spatial-video-projection status=decoder-start-skipped " +
              "reason=${activityMarkerToken(reason)} readableVideoConsumerRequired=false " +
              "visualContribution=false activeDecoderCount=0 decoderOverlap=false " +
              "zeroContributionDecodeWorkSkipped=true"
      )
      return
    }
    bindings.marker(SpatialVideoProjectionRouteModule.startRequestedMarker(reason, settings))
    started = runCatching { bindings.startPlayback(settings, offlinePack) }.getOrDefault(false)
    bindings.marker(
        "channel=spatial-video-projection status=decoder-start-result " +
            "reason=${activityMarkerToken(reason)} started=$started " +
            "activeDecoderCount=${if (started) 1 else 0} decoderOverlap=false"
    )
  }

  fun replaceMediaSource(
      settings: SpatialVideoProjectionSettings,
      offlinePack: OfflineImmersiveMediaPack?,
      reason: String,
  ): SpatialVideoProjectionSourceSwitchResult {
    if (!readableVideoConsumerRequired && settings.active) {
      val previousStopped =
          if (started) runCatching { bindings.stopPlayback() }.getOrDefault(false) else true
      if (!previousStopped) {
        bindings.marker(
            "channel=spatial-video-projection status=source-switch-rejected " +
                "reason=${activityMarkerToken(reason)} decoderHandoffComplete=false " +
                "oldDecoderMayBeActive=true newDecoderStarted=false decoderOverlapPrevented=true"
        )
        return SpatialVideoProjectionSourceSwitchResult(applied = false, decoderStarted = false)
      }
      this.settings = settings
      this.offlinePack = offlinePack
      configure(settings, "$reason-source-switch")
      started = false
      bindings.marker(
          "channel=spatial-video-projection status=source-switch-applied " +
              "reason=${activityMarkerToken(reason)} mediaDecoderRestarted=false " +
              "readableVideoConsumerRequired=false visualContribution=false " +
              "oldDecoderStoppedBeforeNew=true newDecoderStarted=false decoderOverlap=false " +
              "zeroContributionDecodeWorkSkipped=true customProjectionCarrierRetained=true " +
              "projectionEntityRestarted=false customProjectionStackRestarted=false " +
              "cameraRuntimeRestarted=false activityRestarted=false ${markerFields(settings)}"
      )
      return SpatialVideoProjectionSourceSwitchResult(applied = true, decoderStarted = false)
    }
    if (!started || !settings.active) {
      bindings.marker(
          "channel=spatial-video-projection status=source-switch-rejected " +
              "reason=${activityMarkerToken(reason)} projectionStarted=$started " +
              "sourceActive=${settings.active} activityRestarted=false"
      )
      return SpatialVideoProjectionSourceSwitchResult(applied = false, decoderStarted = false)
    }
    val previousStopped = runCatching { bindings.stopPlayback() }.getOrDefault(false)
    if (!previousStopped) {
      bindings.marker(
          "channel=spatial-video-projection status=source-switch-rejected " +
              "reason=${activityMarkerToken(reason)} decoderHandoffComplete=false " +
              "oldDecoderMayBeActive=true newDecoderStarted=false decoderOverlapPrevented=true"
      )
      return SpatialVideoProjectionSourceSwitchResult(applied = false, decoderStarted = false)
    }
    this.settings = settings
    this.offlinePack = offlinePack
    configure(settings, "$reason-source-switch")
    val replacementStarted =
        runCatching { bindings.startPlayback(settings, offlinePack) }.getOrDefault(false)
    started = replacementStarted
    bindings.marker(
        "channel=spatial-video-projection status=source-switch-applied " +
            "reason=${activityMarkerToken(reason)} mediaDecoderRestarted=$replacementStarted " +
            "decoderHandoffComplete=true oldDecoderStoppedBeforeNew=true " +
            "newDecoderStarted=$replacementStarted decoderOverlap=false " +
            "customProjectionCarrierRetained=true projectionEntityRestarted=false " +
            "customProjectionStackRestarted=false cameraRuntimeRestarted=false " +
            "activityRestarted=false ${markerFields(settings)}"
    )
    return SpatialVideoProjectionSourceSwitchResult(
        applied = true,
        decoderStarted = replacementStarted,
    )
  }

  fun updateReadableVideoConsumer(required: Boolean, reason: String) {
    val previousRequired = readableVideoConsumerRequired
    readableVideoConsumerRequired = required
    if (!required) {
      val playbackStopped =
          if (started) runCatching { bindings.stopPlayback() }.getOrDefault(false) else true
      if (playbackStopped) {
        started = false
      }
      bindings.marker(
          "channel=spatial-video-projection status=consumer-policy-applied " +
              "reason=${activityMarkerToken(reason)} previousReadableVideoConsumerRequired=$previousRequired " +
              "readableVideoConsumerRequired=false playbackStopped=$playbackStopped " +
              "visualContribution=false activeDecoderCount=${if (started) 1 else 0} " +
              "zeroContributionDecodeWorkSkipped=${!started} decoderOverlap=false"
      )
      return
    }

    val shouldStart = !previousRequired && !started && settings.active
    if (shouldStart) {
      start(settings, "$reason-consumer-required")
    }
    bindings.marker(
        "channel=spatial-video-projection status=consumer-policy-applied " +
            "reason=${activityMarkerToken(reason)} previousReadableVideoConsumerRequired=$previousRequired " +
            "readableVideoConsumerRequired=true decoderStartRequested=$shouldStart " +
            "visualContribution=true activeDecoderCount=${if (started) 1 else 0} decoderOverlap=false"
    )
  }

  fun prepareForCarrierRebuild(
      settings: SpatialVideoProjectionSettings,
      offlinePack: OfflineImmersiveMediaPack?,
      reason: String,
  ) {
    if (started) {
      runCatching { bindings.stopPlayback() }
    }
    started = false
    adoptSettings(settings, offlinePack)
    bindings.marker(
        "channel=spatial-video-projection status=carrier-rebuild-prepared " +
            "reason=${activityMarkerToken(reason)} playbackStopped=true " +
            "activityRestarted=false ${markerFields(settings)}"
    )
  }

  fun stop(reason: String) {
    if (!started && !settings.enabled) {
      return
    }
    val previousSettings = settings
    val playbackStopped = runCatching { bindings.stopPlayback() }.getOrDefault(false)
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
    offlinePack = null
    readableVideoConsumerRequired = true
    bindings.marker(SpatialVideoProjectionRouteModule.stoppedMarker(reason, previousSettings))
    bindings.marker(
        "channel=spatial-video-projection status=decoder-release-result " +
            "reason=${activityMarkerToken(reason)} playbackStopped=$playbackStopped " +
            "visualContribution=false newDecoderStarted=false decoderOverlap=false"
    )
  }

  companion object {
    const val MODULE_ID = "spatial-video-projection-runtime-coordinator"
  }
}
