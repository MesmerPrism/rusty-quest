package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionSurfaceTilingTest {
  @Test
  fun defaultIsDisabledContinuousCompatibilityIdentity() {
    val value = ProjectionSurfaceTilingModule.normalize(ProjectionSurfaceTiling())
    assertEquals(ProjectionSurfaceTilingControls.off, value)
    assertEquals(
        ProjectionSurfaceTilingControls.topologyContinuous,
        value.topology,
    )
    assertEquals(0.0f, value.gapNormalized)
    assertEquals(1.0f, value.depthFlexibility)
    assertEquals(ProjectionSurfaceTilingControls.scopeCoreAndStretch, value.scope)
    assertFalse(ProjectionSurfaceTilingModule.requested(value))
  }

  @Test
  fun normalizeClampsValuesAndFallsBackToPublicTokens() {
    val value =
        ProjectionSurfaceTilingModule.normalize(
            ProjectionSurfaceTiling(
                enabled = true,
                topology = 99,
                gapNormalized = 2.0f,
                depthFlexibility = Float.NaN,
                scope = -4,
            )
        )
    assertEquals(ProjectionSurfaceTilingControls.topologyContinuous, value.topology)
    assertEquals("continuous", ProjectionSurfaceTilingControls.topologyToken(value.topology))
    assertEquals(0.45f, value.gapNormalized)
    assertEquals(1.0f, value.depthFlexibility)
    assertEquals(ProjectionSurfaceTilingControls.scopeCoreAndStretch, value.scope)
    assertEquals("inner-and-buffer", ProjectionSurfaceTilingControls.scopeToken(value.scope))
  }

  @Test
  fun enabledTiledRequestPreservesExactScopeAndLowerBounds() {
    val value =
        ProjectionSurfaceTilingModule.normalize(
            ProjectionSurfaceTiling(
                enabled = true,
                topology = ProjectionSurfaceTilingControls.topologyTiled,
                gapNormalized = -1.0f,
                depthFlexibility = -2.0f,
                scope = ProjectionSurfaceTilingControls.scopeCoreOnly,
            )
        )
    assertTrue(ProjectionSurfaceTilingModule.requested(value))
    assertEquals("tiled", ProjectionSurfaceTilingControls.topologyToken(value.topology))
    assertEquals(0.0f, value.gapNormalized)
    assertEquals(0.0f, value.depthFlexibility)
    assertEquals("core-only", ProjectionSurfaceTilingControls.scopeToken(value.scope))
  }

  @Test
  fun triangleTilesAreAnExplicitThirdTopologyWithoutChangingSquareTiles() {
    val triangleTiles =
        ProjectionSurfaceTilingModule.normalize(
            ProjectionSurfaceTiling(
                enabled = true,
                topology = ProjectionSurfaceTilingControls.topologyTriangleTiles,
                gapNormalized = 0.08f,
                depthFlexibility = 0.0f,
            )
        )
    assertEquals(
        ProjectionSurfaceTilingControls.topologyTriangleTiles,
        triangleTiles.topology,
    )
    assertEquals("triangle-tiles", ProjectionSurfaceTilingControls.topologyToken(triangleTiles.topology))

    val squareTiles =
        ProjectionSurfaceTilingModule.normalize(
            triangleTiles.copy(topology = ProjectionSurfaceTilingControls.topologyTiled)
        )
    assertEquals(ProjectionSurfaceTilingControls.topologyTiled, squareTiles.topology)
    assertEquals("tiled", ProjectionSurfaceTilingControls.topologyToken(squareTiles.topology))
  }

  @Test
  fun requestedSupportedAndEffectiveRemainSeparate() {
    val active =
        ProjectionSurfaceTiling(
            enabled = true,
            topology = ProjectionSurfaceTilingControls.topologyTiled,
        )
    val unsupported =
        ProjectionSurfaceTilingModule.markerFields(
            configuration = active,
            supported = false,
            effective = true,
        )
    assertTrue(unsupported.contains("projectionSurfaceTilingRequested=true"))
    assertTrue(unsupported.contains("projectionSurfaceTilingSupported=false"))
    assertTrue(unsupported.contains("projectionSurfaceTilingEffective=false"))

    val adopted =
        ProjectionSurfaceTilingModule.markerFields(
            configuration = active,
            supported = true,
            effective = true,
        )
    assertTrue(adopted.contains("projectionSurfaceTilingSupported=true"))
    assertTrue(adopted.contains("projectionSurfaceTilingEffective=true"))
  }
}
