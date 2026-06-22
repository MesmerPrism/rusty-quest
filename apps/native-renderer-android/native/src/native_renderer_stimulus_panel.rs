//! Same-APK 2D panel candidate import for the native stimulus route.
//!
//! The panel is a low-rate requester. Rust remains the validator and the
//! effective runtime authority.

use std::collections::BTreeMap;
#[cfg(target_os = "android")]
use std::path::Path;
#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};

use serde_json::{json, Value};

#[cfg(target_os = "android")]
use crate::gpu_private_particles::{
    GpuPrivateParticlePanelEffectiveSettings, GpuPrivateParticlePanelSettings,
};

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
pub(crate) const ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA: &str =
    "rusty.quest.native_renderer.environment_depth_alignment.v1";
pub(crate) const ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_SCHEMA: &str =
    "rusty.quest.native_renderer.environment_depth_alignment_status.v1";
pub(crate) const ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_FILE: &str = "depth_alignment_status.json";
pub(crate) const PRIVATE_PARTICLE_DYNAMICS_SCHEMA: &str =
    "rusty.quest.native_renderer.private_particle_dynamics.v1";
pub(crate) const PRIVATE_PARTICLE_DYNAMICS_STATUS_SCHEMA: &str =
    "rusty.quest.native_renderer.private_particle_dynamics_status.v1";
pub(crate) const PRIVATE_PARTICLE_DYNAMICS_STATUS_FILE: &str =
    "private_particle_dynamics_status.json";
pub(crate) const PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT: usize = 8;
const PRIVATE_PARTICLE_DRIVER_CONTROL_OSCILLATOR: u32 = 0;
const PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL: u32 = 1;
const PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT: u32 = 2;
const PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT: u32 = 3;
const PRIVATE_PARTICLE_CURVE_LINEAR: u32 = 0;
const PRIVATE_PARTICLE_CURVE_AKD_HUMP: u32 = 1;
const PRIVATE_PARTICLE_CURVE_SMOOTHSTEP: u32 = 2;
const PRIVATE_PARTICLE_CURVE_REVERSE_LINEAR: u32 = 3;
const PRIVATE_PARTICLE_CURVE_HOLD_LOW: u32 = 4;
const PRIVATE_PARTICLE_CURVE_HOLD_HIGH: u32 = 5;

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

#[derive(Clone, Debug)]
pub(crate) struct EnvironmentDepthAlignmentPanelCandidate {
    pub(crate) revision: i64,
    pub(crate) effective_offsets_uv: [[f32; 2]; 2],
    pub(crate) sample_scale: f32,
}

#[derive(Clone, Debug)]
pub(crate) struct PrivateParticleDynamicsPanelCandidate {
    pub(crate) revision: i64,
    pub(crate) visual_scale: f32,
    pub(crate) world_anchor_scale_m: f32,
    pub(crate) driver_values01: [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    pub(crate) driver_control_modes: [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    pub(crate) driver_control_source_slots: [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    pub(crate) driver_control_curve_codes: [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    pub(crate) driver_control_range_mins: [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    pub(crate) driver_control_range_maxs: [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    pub(crate) driver_control_cycle_multipliers: [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    pub(crate) tracer_draw_slots_per_oscillator: u32,
    pub(crate) tracer_lifetime_seconds: f32,
    pub(crate) tracer_copies_per_second: f32,
    pub(crate) transparency_opacity: f32,
    pub(crate) transparency_output_alpha_scale: f32,
    pub(crate) transparency_depth_suppression_strength: f32,
    pub(crate) transparency_rgb_alpha_coupling: f32,
    pub(crate) color_facing_attenuation_strength: f32,
}

#[cfg(target_os = "android")]
#[derive(Clone, Debug)]
pub(crate) struct PrivateParticleDynamicsPanelAppliedState {
    pub(crate) world_anchor_scale_m: f32,
    pub(crate) world_anchor_scale_parameter_source: &'static str,
    pub(crate) settings: GpuPrivateParticlePanelEffectiveSettings,
}

impl PrivateParticleDynamicsPanelCandidate {
    #[cfg(target_os = "android")]
    pub(crate) fn panel_settings(&self) -> GpuPrivateParticlePanelSettings {
        GpuPrivateParticlePanelSettings {
            visual_scale: self.visual_scale,
            driver_values01: self.driver_values01,
            driver_control_modes: self.driver_control_modes,
            driver_control_source_slots: self.driver_control_source_slots,
            driver_control_curve_codes: self.driver_control_curve_codes,
            driver_control_range_mins: self.driver_control_range_mins,
            driver_control_range_maxs: self.driver_control_range_maxs,
            driver_control_cycle_multipliers: self.driver_control_cycle_multipliers,
            tracer_draw_slots_per_oscillator: self.tracer_draw_slots_per_oscillator,
            tracer_lifetime_seconds: self.tracer_lifetime_seconds,
            tracer_copies_per_second: self.tracer_copies_per_second,
            transparency_opacity: self.transparency_opacity,
            transparency_output_alpha_scale: self.transparency_output_alpha_scale,
            transparency_depth_suppression_strength: self.transparency_depth_suppression_strength,
            transparency_rgb_alpha_coupling: self.transparency_rgb_alpha_coupling,
            color_facing_attenuation_strength: self.color_facing_attenuation_strength,
        }
    }
}

#[cfg(target_os = "android")]
static LIVE_CANDIDATE_QUEUE: OnceLock<Mutex<Option<StimulusPanelCandidate>>> = OnceLock::new();
#[cfg(target_os = "android")]
static LIVE_PRIVATE_LAYER_SELECTION_QUEUE: OnceLock<Mutex<Option<PrivateLayerPanelSelection>>> =
    OnceLock::new();
#[cfg(target_os = "android")]
static LIVE_ENVIRONMENT_DEPTH_ALIGNMENT_QUEUE: OnceLock<
    Mutex<Option<EnvironmentDepthAlignmentPanelCandidate>>,
> = OnceLock::new();
#[cfg(target_os = "android")]
static LIVE_PRIVATE_PARTICLE_DYNAMICS_QUEUE: OnceLock<
    Mutex<Option<PrivateParticleDynamicsPanelCandidate>>,
> = OnceLock::new();

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
fn live_environment_depth_alignment_queue(
) -> &'static Mutex<Option<EnvironmentDepthAlignmentPanelCandidate>> {
    LIVE_ENVIRONMENT_DEPTH_ALIGNMENT_QUEUE.get_or_init(|| Mutex::new(None))
}

#[cfg(target_os = "android")]
fn live_private_particle_dynamics_queue(
) -> &'static Mutex<Option<PrivateParticleDynamicsPanelCandidate>> {
    LIVE_PRIVATE_PARTICLE_DYNAMICS_QUEUE.get_or_init(|| Mutex::new(None))
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
pub(crate) fn take_live_environment_depth_alignment(
) -> Option<EnvironmentDepthAlignmentPanelCandidate> {
    let mut queue = live_environment_depth_alignment_queue().lock().ok()?;
    queue.take()
}

#[cfg(target_os = "android")]
pub(crate) fn take_live_private_particle_dynamics() -> Option<PrivateParticleDynamicsPanelCandidate>
{
    let mut queue = live_private_particle_dynamics_queue().lock().ok()?;
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
fn queue_live_environment_depth_alignment(text: &str) -> Result<LiveQueueOutcome, String> {
    let candidate = parse_environment_depth_alignment_json(text)?;
    let revision = candidate.revision;
    let offsets = candidate.effective_offsets_uv;
    let sample_scale = candidate.sample_scale;
    let mut queue = live_environment_depth_alignment_queue()
        .lock()
        .map_err(|_| "live_queue_poisoned".to_string())?;
    let overwrote_pending = queue.replace(candidate).is_some();
    crate::marker(
        "environment-depth-alignment-panel",
        format!(
            "status=live-queued transport=jni-live-queue schema={} candidateRevision={} leftRequestedOffsetUv={:.6},{:.6} rightRequestedOffsetUv={:.6},{:.6} requestedSampleScale={:.4} overwrotePendingAlignment={}",
            ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA,
            revision,
            offsets[0][0],
            offsets[0][1],
            offsets[1][0],
            offsets[1][1],
            sample_scale,
            overwrote_pending
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
fn queue_live_private_particle_dynamics(text: &str) -> Result<LiveQueueOutcome, String> {
    let candidate = parse_private_particle_dynamics_json(text)?;
    let revision = candidate.revision;
    let mut queue = live_private_particle_dynamics_queue()
        .lock()
        .map_err(|_| "live_queue_poisoned".to_string())?;
    let overwrote_pending = queue.replace(candidate.clone()).is_some();
    crate::marker(
        "private-particle-panel",
        format!(
            "status=live-queued transport=jni-live-queue schema={} candidateRevision={} privateParticleVisualScale={:.3} privateParticleWorldAnchorScaleM={:.3} privateParticleDriver0Value01={:.3} privateParticleDriver1Value01={:.3} privateParticleTracerDrawSlotsPerOscillator={} privateParticleTracerLifetimeSeconds={:.3} privateParticleTracerCopiesPerSecond={:.3} privateParticleTransparencyOpacity={:.3} privateParticleTransparencyOutputAlphaScale={:.3} privateParticleTransparencyDepthSuppressionStrength={:.3} privateParticleTransparencyRgbAlphaCoupling={:.3} privateParticleColorFacingAttenuationStrength={:.3} overwrotePendingDynamics={}",
            PRIVATE_PARTICLE_DYNAMICS_SCHEMA,
            revision,
            candidate.visual_scale,
            candidate.world_anchor_scale_m,
            candidate.driver_values01[0],
            candidate.driver_values01[1],
            candidate.tracer_draw_slots_per_oscillator,
            candidate.tracer_lifetime_seconds,
            candidate.tracer_copies_per_second,
            candidate.transparency_opacity,
            candidate.transparency_output_alpha_scale,
            candidate.transparency_depth_suppression_strength,
            candidate.transparency_rgb_alpha_coupling,
            candidate.color_facing_attenuation_strength,
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

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeSubmitLiveDepthAlignment(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    alignment_json: jni::objects::JString,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let alignment_json = alignment_json.try_to_string(env)?;
            let response = match queue_live_environment_depth_alignment(&alignment_json) {
                Ok(outcome) => json!({
                    "schema": ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_SCHEMA,
                    "status": "queued",
                    "transport": "jni_live_queue",
                    "candidate_revision": outcome.revision,
                    "overwrote_pending": outcome.overwrote_pending
                })
                .to_string(),
                Err(reason) => {
                    crate::marker(
                        "environment-depth-alignment-panel",
                        format!(
                            "status=live-rejected transport=jni-live-queue schema={} reason={}",
                            ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA,
                            crate::sanitize(&reason)
                        ),
                    );
                    json!({
                        "schema": ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_SCHEMA,
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
                "environment-depth-alignment-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_error:{}",
                    ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA,
                    crate::sanitize(&error.to_string())
                ),
            );
            std::ptr::null_mut()
        }
        jni::Outcome::Panic(_) => {
            crate::marker(
                "environment-depth-alignment-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_panic",
                    ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA
                ),
            );
            std::ptr::null_mut()
        }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeSubmitLivePrivateParticleDynamics(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    dynamics_json: jni::objects::JString,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let dynamics_json = dynamics_json.try_to_string(env)?;
            let response = match queue_live_private_particle_dynamics(&dynamics_json) {
                Ok(outcome) => json!({
                    "schema": PRIVATE_PARTICLE_DYNAMICS_STATUS_SCHEMA,
                    "status": "queued",
                    "transport": "jni_live_queue",
                    "candidate_revision": outcome.revision,
                    "overwrote_pending": outcome.overwrote_pending
                })
                .to_string(),
                Err(reason) => {
                    crate::marker(
                        "private-particle-panel",
                        format!(
                            "status=live-rejected transport=jni-live-queue schema={} reason={}",
                            PRIVATE_PARTICLE_DYNAMICS_SCHEMA,
                            crate::sanitize(&reason)
                        ),
                    );
                    json!({
                        "schema": PRIVATE_PARTICLE_DYNAMICS_STATUS_SCHEMA,
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
                "private-particle-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_error:{}",
                    PRIVATE_PARTICLE_DYNAMICS_SCHEMA,
                    crate::sanitize(&error.to_string())
                ),
            );
            std::ptr::null_mut()
        }
        jni::Outcome::Panic(_) => {
            crate::marker(
                "private-particle-panel",
                format!(
                    "status=live-rejected transport=jni-live-queue schema={} reason=jni_panic",
                    PRIVATE_PARTICLE_DYNAMICS_SCHEMA
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

pub(crate) fn parse_private_particle_dynamics_json(
    text: &str,
) -> Result<PrivateParticleDynamicsPanelCandidate, String> {
    let value: Value = serde_json::from_str(text).map_err(|error| format!("json_parse:{error}"))?;
    let schema = string_at(&value, &["schema"]).unwrap_or_default();
    if schema != PRIVATE_PARTICLE_DYNAMICS_SCHEMA {
        return Err(format!("schema_mismatch:{schema}"));
    }

    let revision = value
        .get("revision")
        .and_then(Value::as_i64)
        .unwrap_or(0)
        .max(0);
    let private_particles = object_value_at(&value, &["private_particles"])?;
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

    let visual_scale = bounded_number_at(private_particles, "visual_scale", 0.7, 0.05, 1.0)? as f32;
    let world_anchor_scale_m =
        bounded_number_at(private_particles, "world_anchor_scale_m", 0.46, 0.05, 4.0)? as f32;
    let driver_values01 = bounded_number_array_at::<{ PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT }>(
        private_particles,
        "driver_values01",
        0.0,
        1.0,
    )?;
    let driver_controls = private_particle_driver_controls(private_particles)?;
    let tracer = object_value_at(private_particles, &["tracer"])?;
    let tracer_draw_slots_per_oscillator =
        bounded_u32_at(tracer, "draw_slots_per_oscillator", 7, 0, 1024)?;
    let tracer_lifetime_seconds =
        bounded_number_at(tracer, "lifetime_seconds", 0.5, 0.016, 30.0)? as f32;
    let tracer_copies_per_second =
        bounded_number_at(tracer, "copies_per_second", 14.0, 0.0, 120.0)? as f32;
    let transparency = private_particles
        .get("transparency")
        .filter(|value| value.is_object())
        .unwrap_or(private_particles);
    let transparency_opacity = bounded_number_at(transparency, "opacity", 1.0, 0.0, 4.0)? as f32;
    let transparency_output_alpha_scale =
        bounded_number_at(transparency, "output_alpha_scale", 1.0, 0.0, 4.0)? as f32;
    let transparency_depth_suppression_strength =
        bounded_number_at(transparency, "depth_suppression_strength", 0.0, 0.0, 8.0)? as f32;
    let transparency_rgb_alpha_coupling =
        bounded_number_at(transparency, "rgb_alpha_coupling", 1.0, 0.0, 1.0)? as f32;
    let color = private_particles
        .get("color")
        .filter(|value| value.is_object())
        .unwrap_or(private_particles);
    let color_facing_attenuation_strength =
        bounded_number_at(color, "facing_attenuation_strength", 0.0, 0.0, 1.0)? as f32;

    Ok(PrivateParticleDynamicsPanelCandidate {
        revision,
        visual_scale,
        world_anchor_scale_m,
        driver_values01,
        driver_control_modes: driver_controls.0,
        driver_control_source_slots: driver_controls.1,
        driver_control_curve_codes: driver_controls.2,
        driver_control_range_mins: driver_controls.3,
        driver_control_range_maxs: driver_controls.4,
        driver_control_cycle_multipliers: driver_controls.5,
        tracer_draw_slots_per_oscillator,
        tracer_lifetime_seconds,
        tracer_copies_per_second,
        transparency_opacity,
        transparency_output_alpha_scale,
        transparency_depth_suppression_strength,
        transparency_rgb_alpha_coupling,
        color_facing_attenuation_strength,
    })
}

pub(crate) fn parse_environment_depth_alignment_json(
    text: &str,
) -> Result<EnvironmentDepthAlignmentPanelCandidate, String> {
    let value: Value = serde_json::from_str(text).map_err(|error| format!("json_parse:{error}"))?;
    let schema = string_at(&value, &["schema"]).unwrap_or_default();
    if schema != ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA {
        return Err(format!("schema_mismatch:{schema}"));
    }

    let revision = value
        .get("revision")
        .and_then(Value::as_i64)
        .unwrap_or(0)
        .max(0);
    let alignment = object_value_at(&value, &["depth_alignment"])?;
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
    let left = bounded_number_pair_at(alignment, "left_offset_uv", [0.0, 0.0], -1.0, 1.0)?;
    let right = bounded_number_pair_at(alignment, "right_offset_uv", [0.0, 0.0], -1.0, 1.0)?;
    let sample_scale = bounded_number_at(alignment, "sample_scale", 1.0, 0.25, 4.0)? as f32;
    Ok(EnvironmentDepthAlignmentPanelCandidate {
        revision,
        effective_offsets_uv: [left, right],
        sample_scale,
    })
}

type PrivateParticleDriverControls = (
    [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
);

fn private_particle_driver_controls(
    private_particles: &Value,
) -> Result<PrivateParticleDriverControls, String> {
    let mut modes =
        [PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT];
    let mut source_slots = [0_u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT];
    let mut curve_codes = [PRIVATE_PARTICLE_CURVE_LINEAR; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT];
    let mut range_mins = [0.0_f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT];
    let mut range_maxs = [1.0_f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT];
    let mut cycle_multipliers = [0.0_f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT];
    for index in 0..PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT {
        source_slots[index] = index as u32;
        let range = private_particle_canonical_driver_range(index);
        range_mins[index] = range.0;
        range_maxs[index] = range.1;
    }

    let Some(controls_value) = private_particles.get("driver_controls") else {
        return Ok((
            modes,
            source_slots,
            curve_codes,
            range_mins,
            range_maxs,
            cycle_multipliers,
        ));
    };
    let Some(controls) = controls_value.as_array() else {
        return Err("driver_controls_must_be_array".to_string());
    };
    if controls.len() > PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT {
        return Err(format!(
            "driver_controls_too_long:{}>{}",
            controls.len(),
            PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT
        ));
    }
    for (fallback_index, control) in controls.iter().enumerate() {
        let Some(control_object) = control.as_object() else {
            return Err(format!("driver_controls_{fallback_index}_must_be_object"));
        };
        let target_slot = bounded_u32_at(
            control,
            "target_slot",
            fallback_index as u32,
            0,
            (PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT - 1) as u32,
        )? as usize;
        modes[target_slot] = private_particle_driver_control_mode(control)?;
        source_slots[target_slot] = bounded_u32_at(
            control,
            "source_slot",
            target_slot as u32,
            0,
            (PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT - 1) as u32,
        )?;
        curve_codes[target_slot] = private_particle_curve_code(control)?;
        range_mins[target_slot] = bounded_number_at(
            control,
            "range_min",
            range_mins[target_slot],
            -1000.0,
            1000.0,
        )?;
        range_maxs[target_slot] = bounded_number_at(
            control,
            "range_max",
            range_maxs[target_slot],
            -1000.0,
            1000.0,
        )?;
        cycle_multipliers[target_slot] =
            bounded_number_at(control, "cycle_multiplier", 0.0, 0.0, 10.0)?;
        if range_maxs[target_slot] < range_mins[target_slot] {
            return Err(format!("driver_controls_{target_slot}_range_inverted"));
        }
        if control_object.contains_key("mode") && control_object.contains_key("mode_code") {
            let mode_code = private_particle_driver_control_mode_code(control)?;
            if mode_code != modes[target_slot] {
                return Err(format!("driver_controls_{target_slot}_mode_mismatch"));
            }
        }
    }
    Ok((
        modes,
        source_slots,
        curve_codes,
        range_mins,
        range_maxs,
        cycle_multipliers,
    ))
}

fn private_particle_driver_control_mode(control: &Value) -> Result<u32, String> {
    if control.get("mode_code").is_some() {
        return private_particle_driver_control_mode_code(control);
    }
    let mode = control
        .get("mode")
        .and_then(Value::as_str)
        .map(|value| value.trim().to_ascii_lowercase())
        .unwrap_or_else(|| "direct".to_string());
    match mode.as_str() {
        "oscillator" => Ok(PRIVATE_PARTICLE_DRIVER_CONTROL_OSCILLATOR),
        "manual" => Ok(PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL),
        "input-slot" | "input_slot" | "input slot" => {
            Ok(PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT)
        }
        "direct" => Ok(PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT),
        _ => Err(format!("unsupported_driver_control_mode:{mode}")),
    }
}

fn private_particle_driver_control_mode_code(control: &Value) -> Result<u32, String> {
    bounded_u32_at(
        control,
        "mode_code",
        PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT,
        0,
        3,
    )
}

fn private_particle_curve_code(control: &Value) -> Result<u32, String> {
    if control.get("curve_code").is_some() {
        return bounded_u32_at(control, "curve_code", PRIVATE_PARTICLE_CURVE_LINEAR, 0, 5);
    }
    let curve = control
        .get("curve")
        .and_then(Value::as_str)
        .map(|value| value.trim().to_ascii_lowercase())
        .unwrap_or_else(|| "linear".to_string());
    match curve.as_str() {
        "linear" => Ok(PRIVATE_PARTICLE_CURVE_LINEAR),
        "akd hump" | "akd-hump" | "hump" => Ok(PRIVATE_PARTICLE_CURVE_AKD_HUMP),
        "smoothstep" => Ok(PRIVATE_PARTICLE_CURVE_SMOOTHSTEP),
        "reverse linear" | "reverse-linear" => Ok(PRIVATE_PARTICLE_CURVE_REVERSE_LINEAR),
        "hold low" | "hold-low" => Ok(PRIVATE_PARTICLE_CURVE_HOLD_LOW),
        "hold high" | "hold-high" => Ok(PRIVATE_PARTICLE_CURVE_HOLD_HIGH),
        _ => Err(format!("unsupported_driver_control_curve:{curve}")),
    }
}

fn private_particle_canonical_driver_range(index: usize) -> (f32, f32) {
    match index {
        2 => (0.04, 0.115),
        3 => (0.0, 0.1),
        4 => (0.1, 0.5),
        5 => (0.2, 1.5),
        6 => (0.0, std::f32::consts::TAU),
        7 => (0.0, 1.0),
        _ => (0.0, 1.0),
    }
}

fn private_particle_driver_controls_status_json(
    modes: [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    source_slots: [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    curve_codes: [u32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    range_mins: [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    range_maxs: [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
    cycle_multipliers: [f32; PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT],
) -> Value {
    Value::Array(
        (0..PRIVATE_PARTICLE_DYNAMICS_DRIVER_COUNT)
            .map(|index| {
                json!({
                    "target_slot": index,
                    "mode_code": modes[index],
                    "mode": private_particle_driver_control_mode_label(modes[index]),
                    "source_slot": source_slots[index],
                    "curve_code": curve_codes[index],
                    "curve": private_particle_curve_label(curve_codes[index]),
                    "range_min": range_mins[index],
                    "range_max": range_maxs[index],
                    "cycle_multiplier": cycle_multipliers[index],
                })
            })
            .collect(),
    )
}

fn private_particle_driver_control_mode_label(mode: u32) -> &'static str {
    match mode {
        PRIVATE_PARTICLE_DRIVER_CONTROL_OSCILLATOR => "oscillator",
        PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL => "manual",
        PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT => "input-slot",
        _ => "direct",
    }
}

fn private_particle_curve_label(curve_code: u32) -> &'static str {
    match curve_code {
        PRIVATE_PARTICLE_CURVE_AKD_HUMP => "akd-hump",
        PRIVATE_PARTICLE_CURVE_SMOOTHSTEP => "smoothstep",
        PRIVATE_PARTICLE_CURVE_REVERSE_LINEAR => "reverse-linear",
        PRIVATE_PARTICLE_CURVE_HOLD_LOW => "hold-low",
        PRIVATE_PARTICLE_CURVE_HOLD_HIGH => "hold-high",
        _ => "linear",
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
pub(crate) fn write_environment_depth_alignment_status(
    app: &android_activity::AndroidApp,
    status: &str,
    revision: i64,
    reason: &str,
    state: &crate::environment_depth_alignment_state::EnvironmentDepthAlignmentState,
) {
    let effective_revision = if status == "applied" { revision } else { 0 };
    let left_effective = state.offset_for_eye(0);
    let right_effective = state.offset_for_eye(1);
    let left_base = state.base_offset_for_eye(0);
    let right_base = state.base_offset_for_eye(1);
    let manual = state.manual_offset_uv();
    let sample_scale = state.sample_scale();
    let external_status_path = app
        .external_data_path()
        .map(|path| path.join(ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_FILE));
    let body = json!({
        "schema": ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_SCHEMA,
        "status": status,
        "candidate_revision": revision,
        "effective_revision": effective_revision,
        "rejection_code": if reason == "none" { Value::Null } else { Value::String(reason.to_string()) },
        "transport": "runtime_state",
        "host_readback": {
            "authority": "app-scoped-external-files",
            "status_file": ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_FILE,
            "external_status_path": external_status_path
                .as_ref()
                .map(|path| path.to_string_lossy().to_string())
                .unwrap_or_else(|| "unavailable".to_string())
        },
        "depth_alignment": {
            "left_offset_uv": [left_effective[0], left_effective[1]],
            "right_offset_uv": [right_effective[0], right_effective[1]],
            "left_base_offset_uv": [left_base[0], left_base[1]],
            "right_base_offset_uv": [right_base[0], right_base[1]],
            "manual_offset_uv": [manual[0], manual[1]],
            "sample_scale": sample_scale
        },
        "marker_fields": state.marker_fields()
    });
    let body = body.to_string();
    if let Some(data_path) = app.internal_data_path() {
        let _ = std::fs::write(
            data_path.join(ENVIRONMENT_DEPTH_ALIGNMENT_STATUS_FILE),
            &body,
        );
    }
    if let Some(status_path) = external_status_path {
        if let Some(parent) = status_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(status_path, body);
    }
}

#[cfg(target_os = "android")]
pub(crate) fn write_private_particle_dynamics_status(
    app: &android_activity::AndroidApp,
    status: &str,
    revision: i64,
    reason: &str,
    candidate: Option<&PrivateParticleDynamicsPanelCandidate>,
    effective: Option<&PrivateParticleDynamicsPanelAppliedState>,
) {
    if let Some(data_path) = app.internal_data_path() {
        let effective_revision = if status == "applied" { revision } else { 0 };
        let private_particles = if let Some(effective) = effective {
            json!({
                "visual_scale": effective.settings.visual_scale,
                "visual_parameter_source": "same-apk-panel-live",
                "world_anchor_scale_m": effective.world_anchor_scale_m,
                "world_anchor_scale_parameter_source": effective.world_anchor_scale_parameter_source,
                "driver_values01": effective.settings.driver_values01,
                "driver_controls": private_particle_driver_controls_status_json(
                    effective.settings.driver_control_modes,
                    effective.settings.driver_control_source_slots,
                    effective.settings.driver_control_curve_codes,
                    effective.settings.driver_control_range_mins,
                    effective.settings.driver_control_range_maxs,
                    effective.settings.driver_control_cycle_multipliers,
                ),
                "driver_parameter_source": effective.settings.driver_parameter_source,
                "tracer": {
                    "draw_slots_per_oscillator": effective.settings.tracer_draw_slots_per_oscillator,
                    "draw_slots_capacity": effective.settings.tracer_draw_slots_capacity,
                    "lifetime_seconds": effective.settings.tracer_lifetime_seconds,
                    "copies_per_second": effective.settings.tracer_copies_per_second,
                    "parameter_source": effective.settings.tracer_parameter_source
                },
                "transparency": {
                    "opacity": effective.settings.transparency_opacity,
                    "output_alpha_scale": effective.settings.transparency_output_alpha_scale,
                    "depth_suppression_strength": effective.settings.transparency_depth_suppression_strength,
                    "rgb_alpha_coupling": effective.settings.transparency_rgb_alpha_coupling,
                    "parameter_source": effective.settings.transparency_parameter_source
                },
                "color": {
                    "facing_attenuation_strength": effective.settings.color_facing_attenuation_strength,
                    "parameter_source": effective.settings.color_parameter_source
                }
            })
        } else if let Some(candidate) = candidate {
            json!({
                "visual_scale": candidate.visual_scale,
                "visual_parameter_source": "requested",
                "world_anchor_scale_m": candidate.world_anchor_scale_m,
                "world_anchor_scale_parameter_source": "requested",
                "driver_values01": candidate.driver_values01,
                "driver_controls": private_particle_driver_controls_status_json(
                    candidate.driver_control_modes,
                    candidate.driver_control_source_slots,
                    candidate.driver_control_curve_codes,
                    candidate.driver_control_range_mins,
                    candidate.driver_control_range_maxs,
                    candidate.driver_control_cycle_multipliers,
                ),
                "driver_parameter_source": "requested",
                "tracer": {
                    "draw_slots_per_oscillator": candidate.tracer_draw_slots_per_oscillator,
                    "draw_slots_capacity": Value::Null,
                    "lifetime_seconds": candidate.tracer_lifetime_seconds,
                    "copies_per_second": candidate.tracer_copies_per_second,
                    "parameter_source": "requested"
                },
                "transparency": {
                    "opacity": candidate.transparency_opacity,
                    "output_alpha_scale": candidate.transparency_output_alpha_scale,
                    "depth_suppression_strength": candidate.transparency_depth_suppression_strength,
                    "rgb_alpha_coupling": candidate.transparency_rgb_alpha_coupling,
                    "parameter_source": "requested"
                },
                "color": {
                    "facing_attenuation_strength": candidate.color_facing_attenuation_strength,
                    "parameter_source": "requested"
                }
            })
        } else {
            Value::Null
        };
        let body = json!({
            "schema": PRIVATE_PARTICLE_DYNAMICS_STATUS_SCHEMA,
            "status": status,
            "candidate_revision": revision,
            "effective_revision": effective_revision,
            "rejection_code": if reason == "none" { Value::Null } else { Value::String(reason.to_string()) },
            "transport": "jni_live_queue",
            "private_particles": private_particles
        });
        let _ = std::fs::write(
            data_path.join(PRIVATE_PARTICLE_DYNAMICS_STATUS_FILE),
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

fn bounded_number_array_at<const N: usize>(
    value: &Value,
    key: &str,
    min: f64,
    max: f64,
) -> Result<[f32; N], String> {
    let Some(array) = value.get(key).and_then(Value::as_array) else {
        return Err(format!("missing_array:{key}"));
    };
    if array.len() != N {
        return Err(format!("{key}_length_mismatch:{}!={N}", array.len()));
    }
    let mut result = [0.0_f32; N];
    for (index, entry) in array.iter().enumerate() {
        let Some(number) = entry.as_f64() else {
            return Err(format!("{key}_{index}_must_be_number"));
        };
        if !number.is_finite() || number < min || number > max {
            return Err(format!("{key}_{index}_out_of_range:{number:.3}"));
        }
        result[index] = number as f32;
    }
    Ok(result)
}

fn bounded_u32_at(
    value: &Value,
    key: &str,
    fallback: u32,
    min: u32,
    max: u32,
) -> Result<u32, String> {
    let requested = number_at(value, &[key]).unwrap_or(fallback as f64);
    let rounded = requested.round();
    if (requested - rounded).abs() > 0.001 {
        return Err(format!("{key}_not_integral:{requested:.3}"));
    }
    if !rounded.is_finite() || rounded < min as f64 || rounded > max as f64 {
        return Err(format!("{key}_out_of_range:{rounded:.0}"));
    }
    Ok(rounded as u32)
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

    #[test]
    fn parses_environment_depth_alignment_candidate() {
        let value = json!({
            "schema": ENVIRONMENT_DEPTH_ALIGNMENT_SCHEMA,
            "revision": 9,
            "source": {
                "surface": "same_apk_panel",
                "transport": "jni_live_queue"
            },
            "depth_alignment": {
                "left_offset_uv": [0.010, -0.020],
                "right_offset_uv": [-0.030, 0.040],
                "sample_scale": 1.35
            },
            "apply": {
                "mode": "apply-on-next-safe-frame"
            }
        });

        let candidate =
            parse_environment_depth_alignment_json(&value.to_string()).expect("alignment parses");

        assert_eq!(candidate.revision, 9);
        assert_close(candidate.effective_offsets_uv[0][0], 0.010);
        assert_close(candidate.effective_offsets_uv[0][1], -0.020);
        assert_close(candidate.effective_offsets_uv[1][0], -0.030);
        assert_close(candidate.effective_offsets_uv[1][1], 0.040);
        assert_close(candidate.sample_scale, 1.35);
    }

    #[test]
    fn parses_private_particle_dynamics_candidate() {
        let value = json!({
            "schema": PRIVATE_PARTICLE_DYNAMICS_SCHEMA,
            "revision": 11,
            "source": {
                "surface": "same_apk_panel",
                "transport": "jni_live_queue"
            },
            "private_particles": {
                "visual_scale": 0.62,
                "world_anchor_scale_m": 0.88,
                "driver_values01": [0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80],
                "tracer": {
                    "draw_slots_per_oscillator": 9,
                    "lifetime_seconds": 1.25,
                    "copies_per_second": 22.5
                },
                "transparency": {
                    "opacity": 0.75,
                    "output_alpha_scale": 1.5,
                    "depth_suppression_strength": 2.25,
                    "rgb_alpha_coupling": 0.35
                },
                "color": {
                    "facing_attenuation_strength": 0.65
                }
            },
            "apply": {
                "mode": "apply-on-next-safe-frame"
            }
        });

        let candidate =
            parse_private_particle_dynamics_json(&value.to_string()).expect("dynamics parses");

        assert_eq!(candidate.revision, 11);
        assert_close(candidate.visual_scale, 0.62);
        assert_close(candidate.world_anchor_scale_m, 0.88);
        assert_close(candidate.driver_values01[0], 0.10);
        assert_close(candidate.driver_values01[7], 0.80);
        assert_eq!(candidate.tracer_draw_slots_per_oscillator, 9);
        assert_close(candidate.tracer_lifetime_seconds, 1.25);
        assert_close(candidate.tracer_copies_per_second, 22.5);
        assert_close(candidate.transparency_opacity, 0.75);
        assert_close(candidate.transparency_output_alpha_scale, 1.5);
        assert_close(candidate.transparency_depth_suppression_strength, 2.25);
        assert_close(candidate.transparency_rgb_alpha_coupling, 0.35);
        assert_close(candidate.color_facing_attenuation_strength, 0.65);
    }

    #[test]
    fn parses_private_particle_driver_controls() {
        let value = json!({
            "schema": PRIVATE_PARTICLE_DYNAMICS_SCHEMA,
            "revision": 13,
            "private_particles": {
                "visual_scale": 0.62,
                "world_anchor_scale_m": 0.88,
                "driver_values01": [0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80],
                "driver_controls": [
                    {
                        "target_slot": 2,
                        "mode": "manual",
                        "mode_code": 1,
                        "source_slot": 2,
                        "curve": "akd-hump",
                        "curve_code": 1,
                        "range_min": 0.04,
                        "range_max": 0.115,
                        "cycle_multiplier": 1.0
                    },
                    {
                        "target_slot": 3,
                        "mode": "input-slot",
                        "mode_code": 2,
                        "source_slot": 0,
                        "curve": "smoothstep",
                        "curve_code": 2,
                        "range_min": 0.0,
                        "range_max": 0.1,
                        "cycle_multiplier": 0.0
                    },
                    {
                        "target_slot": 4,
                        "mode": "oscillator",
                        "mode_code": 0,
                        "source_slot": 4,
                        "curve": "linear",
                        "curve_code": 0,
                        "range_min": 0.1,
                        "range_max": 0.5,
                        "cycle_multiplier": 2.0
                    }
                ],
                "tracer": {
                    "draw_slots_per_oscillator": 9,
                    "lifetime_seconds": 1.25,
                    "copies_per_second": 22.5
                }
            },
            "apply": {
                "mode": "apply-on-next-safe-frame"
            }
        });

        let candidate =
            parse_private_particle_dynamics_json(&value.to_string()).expect("dynamics parses");

        assert_eq!(
            candidate.driver_control_modes[2],
            PRIVATE_PARTICLE_DRIVER_CONTROL_MANUAL
        );
        assert_eq!(
            candidate.driver_control_modes[3],
            PRIVATE_PARTICLE_DRIVER_CONTROL_INPUT_SLOT
        );
        assert_eq!(candidate.driver_control_source_slots[3], 0);
        assert_eq!(
            candidate.driver_control_modes[4],
            PRIVATE_PARTICLE_DRIVER_CONTROL_OSCILLATOR
        );
        assert_eq!(
            candidate.driver_control_curve_codes[2],
            PRIVATE_PARTICLE_CURVE_AKD_HUMP
        );
        assert_eq!(
            candidate.driver_control_curve_codes[3],
            PRIVATE_PARTICLE_CURVE_SMOOTHSTEP
        );
        assert_close(candidate.driver_control_range_mins[2], 0.04);
        assert_close(candidate.driver_control_range_maxs[2], 0.115);
        assert_close(candidate.driver_control_cycle_multipliers[4], 2.0);
        assert_eq!(
            candidate.driver_control_modes[7],
            PRIVATE_PARTICLE_DRIVER_CONTROL_DIRECT
        );
    }

    #[test]
    fn rejects_out_of_range_private_particle_driver() {
        let value = json!({
            "schema": PRIVATE_PARTICLE_DYNAMICS_SCHEMA,
            "revision": 12,
            "private_particles": {
                "visual_scale": 0.62,
                "world_anchor_scale_m": 0.88,
                "driver_values01": [0.10, 0.20, 1.30, 0.40, 0.50, 0.60, 0.70, 0.80],
                "tracer": {
                    "draw_slots_per_oscillator": 9,
                    "lifetime_seconds": 1.25,
                    "copies_per_second": 22.5
                }
            },
            "apply": {
                "mode": "apply-on-next-safe-frame"
            }
        });

        let error = parse_private_particle_dynamics_json(&value.to_string()).unwrap_err();
        assert!(error.starts_with("driver_values01_2_out_of_range"));
    }
}
