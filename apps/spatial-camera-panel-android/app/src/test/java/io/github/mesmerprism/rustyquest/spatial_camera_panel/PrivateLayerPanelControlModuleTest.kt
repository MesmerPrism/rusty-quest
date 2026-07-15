package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateLayerPanelControlModuleTest {
  @Test
  fun stereoDepthAndMetadataAlignmentAreDefaults() {
    val alignment = PrivateLayerDepthAlignment()

    assertEquals(PrivateLayerControls.depthPolicyEyeIndex, PrivateLayerControls.defaultDepthLayerPolicy)
    assertEquals("Stereo (per eye)", PrivateLayerControls.labelForDepthLayerPolicy(2))
    assertEquals("eye-index", PrivateLayerControls.tokenForDepthLayerPolicy(2))
    assertTrue(alignment.metadataAutoAlign)
    assertEquals(1.0f, alignment.sampleScale)
    assertEquals(1.0f, alignment.sampleScaleY)
    assertEquals(0.0f, alignment.rollDegrees)
  }

  @Test
  fun alignmentFineTuneValuesAreClampedWithoutChangingAutoChoice() {
    val coerced =
        PrivateLayerPanelControlModule.coerceDepthAlignment(
            PrivateLayerDepthAlignment(
                leftX = -1.0f,
                leftY = 1.0f,
                rightX = 2.0f,
                rightY = -2.0f,
                sampleScale = 0.0f,
                sampleScaleY = 8.0f,
                rollDegrees = 90.0f,
                metadataAutoAlign = false,
            )
        )

    assertEquals(-0.25f, coerced.leftX)
    assertEquals(0.25f, coerced.leftY)
    assertEquals(0.25f, coerced.rightX)
    assertEquals(-0.25f, coerced.rightY)
    assertEquals(0.25f, coerced.sampleScale)
    assertEquals(3.0f, coerced.sampleScaleY)
    assertEquals(15.0f, coerced.rollDegrees)
    assertFalse(coerced.metadataAutoAlign)
  }

  @Test
  fun alignmentReceiptIncludesMetadataAndResidualFields() {
    val marker =
        PrivateLayerPanelControlModule.depthAlignmentSubmittedMarker(
            source = "test",
            updateMask = 1L,
            previousAlignment = PrivateLayerDepthAlignment(),
            updatedAlignment =
                PrivateLayerDepthAlignment(
                    sampleScale = 1.1f,
                    sampleScaleY = 0.9f,
                    rollDegrees = 2.0f,
                    metadataAutoAlign = false,
                ),
        )

    assertTrue(marker.contains("publicMultiStackDepthAlignmentSampleScale=1.1000"))
    assertTrue(marker.contains("publicMultiStackDepthAlignmentSampleScaleY=0.9000"))
    assertTrue(marker.contains("publicMultiStackDepthAlignmentRollDegrees=2.0000"))
    assertTrue(marker.contains("publicMultiStackDepthMetadataAutoAlignRequested=false"))
  }
}
