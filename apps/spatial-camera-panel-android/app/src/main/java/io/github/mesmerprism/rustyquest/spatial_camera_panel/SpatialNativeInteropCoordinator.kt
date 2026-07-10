package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.PanelSurface
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.Scene

internal data class SpatialNativeInteropBindings(
    val scene: Scene,
    val recordNoRenderReceipt: (Long, Long, Long, Boolean) -> Long,
    val requestMultimodalInput: (SpatialNativeInteropProbe, String) -> Unit,
    val startControllerActions: (SpatialNativeInteropProbe, String) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialNativeInteropCoordinator(
    private val bindings: SpatialNativeInteropBindings,
) {
  var receiptLibraryLoaded = false
    private set

  var receiptLibraryError = "not-loaded"
    private set

  fun loadReceiptLibrary() {
    val result = runCatching { System.loadLibrary(NATIVE_RECEIPT_LIBRARY) }
    receiptLibraryLoaded = result.isSuccess
    receiptLibraryError = result.exceptionOrNull()?.javaClass?.simpleName ?: "none"
    bindings.marker(
        SpatialOpenXrRouteModule.nativeReceiptLibraryLoadMarker(
            library = NATIVE_RECEIPT_LIBRARY,
            loaded = receiptLibraryLoaded,
            error = receiptLibraryError,
        )
    )
  }

  fun logProbe(phase: String, probeSurface: Boolean) {
    val probe = SpatialNativeInteropProbe.capture(bindings.scene)
    val surfaceProbe =
        if (probeSurface) {
          createNoRenderSurfaceProbe()
        } else {
          NativeInteropSurfaceProbeResult(
              capability = "PanelSurface",
              status = "deferred-until-vr-ready",
              surfaceValid = false,
              error = "none",
          )
        }
    val nativeReceipt = recordNativeInteropReceipt(probe, surfaceProbe)
    bindings.marker(SpatialOpenXrRouteModule.nativeInteropProbeMarker(phase, probe, surfaceProbe))
    bindings.marker(
        SpatialOpenXrRouteModule.nativeInteropReceiptMarker(
            phase,
            receiptLibraryLoaded,
            nativeReceipt,
        )
    )
    bindings.requestMultimodalInput(probe, phase)
    bindings.startControllerActions(probe, phase)
  }

  private fun recordNativeInteropReceipt(
      probe: SpatialNativeInteropProbe,
      surfaceProbe: NativeInteropSurfaceProbeResult,
  ): NativeInteropReceiptResult {
    if (!receiptLibraryLoaded) {
      return SpatialOpenXrRouteModule.nativeInteropReceiptUnavailable(receiptLibraryError)
    }
    return runCatching {
          val mask =
              bindings.recordNoRenderReceipt(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
                  surfaceProbe.surfaceValid,
              )
          SpatialOpenXrRouteModule.nativeInteropReceiptReceived(mask)
        }
        .getOrElse { throwable ->
          SpatialOpenXrRouteModule.nativeInteropReceiptCallFailed(throwable.javaClass.simpleName)
        }
  }

  private fun createNoRenderSurfaceProbe(): NativeInteropSurfaceProbeResult {
    var panelSurface: PanelSurface? = null
    return runCatching {
          panelSurface =
              PanelSurface(bindings.scene, 64, 64, 1, SamplerConfig(), true, false, "", false)
          NativeInteropSurfaceProbeResult(
              capability = "PanelSurface",
              status = "created-destroyed-no-render",
              surfaceValid = panelSurface?.surface?.isValid == true,
              error = "none",
          )
        }
        .getOrElse { throwable ->
          NativeInteropSurfaceProbeResult(
              capability = "PanelSurface",
              status = "unavailable",
              surfaceValid = false,
              error = throwable.javaClass.simpleName,
          )
        }
        .also { panelSurface?.destroy() }
  }

  companion object {
    const val MODULE_ID = "spatial-native-interop-coordinator"
  }
}
