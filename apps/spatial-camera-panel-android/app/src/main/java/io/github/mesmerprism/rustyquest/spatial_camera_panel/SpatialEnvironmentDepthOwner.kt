package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal enum class SpatialEnvironmentDepthOwner(
    val markerToken: String,
) {
  Disabled("disabled"),
  LegacyNativeSidecar("legacy-native-sidecar"),
  SpatialSdkApiLayer("spatial-sdk-api-layer");

  val ownsLegacyProvider: Boolean
    get() = this == LegacyNativeSidecar

  companion object {
    fun parse(raw: String): SpatialEnvironmentDepthOwner =
        entries.firstOrNull { it.markerToken == raw.trim().lowercase() }
            ?: error("unsupported_environment_depth_owner")
  }
}
