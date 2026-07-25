package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLayerZoneCompositorTest {
  @Test
  fun normalizationClampsAllTransportRanges() {
    val normalized =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositor(
                coverageMode = 99,
                stretchSource = -4,
                stretchMapping = PrivateLayerZoneCompositorControls.mappingRectangularLinear,
                edgeInsetUv = 0.4f,
                maxInsetUv = 0.1f,
                innerSignal = 99,
                innerWidthUv = 2.0f,
                innerThresholdR = -1.0f,
                innerThresholdG = 2.0f,
                outerMotionGain = 2.0f,
            )
        )
    assertEquals(PrivateLayerZoneCompositorControls.coverageReplaceVideo, normalized.coverageMode)
    assertEquals(PrivateLayerZoneCompositorControls.sourceRaw, normalized.stretchSource)
    assertEquals(
        PrivateLayerZoneCompositorControls.mappingRectangularLinear,
        normalized.stretchMapping,
    )
    assertEquals(0.4f, normalized.maxInsetUv)
    assertEquals(PrivateLayerZoneCompositorControls.signalDifference, normalized.innerSignal)
    assertEquals(0.25f, normalized.innerWidthUv)
    assertEquals(0.0f, normalized.innerThresholdR)
    assertEquals(1.0f, normalized.innerThresholdG)
    assertEquals(0.5f, normalized.outerMotionGain)
  }

  @Test
  fun presetsKeepRollbackAndCoverageModesIndependent() {
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageOff,
        PrivateLayerZoneCompositorControls.legacyOff.coverageMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageDynamicBuffer,
        PrivateLayerZoneCompositorControls.nativeBuffer.coverageMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.mappingMirroredLens,
        PrivateLayerZoneCompositorControls.nativeBuffer.stretchMapping,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.mappingRectangularLinear,
        PrivateLayerZoneCompositorControls.linearBuffer.stretchMapping,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageReplaceVideo,
        PrivateLayerZoneCompositorControls.fullStretch.coverageMode,
    )
    assertTrue(PrivateLayerZoneCompositorControls.organicBuffer.innerCycleAmplitude > 0.0f)
    assertTrue(PrivateLayerZoneCompositorControls.organicBuffer.outerCycleAmplitude > 0.0f)
  }

  @Test
  fun markerDocumentsScaleAndDynamicGuardOrder() {
    val marker =
        PrivateLayerZoneCompositorModule.markerFields(
            PrivateLayerZoneCompositorControls.organicBuffer
        )
    assertTrue(marker.contains("projectionZoneDynamicGuardAware=true"))
    assertTrue(marker.contains("projectionZoneProjectionScaleAware=true"))
    assertTrue(marker.contains("projectionZoneGeometryOrder=user-scale-then-dynamic-core"))
    assertTrue(marker.contains("projectionZoneStretchMapping=mirrored-lens-native"))
  }
}
