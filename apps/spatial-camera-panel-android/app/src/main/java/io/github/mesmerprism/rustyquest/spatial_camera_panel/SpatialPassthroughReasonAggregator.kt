package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.EnvironmentDepthMode

internal data class SpatialPassthroughReasons(
    val visibleBackground: Boolean = false,
    val diagnosticLut: Boolean = false,
    val environmentDepth: Boolean = false,
) {
  val systemPassthroughRequired: Boolean
    get() = visibleBackground || diagnosticLut || environmentDepth
}

internal data class SpatialPassthroughReasonState(
    val reasons: SpatialPassthroughReasons,
    val systemPassthroughEnabled: Boolean,
    val environmentDepthMode: EnvironmentDepthMode,
)

/** Keeps visible Background selection independent from the internal depth prerequisite. */
internal class SpatialPassthroughReasonAggregator(
    private val setSystemPassthrough: (Boolean) -> Unit,
    private val readSystemPassthrough: () -> Boolean,
    private val setEnvironmentDepthMode: (EnvironmentDepthMode) -> Unit,
    private val marker: (String) -> Unit,
) {
  private var reasons = SpatialPassthroughReasons()
  private var appliedSystemPassthrough = false
  private var systemPassthroughRequestIssued = false
  private var appliedDepthMode = EnvironmentDepthMode.OFF

  fun updateVisibleReasons(
      mode: SpatialBackgroundMode,
      diagnosticLutRequested: Boolean,
      source: String,
  ): SpatialPassthroughReasonState =
      applyReasons(
          reasons.copy(
              visibleBackground = mode != SpatialBackgroundMode.Black,
              diagnosticLut = diagnosticLutRequested,
          ),
          source,
      )

  fun updateEnvironmentDepthRequired(
      required: Boolean,
      source: String,
  ): SpatialPassthroughReasonState =
      applyReasons(reasons.copy(environmentDepth = required), source)

  fun snapshot(): SpatialPassthroughReasonState =
      SpatialPassthroughReasonState(reasons, appliedSystemPassthrough, appliedDepthMode)

  fun reconcile(source: String): SpatialPassthroughReasonState = applyReasons(reasons, source)

  private fun applyReasons(
      updated: SpatialPassthroughReasons,
      source: String,
  ): SpatialPassthroughReasonState {
    val previous = reasons
    reasons = updated
    val requestedDepthMode =
        if (updated.environmentDepth) EnvironmentDepthMode.TEXTURE_ONLY else EnvironmentDepthMode.OFF

    val passthroughReadbackBefore = readSystemPassthrough()
    if (updated.systemPassthroughRequired && !passthroughReadbackBefore) {
      setSystemPassthrough(true)
      systemPassthroughRequestIssued = true
    }
    if (requestedDepthMode != appliedDepthMode) {
      setEnvironmentDepthMode(requestedDepthMode)
      appliedDepthMode = requestedDepthMode
    }
    if (!updated.systemPassthroughRequired &&
        (systemPassthroughRequestIssued || passthroughReadbackBefore)) {
      setSystemPassthrough(false)
      systemPassthroughRequestIssued = false
    }
    appliedSystemPassthrough = readSystemPassthrough()

    marker(
        "channel=spatial-passthrough-reasons status=applied " +
            "source=${activityMarkerToken(source)} " +
            "visibleBackgroundReason=${updated.visibleBackground} " +
            "diagnosticLutReason=${updated.diagnosticLut} " +
            "environmentDepthReason=${updated.environmentDepth} " +
            "previousEnvironmentDepthReason=${previous.environmentDepth} " +
            "systemPassthroughRequested=${updated.systemPassthroughRequired} " +
            "systemPassthroughRequestIssued=$systemPassthroughRequestIssued " +
            "systemPassthroughReadback=$appliedSystemPassthrough " +
            "environmentDepthMode=${appliedDepthMode.name} " +
            "backgroundSelectionIndependent=true cachedPassthroughReadbackUsed=false runtimeCrash=false"
    )
    return snapshot()
  }
}
