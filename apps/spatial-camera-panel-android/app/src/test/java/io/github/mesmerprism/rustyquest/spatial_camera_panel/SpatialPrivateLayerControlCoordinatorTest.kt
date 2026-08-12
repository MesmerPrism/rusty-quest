package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialPrivateLayerControlCoordinatorTest {
  @Test
  fun lateProjectionInitializationDoesNotOverwriteExplicitProfileDepthControls() {
    val coordinator = coordinator()
    val requestedGuide =
        PrivateLayerGuideProcessing(
            preblurInput = 1,
            preblurKernel = 2,
            postblurKernel = 3,
            cameraSampling = 0,
        )

    coordinator.updateDepthLayerPolicy(PrivateLayerControls.depthPolicyMonoLayer0, "profile-playlist-step")
    coordinator.updateGuideProcessing(requestedGuide, "profile-playlist-step")
    coordinator.initializeDepthLayerPolicy(PrivateLayerControls.depthPolicyEyeIndex)
    coordinator.initializeGuideProcessing(PrivateLayerControls.nativeParityGuideProcessing)

    assertEquals(PrivateLayerControls.depthPolicyMonoLayer0, coordinator.depthLayerPolicy)
    assertEquals(
        PrivateLayerPanelControlModule.normalizeGuideProcessing(requestedGuide),
        coordinator.guideProcessing,
    )
  }

  @Test
  fun projectionInitializationStillSuppliesDefaultsBeforeAnyExplicitProfileUpdate() {
    val coordinator = coordinator()
    val requestedGuide =
        PrivateLayerGuideProcessing(
            preblurInput = 1,
            preblurKernel = 1,
            postblurKernel = 2,
            cameraSampling = 0,
        )

    coordinator.initializeDepthLayerPolicy(PrivateLayerControls.depthPolicyMonoLayer0)
    coordinator.initializeGuideProcessing(requestedGuide)

    assertEquals(PrivateLayerControls.depthPolicyMonoLayer0, coordinator.depthLayerPolicy)
    assertEquals(
        PrivateLayerPanelControlModule.normalizeGuideProcessing(requestedGuide),
        coordinator.guideProcessing,
    )
  }

  private fun coordinator(): SpatialPrivateLayerControlCoordinator =
      SpatialPrivateLayerControlCoordinator(
          SpatialPrivateLayerControlBindings(
              routeActive = { true },
              placementMode = { CameraHwbProjectionPlacementMode.ViewerLocked },
              projectionTargetScale = { 1.0f },
              updatePlacement = { _, _ -> },
              updateLayerOverrideNative = { 1L },
              updateEnvironmentDepthConsumerRequired = { _, _ -> },
              updateMetaPassthroughStyle = { requested, _ ->
                SpatialPassthroughLutUpdate(
                    requested = requested,
                    systemPassthroughEnabled = requested,
                    lutApplied = requested,
                    phase = 0.0f,
                    amplitude = 0.0f,
                )
              },
              projectionPanelEnabled = { true },
              refreshProjectionAfterPassthroughActivation = {},
              updateDepthLayerPolicyNative = { 1L },
              updateDepthAlignmentNative = { 1L },
              updateGuideProcessingNative = { 1L },
              updateZoneCompositorNative = { 1L },
              updateReadableVideoConsumerRequired = { _, _ -> },
              updateRgbChannelTransformNative = { 1L },
              updateProjectionSurfaceDisplacementNative = { 1L },
              updateProjectionSurfaceFeaturesNative = { _, _ -> 1L },
              marker = {},
          )
      )
}
