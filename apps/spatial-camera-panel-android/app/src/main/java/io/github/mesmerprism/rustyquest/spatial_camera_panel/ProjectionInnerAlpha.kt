package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.Locale

internal data class ProjectionInnerAlpha(
    val enabled: Boolean = false,
    val driver: Int = ProjectionInnerAlphaControls.driverLuma,
    val threshold: Float = 0.5f,
    val softness: Float = 0.1f,
    val amount: Float = 0.0f,
    val invert: Boolean = false,
    val stretchPolicy: Int = ProjectionInnerAlphaControls.stretchFollowProjection,
    val stretchObeysExactProjectionMask: Boolean = false,
)

internal object ProjectionInnerAlphaControls {
  const val driverRed = 0
  const val driverGreen = 1
  const val driverBlue = 2
  const val driverLuma = 3
  const val driverMax = 4

  const val stretchFollowProjection = 0
  const val stretchOpaqueIndependent = 1

  val off = ProjectionInnerAlpha()

  fun driverToken(driver: Int): String =
      when (driver) {
        driverRed -> "red"
        driverGreen -> "green"
        driverBlue -> "blue"
        driverMax -> "max"
        else -> "luma"
      }

  fun stretchPolicyToken(policy: Int): String =
      when (policy) {
        stretchOpaqueIndependent -> "opaque-independent"
        else -> "follow-projection"
      }
}

internal object ProjectionInnerAlphaModule {
  const val MODULE_ID = "projection-inner-alpha"
  const val CONTRACT_ID = "rusty.quest.projection-inner-alpha.v1"
  const val INPUT_TOKEN = "processed-core"

  fun normalize(requested: ProjectionInnerAlpha): ProjectionInnerAlpha {
    if (!requested.enabled) {
      return ProjectionInnerAlphaControls.off
    }
    return ProjectionInnerAlpha(
        enabled = true,
        driver =
            when (requested.driver) {
              ProjectionInnerAlphaControls.driverRed -> ProjectionInnerAlphaControls.driverRed
              ProjectionInnerAlphaControls.driverGreen -> ProjectionInnerAlphaControls.driverGreen
              ProjectionInnerAlphaControls.driverBlue -> ProjectionInnerAlphaControls.driverBlue
              ProjectionInnerAlphaControls.driverMax -> ProjectionInnerAlphaControls.driverMax
              else -> ProjectionInnerAlphaControls.driverLuma
            },
        threshold = finiteOr(requested.threshold, 0.5f).coerceIn(0.0f, 1.0f),
        softness = finiteOr(requested.softness, 0.1f).coerceIn(0.001f, 0.5f),
        amount = finiteOr(requested.amount, 0.0f).coerceIn(0.0f, 1.0f),
        invert = requested.invert,
        stretchPolicy =
            when (requested.stretchPolicy) {
              ProjectionInnerAlphaControls.stretchOpaqueIndependent ->
                  ProjectionInnerAlphaControls.stretchOpaqueIndependent
              else -> ProjectionInnerAlphaControls.stretchFollowProjection
            },
        stretchObeysExactProjectionMask = requested.stretchObeysExactProjectionMask,
    )
  }

  fun requested(configuration: ProjectionInnerAlpha): Boolean {
    val value = normalize(configuration)
    return value.enabled && value.amount > 0.0001f
  }

  fun markerFields(
      configuration: ProjectionInnerAlpha,
      supported: Boolean,
      effective: Boolean,
  ): String {
    val value = normalize(configuration)
    val isRequested = requested(value)
    val isEffective = isRequested && supported && effective
    return "projectionInnerAlphaContract=$CONTRACT_ID " +
        "projectionInnerAlphaInput=$INPUT_TOKEN " +
        "projectionInnerAlphaRequested=$isRequested " +
        "projectionInnerAlphaSupported=$supported " +
        "projectionInnerAlphaEffective=$isEffective " +
        "projectionInnerAlphaDriver=${ProjectionInnerAlphaControls.driverToken(value.driver)} " +
        "projectionInnerAlphaThreshold=${markerFloat(value.threshold)} " +
        "projectionInnerAlphaSoftness=${markerFloat(value.softness)} " +
        "projectionInnerAlphaAmount=${markerFloat(value.amount)} " +
        "projectionInnerAlphaInvert=${value.invert} " +
        "projectionInnerAlphaStretchPolicy=${ProjectionInnerAlphaControls.stretchPolicyToken(value.stretchPolicy)} " +
        "projectionInnerAlphaStretchObeysExactProjectionMask=${value.stretchObeysExactProjectionMask}"
  }

  private fun finiteOr(value: Float, fallback: Float): Float =
      if (value.isFinite()) value else fallback

  private fun markerFloat(value: Float): String = String.format(Locale.US, "%.5f", value)
}
