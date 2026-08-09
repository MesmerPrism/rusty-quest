package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.util.AtomicFile
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal enum class SpatialBackgroundMode(val token: String) {
  Black("black"),
  Passthrough("passthrough"),
  LutPassthrough("lut-passthrough");

  companion object {
    fun fromToken(token: String?): SpatialBackgroundMode =
        when (token?.trim()?.lowercase()?.replace('_', '-')) {
          Passthrough.token -> Passthrough
          LutPassthrough.token, "lut", "poster-lut", "posterized-passthrough" -> LutPassthrough
          else -> Black
        }
  }
}

internal data class SpatialBackgroundEffects(
    val blackBackingVisible: Boolean,
    val systemPassthroughRequested: Boolean,
    val passthroughLutRequested: Boolean,
)

internal object SpatialBackgroundModePolicy {
  fun resolve(
      mode: SpatialBackgroundMode,
      diagnosticLutRequested: Boolean,
  ): SpatialBackgroundEffects =
      SpatialBackgroundEffects(
          blackBackingVisible = mode == SpatialBackgroundMode.Black,
          systemPassthroughRequested =
              mode != SpatialBackgroundMode.Black || diagnosticLutRequested,
          passthroughLutRequested =
              mode == SpatialBackgroundMode.LutPassthrough || diagnosticLutRequested,
      )

  fun marker(
      mode: SpatialBackgroundMode,
      diagnosticLutRequested: Boolean,
      effects: SpatialBackgroundEffects,
      source: String,
  ): String =
      "channel=spatial-background status=mode-applied " +
          "source=${activityMarkerToken(source)} backgroundMode=${mode.token} " +
          "backgroundBlackBackingVisible=${effects.blackBackingVisible} " +
          "backgroundSystemPassthroughRequested=${effects.systemPassthroughRequested} " +
          "backgroundPassthroughLutRequested=${effects.passthroughLutRequested} " +
          "diagnosticPassthroughLutRequested=$diagnosticLutRequested " +
          "passthroughLutOwner=spatial-sdk-system-passthrough"
}

internal data class SpatialCameraPanelControlSnapshot(
    val projectionPanelEnabled: Boolean,
    val layerOverride: Float,
    val projectionScale: Float,
    val depthLayerPolicy: Int,
    val depthAlignment: PrivateLayerDepthAlignment,
    val guideProcessing: PrivateLayerGuideProcessing,
    val zoneCompositor: PrivateLayerZoneCompositor,
    val rgbChannelTransform: RgbChannelTransform,
    val projectionSurfaceDisplacement: ProjectionSurfaceDisplacement,
    val projectionSurfaceTiling: ProjectionSurfaceTiling,
    val projectionInnerAlpha: ProjectionInnerAlpha,
    val videoPlaybackEnabled: Boolean,
    val videoPresentationMode: String,
    // Null is the v1 on-disk representation for profiles created before Background existed.
    // Keep that representation stable so exact profile fingerprints used by playlists and
    // Kiosk launch options do not change merely because a newer app reads the profile.
    val backgroundMode: String? = null,
) {
  fun normalized(): SpatialCameraPanelControlSnapshot =
      copy(
          layerOverride = PrivateLayerPanelControlModule.normalizeLayerOverride(layerOverride),
          projectionScale =
              projectionScale
                  .takeIf(Float::isFinite)
                  ?.coerceIn(
                      CAMERA_HWB_PROJECTION_TARGET_MIN_SCALE,
                      CAMERA_HWB_PROJECTION_TARGET_MAX_SCALE,
                  )
                  ?: 1.0f,
          depthLayerPolicy =
              PrivateLayerPanelControlModule.normalizeDepthLayerPolicy(depthLayerPolicy),
          depthAlignment = PrivateLayerPanelControlModule.coerceDepthAlignment(depthAlignment),
          guideProcessing =
              PrivateLayerPanelControlModule.normalizeGuideProcessing(guideProcessing),
          zoneCompositor = PrivateLayerZoneCompositorModule.normalize(zoneCompositor),
          rgbChannelTransform = RgbChannelTransformModule.normalize(rgbChannelTransform),
          projectionSurfaceDisplacement =
              ProjectionSurfaceDisplacementModule.normalize(projectionSurfaceDisplacement),
          projectionSurfaceTiling =
              ProjectionSurfaceTilingModule.normalize(projectionSurfaceTiling),
          projectionInnerAlpha = ProjectionInnerAlphaModule.normalize(projectionInnerAlpha),
          videoPresentationMode =
              when (videoPresentationMode.trim().lowercase().replace('_', '-')) {
                SpatialImmersiveVideoPresentationMode.HeadFixedBorder.token ->
                    SpatialImmersiveVideoPresentationMode.HeadFixedBorder.token
                else -> SpatialImmersiveVideoPresentationMode.WorldAnchored.token
              },
          backgroundMode =
              backgroundMode?.let { SpatialBackgroundMode.fromToken(it).token },
      )

  fun presentationMode(): SpatialImmersiveVideoPresentationMode =
      if (videoPresentationMode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder.token) {
        SpatialImmersiveVideoPresentationMode.HeadFixedBorder
      } else {
        SpatialImmersiveVideoPresentationMode.WorldAnchored
      }

  fun resolvedBackgroundMode(): SpatialBackgroundMode =
      SpatialBackgroundMode.fromToken(backgroundMode)
}

internal data class SpatialCameraPanelProfileEntry(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val controls: SpatialCameraPanelControlSnapshot,
)

internal data class SpatialCameraPanelProfileLibraryBindings(
    val readPayload: () -> String?,
    val writePayload: (String) -> Boolean,
    val readImportBundlePayload: () -> String? = { null },
    val clearImportBundlePayload: () -> Boolean = { true },
    val writeExportBundlePayload: (String) -> Boolean = { true },
    val wallClockNowMs: () -> Long = System::currentTimeMillis,
)

internal data class SpatialCameraPanelProfileLibrarySnapshot(
    val profiles: List<SpatialCameraPanelProfileEntry>,
    val loadStatus: String,
    val exportStatus: String,
)

internal data class SpatialCameraPanelProfileOperationResult(
    val status: String,
    val library: SpatialCameraPanelProfileLibrarySnapshot,
    val effectiveControls: SpatialCameraPanelControlSnapshot? = null,
)

internal class SpatialCameraPanelProfileLibraryAuthority(
    private val bindings: SpatialCameraPanelProfileLibraryBindings,
) {
  private var profiles: List<SpatialCameraPanelProfileEntry>
  private var loadStatus: String
  private var exportStatus: String = "not-attempted"

  init {
    val payload = runCatching(bindings.readPayload).getOrNull()
    val decoded =
        if (payload.isNullOrBlank()) Result.success(emptyList())
        else runCatching { SpatialCameraPanelProfileBundleCodec.decode(payload) }
    profiles = decoded.getOrDefault(emptyList())
    loadStatus = if (decoded.isSuccess) "ready" else "corrupt-payload-ignored"
    importStagedIfPresent()
    publishExport()
  }

  fun snapshot(): SpatialCameraPanelProfileLibrarySnapshot =
      SpatialCameraPanelProfileLibrarySnapshot(profiles, loadStatus, exportStatus)

  fun find(id: String): SpatialCameraPanelProfileEntry? = profiles.firstOrNull { it.id == id }

  fun store(
      requestedTitle: String,
      controls: SpatialCameraPanelControlSnapshot,
  ): SpatialCameraPanelProfileOperationResult {
    if (profiles.size >= SpatialCameraPanelProfileBundleCodec.MAX_PROFILES) {
      return result("profile-limit-reached")
    }
    val title = requestedTitle.trim().take(SpatialCameraPanelProfileBundleCodec.MAX_TITLE_LENGTH)
    if (title.isBlank()) return result("profile-name-required")
    val createdAt = bindings.wallClockNowMs().coerceAtLeast(0L)
    val stored =
        SpatialCameraPanelProfileEntry(
            id = uniqueId(createdAt),
            title = title,
            createdAtEpochMs = createdAt,
            controls = controls.normalized(),
        )
    val candidate = listOf(stored) + profiles
    if (!persist(candidate)) return result("profile-save-failed")
    profiles = candidate
    loadStatus = "ready"
    publishExport()
    return result("profile-saved")
  }

  fun delete(id: String): SpatialCameraPanelProfileOperationResult {
    val candidate = profiles.filterNot { it.id == id }
    if (candidate.size == profiles.size) return result("profile-not-found")
    if (!persist(candidate)) return result("profile-delete-failed")
    profiles = candidate
    publishExport()
    return result("profile-deleted")
  }

  fun replaceFromBundle(payload: String): SpatialCameraPanelProfileOperationResult {
    val candidate =
        runCatching { SpatialCameraPanelProfileBundleCodec.decode(payload) }.getOrNull()
            ?: return result("profile-import-rejected")
    if (!persist(candidate)) return result("profile-import-persist-failed")
    profiles = candidate
    loadStatus = "imported"
    publishExport()
    return result("profiles-imported")
  }

  fun importStaged(): SpatialCameraPanelProfileOperationResult {
    val payload = runCatching(bindings.readImportBundlePayload).getOrNull()
    if (payload.isNullOrBlank()) return result("no-staged-import")
    val imported = replaceFromBundle(payload)
    if (imported.status != "profiles-imported") return imported
    val cleared = runCatching(bindings.clearImportBundlePayload).getOrDefault(false)
    loadStatus = if (cleared) "imported" else "imported-staging-clear-failed"
    return result(if (cleared) "profiles-imported" else "profiles-imported-clear-failed")
  }

  fun exportBundle(): String = SpatialCameraPanelProfileBundleCodec.encode(profiles)

  private fun importStagedIfPresent() {
    val payload = runCatching(bindings.readImportBundlePayload).getOrNull()
    if (payload.isNullOrBlank()) return
    val candidate =
        runCatching { SpatialCameraPanelProfileBundleCodec.decode(payload) }.getOrNull()
    if (candidate == null || !persist(candidate)) {
      loadStatus = "import-rejected"
      return
    }
    profiles = candidate
    loadStatus =
        if (runCatching(bindings.clearImportBundlePayload).getOrDefault(false)) {
          "imported"
        } else {
          "imported-staging-clear-failed"
        }
  }

  private fun persist(candidate: List<SpatialCameraPanelProfileEntry>): Boolean {
    val payload =
        runCatching { SpatialCameraPanelProfileBundleCodec.encode(candidate) }.getOrNull()
            ?: return false
    return runCatching { bindings.writePayload(payload) }.getOrDefault(false)
  }

  private fun publishExport() {
    val payload = runCatching(::exportBundle).getOrNull()
    exportStatus =
        when {
          payload == null -> "encode-failed"
          runCatching { bindings.writeExportBundlePayload(payload) }.getOrDefault(false) -> "ready"
          else -> "write-failed"
        }
  }

  private fun result(
      status: String,
      effectiveControls: SpatialCameraPanelControlSnapshot? = null,
  ): SpatialCameraPanelProfileOperationResult =
      SpatialCameraPanelProfileOperationResult(status, snapshot(), effectiveControls)

  private fun uniqueId(createdAtMs: Long): String {
    val base = "profile-$createdAtMs"
    if (profiles.none { it.id == base }) return base
    var suffix = 2
    while (profiles.any { it.id == "$base-$suffix" }) suffix += 1
    return "$base-$suffix"
  }
}

/** Human-readable interchange shared by the panel and the serial-scoped PC transfer tool. */
internal object SpatialCameraPanelProfileBundleCodec {
  const val SCHEMA = "rusty.quest.spatial_camera_panel.profile_bundle.v1"
  const val FORMAT_VERSION = 1
  const val MAX_PROFILES = 128
  const val MAX_TITLE_LENGTH = 96
  const val MAX_PAYLOAD_BYTES = 1_048_576
  private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  fun encode(profiles: List<SpatialCameraPanelProfileEntry>): String {
    require(profiles.size <= MAX_PROFILES) { "profile-bundle-count-invalid" }
    require(profiles.map { it.id }.distinct().size == profiles.size) {
      "profile-bundle-duplicate-id"
    }
    val normalized = profiles.map(::validated)
    val root = JsonObject()
    root.addProperty("schema", SCHEMA)
    root.addProperty("format_version", FORMAT_VERSION)
    root.addProperty("profile_count", normalized.size)
    root.add("profiles", JsonArray().apply { normalized.forEach { add(gson.toJsonTree(it)) } })
    return (gson.toJson(root) + "\n").also {
      require(it.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
        "profile-bundle-size-invalid"
      }
    }
  }

  fun decode(payload: String): List<SpatialCameraPanelProfileEntry> {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "profile-bundle-size-invalid"
    }
    val root =
        JsonParser.parseString(payload).takeIf { it.isJsonObject }?.asJsonObject
            ?: error("profile-bundle-root-invalid")
    require(root.requireString("schema") == SCHEMA) { "profile-bundle-schema-unsupported" }
    require(root.requireInt("format_version") == FORMAT_VERSION) {
      "profile-bundle-version-unsupported"
    }
    val array =
        root.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: error("profile-bundle-array-invalid")
    val count = root.requireInt("profile_count")
    require(count == array.size() && count in 0..MAX_PROFILES) {
      "profile-bundle-count-invalid"
    }
    val profiles =
        array.map {
          require(it.isJsonObject) { "profile-bundle-profile-invalid" }
          validated(gson.fromJson(it, SpatialCameraPanelProfileEntry::class.java))
        }
    require(profiles.map { it.id }.distinct().size == profiles.size) {
      "profile-bundle-duplicate-id"
    }
    return profiles
  }

  private fun validated(profile: SpatialCameraPanelProfileEntry): SpatialCameraPanelProfileEntry {
    require(profile.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))) {
      "profile-bundle-id-invalid"
    }
    require(profile.title.isNotBlank() && profile.title.length <= MAX_TITLE_LENGTH) {
      "profile-bundle-title-invalid"
    }
    require(profile.createdAtEpochMs >= 0L) { "profile-bundle-time-invalid" }
    val normalizedControls = profile.controls.normalized()
    val legacyBlackBackground =
        profile.controls.backgroundMode == null &&
            profile.controls.copy(backgroundMode = SpatialBackgroundMode.Black.token) ==
                normalizedControls
    require(profile.controls == normalizedControls || legacyBlackBackground) {
      "profile-bundle-controls-out-of-bounds"
    }
    return profile.copy(controls = normalizedControls)
  }

  private fun JsonObject.requireString(name: String): String =
      get(name)?.takeUnless { it.isJsonNull }?.asString
          ?: error("profile-bundle-field-missing-$name")

  private fun JsonObject.requireInt(name: String): Int =
      get(name)?.takeUnless { it.isJsonNull }?.asInt
          ?: error("profile-bundle-field-missing-$name")
}

internal object SpatialCameraPanelProfileFiles {
  const val INTERNAL_FILE_NAME = "spatial-camera-panel-profiles.json"
  const val TRANSFER_DIRECTORY = "profile-library"
  const val IMPORT_FILE_NAME = "import.profile-bundle.json"
  const val EXPORT_FILE_NAME = "export.profile-bundle.json"

  fun read(file: File): String? =
      runCatching {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
          }
          .getOrNull()

  fun writeAtomically(file: File, payload: String): Boolean =
      runCatching {
            file.parentFile?.mkdirs()
            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()
            try {
              output.write(payload.toByteArray(Charsets.UTF_8))
              atomicFile.finishWrite(output)
            } catch (throwable: Throwable) {
              atomicFile.failWrite(output)
              throw throwable
            }
            true
          }
          .getOrDefault(false)
}

