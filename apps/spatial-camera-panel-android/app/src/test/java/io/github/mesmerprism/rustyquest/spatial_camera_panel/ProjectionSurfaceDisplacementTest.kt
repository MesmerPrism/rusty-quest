package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionSurfaceDisplacementTest {
  @Test
  fun offIsExactIdentity() {
    val value =
        ProjectionSurfaceDisplacementModule.normalize(ProjectionSurfaceDisplacementControls.off)
    assertFalse(value.enabled)
    assertEquals(0.0f, value.maxDisplacementMeters)
    assertEquals("off", ProjectionSurfaceDisplacementControls.presetToken(value))
  }

  @Test
  fun presetsRemainBoundedAndOrdered() {
    val gentle =
        ProjectionSurfaceDisplacementModule.normalize(
            ProjectionSurfaceDisplacementControls.gentle
        )
    val deep =
        ProjectionSurfaceDisplacementModule.normalize(ProjectionSurfaceDisplacementControls.deep)
    assertTrue(gentle.enabled)
    assertTrue(deep.enabled)
    assertTrue(deep.maxDisplacementMeters > gentle.maxDisplacementMeters)
    assertEquals(6144, ProjectionSurfaceDisplacementModule.GRID_VERTEX_COUNT)
  }

  @Test
  fun damagedValuesFailClosedAndStayFinite() {
    val value =
        ProjectionSurfaceDisplacementModule.normalize(
            ProjectionSurfaceDisplacement(
                enabled = true,
                maxDisplacementMeters = Float.NaN,
                referenceSurfaceDistanceMeters = -2.0f,
                polarity = 0.0f,
                edgeTaper = 8.0f,
            )
        )
    assertEquals(0.0f, value.maxDisplacementMeters)
    assertEquals(1.0f, value.referenceSurfaceDistanceMeters)
    assertEquals(1.0f, value.polarity)
    assertEquals(0.45f, value.edgeTaper)
  }

  @Test
  fun markerUsesNeutralPublicVocabulary() {
    val fields =
        ProjectionSurfaceDisplacementModule.markerFields(
            ProjectionSurfaceDisplacementControls.gentle
        )
    assertTrue(
        fields.contains(
            "projectionSurfaceDisplacementContract=rusty.quest.projection-surface-displacement.v1"
        )
    )
    assertTrue(fields.contains("projectionSurfaceDisplacementPreset=gentle"))
  }
}
