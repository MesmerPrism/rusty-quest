package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.Surface as AndroidSurface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.LayerAlphaBlend
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialCameraHwbProjectionPanelNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialCameraHwbProjectionPanelCarrierBindings(
    val scene: Scene,
    val sceneObjectSystem: () -> SceneObjectSystem,
    val routeEnabled: () -> Boolean,
    val manualCustomMeshEnabled: () -> Boolean,
    val nativeState: () -> SpatialCameraHwbProjectionPanelNativeState,
    val panelMediaSettings: () -> MediaPanelSettings,
    val projectionPlane: () -> CameraHwbProjectionPlane,
    val projectionEntity: () -> Entity?,
    val setProjectionEntity: (Entity?) -> Unit,
    val layerZIndex: (CameraHwbProjectionPlacementMode) -> Int,
    val carrierToken: () -> String,
    val panelRegistrationId: () -> String,
    val projectionMarkerFields: (CameraHwbProjectionPlane?) -> String,
    val stereoMarkerFields: () -> String,
    val videoSettings: () -> SpatialVideoProjectionSettings,
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
    val stopNative: () -> Unit,
    val updateFromViewer: (String, Boolean) -> Unit,
    val marker: (String) -> Unit,
)

@OptIn(SpatialSDKExperimentalAPI::class)
internal class SpatialCameraHwbProjectionPanelCarrierCoordinator(
    private val bindings: SpatialCameraHwbProjectionPanelCarrierBindings,
) {
  private var panelEntity: Entity? = null
  private var panelSceneObject: PanelSceneObject? = null
  private var panelSurface: AndroidSurface? = null
  private var surfaceConsumerCalled = false
  private var panelReady = false
  private var nativeStarted = false
  private var startMask = 0L
  private var syntheticVisualPresented = false
  private var readerMaxImages = CAMERA_HWB_PROJECTION_DEFAULT_READER_MAX_IMAGES

  fun videoPanelBindings(): CameraHwbProjectionVideoPanelBindings =
      CameraHwbProjectionVideoPanelBindings(
          adoptSurface = { surface ->
            surfaceConsumerCalled = true
            panelSurface = surface
          },
          settings = { _ -> bindings.panelMediaSettings() },
          adoptPanel = { panel ->
            panelSceneObject = panel
            panelReady = true
            panelSurface = panel.surface
          },
          planeForPlacement = bindings.projectionPlane,
          updateLayer = { plane -> updateLayer(plane, "panel-setup") },
          currentProjectionMarkerFields = { bindings.projectionMarkerFields(null) },
          projectionMarkerFields = bindings.projectionMarkerFields,
          stereoMarkerFields = bindings.stereoMarkerFields,
          videoProjectionMarkerFields = {
            bindings.videoProjectionMarkerFields(bindings.videoSettings())
          },
          startCarrier = ::startIfReady,
          emitMarker = bindings.marker,
      )

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
    val plane = bindings.projectionPlane()
    nativeStarted = false
    startMask = 0L
    surfaceConsumerCalled = false
    panelReady = false
    panelSurface = null
    syntheticVisualPresented = false
    panelSceneObject = null
    this.readerMaxImages = readerMaxImages
    if (bindings.manualCustomMeshEnabled()) {
      val entity = createManualCustomMeshPanel(plane, videoSettings)
      bindings.setProjectionEntity(entity)
      panelEntity = entity
      if (entity != null) {
        startIfReady("manual-custom-mesh-created")
      }
      return
    }
    val entity =
        when (
            val result =
                CameraHwbProjectionPanelCarrierModule.createVideoSurfacePanelEntity(
                    plane = plane,
                    carrier = bindings.carrierToken(),
                )
        ) {
          is CameraHwbProjectionPanelEntityCreateResult.Ready -> result.entity
          is CameraHwbProjectionPanelEntityCreateResult.Failed -> {
            bindings.marker(result.marker)
            null
          }
        }
    bindings.setProjectionEntity(entity)
    panelEntity = entity
    val entityCreated = entity != null
    bindings.marker(
        CameraHwbProjectionPanelCarrierModule.scenePanelCarrierEntitySpawnedMarker(
            entityCreated = entityCreated,
            carrier = bindings.carrierToken(),
            plane = plane,
            projectionMarkerFields = bindings.projectionMarkerFields(plane),
            stereoMarkerFields = bindings.stereoMarkerFields(),
            videoProjectionMarkerFields = bindings.videoProjectionMarkerFields(videoSettings),
            publicMultiStackMarkerFields = SpatialPublicMultiStack.markerFields(),
        )
    )
    if (entityCreated) {
      startIfReady("entity-spawned")
    }
  }

  private fun createManualCustomMeshPanel(
      plane: CameraHwbProjectionPlane,
      videoSettings: SpatialVideoProjectionSettings,
  ): Entity? {
    val carrierResult =
        CameraHwbProjectionPanelCarrierModule.createManualCustomMeshPanel(
            scene = bindings.scene,
            sceneObjectSystem = bindings.sceneObjectSystem(),
            plane = plane,
            carrier = bindings.carrierToken(),
        )
    val readyCarrier =
        when (carrierResult) {
          is CameraHwbProjectionManualPanelCarrierResult.Ready -> carrierResult
          is CameraHwbProjectionManualPanelCarrierResult.Failed -> {
            bindings.marker(carrierResult.marker)
            return null
          }
        }
    panelSceneObject = readyCarrier.panelSceneObject
    panelReady = true
    panelSurface = readyCarrier.surface
    surfaceConsumerCalled = true
    val panelLayerUpdateStatus = updateLayer(plane, "manual-custom-mesh-created")
    bindings.marker(
        CameraHwbProjectionPanelCarrierModule.manualPanelCarrierReadyMarker(
            surfaceValid = readyCarrier.surface.isValid,
            panelLayerUpdateStatus = panelLayerUpdateStatus,
            carrier = bindings.carrierToken(),
            projectionMarkerFields = bindings.projectionMarkerFields(plane),
            stereoMarkerFields = bindings.stereoMarkerFields(),
            videoProjectionMarkerFields = bindings.videoProjectionMarkerFields(videoSettings),
        )
    )
    return readyCarrier.entity
  }

  private fun startIfReady(reason: String) {
    if (!bindings.routeEnabled()) {
      return
    }
    if (nativeStarted) {
      bindings.marker(
          CameraHwbProjectionModule.panelCarrierStartSkippedMarker(
              reason = reason,
              startMask = startMask,
              carrier = bindings.carrierToken(),
          )
      )
      return
    }
    if (bindings.syntheticVisualEnabled() && syntheticVisualPresented) {
      bindings.marker(
          CameraHwbProjectionModule.panelCarrierSyntheticVisualStartSkippedMarker(
              reason = reason,
              carrier = bindings.carrierToken(),
          )
      )
      return
    }
    val entity = panelEntity
    val surface = panelSurface
    if (entity == null || !panelReady || surface?.isValid != true) {
      bindings.marker(
          CameraHwbProjectionModule.panelCarrierStartDeferredMarker(
              reason = reason,
              entityPresent = entity != null,
              panelReady = panelReady,
              surfacePresent = surface != null,
              surfaceValid = surface?.isValid == true,
              surfaceConsumerCalled = surfaceConsumerCalled,
              carrier = bindings.carrierToken(),
          )
      )
      return
    }
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          CameraHwbProjectionModule.panelCarrierStartFailedMarker(
              reason = reason,
              carrier = bindings.carrierToken(),
              error = nativeState.receiptLibraryError,
          )
      )
      return
    }

    val plane = bindings.projectionPlane()
    entity.setComponent(Transform(plane.pose))
    entity.setComponent(
        PanelDimensions(Vector2(plane.projectionWidthMeters, plane.projectionHeightMeters))
    )
    if (!bindings.manualCustomMeshEnabled()) {
      entity.setComponent(Hittable(MeshCollision.NoCollision))
    }
    entity.setComponent(Visible(true))
    val panelLayerUpdateStatus = updateLayer(plane, reason)
    if (bindings.syntheticVisualEnabled()) {
      val canvasDrawn =
          bindings.drawSyntheticVisual(
              surface,
              if (bindings.manualCustomMeshEnabled()) {
                "ManualPanelSceneObjectCustomMesh"
              } else {
                "VideoSurfacePanel"
              },
          )
      syntheticVisualPresented = canvasDrawn
      bindings.marker(
          CameraHwbProjectionModule.panelCarrierSyntheticVisualPresentedMarker(
              surfaceValid = surface.isValid,
              canvasDrawn = canvasDrawn,
              panelRegistrationId = bindings.panelRegistrationId(),
              carrier = bindings.carrierToken(),
              panelLayerUpdateStatus = panelLayerUpdateStatus,
          )
      )
      bindings.updateFromViewer("synthetic-visual-panel-carrier-start", true)
      return
    }

    val startReason = "raw-projection-panel-carrier-start"
    val nativePassthroughStartMask = bindings.startNativePassthrough(startReason)
    val nativePassthroughLayerActive =
        SpatialOpenXrRouteModule.nativePassthroughLayerActive(nativePassthroughStartMask)
    val nativeEnvironmentDepthStartMask = bindings.startEnvironmentDepth(startReason)
    val nativeEnvironmentDepthProviderBound =
        SpatialOpenXrRouteModule.spatialEnvironmentDepthProviderStarted(
            nativeEnvironmentDepthStartMask
        )
    bindings.updateNativeStereoOffset(startReason, true)
    bindings.updateNativeTargetScale(startReason, true)
    bindings.applyPrivateLayerConfiguration(startReason)
    val videoSettings = bindings.videoSettings()
    bindings.configureVideoProjection(videoSettings, startReason)
    if (videoSettings.active) {
      bindings.startVideoProjection(videoSettings, startReason)
    }
    val requestedStartMask =
        runCatching {
              bindings.startNative(
                  surface,
                  CAMERA_HWB_PROJECTION_WIDTH_PX,
                  CAMERA_HWB_PROJECTION_HEIGHT_PX,
                  CAMERA_HWB_PROJECTION_FRAME_COUNT_UNBOUNDED,
                  readerMaxImages,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  CameraHwbProjectionModule.panelCarrierStartFailedMarker(
                      reason = reason,
                      carrier = bindings.carrierToken(),
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                      panelLayerUpdateStatus = panelLayerUpdateStatus,
                  )
              )
              return
            }
    nativeStarted = true
    startMask = requestedStartMask
    bindings.marker(
        CameraHwbProjectionModule.panelCarrierNativeStartRequestedMarker(
            surfaceValid = surface.isValid,
            startMask = requestedStartMask,
            readerMaxImages = readerMaxImages,
            panelRegistrationId = bindings.panelRegistrationId(),
            carrier = bindings.carrierToken(),
            panelLayerUpdateStatus = panelLayerUpdateStatus,
            projectionMarkerFields = bindings.projectionMarkerFields(plane),
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
    bindings.updateFromViewer(startReason, true)
  }

  fun updateLayer(plane: CameraHwbProjectionPlane, reason: String): String {
    val panel = panelSceneObject ?: return "panel-scene-object-missing"
    return runCatching {
          panel.layer?.setAlphaBlend(
              LayerAlphaBlend(
                  BlendFactor.SOURCE_ALPHA,
                  BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                  BlendFactor.ONE,
                  BlendFactor.ONE_MINUS_SOURCE_ALPHA,
              )
          )
          if (bindings.manualCustomMeshEnabled()) {
            panel.setPosition(plane.center)
            panel.setRotationQuat(plane.pose.q)
            panel.setScale(Vector3(1.0f, 1.0f, 1.0f))
            panel.setIsVisible(true)
            return "updated-manual-custom-mesh-scene-object-layer-skipped"
          }
          panelEntity?.setComponent(Hittable(MeshCollision.NoCollision))
          panel.setPosition(plane.center)
          panel.setRotationQuat(plane.pose.q)
          panel.setScale(Vector3(1.0f, 1.0f, 1.0f))
          panel.layer?.setZIndex(bindings.layerZIndex(plane.placementMode))
              ?: return "panel-layer-missing"
          panel.setIsVisible(true)
          "updated-panel-scene-object"
        }
        .getOrElse { throwable ->
          bindings.marker(
              CameraHwbProjectionModule.scenePanelCarrierUpdateFailedMarker(
                  reason = reason,
                  plane = plane,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
          "failed-${throwable.javaClass.simpleName}"
        }
  }

  fun cleanup(reason: String): String {
    val nativeState = bindings.nativeState()
    var nativeStopped = true
    if (nativeStarted && nativeState.receiptLibraryLoaded) {
      nativeStopped =
          runCatching {
                bindings.stopNative()
                true
              }
              .getOrDefault(false)
    }
    var sceneObjectDestroyed = !bindings.manualCustomMeshEnabled() || panelSceneObject == null
    val manualPanelSceneObject = panelSceneObject
    if (bindings.manualCustomMeshEnabled()) {
      manualPanelSceneObject?.let { sceneObject ->
        sceneObjectDestroyed =
            runCatching {
                  bindings.scene.destroyObject(sceneObject)
                  true
                }
                .recoverCatching {
                  sceneObject.destroy()
                  true
                }
                .getOrDefault(false)
      }
    }
    var entityDestroyed = panelEntity == null
    val entity = panelEntity
    entity?.let {
      entityDestroyed =
          runCatching {
                it.destroy()
                true
              }
              .getOrDefault(false)
    }
    panelEntity = null
    panelSceneObject = null
    panelSurface = null
    surfaceConsumerCalled = false
    panelReady = false
    nativeStarted = false
    startMask = 0L
    if (bindings.projectionEntity() == entity) {
      bindings.setProjectionEntity(null)
    }

    val cleanupStatus =
        if (nativeStopped && entityDestroyed && sceneObjectDestroyed) {
          "destroyed"
        } else {
          "incomplete"
        }
    if (
        !nativeStopped ||
            !entityDestroyed ||
            !sceneObjectDestroyed ||
            reason != "camera-hwb-projection-pre-run"
    ) {
      bindings.marker(
          CameraHwbProjectionModule.scenePanelCarrierDestroyedMarker(
              reason = reason,
              nativeStopped = nativeStopped,
              entityDestroyed = entityDestroyed,
              sceneObjectDestroyed = sceneObjectDestroyed,
              carrier = bindings.carrierToken(),
              cleanupStatus = cleanupStatus,
          )
      )
    }
    return cleanupStatus
  }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-panel-carrier-coordinator"
  }
}
