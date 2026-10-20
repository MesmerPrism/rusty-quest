package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

/** Modeled wait estimate; actual retained CompletableFuture completion, no elapsed sleep. */
class EmbeddedDuplexOperatorAwaitTest {
  private class Estimated<T>(val original: CompletableFuture<T>) : Future<T> {
    var timedReads = 0
    var actualReads = 0
    var cancellations = 0
    override fun get(timeout: Long, unit: TimeUnit): T {
      timedReads++
      assertFalse(original.isDone)
      throw TimeoutException("modeled observer estimate")
    }
    override fun get(): T { actualReads++; return original.get() }
    override fun cancel(interrupt: Boolean): Boolean { cancellations++; return original.cancel(interrupt) }
    override fun isCancelled() = original.isCancelled
    override fun isDone() = original.isDone
  }

  @Test fun estimateKeepsSameOriginalUntilActualCompletion() {
    val original = CompletableFuture<String>()
    val held = Estimated(original)
    var warnings = 0
    val result = held.awaitOperatorCompletion(30) {
      warnings++
      assertFalse(original.isDone)
      original.complete("actual-original-result")
    }
    assertEquals("actual-original-result", result)
    assertEquals(1, warnings); assertEquals(1, held.timedReads)
    assertEquals(1, held.actualReads); assertEquals(0, held.cancellations)
  }

  @Test fun warningSinkFailureCannotReplaceActualCompletion() {
    val original = CompletableFuture<String>()
    val held = Estimated(original)
    assertEquals("actual", held.awaitOperatorCompletion(120) {
      original.complete("actual")
      throw IOException("modeled warning sink")
    })
    assertEquals(1, held.actualReads); assertEquals(0, held.cancellations)
  }

  @Test fun actualFailureSurvivesEstimateWithoutNoEffectClaim() {
    val original = CompletableFuture<String>()
    val held = Estimated(original)
    val nativeFailure = IllegalStateException("modeled native failure after a partial effect")
    val observed = assertThrows(ExecutionException::class.java) {
      held.awaitOperatorCompletion(120) { original.completeExceptionally(nativeFailure) }
    }
    assertSame(nativeFailure, observed.cause)
    assertEquals(1, held.actualReads); assertEquals(0, held.cancellations)
  }

  @Test fun completedOriginalHasNoEstimateWarning() {
    val original = CompletableFuture.completedFuture("completed")
    var warnings = 0
    assertEquals("completed", original.awaitOperatorCompletion(10) { warnings++ })
    assertEquals(0, warnings)
  }

  @Test fun observerInterruptionRestoresFlagAndDoesNotCancelOriginal() {
    val original = CompletableFuture<String>()
    val held = Estimated(original)
    try {
      assertThrows(InterruptedException::class.java) {
        held.awaitOperatorCompletion(120) { Thread.currentThread().interrupt() }
      }
      assertTrue(Thread.currentThread().isInterrupted)
      assertFalse(original.isDone); assertFalse(original.isCancelled)
      assertEquals(0, held.cancellations)
      // The retained owner may still complete after this observer disconnected.
      original.complete("later")
    } finally { Thread.interrupted() }
    assertEquals("later", original.get())
  }
}
