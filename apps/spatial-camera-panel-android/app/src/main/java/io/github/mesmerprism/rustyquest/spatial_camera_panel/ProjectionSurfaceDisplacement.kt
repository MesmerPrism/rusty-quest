package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.Locale

internal data class ProjectionSurfaceDisplacement(
    val enabled: Boolean = false,
    val maxDisplacementMeters: Float = 0.0f,
    val referenceSurfaceDistanceMeters: Float = 2.0f,
    val polarity: Float = 1.0f,
    val edgeTaper: Float = 0.12f,
)

internal object ProjectionSurfaceDisplacementControls {
  val off = ProjectionSurfaceDisplacement()

  val gentle =
      ProjectionSurfaceDisplacement(
          enabled = true,
          maxDisplacementMeters = 0.06f,
          referenceSurfaceDistanceMeters = 2.0f,
          polarity = 1.0f,
          edgeTaper = 0.14f,
      )

  val deep =
      ProjectionSurfaceDisplacement(
          enabled = true,
          maxDisplacementMeters = 0.18f,
          referenceSurfaceDistanceMeters = 2.0f,
          polarity = 1.0f,
          edgeTaper = 0.18f,
      )

  fun presetToken(value: ProjectionSurfaceDisplacement): String {
    val normalized = ProjectionSurfaceDisplacementModule.normalize(value)
    return when {
      !normalized.enabled -> "off"
      normalized.maxDisplacementMeters >= 0.12f -> "deep"
      else -> "gentle"
    }
  }
}

internal object ProjectionSurfaceDisplacementModule {
  const val MODULE_ID = "projection-surface-displacement"
  const val CONTRACT_ID = "rusty.quest.projection-surface-displacement.v1"
  const val GRID_RESOLUTION = 32
  const val GRID_VERTEX_COUNT = GRID_RESOLUTION * GRID_RESOLUTION * 6

  fun normalize(requested: ProjectionSurfaceDisplacement): ProjectionSurfaceDisplacement {
    val enabled = requested.enabled
    return ProjectionSurfaceDisplacement(
        enabled = enabled,
        maxDisplacementMeters =
            if (enabled) {
              finiteOr(requested.maxDisplacementMeters, 0.0f).coerceIn(0.0f, 0.35f)
            } else {
              0.0f
            },
        referenceSurfaceDistanceMeters =
            finiteOr(requested.referenceSurfaceDistanceMeters, 2.0f).coerceIn(1.0f, 4.0f),
        polarity =
            finiteOr(requested.polarity, 1.0f)
                .coerceIn(-1.0f, 1.0f)
                .let { if (kotlin.math.abs(it) < 0.001f) 1.0f else it },
        edgeTaper = finiteOr(requested.edgeTaper, 0.12f).coerceIn(0.02f, 0.45f),
    )
  }

  fun markerFields(configuration: ProjectionSurfaceDisplacement): String {
    val value = normalize(configuration)
    return "projectionSurfaceDisplacementContract=$CONTRACT_ID " +
        "projectionSurfaceDisplacementRequested=${value.enabled && value.maxDisplacementMeters > 0.0001f} " +
        "projectionSurfaceDisplacementPreset=${ProjectionSurfaceDisplacementControls.presetToken(value)} " +
        "projectionSurfaceDisplacementMaxMeters=${markerFloat(value.maxDisplacementMeters)} " +
        "projectionSurfaceReferenceDistanceMeters=${markerFloat(value.referenceSurfaceDistanceMeters)} " +
        "projectionSurfacePolarity=${markerFloat(value.polarity)} " +
        "projectionSurfaceEdgeTaper=${markerFloat(value.edgeTaper)} " +
        "projectionSurfaceGrid=${GRID_RESOLUTION}x$GRID_RESOLUTION " +
        "projectionSurfaceVertexCount=$GRID_VERTEX_COUNT"
  }

  private fun finiteOr(value: Float, fallback: Float): Float =
      if (value.isFinite()) value else fallback

  private fun markerFloat(value: Float): String = String.format(Locale.US, "%.5f", value)
}
