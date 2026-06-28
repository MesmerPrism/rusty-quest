package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal object SpatialPublicMultiStack {
  private const val SCHEMA = "rusty.quest.spatial_camera_panel.public_multistack.v1"
  private const val CARRIER = "scenequadlayer-createAsAndroid-vulkan-wsi"
  private const val LAYER_COUNT = 7
  private const val GUIDE_TARGET_COUNT = 5
  private const val GUIDE_PASS_COUNT = 6
  private const val PUBLIC_BLUR_PASS_COUNT = 4
  private const val GUIDE_TARGET_MANIFEST =
      "0:opaque-analysis0-target,1:public-blur-temp,2:public-preblur-guide," +
          "3:opaque-analysis1-target,4:public-postblur-guide"
  private const val GUIDE_PASS_MANIFEST =
      "0:opaque-analysis0,1:public-preblur-horizontal,2:public-preblur-vertical," +
          "3:opaque-analysis1,4:public-postblur-horizontal,5:public-postblur-vertical"
  private const val LAYER_MANIFEST =
      "0:final,1:opaque-analysis0-slot,2:public-guide-blur," +
          "3:opaque-analysis1-slot,4:public-post-blur-guide," +
          "5:opaque-projection-slot,6:public-depth-diagnostic"

  fun markerFields(): String =
      "publicMultiStackActive=true " +
          "publicMultiStackSchema=$SCHEMA " +
          "publicMultiStackCarrier=$CARRIER " +
          "publicMultiStackLayerCount=$LAYER_COUNT " +
          "publicMultiStackGuideTargets=$GUIDE_TARGET_COUNT " +
          "publicMultiStackGuidePasses=$GUIDE_PASS_COUNT " +
          "publicMultiStackPublicGuidePasses=$PUBLIC_BLUR_PASS_COUNT " +
          "publicMultiStackPublicBlurPasses=$PUBLIC_BLUR_PASS_COUNT " +
          "publicMultiStackOpaqueGuidePasses=2 " +
          "publicMultiStackDownstreamPayloadActive=false " +
          "publicMultiStackOpaqueSlots=1,3,5 " +
          "publicMultiStackPublicLayers=0,2,4,6 " +
          "publicMultiStackGuideTargetManifest=$GUIDE_TARGET_MANIFEST " +
          "publicMultiStackGuidePassManifest=$GUIDE_PASS_MANIFEST " +
          "publicGuideBlurKernel=separable-5tap " +
          "publicGuideBlurShader=public_guide_blur.frag.glsl " +
          "publicGuideBlurShaderCompiled=true " +
          "publicGuideBlurLayer=public-contract " +
          "publicGuideBlurRuntimeReady=false " +
          "publicMultiStackOpaqueGuideShaderEnv=RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER " +
          "publicMultiStackOpaqueProjectionShaderEnv=RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER " +
          "publicMultiStackLayerManifest=$LAYER_MANIFEST"

  fun inactiveMarkerFields(): String = "publicMultiStackActive=false"
}
