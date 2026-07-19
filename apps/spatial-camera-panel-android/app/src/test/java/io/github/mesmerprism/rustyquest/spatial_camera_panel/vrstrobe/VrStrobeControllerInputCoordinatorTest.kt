package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrStrobeControllerInputCoordinatorTest {
  @Test
  fun presentRightControllerSampleRemainsValidWhenRuntimeActiveFlagDrops() {
    assertTrue(
        VrStrobeRightControllerSamplePolicy.isValid(
            localControllerType = "CONTROLLER",
            localAttachmentType = "right_controller",
            avatarControllerType = "none",
        )
    )
    assertTrue(
        VrStrobeRightControllerSamplePolicy.isValid(
            localControllerType = "none",
            localAttachmentType = "none",
            avatarControllerType = "CONTROLLER",
        )
    )
    assertFalse(
        VrStrobeRightControllerSamplePolicy.isValid(
            localControllerType = "HAND",
            localAttachmentType = "right_controller",
            avatarControllerType = "none",
        )
    )
  }

  @Test
  fun presentLeftControllerSampleOwnsLeftPrimaryStoreEvenWhenRuntimeActiveFlagDrops() {
    assertTrue(
        VrStrobeLeftControllerSamplePolicy.isValid(
            localControllerType = "CONTROLLER",
            localAttachmentType = "left_controller",
            avatarControllerType = "none",
        )
    )
    assertFalse(
        VrStrobeLeftControllerSamplePolicy.isValid(
            localControllerType = "CONTROLLER",
            localAttachmentType = "right_controller",
            avatarControllerType = "none",
        )
    )
  }

  @Test
  fun androidPrimaryEdgeRandomizesWithoutWaitingForSpatialSnapshotState() {
    val harness = Harness(selected = true)

    assertTrue(
        harness.coordinator.handlePrimary(
            "android-key-event",
            "keyCode=96",
        )
    )
    assertEquals(1, harness.randomizeCount)
    assertTrue(harness.markers.any { it.contains("status=right-a-ingress-observed") })
    assertTrue(
        harness.markers.any {
          it.contains("inputObservation=android-controller-event") &&
              it.contains("actionAuthority=vr-strobe-controller-input-coordinator")
        }
    )
  }

  @Test
  fun spatialPrimaryEdgeRemainsAvailableAsFallback() {
    val harness = Harness(selected = true)

    harness.snapshot(primaryDown = true, sampleValid = true)
    harness.snapshot(primaryDown = true, sampleValid = true)
    assertEquals(1, harness.randomizeCount)
    assertTrue(harness.markers.any { it.contains("status=right-a-press-dispatched") })
    assertTrue(harness.markers.any { it.contains("status=right-a-press-complete") })
    assertTrue(
        harness.markers.any { it.contains("inputObservation=spatial-sdk-controller-snapshot") }
    )
  }

  @Test
  fun androidAndSpatialObservationsOfOnePrimaryPressRandomizeOnce() {
    val harness = Harness(selected = true)

    harness.coordinator.handlePrimary("android-key-event", "keyCode=96")
    harness.nowMs += 16L
    harness.snapshot(primaryDown = true, sampleValid = true)
    assertEquals(1, harness.randomizeCount)

    harness.nowMs += 500L
    harness.snapshot(primaryDown = false, sampleValid = true)
    harness.nowMs += VrStrobeControllerInputCoordinator.RELEASE_CONFIRM_MS
    harness.snapshot(primaryDown = false, sampleValid = true)
    harness.snapshot(primaryDown = true, sampleValid = true)
    harness.nowMs += 16L
    harness.coordinator.handlePrimary("android-key-event", "keyCode=96")
    assertEquals(2, harness.randomizeCount)
    assertTrue(harness.markers.any { it.contains("reason=duplicate-edge") })
  }

  @Test
  fun separateAndroidPrimaryPressesEachRandomizeWhenSnapshotRouteIsUnavailable() {
    val harness = Harness(selected = true)

    repeat(6) { press ->
      harness.coordinator.handlePrimary("android-key-event", "press=$press")
      harness.nowMs += VrStrobeControllerInputCoordinator.DUPLICATE_ROUTE_WINDOW_MS + 1L
    }

    assertEquals(6, harness.randomizeCount)
  }

  @Test
  fun heldPrimaryIsNotRearmedByInvalidControllerDropout() {
    val harness = Harness(selected = true)

    harness.snapshot(primaryDown = true, sampleValid = true)
    assertEquals(1, harness.randomizeCount)

    harness.nowMs += 500L
    harness.snapshot(primaryDown = false, sampleValid = false)
    harness.nowMs += 500L
    harness.snapshot(primaryDown = true, sampleValid = true)
    assertEquals(1, harness.randomizeCount)

    harness.snapshot(primaryDown = false, sampleValid = true)
    harness.nowMs += VrStrobeControllerInputCoordinator.RELEASE_CONFIRM_MS
    harness.snapshot(primaryDown = false, sampleValid = true)
    harness.snapshot(primaryDown = true, sampleValid = true)
    assertEquals(2, harness.randomizeCount)
  }

  @Test
  fun leftPrimaryStoresOncePerConfirmedPhysicalPress() {
    val harness = Harness(selected = true)

    harness.snapshot(storeDown = true, storeSampleValid = true)
    harness.nowMs += 500L
    harness.snapshot(storeDown = false, storeSampleValid = false)
    harness.snapshot(storeDown = true, storeSampleValid = true)
    assertEquals(1, harness.storeCount)

    harness.snapshot(storeDown = false, storeSampleValid = true)
    harness.nowMs += VrStrobeControllerInputCoordinator.RELEASE_CONFIRM_MS
    harness.snapshot(storeDown = false, storeSampleValid = true)
    harness.snapshot(storeDown = true, storeSampleValid = true)
    assertEquals(2, harness.storeCount)
    assertTrue(harness.markers.any { it.contains("status=left-primary-store-dispatched") })
  }

  @Test
  fun secondaryTogglesPanelWithoutASelectedStimulusAndDebouncesDuplicateRoutes() {
    val harness = Harness()

    assertTrue(harness.coordinator.handleSecondary("android"))
    assertTrue(harness.coordinator.handleSecondary("spatial-sdk"))
    assertEquals(1, harness.toggleCount)
  }

  @Test
  fun horizontalFlickCyclesOnceUntilBothSticksRelease() {
    val harness = Harness(selected = true)

    harness.coordinator.handleAxes(VrStrobeControllerAxes(leftX = -1f), "spatial-sdk")
    harness.nowMs += 300L
    harness.coordinator.handleAxes(VrStrobeControllerAxes(leftX = -1f), "spatial-sdk")
    assertEquals(listOf(-1), harness.cycles)

    harness.coordinator.handleAxes(VrStrobeControllerAxes(), "spatial-sdk")
    harness.nowMs += 300L
    harness.coordinator.handleAxes(VrStrobeControllerAxes(rightX = 1f), "spatial-sdk")
    assertEquals(listOf(-1, 1), harness.cycles)
  }

  @Test
  fun dominantVerticalAxisAdjustsDistanceWithoutCyclingPreset() {
    val harness = Harness(selected = true)

    harness.coordinator.handleAxes(
        VrStrobeControllerAxes(leftX = 0.8f, leftY = -1f),
        "android",
    )

    assertEquals(emptyList(), harness.cycles)
    assertEquals(1, harness.distanceInputs.size)
    assertEquals(-1f, harness.distanceInputs.single().first)
    assertTrue(harness.distanceInputs.single().second > 0f)
  }

  @Test
  fun curvedModeRoutesLeftVerticalToConcavityAndRightVerticalToDistance() {
    val harness = Harness(selected = true)
    harness.curved = true

    harness.coordinator.handleAxes(VrStrobeControllerAxes(leftY = -1f), "spatial-sdk")
    harness.nowMs += 16L
    harness.coordinator.handleAxes(VrStrobeControllerAxes(rightY = 1f), "spatial-sdk")

    assertEquals(listOf(-1f), harness.concavityInputs.map { it.first })
    assertEquals(listOf(1f), harness.distanceInputs.map { it.first })
  }

  @Test
  fun curvedModeHorizontalFlickWinsOverLeftVerticalAdjustment() {
    val harness = Harness(selected = true)
    harness.curved = true

    harness.coordinator.handleAxes(
        VrStrobeControllerAxes(leftX = -1f, leftY = 0.5f),
        "spatial-sdk",
    )

    assertEquals(listOf(-1), harness.cycles)
    assertEquals(emptyList(), harness.concavityInputs)
  }

  @Test
  fun inactiveStimulusConsumesStrobeAxesWithoutChangingOutput() {
    val harness = Harness(selected = false)

    assertTrue(
        harness.coordinator.handleAxes(
            VrStrobeControllerAxes(rightX = 1f, rightY = -1f),
            "android",
        )
    )
    assertEquals(emptyList(), harness.cycles)
    assertEquals(emptyList(), harness.distanceInputs)
  }

  @Test
  fun distancePolicyMovesUpFartherAndDownNearerWithinClamps() {
    assertEquals(VrStrobeDistancePolicy.MAX_METERS, VrStrobeDistancePolicy.DEFAULT_METERS)
    assertEquals(
        VrStrobeDistancePolicy.MAX_METERS,
        VrStrobeDistancePolicy.apply(VrStrobeDistancePolicy.DEFAULT_METERS, -1f, 0.05f),
    )
    assertTrue(
        VrStrobeDistancePolicy.apply(VrStrobeDistancePolicy.DEFAULT_METERS, 1f, 0.05f) <
            VrStrobeDistancePolicy.DEFAULT_METERS
    )
    assertEquals(
        VrStrobeDistancePolicy.MAX_METERS,
        VrStrobeDistancePolicy.apply(VrStrobeDistancePolicy.MAX_METERS, -1f, 0.05f),
    )
    assertEquals(
        VrStrobeDistancePolicy.MIN_METERS,
        VrStrobeDistancePolicy.apply(VrStrobeDistancePolicy.MIN_METERS, 1f, 0.05f),
    )
  }

  private class Harness(
      var selected: Boolean = false,
  ) {
    var enabled = true
    var nowMs = 1_000L
    var randomizeCount = 0
    var storeCount = 0
    var toggleCount = 0
    var curved = false
    val cycles = mutableListOf<Int>()
    val distanceInputs = mutableListOf<Pair<Float, Float>>()
    val concavityInputs = mutableListOf<Pair<Float, Float>>()
    val markers = mutableListOf<String>()
    val coordinator =
        VrStrobeControllerInputCoordinator(
            VrStrobeControllerInputBindings(
                featureEnabled = { enabled },
                stimulusSelected = { selected },
                randomizeActive = {
                  randomizeCount += 1
                  VrStrobeSafetySnapshot(
                      state = VrStrobeSafetyState.RUNNING,
                      stimulusRevision = randomizeCount.toLong(),
                  )
                },
                storeActive = { storeCount += 1 },
                togglePanel = { toggleCount += 1 },
                cyclePreset = { direction, _ -> cycles += direction },
                curvedMode = { curved },
                adjustDistance = { value, seconds, _ -> distanceInputs += value to seconds },
                adjustConcavity = { value, seconds, _ -> concavityInputs += value to seconds },
                monotonicNowMs = { nowMs },
                marker = markers::add,
            )
        )

    fun snapshot(
        primaryDown: Boolean = false,
        secondaryDown: Boolean = false,
        storeDown: Boolean = false,
        sampleValid: Boolean = true,
        storeSampleValid: Boolean = sampleValid,
    ) {
      coordinator.handleSnapshot(
          axes = VrStrobeControllerAxes(),
          primaryDown = primaryDown,
          secondaryDown = secondaryDown,
          storeDown = storeDown,
          rightControllerSampleValid = sampleValid,
          storeControllerSampleValid = storeSampleValid,
          inputSource = "spatial-sdk",
          storeInputSource = "spatial-sdk-left",
      )
    }
  }
}
