package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.math.roundToInt

internal const val VR_STROBE_MAX_PATTERN_INSTANCES = 8
internal const val VR_STROBE_DEFAULT_DURATION_SECONDS = 15f
internal const val VR_STROBE_MIN_DURATION_SECONDS = 1f
internal const val VR_STROBE_MAX_DURATION_SECONDS = 300f

internal data class VrStrobeColor(val rgb: Int) {
  val red: Float
    get() = ((rgb shr 16) and 0xff) / 255f

  val green: Float
    get() = ((rgb shr 8) and 0xff) / 255f

  val blue: Float
    get() = (rgb and 0xff) / 255f

  fun hex(): String = "#%06x".format(rgb)

  companion object {
    val BLACK = of("#000000")
    val WHITE = of("#ffffff")
    val RED = of("#ff0000")

    fun of(raw: String, fallback: VrStrobeColor? = null): VrStrobeColor {
      val token = raw.trim().removePrefix("#")
      val parsed = token.takeIf { it.length == 6 }?.toIntOrNull(16)
      return if (parsed != null) VrStrobeColor(parsed and 0xffffff)
      else fallback ?: VrStrobeColor(0)
    }

    fun fromChannels(red: Float, green: Float, blue: Float): VrStrobeColor {
      val r = (red.coerceIn(0f, 1f) * 255f).roundToInt()
      val g = (green.coerceIn(0f, 1f) * 255f).roundToInt()
      val b = (blue.coerceIn(0f, 1f) * 255f).roundToInt()
      return VrStrobeColor((r shl 16) or (g shl 8) or b)
    }
  }
}

internal enum class VrStrobePatternKind { STRIPE, RIPPLE, RAY, PERLIN }

internal data class VrStrobePattern(
    val kind: VrStrobePatternKind,
    val active: Boolean = true,
    val strength: Float = 1f,
    val period: Float = if (kind == VrStrobePatternKind.RAY) 10f else 10f,
    val speed: Float = 2f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
    val distortFreq: Float = 1f,
    val distortAmp: Float = 0f,
    val distortSpeed: Float = 1f,
    val distMultPar: Float = 1f,
    val distMultOrth: Float = 1f,
    val waveFreq: Float = 2f,
    val waveAmp: Float = 0f,
    val waveShape: Float = 0f,
    val angle: Float = 0f,
    val rotationPivotX: Float = 0f,
    val rotationPivotY: Float = 0f,
    val rotationSpeed: Float = 0f,
    val extent: Float = 0f,
    val noiseMove: Float = 0f,
    val perlinScale: Float = 5f,
    val perlinZSpeed: Float = 1f,
    val perlinZOffset: Float = 0f,
) {
  fun sanitized(): VrStrobePattern =
      copy(
          strength = strength.coerceIn(-2f, 2f),
          period =
              period.coerceIn(
                  if (kind == VrStrobePatternKind.RAY) 1f else 0.1f,
                  50f,
              ),
          speed = speed.coerceIn(-10f, 10f),
          pivotX = pivotX.coerceIn(-2f, 2f),
          pivotY = pivotY.coerceIn(-2f, 2f),
          distortFreq = distortFreq.coerceIn(0f, 20f),
          distortAmp = distortAmp.coerceIn(0f, 5f),
          distortSpeed = distortSpeed.coerceIn(-10f, 10f),
          distMultPar = distMultPar.coerceIn(0f, 5f),
          distMultOrth = distMultOrth.coerceIn(0f, 5f),
          waveFreq = waveFreq.coerceIn(0f, 20f),
          waveAmp = waveAmp.coerceIn(0f, 5f),
          waveShape = waveShape.coerceIn(0f, 1f),
          angle = angle.coerceIn(0f, 6.28f),
          rotationPivotX = rotationPivotX.coerceIn(-2f, 2f),
          rotationPivotY = rotationPivotY.coerceIn(-2f, 2f),
          rotationSpeed = rotationSpeed.coerceIn(-2f, 2f),
          extent = extent.coerceIn(0f, 20f),
          noiseMove = noiseMove.coerceIn(0f, 2f),
          perlinScale = perlinScale.coerceIn(0.1f, 50f),
          perlinZSpeed = perlinZSpeed.coerceIn(-10f, 10f),
          perlinZOffset = perlinZOffset.coerceIn(-100f, 100f),
      )
}

internal data class VrStrobeInterferenceProfile(
    val id: String = "interference-designer",
    val title: String = "Interference designer",
    val sourceLabel: String? = null,
    val sourcePayload: String? = null,
    val durationSeconds: Float = VR_STROBE_DEFAULT_DURATION_SECONDS,
    val colorCount: Int = 2,
    val color1: VrStrobeColor = VrStrobeColor.BLACK,
    val color2: VrStrobeColor = VrStrobeColor.RED,
    val color3: VrStrobeColor = VrStrobeColor.of("#00aaff"),
    val oscillatorActive: Boolean = false,
    val oscillatorFrequencyHz: Float = 0.5f,
    val oscillatorShape: Float = 1f,
    val scale: Float = 2f,
    val shearX: Float = 0f,
    val shearY: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val shakeAmplitude: Float = 0f,
    val shakeFrequencyHz: Float = 5f,
    val rotationSpeed: Float = 0f,
    val stepFactor: Float = 0f,
    val trailAmount: Float = 0f,
    val blurRadius: Float = 0f,
    val glowStrength: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val noiseFrequency: Float = 1f,
    val noiseStrength: Float = 0f,
    val noiseBias: Float = 0.5f,
    val vignetteCenter: Float = 0f,
    val vignetteEdge: Float = 2f,
    val vignetteBias: Float = 0f,
    val patterns: List<VrStrobePattern> =
        listOf(
            VrStrobePattern(VrStrobePatternKind.STRIPE),
            VrStrobePattern(VrStrobePatternKind.RIPPLE),
        ),
) {
  fun sanitized(): VrStrobeInterferenceProfile =
      copy(
          durationSeconds =
              durationSeconds.coerceIn(
                  VR_STROBE_MIN_DURATION_SECONDS,
                  VR_STROBE_MAX_DURATION_SECONDS,
              ),
          colorCount = colorCount.coerceIn(2, 3),
          oscillatorFrequencyHz = oscillatorFrequencyHz.coerceIn(0f, 40f),
          oscillatorShape = oscillatorShape.coerceIn(0.1f, 10f),
          scale = scale.coerceIn(0.1f, 100f),
          shearX = shearX.coerceIn(-2f, 2f),
          shearY = shearY.coerceIn(-2f, 2f),
          offsetX = offsetX.coerceIn(-1f, 1f),
          offsetY = offsetY.coerceIn(-1f, 1f),
          shakeAmplitude = shakeAmplitude.coerceIn(0f, 0.1f),
          shakeFrequencyHz = shakeFrequencyHz.coerceIn(0f, 40f),
          rotationSpeed = rotationSpeed.coerceIn(-5f, 5f),
          stepFactor = stepFactor.coerceIn(0f, 1f),
          trailAmount = trailAmount.coerceIn(0f, 0.99f),
          blurRadius = blurRadius.coerceIn(0f, 15f),
          glowStrength = glowStrength.coerceIn(0f, 3f),
          brightness = brightness.coerceIn(-1f, 1f),
          contrast = contrast.coerceIn(0f, 3f),
          noiseFrequency = noiseFrequency.coerceIn(0.1f, 5f),
          noiseStrength = noiseStrength.coerceIn(0f, 1f),
          noiseBias = noiseBias.coerceIn(0f, 1f),
          vignetteCenter = vignetteCenter.coerceIn(0f, 5f),
          vignetteEdge = vignetteEdge.coerceIn(0f, 5f),
          vignetteBias = vignetteBias.coerceIn(0f, 1f),
          patterns =
              patterns
                  .groupBy { it.kind }
                  .flatMap { (_, values) ->
                    values.take(VR_STROBE_MAX_PATTERN_INSTANCES).map(VrStrobePattern::sanitized)
                  },
      )

  fun patterns(kind: VrStrobePatternKind): List<VrStrobePattern> =
      patterns.filter { it.kind == kind }.take(VR_STROBE_MAX_PATTERN_INSTANCES)
}

internal enum class VrStrobeNoiseType { WHITE, PERLIN }

internal data class VrStrobeTemporalProfile(
    val id: String = "temporal-designer",
    val title: String = "Strobe designer",
    val sourceLabel: String? = null,
    val durationSeconds: Float = VR_STROBE_DEFAULT_DURATION_SECONDS,
    val color1: VrStrobeColor = VrStrobeColor.BLACK,
    val color2: VrStrobeColor = VrStrobeColor.WHITE,
    val frequencyHz: Float = 7f,
    val dutyPercent: Float = 50f,
    val noiseType: VrStrobeNoiseType = VrStrobeNoiseType.WHITE,
    val noiseResolution: Int = 1,
    val noisePhase1: Boolean = false,
    val noiseAmplitude1: Float = 0.2f,
    val noisePhase2: Boolean = false,
    val noiseAmplitude2: Float = 0.2f,
    val fixationEnabled: Boolean = false,
    val fixationColor: VrStrobeColor = VrStrobeColor.RED,
    val fixationSize: Int = 15,
) {
  fun sanitized(): VrStrobeTemporalProfile =
      copy(
          durationSeconds =
              durationSeconds.coerceIn(
                  VR_STROBE_MIN_DURATION_SECONDS,
                  VR_STROBE_MAX_DURATION_SECONDS,
              ),
          frequencyHz = frequencyHz.coerceIn(0.1f, 120f),
          dutyPercent = dutyPercent.coerceIn(1f, 99f),
          noiseResolution = noiseResolution.coerceIn(1, 50),
          noiseAmplitude1 = noiseAmplitude1.coerceIn(0f, 1f),
          noiseAmplitude2 = noiseAmplitude2.coerceIn(0f, 1f),
          fixationSize = fixationSize.coerceIn(2, 100),
      )
}
