package io.github.mesmerprism.rustyquest.spatial_video_control

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Process-local bridge reached only by the debug manifest's DUMP-protected provider. */
internal interface DebugShellControlTarget {
  fun debugStatus(): String

  fun debugEnable(accessMode: ManifoldAuthorityPort.AccessMode): String

  fun debugRevoke(): String
}

internal object DebugShellControlBridge {
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var target = WeakReference<DebugShellControlTarget>(null)

  fun attach(next: DebugShellControlTarget) {
    check(BuildConfig.DEBUG) { "debug shell bridge is disabled in release builds" }
    target = WeakReference(next)
  }

  fun detach(prior: DebugShellControlTarget) {
    if (target.get() === prior) {
      target.clear()
    }
  }

  fun status(): String = call(DebugShellControlTarget::debugStatus)

  fun enablePaired(): String =
      call { it.debugEnable(ManifoldAuthorityPort.AccessMode.PAIRED) }

  fun enableOpenLan(): String =
      call { it.debugEnable(ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE) }

  fun revoke(): String = call(DebugShellControlTarget::debugRevoke)

  private fun call(operation: (DebugShellControlTarget) -> String): String {
    check(BuildConfig.DEBUG) { "debug shell bridge is disabled in release builds" }
    val current = target.get() ?: error("foreground_activity_unavailable")
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return operation(current)
    }
    val done = CountDownLatch(1)
    var result: Result<String>? = null
    mainHandler.post {
      result = runCatching { operation(current) }
      done.countDown()
    }
    check(done.await(4, TimeUnit.SECONDS)) { "debug_shell_operation_timed_out" }
    return requireNotNull(result).getOrThrow()
  }
}
