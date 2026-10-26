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

  @Test fun originalIdsOptInOnlyForTheEightClosedRuntimeMutators() {
    val original = "a".repeat(32)
    val policy = mapOf<String,Any?>("center" to 0,"middle" to 1,"outer" to 0,
        "geometry" to 1,"brightness" to 2,"strength" to 0)
    val blend = mapOf<String,Any?>("version" to 1,"enabled" to true,"threshold" to 0.25f,
        "softness" to 0.125f,"amount" to 0.75f,"invert" to false,"diagnostic" to true)
    for (method in listOf("start","renew_authority","peer_stop","peer_revoke","whole_app_close",
        "concurrent_arm","policy_update","blend_update")) {
      val fields = when (method) { "policy_update" -> policy; "blend_update" -> blend; else -> emptyMap() }
      val direct = EmbeddedDuplexOperatorContract.parseFields(method, nonce, fields)
      val explicit = EmbeddedDuplexOperatorContract.parseFields(method, nonce, fields + ("original_id" to original))
      assertEquals(null, direct.originalId)
      assertEquals(original, explicit.originalId)
      assertEquals(direct.route, explicit.route)
      assertEquals(direct.challenge, explicit.challenge)
      if (method == "policy_update") assertTrue(direct.policy!!.contentEquals(explicit.policy!!))
      if (method == "blend_update") {
        assertTrue(direct.mask!!.contentEquals(explicit.mask!!))
        assertTrue(explicit.mask!!.contentEquals(longArrayOf(1,1,0x3e800000,0x3e000000,0x3f400000,0,1)))
      }
      for (badId in listOf<Any?>(null, 12, "A".repeat(32), "a".repeat(31), "a".repeat(33))) {
        assertThrows(IllegalArgumentException::class.java) {
          EmbeddedDuplexOperatorContract.parseFields(method, nonce, fields + ("original_id" to badId))
        }
      }
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields(method, nonce, fields + ("original_id" to original) + ("json" to "{}"))
      }
    }
    for (method in listOf("runtime_status","peer_status","concurrent_status","policy_read","blend_read",
        "bootstrap_real_peer","close_no_media","pair_status","pair_session","start_preflight",
        "owner_failure_diagnostic","own_capture_diagnostic","stream_dropout_diagnostic")) {
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields(method, nonce, mapOf("original_id" to original))
      }
    }
    for ((method, base) in listOf("review" to fields, "status" to mapOf("role_id" to "peer_a"),
        "confirm" to mapOf("review_sha256" to "b".repeat(64)))) {
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields(method, nonce, base + ("original_id" to original))
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("blend_update", nonce,
          blend + ("original_id" to original) + ("threshold" to -0.0f))
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("policy_update", nonce,
          policy + ("original_id" to original) + ("strength" to 3))
    }
  }

  @Test fun originalQueryAndAcknowledgmentAreTypedAndClosed() {
    val original = "a".repeat(32)
    val queryFields = mapOf<String,Any?>("original_id" to original)
    val ackFields = queryFields + mapOf("request_sha256" to "b".repeat(64),
        "outcome_sha256" to "c".repeat(64), "consumer_receipt_sha256" to "d".repeat(64))
    val query = EmbeddedDuplexOperatorContract.parseFields("operation_query", nonce, queryFields)
    assertEquals(EmbeddedDuplexOperatorContract.Route.OPERATION_QUERY, query.route)
    assertEquals(original, query.originalId)
    val ack = EmbeddedDuplexOperatorContract.parseFields("operation_ack", nonce, ackFields)
    assertEquals(EmbeddedDuplexOperatorContract.Route.OPERATION_ACK, ack.route)
    assertEquals(original, ack.originalId)
    assertEquals("b".repeat(64), ack.requestSha256)
    assertEquals("c".repeat(64), ack.outcomeSha256)
    assertEquals("d".repeat(64), ack.consumerReceiptSha256)
    for (method in listOf("operation_query", "operation_ack")) {
      val selected = if (method == "operation_query") queryFields else ackFields
      for (invalid in listOf(selected - "original_id", selected + ("original_id" to "a".repeat(31)),
          selected + ("original_id" to 1), selected + ("operation" to "start"), selected + ("request" to "{}"))) {
        assertThrows(IllegalArgumentException::class.java) {
          EmbeddedDuplexOperatorContract.parseFields(method, nonce, invalid)
        }
      }
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields(method, "A".repeat(32), selected)
      }
    }
    for (key in listOf("request_sha256", "outcome_sha256", "consumer_receipt_sha256")) {
      for (invalid in listOf(ackFields - key, ackFields + (key to "A".repeat(64)),
          ackFields + (key to "e".repeat(63)), ackFields + (key to 12))) {
        assertThrows(IllegalArgumentException::class.java) {
          EmbeddedDuplexOperatorContract.parseFields("operation_ack", nonce, invalid)
        }
      }
    }
  }

  @Test fun neutralMaskIsExplicitVersionedTypedAndClosed() {
    val selected = mapOf<String,Any?>("version" to 1,"enabled" to true,"threshold" to 0.5f,
        "softness" to 0.1f,"amount" to 1f,"invert" to false,"diagnostic" to true)
    val accepted=EmbeddedDuplexOperatorContract.parseFields("blend_update",nonce,selected)
    assertEquals(EmbeddedDuplexOperatorContract.Route.BLEND_UPDATE,accepted.route)
    assertTrue(accepted.mask!!.contentEquals(NeutralMaskPolicy(enabled=true,amount=1f,diagnostic=true).words()))
    assertEquals(EmbeddedDuplexOperatorContract.Route.BLEND_READ,
        EmbeddedDuplexOperatorContract.parseFields("blend_read",nonce,emptyMap()).route)
    for(bad in listOf(selected+("version" to 2),selected+("threshold" to Float.NaN),
        selected+("threshold" to -0.0f),selected+("softness" to 0f),selected+("amount" to 2f),
        selected+("enabled" to 1),selected+("threshold" to 0.5),selected+("source" to "peer"),
        selected-"diagnostic",selected+("enabled" to false))) {
      assertThrows(IllegalArgumentException::class.java){EmbeddedDuplexOperatorContract.parseFields("blend_update",nonce,bad)}
    }
    assertThrows(IllegalArgumentException::class.java){EmbeddedDuplexOperatorContract.parseFields("blend_read",nonce,selected)}
    assertTrue(NeutralMaskPolicy().words().contentEquals(longArrayOf(1,0,0.5f.toRawBits().toLong(),0.1f.toRawBits().toLong(),0,0,0)))
  }

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
    listOf("runtime_status", "bootstrap_real_peer", "close_no_media",
        "pair_status", "pair_session", "start_preflight", "owner_failure_diagnostic").forEach { method ->
      val typed = EmbeddedDuplexOperatorContract.parseFields(method, nonce, emptyMap())
      assertEquals(null, typed.draft)
      assertEquals(null, typed.roleId)
      assertEquals(null, typed.reviewSha256)
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields(method, nonce,
            mapOf("operation" to "media_command"))
      }
    }
  }

  @Test fun callerCannotSmuggleFixtureJsonGenericCommandsOrInvalidIdentity() {
    listOf("run", "start", "stop", "revoke", "native-command", "intent", "component",
        "query", "delete", "openFile").forEach {
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
