package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class PrivateLayerChoice(
    val index: Int,
    val title: String,
    val token: String,
)

internal data class PrivateLayerDepthAlignment(
    val leftX: Float = BuildConfig.DEPTH_ALIGNMENT_DEFAULT_LEFT_X,
    val leftY: Float = BuildConfig.DEPTH_ALIGNMENT_DEFAULT_LEFT_Y,
    val rightX: Float = BuildConfig.DEPTH_ALIGNMENT_DEFAULT_RIGHT_X,
    val rightY: Float = BuildConfig.DEPTH_ALIGNMENT_DEFAULT_RIGHT_Y,
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

internal data class PrivateLayerGuideProcessing(
    val preblurKernel: Int = PrivateLayerControls.guideKernelNativeBox5,
    val preblurInput: Int = PrivateLayerControls.guideInputLuma,
    val postblurKernel: Int = PrivateLayerControls.guideKernelNativeBox5,
    val cameraSampling: Int = PrivateLayerControls.cameraSamplingThinLineTent5,
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
  const val guideKernelNativeBox5: Int = 0
  const val guideKernelGaussian5: Int = 1
  const val guideInputLuma: Int = 0
  const val guideInputPreserveRgb: Int = 1
  const val cameraSamplingLinear: Int = 0
  const val cameraSamplingThinLineTent5: Int = 1
  val nativeParityGuideProcessing = PrivateLayerGuideProcessing()
  val gaussianRgbGuideProcessing =
      PrivateLayerGuideProcessing(
          preblurKernel = guideKernelGaussian5,
          preblurInput = guideInputPreserveRgb,
          postblurKernel = guideKernelGaussian5,
      )

  val layers =
      listOf(
          PrivateLayerChoice(0, "Final composition", "final-composition"),
          PrivateLayerChoice(1, "Camera brightness", "camera-brightness"),
          PrivateLayerChoice(2, "Brightness after first blur", "brightness-after-first-blur"),
          PrivateLayerChoice(
              3,
              "Distortion strength · before smoothing",
              "distortion-strength-before-smoothing",
          ),
          PrivateLayerChoice(
              4,
              "Distortion strength · smoothed",
              "distortion-strength-smoothed",
          ),
          PrivateLayerChoice(
              5,
              "Distortion strength · depth adjusted",
              "distortion-strength-depth-adjusted",
          ),
          PrivateLayerChoice(6, "Meta depth diagnostic", "meta-depth-diagnostic"),
          PrivateLayerChoice(7, "Meta poster LUT", "meta-passthrough-edge-window"),
          PrivateLayerChoice(8, "Raw camera projection", "raw-camera-projection"),
      )

  val centerContentLayers =
      listOf(0, 8, 1, 2, 3, 4, 5, 6).map { index ->
        layers.first { it.index == index }
      }

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

  /**
   * Mirrors the private projection shader's environment-depth reads. Cycle can visit a
   * depth-consuming layer, while Final, depth-adjusted strength, and Meta depth diagnostic sample
   * environment depth directly. The remaining fixed diagnostic layers cannot be changed by
   * depth, so keeping the provider alive for them is pure background work.
   */
  fun environmentDepthConsumerRequired(layerOverride: Float): Boolean {
    if (layerOverride < 0.0f) return true
    return when (layerOverride.toInt()) {
      0, 5, 6 -> true
      else -> false
    }
  }

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

  fun normalizeGuideKernel(code: Int): Int =
      if (code == guideKernelGaussian5) guideKernelGaussian5 else guideKernelNativeBox5

  fun normalizeGuideInput(code: Int): Int =
      if (code == guideInputPreserveRgb) guideInputPreserveRgb else guideInputLuma

  fun normalizeCameraSampling(code: Int): Int =
      if (code == cameraSamplingLinear) cameraSamplingLinear else cameraSamplingThinLineTent5

  fun guideKernelForToken(token: String): Int? =
      when (token.trim().lowercase().replace("_", "-")) {
        "native-box5", "box5", "box", "native", "0" -> guideKernelNativeBox5
        "gaussian5", "gaussian", "1" -> guideKernelGaussian5
        else -> null
      }

  fun guideInputForToken(token: String): Int? =
      when (token.trim().lowercase().replace("_", "-")) {
        "luma", "luminance", "grayscale", "native", "0" -> guideInputLuma
        "rgb", "preserve-rgb", "rgb-preserve", "color", "1" -> guideInputPreserveRgb
        else -> null
      }

  fun cameraSamplingForToken(token: String): Int? =
      when (token.trim().lowercase().replace("_", "-")) {
        "linear", "bilinear", "0" -> cameraSamplingLinear
        "thin-line-aa", "thin-line-tent5", "tent5", "aa", "1" -> cameraSamplingThinLineTent5
        else -> null
      }

  fun guideKernelToken(code: Int): String =
      if (normalizeGuideKernel(code) == guideKernelGaussian5) "gaussian5" else "native-box5"

  fun guideInputToken(code: Int): String =
      if (normalizeGuideInput(code) == guideInputPreserveRgb) "rgb-preserve" else "luma"

  fun cameraSamplingToken(code: Int): String =
      if (normalizeCameraSampling(code) == cameraSamplingLinear) "linear" else "thin-line-tent5"

  fun guideProcessingPresetToken(processing: PrivateLayerGuideProcessing): String {
    val normalized = normalizeGuideProcessing(processing)
    return when {
      normalized.preblurKernel == guideKernelNativeBox5 &&
          normalized.preblurInput == guideInputLuma &&
          normalized.postblurKernel == guideKernelNativeBox5 -> "native-parity"
      normalized.preblurKernel == guideKernelGaussian5 &&
          normalized.preblurInput == guideInputPreserveRgb &&
          normalized.postblurKernel == guideKernelGaussian5 -> "gaussian-rgb-diagnostic"
      else -> "custom-ab"
    }
  }

  fun normalizeGuideProcessing(
      processing: PrivateLayerGuideProcessing
  ): PrivateLayerGuideProcessing =
      PrivateLayerGuideProcessing(
          preblurKernel = normalizeGuideKernel(processing.preblurKernel),
          preblurInput = normalizeGuideInput(processing.preblurInput),
          postblurKernel = normalizeGuideKernel(processing.postblurKernel),
          cameraSampling = normalizeCameraSampling(processing.cameraSampling),
      )
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

  fun normalizeGuideProcessing(
      requestedProcessing: PrivateLayerGuideProcessing
  ): PrivateLayerGuideProcessing = PrivateLayerControls.normalizeGuideProcessing(requestedProcessing)

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
          "privateLayerPanelInputButtons=trigger-l+trigger-r " +
          "privateLayerPanelRightPrimarySelectEnabled=false " +
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

  fun guideProcessingUpdateFailedMarker(
      source: String,
      updatedProcessing: PrivateLayerGuideProcessing,
      error: String,
      message: String,
  ): String =
      "channel=private-layer-panel status=guide-processing-update-failed " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          guideProcessingMarkerFields(updatedProcessing) + " " +
          "error=${activityMarkerToken(error)} message=${activityMarkerToken(message)} " +
          "runtimeCrash=false"

  fun guideProcessingSubmittedMarker(
      source: String,
      updateMask: Long,
      previousProcessing: PrivateLayerGuideProcessing,
      updatedProcessing: PrivateLayerGuideProcessing,
  ): String =
      "channel=private-layer-panel status=guide-processing-submitted " +
          "source=${activityMarkerToken(source)} spatialPrivateLayerControlPanel=true " +
          "transport=jni-live-queue publicGuideProcessingControl=true updateMask=$updateMask " +
          guideProcessingMarkerFields(previousProcessing, "previous") + " " +
          guideProcessingMarkerFields(updatedProcessing) + " " +
          "publicGuideProcessingDefault=native-parity " +
          "publicGuideKernelAlternatives=native-box5+gaussian5 " +
          "publicGuideInputAlternatives=luma+rgb-preserve " +
          "publicCameraSamplingAlternatives=linear+thin-line-tent5 " +
          "publicCameraSamplingDefault=thin-line-tent5 " +
          "runtimeCrash=false"

  private fun guideProcessingMarkerFields(
      processing: PrivateLayerGuideProcessing,
      prefix: String = "",
  ): String {
    val normalized = normalizeGuideProcessing(processing)
    val namePrefix = if (prefix.isBlank()) "public" else prefix
    return "${namePrefix}GuideProcessingPreset=${activityMarkerToken(PrivateLayerControls.guideProcessingPresetToken(normalized))} " +
        "${namePrefix}GuidePreblurKernel=${activityMarkerToken(PrivateLayerControls.guideKernelToken(normalized.preblurKernel))} " +
        "${namePrefix}GuidePreblurInput=${activityMarkerToken(PrivateLayerControls.guideInputToken(normalized.preblurInput))} " +
        "${namePrefix}GuidePostblurKernel=${activityMarkerToken(PrivateLayerControls.guideKernelToken(normalized.postblurKernel))} " +
        "${namePrefix}CameraSampling=${activityMarkerToken(PrivateLayerControls.cameraSamplingToken(normalized.cameraSampling))}"
  }

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
