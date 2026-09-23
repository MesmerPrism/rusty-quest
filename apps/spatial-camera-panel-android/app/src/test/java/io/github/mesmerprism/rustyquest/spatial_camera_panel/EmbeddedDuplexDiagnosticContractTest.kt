package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedDuplexDiagnosticContractTest {
  private val nonce = "0123456789abcdef0123456789abcdef"

  @Test
  fun onlyExactChallengeArmAndReadShapesAreAccepted() {
    assertEquals(
        EmbeddedDuplexDiagnosticContract.Route.ARM,
        EmbeddedDuplexDiagnosticContract.parseCall("arm", nonce, null).route,
    )
    assertEquals(
        EmbeddedDuplexDiagnosticContract.Route.READ,
        EmbeddedDuplexDiagnosticContract.parseCall("read", nonce, null).route,
    )
    assertEquals(
        EmbeddedDuplexDiagnosticContract.Route.ARM,
        EmbeddedDuplexDiagnosticContract.parseCallKeys("arm", nonce, emptySet()).route,
    )
    assertEquals(
        EmbeddedDuplexDiagnosticContract.Route.READ,
        EmbeddedDuplexDiagnosticContract.parseCallKeys("read", nonce, emptySet()).route,
    )
    listOf("status", "cleanup", "query", "insert", "delete", "intent", "component").forEach {
        method ->
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexDiagnosticContract.parseCall(method, nonce, null)
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexDiagnosticContract.parseCallKeys("arm", nonce, setOf("extra"))
    }
  }

  @Test
  fun challengeRejectsPathsUrisAndNoncanonicalHex() {
    listOf(
            "content://authority/path",
            "/data/local/tmp/receipt",
            "0".repeat(31),
            "0".repeat(33),
            "A".repeat(32),
            "g".repeat(32),
            "0".repeat(31) + "/",
        )
        .forEach { candidate ->
          assertThrows(IllegalArgumentException::class.java) {
            EmbeddedDuplexDiagnosticContract.parseCall("arm", candidate, null)
          }
          assertThrows(IllegalArgumentException::class.java) {
            EmbeddedDuplexDiagnosticContract.parseCall("read", candidate, null)
          }
        }
  }

  @Test
  fun shellUidIsTheOnlyRuntimeCaller() {
    assertTrue(EmbeddedDuplexDiagnosticContract.callerIsShell(Process.SHELL_UID))
    assertFalse(EmbeddedDuplexDiagnosticContract.callerIsShell(Process.SYSTEM_UID))
    assertFalse(EmbeddedDuplexDiagnosticContract.callerIsShell(Process.SHELL_UID + 1))
  }
}
