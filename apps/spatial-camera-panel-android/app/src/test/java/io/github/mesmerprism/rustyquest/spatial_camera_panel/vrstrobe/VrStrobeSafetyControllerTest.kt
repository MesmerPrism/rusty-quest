package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrStrobeSafetyControllerTest {
  @Test
  fun disabledFeatureCannotBeArmedOrStarted() {
    val controller = VrStrobeSafetyController(featureEnabled = false)

    assertEquals(VrStrobeSafetyState.FEATURE_DISABLED, controller.acknowledgeWarning(true).state)
    val begin = controller.begin(VrStrobeOutputKind.TEMPORAL, "test", 100L)
    assertEquals(VrStrobeSafetyState.FEATURE_DISABLED, begin.state)
    assertEquals("feature-disabled", begin.rejectionReason)
  }

  @Test
  fun presetCannotStartWithoutFreshAcknowledgement() {
    val controller = VrStrobeSafetyController(featureEnabled = true)

    val begin = controller.begin(VrStrobeOutputKind.INTERFERENCE, "preset", 100L)

    assertEquals(VrStrobeSafetyState.READY, begin.state)
    assertFalse(begin.visualOutputActive)
    assertEquals("fresh-warning-acknowledgement-required", begin.rejectionReason)
  }

  @Test
  fun beginUsesBlackLeadInThenRunsUntilExplicitStop() {
    val controller = VrStrobeSafetyController(featureEnabled = true)
    controller.acknowledgeWarning(true)

    val beginning = controller.begin(VrStrobeOutputKind.TEMPORAL, "preset", 1_000L)
    assertEquals(VrStrobeSafetyState.BLACK_LEAD_IN, beginning.state)
    assertTrue(beginning.blackCarrierRequired)
    assertFalse(beginning.visualOutputActive)

    assertEquals(VrStrobeSafetyState.BLACK_LEAD_IN, controller.tick(1_499L).state)
    assertEquals(VrStrobeSafetyState.RUNNING, controller.tick(1_500L).state)
    assertTrue(controller.tick(3_500L).visualOutputActive)
    assertEquals(VrStrobeSafetyState.RUNNING, controller.tick(86_400_000L).state)
    assertFalse(controller.tick(86_400_000L).automaticTimeLimit)
  }

  @Test
  fun activeRunHasNoPauseStateAndElapsedTimeContinues() {
    val controller = VrStrobeSafetyController(featureEnabled = true)
    controller.acknowledgeWarning(true)
    controller.begin(VrStrobeOutputKind.INTERFERENCE, "preset", 0L)
    controller.tick(500L)

    val running = controller.tick(3_000L)
    assertEquals(VrStrobeSafetyState.RUNNING, running.state)
    assertEquals(2.5f, running.elapsedSeconds)
    assertTrue(running.visualOutputActive)
    assertFalse(running.blackCarrierRequired)
    assertFalse(VrStrobeSafetyState.entries.any { it.name == "PAUSED" })
  }

  @Test
  fun focusLossReturnsToReadyAndClearsRun() {
    val controller = VrStrobeSafetyController(featureEnabled = true)
    controller.acknowledgeWarning(true)
    controller.begin(VrStrobeOutputKind.TEMPORAL, "preset", 0L)

    val stopped = controller.focusLost(200L)

    assertEquals(VrStrobeSafetyState.READY, stopped.state)
    assertEquals("none", stopped.profileId)
    assertFalse(stopped.visualOutputActive)
    assertEquals("focus-lost", stopped.rejectionReason)
  }

  @Test
  fun explicitStopKeepsTheAcknowledgedSessionArmedForSelection() {
    val controller = VrStrobeSafetyController(featureEnabled = true)
    controller.acknowledgeWarning(true)
    controller.begin(VrStrobeOutputKind.INTERFERENCE, "first", 0L)

    val stopped = controller.stop("panel-stop", 200L)

    assertEquals(VrStrobeSafetyState.ARMED, stopped.state)
    assertEquals("none", stopped.profileId)
    assertFalse(stopped.visualOutputActive)
    assertEquals(
        VrStrobeSafetyState.BLACK_LEAD_IN,
        controller.begin(VrStrobeOutputKind.TEMPORAL, "second", 300L).state,
    )
  }

  @Test
  fun activeRunMustBeStoppedBeforeAnotherSelection() {
    val controller = VrStrobeSafetyController(featureEnabled = true)
    controller.acknowledgeWarning(true)
    controller.begin(VrStrobeOutputKind.INTERFERENCE, "first", 0L)
    controller.tick(500L)

    val rejected = controller.begin(VrStrobeOutputKind.TEMPORAL, "second", 2_000L)

    assertEquals(VrStrobeSafetyState.RUNNING, rejected.state)
    assertEquals("stimulus-selection-requires-armed-session", rejected.rejectionReason)
  }

  @Test
  fun withdrawingAcknowledgementStopsAndClearsTheRun() {
    val controller = VrStrobeSafetyController(featureEnabled = true)
    controller.acknowledgeWarning(true)
    controller.begin(VrStrobeOutputKind.TEMPORAL, "preset", 0L)
    controller.tick(500L)

    val withdrawn = controller.acknowledgeWarning(false)

    assertEquals(VrStrobeSafetyState.READY, withdrawn.state)
    assertEquals("none", withdrawn.profileId)
    assertFalse(withdrawn.visualOutputActive)
  }

  @Test
  fun acknowledgementCannotBeReassertedDuringAnActiveRun() {
    val controller = VrStrobeSafetyController(featureEnabled = true)
    controller.acknowledgeWarning(true)
    controller.begin(VrStrobeOutputKind.TEMPORAL, "preset", 0L)
    controller.tick(500L)

    val repeated = controller.acknowledgeWarning(true)

    assertEquals(VrStrobeSafetyState.RUNNING, repeated.state)
    assertEquals("acknowledgement-not-allowed-during-run", repeated.rejectionReason)
    assertTrue(repeated.visualOutputActive)
  }
}
