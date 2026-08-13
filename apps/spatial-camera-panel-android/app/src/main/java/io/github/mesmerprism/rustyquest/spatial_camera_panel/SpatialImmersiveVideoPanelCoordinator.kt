package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.LayerAlphaBlend
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
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
    private val recenterAfterNewVideoLoad: (String, String) -> Boolean,
) {
  private var entity: Entity? = null
  private var blackBackdropEntity: Entity? = null
  private var panelSceneObject: PanelSceneObject? = null
  private var player: ExoPlayer? = null
  private var directDecoderGeneration = 0L
  private var surface: Surface? = null
  private var playerSurface: Surface? = null
  private var directVideoConsumerRequired = true
  private var directResumePositionMs = 0L
  private var directSuspendedAtRealtimeMs: Long? = null
  private var pendingDirectResumeTargetMs: Long? = null
  private var spawnPose: Pose? = null
  private var latestViewerPose: Pose? = null
  private var resumeAfterPause = false
  private var playbackEnabled =
      (resolution as? SpatialImmersiveVideoRouteResolution.Ready)
          ?.playbackInitiallyEnabled == true
  private var activeIndex = 0
  private var presentationMode = SpatialImmersiveVideoPresentationMode.WorldAnchored
  private var backgroundMode = SpatialBackgroundMode.Black
  private val progressHandler = Handler(Looper.getMainLooper())
  private val transitionHandler = Handler(Looper.getMainLooper())
  private var transitionGeneration = 0L
  private var transitionTargetIndex: Int? = null
  private var transitionSource = ""
  private var incomingFadeGeneration = -1L
  private val autoRecenterGate = SpatialImmersiveVideoAutoRecenterGate()
  private val initialConfig: SpatialImmersiveVideoConfig?
    get() = (resolution as? SpatialImmersiveVideoRouteResolution.Ready)?.config
  private val catalog: List<SpatialImmersiveVideoConfig> by
      lazy(LazyThreadSafetyMode.NONE, ::loadCompatibleCatalog)
  private val panelRegistrationIds: Map<SpatialImmersiveVideoPresentationMode, List<Int>> by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialImmersiveVideoPresentationMode.entries.associateWith {
          catalog.map { View.generateViewId() }
        }
      }
  private val blackBackdropRegistrationIds:
      Map<SpatialImmersiveVideoPresentationMode, List<Int>> by
      lazy(LazyThreadSafetyMode.NONE) {
        SpatialImmersiveVideoPresentationMode.entries.associateWith {
          catalog.map { View.generateViewId() }
        }
      }

  val requested: Boolean
    get() = resolution.requested

  val activeConfig: SpatialImmersiveVideoConfig?
    get() = catalog.getOrNull(activeIndex)

  val activeOfflinePack: OfflineImmersiveMediaPack?
    get() = activeConfig?.offlinePack

  val activePlainMedia: SharedPlainImmersiveMediaItem?
    get() = activeConfig?.plainMedia

  val customProjectionCompatible: Boolean
    get() =
        SpatialImmersiveVideoSessionPolicy.customProjectionSettings(
            SpatialVideoProjectionSettings.disabled(),
            activeConfig,
        ) != null

  val customCarrierPresentation: SpatialImmersiveVideoCustomCarrierPresentation?
    get() =
        SpatialImmersiveVideoSessionPolicy.customCarrierPresentation(
            activeConfig,
            presentationMode,
        )

  fun routePolicyMarker(): String =
      SpatialImmersiveVideoRouteModule.routePolicyMarker(resolution)

  fun sessionSnapshot(): SpatialImmersiveVideoSessionSnapshot {
    val config = activeConfig
    return SpatialImmersiveVideoSessionSnapshot(
        requested = requested,
        available = config != null,
        playbackEnabled = playbackEnabled && config != null,
        activeIndex = if (config == null) -1 else activeIndex,
        itemCount = catalog.size,
        activePackId = config?.offlinePack?.packId,
        activeMediaLabel = config?.catalogLabel,
        customProjectionCompatible =
            SpatialImmersiveVideoSessionPolicy.customProjectionSettings(
                SpatialVideoProjectionSettings.disabled(),
                config,
            ) != null,
        presentationMode = presentationMode,
        backgroundMode = backgroundMode,
        items =
            catalog.mapIndexed { index, item ->
              SpatialImmersiveVideoCatalogItemSnapshot(
                  index = index,
                  label = item.catalogLabel,
                  sourceLabel =
                      if (item.isEncryptedOfflinePack) "Encrypted pack" else "Shared plain video",
                  projectionLabel = item.shape.token,
                  stereoLabel = item.stereoLayout.token,
                  dimensionsLabel = "${item.widthPx} × ${item.heightPx}",
              )
            },
    )
  }

  fun setPresentationMode(
      requestedMode: SpatialImmersiveVideoPresentationMode,
      source: String,
  ): SpatialImmersiveVideoSessionSnapshot {
    val changed = presentationMode != requestedMode
    if (changed) {
      cancelSelectionTransition("presentation-change")
    }
    presentationMode = requestedMode
    val panelRebuilt = changed && rebuildActiveEntity("presentation-change")
    val presentation = customCarrierPresentation
    emitMarker(
        "channel=spatial-immersive-video status=presentation-mode-applied " +
            "source=${activityMarkerToken(source)} changed=$changed " +
            "spatialPanelRebuilt=$panelRebuilt activityRestarted=false " +
            "customProjectionConfigurationRetained=true customProjectionCarrierShape=planar-quad " +
            (presentation?.markerFields()
                ?: "videoCarrierPresentation=${presentationMode.token} " +
                    "headOrientationLocked=" +
                    "${presentationMode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder}")
    )
    return sessionSnapshot()
  }

  fun setBackgroundMode(
      requestedMode: SpatialBackgroundMode,
      source: String,
  ): SpatialImmersiveVideoSessionSnapshot {
    val changed = backgroundMode != requestedMode
    backgroundMode = requestedMode
    if (requestedMode == SpatialBackgroundMode.Black) {
      spawnBlackBackdropIfReady("background-mode-black")
    } else {
      blackBackdropEntity?.destroy()
      blackBackdropEntity = null
    }
    emitMarker(
        "channel=spatial-immersive-video status=background-mode-applied " +
            "source=${activityMarkerToken(source)} changed=$changed " +
            "backgroundMode=${backgroundMode.token} " +
            "videoBlackBacking=${blackBackdropEntity != null} " +
            "videoPlaybackEnabled=$playbackEnabled activityRestarted=false"
    )
    return sessionSnapshot()
  }

  /**
   * Makes the direct Spatial decoder mutually exclusive with the same-media custom decoder.
   * Playback intent remains enabled: only the route with a current visual contribution owns a
   * codec, and a returning direct route advances its resume target by the hidden interval.
   */
  fun setDirectVideoConsumerRequired(required: Boolean, source: String): Boolean {
    val previousRequired = directVideoConsumerRequired
    directVideoConsumerRequired = required
    if (!required) {
      player?.let { currentPlayer ->
        directResumePositionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
        directSuspendedAtRealtimeMs = SystemClock.elapsedRealtime()
      }
      entity?.setComponent(Visible(false))
      releasePlayer("direct-zero-contribution")
    } else {
      entity?.setComponent(Visible(playbackEnabled))
      val currentSurface = surface
      val config = activeConfig
      if (playbackEnabled && currentSurface != null && currentSurface.isValid && config != null) {
        startPlayer(config, currentSurface)
      }
    }
    val active = player != null
    emitMarker(
        "channel=spatial-immersive-video status=direct-consumer-policy-applied " +
            "source=${activityMarkerToken(source)} " +
            "previousDirectVideoConsumerRequired=$previousRequired " +
            "directVideoConsumerRequired=$required visualContribution=$required " +
            "playbackIntentEnabled=$playbackEnabled directVideoCarrierVisible=" +
            "${entity != null && required && playbackEnabled} " +
            "activeDecoderCount=${if (active) 1 else 0} decoderOverlap=false " +
            "zeroContributionDecodeWorkSkipped=${!required && !active}"
    )
    return active
  }

  fun directDecoderActive(): Boolean = player != null

  fun selectPrevious(source: String): SpatialImmersiveVideoSelection =
      selectIndex(activeIndex - 1, source)

  fun selectNext(source: String): SpatialImmersiveVideoSelection =
      selectIndex(activeIndex + 1, source)

  fun selectCatalogIndex(index: Int, source: String): SpatialImmersiveVideoSelection =
      selectIndex(index, source)

  fun selectPack(packId: String, source: String): SpatialImmersiveVideoSelection {
    val normalizedPackId = packId.trim().lowercase()
    val requestedIndex =
        catalog.indexOfFirst { it.offlinePack?.packId == normalizedPackId }
    if (requestedIndex < 0) {
      emitMarker(
          "channel=spatial-immersive-video status=selection-rejected " +
              "reason=pack-not-in-session source=${activityMarkerToken(source)}"
      )
      return SpatialImmersiveVideoSelection(sessionSnapshot(), activeConfig, false)
    }
    return selectIndex(requestedIndex, source)
  }

  fun customProjectionSettings(
      base: SpatialVideoProjectionSettings,
  ): SpatialVideoProjectionSettings? =
      SpatialImmersiveVideoSessionPolicy.customProjectionSettings(base, activeConfig)

  fun setPlaybackEnabled(
      requestedEnabled: Boolean,
      source: String,
  ): SpatialImmersiveVideoSessionSnapshot {
    val effectiveEnabled = requestedEnabled && activeConfig != null
    if (playbackEnabled == effectiveEnabled) {
      emitMarker(
          "channel=spatial-immersive-video status=playback-visibility-unchanged " +
              "source=${activityMarkerToken(source)} playbackEnabled=$playbackEnabled " +
              "directVideoCarrierVisible=${entity != null} activityRestarted=false"
      )
      return sessionSnapshot()
    }
    playbackEnabled = effectiveEnabled
    cancelSelectionTransition("playback-visibility-change")
    if (!playbackEnabled) {
      releasePlayer("playback-disabled-zero-contribution")
      directResumePositionMs = 0L
      directSuspendedAtRealtimeMs = null
      pendingDirectResumeTargetMs = null
      surface = null
      panelSceneObject = null
      entity?.destroy()
      entity = null
      if (backgroundMode == SpatialBackgroundMode.Black) {
        spawnBlackBackdropIfReady("playback-disabled-background-retained")
      } else {
        blackBackdropEntity?.destroy()
        blackBackdropEntity = null
      }
      emitMarker(
          "channel=spatial-immersive-video status=playback-disabled " +
              "source=${activityMarkerToken(source)} playbackEnabled=false " +
              "directVideoCarrierVisible=false " +
              "backgroundMode=${backgroundMode.token} " +
              "videoBlackBackingRetained=${blackBackdropEntity != null} " +
              "customProjectionRetained=true " +
              "activityRestarted=false"
      )
    } else {
      spawnActiveEntity("playback-enabled")
      emitMarker(
          "channel=spatial-immersive-video status=playback-enabled " +
              "source=${activityMarkerToken(source)} playbackEnabled=true " +
              "directVideoCarrierVisible=${entity != null} customProjectionRetained=true " +
              "activityRestarted=false"
      )
    }
    return sessionSnapshot()
  }

  fun panelRegistrations(): List<PanelRegistration> =
      SpatialImmersiveVideoPresentationMode.entries.flatMap { registeredMode ->
        catalog.flatMapIndexed { index, config ->
          listOf(
              blackBackdropPanelRegistration(index, config, registeredMode),
              VideoSurfacePanelRegistration(
                  requireNotNull(panelRegistrationIds[registeredMode]).get(index),
                  surfaceConsumer = { _, videoSurface ->
                    if (index == activeIndex && registeredMode == presentationMode) {
                      surface = videoSurface
                      startPlayer(config, videoSurface)
                    }
                  },
                  settingsCreator = { mediaPanelSettings(config, registeredMode) },
                  panelSetup = { panel, _ ->
                    if (index == activeIndex && registeredMode == presentationMode) {
                      panelSceneObject = panel
                      if (transitionTargetIndex == index) {
                        applyDirectLayerOpacity(0.0f)
                      }
                      emitMarker(
                          "channel=spatial-immersive-video status=panel-ready " +
                              "registrationOrdinal=${index + 1} " +
                              "${config.markerFields()} " +
                              "${SpatialImmersiveVideoSessionPolicy.directPanelPresentation(config, registeredMode).markerFields()} " +
                              "surfaceValid=${panel.surface.isValid}"
                      )
                    }
                  },
              ),
          )
        }
      }

  private fun blackBackdropPanelRegistration(
      index: Int,
      config: SpatialImmersiveVideoConfig,
      registeredMode: SpatialImmersiveVideoPresentationMode,
  ): PanelRegistration =
      ComposeViewPanelRegistration(
          requireNotNull(blackBackdropRegistrationIds[registeredMode]).get(index),
          composeViewCreator = { _, panelContext ->
            ComposeView(panelContext).apply {
              setBackgroundColor(AndroidColor.BLACK)
              alpha = 1.0f
              setWillNotDraw(false)
              setLayerType(View.LAYER_TYPE_HARDWARE, null)
              setContent {
                Box(Modifier.fillMaxSize().background(Color.Black))
              }
            }
          },
          settingsCreator = { blackBackdropMediaPanelSettings(config, registeredMode) },
          panelSetupWithComposeView = { _, _, _ ->
            emitMarker(
                "channel=spatial-immersive-video status=black-backing-panel-ready " +
                    "backingOpaque=true backingColor=black " +
                    "backingShape=${SpatialImmersiveVideoBlackBackingPolicy.shapeToken(config, registeredMode)} " +
                    "backgroundMode=${backgroundMode.token} " +
                    "backingRegistrationImmutable=true " +
                    "uncoveredVideoPixelsRevealPassthrough=false"
            )
          },
      )

  fun spawnAtViewer(viewerPose: Pose) {
    spawnPose = Pose(viewerPose.t, viewerPose.q)
    latestViewerPose = Pose(viewerPose.t, viewerPose.q)
    if (entity != null || blackBackdropEntity != null) {
      return
    }
    spawnActiveEntity("initial-spawn")
  }

  fun updateFromViewer(viewerPose: Pose) {
    latestViewerPose = Pose(viewerPose.t, viewerPose.q)
    if (presentationMode != SpatialImmersiveVideoPresentationMode.HeadFixedBorder) {
      return
    }
    val pose = headFixedPose(viewerPose)
    entity?.setComponent(Transform(pose))
    blackBackdropEntity?.setComponent(Transform(pose))
  }

  fun recenterAtViewer(
      viewerPose: Pose,
      source: String,
      detail: String,
  ): Boolean {
    val currentEntity = entity
    val config = activeConfig
    if (!playbackEnabled || currentEntity == null || config == null) {
      emitMarker(
          "channel=spatial-immersive-video status=recenter-rejected " +
              "reason=no-active-direct-video source=${activityMarkerToken(source)} " +
              "detail=${activityMarkerToken(detail)} activityRestarted=false"
      )
      return false
    }
    spawnPose = Pose(viewerPose.t, viewerPose.q)
    latestViewerPose = Pose(viewerPose.t, viewerPose.q)
    val recenteredPose =
        if (presentationMode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder) {
          headFixedPose(viewerPose)
        } else {
          Pose(viewerPose.t, viewerPose.q)
        }
    currentEntity.setComponent(Transform(recenteredPose))
    blackBackdropEntity?.setComponent(Transform(recenteredPose))
    emitMarker(
        "channel=spatial-immersive-video status=recenter-applied " +
            "source=${activityMarkerToken(source)} detail=${activityMarkerToken(detail)} " +
            "videoCarrierPresentation=${presentationMode.token} entityRetained=true " +
            "videoBlackBackingRetained=${blackBackdropEntity != null} " +
            "decoderRetained=true customProjectionCarrierRetained=true " +
            "customProjectionStackRestarted=false activityRestarted=false ${config.markerFields()}"
    )
    return true
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
    if (!playbackEnabled) {
      return
    }
    val config = activeConfig ?: return
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
    cancelSelectionTransition("destroy")
    transitionHandler.removeCallbacksAndMessages(null)
    releasePlayer("coordinator-destroy")
    surface = null
    spawnPose = null
    latestViewerPose = null
    panelSceneObject = null
    entity?.destroy()
    entity = null
    blackBackdropEntity?.destroy()
    blackBackdropEntity = null
    if (requested) {
      emitMarker(
          "channel=spatial-immersive-video status=destroyed reason=${activityMarkerToken(reason)}"
      )
    }
  }

  private fun selectIndex(
      requestedIndex: Int,
      source: String,
  ): SpatialImmersiveVideoSelection {
    if (catalog.isEmpty()) {
      return SpatialImmersiveVideoSelection(sessionSnapshot(), null, false)
    }
    if (transitionTargetIndex != null) {
      emitMarker(
          "channel=spatial-immersive-video status=selection-rejected " +
              "reason=transition-in-progress source=${activityMarkerToken(source)} " +
              "activeOrdinal=${activeIndex + 1} itemCount=${catalog.size} " +
              "activityRestarted=false"
      )
      return SpatialImmersiveVideoSelection(sessionSnapshot(), activeConfig, false)
    }
    val normalizedIndex =
        SpatialImmersiveVideoSessionPolicy.wrappedIndex(requestedIndex, catalog.size)
    val changed = normalizedIndex != activeIndex
    if (changed) {
      directResumePositionMs = 0L
      directSuspendedAtRealtimeMs = null
      pendingDirectResumeTargetMs = null
    }
    val hadDirectPanelEntity = entity != null
    activeIndex = normalizedIndex
    val config = activeConfig
    if (changed && config != null) {
      autoRecenterGate.arm(config.path, source)
      if (hadDirectPanelEntity) {
        beginSelectionTransition(source)
      } else if (spawnPose != null) {
        blackBackdropEntity?.destroy()
        blackBackdropEntity = null
        spawnActiveEntity("selection-change-without-active-entity")
      }
      emitMarker(
          "channel=spatial-immersive-video status=selection-applied " +
              "source=${activityMarkerToken(source)} activeOrdinal=${activeIndex + 1} " +
              "itemCount=${catalog.size} decoderRestartDeferred=$hadDirectPanelEntity " +
              "directSpatialPanelRebuildDeferred=$hadDirectPanelEntity " +
              "directLayerFadeTransition=$hadDirectPanelEntity " +
              "customProjectionCarrierRetained=true customProjectionStackRestarted=false " +
              "activityRestarted=false ${config.markerFields()}"
      )
    }
    return SpatialImmersiveVideoSelection(sessionSnapshot(), config, changed)
  }

  fun notifyCustomProjectionVideoLoaded(
      config: SpatialImmersiveVideoConfig,
  ): Boolean =
      dispatchPendingVideoLoadRecenter(
          config = config,
          readyRoute = "custom-projection-decoder-ready",
      )

  private fun beginSelectionTransition(source: String) {
    transitionGeneration += 1L
    val generation = transitionGeneration
    transitionTargetIndex = activeIndex
    transitionSource = source
    incomingFadeGeneration = -1L
    emitMarker(
        "channel=spatial-immersive-video status=direct-layer-fade-out-started " +
            "source=${activityMarkerToken(source)} durationMs=$DIRECT_VIDEO_FADE_DURATION_MS " +
            "singleDecoderSwap=true customProjectionCarrierRetained=true"
    )
    animateDirectLayerOpacity(
        from = 1.0f,
        to = 0.0f,
        generation = generation,
        phase = "fade-out",
    ) {
      if (generation != transitionGeneration || transitionTargetIndex == null) {
        return@animateDirectLayerOpacity
      }
      releasePlayer("selection-hidden-source-swap")
      surface = null
      panelSceneObject = null
      entity?.destroy()
      entity = null
      emitMarker(
          "channel=spatial-immersive-video status=direct-layer-source-swap " +
              "source=${activityMarkerToken(source)} oldLayerHidden=true " +
              "decoderRestarted=true directSpatialPanelRebuilt=true " +
              "customProjectionCarrierRetained=true customProjectionStackRestarted=false " +
              "activityRestarted=false"
      )
      spawnActiveEntity("selection-change-hidden-swap")
      transitionHandler.postDelayed(
          {
            if (generation == transitionGeneration && transitionTargetIndex != null) {
              applyDirectLayerOpacity(1.0f)
              emitMarker(
                  "channel=spatial-immersive-video status=direct-layer-fade-timeout " +
                      "source=${activityMarkerToken(source)} firstFrameObserved=false " +
                      "failOpenVisible=true customProjectionCarrierRetained=true"
              )
              completeSelectionTransition(generation, "first-frame-timeout")
            }
          },
          DIRECT_VIDEO_FIRST_FRAME_TIMEOUT_MS,
      )
    }
  }

  private fun beginIncomingFade(
      config: SpatialImmersiveVideoConfig,
      layerReadyAttempt: Int = 0,
  ) {
    val targetIndex = transitionTargetIndex ?: return
    if (targetIndex != activeIndex || config.path != activeConfig?.path) {
      return
    }
    val generation = transitionGeneration
    if (layerReadyAttempt == 0) {
      if (incomingFadeGeneration == generation) {
        return
      }
      incomingFadeGeneration = generation
    }
    if (panelSceneObject?.layer == null) {
      if (layerReadyAttempt < DIRECT_VIDEO_LAYER_READY_MAX_ATTEMPTS) {
        transitionHandler.postDelayed(
            {
              if (generation == transitionGeneration && transitionTargetIndex != null) {
                beginIncomingFade(config, layerReadyAttempt + 1)
              }
            },
            DIRECT_VIDEO_FADE_FRAME_INTERVAL_MS,
        )
      } else {
        emitMarker(
            "channel=spatial-immersive-video status=direct-layer-fade-unavailable " +
                "phase=fade-in directLayerAvailable=false firstFrameObserved=true " +
                "customProjectionCarrierRetained=true"
        )
        completeSelectionTransition(generation, "incoming-layer-unavailable")
      }
      return
    }
    emitMarker(
        "channel=spatial-immersive-video status=direct-layer-fade-in-started " +
            "source=${activityMarkerToken(transitionSource)} firstFrameObserved=true " +
            "durationMs=$DIRECT_VIDEO_FADE_DURATION_MS customProjectionCarrierRetained=true"
    )
    animateDirectLayerOpacity(
        from = 0.0f,
        to = 1.0f,
        generation = generation,
        phase = "fade-in",
    ) {
      completeSelectionTransition(generation, "first-frame-fade-complete")
    }
  }

  private fun animateDirectLayerOpacity(
      from: Float,
      to: Float,
      generation: Long,
      phase: String,
      onComplete: () -> Unit,
  ) {
    if (!applyDirectLayerOpacity(from)) {
      emitMarker(
          "channel=spatial-immersive-video status=direct-layer-fade-unavailable " +
              "phase=${activityMarkerToken(phase)} directLayerAvailable=false " +
              "customProjectionCarrierRetained=true"
      )
      onComplete()
      return
    }
    val startedAtMs = SystemClock.uptimeMillis()
    val step =
        object : Runnable {
          override fun run() {
            if (generation != transitionGeneration || transitionTargetIndex == null) {
              return
            }
            val elapsedMs = SystemClock.uptimeMillis() - startedAtMs
            val opacity =
                SpatialImmersiveVideoSessionPolicy.fadeOpacity(
                    from,
                    to,
                    elapsedMs,
                    DIRECT_VIDEO_FADE_DURATION_MS,
                )
            if (!applyDirectLayerOpacity(opacity)) {
              emitMarker(
                  "channel=spatial-immersive-video status=direct-layer-fade-failed " +
                      "phase=${activityMarkerToken(phase)} " +
                      "customProjectionCarrierRetained=true"
              )
              onComplete()
              return
            }
            if (elapsedMs >= DIRECT_VIDEO_FADE_DURATION_MS) {
              onComplete()
            } else {
              transitionHandler.postDelayed(this, DIRECT_VIDEO_FADE_FRAME_INTERVAL_MS)
            }
          }
        }
    transitionHandler.post(step)
  }

  private fun applyDirectLayerOpacity(opacity: Float): Boolean {
    val layer = panelSceneObject?.layer ?: return false
    return runCatching {
          layer.setAlphaBlend(
              LayerAlphaBlend(
                  BlendFactor.SOURCE_ALPHA,
                  BlendFactor.ONE_MINUS_SOURCE_ALPHA,
                  BlendFactor.ONE,
                  BlendFactor.ONE_MINUS_SOURCE_ALPHA,
              )
          )
          layer.setColorScaleBias(
              Vector4(1.0f, 1.0f, 1.0f, opacity.coerceIn(0.0f, 1.0f)),
              Vector4(0.0f),
          )
        }
        .isSuccess
  }

  private fun completeSelectionTransition(generation: Long, reason: String) {
    if (generation != transitionGeneration || transitionTargetIndex == null) {
      return
    }
    applyDirectLayerOpacity(1.0f)
    transitionTargetIndex = null
    val completedSource = transitionSource
    transitionSource = ""
    incomingFadeGeneration = -1L
    emitMarker(
        "channel=spatial-immersive-video status=direct-layer-transition-complete " +
            "source=${activityMarkerToken(completedSource)} reason=${activityMarkerToken(reason)} " +
            "directLayerOpacity=1.0 customProjectionCarrierRetained=true " +
            "customProjectionStackRestarted=false activityRestarted=false"
    )
  }

  private fun cancelSelectionTransition(reason: String) {
    if (transitionTargetIndex == null) {
      return
    }
    transitionGeneration += 1L
    applyDirectLayerOpacity(1.0f)
    transitionTargetIndex = null
    transitionSource = ""
    incomingFadeGeneration = -1L
    emitMarker(
        "channel=spatial-immersive-video status=direct-layer-transition-cancelled " +
            "reason=${activityMarkerToken(reason)} directLayerOpacity=1.0 " +
            "customProjectionCarrierRetained=true"
    )
  }

  private fun rebuildActiveEntity(reason: String): Boolean {
    if (entity == null && blackBackdropEntity == null) {
      return false
    }
    releasePlayer("presentation-rebuild")
    surface = null
    panelSceneObject = null
    entity?.destroy()
    entity = null
    blackBackdropEntity?.destroy()
    blackBackdropEntity = null
    spawnActiveEntity(reason)
    return entity != null || blackBackdropEntity != null
  }

  private fun spawnActiveEntity(reason: String) {
    val config = activeConfig ?: return
    val pose = spawnPose ?: return
    val entityPose =
        if (presentationMode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder) {
          headFixedPose(latestViewerPose ?: pose)
        } else {
          Pose(pose.t, pose.q)
        }
    spawnBlackBackdropIfReady(reason, entityPose)
    if (!playbackEnabled) {
      return
    }
    val registrationId = panelRegistrationIds[presentationMode]?.getOrNull(activeIndex) ?: return
    entity =
        Entity.create(
            Panel(registrationId),
            Transform(entityPose),
              Visible(directVideoConsumerRequired),
        )
    val presentation = customCarrierPresentation
    emitMarker(
        "channel=spatial-immersive-video status=entity-spawned " +
            "reason=${activityMarkerToken(reason)} registrationOrdinal=${activeIndex + 1} " +
            "${config.markerFields()} directVideoLayer=true customProjectionCarrierShape=planar-quad " +
            "directVideoLayerZIndex=$DIRECT_VIDEO_BACKGROUND_Z_INDEX " +
            "videoBlackBacking=${blackBackdropEntity != null} videoBlackBackingZIndex=$DIRECT_VIDEO_BLACK_BACKING_Z_INDEX " +
            "videoBlackBackingShape=${SpatialImmersiveVideoBlackBackingPolicy.shapeToken(config, presentationMode)} " +
            "backgroundMode=${backgroundMode.token} " +
            "uncoveredVideoPixelsRevealPassthrough=${backgroundMode != SpatialBackgroundMode.Black} " +
            "${SpatialImmersiveVideoSessionPolicy.directPanelPresentation(config, presentationMode).markerFields()} " +
            (presentation?.markerFields()
                ?: "videoCarrierPresentation=${presentationMode.token}")
    )
  }

  private fun spawnBlackBackdropIfReady(reason: String, explicitPose: Pose? = null) {
    if (backgroundMode != SpatialBackgroundMode.Black) {
      blackBackdropEntity?.destroy()
      blackBackdropEntity = null
      return
    }
    val config = activeConfig ?: return
    val basePose = explicitPose ?: spawnPose ?: return
    val entityPose =
        if (explicitPose != null) {
          explicitPose
        } else if (presentationMode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder) {
          headFixedPose(latestViewerPose ?: basePose)
        } else {
          Pose(basePose.t, basePose.q)
        }
    val registrationId =
        blackBackdropRegistrationIds[presentationMode]?.getOrNull(activeIndex) ?: return
    val previousBackdrop = blackBackdropEntity
    blackBackdropEntity =
        Entity.create(
            Panel(registrationId),
            Transform(entityPose),
            Visible(true),
        )
    previousBackdrop?.destroy()
    emitMarker(
        "channel=spatial-immersive-video status=black-backing-spawned " +
            "reason=${activityMarkerToken(reason)} backgroundMode=${backgroundMode.token} " +
            "videoPlaybackEnabled=$playbackEnabled registrationOrdinal=${activeIndex + 1} " +
            "backingShape=${SpatialImmersiveVideoBlackBackingPolicy.shapeToken(config, presentationMode)}"
    )
  }

  private fun headFixedPose(viewerPose: Pose): Pose =
      Pose(
          viewerPose.t + viewerPose.forward() * HEAD_FIXED_VIDEO_DISTANCE_METERS,
          viewerPose.q,
      )

  private fun loadCompatibleCatalog(): List<SpatialImmersiveVideoConfig> {
    val anchor = initialConfig ?: return emptyList()
    val anchorPack = anchor.offlinePack
    val mediaPackRoot = File(context.filesDir, "offline-media-packs")
    val packagedPackIds =
        PackagedOfflineImmersiveMediaPackImporter.packagedPackIds(context)
    val retainedPackIds =
        PackagedOfflineImmersiveMediaPackImporter.installedPackIds(context)
    val sharedPackIds = SharedOfflineImmersiveMediaLibrary.packIds(context)
    val candidatePackIds =
        (sharedPackIds + packagedPackIds + retainedPackIds)
            .asSequence()
            .filter { it != anchorPack?.packId }
            .distinct()
            .sorted()
            .toList()
    val plainDiscovery = SharedPlainImmersiveMediaLibrary.discover(context)
    val candidates = ArrayList<SpatialImmersiveVideoConfig>()
    candidates += anchor
    var rejectedCount = plainDiscovery.rejectedCount
    for (packId in candidatePackIds) {
      val pack =
          when (
              val packResolution = if (sharedPackIds.contains(packId)) {
                SharedOfflineImmersiveMediaLibrary.resolve(
                    context = context,
                    packId = packId,
                    keyHex = BuildConfig.OFFLINE_MEDIA_KEY_HEX,
                ) ?: OfflineImmersiveMediaPackResolution.Rejected(
                    "shared-offline-media-unavailable"
                )
              } else {
                if (!PackagedOfflineImmersiveMediaPackImporter.ensureImported(context, packId)) {
                  rejectedCount += 1
                  continue
                }
                OfflineImmersiveMediaPackLoader.resolve(
                    mediaPackRoot = mediaPackRoot,
                    packId = packId,
                    keyHex = BuildConfig.OFFLINE_MEDIA_KEY_HEX,
                    packagedInApk = packagedPackIds.contains(packId),
                )
              }
          ) {
            is OfflineImmersiveMediaPackResolution.Ready -> packResolution.pack
            is OfflineImmersiveMediaPackResolution.Rejected -> {
              rejectedCount += 1
              continue
            }
          }
      val candidateResolution =
          SpatialImmersiveVideoRouteModule.resolveOfflinePack(
              pack = pack,
              autoplay = anchor.autoplay.toString(),
              loop = anchor.loop.toString(),
              radiusMeters = anchor.radiusMeters.toString(),
          )
      val candidate =
          (candidateResolution as? SpatialImmersiveVideoRouteResolution.Ready)?.config
      if (candidate == null ||
          !SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, candidate)) {
        rejectedCount += 1
        continue
      }
      candidates += candidate
    }
    for (item in plainDiscovery.items) {
      val candidateResolution =
          SpatialImmersiveVideoRouteModule.resolvePlainMedia(
              item = item,
              autoplay = anchor.autoplay.toString(),
              loop = anchor.loop.toString(),
              radiusMeters = anchor.radiusMeters.toString(),
          )
      val candidate =
          (candidateResolution as? SpatialImmersiveVideoRouteResolution.Ready)?.config
      if (candidate == null ||
          !SpatialImmersiveVideoSessionPolicy.compatibleWithSession(anchor, candidate)) {
        rejectedCount += 1
        continue
      }
      candidates += candidate
    }
    val result =
        candidates
            .distinctBy { it.offlinePack?.packId ?: it.plainMedia?.mediaId ?: it.path }
            .take(MAX_SESSION_PACKS)
    emitMarker(
        "channel=spatial-immersive-video status=catalog-ready " +
            "itemCount=${result.size} rejectedCount=$rejectedCount " +
            "boundedCatalog=true maxSessionPacks=$MAX_SESSION_PACKS " +
            "packagedCandidateCount=${packagedPackIds.size} " +
            "retainedCandidateCount=${retainedPackIds.size} retainedPackDiscovery=true " +
            "sharedCandidateCount=${sharedPackIds.size} sharedDocumentTreeDiscovery=true " +
            "plainCandidateCount=${plainDiscovery.items.size} " +
            "plainProbedCount=${plainDiscovery.probedCount} " +
            "plainRejectedCount=${plainDiscovery.rejectedCount} " +
            "plainFolderTaxonomy=plain-videos-shape-stereo " +
            "plainClassification=container-metadata-plus-sampled-frame-and-folder-declaration " +
            "projectionClassLocked=false " +
            "encryptedAndPlainStereoCatalog=true shapeAndStereoLayoutMayVary=true " +
            "resolutionMayVary=true rawMediaNamesExposed=false"
    )
    return result
  }

  private fun releasePlayer(reason: String) {
    progressHandler.removeCallbacksAndMessages(null)
    val releasedGeneration = if (player == null) 0L else directDecoderGeneration
    player?.run {
      playWhenReady = false
      clearVideoSurface()
      clearMediaItems()
      release()
    }
    player = null
    playerSurface = null
    if (releasedGeneration > 0L) {
      emitMarker(
          "channel=spatial-immersive-video status=decoder-released " +
              "route=direct-spatial generation=$releasedGeneration " +
              "reason=${activityMarkerToken(reason)} activeDecoderCount=0 " +
              "zeroContributionRequired=${reason.contains("zero-contribution")} " +
              "decoderOverlap=false"
      )
    }
  }

  private fun startPlayer(config: SpatialImmersiveVideoConfig, videoSurface: Surface) {
    if (!directVideoConsumerRequired) {
      emitMarker(
          "channel=spatial-immersive-video status=decoder-start-skipped " +
              "route=direct-spatial reason=zero-contribution " +
              "directVideoConsumerRequired=false activeDecoderCount=0 decoderOverlap=false"
      )
      return
    }
    if (player != null) {
      val surfaceChanged = playerSurface !== videoSurface
      emitMarker(
          "channel=spatial-immersive-video status=surface-reused " +
              "surfaceValid=${videoSurface.isValid} surfaceChanged=$surfaceChanged " +
              "decoderOutputSurfaceReset=$surfaceChanged"
      )
      if (surfaceChanged) {
        player?.setVideoSurface(videoSurface)
        playerSurface = videoSurface
      }
      return
    }
    val mediaUri =
        when {
          config.isEncryptedOfflinePack -> config.offlinePack!!.virtualUri
          config.isGrantedContentUri -> Uri.parse(config.path)
          else -> Uri.fromFile(File(config.path))
        }
    val exoPlayer =
        if (config.offlinePack != null) {
          ExoPlayer.Builder(context)
              .setMediaSourceFactory(
                  DefaultMediaSourceFactory(
                      EncryptedOfflineImmersiveMediaDataSource.Factory(
                          config.offlinePack,
                          emitMarker,
                      )
                  )
              )
              .build()
        } else {
          ExoPlayer.Builder(context).build()
        }
    directDecoderGeneration += 1L
    val generation = directDecoderGeneration
    val suspendedAt = directSuspendedAtRealtimeMs
    val resumeTargetMs =
        if (suspendedAt == null) {
          directResumePositionMs
        } else {
          directResumePositionMs +
              (SystemClock.elapsedRealtime() - suspendedAt).coerceAtLeast(0L)
        }
    pendingDirectResumeTargetMs = resumeTargetMs.takeIf { it > 0L }
    directSuspendedAtRealtimeMs = null
    exoPlayer.apply {
          repeatMode = if (config.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
          addListener(playerListener(config))
          setMediaItem(
              if (config.isEncryptedOfflinePack) {
                MediaItem.Builder().setUri(mediaUri).setMimeType(MimeTypes.VIDEO_MP4).build()
              } else {
                MediaItem.fromUri(mediaUri)
              }
          )
          setVideoSurface(videoSurface)
          playWhenReady = config.autoplay && pendingDirectResumeTargetMs == null
          prepare()
        }
    playerSurface = videoSurface
    player = exoPlayer
    resumeAfterPause = config.autoplay
    emitMarker(
        "channel=spatial-immersive-video status=player-preparing " +
            "decoderRoute=direct-spatial decoderGeneration=$generation " +
            "activeDecoderCount=1 decoderOverlap=false " +
            "${config.markerFields()} surfaceValid=${videoSurface.isValid} " +
            "source=${when {
              config.isEncryptedOfflinePack -> "encrypted-offline-media-pack"
              config.isSharedPlainVideo -> "shared-plain-video"
              config.isGrantedContentUri -> "granted-media-content-uri"
              else -> "app-scoped-local-file"
            }}"
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
          if (playbackState == Player.STATE_READY) {
            val resumeTargetMs = pendingDirectResumeTargetMs
            val currentPlayer = player
            if (resumeTargetMs != null && currentPlayer != null) {
              val durationMs = currentPlayer.duration
              val normalizedTargetMs =
                  if (config.loop && durationMs > 0L) {
                    resumeTargetMs % durationMs
                  } else if (durationMs > 0L) {
                    resumeTargetMs.coerceAtMost(durationMs)
                  } else {
                    resumeTargetMs
                  }
              pendingDirectResumeTargetMs = null
              directResumePositionMs = 0L
              currentPlayer.seekTo(normalizedTargetMs)
              if (config.autoplay && resumeAfterPause) {
                currentPlayer.play()
              }
              emitMarker(
                  "channel=spatial-immersive-video status=decoder-resumed " +
                      "route=direct-spatial resumeTargetMs=$normalizedTargetMs " +
                      "hiddenClockAdvanced=true activeDecoderCount=1 decoderOverlap=false"
              )
            }
          }
        }

        override fun onRenderedFirstFrame() {
          val firstFramePositionMs = player?.currentPosition ?: 0L
          emitMarker(
              "channel=spatial-immersive-video status=first-frame-rendered " +
                  "${config.markerFields()}"
          )
          dispatchPendingVideoLoadRecenter(
              config = config,
              readyRoute = "direct-spatial-first-frame",
          )
          beginIncomingFade(config)
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
          if (transitionTargetIndex != null && config.path == activeConfig?.path) {
            applyDirectLayerOpacity(1.0f)
            completeSelectionTransition(transitionGeneration, "incoming-player-error")
          }
          emitMarker(
              "channel=spatial-immersive-video status=playback-error " +
                  "errorCode=${error.errorCode} error=${activityMarkerToken(error.javaClass.simpleName)} " +
                  "message=${activityMarkerToken(error.message ?: "none")}"
          )
        }
      }

  private fun dispatchPendingVideoLoadRecenter(
      config: SpatialImmersiveVideoConfig,
      readyRoute: String,
  ): Boolean {
    val request = autoRecenterGate.consume(config.path, readyRoute) ?: return false
    val inputSource = "new-video-load"
    val applied = recenterAfterNewVideoLoad(inputSource, request.detail)
    emitMarker(
        "channel=spatial-immersive-video status=new-video-load-recenter " +
            "source=${activityMarkerToken(inputSource)} " +
            "selectionSource=${activityMarkerToken(request.selectionSource)} " +
            "readyRoute=${activityMarkerToken(readyRoute)} applied=$applied " +
            "sameViewerPoseAuthority=true exactlyOncePerChangedLoad=true " +
            "activityRestarted=false ${config.markerFields()}"
    )
    return applied
  }

  private fun blackBackdropMediaPanelSettings(
      config: SpatialImmersiveVideoConfig,
      registeredMode: SpatialImmersiveVideoPresentationMode,
  ): MediaPanelSettings {
    val presentation =
        SpatialImmersiveVideoSessionPolicy.directPanelPresentation(config, registeredMode)
    val shape =
        when (SpatialImmersiveVideoBlackBackingPolicy.shape(config, registeredMode)) {
            SpatialImmersiveVideoBlackBackingShape.Quad -> quadShapeOptions(presentation)
            SpatialImmersiveVideoBlackBackingShape.Equirect360 ->
                Equirect360ShapeOptions(
                    radius = SpatialImmersiveVideoBlackBackingPolicy.radiusMeters(config)
                )
        }
    return MediaPanelSettings(
        shape = shape,
        display = PixelDisplayOptions(width = BLACK_BACKING_DISPLAY_SIZE_PX, height = BLACK_BACKING_DISPLAY_SIZE_PX),
        rendering =
            MediaPanelRenderOptions(
                stereoMode = StereoMode.None,
                zIndex = DIRECT_VIDEO_BLACK_BACKING_Z_INDEX,
            ),
        style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueVideoBlack),
        input = PanelInputOptions(0),
    )
  }

  private fun mediaPanelSettings(
      config: SpatialImmersiveVideoConfig,
      registeredMode: SpatialImmersiveVideoPresentationMode,
  ): MediaPanelSettings {
    val presentation =
        SpatialImmersiveVideoSessionPolicy.directPanelPresentation(config, registeredMode)
    return MediaPanelSettings(
          shape =
              when (presentation.shape) {
                SpatialImmersiveVideoCarrierShape.LegacyQuad,
                SpatialImmersiveVideoCarrierShape.WorldQuad -> quadShapeOptions(presentation)
                SpatialImmersiveVideoCarrierShape.Equirect180 ->
                    Equirect180ShapeOptions(radius = config.radiusMeters)
                SpatialImmersiveVideoCarrierShape.Equirect360 ->
                    Equirect360ShapeOptions(radius = config.radiusMeters)
              },
          display =
              PixelDisplayOptions(
                  width = presentation.displayWidthPx,
                  height = presentation.displayHeightPx,
              ),
          rendering =
              MediaPanelRenderOptions(
                  stereoMode = presentation.stereoMode,
                  zIndex = DIRECT_VIDEO_BACKGROUND_Z_INDEX,
              ),
          input = PanelInputOptions(0),
      )
  }

  private fun quadShapeOptions(
      presentation: SpatialImmersiveVideoDirectPanelPresentation,
  ): QuadShapeOptions {
    val quadGeometry = requireNotNull(presentation.quadGeometry)
    return QuadShapeOptions(
        width = quadGeometry.widthMeters,
        height = quadGeometry.heightMeters,
    )
  }

  companion object {
    private const val DIRECT_VIDEO_BACKGROUND_Z_INDEX = -40
    private const val DIRECT_VIDEO_BLACK_BACKING_Z_INDEX = -41
    private const val BLACK_BACKING_DISPLAY_SIZE_PX = 16
    private const val MAX_SESSION_PACKS = 32
    private const val DIRECT_VIDEO_FADE_DURATION_MS = 300L
    private const val DIRECT_VIDEO_FADE_FRAME_INTERVAL_MS = 16L
    private const val DIRECT_VIDEO_LAYER_READY_MAX_ATTEMPTS = 60
    private const val DIRECT_VIDEO_FIRST_FRAME_TIMEOUT_MS = 8_000L
    private const val EXTRA_OFFLINE_PACK_ID =
        "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_OFFLINE_PACK_ID"

    fun resolveFromIntent(
        context: Context,
        intent: Intent,
        sourceReadable: ((String) -> Boolean)? = null,
    ): SpatialImmersiveVideoRouteResolution {
      val intentEnabledProvided =
          intent.hasExtra(SpatialImmersiveVideoRouteModule.EXTRA_ENABLED)
      val effectiveEnabled =
          if (intentEnabledProvided) {
            intent.getBooleanExtra(SpatialImmersiveVideoRouteModule.EXTRA_ENABLED, false)
          } else {
            BuildConfig.IMMERSIVE_VIDEO_DEFAULT_ENABLED
          }
      val values =
          SpatialImmersiveVideoLaunchValues(
              enabled =
                  if (intentEnabledProvided || BuildConfig.IMMERSIVE_VIDEO_DEFAULT_ENABLED) {
                    effectiveEnabled.toString()
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
        val passivePlainMediaSeed =
            if (!intentEnabledProvided &&
                !BuildConfig.IMMERSIVE_VIDEO_DEFAULT_ENABLED &&
                values.path.isNullOrBlank() &&
                !intent.hasExtra(EXTRA_OFFLINE_PACK_ID)) {
              SharedPlainImmersiveMediaLibrary.discover(context).items.firstOrNull()
            } else {
              null
            }
        if (passivePlainMediaSeed != null) {
          return SpatialImmersiveVideoRouteModule.resolveAvailablePlainMedia(
              item = passivePlainMediaSeed,
              autoplay = values.autoplay,
              loop = values.loop,
              radiusMeters = values.radiusMeters,
          )
        }
        return SpatialImmersiveVideoRouteModule.resolve(
            values = values,
            allowedMediaRoot = "",
            sourceReadable = sourceReadable ?: { File(it).isFile },
        )
      }

      val requestedPath = values.path?.trim().orEmpty()
      val explicitOfflinePackId = intent.hasExtra(EXTRA_OFFLINE_PACK_ID)
      val offlinePackId =
          intent.getStringExtra(EXTRA_OFFLINE_PACK_ID)?.trim().orEmpty().ifBlank {
            if (requestedPath.isEmpty()) {
              BuildConfig.IMMERSIVE_VIDEO_DEFAULT_OFFLINE_PACK_ID
            } else {
              ""
            }
          }
      if (offlinePackId.isNotEmpty()) {
        if (!values.path.isNullOrBlank()) {
          return SpatialImmersiveVideoRouteResolution.Rejected(
              "offline-pack-source-ambiguous"
          )
        }
        val mediaPackRoot = File(context.filesDir, "offline-media-packs")
        val sharedPackIds = SharedOfflineImmersiveMediaLibrary.packIds(context)
        val packagedImportReady =
            if (!sharedPackIds.contains(offlinePackId) &&
                BuildConfig.OFFLINE_MEDIA_PACKAGED_ASSETS) {
              PackagedOfflineImmersiveMediaPackImporter.ensureImported(
                  context,
                  offlinePackId,
              )
            } else {
              false
            }
        val packResolution =
                if (sharedPackIds.contains(offlinePackId)) {
                  SharedOfflineImmersiveMediaLibrary.resolve(
                      context = context,
                      packId = offlinePackId,
                      keyHex = BuildConfig.OFFLINE_MEDIA_KEY_HEX,
                  ) ?: OfflineImmersiveMediaPackResolution.Rejected(
                      "shared-offline-media-unavailable"
                  )
                } else {
                  OfflineImmersiveMediaPackLoader.resolve(
                      mediaPackRoot = mediaPackRoot,
                      packId = offlinePackId,
                      keyHex = BuildConfig.OFFLINE_MEDIA_KEY_HEX,
                      packagedInApk = packagedImportReady,
                  )
                }
        return when (packResolution) {
          is OfflineImmersiveMediaPackResolution.Rejected -> {
            val fallback =
                if (!explicitOfflinePackId) {
                  SharedPlainImmersiveMediaLibrary.discover(context).items.firstOrNull()
                } else {
                  null
                }
            if (fallback == null) {
              SpatialImmersiveVideoRouteResolution.Rejected(packResolution.reason)
            } else {
              SpatialImmersiveVideoRouteModule.resolvePlainMedia(
                  item = fallback,
                  autoplay = values.autoplay,
                  loop = values.loop,
                  radiusMeters = values.radiusMeters,
              )
            }
          }
          is OfflineImmersiveMediaPackResolution.Ready ->
              SpatialImmersiveVideoRouteModule.resolveOfflinePack(
                  pack = packResolution.pack,
                  autoplay = values.autoplay,
                  loop = values.loop,
                  radiusMeters = values.radiusMeters,
              )
        }
      }

      val externalFilesRoot =
          context.getExternalFilesDir(null)?.canonicalFile
              ?: return SpatialImmersiveVideoRouteResolution.Rejected(
                  "app-external-files-root-unavailable"
              )
      val allowedMediaRoot = File(externalFilesRoot, "immersive-video").canonicalFile
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

internal enum class SpatialImmersiveVideoBlackBackingShape(val token: String) {
  Quad("quad"),
  Equirect360("equirect-360"),
}

/** Geometry policy for opaque black pixels behind direct video carriers. */
internal object SpatialImmersiveVideoBlackBackingPolicy {
  private const val RADIAL_OFFSET_METERS = 0.025f
  private const val MAX_RADIUS_METERS = 100.0f

  fun shape(
      config: SpatialImmersiveVideoConfig,
      presentationMode: SpatialImmersiveVideoPresentationMode,
  ): SpatialImmersiveVideoBlackBackingShape =
      if (
          presentationMode == SpatialImmersiveVideoPresentationMode.HeadFixedBorder ||
              config.shape == SpatialImmersiveVideoShape.Flat
      ) {
        SpatialImmersiveVideoBlackBackingShape.Quad
      } else {
        // A full shell blacks the uncovered rear hemisphere of 180-degree content too.
        SpatialImmersiveVideoBlackBackingShape.Equirect360
      }

  fun shapeToken(
      config: SpatialImmersiveVideoConfig?,
      presentationMode: SpatialImmersiveVideoPresentationMode,
  ): String = config?.let { shape(it, presentationMode).token } ?: "unavailable"

  fun radiusMeters(config: SpatialImmersiveVideoConfig): Float =
      (config.radiusMeters + RADIAL_OFFSET_METERS).coerceAtMost(MAX_RADIUS_METERS)
}
