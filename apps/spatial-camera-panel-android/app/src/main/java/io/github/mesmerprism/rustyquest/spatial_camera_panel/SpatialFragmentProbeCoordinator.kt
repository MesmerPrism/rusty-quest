package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.AlphaMode
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
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialFragmentProbeBindings(
    val scene: Scene,
    val poseFromViewer: (Float) -> Pose,
    val marker: (String) -> Unit,
)

@OptIn(SpatialSDKExperimentalAPI::class)
internal class SpatialFragmentProbeCoordinator(
    private val bindings: SpatialFragmentProbeBindings,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val sceneObjects = mutableListOf<SceneObject>()
  private val meshes = mutableListOf<SceneMesh>()
  private val materials = mutableListOf<SceneMaterial>()
  private val textures = mutableListOf<SceneTexture>()
  private var activeConfig: SpatialFragmentProbeConfig? = null
  private var started = false
  private var completed = false
  private var sceneTicks = 0L

  fun runIfRequested(reason: String) {
    if (started) {
      return
    }
    val config = SpatialFragmentProbeRoute.resolve()
    if (!config.enabled) {
      return
    }
    started = true
    activeConfig = config
    bindings.marker(SpatialFragmentProbeRoute.startMarker(reason, config))
    handler.post {
      runCatching { createScene(config) }
          .onSuccess {
            bindings.marker(SpatialFragmentProbeRoute.effectiveMarker(config))
            bindings.marker(SpatialFragmentProbeRoute.renderReadyMarker(config))
            handler.postDelayed(
                {
                  if (!completed) {
                    bindings.marker(
                        SpatialFragmentProbeRoute.renderWindowMarker(config, sceneTicks)
                    )
                  }
                },
                RENDER_WINDOW_MARKER_DELAY_MS,
            )
            handler.postDelayed(
                {
                  if (!completed) {
                    completed = true
                    bindings.marker(SpatialFragmentProbeRoute.completeMarker(config, sceneTicks))
                  }
                },
                config.holdMs,
            )
          }
          .onFailure { error ->
            bindings.marker(SpatialFragmentProbeRoute.failureMarker(config, error))
            destroy("create-failed")
          }
    }
  }

  fun onSceneTick() {
    if (started && !completed) {
      sceneTicks += 1L
    }
  }

  fun destroy(reason: String) {
    if (!started && sceneObjects.isEmpty() && meshes.isEmpty() && materials.isEmpty()) {
      return
    }
    handler.removeCallbacksAndMessages(null)
    var complete = true
    sceneObjects.asReversed().forEach { sceneObject ->
      complete =
          runCatching {
                bindings.scene.destroyObject(sceneObject)
                true
              }
              .recoverCatching {
                sceneObject.destroy()
                true
              }
              .getOrDefault(false) && complete
    }
    sceneObjects.clear()
    meshes.asReversed().forEach { mesh ->
      complete = runCatching { mesh.destroy() }.isSuccess && complete
    }
    meshes.clear()
    materials.asReversed().forEach { material ->
      complete = runCatching { material.destroy() }.isSuccess && complete
    }
    materials.clear()
    textures.asReversed().forEach { texture ->
      complete = runCatching { texture.destroy() }.isSuccess && complete
    }
    textures.clear()
    activeConfig = null
    completed = true
    bindings.marker(SpatialFragmentProbeRoute.cleanupMarker(reason, complete))
  }

  private fun createScene(config: SpatialFragmentProbeConfig) {
    val volumePose = bindings.poseFromViewer(RAYMARCH_VOLUME_DISTANCE_METERS)
    val customMaterial = createProbeMaterial(config)
    val volumeMesh =
        SceneMesh.box(
            -RAYMARCH_VOLUME_HALF_WIDTH_METERS,
            -RAYMARCH_VOLUME_HALF_HEIGHT_METERS,
            -RAYMARCH_VOLUME_HALF_DEPTH_METERS,
            RAYMARCH_VOLUME_HALF_WIDTH_METERS,
            RAYMARCH_VOLUME_HALF_HEIGHT_METERS,
            RAYMARCH_VOLUME_HALF_DEPTH_METERS,
            customMaterial,
        )
    addObject("spatial_fragment_probe_volume", volumePose, volumeMesh)

    addOccluder(
        name = "spatial_fragment_probe_foreground_occluder",
        pose = offsetPose(bindings.poseFromViewer(FOREGROUND_OCCLUDER_DISTANCE_METERS), -0.20f, -0.12f),
        color = AndroidColor.valueOf(0.95f, 0.08f, 0.72f, 1.0f),
        halfWidth = 0.10f,
        halfHeight = 0.12f,
    )
    addOccluder(
        name = "spatial_fragment_probe_depth_discriminator",
        pose = offsetPose(bindings.poseFromViewer(DEPTH_DISCRIMINATOR_DISTANCE_METERS), 0.18f, 0.08f),
        color = AndroidColor.valueOf(0.05f, 0.95f, 0.95f, 1.0f),
        halfWidth = 0.09f,
        halfHeight = 0.11f,
    )
  }

  private fun createProbeMaterial(config: SpatialFragmentProbeConfig): SceneMaterial {
    val material =
        SceneMaterial.custom(
                config.shaderName,
                arrayOf(
                    SceneMaterialAttribute("stereoParams", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("probeParams", SceneMaterialDataType.Vector4),
                    SceneMaterialAttribute("colorParams", SceneMaterialDataType.Vector4),
                ),
            )
            .apply {
              setAttribute("stereoParams", Vector4(0.0f, 0.0f, 1.0f, 1.0f))
              setAttribute(
                  "probeParams",
                  Vector4(
                      config.mode?.shaderValue ?: 0.0f,
                      SPATIAL_FRAGMENT_PROBE_RAYMARCH_STEPS.toFloat(),
                      if (config.fragmentDepth) 1.0f else 0.0f,
                      0.0f,
                  ),
              )
              setAttribute("colorParams", Vector4(0.04f, 0.72f, 1.0f, 1.0f))
              setSidedness(MaterialSidedness.DOUBLE_SIDED)
              setBlendMode(BlendMode.OPAQUE)
              setDepthTest(DepthTest.LESS_OR_EQUAL)
              setDepthWrite(DepthWrite.ENABLE)
            }
    materials += material
    return material
  }

  private fun addOccluder(
      name: String,
      pose: Pose,
      color: AndroidColor,
      halfWidth: Float,
      halfHeight: Float,
  ) {
    val texture = SceneTexture(color)
    val material =
        SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER).apply {
          setUnlit(true)
          setSidedness(MaterialSidedness.DOUBLE_SIDED)
          setBlendMode(BlendMode.OPAQUE)
          setDepthTest(DepthTest.LESS_OR_EQUAL)
          setDepthWrite(DepthWrite.ENABLE)
        }
    val mesh =
        SceneMesh.box(
            -halfWidth,
            -halfHeight,
            -OCCLUDER_HALF_DEPTH_METERS,
            halfWidth,
            halfHeight,
            OCCLUDER_HALF_DEPTH_METERS,
            material,
        )
    textures += texture
    materials += material
    addObject(name, pose, mesh)
  }

  private fun addObject(name: String, pose: Pose, mesh: SceneMesh) {
    val entity =
        Entity.create(
            Transform(pose),
            Scale(Vector3(1.0f, 1.0f, 1.0f)),
            Visible(true),
        )
    val sceneObject = SceneObject(bindings.scene, mesh, name, entity)
    bindings.scene.addObject(sceneObject)
    meshes += mesh
    sceneObjects += sceneObject
  }

  private fun offsetPose(pose: Pose, rightMeters: Float, upMeters: Float): Pose =
      Pose(
          pose.t + pose.right() * rightMeters + pose.up() * upMeters,
          pose.q,
      )

  companion object {
    const val MODULE_ID = "spatial-fragment-probe-coordinator"
    private const val RAYMARCH_VOLUME_DISTANCE_METERS = 1.40f
    private const val RAYMARCH_VOLUME_HALF_WIDTH_METERS = 0.35f
    private const val RAYMARCH_VOLUME_HALF_HEIGHT_METERS = 0.28f
    private const val RAYMARCH_VOLUME_HALF_DEPTH_METERS = 0.35f
    private const val FOREGROUND_OCCLUDER_DISTANCE_METERS = 0.92f
    private const val DEPTH_DISCRIMINATOR_DISTANCE_METERS = 1.14f
    private const val OCCLUDER_HALF_DEPTH_METERS = 0.012f
    private const val RENDER_WINDOW_MARKER_DELAY_MS = 1_000L
  }
}
