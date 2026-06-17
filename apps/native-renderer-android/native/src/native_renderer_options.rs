//! Runtime property options for the native renderer.
//!
//! This module keeps Android property parsing separate from the OpenXR/Vulkan
//! frame loop so replay-proof, live-hand, and SDF visual modes stay testable.

pub(crate) const PROP_ENABLE_SDF_VISUAL: &str =
    "debug.rustyquest.native_renderer.sdf.visual.enabled";
pub(crate) const PROP_SDF_UPDATE_PERIOD_FRAMES: &str =
    "debug.rustyquest.native_renderer.sdf.update_period_frames";
pub(crate) const PROP_REPLAY_VISUAL_PROOF_ENABLED: &str =
    "debug.rustyquest.native_renderer.replay.visual_proof.enabled";
pub(crate) const PROP_HAND_MESH_INPUT_SOURCE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.input.source";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CompactHandInputSourceMode {
    Auto,
    RecordedReplay,
    LiveMeta,
}

impl CompactHandInputSourceMode {
    pub(crate) fn from_property(value: Option<String>, replay_visual_proof_enabled: bool) -> Self {
        let normalized = value
            .as_deref()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        match normalized.as_str() {
            "recorded" | "recorded-replay" | "replay" => Self::RecordedReplay,
            "live" | "live-meta" | "openxr" | "live-meta-openxr-hand-tracking" => Self::LiveMeta,
            "auto" => Self::Auto,
            _ if replay_visual_proof_enabled => Self::RecordedReplay,
            _ => Self::Auto,
        }
    }

    pub(crate) fn marker_value(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::RecordedReplay => "recorded-replay",
            Self::LiveMeta => "live-meta-openxr-hand-tracking",
        }
    }

    pub(crate) fn selects_live_frame(self) -> bool {
        !matches!(self, Self::RecordedReplay)
    }

    pub(crate) fn allows_recorded_fallback(self) -> bool {
        !matches!(self, Self::LiveMeta)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct HandMeshVisualDiagnosticSettings {
    pub(crate) enabled: bool,
    pub(crate) offset_uv: [f32; 2],
    pub(crate) alpha: f32,
}

impl HandMeshVisualDiagnosticSettings {
    pub(crate) fn new(enabled: bool, offset_uv: [f32; 2], alpha: f32) -> Self {
        Self {
            enabled,
            offset_uv: [
                offset_uv[0].clamp(-0.45, 0.45),
                offset_uv[1].clamp(-0.45, 0.45),
            ],
            alpha: alpha.clamp(0.20, 1.0),
        }
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "handMeshVisualDiagnosticEnabled={} handMeshVisualDiagnosticOffsetUv={:.3},{:.3} handMeshVisualDiagnosticAlpha={:.2} handMeshVisualDiagnosticScale=1.35",
            self.enabled, self.offset_uv[0], self.offset_uv[1], self.alpha
        )
    }

    pub(crate) fn push_params(&self) -> [f32; 4] {
        if self.enabled {
            [self.offset_uv[0], self.offset_uv[1], self.alpha, 1.0]
        } else {
            [0.0, 0.0, self.alpha, 0.0]
        }
    }
}

impl Default for HandMeshVisualDiagnosticSettings {
    fn default() -> Self {
        Self::new(false, [0.0, 0.0], 0.78)
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct NativeRendererRuntimeOptions {
    pub(crate) replay_visual_proof_enabled: bool,
    pub(crate) compact_hand_input_source_mode: CompactHandInputSourceMode,
    pub(crate) sdf_visual_enabled: bool,
    pub(crate) sdf_update_period_frames: u64,
    pub(crate) hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings,
}

impl NativeRendererRuntimeOptions {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let replay_visual_proof_enabled =
            bool_value(lookup(PROP_REPLAY_VISUAL_PROOF_ENABLED), false);
        let compact_hand_input_source_mode = CompactHandInputSourceMode::from_property(
            lookup(PROP_HAND_MESH_INPUT_SOURCE),
            replay_visual_proof_enabled,
        );
        let sdf_visual_enabled =
            replay_visual_proof_enabled || bool_value(lookup(PROP_ENABLE_SDF_VISUAL), false);
        let sdf_update_period_frames = u64_value(lookup(PROP_SDF_UPDATE_PERIOD_FRAMES), 2, 1, 120);
        let diagnostic_enabled = replay_visual_proof_enabled
            || bool_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED), false);
        let diagnostic_offset_uv = f32_pair_value(
            lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV),
            [0.12, -0.08],
        );
        let diagnostic_alpha = f32_value(lookup(PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA), 0.86);

        Self {
            replay_visual_proof_enabled,
            compact_hand_input_source_mode,
            sdf_visual_enabled,
            sdf_update_period_frames,
            hand_mesh_visual_diagnostic_settings: HandMeshVisualDiagnosticSettings::new(
                diagnostic_enabled,
                diagnostic_offset_uv,
                diagnostic_alpha,
            ),
        }
    }

    #[cfg(target_os = "android")]
    pub(crate) fn load_from_android_properties() -> Self {
        Self::from_property_lookup(android_property)
    }
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

fn bool_value(value: Option<String>, default_value: bool) -> bool {
    value.map_or(default_value, |value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on"
        )
    })
}

fn u64_value(value: Option<String>, default_value: u64, min_value: u64, max_value: u64) -> u64 {
    value
        .and_then(|value| value.trim().parse::<u64>().ok())
        .filter(|value| *value >= min_value)
        .unwrap_or(default_value)
        .min(max_value)
}

fn f32_value(value: Option<String>, default_value: f32) -> f32 {
    value
        .and_then(|value| value.trim().parse::<f32>().ok())
        .unwrap_or(default_value)
}

fn f32_pair_value(value: Option<String>, default_value: [f32; 2]) -> [f32; 2] {
    let Some(value) = value else {
        return default_value;
    };
    let parts = value
        .split(|character: char| character == ',' || character == ';' || character.is_whitespace())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    if parts.len() != 2 {
        return default_value;
    }
    let Some(x) = parts[0].trim().parse::<f32>().ok() else {
        return default_value;
    };
    let Some(y) = parts[1].trim().parse::<f32>().ok() else {
        return default_value;
    };
    [x, y]
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use super::{
        CompactHandInputSourceMode, NativeRendererRuntimeOptions, PROP_ENABLE_SDF_VISUAL,
        PROP_HAND_MESH_INPUT_SOURCE, PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA,
        PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED, PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV,
        PROP_REPLAY_VISUAL_PROOF_ENABLED, PROP_SDF_UPDATE_PERIOD_FRAMES,
    };

    fn options_from(values: &[(&str, &str)]) -> NativeRendererRuntimeOptions {
        let values = values.iter().copied().collect::<BTreeMap<_, _>>();
        NativeRendererRuntimeOptions::from_property_lookup(|name| {
            values.get(name).map(|value| (*value).to_owned())
        })
    }

    #[test]
    fn replay_visual_proof_forces_recorded_diagnostic_and_sdf() {
        let options = options_from(&[(PROP_REPLAY_VISUAL_PROOF_ENABLED, "true")]);
        assert!(options.replay_visual_proof_enabled);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::RecordedReplay
        );
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert!(!options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn explicit_live_source_overrides_replay_proof_source_selection() {
        let options = options_from(&[
            (PROP_REPLAY_VISUAL_PROOF_ENABLED, "true"),
            (PROP_HAND_MESH_INPUT_SOURCE, "live-meta"),
        ]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(options.sdf_visual_enabled);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn canonical_live_source_value_selects_live_without_replay_fallback() {
        let options = options_from(&[(
            PROP_HAND_MESH_INPUT_SOURCE,
            "live-meta-openxr-hand-tracking",
        )]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::LiveMeta
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(!options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
    }

    #[test]
    fn auto_mode_defaults_to_recorded_fallback_without_diagnostics() {
        let options = options_from(&[(PROP_HAND_MESH_INPUT_SOURCE, "auto")]);
        assert_eq!(
            options.compact_hand_input_source_mode,
            CompactHandInputSourceMode::Auto
        );
        assert!(options.compact_hand_input_source_mode.selects_live_frame());
        assert!(options
            .compact_hand_input_source_mode
            .allows_recorded_fallback());
        assert!(!options.sdf_visual_enabled);
        assert!(!options.hand_mesh_visual_diagnostic_settings.enabled);
    }

    #[test]
    fn sdf_and_diagnostic_values_parse_and_clamp() {
        let options = options_from(&[
            (PROP_ENABLE_SDF_VISUAL, "on"),
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "999"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED, "yes"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "9.0,-9.0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "4.0"),
        ]);
        assert!(options.sdf_visual_enabled);
        assert_eq!(options.sdf_update_period_frames, 120);
        assert!(options.hand_mesh_visual_diagnostic_settings.enabled);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.45, -0.45]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 1.0);
    }

    #[test]
    fn invalid_values_keep_defaults() {
        let options = options_from(&[
            (PROP_SDF_UPDATE_PERIOD_FRAMES, "0"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV, "bad"),
            (PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA, "bad"),
        ]);
        assert_eq!(options.sdf_update_period_frames, 2);
        assert_eq!(
            options.hand_mesh_visual_diagnostic_settings.offset_uv,
            [0.12, -0.08]
        );
        assert_eq!(options.hand_mesh_visual_diagnostic_settings.alpha, 0.86);
    }
}
