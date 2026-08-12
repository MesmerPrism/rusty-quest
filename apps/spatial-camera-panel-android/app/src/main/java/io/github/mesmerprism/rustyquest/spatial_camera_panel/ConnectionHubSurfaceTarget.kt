
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONObject

/**
 * Neutral app-owned boundary behind the device-wide Connection Hub provider transport.
 * Implementations expose only effective low-rate state and return the revision they applied.
 */
internal interface ConnectionHubSurfaceTarget {
  fun hubSurfaceAvailable(): Boolean

  fun hubSurfaceState(): JSONObject

  fun applyHubAuthorizedCommand(
      requestId: String,
      surfaceId: String,
      command: String,
      args: JSONObject,
      authorityReceipt: JSONObject,
  ): Long
}

internal object ConnectionHubLockedPlaylistContract {
  const val PROVIDER_ID = "provider.quest.spatial-camera-panel-locked-playlist"
  const val SURFACE_ID = "surface.spatial_camera_panel.locked_playlist"
  const val DISPLAY_LABEL = "Spatial Camera Locked Playlist"
  const val DESCRIPTION =
      "Control the active locked media sequence offered by Spatial Camera Panel."
  const val SURFACE_CONTRACT_SHA256 =
      "sha256:3eafe0fb1ff859a7848dfba8cf64a6eb532f98a39d0953fd628594792ca18d6e"

  const val COMMAND_NEXT = "command.spatial_camera_panel.locked_playlist.next"
  const val COMMAND_PAUSE = "command.spatial_camera_panel.locked_playlist.pause"
  const val COMMAND_PREVIOUS = "command.spatial_camera_panel.locked_playlist.previous"
  const val COMMAND_RESUME = "command.spatial_camera_panel.locked_playlist.resume"

  const val CAPABILITY_NEXT = "capability.spatial_camera_panel.locked_playlist.next"
  const val CAPABILITY_PAUSE = "capability.spatial_camera_panel.locked_playlist.pause"
  const val CAPABILITY_PREVIOUS = "capability.spatial_camera_panel.locked_playlist.previous"
  const val CAPABILITY_RESUME = "capability.spatial_camera_panel.locked_playlist.resume"

  val commands = setOf(COMMAND_NEXT, COMMAND_PAUSE, COMMAND_PREVIOUS, COMMAND_RESUME)
  val stateKeys =
      setOf(
          "active_index",
          "active_label",
          "item_count",
          "item_duration_seconds",
          "item_elapsed_seconds",
          "paused",
          "phase",
          "playlist_title",
          "progress",
          "revision",
          "running",
      )
}

/** Shared fail-closed authorization check used before an app-owned effect is attempted. */
internal fun requireConnectionHubCommandAuthorization(
    requestId: String,
    surfaceId: String,
    command: String,
    args: JSONObject,
    authorityReceipt: JSONObject,
) {
  require(surfaceId == ConnectionHubLockedPlaylistContract.SURFACE_ID)
  require(command in ConnectionHubLockedPlaylistContract.commands)
  require(authorityReceipt.optString("\$schema") == "rusty.manifold.connection_hub.receipt.v3")
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
  val expectedAuthorityRequestId = deriveConnectionHubAuthorityRequestId(requestId, authorityEpoch)
  require(authorityReceipt.optString("request_id") == expectedAuthorityRequestId)
  require(authorityRequest.optString("request_id") == expectedAuthorityRequestId)
  require(authorization.optString("request_id") == expectedAuthorityRequestId)
  val providerInstanceId = authorization.getString("provider_instance_id")
  require(isConnectionHubDottedIdentifier(providerInstanceId))
  require(
      authorization.optString("surface_id") ==
          "$providerInstanceId.surface-instance.${ConnectionHubLockedPlaylistContract.SURFACE_ID}"
  )
  require(authorization.optString("provider_id") == ConnectionHubLockedPlaylistContract.PROVIDER_ID)
  require(authorization.optString("command_id") == command)
  require(authorization.optString("typed_params_sha256") == EMPTY_OBJECT_SHA256)
  require(authorization.optString("typed_params_schema_id") == EMPTY_TYPED_PARAMS_SCHEMA_ID)
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
  require(details.optString("typed_params_schema_sha256") == EMPTY_TYPED_PARAMS_SCHEMA_SHA256)
  require(isConnectionHubSha256(details.optString("external_request_sha256")))
  require(args.length() == 0)
}

internal fun connectionHubCommandEffectObserved(
    command: String,
    expectedRevision: Long,
    state: JSONObject,
): Boolean {
  if (state.optLong("revision", -1L) < expectedRevision || !state.optBoolean("running", false)) {
    return false
  }
  return when (command) {
    ConnectionHubLockedPlaylistContract.COMMAND_PAUSE -> state.optBoolean("paused", false)
    ConnectionHubLockedPlaylistContract.COMMAND_RESUME -> !state.optBoolean("paused", true)
    ConnectionHubLockedPlaylistContract.COMMAND_NEXT,
    ConnectionHubLockedPlaylistContract.COMMAND_PREVIOUS -> true
    else -> false
  }
}

private fun deriveConnectionHubAuthorityRequestId(
    externalRequestId: String,
    authorityEpoch: Long,
): String {
  require(authorityEpoch > 0L)
  val epochPrefix = "epoch-$authorityEpoch."
  if (externalRequestId.startsWith(epochPrefix)) {
    require(isConnectionHubDottedIdentifier(externalRequestId))
    return externalRequestId
  }
  val suffix =
      externalRequestId
          .lowercase(java.util.Locale.ROOT)
          .filter { (it.isLetterOrDigit() && it.code < 128) || it == '-' }
          .take(40)
          .trim('-')
  require(suffix.isNotEmpty())
  return "epoch-$authorityEpoch.request.$suffix".also {
    require(isConnectionHubDottedIdentifier(it))
  }
}

private fun isConnectionHubDottedIdentifier(value: String): Boolean =
    value.isNotEmpty() &&
        value.split('.').all { segment ->
          segment.isNotEmpty() &&
              segment.first().isConnectionHubAsciiLowerOrDigit() &&
              segment.last().isConnectionHubAsciiLowerOrDigit() &&
              segment.all { it.isConnectionHubAsciiLowerOrDigit() || it == '_' || it == '-' }
        }

private fun Char.isConnectionHubAsciiLowerOrDigit(): Boolean =
    this in 'a'..'z' || this in '0'..'9'

private fun isConnectionHubSha256(value: String): Boolean =
    value.length == 71 &&
        value.startsWith("sha256:") &&
        value.substring(7).all { it in '0'..'9' || it in 'a'..'f' }

private const val EMPTY_OBJECT_SHA256 =
    "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
private const val EMPTY_TYPED_PARAMS_SCHEMA_ID =
    "rusty.manifold.connection_hub.typed_params.empty.v1"
private const val EMPTY_TYPED_PARAMS_SCHEMA_SHA256 =
    "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51"
