package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLayerZoneCompositorTest {
  @Test
  fun normalizationClampsAllTransportRanges() {
    val normalized =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositor(
                coverageMode = 99,
                bufferGeometryMode = 99,
                bufferStaticWidthUv = 4.0f,
                bufferFillMode = 99,
                stretchExtentMode = 99,
                stretchSource = -4,
                outerTargetMode = 99,
                stretchMapping = 1,
                stretchOptionFlags = 99,
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
    assertEquals(
        PrivateLayerZoneCompositorControls.regionContractIndependent,
        normalized.regionContractVersion,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferGeometryDynamic,
        normalized.bufferGeometryMode,
    )
    assertEquals(0.2f, normalized.bufferStaticWidthUv)
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferFillStretch,
        normalized.bufferFillMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.stretchExtentReplaceOuter,
        normalized.stretchExtentMode,
    )
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
    assertEquals(1, normalized.stretchOptionFlags)
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
  fun productDefaultTokenSelectsTheThreeRegionVideoUnderlayPreset() {
    val preset =
        PrivateLayerZoneCompositorControls.presetForToken(
            "spatial-video-underlay"
        )

    assertEquals(
        PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest,
        preset,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.coverageDynamicBuffer,
        preset.coverageMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
        preset.outerTargetMode,
    )
    assertTrue(preset.innerCycleAmplitude > 0.0f)
    assertTrue(preset.outerCycleAmplitude > 0.0f)
    assertEquals(
        "spatial-video-underlay",
        PrivateLayerZoneCompositorControls.presetToken(preset),
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferOff,
        PrivateLayerZoneCompositorControls.presetForToken("unsupported"),
    )
  }

  @Test
  fun nativeAndOrganicStylesPreserveTransparentSpatialVideoUnderlay() {
    val current =
        PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest.copy(
            outerWidthUv = 0.09f,
        )

    listOf(
            PrivateLayerZoneCompositorControls.nativeBuffer,
            PrivateLayerZoneCompositorControls.organicBuffer,
        )
        .forEach { style ->
          val applied = PrivateLayerZoneCompositorControls.applyStretchStyle(current, style)
          assertEquals(
              PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
              applied.outerTargetMode,
          )
          assertTrue(
              PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(applied)
          )
          assertEquals(style.stretchSource, applied.stretchSource)
          assertTrue(PrivateLayerZoneCompositorControls.matchesStretchStyle(applied, style))
        }
  }

  @Test
  fun nativeAndOrganicStylesPreserveReadableSameLayerVideo() {
    val current =
        PrivateLayerZoneCompositorControls.nativeBuffer.copy(
            outerTargetMode = PrivateLayerZoneCompositorControls.outerTargetReadableColor,
        )

    listOf(
            PrivateLayerZoneCompositorControls.nativeBuffer,
            PrivateLayerZoneCompositorControls.organicBuffer,
        )
        .forEach { style ->
          val applied = PrivateLayerZoneCompositorControls.applyStretchStyle(current, style)
          assertEquals(
              PrivateLayerZoneCompositorControls.outerTargetReadableColor,
              applied.outerTargetMode,
          )
          assertTrue(
              !PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(applied)
          )
          assertEquals(style.stretchSource, applied.stretchSource)
          assertTrue(PrivateLayerZoneCompositorControls.matchesStretchStyle(applied, style))
        }
  }

  @Test
  fun disablingStretchPreservesDynamicBufferAndTransparentSpatialVideoUnderlay() {
    val current = PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest

    val disabled = PrivateLayerZoneCompositorControls.disableStretch(current)
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferGeometryDynamic,
        disabled.bufferGeometryMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferFillOuterContinuation,
        disabled.bufferFillMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
        disabled.outerTargetMode,
    )
    assertTrue(PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(disabled))

    listOf(
            PrivateLayerZoneCompositorControls.nativeBuffer,
            PrivateLayerZoneCompositorControls.organicBuffer,
        )
        .forEach { style ->
          val restored = PrivateLayerZoneCompositorControls.applyStretchStyle(disabled, style)
          assertEquals(
              PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
              restored.outerTargetMode,
          )
          assertTrue(
              PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(restored)
          )
        }
  }

  @Test
  fun disablingStretchPreservesExplicitReadableSameLayerVideoAndBufferGeometry() {
    val current =
        PrivateLayerZoneCompositorControls.organicBuffer.copy(
            outerTargetMode = PrivateLayerZoneCompositorControls.outerTargetReadableColor,
        )

    val disabled = PrivateLayerZoneCompositorControls.disableStretch(current)
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferGeometryDynamic,
        disabled.bufferGeometryMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferFillOuterContinuation,
        disabled.bufferFillMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.outerTargetReadableColor,
        disabled.outerTargetMode,
    )
    assertTrue(!PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(disabled))
  }

  @Test
  fun selectingTransparentUnderlayWhileStretchIsOffDoesNotEnableStretch() {
    val selected =
        PrivateLayerZoneCompositorControls.withOuterTarget(
            PrivateLayerZoneCompositorControls.legacyOff,
            PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
        )

    assertEquals(PrivateLayerZoneCompositorControls.coverageOff, selected.coverageMode)
    assertEquals(
        PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
        selected.outerTargetMode,
    )
    assertTrue(PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(selected))
  }

  @Test
  fun independentSpatialVideoRouteSupportsTransitionVariantsWithoutSamplingVideo() {
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
    assertTrue(PrivateLayerZoneCompositorControls.transparentSpatialVideoSupported(invalid))
    val marker = PrivateLayerZoneCompositorModule.markerFields(invalid)
    assertTrue(marker.contains("projectionZoneOuterTarget=transparent-spatial-video"))
    assertTrue(marker.contains("projectionZoneOuterUnderlaySupported=true"))
    assertTrue(marker.contains("projectionZoneUnsampledOuterData=true"))
  }

  @Test
  fun bufferGeometryFillAndStretchExtentRemainOrthogonal() {
    val base =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositorControls.organicBuffer.copy(
                bufferGeometryMode = PrivateLayerZoneCompositorControls.bufferGeometryStatic,
                bufferStaticWidthUv = 0.13f,
                bufferFillMode =
                    PrivateLayerZoneCompositorControls.bufferFillTransparentReveal,
                stretchExtentMode =
                    PrivateLayerZoneCompositorControls.stretchExtentReplaceOuter,
            )
        )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferGeometryStatic,
        base.bufferGeometryMode,
    )
    assertEquals(0.13f, base.bufferStaticWidthUv)
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferFillTransparentReveal,
        base.bufferFillMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.stretchExtentReplaceOuter,
        base.stretchExtentMode,
    )
    assertEquals(PrivateLayerZoneCompositorControls.coverageDynamicBuffer, base.coverageMode)

    val stretch =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositorControls.applyStretchStyle(
                base,
                PrivateLayerZoneCompositorControls.nativeBuffer,
            )
        )
    assertEquals(base.bufferGeometryMode, stretch.bufferGeometryMode)
    assertEquals(base.bufferStaticWidthUv, stretch.bufferStaticWidthUv)
    assertEquals(base.stretchExtentMode, stretch.stretchExtentMode)
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferFillStretch,
        stretch.bufferFillMode,
    )
    assertEquals(PrivateLayerZoneCompositorControls.coverageReplaceVideo, stretch.coverageMode)
  }

  @Test
  fun legacyCoverageProfilesMigrateToIndependentRegionContract() {
    val off =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositorControls.legacyOff
        )
    assertEquals(
        PrivateLayerZoneCompositorControls.regionContractIndependent,
        off.regionContractVersion,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferGeometryOff,
        off.bufferGeometryMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferFillOuterContinuation,
        off.bufferFillMode,
    )

    val full =
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositor(
                coverageMode = PrivateLayerZoneCompositorControls.coverageReplaceVideo,
                regionContractVersion = PrivateLayerZoneCompositorControls.regionContractLegacy,
            )
        )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferGeometryDynamic,
        full.bufferGeometryMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.bufferFillStretch,
        full.bufferFillMode,
    )
    assertEquals(
        PrivateLayerZoneCompositorControls.stretchExtentReplaceOuter,
        full.stretchExtentMode,
    )
  }

  @Test
  fun markerDocumentsScaleAndDynamicGuardOrder() {
    val marker =
        PrivateLayerZoneCompositorModule.markerFields(
            PrivateLayerZoneCompositorControls.organicBuffer
        )
    assertTrue(marker.contains("projectionZoneDynamicGuardAware=true"))
    assertTrue(marker.contains("projectionZoneProjectionScaleAware=true"))
    assertTrue(marker.contains("projectionZoneGeometryOrder=user-scale-then-guard-contraction"))
    assertTrue(marker.contains("projectionZoneStretchMapping=graded-edge-trail-native"))
    assertTrue(marker.contains("projectionZoneEffectEdgeGuardEnabled=true"))
    assertTrue(marker.contains("projectionZoneInnerApplication=legacy"))
    assertTrue(marker.contains("projectionZoneInnerColorSource=midpoint"))
    assertTrue(marker.contains("projectionZoneOuterTarget=readable-color"))
  }

  @Test
  fun transparentSpatialUnderlayIsTheOnlyUnconditionalZeroReadableVideoConsumer() {
    assertFalse(
        PrivateLayerZoneCompositorModule.readableVideoConsumerRequired(
            PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest
        )
    )
    assertTrue(
        PrivateLayerZoneCompositorModule.readableVideoConsumerRequired(
            PrivateLayerZoneCompositorControls.organicBuffer
        )
    )
    assertTrue(
        PrivateLayerZoneCompositorModule.readableVideoConsumerRequired(
            PrivateLayerZoneCompositorControls.fullStretch
        )
    )
  }
}
