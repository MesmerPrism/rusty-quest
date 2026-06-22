//! Same-APK 2D panel candidate import for the native stimulus route.
//!
//! The panel is a low-rate requester. Rust remains the validator and the
//! effective runtime authority.

use std::collections::BTreeMap;
#[cfg(target_os = "android")]
use std::path::Path;
#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};

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
        NativeStimulusVolumeStartupDynamics,
    },
    projection_target_state::ProjectionTargetSettings,
};

pub(crate) const CANDIDATE_FILE: &str = "stimulus_volume_candidate.json";
pub(crate) const STATUS_FILE: &str = "stimulus_volume_status.json";
pub(crate) const PROFILE_SCHEMA: &str = "rusty.quest.stimulus_volume.profile.v1";
pub(crate) const STATUS_SCHEMA: &str = "rusty.quest.stimulus_volume.apply_status.v1";
pub(crate) const PRIVATE_LAYER_SELECTION_SCHEMA: &str =
    "rusty.quest.native_renderer.private_layer_selection.v1";
pub(crate) const PRIVATE_LAYER_SELECTION_STATUS_SCHEMA: &str =
    "rusty.quest.native_renderer.private_layer_selection_status.v1";
pub(crate) const PRIVATE_LAYER_SELECTION_STATUS_FILE: &str = "private_layer_selection_status.json";

#[derive(Clone, Debug)]
pub(crate) struct StimulusPanelCandidate {
    pub(crate) revision: i64,
    pub(crate) render_mode: NativeRendererRenderMode,
    pub(crate) settings: NativeStimulusVolumeSettings,
}

#[derive(Clone, Debug)]
pub(crate) struct PrivateLayerPanelSelection {
    pub(crate) revision: i64,
    pub(crate) layer_override: f32,
    pub(crate) layer_label: String,
}

#[cfg(target_os = "android")]
static LIVE_CANDIDATE_QUEUE: OnceLock<Mutex<Option<StimulusPanelCandidate>>> = OnceLock::new();
#[cfg(target_os = "android")]
static LIVE_PRIVATE_LAYER_SELECTION_QUEUE: OnceLock<Mutex<Option<PrivateLayerPanelSelection>>> =
    OnceLock::new();

#[cfg(target_os = "android")]
#[derive(Clone, Copy, Debug)]
struct LiveQueueOutcome {
    revision: i64,
    overwrote_pending: bool,
}

#[cfg(target_os = "android")]
fn live_candidate_queue() -> &'static Mutex<Option<StimulusPanelCandidate>> {
    LIVE_CANDIDATE_QUEUE.get_or_init(|| Mutex::new(None))
}

#[cfg(target_os = "android")]
fn live_private_layer_selection_queue() -> &'static Mutex<Option<PrivateLayerPanelSelection>> {
    LIVE_PRIVATE_LAYER_SELECTION_QUEUE.get_or_init(|| Mutex::new(None))
}

#[cfg(target_os = "android")]
pub(crate) fn take_live_candidate() -> Option<StimulusPanelCandidate> {
    let mut queue = live_candidate_queue().lock().ok()?;
    queue.take()
}

#[cfg(target_os = "android")]
pub(crate) fn take_live_private_layer_selection() -> Option<PrivateLayerPanelSelection> {
    let mut queue = live_private_layer_selection_queue().lock().ok()?;
    queue.take()
}

#[cfg(target_os = "android")]
fn queue_live_candidate(text: &str) -> Result<LiveQueueOutcome, String> {
    let candidate = parse_candidate_json(text)?;
    let revision = candidate.revision;
    let pattern_family = candidate.settings.pattern_family.marker_value();
    let mut queue = live_candidate_queue()
        .lock()
        .map_err(|_| "live_queue_poisoned".to_string())?;
    let overwrote_pending = queue.replace(candidate).is_some();
    crate::marker(
        "stimulus-panel",
        format!(
            "status=live-queued transport=jni-live-queue schema={} candidateRevision={} activePatternFamily={} overwrotePendingCandidate={}",
            PROFILE_SCHEMA, revision, pattern_family, overwrote_pending
        ),
    );
    Ok(LiveQueueOutcome {
        revision,
        overwrote_pending,
    })
}

#[cfg(target_os = "android")]
fn queue_live_private_layer_selection(text: &str) -> Result<LiveQueueOutcome, String> {
    let selection = parse_private_layer_selection_json(text)?;
    let revision = selection.revision;
    let layer_override = selection.layer_override;
    let layer_label = selection.layer_label.clone();
    let mut queue = live_private_layer_selection_queue()
        .lock()
        .map_err(|_| "live_queue_poisoned".to_string())?;
    let overwrote_pending = queue.replace(selection).is_some();
    crate::marker(
        "private-layer-panel",
        format!(
            "status=live-queued transport=jni-live-queue schema={} candidateRevision={} privateLayerOverride={:.1} privateLayerActiveLayer={} overwrotePendingSelection={}",
            PRIVATE_LAYER_SELECTION_SCHEMA,
            revision,
            layer_override,
            crate::sanitize(&layer_label),
            overwrote_pending
        ),
    );
    Ok(LiveQueueOutcome {
        revision,
        overwrote_pending,
    })
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeSubmitLiveStimulusCandidate(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    candidate_json: jni::objects::JString,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let candidate_json = candidate_json.try_to_string(env)?;
            let response = match queue_live_candidate(&candidate_json) {
                Ok(outcome) => json!({
                    "schema": STATUS_SCHEMA,
                    "status": "queued",
                    "transport": "jni_live_queue",
                    "candidate_revision": outcome.revision,
                    "overwrote_pending": outcome.overwrote_pending
                })
                .to_string(),
                Err(reason) => {
                    crate::marker(
                        "stimulus-panel",
                        format!(
                            "status=live-rejected transport=jni-live-queue schema={} reason={}",
                            PROFILE_SCHEMA,
                            crate::sanitize(&reason)
                        ),
                    );
                    json!({
                        "schema": STATUS_SCHEMA,
                        "status": "rejected",
                        "transport": "jni_live_queue",
                        "rejection_code": reason
                    })
                    .to_string()
                }
            };
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(error) => {
            crate::marker(
                "stimulus-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_error:{}",
                    PROFILE_SCHEMA,
                    crate::sanitize(&error.to_string())
                ),
            );
            std::ptr::null_mut()
        }
        jni::Outcome::Panic(_) => {
            crate::marker(
                "stimulus-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_panic",
                    PROFILE_SCHEMA
                ),
            );
            std::ptr::null_mut()
        }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeSubmitLivePrivateLayerSelection(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    selection_json: jni::objects::JString,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let selection_json = selection_json.try_to_string(env)?;
            let response = match queue_live_private_layer_selection(&selection_json) {
                Ok(outcome) => json!({
                    "schema": PRIVATE_LAYER_SELECTION_STATUS_SCHEMA,
                    "status": "queued",
                    "transport": "jni_live_queue",
                    "candidate_revision": outcome.revision,
                    "overwrote_pending": outcome.overwrote_pending
                })
                .to_string(),
                Err(reason) => {
                    crate::marker(
                        "private-layer-panel",
                        format!(
                            "status=live-rejected transport=jni-live-queue schema={} reason={}",
                            PRIVATE_LAYER_SELECTION_SCHEMA,
                            crate::sanitize(&reason)
                        ),
                    );
                    json!({
                        "schema": PRIVATE_LAYER_SELECTION_STATUS_SCHEMA,
                        "status": "rejected",
                        "transport": "jni_live_queue",
                        "rejection_code": reason
                    })
                    .to_string()
                }
            };
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(error) => {
            crate::marker(
                "private-layer-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_error:{}",
                    PRIVATE_LAYER_SELECTION_SCHEMA,
                    crate::sanitize(&error.to_string())
                ),
            );
            std::ptr::null_mut()
        }
        jni::Outcome::Panic(_) => {
            crate::marker(
                "private-layer-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_panic",
                    PRIVATE_LAYER_SELECTION_SCHEMA
                ),
            );
            std::ptr::null_mut()
        }
    }
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

pub(crate) fn parse_private_layer_selection_json(
    text: &str,
) -> Result<PrivateLayerPanelSelection, String> {
    let value: Value = serde_json::from_str(text).map_err(|error| format!("json_parse:{error}"))?;
    let schema = string_at(&value, &["schema"]).unwrap_or_default();
    if schema != PRIVATE_LAYER_SELECTION_SCHEMA {
        return Err(format!("schema_mismatch:{schema}"));
    }

    let revision = value
        .get("revision")
        .and_then(Value::as_i64)
        .unwrap_or(0)
        .max(0);
    let private_layer = object_value_at(&value, &["private_layer"])?;
    let apply = value.get("apply").and_then(Value::as_object);
    if let Some(mode) = apply
        .and_then(|object| object.get("mode"))
        .and_then(Value::as_str)
    {
        match mode {
            "apply-on-next-safe-frame" => {}
            _ => return Err(format!("unsupported_apply_mode:{mode}")),
        }
    }

    let requested = number_at(private_layer, &["layer_override"])
        .ok_or_else(|| "missing_number:private_layer.layer_override".to_string())?;
    let rounded = requested.round();
    if (requested - rounded).abs() > 0.001 {
        return Err(format!(
            "private_layer_override_not_integral:{requested:.3}"
        ));
    }
    if !(0.0..=6.0).contains(&rounded) {
        return Err(format!("private_layer_override_out_of_range:{rounded:.1}"));
    }
    let index = rounded as u32;
    let expected_label = private_layer_label(index);
    let layer_label =
        string_at(private_layer, &["layer_label"]).unwrap_or_else(|| expected_label.to_string());
    validate_token(
        "private_layer.layer_label",
        &layer_label,
        &[
            "final",
            "raw-brightness",
            "preblur-brightness",
            "raw-strength",
            "blurred-strength",
            "displacement",
            "depth-gradient",
        ],
    )?;

    Ok(PrivateLayerPanelSelection {
        revision,
        layer_override: rounded as f32,
        layer_label,
    })
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
    settings.startup_dynamics = parse_startup_dynamics(stimulus, settings.startup_dynamics)?;

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
            write_status(&data_path, "rejected", 0, &reason, "app_private_file", None);
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
            write_status(
                &data_path,
                "applied",
                revision,
                "none",
                "app_private_file",
                Some(&updated.stimulus_volume_settings),
            );
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
            write_status(&data_path, "rejected", 0, &reason, "app_private_file", None);
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

fn parse_startup_dynamics(
    stimulus: &Value,
    fallback: NativeStimulusVolumeStartupDynamics,
) -> Result<NativeStimulusVolumeStartupDynamics, String> {
    let Some(dynamics_value) = value_at(stimulus, &["dynamics"]) else {
        return Ok(fallback);
    };
    if !dynamics_value.is_object() {
        return Err("missing_object:stimulus.dynamics".to_string());
    }

    let mut dynamics = fallback;
    dynamics.temporal_frequency_hz = bounded_number_at(
        dynamics_value,
        "temporal_frequency_hz",
        dynamics.temporal_frequency_hz,
        3.0,
        40.0,
    )?;
    dynamics.oscillator_hz = bounded_number_triplet_at(
        dynamics_value,
        "spatial_oscillator_hz",
        dynamics.oscillator_hz,
        3.0,
        40.0,
    )?;
    dynamics.spatial_frequency_scale = bounded_number_at(
        dynamics_value,
        "spatial_frequency_scale",
        dynamics.spatial_frequency_scale,
        0.35,
        3.0,
    )?;
    dynamics.source_shift = bounded_number_pair_at(
        dynamics_value,
        "source_shift",
        dynamics.source_shift,
        -0.5,
        0.5,
    )?;
    dynamics.noise_scale = bounded_number_at(
        dynamics_value,
        "noise_scale",
        dynamics.noise_scale,
        0.0,
        12.0,
    )?;
    dynamics.depth_warp =
        bounded_number_at(dynamics_value, "depth_warp", dynamics.depth_warp, 0.0, 0.25)?;
    dynamics.twist = bounded_number_at(dynamics_value, "twist", dynamics.twist, -1.6, 1.6)?;
    dynamics.pinch = bounded_number_at(dynamics_value, "pinch", dynamics.pinch, -1.2, 1.2)?;
    dynamics.scramble = bounded_number_at(dynamics_value, "scramble", dynamics.scramble, 0.0, 1.0)?;
    dynamics.jumble = bounded_number_at(dynamics_value, "jumble", dynamics.jumble, 0.0, 1.0)?;
    dynamics.stretch =
        bounded_number_pair_at(dynamics_value, "stretch", dynamics.stretch, 0.4, 2.0)?;
    dynamics.phase_offsets = bounded_number_triplet_at(
        dynamics_value,
        "phase_offsets",
        dynamics.phase_offsets,
        0.0,
        std::f64::consts::TAU,
    )?;
    if let Some(mirror_mode) = string_at(dynamics_value, &["mirror_mode"]) {
        dynamics.mirror_mode = match mirror_mode.as_str() {
            "none" => 0,
            "mirror-x" => 1,
            "mirror-y" => 2,
            "mirror-xy" => 3,
            "radial-wedge" => 4,
            "grid-fold" => 5,
            _ => return Err(format!("unsupported_mirror_mode:{mirror_mode}")),
        };
    }

    Ok(dynamics)
}

fn private_layer_label(index: u32) -> &'static str {
    match index {
        0 => "final",
        1 => "raw-brightness",
        2 => "preblur-brightness",
        3 => "raw-strength",
        4 => "blurred-strength",
        5 => "displacement",
        6 => "depth-gradient",
        _ => "unknown",
    }
}

#[cfg(target_os = "android")]
pub(crate) fn write_live_status(
    app: &android_activity::AndroidApp,
    status: &str,
    revision: i64,
    reason: &str,
    settings: Option<&NativeStimulusVolumeSettings>,
) {
    if let Some(data_path) = app.internal_data_path() {
        write_status(
            &data_path,
            status,
            revision,
            reason,
            "jni_live_queue",
            settings,
        );
    }
}

#[cfg(target_os = "android")]
pub(crate) fn write_private_layer_selection_status(
    app: &android_activity::AndroidApp,
    status: &str,
    revision: i64,
    reason: &str,
    selection: Option<&PrivateLayerPanelSelection>,
) {
    if let Some(data_path) = app.internal_data_path() {
        let effective_revision = if status == "applied" { revision } else { 0 };
        let body = json!({
            "schema": PRIVATE_LAYER_SELECTION_STATUS_SCHEMA,
            "status": status,
            "candidate_revision": revision,
            "effective_revision": effective_revision,
            "rejection_code": if reason == "none" { Value::Null } else { Value::String(reason.to_string()) },
            "transport": "jni_live_queue",
            "private_layer_override": selection
                .map(|selection| json!(selection.layer_override))
                .unwrap_or(Value::Null),
            "private_layer_active_layer": selection
                .map(|selection| json!(selection.layer_label.clone()))
                .unwrap_or(Value::Null)
        });
        let _ = std::fs::write(
            data_path.join(PRIVATE_LAYER_SELECTION_STATUS_FILE),
            body.to_string(),
        );
    }
}

#[cfg(target_os = "android")]
fn write_status(
    data_path: &Path,
    status: &str,
    revision: i64,
    reason: &str,
    transport: &str,
    settings: Option<&NativeStimulusVolumeSettings>,
) {
    let effective_revision = if status == "applied" { revision } else { 0 };
    let body = json!({
        "schema": STATUS_SCHEMA,
        "status": status,
        "candidate_revision": revision,
        "effective_revision": effective_revision,
        "rejection_code": if reason == "none" { Value::Null } else { Value::String(reason.to_string()) },
        "transport": transport,
        "active_pattern_family": settings
            .map(|settings| settings.pattern_family.marker_value())
            .unwrap_or("none"),
        "active_randomize": settings.map(|settings| json!({
            "enabled": settings.randomize_enabled,
            "min_hz": settings.randomize_min_hz,
            "max_hz": settings.randomize_max_hz
        })).unwrap_or(Value::Null),
        "safety_gate": settings
            .map(|settings| if settings.active() {
                "acknowledged-active"
            } else if settings.enabled {
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

fn bounded_number_at(
    value: &Value,
    key: &str,
    fallback: f32,
    min: f64,
    max: f64,
) -> Result<f32, String> {
    let number = value.get(key).and_then(Value::as_f64);
    let Some(number) = number else {
        return Ok(fallback);
    };
    if !number.is_finite() || number < min || number > max {
        return Err(format!("{key}_out_of_range:{number:.3}"));
    }
    Ok(number as f32)
}

fn bounded_number_pair_at(
    value: &Value,
    key: &str,
    fallback: [f32; 2],
    min: f64,
    max: f64,
) -> Result<[f32; 2], String> {
    let Some(array) = value.get(key) else {
        return Ok(fallback);
    };
    let Some(array) = array.as_array() else {
        return Err(format!("{key}_must_be_number_pair"));
    };
    if array.len() != 2 {
        return Err(format!("{key}_must_be_number_pair"));
    }
    Ok([
        bounded_array_number(key, &array[0], min, max)?,
        bounded_array_number(key, &array[1], min, max)?,
    ])
}

fn bounded_number_triplet_at(
    value: &Value,
    key: &str,
    fallback: [f32; 3],
    min: f64,
    max: f64,
) -> Result<[f32; 3], String> {
    let Some(array) = value.get(key) else {
        return Ok(fallback);
    };
    let Some(array) = array.as_array() else {
        return Err(format!("{key}_must_be_number_triplet"));
    };
    if array.len() != 3 {
        return Err(format!("{key}_must_be_number_triplet"));
    }
    Ok([
        bounded_array_number(key, &array[0], min, max)?,
        bounded_array_number(key, &array[1], min, max)?,
        bounded_array_number(key, &array[2], min, max)?,
    ])
}

fn bounded_array_number(key: &str, value: &Value, min: f64, max: f64) -> Result<f32, String> {
    let Some(number) = value.as_f64() else {
        return Err(format!("{key}_must_be_number"));
    };
    if !number.is_finite() || number < min || number > max {
        return Err(format!("{key}_out_of_range:{number:.3}"));
    }
    Ok(number as f32)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::native_renderer_options::{
        NativeStimulusVolumePatternFamily, NativeStimulusVolumeRenderTarget,
    };

    fn assert_close(actual: f32, expected: f32) {
        assert!(
            (actual - expected).abs() < 0.000_5,
            "actual {actual} expected {expected}"
        );
    }

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
                },
                "dynamics": {
                    "mirror_mode": "grid-fold",
                    "temporal_frequency_hz": 3.084,
                    "spatial_oscillator_hz": [6.041, 35.362, 37.531],
                    "spatial_frequency_scale": 0.900,
                    "source_shift": [-0.052, 0.099],
                    "noise_scale": 6.633,
                    "depth_warp": 0.103,
                    "twist": -0.791,
                    "pinch": -0.282,
                    "scramble": 0.128,
                    "jumble": 0.165,
                    "stretch": [1.390, 1.072],
                    "phase_offsets": [0.965, 1.613, 3.836]
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
        let dynamics = candidate.settings.startup_dynamics;
        assert_eq!(dynamics.mirror_mode, 5);
        assert_eq!(
            dynamics.pattern_family,
            NativeStimulusVolumePatternFamily::Spiral
        );
        assert_close(dynamics.temporal_frequency_hz, 3.084);
        assert_close(dynamics.oscillator_hz[0], 6.041);
        assert_close(dynamics.oscillator_hz[1], 35.362);
        assert_close(dynamics.oscillator_hz[2], 37.531);
        assert_close(dynamics.spatial_frequency_scale, 0.900);
        assert_close(dynamics.source_shift[0], -0.052);
        assert_close(dynamics.source_shift[1], 0.099);
        assert_close(dynamics.noise_scale, 6.633);
        assert_close(dynamics.depth_warp, 0.103);
        assert_close(dynamics.twist, -0.791);
        assert_close(dynamics.pinch, -0.282);
        assert_close(dynamics.scramble, 0.128);
        assert_close(dynamics.jumble, 0.165);
        assert_close(dynamics.stretch[0], 1.390);
        assert_close(dynamics.stretch[1], 1.072);
        assert_close(dynamics.phase_offsets[0], 0.965);
        assert_close(dynamics.phase_offsets[1], 1.613);
        assert_close(dynamics.phase_offsets[2], 3.836);
    }

    #[test]
    fn parses_live_panel_candidate_for_performance_render_target() {
        let mut value: Value = serde_json::from_str(&valid_candidate()).unwrap();
        value["stimulus"]["render_target"] = Value::from("512x512x2-rgba16f");
        value["stimulus"]["central_fov_fraction"] = Value::from(0.72);
        value["stimulus"]["gradient_smoothing"] = Value::from(0.78);
        value["stimulus"]["randomize"]["min_hz"] = Value::from(3.0);
        value["stimulus"]["randomize"]["max_hz"] = Value::from(40.0);
        value["apply"]["mode"] = Value::from("apply-on-next-safe-frame");

        let candidate = parse_candidate_json(&value.to_string()).expect("live candidate parses");

        assert_eq!(candidate.revision, 7);
        assert_eq!(
            candidate.settings.render_target,
            NativeStimulusVolumeRenderTarget::Rgba16f512Stereo
        );
        assert_eq!(candidate.settings.raymarch_samples, 12);
        assert_close(candidate.settings.central_fov_fraction, 0.72);
        assert_close(candidate.settings.gradient_smoothing, 0.78);
        assert_close(candidate.settings.randomize_min_hz, 3.0);
        assert_close(candidate.settings.randomize_max_hz, 40.0);
        assert!(candidate.settings.enabled);
        assert!(candidate.settings.safety_acknowledged);
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

    #[test]
    fn rejects_out_of_range_dynamics() {
        let mut value: Value = serde_json::from_str(&valid_candidate()).unwrap();
        value["stimulus"]["dynamics"]["twist"] = Value::from(2.0);
        let error = parse_candidate_json(&value.to_string()).unwrap_err();
        assert!(error.starts_with("twist_out_of_range"));
    }

    #[test]
    fn rejects_unknown_mirror_mode() {
        let mut value: Value = serde_json::from_str(&valid_candidate()).unwrap();
        value["stimulus"]["dynamics"]["mirror_mode"] = Value::from("kaleidoscope");
        let error = parse_candidate_json(&value.to_string()).unwrap_err();
        assert_eq!(error, "unsupported_mirror_mode:kaleidoscope");
    }
}
