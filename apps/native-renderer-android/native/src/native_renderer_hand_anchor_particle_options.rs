//! Hand-anchor particle property settings for the native renderer.
//!
//! This module owns parsed settings, marker fields, transparency policy, and
//! draw-order policy for resident GPU hand-anchor particle billboards.

use crate::{
    native_renderer_properties::{
        PROP_HAND_ANCHOR_PARTICLES_DYNAMICS, PROP_HAND_ANCHOR_PARTICLES_ENABLED,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES,
        PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE, PROP_HAND_ANCHOR_PARTICLES_PER_HAND,
        PROP_HAND_ANCHOR_PARTICLES_RADIUS_M, PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE,
        PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH,
    },
    native_renderer_property_values::{
        bool_value, f32_clamped_value, normalized_property, u32_value, u64_value,
    },
};

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
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
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

    pub(crate) fn external_payload_requested(self) -> bool {
        false
    }

    pub(crate) fn marker_fields(self) -> String {
        format!(
            "handAnchorParticlesEnabled={} handAnchorParticlesPerHand={} handAnchorParticleRadiusMeters={:.5} handAnchorParticleDynamics={} handAnchorParticleExternalPayloadRequested={} handAnchorParticleTransparencyBlendMode={} handAnchorParticleTransparencyCompositionMode={} handAnchorParticleTransparencyDepthSuppressionStrength={:.3} handAnchorParticleOrderingMode={} handAnchorParticleOrderingImplementation={} handAnchorParticleOrderingIntervalFrames={} handAnchorParticleOrderingStatus={} handAnchorParticleOrderingCpuExpandedUploadPerFrame=false handAnchorParticlePath=resident-skinned-mesh-coordinate-anchor-billboards handAnchorParticleCoordinateSpace=openxr-reference-space handAnchorParticleMask=static-feather-dot-r8-texture handAnchorParticleMaskTextureSharedWithPrivateParticles=true handAnchorParticleAnimation=false handAnchorParticleCpuExpandedUploadPerFrame=false handAnchorParticleMeshUploadPerFrame=false",
            self.enabled,
            self.particles_per_hand,
            self.radius_m,
            self.dynamics.marker_value(),
            self.external_payload_requested(),
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
}

impl NativeHandAnchorParticleDynamics {
    fn from_property(value: Option<String>) -> Self {
        match normalized_property(value).as_str() {
            _ => Self::DeterministicAnchors,
        }
    }

    fn marker_value(self) -> &'static str {
        match self {
            Self::DeterministicAnchors => "deterministic-anchors",
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
