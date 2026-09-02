package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event-driven owner for document-provider media discovery.
 *
 * The worker is created lazily only after an explicit refresh or folder adoption. The status and
 * observer routes are memory-only, so an idle panel performs no Storage Access Framework work.
 */
internal class SharedMediaLibrarySnapshotClient(
    context: Context,
) : Closeable {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val observers =
      CopyOnWriteArraySet<(SharedOfflineImmersiveMediaLibrarySnapshot) -> Unit>()
  private val closed = AtomicBoolean(false)
  private val refreshInFlight = AtomicBoolean(false)
  private val workerLock = Any()
  private var workerThread: HandlerThread? = null
  private var workerHandler: Handler? = null

  @Volatile
  private var latestSnapshot =
      SharedOfflineImmersiveMediaLibrary.statusWithoutScan(appContext)

  /** Returns an in-memory readback and never contacts a document provider. */
  fun status(): SharedOfflineImmersiveMediaLibrarySnapshot = latestSnapshot

  fun observe(
      observer: (SharedOfflineImmersiveMediaLibrarySnapshot) -> Unit
  ): Closeable {
    if (closed.get()) return Closeable {}
    observers += observer
    mainHandler.post {
      if (!closed.get() && observer in observers) observer(latestSnapshot)
    }
    return Closeable { observers -= observer }
  }

  /** A user-requested, coalesced full scan on a background-priority thread. */
  fun refresh(
      onSuccess: ((SharedOfflineImmersiveMediaLibrarySnapshot) -> Unit)? = null,
  ): SharedOfflineImmersiveMediaLibrarySnapshot {
    if (closed.get() || !latestSnapshot.configured) return latestSnapshot
    if (!refreshInFlight.compareAndSet(false, true)) return latestSnapshot
    val pending = latestSnapshot.copy(status = "refresh-pending")
    publish(pending)
    if (!postToWorker {
          try {
            if (!closed.get()) {
              val snapshot =
                  SharedOfflineImmersiveMediaLibrary.snapshot(
                      appContext,
                      forceRefresh = true,
                  )
              publish(snapshot)
              if (onSuccess != null) {
                mainHandler.post { if (!closed.get()) onSuccess(snapshot) }
              }
            }
          } finally {
            refreshInFlight.set(false)
          }
        }) {
      refreshInFlight.set(false)
    }
    return pending
  }

  /** Persists and validates a user-selected tree without blocking the Activity/main thread. */
  fun adoptTreeUri(
      treeUri: Uri,
      returnedGrantFlags: Int,
      onSuccess: (SharedOfflineImmersiveMediaLibrarySnapshot) -> Unit,
      onFailure: (Throwable) -> Unit,
  ) {
    if (closed.get()) return
    publish(latestSnapshot.copy(configured = true, status = "folder-adoption-pending"))
    postToWorker {
      val result =
          runCatching {
            SharedOfflineImmersiveMediaLibrary.adoptTreeUri(
                appContext,
                treeUri,
                returnedGrantFlags,
            )
          }
      if (closed.get()) return@postToWorker
      result
          .onSuccess { snapshot ->
            publish(snapshot)
            mainHandler.post { if (!closed.get()) onSuccess(snapshot) }
          }
          .onFailure { error ->
            publish(
                SharedOfflineImmersiveMediaLibrary.statusWithoutScan(appContext).copy(
                    status = "folder-adoption-failed"
                )
            )
            mainHandler.post { if (!closed.get()) onFailure(error) }
          }
    }
  }

  private fun publish(snapshot: SharedOfflineImmersiveMediaLibrarySnapshot) {
    latestSnapshot = snapshot
    mainHandler.post {
      if (!closed.get()) observers.forEach { observer -> observer(snapshot) }
    }
  }

  private fun postToWorker(block: () -> Unit): Boolean {
    val handler =
        synchronized(workerLock) {
          if (closed.get()) {
            null
          } else {
            workerHandler
                ?: HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)
                    .also { thread ->
                      thread.start()
                      workerThread = thread
                      workerHandler = Handler(thread.looper)
                    }
                    .let { workerHandler }
          }
        }
    return handler?.post(block) == true
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    observers.clear()
    mainHandler.removeCallbacksAndMessages(null)
    synchronized(workerLock) {
      workerHandler?.removeCallbacksAndMessages(null)
      workerHandler = null
      workerThread?.quitSafely()
      workerThread = null
    }
  }

  private companion object {
    const val WORKER_THREAD_NAME = "RqSharedMediaControl"
  }
}
