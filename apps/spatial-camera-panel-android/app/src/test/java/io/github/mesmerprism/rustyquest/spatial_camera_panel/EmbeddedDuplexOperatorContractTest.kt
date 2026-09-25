package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Process
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedDuplexOperatorContractTest {
  private val nonce = "0123456789abcdef0123456789abcdef"
  private val fields = mapOf<String, Any?>(
      "role_id" to "peer_b",
      "remote_public_key_hex" to "ab".repeat(32),
      "runtime_host_id" to "host.runtime",
      "trusted_operator_id" to "trusted.operator",
      "adapter_id" to "quest.adapter",
      "media_revoker_id" to "media.revoker",
      "admission_authority_id" to "admission.authority",
      "max_token_ttl_ms" to 60_000L)

  @Test fun exactTypedReviewAndConfirmShapes() {
    val review = EmbeddedDuplexOperatorContract.parseFields("review", nonce, fields)
    assertEquals(EmbeddedDuplexOperatorContract.Route.REVIEW, review.route)
    assertEquals("peer_b", review.draft!!.roleId)
    assertEquals(false, review.draft!!.localFixture)
    assertEquals(60_000L, review.draft!!.maxTokenTtlMs)
    val status = EmbeddedDuplexOperatorContract.parseFields("status", nonce,
        mapOf("role_id" to "peer_a"))
    assertEquals(EmbeddedDuplexOperatorContract.Route.STATUS, status.route)
    val confirm = EmbeddedDuplexOperatorContract.parseFields("confirm", nonce,
        mapOf("review_sha256" to "f".repeat(64)))
    assertEquals(EmbeddedDuplexOperatorContract.Route.CONFIRM, confirm.route)
    assertEquals("f".repeat(64), confirm.reviewSha256)
  }

  @Test fun callerCannotSmuggleFixtureJsonGenericCommandsOrInvalidIdentity() {
    listOf("run", "native-command", "intent", "component", "query", "delete", "openFile").forEach {
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields(it, nonce, fields)
      }
    }
    listOf(fields + ("fixture" to true), fields + ("json" to "{}"),
        fields + ("operation" to "issue"), fields - "adapter_id",
        fields + ("max_token_ttl_ms" to "60000"),
        fields + ("remote_public_key_hex" to "AB".repeat(32)),
        fields + ("role_id" to "peer_c"),
        fields + ("trusted_operator_id" to "caller supplied")).forEach { invalid ->
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields("review", nonce, invalid)
      }
    }
    listOf("content://x", "/tmp/x", "A".repeat(32), "0".repeat(31)).forEach {
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields("status", it, mapOf("role_id" to "peer_a"))
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("confirm", nonce,
          mapOf("review_sha256" to "F".repeat(64)))
    }
  }

  @Test fun debugOnlyShellAndPackageScopedAuthority() {
    assertTrue(EmbeddedDuplexOperatorContract.callerIsShell(Process.SHELL_UID))
    assertFalse(EmbeddedDuplexOperatorContract.callerIsShell(Process.SYSTEM_UID))
    val appRoot = File(System.getProperty("user.dir") ?: ".")
    val debug = File(appRoot, "src/debug/AndroidManifest.xml").readText()
    val main = File(appRoot, "src/main/AndroidManifest.xml").readText()
    assertTrue(debug.contains("EmbeddedDuplexOperatorProvider"))
    assertTrue(debug.contains("${'$'}{applicationId}${EmbeddedDuplexOperatorContract.AUTHORITY_SUFFIX}"))
    assertTrue(debug.contains("android:permission=\"android.permission.DUMP\""))
    assertFalse(main.contains("EmbeddedDuplexOperatorProvider"))
    assertFalse(main.contains(EmbeddedDuplexOperatorContract.AUTHORITY_SUFFIX))
    val release = File(appRoot, "src/release")
    if (release.exists()) release.walkTopDown().filter { it.isFile }.forEach {
      assertFalse(it.path, it.readText().contains("EmbeddedDuplexOperatorProvider"))
    }
  }

  @Test fun reviewedObjectIsOneUseAndChallengeBound() {
    val slot = EmbeddedDuplexOperatorReviewSlot<Any>()
    val reviewed = Any()
    val digest = "a".repeat(64)
    slot.install(nonce, digest, reviewed)
    assertThrows(IllegalArgumentException::class.java) {
      slot.consume("f".repeat(32), digest)
    }
    assertThrows(IllegalArgumentException::class.java) {
      slot.consume(nonce, "b".repeat(64))
    }
    assertSame(reviewed, slot.consume(nonce, digest))
    assertThrows(IllegalArgumentException::class.java) { slot.consume(nonce, digest) }
    slot.install(nonce, digest, reviewed)
    slot.clear()
    assertThrows(IllegalArgumentException::class.java) { slot.consume(nonce, digest) }
  }
}
