package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.runtime.BlendFactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialCameraHwbProjectionRawCarrierCoordinatorTest {
  @Test
  fun alphaAccumulationModesKeepRgbOverAndToggleOnlyDestinationAlpha() {
    val standard =
        SpatialCameraHwbProjectionRawAlphaBlend.forConfiguration(
            PrivateLayerZoneCompositorControls.nativeBuffer
        )
    val replace =
        SpatialCameraHwbProjectionRawAlphaBlend.forConfiguration(
            PrivateLayerZoneCompositorControls.nativeBuffer.copy(
                outerStretchOptionFlags =
                    PrivateLayerZoneCompositorControls.outerStretchOptionAlphaAccumulationReplace
            )
        )

    assertEquals(BlendFactor.ONE, standard.colorSource)
    assertEquals(BlendFactor.ONE_MINUS_SOURCE_ALPHA, standard.colorDestination)
    assertEquals(BlendFactor.ONE, standard.alphaSource)
    assertEquals(BlendFactor.ONE_MINUS_SOURCE_ALPHA, standard.alphaDestination)
    assertEquals(standard.colorSource, replace.colorSource)
    assertEquals(standard.colorDestination, replace.colorDestination)
    assertEquals(standard.alphaSource, replace.alphaSource)
    assertEquals(BlendFactor.ZERO, replace.alphaDestination)
  }

  @Test
  fun straightRgbBlendChangesOnlyTheSdkRgbSourceFactor() {
    val submission = SpatialCameraHwbProjectionRawAlphaBlend.forConfiguration(
        PrivateLayerZoneCompositorControls.nativeBuffer.copy(
            outerStretchOptionFlags =
                PrivateLayerZoneCompositorControls.outerStretchOptionStraightRgbBlend,
        )
    )
    assertEquals(BlendFactor.SOURCE_ALPHA, submission.colorSource)
    assertEquals(BlendFactor.ONE_MINUS_SOURCE_ALPHA, submission.colorDestination)
    assertEquals(BlendFactor.ONE, submission.alphaSource)
    assertEquals(BlendFactor.ONE_MINUS_SOURCE_ALPHA, submission.alphaDestination)
  }

  @Test
  fun samplerConfigurationsKeepOriginalDistinctFromExplicitLinearAndNearest() {
    assertEquals(null, SpatialCameraHwbProjectionRawSampler.explicitConfigFor(PrivateLayerZoneCompositorControls.nativeBuffer))
    val sdkDefault = SpatialCameraHwbProjectionRawSampler.sdkDefaultConfig()
    assertEquals(com.meta.spatial.runtime.Filter.LINEAR, sdkDefault.minFilter)
    assertEquals(com.meta.spatial.runtime.Filter.LINEAR, sdkDefault.magFilter)
    assertEquals(com.meta.spatial.runtime.AddressMode.REPEAT, sdkDefault.addressModeU)
    val explicitLinear = checkNotNull(
        SpatialCameraHwbProjectionRawSampler.explicitConfigFor(
            PrivateLayerZoneCompositorControls.nativeBuffer.copy(
                outerStretchOptionFlags =
                    PrivateLayerZoneCompositorControls.outerStretchOptionExplicitLinearSampler,
            )
        )
    )
    assertEquals(com.meta.spatial.runtime.Filter.LINEAR, explicitLinear.minFilter)
    assertEquals(com.meta.spatial.runtime.AddressMode.REPEAT, explicitLinear.addressModeU)
    assertEquals(
        com.meta.spatial.runtime.Filter.NEAREST,
        checkNotNull(
            SpatialCameraHwbProjectionRawSampler.explicitConfigFor(
                PrivateLayerZoneCompositorControls.nativeBuffer.copy(
                    outerStretchOptionFlags =
                        PrivateLayerZoneCompositorControls.outerStretchOptionNearestSampler,
                )
            )
        ).minFilter,
    )
  }

  @Test
  fun alphaBlendSubmissionIncludesAllFactorsAndTheRecreatedLayerGeneration() {
    val submission =
        SpatialCameraHwbProjectionRawAlphaBlend.forConfiguration(
            PrivateLayerZoneCompositorControls.nativeBuffer.copy(
                outerStretchOptionFlags =
                    PrivateLayerZoneCompositorControls.outerStretchOptionAlphaAccumulationReplace
            )
        )

    val marker = submission.markerFields(layerGeneration = 2L)
    for (field in
        listOf(
            "alphaColorSource=ONE",
            "alphaColorDestination=ONE_MINUS_SOURCE_ALPHA",
            "alphaSource=ONE",
            "alphaDestination=ZERO",
            "rawProjectionLayerGeneration=2",
            "sdkAlphaBlendSubmission=true",
            "compositorAdoptionObserved=false",
        )) {
      assertTrue(marker.contains(field), marker)
    }
  }

  @Test
  fun canonicalBridgeToggleReappliesTheLiveAlphaSubmissionForItsSameConfiguration() {
    var callbackSource: String? = null
    var callbackSubmission: SpatialCameraHwbProjectionRawAlphaBlendSubmission? = null
    PrivateLayerZoneCompositorPanelBridge.bind(
        initial = PrivateLayerZoneCompositorControls.nativeBuffer,
        submit = { requested, _ -> PrivateLayerZoneCompositorModule.normalize(requested) },
        onSubmitted = { source ->
          callbackSource = source
          callbackSubmission =
              SpatialCameraHwbProjectionRawAlphaBlend.forConfiguration(
                  PrivateLayerZoneCompositorPanelBridge.configuration
              )
        },
    )

    PrivateLayerZoneCompositorPanelBridge.submit(
        PrivateLayerZoneCompositorPanelBridge.configuration.copy(
            outerStretchOptionFlags =
                PrivateLayerZoneCompositorControls.outerStretchOptionAlphaAccumulationReplace
        ),
        "transition-alpha-replace",
    )

    assertEquals("transition-alpha-replace", callbackSource)
    assertEquals(BlendFactor.ZERO, callbackSubmission?.alphaDestination)
    assertEquals(
        PrivateLayerZoneCompositorPanelBridge.configuration,
        PrivateLayerZoneCompositorModule.normalize(
            PrivateLayerZoneCompositorPanelBridge.configuration
        ),
    )
  }

  @Test
  fun realPreStartSequenceAppliesPendingOverrideBeforeNativeStartExactlyOnce() {
    val events = mutableListOf<String>()
    val harness = PrivateLayerHarness(events, routeActive = true)
    val coordinator = harness.coordinator()
    coordinator.updateLayerOverride(8.0f, "pre-handler-race")

    val result =
        executeSequence(
            events = events,
            application = {
              coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 701L)
            },
        )

    assertTrue(result.admitted)
    assertNull(result.cleanupStatus)
    assertEquals(1L, result.startMask)
    assertEquals(listOf(8.0f), harness.nativeLayerUpdates)
    assertOrdered(
        events,
        "layer-override-requested",
        "layer-override-pending",
        "layer-override-submitted",
        "layer-override-effective",
        "startNative",
    )
    assertEquals(1, events.count { it == "layer-override-submitted" })
  }

  @Test
  fun everyNonAcceptedLayerOverrideCleansAndNeverStartsNative() {
    for (updateMask in listOf<Long?>(0L, 2L, 3L, null)) {
      val events = mutableListOf<String>()
      val harness =
          PrivateLayerHarness(
              events = events,
              routeActive = true,
              layerUpdateMask = updateMask,
              throwOnLayerUpdate = updateMask == null,
          )
      val coordinator = harness.coordinator()
      coordinator.updateLayerOverride(8.0f, "pre-handler-race")

      val result =
          executeSequence(
              events = events,
              application = {
                coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 711L)
              },
          )

      assertFalse(result.admitted)
      assertEquals("cleanup-complete", result.cleanupStatus)
      assertEquals(1, events.count { it == "cleanup" })
      assertEquals(0, events.count { it == "startNative" })
    }
  }

  @Test
  fun everyPreStartCallbackThrowableCleansAndNeverStartsNative() {
    val failingStages =
        listOf("passthrough", "stereo", "scale", "override", "remaining", "depth", "configure", "video")
    for (failingStage in failingStages) {
      val events = mutableListOf<String>()
      val result =
          executeSequence(
              events = events,
              application = { acceptedApplication() },
              failingStage = failingStage,
              videoActive = true,
          )

      assertFalse(result.admitted, failingStage)
      assertEquals("cleanup-complete", result.cleanupStatus, failingStage)
      assertEquals(1, events.count { it == "cleanup" }, failingStage)
      assertEquals(0, events.count { it == "startNative" }, failingStage)
    }
  }

  @Test
  fun layerOverrideMarkerThrowableCleansAndNeverStartsNative() {
    val events = mutableListOf<String>()
    val harness =
        PrivateLayerHarness(
            events = events,
            routeActive = true,
            throwOnMarkerStatus = "layer-override-submitted",
        )
    val coordinator = harness.coordinator()

    val result =
        executeSequence(
            events = events,
            application = {
              coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 801L)
            },
        )

    assertFalse(result.admitted)
    assertEquals("cleanup-complete", result.cleanupStatus)
    assertEquals(1, events.count { it == "cleanup" })
    assertEquals(0, events.count { it == "startNative" })
  }

  @Test
  fun invalidatedLayerSevenLifecycleNeverContinuesToOldNativeStart() {
    val events = mutableListOf<String>()
    var lifecycleCurrent = true
    val result =
        executeSequence(
            events = events,
            application = {
              acceptedApplication().copy(requestedOverride = 7.0f)
            },
            afterApplication = { lifecycleCurrent = false },
            lifecycleCurrent = { lifecycleCurrent },
        )

    assertFalse(result.admitted)
    assertEquals("PrivateLayerOverrideLifecycleInvalidated", result.error)
    assertEquals(1, events.count { it == "cleanup" })
    assertEquals(0, events.count { it == "startNative" })
  }

  private fun executeSequence(
      events: MutableList<String>,
      application: () -> PrivateLayerOverrideApplicationResult,
      failingStage: String? = null,
      videoActive: Boolean = false,
      afterApplication: () -> Unit = {},
      lifecycleCurrent: () -> Boolean = { true },
  ): SpatialCameraHwbProjectionRawStartSequenceResult {
    fun stage(name: String) {
      events.add(name)
      if (failingStage == name) error("synthetic-$name-failure")
    }
    return SpatialCameraHwbProjectionRawStartSequence.execute(
        SpatialCameraHwbProjectionRawStartSequenceBindings(
            startNativePassthrough = {
              stage("passthrough")
              1L
            },
            updateNativeStereoOffset = { stage("stereo") },
            updateNativeTargetScale = { stage("scale") },
            applyPrivateLayerOverride = {
              stage("override")
              application()
            },
            applyRemainingPrivateLayerConfiguration = {
              stage("remaining")
              afterApplication()
            },
            startEnvironmentDepth = {
              stage("depth")
              1L
            },
            configureVideoProjection = { stage("configure") },
            startVideoProjection = if (videoActive) ({ stage("video") }) else null,
            privateLayerOverrideLifecycleCurrent = lifecycleCurrent,
            startNative = {
              stage("startNative")
              1L
            },
            cleanup = {
              events.add("cleanup")
              "cleanup-complete"
            },
        )
    )
  }

  private fun acceptedApplication() =
      PrivateLayerOverrideApplicationResult(
          accepted = true,
          requestGeneration = 7L,
          nativeLifecycleGeneration = 11L,
          requestedOverride = 8.0f,
          updateMask = PrivateLayerPanelControlModule.LAYER_OVERRIDE_ACCEPTED_MASK,
          failureReason = null,
      )

  private fun assertOrdered(events: List<String>, vararg expected: String) {
    var prior = -1
    for (token in expected) {
      val index = events.indexOfFirst { it == token }
      assertTrue(index > prior, "Expected '$token' after index $prior in $events")
      prior = index
    }
  }

  private class PrivateLayerHarness(
      private val events: MutableList<String>,
      var routeActive: Boolean,
      private val throwOnMarkerStatus: String? = null,
      private val layerUpdateMask: Long? =
          PrivateLayerPanelControlModule.LAYER_OVERRIDE_ACCEPTED_MASK,
      private val throwOnLayerUpdate: Boolean = false,
  ) {
    val nativeLayerUpdates = mutableListOf<Float>()

    fun coordinator() =
        SpatialPrivateLayerControlCoordinator(
            SpatialPrivateLayerControlBindings(
                routeActive = { routeActive },
                placementMode = { CameraHwbProjectionPlacementMode.ViewerLocked },
                projectionTargetScale = { 1.0f },
                updatePlacement = { _, _ -> },
                updateLayerOverrideNative = {
                  nativeLayerUpdates.add(it)
                  if (throwOnLayerUpdate) error("synthetic-native-failure")
                  requireNotNull(layerUpdateMask)
                },
                updateEnvironmentDepthConsumerRequired = { _, _ -> },
                updateMetaPassthroughStyle = { requested, _ ->
                  SpatialPassthroughLutUpdate(
                      requested = requested,
                      systemPassthroughEnabled = requested,
                      lutApplied = requested,
                      phase = 0.0f,
                      amplitude = 0.0f,
                  )
                },
                projectionPanelEnabled = { false },
                refreshProjectionAfterPassthroughActivation = { _ -> },
                updateDepthLayerPolicyNative = { 1L },
                updateDepthAlignmentNative = { 1L },
                updateGuideProcessingNative = { 1L },
                updateZoneCompositorNative = { 1L },
                updateReadableVideoConsumerRequired = { _, _ -> },
              updateRgbChannelTransformNative = { 1L },
              updateStrengthCycleSpeedHzNative = { 1L },
                updateProjectionSurfaceDisplacementNative = { 1L },
                updateProjectionSurfaceFeaturesNative = { _, _ -> 1L },
                marker = { marker ->
                  val status = marker.substringAfter("status=").substringBefore(' ')
                  events.add(status)
                  if (status == throwOnMarkerStatus) error("synthetic-marker-failure")
                },
            )
        )
  }
}
