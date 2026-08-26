//! Lock-bound same-process composition authority and low-rate panel bridge.

use std::{
    collections::VecDeque,
    sync::{Mutex, MutexGuard, OnceLock},
    time::{SystemTime, UNIX_EPOCH},
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
    PROP_BREATH_COMPOSITION_POLAR_PROJECTION, PROP_BREATH_COMPOSITION_POLAR_STATE_CONFIG_V1,
    PROP_BREATH_COMPOSITION_SOURCE, PROP_BREATH_COMPOSITION_STALE_MILLIS,
    PROP_BREATH_COMPOSITION_STATE_MAPPING_ENABLED, PROP_BREATH_COMPOSITION_VOLUME_MAPPING_ENABLED,
};
use crate::{
    polar_acc_breath_adapter::{
        PolarAccBreathAdapter, PolarAccInput, PolarAccProjection, PolarAccRuntimeDiagnostics,
        PolarAccVolumeSettings, TimedPolarAccFrame,
    },
    polar_acc_phase_classifier::{PolarAccPhaseConfiguration, PolarAccPhaseParameters},
    polar_composition_adapters::polar_acc_for_presentation,
};

pub(crate) const COMMAND_SCHEMA_ID: &str = "rusty.quest.breath_composition.command.v1";
pub(crate) const RESPONSE_SCHEMA_ID: &str = "rusty.quest.breath_composition.response.v1";
const MAX_COMMAND_BYTES: usize = 4_096;
const MAX_PENDING_ACTIONS: usize = 16;
const DEFAULT_STALE_MILLIS: u64 = 500;
const PACKAGED_ACTIVATION_BINDING_SHA256: Option<&str> =
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_BREATH_COMPOSITION_EXPECTED_BINDING_SHA256");

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct BreathCompositionRuntimeConfig {
    pub(crate) enabled: bool,
    pub(crate) packaged_activation_binding: BreathCompositionBinding,
    pub(crate) runtime_activation_binding: BreathCompositionBinding,
    pub(crate) capabilities: BreathCompositionCapabilities,
    pub(crate) initial_request: Option<BreathCompositionRequest>,
    pub(crate) stale_after_micros: u64,
    pub(crate) polar_state_parameters: Option<PolarAccPhaseParameters>,
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
            polar_state_parameters: None,
        }
    }
}

impl BreathCompositionRuntimeConfig {
    #[cfg(target_os = "android")]
    pub(crate) fn from_android_properties_with_defaults(
        mut default_lookup: impl FnMut(&str) -> Option<String>,
    ) -> Self {
        Self::from_property_lookup(|name| android_property(name).or_else(|| default_lookup(name)))
    }

    #[cfg(target_os = "android")]
    fn from_property_lookup(mut get: impl FnMut(&str) -> Option<String>) -> Self {
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
        let polar_state_parameters = get(PROP_BREATH_COMPOSITION_POLAR_STATE_CONFIG_V1)
            .as_deref()
            .and_then(parse_polar_state_compact);
        Self {
            enabled,
            packaged_activation_binding: parse_binding(PACKAGED_ACTIVATION_BINDING_SHA256),
            runtime_activation_binding: parse_binding(
                get(PROP_BREATH_COMPOSITION_ACTIVATION_BINDING_SHA256).as_deref(),
            ),
            capabilities,
            initial_request,
            stale_after_micros: stale_millis * 1_000,
            polar_state_parameters,
        }
    }
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    crate::native_app_settings::nonempty_trimmed(property.value().as_deref())
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
    polar_state_tuning: PolarStateTuningControl,
    last_polar_diagnostics: Option<PolarAccRuntimeDiagnostics>,
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

#[derive(Clone, Debug, PartialEq)]
struct PolarStateTuningRequest {
    session_id: String,
    generation: u64,
    request_id: String,
    parameters: PolarAccPhaseParameters,
}

#[derive(Clone, Debug, PartialEq)]
struct PolarStateTuningControl {
    session_id: String,
    generation: u64,
    requested: Option<PolarStateTuningRequest>,
    effective: Option<PolarStateTuningRequest>,
    pending: Option<PolarStateTuningRequest>,
    accepted_count: u64,
    rejected_count: u64,
    effective_count: u64,
    last_reason: &'static str,
}

impl PolarStateTuningControl {
    fn new(profile_parameters: Option<PolarAccPhaseParameters>) -> Self {
        let session_id = new_session_id();
        let effective = profile_parameters.map(|parameters| PolarStateTuningRequest {
            session_id: session_id.clone(),
            generation: 0,
            request_id: "startup-profile".to_owned(),
            parameters,
        });
        Self {
            session_id,
            generation: 0,
            requested: effective.clone(),
            effective,
            pending: None,
            accepted_count: 0,
            rejected_count: 0,
            effective_count: u64::from(profile_parameters.is_some()),
            last_reason: if profile_parameters.is_some() {
                "startup-profile"
            } else {
                "common-default"
            },
        }
    }

    fn current_parameters(&self) -> Option<PolarAccPhaseParameters> {
        self.effective.as_ref().map(|request| request.parameters)
    }

    fn accept(&mut self, request: PolarStateTuningRequest) -> Result<(), &'static str> {
        if request.session_id != self.session_id {
            increment(&mut self.rejected_count);
            self.last_reason = "session-mismatch";
            return Err("polar-state-session-mismatch");
        }
        if request.generation == 0 || request.generation <= self.generation {
            increment(&mut self.rejected_count);
            self.last_reason = "stale-or-replayed-generation";
            return Err("polar-state-stale-or-replayed-generation");
        }
        if self
            .requested
            .as_ref()
            .is_some_and(|old| old.request_id == request.request_id)
        {
            increment(&mut self.rejected_count);
            self.last_reason = "duplicate-request-id";
            return Err("polar-state-duplicate-request-id");
        }
        self.generation = request.generation;
        self.requested = Some(request.clone());
        self.pending = Some(request);
        increment(&mut self.accepted_count);
        self.last_reason = "accepted-pending-consumer";
        Ok(())
    }

    fn apply_pending(&mut self) -> Option<PolarStateTuningRequest> {
        let request = self.pending.take()?;
        self.effective = Some(request.clone());
        increment(&mut self.effective_count);
        self.last_reason = "effective-polar-assessment-boundary";
        Some(request)
    }
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
            polar_state_tuning: PolarStateTuningControl::new(config.polar_state_parameters),
            last_polar_diagnostics: None,
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
                Some(&self.polar_state_tuning),
                self.last_polar_diagnostics.as_ref(),
            ),
            Err(reason) => response_json(
                "rejected",
                reason,
                self.authority.snapshot(),
                self.latest_calibration.as_ref(),
                Some(&self.polar_state_tuning),
                self.last_polar_diagnostics.as_ref(),
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
            "start_calibration" => {
                require_fields(object, &["schema", "operation"])?;
                self.start_calibration_inner()
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
            "configure_polar_state" => {
                require_fields(
                    object,
                    &[
                        "schema",
                        "operation",
                        "session_id",
                        "generation",
                        "request_id",
                        "settings",
                    ],
                )?;
                let request = parse_polar_state_request(object)?;
                #[cfg(target_os = "android")]
                let marker_request = request.clone();
                let result = self.polar_state_tuning.accept(request);
                #[cfg(target_os = "android")]
                crate::marker(
                    "polar-state-tuning",
                    format!(
                        "status={} sessionId={} generation={} requestId={} reason={} settings={}",
                        if result.is_ok() {
                            "accepted"
                        } else {
                            "rejected"
                        },
                        marker_token(&marker_request.session_id),
                        marker_request.generation,
                        marker_token(&marker_request.request_id),
                        result.as_ref().err().copied().unwrap_or("none"),
                        polar_state_settings_marker(marker_request.parameters),
                    ),
                );
                result
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

    fn start_calibration_inner(&mut self) -> Result<(), &'static str> {
        let source = self.effective_source()?;
        let mut candidate = self.authority.clone();
        let mut actions = Vec::with_capacity(3);
        if candidate.snapshot().status == BreathCompositionStatus::Running {
            let reset = candidate.action(BreathCompositionAction::Reset);
            if reset.rejection.is_some() {
                return Err("invalid-action");
            }
            actions.push(PendingAdapterAction {
                source,
                action: AdapterAction::Reset,
            });
        }
        let configured = candidate.action(BreathCompositionAction::Configure);
        if configured.rejection.is_some() {
            return Err("invalid-action");
        }
        actions.push(PendingAdapterAction {
            source,
            action: AdapterAction::Configure,
        });
        let started = candidate.action(BreathCompositionAction::Start);
        if started.rejection.is_some() {
            return Err("invalid-action");
        }
        let generation = started.generation.ok_or("invalid-action")?;
        actions.push(PendingAdapterAction {
            source,
            action: AdapterAction::Start(generation),
        });
        self.commit_transition(candidate, &actions, true)
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
        if let Some(applied) = self.polar_state_tuning.apply_pending() {
            let mut parameters = applied.parameters;
            parameters.inverted = self
                .authority
                .snapshot()
                .effective
                .is_some_and(|selection| selection.inverted);
            if let Some(adapter) = self.polar_adapter.as_mut() {
                let _ = adapter.apply_polar_phase_parameters(at, parameters);
            }
            #[cfg(target_os = "android")]
            crate::marker(
                "polar-state-tuning",
                format!(
                    "status=effective sessionId={} generation={} requestId={} consumerBoundary=poll-polar settings={}",
                    marker_token(&applied.session_id),
                    applied.generation,
                    marker_token(&applied.request_id),
                    polar_state_settings_marker(applied.parameters),
                ),
            );
        }
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
                    let Ok(mut settings) =
                        PolarAccVolumeSettings::new(true, projection, parameters)
                    else {
                        continue;
                    };
                    if let Some(mut phase_parameters) = self.polar_state_tuning.current_parameters()
                    {
                        phase_parameters.inverted = request.inverted;
                        let Ok(tuned) = settings.with_polar_phase_parameters(phase_parameters)
                        else {
                            continue;
                        };
                        settings = tuned;
                    }
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
        self.refresh_polar_state_diagnostics();
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
        let Some(measurement) = polar_acc_for_presentation(
            at.get().saturating_mul(1_000),
            self.stale_after_micros.saturating_mul(1_000),
        ) else {
            self.observe_polar_missing_if_due(at, generation);
            self.refresh_polar_state_diagnostics();
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
            let snapshot =
                self.authority
                    .observe(at, BreathCompositionSource::PolarAcc, assessment);
            crate::breath_capture::record_assessment(
                BreathCompositionSource::PolarAcc,
                assessment,
                snapshot,
            );
        }
        self.refresh_polar_state_diagnostics();
    }

    fn refresh_polar_state_diagnostics(&mut self) {
        let current = self
            .polar_adapter
            .as_ref()
            .map(PolarAccBreathAdapter::runtime_diagnostics);
        if current == self.last_polar_diagnostics {
            return;
        }
        #[cfg(target_os = "android")]
        if let Some(value) = current.as_ref() {
            crate::marker(
                "polar-state-assessment",
                format!(
                    "classifier={} phase={} transitions={} holdTransitions={} lateWindowMicros={} lateDrops={} outOfWindowDisorder={} staleGaps={} settings={}",
                    value.classifier,
                    value.phase.as_str(),
                    value.phase_transition_count,
                    value.hold_transition_count,
                    value.late_sample_window_micros,
                    value.late_sample_drop_count,
                    value.out_of_window_disorder_count,
                    value.stale_gap_count,
                    value.settings,
                ),
            );
        }
        self.last_polar_diagnostics = current;
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
            let snapshot =
                self.authority
                    .observe(at, BreathCompositionSource::PolarAcc, assessment);
            crate::breath_capture::record_assessment(
                BreathCompositionSource::PolarAcc,
                assessment,
                snapshot,
            );
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
pub(crate) fn install_from_android_properties_with_defaults(
    default_lookup: impl FnMut(&str) -> Option<String>,
) {
    let config =
        BreathCompositionRuntimeConfig::from_android_properties_with_defaults(default_lookup);
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

pub(crate) fn feature_lock_active() -> bool {
    lock_runtime().snapshot().feature_lock_active
}

pub(crate) fn take_adapter_action(source: BreathCompositionSource) -> Option<AdapterAction> {
    lock_runtime().take_action(source)
}

pub(crate) fn submit_assessment(
    at: BreathTimestampMicros,
    source: BreathCompositionSource,
    assessment: BreathAssessmentObservation,
) -> BreathCompositionSnapshot {
    let snapshot = lock_runtime().submit_assessment(at, source, assessment);
    crate::breath_capture::record_assessment(source, assessment, snapshot);
    snapshot
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
        Some(&state.polar_state_tuning),
        state.last_polar_diagnostics.as_ref(),
    )
}

pub(crate) fn apply_command_json(command_json: &str) -> String {
    lock_runtime().apply_command(command_json)
}

pub(crate) fn start_calibration(trigger_source: &str) -> String {
    let mut state = lock_runtime();
    let result = state.start_calibration_inner();
    let (status, reason) = match result {
        Ok(()) => ("accepted", "none"),
        Err(reason) => ("rejected", reason),
    };
    let snapshot = state.snapshot();
    #[cfg(target_os = "android")]
    {
        crate::marker(
            "breath-calibration-action",
            format!(
                "status={status} reason={} trigger={} generation={} lifecycle={} source={} mapping={}",
                marker_token(reason),
                marker_token(trigger_source),
                snapshot.generation.map_or(0, BreathGeneration::get),
                snapshot.status.as_str(),
                snapshot
                    .effective
                    .map_or("none", |request| request.source.as_str()),
                snapshot
                    .effective
                    .map_or("none", |request| request.mapping.as_str()),
            ),
        );
    }
    #[cfg(not(target_os = "android"))]
    let _ = trigger_source;
    response_json(
        status,
        reason,
        snapshot,
        state.latest_calibration.as_ref(),
        Some(&state.polar_state_tuning),
        state.last_polar_diagnostics.as_ref(),
    )
}

fn response_json(
    status: &str,
    reason_code: &str,
    snapshot: BreathCompositionSnapshot,
    calibration: Option<&CalibrationPanelReadback>,
    polar_state_tuning: Option<&PolarStateTuningControl>,
    polar_state_diagnostics: Option<&PolarAccRuntimeDiagnostics>,
) -> String {
    json!({
        "schema": RESPONSE_SCHEMA_ID,
        "command_status": status,
        "reason_code": reason_code,
        "snapshot": snapshot_value(
            snapshot,
            calibration,
            polar_state_tuning,
            polar_state_diagnostics,
        ),
    })
    .to_string()
}

fn snapshot_value(
    snapshot: BreathCompositionSnapshot,
    calibration: Option<&CalibrationPanelReadback>,
    polar_state_tuning: Option<&PolarStateTuningControl>,
    polar_state_diagnostics: Option<&PolarAccRuntimeDiagnostics>,
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
        },
        "polar_state_tuning": polar_state_tuning.map(polar_state_tuning_value),
        "polar_state_diagnostics": polar_state_diagnostics.map(|value| json!({
            "classifier": value.classifier,
            "phase": value.phase.as_str(),
            "phase_transitions": value.phase_transition_count,
            "hold_transitions": value.hold_transition_count,
            "late_sample_window_micros": value.late_sample_window_micros,
            "late_sample_drops": value.late_sample_drop_count,
            "out_of_window_disorder": value.out_of_window_disorder_count,
            "stale_gaps": value.stale_gap_count,
            "settings": value.settings,
        })),
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

fn parse_polar_state_request(
    object: &Map<String, Value>,
) -> Result<PolarStateTuningRequest, &'static str> {
    let session_id = required_string(object, "session_id")?;
    if !is_lower_hex(session_id, 32) || session_id.bytes().all(|byte| byte == b'0') {
        return Err("invalid-polar-state-session-id");
    }
    let generation = object
        .get("generation")
        .and_then(Value::as_u64)
        .filter(|value| *value > 0)
        .ok_or("invalid-polar-state-generation")?;
    let request_id = required_string(object, "request_id")?;
    if !is_lower_hex(request_id, 32) || request_id.bytes().all(|byte| byte == b'0') {
        return Err("invalid-polar-state-request-id");
    }
    let settings = object
        .get("settings")
        .and_then(Value::as_object)
        .ok_or("invalid-polar-state-settings")?;
    let parameters = parse_polar_state_settings(settings)?;
    Ok(PolarStateTuningRequest {
        session_id: session_id.to_owned(),
        generation,
        request_id: request_id.to_owned(),
        parameters,
    })
}

fn parse_polar_state_settings(
    object: &Map<String, Value>,
) -> Result<PolarAccPhaseParameters, &'static str> {
    let fields = [
        "inhale_entry_per_second",
        "exhale_entry_per_second",
        "hold_band_per_second",
        "smoothing_millis",
        "confirmation_millis",
        "minimum_dwell_millis",
        "stale_millis",
        "motion_admission_mg",
        "leave_full_contraction_per_second",
        "leave_full_expansion_per_second",
        "late_sample_window_millis",
    ];
    require_fields(object, &fields)?;
    let stale_millis = required_u64(object, "stale_millis", 1, 20_000)?;
    let parameters = PolarAccPhaseParameters {
        inhale_entry_per_second: required_f64(
            object,
            "inhale_entry_per_second",
            0.000_001,
            1_000.0,
        )?,
        exhale_entry_per_second: required_f64(
            object,
            "exhale_entry_per_second",
            0.000_001,
            1_000.0,
        )?,
        hold_band_per_second: required_f64(object, "hold_band_per_second", 0.0, 999.999)?,
        smoothing_tau_micros: required_u64(object, "smoothing_millis", 0, 10_000)?
            .saturating_mul(1_000),
        confirmation_micros: required_u64(object, "confirmation_millis", 1, 10_000)?
            .saturating_mul(1_000),
        minimum_dwell_micros: required_u64(object, "minimum_dwell_millis", 0, 10_000)?
            .saturating_mul(1_000),
        stale_after_micros: stale_millis.saturating_mul(1_000),
        discontinuity_after_micros: stale_millis
            .saturating_mul(3)
            .max(stale_millis.saturating_add(1))
            .saturating_mul(1_000),
        motion_admission_mg: required_f64(object, "motion_admission_mg", 0.0, 1_000.0)?,
        leave_full_contraction_per_second: required_f64(
            object,
            "leave_full_contraction_per_second",
            0.000_001,
            1_000.0,
        )?,
        leave_full_expansion_per_second: required_f64(
            object,
            "leave_full_expansion_per_second",
            0.000_001,
            1_000.0,
        )?,
        late_sample_window_micros: required_u64(object, "late_sample_window_millis", 0, 10_000)?
            .saturating_mul(1_000),
        inverted: false,
    };
    PolarAccPhaseConfiguration::new(parameters)
        .map(|configuration| configuration.parameters())
        .map_err(|_| "invalid-polar-state-settings")
}

fn parse_polar_state_compact(value: &str) -> Option<PolarAccPhaseParameters> {
    let fields = value.trim().split('|').collect::<Vec<_>>();
    if fields.len() != 12 || fields[0] != "v1" {
        return None;
    }
    let object = Map::from_iter([
        (
            "inhale_entry_per_second".to_owned(),
            json!(fields[1].parse::<f64>().ok()?),
        ),
        (
            "exhale_entry_per_second".to_owned(),
            json!(fields[2].parse::<f64>().ok()?),
        ),
        (
            "hold_band_per_second".to_owned(),
            json!(fields[3].parse::<f64>().ok()?),
        ),
        (
            "smoothing_millis".to_owned(),
            json!(fields[4].parse::<u64>().ok()?),
        ),
        (
            "confirmation_millis".to_owned(),
            json!(fields[5].parse::<u64>().ok()?),
        ),
        (
            "minimum_dwell_millis".to_owned(),
            json!(fields[6].parse::<u64>().ok()?),
        ),
        (
            "stale_millis".to_owned(),
            json!(fields[7].parse::<u64>().ok()?),
        ),
        (
            "motion_admission_mg".to_owned(),
            json!(fields[8].parse::<f64>().ok()?),
        ),
        (
            "leave_full_contraction_per_second".to_owned(),
            json!(fields[9].parse::<f64>().ok()?),
        ),
        (
            "leave_full_expansion_per_second".to_owned(),
            json!(fields[10].parse::<f64>().ok()?),
        ),
        (
            "late_sample_window_millis".to_owned(),
            json!(fields[11].parse::<u64>().ok()?),
        ),
    ]);
    parse_polar_state_settings(&object).ok()
}

fn required_f64(
    object: &Map<String, Value>,
    name: &str,
    minimum: f64,
    maximum: f64,
) -> Result<f64, &'static str> {
    object
        .get(name)
        .and_then(Value::as_f64)
        .filter(|value| value.is_finite() && (minimum..=maximum).contains(value))
        .ok_or("invalid-polar-state-settings")
}

fn required_u64(
    object: &Map<String, Value>,
    name: &str,
    minimum: u64,
    maximum: u64,
) -> Result<u64, &'static str> {
    object
        .get(name)
        .and_then(Value::as_u64)
        .filter(|value| (minimum..=maximum).contains(value))
        .ok_or("invalid-polar-state-settings")
}

fn polar_state_tuning_value(control: &PolarStateTuningControl) -> Value {
    let request_value = |request: &PolarStateTuningRequest| {
        json!({
            "session_id": request.session_id,
            "generation": request.generation,
            "request_id": request.request_id,
            "settings": polar_state_settings_value(request.parameters),
        })
    };
    json!({
        "schema": "rusty.quest.polar_acc_state_tuning.status.v1",
        "session_id": control.session_id,
        "generation": control.generation,
        "requested": control.requested.as_ref().map(request_value),
        "effective": control.effective.as_ref().map(request_value),
        "pending": control.pending.as_ref().map(request_value),
        "accepted_count": control.accepted_count,
        "rejected_count": control.rejected_count,
        "effective_count": control.effective_count,
        "reason": control.last_reason,
    })
}

fn polar_state_settings_value(parameters: PolarAccPhaseParameters) -> Value {
    json!({
        "inhale_entry_per_second": parameters.inhale_entry_per_second,
        "exhale_entry_per_second": parameters.exhale_entry_per_second,
        "hold_band_per_second": parameters.hold_band_per_second,
        "smoothing_millis": parameters.smoothing_tau_micros / 1_000,
        "confirmation_millis": parameters.confirmation_micros / 1_000,
        "minimum_dwell_millis": parameters.minimum_dwell_micros / 1_000,
        "stale_millis": parameters.stale_after_micros / 1_000,
        "motion_admission_mg": parameters.motion_admission_mg,
        "leave_full_contraction_per_second": parameters.leave_full_contraction_per_second,
        "leave_full_expansion_per_second": parameters.leave_full_expansion_per_second,
        "late_sample_window_millis": parameters.late_sample_window_micros / 1_000,
    })
}

fn polar_state_settings_marker(parameters: PolarAccPhaseParameters) -> String {
    format!(
        "inhale-{:.6}_exhale-{:.6}_hold-{:.6}_smoothMs-{}_confirmMs-{}_dwellMs-{}_staleMs-{}_motionMg-{:.3}_leaveContract-{:.6}_leaveExpand-{:.6}_lateMs-{}",
        parameters.inhale_entry_per_second,
        parameters.exhale_entry_per_second,
        parameters.hold_band_per_second,
        parameters.smoothing_tau_micros / 1_000,
        parameters.confirmation_micros / 1_000,
        parameters.minimum_dwell_micros / 1_000,
        parameters.stale_after_micros / 1_000,
        parameters.motion_admission_mg,
        parameters.leave_full_contraction_per_second,
        parameters.leave_full_expansion_per_second,
        parameters.late_sample_window_micros / 1_000,
    )
}

fn is_lower_hex(value: &str, expected_len: usize) -> bool {
    value.len() == expected_len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn new_session_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let mixed = nanos ^ ((std::process::id() as u128) << 64);
    format!("{mixed:032x}")
}

fn increment(counter: &mut u64) {
    *counter = counter.saturating_add(1);
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

fn marker_token(value: &str) -> String {
    let token = value
        .trim()
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.') {
                character
            } else {
                '_'
            }
        })
        .take(96)
        .collect::<String>();
    if token.is_empty() {
        "none".to_owned()
    } else {
        token
    }
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
            None,
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
            polar_state_parameters: None,
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

    fn polar_settings() -> Value {
        json!({
            "inhale_entry_per_second": 0.030,
            "exhale_entry_per_second": 0.031,
            "hold_band_per_second": 0.025,
            "smoothing_millis": 400,
            "confirmation_millis": 400,
            "minimum_dwell_millis": 400,
            "stale_millis": 500,
            "motion_admission_mg": 2.0,
            "leave_full_contraction_per_second": 0.040,
            "leave_full_expansion_per_second": 0.041,
            "late_sample_window_millis": 120,
        })
    }

    fn polar_tuning_command(
        runtime: &BreathCompositionRuntime,
        generation: u64,
        request: &str,
    ) -> String {
        json!({
            "schema": COMMAND_SCHEMA_ID,
            "operation": "configure_polar_state",
            "session_id": runtime.polar_state_tuning.session_id,
            "generation": generation,
            "request_id": request,
            "settings": polar_settings(),
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
    fn polar_state_tuning_is_atomic_fenced_and_effective_only_at_consumer_boundary() {
        let mut runtime = runtime();
        let request_id = "11111111111111111111111111111111";
        let accepted: Value = serde_json::from_str(
            &runtime.apply_command(&polar_tuning_command(&runtime, 1, request_id)),
        )
        .expect("accepted response");
        assert_eq!(accepted["command_status"], "accepted");
        assert_eq!(
            accepted["snapshot"]["polar_state_tuning"]["pending"]["request_id"],
            request_id
        );
        assert!(accepted["snapshot"]["polar_state_tuning"]["effective"].is_null());

        let settings = PolarAccVolumeSettings::new(
            true,
            PolarAccProjection::Xz,
            rusty_quest_breath_contract::calibration::CalibrationParameters::default(),
        )
        .expect("Polar adapter settings");
        runtime.polar_adapter = Some(PolarAccBreathAdapter::new(settings));
        runtime.poll_polar(BreathTimestampMicros::new(1_000_000));
        let effective: Value = serde_json::from_str(&response_json(
            "status",
            "none",
            runtime.snapshot(),
            runtime.latest_calibration.as_ref(),
            Some(&runtime.polar_state_tuning),
            runtime.last_polar_diagnostics.as_ref(),
        ))
        .expect("effective response");
        assert_eq!(
            effective["snapshot"]["polar_state_tuning"]["effective"]["request_id"],
            request_id
        );
        assert!(effective["snapshot"]["polar_state_tuning"]["pending"].is_null());
        assert_eq!(
            effective["snapshot"]["polar_state_diagnostics"]["classifier"],
            "polar-specific-v1"
        );

        let replay: Value = serde_json::from_str(
            &runtime.apply_command(&polar_tuning_command(&runtime, 1, request_id)),
        )
        .expect("replay response");
        assert_eq!(replay["command_status"], "rejected");
        assert_eq!(
            replay["snapshot"]["polar_state_tuning"]["effective"]["request_id"],
            request_id
        );

        let duplicate_id: Value = serde_json::from_str(
            &runtime.apply_command(&polar_tuning_command(&runtime, 2, request_id)),
        )
        .expect("duplicate request ID response");
        assert_eq!(duplicate_id["command_status"], "rejected");
        assert_eq!(
            duplicate_id["snapshot"]["polar_state_tuning"]["effective"]["generation"],
            1
        );
    }

    #[test]
    fn malformed_session_nonfinite_range_and_unknown_fields_are_rejected_without_change() {
        let mut runtime = runtime();
        let baseline = runtime.polar_state_tuning.clone();
        let valid: Value = serde_json::from_str(&polar_tuning_command(
            &runtime,
            1,
            "22222222222222222222222222222222",
        ))
        .expect("command");
        for damaged in [
            {
                let mut value = valid.clone();
                value["session_id"] = json!("ffffffffffffffffffffffffffffffff");
                value
            },
            {
                let mut value = valid.clone();
                value["session_id"] = json!("00000000000000000000000000000000");
                value
            },
            {
                let mut value = valid.clone();
                value["generation"] = json!(0);
                value
            },
            {
                let mut value = valid.clone();
                value["request_id"] = json!("00000000000000000000000000000000");
                value
            },
            {
                let mut value = valid.clone();
                value["settings"]["inhale_entry_per_second"] = json!("NaN");
                value
            },
            {
                let mut value = valid.clone();
                value["settings"]["hold_band_per_second"] = json!(0.5);
                value
            },
            {
                let mut value = valid.clone();
                value["settings"]["extra"] = json!(true);
                value
            },
        ] {
            let response: Value =
                serde_json::from_str(&runtime.apply_command(&damaged.to_string()))
                    .expect("rejection");
            assert_eq!(response["command_status"], "rejected");
            assert_eq!(runtime.polar_state_tuning.effective, baseline.effective);
            assert!(runtime.polar_state_tuning.pending.is_none());
        }
    }

    #[test]
    fn compact_profile_is_exact_and_fresh_runtime_resets_request_fence() {
        let parameters =
            parse_polar_state_compact("v1|0.030|0.031|0.025|400|400|400|500|2.0|0.040|0.041|120")
                .expect("compact profile");
        assert_eq!(parameters.motion_admission_mg, 2.0);
        assert_eq!(parameters.smoothing_tau_micros, 400_000);
        assert!(parse_polar_state_compact("v1|0.030").is_none());

        let first = runtime();
        let second = runtime();
        assert_ne!(
            first.polar_state_tuning.session_id,
            second.polar_state_tuning.session_id
        );
        assert_eq!(second.polar_state_tuning.generation, 0);
        assert!(second.polar_state_tuning.pending.is_none());
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
    fn start_calibration_is_atomic_and_queues_configure_before_start() {
        let mut runtime = runtime();
        runtime.apply_command(&select("controller", "volume"));
        let response: Value = serde_json::from_str(&runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "start_calibration"}).to_string(),
        ))
        .expect("response");
        assert_eq!(response["command_status"], "accepted");
        assert_eq!(response["snapshot"]["status"], "running");
        assert_eq!(
            runtime.take_action(BreathCompositionSource::Controller),
            Some(AdapterAction::Configure)
        );
        assert!(matches!(
            runtime.take_action(BreathCompositionSource::Controller),
            Some(AdapterAction::Start(_))
        ));
    }

    #[test]
    fn start_calibration_without_an_effective_selection_is_inert() {
        let mut runtime = runtime();
        let before = runtime.snapshot();
        let response: Value = serde_json::from_str(&runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "start_calibration"}).to_string(),
        ))
        .expect("response");
        assert_eq!(response["command_status"], "rejected");
        assert_eq!(response["reason_code"], "no-effective-selection");
        assert_eq!(runtime.snapshot(), before);
        assert!(runtime.pending_actions.is_empty());
    }

    #[test]
    fn start_calibration_restarts_running_ready_and_failed_generations_atomically() {
        let mut runtime = runtime();
        runtime.apply_command(&select("controller", "volume"));
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "start_calibration"}).to_string(),
        );
        let first_generation = runtime.snapshot().generation.expect("first generation");
        runtime.pending_actions.clear();

        for lifecycle in ["ready", "failed"] {
            runtime.latest_calibration = Some(CalibrationPanelReadback {
                source: BreathCompositionSource::Controller,
                generation: runtime.snapshot().generation.expect("running generation"),
                lifecycle,
                progress01: if lifecycle == "ready" { 1.0 } else { 0.4 },
                accepted_frames: 8,
                target_frames: Some(12),
                watchdog_age_micros: Some(20),
                failure_code: (lifecycle == "failed").then(|| "Timeout".to_owned()),
            });
            let response: Value = serde_json::from_str(&runtime.apply_command(
                &json!({"schema": COMMAND_SCHEMA_ID, "operation": "start_calibration"}).to_string(),
            ))
            .expect("response");
            assert_eq!(response["command_status"], "accepted");
            assert_eq!(response["snapshot"]["status"], "running");
            assert!(runtime.latest_calibration.is_none());
            assert_eq!(
                runtime.take_action(BreathCompositionSource::Controller),
                Some(AdapterAction::Reset)
            );
            assert_eq!(
                runtime.take_action(BreathCompositionSource::Controller),
                Some(AdapterAction::Configure)
            );
            assert!(matches!(
                runtime.take_action(BreathCompositionSource::Controller),
                Some(AdapterAction::Start(_))
            ));
        }
        assert_ne!(
            runtime.snapshot().generation.expect("latest generation"),
            first_generation
        );
    }

    #[test]
    fn running_calibration_restart_rejects_before_any_mutation_when_queue_is_full() {
        let mut runtime = runtime();
        runtime.apply_command(&select("controller", "volume"));
        runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "start_calibration"}).to_string(),
        );
        runtime.pending_actions.clear();
        for _ in 0..(MAX_PENDING_ACTIONS - 2) {
            runtime.pending_actions.push_back(PendingAdapterAction {
                source: BreathCompositionSource::Controller,
                action: AdapterAction::Configure,
            });
        }
        runtime.latest_calibration = Some(CalibrationPanelReadback {
            source: BreathCompositionSource::Controller,
            generation: runtime.snapshot().generation.expect("generation"),
            lifecycle: "ready",
            progress01: 1.0,
            accepted_frames: 12,
            target_frames: Some(12),
            watchdog_age_micros: Some(0),
            failure_code: None,
        });
        let before = runtime.snapshot();
        let pending_before = runtime.pending_actions.clone();
        let calibration_before = runtime.latest_calibration.clone();

        let response: Value = serde_json::from_str(&runtime.apply_command(
            &json!({"schema": COMMAND_SCHEMA_ID, "operation": "start_calibration"}).to_string(),
        ))
        .expect("response");
        assert_eq!(response["command_status"], "rejected");
        assert_eq!(response["reason_code"], "action-queue-full");
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.pending_actions, pending_before);
        assert_eq!(runtime.latest_calibration, calibration_before);
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
                polar_state_parameters: None,
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
            polar_state_parameters: None,
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
