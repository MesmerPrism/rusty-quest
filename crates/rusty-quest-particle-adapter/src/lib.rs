//! Explicit Quest adapter for the accepted Matter, Lattice, and Optics
//! particle contracts.
//!
//! This crate owns platform-facing row preparation only. It does not own
//! simulation, relation truth, appearance policy, renderer resources, runtime
//! activation, or application composition.

use rusty_lattice_model::{validate_situated_anchor, SituatedAnchorSnapshot};
use rusty_matter_particles::ParticleRenderPayload;
use rusty_optics_particles::ParticleVisualFrame;
use serde::{Deserialize, Serialize};

mod lock_bound_activation;

pub use lock_bound_activation::{
    resolve_particle_adapter_activation, ParticleAdapterLockActivationDecision,
    ParticleAdapterLockActivationState, ParticleAdapterLockRejection,
    ParticleAdapterRuntimeActivationInput, LOCK_BOUND_ACTIVATION_SCHEMA_ID,
};

/// Adapter descriptor schema.
pub const PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID: &str =
    "rusty.quest.particle_adapter.descriptor.v1";
/// Adapted frame schema.
pub const PARTICLE_ADAPTER_FRAME_SCHEMA_ID: &str = "rusty.quest.particle_adapter.frame.v1";
/// Activation receipt schema.
pub const PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID: &str = "rusty.quest.particle_adapter.receipt.v1";

/// Closed activation descriptor for one app consumer.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct QuestParticleAdapterDescriptor {
    /// Schema identifier.
    pub schema_id: String,
    /// Stable adapter instance identifier.
    pub adapter_id: String,
    /// Explicit application consumer.
    pub consumer_id: String,
    /// Whether this descriptor selects the adapter for the current run.
    pub enabled: bool,
    /// Maximum accepted particle rows.
    pub max_particles: usize,
    /// Maximum source-to-visual position/radius difference in meters.
    pub numerical_tolerance_m: f32,
}

impl QuestParticleAdapterDescriptor {
    /// Create an explicit descriptor.
    #[must_use]
    pub fn new(
        adapter_id: impl Into<String>,
        consumer_id: impl Into<String>,
        enabled: bool,
        max_particles: usize,
    ) -> Self {
        Self {
            schema_id: PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID.to_owned(),
            adapter_id: adapter_id.into(),
            consumer_id: consumer_id.into(),
            enabled,
            max_particles,
            numerical_tolerance_m: 0.000_1,
        }
    }

    /// Validate descriptor shape and bounded settings.
    ///
    /// # Errors
    ///
    /// Returns an adapter error for unknown schemas, blank identifiers, or
    /// unsafe bounds.
    pub fn validate(&self) -> Result<(), ParticleAdapterError> {
        if self.schema_id != PARTICLE_ADAPTER_DESCRIPTOR_SCHEMA_ID {
            return Err(ParticleAdapterError::UnexpectedSchema);
        }
        if self.adapter_id.trim().is_empty() || self.consumer_id.trim().is_empty() {
            return Err(ParticleAdapterError::EmptyIdentifier);
        }
        if self.max_particles == 0 || self.max_particles > 1_000_000 {
            return Err(ParticleAdapterError::InvalidLimit);
        }
        if !self.numerical_tolerance_m.is_finite()
            || !(0.0..=0.01).contains(&self.numerical_tolerance_m)
        {
            return Err(ParticleAdapterError::InvalidTolerance);
        }
        Ok(())
    }
}

/// One renderer-neutral Quest instance row.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct QuestParticleInstance {
    /// Source Matter particle identifier.
    pub source_particle_id: String,
    /// Position after the Lattice anchor transform, in reference-space meters.
    pub position_m: [f32; 3],
    /// Optics visual radius in meters.
    pub radius_m: f32,
    /// Optics linear RGBA channels.
    pub color_rgba: [f32; 4],
    /// Domain-neutral source flags.
    pub flags: u32,
}

/// Adapted frame for a Quest renderer backend.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct QuestParticleAdapterFrame {
    /// Schema identifier.
    pub schema_id: String,
    /// Selected adapter identifier.
    pub adapter_id: String,
    /// Explicit application consumer.
    pub consumer_id: String,
    /// Source Matter payload identifier.
    pub source_payload_id: String,
    /// Source Optics frame identifier.
    pub source_visual_frame_id: String,
    /// Source Lattice anchor identifier.
    pub anchor_id: String,
    /// Stable reference-space identifier.
    pub reference_space_id: String,
    /// Source time in seconds.
    pub time_seconds: f32,
    /// Renderer-neutral instance rows.
    pub instances: Vec<QuestParticleInstance>,
    /// Radius-expanded minimum bounds.
    pub bounds_min_m: Option<[f32; 3]>,
    /// Radius-expanded maximum bounds.
    pub bounds_max_m: Option<[f32; 3]>,
}

/// Low-rate activation and conformance receipt.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct QuestParticleAdapterReceipt {
    /// Schema identifier.
    pub schema_id: String,
    /// Adapter identifier.
    pub adapter_id: String,
    /// Consumer identifier.
    pub consumer_id: String,
    /// Effective activation state.
    pub enabled: bool,
    /// Number of adapted rows.
    pub particle_count: usize,
    /// Whether source identities matched.
    pub identity_parity: bool,
    /// Whether source positions/radii stayed within tolerance.
    pub numerical_parity: bool,
    /// Whether the descriptor denied platform/backend payload fields.
    pub backend_payload_absent: bool,
}

/// Adapter validation error.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ParticleAdapterError {
    /// Descriptor or input schema did not match.
    UnexpectedSchema,
    /// A stable identifier was blank.
    EmptyIdentifier,
    /// A descriptor bound was invalid.
    InvalidLimit,
    /// Numerical tolerance was invalid.
    InvalidTolerance,
    /// The descriptor did not activate the adapter.
    Disabled,
    /// A source contract failed its owner validation.
    InvalidSourceContract,
    /// Source payload and visual frame identifiers differed.
    SourceMismatch,
    /// Particle row count exceeded the descriptor limit.
    ParticleLimitExceeded,
    /// Particle identity or row count differed across source contracts.
    IdentityMismatch,
    /// Position or radius exceeded the declared parity tolerance.
    NumericalMismatch,
}

impl core::fmt::Display for ParticleAdapterError {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter.write_str(match self {
            Self::UnexpectedSchema => "unexpected schema",
            Self::EmptyIdentifier => "identifier must not be blank",
            Self::InvalidLimit => "particle limit is invalid",
            Self::InvalidTolerance => "numerical tolerance is invalid",
            Self::Disabled => "particle adapter is disabled",
            Self::InvalidSourceContract => "source contract is invalid",
            Self::SourceMismatch => "source payload and visual frame do not match",
            Self::ParticleLimitExceeded => "particle count exceeds descriptor limit",
            Self::IdentityMismatch => "source particle identity mismatch",
            Self::NumericalMismatch => "source particle numerical mismatch",
        })
    }
}

impl std::error::Error for ParticleAdapterError {}

/// Adapt owner-contract particle data into renderer-neutral Quest rows.
///
/// # Errors
///
/// Rejects disabled descriptors, invalid owner contracts, identity/count drift,
/// numerical drift beyond tolerance, and over-limit frames.
pub fn adapt_particle_frame(
    descriptor: &QuestParticleAdapterDescriptor,
    payload: &ParticleRenderPayload,
    anchor: &SituatedAnchorSnapshot,
    visual: &ParticleVisualFrame,
) -> Result<(QuestParticleAdapterFrame, QuestParticleAdapterReceipt), ParticleAdapterError> {
    descriptor.validate()?;
    if !descriptor.enabled {
        return Err(ParticleAdapterError::Disabled);
    }
    payload
        .validate()
        .map_err(|_| ParticleAdapterError::InvalidSourceContract)?;
    visual
        .validate()
        .map_err(|_| ParticleAdapterError::InvalidSourceContract)?;
    validate_situated_anchor(anchor).map_err(|_| ParticleAdapterError::InvalidSourceContract)?;
    if visual.source_payload_id != payload.payload_id
        || visual.source_schema_id != payload.schema_id
        || (visual.time_seconds - payload.time_seconds).abs() > descriptor.numerical_tolerance_m
    {
        return Err(ParticleAdapterError::SourceMismatch);
    }
    if payload.samples.len() > descriptor.max_particles {
        return Err(ParticleAdapterError::ParticleLimitExceeded);
    }
    if payload.samples.len() != visual.samples.len() {
        return Err(ParticleAdapterError::IdentityMismatch);
    }

    let mut instances = Vec::with_capacity(payload.samples.len());
    for (matter, optics) in payload.samples.iter().zip(&visual.samples) {
        if matter.particle_id != optics.source_particle_id {
            return Err(ParticleAdapterError::IdentityMismatch);
        }
        if vec3_delta_max(matter.position, optics.position) > descriptor.numerical_tolerance_m
            || (matter.radius - optics.radius).abs() > descriptor.numerical_tolerance_m
        {
            return Err(ParticleAdapterError::NumericalMismatch);
        }
        let rotated = rotate(
            [
                anchor.pose.orientation.x,
                anchor.pose.orientation.y,
                anchor.pose.orientation.z,
                anchor.pose.orientation.w,
            ],
            [optics.position.x, optics.position.y, optics.position.z],
        );
        instances.push(QuestParticleInstance {
            source_particle_id: matter.particle_id.clone(),
            position_m: [
                rotated[0] + anchor.pose.position.x,
                rotated[1] + anchor.pose.position.y,
                rotated[2] + anchor.pose.position.z,
            ],
            radius_m: optics.radius,
            color_rgba: [
                optics.color.r,
                optics.color.g,
                optics.color.b,
                optics.color.a,
            ],
            flags: optics.flags,
        });
    }
    let (bounds_min_m, bounds_max_m) = expanded_bounds(&instances);
    let frame = QuestParticleAdapterFrame {
        schema_id: PARTICLE_ADAPTER_FRAME_SCHEMA_ID.to_owned(),
        adapter_id: descriptor.adapter_id.clone(),
        consumer_id: descriptor.consumer_id.clone(),
        source_payload_id: payload.payload_id.clone(),
        source_visual_frame_id: visual.frame_id.clone(),
        anchor_id: anchor.anchor_id.clone(),
        reference_space_id: anchor.reference_space.stable_id.clone(),
        time_seconds: payload.time_seconds,
        instances,
        bounds_min_m,
        bounds_max_m,
    };
    let receipt = QuestParticleAdapterReceipt {
        schema_id: PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID.to_owned(),
        adapter_id: descriptor.adapter_id.clone(),
        consumer_id: descriptor.consumer_id.clone(),
        enabled: true,
        particle_count: frame.instances.len(),
        identity_parity: true,
        numerical_parity: true,
        backend_payload_absent: true,
    };
    Ok((frame, receipt))
}

/// Build a low-rate activation receipt before high-rate rows are available.
///
/// # Errors
///
/// Rejects an invalid descriptor.
pub fn activation_receipt(
    descriptor: &QuestParticleAdapterDescriptor,
) -> Result<QuestParticleAdapterReceipt, ParticleAdapterError> {
    descriptor.validate()?;
    Ok(QuestParticleAdapterReceipt {
        schema_id: PARTICLE_ADAPTER_RECEIPT_SCHEMA_ID.to_owned(),
        adapter_id: descriptor.adapter_id.clone(),
        consumer_id: descriptor.consumer_id.clone(),
        enabled: descriptor.enabled,
        particle_count: 0,
        identity_parity: descriptor.enabled,
        numerical_parity: descriptor.enabled,
        backend_payload_absent: true,
    })
}

fn vec3_delta_max(left: rusty_matter_model::Vec3, right: rusty_matter_model::Vec3) -> f32 {
    (left.x - right.x)
        .abs()
        .max((left.y - right.y).abs())
        .max((left.z - right.z).abs())
}

fn rotate(quaternion: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let q = [quaternion[0], quaternion[1], quaternion[2]];
    let uv = cross(q, vector);
    let uuv = cross(q, uv);
    [
        vector[0] + 2.0 * (quaternion[3] * uv[0] + uuv[0]),
        vector[1] + 2.0 * (quaternion[3] * uv[1] + uuv[1]),
        vector[2] + 2.0 * (quaternion[3] * uv[2] + uuv[2]),
    ]
}

fn cross(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

fn expanded_bounds(instances: &[QuestParticleInstance]) -> (Option<[f32; 3]>, Option<[f32; 3]>) {
    let Some(first) = instances.first() else {
        return (None, None);
    };
    let mut minimum = [
        first.position_m[0] - first.radius_m,
        first.position_m[1] - first.radius_m,
        first.position_m[2] - first.radius_m,
    ];
    let mut maximum = [
        first.position_m[0] + first.radius_m,
        first.position_m[1] + first.radius_m,
        first.position_m[2] + first.radius_m,
    ];
    for instance in &instances[1..] {
        for axis in 0..3 {
            minimum[axis] = minimum[axis].min(instance.position_m[axis] - instance.radius_m);
            maximum[axis] = maximum[axis].max(instance.position_m[axis] + instance.radius_m);
        }
    }
    (Some(minimum), Some(maximum))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_lattice_model::{
        Pose, Quat, ReferenceSpace, ReferenceSpaceKind, Vec3 as LatticeVec3,
    };
    use rusty_matter_model::Vec3;
    use rusty_matter_particles::{ParticleRenderPayload, ParticleSet, ParticleState};
    use rusty_optics_model::ColorRgba;
    use rusty_optics_particles::ParticleVisualFrame;

    fn inputs() -> (
        ParticleRenderPayload,
        SituatedAnchorSnapshot,
        ParticleVisualFrame,
    ) {
        let mut set = ParticleSet::new("particles.quest.adapter");
        set.push(ParticleState::new(
            "particle.quest.0",
            Vec3::new(0.25, 0.5, -1.0),
            0.02,
        ));
        let payload =
            ParticleRenderPayload::from_particle_set("particle.payload.quest.adapter", &set)
                .unwrap();
        let visual = ParticleVisualFrame::from_matter_payload(
            "particle.visual.quest.adapter",
            &payload,
            ColorRgba::new(0.2, 0.8, 1.0, 0.7),
        )
        .unwrap();
        let anchor = SituatedAnchorSnapshot {
            schema: rusty_lattice_model::SITUATED_ANCHOR_SCHEMA_ID.to_owned(),
            anchor_id: "anchor.quest.adapter".to_owned(),
            reference_space: ReferenceSpace::new("space.local", ReferenceSpaceKind::Local),
            pose: Pose::new(LatticeVec3::new(1.0, 0.0, 0.0), Quat::IDENTITY),
            valid: true,
            confidence: 1.0,
            observed_at_ns: Some(1),
            revision: 1,
            source: "fixture.quest.adapter".to_owned(),
        };
        (payload, anchor, visual)
    }

    #[test]
    fn adapter_preserves_identity_and_applies_lattice_anchor() {
        let descriptor =
            QuestParticleAdapterDescriptor::new("adapter.test", "consumer.test", true, 16);
        let (payload, anchor, visual) = inputs();
        let (frame, receipt) =
            adapt_particle_frame(&descriptor, &payload, &anchor, &visual).unwrap();
        assert_eq!(frame.instances[0].source_particle_id, "particle.quest.0");
        assert_eq!(frame.instances[0].position_m, [1.25, 0.5, -1.0]);
        assert_eq!(receipt.particle_count, 1);
        assert!(receipt.identity_parity && receipt.numerical_parity);
    }

    #[test]
    fn disabled_descriptor_is_inert() {
        let descriptor =
            QuestParticleAdapterDescriptor::new("adapter.disabled", "consumer.test", false, 16);
        let (payload, anchor, visual) = inputs();
        assert_eq!(
            adapt_particle_frame(&descriptor, &payload, &anchor, &visual),
            Err(ParticleAdapterError::Disabled)
        );
        let receipt = activation_receipt(&descriptor).unwrap();
        assert!(!receipt.enabled);
        assert_eq!(receipt.particle_count, 0);
    }

    #[test]
    fn mismatched_visual_identity_fails_closed() {
        let descriptor =
            QuestParticleAdapterDescriptor::new("adapter.test", "consumer.test", true, 16);
        let (payload, anchor, mut visual) = inputs();
        visual.samples[0].source_particle_id = "particle.wrong".to_owned();
        assert_eq!(
            adapt_particle_frame(&descriptor, &payload, &anchor, &visual),
            Err(ParticleAdapterError::IdentityMismatch)
        );
    }

    #[test]
    fn descriptor_rejects_backend_field_leakage() {
        let damaged = r#"{"schema_id":"rusty.quest.particle_adapter.descriptor.v1","adapter_id":"adapter.test","consumer_id":"consumer.test","enabled":true,"max_particles":16,"numerical_tolerance_m":0.0001,"renderer_resource":"vk-buffer"}"#;
        assert!(serde_json::from_str::<QuestParticleAdapterDescriptor>(damaged).is_err());
    }
}
