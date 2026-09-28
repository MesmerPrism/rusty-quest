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
  @Volatile private var ownershipRevision = 0L
  private val uncertainLayerRemovals = mutableSetOf<SceneQuadLayer>()
  private val uncertainObjectRemovals = mutableSetOf<SceneObject>()
  private val uncertainMeshRemovals = mutableSetOf<SceneMesh>()
  private val uncertainMaterialRemovals = mutableSetOf<SceneMaterial>()
  private val uncertainSwapchainRemovals = mutableSetOf<SceneSwapchain>()
  private val layers = mutableListOf<SceneQuadLayer>()
  private val sceneObjects = mutableListOf<SceneObject>()
  private var swapchain: SceneSwapchain? = null
  private var surface: AndroidSurface? = null
  private val anchorMeshes = mutableListOf<SceneMesh>()
  private val anchorMaterials = mutableListOf<SceneMaterial>()

  fun ownershipRevision(): Long = ownershipRevision

  fun adoptSwapchain(value: SceneSwapchain) {
    checkNoUncertainRemovals()
    check(swapchain === value || (swapchain == null && surface == null && layers.isEmpty() &&
        sceneObjects.isEmpty() && anchorMeshes.isEmpty() && anchorMaterials.isEmpty())) {
      "SDK projection resources are still owned"
    }
    ownershipRevision = Math.addExact(ownershipRevision, 1L)
    swapchain = value
  }

  fun adoptSurface(value: AndroidSurface?) {
    checkNoUncertainRemovals()
    check(surface == null || surface === value) { "SDK projection surface is still owned" }
    ownershipRevision = Math.addExact(ownershipRevision, 1L)
    surface = value
  }

  fun registerAnchor(material: SceneMaterial, mesh: SceneMesh) {
    ownershipRevision = Math.addExact(ownershipRevision, 1L)
    anchorMaterials += material
    anchorMeshes += mesh
  }

  fun registerSceneObject(value: SceneObject) {
    ownershipRevision = Math.addExact(ownershipRevision, 1L)
    sceneObjects += value
  }

  fun registerLayer(value: SceneQuadLayer) {
    ownershipRevision = Math.addExact(ownershipRevision, 1L)
    layers += value
  }

  fun <T> withLayer(block: (SceneQuadLayer) -> T): T? = layers.lastOrNull()?.let(block)

  fun cleanupSceneOnly(reason: String): String {
    var layerDestroyed = true
    var sceneObjectDestroyed = true
    var meshDestroyed = true
    var materialDestroyed = true

    layers.toList().asReversed().forEach { ownedLayer ->
      val destroyed = if (ownedLayer in uncertainLayerRemovals) false else
          runCatching { ownedLayer.destroy() }.onFailure { failure ->
            uncertainLayerRemovals += ownedLayer
            recordRemovalFailure("LAYER", failure)
          }.isSuccess
      if (destroyed) layers.remove(ownedLayer)
      layerDestroyed = destroyed && layerDestroyed
    }
    if (!layerDestroyed) {
      bindings.marker(SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeSceneAnchorDestroyedMarker(
          reason, false, false, false, false, "uncertain-sdk-layer-removal"))
      return "incomplete"
    }

    sceneObjects.toList().asReversed().forEach { ownedSceneObject ->
      val destroyed = if (ownedSceneObject in uncertainObjectRemovals) false else
          runCatching { bindings.scene.destroyObject(ownedSceneObject) }.onFailure { failure ->
            uncertainObjectRemovals += ownedSceneObject
            recordRemovalFailure("SCENE_OBJECT", failure)
          }.isSuccess
      if (destroyed) sceneObjects.remove(ownedSceneObject)
      sceneObjectDestroyed = destroyed && sceneObjectDestroyed
    }
    if (!sceneObjectDestroyed) {
      bindings.marker(SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeSceneAnchorDestroyedMarker(
          reason, true, false, false, false, "uncertain-sdk-object-removal"))
      return "incomplete"
    }
    bindings.onSceneResourcesCleared()

    anchorMeshes.toList().asReversed().forEach { ownedMesh ->
      val destroyed = if (ownedMesh in uncertainMeshRemovals) false else
          runCatching { ownedMesh.destroy() }.onFailure { failure ->
            uncertainMeshRemovals += ownedMesh
            recordRemovalFailure("ANCHOR_MESH", failure)
          }.isSuccess
      if (destroyed) anchorMeshes.remove(ownedMesh)
      meshDestroyed = destroyed && meshDestroyed
    }


    if (!meshDestroyed) {
      bindings.marker(SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeSceneAnchorDestroyedMarker(
          reason, true, true, false, false, "incomplete"))
      return "incomplete"
    }
    anchorMaterials.toList().asReversed().forEach { ownedMaterial ->
      val destroyed = if (ownedMaterial in uncertainMaterialRemovals) false else
          runCatching { ownedMaterial.destroy() }.onFailure { failure ->
            uncertainMaterialRemovals += ownedMaterial
            recordRemovalFailure("ANCHOR_MATERIAL", failure)
          }.isSuccess
      if (destroyed) anchorMaterials.remove(ownedMaterial)
      materialDestroyed = destroyed && materialDestroyed
    }


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

    if (sceneCleanupDestroyed) swapchain?.let { ownedSwapchain ->
      swapchainDestroyed = if (ownedSwapchain in uncertainSwapchainRemovals) false else
          runCatching { ownedSwapchain.destroy() }.onFailure { failure ->
            uncertainSwapchainRemovals += ownedSwapchain
            recordRemovalFailure("SWAPCHAIN", failure)
          }.isSuccess
    }
    if (sceneCleanupDestroyed && swapchainDestroyed) {
      swapchain = null
      surface = null
    }

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

  private fun checkNoUncertainRemovals() {
    check(uncertainLayerRemovals.isEmpty() && uncertainObjectRemovals.isEmpty() &&
        uncertainMeshRemovals.isEmpty() && uncertainMaterialRemovals.isEmpty() &&
        uncertainSwapchainRemovals.isEmpty()) { "SDK projection removal is uncertain" }
  }

  private fun recordRemovalFailure(stage: String, failure: Throwable) {
    val code = when (failure) {
      is SecurityException -> "SECURITY"
      is IllegalStateException -> "STATE"
      is IllegalArgumentException -> "ARGUMENT"
      is LinkageError -> "NATIVE_LINK"
      else -> "OTHER"
    }
    bindings.marker("channel=sdk-owned-quad-surface-probe status=cleanup-rejected " +
        "stage=$stage code=$code physicalRemoval=uncertain " +
        "onSceneThread=${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()}")
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
