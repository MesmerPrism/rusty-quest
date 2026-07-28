package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VrStrobeFeatureRouteTest {
  @Test
  fun standaloneApplicationModuleSelectsTheWarningGatedPanel() {
    val decision = VrStrobeFeatureRoute.resolve()

    assertTrue(decision.enabled)
    assertEquals("standalone-application-module", decision.reason)
  }

  @Test
  fun activationMarkerRequiresWarningFirstAndUsesSelectionAsTheBeginGesture() {
    val marker = VrStrobeFeatureRoute.activationMarker(VrStrobeFeatureDecision(true, "test"))

    assertTrue(marker.contains("activationAuthority=application-module"))
    assertTrue(marker.contains("autostart=false"))
    assertTrue(marker.contains("restoredStateMayStart=false"))
    assertTrue(marker.contains("warningScreenFirst=true"))
    assertTrue(marker.contains("warningAcknowledgementScope=focused-app-session"))
    assertTrue(marker.contains("presetSelectionIsBeginGesture=true"))
    assertTrue(marker.contains("currentRunBeginGestureRequired=true"))
  }
}
