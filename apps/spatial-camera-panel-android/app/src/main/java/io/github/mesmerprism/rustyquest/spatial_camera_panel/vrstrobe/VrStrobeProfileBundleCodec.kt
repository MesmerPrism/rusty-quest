package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Human-readable, browser-compatible profile interchange. */
internal object VrStrobeProfileBundleCodec {
  const val SCHEMA = "rusty.quest.spatial_vr_strobe.profile_bundle.v1"
  const val FORMAT_VERSION = 1
  const val MAX_PROFILES = 512
  private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  fun encode(profiles: List<VrStrobeStoredProfile>): String {
    require(profiles.size <= MAX_PROFILES) { "profile-bundle-count-invalid" }
    val root = JsonObject()
    root.addProperty("schema", SCHEMA)
    root.addProperty("format_version", FORMAT_VERSION)
    root.addProperty("profile_count", profiles.size)
    root.add("profiles", JsonArray().apply { profiles.forEach { add(it.toJson()) } })
    return gson.toJson(root) + "\n"
  }

  fun decode(payload: String): List<VrStrobeStoredProfile> {
    val root = JsonParser.parseString(payload).requiredObject("profile-bundle-root-invalid")
    require(root.string("schema") == SCHEMA) { "profile-bundle-schema-unsupported" }
    require(root.int("format_version") == FORMAT_VERSION) {
      "profile-bundle-version-unsupported"
    }
    val profilesJson = root.array("profiles")
    val declaredCount = root.int("profile_count")
    require(declaredCount == profilesJson.size() && declaredCount in 0..MAX_PROFILES) {
      "profile-bundle-count-invalid"
    }
    val profiles = profilesJson.map { it.requiredObject("profile-envelope-invalid").toStoredProfile() }
    require(profiles.map { it.id }.distinct().size == profiles.size) {
      "profile-bundle-duplicate-id"
    }
    return profiles
  }

  private fun VrStrobeStoredProfile.toJson(): JsonObject =
      JsonObject().apply {
        addProperty("id", id)
        addProperty("title", title)
        addProperty("created_at_epoch_ms", createdAtEpochMs)
        addProperty("distance_meters", distanceMeters)
        add(
            "carrier",
            JsonObject().apply {
              addProperty("curved_mode", carrierShape.curvedMode)
              addProperty("concavity", carrierShape.concavity)
            },
        )
        when (val stimulus = profile) {
          is VrStrobeStimulusProfile.Interference -> {
            addProperty("kind", "interference")
            add("profile", stimulus.profile.toJson())
          }
          is VrStrobeStimulusProfile.Temporal -> {
            addProperty("kind", "temporal")
            add("profile", stimulus.profile.toJson())
          }
        }
      }

  private fun JsonObject.toStoredProfile(): VrStrobeStoredProfile {
    val id = boundedIdentity(string("id"))
    val title = boundedTitle(string("title"))
    val distanceMeters = float("distance_meters")
    require(distanceMeters in VrStrobeDistancePolicy.MIN_METERS..VrStrobeDistancePolicy.MAX_METERS) {
      "profile-bundle-distance-invalid"
    }
    val carrierJson = obj("carrier")
    val carrierShape =
        VrStrobeCarrierShapeState(
            curvedMode = carrierJson.boolean("curved_mode"),
            concavity = carrierJson.float("concavity"),
        )
    require(carrierShape == carrierShape.sanitized()) { "profile-bundle-carrier-invalid" }
    val profileJson = obj("profile")
    val profile =
        when (string("kind")) {
          "interference" ->
              VrStrobeStimulusProfile.Interference(profileJson.toInterferenceProfile())
          "temporal" -> VrStrobeStimulusProfile.Temporal(profileJson.toTemporalProfile())
          else -> error("profile-bundle-kind-unsupported")
        }
    require(profile.id == id && profile.title == title) { "profile-bundle-envelope-mismatch" }
    return VrStrobeStoredProfile(
        id = id,
        title = title,
        createdAtEpochMs = long("created_at_epoch_ms").coerceAtLeast(0L),
        distanceMeters = distanceMeters,
        carrierShape = carrierShape,
        profile = profile,
    )
  }

  private fun VrStrobeInterferenceProfile.toJson(): JsonObject =
      JsonObject().apply {
        addIdentity(this@toJson.id, title, sourceLabel, durationSeconds)
        sourcePayload?.let { addProperty("source_payload", it) }
        addProperty("color_count", colorCount)
        addProperty("color_1", color1.hex())
        addProperty("color_2", color2.hex())
        addProperty("color_3", color3.hex())
        addProperty("oscillator_active", oscillatorActive)
        addProperty("oscillator_frequency_hz", oscillatorFrequencyHz)
        addProperty("oscillator_shape", oscillatorShape)
        addProperty("scale", scale)
        addProperty("shear_x", shearX)
        addProperty("shear_y", shearY)
        addProperty("offset_x", offsetX)
        addProperty("offset_y", offsetY)
        addProperty("shake_amplitude", shakeAmplitude)
        addProperty("shake_frequency_hz", shakeFrequencyHz)
        addProperty("rotation_speed", rotationSpeed)
        addProperty("step_factor", stepFactor)
        addProperty("trail_amount", trailAmount)
        addProperty("blur_radius", blurRadius)
        addProperty("glow_strength", glowStrength)
        addProperty("brightness", brightness)
        addProperty("contrast", contrast)
        addProperty("noise_frequency", noiseFrequency)
        addProperty("noise_strength", noiseStrength)
        addProperty("noise_bias", noiseBias)
        addProperty("vignette_center", vignetteCenter)
        addProperty("vignette_edge", vignetteEdge)
        addProperty("vignette_bias", vignetteBias)
        add("patterns", JsonArray().apply { patterns.forEach { add(it.toJson()) } })
      }

  private fun JsonObject.toInterferenceProfile(): VrStrobeInterferenceProfile {
    val raw =
        VrStrobeInterferenceProfile(
            id = boundedIdentity(string("id")),
            title = boundedTitle(string("title")),
            sourceLabel = nullableString("source_label"),
            sourcePayload = nullableString("source_payload"),
            durationSeconds = float("duration_seconds"),
            colorCount = int("color_count"),
            color1 = color("color_1"),
            color2 = color("color_2"),
            color3 = color("color_3"),
            oscillatorActive = boolean("oscillator_active"),
            oscillatorFrequencyHz = float("oscillator_frequency_hz"),
            oscillatorShape = float("oscillator_shape"),
            scale = float("scale"),
            shearX = float("shear_x"),
            shearY = float("shear_y"),
            offsetX = float("offset_x"),
            offsetY = float("offset_y"),
            shakeAmplitude = float("shake_amplitude"),
            shakeFrequencyHz = float("shake_frequency_hz"),
            rotationSpeed = float("rotation_speed"),
            stepFactor = float("step_factor"),
            trailAmount = float("trail_amount"),
            blurRadius = float("blur_radius"),
            glowStrength = float("glow_strength"),
            brightness = float("brightness"),
            contrast = float("contrast"),
            noiseFrequency = float("noise_frequency"),
            noiseStrength = float("noise_strength"),
            noiseBias = float("noise_bias"),
            vignetteCenter = float("vignette_center"),
            vignetteEdge = float("vignette_edge"),
            vignetteBias = float("vignette_bias"),
            patterns = array("patterns").map { it.requiredObject("profile-pattern-invalid").toPattern() },
        )
    require(raw == raw.sanitized()) { "profile-bundle-interference-out-of-bounds" }
    return raw
  }

  private fun VrStrobeTemporalProfile.toJson(): JsonObject =
      JsonObject().apply {
        addIdentity(this@toJson.id, title, sourceLabel, durationSeconds)
        addProperty("color_1", color1.hex())
        addProperty("color_2", color2.hex())
        addProperty("frequency_hz", frequencyHz)
        addProperty("duty_percent", dutyPercent)
        addProperty("noise_type", noiseType.name.lowercase())
        addProperty("noise_resolution", noiseResolution)
        addProperty("noise_phase_1", noisePhase1)
        addProperty("noise_amplitude_1", noiseAmplitude1)
        addProperty("noise_phase_2", noisePhase2)
        addProperty("noise_amplitude_2", noiseAmplitude2)
        addProperty("fixation_enabled", fixationEnabled)
        addProperty("fixation_color", fixationColor.hex())
        addProperty("fixation_size", fixationSize)
      }

  private fun JsonObject.toTemporalProfile(): VrStrobeTemporalProfile {
    val raw =
        VrStrobeTemporalProfile(
            id = boundedIdentity(string("id")),
            title = boundedTitle(string("title")),
            sourceLabel = nullableString("source_label"),
            durationSeconds = float("duration_seconds"),
            color1 = color("color_1"),
            color2 = color("color_2"),
            frequencyHz = float("frequency_hz"),
            dutyPercent = float("duty_percent"),
            noiseType =
                when (string("noise_type")) {
                  "white" -> VrStrobeNoiseType.WHITE
                  "perlin" -> VrStrobeNoiseType.PERLIN
                  else -> error("profile-bundle-noise-type-unsupported")
                },
            noiseResolution = int("noise_resolution"),
            noisePhase1 = boolean("noise_phase_1"),
            noiseAmplitude1 = float("noise_amplitude_1"),
            noisePhase2 = boolean("noise_phase_2"),
            noiseAmplitude2 = float("noise_amplitude_2"),
            fixationEnabled = boolean("fixation_enabled"),
            fixationColor = color("fixation_color"),
            fixationSize = int("fixation_size"),
        )
    require(raw == raw.sanitized()) { "profile-bundle-temporal-out-of-bounds" }
    return raw
  }

  private fun VrStrobePattern.toJson(): JsonObject =
      JsonObject().apply {
        addProperty("kind", kind.name.lowercase())
        addProperty("active", active)
        addProperty("strength", strength)
        addProperty("period", period)
        addProperty("speed", speed)
        addProperty("pivot_x", pivotX)
        addProperty("pivot_y", pivotY)
        addProperty("distort_freq", distortFreq)
        addProperty("distort_amp", distortAmp)
        addProperty("distort_speed", distortSpeed)
        addProperty("dist_mult_parallel", distMultPar)
        addProperty("dist_mult_orthogonal", distMultOrth)
        addProperty("wave_freq", waveFreq)
        addProperty("wave_amp", waveAmp)
        addProperty("wave_shape", waveShape)
        addProperty("angle", angle)
        addProperty("rotation_pivot_x", rotationPivotX)
        addProperty("rotation_pivot_y", rotationPivotY)
        addProperty("rotation_speed", rotationSpeed)
        addProperty("extent", extent)
        addProperty("noise_move", noiseMove)
        addProperty("perlin_scale", perlinScale)
        addProperty("perlin_z_speed", perlinZSpeed)
        addProperty("perlin_z_offset", perlinZOffset)
      }

  private fun JsonObject.toPattern(): VrStrobePattern {
    val raw =
        VrStrobePattern(
            kind =
                VrStrobePatternKind.entries.firstOrNull { it.name.lowercase() == string("kind") }
                    ?: error("profile-bundle-pattern-kind-unsupported"),
            active = boolean("active"),
            strength = float("strength"),
            period = float("period"),
            speed = float("speed"),
            pivotX = float("pivot_x"),
            pivotY = float("pivot_y"),
            distortFreq = float("distort_freq"),
            distortAmp = float("distort_amp"),
            distortSpeed = float("distort_speed"),
            distMultPar = float("dist_mult_parallel"),
            distMultOrth = float("dist_mult_orthogonal"),
            waveFreq = float("wave_freq"),
            waveAmp = float("wave_amp"),
            waveShape = float("wave_shape"),
            angle = float("angle"),
            rotationPivotX = float("rotation_pivot_x"),
            rotationPivotY = float("rotation_pivot_y"),
            rotationSpeed = float("rotation_speed"),
            extent = float("extent"),
            noiseMove = float("noise_move"),
            perlinScale = float("perlin_scale"),
            perlinZSpeed = float("perlin_z_speed"),
            perlinZOffset = float("perlin_z_offset"),
        )
    require(raw == raw.sanitized()) { "profile-bundle-pattern-out-of-bounds" }
    return raw
  }

  private fun JsonObject.addIdentity(
      id: String,
      title: String,
      sourceLabel: String?,
      durationSeconds: Float,
  ) {
    addProperty("id", id)
    addProperty("title", title)
    sourceLabel?.let { addProperty("source_label", it) }
    addProperty("duration_seconds", durationSeconds)
  }

  private fun boundedIdentity(value: String): String =
      value.also {
        require(it.length in 1..128 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) {
          "profile-bundle-id-invalid"
        }
      }

  private fun boundedTitle(value: String): String =
      value.also { require(it.isNotBlank() && it.length <= 160) { "profile-bundle-title-invalid" } }

  private fun JsonElement.requiredObject(message: String): JsonObject =
      takeIf { it.isJsonObject }?.asJsonObject ?: error(message)

  private fun JsonObject.element(name: String): JsonElement =
      get(name)?.takeUnless { it.isJsonNull } ?: error("profile-bundle-field-missing-$name")

  private fun JsonObject.obj(name: String): JsonObject =
      element(name).requiredObject("profile-bundle-object-invalid-$name")

  private fun JsonObject.array(name: String): JsonArray =
      element(name).takeIf { it.isJsonArray }?.asJsonArray
          ?: error("profile-bundle-array-invalid-$name")

  private fun JsonObject.string(name: String): String = element(name).asString
  private fun JsonObject.nullableString(name: String): String? =
      get(name)?.takeUnless { it.isJsonNull }?.asString
  private fun JsonObject.boolean(name: String): Boolean = element(name).asBoolean
  private fun JsonObject.int(name: String): Int = element(name).asInt
  private fun JsonObject.long(name: String): Long = element(name).asLong
  private fun JsonObject.float(name: String): Float =
      element(name).asFloat.also { require(it.isFinite()) { "profile-bundle-number-invalid-$name" } }
  private fun JsonObject.color(name: String): VrStrobeColor =
      VrStrobeColor.of(string(name)).also {
        require(it.hex().equals(string(name), ignoreCase = true)) { "profile-bundle-color-invalid-$name" }
      }
}
