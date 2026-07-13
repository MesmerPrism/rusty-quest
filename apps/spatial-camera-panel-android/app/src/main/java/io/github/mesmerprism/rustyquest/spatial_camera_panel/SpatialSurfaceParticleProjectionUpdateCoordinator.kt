package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.abs

internal data class SpatialSurfaceParticleViewerProjectionState(
    val viewerPose: Pose,
    val leftEyeOffset: Vector3?,
    val rightEyeOffset: Vector3?,
)

internal data class SpatialSurfaceParticleEntityProjectionUpdate(
    val pose: Pose,
    val surfaceWidthMeters: Float,
    val surfaceHeightMeters: Float,
    val applySurfaceGeometry: Boolean,
    val visible: Boolean,
)

internal data class SpatialSurfaceParticlePanelPoseNativeUpdate(
    val center: Vector3,
    val right: Vector3,
    val up: Vector3,
    val surfaceWidthMeters: Float,
    val surfaceHeightMeters: Float,
    val targetDistanceMeters: Float,
    val leftEyeOffsetRightMeters: Float,
    val rightEyeOffsetRightMeters: Float,
)

internal data class SpatialSurfaceParticleViewerEyePoseNativeUpdate(
    val viewerPosition: Vector3,
    val rawRight: Vector3,
    val rawUp: Vector3,
    val rawForward: Vector3,
    val leftEyeWorld: Vector3,
    val rightEyeWorld: Vector3,
)

internal data class SpatialSurfaceParticleProjectionUpdateRequest(
    val reason: String,
    val forceLog: Boolean,
    val hideProjectionEntity: () -> Unit,
    val applyProjectionEntity: (SpatialSurfaceParticleEntityProjectionUpdate) -> Unit,
)

internal data class SpatialSurfaceParticleProjectionUpdateBindings(
    val featureEnabled: () -> Boolean,
    val cameraStackSuppressesParticles: () -> Boolean,
    val captureViewerState: () -> SpatialSurfaceParticleViewerProjectionState,
    val currentViewYawDegrees: () -> Float,
    val currentTargetDistanceMeters: () -> Float,
    val projectionWidthMeters: (Float) -> Float,
    val projectionHeightMeters: (Float) -> Float,
    val currentSurfaceOverscanScale: () -> Float,
    val surfaceWidthMeters: (Float, Float) -> Float,
    val surfaceHeightMeters: (Float, Float) -> Float,
    val particleLayerVisible: () -> Boolean,
    val updatePanelLayer: (String, Boolean) -> String,
    val receiptLibraryLoaded: () -> Boolean,
    val updateNativePanelPose: (SpatialSurfaceParticlePanelPoseNativeUpdate) -> Long,
    val updateNativeViewerEyePose: (SpatialSurfaceParticleViewerEyePoseNativeUpdate) -> Long,
    val elapsedRealtime: () -> Long,
    val placementMarkerFields: () -> String,
    val marker: (String) -> Unit,
)

internal class SpatialSurfaceParticleProjectionUpdateCoordinator(
    private val bindings: SpatialSurfaceParticleProjectionUpdateBindings,
) {
  private var projectionMarkerCount = 0
  private var lastProjectionMarkerMs = 0L
  private var lastTargetDistanceMeters: Float? = null
  private var lastSurfaceOverscanScale: Float? = null
  private var lastPanelLayerCheckMs = 0L
  private var surfaceGeometryApplied = false

  fun update(request: SpatialSurfaceParticleProjectionUpdateRequest) {
    if (!bindings.featureEnabled()) {
      request.hideProjectionEntity()
      if (request.forceLog) {
        bindings.marker(
            SpatialSurfaceParticleRouteModule.nativeSurfaceParticleEffectSuppressedMarker(
                "projection-update",
                request.reason,
            )
        )
      }
      return
    }
    if (bindings.cameraStackSuppressesParticles()) {
      request.hideProjectionEntity()
      if (request.forceLog) {
        bindings.marker(
            SpatialSurfaceParticleRouteModule.nativeSurfaceParticleProjectionPlaneUpdateSuppressedMarker(
                request.reason
            )
        )
      }
      return
    }

    val viewerState =
        runCatching { bindings.captureViewerState() }
            .getOrElse { throwable ->
              if (request.forceLog) {
                bindings.marker(
                    SpatialSurfaceParticleRouteModule.nativeSurfaceParticleProjectionPlaneUpdateSkippedMarker(
                        request.reason,
                        throwable.javaClass.simpleName,
                    )
                )
              }
              return
            }
    val viewerPose = viewerState.viewerPose
    val rawForward = viewerPose.forward().activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val rawUp = viewerPose.up().activityNormalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val rawRight =
        activityCross(rawForward, rawUp).activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val yawDegrees = bindings.currentViewYawDegrees()
    val rollStableBasis = activityRollStableParticleProjectionBasis(rawForward, yawDegrees)
    val forward = rollStableBasis.first
    val right = rollStableBasis.second
    val up = rollStableBasis.third
    val leftEyeOffsetRightMeters = activityEyeOffsetRightMeters(viewerState.leftEyeOffset)
    val rightEyeOffsetRightMeters = activityEyeOffsetRightMeters(viewerState.rightEyeOffset)
    val leftEyeWorld = viewerPose.t + rawRight * leftEyeOffsetRightMeters
    val rightEyeWorld = viewerPose.t + rawRight * rightEyeOffsetRightMeters
    val targetDistanceMeters = bindings.currentTargetDistanceMeters()
    val projectionWidthMeters = bindings.projectionWidthMeters(targetDistanceMeters)
    val projectionHeightMeters = bindings.projectionHeightMeters(targetDistanceMeters)
    val surfaceOverscanScale = bindings.currentSurfaceOverscanScale()
    val surfaceWidthMeters = bindings.surfaceWidthMeters(targetDistanceMeters, surfaceOverscanScale)
    val surfaceHeightMeters = bindings.surfaceHeightMeters(targetDistanceMeters, surfaceOverscanScale)
    val projectionSurfaceMarkerFields =
        SpatialSurfaceParticleRouteModule.projectionSurfaceMarkerFields(
            projectionWidthMeters,
            projectionHeightMeters,
            surfaceWidthMeters,
            surfaceHeightMeters,
        )
    val previousTargetDistanceMeters = lastTargetDistanceMeters
    val previousSurfaceOverscanScale = lastSurfaceOverscanScale
    val surfaceGeometryChanged =
        previousTargetDistanceMeters == null ||
            abs(previousTargetDistanceMeters - targetDistanceMeters) >= 0.001f ||
            previousSurfaceOverscanScale == null ||
            abs(previousSurfaceOverscanScale - surfaceOverscanScale) >= 0.001f
    if (surfaceGeometryChanged) {
      lastTargetDistanceMeters = targetDistanceMeters
      lastSurfaceOverscanScale = surfaceOverscanScale
      bindings.marker(
          SpatialSurfaceParticleRouteModule.nativeSurfaceParticleSurfaceGeometryHotloadUpdatedMarker(
              targetDistanceMeters,
              projectionWidthMeters,
              projectionHeightMeters,
              surfaceOverscanScale,
              surfaceWidthMeters,
              surfaceHeightMeters,
              projectionSurfaceMarkerFields,
          )
      )
    }

    val now = bindings.elapsedRealtime()
    val center = viewerPose.t + forward * targetDistanceMeters
    val planePose = Pose(center, Quaternion.fromDirection(forward, up))
    val applySurfaceGeometry = surfaceGeometryChanged || !surfaceGeometryApplied
    request.applyProjectionEntity(
        SpatialSurfaceParticleEntityProjectionUpdate(
            pose = planePose,
            surfaceWidthMeters = surfaceWidthMeters,
            surfaceHeightMeters = surfaceHeightMeters,
            applySurfaceGeometry = applySurfaceGeometry,
            visible = bindings.particleLayerVisible(),
        )
    )
    if (applySurfaceGeometry) {
      surfaceGeometryApplied = true
    }
    if (
        request.forceLog ||
            surfaceGeometryChanged ||
            now - lastPanelLayerCheckMs >= PARTICLE_LAYER_PANEL_LAYER_CHECK_INTERVAL_MS
    ) {
      lastPanelLayerCheckMs = now
      bindings.updatePanelLayer("projection-plane-update", false)
    }

    val nativePanelPoseUpdateMask =
        if (bindings.receiptLibraryLoaded()) {
          runCatching {
                bindings.updateNativePanelPose(
                    SpatialSurfaceParticlePanelPoseNativeUpdate(
                        center = center,
                        right = right,
                        up = up,
                        surfaceWidthMeters = surfaceWidthMeters,
                        surfaceHeightMeters = surfaceHeightMeters,
                        targetDistanceMeters = targetDistanceMeters,
                        leftEyeOffsetRightMeters = leftEyeOffsetRightMeters,
                        rightEyeOffsetRightMeters = rightEyeOffsetRightMeters,
                    )
                )
              }
              .getOrElse { throwable ->
                if (request.forceLog) {
                  bindings.marker(
                      SpatialSurfaceParticleRouteModule.nativeSurfaceParticlePanelPoseNativeUpdateFailedMarker(
                          request.reason,
                          throwable.javaClass.simpleName,
                      )
                  )
                }
                0L
              }
        } else {
          0L
        }
    val nativeViewerEyePoseUpdateMask =
        if (bindings.receiptLibraryLoaded()) {
          runCatching {
                bindings.updateNativeViewerEyePose(
                    SpatialSurfaceParticleViewerEyePoseNativeUpdate(
                        viewerPosition = viewerPose.t,
                        rawRight = rawRight,
                        rawUp = rawUp,
                        rawForward = rawForward,
                        leftEyeWorld = leftEyeWorld,
                        rightEyeWorld = rightEyeWorld,
                    )
                )
              }
              .getOrElse { throwable ->
                if (request.forceLog) {
                  bindings.marker(
                      SpatialSurfaceParticleRouteModule.nativeSurfaceParticleViewerEyePoseNativeUpdateFailedMarker(
                          request.reason,
                          throwable.javaClass.simpleName,
                      )
                  )
                }
                0L
              }
        } else {
          0L
        }
    val shouldLog =
        request.forceLog ||
            (projectionMarkerCount < 4 &&
                now - lastProjectionMarkerMs >= PARTICLE_LAYER_PROJECTION_MARKER_INTERVAL_MS)
    if (!shouldLog) {
      return
    }
    projectionMarkerCount += 1
    lastProjectionMarkerMs = now
    bindings.marker(
        SpatialSurfaceParticleRouteModule.nativeSurfaceParticleProjectionPlaneUpdatedMarker(
            reason = request.reason,
            placementMarkerFields = bindings.placementMarkerFields(),
            viewYawDegrees = yawDegrees,
            viewerPositionM = activityVectorMarker(viewerPose.t),
            viewerForward = activityVectorMarker(rawForward),
            viewerUp = activityVectorMarker(rawUp),
            viewerRight = activityVectorMarker(rawRight),
            panelForward = activityVectorMarker(forward),
            panelRight = activityVectorMarker(right),
            panelUp = activityVectorMarker(up),
            nativePanelPoseUpdateMask = nativePanelPoseUpdateMask,
            nativeViewerEyePoseUpdateMask = nativeViewerEyePoseUpdateMask,
            projectionSurfaceMarkerFields = projectionSurfaceMarkerFields,
            projectionWidthMeters = projectionWidthMeters,
            projectionHeightMeters = projectionHeightMeters,
            surfaceOverscanScale = surfaceOverscanScale,
            surfaceWidthMeters = surfaceWidthMeters,
            surfaceHeightMeters = surfaceHeightMeters,
            planeCenterM = activityVectorMarker(center),
            planeQuaternion = activityQuaternionMarker(planePose.q),
            leftEyeOffsetM = activityVectorMarker(viewerState.leftEyeOffset ?: Vector3(0.0f)),
            rightEyeOffsetM = activityVectorMarker(viewerState.rightEyeOffset ?: Vector3(0.0f)),
            leftEyeWorldM = activityVectorMarker(leftEyeWorld),
            rightEyeWorldM = activityVectorMarker(rightEyeWorld),
            leftEyeOffsetRightMeters = leftEyeOffsetRightMeters,
            rightEyeOffsetRightMeters = rightEyeOffsetRightMeters,
        )
    )
  }

  companion object {
    const val MODULE_ID = "spatial-surface-particle-projection-update-coordinator"
  }
}
