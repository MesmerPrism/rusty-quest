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
          "rusty.manifold.connection_hub.receipt.v2"
  )
  require(authorityReceipt.optBoolean("applied", false))
  require(authorityReceipt.optString("operation") == "authorize_surface_command")
  val authorization = authorityReceipt.getJSONObject("command_authorization")
  require(!authorization.optBoolean("proves_application_effect", true))
  require(authorization.optString("request_id") == requestId)
  require(authorization.optString("surface_id") == surfaceId)
  require(authorization.optString("command_id") == command)
  require(
      authorization.optString("typed_params_sha256") ==
          EMPTY_OBJECT_SHA256
  )
  require(args.length() == 0)
}

private const val EMPTY_OBJECT_SHA256 =
    "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
