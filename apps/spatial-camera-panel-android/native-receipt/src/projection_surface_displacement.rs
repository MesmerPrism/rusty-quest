//! Public, renderer-neutral projection-surface displacement transport.
//!
//! This module owns bounded low-rate controls, disabled identity behavior,
//! tessellation metadata, and the uniform ABI. Downstream private payloads own
//! the signal source, signed signal mapping, and product tuning.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{OnceLock, RwLock};

pub(crate) const PROJECTION_SURFACE_DISPLACEMENT_CONTRACT_ID: &str =
    "rusty.quest.projection-surface-displacement.v1";
pub(crate) const PROJECTION_SURFACE_GRID_RESOLUTION: u32 = 32;
pub(crate) const PROJECTION_SURFACE_GRID_VERTEX_COUNT: u32 =
    PROJECTION_SURFACE_GRID_RESOLUTION * PROJECTION_SURFACE_GRID_RESOLUTION * 6;
pub(crate) const PROJECTION_SURFACE_MAX_DISPLACEMENT_M: f32 = 0.35;
pub(crate) const PROJECTION_SURFACE_REFERENCE_DISTANCE_MIN_M: f32 = 1.0;
pub(crate) const PROJECTION_SURFACE_REFERENCE_DISTANCE_MAX_M: f32 = 4.0;
pub(crate) const PROJECTION_SURFACE_EDGE_TAPER_MIN: f32 = 0.02;
pub(crate) const PROJECTION_SURFACE_EDGE_TAPER_MAX: f32 = 0.45;

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ProjectionSurfaceDisplacementSettings {
    pub(crate) enabled: bool,
    pub(crate) max_displacement_m: f32,
    pub(crate) reference_distance_m: f32,
    pub(crate) polarity: f32,
    pub(crate) edge_taper: f32,
    pub(crate) revision: u32,
}

impl Default for ProjectionSurfaceDisplacementSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            max_displacement_m: 0.0,
            reference_distance_m: 2.0,
            polarity: 1.0,
            edge_taper: 0.12,
            revision: 0,
        }
    }
}

impl ProjectionSurfaceDisplacementSettings {
    pub(crate) fn normalized(mut self) -> Self {
        self.max_displacement_m = finite_or(self.max_displacement_m, 0.0)
            .clamp(0.0, PROJECTION_SURFACE_MAX_DISPLACEMENT_M);
        self.reference_distance_m = finite_or(self.reference_distance_m, 2.0).clamp(
            PROJECTION_SURFACE_REFERENCE_DISTANCE_MIN_M,
            PROJECTION_SURFACE_REFERENCE_DISTANCE_MAX_M,
        );
        self.polarity = finite_or(self.polarity, 1.0).clamp(-1.0, 1.0);
        if self.polarity.abs() < 0.001 {
            self.polarity = 1.0;
        }
        self.edge_taper = finite_or(self.edge_taper, 0.12).clamp(
            PROJECTION_SURFACE_EDGE_TAPER_MIN,
            PROJECTION_SURFACE_EDGE_TAPER_MAX,
        );
        if !self.enabled {
            self.max_displacement_m = 0.0;
        }
        self
    }

    pub(crate) fn requested_active(self) -> bool {
        self.enabled && self.max_displacement_m > 0.0001
    }

    pub(crate) fn effective(self, private_vertex_pipeline_available: bool) -> bool {
        self.requested_active() && private_vertex_pipeline_available
    }

    pub(crate) fn marker_fields(self, private_vertex_pipeline_available: bool) -> String {
        format!(
            "projectionSurfaceDisplacementContract={} projectionSurfaceDisplacementRequested={} projectionSurfaceDisplacementEffective={} projectionSurfaceDisplacementPrivateVertexAvailable={} projectionSurfaceDisplacementRevision={} projectionSurfaceDisplacementMaxMeters={:.4} projectionSurfaceReferenceDistanceMeters={:.4} projectionSurfacePolarity={:.3} projectionSurfaceEdgeTaper={:.4} projectionSurfaceGrid={}x{} projectionSurfaceVertexCount={} projectionSurfaceCarrier=planar-spatial-quad projectionSurfaceRepresentation=tessellated-parallax-warp projectionSurfaceDisplacementDisabledPath=original-fullscreen-triangle",
            PROJECTION_SURFACE_DISPLACEMENT_CONTRACT_ID,
            bool_marker(self.requested_active()),
            bool_marker(self.effective(private_vertex_pipeline_available)),
            bool_marker(private_vertex_pipeline_available),
            self.revision,
            self.max_displacement_m,
            self.reference_distance_m,
            self.polarity,
            self.edge_taper,
            PROJECTION_SURFACE_GRID_RESOLUTION,
            PROJECTION_SURFACE_GRID_RESOLUTION,
            PROJECTION_SURFACE_GRID_VERTEX_COUNT,
        )
    }

    pub(crate) fn uniform(self, draw_rects: [[f32; 4]; 2]) -> ProjectionSurfaceDisplacementUniform {
        ProjectionSurfaceDisplacementUniform {
            mode: [
                if self.requested_active() { 1.0 } else { 0.0 },
                self.polarity,
                self.revision as f32,
                PROJECTION_SURFACE_GRID_RESOLUTION as f32,
            ],
            geometry: [
                self.max_displacement_m,
                self.reference_distance_m,
                self.edge_taper,
                0.0,
            ],
            draw_rects,
        }
    }

    #[cfg(test)]
    fn warp_surface_uv(
        self,
        surface_uv: [f32; 2],
        center_uv: [f32; 2],
        centered_signal: f32,
        edge_envelope: f32,
    ) -> [f32; 2] {
        if !self.requested_active() {
            return surface_uv;
        }
        let displacement_m = finite_or(centered_signal, 0.0).clamp(-1.0, 1.0)
            * self.max_displacement_m
            * self.polarity
            * finite_or(edge_envelope, 0.0).clamp(0.0, 1.0);
        let bounded_displacement = displacement_m.clamp(
            -self.reference_distance_m * 0.45,
            self.reference_distance_m * 0.45,
        );
        let scale = self.reference_distance_m / (self.reference_distance_m - bounded_displacement);
        [
            center_uv[0] + (surface_uv[0] - center_uv[0]) * scale,
            center_uv[1] + (surface_uv[1] - center_uv[1]) * scale,
        ]
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ProjectionSurfaceDisplacementUniform {
    pub(crate) mode: [f32; 4],
    pub(crate) geometry: [f32; 4],
    pub(crate) draw_rects: [[f32; 4]; 2],
}

const _: () = assert!(std::mem::size_of::<ProjectionSurfaceDisplacementUniform>() == 64);

static SETTINGS: OnceLock<RwLock<ProjectionSurfaceDisplacementSettings>> = OnceLock::new();
static REVISION: AtomicU32 = AtomicU32::new(0);

fn settings_lock() -> &'static RwLock<ProjectionSurfaceDisplacementSettings> {
    SETTINGS.get_or_init(|| RwLock::new(ProjectionSurfaceDisplacementSettings::default()))
}

pub(crate) fn current_projection_surface_displacement_settings(
) -> ProjectionSurfaceDisplacementSettings {
    *settings_lock()
        .read()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

pub(crate) fn update_projection_surface_displacement_settings(
    enabled: bool,
    max_displacement_m: f32,
    reference_distance_m: f32,
    polarity: f32,
    edge_taper: f32,
) -> ProjectionSurfaceDisplacementSettings {
    let revision = REVISION.fetch_add(1, Ordering::AcqRel).saturating_add(1);
    let settings = ProjectionSurfaceDisplacementSettings {
        enabled,
        max_displacement_m,
        reference_distance_m,
        polarity,
        edge_taper,
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
    fn default_is_exact_identity_and_uses_fullscreen_fallback() {
        let settings = ProjectionSurfaceDisplacementSettings::default();
        assert!(!settings.requested_active());
        assert!(!settings.effective(true));
        assert_eq!(
            settings.warp_surface_uv([0.2, 0.8], [0.25, 0.5], 1.0, 1.0),
            [0.2, 0.8]
        );
        assert_eq!(PROJECTION_SURFACE_GRID_VERTEX_COUNT, 6144);
    }

    #[test]
    fn near_and_far_signals_move_about_one_stereo_eye_center() {
        let settings = ProjectionSurfaceDisplacementSettings {
            enabled: true,
            max_displacement_m: 0.18,
            reference_distance_m: 2.0,
            ..ProjectionSurfaceDisplacementSettings::default()
        }
        .normalized();
        let center = [0.25, 0.5];
        let surface = [0.4, 0.5];
        let near = settings.warp_surface_uv(surface, center, 1.0, 1.0);
        let far = settings.warp_surface_uv(surface, center, -1.0, 1.0);
        assert!(near[0] > surface[0]);
        assert!(far[0] < surface[0]);
        assert_eq!(near[1], surface[1]);
        assert_eq!(far[1], surface[1]);
    }

    #[test]
    fn anchored_edge_has_zero_displacement() {
        let settings = ProjectionSurfaceDisplacementSettings {
            enabled: true,
            max_displacement_m: 0.35,
            ..ProjectionSurfaceDisplacementSettings::default()
        }
        .normalized();
        let anchored = settings.warp_surface_uv([0.1, 0.9], [0.25, 0.5], 1.0, 0.0);
        assert!((anchored[0] - 0.1).abs() < 1.0e-6);
        assert!((anchored[1] - 0.9).abs() < 1.0e-6);
    }

    #[test]
    fn damaged_values_are_finite_and_bounded() {
        let settings = ProjectionSurfaceDisplacementSettings {
            enabled: true,
            max_displacement_m: f32::INFINITY,
            reference_distance_m: -8.0,
            polarity: 0.0,
            edge_taper: 9.0,
            revision: 4,
        }
        .normalized();
        assert_eq!(settings.max_displacement_m, 0.0);
        assert_eq!(settings.reference_distance_m, 1.0);
        assert_eq!(settings.polarity, 1.0);
        assert_eq!(settings.edge_taper, 0.45);
        assert_eq!(
            std::mem::size_of::<ProjectionSurfaceDisplacementUniform>(),
            64
        );
    }

    #[test]
    fn requested_and_effective_are_separate() {
        let settings = ProjectionSurfaceDisplacementSettings {
            enabled: true,
            max_displacement_m: 0.06,
            ..ProjectionSurfaceDisplacementSettings::default()
        }
        .normalized();
        assert!(settings.requested_active());
        assert!(!settings.effective(false));
        assert!(settings.effective(true));
        assert!(settings
            .marker_fields(false)
            .contains("projectionSurfaceDisplacementEffective=false"));
    }
}
