package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Surface as AndroidSurface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.LayerAlphaBlend
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

internal data class SpatialSdkQuadStereoAlphaProbeBindings(
    val scene: Scene,
    val resources: SpatialSdkQuadResourceCoordinator,
    val cleanup: (String) -> String,
    val marker: (String) -> Unit,
)

internal class SpatialSdkQuadStereoAlphaProbeCoordinator(
    private val bindings: SpatialSdkQuadStereoAlphaProbeBindings,
) {
  private var started = false
  private var zIndexChanged = false

  fun runIfRequested(reason: String) {
    if (started || !SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeEnabled()) {
      return
    }
    started = true
    val holdMs = SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeHoldMs()
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeStartMarker(
            reason = reason,
            holdMs = holdMs,
        )
    )
    Handler(Looper.getMainLooper()).post { run(holdMs) }
  }

  private fun run(holdMs: Long) {
    bindings.cleanup("stereo-alpha-pre-run")
    zIndexChanged = false
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX,
                  SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      canvasDrawn = false,
                      sceneQuadLayerCreated = false,
                      setClipApplied = false,
                      alphaBlendApplied = false,
                      zIndexChanged = false,
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
                  SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeGetSurfaceFailedMarker(
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
        SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )
    if (!surfaceValid) {
      val cleanupStatus = bindings.cleanup("stereo-alpha-surface-invalid")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              canvasDrawn = false,
              sceneQuadLayerCreated = false,
              setClipApplied = false,
              alphaBlendApplied = false,
              zIndexChanged = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val canvasDrawn = drawPattern(surface)
    val layerCreated = createLayer(sdkSwapchain, canvasDrawn)
    val viable = surfaceValid && canvasDrawn && layerCreated
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeVisibleWindowMarker(
            surfaceValid = surfaceValid,
            canvasDrawn = canvasDrawn,
            sceneQuadLayerCreated = layerCreated,
            manualSceneQuadLayerViable = viable,
            holdMs = holdMs,
        )
    )
    if (!layerCreated) {
      val cleanupStatus = bindings.cleanup("stereo-alpha-layer-create-failed")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              canvasDrawn = canvasDrawn,
              sceneQuadLayerCreated = false,
              setClipApplied = false,
              alphaBlendApplied = false,
              zIndexChanged = false,
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }
    scheduleLayerMutations()
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val cleanupStatus = bindings.cleanup("stereo-alpha-hold-complete")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      canvasDrawn = canvasDrawn,
                      sceneQuadLayerCreated = layerCreated,
                      setClipApplied = true,
                      alphaBlendApplied = true,
                      zIndexChanged = zIndexChanged,
                      manualSceneQuadLayerViable = viable,
                      colorScaleAlphaApplied = true,
                      cleanupStatus = cleanupStatus,
                      includeOperatorChecks = true,
                  )
              )
            },
            holdMs,
        )
  }

  private fun scheduleLayerMutations() {
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              bindings.resources.withLayer { layer ->
                runCatching { layer.setZIndex(SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_HIGH) }
                    .onSuccess {
                      zIndexChanged = true
                      bindings.marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeZIndexUpdatedMarker()
                      )
                    }
                    .onFailure { throwable ->
                      bindings.marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeZIndexUpdateFailedMarker(
                              error = throwable.javaClass.simpleName,
                              message = throwable.message ?: "none",
                          )
                      )
                    }
              }
            },
            SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_CHANGE_MS,
        )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              bindings.resources.withLayer { layer ->
                runCatching {
                      layer.setColorScaleBias(
                          Vector4(
                              1.0f,
                              1.0f,
                              1.0f,
                              SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_LOW,
                          ),
                          Vector4(0.0f),
                      )
                    }
                    .onSuccess {
                      bindings.marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaUpdatedMarker()
                      )
                    }
                    .onFailure { throwable ->
                      bindings.marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaUpdateFailedMarker(
                              error = throwable.javaClass.simpleName
                          )
                      )
                    }
              }
            },
            SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_CHANGE_MS,
        )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              bindings.resources.withLayer { layer ->
                runCatching {
                      layer.setColorScaleBias(Vector4(1.0f), Vector4(0.0f))
                    }
                    .onSuccess {
                      bindings.marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaRestoredMarker()
                      )
                    }
                    .onFailure { throwable ->
                      bindings.marker(
                          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeAlphaRestoreFailedMarker(
                              error = throwable.javaClass.simpleName
                          )
                      )
                    }
              }
            },
            SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_RESTORE_MS,
        )
  }

  private fun createLayer(
      sdkSwapchain: SceneSwapchain,
      canvasDrawn: Boolean,
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
            val material = SceneMaterial.passthrough()
            val mesh =
                SceneMesh.singleSidedQuad(
                    SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS,
                    SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS,
                    material,
                )
            bindings.resources.registerAnchor(material, mesh)
            val sceneObject =
                SceneObject(
                    bindings.scene,
                    mesh,
                    "sdk_quad_stereo_alpha_probe_anchor",
                    entity,
                )
            bindings.scene.addObject(sceneObject)
            bindings.resources.registerSceneObject(sceneObject)
            val layer =
                SceneQuadLayer(
                    bindings.scene,
                    sdkSwapchain,
                    SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS,
                    SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS,
                    0.5f,
                    0.5f,
                    StereoMode.LeftRight,
                    sceneObject,
                )
            layer.setZIndex(SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_LOW)
            layer.setClip(
                Vector2(0.04f, 0.04f),
                Vector2(0.96f, 0.04f),
                Vector2(0.96f, 0.96f),
                Vector2(0.04f, 0.96f),
            )
            layer.setAlphaBlend(
                LayerAlphaBlend(
                    BlendFactor.SOURCE_ALPHA,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                    BlendFactor.ONE,
                    BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                )
            )
            layer.setColorScaleBias(
                Vector4(1.0f, 1.0f, 1.0f, SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_HIGH),
                Vector4(0.0f),
            )
            bindings.resources.registerLayer(layer)
            bindings.marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeLayerCreatedMarker(
                    canvasDrawn = canvasDrawn,
                    sceneObjectHandle = sceneObject.handle,
                    layerPositionM = activityVectorMarker(pose.t),
                    layerQuaternion = activityQuaternionMarker(pose.q),
                )
            )
            true
          }
          .getOrElse { throwable ->
            bindings.marker(
                SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeLayerCreateFailedMarker(
                    canvasDrawn = canvasDrawn,
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  private fun drawPattern(surface: AndroidSurface): Boolean {
    if (!surface.isValid) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCanvasDrawSkippedMarker()
      )
      return false
    }
    var canvas: android.graphics.Canvas? = null
    return runCatching {
          canvas = surface.lockCanvas(null)
          val lockedCanvas = canvas ?: return@runCatching false
          lockedCanvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
          val paint = AndroidPaint().apply { isAntiAlias = true }
          val left =
              android.graphics.RectF(
                  0f,
                  0f,
                  lockedCanvas.width / 2f,
                  lockedCanvas.height.toFloat(),
              )
          val right =
              android.graphics.RectF(
                  lockedCanvas.width / 2f,
                  0f,
                  lockedCanvas.width.toFloat(),
                  lockedCanvas.height.toFloat(),
              )
          paint.style = AndroidPaint.Style.FILL
          paint.color = AndroidColor.argb(230, 220, 24, 24)
          lockedCanvas.drawRect(left, paint)
          paint.color = AndroidColor.argb(230, 30, 90, 235)
          lockedCanvas.drawRect(right, paint)

          drawStereoGrid(
              lockedCanvas,
              paint,
              left.left,
              left.top,
              left.width(),
              left.height(),
              AndroidColor.WHITE,
              AndroidColor.YELLOW,
              "LEFT RED",
          )
          drawStereoGrid(
              lockedCanvas,
              paint,
              right.left,
              right.top,
              right.width(),
              right.height(),
              AndroidColor.WHITE,
              AndroidColor.CYAN,
              "RIGHT BLUE",
          )
          true
        }
        .onSuccess { drawn ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          bindings.marker(
              SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCanvasDrawCompleteMarker(
                  drawn = drawn
              )
          )
        }
        .onFailure { throwable ->
          canvas?.let { locked -> runCatching { surface.unlockCanvasAndPost(locked) } }
          bindings.marker(
              SpatialDiagnosticProbeRouteModule.sdkQuadStereoAlphaProbeCanvasDrawFailedMarker(
                  error = throwable.javaClass.simpleName,
                  message = throwable.message ?: "none",
              )
          )
        }
        .getOrDefault(false)
  }

  private fun drawStereoGrid(
      canvas: android.graphics.Canvas,
      paint: AndroidPaint,
      x: Float,
      y: Float,
      width: Float,
      height: Float,
      gridColor: Int,
      accentColor: Int,
      label: String,
  ) {
    val cells = 8
    paint.style = AndroidPaint.Style.STROKE
    paint.strokeWidth = 3.0f
    paint.color = gridColor
    for (index in 0..cells) {
      val px = x + width * index / cells
      val py = y + height * index / cells
      canvas.drawLine(px, y, px, y + height, paint)
      canvas.drawLine(x, py, x + width, py, paint)
    }
    paint.strokeWidth = 8.0f
    paint.color = accentColor
    canvas.drawRect(x + 36f, y + 36f, x + width - 36f, y + height - 36f, paint)
    canvas.drawLine(
        x + width * 0.20f,
        y + height * 0.50f,
        x + width * 0.80f,
        y + height * 0.50f,
        paint,
    )
    canvas.drawLine(
        x + width * 0.80f,
        y + height * 0.50f,
        x + width * 0.68f,
        y + height * 0.38f,
        paint,
    )
    canvas.drawLine(
        x + width * 0.80f,
        y + height * 0.50f,
        x + width * 0.68f,
        y + height * 0.62f,
        paint,
    )
    paint.style = AndroidPaint.Style.FILL
    paint.textSize = height * 0.075f
    paint.color = AndroidColor.WHITE
    canvas.drawText(label, x + width * 0.24f, y + height * 0.22f, paint)
    paint.textSize = height * 0.045f
    canvas.drawText("UV 0,0 -> top-left", x + width * 0.08f, y + height * 0.91f, paint)
  }

  companion object {
    const val MODULE_ID = "spatial-sdk-quad-stereo-alpha-probe-coordinator"
  }
}
