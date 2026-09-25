package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Bundle
import android.os.Process
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentDraft

/** Debug shell transport only; the process host owns every reviewed mutation. */
internal object EmbeddedDuplexOperatorContract {
  const val SCHEMA = "rusty.quest.embedded_duplex.operator_transport.v1"
  const val AUTHORITY_SUFFIX = ".embedded-duplex-operator"
  const val METHOD_STATUS = "status"
  const val METHOD_REVIEW = "review"
  const val METHOD_CONFIRM = "confirm"
  const val METHOD_RUNTIME_STATUS = "runtime_status"
  const val METHOD_BOOTSTRAP_REAL_PEER = "bootstrap_real_peer"
  const val METHOD_CLOSE_NO_MEDIA = "close_no_media"
  const val METHOD_PAIR_STATUS = "pair_status"
  const val METHOD_PAIR_SESSION = "pair_session"
  const val METHOD_START_PREFLIGHT = "start_preflight"
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

  enum class Route { STATUS, REVIEW, CONFIRM, RUNTIME_STATUS, BOOTSTRAP_REAL_PEER,
    CLOSE_NO_MEDIA, PAIR_STATUS, PAIR_SESSION, START_PREFLIGHT }
  data class Request(
      val route: Route,
      val challenge: String,
      val roleId: String? = null,
      val draft: EmbeddedDuplexEnrollmentDraft? = null,
      val reviewSha256: String? = null,
  )

  fun callerIsShell(uid: Int): Boolean = uid == Process.SHELL_UID

  fun parseCall(method: String, argument: String?, extras: Bundle?): Request {
    val fields = extras?.keySet()?.associateWith { extras.get(it) } ?: emptyMap()
    return parseFields(method, argument, fields)
  }

  internal fun parseFields(method: String, argument: String?, fields: Map<String, Any?>): Request {
    require(argument != null && nonce.matches(argument)) { "operator-challenge-invalid" }
    val route = when (method) {
      METHOD_STATUS -> Route.STATUS
      METHOD_REVIEW -> Route.REVIEW
      METHOD_CONFIRM -> Route.CONFIRM
      METHOD_RUNTIME_STATUS -> Route.RUNTIME_STATUS
      METHOD_BOOTSTRAP_REAL_PEER -> Route.BOOTSTRAP_REAL_PEER
      METHOD_CLOSE_NO_MEDIA -> Route.CLOSE_NO_MEDIA
      METHOD_PAIR_STATUS -> Route.PAIR_STATUS
      METHOD_PAIR_SESSION -> Route.PAIR_SESSION
      METHOD_START_PREFLIGHT -> Route.START_PREFLIGHT
      else -> throw IllegalArgumentException("operator-method-invalid")
    }
    val expected = when (route) {
      Route.STATUS -> setOf(KEY_ROLE)
      Route.REVIEW -> reviewKeys
      Route.CONFIRM -> setOf(KEY_REVIEW_SHA)
      Route.RUNTIME_STATUS, Route.BOOTSTRAP_REAL_PEER, Route.CLOSE_NO_MEDIA,
      Route.PAIR_STATUS, Route.PAIR_SESSION, Route.START_PREFLIGHT -> emptySet()
    }
    require(fields.keys == expected) { "operator-fields-invalid" }
    if (route == Route.RUNTIME_STATUS || route == Route.BOOTSTRAP_REAL_PEER ||
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
