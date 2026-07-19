package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import kotlin.random.Random

internal sealed interface VrStrobeStimulusProfile {
  val id: String
  val title: String

  data class Interference(val profile: VrStrobeInterferenceProfile) : VrStrobeStimulusProfile {
    override val id: String
      get() = profile.id

    override val title: String
      get() = profile.title
  }

  data class Temporal(val profile: VrStrobeTemporalProfile) : VrStrobeStimulusProfile {
    override val id: String
      get() = profile.id

    override val title: String
      get() = profile.title
  }
}

internal data class VrStrobeActiveStimulus(
    val profile: VrStrobeStimulusProfile,
    val presetIndex: Int,
    val revision: Long,
)

internal class VrStrobeStimulusSelectionAuthority(
    private val catalog: () -> List<VrStrobePortalPreset> = { VrStrobePresetCatalog.all },
) {
  private var revision = 0L
  private var active: VrStrobeActiveStimulus? = null

  fun snapshot(): VrStrobeActiveStimulus? = active

  fun adopt(profile: VrStrobeInterferenceProfile): VrStrobeActiveStimulus =
      adopt(VrStrobeStimulusProfile.Interference(profile.sanitized()))

  fun adopt(profile: VrStrobeTemporalProfile): VrStrobeActiveStimulus =
      adopt(VrStrobeStimulusProfile.Temporal(profile.sanitized()))

  fun adopt(profile: VrStrobeStimulusProfile): VrStrobeActiveStimulus {
    revision += 1L
    return VrStrobeActiveStimulus(
            profile = profile,
            presetIndex = catalog().indexOfFirst { it.id == profile.id },
            revision = revision,
        )
        .also { active = it }
  }

  fun randomizedCandidate(random: Random = Random.Default): VrStrobeStimulusProfile? =
      when (val current = active?.profile) {
        is VrStrobeStimulusProfile.Interference ->
            VrStrobeStimulusProfile.Interference(current.profile.randomized(random))
        is VrStrobeStimulusProfile.Temporal ->
            VrStrobeStimulusProfile.Temporal(current.profile.randomized(random))
        null -> null
      }

  fun cycleCandidate(direction: Int): VrStrobeStimulusProfile? {
    if (direction == 0) return null
    val values = catalog()
    if (values.isEmpty()) return null
    val step = if (direction < 0) -1 else 1
    val currentIndex = active?.presetIndex ?: -1
    val baseIndex =
        if (currentIndex in values.indices) currentIndex
        else if (step > 0) -1 else 0
    return when (val preset = values[Math.floorMod(baseIndex + step, values.size)]) {
      is VrStrobeInterferencePreset -> VrStrobeStimulusProfile.Interference(preset.profile)
      is VrStrobeTemporalPreset -> VrStrobeStimulusProfile.Temporal(preset.profile)
    }
  }
}
