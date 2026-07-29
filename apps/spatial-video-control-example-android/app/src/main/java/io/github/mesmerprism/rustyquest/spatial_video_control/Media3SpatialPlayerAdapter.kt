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
  private var released = false
  private var state =
      PlayerPort.Snapshot(
          0,
          "synthetic-grid-1s",
          false,
          "idle",
          0,
      )
  private val positionObservation =
      object : Runnable {
        override fun run() {
          if (released) {
            return
          }
          observeMedia3State(allowEffectReceipt = false)
          if (player.isPlaying) {
            mainHandler.postDelayed(this, POSITION_OBSERVATION_INTERVAL_MS)
          }
        }
      }

  init {
    player.addListener(
        object : Player.Listener {
          override fun onEvents(player: Player, events: Player.Events) {
            observeMedia3State(allowEffectReceipt = true)
            schedulePositionObservation()
          }

          override fun onPlayerError(error: PlaybackException) {
            failPendingAndObserve("media3_${error.errorCode}")
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

  override fun cancelPending(effect: PlayerPort.AcceptedEffect) {
    synchronized(lock) {
      if (pending?.requestId() == effect.requestId() &&
          pending?.acceptedCommandReceiptId() == effect.acceptedCommandReceiptId()) {
        pending = null
      }
    }
  }

  fun attachVideoSurface(surface: Surface) {
    mainHandler.post { player.setVideoSurface(surface) }
  }

  fun release() {
    mainHandler.post {
      released = true
      mainHandler.removeCallbacks(positionObservation)
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
    mainHandler.post {
      runCatching(action)
          .onFailure { error ->
            failPendingAndObserve("media3_dispatch_${error.javaClass.simpleName}")
          }
    }
  }

  private fun observeMedia3State(allowEffectReceipt: Boolean) {
    var applied: PlayerPort.AppliedEffect? = null
    var observed: PlayerPort.Snapshot? = null
    synchronized(lock) {
      if (released) {
        return
      }
      val previous = state
      val selected = player.currentMediaItem?.mediaId ?: previous.selectedVideoId()
      val playing = player.isPlaying
      val playbackState = playbackStateToken(player.playbackState)
      val position = player.currentPosition.coerceAtLeast(0)
      val next =
          PlayerStateProjection.apply(
              previous,
              PlayerStateProjection.Observation(
                  selected,
                  playing,
                  playbackState,
                  position,
              ),
          )
      val effect = pending
      val effectObserved =
          effect != null &&
              when (effect.command()) {
                "select_video" ->
                    selected == effect.videoId() && !player.playWhenReady
                "play" -> playing
                "pause" -> !playing && !player.playWhenReady
                else -> false
              }
      if (next != previous) {
        state = next
      }
      if (allowEffectReceipt && effectObserved) {
        pending = null
        applied = PlayerPort.AppliedEffect(requireNotNull(effect), state)
      } else if (next != previous) {
        observed = state
      }
    }
    applied?.let { listener?.onApplied(it) }
    observed?.let { listener?.onStateObserved(it) }
  }

  private fun failPendingAndObserve(reason: String) {
    var failed: PlayerPort.AcceptedEffect? = null
    val observed =
        synchronized(lock) {
          failed = pending
          pending = null
          val selected = player.currentMediaItem?.mediaId ?: state.selectedVideoId()
          state =
              PlayerPort.Snapshot(
                  state.revision() + 1,
                  selected,
                  false,
                  reason,
                  player.currentPosition.coerceAtLeast(0),
              )
          state
        }
    failed?.let { listener?.onFailed(it, reason) }
    listener?.onStateObserved(observed)
  }

  private fun schedulePositionObservation() {
    mainHandler.removeCallbacks(positionObservation)
    if (!released && player.isPlaying) {
      mainHandler.postDelayed(positionObservation, POSITION_OBSERVATION_INTERVAL_MS)
    }
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

  private companion object {
    const val POSITION_OBSERVATION_INTERVAL_MS = 500L
  }
}
