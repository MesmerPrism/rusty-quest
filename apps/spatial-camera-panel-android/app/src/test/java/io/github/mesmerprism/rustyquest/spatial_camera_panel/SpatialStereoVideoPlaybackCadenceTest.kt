package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialStereoVideoPlaybackCadenceTest {
  @Test
  fun thirtyOnThirtyKeepsEveryFrameAndThirtyOnSixtyKeepsAlternateOutputs() {
    assertSequenceRendered(fps = 30, sourceStepUs = 33_333L, expectedRendered = 6)
    assertSequenceRendered(fps = 30, sourceStepUs = 16_667L, expectedRendered = 3)
  }

  @Test
  fun sixtyOnSixtyAndSourceModeDoNotIntentionallyDropOutputs() {
    assertSequenceRendered(fps = 60, sourceStepUs = 16_667L, expectedRendered = 6)
    assertSequenceRendered(fps = 0, sourceStepUs = 33_333L, expectedRendered = 6)
    assertSequenceRendered(fps = 0, sourceStepUs = 16_667L, expectedRendered = 6)
    assertTrue(SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(100L, 90L, 0))
  }

  @Test
  fun sourceRateCeilingFailsTruthfullyAndMarkersNameRequestedAndEffectiveModes() {
    assertFalse(SpatialStereoVideoPlayback.sourceRateSupported(-1.0))
    assertTrue(SpatialStereoVideoPlayback.sourceRateSupported(30.0))
    assertTrue(SpatialStereoVideoPlayback.sourceRateSupported(90.0))
    assertFalse(SpatialStereoVideoPlayback.sourceRateSupported(90.01))

    val source = SpatialStereoVideoPlayback.codecOutputCadenceMarker(12, 12, 0, 0, 60.0, true)
    assertTrue(source.contains("requestedMode=source"))
    assertTrue(source.contains("effectiveMode=source"))
    assertTrue(source.contains("surfaceGateEnabled=false"))
    assertTrue(source.contains("nativeFallbackFps=90"))

    val capped = SpatialStereoVideoPlayback.codecOutputCadenceMarker(12, 6, 6, 30, 60.0, true)
    assertTrue(capped.contains("requestedMode=30"))
    assertTrue(capped.contains("effectiveMode=capped-30"))
    assertTrue(capped.contains("effectiveRateFps=30.000"))

    val unsupported = SpatialStereoVideoPlayback.codecOutputCadenceUnsupportedMarker(120.0)
    assertTrue(unsupported.contains("effectiveMode=unsupported"))
    assertTrue(unsupported.contains("sourceRateCeilingFps=90"))
  }

  private fun assertSequenceRendered(fps: Int, sourceStepUs: Long, expectedRendered: Int) {
    var lastRendered = -1L
    var rendered = 0
    repeat(6) { index ->
      val presentation = sourceStepUs * index
      if (SpatialStereoVideoPlayback.shouldRenderSurfaceOutput(lastRendered, presentation, fps)) {
        rendered += 1
        lastRendered = presentation
      }
    }
    kotlin.test.assertEquals(expectedRendered, rendered)
  }
}
