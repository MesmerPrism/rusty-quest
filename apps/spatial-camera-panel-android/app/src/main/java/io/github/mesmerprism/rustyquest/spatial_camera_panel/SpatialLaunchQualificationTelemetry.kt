package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class SpatialLaunchQualificationNativeBoundary(
    private val loadLibrary: () -> Unit,
    private val resetQualification: () -> Unit,
) {
  private val monitor = Any()
  @Volatile private var loaded = false

  fun ensureLoadedAndReset() = synchronized(monitor) {
    try {
      if (!loaded) {
        loadLibrary()
        loaded = true
      }
      resetQualification()
    } catch (failure: LinkageError) {
      unavailable(failure)
    } catch (failure: SecurityException) {
      unavailable(failure)
    }
  }

  private fun unavailable(failure: Throwable): Nothing =
      throw IllegalStateException("debug_host_receipt_native_unavailable", failure)
}

/**
 * App-owned, in-process readback for a bounded launch qualification consumer.
 *
 * Production behavior does not depend on this surface. It contains no paths or private media
 * identity, remains inert until explicitly armed by a variant-specific consumer, and reads native
 * counters instead of parsing log output.
 */
internal object SpatialLaunchQualificationTelemetry {
  data class NativeSnapshot(
      val decoderStarted: Boolean,
      val errorCode: Int,
      val firstDecodedFrame: Long,
      val lastDecodedFrame: Long,
      val lastImportSequence: Long,
      val firstTimestampNs: Long,
      val lastTimestampNs: Long,
      val width: Int,
      val height: Int,
      val maxImages: Int,
      val fpsCap: Int,
      val firstAdoptedFrame: Long,
      val lastAdoptedFrame: Long,
      val lastPresentOrdinal: Long,
      val distinctAdoptedFrames: Long,
  )

  data class Snapshot(
      val source: String,
      val cadence: String,
      val native: NativeSnapshot,
  )

  private const val FIELD_REVISION = 0
  private const val FIELD_DECODER_STARTED = 1
  private const val FIELD_ERROR_CODE = 2
  private const val FIELD_FIRST_DECODED_FRAME = 3
  private const val FIELD_LAST_DECODED_FRAME = 4
  private const val FIELD_LAST_IMPORT_SEQUENCE = 5
  private const val FIELD_FIRST_TIMESTAMP_NS = 6
  private const val FIELD_LAST_TIMESTAMP_NS = 7
  private const val FIELD_WIDTH = 8
  private const val FIELD_HEIGHT = 9
  private const val FIELD_MAX_IMAGES = 10
  private const val FIELD_FPS_CAP = 11
  private const val FIELD_FIRST_ADOPTED_FRAME = 12
  private const val FIELD_LAST_ADOPTED_FRAME = 13
  private const val FIELD_LAST_PRESENT_ORDINAL = 14
  private const val FIELD_DISTINCT_ADOPTED_FRAMES = 15
  private const val MAX_SNAPSHOT_ATTEMPTS = 4

  private val monitor = Any()
  private val nativeBoundary =
      SpatialLaunchQualificationNativeBoundary(
          loadLibrary = { System.loadLibrary(NATIVE_RECEIPT_LIBRARY) },
          resetQualification = { nativeResetQualification() },
      )
  private var armed = false
  private data class SettingsSnapshot(
      val source: String,
      val cadence: String,
      val active: Boolean,
      val sourceIdentitySha256: String,
  )
  private var settings: SettingsSnapshot? = null

  fun arm() {
    synchronized(monitor) {
      armed = false
      nativeBoundary.ensureLoadedAndReset()
      armed = true
    }
  }

  fun disarm() {
    synchronized(monitor) { armed = false }
    runCatching { nativeDisableQualification() }
  }

  fun recordSettings(settings: SpatialVideoProjectionSettings) {
    val next =
        SettingsSnapshot(
            source = settings.source,
            cadence =
                when {
                  settings.fpsCap <= 30 -> "30"
                  settings.fpsCap <= 60 -> "60"
                  else -> "source"
                },
            active = settings.active,
            sourceIdentitySha256 = sha256(settings.path),
        )
    val shouldReset =
        synchronized(monitor) {
          val changed = this.settings != next
          this.settings = next
          armed && changed
        }
    if (shouldReset) resetNative()
  }

  fun snapshot(): Snapshot? {
    val currentSettings =
        synchronized(monitor) { settings?.takeIf { armed && it.active } } ?: return null
    val native = readNativeConsistently() ?: return null
    return Snapshot(
        source = currentSettings.source,
        cadence = currentSettings.cadence,
        native = native,
    )
  }

  private fun resetNative() = runCatching { nativeResetQualification() }

  private fun sha256(value: String): String =
      MessageDigest.getInstance("SHA-256")
          .digest(value.toByteArray(StandardCharsets.UTF_8))
          .joinToString("") { "%02x".format(it) }

  private fun readNativeConsistently(): NativeSnapshot? {
    repeat(MAX_SNAPSHOT_ATTEMPTS) {
      val revisionBefore = runCatching { nativeReadQualificationField(FIELD_REVISION) }.getOrNull()
          ?: return null
      if (revisionBefore < 0) return null
      val values = LongArray(FIELD_DISTINCT_ADOPTED_FRAMES + 1)
      for (field in FIELD_DECODER_STARTED..FIELD_DISTINCT_ADOPTED_FRAMES) {
        values[field] =
            runCatching { nativeReadQualificationField(field) }.getOrNull() ?: return null
      }
      val revisionAfter = runCatching { nativeReadQualificationField(FIELD_REVISION) }.getOrNull()
          ?: return null
      if (revisionBefore == revisionAfter) {
        return NativeSnapshot(
            decoderStarted = values[FIELD_DECODER_STARTED] == 1L,
            errorCode = values[FIELD_ERROR_CODE].toInt(),
            firstDecodedFrame = values[FIELD_FIRST_DECODED_FRAME],
            lastDecodedFrame = values[FIELD_LAST_DECODED_FRAME],
            lastImportSequence = values[FIELD_LAST_IMPORT_SEQUENCE],
            firstTimestampNs = values[FIELD_FIRST_TIMESTAMP_NS],
            lastTimestampNs = values[FIELD_LAST_TIMESTAMP_NS],
            width = values[FIELD_WIDTH].toInt(),
            height = values[FIELD_HEIGHT].toInt(),
            maxImages = values[FIELD_MAX_IMAGES].toInt(),
            fpsCap = values[FIELD_FPS_CAP].toInt(),
            firstAdoptedFrame = values[FIELD_FIRST_ADOPTED_FRAME],
            lastAdoptedFrame = values[FIELD_LAST_ADOPTED_FRAME],
            lastPresentOrdinal = values[FIELD_LAST_PRESENT_ORDINAL],
            distinctAdoptedFrames = values[FIELD_DISTINCT_ADOPTED_FRAMES],
        )
      }
    }
    return null
  }

  private external fun nativeResetQualification()

  private external fun nativeDisableQualification()

  private external fun nativeReadQualificationField(field: Int): Long
}
