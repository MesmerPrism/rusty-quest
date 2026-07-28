package io.github.mesmerprism.rustyquest.spatial_sdk_shared

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt

/** Shared front-face pose authority for one-sided Meta Spatial SDK UI panels. */
@OptIn(SpatialSDKExperimentalAPI::class)
object SpatialPanelFacing {
  const val MODULE_ID = "spatial-panel-facing"
  const val CONVENTION_MARKER = "meta-panel-front-look-rotation-around-y"
  const val FALLBACK_MARKER = "static-panel-front-yaw-180"

  fun poseFromViewer(viewerPose: Pose?, distanceMeters: Float): Pose {
    require(distanceMeters > 0.0f) { "Panel distance must be positive" }
    if (viewerPose == null) {
      return Pose(
          Vector3(0.0f, FALLBACK_EYE_HEIGHT_METERS, -distanceMeters),
          staticFrontRotation(),
      )
    }

    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val center = viewerPose.t + forward * distanceMeters
    return Pose(center, rotationFacingViewer(viewerToPanel = center - viewerPose.t))
  }

  fun rotationFacingViewer(viewerToPanel: Vector3): Quaternion {
    val direction = viewerToPanel.normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    return Quaternion.lookRotationAroundY(direction)
  }

  fun staticFrontRotation(): Quaternion = Quaternion(0.0f, 180.0f, 0.0f)

  private fun Vector3.normalizedOr(fallback: Vector3): Vector3 {
    val magnitude = sqrt(x * x + y * y + z * z)
    return if (magnitude > 0.000001f) this * (1.0f / magnitude) else fallback
  }

  private const val FALLBACK_EYE_HEIGHT_METERS = 1.20f
}
