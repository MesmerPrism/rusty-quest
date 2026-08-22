package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugHostReceiptStoreTest {
  private val nonce = "a".repeat(64)
  private val epoch = "c".repeat(32)

  @Test
  fun nonceIsSingleUseEpochBoundAndCleanupIsReceiptHashBound() {
    var now = 1_000L
    val root = temporaryRoot()
    val store = DebugHostReceiptStore(root, { now })
    val expiry = store.arm(nonce, epoch)
    assertTrue(expiry > now)
    assertEquals("armed", store.status().value)
    assertThrows(IllegalStateException::class.java) { store.arm("d".repeat(64), epoch) }
    assertThrows(IllegalArgumentException::class.java) {
      store.finalizeReceipt(nonce, identity(epoch.copyWith("d", 0)), facts())
    }

    val receiptHash = store.finalizeReceipt(nonce, identity(epoch), facts())
    assertEquals("terminal", store.status().value)
    assertEquals(receiptHash, store.status().receiptHash)
    assertTrue(store.read(receiptHash).contains("\"receipt_hash\":\"$receiptHash\""))
    assertThrows(IllegalArgumentException::class.java) { store.cleanup("e".repeat(64)) }
    assertTrue(File(root, "receipt.v1.json").exists())
    store.cleanup(receiptHash)
    assertFalse(File(root, "receipt.v1.json").exists())
    assertThrows(IllegalStateException::class.java) { store.finalizeReceipt(nonce, identity(epoch), facts()) }
  }

  @Test
  fun expiredStateAndReplayFailClosed() {
    var now = 10_000L
    val store = DebugHostReceiptStore(temporaryRoot(), { now })
    store.arm(nonce, epoch)
    now += DebugHostReceiptContract.NONCE_TTL_MS + 1
    assertEquals("stale", store.status().value)
    assertThrows(IllegalStateException::class.java) { store.finalizeReceipt(nonce, identity(epoch), facts()) }
    assertTrue(store.arm("b".repeat(64), epoch) > now)
  }

  @Test
  fun atomicFinalizationHasNoPendingStateAndDamagedOrOversizedDataIsRejected() {
    val root = temporaryRoot()
    val store = DebugHostReceiptStore(root)
    store.arm(nonce, epoch)
    val receiptHash = store.finalizeReceipt(nonce, identity(epoch), facts())
    assertFalse(File(root, "state.v1").exists())
    assertTrue(File(root, "receipt.v1.json").exists())
    assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    val receipt = File(root, "receipt.v1.json")
    receipt.writeText(store.read(receiptHash).replace("\"prepared\"", "\"damaged\""))
    assertThrows(IllegalArgumentException::class.java) { store.read(receiptHash) }

    val bounded = DebugHostReceiptStore(temporaryRoot(), maximumReceiptBytes = 128)
    bounded.arm(nonce, epoch)
    assertThrows(IllegalArgumentException::class.java) {
      bounded.finalizeReceipt(nonce, identity(epoch), facts())
    }
    assertEquals("terminal-unavailable", bounded.status().value)
  }

  @Test
  fun receiptIsHashChainedAndPrivacySanitized() {
    val store = DebugHostReceiptStore(temporaryRoot())
    store.arm(nonce, epoch)
    val receiptHash = store.finalizeReceipt(nonce, identity(epoch), facts())
    val receipt = store.read(receiptHash)
    assertFalse(receipt.contains(nonce))
    assertTrue(receipt.contains("\"nonce_hash\""))
    assertTrue(receipt.contains("\"previous_hash\""))
    assertFalse(receipt.contains("content://"))
    assertFalse(receipt.contains("/private/"))
    assertNotEquals("0".repeat(64), receiptHash)
  }

  private fun identity(epoch: String) =
      DebugHostReceiptStore.Identity(
          applicationId = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
          apkSha256 = "f".repeat(64),
          versionCode = 1,
          versionName = "0.1.0",
          variant = "debug",
          pid = 1234,
          epoch = epoch,
      )

  private fun facts() =
      DebugHostReceiptContract.FACT_TYPES.map { type -> DebugHostReceiptStore.Fact(type, "accepted") }

  private fun temporaryRoot(): File = Files.createTempDirectory("debug-host-receipt-test-").toFile()

  private fun String.copyWith(value: String, at: Int): String = replaceRange(at, at + value.length, value)
}
