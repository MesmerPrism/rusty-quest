package io.github.mesmerprism.rustyquest.spatial_camera_panel

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

internal data class SpatialCameraHwbNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialCameraHwbProbeBindings(
    val scene: Scene,
    val resources: SpatialSdkQuadResourceCoordinator,
    val cleanup: (String) -> String,
    val projectionProbeEnabled: () -> Boolean,
    val nativeState: () -> SpatialCameraHwbNativeState,
    val startNative: (AndroidSurface, Int, Int, Int, Int) -> Long,
    val stopNative: () -> Unit,
    val marker: (String) -> Unit,
)

internal class SpatialCameraHwbProbeCoordinator(
    private val bindings: SpatialCameraHwbProbeBindings,
) {
  private var started = false

  fun runIfRequested(reason: String) {
    if (started || bindings.projectionProbeEnabled()) {
      return
    }
    if (!SpatialDiagnosticProbeRouteModule.cameraHwbProbeEnabled()) {
      return
    }
    started = true
    val holdMs = SpatialDiagnosticProbeRouteModule.cameraHwbProbeHoldMs()
    val frameCount = SpatialDiagnosticProbeRouteModule.cameraHwbProbeFrameCount()
    val readerMaxImages = SpatialDiagnosticProbeRouteModule.cameraHwbProbeReaderMaxImages()
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.cameraHwbProbeStartMarker(
            reason = reason,
            frameCount = frameCount,
            holdMs = holdMs,
            readerMaxImages = readerMaxImages,
            publicMultiStackMarkerFields = SpatialPublicMultiStack.inactiveMarkerFields(),
        )
    )
    Handler(Looper.getMainLooper()).post { run(holdMs, frameCount, readerMaxImages) }
  }

  private fun run(holdMs: Long, frameCount: Int, readerMaxImages: Int) {
    bindings.cleanup("camera-hwb-pre-run")
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
              sdkSwapchainCreated = false,
              surfaceValid = false,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              sampledCameraTexture = "false",
              error = nativeState.receiptLibraryError,
          )
      )
      return
    }
    val sdkSwapchain =
        runCatching {
              SceneSwapchain.createAsAndroid(
                  CAMERA_HWB_PROBE_WIDTH_PX,
                  CAMERA_HWB_PROBE_HEIGHT_PX,
                  false,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
                      sdkSwapchainCreated = false,
                      surfaceValid = false,
                      sceneQuadLayerCreated = false,
                      nativeStartRequested = false,
                      sampledCameraTexture = "false",
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
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeGetSurfaceFailedMarker(
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
        SpatialDiagnosticProbeRouteModule.cameraHwbProbeSdkSwapchainCreatedMarker(
            handle = sdkSwapchain.handle,
            nativeHandle = sdkSwapchain.nativeHandle(),
            platformHandle = sdkSwapchain.platformHandle(),
            surfaceValid = surfaceValid,
        )
    )
    val renderSurface = surface
    if (!surfaceValid) {
      val cleanupStatus = bindings.cleanup("camera-hwb-surface-invalid")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              sampledCameraTexture = "false",
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val layerCreated = createLayer(sdkSwapchain)
    if (!layerCreated) {
      val cleanupStatus = bindings.cleanup("camera-hwb-layer-create-failed")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
              sdkSwapchainCreated = true,
              surfaceValid = surfaceValid,
              sceneQuadLayerCreated = false,
              nativeStartRequested = false,
              sampledCameraTexture = "false",
              cleanupStatus = cleanupStatus,
          )
      )
      return
    }

    val startMask =
        runCatching {
              bindings.startNative(
                  renderSurface,
                  CAMERA_HWB_PROBE_WIDTH_PX,
                  CAMERA_HWB_PROBE_HEIGHT_PX,
                  frameCount,
                  readerMaxImages,
              )
            }
            .getOrElse { throwable ->
              val cleanupStatus = bindings.cleanup("camera-hwb-start-failed")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = false,
                      sampledCameraTexture = "false",
                      cleanupStatus = cleanupStatus,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return
            }
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.cameraHwbProbeNativeStartRequestedMarker(
            surfaceValid = surfaceValid,
            startMask = startMask,
            frameCount = frameCount,
            readerMaxImages = readerMaxImages,
            holdMs = holdMs,
            publicMultiStackMarkerFields = SpatialPublicMultiStack.inactiveMarkerFields(),
        )
    )
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              if (bindings.nativeState().receiptLibraryLoaded) {
                runCatching { bindings.stopNative() }
              }
              val cleanupStatus = bindings.cleanup("camera-hwb-hold-complete")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.cameraHwbProbeCompleteMarker(
                      sdkSwapchainCreated = true,
                      surfaceValid = surfaceValid,
                      sceneQuadLayerCreated = true,
                      nativeStartRequested = true,
                      sampledCameraTexture = "see-native-logcat",
                      cleanupStatus = cleanupStatus,
                      firstCameraFramePresented = "see-native-logcat",
                  )
              )
            },
            holdMs,
        )
  }

  private fun createLayer(sdkSwapchain: SceneSwapchain): Boolean =
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
                    CAMERA_HWB_PROBE_WIDTH_METERS,
                    CAMERA_HWB_PROBE_HEIGHT_METERS,
                    material,
                )
            bindings.resources.registerAnchor(material, mesh)
            val sceneObject =
                SceneObject(bindings.scene, mesh, "camera_hwb_probe_anchor", entity)
            bindings.scene.addObject(sceneObject)
            bindings.resources.registerSceneObject(sceneObject)
            val layer =
                SceneQuadLayer(
                    bindings.scene,
                    sdkSwapchain,
                    CAMERA_HWB_PROBE_WIDTH_METERS,
                    CAMERA_HWB_PROBE_HEIGHT_METERS,
                    0.5f,
                    0.5f,
                    StereoMode.None,
                    sceneObject,
                )
            layer.setZIndex(CAMERA_HWB_PROBE_Z_INDEX)
            bindings.resources.registerLayer(layer)
            bindings.marker(
                SpatialDiagnosticProbeRouteModule.cameraHwbProbeLayerCreatedMarker(
                    sceneObjectHandle = sceneObject.handle,
                    layerPositionM = activityVectorMarker(pose.t),
                    layerQuaternion = activityQuaternionMarker(pose.q),
                )
            )
            true
          }
          .getOrElse { throwable ->
            bindings.marker(
                SpatialDiagnosticProbeRouteModule.cameraHwbProbeLayerCreateFailedMarker(
                    error = throwable.javaClass.simpleName,
                    message = throwable.message ?: "none",
                )
            )
            false
          }

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-probe-coordinator"
  }
}
