package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import android.view.Surface as AndroidSurface
import com.meta.spatial.runtime.PanelSurface
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.Scene

internal data class SpatialPanelSurfaceMatrixNativeState(
    val receiptLibraryLoaded: Boolean,
)

internal data class SpatialPanelSurfaceMatrixProbeBindings(
    val scene: Scene,
    val surfaceProbe: SpatialSdkQuadSurfaceProbeCoordinator,
    val cleanup: (String) -> String,
    val nativeState: () -> SpatialPanelSurfaceMatrixNativeState,
    val startNative: (AndroidSurface, Int, Int, Int) -> Long,
    val stopNative: () -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialPanelSurfaceMatrixProbeCoordinator(
    private val bindings: SpatialPanelSurfaceMatrixProbeBindings,
) {
  private var started = false

  fun runIfRequested(reason: String) {
    if (started || !SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeEnabled()) {
      return
    }
    started = true
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeStartMarker(reason)
    )
    Handler(Looper.getMainLooper()).post {
      runVariant(
          variantIndex = 0,
          useSwapchain = true,
          useTexture = false,
      )
    }
  }

  private fun runVariant(
      variantIndex: Int,
      useSwapchain: Boolean,
      useTexture: Boolean,
  ) {
    bindings.cleanup("panel-surface-matrix-pre-variant-$variantIndex")
    if (bindings.nativeState().receiptLibraryLoaded) {
      runCatching { bindings.stopNative() }
    }
    val variantName = "useSwapchain-$useSwapchain-useTexture-$useTexture"
    var panelSurface: PanelSurface? = null
    val created =
        runCatching {
              PanelSurface(
                  bindings.scene,
                  PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX,
                  PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX,
                  1,
                  SamplerConfig(),
                  useSwapchain,
                  useTexture,
                  "",
                  false,
              )
            }
            .onSuccess { panelSurface = it }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeVariantCreateFailedMarker(
                      variantName = variantName,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              scheduleNextVariant(variantIndex)
              return
            }
    val surface = runCatching { created.surface }.getOrNull()
    val swapchain = runCatching { created.swapchain }.getOrNull()
    val texture = runCatching { created.texture }.getOrNull()
    val surfaceValid = surface?.isValid == true
    val swapchainNonNull = swapchain != null
    val textureNonNull = texture != null
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeVariantCreatedMarker(
            variantName = variantName,
            surfaceValid = surfaceValid,
            swapchainNonNull = swapchainNonNull,
            textureNonNull = textureNonNull,
            widthPx = created.widthInPx,
            heightPx = created.heightInPx,
            mips = created.mips,
            reportedUseSwapchain = created.useSwapchain,
            reportedUseTexture = created.useTexture,
        )
    )

    val layerCreated =
        if (swapchain != null) {
          bindings.surfaceProbe.createLayer(
              sdkSwapchain = swapchain,
              canvasDrawn = false,
              anchorMode = "generated-single-sided-quad",
          )
        } else {
          false
        }
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeSceneQuadLayerAttemptedMarker(
            variantName = variantName,
            swapchainNonNull = swapchainNonNull,
            layerCreated = layerCreated,
        )
    )

    val nativeState = bindings.nativeState()
    val nativeStartMask =
        if (surfaceValid && nativeState.receiptLibraryLoaded) {
          runCatching {
                bindings.startNative(
                    surface,
                    PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX,
                    PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX,
                    PANEL_SURFACE_MATRIX_PROBE_FRAME_COUNT,
                )
              }
              .getOrElse { throwable ->
                bindings.marker(
                    SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeNativeStartFailedMarker(
                        variantName = variantName,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
                0L
              }
        } else {
          0L
        }
    val nativeStartRequested = nativeStartMask != 0L
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeNativeStartAttemptedMarker(
            variantName = variantName,
            surfaceValid = surfaceValid,
            nativeReceiptLibraryLoaded = nativeState.receiptLibraryLoaded,
            nativeStartRequested = nativeStartRequested,
            nativeStartMask = nativeStartMask,
        )
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (bindings.nativeState().receiptLibraryLoaded) {
                runCatching { bindings.stopNative() }
              }
              val sceneCleanupStatus =
                  bindings.cleanup("panel-surface-matrix-variant-$variantIndex")
              val panelSurfaceDestroyed =
                  runCatching {
                        panelSurface?.destroy()
                        true
                      }
                      .getOrDefault(false)
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeVariantCompleteMarker(
                      variantName = variantName,
                      surfaceValid = surfaceValid,
                      swapchainNonNull = swapchainNonNull,
                      textureNonNull = textureNonNull,
                      layerCreated = layerCreated,
                      nativeStartRequested = nativeStartRequested,
                      nativeStartMask = nativeStartMask,
                      sceneCleanupStatus = sceneCleanupStatus,
                      panelSurfaceDestroyed = panelSurfaceDestroyed,
                  )
              )
              scheduleNextVariant(variantIndex)
            },
            PANEL_SURFACE_MATRIX_PROBE_VARIANT_HOLD_MS,
        )
  }

  private fun scheduleNextVariant(variantIndex: Int) {
    if (variantIndex == 0) {
      Handler(Looper.getMainLooper())
          .postDelayed(
              {
                runVariant(
                    variantIndex = 1,
                    useSwapchain = false,
                    useTexture = true,
                )
              },
            PANEL_SURFACE_MATRIX_PROBE_INTER_VARIANT_MS,
          )
      return
    }
    bindings.marker(SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeCompleteMarker())
  }

  companion object {
    const val MODULE_ID = "spatial-panel-surface-matrix-probe-coordinator"
  }
}
