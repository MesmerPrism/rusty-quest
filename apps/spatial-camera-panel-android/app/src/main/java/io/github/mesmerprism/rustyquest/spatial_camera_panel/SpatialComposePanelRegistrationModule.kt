package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelSettings
import java.io.Closeable

internal data class SpatialPrivateLayerPanelRegistrationBindings(
    val layerOverride: () -> Float,
    val projectionPanelEnabled: () -> Boolean,
    val projectionScale: Float,
    val projectionScaleRange: ClosedFloatingPointRange<Float>,
    val depthLayerPolicy: Int,
    val depthAlignment: PrivateLayerDepthAlignment,
    val guideProcessing: PrivateLayerGuideProcessing,
    val rgbChannelTransform: RgbChannelTransform,
    val projectionSurfaceDisplacement: ProjectionSurfaceDisplacement,
    val projectionSurfaceTiling: ProjectionSurfaceTiling,
    val projectionInnerAlpha: ProjectionInnerAlpha,
    val videoSession: () -> SpatialImmersiveVideoSessionSnapshot,
    val sharedMediaLibraryStatus: () -> SharedOfflineImmersiveMediaLibrarySnapshot,
    val observeSharedMediaLibrary:
        ((SharedOfflineImmersiveMediaLibrarySnapshot) -> Unit) -> Closeable,
    val refreshSharedMediaLibrary: () -> SharedOfflineImmersiveMediaLibrarySnapshot,
    val connectionHubStatus: () -> ConnectionHubWearerControlSnapshot,
    val observeConnectionHub:
        ((ConnectionHubWearerControlSnapshot) -> Unit) -> Closeable,
    val refreshConnectionHub: () -> ConnectionHubWearerControlSnapshot,
    val startConnectionHub: () -> ConnectionHubWearerControlSnapshot,
    val stopConnectionHub: () -> ConnectionHubWearerControlSnapshot,
    val environmentDepthUnavailableWarning: () -> String?,
    val environmentDepthRecoveryPolicy: () -> SpatialEnvironmentDepthRecoveryPolicy,
    val updateEnvironmentDepthRecoveryPolicy:
        (SpatialEnvironmentDepthRecoveryPolicy, String) -> SpatialEnvironmentDepthRecoveryPolicy,
    val setLayerOverride: (Float, String) -> Float,
    val setProjectionPanelEnabled: (Boolean, String) -> Boolean,
    val setVideoPlaybackEnabled:
        (Boolean) -> SpatialImmersiveVideoSessionSnapshot,
    val updateProjectionScale: (Float, String) -> Float,
    val updateDepthLayerPolicy: (Int, String) -> Int,
    val updateDepthAlignment:
        (PrivateLayerDepthAlignment, String) -> PrivateLayerDepthAlignment,
    val updateGuideProcessing:
        (PrivateLayerGuideProcessing, String) -> PrivateLayerGuideProcessing,
    val updateRgbChannelTransform:
        (RgbChannelTransform, String) -> RgbChannelTransform,
    val updateProjectionSurfaceDisplacement:
        (ProjectionSurfaceDisplacement, String) -> ProjectionSurfaceDisplacement,
    val updateProjectionSurfaceTiling:
        (ProjectionSurfaceTiling, String) -> ProjectionSurfaceTiling,
    val updateProjectionInnerAlpha:
        (ProjectionInnerAlpha, String) -> ProjectionInnerAlpha,
    val selectPreviousVideo: () -> SpatialImmersiveVideoSessionSnapshot,
    val selectNextVideo: () -> SpatialImmersiveVideoSessionSnapshot,
    val selectVideo: (Int) -> SpatialImmersiveVideoSessionSnapshot,
    val setVideoPresentationMode:
        (SpatialImmersiveVideoPresentationMode) -> SpatialImmersiveVideoSessionSnapshot,
    val setBackgroundMode: (SpatialBackgroundMode) -> SpatialImmersiveVideoSessionSnapshot,
    val chooseSharedMediaFolder: () -> Unit,
    val profileLibrary: () -> SpatialCameraPanelProfileLibrarySnapshot,
    val saveStoredProfile: (String) -> SpatialCameraPanelProfileOperationResult,
    val loadStoredProfile: (String) -> SpatialCameraPanelProfileOperationResult,
    val deleteStoredProfile: (String) -> SpatialCameraPanelProfileOperationResult,
    val importStagedProfiles: () -> SpatialCameraPanelProfileOperationResult,
    val panelExtension: SpatialPrivatePanelExtension?,
    val closePanel: () -> Unit,
    val settings: (Entity) -> PanelSettings,
    val onPanelSetup: (PanelSceneObject) -> Unit,
)

internal object SpatialComposePanelRegistrationModule {
  const val MODULE_ID = "spatial-compose-panel-registration"

  fun registrations(
      privateLayer: SpatialPrivateLayerPanelRegistrationBindings,
  ): List<PanelRegistration> = listOf(privateLayerPanel(privateLayer))

  private fun privateLayerPanel(
      bindings: SpatialPrivateLayerPanelRegistrationBindings
  ): PanelRegistration =
      ComposeViewPanelRegistration(
          R.id.spatial_private_layer_panel,
          composeViewCreator = { _, context ->
            ComposeView(context).apply {
              setBackgroundColor(AndroidColor.rgb(20, 24, 32))
              alpha = 1.0f
              setWillNotDraw(false)
              setLayerType(View.LAYER_TYPE_HARDWARE, null)
              setContent {
                MaterialTheme(
                    colorScheme =
                        lightColorScheme(
                            primary = Color(0xFF63D2FF),
                            onPrimary = Color(0xFF04111A),
                            background = Color(0xFF141820),
                            onBackground = Color(0xFFF4F7FA),
                            surface = Color(0xFF202634),
                            onSurface = Color(0xFFF4F7FA),
                        )
                ) {
                  PrivateLayerControlPanel(
                      layerOverride = bindings.layerOverride(),
                      projectionPanelEnabled = bindings.projectionPanelEnabled(),
                      projectionScale = bindings.projectionScale,
                      projectionScaleRange = bindings.projectionScaleRange,
                      depthLayerPolicy = bindings.depthLayerPolicy,
                      depthAlignment = bindings.depthAlignment,
                      guideProcessing = bindings.guideProcessing,
                      rgbChannelTransform = bindings.rgbChannelTransform,
                      projectionSurfaceDisplacement = bindings.projectionSurfaceDisplacement,
                      projectionSurfaceTiling = bindings.projectionSurfaceTiling,
                      projectionInnerAlpha = bindings.projectionInnerAlpha,
                      videoSession = bindings.videoSession,
                      sharedMediaLibraryStatus = bindings.sharedMediaLibraryStatus,
                      observeSharedMediaLibrary = bindings.observeSharedMediaLibrary,
                      refreshSharedMediaLibrary = bindings.refreshSharedMediaLibrary,
                      connectionHubStatus = bindings.connectionHubStatus,
                      observeConnectionHub = bindings.observeConnectionHub,
                      refreshConnectionHub = bindings.refreshConnectionHub,
                      startConnectionHub = bindings.startConnectionHub,
                      stopConnectionHub = bindings.stopConnectionHub,
                      environmentDepthUnavailableWarning =
                          bindings.environmentDepthUnavailableWarning,
                      environmentDepthRecoveryPolicy = bindings.environmentDepthRecoveryPolicy,
                      updateEnvironmentDepthRecoveryPolicy =
                          bindings.updateEnvironmentDepthRecoveryPolicy,
                      setLayerOverride = bindings.setLayerOverride,
                      setProjectionPanelEnabled = bindings.setProjectionPanelEnabled,
                      setVideoPlaybackEnabled = bindings.setVideoPlaybackEnabled,
                      updateProjectionScale = bindings.updateProjectionScale,
                      updateDepthLayerPolicy = bindings.updateDepthLayerPolicy,
                      updateDepthAlignment = bindings.updateDepthAlignment,
                      updateGuideProcessing = bindings.updateGuideProcessing,
                      updateRgbChannelTransform = bindings.updateRgbChannelTransform,
                      updateProjectionSurfaceDisplacement =
                          bindings.updateProjectionSurfaceDisplacement,
                      updateProjectionSurfaceTiling = bindings.updateProjectionSurfaceTiling,
                      updateProjectionInnerAlpha = bindings.updateProjectionInnerAlpha,
                      selectPreviousVideo = bindings.selectPreviousVideo,
                      selectNextVideo = bindings.selectNextVideo,
                      selectVideo = bindings.selectVideo,
                      setVideoPresentationMode = bindings.setVideoPresentationMode,
                      setBackgroundMode = bindings.setBackgroundMode,
                      chooseSharedMediaFolder = bindings.chooseSharedMediaFolder,
                      profileLibrary = bindings.profileLibrary,
                      saveStoredProfile = bindings.saveStoredProfile,
                      loadStoredProfile = bindings.loadStoredProfile,
                      deleteStoredProfile = bindings.deleteStoredProfile,
                      importStagedProfiles = bindings.importStagedProfiles,
                      panelExtension = bindings.panelExtension,
                      closePanel = bindings.closePanel,
                  )
                }
              }
            }
          },
          settingsCreator = bindings.settings,
          panelSetupWithComposeView = { _, panel, _ -> bindings.onPanelSetup(panel) },
      )
}
