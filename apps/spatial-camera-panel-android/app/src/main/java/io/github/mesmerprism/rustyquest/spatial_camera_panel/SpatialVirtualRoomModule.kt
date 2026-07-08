package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.net.Uri
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.SortOrder
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class SpatialVirtualRoomProjectionState(
    val placementModeToken: String,
    val carrierToken: String,
    val carrierProperty: String,
    val roomRenderOrderToken: String,
)

internal class SpatialVirtualRoomModule(
    private val context: Context,
    private val scene: Scene,
    private val activityScope: CoroutineScope,
    private val loadGlxf: suspend (Uri, Entity, (GLXFInfo) -> Unit) -> Unit,
    private val marker: (String) -> Unit,
) {
  private var roomEntity: Entity? = null
  private var skyboxEntity: Entity? = null
  private var skyboxSceneObject: SceneObject? = null
  private var skyboxMesh: SceneMesh? = null
  private var skyboxMaterial: SceneMaterial? = null
  private var skyboxTexture: SceneTexture? = null
  private var loadJob: Job? = null
  private var configured = false

  var loaded = false
    private set

  fun enabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(VIRTUAL_ROOM_ENABLED_PROPERTY) ?: false

  fun skyboxEnabled(): Boolean = currentSkyboxMode() != SpatialSkyboxMode.None

  fun isStarted(): Boolean = loadJob != null || roomEntity != null

  fun shouldConfigureScene(): Boolean = (enabled() || skyboxEnabled()) && !configured

  fun runIfRequested(
      reason: String,
      projectionState: SpatialVirtualRoomProjectionState,
      onLoaded: () -> Unit,
  ) {
    if (!enabled() || isStarted()) {
      return
    }
    runCatching {
          NetworkedAssetLoader.init(
              File(context.cacheDir.canonicalPath),
              OkHttpAssetFetcher(),
          )
        }
        .onFailure { throwable ->
          marker(
              "channel=spatial-virtual-room status=asset-loader-init-failed " +
                  "module=$MODULE_ID reason=${activityMarkerToken(reason)} " +
                  "error=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                  "message=${activityMarkerToken(throwable.message ?: "none")} runtimeCrash=false"
          )
          return
        }
    val root = Entity.create()
    roomEntity = root
    marker(
        "channel=spatial-virtual-room status=load-requested " +
            "module=$MODULE_ID reason=${activityMarkerToken(reason)} " +
            "sceneUri=${activityMarkerToken(SCENE_URI)} " +
            "roomAssetSource=packaged-glxf virtualRoomSceneAuthoring=meta-spatial-editor " +
            "sampleRoomAssetPolicy=local-launch-input genericModuleSupport=true " +
            "projectionDefaultPlacementMode=${projectionState.placementModeToken} " +
            "projectionCarrier=${projectionState.carrierToken} " +
            "projectionCarrierProperty=${projectionState.carrierProperty} " +
            "projectionRoomRenderOrder=${projectionState.roomRenderOrderToken} " +
            "rightSecondaryTogglesFullFov=false " +
            "projectionDisplaySurface=video-plus-custom-camera-stack " +
            "legacyLauncherPanelSuppressed=true " +
            "mrukPlacement=false passthroughRoomPlacement=false highRateJsonPayload=false"
    )
    loadJob =
        activityScope.launch {
          runCatching {
                loadGlxf(
                    Uri.parse(SCENE_URI),
                    root,
                    { composition -> handleLoaded(composition, onLoaded) },
                )
              }
              .onFailure { throwable ->
                marker(
                    "channel=spatial-virtual-room status=load-failed " +
                        "module=$MODULE_ID " +
                        "sceneUri=${activityMarkerToken(SCENE_URI)} " +
                        "error=${activityMarkerToken(throwable.javaClass.simpleName)} " +
                        "message=${activityMarkerToken(throwable.message ?: "none")} runtimeCrash=false"
                )
                destroy("load-failed")
              }
        }
  }

  private fun handleLoaded(composition: GLXFInfo, afterLoaded: () -> Unit) {
    loaded = true
    val environmentEntity =
        runCatching { composition.getNodeByName(ENVIRONMENT_NODE).entity }.getOrNull()
    val environmentMesh = environmentEntity?.tryGetComponent<Mesh>()
    marker(
        "channel=spatial-virtual-room status=loaded " +
            "module=$MODULE_ID " +
            "sceneUri=${activityMarkerToken(SCENE_URI)} " +
            "environmentNode=${activityMarkerToken(ENVIRONMENT_NODE)} " +
            "environmentNodeFound=${environmentEntity != null} " +
            "environmentMeshFound=${environmentMesh != null} " +
            "environmentMaterialPolicy=sample-authored-normal-materials " +
            "roomDepthWrite=sample-authored roomSortOrder=sample-authored " +
            "roomProjectionForegroundPolicy=normal-room-depth-order " +
            "roomAssetSource=packaged-glxf genericModuleSupport=true " +
            "privateSourceAssetPackaged=false highRateJsonPayload=false"
    )
    afterLoaded()
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  fun configureScene(reason: String, projectionState: SpatialVirtualRoomProjectionState) {
    val virtualRoomEnabled = enabled()
    val skyboxMode = currentSkyboxMode()
    val skyboxEnabled = skyboxMode != SpatialSkyboxMode.None
    if ((!virtualRoomEnabled && !skyboxEnabled) || configured) {
      return
    }
    configured = true
    val lightingConfigured =
        runCatching {
              scene.setLightingEnvironment(
                  ambientColor = Vector3(0.0f),
                  sunColor = Vector3(7.0f, 7.0f, 7.0f),
                  sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
                  environmentIntensity = 0.3f,
              )
              true
            }
            .getOrDefault(false)
    val iblConfigured =
        runCatching {
              scene.updateIBLEnvironment(IBL_ASSET)
              true
            }
            .getOrDefault(false)
    val skydomeResourceId =
        context.resources.getIdentifier(SKYDOME_RESOURCE, "drawable", context.packageName)
    val skyboxCreated =
        when (skyboxMode) {
          SpatialSkyboxMode.None -> false
          SpatialSkyboxMode.SampleMeshUri -> createSampleSkybox(skydomeResourceId)
          SpatialSkyboxMode.CustomSceneMesh -> createCustomSkybox(skydomeResourceId)
        }
    val channel = if (virtualRoomEnabled) "spatial-virtual-room" else "spatial-skybox"
    val module = if (virtualRoomEnabled) MODULE_ID else SKYBOX_MODULE_ID
    marker(
        "channel=$channel status=scene-configured " +
            "module=$module reason=${activityMarkerToken(reason)} " +
            "virtualRoomEnabled=$virtualRoomEnabled skyboxOnly=${skyboxEnabled && !virtualRoomEnabled} " +
            "skyboxMode=${skyboxMode.markerToken} " +
            "lightingConfigured=$lightingConfigured iblConfigured=$iblConfigured " +
            "iblAsset=${activityMarkerToken(IBL_ASSET)} " +
            "skydomeResource=${activityMarkerToken(SKYDOME_RESOURCE)} " +
            "skydomeResourceFound=${skydomeResourceId != 0} skyboxCreated=$skyboxCreated " +
            skyboxMarkerFields(skyboxMode) + " " +
            "referenceSpace=LOCAL_FLOOR viewOrigin=0.0;0.0;2.0 yawDegrees=180.0 " +
            "projectionDefaultPlacementMode=${projectionState.placementModeToken} " +
            "projectionCarrier=${projectionState.carrierToken} " +
            "projectionCarrierProperty=${projectionState.carrierProperty} " +
            "rightSecondaryTogglesFullFov=false " +
            "projectionRoomRenderOrder=${projectionState.roomRenderOrderToken} " +
            "legacyLauncherPanelSuppressed=true " +
            "roomAssetSource=packaged-glxf roomMeshLoaded=$virtualRoomEnabled " +
            "mrukPlacement=false passthroughRoomPlacement=false " +
            "runtimeCrash=false"
    )
  }

  private fun createSampleSkybox(skydomeResourceId: Int): Boolean {
    if (skydomeResourceId == 0 || skyboxEntity != null) {
      return false
    }
    return runCatching {
          val entity =
              Entity.create(
                  Mesh(
                      Uri.parse(SKYBOX_MESH_URI),
                      hittable = MeshCollision.NoCollision,
                  ),
                  Material().apply {
                    baseTextureAndroidResourceId = skydomeResourceId
                    unlit = true
                  },
                  Transform(Pose(Vector3(0.0f, 0.0f, 0.0f))),
              )
          skyboxEntity = entity
          true
        }
        .getOrDefault(false)
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun createCustomSkybox(skydomeResourceId: Int): Boolean {
    if (skydomeResourceId == 0 || skyboxSceneObject != null) {
      return false
    }
    return runCatching {
          val texture = SceneTexture.fromResource(context, skydomeResourceId)
          val material =
              SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER).apply {
                setUnlit(true)
                setSidedness(MaterialSidedness.BACK_SIDED)
                setBlendMode(BlendMode.OPAQUE)
                setDepthWrite(DepthWrite.DISABLE)
                setDepthTest(DepthTest.LESS_OR_EQUAL)
                setSortOrder(SortOrder.PREPROCESS)
                setRenderOrder(CUSTOM_SKYBOX_RENDER_ORDER)
              }
          val mesh = SceneMesh.skybox(CUSTOM_SKYBOX_RADIUS_METERS, material)
          val entity = Entity.create(Transform(Pose(Vector3(0.0f, 0.0f, 0.0f))))
          val sceneObject = SceneObject(scene, mesh, CUSTOM_SKYBOX_SCENE_OBJECT_NAME, entity)
          scene.addObject(sceneObject)
          skyboxTexture = texture
          skyboxMaterial = material
          skyboxMesh = mesh
          skyboxEntity = entity
          skyboxSceneObject = sceneObject
          true
        }
        .getOrDefault(false)
  }

  private fun skyboxMarkerFields(mode: SpatialSkyboxMode): String =
      when (mode) {
        SpatialSkyboxMode.None ->
            "skyboxRenderer=disabled skyboxMeshUri=none " +
                "skyboxEntityCreateApi=none skyboxDepthWrite=disabled " +
                "skyboxDepthTest=disabled skyboxSortOrder=none skyboxRenderOrder=none " +
                "skyboxRadiusMeters=none skyboxMaterialSidedness=none " +
                "skyboxProjectionForegroundPolicy=no-skybox"
        SpatialSkyboxMode.SampleMeshUri ->
            "skyboxRenderer=sample-toolkit-mesh-uri " +
                "skyboxMeshUri=$SKYBOX_MESH_URI " +
                "skyboxEntityCreateApi=toolkit-varargs-first-room-replay " +
                "skyboxDepthWrite=sample-authored skyboxDepthTest=sample-authored " +
                "skyboxSortOrder=sample-authored skyboxRenderOrder=sample-authored " +
                "skyboxRadiusMeters=sample-authored skyboxMaterialSidedness=sample-authored " +
                "skyboxProjectionForegroundPolicy=sample-skybox-layering-under-test"
        SpatialSkyboxMode.CustomSceneMesh ->
            "skyboxRenderer=custom-runtime-scene-mesh-skybox " +
                "skyboxMeshUri=SceneMesh.skybox " +
                "skyboxEntityCreateApi=scene-object-runtime-skydome " +
                "skyboxDepthWrite=disabled skyboxDepthTest=less-or-equal " +
                "skyboxSortOrder=preprocess " +
                "skyboxRenderOrder=$CUSTOM_SKYBOX_RENDER_ORDER " +
                "skyboxRadiusMeters=${activityMarkerFloat(CUSTOM_SKYBOX_RADIUS_METERS)} " +
                "skyboxMaterialSidedness=back-sided " +
                "skyboxProjectionForegroundPolicy=scene-layer-over-background-skybox"
      }

  fun destroy(reason: String) {
    loadJob?.cancel()
    loadJob = null
    roomEntity?.let { entity -> runCatching { entity.destroy() } }
    skyboxSceneObject?.let { sceneObject ->
      runCatching { scene.destroyObject(sceneObject) }.recoverCatching { sceneObject.destroy() }
    }
    skyboxMesh?.let { mesh -> runCatching { mesh.destroy() } }
    skyboxMaterial?.let { material -> runCatching { material.destroy() } }
    skyboxTexture?.let { texture -> runCatching { texture.destroy() } }
    skyboxEntity?.let { entity -> runCatching { entity.destroy() } }
    val hadRoom = roomEntity != null || skyboxEntity != null || skyboxSceneObject != null
    roomEntity = null
    skyboxEntity = null
    skyboxSceneObject = null
    skyboxMesh = null
    skyboxMaterial = null
    skyboxTexture = null
    configured = false
    loaded = false
    if (hadRoom) {
      marker(
          "channel=spatial-virtual-room status=destroyed " +
              "module=$MODULE_ID reason=${activityMarkerToken(reason)}"
      )
    }
  }

  private fun currentSkyboxMode(): SpatialSkyboxMode {
    val modeToken =
        activityReadSystemProperty(SKYBOX_MODE_PROPERTY).trim().lowercase(Locale.US).replace("_", "-")
    return when (modeToken) {
      "sample", "sample-mesh", "sample-mesh-uri", "mesh://skybox", "mesh-skybox" ->
          SpatialSkyboxMode.SampleMeshUri
      "custom", "custom-skybox", "custom-scene-mesh", "runtime-scene-mesh" ->
          SpatialSkyboxMode.CustomSceneMesh
      "none", "off", "disabled", "" ->
          if (activityReadOptionalBooleanSystemProperty(SKYBOX_ENABLED_PROPERTY) == true) {
            SpatialSkyboxMode.SampleMeshUri
          } else {
            SpatialSkyboxMode.None
          }
      else -> SpatialSkyboxMode.None
    }
  }

  companion object {
    const val MODULE_ID = "spatial-sdk-packaged-virtual-room"
    const val ENABLED_PROPERTY = "debug.rustyquest.spatial.virtual_room.enabled"
    const val SKYBOX_MODULE_ID = "spatial-sdk-skybox-only"
    const val SKYBOX_ENABLED_PROPERTY = "debug.rustyquest.spatial.skybox.enabled"
    const val SKYBOX_MODE_PROPERTY = "debug.rustyquest.spatial.skybox.mode"

    private const val VIRTUAL_ROOM_ENABLED_PROPERTY = ENABLED_PROPERTY
    private const val SCENE_URI = "apk:///scenes/Composition.glxf"
    private const val ENVIRONMENT_NODE = "Environment"
    private const val IBL_ASSET = "environment.env"
    private const val SKYDOME_RESOURCE = "skydome"
    private const val SKYBOX_MESH_URI = "mesh://skybox"
    private const val CUSTOM_SKYBOX_SCENE_OBJECT_NAME = "rusty_quest_background_skybox"
    private const val CUSTOM_SKYBOX_RADIUS_METERS = 280.0f
    private const val CUSTOM_SKYBOX_RENDER_ORDER = -1000
  }
}
