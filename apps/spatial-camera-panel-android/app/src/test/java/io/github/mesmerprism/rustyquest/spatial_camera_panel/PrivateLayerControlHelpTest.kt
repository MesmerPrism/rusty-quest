package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLayerControlHelpTest {
  @Test
  fun everyRequiredChoiceGroupHasCompactHelpCopy() {
    PrivateLayerControlHelp.requiredGroupLabels.forEach { label ->
      val help = PrivateLayerControlHelp.forLabel(label)
      assertEquals(label, help.title)
      assertTrue("Missing help for $label", help.description.length >= 24)
    }
  }

  @Test
  fun dynamicSliderLabelsResolveToUsefulCopy() {
    listOf(
            "Projection scale",
            "Tile gap",
            "Center–Middle red threshold",
            "Middle–Outer blue cycle speed",
            "Center–Middle green phase (turns)",
            "Left depth X",
            "Depth Y scale",
        )
        .forEach { label ->
          val help = PrivateLayerControlHelp.forLabel(label)
          assertEquals(label, help.title)
          assertTrue("Missing slider help for $label", help.description.length >= 24)
        }
  }
}
