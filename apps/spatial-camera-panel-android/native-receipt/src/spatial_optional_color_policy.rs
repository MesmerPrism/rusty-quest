//! Admission for optional camera color processing; source selection remains app-owned.

/// Negotiated provider ABI1 draw modes. These never reach a legacy provider.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CameraColorStage {
    Inactive,
    Capture,
    Display,
}

impl CameraColorStage {
    pub(crate) fn provider_mode(self) -> f32 {
        match self {
            Self::Inactive => 0.0,
            Self::Capture => -2.0,
            Self::Display => -3.0,
        }
    }
}

pub(crate) fn placement_supported(
    placement: i32,
    source_color_active: bool,
    optional_shader_compiled: bool,
    camera_color_abi: u32,
) -> bool {
    match placement {
        0 => true,
        1 | 2 => source_color_active,
        3 => optional_shader_compiled && camera_color_abi == 1,
        _ => false,
    }
}

/// Enabled blends require both exact source inputs. A disabled blend still
/// permits a selected standalone camera. Diagnostic masks never enter color.
pub(crate) fn camera_input_ready(mask_word: u32, available: [bool; 2], center_origin: u32) -> bool {
    if mask_word & 0x4000_0000 != 0 {
        return false;
    }
    if mask_word & 0x8000_0000 != 0 {
        available.into_iter().all(|ready| ready)
    } else {
        available
            .get(center_origin as usize)
            .copied()
            .unwrap_or(false)
    }
}

pub(crate) fn source_layer_supports_color(layer: f32) -> bool {
    layer.is_finite() && (layer <= 0.0 || layer == 8.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standalone_sources_do_not_require_a_blend_or_the_other_camera() {
        assert!(camera_input_ready(0, [true, false], 0), "pure Own");
        assert!(camera_input_ready(0, [false, true], 1), "pure Peer");
        assert!(camera_input_ready(0, [true, true], 1), "unmixed selection");
        assert!(!camera_input_ready(0, [false, false], 0), "no source");
        assert!(
            !camera_input_ready(0, [true, false], 1),
            "missing selected Peer"
        );
        assert!(
            !camera_input_ready(0, [false, true], 0),
            "missing selected Own"
        );
        assert!(!camera_input_ready(0, [true, true], 2), "invalid center");
    }

    #[test]
    fn blends_and_diagnostics_retain_their_input_fences() {
        assert!(camera_input_ready(0x8000_0000, [true, true], 0));
        assert!(!camera_input_ready(0x8000_0000, [true, false], 0));
        assert!(!camera_input_ready(0x8000_0000, [false, true], 1));
        assert!(!camera_input_ready(0xc000_0000, [true, true], 0));
        assert!(!camera_input_ready(0x4000_0000, [true, true], 0));
    }

    #[test]
    fn raw_camera_and_full_stack_accept_color_without_enabling_guide_diagnostics() {
        assert!(source_layer_supports_color(0.0));
        assert!(source_layer_supports_color(-1.0));
        assert!(source_layer_supports_color(8.0), "Raw8 is camera content");
        for layer in 1..8 {
            assert!(!source_layer_supports_color(layer as f32));
        }
        assert!(!source_layer_supports_color(f32::NAN));
        assert!(!source_layer_supports_color(f32::INFINITY));
    }

    #[test]
    fn legacy_providers_refuse_combined_without_changing_source_or_disabled_routes() {
        assert!(!placement_supported(3, true, true, 0));
        assert!(placement_supported(3, true, true, 1));
        assert!(!placement_supported(3, true, false, 1));
        assert!(!placement_supported(3, true, true, 7));
        assert!(placement_supported(0, false, false, 0));
        assert!(placement_supported(1, true, true, 0));
        assert!(placement_supported(2, true, true, 0));
        assert!(!placement_supported(1, false, true, 1));
        assert!(!placement_supported(4, true, true, 1));
    }
}
