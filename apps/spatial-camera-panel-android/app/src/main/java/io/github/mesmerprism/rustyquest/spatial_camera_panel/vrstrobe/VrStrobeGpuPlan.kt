package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

internal const val VR_STROBE_MAX_SIGNAL_EVALUATIONS_PER_FRAGMENT = 1
internal const val VR_STROBE_PATTERN_ATTRIBUTE_WRITES = 5
internal const val VR_STROBE_INITIAL_PROFILE_CLEAR_WRITES =
    15 + 4 * VR_STROBE_MAX_PATTERN_INSTANCES * VR_STROBE_PATTERN_ATTRIBUTE_WRITES

internal data class VrStrobeGpuPlan(
    val stripeCount: Int,
    val rippleCount: Int,
    val rayCount: Int,
    val perlinCount: Int,
) {
  val activePatternCount: Int
    get() = stripeCount + rippleCount + rayCount + perlinCount
}

/**
 * Host-to-Spatial material mutations for one randomize submission.
 *
 * Pattern counts are the shader's active-slot authority, so inactive trailing
 * slots do not need to be cleared on every submission. They are initialized
 * once when the material is created and ignored whenever their count is lower.
 */
internal data class VrStrobeMaterialUpdatePlan(
    val activePatternCount: Int,
    val uniformWriteCount: Int,
    val fullProfileClear: Boolean = false,
    val inactivePatternSlotWrites: Int = 0,
)

internal fun VrStrobeInterferenceProfile.materialUpdatePlan(): VrStrobeMaterialUpdatePlan {
  val activePatternCount = gpuPlan().activePatternCount
  return VrStrobeMaterialUpdatePlan(
      activePatternCount = activePatternCount,
      // Ten profile globals, one pattern-count commit, mode/time, and five vec4s per active slot.
      uniformWriteCount = 12 + activePatternCount * VR_STROBE_PATTERN_ATTRIBUTE_WRITES,
  )
}

internal fun VrStrobeTemporalProfile.materialUpdatePlan(): VrStrobeMaterialUpdatePlan =
    VrStrobeMaterialUpdatePlan(
        activePatternCount = 0,
        // Seven temporal/profile globals, one zero-count commit, and mode/time.
        uniformWriteCount = 9,
    )

internal fun VrStrobeInterferenceProfile.activeGpuPatterns(
    kind: VrStrobePatternKind,
): List<VrStrobePattern> =
    patterns(kind).filter(VrStrobePattern::active).take(VR_STROBE_MAX_PATTERN_INSTANCES)

internal fun VrStrobeInterferenceProfile.gpuPlan(): VrStrobeGpuPlan =
    VrStrobeGpuPlan(
        stripeCount = activeGpuPatterns(VrStrobePatternKind.STRIPE).size,
        rippleCount = activeGpuPatterns(VrStrobePatternKind.RIPPLE).size,
        rayCount = activeGpuPatterns(VrStrobePatternKind.RAY).size,
        perlinCount = activeGpuPatterns(VrStrobePatternKind.PERLIN).size,
    )
