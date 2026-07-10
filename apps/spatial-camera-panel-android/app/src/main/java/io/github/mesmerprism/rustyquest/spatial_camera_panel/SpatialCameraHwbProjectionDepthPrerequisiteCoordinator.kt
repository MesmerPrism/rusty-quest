package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialCameraHwbProjectionDepthPrerequisiteNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialCameraHwbProjectionDepthPrerequisiteBindings(
    val routeActive: () -> Boolean,
    val nativeState: () -> SpatialCameraHwbProjectionDepthPrerequisiteNativeState,
    val captureInteropProbe: () -> SpatialNativeInteropProbe,
    val requiredOpenXrExtensions: () -> String,
    val projectionEntityPresent: () -> Boolean,
    val startNativePassthrough: (Long, Long, Long) -> Long,
    val stopNativePassthrough: () -> Long,
    val startNativeEnvironmentDepth: (Long, Long, Long) -> Long,
    val stopNativeEnvironmentDepth: () -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionDepthPrerequisiteCoordinator(
    private val bindings: SpatialCameraHwbProjectionDepthPrerequisiteBindings,
) {
  private var environmentDepthStartMask = 0L

  fun startPassthrough(source: String): Long {
    if (!bindings.routeActive()) {
      return 0L
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
        )
    )
    return mask
  }

  fun stop() {
    if (!bindings.nativeState().receiptLibraryLoaded) {
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
