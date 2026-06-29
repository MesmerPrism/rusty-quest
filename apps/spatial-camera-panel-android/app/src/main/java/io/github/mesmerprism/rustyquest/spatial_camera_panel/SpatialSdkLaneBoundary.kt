package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal enum class SpatialSdkLaneKind {
  Carrier,
  PanelWorkflow,
  CameraProjection,
  PublicMultiStack,
  SurfaceParticle,
  StagedAsset,
  VirtualRoom,
  DebugProbe,
}

internal data class SpatialSdkLaneBoundary(
    val id: String,
    val kind: SpatialSdkLaneKind,
    val owner: String,
    val authority: String,
    val renderCarrier: String,
    val highRatePayloadPolicy: String,
    val mayRequest: Set<String> = emptySet(),
    val mustNotOwn: Set<String> = emptySet(),
)

internal object SpatialSdkLayerCarrier {
  val boundary =
      SpatialSdkLaneBoundary(
          id = "spatial-sdk-layer-carrier",
          kind = SpatialSdkLaneKind.Carrier,
          owner = "Spatial SDK Scene/Panel/Quad layer adapter",
          authority = "layer creation, placement, visibility, size, z-index",
          renderCarrier = "SceneSwapchain.createAsAndroid-Surface-SceneQuadLayer-or-VideoSurfacePanel",
          highRatePayloadPolicy = "surface-carrier-only",
          mayRequest =
              setOf(
                  "panel-workflow",
                  "camera-projection",
                  "public-multistack",
                  "surface-particle",
                  "virtual-room",
                  "debug-probe",
              ),
          mustNotOwn = setOf("Camera2 streams", "Vulkan shader semantics", "particle dynamics", "session truth"),
      )
}

internal object ExperimentPanelControllerBoundary {
  val boundary =
      SpatialSdkLaneBoundary(
          id = ExperimentPanelController.boundaryId,
          kind = SpatialSdkLaneKind.PanelWorkflow,
          owner = "Compose experiment panel",
          authority = ExperimentPanelController.authority,
          renderCarrier = "Spatial SDK ComposeViewPanelRegistration",
          highRatePayloadPolicy = ExperimentPanelController.highRatePayloadPolicy,
          mayRequest = setOf("surface-particle parameter update", "workflow panel visibility"),
          mustNotOwn = setOf("Camera2 frames", "Vulkan WSI", "particle buffers"),
      )
}

internal object CameraProjectionProbeController {
  val boundary =
      SpatialSdkLaneBoundary(
          id = "spatial-sdk-camera-projection-controller",
          kind = SpatialSdkLaneKind.CameraProjection,
          owner = "Camera2/HWB projection probe",
          authority = "camera stream setup, target-rect projection markers, native Vulkan WSI pixels",
          renderCarrier = "SDK-owned SceneQuadLayer Android Surface",
          highRatePayloadPolicy = "Camera2/AHardwareBuffer only, no JSON frames",
          mayRequest = setOf("spatial-sdk-layer-carrier"),
          mustNotOwn = setOf("surface particles", "driver-profile dynamics", "questionnaire state"),
      )
}

internal object PublicMultiStackController {
  val boundary =
      SpatialSdkLaneBoundary(
          id = "spatial-sdk-public-multistack-controller",
          kind = SpatialSdkLaneKind.PublicMultiStack,
          owner = "public camera guide multi-stack contract",
          authority = "seven-slot layer manifest, public guide blur contract, opaque downstream slot boundaries",
          renderCarrier = "SDK-owned SceneQuadLayer Android Surface",
          highRatePayloadPolicy = "Camera2/AHardwareBuffer and resident public guide textures only, no JSON frames",
          mayRequest = setOf("spatial-sdk-layer-carrier", "camera-projection"),
          mustNotOwn = setOf("downstream visual formulas", "tuned downstream profiles", "study-specific semantics"),
      )
}

internal object SurfaceParticleLayerController {
  val boundary =
      SpatialSdkLaneBoundary(
          id = "spatial-sdk-surface-particle-controller",
          kind = SpatialSdkLaneKind.SurfaceParticle,
          owner = "native surface particle proof",
          authority = "native Vulkan particle compute/draw over SDK-owned panel surface",
          renderCarrier = "Spatial SDK VideoSurfacePanelRegistration Android Surface",
          highRatePayloadPolicy = "resident native buffers only, no Kotlin particle arrays",
          mayRequest = setOf("spatial-sdk-layer-carrier", "low-rate driver-profile scalars"),
          mustNotOwn = setOf("Camera2/AImageReader streams", "raw camera target rects", "questionnaire state"),
      )
}

internal object SpatialStagedAssetBoundary {
  val boundary =
      SpatialSdkLaneBoundary(
          id = SpatialStagedAssetModule.MODULE_ID,
          kind = SpatialSdkLaneKind.StagedAsset,
          owner = "Spatial SDK staged 3D asset module",
          authority = "runtime Mesh entity creation from an explicit staged mesh URI",
          renderCarrier = "Spatial SDK Entity Mesh component",
          highRatePayloadPolicy = "staged mesh file only, no Kotlin vertex arrays",
          mayRequest = setOf("spatial-sdk-layer-carrier"),
          mustNotOwn = setOf("source asset provenance", "asset conversion", "effect semantics"),
      )
}

internal object SpatialVirtualRoomBoundary {
  val boundary =
      SpatialSdkLaneBoundary(
          id = "spatial-sdk-packaged-virtual-room",
          kind = SpatialSdkLaneKind.VirtualRoom,
          owner = "Spatial SDK packaged virtual room module",
          authority = "optional packaged GLXF scene loading and sample-style room lighting",
          renderCarrier = "Spatial SDK GLXF composition plus skybox mesh",
          highRatePayloadPolicy = "packaged scene assets only, no Kotlin geometry arrays",
          mayRequest = setOf("spatial-sdk-layer-carrier"),
          mustNotOwn = setOf("MRUK real-room placement", "passthrough room capture", "private model provenance"),
      )
}

internal object SpatialDebugProbeController {
  val boundary =
      SpatialSdkLaneBoundary(
          id = "spatial-sdk-debug-probe-controller",
          kind = SpatialSdkLaneKind.DebugProbe,
          owner = "Spatial SDK capability probes",
          authority = "isolated SDK surface/layer capability evidence",
          renderCarrier = "temporary SceneQuadLayer or PanelSurface variants",
          highRatePayloadPolicy = "diagnostic-only",
          mayRequest = setOf("spatial-sdk-layer-carrier"),
          mustNotOwn = setOf("experiment flow", "camera projection", "surface particles"),
      )
}

internal object SpatialSdkLaneBoundaries {
  val all: List<SpatialSdkLaneBoundary> =
      listOf(
          SpatialSdkLayerCarrier.boundary,
          ExperimentPanelControllerBoundary.boundary,
          CameraProjectionProbeController.boundary,
          PublicMultiStackController.boundary,
          SurfaceParticleLayerController.boundary,
          SpatialStagedAssetBoundary.boundary,
          SpatialVirtualRoomBoundary.boundary,
          SpatialDebugProbeController.boundary,
      )

  fun summaryToken(): String =
      all.joinToString(";") { boundary ->
        "${boundary.id}:${boundary.kind.name}"
      }
}
