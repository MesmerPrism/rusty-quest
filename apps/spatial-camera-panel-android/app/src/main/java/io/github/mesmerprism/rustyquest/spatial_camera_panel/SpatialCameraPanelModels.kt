package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.toolkit.MediaPanelDisplayOptions
import kotlin.math.abs

internal data class CameraHwbProjectionPlane(
    val viewerPosition: Vector3,
    val forward: Vector3,
    val up: Vector3,
    val right: Vector3,
    val center: Vector3,
    val pose: Pose,
    val placementMode: CameraHwbProjectionPlacementMode,
    val targetDistanceMeters: Float,
    val projectionWidthMeters: Float,
    val projectionHeightMeters: Float,
    val leftEyeOffset: Vector3,
    val rightEyeOffset: Vector3,
)

internal enum class CameraHwbProjectionPlacementMode(val markerToken: String) {
  ViewerLocked("viewer-pose-projection-locked-quad"),
  VirtualRoomWall("virtual-room-wall-fixed-quad"),
}

internal enum class CameraHwbProjectionCarrierMode(val markerToken: String) {
  SceneQuadLayerRoomObject("scenequadlayer-room-object"),
  VideoSurfacePanelSceneObject("video-surface-panel-scene-object"),
}

internal fun Long.hasReceiptBit(bit: Long): Boolean = (this and bit) != 0L

internal fun PanelPlacement.headlockEquivalent(other: PanelPlacement): Boolean =
    visible == other.visible &&
        headlocked == other.headlocked &&
        abs(xMeters - other.xMeters) < 0.0005f &&
        abs(yMeters - other.yMeters) < 0.0005f &&
        abs(zMeters - other.zMeters) < 0.0005f &&
        abs(scale - other.scale) < 0.0005f &&
        abs(widthMeters - other.widthMeters) < 0.0005f &&
        abs(heightMeters - other.heightMeters) < 0.0005f

internal class FixedMediaPanelDisplayOptions(
    private val widthPx: Int,
    private val heightPx: Int,
) : MediaPanelDisplayOptions {
  override fun applyTo(config: PanelConfigOptions) {
    config.layoutWidthInPx = widthPx
    config.layoutHeightInPx = heightPx
    config.layoutDpi = PanelConfigOptions.DEFAULT_DPI
  }
}

data class PanelPlacement(
    val visible: Boolean = true,
    val headlocked: Boolean = true,
    val xMeters: Float = 0.0f,
    val yMeters: Float = 0.0f,
    val zMeters: Float = 1.40f,
    val scale: Float = 0.65f,
    val widthMeters: Float = 1.20f,
    val heightMeters: Float = 1.254f,
)

internal data class SpatialControllerPrimarySnapshot(
    val componentCount: Int,
    val controllerTypeCount: Int,
    val activeCount: Int,
    val localControllerCount: Int,
    val localActiveControllerCount: Int,
    val localRightControllerType: String,
    val localRightControllerAttachmentType: String,
    val localRightControllerActive: Boolean,
    val localRightControllerButtonState: Int,
    val localRightControllerChangedButtons: Int,
    val rightInputSource: String,
    val avatarBodyCount: Int,
    val playerAvatarBodyCount: Int,
    val leftAvatarControllerType: String,
    val rightAvatarControllerType: String,
    val leftAvatarControllerActive: Boolean,
    val rightAvatarControllerActive: Boolean,
    val leftAvatarButtonState: Int,
    val leftAvatarChangedButtons: Int,
    val rightAvatarButtonState: Int,
    val rightAvatarChangedButtons: Int,
    val buttonState: Int,
    val changedButtons: Int,
    val allControllerButtonState: Int,
    val allControllerChangedButtons: Int,
    val leftThumbUp: Boolean,
    val leftThumbDown: Boolean,
    val leftThumbY: Float,
    val rightThumbUp: Boolean,
    val rightThumbDown: Boolean,
    val rightThumbY: Float,
    val down: Boolean,
    val pressed: Boolean,
    val secondaryDown: Boolean,
    val secondaryPressed: Boolean,
)

data class SurfaceParticleControlState(
    val driver0Value01: Float = 1.0f,
    val driver1Value01: Float = 0.0f,
    val pointScale: Float = 1.0f,
)

data class SpatialNativeInteropProbe(
    val runtimeName: String,
    val openXrInstanceHandle: Long,
    val openXrSessionHandle: Long,
    val openXrGetInstanceProcAddrHandle: Long,
    val renderPolicy: String = "no-render",
) {
  val openXrInstanceHandleNonZero: Boolean
    get() = openXrInstanceHandle != 0L
  val openXrSessionHandleNonZero: Boolean
    get() = openXrSessionHandle != 0L
  val openXrGetInstanceProcAddrHandleNonZero: Boolean
    get() = openXrGetInstanceProcAddrHandle != 0L

  companion object {
    @OptIn(SpatialSDKExperimentalAPI::class)
    fun capture(scene: Scene): SpatialNativeInteropProbe =
        SpatialNativeInteropProbe(
            runtimeName = runCatching { scene.getRuntimeName().name }.getOrElse { "unavailable" },
            openXrInstanceHandle = runCatching { scene.getOpenXrInstanceHandle() }.getOrDefault(0L),
            openXrSessionHandle = runCatching { scene.getOpenXrSessionHandle() }.getOrDefault(0L),
            openXrGetInstanceProcAddrHandle =
                runCatching { scene.getOpenXrGetInstanceProcAddrHandle() }.getOrDefault(0L),
        )
  }
}

data class NativeInteropSurfaceProbeResult(
    val capability: String,
    val status: String,
    val surfaceValid: Boolean,
    val error: String,
)

data class NativeInteropReceiptResult(
    val status: String,
    val mask: Long,
    val openXrInstanceHandleNonZero: Boolean,
    val openXrSessionHandleNonZero: Boolean,
    val openXrGetInstanceProcAddrHandleNonZero: Boolean,
    val openXrGetInstanceProcAddrCallable: Boolean,
    val xrGetInstancePropertiesResolved: Boolean,
    val xrGetInstancePropertiesSucceeded: Boolean,
    val xrGetSystemResolved: Boolean,
    val xrGetSystemSucceeded: Boolean,
    val xrVulkanGraphicsRequirements2Resolved: Boolean,
    val xrVulkanGraphicsRequirements2Succeeded: Boolean,
    val xrCreateVulkanInstanceResolved: Boolean,
    val xrGetVulkanGraphicsDevice2Resolved: Boolean,
    val xrCreateVulkanDeviceResolved: Boolean,
    val vkInstanceCreated: Boolean,
    val vkGraphicsDeviceObtained: Boolean,
    val vkGraphicsComputeQueueFound: Boolean,
    val vkDeviceCreated: Boolean,
    val vkQueueObtained: Boolean,
    val vkObjectsDestroyed: Boolean,
    val surfaceValid: Boolean,
    val error: String,
)
