package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal val LayerPanelBackground = Color(0xFF141820)
internal val LayerPanelSurface = Color(0xFF202634)
internal val LayerPanelSurfaceAlt = Color(0xFF293142)
internal val LayerPanelInk = Color(0xFFF4F7FA)
internal val LayerPanelMuted = Color(0xFFAAB3C2)
internal val LayerPanelAccent = Color(0xFF63D2FF)
internal val LayerPanelWarm = Color(0xFFFFC857)
internal val LayerPanelBorder = Color(0xFF3B465A)

internal object SpatialVideoCadencePanelBridge {
  private var resolve: () -> SpatialVideoCadenceMode = { SpatialVideoCadenceMode.Fps30 }
  private var update: (SpatialVideoCadenceMode) -> SpatialVideoCadenceMode = { it }

  fun bind(
      resolve: () -> SpatialVideoCadenceMode,
      update: (SpatialVideoCadenceMode) -> SpatialVideoCadenceMode,
  ) {
    this.resolve = resolve
    this.update = update
  }

  fun clear() {
    resolve = { SpatialVideoCadenceMode.Fps30 }
    update = { it }
  }

  fun current(): SpatialVideoCadenceMode = resolve()

  fun select(mode: SpatialVideoCadenceMode): SpatialVideoCadenceMode = update(mode)
}

private enum class PrivateLayerPanelPage(
    val title: String,
    val subtitle: String,
) {
  Home("Settings", "Choose a topic"),
  Layers("Layers & projection", "Visibility, rendering layer, and projection size"),
  Video("Background", "Background style, video playback, presentation, and active video"),
  Regions("Three-region effect", "Core, dynamic buffer, and outer-region behavior"),
  Image("Image processing", "Depth warp, RGB transform, sampling, and guide blur"),
  Depth("Depth alignment", "Depth source and per-eye fine tuning"),
  Profiles("Profiles", "Save, restore, and exchange complete tuning setups"),
  Playlists("Playlists", "Sequence saved profiles with timed looping playback"),
  ExternalControl("External control", "Connection Hub listener and WebSocket availability"),
}

private enum class RegionSettingsTab(val label: String) {
  Buffer("Buffer"),
  Effects("Effects"),
  Stretch("Stretch"),
  Transitions("Transitions"),
  Outer("Outer"),
  Diagnostics("Diagnostics"),
}

@Composable
internal fun PrivateLayerControlPanel(
    layerOverride: Float,
    projectionPanelEnabled: Boolean,
    projectionScale: Float,
    projectionScaleRange: ClosedFloatingPointRange<Float>,
    depthLayerPolicy: Int,
    depthAlignment: PrivateLayerDepthAlignment,
    guideProcessing: PrivateLayerGuideProcessing,
    rgbChannelTransform: RgbChannelTransform,
    projectionSurfaceDisplacement: ProjectionSurfaceDisplacement,
    projectionSurfaceTiling: ProjectionSurfaceTiling,
    projectionInnerAlpha: ProjectionInnerAlpha,
    videoSession: () -> SpatialImmersiveVideoSessionSnapshot,
    sharedMediaLibrary: () -> SharedOfflineImmersiveMediaLibrarySnapshot,
    connectionHubStatus: () -> ConnectionHubWearerControlSnapshot,
    startConnectionHub: () -> ConnectionHubWearerControlSnapshot,
    stopConnectionHub: () -> ConnectionHubWearerControlSnapshot,
    environmentDepthUnavailableWarning: () -> String?,
    environmentDepthRecoveryPolicy: () -> SpatialEnvironmentDepthRecoveryPolicy,
    updateEnvironmentDepthRecoveryPolicy:
        (SpatialEnvironmentDepthRecoveryPolicy, String) -> SpatialEnvironmentDepthRecoveryPolicy,
    setLayerOverride: (Float, String) -> Float,
    setProjectionPanelEnabled: (Boolean, String) -> Boolean,
    setVideoPlaybackEnabled: (Boolean) -> SpatialImmersiveVideoSessionSnapshot,
    updateProjectionScale: (Float, String) -> Float,
    updateDepthLayerPolicy: (Int, String) -> Int,
    updateDepthAlignment: (PrivateLayerDepthAlignment, String) -> PrivateLayerDepthAlignment,
    updateGuideProcessing:
        (PrivateLayerGuideProcessing, String) -> PrivateLayerGuideProcessing,
    updateRgbChannelTransform:
        (RgbChannelTransform, String) -> RgbChannelTransform,
    updateProjectionSurfaceDisplacement:
        (ProjectionSurfaceDisplacement, String) -> ProjectionSurfaceDisplacement,
    updateProjectionSurfaceTiling:
        (ProjectionSurfaceTiling, String) -> ProjectionSurfaceTiling,
    updateProjectionInnerAlpha:
        (ProjectionInnerAlpha, String) -> ProjectionInnerAlpha,
    selectPreviousVideo: () -> SpatialImmersiveVideoSessionSnapshot,
    selectNextVideo: () -> SpatialImmersiveVideoSessionSnapshot,
    setVideoPresentationMode:
        (SpatialImmersiveVideoPresentationMode) -> SpatialImmersiveVideoSessionSnapshot,
    setBackgroundMode: (SpatialBackgroundMode) -> SpatialImmersiveVideoSessionSnapshot,
    chooseSharedMediaFolder: () -> Unit,
    profileLibrary: () -> SpatialCameraPanelProfileLibrarySnapshot,
    saveStoredProfile: (String) -> SpatialCameraPanelProfileOperationResult,
    loadStoredProfile: (String) -> SpatialCameraPanelProfileOperationResult,
    deleteStoredProfile: (String) -> SpatialCameraPanelProfileOperationResult,
    importStagedProfiles: () -> SpatialCameraPanelProfileOperationResult,
    panelExtension: SpatialPrivatePanelExtension?,
    closePanel: () -> Unit,
) {
  var localLayerOverride by remember(layerOverride) { mutableStateOf(layerOverride) }
  var localProjectionPanelEnabled by
      remember(projectionPanelEnabled) { mutableStateOf(projectionPanelEnabled) }
  var localProjectionScale by remember(projectionScale) { mutableStateOf(projectionScale) }
  var localDepthLayerPolicy by remember(depthLayerPolicy) { mutableStateOf(depthLayerPolicy) }
  var localDepthAlignment by remember(depthAlignment) { mutableStateOf(depthAlignment) }
  var localGuideProcessing by remember(guideProcessing) { mutableStateOf(guideProcessing) }
  var localRgbChannelTransform by
      remember(rgbChannelTransform) { mutableStateOf(rgbChannelTransform) }
  var localProjectionSurfaceDisplacement by
      remember(projectionSurfaceDisplacement) {
        mutableStateOf(projectionSurfaceDisplacement)
      }
  var localProjectionSurfaceTiling by
      remember(projectionSurfaceTiling) { mutableStateOf(projectionSurfaceTiling) }
  var localProjectionInnerAlpha by
      remember(projectionInnerAlpha) { mutableStateOf(projectionInnerAlpha) }
  var currentPage by remember { mutableStateOf(PrivateLayerPanelPage.Home) }
  var currentRegionTab by remember { mutableStateOf(RegionSettingsTab.Buffer) }
  var localVideoSession by remember { mutableStateOf(videoSession()) }
  var localVideoCadenceMode by remember { mutableStateOf(SpatialVideoCadencePanelBridge.current()) }
  var localSharedMediaLibrary by remember { mutableStateOf(sharedMediaLibrary()) }
  var localConnectionHub by remember { mutableStateOf(connectionHubStatus()) }
  var localEnvironmentDepthUnavailableWarning by
      remember { mutableStateOf(environmentDepthUnavailableWarning()) }
  var localEnvironmentDepthRecoveryPolicy by
      remember { mutableStateOf(environmentDepthRecoveryPolicy()) }
  var localProfileLibrary by remember { mutableStateOf(profileLibrary()) }
  var localProfileName by remember { mutableStateOf("") }
  var profileStatus by remember { mutableStateOf(localProfileLibrary.loadStatus) }
  var selectedHelp by remember { mutableStateOf<PrivateLayerControlHelpEntry?>(null) }
  fun adoptProfileControls(controls: SpatialCameraPanelControlSnapshot) {
    localProjectionPanelEnabled = controls.projectionPanelEnabled
    localLayerOverride = controls.layerOverride
    localProjectionScale = controls.projectionScale
    localDepthLayerPolicy = controls.depthLayerPolicy
    localDepthAlignment = controls.depthAlignment
    localGuideProcessing = controls.guideProcessing
    localRgbChannelTransform = controls.rgbChannelTransform
    localProjectionSurfaceDisplacement = controls.projectionSurfaceDisplacement
    localProjectionSurfaceTiling = controls.projectionSurfaceTiling
    localProjectionInnerAlpha = controls.projectionInnerAlpha
    localVideoSession = videoSession()
  }
  LaunchedEffect(Unit) {
    while (true) {
      delay(500L)
      val latestVideoSession = videoSession()
      if (latestVideoSession != localVideoSession) {
        localVideoSession = latestVideoSession
      }
      val latestVideoCadenceMode = SpatialVideoCadencePanelBridge.current()
      if (latestVideoCadenceMode != localVideoCadenceMode) {
        localVideoCadenceMode = latestVideoCadenceMode
      }
      val latestSharedMediaLibrary = sharedMediaLibrary()
      if (latestSharedMediaLibrary != localSharedMediaLibrary) {
        localSharedMediaLibrary = latestSharedMediaLibrary
      }
      val latestConnectionHub = connectionHubStatus()
      if (latestConnectionHub != localConnectionHub) {
        localConnectionHub = latestConnectionHub
      }
      val latestEnvironmentDepthUnavailableWarning = environmentDepthUnavailableWarning()
      if (latestEnvironmentDepthUnavailableWarning !=
          localEnvironmentDepthUnavailableWarning) {
        localEnvironmentDepthUnavailableWarning = latestEnvironmentDepthUnavailableWarning
      }
      val latestEnvironmentDepthRecoveryPolicy = environmentDepthRecoveryPolicy()
      if (latestEnvironmentDepthRecoveryPolicy != localEnvironmentDepthRecoveryPolicy) {
        localEnvironmentDepthRecoveryPolicy = latestEnvironmentDepthRecoveryPolicy
      }
    }
  }
  val localZoneCompositor = PrivateLayerZoneCompositorPanelBridge.configuration
  CompositionLocalProvider(
      LocalPrivateLayerControlHelpRequest provides { label ->
        selectedHelp = PrivateLayerControlHelp.forLabel(label)
      }
  ) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = LayerPanelBackground,
        contentColor = LayerPanelInk,
    ) {
      Column(modifier = Modifier.fillMaxSize().background(LayerPanelBackground)) {
        PersistentPanelHeader(
            currentPage = currentPage,
            panelExtension = panelExtension,
            selectedHelp = selectedHelp,
            onSelectPage = { currentPage = it },
            onDismissHelp = { selectedHelp = null },
            closePanel = closePanel,
        )
        Column(
            modifier =
                Modifier
                    .weight(1.0f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
      if (currentPage == PrivateLayerPanelPage.Home) {
        PreviewBand()
        PanelTopicMenu(
            projectionSummary =
                "Custom projection ${if (localProjectionPanelEnabled) "on" else "off"} · " +
                    PrivateLayerControls.labelForOverride(localLayerOverride),
            videoSummary =
                if (localVideoSession.available) {
                  "${if (localVideoSession.playbackEnabled) "On" else "Off"} · " +
                      "${localVideoSession.activeOrdinal} of ${localVideoSession.itemCount}"
                } else {
                  "No packaged video available"
                },
            regionSummary =
                "${PrivateLayerZoneCompositorControls.presetToken(localZoneCompositor)} · " +
                    PrivateLayerZoneCompositorControls.coverageToken(
                        localZoneCompositor.coverageMode
                    ),
            imageSummary =
                "${PrivateLayerControls.guideProcessingPresetToken(localGuideProcessing)} · " +
                    RgbChannelTransformControls.modeToken(localRgbChannelTransform.mode),
            depthSummary =
                "${PrivateLayerControls.labelForDepthLayerPolicy(localDepthLayerPolicy)} · " +
                    (if (localDepthAlignment.metadataAutoAlign) "auto align" else "manual"),
            profileSummary = "${localProfileLibrary.profiles.size} saved · JSON PC transfer",
            playlistSummary = panelExtension?.homeSummary(),
            externalControlSummary = localConnectionHub.summary,
            onSelect = { currentPage = it },
        )
      }
      if (currentPage == PrivateLayerPanelPage.Layers) {
      Section("Custom Projection") {
        Text(
            if (localProjectionPanelEnabled) {
              "Custom camera/effect projection: On. The independent 360 video layer can stay on or off."
            } else {
              "Custom camera/effect projection: Off. The 360 video layer and system passthrough are retained."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
              localProjectionPanelEnabled =
                  setProjectionPanelEnabled(
                      !localProjectionPanelEnabled,
                      "private-layer-control-panel-projection-toggle",
                  )
            },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (localProjectionPanelEnabled) LayerPanelWarm else LayerPanelAccent,
                    contentColor = Color(0xFF04111A),
                ),
        ) {
          Text(
              if (localProjectionPanelEnabled) {
                "Turn custom projection off"
              } else {
                "Turn custom projection on"
              }
          )
        }
      }
      Section("Active Rendering") {
        LayerButtonGrid(
            selectedLayerOverride = localLayerOverride,
            onSelect = { override ->
              localLayerOverride = setLayerOverride(override, "private-layer-control-panel")
            },
        )
      }
      }

      if (currentPage == PrivateLayerPanelPage.Video) {
        Section("Background") {
          HelpLabel("Background")
          Text(
              "Choose what fills the scene behind the video layer. This setting is independent of video playback.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          ) {
            ChoiceButton(
                label = "Black",
                selected = localVideoSession.backgroundMode == SpatialBackgroundMode.Black,
            ) {
              localVideoSession = setBackgroundMode(SpatialBackgroundMode.Black)
            }
            ChoiceButton(
                label = "Passthrough",
                selected = localVideoSession.backgroundMode == SpatialBackgroundMode.Passthrough,
            ) {
              localVideoSession = setBackgroundMode(SpatialBackgroundMode.Passthrough)
            }
            ChoiceButton(
                label = "LUT passthrough",
                selected =
                    localVideoSession.backgroundMode == SpatialBackgroundMode.LutPassthrough,
            ) {
              localVideoSession = setBackgroundMode(SpatialBackgroundMode.LutPassthrough)
            }
          }
        }
        Section("Peer stereo") {
          HelpLabel("Quest-to-Quest stereo")
          Text(
              "A receiver-first accepted media session can feed one packed stereo stream into the same custom projection. LAN, Wi-Fi Direct, and authenticated TLS relay routes share the decoder and render path.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          Text(
              SpatialPeerStereoStatus.snapshotSummary(),
              style = MaterialTheme.typography.bodyMedium,
              color = LayerPanelAccent,
          )
          Text(
              "Targets and relay credentials are run-owned inputs and are never saved in profiles, playlists, Hub, or Fleet. Broker route endpoints remain private run evidence and are redacted from this UI and log markers.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
        }
        Section("Shared Media") {
          HelpLabel("Shared media folder")
          Text(
              when {
                localSharedMediaLibrary.accessible ->
                    "${localSharedMediaLibrary.folderLabel}: " +
                        "${localSharedMediaLibrary.packCount} encrypted pack(s), " +
                        "${localSharedMediaLibrary.plainVideoCount} validated plain video(s)."
                localSharedMediaLibrary.configured ->
                    "The previously selected folder is no longer readable. Select it again."
                else ->
                    "Select RustySpatialMedia once. Encrypted packs and plain videos stay outside the app and remain available to future thin APK updates."
              },
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          Text(
              "Plain videos: plain-videos/<flat|equirect-180|equirect-360>/<mono|side-by-side-left-right|top-bottom>/. The app verifies container dimensions and a sampled frame before listing them.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          if (localSharedMediaLibrary.configured && localSharedMediaLibrary.accessible) {
            Text(
                when {
                  localSharedMediaLibrary.plainVideoTaxonomyReady ->
                      "The standard plain-video folders are ready for File Manager uploads."
                  localSharedMediaLibrary.writable ->
                      "The standard plain-video folders could not be created. Select the folder again or create the documented taxonomy manually."
                  else ->
                      "This provider granted read-only access. Create the documented taxonomy manually before uploading videos."
                },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (localSharedMediaLibrary.plainVideoTaxonomyReady) {
                      LayerPanelAccent
                    } else {
                      LayerPanelWarm
                    },
            )
          }
          if (localSharedMediaLibrary.rejectedPlainVideoCount > 0) {
            Text(
                "${localSharedMediaLibrary.rejectedPlainVideoCount} plain video(s) were rejected because their declared type could not be validated.",
                style = MaterialTheme.typography.bodySmall,
                color = LayerPanelWarm,
            )
          }
          Button(
              modifier = Modifier.fillMaxWidth().height(52.dp),
              onClick = chooseSharedMediaFolder,
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = LayerPanelSurfaceAlt,
                      contentColor = LayerPanelInk,
                  ),
          ) {
            Text(
                if (localSharedMediaLibrary.configured) {
                  "Choose a different media folder"
                } else {
                  "Choose shared media folder"
                }
            )
          }
        }
        Section("Video layer") {
          HelpLabel("Video playback")
          Text(
              if (localVideoSession.available) {
                "Active video ${localVideoSession.activeOrdinal} of ${localVideoSession.itemCount}: " +
                    (localVideoSession.activeMediaLabel ?: "Unknown type")
              } else {
                "No compatible offline video is available."
              },
              style = MaterialTheme.typography.bodyMedium,
              color = LayerPanelMuted,
          )
          Button(
              modifier = Modifier.fillMaxWidth().height(52.dp),
              enabled = localVideoSession.available,
              onClick = {
                localVideoSession =
                    setVideoPlaybackEnabled(!localVideoSession.playbackEnabled)
              },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor =
                          if (localVideoSession.playbackEnabled) {
                            LayerPanelWarm
                          } else {
                            LayerPanelAccent
                          },
                      contentColor = Color(0xFF04111A),
                  ),
          ) {
            Text(
                if (localVideoSession.playbackEnabled) {
                  "Turn video layer off"
                } else {
                  "Turn video layer on"
                }
            )
          }
          Text(
              if (localVideoSession.customProjectionCompatible) {
                "The video uses its ideal Spatial SDK surface behind the planar custom camera projection."
              } else {
                "The selected video is using its ideal Spatial SDK projection surface."
              },
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          HelpLabel("Playback cadence")
          Text(
              "Choose the file-decoder cadence before MediaCodec output reaches the Surface. Source leaves that gate disabled; the native 90 fps safety fallback remains active.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                label = "30 fps",
                selected = localVideoCadenceMode == SpatialVideoCadenceMode.Fps30,
            ) {
              localVideoCadenceMode =
                  SpatialVideoCadencePanelBridge.select(SpatialVideoCadenceMode.Fps30)
            }
            ChoiceButton(
                label = "60 fps",
                selected = localVideoCadenceMode == SpatialVideoCadenceMode.Fps60,
            ) {
              localVideoCadenceMode =
                  SpatialVideoCadencePanelBridge.select(SpatialVideoCadenceMode.Fps60)
            }
            ChoiceButton(
                label = "Source",
                selected = localVideoCadenceMode == SpatialVideoCadenceMode.Source,
            ) {
              localVideoCadenceMode =
                  SpatialVideoCadencePanelBridge.select(SpatialVideoCadenceMode.Source)
            }
          }
          Text(
              if (localVideoSession.presentationMode ==
                  SpatialImmersiveVideoPresentationMode.WorldAnchored) {
                "World anchored: immersive video stays fixed in the scene as you turn your head."
              } else {
                "Head-fixed border: a flat video background follows your view behind the camera projection."
              },
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          HelpLabel("Video presentation")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                label = "World anchored",
                selected =
                    localVideoSession.presentationMode ==
                        SpatialImmersiveVideoPresentationMode.WorldAnchored,
            ) {
              localVideoSession =
                  setVideoPresentationMode(
                      SpatialImmersiveVideoPresentationMode.WorldAnchored
                  )
            }
            ChoiceButton(
                label = "Head-fixed border",
                selected =
                    localVideoSession.presentationMode ==
                        SpatialImmersiveVideoPresentationMode.HeadFixedBorder,
            ) {
              localVideoSession =
                  setVideoPresentationMode(
                      SpatialImmersiveVideoPresentationMode.HeadFixedBorder
                  )
            }
          }
          HelpLabel("Active video")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            Button(
                modifier = Modifier.weight(1.0f),
                enabled = localVideoSession.itemCount > 1,
                onClick = { localVideoSession = selectPreviousVideo() },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = LayerPanelSurfaceAlt,
                        contentColor = LayerPanelInk,
                    ),
            ) {
              Text("Previous")
            }
            Button(
                modifier = Modifier.weight(1.0f),
                enabled = localVideoSession.itemCount > 1,
                onClick = { localVideoSession = selectNextVideo() },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = LayerPanelAccent,
                        contentColor = Color(0xFF04111A),
                    ),
            ) {
              Text("Next")
            }
          }
        }
      }

      if (currentPage == PrivateLayerPanelPage.ExternalControl) {
        Section("Connection Hub") {
          Text(
              when {
                !localConnectionHub.available ->
                    "The same-signer Connection Hub companion is unavailable."
                localConnectionHub.listenerEnabled ->
                    "On — WebSocket control is available to paired clients."
                else -> "Off — no Hub listener or WebSocket is exposed."
              },
              style = MaterialTheme.typography.bodyMedium,
              color =
                  if (localConnectionHub.listenerEnabled) LayerPanelAccent else LayerPanelMuted,
          )
          Text(
              "Effective state: ${localConnectionHub.desiredConnectionState} · " +
                  "controllers: ${localConnectionHub.activeControllerSessions} · " +
                  "transport: ${localConnectionHub.transportClassification}",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            Button(
                modifier = Modifier.weight(1.0f),
                enabled = !localConnectionHub.listenerEnabled,
                onClick = { localConnectionHub = startConnectionHub() },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = LayerPanelAccent,
                        contentColor = Color(0xFF04111A),
                    ),
            ) {
              Text("Start Hub")
            }
            Button(
                modifier = Modifier.weight(1.0f),
                enabled =
                    localConnectionHub.listenerEnabled ||
                        localConnectionHub.desiredConnectionState == "running",
                onClick = { localConnectionHub = stopConnectionHub() },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = LayerPanelWarm,
                        contentColor = Color(0xFF04111A),
                    ),
            ) {
              Text("Stop Hub")
            }
            Button(
                modifier = Modifier.weight(1.0f),
                onClick = { localConnectionHub = connectionHubStatus() },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = LayerPanelSurfaceAlt,
                        contentColor = LayerPanelInk,
                    ),
            ) {
              Text("Refresh")
            }
          }
          Text(
              "Stopping the Hub closes its network listener and WebSocket work only. Headset controller input, panel reopening, projection, video, profiles, and playlists remain active.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          Text(
              "Current transport reports confidentiality=${localConnectionHub.confidentiality} and productionEligible=${localConnectionHub.productionEligible}.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
        }
      }

      if (currentPage == PrivateLayerPanelPage.Layers) {
      Section("Projection Area") {
        HelpLabel("Projection scale")
        Text(
            "Scale ${"%.2f".format(localProjectionScale)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Slider(
            value = localProjectionScale,
            onValueChange = { value ->
              localProjectionScale = updateProjectionScale(value, "private-layer-control-panel-scale")
            },
            valueRange = projectionScaleRange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OperatorButton("0.75x") {
            localProjectionScale =
                updateProjectionScale(0.75f, "private-layer-control-panel-scale-preset")
          }
          OperatorButton("1.00x") {
            localProjectionScale =
                updateProjectionScale(1.0f, "private-layer-control-panel-scale-preset")
          }
          OperatorButton("1.25x") {
            localProjectionScale =
                updateProjectionScale(1.25f, "private-layer-control-panel-scale-preset")
          }
        }
      }
      }

      if (currentPage == PrivateLayerPanelPage.Image) {
      Section("Projection Depth") {
        Text(
            "Adds a guide-driven depth/parallax warp inside the existing stereo projection. The Spatial carrier stays planar, so video switching and the peripheral compositor remain active.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Preset: ${ProjectionSurfaceDisplacementControls.presetToken(localProjectionSurfaceDisplacement)} · amplitude ${"%.2f".format(localProjectionSurfaceDisplacement.maxDisplacementMeters)} m",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              "Off",
              !localProjectionSurfaceDisplacement.enabled,
          ) {
            localProjectionSurfaceDisplacement =
                updateProjectionSurfaceDisplacement(
                    ProjectionSurfaceDisplacementControls.off,
                    "private-layer-projection-depth-off",
                )
          }
          ChoiceButton(
              "Gentle",
              localProjectionSurfaceDisplacement.enabled &&
                  localProjectionSurfaceDisplacement.maxDisplacementMeters < 0.12f,
          ) {
            localProjectionSurfaceDisplacement =
                updateProjectionSurfaceDisplacement(
                    ProjectionSurfaceDisplacementControls.gentle,
                    "private-layer-projection-depth-gentle",
                )
          }
          ChoiceButton(
              "Deep",
              localProjectionSurfaceDisplacement.enabled &&
                  localProjectionSurfaceDisplacement.maxDisplacementMeters >= 0.12f,
          ) {
            localProjectionSurfaceDisplacement =
                updateProjectionSurfaceDisplacement(
                    ProjectionSurfaceDisplacementControls.deep,
                    "private-layer-projection-depth-deep",
                )
          }
        }
      }

      Section("Surface Topology") {
        Text(
            "Keeps the same rest-space content coordinates while selecting a continuous grid or separated tiles. Depth flexibility moves from one depth value per tile at 0 to the per-vertex depth path at 1.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Off", !localProjectionSurfaceTiling.enabled) {
            localProjectionSurfaceTiling =
                updateProjectionSurfaceTiling(
                    ProjectionSurfaceTilingControls.off,
                    "private-layer-surface-tiling-off",
                )
          }
          ChoiceButton(
              "Continuous grid",
              localProjectionSurfaceTiling.enabled &&
                  localProjectionSurfaceTiling.topology ==
                      ProjectionSurfaceTilingControls.topologyContinuous,
          ) {
            localProjectionSurfaceTiling =
                updateProjectionSurfaceTiling(
                    localProjectionSurfaceTiling.copy(
                        enabled = true,
                        topology = ProjectionSurfaceTilingControls.topologyContinuous,
                    ),
                    "private-layer-surface-topology-continuous",
                )
          }
          ChoiceButton(
              "Tiled",
              localProjectionSurfaceTiling.enabled &&
                  localProjectionSurfaceTiling.topology ==
                      ProjectionSurfaceTilingControls.topologyTiled,
          ) {
            localProjectionSurfaceTiling =
                updateProjectionSurfaceTiling(
                    localProjectionSurfaceTiling.copy(
                        enabled = true,
                        topology = ProjectionSurfaceTilingControls.topologyTiled,
                    ),
                    "private-layer-surface-topology-tiled",
                )
          }
          ChoiceButton(
              "Triangle tiles",
              localProjectionSurfaceTiling.enabled &&
                  localProjectionSurfaceTiling.topology ==
                      ProjectionSurfaceTilingControls.topologyTriangleTiles,
          ) {
            localProjectionSurfaceTiling =
                updateProjectionSurfaceTiling(
                    localProjectionSurfaceTiling.copy(
                        enabled = true,
                        topology = ProjectionSurfaceTilingControls.topologyTriangleTiles,
                    ),
                    "private-layer-surface-topology-triangle-tiles",
                )
          }
        }
        if (localProjectionSurfaceTiling.enabled &&
            localProjectionSurfaceTiling.topology !=
                ProjectionSurfaceTilingControls.topologyContinuous) {
          DepthSlider(
              "Tile gap",
              localProjectionSurfaceTiling.gapNormalized,
              0.0f..0.45f,
          ) {
            localProjectionSurfaceTiling =
                updateProjectionSurfaceTiling(
                    localProjectionSurfaceTiling.copy(gapNormalized = it),
                    "private-layer-surface-tile-gap",
                )
          }
        }
        if (localProjectionSurfaceTiling.enabled) {
          DepthSlider(
              "Depth flexibility",
              localProjectionSurfaceTiling.depthFlexibility,
              0.0f..1.0f,
          ) {
            localProjectionSurfaceTiling =
                updateProjectionSurfaceTiling(
                    localProjectionSurfaceTiling.copy(depthFlexibility = it),
                    "private-layer-surface-depth-flexibility",
                )
          }
          HelpLabel("Tile scope")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Inner + buffer",
                localProjectionSurfaceTiling.scope ==
                    ProjectionSurfaceTilingControls.scopeInnerAndBuffer,
            ) {
              localProjectionSurfaceTiling =
                  updateProjectionSurfaceTiling(
                      localProjectionSurfaceTiling.copy(
                          scope = ProjectionSurfaceTilingControls.scopeInnerAndBuffer
                      ),
                      "private-layer-surface-scope-inner-and-buffer",
                  )
            }
            ChoiceButton(
                "Inner only",
                localProjectionSurfaceTiling.scope ==
                    ProjectionSurfaceTilingControls.scopeCoreOnly,
            ) {
              localProjectionSurfaceTiling =
                  updateProjectionSurfaceTiling(
                      localProjectionSurfaceTiling.copy(
                          scope = ProjectionSurfaceTilingControls.scopeCoreOnly
                      ),
                      "private-layer-surface-scope-core-only",
                  )
            }
          }
        }
      }

      Section("Inner Transparency") {
        Text(
            "Derives transparency from the processed core color, emits premultiplied alpha, and multiplies the existing outer-underlay alpha. This does not alter the direct 180/360 video layer.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Off", !localProjectionInnerAlpha.enabled) {
            localProjectionInnerAlpha =
                updateProjectionInnerAlpha(
                    ProjectionInnerAlphaControls.off,
                    "private-layer-inner-alpha-off",
                )
          }
          ChoiceButton("On", localProjectionInnerAlpha.enabled) {
            localProjectionInnerAlpha =
                updateProjectionInnerAlpha(
                    localProjectionInnerAlpha.copy(
                        enabled = true,
                        amount =
                            if (localProjectionInnerAlpha.amount > 0.0f) {
                              localProjectionInnerAlpha.amount
                            } else {
                              1.0f
                            },
                    ),
                    "private-layer-inner-alpha-on",
                )
          }
        }
        if (localProjectionInnerAlpha.enabled) {
          HelpLabel("Transparency driver")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            listOf(
                    "Red" to ProjectionInnerAlphaControls.driverRed,
                    "Green" to ProjectionInnerAlphaControls.driverGreen,
                    "Blue" to ProjectionInnerAlphaControls.driverBlue,
                )
                .forEach { (label, driver) ->
                  ChoiceButton(label, localProjectionInnerAlpha.driver == driver) {
                    localProjectionInnerAlpha =
                        updateProjectionInnerAlpha(
                            localProjectionInnerAlpha.copy(driver = driver),
                            "private-layer-inner-alpha-driver-${label.lowercase()}",
                        )
                  }
                }
          }
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Luma",
                localProjectionInnerAlpha.driver == ProjectionInnerAlphaControls.driverLuma,
            ) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(
                          driver = ProjectionInnerAlphaControls.driverLuma
                      ),
                      "private-layer-inner-alpha-driver-luma",
                  )
            }
            ChoiceButton(
                "Max",
                localProjectionInnerAlpha.driver == ProjectionInnerAlphaControls.driverMax,
            ) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(
                          driver = ProjectionInnerAlphaControls.driverMax
                      ),
                      "private-layer-inner-alpha-driver-max",
                  )
            }
          }
          DepthSlider("Threshold", localProjectionInnerAlpha.threshold, 0.0f..1.0f) {
            localProjectionInnerAlpha =
                updateProjectionInnerAlpha(
                    localProjectionInnerAlpha.copy(threshold = it),
                    "private-layer-inner-alpha-threshold",
                )
          }
          DepthSlider("Softness", localProjectionInnerAlpha.softness, 0.001f..0.5f) {
            localProjectionInnerAlpha =
                updateProjectionInnerAlpha(
                    localProjectionInnerAlpha.copy(softness = it),
                    "private-layer-inner-alpha-softness",
                )
          }
          DepthSlider("Amount", localProjectionInnerAlpha.amount, 0.0f..1.0f) {
            localProjectionInnerAlpha =
                updateProjectionInnerAlpha(
                    localProjectionInnerAlpha.copy(amount = it),
                    "private-layer-inner-alpha-amount",
                )
          }
          HelpLabel("Transparency direction")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Normal", !localProjectionInnerAlpha.invert) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(invert = false),
                      "private-layer-inner-alpha-normal",
                  )
            }
            ChoiceButton("Invert", localProjectionInnerAlpha.invert) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(invert = true),
                      "private-layer-inner-alpha-invert",
                  )
            }
          }
          HelpLabel("Stretch transparency policy")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Follow projection",
                localProjectionInnerAlpha.stretchPolicy ==
                    ProjectionInnerAlphaControls.stretchFollowProjection,
            ) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(
                          stretchPolicy = ProjectionInnerAlphaControls.stretchFollowProjection
                      ),
                      "private-layer-inner-alpha-stretch-follow-projection",
                  )
            }
            ChoiceButton(
                "Opaque stretch",
                localProjectionInnerAlpha.stretchPolicy ==
                    ProjectionInnerAlphaControls.stretchOpaqueIndependent,
            ) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(
                          stretchPolicy = ProjectionInnerAlphaControls.stretchOpaqueIndependent
                      ),
                      "private-layer-inner-alpha-stretch-opaque-independent",
                  )
            }
          }
          HelpLabel("Exact projection mask")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Exact projection mask",
                localProjectionInnerAlpha.stretchObeysExactProjectionMask,
            ) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(
                          stretchObeysExactProjectionMask = true
                      ),
                      "private-layer-inner-alpha-exact-mask-on",
                  )
            }
            ChoiceButton(
                "Independent mask",
                !localProjectionInnerAlpha.stretchObeysExactProjectionMask,
            ) {
              localProjectionInnerAlpha =
                  updateProjectionInnerAlpha(
                      localProjectionInnerAlpha.copy(
                          stretchObeysExactProjectionMask = false
                      ),
                      "private-layer-inner-alpha-exact-mask-off",
                  )
            }
          }
        }
      }

      Section("RGB Channel Transform") {
        Text(
            "A public neutral transform supplies independent direction, cycle rate, strength, image scale, and effect coverage. The private shader retains authority over the guide signal and final distortion formula.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Mode: ${RgbChannelTransformControls.modeToken(localRgbChannelTransform.mode)} · edge: ${RgbChannelTransformControls.edgeToken(localRgbChannelTransform.edgeMode)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        HelpLabel("RGB transform mode")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              "Bypass",
              localRgbChannelTransform.mode == RgbChannelTransformControls.modeBypass,
          ) {
            localRgbChannelTransform =
                updateRgbChannelTransform(
                    RgbChannelTransformControls.bypass,
                    "private-layer-rgb-preset-bypass",
                )
          }
          ChoiceButton(
              "Linked",
              localRgbChannelTransform.mode == RgbChannelTransformControls.modeLinked,
          ) {
            localRgbChannelTransform =
                updateRgbChannelTransform(
                    RgbChannelTransformControls.linked,
                    "private-layer-rgb-preset-linked",
                )
          }
          ChoiceButton(
              "Independent",
              localRgbChannelTransform.mode == RgbChannelTransformControls.modeIndependent,
          ) {
            localRgbChannelTransform =
                updateRgbChannelTransform(
                    RgbChannelTransformControls.independent,
                    "private-layer-rgb-preset-independent",
                )
          }
        }
        if (localRgbChannelTransform.mode != RgbChannelTransformControls.modeBypass) {
          HelpLabel("RGB edge handling")
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ChoiceButton(
                "Clamp",
                localRgbChannelTransform.edgeMode == RgbChannelTransformControls.edgeClamp,
            ) {
              localRgbChannelTransform =
                  updateRgbChannelTransform(
                      localRgbChannelTransform.copy(edgeMode = RgbChannelTransformControls.edgeClamp),
                      "private-layer-rgb-edge-clamp",
                  )
            }
            ChoiceButton(
                "Mirror",
                localRgbChannelTransform.edgeMode == RgbChannelTransformControls.edgeMirror,
            ) {
              localRgbChannelTransform =
                  updateRgbChannelTransform(
                      localRgbChannelTransform.copy(edgeMode = RgbChannelTransformControls.edgeMirror),
                      "private-layer-rgb-edge-mirror",
                  )
            }
            ChoiceButton(
                "Fade",
                localRgbChannelTransform.edgeMode == RgbChannelTransformControls.edgeFade,
            ) {
              localRgbChannelTransform =
                  updateRgbChannelTransform(
                      localRgbChannelTransform.copy(edgeMode = RgbChannelTransformControls.edgeFade),
                      "private-layer-rgb-edge-fade",
                  )
            }
          }
          if (localRgbChannelTransform.mode == RgbChannelTransformControls.modeLinked) {
            RgbChannelEditor(
                label = "Linked RGB",
                channel = localRgbChannelTransform.red,
                update = { channel, source ->
                  localRgbChannelTransform =
                      updateRgbChannelTransform(
                          localRgbChannelTransform.copy(red = channel),
                          source,
                      )
                },
            )
          } else {
            RgbChannelEditor(
                label = "Red",
                channel = localRgbChannelTransform.red,
                update = { channel, source ->
                  localRgbChannelTransform =
                      updateRgbChannelTransform(
                          localRgbChannelTransform.copy(red = channel),
                          source,
                      )
                },
            )
            RgbChannelEditor(
                label = "Green",
                channel = localRgbChannelTransform.green,
                update = { channel, source ->
                  localRgbChannelTransform =
                      updateRgbChannelTransform(
                          localRgbChannelTransform.copy(green = channel),
                          source,
                      )
                },
            )
            RgbChannelEditor(
                label = "Blue",
                channel = localRgbChannelTransform.blue,
                update = { channel, source ->
                  localRgbChannelTransform =
                      updateRgbChannelTransform(
                          localRgbChannelTransform.copy(blue = channel),
                          source,
                      )
                },
            )
          }
        }
      }
      }

      if (currentPage == PrivateLayerPanelPage.Regions) {
        RegionSettingsPage(
            selectedTab = currentRegionTab,
            onSelectTab = { currentRegionTab = it },
            configuration = localZoneCompositor,
            surfaceTiling = localProjectionSurfaceTiling,
            innerAlpha = localProjectionInnerAlpha,
            onConfigurationChange = { requested, source ->
              PrivateLayerZoneCompositorPanelBridge.submit(requested, source)
            },
            onSurfaceTilingChange = { requested, source ->
              localProjectionSurfaceTiling = updateProjectionSurfaceTiling(requested, source)
            },
            onInnerAlphaChange = { requested, source ->
              localProjectionInnerAlpha = updateProjectionInnerAlpha(requested, source)
            },
            disableSurfaceDisplacement = {
              localProjectionSurfaceDisplacement =
                  updateProjectionSurfaceDisplacement(
                      ProjectionSurfaceDisplacementControls.off,
                      "private-layer-zone-synthetic-displacement-off",
                  )
            },
        )
      }

      if (currentPage == PrivateLayerPanelPage.Image) {
      Section("Camera Sampling A/B") {
        Text(
            "Thin-line AA applies a modest footprint-aware five-tap tent filter at camera ingress. Linear preserves the previous single bilinear sample for direct comparison.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Active: ${PrivateLayerControls.cameraSamplingToken(localGuideProcessing.cameraSampling)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        HelpLabel("Camera sampling")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Thin-line AA",
              selected =
                  localGuideProcessing.cameraSampling ==
                      PrivateLayerControls.cameraSamplingThinLineTent5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        cameraSampling = PrivateLayerControls.cameraSamplingThinLineTent5,
                    ),
                    "private-layer-control-panel-camera-sampling-thin-line-aa",
                )
          }
          ChoiceButton(
              label = "Linear",
              selected =
                  localGuideProcessing.cameraSampling == PrivateLayerControls.cameraSamplingLinear,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        cameraSampling = PrivateLayerControls.cameraSamplingLinear,
                    ),
                    "private-layer-control-panel-camera-sampling-linear",
                )
          }
        }
      }

      Section("Guide Processing A/B") {
        Text(
            "Native parity is the verified target: 5-tap box pre/post blur with luma extracted before pre-blur. Gaussian and RGB remain live diagnostics.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Active: ${PrivateLayerControls.guideProcessingPresetToken(localGuideProcessing)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        HelpLabel("Guide processing preset")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native target",
              selected =
                  PrivateLayerControls.guideProcessingPresetToken(localGuideProcessing) ==
                      "native-parity",
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    PrivateLayerControls.nativeParityGuideProcessing.copy(
                        cameraSampling = localGuideProcessing.cameraSampling,
                    ),
                    "private-layer-control-panel-guide-native-parity",
                )
          }
          ChoiceButton(
              label = "Gaussian + RGB",
              selected =
                  PrivateLayerControls.guideProcessingPresetToken(localGuideProcessing) ==
                      "gaussian-rgb-diagnostic",
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    PrivateLayerControls.gaussianRgbGuideProcessing.copy(
                        cameraSampling = localGuideProcessing.cameraSampling,
                    ),
                    "private-layer-control-panel-guide-gaussian-rgb",
                )
          }
        }
        HelpLabel("Guide preblur kernel")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native box 5",
              selected =
                  localGuideProcessing.preblurKernel == PrivateLayerControls.guideKernelNativeBox5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        preblurKernel = PrivateLayerControls.guideKernelNativeBox5,
                    ),
                    "private-layer-control-panel-guide-preblur-box5",
                )
          }
          ChoiceButton(
              label = "Gaussian 5",
              selected =
                  localGuideProcessing.preblurKernel == PrivateLayerControls.guideKernelGaussian5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        preblurKernel = PrivateLayerControls.guideKernelGaussian5,
                    ),
                    "private-layer-control-panel-guide-preblur-gaussian5",
                )
          }
        }
        HelpLabel("Guide input")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native luma",
              selected = localGuideProcessing.preblurInput == PrivateLayerControls.guideInputLuma,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(preblurInput = PrivateLayerControls.guideInputLuma),
                    "private-layer-control-panel-guide-input-luma",
                )
          }
          ChoiceButton(
              label = "Preserve RGB",
              selected =
                  localGuideProcessing.preblurInput == PrivateLayerControls.guideInputPreserveRgb,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        preblurInput = PrivateLayerControls.guideInputPreserveRgb,
                    ),
                    "private-layer-control-panel-guide-input-rgb",
                )
          }
        }
        HelpLabel("Guide postblur kernel")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native box 5",
              selected =
                  localGuideProcessing.postblurKernel == PrivateLayerControls.guideKernelNativeBox5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        postblurKernel = PrivateLayerControls.guideKernelNativeBox5,
                    ),
                    "private-layer-control-panel-guide-postblur-box5",
                )
          }
          ChoiceButton(
              label = "Gaussian 5",
              selected =
                  localGuideProcessing.postblurKernel == PrivateLayerControls.guideKernelGaussian5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        postblurKernel = PrivateLayerControls.guideKernelGaussian5,
                    ),
                    "private-layer-control-panel-guide-postblur-gaussian5",
                )
          }
        }
      }
      }

      if (currentPage == PrivateLayerPanelPage.Depth) {
      Section("Depth Recovery") {
        Text(
            "Bounded recovery reduces invalid-call pressure. Maximum freshness keeps trying on every eligible Spatial tick and may produce runtime error spam. Both retain the last valid depth frame.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          SpatialEnvironmentDepthRecoveryPolicy.entries.forEach { policy ->
            ChoiceButton(
                label = policy.panelLabel,
                selected = localEnvironmentDepthRecoveryPolicy == policy,
            ) {
              localEnvironmentDepthRecoveryPolicy =
                  updateEnvironmentDepthRecoveryPolicy(
                      policy,
                      "private-layer-control-panel-depth-recovery",
                  )
            }
          }
        }
      }

      Section("Depth Source") {
        localEnvironmentDepthUnavailableWarning?.let { warning ->
          Text(
              warning,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              color = LayerPanelWarm,
          )
        }
        Text(
            "Active: ${PrivateLayerControls.labelForDepthLayerPolicy(localDepthLayerPolicy)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Text(
            "Meta supplies left/right depth layers. Stereo selects the matching layer for each eye; mono and compare remain diagnostics.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        DepthSourceButtonGrid(
            selectedPolicy = localDepthLayerPolicy,
            onSelect = { policy ->
              localDepthLayerPolicy =
                  updateDepthLayerPolicy(policy, "private-layer-control-panel-depth-source")
            },
        )
      }

      Section("Depth Alignment") {
        Text(
            "Auto uses Meta's per-eye FOV and pose first. These controls apply residual fine tuning for camera crop and headset-specific alignment.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        HelpLabel("Depth metadata alignment")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OperatorButton(
              if (localDepthAlignment.metadataAutoAlign) "Auto metadata: On" else "Auto metadata: Off"
          ) {
            localDepthAlignment =
                updateDepthAlignment(
                    localDepthAlignment.copy(
                        metadataAutoAlign = !localDepthAlignment.metadataAutoAlign,
                    ),
                    "private-layer-control-panel-depth-metadata-auto",
                )
          }
          OperatorButton("Reset fine tune") {
            localDepthAlignment =
                updateDepthAlignment(
                    PrivateLayerDepthAlignment(
                        metadataAutoAlign = localDepthAlignment.metadataAutoAlign,
                    ),
                    "private-layer-control-panel-depth-fine-tune-reset",
                )
          }
        }
        DepthSlider("Left depth X", localDepthAlignment.leftX, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(leftX = value),
                  "private-layer-control-panel-depth-left-x",
              )
        }
        DepthSlider("Left depth Y", localDepthAlignment.leftY, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(leftY = value),
                  "private-layer-control-panel-depth-left-y",
              )
        }
        DepthSlider("Right depth X", localDepthAlignment.rightX, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rightX = value),
                  "private-layer-control-panel-depth-right-x",
              )
        }
        DepthSlider("Right depth Y", localDepthAlignment.rightY, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rightY = value),
                  "private-layer-control-panel-depth-right-y",
              )
        }
        DepthSlider("Depth X scale", localDepthAlignment.sampleScale, 0.25f..3.0f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(sampleScale = value),
                  "private-layer-control-panel-depth-sample-scale-x",
              )
        }
        DepthSlider("Depth Y scale", localDepthAlignment.sampleScaleY, 0.25f..3.0f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(sampleScaleY = value),
                  "private-layer-control-panel-depth-sample-scale-y",
              )
        }
        DepthSlider("Depth roll", localDepthAlignment.rollDegrees, -15.0f..15.0f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rollDegrees = value),
                  "private-layer-control-panel-depth-roll",
              )
        }
      }
      }

      if (currentPage == PrivateLayerPanelPage.Profiles) {
        Section("Saved Profiles") {
          Text(
              "Save the complete current tuning setup. Video catalog selection is intentionally retained when a profile is loaded.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          HelpLabel("Profile name")
          OutlinedTextField(
              modifier = Modifier.fillMaxWidth(),
              value = localProfileName,
              onValueChange = { localProfileName = it.take(96) },
              singleLine = true,
              label = { Text("Name") },
          )
          Button(
              modifier = Modifier.fillMaxWidth().height(52.dp),
              enabled = localProfileName.isNotBlank(),
              onClick = {
                val result = saveStoredProfile(localProfileName)
                localProfileLibrary = result.library
                profileStatus = result.status
                if (result.status == "profile-saved") localProfileName = ""
              },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = LayerPanelAccent,
                      contentColor = Color(0xFF04111A),
                  ),
          ) {
            Text("Save current setup")
          }
          Text(
              "Status: ${profileStatus.replace('-', ' ')} · ${localProfileLibrary.profiles.size} saved",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          if (localProfileLibrary.profiles.isEmpty()) {
            Text(
                "No profiles saved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = LayerPanelMuted,
            )
          }
          localProfileLibrary.profiles.forEach { stored ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = LayerPanelSurfaceAlt,
            ) {
              Row(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Column(modifier = Modifier.weight(1.0f)) {
                  Text(stored.title, fontWeight = FontWeight.SemiBold)
                  Text(
                      "${PrivateLayerControls.labelForOverride(stored.controls.layerOverride)} · " +
                          "scale ${"%.2f".format(stored.controls.projectionScale)} · " +
                          stored.controls.videoPresentationMode,
                      style = MaterialTheme.typography.bodySmall,
                      color = LayerPanelMuted,
                  )
                }
                OperatorButton("Load") {
                  val result = loadStoredProfile(stored.id)
                  localProfileLibrary = result.library
                  profileStatus = result.status
                  result.effectiveControls?.let(::adoptProfileControls)
                }
                OperatorButton("Delete") {
                  val result = deleteStoredProfile(stored.id)
                  localProfileLibrary = result.library
                  profileStatus = result.status
                }
              }
            }
          }
        }

        Section("PC Import / Export") {
          Text(
              "The app continuously publishes a human-readable export bundle. Use the repository transfer tool with an explicit headset serial to pull it to a PC or stage an import bundle.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          Text(
              "Export mirror: ${localProfileLibrary.exportStatus.replace('-', ' ')}",
              style = MaterialTheme.typography.bodyMedium,
          )
          Button(
              modifier = Modifier.fillMaxWidth().height(52.dp),
              onClick = {
                val result = importStagedProfiles()
                localProfileLibrary = result.library
                profileStatus = result.status
              },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = LayerPanelSurfaceAlt,
                      contentColor = LayerPanelInk,
                  ),
          ) {
            Text("Import staged bundle")
          }
        }
      }
      if (currentPage == PrivateLayerPanelPage.Playlists) {
        panelExtension?.PanelContent(
            profileLibrary = profileLibrary,
            onControlsApplied = ::adoptProfileControls,
        )
      }
        }
      }
    }
  }
}

@Composable
private fun PersistentPanelHeader(
    currentPage: PrivateLayerPanelPage,
    panelExtension: SpatialPrivatePanelExtension?,
    selectedHelp: PrivateLayerControlHelpEntry?,
    onSelectPage: (PrivateLayerPanelPage) -> Unit,
    onDismissHelp: () -> Unit,
    closePanel: () -> Unit,
) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      color = LayerPanelSurface,
      contentColor = LayerPanelInk,
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
            modifier = Modifier.weight(1.0f).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          PanelGrabHandle()
          Column {
            Text(
                if (currentPage == PrivateLayerPanelPage.Playlists) {
                  panelExtension?.pageTitle ?: currentPage.title
                } else {
                  currentPage.title
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (currentPage == PrivateLayerPanelPage.Playlists) {
                  panelExtension?.pageSubtitle ?: currentPage.subtitle
                } else {
                  currentPage.subtitle
                },
                style = MaterialTheme.typography.bodySmall,
                color = LayerPanelMuted,
            )
          }
        }
        Button(
            onClick = closePanel,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = LayerPanelSurfaceAlt,
                    contentColor = LayerPanelInk,
                ),
        ) {
          Text("Close")
        }
      }
      Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            onClick = { onSelectPage(PrivateLayerPanelPage.Home) },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (currentPage == PrivateLayerPanelPage.Home) {
                          LayerPanelAccent
                        } else {
                          LayerPanelSurfaceAlt
                        },
                    contentColor =
                        if (currentPage == PrivateLayerPanelPage.Home) {
                          Color(0xFF04111A)
                        } else {
                          LayerPanelInk
                        },
                ),
        ) {
          Text("Home")
        }
        PrivateLayerPanelPage.entries.filterNot {
              it == PrivateLayerPanelPage.Home ||
                  (it == PrivateLayerPanelPage.Playlists && panelExtension == null)
            }
            .forEach { page ->
          HeaderPageButton(
              label =
                  if (page == PrivateLayerPanelPage.Playlists) {
                    panelExtension?.pageTitle ?: page.title
                  } else {
                    page.title
                  },
              selected = currentPage == page,
              onClick = { onSelectPage(page) },
          )
        }
      }
      selectedHelp?.let { help ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = LayerPanelSurfaceAlt,
        ) {
          Row(
              modifier = Modifier.fillMaxWidth().padding(10.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(modifier = Modifier.weight(1.0f)) {
              Text(help.title, fontWeight = FontWeight.SemiBold, color = LayerPanelAccent)
              Text(
                  help.description,
                  style = MaterialTheme.typography.bodySmall,
                  color = LayerPanelInk,
              )
            }
            Button(
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                onClick = onDismissHelp,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = LayerPanelSurface,
                        contentColor = LayerPanelInk,
                    ),
            ) {
              Text("Hide")
            }
          }
        }
      }
    }
  }
  HorizontalDivider(color = LayerPanelBorder)
}

@Composable
private fun HeaderPageButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
  Button(
      modifier = Modifier.height(44.dp),
      contentPadding = PaddingValues(horizontal = 14.dp),
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
              contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
          ),
  ) {
    Text(label, maxLines = 1)
  }
}

@Composable
internal fun HelpLabel(label: String) {
  val requestHelp = LocalPrivateLayerControlHelpRequest.current
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Button(
        modifier = Modifier.size(32.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = { requestHelp(label) },
        colors =
            ButtonDefaults.buttonColors(
                containerColor = LayerPanelSurfaceAlt,
                contentColor = LayerPanelAccent,
            ),
    ) {
      Text("?")
    }
  }
}

@Composable
private fun PanelTopicMenu(
    projectionSummary: String,
    videoSummary: String,
    regionSummary: String,
    imageSummary: String,
    depthSummary: String,
    profileSummary: String,
    playlistSummary: String?,
    externalControlSummary: String,
    onSelect: (PrivateLayerPanelPage) -> Unit,
) {
  Section("Settings topics") {
    TopicNavigationButton(
        title = PrivateLayerPanelPage.Layers.title,
        summary = projectionSummary,
        onClick = { onSelect(PrivateLayerPanelPage.Layers) },
    )
    TopicNavigationButton(
        title = PrivateLayerPanelPage.Video.title,
        summary = videoSummary,
        onClick = { onSelect(PrivateLayerPanelPage.Video) },
    )
    TopicNavigationButton(
        title = PrivateLayerPanelPage.Regions.title,
        summary = regionSummary,
        onClick = { onSelect(PrivateLayerPanelPage.Regions) },
    )
    TopicNavigationButton(
        title = PrivateLayerPanelPage.Image.title,
        summary = imageSummary,
        onClick = { onSelect(PrivateLayerPanelPage.Image) },
    )
    TopicNavigationButton(
        title = PrivateLayerPanelPage.Depth.title,
        summary = depthSummary,
        onClick = { onSelect(PrivateLayerPanelPage.Depth) },
    )
    TopicNavigationButton(
        title = PrivateLayerPanelPage.Profiles.title,
        summary = profileSummary,
        onClick = { onSelect(PrivateLayerPanelPage.Profiles) },
    )
    playlistSummary?.let { summary ->
      TopicNavigationButton(
          title = PrivateLayerPanelPage.Playlists.title,
          summary = summary,
          onClick = { onSelect(PrivateLayerPanelPage.Playlists) },
      )
    }
    TopicNavigationButton(
        title = PrivateLayerPanelPage.ExternalControl.title,
        summary = externalControlSummary,
        onClick = { onSelect(PrivateLayerPanelPage.ExternalControl) },
    )
  }
}

@Composable
private fun TopicNavigationButton(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
  Button(
      modifier = Modifier.fillMaxWidth().height(72.dp),
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = LayerPanelSurfaceAlt,
              contentColor = LayerPanelInk,
          ),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(summary, style = MaterialTheme.typography.bodySmall, color = LayerPanelMuted)
    }
  }
}

@Composable
private fun RgbChannelEditor(
    label: String,
    channel: RgbChannelParameters,
    update: (RgbChannelParameters, String) -> Unit,
) {
  Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
  DepthSlider("Direction", channel.directionTurns, 0.0f..1.0f) {
    update(channel.copy(directionTurns = it), "private-layer-rgb-${label.lowercase()}-direction")
  }
  DepthSlider("Direction speed", channel.directionRateHz, -1.0f..1.0f) {
    update(channel.copy(directionRateHz = it), "private-layer-rgb-${label.lowercase()}-speed")
  }
  DepthSlider("Strength", channel.displacementStrengthUv, 0.0f..0.08f) {
    update(
        channel.copy(displacementStrengthUv = it),
        "private-layer-rgb-${label.lowercase()}-strength",
    )
  }
  DepthSlider("Image scale", channel.imageScale, 0.5f..2.0f) {
    update(channel.copy(imageScale = it), "private-layer-rgb-${label.lowercase()}-image-scale")
  }
  DepthSlider("Coverage scale", channel.coverageScale, 0.5f..1.0f) {
    update(
        channel.copy(coverageScale = it),
        "private-layer-rgb-${label.lowercase()}-coverage-scale",
    )
  }
}

@Composable
private fun PanelGrabHandle() {
  Column(
      modifier =
          Modifier
              .width(30.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(LayerPanelSurfaceAlt)
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp))
              .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    repeat(3) {
      Box(
          modifier =
              Modifier
                  .fillMaxWidth()
                  .height(2.dp)
                  .background(LayerPanelAccent, RoundedCornerShape(1.dp))
      )
    }
  }
}

@Composable
private fun PreviewBand() {
  Box(
      modifier =
          Modifier
              .fillMaxWidth()
              .height(62.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(
                  Brush.horizontalGradient(
                      listOf(
                          Color(0xFF111827),
                          Color(0xFF2B8FD8),
                          LayerPanelWarm,
                          Color(0xFFD84F9A),
                          Color(0xFF111827),
                      )
                  )
              )
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp)),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        "private layer selector",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
  Column(
      modifier =
          Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(LayerPanelSurface)
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp))
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    HelpLabel(title)
    HorizontalDivider(color = LayerPanelBorder)
    content()
  }
}

@Composable
private fun LayerButtonGrid(
    selectedLayerOverride: Float,
    onSelect: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    HelpLabel("Rendering layer")
    LayerButtonRow(
        choices =
            listOf(
                PrivateLayerChoice(-1, "Cycle", "cycle"),
                PrivateLayerControls.layers[0],
            ),
        selectedLayerOverride = selectedLayerOverride,
        onSelect = onSelect,
    )
    PrivateLayerControls.layers.drop(1).chunked(2).forEach { row ->
      LayerButtonRow(row, selectedLayerOverride, onSelect)
    }
  }
}

@Composable
private fun LayerButtonRow(
    choices: List<PrivateLayerChoice>,
    selectedLayerOverride: Float,
    onSelect: (Float) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    choices.forEach { choice ->
      val override =
          if (choice.index < 0) PrivateLayerControls.cycleOverride else choice.index.toFloat()
      val selected =
          if (override < 0.0f) {
            selectedLayerOverride < 0.0f
          } else {
            selectedLayerOverride.toInt() == choice.index
          }
      Button(
          modifier = Modifier.weight(1.0f).height(52.dp),
          onClick = { onSelect(override) },
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
                  contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
              ),
      ) {
        Text(choice.title)
      }
    }
    if (choices.size == 1) {
      Spacer(Modifier.weight(1.0f))
    }
  }
}

@Composable
private fun DepthSourceButtonGrid(
    selectedPolicy: Int,
    onSelect: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    HelpLabel("Depth source")
    PrivateLayerControls.depthSourcePolicies.chunked(2).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        row.forEach { choice ->
          val selected =
              PrivateLayerControls.normalizeDepthLayerPolicy(selectedPolicy) == choice.code
          Button(
              modifier = Modifier.weight(1.0f).height(52.dp),
              onClick = { onSelect(choice.code) },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
                      contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
                  ),
          ) {
            Text(choice.title)
          }
        }
      }
    }
  }
}

@Composable
private fun RegionSettingsPage(
    selectedTab: RegionSettingsTab,
    onSelectTab: (RegionSettingsTab) -> Unit,
    configuration: PrivateLayerZoneCompositor,
    surfaceTiling: ProjectionSurfaceTiling,
    innerAlpha: ProjectionInnerAlpha,
    onConfigurationChange: (PrivateLayerZoneCompositor, String) -> Unit,
    onSurfaceTilingChange: (ProjectionSurfaceTiling, String) -> Unit,
    onInnerAlphaChange: (ProjectionInnerAlpha, String) -> Unit,
    disableSurfaceDisplacement: () -> Unit,
) {
  val controls = PrivateLayerZoneCompositorControls
  val bufferActive = configuration.bufferGeometryMode != controls.bufferGeometryOff
  val stretchSelected = configuration.bufferFillMode == controls.bufferFillStretch
  val stretchActive = bufferActive && stretchSelected
  val outerReplaced =
      stretchActive && configuration.stretchExtentMode == controls.stretchExtentReplaceOuter
  val transparentUnderlay =
      configuration.outerTargetMode == controls.outerTargetTransparentSpatialVideo

  Section("Effective region topology") {
    Text(
        if (bufferActive) {
          "Inner  →  ${controls.bufferContentLabel(configuration)}  →  " +
              if (outerReplaced) "Stretch to carrier edge" else "Outer"
        } else {
          "Inner  →  Outer  · buffer inactive"
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = LayerPanelAccent,
    )
    Text(
        "Buffer ${controls.bufferGeometryToken(configuration.bufferGeometryMode)} · " +
            "fill ${controls.bufferFillToken(configuration.bufferFillMode)} · " +
            "outer ${controls.outerTargetToken(configuration.outerTargetMode)}",
        style = MaterialTheme.typography.bodySmall,
        color = LayerPanelMuted,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      RegionSettingsTab.entries.forEach { tab ->
        Button(
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            onClick = { onSelectTab(tab) },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (selectedTab == tab) LayerPanelAccent else LayerPanelSurfaceAlt,
                    contentColor =
                        if (selectedTab == tab) Color(0xFF04111A) else LayerPanelInk,
                ),
        ) {
          Text(tab.label, maxLines = 1)
        }
      }
    }
  }

  when (selectedTab) {
    RegionSettingsTab.Buffer ->
        Section("Buffer region") {
          Text(
              "Geometry decides whether a middle region exists and how large it is. Content is a separate choice and remains stored when geometry is Off.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          HelpLabel("Buffer geometry")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Off", configuration.bufferGeometryMode == controls.bufferGeometryOff) {
              onConfigurationChange(
                  configuration.copy(bufferGeometryMode = controls.bufferGeometryOff),
                  "private-layer-buffer-geometry-off",
              )
            }
            ChoiceButton(
                "Static",
                configuration.bufferGeometryMode == controls.bufferGeometryStatic,
            ) {
              onConfigurationChange(
                  configuration.copy(bufferGeometryMode = controls.bufferGeometryStatic),
                  "private-layer-buffer-geometry-static",
              )
            }
            ChoiceButton(
                "Dynamic",
                configuration.bufferGeometryMode == controls.bufferGeometryDynamic,
            ) {
              onConfigurationChange(
                  configuration.copy(bufferGeometryMode = controls.bufferGeometryDynamic),
                  "private-layer-buffer-geometry-dynamic",
              )
            }
          }
          if (configuration.bufferGeometryMode != controls.bufferGeometryOff) {
            DepthSlider("Guard size", configuration.bufferStaticWidthUv, 0.0f..0.2f) {
              onConfigurationChange(
                  configuration.copy(bufferStaticWidthUv = it),
                  "private-layer-buffer-guard-size",
              )
            }
          }
          Text(
              if (configuration.bufferGeometryMode == controls.bufferGeometryDynamic) {
                "Guard size is the minimum retained source border and visible projection contraction. Dynamic may grow that same guard from current head motion, then releases back to this value."
              } else if (configuration.bufferGeometryMode == controls.bufferGeometryStatic) {
                "Guard size reserves that source border and contracts the visible custom projection by the matching amount. Projection scale stays independent."
              } else {
                "With Buffer Off, no source guard is reserved and the visible custom projection returns to its full configured scale."
              },
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          HelpLabel("Buffer content")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Outer",
                configuration.bufferFillMode == controls.bufferFillOuterContinuation,
            ) {
              onConfigurationChange(
                  configuration.copy(
                      bufferFillMode = controls.bufferFillOuterContinuation,
                      stretchExtentMode = controls.stretchExtentBufferOnly,
                  ),
                  "private-layer-buffer-fill-outer",
              )
            }
            ChoiceButton(
                "Transparent",
                configuration.bufferFillMode == controls.bufferFillTransparentReveal,
            ) {
              onConfigurationChange(
                  configuration.copy(
                      bufferFillMode = controls.bufferFillTransparentReveal,
                      stretchExtentMode = controls.stretchExtentBufferOnly,
                  ),
                  "private-layer-buffer-fill-transparent",
              )
            }
            ChoiceButton("Stretch", stretchSelected) {
              onConfigurationChange(
                  configuration.copy(bufferFillMode = controls.bufferFillStretch),
                  "private-layer-buffer-fill-stretch",
              )
            }
          }
          Text(
              "Selected content: ${controls.bufferContentLabel(configuration)}. " +
                  if (bufferActive) "It is currently visible in the middle region."
                  else "It is retained but inactive until Static or Dynamic geometry is enabled.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
        }

    RegionSettingsTab.Effects ->
        Section("General Inner + buffer effects") {
          Text(
              "These are surface-region effects, not Stretch sampling controls. With Inner + buffer scope they affect whatever content occupies the buffer, including Stretch, but stop at the buffer boundary when Stretch replaces Outer.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          HelpLabel("Surface topology")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Off", !surfaceTiling.enabled) {
              onSurfaceTilingChange(
                  ProjectionSurfaceTilingControls.off,
                  "private-layer-region-effects-tiling-off",
              )
            }
            ChoiceButton(
                "Continuous grid",
                surfaceTiling.enabled &&
                    surfaceTiling.topology == ProjectionSurfaceTilingControls.topologyContinuous,
            ) {
              onSurfaceTilingChange(
                  surfaceTiling.copy(
                      enabled = true,
                      topology = ProjectionSurfaceTilingControls.topologyContinuous,
                  ),
                  "private-layer-region-effects-topology-continuous",
              )
            }
            ChoiceButton(
                "Squares",
                surfaceTiling.enabled &&
                    surfaceTiling.topology == ProjectionSurfaceTilingControls.topologyTiled,
            ) {
              onSurfaceTilingChange(
                  surfaceTiling.copy(
                      enabled = true,
                      topology = ProjectionSurfaceTilingControls.topologyTiled,
                  ),
                  "private-layer-region-effects-topology-square",
              )
            }
            ChoiceButton(
                "Triangles",
                surfaceTiling.enabled &&
                    surfaceTiling.topology ==
                        ProjectionSurfaceTilingControls.topologyTriangleTiles,
            ) {
              onSurfaceTilingChange(
                  surfaceTiling.copy(
                      enabled = true,
                      topology = ProjectionSurfaceTilingControls.topologyTriangleTiles,
                  ),
                  "private-layer-region-effects-topology-triangle",
              )
            }
          }
          if (surfaceTiling.enabled &&
              surfaceTiling.topology != ProjectionSurfaceTilingControls.topologyContinuous) {
            DepthSlider("Tile gap", surfaceTiling.gapNormalized, 0.0f..0.45f) {
              onSurfaceTilingChange(
                  surfaceTiling.copy(gapNormalized = it),
                  "private-layer-region-effects-tile-gap",
              )
            }
          }
          if (surfaceTiling.enabled) {
            DepthSlider("Tile depth flexibility", surfaceTiling.depthFlexibility, 0.0f..1.0f) {
              onSurfaceTilingChange(
                  surfaceTiling.copy(depthFlexibility = it),
                  "private-layer-region-effects-depth-flexibility",
              )
            }
            HelpLabel("Tile scope")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
              ChoiceButton(
                  "Inner + buffer",
                  surfaceTiling.scope == ProjectionSurfaceTilingControls.scopeInnerAndBuffer,
              ) {
                onSurfaceTilingChange(
                    surfaceTiling.copy(
                        scope = ProjectionSurfaceTilingControls.scopeInnerAndBuffer
                    ),
                    "private-layer-region-effects-scope-inner-buffer",
                )
              }
              ChoiceButton(
                  "Inner only",
                  surfaceTiling.scope == ProjectionSurfaceTilingControls.scopeCoreOnly,
              ) {
                onSurfaceTilingChange(
                    surfaceTiling.copy(scope = ProjectionSurfaceTilingControls.scopeCoreOnly),
                    "private-layer-region-effects-scope-inner-only",
                )
              }
            }
          }
          Text(
              if (!bufferActive && surfaceTiling.scope == ProjectionSurfaceTilingControls.scopeInnerAndBuffer) {
                "Buffer is Off, so Inner + buffer currently resolves to Inner only. The wider scope is retained."
              } else {
                "Effective scope: ${ProjectionSurfaceTilingControls.scopeToken(surfaceTiling.scope)}."
              },
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
        }

    RegionSettingsTab.Stretch ->
        Section("Stretch content") {
          Text(
              if (stretchActive) {
                "Stretch is active in the buffer${if (outerReplaced) " and continues through Outer" else ""}."
              } else if (stretchSelected) {
                "Stretch is selected but inactive because Buffer geometry is Off. Its settings are retained."
              } else {
                "Stretch is inactive because the buffer currently uses ${controls.bufferContentLabel(configuration)}. Its settings are retained."
              },
              style = MaterialTheme.typography.bodySmall,
              color = if (stretchActive) LayerPanelAccent else LayerPanelMuted,
          )
          HelpLabel("Stretch starting point")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Native",
                controls.matchesStretchStyle(configuration, controls.nativeBuffer),
            ) {
              onConfigurationChange(
                  controls.applyStretchStyle(configuration, controls.nativeBuffer),
                  "private-layer-stretch-style-native",
              )
            }
            ChoiceButton(
                "Organic",
                controls.matchesStretchStyle(configuration, controls.organicBuffer),
            ) {
              onConfigurationChange(
                  controls.applyStretchStyle(configuration, controls.organicBuffer),
                  "private-layer-stretch-style-organic",
              )
            }
            ChoiceButton(
                "Custom",
                stretchSelected &&
                    !controls.matchesStretchStyle(configuration, controls.nativeBuffer) &&
                    !controls.matchesStretchStyle(configuration, controls.organicBuffer),
                enabled = false,
            ) {}
          }
          HelpLabel("Stretch extent")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Buffer only",
                configuration.stretchExtentMode == controls.stretchExtentBufferOnly,
            ) {
              onConfigurationChange(
                  configuration.copy(stretchExtentMode = controls.stretchExtentBufferOnly),
                  "private-layer-stretch-extent-buffer",
              )
            }
            ChoiceButton(
                "Replace Outer",
                configuration.stretchExtentMode == controls.stretchExtentReplaceOuter,
            ) {
              onConfigurationChange(
                  configuration.copy(stretchExtentMode = controls.stretchExtentReplaceOuter),
                  "private-layer-stretch-extent-outer",
              )
            }
          }
          HelpLabel("Stretch source")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Raw", configuration.stretchSource == controls.sourceRaw) {
              onConfigurationChange(
                  configuration.copy(stretchSource = controls.sourceRaw),
                  "private-layer-stretch-source-raw",
              )
            }
            ChoiceButton("Processed", configuration.stretchSource == controls.sourceProcessed) {
              onConfigurationChange(
                  configuration.copy(stretchSource = controls.sourceProcessed),
                  "private-layer-stretch-source-processed",
              )
            }
            ChoiceButton("Mix", configuration.stretchSource == controls.sourceMixed) {
              onConfigurationChange(
                  configuration.copy(stretchSource = controls.sourceMixed),
                  "private-layer-stretch-source-mix",
              )
            }
          }
          DepthSlider("Edge inset", configuration.edgeInsetUv, 0.0f..0.49f) {
            onConfigurationChange(
                configuration.copy(edgeInsetUv = it),
                "private-layer-stretch-edge-inset",
            )
          }
          DepthSlider("Maximum inset", configuration.maxInsetUv, 0.0f..0.49f) {
            onConfigurationChange(
                configuration.copy(maxInsetUv = it),
                "private-layer-stretch-maximum-inset",
            )
          }
          DepthSlider("Stretch curve", configuration.stretchCurve, 0.25f..6.0f) {
            onConfigurationChange(
                configuration.copy(stretchCurve = it),
                "private-layer-stretch-curve",
            )
          }
          if (configuration.stretchSource == controls.sourceMixed) {
            DepthSlider("Processed source mix", configuration.processedMix, 0.0f..1.0f) {
              onConfigurationChange(
                  configuration.copy(processedMix = it),
                  "private-layer-stretch-processed-mix",
              )
            }
          }
          HelpLabel("Stretch attachment")
          val attachmentFlags = configuration.stretchOptionFlags and 0x1c
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Standard", attachmentFlags == 0) {
              onConfigurationChange(
                  configuration.copy(stretchOptionFlags = configuration.stretchOptionFlags and 0x1c.inv()),
                  "private-layer-stretch-attachment-standard",
              )
            }
            ChoiceButton("Sample warp", attachmentFlags == 0x04) {
              onConfigurationChange(
                  configuration.copy(
                      stretchOptionFlags =
                          (configuration.stretchOptionFlags and 0x1c.inv()) or 0x04
                  ),
                  "private-layer-stretch-attachment-sample-warp",
              )
            }
          }
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Smooth radial", attachmentFlags == 0x08) {
              onConfigurationChange(
                  configuration.copy(
                      stretchOptionFlags =
                          (configuration.stretchOptionFlags and 0x1c.inv()) or 0x08
                  ),
                  "private-layer-stretch-attachment-smooth-radial",
              )
            }
            ChoiceButton("Seamless", attachmentFlags == 0x10) {
              onConfigurationChange(
                  configuration.copy(
                      stretchOptionFlags =
                          (configuration.stretchOptionFlags and 0x1c.inv()) or 0x10
                  ),
                  "private-layer-stretch-attachment-seamless",
              )
            }
          }
          Text(
              "Stretch transparency is a Stretch-only relationship to the general Inner transparency effect.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          if (innerAlpha.enabled) {
            HelpLabel("Stretch transparency policy")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
              ChoiceButton(
                  "Follow projection",
                  innerAlpha.stretchPolicy == ProjectionInnerAlphaControls.stretchFollowProjection,
              ) {
                onInnerAlphaChange(
                    innerAlpha.copy(
                        stretchPolicy = ProjectionInnerAlphaControls.stretchFollowProjection
                    ),
                    "private-layer-stretch-alpha-follow",
                )
              }
              ChoiceButton(
                  "Opaque Stretch",
                  innerAlpha.stretchPolicy ==
                      ProjectionInnerAlphaControls.stretchOpaqueIndependent,
              ) {
                onInnerAlphaChange(
                    innerAlpha.copy(
                        stretchPolicy = ProjectionInnerAlphaControls.stretchOpaqueIndependent
                    ),
                    "private-layer-stretch-alpha-opaque",
                )
              }
            }
            HelpLabel("Exact projection mask")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
              ChoiceButton("Exact", innerAlpha.stretchObeysExactProjectionMask) {
                onInnerAlphaChange(
                    innerAlpha.copy(stretchObeysExactProjectionMask = true),
                    "private-layer-stretch-alpha-exact",
                )
              }
              ChoiceButton("Independent", !innerAlpha.stretchObeysExactProjectionMask) {
                onInnerAlphaChange(
                    innerAlpha.copy(stretchObeysExactProjectionMask = false),
                    "private-layer-stretch-alpha-independent",
                )
              }
            }
          } else {
            Text(
                "Inner transparency is Off on the Image processing page, so these stored relationship settings have no visible effect.",
                style = MaterialTheme.typography.bodySmall,
                color = LayerPanelMuted,
            )
          }
        }

    RegionSettingsTab.Transitions ->
        RegionTransitionsSection(
            configuration = configuration,
            onConfigurationChange = onConfigurationChange,
        )

    RegionSettingsTab.Outer ->
        Section("Outer region") {
          Text(
              if (outerReplaced) {
                "Outer is currently replaced by Stretch. The selected Outer target is retained and returns when Stretch extent changes to Buffer only."
              } else if (transparentUnderlay) {
                "Outer is transparent so the separate world-anchored 180/360 Spatial video remains visible underneath."
              } else {
                "Outer is readable same-surface video sampled by the custom compositor."
              },
              style = MaterialTheme.typography.bodySmall,
              color = if (outerReplaced) LayerPanelWarm else LayerPanelMuted,
          )
          HelpLabel("Outer target")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Same-layer video", !transparentUnderlay) {
              onConfigurationChange(
                  controls.withOuterTarget(configuration, controls.outerTargetReadableColor),
                  "private-layer-outer-target-readable",
              )
            }
            ChoiceButton("180/360 underlay", transparentUnderlay) {
              onConfigurationChange(
                  controls.withOuterTarget(
                      configuration,
                      controls.outerTargetTransparentSpatialVideo,
                  ),
                  "private-layer-outer-target-spatial-underlay",
              )
            }
          }
          Text(
              "The Outer target does not choose Buffer content. Buffer → Outer continuation follows this target; Transparent reveal remains transparent; Stretch remains controlled on its own tab.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
        }

    RegionSettingsTab.Diagnostics ->
        Section("Region diagnostics") {
          Text(
              "Diagnostics deliberately use readable synthetic colors and can temporarily disable displacement so region boundaries are easy to inspect.",
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
          HelpLabel("Debug view")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("Normal", configuration.debugMode == controls.debugOff) {
              onConfigurationChange(
                  configuration.copy(debugMode = controls.debugOff),
                  "private-layer-region-debug-off",
              )
            }
            ChoiceButton("Regions", configuration.debugMode == controls.debugRegions) {
              disableSurfaceDisplacement()
              onConfigurationChange(
                  configuration.copy(debugMode = controls.debugRegions),
                  "private-layer-region-debug-regions",
              )
            }
            ChoiceButton("Sample UV", configuration.debugMode == controls.debugSampleUv) {
              onConfigurationChange(
                  configuration.copy(debugMode = controls.debugSampleUv),
                  "private-layer-region-debug-sample-uv",
              )
            }
          }
          HelpLabel("Blend test preset")
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton("RGB components", configuration == controls.componentBlendTest) {
              disableSurfaceDisplacement()
              onConfigurationChange(
                  controls.componentBlendTest,
                  "private-layer-region-preset-components",
              )
            }
            ChoiceButton("RGB regions", configuration == controls.regionBlendTest) {
              disableSurfaceDisplacement()
              onConfigurationChange(
                  controls.regionBlendTest,
                  "private-layer-region-preset-regions",
              )
            }
            ChoiceButton("360 underlay", configuration == controls.spatialVideoUnderlayBlendTest) {
              onConfigurationChange(
                  controls.spatialVideoUnderlayBlendTest,
                  "private-layer-region-preset-underlay",
              )
            }
          }
          Text(
              "Effective boundaries: ${controls.innerBoundaryLabel(configuration)}" +
                  if (controls.outerBoundaryActive(configuration)) {
                    " · ${controls.outerBoundaryLabel(configuration)}"
                  } else {
                    " · no separate outer transition"
                  },
              style = MaterialTheme.typography.bodySmall,
              color = LayerPanelMuted,
          )
        }
  }
}

@Composable
private fun RegionTransitionsSection(
    configuration: PrivateLayerZoneCompositor,
    onConfigurationChange: (PrivateLayerZoneCompositor, String) -> Unit,
) {
  val controls = PrivateLayerZoneCompositorControls
  val outerActive = controls.outerBoundaryActive(configuration)
  var editOuter by remember { mutableStateOf(false) }
  LaunchedEffect(outerActive) {
    if (!outerActive) editOuter = false
  }
  Section("Transition boundaries") {
    Text(
        "Transition controls shape blending between adjacent regions. They do not create the buffer or choose Stretch sampling.",
        style = MaterialTheme.typography.bodySmall,
        color = LayerPanelMuted,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
      ChoiceButton(controls.innerBoundaryLabel(configuration), !editOuter) { editOuter = false }
      ChoiceButton(
          controls.outerBoundaryLabel(configuration),
          editOuter,
          enabled = outerActive,
      ) {
        editOuter = true
      }
    }
    if (!outerActive) {
      Text(
          if (configuration.bufferGeometryMode == controls.bufferGeometryOff) {
            "Buffer is Off, so there is one direct Inner ↔ Outer boundary."
          } else {
            "Stretch replaces Outer, so there is no separate buffer ↔ Outer boundary."
          },
          style = MaterialTheme.typography.bodySmall,
          color = LayerPanelWarm,
      )
    }
    ZoneTransitionEditor(
        inner = !editOuter,
        configuration = configuration,
        onConfigurationChange = onConfigurationChange,
    )
  }
}

@Composable
private fun ZoneTransitionEditor(
    inner: Boolean,
    configuration: PrivateLayerZoneCompositor,
    onConfigurationChange: (PrivateLayerZoneCompositor, String) -> Unit,
) {
  val prefix = if (inner) "Inner" else "Outer"
  val sourcePrefix = if (inner) "inner" else "outer"
  val signal = if (inner) configuration.innerSignal else configuration.outerSignal
  val dynamics =
      if (inner) configuration.innerChannelDynamics else configuration.outerChannelDynamics
  ZoneSignalButtons(
      selectedSignal = signal,
      helpLabel = "$prefix transition signal",
  ) { value ->
    onConfigurationChange(
        if (inner) configuration.copy(innerSignal = value)
        else configuration.copy(outerSignal = value),
        "private-layer-transition-$sourcePrefix-signal",
    )
  }
  DepthSlider(
      "$prefix width",
      if (inner) configuration.innerWidthUv else configuration.outerWidthUv,
      0.0f..0.25f,
  ) {
    onConfigurationChange(
        if (inner) configuration.copy(innerWidthUv = it)
        else configuration.copy(outerWidthUv = it),
        "private-layer-transition-$sourcePrefix-width",
    )
  }
  DepthSlider(
      "$prefix spatial curve",
      if (inner) configuration.innerCurve else configuration.outerCurve,
      0.25f..6.0f,
  ) {
    onConfigurationChange(
        if (inner) configuration.copy(innerCurve = it)
        else configuration.copy(outerCurve = it),
        "private-layer-transition-$sourcePrefix-curve",
    )
  }
  ZoneChannelSliders(
      prefix,
      if (inner) configuration.innerThresholdR else configuration.outerThresholdR,
      if (inner) configuration.innerThresholdG else configuration.outerThresholdG,
      if (inner) configuration.innerThresholdB else configuration.outerThresholdB,
  ) { red, green, blue ->
    onConfigurationChange(
        if (inner) {
          configuration.copy(
              innerThresholdR = red,
              innerThresholdG = green,
              innerThresholdB = blue,
          )
        } else {
          configuration.copy(
              outerThresholdR = red,
              outerThresholdG = green,
              outerThresholdB = blue,
          )
        },
        "private-layer-transition-$sourcePrefix-threshold",
    )
  }
  DepthSlider(
      "$prefix softness",
      if (inner) configuration.innerSoftness else configuration.outerSoftness,
      0.001f..0.5f,
  ) {
    onConfigurationChange(
        if (inner) configuration.copy(innerSoftness = it)
        else configuration.copy(outerSoftness = it),
        "private-layer-transition-$sourcePrefix-softness",
    )
  }
  ZoneBlendApplicationButtons(dynamics.applicationMode) { value ->
    onConfigurationChange(
        if (inner) {
          configuration.copy(innerChannelDynamics = dynamics.copy(applicationMode = value))
        } else {
          configuration.copy(outerChannelDynamics = dynamics.copy(applicationMode = value))
        },
        "private-layer-transition-$sourcePrefix-application",
    )
  }
  if (dynamics.applicationMode == PrivateLayerZoneCompositorControls.applicationLegacy) {
    DepthSlider(
        "$prefix channel influence",
        if (inner) configuration.innerStrength else configuration.outerStrength,
        0.0f..1.0f,
    ) {
      onConfigurationChange(
          if (inner) configuration.copy(innerStrength = it)
          else configuration.copy(outerStrength = it),
          "private-layer-transition-$sourcePrefix-influence",
      )
    }
    DepthSlider(
        "$prefix cycle amount",
        if (inner) configuration.innerCycleAmplitude else configuration.outerCycleAmplitude,
        0.0f..0.5f,
    ) {
      onConfigurationChange(
          if (inner) configuration.copy(innerCycleAmplitude = it)
          else configuration.copy(outerCycleAmplitude = it),
          "private-layer-transition-$sourcePrefix-cycle-amount",
      )
    }
    DepthSlider(
        "$prefix cycle speed",
        if (inner) configuration.innerCycleHz else configuration.outerCycleHz,
        0.0f..1.0f,
    ) {
      onConfigurationChange(
          if (inner) configuration.copy(innerCycleHz = it)
          else configuration.copy(outerCycleHz = it),
          "private-layer-transition-$sourcePrefix-cycle-speed",
      )
    }
  } else {
    ZoneBlendSourceButtons(dynamics.sourceChoice) { value ->
      onConfigurationChange(
          if (inner) {
            configuration.copy(innerChannelDynamics = dynamics.copy(sourceChoice = value))
          } else {
            configuration.copy(outerChannelDynamics = dynamics.copy(sourceChoice = value))
          },
          "private-layer-transition-$sourcePrefix-source",
      )
    }
    if (dynamics.applicationMode == PrivateLayerZoneCompositorControls.applicationRegion) {
      ZoneRegionDriverButtons(dynamics.regionDriver) { value ->
        onConfigurationChange(
            if (inner) {
              configuration.copy(innerChannelDynamics = dynamics.copy(regionDriver = value))
            } else {
              configuration.copy(outerChannelDynamics = dynamics.copy(regionDriver = value))
            },
            "private-layer-transition-$sourcePrefix-driver",
        )
      }
    }
    ZoneChannelDynamicsEditor(prefix, dynamics) { value ->
      onConfigurationChange(
          if (inner) configuration.copy(innerChannelDynamics = value)
          else configuration.copy(outerChannelDynamics = value),
          "private-layer-transition-$sourcePrefix-channel-dynamics",
      )
    }
  }
  DepthSlider(
      "$prefix motion response",
      if (inner) configuration.innerMotionGain else configuration.outerMotionGain,
      -0.5f..0.5f,
  ) {
    onConfigurationChange(
        if (inner) configuration.copy(innerMotionGain = it)
        else configuration.copy(outerMotionGain = it),
        "private-layer-transition-$sourcePrefix-motion",
    )
  }
}

@Composable
private fun ZoneSignalButtons(
    selectedSignal: Int,
    differenceEnabled: Boolean = true,
    helpLabel: String = "Transition signal",
    onSelect: (Int) -> Unit,
) {
  HelpLabel(helpLabel)
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Flat", selectedSignal == PrivateLayerZoneCompositorControls.signalFlat) {
      onSelect(PrivateLayerZoneCompositorControls.signalFlat)
    }
    ChoiceButton("RGB", selectedSignal == PrivateLayerZoneCompositorControls.signalRgb) {
      onSelect(PrivateLayerZoneCompositorControls.signalRgb)
    }
    ChoiceButton("Luma", selectedSignal == PrivateLayerZoneCompositorControls.signalLuma) {
      onSelect(PrivateLayerZoneCompositorControls.signalLuma)
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Chroma", selectedSignal == PrivateLayerZoneCompositorControls.signalChroma) {
      onSelect(PrivateLayerZoneCompositorControls.signalChroma)
    }
    ChoiceButton(
        "Difference",
        selectedSignal == PrivateLayerZoneCompositorControls.signalDifference,
        enabled = differenceEnabled,
    ) {
      onSelect(PrivateLayerZoneCompositorControls.signalDifference)
    }
  }
}

@Composable
private fun ZoneChannelSliders(
    prefix: String,
    red: Float,
    green: Float,
    blue: Float,
    onChange: (Float, Float, Float) -> Unit,
) {
  DepthSlider("$prefix red threshold", red, 0.0f..1.0f) { onChange(it, green, blue) }
  DepthSlider("$prefix green threshold", green, 0.0f..1.0f) { onChange(red, it, blue) }
  DepthSlider("$prefix blue threshold", blue, 0.0f..1.0f) { onChange(red, green, it) }
}

@Composable
private fun ZoneBlendApplicationButtons(
    selectedMode: Int,
    legacyEnabled: Boolean = true,
    componentEnabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
  HelpLabel("Blend application")
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton(
        "Legacy",
        selectedMode == PrivateLayerZoneCompositorControls.applicationLegacy,
        enabled = legacyEnabled,
    ) {
      onSelect(PrivateLayerZoneCompositorControls.applicationLegacy)
    }
    ChoiceButton(
        "Components",
        selectedMode == PrivateLayerZoneCompositorControls.applicationComponent,
        enabled = componentEnabled,
    ) {
      onSelect(PrivateLayerZoneCompositorControls.applicationComponent)
    }
    ChoiceButton("Regions", selectedMode == PrivateLayerZoneCompositorControls.applicationRegion) {
      onSelect(PrivateLayerZoneCompositorControls.applicationRegion)
    }
  }
}

@Composable
private fun ZoneBlendSourceButtons(
    selectedSource: Int,
    midpointEnabled: Boolean = true,
    incomingEnabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
  HelpLabel("Blend sample source")
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Outgoing", selectedSource == PrivateLayerZoneCompositorControls.blendSourceOutgoing) {
      onSelect(PrivateLayerZoneCompositorControls.blendSourceOutgoing)
    }
    ChoiceButton(
        "Midpoint",
        selectedSource == PrivateLayerZoneCompositorControls.blendSourceMidpoint,
        enabled = midpointEnabled,
    ) {
      onSelect(PrivateLayerZoneCompositorControls.blendSourceMidpoint)
    }
    ChoiceButton(
        "Incoming",
        selectedSource == PrivateLayerZoneCompositorControls.blendSourceIncoming,
        enabled = incomingEnabled,
    ) {
      onSelect(PrivateLayerZoneCompositorControls.blendSourceIncoming)
    }
  }
}

@Composable
private fun ZoneRegionDriverButtons(selectedDriver: Int, onSelect: (Int) -> Unit) {
  HelpLabel("Region driver")
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Red", selectedDriver == PrivateLayerZoneCompositorControls.regionDriverRed) {
      onSelect(PrivateLayerZoneCompositorControls.regionDriverRed)
    }
    ChoiceButton("Green", selectedDriver == PrivateLayerZoneCompositorControls.regionDriverGreen) {
      onSelect(PrivateLayerZoneCompositorControls.regionDriverGreen)
    }
    ChoiceButton("Blue", selectedDriver == PrivateLayerZoneCompositorControls.regionDriverBlue) {
      onSelect(PrivateLayerZoneCompositorControls.regionDriverBlue)
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Luma", selectedDriver == PrivateLayerZoneCompositorControls.regionDriverLuma) {
      onSelect(PrivateLayerZoneCompositorControls.regionDriverLuma)
    }
    ChoiceButton("Max", selectedDriver == PrivateLayerZoneCompositorControls.regionDriverMax) {
      onSelect(PrivateLayerZoneCompositorControls.regionDriverMax)
    }
  }
}

@Composable
private fun ZoneChannelDynamicsEditor(
    prefix: String,
    dynamics: PrivateLayerZoneChannelDynamics,
    onChange: (PrivateLayerZoneChannelDynamics) -> Unit,
) {
  var selectedChannel by remember(prefix) { mutableStateOf(0) }
  HelpLabel("$prefix channel controls")
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Red", selectedChannel == 0) { selectedChannel = 0 }
    ChoiceButton("Green", selectedChannel == 1) { selectedChannel = 1 }
    ChoiceButton("Blue", selectedChannel == 2) { selectedChannel = 2 }
  }
  val channelName = when (selectedChannel) {
    1 -> "green"
    2 -> "blue"
    else -> "red"
  }
  val strength = when (selectedChannel) {
    1 -> dynamics.strengthG
    2 -> dynamics.strengthB
    else -> dynamics.strengthR
  }
  val amplitude = when (selectedChannel) {
    1 -> dynamics.cycleAmplitudeG
    2 -> dynamics.cycleAmplitudeB
    else -> dynamics.cycleAmplitudeR
  }
  val frequency = when (selectedChannel) {
    1 -> dynamics.cycleHzG
    2 -> dynamics.cycleHzB
    else -> dynamics.cycleHzR
  }
  val phase = when (selectedChannel) {
    1 -> dynamics.cyclePhaseG
    2 -> dynamics.cyclePhaseB
    else -> dynamics.cyclePhaseR
  }
  DepthSlider("$prefix $channelName influence", strength, 0.0f..1.0f) {
    onChange(
        when (selectedChannel) {
          1 -> dynamics.copy(strengthG = it)
          2 -> dynamics.copy(strengthB = it)
          else -> dynamics.copy(strengthR = it)
        }
    )
  }
  DepthSlider("$prefix $channelName cycle amount", amplitude, 0.0f..0.5f) {
    onChange(
        when (selectedChannel) {
          1 -> dynamics.copy(cycleAmplitudeG = it)
          2 -> dynamics.copy(cycleAmplitudeB = it)
          else -> dynamics.copy(cycleAmplitudeR = it)
        }
    )
  }
  DepthSlider("$prefix $channelName cycle speed", frequency, 0.0f..1.0f) {
    onChange(
        when (selectedChannel) {
          1 -> dynamics.copy(cycleHzG = it)
          2 -> dynamics.copy(cycleHzB = it)
          else -> dynamics.copy(cycleHzR = it)
        }
    )
  }
  DepthSlider("$prefix $channelName phase (turns)", phase, -1.0f..1.0f) {
    onChange(
        when (selectedChannel) {
          1 -> dynamics.copy(cyclePhaseG = it)
          2 -> dynamics.copy(cyclePhaseB = it)
          else -> dynamics.copy(cyclePhaseR = it)
        }
    )
  }
}

@Composable
private fun DepthSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      HelpLabel(label)
      Text("%.3f".format(value), style = MaterialTheme.typography.bodyMedium, color = LayerPanelMuted)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
  }
}

@Composable
internal fun OperatorButton(label: String, onClick: () -> Unit) {
  Button(
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = LayerPanelSurfaceAlt,
              contentColor = LayerPanelInk,
          ),
  ) {
    Text(label)
  }
}

@Composable
private fun RowScope.ChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  Button(
      modifier = Modifier.weight(1.0f).height(52.dp),
      enabled = enabled,
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
              contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
          ),
  ) {
    Text(label)
  }
}
