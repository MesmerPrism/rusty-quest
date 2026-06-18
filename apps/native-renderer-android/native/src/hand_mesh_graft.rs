//! Pure hand-mesh graft-copy anchor math for the native renderer.
//!
//! The Vulkan renderer owns buffers and draw calls. This module owns only the
//! compact-frame math for reusing an already-skinned hand mesh as fingertip
//! instances on the opposite hand.

use crate::recorded_hand_replay::{RecordedHandGpuPose, RecordedHandSkinningFrame};

pub(crate) const GRAFT_COPY_TARGET_COUNT_USIZE: usize = 5;
pub(crate) const GRAFT_COPY_TARGET_COUNT: u32 = GRAFT_COPY_TARGET_COUNT_USIZE as u32;
const RUNTIME_PALM_INDEX: usize = 0;
const RUNTIME_WRIST_INDEX: usize = 1;
const RUNTIME_FINGER_DISTAL_INDICES: [usize; GRAFT_COPY_TARGET_COUNT_USIZE] = [4, 8, 12, 16, 20];

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct HandMeshGraftParams {
    pub(crate) source_palm_position_scale: [f32; 4],
    pub(crate) source_palm_orientation_xyzw: [f32; 4],
    pub(crate) target_position_scale: [[f32; 4]; GRAFT_COPY_TARGET_COUNT_USIZE],
    pub(crate) target_orientation_xyzw: [[f32; 4]; GRAFT_COPY_TARGET_COUNT_USIZE],
}

impl HandMeshGraftParams {
    pub(crate) fn from_frames(
        source_frame: &RecordedHandSkinningFrame,
        target_frame: &RecordedHandSkinningFrame,
        scale_multiplier: f32,
    ) -> Result<Self, String> {
        let source_palm = runtime_pose(source_frame, RUNTIME_PALM_INDEX, "source palm")?;
        let source_wrist = runtime_pose(source_frame, RUNTIME_WRIST_INDEX, "source wrist")?;
        let source_wrist_radius = positive_or(source_wrist.translation_pad[3], 0.022);
        let scale_multiplier = positive_or(scale_multiplier, 1.0).clamp(0.10, 2.0);
        let mut params = Self {
            source_palm_position_scale: [
                source_palm.translation_pad[0],
                source_palm.translation_pad[1],
                source_palm.translation_pad[2],
                source_wrist_radius,
            ],
            source_palm_orientation_xyzw: source_palm.rotation_xyzw,
            target_position_scale: [[0.0; 4]; GRAFT_COPY_TARGET_COUNT_USIZE],
            target_orientation_xyzw: [[0.0, 0.0, 0.0, 1.0]; GRAFT_COPY_TARGET_COUNT_USIZE],
        };

        for (slot, distal_index) in RUNTIME_FINGER_DISTAL_INDICES.iter().copied().enumerate() {
            let distal = runtime_pose(target_frame, distal_index, "target distal")?;
            let tip_length = tip_length(target_frame, slot);
            let tip_offset = rotate_by_quat(distal.rotation_xyzw, [0.0, 0.0, -tip_length]);
            let tip_position = [
                distal.translation_pad[0] + tip_offset[0],
                distal.translation_pad[1] + tip_offset[1],
                distal.translation_pad[2] + tip_offset[2],
            ];
            let target_radius = positive_or(distal.translation_pad[3], 0.006);
            let scale =
                ((target_radius / source_wrist_radius) * scale_multiplier).clamp(0.045, 0.42);
            params.target_position_scale[slot] =
                [tip_position[0], tip_position[1], tip_position[2], scale];
            params.target_orientation_xyzw[slot] = distal.rotation_xyzw;
        }

        Ok(params)
    }
}

fn runtime_pose<'a>(
    frame: &'a RecordedHandSkinningFrame,
    index: usize,
    label: &'static str,
) -> Result<&'a RecordedHandGpuPose, String> {
    frame
        .runtime_joint_poses
        .get(index)
        .ok_or_else(|| format!("hand mesh graft missing {label} runtime joint"))
}

fn tip_length(frame: &RecordedHandSkinningFrame, index: usize) -> f32 {
    let Some(row) = frame.tip_length_rows.get(index / 4) else {
        return 0.0;
    };
    row[index % 4].max(0.0)
}

fn positive_or(value: f32, fallback: f32) -> f32 {
    value
        .is_finite()
        .then_some(value)
        .filter(|value| *value > 0.0001)
        .unwrap_or(fallback)
}

fn rotate_by_quat(quat: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let q = normalize_quat(quat);
    let qxyz = [q[0], q[1], q[2]];
    let uv = cross(qxyz, vector);
    let uuv = cross(qxyz, uv);
    [
        vector[0] + uv[0] * (2.0 * q[3]) + uuv[0] * 2.0,
        vector[1] + uv[1] * (2.0 * q[3]) + uuv[1] * 2.0,
        vector[2] + uv[2] * (2.0 * q[3]) + uuv[2] * 2.0,
    ]
}

fn normalize_quat(quat: [f32; 4]) -> [f32; 4] {
    let length_sq = quat.iter().map(|value| value * value).sum::<f32>();
    if !length_sq.is_finite() || length_sq <= 0.000000000001 {
        return [0.0, 0.0, 0.0, 1.0];
    }
    let scale = length_sq.sqrt().recip();
    [
        quat[0] * scale,
        quat[1] * scale,
        quat[2] * scale,
        quat[3] * scale,
    ]
}

fn cross(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

#[cfg(test)]
mod tests {
    use super::{HandMeshGraftParams, RecordedHandGpuPose, RecordedHandSkinningFrame};

    #[test]
    fn graft_params_use_source_palm_and_target_fingertips() {
        let mut source_poses = vec![pose(0.0, 0.0, 0.0, 0.01); 21];
        source_poses[0] = pose(1.0, 2.0, 3.0, 0.020);
        source_poses[1] = pose(1.0, 1.9, 3.0, 0.020);

        let mut target_poses = vec![pose(0.0, 0.0, 0.0, 0.01); 21];
        for (index, distal_index) in [4, 8, 12, 16, 20].iter().copied().enumerate() {
            target_poses[distal_index] = pose(index as f32, 0.5, 0.25, 0.005);
        }

        let source = frame(
            source_poses,
            [[0.01, 0.01, 0.01, 0.01], [0.01, 0.0, 0.0, 0.0]],
        );
        let target = frame(
            target_poses,
            [[0.03, 0.04, 0.05, 0.06], [0.07, 0.0, 0.0, 0.0]],
        );

        let params =
            HandMeshGraftParams::from_frames(&source, &target, 0.85).expect("graft params");

        assert_eq!(&params.source_palm_position_scale[0..3], &[1.0, 2.0, 3.0]);
        assert!((params.source_palm_position_scale[3] - 0.020).abs() < 0.0001);
        assert!((params.target_position_scale[0][2] - 0.22).abs() < 0.0001);
        assert!((params.target_position_scale[0][3] - 0.2125).abs() < 0.0001);
        assert!(params
            .target_position_scale
            .iter()
            .all(|target| target[3] > 0.0));
    }

    fn frame(
        runtime_joint_poses: Vec<RecordedHandGpuPose>,
        tip_length_rows: [[f32; 4]; 2],
    ) -> RecordedHandSkinningFrame {
        RecordedHandSkinningFrame {
            frame_index: 1,
            timestamp_ns: 2,
            runtime_joint_poses,
            tip_length_rows: tip_length_rows.to_vec(),
        }
    }

    fn pose(x: f32, y: f32, z: f32, radius: f32) -> RecordedHandGpuPose {
        RecordedHandGpuPose {
            translation_pad: [x, y, z, radius],
            rotation_xyzw: [0.0, 0.0, 0.0, 1.0],
        }
    }
}
