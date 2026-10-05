package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.ExecutorService

/** Fences new video lifecycle work and appends destroy cleanup behind all accepted work. */
internal class SpatialVideoLifecycleShutdownCoordinator(
    private val executor: ExecutorService,
) {
  private val lock = Any()
  private var fenced = false

  fun dispatch(action: () -> Unit): Boolean = synchronized(lock) {
    if (fenced) return@synchronized false
    executor.execute(action)
    true
  }

  fun beginOrderedShutdown(
      retireProjectionPeer: () -> Boolean,
      finishCleanup: (peerRetired: Boolean) -> Unit,
  ): Boolean = synchronized(lock) {
    if (fenced) return@synchronized false
    fenced = true
    executor.execute {
      val peerRetired = runCatching(retireProjectionPeer).getOrDefault(false)
      try {
        finishCleanup(peerRetired)
      } finally {
        executor.shutdown()
      }
    }
    true
  }

  fun isFenced(): Boolean = synchronized(lock) { fenced }
}
