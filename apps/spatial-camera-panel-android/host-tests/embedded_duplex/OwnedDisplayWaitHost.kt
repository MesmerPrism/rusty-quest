package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import io.github.mesmerprism.rustyquest.spatial_camera_panel.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real serialized coordinator; dispatch queue and Android Looper are explicit host leaves. */
internal object OwnedDisplayWaitHost {
  @JvmStatic fun main(args: Array<String>) {
    val interrupted = Fixture()
    val caller = interrupted.begin()
    check(interrupted.enqueued.await(2, TimeUnit.SECONDS))
    caller.interrupt()
    caller.join(100)
    check(caller.isAlive) { "interrupted caller abandoned its queued lifecycle action" }
    interrupted.finish()
    caller.join(2000)
    check(!caller.isAlive && interrupted.failure.get() == null && interrupted.interrupted)
    check(interrupted.dispatches == 1 && interrupted.effects == 1)

    val failed = Fixture(true)
    val failedCaller = failed.begin()
    check(failed.enqueued.await(2, TimeUnit.SECONDS))
    failedCaller.interrupt()
    failedCaller.join(100)
    check(failedCaller.isAlive)
    failed.finish(); failedCaller.join(2000)
    check(!failedCaller.isAlive && failed.interrupted)
    check(generateSequence(failed.failure.get()) { it.cause }.any { it.message == "archived renderer ID differs" })
    check(failed.dispatches == 1 && failed.effects == 1)

    val delayed = Fixture()
    val delayedCaller = delayed.begin()
    check(delayed.enqueued.await(2, TimeUnit.SECONDS))
    Thread.sleep(30_200)
    check(delayedCaller.isAlive && delayed.failure.get() == null)
    check(delayed.dispatches == 1 && delayed.effects == 0)
    delayed.finish(); delayedCaller.join(2000)
    check(!delayedCaller.isAlive && delayed.failure.get() == null && delayed.effects == 1)

    val fenced = Fixture(fenced = true)
    val fencedCaller = fenced.begin(); fencedCaller.join(2000)
    check(fenced.failure.get()?.message == "Activity lifecycle is already fenced")
    check(fenced.effects == 0)
    android.os.Looper.setMainThread(true)
    try {
      check(runCatching { delayed.display.retireArchivedPreparedRenderer("main") }.exceptionOrNull()
          ?.message == "embedded lifecycle must not block the UI thread")
    } finally { android.os.Looper.setMainThread(false) }
    println("PASS production serialized queue: >30s same action, interrupts retain custody and real outcome, single dispatch, fencing and UI-thread controls")
  }

  private class Fixture(val fail: Boolean = false, val fenced: Boolean = false) {
    val enqueued = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    @Volatile var interrupted = false
    var dispatches = 0
    var effects = 0
    private var held: (() -> Unit)? = null
    private val adapter = java.lang.reflect.Proxy.newProxyInstance(
        SpatialVideoSourceExecutionAdapter::class.java.classLoader,
        arrayOf(SpatialVideoSourceExecutionAdapter::class.java)) { _, method, _ ->
          error("unexpected routing leaf ${method.name}")
        } as SpatialVideoSourceExecutionAdapter
    private val routing = SpatialVideoSourceRoutingCoordinator(adapter) { 1000L }
    val display = EmbeddedDuplexDisplayCoordinator(routing, { action ->
      dispatches++
      if (fenced) false else { held = action; enqueued.countDown(); true }
    }, { false }, { null }, { false }, { false }, { null }, { longArrayOf() }, { false })
    fun begin() = Thread({
      try { display.retireArchivedPreparedRenderer("same-preparation") }
      catch (actual: Throwable) { failure.set(actual) }
      finally { interrupted = Thread.currentThread().isInterrupted }
    }, "owned-lifecycle-caller").also { it.isDaemon = true; it.start() }
    fun finish() {
      effects++
      if (fail) {
        // Make the production action itself reject its still-retained exact identity.
        display.javaClass.getDeclaredField("preparedRendererId").also { it.isAccessible = true }
            .set(display, "actual action failure")
        held!!.invoke()
      } else held!!.invoke()
    }
  }
}
