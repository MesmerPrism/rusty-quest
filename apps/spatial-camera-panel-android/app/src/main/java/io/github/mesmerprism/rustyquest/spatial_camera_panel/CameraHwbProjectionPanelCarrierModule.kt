package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.Surface as AndroidSurface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector2
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import java.util.concurrent.CompletableFuture

internal sealed class CameraHwbProjectionPanelEntityCreateResult {
  data class Ready(val entity: Entity) : CameraHwbProjectionPanelEntityCreateResult()

  data class Failed(val marker: String) : CameraHwbProjectionPanelEntityCreateResult()
}

internal sealed class CameraHwbProjectionManualPanelCarrierResult {
  data class Ready(
      val entity: Entity,
      val panelSceneObject: PanelSceneObject,
      val surface: AndroidSurface,
  ) : CameraHwbProjectionManualPanelCarrierResult()

  data class Failed(val marker: String) : CameraHwbProjectionManualPanelCarrierResult()
}

internal object CameraHwbProjectionPanelCarrierModule {
  @OptIn(SpatialSDKExperimentalAPI::class)
  fun createVideoSurfacePanelEntity(
      plane: CameraHwbProjectionPlane,
      carrier: String,
  ): CameraHwbProjectionPanelEntityCreateResult =
      runCatching {
            Entity.createPanelEntity(
                R.id.spatial_camera_projection_surface_panel,
                Transform(plane.pose),
                PanelDimensions(Vector2(plane.projectionWidthMeters, plane.projectionHeightMeters)),
                Hittable(MeshCollision.NoCollision),
                Visible(true),
            )
          }
          .fold(
              onSuccess = { entity -> CameraHwbProjectionPanelEntityCreateResult.Ready(entity) },
              onFailure = { throwable ->
                CameraHwbProjectionPanelEntityCreateResult.Failed(
                    scenePanelCarrierCreateFailedMarker(
                        carrier = carrier,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              },
          )

  @OptIn(SpatialSDKExperimentalAPI::class)
  fun createManualCustomMeshPanel(
      scene: Scene,
      sceneObjectSystem: SceneObjectSystem,
      plane: CameraHwbProjectionPlane,
      carrier: String,
  ): CameraHwbProjectionManualPanelCarrierResult {
    val entity = Entity(R.id.spatial_camera_projection_manual_custom_mesh_panel)
    entity.setComponent(Transform(plane.pose))
    entity.setComponent(PanelDimensions(Vector2(plane.projectionWidthMeters, plane.projectionHeightMeters)))
    entity.setComponent(Visible(true))
    val settings =
        MediaPanelSettings(
            shape = QuadShapeOptions(plane.projectionWidthMeters, plane.projectionHeightMeters),
            display =
                FixedMediaPanelDisplayOptions(
                    CAMERA_HWB_PROJECTION_WIDTH_PX,
                    CAMERA_HWB_PROJECTION_HEIGHT_PX,
                ),
            rendering = MediaPanelRenderOptions(stereoMode = StereoMode.LeftRight),
            input = PanelInputOptions(0),
        )
    val panelSceneObject =
        runCatching {
              PanelSceneObject(
                  scene,
                  entity,
                  settings.toPanelConfigOptions().apply {
                    enableLayer = false
                    layerConfig = null
                    forceSceneTexture = true
                    includeGlass = false
                    sceneMeshCreator = { texture: SceneTexture ->
                      val material =
                          SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
                              .apply {
                                setStereoMode(StereoMode.LeftRight)
                                setUnlit(true)
                              }
                      SceneMesh.singleSidedQuad(
                          plane.projectionWidthMeters / 2.0f,
                          plane.projectionHeightMeters / 2.0f,
                          material,
                      )
                    }
                  },
              )
            }
            .getOrElse { throwable ->
              return CameraHwbProjectionManualPanelCarrierResult.Failed(
                  manualPanelCarrierFailureMarker(
                      status = "manual-panel-carrier-create-failed",
                      carrier = carrier,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
            }
    val surface =
        runCatching { panelSceneObject.getSurface() }
            .getOrElse { throwable ->
              panelSceneObject.destroy()
              return CameraHwbProjectionManualPanelCarrierResult.Failed(
                  manualPanelCarrierFailureMarker(
                      status = "manual-panel-carrier-surface-failed",
                      carrier = carrier,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
            }
    runCatching {
          sceneObjectSystem.addSceneObject(
              entity,
              CompletableFuture<SceneObject>().apply { complete(panelSceneObject) },
          )
        }
        .getOrElse { throwable ->
          panelSceneObject.destroy()
          return CameraHwbProjectionManualPanelCarrierResult.Failed(
              manualPanelCarrierFailureMarker(
                  status = "manual-panel-carrier-add-failed",
                  carrier = carrier,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
    return CameraHwbProjectionManualPanelCarrierResult.Ready(
        entity = entity,
        panelSceneObject = panelSceneObject,
        surface = surface,
    )
  }

  fun scenePanelCarrierCreateFailedMarker(
      carrier: String,
      error: String,
      message: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=scene-panel-carrier-create-failed " +
          "rawCameraProjectionProbe=true scenePanelCarrier=true " +
          "sceneQuadLayerCreated=false nativeStartRequested=false " +
          "panelRegistrationId=spatial_camera_projection_surface_panel " +
          "carrier=${activityMarkerToken(carrier)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun scenePanelCarrierEntitySpawnedMarker(
      entityCreated: Boolean,
      carrier: String,
      plane: CameraHwbProjectionPlane,
      projectionMarkerFields: String,
      stereoMarkerFields: String,
      videoProjectionMarkerFields: String,
      publicMultiStackMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=scene-panel-carrier-entity-spawned " +
          "rawCameraProjectionProbe=true scenePanelCarrier=true entityCreated=$entityCreated " +
          "sceneQuadLayerCreated=false nativeStartRequested=false " +
          "panelRegistrationId=spatial_camera_projection_surface_panel " +
          "carrier=${activityMarkerToken(carrier)} " +
          projectionMarkerFields + " " +
          stereoMarkerFields + " " +
          videoProjectionMarkerFields + " " +
          publicMultiStackMarkerFields + " " +
          "poseSource=${CameraHwbProjectionModule.poseSourceToken(plane)} " +
          "viewerPositionM=${activityVectorMarker(plane.viewerPosition)} " +
          "viewerForward=${activityVectorMarker(plane.forward)} viewerUp=${activityVectorMarker(plane.up)} " +
          "viewerRight=${activityVectorMarker(plane.right)} planeCenterM=${activityVectorMarker(plane.center)} " +
          "planeQuaternion=${activityQuaternionMarker(plane.pose.q)} runtimeCrash=false"

  fun scenePanelSurfaceConsumerCalledMarker(
      surfaceValid: Boolean,
      projectionMarkerFields: String,
      stereoMarkerFields: String,
      videoProjectionMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=scene-panel-surface-consumer-called " +
          "rawCameraProjectionProbe=true scenePanelCarrier=true " +
          "surfaceValid=$surfaceValid " +
          "panelRegistrationId=spatial_camera_projection_surface_panel " +
          "carrier=video-surface-panel-scene-object " +
          "${projectionMarkerFields.trim()} " +
          "${stereoMarkerFields.trim()} " +
          "${videoProjectionMarkerFields.trim()} runtimeCrash=false"

  fun scenePanelReadyMarker(
      panelHandle: Long,
      surfaceValid: Boolean,
      panelLayerUpdateStatus: String,
      projectionMarkerFields: String,
      stereoMarkerFields: String,
      videoProjectionMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=scene-panel-ready " +
          "rawCameraProjectionProbe=true scenePanelCarrier=true " +
          "panelHandle=$panelHandle surfaceValid=$surfaceValid " +
          "panelRegistrationId=spatial_camera_projection_surface_panel " +
          "carrier=video-surface-panel-scene-object " +
          "panelLayerUpdateStatus=${activityMarkerToken(panelLayerUpdateStatus)} " +
          "${projectionMarkerFields.trim()} " +
          "${stereoMarkerFields.trim()} " +
          "${videoProjectionMarkerFields.trim()} runtimeCrash=false"

  fun manualPanelCarrierReadyMarker(
      surfaceValid: Boolean,
      panelLayerUpdateStatus: String,
      carrier: String,
      projectionMarkerFields: String,
      stereoMarkerFields: String,
      videoProjectionMarkerFields: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=manual-panel-carrier-ready " +
          "rawCameraProjectionProbe=true scenePanelCarrier=true manualPanelSceneObject=true " +
          "sceneMeshCreator=single-sided-quad sceneMesh=SceneMesh.singleSidedQuad " +
          "manualPanelSceneObjectCustomMesh=true manualPanelNoHittable=true " +
          "manualPanelNoIsdkGrabbable=true panelInputOptionsClickButtons=0 " +
          "manualPanelForceSceneTexture=true " +
          "panelLayerUpdateStatus=${activityMarkerToken(panelLayerUpdateStatus)} " +
          "surfaceValid=$surfaceValid sceneQuadLayerCreated=false nativeStartRequested=false " +
          "panelRegistrationId=spatial_camera_projection_manual_custom_mesh_panel " +
          "carrier=${activityMarkerToken(carrier)} " +
          projectionMarkerFields + " " +
          stereoMarkerFields + " " +
          videoProjectionMarkerFields + " " +
          "runtimeCrash=false"

  private fun manualPanelCarrierFailureMarker(
      status: String,
      carrier: String,
      error: String,
      message: String,
  ): String =
      "channel=camera-hwb-spatial-probe status=$status " +
          "rawCameraProjectionProbe=true scenePanelCarrier=true manualPanelSceneObject=true " +
          "sceneMeshCreator=single-sided-quad sceneQuadLayerCreated=false " +
          "nativeStartRequested=false panelRegistrationId=spatial_camera_projection_manual_custom_mesh_panel " +
          "carrier=${activityMarkerToken(carrier)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"
}
