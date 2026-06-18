//! Runtime property options for the native renderer.
//!
//! This module keeps Android property parsing separate from the OpenXR/Vulkan
//! frame loop so replay-proof, live-hand, and SDF visual modes stay testable.

pub(crate) const PROP_ENABLE_SDF_VISUAL: &str =
    "debug.rustyquest.native_renderer.sdf.visual.enabled";
pub(crate) const PROP_RENDER_MODE: &str = "debug.rustyquest.native_renderer.render.mode";
pub(crate) const PROP_CAMERA_OUTPUT_MODE: &str = "debug.rustyquest.native_renderer.camera.output";
pub(crate) const PROP_CAMERA_YCBCR_MODE: &str =
    "debug.rustyquest.native_renderer.camera.ycbcr.mode";
pub(crate) const PROP_CAMERA_RESOLUTION_PROFILE: &str =
    "debug.rustyquest.native_renderer.camera.resolution";
pub(crate) const PROP_CAMERA_READER_MAX_IMAGES: &str =
    "debug.rustyquest.native_renderer.camera.reader_max_images";
pub(crate) const PROP_CAMERA_QUALITY_PROFILE: &str =
    "debug.rustyquest.native_renderer.camera.quality_profile";
pub(crate) const PROP_CAMERA_SYNC_MODE: &str = "debug.rustyquest.native_renderer.camera.sync_mode";
pub(crate) const PROP_CAMERA_DIRECT_BORDER_OPACITY: &str =
    "debug.rustyquest.native_renderer.camera.direct_border.opacity";
pub(crate) const PROP_SWAPCHAIN_COLOR_FORMAT_MODE: &str =
    "debug.rustyquest.native_renderer.swapchain.color_format";
pub(crate) const PROP_SDF_UPDATE_PERIOD_FRAMES: &str =
    "debug.rustyquest.native_renderer.sdf.update_period_frames";
pub(crate) const PROP_REPLAY_VISUAL_PROOF_ENABLED: &str =
    "debug.rustyquest.native_renderer.replay.visual_proof.enabled";
pub(crate) const PROP_HAND_MESH_INPUT_SOURCE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.input.source";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha";
pub(crate) const PROP_HAND_MESH_GRAFT_COPIES_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled";
pub(crate) const PROP_HAND_MESH_GRAFT_COPY_SCALE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale";
pub(crate) const PROP_HAND_MESH_REAL_HANDS_VISIBLE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.real_hands.visible";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.enabled";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_PER_HAND: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.per_hand";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_RADIUS_M: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.radius_m";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_DYNAMICS: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.dynamics";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.transparency.blend_mode";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.transparency.composition_mode";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.transparency.depth_suppression_strength";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.ordering.mode";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.ordering.implementation";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.ordering.interval_frames";
pub(crate) const PROP_PROCESSING_LAYER: &str = "debug.rustyquest.native_renderer.processing.layer";
pub(crate) const PROP_PROJECTION_BORDER_POLICY: &str =
    "debug.rustyquest.native_renderer.projection.border.policy";
pub(crate) const PROP_PROJECTION_BORDER_OPACITY: &str =
    "debug.rustyquest.native_renderer.projection.border.opacity";
pub(crate) const PROP_PROJECTION_AREA_OPACITY: &str =
    "debug.rustyquest.native_renderer.projection.area.opacity";
pub(crate) const PROP_PERIPHERAL_STRETCH_CORE_SCALE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.core.scale";
pub(crate) const PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.edge.inset.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_MAX_INSET_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.max.inset.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_CURVE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.curve";
pub(crate) const PROP_PERIPHERAL_STRETCH_INNER_BLEND_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.inner.blend.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_BLEND_CURVE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.blend.curve";
pub(crate) const PROP_PERIPHERAL_STRETCH_BLEND_MODE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.blend.mode";
pub(crate) const PROP_PERIPHERAL_STRETCH_DEBUG: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.debug";
pub(crate) const PROP_PRIVATE_LAYER_ENABLED: &str =
    "debug.rustyquest.native_renderer.private_layer.enabled";
pub(crate) const PROP_PRIVATE_LAYER_SECONDS: &str =
    "debug.rustyquest.native_renderer.private_layer.layer_seconds";
pub(crate) const PROP_PRIVATE_LAYER_OVERRIDE: &str =
    "debug.rustyquest.native_renderer.private_layer.layer_override";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT0: &str =
    "debug.rustyquest.native_renderer.private_layer.effect0";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT1: &str =
    "debug.rustyquest.native_renderer.private_layer.effect1";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT2: &str =
    "debug.rustyquest.native_renderer.private_layer.effect2";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT3: &str =
    "debug.rustyquest.native_renderer.private_layer.effect3";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeRendererRenderMode {
    CustomStereoProjection,
    NativePassthroughGraftOnly,
    SolidBlackHandsAndGrafts,
    SolidBlackOpenXrHandsAnchorParticles,
}

impl NativeRendererRenderMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        let normalized = value
            .as_deref()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        match normalized.as_str() {
            "native-passthrough-graft-only"
            | "passthrough-graft-only"
            | "graft-only"
            | "native-passthrough" => Self::NativePassthroughGraftOnly,
            "solid-black-hands-and-grafts"
            | "black-hands-and-grafts"
            | "solid-black"
            | "black-background-hands-and-grafts" => Self::SolidBlackHandsAndGrafts,
            "solid-black-openxr-hands-anchor-particles"
            | "solid-black-openxr-hands"
            | "solid-black-default-hands-anchor-particles"
            | "solid-black-default-hands"
            | "black-background-openxr-hands-anchor-particles" => {
                Self::SolidBlackOpenXrHandsAnchorParticles
            }
            _ => Self::CustomStereoProjection,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "custom-stereo-projection",
            Self::NativePassthroughGraftOnly => "native-passthrough-graft-only",
            Self::SolidBlackHandsAndGrafts => "solid-black-hands-and-grafts",
            Self::SolidBlackOpenXrHandsAnchorParticles => {
                "solid-black-openxr-hands-anchor-particles"
            }
        }
    }

    pub(crate) fn uses_custom_stereo_projection(self) -> bool {
        matches!(self, Self::CustomStereoProjection)
    }

    pub(crate) fn uses_native_passthrough(self) -> bool {
        matches!(self, Self::NativePassthroughGraftOnly)
    }

    pub(crate) fn uses_solid_black_background(self) -> bool {
        matches!(
            self,
            Self::SolidBlackHandsAndGrafts | Self::SolidBlackOpenXrHandsAnchorParticles
        )
    }

    pub(crate) fn requests_openxr_default_hand_visual(self) -> bool {
        matches!(self, Self::SolidBlackOpenXrHandsAnchorParticles)
    }

    pub(crate) fn forces_graft_copies(self) -> bool {
        matches!(
            self,
            Self::NativePassthroughGraftOnly | Self::SolidBlackHandsAndGrafts
        )
    }

    pub(crate) fn forces_real_hand_meshes(self) -> bool {
        matches!(self, Self::SolidBlackHandsAndGrafts)
    }

    pub(crate) fn camera_runtime_mode(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "camera2-hwb",
            Self::NativePassthroughGraftOnly => "skipped-native-passthrough",
            Self::SolidBlackHandsAndGrafts => "skipped-solid-black-hands-and-grafts",
            Self::SolidBlackOpenXrHandsAnchorParticles => {
                "skipped-solid-black-openxr-hands-anchor-particles"
            }
        }
    }

    pub(crate) fn disabled_camera_projection_path(self) -> &'static str {
        match self {
            Self::CustomStereoProjection => "metadata-target-direct-hwb-fallback",
            Self::NativePassthroughGraftOnly => "disabled-native-passthrough-graft-only",
            Self::SolidBlackHandsAndGrafts => "disabled-solid-black-hands-and-grafts",
            Self::SolidBlackOpenXrHandsAnchorParticles => {
                "disabled-solid-black-openxr-hands-anchor-particles"
            }
        }
    }

    pub(crate) fn allows_sdf_visual(self) -> bool {
        matches!(self, Self::CustomStereoProjection)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraOutputMode {
    Auto,
    DirectHwb,
    GuidePublic,
    Disabled,
}

impl NativeCameraOutputMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "direct" | "direct-hwb" | "direct-hardware-buffer" | "raw" | "raw-hwb" => {
                Self::DirectHwb
            }
            "guide" | "guide-public" | "public-guide" | "guide-texture" => Self::GuidePublic,
            "0" | "false" | "no" | "off" | "disabled" => Self::Disabled,
            _ => Self::Auto,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::DirectHwb => "direct-hwb",
            Self::GuidePublic => "guide-public",
            Self::Disabled => "disabled",
        }
    }

    pub(crate) fn camera_import_enabled(self) -> bool {
        !matches!(self, Self::Disabled)
    }

    pub(crate) fn private_layer_projection_enabled(self) -> bool {
        matches!(self, Self::Auto)
    }

    pub(crate) fn guide_projection_enabled(self) -> bool {
        matches!(self, Self::Auto | Self::GuidePublic)
    }

    pub(crate) fn guide_graph_processing_enabled(self) -> bool {
        matches!(self, Self::Auto | Self::GuidePublic)
    }

    pub(crate) fn direct_hwb_forced(self) -> bool {
        matches!(self, Self::DirectHwb)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraYcbcrMode {
    AndroidSuggested,
    ForcedBt601Narrow,
}

impl NativeCameraYcbcrMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "bt601-narrow"
            | "bt601-limited"
            | "forced-bt601"
            | "forced-bt601-narrow"
            | "forced-bt601-limited"
            | "cpuyuv-reference" => Self::ForcedBt601Narrow,
            _ => Self::AndroidSuggested,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::AndroidSuggested => "android-suggested",
            Self::ForcedBt601Narrow => "forced-bt601-narrow",
        }
    }

    pub(crate) fn conversion_mode(self) -> &'static str {
        match self {
            Self::AndroidSuggested => "android-suggested-ycbcr",
            Self::ForcedBt601Narrow => "forced-bt601-limited-cpuyuv-reference",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraResolutionProfile {
    Square1280,
    Wide1280x960,
    ClosestSupported,
}

impl NativeCameraResolutionProfile {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "1280x960" | "wide-1280x960" | "quest-1280x960" => Self::Wide1280x960,
            "closest" | "closest-supported" | "auto-supported" => Self::ClosestSupported,
            _ => Self::Square1280,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Square1280 => "1280x1280",
            Self::Wide1280x960 => "1280x960",
            Self::ClosestSupported => "closest-supported",
        }
    }

    pub(crate) fn requested_size(self) -> Option<[i32; 2]> {
        match self {
            Self::Square1280 => Some([1280, 1280]),
            Self::Wide1280x960 => Some([1280, 960]),
            Self::ClosestSupported => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraQualityProfile {
    DirectBaseline,
    DirectLowNoise30,
    DirectLowLatency60,
    DirectQualityProbe,
}

impl NativeCameraQualityProfile {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "direct-low-noise-30" | "low-noise-30" | "noise-30" | "low-noise" => {
                Self::DirectLowNoise30
            }
            "direct-low-latency-60" | "low-latency-60" | "latency-60" | "low-latency" => {
                Self::DirectLowLatency60
            }
            "direct-quality-probe" | "quality-probe" | "quality" => Self::DirectQualityProbe,
            _ => Self::DirectBaseline,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::DirectBaseline => "direct-baseline",
            Self::DirectLowNoise30 => "direct-low-noise-30",
            Self::DirectLowLatency60 => "direct-low-latency-60",
            Self::DirectQualityProbe => "direct-quality-probe",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeCameraSyncMode {
    EarlyDeleteAhbRetained,
    HoldImageUntilGpuFence,
    DeleteAsyncReleaseFence,
}

impl NativeCameraSyncMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "hold-image-until-gpu-fence" | "hold-image" | "hold-image-until-fence" => {
                Self::HoldImageUntilGpuFence
            }
            "delete-async-release-fence" | "delete-async" | "async-release-fence" => {
                Self::DeleteAsyncReleaseFence
            }
            _ => Self::EarlyDeleteAhbRetained,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::EarlyDeleteAhbRetained => "early-delete-ahb-retained",
            Self::HoldImageUntilGpuFence => "hold-image-until-gpu-fence",
            Self::DeleteAsyncReleaseFence => "delete-async-release-fence",
        }
    }

    pub(crate) fn active_marker_value(self) -> &'static str {
        match self {
            Self::EarlyDeleteAhbRetained => "early-delete-ahb-retained",
            Self::HoldImageUntilGpuFence => "hold-image-until-gpu-fence",
            Self::DeleteAsyncReleaseFence => "early-delete-ahb-retained",
        }
    }

    pub(crate) fn implementation_status(self) -> &'static str {
        match self {
            Self::EarlyDeleteAhbRetained => "active-baseline",
            Self::HoldImageUntilGpuFence => "active-diagnostic",
            Self::DeleteAsyncReleaseFence => "declared-diagnostic-not-active-yet",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeSwapchainColorFormatMode {
    Auto,
    Srgb,
    Unorm,
}

impl NativeSwapchainColorFormatMode {
    pub(crate) fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "srgb" | "s-rgb" | "prefer-srgb" => Self::Srgb,
            "unorm" | "linear" | "prefer-unorm" => Self::Unorm,
            _ => Self::Auto,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto-srgb-preferred",
            Self::Srgb => "srgb",
            Self::Unorm => "unorm",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CompactHandInputSourceMode {
    Auto,
    Disabled,
    RecordedReplay,
    LiveMeta,
}

impl CompactHandInputSourceMode {
    pub(crate) fn from_property(value: Option<String>, replay_visual_proof_enabled: bool) -> Self {
        let normalized = value
            .as_deref()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        match normalized.as_str() {
            "0" | "false" | "no" | "off" | "disabled" | "none" => Self::Disabled,
            "recorded" | "recorded-replay" | "replay" => Self::RecordedReplay,
            "live" | "live-meta" | "openxr" | "live-meta-openxr-hand-tracking" => Self::LiveMeta,
            "auto" => Self::Auto,
            _ if replay_visual_proof_enabled => Self::RecordedReplay,
            _ => Self::Auto,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Disabled => "disabled",
            Self::RecordedReplay => "recorded-replay",
            Self::LiveMeta => "live-meta-openxr-hand-tracking",
        }
    }

    pub(crate) fn selects_live_frame(self) -> bool {
        matches!(self, Self::Auto | Self::LiveMeta)
    }

    pub(crate) fn allows_recorded_fallback(self) -> bool {
        matches!(self, Self::Auto | Self::RecordedReplay)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct HandMeshVisualDiagnosticSettings {
    pub(crate) enabled: bool,
    pub(crate) offset_uv: [f32; 2],
    pub(crate) alpha: f32,
}

impl HandMeshVisualDiagnosticSettings {
    pub(crate) fn new(enabled: bool, offset_uv: [f32; 2], alpha: f32) -> Self {
        Self {
            enabled,
            offset_uv: [
                offset_uv[0].clamp(-0.45, 0.45),
                offset_uv[1].clamp(-0.45, 0.45),
            ],
            alpha: alpha.clamp(0.20, 1.0),
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "handMeshVisualDiagnosticEnabled={} handMeshVisualDiagnosticOffsetUv={:.3},{:.3} handMeshVisualDiagnosticAlpha={:.2} handMeshVisualDiagnosticScale=1.35",
            self.enabled, self.offset_uv[0], self.offset_uv[1], self.alpha
        )
    }

    pub(crate) fn push_params(&self) -> [f32; 4] {
        if self.enabled {
            [self.offset_uv[0], self.offset_uv[1], self.alpha, 1.0]
        } else {
            [0.0, 0.0, self.alpha, 0.0]
        }
    }
}

impl Default for HandMeshVisualDiagnosticSettings {
    fn default() -> Self {
        Self::new(false, [0.0, 0.0], 0.78)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeHandAnchorParticleSettings {
    pub(crate) enabled: bool,
    pub(crate) particles_per_hand: u32,
    pub(crate) radius_m: f32,
    pub(crate) dynamics: NativeHandAnchorParticleDynamics,
    pub(crate) transparency_blend_mode: NativeHandAnchorParticleTransparencyBlendMode,
    pub(crate) transparency_composition_mode: NativeHandAnchorParticleTransparencyCompositionMode,
    pub(crate) transparency_depth_suppression_strength: f32,
    pub(crate) ordering_mode: NativeHandAnchorParticleOrderingMode,
    pub(crate) ordering_implementation: NativeHandAnchorParticleOrderingImplementation,
    pub(crate) ordering_interval_frames: u64,
}

impl NativeHandAnchorParticleSettings {
    fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let ordering_mode = NativeHandAnchorParticleOrderingMode::from_property(lookup(
            PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE,
        ));
        let ordering_implementation = NativeHandAnchorParticleOrderingImplementation::from_property(
            lookup(PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION),
        );
        Self {
            enabled: bool_value(lookup(PROP_HAND_ANCHOR_PARTICLES_ENABLED), false),
            particles_per_hand: u32_value(
                lookup(PROP_HAND_ANCHOR_PARTICLES_PER_HAND),
                256,
                1,
                4096,
            ),
            radius_m: f32_clamped_value(
                lookup(PROP_HAND_ANCHOR_PARTICLES_RADIUS_M),
                0.0045,
                0.001,
                0.040,
            ),
            dynamics: NativeHandAnchorParticleDynamics::from_property(lookup(
                PROP_HAND_ANCHOR_PARTICLES_DYNAMICS,
            )),
            transparency_blend_mode: NativeHandAnchorParticleTransparencyBlendMode::from_property(
                lookup(PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE),
            ),
            transparency_composition_mode:
                NativeHandAnchorParticleTransparencyCompositionMode::from_property(lookup(
                    PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
                )),
            transparency_depth_suppression_strength: f32_clamped_value(
                lookup(PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH),
                1.5,
                0.0,
                8.0,
            ),
            ordering_mode,
            ordering_implementation,
            ordering_interval_frames: u64_value(
                lookup(PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES),
                1,
                1,
                8,
            ),
        }
    }

    pub(crate) fn private_gpu_payload_requested(self) -> bool {
        self.dynamics == NativeHandAnchorParticleDynamics::PrivateGpuPayload
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "handAnchorParticlesEnabled={} handAnchorParticlesPerHand={} handAnchorParticleRadiusMeters={:.5} handAnchorParticleDynamics={} handAnchorParticlePrivateGpuPayloadRequested={} handAnchorParticleTransparencyBlendMode={} handAnchorParticleTransparencyCompositionMode={} handAnchorParticleTransparencyDepthSuppressionStrength={:.3} handAnchorParticleOrderingMode={} handAnchorParticleOrderingImplementation={} handAnchorParticleOrderingIntervalFrames={} handAnchorParticleOrderingStatus={} handAnchorParticleOrderingCpuExpandedUploadPerFrame=false handAnchorParticlePath=resident-skinned-mesh-coordinate-anchor-billboards handAnchorParticleCoordinateSpace=openxr-reference-space handAnchorParticleMask=static-feather-dot-luminance-alpha handAnchorParticleAnimation=false handAnchorParticleCpuExpandedUploadPerFrame=false handAnchorParticleMeshUploadPerFrame=false",
            self.enabled,
            self.particles_per_hand,
            self.radius_m,
            self.dynamics.marker_value(),
            self.private_gpu_payload_requested(),
            self.transparency_blend_mode.marker_value(),
            self.transparency_composition_mode.marker_value(),
            self.transparency_depth_suppression_strength,
            self.ordering_mode.marker_value(),
            self.ordering_implementation.marker_value(),
            self.ordering_interval_frames,
            self.ordering_status()
        )
    }

    pub(crate) fn ordering_status(self) -> &'static str {
        if self.ordering_mode.requires_particle_sort() {
            return match self.ordering_implementation {
                NativeHandAnchorParticleOrderingImplementation::GpuIndexRemap => {
                    "resident-gpu-index-remap-requested"
                }
                NativeHandAnchorParticleOrderingImplementation::CpuSortedRenderBuffers => {
                    "cpu-sorted-render-buffers-disabled-no-expanded-particle-upload"
                }
                NativeHandAnchorParticleOrderingImplementation::IdentityDrawOrder => {
                    "identity-instance-order"
                }
            };
        }
        match self.ordering_mode {
            NativeHandAnchorParticleOrderingMode::PrimaryThenSecondary => {
                "identity-hand-draw-order"
            }
            NativeHandAnchorParticleOrderingMode::SecondaryThenPrimary => "fixed-hand-draw-order",
            NativeHandAnchorParticleOrderingMode::NearHandFirst
            | NativeHandAnchorParticleOrderingMode::FarHandFirst => "eye-depth-hand-draw-order",
            NativeHandAnchorParticleOrderingMode::PerParticleBackToFront => {
                "identity-instance-order"
            }
        }
    }

    pub(crate) fn resident_gpu_particle_sort_requested(self) -> bool {
        self.ordering_mode.requires_particle_sort()
            && self.ordering_implementation
                == NativeHandAnchorParticleOrderingImplementation::GpuIndexRemap
    }
}

impl Default for NativeHandAnchorParticleSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            particles_per_hand: 256,
            radius_m: 0.0045,
            dynamics: NativeHandAnchorParticleDynamics::DeterministicAnchors,
            transparency_blend_mode: NativeHandAnchorParticleTransparencyBlendMode::Premultiplied,
            transparency_composition_mode:
                NativeHandAnchorParticleTransparencyCompositionMode::TrueAdditive,
            transparency_depth_suppression_strength: 1.5,
            ordering_mode: NativeHandAnchorParticleOrderingMode::PrimaryThenSecondary,
            ordering_implementation:
                NativeHandAnchorParticleOrderingImplementation::IdentityDrawOrder,
            ordering_interval_frames: 1,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeHandAnchorParticleDynamics {
    DeterministicAnchors,
    PrivateGpuPayload,
}

impl NativeHandAnchorParticleDynamics {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "private" | "private-gpu" | "private-gpu-payload" | "kuramoto" | "kuramoto-gpu" => {
                Self::PrivateGpuPayload
            }
            _ => Self::DeterministicAnchors,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::DeterministicAnchors => "deterministic-anchors",
            Self::PrivateGpuPayload => "private-gpu-payload",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeHandAnchorParticleTransparencyBlendMode {
    LegacyAdditiveMultiply,
    TrueAdditive,
    Fade,
    Premultiplied,
}

impl NativeHandAnchorParticleTransparencyBlendMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "legacy-additive" | "legacy-additive-multiply" | "additive-multiply" => {
                Self::LegacyAdditiveMultiply
            }
            "true-additive" | "additive" | "one-one" => Self::TrueAdditive,
            "fade" | "alpha" | "alpha-blend" | "straight-alpha" => Self::Fade,
            "premultiplied" | "premultiplied-alpha" | "pre-multiplied" => Self::Premultiplied,
            _ => Self::Premultiplied,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::LegacyAdditiveMultiply => "legacy-additive-multiply",
            Self::TrueAdditive => "true-additive",
            Self::Fade => "fade",
            Self::Premultiplied => "premultiplied",
        }
    }

    pub(crate) fn premultiply_rgb(self) -> bool {
        matches!(self, Self::TrueAdditive | Self::Premultiplied)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeHandAnchorParticleTransparencyCompositionMode {
    TrueAdditive,
    ApproximateDepthSuppressed,
}

impl NativeHandAnchorParticleTransparencyCompositionMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "approximate-depth-suppressed"
            | "depth-suppressed"
            | "depth-suppression"
            | "approx-depth" => Self::ApproximateDepthSuppressed,
            _ => Self::TrueAdditive,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::TrueAdditive => "true-additive",
            Self::ApproximateDepthSuppressed => "approximate-depth-suppressed",
        }
    }

    pub(crate) fn shader_code(self) -> f32 {
        match self {
            Self::TrueAdditive => 0.0,
            Self::ApproximateDepthSuppressed => 1.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeHandAnchorParticleOrderingMode {
    PrimaryThenSecondary,
    SecondaryThenPrimary,
    NearHandFirst,
    FarHandFirst,
    PerParticleBackToFront,
}

impl NativeHandAnchorParticleOrderingMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "secondary-then-primary" | "right-then-left" => Self::SecondaryThenPrimary,
            "near-hand-first" | "near-first" | "front-to-back" => Self::NearHandFirst,
            "far-hand-first" | "far-first" | "back-to-front" | "per-hand-back-to-front" => {
                Self::FarHandFirst
            }
            "per-particle-back-to-front"
            | "main-back-to-front"
            | "main-and-cpu-tracers-back-to-front" => Self::PerParticleBackToFront,
            _ => Self::PrimaryThenSecondary,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::PrimaryThenSecondary => "primary-then-secondary",
            Self::SecondaryThenPrimary => "secondary-then-primary",
            Self::NearHandFirst => "near-hand-first",
            Self::FarHandFirst => "far-hand-first",
            Self::PerParticleBackToFront => "per-particle-back-to-front",
        }
    }

    pub(crate) fn requires_particle_sort(self) -> bool {
        matches!(self, Self::PerParticleBackToFront)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeHandAnchorParticleOrderingImplementation {
    IdentityDrawOrder,
    GpuIndexRemap,
    CpuSortedRenderBuffers,
}

impl NativeHandAnchorParticleOrderingImplementation {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "gpu-index-remap" | "gpu-remap" | "index-remap" => Self::GpuIndexRemap,
            "cpu-sorted-render-buffers" | "cpu-sorted" | "sorted-render-buffers" => {
                Self::CpuSortedRenderBuffers
            }
            _ => Self::IdentityDrawOrder,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::IdentityDrawOrder => "identity-draw-order",
            Self::GpuIndexRemap => "gpu-index-remap",
            Self::CpuSortedRenderBuffers => "cpu-sorted-render-buffers",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeProjectionProcessingLayer {
    Blur,
    PeripheralStretch,
}

impl NativeProjectionProcessingLayer {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "stretch"
            | "peripheral-stretch"
            | "border-stretch"
            | "projection-border-stretch"
            | "edge-stretch" => Self::PeripheralStretch,
            _ => Self::Blur,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Blur => "blur",
            Self::PeripheralStretch => "peripheral-stretch",
        }
    }

    pub(crate) fn consumes_projection_exterior(self) -> bool {
        matches!(self, Self::PeripheralStretch)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeProjectionBorderPolicy {
    SolidRed,
    PassthroughUnderlay,
}

impl NativeProjectionBorderPolicy {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "passthrough" | "passthrough-underlay" | "underlay" => Self::PassthroughUnderlay,
            _ => Self::SolidRed,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::SolidRed => "solid-red",
            Self::PassthroughUnderlay => "passthrough-underlay",
        }
    }

    fn shader_code(self) -> f32 {
        match self {
            Self::SolidRed => 0.0,
            Self::PassthroughUnderlay => 1.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativePeripheralStretchBlendMode {
    Off,
    TargetInnerBand,
}

impl NativePeripheralStretchBlendMode {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "0" | "false" | "no" | "off" | "disabled" => Self::Off,
            _ => Self::TargetInnerBand,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::TargetInnerBand => "target-inner-band",
        }
    }

    fn shader_code(self) -> f32 {
        match self {
            Self::Off => 0.0,
            Self::TargetInnerBand => 1.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativePeripheralStretchDebug {
    Off,
    Regions,
    SampleUv,
}

impl NativePeripheralStretchDebug {
    fn from_property(value: Option<String>) -> Self {
        let normalized = normalized_property(value);
        match normalized.as_str() {
            "1" | "true" | "yes" | "on" | "enabled" | "regions" | "region" => Self::Regions,
            "2" | "sample-uv" | "sampleuv" | "uv" => Self::SampleUv,
            _ => Self::Off,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Regions => "regions",
            Self::SampleUv => "sample-uv",
        }
    }

    fn shader_code(self) -> f32 {
        match self {
            Self::Off => 0.0,
            Self::Regions => 1.0,
            Self::SampleUv => 2.0,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeProjectionBorderStretchSettings {
    pub(crate) processing_layer: NativeProjectionProcessingLayer,
    pub(crate) border_policy: NativeProjectionBorderPolicy,
    pub(crate) projection_area_opacity: f32,
    pub(crate) projection_border_opacity: f32,
    pub(crate) core_scale: f32,
    pub(crate) edge_inset_uv: f32,
    pub(crate) max_inset_uv: f32,
    pub(crate) curve: f32,
    pub(crate) inner_blend_uv: f32,
    pub(crate) blend_curve: f32,
    pub(crate) blend_mode: NativePeripheralStretchBlendMode,
    pub(crate) debug: NativePeripheralStretchDebug,
}

impl NativeProjectionBorderStretchSettings {
    fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let edge_inset_uv = f32_clamped_value(
            lookup(PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV),
            0.015,
            0.0,
            0.49,
        );
        Self {
            processing_layer: NativeProjectionProcessingLayer::from_property(lookup(
                PROP_PROCESSING_LAYER,
            )),
            border_policy: NativeProjectionBorderPolicy::from_property(lookup(
                PROP_PROJECTION_BORDER_POLICY,
            )),
            projection_area_opacity: f32_clamped_value(
                lookup(PROP_PROJECTION_AREA_OPACITY),
                1.0,
                0.0,
                1.0,
            ),
            projection_border_opacity: f32_clamped_value(
                lookup(PROP_PROJECTION_BORDER_OPACITY),
                1.0,
                0.0,
                1.0,
            ),
            core_scale: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_CORE_SCALE),
                1.0,
                0.05,
                1.0,
            ),
            edge_inset_uv,
            max_inset_uv: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_MAX_INSET_UV),
                0.14,
                edge_inset_uv,
                0.49,
            ),
            curve: f32_clamped_value(lookup(PROP_PERIPHERAL_STRETCH_CURVE), 1.6, 0.25, 6.0),
            inner_blend_uv: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_INNER_BLEND_UV),
                0.040,
                0.0,
                0.25,
            ),
            blend_curve: f32_clamped_value(
                lookup(PROP_PERIPHERAL_STRETCH_BLEND_CURVE),
                1.6,
                0.25,
                6.0,
            ),
            blend_mode: NativePeripheralStretchBlendMode::from_property(lookup(
                PROP_PERIPHERAL_STRETCH_BLEND_MODE,
            )),
            debug: NativePeripheralStretchDebug::from_property(lookup(
                PROP_PERIPHERAL_STRETCH_DEBUG,
            )),
        }
    }

    pub(crate) fn peripheral_stretch_active(self) -> bool {
        self.processing_layer.consumes_projection_exterior()
    }

    pub(crate) fn transition_active(self) -> bool {
        self.blend_mode == NativePeripheralStretchBlendMode::TargetInnerBand
            && self.inner_blend_uv > 0.0001
    }

    pub(crate) fn marker_fields(self) -> String {
        let transition_active = self.transition_active();
        let (core_region, transition_region, transition_space, transition_semantics) =
            if transition_active {
                (
                    "target-footprint-minus-inner-transition-band",
                    "target-footprint-inner-edge-band",
                    "target-local-raster-uv",
                    "canonical-sample-to-stretch-sample-remap",
                )
            } else {
                (
                    "target-footprint",
                    "off",
                    "off",
                    "hard-edge-preblend-reference",
                )
            };
        let projection_exterior_mode = if self.peripheral_stretch_active() && transition_active {
            "target-edge-stretch-with-inner-band-blend"
        } else if self.peripheral_stretch_active() {
            "target-edge-stretch-hard-edge"
        } else {
            "projection-border-policy-fallback"
        };
        format!(
            "processingLayer={} projectionBorderPolicy={} projectionAreaOpacity={:.3} projectionBorderOpacity={:.3} peripheralStretchMode=edge-stretch peripheralStretchCoreScale={:.3} peripheralStretchEdgeInsetUv={:.3} peripheralStretchMaxInsetUv={:.3} peripheralStretchCurve={:.3} peripheralStretchInnerBlendUv={:.3} peripheralStretchBlendCurve={:.3} peripheralStretchBlendMode={} peripheralStretchCornerMode=target-footprint peripheralStretchDebug={} peripheralStretchActive={} peripheralStretchTransitionActive={} peripheralStretchConsumesProjectionExterior={} peripheralStretchCoreRegion={} peripheralStretchTransitionRegion={} peripheralStretchExteriorRegion=visible-render-surface-minus-target-footprint peripheralStretchTransitionSpace={} peripheralStretchTransitionSemantics={} peripheralStretchProjectionExteriorMode={} peripheralStretchMapping=mirrored-curved-target-footprint peripheralStretchDistanceCurve=mirrored-border-smoothstep-swirl peripheralStretchBorderSource=mirrored-projection-edge-trail peripheralStretchExteriorSource=curved-target-edge-sample peripheralStretchBlendSemantics=curved-sample-blends-through-inner-band peripheralStretchTargetLocalRasterRegionModel=projection-area-plus-single-border-region peripheralStretchSourceInvalidConsumesSolidRed=false peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band",
            self.processing_layer.marker_value(),
            self.border_policy.marker_value(),
            self.projection_area_opacity,
            self.projection_border_opacity,
            self.core_scale,
            self.edge_inset_uv,
            self.max_inset_uv,
            self.curve,
            self.inner_blend_uv,
            self.blend_curve,
            self.blend_mode.marker_value(),
            self.debug.marker_value(),
            self.peripheral_stretch_active(),
            transition_active,
            self.processing_layer.consumes_projection_exterior(),
            core_region,
            transition_region,
            transition_space,
            transition_semantics,
            projection_exterior_mode,
        )
    }

    pub(crate) fn push_params(self) -> NativeProjectionBorderStretchPush {
        NativeProjectionBorderStretchPush {
            params: [
                if self.peripheral_stretch_active() {
                    1.0
                } else {
                    0.0
                },
                self.border_policy.shader_code(),
                self.projection_area_opacity,
                self.projection_border_opacity,
            ],
            stretch0: [
                self.core_scale,
                self.edge_inset_uv,
                self.max_inset_uv,
                self.curve,
            ],
            stretch1: [
                self.inner_blend_uv,
                self.blend_curve,
                self.blend_mode.shader_code(),
                self.debug.shader_code(),
            ],
        }
    }
}

impl Default for NativeProjectionBorderStretchSettings {
    fn default() -> Self {
        Self {
            processing_layer: NativeProjectionProcessingLayer::Blur,
            border_policy: NativeProjectionBorderPolicy::SolidRed,
            projection_area_opacity: 1.0,
            projection_border_opacity: 1.0,
            core_scale: 1.0,
            edge_inset_uv: 0.015,
            max_inset_uv: 0.14,
            curve: 1.6,
            inner_blend_uv: 0.040,
            blend_curve: 1.6,
            blend_mode: NativePeripheralStretchBlendMode::TargetInnerBand,
            debug: NativePeripheralStretchDebug::Off,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativePrivateLayerSettings {
    pub(crate) enabled: bool,
    pub(crate) layer_seconds: f32,
    pub(crate) layer_override: f32,
    pub(crate) effect: [f32; 4],
}

impl NativePrivateLayerSettings {
    fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        Self {
            enabled: bool_value(lookup(PROP_PRIVATE_LAYER_ENABLED), false),
            layer_seconds: f32_clamped_value(lookup(PROP_PRIVATE_LAYER_SECONDS), 5.0, 0.25, 60.0),
            layer_override: f32_clamped_value(lookup(PROP_PRIVATE_LAYER_OVERRIDE), -1.0, -1.0, 5.0),
            effect: [
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT0), 1.0, 0.0, 4.0),
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT1), 1.0, 0.0, 4.0),
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT2), 0.0, 0.0, 0.25),
                f32_clamped_value(lookup(PROP_PRIVATE_LAYER_EFFECT3), 1.0, 0.0, 4.0),
            ],
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "privateLayerEnabled={} privateLayerSeconds={:.3} privateLayerOverride={:.1} privateLayerEffect0={:.3} privateLayerEffect1={:.3} privateLayerEffect2={:.5} privateLayerEffect3={:.3}",
            self.enabled,
            self.layer_seconds,
            self.layer_override,
            self.effect[0],
            self.effect[1],
            self.effect[2],
            self.effect[3]
        )
    }
}

impl Default for NativePrivateLayerSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            layer_seconds: 5.0,
            layer_override: -1.0,
            effect: [1.0, 1.0, 0.0, 1.0],
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct NativeProjectionBorderStretchPush {
    pub(crate) params: [f32; 4],
    pub(crate) stretch0: [f32; 4],
    pub(crate) stretch1: [f32; 4],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeRendererRuntimeOptions {
    pub(crate) render_mode: NativeRendererRenderMode,
    pub(crate) camera_output_mode: NativeCameraOutputMode,
    pub(crate) camera_ycbcr_mode: NativeCameraYcbcrMode,
    pub(crate) camera_resolution_profile: NativeCameraResolutionProfile,
    pub(crate) camera_reader_max_images: u32,
    pub(crate) camera_quality_profile: NativeCameraQualityProfile,
    pub(crate) camera_sync_mode: NativeCameraSyncMode,
    pub(crate) camera_direct_border_opacity: f32,
    pub(crate) swapchain_color_format_mode: NativeSwapchainColorFormatMode,
    pub(crate) replay_visual_proof_enabled: bool,
    pub(crate) compact_hand_input_source_mode: CompactHandInputSourceMode,
    pub(crate) sdf_visual_enabled: bool,
    pub(crate) sdf_update_period_frames: u64,
    pub(crate) hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings,
    pub(crate) hand_mesh_graft_copies_enabled: bool,
    pub(crate) hand_mesh_graft_copy_scale: f32,
    pub(crate) hand_mesh_real_hands_visible: bool,
    pub(crate) hand_anchor_particle_settings: NativeHandAnchorParticleSettings,
    pub(crate) projection_border_stretch_settings: NativeProjectionBorderStretchSettings,
    pub(crate) private_layer_settings: NativePrivateLayerSettings,
}

impl NativeRendererRuntimeOptions {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let render_mode = NativeRendererRenderMode::from_property(lookup(PROP_RENDER_MODE));
        let camera_output_mode =
            NativeCameraOutputMode::from_property(lookup(PROP_CAMERA_OUTPUT_MODE));
        let camera_ycbcr_mode =
            NativeCameraYcbcrMode::from_property(lookup(PROP_CAMERA_YCBCR_MODE));
        let camera_resolution_profile =
            NativeCameraResolutionProfile::from_property(lookup(PROP_CAMERA_RESOLUTION_PROFILE));
        let camera_reader_max_images = u32_value(lookup(PROP_CAMERA_READER_MAX_IMAGES), 4, 3, 12);
        let camera_quality_profile =
            NativeCameraQualityProfile::from_property(lookup(PROP_CAMERA_QUALITY_PROFILE));
        let camera_sync_mode = NativeCameraSyncMode::from_property(lookup(PROP_CAMERA_SYNC_MODE));
        let camera_direct_border_opacity =
            f32_clamped_value(lookup(PROP_CAMERA_DIRECT_BORDER_OPACITY), 0.72, 0.0, 1.0);
        let swapchain_color_format_mode =
            NativeSwapchainColorFormatMode::from_property(lookup(PROP_SWAPCHAIN_COLOR_FORMAT_MODE));
        let replay_visual_proof_enabled =
            bool_value(lookup(PROP_REPLAY_VISUAL_PROOF_ENABLED), false);
        let compact_hand_input_source_mode = CompactHandInputSourceMode::from_property(
            lookup(PROP_HAND_MESH_INPUT_SOURCE),
            replay_visual_proof_enabled,
        );
        let requested_sdf_visual =
            replay_visual_proof_enabled || bool_value(lookup(PROP_ENABLE_SDF_VISUAL), false);
        let sdf_visual_enabled = requested_sdf_visual && render_mode.allows_sdf_visual();
        let sdf_update_period_frames = u64_value(lookup(PROP_SDF_UPDATE_PERIOD_FRAMES), 2, 1, 120);
        let diagnostic_enabled = replay_visual_proof_enabled
            || bool_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED), false);
        let diagnostic_offset_uv = f32_pair_value(
            lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV),
            [0.12, -0.08],
        );
        let diagnostic_alpha = f32_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA), 0.86);
        let hand_mesh_graft_copies_enabled = render_mode.forces_graft_copies()
            || bool_value(lookup(PROP_HAND_MESH_GRAFT_COPIES_ENABLED), false);
        let hand_mesh_graft_copy_scale =
            f32_value(lookup(PROP_HAND_MESH_GRAFT_COPY_SCALE), 1.0).clamp(0.10, 2.0);
        let hand_mesh_real_hands_visible = render_mode.forces_real_hand_meshes()
            || bool_value(lookup(PROP_HAND_MESH_REAL_HANDS_VISIBLE), false);
        let hand_anchor_particle_settings =
            NativeHandAnchorParticleSettings::from_property_lookup(&mut lookup);
        let projection_border_stretch_settings =
            NativeProjectionBorderStretchSettings::from_property_lookup(&mut lookup);
        let private_layer_settings = NativePrivateLayerSettings::from_property_lookup(&mut lookup);

        Self {
            render_mode,
            camera_output_mode,
            camera_ycbcr_mode,
            camera_resolution_profile,
            camera_reader_max_images,
            camera_quality_profile,
            camera_sync_mode,
            camera_direct_border_opacity,
            swapchain_color_format_mode,
            replay_visual_proof_enabled,
            compact_hand_input_source_mode,
            sdf_visual_enabled,
            sdf_update_period_frames,
            hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings::new(
                diagnostic_enabled,
                diagnostic_offset_uv,
                diagnostic_alpha,
            ),
            hand_mesh_graft_copies_enabled,
            hand_mesh_graft_copy_scale,
            hand_mesh_real_hands_visible,
            hand_anchor_particle_settings,
            projection_border_stretch_settings,
            private_layer_settings,
        }
    }

    #[cfg(target_os = "android")]
    pub(crate) fn load_from_android_properties() -> Self {
        Self::from_property_lookup(android_property)
    }
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

fn bool_value(value: Option<String>, default_value: bool) -> bool {
    value.map_or(default_value, |value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on"
        )
    })
}

fn u64_value(value: Option<String>, default_value: u64, min_value: u64, max_value: u64) -> u64 {
    value
        .and_then(|value| value.trim().parse::<u64>().ok())
        .filter(|value| *value >= min_value)
        .unwrap_or(default_value)
        .min(max_value)
}

fn u32_value(value: Option<String>, default_value: u32, min_value: u32, max_value: u32) -> u32 {
    value
        .and_then(|value| value.trim().parse::<u32>().ok())
        .filter(|value| *value >= min_value)
        .unwrap_or(default_value)
        .min(max_value)
}

fn f32_value(value: Option<String>, default_value: f32) -> f32 {
    value
        .and_then(|value| value.trim().parse::<f32>().ok())
        .unwrap_or(default_value)
}

fn f32_clamped_value(
    value: Option<String>,
    default_value: f32,
    min_value: f32,
    max_value: f32,
) -> f32 {
    f32_value(value, default_value).clamp(min_value, max_value)
}

fn f32_pair_value(value: Option<String>, default_value: [f32; 2]) -> [f32; 2] {
    let Some(value) = value else {
        return default_value;
    };
    let parts = value
        .split(|character: char| character == ',' || character == ';' || character.is_whitespace())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    if parts.len() != 2 {
        return default_value;
    }
    let Some(x) = parts[0].trim().parse::<f32>().ok() else {
        return default_value;
    };
    let Some(y) = parts[1].trim().parse::<f32>().ok() else {
        return default_value;
    };
    [x, y]
}

fn normalized_property(value: Option<String>) -> String {
    value
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .replace('_', "-")
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use super::{
        CompactHandInputSourceMode, NativeCameraOutputMode, NativeCameraQualityProfile,
        NativeCameraResolutionProfile, NativeCameraSyncMode, NativeCameraYcbcrMode,
        NativeRendererRuntimeOptions, NativeSwapchainColorFormatMode,
        PROP_CAMERA_DIRECT_BORDER_OPACITY, PROP_CAMERA_OUTPUT_MODE, PROP_CAMERA_QUALITY_PROFILE,
        PROP_CAMERA_READER_MAX_IMAGES, PROP_CAMERA_RESOLUTION_PROFILE, PROP_CAMERA_SYNC_MODE,
        PROP_CAMERA_YCBCR_MODE, PROP_ENABLE_SDF_VISUAL, PROP_HAND_ANCHOR_PARTICLES_DYNAMICS,
        PROP_HAND_ANCHOR_PARTICLES_ENABLED, PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE, PROP_HAND_ANCHOR_PARTICLES_PER_HAND,
        PROP_HAND_ANCHOR_PARTICLES_RADIUS_M, PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
        PROP_HAND_MESH_GRAFT_COPIES_ENABLED, PROP_HAND_MESH_GRAFT_COPY_SCALE,
        PROP_HAND_MESH_INPUT_SOURCE, PROP_HAND_MESH_REAL_HANDS_VISIBLE,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, PROP_PERIPHERAL_STRETCH_BLEND_MODE,
        PROP_PERIPHERAL_STRETCH_CORE_SCALE, PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV,
        PROP_PERIPHERAL_STRETCH_MAX_INSET_UV, PROP_PROCESSING_LAYER,
        PROP_PROJECTION_BORDER_OPACITY, PROP_PROJECTION_BORDER_POLICY, PROP_RENDER_MODE,
        PROP_REPLAY_VISUAL_PROOF_ENABLED, PROP_SDF_UPDATE_PERIOD_FRAMES,
        PROP_SWAPCHAIN_COLOR_FORMAT_MODE,
    };

    fn options_from(values: &[(&str, &str)]) -> NativeRendererRuntimeOptions {
        let values = values.iter().copied().collect::<BTreeMap<_, _>>();
        NativeRendererRuntimeOptions::from_property_lookup(|name| {
            values.get(name).map(|value| (*value).to_owned())
        })
    }

    #[test]
    fn replay_visual_proof_forces_recorded_diagnostic_and_sdf() {
        let options = options_from(&[(PROP_REPLAY_VISUAL_PROOF_ENABLED, "true")]);
        assert!(options.replay_visual_proof_enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::RecordedReplay
        );
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert_eq!(
            options.render_mode.marker_value(),
            "custom-stereo-projection"
        );
        assert_eq!(options.hand_mesh_graft_copy_scale, 1.0);
        assert!(!options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn explicit_live_source_overrides_replay_proof_source_selection() {
        let options = options_from(&[
            (PROP_REPLAY_VISUAL_PROOF_ENABLED, "true"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
        ]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn canonical_live_source_value_selects_live_without_replay_fallback() {
        let options = options_from(&[(
            PROP_HAND_MESH_INPUT_SOURCE,
            "live-meta-openxr-hand-tracking",
        )]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn auto_mode_defaults_to_recorded_fallback_without_diagnostics() {
        let options = options_from(&[(PROP_HAND_MESH_INPUT_SOURCE, "auto")]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Auto
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn disabled_hand_input_source_selects_no_hand_frames() {
        let options = options_from(&[(PROP_HAND_MESH_INPUT_SOURCE, "disabled")]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Disabled
        );
        assert!(!options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert_eq!(
            options.compact_hand_input_source_mode.marker_value(),
            "disabled"
        );
    }

    #[test]
    fn camera_output_mode_defaults_to_auto_and_parses_diagnostics() {
        let options = options_from(&[]);
        assert_eq!(options.camera_output_mode, NativeCameraOutputMode::Auto);
        assert_eq!(options.camera_output_mode.marker_value(), "auto");
        assert!(options.camera_output_mode.camera_import_enabled());
        assert!(options
            .camera_output_mode
            .private_layer_projection_enabled());
        assert!(options.camera_output_mode.guide_projection_enabled());
        assert_eq!(
            options.camera_ycbcr_mode,
            NativeCameraYcbcrMode::AndroidSuggested
        );
        assert_eq!(
            options.camera_resolution_profile,
            NativeCameraResolutionProfile::Square1280
        );
        assert_eq!(
            options.camera_resolution_profile.marker_value(),
            "1280x1280"
        );
        assert_eq!(options.camera_reader_max_images, 4);
        assert_eq!(
            options.camera_quality_profile,
            NativeCameraQualityProfile::DirectBaseline
        );
        assert_eq!(
            options.camera_sync_mode,
            NativeCameraSyncMode::EarlyDeleteAhbRetained
        );
        assert_eq!(
            options.swapchain_color_format_mode,
            NativeSwapchainColorFormatMode::Auto
        );
        assert_eq!(options.camera_direct_border_opacity, 0.72);

        let direct = options_from(&[
            (PROP_CAMERA_OUTPUT_MODE, "raw_hwb"),
            (PROP_CAMERA_YCBCR_MODE, "cpuyuv-reference"),
            (PROP_CAMERA_RESOLUTION_PROFILE, "1280x960"),
            (PROP_CAMERA_READER_MAX_IMAGES, "8"),
            (PROP_CAMERA_QUALITY_PROFILE, "low-noise-30"),
            (PROP_CAMERA_SYNC_MODE, "delete-async"),
            (PROP_SWAPCHAIN_COLOR_FORMAT_MODE, "unorm"),
            (PROP_CAMERA_DIRECT_BORDER_OPACITY, "0"),
        ]);
        assert_eq!(direct.camera_output_mode, NativeCameraOutputMode::DirectHwb);
        assert!(direct.camera_output_mode.camera_import_enabled());
        assert!(direct.camera_output_mode.direct_hwb_forced());
        assert!(!direct.camera_output_mode.private_layer_projection_enabled());
        assert!(!direct.camera_output_mode.guide_graph_processing_enabled());
        assert_eq!(
            direct.camera_ycbcr_mode,
            NativeCameraYcbcrMode::ForcedBt601Narrow
        );
        assert_eq!(
            direct.camera_ycbcr_mode.conversion_mode(),
            "forced-bt601-limited-cpuyuv-reference"
        );
        assert_eq!(
            direct.camera_resolution_profile,
            NativeCameraResolutionProfile::Wide1280x960
        );
        assert_eq!(
            direct.camera_resolution_profile.requested_size(),
            Some([1280, 960])
        );
        assert_eq!(direct.camera_reader_max_images, 8);
        assert_eq!(
            direct.camera_quality_profile,
            NativeCameraQualityProfile::DirectLowNoise30
        );
        assert_eq!(
            direct.camera_quality_profile.marker_value(),
            "direct-low-noise-30"
        );
        assert_eq!(
            direct.camera_sync_mode,
            NativeCameraSyncMode::DeleteAsyncReleaseFence
        );
        assert_eq!(
            direct.camera_sync_mode.marker_value(),
            "delete-async-release-fence"
        );
        assert_eq!(
            direct.camera_sync_mode.active_marker_value(),
            "early-delete-ahb-retained"
        );
        assert_eq!(
            direct.camera_sync_mode.implementation_status(),
            "declared-diagnostic-not-active-yet"
        );
        assert_eq!(
            direct.swapchain_color_format_mode,
            NativeSwapchainColorFormatMode::Unorm
        );
        assert_eq!(direct.camera_direct_border_opacity, 0.0);

        let hold_sync = options_from(&[
            (PROP_CAMERA_RESOLUTION_PROFILE, "closest"),
            (PROP_CAMERA_READER_MAX_IMAGES, "99"),
            (PROP_CAMERA_SYNC_MODE, "hold-image"),
        ]);
        assert_eq!(
            hold_sync.camera_resolution_profile,
            NativeCameraResolutionProfile::ClosestSupported
        );
        assert_eq!(
            hold_sync.camera_sync_mode,
            NativeCameraSyncMode::HoldImageUntilGpuFence
        );
        assert_eq!(
            hold_sync.camera_sync_mode.active_marker_value(),
            "hold-image-until-gpu-fence"
        );
        assert_eq!(
            hold_sync.camera_sync_mode.implementation_status(),
            "active-diagnostic"
        );
        assert_eq!(hold_sync.camera_reader_max_images, 12);

        let guide = options_from(&[(PROP_CAMERA_OUTPUT_MODE, "public-guide")]);
        assert_eq!(
            guide.camera_output_mode,
            NativeCameraOutputMode::GuidePublic
        );
        assert!(guide.camera_output_mode.guide_projection_enabled());
        assert!(!guide.camera_output_mode.private_layer_projection_enabled());

        let disabled = options_from(&[(PROP_CAMERA_OUTPUT_MODE, "off")]);
        assert_eq!(
            disabled.camera_output_mode,
            NativeCameraOutputMode::Disabled
        );
        assert!(!disabled.camera_output_mode.camera_import_enabled());
    }

    #[test]
    fn sdf_and_diagnostic_values_parse_and_clamp() {
        let options = options_from(&[
            (PROP_ENABLE_SDF_VISUAL, "on"),
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "999"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED, "yes"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "9.0,-9.0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "4.0"),
            (PROP_HAND_MESH_GRAFT_COPIES_ENABLED, "on"),
        ]);
        assert!(options.sdf_visual_enabled);
        assert_eq!(options.sdf_update_period_frames, 120);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.45, -0.45]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 1.0);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn native_passthrough_graft_only_forces_grafts_and_disables_sdf_visual() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-graft-only"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "native-passthrough-graft-only"
        );
        assert!(options.render_mode.uses_native_passthrough());
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.sdf_visual_enabled);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert_eq!(options.hand_mesh_graft_copy_scale, 0.85);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn native_passthrough_real_hand_mesh_visibility_is_explicit() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "native-passthrough-graft-only"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_HAND_MESH_REAL_HANDS_VISIBLE, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert!(options.render_mode.uses_native_passthrough());
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(options.hand_mesh_real_hands_visible);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
    }

    #[test]
    fn solid_black_hands_and_grafts_forces_hand_visuals_without_camera_or_sdf() {
        let options = options_from(&[
            (PROP_RENDER_MODE, "solid-black-hands-and-grafts"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "0.85"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "solid-black-hands-and-grafts"
        );
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.render_mode.uses_native_passthrough());
        assert!(options.render_mode.uses_solid_black_background());
        assert!(!options.sdf_visual_enabled);
        assert!(options.hand_mesh_graft_copies_enabled);
        assert!(options.hand_mesh_real_hands_visible);
        assert_eq!(options.hand_mesh_graft_copy_scale, 0.85);
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-solid-black-hands-and-grafts"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-solid-black-hands-and-grafts"
        );
    }

    #[test]
    fn solid_black_openxr_hands_anchor_particles_keeps_custom_mesh_visual_off() {
        let options = options_from(&[
            (
                PROP_RENDER_MODE,
                "solid-black-openxr-hands-anchor-particles",
            ),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
            (PROP_ENABLE_SDF_VISUAL, "true"),
            (PROP_HAND_MESH_GRAFT_COPIES_ENABLED, "false"),
            (PROP_HAND_MESH_REAL_HANDS_VISIBLE, "false"),
            (PROP_HAND_ANCHOR_PARTICLES_ENABLED, "true"),
        ]);
        assert_eq!(
            options.render_mode.marker_value(),
            "solid-black-openxr-hands-anchor-particles"
        );
        assert!(!options.render_mode.uses_custom_stereo_projection());
        assert!(!options.render_mode.uses_native_passthrough());
        assert!(options.render_mode.uses_solid_black_background());
        assert!(options.render_mode.requests_openxr_default_hand_visual());
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert!(!options.hand_mesh_real_hands_visible);
        assert!(options.hand_anchor_particle_settings.enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert_eq!(
            options.render_mode.camera_runtime_mode(),
            "skipped-solid-black-openxr-hands-anchor-particles"
        );
        assert_eq!(
            options.render_mode.disabled_camera_projection_path(),
            "disabled-solid-black-openxr-hands-anchor-particles"
        );
    }

    #[test]
    fn invalid_values_keep_defaults() {
        let options = options_from(&[
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "bad"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "bad"),
            (PROP_HAND_MESH_GRAFT_COPY_SCALE, "bad"),
        ]);
        assert_eq!(options.sdf_update_period_frames, 2);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.12, -0.08]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 0.86);
        assert!(!options.hand_mesh_graft_copies_enabled);
        assert_eq!(options.hand_mesh_graft_copy_scale, 1.0);
        assert!(!options.hand_mesh_real_hands_visible);
    }

    #[test]
    fn hand_anchor_particle_settings_parse_and_clamp() {
        let options = options_from(&[
            (PROP_HAND_ANCHOR_PARTICLES_ENABLED, "on"),
            (PROP_HAND_ANCHOR_PARTICLES_PER_HAND, "99999"),
            (PROP_HAND_ANCHOR_PARTICLES_RADIUS_M, "0.2"),
            (PROP_HAND_ANCHOR_PARTICLES_DYNAMICS, "private-gpu-payload"),
            (
                PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE,
                "legacy-additive-multiply",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
                "approximate-depth-suppressed",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
                "99",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE,
                "main-and-cpu-tracers-back-to-front",
            ),
            (
                PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION,
                "gpu-index-remap",
            ),
            (PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES, "99"),
        ]);

        assert!(options.hand_anchor_particle_settings.enabled);
        assert_eq!(
            options.hand_anchor_particle_settings.particles_per_hand,
            4096
        );
        assert_eq!(options.hand_anchor_particle_settings.radius_m, 0.040);
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .transparency_blend_mode
                .marker_value(),
            "legacy-additive-multiply"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .transparency_composition_mode
                .marker_value(),
            "approximate-depth-suppressed"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .transparency_depth_suppression_strength,
            8.0
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .ordering_mode
                .marker_value(),
            "per-particle-back-to-front"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .ordering_implementation
                .marker_value(),
            "gpu-index-remap"
        );
        assert_eq!(
            options
                .hand_anchor_particle_settings
                .ordering_interval_frames,
            8
        );
        assert!(options
            .hand_anchor_particle_settings
            .private_gpu_payload_requested());
        let fields = options.hand_anchor_particle_settings.marker_fields();
        assert!(fields.contains("handAnchorParticleDynamics=private-gpu-payload"));
        assert!(
            fields.contains("handAnchorParticleOrderingStatus=resident-gpu-index-remap-requested")
        );
        assert!(fields.contains("handAnchorParticleOrderingCpuExpandedUploadPerFrame=false"));
        assert!(fields.contains("handAnchorParticleCoordinateSpace=openxr-reference-space"));
        assert!(fields.contains("handAnchorParticleCpuExpandedUploadPerFrame=false"));
        assert!(options
            .hand_anchor_particle_settings
            .resident_gpu_particle_sort_requested());
    }

    #[test]
    fn peripheral_stretch_settings_match_hwb_reference_defaults() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "peripheral-stretch"),
            (PROP_PROJECTION_BORDER_POLICY, "passthrough-underlay"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(settings.peripheral_stretch_active());
        assert!(settings.transition_active());
        assert_eq!(settings.core_scale, 1.0);
        assert_eq!(settings.edge_inset_uv, 0.015);
        assert_eq!(settings.max_inset_uv, 0.14);
        assert_eq!(settings.inner_blend_uv, 0.040);

        let fields = settings.marker_fields();
        assert!(fields.contains("processingLayer=peripheral-stretch"));
        assert!(fields.contains("projectionBorderPolicy=passthrough-underlay"));
        assert!(fields.contains("peripheralStretchBlendMode=target-inner-band"));
        assert!(fields.contains("peripheralStretchTransitionActive=true"));
        assert!(fields.contains("peripheralStretchConsumesProjectionExterior=true"));
        assert!(fields.contains(
            "peripheralStretchProjectionExteriorMode=target-edge-stretch-with-inner-band-blend"
        ));
        assert!(fields
            .contains("peripheralStretchReference=pure-hwb-target-local-raster-curved-inner-band"));
    }

    #[test]
    fn peripheral_stretch_values_parse_and_clamp() {
        let options = options_from(&[
            (PROP_PROCESSING_LAYER, "edge_stretch"),
            (PROP_PERIPHERAL_STRETCH_CORE_SCALE, "0.001"),
            (PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV, "0.7"),
            (PROP_PERIPHERAL_STRETCH_MAX_INSET_UV, "0.1"),
            (PROP_PERIPHERAL_STRETCH_BLEND_MODE, "off"),
            (PROP_PROJECTION_BORDER_OPACITY, "-5"),
        ]);
        let settings = options.projection_border_stretch_settings;

        assert!(settings.peripheral_stretch_active());
        assert_eq!(settings.core_scale, 0.05);
        assert_eq!(settings.edge_inset_uv, 0.49);
        assert_eq!(settings.max_inset_uv, 0.49);
        assert_eq!(settings.projection_border_opacity, 0.0);
        assert!(!settings.transition_active());
    }
}
