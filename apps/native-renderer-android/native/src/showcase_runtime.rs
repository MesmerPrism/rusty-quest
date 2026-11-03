//! Closed visual control authority. Contains no sessions, histories or file IO.
use crate::automatic_breath::AutomaticBreath;
use crate::bounded_breath_phase_integrator::{BoundedBreathPhaseIntegrator, BreathHoldPolicy};
use rusty_quest_breath_contract::composition::{
    BreathCompositionSnapshot, BreathCompositionStatus,
};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, VecDeque},
    sync::{Mutex, OnceLock},
    time::{Instant, SystemTime, UNIX_EPOCH},
};

pub(crate) const PRODUCT: &str = "visual-showcase";
const COMMAND: &str = "rusty.quest.visual_showcase.command.v1";
const STATUS: &str = "rusty.quest.visual_showcase.status.v1";
const RECEIPT: &str = "rusty.quest.visual_showcase.receipt.v1";
const SETTINGS: &str = "rusty.quest.visual_showcase.settings.v1";
const MAX_BYTES: usize = 65536;
const MAX_PROFILES: usize = 16;
#[derive(Debug)]
pub(crate) struct ShowcaseRuntime {
    epoch: String,
    revision: u64,
    rendered_visual_revision: Option<u64>,
    rendered_radius_m: Option<f32>,
    rendered_material: Option<Value>,
    inputs: Value,
    visual: Option<Value>,
    profiles: BTreeMap<String, Value>,
    receipts: VecDeque<(String, String, Value)>,
    automatic: AutomaticBreath,
    integrator: BoundedBreathPhaseIntegrator,
    progress: f32,
    last_tick_ns: u64,
    breath_available: bool,
    heartbeat_available: bool,
}

impl ShowcaseRuntime {
    fn new(epoch: String) -> Self {
        Self {
            epoch,
            revision: 0,
            rendered_visual_revision: None,
            rendered_radius_m: None,
            rendered_material: None,
            inputs: json!({"breath_source":"automatic","breath_mapping":"state","heartbeat_source":"synthetic","synthetic_bpm":60.0,"controller_projection":"fixed-orientation","polar_projection":"xz","inverted":false}),
            visual: None,
            profiles: BTreeMap::new(),
            receipts: VecDeque::new(),
            automatic: AutomaticBreath::new(0, 0.0),
            integrator: BoundedBreathPhaseIntegrator::new(0.0),
            progress: 0.0,
            last_tick_ns: 0,
            breath_available: true,
            heartbeat_available: true,
        }
    }
    fn status(&self) -> Value {
        let driver_ranges: Vec<_> = (0..8)
            .map(crate::native_renderer_stimulus_panel::private_particle_canonical_driver_range)
            .collect();
        json!({"schema":STATUS,"product":PRODUCT,"epoch":self.epoch,"revision":self.revision,"rendered_visual_revision":self.rendered_visual_revision,"rendered_radius_m":self.rendered_radius_m,"rendered_material":self.rendered_material,"material_presets":self.material_presets(),"driver_ranges":driver_ranges,"o":{"e":self.epoch,"s":"observed"},"observed_at_ns":monotonic_ns().to_string(),"ready":self.visual.is_some(),"inputs":self.inputs,"visual":self.visual,"profiles":self.profiles,"availability":{"breath":self.breath_available,"heartbeat":self.heartbeat_available,"polar_required":self.inputs["breath_source"]=="polar-acc" || self.inputs["heartbeat_source"]=="polar","selected_polar_unavailable":(self.inputs["breath_source"]=="polar-acc" && !self.breath_available)||(self.inputs["heartbeat_source"]=="polar" && !self.heartbeat_available)},"applied_progress":self.progress,"automatic_phase":self.automatic.phase(self.last_tick_ns)})
    }
    fn material_presets(&self) -> Value {
        use crate::native_renderer_private_particle_material_request::PrivateParticleMaterialPreset;
        let mut map = serde_json::Map::new();
        for preset in [
            PrivateParticleMaterialPreset::CurrentAdditive,
            PrivateParticleMaterialPreset::PremultipliedAlphaOver,
            PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFade,
            PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFacingFade,
            PrivateParticleMaterialPreset::ReferenceMaterialEmulation,
        ] {
            let p = preset.parameters();
            map.insert(preset.marker_name().into(),json!({"transparency":{"opacity":p.opacity,"output_alpha_scale":p.output_alpha_scale,"depth_suppression_strength":p.depth_suppression_strength,"rgb_alpha_coupling":p.rgb_alpha_coupling},"color":{"facing_attenuation_strength":p.facing_attenuation_strength}}));
        }
        map.insert("packaged-default".into(),self.profiles.get("default").map(|builtin|json!({"transparency":builtin["transparency"],"color":builtin["color"]})).unwrap_or(Value::Null));
        Value::Object(map)
    }
    fn settings(&self) -> Value {
        json!({"schema":SETTINGS,"inputs":self.inputs,"visual":self.visual,"profiles":self.profiles})
    }
    fn execute(&mut self, raw: &str) -> Value {
        self.execute_with_clock(raw, monotonic_ns)
    }
    #[cfg(test)]
    fn execute_at(&mut self, raw: &str, accepted_at_ns: u64) -> Value {
        self.execute_with_clock(raw, || accepted_at_ns)
    }
    fn execute_with_clock(&mut self, raw: &str, mut accepted_at: impl FnMut() -> u64) -> Value {
        let result = (|| -> Result<(String, Value), String> {
            if raw.len() > MAX_BYTES {
                return Err("command_too_large".into());
            }
            let command: Value = serde_json::from_str(raw).map_err(|_| "malformed_json")?;
            exact(
                &command,
                &[
                    "schema",
                    "product",
                    "epoch",
                    "expected_revision",
                    "id",
                    "op",
                    "payload",
                ],
            )?;
            if command["schema"] != COMMAND || command["product"] != PRODUCT {
                return Err("product_or_schema_mismatch".into());
            }
            let id = token(&command["id"], 96)?;
            // Deduplication happens before revision validation; uncertain accepted
            // commands are reconciled, never applied twice.
            if let Some((_, original, receipt)) = self.receipts.iter().find(|(key, _, _)| key == id)
            {
                if original == raw {
                    return Ok((id.into(), receipt.clone()));
                }
                return Err("command_id_conflict".into());
            }
            if command["epoch"] != self.epoch {
                return Err("epoch_mismatch".into());
            }
            if command["expected_revision"].as_u64() != Some(self.revision) {
                return Err("revision_mismatch".into());
            }
            if self.visual.is_none() {
                return Err("not_initialized".into());
            }
            let payload = &command["payload"];
            match command["op"].as_str() {
                Some("set-inputs") => {
                    validate_inputs(payload)?;
                    let changed = self.inputs["breath_source"] != payload["breath_source"];
                    self.inputs = payload.clone();
                    if changed {
                        self.automatic = AutomaticBreath::new(accepted_at(), self.progress);
                        self.integrator.reset(self.progress);
                    }
                }
                Some("apply-visual") => {
                    exact(payload, &["visual"])?;
                    validate_visual(&payload["visual"])?;
                    self.visual = Some(payload["visual"].clone());
                }
                Some("save-profile") => {
                    exact(payload, &["name", "visual"])?;
                    let name = token(&payload["name"], 64)?.to_owned();
                    if name == "default" {
                        return Err("builtin_profile_immutable".into());
                    }
                    validate_visual(&payload["visual"])?;
                    if !self.profiles.contains_key(&name) && self.profiles.len() >= MAX_PROFILES {
                        return Err("profile_limit".into());
                    }
                    self.profiles.insert(name, payload["visual"].clone());
                }
                Some("apply-profile") => {
                    exact(payload, &["name"])?;
                    let name = token(&payload["name"], 64)?;
                    self.visual = Some(self.profiles.get(name).ok_or("profile_missing")?.clone());
                }
                Some("delete-profile") => {
                    exact(payload, &["name"])?;
                    let name = token(&payload["name"], 64)?;
                    if name == "default" {
                        return Err("builtin_profile_immutable".into());
                    }
                    if self.profiles.remove(name).is_none() {
                        return Err("profile_missing".into());
                    }
                }
                _ => return Err("unsupported_operation".into()),
            }
            self.revision = self.revision.checked_add(1).ok_or("revision_exhausted")?;
            Ok((
                id.into(),
                json!({"schema":RECEIPT,"product":PRODUCT,"epoch":self.epoch,"id":id,"status":"accepted","revision":self.revision}),
            ))
        })();
        match result {
            Ok((id, receipt)) => {
                if !self.receipts.iter().any(|(key, _, _)| key == &id) {
                    self.receipts.push_back((id, raw.into(), receipt.clone()));
                    while self.receipts.len() > 64 {
                        self.receipts.pop_front();
                    }
                }
                receipt
            }
            Err(reason) => self.remember_rejection(raw, &reason),
        }
    }
    fn remember_rejection(&mut self, raw: &str, reason: &str) -> Value {
        let command = serde_json::from_str::<Value>(raw).ok();
        let id = command
            .as_ref()
            .and_then(|v| v["id"].as_str())
            .filter(|id| !id.is_empty() && id.len() <= 96 && !id.chars().any(|c| c.is_control()));
        let receipt = json!({"schema":RECEIPT,"product":PRODUCT,"epoch":self.epoch,"id":id,"status":"rejected","reason":reason,"revision":self.revision});
        if let (Some(v), Some(id)) = (command.as_ref(), id) {
            if v["product"] == PRODUCT
                && v["schema"] == COMMAND
                && v["epoch"] == self.epoch
                && !self.receipts.iter().any(|(key, _, _)| key == id)
            {
                self.receipts
                    .push_back((id.into(), raw.into(), receipt.clone()));
                while self.receipts.len() > 64 {
                    self.receipts.pop_front();
                }
            }
        }
        receipt
    }
}

fn exact(value: &Value, keys: &[&str]) -> Result<(), String> {
    let object = value.as_object().ok_or("object_required")?;
    if object.len() != keys.len() || object.keys().any(|key| !keys.contains(&key.as_str())) {
        return Err("closed_fields".into());
    }
    Ok(())
}
fn token(value: &Value, max: usize) -> Result<&str, String> {
    let text = value.as_str().ok_or("string_required")?;
    if text.is_empty() || text.len() > max || text.chars().any(|c| c.is_control()) {
        return Err("invalid_token".into());
    }
    Ok(text)
}
fn choice(value: &Value, values: &[&str]) -> Result<(), String> {
    if !value.as_str().is_some_and(|s| values.contains(&s)) {
        return Err("invalid_choice".into());
    }
    Ok(())
}
fn number(value: &Value, min: f64, max: f64) -> Result<(), String> {
    if !value
        .as_f64()
        .is_some_and(|n| n.is_finite() && n >= min && n <= max)
    {
        return Err("number_out_of_range".into());
    }
    Ok(())
}
fn validate_inputs(v: &Value) -> Result<(), String> {
    exact(
        v,
        &[
            "breath_source",
            "breath_mapping",
            "heartbeat_source",
            "synthetic_bpm",
            "controller_projection",
            "polar_projection",
            "inverted",
        ],
    )?;
    choice(
        &v["breath_source"],
        &["automatic", "controller", "polar-acc"],
    )?;
    choice(&v["breath_mapping"], &["volume", "state"])?;
    if v["breath_source"] == "automatic" && v["breath_mapping"] != "state" {
        return Err("automatic_requires_state_mapping".into());
    }
    choice(&v["heartbeat_source"], &["off", "polar", "synthetic"])?;
    number(&v["synthetic_bpm"], 30.0, 240.0)?;
    choice(
        &v["controller_projection"],
        &["dynamic-axis", "fixed-orientation"],
    )?;
    choice(&v["polar_projection"], &["xz", "3d"])?;
    if !v["inverted"].is_boolean() {
        return Err("boolean_required".into());
    }
    Ok(())
}
pub(crate) fn validate_visual(v: &Value) -> Result<(), String> {
    if v.to_string().len() > 16384 {
        return Err("visual_too_large".into());
    }
    exact(
        v,
        &[
            "visual_scale",
            "size",
            "world_anchor_scale_m",
            "driver_values01",
            "driver_controls",
            "tracer",
            "transparency",
            "color",
            "material",
            "orbit_distance",
            "radius",
            "frame_extension",
        ],
    )?;
    exact(&v["radius"], &["minimum_m", "maximum_m"])?;
    number(&v["radius"]["minimum_m"], 0.05, 4.0)?;
    number(&v["radius"]["maximum_m"], 0.05, 4.0)?;
    if v["radius"]["minimum_m"].as_f64() > v["radius"]["maximum_m"].as_f64() {
        return Err("radius_range_inverted".into());
    }
    exact(
        &v["size"],
        &[
            "enabled",
            "mode",
            "world_meters",
            "sphere_radius_percent",
            "oscillation_percent",
        ],
    )?;
    exact(
        &v["tracer"],
        &[
            "draw_slots_per_oscillator",
            "lifetime_seconds",
            "copies_per_second",
        ],
    )?;
    exact(
        &v["transparency"],
        &[
            "opacity",
            "output_alpha_scale",
            "depth_suppression_strength",
            "rgb_alpha_coupling",
        ],
    )?;
    exact(&v["color"], &["facing_attenuation_strength"])?;
    exact(&v["material"], &["preset"])?;
    exact(
        &v["orbit_distance"],
        &["scale_with_particle_size", "reference_radius_m"],
    )?;
    if !v["orbit_distance"]["scale_with_particle_size"].is_boolean() {
        return Err("boolean_required".into());
    }
    exact(
        &v["frame_extension"],
        &["mode", "endpoint_gain", "midpoint_gain"],
    )?;
    choice(&v["frame_extension"]["mode"], &["constant", "radius"])?;
    number(&v["frame_extension"]["endpoint_gain"], 0.0, 8.0)?;
    number(&v["frame_extension"]["midpoint_gain"], 0.0, 8.0)?;
    if v["driver_values01"].as_array().map(Vec::len) != Some(8) {
        return Err("eight_driver_values_required".into());
    }
    let controls = v["driver_controls"]
        .as_array()
        .ok_or("driver_controls_required")?;
    if controls.len() != 8 {
        return Err("eight_driver_controls_required".into());
    }
    for (slot, control) in controls.iter().enumerate() {
        exact(
            control,
            &[
                "target_slot",
                "mode",
                "source_slot",
                "curve",
                "range_min",
                "range_max",
                "cycle_multiplier",
            ],
        )?;
        if control["target_slot"].as_u64() != Some(slot as u64) {
            return Err("ordered_unique_driver_slots_required".into());
        }
        choice(
            &control["mode"],
            &["direct", "manual", "input-slot", "oscillator"],
        )?;
        choice(
            &control["curve"],
            &[
                "linear",
                "hump",
                "smoothstep",
                "reverse-linear",
                "hold-low",
                "hold-high",
            ],
        )?;
    }

    for (object, fields) in [
        (v, &["visual_scale", "world_anchor_scale_m"][..]),
        (
            &v["size"],
            &[
                "world_meters",
                "sphere_radius_percent",
                "oscillation_percent",
            ][..],
        ),
        (&v["tracer"], &["lifetime_seconds", "copies_per_second"][..]),
        (
            &v["transparency"],
            &[
                "opacity",
                "output_alpha_scale",
                "depth_suppression_strength",
                "rgb_alpha_coupling",
            ][..],
        ),
        (&v["color"], &["facing_attenuation_strength"][..]),
        (&v["orbit_distance"], &["reference_radius_m"][..]),
    ] {
        for field in fields {
            number(&object[*field], -1000.0, 1000.0)?;
        }
    }
    if !v["size"]["enabled"].is_boolean() {
        return Err("size_boolean_required".into());
    }
    choice(
        &v["size"]["mode"],
        &[
            "legacy-payload-envelope",
            "sphere-radius-percent",
            "world-meters",
        ],
    )?;
    if !v["tracer"]["draw_slots_per_oscillator"]
        .as_u64()
        .is_some_and(|n| n <= 7)
    {
        return Err("tracer_capacity".into());
    }
    for (slot, control) in controls.iter().enumerate() {
        let (minimum, maximum) =
            crate::native_renderer_stimulus_panel::private_particle_canonical_driver_range(slot);
        for field in ["range_min", "range_max"] {
            let n = control[field].as_f64().ok_or("driver_number_required")?;
            if !n.is_finite() || (n as f32) < minimum || (n as f32) > maximum {
                return Err("driver_canonical_range".into());
            }
        }
        if !control["source_slot"].as_u64().is_some_and(|n| n < 8) {
            return Err("driver_source_slot".into());
        }
        for field in ["range_min", "range_max", "cycle_multiplier"] {
            number(&control[field], -1000.0, 1000.0)?;
        }
    }
    // Reuse the maintained typed renderer parser for every visual range.
    let mut particles = v.clone();
    particles.as_object_mut().unwrap().remove("frame_extension");
    particles.as_object_mut().unwrap().remove("radius");
    crate::native_renderer_stimulus_panel::parse_private_particle_dynamics_json(&json!({"schema":crate::native_renderer_stimulus_panel::PRIVATE_PARTICLE_DYNAMICS_SCHEMA,"revision":0,"private_particles":particles}).to_string())?;
    Ok(())
}

fn state() -> &'static Mutex<ShowcaseRuntime> {
    static STATE: OnceLock<Mutex<ShowcaseRuntime>> = OnceLock::new();
    STATE.get_or_init(|| {
        let epoch = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
            .max(1)
            .to_string();
        Mutex::new(ShowcaseRuntime::new(epoch))
    })
}
fn lock() -> std::sync::MutexGuard<'static, ShowcaseRuntime> {
    state().lock().unwrap_or_else(|e| e.into_inner())
}
pub(crate) fn monotonic_ns() -> u64 {
    static START: OnceLock<Instant> = OnceLock::new();
    START
        .get_or_init(Instant::now)
        .elapsed()
        .as_nanos()
        .min(u64::MAX as u128) as u64
}
pub(crate) fn status_json() -> String {
    lock().status().to_string()
}
pub(crate) fn settings_json() -> String {
    lock().settings().to_string()
}
pub(crate) fn command_json(raw: &str) -> String {
    let answer = lock().execute(raw);
    if answer["status"] == "accepted"
        && serde_json::from_str::<Value>(raw).is_ok_and(|v| v["op"] == "set-inputs")
    {
        select_breath_input();
    }
    answer.to_string()
}
pub(crate) fn receipt_json(id: &str) -> String {
    let s = lock();
    s.receipts.iter().find(|(key,_,_)|key==id).map(|(_,_,value)|value.clone()).unwrap_or_else(||json!({"schema":RECEIPT,"product":PRODUCT,"epoch":s.epoch,"id":id,"status":"unknown","revision":s.revision})).to_string()
}

pub(crate) fn reconcile_receipt(
    id: &str,
    mut observe: impl FnMut(&str) -> Result<Value, String>,
) -> String {
    // Snapshot acquisition identity, then release the authority before entering
    // the Android executor. Observation is never a command retry.
    let pending = {
        let s = lock();
        s.receipts
            .iter()
            .find(|(key, _, _)| key == id)
            .and_then(|(_, _, r)| {
                if r["status"] != "pending" {
                    return None;
                }
                r["acquisition_id"]
                    .as_str()
                    .or_else(|| r["effective"]["acquisition_receipt"]["id"].as_str())
                    .map(|key| (s.epoch.clone(), key.to_owned()))
            })
    };
    if let Some((epoch, acquisition_id)) = pending {
        if let Ok(observation) = observe(&acquisition_id) {
            let mut s = lock();
            if s.epoch == epoch {
                if let Some((_, _, receipt)) = s.receipts.iter_mut().find(|(key, _, _)| key == id) {
                    if receipt["status"] == "pending" {
                        receipt["status"] = json!(match observation["status"].as_str() {
                            Some("applied") => "accepted",
                            Some("rejected") => "rejected",
                            Some("pending") => "pending",
                            _ => "unknown",
                        });
                        receipt["effective"] = observation;
                    }
                }
            }
        }
    }
    receipt_json(id)
}
pub(crate) fn initialize(defaults: &str, persisted: &str, migrations: &str) -> Result<(), String> {
    if defaults.len() > 16384 || persisted.len() > MAX_BYTES * 4 {
        return Err("settings_too_large".into());
    }
    let visual: Value = serde_json::from_str(defaults).map_err(|_| "default_json")?;
    validate_visual(&visual)?;
    let trusted = parse_builtin_migrations(migrations)?;
    let mut s = lock();
    if s.visual.is_some() {
        return Ok(());
    }
    let mut candidate = ShowcaseRuntime::new(s.epoch.clone());
    candidate.visual = Some(visual.clone());
    candidate.profiles.insert("default".into(), visual);
    if !persisted.is_empty() {
        let p: Value = serde_json::from_str(persisted).map_err(|_| "settings_json")?;
        restore_persisted_settings(&mut candidate, &p, &trusted)?;
    }
    *s = candidate;
    drop(s);
    select_breath_input();
    Ok(())
}
#[derive(Clone, Debug)]
struct TrustedBuiltin {
    settings_schema: String,
    visual: Value,
}

fn parse_builtin_migrations(text: &str) -> Result<Vec<TrustedBuiltin>, String> {
    if text.len() > 131072 {
        return Err("builtin_migrations_too_large".into());
    }
    let value: Value = serde_json::from_str(text).map_err(|_| "builtin_migrations_json")?;
    exact(&value, &["schema", "entries"])?;
    if value["schema"] != "rusty.quest.visual_showcase.builtin_migrations.v1" {
        return Err("builtin_migrations_schema".into());
    }
    let entries = value["entries"]
        .as_array()
        .ok_or("builtin_migrations_entries")?;
    if entries.len() > 8 {
        return Err("builtin_migrations_limit".into());
    }
    let mut trusted = Vec::new();
    for entry in entries {
        exact(
            entry,
            &[
                "settings_schema",
                "source_version",
                "source_visual_sha256",
                "visual",
            ],
        )?;
        let settings_schema = token(&entry["settings_schema"], 128)?.to_owned();
        token(&entry["source_version"], 96)?;
        let digest = entry["source_visual_sha256"]
            .as_str()
            .ok_or("builtin_migration_digest")?;
        if digest.len() != 64 || !digest.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("builtin_migration_digest".into());
        }
        validate_visual(&entry["visual"])?;
        trusted.push(TrustedBuiltin {
            settings_schema,
            visual: entry["visual"].clone(),
        });
    }
    Ok(trusted)
}

fn restore_persisted_settings(
    candidate: &mut ShowcaseRuntime,
    p: &Value,
    trusted: &[TrustedBuiltin],
) -> Result<(), String> {
    exact(&p, &["schema", "inputs", "visual", "profiles"])?;
    if p["schema"] != SETTINGS {
        return Err("settings_schema".into());
    }
    validate_inputs(&p["inputs"])?;
    validate_visual(&p["visual"])?;
    let profiles = p["profiles"].as_object().ok_or("profiles_required")?;
    if profiles.len() > MAX_PROFILES {
        return Err("profile_limit".into());
    }
    let mut restored = BTreeMap::new();
    for (name, profile) in profiles {
        token(&Value::String(name.clone()), 64)?;
        validate_visual(profile)?;
        if name != "default" {
            restored.insert(name.clone(), profile.clone());
        }
    }
    restored.insert("default".into(), candidate.profiles["default"].clone());
    // The reserved builtin follows the provided release asset. Only a still-
    // identical old builtin may follow it automatically; user edits stay owned.
    let recognized = trusted.iter().any(|entry| {
        p["schema"] == entry.settings_schema
            && entry.visual == p["visual"]
            && profiles.get("default") == Some(&entry.visual)
    });
    let active = if recognized {
        candidate.profiles["default"].clone()
    } else {
        p["visual"].clone()
    };
    candidate.inputs = p["inputs"].clone();
    candidate.visual = Some(active);
    candidate.profiles = restored;
    Ok(())
}

fn select_breath_input() {
    let input = lock().inputs.clone();
    let source = if input["breath_source"] == "polar-acc" {
        "polar-acc"
    } else {
        "controller"
    };
    let command = json!({"schema":"rusty.quest.breath_composition.command.v1","operation":"select","source":source,"mapping":input["breath_mapping"],"controller_projection":input["controller_projection"],"polar_projection":input["polar_projection"],"inverted":input["inverted"]});
    let _ = crate::breath_composition_runtime::apply_command_json(&command.to_string());
    if input["breath_source"] != "automatic" {
        let _ = crate::breath_composition_runtime::ensure_running_for_visual_use();
    }
}
/// Immutable render-thread snapshot. Requests accepted after preparation belong
/// to the next frame; no GPU parameter is read from requested global visuals.
#[derive(Clone, Debug)]
pub(crate) struct PreparedVisualFrame {
    epoch: String,
    candidate: crate::native_renderer_stimulus_panel::PrivateParticleDynamicsPanelCandidate,
    progress: f32,
    radius_m: f32,
    extension: [[f32; 4]; 2],
}
impl PreparedVisualFrame {
    pub(crate) fn revision(&self) -> i64 {
        self.candidate.revision
    }
    pub(crate) fn candidate(
        &self,
    ) -> crate::native_renderer_stimulus_panel::PrivateParticleDynamicsPanelCandidate {
        self.candidate.clone()
    }
    pub(crate) fn progress(&self) -> f32 {
        self.progress
    }
    pub(crate) fn radius_m(&self) -> f32 {
        self.radius_m
    }
    pub(crate) fn frame_extension(&self) -> [[f32; 4]; 2] {
        self.extension
    }
}
pub(crate) fn prepare_visual_frame() -> Option<PreparedVisualFrame> {
    prepare_visual_frame_from(&lock())
}
fn prepare_visual_frame_from(s: &ShowcaseRuntime) -> Option<PreparedVisualFrame> {
    let visual = s.visual.as_ref()?;
    let revision = i64::try_from(s.revision).ok()?;
    let progress = s.progress;
    let radius = &visual["radius"];
    let minimum = radius["minimum_m"].as_f64()? as f32;
    let maximum = radius["maximum_m"].as_f64()? as f32;
    let extension = &visual["frame_extension"];
    let extension_rows = [
        [
            7601.0,
            1.0,
            1.0,
            if extension["mode"] == "radius" {
                1.0
            } else {
                0.0
            },
        ],
        [
            progress,
            extension["endpoint_gain"].as_f64()? as f32,
            extension["midpoint_gain"].as_f64()? as f32,
            0.0,
        ],
    ];
    let mut particles = visual.clone();
    particles.as_object_mut()?.remove("frame_extension");
    particles.as_object_mut()?.remove("radius");
    particles["orbit_distance"]["source"] = json!(match s.inputs["heartbeat_source"].as_str() {
        Some("polar") => "polar-heartbeat",
        Some("synthetic") => "synthetic-heartbeat",
        _ => "static-minimum",
    });
    particles["orbit_distance"]["synthetic_bpm"] = s.inputs["synthetic_bpm"].clone();
    let candidate=crate::native_renderer_stimulus_panel::parse_private_particle_dynamics_json(&json!({"schema":crate::native_renderer_stimulus_panel::PRIVATE_PARTICLE_DYNAMICS_SCHEMA,"revision":revision,"private_particles":particles}).to_string()).ok()?;
    Some(PreparedVisualFrame {
        epoch: s.epoch.clone(),
        candidate,
        progress,
        radius_m: minimum + (maximum - minimum) * progress,
        extension: extension_rows,
    })
}
pub(crate) fn tick(snapshot: BreathCompositionSnapshot, dt: f32, rr_fresh: bool) -> f32 {
    advance_frame(&mut lock(), snapshot, dt, rr_fresh, monotonic_ns())
}
fn advance_frame(
    s: &mut ShowcaseRuntime,
    snapshot: BreathCompositionSnapshot,
    dt: f32,
    rr_fresh: bool,
    now: u64,
) -> f32 {
    s.last_tick_ns = now;
    let automatic = s.inputs["breath_source"] == "automatic";
    let valid = snapshot.status == BreathCompositionStatus::Running
        && snapshot.output.is_some_and(|output| {
            s.inputs["breath_source"] == output.source.as_str()
                && s.inputs["breath_mapping"] == output.mapping.as_str()
        })
        && snapshot.rejection.is_none()
        && snapshot.latest_assessment.is_some_and(|a| {
            a.tracking == rusty_quest_breath_contract::assessment::BreathTrackingState::Valid
        });
    s.breath_available = automatic || valid;
    s.heartbeat_available = s.inputs["heartbeat_source"] != "polar" || rr_fresh;
    if automatic {
        s.progress = s.automatic.advance(now);
    } else if valid {
        if let Some(output) = snapshot.output {
            if let Some(value) = output.volume01 {
                s.progress = value as f32;
                let progress = s.progress;
                s.integrator.reset(progress);
            } else if let Some(phase) = output.phase {
                s.integrator
                    .update(phase, dt, 0.25, 0.25, BreathHoldPolicy::Hold, 0.0);
                s.progress = s.integrator.value01();
            }
        }
    }
    s.progress
}

/// Acknowledgement is supplied only after the exact frame's xrEndFrame result.
/// CPU preparation and command acceptance are not rendering evidence.
pub(crate) fn confirm_visual_frame_submission(
    frame: &PreparedVisualFrame,
    submitted: bool,
    ready: bool,
    visible: bool,
    draw_count: u32,
    material: Value,
) {
    if !submitted || !ready || !visible || draw_count == 0 {
        return;
    }
    let mut s = lock();
    if frame.epoch == s.epoch && frame.revision() >= 0 && frame.revision() as u64 <= s.revision {
        s.rendered_visual_revision = Some(frame.revision() as u64);
        s.rendered_radius_m = Some(frame.radius_m());
        s.rendered_material = Some(material);
    }
}
pub(crate) fn command_with_executor(
    raw: &str,
    mut executor: impl FnMut(&str, &Value) -> Result<Value, String>,
) -> String {
    if raw.len() > MAX_BYTES {
        return lock()
            .remember_rejection("", "command_too_large")
            .to_string();
    }
    let parsed = serde_json::from_str::<Value>(raw).ok();
    let platform = parsed
        .as_ref()
        .and_then(|v| v["op"].as_str())
        .is_some_and(|op| {
            matches!(
                op,
                "calibrate"
                    | "polar-scan"
                    | "polar-select"
                    | "polar-connect"
                    | "polar-disconnect"
                    | "polar-start-acc"
                    | "polar-stop-acc"
                    | "set-phone-control"
            )
        });
    if !platform {
        return command_json(raw);
    }
    let reservation = (|| -> Result<(String, String, Value), String> {
        if raw.len() > MAX_BYTES {
            return Err("command_too_large".into());
        }
        let v = parsed.as_ref().ok_or("malformed_json")?;
        exact(
            v,
            &[
                "schema",
                "product",
                "epoch",
                "expected_revision",
                "id",
                "op",
                "payload",
            ],
        )?;
        let id = token(&v["id"], 96)?.to_owned();
        let mut s = lock();
        if s.receipts.iter().any(|(key, _, _)| key == &id) {
            return Err("already_observed".into());
        }
        if v["schema"] != COMMAND || v["product"] != PRODUCT || v["epoch"] != s.epoch {
            return Err("identity_mismatch".into());
        }
        if v["expected_revision"].as_u64() != Some(s.revision) {
            return Err("revision_mismatch".into());
        }
        let op = v["op"].as_str().ok_or("operation_required")?;
        let p = &v["payload"];
        match op {
            "calibrate" => {
                exact(p, &["action"])?;
                choice(&p["action"], &["start", "cancel", "reset"])?;
            }
            "polar-select" => {
                exact(p, &["device_id"])?;
                token(&p["device_id"], 128)?;
            }
            "set-phone-control" => {
                exact(p, &["enabled"])?;
                if !p["enabled"].is_boolean() {
                    return Err("boolean_required".into());
                }
            }
            _ => exact(p, &[])?,
        }
        s.revision = s.revision.checked_add(1).ok_or("revision_exhausted")?;
        let receipt = json!({"schema":RECEIPT,"product":PRODUCT,"epoch":s.epoch,"revision":s.revision,"id":id,"status":"pending"});
        s.receipts.push_back((id.clone(), raw.into(), receipt));
        while s.receipts.len() > 64 {
            s.receipts.pop_front();
        }
        Ok((id, op.into(), p.clone()))
    })();
    let (id, op, payload) = match reservation {
        Ok(v) => v,
        Err(reason) => {
            if reason == "already_observed" {
                let v = parsed.unwrap();
                let id = v["id"].as_str().unwrap_or_default();
                let s = lock();
                return s.receipts.iter().find(|(key,_,_)|key==id).map(|(_,original,r)|if original==raw{r.to_string()}else{json!({"schema":RECEIPT,"product":PRODUCT,"status":"rejected","reason":"command_id_conflict"}).to_string()}).unwrap_or_default();
            }
            return lock().remember_rejection(raw, &reason).to_string();
        }
    };
    // No authority lock is held across acquisition/UI callbacks.
    let outcome = if op == "calibrate" {
        let action = payload["action"].as_str().unwrap_or_default();
        let result = if action == "start" {
            crate::breath_composition_runtime::start_calibration("showcase-command")
        } else {
            {
                let mut command = json!({"schema":"rusty.quest.breath_composition.command.v1","operation":action});
                if action == "cancel" {
                    command["generation"] = json!(crate::breath_composition_runtime::snapshot()
                        .generation
                        .map_or(0, |g| g.get()));
                }
                crate::breath_composition_runtime::apply_command_json(&command.to_string())
            }
        };
        serde_json::from_str::<Value>(&result).map(|value|json!({"status":if value["status"]=="accepted"{"applied"}else{"rejected"},"effective":value})).map_err(|e|e.to_string())
    } else {
        executor(&op, &payload)
    };
    let mut s = lock();
    if let Some((_, _, receipt)) = s.receipts.iter_mut().find(|(key, _, _)| key == &id) {
        match outcome {
            Ok(value) => {
                // This key belongs to the original executor result, independent
                // of later replacement observations. Polling can never retarget it.
                if let Some(key) = value["acquisition_receipt"]["id"]
                    .as_str()
                    .filter(|key| !key.is_empty() && key.len() <= 128)
                {
                    receipt["acquisition_id"] = json!(key);
                }
                receipt["status"] = json!(match value["status"].as_str() {
                    Some("applied") => "accepted",
                    Some("pending") => "pending",
                    Some("rejected") => "rejected",
                    _ => "unknown",
                });
                receipt["effective"] = value;
            }
            Err(reason) => {
                receipt["status"] = json!("unknown");
                receipt["reason"] = json!(reason);
            }
        }
        return receipt.to_string();
    }
    json!({"status":"unknown","id":id}).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn visual() -> Value {
        json!({"visual_scale":0.5,"size":{"enabled":true,"mode":"world-meters","world_meters":0.01,"sphere_radius_percent":1.0,"oscillation_percent":0.0},"world_anchor_scale_m":1.0,"radius":{"minimum_m":0.5,"maximum_m":1.5},"driver_values01":[0,1,0,0,0,0,0,0],"driver_controls":(0..8).map(|i|json!({"target_slot":i,"mode":"direct","source_slot":i,"curve":"linear","range_min":crate::native_renderer_stimulus_panel::private_particle_canonical_driver_range(i as usize).0,"range_max":crate::native_renderer_stimulus_panel::private_particle_canonical_driver_range(i as usize).1,"cycle_multiplier":0.0})).collect::<Vec<_>>(),"tracer":{"draw_slots_per_oscillator":0,"lifetime_seconds":0.5,"copies_per_second":1.0},"transparency":{"opacity":1.0,"output_alpha_scale":1.0,"depth_suppression_strength":0.0,"rgb_alpha_coupling":0.0},"color":{"facing_attenuation_strength":0.0},"material":{"preset":"packaged-default"},"orbit_distance":{"scale_with_particle_size":false,"reference_radius_m":0.03},"frame_extension":{"mode":"constant","endpoint_gain":1.0,"midpoint_gain":0.0}})
    }
    fn state() -> ShowcaseRuntime {
        let mut s = ShowcaseRuntime::new("10".into());
        s.visual = Some(visual());
        s.profiles.insert("default".into(), visual());
        s
    }
    fn command(s: &ShowcaseRuntime, id: &str, op: &str, payload: Value) -> String {
        json!({"schema":COMMAND,"product":PRODUCT,"epoch":s.epoch,"expected_revision":s.revision,"id":id,"op":op,"payload":payload}).to_string()
    }
    #[test]
    fn complete_visual_is_closed_typed_and_input_free() {
        assert!(validate_visual(&visual()).is_ok());
        for key in ["heartbeat_source", "session", "recording", "breath_source"] {
            let mut v = visual();
            v[key] = json!("anything");
            assert!(validate_visual(&v).is_err());
        }
        let mut v = visual();
        v["orbit_distance"]["synthetic_bpm"] = json!(60);
        assert!(validate_visual(&v).is_err());
        let mut v = visual();
        v["size"]["world_meters"] = json!("invalid");
        assert!(validate_visual(&v).is_err());
        let mut v = visual();
        v["driver_controls"][1]["target_slot"] = json!(0);
        assert!(validate_visual(&v).is_err());
        let mut v = visual();
        v["radius"]["minimum_m"] = json!(2.0);
        assert!(validate_visual(&v).is_err());
    }
    #[test]
    fn commands_bind_product_epoch_revision_and_deduplicate() {
        let mut s = state();
        let raw = command(&s, "one", "apply-visual", json!({"visual":visual()}));
        let first = s.execute(&raw);
        assert_eq!(first["status"], "accepted");
        assert_eq!(s.execute(&raw), first);
        assert_eq!(s.revision, 1);
        let wrong = command(&s, "two", "apply-visual", json!({"visual":visual()}));
        let mut v: Value = serde_json::from_str(&wrong).unwrap();
        v["epoch"] = json!("11");
        assert_eq!(s.execute(&v.to_string())["status"], "rejected");
        v["epoch"] = json!("10");
        v["product"] = json!("experiment");
        assert_eq!(s.execute(&v.to_string())["status"], "rejected");
    }
    #[test]
    fn rejected_outcome_is_reconciled_and_never_later_applied() {
        let mut s = state();
        let raw = command(&s, "bad", "apply-visual", json!({"visual":{}}));
        let first = s.execute(&raw);
        assert_eq!(first["status"], "rejected");
        assert_eq!(first["id"], "bad");
        assert_eq!(s.execute(&raw), first);
        assert_eq!(s.revision, 0);
    }
    #[test]
    fn applying_profile_cannot_change_input_selection() {
        let mut s = state();
        let inputs = s.inputs.clone();
        let raw = command(&s, "profile", "apply-profile", json!({"name":"default"}));
        assert_eq!(s.execute(&raw)["status"], "accepted");
        assert_eq!(s.inputs, inputs);
    }
    #[test]
    fn polar_loss_holds_progress_without_fallback_and_recovery_uses_selected_source() {
        use rusty_quest_breath_contract::{
            assessment::{BreathAssessmentObservation, BreathTrackingState, CommonBreathPhase},
            calibration::CalibrationLifecycle,
            composition::{
                BreathCompositionMapping, BreathCompositionOutput, BreathCompositionSource,
            },
            BreathGeneration, BreathTimestampMicros,
        };
        let mut s = state();
        s.inputs["breath_source"] = json!("polar-acc");
        s.inputs["heartbeat_source"] = json!("polar");
        s.inputs["breath_mapping"] = json!("volume");
        s.progress = 0.6;
        s.integrator.reset(0.6);
        let mut snapshot = crate::breath_composition_runtime::snapshot();
        snapshot.output = None;
        assert_eq!(
            advance_frame(&mut s, snapshot, 1.0, false, 2_000_000_000),
            0.6
        );
        assert_eq!(
            advance_frame(&mut s, snapshot, 1.0, false, 100_000_000_000),
            0.6
        );
        assert_eq!(s.inputs["breath_source"], "polar-acc");
        assert_eq!(s.inputs["heartbeat_source"], "polar");
        assert!(!s.breath_available);
        assert!(!s.heartbeat_available);
        let generation = BreathGeneration::new(1).unwrap();
        let at = BreathTimestampMicros::new(1);
        snapshot.status = BreathCompositionStatus::Running;
        snapshot.rejection = None;
        snapshot.output = Some(BreathCompositionOutput {
            source: BreathCompositionSource::PolarAcc,
            mapping: BreathCompositionMapping::Volume,
            sequence_id: 1,
            sampled_at: at,
            volume01: Some(0.8),
            phase: None,
            quality01: 1.0,
        });
        snapshot.latest_assessment = Some(BreathAssessmentObservation {
            schema_id:
                rusty_quest_breath_contract::assessment::BREATH_ASSESSMENT_OBSERVATION_SCHEMA_ID,
            generation,
            sequence_id: 1,
            sampled_at: at,
            observed_at: at,
            volume01: Some(0.8),
            phase: CommonBreathPhase::Inhale,
            calibration: CalibrationLifecycle::Ready,
            tracking: BreathTrackingState::Valid,
            quality01: 1.0,
        });
        assert_eq!(
            advance_frame(&mut s, snapshot, 0.1, true, 101_000_000_000),
            0.8
        );
        assert!(s.breath_available && s.heartbeat_available);
    }
    #[test]
    fn automatic_entry_seeds_current_progress_and_rejects_volume_label() {
        let mut s = state();
        s.inputs["breath_source"] = json!("controller");
        s.progress = 0.5;
        s.last_tick_ns = 2_000_000_000;
        let mut inputs = s.inputs.clone();
        inputs["breath_source"] = json!("automatic");
        let raw = command(&s, "auto", "set-inputs", inputs.clone());
        assert_eq!(s.execute_at(&raw, 2_000_000_000)["status"], "accepted");
        let snapshot = crate::breath_composition_runtime::snapshot();
        assert_eq!(
            advance_frame(&mut s, snapshot, 0.1, false, 4_000_000_000),
            1.0
        );
        assert_eq!(s.automatic.phase(5_000_000_000), "inhale");
        inputs["breath_mapping"] = json!("volume");
        assert!(validate_inputs(&inputs).is_err());
    }
    #[test]
    fn automatic_activation_during_render_suspend_starts_at_command_acceptance() {
        let mut s = state();
        s.inputs["breath_source"] = json!("controller");
        s.progress = 0.5;
        s.last_tick_ns = 1_000_000_000;
        let mut inputs = s.inputs.clone();
        inputs["breath_source"] = json!("automatic");
        let raw = command(&s, "activate-after-suspend", "set-inputs", inputs);
        assert_eq!(s.execute_at(&raw, 100_000_000_000)["status"], "accepted");
        let snapshot = crate::breath_composition_runtime::snapshot();
        assert_eq!(
            advance_frame(&mut s, snapshot, 0.1, false, 100_000_000_000),
            0.5
        );
        assert_eq!(
            advance_frame(&mut s, snapshot, 0.1, false, 102_000_000_000),
            1.0
        );
        assert_eq!(s.automatic.phase(103_999_999_999), "inhale");
        assert_eq!(s.automatic.phase(104_000_000_000), "full-hold");
        // Ordinary suspension keeps the already-active pacer clock.
        advance_frame(&mut s, snapshot, 0.1, false, 110_000_000_000);
        assert_eq!(s.automatic.phase(110_000_000_000), "exhale");
    }
    #[test]
    fn repeated_pending_acquisition_observations_keep_original_identity_and_reconcile() {
        struct Restore(Option<ShowcaseRuntime>);
        impl Drop for Restore {
            fn drop(&mut self) {
                *lock() = self.0.take().unwrap();
            }
        }
        let _restore = Restore(Some(std::mem::replace(&mut *lock(), state())));
        let raw = command(&lock(), "stop-original", "polar-stop-acc", json!({}));
        let mut effects = 0;
        let initial:Value=serde_json::from_str(&command_with_executor(&raw,|_,_|{effects+=1;Ok(json!({"status":"pending","acquisition_receipt":{"id":"acquisition-original","state":"pending"},"effective":{"acc_requested":true}}))})).unwrap();
        assert_eq!(initial["status"], "pending");
        assert_eq!(effects, 1);
        let mut observations = Vec::new();
        let first:Value=serde_json::from_str(&reconcile_receipt("stop-original",|id|{observations.push(id.to_owned());Ok(json!({"status":"pending","acquisition_receipt":{"id":"unexpected-observation-key"},"effective":{"acc_requested":false,"acc_ack":true}}))})).unwrap();
        assert_eq!(first["status"], "pending");
        let second: Value = serde_json::from_str(&reconcile_receipt("stop-original", |id| {
            observations.push(id.to_owned());
            Ok(json!({"status":"applied","effective":{"acc_requested":false,"acc_ack":false}}))
        }))
        .unwrap();
        assert_eq!(
            observations,
            vec!["acquisition-original", "acquisition-original"]
        );
        assert_eq!(second["status"], "accepted");
        assert_eq!(second["acquisition_id"], "acquisition-original");
        for field in ["id", "epoch", "revision", "product"] {
            assert_eq!(second[field], initial[field]);
        }
        assert_eq!(
            command_with_executor(&raw, |_, _| {
                effects += 1;
                panic!("uncertain command was replayed")
            }),
            second.to_string()
        );
        assert_eq!(effects, 1);
    }
    #[test]
    fn prepared_visual_keeps_radius_and_extension_when_new_request_arrives_midframe() {
        struct Restore(Option<ShowcaseRuntime>);
        impl Drop for Restore {
            fn drop(&mut self) {
                *lock() = self.0.take().unwrap();
            }
        }
        let _restore = Restore(Some(std::mem::replace(&mut *lock(), state())));
        lock().progress = 0.25;
        let prepared = prepare_visual_frame().unwrap();
        let old_radius = prepared.radius_m();
        let old_extension = prepared.frame_extension();
        let inputs = lock().inputs.clone();
        let mut next = visual();
        next["radius"] = json!({"minimum_m":2.0,"maximum_m":3.0});
        next["frame_extension"]["endpoint_gain"] = json!(4.0);
        next["transparency"]["opacity"] = json!(0.3);
        let raw = command(
            &lock(),
            "new-profile-midframe",
            "apply-visual",
            json!({"visual":next}),
        );
        assert_eq!(
            command_json(&raw).parse::<Value>().unwrap()["status"],
            "accepted"
        );
        assert_eq!(prepared.revision(), 0);
        assert_eq!(prepared.progress(), 0.25);
        assert_eq!(prepared.radius_m(), old_radius);
        assert_eq!(old_radius, 0.75);
        assert_eq!(prepared.frame_extension(), old_extension);
        assert_eq!(prepared.candidate().transparency_opacity, 1.0);
        assert_eq!(lock().inputs, inputs);
        let next_frame = prepare_visual_frame().unwrap();
        assert_eq!(next_frame.revision(), 1);
        assert_eq!(next_frame.radius_m(), 2.25);
        assert_eq!(next_frame.frame_extension()[1][1], 4.0);
        assert_eq!(next_frame.candidate().transparency_opacity, 0.3);
        assert_eq!(
            prepared.candidate().orbit_distance.source,
            next_frame.candidate().orbit_distance.source
        );
        assert_eq!(
            prepared.candidate().orbit_distance.synthetic_bpm,
            next_frame.candidate().orbit_distance.synthetic_bpm
        );
    }
    #[test]
    fn preparation_without_visible_submission_cannot_acknowledge_rendering() {
        struct Restore(Option<ShowcaseRuntime>);
        impl Drop for Restore {
            fn drop(&mut self) {
                *lock() = self.0.take().unwrap();
            }
        }
        let _restore = Restore(Some(std::mem::replace(&mut *lock(), state())));
        let prepared = prepare_visual_frame().unwrap();
        for (submitted, ready, visible, count) in [
            (false, true, true, 10),
            (true, false, true, 10),
            (true, true, false, 10),
            (true, true, true, 0),
        ] {
            confirm_visual_frame_submission(
                &prepared,
                submitted,
                ready,
                visible,
                count,
                json!({"source":"test-submitted-frame"}),
            );
            assert_eq!(lock().rendered_visual_revision, None);
        }
        let mut next = visual();
        next["visual_scale"] = json!(0.9);
        let raw = command(
            &lock(),
            "pending-after-preparation",
            "apply-visual",
            json!({"visual":next}),
        );
        assert_eq!(
            command_json(&raw).parse::<Value>().unwrap()["status"],
            "accepted"
        );
        confirm_visual_frame_submission(
            &prepared,
            true,
            true,
            true,
            10,
            json!({"source":"test-submitted-frame"}),
        );
        let s = lock();
        assert_eq!(s.revision, 1);
        assert_eq!(s.rendered_visual_revision, Some(0));
        assert_eq!(s.visual.as_ref().unwrap()["visual_scale"], 0.9);
        drop(s);
        let next_frame = prepare_visual_frame().unwrap();
        confirm_visual_frame_submission(
            &next_frame,
            true,
            true,
            true,
            10,
            json!({"source":"test-submitted-frame"}),
        );
        assert_eq!(lock().rendered_visual_revision, Some(1));
    }
    #[test]
    fn prepared_frame_from_another_owner_cannot_acknowledge_rendering() {
        struct Restore(Option<ShowcaseRuntime>);
        impl Drop for Restore {
            fn drop(&mut self) {
                *lock() = self.0.take().unwrap();
            }
        }
        let _restore = Restore(Some(std::mem::replace(&mut *lock(), state())));
        let prepared = prepare_visual_frame().unwrap();
        lock().epoch = "replacement-owner".into();
        confirm_visual_frame_submission(
            &prepared,
            true,
            true,
            true,
            10,
            json!({"source":"test-submitted-frame"}),
        );
        assert_eq!(lock().rendered_visual_revision, None);
    }
    #[test]
    fn release_default_refreshes_active_builtin_without_changing_user_settings() {
        let old = state();
        let mut saved = old.settings();
        saved["inputs"]["breath_source"] = json!("controller");
        saved["inputs"]["heartbeat_source"] = json!("polar");
        let mut custom = visual();
        custom["visual_scale"] = json!(0.6);
        saved["profiles"]["mine"] = custom.clone();
        let mut release = state();
        let mut new_default = visual();
        new_default["visual_scale"] = json!(0.8);
        release.visual = Some(new_default.clone());
        release
            .profiles
            .insert("default".into(), new_default.clone());
        restore_persisted_settings(
            &mut release,
            &saved,
            &[TrustedBuiltin {
                settings_schema: SETTINGS.into(),
                visual: visual(),
            }],
        )
        .unwrap();
        assert_eq!(release.visual, Some(new_default.clone()));
        assert_eq!(release.profiles["default"], new_default);
        assert_eq!(release.profiles["mine"], custom);
        assert_eq!(release.inputs, saved["inputs"]);
    }
    #[test]
    fn release_default_preserves_custom_active_visual_and_profiles() {
        let mut saved = state().settings();
        let mut custom = visual();
        custom["visual_scale"] = json!(0.6);
        saved["visual"] = custom.clone();
        saved["profiles"]["mine"] = custom.clone();
        let mut release = state();
        let mut new_default = visual();
        new_default["visual_scale"] = json!(0.8);
        release.visual = Some(new_default.clone());
        release
            .profiles
            .insert("default".into(), new_default.clone());
        restore_persisted_settings(
            &mut release,
            &saved,
            &[TrustedBuiltin {
                settings_schema: SETTINGS.into(),
                visual: visual(),
            }],
        )
        .unwrap();
        assert_eq!(release.visual, Some(custom.clone()));
        assert_eq!(release.profiles["mine"], custom);
        assert_eq!(release.profiles["default"], new_default);
        assert_eq!(release.inputs, saved["inputs"]);
    }
    #[test]
    fn release_default_never_migrates_an_unrecognized_modified_builtin() {
        let mut saved = state().settings();
        let mut edited = visual();
        edited["visual_scale"] = json!(0.6);
        saved["visual"] = edited.clone();
        saved["profiles"]["default"] = edited.clone();
        let mut release = state();
        let mut new_default = visual();
        new_default["visual_scale"] = json!(0.8);
        release.visual = Some(new_default.clone());
        release.profiles.insert("default".into(), new_default);
        restore_persisted_settings(
            &mut release,
            &saved,
            &[TrustedBuiltin {
                settings_schema: SETTINGS.into(),
                visual: visual(),
            }],
        )
        .unwrap();
        assert_eq!(release.visual, Some(edited));
    }
    #[test]
    fn trusted_builtin_metadata_is_closed_and_bounded() {
        let entry = json!({"settings_schema":SETTINGS,"source_version":"release-a","source_visual_sha256":"a".repeat(64),"visual":visual()});
        let metadata = json!({"schema":"rusty.quest.visual_showcase.builtin_migrations.v1","entries":[entry.clone()]});
        assert_eq!(
            parse_builtin_migrations(&metadata.to_string())
                .unwrap()
                .len(),
            1
        );
        let mut bad = metadata.clone();
        bad["entries"][0]["source_visual_sha256"] = json!("not-a-digest");
        assert!(parse_builtin_migrations(&bad.to_string()).is_err());
        let mut bad = metadata.clone();
        bad["entries"][0]["unexpected"] = json!(true);
        assert!(parse_builtin_migrations(&bad.to_string()).is_err());
        let mut bad = metadata.clone();
        bad["entries"] = json!(vec![entry; 9]);
        assert!(parse_builtin_migrations(&bad.to_string()).is_err());
        assert!(parse_builtin_migrations(&" ".repeat(131073)).is_err());
    }
    #[test]
    fn migration_requires_trusted_schema_and_persisted_builtin_identity() {
        let saved = state().settings();
        for trusted in [
            vec![],
            vec![TrustedBuiltin {
                settings_schema: "other-settings".into(),
                visual: visual(),
            }],
        ] {
            let mut release = state();
            let mut new_default = visual();
            new_default["visual_scale"] = json!(0.8);
            release.profiles.insert("default".into(), new_default);
            restore_persisted_settings(&mut release, &saved, &trusted).unwrap();
            assert_eq!(release.visual, Some(saved["visual"].clone()));
        }
        let mut mismatch = saved.clone();
        mismatch["profiles"]["default"]["visual_scale"] = json!(0.6);
        let mut release = state();
        restore_persisted_settings(
            &mut release,
            &mismatch,
            &[TrustedBuiltin {
                settings_schema: SETTINGS.into(),
                visual: visual(),
            }],
        )
        .unwrap();
        assert_eq!(release.visual, Some(saved["visual"].clone()));
    }
    #[test]
    fn material_metadata_uses_shared_presets_and_release_builtin() {
        use crate::native_renderer_private_particle_material_request::PrivateParticleMaterialPreset;
        let s = state();
        let status = s.status();
        let map = status["material_presets"]
            .as_object()
            .expect("bounded material catalogue");
        assert_eq!(map.len(), 6);
        for preset in [
            PrivateParticleMaterialPreset::CurrentAdditive,
            PrivateParticleMaterialPreset::PremultipliedAlphaOver,
            PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFade,
            PrivateParticleMaterialPreset::PremultipliedAlphaOverDepthFacingFade,
            PrivateParticleMaterialPreset::ReferenceMaterialEmulation,
        ] {
            let value = &map[preset.marker_name()];
            let expected = preset.parameters();
            assert_eq!(
                value["transparency"]["opacity"].as_f64(),
                Some(expected.opacity as f64)
            );
            assert_eq!(
                value["transparency"]["output_alpha_scale"].as_f64(),
                Some(expected.output_alpha_scale as f64)
            );
            assert_eq!(
                value["transparency"]["depth_suppression_strength"].as_f64(),
                Some(expected.depth_suppression_strength as f64)
            );
            assert_eq!(
                value["transparency"]["rgb_alpha_coupling"].as_f64(),
                Some(expected.rgb_alpha_coupling as f64)
            );
            assert_eq!(
                value["color"]["facing_attenuation_strength"].as_f64(),
                Some(expected.facing_attenuation_strength as f64)
            );
        }
        assert_eq!(
            map["packaged-default"]["transparency"],
            s.profiles["default"]["transparency"]
        );
        assert_eq!(
            map["packaged-default"]["color"],
            s.profiles["default"]["color"]
        );
    }
    #[test]
    fn rendered_radius_is_submission_evidence_not_requested_visual_state() {
        struct Restore(Option<ShowcaseRuntime>);
        impl Drop for Restore {
            fn drop(&mut self) {
                *lock() = self.0.take().unwrap();
            }
        }
        let _restore = Restore(Some(std::mem::replace(&mut *lock(), state())));
        lock().progress = 0.25;
        let frame = prepare_visual_frame().unwrap();
        assert!(lock().status()["rendered_radius_m"].is_null());
        assert!(lock().status()["rendered_material"].is_null());
        confirm_visual_frame_submission(
            &frame,
            false,
            true,
            true,
            10,
            json!({"source":"test-submitted-frame"}),
        );
        assert!(lock().status()["rendered_radius_m"].is_null());
        assert!(lock().status()["rendered_material"].is_null());
        let mut next = visual();
        next["radius"] = json!({"minimum_m":2.0,"maximum_m":3.0});
        let raw = command(
            &lock(),
            "pending-radius",
            "apply-visual",
            json!({"visual":next}),
        );
        assert_eq!(
            command_json(&raw).parse::<Value>().unwrap()["status"],
            "accepted"
        );
        confirm_visual_frame_submission(
            &frame,
            true,
            true,
            true,
            10,
            json!({"source":"test-submitted-frame"}),
        );
        assert_eq!(
            lock().status()["rendered_radius_m"].as_f64(),
            Some(frame.radius_m() as f64)
        );
        assert_eq!(lock().rendered_visual_revision, Some(0));
        assert_eq!(
            lock().status()["rendered_material"]["source"],
            "test-submitted-frame"
        );
    }
}

#[cfg(test)]
#[path = "showcase_runtime_external_tests.rs"]
mod external_configuration_tests;
