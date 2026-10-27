package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeStatus
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPairStatus
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexStartPreflight

/** Debug transport reaches only fixed actions on the current resumed Activity. */
object EmbeddedDuplexDiagnosticActivityGate {
  @Volatile private var resumed: WeakReference<SpatialCameraPanelActivity>? = null
  // Pure bookkeeping may complete while paused; this weak reference grants no effect/foreground authority.
  @Volatile private var bookkeeping: WeakReference<SpatialCameraPanelActivity>? = null
  private val main = Handler(Looper.getMainLooper())
  private fun postOriginal(action: () -> Unit): Boolean =
      io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexOriginals.post(
          Runnable { action() }, java.util.function.Predicate { main.post(it) })

  fun resumed(activity: SpatialCameraPanelActivity) {
    bookkeeping = WeakReference(activity)
    if (BuildConfig.DEBUG) resumed = WeakReference(activity)
  }

  fun paused(activity: SpatialCameraPanelActivity) {
    if (resumed?.get() === activity) resumed = null
  }

  /** Captured before the actual source-owned selection; a missing Activity remains headless. */
  @JvmStatic
  fun captureRunSelectionReconciliation(): java.util.function.BiConsumer<String?, Throwable?> {
    val reconcile = bookkeeping?.get()?.captureEmbeddedDuplexRunSelectionReconciliation()
    return java.util.function.BiConsumer { receipt, failure ->
      if (failure == null && receipt != null && reconcile != null) {
        val generation = runCatching {
          val status = org.json.JSONObject(receipt)
          require(status.getString("\$schema") == "rusty.quest.embedded_duplex.run_status.v1")
          status.getLong("app_display_generation").also { require(it >= 0L) }
        }.getOrNull()
        if (generation != null) reconcile.accept(generation)
      }
    }
  }

  @JvmStatic
  fun requestRun(): CompletableFuture<Boolean> {
    val result = CompletableFuture<Boolean>()
    if (!BuildConfig.DEBUG) {
      result.complete(false)
      return result
    }
    val accepted = postOriginal {
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
    if (!BuildConfig.DEBUG) return CompletableFuture.failedFuture(
        io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.refuse(
            io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.debugDisabled()))
    if (!postOriginal {
        try {
          val activity = resumed?.get()
          if (activity == null) {
            result.completeExceptionally(
                io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.refuse(
                    io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.noActivity()))
          } else {
            activity.runEmbeddedDuplexRealPeerBootstrap().whenComplete { status, failure ->
              if (failure == null) result.complete(status) else result.completeExceptionally(failure)
            }
          }
        } catch (failure: Throwable) {
          // An unavailable actor/read cannot mint a refusal proof or escape the actual callback.
          result.completeExceptionally(failure)
        }
        }) {
      result.completeExceptionally(
          io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.refuse(
              io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.dispatchRejected()))
    }
    return result
  }

  @JvmStatic
  fun requestBootstrapReadiness(): CompletableFuture<String> {
    val result = CompletableFuture<String>()
    if (!main.post {
      runCatching {
        (resumed?.get()?.embeddedDuplexBootstrapReadiness()
            ?: io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.noActivity()).json()
      }.onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) }
    }) result.complete(
        io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission.dispatchRejected().json())
    return result
  }

  @JvmStatic
  fun requestNoMediaClose(): CompletableFuture<String> {
    val result = CompletableFuture<String>()
    if (!BuildConfig.DEBUG || !postOriginal {
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
    if (!BuildConfig.DEBUG || !postOriginal {
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

  /** The Original remains carried through the same resumed Activity action used by the panel. */
  @JvmStatic
  fun requestOrdinaryStart(challenge: String): CompletableFuture<String> {
    val result = CompletableFuture<String>()
    if (!BuildConfig.DEBUG || !postOriginal {
          val activity = resumed?.get()
          if (activity == null) {
            result.completeExceptionally(IllegalStateException("resumed Activity unavailable"))
          } else {
            activity.runEmbeddedDuplexOrdinaryStart(challenge).whenComplete { receipt, failure ->
              if (failure == null) result.complete(receipt) else result.completeExceptionally(failure)
            }
          }
        }) {
      result.completeExceptionally(IllegalStateException("Activity dispatch unavailable"))
    }
    return result
  }

  @JvmStatic
  fun requestStartPreflight(): CompletableFuture<EmbeddedDuplexStartPreflight> {
    val result = CompletableFuture<EmbeddedDuplexStartPreflight>()
    if (!BuildConfig.DEBUG || !postOriginal {
          val activity = resumed?.get()
          if (activity == null) {
            result.completeExceptionally(IllegalStateException("resumed Activity unavailable"))
          } else {
            activity.prepareEmbeddedDuplexStartPreflight().whenComplete { decision, failure ->
              if (failure == null) result.complete(decision) else result.completeExceptionally(failure)
            }
          }
        }) {
      result.completeExceptionally(IllegalStateException("Activity dispatch unavailable"))
    }
    return result
  }
  @JvmStatic
  fun requestNewRun(runId: String, legacyWholeOriginalId: String? = null): CompletableFuture<String> = selectRun(runId,true,legacyWholeOriginalId)
  @JvmStatic
  fun requestResumeRun(runId: String): CompletableFuture<String> = selectRun(runId,false,null)
  private fun selectRun(runId:String,create:Boolean,legacy:String?):CompletableFuture<String>{
    val result=CompletableFuture<String>()
    if(!BuildConfig.DEBUG || !main.post {
      val activity=resumed?.get()
      if(activity==null)result.completeExceptionally(IllegalStateException("resumed Activity unavailable"))
      else activity.selectEmbeddedDuplexRun(runId,create,legacy).whenComplete { receipt,failure ->
        if(failure==null)result.complete(receipt) else result.completeExceptionally(failure)
      }
    }) result.completeExceptionally(IllegalStateException("Activity dispatch unavailable"))
    return result
  }

}
