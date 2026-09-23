package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.os.Bundle
import android.os.Process

/** Closed debug transport for a single challenge-bound local diagnostic. */
internal object EmbeddedDuplexDiagnosticContract {
  const val SCHEMA = "rusty.quest.embedded_duplex.local_diagnostic_transport.v1"
  const val AUTHORITY_SUFFIX = ".embedded-duplex-diagnostic"
  const val METHOD_ARM = "arm"
  const val METHOD_READ = "read"
  const val KEY_STATUS = "status"
  const val KEY_RECEIPT_BASE64 = "receipt_base64"

  private val noncePattern = Regex("^[0-9a-f]{32}$")

  enum class Route { ARM, READ }

  data class Request(val route: Route, val nonce: String)

  fun callerIsShell(callingUid: Int): Boolean = callingUid == Process.SHELL_UID

  fun parseCall(method: String, argument: String?, extras: Bundle?): Request {
    require(extras == null) { "embedded_duplex_diagnostic_bundle_rejected" }
    require(argument != null && noncePattern.matches(argument)) {
      "embedded_duplex_diagnostic_nonce_rejected"
    }
    val route =
        when (method) {
          METHOD_ARM -> Route.ARM
          METHOD_READ -> Route.READ
          else -> throw IllegalArgumentException("embedded_duplex_diagnostic_method_rejected")
        }
    return Request(route, argument)
  }
}
