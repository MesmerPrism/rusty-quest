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
import java.io.File
import org.json.JSONObject

/**
 * Quest owns this effective state. Revisions advance only inside a Media3
 * callback that observes the accepted effect. A selection is not applied until
 * its matching Spatial carrier surface is attached and Media3 reaches READY.
 */
class Media3SpatialPlayerAdapter(
    context: Context,
    private val catalog: VideoCatalog,
    private val requestPresentation: (String) -> Unit,
) : PlayerPort, ConnectionHubSurfaceTarget {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val player = ExoPlayer.Builder(context).build()
  private val resourcesByVideoId =
      mapOf(
          "synthetic-grid-1s" to R.raw.synthetic_grid_1s,
          "synthetic-blue-2s" to R.raw.synthetic_blue_2s,
          "synthetic-180-mono" to R.raw.synthetic_180_mono_1s,
          "synthetic-180-sbs-lr" to R.raw.synthetic_180_sbs_lr_1s,
          "synthetic-180-top-bottom" to R.raw.synthetic_180_top_bottom_1s,
          "synthetic-360-mono" to R.raw.synthetic_360_mono_1s,
          "synthetic-360-sbs-lr" to R.raw.synthetic_360_sbs_lr_1s,
          "synthetic-360-top-bottom" to R.raw.synthetic_360_top_bottom_1s,
      )
  private val packageName = context.packageName
  private val lock = Any()
  private var listener: PlayerPort.Listener? = null
  private var pending: PlayerPort.AcceptedEffect? = null
  private var activeSurfaceVideoId: String? = null
  private var released = false
  private var state =
      PlayerPort.Snapshot(
          0,
          INITIAL_VIDEO_ID,
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
    check(catalog.contains(INITIAL_VIDEO_ID))
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
  }

  override fun snapshot(): PlayerPort.Snapshot = synchronized(lock) { state }

  override fun setListener(listener: PlayerPort.Listener) {
    synchronized(lock) { this.listener = listener }
  }

  override fun selectVideo(effect: PlayerPort.AcceptedEffect) {
    require(effect.command() == "select_video")
    catalog.require(requireNotNull(effect.videoId()))
    dispatch(effect) {
      player.pause()
      player.clearVideoSurface()
      synchronized(lock) { activeSurfaceVideoId = null }
      requestPresentation(requireNotNull(effect.videoId()))
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

  /** Called only by the fixed registration that currently owns the active video entity. */
  fun attachVideoSurface(videoId: String, surface: Surface) {
    mainHandler.post {
      if (released || !catalog.contains(videoId)) {
        return@post
      }
      val desiredVideoId =
          synchronized(lock) { pending?.videoId() ?: state.selectedVideoId() }
      if (videoId != desiredVideoId) {
        return@post
      }
      player.setVideoSurface(surface)
      synchronized(lock) { activeSurfaceVideoId = videoId }
      if (player.currentMediaItem?.mediaId != videoId) {
        player.setMediaItem(mediaItem(videoId))
      }
      if (player.playbackState == Player.STATE_IDLE) {
        player.prepare()
      }
    }
  }

  fun release() {
    mainHandler.post {
      released = true
      mainHandler.removeCallbacks(positionObservation)
      player.clearVideoSurface()
      player.release()
    }
  }

  /**
   * Applies only a Rust-authored Connection Hub authorization. The returned
   * status proves provider dispatch, not a Media3/render effect; effective
   * state is reported separately through the surface-state channel.
   */
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
    mainHandler.post {
      when (command) {
        "command.spatial_video_control.select_next",
        "command.spatial_video_control.select_previous" -> {
          val videoIds = catalog.videos().map { it.id() }
          val current = snapshot().selectedVideoId()
          val currentIndex = videoIds.indexOf(current).coerceAtLeast(0)
          val delta =
              if (command == "command.spatial_video_control.select_next") 1 else -1
          val videoId = videoIds[(currentIndex + delta + videoIds.size) % videoIds.size]
          catalog.require(videoId)
          player.pause()
          player.clearVideoSurface()
          synchronized(lock) { activeSurfaceVideoId = null }
          requestPresentation(videoId)
        }
        "command.spatial_video_control.play" -> player.play()
        "command.spatial_video_control.pause" -> player.pause()
        else -> error("unregistered Hub media command")
      }
    }
    return "provider_dispatch_queued_effect_pending"
  }

  override fun hubSurfaceState(): JSONObject {
    val observed = snapshot()
    val selected = catalog.require(observed.selectedVideoId())
    return JSONObject()
        .put("selected_video_id", observed.selectedVideoId())
        .put("selected_video_title", selected.title())
        .put("projection_shape", selected.projectionShape().protocolName())
        .put("stereo_layout", selected.stereoLayout().protocolName())
        .put("media_source_kind", selected.sourceKind().protocolName())
        .put("catalog_size", catalog.videos().size)
        .put(
            "user_video_count",
            catalog.videos().count {
              it.sourceKind() == VideoCatalog.SourceKind.PERSISTED_DOCUMENT_TREE
            },
        )
        .put("playing", observed.playing())
        .put("playback_state", observed.playbackState())
        .put("position_ms", observed.positionMs())
        .put("player_revision", observed.revision())
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
                    selected == effect.videoId() &&
                        activeSurfaceVideoId == effect.videoId() &&
                        !player.playWhenReady &&
                        player.playbackState == Player.STATE_READY
                "play" -> playing && activeSurfaceVideoId == selected
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
    val video = catalog.require(videoId)
    val uri =
        when (video.sourceKind()) {
          VideoCatalog.SourceKind.BUNDLED_CC0 -> {
            val resourceId =
                resourcesByVideoId[videoId]
                    ?: throw IllegalArgumentException("bundled video id has no resource")
            Uri.parse("android.resource://$packageName/$resourceId")
          }
          VideoCatalog.SourceKind.DEBUG_EXTERNAL_TEST -> {
            check(BuildConfig.DEBUG) { "external device-test media is debug-only" }
            val root =
                checkNotNull(appContext.getExternalFilesDir(DEBUG_MEDIA_DIRECTORY)) {
                  "debug media root is unavailable"
                }
            val source = File(root, "${video.resourceName()}.mp4")
            check(source.isFile && source.length() > 0) { "debug media slot is unavailable" }
            Uri.fromFile(source)
          }
          VideoCatalog.SourceKind.PERSISTED_DOCUMENT_TREE -> {
            Uri.parse(video.contentUri()).also { source ->
              check(source.scheme == "content") { "persisted media URI must use content" }
            }
          }
        }
    return MediaItem.Builder().setMediaId(videoId).setUri(uri).build()
  }

  private fun playbackStateToken(value: Int): String =
      when (value) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown"
      }

  companion object {
    const val CONNECTION_HUB_SURFACE_ID = "surface.spatial_video_control.media"
    const val INITIAL_VIDEO_ID = "synthetic-grid-1s"
    const val DEBUG_MEDIA_DIRECTORY = "immersive-video-test"
    const val POSITION_OBSERVATION_INTERVAL_MS = 500L
  }
}
