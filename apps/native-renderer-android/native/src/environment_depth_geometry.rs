//! Source-only environment-depth reference-space math.
//!
//! Runtime depth acquisition and GPU ownership stay in the OpenXR/Vulkan
//! renderer. This module only proves the coordinate semantics that the first
//! environment-depth GPU path must preserve.

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct FovTangents {
    pub(crate) left: f32,
    pub(crate) right: f32,
    pub(crate) down: f32,
    pub(crate) up: f32,
}

impl FovTangents {
    pub(crate) const fn symmetric(unit_tangent: f32) -> Self {
        Self {
            left: -unit_tangent,
            right: unit_tangent,
            down: -unit_tangent,
            up: unit_tangent,
        }
    }

    fn valid(self) -> bool {
        self.left.is_finite()
            && self.right.is_finite()
            && self.down.is_finite()
            && self.up.is_finite()
            && self.right > self.left
            && self.up > self.down
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ReferencePose {
    pub(crate) position_m: [f32; 3],
    pub(crate) orientation_xyzw: [f32; 4],
}

impl ReferencePose {
    pub(crate) const fn identity() -> Self {
        Self {
            position_m: [0.0, 0.0, 0.0],
            orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct RenderEyeProjection {
    pub(crate) eye_position_m: [f32; 3],
    pub(crate) ndc_xy: [f32; 2],
    pub(crate) screen_uv: [f32; 2],
    pub(crate) forward_m: f32,
}

pub(crate) fn reconstruct_reference_space_point(
    depth_uv: [f32; 2],
    depth_meters: f32,
    depth_view_fov: FovTangents,
    depth_view_pose: ReferencePose,
) -> Option<[f32; 3]> {
    if !depth_uv[0].is_finite()
        || !depth_uv[1].is_finite()
        || !depth_meters.is_finite()
        || depth_meters <= 0.0
        || !depth_view_fov.valid()
    {
        return None;
    }

    let u = depth_uv[0].clamp(0.0, 1.0);
    let v = depth_uv[1].clamp(0.0, 1.0);
    let tangent_x = lerp(depth_view_fov.left, depth_view_fov.right, u);
    let tangent_y = lerp(depth_view_fov.down, depth_view_fov.up, v);
    let view_position = [
        tangent_x * depth_meters,
        tangent_y * depth_meters,
        -depth_meters,
    ];
    let rotated = rotate_by_quat(depth_view_pose.orientation_xyzw, view_position);
    Some([
        depth_view_pose.position_m[0] + rotated[0],
        depth_view_pose.position_m[1] + rotated[1],
        depth_view_pose.position_m[2] + rotated[2],
    ])
}

pub(crate) fn project_reference_space_point_to_render_eye(
    reference_point_m: [f32; 3],
    render_eye_fov: FovTangents,
    render_eye_pose: ReferencePose,
) -> Option<RenderEyeProjection> {
    if !reference_point_m.iter().all(|value| value.is_finite()) || !render_eye_fov.valid() {
        return None;
    }

    let delta = [
        reference_point_m[0] - render_eye_pose.position_m[0],
        reference_point_m[1] - render_eye_pose.position_m[1],
        reference_point_m[2] - render_eye_pose.position_m[2],
    ];
    let eye_position_m = rotate_by_quat(inverse_quat(render_eye_pose.orientation_xyzw), delta);
    if eye_position_m[2] >= -0.0001 {
        return None;
    }

    let forward_m = -eye_position_m[2];
    let tangent_x = eye_position_m[0] / forward_m;
    let tangent_y = eye_position_m[1] / forward_m;
    let tangent_width = render_eye_fov.right - render_eye_fov.left;
    let tangent_height = render_eye_fov.up - render_eye_fov.down;
    let screen_x = (tangent_x - render_eye_fov.left) / tangent_width;
    let screen_y = 1.0 - ((tangent_y - render_eye_fov.down) / tangent_height);
    let ndc_xy = [screen_x * 2.0 - 1.0, screen_y * 2.0 - 1.0];

    Some(RenderEyeProjection {
        eye_position_m,
        ndc_xy,
        screen_uv: [screen_x, screen_y],
        forward_m,
    })
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

fn inverse_quat(quat: [f32; 4]) -> [f32; 4] {
    let q = normalize_quat(quat);
    [-q[0], -q[1], -q[2], q[3]]
}

fn rotate_by_quat(quat: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let q = normalize_quat(quat);
    let q_xyz = [q[0], q[1], q[2]];
    let uv = cross(q_xyz, vector);
    let uuv = cross(q_xyz, uv);
    [
        vector[0] + (uv[0] * q[3] + uuv[0]) * 2.0,
        vector[1] + (uv[1] * q[3] + uuv[1]) * 2.0,
        vector[2] + (uv[2] * q[3] + uuv[2]) * 2.0,
    ]
}

fn normalize_quat(quat: [f32; 4]) -> [f32; 4] {
    let length_sq = quat[0] * quat[0] + quat[1] * quat[1] + quat[2] * quat[2] + quat[3] * quat[3];
    if !length_sq.is_finite() || length_sq <= 0.000_000_000_001 {
        return [0.0, 0.0, 0.0, 1.0];
    }
    let inv = length_sq.sqrt().recip();
    [quat[0] * inv, quat[1] * inv, quat[2] * inv, quat[3] * inv]
}

fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

#[cfg(test)]
mod tests {
    use super::{
        project_reference_space_point_to_render_eye, reconstruct_reference_space_point,
        FovTangents, ReferencePose,
    };

    fn assert_vec3_close(actual: [f32; 3], expected: [f32; 3]) {
        for index in 0..3 {
            assert!(
                (actual[index] - expected[index]).abs() < 0.0001,
                "index {index}: actual {} expected {}",
                actual[index],
                expected[index]
            );
        }
    }

    fn assert_vec2_close(actual: [f32; 2], expected: [f32; 2]) {
        for index in 0..2 {
            assert!(
                (actual[index] - expected[index]).abs() < 0.0001,
                "index {index}: actual {} expected {}",
                actual[index],
                expected[index]
            );
        }
    }

    #[test]
    fn center_depth_pixel_reconstructs_in_reference_space_meters() {
        let point = reconstruct_reference_space_point(
            [0.5, 0.5],
            2.0,
            FovTangents::symmetric(1.0),
            ReferencePose::identity(),
        )
        .expect("center depth reconstructs");

        assert_vec3_close(point, [0.0, 0.0, -2.0]);
    }

    #[test]
    fn off_center_depth_uv_follows_fov_tangents() {
        let point = reconstruct_reference_space_point(
            [1.0, 0.75],
            2.0,
            FovTangents::symmetric(1.0),
            ReferencePose::identity(),
        )
        .expect("off-center depth reconstructs");

        assert_vec3_close(point, [2.0, 1.0, -2.0]);
    }

    #[test]
    fn depth_view_pose_rotation_and_translation_are_applied_once() {
        let half_turn = std::f32::consts::FRAC_1_SQRT_2;
        let pose = ReferencePose {
            position_m: [10.0, 0.0, 0.0],
            orientation_xyzw: [0.0, half_turn, 0.0, half_turn],
        };
        let point =
            reconstruct_reference_space_point([0.5, 0.5], 2.0, FovTangents::symmetric(1.0), pose)
                .expect("rotated depth reconstructs");

        assert_vec3_close(point, [8.0, 0.0, 0.0]);
    }

    #[test]
    fn retained_reference_point_projects_through_current_render_eye() {
        let fov = FovTangents::symmetric(1.0);
        let retained_reference_point =
            reconstruct_reference_space_point([0.75, 0.5], 2.0, fov, ReferencePose::identity())
                .expect("retained point reconstructs");

        let acquired_eye_projection = project_reference_space_point_to_render_eye(
            retained_reference_point,
            fov,
            ReferencePose::identity(),
        )
        .expect("acquired eye projects");
        assert_vec2_close(acquired_eye_projection.screen_uv, [0.75, 0.5]);

        let current_render_eye_projection = project_reference_space_point_to_render_eye(
            retained_reference_point,
            fov,
            ReferencePose {
                position_m: [1.0, 0.0, 0.0],
                orientation_xyzw: [0.0, 0.0, 0.0, 1.0],
            },
        )
        .expect("current render eye projects");

        assert_vec2_close(current_render_eye_projection.screen_uv, [0.5, 0.5]);
        assert_vec2_close(current_render_eye_projection.ndc_xy, [0.0, 0.0]);
        assert!((current_render_eye_projection.forward_m - 2.0).abs() < 0.0001);
    }

    #[test]
    fn invalid_or_behind_eye_samples_are_rejected() {
        assert!(reconstruct_reference_space_point(
            [0.5, 0.5],
            f32::INFINITY,
            FovTangents::symmetric(1.0),
            ReferencePose::identity(),
        )
        .is_none());
        assert!(project_reference_space_point_to_render_eye(
            [0.0, 0.0, 1.0],
            FovTangents::symmetric(1.0),
            ReferencePose::identity(),
        )
        .is_none());
    }
}
