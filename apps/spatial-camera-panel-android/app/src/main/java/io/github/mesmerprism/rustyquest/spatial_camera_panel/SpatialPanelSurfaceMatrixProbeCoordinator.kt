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

  private data class Variant(
      val useSwapchain: Boolean,
      val useTexture: Boolean,
      val fragmentShader: String,
  ) {
    val name: String
      get() = "useSwapchain-$useSwapchain-useTexture-$useTexture"
  }

  fun runIfRequested(reason: String) {
    if (started || !SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeEnabled()) {
      return
    }
    started = true
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeStartMarker(reason)
    )
    Handler(Looper.getMainLooper()).post {
      runVariant(variantIndex = 0)
    }
  }

  private fun runVariant(variantIndex: Int) {
    val variant = VARIANTS[variantIndex]
    bindings.cleanup("panel-surface-matrix-pre-variant-$variantIndex")
    if (bindings.nativeState().receiptLibraryLoaded) {
      runCatching { bindings.stopNative() }
    }
    runCatching { bindings.scene.setSkipRender(false) }
    val variantName = variant.name
    var panelSurface: PanelSurface? = null
    val created =
        runCatching {
              PanelSurface(
                  bindings.scene,
                  PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX,
                  PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX,
                  1,
                  SamplerConfig(),
                  variant.useSwapchain,
                  variant.useTexture,
                  variant.fragmentShader,
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
            fragmentShader = created.fragmentShader,
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
    val textureMeshCreated =
        if (texture != null) {
          bindings.surfaceProbe.createTextureMesh(
              texture = texture,
              variantName = variantName,
              horizontalOffsetMeters =
                  if (swapchainNonNull) PANEL_SURFACE_MATRIX_DUAL_TEXTURE_OFFSET_METERS else 0.0f,
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

    if (variant.useSwapchain && variant.useTexture) {
      Handler(Looper.getMainLooper())
          .postDelayed(
              {
                val accepted = runCatching { bindings.scene.setSkipRender(true) }.isSuccess
                bindings.marker(
                    SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeSkipRenderMarker(
                        enabled = true,
                        accepted = accepted,
                        expectedWitness = "scene-mesh-hidden-compositor-layer-retained",
                    )
                )
              },
              PANEL_SURFACE_MATRIX_SKIP_RENDER_START_MS,
          )
      Handler(Looper.getMainLooper())
          .postDelayed(
              {
                val accepted = runCatching { bindings.scene.setSkipRender(false) }.isSuccess
                bindings.marker(
                    SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeSkipRenderMarker(
                        enabled = false,
                        accepted = accepted,
                        expectedWitness = "scene-mesh-and-compositor-layer-restored",
                    )
                )
              },
              PANEL_SURFACE_MATRIX_SKIP_RENDER_END_MS,
          )
    }

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
              runCatching { bindings.scene.setSkipRender(false) }
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
                      textureMeshCreated = textureMeshCreated,
                      fragmentShader = variant.fragmentShader,
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
    if (variantIndex + 1 < VARIANTS.size) {
      Handler(Looper.getMainLooper())
          .postDelayed(
              { runVariant(variantIndex = variantIndex + 1) },
              PANEL_SURFACE_MATRIX_PROBE_INTER_VARIANT_MS,
          )
      return
    }
    bindings.marker(SpatialDiagnosticProbeRouteModule.panelSurfaceMatrixProbeCompleteMarker())
  }

  companion object {
    const val MODULE_ID = "spatial-panel-surface-matrix-probe-coordinator"
    private const val PANEL_SURFACE_MATRIX_DUAL_TEXTURE_OFFSET_METERS = 0.52f
    private const val PANEL_SURFACE_MATRIX_SKIP_RENDER_START_MS = 900L
    private const val PANEL_SURFACE_MATRIX_SKIP_RENDER_END_MS = 1_500L
    private const val PANEL_SURFACE_MATRIX_EFFECT_SHADER = "spatial_panel_surface_probe.frag"
    private val VARIANTS =
        listOf(
            Variant(useSwapchain = true, useTexture = false, fragmentShader = ""),
            Variant(useSwapchain = false, useTexture = true, fragmentShader = ""),
            Variant(
                useSwapchain = true,
                useTexture = true,
                fragmentShader = PANEL_SURFACE_MATRIX_EFFECT_SHADER,
            ),
        )
  }
}
