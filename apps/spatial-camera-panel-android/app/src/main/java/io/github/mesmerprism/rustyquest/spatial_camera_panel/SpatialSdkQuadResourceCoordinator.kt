package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.view.Surface as AndroidSurface
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneQuadLayer
import com.meta.spatial.runtime.SceneSwapchain

internal data class SpatialSdkQuadResourceBindings(
    val scene: Scene,
    val marker: (String) -> Unit,
    val onSceneResourcesCleared: () -> Unit,
)

internal class SpatialSdkQuadResourceCoordinator(
    private val bindings: SpatialSdkQuadResourceBindings,
) {
  private val layers = mutableListOf<SceneQuadLayer>()
  private val sceneObjects = mutableListOf<SceneObject>()
  private var swapchain: SceneSwapchain? = null
  private var surface: AndroidSurface? = null
  private val anchorMeshes = mutableListOf<SceneMesh>()
  private val anchorMaterials = mutableListOf<SceneMaterial>()

  fun adoptSwapchain(value: SceneSwapchain) {
    swapchain = value
  }

  fun adoptSurface(value: AndroidSurface?) {
    surface = value
  }

  fun registerAnchor(material: SceneMaterial, mesh: SceneMesh) {
    anchorMaterials += material
    anchorMeshes += mesh
  }

  fun registerSceneObject(value: SceneObject) {
    sceneObjects += value
  }

  fun registerLayer(value: SceneQuadLayer) {
    layers += value
  }

  fun <T> withLayer(block: (SceneQuadLayer) -> T): T? = layers.lastOrNull()?.let(block)

  fun cleanupSceneOnly(reason: String): String {
    var layerDestroyed = true
    var sceneObjectDestroyed = true
    var meshDestroyed = true
    var materialDestroyed = true

    layers.asReversed().forEach { ownedLayer ->
      layerDestroyed = runCatching { ownedLayer.destroy() }.isSuccess && layerDestroyed
    }
    layers.clear()

    sceneObjects.asReversed().forEach { ownedSceneObject ->
      val destroyed =
          runCatching {
                bindings.scene.destroyObject(ownedSceneObject)
                true
              }
              .recoverCatching {
                ownedSceneObject.destroy()
                true
              }
              .getOrDefault(false)
      sceneObjectDestroyed = destroyed && sceneObjectDestroyed
    }
    sceneObjects.clear()
    bindings.onSceneResourcesCleared()

    anchorMeshes.asReversed().forEach { ownedMesh ->
      meshDestroyed = runCatching { ownedMesh.destroy() }.isSuccess && meshDestroyed
    }
    anchorMeshes.clear()

    anchorMaterials.asReversed().forEach { ownedMaterial ->
      materialDestroyed = runCatching { ownedMaterial.destroy() }.isSuccess && materialDestroyed
    }
    anchorMaterials.clear()

    val cleanupStatus =
        if (layerDestroyed && sceneObjectDestroyed && meshDestroyed && materialDestroyed) {
          "destroyed"
        } else {
          "incomplete"
        }
    if (cleanupStatus == "incomplete") {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeSceneAnchorDestroyedMarker(
              reason = reason,
              layerDestroyed = layerDestroyed,
              sceneObjectDestroyed = sceneObjectDestroyed,
              anchorMeshDestroyed = meshDestroyed,
              anchorMaterialDestroyed = materialDestroyed,
              cleanupStatus = cleanupStatus,
          )
      )
    }
    return cleanupStatus
  }

  fun cleanup(reason: String): String {
    val hadResources =
        layers.isNotEmpty() ||
            sceneObjects.isNotEmpty() ||
            swapchain != null ||
            surface != null ||
            anchorMeshes.isNotEmpty() ||
            anchorMaterials.isNotEmpty()
    val sceneCleanupStatus = cleanupSceneOnly(reason)
    val sceneCleanupDestroyed = sceneCleanupStatus == "destroyed"
    var swapchainDestroyed = swapchain == null

    swapchain?.let { ownedSwapchain ->
      swapchainDestroyed =
          runCatching {
                ownedSwapchain.destroy()
                true
              }
              .getOrDefault(false)
    }
    swapchain = null
    surface = null

    val cleanupStatus =
        if (sceneCleanupDestroyed && swapchainDestroyed) {
          "destroyed"
        } else {
          "incomplete"
        }
    if ((hadResources && reason != "pre-run") || cleanupStatus == "incomplete") {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeDestroyedMarker(
              reason = reason,
              sceneCleanupStatus = sceneCleanupStatus,
              swapchainDestroyed = swapchainDestroyed,
              cleanupStatus = cleanupStatus,
          )
      )
    }
    return cleanupStatus
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  fun poseFromViewer(distanceMeters: Float): Pose {
    val viewerPose = runCatching { bindings.scene.getViewerPose() }.getOrNull()
    if (viewerPose == null) {
      return Pose(
          Vector3(0.0f, 1.20f, -distanceMeters),
          Quaternion.fromDirection(Vector3(0.0f, 0.0f, -1.0f), Vector3(0.0f, 1.0f, 0.0f)),
      )
    }
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val center = viewerPose.t + forward * distanceMeters
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  companion object {
    const val MODULE_ID = "spatial-sdk-quad-resource-coordinator"
  }
}
