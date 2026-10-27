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
  @Test fun explicitRunCallsAreClosedAndRetainActualOperation() {
    val run="b".repeat(32); val original="c".repeat(32)
    val create=EmbeddedDuplexOperatorContract.parseFields("new_run",nonce,mapOf("run_id" to run,"legacy_whole_original_id" to original))
    assertEquals("NEW_RUN",create.route.name); assertEquals(run,create.runId);assertEquals(original,create.legacyWholeOriginalId)
    assertEquals("RESUME_RUN",EmbeddedDuplexOperatorContract.parseFields("resume_run",nonce,mapOf("run_id" to run)).route.name)
    assertEquals("RUN_STATUS",EmbeddedDuplexOperatorContract.parseFields("run_status",nonce,emptyMap()).route.name)
    val start=EmbeddedDuplexOperatorContract.parseFields("start",nonce,mapOf("run_id" to run,"original_id" to original,"prepare_preflight" to true))
    assertEquals("ORDINARY_START",start.route.name);assertEquals(run,start.runId);assertEquals(original,start.originalId)
    assertEquals(run,EmbeddedDuplexOperatorContract.parseFields("operation_query",nonce,mapOf("run_id" to run,"original_id" to original)).runId)
    assertEquals(run,EmbeddedDuplexOperatorContract.parseFields("operation_ack",nonce,mapOf("run_id" to run,"original_id" to original,
        "request_sha256" to "d".repeat(64),"outcome_sha256" to "e".repeat(64),"consumer_receipt_sha256" to "f".repeat(64))).runId)
    for(fields in listOf(mapOf("run_id" to "../escape"),mapOf("run_id" to run,"path" to "/outside"),emptyMap<String,Any>()))
      assertThrows(IllegalArgumentException::class.java){EmbeddedDuplexOperatorContract.parseFields("new_run",nonce,fields)}
    assertThrows(IllegalArgumentException::class.java){EmbeddedDuplexOperatorContract.parseFields("resume_run",nonce,mapOf("run_id" to run,"legacy_whole_original_id" to original))}
    assertThrows(IllegalArgumentException::class.java){EmbeddedDuplexOperatorContract.parseFields("run_status",nonce,mapOf("run_id" to run))}
  }
  @Test fun ordinaryStartUsesSelectedDepthPermissionDemand() {
    for (layer in listOf(-1.0f, 0.0f, 5.0f, 6.0f)) {
      assertFalse(embeddedDuplexStartNeedsScenePermission("disabled", layer))
      assertTrue(embeddedDuplexStartNeedsScenePermission("legacy-native-sidecar", layer))
      assertTrue(embeddedDuplexStartNeedsScenePermission("spatial-sdk-api-layer", layer))
    }
    for (layer in listOf(1.0f, 2.0f, 3.0f, 4.0f, 7.0f, 8.0f)) {
      assertFalse(embeddedDuplexStartNeedsScenePermission("legacy-native-sidecar", layer))
      assertFalse(embeddedDuplexStartNeedsScenePermission("spatial-sdk-api-layer", layer))
    }
  }
  @Test fun ordinaryStartIsAnExplicitOwnedStartChoice() {
    val original = "a".repeat(32)
    val low = EmbeddedDuplexOperatorContract.parseFields("start", nonce, mapOf("original_id" to original))
    val ordinary = EmbeddedDuplexOperatorContract.parseFields("start", nonce,
        mapOf("original_id" to original, "prepare_preflight" to true))
    assertEquals("START", low.route.name)
    assertEquals("ORDINARY_START", ordinary.route.name)
    assertEquals(original, ordinary.originalId)
    assertEquals(nonce, ordinary.challenge)
    for (invalid in listOf<Any?>(false, 1, "true", null)) assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("start", nonce,
          mapOf("original_id" to original, "prepare_preflight" to invalid))
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("start", nonce, mapOf("prepare_preflight" to true))
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("peer_stop", nonce,
          mapOf("original_id" to original, "prepare_preflight" to true))
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("start", nonce,
          mapOf("original_id" to original, "prepare_preflight" to true, "native_command" to "start"))
    }
  }
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
        "bootstrap_real_peer","close_no_media","pair_status","start_preflight",
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

  @Test fun freshCallsKeepClosedOriginalAndContinuationShapes() {
    val original = "a".repeat(32)
    val selected = mapOf<String,Any?>("original_id" to original)
    for (method in listOf("prepare_peer_start", "reestablish_peer_roots", "pair_session")) {
      assertEquals(original, EmbeddedDuplexOperatorContract.parseFields(method, nonce, selected).originalId)
      assertThrows(IllegalArgumentException::class.java) {
        EmbeddedDuplexOperatorContract.parseFields(method, nonce, selected + ("json" to "{}"))
      }
    }
    val continued = selected + mapOf("prior_original_id" to "b".repeat(32), "prior_outcome_sha256" to "c".repeat(64))
    val parsed = EmbeddedDuplexOperatorContract.parseFields("fresh_pair_continue", nonce, continued)
    assertEquals("b".repeat(32), parsed.priorOriginalId)
    assertEquals("c".repeat(64), parsed.priorOutcomeSha256)
    for (bad in listOf(continued - "prior_outcome_sha256", continued + ("prior_outcome_sha256" to "C".repeat(64)),
        continued + ("prior_original_id" to 1), continued + ("operation" to "arbitrary"))) {
      assertThrows(IllegalArgumentException::class.java) { EmbeddedDuplexOperatorContract.parseFields("fresh_pair_continue", nonce, bad) }
    }
    for (method in listOf("operation_join_status", "operation_continuation_status", "operation_continuation_handoff")) {
      assertEquals(original, EmbeddedDuplexOperatorContract.parseFields(method, nonce, selected).originalId)
      assertThrows(IllegalArgumentException::class.java) { EmbeddedDuplexOperatorContract.parseFields(method, nonce, emptyMap()) }
    }
    for (method in listOf("prepared_peer_start_status", "peer_root_status", "pair_setup_recovery_status")) {
      EmbeddedDuplexOperatorContract.parseFields(method, nonce, emptyMap())
      assertThrows(IllegalArgumentException::class.java) { EmbeddedDuplexOperatorContract.parseFields(method, nonce, selected) }
    }
    val stop = selected + mapOf("request_sha256" to "d".repeat(64), "stop_intent_sha256" to "e".repeat(64))
    assertEquals("e".repeat(64), EmbeddedDuplexOperatorContract.parseFields("operation_stop", nonce, stop).stopIntentSha256)
    assertThrows(IllegalArgumentException::class.java) { EmbeddedDuplexOperatorContract.parseFields("operation_stop", nonce, stop + ("stop_intent_sha256" to 1)) }
    for (method in listOf("owner_succession_commit", "abandon_retained_scope", "new_run", "data")) {
      assertThrows(IllegalArgumentException::class.java) { EmbeddedDuplexOperatorContract.parseFields(method, nonce, selected) }
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
