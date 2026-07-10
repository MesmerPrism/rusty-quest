package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneQuadLayer
import com.meta.spatial.runtime.SceneSwapchain
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialExternalSwapchainProbeNativeState(
    val receiptLibraryLoaded: Boolean,
    val receiptLibraryError: String,
)

internal data class SpatialExternalSwapchainProbeBindings(
    val scene: Scene,
    val nativeState: () -> SpatialExternalSwapchainProbeNativeState,
    val createExternalSwapchain: (Long, Long, Long, Int, Int) -> Long,
    val destroyExternalSwapchain: (Long, Long, Long) -> Int,
    val marker: (String) -> Unit,
)

internal class SpatialExternalSwapchainProbeCoordinator(
    private val bindings: SpatialExternalSwapchainProbeBindings,
) {
  private var started = false
  private var layer: SceneQuadLayer? = null
  private var sceneObject: SceneObject? = null
  private var wrappedSwapchain: SceneSwapchain? = null
  private var externalHandle = 0L
  private val sdkWrapRetainers = mutableListOf<SceneSwapchain>()
  private val externalWrapRetainers = mutableListOf<SceneSwapchain>()

  fun runIfRequested(reason: String) {
    if (started || !SpatialDiagnosticProbeRouteModule.externalSwapchainProbeEnabled()) {
      return
    }
    started = true
    val cycles = SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycles()
    val cycleMs = SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycleMs()
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeStartMarker(
            reason = reason,
            cycles = cycles,
            cycleMs = cycleMs,
        )
    )
    Handler(Looper.getMainLooper()).post { runCycle(1, cycles, cycleMs) }
  }

  fun destroy(reason: String): String = cleanup(reason)

  private fun runCycle(
      cycleIndex: Int,
      cycleCount: Int,
      cycleMs: Long,
  ) {
    cleanup("cycle-$cycleIndex-pre-cleanup")
    val nativeState = bindings.nativeState()
    if (!nativeState.receiptLibraryLoaded) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLibraryUnavailableCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              error = nativeState.receiptLibraryError,
          )
      )
      return
    }

    val probe = SpatialNativeInteropProbe.capture(bindings.scene)
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeMissingOpenXrHandlesCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              openXrInstanceHandleNonZero = probe.openXrInstanceHandleNonZero,
              openXrSessionHandleNonZero = probe.openXrSessionHandleNonZero,
              openXrGetInstanceProcAddrHandleNonZero =
                  probe.openXrGetInstanceProcAddrHandleNonZero,
          )
      )
      return
    }

    val sdkHandleWrapMode = probeSdkSceneSwapchainHandleWrapping(cycleIndex)
    val createdExternalHandle =
        runCatching {
              bindings.createExternalSwapchain(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
                  EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX,
                  EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeNativeCreateCallFailedMarker(
                      cycleIndex = cycleIndex,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              0L
            }
    externalHandle = createdExternalHandle
    if (createdExternalHandle == 0L) {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeZeroHandleCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              sdkHandleWrapMode = sdkHandleWrapMode,
          )
      )
      return
    }

    val wrapped =
        runCatching { SceneSwapchain(createdExternalHandle) }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeExternalWrapFailedMarker(
                      cycleIndex = cycleIndex,
                      externalHandle = createdExternalHandle,
                      sdkHandleWrapMode = sdkHandleWrapMode,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              val ownership = cleanup("cycle-$cycleIndex-wrap-failed")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeExternalWrapFailedCompleteMarker(
                      cycleIndex = cycleIndex,
                      cycleCount = cycleCount,
                      sdkHandleWrapMode = sdkHandleWrapMode,
                      destroyOwnership = ownership,
                  )
              )
              return
            }
    wrappedSwapchain = wrapped
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeExternalWrapResultMarker(
            cycleIndex = cycleIndex,
            externalHandle = createdExternalHandle,
            wrapperHandle = wrapped.handle,
            wrapperNativeHandle = wrapped.nativeHandle(),
            wrapperPlatformHandle = wrapped.platformHandle(),
            platformHandleMatchesExternal = wrapped.platformHandle() == createdExternalHandle,
            nativeHandleMatchesExternal = wrapped.nativeHandle() == createdExternalHandle,
            handleMatchesExternal = wrapped.handle == createdExternalHandle,
        )
    )

    val layerCreated =
        runCatching {
              val pose = poseFromViewer()
              val entity =
                  Entity.create(
                      Transform(pose),
                      Scale(Vector3(1.0f, 1.0f, 1.0f)),
                      Visible(true),
                  )
              val createdSceneObject = SceneObject(bindings.scene, entity)
              bindings.scene.addObject(createdSceneObject)
              sceneObject = createdSceneObject
              val createdLayer =
                  SceneQuadLayer(
                      bindings.scene,
                      wrapped,
                      EXTERNAL_SWAPCHAIN_PROBE_WIDTH_METERS,
                      EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_METERS,
                      0.5f,
                      0.5f,
                      StereoMode.None,
                      createdSceneObject,
                  )
              createdLayer.setZIndex(EXTERNAL_SWAPCHAIN_PROBE_Z_INDEX)
              layer = createdLayer
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLayerCreatedMarker(
                      cycleIndex = cycleIndex,
                      layerPositionM = activityVectorMarker(pose.t),
                      layerQuaternion = activityQuaternionMarker(pose.q),
                  )
              )
              true
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLayerCreateFailedMarker(
                      cycleIndex = cycleIndex,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              false
            }

    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycleVisibleMarker(
            cycleIndex = cycleIndex,
            cycleCount = cycleCount,
            sdkHandleWrapMode = sdkHandleWrapMode,
            layerCreated = layerCreated,
        )
    )
    if (!layerCreated) {
      val ownership = cleanup("cycle-$cycleIndex-layer-create-failed")
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeLayerCreateFailedCompleteMarker(
              cycleIndex = cycleIndex,
              cycleCount = cycleCount,
              sdkHandleWrapMode = sdkHandleWrapMode,
              destroyOwnership = ownership,
          )
      )
      return
    }
    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              val ownership = cleanup("cycle-$cycleIndex-destroy")
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCycleCompleteMarker(
                      cycleIndex = cycleIndex,
                      cycleCount = cycleCount,
                      sdkHandleWrapMode = sdkHandleWrapMode,
                      layerCreated = layerCreated,
                      destroyOwnership = ownership,
                  )
              )
              if (cycleIndex < cycleCount) {
                Handler(Looper.getMainLooper())
                    .postDelayed(
                        { runCycle(cycleIndex + 1, cycleCount, cycleMs) },
                        EXTERNAL_SWAPCHAIN_PROBE_INTER_CYCLE_MS,
                    )
              } else {
                bindings.marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeCompleteMarker(
                        cycleCount = cycleCount,
                        sdkHandleWrapMode = sdkHandleWrapMode,
                        layerCreated = layerCreated,
                        destroyOwnership = ownership,
                    )
                )
              }
            },
            cycleMs,
        )
  }

  private fun probeSdkSceneSwapchainHandleWrapping(cycleIndex: Int): String {
    val sdkSwap =
        runCatching {
              SceneSwapchain.create(
                  EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX,
                  EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX,
                  1,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkSwapchainCreateFailedMarker(
                      cycleIndex = cycleIndex,
                      error = throwable.javaClass.simpleName,
                      message = throwable.message ?: "none",
                  )
              )
              return "none"
            }
    val sdkSurfaceValid = runCatching { sdkSwap.getSurface()?.isValid == true }.getOrDefault(false)
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkSwapchainCreatedMarker(
            cycleIndex = cycleIndex,
            handle = sdkSwap.handle,
            nativeHandle = sdkSwap.nativeHandle(),
            platformHandle = sdkSwap.platformHandle(),
            surfaceValid = sdkSurfaceValid,
        )
    )
    var firstSuccess = "none"
    listOf(
            "handle" to sdkSwap.handle,
            "nativeHandle" to sdkSwap.nativeHandle(),
            "platformHandle" to sdkSwap.platformHandle(),
        )
        .forEach { (label, handle) ->
          if (handle == 0L) {
            bindings.marker(
                SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapZeroMarker(
                    cycleIndex = cycleIndex,
                    handleLabel = label,
                    sourceHandle = handle,
                )
            )
            return@forEach
          }
          runCatching { SceneSwapchain(handle) }
              .onSuccess { wrapper ->
                sdkWrapRetainers.add(wrapper)
                if (firstSuccess == "none") {
                  firstSuccess = label
                }
                val wrapperSurfaceValid =
                    runCatching { wrapper.getSurface()?.isValid == true }.getOrDefault(false)
                bindings.marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapSuccessMarker(
                        cycleIndex = cycleIndex,
                        handleLabel = label,
                        sourceHandle = handle,
                        wrapperHandle = wrapper.handle,
                        wrapperNativeHandle = wrapper.nativeHandle(),
                        wrapperPlatformHandle = wrapper.platformHandle(),
                        wrapperSurfaceValid = wrapperSurfaceValid,
                    )
                )
              }
              .onFailure { throwable ->
                bindings.marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapFailedMarker(
                        cycleIndex = cycleIndex,
                        handleLabel = label,
                        sourceHandle = handle,
                        error = throwable.javaClass.simpleName,
                        message = throwable.message ?: "none",
                    )
                )
              }
        }
    runCatching { sdkSwap.destroy() }
        .onFailure { throwable ->
          bindings.marker(
              SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkSwapchainDestroyFailedMarker(
                  cycleIndex = cycleIndex,
                  error = throwable.javaClass.simpleName,
              )
          )
        }
    bindings.marker(
        SpatialDiagnosticProbeRouteModule.externalSwapchainProbeSdkHandleWrapSummaryMarker(
            cycleIndex = cycleIndex,
            sdkHandleWrapMode = firstSuccess,
        )
    )
    return firstSuccess
  }

  private fun cleanup(reason: String): String {
    var layerDestroyed = layer == null
    var sceneObjectDestroyed = sceneObject == null
    var wrapperDestroyed = wrappedSwapchain == null
    var wrapperDestroySkipped = false
    var nativeDestroyResult = "not-run"
    var destroyOwnership = "unknown"

    layer?.let { currentLayer ->
      layerDestroyed =
          runCatching {
                currentLayer.destroy()
                true
              }
              .getOrDefault(false)
    }
    layer = null

    sceneObject?.let { currentSceneObject ->
      sceneObjectDestroyed =
          runCatching {
                bindings.scene.destroyObject(currentSceneObject)
                true
              }
              .recoverCatching {
                currentSceneObject.destroy()
                true
              }
              .getOrDefault(false)
    }
    sceneObject = null

    wrappedSwapchain?.let { currentWrappedSwapchain ->
      externalWrapRetainers.add(currentWrappedSwapchain)
      wrapperDestroyed = false
      wrapperDestroySkipped = true
    }
    wrappedSwapchain = null

    val handle = externalHandle
    if (handle != 0L && bindings.nativeState().receiptLibraryLoaded) {
      val probe = SpatialNativeInteropProbe.capture(bindings.scene)
      val destroyCode =
          runCatching {
                bindings.destroyExternalSwapchain(
                    probe.openXrInstanceHandle,
                    probe.openXrGetInstanceProcAddrHandle,
                    handle,
                )
              }
              .getOrElse { throwable ->
                bindings.marker(
                    SpatialDiagnosticProbeRouteModule.externalSwapchainProbeNativeDestroyCallFailedMarker(
                        reason = reason,
                        externalHandle = handle,
                        error = throwable.javaClass.simpleName,
                    )
                )
                Int.MIN_VALUE
              }
      nativeDestroyResult = destroyCode.toString()
      destroyOwnership =
          when (destroyCode) {
            0 -> "native"
            OPENXR_ERROR_HANDLE_INVALID -> "sdk"
            else -> "unknown"
          }
    }
    externalHandle = 0L
    if (!layerDestroyed ||
        !sceneObjectDestroyed ||
        !wrapperDestroyed ||
        nativeDestroyResult != "not-run") {
      bindings.marker(
          SpatialDiagnosticProbeRouteModule.externalSwapchainProbeDestroyedMarker(
              reason = reason,
              layerDestroyed = layerDestroyed,
              sceneObjectDestroyed = sceneObjectDestroyed,
              wrapperDestroyed = wrapperDestroyed,
              wrapperDestroySkipped = wrapperDestroySkipped,
              nativeDestroyResult = nativeDestroyResult,
              destroyOwnership = destroyOwnership,
          )
      )
    }
    return destroyOwnership
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  private fun poseFromViewer(): Pose {
    val viewerPose = runCatching { bindings.scene.getViewerPose() }.getOrNull()
    if (viewerPose == null) {
      return Pose(
          Vector3(0.0f, 1.20f, -EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS),
          Quaternion.fromDirection(
              Vector3(0.0f, 0.0f, -1.0f),
              Vector3(0.0f, 1.0f, 0.0f),
          ),
      )
    }
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val center = viewerPose.t + forward * EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  companion object {
    const val MODULE_ID = "spatial-external-swapchain-probe-coordinator"
  }
}
