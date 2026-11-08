package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal enum class SpatialNativeInteropDiagnosticMode(
    val code: Int,
    val token: String,
    val allowSurface: Boolean,
    val allowVulkanObjectConstruction: Boolean,
) {
  Full(0, "full", true, true),
  BorrowedOnly(1, "borrowed-only", false, false),
  SurfaceOnly(2, "surface-only", true, false),
  VulkanOnly(3, "vulkan-only", false, true),
}

/** Construction-time allocation policy for the disposable no-render diagnostic only. */
internal object SpatialNativeInteropDiagnosticPolicy {
  const val PROPERTY = "debug.rustyquest.spatial.native_interop_probe.mode"

  @JvmStatic
  fun select(
      debug: Boolean,
      rawSupplier: (() -> String)? = null,
  ): SpatialNativeInteropDiagnosticMode {
    if (!debug) return SpatialNativeInteropDiagnosticMode.Full
    return when (runCatching { rawSupplier?.invoke() }.getOrNull()) {
      "1" -> SpatialNativeInteropDiagnosticMode.BorrowedOnly
      "2" -> SpatialNativeInteropDiagnosticMode.SurfaceOnly
      "3" -> SpatialNativeInteropDiagnosticMode.VulkanOnly
      else -> SpatialNativeInteropDiagnosticMode.Full
    }
  }
}
