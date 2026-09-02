package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugHostReceiptContractTest {
  private val nonce = "a".repeat(64)
  private val hash = "b".repeat(64)

  @Test
  fun fixedNonceHashAndFactVocabularyAreClosed() {
    assertEquals(nonce, DebugHostReceiptContract.parseArmNonce(nonce))
    assertEquals(hash, DebugHostReceiptContract.requireReceiptHash(hash))
    assertEquals(
        listOf(
            "source",
            "grant",
            "decoder",
            "max-count",
            "decoded-geometry",
            "prepared",
            "advancing-frame",
            "cadence",
            "render-adoption",
            "error",
            "terminal",
        ),
        DebugHostReceiptContract.FACT_TYPES,
    )
    listOf("", "A".repeat(64), "a".repeat(63), "a".repeat(64) + "/path").forEach { bad ->
      assertThrows(IllegalArgumentException::class.java) {
        DebugHostReceiptContract.parseArmNonce(bad)
      }
    }
  }

  @Test
  fun onlyShellUidMayReachTheProviderContract() {
    assertTrue(DebugHostReceiptContract.callerIsShell(Process.SHELL_UID))
    assertFalse(DebugHostReceiptContract.callerIsShell(Process.SHELL_UID + 1))
    assertFalse(DebugHostReceiptContract.callerIsShell(0))
  }

  @Test
  fun statusReadAndCleanupRejectGenericArgumentsAndBundles() {
    assertEquals(
        DebugHostReceiptContract.Route.STATUS,
        DebugHostReceiptContract.parseCall(DebugHostReceiptContract.METHOD_STATUS, null, null).route,
    )
    assertEquals(
        hash,
        DebugHostReceiptContract.parseCall(DebugHostReceiptContract.METHOD_READ, hash, null).receiptHash,
    )
    assertEquals(
        hash,
        DebugHostReceiptContract.parseCall(DebugHostReceiptContract.METHOD_CLEANUP, hash, null).receiptHash,
    )
    listOf(
        Triple("unknown", null, null),
        Triple(DebugHostReceiptContract.METHOD_STATUS, "argument", null),
        Triple(DebugHostReceiptContract.METHOD_READ, null, null),
        Triple(DebugHostReceiptContract.METHOD_CLEANUP, "content://bad", null),
    ).forEach { (method, argument, extras) ->
      assertThrows(IllegalArgumentException::class.java) {
        DebugHostReceiptContract.parseCall(method, argument, extras)
      }
    }
  }
}
