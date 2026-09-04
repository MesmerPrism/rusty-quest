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
    val applyPrivateLayerOverrideForLaunch:
        (String, Long) -> PrivateLayerOverrideApplicationResult,
    val privateLayerOverrideLifecycleCurrent: (Long) -> Boolean,
    val applyRemainingPrivateLayerConfiguration: (String) -> Unit,
    val configureVideoProjection: (SpatialVideoProjectionSettings, String) -> Unit,
    val startVideoProjection: (SpatialVideoProjectionSettings, String) -> Unit,
    val updateNativeLayerFence: (Long, Long, Long, Int) -> Long,
    val startNative: (AndroidSurface, Int, Int, Int, Int, Long, Long, Long, Int) -> Long,
    val updateFromViewer: (String, Boolean) -> Unit,
    val marker: (String) -> Unit,
)

internal data class SpatialCameraHwbProjectionRawStartSequenceBindings(
    val startNativePassthrough: () -> Long,
    val updateNativeStereoOffset: () -> Unit,
    val updateNativeTargetScale: () -> Unit,
    val applyPrivateLayerOverride: () -> PrivateLayerOverrideApplicationResult,
    val applyRemainingPrivateLayerConfiguration: () -> Unit,
    val startEnvironmentDepth: () -> Long,
    val configureVideoProjection: () -> Unit,
    val startVideoProjection: (() -> Unit)?,
    val privateLayerOverrideLifecycleCurrent: () -> Boolean,
    val startNative: () -> Long,
    val cleanup: (String) -> String,
)

internal data class SpatialCameraHwbProjectionRawStartSequenceResult(
    val admitted: Boolean,
    val cleanupStatus: String?,
    val privateLayerApplication: PrivateLayerOverrideApplicationResult?,
    val nativePassthroughStartMask: Long,
    val nativeEnvironmentDepthStartMask: Long,
    val startMask: Long?,
    val error: String?,
    val message: String?,
)

internal object SpatialCameraHwbProjectionRawStartSequence {
  fun execute(
      bindings: SpatialCameraHwbProjectionRawStartSequenceBindings,
  ): SpatialCameraHwbProjectionRawStartSequenceResult {
    var privateLayerApplication: PrivateLayerOverrideApplicationResult? = null
    var nativePassthroughStartMask = 0L
    var nativeEnvironmentDepthStartMask = 0L
    return try {
      nativePassthroughStartMask = bindings.startNativePassthrough()
      bindings.updateNativeStereoOffset()
      bindings.updateNativeTargetScale()
      privateLayerApplication = bindings.applyPrivateLayerOverride()
      if (privateLayerApplication?.readyForNativeStart != true) {
        return rejected(
            bindings = bindings,
            cleanupReason = "camera-hwb-projection-layer-override-not-effective",
            privateLayerApplication = privateLayerApplication,
            nativePassthroughStartMask = nativePassthroughStartMask,
            nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
            error = "PrivateLayerOverrideNotEffective",
            message = privateLayerApplication?.failureReason ?: "unknown",
        )
      }
      bindings.applyRemainingPrivateLayerConfiguration()
      nativeEnvironmentDepthStartMask = bindings.startEnvironmentDepth()
      bindings.configureVideoProjection()
      bindings.startVideoProjection?.invoke()
      if (!bindings.privateLayerOverrideLifecycleCurrent()) {
        return rejected(
            bindings = bindings,
            cleanupReason = "camera-hwb-projection-layer-override-lifecycle-invalidated",
            privateLayerApplication = privateLayerApplication,
            nativePassthroughStartMask = nativePassthroughStartMask,
            nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
            error = "PrivateLayerOverrideLifecycleInvalidated",
            message = "native-lifecycle-invalidated-before-start",
        )
      }
      val startMask = bindings.startNative()
      SpatialCameraHwbProjectionRawStartSequenceResult(
          admitted = true,
          cleanupStatus = null,
          privateLayerApplication = privateLayerApplication,
          nativePassthroughStartMask = nativePassthroughStartMask,
          nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
          startMask = startMask,
          error = null,
          message = null,
      )
    } catch (throwable: Throwable) {
      rejected(
          bindings = bindings,
          cleanupReason = "camera-hwb-projection-pre-start-failed",
          privateLayerApplication = privateLayerApplication,
          nativePassthroughStartMask = nativePassthroughStartMask,
          nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
          error = throwable.javaClass.simpleName,
          message = throwable.message ?: "none",
      )
    }
  }

  private fun rejected(
      bindings: SpatialCameraHwbProjectionRawStartSequenceBindings,
      cleanupReason: String,
      privateLayerApplication: PrivateLayerOverrideApplicationResult?,
      nativePassthroughStartMask: Long,
      nativeEnvironmentDepthStartMask: Long,
      error: String,
      message: String,
  ): SpatialCameraHwbProjectionRawStartSequenceResult {
    val cleanupStatus =
        try {
          bindings.cleanup(cleanupReason)
        } catch (cleanupThrowable: Throwable) {
          "cleanup-failed-${cleanupThrowable.javaClass.simpleName}"
        }
    return SpatialCameraHwbProjectionRawStartSequenceResult(
        admitted = false,
        cleanupStatus = cleanupStatus,
        privateLayerApplication = privateLayerApplication,
        nativePassthroughStartMask = nativePassthroughStartMask,
        nativeEnvironmentDepthStartMask = nativeEnvironmentDepthStartMask,
        startMask = null,
        error = error,
        message = message,
    )
  }
}

internal class SpatialCameraHwbProjectionRawCarrierCoordinator(
    private val bindings: SpatialCameraHwbProjectionRawCarrierBindings,
) {
  private val rawLayerContinuity = SpatialCameraHwbProjectionRawLayerContinuity()
  private val rawLayerPublicationMonitor = Any()

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

    val launchFence =
        createObservedLayerForNewLaunch(sdkSwapchain, videoSettings, "raw-projection-run")
    if (launchFence == null) {
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
    val startSequence =
        SpatialCameraHwbProjectionRawStartSequence.execute(
            SpatialCameraHwbProjectionRawStartSequenceBindings(
                startNativePassthrough = { bindings.startNativePassthrough(reason) },
                updateNativeStereoOffset = { bindings.updateNativeStereoOffset(reason, true) },
                updateNativeTargetScale = { bindings.updateNativeTargetScale(reason, true) },
                applyPrivateLayerOverride = {
                  bindings.applyPrivateLayerOverrideForLaunch(
                      reason,
                      launchFence.launchChallenge,
                  )
                },
                applyRemainingPrivateLayerConfiguration = {
                  bindings.applyRemainingPrivateLayerConfiguration(reason)
                },
                startEnvironmentDepth = { bindings.startEnvironmentDepth(reason) },
                configureVideoProjection = {
                  bindings.configureVideoProjection(videoSettings, reason)
                },
                startVideoProjection =
                    if (videoSettings.active) {
                      { bindings.startVideoProjection(videoSettings, reason) }
                    } else {
                      null
                    },
                privateLayerOverrideLifecycleCurrent = {
                  bindings.privateLayerOverrideLifecycleCurrent(launchFence.launchChallenge)
                },
                startNative = {
                  bindings.startNative(
                      renderSurface,
                      CAMERA_HWB_PROJECTION_WIDTH_PX,
                      CAMERA_HWB_PROJECTION_HEIGHT_PX,
                      CAMERA_HWB_PROJECTION_FRAME_COUNT_UNBOUNDED,
                      readerMaxImages,
                      launchFence.launchChallenge,
                      launchFence.layerGeneration,
                      launchFence.layerSwitchCount,
                      launchFence.layerState.code,
                  )
                },
                cleanup = bindings.cleanup,
            )
        )
    if (!startSequence.admitted) {
      bindings.marker(
          CameraHwbProjectionModule.rawProjectionCompleteAfterCleanupMarker(
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = true,
              cleanupStatus = startSequence.cleanupStatus ?: "cleanup-status-unavailable",
              error = startSequence.error ?: "RawPreStartRejected",
              message = startSequence.message ?: "unknown",
          )
      )
      return
    }
    val nativePassthroughStartMask = startSequence.nativePassthroughStartMask
    val nativePassthroughLayerActive =
        SpatialOpenXrRouteModule.nativePassthroughLayerActive(nativePassthroughStartMask)
    val nativeEnvironmentDepthStartMask = startSequence.nativeEnvironmentDepthStartMask
    val nativeEnvironmentDepthProviderBound =
        SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(
            nativeEnvironmentDepthStartMask
        )
    val startMask = requireNotNull(startSequence.startMask)
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
            launchFenceMarkerFields = launchFence.markerFields(),
        )
    )
    bindings.updateFromViewer(reason, true)
  }

  fun createLayer(
      sdkSwapchain: SceneSwapchain,
      videoSettings: SpatialVideoProjectionSettings,
  ): Boolean =
      createObservedLayerForNewLaunch(sdkSwapchain, videoSettings, "external-layer-create") != null

  fun recordLayerRemoved(reason: String) {
    synchronized(rawLayerPublicationMonitor) { recordLayerRemovedLocked(reason) }
  }

  private fun recordLayerRemovedLocked(reason: String) {
    val removedFence = rawLayerContinuity.recordLayerRemoved() ?: return
    publishLayerFence(removedFence, "removed-$reason")
  }

  private fun createObservedLayerForNewLaunch(
      sdkSwapchain: SceneSwapchain,
      videoSettings: SpatialVideoProjectionSettings,
      reason: String,
  ): SpatialCameraHwbProjectionRawLaunchFence? = synchronized(rawLayerPublicationMonitor) {
    recordLayerRemovedLocked("before-$reason")
    val launchChallenge = SpatialCameraHwbProjectionRawLaunchChallengeSource.next()
    rawLayerContinuity.beginLaunch(launchChallenge)
    if (!createLayerUnobserved(sdkSwapchain, videoSettings)) {
      return@synchronized null
    }
    val fence = rawLayerContinuity.recordLayerCreated(launchChallenge)
    if (publishLayerFence(fence, "created")) fence else null
  }

  private fun publishLayerFence(
      fence: SpatialCameraHwbProjectionRawLaunchFence,
      reason: String,
  ): Boolean {
    val updateMask =
        bindings.updateNativeLayerFence(
            fence.launchChallenge,
            fence.layerGeneration,
            fence.layerSwitchCount,
            fence.layerState.code,
        )
    bindings.marker(
        "channel=camera-hwb-spatial-probe status=raw-projection-layer-fence-updated " +
            "reason=$reason updateMask=$updateMask ${fence.markerFields()} " +
            "continuousObservation=true runtimeCrash=false"
    )
    return updateMask == 1L
  }

  private fun createLayerUnobserved(
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
                    BlendFactor.ONE,
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
