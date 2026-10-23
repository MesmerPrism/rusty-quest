//! Small truthful world-space input visuals, independent from the Android texture.
use crate::{
    native_alternate_input::PointerOwner,
    native_mesh_ui::{self, PanelPose},
};

pub(crate) const NEUTRAL_WORLDSPACE_CLEAR: [f32; 4] = [0.022, 0.022, 0.022, 1.0];

#[derive(Clone, Copy, Debug)]
pub(crate) struct PointerVisual {
    pub(crate) owner: PointerOwner,
    pub(crate) origin: [f32; 3],
    pub(crate) end: [f32; 3],
    pub(crate) hit: bool,
    pub(crate) pressed: bool,
}
impl PointerVisual {
    pub(crate) fn new(
        owner: PointerOwner,
        valid: bool,
        pressed: bool,
        origin: [f32; 3],
        direction: [f32; 3],
        panel: Option<PanelPose>,
    ) -> Option<Self> {
        if !valid
            || owner == PointerOwner::None
            || origin
                .iter()
                .chain(direction.iter())
                .any(|v| !v.is_finite())
            || (dot(direction, direction) - 1.0).abs() > 0.01
        {
            return None;
        }
        let pixels = panel.and_then(|pose| native_mesh_ui::ray_pixels(pose, origin, direction));
        let hit = pixels.is_some_and(|p| {
            (0.0..native_mesh_ui::WIDTH as f32).contains(&p[0])
                && (0.0..native_mesh_ui::HEIGHT as f32).contains(&p[1])
        });
        let end = if hit {
            let pose = panel.unwrap();
            let p = pixels.unwrap();
            let normal = cross(pose.right, pose.up);
            std::array::from_fn(|i| {
                pose.center[i]
                    + (p[0] / native_mesh_ui::WIDTH as f32 - 0.5) * pose.width * pose.right[i]
                    + (0.5 - p[1] / native_mesh_ui::HEIGHT as f32) * pose.height * pose.up[i]
                    + normal[i] * 0.006
            })
        } else {
            std::array::from_fn(|i| origin[i] + direction[i] * 2.5)
        };
        Some(Self {
            owner,
            origin,
            end,
            hit,
            pressed,
        })
    }
    pub(crate) fn color(self) -> [f32; 3] {
        if self.pressed {
            [1.0, 0.65, 0.08]
        } else if self.hit {
            [0.18, 1.0, 0.45]
        } else {
            [0.8, 0.9, 1.0]
        }
    }
}
pub(crate) fn dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    (0..3).map(|i| a[i] * b[i]).sum()
}
pub(crate) fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
pub(crate) fn normalized(v: [f32; 3]) -> Option<[f32; 3]> {
    let n = dot(v, v).sqrt();
    (n.is_finite() && n > 0.00001).then(|| v.map(|x| x / n))
}
/// A thin quad along a segment; fallback handles looking directly along the ray.
pub(crate) fn segment_pose(
    a: [f32; 3],
    b: [f32; 3],
    eye: [f32; 3],
    eye_right: [f32; 3],
    width: f32,
) -> Option<PanelPose> {
    let delta = std::array::from_fn(|i| b[i] - a[i]);
    let up = normalized(delta)?;
    let center = std::array::from_fn(|i| (a[i] + b[i]) * 0.5);
    let to_eye = std::array::from_fn(|i| eye[i] - center[i]);
    let right = normalized(cross(up, to_eye)).or_else(|| normalized(cross(up, eye_right)))?;
    Some(PanelPose {
        center,
        right,
        up,
        width,
        height: dot(delta, delta).sqrt(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn valid_miss_stays_visible_and_hit_matches_widget_pixels() {
        let p = PanelPose::default();
        let v = PointerVisual::new(
            PointerOwner::RightHand,
            true,
            false,
            [0.0; 3],
            [0.0, 0.0, -1.0],
            Some(p),
        )
        .unwrap();
        assert!(v.hit);
        assert!((v.end[2] - (p.center[2] + 0.006)).abs() < 0.00001);
        let miss = PointerVisual::new(
            PointerOwner::Controllers,
            true,
            false,
            [2.0, 0.0, 0.0],
            [0.0, 0.0, -1.0],
            Some(p),
        )
        .unwrap();
        assert!(!miss.hit);
        assert_eq!(miss.end, [2.0, 0.0, -2.5]);
    }
    #[test]
    fn loss_and_unowned_input_never_leave_a_visual() {
        for (owner, valid) in [(PointerOwner::RightHand, false), (PointerOwner::None, true)] {
            assert!(
                PointerVisual::new(owner, valid, false, [0.0; 3], [0.0, 0.0, -1.0], None).is_none()
            );
        }
        assert!(PointerVisual::new(
            PointerOwner::Controllers,
            true,
            false,
            [0.0; 3],
            [f32::NAN, 0.0, 0.0],
            None
        )
        .is_none());
    }
    #[test]
    fn parallel_view_segment_uses_finite_fallback() {
        let p = segment_pose(
            [0.0, 0.0, -0.2],
            [0.0, 0.0, -1.0],
            [0.0; 3],
            [1.0, 0.0, 0.0],
            0.003,
        )
        .unwrap();
        assert!((dot(p.right, p.up)).abs() < 0.00001);
        assert!((p.height - 0.8).abs() < 0.00001);
        assert!(segment_pose([0.0; 3], [0.0; 3], [0.0; 3], [1.0, 0.0, 0.0], 0.003).is_none());
    }
}
