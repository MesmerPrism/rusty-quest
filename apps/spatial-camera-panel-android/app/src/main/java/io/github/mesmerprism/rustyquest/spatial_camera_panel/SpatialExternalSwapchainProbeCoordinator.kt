package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import com.meta.spatial.runtime.Scene

internal data class SpatialExternalSwapchainProbeNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialExternalSwapchainProbeBindings(
    val scene: Scene,
    val nativeState: () -> SpatialExternalSwapchainProbeNativeState,
    val createExternalSwapchain: (Long, Long, Long, Int, Int) -> Long,
    val destroyExternalSwapchain: (Long, Long, Long) -> Int,
    val marker: (String) -> Unit,
)

/**
 * Exercises only the OpenXR handles exposed by Spatial SDK and standard OpenXR swapchain calls.
 * It does not convert the returned XrSwapchain into an SDK scene object.
 */
internal class SpatialExternalSwapchainProbeCoordinator(
    private val bindings: SpatialExternalSwapchainProbeBindings,
) {
  private var started = false
  private var externalHandle = 0L

  fun runIfRequested(reason: String) {
    if (started || !SpatialDiagnosticProbeRouteModule.externalSwapchainProbeEnabled()) return
    started = true
    val cycles = SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycles()
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeStartMarker(
            reason = reason,
            cycles = cycles,
            cycleMs = 0L,
        )
    )
    Handler(Looper.getMainLooper()).post { runCycle(1, cycles) }
  }

  fun destroy(reason: String): String = cleanup(reason)

  private fun runCycle(cycleIndex: Int, cycleCount: Int) {
    cleanup("cycle-$cycleIndex-pre-cleanup")
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLibraryUnavailableCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              error = nativeState.receiptLibraryError,
          )
      )
      return
    }

    val probe = SpatialNativeInteropProbe.capture(bindings.scene)
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeMissingOpenXrHandlesCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              openXrInstanceHandleNonZero = probe.openXrInstanceHandleNonZero,
              openXrSessionHandleNonZero = probe.openXrSessionHandleNonZero,
              openXrGetInstanceProcAddrHandleNonZero =
                  probe.openXrGetInstanceProcAddrHandleNonZero,
          )
      )
      return
    }

    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCompileTimeBoundaryMarker(
            cycleIndex = cycleIndex,
            openXrInstanceHandleNonZero = probe.openXrInstanceHandleNonZero,
            openXrSessionHandleNonZero = probe.openXrSessionHandleNonZero,
            openXrGetInstanceProcAddrHandleNonZero =
                probe.openXrGetInstanceProcAddrHandleNonZero,
        )
    )

    externalHandle =
        runCatching {
              bindings.createExternalSwapchain(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
                  EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX,
                  EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeNativeCreateCallFailedMarker(
                      cycleIndex = cycleIndex,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    if (externalHandle == 0L) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeZeroHandleCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              sdkHandleWrapMode = "not-used",
          )
      )
      return
    }

    val destroyOwnership = cleanup("cycle-$cycleIndex-complete")
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeStandardCompleteMarker(
            cycleIndex = cycleIndex,
            cycleCount = cycleCount,
            destroyOwnership = destroyOwnership,
        )
    )
    if (cycleIndex < cycleCount) {
      Handler(Looper.getMainLooper())
          .postDelayed(
              { runCycle(cycleIndex + 1, cycleCount) },
              EXTERNAL_SWAPCHAIN_PROBE_INTER_CYCLE_MS,
          )
    }
  }

  private fun cleanup(reason: String): String {
    val handle = externalHandle
    if (handle == 0L || !bindings.nativeState().receiptLibraryLoaded) return "not-run"
    val probe = SpatialNativeInteropProbe.capture(bindings.scene)
    val result =
        runCatching {
              bindings.destroyExternalSwapchain(
                  probe.openXrInstanceHandle,
                  probe.openXrGetInstanceProcAddrHandle,
                  handle,
              )
            }
            .getOrDefault(Int.MIN_VALUE)
    externalHandle = 0L
    val ownership =
        when (result) {
          0 -> "native"
          OPENXR_ERROR_HANDLE_INVALID -> "runtime"
          else -> "unknown"
        }
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeStandardCleanupMarker(
            reason = reason,
            nativeDestroyResult = result,
            destroyOwnership = ownership,
        )
    )
    return ownership
  }

  companion object {
    const val MODULE_ID = "spatial-external-swapchain-probe-coordinator"
  }
}
