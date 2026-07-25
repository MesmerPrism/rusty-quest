package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import java.io.File

internal class SpatialImmersiveVideoPanelCoordinator(
    private val context: Context,
    private val resolution: SpatialImmersiveVideoRouteResolution,
    private val emitMarker: (String) -> Unit,
) {
  private var entity: Entity? = null
  private var panelSceneObject: PanelSceneObject? = null
  private var player: ExoPlayer? = null
  private var surface: Surface? = null
  private var resumeAfterPause = false
  private val progressHandler = Handler(Looper.getMainLooper())

  val requested: Boolean
    get() = resolution.requested

  fun routePolicyMarker(): String =
      SpatialImmersiveVideoRouteModule.routePolicyMarker(resolution)

  fun panelRegistrationOrNull(): PanelRegistration? {
    val config = (resolution as? SpatialImmersiveVideoRouteResolution.Ready)?.config ?: return null
    return VideoSurfacePanelRegistration(
        R.id.spatial_immersive_video_panel,
        surfaceConsumer = { _, videoSurface ->
          surface = videoSurface
          startPlayer(config, videoSurface)
        },
        settingsCreator = { mediaPanelSettings(config) },
        panelSetup = { panel, _ ->
          panelSceneObject = panel
          emitMarker(
              "channel=spatial-immersive-video status=panel-ready " +
                  "${config.markerFields()} surfaceValid=${panel.surface.isValid}"
          )
        },
    )
  }

  fun spawnAtViewer(viewerPose: Pose) {
    val config = (resolution as? SpatialImmersiveVideoRouteResolution.Ready)?.config ?: return
    if (entity != null) {
      return
    }
    entity =
        Entity.create(
            Panel(R.id.spatial_immersive_video_panel),
            Transform(Pose(viewerPose.t, viewerPose.q)),
            Visible(true),
        )
    emitMarker(
        "channel=spatial-immersive-video status=entity-spawned " +
            "${config.markerFields()} centeredOnInitialViewer=true"
    )
  }

  fun pause(reason: String) {
    val currentPlayer = player ?: return
    resumeAfterPause = currentPlayer.playWhenReady
    currentPlayer.pause()
    emitMarker(
        "channel=spatial-immersive-video status=paused reason=${activityMarkerToken(reason)}"
    )
  }

  fun resume(reason: String) {
    val config = (resolution as? SpatialImmersiveVideoRouteResolution.Ready)?.config ?: return
    val currentPlayer = player ?: return
    if (config.autoplay && resumeAfterPause) {
      currentPlayer.play()
      emitMarker(
          "channel=spatial-immersive-video status=resumed reason=${activityMarkerToken(reason)}"
      )
    }
  }

  fun destroy(reason: String) {
    progressHandler.removeCallbacksAndMessages(null)
    player?.run {
      playWhenReady = false
      clearVideoSurface()
      clearMediaItems()
      release()
    }
    player = null
    surface = null
    panelSceneObject = null
    entity?.destroy()
    entity = null
    if (requested) {
      emitMarker(
          "channel=spatial-immersive-video status=destroyed reason=${activityMarkerToken(reason)}"
      )
    }
  }

  private fun startPlayer(config: SpatialImmersiveVideoConfig, videoSurface: Surface) {
    if (player != null) {
      emitMarker(
          "channel=spatial-immersive-video status=surface-reused surfaceValid=${videoSurface.isValid}"
      )
      player?.setVideoSurface(videoSurface)
      return
    }
    val mediaUri =
        if (config.isGrantedContentUri) {
          Uri.parse(config.path)
        } else {
          Uri.fromFile(File(config.path))
        }
    val exoPlayer =
        ExoPlayer.Builder(context).build().apply {
          repeatMode = if (config.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
          addListener(playerListener(config))
          setMediaItem(MediaItem.fromUri(mediaUri))
          setVideoSurface(videoSurface)
          playWhenReady = config.autoplay
          prepare()
        }
    player = exoPlayer
    resumeAfterPause = config.autoplay
    emitMarker(
        "channel=spatial-immersive-video status=player-preparing " +
            "${config.markerFields()} surfaceValid=${videoSurface.isValid} " +
            "source=${if (config.isGrantedContentUri) "granted-media-content-uri" else "app-scoped-local-file"}"
    )
  }

  private fun playerListener(config: SpatialImmersiveVideoConfig): Player.Listener =
      object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          val state =
              when (playbackState) {
                Player.STATE_IDLE -> "idle"
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "unknown"
              }
          emitMarker(
              "channel=spatial-immersive-video status=playback-state state=$state " +
                  "playing=${player?.isPlaying == true} ${config.markerFields()}"
          )
        }

        override fun onRenderedFirstFrame() {
          val firstFramePositionMs = player?.currentPosition ?: 0L
          emitMarker(
              "channel=spatial-immersive-video status=first-frame-rendered " +
                  "${config.markerFields()}"
          )
          progressHandler.postDelayed(
              {
                val currentPositionMs = player?.currentPosition ?: firstFramePositionMs
                emitMarker(
                    "channel=spatial-immersive-video status=playback-progress " +
                        "firstFramePositionMs=$firstFramePositionMs " +
                        "currentPositionMs=$currentPositionMs " +
                        "advancing=${currentPositionMs > firstFramePositionMs} " +
                        "playing=${player?.isPlaying == true}"
                )
              },
              1_500L,
          )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
          emitMarker(
              "channel=spatial-immersive-video status=decoded-video-size " +
                  "decodedWidthPx=${videoSize.width} decodedHeightPx=${videoSize.height} " +
                  "expectedWidthPx=${config.widthPx} expectedHeightPx=${config.heightPx}"
          )
        }

        override fun onPlayerError(error: PlaybackException) {
          emitMarker(
              "channel=spatial-immersive-video status=playback-error " +
                  "errorCode=${error.errorCode} error=${activityMarkerToken(error.javaClass.simpleName)} " +
                  "message=${activityMarkerToken(error.message ?: "none")}"
          )
        }
      }

  private fun mediaPanelSettings(config: SpatialImmersiveVideoConfig): MediaPanelSettings =
      MediaPanelSettings(
          shape =
              when (config.shape) {
                SpatialImmersiveVideoShape.Flat ->
                    QuadShapeOptions(
                        width = config.flatPanelWidthMeters,
                        height = config.flatPanelHeightMeters,
                    )
                SpatialImmersiveVideoShape.Equirect180 ->
                    Equirect180ShapeOptions(radius = config.radiusMeters)
                SpatialImmersiveVideoShape.Equirect360 ->
                    Equirect360ShapeOptions(radius = config.radiusMeters)
              },
          display = PixelDisplayOptions(width = config.widthPx, height = config.heightPx),
          rendering =
              MediaPanelRenderOptions(
                  stereoMode =
                      when (config.stereoLayout) {
                        SpatialImmersiveVideoStereoLayout.Mono -> StereoMode.None
                        SpatialImmersiveVideoStereoLayout.SideBySideLeftRight ->
                            StereoMode.LeftRight
                        SpatialImmersiveVideoStereoLayout.TopBottom -> StereoMode.UpDown
                      },
                  zIndex = config.zIndex,
              ),
          input = PanelInputOptions(0),
      )

  companion object {
    fun resolveFromIntent(
        context: Context,
        intent: Intent,
        sourceReadable: ((String) -> Boolean)? = null,
    ): SpatialImmersiveVideoRouteResolution {
      val values =
          SpatialImmersiveVideoLaunchValues(
              enabled =
                  if (intent.hasExtra(SpatialImmersiveVideoRouteModule.EXTRA_ENABLED)) {
                    intent
                        .getBooleanExtra(SpatialImmersiveVideoRouteModule.EXTRA_ENABLED, false)
                        .toString()
                  } else {
                    null
                  },
              path =
                  intent.getStringExtra(SpatialImmersiveVideoRouteModule.EXTRA_PATH)
                      ?: intent.dataString,
              shape = intent.getStringExtra(SpatialImmersiveVideoRouteModule.EXTRA_SHAPE),
              stereo = intent.getStringExtra(SpatialImmersiveVideoRouteModule.EXTRA_STEREO),
              widthPx =
                  if (intent.hasExtra(SpatialImmersiveVideoRouteModule.EXTRA_WIDTH_PX)) {
                    intent
                        .getIntExtra(SpatialImmersiveVideoRouteModule.EXTRA_WIDTH_PX, 0)
                        .toString()
                  } else {
                    null
                  },
              heightPx =
                  if (intent.hasExtra(SpatialImmersiveVideoRouteModule.EXTRA_HEIGHT_PX)) {
                    intent
                        .getIntExtra(SpatialImmersiveVideoRouteModule.EXTRA_HEIGHT_PX, 0)
                        .toString()
                  } else {
                    null
                  },
              autoplay =
                  if (intent.hasExtra(SpatialImmersiveVideoRouteModule.EXTRA_AUTOPLAY)) {
                    intent
                        .getBooleanExtra(SpatialImmersiveVideoRouteModule.EXTRA_AUTOPLAY, true)
                        .toString()
                  } else {
                    null
                  },
              loop =
                  if (intent.hasExtra(SpatialImmersiveVideoRouteModule.EXTRA_LOOP)) {
                    intent
                        .getBooleanExtra(SpatialImmersiveVideoRouteModule.EXTRA_LOOP, true)
                        .toString()
                  } else {
                    null
                  },
              radiusMeters =
                  intent.getStringExtra(SpatialImmersiveVideoRouteModule.EXTRA_RADIUS_METERS),
          )

      if (values.enabled == null || values.enabled == "false") {
        return SpatialImmersiveVideoRouteModule.resolve(
            values = values,
            allowedMediaRoot = "",
            sourceReadable = sourceReadable ?: { File(it).isFile },
        )
      }

      val externalFilesRoot =
          context.getExternalFilesDir(null)?.canonicalFile
              ?: return SpatialImmersiveVideoRouteResolution.Rejected(
                  "app-external-files-root-unavailable"
              )
      val allowedMediaRoot = File(externalFilesRoot, "immersive-video").canonicalFile
      val requestedPath = values.path?.trim().orEmpty()
      val packageScopedAliases =
          listOf(
              "/sdcard/Android/data/${context.packageName}/files/immersive-video/",
              "/storage/emulated/0/Android/data/${context.packageName}/files/immersive-video/",
          )
      val relativePath =
          when {
            requestedPath.startsWith("immersive-video/") -> requestedPath
            else -> {
              val matchingAlias = packageScopedAliases.firstOrNull(requestedPath::startsWith)
              if (matchingAlias == null) {
                null
              } else {
                "immersive-video/" + requestedPath.removePrefix(matchingAlias)
              }
            }
          }
      val resolvedPath =
          relativePath?.let { File(externalFilesRoot, it).canonicalPath } ?: requestedPath
      val resolvedSourceReadable =
          sourceReadable
              ?: { candidate ->
                if (candidate.startsWith("content://")) {
                  runCatching {
                        context.contentResolver
                            .openAssetFileDescriptor(Uri.parse(candidate), "r")
                            ?.use { true } == true
                      }
                      .getOrDefault(false)
                } else {
                  File(candidate).isFile
                }
              }

      return SpatialImmersiveVideoRouteModule.resolve(
          values = values.copy(path = resolvedPath),
          allowedMediaRoot = allowedMediaRoot.path,
          sourceReadable = resolvedSourceReadable,
      )
    }
  }
}
