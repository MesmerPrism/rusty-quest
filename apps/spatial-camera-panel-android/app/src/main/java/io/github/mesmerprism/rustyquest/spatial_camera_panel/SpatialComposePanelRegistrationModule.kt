package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelSettings

internal data class SpatialWorkflowPanelRegistrationBindings(
    val store: SpatialCameraPanelStore,
    val placement: PanelPlacement,
    val particleControls: SurfaceParticleControlState,
    val polarPanel: PolarSensorPanel,
    val questionnaireDueReopensPanel: Boolean,
    val setWorkflowPanelVisible: (Boolean, Boolean, String) -> PanelPlacement,
    val adjustPlacement: (Float, Float, Float, Float) -> PanelPlacement,
    val setPanelHeadlocked: (Boolean, String) -> PanelPlacement,
    val resizePanel: (Float, Float) -> PanelPlacement,
    val resetPlacement: () -> PanelPlacement,
    val updateParticleControls: (SurfaceParticleControlState) -> SurfaceParticleControlState,
    val applyDriverProfile: (ActiveBlockSnapshot, String) -> SurfaceParticleControlState,
    val setQuestionnaireDueReopensPanel: (Boolean, String) -> Unit,
)

internal data class SpatialPrivateLayerPanelRegistrationBindings(
    val layerOverride: Float,
    val projectionScale: Float,
    val projectionScaleRange: ClosedFloatingPointRange<Float>,
    val depthLayerPolicy: Int,
    val depthAlignment: PrivateLayerDepthAlignment,
    val setLayerOverride: (Float, String) -> Float,
    val updateProjectionScale: (Float, String) -> Float,
    val updateDepthLayerPolicy: (Int, String) -> Int,
    val updateDepthAlignment:
        (PrivateLayerDepthAlignment, String) -> PrivateLayerDepthAlignment,
    val closePanel: () -> Unit,
    val settings: (Entity) -> PanelSettings,
    val onPanelSetup: (PanelSceneObject) -> Unit,
)

internal object SpatialComposePanelRegistrationModule {
  const val MODULE_ID = "spatial-compose-panel-registration"

  fun registrations(
      workflow: SpatialWorkflowPanelRegistrationBindings,
      privateLayer: SpatialPrivateLayerPanelRegistrationBindings,
      openWorkflowPanel: () -> Unit,
  ): List<PanelRegistration> =
      listOf(
          workflowPanel(workflow),
          privateLayerPanel(privateLayer),
          launcherPanel(openWorkflowPanel),
      )

  private fun workflowPanel(
      bindings: SpatialWorkflowPanelRegistrationBindings
  ): PanelRegistration =
      ComposeViewPanelRegistration(
          R.id.spatial_camera_panel,
          composeViewCreator = { _, context ->
            ComposeView(context).apply {
              setBackgroundColor(AndroidColor.rgb(255, 243, 176))
              alpha = 1.0f
              setWillNotDraw(false)
              setLayerType(View.LAYER_TYPE_HARDWARE, null)
              setContent {
                MaterialTheme(
                    colorScheme =
                        lightColorScheme(
                            primary = PanelProbeHeader,
                            onPrimary = Color.White,
                            background = PanelProbeBackground,
                            onBackground = PanelProbeInk,
                            surface = PanelProbeBackground,
                            onSurface = PanelProbeInk,
                        )
                ) {
                  SpatialCameraPanel(
                      store = bindings.store,
                      placement = bindings.placement,
                      particleControls = bindings.particleControls,
                      polarPanel = bindings.polarPanel,
                      setWorkflowPanelVisible = bindings.setWorkflowPanelVisible,
                      adjustPlacement = bindings.adjustPlacement,
                      setPanelHeadlocked = bindings.setPanelHeadlocked,
                      resizePanel = bindings.resizePanel,
                      resetPlacement = bindings.resetPlacement,
                      updateParticleControls = bindings.updateParticleControls,
                      applyDriverProfile = bindings.applyDriverProfile,
                      questionnaireDueReopensPanel = bindings.questionnaireDueReopensPanel,
                      setQuestionnaireDueReopensPanel = bindings.setQuestionnaireDueReopensPanel,
                  )
                }
              }
            }
          },
          settingsCreator = {
            UIPanelSettings(
                shape = QuadShapeOptions(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS),
                style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
                display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
            )
          },
      )

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
                      layerOverride = bindings.layerOverride,
                      projectionScale = bindings.projectionScale,
                      projectionScaleRange = bindings.projectionScaleRange,
                      depthLayerPolicy = bindings.depthLayerPolicy,
                      depthAlignment = bindings.depthAlignment,
                      setLayerOverride = bindings.setLayerOverride,
                      updateProjectionScale = bindings.updateProjectionScale,
                      updateDepthLayerPolicy = bindings.updateDepthLayerPolicy,
                      updateDepthAlignment = bindings.updateDepthAlignment,
                      closePanel = bindings.closePanel,
                  )
                }
              }
            }
          },
          settingsCreator = bindings.settings,
          panelSetupWithComposeView = { _, panel, _ -> bindings.onPanelSetup(panel) },
      )

  private fun launcherPanel(openWorkflowPanel: () -> Unit): PanelRegistration =
      ComposeViewPanelRegistration(
          R.id.spatial_camera_panel_launcher,
          composeViewCreator = { _, context ->
            ComposeView(context).apply {
              setBackgroundColor(AndroidColor.rgb(15, 95, 111))
              alpha = 1.0f
              setWillNotDraw(false)
              setLayerType(View.LAYER_TYPE_HARDWARE, null)
              setContent {
                MaterialTheme(
                    colorScheme =
                        lightColorScheme(
                            primary = PanelProbeButton,
                            onPrimary = Color.White,
                            background = PanelProbeHeader,
                            onBackground = Color.White,
                            surface = PanelProbeHeader,
                            onSurface = Color.White,
                        )
                ) {
                  SpatialCameraPanelLauncher(openPanel = openWorkflowPanel)
                }
              }
            }
          },
          settingsCreator = {
            UIPanelSettings(
                shape =
                    QuadShapeOptions(
                        width = PANEL_LAUNCHER_WIDTH_METERS,
                        height = PANEL_LAUNCHER_HEIGHT_METERS,
                    ),
                style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
                display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_LAUNCHER_DP_PER_METER),
            )
          },
      )
}
