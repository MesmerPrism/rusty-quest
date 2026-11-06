//! Select live poses by the resident bind rig's hand, never by tracking order.

pub(super) fn frame_for_rig<'a, T>(
    left: Option<&'a T>,
    right: Option<&'a T>,
    rig_handedness: &str,
) -> Option<&'a T> {
    match rig_handedness {
        "left" => left,
        "right" => right,
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::frame_for_rig;

    #[test]
    fn asymmetric_live_frames_stay_with_their_resident_bind_rigs() {
        let left = [1.0, 2.0, 3.0];
        let right = [-4.0, 5.0, 6.0];
        assert!(std::ptr::eq(
            frame_for_rig(Some(&left), Some(&right), "left").unwrap(),
            &left
        ));
        assert!(std::ptr::eq(
            frame_for_rig(Some(&left), Some(&right), "right").unwrap(),
            &right
        ));
    }

    #[test]
    fn right_only_tracking_never_skins_the_left_rig() {
        let right = [4.0, 5.0, 6.0];
        assert_eq!(frame_for_rig(None, Some(&right), "left"), None);
        assert_eq!(frame_for_rig(None, Some(&right), "right"), Some(&right));
    }

    #[test]
    fn left_only_tracking_and_unknown_rigs_fail_closed() {
        let left = [1.0, 2.0, 3.0];
        assert_eq!(frame_for_rig(Some(&left), None, "right"), None);
        assert_eq!(frame_for_rig(Some(&left), None, "left"), Some(&left));
        assert_eq!(frame_for_rig(Some(&left), Some(&left), "unknown"), None);
        assert_eq!(frame_for_rig::<[f32; 3]>(None, None, "right"), None);
    }
}
