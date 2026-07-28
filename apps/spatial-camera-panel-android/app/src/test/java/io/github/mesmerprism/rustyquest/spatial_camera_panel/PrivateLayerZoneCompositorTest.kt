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
                outerTargetMode = 99,
                stretchMapping = 1,
                edgeInsetUv = 0.4f,
                maxInsetUv = 0.1f,
                innerSignal = 99,
                innerWidthUv = 2.0f,
                innerThresholdR = -1.0f,
                innerThresholdG = 2.0f,
                outerMotionGain = 2.0f,
                innerChannelDynamics =
                    PrivateLayerZoneChannelDynamics(
                        applicationMode = 99,
                        sourceChoice = -3,
                        regionDriver = 99,
                        strengthR = -1.0f,
                        strengthG = 2.0f,
                        cycleAmplitudeB = 4.0f,
                        cycleHzR = 9.0f,
                        cyclePhaseG = -8.0f,
                    ),
            )
        )
    assertEquals(PrivateLayerZoneCompositorControls.coverageReplaceVideo, normalized.coverageMode)
    assertEquals(PrivateLayerZoneCompositorControls.sourceRaw, normalized.stretchSource)
    assertEquals(
        PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
        normalized.outerTargetMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.mappingGradedEdgeTrail,
        normalized.stretchMapping,
    )
    assertEquals(0.015f, normalized.edgeInsetUv)
    assertEquals(0.14f, normalized.maxInsetUv)
    assertEquals(1.6f, normalized.stretchCurve)
    assertEquals(PrivateLayerZoneCompositorControls.signalDifference, normalized.innerSignal)
    assertEquals(0.25f, normalized.innerWidthUv)
    assertEquals(0.0f, normalized.innerThresholdR)
    assertEquals(1.0f, normalized.innerThresholdG)
    assertEquals(0.5f, normalized.outerMotionGain)
    assertEquals(
        PrivateLayerZoneCompositorControls.applicationRegion,
        normalized.innerChannelDynamics.applicationMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.blendSourceOutgoing,
        normalized.innerChannelDynamics.sourceChoice,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.regionDriverMax,
        normalized.innerChannelDynamics.regionDriver,
    )
    assertEquals(0.0f, normalized.innerChannelDynamics.strengthR)
    assertEquals(1.0f, normalized.innerChannelDynamics.strengthG)
    assertEquals(0.5f, normalized.innerChannelDynamics.cycleAmplitudeB)
    assertEquals(4.0f, normalized.innerChannelDynamics.cycleHzR)
    assertEquals(-4.0f, normalized.innerChannelDynamics.cyclePhaseG)
    assertTrue(normalized.projectionEffectEdgeGuardEnabled)
  }

  @Test
  fun presetsKeepLensOnlyMappingAndCoverageModesIndependent() {
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageOff,
        PrivateLayerZoneCompositorControls.legacyOff.coverageMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageDynamicBuffer,
        PrivateLayerZoneCompositorControls.nativeBuffer.coverageMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.mappingGradedEdgeTrail,
        PrivateLayerZoneCompositorControls.nativeBuffer.stretchMapping,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageReplaceVideo,
        PrivateLayerZoneCompositorControls.fullStretch.coverageMode,
    )
    assertTrue(PrivateLayerZoneCompositorControls.organicBuffer.innerCycleAmplitude > 0.0f)
    assertTrue(PrivateLayerZoneCompositorControls.organicBuffer.outerCycleAmplitude > 0.0f)
    assertEquals(
        PrivateLayerZoneCompositorControls.applicationLegacy,
        PrivateLayerZoneCompositorControls.organicBuffer.innerChannelDynamics.applicationMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.applicationComponent,
        PrivateLayerZoneCompositorControls.componentBlendTest.innerChannelDynamics.applicationMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.debugRegions,
        PrivateLayerZoneCompositorControls.componentBlendTest.debugMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.outerTargetReadableColor,
        PrivateLayerZoneCompositorControls.componentBlendTest.outerTargetMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.blendSourceOutgoing,
        PrivateLayerZoneCompositorControls.componentBlendTest.innerChannelDynamics.sourceChoice,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.blendSourceIncoming,
        PrivateLayerZoneCompositorControls.componentBlendTest.outerChannelDynamics.sourceChoice,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.applicationRegion,
        PrivateLayerZoneCompositorControls.regionBlendTest.outerChannelDynamics.applicationMode,
    )
    assertTrue(
        PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(
            PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest
        )
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.blendSourceOutgoing,
        PrivateLayerZoneCompositorControls
            .spatialVideoUnderlayBlendTest
            .outerChannelDynamics
            .sourceChoice,
    )
  }

  @Test
  fun unsampledSpatialVideoRouteFailsClosedInsteadOfSubstitutingAReadableSource() {
    val invalid =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest.copy(
                outerChannelDynamics =
                    PrivateLayerZoneCompositorControls
                        .spatialVideoUnderlayBlendTest
                        .outerChannelDynamics
                        .copy(
                            applicationMode = PrivateLayerZoneCompositorControls.applicationComponent,
                            sourceChoice = PrivateLayerZoneCompositorControls.blendSourceIncoming,
                        )
            )
        )
    assertEquals(
        PrivateLayerZoneCompositorControls.applicationComponent,
        invalid.outerChannelDynamics.applicationMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.blendSourceIncoming,
        invalid.outerChannelDynamics.sourceChoice,
    )
    assertTrue(
        !PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(invalid)
    )
    val marker = PrivateLayerZoneCompositorModule.markerFields(invalid)
    assertTrue(marker.contains("projectionZoneOuterTarget=transparent-spatial-video"))
    assertTrue(marker.contains("projectionZoneOuterUnderlaySupported=false"))
    assertTrue(marker.contains("projectionZoneUnsampledOuterData=true"))
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
    assertTrue(marker.contains("projectionZoneStretchMapping=graded-edge-trail-native"))
    assertTrue(marker.contains("projectionZoneEffectEdgeGuardEnabled=true"))
    assertTrue(marker.contains("projectionZoneInnerApplication=legacy"))
    assertTrue(marker.contains("projectionZoneInnerColorSource=midpoint"))
    assertTrue(marker.contains("projectionZoneOuterTarget=readable-color"))
  }
}
