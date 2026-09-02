package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.Locale

internal data class ProjectionSurfaceTiling(
    val enabled: Boolean = false,
    val topology: Int = ProjectionSurfaceTilingControls.topologyContinuous,
    val gapNormalized: Float = 0.0f,
    val depthFlexibility: Float = 1.0f,
    val scope: Int = ProjectionSurfaceTilingControls.scopeInnerAndBuffer,
)

internal object ProjectionSurfaceTilingControls {
  const val topologyContinuous = 0
  const val topologyTiled = 1
  const val topologyTriangleTiles = 2

  const val scopeInnerAndBuffer = 0
  const val scopeCoreAndStretch = scopeInnerAndBuffer
  const val scopeCoreOnly = 1

  val off = ProjectionSurfaceTiling()

  fun topologyToken(topology: Int): String =
      when (topology) {
        topologyTiled -> "tiled"
        topologyTriangleTiles -> "triangle-tiles"
        else -> "continuous"
      }

  fun scopeToken(scope: Int): String =
      when (scope) {
        scopeCoreOnly -> "core-only"
        else -> "inner-and-buffer"
      }
}

internal object ProjectionSurfaceTilingModule {
  const val MODULE_ID = "projection-surface-tiling"
  const val CONTRACT_ID = "rusty.quest.projection-surface-tiling.v1"

  fun normalize(requested: ProjectionSurfaceTiling): ProjectionSurfaceTiling {
    if (!requested.enabled) {
      return ProjectionSurfaceTilingControls.off
    }
    return ProjectionSurfaceTiling(
        enabled = true,
        topology =
            when (requested.topology) {
              ProjectionSurfaceTilingControls.topologyTiled ->
                  ProjectionSurfaceTilingControls.topologyTiled
              ProjectionSurfaceTilingControls.topologyTriangleTiles ->
                  ProjectionSurfaceTilingControls.topologyTriangleTiles
              else -> ProjectionSurfaceTilingControls.topologyContinuous
            },
        gapNormalized = finiteOr(requested.gapNormalized, 0.0f).coerceIn(0.0f, 0.45f),
        depthFlexibility =
            finiteOr(requested.depthFlexibility, 1.0f).coerceIn(0.0f, 1.0f),
        scope =
            when (requested.scope) {
              ProjectionSurfaceTilingControls.scopeCoreOnly ->
                  ProjectionSurfaceTilingControls.scopeCoreOnly
              else -> ProjectionSurfaceTilingControls.scopeInnerAndBuffer
            },
    )
  }

  fun requested(configuration: ProjectionSurfaceTiling): Boolean = normalize(configuration).enabled

  fun markerFields(
      configuration: ProjectionSurfaceTiling,
      supported: Boolean,
      effective: Boolean,
  ): String {
    val value = normalize(configuration)
    val isRequested = requested(value)
    val isEffective = isRequested && supported && effective
    return "projectionSurfaceTilingContract=$CONTRACT_ID " +
        "projectionSurfaceTilingRequested=$isRequested " +
        "projectionSurfaceTilingSupported=$supported " +
        "projectionSurfaceTilingEffective=$isEffective " +
        "projectionSurfaceTopology=${ProjectionSurfaceTilingControls.topologyToken(value.topology)} " +
        "projectionSurfaceTileGapNormalized=${markerFloat(value.gapNormalized)} " +
        "projectionSurfaceDepthFlexibility=${markerFloat(value.depthFlexibility)} " +
        "projectionSurfaceTilingScope=${ProjectionSurfaceTilingControls.scopeToken(value.scope)}"
  }

  private fun finiteOr(value: Float, fallback: Float): Float =
      if (value.isFinite()) value else fallback

  private fun markerFloat(value: Float): String = String.format(Locale.US, "%.5f", value)
}
