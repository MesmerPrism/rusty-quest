package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialPresentationPolicy(
    val lockedFinalPresentation: Boolean,
    val distortionSpeedScale: Float,
) {
  val fixedLayerOverride: Float?
    get() = if (lockedFinalPresentation) PrivateLayerControls.layers.first().index.toFloat() else null

  val fixedProjectionScale: Float?
    get() = if (lockedFinalPresentation) CAMERA_HWB_PROJECTION_TARGET_LIVE_SCALE_DEFAULT else null

  val appControlInputsEnabled: Boolean
    get() = !lockedFinalPresentation

  fun videoSettings(settings: SpatialVideoProjectionSettings): SpatialVideoProjectionSettings =
      if (lockedFinalPresentation) settings.copy(enabled = true) else settings

  fun markerFields(): String =
      "lockedFinalPresentation=$lockedFinalPresentation " +
          "appControlInputsEnabled=$appControlInputsEnabled " +
          "forcedPrivateLayerOverride=${fixedLayerOverride ?: "none"} " +
          "forcedProjectionScale=${fixedProjectionScale ?: "none"} " +
          "videoProjectionForcedEnabled=$lockedFinalPresentation " +
          "videoBorderForcedEnabled=$lockedFinalPresentation " +
          "distortionSpeedScale=${activityMarkerFloat(distortionSpeedScale)}"
}

internal object SpatialPresentationBuildPolicy {
  val current =
      SpatialPresentationPolicy(
          lockedFinalPresentation = BuildConfig.LOCKED_FINAL_PRESENTATION,
          distortionSpeedScale = BuildConfig.DISTORTION_SPEED_SCALE,
      )
}
