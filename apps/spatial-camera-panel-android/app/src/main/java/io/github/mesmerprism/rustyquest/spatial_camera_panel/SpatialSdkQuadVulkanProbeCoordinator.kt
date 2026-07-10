package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import android.view.Surface as AndroidSurface
import com.meta.spatial.runtime.SceneSwapchain

internal data class SpatialSdkQuadVulkanNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialSdkQuadVulkanProbeBindings(
    val resources: SpatialSdkQuadResourceCoordinator,
    val surfaceProbe: SpatialSdkQuadSurfaceProbeCoordinator,
    val cleanup: (String) -> String,
    val nativeState: () -> SpatialSdkQuadVulkanNativeState,
    val startNative: (AndroidSurface, Int, Int, Int) -> Long,
    val stopNative: () -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialSdkQuadVulkanProbeCoordinator(
    private val bindings: SpatialSdkQuadVulkanProbeBindings,
) {
  private var started = false

  fun runIfRequested(reason: String) {
    if (started || !SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeEnabled()) {
      return
    }
    started = true
    val holdMs = SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeHoldMs()
    val frameCount = SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeFrameCount()
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeStartMarker(
            reason = reason,
            holdMs = holdMs,
            frameCount = frameCount,
        )
    )
    Handler(Looper.getMainLooper()).post { run(holdMs, frameCount) }
  }

  private fun run(holdMs: Long, frameCount: Int) {
    bindings.cleanup("vulkan-pre-run")
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
              sdkSwapchainCreated = false,
              surfaceValid = false,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              nativeVulkanProducer = false,
              firstFramePresented = "false",
              manualSceneQuadLayerViable = false,
              error = nativeState.receiptLibraryError,
          )
      )
      return
    }
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  SDK_QUAD_SURFACE_PROBE_WIDTH_PX,
                  SDK_QUAD_SURFACE_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      sceneQuadLayerCreated = false,
                      nativeStartRequested = false,
                      nativeVulkanProducer = false,
                      firstFramePresented = "false",
                      manualSceneQuadLayerViable = false,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    bindings.resources.adoptSwapchain(sdkSwapchain)
    val surface =
        runCatching { sdkSwapchain.getSurface() }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeGetSurfaceFailedMarker(
                      handle = sdkSwapchain.handle,
                      nativeHandle = sdkSwapchain.nativeHandle(),
                      platformHandle = sdkSwapchain.platformHandle(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              null
            }
    bindings.resources.adoptSurface(surface)
    val surfaceValid = surface?.isValid == true
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = bindings.cleanup("vulkan-surface-invalid")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              nativeVulkanProducer = false,
              firstFramePresented = "false",
              manualSceneQuadLayerViable = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated =
        bindings.surfaceProbe.createLayer(
            sdkSwapchain = sdkSwapchain,
            canvasDrawn = false,
            anchorMode = "generated-single-sided-quad",
        )
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeLayerCreatedMarker(
            layerCreated = layerCreated
        )
    )
    if (!layerCreated) {
      val cleanupStatus = bindings.cleanup("vulkan-layer-create-failed")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              nativeVulkanProducer = false,
              firstFramePresented = "false",
              manualSceneQuadLayerViable = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val startMask =
        runCatching {
              bindings.startNative(
                  renderSurface,
                  SDK_QUAD_SURFACE_PROBE_WIDTH_PX,
                  SDK_QUAD_SURFACE_PROBE_HEIGHT_PX,
                  frameCount,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = bindings.cleanup("vulkan-start-failed")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = false,
                      nativeVulkanProducer = false,
                      firstFramePresented = "false",
                      manualSceneQuadLayerViable = true,
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeNativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            frameCount = frameCount,
            holdMs = holdMs,
        )
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (bindings.nativeState().receiptLibraryLoaded) {
                runCatching { bindings.stopNative() }
              }
              val cleanupStatus = bindings.cleanup("vulkan-hold-complete")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadVulkanProbeHoldCompleteMarker(
                      surfaceValid = surfaceValid,
                      frameCount = frameCount,
                      cleanupStatus = cleanupStatus,
                  )
              )
            },
            holdMs,
        )
  }

  companion object {
    const val MODULE_ID = "spatial-sdk-quad-vulkan-probe-coordinator"
  }
}
