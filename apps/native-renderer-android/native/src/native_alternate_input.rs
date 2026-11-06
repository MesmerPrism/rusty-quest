//! Exclusive hands-or-controllers input with optional runtime aim and joint fallback.

pub(crate) fn controller_only_selected() -> bool {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_CONTROLLER_ONLY") == Some("true")
}

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
    !controller_only_selected()
        && tracked_pointer_selection(
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
    interaction_hand: PointerOwner,
    interaction_runtime_aim: bool,
    hand_armed: bool,
    hand_down: bool,
    controller_armed: bool,
    controller_down: bool,
    fresh_press_admitted: bool,
}
impl AlternatePointer {
    pub(crate) fn reset(&mut self) {
        *self = Self::default();
    }
    /// This frame selected a fresh edge backed by this exact source's valid-open proof.
    pub(crate) fn fresh_press_admitted(&self) -> bool {
        self.fresh_press_admitted
    }
    pub(crate) fn interaction_pressed(&self) -> bool {
        self.pressed
    }
    /// Selected experimenter panels use controller input only. Keeping the
    /// source arbiter cancels an old hand capture and still requires a valid
    /// controller release followed by a fresh trigger edge.
    pub(crate) fn update_original_product_interaction(
        &mut self,
        controller_only: bool,
        controller: bool,
        controller_pressed: bool,
        hand: Option<HandPointerSample>,
    ) -> (PointerOwner, bool, bool) {
        self.update_interaction(
            controller,
            controller_pressed,
            if controller_only { None } else { hand },
        )
    }
    /// Explicit Operator selection gives valid hand input priority over controllers.
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
        self.fresh_press_admitted = false;
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

    /// Original-product UI only: tracking supplies hover, not interaction priority.
    /// Observe both sources' release/press edges even while the other owns hover.
    /// A held owner keeps its capture through release; competing edges are consumed,
    /// never queued for a later handoff. Tracking/source loss requires a fresh open.
    pub(crate) fn update_interaction(
        &mut self,
        controller: bool,
        controller_pressed: bool,
        hand: Option<HandPointerSample>,
    ) -> (PointerOwner, bool, bool) {
        self.fresh_press_admitted = false;
        let previous_controller_down = self.controller_down;
        if !controller {
            self.controller_armed = false;
            self.controller_down = false;
        } else if !controller_pressed {
            self.controller_armed = true;
            self.controller_down = false;
        } else {
            self.controller_down = self.controller_armed;
        }
        let controller_edge = self.controller_down && !previous_controller_down;

        let hand_owner = hand
            .map(|sample| {
                if sample.right {
                    PointerOwner::RightHand
                } else {
                    PointerOwner::LeftHand
                }
            })
            .unwrap_or_default();
        let runtime_aim = hand.is_some_and(|sample| sample.runtime_pinch.is_some());
        let source_changed =
            hand_owner != self.interaction_hand || runtime_aim != self.interaction_runtime_aim;
        let previous_hand_down = self.hand_down;
        if source_changed {
            self.hand_armed = false;
            self.hand_down = false;
        }
        self.interaction_hand = hand_owner;
        self.interaction_runtime_aim = runtime_aim;
        if let Some(sample) = hand {
            if sample.runtime_pinch == Some(false)
                || sample.runtime_pinch.is_none() && sample.pinch_distance >= 0.04
            {
                self.hand_armed = true;
                self.hand_down = false;
            } else if sample.runtime_pinch == Some(true)
                || sample.runtime_pinch.is_none() && sample.pinch_distance <= 0.025
            {
                self.hand_down = self.hand_armed;
            }
        } else {
            self.hand_armed = false;
            self.hand_down = false;
        }
        let hand_edge = self.hand_down && (!previous_hand_down || source_changed);
        let previous_owner = self.owner;
        let owner_available = match previous_owner {
            PointerOwner::Controllers => controller,
            PointerOwner::RightHand | PointerOwner::LeftHand => {
                previous_owner == hand_owner && !source_changed
            }
            PointerOwner::None => false,
        };
        let owner_down = match previous_owner {
            PointerOwner::Controllers => self.controller_down,
            PointerOwner::RightHand | PointerOwner::LeftHand => self.hand_down,
            PointerOwner::None => false,
        };
        if self.pressed {
            // Deliver this owner's release, or cancel on loss, before any other down.
            self.owner = if owner_available {
                previous_owner
            } else {
                PointerOwner::None
            };
            self.pressed = owner_available && owner_down;
        } else if controller_edge {
            self.fresh_press_admitted = true;
            self.owner = PointerOwner::Controllers;
            self.pressed = true;
        } else if hand_edge {
            self.fresh_press_admitted = true;
            self.owner = hand_owner;
            self.pressed = true;
        } else {
            self.owner = if owner_available {
                previous_owner
            } else if hand.is_some() {
                hand_owner
            } else if controller {
                PointerOwner::Controllers
            } else {
                PointerOwner::None
            };
            self.pressed = false;
        }
        (self.owner, self.pressed, self.owner != previous_owner)
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

    #[derive(Default)]
    struct InteractionFixture {
        pointer: AlternatePointer,
        capture: crate::native_mesh_ui::PointerCapture,
        actions: Vec<i32>,
    }
    impl InteractionFixture {
        fn frame(
            &mut self,
            controller_tracked: bool,
            trigger: bool,
            hand: Option<HandPointerSample>,
        ) -> (PointerOwner, bool, bool) {
            self.product_frame(false, controller_tracked, trigger, hand)
        }
        fn product_frame(
            &mut self,
            selected_experimenter: bool,
            controller_tracked: bool,
            trigger: bool,
            hand: Option<HandPointerSample>,
        ) -> (PointerOwner, bool, bool) {
            let result = self.pointer.update_original_product_interaction(
                selected_experimenter,
                controller_tracked,
                trigger,
                hand,
            );
            if result.2 {
                if let Some((action, _)) = self.capture.update(false, false, None) {
                    self.actions.push(action);
                }
            }
            if self.pointer.fresh_press_admitted() {
                self.capture.admit_fresh_press();
            }
            let ray = match result.0 {
                PointerOwner::Controllers if controller_tracked => {
                    controller_pointer(Some(controller([0.0; 3])), None)
                        .map(|ray| (ray.origin, ray.direction))
                }
                PointerOwner::RightHand | PointerOwner::LeftHand => hand
                    .filter(|sample| sample.right == (result.0 == PointerOwner::RightHand))
                    .map(|sample| (sample.origin, sample.direction)),
                _ => None,
            };
            let pixels = ray.and_then(|(origin, direction)| {
                crate::native_mesh_ui::ray_pixels(
                    crate::native_mesh_ui::PanelPose::default(),
                    origin,
                    direction,
                )
            });
            if let Some((action, _)) = self.capture.update(ray.is_some(), result.1, pixels) {
                if action != 1 {
                    self.actions.push(action);
                }
            }
            result
        }
    }
    #[test]
    fn selected_experimenter_cancels_hand_capture_then_accepts_controller_click() {
        let mut f = InteractionFixture::default();
        f.frame(false, false, Some(hand(0.05)));
        f.frame(false, false, Some(hand(0.02)));
        assert!(f.capture.captured());
        assert_eq!(f.actions, [0]);
        assert_eq!(
            f.product_frame(true, true, false, Some(hand(0.02))),
            (PointerOwner::None, false, true)
        );
        assert_eq!(f.actions, [0, 3]);
        assert!(!f.capture.captured());
        assert_eq!(
            f.product_frame(true, true, false, Some(hand(0.02))).0,
            PointerOwner::Controllers
        );
        assert_eq!(
            f.product_frame(true, true, true, Some(hand(0.02))).0,
            PointerOwner::Controllers
        );
        assert!(f.capture.captured());
        f.product_frame(true, true, false, Some(hand(0.02)));
        assert_eq!(f.actions, [0, 3, 0, 2]);
    }

    #[test]
    fn selected_experimenter_never_falls_back_to_hands_or_replays_untracked_held_trigger() {
        let mut f = InteractionFixture::default();
        for distance in [0.05, 0.02, 0.05, 0.02] {
            assert_eq!(
                f.product_frame(true, false, true, Some(hand(distance))).0,
                PointerOwner::None
            );
        }
        assert!(f.actions.is_empty());
        assert!(!f.product_frame(true, true, true, Some(hand(0.02))).1);
        assert!(f.actions.is_empty());
        f.product_frame(true, true, false, Some(hand(0.02)));
        assert!(f.product_frame(true, true, true, Some(hand(0.02))).1);
        assert_eq!(f.actions, [0]);
        assert_eq!(
            f.product_frame(true, false, true, Some(hand(0.02))),
            (PointerOwner::None, false, true)
        );
        assert_eq!(f.actions, [0, 3]);
        assert!(!f.product_frame(true, true, true, Some(hand(0.02))).1);
        f.product_frame(true, true, false, Some(hand(0.02)));
        f.product_frame(true, true, true, Some(hand(0.02)));
        f.product_frame(true, true, false, Some(hand(0.02)));
        assert_eq!(f.actions, [0, 3, 0, 2]);
    }
    #[test]
    fn admission_is_current_source_fresh_edge_only_not_held_or_competing() {
        let mut p = AlternatePointer::default();
        p.update_interaction(true, true, Some(hand(0.01)));
        assert!(!p.fresh_press_admitted());
        p.update_interaction(true, false, Some(hand(0.05)));
        assert!(!p.fresh_press_admitted());
        p.update_interaction(true, true, Some(hand(0.05)));
        assert!(p.fresh_press_admitted());
        p.update_interaction(true, true, Some(hand(0.01)));
        assert!(!p.fresh_press_admitted());
        p.update_interaction(true, false, Some(hand(0.01)));
        assert!(!p.fresh_press_admitted());
        p.update_interaction(false, false, Some(hand(0.01)));
        assert!(!p.fresh_press_admitted());
        p.update_interaction(false, false, Some(hand(0.05)));
        assert!(!p.fresh_press_admitted());
        p.update_interaction(false, false, Some(hand(0.01)));
        assert!(p.fresh_press_admitted());
        p.update_interaction(false, false, None);
        assert!(!p.fresh_press_admitted());
        p.update_interaction(false, false, Some(hand(0.01)));
        assert!(!p.fresh_press_admitted());
        p.reset();
        assert!(!p.fresh_press_admitted());
    }
    #[test]
    fn original_first_hand_click_works_with_continuously_tracked_idle_controller() {
        let mut f = InteractionFixture::default();
        assert_eq!(f.frame(true, false, None).0, PointerOwner::Controllers);
        assert_eq!(
            f.frame(true, false, Some(hand(0.05))).0,
            PointerOwner::Controllers
        );
        assert_eq!(
            f.frame(true, false, Some(hand(0.02))),
            (PointerOwner::RightHand, true, true)
        );
        assert!(f.capture.captured());
        f.frame(true, false, Some(hand(0.03))); // hysteresis retains one capture
        f.frame(true, false, Some(hand(0.05)));
        assert_eq!(f.actions, [0, 2]);
        // The old neutral route deliberately retains tracked-controller priority.
        let mut neutral = AlternatePointer::default();
        neutral.update(true, Some(hand(0.05)));
        assert_eq!(
            neutral.update(true, Some(hand(0.02))),
            (PointerOwner::Controllers, false, false)
        );
    }
    #[test]
    fn original_controller_drag_consumes_competing_pinch_until_fresh_open_press() {
        let mut f = InteractionFixture::default();
        f.frame(true, false, Some(hand(0.05)));
        assert_eq!(
            f.frame(true, true, Some(hand(0.05))).0,
            PointerOwner::Controllers
        );
        assert_eq!(
            f.frame(true, true, Some(hand(0.02))).0,
            PointerOwner::Controllers
        );
        f.frame(true, false, Some(hand(0.02)));
        assert!(!f.frame(true, false, Some(hand(0.02))).1);
        assert_eq!(f.actions, [0, 2]);
        f.frame(true, false, Some(hand(0.05)));
        f.frame(true, false, Some(hand(0.02)));
        f.frame(true, false, Some(hand(0.05)));
        assert_eq!(f.actions, [0, 2, 0, 2]);
    }
    #[test]
    fn original_hand_drag_consumes_controller_edge_even_on_release_frame() {
        let mut f = InteractionFixture::default();
        f.frame(true, false, Some(hand(0.05)));
        f.frame(true, false, Some(hand(0.02)));
        assert_eq!(
            f.frame(true, true, Some(hand(0.05))).0,
            PointerOwner::RightHand
        );
        assert!(!f.frame(true, true, Some(hand(0.05))).1);
        assert_eq!(f.actions, [0, 2]);
        f.frame(true, false, Some(hand(0.05)));
        assert_eq!(
            f.frame(true, true, Some(hand(0.05))).0,
            PointerOwner::Controllers
        );
        f.frame(true, false, Some(hand(0.05)));
        assert_eq!(f.actions, [0, 2, 0, 2]);
    }
    #[test]
    fn original_tracking_loss_cancels_before_handoff_without_replaying_held_input() {
        let mut f = InteractionFixture::default();
        f.frame(true, false, Some(hand(0.05)));
        f.frame(true, true, Some(hand(0.05)));
        assert_eq!(
            f.frame(false, true, Some(hand(0.02))),
            (PointerOwner::None, false, true)
        );
        assert_eq!(f.actions, [0, 3]);
        assert!(!f.frame(false, false, Some(hand(0.02))).1);
        assert!(!f.frame(true, true, Some(hand(0.02))).1);
        f.frame(true, false, Some(hand(0.05)));
        f.frame(true, false, Some(hand(0.02)));
        assert_eq!(f.actions, [0, 3, 0]);
    }
    #[test]
    fn original_first_held_controller_and_hand_require_release_before_click() {
        let mut f = InteractionFixture::default();
        for _ in 0..3 {
            assert!(!f.frame(true, true, Some(hand(0.01))).1);
        }
        assert!(f.actions.is_empty());
        f.frame(true, false, Some(hand(0.05)));
        // Deterministic tie break: one controller down, never both pointers.
        assert_eq!(
            f.frame(true, true, Some(hand(0.01))).0,
            PointerOwner::Controllers
        );
        f.frame(true, false, Some(hand(0.01)));
        assert!(!f.frame(true, false, Some(hand(0.01))).1);
        assert_eq!(f.actions, [0, 2]);
    }
    #[test]
    fn original_hand_identity_and_aim_source_changes_cancel_and_rearm() {
        let mut f = InteractionFixture::default();
        f.frame(true, false, Some(hand(0.05)));
        f.frame(true, false, Some(hand(0.02)));
        let left = |distance| HandPointerSample {
            right: false,
            ..hand(distance)
        };
        assert_eq!(f.frame(true, false, Some(left(0.02))).0, PointerOwner::None);
        assert!(!f.frame(true, false, Some(left(0.02))).1);
        f.frame(true, false, Some(left(0.05)));
        f.frame(true, false, Some(left(0.02)));
        let aim = |pinch| {
            runtime_aim_pointer([0.; 3], [0., 0., 0., 1.], false, true, false, pinch).unwrap()
        };
        assert_eq!(f.frame(true, false, Some(aim(true))).0, PointerOwner::None);
        assert!(!f.frame(true, false, Some(aim(true))).1);
        f.frame(true, false, Some(aim(false)));
        f.frame(true, false, Some(aim(true)));
        assert_eq!(f.actions, [0, 3, 0, 3, 0]);
    }
    #[test]
    fn original_invalid_aim_system_gesture_and_reset_cannot_reenter_held() {
        let sample = |valid, system, pinch| {
            runtime_aim_pointer([0.; 3], [0., 0., 0., 1.], true, valid, system, pinch)
        };
        for lost in [
            sample(false, false, true),
            sample(true, true, true),
            runtime_aim_pointer([f32::NAN; 3], [0., 0., 0., 1.], true, true, false, true),
        ] {
            let mut f = InteractionFixture::default();
            f.frame(true, false, sample(true, false, false));
            f.frame(true, false, sample(true, false, true));
            assert_eq!(f.frame(true, false, lost).0, PointerOwner::None);
            assert!(!f.frame(true, false, sample(true, false, true)).1);
            f.frame(true, false, sample(true, false, false));
            f.frame(true, false, sample(true, false, true));
            assert_eq!(f.actions, [0, 3, 0]);
            f.pointer.reset();
            assert!(!f.frame(true, true, sample(true, false, true)).1);
        }
    }
}
