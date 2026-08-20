//! Lock-bound same-process composition authority and low-rate panel bridge.

use std::{
    collections::VecDeque,
    sync::{Mutex, MutexGuard, OnceLock},
};

use rusty_quest_breath_contract::{
    assessment::BreathAssessmentObservation,
    calibration::CalibrationObservation,
    composition::{
        BreathCompositionAction, BreathCompositionAuthority, BreathCompositionCapabilities,
        BreathCompositionMapping, BreathCompositionRequest, BreathCompositionSnapshot,
        BreathCompositionSource, ControllerProjectionSelection, PolarProjectionSelection,
    },
    BreathGeneration, BreathTimestampMicros,
};
use serde_json::{json, Map, Value};

#[cfg(target_os = "android")]
use crate::native_renderer_properties::{
    PROP_BREATH_COMPOSITION_ACTIVATION_BINDING_SHA256,
    PROP_BREATH_COMPOSITION_CONTROLLER_ASSESSMENT_ENABLED,
    PROP_BREATH_COMPOSITION_CONTROLLER_PROJECTION, PROP_BREATH_COMPOSITION_ENABLED,
    PROP_BREATH_COMPOSITION_INVERTED, PROP_BREATH_COMPOSITION_MAPPING,
    PROP_BREATH_COMPOSITION_PANEL_ENABLED, PROP_BREATH_COMPOSITION_POLAR_ACC_ASSESSMENT_ENABLED,
    PROP_BREATH_COMPOSITION_POLAR_PROJECTION, PROP_BREATH_COMPOSITION_SOURCE,
    PROP_BREATH_COMPOSITION_STALE_MILLIS, PROP_BREATH_COMPOSITION_STATE_MAPPING_ENABLED,
    PROP_BREATH_COMPOSITION_VOLUME_MAPPING_ENABLED,
};
use crate::{
    polar_acc_breath_adapter::{
        PolarAccBreathAdapter, PolarAccInput, PolarAccProjection, PolarAccVolumeSettings,
        TimedPolarAccFrame,
    },
    polar_composition_adapters::latest_polar_acc_after,
};

pub(crate) const COMMAND_SCHEMA_ID: &str = "rusty.quest.breath_composition.command.v1";
pub(crate) const RESPONSE_SCHEMA_ID: &str = "rusty.quest.breath_composition.response.v1";
const MAX_COMMAND_BYTES: usize = 4_096;
const MAX_PENDING_ACTIONS: usize = 16;
const DEFAULT_STALE_MILLIS: u64 = 500;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct BreathCompositionRuntimeConfig {
    pub(crate) enabled: bool,
    pub(crate) activation_binding_sha256: [u8; 32],
    pub(crate) capabilities: BreathCompositionCapabilities,
    pub(crate) initial_request: Option<BreathCompositionRequest>,
    pub(crate) stale_after_micros: u64,
}

impl Default for BreathCompositionRuntimeConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            activation_binding_sha256: [0; 32],
            capabilities: BreathCompositionCapabilities::default(),
            initial_request: None,
            stale_after_micros: DEFAULT_STALE_MILLIS * 1_000,
        }
    }
}

impl BreathCompositionRuntimeConfig {
    #[cfg(target_os = "android")]
    pub(crate) fn from_android_properties() -> Self {
        let get = |name: &str| {
            let mut property = android_properties::getprop(name);
            property.value().map(|value| value.trim().to_owned())
        };
        let enabled = bool_token(get(PROP_BREATH_COMPOSITION_ENABLED));
        let capabilities = BreathCompositionCapabilities {
            controller_assessment: bool_token(get(
                PROP_BREATH_COMPOSITION_CONTROLLER_ASSESSMENT_ENABLED,
            )),
            polar_acc_assessment: bool_token(get(
                PROP_BREATH_COMPOSITION_POLAR_ACC_ASSESSMENT_ENABLED,
            )),
            volume_mapping: bool_token(get(PROP_BREATH_COMPOSITION_VOLUME_MAPPING_ENABLED)),
            state_mapping: bool_token(get(PROP_BREATH_COMPOSITION_STATE_MAPPING_ENABLED)),
            same_apk_panel: bool_token(get(PROP_BREATH_COMPOSITION_PANEL_ENABLED)),
        };
        let initial_request = parse_request_tokens(
            get(PROP_BREATH_COMPOSITION_SOURCE).as_deref(),
            get(PROP_BREATH_COMPOSITION_MAPPING).as_deref(),
            get(PROP_BREATH_COMPOSITION_CONTROLLER_PROJECTION).as_deref(),
            get(PROP_BREATH_COMPOSITION_POLAR_PROJECTION).as_deref(),
            bool_token(get(PROP_BREATH_COMPOSITION_INVERTED)),
        )
        .ok();
        let stale_millis = get(PROP_BREATH_COMPOSITION_STALE_MILLIS)
            .and_then(|value| value.trim().parse::<u64>().ok())
            .filter(|value| (1..=60_000).contains(value))
            .unwrap_or(DEFAULT_STALE_MILLIS);
        Self {
            enabled,
            activation_binding_sha256: get(PROP_BREATH_COMPOSITION_ACTIVATION_BINDING_SHA256)
                .as_deref()
                .and_then(parse_sha256)
                .unwrap_or([0; 32]),
            capabilities,
            initial_request,
            stale_after_micros: stale_millis * 1_000,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AdapterAction {
    Configure,
    Start(BreathGeneration),
    Cancel(BreathGeneration),
    Reset,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct PendingAdapterAction {
    pub(crate) source: BreathCompositionSource,
    pub(crate) action: AdapterAction,
}

#[derive(Debug)]
pub(crate) struct BreathCompositionRuntime {
    authority: BreathCompositionAuthority,
    controller_adapter_available: bool,
    pending_actions: VecDeque<PendingAdapterAction>,
    polar_adapter: Option<PolarAccBreathAdapter>,
    last_polar_sequence_id: Option<u64>,
    last_polar_observed_at: Option<BreathTimestampMicros>,
    polar_missing_reported: bool,
    stale_after_micros: u64,
    latest_calibration: Option<CalibrationPanelReadback>,
}

#[derive(Clone, Debug, PartialEq)]
struct CalibrationPanelReadback {
    source: BreathCompositionSource,
    lifecycle: &'static str,
    progress01: f64,
    accepted_frames: usize,
    target_frames: Option<usize>,
    watchdog_age_micros: Option<u64>,
    failure_code: Option<String>,
}

impl BreathCompositionRuntime {
    pub(crate) fn new(config: BreathCompositionRuntimeConfig) -> Self {
        let controller_adapter_available = config.enabled
            && config.activation_binding_sha256 != [0; 32]
            && config.capabilities.controller_assessment
            && config.stale_after_micros > 0
            && config.stale_after_micros <= rusty_quest_breath_contract::MAX_STALE_AFTER_MICROS;
        let mut authority = BreathCompositionAuthority::new(
            config.enabled,
            config.activation_binding_sha256,
            config.capabilities,
            config.stale_after_micros,
        );
        if config.initial_request.is_some() {
            authority.select(config.initial_request);
        }
        Self {
            authority,
            controller_adapter_available,
            pending_actions: VecDeque::new(),
            polar_adapter: None,
            last_polar_sequence_id: None,
            last_polar_observed_at: None,
            polar_missing_reported: false,
            stale_after_micros: config.stale_after_micros,
            latest_calibration: None,
        }
    }

    pub(crate) fn snapshot(&self) -> BreathCompositionSnapshot {
        self.authority.snapshot()
    }

    pub(crate) fn apply_command(&mut self, command_json: &str) -> String {
        let result = self.apply_command_inner(command_json);
        match result {
            Ok(()) => response_json(
                "accepted",
                "none",
                self.authority.snapshot(),
                self.latest_calibration.as_ref(),
            ),
            Err(reason) => response_json(
                "rejected",
                reason,
                self.authority.snapshot(),
                self.latest_calibration.as_ref(),
            ),
        }
    }

    fn apply_command_inner(&mut self, command_json: &str) -> Result<(), &'static str> {
        if command_json.len() > MAX_COMMAND_BYTES {
            return Err("command-too-large");
        }
        let value: Value = serde_json::from_str(command_json).map_err(|_| "malformed-json")?;
        let object = value.as_object().ok_or("command-not-object")?;
        let schema = required_string(object, "schema")?;
        if schema != COMMAND_SCHEMA_ID {
            return Err("unsupported-schema");
        }
        let operation = required_string(object, "operation")?;
        match operation {
            "select" => {
                require_fields(
                    object,
                    &[
                        "schema",
                        "operation",
                        "source",
                        "mapping",
                        "controller_projection",
                        "polar_projection",
                        "inverted",
                    ],
                )?;
                let request = parse_request_tokens(
                    Some(required_string(object, "source")?),
                    Some(required_string(object, "mapping")?),
                    Some(required_string(object, "controller_projection")?),
                    Some(required_string(object, "polar_projection")?),
                    object
                        .get("inverted")
                        .and_then(Value::as_bool)
                        .ok_or("invalid-inverted")?,
                )?;
                let before = self.authority.snapshot();
                let after = self.authority.select(Some(request));
                if after.effective != Some(request) {
                    return Err(after
                        .rejection
                        .map_or("selection-rejected", |reason| reason.as_str()));
                }
                if after.telemetry.hard_reset_count > before.telemetry.hard_reset_count {
                    self.queue_reset(before.effective.map(|value| value.source));
                    self.queue_reset(Some(request.source));
                }
                Ok(())
            }
            "disable" => {
                require_fields(object, &["schema", "operation"])?;
                let before = self.authority.snapshot();
                self.authority.select(None);
                self.queue_reset(before.effective.map(|value| value.source));
                Ok(())
            }
            "configure" => {
                require_fields(object, &["schema", "operation"])?;
                let source = self.effective_source()?;
                let after = self.authority.action(BreathCompositionAction::Configure);
                if after.rejection.is_some() {
                    return Err("invalid-action");
                }
                self.queue(source, AdapterAction::Configure);
                Ok(())
            }
            "start" => {
                require_fields(object, &["schema", "operation"])?;
                let source = self.effective_source()?;
                let after = self.authority.action(BreathCompositionAction::Start);
                let generation = after.generation.ok_or("invalid-action")?;
                if after.rejection.is_some() {
                    return Err("invalid-action");
                }
                self.queue(source, AdapterAction::Start(generation));
                Ok(())
            }
            "cancel" => {
                require_fields(object, &["schema", "operation", "generation"])?;
                let source = self.effective_source()?;
                let generation = object
                    .get("generation")
                    .and_then(Value::as_u64)
                    .and_then(|value| BreathGeneration::new(value).ok())
                    .ok_or("invalid-generation")?;
                let after = self
                    .authority
                    .action(BreathCompositionAction::Cancel(generation));
                if after.rejection.is_some() {
                    return Err("invalid-action");
                }
                self.queue(source, AdapterAction::Cancel(generation));
                Ok(())
            }
            "reset" => {
                require_fields(object, &["schema", "operation"])?;
                let source = self.effective_source()?;
                self.authority.action(BreathCompositionAction::Reset);
                self.queue(source, AdapterAction::Reset);
                Ok(())
            }
            "status" => require_fields(object, &["schema", "operation"]),
            _ => Err("unsupported-operation"),
        }
    }

    fn effective_source(&self) -> Result<BreathCompositionSource, &'static str> {
        self.authority
            .snapshot()
            .effective
            .map(|request| request.source)
            .ok_or("no-effective-selection")
    }

    fn queue_reset(&mut self, source: Option<BreathCompositionSource>) {
        if let Some(source) = source {
            self.queue(source, AdapterAction::Reset);
        }
    }

    fn queue(&mut self, source: BreathCompositionSource, action: AdapterAction) {
        if self.pending_actions.len() == MAX_PENDING_ACTIONS {
            self.pending_actions.pop_front();
        }
        self.pending_actions
            .push_back(PendingAdapterAction { source, action });
    }

    pub(crate) fn take_action(&mut self, source: BreathCompositionSource) -> Option<AdapterAction> {
        let index = self
            .pending_actions
            .iter()
            .position(|pending| pending.source == source)?;
        self.pending_actions.remove(index).map(|value| value.action)
    }

    pub(crate) fn submit_assessment(
        &mut self,
        at: BreathTimestampMicros,
        source: BreathCompositionSource,
        assessment: BreathAssessmentObservation,
    ) -> BreathCompositionSnapshot {
        self.authority.observe(at, source, assessment)
    }

    pub(crate) fn submit_calibration(
        &mut self,
        source: BreathCompositionSource,
        observation: &CalibrationObservation,
    ) {
        self.latest_calibration = Some(CalibrationPanelReadback {
            source,
            lifecycle: observation.lifecycle.as_str(),
            progress01: observation.progress01,
            accepted_frames: observation.accepted_frames,
            target_frames: observation.target_frames,
            watchdog_age_micros: observation.watchdog_age_micros,
            failure_code: observation.failure.map(|failure| format!("{failure:?}")),
        });
    }

    pub(crate) fn poll_polar(&mut self, at: BreathTimestampMicros) {
        while let Some(action) = self.take_action(BreathCompositionSource::PolarAcc) {
            match action {
                AdapterAction::Configure => {
                    let Some(request) = self.authority.snapshot().effective else {
                        continue;
                    };
                    let projection = match request.polar_projection {
                        PolarProjectionSelection::Xz => PolarAccProjection::Xz,
                        PolarProjectionSelection::Full3d => PolarAccProjection::Full3d,
                    };
                    let mut parameters =
                        rusty_quest_breath_contract::calibration::CalibrationParameters::default();
                    parameters.inverted = request.inverted;
                    let Ok(settings) = PolarAccVolumeSettings::new(true, projection, parameters)
                    else {
                        continue;
                    };
                    let mut adapter = PolarAccBreathAdapter::new(settings);
                    adapter.configure(at);
                    self.polar_adapter = Some(adapter);
                    self.last_polar_sequence_id = None;
                    self.last_polar_observed_at = None;
                    self.polar_missing_reported = false;
                }
                AdapterAction::Start(generation) => {
                    if let Some(adapter) = self.polar_adapter.as_mut() {
                        adapter.start(at, generation);
                        self.last_polar_observed_at = Some(at);
                        self.polar_missing_reported = false;
                    }
                }
                AdapterAction::Cancel(generation) => {
                    if let Some(adapter) = self.polar_adapter.as_mut() {
                        adapter.cancel(at, generation);
                    }
                }
                AdapterAction::Reset => {
                    if let Some(adapter) = self.polar_adapter.as_mut() {
                        adapter.reset(at);
                    }
                    self.last_polar_sequence_id = None;
                    self.last_polar_observed_at = None;
                    self.polar_missing_reported = false;
                }
            }
        }
        let snapshot = self.authority.snapshot();
        let Some(generation) = snapshot.generation else {
            return;
        };
        if snapshot
            .effective
            .is_none_or(|request| request.source != BreathCompositionSource::PolarAcc)
        {
            return;
        }
        let Some(measurement) = latest_polar_acc_after(self.last_polar_sequence_id) else {
            self.observe_polar_missing_if_due(at, generation);
            return;
        };
        self.last_polar_sequence_id = Some(measurement.sequence_id);
        self.last_polar_observed_at = Some(at);
        self.polar_missing_reported = false;
        let Some(adapter) = self.polar_adapter.as_mut() else {
            return;
        };
        let result = adapter.observe(
            at,
            generation,
            PolarAccInput::Frame(TimedPolarAccFrame::from_pmd_measurement(measurement)),
        );
        self.submit_calibration(BreathCompositionSource::PolarAcc, &result.calibration);
        if let Some(assessment) = result.assessment {
            self.authority
                .observe(at, BreathCompositionSource::PolarAcc, assessment);
        }
    }

    fn observe_polar_missing_if_due(
        &mut self,
        at: BreathTimestampMicros,
        generation: BreathGeneration,
    ) {
        let Some(last_observed_at) = self.last_polar_observed_at else {
            return;
        };
        if self.polar_missing_reported
            || at.get().saturating_sub(last_observed_at.get()) <= self.stale_after_micros
        {
            return;
        }
        let sequence_id = self.last_polar_sequence_id.unwrap_or(0).saturating_add(1);
        let Some(adapter) = self.polar_adapter.as_mut() else {
            return;
        };
        let result = adapter.observe(at, generation, PolarAccInput::Missing { sequence_id });
        self.polar_missing_reported = true;
        self.submit_calibration(BreathCompositionSource::PolarAcc, &result.calibration);
        if let Some(assessment) = result.assessment {
            self.authority
                .observe(at, BreathCompositionSource::PolarAcc, assessment);
        }
    }

    pub(crate) const fn controller_adapter_available(&self) -> bool {
        self.controller_adapter_available
    }

    pub(crate) fn controller_selected(&self) -> bool {
        self.authority
            .snapshot()
            .effective
            .is_some_and(|selection| selection.source == BreathCompositionSource::Controller)
    }
}

fn runtime() -> &'static Mutex<BreathCompositionRuntime> {
    static RUNTIME: OnceLock<Mutex<BreathCompositionRuntime>> = OnceLock::new();
    RUNTIME.get_or_init(|| Mutex::new(BreathCompositionRuntime::new(Default::default())))
}

fn lock_runtime() -> MutexGuard<'static, BreathCompositionRuntime> {
    runtime()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(target_os = "android")]
pub(crate) fn install_from_android_properties() {
    let config = BreathCompositionRuntimeConfig::from_android_properties();
    let mut state = lock_runtime();
    *state = BreathCompositionRuntime::new(config);
    crate::marker("breath-composition", marker_fields(state.snapshot()));
}

pub(crate) fn controller_adapter_available() -> bool {
    lock_runtime().controller_adapter_available()
}

pub(crate) fn controller_selected() -> bool {
    lock_runtime().controller_selected()
}

pub(crate) fn effective_request() -> Option<BreathCompositionRequest> {
    lock_runtime().snapshot().effective
}

pub(crate) fn take_adapter_action(source: BreathCompositionSource) -> Option<AdapterAction> {
    lock_runtime().take_action(source)
}

pub(crate) fn submit_assessment(
    at: BreathTimestampMicros,
    source: BreathCompositionSource,
    assessment: BreathAssessmentObservation,
) -> BreathCompositionSnapshot {
    lock_runtime().submit_assessment(at, source, assessment)
}

pub(crate) fn submit_calibration(
    source: BreathCompositionSource,
    observation: &CalibrationObservation,
) {
    lock_runtime().submit_calibration(source, observation);
}

pub(crate) fn poll_polar(at: BreathTimestampMicros) {
    lock_runtime().poll_polar(at);
}

pub(crate) fn status_json() -> String {
    let state = lock_runtime();
    response_json(
        "accepted",
        "none",
        state.snapshot(),
        state.latest_calibration.as_ref(),
    )
}

pub(crate) fn apply_command_json(command_json: &str) -> String {
    lock_runtime().apply_command(command_json)
}

fn response_json(
    status: &str,
    reason_code: &str,
    snapshot: BreathCompositionSnapshot,
    calibration: Option<&CalibrationPanelReadback>,
) -> String {
    json!({
        "schema": RESPONSE_SCHEMA_ID,
        "command_status": status,
        "reason_code": reason_code,
        "snapshot": snapshot_value(snapshot, calibration),
    })
    .to_string()
}

fn snapshot_value(
    snapshot: BreathCompositionSnapshot,
    calibration: Option<&CalibrationPanelReadback>,
) -> Value {
    let request_value = |request: BreathCompositionRequest| {
        json!({
            "source": request.source.as_str(),
            "mapping": request.mapping.as_str(),
            "controller_projection": request.controller_projection.as_str(),
            "polar_projection": request.polar_projection.as_str(),
            "inverted": request.inverted,
        })
    };
    json!({
        "schema": snapshot.schema_id,
        "feature_lock_active": snapshot.feature_lock_active,
        "activation_binding_sha256": hex_sha256(snapshot.feature_lock_sha256),
        "requested": snapshot.requested.map(request_value),
        "effective": snapshot.effective.map(request_value),
        "status": snapshot.status.as_str(),
        "generation": snapshot.generation.map(BreathGeneration::get),
        "output": snapshot.output.map(|output| json!({
            "source": output.source.as_str(),
            "mapping": output.mapping.as_str(),
            "sequence_id": output.sequence_id,
            "sampled_at_micros": output.sampled_at.get(),
            "volume01": output.volume01,
            "phase": output.phase.map(|phase| phase.as_str()),
            "quality01": output.quality01,
        })),
        "latest_assessment": snapshot.latest_assessment.map(|assessment| json!({
            "sequence_id": assessment.sequence_id,
            "sampled_at_micros": assessment.sampled_at.get(),
            "observed_at_micros": assessment.observed_at.get(),
            "input_age_micros": assessment.observed_at.get().saturating_sub(assessment.sampled_at.get()),
            "volume01": assessment.volume01,
            "phase": assessment.phase.as_str(),
            "calibration": assessment.calibration.as_str(),
            "tracking": assessment.tracking.as_str(),
            "quality01": assessment.quality01,
        })),
        "calibration_readback": calibration.map(|value| json!({
            "source": value.source.as_str(),
            "lifecycle": value.lifecycle,
            "progress01": value.progress01,
            "accepted_frames": value.accepted_frames,
            "target_frames": value.target_frames,
            "watchdog_age_micros": value.watchdog_age_micros,
            "failure_code": value.failure_code,
        })),
        "rejection": snapshot.rejection.map(|reason| reason.as_str()),
        "telemetry": {
            "selection_requests": snapshot.telemetry.selection_request_count,
            "selection_changes": snapshot.telemetry.selection_change_count,
            "mapping_changes": snapshot.telemetry.mapping_change_count,
            "hard_resets": snapshot.telemetry.hard_reset_count,
            "received_assessments": snapshot.telemetry.received_assessment_count,
            "accepted_assessments": snapshot.telemetry.accepted_assessment_count,
            "rejected_assessments": snapshot.telemetry.rejected_assessment_count,
        }
    })
}

fn marker_fields(snapshot: BreathCompositionSnapshot) -> String {
    format!(
        "featureLockActive={} requestedSource={} requestedMapping={} effectiveSource={} effectiveMapping={} status={} generation={} outputVolume01={} outputPhase={} rejection={} transport=same-process-direct",
        snapshot.feature_lock_active,
        snapshot.requested.map_or("none", |value| value.source.as_str()),
        snapshot.requested.map_or("none", |value| value.mapping.as_str()),
        snapshot.effective.map_or("none", |value| value.source.as_str()),
        snapshot.effective.map_or("none", |value| value.mapping.as_str()),
        snapshot.status.as_str(),
        snapshot.generation.map_or_else(|| "none".to_owned(), |value| value.get().to_string()),
        snapshot.output.and_then(|value| value.volume01).map_or_else(|| "none".to_owned(), |value| format!("{value:.6}")),
        snapshot.output.and_then(|value| value.phase).map_or("none", |value| value.as_str()),
        snapshot.rejection.map_or("none", |value| value.as_str()),
    )
}

fn required_string<'a>(
    object: &'a Map<String, Value>,
    name: &str,
) -> Result<&'a str, &'static str> {
    object
        .get(name)
        .and_then(Value::as_str)
        .ok_or("missing-or-invalid-field")
}

fn require_fields(object: &Map<String, Value>, allowed: &[&str]) -> Result<(), &'static str> {
    if object.len() != allowed.len() || object.keys().any(|name| !allowed.contains(&name.as_str()))
    {
        return Err("unexpected-field");
    }
    Ok(())
}

fn parse_request_tokens(
    source: Option<&str>,
    mapping: Option<&str>,
    controller_projection: Option<&str>,
    polar_projection: Option<&str>,
    inverted: bool,
) -> Result<BreathCompositionRequest, &'static str> {
    let source = match source
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "controller" => BreathCompositionSource::Controller,
        "polar" | "polar-acc" => BreathCompositionSource::PolarAcc,
        _ => return Err("unsupported-source"),
    };
    let mapping = match mapping
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "volume" => BreathCompositionMapping::Volume,
        "state" => BreathCompositionMapping::State,
        _ => return Err("unsupported-mapping"),
    };
    let controller_projection = match controller_projection
        .unwrap_or("dynamic-axis")
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "fixed-orientation" => ControllerProjectionSelection::FixedOrientation,
        "dynamic-axis" => ControllerProjectionSelection::DynamicAxis,
        _ => return Err("unsupported-controller-projection"),
    };
    let polar_projection = match polar_projection
        .unwrap_or("xz")
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "xz" => PolarProjectionSelection::Xz,
        "3d" | "full-3d" => PolarProjectionSelection::Full3d,
        _ => return Err("unsupported-polar-projection"),
    };
    Ok(BreathCompositionRequest {
        source,
        mapping,
        controller_projection,
        polar_projection,
        inverted,
    })
}

fn bool_token(value: Option<String>) -> bool {
    value.is_some_and(|value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on"
        )
    })
}

fn parse_sha256(value: &str) -> Option<[u8; 32]> {
    let value = value.trim().strip_prefix("sha256:").unwrap_or(value.trim());
    if value.len() != 64 {
        return None;
    }
    let mut bytes = [0_u8; 32];
    for (index, output) in bytes.iter_mut().enumerate() {
        *output = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16).ok()?;
    }
    Some(bytes)
}

fn hex_sha256(bytes: [u8; 32]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(target_os = "android")]
fn jni_response(mut env: jni::EnvUnowned, response: String) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeApplyBreathCompositionCommand(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    command: jni::objects::JString,
) -> jni::sys::jstring {
    let response = match env
        .with_env(|env| command.try_to_string(env))
        .into_outcome()
    {
        jni::Outcome::Ok(command) => apply_command_json(&command),
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => response_json(
            "rejected",
            "invalid-jni-string",
            lock_runtime().snapshot(),
            None,
        ),
    };
    jni_response(env, response)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeReadBreathCompositionStatus(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    jni_response(env, status_json())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn runtime() -> BreathCompositionRuntime {
        BreathCompositionRuntime::new(BreathCompositionRuntimeConfig {
            enabled: true,
            activation_binding_sha256: [0x6b; 32],
            capabilities: BreathCompositionCapabilities {
                controller_assessment: true,
                polar_acc_assessment: true,
                volume_mapping: true,
                state_mapping: true,
                same_apk_panel: true,
            },
            initial_request: None,
            stale_after_micros: 500_000,
        })
    }

    fn select(source: &str, mapping: &str) -> String {
        json!({
            "schema": COMMAND_SCHEMA_ID,
            "operation": "select",
            "source": source,
            "mapping": mapping,
            "controller_projection": "dynamic-axis",
            "polar_projection": "xz",
            "inverted": false,
        })
        .to_string()
    }

    #[test]
    fn panel_requests_require_native_effective_readback() {
        let mut runtime = runtime();
        let response: Value =
            serde_json::from_str(&runtime.apply_command(&select("controller", "volume")))
                .expect("response");
        assert_eq!(response["command_status"], "accepted");
        assert_eq!(response["snapshot"]["requested"]["source"], "controller");
        assert_eq!(response["snapshot"]["effective"]["mapping"], "volume");
    }

    #[test]
    fn four_way_commands_are_independent_and_bounded() {
        for source in ["controller", "polar-acc"] {
            for mapping in ["volume", "state"] {
                let mut runtime = runtime();
                let response: Value =
                    serde_json::from_str(&runtime.apply_command(&select(source, mapping)))
                        .expect("response");
                assert_eq!(response["command_status"], "accepted");
                assert_eq!(response["snapshot"]["effective"]["source"], source);
                assert_eq!(response["snapshot"]["effective"]["mapping"], mapping);
            }
        }
    }

    #[test]
    fn action_order_and_adapter_dispatch_are_deterministic() {
        let mut runtime = runtime();
        runtime.apply_command(&select("polar-acc", "state"));
        let start = runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "start"}).to_string());
        assert_eq!(
            serde_json::from_str::<Value>(&start).expect("response")["reason_code"],
            "invalid-action"
        );
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "configure"}).to_string(),
        );
        runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "start"}).to_string());
        assert_eq!(
            runtime.take_action(BreathCompositionSource::PolarAcc),
            Some(AdapterAction::Configure)
        );
        assert!(matches!(
            runtime.take_action(BreathCompositionSource::PolarAcc),
            Some(AdapterAction::Start(_))
        ));
    }

    #[test]
    fn malformed_unknown_and_oversized_commands_do_not_mutate_state() {
        let mut runtime = runtime();
        let before = runtime.snapshot();
        for command in [
            "not-json".to_owned(),
            json!({"schema": COMMAND_SCHEMA_ID, "operation": "unknown"}).to_string(),
            "x".repeat(MAX_COMMAND_BYTES + 1),
        ] {
            let response: Value =
                serde_json::from_str(&runtime.apply_command(&command)).expect("response");
            assert_eq!(response["command_status"], "rejected");
            assert_eq!(runtime.snapshot(), before);
        }
    }

    #[test]
    fn source_change_queues_hard_resets_but_mapping_change_does_not() {
        let mut runtime = runtime();
        runtime.apply_command(&select("controller", "volume"));
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "configure"}).to_string(),
        );
        runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "start"}).to_string());
        while runtime
            .take_action(BreathCompositionSource::Controller)
            .is_some()
        {}
        runtime.apply_command(&select("controller", "state"));
        assert_eq!(
            runtime.take_action(BreathCompositionSource::Controller),
            None
        );
        runtime.apply_command(&select("polar-acc", "state"));
        assert_eq!(
            runtime.take_action(BreathCompositionSource::Controller),
            Some(AdapterAction::Reset)
        );
        assert_eq!(
            runtime.take_action(BreathCompositionSource::PolarAcc),
            Some(AdapterAction::Reset)
        );
    }

    #[test]
    fn lock_binding_parser_is_exact_and_nonzero_activation_is_required() {
        assert_eq!(parse_sha256(&"ab".repeat(32)), Some([0xab; 32]));
        assert_eq!(parse_sha256("abc"), None);
        let runtime = BreathCompositionRuntime::new(BreathCompositionRuntimeConfig {
            enabled: true,
            activation_binding_sha256: [0; 32],
            capabilities: BreathCompositionCapabilities::default(),
            initial_request: None,
            stale_after_micros: 500_000,
        });
        assert!(!runtime.snapshot().feature_lock_active);
    }

    #[test]
    fn controller_adapter_availability_is_independent_of_initial_source() {
        let mut runtime = runtime();
        runtime.apply_command(&select("polar-acc", "volume"));
        assert!(runtime.controller_adapter_available());
        assert!(!runtime.controller_selected());

        runtime.apply_command(&select("controller", "volume"));
        assert!(runtime.controller_adapter_available());
        assert!(runtime.controller_selected());
    }

    #[test]
    fn bounded_polar_silence_emits_one_missing_observation_and_clears_output() {
        use rusty_quest_breath_contract::{
            assessment::{BreathAssessmentFields, BreathTrackingState, CommonBreathPhase},
            calibration::{CalibrationLifecycle, CalibrationParameters},
        };

        let mut runtime = runtime();
        runtime.apply_command(&select("polar-acc", "volume"));
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "configure"}).to_string(),
        );
        runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "start"}).to_string());
        let generation = runtime.snapshot().generation.expect("active generation");
        let at = BreathTimestampMicros::new(1_000_000);

        let settings = PolarAccVolumeSettings::new(
            true,
            PolarAccProjection::Xz,
            CalibrationParameters::default(),
        )
        .expect("settings");
        let mut adapter = PolarAccBreathAdapter::new(settings);
        adapter.configure(at);
        adapter.start(at, generation);
        runtime.polar_adapter = Some(adapter);
        runtime.last_polar_observed_at = Some(at);

        let valid = BreathAssessmentObservation::new(BreathAssessmentFields {
            generation,
            sequence_id: 1,
            sampled_at: at,
            observed_at: at,
            volume01: Some(0.75),
            phase: CommonBreathPhase::Inhale,
            calibration: CalibrationLifecycle::Ready,
            tracking: BreathTrackingState::Valid,
            quality01: 0.8,
        })
        .expect("valid assessment");
        runtime.submit_assessment(at, BreathCompositionSource::PolarAcc, valid);
        assert_eq!(
            runtime.snapshot().output.and_then(|value| value.volume01),
            Some(0.75)
        );

        runtime.observe_polar_missing_if_due(
            BreathTimestampMicros::new(at.get() + runtime.stale_after_micros + 1),
            generation,
        );
        let snapshot = runtime.snapshot();
        assert_eq!(
            snapshot.latest_assessment.map(|value| value.tracking),
            Some(BreathTrackingState::Missing)
        );
        assert!(snapshot.output.is_none());
        assert!(runtime.polar_missing_reported);

        let received = snapshot.telemetry.received_assessment_count;
        runtime.observe_polar_missing_if_due(
            BreathTimestampMicros::new(at.get() + runtime.stale_after_micros + 2),
            generation,
        );
        assert_eq!(
            runtime.snapshot().telemetry.received_assessment_count,
            received
        );
    }
}
