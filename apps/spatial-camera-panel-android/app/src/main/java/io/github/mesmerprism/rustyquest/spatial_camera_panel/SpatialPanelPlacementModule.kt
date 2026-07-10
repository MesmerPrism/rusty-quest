package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import kotlin.math.sqrt

internal const val PANEL_WIDTH_METERS = 1.20f
internal const val PANEL_HEIGHT_METERS = 1.254f
internal const val PANEL_DP_PER_METER = 720f
internal const val PANEL_FOCUS_Y_METERS = 1.1f
internal const val PANEL_FOCUS_Z_METERS = 0.475f
internal const val PANEL_WORLD_Y_MIN_METERS = 0.8f
internal const val PANEL_WORLD_Y_MAX_METERS = 2.2f
internal const val PANEL_WORLD_Z_MIN_METERS = -3.2f
internal const val PANEL_WORLD_Z_MAX_METERS = 0.8f
internal const val PANEL_HEADLOCK_TUNING_FILE = "spatial_camera_panel_headlock_tuning.json"
internal const val PANEL_HEADLOCK_OFFSET_X_METERS = 0.0f
internal const val PANEL_HEADLOCK_OFFSET_Y_METERS = 0.0f
internal const val PANEL_HEADLOCK_DISTANCE_METERS = 1.40f
internal const val PANEL_HEADLOCK_SCALE = 0.65f
internal const val PANEL_FRONT_OF_CAMERA_VIDEO_DISTANCE_METERS = 0.72f
internal const val PANEL_FRONT_OF_CAMERA_VIDEO_SCALE = 0.65f
internal const val PRIVATE_LAYER_PANEL_OFFSET_X_METERS = 0.0f
internal const val PRIVATE_LAYER_PANEL_OFFSET_Y_METERS = 0.0f
internal const val PRIVATE_LAYER_PANEL_DISTANCE_METERS = 1.0f
internal const val PRIVATE_LAYER_PANEL_SCALE = 0.65f
internal const val PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS = 0.18f
internal const val PRIVATE_LAYER_PANEL_SCALE_MIN = 0.15f
internal const val PRIVATE_LAYER_PANEL_MAX_LATERAL_DISTANCE_FRACTION = 0.80f
internal const val PRIVATE_LAYER_PANEL_GRAB_MIN_HEIGHT_METERS = 0.55f
internal const val PRIVATE_LAYER_PANEL_GRAB_MAX_HEIGHT_METERS = 2.50f
internal const val PRIVATE_LAYER_PANEL_LAYER_Z_INDEX = 99
internal const val PRIVATE_LAYER_PANEL_SDK_FREE_TRANSFORM = true
internal const val PANEL_HEADLOCK_OFFSET_X_MIN_METERS = -0.85f
internal const val PANEL_HEADLOCK_OFFSET_X_MAX_METERS = 0.85f
internal const val PANEL_HEADLOCK_OFFSET_Y_MIN_METERS = -0.65f
internal const val PANEL_HEADLOCK_OFFSET_Y_MAX_METERS = 0.65f
internal const val PANEL_HEADLOCK_DISTANCE_MIN_METERS = 0.35f
internal const val PANEL_HEADLOCK_DISTANCE_MAX_METERS = 1.50f
internal const val PANEL_HEADLOCK_SCALE_MIN = 0.65f
internal const val PANEL_HEADLOCK_SCALE_MAX = 1.60f
internal const val PANEL_HEADLOCK_ENABLED_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.enabled"
internal const val PANEL_HEADLOCK_OFFSET_X_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_x_m"
internal const val PANEL_HEADLOCK_OFFSET_Y_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.offset_y_m"
internal const val PANEL_HEADLOCK_DISTANCE_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.distance_meters"
internal const val PANEL_HEADLOCK_WIDTH_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.width_meters"
internal const val PANEL_HEADLOCK_HEIGHT_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.height_meters"
internal const val PANEL_HEADLOCK_SCALE_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.scale"
internal const val PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.enabled"
internal const val PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.translate_rate_mps"
internal const val PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.distance_rate_mps"
internal const val PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PROPERTY =
    "debug.rustyquest.spatial_camera_panel.panel.headlocked.joystick.scale_rate_per_second"
internal const val PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_METERS_PER_SECOND = 0.18f
internal const val PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_METERS_PER_SECOND = 0.16f
internal const val PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PER_SECOND = 0.30f
internal const val PANEL_HEADLOCK_JOYSTICK_DEADZONE = 0.14f
internal const val PRIVATE_LAYER_PANEL_GRABBABLE_MARKER_INTERVAL_MS = 450L
internal const val PANEL_HEADLOCK_JOYSTICK_MARKER_INTERVAL_MS = 450L
internal const val PANEL_HEADLOCK_MARKER_INTERVAL_MS = 900L
internal const val PANEL_WIDTH_MIN_METERS = 1.20f
internal const val PANEL_WIDTH_MAX_METERS = 2.60f
internal const val PANEL_HEIGHT_MIN_METERS = 0.75f
internal const val PANEL_HEIGHT_MAX_METERS = 1.65f
internal const val PANEL_LAUNCHER_WIDTH_METERS = 0.78f
internal const val PANEL_LAUNCHER_HEIGHT_METERS = 0.30f
internal const val PANEL_LAUNCHER_DP_PER_METER = 680f
internal const val PANEL_LAUNCHER_X_METERS = -0.62f
internal const val PANEL_LAUNCHER_Y_METERS = 0.92f
internal const val PANEL_LAUNCHER_Z_METERS = 0.525f

internal data class SpatialPanelHeadlockMarkerInput(
    val activePlacement: PanelPlacement,
    val privateLayerPanelVisible: Boolean,
    val cameraTargetDistanceMeters: Float,
    val projectionInputClearanceActive: Boolean,
    val projectionInputCarrierBehindPrivatePanel: Boolean,
    val cameraProjectionLayerZIndex: Int,
)

internal object SpatialPanelPlacementModule {
  fun initialPrivateLayerPlacement(): PanelPlacement =
      PanelPlacement(
          visible = false,
          headlocked = true,
          xMeters = PRIVATE_LAYER_PANEL_OFFSET_X_METERS,
          yMeters = PRIVATE_LAYER_PANEL_OFFSET_Y_METERS,
          zMeters = PRIVATE_LAYER_PANEL_DISTANCE_METERS,
          scale = PRIVATE_LAYER_PANEL_SCALE,
      )

  fun adjustWorkflowPlacement(
      placement: PanelPlacement,
      deltaX: Float,
      deltaY: Float,
      deltaZ: Float,
      deltaScale: Float,
  ): PanelPlacement {
    val yRange =
        if (placement.headlocked) {
          PANEL_HEADLOCK_OFFSET_Y_MIN_METERS..PANEL_HEADLOCK_OFFSET_Y_MAX_METERS
        } else {
          PANEL_WORLD_Y_MIN_METERS..PANEL_WORLD_Y_MAX_METERS
        }
    val zRange =
        if (placement.headlocked) {
          PANEL_HEADLOCK_DISTANCE_MIN_METERS..PANEL_HEADLOCK_DISTANCE_MAX_METERS
        } else {
          PANEL_WORLD_Z_MIN_METERS..PANEL_WORLD_Z_MAX_METERS
        }
    return placement.copy(
        xMeters =
            (placement.xMeters + deltaX)
                .coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
        yMeters = (placement.yMeters + deltaY).coerceIn(yRange.start, yRange.endInclusive),
        zMeters = (placement.zMeters + deltaZ).coerceIn(zRange.start, zRange.endInclusive),
        scale = (placement.scale + deltaScale).coerceIn(0.65f, 1.6f),
    )
  }

  fun resizeWorkflowPanel(
      placement: PanelPlacement,
      deltaWidth: Float,
      deltaHeight: Float,
  ): PanelPlacement =
      placement.copy(
          widthMeters =
              (placement.widthMeters + deltaWidth).coerceIn(PANEL_WIDTH_MIN_METERS, PANEL_WIDTH_MAX_METERS),
          heightMeters =
              (placement.heightMeters + deltaHeight).coerceIn(PANEL_HEIGHT_MIN_METERS, PANEL_HEIGHT_MAX_METERS),
      )

  fun resetWorkflowPanelPlacement(placement: PanelPlacement): PanelPlacement =
      placement.copy(
          visible = true,
          xMeters = PANEL_HEADLOCK_OFFSET_X_METERS,
          yMeters = if (placement.headlocked) PANEL_HEADLOCK_OFFSET_Y_METERS else PANEL_FOCUS_Y_METERS,
          zMeters =
              if (placement.headlocked) PANEL_FRONT_OF_CAMERA_VIDEO_DISTANCE_METERS
              else PANEL_FOCUS_Z_METERS,
          scale = if (placement.headlocked) PANEL_FRONT_OF_CAMERA_VIDEO_SCALE else 1.0f,
          widthMeters = PANEL_WIDTH_METERS,
          heightMeters = PANEL_HEIGHT_METERS,
      )

  fun setWorkflowHeadlocked(placement: PanelPlacement, enabled: Boolean): PanelPlacement =
      if (enabled) {
        placement.copy(
            headlocked = true,
            xMeters =
                placement.xMeters.coerceIn(
                    PANEL_HEADLOCK_OFFSET_X_MIN_METERS,
                    PANEL_HEADLOCK_OFFSET_X_MAX_METERS,
                ),
            yMeters =
                placement.yMeters.coerceIn(
                    PANEL_HEADLOCK_OFFSET_Y_MIN_METERS,
                    PANEL_HEADLOCK_OFFSET_Y_MAX_METERS,
                ),
            zMeters =
                placement.zMeters.coerceIn(
                    PANEL_HEADLOCK_DISTANCE_MIN_METERS,
                    PANEL_HEADLOCK_DISTANCE_MAX_METERS,
                ),
        )
      } else {
        placement.copy(
            headlocked = false,
            yMeters = PANEL_FOCUS_Y_METERS,
            zMeters = PANEL_FOCUS_Z_METERS,
        )
      }

  fun hotloadedWorkflowPlacement(placement: PanelPlacement): PanelPlacement {
    val requestedHeadlocked =
        activityReadOptionalBooleanSystemProperty(PANEL_HEADLOCK_ENABLED_PROPERTY)
            ?: placement.headlocked
    return placement.copy(
        headlocked = requestedHeadlocked,
        xMeters =
            (activityReadOptionalFloatSystemProperty(
                    PANEL_HEADLOCK_OFFSET_X_PROPERTY,
                    PANEL_HEADLOCK_OFFSET_X_MIN_METERS,
                    PANEL_HEADLOCK_OFFSET_X_MAX_METERS,
                )
                ?: placement.xMeters)
                .coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
        yMeters =
            (activityReadOptionalFloatSystemProperty(
                    PANEL_HEADLOCK_OFFSET_Y_PROPERTY,
                    PANEL_HEADLOCK_OFFSET_Y_MIN_METERS,
                    PANEL_HEADLOCK_OFFSET_Y_MAX_METERS,
                )
                ?: placement.yMeters)
                .coerceIn(
                    if (requestedHeadlocked) PANEL_HEADLOCK_OFFSET_Y_MIN_METERS
                    else PANEL_WORLD_Y_MIN_METERS,
                    if (requestedHeadlocked) PANEL_HEADLOCK_OFFSET_Y_MAX_METERS
                    else PANEL_WORLD_Y_MAX_METERS,
                ),
        zMeters =
            (activityReadOptionalFloatSystemProperty(
                    PANEL_HEADLOCK_DISTANCE_PROPERTY,
                    PANEL_HEADLOCK_DISTANCE_MIN_METERS,
                    PANEL_HEADLOCK_DISTANCE_MAX_METERS,
                )
                ?: placement.zMeters)
                .coerceIn(
                    if (requestedHeadlocked) PANEL_HEADLOCK_DISTANCE_MIN_METERS
                    else PANEL_WORLD_Z_MIN_METERS,
                    if (requestedHeadlocked) PANEL_HEADLOCK_DISTANCE_MAX_METERS
                    else PANEL_WORLD_Z_MAX_METERS,
                ),
        widthMeters =
            (activityReadOptionalFloatSystemProperty(
                    PANEL_HEADLOCK_WIDTH_PROPERTY,
                    PANEL_WIDTH_MIN_METERS,
                    PANEL_WIDTH_MAX_METERS,
                )
                ?: placement.widthMeters)
                .coerceIn(PANEL_WIDTH_MIN_METERS, PANEL_WIDTH_MAX_METERS),
        heightMeters =
            (activityReadOptionalFloatSystemProperty(
                    PANEL_HEADLOCK_HEIGHT_PROPERTY,
                    PANEL_HEIGHT_MIN_METERS,
                    PANEL_HEIGHT_MAX_METERS,
                )
                ?: placement.heightMeters)
                .coerceIn(PANEL_HEIGHT_MIN_METERS, PANEL_HEIGHT_MAX_METERS),
        scale =
            (activityReadOptionalFloatSystemProperty(
                    PANEL_HEADLOCK_SCALE_PROPERTY,
                    PANEL_HEADLOCK_SCALE_MIN,
                    PANEL_HEADLOCK_SCALE_MAX,
                )
                ?: placement.scale)
                .coerceIn(PANEL_HEADLOCK_SCALE_MIN, PANEL_HEADLOCK_SCALE_MAX),
    )
  }

  fun coercePrivateLayerPanelPlacement(placement: PanelPlacement): PanelPlacement {
    val distance =
        placement.zMeters.coerceIn(
            PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS,
            PANEL_HEADLOCK_DISTANCE_MAX_METERS,
        )
    val maxLateral =
        (distance * PRIVATE_LAYER_PANEL_MAX_LATERAL_DISTANCE_FRACTION).coerceAtLeast(0.05f)
    val lateralLength =
        sqrt((placement.xMeters * placement.xMeters + placement.yMeters * placement.yMeters).toDouble())
            .toFloat()
    val lateralScale =
        if (lateralLength > maxLateral && lateralLength > 0.000001f) {
          maxLateral / lateralLength
        } else {
          1.0f
        }
    return placement.copy(
        xMeters =
            (placement.xMeters * lateralScale)
                .coerceIn(PANEL_HEADLOCK_OFFSET_X_MIN_METERS, PANEL_HEADLOCK_OFFSET_X_MAX_METERS),
        yMeters =
            (placement.yMeters * lateralScale)
                .coerceIn(PANEL_HEADLOCK_OFFSET_Y_MIN_METERS, PANEL_HEADLOCK_OFFSET_Y_MAX_METERS),
        zMeters = distance,
        scale = placement.scale.coerceIn(PRIVATE_LAYER_PANEL_SCALE_MIN, PANEL_HEADLOCK_SCALE_MAX),
        widthMeters = placement.widthMeters.coerceIn(PANEL_WIDTH_MIN_METERS, PANEL_WIDTH_MAX_METERS),
        heightMeters = placement.heightMeters.coerceIn(PANEL_HEIGHT_MIN_METERS, PANEL_HEIGHT_MAX_METERS),
    )
  }

  fun workflowWorldPose(placement: PanelPlacement): Pose =
      Pose(
          Vector3(placement.xMeters, placement.yMeters, placement.zMeters),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

  fun panelLauncherPose(): Pose =
      Pose(
          Vector3(PANEL_LAUNCHER_X_METERS, PANEL_LAUNCHER_Y_METERS, PANEL_LAUNCHER_Z_METERS),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

  fun privateLayerPanelWorldPose(placement: PanelPlacement): Pose =
      Pose(
          Vector3(placement.xMeters, placement.yMeters, placement.zMeters),
          Quaternion(6.12323426e-17f, 6.12323426e-17f, 1.0f, -3.74939976e-33f),
      )

  fun privateLayerPanelGrabbable(enabled: Boolean): Grabbable =
      Grabbable(
          enabled = enabled,
          type = GrabbableType.PIVOT_Y,
          minHeight = PRIVATE_LAYER_PANEL_GRAB_MIN_HEIGHT_METERS,
          maxHeight = PRIVATE_LAYER_PANEL_GRAB_MAX_HEIGHT_METERS,
      )

  fun panelDimensions(placement: PanelPlacement): PanelDimensions =
      PanelDimensions(Vector2(placement.widthMeters, placement.heightMeters))

  fun privateLayerPanelDimensions(placement: PanelPlacement): PanelDimensions =
      PanelDimensions(Vector2(placement.widthMeters, placement.heightMeters))

  fun panelLauncherDimensions(): PanelDimensions =
      PanelDimensions(Vector2(PANEL_LAUNCHER_WIDTH_METERS, PANEL_LAUNCHER_HEIGHT_METERS))

  fun privateLayerPanelSettings(): PanelSettings =
      UIPanelSettings(
          shape = QuadShapeOptions(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS),
          style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
          display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
          rendering = UIPanelRenderOptions(PanelRenderMode.Layer()),
          input =
              PanelInputOptions(
                  ButtonBits.ButtonA or ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
              ),
      )

  fun particleLayerVisibleForPanelMode(
      workflowPanelVisible: Boolean,
      privateLayerPanelVisible: Boolean,
      cameraStackSuppressesParticles: Boolean,
      nativeSurfaceParticleLayerEnabled: Boolean,
  ): Boolean =
      !workflowPanelVisible &&
          !privateLayerPanelVisible &&
          !cameraStackSuppressesParticles &&
          nativeSurfaceParticleLayerEnabled

  fun launcherPanelVisibleForPanelMode(
      panelShellVisible: Boolean,
      panelLauncherVisible: Boolean,
      workflowPanelVisible: Boolean,
      privateLayerPanelVisible: Boolean,
      cameraStackSuppressesParticles: Boolean,
      spatialVirtualRoomEnabled: Boolean,
  ): Boolean =
      panelShellVisible &&
          panelLauncherVisible &&
          !workflowPanelVisible &&
          !privateLayerPanelVisible &&
          !cameraStackSuppressesParticles &&
          !spatialVirtualRoomEnabled

  fun legacyLauncherPanelSuppressedForCameraStack(
      cameraStackSuppressesParticles: Boolean,
      spatialVirtualRoomEnabled: Boolean,
  ): Boolean = cameraStackSuppressesParticles || spatialVirtualRoomEnabled

  fun panelStateToken(
      panelShellVisible: Boolean,
      privateLayerPanelVisible: Boolean,
      workflowPanelVisible: Boolean,
  ): String =
      when {
        !panelShellVisible -> "spatial-sdk-panel-shell-hidden"
        privateLayerPanelVisible -> "spatial-sdk-private-layer-panel-open"
        workflowPanelVisible -> "spatial-sdk-workflow-panel-open"
        else -> "spatial-sdk-particle-view-panel-closed"
      }

  fun headlockMarkerFields(input: SpatialPanelHeadlockMarkerInput): String {
    val placement = input.activePlacement
    val activePanelToken =
        if (input.privateLayerPanelVisible) "private-layer-panel" else "workflow-panel"
    val distanceMode =
        if (input.privateLayerPanelVisible) {
          "left-stick-stored-placement"
        } else {
          "viewer-forward-distance"
        }
    return "headlockedPanelEnabled=${placement.headlocked} " +
        "headlockedPanelDefaultEnabled=true " +
        "activeHeadlockedPanel=${activityMarkerToken(activePanelToken)} " +
        "headlockedPanelOffsetXMeters=${activityMarkerFloat(placement.xMeters)} " +
        "headlockedPanelOffsetYMeters=${activityMarkerFloat(placement.yMeters)} " +
        "headlockedPanelDistanceMeters=${activityMarkerFloat(placement.zMeters)} " +
        "headlockedPanelDistanceMode=${activityMarkerToken(distanceMode)} " +
        "privateLayerPanelWorldSpace=true " +
        "privateLayerPanelPoseSource=initial-headset-facing-world-space-then-stored-placement-unless-grabbed " +
        "privateLayerPanelLayerConfig=enabled " +
        "privateLayerPanelLayerZIndex=$PRIVATE_LAYER_PANEL_LAYER_Z_INDEX " +
        "privateLayerPanelGrabbable=true " +
        "privateLayerPanelGrabType=PIVOT_Y " +
        "privateLayerPanelTransformAuthority=app-stored-placement-unless-grabbed " +
        "privateLayerPanelForcedDistanceDisabled=false " +
        "composeDragPanelMovement=false " +
        "panelRenderOrder=spatial-sdk-quad-layer-z-index " +
        "panelOpensInFrontOfCameraVideo=${placement.zMeters < CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS} " +
        "panelDistanceLessThanCameraProjection=${placement.zMeters < input.cameraTargetDistanceMeters} " +
        "projectionPanelInputClearanceActive=${input.projectionInputClearanceActive} " +
        "projectionPanelInputBehindPrivateLayerPanel=${input.projectionInputCarrierBehindPrivatePanel} " +
        "projectionPanelInputClearanceMeters=${activityMarkerFloat(CAMERA_HWB_PROJECTION_PRIVATE_PANEL_INPUT_CLEARANCE_METERS)} " +
        "cameraVideoProjectionLayerZIndex=${input.cameraProjectionLayerZIndex} " +
        "privateLayerPanelAboveCameraProjectionLayer=quad-layer-z-index " +
        "privateLayerPanelRenderMode=spatial-sdk-layer " +
        "panelRenderOrderProof=ui-panel-quad-layer-high-z-over-projection-test " +
        "rightStickSideFlickPanelMoveDisabled=true " +
        "privateLayerPanelDistancePersistsAcrossToggle=true " +
        "panelScale=${activityMarkerFloat(placement.scale)} " +
        "panelWidth=${activityMarkerFloat(placement.widthMeters)} " +
        "panelHeight=${activityMarkerFloat(placement.heightMeters)}"
  }

  fun headlockPropertyMarkerFields(): String =
      "headlockedPanelEnabledProperty=$PANEL_HEADLOCK_ENABLED_PROPERTY " +
          "headlockedPanelOffsetXProperty=$PANEL_HEADLOCK_OFFSET_X_PROPERTY " +
          "headlockedPanelOffsetYProperty=$PANEL_HEADLOCK_OFFSET_Y_PROPERTY " +
          "headlockedPanelDistanceProperty=$PANEL_HEADLOCK_DISTANCE_PROPERTY " +
          "headlockedPanelWidthProperty=$PANEL_HEADLOCK_WIDTH_PROPERTY " +
          "headlockedPanelHeightProperty=$PANEL_HEADLOCK_HEIGHT_PROPERTY " +
          "headlockedPanelScaleProperty=$PANEL_HEADLOCK_SCALE_PROPERTY " +
          "headlockedPanelJoystickEnabledProperty=$PANEL_HEADLOCK_JOYSTICK_ENABLED_PROPERTY " +
          "headlockedPanelJoystickTranslateRateProperty=$PANEL_HEADLOCK_JOYSTICK_TRANSLATE_RATE_PROPERTY " +
          "headlockedPanelJoystickDistanceRateProperty=$PANEL_HEADLOCK_JOYSTICK_DISTANCE_RATE_PROPERTY " +
          "headlockedPanelJoystickScaleRateProperty=$PANEL_HEADLOCK_JOYSTICK_SCALE_RATE_PROPERTY"

  fun workflowPlacementUpdatedMarker(panelMode: String, headlockMarkerFields: String): String =
      "channel=spatial-panel status=placement-updated " +
          headlockMarkerFields + " " +
          "panelMode=$panelMode particleLayerRenderContinuity=kept-running"

  fun workflowPanelSizeUpdatedMarker(
      widthMeters: Float,
      heightMeters: Float,
      panelMode: String,
  ): String =
      "channel=spatial-panel status=size-updated panelWidth=${activityMarkerFloat(widthMeters)} " +
          "panelHeight=${activityMarkerFloat(heightMeters)} panelMode=$panelMode " +
          "particleLayerRenderContinuity=kept-running"

  fun workflowPlacementResetMarker(panelMode: String, headlockMarkerFields: String): String =
      "channel=spatial-panel status=placement-reset panelMode=$panelMode " +
          headlockMarkerFields + " " +
          "particleLayerRenderContinuity=kept-running"

  fun workflowHeadlockModeUpdatedMarker(source: String, headlockMarkerFields: String): String =
      "channel=spatial-panel status=headlock-mode-updated source=${activityMarkerToken(source)} " +
          headlockMarkerFields + " " +
          "rendererAuthority=native-vulkan-wsi-surface-panel uiAuthority=spatial-sdk-compose-panel"

  fun privateLayerPlacementSyncedFromSdkTransformMarker(
      reason: String,
      previousDistanceMeters: Float,
      headlockMarkerFields: String,
  ): String =
      "channel=private-layer-panel status=placement-synced-from-sdk-transform " +
          "reason=${activityMarkerToken(reason)} privateLayerPanelTransformAuthority=spatial-sdk-grabbable " +
          "composeDragPanelMovement=false previousDistanceMeters=${activityMarkerFloat(previousDistanceMeters)} " +
          headlockMarkerFields

  fun privateLayerGrabbableStateMarker(
      reason: String,
      grabbed: Boolean,
      headlockMarkerFields: String,
  ): String =
      "channel=private-layer-panel status=sdk-grabbable-state " +
          "reason=${activityMarkerToken(reason)} privateLayerPanelGrabbable=true " +
          "privateLayerPanelGrabType=PIVOT_Y privateLayerPanelIsGrabbed=$grabbed " +
          "privateLayerPanelGrabMinHeightMeters=${activityMarkerFloat(PRIVATE_LAYER_PANEL_GRAB_MIN_HEIGHT_METERS)} " +
          "privateLayerPanelGrabMaxHeightMeters=${activityMarkerFloat(PRIVATE_LAYER_PANEL_GRAB_MAX_HEIGHT_METERS)} " +
          "privateLayerPanelTransformAuthority=app-stored-placement-unless-grabbed " +
          "privateLayerPanelForcedDistanceDisabled=false " +
          "privateLayerPanelDistanceControl=left-stick-y-private-panel-free-transform-distance " +
          "rightStickSideFlickPanelMoveDisabled=true " +
          "composeDragPanelMovement=false panelHeaderGrabHandleVisualOnly=true " +
          headlockMarkerFields

  fun headlockedPoseUpdateSkippedMarker(reason: String): String =
      "channel=spatial-panel status=headlocked-pose-update-skipped " +
          "reason=${activityMarkerToken(reason)} headlockedPanelEnabled=true " +
          "viewerPoseSource=Scene.getViewerPose error=unavailable"

  fun headlockedPoseUpdatedMarker(
      reason: String,
      privateLayerPanelVisible: Boolean,
      headlockMarkerFields: String,
      panelPositionM: String,
      panelQuaternion: String,
  ): String =
      "channel=spatial-panel status=headlocked-pose-updated " +
          "reason=${activityMarkerToken(reason)} viewerPoseSource=Scene.getViewerPose " +
          "panelPoseSource=${if (privateLayerPanelVisible) "stored-placement-unless-grabbed" else "headlocked-viewer-relative"} " +
          headlockMarkerFields + " " +
          "panelPositionM=$panelPositionM " +
          "panelQuaternion=$panelQuaternion"

  fun headlockHotloadUpdatedMarker(reason: String, headlockMarkerFields: String): String =
      "channel=spatial-panel status=headlock-hotload-updated " +
          "reason=${activityMarkerToken(reason)} " +
          "headlockedPanelHotloadSource=runtime-hotload-android-property " +
          headlockPropertyMarkerFields() + " " +
          headlockMarkerFields

  fun headlockTuningPersistFailedMarker(
      source: String,
      error: String,
  ): String =
      "channel=spatial-panel status=headlock-tuning-persist-failed " +
          "source=${activityMarkerToken(source)} error=${activityMarkerToken(error)}"
}
