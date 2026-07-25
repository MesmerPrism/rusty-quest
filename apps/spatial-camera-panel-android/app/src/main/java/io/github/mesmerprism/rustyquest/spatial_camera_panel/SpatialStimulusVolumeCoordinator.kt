package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialStimulusVolumeBindings(
    val scene: Scene,
    val poseFromViewer: (Float) -> Pose,
    val marker: (String) -> Unit,
)

@OptIn(SpatialSDKExperimentalAPI::class)
internal class SpatialStimulusVolumeCoordinator(
    private val bindings: SpatialStimulusVolumeBindings,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var resolved = false
  private var started = false
  private var completed = false
  private var sceneTicks = 0L
  private var viewLockUpdateFailures = 0L
  private var carrierEntity: Entity? = null
  private var sceneObject: SceneObject? = null
  private var mesh: SceneMesh? = null
  private var material: SceneMaterial? = null

  fun runIfRequested(reason: String) {
    if (resolved) {
      return
    }
    resolved = true
    val config = SpatialStimulusVolumeRoute.resolve()
    if (!config.enabled) {
      if (config.rejectionReason != "disabled" &&
          config.rejectionReason != "missing-or-invalid-enable") {
        bindings.marker(SpatialStimulusVolumeRoute.inertMarker(reason, config))
      }
      return
    }

    started = true
    bindings.marker(SpatialStimulusVolumeRoute.startMarker(reason, config))
    handler.post {
      runCatching { createCarrier() }
          .onSuccess {
            bindings.marker(SpatialStimulusVolumeRoute.effectiveMarker(config))
            bindings.marker(SpatialStimulusVolumeRoute.renderReadyMarker())
            handler.postDelayed(
                {
                  if (!completed) {
                    completed = true
                    bindings.marker(
                        SpatialStimulusVolumeRoute.completeMarker(
                            sceneTicks,
                            viewLockUpdateFailures,
                        )
                    )
                    destroy("bounded-hold-complete")
                  }
                },
                config.holdMs,
            )
          }
          .onFailure { error ->
            bindings.marker(SpatialStimulusVolumeRoute.failureMarker(error))
            destroy("create-failed")
          }
    }
  }

  fun onSceneTick() {
    if (!started || completed) {
      return
    }
    sceneTicks += 1L
    val entity = carrierEntity ?: return
    runCatching {
          entity.setComponent(Transform(bindings.poseFromViewer(CARRIER_DISTANCE_METERS)))
        }
        .onFailure { viewLockUpdateFailures += 1L }
  }

  fun destroy(reason: String) {
    if (!started && sceneObject == null && mesh == null && material == null) {
      return
    }
    handler.removeCallbacksAndMessages(null)
    var cleanupComplete = true
    sceneObject?.let { ownedObject ->
      cleanupComplete =
          runCatching {
                bindings.scene.destroyObject(ownedObject)
                true
              }
              .recoverCatching {
                ownedObject.destroy()
                true
              }
              .getOrDefault(false) && cleanupComplete
    }
    sceneObject = null
    carrierEntity = null
    mesh?.let { ownedMesh ->
      cleanupComplete = runCatching { ownedMesh.destroy() }.isSuccess && cleanupComplete
    }
    mesh = null
    material?.let { ownedMaterial ->
      cleanupComplete = runCatching { ownedMaterial.destroy() }.isSuccess && cleanupComplete
    }
    material = null
    started = false
    completed = true
    bindings.marker(SpatialStimulusVolumeRoute.cleanupMarker(reason, cleanupComplete))
  }

  private fun createCarrier() {
    val customMaterial =
        SceneMaterial.custom(
                SpatialStimulusVolumeRoute.SHADER_NAME,
                arrayOf(
                    SceneMaterialAttribute("profileParams", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("sourceA", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("sourceB", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("colorNear", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("colorMid", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("colorFar", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("fixedParams", SceneMaterialDataType.Vector4),
                ),
            )
            .apply {
              setAttribute("profileParams", Vector4(1.0f, 0.90f, 0.72f, 2.65f))
              setAttribute("sourceA", Vector4(-0.30f, -0.06f, 0.0f, 0.96f))
              setAttribute("sourceB", Vector4(0.30f, 0.06f, 0.0f, 0.24f))
              setAttribute("colorNear", Vector4(1.0f, 0.82f, 0.10f, 1.0f))
              setAttribute("colorMid", Vector4(0.0f, 1.0f, 0.82f, 1.0f))
              setAttribute("colorFar", Vector4(0.46f, 0.18f, 1.0f, 1.0f))
              setAttribute(
                  "fixedParams",
                  Vector4(
                      0.0f,
                      0.0f,
                      SPATIAL_STIMULUS_VOLUME_FRAGMENT_SAMPLES.toFloat(),
                      SPATIAL_STIMULUS_VOLUME_PROFILE_SAMPLES.toFloat(),
                  ),
              )
              setSidedness(MaterialSidedness.DOUBLE_SIDED)
              setBlendMode(BlendMode.OPAQUE)
              setDepthTest(DepthTest.ALWAYS)
              setDepthWrite(DepthWrite.DISABLE)
            }
    val carrierMesh =
        SceneMesh.box(
            -CARRIER_HALF_WIDTH_METERS,
            -CARRIER_HALF_HEIGHT_METERS,
            -CARRIER_HALF_DEPTH_METERS,
            CARRIER_HALF_WIDTH_METERS,
            CARRIER_HALF_HEIGHT_METERS,
            CARRIER_HALF_DEPTH_METERS,
            customMaterial,
        )
    val entity =
        Entity.create(
            Transform(bindings.poseFromViewer(CARRIER_DISTANCE_METERS)),
            Scale(Vector3(1.0f, 1.0f, 1.0f)),
            Visible(true),
        )
    val ownedObject =
        SceneObject(
            bindings.scene,
            carrierMesh,
            "spatial_stimulus_volume_fixed_phase_carrier",
            entity,
        )
    bindings.scene.addObject(ownedObject)
    material = customMaterial
    mesh = carrierMesh
    carrierEntity = entity
    sceneObject = ownedObject
  }

  companion object {
    const val MODULE_ID = "spatial-stimulus-volume-coordinator"
    private const val CARRIER_DISTANCE_METERS = 0.62f
    private const val CARRIER_HALF_WIDTH_METERS = 1.25f
    private const val CARRIER_HALF_HEIGHT_METERS = 1.05f
    private const val CARRIER_HALF_DEPTH_METERS = 0.015f
  }
}
