//! Public, renderer-neutral projection-surface feature transport.
//!
//! This module owns bounded low-rate controls, requested/supported/effective
//! state, and the additive uniform suffix shared with optional projection
//! shaders. It deliberately does not own guide mapping, depth coupling, alpha
//! formulas, or product tuning.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{OnceLock, RwLock};

use crate::projection_surface_displacement::{
    ProjectionSurfaceDisplacementSettings, ProjectionSurfaceDisplacementUniform,
};

pub(crate) const PROJECTION_SURFACE_TILING_CONTRACT_ID: &str =
    "rusty.quest.projection-surface-tiling.v1";
pub(crate) const PROJECTION_INNER_ALPHA_CONTRACT_ID: &str = "rusty.quest.projection-inner-alpha.v1";
pub(crate) const PROJECTION_SURFACE_FEATURE_UNIFORM_ABI_VERSION: u32 = 2;
pub(crate) const PROJECTION_SURFACE_FEATURE_UNIFORM_PREFIX_BYTES: usize = 64;
pub(crate) const PROJECTION_SURFACE_FEATURE_UNIFORM_SUFFIX_BYTES: usize = 64;
pub(crate) const PROJECTION_SURFACE_FEATURE_UNIFORM_BYTES: usize = 128;
pub(crate) const PROJECTION_SURFACE_TILE_GAP_MAX: f32 = 0.45;
pub(crate) const PROJECTION_INNER_ALPHA_SOFTNESS_MIN: f32 = 0.001;
pub(crate) const PROJECTION_INNER_ALPHA_SOFTNESS_MAX: f32 = 0.5;

#[repr(u32)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum ProjectionSurfaceTopology {
    #[default]
    Continuous = 0,
    Tiled = 1,
    TriangleTiles = 2,
}

impl ProjectionSurfaceTopology {
    pub(crate) fn from_raw(value: i32) -> Self {
        match value {
            value if value == Self::Tiled as i32 => Self::Tiled,
            value if value == Self::TriangleTiles as i32 => Self::TriangleTiles,
            _ => Self::Continuous,
        }
    }

    fn marker_token(self) -> &'static str {
        match self {
            Self::Continuous => "continuous",
            Self::Tiled => "tiled",
            Self::TriangleTiles => "triangle-tiles",
        }
    }
}

#[repr(u32)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum ProjectionSurfaceScope {
    #[default]
    CoreAndStretch = 0,
    CoreOnly = 1,
}

impl ProjectionSurfaceScope {
    pub(crate) fn from_raw(value: i32) -> Self {
        if value == Self::CoreOnly as i32 {
            Self::CoreOnly
        } else {
            Self::CoreAndStretch
        }
    }

    fn marker_token(self) -> &'static str {
        match self {
            Self::CoreAndStretch => "core-and-stretch",
            Self::CoreOnly => "core-only",
        }
    }
}

#[repr(u32)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum ProjectionInnerAlphaDriver {
    Red = 0,
    Green = 1,
    Blue = 2,
    #[default]
    Luma = 3,
    Max = 4,
}

impl ProjectionInnerAlphaDriver {
    pub(crate) fn from_raw(value: i32) -> Self {
        match value {
            0 => Self::Red,
            1 => Self::Green,
            2 => Self::Blue,
            4 => Self::Max,
            _ => Self::Luma,
        }
    }

    fn marker_token(self) -> &'static str {
        match self {
            Self::Red => "red",
            Self::Green => "green",
            Self::Blue => "blue",
            Self::Luma => "luma",
            Self::Max => "max",
        }
    }
}

#[repr(u32)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum ProjectionInnerAlphaStretchPolicy {
    #[default]
    FollowProjection = 0,
    OpaqueIndependent = 1,
}

impl ProjectionInnerAlphaStretchPolicy {
    pub(crate) fn from_raw(value: i32) -> Self {
        if value == Self::OpaqueIndependent as i32 {
            Self::OpaqueIndependent
        } else {
            Self::FollowProjection
        }
    }

    fn marker_token(self) -> &'static str {
        match self {
            Self::FollowProjection => "follow-projection",
            Self::OpaqueIndependent => "opaque-independent",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ProjectionSurfaceTilingSettings {
    pub(crate) enabled: bool,
    pub(crate) topology: ProjectionSurfaceTopology,
    pub(crate) gap: f32,
    pub(crate) depth_flexibility: f32,
    pub(crate) scope: ProjectionSurfaceScope,
}

impl Default for ProjectionSurfaceTilingSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            topology: ProjectionSurfaceTopology::Continuous,
            gap: 0.0,
            depth_flexibility: 1.0,
            scope: ProjectionSurfaceScope::CoreAndStretch,
        }
    }
}

impl ProjectionSurfaceTilingSettings {
    pub(crate) fn normalized(mut self) -> Self {
        self.gap = finite_or(self.gap, 0.0).clamp(0.0, PROJECTION_SURFACE_TILE_GAP_MAX);
        self.depth_flexibility = finite_or(self.depth_flexibility, 1.0).clamp(0.0, 1.0);
        if !self.enabled {
            self.topology = ProjectionSurfaceTopology::Continuous;
            self.gap = 0.0;
            self.depth_flexibility = 1.0;
            self.scope = ProjectionSurfaceScope::CoreAndStretch;
        }
        self
    }

    pub(crate) fn requested(self) -> bool {
        self.enabled
    }

    pub(crate) fn effective(self, supported: bool) -> bool {
        self.requested() && supported
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ProjectionInnerAlphaSettings {
    pub(crate) enabled: bool,
    pub(crate) driver: ProjectionInnerAlphaDriver,
    pub(crate) threshold: f32,
    pub(crate) softness: f32,
    pub(crate) amount: f32,
    pub(crate) invert: bool,
    pub(crate) stretch_policy: ProjectionInnerAlphaStretchPolicy,
    pub(crate) stretch_obeys_projection_mask: bool,
}

impl Default for ProjectionInnerAlphaSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            driver: ProjectionInnerAlphaDriver::Luma,
            threshold: 0.5,
            softness: 0.1,
            amount: 0.0,
            invert: false,
            stretch_policy: ProjectionInnerAlphaStretchPolicy::FollowProjection,
            stretch_obeys_projection_mask: false,
        }
    }
}

impl ProjectionInnerAlphaSettings {
    pub(crate) fn normalized(mut self) -> Self {
        self.threshold = finite_or(self.threshold, 0.5).clamp(0.0, 1.0);
        self.softness = finite_or(self.softness, 0.1).clamp(
            PROJECTION_INNER_ALPHA_SOFTNESS_MIN,
            PROJECTION_INNER_ALPHA_SOFTNESS_MAX,
        );
        self.amount = finite_or(self.amount, 0.0).clamp(0.0, 1.0);
        if !self.enabled {
            self.driver = ProjectionInnerAlphaDriver::Luma;
            self.threshold = 0.5;
            self.softness = 0.1;
            self.amount = 0.0;
            self.invert = false;
            self.stretch_policy = ProjectionInnerAlphaStretchPolicy::FollowProjection;
            self.stretch_obeys_projection_mask = false;
        }
        self
    }

    pub(crate) fn requested(self) -> bool {
        self.enabled && self.amount > 0.0001
    }

    pub(crate) fn effective(self, supported: bool) -> bool {
        self.requested() && supported
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub(crate) struct ProjectionSurfaceFeatureSettings {
    pub(crate) tiling: ProjectionSurfaceTilingSettings,
    pub(crate) inner_alpha: ProjectionInnerAlphaSettings,
    pub(crate) revision: u32,
}

impl ProjectionSurfaceFeatureSettings {
    pub(crate) fn normalized(mut self) -> Self {
        self.tiling = self.tiling.normalized();
        self.inner_alpha = self.inner_alpha.normalized();
        self
    }

    pub(crate) fn tessellated_requested(
        self,
        displacement: ProjectionSurfaceDisplacementSettings,
    ) -> bool {
        displacement.requested_active() || self.tiling.requested()
    }

    pub(crate) fn tessellated_effective(
        self,
        displacement: ProjectionSurfaceDisplacementSettings,
        supported: bool,
    ) -> bool {
        self.tessellated_requested(displacement) && supported
    }

    pub(crate) fn marker_fields(
        self,
        tiling_supported: bool,
        inner_alpha_supported: bool,
        provider_abi_version: u32,
    ) -> String {
        format!(
            "projectionSurfaceTilingContract={} projectionSurfaceTilingRequested={} projectionSurfaceTilingSupported={} projectionSurfaceTilingEffective={} projectionSurfaceTopology={} projectionSurfaceTileGapNormalized={:.4} projectionSurfaceDepthFlexibility={:.4} projectionSurfaceTilingScope={} projectionSurfaceRestUvPolicy=preserve-content-identity projectionInnerAlphaContract={} projectionInnerAlphaRequested={} projectionInnerAlphaSupported={} projectionInnerAlphaEffective={} projectionInnerAlphaInput=processed-core projectionInnerAlphaDriver={} projectionInnerAlphaThreshold={:.4} projectionInnerAlphaSoftness={:.4} projectionInnerAlphaAmount={:.4} projectionInnerAlphaInvert={} projectionInnerAlphaStretchPolicy={} projectionInnerAlphaStretchObeysExactProjectionMask={} projectionInnerAlphaComposition=premultiplied-multiply-existing-outer-underlay-alpha projectionSurfaceFeatureRevision={} projectionSurfaceFeatureUniformAbiRequested={} projectionSurfaceFeatureUniformAbiProvided={}",
            PROJECTION_SURFACE_TILING_CONTRACT_ID,
            bool_marker(self.tiling.requested()),
            bool_marker(tiling_supported),
            bool_marker(self.tiling.effective(tiling_supported)),
            self.tiling.topology.marker_token(),
            self.tiling.gap,
            self.tiling.depth_flexibility,
            self.tiling.scope.marker_token(),
            PROJECTION_INNER_ALPHA_CONTRACT_ID,
            bool_marker(self.inner_alpha.requested()),
            bool_marker(inner_alpha_supported),
            bool_marker(self.inner_alpha.effective(inner_alpha_supported)),
            self.inner_alpha.driver.marker_token(),
            self.inner_alpha.threshold,
            self.inner_alpha.softness,
            self.inner_alpha.amount,
            bool_marker(self.inner_alpha.invert),
            self.inner_alpha.stretch_policy.marker_token(),
            bool_marker(self.inner_alpha.stretch_obeys_projection_mask),
            self.revision,
            PROJECTION_SURFACE_FEATURE_UNIFORM_ABI_VERSION,
            provider_abi_version,
        )
    }

    pub(crate) fn uniform(
        self,
        displacement: ProjectionSurfaceDisplacementSettings,
        draw_rects: [[f32; 4]; 2],
    ) -> ProjectionSurfaceFeatureUniformV2 {
        ProjectionSurfaceFeatureUniformV2 {
            prefix: displacement.uniform(draw_rects),
            tiling: [
                if self.tiling.requested() { 1.0 } else { 0.0 },
                self.tiling.topology as u32 as f32,
                self.tiling.gap,
                self.tiling.depth_flexibility,
            ],
            policies: [
                self.tiling.scope as u32 as f32,
                if self.inner_alpha.requested() {
                    1.0
                } else {
                    0.0
                },
                self.inner_alpha.driver as u32 as f32,
                self.inner_alpha.stretch_policy as u32 as f32,
            ],
            alpha: [
                self.inner_alpha.threshold,
                self.inner_alpha.softness,
                self.inner_alpha.amount,
                if self.inner_alpha.invert { 1.0 } else { 0.0 },
            ],
            mask_and_version: [
                if self.inner_alpha.stretch_obeys_projection_mask {
                    1.0
                } else {
                    0.0
                },
                self.revision as f32,
                PROJECTION_SURFACE_FEATURE_UNIFORM_ABI_VERSION as f32,
                0.0,
            ],
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ProjectionSurfaceFeatureUniformV2 {
    pub(crate) prefix: ProjectionSurfaceDisplacementUniform,
    pub(crate) tiling: [f32; 4],
    pub(crate) policies: [f32; 4],
    pub(crate) alpha: [f32; 4],
    pub(crate) mask_and_version: [f32; 4],
}

const _: () = assert!(
    std::mem::offset_of!(ProjectionSurfaceFeatureUniformV2, tiling)
        == PROJECTION_SURFACE_FEATURE_UNIFORM_PREFIX_BYTES
);
const _: () = assert!(
    std::mem::size_of::<ProjectionSurfaceFeatureUniformV2>()
        == PROJECTION_SURFACE_FEATURE_UNIFORM_BYTES
);
const _: () = assert!(
    std::mem::size_of::<ProjectionSurfaceFeatureUniformV2>()
        - std::mem::size_of::<ProjectionSurfaceDisplacementUniform>()
        == PROJECTION_SURFACE_FEATURE_UNIFORM_SUFFIX_BYTES
);

static SETTINGS: OnceLock<RwLock<ProjectionSurfaceFeatureSettings>> = OnceLock::new();
static REVISION: AtomicU32 = AtomicU32::new(0);

fn settings_lock() -> &'static RwLock<ProjectionSurfaceFeatureSettings> {
    SETTINGS.get_or_init(|| RwLock::new(ProjectionSurfaceFeatureSettings::default()))
}

pub(crate) fn current_projection_surface_feature_settings() -> ProjectionSurfaceFeatureSettings {
    *settings_lock()
        .read()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn update_projection_surface_feature_settings(
    tiling_enabled: bool,
    topology: i32,
    gap: f32,
    depth_flexibility: f32,
    scope: i32,
    inner_alpha_enabled: bool,
    inner_alpha_driver: i32,
    threshold: f32,
    softness: f32,
    amount: f32,
    invert: bool,
    stretch_policy: i32,
    stretch_obeys_projection_mask: bool,
) -> ProjectionSurfaceFeatureSettings {
    let revision = REVISION.fetch_add(1, Ordering::AcqRel).saturating_add(1);
    let settings = ProjectionSurfaceFeatureSettings {
        tiling: ProjectionSurfaceTilingSettings {
            enabled: tiling_enabled,
            topology: ProjectionSurfaceTopology::from_raw(topology),
            gap,
            depth_flexibility,
            scope: ProjectionSurfaceScope::from_raw(scope),
        },
        inner_alpha: ProjectionInnerAlphaSettings {
            enabled: inner_alpha_enabled,
            driver: ProjectionInnerAlphaDriver::from_raw(inner_alpha_driver),
            threshold,
            softness,
            amount,
            invert,
            stretch_policy: ProjectionInnerAlphaStretchPolicy::from_raw(stretch_policy),
            stretch_obeys_projection_mask,
        },
        revision,
    }
    .normalized();
    *settings_lock()
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = settings;
    settings
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value
    } else {
        fallback
    }
}

fn bool_marker(value: bool) -> &'static str {
    if value {
        "true"
    } else {
        "false"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_defaults_preserve_legacy_prefix_and_identity_suffix() {
        let features = ProjectionSurfaceFeatureSettings::default();
        let displacement = ProjectionSurfaceDisplacementSettings::default();
        let uniform = features.uniform(displacement, [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]]);
        assert!(!features.tiling.requested());
        assert!(!features.inner_alpha.requested());
        assert_eq!(
            uniform.prefix,
            displacement.uniform(uniform.prefix.draw_rects)
        );
        assert_eq!(uniform.tiling, [0.0, 0.0, 0.0, 1.0]);
        assert_eq!(uniform.policies, [0.0, 0.0, 3.0, 0.0]);
        assert_eq!(uniform.alpha, [0.5, 0.1, 0.0, 0.0]);
        assert_eq!(
            std::mem::size_of::<ProjectionSurfaceDisplacementUniform>(),
            64
        );
        assert_eq!(
            std::mem::size_of::<ProjectionSurfaceFeatureUniformV2>(),
            128
        );
    }

    #[test]
    fn normalization_bounds_damaged_controls() {
        let settings = ProjectionSurfaceFeatureSettings {
            tiling: ProjectionSurfaceTilingSettings {
                enabled: true,
                topology: ProjectionSurfaceTopology::Tiled,
                gap: f32::INFINITY,
                depth_flexibility: -4.0,
                scope: ProjectionSurfaceScope::CoreOnly,
            },
            inner_alpha: ProjectionInnerAlphaSettings {
                enabled: true,
                driver: ProjectionInnerAlphaDriver::Max,
                threshold: 9.0,
                softness: f32::NAN,
                amount: 2.0,
                invert: true,
                stretch_policy: ProjectionInnerAlphaStretchPolicy::OpaqueIndependent,
                stretch_obeys_projection_mask: true,
            },
            revision: 7,
        }
        .normalized();
        assert_eq!(settings.tiling.gap, 0.0);
        assert_eq!(settings.tiling.depth_flexibility, 0.0);
        assert_eq!(settings.inner_alpha.threshold, 1.0);
        assert_eq!(settings.inner_alpha.softness, 0.1);
        assert_eq!(settings.inner_alpha.amount, 1.0);
    }

    #[test]
    fn tiling_and_inner_alpha_request_and_effectiveness_are_independent() {
        let settings = ProjectionSurfaceFeatureSettings {
            tiling: ProjectionSurfaceTilingSettings {
                enabled: true,
                topology: ProjectionSurfaceTopology::Tiled,
                gap: 0.08,
                depth_flexibility: 0.0,
                scope: ProjectionSurfaceScope::CoreOnly,
            },
            inner_alpha: ProjectionInnerAlphaSettings {
                enabled: true,
                amount: 0.75,
                ..ProjectionInnerAlphaSettings::default()
            },
            revision: 3,
        }
        .normalized();
        assert!(settings.tiling.requested());
        assert!(settings.inner_alpha.requested());
        assert!(!settings.tiling.effective(false));
        assert!(settings.inner_alpha.effective(true));
        let markers = settings.marker_fields(false, true, 2);
        assert!(markers.contains("projectionSurfaceTilingEffective=false"));
        assert!(markers.contains("projectionInnerAlphaEffective=true"));
    }

    #[test]
    fn tiling_selects_tessellated_path_without_depth_displacement() {
        let settings = ProjectionSurfaceFeatureSettings {
            tiling: ProjectionSurfaceTilingSettings {
                enabled: true,
                topology: ProjectionSurfaceTopology::Tiled,
                ..ProjectionSurfaceTilingSettings::default()
            },
            ..ProjectionSurfaceFeatureSettings::default()
        }
        .normalized();
        let displacement = ProjectionSurfaceDisplacementSettings::default();
        assert!(settings.tessellated_requested(displacement));
        assert!(!settings.tessellated_effective(displacement, false));
        assert!(settings.tessellated_effective(displacement, true));
    }

    #[test]
    fn triangle_tiles_round_trip_as_topology_two_without_changing_the_draw_size() {
        let settings = ProjectionSurfaceFeatureSettings {
            tiling: ProjectionSurfaceTilingSettings {
                enabled: true,
                topology: ProjectionSurfaceTopology::TriangleTiles,
                gap: 0.08,
                depth_flexibility: 0.0,
                ..ProjectionSurfaceTilingSettings::default()
            },
            revision: 9,
            ..ProjectionSurfaceFeatureSettings::default()
        }
        .normalized();
        let uniform = settings.uniform(
            ProjectionSurfaceDisplacementSettings::default(),
            [[0.0, 0.0, 0.5, 1.0], [0.5, 0.0, 0.5, 1.0]],
        );
        assert_eq!(uniform.tiling[1], 2.0);
        assert!(settings
            .marker_fields(true, false, 2)
            .contains("projectionSurfaceTopology=triangle-tiles"));
        assert_eq!(
            crate::projection_surface_displacement::PROJECTION_SURFACE_GRID_VERTEX_COUNT,
            6144
        );
    }
}
