package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class VrStrobeStimulusSelectionAuthorityTest {
  @Test
  fun randomizationTracksThePresetSelectedByControllerCycle() {
    val authority = VrStrobeStimulusSelectionAuthority()
    val first = VrStrobePresetCatalog.interference.first()
    authority.adopt(first.profile)

    val cycled = assertIs<VrStrobeStimulusProfile.Interference>(authority.cycleCandidate(1))
    val cycledState = authority.adopt(cycled)
    val randomized =
        assertIs<VrStrobeStimulusProfile.Interference>(
            authority.randomizedCandidate(Random(17))
        )
    val randomizedState = authority.adopt(randomized)

    assertEquals(VrStrobePresetCatalog.interference[1].id, randomized.profile.id)
    assertEquals(cycledState.presetIndex, randomizedState.presetIndex)
    assertEquals(cycledState.revision + 1L, randomizedState.revision)
    assertNotEquals(cycled.profile.scale, randomized.profile.scale)
    assertEquals(
        VrStrobePresetCatalog.interference[2].id,
        authority.cycleCandidate(1)?.id,
    )
  }

  @Test
  fun cyclingAndRandomizingAcrossKindsKeepsOneAuthoritativeSelection() {
    val authority = VrStrobeStimulusSelectionAuthority()
    authority.adopt(VrStrobePresetCatalog.interference.last().profile)

    val temporal = assertIs<VrStrobeStimulusProfile.Temporal>(authority.cycleCandidate(1))
    authority.adopt(temporal)
    val randomized = assertIs<VrStrobeStimulusProfile.Temporal>(authority.randomizedCandidate(Random(23)))
    val state = authority.adopt(randomized)

    assertEquals(VrStrobePresetCatalog.temporal.first().id, state.profile.id)
    assertEquals(VrStrobePresetCatalog.interference.size, state.presetIndex)
  }
}
