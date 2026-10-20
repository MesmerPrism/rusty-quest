package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One observer waits for its already-issued original. This is no retry or effect grant. */
internal fun <T> Future<T>.awaitOperatorCompletion(
    estimateSeconds: Long,
    warn: () -> Unit = {
      android.util.Log.w("EmbeddedDuplexOperator", "original_wait_estimate_exceeded")
    },
): T {
  require(estimateSeconds > 0) { "operator-estimate-invalid" }
  try {
    return try {
      get(estimateSeconds, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
      // A diagnostic sink cannot replace original completion. No exception text escapes.
      try { warn() } catch (_: Exception) { }
      get()
    }
  } catch (interrupted: InterruptedException) {
    // Interrupting an observer does not cancel or establish the effect's outcome.
    Thread.currentThread().interrupt()
    throw interrupted
  }
}
