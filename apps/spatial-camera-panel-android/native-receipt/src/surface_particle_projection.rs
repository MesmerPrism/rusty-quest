#[derive(Clone, Copy, Debug)]
pub(crate) struct SurfaceParticleOpenXrPanelMapping {
    pub(crate) view_position: [f32; 3],
    pub(crate) raw_right: [f32; 3],
    pub(crate) raw_up: [f32; 3],
    pub(crate) raw_forward: [f32; 3],
    pub(crate) scene_eye_position: [f32; 3],
    pub(crate) panel_right: [f32; 3],
    pub(crate) panel_up: [f32; 3],
    pub(crate) panel_forward: [f32; 3],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct SurfaceParticleFixedWorldRegistration {
    pub(crate) raw_origin: [f32; 3],
    pub(crate) raw_right: [f32; 3],
    pub(crate) raw_up: [f32; 3],
    pub(crate) raw_forward: [f32; 3],
    pub(crate) scene_origin: [f32; 3],
    pub(crate) scene_right: [f32; 3],
    pub(crate) scene_up: [f32; 3],
    pub(crate) scene_forward: [f32; 3],
}

impl SurfaceParticleOpenXrPanelMapping {
    pub(crate) fn map_point(self, point: [f32; 3]) -> [f32; 3] {
        let rel = sub3(point, self.view_position);
        add3(
            self.scene_eye_position,
            add3(
                scale3(self.panel_right, dot3(rel, self.raw_right)),
                add3(
                    scale3(self.panel_up, dot3(rel, self.raw_up)),
                    scale3(self.panel_forward, dot3(rel, self.raw_forward)),
                ),
            ),
        )
    }

    pub(crate) fn map_vector(self, vector: [f32; 3]) -> [f32; 3] {
        normalize_or(
            add3(
                scale3(self.panel_right, dot3(vector, self.raw_right)),
                add3(
                    scale3(self.panel_up, dot3(vector, self.raw_up)),
                    scale3(self.panel_forward, dot3(vector, self.raw_forward)),
                ),
            ),
            self.panel_forward,
        )
    }
}

impl SurfaceParticleFixedWorldRegistration {
    pub(crate) fn map_point(self, point: [f32; 3]) -> [f32; 3] {
        let rel = sub3(point, self.raw_origin);
        add3(
            self.scene_origin,
            add3(
                scale3(self.scene_right, dot3(rel, self.raw_right)),
                add3(
                    scale3(self.scene_up, dot3(rel, self.raw_up)),
                    scale3(self.scene_forward, dot3(rel, self.raw_forward)),
                ),
            ),
        )
    }

    pub(crate) fn map_vector(self, vector: [f32; 3]) -> [f32; 3] {
        normalize_or(
            add3(
                scale3(self.scene_right, dot3(vector, self.raw_right)),
                add3(
                    scale3(self.scene_up, dot3(vector, self.raw_up)),
                    scale3(self.scene_forward, dot3(vector, self.raw_forward)),
                ),
            ),
            self.scene_forward,
        )
    }
}

pub(crate) fn panel_forward(right: [f32; 3], up: [f32; 3]) -> [f32; 3] {
    normalize_or(
        [
            up[1] * right[2] - up[2] * right[1],
            up[2] * right[0] - up[0] * right[2],
            up[0] * right[1] - up[1] * right[0],
        ],
        [0.0, 0.0, -1.0],
    )
}

pub(crate) fn canonical_world_basis() -> ([f32; 3], [f32; 3], [f32; 3]) {
    ([1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, -1.0])
}

fn cross3(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]
}

pub(crate) fn main_draw_panel_forward_distance(
    right: [f32; 3],
    up: [f32; 3],
    target_distance_meters: f32,
) -> [f32; 4] {
    let forward = panel_forward(right, up);
    [
        forward[0],
        forward[1],
        forward[2],
        target_distance_meters.clamp(0.20, 2.00),
    ]
}

pub(crate) fn panel_eye_position(
    center: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    target_distance_meters: f32,
) -> [f32; 3] {
    let forward = panel_forward(right, up);
    add3(center, scale3(forward, -target_distance_meters))
}

pub(crate) fn viewer_camera_panel_center(
    viewer_position: [f32; 3],
    viewer_forward: [f32; 3],
    target_distance_meters: f32,
) -> [f32; 3] {
    let forward = normalize_or(viewer_forward, [0.0, 0.0, -1.0]);
    let distance = if target_distance_meters.is_finite() {
        target_distance_meters
    } else {
        2.0
    };
    add3(viewer_position, scale3(forward, distance))
}

fn length3(value: [f32; 3]) -> f32 {
    (value[0] * value[0] + value[1] * value[1] + value[2] * value[2]).sqrt()
}

fn roll_stable_right_for_forward(forward: [f32; 3]) -> [f32; 3] {
    let world_up_right = cross3(forward, [0.0, 1.0, 0.0]);
    if length3(world_up_right) > 0.0001 {
        return normalize_or(world_up_right, [1.0, 0.0, 0.0]);
    }
    let depth_right = cross3(forward, [0.0, 0.0, -1.0]);
    if length3(depth_right) > 0.0001 {
        return normalize_or(depth_right, [1.0, 0.0, 0.0]);
    }
    [1.0, 0.0, 0.0]
}

fn particle_camera_forward_from_spatial_viewer_forward(viewer_forward: [f32; 3]) -> [f32; 3] {
    normalize_or(
        [-viewer_forward[0], viewer_forward[1], viewer_forward[2]],
        [0.0, 0.0, -1.0],
    )
}

pub(crate) fn viewer_forward_roll_stable_camera_basis(
    viewer_forward: [f32; 3],
) -> ([f32; 3], [f32; 3], [f32; 3]) {
    let forward = particle_camera_forward_from_spatial_viewer_forward(viewer_forward);
    let right = roll_stable_right_for_forward(forward);
    let up = normalize_or(cross3(right, forward), [0.0, 1.0, 0.0]);
    (right, up, forward)
}

pub(crate) fn viewer_forward_roll_stable_panel_center(
    viewer_position: [f32; 3],
    viewer_forward: [f32; 3],
    target_distance_meters: f32,
) -> [f32; 3] {
    let (_, _, forward) = viewer_forward_roll_stable_camera_basis(viewer_forward);
    let distance = if target_distance_meters.is_finite() {
        target_distance_meters
    } else {
        2.0
    };
    add3(viewer_position, scale3(forward, distance))
}

pub(crate) fn viewer_forward_roll_stable_eye_position(
    viewer_position: [f32; 3],
    viewer_forward: [f32; 3],
    eye_offset_right_meters: f32,
) -> [f32; 3] {
    let (right, _, _) = viewer_forward_roll_stable_camera_basis(viewer_forward);
    let offset = if eye_offset_right_meters.is_finite() {
        eye_offset_right_meters
    } else {
        0.0
    };
    add3(viewer_position, scale3(right, offset.clamp(-0.12, 0.12)))
}

pub(crate) fn draw_eye_world(
    stored_eye: Option<[f32; 3]>,
    viewer_position: Option<[f32; 3]>,
    viewer_forward: [f32; 3],
    eye_offset_right_meters: f32,
    panel_center: [f32; 3],
    panel_right: [f32; 3],
    panel_up: [f32; 3],
    target_distance_meters: f32,
) -> [f32; 3] {
    if let Some(eye) = stored_eye {
        if eye.iter().all(|component| component.is_finite()) {
            return eye;
        }
    }
    if let Some(viewer) = viewer_position {
        if viewer.iter().all(|component| component.is_finite()) {
            return viewer_forward_roll_stable_eye_position(
                viewer,
                viewer_forward,
                eye_offset_right_meters,
            );
        }
    }
    add3(
        panel_eye_position(panel_center, panel_right, panel_up, target_distance_meters),
        scale3(
            panel_right,
            eye_offset_right_meters.clamp(-0.12, 0.12),
        ),
    )
}

pub(crate) fn viewer_sphere_center_distance_exceeds_threshold(
    viewer_position: [f32; 3],
    sphere_center: [f32; 3],
    threshold_meters: f32,
) -> Option<f32> {
    if !viewer_position.iter().all(|value| value.is_finite())
        || !sphere_center.iter().all(|value| value.is_finite())
        || !threshold_meters.is_finite()
        || threshold_meters < 0.0
        || viewer_position[1].abs() < 0.25
    {
        return None;
    }
    let dx = viewer_position[0] - sphere_center[0];
    let dy = viewer_position[1] - sphere_center[1];
    let dz = viewer_position[2] - sphere_center[2];
    let distance_squared = dx * dx + dy * dy + dz * dz;
    let threshold_squared = threshold_meters * threshold_meters;
    if distance_squared > threshold_squared {
        Some(distance_squared.sqrt())
    } else {
        None
    }
}

#[cfg(test)]
pub(crate) fn panel_center_from_eye(
    eye_position: [f32; 3],
    right: [f32; 3],
    up: [f32; 3],
    target_distance_meters: f32,
) -> [f32; 3] {
    let forward = panel_forward(right, up);
    add3(eye_position, scale3(forward, target_distance_meters))
}

pub(crate) fn project_world_to_panel_ndc(
    world: [f32; 3],
    eye: [f32; 3],
    panel_center: [f32; 3],
    panel_right: [f32; 3],
    panel_up: [f32; 3],
    panel_width_m: f32,
    panel_height_m: f32,
) -> Option<[f32; 2]> {
    let right = normalize_or(panel_right, [1.0, 0.0, 0.0]);
    let up = normalize_or(panel_up, [0.0, 1.0, 0.0]);
    let forward = panel_forward(right, up);
    let ray = sub3(world, eye);
    let depth = dot3(ray, forward);
    let plane_distance = dot3(sub3(panel_center, eye), forward);
    if depth <= 0.030 || plane_distance <= 0.030 {
        return None;
    }

    let t = plane_distance / depth;
    let hit = add3(eye, scale3(ray, t));
    let rel = sub3(hit, panel_center);
    let half_width = panel_width_m.max(0.001) * 0.5;
    let half_height = panel_height_m.max(0.001) * 0.5;
    Some([dot3(rel, right) / half_width, dot3(rel, up) / half_height])
}

pub(crate) fn normalize_or(value: [f32; 3], fallback: [f32; 3]) -> [f32; 3] {
    let len_sq = value[0] * value[0] + value[1] * value[1] + value[2] * value[2];
    if len_sq > 0.0000001 {
        let inv_len = len_sq.sqrt().recip();
        [value[0] * inv_len, value[1] * inv_len, value[2] * inv_len]
    } else {
        fallback
    }
}

pub(crate) fn add3(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [left[0] + right[0], left[1] + right[1], left[2] + right[2]]
}

fn sub3(left: [f32; 3], right: [f32; 3]) -> [f32; 3] {
    [left[0] - right[0], left[1] - right[1], left[2] - right[2]]
}

pub(crate) fn scale3(value: [f32; 3], scale: f32) -> [f32; 3] {
    [value[0] * scale, value[1] * scale, value[2] * scale]
}

fn dot3(left: [f32; 3], right: [f32; 3]) -> f32 {
    left[0] * right[0] + left[1] * right[1] + left[2] * right[2]
}

pub(crate) fn normalize_quat_or_none(value: [f32; 4]) -> Option<[f32; 4]> {
    if !value.iter().all(|component| component.is_finite()) {
        return None;
    }
    let len_sq =
        value[0] * value[0] + value[1] * value[1] + value[2] * value[2] + value[3] * value[3];
    if len_sq <= 0.000001 {
        return None;
    }
    let inv_len = len_sq.sqrt().recip();
    Some([
        value[0] * inv_len,
        value[1] * inv_len,
        value[2] * inv_len,
        value[3] * inv_len,
    ])
}

pub(crate) fn rotate_vec3_by_quat(quat: [f32; 4], vector: [f32; 3]) -> [f32; 3] {
    let Some(q) = normalize_quat_or_none(quat) else {
        return vector;
    };
    let q_vec = [q[0], q[1], q[2]];
    let uv = [
        q_vec[1] * vector[2] - q_vec[2] * vector[1],
        q_vec[2] * vector[0] - q_vec[0] * vector[2],
        q_vec[0] * vector[1] - q_vec[1] * vector[0],
    ];
    let uuv = [
        q_vec[1] * uv[2] - q_vec[2] * uv[1],
        q_vec[2] * uv[0] - q_vec[0] * uv[2],
        q_vec[0] * uv[1] - q_vec[1] * uv[0],
    ];
    [
        vector[0] + uv[0] * (2.0 * q[3]) + uuv[0] * 2.0,
        vector[1] + uv[1] * (2.0 * q[3]) + uuv[1] * 2.0,
        vector[2] + uv[2] * (2.0 * q[3]) + uuv[2] * 2.0,
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_vec3_near(actual: [f32; 3], expected: [f32; 3]) {
        for (actual, expected) in actual.into_iter().zip(expected) {
            assert!((actual - expected).abs() < 0.0001, "{actual} != {expected}");
        }
    }

    fn assert_vec2_near(actual: [f32; 2], expected: [f32; 2]) {
        for (actual, expected) in actual.into_iter().zip(expected) {
            assert!((actual - expected).abs() < 0.0001, "{actual} != {expected}");
        }
    }

    #[test]
    fn panel_forward_uses_live_panel_basis() {
        assert_vec3_near(
            panel_forward([1.0, 0.0, 0.0], [0.0, 1.0, 0.0]),
            [0.0, 0.0, -1.0],
        );
        assert_vec3_near(
            panel_forward([0.0, 0.0, 1.0], [0.0, 1.0, 0.0]),
            [1.0, 0.0, 0.0],
        );
    }

    #[test]
    fn canonical_world_basis_does_not_follow_panel_yaw() {
        let (right, up, forward) = canonical_world_basis();
        assert_vec3_near(right, [1.0, 0.0, 0.0]);
        assert_vec3_near(up, [0.0, 1.0, 0.0]);
        assert_vec3_near(forward, [0.0, 0.0, -1.0]);

        let yawed_panel_forward = panel_forward([0.0, 0.0, 1.0], [0.0, 1.0, 0.0]);
        assert_vec3_near(yawed_panel_forward, [1.0, 0.0, 0.0]);
        assert_ne!(forward, yawed_panel_forward);
    }

    #[test]
    fn fixed_openxr_anchor_remaps_through_changed_panel_basis() {
        let raw_anchor = [0.0, 1.4, -2.0];
        let mapping = SurfaceParticleOpenXrPanelMapping {
            view_position: [0.0, 1.4, 0.0],
            raw_right: [1.0, 0.0, 0.0],
            raw_up: [0.0, 1.0, 0.0],
            raw_forward: [0.0, 0.0, -1.0],
            scene_eye_position: [0.0, 1.4, 0.0],
            panel_right: [0.0, 0.0, 1.0],
            panel_up: [0.0, 1.0, 0.0],
            panel_forward: [1.0, 0.0, 0.0],
        };

        assert_vec3_near(mapping.map_point(raw_anchor), [2.0, 1.4, 0.0]);
        assert_vec3_near(mapping.map_vector([0.0, 0.0, -1.0]), [1.0, 0.0, 0.0]);
    }

    #[test]
    fn spatial_panel_camera_pose_translates_without_reanchoring_particles() {
        let right = [1.0, 0.0, 0.0];
        let up = [0.0, 1.0, 0.0];
        let target_distance = 2.0;
        let initial_eye = [0.0, 1.4, 0.0];
        let initial_panel_center = panel_center_from_eye(initial_eye, right, up, target_distance);
        let fixed_sim_origin_in_spatial_world = initial_panel_center;

        let moved_eye = [0.5, 1.4, 0.0];
        let moved_panel_center = panel_center_from_eye(moved_eye, right, up, target_distance);

        assert_vec3_near(initial_panel_center, [0.0, 1.4, -2.0]);
        assert_vec3_near(
            panel_eye_position(moved_panel_center, right, up, target_distance),
            moved_eye,
        );
        assert_vec3_near(fixed_sim_origin_in_spatial_world, [0.0, 1.4, -2.0]);
        assert_vec3_near(moved_panel_center, [0.5, 1.4, -2.0]);
    }

    #[test]
    fn fixed_world_registration_moves_eye_without_moving_anchor() {
        let registration = SurfaceParticleFixedWorldRegistration {
            raw_origin: [0.0, 1.4, 0.0],
            raw_right: [1.0, 0.0, 0.0],
            raw_up: [0.0, 1.0, 0.0],
            raw_forward: [0.0, 0.0, -1.0],
            scene_origin: [0.0, 1.4, 0.0],
            scene_right: [1.0, 0.0, 0.0],
            scene_up: [0.0, 1.0, 0.0],
            scene_forward: [0.0, 0.0, -1.0],
        };

        assert_vec3_near(registration.map_point([0.0, 1.4, -2.0]), [0.0, 1.4, -2.0]);
        assert_vec3_near(registration.map_point([0.1, 1.4, 0.0]), [0.1, 1.4, 0.0]);
        assert_vec3_near(registration.map_vector([0.0, 0.0, -1.0]), [0.0, 0.0, -1.0]);
    }

    #[test]
    fn main_draw_forward_distance_uses_live_basis_and_spatial_distance_clamp() {
        assert_eq!(
            main_draw_panel_forward_distance([0.0, 0.0, 1.0], [0.0, 1.0, 0.0], 3.0),
            [1.0, 0.0, 0.0, 2.0],
        );
        assert_eq!(
            main_draw_panel_forward_distance([1.0, 0.0, 0.0], [0.0, 1.0, 0.0], 0.05),
            [0.0, 0.0, -1.0, 0.2],
        );
    }

    #[test]
    fn panel_yaw_does_not_move_eye_camera() {
        let explicit_eye = [0.0, 1.4, 0.0];
        let yawed_panel_center = [1.0, 1.4, -2.0];
        let yawed_panel_right = [0.0, 0.0, 1.0];
        let yawed_panel_up = [0.0, 1.0, 0.0];
        let world_particle = [2.0, 1.4, -2.0];

        let panel_derived_eye =
            panel_eye_position(yawed_panel_center, yawed_panel_right, yawed_panel_up, 2.0);
        assert_vec3_near(panel_derived_eye, [-1.0, 1.4, -2.0]);
        assert_ne!(panel_derived_eye, explicit_eye);

        let explicit_projection = project_world_to_panel_ndc(
            world_particle,
            explicit_eye,
            yawed_panel_center,
            yawed_panel_right,
            yawed_panel_up,
            2.0,
            2.0,
        )
        .expect("explicit eye should see the yawed panel");
        let panel_derived_projection = project_world_to_panel_ndc(
            world_particle,
            panel_derived_eye,
            yawed_panel_center,
            yawed_panel_right,
            yawed_panel_up,
            2.0,
            2.0,
        )
        .expect("panel-derived fallback remains projectable for comparison");

        assert_vec2_near(explicit_projection, [1.0, 0.0]);
        assert_vec2_near(panel_derived_projection, [0.0, 0.0]);
    }

    #[test]
    fn viewer_camera_panel_center_ignores_yawed_carrier_panel_basis() {
        let viewer = [0.0, 1.4, 0.0];
        let viewer_forward = [0.0, 0.0, -1.0];
        let yawed_carrier_right = [0.0, 0.0, 1.0];
        let yawed_carrier_up = [0.0, 1.0, 0.0];

        assert_vec3_near(
            panel_forward(yawed_carrier_right, yawed_carrier_up),
            [1.0, 0.0, 0.0],
        );
        assert_vec3_near(
            viewer_camera_panel_center(viewer, viewer_forward, 2.0),
            [0.0, 1.4, -2.0],
        );
    }

    #[test]
    fn viewer_forward_roll_stable_basis_keeps_yaw_and_pitch_without_roll() {
        let (right, up, forward) = viewer_forward_roll_stable_camera_basis([0.8660254, -0.5, 0.0]);

        assert_vec3_near(forward, [-0.8660254, -0.5, 0.0]);
        assert_vec3_near(right, [0.0, 0.0, -1.0]);
        assert_vec3_near(up, [-0.5, 0.8660254, 0.0]);
        assert_vec3_near(panel_forward(right, up), [-0.8660254, -0.5, 0.0]);
    }

    #[test]
    fn viewer_forward_roll_stable_basis_corrects_spatial_horizontal_mirror() {
        let (right_for_raw_positive_x, up_for_raw_positive_x, forward_for_raw_positive_x) =
            viewer_forward_roll_stable_camera_basis([1.0, 0.0, 0.0]);
        let (right_for_raw_negative_x, up_for_raw_negative_x, forward_for_raw_negative_x) =
            viewer_forward_roll_stable_camera_basis([-1.0, 0.0, 0.0]);

        assert_vec3_near(forward_for_raw_positive_x, [-1.0, 0.0, 0.0]);
        assert_vec3_near(right_for_raw_positive_x, [0.0, 0.0, -1.0]);
        assert_vec3_near(up_for_raw_positive_x, [0.0, 1.0, 0.0]);
        assert_vec3_near(
            panel_forward(right_for_raw_positive_x, up_for_raw_positive_x),
            [-1.0, 0.0, 0.0],
        );
        assert_vec3_near(forward_for_raw_negative_x, [1.0, 0.0, 0.0]);
        assert_vec3_near(right_for_raw_negative_x, [0.0, 0.0, 1.0]);
        assert_vec3_near(up_for_raw_negative_x, [0.0, 1.0, 0.0]);
        assert_vec3_near(
            panel_forward(right_for_raw_negative_x, up_for_raw_negative_x),
            [1.0, 0.0, 0.0],
        );
    }

    #[test]
    fn viewer_forward_roll_stable_panel_center_keeps_yawed_view_direction() {
        assert_vec3_near(
            viewer_forward_roll_stable_panel_center([0.5, 1.4, 0.25], [0.8660254, -0.5, 0.0], 2.0),
            [-1.2320508, 0.4, 0.25],
        );
    }

    #[test]
    fn viewer_forward_roll_stable_eye_position_uses_yawed_right() {
        assert_vec3_near(
            viewer_forward_roll_stable_eye_position([0.2, 1.4, 0.1], [1.0, 0.0, 0.0], -0.0315),
            [0.2, 1.4, 0.1315],
        );
    }

    #[test]
    fn draw_eye_world_prefers_scene_eye_over_reconstructed_yawed_eye() {
        let scene_eye = [0.2, 1.4, 0.0685];
        let reconstructed = viewer_forward_roll_stable_eye_position(
            [0.2, 1.4, 0.1],
            [1.0, 0.0, 0.0],
            -0.0315,
        );

        assert_vec3_near(reconstructed, [0.2, 1.4, 0.1315]);
        assert_vec3_near(
            draw_eye_world(
                Some(scene_eye),
                Some([0.2, 1.4, 0.1]),
                [1.0, 0.0, 0.0],
                -0.0315,
                [0.0, 1.4, -2.0],
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                2.0,
            ),
            scene_eye,
        );
    }

    #[test]
    fn backward_viewer_motion_shrinks_yawed_fixed_world_particle_projection() {
        let viewer_forward = [1.0, 0.0, 0.0];
        let (right, up, _) = viewer_forward_roll_stable_camera_basis(viewer_forward);
        let target_distance = 2.0;
        let panel_width = 4.0;
        let panel_height = 4.0;
        let world_particle = [-2.0, 1.4, -0.5];

        let initial_viewer = [0.0, 1.4, 0.0];
        let moved_backward_viewer = [0.5, 1.4, 0.0];
        let initial_ndc = project_world_to_panel_ndc(
            world_particle,
            viewer_forward_roll_stable_eye_position(initial_viewer, viewer_forward, 0.0),
            viewer_forward_roll_stable_panel_center(
                initial_viewer,
                viewer_forward,
                target_distance,
            ),
            right,
            up,
            panel_width,
            panel_height,
        )
        .expect("initial fixed particle should project");
        let moved_ndc = project_world_to_panel_ndc(
            world_particle,
            viewer_forward_roll_stable_eye_position(moved_backward_viewer, viewer_forward, 0.0),
            viewer_forward_roll_stable_panel_center(
                moved_backward_viewer,
                viewer_forward,
                target_distance,
            ),
            right,
            up,
            panel_width,
            panel_height,
        )
        .expect("moved viewer should still project fixed particle");

        assert_vec2_near(initial_ndc, [0.25, 0.0]);
        assert_vec2_near(moved_ndc, [0.2, 0.0]);
        assert!(moved_ndc[0].abs() < initial_ndc[0].abs());
    }

    #[test]
    fn viewer_sphere_center_distance_triggers_only_beyond_half_meter() {
        let sphere_center = [0.0, 1.4, 0.0];

        assert_eq!(
            viewer_sphere_center_distance_exceeds_threshold([0.5, 1.4, 0.0], sphere_center, 0.5,),
            None,
        );
        let distance =
            viewer_sphere_center_distance_exceeds_threshold([0.501, 1.4, 0.0], sphere_center, 0.5)
                .expect("distance just beyond threshold should trigger recenter");
        assert!((distance - 0.501).abs() < 0.0001);
    }

    #[test]
    fn viewer_sphere_center_distance_rejects_non_finite_positions() {
        assert_eq!(
            viewer_sphere_center_distance_exceeds_threshold(
                [f32::NAN, 1.4, 0.0],
                [0.0, 1.4, 0.0],
                0.5,
            ),
            None,
        );
        assert_eq!(
            viewer_sphere_center_distance_exceeds_threshold(
                [0.0, 1.4, 0.0],
                [0.0, f32::INFINITY, 0.0],
                0.5,
            ),
            None,
        );
    }

    #[test]
    fn viewer_sphere_center_distance_rejects_origin_like_untracked_viewer_pose() {
        assert_eq!(
            viewer_sphere_center_distance_exceeds_threshold([0.0, 0.0, 0.0], [0.0, 1.4, -2.0], 0.5,),
            None,
        );
        assert_eq!(
            viewer_sphere_center_distance_exceeds_threshold(
                [0.0, 0.249, 2.0],
                [0.0, 1.4, -2.0],
                0.5,
            ),
            None,
        );
    }

    #[test]
    fn panel_distance_motion_does_not_reanchor_world_particle() {
        let eye = [0.0, 1.4, 0.0];
        let right = [1.0, 0.0, 0.0];
        let up = [0.0, 1.0, 0.0];
        let world_particle = [0.5, 1.6, -3.0];
        let same_world_particle = world_particle;

        let near_panel_projection =
            project_world_to_panel_ndc(world_particle, eye, [0.0, 1.4, -1.0], right, up, 2.0, 1.0)
                .expect("near panel should project the fixed particle");
        let far_panel_projection = project_world_to_panel_ndc(
            same_world_particle,
            eye,
            [0.0, 1.4, -2.0],
            right,
            up,
            2.0,
            1.0,
        )
        .expect("far panel should project the fixed particle");

        assert_eq!(same_world_particle, world_particle);
        assert_vec2_near(near_panel_projection, [0.16666667, 0.13333334]);
        assert_vec2_near(far_panel_projection, [0.33333334, 0.26666668]);
    }

    #[test]
    fn explicit_eye_replaces_panel_derived_eye() {
        let panel_center = [0.0, 1.4, -2.0];
        let right = [1.0, 0.0, 0.0];
        let up = [0.0, 1.0, 0.0];
        let explicit_eye = [0.3, 1.4, 0.0];
        let panel_derived_eye = panel_eye_position(panel_center, right, up, 2.0);
        let world_particle = [0.3, 1.4, -3.0];

        assert_vec3_near(panel_derived_eye, [0.0, 1.4, 0.0]);
        assert_ne!(panel_derived_eye, explicit_eye);

        let explicit_projection = project_world_to_panel_ndc(
            world_particle,
            explicit_eye,
            panel_center,
            right,
            up,
            2.0,
            2.0,
        )
        .expect("explicit eye should project the particle");
        let panel_derived_projection = project_world_to_panel_ndc(
            world_particle,
            panel_derived_eye,
            panel_center,
            right,
            up,
            2.0,
            2.0,
        )
        .expect("panel-derived eye should project differently");

        assert_vec2_near(explicit_projection, [0.3, 0.0]);
        assert_vec2_near(panel_derived_projection, [0.2, 0.0]);
    }

    #[test]
    fn quaternion_rotation_helper_matches_view_basis_axes() {
        let ninety_degrees_y = [
            0.0,
            std::f32::consts::FRAC_1_SQRT_2,
            0.0,
            std::f32::consts::FRAC_1_SQRT_2,
        ];

        assert_vec3_near(
            rotate_vec3_by_quat(ninety_degrees_y, [0.0, 0.0, -1.0]),
            [-1.0, 0.0, 0.0],
        );
    }
}
