package io.github.mesmerprism.rustyquest.spatial_video_control

import android.util.Log
import org.json.JSONObject

/**
 * Deterministic debug-only effect target for off-head Hub qualification. It
 * never constructs Media3, a Spatial surface, or the XR Activity. Every state
 * transition follows an exact Manifold authorization validated by the shared
 * target boundary.
 */
internal class DebugConnectionHubSurfaceTarget : ConnectionHubSurfaceTarget {
  private val lock = Any()
  private var selectedIndex = 0
  private var playing = false
  private var playerRevision = 0L

  override fun hubSurfaceState(): JSONObject =
      synchronized(lock) {
        JSONObject()
            .put("selected_video_id", VIDEO_IDS[selectedIndex])
            .put("playing", playing)
            .put("playback_state", "ready")
            .put("position_ms", 0L)
            .put("player_revision", playerRevision)
      }

  override fun enqueueHubAuthorizedCommand(
      requestId: String,
      surfaceId: String,
      command: String,
      args: JSONObject,
      authorityReceipt: JSONObject,
  ): String {
    requireConnectionHubCommandAuthorization(
        requestId,
        surfaceId,
        command,
        args,
        authorityReceipt,
    )
    val state =
        synchronized(lock) {
          when (command) {
            COMMAND_SELECT_NEXT -> selectedIndex = (selectedIndex + 1) % VIDEO_IDS.size
            COMMAND_SELECT_PREVIOUS ->
                selectedIndex = (selectedIndex + VIDEO_IDS.size - 1) % VIDEO_IDS.size
            COMMAND_PLAY -> playing = true
            COMMAND_PAUSE -> playing = false
            else -> error("unregistered Hub media command")
          }
          playerRevision += 1
          JSONObject()
              .put("selected_video_id", VIDEO_IDS[selectedIndex])
              .put("playing", playing)
              .put("player_revision", playerRevision)
        }
    Log.i(
        TAG,
        "channel=rusty-connection-hub-debug-surface status=effect_applied " +
            "command=$command selectedVideoId=${state.getString("selected_video_id")} " +
            "playing=${state.getBoolean("playing")} playerRevision=${state.getLong("player_revision")}",
    )
    return "provider_dispatch_applied_effect_observed"
  }

  private companion object {
    const val TAG = "RqHubDebugSurface"
    const val COMMAND_PAUSE = "command.spatial_video_control.pause"
    const val COMMAND_PLAY = "command.spatial_video_control.play"
    const val COMMAND_SELECT_NEXT = "command.spatial_video_control.select_next"
    const val COMMAND_SELECT_PREVIOUS = "command.spatial_video_control.select_previous"
    val VIDEO_IDS =
        listOf(
            "synthetic-grid-1s",
            "synthetic-blue-2s",
            "synthetic-180-mono",
        )
  }
}
