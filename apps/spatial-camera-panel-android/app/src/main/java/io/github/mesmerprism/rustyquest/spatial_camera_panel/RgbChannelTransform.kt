package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.Locale

internal data class RgbChannelParameters(
    val directionTurns: Float = 0.0f,
    val directionRateHz: Float = 0.0f,
    val displacementStrengthUv: Float = 0.0f,
    val imageScale: Float = 1.0f,
    val coverageScale: Float = 1.0f,
)

internal data class RgbChannelTransform(
    val mode: Int = RgbChannelTransformControls.modeBypass,
    val edgeMode: Int = RgbChannelTransformControls.edgeClamp,
    val red: RgbChannelParameters = RgbChannelParameters(),
    val green: RgbChannelParameters = RgbChannelParameters(),
    val blue: RgbChannelParameters = RgbChannelParameters(),
)

internal object RgbChannelTransformControls {
  const val modeBypass = 0
  const val modeIndependent = 1
  const val modeLinked = 2

  const val edgeClamp = 0
  const val edgeMirror = 1
  const val edgeFade = 2

  val bypass = RgbChannelTransform()

  val linked =
      RgbChannelTransform(
          mode = modeLinked,
          red =
              RgbChannelParameters(
                  directionTurns = 0.125f,
                  directionRateHz = 0.125f,
                  displacementStrengthUv = 0.018f,
              ),
      )

  val independent =
      RgbChannelTransform(
          mode = modeIndependent,
          edgeMode = edgeMirror,
          red =
              RgbChannelParameters(
                  directionTurns = 0.0f,
                  directionRateHz = 0.11f,
                  displacementStrengthUv = 0.018f,
                  imageScale = 1.0f,
                  coverageScale = 1.0f,
              ),
          green =
              RgbChannelParameters(
                  directionTurns = 0.333333f,
                  directionRateHz = 0.17f,
                  displacementStrengthUv = 0.014f,
                  imageScale = 1.05f,
                  coverageScale = 0.92f,
              ),
          blue =
              RgbChannelParameters(
                  directionTurns = 0.666667f,
                  directionRateHz = -0.13f,
                  displacementStrengthUv = 0.022f,
                  imageScale = 0.95f,
                  coverageScale = 0.84f,
              ),
      )

  fun modeToken(mode: Int): String =
      when (mode) {
        modeIndependent -> "independent-rgb"
        modeLinked -> "linked-rgb"
        else -> "bypass"
      }

  fun edgeToken(mode: Int): String =
      when (mode) {
        edgeMirror -> "mirror"
        edgeFade -> "fade"
        else -> "clamp"
      }
}

internal object RgbChannelTransformModule {
  const val MODULE_ID = "rgb-channel-transform"
  const val CONTRACT_ID = "rusty.quest.rgb-channel-transform.v1"

  fun normalize(requested: RgbChannelTransform): RgbChannelTransform {
    val mode =
        when (requested.mode) {
          RgbChannelTransformControls.modeIndependent ->
              RgbChannelTransformControls.modeIndependent
          RgbChannelTransformControls.modeLinked -> RgbChannelTransformControls.modeLinked
          else -> RgbChannelTransformControls.modeBypass
        }
    val edgeMode =
        when (requested.edgeMode) {
          RgbChannelTransformControls.edgeMirror -> RgbChannelTransformControls.edgeMirror
          RgbChannelTransformControls.edgeFade -> RgbChannelTransformControls.edgeFade
          else -> RgbChannelTransformControls.edgeClamp
        }
    val red = normalizeChannel(requested.red)
    val green = normalizeChannel(requested.green)
    val blue = normalizeChannel(requested.blue)
    return if (mode == RgbChannelTransformControls.modeLinked) {
      RgbChannelTransform(mode = mode, edgeMode = edgeMode, red = red, green = red, blue = red)
    } else {
      RgbChannelTransform(
          mode = mode,
          edgeMode = edgeMode,
          red = red,
          green = green,
          blue = blue,
      )
    }
  }

  fun markerFields(configuration: RgbChannelTransform): String {
    val value = normalize(configuration)
    return "rgbChannelTransformContract=$CONTRACT_ID " +
        "rgbChannelTransformMode=${RgbChannelTransformControls.modeToken(value.mode)} " +
        "rgbChannelTransformEdge=${RgbChannelTransformControls.edgeToken(value.edgeMode)} " +
        "rgbDirectionTurns=${markerFloat(value.red.directionTurns)},${markerFloat(value.green.directionTurns)},${markerFloat(value.blue.directionTurns)} " +
        "rgbDirectionRateHz=${markerFloat(value.red.directionRateHz)},${markerFloat(value.green.directionRateHz)},${markerFloat(value.blue.directionRateHz)} " +
        "rgbDisplacementStrengthUv=${markerFloat(value.red.displacementStrengthUv)},${markerFloat(value.green.displacementStrengthUv)},${markerFloat(value.blue.displacementStrengthUv)} " +
        "rgbImageScale=${markerFloat(value.red.imageScale)},${markerFloat(value.green.imageScale)},${markerFloat(value.blue.imageScale)} " +
        "rgbCoverageScale=${markerFloat(value.red.coverageScale)},${markerFloat(value.green.coverageScale)},${markerFloat(value.blue.coverageScale)}"
  }

  private fun normalizeChannel(requested: RgbChannelParameters): RgbChannelParameters =
      RgbChannelParameters(
          directionTurns = finiteOr(requested.directionTurns, 0.0f).mod(1.0f),
          directionRateHz = finiteOr(requested.directionRateHz, 0.0f).coerceIn(-2.0f, 2.0f),
          displacementStrengthUv =
              finiteOr(requested.displacementStrengthUv, 0.0f).coerceIn(0.0f, 0.08f),
          imageScale = finiteOr(requested.imageScale, 1.0f).coerceIn(0.5f, 2.0f),
          coverageScale = finiteOr(requested.coverageScale, 1.0f).coerceIn(0.5f, 1.0f),
      )

  private fun finiteOr(value: Float, fallback: Float): Float =
      if (value.isFinite()) value else fallback

  private fun markerFloat(value: Float): String = String.format(Locale.US, "%.5f", value)
}
