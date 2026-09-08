package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialPrivateLayerControlCoordinatorTest {
  @Test
  fun lateProjectionInitializationDoesNotOverwriteExplicitProfileDepthControls() {
    val coordinator = Harness().coordinator()
    val requestedGuide =
        PrivateLayerGuideProcessing(
            preblurInput = 1,
            preblurKernel = 2,
            postblurKernel = 3,
            cameraSampling = 0,
        )

    coordinator.updateDepthLayerPolicy(PrivateLayerControls.depthPolicyMonoLayer0, "profile-playlist-step")
    coordinator.updateGuideProcessing(requestedGuide, "profile-playlist-step")
    coordinator.initializeDepthLayerPolicy(PrivateLayerControls.depthPolicyEyeIndex)
    coordinator.initializeGuideProcessing(PrivateLayerControls.nativeParityGuideProcessing)

    assertEquals(PrivateLayerControls.depthPolicyMonoLayer0, coordinator.depthLayerPolicy)
    assertEquals(
        PrivateLayerPanelControlModule.normalizeGuideProcessing(requestedGuide),
        coordinator.guideProcessing,
    )
  }

  @Test
  fun projectionInitializationStillSuppliesDefaultsBeforeAnyExplicitProfileUpdate() {
    val coordinator = Harness().coordinator()
    val requestedGuide =
        PrivateLayerGuideProcessing(
            preblurInput = 1,
            preblurKernel = 1,
            postblurKernel = 2,
            cameraSampling = 0,
        )

    coordinator.initializeDepthLayerPolicy(PrivateLayerControls.depthPolicyMonoLayer0)
    coordinator.initializeGuideProcessing(requestedGuide)

    assertEquals(PrivateLayerControls.depthPolicyMonoLayer0, coordinator.depthLayerPolicy)
    assertEquals(
        PrivateLayerPanelControlModule.normalizeGuideProcessing(requestedGuide),
        coordinator.guideProcessing,
    )
  }

  @Test
  fun strengthCycleSpeedUsesTheSharedBoundsAndZeroRemainsAFreezeRequest() {
    val harness = Harness()
    val coordinator = harness.coordinator()

    assertEquals(0.0f, coordinator.updateStrengthCycleSpeedHz(-1.0f, "freeze"))
    assertEquals(2.0f, coordinator.updateStrengthCycleSpeedHz(4.0f, "clamp"))
    assertTrue(
        harness.markers.any {
          it.contains("status=strength-cycle-speed-submitted") &&
              it.contains("effectiveStrengthCycleSpeedHz=2.0")
        }
    )
  }

  @Test
  fun inactiveRequestsRemainPendingAndNewestGenerationAppliesOnceForOneLifecycle() {
    val harness = Harness(routeActive = false)
    val coordinator = harness.coordinator()

    coordinator.updateLayerOverride(8.0f, "first")
    coordinator.updateLayerOverride(2.0f, "newest")

    assertEquals(2.0f, coordinator.layerOverride)
    assertEquals(PrivateLayerControls.cycleOverride, coordinator.effectiveLayerOverride)
    assertTrue(harness.nativeLayerUpdates.isEmpty())
    assertTrue(
        harness.markers.any {
          it.contains("status=layer-override-requested") &&
              it.contains("layerOverrideRequestGeneration=1")
        }
    )
    assertTrue(
        harness.markers.any {
          it.contains("status=layer-override-pending") &&
              it.contains("layerOverrideRequestGeneration=2")
        }
    )

    harness.routeActive = true
    val first = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 101L)
    val replay = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch-replay", 101L)

    assertTrue(first.accepted)
    assertEquals(first, replay)
    assertEquals(listOf(2.0f), harness.nativeLayerUpdates)
    assertEquals(2.0f, coordinator.effectiveLayerOverride)
    assertEquals(
        listOf("layer-override-submitted", "layer-override-effective"),
        harness.markers
            .filter { it.contains("nativeLifecycleGeneration=101") }
            .map { it.substringAfter("status=").substringBefore(' ') },
    )
  }

  @Test
  fun routeActiveBeforeRawLifecycleKeepsRequestPendingThenSubmitsOnceToPositiveLifecycle() {
    val harness = Harness(routeActive = true)
    val coordinator = harness.coordinator()

    coordinator.updateLayerOverride(8.0f, "pre-handler-race")

    assertTrue(harness.nativeLayerUpdates.isEmpty())
    assertTrue(
        harness.markers.any {
          it.contains("status=layer-override-pending") &&
              it.contains("pendingReason=native-lifecycle-not-ready")
        }
    )

    val accepted = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 501L)
    val replay = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch-replay", 501L)

    assertTrue(accepted.accepted)
    assertEquals(accepted, replay)
    assertEquals(501L, accepted.nativeLifecycleGeneration)
    assertEquals(listOf(8.0f), harness.nativeLayerUpdates)
  }

  @Test
  fun profileLayerOverrideResultIsPendingBeforeLifecycleAndEffectiveAfterAcceptance() {
    val harness = Harness(routeActive = true)
    val coordinator = harness.coordinator()

    val pending = coordinator.updateLayerOverrideWithResult(8.0f, "profile-pending")

    assertEquals("pending", pending.status)
    assertFalse(pending.effective)
    assertEquals(PrivateLayerControls.cycleOverride, pending.effectiveOverride)
    assertNull(pending.nativeLifecycleGeneration)

    coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 601L)
    val effective = coordinator.updateLayerOverrideWithResult(2.0f, "profile-effective")

    assertEquals("effective", effective.status)
    assertTrue(effective.effective)
    assertEquals(2.0f, effective.effectiveOverride)
    assertEquals(601L, effective.nativeLifecycleGeneration)
  }

  @Test
  fun layerSevenRunningRefreshInvalidationNeverResubmitsOnStaleLifecycle() {
    val harness = Harness(routeActive = true)
    lateinit var coordinator: SpatialPrivateLayerControlCoordinator
    var refreshCalls = 0
    coordinator =
        harness.coordinator(
            refreshProjection = {
              refreshCalls += 1
              coordinator.clearNativeLayerOverrideLifecycle()
            }
        )
    coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 602L)

    val invalidated = coordinator.updateLayerOverrideWithResult(7.0f, "running-layer-seven")

    assertEquals(1, refreshCalls)
    assertEquals("failed", invalidated.status)
    assertFalse(invalidated.effective)
    assertEquals("native-lifecycle-invalidated-during-submission", invalidated.failureReason)
    assertEquals(listOf(PrivateLayerControls.cycleOverride, 7.0f), harness.nativeLayerUpdates)
    assertFalse(coordinator.layerOverrideNativeLifecycleReady())
  }

  @Test
  fun layerSevenPreStartApplicationSuppressesCarrierRefresh() {
    val harness = Harness(routeActive = true)
    var refreshCalls = 0
    val coordinator = harness.coordinator(refreshProjection = { refreshCalls += 1 })
    coordinator.updateLayerOverride(7.0f, "prestart-layer-seven")

    val application = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 603L)

    assertTrue(application.readyForNativeStart)
    assertEquals(0, refreshCalls)
    assertEquals(listOf(7.0f), harness.nativeLayerUpdates)
  }

  @Test
  fun wrongOrAmbiguousMaskKeepsPriorEffectiveValueAndRetriesOnlyInNewLifecycle() {
    val harness = Harness(routeActive = false)
    harness.updateMasks.add(3L)
    harness.updateMasks.add(PrivateLayerPanelControlModule.LAYER_OVERRIDE_ACCEPTED_MASK)
    val coordinator = harness.coordinator()
    coordinator.updateLayerOverride(8.0f, "request")

    val rejected = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 201L)
    val sameLifecycle = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch-repeat", 201L)

    assertFalse(rejected.accepted)
    assertEquals("native-update-mask-not-exactly-accepted", rejected.failureReason)
    assertEquals(rejected, sameLifecycle)
    assertEquals(PrivateLayerControls.cycleOverride, coordinator.effectiveLayerOverride)
    val accepted = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch-next", 202L)
    assertTrue(accepted.accepted)
    assertEquals(listOf(8.0f, 8.0f), harness.nativeLayerUpdates)
    assertTrue(
        harness.markers.any {
          it.contains("status=layer-override-update-failed") &&
              it.contains("layerOverrideAccepted=false")
        }
    )
  }

  @Test
  fun nativeExceptionKeepsRequestPendingAndContainsNoEffectiveMarker() {
    val harness = Harness(routeActive = false)
    harness.throwOnNextLayerUpdate = true
    val coordinator = harness.coordinator()
    coordinator.updateLayerOverride(8.0f, "request")

    val result = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 301L)

    assertFalse(result.accepted)
    assertNull(result.updateMask)
    assertEquals(PrivateLayerControls.cycleOverride, coordinator.effectiveLayerOverride)
    assertTrue(harness.markers.any { it.contains("status=layer-override-update-failed") })
    assertFalse(harness.markers.any { it.contains("status=layer-override-effective") })
  }

  @Test
  fun reentrantNewerRequestDrainsAfterAcceptedOlderValueWithoutDivergence() {
    val harness = Harness(routeActive = false)
    lateinit var coordinator: SpatialPrivateLayerControlCoordinator
    var injectedNewerRequest = false
    coordinator =
        harness.coordinator(
            layerUpdate = {
              if (!injectedNewerRequest) {
                injectedNewerRequest = true
                harness.routeActive = false
                coordinator.updateLayerOverride(2.0f, "newer-during-native")
              }
              PrivateLayerPanelControlModule.LAYER_OVERRIDE_ACCEPTED_MASK
            }
        )
    coordinator.updateLayerOverride(8.0f, "older")

    val newest = coordinator.applyPendingLayerOverrideForRawLaunch("raw-launch", 401L)

    assertTrue(newest.accepted)
    assertEquals(2.0f, newest.requestedOverride)
    assertEquals(2.0f, coordinator.layerOverride)
    assertEquals(2.0f, coordinator.effectiveLayerOverride)
    assertEquals(listOf(8.0f, 2.0f), harness.nativeLayerUpdates)
    assertTrue(
        harness.markers.any {
          it.contains("status=layer-override-effective") &&
              it.contains("effectivePublicMultiStackOpaqueProjectionLayerOverride=8.0000") &&
              it.contains("pendingRequestCleared=false")
        }
    )
  }

  @Test
  fun rawRemainingConfigurationNeverResubmitsLayerOverride() {
    val harness = Harness(routeActive = true)
    val coordinator = harness.coordinator()

    coordinator.applyRemainingConfiguration("raw-launch-rest")

    assertTrue(harness.nativeLayerUpdates.isEmpty())
  }

  @Test
  fun panelCarrierConfigurationRetainsFullLayerOverridePath() {
    val harness = Harness(routeActive = true)
    val coordinator = harness.coordinator()

    coordinator.applyCurrentConfiguration("panel-carrier-ready")

    assertEquals(listOf(PrivateLayerControls.cycleOverride), harness.nativeLayerUpdates)
    assertTrue(
        harness.markers.any {
          it.contains("status=layer-override-effective") &&
              it.contains(
                  "nativeLifecycleGeneration=" +
                      SpatialPrivateLayerControlCoordinator.PANEL_CARRIER_READY_LIFECYCLE_GENERATION
              )
        }
    )
  }

  @Test
  fun outerVideoRetainsTransparentCompositorUntilDecoderFirstFrame() {
    val harness = Harness(routeActive = true)
    val transparent = PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest
    val video =
        transparent.copy(
            outerTargetMode = PrivateLayerZoneCompositorControls.outerTargetReadableColor,
            outerContentMode = PrivateLayerZoneCompositorControls.outerContentVideo,
        )
    val coordinator = harness.coordinator(initialZoneCompositor = transparent)

    coordinator.updateZoneCompositor(video, "outer-video")

    assertEquals(video, coordinator.zoneCompositor)
    assertTrue(harness.nativeZoneUpdates.isEmpty())
    assertEquals(listOf(true), harness.videoConsumerPolicies)
    coordinator.updateReadableVideoLifecycle(
        SpatialVideoProjectionDecoderState.Effective,
        "first-frame",
    )
    assertEquals(listOf(PrivateLayerZoneCompositorModule.normalize(video)), harness.nativeZoneUpdates)
  }

  @Test
  fun outerVideoFailureKeepsOrRestoresTransparentCompositor() {
    val harness = Harness(routeActive = true)
    val transparent = PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest
    val video =
        transparent.copy(
            outerTargetMode = PrivateLayerZoneCompositorControls.outerTargetReadableColor,
            outerContentMode = PrivateLayerZoneCompositorControls.outerContentVideo,
        )
    val coordinator = harness.coordinator(initialZoneCompositor = transparent)
    coordinator.updateZoneCompositor(video, "outer-video")

    coordinator.updateReadableVideoLifecycle(
        SpatialVideoProjectionDecoderState.Failed,
        "offline-pack-chunk-authentication-failed",
    )

    assertTrue(harness.nativeZoneUpdates.isEmpty())
    assertTrue(
        harness.markers.any {
          it.contains("status=zone-compositor-video-failed") &&
              it.contains("lastSafeCompositorRetained=true")
        }
    )
  }

  @Test
  fun decoderFailureAfterFirstFrameRestoresTransparentCompositor() {
    val harness = Harness(routeActive = true)
    val transparent = PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest
    val video =
        transparent.copy(
            outerTargetMode = PrivateLayerZoneCompositorControls.outerTargetReadableColor,
            outerContentMode = PrivateLayerZoneCompositorControls.outerContentVideo,
        )
    val coordinator = harness.coordinator(initialZoneCompositor = transparent)
    coordinator.updateZoneCompositor(video, "outer-video")
    coordinator.updateReadableVideoLifecycle(
        SpatialVideoProjectionDecoderState.Effective,
        "first-frame",
    )

    coordinator.updateReadableVideoLifecycle(
        SpatialVideoProjectionDecoderState.Failed,
        "decoder-error",
    )

    assertEquals(2, harness.nativeZoneUpdates.size)
    assertEquals(PrivateLayerZoneCompositorControls.outerContentVideo, harness.nativeZoneUpdates[0].outerContentMode)
    assertEquals(
        PrivateLayerZoneCompositorControls.outerContentTransparent,
        harness.nativeZoneUpdates[1].outerContentMode,
    )
  }

  @Test
  fun zoneCompositorNativeExceptionRetainsEffectiveStateAndDoesNotClaimSubmission() {
    val harness = Harness(routeActive = true)
    harness.throwOnNextZoneUpdate = true
    val coordinator = harness.coordinator()
    val requested = PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest

    coordinator.updateZoneCompositor(requested, "synthetic-native-failure")

    assertEquals(
        listOf(PrivateLayerZoneCompositorModule.normalize(requested)),
        harness.nativeZoneUpdates,
    )
    assertTrue(
        harness.markers.any {
          it.contains("status=zone-compositor-update-failed") &&
              it.contains("effectiveStateRetained=true") &&
              it.contains("jniSubmissionSucceeded=false") &&
              it.contains("rendered=false")
        }
    )
    assertFalse(harness.markers.any { it.contains("status=zone-compositor-submitted") })
  }

  private class Harness(var routeActive: Boolean = true) {
    val markers = mutableListOf<String>()
    val nativeLayerUpdates = mutableListOf<Float>()
    val updateMasks = mutableListOf<Long>()
    val nativeZoneUpdates = mutableListOf<PrivateLayerZoneCompositor>()
    val videoConsumerPolicies = mutableListOf<Boolean>()
    var throwOnNextLayerUpdate = false
    var throwOnNextZoneUpdate = false

    fun coordinator(
        layerUpdate: ((Float) -> Long)? = null,
        refreshProjection: ((String) -> Unit)? = null,
        initialZoneCompositor: PrivateLayerZoneCompositor =
            PrivateLayerZoneCompositorControls.legacyOff,
    ): SpatialPrivateLayerControlCoordinator =
        SpatialPrivateLayerControlCoordinator(
            SpatialPrivateLayerControlBindings(
              routeActive = { routeActive },
              placementMode = { CameraHwbProjectionPlacementMode.ViewerLocked },
              projectionTargetScale = { 1.0f },
              updatePlacement = { _, _ -> },
              updateLayerOverrideNative = { requested ->
                nativeLayerUpdates.add(requested)
                if (throwOnNextLayerUpdate) {
                  throwOnNextLayerUpdate = false
                  error("synthetic-native-failure")
                }
                layerUpdate?.invoke(requested)
                    ?: if (updateMasks.isEmpty()) {
                      PrivateLayerPanelControlModule.LAYER_OVERRIDE_ACCEPTED_MASK
                    } else {
                      updateMasks.removeAt(0)
                    }
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
              projectionPanelEnabled = { true },
              refreshProjectionAfterPassthroughActivation = { reason ->
                refreshProjection?.invoke(reason)
              },
              updateDepthLayerPolicyNative = { 1L },
              updateDepthAlignmentNative = { 1L },
              updateGuideProcessingNative = { 1L },
              updateZoneCompositorNative = {
                nativeZoneUpdates += it
                if (throwOnNextZoneUpdate) {
                  throwOnNextZoneUpdate = false
                  error("synthetic-zone-native-failure")
                }
                1L
              },
               updateReadableVideoConsumerRequired = { required, _ ->
                 videoConsumerPolicies += required
               },
              updateRgbChannelTransformNative = { 1L },
              updateStrengthCycleSpeedHzNative = { 1L },
              updateProjectionSurfaceDisplacementNative = { 1L },
              updateProjectionSurfaceFeaturesNative = { _, _ -> 1L },
              marker = markers::add,
          ),
          initialZoneCompositor = initialZoneCompositor,
      )
  }
}
