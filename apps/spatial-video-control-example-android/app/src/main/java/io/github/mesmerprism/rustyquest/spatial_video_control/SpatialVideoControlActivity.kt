package io.github.mesmerprism.rustyquest.spatial_video_control

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
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
import kotlin.math.sqrt
import org.json.JSONObject

private val PanelBackground = Color(0xFF141820)
private val PanelSurface = Color(0xFF202634)
private val PanelSurfaceAlt = Color(0xFF293142)
private val PanelInk = Color(0xFFF4F7FA)
private val PanelMuted = Color(0xFFAAB3C2)
private val PanelAccent = Color(0xFF63D2FF)
private val PanelWarm = Color(0xFFFFC857)
private val PanelBorder = Color(0xFF3B465A)

private data class ControlPanelPose(
    val pose: Pose,
    val source: String,
)

/**
 * Clean public Spatial SDK example. The default build has no Manifold binding
 * unless the closed native provider initializes successfully. The listener
 * remains disabled until the wearer reviews one unambiguous private address.
 */
@OptIn(SpatialSDKExperimentalAPI::class)
open class SpatialVideoControlActivity : AppSystemActivity(), DebugShellControlTarget {
  private lateinit var player: Media3SpatialPlayerAdapter
  private var control: AndroidTrustedLocalControlAdapter? = null
  private var controllerState by mutableStateOf(HeadsetControllerState())
  private var bindCandidate by
      mutableStateOf(PrivateAddressSelector.Candidate.unavailable())
  private var controlPanel: Entity? = null
  private var controlPanelSceneObject: PanelSceneObject? = null
  private var controlPanelComposeView: ComposeView? = null
  private var controlPanelVisible = true
  private var videoPanel: Entity? = null
  private var nextControllerStateRefreshMs = 0L
  private var nextBindCandidateRefreshMs = 0L
  private var operatorForeground = false
  private var lastSpatialRightPrimarySource: String? = null
  private val rightControllerPanelToggle =
      RightControllerPanelToggleArbiter(
          elapsedRealtimeMs = SystemClock::elapsedRealtime,
          onAcceptedPress = ::toggleControlPanelFromRightController,
      )

  /** Fails closed when the exact process-local native Manifold provider cannot initialize. */
  protected open fun manifoldAuthorityPort(): ManifoldAuthorityPort? =
      NativeManifoldAuthorityPort.createOrNull(this)

  /** Platform observation only; selection does not enable or authorize the listener. */
  protected open fun resolvePrivateAddressCandidate(): PrivateAddressSelector.Candidate =
      PrivateAddressSelector.selectHostAddress()

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
    if (BuildConfig.DEBUG) {
      DebugShellControlBridge.attach(this)
    }
    refreshPrivateAddressCandidate()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
    scene.spatialInterface.enableInput(true)
    val initialPose = controlPanelPoseFromWearer()
    controlPanel =
        Entity.createPanelEntity(
            R.id.local_control_panel,
            Transform(initialPose.pose),
            controlPanelDimensions(),
            controlPanelGrabbable(enabled = true),
            Visible(true),
        )
    android.util.Log.i(
        PANEL_LOG_TAG,
        "channel=trusted-local-http-v1 status=control-panel-spawned " +
            "panelFacingConvention=meta-panel-front-look-rotation-around-y " +
            "poseSource=${initialPose.source} panelDistanceMeters=$CONTROL_PANEL_DISTANCE_METERS " +
            "panelGrabbable=true panelGrabType=PIVOT_Y rightControllerA=toggle-and-recenter",
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
    operatorForeground = true
    refreshPrivateAddressCandidate()
    control?.setWearerForeground(true)
    control?.refreshVisibleState()
  }

  override fun onSceneTick() {
    super.onSceneTick()
    pollRightControllerPanelToggle()
    val now = SystemClock.elapsedRealtime()
    if (now >= nextControllerStateRefreshMs) {
      nextControllerStateRefreshMs = now + 500L
      control?.refreshVisibleState()
    }
    if (!controllerState.listenerEnabled && now >= nextBindCandidateRefreshMs) {
      nextBindCandidateRefreshMs = now + 2_000L
      refreshPrivateAddressCandidate()
    }
  }

  override fun onPause() {
    operatorForeground = false
    control?.setWearerForeground(false)
    super.onPause()
  }

  override fun onDestroy() {
    if (BuildConfig.DEBUG) {
      DebugShellControlBridge.detach(this)
    }
    control?.close()
    player.release()
    controlPanel = null
    controlPanelSceneObject = null
    controlPanelComposeView = null
    videoPanel = null
    super.onDestroy()
  }

  override fun debugStatus(): String = debugReceipt("status", confirmed = true)

  override fun debugEnable(accessMode: ManifoldAuthorityPort.AccessMode): String {
    check(BuildConfig.DEBUG) { "debug_shell_operator_disabled" }
    check(operatorForeground) { "foreground_activity_required" }
    val address = bindCandidate.address() ?: error("private_bind_address_unavailable")
    val adapter = control ?: error("manifold_authority_unavailable")
    adapter.enableFromDebugShell(address, accessMode)
    val confirmed =
        controllerState.listenerEnabled && controllerState.accessMode == accessMode
    return debugReceipt(
        if (accessMode == ManifoldAuthorityPort.AccessMode.PAIRED) {
          "enable_paired"
        } else {
          "enable_open_lan"
        },
        confirmed,
    )
  }

  override fun debugRevoke(): String {
    check(BuildConfig.DEBUG) { "debug_shell_operator_disabled" }
    control?.revokeFromHeadset("debug_shell_revoke")
    return debugReceipt("revoke", confirmed = !controllerState.listenerEnabled)
  }

  private fun debugReceipt(action: String, confirmed: Boolean): String {
    val playerState = player.snapshot()
    return JSONObject()
        .put("schema", "rusty.quest.debug_local_control_receipt.v1")
        .put("action", action)
        .put("phase", if (confirmed) "confirmed" else "pending")
        .put("confirmed", confirmed)
        .put("foreground", operatorForeground)
        .put("listener_enabled", controllerState.listenerEnabled)
        .put("access_mode", controllerState.accessMode?.protocolName() ?: JSONObject.NULL)
        .put("origin", controllerState.displayedAddress ?: JSONObject.NULL)
        .put("pairing_code", controllerState.pairingCode ?: JSONObject.NULL)
        .put("enable_expires_at", controllerState.enableExpiresAt?.toString() ?: JSONObject.NULL)
        .put("controller_connected", controllerState.controllerConnected)
        .put("authority_revision", controllerState.authorityRevision)
        .put("discovery_status", controllerState.discoveryStatus ?: JSONObject.NULL)
        .put("player_revision", playerState.revision())
        .put("selected_video_id", playerState.selectedVideoId() ?: JSONObject.NULL)
        .put("playing", playerState.playing())
        .put("playback_state", playerState.playbackState())
        .put("position_ms", playerState.positionMs())
        .toString()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val rightPrimary =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_1
    if (rightPrimary) {
      when (event.action) {
        KeyEvent.ACTION_DOWN -> {
          if (event.repeatCount == 0) {
            rightControllerPanelToggle.observe("android-right-controller-key", down = true)
          }
        }
        KeyEvent.ACTION_UP ->
            rightControllerPanelToggle.observe("android-right-controller-key", down = false)
      }
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    val controllerSource =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
    val primaryAction =
        event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    if (
        controllerSource &&
            primaryAction &&
            event.actionButton == MotionEvent.BUTTON_PRIMARY
    ) {
      rightControllerPanelToggle.observe(
          "android-right-controller-motion",
          down = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS,
      )
      return true
    }
    return super.dispatchGenericMotionEvent(event)
  }

  private fun controlPanelRegistration(): PanelRegistration =
      ComposeViewPanelRegistration(
          R.id.local_control_panel,
          composeViewCreator = { _, context ->
            ComposeView(context).apply {
              setBackgroundColor(AndroidColor.rgb(20, 24, 32))
              alpha = 1.0f
              setWillNotDraw(false)
              setLayerType(View.LAYER_TYPE_HARDWARE, null)
              setContent {
                MaterialTheme(colorScheme = controlPanelColorScheme()) {
                  ControlPanelContent()
                }
              }
            }
          },
          settingsCreator = {
            UIPanelSettings(
                shape =
                    QuadShapeOptions(
                        width = CONTROL_PANEL_WIDTH_METERS,
                        height = CONTROL_PANEL_HEIGHT_METERS,
                    ),
                style = PanelStyleOptions(themeResourceId = R.style.VideoControlPanelTheme),
                display = DpPerMeterDisplayOptions(dpPerMeter = CONTROL_PANEL_DP_PER_METER),
                rendering = UIPanelRenderOptions(PanelRenderMode.Layer()),
                input =
                    PanelInputOptions(
                        ButtonBits.ButtonTriggerL or
                            ButtonBits.ButtonTriggerR
                    ),
            )
          },
          panelSetupWithComposeView = { composeView, panel, _ ->
            controlPanelSceneObject = panel
            controlPanelComposeView = composeView
            applyControlPanelPresentation(controlPanelVisible)
          },
      )

  @androidx.compose.runtime.Composable
  private fun ControlPanelContent() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = PanelBackground,
        contentColor = PanelInk,
    ) {
      Column(modifier = Modifier.fillMaxSize().background(PanelBackground)) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .background(PanelSurface)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
              "Local browser control",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
          )
          Text(
              "Right controller A hides this panel; A again recenters it.",
              style = MaterialTheme.typography.bodyMedium,
              color = PanelMuted,
          )
        }
        HorizontalDivider(color = PanelBorder)
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Surface(
              modifier = Modifier.fillMaxWidth().border(1.dp, PanelBorder, RoundedCornerShape(8.dp)),
              shape = RoundedCornerShape(8.dp),
              color = PanelSurface,
              contentColor = PanelInk,
          ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                  if (controllerState.listenerEnabled) controllerState.status else "Listener disabled",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                  color =
                      if (controllerState.accessMode ==
                          ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE) {
                        PanelWarm
                      } else if (controllerState.listenerEnabled) {
                        PanelAccent
                      } else {
                        PanelInk
                      },
              )
              Text(
                  controllerState.displayedAddress
                      ?: if (control == null) {
                        "The process-local Manifold authority is unavailable."
                      } else {
                        bindCandidate.displayText()
                      },
                  style = MaterialTheme.typography.bodyLarge,
              )
              Text(
                  when {
                    controllerState.pairingCode != null ->
                        "Single-use code  ${controllerState.pairingCode}"
                    controllerState.accessMode ==
                        ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE ->
                        "UNSAFE: no pairing code required"
                    else -> "No pairing code active"
                  },
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                  color = if (controllerState.pairingCode != null) PanelWarm else PanelMuted,
              )
            }
          }
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (controllerState.controllerConnected) {
                  "Controller connected: ${controllerState.controllerLabel}"
                } else {
                  "No browser controller connected"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            controllerState.lastRevocationCause?.let { cause ->
              Text(
                  "Last revoke: $cause",
                  style = MaterialTheme.typography.bodyMedium,
                  color = PanelMuted,
              )
            }
            Text(
                if (controllerState.accessMode ==
                    ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE) {
                  "UNSAFE OPEN LAN: anyone on this network may claim control. Traffic is not encrypted."
                } else {
                  "Trusted-LAN authentication only; traffic is not confidential."
                },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (controllerState.accessMode ==
                        ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE) {
                      PanelWarm
                    } else {
                      PanelMuted
                    },
            )
            controllerState.discoveryStatus?.let { discovery ->
              Text(discovery, style = MaterialTheme.typography.bodySmall, color = PanelMuted)
            }
          }
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Button(
                modifier = Modifier.height(52.dp),
                enabled =
                    control != null &&
                        bindCandidate.available() &&
                        !controllerState.listenerEnabled,
                shape = RoundedCornerShape(8.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = PanelAccent,
                        contentColor = Color(0xFF04111A),
                        disabledContainerColor = PanelSurfaceAlt,
                        disabledContentColor = PanelMuted,
                    ),
                onClick = {
                  bindCandidate.address()?.let { address -> control?.enableFromWearer(address) }
                },
            ) {
              Text("Paired control", fontWeight = FontWeight.SemiBold)
            }
            Button(
                modifier = Modifier.height(52.dp),
                enabled =
                    control != null &&
                        bindCandidate.available() &&
                        !controllerState.listenerEnabled,
                shape = RoundedCornerShape(8.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = PanelWarm,
                        contentColor = Color(0xFF291A00),
                        disabledContainerColor = PanelSurfaceAlt,
                        disabledContentColor = PanelMuted,
                    ),
                onClick = {
                  bindCandidate.address()?.let { address ->
                    control?.enableOpenLanFromWearer(address)
                  }
                },
            ) {
              Text("Open LAN (unsafe)", fontWeight = FontWeight.SemiBold)
            }
          }
          Button(
              modifier = Modifier.height(52.dp),
              enabled = controllerState.listenerEnabled,
              shape = RoundedCornerShape(8.dp),
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = PanelSurfaceAlt,
                      contentColor = PanelInk,
                      disabledContainerColor = PanelSurfaceAlt,
                      disabledContentColor = PanelMuted,
                  ),
              onClick = { control?.revokeFromHeadset() },
          ) {
            Text("Revoke network control", fontWeight = FontWeight.SemiBold)
          }
          Text(
              "Point and use either trigger. Hold a grip button to move the panel.",
              style = MaterialTheme.typography.bodyMedium,
              color = PanelMuted,
          )
        }
      }
    }
  }

  private fun controlPanelColorScheme() =
      darkColorScheme(
          primary = PanelAccent,
          onPrimary = Color(0xFF04111A),
          background = PanelBackground,
          onBackground = PanelInk,
          surface = PanelSurface,
          onSurface = PanelInk,
          surfaceVariant = PanelSurfaceAlt,
          onSurfaceVariant = PanelMuted,
      )

  private fun pollRightControllerPanelToggle() {
    val state = runCatching { RightControllerPrimaryStateReader.read(scene) }.getOrNull()
    if (state == null) {
      lastSpatialRightPrimarySource?.let(rightControllerPanelToggle::release)
      lastSpatialRightPrimarySource = null
      return
    }
    if (lastSpatialRightPrimarySource != state.source) {
      lastSpatialRightPrimarySource?.let(rightControllerPanelToggle::release)
      lastSpatialRightPrimarySource = state.source
    }
    rightControllerPanelToggle.observe(state.source, state.down)
  }

  private fun toggleControlPanelFromRightController(inputSource: String) {
    setControlPanelVisible(
        visible = !controlPanelVisible,
        recenter = !controlPanelVisible,
        source = inputSource,
    )
  }

  private fun setControlPanelVisible(
      visible: Boolean,
      recenter: Boolean,
      source: String,
  ) {
    var poseSource = "retained-grabbed-pose"
    if (visible && recenter) {
      val placement = controlPanelPoseFromWearer()
      controlPanel?.setComponent(Transform(placement.pose))
      poseSource = placement.source
    }
    controlPanelVisible = visible
    controlPanel?.setComponent(controlPanelGrabbable(enabled = visible))
    controlPanel?.setComponent(Visible(visible))
    applyControlPanelPresentation(visible)
    android.util.Log.i(
        PANEL_LOG_TAG,
        "channel=trusted-local-http-v1 status=control-panel-visibility-applied " +
            "visible=$visible source=$source recentered=${visible && recenter} " +
            "poseSource=$poseSource rightControllerAActionAuthority=panel-toggle-arbiter",
    )
  }

  private fun applyControlPanelPresentation(visible: Boolean) {
    runCatching { controlPanelSceneObject?.setIsVisible(visible) }
    controlPanelComposeView?.let { composeView ->
      runOnUiThread {
        composeView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        composeView.isEnabled = visible
        composeView.isClickable = visible
      }
    }
  }

  private fun controlPanelGrabbable(enabled: Boolean): Grabbable =
      Grabbable(
          enabled = enabled,
          type = GrabbableType.PIVOT_Y,
          minHeight = CONTROL_PANEL_GRAB_MIN_HEIGHT_METERS,
          maxHeight = CONTROL_PANEL_GRAB_MAX_HEIGHT_METERS,
      )

  private fun controlPanelDimensions(): PanelDimensions =
      PanelDimensions(Vector2(CONTROL_PANEL_WIDTH_METERS, CONTROL_PANEL_HEIGHT_METERS))

  private fun controlPanelPoseFromWearer(): ControlPanelPose {
    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull()
    if (viewerPose == null) {
      return ControlPanelPose(
          pose =
              Pose(
                  Vector3(0.0f, CONTROL_PANEL_FALLBACK_Y_METERS, -CONTROL_PANEL_DISTANCE_METERS),
                  staticControlPanelFrontRotation(),
              ),
          source = "static-fallback",
      )
    }
    val rawForward = viewerPose.forward()
    val length =
        sqrt(
            rawForward.x * rawForward.x +
                rawForward.y * rawForward.y +
                rawForward.z * rawForward.z
        )
    val direction =
        if (length > 0.0001f) {
          Vector3(rawForward.x / length, rawForward.y / length, rawForward.z / length)
        } else {
          Vector3(0.0f, 0.0f, -1.0f)
        }
    val center = viewerPose.t + direction * CONTROL_PANEL_DISTANCE_METERS
    return ControlPanelPose(
        pose = Pose(center, Quaternion.lookRotationAroundY(direction)),
        source = "viewer-relative-recenter",
    )
  }

  private fun refreshPrivateAddressCandidate() {
    if (!controllerState.listenerEnabled) {
      bindCandidate = resolvePrivateAddressCandidate()
    }
  }

  /** Known-facing fallback from the Rusty Quest one-sided Spatial UI-panel convention. */
  private fun staticControlPanelFrontRotation(): Quaternion = Quaternion(0.0f, 180.0f, 0.0f)

  private companion object {
    const val PANEL_LOG_TAG = "RustyQuestVideoControl"
    const val CONTROL_PANEL_WIDTH_METERS = 0.92f
    const val CONTROL_PANEL_HEIGHT_METERS = 0.72f
    const val CONTROL_PANEL_DISTANCE_METERS = 1.0f
    const val CONTROL_PANEL_FALLBACK_Y_METERS = 1.35f
    const val CONTROL_PANEL_DP_PER_METER = 720.0f
    const val CONTROL_PANEL_GRAB_MIN_HEIGHT_METERS = 0.55f
    const val CONTROL_PANEL_GRAB_MAX_HEIGHT_METERS = 2.50f
  }
}
