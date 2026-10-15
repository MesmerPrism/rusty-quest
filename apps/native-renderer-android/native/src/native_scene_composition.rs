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

/// Explicit opaque scene selection outranks a mesh demo's neutral background.
pub(crate) fn projection_opaque_background(
    solid_black: bool,
    stimulus_volume: bool,
    panel_background: Option<[f32; 4]>,
    eye_index: usize,
) -> [f32; 4] {
    if solid_black || stimulus_volume {
        [0.0, 0.0, 0.0, 1.0]
    } else if let Some(background) = panel_background {
        background
    } else if eye_index == 0 {
        [0.012, 0.030, 0.038, 1.0]
    } else {
        [0.034, 0.016, 0.050, 1.0]
    }
}

#[cfg(test)]
mod tests {
    use super::{projection_alpha_blend, projection_clear_color, projection_opaque_background};

    #[test]
    fn explicit_black_outranks_panel_neutral_and_passthrough_still_clears_transparent() {
        let neutral = [0.01, 0.02, 0.03, 1.0];
        let black = projection_opaque_background(true, false, Some(neutral), 0);
        assert_eq!(black, [0.0, 0.0, 0.0, 1.0]);
        assert_eq!(
            projection_opaque_background(false, false, Some(neutral), 0),
            neutral
        );
        assert_eq!(projection_clear_color(true, black), [0.0; 4]);
    }

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
