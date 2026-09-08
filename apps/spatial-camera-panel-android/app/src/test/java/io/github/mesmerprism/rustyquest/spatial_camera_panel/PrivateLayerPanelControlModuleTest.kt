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
    assertEquals(BuildConfig.DEPTH_ALIGNMENT_DEFAULT_LEFT_X, alignment.leftX)
    assertEquals(BuildConfig.DEPTH_ALIGNMENT_DEFAULT_LEFT_Y, alignment.leftY)
    assertEquals(BuildConfig.DEPTH_ALIGNMENT_DEFAULT_RIGHT_X, alignment.rightX)
    assertEquals(BuildConfig.DEPTH_ALIGNMENT_DEFAULT_RIGHT_Y, alignment.rightY)
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

  @Test
  fun environmentDepthRunsOnlyForShaderLayersThatCanConsumeIt() {
    assertTrue(PrivateLayerControls.environmentDepthConsumerRequired(-1.0f))
    assertTrue(PrivateLayerControls.environmentDepthConsumerRequired(0.0f))
    assertFalse(PrivateLayerControls.environmentDepthConsumerRequired(1.0f))
    assertFalse(PrivateLayerControls.environmentDepthConsumerRequired(2.0f))
    assertFalse(PrivateLayerControls.environmentDepthConsumerRequired(3.0f))
    assertFalse(PrivateLayerControls.environmentDepthConsumerRequired(4.0f))
    assertTrue(PrivateLayerControls.environmentDepthConsumerRequired(5.0f))
    assertTrue(PrivateLayerControls.environmentDepthConsumerRequired(6.0f))
    assertFalse(PrivateLayerControls.environmentDepthConsumerRequired(7.0f))
    assertFalse(PrivateLayerControls.environmentDepthConsumerRequired(8.0f))
  }

  @Test
  fun layerOverrideAcceptsOnlyTheNamedExactNativeMask() {
    assertTrue(
        PrivateLayerPanelControlModule.layerOverrideMaskAccepted(
            PrivateLayerPanelControlModule.LAYER_OVERRIDE_ACCEPTED_MASK
        )
    )
    assertFalse(PrivateLayerPanelControlModule.layerOverrideMaskAccepted(0L))
    assertFalse(PrivateLayerPanelControlModule.layerOverrideMaskAccepted(3L))
  }

  @Test
  fun layerOverrideMarkersKeepRequestedPendingSubmittedEffectiveAndFailedDistinct() {
    val requested =
        PrivateLayerPanelControlModule.layerOverrideRequestedMarker(
            source = "test",
            requestedLayerOverride = 8.0f,
            previousRequestedOverride = -1.0f,
            normalizedRequestedOverride = 8.0f,
            requestGeneration = 4L,
            placementMode = CameraHwbProjectionPlacementMode.ViewerLocked,
        )
    val pending =
        PrivateLayerPanelControlModule.layerOverridePendingMarker(
            source = "test",
            requestedOverride = 8.0f,
            requestGeneration = 4L,
            pendingReason = "native-lifecycle-not-ready",
        )
    val submitted =
        PrivateLayerPanelControlModule.layerOverrideSubmittedMarker(
            source = "test",
            updateMask = PrivateLayerPanelControlModule.LAYER_OVERRIDE_ACCEPTED_MASK,
            requestGeneration = 4L,
            nativeLifecycleGeneration = 9L,
            requestedOverride = 8.0f,
            placementMode = CameraHwbProjectionPlacementMode.ViewerLocked,
            projectionTargetScale = 1.0f,
        )
    val effective =
        PrivateLayerPanelControlModule.layerOverrideEffectiveMarker(
            source = "test",
            requestGeneration = 4L,
            nativeLifecycleGeneration = 9L,
            previousEffectiveOverride = -1.0f,
            effectiveOverride = 8.0f,
            pendingRequestCleared = true,
        )
    val failed =
        PrivateLayerPanelControlModule.layerOverrideUpdateFailedMarker(
            source = "test",
            requestedLayerOverride = 8.0f,
            requestGeneration = 4L,
            nativeLifecycleGeneration = 9L,
            updateMask = 3L,
            pendingRequestPreserved = true,
            error = "NativeUpdateMaskRejected",
            message = "expected-1",
        )

    assertTrue(requested.contains("status=layer-override-requested"))
    assertTrue(pending.contains("status=layer-override-pending"))
    assertTrue(pending.contains("nativeSubmissionAttempted=false"))
    assertTrue(pending.contains("pendingRequestPresent=true"))
    assertTrue(submitted.contains("status=layer-override-submitted"))
    assertTrue(submitted.contains("acceptedMask=1"))
    assertTrue(effective.contains("status=layer-override-effective"))
    assertTrue(effective.contains("effectivePublicMultiStackOpaqueProjectionLayerOverride=8.0000"))
    assertTrue(failed.contains("status=layer-override-update-failed"))
    assertTrue(failed.contains("layerOverrideAccepted=false"))
    assertFalse(failed.contains("effectivePublicMultiStackOpaqueProjectionLayerOverride"))
  }
}
