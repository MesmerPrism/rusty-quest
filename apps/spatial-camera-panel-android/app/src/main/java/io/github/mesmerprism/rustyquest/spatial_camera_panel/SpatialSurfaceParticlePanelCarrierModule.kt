package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.Surface as AndroidSurface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import java.util.concurrent.CompletableFuture

internal sealed class SpatialSurfaceParticleManualPanelCarrierResult {
  data class Ready(
      val entity: Entity,
      val panelSceneObject: PanelSceneObject,
      val surface: AndroidSurface,
  ) : SpatialSurfaceParticleManualPanelCarrierResult()

  data class Failed(val marker: String) : SpatialSurfaceParticleManualPanelCarrierResult()
}

internal data class SpatialSurfaceParticleVideoPanelBindings(
    val adoptSurface: (AndroidSurface) -> Unit,
    val settings: (Entity) -> MediaPanelSettings,
    val carrier: () -> String,
    val placementMarkerFields: () -> String,
    val stereoMarkerFields: () -> String,
    val startLayer: (AndroidSurface) -> Unit,
    val adoptPanel: (PanelSceneObject) -> Unit,
    val updateLayer: () -> String,
    val emitMarker: (String) -> Unit,
)

internal object SpatialSurfaceParticlePanelCarrierModule {
  fun videoSurfacePanelRegistration(
      bindings: SpatialSurfaceParticleVideoPanelBindings
  ): PanelRegistration =
      VideoSurfacePanelRegistration(
          R.id.spatial_camera_surface_panel,
          surfaceConsumer = { _, surface ->
            bindings.adoptSurface(surface)
            bindings.emitMarker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticleSurfaceConsumerCalledMarker(
                    surfaceValid = surface.isValid,
                    carrier = bindings.carrier(),
                    placementMarkerFields = bindings.placementMarkerFields(),
                    stereoMarkerFields = bindings.stereoMarkerFields(),
                )
            )
            bindings.startLayer(surface)
          },
          settingsCreator = bindings.settings,
          panelSetup = { panel, _ ->
            bindings.adoptPanel(panel)
            val layerUpdateStatus = bindings.updateLayer()
            bindings.emitMarker(
                SpatialSurfaceParticleRouteModule.nativeSurfaceParticleSurfacePanelReadyMarker(
                    panelHandle = panel.handle,
                    layerUpdateStatus = layerUpdateStatus,
                    surfaceValid = panel.surface.isValid,
                    carrier = bindings.carrier(),
                    placementMarkerFields = bindings.placementMarkerFields(),
                    stereoMarkerFields = bindings.stereoMarkerFields(),
                )
            )
          },
      )

  @OptIn(SpatialSDKExperimentalAPI::class)
  fun createManualCustomMeshPanel(
      scene: Scene,
      sceneObjectSystem: SceneObjectSystem,
      pose: Pose,
      surfaceWidthMeters: Float,
      surfaceHeightMeters: Float,
      visible: Boolean,
      reason: String,
      carrier: String,
  ): SpatialSurfaceParticleManualPanelCarrierResult {
    val entity = Entity(R.id.spatial_camera_surface_panel)
    entity.setComponent(Transform(pose))
    entity.setComponent(PanelDimensions(Vector2(surfaceWidthMeters, surfaceHeightMeters)))
    entity.setComponent(Visible(visible))
    val settings =
        SpatialSurfaceParticleRouteModule.manualCarrierMediaSettings(
            surfaceWidthMeters,
            surfaceHeightMeters,
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
                          surfaceWidthMeters / 2.0f,
                          surfaceHeightMeters / 2.0f,
                          material,
                      )
                    }
                  },
              )
            }
            .getOrElse { throwable ->
              return SpatialSurfaceParticleManualPanelCarrierResult.Failed(
                  manualPanelCarrierFailureMarker(
                      status = "manual-panel-carrier-create-failed",
                      reason = reason,
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
              return SpatialSurfaceParticleManualPanelCarrierResult.Failed(
                  manualPanelCarrierFailureMarker(
                      status = "manual-panel-carrier-surface-failed",
                      reason = reason,
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
          return SpatialSurfaceParticleManualPanelCarrierResult.Failed(
              manualPanelCarrierFailureMarker(
                  status = "manual-panel-carrier-add-failed",
                  reason = reason,
                  carrier = carrier,
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
    return SpatialSurfaceParticleManualPanelCarrierResult.Ready(
        entity = entity,
        panelSceneObject = panelSceneObject,
        surface = surface,
    )
  }

  fun manualPanelCarrierReadyMarker(
      reason: String,
      carrier: String,
      surfaceValid: Boolean,
      layerUpdateStatus: String,
      placementMarkerFields: String,
      stereoMarkerFields: String,
  ): String =
      "channel=native-surface-particle-layer status=manual-panel-carrier-ready " +
          "renderPolicy=native-vulkan-wsi-surface-panel reason=${activityMarkerToken(reason)} " +
          "surfaceParticleProjectionCarrier=${activityMarkerToken(carrier)} " +
          "manualPanelSceneObjectCustomMesh=true sceneMeshCreator=single-sided-quad " +
          "sceneMesh=SceneMesh.singleSidedQuad manualPanelNoHittable=true " +
          "manualPanelNoIsdkGrabbable=true panelInputOptionsClickButtons=0 " +
          "manualPanelForceSceneTexture=true manualPanelEnableLayer=false " +
          "manualPanelLayerConfig=none surfaceValid=$surfaceValid " +
          "particleLayerPanelLayerUpdateStatus=${activityMarkerToken(layerUpdateStatus)} " +
          "nativeStartRequested=false panelRegistrationId=manual-scene-object " +
          placementMarkerFields + " " +
          stereoMarkerFields + " runtimeCrash=false"

  private fun manualPanelCarrierFailureMarker(
      status: String,
      reason: String,
      carrier: String,
      error: String,
      message: String,
  ): String =
      "channel=native-surface-particle-layer status=$status " +
          "renderPolicy=native-vulkan-wsi-surface-panel reason=${activityMarkerToken(reason)} " +
          "surfaceParticleProjectionCarrier=${activityMarkerToken(carrier)} " +
          "manualPanelSceneObjectCustomMesh=true sceneMeshCreator=single-sided-quad " +
          "nativeStartRequested=false error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"
}
