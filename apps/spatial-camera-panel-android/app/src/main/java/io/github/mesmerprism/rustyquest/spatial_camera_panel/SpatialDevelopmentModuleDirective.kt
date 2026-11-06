package io.github.mesmerprism.rustyquest.spatial_camera_panel


/** Fixed developer-addon data. Each named consumer retains its own admission and readback. */
data class SpatialDevelopmentModuleDirective(
    val mode: String,
    val generation: Long? = null,
    val sha256: String? = null,
    val policy: String? = null,
)
