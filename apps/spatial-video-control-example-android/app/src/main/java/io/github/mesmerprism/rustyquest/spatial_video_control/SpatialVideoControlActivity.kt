package io.github.mesmerprism.rustyquest.spatial_video_control

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Panel
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.ButtonBits
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionControls
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import java.net.InetAddress

/**
 * Clean public Spatial SDK example. The default build has no Manifold binding
 * or bind address, so its listener remains visibly unavailable and disabled.
 */
open class SpatialVideoControlActivity : AppSystemActivity() {
  private lateinit var player: Media3SpatialPlayerAdapter
  private var control: AndroidTrustedLocalControlAdapter? = null
  private var controllerState by mutableStateOf(HeadsetControllerState())
  private var controlPanel: Entity? = null
  private var videoPanel: Entity? = null
  private var nextControllerStateRefreshMs = 0L

  /** A production build must return its explicit process-local Manifold adapter. */
  protected open fun manifoldAuthorityPort(): ManifoldAuthorityPort? = null

  /** A production build must return the wearer-reviewed LAN/private-hotspot bind address. */
  protected open fun listenerBindAddress(): InetAddress? = null

  override fun registerFeatures(): List<SpatialFeature> =
      listOf(
          VRFeature(this, LocomotionControls.Right, false, VrInputSystemType.INTERACTION_SDK),
          ComposeFeature(),
      )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    check(!BuildConfig.TRUSTED_LOCAL_HTTP_ENABLED_DEFAULT)
    player = Media3SpatialPlayerAdapter(this)
    control =
        manifoldAuthorityPort()?.let { authority ->
          AndroidTrustedLocalControlAdapter(this, authority, player) { next ->
            runOnUiThread { controllerState = next }
          }
        }
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    controlPanel =
        Entity.createPanelEntity(
            R.id.local_control_panel,
            Transform(Pose(Vector3(-0.65f, 1.25f, -2.0f), Quaternion())),
            PanelDimensions(Vector2(0.80f, 0.58f)),
            Visible(true),
        )
    videoPanel =
        Entity.create(
            Panel(R.id.video_surface_panel),
            Transform(Pose(Vector3(0.55f, 1.30f, -2.25f), Quaternion())),
            Visible(true),
        )
  }

  override fun registerPanels(): List<PanelRegistration> =
      listOf(
          controlPanelRegistration(),
          VideoSurfacePanelRegistration(
              R.id.video_surface_panel,
              surfaceConsumer = { _, surface -> player.attachVideoSurface(surface) },
              settingsCreator = {
                MediaPanelSettings(
                    shape = QuadShapeOptions(width = 1.45f, height = 0.82f),
                    display = PixelDisplayOptions(width = 320, height = 180),
                    rendering = MediaPanelRenderOptions(),
                    input = PanelInputOptions(0),
                )
              },
          ),
      )

  override fun onResume() {
    super.onResume()
    control?.setWearerForeground(true)
    control?.refreshVisibleState()
  }

  override fun onSceneTick() {
    super.onSceneTick()
    val now = SystemClock.elapsedRealtime()
    if (now >= nextControllerStateRefreshMs) {
      nextControllerStateRefreshMs = now + 500L
      control?.refreshVisibleState()
    }
  }

  override fun onPause() {
    control?.setWearerForeground(false)
    super.onPause()
  }

  override fun onDestroy() {
    control?.close()
    player.release()
    controlPanel = null
    videoPanel = null
    super.onDestroy()
  }

  private fun controlPanelRegistration(): PanelRegistration =
      ComposeViewPanelRegistration(
          R.id.local_control_panel,
          composeViewCreator = { _, context ->
            ComposeView(context).apply {
              setBackgroundColor(AndroidColor.rgb(8, 30, 38))
              alpha = 1.0f
              setWillNotDraw(false)
              setLayerType(View.LAYER_TYPE_HARDWARE, null)
              setContent {
                MaterialTheme {
                  Column(
                      modifier = Modifier.fillMaxSize().padding(24.dp),
                      verticalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    Text("Local browser control")
                    Text(
                        if (controllerState.listenerEnabled) {
                          controllerState.status
                        } else {
                          "Disabled"
                        }
                    )
                    Text(
                        controllerState.displayedAddress
                            ?: "Bind a real Manifold authority and reviewed network address."
                    )
                    Text(
                        controllerState.pairingCode?.let { "Single-use code: $it" }
                            ?: "No pairing code active."
                    )
                    Text(
                        if (controllerState.controllerConnected) {
                          "Controller: ${controllerState.controllerLabel}"
                        } else {
                          "No controller connected."
                        }
                    )
                    Text("Authenticated trusted-LAN control; no confidentiality.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                      Button(
                          enabled =
                              control != null &&
                                  listenerBindAddress() != null &&
                                  !controllerState.listenerEnabled,
                          onClick = {
                            listenerBindAddress()?.let { address ->
                              control?.enableFromWearer(address)
                            }
                          },
                      ) {
                        Text("Enable 2 minutes")
                      }
                      Button(
                          enabled = controllerState.listenerEnabled,
                          onClick = { control?.revokeFromHeadset() },
                      ) {
                        Text("Revoke")
                      }
                    }
                  }
                }
              }
            }
          },
          settingsCreator = {
            UIPanelSettings(
                shape = QuadShapeOptions(width = 0.80f, height = 0.58f),
                style = PanelStyleOptions(themeResourceId = R.style.VideoControlPanelTheme),
                display = DpPerMeterDisplayOptions(dpPerMeter = 1100.0f),
                rendering = UIPanelRenderOptions(PanelRenderMode.Layer()),
                input =
                    PanelInputOptions(
                        ButtonBits.ButtonA or
                            ButtonBits.ButtonTriggerL or
                            ButtonBits.ButtonTriggerR
                    ),
            )
          },
      )
}
