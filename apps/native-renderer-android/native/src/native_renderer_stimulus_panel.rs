//! Same-APK 2D panel candidate import for the native stimulus route.
//!
//! The panel is a low-rate requester. Rust remains the validator and the
//! effective runtime authority.

use std::collections::BTreeMap;
#[cfg(target_os = "android")]
use std::path::Path;

#[cfg(any(target_os = "android", test))]
use serde_json::json;
use serde_json::Value;

use crate::{
    native_renderer_options::{
        NativeRendererRenderMode, NativeRendererRuntimeOptions,
        PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION, PROP_STIMULUS_VOLUME_COMPOSITION,
        PROP_STIMULUS_VOLUME_ENABLED, PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING,
        PROP_STIMULUS_VOLUME_PATTERN_FAMILY, PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED,
        PROP_STIMULUS_VOLUME_RANDOMIZE_MAX_HZ, PROP_STIMULUS_VOLUME_RANDOMIZE_MIN_HZ,
        PROP_STIMULUS_VOLUME_RAYMARCH_SAMPLES, PROP_STIMULUS_VOLUME_RENDER_TARGET,
        PROP_STIMULUS_VOLUME_SAFETY_ACK,
    },
    native_renderer_stimulus_volume_options::{
        NativeStimulusVolumeCompositionMode, NativeStimulusVolumeSettings,
    },
    projection_target_state::ProjectionTargetSettings,
};

pub(crate) const CANDIDATE_FILE: &str = "stimulus_volume_candidate.json";
pub(crate) const STATUS_FILE: &str = "stimulus_volume_status.json";
pub(crate) const PROFILE_SCHEMA: &str = "rusty.quest.stimulus_volume.profile.v1";
pub(crate) const STATUS_SCHEMA: &str = "rusty.quest.stimulus_volume.apply_status.v1";

#[derive(Clone, Debug)]
pub(crate) struct StimulusPanelCandidate {
    pub(crate) revision: i64,
    pub(crate) render_mode: NativeRendererRenderMode,
    pub(crate) settings: NativeStimulusVolumeSettings,
}

impl StimulusPanelCandidate {
    pub(crate) fn apply_to(
        self,
        mut options: NativeRendererRuntimeOptions,
    ) -> NativeRendererRuntimeOptions {
        options.render_mode = self.render_mode;
        options.stimulus_volume_settings = self.settings;
        options.projection_target_settings =
            ProjectionTargetSettings::disabled_for_volume_only_route();
        options
    }
}

pub(crate) fn parse_candidate_json(text: &str) -> Result<StimulusPanelCandidate, String> {
    let value: Value = serde_json::from_str(text).map_err(|error| format!("json_parse:{error}"))?;
    let schema = string_at(&value, &["schema"]).unwrap_or_default();
    if schema != PROFILE_SCHEMA {
        return Err(format!("schema_mismatch:{schema}"));
    }

    let revision = value
        .get("revision")
        .and_then(Value::as_i64)
        .unwrap_or(0)
        .max(0);
    let stimulus = object_value_at(&value, &["stimulus"])?;
    let safety = object_value_at(&value, &["safety"])?;
    let apply = value.get("apply").and_then(Value::as_object);
    if let Some(mode) = apply
        .and_then(|object| object.get("mode"))
        .and_then(Value::as_str)
    {
        match mode {
            "validate-only" | "stage" | "apply-on-next-safe-frame" => {}
            _ => return Err(format!("unsupported_apply_mode:{mode}")),
        }
    }

    let enabled_requested = bool_at(stimulus, &["enabled_requested"]).unwrap_or(false);
    let safety_ack = bool_at(safety, &["photosensitive_risk_ack"]).unwrap_or(false);
    if enabled_requested && !safety_ack {
        return Err("safety_ack_missing".to_string());
    }
    if bool_at(safety, &["allow_autostart"]).unwrap_or(false) {
        return Err("allow_autostart_not_supported".to_string());
    }
    if !bool_at(safety, &["requires_user_activation"]).unwrap_or(true) {
        return Err("user_activation_required".to_string());
    }

    let composition = string_at(stimulus, &["composition"])
        .unwrap_or_else(|| "opaque-black-projection".to_string());
    let render_mode = match composition.as_str() {
        "opaque-black-projection" | "solid-black" | "black" => {
            NativeRendererRenderMode::SolidBlackStimulusVolume
        }
        "alpha-over-native-passthrough" | "passthrough-alpha" => {
            NativeRendererRenderMode::NativePassthroughStimulusVolume
        }
        _ => return Err(format!("unsupported_composition:{composition}")),
    };

    let render_target =
        string_at(stimulus, &["render_target"]).unwrap_or_else(|| "512x512x2-rgba16f".to_string());
    validate_token(
        "render_target",
        &render_target,
        &[
            "512x512x2-rgba16f",
            "512x512x2-rgba8-unorm",
            "768x768x2-rgba16f",
            "1024x1024x2-rgba16f",
        ],
    )?;
    let pattern_family = string_at(stimulus, &["pattern_family"])
        .unwrap_or_else(|| "randomized-trevor-vocabulary".to_string());
    validate_token(
        "pattern_family",
        &pattern_family,
        &[
            "randomized-trevor-vocabulary",
            "trevor-mix",
            "stripes",
            "ripples",
            "rays",
            "checker",
            "spiral",
            "noise-field",
        ],
    )?;

    let raymarch_samples = number_at(stimulus, &["raymarch_samples"])
        .unwrap_or(6.0)
        .round() as i64;
    if !(1..=48).contains(&raymarch_samples) {
        return Err(format!("raymarch_samples_out_of_range:{raymarch_samples}"));
    }
    let central_fov = number_at(stimulus, &["central_fov_fraction"]).unwrap_or(0.78);
    if !(0.45..=1.0).contains(&central_fov) {
        return Err(format!(
            "central_fov_fraction_out_of_range:{central_fov:.3}"
        ));
    }
    let smoothing = number_at(stimulus, &["gradient_smoothing"]).unwrap_or(0.65);
    if !(0.0..=1.0).contains(&smoothing) {
        return Err(format!("gradient_smoothing_out_of_range:{smoothing:.3}"));
    }

    let randomize = object_value_at(stimulus, &["randomize"])?;
    let randomize_enabled = bool_at(randomize, &["enabled"]).unwrap_or(true);
    let min_hz = number_at(randomize, &["min_hz"]).unwrap_or(3.0);
    let max_hz = number_at(randomize, &["max_hz"]).unwrap_or(40.0);
    if min_hz < 3.0 || max_hz > 40.0 || min_hz > max_hz {
        return Err(format!("randomize_hz_out_of_range:{min_hz:.3}-{max_hz:.3}"));
    }

    let mut properties = BTreeMap::<&str, String>::new();
    properties.insert(PROP_STIMULUS_VOLUME_ENABLED, enabled_requested.to_string());
    properties.insert(PROP_STIMULUS_VOLUME_COMPOSITION, composition);
    properties.insert(PROP_STIMULUS_VOLUME_RENDER_TARGET, render_target);
    properties.insert(
        PROP_STIMULUS_VOLUME_RAYMARCH_SAMPLES,
        raymarch_samples.to_string(),
    );
    properties.insert(
        PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION,
        format!("{central_fov:.4}"),
    );
    properties.insert(
        PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING,
        format!("{smoothing:.4}"),
    );
    properties.insert(PROP_STIMULUS_VOLUME_PATTERN_FAMILY, pattern_family);
    properties.insert(
        PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED,
        randomize_enabled.to_string(),
    );
    properties.insert(
        PROP_STIMULUS_VOLUME_RANDOMIZE_MIN_HZ,
        format!("{min_hz:.4}"),
    );
    properties.insert(
        PROP_STIMULUS_VOLUME_RANDOMIZE_MAX_HZ,
        format!("{max_hz:.4}"),
    );
    properties.insert(PROP_STIMULUS_VOLUME_SAFETY_ACK, safety_ack.to_string());

    let mut settings = NativeStimulusVolumeSettings::from_property_lookup(
        |name| properties.get(name).cloned(),
        render_mode,
    );
    settings.composition = match render_mode {
        NativeRendererRenderMode::NativePassthroughStimulusVolume => {
            NativeStimulusVolumeCompositionMode::AlphaOverNativePassthrough
        }
        _ => NativeStimulusVolumeCompositionMode::OpaqueBlackProjection,
    };

    Ok(StimulusPanelCandidate {
        revision,
        render_mode,
        settings,
    })
}

#[cfg(target_os = "android")]
pub(crate) fn apply_app_private_candidate(
    app: &android_activity::AndroidApp,
    options: NativeRendererRuntimeOptions,
) -> NativeRendererRuntimeOptions {
    let Some(data_path) = app.internal_data_path() else {
        crate::marker(
            "stimulus-panel",
            "status=unavailable reason=missing-internal-data-path",
        );
        return options;
    };
    let candidate_path = data_path.join(CANDIDATE_FILE);
    if !candidate_path.exists() {
        crate::marker(
            "stimulus-panel",
            format!(
                "status=missing transport=app-private-file candidateFile={}",
                crate::sanitize(&path_marker(&candidate_path))
            ),
        );
        return options;
    }
    let text = match std::fs::read_to_string(&candidate_path) {
        Ok(text) => text,
        Err(error) => {
            let reason = format!("read_failed:{error}");
            write_status(&data_path, "rejected", 0, &reason, None);
            crate::marker(
                "stimulus-panel",
                format!("status=rejected reason={}", crate::sanitize(&reason)),
            );
            return options;
        }
    };
    match parse_candidate_json(&text) {
        Ok(candidate) => {
            let revision = candidate.revision;
            let render_mode = candidate.render_mode.marker_value();
            let pattern_family = candidate.settings.pattern_family.marker_value();
            let updated = candidate.apply_to(options);
            write_status(&data_path, "applied", revision, "none", Some(&updated));
            crate::marker(
                "stimulus-panel",
                format!(
                    "status=applied transport=app-private-file schema={} candidateRevision={} effectiveRevision={} renderMode={} activePatternFamily={} {}",
                    PROFILE_SCHEMA,
                    revision,
                    revision,
                    render_mode,
                    pattern_family,
                    updated.stimulus_volume_settings.marker_fields()
                ),
            );
            updated
        }
        Err(reason) => {
            write_status(&data_path, "rejected", 0, &reason, None);
            crate::marker(
                "stimulus-panel",
                format!(
                    "status=rejected transport=app-private-file schema={} reason={}",
                    PROFILE_SCHEMA,
                    crate::sanitize(&reason)
                ),
            );
            options
        }
    }
}

#[cfg(target_os = "android")]
fn write_status(
    data_path: &Path,
    status: &str,
    revision: i64,
    reason: &str,
    options: Option<&NativeRendererRuntimeOptions>,
) {
    let effective_revision = if status == "applied" { revision } else { 0 };
    let body = json!({
        "schema": STATUS_SCHEMA,
        "status": status,
        "candidate_revision": revision,
        "effective_revision": effective_revision,
        "rejection_code": if reason == "none" { Value::Null } else { Value::String(reason.to_string()) },
        "transport": "app_private_file",
        "active_pattern_family": options
            .map(|options| options.stimulus_volume_settings.pattern_family.marker_value())
            .unwrap_or("none"),
        "active_randomize": options.map(|options| json!({
            "enabled": options.stimulus_volume_settings.randomize_enabled,
            "min_hz": options.stimulus_volume_settings.randomize_min_hz,
            "max_hz": options.stimulus_volume_settings.randomize_max_hz
        })).unwrap_or(Value::Null),
        "safety_gate": options
            .map(|options| if options.stimulus_volume_settings.active() {
                "acknowledged-active"
            } else if options.stimulus_volume_settings.enabled {
                "render-black-until-safety-ack"
            } else {
                "disabled"
            })
            .unwrap_or("not-applied")
    });
    let _ = std::fs::write(data_path.join(STATUS_FILE), body.to_string());
}

#[cfg(target_os = "android")]
fn path_marker(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}

fn object_value_at<'a>(value: &'a Value, path: &[&str]) -> Result<&'a Value, String> {
    value_at(value, path)
        .filter(|value| value.is_object())
        .ok_or_else(|| format!("missing_object:{}", path.join(".")))
}

fn string_at(value: &Value, path: &[&str]) -> Option<String> {
    value_at(value, path)
        .and_then(Value::as_str)
        .map(|value| value.trim().to_ascii_lowercase())
        .filter(|value| !value.is_empty())
}

fn bool_at(value: &Value, path: &[&str]) -> Option<bool> {
    value_at(value, path).and_then(Value::as_bool)
}

fn number_at(value: &Value, path: &[&str]) -> Option<f64> {
    value_at(value, path).and_then(Value::as_f64)
}

fn value_at<'a>(value: &'a Value, path: &[&str]) -> Option<&'a Value> {
    let mut current = value;
    for key in path {
        current = current.get(*key)?;
    }
    Some(current)
}

fn validate_token(name: &str, value: &str, allowed: &[&str]) -> Result<(), String> {
    if allowed.iter().any(|allowed| value == *allowed) {
        Ok(())
    } else {
        Err(format!("unsupported_{name}:{value}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::native_renderer_options::{
        NativeStimulusVolumePatternFamily, NativeStimulusVolumeRenderTarget,
    };

    fn valid_candidate() -> String {
        json!({
            "schema": PROFILE_SCHEMA,
            "revision": 7,
            "source": {
                "surface": "same_apk_panel",
                "transport": "app_private_file"
            },
            "safety": {
                "photosensitive_risk_ack": true,
                "requires_user_activation": true,
                "allow_autostart": false
            },
            "stimulus": {
                "enabled_requested": true,
                "composition": "opaque-black-projection",
                "render_target": "768x768x2-rgba16f",
                "raymarch_samples": 12,
                "central_fov_fraction": 0.78,
                "gradient_smoothing": 0.65,
                "pattern_family": "spiral",
                "randomize": {
                    "enabled": true,
                    "min_hz": 4.0,
                    "max_hz": 30.0
                }
            },
            "apply": {
                "mode": "stage"
            }
        })
        .to_string()
    }

    #[test]
    fn parses_valid_panel_candidate() {
        let candidate = parse_candidate_json(&valid_candidate()).expect("candidate parses");
        assert_eq!(candidate.revision, 7);
        assert_eq!(
            candidate.render_mode,
            NativeRendererRenderMode::SolidBlackStimulusVolume
        );
        assert!(candidate.settings.enabled);
        assert!(candidate.settings.safety_acknowledged);
        assert_eq!(candidate.settings.randomize_min_hz, 4.0);
        assert_eq!(candidate.settings.randomize_max_hz, 30.0);
        assert_eq!(
            candidate.settings.render_target,
            NativeStimulusVolumeRenderTarget::Rgba16f768Stereo
        );
        assert_eq!(
            candidate.settings.pattern_family,
            NativeStimulusVolumePatternFamily::Spiral
        );
    }

    #[test]
    fn rejects_active_candidate_without_safety_ack() {
        let mut value: Value = serde_json::from_str(&valid_candidate()).unwrap();
        value["safety"]["photosensitive_risk_ack"] = Value::Bool(false);
        let error = parse_candidate_json(&value.to_string()).unwrap_err();
        assert_eq!(error, "safety_ack_missing");
    }

    #[test]
    fn rejects_out_of_range_randomize_hz() {
        let mut value: Value = serde_json::from_str(&valid_candidate()).unwrap();
        value["stimulus"]["randomize"]["max_hz"] = Value::from(48.0);
        let error = parse_candidate_json(&value.to_string()).unwrap_err();
        assert!(error.starts_with("randomize_hz_out_of_range"));
    }

    #[test]
    fn rejects_unknown_pattern_family() {
        let mut value: Value = serde_json::from_str(&valid_candidate()).unwrap();
        value["stimulus"]["pattern_family"] = Value::from("unexpected");
        let error = parse_candidate_json(&value.to_string()).unwrap_err();
        assert_eq!(error, "unsupported_pattern_family:unexpected");
    }
}
