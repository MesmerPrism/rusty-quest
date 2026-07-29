package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Quest owns this effective state. Revisions advance only inside a Media3
 * callback that observes the accepted effect.
 */
class Media3SpatialPlayerAdapter(
    context: Context,
) : PlayerPort {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val player = ExoPlayer.Builder(context).build()
  private val resourcesByVideoId =
      mapOf(
          "synthetic-grid-1s" to R.raw.synthetic_grid_1s,
          "synthetic-blue-2s" to R.raw.synthetic_blue_2s,
      )
  private val packageName = context.packageName
  private val lock = Any()
  private var listener: PlayerPort.Listener? = null
  private var pending: PlayerPort.AcceptedEffect? = null
  private var state =
      PlayerPort.Snapshot(
          0,
          "synthetic-grid-1s",
          false,
          "idle",
          0,
      )

  init {
    player.addListener(
        object : Player.Listener {
          override fun onEvents(player: Player, events: Player.Events) {
            observeMedia3Callback()
          }

          override fun onPlayerError(error: PlaybackException) {
            val effect = synchronized(lock) { pending.also { pending = null } } ?: return
            listener?.onFailed(effect, "media3_${error.errorCode}")
          }
        }
    )
    player.repeatMode = Player.REPEAT_MODE_ONE
    player.setMediaItem(mediaItem("synthetic-grid-1s"))
    player.prepare()
  }

  override fun snapshot(): PlayerPort.Snapshot = synchronized(lock) { state }

  override fun setListener(listener: PlayerPort.Listener) {
    synchronized(lock) { this.listener = listener }
  }

  override fun selectVideo(effect: PlayerPort.AcceptedEffect) {
    require(effect.command() == "select_video")
    dispatch(effect) {
      player.pause()
      player.setMediaItem(mediaItem(requireNotNull(effect.videoId())))
      player.prepare()
    }
  }

  override fun play(effect: PlayerPort.AcceptedEffect) {
    require(effect.command() == "play")
    dispatch(effect) { player.play() }
  }

  override fun pause(effect: PlayerPort.AcceptedEffect) {
    require(effect.command() == "pause")
    dispatch(effect) { player.pause() }
  }

  fun attachVideoSurface(surface: Surface) {
    mainHandler.post { player.setVideoSurface(surface) }
  }

  fun release() {
    mainHandler.post {
      player.clearVideoSurface()
      player.release()
    }
  }

  private fun dispatch(effect: PlayerPort.AcceptedEffect, action: () -> Unit) {
    synchronized(lock) {
      check(pending == null) { "Manifold must not accept overlapping player effects" }
      check(effect.expectedPlayerRevision() == state.revision()) {
        "accepted effect has stale player revision"
      }
      pending = effect
    }
    mainHandler.post(action)
  }

  private fun observeMedia3Callback() {
    val applied =
        synchronized(lock) {
          val effect = pending ?: return
          val selected = player.currentMediaItem?.mediaId ?: state.selectedVideoId()
          val observed =
              when (effect.command()) {
                "select_video" ->
                    selected == effect.videoId() && !player.playWhenReady
                "play" -> player.isPlaying
                "pause" -> !player.isPlaying && !player.playWhenReady
                else -> false
              }
          if (!observed) {
            return
          }
          state =
              PlayerPort.Snapshot(
                  state.revision() + 1,
                  selected,
                  player.isPlaying,
                  playbackStateToken(player.playbackState),
                  player.currentPosition.coerceAtLeast(0),
              )
          pending = null
          PlayerPort.AppliedEffect(effect, state)
        }
    listener?.onApplied(applied)
  }

  private fun mediaItem(videoId: String): MediaItem {
    val resourceId =
        resourcesByVideoId[videoId] ?: throw IllegalArgumentException("video id is not bundled")
    return MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(Uri.parse("android.resource://$packageName/$resourceId"))
        .build()
  }

  private fun playbackStateToken(value: Int): String =
      when (value) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown"
      }
}
