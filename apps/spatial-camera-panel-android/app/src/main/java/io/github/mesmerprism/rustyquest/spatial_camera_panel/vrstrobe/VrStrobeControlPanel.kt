package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay

internal data class VrStrobePanelActions(
    val snapshot: () -> VrStrobeSafetySnapshot,
    val acknowledgeWarning: (Boolean) -> VrStrobeSafetySnapshot,
    val beginInterference: (VrStrobeInterferenceProfile) -> VrStrobeSafetySnapshot,
    val beginTemporal: (VrStrobeTemporalProfile) -> VrStrobeSafetySnapshot,
    val randomizeActive: (String) -> VrStrobeSafetySnapshot,
    val toggleCurvedMode: (String) -> VrStrobeSafetySnapshot,
    val storedProfiles: () -> List<VrStrobeStoredProfile>,
    val storeActive: (String) -> VrStrobeStoreResult,
    val loadStoredProfile: (String, String) -> VrStrobeSafetySnapshot,
    val stop: (String) -> VrStrobeSafetySnapshot,
)

@Composable
internal fun VrStrobeControlPanel(actions: VrStrobePanelActions) {
  var screen by remember { mutableStateOf(VrStrobePanelFlow.initialScreen()) }
  var interferenceProfile by remember { mutableStateOf(VrStrobeInterferenceProfile()) }
  var temporalProfile by remember { mutableStateOf(VrStrobeTemporalProfile()) }
  var safetySnapshot by remember { mutableStateOf(actions.snapshot()) }
  var storedProfiles by remember { mutableStateOf(actions.storedProfiles()) }
  var storedReturnScreen by remember { mutableStateOf(VrStrobePanelScreen.PORTAL) }
  var storageStatus by remember { mutableStateOf("none") }
  LaunchedEffect(Unit) {
    while (true) {
      delay(250L)
      safetySnapshot = actions.snapshot()
      storedProfiles = actions.storedProfiles()
      if (
          screen != VrStrobePanelScreen.WARNING &&
              VrStrobePanelFlow.warningRequired(safetySnapshot)
      ) {
        screen = VrStrobePanelScreen.WARNING
      }
    }
  }

  fun beginInterference(profile: VrStrobeInterferenceProfile, fallback: VrStrobePanelScreen) {
    val sanitized = profile.sanitized()
    interferenceProfile = sanitized
    safetySnapshot = actions.beginInterference(sanitized)
    screen = VrStrobePanelFlow.afterBegin(safetySnapshot, fallback)
  }

  fun beginTemporal(profile: VrStrobeTemporalProfile, fallback: VrStrobePanelScreen) {
    val sanitized = profile.sanitized()
    temporalProfile = sanitized
    safetySnapshot = actions.beginTemporal(sanitized)
    screen = VrStrobePanelFlow.afterBegin(safetySnapshot, fallback)
  }

  when (screen) {
    VrStrobePanelScreen.WARNING ->
        WarningGate(
            snapshot = safetySnapshot,
            acknowledge = {
              safetySnapshot = actions.acknowledgeWarning(true)
              screen = VrStrobePanelFlow.afterWarningAcknowledgement(safetySnapshot)
            },
        )
    VrStrobePanelScreen.PORTAL ->
        Portal(
            selectInterference = { beginInterference(it, VrStrobePanelScreen.PORTAL) },
            selectTemporal = { beginTemporal(it, VrStrobePanelScreen.PORTAL) },
            openInterferenceDesigner = {
              interferenceProfile = VrStrobeInterferenceProfile()
              screen = VrStrobePanelScreen.INTERFERENCE
            },
            openTemporalDesigner = {
              temporalProfile = VrStrobeTemporalProfile()
              screen = VrStrobePanelScreen.TEMPORAL
            },
            storedProfileCount = storedProfiles.size,
            openStoredProfiles = {
              storedReturnScreen = VrStrobePanelScreen.PORTAL
              screen = VrStrobePanelScreen.STORED
            },
        )
    VrStrobePanelScreen.STORED ->
        StoredProfiles(
            profiles = storedProfiles,
            load = { stored ->
              safetySnapshot = actions.loadStoredProfile(stored.id, "stored-profile-panel")
              screen =
                  VrStrobePanelFlow.afterBegin(
                      safetySnapshot,
                      VrStrobePanelScreen.STORED,
                  )
            },
            back = { screen = storedReturnScreen },
        )
    VrStrobePanelScreen.INTERFERENCE ->
        InterferenceDesigner(
            profile = interferenceProfile,
            updateProfile = { interferenceProfile = it },
            snapshot = safetySnapshot,
            start = { beginInterference(it, VrStrobePanelScreen.INTERFERENCE) },
            back = { screen = VrStrobePanelScreen.PORTAL },
        )
    VrStrobePanelScreen.TEMPORAL ->
        TemporalDesigner(
            profile = temporalProfile,
            updateProfile = { temporalProfile = it },
            snapshot = safetySnapshot,
            start = { beginTemporal(it, VrStrobePanelScreen.TEMPORAL) },
            back = { screen = VrStrobePanelScreen.PORTAL },
        )
    VrStrobePanelScreen.ACTIVE ->
        ActiveStimulus(
            title = safetySnapshot.profileTitle,
            snapshot = safetySnapshot,
            randomize = {
              safetySnapshot = actions.randomizeActive("active-panel")
            },
            toggleCurvedMode = {
              safetySnapshot = actions.toggleCurvedMode("active-panel")
            },
            store = {
              val result = actions.storeActive("active-panel")
              storageStatus =
                  result.storedProfile?.let { "Saved as ${it.title}" }
                      ?: result.rejectionReason.replace('-', ' ')
              storedProfiles = actions.storedProfiles()
            },
            storageStatus = storageStatus,
            storedProfileCount = storedProfiles.size,
            openStoredProfiles = {
              storedReturnScreen = VrStrobePanelScreen.ACTIVE
              screen = VrStrobePanelScreen.STORED
            },
            stopAndSelect = {
              safetySnapshot = actions.stop("panel-stop-and-select")
              screen =
                  if (safetySnapshot.state == VrStrobeSafetyState.ARMED) {
                    VrStrobePanelScreen.PORTAL
                  } else {
                    VrStrobePanelScreen.WARNING
                  }
            },
            endSession = {
              safetySnapshot = actions.acknowledgeWarning(false)
              screen = VrStrobePanelScreen.WARNING
            },
        )
  }
}

@Composable
private fun WarningGate(
    snapshot: VrStrobeSafetySnapshot,
    acknowledge: () -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { Text("PHOTOSENSITIVITY WARNING", fontWeight = FontWeight.Bold) }
    item {
      Text(
          "Rapidly changing light and high-contrast motion can provoke seizures, migraine, nausea, disorientation, or discomfort. Do not continue if you are photosensitive, unsure, tired, unwell, or using the headset alone."
      )
    }
    item {
      Text(
          "This is experimental visual software, not a medical device or treatment. Stop immediately if you feel uncomfortable. A stimulus keeps running until you press Stop; removing focus or ending the session also suppresses and releases it."
      )
    }
    item {
      Button(
          enabled = snapshot.state != VrStrobeSafetyState.FEATURE_DISABLED,
          onClick = acknowledge,
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("I UNDERSTAND — CONTINUE")
      }
    }
    item {
      Text(
          "Acknowledging only opens stimulus selection. A stimulus starts when you deliberately select its button, after a 500 ms black lead-in."
      )
      if (snapshot.rejectionReason != "none") {
        Text("Status: ${snapshot.rejectionReason.replace('-', ' ')}")
      }
    }
  }
}

@Composable
private fun Portal(
    selectInterference: (VrStrobeInterferenceProfile) -> Unit,
    selectTemporal: (VrStrobeTemporalProfile) -> Unit,
    openInterferenceDesigner: () -> Unit,
    openTemporalDesigner: () -> Unit,
    storedProfileCount: Int,
    openStoredProfiles: () -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Text("VR STROBE", fontWeight = FontWeight.Bold)
      Text("Spatial SDK port of Trevor Hewitt's vr_strobe")
      Spacer(Modifier.height(6.dp))
      Text("SELECTING A STIMULUS STARTS IT immediately after a 500 ms black lead-in.")
      Text("Once started, it runs until you stop it. Right A randomizes, left X stores the current profile, B toggles this panel, horizontal stick flicks change preset, and vertical stick movement changes distance.")
      Text("Preset names below are source attribution labels, not medical or device-equivalence claims.")
    }
    item { SectionTitle("Simulation presets") }
    items(VrStrobePresetCatalog.interference) { preset ->
      PresetButton(preset.title, preset.sourceLabel) { selectInterference(preset.profile) }
    }
    item { SectionTitle("Temporal strobe presets") }
    items(VrStrobePresetCatalog.temporal) { preset ->
      PresetButton(preset.title, preset.sourceLabel) { selectTemporal(preset.profile) }
    }
    item {
      SectionTitle("Stored profiles")
      OutlinedButton(onClick = openStoredProfiles, modifier = Modifier.fillMaxWidth()) {
        Text("OPEN STORED PROFILES ($storedProfileCount)")
      }
    }
    item {
      SectionTitle("Design")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = openInterferenceDesigner) { Text("Interference designer") }
        Button(onClick = openTemporalDesigner) { Text("Strobe designer") }
      }
    }
    item {
      HorizontalDivider()
      Text(
          "Source: ${VrStrobePresetCatalog.UPSTREAM_REPOSITORY} @ ${VrStrobePresetCatalog.UPSTREAM_COMMIT.take(12)}. Port licensed AGPL-3.0-or-later with creator permission."
      )
    }
  }
}

@Composable
private fun StoredProfiles(
    profiles: List<VrStrobeStoredProfile>,
    load: (VrStrobeStoredProfile) -> Unit,
    back: () -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Header("STORED PROFILES", back, "Back")
      Text("Left X stores an exact copy of the active profile. Selecting a stored profile starts it after the 500 ms black lead-in.")
    }
    if (profiles.isEmpty()) {
      item { Text("No stored profiles yet. Start a stimulus and press the right controller trigger.") }
    } else {
      items(profiles, key = { it.id }) { stored ->
        OutlinedButton(onClick = { load(stored) }, modifier = Modifier.fillMaxWidth()) {
          Column(Modifier.fillMaxWidth()) {
            Text(stored.title, fontWeight = FontWeight.Bold)
            Text(
                "${stored.kind.name.lowercase(Locale.US)} · ${"%.2f".format(Locale.US, stored.distanceMeters)} m · " +
                    if (stored.carrierShape.curvedMode) {
                      "curved ${"%.0f".format(Locale.US, stored.carrierShape.concavity * 100f)}%"
                    } else {
                      "flat"
                    }
            )
            Text("LOAD & START")
          }
        }
      }
    }
  }
}

@Composable
private fun PresetButton(title: String, sourceLabel: String, select: () -> Unit) {
  OutlinedButton(onClick = select, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth()) {
      Text(title, fontWeight = FontWeight.Bold)
      Text("Original source label: $sourceLabel")
      Text("SELECT & START")
    }
  }
}

@Composable
private fun InterferenceDesigner(
    profile: VrStrobeInterferenceProfile,
    updateProfile: (VrStrobeInterferenceProfile) -> Unit,
    snapshot: VrStrobeSafetySnapshot,
    start: (VrStrobeInterferenceProfile) -> Unit,
    back: () -> Unit,
) {
  var editingKind by remember { mutableStateOf(VrStrobePatternKind.STRIPE) }
  var editingIndex by remember { mutableIntStateOf(0) }
  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Header("INTERFERENCE DESIGNER", back)
      profile.sourceLabel?.let { Text("Original source label: $it") }
      Text("Editing is inert. START CUSTOM STIMULUS begins after the 500 ms black lead-in.")
    }
    item {
      SectionTitle("Colors and oscillator")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { updateProfile(profile.copy(colorCount = 2)) }) { Text("2 colors") }
        TextButton(onClick = { updateProfile(profile.copy(colorCount = 3)) }) { Text("3 colors") }
      }
      ColorEditor("Color 1", profile.color1) { updateProfile(profile.copy(color1 = it)) }
      ColorEditor("Color 2", profile.color2) { updateProfile(profile.copy(color2 = it)) }
      if (profile.colorCount == 3) {
        ColorEditor("Color 3", profile.color3) { updateProfile(profile.copy(color3 = it)) }
      }
      BooleanControl("Color oscillator", profile.oscillatorActive) {
        updateProfile(profile.copy(oscillatorActive = it))
      }
      FloatControl("Oscillator frequency", profile.oscillatorFrequencyHz, 0f..40f) {
        updateProfile(profile.copy(oscillatorFrequencyHz = it))
      }
      FloatControl("Oscillator shape", profile.oscillatorShape, 0.1f..10f) {
        updateProfile(profile.copy(oscillatorShape = it))
      }
    }
    item {
      SectionTitle("Global transform")
      FloatControl("Scale", profile.scale, 0.1f..100f) { updateProfile(profile.copy(scale = it)) }
      FloatControl("Shear X", profile.shearX, -2f..2f) { updateProfile(profile.copy(shearX = it)) }
      FloatControl("Shear Y", profile.shearY, -2f..2f) { updateProfile(profile.copy(shearY = it)) }
      FloatControl("Offset X", profile.offsetX, -1f..1f) { updateProfile(profile.copy(offsetX = it)) }
      FloatControl("Offset Y", profile.offsetY, -1f..1f) { updateProfile(profile.copy(offsetY = it)) }
      FloatControl("Shake amplitude", profile.shakeAmplitude, 0f..0.1f) {
        updateProfile(profile.copy(shakeAmplitude = it))
      }
      FloatControl("Shake frequency", profile.shakeFrequencyHz, 0f..40f) {
        updateProfile(profile.copy(shakeFrequencyHz = it))
      }
      FloatControl("Rotation speed", profile.rotationSpeed, -5f..5f) {
        updateProfile(profile.copy(rotationSpeed = it))
      }
      FloatControl("Step factor", profile.stepFactor, 0f..1f) {
        updateProfile(profile.copy(stepFactor = it))
      }
    }
    item {
      SectionTitle("Post-processing")
      FloatControl("Trail", profile.trailAmount, 0f..0.99f) { updateProfile(profile.copy(trailAmount = it)) }
      FloatControl("Blur radius", profile.blurRadius, 0f..15f) { updateProfile(profile.copy(blurRadius = it)) }
      FloatControl("Glow", profile.glowStrength, 0f..3f) { updateProfile(profile.copy(glowStrength = it)) }
      FloatControl("Brightness", profile.brightness, -1f..1f) { updateProfile(profile.copy(brightness = it)) }
      FloatControl("Contrast", profile.contrast, 0f..3f) { updateProfile(profile.copy(contrast = it)) }
    }
    item {
      SectionTitle("Noise and vignette")
      FloatControl("Noise frequency", profile.noiseFrequency, 0.1f..5f) { updateProfile(profile.copy(noiseFrequency = it)) }
      FloatControl("Noise strength", profile.noiseStrength, 0f..1f) { updateProfile(profile.copy(noiseStrength = it)) }
      FloatControl("Noise bias", profile.noiseBias, 0f..1f) { updateProfile(profile.copy(noiseBias = it)) }
      FloatControl("Vignette center", profile.vignetteCenter, 0f..5f) { updateProfile(profile.copy(vignetteCenter = it)) }
      FloatControl("Vignette edge", profile.vignetteEdge, 0f..5f) { updateProfile(profile.copy(vignetteEdge = it)) }
      FloatControl("Vignette bias", profile.vignetteBias, 0f..1f) { updateProfile(profile.copy(vignetteBias = it)) }
    }
    item {
      SectionTitle("Pattern layers")
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        VrStrobePatternKind.entries.forEach { kind ->
          TextButton(onClick = { editingKind = kind; editingIndex = 0 }) {
            Text("${kind.name.lowercase().replaceFirstChar(Char::uppercase)} (${profile.patterns(kind).size})")
          }
        }
      }
      PatternEditor(
          profile = profile,
          kind = editingKind,
          requestedIndex = editingIndex,
          setIndex = { editingIndex = it },
          updateProfile = updateProfile,
      )
    }
    item {
      SectionTitle("Profile")
      Text("Runtime: continuous until Stop, focus loss, or session end.")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { updateProfile(VrStrobeInterferenceProfile()) }) { Text("Reset") }
        OutlinedButton(onClick = { updateProfile(profile.randomized()) }) { Text("Randomize") }
      }
      DesignerStartControls(snapshot = snapshot, start = { start(profile.sanitized()) })
    }
  }
}

@Composable
private fun PatternEditor(
    profile: VrStrobeInterferenceProfile,
    kind: VrStrobePatternKind,
    requestedIndex: Int,
    setIndex: (Int) -> Unit,
    updateProfile: (VrStrobeInterferenceProfile) -> Unit,
) {
  val values = profile.patterns(kind)
  if (values.isEmpty()) {
    Button(
        onClick = { updateProfile(profile.copy(patterns = profile.patterns + VrStrobePattern(kind))) }
    ) {
      Text("Add ${kind.name.lowercase()}")
    }
    return
  }
  val index = requestedIndex.coerceIn(0, values.lastIndex)
  val pattern = values[index]
  fun update(transform: (VrStrobePattern) -> VrStrobePattern) {
    var seen = 0
    updateProfile(
        profile.copy(
            patterns =
                profile.patterns.map {
                  if (it.kind == kind && seen++ == index) transform(it) else it
                }
        )
    )
  }
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("${kind.name.lowercase()} ${index + 1}/${values.size}")
    TextButton(onClick = { setIndex((index - 1).coerceAtLeast(0)) }) { Text("Previous") }
    TextButton(onClick = { setIndex((index + 1).coerceAtMost(values.lastIndex)) }) { Text("Next") }
    if (values.size < VR_STROBE_MAX_PATTERN_INSTANCES) {
      TextButton(onClick = { updateProfile(profile.copy(patterns = profile.patterns + VrStrobePattern(kind))); setIndex(values.size) }) { Text("Add") }
    }
    TextButton(
        onClick = {
          var kindIndex = 0
          updateProfile(
              profile.copy(
                  patterns =
                      profile.patterns.filter {
                        val remove = it.kind == kind && kindIndex++ == index
                        !remove
                      }
              )
          )
          setIndex((index - 1).coerceAtLeast(0))
        }
    ) { Text("Remove") }
  }
  BooleanControl("Active", pattern.active) { update { value -> value.copy(active = it) } }
  FloatControl("Strength", pattern.strength, -2f..2f) { update { value -> value.copy(strength = it) } }
  if (kind == VrStrobePatternKind.PERLIN) {
    FloatControl("Scale", pattern.perlinScale, 0.1f..50f) { update { value -> value.copy(perlinScale = it) } }
    FloatControl("Z speed", pattern.perlinZSpeed, -10f..10f) { update { value -> value.copy(perlinZSpeed = it) } }
    FloatControl("Z offset", pattern.perlinZOffset, -100f..100f) { update { value -> value.copy(perlinZOffset = it) } }
    FloatControl("Pivot X", pattern.pivotX, -2f..2f) { update { value -> value.copy(pivotX = it) } }
    FloatControl("Pivot Y", pattern.pivotY, -2f..2f) { update { value -> value.copy(pivotY = it) } }
    return
  }
  FloatControl("Period", pattern.period, if (kind == VrStrobePatternKind.RAY) 1f..50f else 0.1f..50f) { update { value -> value.copy(period = it) } }
  FloatControl("Speed", pattern.speed, -10f..10f) { update { value -> value.copy(speed = it) } }
  FloatControl("Pivot X", pattern.pivotX, -2f..2f) { update { value -> value.copy(pivotX = it) } }
  FloatControl("Pivot Y", pattern.pivotY, -2f..2f) { update { value -> value.copy(pivotY = it) } }
  FloatControl("Distortion frequency", pattern.distortFreq, 0f..20f) { update { value -> value.copy(distortFreq = it) } }
  FloatControl("Distortion amplitude", pattern.distortAmp, 0f..5f) { update { value -> value.copy(distortAmp = it) } }
  FloatControl("Distortion speed", pattern.distortSpeed, -10f..10f) { update { value -> value.copy(distortSpeed = it) } }
  FloatControl("Distortion parallel", pattern.distMultPar, 0f..5f) { update { value -> value.copy(distMultPar = it) } }
  FloatControl("Distortion orthogonal", pattern.distMultOrth, 0f..5f) { update { value -> value.copy(distMultOrth = it) } }
  FloatControl("Wave frequency", pattern.waveFreq, 0f..20f) { update { value -> value.copy(waveFreq = it) } }
  FloatControl("Wave amplitude", pattern.waveAmp, 0f..5f) { update { value -> value.copy(waveAmp = it) } }
  FloatControl("Wave shape", pattern.waveShape, 0f..1f) { update { value -> value.copy(waveShape = it) } }
  if (kind == VrStrobePatternKind.STRIPE) {
    FloatControl("Angle", pattern.angle, 0f..6.28f) { update { value -> value.copy(angle = it) } }
    FloatControl("Rotation speed", pattern.rotationSpeed, -2f..2f) { update { value -> value.copy(rotationSpeed = it) } }
    FloatControl("Extent", pattern.extent, 0f..20f) { update { value -> value.copy(extent = it) } }
  } else {
    FloatControl("Rotation pivot X", pattern.rotationPivotX, -2f..2f) { update { value -> value.copy(rotationPivotX = it) } }
    FloatControl("Rotation pivot Y", pattern.rotationPivotY, -2f..2f) { update { value -> value.copy(rotationPivotY = it) } }
    FloatControl("Rotation speed", pattern.rotationSpeed, -2f..2f) { update { value -> value.copy(rotationSpeed = it) } }
    FloatControl("Noise movement", pattern.noiseMove, 0f..2f) { update { value -> value.copy(noiseMove = it) } }
  }
}

@Composable
private fun TemporalDesigner(
    profile: VrStrobeTemporalProfile,
    updateProfile: (VrStrobeTemporalProfile) -> Unit,
    snapshot: VrStrobeSafetySnapshot,
    start: (VrStrobeTemporalProfile) -> Unit,
    back: () -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Header("TEMPORAL STROBE DESIGNER", back)
      profile.sourceLabel?.let { Text("Original source label: $it") }
      Text("Editing is inert. START CUSTOM STIMULUS begins after the 500 ms black lead-in.")
    }
    item {
      SectionTitle("Colors and timing")
      ColorEditor("Color 1", profile.color1) { updateProfile(profile.copy(color1 = it)) }
      ColorEditor("Color 2", profile.color2) { updateProfile(profile.copy(color2 = it)) }
      FloatControl("Frequency Hz", profile.frequencyHz, 0.1f..120f) { updateProfile(profile.copy(frequencyHz = it)) }
      FloatControl("Duty percent", profile.dutyPercent, 1f..99f) { updateProfile(profile.copy(dutyPercent = it)) }
      Text("Runtime: continuous until Stop, focus loss, or session end.")
    }
    item {
      SectionTitle("Noise")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { updateProfile(profile.copy(noiseType = VrStrobeNoiseType.WHITE)) }) { Text("White") }
        TextButton(onClick = { updateProfile(profile.copy(noiseType = VrStrobeNoiseType.PERLIN)) }) { Text("Perlin") }
      }
      IntControl("Noise resolution", profile.noiseResolution, 1..50) { updateProfile(profile.copy(noiseResolution = it)) }
      BooleanControl("Noise in phase 1", profile.noisePhase1) { updateProfile(profile.copy(noisePhase1 = it)) }
      FloatControl("Phase 1 noise amplitude", profile.noiseAmplitude1, 0f..1f) { updateProfile(profile.copy(noiseAmplitude1 = it)) }
      BooleanControl("Noise in phase 2", profile.noisePhase2) { updateProfile(profile.copy(noisePhase2 = it)) }
      FloatControl("Phase 2 noise amplitude", profile.noiseAmplitude2, 0f..1f) { updateProfile(profile.copy(noiseAmplitude2 = it)) }
    }
    item {
      SectionTitle("Fixation")
      BooleanControl("Fixation cross", profile.fixationEnabled) { updateProfile(profile.copy(fixationEnabled = it)) }
      ColorEditor("Fixation color", profile.fixationColor) { updateProfile(profile.copy(fixationColor = it)) }
      IntControl("Fixation size", profile.fixationSize, 2..100) { updateProfile(profile.copy(fixationSize = it)) }
    }
    item {
      OutlinedButton(onClick = { updateProfile(VrStrobeTemporalProfile()) }) { Text("Reset") }
      DesignerStartControls(snapshot = snapshot, start = { start(profile.sanitized()) })
    }
  }
}

@Composable
private fun DesignerStartControls(
    snapshot: VrStrobeSafetySnapshot,
    start: () -> Unit,
) {
  HorizontalDivider(Modifier.padding(vertical = 8.dp))
  Text("CUSTOM STIMULUS", fontWeight = FontWeight.Bold)
  if (snapshot.rejectionReason != "none") Text("Status: ${snapshot.rejectionReason.replace('-', ' ')}")
  Button(
      enabled = snapshot.state == VrStrobeSafetyState.ARMED,
      onClick = start,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text("START CUSTOM STIMULUS")
  }
  Text("Editing and designer navigation never start output.")
}

@Composable
private fun ActiveStimulus(
    title: String,
    snapshot: VrStrobeSafetySnapshot,
    randomize: () -> Unit,
    toggleCurvedMode: () -> Unit,
    store: () -> Unit,
    storageStatus: String,
    storedProfileCount: Int,
    openStoredProfiles: () -> Unit,
    stopAndSelect: () -> Unit,
    endSession: () -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Text("STIMULUS ACTIVE", fontWeight = FontWeight.Bold)
      Text(title)
      Text(
          "State: ${snapshot.state.name.lowercase(Locale.US).replace('_', ' ')} · elapsed ${"%.1f".format(Locale.US, snapshot.elapsedSeconds)} s"
      )
      Text("Distance: ${"%.2f".format(Locale.US, snapshot.distanceMeters)} m · no automatic time limit")
      Text(
          if (snapshot.curvedMode) {
            "Carrier: curved · concavity ${"%.0f".format(Locale.US, snapshot.concavity * 100f)}% · arc ${"%.0f".format(Locale.US, snapshot.carrierArcDegrees)}°"
          } else {
            "Carrier: flat"
          }
      )
      if (snapshot.selectedPresetIndex >= 0) {
        Text(
            "Preset ${snapshot.selectedPresetIndex + 1}/${VrStrobePresetCatalog.all.size} · stimulus revision ${snapshot.stimulusRevision}"
        )
      }
      if (snapshot.rejectionReason != "none") {
        Text("Status: ${snapshot.rejectionReason.replace('-', ' ')}")
      }
    }
    item {
      Button(
          enabled = snapshot.randomizeAvailable,
          onClick = randomize,
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("RANDOMIZE — RIGHT A")
      }
    }
    item {
      OutlinedButton(onClick = toggleCurvedMode, modifier = Modifier.fillMaxWidth()) {
        Text(if (snapshot.curvedMode) "USE FLAT CARRIER" else "USE CURVED CARRIER")
      }
      Text(
          if (snapshot.curvedMode) {
            "Left stick up/down: more/less concavity · right stick up/down: farther/nearer"
          } else {
            "Either stick up/down: farther/nearer"
          }
      )
    }
    item {
      OutlinedButton(onClick = store, modifier = Modifier.fillMaxWidth()) {
        Text("STORE CURRENT — LEFT X")
      }
      if (storageStatus != "none") Text(storageStatus)
      TextButton(onClick = openStoredProfiles, modifier = Modifier.fillMaxWidth()) {
        Text("STORED PROFILES ($storedProfileCount)")
      }
    }
    item {
      Button(onClick = stopAndSelect, modifier = Modifier.fillMaxWidth()) {
        Text("STOP AND CHOOSE ANOTHER")
      }
      Text("Either stick left/right: previous/next preset · left X: store · B: hide/show panel")
    }
    item {
      TextButton(onClick = endSession, modifier = Modifier.fillMaxWidth()) {
        Text("END SESSION — SHOW WARNING AGAIN")
      }
      Text("Focus loss also ends the acknowledged session and returns to the warning.")
    }
  }
}

@Composable
private fun Header(title: String, back: () -> Unit, backLabel: String = "Back to presets") {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, fontWeight = FontWeight.Bold)
    TextButton(onClick = back) { Text(backLabel) }
  }
}

@Composable
private fun SectionTitle(title: String) {
  Text(title.uppercase(Locale.US), fontWeight = FontWeight.Bold)
}

@Composable
private fun BooleanControl(label: String, value: Boolean, update: (Boolean) -> Unit) {
  Row {
    Checkbox(checked = value, onCheckedChange = update)
    Text(label)
  }
}

@Composable
private fun FloatControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
  Column {
    Text("$label: ${"%.3f".format(Locale.US, value)}")
    Slider(value = value.coerceIn(range), onValueChange = update, valueRange = range)
  }
}

@Composable
private fun IntControl(label: String, value: Int, range: IntRange, update: (Int) -> Unit) {
  FloatControl(label, value.toFloat(), range.first.toFloat()..range.last.toFloat()) { update(it.toInt().coerceIn(range)) }
}

@Composable
private fun ColorEditor(label: String, value: VrStrobeColor, update: (VrStrobeColor) -> Unit) {
  var text by remember(value) { mutableStateOf(value.hex()) }
  OutlinedTextField(
      value = text,
      onValueChange = {
        text = it
        if (it.trim().removePrefix("#").length == 6) update(VrStrobeColor.of(it, value))
      },
      label = { Text(label) },
      singleLine = true,
  )
}
