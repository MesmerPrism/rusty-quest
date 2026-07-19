package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

internal data class VrStrobeStoredProfile(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val distanceMeters: Float,
    val carrierShape: VrStrobeCarrierShapeState = VrStrobeCarrierShapeState(),
    val profile: VrStrobeStimulusProfile,
) {
  val kind: VrStrobeOutputKind
    get() =
        when (profile) {
          is VrStrobeStimulusProfile.Interference -> VrStrobeOutputKind.INTERFERENCE
          is VrStrobeStimulusProfile.Temporal -> VrStrobeOutputKind.TEMPORAL
        }
}

internal data class VrStrobeStoredProfileBindings(
    val readPayload: () -> String?,
    val writePayload: (String) -> Boolean,
    val readImportBundlePayload: () -> String? = { null },
    val clearImportBundlePayload: () -> Boolean = { true },
    val writeExportBundlePayload: (String) -> Boolean = { true },
    val wallClockNowMs: () -> Long = System::currentTimeMillis,
)

internal data class VrStrobeStoreResult(
    val storedProfile: VrStrobeStoredProfile? = null,
    val rejectionReason: String = "none",
    val exportStatus: String = "not-attempted",
)

internal class VrStrobeStoredProfileAuthority(
    private val bindings: VrStrobeStoredProfileBindings,
) {
  private var profiles: List<VrStrobeStoredProfile>
  val loadStatus: String
  var exportStatus: String = "not-attempted"
    private set

  init {
    val payload = runCatching(bindings.readPayload).getOrNull()
    val decoded =
        if (payload.isNullOrBlank()) Result.success(emptyList())
        else runCatching { VrStrobeStoredProfileCodec.decode(payload) }
    profiles = decoded.getOrDefault(emptyList())
    var resolvedLoadStatus = if (decoded.isSuccess) "ready" else "corrupt-payload-ignored"
    val importPayload = runCatching(bindings.readImportBundlePayload).getOrNull()
    if (!importPayload.isNullOrBlank()) {
      val imported = runCatching { VrStrobeProfileBundleCodec.decode(importPayload) }
      if (imported.isSuccess && persist(imported.getOrThrow())) {
        profiles = imported.getOrThrow()
        val cleared = runCatching(bindings.clearImportBundlePayload).getOrDefault(false)
        resolvedLoadStatus = if (cleared) "imported" else "imported-staging-clear-failed"
      } else {
        resolvedLoadStatus = "import-rejected"
      }
    }
    loadStatus = resolvedLoadStatus
    publishExport(profiles)
  }

  fun snapshot(): List<VrStrobeStoredProfile> = profiles

  fun find(id: String): VrStrobeStoredProfile? = profiles.firstOrNull { it.id == id }

  fun store(
      active: VrStrobeStimulusProfile,
      distanceMeters: Float = VrStrobeDistancePolicy.DEFAULT_METERS,
      carrierShape: VrStrobeCarrierShapeState = VrStrobeCarrierShapeState(),
  ): VrStrobeStoreResult {
    val createdAtMs = bindings.wallClockNowMs().coerceAtLeast(0L)
    val id = uniqueId(createdAtMs)
    val baseTitle = active.title.substringAfter(" · ", active.title)
    val title = "Saved %02d · %s".format(profiles.size + 1, baseTitle)
    val storedStimulus =
        when (active) {
          is VrStrobeStimulusProfile.Interference ->
              VrStrobeStimulusProfile.Interference(
                  active.profile.copy(id = id, title = title).sanitized()
              )
          is VrStrobeStimulusProfile.Temporal ->
              VrStrobeStimulusProfile.Temporal(
                  active.profile.copy(id = id, title = title).sanitized()
              )
        }
    val stored =
        VrStrobeStoredProfile(
            id = id,
            title = title,
            createdAtEpochMs = createdAtMs,
            distanceMeters =
                distanceMeters.coerceIn(
                    VrStrobeDistancePolicy.MIN_METERS,
                    VrStrobeDistancePolicy.MAX_METERS,
                ),
            carrierShape = carrierShape.sanitized(),
            profile = storedStimulus,
        )
    val candidate = listOf(stored) + profiles
    if (!persist(candidate)) {
      return VrStrobeStoreResult(rejectionReason = "stored-profile-persist-failed")
    }
    profiles = candidate
    publishExport(candidate)
    return VrStrobeStoreResult(storedProfile = stored, exportStatus = exportStatus)
  }

  fun replaceFromBundle(payload: String): Boolean {
    val candidate = runCatching { VrStrobeProfileBundleCodec.decode(payload) }.getOrNull()
        ?: return false
    if (!persist(candidate)) return false
    profiles = candidate
    publishExport(candidate)
    return true
  }

  fun exportBundle(): String = VrStrobeProfileBundleCodec.encode(profiles)

  private fun persist(candidate: List<VrStrobeStoredProfile>): Boolean {
    val payload = runCatching { VrStrobeStoredProfileCodec.encode(candidate) }.getOrNull()
        ?: return false
    return runCatching { bindings.writePayload(payload) }.getOrDefault(false)
  }

  private fun publishExport(candidate: List<VrStrobeStoredProfile>) {
    val payload = runCatching { VrStrobeProfileBundleCodec.encode(candidate) }.getOrNull()
    exportStatus =
        when {
          payload == null -> "encode-failed"
          runCatching { bindings.writeExportBundlePayload(payload) }.getOrDefault(false) -> "ready"
          else -> "write-failed"
        }
  }

  private fun uniqueId(createdAtMs: Long): String {
    val base = "stored-$createdAtMs"
    if (profiles.none { it.id == base }) return base
    var suffix = 2
    while (profiles.any { it.id == "$base-$suffix" }) suffix += 1
    return "$base-$suffix"
  }

  companion object {
    const val MODULE_ID = "vr-strobe-stored-profile-authority"
  }
}

internal object VrStrobeStoredProfileCodec {
  private const val MAGIC = 0x56525350
  private const val VERSION = 3
  private const val LEGACY_VERSION = 1
  private const val DISTANCE_SCALED_VERSION = 3
  private const val LEGACY_DISTANCE_SCALE = 2f
  private const val KIND_INTERFERENCE = 1
  private const val KIND_TEMPORAL = 2

  fun encode(profiles: List<VrStrobeStoredProfile>): String {
    return encodeForVersion(profiles, VERSION)
  }

  internal fun encodeForVersion(
      profiles: List<VrStrobeStoredProfile>,
      version: Int,
  ): String {
    require(version in LEGACY_VERSION..VERSION) { "stored-profile-version-unsupported" }
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
      output.writeInt(MAGIC)
      output.writeInt(version)
      output.writeInt(profiles.size)
      profiles.forEach { stored ->
        output.writeString(stored.id)
        output.writeString(stored.title)
        output.writeLong(stored.createdAtEpochMs)
        output.writeFloat(stored.distanceMeters)
        if (version >= 2) {
          output.writeBoolean(stored.carrierShape.curvedMode)
          output.writeFloat(stored.carrierShape.sanitized().concavity)
        }
        when (val stimulus = stored.profile) {
          is VrStrobeStimulusProfile.Interference -> {
            output.writeByte(KIND_INTERFERENCE)
            output.writeInterference(stimulus.profile)
          }
          is VrStrobeStimulusProfile.Temporal -> {
            output.writeByte(KIND_TEMPORAL)
            output.writeTemporal(stimulus.profile)
          }
        }
      }
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
  }

  fun decode(payload: String): List<VrStrobeStoredProfile> {
    val bytes = Base64.getDecoder().decode(payload)
    return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
      require(input.readInt() == MAGIC) { "stored-profile-magic-mismatch" }
      val version = input.readInt()
      require(version in LEGACY_VERSION..VERSION) { "stored-profile-version-unsupported" }
      val count = input.readInt()
      require(count in 0..10_000) { "stored-profile-count-invalid" }
      buildList(count) {
        repeat(count) {
          val id = input.readString()
          val title = input.readString()
          val createdAtMs = input.readLong()
          val storedDistanceMeters = input.readFloat()
          val distanceMeters =
              (if (version < DISTANCE_SCALED_VERSION) {
                    storedDistanceMeters * LEGACY_DISTANCE_SCALE
                  } else {
                    storedDistanceMeters
                  })
                  .coerceIn(
                  VrStrobeDistancePolicy.MIN_METERS,
                  VrStrobeDistancePolicy.MAX_METERS,
              )
          val carrierShape =
              if (version >= 2) {
                VrStrobeCarrierShapeState(
                        curvedMode = input.readBoolean(),
                        concavity = input.readFloat(),
                    )
                    .sanitized()
              } else {
                VrStrobeCarrierShapeState(
                    curvedMode = false,
                    concavity = VrStrobeConcavityPolicy.DEFAULT,
                )
              }
          val profile =
              when (input.readUnsignedByte()) {
                KIND_INTERFERENCE ->
                    VrStrobeStimulusProfile.Interference(input.readInterference())
                KIND_TEMPORAL -> VrStrobeStimulusProfile.Temporal(input.readTemporal())
                else -> error("stored-profile-kind-unsupported")
              }
          require(profile.id == id && profile.title == title) {
            "stored-profile-envelope-mismatch"
          }
          add(
              VrStrobeStoredProfile(
                  id = id,
                  title = title,
                  createdAtEpochMs = createdAtMs,
                  distanceMeters = distanceMeters,
                  carrierShape = carrierShape,
                  profile = profile,
              )
          )
        }
      }.also { require(input.available() == 0) { "stored-profile-trailing-data" } }
    }
  }

  private fun DataOutputStream.writeInterference(profile: VrStrobeInterferenceProfile) {
    writeString(profile.id)
    writeString(profile.title)
    writeNullableString(profile.sourceLabel)
    writeNullableString(profile.sourcePayload)
    writeFloat(profile.durationSeconds)
    writeInt(profile.colorCount)
    writeInt(profile.color1.rgb)
    writeInt(profile.color2.rgb)
    writeInt(profile.color3.rgb)
    writeBoolean(profile.oscillatorActive)
    writeFloat(profile.oscillatorFrequencyHz)
    writeFloat(profile.oscillatorShape)
    writeFloat(profile.scale)
    writeFloat(profile.shearX)
    writeFloat(profile.shearY)
    writeFloat(profile.offsetX)
    writeFloat(profile.offsetY)
    writeFloat(profile.shakeAmplitude)
    writeFloat(profile.shakeFrequencyHz)
    writeFloat(profile.rotationSpeed)
    writeFloat(profile.stepFactor)
    writeFloat(profile.trailAmount)
    writeFloat(profile.blurRadius)
    writeFloat(profile.glowStrength)
    writeFloat(profile.brightness)
    writeFloat(profile.contrast)
    writeFloat(profile.noiseFrequency)
    writeFloat(profile.noiseStrength)
    writeFloat(profile.noiseBias)
    writeFloat(profile.vignetteCenter)
    writeFloat(profile.vignetteEdge)
    writeFloat(profile.vignetteBias)
    writeInt(profile.patterns.size)
    profile.patterns.forEach { pattern -> writePattern(pattern) }
  }

  private fun DataInputStream.readInterference(): VrStrobeInterferenceProfile =
      VrStrobeInterferenceProfile(
              id = readString(),
              title = readString(),
              sourceLabel = readNullableString(),
              sourcePayload = readNullableString(),
              durationSeconds = readFloat(),
              colorCount = readInt(),
              color1 = VrStrobeColor(readInt()),
              color2 = VrStrobeColor(readInt()),
              color3 = VrStrobeColor(readInt()),
              oscillatorActive = readBoolean(),
              oscillatorFrequencyHz = readFloat(),
              oscillatorShape = readFloat(),
              scale = readFloat(),
              shearX = readFloat(),
              shearY = readFloat(),
              offsetX = readFloat(),
              offsetY = readFloat(),
              shakeAmplitude = readFloat(),
              shakeFrequencyHz = readFloat(),
              rotationSpeed = readFloat(),
              stepFactor = readFloat(),
              trailAmount = readFloat(),
              blurRadius = readFloat(),
              glowStrength = readFloat(),
              brightness = readFloat(),
              contrast = readFloat(),
              noiseFrequency = readFloat(),
              noiseStrength = readFloat(),
              noiseBias = readFloat(),
              vignetteCenter = readFloat(),
              vignetteEdge = readFloat(),
              vignetteBias = readFloat(),
              patterns = List(readBoundedCount()) { readPattern() },
          )
          .sanitized()

  private fun DataOutputStream.writeTemporal(profile: VrStrobeTemporalProfile) {
    writeString(profile.id)
    writeString(profile.title)
    writeNullableString(profile.sourceLabel)
    writeFloat(profile.durationSeconds)
    writeInt(profile.color1.rgb)
    writeInt(profile.color2.rgb)
    writeFloat(profile.frequencyHz)
    writeFloat(profile.dutyPercent)
    writeInt(profile.noiseType.ordinal)
    writeInt(profile.noiseResolution)
    writeBoolean(profile.noisePhase1)
    writeFloat(profile.noiseAmplitude1)
    writeBoolean(profile.noisePhase2)
    writeFloat(profile.noiseAmplitude2)
    writeBoolean(profile.fixationEnabled)
    writeInt(profile.fixationColor.rgb)
    writeInt(profile.fixationSize)
  }

  private fun DataInputStream.readTemporal(): VrStrobeTemporalProfile =
      VrStrobeTemporalProfile(
              id = readString(),
              title = readString(),
              sourceLabel = readNullableString(),
              durationSeconds = readFloat(),
              color1 = VrStrobeColor(readInt()),
              color2 = VrStrobeColor(readInt()),
              frequencyHz = readFloat(),
              dutyPercent = readFloat(),
              noiseType = VrStrobeNoiseType.entries[readInt().coerceIn(0, VrStrobeNoiseType.entries.lastIndex)],
              noiseResolution = readInt(),
              noisePhase1 = readBoolean(),
              noiseAmplitude1 = readFloat(),
              noisePhase2 = readBoolean(),
              noiseAmplitude2 = readFloat(),
              fixationEnabled = readBoolean(),
              fixationColor = VrStrobeColor(readInt()),
              fixationSize = readInt(),
          )
          .sanitized()

  private fun DataOutputStream.writePattern(pattern: VrStrobePattern) {
    writeInt(pattern.kind.ordinal)
    writeBoolean(pattern.active)
    writeFloat(pattern.strength)
    writeFloat(pattern.period)
    writeFloat(pattern.speed)
    writeFloat(pattern.pivotX)
    writeFloat(pattern.pivotY)
    writeFloat(pattern.distortFreq)
    writeFloat(pattern.distortAmp)
    writeFloat(pattern.distortSpeed)
    writeFloat(pattern.distMultPar)
    writeFloat(pattern.distMultOrth)
    writeFloat(pattern.waveFreq)
    writeFloat(pattern.waveAmp)
    writeFloat(pattern.waveShape)
    writeFloat(pattern.angle)
    writeFloat(pattern.rotationPivotX)
    writeFloat(pattern.rotationPivotY)
    writeFloat(pattern.rotationSpeed)
    writeFloat(pattern.extent)
    writeFloat(pattern.noiseMove)
    writeFloat(pattern.perlinScale)
    writeFloat(pattern.perlinZSpeed)
    writeFloat(pattern.perlinZOffset)
  }

  private fun DataInputStream.readPattern(): VrStrobePattern =
      VrStrobePattern(
              kind = VrStrobePatternKind.entries[readInt().coerceIn(0, VrStrobePatternKind.entries.lastIndex)],
              active = readBoolean(),
              strength = readFloat(),
              period = readFloat(),
              speed = readFloat(),
              pivotX = readFloat(),
              pivotY = readFloat(),
              distortFreq = readFloat(),
              distortAmp = readFloat(),
              distortSpeed = readFloat(),
              distMultPar = readFloat(),
              distMultOrth = readFloat(),
              waveFreq = readFloat(),
              waveAmp = readFloat(),
              waveShape = readFloat(),
              angle = readFloat(),
              rotationPivotX = readFloat(),
              rotationPivotY = readFloat(),
              rotationSpeed = readFloat(),
              extent = readFloat(),
              noiseMove = readFloat(),
              perlinScale = readFloat(),
              perlinZSpeed = readFloat(),
              perlinZOffset = readFloat(),
          )
          .sanitized()

  private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
  }

  private fun DataInputStream.readNullableString(): String? =
      if (readBoolean()) readString() else null

  private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
  }

  private fun DataInputStream.readString(): String {
    val size = readInt()
    require(size in 0..4_000_000) { "stored-profile-string-size-invalid" }
    return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
  }

  private fun DataInputStream.readBoundedCount(): Int =
      readInt().also { require(it in 0..(VR_STROBE_MAX_PATTERN_INSTANCES * 4)) }
}
