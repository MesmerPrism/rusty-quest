//! Exclusive hands-or-controllers input with optional runtime aim and joint fallback.

/// One current action-space location. Valid-but-untracked poses can be runtime
/// estimates (including a stationary origin), not a tracked panel pointer.
#[derive(Clone, Copy, Debug)]
pub(crate) struct ControllerPointerPose {
    pub(crate) active: bool,
    pub(crate) tracked: bool,
    pub(crate) origin: [f32; 3],
    pub(crate) orientation: [f32; 4],
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct ControllerPointerRay {
    pub(crate) origin: [f32; 3],
    pub(crate) direction: [f32; 3],
    pub(crate) source: &'static str,
}

/// Prefer the runtime aim; a tracked grip supplies local -Z only when aim is
/// unavailable. Never keep a prior frame or invent an origin from an inactive pose.
pub(crate) fn controller_pointer(
    aim: Option<ControllerPointerPose>,
    grip: Option<ControllerPointerPose>,
) -> Option<ControllerPointerRay> {
    let ray = |pose: ControllerPointerPose, source| {
        if !pose.active || !pose.tracked {
            return None;
        }
        let sample = runtime_aim_pointer(pose.origin, pose.orientation, true, true, false, false)?;
        Some(ControllerPointerRay {
            origin: sample.origin,
            direction: sample.direction,
            source,
        })
    };
    aim.and_then(|pose| ray(pose, "right-aim"))
        .or_else(|| grip.and_then(|pose| ray(pose, "right-grip")))
}

pub(crate) fn tracked_pointer_enabled_for_mode(mode: &str) -> bool {
    tracked_pointer_selection(
        mode,
        option_env!("RUSTY_QUEST_NATIVE_RENDERER_TRACKED_HAND_POINTER_ENABLED") == Some("true"),
    )
}
fn tracked_pointer_selection(mode: &str, packaged: bool) -> bool {
    packaged && mode == "native-worldspace-integration"
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct HandPointerSample {
    pub(crate) origin: [f32; 3],
    pub(crate) direction: [f32; 3],
    pub(crate) pinch_distance: f32,
    pub(crate) right: bool,
    /// Some only for valid runtime FB aim; None selects distance hysteresis.
    pub(crate) runtime_pinch: Option<bool>,
}

pub(crate) fn joint_pointer(
    wrist: [f32; 3],
    index_base: [f32; 3],
    thumb_tip: [f32; 3],
    index_tip: [f32; 3],
    right: bool,
) -> Option<HandPointerSample> {
    if wrist
        .iter()
        .chain(index_base.iter())
        .chain(thumb_tip.iter())
        .chain(index_tip.iter())
        .any(|v| !v.is_finite())
    {
        return None;
    }
    let delta: [f32; 3] = std::array::from_fn(|i| index_base[i] - wrist[i]);
    let length = delta.iter().map(|v| v * v).sum::<f32>().sqrt();
    let pinch = (0..3)
        .map(|i| (thumb_tip[i] - index_tip[i]).powi(2))
        .sum::<f32>()
        .sqrt();
    if !(0.025..=0.25).contains(&length) || !(0.0..=0.2).contains(&pinch) {
        return None;
    }
    Some(HandPointerSample {
        origin: index_base,
        direction: delta.map(|v| v / length),
        pinch_distance: pinch,
        right,
        runtime_pinch: None,
    })
}

/// Runtime aim is accepted only while valid and outside a system gesture.
/// Never infer a click or ray from an invalid pose; re-entry still needs an open pinch.
pub(crate) fn runtime_aim_pointer(
    origin: [f32; 3],
    q: [f32; 4],
    right: bool,
    valid: bool,
    system_gesture: bool,
    pinching: bool,
) -> Option<HandPointerSample> {
    if !valid
        || system_gesture
        || origin.iter().chain(q.iter()).any(|v| !v.is_finite())
        || (q.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() > 0.01
    {
        return None;
    }
    // OpenXR aim points along local -Z. Keep this in the same reference space as hits.
    let direction = [
        -2.0 * (q[0] * q[2] + q[3] * q[1]),
        -2.0 * (q[1] * q[2] - q[3] * q[0]),
        2.0 * (q[0] * q[0] + q[1] * q[1]) - 1.0,
    ];
    let length = direction.iter().map(|v| v * v).sum::<f32>().sqrt();
    Some(HandPointerSample {
        origin,
        direction: direction.map(|v| v / length),
        pinch_distance: 0.0,
        right,
        runtime_pinch: Some(pinching),
    })
}
impl HandPointerSample {
    pub(crate) fn source(self) -> &'static str {
        if self.runtime_pinch.is_some() {
            "XR_FB_hand_tracking_aim"
        } else {
            "tracked-joints-wrist-index-proximal"
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub(crate) enum PointerOwner {
    #[default]
    None,
    Controllers,
    RightHand,
    LeftHand,
}
impl PointerOwner {
    pub(crate) fn marker(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::Controllers => "controllers",
            Self::RightHand => "right-hand",
            Self::LeftHand => "left-hand",
        }
    }
}
#[derive(Default)]
pub(crate) struct AlternatePointer {
    owner: PointerOwner,
    armed: bool,
    pressed: bool,
}
impl AlternatePointer {
    pub(crate) fn reset(&mut self) {
        *self = Self::default();
    }
    /// The dedicated Operator hand-test host prefers a current valid hand sample.
    /// Samples have already passed the same runtime tracking/aim gates as physical hands.
    /// Reuse the ordinary ownership transition and pinch arming state machine.
    #[cfg(any(native_meta_xr_operator, test))]
    pub(crate) fn update_preferring_hands(
        &mut self,
        controller: bool,
        hand: Option<HandPointerSample>,
    ) -> (PointerOwner, bool, bool) {
        self.update(controller && hand.is_none(), hand)
    }
    /// Controller priority. Ownership changes must cancel the previous UI capture first.
    /// A new/lost hand must first open its pinch before it can generate a down event.
    pub(crate) fn update(
        &mut self,
        controller: bool,
        hand: Option<HandPointerSample>,
    ) -> (PointerOwner, bool, bool) {
        let owner = if controller {
            PointerOwner::Controllers
        } else {
            hand.map(|h| {
                if h.right {
                    PointerOwner::RightHand
                } else {
                    PointerOwner::LeftHand
                }
            })
            .unwrap_or_default()
        };
        let changed = owner != self.owner;
        if changed {
            self.armed = false;
            self.pressed = false;
            self.owner = owner;
        }
        if !controller {
            if let Some(hand) = hand {
                if let Some(pinching) = hand.runtime_pinch {
                    if !pinching {
                        self.armed = true;
                    }
                    self.pressed = self.armed && pinching;
                } else if hand.pinch_distance >= 0.04 {
                    self.armed = true;
                    self.pressed = false;
                } else if self.armed && hand.pinch_distance <= 0.025 {
                    self.pressed = true;
                }
            } else {
                self.armed = false;
                self.pressed = false;
            }
        }
        (owner, !controller && self.pressed, changed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn controller(origin: [f32; 3]) -> ControllerPointerPose {
        ControllerPointerPose {
            active: true,
            tracked: true,
            origin,
            orientation: [0., 0., 0., 1.],
        }
    }
    #[test]
    fn tracked_controller_motion_reaches_panel_pixels_without_origin_substitution() {
        let first = controller_pointer(Some(controller([0.1, 0.1, 0.])), None).unwrap();
        let moved = controller_pointer(Some(controller([0.3, 0.1, 0.])), None).unwrap();
        assert_eq!(first.source, "right-aim");
        assert_eq!(moved.origin, [0.3, 0.1, 0.]);
        let panel = crate::native_mesh_ui::PanelPose::default();
        let a = crate::native_mesh_ui::ray_pixels(panel, first.origin, first.direction).unwrap();
        let b = crate::native_mesh_ui::ray_pixels(panel, moved.origin, moved.direction).unwrap();
        assert!(b[0] > a[0] + 100.);
        assert_eq!(a[1], b[1]);
    }
    #[test]
    fn inactive_or_untracked_controller_default_does_not_capture_from_hand() {
        for pose in [
            ControllerPointerPose {
                active: false,
                ..controller([0.; 3])
            },
            ControllerPointerPose {
                tracked: false,
                ..controller([0.; 3])
            },
        ] {
            let ray = controller_pointer(Some(pose), None);
            assert!(ray.is_none());
            let mut owner = AlternatePointer::default();
            assert_eq!(
                owner.update(ray.is_some(), Some(hand(0.05))).0,
                PointerOwner::RightHand
            );
        }
        // The reference-space origin itself is legal when actually tracked.
        assert!(controller_pointer(Some(controller([0.; 3])), None).is_some());
    }
    #[test]
    fn tracked_grip_fallback_is_current_and_aim_keeps_priority() {
        let grip = controller([0.25, 1., -0.3]);
        let missing_aim = ControllerPointerPose {
            tracked: false,
            ..controller([0.; 3])
        };
        let fallback = controller_pointer(Some(missing_aim), Some(grip)).unwrap();
        assert_eq!(fallback.origin, grip.origin);
        assert_eq!(fallback.source, "right-grip");
        let aim = controller([-0.2, 1.1, -0.4]);
        let preferred = controller_pointer(Some(aim), Some(grip)).unwrap();
        assert_eq!(preferred.source, "right-aim");
        assert_eq!(preferred.origin, aim.origin);
        assert!(controller_pointer(None, None).is_none());
    }
    #[test]
    fn malformed_controller_pose_never_becomes_a_ray() {
        for pose in [
            ControllerPointerPose {
                origin: [f32::NAN, 0., 0.],
                ..controller([0.; 3])
            },
            ControllerPointerPose {
                orientation: [0.; 4],
                ..controller([0.; 3])
            },
            ControllerPointerPose {
                orientation: [0., f32::INFINITY, 0., 1.],
                ..controller([0.; 3])
            },
        ] {
            assert!(controller_pointer(Some(pose), None).is_none());
            assert!(controller_pointer(None, Some(pose)).is_none());
        }
    }
    #[test]
    fn controller_ray_rotates_local_negative_z() {
        let pose = ControllerPointerPose {
            orientation: [
                0.,
                std::f32::consts::FRAC_1_SQRT_2,
                0.,
                std::f32::consts::FRAC_1_SQRT_2,
            ],
            ..controller([0.2, 1.2, -0.5])
        };
        let ray = controller_pointer(Some(pose), None).unwrap();
        assert!((ray.direction[0] + 1.).abs() < 1e-6);
        assert!(ray.direction[1].abs() < 1e-6);
        assert!(ray.direction[2].abs() < 1e-6);
    }
    #[test]
    fn controller_tracking_loss_cancels_drag_before_hand_handoff() {
        let ray = controller_pointer(Some(controller([0.1, 0.1, 0.])), None).unwrap();
        let pixels = crate::native_mesh_ui::ray_pixels(
            crate::native_mesh_ui::PanelPose::default(),
            ray.origin,
            ray.direction,
        );
        let mut capture = crate::native_mesh_ui::PointerCapture::default();
        assert_eq!(capture.update(true, true, pixels).unwrap().0, 0);
        assert!(capture.captured());
        let missing = controller_pointer(None, None);
        assert_eq!(capture.update(missing.is_some(), false, None).unwrap().0, 3);
        assert!(!capture.captured());
    }
    #[test]
    fn raw_pointer_requires_explicit_package_selection_and_worldspace_mode() {
        assert!(!tracked_pointer_selection(
            "native-worldspace-integration",
            false
        ));
        assert!(!tracked_pointer_selection(
            "solid-black-hands-and-grafts",
            true
        ));
        assert!(tracked_pointer_selection(
            "native-worldspace-integration",
            true
        ));
    }
    fn hand(distance: f32) -> HandPointerSample {
        HandPointerSample {
            runtime_pinch: None,
            origin: [0.; 3],
            direction: [0., 0., -1.],
            pinch_distance: distance,
            right: true,
        }
    }
    fn operator_update(
        state: &mut AlternatePointer,
        controller: bool,
        sample: Option<HandPointerSample>,
    ) -> (PointerOwner, bool, bool) {
        state.update_preferring_hands(controller, sample)
    }
    #[test]
    fn operator_hand_preference_selects_valid_hand_despite_active_controller() {
        let mut state = AlternatePointer::default();
        assert_eq!(
            operator_update(&mut state, true, Some(hand(0.05))),
            (PointerOwner::RightHand, false, true)
        );
        assert_eq!(
            operator_update(&mut state, true, Some(hand(0.01))),
            (PointerOwner::RightHand, true, false)
        );
    }
    #[test]
    fn operator_hand_loss_falls_back_and_reentry_requires_open_pinch() {
        let mut state = AlternatePointer::default();
        operator_update(&mut state, true, Some(hand(0.05)));
        assert!(operator_update(&mut state, true, Some(hand(0.01))).1);
        assert_eq!(
            operator_update(&mut state, true, None),
            (PointerOwner::Controllers, false, true)
        );
        assert_eq!(
            operator_update(&mut state, true, Some(hand(0.01))),
            (PointerOwner::RightHand, false, true)
        );
        assert!(!operator_update(&mut state, true, Some(hand(0.05))).1);
        assert!(operator_update(&mut state, true, Some(hand(0.01))).1);
        assert_eq!(
            operator_update(&mut state, false, None),
            (PointerOwner::None, false, true)
        );
        assert!(!operator_update(&mut state, false, Some(hand(0.01))).1);
    }
    #[test]
    fn operator_hand_handoff_cancels_capture_and_runtime_pinch_rearms() {
        let mut state = AlternatePointer::default();
        let sample = |right, pinch| {
            runtime_aim_pointer([0.; 3], [0., 0., 0., 1.], right, true, false, pinch)
        };
        operator_update(&mut state, true, sample(true, false));
        let (_, pressed, _) = operator_update(&mut state, true, sample(true, true));
        let mut capture = crate::native_mesh_ui::PointerCapture::default();
        assert_eq!(
            capture.update(true, pressed, Some([512., 169.])).unwrap().0,
            0
        );
        let (owner, pressed, changed) = operator_update(&mut state, true, sample(false, true));
        assert_eq!(
            (owner, pressed, changed),
            (PointerOwner::LeftHand, false, true)
        );
        // The render loop cancels the original capture on this ownership edge.
        assert_eq!(capture.update(false, false, None).unwrap().0, 3);
        assert!(!capture.captured());
        assert!(!operator_update(&mut state, true, sample(false, false)).1);
        assert!(operator_update(&mut state, true, sample(false, true)).1);
        state.reset();
        assert!(!operator_update(&mut state, true, sample(false, true)).1);
    }
    #[test]
    fn pinch_hysteresis_and_tracking_loss_require_open_hand() {
        let mut state = AlternatePointer::default();
        assert_eq!(
            state.update(false, Some(hand(0.01))),
            (PointerOwner::RightHand, false, true)
        );
        assert!(!state.update(false, Some(hand(0.05))).1);
        assert!(state.update(false, Some(hand(0.02))).1);
        assert!(state.update(false, Some(hand(0.03))).1);
        assert!(!state.update(false, None).1);
        assert!(!state.update(false, Some(hand(0.01))).1);
        state.reset();
        assert!(!state.update(false, Some(hand(0.01))).1);
    }
    #[test]
    fn controllers_and_hand_changes_cancel_previous_capture() {
        let mut state = AlternatePointer::default();
        state.update(false, Some(hand(0.05)));
        assert_eq!(
            state.update(true, Some(hand(0.01))),
            (PointerOwner::Controllers, false, true)
        );
        assert_eq!(
            state.update(true, None),
            (PointerOwner::Controllers, false, false)
        );
        assert_eq!(
            state.update(false, Some(hand(0.01))),
            (PointerOwner::RightHand, false, true)
        );
        let mut left = hand(0.05);
        left.right = false;
        assert_eq!(
            state.update(false, Some(left)),
            (PointerOwner::LeftHand, false, true)
        );
    }
    #[test]
    fn ray_normalization_and_invalid_geometry_fail_closed() {
        let sample = joint_pointer(
            [0.; 3],
            [0., 0., -0.1],
            [0., 0., -0.2],
            [0.01, 0., -0.2],
            true,
        )
        .unwrap();
        assert_eq!(sample.direction, [0., 0., -1.]);
        assert_eq!(sample.origin, [0., 0., -0.1]);
        assert_eq!(PointerOwner::Controllers.marker(), "controllers");
        assert!((sample.pinch_distance - 0.01).abs() < 1e-6);
        assert!(joint_pointer([0.; 3], [0.; 3], [0.; 3], [0.; 3], true).is_none());
        assert!(
            joint_pointer([f32::NAN, 0., 0.], [0., 0., -0.1], [0.; 3], [0.; 3], true).is_none()
        );
    }
    #[test]
    fn hand_pinch_hits_real_panel_math_and_switch_cancels_drag() {
        use crate::native_mesh_ui::{ray_pixels, PanelPose, PointerCapture};
        let pose = PanelPose {
            center: [0., 0., -1.4],
            right: [1., 0., 0.],
            up: [0., 1., 0.],
            width: 1.,
            height: 0.75,
        };
        let mut sample = joint_pointer(
            [0.; 3],
            [0., 0., -0.1],
            [0., 0., -0.2],
            [0.05, 0., -0.2],
            true,
        )
        .unwrap();
        let pixels = ray_pixels(pose, sample.origin, sample.direction).unwrap();
        assert_eq!(pixels, [512., 384.]);
        let mut owner = AlternatePointer::default();
        let mut capture = PointerCapture::default();
        let (_, pressed, _) = owner.update(false, Some(sample));
        assert!(capture.update(true, pressed, Some(pixels)).is_none());
        sample.pinch_distance = 0.01;
        let (_, pressed, _) = owner.update(false, Some(sample));
        assert_eq!(capture.update(true, pressed, Some(pixels)).unwrap().0, 0);
        assert_eq!(
            capture.update(true, pressed, Some([600., 384.])).unwrap().0,
            1
        );
        let (_, _, changed) = owner.update(true, None);
        assert!(changed);
        assert_eq!(capture.update(false, false, None).unwrap().0, 3);
        assert!(!capture.captured());
        assert!(!owner.update(false, Some(sample)).1);
    }
    #[test]
    fn runtime_aim_cancels_system_gesture_and_rearms_after_loss() {
        let sample = |valid, system, pinch| {
            runtime_aim_pointer([0.0; 3], [0.0, 0.0, 0.0, 1.0], true, valid, system, pinch)
        };
        assert_eq!(
            sample(true, false, false).unwrap().direction,
            [0.0, 0.0, -1.0]
        );
        assert!(sample(false, false, false).is_none());
        assert!(sample(true, true, true).is_none());
        assert!(runtime_aim_pointer([0.0; 3], [f32::NAN; 4], true, true, false, false).is_none());
        let mut state = AlternatePointer::default();
        assert!(!state.update(false, sample(true, false, true)).1);
        assert!(!state.update(false, sample(true, false, false)).1);
        assert!(state.update(false, sample(true, false, true)).1);
        assert!(!state.update(false, sample(true, true, true)).1);
        assert!(!state.update(false, sample(true, false, true)).1);
        assert!(!state.update(false, sample(true, false, false)).1);
        assert!(state.update(false, sample(true, false, true)).1);
        assert_eq!(
            sample(true, false, false).unwrap().source(),
            "XR_FB_hand_tracking_aim"
        );
    }
    #[test]
    fn runtime_aim_uses_pose_negative_z_and_rejects_nonunit_orientation() {
        let h = std::f32::consts::FRAC_1_SQRT_2;
        let sample =
            runtime_aim_pointer([1.0, 2.0, 3.0], [0.0, h, 0.0, h], false, true, false, false)
                .unwrap();
        assert!((sample.direction[0] + 1.0).abs() < 1e-6);
        assert!(sample.direction[2].abs() < 1e-6);
        assert_eq!(sample.origin, [1.0, 2.0, 3.0]);
        assert!(runtime_aim_pointer([0.0; 3], [0.0; 4], true, true, false, false).is_none());
    }
}
