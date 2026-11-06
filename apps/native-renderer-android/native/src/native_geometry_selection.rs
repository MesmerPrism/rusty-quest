//! Build-locked resource selection, independent of runtime visibility controls.
//!
//! The owning builder admits the false build value only for the resolved
//! renderer.native_scene.particles_only feature. Missing selection preserves
//! existing visual routes. This does not disable OpenXR hand pointer tracking.

use crate::native_renderer_visual_options::NativeRendererRenderMode;

pub(crate) const fn hand_geometry_enabled() -> bool {
    !cfg!(native_hand_geometry_disabled)
}

pub(crate) fn validate_render_mode(mode: NativeRendererRenderMode) -> Result<(), String> {
    validate_selection(hand_geometry_enabled(), mode)
}

fn validate_selection(enabled: bool, mode: NativeRendererRenderMode) -> Result<(), String> {
    if !enabled && !matches!(mode, NativeRendererRenderMode::NativeWorldspaceIntegration) {
        return Err(
            "particles-only geometry selection requires native-worldspace-integration".into(),
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn particles_only_is_bound_to_the_native_scene_route() {
        assert!(
            validate_selection(false, NativeRendererRenderMode::NativeWorldspaceIntegration)
                .is_ok()
        );
        assert!(
            validate_selection(false, NativeRendererRenderMode::CustomStereoProjection).is_err()
        );
        assert!(validate_selection(true, NativeRendererRenderMode::CustomStereoProjection).is_ok());
    }
}
