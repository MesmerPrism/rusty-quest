//! Lock-bound same-process composition authority and low-rate panel bridge.

use std::{
    collections::VecDeque,
    sync::{Mutex, MutexGuard, OnceLock},
};

use rusty_quest_breath_contract::{
    assessment::BreathAssessmentObservation,
    calibration::{CalibrationLifecycle, CalibrationObservation, CalibrationStatus},
    composition::{
        BreathCompositionAction, BreathCompositionAuthority, BreathCompositionBinding,
        BreathCompositionCapabilities, BreathCompositionMapping, BreathCompositionRequest,
        BreathCompositionSnapshot, BreathCompositionSource, BreathCompositionStatus,
        ControllerProjectionSelection, PolarProjectionSelection,
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
const PACKAGED_ACTIVATION_BINDING_SHA256: Option<&str> =
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256");

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct BreathCompositionRuntimeConfig {
    pub(crate) enabled: bool,
    pub(crate) packaged_activation_binding: BreathCompositionBinding,
    pub(crate) runtime_activation_binding: BreathCompositionBinding,
    pub(crate) capabilities: BreathCompositionCapabilities,
    pub(crate) initial_request: Option<BreathCompositionRequest>,
    pub(crate) stale_after_micros: u64,
}

impl Default for BreathCompositionRuntimeConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            packaged_activation_binding: BreathCompositionBinding::Missing,
            runtime_activation_binding: BreathCompositionBinding::Missing,
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
            packaged_activation_binding: parse_binding(PACKAGED_ACTIVATION_BINDING_SHA256),
            runtime_activation_binding: parse_binding(
                get(PROP_BREATH_COMPOSITION_ACTIVATION_BINDING_SHA256).as_deref(),
            ),
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
    generation: BreathGeneration,
    lifecycle: &'static str,
    progress01: f64,
    accepted_frames: usize,
    target_frames: Option<usize>,
    watchdog_age_micros: Option<u64>,
    failure_code: Option<String>,
}

impl BreathCompositionRuntime {
    pub(crate) fn new(config: BreathCompositionRuntimeConfig) -> Self {
        let mut authority = BreathCompositionAuthority::new(
            config.enabled,
            config.packaged_activation_binding,
            config.runtime_activation_binding,
            config.capabilities,
            config.stale_after_micros,
        );
        let controller_adapter_available =
            authority.snapshot().feature_lock_active && config.capabilities.controller_assessment;
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
                let mut candidate = self.authority.clone();
                let after = candidate.select(Some(request));
                let hard_reset =
                    after.telemetry.hard_reset_count > before.telemetry.hard_reset_count;
                let actions = if hard_reset {
                    let target = (after.effective == Some(request)).then_some(request.source);
                    reset_actions(before.effective.map(|value| value.source), target)
                } else {
                    Vec::new()
                };
                self.commit_transition(candidate, &actions, hard_reset)?;
                if after.effective != Some(request) {
                    return Err(after
                        .rejection
                        .map_or("selection-rejected", |reason| reason.as_str()));
                }
                Ok(())
            }
            "disable" => {
                require_fields(object, &["schema", "operation"])?;
                let before = self.authority.snapshot();
                let mut candidate = self.authority.clone();
                let after = candidate.select(None);
                let hard_reset =
                    after.telemetry.hard_reset_count > before.telemetry.hard_reset_count;
                let actions = if hard_reset {
                    reset_actions(before.effective.map(|value| value.source), None)
                } else {
                    Vec::new()
                };
                self.commit_transition(candidate, &actions, true)?;
                Ok(())
            }
            "configure" => {
                require_fields(object, &["schema", "operation"])?;
                let source = self.effective_source()?;
                let mut candidate = self.authority.clone();
                let after = candidate.action(BreathCompositionAction::Configure);
                if after.rejection.is_some() {
                    self.authority = candidate;
                    return Err("invalid-action");
                }
                self.commit_transition(
                    candidate,
                    &[PendingAdapterAction {
                        source,
                        action: AdapterAction::Configure,
                    }],
                    true,
                )?;
                Ok(())
            }
            "start" => {
                require_fields(object, &["schema", "operation"])?;
                let source = self.effective_source()?;
                let mut candidate = self.authority.clone();
                let after = candidate.action(BreathCompositionAction::Start);
                if after.rejection.is_some() {
                    self.authority = candidate;
                    return Err("invalid-action");
                }
                let generation = after.generation.ok_or("invalid-action")?;
                self.commit_transition(
                    candidate,
                    &[PendingAdapterAction {
                        source,
                        action: AdapterAction::Start(generation),
                    }],
                    false,
                )?;
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
                let mut candidate = self.authority.clone();
                let after = candidate.action(BreathCompositionAction::Cancel(generation));
                if after.rejection.is_some() {
                    self.authority = candidate;
                    return Err("invalid-action");
                }
                self.commit_transition(
                    candidate,
                    &[PendingAdapterAction {
                        source,
                        action: AdapterAction::Cancel(generation),
                    }],
                    true,
                )?;
                Ok(())
            }
            "reset" => {
                require_fields(object, &["schema", "operation"])?;
                let source = self.effective_source()?;
                let mut candidate = self.authority.clone();
                let after = candidate.action(BreathCompositionAction::Reset);
                if after.rejection.is_some() {
                    self.authority = candidate;
                    return Err("invalid-action");
                }
                self.commit_transition(
                    candidate,
                    &[PendingAdapterAction {
                        source,
                        action: AdapterAction::Reset,
                    }],
                    true,
                )?;
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

    fn commit_transition(
        &mut self,
        candidate: BreathCompositionAuthority,
        actions: &[PendingAdapterAction],
        clear_calibration: bool,
    ) -> Result<(), &'static str> {
        if self.pending_actions.len().saturating_add(actions.len()) > MAX_PENDING_ACTIONS {
            return Err("action-queue-full");
        }
        self.authority = candidate;
        self.pending_actions.extend(actions.iter().copied());
        if clear_calibration {
            self.latest_calibration = None;
        }
        Ok(())
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
        let snapshot = self.authority.snapshot();
        let Some(selection) = snapshot.effective else {
            return;
        };
        let Some(active_generation) = snapshot.generation else {
            return;
        };
        if snapshot.status != BreathCompositionStatus::Running
            || selection.source != source
            || observation.generation != Some(active_generation)
            || !matches!(
                observation.lifecycle,
                CalibrationLifecycle::Collecting
                    | CalibrationLifecycle::Ready
                    | CalibrationLifecycle::Failed
            )
            || matches!(
                observation.status,
                CalibrationStatus::Disabled
                    | CalibrationStatus::Reset
                    | CalibrationStatus::Configured
                    | CalibrationStatus::Cancelled
                    | CalibrationStatus::ActionRejected
            )
        {
            return;
        }
        self.latest_calibration = Some(CalibrationPanelReadback {
            source,
            generation: active_generation,
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
                    let calibration = adapter.configure(at);
                    self.polar_adapter = Some(adapter);
                    self.last_polar_sequence_id = None;
                    self.last_polar_observed_at = None;
                    self.polar_missing_reported = false;
                    self.submit_calibration(BreathCompositionSource::PolarAcc, &calibration);
                }
                AdapterAction::Start(generation) => {
                    let calibration = self
                        .polar_adapter
                        .as_mut()
                        .map(|adapter| adapter.start(at, generation));
                    if let Some(calibration) = calibration {
                        self.last_polar_observed_at = Some(at);
                        self.polar_missing_reported = false;
                        self.submit_calibration(BreathCompositionSource::PolarAcc, &calibration);
                    }
                }
                AdapterAction::Cancel(generation) => {
                    let calibration = self
                        .polar_adapter
                        .as_mut()
                        .map(|adapter| adapter.cancel(at, generation));
                    if let Some(calibration) = calibration {
                        self.submit_calibration(BreathCompositionSource::PolarAcc, &calibration);
                    }
                }
                AdapterAction::Reset => {
                    let calibration = self.polar_adapter.as_mut().map(|adapter| adapter.reset(at));
                    if let Some(calibration) = calibration {
                        self.submit_calibration(BreathCompositionSource::PolarAcc, &calibration);
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

fn reset_actions(
    previous: Option<BreathCompositionSource>,
    target: Option<BreathCompositionSource>,
) -> Vec<PendingAdapterAction> {
    let mut actions = Vec::with_capacity(2);
    for source in [previous, target].into_iter().flatten() {
        if actions
            .iter()
            .any(|pending: &PendingAdapterAction| pending.source == source)
        {
            continue;
        }
        actions.push(PendingAdapterAction {
            source,
            action: AdapterAction::Reset,
        });
    }
    actions
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

pub(crate) fn snapshot() -> BreathCompositionSnapshot {
    lock_runtime().snapshot()
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
        "packaged_activation_binding_sha256": hex_sha256(snapshot.packaged_feature_lock_sha256),
        "activation_binding_matches": snapshot.feature_lock_active
            && snapshot.feature_lock_sha256 == snapshot.packaged_feature_lock_sha256,
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
            "generation": value.generation.get(),
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
        "featureLockActive={} activationBindingMatches={} requestedSource={} requestedMapping={} effectiveSource={} effectiveMapping={} status={} generation={} outputVolume01={} outputPhase={} rejection={} transport=same-process-direct",
        snapshot.feature_lock_active,
        snapshot.feature_lock_active
            && snapshot.feature_lock_sha256 == snapshot.packaged_feature_lock_sha256,
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

fn parse_binding(value: Option<&str>) -> BreathCompositionBinding {
    let Some(value) = value.map(str::trim).filter(|value| !value.is_empty()) else {
        return BreathCompositionBinding::Missing;
    };
    match parse_sha256(value) {
        Some(digest) if digest != [0; 32] => BreathCompositionBinding::Digest(digest),
        Some(_) | None => BreathCompositionBinding::Malformed,
    }
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
            packaged_activation_binding: BreathCompositionBinding::Digest([0x6b; 32]),
            runtime_activation_binding: BreathCompositionBinding::Digest([0x6b; 32]),
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
    fn lock_binding_parser_and_exact_packaged_match_are_required() {
        assert_eq!(parse_sha256(&"ab".repeat(32)), Some([0xab; 32]));
        assert_eq!(parse_sha256("abc"), None);
        assert_eq!(parse_binding(None), BreathCompositionBinding::Missing);
        assert_eq!(
            parse_binding(Some(&"00".repeat(32))),
            BreathCompositionBinding::Malformed
        );
        for (packaged, observed, reason) in [
            (
                BreathCompositionBinding::Missing,
                BreathCompositionBinding::Digest([1; 32]),
                "missing-packaged-binding",
            ),
            (
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionBinding::Missing,
                "missing-runtime-binding",
            ),
            (
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionBinding::Malformed,
                "malformed-runtime-binding",
            ),
            (
                BreathCompositionBinding::Digest([1; 32]),
                BreathCompositionBinding::Digest([2; 32]),
                "activation-binding-mismatch",
            ),
        ] {
            let runtime = BreathCompositionRuntime::new(BreathCompositionRuntimeConfig {
                enabled: true,
                packaged_activation_binding: packaged,
                runtime_activation_binding: observed,
                capabilities: BreathCompositionCapabilities::default(),
                initial_request: None,
                stale_after_micros: 500_000,
            });
            let snapshot = runtime.snapshot();
            assert!(!snapshot.feature_lock_active);
            assert_eq!(snapshot.rejection.map(|value| value.as_str()), Some(reason));
        }
        assert!(runtime().snapshot().feature_lock_active);
    }

    #[test]
    fn rejected_unavailable_selection_resets_the_prior_adapter_only() {
        let capabilities = BreathCompositionCapabilities {
            controller_assessment: true,
            polar_acc_assessment: true,
            volume_mapping: true,
            state_mapping: false,
            same_apk_panel: true,
        };
        let mut runtime = BreathCompositionRuntime::new(BreathCompositionRuntimeConfig {
            enabled: true,
            packaged_activation_binding: BreathCompositionBinding::Digest([0x6b; 32]),
            runtime_activation_binding: BreathCompositionBinding::Digest([0x6b; 32]),
            capabilities,
            initial_request: None,
            stale_after_micros: 500_000,
        });
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

        let rejected: Value =
            serde_json::from_str(&runtime.apply_command(&select("polar-acc", "state")))
                .expect("response");
        assert_eq!(rejected["reason_code"], "unavailable-selection");
        assert!(runtime.snapshot().effective.is_none());
        assert_eq!(
            runtime.take_action(BreathCompositionSource::Controller),
            Some(AdapterAction::Reset)
        );
        assert_eq!(runtime.take_action(BreathCompositionSource::PolarAcc), None);
    }

    #[test]
    fn queue_capacity_rejects_before_mutation_and_preserves_action_order() {
        let mut runtime = runtime();
        runtime.apply_command(&select("controller", "volume"));
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "configure"}).to_string(),
        );
        runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "start"}).to_string());
        runtime.pending_actions.clear();
        for _ in 0..(MAX_PENDING_ACTIONS - 2) {
            runtime.pending_actions.push_back(PendingAdapterAction {
                source: BreathCompositionSource::Controller,
                action: AdapterAction::Configure,
            });
        }

        let switched: Value =
            serde_json::from_str(&runtime.apply_command(&select("polar-acc", "volume")))
                .expect("response");
        assert_eq!(switched["command_status"], "accepted");
        assert_eq!(runtime.pending_actions.len(), MAX_PENDING_ACTIONS);
        let selected = runtime.snapshot();

        let rejected: Value = serde_json::from_str(&runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "configure"}).to_string(),
        ))
        .expect("response");
        assert_eq!(rejected["reason_code"], "action-queue-full");
        assert_eq!(runtime.snapshot(), selected);
        assert_eq!(runtime.pending_actions.len(), MAX_PENDING_ACTIONS);

        for _ in 0..(MAX_PENDING_ACTIONS - 2) {
            assert_eq!(
                runtime.pending_actions.pop_front(),
                Some(PendingAdapterAction {
                    source: BreathCompositionSource::Controller,
                    action: AdapterAction::Configure,
                })
            );
        }
        assert_eq!(
            runtime.take_action(BreathCompositionSource::Controller),
            Some(AdapterAction::Reset)
        );
        assert_eq!(
            runtime.take_action(BreathCompositionSource::PolarAcc),
            Some(AdapterAction::Reset)
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

        runtime.pending_actions.clear();
        runtime.apply_command(&select("controller", "volume"));
        while runtime
            .take_action(BreathCompositionSource::PolarAcc)
            .is_some()
        {}
        while runtime
            .take_action(BreathCompositionSource::Controller)
            .is_some()
        {}
        for _ in 0..(MAX_PENDING_ACTIONS - 1) {
            runtime.pending_actions.push_back(PendingAdapterAction {
                source: BreathCompositionSource::Controller,
                action: AdapterAction::Configure,
            });
        }
        runtime.latest_calibration = Some(CalibrationPanelReadback {
            source: BreathCompositionSource::Controller,
            generation: BreathGeneration::new(99).expect("test generation"),
            lifecycle: "ready",
            progress01: 1.0,
            accepted_frames: 12,
            target_frames: Some(12),
            watchdog_age_micros: Some(0),
            failure_code: None,
        });
        let before_atomic_rejection = runtime.snapshot();
        let calibration_before_atomic_rejection = runtime.latest_calibration.clone();
        let pending_before_atomic_rejection = runtime.pending_actions.clone();
        let rejected_switch: Value =
            serde_json::from_str(&runtime.apply_command(&select("polar-acc", "volume")))
                .expect("response");
        assert_eq!(rejected_switch["reason_code"], "action-queue-full");
        assert_eq!(runtime.snapshot(), before_atomic_rejection);
        assert_eq!(
            runtime.latest_calibration,
            calibration_before_atomic_rejection
        );
        assert_eq!(runtime.pending_actions, pending_before_atomic_rejection);
    }

    #[test]
    fn calibration_readback_requires_the_running_source_and_exact_generation() {
        let mut runtime = runtime();
        runtime.apply_command(&select("polar-acc", "volume"));
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "configure"}).to_string(),
        );
        runtime.poll_polar(BreathTimestampMicros::new(10));
        assert!(runtime.latest_calibration.is_none());

        runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "start"}).to_string());
        let first_generation = runtime.snapshot().generation.expect("first generation");
        runtime.poll_polar(BreathTimestampMicros::new(20));
        assert_eq!(
            runtime
                .latest_calibration
                .as_ref()
                .map(|value| value.generation),
            Some(first_generation)
        );
        let first_readback = runtime.latest_calibration.clone();
        let delayed_first_generation = runtime
            .polar_adapter
            .as_mut()
            .expect("running adapter")
            .observe(
                BreathTimestampMicros::new(30),
                first_generation,
                PolarAccInput::Missing { sequence_id: 1 },
            )
            .calibration;

        runtime.submit_calibration(
            BreathCompositionSource::Controller,
            &delayed_first_generation,
        );
        assert_eq!(runtime.latest_calibration, first_readback);
        let mut missing_generation = delayed_first_generation.clone();
        missing_generation.generation = None;
        runtime.submit_calibration(BreathCompositionSource::PolarAcc, &missing_generation);
        assert_eq!(runtime.latest_calibration, first_readback);
        let mut rejected_status = delayed_first_generation.clone();
        rejected_status.status = CalibrationStatus::ActionRejected;
        runtime.submit_calibration(BreathCompositionSource::PolarAcc, &rejected_status);
        assert_eq!(runtime.latest_calibration, first_readback);

        runtime.apply_command(
            &json!({
                "schema": COMMAND_SCHEMA_ID,
                "operation": "cancel",
                "generation": first_generation.get()
            })
            .to_string(),
        );
        assert!(runtime.latest_calibration.is_none());
        runtime.submit_calibration(BreathCompositionSource::PolarAcc, &delayed_first_generation);
        assert!(runtime.latest_calibration.is_none());

        runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "reset"}).to_string());
        runtime.submit_calibration(BreathCompositionSource::PolarAcc, &delayed_first_generation);
        assert!(runtime.latest_calibration.is_none());
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "configure"}).to_string(),
        );
        runtime.submit_calibration(BreathCompositionSource::PolarAcc, &delayed_first_generation);
        assert!(runtime.latest_calibration.is_none());
        runtime
            .apply_command(&json!({"schema": COMMAND_SCHEMA_ID, "operation": "start"}).to_string());
        let second_generation = runtime.snapshot().generation.expect("second generation");
        assert_ne!(second_generation, first_generation);
        runtime.submit_calibration(BreathCompositionSource::PolarAcc, &delayed_first_generation);
        assert!(runtime.latest_calibration.is_none());
        runtime.poll_polar(BreathTimestampMicros::new(40));
        assert_eq!(
            runtime
                .latest_calibration
                .as_ref()
                .map(|value| value.generation),
            Some(second_generation)
        );
        let status: Value = serde_json::from_str(&runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "status"}).to_string(),
        ))
        .expect("status response");
        assert_eq!(
            status["snapshot"]["calibration_readback"]["generation"],
            second_generation.get()
        );

        let second_generation_observation = runtime
            .polar_adapter
            .as_mut()
            .expect("second running adapter")
            .observe(
                BreathTimestampMicros::new(50),
                second_generation,
                PolarAccInput::Missing {
                    sequence_id: u64::MAX,
                },
            )
            .calibration;
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "disable"}).to_string(),
        );
        assert!(runtime.latest_calibration.is_none());
        runtime.submit_calibration(
            BreathCompositionSource::PolarAcc,
            &second_generation_observation,
        );
        assert!(runtime.latest_calibration.is_none());
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
