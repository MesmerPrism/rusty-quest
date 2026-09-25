package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture

/** Debug transport reaches only the current resumed Activity's existing diagnostic action. */
object EmbeddedDuplexDiagnosticActivityGate {
  @Volatile private var resumed: WeakReference<SpatialCameraPanelActivity>? = null
  private val main = Handler(Looper.getMainLooper())

  fun resumed(activity: SpatialCameraPanelActivity) {
    if (BuildConfig.DEBUG) resumed = WeakReference(activity)
  }

  fun paused(activity: SpatialCameraPanelActivity) {
    if (resumed?.get() === activity) resumed = null
  }

  @JvmStatic
  fun requestRun(): CompletableFuture<Boolean> {
    val result = CompletableFuture<Boolean>()
    if (!BuildConfig.DEBUG) {
      result.complete(false)
      return result
    }
    val accepted = main.post {
      val activity = resumed?.get()
      if (activity == null || !activity.embeddedDuplexLocalDiagnosticReady()) {
        result.complete(false)
      } else {
        result.complete(runCatching { activity.runEmbeddedDuplexLocalDiagnostic(); true }
            .getOrDefault(false))
      }
    }
    if (!accepted) result.complete(false)
    return result
  }
}
