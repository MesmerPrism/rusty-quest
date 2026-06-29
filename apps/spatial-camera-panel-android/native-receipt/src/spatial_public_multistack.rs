#[cfg(test)]
const PUBLIC_MULTISTACK_SCHEMA: &str = "rusty.quest.spatial_camera_panel.public_multistack.v1";
#[cfg(test)]
const PUBLIC_MULTISTACK_CARRIER: &str = "scenequadlayer-createAsAndroid-vulkan-wsi";
#[cfg(test)]
const PUBLIC_MULTISTACK_LAYER_COUNT: u32 = 7;
#[cfg(test)]
const PUBLIC_MULTISTACK_GUIDE_TARGET_COUNT: u32 = 5;
#[cfg(test)]
const PUBLIC_MULTISTACK_GUIDE_PASS_COUNT: u32 = 6;
#[cfg(test)]
const PUBLIC_MULTISTACK_PUBLIC_BLUR_PASS_COUNT: u32 = 4;
#[cfg(test)]
const PUBLIC_MULTISTACK_GUIDE_TARGET_MANIFEST: &str =
    "0:opaque-analysis0-target,1:public-blur-temp,2:public-preblur-guide,\
     3:opaque-analysis1-target,4:public-postblur-guide";
#[cfg(test)]
const PUBLIC_MULTISTACK_GUIDE_PASS_MANIFEST: &str =
    "0:opaque-analysis0,1:public-preblur-horizontal,2:public-preblur-vertical,\
     3:opaque-analysis1,4:public-postblur-horizontal,5:public-postblur-vertical";
#[cfg(test)]
const PUBLIC_MULTISTACK_LAYER_MANIFEST: &str =
    "0:final,1:opaque-analysis0-slot,2:public-guide-blur,\
     3:opaque-analysis1-slot,4:public-post-blur-guide,\
     5:opaque-projection-slot,6:public-depth-diagnostic";

include!(concat!(
    env!("OUT_DIR"),
    "/spatial_public_multistack_build.rs"
));

#[cfg(not(target_os = "android"))]
const _: [f32; 4] = OPAQUE_PROJECTION_EFFECT;

const PUBLIC_MULTISTACK_STATIC_MARKER_FIELDS: &str = concat!(
        "publicMultiStackActive=true ",
        "publicMultiStackSchema=",
        "rusty.quest.spatial_camera_panel.public_multistack.v1 ",
        "publicMultiStackCarrier=scenequadlayer-createAsAndroid-vulkan-wsi ",
        "publicMultiStackLayerCount=7 ",
        "publicMultiStackGuideTargets=5 ",
        "publicMultiStackGuidePasses=6 ",
        "publicMultiStackPublicGuidePasses=4 ",
        "publicMultiStackPublicBlurPasses=4 ",
        "publicMultiStackOpaqueGuidePasses=2 ",
        "publicMultiStackDownstreamPayloadActive=false ",
        "publicMultiStackOpaqueSlots=1,3,5 ",
        "publicMultiStackPublicLayers=0,2,4,6 ",
        "publicMultiStackGuideTargetManifest=0:opaque-analysis0-target,1:public-blur-temp,",
        "2:public-preblur-guide,3:opaque-analysis1-target,4:public-postblur-guide ",
        "publicMultiStackGuidePassManifest=0:opaque-analysis0,",
        "1:public-preblur-horizontal,2:public-preblur-vertical,3:opaque-analysis1,",
        "4:public-postblur-horizontal,5:public-postblur-vertical ",
        "publicGuideBlurKernel=separable-5tap ",
        "publicGuideBlurShader=public_guide_blur.frag.glsl ",
        "publicGuideBlurLayer=public-contract ",
        "publicGuideBlurRuntimeReady=false ",
        "publicMultiStackPassExecutionReady=false ",
        "publicMultiStackOpaqueGuideShaderEnv=RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER ",
        "publicMultiStackOpaqueProjectionShaderEnv=RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER ",
        "publicMultiStackLayerManifest=0:final,1:opaque-analysis0-slot,2:public-guide-blur,",
        "3:opaque-analysis1-slot,4:public-post-blur-guide,",
        "5:opaque-projection-slot,6:public-depth-diagnostic"
);

pub(crate) fn public_multistack_marker_fields() -> String {
    let passthrough_fields = spatial_native_passthrough_marker_fields();
    let depth_fields = spatial_environment_depth_marker_fields();
    format!(
        "{} {} {} publicGuideBlurShaderCompiled={} publicGuideBlurShaderBytes={} publicMultiStackOpaqueGuideShaderCompiled={} publicMultiStackOpaqueGuideShaderBytes={} publicMultiStackOpaqueGuideShaderPassCount={} publicMultiStackOpaqueGuideShaderPassVariantsCompiled={} publicMultiStackOpaqueGuideShaderPassByteCounts={} publicMultiStackOpaqueProjectionShaderCompiled={} publicMultiStackOpaqueProjectionShaderBytes={} publicMultiStackOpaquePayloadExecutionReady=false",
        PUBLIC_MULTISTACK_STATIC_MARKER_FIELDS,
        passthrough_fields,
        depth_fields,
        bool_marker(PUBLIC_GUIDE_BLUR_SHADER_COMPILED),
        PUBLIC_GUIDE_BLUR_SHADER_BYTE_COUNT,
        bool_marker(OPAQUE_GUIDE_SHADER_COMPILED),
        OPAQUE_GUIDE_SHADER_BYTE_COUNT,
        OPAQUE_GUIDE_SHADER_PASS_COUNT,
        opaque_guide_shader_pass_variants_compiled(),
        opaque_guide_shader_pass_byte_counts_marker(),
        bool_marker(OPAQUE_PROJECTION_SHADER_COMPILED),
        OPAQUE_PROJECTION_SHADER_BYTE_COUNT,
    )
}

#[cfg(target_os = "android")]
fn spatial_native_passthrough_marker_fields() -> String {
    crate::spatial_native_passthrough::spatial_native_passthrough_marker_fields()
}

#[cfg(not(target_os = "android"))]
fn spatial_native_passthrough_marker_fields() -> String {
    "nativePassthroughRequested=true nativePassthroughLayerActive=false nativePassthroughActivationPath=spatial-native-receipt-xr-fb-passthrough nativePassthroughCompositionLayerSubmission=spatial-sdk-owned-end-frame".to_string()
}

#[cfg(target_os = "android")]
fn spatial_environment_depth_marker_fields() -> String {
    crate::spatial_environment_depth::spatial_environment_depth_marker_fields()
}

#[cfg(not(target_os = "android"))]
fn spatial_environment_depth_marker_fields() -> String {
    "publicMultiStackDepthSource=spatial-fallback-depth-descriptor publicMultiStackDepthProviderRequested=false publicMultiStackDepthRealProviderBound=false publicMultiStackDepthValidData=false publicMultiStackDepthPermissionSurface=horizonos.permission.USE_SCENE+USE_SCENE_DATA environmentDepthSource=spatial-fallback-depth-descriptor environmentDepthProviderState=not-bound environmentDepthProviderAvailable=false environmentDepthRealProviderBound=false environmentDepthAcquireStatus=not-attempted-provider-not-bound environmentDepthValidData=false environmentDepthDebugValidSampleCount=0 environmentDepthAcquiredFrameCount=0".to_string()
}

pub(crate) fn public_multistack_inactive_marker_fields() -> &'static str {
    "publicMultiStackActive=false"
}

fn bool_marker(value: bool) -> &'static str {
    if value {
        "true"
    } else {
        "false"
    }
}

fn opaque_guide_shader_pass_variants_compiled() -> usize {
    if OPAQUE_GUIDE_SHADER_COMPILED {
        OPAQUE_GUIDE_SHADER_PASS_COUNT
    } else {
        0
    }
}

fn opaque_guide_shader_pass_byte_counts_marker() -> String {
    OPAQUE_GUIDE_SHADER_PASS_BYTE_COUNTS
        .iter()
        .map(usize::to_string)
        .collect::<Vec<_>>()
        .join(",")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn marker_fields_match_public_contract() {
        let fields = public_multistack_marker_fields();
        assert!(fields.contains(PUBLIC_MULTISTACK_SCHEMA));
        assert!(fields.contains(PUBLIC_MULTISTACK_CARRIER));
        assert!(fields.contains(PUBLIC_MULTISTACK_GUIDE_TARGET_MANIFEST));
        assert!(fields.contains(PUBLIC_MULTISTACK_GUIDE_PASS_MANIFEST));
        assert!(fields.contains(PUBLIC_MULTISTACK_LAYER_MANIFEST));
        assert!(fields.contains(&format!(
            "publicMultiStackLayerCount={PUBLIC_MULTISTACK_LAYER_COUNT}"
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackGuideTargets={PUBLIC_MULTISTACK_GUIDE_TARGET_COUNT}"
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackGuidePasses={PUBLIC_MULTISTACK_GUIDE_PASS_COUNT}"
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackPublicGuidePasses={PUBLIC_MULTISTACK_PUBLIC_BLUR_PASS_COUNT}"
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackPublicBlurPasses={PUBLIC_MULTISTACK_PUBLIC_BLUR_PASS_COUNT}"
        )));
        assert!(fields.contains("publicMultiStackOpaqueGuidePasses=2"));
        assert!(fields.contains("publicGuideBlurKernel=separable-5tap"));
        assert!(fields.contains("publicGuideBlurShader=public_guide_blur.frag.glsl"));
        assert!(fields.contains("publicGuideBlurShaderCompiled=true"));
        assert!(fields.contains("publicGuideBlurShaderBytes="));
        assert!(fields.contains("publicMultiStackPassExecutionReady=false"));
        assert!(fields.contains("RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER"));
        assert!(fields.contains("RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER"));
        assert!(fields.contains(&format!(
            "publicMultiStackOpaqueGuideShaderCompiled={}",
            bool_marker(OPAQUE_GUIDE_SHADER_COMPILED)
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackOpaqueGuideShaderBytes={OPAQUE_GUIDE_SHADER_BYTE_COUNT}"
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackOpaqueGuideShaderPassCount={OPAQUE_GUIDE_SHADER_PASS_COUNT}"
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackOpaqueGuideShaderPassVariantsCompiled={}",
            opaque_guide_shader_pass_variants_compiled()
        )));
        assert!(fields.contains("publicMultiStackOpaqueGuideShaderPassByteCounts="));
        assert!(fields.contains(&format!(
            "publicMultiStackOpaqueProjectionShaderCompiled={}",
            bool_marker(OPAQUE_PROJECTION_SHADER_COMPILED)
        )));
        assert!(fields.contains(&format!(
            "publicMultiStackOpaqueProjectionShaderBytes={OPAQUE_PROJECTION_SHADER_BYTE_COUNT}"
        )));
        assert!(fields.contains("publicMultiStackOpaquePayloadExecutionReady=false"));
        assert!(fields.contains("publicMultiStackDownstreamPayloadActive=false"));
        assert!(!fields.contains("publicMultiStackDownstreamPayloadActive=true"));
        assert_eq!(
            public_multistack_inactive_marker_fields(),
            "publicMultiStackActive=false"
        );
    }
}
