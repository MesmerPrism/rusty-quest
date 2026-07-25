#![cfg_attr(not(any(target_os = "android", test)), allow(dead_code))]

include!(concat!(
    env!("OUT_DIR"),
    "/spatial_presentation_policy_build.rs"
));

const FINAL_LAYER_OVERRIDE: f32 = 0.0;
const LOCKED_PROJECTION_SCALE: f32 = 1.0;

pub(crate) fn presentation_layer_override(requested: f32) -> f32 {
    presentation_layer_override_for_policy(requested, SPATIAL_LOCKED_FINAL_PRESENTATION)
}

pub(crate) fn presentation_projection_scale(requested: f32) -> f32 {
    presentation_projection_scale_for_policy(requested, SPATIAL_LOCKED_FINAL_PRESENTATION)
}

pub(crate) fn presentation_distortion_phase_rate_hz(base_rate_hz: f32) -> f32 {
    distortion_phase_rate_hz_for_policy(base_rate_hz, SPATIAL_DISTORTION_SPEED_SCALE)
}

pub(crate) fn spatial_presentation_policy_marker_fields() -> String {
    format!(
        "lockedFinalPresentation={} appControlInputsEnabled={} forcedPrivateLayerOverride={} forcedProjectionScale={} distortionSpeedScale={:.6}",
        SPATIAL_LOCKED_FINAL_PRESENTATION,
        !SPATIAL_LOCKED_FINAL_PRESENTATION,
        if SPATIAL_LOCKED_FINAL_PRESENTATION {
            "0"
        } else {
            "none"
        },
        if SPATIAL_LOCKED_FINAL_PRESENTATION {
            "1"
        } else {
            "none"
        },
        SPATIAL_DISTORTION_SPEED_SCALE,
    )
}

fn presentation_layer_override_for_policy(requested: f32, locked: bool) -> f32 {
    if locked {
        FINAL_LAYER_OVERRIDE
    } else {
        requested
    }
}

fn presentation_projection_scale_for_policy(requested: f32, locked: bool) -> f32 {
    if locked {
        LOCKED_PROJECTION_SCALE
    } else {
        requested
    }
}

fn distortion_phase_rate_hz_for_policy(base_rate_hz: f32, speed_scale: f32) -> f32 {
    base_rate_hz.max(0.0) * speed_scale.clamp(0.0, 4.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn locked_policy_forces_final_and_scale_one() {
        assert_eq!(presentation_layer_override_for_policy(8.0, true), 0.0);
        assert_eq!(presentation_projection_scale_for_policy(0.8, true), 1.0);
    }

    #[test]
    fn interactive_policy_preserves_requested_values() {
        assert_eq!(presentation_layer_override_for_policy(5.0, false), 5.0);
        assert_eq!(presentation_projection_scale_for_policy(0.8, false), 0.8);
    }

    #[test]
    fn half_speed_policy_halves_the_native_phase_rate() {
        assert_eq!(distortion_phase_rate_hz_for_policy(0.5, 0.5), 0.25);
    }
}
