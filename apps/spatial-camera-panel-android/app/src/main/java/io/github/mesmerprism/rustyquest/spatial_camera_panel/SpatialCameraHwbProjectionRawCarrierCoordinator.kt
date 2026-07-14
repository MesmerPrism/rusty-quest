package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.Surface as AndroidSurface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.LayerAlphaBlend
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneQuadLayer
import com.meta.spatial.runtime.SceneSwapchain
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialCameraHwbProjectionRawNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialCameraHwbProjectionRawCarrierBindings(
    val scene: Scene,
    val resources: SpatialSdkQuadResourceCoordinator,
    val routeEnabled: () -> Boolean,
    val nativeState: () -> SpatialCameraHwbProjectionRawNativeState,
    val cleanup: (String) -> String,
    val projectionPlane: () -> CameraHwbProjectionPlane,
    val setProjectionEntity: (Entity?) -> Unit,
    val layerZIndex: (CameraHwbProjectionPlacementMode) -> Int,
    val carrierMode: () -> CameraHwbProjectionCarrierMode,
    val carrierToken: () -> String,
    val projectionMarkerFields: (CameraHwbProjectionPlane?) -> String,
    val stereoMarkerFields: () -> String,
    val videoProjectionMarkerFields: (SpatialVideoProjectionSettings) -> String,
    val syntheticVisualEnabled: () -> Boolean,
    val drawSyntheticVisual: (AndroidSurface, String) -> Boolean,
    val startNativePassthrough: (String) -> Long,
    val startEnvironmentDepth: (String) -> Long,
    val updateNativeStereoOffset: (String, Boolean) -> Unit,
    val updateNativeTargetScale: (String, Boolean) -> Unit,
    val applyPrivateLayerConfiguration: (String) -> Unit,
    val configureVideoProjection: (SpatialVideoProjectionSettings, String) -> Unit,
    val startVideoProjection: (SpatialVideoProjectionSettings, String) -> Unit,
    val startNative: (AndroidSurface, Int, Int, Int, Int) -> Long,
    val updateFromViewer: (String, Boolean) -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProjectionRawCarrierCoordinator(
    private val bindings: SpatialCameraHwbProjectionRawCarrierBindings,
) {
  fun run(readerMaxImages: Int, videoSettings: SpatialVideoProjectionSettings) {
    if (!bindings.routeEnabled()) {
      return
    }
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          CameraHwbProjectionModule.rawProjectionCompleteBeforeSwapchainMarker(
              nativeState.receiptLibraryError
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
                  CameraHwbProjectionModule.rawProjectionCompleteBeforeSwapchainMarker(
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
                  CameraHwbProjectionModule.rawProjectionGetSurfaceFailedMarker(
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
        CameraHwbProjectionModule.rawProjectionSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
            carrier = bindings.carrierToken(),
            stereoMarkerFields = bindings.stereoMarkerFields(),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = bindings.cleanup("camera-hwb-projection-surface-invalid")
      bindings.marker(
          CameraHwbProjectionModule.rawProjectionCompleteAfterCleanupMarker(
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated = createLayer(sdkSwapchain, videoSettings)
    if (!layerCreated) {
      val cleanupStatus = bindings.cleanup("camera-hwb-projection-layer-create-failed")
      bindings.marker(
          CameraHwbProjectionModule.rawProjectionCompleteAfterCleanupMarker(
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    if (bindings.syntheticVisualEnabled()) {
      val canvasDrawn = bindings.drawSyntheticVisual(renderSurface, "SceneQuadLayer")
      bindings.marker(
          CameraHwbProjectionModule.rawProjectionSyntheticVisualPresentedMarker(
              surfaceValid = surfaceValid,
              canvasDrawn = canvasDrawn,
              carrier = bindings.carrierToken(),
          )
      )
      bindings.updateFromViewer("synthetic-visual-start", true)
      return
    }

    val reason = "raw-projection-start"
    val nativePassthroughStartMask = bindings.startNativePassthrough(reason)
    val nativePassthroughLayerActive =
        SpatialOpenXrRouteModule.nativePassthroughLayerActive(nativePassthroughStartMask)
    val nativeEnvironmentDepthStartMask = bindings.startEnvironmentDepth(reason)
    val nativeEnvironmentDepthProviderBound =
        SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(
            nativeEnvironmentDepthStartMask
        )
    bindings.updateNativeStereoOffset(reason, true)
    bindings.updateNativeTargetScale(reason, true)
    bindings.applyPrivateLayerConfiguration(reason)
    bindings.configureVideoProjection(videoSettings, reason)
    if (videoSettings.active) {
      bindings.startVideoProjection(videoSettings, reason)
    }
    val startMask =
        runCatching {
              bindings.startNative(
                  renderSurface,
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  CAMERA_HWB_PROJECTION_FRAME_COUNT_UNBOUNDED,
                  readerMaxImages,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = bindings.cleanup("camera-hwb-projection-start-failed")
              bindings.marker(
                  CameraHwbProjectionModule.rawProjectionCompleteAfterCleanupMarker(
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    bindings.marker(
        CameraHwbProjectionModule.rawProjectionNativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            readerMaxImages = readerMaxImages,
            carrier = bindings.carrierToken(),
            projectionMarkerFields = bindings.projectionMarkerFields(null),
            stereoMarkerFields = bindings.stereoMarkerFields(),
            videoProjectionMarkerFields = bindings.videoProjectionMarkerFields(videoSettings),
            publicMultiStackMarkerFields =
                SpatialPublicMultiStack.markerFields(
                    nativePassthroughLayerActive = nativePassthroughLayerActive,
                    nativeEnvironmentDepthProviderRequested = true,
                    nativeEnvironmentDepthProviderBound = nativeEnvironmentDepthProviderBound,
                ),
            nativePassthroughStartMask = nativePassthroughStartMask,
            nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
        )
    )
    bindings.updateFromViewer(reason, true)
  }

  fun createLayer(
      sdkSwapchain: SceneSwapchain,
      videoSettings: SpatialVideoProjectionSettings,
  ): Boolean =
      runCatching {
            val plane = bindings.projectionPlane()
            val entity =
                Entity.create(
                    Transform(plane.pose),
                    Scale(Vector3(1.0f, 1.0f, 1.0f)),
                    Visible(true),
                )
            bindings.setProjectionEntity(entity)
            val material = SceneMaterial.passthrough()
            val mesh =
                SceneMesh.singleSidedQuad(
                    plane.projectionWidthMeters,
                    plane.projectionHeightMeters,
                    material,
                )
            bindings.resources.registerAnchor(material, mesh)
            val sceneObject =
                SceneObject(bindings.scene, mesh, "camera_hwb_projection_anchor", entity)
            bindings.scene.addObject(sceneObject)
            bindings.resources.registerSceneObject(sceneObject)
            val layer =
                SceneQuadLayer(
                    bindings.scene,
                    sdkSwapchain,
                    plane.projectionWidthMeters,
                    plane.projectionHeightMeters,
                    0.5f,
                    0.5f,
                    StereoMode.LeftRight,
                    sceneObject,
                )
            val layerZIndex = bindings.layerZIndex(plane.placementMode)
            layer.setZIndex(layerZIndex)
            layer.setAlphaBlend(
                LayerAlphaBlend(
                    BlendFactor.SOURCE_ALPHA,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                    BlendFactor.ONE,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                )
            )
            bindings.resources.registerLayer(layer)
            bindings.marker(
                CameraHwbProjectionModule.rawProjectionLayerCreatedMarker(
                    sceneObjectHandle = sceneObject.handle,
                    plane = plane,
                    layerZIndex = layerZIndex,
                    carrier = bindings.carrierToken(),
                    carrierMode = bindings.carrierMode(),
                    projectionMarkerFields = bindings.projectionMarkerFields(plane),
                    stereoMarkerFields = bindings.stereoMarkerFields(),
                    videoProjectionMarkerFields =
                        bindings.videoProjectionMarkerFields(videoSettings),
                    publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
                )
            )
            true
          }
          .getOrElse { throwable ->
            bindings.setProjectionEntity(null)
            bindings.marker(
                CameraHwbProjectionModule.rawProjectionLayerCreateFailedMarker(
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-raw-carrier-coordinator"
  }
}
