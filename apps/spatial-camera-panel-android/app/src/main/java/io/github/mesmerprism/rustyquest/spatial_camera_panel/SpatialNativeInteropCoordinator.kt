package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.PanelSurface
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.Scene
import java.util.concurrent.atomic.AtomicLong

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
  private val probeSequence = AtomicLong(0L)
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
    val generation = probeSequence.incrementAndGet()
    lifecycleMarker("probe-begin", generation, phase)
    val probe = SpatialNativeInteropProbe.capture(bindings.scene)
    val surfaceProbe =
        if (probeSurface) {
          createNoRenderSurfaceProbe(generation, phase)
        } else {
          NativeInteropSurfaceProbeResult(
              capability = "PanelSurface",
              status = "deferred-until-vr-ready",
              surfaceValid = false,
              error = "none",
          )
        }
    lifecycleMarker("receipt-begin", generation, phase)
    val nativeReceipt = recordNativeInteropReceipt(probe, surfaceProbe)
    lifecycleMarker("receipt-end", generation, phase)
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
    lifecycleMarker("probe-end", generation, phase)
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

  private fun lifecycleMarker(stage: String, generation: Long, phase: String) {
    bindings.marker(
        "channel=native-interop-lifecycle status=observed stage=$stage " +
            "probeGeneration=$generation phase=${activityMarkerToken(phase)} renderPolicy=no-render"
    )
  }

  private fun createNoRenderSurfaceProbe(
      generation: Long,
      phase: String,
  ): NativeInteropSurfaceProbeResult {
    var panelSurface: PanelSurface? = null
    return runCatching {
          lifecycleMarker("surface-create-begin", generation, phase)
          panelSurface =
              PanelSurface(bindings.scene, 64, 64, 1, SamplerConfig(), true, false, "", false)
          lifecycleMarker("surface-create-end", generation, phase)
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
        .also {
          panelSurface?.let {
            lifecycleMarker("surface-destroy-begin", generation, phase)
            it.destroy()
            lifecycleMarker("surface-destroy-end", generation, phase)
          }
        }
  }

  companion object {
    const val MODULE_ID = "spatial-native-interop-coordinator"
  }
}
