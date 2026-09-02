package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DebugHostReceiptProviderTest {
  @Test
  fun providerRoutesAreLimitedToFixedCallVocabulary() {
    val hash = "f".repeat(64)
    assertEquals(
        DebugHostReceiptContract.Route.READ,
        DebugHostReceiptContract.parseCall(DebugHostReceiptContract.METHOD_READ, hash, null).route,
    )
    assertEquals(
        DebugHostReceiptContract.Route.CLEANUP,
        DebugHostReceiptContract.parseCall(DebugHostReceiptContract.METHOD_CLEANUP, hash, null).route,
    )
    listOf("query", "insert", "update", "delete", "openFile", "intent", "component").forEach { route ->
      assertThrows(IllegalArgumentException::class.java) {
        DebugHostReceiptContract.parseCall(route, null, null)
      }
    }
  }

  @Test
  fun receiptReadAndCleanupCannotUseUrisOrUnvalidatedHashes() {
    listOf("content://authority/path", "/data/local/tmp/x", "0".repeat(63), "F".repeat(64)).forEach {
        candidate ->
      assertThrows(IllegalArgumentException::class.java) {
        DebugHostReceiptContract.parseCall(DebugHostReceiptContract.METHOD_READ, candidate, null)
      }
      assertThrows(IllegalArgumentException::class.java) {
        DebugHostReceiptContract.parseCall(DebugHostReceiptContract.METHOD_CLEANUP, candidate, null)
      }
    }
  }

  @Test
  fun providerFirstArmLoadsNativeLibraryOnceBeforeEveryReset() {
    val events = mutableListOf<String>()
    val boundary =
        SpatialLaunchQualificationNativeBoundary(
            loadLibrary = { events += "load" },
            resetQualification = { events += "reset" },
        )

    boundary.ensureLoadedAndReset()
    boundary.ensureLoadedAndReset()

    assertEquals(listOf("load", "reset", "reset"), events)
  }

  @Test
  fun providerFirstArmNormalizesNativeLoadAndLinkageFailures() {
    listOf(UnsatisfiedLinkError("missing"), SecurityException("denied")).forEach { failure ->
      val boundary =
          SpatialLaunchQualificationNativeBoundary(
              loadLibrary = { throw failure },
              resetQualification = { throw AssertionError("reset must not run") },
          )
      val rejected =
          assertThrows(IllegalStateException::class.java) { boundary.ensureLoadedAndReset() }
      assertEquals("debug_host_receipt_native_unavailable", rejected.message)
      assertEquals(failure, rejected.cause)
    }
  }
}
