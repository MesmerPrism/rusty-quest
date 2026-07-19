package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VrStrobeFeatureRouteTest {
  @Test
  fun missingFeaturePropertyIsInert() {
    val decision = VrStrobeFeatureRoute.resolve { "" }

    assertFalse(decision.enabled)
    assertEquals("missing-or-invalid-enable", decision.reason)
  }

  @Test
  fun onlyExplicitTrueEnablesThePanel() {
    assertTrue(VrStrobeFeatureRoute.resolve { "true" }.enabled)
    assertFalse(VrStrobeFeatureRoute.resolve { "perhaps" }.enabled)
  }

  @Test
  fun activationMarkerRequiresWarningFirstAndUsesSelectionAsTheBeginGesture() {
    val marker = VrStrobeFeatureRoute.activationMarker(VrStrobeFeatureDecision(true, "test"))

    assertTrue(marker.contains("defaultActivation=disabled"))
    assertTrue(marker.contains("autostart=false"))
    assertTrue(marker.contains("restoredStateMayStart=false"))
    assertTrue(marker.contains("warningScreenFirst=true"))
    assertTrue(marker.contains("warningAcknowledgementScope=focused-app-session"))
    assertTrue(marker.contains("presetSelectionIsBeginGesture=true"))
    assertTrue(marker.contains("currentRunBeginGestureRequired=true"))
  }
}
