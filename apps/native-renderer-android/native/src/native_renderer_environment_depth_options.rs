//! Environment-depth property settings for the native renderer.
//!
//! This module owns parsed runtime settings and marker helpers for the
//! environment-depth GPU route while `native_renderer_options` remains the
//! caller-facing aggregate facade.

use crate::{
    native_renderer_properties::{
        PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW, PROP_ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY,
        PROP_ENVIRONMENT_DEPTH_FAR_M, PROP_ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED,
        PROP_ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD, PROP_ENVIRONMENT_DEPTH_LAYER_POLICY,
        PROP_ENVIRONMENT_DEPTH_MODE, PROP_ENVIRONMENT_DEPTH_NATIVE_PASSTHROUGH_REQUIRED,
        PROP_ENVIRONMENT_DEPTH_NEAR_M, PROP_ENVIRONMENT_DEPTH_PARTICLE_CAPACITY,
        PROP_ENVIRONMENT_DEPTH_REFERENCE_SPACE, PROP_ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS,
        PROP_ENVIRONMENT_DEPTH_SOURCE, PROP_ENVIRONMENT_DEPTH_SURFACE_MODEL,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS,
        PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY,
    },
    native_renderer_property_values::{
        bool_value, f32_clamped_value, normalized_property, u32_value,
    },
};

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeEnvironmentDepthSettings {
    pub(crate) mode: NativeEnvironmentDepthMode,
    pub(crate) source: NativeEnvironmentDepthSource,
    pub(crate) layer_policy: NativeEnvironmentDepthLayerPolicy,
    pub(crate) depth_units_policy: NativeEnvironmentDepthDepthUnitsPolicy,
    pub(crate) debug_view: NativeEnvironmentDepthDebugView,
    pub(crate) reference_space: NativeEnvironmentDepthReferenceSpace,
    pub(crate) hand_removal_requested: bool,
    pub(crate) native_passthrough_required: bool,
    pub(crate) particle_capacity: u32,
    pub(crate) sample_stride_pixels: u32,
    pub(crate) near_m: f32,
    pub(crate) far_m: f32,
    pub(crate) high_rate_json_payload: bool,
    pub(crate) surface_model: NativeEnvironmentDepthSurfaceModel,
    pub(crate) surface_support_radius_cells: u32,
    pub(crate) surface_support_min_neighbors: u32,
    pub(crate) surface_support_min_observations: u32,
    pub(crate) surface_support_min_source_layers: u32,
    pub(crate) surface_support_component_min_cells: u32,
    pub(crate) surface_support_component_mode: NativeEnvironmentDepthSurfaceComponentMode,
    pub(crate) surface_support_normal_source: NativeEnvironmentDepthSurfaceNormalSource,
    pub(crate) surface_support_normal_coherence: NativeEnvironmentDepthSurfaceNormalCoherence,
    pub(crate) surface_support_small_component_policy:
        NativeEnvironmentDepthSurfaceSmallComponentPolicy,
    pub(crate) surface_support_free_space_decay: NativeEnvironmentDepthSurfaceFreeSpaceDecay,
}

impl NativeEnvironmentDepthSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let near_m = f32_clamped_value(lookup(PROP_ENVIRONMENT_DEPTH_NEAR_M), 0.20, 0.001, 10.0);
        let requested_far_m = f32_clamped_value(
            lookup(PROP_ENVIRONMENT_DEPTH_FAR_M),
            5.0,
            near_m + 0.001,
            100.0,
        );
        let far_m = if requested_far_m > near_m {
            requested_far_m
        } else {
            5.0
        };
        Self {
            mode: NativeEnvironmentDepthMode::from_property(lookup(PROP_ENVIRONMENT_DEPTH_MODE)),
            source: NativeEnvironmentDepthSource::from_property(lookup(
                PROP_ENVIRONMENT_DEPTH_SOURCE,
            )),
            layer_policy: NativeEnvironmentDepthLayerPolicy::from_property(lookup(
                PROP_ENVIRONMENT_DEPTH_LAYER_POLICY,
            )),
            depth_units_policy: NativeEnvironmentDepthDepthUnitsPolicy::from_property(lookup(
                PROP_ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY,
            )),
            debug_view: NativeEnvironmentDepthDebugView::from_property(lookup(
                PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW,
            )),
            reference_space: NativeEnvironmentDepthReferenceSpace::from_property(lookup(
                PROP_ENVIRONMENT_DEPTH_REFERENCE_SPACE,
            )),
            hand_removal_requested: bool_value(
                lookup(PROP_ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED),
                false,
            ),
            native_passthrough_required: bool_value(
                lookup(PROP_ENVIRONMENT_DEPTH_NATIVE_PASSTHROUGH_REQUIRED),
                false,
            ),
            particle_capacity: u32_value(
                lookup(PROP_ENVIRONMENT_DEPTH_PARTICLE_CAPACITY),
                32_768,
                64,
                262_144,
            ),
            sample_stride_pixels: u32_value(
                lookup(PROP_ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS),
                12,
                1,
                128,
            ),
            near_m,
            far_m,
            high_rate_json_payload: bool_value(
                lookup(PROP_ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD),
                false,
            ),
            surface_model: NativeEnvironmentDepthSurfaceModel::from_property(lookup(
                PROP_ENVIRONMENT_DEPTH_SURFACE_MODEL,
            )),
            surface_support_radius_cells: u32_value(
                lookup(PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS),
                1,
                1,
                8,
            ),
            surface_support_min_neighbors: u32_value(
                lookup(PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS),
                0,
                0,
                26,
            ),
            surface_support_min_observations: u32_value(
                lookup(PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS),
                1,
                1,
                64,
            ),
            surface_support_min_source_layers: u32_value(
                lookup(PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS),
                1,
                1,
                2,
            ),
            surface_support_component_min_cells: u32_value(
                lookup(PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS),
                1,
                1,
                4096,
            ),
            surface_support_component_mode:
                NativeEnvironmentDepthSurfaceComponentMode::from_property(lookup(
                    PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE,
                )),
            surface_support_normal_source: NativeEnvironmentDepthSurfaceNormalSource::from_property(
                lookup(PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE),
            ),
            surface_support_normal_coherence:
                NativeEnvironmentDepthSurfaceNormalCoherence::from_property(lookup(
                    PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE,
                )),
            surface_support_small_component_policy:
                NativeEnvironmentDepthSurfaceSmallComponentPolicy::from_property(lookup(
                    PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY,
                )),
            surface_support_free_space_decay:
                NativeEnvironmentDepthSurfaceFreeSpaceDecay::from_property(lookup(
                    PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY,
                )),
        }
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "modeProperty={} sourceProperty={} layerPolicyProperty={} depthUnitsPolicyProperty={} debugViewProperty={} handRemovalProperty={} nativePassthroughRequiredProperty={} surfaceModelProperty={} surfaceSupportRadiusCellsProperty={} surfaceSupportMinNeighborsProperty={} surfaceSupportMinObservationsProperty={} surfaceSupportMinSourceLayersProperty={} surfaceSupportComponentMinCellsProperty={} surfaceSupportComponentModeProperty={} surfaceSupportNormalSourceProperty={} surfaceSupportNormalCoherenceProperty={} surfaceSupportSmallComponentPolicyProperty={} surfaceSupportFreeSpaceDecayProperty={} environmentDepthMode={} environmentDepthSource={} environmentDepthSourceViewCount={} environmentDepthSampledLayerMask={} environmentDepthShaderLayerPolicy={} environmentDepthDepthUnitsPolicy={} environmentDepthRawToMetersPolicy={} environmentDepthDebugView={} environmentDepthProviderState={} environmentDepthProviderAvailable=false environmentDepthRealProviderBound=false environmentDepthSupported=false environmentDepthAcquireStatus={} environmentDepthImageSize=0x0 environmentDepthFormat=none environmentDepthLayerCount=0 environmentDepthReferenceSpace={} environmentDepthHandRemovalRequested={} environmentDepthNativePassthroughRequired={} environmentDepthHandRemovalEnabled=false environmentDepthPoseValid=false environmentDepthParticleCapacity={} environmentDepthSampleStridePixels={} environmentDepthNearM={:.3} environmentDepthFarM={:.3} environmentDepthCpuUploadBytes=0 environmentDepthGpuReconstructMs=0.000 environmentDepthGpuMapUpdateMs=0.000 environmentDepthGpuDrawMs=0.000 environmentDepthReadbackCadenceFrames=0 environmentDepthHighRateJsonPayload={} {}",
            PROP_ENVIRONMENT_DEPTH_MODE,
            PROP_ENVIRONMENT_DEPTH_SOURCE,
            PROP_ENVIRONMENT_DEPTH_LAYER_POLICY,
            PROP_ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY,
            PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW,
            PROP_ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED,
            PROP_ENVIRONMENT_DEPTH_NATIVE_PASSTHROUGH_REQUIRED,
            PROP_ENVIRONMENT_DEPTH_SURFACE_MODEL,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY,
            PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY,
            self.mode.marker_value(),
            self.source.marker_value(),
            self.layer_policy.source_view_count(),
            self.layer_policy.sampled_layer_mask(),
            self.layer_policy.marker_value(),
            self.depth_units_policy.marker_value(),
            self.depth_units_policy.raw_to_meters_marker_value(),
            self.debug_view.marker_value(),
            self.source.provider_state_marker(self.mode),
            self.source.acquire_status_marker(self.mode),
            self.reference_space.marker_value(),
            self.hand_removal_requested,
            self.native_passthrough_required,
            self.particle_capacity,
            self.sample_stride_pixels,
            self.near_m,
            self.far_m,
            self.high_rate_json_payload,
            self.surface_support_marker_fields()
        )
    }

    pub(crate) fn synthetic_gpu_proof_requested(self) -> bool {
        self.mode.draws_particles()
            && self.source == NativeEnvironmentDepthSource::SyntheticGpuProof
    }

    pub(crate) fn runtime_provider_requested(self) -> bool {
        self.mode.enabled() && self.source.runtime_provider_requested()
    }

    pub(crate) fn native_passthrough_required(self) -> bool {
        self.native_passthrough_required
    }

    pub(crate) fn mode_draws_particles(self) -> bool {
        self.mode.draws_particles()
    }

    pub(crate) fn scene_particle_map_requested(self) -> bool {
        matches!(self.mode, NativeEnvironmentDepthMode::SceneParticleMap)
    }

    pub(crate) fn mode_enabled(self) -> bool {
        self.mode.enabled()
    }

    pub(crate) fn mode_marker_value(self) -> &'static str {
        self.mode.marker_value()
    }

    pub(crate) fn source_marker_value(self) -> &'static str {
        self.source.marker_value()
    }

    pub(crate) fn layer_policy_marker_value(self) -> &'static str {
        self.layer_policy.marker_value()
    }

    pub(crate) fn depth_units_policy_marker_value(self) -> &'static str {
        self.depth_units_policy.marker_value()
    }

    pub(crate) fn raw_to_meters_policy_marker_value(self) -> &'static str {
        self.depth_units_policy.raw_to_meters_marker_value()
    }

    pub(crate) fn debug_view_marker_value(self) -> &'static str {
        self.debug_view.marker_value()
    }

    pub(crate) fn source_view_count(self) -> u32 {
        self.layer_policy.source_view_count()
    }

    pub(crate) fn source_view_index(self) -> usize {
        self.layer_policy.source_view_index()
    }

    pub(crate) fn sampled_layer_mask(self) -> &'static str {
        self.layer_policy.sampled_layer_mask()
    }

    pub(crate) fn reference_space_marker_value(self) -> &'static str {
        self.reference_space.marker_value()
    }

    pub(crate) fn provider_state_marker_value(self) -> &'static str {
        self.source.provider_state_marker(self.mode)
    }

    pub(crate) fn acquire_status_marker_value(self) -> &'static str {
        self.source.acquire_status_marker(self.mode)
    }

    pub(crate) fn surface_support_requested(self) -> bool {
        self.surface_model.support_requested()
    }

    pub(crate) fn surface_support_status_marker(self) -> &'static str {
        if self.surface_support_requested() {
            "pending-gpu-support-pass"
        } else {
            "disabled"
        }
    }

    pub(crate) fn surface_lifecycle_status_marker(self) -> &'static str {
        if self.surface_support_requested() {
            "pending-runtime-support"
        } else {
            "disabled"
        }
    }

    pub(crate) fn surface_support_marker_fields(self) -> String {
        format!(
            "environmentDepthSurfaceModel={} environmentDepthSurfaceSupportRequested={} environmentDepthSurfaceSupportEnforced=false environmentDepthSurfaceSupportMode={} environmentDepthSurfaceSupportRadiusCells={} environmentDepthSurfaceMinNeighborCount={} environmentDepthSurfaceMinObservationCount={} environmentDepthSurfaceMinSourceLayerCount={} environmentDepthSourceLayerAgreementRequired={} environmentDepthSourceLayerAgreementCells=0 environmentDepthSingleLayerOnlyCells=0 environmentDepthSurfaceComponentMinCells={} environmentDepthSurfaceComponentMode={} environmentDepthSurfaceSmallComponentPolicy={} environmentDepthSurfaceSmallComponentRejectedCells=0 environmentDepthSurfaceComponentCandidateCells=0 environmentDepthSurfaceConfirmedComponentCells=0 environmentDepthSurfaceNormalSource={} environmentDepthSurfaceNormalCoherence={} environmentDepthSurfaceNormalValidCells=0 environmentDepthSurfaceNormalInvalidCells=0 environmentDepthSurfaceNormalRejectedCells=0 environmentDepthSurfaceNormalStatus={} environmentDepthSurfaceFreeSpaceDecay={} environmentDepthSurfaceSupportedCells=0 environmentDepthSurfaceRejectedIsolatedCells=0 environmentDepthSurfaceLargestComponentCells=0 environmentDepthSurfaceSupportStatus={} environmentDepthSurfaceLifecycleStatus={} environmentDepthSurfaceCandidateCells=0 environmentDepthSurfaceConfirmedCells=0 environmentDepthSurfacePromotedCells=0 environmentDepthSurfaceCandidateRetiredCells=0",
            self.surface_model.marker_value(),
            self.surface_support_requested(),
            self.surface_model.support_mode_marker_value(),
            self.surface_support_radius_cells,
            self.surface_support_min_neighbors,
            self.surface_support_min_observations,
            self.surface_support_min_source_layers,
            self.surface_support_min_source_layers > 1,
            self.surface_support_component_min_cells,
            self.surface_support_component_mode.marker_value(),
            self.surface_support_small_component_policy.marker_value(),
            self.surface_support_normal_source.marker_value(),
            self.surface_support_normal_coherence.marker_value(),
            self.surface_normal_status_marker(),
            self.surface_support_free_space_decay.marker_value(),
            self.surface_support_status_marker(),
            self.surface_lifecycle_status_marker(),
        )
    }

    pub(crate) fn surface_normal_status_marker(self) -> &'static str {
        if self.surface_support_normal_source.enabled() {
            "configured-counters-pending"
        } else {
            "disabled"
        }
    }

    pub(crate) fn surface_normal_source_code(self) -> f32 {
        self.surface_support_normal_source.push_constant_code()
    }

    pub(crate) fn surface_normal_coherence_code(self) -> f32 {
        self.surface_support_normal_coherence.push_constant_code()
    }

    pub(crate) fn surface_depth_neighborhood_normals_requested(self) -> bool {
        matches!(
            self.surface_support_normal_source,
            NativeEnvironmentDepthSurfaceNormalSource::DepthNeighborhood
        )
    }
}

impl Default for NativeEnvironmentDepthSettings {
    fn default() -> Self {
        Self {
            mode: NativeEnvironmentDepthMode::Disabled,
            source: NativeEnvironmentDepthSource::RuntimeProvider,
            layer_policy: NativeEnvironmentDepthLayerPolicy::MonoLayer0,
            depth_units_policy: NativeEnvironmentDepthDepthUnitsPolicy::ProjectedDepthFromNearFar,
            debug_view: NativeEnvironmentDepthDebugView::Normal,
            reference_space: NativeEnvironmentDepthReferenceSpace::OpenXrLocal,
            hand_removal_requested: false,
            native_passthrough_required: false,
            particle_capacity: 32_768,
            sample_stride_pixels: 12,
            near_m: 0.20,
            far_m: 5.0,
            high_rate_json_payload: false,
            surface_model: NativeEnvironmentDepthSurfaceModel::Particles,
            surface_support_radius_cells: 1,
            surface_support_min_neighbors: 0,
            surface_support_min_observations: 1,
            surface_support_min_source_layers: 1,
            surface_support_component_min_cells: 1,
            surface_support_component_mode: NativeEnvironmentDepthSurfaceComponentMode::Off,
            surface_support_normal_source: NativeEnvironmentDepthSurfaceNormalSource::Off,
            surface_support_normal_coherence: NativeEnvironmentDepthSurfaceNormalCoherence::Off,
            surface_support_small_component_policy:
                NativeEnvironmentDepthSurfaceSmallComponentPolicy::Dim,
            surface_support_free_space_decay: NativeEnvironmentDepthSurfaceFreeSpaceDecay::Soft,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthSurfaceModel {
    Particles,
    LocalSurfels,
    GlobalSurfaces,
    Hybrid,
}

impl NativeEnvironmentDepthSurfaceModel {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "local-surfels" | "local-surfels-candidates" | "local" => Self::LocalSurfels,
            "global-surfaces" | "confirmed-surfaces" | "global" => Self::GlobalSurfaces,
            "hybrid" | "hybrid-surfaces" | "local-and-global" => Self::Hybrid,
            _ => Self::Particles,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Particles => "particles",
            Self::LocalSurfels => "local-surfels",
            Self::GlobalSurfaces => "global-surfaces",
            Self::Hybrid => "hybrid",
        }
    }

    pub(crate) fn support_mode_marker_value(self) -> &'static str {
        match self {
            Self::Particles => "disabled",
            Self::LocalSurfels => "local-surfels",
            Self::GlobalSurfaces => "global-surfaces",
            Self::Hybrid => "hybrid",
        }
    }

    pub(crate) fn support_requested(self) -> bool {
        !matches!(self, Self::Particles)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthSurfaceComponentMode {
    Off,
    LocalHint,
    ConnectedLabels,
}

impl NativeEnvironmentDepthSurfaceComponentMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "local-hint" | "local" | "hint" | "local-neighborhood" => Self::LocalHint,
            "connected-labels" | "connected" | "labels" | "connected-components" => {
                Self::ConnectedLabels
            }
            _ => Self::Off,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::LocalHint => "local-hint",
            Self::ConnectedLabels => "connected-labels",
        }
    }

    pub(crate) fn push_constant_code(self) -> f32 {
        match self {
            Self::Off => 0.0,
            Self::LocalHint => 1.0,
            Self::ConnectedLabels => 2.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthSurfaceNormalSource {
    Off,
    DepthNeighborhood,
    CellNeighborhood,
}

impl NativeEnvironmentDepthSurfaceNormalSource {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "depth-neighborhood" | "depth" | "depth-view" => Self::DepthNeighborhood,
            "cell-neighborhood" | "cell" | "scene-cell" | "retained-cell" => Self::CellNeighborhood,
            _ => Self::Off,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::DepthNeighborhood => "depth-neighborhood",
            Self::CellNeighborhood => "cell-neighborhood",
        }
    }

    fn enabled(self) -> bool {
        !matches!(self, Self::Off)
    }

    fn push_constant_code(self) -> f32 {
        match self {
            Self::Off => 0.0,
            Self::DepthNeighborhood => 1.0,
            Self::CellNeighborhood => 2.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthSurfaceNormalCoherence {
    Off,
    Loose,
    Strict,
}

impl NativeEnvironmentDepthSurfaceNormalCoherence {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "loose" | "low" => Self::Loose,
            "strict" | "high" => Self::Strict,
            _ => Self::Off,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Loose => "loose",
            Self::Strict => "strict",
        }
    }

    fn push_constant_code(self) -> f32 {
        match self {
            Self::Off => 0.0,
            Self::Loose => 1.0,
            Self::Strict => 2.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthSurfaceSmallComponentPolicy {
    Dim,
    Hide,
    DebugOnly,
}

impl NativeEnvironmentDepthSurfaceSmallComponentPolicy {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "hide" | "hidden" => Self::Hide,
            "debug-only" | "debug" | "diagnostic-only" => Self::DebugOnly,
            _ => Self::Dim,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Dim => "dim",
            Self::Hide => "hide",
            Self::DebugOnly => "debug-only",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthSurfaceFreeSpaceDecay {
    Soft,
    Hard,
}

impl NativeEnvironmentDepthSurfaceFreeSpaceDecay {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "hard" | "immediate" => Self::Hard,
            _ => Self::Soft,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Soft => "soft",
            Self::Hard => "hard",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthDepthUnitsPolicy {
    ProjectedDepthFromNearFar,
}

impl NativeEnvironmentDepthDepthUnitsPolicy {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "projected-depth-from-near-far" | "projected-near-far" | "near-far-projection" => {
                Self::ProjectedDepthFromNearFar
            }
            _ => Self::ProjectedDepthFromNearFar,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::ProjectedDepthFromNearFar => "projected-depth-from-near-far",
        }
    }

    fn raw_to_meters_marker_value(self) -> &'static str {
        match self {
            Self::ProjectedDepthFromNearFar => "projected-depth-from-near-far",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthDebugView {
    Normal,
    RawD16,
    Confidence,
    Age,
    SourceLayer,
    HashProbe,
    FreeSpaceState,
    SurfaceSupport,
    NormalCoherence,
    SupportCount,
    SurfaceResidual,
}

impl NativeEnvironmentDepthDebugView {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "raw-d16" | "raw-depth" | "debug-raw-d16" => Self::RawD16,
            "confidence" | "debug-confidence" | "confidence-filter" => Self::Confidence,
            "age" | "particle-age" | "cell-age" | "debug-age" => Self::Age,
            "source-layer" | "source-layer-mask" | "layer" | "debug-source-layer" => {
                Self::SourceLayer
            }
            "hash-probe" | "probe" | "hash" | "debug-hash-probe" => Self::HashProbe,
            "free-space-state" | "free-space" | "retired-state" | "debug-free-space-state" => {
                Self::FreeSpaceState
            }
            "surface-support" | "surface" | "support" | "debug-surface-support" => {
                Self::SurfaceSupport
            }
            "normal-coherence" | "coherence" | "debug-normal-coherence" => Self::NormalCoherence,
            "support-count" | "surface-support-count" | "debug-support-count" => Self::SupportCount,
            "surface-residual" | "residual" | "debug-surface-residual" => Self::SurfaceResidual,
            _ => Self::Normal,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Normal => "normal",
            Self::RawD16 => "raw-d16",
            Self::Confidence => "confidence",
            Self::Age => "age",
            Self::SourceLayer => "source-layer",
            Self::HashProbe => "hash-probe",
            Self::FreeSpaceState => "free-space-state",
            Self::SurfaceSupport => "surface-support",
            Self::NormalCoherence => "normal-coherence",
            Self::SupportCount => "support-count",
            Self::SurfaceResidual => "surface-residual",
        }
    }

    pub(crate) fn particle_debug_color_mode(self) -> &'static str {
        match self {
            Self::Normal | Self::RawD16 => "depth-gradient",
            Self::Confidence => "confidence",
            Self::Age => "age",
            Self::SourceLayer => "source-layer",
            Self::HashProbe => "hash-probe",
            Self::FreeSpaceState => "free-space-state",
            Self::SurfaceSupport => "surface-support",
            Self::NormalCoherence => "normal-coherence",
            Self::SupportCount => "support-count",
            Self::SurfaceResidual => "surface-residual",
        }
    }

    pub(crate) fn particle_debug_color_code(self) -> f32 {
        match self {
            Self::Normal | Self::RawD16 => 0.0,
            Self::Confidence => 1.0,
            Self::Age => 2.0,
            Self::SourceLayer => 3.0,
            Self::HashProbe => 4.0,
            Self::FreeSpaceState => 5.0,
            Self::SurfaceSupport => 6.0,
            Self::NormalCoherence => 7.0,
            Self::SupportCount => 8.0,
            Self::SurfaceResidual => 9.0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthMode {
    Disabled,
    StatusOnly,
    ProjectionSampler,
    RetainedParticles,
    SceneParticleMap,
}

impl NativeEnvironmentDepthMode {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "status" | "status-only" | "provider-status" => Self::StatusOnly,
            "projection-sampler" | "sampled-provider" | "provider-sampler" => {
                Self::ProjectionSampler
            }
            "retained-particles" | "retained-particle-map" => Self::RetainedParticles,
            "scene-particle-map" | "scene-map" => Self::SceneParticleMap,
            _ => Self::Disabled,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::StatusOnly => "status-only",
            Self::ProjectionSampler => "projection-sampler",
            Self::RetainedParticles => "retained-particles",
            Self::SceneParticleMap => "scene-particle-map",
        }
    }

    fn provider_state_marker(self) -> &'static str {
        match self {
            Self::Disabled => "not-requested",
            Self::StatusOnly => "status-only-skeleton",
            Self::ProjectionSampler => "provider-not-bound",
            Self::RetainedParticles | Self::SceneParticleMap => "provider-not-bound",
        }
    }

    fn acquire_status_marker(self) -> &'static str {
        match self {
            Self::Disabled => "skipped-disabled",
            Self::StatusOnly => "not-attempted-status-only",
            Self::ProjectionSampler => "not-attempted-provider-not-bound",
            Self::RetainedParticles | Self::SceneParticleMap => "not-attempted-provider-not-bound",
        }
    }

    fn enabled(self) -> bool {
        !matches!(self, Self::Disabled)
    }

    fn draws_particles(self) -> bool {
        matches!(self, Self::RetainedParticles | Self::SceneParticleMap)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthSource {
    RuntimeProvider,
    MetaEnvironmentDepth,
    SyntheticGpuProof,
}

impl NativeEnvironmentDepthSource {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "xr-meta-environment-depth" | "meta-environment-depth" | "meta-provider" => {
                Self::MetaEnvironmentDepth
            }
            "synthetic-gpu-proof" | "synthetic-proof" | "synthetic-depth-grid" => {
                Self::SyntheticGpuProof
            }
            _ => Self::RuntimeProvider,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::RuntimeProvider => "runtime-provider",
            Self::MetaEnvironmentDepth => "xr-meta-environment-depth",
            Self::SyntheticGpuProof => "synthetic-gpu-proof",
        }
    }

    fn provider_state_marker(self, mode: NativeEnvironmentDepthMode) -> &'static str {
        match self {
            Self::SyntheticGpuProof if mode.draws_particles() => "synthetic-gpu-proof",
            Self::RuntimeProvider | Self::MetaEnvironmentDepth | Self::SyntheticGpuProof => {
                mode.provider_state_marker()
            }
        }
    }

    fn acquire_status_marker(self, mode: NativeEnvironmentDepthMode) -> &'static str {
        match self {
            Self::SyntheticGpuProof if mode.draws_particles() => {
                "not-attempted-synthetic-gpu-proof"
            }
            Self::RuntimeProvider | Self::MetaEnvironmentDepth | Self::SyntheticGpuProof => {
                mode.acquire_status_marker()
            }
        }
    }

    fn runtime_provider_requested(self) -> bool {
        matches!(self, Self::RuntimeProvider | Self::MetaEnvironmentDepth)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthLayerPolicy {
    MonoLayer0,
    MonoLayer1,
}

impl NativeEnvironmentDepthLayerPolicy {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "mono-layer1" | "layer1" | "view1" | "right" => Self::MonoLayer1,
            _ => Self::MonoLayer0,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::MonoLayer0 => "mono-layer0",
            Self::MonoLayer1 => "mono-layer1",
        }
    }

    fn source_view_count(self) -> u32 {
        1
    }

    fn source_view_index(self) -> usize {
        match self {
            Self::MonoLayer0 => 0,
            Self::MonoLayer1 => 1,
        }
    }

    fn sampled_layer_mask(self) -> &'static str {
        match self {
            Self::MonoLayer0 => "0x1",
            Self::MonoLayer1 => "0x2",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeEnvironmentDepthReferenceSpace {
    OpenXrLocal,
    OpenXrStage,
}

impl NativeEnvironmentDepthReferenceSpace {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            "stage" | "openxr-stage" => Self::OpenXrStage,
            _ => Self::OpenXrLocal,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::OpenXrLocal => "openxr-local",
            Self::OpenXrStage => "openxr-stage",
        }
    }
}
