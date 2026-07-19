package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrStrobePanelFlowTest {
  @Test
  fun freshPanelAlwaysStartsAtTheWarning() {
    assertEquals(VrStrobePanelScreen.WARNING, VrStrobePanelFlow.initialScreen())
  }

  @Test
  fun acknowledgementIsRequiredBeforeSelection() {
    assertEquals(
        VrStrobePanelScreen.WARNING,
        VrStrobePanelFlow.afterWarningAcknowledgement(
            VrStrobeSafetySnapshot(VrStrobeSafetyState.READY)
        ),
    )
    assertEquals(
        VrStrobePanelScreen.PORTAL,
        VrStrobePanelFlow.afterWarningAcknowledgement(
            VrStrobeSafetySnapshot(VrStrobeSafetyState.ARMED)
        ),
    )
  }

  @Test
  fun acceptedBeginTransitionsDirectlyToActiveControls() {
    assertEquals(
        VrStrobePanelScreen.ACTIVE,
        VrStrobePanelFlow.afterBegin(
            VrStrobeSafetySnapshot(VrStrobeSafetyState.BLACK_LEAD_IN),
            VrStrobePanelScreen.PORTAL,
        ),
    )
    assertEquals(
        VrStrobePanelScreen.INTERFERENCE,
        VrStrobePanelFlow.afterBegin(
            VrStrobeSafetySnapshot(VrStrobeSafetyState.ARMED),
            VrStrobePanelScreen.INTERFERENCE,
        ),
    )
    assertTrue(
        VrStrobePanelFlow.beginAccepted(
            VrStrobeSafetySnapshot(VrStrobeSafetyState.BLACK_LEAD_IN)
        )
    )
    assertFalse(
        VrStrobePanelFlow.beginAccepted(VrStrobeSafetySnapshot(VrStrobeSafetyState.ARMED))
    )
  }

  @Test
  fun depthSeparatedForegroundKeepsSelectedCarrierActiveBehindPanel() {
    assertTrue(
        VrStrobePanelForegroundPolicy.carrierVisible(stimulusSelected = true)
    )
    assertTrue(
        VrStrobePanelForegroundPolicy.carrierVisible(stimulusSelected = true)
    )
    assertFalse(
        VrStrobePanelForegroundPolicy.carrierVisible(stimulusSelected = false)
    )
  }

  @Test
  fun focusLossStateRequiresTheWarningAgain() {
    assertTrue(
        VrStrobePanelFlow.warningRequired(VrStrobeSafetySnapshot(VrStrobeSafetyState.READY))
    )
    assertFalse(
        VrStrobePanelFlow.warningRequired(VrStrobeSafetySnapshot(VrStrobeSafetyState.ARMED))
    )
  }
}
