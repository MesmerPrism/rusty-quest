package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt

internal data class SpatialPrivateLayerPanelPoseResult(
    val pose: Pose,
    val placement: PanelPlacement,
)

@OptIn(SpatialSDKExperimentalAPI::class)
internal class SpatialPanelPoseCoordinator {
  fun privateLayerPlacementFromEntity(
      panelPose: Pose,
      viewerPose: Pose,
      currentPlacement: PanelPlacement,
      privateLayerVisible: Boolean,
  ): PanelPlacement {
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val viewerUp = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = activityCross(forward, viewerUp).activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val up = activityCross(right, forward).activityNormalizedOr(viewerUp)
    val offset = activityVectorSubtract(panelPose.t, viewerPose.t)
    val distance =
        activityVectorLength(offset)
            .coerceIn(PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS, PANEL_HEADLOCK_DISTANCE_MAX_METERS)
    return SpatialPanelPlacementModule.coercePrivateLayerPanelPlacement(
        currentPlacement.copy(
            xMeters = activityDot(offset, right),
            yMeters = activityDot(offset, up),
            zMeters = distance,
            visible = privateLayerVisible,
        )
    )
  }

  fun headlockedWorkflowPose(
      viewerPose: Pose,
      placement: PanelPlacement,
      yawDegrees: Float,
  ): Pose {
    val rawForward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val rollStableBasis = activityRollStableParticleProjectionBasis(rawForward, yawDegrees)
    val forward = rollStableBasis.first
    val right = rollStableBasis.second
    val up = rollStableBasis.third
    val center =
        viewerPose.t +
            right * placement.xMeters +
            up * placement.yMeters +
            forward * placement.zMeters
    return Pose(center, Quaternion.fromDirection(forward, up))
  }

  fun privateLayerPoseFromViewer(
      viewerPose: Pose,
      currentPlacement: PanelPlacement,
  ): SpatialPrivateLayerPanelPoseResult {
    val forward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val viewerUp = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = activityCross(forward, viewerUp).activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val up = activityCross(right, forward).activityNormalizedOr(viewerUp)
    val placement =
        SpatialPanelPlacementModule.coercePrivateLayerPanelPlacement(currentPlacement)
    val distance =
        placement.zMeters.coerceIn(
            PRIVATE_LAYER_PANEL_DISTANCE_MIN_METERS,
            PANEL_HEADLOCK_DISTANCE_MAX_METERS,
        )
    val lateralSquared = placement.xMeters * placement.xMeters + placement.yMeters * placement.yMeters
    val forwardMeters =
        sqrt((distance * distance - lateralSquared).coerceAtLeast(0.0f).toDouble()).toFloat()
    val offset =
        right * placement.xMeters + up * placement.yMeters + forward * forwardMeters
    val direction = offset.activityNormalizedOr(forward)
    val panelUp =
        (up + direction * -activityDot(up, direction)).activityNormalizedOr(up)
    val center = viewerPose.t + direction * distance
    return SpatialPrivateLayerPanelPoseResult(
        pose = Pose(center, Quaternion.fromDirection(direction, panelUp)),
        placement = placement,
    )
  }

  companion object {
    const val MODULE_ID = "spatial-panel-pose-coordinator"
  }
}
