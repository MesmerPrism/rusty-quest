//! Native presentation controls. Values always come from the panel owner.
use crate::{
    native_panel_interaction::SliderDescriptor, native_panel_model::LogicalBounds,
    native_panel_ui_geometry::Primitive,
};
pub(crate) const SCALE_CONTROL: u32 = 10;
pub(crate) const ATTACH_CONTROL: u32 = 11;
pub(crate) const SCALE_TRACK: [f32; 4] = [-0.22, 0.775, 0.30, 0.009];
pub(crate) const ATTACH_BUTTON: [f32; 4] = [0.45, 0.765, 0.135, 0.035];
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct ToolbarKey {
    pub hovered: Option<usize>,
    pub pressed: Option<usize>,
    pub forms: bool,
    pub width: u32,
    pub height: u32,
    pub detail: crate::native_hybrid_panel::DetailMode,
    pub physical_width: f32,
    pub wrist: bool,
    pub anchor_valid: bool,
    pub wrist_available: bool,
    pub extra_hover: Option<u32>,
    pub extra_pressed: Option<u32>,
    pub fps: u16,
}
pub(crate) fn normalized_width(width: f32) -> f32 {
    ((width - 0.48) / 0.96).clamp(0.0, 1.0)
}
pub(crate) fn wrist_local_pose(
    world: crate::native_panel_model::PanelPose,
    wrist: crate::native_panel_model::TrackedPose,
) -> Option<crate::native_panel_model::PanelPose> {
    if !world.valid()
        || !wrist.valid
        || !wrist
            .orientation_xyzw
            .iter()
            .chain(wrist.position.iter())
            .all(|v| v.is_finite())
        || (wrist.orientation_xyzw.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() > 0.01
    {
        return None;
    }
    let [x, y, z, w] = wrist.orientation_xyzw;
    let q = [-x, -y, -z];
    let rotate = |v: [f32; 3]| {
        let cross = |a: [f32; 3], b: [f32; 3]| {
            [
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
            ]
        };
        let a = cross(q, v);
        let b = cross(q, a);
        std::array::from_fn(|i| v[i] + 2.0 * (w * a[i] + b[i]))
    };
    let width = (world.width * 0.5).clamp(0.48, 1.44);
    Some(crate::native_panel_model::PanelPose {
        center: rotate([0.0, 0.12, 0.0]),
        right: rotate(world.right),
        up: rotate(world.up),
        width,
        height: width * 0.75,
    })
}
pub(crate) fn extra_hit(p: [f32; 2]) -> Option<u32> {
    let inside = |r: [f32; 4]| (p[0] - r[0]).abs() <= r[2] && (p[1] - r[1]).abs() <= r[3];
    if inside(ATTACH_BUTTON) {
        Some(ATTACH_CONTROL)
    } else if inside([
        SCALE_TRACK[0],
        SCALE_TRACK[1],
        SCALE_TRACK[2] + 0.015,
        0.028,
    ]) {
        Some(SCALE_CONTROL)
    } else {
        None
    }
}
pub(crate) fn slider(width: f32) -> SliderDescriptor {
    SliderDescriptor {
        bounds: LogicalBounds {
            min: [SCALE_TRACK[0] - SCALE_TRACK[2], 0.74],
            max: [SCALE_TRACK[0] + SCALE_TRACK[2], 0.81],
        },
        minimum: 0.48,
        maximum: 1.44,
        value: width.clamp(0.48, 1.44),
        step: None,
    }
}
pub(crate) fn append_shapes(out: &mut Vec<Primitive>, key: ToolbarKey) {
    out.push(Primitive::rounded_rect(
        [0.0, 0.765, 0.61, 0.055],
        0.018,
        [0.022, 0.031, 0.045, 0.97],
    ));
    let value = normalized_width(key.physical_width);
    out.push(Primitive::rounded_rect(
        SCALE_TRACK,
        0.007,
        [0.12, 0.16, 0.20, 1.0],
    ));
    if value > 0.0 {
        out.push(Primitive::rounded_rect(
            [
                SCALE_TRACK[0] - SCALE_TRACK[2] + SCALE_TRACK[2] * value,
                SCALE_TRACK[1],
                SCALE_TRACK[2] * value,
                0.006,
            ],
            0.005,
            [0.08, 0.55, 0.72, 1.0],
        ));
    }
    let active = key.extra_pressed == Some(SCALE_CONTROL) || key.extra_hover == Some(SCALE_CONTROL);
    out.push(Primitive::circle(
        [
            SCALE_TRACK[0] - SCALE_TRACK[2] + 2.0 * SCALE_TRACK[2] * value,
            SCALE_TRACK[1],
        ],
        if active { 0.017 } else { 0.014 },
        [0.80, 0.94, 1.0, 1.0],
    ));
    out.push(Primitive::rounded_rect(
        ATTACH_BUTTON,
        0.012,
        if !key.anchor_valid {
            [0.35, 0.16, 0.06, 1.0]
        } else if key.extra_pressed == Some(ATTACH_CONTROL) {
            [0.08, 0.40, 0.57, 1.0]
        } else if key.extra_hover == Some(ATTACH_CONTROL) {
            [0.16, 0.25, 0.34, 1.0]
        } else {
            [0.065, 0.09, 0.12, 1.0]
        },
    ));
    // Effective raster budget, derived from adopted image dimensions, never request progress.
    let budget = (key.width as f32 / 2048.0).clamp(0.0, 1.0);
    out.push(Primitive::rounded_rect(
        [0.45, 0.637, 0.13, 0.003],
        0.002,
        [0.12, 0.16, 0.20, 1.0],
    ));
    out.push(Primitive::rounded_rect(
        [0.32 + 0.13 * budget, 0.637, 0.13 * budget, 0.002],
        0.001,
        [0.08, 0.55, 0.72, 1.0],
    ));
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn size_drag_uses_absolute_owner_width_and_clamps_outside_track() {
        let s = slider(0.96);
        assert_eq!(s.proposal([-1.0, 0.775]), Some(0.48));
        assert_eq!(s.proposal([1.0, 0.775]), Some(1.44));
        assert!((s.proposal([-0.22, 0.775]).unwrap() - 0.96).abs() < 0.0001);
        assert_eq!(extra_hit([0.45, 0.765]), Some(ATTACH_CONTROL));
        assert_eq!(extra_hit([-0.22, 0.775]), Some(SCALE_CONTROL));
        assert_eq!(extra_hit([0.45, 0.59]), None);
    }
    #[test]
    fn dock_starts_above_current_wrist_and_keeps_world_facing_axes() {
        use crate::native_panel_model::{AttachmentState, PanelPose, TrackedPose};
        let world = PanelPose {
            center: [0.0, 1.6, -1.4],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            width: 0.96,
            height: 0.72,
        };
        let h = std::f32::consts::FRAC_1_SQRT_2;
        let tracking = TrackedPose {
            position: [-0.3, 1.2, -0.5],
            orientation_xyzw: [0.0, 0.0, h, h],
            valid: true,
            sequence: 1,
        };
        let local = wrist_local_pose(world, tracking).unwrap();
        let mut attached = AttachmentState::new(world).unwrap();
        attached.attach_wrist(local);
        let shown = attached.observe_wrist(Some(tracking));
        assert!(shown.input_valid);
        for i in 0..3 {
            assert!(
                (shown.pose.center[i] - tracking.position[i] - [0.0, 0.12, 0.0][i]).abs() < 0.0001
            );
            assert!((shown.pose.right[i] - world.right[i]).abs() < 0.0001);
            assert!((shown.pose.up[i] - world.up[i]).abs() < 0.0001);
        }
        assert_eq!(shown.pose.width, 0.48);
        assert!(wrist_local_pose(
            world,
            TrackedPose {
                valid: false,
                ..tracking
            }
        )
        .is_none());
        assert!(wrist_local_pose(
            world,
            TrackedPose {
                orientation_xyzw: [0.0; 4],
                ..tracking
            }
        )
        .is_none());
    }
}
