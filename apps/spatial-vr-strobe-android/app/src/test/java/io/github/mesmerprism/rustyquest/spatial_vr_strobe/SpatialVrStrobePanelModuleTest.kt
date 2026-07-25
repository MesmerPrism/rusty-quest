package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import android.view.KeyEvent
import com.meta.spatial.runtime.ButtonBits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialVrStrobePanelModuleTest {
  @Test
  fun panelUsesTheAttendedComfortDistanceAndDedicatedLayer() {
    assertEquals(0.82f, VR_STROBE_PANEL_COMFORT_DISTANCE_METERS)
    assertEquals(100, VR_STROBE_PANEL_LAYER_Z_INDEX)
  }

  @Test
  fun panelDoesNotConsumeTheGlobalRandomizeButton() {
    assertTrue(SpatialVrStrobePanelInputPolicy.consumeAndroidKeyCode(KeyEvent.KEYCODE_BUTTON_A))
    assertFalse(SpatialVrStrobePanelInputPolicy.consumeAndroidKeyCode(KeyEvent.KEYCODE_BUTTON_B))
    val inputButtons = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
    assertFalse(inputButtons and ButtonBits.ButtonA != 0)
  }
}
