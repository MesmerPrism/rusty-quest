//! Recorded Meta/OpenXR hand-mesh replay source for the native renderer.
//!
//! The committed fallback is a public topology/shape fixture. Local builds may
//! embed a generated source bundle from an external capture by setting
//! `RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR`; that generated bundle stays
//! under Cargo `OUT_DIR` and is not committed.

const RECORDED_HAND_REPLAY_JSON: &str = include_str!(concat!(
    env!("OUT_DIR"),
    "/recorded_hand_replay_source.json"
));

#[derive(Clone, Debug)]
pub(crate) struct RecordedHandReplaySummary {
    pub(crate) source_id: String,
    pub(crate) source_kind: String,
    pub(crate) handedness: String,
    pub(crate) reference_space: String,
    pub(crate) runtime_provider: String,
    pub(crate) topology_key: String,
    pub(crate) frame_count: u64,
    pub(crate) validation_frame_count: u64,
    pub(crate) vertex_count: u64,
    pub(crate) triangle_count: u64,
    pub(crate) index_count: u64,
    pub(crate) bind_joint_count: u64,
    pub(crate) runtime_joint_count: u64,
    pub(crate) tip_length_count: u64,
    pub(crate) has_bind_mesh_payload: bool,
    pub(crate) bind_vertices: Vec<[f32; 4]>,
    pub(crate) skinning_vertices: Vec<RecordedHandGpuSkinningVertex>,
    pub(crate) skinning_triangles: Vec<[u32; 4]>,
    pub(crate) bind_joint_poses: Vec<RecordedHandGpuPose>,
    pub(crate) bind_joint_source_rows: Vec<[u32; 4]>,
    pub(crate) skinning_frames: Vec<RecordedHandSkinningFrame>,
    pub(crate) mesh_target_transform: Option<RecordedMeshTargetTransform>,
    pub(crate) mesh_component_summary: MeshComponentSummary,
    pub(crate) mesh_visual_frames: Vec<RecordedHandMeshVisualFrame>,
    visual_frames: Vec<RecordedHandVisualFrame>,
}

#[derive(Clone, Debug)]
pub(crate) struct RecordedHandReplaySet {
    pub(crate) left: RecordedHandReplaySummary,
    pub(crate) right: RecordedHandReplaySummary,
    pub(crate) right_hand_distinct: bool,
}

#[derive(Clone, Debug)]
pub(crate) struct RecordedHandVisualFrame {
    pub(crate) frame_index: u32,
    pub(crate) timestamp_ns: u64,
    pub(crate) normalized_points: Vec<[f32; 2]>,
}

#[derive(Clone, Debug)]
pub(crate) struct RecordedHandMeshVisualFrame {
    pub(crate) expanded_vertices: Vec<[f32; 4]>,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct RecordedHandGpuSkinningVertex {
    pub(crate) bind_position: [f32; 4],
    pub(crate) joint_indices: [u32; 4],
    pub(crate) joint_weights: [f32; 4],
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct RecordedHandGpuPose {
    pub(crate) translation_pad: [f32; 4],
    pub(crate) rotation_xyzw: [f32; 4],
}

#[derive(Clone, Debug)]
pub(crate) struct RecordedHandSkinningFrame {
    pub(crate) frame_index: u32,
    pub(crate) timestamp_ns: u64,
    pub(crate) runtime_joint_poses: Vec<RecordedHandGpuPose>,
    pub(crate) tip_length_rows: Vec<[f32; 4]>,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct RecordedMeshTargetTransform {
    pub(crate) center: [f32; 3],
    pub(crate) radius: f32,
    pub(crate) min_z: f32,
    pub(crate) depth: f32,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct MeshComponentSummary {
    pub(crate) component_count: u64,
    pub(crate) vertex_counts: Vec<u64>,
    pub(crate) triangle_counts: Vec<u64>,
}

impl RecordedHandReplaySummary {
    pub(crate) fn load() -> Result<Self, String> {
        Self::from_json_str_for_hand(RECORDED_HAND_REPLAY_JSON, "left")
    }

    fn from_json_str(json: &str) -> Result<Self, String> {
        Self::from_json_str_for_hand(json, "left")
    }

    fn from_json_str_for_hand(json: &str, handedness: &str) -> Result<Self, String> {
        let value: serde_json::Value = serde_json::from_str(json)
            .map_err(|error| format!("parse recorded hand replay source JSON: {error}"))?;
        let hands = source_hands(&value)?;
        let hand = hand_by_handedness(hands, handedness)
            .or_else(|| hands.first())
            .ok_or_else(|| "recorded hand replay source has no hands".to_string())?;
        Self::from_json_value_hand(&value, hand)
    }

    fn from_json_value_hand(
        value: &serde_json::Value,
        hand: &serde_json::Value,
    ) -> Result<Self, String> {
        let source_id = required_text(value, "source_id")?;
        let source_kind = required_text(value, "source_kind")?;

        let parsed =
            if let Some(rig_json) = hand.get("rig_json").and_then(serde_json::Value::as_str) {
                parse_embedded_capture_hand(hand, rig_json)?
            } else {
                parse_public_shape_hand(hand)?
            };

        if parsed.vertex_count == 0 || parsed.triangle_count == 0 || parsed.index_count == 0 {
            return Err("recorded hand replay source has incomplete topology".to_string());
        }
        if parsed.bind_joint_count == 0 || parsed.runtime_joint_count == 0 {
            return Err("recorded hand replay source has incomplete joint metadata".to_string());
        }
        if parsed.visual_frames.is_empty() {
            return Err("recorded hand replay source has no visual frames".to_string());
        }

        Ok(Self {
            source_id,
            source_kind,
            handedness: parsed.handedness,
            reference_space: parsed.reference_space,
            runtime_provider: parsed.runtime_provider,
            topology_key: parsed.topology_key,
            frame_count: parsed.frame_count,
            validation_frame_count: parsed.validation_frame_count,
            vertex_count: parsed.vertex_count,
            triangle_count: parsed.triangle_count,
            index_count: parsed.index_count,
            bind_joint_count: parsed.bind_joint_count,
            runtime_joint_count: parsed.runtime_joint_count,
            tip_length_count: parsed.tip_length_count,
            has_bind_mesh_payload: parsed.has_bind_mesh_payload,
            bind_vertices: parsed.bind_vertices,
            skinning_vertices: parsed.skinning_vertices,
            skinning_triangles: parsed.skinning_triangles,
            bind_joint_poses: parsed.bind_joint_poses,
            bind_joint_source_rows: parsed.bind_joint_source_rows,
            skinning_frames: parsed.skinning_frames,
            mesh_target_transform: parsed.mesh_target_transform,
            mesh_component_summary: parsed.mesh_component_summary,
            mesh_visual_frames: parsed.mesh_visual_frames,
            visual_frames: parsed.visual_frames,
        })
    }

    pub(crate) fn frame_for_count(&self, frame_count: u64) -> &RecordedHandVisualFrame {
        let index = if self.visual_frames.is_empty() {
            0
        } else {
            (frame_count / 18 % self.visual_frames.len() as u64) as usize
        };
        &self.visual_frames[index]
    }

    pub(crate) fn mesh_visual_vertex_capacity(&self) -> usize {
        self.mesh_visual_frames
            .iter()
            .map(|frame| frame.expanded_vertices.len())
            .max()
            .unwrap_or(0)
    }

    pub(crate) fn skinning_frame_for_count(
        &self,
        frame_count: u64,
    ) -> Option<&RecordedHandSkinningFrame> {
        if self.skinning_frames.is_empty() {
            return None;
        }
        let index = (frame_count / 6 % self.skinning_frames.len() as u64) as usize;
        self.skinning_frames.get(index)
    }

    pub(crate) fn has_gpu_skinning_sdf_payload(&self) -> bool {
        !self.skinning_vertices.is_empty()
            && !self.skinning_triangles.is_empty()
            && !self.bind_joint_poses.is_empty()
            && !self.bind_joint_source_rows.is_empty()
            && !self.skinning_frames.is_empty()
            && self.mesh_target_transform.is_some()
    }

    pub(crate) fn source_vertex_buffer_bytes(&self) -> u64 {
        self.bind_vertices.len() as u64 * 16
    }

    pub(crate) fn skinning_source_vertex_buffer_bytes(&self) -> u64 {
        self.skinning_vertices.len() as u64
            * std::mem::size_of::<RecordedHandGpuSkinningVertex>() as u64
    }

    pub(crate) fn skinning_triangle_buffer_bytes(&self) -> u64 {
        self.skinning_triangles.len() as u64 * std::mem::size_of::<[u32; 4]>() as u64
    }

    pub(crate) fn bind_joint_pose_buffer_bytes(&self) -> u64 {
        self.bind_joint_poses.len() as u64 * std::mem::size_of::<RecordedHandGpuPose>() as u64
    }

    pub(crate) fn bind_joint_source_buffer_bytes(&self) -> u64 {
        self.bind_joint_source_rows.len() as u64 * std::mem::size_of::<[u32; 4]>() as u64
    }

    pub(crate) fn runtime_joint_pose_frame_buffer_bytes(&self) -> u64 {
        self.skinning_frames.first().map_or(0, |frame| {
            frame.runtime_joint_poses.len() as u64
                * std::mem::size_of::<RecordedHandGpuPose>() as u64
        })
    }

    pub(crate) fn tip_length_frame_buffer_bytes(&self) -> u64 {
        self.skinning_frames
            .first()
            .map_or(0, |frame| frame.tip_length_rows.len() as u64 * 16)
    }

    pub(crate) fn compact_joint_frame_buffer_bytes(&self) -> u64 {
        self.runtime_joint_pose_frame_buffer_bytes()
            .saturating_add(self.tip_length_frame_buffer_bytes())
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "recordedHandReplaySource={} recordedHandReplaySourceKind={} recordedInputEquivalent=true validationInputShape=bind-mesh-plus-compact-joint-frame replayModes=recorded-mesh-validation-frames,recorded-joints-skin-live recordedMeshReplayMode=validation_mesh_jsonl recordedJointReplayMode=clip_jsonl-compact-joint-skinning handedness={} referenceSpace={} runtimeProvider={} topologyKey={} bindJointCount={} runtimeJointCount={} tipLengthCount={} topologyVertexCount={} topologyTriangleCount={} topologyIndexCount={} clipFrameCount={} validationFrameCount={} hasBindMeshPayload={} visualFrameCount={} sourceVertexBufferBytes={} meshVisualFrameCount={} meshVisualExpandedVertexCapacity={} gpuSkinningPayloadReady={} skinningFrameCount={} skinningSourceVertexBufferBytes={} skinningTriangleBufferBytes={} bindJointPoseBufferBytes={} bindJointSourceBufferBytes={} runtimeJointPoseFrameBufferBytes={} tipLengthFrameBufferBytes={} compactJointFrameBufferBytes={} jointMatrixFrameBufferBytes=0 skinningRuntimePosesPerFrame={} tipLengthRowsPerFrame={} jointMatrixUploadPerFrame=false compactJointPoseUploadPerFrame=true meshComponentCount={} meshComponentVertexCounts={} meshComponentTriangleCounts={} meshComponentRank0=hand-inside meshComponentRank1=hand-back meshComponentRank2=wrist-cap",
            crate::sanitize(&self.source_id),
            crate::sanitize(&self.source_kind),
            crate::sanitize(&self.handedness),
            crate::sanitize(&self.reference_space),
            crate::sanitize(&self.runtime_provider),
            crate::sanitize(&self.topology_key),
            self.bind_joint_count,
            self.runtime_joint_count,
            self.tip_length_count,
            self.vertex_count,
            self.triangle_count,
            self.index_count,
            self.frame_count,
            self.validation_frame_count,
            self.has_bind_mesh_payload,
            self.visual_frames.len(),
            self.source_vertex_buffer_bytes(),
            self.mesh_visual_frames.len(),
            self.mesh_visual_vertex_capacity(),
            self.has_gpu_skinning_sdf_payload(),
            self.skinning_frames.len(),
            self.skinning_source_vertex_buffer_bytes(),
            self.skinning_triangle_buffer_bytes(),
            self.bind_joint_pose_buffer_bytes(),
            self.bind_joint_source_buffer_bytes(),
            self.runtime_joint_pose_frame_buffer_bytes(),
            self.tip_length_frame_buffer_bytes(),
            self.compact_joint_frame_buffer_bytes(),
            self.skinning_frames
                .first()
                .map_or(0, |frame| frame.runtime_joint_poses.len()),
            self.skinning_frames
                .first()
                .map_or(0, |frame| frame.tip_length_rows.len()),
            self.mesh_component_summary.component_count,
            join_u64(&self.mesh_component_summary.vertex_counts),
            join_u64(&self.mesh_component_summary.triangle_counts),
        )
    }
}

impl RecordedHandReplaySet {
    pub(crate) fn load() -> Result<Self, String> {
        Self::from_json_str(RECORDED_HAND_REPLAY_JSON)
    }

    fn from_json_str(json: &str) -> Result<Self, String> {
        let value: serde_json::Value = serde_json::from_str(json)
            .map_err(|error| format!("parse recorded hand replay source JSON: {error}"))?;
        let hands = source_hands(&value)?;
        let left_hand = hand_by_handedness(hands, "left")
            .or_else(|| hands.first())
            .ok_or_else(|| "recorded hand replay source has no hands".to_string())?;
        let left = RecordedHandReplaySummary::from_json_value_hand(&value, left_hand)?;
        let right = hand_by_handedness(hands, "right")
            .map(|hand| RecordedHandReplaySummary::from_json_value_hand(&value, hand))
            .transpose()?;
        let right_hand_distinct = right.is_some();
        let right = right.unwrap_or_else(|| left.clone());
        Ok(Self {
            left,
            right,
            right_hand_distinct,
        })
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "recordedHandReplayHandSetReady=true recordedHandReplayRightHandDistinct={} recordedHandReplayLeftHandedness={} recordedHandReplayRightHandedness={} recordedHandReplayLeftGpuSkinningPayloadReady={} recordedHandReplayRightGpuSkinningPayloadReady={} recordedHandReplayRightTopologyVertexCount={} recordedHandReplayRightTopologyTriangleCount={} recordedHandReplayRightMeshComponentCount={}",
            self.right_hand_distinct,
            crate::sanitize(&self.left.handedness),
            crate::sanitize(&self.right.handedness),
            self.left.has_gpu_skinning_sdf_payload(),
            self.right.has_gpu_skinning_sdf_payload(),
            self.right.vertex_count,
            self.right.triangle_count,
            self.right.mesh_component_summary.component_count,
        )
    }
}

struct ParsedHand {
    handedness: String,
    reference_space: String,
    runtime_provider: String,
    topology_key: String,
    frame_count: u64,
    validation_frame_count: u64,
    vertex_count: u64,
    triangle_count: u64,
    index_count: u64,
    bind_joint_count: u64,
    runtime_joint_count: u64,
    tip_length_count: u64,
    has_bind_mesh_payload: bool,
    bind_vertices: Vec<[f32; 4]>,
    skinning_vertices: Vec<RecordedHandGpuSkinningVertex>,
    skinning_triangles: Vec<[u32; 4]>,
    bind_joint_poses: Vec<RecordedHandGpuPose>,
    bind_joint_source_rows: Vec<[u32; 4]>,
    skinning_frames: Vec<RecordedHandSkinningFrame>,
    mesh_target_transform: Option<RecordedMeshTargetTransform>,
    mesh_component_summary: MeshComponentSummary,
    mesh_visual_frames: Vec<RecordedHandMeshVisualFrame>,
    visual_frames: Vec<RecordedHandVisualFrame>,
}

fn parse_public_shape_hand(hand: &serde_json::Value) -> Result<ParsedHand, String> {
    let visual_frames = parse_visual_frames(hand)?;
    Ok(ParsedHand {
        handedness: required_text(hand, "handedness")?,
        reference_space: required_text(hand, "reference_space")?,
        runtime_provider: required_text(hand, "runtime_provider")?,
        topology_key: required_text(hand, "topology_key")?,
        frame_count: required_u64(hand, "clip_frame_count")?,
        validation_frame_count: required_u64(hand, "validation_frame_count")?,
        vertex_count: required_u64(hand, "topology_vertex_count")?,
        triangle_count: required_u64(hand, "topology_triangle_count")?,
        index_count: required_u64(hand, "topology_index_count")?,
        bind_joint_count: required_u64(hand, "bind_joint_count")?,
        runtime_joint_count: required_u64(hand, "runtime_joint_count")?,
        tip_length_count: required_u64(hand, "tip_length_count")?,
        has_bind_mesh_payload: bool_field(hand, "has_bind_mesh_payload"),
        bind_vertices: Vec::new(),
        skinning_vertices: Vec::new(),
        skinning_triangles: Vec::new(),
        bind_joint_poses: Vec::new(),
        bind_joint_source_rows: Vec::new(),
        skinning_frames: Vec::new(),
        mesh_target_transform: None,
        mesh_component_summary: MeshComponentSummary::default(),
        mesh_visual_frames: Vec::new(),
        visual_frames,
    })
}

fn parse_embedded_capture_hand(
    hand: &serde_json::Value,
    rig_json: &str,
) -> Result<ParsedHand, String> {
    let rig: serde_json::Value = serde_json::from_str(rig_json)
        .map_err(|error| format!("parse embedded hand rig: {error}"))?;
    let handedness = required_text(&rig, "handedness")?;
    let reference_space = required_text(&rig, "reference_space")?;
    let topology_key = required_text(&rig, "topology_key")?;
    let runtime_joint_set = rig
        .get("runtime_joint_set")
        .ok_or_else(|| "recorded hand rig missing runtime_joint_set".to_string())?;
    let runtime_provider = required_text(runtime_joint_set, "provider")?;
    let bind_joint_count = array_len(&rig, "joints")?;
    let runtime_joint_count = required_u64(runtime_joint_set, "joint_count")?;
    let tip_length_count = required_u64(runtime_joint_set, "tip_length_count")?;
    let bind_vertices = parse_bind_vertices(&rig)?;
    let triangles = parse_triangles(&rig)?;
    let bind_joint_poses = parse_bind_joint_poses(&rig)?;
    let bind_joint_sources = parse_bind_joint_sources(runtime_joint_set)?;
    let blend_indices = parse_blend_indices(&rig)?;
    let blend_weights = parse_blend_weights(&rig)?;
    let (triangle_component_ranks, mesh_component_summary) =
        analyze_mesh_components(bind_vertices.len(), &triangles)?;
    let (mesh_visual_frames, mesh_target_transform) =
        parse_validation_mesh_visual_frames(hand, &triangles, &triangle_component_ranks)?;
    let skinning_vertices =
        build_skinning_vertices(&bind_vertices, &blend_indices, &blend_weights)?;
    let skinning_triangles = triangles
        .iter()
        .copied()
        .enumerate()
        .map(|(index, [a, b, c])| [a, b, c, *triangle_component_ranks.get(index).unwrap_or(&0)])
        .collect::<Vec<_>>();
    let bind_joint_pose_rows = bind_joint_poses
        .iter()
        .copied()
        .map(RecordedHandGpuPose::from)
        .collect::<Vec<_>>();
    let bind_joint_source_rows =
        build_bind_joint_source_rows(bind_joint_pose_rows.len(), &bind_joint_sources)?;
    let skinning_frames = parse_skinning_frames(hand, runtime_joint_count as usize)?;
    let triangle_count = triangles.len() as u64;
    let visual_frames = parse_clip_visual_frames(hand)?;
    Ok(ParsedHand {
        handedness,
        reference_space,
        runtime_provider,
        topology_key,
        frame_count: hand
            .get("clip_frame_count")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(visual_frames.len() as u64),
        validation_frame_count: hand
            .get("validation_frame_count")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0),
        vertex_count: bind_vertices.len() as u64,
        triangle_count,
        index_count: triangle_count.saturating_mul(3),
        bind_joint_count,
        runtime_joint_count,
        tip_length_count,
        has_bind_mesh_payload: !bind_vertices.is_empty(),
        bind_vertices,
        skinning_vertices,
        skinning_triangles,
        bind_joint_poses: bind_joint_pose_rows,
        bind_joint_source_rows,
        skinning_frames,
        mesh_target_transform,
        mesh_component_summary,
        mesh_visual_frames,
        visual_frames,
    })
}

fn parse_visual_frames(hand: &serde_json::Value) -> Result<Vec<RecordedHandVisualFrame>, String> {
    let frames = hand
        .get("visual_frames")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand shape missing visual_frames".to_string())?;
    frames
        .iter()
        .map(|frame| {
            Ok(RecordedHandVisualFrame {
                frame_index: required_u64(frame, "frame_index")? as u32,
                timestamp_ns: required_u64(frame, "timestamp_ns")?,
                normalized_points: parse_normalized_points(frame)?,
            })
        })
        .collect()
}

fn parse_clip_visual_frames(
    hand: &serde_json::Value,
) -> Result<Vec<RecordedHandVisualFrame>, String> {
    let lines = hand
        .get("clip_jsonl")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "embedded recorded hand source missing clip_jsonl".to_string())?;
    let mut frames = Vec::new();
    for line in lines.iter().filter_map(serde_json::Value::as_str) {
        let row: serde_json::Value = serde_json::from_str(line)
            .map_err(|error| format!("parse embedded recorded hand clip row: {error}"))?;
        let points = row
            .get("joints")
            .and_then(serde_json::Value::as_array)
            .ok_or_else(|| "recorded hand clip row missing joints".to_string())?
            .iter()
            .filter_map(|joint| joint.get("pose"))
            .filter_map(|pose| pose.get("translation"))
            .filter_map(parse_vec3)
            .collect::<Vec<_>>();
        if points.is_empty() {
            continue;
        }
        frames.push(RecordedHandVisualFrame {
            frame_index: required_u64(&row, "frame_index")? as u32,
            timestamp_ns: required_u64(&row, "timestamp_ns")?,
            normalized_points: normalize_xy_points(&points),
        });
    }
    if frames.is_empty() {
        return Err("embedded recorded hand clip did not produce visual frames".to_string());
    }
    Ok(frames)
}

fn parse_normalized_points(frame: &serde_json::Value) -> Result<Vec<[f32; 2]>, String> {
    let points = frame
        .get("normalized_points")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand visual frame missing normalized_points".to_string())?;
    let mut result = Vec::with_capacity(points.len());
    for point in points {
        let values = point
            .as_array()
            .filter(|values| values.len() == 2)
            .ok_or_else(|| "normalized point must be [x,y]".to_string())?;
        let x = f32_value(&values[0], "normalized_point.x")?.clamp(0.0, 1.0);
        let y = f32_value(&values[1], "normalized_point.y")?.clamp(0.0, 1.0);
        result.push([x, y]);
    }
    Ok(result)
}

fn parse_bind_vertices(rig: &serde_json::Value) -> Result<Vec<[f32; 4]>, String> {
    let vertices = rig
        .get("bind_vertices")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing bind_vertices".to_string())?;
    vertices
        .iter()
        .map(|value| {
            let [x, y, z] = parse_vec3(value).ok_or_else(|| "invalid bind vertex".to_string())?;
            Ok([x, y, z, 1.0])
        })
        .collect()
}

fn parse_bind_joint_poses(rig: &serde_json::Value) -> Result<Vec<RecordedHandPose>, String> {
    let joints = rig
        .get("joints")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing joints".to_string())?;
    let mut poses = Vec::with_capacity(joints.len());
    for joint in joints {
        let pose = joint
            .get("bind_pose")
            .ok_or_else(|| "recorded hand joint missing bind_pose".to_string())?;
        poses.push(parse_pose(pose)?);
    }
    Ok(poses)
}

fn parse_triangles(rig: &serde_json::Value) -> Result<Vec<[u32; 3]>, String> {
    let triangles = rig
        .get("triangle_indices")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing triangle_indices".to_string())?;
    triangles
        .iter()
        .map(|value| {
            let values = value
                .as_array()
                .filter(|values| values.len() == 3)
                .ok_or_else(|| "triangle_indices entry must be [a,b,c]".to_string())?;
            Ok([
                u32_value(&values[0], "triangle.a")?,
                u32_value(&values[1], "triangle.b")?,
                u32_value(&values[2], "triangle.c")?,
            ])
        })
        .collect()
}

fn parse_blend_indices(rig: &serde_json::Value) -> Result<Vec<[u32; 4]>, String> {
    let values = rig
        .get("vertex_blend_indices")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing vertex_blend_indices".to_string())?;
    values
        .iter()
        .map(|value| {
            let values = value
                .as_array()
                .filter(|values| values.len() == 4)
                .ok_or_else(|| "vertex_blend_indices entry must be [a,b,c,d]".to_string())?;
            Ok([
                u32_value(&values[0], "blend_indices.0")?,
                u32_value(&values[1], "blend_indices.1")?,
                u32_value(&values[2], "blend_indices.2")?,
                u32_value(&values[3], "blend_indices.3")?,
            ])
        })
        .collect()
}

fn parse_blend_weights(rig: &serde_json::Value) -> Result<Vec<[f32; 4]>, String> {
    let values = rig
        .get("vertex_blend_weights")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand rig missing vertex_blend_weights".to_string())?;
    values
        .iter()
        .map(|value| {
            let values = value
                .as_array()
                .filter(|values| values.len() == 4)
                .ok_or_else(|| "vertex_blend_weights entry must be [a,b,c,d]".to_string())?;
            Ok([
                f32_value(&values[0], "blend_weights.0")?,
                f32_value(&values[1], "blend_weights.1")?,
                f32_value(&values[2], "blend_weights.2")?,
                f32_value(&values[3], "blend_weights.3")?,
            ])
        })
        .collect()
}

fn build_skinning_vertices(
    bind_vertices: &[[f32; 4]],
    blend_indices: &[[u32; 4]],
    blend_weights: &[[f32; 4]],
) -> Result<Vec<RecordedHandGpuSkinningVertex>, String> {
    if bind_vertices.len() != blend_indices.len() || bind_vertices.len() != blend_weights.len() {
        return Err("recorded hand skinning metadata must match vertex count".to_string());
    }
    Ok(bind_vertices
        .iter()
        .copied()
        .zip(blend_indices.iter().copied())
        .zip(blend_weights.iter().copied())
        .map(
            |((bind_position, joint_indices), joint_weights)| RecordedHandGpuSkinningVertex {
                bind_position,
                joint_indices,
                joint_weights,
            },
        )
        .collect())
}

fn parse_validation_mesh_visual_frames(
    hand: &serde_json::Value,
    triangles: &[[u32; 3]],
    triangle_component_ranks: &[u32],
) -> Result<
    (
        Vec<RecordedHandMeshVisualFrame>,
        Option<RecordedMeshTargetTransform>,
    ),
    String,
> {
    let Some(lines) = hand
        .get("validation_mesh_jsonl")
        .and_then(serde_json::Value::as_array)
    else {
        return Ok((Vec::new(), None));
    };
    let mut raw_frames = Vec::new();
    for line in lines.iter().filter_map(serde_json::Value::as_str) {
        let row: serde_json::Value = serde_json::from_str(line)
            .map_err(|error| format!("parse embedded validation mesh row: {error}"))?;
        let vertices = row
            .get("vertices")
            .and_then(serde_json::Value::as_array)
            .ok_or_else(|| "validation mesh row missing vertices".to_string())?
            .iter()
            .map(|value| {
                parse_vec3(value).ok_or_else(|| "invalid validation mesh vertex".to_string())
            })
            .collect::<Result<Vec<_>, _>>()?;
        if vertices.is_empty() {
            continue;
        }
        raw_frames.push(RawValidationMeshFrame { vertices });
    }
    if raw_frames.is_empty() {
        return Ok((Vec::new(), None));
    }

    let bounds = MeshVisualBounds::from_frames(&raw_frames)?;
    let frames = raw_frames
        .iter()
        .map(|frame| {
            let mut expanded_vertices = Vec::with_capacity(triangles.len() * 3);
            for (triangle_index, triangle) in triangles.iter().copied().enumerate() {
                let component = *triangle_component_ranks.get(triangle_index).unwrap_or(&0);
                for vertex_index in triangle {
                    let position = frame.vertices.get(vertex_index as usize).ok_or_else(|| {
                        "validation mesh triangle index outside vertices".to_string()
                    })?;
                    expanded_vertices.push(bounds.target_vertex(*position, component));
                }
            }
            Ok::<RecordedHandMeshVisualFrame, String>(RecordedHandMeshVisualFrame {
                expanded_vertices,
            })
        })
        .collect::<Result<Vec<_>, _>>()?;
    Ok((frames, Some(bounds.target_transform())))
}

struct RawValidationMeshFrame {
    vertices: Vec<[f32; 3]>,
}

#[derive(Clone, Copy, Debug)]
struct RecordedHandPose {
    translation: [f32; 3],
    rotation_xyzw: [f32; 4],
}

impl From<RecordedHandPose> for RecordedHandGpuPose {
    fn from(pose: RecordedHandPose) -> Self {
        Self {
            translation_pad: [
                pose.translation[0],
                pose.translation[1],
                pose.translation[2],
                0.0,
            ],
            rotation_xyzw: pose.rotation_xyzw,
        }
    }
}

#[derive(Clone, Debug)]
struct RecordedBindJointSource {
    bind_joint_index: usize,
    source: RecordedBindJointSourceKind,
}

#[derive(Clone, Debug)]
enum RecordedBindJointSourceKind {
    RuntimePose {
        runtime_joint_index: usize,
    },
    TipLengthFromParentPose {
        tip_length_index: usize,
        parent_runtime_joint_index: usize,
    },
}

struct MeshVisualBounds {
    cx: f32,
    cy: f32,
    cz: f32,
    radius: f32,
    min_z: f32,
    depth: f32,
}

impl MeshVisualBounds {
    fn from_frames(frames: &[RawValidationMeshFrame]) -> Result<Self, String> {
        let mut min = [f32::INFINITY; 3];
        let mut max = [f32::NEG_INFINITY; 3];
        for frame in frames {
            for [x, y, z] in frame.vertices.iter().copied() {
                min[0] = min[0].min(x);
                min[1] = min[1].min(y);
                min[2] = min[2].min(z);
                max[0] = max[0].max(x);
                max[1] = max[1].max(y);
                max[2] = max[2].max(z);
            }
        }
        if !min.iter().all(|value| value.is_finite()) || !max.iter().all(|value| value.is_finite())
        {
            return Err("validation mesh frames have no finite bounds".to_string());
        }
        let padding = 0.02_f32;
        let width = (max[0] - min[0] + padding * 2.0).max(0.08);
        let height = (max[1] - min[1] + padding * 2.0).max(0.08);
        let depth = (max[2] - min[2] + padding * 2.0).max(0.08);
        Ok(Self {
            cx: (min[0] + max[0]) * 0.5,
            cy: (min[1] + max[1]) * 0.5,
            cz: (min[2] + max[2]) * 0.5,
            radius: width.max(height).max(depth) * 0.5,
            min_z: min[2] - padding,
            depth,
        })
    }

    fn target_vertex(&self, [x, y, z]: [f32; 3], component: u32) -> [f32; 4] {
        let local_x = (x - self.cx) / self.radius;
        let local_y = (y - self.cy) / self.radius;
        let _local_z = (z - self.cz) / self.radius;
        let target_x = 0.5 + local_x * 0.44;
        let target_y = 0.55 - local_y * 0.44;
        let target_z = ((z - self.min_z) / self.depth).clamp(0.0, 1.0);
        [target_x, target_y, target_z, component as f32]
    }

    fn target_transform(&self) -> RecordedMeshTargetTransform {
        RecordedMeshTargetTransform {
            center: [self.cx, self.cy, self.cz],
            radius: self.radius,
            min_z: self.min_z,
            depth: self.depth,
        }
    }
}

fn parse_bind_joint_sources(
    runtime_joint_set: &serde_json::Value,
) -> Result<Vec<RecordedBindJointSource>, String> {
    let values = runtime_joint_set
        .get("bind_joint_sources")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand runtime_joint_set missing bind_joint_sources".to_string())?;
    let mut sources = Vec::with_capacity(values.len());
    for value in values {
        let bind_joint_index = required_u64(value, "bind_joint_index")? as usize;
        let source_kind = required_text(value, "source_kind")?;
        let source = match source_kind.as_str() {
            "runtime_pose" => RecordedBindJointSourceKind::RuntimePose {
                runtime_joint_index: optional_usize(value, "runtime_joint_index")
                    .ok_or_else(|| "runtime_pose source missing runtime_joint_index".to_string())?,
            },
            "tip_length_from_parent_pose" => RecordedBindJointSourceKind::TipLengthFromParentPose {
                tip_length_index: optional_usize(value, "tip_length_index")
                    .ok_or_else(|| "tip source missing tip_length_index".to_string())?,
                parent_runtime_joint_index: optional_usize(value, "parent_runtime_joint_index")
                    .ok_or_else(|| "tip source missing parent_runtime_joint_index".to_string())?,
            },
            _ => return Err(format!("unsupported bind joint source kind {source_kind}")),
        };
        sources.push(RecordedBindJointSource {
            bind_joint_index,
            source,
        });
    }
    Ok(sources)
}

fn build_bind_joint_source_rows(
    bind_joint_count: usize,
    bind_joint_sources: &[RecordedBindJointSource],
) -> Result<Vec<[u32; 4]>, String> {
    let mut rows = vec![None; bind_joint_count];
    for source in bind_joint_sources {
        let row = match source.source {
            RecordedBindJointSourceKind::RuntimePose {
                runtime_joint_index,
            } => [
                0,
                checked_u32(runtime_joint_index, "runtime_joint_index")?,
                0,
                0,
            ],
            RecordedBindJointSourceKind::TipLengthFromParentPose {
                tip_length_index,
                parent_runtime_joint_index,
            } => [
                1,
                0,
                checked_u32(tip_length_index, "tip_length_index")?,
                checked_u32(parent_runtime_joint_index, "parent_runtime_joint_index")?,
            ],
        };
        let slot = rows
            .get_mut(source.bind_joint_index)
            .ok_or_else(|| "bind joint source index outside bind pose array".to_string())?;
        if slot.is_some() {
            return Err("duplicate bind joint source row".to_string());
        }
        *slot = Some(row);
    }
    rows.into_iter()
        .collect::<Option<Vec<_>>>()
        .ok_or_else(|| "bind joint sources did not populate every bind pose".to_string())
}

fn parse_skinning_frames(
    hand: &serde_json::Value,
    runtime_joint_count: usize,
) -> Result<Vec<RecordedHandSkinningFrame>, String> {
    let lines = hand
        .get("clip_jsonl")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "embedded recorded hand source missing clip_jsonl".to_string())?;
    let mut frames = Vec::new();
    for line in lines.iter().filter_map(serde_json::Value::as_str) {
        let row: serde_json::Value = serde_json::from_str(line)
            .map_err(|error| format!("parse embedded recorded hand skinning row: {error}"))?;
        let runtime_poses = parse_runtime_pose_slots(&row)?;
        let tip_lengths = row
            .get("tip_lengths_m")
            .and_then(serde_json::Value::as_array)
            .ok_or_else(|| "recorded hand clip row missing tip_lengths_m".to_string())?
            .iter()
            .map(|value| f32_value(value, "tip_lengths_m"))
            .collect::<Result<Vec<_>, _>>()?;
        frames.push(RecordedHandSkinningFrame {
            frame_index: required_u64(&row, "frame_index")? as u32,
            timestamp_ns: required_u64(&row, "timestamp_ns")?,
            runtime_joint_poses: runtime_pose_slots_to_gpu(&runtime_poses, runtime_joint_count)?,
            tip_length_rows: pack_tip_length_rows(&tip_lengths),
        });
    }
    if frames.is_empty() {
        return Err("embedded recorded hand clip did not produce skinning frames".to_string());
    }
    Ok(frames)
}

fn runtime_pose_slots_to_gpu(
    runtime_poses: &[Option<RecordedHandPose>],
    runtime_joint_count: usize,
) -> Result<Vec<RecordedHandGpuPose>, String> {
    (0..runtime_joint_count)
        .map(|index| {
            runtime_poses
                .get(index)
                .and_then(|pose| *pose)
                .map(RecordedHandGpuPose::from)
                .ok_or_else(|| "recorded hand clip row missing runtime joint pose".to_string())
        })
        .collect()
}

fn pack_tip_length_rows(tip_lengths: &[f32]) -> Vec<[f32; 4]> {
    tip_lengths
        .chunks(4)
        .map(|chunk| {
            let mut row = [0.0; 4];
            row[..chunk.len()].copy_from_slice(chunk);
            row
        })
        .collect()
}

fn parse_runtime_pose_slots(
    row: &serde_json::Value,
) -> Result<Vec<Option<RecordedHandPose>>, String> {
    let joints = row
        .get("joints")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "recorded hand clip row missing joints".to_string())?;
    let max_index = joints
        .iter()
        .filter_map(|joint| joint.get("joint_index").and_then(serde_json::Value::as_u64))
        .max()
        .unwrap_or(0) as usize;
    let mut poses = vec![None; max_index.saturating_add(1)];
    for joint in joints {
        let index = required_u64(joint, "joint_index")? as usize;
        let pose = joint
            .get("pose")
            .ok_or_else(|| "recorded hand runtime joint missing pose".to_string())?;
        if index >= poses.len() {
            poses.resize(index + 1, None);
        }
        poses[index] = Some(parse_pose(pose)?);
    }
    Ok(poses)
}

fn parse_pose(value: &serde_json::Value) -> Result<RecordedHandPose, String> {
    let translation = value
        .get("translation")
        .and_then(parse_vec3)
        .ok_or_else(|| "pose missing translation".to_string())?;
    let rotation_xyzw = value
        .get("rotation")
        .and_then(parse_vec4)
        .ok_or_else(|| "pose missing rotation".to_string())?;
    Ok(RecordedHandPose {
        translation,
        rotation_xyzw,
    })
}

fn parse_vec3(value: &serde_json::Value) -> Option<[f32; 3]> {
    let values = value.as_array()?;
    if values.len() != 3 {
        return None;
    }
    Some([
        f32_value(&values[0], "vec3.x").ok()?,
        f32_value(&values[1], "vec3.y").ok()?,
        f32_value(&values[2], "vec3.z").ok()?,
    ])
}

fn parse_vec4(value: &serde_json::Value) -> Option<[f32; 4]> {
    let values = value.as_array()?;
    if values.len() != 4 {
        return None;
    }
    Some([
        f32_value(&values[0], "vec4.x").ok()?,
        f32_value(&values[1], "vec4.y").ok()?,
        f32_value(&values[2], "vec4.z").ok()?,
        f32_value(&values[3], "vec4.w").ok()?,
    ])
}

fn analyze_mesh_components(
    vertex_count: usize,
    triangles: &[[u32; 3]],
) -> Result<(Vec<u32>, MeshComponentSummary), String> {
    let mut union_find = UnionFind::new(vertex_count);
    for triangle in triangles {
        let [a, b, c] = triangle_vertices(*triangle, vertex_count)?;
        union_find.union(a, b);
        union_find.union(b, c);
        union_find.union(c, a);
    }

    let mut root_ids = Vec::<usize>::new();
    let mut vertex_component_ids = Vec::with_capacity(vertex_count);
    let mut component_vertex_counts = Vec::<u64>::new();
    for vertex_index in 0..vertex_count {
        let root = union_find.find(vertex_index);
        let component_id = if let Some(index) = root_ids.iter().position(|value| *value == root) {
            index
        } else {
            let component_id = root_ids.len();
            root_ids.push(root);
            component_vertex_counts.push(0);
            component_id
        };
        vertex_component_ids.push(component_id);
        component_vertex_counts[component_id] += 1;
    }

    let mut component_triangle_counts = vec![0_u64; component_vertex_counts.len()];
    let mut triangle_component_ids = Vec::with_capacity(triangles.len());
    for triangle in triangles {
        let [a, b, c] = triangle_vertices(*triangle, vertex_count)?;
        let component_id = vertex_component_ids[a];
        if vertex_component_ids[b] != component_id || vertex_component_ids[c] != component_id {
            return Err("recorded hand mesh triangle spans multiple components".to_string());
        }
        triangle_component_ids.push(component_id);
        component_triangle_counts[component_id] += 1;
    }

    let mut ranked_component_ids = (0..component_vertex_counts.len()).collect::<Vec<_>>();
    ranked_component_ids.sort_by(|left, right| {
        component_vertex_counts[*right]
            .cmp(&component_vertex_counts[*left])
            .then(component_triangle_counts[*right].cmp(&component_triangle_counts[*left]))
            .then(left.cmp(right))
    });
    let mut component_rank_by_id = vec![0_u32; component_vertex_counts.len()];
    for (rank, component_id) in ranked_component_ids.iter().copied().enumerate() {
        component_rank_by_id[component_id] = rank as u32;
    }
    let triangle_component_ranks = triangle_component_ids
        .iter()
        .map(|component_id| component_rank_by_id[*component_id])
        .collect::<Vec<_>>();
    let summary = MeshComponentSummary {
        component_count: ranked_component_ids.len() as u64,
        vertex_counts: ranked_component_ids
            .iter()
            .map(|component_id| component_vertex_counts[*component_id])
            .collect(),
        triangle_counts: ranked_component_ids
            .iter()
            .map(|component_id| component_triangle_counts[*component_id])
            .collect(),
    };
    Ok((triangle_component_ranks, summary))
}

struct UnionFind {
    parent: Vec<usize>,
}

impl UnionFind {
    fn new(len: usize) -> Self {
        Self {
            parent: (0..len).collect(),
        }
    }

    fn find(&mut self, value: usize) -> usize {
        let parent = self.parent[value];
        if parent == value {
            value
        } else {
            let root = self.find(parent);
            self.parent[value] = root;
            root
        }
    }

    fn union(&mut self, left: usize, right: usize) {
        let left_root = self.find(left);
        let right_root = self.find(right);
        if left_root != right_root {
            self.parent[right_root] = left_root;
        }
    }
}

fn triangle_vertices(triangle: [u32; 3], vertex_count: usize) -> Result<[usize; 3], String> {
    let [a, b, c] = triangle;
    let result = [a as usize, b as usize, c as usize];
    if result.iter().any(|index| *index >= vertex_count) {
        return Err("recorded hand triangle index outside vertices".to_string());
    }
    Ok(result)
}

fn normalize_xy_points(points: &[[f32; 3]]) -> Vec<[f32; 2]> {
    let mut min_x = f32::INFINITY;
    let mut max_x = f32::NEG_INFINITY;
    let mut min_y = f32::INFINITY;
    let mut max_y = f32::NEG_INFINITY;
    for [x, y, _] in points.iter().copied() {
        min_x = min_x.min(x);
        max_x = max_x.max(x);
        min_y = min_y.min(y);
        max_y = max_y.max(y);
    }
    let width = (max_x - min_x).max(1.0e-5);
    let height = (max_y - min_y).max(1.0e-5);
    points
        .iter()
        .map(|[x, y, _]| {
            let normalized_x = ((*x - min_x) / width * 0.72 + 0.14).clamp(0.0, 1.0);
            let normalized_y = (1.0 - ((*y - min_y) / height * 0.72 + 0.14)).clamp(0.0, 1.0);
            [normalized_x, normalized_y]
        })
        .collect()
}

fn array_len(value: &serde_json::Value, field: &'static str) -> Result<u64, String> {
    value
        .get(field)
        .and_then(serde_json::Value::as_array)
        .map(|values| values.len() as u64)
        .ok_or_else(|| format!("missing array field {field}"))
}

fn text_field(value: &serde_json::Value, field: &'static str) -> Option<String> {
    value
        .get(field)
        .and_then(serde_json::Value::as_str)
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_owned)
}

fn source_hands(value: &serde_json::Value) -> Result<&[serde_json::Value], String> {
    let schema = required_text(value, "schema")?;
    if !schema.ends_with("recorded_hand_replay_source.v1") {
        return Err(format!("unsupported recorded hand replay schema {schema}"));
    }
    value
        .get("hands")
        .and_then(serde_json::Value::as_array)
        .map(Vec::as_slice)
        .filter(|hands| !hands.is_empty())
        .ok_or_else(|| "recorded hand replay source has no hands".to_string())
}

fn hand_by_handedness<'a>(
    hands: &'a [serde_json::Value],
    handedness: &str,
) -> Option<&'a serde_json::Value> {
    hands
        .iter()
        .find(|hand| text_field(hand, "handedness").as_deref() == Some(handedness))
}

fn required_text(value: &serde_json::Value, field: &'static str) -> Result<String, String> {
    text_field(value, field).ok_or_else(|| format!("missing text field {field}"))
}

fn required_u64(value: &serde_json::Value, field: &'static str) -> Result<u64, String> {
    value
        .get(field)
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| format!("missing u64 field {field}"))
}

fn u32_value(value: &serde_json::Value, field: &'static str) -> Result<u32, String> {
    let number = value
        .as_u64()
        .ok_or_else(|| format!("missing u64 field {field}"))?;
    u32::try_from(number).map_err(|_| format!("u32 field {field} is too large"))
}

fn checked_u32(value: usize, field: &'static str) -> Result<u32, String> {
    u32::try_from(value).map_err(|_| format!("{field} must fit in a u32"))
}

fn optional_usize(value: &serde_json::Value, field: &'static str) -> Option<usize> {
    value
        .get(field)
        .and_then(serde_json::Value::as_u64)
        .and_then(|number| usize::try_from(number).ok())
}

fn bool_field(value: &serde_json::Value, field: &'static str) -> bool {
    value
        .get(field)
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false)
}

fn f32_value(value: &serde_json::Value, field: &'static str) -> Result<f32, String> {
    value
        .as_f64()
        .filter(|number| number.is_finite())
        .map(|number| number as f32)
        .ok_or_else(|| format!("invalid f32 field {field}"))
}

fn join_u64(values: &[u64]) -> String {
    values
        .iter()
        .map(u64::to_string)
        .collect::<Vec<_>>()
        .join(",")
}

#[cfg(test)]
mod tests {
    use super::{RecordedHandReplaySet, RecordedHandReplaySummary};

    #[test]
    fn public_recorded_hand_shape_fixture_loads() {
        let replay = RecordedHandReplaySummary::load().expect("fixture loads");

        assert_eq!(replay.vertex_count, 1360);
        assert_eq!(replay.triangle_count, 2314);
        assert_eq!(replay.index_count, 6942);
        assert_eq!(replay.runtime_joint_count, 21);
        assert_eq!(replay.bind_joint_count, 26);
        if replay.source_kind == "public-topology-shape-fixture" {
            assert!(!replay.has_bind_mesh_payload);
            assert_eq!(replay.source_vertex_buffer_bytes(), 0);
            assert!(replay.mesh_visual_frames.is_empty());
            assert!(!replay.has_gpu_skinning_sdf_payload());
            assert_eq!(replay.skinning_source_vertex_buffer_bytes(), 0);
            assert_eq!(replay.skinning_triangle_buffer_bytes(), 0);
        } else {
            assert!(replay.has_bind_mesh_payload);
            assert_eq!(replay.source_vertex_buffer_bytes(), 1360 * 16);
            assert_eq!(replay.mesh_component_summary.component_count, 3);
            assert!(replay.mesh_visual_vertex_capacity() > 0);
            assert!(replay.has_gpu_skinning_sdf_payload());
            assert_eq!(replay.skinning_vertices.len(), 1360);
            assert_eq!(replay.skinning_triangles.len(), 2314);
        }
        assert!(replay
            .marker_fields()
            .contains("recordedInputEquivalent=true"));
    }

    #[test]
    fn recorded_hand_replay_set_provides_right_hand_route() {
        let replay_set = RecordedHandReplaySet::load().expect("fixture set loads");

        assert_eq!(replay_set.left.vertex_count, 1360);
        assert_eq!(replay_set.right.vertex_count, 1360);
        if replay_set.right_hand_distinct {
            assert_eq!(replay_set.right.handedness, "right");
            assert!(replay_set.right.has_gpu_skinning_sdf_payload());
        } else {
            assert_eq!(replay_set.right.handedness, replay_set.left.handedness);
        }
        let markers = replay_set.marker_fields();
        assert!(markers.contains("recordedHandReplayHandSetReady=true"));
        assert!(markers.contains("recordedHandReplayRightHandDistinct="));
    }
}
