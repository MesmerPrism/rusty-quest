package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialSurfaceParticleRuntimeBindings(
    val nativeSurfaceParticleLayerEnabled: () -> Boolean,
    val suppressionSource: () -> String,
    val privateRendererEnabled: () -> Boolean,
    val receiptLibraryLoaded: () -> Boolean,
    val receiptLibraryError: () -> String,
    val launcherPanelVisible: () -> Boolean,
    val stopNative: () -> Unit,
    val marker: (String) -> Unit,
)

internal data class SpatialSurfaceParticleStartRequest(
    val surfaceValid: () -> Boolean,
    val captureOpenXrProbe: () -> SpatialNativeInteropProbe,
    val startNative: (SpatialNativeInteropProbe) -> Long,
    val carrier: () -> String,
    val placementMarkerFields: () -> String,
    val stereoMarkerFields: () -> String,
    val submitParameters: () -> Unit,
)

internal class SpatialSurfaceParticleRuntimeCoordinator(
    private val bindings: SpatialSurfaceParticleRuntimeBindings,
) {
  var particleLayerStarted = false
    private set

  var cameraStackSuppressesParticles = false
    private set

  var nativeSurfaceStartRequested = false
    private set

  var lastNativeSurfaceStartMask = 0L
    private set

  fun start(request: SpatialSurfaceParticleStartRequest) {
    if (!bindings.nativeSurfaceParticleLayerEnabled()) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartSuppressedDisabledMarker(
              suppressionSource = bindings.suppressionSource(),
              privateRendererEnabled = bindings.privateRendererEnabled(),
              particleLayerStarted = particleLayerStarted,
              nativeSurfaceStartRequested = nativeSurfaceStartRequested,
          )
      )
      return
    }
    if (cameraStackSuppressesParticles) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartSuppressedCameraStackMarker(
              particleLayerStarted = particleLayerStarted,
              nativeSurfaceStartRequested = nativeSurfaceStartRequested,
          )
      )
      return
    }
    if (particleLayerStarted) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartSkippedAlreadyStartedMarker()
      )
      return
    }
    if (!bindings.receiptLibraryLoaded()) {
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleLibraryUnavailableMarker(
              bindings.receiptLibraryError()
          )
      )
      return
    }

    val surfaceValid = request.surfaceValid()
    val openXrProbe = request.captureOpenXrProbe()
    if (!surfaceValid) {
      bindings.marker(SpatialSurfaceParticleRouteModule.nativeSurfaceParticleSurfaceUnavailableMarker())
      return
    }

    runCatching {
          val startMask = request.startNative(openXrProbe)
          particleLayerStarted = true
          nativeSurfaceStartRequested = true
          lastNativeSurfaceStartMask = startMask
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartRequestedMarker(
                  surfaceValid = surfaceValid,
                  startMask = startMask,
                  carrier = request.carrier(),
                  openXrInstanceHandleNonZero = openXrProbe.openXrInstanceHandleNonZero,
                  openXrSessionHandleNonZero = openXrProbe.openXrSessionHandleNonZero,
                  openXrGetInstanceProcAddrHandleNonZero =
                      openXrProbe.openXrGetInstanceProcAddrHandleNonZero,
                  placementMarkerFields = request.placementMarkerFields(),
                  stereoMarkerFields = request.stereoMarkerFields(),
              )
          )
          request.submitParameters()
        }
        .getOrElse { throwable ->
          bindings.marker(
              SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStartFailedMarker(
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
  }

  fun suppressStartsForCameraStack() {
    cameraStackSuppressesParticles = true
  }

  fun suppressForCameraStack(source: String) {
    suppressStartsForCameraStack()
    val wasStarted = particleLayerStarted
    val stopAttempted = bindings.receiptLibraryLoaded() && wasStarted
    if (stopAttempted) {
      runCatching { bindings.stopNative() }
          .onSuccess {
            particleLayerStarted = false
            nativeSurfaceStartRequested = false
            bindings.marker(
                SpatialSurfaceParticleRouteModule.cameraStackParticleLayerSuppressedMarker(
                    source = source,
                    stopAttempted = true,
                    stopSucceeded = true,
                    launcherPanelVisible = bindings.launcherPanelVisible(),
                    particleLayerStarted = particleLayerStarted,
                    nativeSurfaceStartRequested = nativeSurfaceStartRequested,
                )
            )
          }
          .onFailure { throwable ->
            bindings.marker(
                SpatialSurfaceParticleRouteModule.cameraStackParticleLayerSuppressFailedMarker(
                    source = source,
                    particleLayerStarted = particleLayerStarted,
                    nativeSurfaceStartRequested = nativeSurfaceStartRequested,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
      return
    }
    if (!wasStarted) {
      particleLayerStarted = false
      nativeSurfaceStartRequested = false
    }
    bindings.marker(
        SpatialSurfaceParticleRouteModule.cameraStackParticleLayerSuppressedMarker(
            source = source,
            stopAttempted = stopAttempted,
            stopSucceeded = true,
            launcherPanelVisible = bindings.launcherPanelVisible(),
            particleLayerStarted = particleLayerStarted,
            nativeSurfaceStartRequested = nativeSurfaceStartRequested,
        )
    )
  }

  fun stop(source: String = "lifecycle") {
    val wasStarted = particleLayerStarted
    if (bindings.receiptLibraryLoaded() && wasStarted) {
      runCatching { bindings.stopNative() }
          .onSuccess {
            particleLayerStarted = false
            nativeSurfaceStartRequested = false
            bindings.marker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStoppedMarker(
                    source = source,
                    particleLayerStarted = particleLayerStarted,
                    nativeSurfaceStartRequested = nativeSurfaceStartRequested,
                )
            )
          }
          .onFailure { throwable ->
            bindings.marker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticleStopFailedMarker(
                    source = source,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
          }
    } else if (!wasStarted) {
      nativeSurfaceStartRequested = false
    }
  }

  companion object {
    const val MODULE_ID = "spatial-surface-particle-runtime-coordinator"
  }
}
