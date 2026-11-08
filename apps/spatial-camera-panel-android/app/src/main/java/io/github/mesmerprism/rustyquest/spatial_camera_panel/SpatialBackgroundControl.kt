package io.github.mesmerprism.rustyquest.spatial_camera_panel

/** Process-local adapter state only; this is neither a media catalog nor rendered-frame proof. */
internal data class SpatialBackgroundControlObservation(
    val sceneEpoch: Long = 0L,
    val sceneReady: Boolean = false,
    val sceneRetired: Boolean = false,
    val vrReady: Boolean = false,
    val vrGeneration: Long = 0L,
    val revision: Long = 0L,
    val mode: String = "black",
    val backingEntityId: Long = 0L,
    val backingEntityEpoch: Long = 0L,
    val videoEntityId: Long = 0L,
    val videoEntityEpoch: Long = 0L,
    val surfaceIdentity: Int = 0,
    val decoderGeneration: Long = 0L,
)
