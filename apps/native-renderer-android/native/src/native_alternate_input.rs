//! Bounded joint-derived pointer for exclusive hands-or-controllers worldspace input.
//! This is an app pointer, not XR_FB_hand_tracking_aim or simultaneous input proof.

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
    })
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
                if hand.pinch_distance >= 0.04 {
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
            origin: [0.; 3],
            direction: [0., 0., -1.],
            pinch_distance: distance,
            right: true,
        }
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
}
