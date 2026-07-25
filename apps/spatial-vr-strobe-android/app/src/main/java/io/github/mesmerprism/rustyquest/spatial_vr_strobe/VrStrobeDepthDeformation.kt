package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import kotlin.math.round
import kotlin.math.sqrt

/** Experimental, interference-only vertex displacement state. */
internal data class VrStrobeDepthDeformationState(
    val enabled: Boolean = true,
    val maxDisplacementMeters: Float = VrStrobeDepthDeformationPolicy.DEFAULT_MAX_METERS,
) {
  fun sanitized(): VrStrobeDepthDeformationState =
      copy(
          maxDisplacementMeters =
              VrStrobeDepthDeformationPolicy.sanitizeMaxMeters(maxDisplacementMeters)
      )
}

internal object VrStrobeDepthDeformationPolicy {
  const val MIN_MAX_METERS = 0f
  const val MAX_MAX_METERS = VrStrobeCarrierGeometry.RADIUS_METERS * 0.5f
  const val DEFAULT_MAX_METERS = MAX_MAX_METERS
  const val UI_STEP_METERS = 0.001f

  // Vertex deformation intentionally carries less spatial detail than the
  // fragment image. This keeps the 32 x 96 mesh below its geometric Nyquist
  // limit while the unchanged fragment shader retains fine stimulus detail.
  const val MAX_SPATIAL_FREQUENCY = 12f
  const val MIN_SPATIAL_FREQUENCY_AT_MAX_DISPLACEMENT = 4f
  const val FULL_DETAIL_MAX_METERS = 0.05f

  fun sanitizeMaxMeters(value: Float): Float {
    if (!value.isFinite()) return DEFAULT_MAX_METERS
    val clamped = value.coerceIn(MIN_MAX_METERS, MAX_MAX_METERS)
    return round(clamped / UI_STEP_METERS) * UI_STEP_METERS
  }

  /**
   * Maps the interference palette coordinate to geometry: palette slot 1 is far, slot 2 is the
   * center of a three-color palette, and the final palette slot is near.
   */
  fun signedPaletteDisplacementMeters(paletteCoordinate: Float, maxMeters: Float): Float =
      sanitizeMaxMeters(maxMeters) * (paletteCoordinate.coerceIn(0f, 1f) * 2f - 1f)

  /**
   * Preserve the fragment shader's fine detail while smoothing only the depth carrier as very
   * large displacement would otherwise fold the mesh back over itself and multiply overdraw.
   */
  fun spatialFrequencyLimit(maxMeters: Float): Float {
    val amplitude = sanitizeMaxMeters(maxMeters)
    val denominator = (MAX_MAX_METERS - FULL_DETAIL_MAX_METERS).coerceAtLeast(UI_STEP_METERS)
    val normalized = ((amplitude - FULL_DETAIL_MAX_METERS) / denominator).coerceIn(0f, 1f)
    val smooth = normalized * normalized * (3f - 2f * normalized)
    return MAX_SPATIAL_FREQUENCY +
        (MIN_SPATIAL_FREQUENCY_AT_MAX_DISPLACEMENT - MAX_SPATIAL_FREQUENCY) * smooth
  }

  /** Quadratic UI mapping preserves fine control near zero across the 1.42 m range. */
  fun sliderPosition(maxMeters: Float): Float =
      sqrt(sanitizeMaxMeters(maxMeters) / MAX_MAX_METERS).coerceIn(0f, 1f)

  fun maxMetersFromSlider(position: Float): Float {
    val normalized = if (position.isFinite()) position.coerceIn(0f, 1f) else 0f
    return sanitizeMaxMeters(normalized * normalized * MAX_MAX_METERS)
  }
}
