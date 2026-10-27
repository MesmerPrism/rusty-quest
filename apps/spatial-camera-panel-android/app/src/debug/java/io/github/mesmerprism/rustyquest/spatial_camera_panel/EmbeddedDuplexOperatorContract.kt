package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Bundle
import android.os.Process
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentDraft

/** Debug shell transport only; the process host owns every reviewed mutation. */
internal object EmbeddedDuplexOperatorContract {
  const val SCHEMA = "rusty.quest.embedded_duplex.operator_transport.v1"
  const val AUTHORITY_SUFFIX = ".embedded-duplex-operator"
  const val RUN_SCHEMA = "rusty.quest.embedded_duplex.operator_transport.v3"
  const val KEY_RUN_ID = "run_id"
  const val METHOD_OPERATION_QUERY = "operation_query"
  const val METHOD_OPERATION_ACK = "operation_ack"
  const val KEY_ORIGINAL_ID = "original_id"
  const val METHOD_STATUS = "status"
  const val METHOD_REVIEW = "review"
  const val METHOD_CONFIRM = "confirm"
  const val METHOD_RUNTIME_STATUS = "runtime_status"
  const val METHOD_OWNER_FAILURE_DIAGNOSTIC = "owner_failure_diagnostic"
  const val METHOD_OWN_CAPTURE_DIAGNOSTIC = "own_capture_diagnostic"
  const val METHOD_STREAM_DROPOUT_DIAGNOSTIC = "stream_dropout_diagnostic"
  const val METHOD_BOOTSTRAP_REAL_PEER = "bootstrap_real_peer"
  const val METHOD_BOOTSTRAP_ADMISSION_STATUS = "bootstrap_admission_status"
  const val METHOD_CLOSE_NO_MEDIA = "close_no_media"
  const val METHOD_PAIR_STATUS = "pair_status"
  const val METHOD_PAIR_SESSION = "pair_session"
  const val METHOD_START_PREFLIGHT = "start_preflight"
  const val KEY_PREPARE_PREFLIGHT = "prepare_preflight"
  const val KEY_ROLE = "role_id"
  const val KEY_REMOTE_KEY = "remote_public_key_hex"
  const val KEY_RUNTIME_HOST = "runtime_host_id"
  const val KEY_TRUSTED_OPERATOR = "trusted_operator_id"
  const val KEY_ADAPTER = "adapter_id"
  const val KEY_MEDIA_REVOKER = "media_revoker_id"
  const val KEY_ADMISSION_AUTHORITY = "admission_authority_id"
  const val KEY_TTL = "max_token_ttl_ms"
  const val KEY_REVIEW_SHA = "review_sha256"

  private val nonce = Regex("[0-9a-f]{32}")
  private val sha = Regex("[0-9a-f]{64}")
  private val key = Regex("[0-9a-f]{64}")
  private val dotted = Regex("[a-z][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+")
  private val reviewKeys = setOf(KEY_ROLE, KEY_REMOTE_KEY, KEY_RUNTIME_HOST,
      KEY_TRUSTED_OPERATOR, KEY_ADAPTER, KEY_MEDIA_REVOKER, KEY_ADMISSION_AUTHORITY, KEY_TTL)

  enum class Route { NEW_RUN, RESUME_RUN, RUN_STATUS, STATUS, REVIEW, CONFIRM, RUNTIME_STATUS, BOOTSTRAP_REAL_PEER, BOOTSTRAP_ADMISSION_STATUS,
    CLOSE_NO_MEDIA, PAIR_STATUS, PAIR_SESSION, START_PREFLIGHT, CONCURRENT_ARM, CONCURRENT_STATUS, OWNER_FAILURE_DIAGNOSTIC, OWN_CAPTURE_DIAGNOSTIC, STREAM_DROPOUT_DIAGNOSTIC, POLICY_READ, POLICY_UPDATE, BLEND_READ, BLEND_UPDATE, START, ORDINARY_START, RENEW_AUTHORITY, PEER_STOP, PEER_REVOKE, PEER_STATUS, WHOLE_APP_CLOSE, OPERATION_QUERY, OPERATION_ACK,
    FRESH_PAIR_CONTINUE, PREPARE_PEER_START, PREPARED_PEER_START_STATUS, REESTABLISH_PEER_ROOTS, PEER_ROOT_STATUS,
    OPERATION_STOP, OPERATION_JOIN_STATUS, OPERATION_CONTINUATION_STATUS, OPERATION_CONTINUATION_HANDOFF, PAIR_SETUP_RECOVERY_STATUS }
  data class Request(
      val route: Route,
      val challenge: String,
      val roleId: String? = null,
      val draft: EmbeddedDuplexEnrollmentDraft? = null,
      val reviewSha256: String? = null,
      val policy: LongArray? = null,
      val mask: LongArray? = null,
      val originalId: String? = null,
      val requestSha256: String? = null,
      val outcomeSha256: String? = null,
      val consumerReceiptSha256: String? = null,
      val priorOriginalId: String? = null,
      val priorOutcomeSha256: String? = null,
      val stopIntentSha256: String? = null,
      val runId: String? = null,
      val legacyWholeOriginalId: String? = null,
  )

  fun callerIsShell(uid: Int): Boolean = uid == Process.SHELL_UID

  fun parseCall(method: String, argument: String?, extras: Bundle?): Request {
    val fields = extras?.keySet()?.associateWith { extras.get(it) } ?: emptyMap()
    return parseFields(method, argument, fields)
  }

  internal fun parseFields(method: String, argument: String?, fields: Map<String, Any?>): Request {
    require(argument != null && nonce.matches(argument)) { "operator-challenge-invalid" }
    if (method == "new_run" || method == "resume_run" || method == "run_status") {
      if (method == "run_status") { require(fields.isEmpty()) { "operator-fields-invalid" }; return Request(Route.RUN_STATUS, argument) }
      val expected = if (method == "new_run" && fields.containsKey("legacy_whole_original_id"))
        setOf(KEY_RUN_ID, "legacy_whole_original_id") else setOf(KEY_RUN_ID)
      require(fields.keys == expected) { "operator-fields-invalid" }
      val run = fields[KEY_RUN_ID] as? String
      val legacy = fields["legacy_whole_original_id"] as? String
      require(run != null && nonce.matches(run) && (!fields.containsKey("legacy_whole_original_id") || legacy != null && nonce.matches(legacy))) { "operator-run-id-invalid" }
      return Request(if(method == "new_run") Route.NEW_RUN else Route.RESUME_RUN, argument, runId=run, legacyWholeOriginalId=legacy)
    }
    if(fields.containsKey(KEY_RUN_ID)) {
      require(method !in setOf(METHOD_STATUS,METHOD_REVIEW,METHOD_CONFIRM)) { "persistent-identity-route-is-not-run-scoped" }
      val run = fields[KEY_RUN_ID] as? String
      require(run != null && nonce.matches(run)) { "operator-run-id-invalid" }
      return parseFields(method, argument, fields - KEY_RUN_ID).copy(runId=run)
    }
    if (method == "start" && fields.containsKey(KEY_PREPARE_PREFLIGHT)) {
      require(fields.keys == setOf(KEY_ORIGINAL_ID, KEY_PREPARE_PREFLIGHT) &&
          fields[KEY_PREPARE_PREFLIGHT] == true) { "operator-fields-invalid" }
      val original = fields[KEY_ORIGINAL_ID] as? String
      require(original != null && nonce.matches(original)) { "operator-original-id-invalid" }
      return Request(Route.ORDINARY_START, argument, originalId = original)
    }
    val freshRoutes = mapOf("fresh_pair_continue" to Route.FRESH_PAIR_CONTINUE,
        "prepare_peer_start" to Route.PREPARE_PEER_START, "prepared_peer_start_status" to Route.PREPARED_PEER_START_STATUS,
        "reestablish_peer_roots" to Route.REESTABLISH_PEER_ROOTS, "peer_root_status" to Route.PEER_ROOT_STATUS,
        "operation_stop" to Route.OPERATION_STOP, "operation_join_status" to Route.OPERATION_JOIN_STATUS,
        "operation_continuation_status" to Route.OPERATION_CONTINUATION_STATUS,
        "operation_continuation_handoff" to Route.OPERATION_CONTINUATION_HANDOFF,
        "pair_setup_recovery_status" to Route.PAIR_SETUP_RECOVERY_STATUS)
    freshRoutes[method]?.let { route ->
      val read = route in setOf(Route.PREPARED_PEER_START_STATUS, Route.PEER_ROOT_STATUS, Route.PAIR_SETUP_RECOVERY_STATUS)
      val expected = when (route) {
        Route.FRESH_PAIR_CONTINUE -> setOf(KEY_ORIGINAL_ID, "prior_original_id", "prior_outcome_sha256")
        Route.OPERATION_STOP -> setOf(KEY_ORIGINAL_ID, "request_sha256", "stop_intent_sha256")
        else -> if (read) emptySet() else setOf(KEY_ORIGINAL_ID)
      }
      require(fields.keys == expected) { "operator-fields-invalid" }
      if (read) return Request(route, argument)
      fun token(name: String, pattern: Regex): String {
        val value = fields[name] as? String
        require(value != null && pattern.matches(value)) { "operator-fresh-field-invalid" }; return value
      }
      val original = token(KEY_ORIGINAL_ID, nonce)
      return when (route) {
        Route.FRESH_PAIR_CONTINUE -> Request(route, argument, originalId = original,
            priorOriginalId = token("prior_original_id", nonce), priorOutcomeSha256 = token("prior_outcome_sha256", sha))
        Route.OPERATION_STOP -> Request(route, argument, originalId = original,
            requestSha256 = token("request_sha256", sha), stopIntentSha256 = token("stop_intent_sha256", sha))
        else -> Request(route, argument, originalId = original)
      }
    }
    if (method == METHOD_OPERATION_QUERY || method == METHOD_OPERATION_ACK) {
      val expected = if (method == METHOD_OPERATION_QUERY) setOf(KEY_ORIGINAL_ID)
        else setOf(KEY_ORIGINAL_ID, "request_sha256", "outcome_sha256", "consumer_receipt_sha256")
      require(fields.keys == expected) { "operator-fields-invalid" }
      val original = fields[KEY_ORIGINAL_ID] as? String
      require(original != null && nonce.matches(original)) { "operator-original-id-invalid" }
      if (method == METHOD_OPERATION_QUERY) return Request(Route.OPERATION_QUERY, argument, originalId = original)
      fun digest(name: String): String {
        val value = fields[name] as? String
        require(value != null && sha.matches(value)) { "operator-original-digest-invalid" }
        return value
      }
      return Request(Route.OPERATION_ACK, argument, originalId = original,
          requestSha256 = digest("request_sha256"), outcomeSha256 = digest("outcome_sha256"),
          consumerReceiptSha256 = digest("consumer_receipt_sha256"))
    }
    if (fields.containsKey(KEY_ORIGINAL_ID)) {
      require(method in setOf("start", "renew_authority", "peer_stop", "peer_revoke", "whole_app_close",
          "concurrent_arm", "policy_update", "blend_update", "pair_session", METHOD_BOOTSTRAP_REAL_PEER)) { "operator-original-route-invalid" }
      val original = fields[KEY_ORIGINAL_ID] as? String
      require(original != null && nonce.matches(original)) { "operator-original-id-invalid" }
      return parseFields(method, argument, fields - KEY_ORIGINAL_ID).copy(originalId = original)
    }
    val route = when (method) {
      METHOD_STATUS -> Route.STATUS
      METHOD_REVIEW -> Route.REVIEW
      METHOD_CONFIRM -> Route.CONFIRM
      METHOD_RUNTIME_STATUS -> Route.RUNTIME_STATUS
      METHOD_OWNER_FAILURE_DIAGNOSTIC -> Route.OWNER_FAILURE_DIAGNOSTIC
      METHOD_OWN_CAPTURE_DIAGNOSTIC -> Route.OWN_CAPTURE_DIAGNOSTIC
      METHOD_STREAM_DROPOUT_DIAGNOSTIC -> Route.STREAM_DROPOUT_DIAGNOSTIC
      METHOD_BOOTSTRAP_REAL_PEER -> Route.BOOTSTRAP_REAL_PEER
      METHOD_BOOTSTRAP_ADMISSION_STATUS -> Route.BOOTSTRAP_ADMISSION_STATUS
      METHOD_CLOSE_NO_MEDIA -> Route.CLOSE_NO_MEDIA
      METHOD_PAIR_STATUS -> Route.PAIR_STATUS
      METHOD_PAIR_SESSION -> Route.PAIR_SESSION
      METHOD_START_PREFLIGHT -> Route.START_PREFLIGHT
      "concurrent_arm" -> Route.CONCURRENT_ARM
      "concurrent_status" -> Route.CONCURRENT_STATUS
      "policy_read" -> Route.POLICY_READ
      "policy_update" -> Route.POLICY_UPDATE
      "blend_read" -> Route.BLEND_READ
      "blend_update" -> Route.BLEND_UPDATE
      "start" -> Route.START
      "renew_authority" -> Route.RENEW_AUTHORITY
      "peer_stop" -> Route.PEER_STOP
      "peer_revoke" -> Route.PEER_REVOKE
      "peer_status" -> Route.PEER_STATUS
      "whole_app_close" -> Route.WHOLE_APP_CLOSE
      else -> throw IllegalArgumentException("operator-method-invalid")
    }
    val expected = when (route) {
      Route.NEW_RUN, Route.RESUME_RUN, Route.RUN_STATUS, Route.FRESH_PAIR_CONTINUE, Route.PREPARE_PEER_START, Route.PREPARED_PEER_START_STATUS,
      Route.REESTABLISH_PEER_ROOTS, Route.PEER_ROOT_STATUS, Route.OPERATION_STOP, Route.OPERATION_JOIN_STATUS,
      Route.OPERATION_CONTINUATION_STATUS, Route.OPERATION_CONTINUATION_HANDOFF, Route.PAIR_SETUP_RECOVERY_STATUS,
      Route.OPERATION_QUERY, Route.OPERATION_ACK, Route.ORDINARY_START -> throw IllegalArgumentException("operator-original-route-invalid")
      Route.STATUS -> setOf(KEY_ROLE)
      Route.REVIEW -> reviewKeys
      Route.CONFIRM -> setOf(KEY_REVIEW_SHA)
      Route.RUNTIME_STATUS, Route.BOOTSTRAP_REAL_PEER, Route.BOOTSTRAP_ADMISSION_STATUS, Route.CLOSE_NO_MEDIA,
      Route.PAIR_STATUS, Route.PAIR_SESSION, Route.START_PREFLIGHT, Route.CONCURRENT_ARM, Route.CONCURRENT_STATUS, Route.OWNER_FAILURE_DIAGNOSTIC, Route.OWN_CAPTURE_DIAGNOSTIC, Route.STREAM_DROPOUT_DIAGNOSTIC, Route.POLICY_READ, Route.BLEND_READ, Route.START, Route.RENEW_AUTHORITY, Route.PEER_STOP, Route.PEER_REVOKE, Route.PEER_STATUS, Route.WHOLE_APP_CLOSE -> emptySet()
      Route.BLEND_UPDATE -> setOf("version","enabled","threshold","softness","amount","invert","diagnostic")
      Route.POLICY_UPDATE -> setOf("center", "middle", "outer", "geometry", "brightness", "strength")
    }
    require(fields.keys == expected) { "operator-fields-invalid" }
    if (route == Route.BLEND_UPDATE) {
      fun flag(name: String) = fields[name] as? Boolean ?: throw IllegalArgumentException("mask flag type")
      fun value(name: String) = fields[name] as? Float ?: throw IllegalArgumentException("mask float type")
      val version = fields["version"] as? Int ?: throw IllegalArgumentException("mask version type")
      val policy = NeutralMaskPolicy(version,flag("enabled"),value("threshold"),value("softness"),value("amount"),flag("invert"),flag("diagnostic"))
      return Request(route,argument,mask=policy.words())
    }
    if (route == Route.POLICY_UPDATE) {
      val words = arrayOf("center", "middle", "outer", "geometry", "brightness", "strength").mapIndexed { index, name ->
        val value = fields[name] as? Int ?: throw IllegalArgumentException("policy type")
        require(value in 0..(if (index < 4) 1 else 2))
        value.toLong()
      }.toLongArray()
      return Request(route, argument, policy = words)
    }
    if (route in setOf(Route.START, Route.RENEW_AUTHORITY, Route.PEER_STOP, Route.PEER_REVOKE, Route.PEER_STATUS, Route.WHOLE_APP_CLOSE)) return Request(route, argument)
    if (route == Route.POLICY_READ || route == Route.BLEND_READ || route == Route.CONCURRENT_ARM || route == Route.CONCURRENT_STATUS ||
        route == Route.OWNER_FAILURE_DIAGNOSTIC || route == Route.OWN_CAPTURE_DIAGNOSTIC || route == Route.STREAM_DROPOUT_DIAGNOSTIC ||
        route == Route.RUNTIME_STATUS || route == Route.BOOTSTRAP_REAL_PEER || route == Route.BOOTSTRAP_ADMISSION_STATUS ||
        route == Route.CLOSE_NO_MEDIA || route == Route.PAIR_STATUS ||
        route == Route.PAIR_SESSION || route == Route.START_PREFLIGHT)
      return Request(route, argument)
    if (route == Route.CONFIRM) {
      val digest = fields[KEY_REVIEW_SHA] as? String
      require(digest != null && sha.matches(digest)) { "operator-review-digest-invalid" }
      return Request(route, argument, reviewSha256 = digest)
    }
    val role = fields[KEY_ROLE] as? String
    require(role == "peer_a" || role == "peer_b") { "operator-role-invalid" }
    if (route == Route.STATUS) return Request(route, argument, roleId = role)

    fun boundedId(name: String): String {
      val value = fields[name] as? String
      require(value != null && value.length <= 128 && dotted.matches(value)) {
        "operator-policy-id-invalid"
      }
      return value
    }
    val remoteKey = fields[KEY_REMOTE_KEY] as? String
    val ttl = fields[KEY_TTL] as? Long
    require(remoteKey != null && key.matches(remoteKey) && ttl != null && ttl in 1L..86_400_000L) {
      "operator-peer-or-ttl-invalid"
    }
    return Request(route, argument, roleId = role,
        draft = EmbeddedDuplexEnrollmentDraft(role, remoteKey,
            boundedId(KEY_RUNTIME_HOST), boundedId(KEY_TRUSTED_OPERATOR), boundedId(KEY_ADAPTER),
            boundedId(KEY_MEDIA_REVOKER), boundedId(KEY_ADMISSION_AUTHORITY), ttl, false))
  }
}

/** Caller serializes access; holds the actual service result, never a reconstructed draft. */
internal class EmbeddedDuplexOperatorReviewSlot<T : Any> {
  private data class Pending<T>(val challenge: String, val digest: String, val value: T)
  private var pending: Pending<T>? = null

  fun clear() { pending = null }
  fun install(challenge: String, digest: String, value: T) {
    require(challenge.matches(Regex("[0-9a-f]{32}")) &&
        digest.matches(Regex("[0-9a-f]{64}"))) { "operator-review-invalid" }
    pending = Pending(challenge, digest, value)
  }
  fun consume(challenge: String, digest: String): T {
    val current = pending
    require(current != null && current.challenge == challenge && current.digest == digest) {
      "operator-review-unavailable"
    }
    pending = null
    return current.value
  }
}
