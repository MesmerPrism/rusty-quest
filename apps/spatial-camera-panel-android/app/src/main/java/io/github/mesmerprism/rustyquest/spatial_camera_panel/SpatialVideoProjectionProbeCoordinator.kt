package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import android.view.Surface as AndroidSurface
import com.meta.spatial.runtime.SceneSwapchain

internal data class SpatialVideoProjectionProbeState(
    val enabled: Boolean,
    val sceneReady: Boolean,
    val virtualRoomEnabled: Boolean,
    val virtualRoomLoaded: Boolean,
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialVideoProjectionProbeBindings(
    val resources: SpatialSdkQuadResourceCoordinator,
    val state: () -> SpatialVideoProjectionProbeState,
    val resolveSettings: () -> SpatialVideoProjectionSettings,
    val projectionMarkerFields: () -> String,
    val stereoMarkerFields: () -> String,
    val cleanup: (String) -> String,
    val prepare: (SpatialVideoProjectionSettings) -> Unit,
    val configureNative: (SpatialVideoProjectionSettings, String) -> Unit,
    val startProjection: (SpatialVideoProjectionSettings, String) -> Unit,
    val createLayer: (SceneSwapchain) -> Boolean,
    val startNative: (AndroidSurface, Int, Int, Int) -> Long,
    val updateFromViewer: (String, Boolean) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialVideoProjectionProbeCoordinator(
    private val bindings: SpatialVideoProjectionProbeBindings,
) {
  private var started = false

  fun runIfRequested(reason: String) {
    val state = bindings.state()
    if (started || !state.enabled) {
      return
    }
    if (!state.sceneReady) {
      bindings.marker(SpatialVideoProjectionRouteModule.startDeferredForSceneMarker(reason))
      return
    }
    if (state.virtualRoomEnabled && !state.virtualRoomLoaded) {
      bindings.marker(
          SpatialVideoProjectionRouteModule.startDeferredForVirtualRoomMarker(
              reason,
              state.sceneReady,
          )
      )
      return
    }
    started = true
    val videoSettings = bindings.resolveSettings()
    bindings.marker(
        SpatialVideoProjectionRouteModule.startMarker(
            reason = reason,
            widthPx = CAMERA_HWB_PROJECTION_WIDTH_PX,
            heightPx = CAMERA_HWB_PROJECTION_HEIGHT_PX,
            projectionMarkerFields = bindings.projectionMarkerFields(),
            stereoMarkerFields = bindings.stereoMarkerFields(),
            settings = videoSettings,
        )
    )
    Handler(Looper.getMainLooper()).post { run(videoSettings) }
  }

  private fun run(videoSettings: SpatialVideoProjectionSettings) {
    bindings.cleanup("spatial-video-projection-pre-run")
    bindings.prepare(videoSettings)
    if (!videoSettings.active) {
      bindings.configureNative(videoSettings, "video-only-inactive")
      bindings.marker(SpatialVideoProjectionRouteModule.inactiveCompleteMarker(videoSettings))
      return
    }
    val state = bindings.state()
    if (!state.receiptLibraryLoaded) {
      bindings.marker(
          SpatialVideoProjectionRouteModule.nativeReceiptUnavailableCompleteMarker(
              state.receiptLibraryError
          )
      )
      return
    }
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialVideoProjectionRouteModule.sdkSwapchainCreateFailedCompleteMarker(
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
                  SpatialVideoProjectionRouteModule.getSurfaceFailedMarker(
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
        SpatialVideoProjectionRouteModule.sdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
            widthPx = CAMERA_HWB_PROJECTION_WIDTH_PX,
            heightPx = CAMERA_HWB_PROJECTION_HEIGHT_PX,
            stereoMarkerFields = bindings.stereoMarkerFields(),
            settings = videoSettings,
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = bindings.cleanup("spatial-video-projection-surface-invalid")
      bindings.marker(
          SpatialVideoProjectionRouteModule.completeMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated = bindings.createLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = bindings.cleanup("spatial-video-projection-layer-create-failed")
      bindings.marker(
          SpatialVideoProjectionRouteModule.completeMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    bindings.configureNative(videoSettings, "video-only-start")
    bindings.startProjection(videoSettings, "video-only-start")
    val startMask =
        runCatching {
              bindings.startNative(
                  renderSurface,
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  SPATIAL_VIDEO_PROJECTION_FRAME_COUNT_UNBOUNDED,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = bindings.cleanup("spatial-video-projection-start-failed")
              bindings.marker(
                  SpatialVideoProjectionRouteModule.completeMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = false,
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    bindings.marker(
        SpatialVideoProjectionRouteModule.nativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            settings = videoSettings,
        )
    )
    bindings.updateFromViewer("video-only-start", true)
  }

  companion object {
    const val MODULE_ID = "spatial-video-projection-probe-coordinator"
  }
}
