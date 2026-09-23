package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialCameraHwbProjectionDepthPrerequisiteNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialCameraHwbProjectionDepthPrerequisiteBindings(
    val routeActive: () -> Boolean,
    val environmentDepthOwner: () -> SpatialEnvironmentDepthOwner,
    val nativeState: () -> SpatialCameraHwbProjectionDepthPrerequisiteNativeState,
    val captureInteropProbe: () -> SpatialNativeInteropProbe,
    val requiredOpenXrExtensions: () -> String,
    val projectionEntityPresent: () -> Boolean,
    val startNativePassthrough: (Long, Long, Long) -> Long,
    val stopNativePassthrough: () -> Long,
    val startNativeEnvironmentDepth: (Long, Long, Long) -> Long,
    val stopNativeEnvironmentDepth: () -> Long,
    val updateSdkEnvironmentDepthDemand: (Boolean, String) -> Unit = { _, _ -> },
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionDepthPrerequisiteCoordinator(
    private val bindings: SpatialCameraHwbProjectionDepthPrerequisiteBindings,
) {
  private var nativePassthroughStartMask = 0L
  private var environmentDepthStartMask = 0L
  private var environmentDepthConsumerRequired = false
  @Volatile
  private var environmentDepthRecoveryPolicy = SpatialEnvironmentDepthRecoveryPolicy.Bounded
  @Volatile
  private var environmentDepthLastValidFrameObserved = false
  @Volatile
  private var environmentDepthLastRecoverableError = "none"
  private var environmentDepthRecoverableErrorCount = 0L

  fun environmentDepthUnavailableWarning(): String? =
      when {
        environmentDepthLastRecoverableError == "none" -> null
        environmentDepthLastValidFrameObserved ->
            "Depth acquisition is recovering in ${environmentDepthRecoveryPolicy.panelLabel}. Final keeps the last valid world-depth frame while fresh acquisition retries continue."
        else ->
            "Depth acquisition is recovering in ${environmentDepthRecoveryPolicy.panelLabel}, but no valid world-depth frame has arrived yet. Final temporarily uses neutral world depth."
      }

  fun environmentDepthRecoveryPolicy(): SpatialEnvironmentDepthRecoveryPolicy =
      environmentDepthRecoveryPolicy

  fun updateEnvironmentDepthRecoveryPolicy(
      policy: SpatialEnvironmentDepthRecoveryPolicy,
      source: String,
  ): SpatialEnvironmentDepthRecoveryPolicy {
    val previous = environmentDepthRecoveryPolicy
    environmentDepthRecoveryPolicy = policy
    bindings.marker(
        "channel=spatial-environment-depth status=recovery-policy-applied " +
            "source=${activityMarkerToken(source)} previousEnvironmentDepthRecoveryPolicy=${previous.markerToken} " +
            "environmentDepthRecoveryPolicy=${policy.markerToken} " +
            "environmentDepthAcquireDuplicateFrameSuppression=${policy == SpatialEnvironmentDepthRecoveryPolicy.Bounded} " +
            "environmentDepthRecoveryCadence=${if (policy == SpatialEnvironmentDepthRecoveryPolicy.Bounded) "bounded-backoff" else "every-positive-ecs-tick"} " +
            "environmentDepthLastValidRetention=true runtimeCrash=false"
    )
    return policy
  }

  fun startPassthrough(source: String): Long {
    if (!bindings.routeActive()) {
      return 0L
    }
    if (!environmentDepthConsumerRequired) {
      bindings.marker(
          "channel=spatial-native-passthrough status=start-skipped " +
              "source=${activityMarkerToken(source)} environmentDepthConsumerRequired=false " +
              "nativePassthroughRequested=false nativePassthroughLayerActive=false " +
              "systemPassthroughConflictAvoided=true zeroContributionWorkSkipped=true runtimeCrash=false"
      )
      nativePassthroughStartMask = 0L
      return 0L
    }
    if (bindings.environmentDepthOwner() == SpatialEnvironmentDepthOwner.SpatialSdkApiLayer) {
      bindings.marker(
          "channel=spatial-environment-depth status=native-passthrough-skipped " +
              "source=${activityMarkerToken(source)} environmentDepthOwner=spatial-sdk-api-layer " +
              "passthroughOwner=spatial-sdk-reason-aggregator nativeFbPassthroughStarted=false runtimeCrash=false"
      )
      return 0L
    }
    if (SpatialOpenXrRouteModule.nativePassthroughLayerActive(nativePassthroughStartMask)) {
      bindings.marker(
          "channel=spatial-native-passthrough status=start-retained " +
              "source=${activityMarkerToken(source)} environmentDepthConsumerRequired=true " +
              "nativePassthroughRequested=true nativePassthroughLayerActive=true " +
              "nativePassthroughStartMask=$nativePassthroughStartMask runtimeCrash=false"
      )
      return nativePassthroughStartMask
    }
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          SpatialOpenXrRouteModule.nativePassthroughLibraryUnavailableMarker(
              source,
              nativeState.receiptLibraryError,
          )
      )
      return 0L
    }
    val probe = captureInteropProbe()
    val requiredOpenXrExtensions = bindings.requiredOpenXrExtensions()
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      bindings.marker(
          SpatialOpenXrRouteModule.nativePassthroughDeferredMarker(
              source,
              probe,
              requiredOpenXrExtensions,
          )
      )
      return 0L
    }
    val mask =
        runCatching {
              bindings.startNativePassthrough(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialOpenXrRouteModule.nativePassthroughStartCallFailedMarker(
                      source,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                      requiredOpenXrExtensions,
                  )
              )
              0L
            }
    nativePassthroughStartMask = mask
    bindings.marker(
        SpatialOpenXrRouteModule.nativePassthroughStartRequestedMarker(
            source,
            mask,
            probe,
            bindings.projectionEntityPresent(),
            requiredOpenXrExtensions,
        )
    )
    return mask
  }

  fun startEnvironmentDepth(source: String): Long {
    if (!environmentDepthConsumerRequired) {
      bindings.marker(
          "channel=spatial-environment-depth status=start-skipped " +
              "source=${activityMarkerToken(source)} environmentDepthConsumerRequired=false " +
              "environmentDepthProviderRequested=false environmentDepthProviderState=stopped " +
              "environmentDepthZeroContributionWorkSkipped=true runtimeCrash=false"
      )
      environmentDepthStartMask = 0L
      return 0L
    }
    val owner = bindings.environmentDepthOwner()
    if (owner == SpatialEnvironmentDepthOwner.SpatialSdkApiLayer) {
      bindings.marker(
          "channel=spatial-environment-depth status=start-delegated " +
              "source=${activityMarkerToken(source)} environmentDepthOwner=spatial-sdk-api-layer " +
              "legacyEnvironmentDepthProviderRequested=false environmentDepthProviderRequested=true " +
              "environmentDepthProviderState=sdk-texture-only-requested exclusiveDepthOwner=true " +
              "environmentDepthConsumerUnavailable=false environmentDepthZeroContributionWorkSkipped=false " +
              "runtimeCrash=false"
      )
      environmentDepthStartMask = 0L
      return 0L
    }
    if (!owner.ownsLegacyProvider) {
      bindings.marker(
          "channel=spatial-environment-depth status=start-skipped " +
              "source=${activityMarkerToken(source)} environmentDepthOwner=${owner.markerToken} " +
              "legacyEnvironmentDepthProviderRequested=false " +
              "environmentDepthProviderState=stopped exclusiveDepthOwner=true " +
              "environmentDepthConsumerUnavailable=true " +
              "environmentDepthZeroContributionWorkSkipped=true runtimeCrash=false"
      )
      environmentDepthStartMask = 0L
      return 0L
    }
    if (SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(
        environmentDepthStartMask
    )) {
      bindings.marker(
          "channel=spatial-environment-depth status=start-retained " +
              "source=${activityMarkerToken(source)} environmentDepthConsumerRequired=true " +
              "environmentDepthProviderRequested=true environmentDepthProviderState=provider-running " +
              "environmentDepthStartMask=$environmentDepthStartMask runtimeCrash=false"
      )
      return environmentDepthStartMask
    }
    if (!bindings.routeActive()) {
      environmentDepthStartMask = 0L
      return 0L
    }
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          SpatialOpenXrRouteModule.spatialEnvironmentDepthLibraryUnavailableMarker(
              source,
              nativeState.receiptLibraryError,
          )
      )
      environmentDepthStartMask = 0L
      return 0L
    }
    val probe = captureInteropProbe()
    val requiredOpenXrExtensions = bindings.requiredOpenXrExtensions()
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      bindings.marker(
          SpatialOpenXrRouteModule.spatialEnvironmentDepthDeferredMarker(
              source,
              probe,
              requiredOpenXrExtensions,
          )
      )
      environmentDepthStartMask = 0L
      return 0L
    }
    val mask =
        runCatching {
              bindings.startNativeEnvironmentDepth(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialOpenXrRouteModule.spatialEnvironmentDepthStartCallFailedMarker(
                      source,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                      requiredOpenXrExtensions,
                  )
              )
              0L
            }
    environmentDepthStartMask = mask
    bindings.marker(
        SpatialOpenXrRouteModule.spatialEnvironmentDepthStartRequestedMarker(
            source,
            mask,
            probe,
            requiredOpenXrExtensions,
            environmentDepthRecoveryPolicy,
        )
    )
    return mask
  }

  fun updateEnvironmentDepthConsumer(required: Boolean, source: String) {
    val previousRequired = environmentDepthConsumerRequired
    environmentDepthConsumerRequired = required
    val owner = bindings.environmentDepthOwner()
    if (owner == SpatialEnvironmentDepthOwner.SpatialSdkApiLayer) {
      bindings.updateSdkEnvironmentDepthDemand(required, source)
    }
    if (required) {
      if (owner.ownsLegacyProvider) {
        startPassthrough(source)
      }
      val mask = startEnvironmentDepth(source)
      bindings.marker(
          "channel=spatial-environment-depth status=consumer-policy-applied " +
              "source=${activityMarkerToken(source)} previousEnvironmentDepthConsumerRequired=$previousRequired " +
              "environmentDepthConsumerRequired=true environmentDepthProviderRequested=true " +
              "environmentDepthProviderState=${if (owner == SpatialEnvironmentDepthOwner.SpatialSdkApiLayer) "sdk-texture-only-requested" else if (SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(mask)) "provider-running" else "deferred"} " +
              "environmentDepthOwner=${owner.markerToken} " +
              "environmentDepthRecoveryPolicy=${environmentDepthRecoveryPolicy.markerToken} " +
              "environmentDepthAcquisitionQuarantined=false " +
              "environmentDepthLastValidRetention=true environmentDepthFallbackBinding=runtime-depth-or-neutral " +
              "environmentDepthZeroContributionWorkSkipped=false runtimeCrash=false"
      )
      return
    }

    val stopRequested = environmentDepthStartMask != 0L
    val stopMask =
        if (stopRequested &&
            bindings.environmentDepthOwner().ownsLegacyProvider &&
            bindings.nativeState().receiptLibraryLoaded) {
          runCatching { bindings.stopNativeEnvironmentDepth() }.getOrDefault(0L)
        } else {
          0L
        }
    val passthroughStopRequested = nativePassthroughStartMask != 0L
    val passthroughStopMask =
        if (passthroughStopRequested &&
            owner.ownsLegacyProvider &&
            bindings.nativeState().receiptLibraryLoaded) {
          runCatching { bindings.stopNativePassthrough() }.getOrDefault(0L)
        } else {
          0L
        }
    nativePassthroughStartMask = 0L
    environmentDepthStartMask = 0L
    environmentDepthLastValidFrameObserved = false
    environmentDepthLastRecoverableError = "none"
    environmentDepthRecoverableErrorCount = 0L
    bindings.marker(
        "channel=spatial-environment-depth status=consumer-policy-applied " +
            "source=${activityMarkerToken(source)} previousEnvironmentDepthConsumerRequired=$previousRequired " +
            "environmentDepthConsumerRequired=false environmentDepthProviderRequested=false " +
            "environmentDepthProviderState=stopped environmentDepthStopRequested=$stopRequested " +
            "environmentDepthStopMask=$stopMask nativePassthroughStopRequested=$passthroughStopRequested " +
            "nativePassthroughStopMask=$passthroughStopMask systemPassthroughConflictAvoided=true " +
            "environmentDepthZeroContributionWorkSkipped=true " +
            "runtimeCrash=false"
    )
  }

  fun acquireEnvironmentDepthFrameIfRequired(
      predictedDisplayTimeNs: Long,
      recoveryPolicy: SpatialEnvironmentDepthRecoveryPolicy = environmentDepthRecoveryPolicy,
      acquireNativeFrame: (Long, Boolean) -> Long,
  ) {
    if (!environmentDepthConsumerRequired ||
        !SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(
            environmentDepthStartMask
        )) {
      return
    }
    environmentDepthRecoveryPolicy = recoveryPolicy
    val receipt =
        runCatching {
              acquireNativeFrame(
                  predictedDisplayTimeNs,
                  recoveryPolicy == SpatialEnvironmentDepthRecoveryPolicy.Aggressive,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  "channel=spatial-environment-depth status=acquire-call-failed " +
                      "reason=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                      "detail=${activityMarkerToken(throwable.message ?: "none")} " +
                      "environmentDepthAcquisitionQuarantined=false runtimeCrash=false"
              )
              return
            }
    if (SpatialOpenXrRouteModule.spatialEnvironmentDepthAcquireSucceeded(receipt)) {
      environmentDepthLastValidFrameObserved = true
      environmentDepthLastRecoverableError = "none"
      return
    }
    if (!SpatialOpenXrRouteModule.spatialEnvironmentDepthAcquireFailed(receipt)) {
      return
    }
    val reason =
        if (SpatialOpenXrRouteModule.spatialEnvironmentDepthAcquireCallOrderInvalid(receipt)) {
          "openxr-call-order-invalid"
        } else {
          "openxr-acquire-error"
        }
    val firstErrorInCurrentStreak = environmentDepthLastRecoverableError == "none"
    environmentDepthLastRecoverableError = reason
    environmentDepthRecoverableErrorCount += 1L
    if (!firstErrorInCurrentStreak &&
        environmentDepthRecoverableErrorCount % 60L != 0L) {
      return
    }
    bindings.marker(
        "channel=spatial-environment-depth status=acquire-recoverable-error " +
            "reason=$reason nativeAcquireReceiptMask=$receipt " +
            "environmentDepthConsumerRequired=true environmentDepthProviderRequested=true " +
            "environmentDepthProviderState=provider-running " +
            "environmentDepthRecoveryPolicy=${recoveryPolicy.markerToken} " +
            "environmentDepthRecoverableErrorCount=$environmentDepthRecoverableErrorCount " +
            "environmentDepthAcquisitionQuarantined=false " +
            "environmentDepthLastValidRetention=true " +
            "environmentDepthLastValidAvailable=$environmentDepthLastValidFrameObserved " +
            "environmentDepthFallbackBinding=${if (environmentDepthLastValidFrameObserved) "last-valid-depth" else "neutral"} " +
            "environmentDepthZeroContributionWorkSkipped=false " +
            "runtimeCrash=false"
    )
  }

  fun stop() {
    environmentDepthConsumerRequired = false
    nativePassthroughStartMask = 0L
    environmentDepthStartMask = 0L
    environmentDepthLastValidFrameObserved = false
    environmentDepthLastRecoverableError = "none"
    environmentDepthRecoverableErrorCount = 0L
    if (bindings.environmentDepthOwner() == SpatialEnvironmentDepthOwner.SpatialSdkApiLayer) {
      bindings.updateSdkEnvironmentDepthDemand(false, "coordinator-stop")
    }
    if (!bindings.environmentDepthOwner().ownsLegacyProvider ||
        !bindings.nativeState().receiptLibraryLoaded) {
      return
    }
    runCatching { bindings.stopNativeEnvironmentDepth() }
    runCatching { bindings.stopNativePassthrough() }
  }

  private fun captureInteropProbe(): SpatialNativeInteropProbe =
      runCatching { bindings.captureInteropProbe() }
          .getOrElse { SpatialNativeInteropProbe(runtimeName = "unavailable", 0L, 0L, 0L) }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-depth-prerequisite-coordinator"
  }
}
