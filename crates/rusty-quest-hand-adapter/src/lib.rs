//! Provider-neutral hand contract adapter for Quest app shells.

use std::collections::BTreeSet;
use std::fmt;

use rusty_lattice_model::{
    validate_hand_joint_mapping_snapshot, validate_hand_provider_frame_match, HandCoordinateBasis,
    HandJointFrameSnapshot, HandJointMappingSnapshot, HandProviderCapabilitySnapshot,
    Handedness as LatticeHandedness,
};
use rusty_matter_mesh::{
    HandJointFrame as MatterHandJointFrame, HandJointPose as MatterHandJointPose,
    HandSkinningMatrixSample, HandSubstrateConformance, Handedness as MatterHandedness,
};
use rusty_matter_model::Vec3 as MatterVec3;
use rusty_optics_model::{HandSubstrateVisualProfile, HandVisualSide};
use serde::{Deserialize, Serialize};

mod lock_bound_activation;

pub use lock_bound_activation::{
    resolve_hand_adapter_activation, HandAdapterLockActivationDecision,
    HandAdapterLockActivationState, HandAdapterLockRejection, HandAdapterRuntimeActivationInput,
    LOCK_BOUND_ACTIVATION_SCHEMA_ID,
};

/// Schema id for explicit Quest hand adapter descriptors.
pub const HAND_ADAPTER_DESCRIPTOR_SCHEMA_ID: &str = "rusty.quest.hand_adapter.descriptor.v1";
/// Schema id for prepared Quest hand adapter frames.
pub const HAND_ADAPTER_FRAME_SCHEMA_ID: &str = "rusty.quest.hand_adapter.frame.v1";
/// Schema id for Quest hand adapter receipts.
pub const HAND_ADAPTER_RECEIPT_SCHEMA_ID: &str = "rusty.quest.hand_adapter.receipt.v1";

/// Explicit activation and identity policy for one app-local hand consumer.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HandAdapterDescriptor {
    /// Descriptor schema.
    pub schema: String,
    /// Stable adapter instance id.
    pub adapter_id: String,
    /// App-local consumer id.
    pub consumer_id: String,
    /// Explicit activation bit.
    pub enabled: bool,
    /// Expected provider id; substitution fails closed.
    pub provider_id: String,
    /// Expected provider coordinate basis.
    pub coordinate_basis: HandCoordinateBasis,
    /// Maximum accepted CPU/prepared-row numerical error.
    pub parity_tolerance: f32,
}

impl HandAdapterDescriptor {
    /// Validate the descriptor without touching platform state.
    pub fn validate(&self) -> Result<(), HandAdapterError> {
        if self.schema != HAND_ADAPTER_DESCRIPTOR_SCHEMA_ID {
            return Err(HandAdapterError::InvalidDescriptor("unexpected schema"));
        }
        if self.adapter_id.trim().is_empty()
            || self.consumer_id.trim().is_empty()
            || self.provider_id.trim().is_empty()
        {
            return Err(HandAdapterError::InvalidDescriptor(
                "adapter, consumer, and provider ids must be non-empty",
            ));
        }
        if !self.parity_tolerance.is_finite() || self.parity_tolerance < 0.0 {
            return Err(HandAdapterError::InvalidDescriptor(
                "parity tolerance must be finite and non-negative",
            ));
        }
        Ok(())
    }
}

/// Renderer-neutral prepared frame containing Matter-owned skinning rows.
#[derive(Clone, Debug, PartialEq)]
pub struct PreparedHandAdapterFrame {
    /// Frame schema.
    pub schema: &'static str,
    /// Adapter id.
    pub adapter_id: String,
    /// Consumer id.
    pub consumer_id: String,
    /// Provider id.
    pub provider_id: String,
    /// Lattice frame id.
    pub lattice_frame_id: String,
    /// Matter rig id.
    pub matter_rig_id: String,
    /// Logical hand.
    pub hand: LatticeHandedness,
    /// Provider coordinate basis.
    pub coordinate_basis: HandCoordinateBasis,
    /// GPU-ready rows with Matter CPU expected positions.
    pub rows: Vec<HandSkinningMatrixSample>,
    /// Stable triangle topology.
    pub triangles: Vec<[u32; 3]>,
}

/// Compact acceptance receipt safe for logs and scorecards.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HandAdapterReceipt {
    /// Receipt schema.
    pub schema: String,
    /// Adapter id.
    pub adapter_id: String,
    /// Consumer id.
    pub consumer_id: String,
    /// Whether the adapter was explicitly enabled.
    pub enabled: bool,
    /// Whether owner-contract identity validation passed.
    pub identity_preserved: bool,
    /// Whether CPU/prepared-row parity passed.
    pub parity_passed: bool,
    /// Number of prepared rows.
    pub row_count: usize,
    /// Number of topology triangles.
    pub triangle_count: usize,
    /// High-rate JSON is forbidden.
    pub high_rate_json: bool,
    /// Backend payloads are absent.
    pub backend_payload_absent: bool,
}

/// Adapter rejection reason.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HandAdapterError {
    /// Adapter is disabled and must remain inert.
    Disabled,
    /// Descriptor was malformed.
    InvalidDescriptor(&'static str),
    /// Owner contract rejected the input.
    OwnerContract(String),
    /// Identity changed across owner lanes.
    IdentityMismatch(&'static str),
    /// Prepared rows no longer match Matter CPU expectations.
    ParityMismatch,
}

impl fmt::Display for HandAdapterError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Disabled => formatter.write_str("hand adapter is disabled"),
            Self::InvalidDescriptor(reason) => write!(formatter, "invalid descriptor: {reason}"),
            Self::OwnerContract(reason) => {
                write!(formatter, "owner contract rejected input: {reason}")
            }
            Self::IdentityMismatch(reason) => write!(formatter, "hand identity mismatch: {reason}"),
            Self::ParityMismatch => {
                formatter.write_str("prepared rows do not match Matter CPU oracle")
            }
        }
    }
}

impl std::error::Error for HandAdapterError {}

/// Prepare a hand frame from accepted owner contracts.
pub fn prepare_hand_frame(
    descriptor: &HandAdapterDescriptor,
    capability: &HandProviderCapabilitySnapshot,
    lattice_frame: &HandJointFrameSnapshot,
    mapping: &HandJointMappingSnapshot,
    matter: &HandSubstrateConformance,
    optics: &HandSubstrateVisualProfile,
) -> Result<(PreparedHandAdapterFrame, HandAdapterReceipt), HandAdapterError> {
    descriptor.validate()?;
    if !descriptor.enabled {
        return Err(HandAdapterError::Disabled);
    }
    validate_hand_provider_frame_match(capability, lattice_frame)
        .map_err(|errors| HandAdapterError::OwnerContract(format_errors(&errors)))?;
    validate_hand_joint_mapping_snapshot(mapping)
        .map_err(|errors| HandAdapterError::OwnerContract(format_errors(&errors)))?;
    matter
        .validate()
        .map_err(|error| HandAdapterError::OwnerContract(error.to_string()))?;
    optics
        .validate()
        .map_err(|error| HandAdapterError::OwnerContract(error.to_string()))?;

    if descriptor.provider_id != capability.provider_id
        || descriptor.provider_id != lattice_frame.provider_id
        || descriptor.provider_id != mapping.provider_id
        || descriptor.provider_id != matter.provider_id
        || descriptor.provider_id != optics.provider_id
    {
        return Err(HandAdapterError::IdentityMismatch("provider"));
    }
    if descriptor.coordinate_basis != capability.coordinate_basis
        || descriptor.coordinate_basis != lattice_frame.coordinate_basis
        || coordinate_basis_id(descriptor.coordinate_basis) != matter.coordinate_basis
    {
        return Err(HandAdapterError::IdentityMismatch("coordinate basis"));
    }
    if matter.lattice_frame_id != lattice_frame.frame_id
        || optics.lattice_frame_id != lattice_frame.frame_id
        || optics.matter_rig_id != matter.rig.rig_capture_id
    {
        return Err(HandAdapterError::IdentityMismatch("frame or rig"));
    }
    if mapping.target_schema_id != matter.rig.schema_id
        || usize::from(mapping.target_joint_count) != matter.rig.joint_count()
    {
        return Err(HandAdapterError::IdentityMismatch("mapping target rig"));
    }
    let expected_matter_hand = matter_hand(lattice_frame.hand);
    let expected_visual_hand = visual_hand(lattice_frame.hand);
    if matter.rig.handedness != expected_matter_hand
        || matter.joint_frame.handedness != expected_matter_hand
        || optics.hand != expected_visual_hand
    {
        return Err(HandAdapterError::IdentityMismatch("handedness"));
    }
    if matter.rig.reference_space != lattice_frame.reference_space.stable_id {
        return Err(HandAdapterError::IdentityMismatch("reference space"));
    }

    let mapped_frame = map_joint_frame(mapping, lattice_frame, matter)?;
    let mut adapted_matter = matter.clone();
    adapted_matter.joint_frame = mapped_frame;
    adapted_matter
        .validate()
        .map_err(|error| HandAdapterError::OwnerContract(error.to_string()))?;
    let oracle = adapted_matter
        .rig
        .skinning_mesh_buffer_oracle(&adapted_matter.joint_frame)
        .map_err(|error| HandAdapterError::OwnerContract(error.to_string()))?;
    if !oracle
        .vertices
        .iter()
        .all(|sample| sample_parity_error(sample) <= descriptor.parity_tolerance)
    {
        return Err(HandAdapterError::ParityMismatch);
    }

    let frame = PreparedHandAdapterFrame {
        schema: HAND_ADAPTER_FRAME_SCHEMA_ID,
        adapter_id: descriptor.adapter_id.clone(),
        consumer_id: descriptor.consumer_id.clone(),
        provider_id: descriptor.provider_id.clone(),
        lattice_frame_id: lattice_frame.frame_id.clone(),
        matter_rig_id: matter.rig.rig_capture_id.clone(),
        hand: lattice_frame.hand,
        coordinate_basis: lattice_frame.coordinate_basis,
        rows: oracle.vertices,
        triangles: oracle.triangles,
    };
    let receipt = HandAdapterReceipt {
        schema: HAND_ADAPTER_RECEIPT_SCHEMA_ID.to_owned(),
        adapter_id: descriptor.adapter_id.clone(),
        consumer_id: descriptor.consumer_id.clone(),
        enabled: true,
        identity_preserved: true,
        parity_passed: true,
        row_count: frame.rows.len(),
        triangle_count: frame.triangles.len(),
        high_rate_json: false,
        backend_payload_absent: true,
    };
    Ok((frame, receipt))
}

/// Build an inert receipt for an explicitly disabled consumer.
pub fn disabled_receipt(
    descriptor: &HandAdapterDescriptor,
) -> Result<HandAdapterReceipt, HandAdapterError> {
    descriptor.validate()?;
    Ok(HandAdapterReceipt {
        schema: HAND_ADAPTER_RECEIPT_SCHEMA_ID.to_owned(),
        adapter_id: descriptor.adapter_id.clone(),
        consumer_id: descriptor.consumer_id.clone(),
        enabled: false,
        identity_preserved: false,
        parity_passed: false,
        row_count: 0,
        triangle_count: 0,
        high_rate_json: false,
        backend_payload_absent: true,
    })
}

/// Marker payload used by app-local effective-runtime receipts.
///
/// An accepted marker can only be constructed from an applied lock-bound
/// decision. Rejected decisions remain disabled and carry their failure reason.
#[must_use]
pub fn activation_marker(
    consumer_id: &str,
    decision: &HandAdapterLockActivationDecision,
) -> String {
    let enabled = decision.is_applied();
    format!(
        "status={} handAdapterDescriptorSchema={} handAdapterFrameSchema={} handAdapterReceiptSchema={} handAdapterConsumer={} handAdapterEnabled={} handAdapterSourceContracts=lattice-hand-frame+matter-hand-substrate+optics-hand-visual handAdapterBothHands={} handAdapterCoordinateBasisPreserved={} handAdapterCpuPreparedParity={} handAdapterHighRateJson=false handAdapterBackendPayloadAbsent=true {}",
        if enabled { "accepted" } else { "rejected" },
        HAND_ADAPTER_DESCRIPTOR_SCHEMA_ID,
        HAND_ADAPTER_FRAME_SCHEMA_ID,
        HAND_ADAPTER_RECEIPT_SCHEMA_ID,
        consumer_id,
        enabled,
        enabled,
        enabled,
        enabled,
        decision.marker_fields(),
    )
}

fn map_joint_frame(
    mapping: &HandJointMappingSnapshot,
    lattice_frame: &HandJointFrameSnapshot,
    matter: &HandSubstrateConformance,
) -> Result<MatterHandJointFrame, HandAdapterError> {
    let mut target_indices = BTreeSet::new();
    let mut poses = vec![None; matter.rig.joint_count()];
    let mut confidence = vec![0.0; matter.rig.joint_count()];
    for entry in &mapping.entries {
        if !target_indices.insert(entry.target_joint_index) {
            return Err(HandAdapterError::IdentityMismatch("duplicate target joint"));
        }
        let source = lattice_frame
            .joints
            .iter()
            .find(|joint| joint.joint_index == entry.source_joint_index)
            .ok_or(HandAdapterError::IdentityMismatch("missing source joint"))?;
        if source.joint_name != entry.source_joint_name {
            return Err(HandAdapterError::IdentityMismatch("joint name"));
        }
        let target = usize::from(entry.target_joint_index);
        poses[target] = Some(MatterHandJointPose {
            position: MatterVec3::new(
                source.pose.position.x,
                source.pose.position.y,
                source.pose.position.z,
            ),
            orientation_xyzw: [
                source.pose.orientation.x,
                source.pose.orientation.y,
                source.pose.orientation.z,
                source.pose.orientation.w,
            ],
            radius_m: source.radius_m.unwrap_or(0.0),
        });
        confidence[target] = source.confidence;
    }
    let poses =
        poses
            .into_iter()
            .collect::<Option<Vec<_>>>()
            .ok_or(HandAdapterError::IdentityMismatch(
                "incomplete target joint mapping",
            ))?;
    Ok(MatterHandJointFrame {
        schema_id: rusty_matter_mesh::HAND_JOINT_FRAME_SCHEMA_ID.to_owned(),
        frame_id: lattice_frame.frame_id.clone(),
        handedness: matter_hand(lattice_frame.hand),
        reference_space: lattice_frame.reference_space.stable_id.clone(),
        source: lattice_frame.provider_id.clone(),
        time_seconds: lattice_frame.timestamp_ns as f32 / 1_000_000_000.0,
        poses,
        confidence,
    })
}

fn sample_parity_error(sample: &HandSkinningMatrixSample) -> f32 {
    let mut out = [0.0_f32; 4];
    let mut total_weight = 0.0_f32;
    for slot in 0..sample.joint_weights.len() {
        let weight = sample.joint_weights[slot];
        if weight <= 0.0 {
            continue;
        }
        let matrix = sample.joint_matrices[slot];
        for row in 0..4 {
            out[row] += weight
                * (0..4)
                    .map(|column| matrix[row][column] * sample.bind_position[column])
                    .sum::<f32>();
        }
        total_weight += weight;
    }
    if total_weight > 0.0 {
        out.iter_mut().for_each(|value| *value /= total_weight);
    }
    out.iter()
        .zip(sample.expected_position.iter())
        .map(|(actual, expected)| (actual - expected).abs())
        .fold(0.0, f32::max)
}

fn matter_hand(hand: LatticeHandedness) -> MatterHandedness {
    match hand {
        LatticeHandedness::Left => MatterHandedness::Left,
        LatticeHandedness::Right => MatterHandedness::Right,
    }
}

fn visual_hand(hand: LatticeHandedness) -> HandVisualSide {
    match hand {
        LatticeHandedness::Left => HandVisualSide::Left,
        LatticeHandedness::Right => HandVisualSide::Right,
    }
}

fn coordinate_basis_id(basis: HandCoordinateBasis) -> &'static str {
    match basis {
        HandCoordinateBasis::RightHandedYUpNegativeZForward => {
            "right_handed_y_up_negative_z_forward"
        }
        HandCoordinateBasis::RightHandedYUpPositiveZForward => {
            "right_handed_y_up_positive_z_forward"
        }
    }
}

fn format_errors(errors: &[rusty_lattice_model::LatticeValidationError]) -> String {
    errors
        .iter()
        .map(|error| error.message.as_str())
        .collect::<Vec<_>>()
        .join("; ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_lattice_model::{
        HandCapability, HandJointMapEntry, HandJointSample, HandJointSet, HandMeshBinding,
        HandRuntimeSignals, Pose, Quat, ReferenceSpace, ReferenceSpaceKind, Vec3,
        HAND_JOINT_FRAME_SCHEMA_ID, HAND_JOINT_MAPPING_SCHEMA_ID,
    };
    use rusty_matter_mesh::{HandRigCapture, TriangleMeshSurface};
    use rusty_optics_model::{ColorRgba, HAND_SUBSTRATE_VISUAL_PROFILE_SCHEMA_ID};

    fn descriptor(consumer: &str, enabled: bool) -> HandAdapterDescriptor {
        HandAdapterDescriptor {
            schema: HAND_ADAPTER_DESCRIPTOR_SCHEMA_ID.to_owned(),
            adapter_id: format!("adapter.quest.{consumer}.hand"),
            consumer_id: consumer.to_owned(),
            enabled,
            provider_id: "generic-tracked-hand-provider".to_owned(),
            coordinate_basis: HandCoordinateBasis::RightHandedYUpNegativeZForward,
            parity_tolerance: 1.0e-5,
        }
    }

    fn capability() -> HandProviderCapabilitySnapshot {
        HandProviderCapabilitySnapshot::new(
            "capability.hand.generic",
            "generic-tracked-hand-provider",
            "synthetic-test",
            ReferenceSpace::new("local-stage", ReferenceSpaceKind::Stage),
            HandCoordinateBasis::RightHandedYUpNegativeZForward,
            "predicted_display_time_ns",
            1,
            [LatticeHandedness::Left, LatticeHandedness::Right]
                .into_iter()
                .map(|hand| HandCapability {
                    hand,
                    joint_set: HandJointSet::OpenXrExtHandTracking26,
                    joint_count: 26,
                    mesh_binding: HandMeshBinding::StaticBindMesh,
                    signals: HandRuntimeSignals {
                        joint_poses: true,
                        mesh_vertices: true,
                        mesh_skinning_weights: true,
                        ..HandRuntimeSignals::default()
                    },
                })
                .collect(),
        )
    }

    fn lattice_frame(hand: LatticeHandedness) -> HandJointFrameSnapshot {
        let names = [
            "palm",
            "wrist",
            "thumb_metacarpal",
            "thumb_proximal",
            "thumb_distal",
            "thumb_tip",
            "index_metacarpal",
            "index_proximal",
            "index_intermediate",
            "index_distal",
            "index_tip",
            "middle_metacarpal",
            "middle_proximal",
            "middle_intermediate",
            "middle_distal",
            "middle_tip",
            "ring_metacarpal",
            "ring_proximal",
            "ring_intermediate",
            "ring_distal",
            "ring_tip",
            "little_metacarpal",
            "little_proximal",
            "little_intermediate",
            "little_distal",
            "little_tip",
        ];
        HandJointFrameSnapshot {
            schema: HAND_JOINT_FRAME_SCHEMA_ID.to_owned(),
            frame_id: format!("frame.hand.{}", hand.stable_id()),
            provider_id: "generic-tracked-hand-provider".to_owned(),
            hand,
            joint_set: HandJointSet::OpenXrExtHandTracking26,
            joint_count: 26,
            reference_space: ReferenceSpace::new("local-stage", ReferenceSpaceKind::Stage),
            coordinate_basis: HandCoordinateBasis::RightHandedYUpNegativeZForward,
            timestamp_domain: "predicted_display_time_ns".to_owned(),
            timestamp_ns: 1_000_000_000,
            sequence_id: 1,
            stale_after_ns: 50_000_000,
            joints: names
                .iter()
                .enumerate()
                .map(|(index, name)| HandJointSample {
                    joint_index: index as u16,
                    joint_name: (*name).to_owned(),
                    pose: Pose::new(
                        Vec3::new(index as f32 * 0.001, 0.02 * index as f32, -0.4),
                        Quat::IDENTITY,
                    ),
                    radius_m: Some(0.01),
                    position_valid: true,
                    orientation_valid: true,
                    tracked: true,
                    confidence: 1.0,
                })
                .collect(),
            valid: true,
            confidence: 1.0,
        }
    }

    fn mapping() -> HandJointMappingSnapshot {
        HandJointMappingSnapshot {
            schema: HAND_JOINT_MAPPING_SCHEMA_ID.to_owned(),
            mapping_id: "mapping.openxr-to-test-rig".to_owned(),
            provider_id: "generic-tracked-hand-provider".to_owned(),
            source: "synthetic-test".to_owned(),
            source_joint_set: HandJointSet::OpenXrExtHandTracking26,
            source_joint_count: 26,
            target_schema_id: rusty_matter_mesh::HAND_RIG_CAPTURE_SCHEMA_ID.to_owned(),
            target_joint_count: 4,
            revision: 1,
            entries: [
                (1, "wrist"),
                (0, "palm"),
                (6, "index_metacarpal"),
                (10, "index_tip"),
            ]
            .into_iter()
            .enumerate()
            .map(|(target, (source, name))| HandJointMapEntry {
                source_joint_index: source,
                source_joint_name: name.to_owned(),
                target_joint_index: target as u16,
                target_joint_name: format!("bind.{name}"),
                joint_role: name.to_owned(),
            })
            .collect(),
            valid: true,
            confidence: 1.0,
        }
    }

    fn matter(hand: LatticeHandedness, frame_id: &str) -> HandSubstrateConformance {
        let matter_hand = matter_hand(hand);
        let surface = TriangleMeshSurface::new(
            "mesh.hand.test",
            vec![
                MatterVec3::new(-0.02, 0.0, 0.0),
                MatterVec3::new(0.02, 0.0, 0.0),
                MatterVec3::new(0.02, 0.08, 0.0),
                MatterVec3::new(-0.02, 0.08, 0.0),
            ],
            vec![[0, 1, 2], [0, 2, 3]],
        );
        let mut rig = HandRigCapture::from_bind_surface(
            "rig.hand.test",
            matter_hand,
            "local-stage",
            "generic-tracked-hand-provider",
            surface,
        );
        rig.joint_parent_indices = vec![-1, 0, 1, 2];
        rig.joint_radii_m = vec![0.01; 4];
        rig.joint_bind_poses = (0..4)
            .map(|index| MatterHandJointPose {
                position: MatterVec3::new(0.0, index as f32 * 0.02, 0.0),
                orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
                radius_m: 0.01,
            })
            .collect();
        rig.vertex_joint_indices = vec![[0, 0, 0, 0], [0, 0, 0, 0], [3, 0, 0, 0], [3, 0, 0, 0]];
        rig.vertex_joint_weights = vec![[1.0, 0.0, 0.0, 0.0]; 4];
        let joint_frame = MatterHandJointFrame {
            schema_id: rusty_matter_mesh::HAND_JOINT_FRAME_SCHEMA_ID.to_owned(),
            frame_id: frame_id.to_owned(),
            handedness: matter_hand,
            reference_space: "local-stage".to_owned(),
            source: "generic-tracked-hand-provider".to_owned(),
            time_seconds: 1.0,
            poses: rig.joint_bind_poses.clone(),
            confidence: vec![1.0; 4],
        };
        HandSubstrateConformance {
            schema: rusty_matter_mesh::HAND_SUBSTRATE_SCHEMA_ID.to_owned(),
            conformance_id: "substrate.hand.test".to_owned(),
            provider_id: "generic-tracked-hand-provider".to_owned(),
            lattice_frame_id: frame_id.to_owned(),
            lattice_frame_schema_id: HAND_JOINT_FRAME_SCHEMA_ID.to_owned(),
            coordinate_basis: "right_handed_y_up_negative_z_forward".to_owned(),
            rig,
            joint_frame,
        }
    }

    fn optics(hand: LatticeHandedness, frame_id: &str) -> HandSubstrateVisualProfile {
        HandSubstrateVisualProfile {
            schema: HAND_SUBSTRATE_VISUAL_PROFILE_SCHEMA_ID.to_owned(),
            profile_id: "visual.hand.test".to_owned(),
            provider_id: "generic-tracked-hand-provider".to_owned(),
            lattice_frame_id: frame_id.to_owned(),
            matter_rig_id: "rig.hand.test".to_owned(),
            hand: visual_hand(hand),
            lattice_frame_schema_id: HAND_JOINT_FRAME_SCHEMA_ID.to_owned(),
            matter_rig_schema_id: rusty_matter_mesh::HAND_RIG_CAPTURE_SCHEMA_ID.to_owned(),
            visual_intent: "neutral-hand".to_owned(),
            surface_color: ColorRgba::new(0.8, 0.8, 0.8, 1.0),
            wireframe: true,
            opacity: 0.8,
        }
    }

    #[test]
    fn both_hands_preserve_identity_and_cpu_prepared_parity() {
        for hand in [LatticeHandedness::Left, LatticeHandedness::Right] {
            let frame = lattice_frame(hand);
            let (prepared, receipt) = prepare_hand_frame(
                &descriptor("test-consumer", true),
                &capability(),
                &frame,
                &mapping(),
                &matter(hand, &frame.frame_id),
                &optics(hand, &frame.frame_id),
            )
            .unwrap();
            assert_eq!(prepared.hand, hand);
            assert_eq!(prepared.rows.len(), 4);
            assert!(receipt.identity_preserved && receipt.parity_passed);
        }
    }

    #[test]
    fn provider_basis_hand_and_mapping_substitution_fail_closed() {
        let frame = lattice_frame(LatticeHandedness::Left);
        let mut wrong_provider = descriptor("test-consumer", true);
        wrong_provider.provider_id = "substitute-provider".to_owned();
        assert!(matches!(
            prepare_hand_frame(
                &wrong_provider,
                &capability(),
                &frame,
                &mapping(),
                &matter(LatticeHandedness::Left, &frame.frame_id),
                &optics(LatticeHandedness::Left, &frame.frame_id)
            ),
            Err(HandAdapterError::IdentityMismatch("provider"))
        ));

        let mut wrong_basis = descriptor("test-consumer", true);
        wrong_basis.coordinate_basis = HandCoordinateBasis::RightHandedYUpPositiveZForward;
        assert!(matches!(
            prepare_hand_frame(
                &wrong_basis,
                &capability(),
                &frame,
                &mapping(),
                &matter(LatticeHandedness::Left, &frame.frame_id),
                &optics(LatticeHandedness::Left, &frame.frame_id)
            ),
            Err(HandAdapterError::IdentityMismatch("coordinate basis"))
        ));

        let mut wrong_hand = optics(LatticeHandedness::Left, &frame.frame_id);
        wrong_hand.hand = HandVisualSide::Right;
        assert!(matches!(
            prepare_hand_frame(
                &descriptor("test-consumer", true),
                &capability(),
                &frame,
                &mapping(),
                &matter(LatticeHandedness::Left, &frame.frame_id),
                &wrong_hand
            ),
            Err(HandAdapterError::IdentityMismatch("handedness"))
        ));

        let mut duplicate_target = mapping();
        duplicate_target.entries[1].target_joint_index = 0;
        assert!(matches!(
            prepare_hand_frame(
                &descriptor("test-consumer", true),
                &capability(),
                &frame,
                &duplicate_target,
                &matter(LatticeHandedness::Left, &frame.frame_id),
                &optics(LatticeHandedness::Left, &frame.frame_id)
            ),
            Err(HandAdapterError::IdentityMismatch("duplicate target joint"))
        ));
    }

    #[test]
    fn disabled_descriptor_is_inert_and_unknown_fields_reject() {
        let descriptor = descriptor("test-consumer", false);
        let receipt = disabled_receipt(&descriptor).unwrap();
        assert_eq!(receipt.row_count, 0);
        assert!(!receipt.enabled);
        let json = serde_json::to_string(&descriptor).unwrap();
        let damaged = json.replace(
            "\"enabled\":false",
            "\"enabled\":false,\"platform_handle\":9",
        );
        assert!(serde_json::from_str::<HandAdapterDescriptor>(&damaged).is_err());
    }
}
