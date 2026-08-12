
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionHubSurfaceClientTest {
  @Test
  fun registrationUsesTheCanonicalAlpha4ContractAndFourEmptyArgumentCommands() {
    val state =
        JSONObject()
            .put("active_index", 1)
            .put("active_label", "Second")
            .put("item_count", 3)
            .put("item_duration_seconds", 90)
            .put("item_elapsed_seconds", 23)
            .put("paused", false)
            .put("phase", "dwell")
            .put("playlist_title", "Sequence")
            .put("progress", 0.25)
            .put("revision", 7)
            .put("running", true)

    val registration = connectionHubSurfaceRegistration(state)

    assertEquals(
        ConnectionHubLockedPlaylistContract.SURFACE_CONTRACT_SHA256,
        registration.getString("surface_contract_sha256"),
    )
    assertEquals(ConnectionHubLockedPlaylistContract.SURFACE_ID, registration.getString("surface_id"))
    assertEquals(ConnectionHubLockedPlaylistContract.stateKeys, registration.getJSONObject("state").keySet())
    val commands = registration.getJSONArray("commands")
    assertEquals(4, commands.length())
    val commandIds =
        (0 until commands.length()).map { commands.getJSONObject(it).getString("command") }.toSet()
    assertEquals(ConnectionHubLockedPlaylistContract.commands, commandIds)
    (0 until commands.length()).forEach { index ->
      val descriptor = commands.getJSONObject(index)
      assertEquals(
          setOf("command", "display_label", "required_controller_capability"),
          descriptor.keySet(),
      )
      assertFalse(descriptor.has("args"))
      assertFalse(descriptor.has("parameters"))
    }
  }

  @Test
  fun declaredRuntimeSurfaceDigestMatchesIndependentlyRecomputedCanonicalBytes() {
    val registration = connectionHubSurfaceRegistration(JSONObject().put("revision", 1))
    val commands = registration.getJSONArray("commands")
    val canonical =
        buildString {
          append("v1\n")
          append(registration.getString("surface_id")).append('\n')
          append(registration.getString("display_label")).append('\n')
          append(registration.getString("description")).append('\n')
          (0 until commands.length()).forEach { index ->
            val command = commands.getJSONObject(index)
            append(command.getString("command")).append('|')
            append(command.getString("display_label")).append('|')
            append(command.getString("required_controller_capability")).append('\n')
          }
        }
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    val recomputed = "sha256:$digest"

    assertEquals(recomputed, registration.getString("surface_contract_sha256"))
    assertEquals(recomputed, ConnectionHubLockedPlaylistContract.SURFACE_CONTRACT_SHA256)
  }
}
