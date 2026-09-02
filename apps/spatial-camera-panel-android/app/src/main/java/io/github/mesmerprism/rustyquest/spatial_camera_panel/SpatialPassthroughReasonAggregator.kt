package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.EnvironmentDepthMode

internal data class SpatialPassthroughReasons(
    val visibleBackground: Boolean = false,
    val diagnosticLut: Boolean = false,
    val environmentDepth: Boolean = false,
) {
  /**
   * This product keeps Meta system passthrough alive for the whole scene session. Opaque black and
   * video carriers occlude it when requested; background selection never tears the substrate down.
   */
  val systemPassthroughRequired: Boolean
    get() = true
}

internal data class SpatialPassthroughReasonState(
    val reasons: SpatialPassthroughReasons,
    val systemPassthroughEnabled: Boolean,
    val systemPassthroughObserved: Boolean,
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
      SpatialPassthroughReasonState(
          reasons,
          appliedSystemPassthrough,
          readSystemPassthrough(),
          appliedDepthMode,
      )

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
    if (!systemPassthroughRequestIssued) {
      setSystemPassthrough(true)
      systemPassthroughRequestIssued = true
    }
    if (requestedDepthMode != appliedDepthMode) {
      setEnvironmentDepthMode(requestedDepthMode)
      appliedDepthMode = requestedDepthMode
    }
    val passthroughReadbackAfter = readSystemPassthrough()
    // Scene.isSystemPassthroughEnabled() is a false-negative on supported Quest builds even after
    // enablePassthrough(true) has visibly taken effect. The accepted dispatch is therefore the
    // session policy readback; the platform getter remains separately observable diagnostics.
    appliedSystemPassthrough = systemPassthroughRequestIssued

    marker(
        "channel=spatial-passthrough-reasons status=applied " +
            "source=${activityMarkerToken(source)} " +
            "visibleBackgroundReason=${updated.visibleBackground} " +
            "diagnosticLutReason=${updated.diagnosticLut} " +
            "environmentDepthReason=${updated.environmentDepth} " +
            "previousEnvironmentDepthReason=${previous.environmentDepth} " +
            "systemPassthroughRequested=${updated.systemPassthroughRequired} " +
            "systemPassthroughRequestIssued=$systemPassthroughRequestIssued " +
            "systemPassthroughReadback=$passthroughReadbackAfter " +
            "environmentDepthMode=${appliedDepthMode.name} " +
            "backgroundSelectionIndependent=true passthroughSessionPolicy=always-on " +
            "opaqueCarriersOwnOcclusion=true platformReadbackAdvisory=true " +
            "passthroughReadbackBefore=$passthroughReadbackBefore runtimeCrash=false"
    )
    return snapshot()
  }
}
