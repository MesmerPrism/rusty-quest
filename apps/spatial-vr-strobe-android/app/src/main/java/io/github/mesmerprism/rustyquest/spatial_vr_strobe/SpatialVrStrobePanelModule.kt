package io.github.mesmerprism.rustyquest.spatial_vr_strobe

import android.graphics.Color as AndroidColor
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.core.Vector2

internal const val VR_STROBE_PANEL_WIDTH_METERS = 0.96f
internal const val VR_STROBE_PANEL_HEIGHT_METERS = 0.72f
internal const val VR_STROBE_PANEL_COMFORT_DISTANCE_METERS = 0.82f
internal const val VR_STROBE_PANEL_LAYER_Z_INDEX = 100
private const val PANEL_DP_PER_METER = 720f

internal object SpatialVrStrobePanelInputPolicy {
  const val MARKER = "compose-button-a-consumed-before-focused-control"
  fun consumeAndroidKeyCode(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A
}

internal data class SpatialVrStrobePanelRegistrationBindings(
    val actions: VrStrobePanelActions,
    val onPanelSetup: (PanelSceneObject) -> Unit,
)

internal object SpatialVrStrobePanelModule {
  fun dimensions(): PanelDimensions =
      PanelDimensions(Vector2(VR_STROBE_PANEL_WIDTH_METERS, VR_STROBE_PANEL_HEIGHT_METERS))

  fun settings(): PanelSettings =
      UIPanelSettings(
          shape = QuadShapeOptions(VR_STROBE_PANEL_WIDTH_METERS, VR_STROBE_PANEL_HEIGHT_METERS),
          style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueStrobe),
          display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
          rendering =
              UIPanelRenderOptions(
                  PanelRenderMode.Layer(layerBlendType = PanelShapeLayerBlendType.OPAQUE)
              ),
          input = PanelInputOptions(ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR),
      )

  fun registration(bindings: SpatialVrStrobePanelRegistrationBindings): PanelRegistration =
      ComposeViewPanelRegistration(
          R.id.spatial_vr_strobe_panel,
          composeViewCreator = { _, context ->
            ComposeView(context).apply {
              setBackgroundColor(AndroidColor.rgb(10, 12, 18))
              alpha = 1.0f
              setWillNotDraw(false)
              setLayerType(View.LAYER_TYPE_HARDWARE, null)
              descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
              isFocusable = false
              isFocusableInTouchMode = false
              setContent {
                MaterialTheme(
                    colorScheme =
                        darkColorScheme(
                            primary = Color(0xFFFF4B8B),
                            onPrimary = Color(0xFF16040B),
                            secondary = Color(0xFF39FF14),
                            onSecondary = Color(0xFF031500),
                            tertiary = Color(0xFF25C7FF),
                            onTertiary = Color(0xFF001319),
                            background = Color(0xFF0A0C12),
                            onBackground = Color(0xFFB6FF9F),
                            surface = Color(0xFF171B25),
                            onSurface = Color(0xFF8FDCFF),
                            outline = Color(0xFF25C7FF),
                            error = Color(0xFFFF6B9F),
                        )
                ) {
                  Surface(
                      modifier =
                          Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                            SpatialVrStrobePanelInputPolicy.consumeAndroidKeyCode(
                                event.nativeKeyEvent.keyCode
                            )
                          },
                      color = MaterialTheme.colorScheme.background,
                      contentColor = MaterialTheme.colorScheme.onBackground,
                  ) {
                    VrStrobeControlPanel(bindings.actions)
                  }
                }
              }
            }
          },
          settingsCreator = { _: Entity -> settings() },
          panelSetupWithComposeView = { _, panel, _ -> bindings.onPanelSetup(panel) },
      )
}
