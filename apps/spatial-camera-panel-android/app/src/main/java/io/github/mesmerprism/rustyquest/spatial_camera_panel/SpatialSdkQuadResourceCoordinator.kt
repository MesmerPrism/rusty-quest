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
  private var layer: SceneQuadLayer? = null
  private var sceneObject: SceneObject? = null
  private var swapchain: SceneSwapchain? = null
  private var surface: AndroidSurface? = null
  private var anchorMesh: SceneMesh? = null
  private var anchorMaterial: SceneMaterial? = null

  fun adoptSwapchain(value: SceneSwapchain) {
    swapchain = value
  }

  fun adoptSurface(value: AndroidSurface?) {
    surface = value
  }

  fun registerAnchor(material: SceneMaterial, mesh: SceneMesh) {
    anchorMaterial = material
    anchorMesh = mesh
  }

  fun registerSceneObject(value: SceneObject) {
    sceneObject = value
  }

  fun registerLayer(value: SceneQuadLayer) {
    layer = value
  }

  fun <T> withLayer(block: (SceneQuadLayer) -> T): T? = layer?.let(block)

  fun cleanupSceneOnly(reason: String): String {
    var layerDestroyed = layer == null
    var sceneObjectDestroyed = sceneObject == null
    var meshDestroyed = anchorMesh == null
    var materialDestroyed = anchorMaterial == null

    layer?.let { ownedLayer ->
      layerDestroyed =
          runCatching {
                ownedLayer.destroy()
                true
              }
              .getOrDefault(false)
    }
    layer = null

    sceneObject?.let { ownedSceneObject ->
      sceneObjectDestroyed =
          runCatching {
                bindings.scene.destroyObject(ownedSceneObject)
                true
              }
              .recoverCatching {
                ownedSceneObject.destroy()
                true
              }
              .getOrDefault(false)
    }
    sceneObject = null
    bindings.onSceneResourcesCleared()

    anchorMesh?.let { ownedMesh ->
      meshDestroyed =
          runCatching {
                ownedMesh.destroy()
                true
              }
              .getOrDefault(false)
    }
    anchorMesh = null

    anchorMaterial?.let { ownedMaterial ->
      materialDestroyed =
          runCatching {
                ownedMaterial.destroy()
                true
              }
              .getOrDefault(false)
    }
    anchorMaterial = null

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
        layer != null ||
            sceneObject != null ||
            swapchain != null ||
            surface != null ||
            anchorMesh != null ||
            anchorMaterial != null
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
