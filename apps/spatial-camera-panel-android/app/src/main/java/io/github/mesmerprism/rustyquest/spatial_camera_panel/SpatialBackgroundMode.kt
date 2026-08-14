package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal enum class SpatialBackgroundMode(val token: String) {
  Black("black"),
  Passthrough("passthrough"),
  LutPassthrough("lut-passthrough");

  companion object {
    fun fromToken(token: String?): SpatialBackgroundMode =
        when (token?.trim()?.lowercase()?.replace('_', '-')) {
          Passthrough.token -> Passthrough
          LutPassthrough.token, "lut", "poster-lut", "posterized-passthrough" -> LutPassthrough
          else -> Black
        }
  }
}

internal data class SpatialBackgroundEffects(
    val blackBackingVisible: Boolean,
    val systemPassthroughRequested: Boolean,
    val passthroughLutRequested: Boolean,
)

/** Resolves the one visible background plus the retained layer-7 diagnostic LUT request. */
internal object SpatialBackgroundModePolicy {
  fun resolve(
      mode: SpatialBackgroundMode,
      diagnosticLutRequested: Boolean,
  ): SpatialBackgroundEffects =
      SpatialBackgroundEffects(
          blackBackingVisible = mode == SpatialBackgroundMode.Black,
          systemPassthroughRequested = true,
          passthroughLutRequested =
              mode == SpatialBackgroundMode.LutPassthrough || diagnosticLutRequested,
      )

  fun marker(
      mode: SpatialBackgroundMode,
      diagnosticLutRequested: Boolean,
      effects: SpatialBackgroundEffects,
      source: String,
  ): String =
      "channel=spatial-background status=mode-applied " +
          "source=${activityMarkerToken(source)} backgroundMode=${mode.token} " +
          "backgroundBlackBackingVisible=${effects.blackBackingVisible} " +
          "backgroundSystemPassthroughRequested=${effects.systemPassthroughRequested} " +
          "backgroundPassthroughLutRequested=${effects.passthroughLutRequested} " +
          "diagnosticPassthroughLutRequested=$diagnosticLutRequested " +
          "passthroughLutOwner=spatial-sdk-system-passthrough " +
          "passthroughSessionPolicy=always-on opaqueBackgroundCarrierVisible=${effects.blackBackingVisible}"
}
