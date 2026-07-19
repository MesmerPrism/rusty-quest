package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

internal const val VR_STROBE_MAX_SIGNAL_EVALUATIONS_PER_FRAGMENT = 1
internal const val VR_STROBE_PATTERN_ATTRIBUTE_WRITES = 5
internal const val VR_STROBE_INITIAL_PROFILE_CLEAR_WRITES =
    15 + 4 * VR_STROBE_MAX_PATTERN_INSTANCES * VR_STROBE_PATTERN_ATTRIBUTE_WRITES
internal const val VR_STROBE_PROFILE_WRITES_PER_SCENE_TICK = 6
internal const val VR_STROBE_PROFILE_PUBLICATION_PASSES = 2

/**
 * A cheap, continuously reasserted shader signature for an accepted randomize transaction.
 *
 * Spatial SDK exposes only one-at-a-time native material mutations. Profile fields still use
 * those writes, but mode/time is already refreshed on every scene tick. Carrying a generation
 * phase and alternating palette polarity through that hot attribute provides an attended
 * publication witness after the bounded multi-frame profile commit finishes.
 */
internal data class VrStrobeVisualGeneration(
    val phaseOffset: Float,
    val palettePolarity: Float,
)

internal object VrStrobeVisualGenerationPolicy {
  private const val CYCLE = 4_093L
  private const val PHASE_MULTIPLIER = 1_597L
  private const val PHASE_INCREMENT = 887L
  private const val MAX_ABS_PHASE_OFFSET = 0.45f

  fun forRendererRevision(rendererRevision: Long): VrStrobeVisualGeneration {
    if (rendererRevision <= 0L) return VrStrobeVisualGeneration(0f, 1f)
    val folded = Math.floorMod(rendererRevision, CYCLE)
    val phaseIndex = (folded * PHASE_MULTIPLIER + PHASE_INCREMENT) % CYCLE
    val unitPhase = phaseIndex.toFloat() / (CYCLE - 1L).toFloat()
    return VrStrobeVisualGeneration(
        phaseOffset = (unitPhase * 2f - 1f) * MAX_ABS_PHASE_OFFSET,
        palettePolarity = if (rendererRevision % 2L == 0L) 1f else -1f,
    )
  }
}

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

internal data class VrStrobeProfilePublicationPlan(
    val profileUniformWrites: Int,
    val maxWritesPerSceneTick: Int,
    val publicationPasses: Int,
    val batchesPerPass: Int,
    val sceneTicks: Int,
    val totalProfileUniformWrites: Int,
)

internal fun VrStrobeMaterialUpdatePlan.profilePublicationPlan():
    VrStrobeProfilePublicationPlan {
  val profileUniformWrites = (uniformWriteCount - 1).coerceAtLeast(0)
  val batchesPerPass =
      (profileUniformWrites + VR_STROBE_PROFILE_WRITES_PER_SCENE_TICK - 1) /
          VR_STROBE_PROFILE_WRITES_PER_SCENE_TICK
  return VrStrobeProfilePublicationPlan(
      profileUniformWrites = profileUniformWrites,
      maxWritesPerSceneTick = VR_STROBE_PROFILE_WRITES_PER_SCENE_TICK,
      publicationPasses = VR_STROBE_PROFILE_PUBLICATION_PASSES,
      batchesPerPass = batchesPerPass,
      sceneTicks = batchesPerPass * VR_STROBE_PROFILE_PUBLICATION_PASSES,
      totalProfileUniformWrites = profileUniformWrites * VR_STROBE_PROFILE_PUBLICATION_PASSES,
  )
}

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
