package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File

internal class SpatialActivityMarkerDeduplicator(
    private val minimumRepeatMs: Long = DEFAULT_MINIMUM_REPEAT_MS,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) {
  private val lastRecordedMs = LinkedHashMap<String, Long>(maximumEntries, 0.75f, true)

  @Synchronized
  fun shouldRecord(detail: String, nowMs: Long): Boolean {
    val previousMs = lastRecordedMs[detail]
    if (previousMs != null && nowMs - previousMs < minimumRepeatMs) return false
    lastRecordedMs[detail] = nowMs
    while (lastRecordedMs.size > maximumEntries) {
      val eldest = lastRecordedMs.entries.iterator()
      eldest.next()
      eldest.remove()
    }
    return true
  }

  private companion object {
    const val DEFAULT_MINIMUM_REPEAT_MS = 10_000L
    const val DEFAULT_MAXIMUM_ENTRIES = 256
  }
}

internal fun activityMarkerFileAccepts(
    currentBytes: Long,
    appendedBytes: Int,
    maximumBytes: Long = SpatialActivityMarkerRecorder.MAXIMUM_FILE_BYTES,
): Boolean =
    currentBytes >= 0L &&
        appendedBytes >= 0 &&
        currentBytes <= maximumBytes &&
        appendedBytes.toLong() <= maximumBytes - currentBytes

internal fun activityMarkerIsPeriodic(detail: String): Boolean =
    detail.contains("status=controller-input-route-ready") ||
        detail.contains("status=spatial-input-enabled")

/**
 * Bounded diagnostic persistence that never performs file I/O on the XR/activity thread.
 *
 * An already-oversized evidence file is preserved byte-for-byte and persistence is disabled for
 * the process. New files stop accepting rows at the cap; this recorder never truncates or rotates
 * historical evidence implicitly.
 */
internal class SpatialActivityMarkerRecorder(
    private val markerFile: File,
    private val tag: String,
    private val markerPrefix: String,
    private val persistToFile: Boolean,
) : Closeable {
  private val deduplicator = SpatialActivityMarkerDeduplicator()
  private val workerThread =
      if (persistToFile) {
        HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND).also(
            HandlerThread::start
        )
      } else {
        null
      }
  private val worker = workerThread?.let { thread -> Handler(thread.looper) }
  @Volatile private var closed = false
  private var persistenceDisabled = persistToFile && markerFile.length() > MAXIMUM_FILE_BYTES
  private var persistenceDisabledMarkerWritten = false

  fun record(detail: String) {
    val elapsedMs = SystemClock.elapsedRealtime()
    if (activityMarkerIsPeriodic(detail) && !deduplicator.shouldRecord(detail, elapsedMs)) return
    val line = "$markerPrefix $detail"
    Log.i(tag, line)
    if (closed || !persistToFile) return
    val wallClockMs = System.currentTimeMillis()
    worker?.post { appendOnWorker(wallClockMs, line) }
  }

  private fun appendOnWorker(wallClockMs: Long, line: String) {
    if (persistenceDisabled) {
      logPersistenceDisabledOnce()
      return
    }
    val row = "$wallClockMs $line\n"
    val rowBytes = row.toByteArray(Charsets.UTF_8)
    if (!activityMarkerFileAccepts(markerFile.length(), rowBytes.size)) {
      persistenceDisabled = true
      logPersistenceDisabledOnce()
      return
    }
    runCatching { markerFile.appendText(row, Charsets.UTF_8) }
        .onFailure {
          persistenceDisabled = true
          logPersistenceDisabledOnce()
        }
  }

  private fun logPersistenceDisabledOnce() {
    if (persistenceDisabledMarkerWritten) return
    persistenceDisabledMarkerWritten = true
    Log.w(
        tag,
        "$markerPrefix channel=activity-marker-persistence status=disabled " +
            "reason=bounded-file-cap existingEvidencePreserved=true " +
            "maximumBytes=$MAXIMUM_FILE_BYTES workerThread=${Thread.currentThread().name}",
    )
  }

  override fun close() {
    if (closed) return
    closed = true
    worker?.post { workerThread?.quitSafely() }
  }

  companion object {
    const val MAXIMUM_FILE_BYTES = 8L * 1024L * 1024L
    const val WORKER_THREAD_NAME = "RqActivityMarkers"
  }
}
