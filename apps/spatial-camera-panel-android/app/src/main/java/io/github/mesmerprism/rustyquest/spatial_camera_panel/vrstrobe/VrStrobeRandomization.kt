package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A Quest-safe distribution inside Trevor's editor bounds.
 *
 * The browser randomizer samples the complete editor domain. That is useful for
 * exploration, but it frequently produces clipped palettes, equal-edge
 * vignettes, aliased temporal rates, and several simultaneous noise branches on
 * a 90 Hz mobile renderer. This envelope preserves the source vocabulary while
 * keeping randomize visually differentiated and bounded in GPU cost.
 */
internal object VrStrobeQuestRandomizationEnvelope {
  const val ID = "quest-reliable-v3"
  const val INTERFERENCE_SCALE_MIN = 0.75f
  const val INTERFERENCE_SCALE_MAX = 16f
  const val NON_RAY_PERIOD_MIN = 1.5f
  const val NON_RAY_PERIOD_MAX = 50f
  const val RAY_PERIOD_MIN = 3f
  const val RAY_PERIOD_MAX = 50f
  const val PERLIN_SCALE_MIN = 0.5f
  const val PERLIN_SCALE_MAX = 40f
  const val FINE_DETAIL_PROBABILITY = 0.60f
  const val BRIGHTNESS_MIN = -0.12f
  const val BRIGHTNESS_MAX = 0.12f
  const val CONTRAST_MIN = 0.9f
  const val CONTRAST_MAX = 1.55f
  const val NOISE_STRENGTH_MAX = 0.25f
  const val TEMPORAL_FREQUENCY_MIN_HZ = 1f
  const val TEMPORAL_FREQUENCY_MAX_HZ = 30f
  const val TEMPORAL_DUTY_MIN_PERCENT = 20f
  const val TEMPORAL_DUTY_MAX_PERCENT = 80f
  const val MIN_COLOR_DISTANCE_SQUARED = 0.18f
  const val MAX_ACTIVE_PATTERNS = 3

  fun accepts(profile: VrStrobeInterferenceProfile): Boolean {
    val colors =
        listOf(profile.color1, profile.color2, profile.color3).take(profile.colorCount)
    val colorsSeparated =
        colors.indices.all { left ->
          ((left + 1) until colors.size).all { right ->
            colorDistanceSquared(colors[left], colors[right]) >= MIN_COLOR_DISTANCE_SQUARED
          }
        }
    val activePatterns = profile.patterns.filter(VrStrobePattern::active)
    val spatialDetailSafe =
        activePatterns.all { pattern ->
          when (pattern.kind) {
            VrStrobePatternKind.RAY -> pattern.period in RAY_PERIOD_MIN..RAY_PERIOD_MAX
            VrStrobePatternKind.PERLIN ->
                pattern.perlinScale in PERLIN_SCALE_MIN..PERLIN_SCALE_MAX
            else -> pattern.period in NON_RAY_PERIOD_MIN..NON_RAY_PERIOD_MAX
          }
        }
    val vignetteSafe =
        profile.vignetteEdge == 0f || profile.vignetteEdge - profile.vignetteCenter >= 0.2f
    return colorsSeparated &&
        activePatterns.isNotEmpty() &&
        activePatterns.size <= MAX_ACTIVE_PATTERNS &&
        activePatterns.all { abs(it.strength) >= 0.44f } &&
        spatialDetailSafe &&
        profile.scale in INTERFERENCE_SCALE_MIN..INTERFERENCE_SCALE_MAX &&
        profile.brightness in BRIGHTNESS_MIN..BRIGHTNESS_MAX &&
        profile.contrast in CONTRAST_MIN..CONTRAST_MAX &&
        profile.noiseStrength in 0f..NOISE_STRENGTH_MAX &&
        vignetteSafe &&
        activePatterns.count { it.distortAmp > 0f } <= 1 &&
        activePatterns.count { it.waveAmp > 0f } <= 1
  }

  fun accepts(profile: VrStrobeTemporalProfile): Boolean =
      colorDistanceSquared(profile.color1, profile.color2) >= MIN_COLOR_DISTANCE_SQUARED &&
          profile.frequencyHz in TEMPORAL_FREQUENCY_MIN_HZ..TEMPORAL_FREQUENCY_MAX_HZ &&
          profile.dutyPercent in TEMPORAL_DUTY_MIN_PERCENT..TEMPORAL_DUTY_MAX_PERCENT &&
          profile.noiseResolution in 4..24 &&
          profile.noiseAmplitude1 in 0f..0.35f &&
          profile.noiseAmplitude2 in 0f..0.35f &&
          profile.fixationSize in 8..48

  fun receipt(profile: VrStrobeInterferenceProfile): String =
      "randomizationEnvelope=$ID envelopeAccepted=${accepts(profile)} " +
          "scale=${profile.scale} brightness=${profile.brightness} contrast=${profile.contrast} " +
          "noiseStrength=${profile.noiseStrength} " +
          "maxPatternPeriod=${profile.patterns.filter { it.active && it.kind != VrStrobePatternKind.PERLIN }.maxOfOrNull { it.period } ?: 0f} " +
          "maxPerlinScale=${profile.patterns.filter { it.active && it.kind == VrStrobePatternKind.PERLIN }.maxOfOrNull { it.perlinScale } ?: 0f} " +
          "fineSpatialDetail=${fineSpatialDetail(profile)} " +
          "distortionBranches=${profile.patterns.count { it.active && it.distortAmp > 0f }} " +
          "waveBranches=${profile.patterns.count { it.active && it.waveAmp > 0f }} " +
          "vignetteWidth=${(profile.vignetteEdge - profile.vignetteCenter).coerceAtLeast(0f)}"

  fun receipt(profile: VrStrobeTemporalProfile): String =
      "randomizationEnvelope=$ID envelopeAccepted=${accepts(profile)} " +
          "frequencyHz=${profile.frequencyHz} dutyPercent=${profile.dutyPercent} " +
          "noiseResolution=${profile.noiseResolution}"

  fun fineSpatialDetail(profile: VrStrobeInterferenceProfile): Boolean =
      profile.scale > 6f ||
          profile.patterns.any { pattern ->
            pattern.active &&
                when (pattern.kind) {
                  VrStrobePatternKind.RAY -> pattern.period >= 32f
                  VrStrobePatternKind.PERLIN -> pattern.perlinScale > 18f
                  else -> pattern.period > 28f
                }
          }

  private fun colorDistanceSquared(left: VrStrobeColor, right: VrStrobeColor): Float {
    val red = left.red - right.red
    val green = left.green - right.green
    val blue = left.blue - right.blue
    return red * red + green * green + blue * blue
  }
}

internal fun VrStrobeInterferenceProfile.randomized(
    random: Random = Random.Default,
): VrStrobeInterferenceProfile {
  fun between(min: Float, max: Float): Float = min + random.nextFloat() * (max - min)
  fun sourceFloat(min: Float, max: Float): Float =
      (between(min, max) * 1_000f).roundToInt() / 1_000f
  fun signedMagnitude(min: Float, max: Float): Float =
      sourceFloat(min, max) * if (random.nextBoolean()) 1f else -1f
  fun sparse(probability: Float): Boolean = random.nextFloat() < probability
  fun detailFloat(coarseMin: Float, coarseMax: Float, fineMin: Float, fineMax: Float): Float =
      if (sparse(VrStrobeQuestRandomizationEnvelope.FINE_DETAIL_PROBABILITY)) {
        sourceFloat(fineMin, fineMax)
      } else {
        sourceFloat(coarseMin, coarseMax)
      }

  val paletteAnchors =
      listOf(
          intArrayOf(244, 104, 104),
          intArrayOf(104, 244, 104),
          intArrayOf(104, 104, 244),
          intArrayOf(244, 244, 104),
          intArrayOf(244, 104, 244),
          intArrayOf(104, 244, 244),
      )
  val palette =
      paletteAnchors.shuffled(random).take(3).map { channels ->
        fun jitter(channel: Int): Int =
            (channel + random.nextInt(-10, 11)).coerceIn(100, 254)
        VrStrobeColor(
            (jitter(channels[0]) shl 16) or
                (jitter(channels[1]) shl 8) or
                jitter(channels[2])
        )
      }

  var distortionBudget = 1
  var waveBudget = 1
  fun randomizedPattern(pattern: VrStrobePattern): VrStrobePattern {
    val distortionEnabled =
        pattern.active && distortionBudget > 0 && sparse(0.12f)
    if (distortionEnabled) distortionBudget -= 1
    val waveEnabled = pattern.active && waveBudget > 0 && sparse(0.12f)
    if (waveEnabled) waveBudget -= 1
    return when (pattern.kind) {
      VrStrobePatternKind.PERLIN ->
          pattern.copy(
              strength = signedMagnitude(0.45f, 1.35f),
              pivotX = sourceFloat(-1f, 1f),
              pivotY = sourceFloat(-1f, 1f),
              perlinScale =
                  detailFloat(
                      VrStrobeQuestRandomizationEnvelope.PERLIN_SCALE_MIN,
                      18f,
                      18.5f,
                      VrStrobeQuestRandomizationEnvelope.PERLIN_SCALE_MAX,
                  ),
              perlinZSpeed = signedMagnitude(0.25f, 3f),
              perlinZOffset = sourceFloat(-20f, 20f),
          )
      else -> {
        val common =
            pattern.copy(
                strength = signedMagnitude(0.45f, 1.35f),
                period =
                    if (pattern.kind == VrStrobePatternKind.RAY) {
                      if (sparse(VrStrobeQuestRandomizationEnvelope.FINE_DETAIL_PROBABILITY)) {
                        random.nextInt(32, 51).toFloat()
                      } else {
                        random.nextInt(3, 32).toFloat()
                      }
                    } else {
                      detailFloat(
                          VrStrobeQuestRandomizationEnvelope.NON_RAY_PERIOD_MIN,
                          28f,
                          28.5f,
                          VrStrobeQuestRandomizationEnvelope.NON_RAY_PERIOD_MAX,
                      )
                    },
                speed = signedMagnitude(0.35f, 4f),
                pivotX = sourceFloat(-1f, 1f),
                pivotY = sourceFloat(-1f, 1f),
                distortFreq = sourceFloat(0.5f, 6f),
                distortAmp = if (distortionEnabled) sourceFloat(0.05f, 0.45f) else 0f,
                distortSpeed = signedMagnitude(0.25f, 3f),
                distMultPar = sourceFloat(0.6f, 1.8f),
                distMultOrth = sourceFloat(0.6f, 1.8f),
                waveFreq = sourceFloat(0.5f, 8f),
                waveAmp = if (waveEnabled) sourceFloat(0.05f, 0.5f) else 0f,
                waveShape = sourceFloat(0.2f, 0.8f),
                rotationSpeed = signedMagnitude(0.05f, 0.65f),
            )
        if (pattern.kind == VrStrobePatternKind.STRIPE) {
          common.copy(
              angle = sourceFloat(0f, 6.28f),
              extent = if (sparse(0.08f)) random.nextInt(2, 9).toFloat() else 0f,
          )
        } else {
          common.copy(
              rotationPivotX = sourceFloat(-0.75f, 0.75f),
              rotationPivotY = sourceFloat(-0.75f, 0.75f),
              noiseMove = sourceFloat(0f, 0.6f),
          )
        }
      }
    }
  }

  val viableSourcePatterns =
      when {
        patterns.any(VrStrobePattern::active) -> patterns
        patterns.isNotEmpty() -> patterns.mapIndexed { index, pattern -> pattern.copy(active = index == 0) }
        else -> listOf(VrStrobePattern(VrStrobePatternKind.STRIPE))
      }
  var activePatternBudget = VrStrobeQuestRandomizationEnvelope.MAX_ACTIVE_PATTERNS
  val sourcePatterns =
      viableSourcePatterns.map { pattern ->
        if (!pattern.active) {
          pattern
        } else if (activePatternBudget > 0) {
          activePatternBudget -= 1
          pattern
        } else {
          pattern.copy(active = false)
        }
      }
  val vignetteEnabled = sparse(0.12f)
  val vignetteCenter = if (vignetteEnabled) sourceFloat(0.55f, 0.85f) else 0f
  val randomized =
      copy(
              color1 = palette[0],
              color2 = palette[1],
              color3 = palette[2],
              oscillatorActive = sparse(0.10f),
              oscillatorFrequencyHz = sourceFloat(0.5f, 12f),
              oscillatorShape = sourceFloat(0.75f, 2.5f),
              scale =
                  detailFloat(
                      VrStrobeQuestRandomizationEnvelope.INTERFERENCE_SCALE_MIN,
                      6f,
                      6.5f,
                      VrStrobeQuestRandomizationEnvelope.INTERFERENCE_SCALE_MAX,
                  ),
              shearX = if (sparse(0.08f)) sourceFloat(-0.35f, 0.35f) else 0f,
              shearY = if (sparse(0.08f)) sourceFloat(-0.35f, 0.35f) else 0f,
              offsetX = sourceFloat(-0.5f, 0.5f),
              offsetY = sourceFloat(-0.5f, 0.5f),
              shakeAmplitude = if (sparse(0.08f)) sourceFloat(0.002f, 0.025f) else 0f,
              shakeFrequencyHz = sourceFloat(0.5f, 10f),
              rotationSpeed = signedMagnitude(0.05f, 1.25f),
              stepFactor = sourceFloat(0.05f, 0.75f),
              trailAmount = if (sparse(0.08f)) sourceFloat(0.05f, 0.35f) else 0f,
              blurRadius = if (sparse(0.08f)) sourceFloat(0.5f, 3f) else 0f,
              glowStrength = if (sparse(0.08f)) sourceFloat(0.1f, 0.65f) else 0f,
              brightness =
                  sourceFloat(
                      VrStrobeQuestRandomizationEnvelope.BRIGHTNESS_MIN,
                      VrStrobeQuestRandomizationEnvelope.BRIGHTNESS_MAX,
                  ),
              contrast =
                  sourceFloat(
                      VrStrobeQuestRandomizationEnvelope.CONTRAST_MIN,
                      VrStrobeQuestRandomizationEnvelope.CONTRAST_MAX,
                  ),
              noiseFrequency = sourceFloat(0.25f, 3f),
              noiseStrength = if (sparse(0.15f)) sourceFloat(0.05f, 0.25f) else 0f,
              noiseBias = sourceFloat(0.25f, 0.75f),
              vignetteCenter = vignetteCenter,
              vignetteEdge =
                  if (vignetteEnabled) {
                    sourceFloat(vignetteCenter + 0.25f, vignetteCenter + 0.5f)
                  } else {
                    0f
                  },
              vignetteBias = sourceFloat(0.2f, 0.8f),
              patterns = sourcePatterns.map(::randomizedPattern),
          )
          .sanitized()
  check(VrStrobeQuestRandomizationEnvelope.accepts(randomized)) {
    "quest-randomization-envelope-rejected"
  }
  return randomized
}

internal fun VrStrobeTemporalProfile.randomized(
    random: Random = Random.Default,
): VrStrobeTemporalProfile {
  fun between(min: Float, max: Float): Float = min + random.nextFloat() * (max - min)
  fun sourceFloat(min: Float, max: Float): Float =
      (between(min, max) * 1_000f).roundToInt() / 1_000f
  fun colorDistanceSquared(left: VrStrobeColor, right: VrStrobeColor): Float {
    val red = left.red - right.red
    val green = left.green - right.green
    val blue = left.blue - right.blue
    return red * red + green * green + blue * blue
  }

  val safeColor2 =
      if (
          colorDistanceSquared(color1, color2) >=
              VrStrobeQuestRandomizationEnvelope.MIN_COLOR_DISTANCE_SQUARED
      ) {
        color2
      } else if (color1.red + color1.green + color1.blue > 1.5f) {
        VrStrobeColor.BLACK
      } else {
        VrStrobeColor.WHITE
      }
  val noisePhase1 = random.nextFloat() < 0.2f
  val noisePhase2 = random.nextFloat() < 0.2f
  val randomized =
      copy(
              color2 = safeColor2,
              frequencyHz =
                  sourceFloat(
                      VrStrobeQuestRandomizationEnvelope.TEMPORAL_FREQUENCY_MIN_HZ,
                      VrStrobeQuestRandomizationEnvelope.TEMPORAL_FREQUENCY_MAX_HZ,
                  ),
              dutyPercent =
                  sourceFloat(
                      VrStrobeQuestRandomizationEnvelope.TEMPORAL_DUTY_MIN_PERCENT,
                      VrStrobeQuestRandomizationEnvelope.TEMPORAL_DUTY_MAX_PERCENT,
                  ),
              noiseType = if (random.nextBoolean()) VrStrobeNoiseType.WHITE else VrStrobeNoiseType.PERLIN,
              noiseResolution = random.nextInt(4, 25),
              noisePhase1 = noisePhase1,
              noiseAmplitude1 = if (noisePhase1) sourceFloat(0.05f, 0.35f) else 0f,
              noisePhase2 = noisePhase2,
              noiseAmplitude2 = if (noisePhase2) sourceFloat(0.05f, 0.35f) else 0f,
              fixationEnabled = random.nextBoolean(),
              fixationSize = random.nextInt(8, 49),
          )
          .sanitized()
  check(VrStrobeQuestRandomizationEnvelope.accepts(randomized)) {
    "quest-temporal-randomization-envelope-rejected"
  }
  return randomized
}
