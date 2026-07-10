package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.os.Handler
import android.os.Looper
import android.view.Surface as AndroidSurface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneQuadLayer
import com.meta.spatial.runtime.SceneSwapchain
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialSdkQuadSurfaceProbeBindings(
    val scene: Scene,
    val resources: SpatialSdkQuadResourceCoordinator,
    val cleanup: (String) -> String,
    val marker: (String) -> Unit,
)

internal class SpatialSdkQuadSurfaceProbeCoordinator(
    private val bindings: SpatialSdkQuadSurfaceProbeBindings,
) {
  private var started = false

  fun runIfRequested(reason: String) {
    if (started || !SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeEnabled()) {
      return
    }
    started = true
    val holdMs = SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeHoldMs()
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeStartMarker(
            reason = reason,
            holdMs = holdMs,
        )
    )
    Handler(Looper.getMainLooper()).post { run(holdMs) }
  }

  fun createLayer(
      sdkSwapchain: SceneSwapchain,
      canvasDrawn: Boolean,
      anchorMode: String,
  ): Boolean =
      runCatching {
            val pose =
                bindings.resources.poseFromViewer(SDK_QUAD_SURFACE_PROBE_DISTANCE_METERS)
            val entity =
                Entity.create(
                    Transform(pose),
                    Scale(Vector3(1.0f, 1.0f, 1.0f)),
                    Visible(true),
                )
            val sceneObject =
                if (anchorMode == "generated-single-sided-quad") {
                  val material = SceneMaterial.passthrough()
                  val mesh =
                      SceneMesh.singleSidedQuad(
                          SDK_QUAD_SURFACE_PROBE_WIDTH_METERS,
                          SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS,
                          material,
                      )
                  bindings.resources.registerAnchor(material, mesh)
                  SceneObject(bindings.scene, mesh, "sdk_quad_surface_probe_anchor", entity)
                } else {
                  SceneObject(bindings.scene, entity)
                }
            bindings.scene.addObject(sceneObject)
            bindings.resources.registerSceneObject(sceneObject)
            val layer =
                SceneQuadLayer(
                    bindings.scene,
                    sdkSwapchain,
                    SDK_QUAD_SURFACE_PROBE_WIDTH_METERS,
                    SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS,
                    0.5f,
                    0.5f,
                    StereoMode.None,
                    sceneObject,
                )
            layer.setZIndex(SDK_QUAD_SURFACE_PROBE_Z_INDEX)
            bindings.resources.registerLayer(layer)
            bindings.marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeLayerCreatedMarker(
                    canvasDrawn = canvasDrawn,
                    anchorMode = anchorMode,
                    sceneObjectHandle = sceneObject.handle,
                    layerPositionM = activityVectorMarker(pose.t),
                    layerQuaternion = activityQuaternionMarker(pose.q),
                )
            )
            true
          }
          .getOrElse { throwable ->
            bindings.marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeLayerCreateFailedMarker(
                    canvasDrawn = canvasDrawn,
                    anchorMode = anchorMode,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  private fun run(holdMs: Long) {
    bindings.cleanup("pre-run")
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  SDK_QUAD_SURFACE_PROBE_WIDTH_PX,
                  SDK_QUAD_SURFACE_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      canvasDrawn = false,
                      sceneQuadLayerCreated = false,
                      manualSceneQuadLayerViable = false,
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
                  SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeGetSurfaceFailedMarker(
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
        SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )

    val canvasDrawn = surface?.let { drawCheckerboard(it) } ?: false
    val plainEntityLayerCreated =
        createLayer(
            sdkSwapchain = sdkSwapchain,
            canvasDrawn = canvasDrawn,
            anchorMode = "plain-entity",
        )
    val layerCreated =
        if (plainEntityLayerCreated) {
          true
        } else {
          bindings.resources.cleanupSceneOnly("plain-entity-retry")
          createLayer(
              sdkSwapchain = sdkSwapchain,
              canvasDrawn = canvasDrawn,
              anchorMode = "generated-single-sided-quad",
          )
        }
    val anchorMode =
        if (plainEntityLayerCreated) {
          "plain-entity"
        } else if (layerCreated) {
          "generated-single-sided-quad"
        } else {
          "none"
        }

    val viable = surfaceValid && canvasDrawn && layerCreated
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeVisibleWindowMarker(
            surfaceValid = surfaceValid,
            canvasDrawn = canvasDrawn,
            sceneQuadLayerCreated = layerCreated,
            manualSceneQuadLayerViable = viable,
            plainEntitySceneObjectLayerCreated = plainEntityLayerCreated,
            anchorMode = anchorMode,
            holdMs = holdMs,
        )
    )
    if (!layerCreated) {
      val cleanupStatus = bindings.cleanup("layer-create-failed")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              canvasDrawn = canvasDrawn,
              sceneQuadLayerCreated = false,
              manualSceneQuadLayerViable = false,
              cleanupStatus = cleanupStatus,
              plainEntitySceneObjectLayerCreated = plainEntityLayerCreated,
              anchorMode = anchorMode,
              nativeVulkanProducer = false,
              visiblePatternConfirmed = false,
          )
      )
      return
    }
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val cleanupStatus = bindings.cleanup("hold-complete")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      canvasDrawn = canvasDrawn,
                      sceneQuadLayerCreated = layerCreated,
                      manualSceneQuadLayerViable = viable,
                      cleanupStatus = cleanupStatus,
                      plainEntitySceneObjectLayerCreated = plainEntityLayerCreated,
                      anchorMode = anchorMode,
                      nativeVulkanProducer = false,
                      visiblePatternConfirmed = false,
                      humanVisiblePatternCheckRequired = true,
                  )
              )
            },
            holdMs,
        )
  }

  private fun drawCheckerboard(surface: AndroidSurface): Boolean {
    if (!surface.isValid) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCanvasDrawSkippedMarker()
      )
      return false
    }
    var canvas: android.graphics.Canvas? = null
    return runCatching {
          canvas = surface.lockCanvas(null)
          val lockedCanvas = canvas ?: return@runCatching false
          val paint = AndroidPaint()
          val cellWidth =
              lockedCanvas.width / SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS.toFloat()
          val cellHeight =
              lockedCanvas.height / SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS.toFloat()
          for (y in 0 until SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS) {
            for (x in 0 until SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS) {
              paint.color =
                  if ((x + y) % 2 == 0) {
                    AndroidColor.rgb(218, 24, 24)
                  } else {
                    AndroidColor.rgb(20, 190, 70)
                  }
              lockedCanvas.drawRect(
                  x * cellWidth,
                  y * cellHeight,
                  (x + 1) * cellWidth,
                  (y + 1) * cellHeight,
                  paint,
              )
            }
          }
          paint.color = AndroidColor.WHITE
          paint.textSize = lockedCanvas.height * 0.075f
          paint.isAntiAlias = true
          lockedCanvas.drawText(
              "SDK Canvas",
              lockedCanvas.width * 0.18f,
              lockedCanvas.height * 0.52f,
              paint,
          )
          true
        }
        .onSuccess { drawn ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          bindings.marker(
              SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCanvasDrawCompleteMarker(drawn)
          )
        }
        .onFailure { throwable ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          bindings.marker(
              SpatialDiagnosticProbeRouteModule.sdkQuadSurfaceProbeCanvasDrawFailedMarker(
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
        .getOrDefault(false)
  }

  companion object {
    const val MODULE_ID = "spatial-sdk-quad-surface-probe-coordinator"
  }
}
