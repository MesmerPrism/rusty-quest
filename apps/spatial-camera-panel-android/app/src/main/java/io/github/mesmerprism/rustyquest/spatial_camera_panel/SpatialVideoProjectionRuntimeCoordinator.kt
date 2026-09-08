package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent

internal data class SpatialVideoProjectionRuntimeNativeState(
    val receiptLibraryLoaded: Boolean,
)

internal enum class SpatialVideoProjectionDecoderState(val token: String) {
  Stopped("stopped"),
  Starting("starting"),
  Effective("effective"),
  Failed("failed"),
}

internal enum class SpatialVideoProjectionStartupDisposition(val token: String) {
  Inactive("inactive"),
  ProjectionHiddenDirect("projection-hidden-direct"),
  ZeroDemandSkipped("zero-demand-skipped"),
  DecoderDispatched("decoder-dispatched"),
  DecoderFailed("decoder-failed"),
}

internal data class SpatialVideoProjectionStartupOwnership(
    val disposition: SpatialVideoProjectionStartupDisposition,
    val directVideoConsumerRequired: Boolean,
)

internal data class SpatialVideoProjectionPlaybackCallbacks(
    val onFirstFrame: () -> Unit,
    val onError: (String) -> Unit,
    val onStopped: () -> Unit,
)

internal data class SpatialVideoProjectionRuntimeBindings(
    val nativeState: () -> SpatialVideoProjectionRuntimeNativeState,
    val configureNative: (SpatialVideoProjectionSettings) -> Long,
    val startPlayback:
        (SpatialVideoProjectionSettings,
            OfflineImmersiveMediaPack?,
            SpatialVideoProjectionPlaybackCallbacks) -> Boolean,
    val stopPlayback: () -> Boolean,
    val stopNativeProbe: () -> Unit,
    val marker: (String) -> Unit,
    val dispatchDecoderLifecycle: ((() -> Unit) -> Unit) = { action -> action() },
    val onDecoderStateChanged: (SpatialVideoProjectionDecoderState, String) -> Unit = { _, _ -> },
)

internal data class SpatialVideoProjectionSourceSwitchResult(
    val applied: Boolean,
    val decoderStarted: Boolean,
    val decoderEffective: Boolean = false,
    val sourceGeneration: Long = 0L,
)

internal class SpatialVideoProjectionRuntimeCoordinator(
    private val bindings: SpatialVideoProjectionRuntimeBindings,
) {
  var settings = SpatialVideoProjectionSettings.disabled()
    private set

  @Volatile var decoderState = SpatialVideoProjectionDecoderState.Stopped
    private set
  val started: Boolean
    get() = decoderState == SpatialVideoProjectionDecoderState.Effective
  @Volatile private var decoderDispatched = false
  val decoderActive: Boolean
    get() = decoderDispatched
  private var offlinePack: OfflineImmersiveMediaPack? = null
  @Volatile private var readableVideoConsumerRequired = true
  private var sourceGeneration = 0L
  private val consumerLifecycleLock = Any()
  private var consumerLifecycleGeneration = 0L
  private var playbackGeneration = 0L

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
    SpatialLaunchQualificationTelemetry.recordSettings(settings)
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

  private fun beginPlayback(
      requestedSettings: SpatialVideoProjectionSettings,
      requestedOfflinePack: OfflineImmersiveMediaPack?,
      reason: String,
      generation: Long,
  ): Boolean {
    val canStart =
        synchronized(consumerLifecycleLock) {
          if (playbackGeneration != generation ||
              !readableVideoConsumerRequired ||
              settings != requestedSettings) {
            false
          } else {
            decoderDispatched = true
            decoderState = SpatialVideoProjectionDecoderState.Starting
            true
          }
        }
    if (!canStart) return false
    bindings.onDecoderStateChanged(SpatialVideoProjectionDecoderState.Starting, reason)
    bindings.marker(
        "channel=spatial-video-projection status=decoder-lifecycle-state " +
            "reason=${activityMarkerToken(reason)} playbackGeneration=$generation " +
            "decoderState=starting decoderDispatched=true decoderEffective=false"
    )
    val callbacks =
        SpatialVideoProjectionPlaybackCallbacks(
            onFirstFrame = {
              transitionDecoderStateForGeneration(
                  generation,
                  requestedSettings,
                  SpatialVideoProjectionDecoderState.Effective,
                  "$reason-first-frame",
              )
            },
            onError = { failure ->
              transitionDecoderStateForGeneration(
                  generation,
                  requestedSettings,
                  SpatialVideoProjectionDecoderState.Failed,
                  "$reason-${activityMarkerToken(failure)}",
              )
            },
            onStopped = {
              transitionDecoderStateForGeneration(
                  generation,
                  requestedSettings,
                  SpatialVideoProjectionDecoderState.Stopped,
                  "$reason-stopped",
              )
            },
        )
    val dispatched =
        runCatching { bindings.startPlayback(requestedSettings, requestedOfflinePack, callbacks) }
            .getOrDefault(false)
    if (!dispatched) {
      transitionDecoderStateForGeneration(
          generation,
          requestedSettings,
          SpatialVideoProjectionDecoderState.Failed,
          "$reason-dispatch-failed",
      )
    }
    return synchronized(consumerLifecycleLock) {
      playbackGeneration == generation && decoderDispatched
    }
  }

  private fun transitionDecoderStateForGeneration(
      generation: Long,
      requestedSettings: SpatialVideoProjectionSettings,
      state: SpatialVideoProjectionDecoderState,
      reason: String,
  ) {
    val applied =
        synchronized(consumerLifecycleLock) {
          if (playbackGeneration != generation || settings != requestedSettings) {
            false
          } else {
            decoderState = state
            decoderDispatched =
                state == SpatialVideoProjectionDecoderState.Starting ||
                    state == SpatialVideoProjectionDecoderState.Effective
            true
          }
        }
    if (!applied) {
      bindings.marker(
          "channel=spatial-video-projection status=decoder-lifecycle-stale-skipped " +
              "reason=${activityMarkerToken(reason)} playbackGeneration=$generation " +
              "decoderState=${state.token}"
      )
      return
    }
    bindings.marker(
        "channel=spatial-video-projection status=decoder-lifecycle-state " +
            "reason=${activityMarkerToken(reason)} playbackGeneration=$generation " +
            "decoderState=${state.token} decoderDispatched=$decoderDispatched " +
            "decoderEffective=$started"
    )
    bindings.onDecoderStateChanged(state, reason)
  }

  fun start(settings: SpatialVideoProjectionSettings, reason: String) {
    startWithDisposition(settings, reason)
  }

  private fun startWithDisposition(
      settings: SpatialVideoProjectionSettings,
      reason: String,
  ): SpatialVideoProjectionStartupDisposition {
    if (!settings.active) {
      return SpatialVideoProjectionStartupDisposition.Inactive
    }
    if (!readableVideoConsumerRequired) {
      decoderDispatched = false
      decoderState = SpatialVideoProjectionDecoderState.Stopped
      bindings.marker(
          "channel=spatial-video-projection status=decoder-start-skipped " +
              "reason=${activityMarkerToken(reason)} readableVideoConsumerRequired=false " +
              "visualContribution=false activeDecoderCount=0 decoderOverlap=false " +
              "zeroContributionDecodeWorkSkipped=true"
      )
      return SpatialVideoProjectionStartupDisposition.ZeroDemandSkipped
    }
    val generation =
        synchronized(consumerLifecycleLock) {
          consumerLifecycleGeneration += 1L
          playbackGeneration += 1L
          playbackGeneration
        }
    bindings.marker(SpatialVideoProjectionRouteModule.startRequestedMarker(reason, settings))
    val dispatched = beginPlayback(settings, offlinePack, reason, generation)
    bindings.marker(
        "channel=spatial-video-projection status=decoder-start-result " +
            "reason=${activityMarkerToken(reason)} decoderDispatched=$dispatched " +
            "decoderState=${decoderState.token} decoderEffective=$started " +
            "activeDecoderCount=${if (decoderDispatched) 1 else 0} decoderOverlap=false"
    )
    return if (dispatched) {
      SpatialVideoProjectionStartupDisposition.DecoderDispatched
    } else {
      SpatialVideoProjectionStartupDisposition.DecoderFailed
    }
  }

  fun startForComposedOwnership(
      settings: SpatialVideoProjectionSettings,
      projectionPanelVisible: Boolean,
      reason: String,
  ): SpatialVideoProjectionStartupOwnership {
    if (settings.active && !projectionPanelVisible) {
      return SpatialVideoProjectionStartupOwnership(
          disposition = SpatialVideoProjectionStartupDisposition.ProjectionHiddenDirect,
          directVideoConsumerRequired = true,
      )
    }
    val disposition = startWithDisposition(settings, reason)
    return SpatialVideoProjectionStartupOwnership(
        disposition = disposition,
        directVideoConsumerRequired =
            disposition == SpatialVideoProjectionStartupDisposition.DecoderFailed,
    )
  }

  fun replaceMediaSource(
      settings: SpatialVideoProjectionSettings,
      offlinePack: OfflineImmersiveMediaPack?,
      reason: String,
  ): SpatialVideoProjectionSourceSwitchResult {
    if (!readableVideoConsumerRequired && settings.active) {
      val previousStopped =
          if (decoderDispatched) runCatching { bindings.stopPlayback() }.getOrDefault(false) else true
      if (!previousStopped) {
        bindings.marker(
            "channel=spatial-video-projection status=source-switch-rejected " +
                "reason=${activityMarkerToken(reason)} decoderHandoffComplete=false " +
                "oldDecoderMayBeActive=true newDecoderStarted=false decoderOverlapPrevented=true"
        )
        return SpatialVideoProjectionSourceSwitchResult(applied = false, decoderStarted = false)
      }
      adoptSettings(settings, offlinePack)
      configure(settings, "$reason-source-switch")
      sourceGeneration += 1L
      decoderDispatched = false
      decoderState = SpatialVideoProjectionDecoderState.Stopped
      bindings.marker(
          "channel=spatial-video-projection status=source-switch-applied " +
              "reason=${activityMarkerToken(reason)} mediaDecoderRestarted=false " +
              "readableVideoConsumerRequired=false visualContribution=false " +
              "oldDecoderStoppedBeforeNew=true newDecoderStarted=false decoderOverlap=false " +
              "zeroContributionDecodeWorkSkipped=true customProjectionCarrierRetained=true " +
              "projectionEntityRestarted=false customProjectionStackRestarted=false " +
              "cameraRuntimeRestarted=false activityRestarted=false ${markerFields(settings)}"
      )
      return SpatialVideoProjectionSourceSwitchResult(
          applied = true,
          decoderStarted = false,
          sourceGeneration = sourceGeneration,
      )
    }
    if (!settings.active) {
      bindings.marker(
          "channel=spatial-video-projection status=source-switch-rejected " +
              "reason=${activityMarkerToken(reason)} projectionStarted=$started " +
              "sourceActive=${settings.active} activityRestarted=false"
      )
      return SpatialVideoProjectionSourceSwitchResult(applied = false, decoderStarted = false)
    }
    if (!decoderDispatched) {
      adoptSettings(settings, offlinePack)
      configure(settings, "$reason-source-switch")
      sourceGeneration += 1L
      start(settings, "$reason-source-switch")
      bindings.marker(
          "channel=spatial-video-projection status=source-switch-applied " +
              "reason=${activityMarkerToken(reason)} mediaDecoderRestarted=$decoderDispatched " +
              "decoderHandoffComplete=true oldDecoderStoppedBeforeNew=true " +
              "newDecoderStarted=$decoderDispatched decoderEffective=$started " +
              "decoderOverlap=false sourceGeneration=$sourceGeneration " +
              "stereoLayoutGenerationAtomic=true eyeCropGenerationAtomic=true " +
              "customProjectionCarrierRetained=true projectionEntityRestarted=false " +
              "customProjectionStackRestarted=false cameraRuntimeRestarted=false " +
              "activityRestarted=false ${markerFields(settings)}"
      )
      return SpatialVideoProjectionSourceSwitchResult(
          applied = true,
          decoderStarted = decoderDispatched,
          decoderEffective = started,
          sourceGeneration = sourceGeneration,
      )
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
    synchronized(consumerLifecycleLock) {
      consumerLifecycleGeneration += 1L
      playbackGeneration += 1L
      decoderDispatched = false
      decoderState = SpatialVideoProjectionDecoderState.Stopped
    }
    adoptSettings(settings, offlinePack)
    configure(settings, "$reason-source-switch")
    sourceGeneration += 1L
    val lifecycleGeneration =
        synchronized(consumerLifecycleLock) {
          consumerLifecycleGeneration += 1L
          playbackGeneration += 1L
          playbackGeneration
        }
    val replacementStarted =
        beginPlayback(settings, offlinePack, "$reason-source-switch", lifecycleGeneration)
    bindings.marker(
        "channel=spatial-video-projection status=source-switch-applied " +
            "reason=${activityMarkerToken(reason)} mediaDecoderRestarted=$replacementStarted " +
            "decoderHandoffComplete=true oldDecoderStoppedBeforeNew=true " +
            "newDecoderStarted=$replacementStarted decoderEffective=$started decoderOverlap=false " +
            "sourceGeneration=$sourceGeneration stereoLayoutGenerationAtomic=true " +
            "eyeCropGenerationAtomic=true " +
            "customProjectionCarrierRetained=true projectionEntityRestarted=false " +
            "customProjectionStackRestarted=false cameraRuntimeRestarted=false " +
            "activityRestarted=false ${markerFields(settings)}"
    )
    return SpatialVideoProjectionSourceSwitchResult(
        applied = true,
        decoderStarted = replacementStarted,
        decoderEffective = started,
        sourceGeneration = sourceGeneration,
    )
  }

  fun updateReadableVideoConsumer(required: Boolean, reason: String) {
    val transition =
        synchronized(consumerLifecycleLock) {
          val previousRequired = readableVideoConsumerRequired
          readableVideoConsumerRequired = required
          consumerLifecycleGeneration += 1L
          Triple(previousRequired, consumerLifecycleGeneration, settings)
        }
    val previousRequired = transition.first
    val generation = transition.second
    val requestedSettings = transition.third
    if (!required) {
      bindings.marker(
          "channel=spatial-video-projection status=consumer-policy-requested " +
              "reason=${activityMarkerToken(reason)} previousReadableVideoConsumerRequired=$previousRequired " +
              "readableVideoConsumerRequired=false lifecycleGeneration=$generation " +
              "uiThreadBlocked=false visualContribution=false"
      )
      bindings.dispatchDecoderLifecycle {
        val stillRequested =
            synchronized(consumerLifecycleLock) {
              consumerLifecycleGeneration == generation && !readableVideoConsumerRequired
            }
        if (!stillRequested) {
          bindings.marker(
              "channel=spatial-video-projection status=consumer-policy-stale-skipped " +
                  "reason=${activityMarkerToken(reason)} lifecycleGeneration=$generation"
          )
          return@dispatchDecoderLifecycle
        }
        val playbackStopped =
            if (decoderDispatched) runCatching { bindings.stopPlayback() }.getOrDefault(false)
            else true
        if (playbackStopped) {
          synchronized(consumerLifecycleLock) {
            playbackGeneration += 1L
            decoderDispatched = false
            decoderState = SpatialVideoProjectionDecoderState.Stopped
          }
          bindings.onDecoderStateChanged(SpatialVideoProjectionDecoderState.Stopped, reason)
        }
        bindings.marker(
            "channel=spatial-video-projection status=consumer-policy-applied " +
                "reason=${activityMarkerToken(reason)} readableVideoConsumerRequired=false " +
                "lifecycleGeneration=$generation playbackStopped=$playbackStopped " +
                "visualContribution=false activeDecoderCount=${if (decoderDispatched) 1 else 0} " +
                "zeroContributionDecodeWorkSkipped=${!decoderDispatched} decoderOverlap=false"
        )
      }
      return
    }

    val shouldStart = !decoderDispatched && settings.active
    if (shouldStart) {
      val requestedOfflinePack = offlinePack
      bindings.dispatchDecoderLifecycle {
        val stillRequested =
            synchronized(consumerLifecycleLock) {
              consumerLifecycleGeneration == generation &&
                  readableVideoConsumerRequired &&
                  settings == requestedSettings
            }
        if (!stillRequested) {
          bindings.marker(
              "channel=spatial-video-projection status=consumer-policy-stale-skipped " +
                  "reason=${activityMarkerToken(reason)} lifecycleGeneration=$generation"
          )
          return@dispatchDecoderLifecycle
        }
        bindings.marker(
            SpatialVideoProjectionRouteModule.startRequestedMarker(
                "$reason-consumer-required",
                requestedSettings,
            )
        )
        val decoderGeneration =
            synchronized(consumerLifecycleLock) {
              playbackGeneration += 1L
              playbackGeneration
            }
        val playbackStarted =
            beginPlayback(
                requestedSettings,
                requestedOfflinePack,
                "$reason-consumer-required",
                decoderGeneration,
            )
        val retainStarted =
            synchronized(consumerLifecycleLock) {
              consumerLifecycleGeneration == generation &&
                  readableVideoConsumerRequired &&
                  settings == requestedSettings
            }
        if (playbackStarted && !retainStarted) {
          runCatching { bindings.stopPlayback() }
          decoderDispatched = false
          decoderState = SpatialVideoProjectionDecoderState.Stopped
        }
        bindings.marker(
            "channel=spatial-video-projection status=decoder-start-result " +
                "reason=${activityMarkerToken(reason)} decoderDispatched=$decoderDispatched " +
                "decoderState=${decoderState.token} decoderEffective=$started " +
                "lifecycleGeneration=$generation uiThreadBlocked=false " +
                "activeDecoderCount=${if (decoderDispatched) 1 else 0} decoderOverlap=false"
        )
      }
    }
    bindings.marker(
        "channel=spatial-video-projection status=consumer-policy-requested " +
            "reason=${activityMarkerToken(reason)} previousReadableVideoConsumerRequired=$previousRequired " +
            "readableVideoConsumerRequired=true decoderStartRequested=$shouldStart " +
            "lifecycleGeneration=$generation uiThreadBlocked=false visualContribution=true " +
            "activeDecoderCount=${if (decoderDispatched) 1 else 0} decoderOverlap=false"
    )
  }

  fun prepareForCarrierRebuild(
      settings: SpatialVideoProjectionSettings,
      offlinePack: OfflineImmersiveMediaPack?,
      reason: String,
  ) {
    if (decoderDispatched) {
      runCatching { bindings.stopPlayback() }
    }
    synchronized(consumerLifecycleLock) {
      playbackGeneration += 1L
      decoderDispatched = false
      decoderState = SpatialVideoProjectionDecoderState.Stopped
    }
    adoptSettings(settings, offlinePack)
    bindings.marker(
        "channel=spatial-video-projection status=carrier-rebuild-prepared " +
            "reason=${activityMarkerToken(reason)} playbackStopped=true " +
            "activityRestarted=false ${markerFields(settings)}"
    )
  }

  fun stop(reason: String) {
    synchronized(consumerLifecycleLock) {
      consumerLifecycleGeneration += 1L
      playbackGeneration += 1L
      readableVideoConsumerRequired = false
    }
    if (!decoderDispatched && !settings.enabled) {
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
    decoderDispatched = false
    decoderState = SpatialVideoProjectionDecoderState.Stopped
    settings = SpatialVideoProjectionSettings.disabled()
    SpatialLaunchQualificationTelemetry.recordSettings(settings)
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
