package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialVideoProjectionSettings(
    val enabled: Boolean,
    val path: String,
    val stereoLayout: String,
    val width: Int,
    val height: Int,
    val maxImages: Int,
    val fpsCap: Int,
    val looping: Boolean,
    val opacity: Float,
    val highRateJsonPayload: Boolean,
) {
  val active: Boolean
    get() = enabled && path.isNotBlank()

  companion object {
    fun disabled(): SpatialVideoProjectionSettings =
        SpatialVideoProjectionSettings(
            enabled = false,
            path = "",
            stereoLayout = "side-by-side-left-right",
            width = 3840,
            height = 1920,
            maxImages = 3,
            fpsCap = 30,
            looping = true,
            opacity = 1.0f,
            highRateJsonPayload = false,
        )
  }
}
