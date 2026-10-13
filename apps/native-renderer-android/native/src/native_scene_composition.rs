//! Projection alpha policy for the opt-in scene renderer; environment sampling is separate.

/// The scene mode gains a transparent background only with an explicit native passthrough
/// request. Existing modes keep their existing policy, including stimulus-volume exclusions.
pub(crate) const fn projection_alpha_blend(
    scene_depth_enabled: bool,
    native_passthrough_requested: bool,
    existing_mode_alpha_blend: bool,
) -> bool {
    existing_mode_alpha_blend || (scene_depth_enabled && native_passthrough_requested)
}

/// Transparent passthrough takes priority over a panel's opaque diagnostic background.
pub(crate) const fn projection_clear_color(
    alpha_blend: bool,
    opaque_background: [f32; 4],
) -> [f32; 4] {
    if alpha_blend {
        [0.0, 0.0, 0.0, 0.0]
    } else {
        opaque_background
    }
}

#[cfg(test)]
mod tests {
    use super::{projection_alpha_blend, projection_clear_color};

    #[test]
    fn public_scene_without_passthrough_stays_opaque() {
        assert!(!projection_alpha_blend(true, false, false));
        assert_eq!(
            projection_clear_color(false, [0.01, 0.02, 0.03, 1.0]),
            [0.01, 0.02, 0.03, 1.0]
        );
    }

    #[test]
    fn explicit_scene_passthrough_enables_projection_alpha_and_transparent_clear() {
        // Both call sites use this same policy: composition source-alpha and clear alpha0.
        assert!(projection_alpha_blend(true, true, false));
        assert_eq!(
            projection_clear_color(
                projection_alpha_blend(true, true, false),
                [0.01, 0.02, 0.03, 1.0]
            ),
            [0.0; 4]
        );
    }

    #[test]
    fn other_modes_do_not_gain_alpha_from_a_depth_provider_request() {
        assert!(!projection_alpha_blend(false, true, false));
        assert!(!projection_alpha_blend(false, false, false));
    }

    #[test]
    fn existing_transparent_modes_keep_their_policy() {
        assert!(projection_alpha_blend(false, true, true));
        assert!(projection_alpha_blend(false, false, true));
    }
}
