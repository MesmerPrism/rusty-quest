//! Pure captured world-basis contract for private-particle compute payloads.

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct CapturedWorldBasis {
    pub(crate) right: [f32; 3],
    pub(crate) up: [f32; 3],
    pub(crate) forward: [f32; 3],
}

impl Default for CapturedWorldBasis {
    fn default() -> Self {
        Self {
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            forward: [0.0, 0.0, -1.0],
        }
    }
}

impl CapturedWorldBasis {
    pub(crate) fn from_orientation_xyzw(orientation_xyzw: [f32; 4]) -> Self {
        Self {
            right: rotate_by_quat(orientation_xyzw, [1.0, 0.0, 0.0]),
            up: rotate_by_quat(orientation_xyzw, [0.0, 1.0, 0.0]),
            forward: rotate_by_quat(orientation_xyzw, [0.0, 0.0, -1.0]),
        }
    }
}

fn rotate_by_quat(quat: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let q = normalize_quat(quat);
    let uv = cross3([q[0], q[1], q[2]], vector);
    let uuv = cross3([q[0], q[1], q[2]], uv);
    [
        vector[0] + uv[0] * (2.0 * q[3]) + uuv[0] * 2.0,
        vector[1] + uv[1] * (2.0 * q[3]) + uuv[1] * 2.0,
        vector[2] + uv[2] * (2.0 * q[3]) + uuv[2] * 2.0,
    ]
}

fn normalize_quat(quat: [f32; 4]) -> [f32; 4] {
    let length_sq = quat
        .iter()
        .map(|value| value * value)
        .sum::<f32>()
        .max(1.0e-12);
    let inv_length = 1.0 / length_sq.sqrt();
    [
        quat[0] * inv_length,
        quat[1] * inv_length,
        quat[2] * inv_length,
        quat[3] * inv_length,
    ]
}

fn cross3(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn captured_compute_basis_does_not_follow_later_head_motion() {
        let captured = CapturedWorldBasis::from_orientation_xyzw([0.0, 0.0, 0.0, 1.0]);
        let later_head = CapturedWorldBasis::from_orientation_xyzw([
            0.0,
            std::f32::consts::FRAC_1_SQRT_2,
            0.0,
            std::f32::consts::FRAC_1_SQRT_2,
        ]);
        assert_eq!(captured, CapturedWorldBasis::default());
        assert_ne!(captured, later_head);
    }

    #[test]
    fn recenter_recaptures_right_up_and_forward_axes_together() {
        let before = CapturedWorldBasis::from_orientation_xyzw([0.0, 0.0, 0.0, 1.0]);
        let after = CapturedWorldBasis::from_orientation_xyzw([0.5, 0.5, 0.5, 0.5]);
        assert_ne!(after.right, before.right);
        assert_ne!(after.up, before.up);
        assert_ne!(after.forward, before.forward);
    }
}
