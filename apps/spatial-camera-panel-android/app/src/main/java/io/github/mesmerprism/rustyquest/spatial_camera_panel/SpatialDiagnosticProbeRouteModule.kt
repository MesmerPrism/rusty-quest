package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal const val EXTERNAL_SWAPCHAIN_PROBE_PROPERTY =
    "debug.rustyquest.spatial.external_swapchain_probe"
internal const val EXTERNAL_SWAPCHAIN_PROBE_CYCLES_PROPERTY =
    "debug.rustyquest.spatial.external_swapchain_probe.cycles"
internal const val EXTERNAL_SWAPCHAIN_PROBE_CYCLE_MS_PROPERTY =
    "debug.rustyquest.spatial.external_swapchain_probe.cycle_ms"
internal const val EXTERNAL_SWAPCHAIN_PROBE_WIDTH_PX = 256
internal const val EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_PX = 256
internal const val EXTERNAL_SWAPCHAIN_PROBE_WIDTH_METERS = 0.35f
internal const val EXTERNAL_SWAPCHAIN_PROBE_HEIGHT_METERS = 0.35f
internal const val EXTERNAL_SWAPCHAIN_PROBE_DISTANCE_METERS = 0.85f
internal const val EXTERNAL_SWAPCHAIN_PROBE_Z_INDEX = 18
internal const val EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLES = 1
internal const val EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLES = 10
internal const val EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLE_MS = 60_000L
internal const val EXTERNAL_SWAPCHAIN_PROBE_MIN_CYCLE_MS = 1_000L
internal const val EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLE_MS = 60_000L
internal const val EXTERNAL_SWAPCHAIN_PROBE_INTER_CYCLE_MS = 750L

internal const val SDK_QUAD_SURFACE_PROBE_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_surface_probe"
internal const val SDK_QUAD_SURFACE_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_surface_probe.hold_ms"
internal const val SDK_QUAD_SURFACE_PROBE_WIDTH_PX = 512
internal const val SDK_QUAD_SURFACE_PROBE_HEIGHT_PX = 512
internal const val SDK_QUAD_SURFACE_PROBE_CHECKER_CELLS = 8
internal const val SDK_QUAD_SURFACE_PROBE_WIDTH_METERS = 0.55f
internal const val SDK_QUAD_SURFACE_PROBE_HEIGHT_METERS = 0.55f
internal const val SDK_QUAD_SURFACE_PROBE_DISTANCE_METERS = 0.85f
internal const val SDK_QUAD_SURFACE_PROBE_Z_INDEX = 22
internal const val SDK_QUAD_SURFACE_PROBE_DEFAULT_HOLD_MS = 30_000L
internal const val SDK_QUAD_SURFACE_PROBE_MIN_HOLD_MS = 1_000L
internal const val SDK_QUAD_SURFACE_PROBE_MAX_HOLD_MS = 120_000L

internal const val SDK_QUAD_VULKAN_PROBE_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_vulkan_probe"
internal const val SDK_QUAD_VULKAN_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_vulkan_probe.hold_ms"
internal const val SDK_QUAD_VULKAN_PROBE_FRAME_COUNT_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_vulkan_probe.frame_count"
internal const val SDK_QUAD_VULKAN_PROBE_DEFAULT_HOLD_MS = 8_000L
internal const val SDK_QUAD_VULKAN_PROBE_MIN_HOLD_MS = 1_000L
internal const val SDK_QUAD_VULKAN_PROBE_MAX_HOLD_MS = 120_000L
internal const val SDK_QUAD_VULKAN_PROBE_DEFAULT_FRAME_COUNT = 240
internal const val SDK_QUAD_VULKAN_PROBE_MAX_FRAME_COUNT = 1_800

internal const val SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe"
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_HOLD_MS_PROPERTY =
    "debug.rustyquest.spatial.sdk_quad_stereo_alpha_probe.hold_ms"
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_PX = 2048
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_PX = 1024
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_PER_EYE_WIDTH_PX = 1024
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_WIDTH_METERS = 1.15f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_HEIGHT_METERS = 1.15f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_LOW = 24
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_HIGH = 34
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_HIGH = 0.88f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_LOW = 0.45f
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_Z_INDEX_CHANGE_MS = 1_500L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_CHANGE_MS = 3_000L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_ALPHA_RESTORE_MS = 5_500L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_DEFAULT_HOLD_MS = 30_000L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_MIN_HOLD_MS = 3_000L
internal const val SDK_QUAD_STEREO_ALPHA_PROBE_MAX_HOLD_MS = 120_000L

internal const val PANEL_SURFACE_MATRIX_PROBE_PROPERTY =
    "debug.rustyquest.spatial.panel_surface_matrix_probe"
internal const val PANEL_SURFACE_MATRIX_PROBE_WIDTH_PX = 512
internal const val PANEL_SURFACE_MATRIX_PROBE_HEIGHT_PX = 512
internal const val PANEL_SURFACE_MATRIX_PROBE_FRAME_COUNT = 90
internal const val PANEL_SURFACE_MATRIX_PROBE_VARIANT_HOLD_MS = 2_500L
internal const val PANEL_SURFACE_MATRIX_PROBE_INTER_VARIANT_MS = 500L

internal object SpatialDiagnosticProbeRouteModule {
  const val MODULE_ID = "spatial-diagnostic-probe-route-policy"

  fun externalSwapchainProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(EXTERNAL_SWAPCHAIN_PROBE_PROPERTY) == true

  fun externalSwapchainProbeCycles(): Int =
      activityReadIntSystemProperty(
          EXTERNAL_SWAPCHAIN_PROBE_CYCLES_PROPERTY,
          EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLES,
          1,
          EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLES,
      )

  fun externalSwapchainProbeCycleMs(): Long =
      activityReadLongSystemProperty(
          EXTERNAL_SWAPCHAIN_PROBE_CYCLE_MS_PROPERTY,
          EXTERNAL_SWAPCHAIN_PROBE_DEFAULT_CYCLE_MS,
          EXTERNAL_SWAPCHAIN_PROBE_MIN_CYCLE_MS,
          EXTERNAL_SWAPCHAIN_PROBE_MAX_CYCLE_MS,
      )

  fun sdkQuadSurfaceProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(SDK_QUAD_SURFACE_PROBE_PROPERTY) == true

  fun sdkQuadSurfaceProbeHoldMs(): Long =
      activityReadLongSystemProperty(
          SDK_QUAD_SURFACE_PROBE_HOLD_MS_PROPERTY,
          SDK_QUAD_SURFACE_PROBE_DEFAULT_HOLD_MS,
          SDK_QUAD_SURFACE_PROBE_MIN_HOLD_MS,
          SDK_QUAD_SURFACE_PROBE_MAX_HOLD_MS,
      )

  fun sdkQuadVulkanProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(SDK_QUAD_VULKAN_PROBE_PROPERTY) == true

  fun sdkQuadVulkanProbeHoldMs(): Long =
      activityReadLongSystemProperty(
          SDK_QUAD_VULKAN_PROBE_HOLD_MS_PROPERTY,
          SDK_QUAD_VULKAN_PROBE_DEFAULT_HOLD_MS,
          SDK_QUAD_VULKAN_PROBE_MIN_HOLD_MS,
          SDK_QUAD_VULKAN_PROBE_MAX_HOLD_MS,
      )

  fun sdkQuadVulkanProbeFrameCount(): Int =
      activityReadIntSystemProperty(
          SDK_QUAD_VULKAN_PROBE_FRAME_COUNT_PROPERTY,
          SDK_QUAD_VULKAN_PROBE_DEFAULT_FRAME_COUNT,
          1,
          SDK_QUAD_VULKAN_PROBE_MAX_FRAME_COUNT,
      )

  fun sdkQuadStereoAlphaProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(SDK_QUAD_STEREO_ALPHA_PROBE_PROPERTY) == true

  fun sdkQuadStereoAlphaProbeHoldMs(): Long =
      activityReadLongSystemProperty(
          SDK_QUAD_STEREO_ALPHA_PROBE_HOLD_MS_PROPERTY,
          SDK_QUAD_STEREO_ALPHA_PROBE_DEFAULT_HOLD_MS,
          SDK_QUAD_STEREO_ALPHA_PROBE_MIN_HOLD_MS,
          SDK_QUAD_STEREO_ALPHA_PROBE_MAX_HOLD_MS,
      )

  fun panelSurfaceMatrixProbeEnabled(): Boolean =
      activityReadOptionalBooleanSystemProperty(PANEL_SURFACE_MATRIX_PROBE_PROPERTY) == true

  fun explicitOptInMarkerFields(propertyName: String): String =
      "spatialFeatureExplicitOptIn=true " +
          "spatialFeatureOptInRoute=android-system-property " +
          "featureOptInProperty=$propertyName"
}
