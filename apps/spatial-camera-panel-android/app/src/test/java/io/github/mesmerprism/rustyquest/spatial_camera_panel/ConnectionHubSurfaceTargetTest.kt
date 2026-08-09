
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHubSurfaceTargetTest {
  @Test
  fun effectConfirmationRequiresTheExpectedRevisionAndEffectivePauseState() {
    val paused = state(revision = 8, paused = true)
    assertTrue(
        connectionHubCommandEffectObserved(
            ConnectionHubLockedPlaylistContract.COMMAND_PAUSE,
            8,
            paused,
        )
    )
    assertFalse(
        connectionHubCommandEffectObserved(
            ConnectionHubLockedPlaylistContract.COMMAND_RESUME,
            8,
            paused,
        )
    )
    assertFalse(
        connectionHubCommandEffectObserved(
            ConnectionHubLockedPlaylistContract.COMMAND_PAUSE,
            9,
            paused,
        )
    )
    assertFalse(
        connectionHubCommandEffectObserved(
            ConnectionHubLockedPlaylistContract.COMMAND_PAUSE,
            8,
            paused.put("running", false),
        )
    )
  }

  @Test
  fun authorizationRejectsNonEmptyArgsAndProviderSubstitution() {
    val command = ConnectionHubLockedPlaylistContract.COMMAND_NEXT
    assertThrows(IllegalArgumentException::class.java) {
      requireConnectionHubCommandAuthorization(
          "hub-request",
          ConnectionHubLockedPlaylistContract.SURFACE_ID,
          command,
          JSONObject().put("index", 1),
          authorizationReceipt(command),
      )
    }
    val substituted =
        authorizationReceipt(command).apply {
          getJSONObject("command_authorization").put("provider_id", "provider.quest.wrong")
        }
    assertThrows(IllegalArgumentException::class.java) {
      requireConnectionHubCommandAuthorization(
          "hub-request",
          ConnectionHubLockedPlaylistContract.SURFACE_ID,
          command,
          JSONObject(),
          substituted,
      )
    }
  }

  private fun state(revision: Long, paused: Boolean) =
      JSONObject().put("revision", revision).put("running", true).put("paused", paused)

  private fun authorizationReceipt(command: String): JSONObject {
    val requestId = "epoch-7.request.hub-request"
    val instanceId = "provider.instance-1"
    val details =
        JSONObject()
            .put("command_id", command)
            .put("lease_id", "lease-1")
            .put("session_id", "session-1")
            .put("expected_transport_epoch", 4L)
            .put(
                "typed_params_sha256",
                "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            )
            .put(
                "typed_params_schema_id",
                "rusty.manifold.connection_hub.typed_params.empty.v1",
            )
            .put(
                "typed_params_schema_sha256",
                "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51",
            )
            .put(
                "external_request_sha256",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            )
    val authorization =
        JSONObject()
            .put("\$schema", "rusty.manifold.connection_hub.command_authorization.v2")
            .put("proves_application_effect", false)
            .put("request_id", requestId)
            .put("provider_instance_id", instanceId)
            .put(
                "surface_id",
                "$instanceId.surface-instance.${ConnectionHubLockedPlaylistContract.SURFACE_ID}",
            )
            .put("provider_id", ConnectionHubLockedPlaylistContract.PROVIDER_ID)
            .put("command_id", command)
            .put("typed_params_sha256", details.getString("typed_params_sha256"))
            .put("typed_params_schema_id", details.getString("typed_params_schema_id"))
            .put("typed_params_schema_sha256", details.getString("typed_params_schema_sha256"))
            .put("lease_id", "lease-1")
            .put("session_id", "session-1")
            .put("transport_epoch", 4L)
    return JSONObject()
        .put("\$schema", "rusty.manifold.connection_hub.receipt.v3")
        .put("applied", true)
        .put("operation", "authorize_surface_command")
        .put("request_id", requestId)
        .put("command_authorization", authorization)
        .put(
            "audit_event",
            JSONObject()
                .put("authority_epoch", 7L)
                .put(
                    "request",
                    JSONObject()
                        .put("request_id", requestId)
                        .put(
                            "operation",
                            JSONObject()
                                .put("type", "authorize_surface_command")
                                .put("details", details),
                        ),
                ),
        )
  }
}

