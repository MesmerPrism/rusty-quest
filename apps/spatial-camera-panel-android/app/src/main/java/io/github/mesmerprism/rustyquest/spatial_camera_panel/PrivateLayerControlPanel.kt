package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private enum class PrivateLayerPanelPage(
    val title: String,
    val subtitle: String,
) {
  Home("Settings", "Choose a topic"),
  Layers("Layers & projection", "Visibility, rendering layer, and projection size"),
  Video("360 video", "Playback, presentation, and active video"),
  Regions("Three-region effect", "Core, dynamic buffer, and outer-region behavior"),
  Image("Image processing", "Depth warp, RGB transform, sampling, and guide blur"),
  Depth("Depth alignment", "Depth source and per-eye fine tuning"),
  Playlists("Playlists", "Sequence saved profiles with timed looping playback"),
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
    profileLibrary: () -> SpatialCameraPanelProfileLibrarySnapshot,
    panelExtension: SpatialPrivatePanelExtension?,
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
  var localVideoSession by remember { mutableStateOf(videoSession()) }
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
    }
  }
  val localZoneCompositor = PrivateLayerZoneCompositorPanelBridge.configuration
  val transparentSpatialUnderlay =
      localZoneCompositor.outerTargetMode ==
          PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo
  Surface(
      modifier = Modifier.fillMaxSize(),
      color = LayerPanelBackground,
      contentColor = LayerPanelInk,
  ) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(LayerPanelBackground)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
            modifier = Modifier.weight(1.0f).padding(end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          PanelGrabHandle()
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                currentPage.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                currentPage.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LayerPanelMuted,
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          if (currentPage != PrivateLayerPanelPage.Home) {
            Button(
                onClick = { currentPage = PrivateLayerPanelPage.Home },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = LayerPanelAccent,
                        contentColor = Color(0xFF04111A),
                    ),
            ) {
              Text("Home")
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
      }

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
            playlistSummary = panelExtension?.homeSummary(),
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
        Section("360 Video Playback") {
          Text(
              if (localVideoSession.available) {
                "Active video ${localVideoSession.activeOrdinal} of ${localVideoSession.itemCount}"
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
                  "Turn 360 video off"
                } else {
                  "Turn 360 video on"
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

      if (currentPage == PrivateLayerPanelPage.Layers) {
      Section("Projection Area") {
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
            "Keeps the same rest-space content coordinates while selecting a continuous or tiled tessellated surface. Depth flexibility moves from one depth value per tile at 0 to the per-vertex depth path at 1.",
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
              "Continuous",
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
        }
        if (localProjectionSurfaceTiling.enabled) {
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
          Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
          ) {
            ChoiceButton(
                "Core + stretch",
                localProjectionSurfaceTiling.scope ==
                    ProjectionSurfaceTilingControls.scopeCoreAndStretch,
            ) {
              localProjectionSurfaceTiling =
                  updateProjectionSurfaceTiling(
                      localProjectionSurfaceTiling.copy(
                          scope = ProjectionSurfaceTilingControls.scopeCoreAndStretch
                      ),
                      "private-layer-surface-scope-core-and-stretch",
                  )
            }
            ChoiceButton(
                "Core only",
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
      Section("Peripheral Stretch & Zone Blend") {
        Text(
            "The core follows the live projection scale, then the motion guard contracts it. Stretch always uses the native mirrored oval treatment. Buffer mode fills only the released margin, while Full mode replaces the video layer.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Mode: ${PrivateLayerZoneCompositorControls.coverageToken(localZoneCompositor.coverageMode)} · style: ${PrivateLayerZoneCompositorControls.mappingToken(localZoneCompositor.stretchMapping)} · source: ${PrivateLayerZoneCompositorControls.sourceToken(localZoneCompositor.stretchSource)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Off", localZoneCompositor.coverageMode == PrivateLayerZoneCompositorControls.coverageOff) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.legacyOff,
                "private-layer-zone-preset-off",
            )
          }
          ChoiceButton("Native stretch", localZoneCompositor == PrivateLayerZoneCompositorControls.nativeBuffer) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.nativeBuffer,
                "private-layer-zone-preset-native-buffer",
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Organic stretch", localZoneCompositor == PrivateLayerZoneCompositorControls.organicBuffer) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.organicBuffer,
                "private-layer-zone-preset-organic-buffer",
            )
          }
          ChoiceButton("Full stretch", localZoneCompositor == PrivateLayerZoneCompositorControls.fullStretch) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.fullStretch,
                "private-layer-zone-preset-full-stretch",
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("RGB components", localZoneCompositor == PrivateLayerZoneCompositorControls.componentBlendTest) {
            localProjectionSurfaceDisplacement =
                updateProjectionSurfaceDisplacement(
                    ProjectionSurfaceDisplacementControls.off,
                    "private-layer-zone-synthetic-displacement-off",
                )
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.componentBlendTest,
                "private-layer-zone-preset-component-blend-test",
            )
          }
          ChoiceButton("RGB regions", localZoneCompositor == PrivateLayerZoneCompositorControls.regionBlendTest) {
            localProjectionSurfaceDisplacement =
                updateProjectionSurfaceDisplacement(
                    ProjectionSurfaceDisplacementControls.off,
                    "private-layer-zone-synthetic-displacement-off",
                )
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.regionBlendTest,
                "private-layer-zone-preset-region-blend-test",
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              "360 underlay blend",
              localZoneCompositor ==
                  PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest,
          ) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.spatialVideoUnderlayBlendTest,
                "private-layer-zone-preset-spatial-video-underlay-blend-test",
            )
          }
        }

        Text("Stretch source", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Raw", localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceRaw) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchSource = PrivateLayerZoneCompositorControls.sourceRaw),
                "private-layer-zone-source-raw",
            )
          }
          ChoiceButton("Processed", localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceProcessed) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchSource = PrivateLayerZoneCompositorControls.sourceProcessed),
                "private-layer-zone-source-processed",
            )
          }
          ChoiceButton("Mix", localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceMixed) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchSource = PrivateLayerZoneCompositorControls.sourceMixed),
                "private-layer-zone-source-mixed",
            )
          }
        }
        DepthSlider("Edge inset", localZoneCompositor.edgeInsetUv, 0.0f..0.49f) {
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(edgeInsetUv = it),
              "private-layer-zone-edge-inset",
          )
        }
        DepthSlider("Maximum inset", localZoneCompositor.maxInsetUv, 0.0f..0.49f) {
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(maxInsetUv = it),
              "private-layer-zone-maximum-inset",
          )
        }
        DepthSlider("Stretch curve", localZoneCompositor.stretchCurve, 0.25f..6.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(stretchCurve = it),
              "private-layer-zone-stretch-curve",
          )
        }
        if (localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceMixed) {
          DepthSlider("Processed source mix", localZoneCompositor.processedMix, 0.0f..1.0f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(processedMix = it),
                "private-layer-zone-processed-mix",
            )
          }
        }

        Text("Inner seam · projection ↔ stretch", style = MaterialTheme.typography.titleSmall)
        ZoneSignalButtons(localZoneCompositor.innerSignal) { signal ->
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(innerSignal = signal),
              "private-layer-zone-inner-signal",
          )
        }
        DepthSlider("Inner width", localZoneCompositor.innerWidthUv, 0.0f..0.25f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerWidthUv = it), "private-layer-zone-inner-width")
        }
        DepthSlider("Inner spatial curve", localZoneCompositor.innerCurve, 0.25f..6.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerCurve = it), "private-layer-zone-inner-curve")
        }
        ZoneChannelSliders("Inner", localZoneCompositor.innerThresholdR, localZoneCompositor.innerThresholdG, localZoneCompositor.innerThresholdB) { r, g, b ->
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerThresholdR = r, innerThresholdG = g, innerThresholdB = b), "private-layer-zone-inner-threshold")
        }
        DepthSlider("Inner softness", localZoneCompositor.innerSoftness, 0.001f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerSoftness = it), "private-layer-zone-inner-softness")
        }
        ZoneBlendApplicationButtons(localZoneCompositor.innerChannelDynamics.applicationMode) { mode ->
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(
                  innerChannelDynamics = localZoneCompositor.innerChannelDynamics.copy(applicationMode = mode)
              ),
              "private-layer-zone-inner-application",
          )
        }
        if (localZoneCompositor.innerChannelDynamics.applicationMode == PrivateLayerZoneCompositorControls.applicationLegacy) {
          DepthSlider("Inner channel influence", localZoneCompositor.innerStrength, 0.0f..1.0f) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerStrength = it), "private-layer-zone-inner-strength")
          }
          DepthSlider("Inner cycle amount", localZoneCompositor.innerCycleAmplitude, 0.0f..0.5f) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerCycleAmplitude = it), "private-layer-zone-inner-cycle-amount")
          }
          DepthSlider("Inner cycle speed", localZoneCompositor.innerCycleHz, 0.0f..1.0f) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerCycleHz = it), "private-layer-zone-inner-cycle-speed")
          }
        } else {
          ZoneBlendSourceButtons(localZoneCompositor.innerChannelDynamics.sourceChoice) { source ->
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(
                    innerChannelDynamics = localZoneCompositor.innerChannelDynamics.copy(sourceChoice = source)
                ),
                "private-layer-zone-inner-color-source",
            )
          }
          if (localZoneCompositor.innerChannelDynamics.applicationMode == PrivateLayerZoneCompositorControls.applicationRegion) {
            ZoneRegionDriverButtons(localZoneCompositor.innerChannelDynamics.regionDriver) { driver ->
              PrivateLayerZoneCompositorPanelBridge.submit(
                  localZoneCompositor.copy(
                      innerChannelDynamics = localZoneCompositor.innerChannelDynamics.copy(regionDriver = driver)
                  ),
                  "private-layer-zone-inner-region-driver",
              )
            }
          }
          ZoneChannelDynamicsEditor("Inner", localZoneCompositor.innerChannelDynamics) { dynamics ->
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(innerChannelDynamics = dynamics),
                "private-layer-zone-inner-channel-dynamics",
            )
          }
        }
        DepthSlider("Inner motion response", localZoneCompositor.innerMotionGain, -0.5f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerMotionGain = it), "private-layer-zone-inner-motion")
        }

        Text(
            if (transparentSpatialUnderlay) {
              "Outer seam · stretch ↔ transparent 360 video"
            } else {
              "Outer seam · stretch ↔ readable color"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (transparentSpatialUnderlay) {
              "The direct Spatial video is not sampled. The outgoing buffer's selected red, green, blue, luma, or max driver controls one premultiplied alpha ramp. Component, midpoint, incoming, difference, and synthetic-debug choices are disabled."
            } else {
              "Readable outer color supports the complete three-source component and region test matrix."
            },
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              "Readable outer",
              !transparentSpatialUnderlay,
          ) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.withOuterTarget(
                    localZoneCompositor,
                    PrivateLayerZoneCompositorControls.outerTargetReadableColor,
                ),
                "private-layer-zone-outer-target-readable-color",
            )
          }
          ChoiceButton(
              "360 underlay",
              transparentSpatialUnderlay,
          ) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.withOuterTarget(
                    localZoneCompositor,
                    PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo,
                ),
                "private-layer-zone-outer-target-transparent-spatial-video",
            )
          }
        }
        ZoneSignalButtons(
            selectedSignal = localZoneCompositor.outerSignal,
            differenceEnabled = !transparentSpatialUnderlay,
        ) { signal ->
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(outerSignal = signal),
              "private-layer-zone-outer-signal",
          )
        }
        DepthSlider("Outer width", localZoneCompositor.outerWidthUv, 0.0f..0.25f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerWidthUv = it), "private-layer-zone-outer-width")
        }
        DepthSlider("Outer spatial curve", localZoneCompositor.outerCurve, 0.25f..6.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerCurve = it), "private-layer-zone-outer-curve")
        }
        ZoneChannelSliders("Outer", localZoneCompositor.outerThresholdR, localZoneCompositor.outerThresholdG, localZoneCompositor.outerThresholdB) { r, g, b ->
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerThresholdR = r, outerThresholdG = g, outerThresholdB = b), "private-layer-zone-outer-threshold")
        }
        DepthSlider("Outer softness", localZoneCompositor.outerSoftness, 0.001f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerSoftness = it), "private-layer-zone-outer-softness")
        }
        ZoneBlendApplicationButtons(
            selectedMode = localZoneCompositor.outerChannelDynamics.applicationMode,
            legacyEnabled = !transparentSpatialUnderlay,
            componentEnabled = !transparentSpatialUnderlay,
        ) { mode ->
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(
                  outerChannelDynamics = localZoneCompositor.outerChannelDynamics.copy(applicationMode = mode)
              ),
              "private-layer-zone-outer-application",
          )
        }
        if (localZoneCompositor.outerChannelDynamics.applicationMode == PrivateLayerZoneCompositorControls.applicationLegacy) {
          DepthSlider("Outer channel influence", localZoneCompositor.outerStrength, 0.0f..1.0f) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerStrength = it), "private-layer-zone-outer-strength")
          }
          DepthSlider("Outer cycle amount", localZoneCompositor.outerCycleAmplitude, 0.0f..0.5f) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerCycleAmplitude = it), "private-layer-zone-outer-cycle-amount")
          }
          DepthSlider("Outer cycle speed", localZoneCompositor.outerCycleHz, 0.0f..1.0f) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerCycleHz = it), "private-layer-zone-outer-cycle-speed")
          }
        } else {
          ZoneBlendSourceButtons(
              selectedSource = localZoneCompositor.outerChannelDynamics.sourceChoice,
              midpointEnabled = !transparentSpatialUnderlay,
              incomingEnabled = !transparentSpatialUnderlay,
          ) { source ->
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(
                    outerChannelDynamics = localZoneCompositor.outerChannelDynamics.copy(sourceChoice = source)
                ),
                "private-layer-zone-outer-color-source",
            )
          }
          if (localZoneCompositor.outerChannelDynamics.applicationMode == PrivateLayerZoneCompositorControls.applicationRegion) {
            ZoneRegionDriverButtons(localZoneCompositor.outerChannelDynamics.regionDriver) { driver ->
              PrivateLayerZoneCompositorPanelBridge.submit(
                  localZoneCompositor.copy(
                      outerChannelDynamics = localZoneCompositor.outerChannelDynamics.copy(regionDriver = driver)
                  ),
                  "private-layer-zone-outer-region-driver",
              )
            }
          }
          ZoneChannelDynamicsEditor("Outer", localZoneCompositor.outerChannelDynamics) { dynamics ->
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(outerChannelDynamics = dynamics),
                "private-layer-zone-outer-channel-dynamics",
            )
          }
        }
        DepthSlider("Outer motion response", localZoneCompositor.outerMotionGain, -0.5f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerMotionGain = it), "private-layer-zone-outer-motion")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Normal", localZoneCompositor.debugMode == PrivateLayerZoneCompositorControls.debugOff) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(debugMode = PrivateLayerZoneCompositorControls.debugOff), "private-layer-zone-debug-off")
          }
          ChoiceButton(
              "Regions",
              localZoneCompositor.debugMode == PrivateLayerZoneCompositorControls.debugRegions,
              enabled = !transparentSpatialUnderlay,
          ) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(debugMode = PrivateLayerZoneCompositorControls.debugRegions), "private-layer-zone-debug-regions")
          }
          ChoiceButton(
              "Sample UV",
              localZoneCompositor.debugMode == PrivateLayerZoneCompositorControls.debugSampleUv,
              enabled = !transparentSpatialUnderlay,
          ) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(debugMode = PrivateLayerZoneCompositorControls.debugSampleUv), "private-layer-zone-debug-sample-uv")
          }
        }
      }
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
        Text("Pre-blur kernel", fontWeight = FontWeight.SemiBold)
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
        Text("Pre-blur input", fontWeight = FontWeight.SemiBold)
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
        Text("Post-blur kernel", fontWeight = FontWeight.SemiBold)
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
      Section("Depth Source") {
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
      if (currentPage == PrivateLayerPanelPage.Playlists) {
        panelExtension?.PanelContent(
            profileLibrary = profileLibrary,
            onControlsApplied = ::adoptProfileControls,
        )
      }
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
    playlistSummary: String?,
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
    playlistSummary?.let { summary ->
      TopicNavigationButton(
          title = PrivateLayerPanelPage.Playlists.title,
          summary = summary,
          onClick = { onSelect(PrivateLayerPanelPage.Playlists) },
      )
    }
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
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    HorizontalDivider(color = LayerPanelBorder)
    content()
  }
}

@Composable
internal fun HelpLabel(label: String) {
  Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LayerButtonGrid(
    selectedLayerOverride: Float,
    onSelect: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun ZoneSignalButtons(
    selectedSignal: Int,
    differenceEnabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
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
  Text("Color application", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
  Text("Color signal source", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
  Text("Whole-region driver", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
      Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
