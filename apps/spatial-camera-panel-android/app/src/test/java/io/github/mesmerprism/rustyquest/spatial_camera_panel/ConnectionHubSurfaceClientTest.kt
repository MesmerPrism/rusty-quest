
package io.github.mesmerprism.rustyquest.spatial_camera_panel

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
}

