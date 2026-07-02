package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.Scene
import com.meta.spatial.core.SpatialSDKExperimentalAPI

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
