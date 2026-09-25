package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeStatus
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPairStatus

/** Debug transport reaches only fixed actions on the current resumed Activity. */
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

  /** The shell can request only the same fixed real-peer action exposed by the panel. */
  @JvmStatic
  fun requestRealPeerBootstrap(): CompletableFuture<EmbeddedDuplexRuntimeStatus> {
    val result = CompletableFuture<EmbeddedDuplexRuntimeStatus>()
    if (!BuildConfig.DEBUG || !main.post {
          val activity = resumed?.get()
          if (activity == null) {
            result.completeExceptionally(IllegalStateException("resumed Activity unavailable"))
          } else {
            activity.runEmbeddedDuplexRealPeerBootstrap().whenComplete { status, failure ->
              if (failure == null) result.complete(status) else result.completeExceptionally(failure)
            }
          }
        }) {
      result.completeExceptionally(IllegalStateException("Activity dispatch unavailable"))
    }
    return result
  }

  @JvmStatic
  fun requestNoMediaClose(): CompletableFuture<String> {
    val result = CompletableFuture<String>()
    if (!BuildConfig.DEBUG || !main.post {
          val activity = resumed?.get()
          if (activity == null) {
            result.completeExceptionally(IllegalStateException("resumed Activity unavailable"))
          } else {
            activity.closeEmbeddedDuplexNoMedia().whenComplete { closed, failure ->
              if (failure == null) result.complete(closed) else result.completeExceptionally(failure)
            }
          }
        }) {
      result.completeExceptionally(IllegalStateException("Activity dispatch unavailable"))
    }
    return result
  }

  @JvmStatic
  fun requestPairSession(): CompletableFuture<EmbeddedDuplexPairStatus> {
    val result = CompletableFuture<EmbeddedDuplexPairStatus>()
    if (!BuildConfig.DEBUG || !main.post {
          val activity = resumed?.get()
          if (activity == null) {
            result.completeExceptionally(IllegalStateException("resumed Activity unavailable"))
          } else {
            activity.runEmbeddedDuplexPairSession().whenComplete { status, failure ->
              if (failure == null) result.complete(status) else result.completeExceptionally(failure)
            }
          }
        }) {
      result.completeExceptionally(IllegalStateException("Activity dispatch unavailable"))
    }
    return result
  }
}
