package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class PrivateLayerChoice(
    val index: Int,
    val title: String,
    val token: String,
)

internal data class PrivateLayerDepthAlignment(
    val leftX: Float = 0.0f,
    val leftY: Float = 0.0f,
    val rightX: Float = 0.0f,
    val rightY: Float = 0.0f,
    val sampleScale: Float = 1.0f,
    val sampleScaleY: Float = 1.0f,
    val rollDegrees: Float = 0.0f,
    val metadataAutoAlign: Boolean = true,
)

internal data class PrivateLayerDepthSourceChoice(
    val code: Int,
    val title: String,
    val token: String,
)

internal object PrivateLayerControls {
  const val cycleOverride: Float = -1.0f
  const val metaPassthroughEdgeWindowOverride: Float = 7.0f
  const val rawCustomProjectionOverride: Float = 8.0f
  const val depthPolicyMonoLayer0: Int = 0
  const val depthPolicyMonoLayer1: Int = 1
  const val depthPolicyEyeIndex: Int = 2
  const val depthPolicyCompare: Int = 3
  const val defaultDepthLayerPolicy: Int = depthPolicyEyeIndex

  val layers =
      listOf(
          PrivateLayerChoice(0, "Final", "final"),
          PrivateLayerChoice(1, "Opaque analysis 0", "opaque-analysis0-slot"),
          PrivateLayerChoice(2, "Public guide blur", "public-guide-blur"),
          PrivateLayerChoice(3, "Opaque analysis 1", "opaque-analysis1-slot"),
          PrivateLayerChoice(4, "Public post-blur guide", "public-post-blur-guide"),
          PrivateLayerChoice(5, "Opaque projection", "opaque-projection-slot"),
          PrivateLayerChoice(6, "Public depth diagnostic", "public-depth-diagnostic"),
          PrivateLayerChoice(7, "Meta poster LUT", "meta-passthrough-edge-window"),
          PrivateLayerChoice(8, "Raw custom projection", "raw-custom-projection"),
      )

  val depthSourcePolicies =
      listOf(
          PrivateLayerDepthSourceChoice(depthPolicyEyeIndex, "Stereo (per eye)", "eye-index"),
          PrivateLayerDepthSourceChoice(depthPolicyMonoLayer0, "Mono 0", "mono-layer0"),
          PrivateLayerDepthSourceChoice(depthPolicyMonoLayer1, "Mono 1", "mono-layer1"),
          PrivateLayerDepthSourceChoice(depthPolicyCompare, "Compare", "compare"),
      )

  fun labelForOverride(layerOverride: Float): String {
    val rounded = layerOverride.toInt()
    return if (layerOverride < 0.0f) {
      "Cycle"
    } else {
      layers.firstOrNull { it.index == rounded }?.title ?: "Final"
    }
  }

  fun metaPassthroughEdgeWindowSelected(layerOverride: Float): Boolean =
      layerOverride.toInt() == metaPassthroughEdgeWindowOverride.toInt()

  fun normalizeDepthLayerPolicy(policy: Int): Int =
      depthSourcePolicies.firstOrNull { it.code == policy }?.code ?: defaultDepthLayerPolicy

  fun labelForDepthLayerPolicy(policy: Int): String =
      depthSourcePolicies.firstOrNull { it.code == normalizeDepthLayerPolicy(policy) }?.title
          ?: "Stereo (per eye)"

  fun tokenForDepthLayerPolicy(policy: Int): String =
      depthSourcePolicies.firstOrNull { it.code == normalizeDepthLayerPolicy(policy) }?.token
          ?: "eye-index"

  fun depthLayerPolicyForToken(token: String): Int? {
    val normalized = token.trim().lowercase().replace("_", "-")
    return when (normalized) {
      "mono-layer0", "mono-left", "layer0", "left", "0" -> depthPolicyMonoLayer0
      "mono-layer1", "mono-right", "layer1", "right", "1" -> depthPolicyMonoLayer1
      "eye-index", "per-eye", "stereo", "stereo-indexed", "2" -> depthPolicyEyeIndex
      "compare", "layer-compare", "compare-layers", "depth-compare", "l0-l1-compare", "3" ->
          depthPolicyCompare
      else -> null
    }
  }
}

internal object PrivateLayerPanelControlModule {
  fun normalizeLayerOverride(requestedLayerOverride: Float): Float =
      if (requestedLayerOverride < 0.0f) {
        PrivateLayerControls.cycleOverride
      } else {
        requestedLayerOverride
            .coerceIn(0.0f, PrivateLayerControls.layers.maxOf { it.index }.toFloat())
            .toInt()
            .toFloat()
      }

  fun normalizeDepthLayerPolicy(requestedPolicy: Int): Int =
      PrivateLayerControls.normalizeDepthLayerPolicy(requestedPolicy)

  fun depthLayerCompareMode(policy: Int): String =
      if (policy == PrivateLayerControls.depthPolicyCompare) {
        "visual-shader"
      } else {
        "off"
      }

  fun coerceDepthAlignment(requestedAlignment: PrivateLayerDepthAlignment): PrivateLayerDepthAlignment =
      PrivateLayerDepthAlignment(
          leftX = requestedAlignment.leftX.coerceIn(-0.25f, 0.25f),
          leftY = requestedAlignment.leftY.coerceIn(-0.25f, 0.25f),
          rightX = requestedAlignment.rightX.coerceIn(-0.25f, 0.25f),
          rightY = requestedAlignment.rightY.coerceIn(-0.25f, 0.25f),
          sampleScale = requestedAlignment.sampleScale.coerceIn(0.25f, 3.0f),
          sampleScaleY = requestedAlignment.sampleScaleY.coerceIn(0.25f, 3.0f),
          rollDegrees = requestedAlignment.rollDegrees.coerceIn(-15.0f, 15.0f),
          metadataAutoAlign = requestedAlignment.metadataAutoAlign,
      )

  fun layerButtonSelectedMarker(
      source: String,
      requestedLayerOverride: Float,
      previousOverride: Float,
      updatedOverride: Float,
      placementMode: CameraHwbProjectionPlacementMode,
  ): String =
      "channel=private-layer-panel status=layer-button-selected " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          "privateLayerPanelInputButtons=button-a+trigger-l+trigger-r " +
          "privateLayerPanelTriggerSelectEnabled=true " +
          "requestedPublicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(requestedLayerOverride)} " +
          "previousPublicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(previousOverride)} " +
          "publicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(updatedOverride)} " +
          "publicMultiStackOpaqueProjectionLayerLabel=${activityMarkerToken(PrivateLayerControls.labelForOverride(updatedOverride))} " +
          "projectionPlacementMode=${placementMode.markerToken} " +
          "layerOverrideAppliesToWallAndFullFov=true " +
          "cameraProjectionPlacementIndependentLayerControl=true " +
          "runtimeCrash=false"

  fun layerOverrideUpdateFailedMarker(
      source: String,
      requestedLayerOverride: Float,
      updatedOverride: Float,
      error: String,
      message: String,
  ): String =
      "channel=private-layer-panel status=layer-override-update-failed " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          "requestedPublicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(requestedLayerOverride)} " +
          "publicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(updatedOverride)} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun layerOverrideSubmittedMarker(
      source: String,
      updateMask: Long,
      previousOverride: Float,
      updatedOverride: Float,
      placementMode: CameraHwbProjectionPlacementMode,
      projectionTargetScale: Float,
  ): String =
      "channel=private-layer-panel status=layer-override-submitted " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          "transport=jni-live-queue publicMultiStackLayerControl=true updateMask=$updateMask " +
          "previousPublicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(previousOverride)} " +
          "publicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(updatedOverride)} " +
          "publicMultiStackOpaqueProjectionLayerLabel=${activityMarkerToken(PrivateLayerControls.labelForOverride(updatedOverride))} " +
          "projectionPlacementMode=${placementMode.markerToken} " +
          "layerOverrideAppliesToWallAndFullFov=true " +
          "cameraProjectionPlacementIndependentLayerControl=true " +
          "publicMultiStackLayerManifest=0:final,1:opaque-analysis0-slot,2:public-guide-blur,3:opaque-analysis1-slot,4:public-post-blur-guide,5:opaque-projection-slot,6:public-depth-diagnostic,7:meta-passthrough-edge-window,8:raw-custom-projection " +
          "projectionTargetLiveScale=${activityMarkerFloat(projectionTargetScale)} " +
          "layerOverrideForcedProjectionRefresh=true " +
          "panelRenderOrder=spatial-sdk-quad-layer-z-index runtimeCrash=false"

  fun metaPassthroughEdgeWindowSubmittedMarker(
      source: String,
      selected: Boolean,
      passthroughStyleUpdate: SpatialPassthroughLutUpdate,
  ): String =
      "channel=private-layer-panel status=meta-passthrough-edge-window-submitted " +
          "source=${activityMarkerToken(source)} metaPassthroughEdgeWindowSelected=$selected " +
          "metaSystemPassthroughEnabled=${passthroughStyleUpdate.systemPassthroughEnabled} " +
          "spatialSdkPassthroughLutRequested=${passthroughStyleUpdate.requested} " +
          "spatialSdkPassthroughLutApplied=${passthroughStyleUpdate.lutApplied} " +
          "spatialSdkPassthroughLutMode=animated-posterized-mono-to-rgba-gradient " +
          "spatialSdkPassthroughLutPhase=${activityMarkerFloat(passthroughStyleUpdate.phase)} " +
          "spatialSdkPassthroughLutAmplitude=${activityMarkerFloat(passthroughStyleUpdate.amplitude)} " +
          "passthroughStyleOwner=spatial-sdk-system-passthrough " +
          "passthroughActivationOrder=system-style-before-native-projection-cutout " +
          "nativePassthroughEdgeStyleRequested=false nativePassthroughEdgeStyleVisualAuthority=false " +
          "projectionAlphaCutoutRequested=$selected " +
          "projectionAlphaCutoutValue=0.000 projectionAlphaCutoutPreservesVideoDecode=true " +
          "runtimeCrash=false"

  fun metaPassthroughProjectionRefreshMarker(
      source: String,
      requested: Boolean,
      previousOverride: Float,
      updatedOverride: Float,
  ): String =
      "channel=private-layer-panel status=meta-passthrough-projection-refresh " +
          "source=${activityMarkerToken(source)} projectionRefreshRequested=$requested " +
          "projectionRefreshPolicy=one-shot-carrier-rebind-after-system-style-and-native-cutout " +
          "previousPublicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(previousOverride)} " +
          "publicMultiStackOpaqueProjectionLayerOverride=${activityMarkerFloat(updatedOverride)} " +
          "videoRestartPolicy=resume-active-video runtimeCrash=false"

  fun depthLayerPolicySelectedMarker(
      source: String,
      requestedPolicy: Int,
      previousPolicy: Int,
      updatedPolicy: Int,
  ): String {
    val policyToken = PrivateLayerControls.tokenForDepthLayerPolicy(updatedPolicy)
    val compareMode = depthLayerCompareMode(updatedPolicy)
    return "channel=private-layer-panel status=depth-layer-policy-selected " +
        "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
        "requestedPublicMultiStackDepthLayerPolicyCode=$requestedPolicy " +
        "previousPublicMultiStackDepthLayerPolicy=${activityMarkerToken(PrivateLayerControls.tokenForDepthLayerPolicy(previousPolicy))} " +
        "publicMultiStackDepthLayerPolicy=${activityMarkerToken(policyToken)} " +
        "publicMultiStackDepthLayerCompareMode=${activityMarkerToken(compareMode)} " +
        "publicMultiStackDepthLayerPolicyProperty=$CAMERA_HWB_PROJECTION_DEPTH_LAYER_POLICY_PROPERTY " +
        "runtimeCrash=false"
  }

  fun depthLayerPolicyUpdateFailedMarker(
      source: String,
      updatedPolicy: Int,
      error: String,
      message: String,
  ): String =
      "channel=private-layer-panel status=depth-layer-policy-update-failed " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          "publicMultiStackDepthLayerPolicy=${activityMarkerToken(PrivateLayerControls.tokenForDepthLayerPolicy(updatedPolicy))} " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun depthLayerPolicySubmittedMarker(
      source: String,
      updateMask: Long,
      previousPolicy: Int,
      updatedPolicy: Int,
  ): String {
    val policyToken = PrivateLayerControls.tokenForDepthLayerPolicy(updatedPolicy)
    val compareMode = depthLayerCompareMode(updatedPolicy)
    val compareEvidence =
        if (compareMode == "visual-shader") {
          "shader-samples-layer0-and-layer1-at-same-depth-uv"
        } else {
          "inactive"
        }
    return "channel=private-layer-panel status=depth-layer-policy-submitted " +
        "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
        "transport=jni-live-queue publicMultiStackDepthLayerPolicyControl=true updateMask=$updateMask " +
        "previousPublicMultiStackDepthLayerPolicy=${activityMarkerToken(PrivateLayerControls.tokenForDepthLayerPolicy(previousPolicy))} " +
        "publicMultiStackDepthLayerPolicy=${activityMarkerToken(policyToken)} " +
        "publicMultiStackDepthLayerCompareMode=${activityMarkerToken(compareMode)} " +
        "publicMultiStackDepthLayerCompareEvidence=${activityMarkerToken(compareEvidence)} " +
        "publicMultiStackDepthLayerPolicyManifest=0:mono-layer0,1:mono-layer1,2:eye-index,3:compare " +
        "panelRenderOrder=spatial-sdk-quad-layer-z-index runtimeCrash=false"
  }

  fun depthAlignmentUpdateFailedMarker(
      source: String,
      updatedAlignment: PrivateLayerDepthAlignment,
      error: String,
      message: String,
  ): String =
      "channel=private-layer-panel status=depth-alignment-update-failed " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          depthAlignmentMarkerFields(updatedAlignment, "") + " " +
          "error=${activityMarkerToken(error)} " +
          "message=${activityMarkerToken(message)} runtimeCrash=false"

  fun depthAlignmentSubmittedMarker(
      source: String,
      updateMask: Long,
      previousAlignment: PrivateLayerDepthAlignment,
      updatedAlignment: PrivateLayerDepthAlignment,
  ): String =
      "channel=private-layer-panel status=depth-alignment-submitted " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          "transport=jni-live-queue publicMultiStackDepthAlignmentControl=true updateMask=$updateMask " +
          depthAlignmentMarkerFields(previousAlignment, "previous") + " " +
          depthAlignmentMarkerFields(updatedAlignment, "") + " " +
          "panelRenderOrder=spatial-sdk-quad-layer-z-index runtimeCrash=false"

  private fun depthAlignmentMarkerFields(
      alignment: PrivateLayerDepthAlignment,
      prefix: String,
  ): String {
    return if (prefix.isBlank()) {
      "publicMultiStackDepthAlignmentLeftOffsetUv=${activityMarkerFloat6(alignment.leftX)},${activityMarkerFloat6(alignment.leftY)} " +
          "publicMultiStackDepthAlignmentRightOffsetUv=${activityMarkerFloat6(alignment.rightX)},${activityMarkerFloat6(alignment.rightY)} " +
          "publicMultiStackDepthAlignmentSampleScale=${activityMarkerFloat(alignment.sampleScale)} " +
          "publicMultiStackDepthAlignmentSampleScaleY=${activityMarkerFloat(alignment.sampleScaleY)} " +
          "publicMultiStackDepthAlignmentRollDegrees=${activityMarkerFloat(alignment.rollDegrees)} " +
          "publicMultiStackDepthMetadataAutoAlignRequested=${alignment.metadataAutoAlign}"
    } else {
      "previousPublicMultiStackDepthAlignmentLeftOffsetUv=${activityMarkerFloat6(alignment.leftX)},${activityMarkerFloat6(alignment.leftY)} " +
          "previousPublicMultiStackDepthAlignmentRightOffsetUv=${activityMarkerFloat6(alignment.rightX)},${activityMarkerFloat6(alignment.rightY)} " +
          "previousPublicMultiStackDepthAlignmentSampleScale=${activityMarkerFloat(alignment.sampleScale)} " +
          "previousPublicMultiStackDepthAlignmentSampleScaleY=${activityMarkerFloat(alignment.sampleScaleY)} " +
          "previousPublicMultiStackDepthAlignmentRollDegrees=${activityMarkerFloat(alignment.rollDegrees)} " +
          "previousPublicMultiStackDepthMetadataAutoAlignRequested=${alignment.metadataAutoAlign}"
    }
  }
}
