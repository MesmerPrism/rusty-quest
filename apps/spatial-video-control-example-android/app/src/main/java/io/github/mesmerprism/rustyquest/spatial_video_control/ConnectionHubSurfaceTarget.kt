package io.github.mesmerprism.rustyquest.spatial_video_control

import org.json.JSONObject

/**
 * Quest-owned effect boundary behind the process-independent Connection Hub
 * surface transport. Implementations report only state they have applied and
 * observed; Manifold remains the command-authorization authority.
 */
interface ConnectionHubSurfaceTarget {
  fun hubSurfaceState(): JSONObject

  fun enqueueHubAuthorizedCommand(
      requestId: String,
      surfaceId: String,
      command: String,
      args: JSONObject,
      authorityReceipt: JSONObject,
  ): String
}

/** Shared fail-closed receipt check used before either real or debug effects. */
internal fun requireConnectionHubCommandAuthorization(
    requestId: String,
    surfaceId: String,
    command: String,
    args: JSONObject,
    authorityReceipt: JSONObject,
) {
  require(surfaceId == Media3SpatialPlayerAdapter.CONNECTION_HUB_SURFACE_ID)
  require(
      authorityReceipt.optString("\$schema") ==
          "rusty.manifold.connection_hub.receipt.v3"
  )
  require(authorityReceipt.optBoolean("applied", false))
  require(authorityReceipt.optString("operation") == "authorize_surface_command")
  val authorization = authorityReceipt.getJSONObject("command_authorization")
  require(
      authorization.optString("\$schema") ==
          "rusty.manifold.connection_hub.command_authorization.v2"
  )
  require(!authorization.optBoolean("proves_application_effect", true))
  val auditEvent = authorityReceipt.getJSONObject("audit_event")
  val authorityEpoch = auditEvent.getLong("authority_epoch")
  val authorityRequest = auditEvent.getJSONObject("request")
  val expectedAuthorityRequestId = deriveAuthorityRequestId(requestId, authorityEpoch)
  require(authorityReceipt.optString("request_id") == expectedAuthorityRequestId)
  require(authorityRequest.optString("request_id") == expectedAuthorityRequestId)
  require(authorization.optString("request_id") == expectedAuthorityRequestId)
  val providerInstanceId = authorization.getString("provider_instance_id")
  require(isDottedIdentifier(providerInstanceId))
  require(
      authorization.optString("surface_id") ==
          "$providerInstanceId.surface-instance.$surfaceId"
  )
  require(
      authorization.optString("provider_id") ==
          "provider.quest.spatial-video-control-example"
  )
  require(authorization.optString("command_id") == command)
  require(
      authorization.optString("typed_params_sha256") ==
          EMPTY_OBJECT_SHA256
  )
  require(
      authorization.optString("typed_params_schema_id") ==
          EMPTY_TYPED_PARAMS_SCHEMA_ID
  )
  require(
      authorization.optString("typed_params_schema_sha256") ==
          EMPTY_TYPED_PARAMS_SCHEMA_SHA256
  )
  val operation = authorityRequest.getJSONObject("operation")
  require(operation.optString("type") == "authorize_surface_command")
  val details = operation.getJSONObject("details")
  require(details.optString("command_id") == command)
  require(details.optString("lease_id") == authorization.optString("lease_id"))
  require(details.optString("session_id") == authorization.optString("session_id"))
  require(
      details.optLong("expected_transport_epoch", -1L) ==
          authorization.optLong("transport_epoch", -2L)
  )
  require(details.optString("typed_params_sha256") == EMPTY_OBJECT_SHA256)
  require(details.optString("typed_params_schema_id") == EMPTY_TYPED_PARAMS_SCHEMA_ID)
  require(
      details.optString("typed_params_schema_sha256") ==
          EMPTY_TYPED_PARAMS_SCHEMA_SHA256
  )
  require(isSha256(details.optString("external_request_sha256")))
  require(args.length() == 0)
}

private fun deriveAuthorityRequestId(externalRequestId: String, authorityEpoch: Long): String {
  require(authorityEpoch > 0L)
  val epochPrefix = "epoch-$authorityEpoch."
  if (externalRequestId.startsWith(epochPrefix)) {
    require(isDottedIdentifier(externalRequestId))
    return externalRequestId
  }
  val suffix =
      externalRequestId
          .lowercase(java.util.Locale.ROOT)
          .filter { it.isLetterOrDigit() && it.code < 128 || it == '-' }
          .take(40)
          .trim('-')
  require(suffix.isNotEmpty())
  return "epoch-$authorityEpoch.request.$suffix".also { require(isDottedIdentifier(it)) }
}

private fun isDottedIdentifier(value: String): Boolean =
    value.isNotEmpty() &&
        value.split('.').all { segment ->
          segment.isNotEmpty() &&
              segment.first().isAsciiLowerOrDigit() &&
              segment.last().isAsciiLowerOrDigit() &&
              segment.all { it.isAsciiLowerOrDigit() || it == '_' || it == '-' }
        }

private fun Char.isAsciiLowerOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

private fun isSha256(value: String): Boolean =
    value.length == 71 &&
        value.startsWith("sha256:") &&
        value.substring(7).all { it in '0'..'9' || it in 'a'..'f' }

private const val EMPTY_OBJECT_SHA256 =
    "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
private const val EMPTY_TYPED_PARAMS_SCHEMA_ID =
    "rusty.manifold.connection_hub.typed_params.empty.v1"
private const val EMPTY_TYPED_PARAMS_SCHEMA_SHA256 =
    "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51"
