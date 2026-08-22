package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Bundle
import android.os.Process
import java.security.MessageDigest
import java.security.SecureRandom

/** Closed debug-only shell transport; app code, not this provider, owns receipt facts. */
internal object DebugHostReceiptContract {
  const val SCHEMA = "rusty.quest.debug_host_receipt.v1"
  const val AUTHORITY =
      "io.github.mesmerprism.rustyquest.spatial_camera_panel.debug-host-receipt"
  const val METHOD_ARM = "arm"
  const val METHOD_STATUS = "status"
  const val METHOD_READ = "read"
  const val METHOD_CLEANUP = "cleanup"
  const val KEY_NONCE = "nonce"
  const val KEY_STATUS = "status"
  const val KEY_RECEIPT_HASH = "receipt_hash"
  const val KEY_RECEIPT_JSON = "receipt_json"
  const val KEY_EXPIRES_AT_MS = "expires_at_ms"
  const val MAX_RECEIPT_BYTES = 64 * 1024
  const val NONCE_TTL_MS = 2 * 60 * 1000L
  const val MAX_FACT_VALUE_LENGTH = 96

  val FACT_TYPES =
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
      )

  private val noncePattern = Regex("^[0-9a-f]{64}$")
  private val hashPattern = Regex("^[0-9a-f]{64}$")
  private val epochPattern = Regex("^[0-9a-f]{32}$")
  private val tokenPattern = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")

  enum class Route { ARM, STATUS, READ, CLEANUP }

  data class CallRequest(
      val route: Route,
      val nonce: String? = null,
      val receiptHash: String? = null,
  )

  fun callerIsShell(callingUid: Int): Boolean = callingUid == Process.SHELL_UID

  fun parseCall(method: String, argument: String?, extras: Bundle?): CallRequest =
      when (method) {
        METHOD_ARM -> {
          require(argument == null) { "debug_host_receipt_argument_rejected" }
          require(extras != null && extras.keySet() == setOf(KEY_NONCE)) {
            "debug_host_receipt_bundle_rejected"
          }
          CallRequest(route = Route.ARM, nonce = parseArmNonce(extras.getString(KEY_NONCE)))
        }
        METHOD_STATUS -> {
          require(argument == null && extras == null) { "debug_host_receipt_status_shape_rejected" }
          CallRequest(route = Route.STATUS)
        }
        METHOD_READ -> {
          require(extras == null) { "debug_host_receipt_bundle_rejected" }
          CallRequest(route = Route.READ, receiptHash = requireReceiptHash(argument))
        }
        METHOD_CLEANUP -> {
          require(extras == null) { "debug_host_receipt_bundle_rejected" }
          CallRequest(route = Route.CLEANUP, receiptHash = requireReceiptHash(argument))
        }
        else -> throw IllegalArgumentException("debug_host_receipt_method_rejected")
      }

  fun requireNonce(value: String?): String {
    require(value != null && noncePattern.matches(value)) { "debug_host_receipt_nonce_rejected" }
    return value
  }

  fun parseArmNonce(value: String?): String = requireNonce(value)

  fun requireReceiptHash(value: String?): String {
    require(value != null && hashPattern.matches(value)) { "debug_host_receipt_hash_rejected" }
    return value
  }

  fun requireEpoch(value: String): String {
    require(epochPattern.matches(value)) { "debug_host_receipt_epoch_rejected" }
    return value
  }

  fun requireToken(value: String, label: String): String {
    require(tokenPattern.matches(value) && value.length <= MAX_FACT_VALUE_LENGTH) {
      "debug_host_receipt_${label}_rejected"
    }
    return value
  }

  fun sha256(value: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

  fun freshEpoch(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }
}
