package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.SamplerConfig
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions

internal data class SpatialCameraHwbProjectionGeometryBindings(
    val scene: Scene,
    val carrierState: SpatialCameraHwbProjectionCarrierStateCoordinator,
    val tuning: SpatialCameraHwbProjectionTuningCoordinator,
    val virtualRoomEnabled: () -> Boolean,
    val projectionWidthMeters: (Float) -> Float,
    val projectionHeightMeters: (Float) -> Float,
    val privateLayerPanelZ: () -> Float,
    val immersiveVideoCarrierPresentation:
        () -> SpatialImmersiveVideoCustomCarrierPresentation?,
)

@OptIn(SpatialSDKExperimentalAPI::class)
internal class SpatialCameraHwbProjectionGeometryCoordinator(
    private val bindings: SpatialCameraHwbProjectionGeometryBindings,
) {
  fun planeForPlacement(): CameraHwbProjectionPlane =
      when (bindings.carrierState.placementMode()) {
        CameraHwbProjectionPlacementMode.ViewerLocked -> planeFromViewer()
        CameraHwbProjectionPlacementMode.VirtualRoomWall -> planeOnVirtualWall()
      }

  private fun planeFromViewer(): CameraHwbProjectionPlane {
    val viewerPose = runCatching { bindings.scene.getViewerPose() }.getOrNull()
    val targetDistanceMeters = targetDistanceMeters()
    return CameraHwbProjectionModule.viewerLockedProjectionPlane(
        viewerPosition = viewerPose?.t,
        viewerForward = viewerPose?.forward(),
        viewerUp = viewerPose?.up(),
        eyeOffsets = runCatching { bindings.scene.getEyeOffsets() }.getOrNull(),
        targetDistanceMeters = targetDistanceMeters,
        projectionWidthMeters = bindings.projectionWidthMeters(targetDistanceMeters),
        projectionHeightMeters = bindings.projectionHeightMeters(targetDistanceMeters),
    )
  }

  private fun planeOnVirtualWall(): CameraHwbProjectionPlane {
    val viewerPose = runCatching { bindings.scene.getViewerPose() }.getOrNull()
    return CameraHwbProjectionModule.virtualWallProjectionPlane(
        viewerPosition = viewerPose?.t,
        eyeOffsets = runCatching { bindings.scene.getEyeOffsets() }.getOrNull(),
    )
  }

  fun markerFields(plane: CameraHwbProjectionPlane? = null): String {
    val placementMode = plane?.placementMode ?: bindings.carrierState.placementMode()
    val targetDistanceMeters = plane?.targetDistanceMeters ?: targetDistanceMeters()
    val projectionWidthMeters =
        plane?.projectionWidthMeters ?: bindings.projectionWidthMeters(targetDistanceMeters)
    val projectionHeightMeters =
        plane?.projectionHeightMeters ?: bindings.projectionHeightMeters(targetDistanceMeters)
    return CameraHwbProjectionModule.markerFields(
        CameraHwbProjectionMarkerInput(
            carrierMode = bindings.carrierState.carrierMode(),
            placementMode = placementMode,
            carrierTransportToken = bindings.carrierState.carrierTransportToken(),
            startGateToken = bindings.carrierState.startGateToken(),
            roomRenderOrderToken = bindings.carrierState.roomRenderOrderToken(),
            targetDistanceMeters = targetDistanceMeters,
            projectionWidthMeters = projectionWidthMeters,
            projectionHeightMeters = projectionHeightMeters,
            targetScale = bindings.tuning.targetScale(),
            stereoHorizontalOffsetUv = bindings.tuning.stereoHorizontalOffsetUv(),
            targetScaleJoystickRatePerSecond = bindings.tuning.targetScaleJoystickRate(),
            targetDistanceSource = targetDistanceSource(),
            virtualRoomForegroundDistanceActive =
                virtualRoomForegroundDistanceActive(placementMode),
            privatePanelInputClearanceActive =
                privatePanelInputClearanceActive(placementMode),
            inputCarrierBehindPrivatePanel =
                inputCarrierBehindPrivatePanel(placementMode, targetDistanceMeters),
            privatePanelInputClearanceTargetDistanceMeters = targetDistanceMeters(),
            targetCoordinateSpace = PARTICLE_LAYER_TARGET_COORDINATE_SPACE,
            targetProjectionSpace = PARTICLE_LAYER_TARGET_PROJECTION_SPACE,
        )
    )
  }

  fun stereoMarkerFields(): String = CameraHwbProjectionModule.stereoMarkerFields()

  fun targetDistanceMeters(): Float {
    val requestedDistance =
        if (virtualRoomForegroundDistanceActive()) {
          CAMERA_HWB_PROJECTION_ROOM_FOREGROUND_TARGET_DISTANCE_METERS
        } else {
          CAMERA_HWB_PROJECTION_TARGET_DISTANCE_METERS
        }
    return requestedDistance.coerceIn(
        PARTICLE_LAYER_TARGET_DISTANCE_MIN_METERS,
        PARTICLE_LAYER_TARGET_DISTANCE_MAX_METERS,
    )
  }

  fun targetDistanceSource(): String =
      if (virtualRoomForegroundDistanceActive()) {
        "virtual-room-viewer-locked-foreground"
      } else {
        "fixed-camera-projection-default"
      }

  @Suppress("UNUSED_PARAMETER")
  fun privatePanelInputClearanceActive(
      placementMode: CameraHwbProjectionPlacementMode = bindings.carrierState.placementMode(),
  ): Boolean = false

  fun inputCarrierBehindPrivatePanel(
      placementMode: CameraHwbProjectionPlacementMode = bindings.carrierState.placementMode(),
      targetDistanceMeters: Float = targetDistanceMeters(),
  ): Boolean =
      privatePanelInputClearanceActive(placementMode) &&
          targetDistanceMeters > bindings.privateLayerPanelZ()

  fun virtualRoomForegroundDistanceActive(
      placementMode: CameraHwbProjectionPlacementMode = bindings.carrierState.placementMode(),
  ): Boolean =
      CameraHwbProjectionModule.virtualRoomForegroundDistanceActive(
          placementMode,
          bindings.virtualRoomEnabled(),
          bindings.carrierState.scenePanelCarrierEnabled(),
      )

  fun panelMediaSettings(): MediaPanelSettings {
    val plane = planeForPlacement()
    val videoPresentation = bindings.immersiveVideoCarrierPresentation()
    val outputWidthPx =
        videoPresentation?.outputWidthPx ?: CAMERA_HWB_PROJECTION_WIDTH_PX
    val outputHeightPx =
        videoPresentation?.outputHeightPx ?: CAMERA_HWB_PROJECTION_HEIGHT_PX
    return MediaPanelSettings(
        shape =
            when (videoPresentation?.shape) {
              SpatialImmersiveVideoCarrierShape.WorldQuad ->
                  QuadShapeOptions(
                      videoPresentation.flatPanelWidthMeters,
                      videoPresentation.flatPanelHeightMeters,
                  )
              SpatialImmersiveVideoCarrierShape.Equirect180 ->
                  Equirect180ShapeOptions(radius = videoPresentation.radiusMeters)
              SpatialImmersiveVideoCarrierShape.Equirect360 ->
                  Equirect360ShapeOptions(radius = videoPresentation.radiusMeters)
              SpatialImmersiveVideoCarrierShape.LegacyQuad, null ->
                  QuadShapeOptions(plane.projectionWidthMeters, plane.projectionHeightMeters)
            },
        display =
            FixedMediaPanelDisplayOptions(
                widthPx = outputWidthPx,
                heightPx = outputHeightPx,
            ),
        rendering =
            MediaPanelRenderOptions(
                false,
                StereoMode.LeftRight,
                SamplerConfig(),
                0,
                bindings.carrierState.zIndexForPlacement(plane.placementMode),
            ),
        style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaqueProbe),
        input = PanelInputOptions(0),
    )
  }

  fun outputDimensions(): Pair<Int, Int> =
      bindings.immersiveVideoCarrierPresentation()?.let {
        it.outputWidthPx to it.outputHeightPx
      } ?: (CAMERA_HWB_PROJECTION_WIDTH_PX to CAMERA_HWB_PROJECTION_HEIGHT_PX)

  companion object {
    const val MODULE_ID = "spatial-camera-hwb-projection-geometry-coordinator"
  }
}
