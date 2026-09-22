//! App-lifetime experiment-session command and sample runtime.
//!
//! JNI, render, and BLE callers only enqueue commands, try-offer immutable
//! records, or clone a precomputed projection. The dedicated control worker
//! owns recovery, prepare, completion checkpoints, finalization, and writer
//! shutdown.

use std::{
    collections::VecDeque,
    fs,
    path::{Component, Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        mpsc::{self, Receiver, SyncSender, TrySendError},
        Arc, Mutex, OnceLock, RwLock, TryLockError,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use serde_json::{json, Map, Value};

use crate::{
    experiment_session::{
        CommandEnvelope, CommandOutcome, CompletionProgress, ExperimentSessionController,
        OperationToken, PresentationState, RecordingFailure, RecordingResult, SessionCommand,
        SessionEffect, SessionPhase,
    },
    session_recording_clock::{
        ClockAnchor, MonotonicNanos, SessionTimestampKey, SourceClock, SourceTimestamp,
        UtcEpochNanos,
    },
    session_recording_contract::{
        AudioAssetIdentity, BreathObservation, BreathPhase, ConditionKey, EffectiveRadiusProfile,
        EffectiveRadiusSnapshotObservation, ExperimentIdentity, FinalizationReason, PolarAccBatch,
        PolarAccSample, PolarEcgBatch, PolarEcgSample, PolarHeartRateObservation, SessionCounts,
        SessionEvent, SessionEventKind, SessionRecord, SessionStartSpec,
        DEFAULT_COMPLETION_THRESHOLD_NS,
    },
    session_recording_recovery::{RecoveredConditionCounts, RecoveryReport},
    session_recording_writer::{
        ExternalLossRecorder, FinalizedReceipt, OfferOutcome, OfferReceipt, SessionRecordingWriter,
        WriterConfig,
    },
};

pub(crate) const EXPERIMENT_SESSION_COMMAND_SCHEMA: &str =
    "rusty.quest.experiment_session.command.v1";
pub(crate) const EXPERIMENT_SESSION_RESPONSE_SCHEMA: &str =
    "rusty.quest.experiment_session.response.v1";
const COMMAND_QUEUE_CAPACITY: usize = 64;
const TERMINAL_QUEUE_CAPACITY: usize = 4;
const RETRY_QUEUE_CAPACITY: usize = 256;
const MAX_COMMAND_BYTES: usize = 32 * 1024;
const MAX_OPERATION_HISTORY: usize = 64;
const CONTROL_TIMEOUT: Duration = Duration::from_secs(10);
const SUBMITTED_FRAME_STALL_NS: u64 = 500_000_000;
const PRODUCER_LOSS_COUNT_BITS: u32 = 32;
const PRODUCER_LOSS_COUNT_MASK: u64 = u32::MAX as u64;
const EXPERIMENT_SESSION_RUNTIME_PROJECTION_SCHEMA: &str =
    "rusty.quest.experiment_session.runtime_projection.v1";
const EXPERIMENT_SESSION_PROFILE_FILE: &str = "experiment-session-profile.json";

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct CompiledExperimentAnchors<'a> {
    profile_sha256: Option<&'a str>,
    provider_manifest_sha256: Option<&'a str>,
    provider_inventory_sha256: Option<&'a str>,
}

impl CompiledExperimentAnchors<'static> {
    const fn production() -> Self {
        Self {
            profile_sha256: option_env!(
                "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROFILE_SHA256"
            ),
            provider_manifest_sha256: option_env!(
                "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_PROVIDER_MANIFEST_SHA256"
            ),
            provider_inventory_sha256: option_env!(
                "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_EXPERIMENT_SESSION_INVENTORY_SHA256"
            ),
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum RouteAction {
    #[default]
    None,
    OpenDeveloper,
    ShowExperimenter,
    ShutdownApp,
}

/// Pure OpenXR submitted-frame gate. Focus is necessary but never sufficient:
/// one current-session frame establishes a baseline and a second advancing
/// submission admits active experiment time. A host-time stall resets the
/// proof and requires two fresh frames after rendering resumes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct SubmittedFrameReadinessGate {
    focused: bool,
    last_frame: Option<u64>,
    last_submitted_host_ns: Option<u64>,
    activation_latched: bool,
}

impl SubmittedFrameReadinessGate {
    pub(crate) fn begin_session(&mut self) {
        *self = Self::default();
    }

    pub(crate) fn set_focused(&mut self, focused: bool) -> bool {
        let lost_active = self.focused && !focused && self.activation_latched;
        self.focused = focused;
        if !focused {
            self.last_frame = None;
            self.last_submitted_host_ns = None;
            self.activation_latched = false;
        }
        lost_active
    }

    pub(crate) fn note_submitted(&mut self, frame: u64, observed_host_ns: u64) -> bool {
        if !self.focused {
            return false;
        }
        let host_clock_reset_or_stalled = self.last_submitted_host_ns.is_some_and(|previous| {
            observed_host_ns < previous
                || observed_host_ns.saturating_sub(previous) > SUBMITTED_FRAME_STALL_NS
        });
        if host_clock_reset_or_stalled {
            self.last_frame = Some(frame);
            self.last_submitted_host_ns = Some(observed_host_ns);
            self.activation_latched = false;
            return false;
        }
        match self.last_frame {
            Some(previous) if frame == previous => return false,
            Some(previous) if frame < previous => {
                self.last_frame = Some(frame);
                self.last_submitted_host_ns = Some(observed_host_ns);
                self.activation_latched = false;
                return false;
            }
            None => {
                self.last_frame = Some(frame);
                self.last_submitted_host_ns = Some(observed_host_ns);
                return false;
            }
            Some(_) => {}
        }
        self.last_submitted_host_ns = Some(observed_host_ns);
        self.last_frame = Some(frame);
        if !self.activation_latched {
            self.activation_latched = true;
            true
        } else {
            false
        }
    }

    pub(crate) fn reject_activation(&mut self) {
        self.activation_latched = false;
    }
}

impl RouteAction {
    const fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::OpenDeveloper => "open-developer",
            Self::ShowExperimenter => "show-experimenter",
            Self::ShutdownApp => "shutdown-app",
        }
    }

    const fn code(self) -> u64 {
        match self {
            Self::None => 0,
            Self::OpenDeveloper => 1,
            Self::ShowExperimenter => 2,
            Self::ShutdownApp => 3,
        }
    }

    const fn from_code(value: u64) -> Self {
        match value {
            1 => Self::OpenDeveloper,
            2 => Self::ShowExperimenter,
            3 => Self::ShutdownApp,
            _ => Self::None,
        }
    }
}

#[derive(Clone, Debug)]
struct Projection {
    audio_technical_hold: bool,
    control_state: crate::experiment_session::ExperimentControlState,
    control_receipt_generation: u64,
    control_receipt_revision: u64,
    control_event: &'static str,
    control_event_elapsed_realtime_ns: u64,
    runtime_epoch: u64,
    revision: u64,
    generation: u64,
    phase: SessionPhase,
    presentation: PresentationState,
    active_time_ns: u64,
    completion: CompletionProgress,
    recording_result: RecordingResult,
    counts: SessionCounts,
    condition_counts: RecoveredConditionCounts,
    storage_status: &'static str,
    recovery_status: &'static str,
    recovery_count: u64,
    last_operation_id: Option<String>,
    last_operation_status: &'static str,
    last_reason: String,
    route_action: RouteAction,
    route_action_revision: u64,
    profile_identity_bound: bool,
    audio_identity_bound: bool,
    producer_retry_drops: u64,
    metric_status: &'static str,
    initialization_status: &'static str,
    inventory_status: &'static str,
    recording_root_status: &'static str,
    shutdown_status: &'static str,
    shutdown_ack_revision: u64,
    defaults_reset_revision: u64,
    finalized_session_generation: u64,
    finalized_operation_id: Option<String>,
}

impl Default for Projection {
    fn default() -> Self {
        Self {
            audio_technical_hold: false,
            control_state: crate::experiment_session::ExperimentControlState::Idle,
            control_receipt_generation: 0,
            control_receipt_revision: 0,
            control_event: "none",
            control_event_elapsed_realtime_ns: 0,
            runtime_epoch: 0,
            revision: 0,
            generation: 0,
            phase: SessionPhase::Idle,
            presentation: PresentationState::Experimenter,
            active_time_ns: 0,
            completion: CompletionProgress::NotReached,
            recording_result: RecordingResult::None,
            counts: SessionCounts::default(),
            condition_counts: RecoveredConditionCounts::default(),
            storage_status: "preparing",
            recovery_status: "not-run",
            recovery_count: 0,
            last_operation_id: None,
            last_operation_status: "none",
            last_reason: "none".to_owned(),
            route_action: RouteAction::None,
            route_action_revision: 0,
            profile_identity_bound: false,
            audio_identity_bound: false,
            producer_retry_drops: 0,
            metric_status: "actual-runtime-radius-with-configured-deformation-envelopes",
            initialization_status: "initializing",
            inventory_status: "inventory-unavailable",
            recording_root_status: "unavailable",
            shutdown_status: "not-requested",
            shutdown_ack_revision: 0,
            defaults_reset_revision: 0,
            finalized_session_generation: 0,
            finalized_operation_id: None,
        }
    }
}

impl Projection {
    fn response_json(&self, command_status: &str, reason_code: &str) -> String {
        json!({
            "schema": EXPERIMENT_SESSION_RESPONSE_SCHEMA,
            "command_status": command_status,
            "reason_code": reason_code,
            "readback": {
                "runtime_epoch": self.runtime_epoch,
                "revision": self.revision,
                "generation": self.generation,
                "phase": phase_token(self.phase),
                "control_state": self.control_state.as_str(),
                "audio_technical_hold": self.audio_technical_hold,
                "control_receipt_generation": self.control_receipt_generation,
                "control_receipt_revision": self.control_receipt_revision,
                "control_event": self.control_event,
                "control_event_elapsed_realtime_ns": self.control_event_elapsed_realtime_ns,
                "presentation": presentation_token(self.presentation),
                "active_time_ms": self.active_time_ns / 1_000_000,
                "completion": completion_token(self.completion),
                "recording_result": recording_result_value(self.recording_result),
                "counts": {
                    "completed": self.counts.completed,
                    "stopped_early": self.counts.stopped_early,
                    "errors": self.counts.errors,
                    "by_condition": {
                        "condition-a": {
                            "completed": self.condition_counts.condition_a.completed,
                            "stopped_early": self.condition_counts.condition_a.stopped_early,
                            "errors": self.condition_counts.condition_a.errors,
                        },
                        "condition-b": {
                            "completed": self.condition_counts.condition_b.completed,
                            "stopped_early": self.condition_counts.condition_b.stopped_early,
                            "errors": self.condition_counts.condition_b.errors,
                        }
                    }
                },
                "storage_status": self.storage_status,
                "recovery_status": self.recovery_status,
                "recovery_count": self.recovery_count,
                "kiosk_requested": true,
                "kiosk_effective": "unknown",
                "kiosk_enforcement": "not-enforced",
                "last_operation_id": self.last_operation_id,
                "last_operation_status": self.last_operation_status,
                "last_reason": self.last_reason,
                "route_action": self.route_action.as_str(),
                "route_action_revision": self.route_action_revision,
                "profile_identity_bound": self.profile_identity_bound,
                "audio_identity_bound": self.audio_identity_bound,
                "producer_retry_drops": self.producer_retry_drops,
                "radius_metric_status": self.metric_status,
                "initialization_status": self.initialization_status,
                "inventory_status": self.inventory_status,
                "recording_root_status": self.recording_root_status,
                "shutdown_status": self.shutdown_status,
                "shutdown_ack_revision": self.shutdown_ack_revision,
                "defaults_reset_revision": self.defaults_reset_revision,
                "finalized_session_generation": self.finalized_session_generation,
                "finalized_operation_id": self.finalized_operation_id,
            }
        })
        .to_string()
    }
}

#[derive(Clone, Debug)]
struct ReadbackCache {
    accepted: String,
    queued: String,
    command_too_large: String,
    command_queue_full: String,
    terminal_queue_full: String,
    terminal_admission_busy: String,
    worker_closed: String,
}

impl ReadbackCache {
    fn from_projection(projection: &Projection) -> Self {
        Self {
            accepted: projection.response_json("accepted", "none"),
            queued: projection.response_json("queued", "none"),
            command_too_large: projection.response_json("rejected", "command-too-large"),
            command_queue_full: projection.response_json("rejected", "command-queue-full"),
            terminal_queue_full: projection.response_json("rejected", "terminal-lane-full"),
            terminal_admission_busy: projection
                .response_json("rejected", "terminal-admission-busy"),
            worker_closed: projection.response_json("rejected", "session-worker-closed"),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct StartIdentity {
    profile_sha256: String,
    audio: AudioAssetIdentity,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct TrustedConditionInventory {
    pub(crate) condition: ConditionKey,
    pub(crate) completion_threshold_ms: u64,
    pub(crate) audio: AudioAssetIdentity,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct TrustedExperimentInventory {
    pub(crate) provider_id: String,
    pub(crate) provider_manifest_sha256: String,
    pub(crate) provider_inventory_sha256: String,
    pub(crate) non_audio_profile_sha256: String,
    pub(crate) effective_radius_profile: EffectiveRadiusProfile,
    pub(crate) conditions: [TrustedConditionInventory; 2],
}

impl TrustedExperimentInventory {
    fn validate(&self) -> Result<(), &'static str> {
        if self.conditions[0].condition.as_str() != "condition-a"
            || self.conditions[1].condition.as_str() != "condition-b"
            || self.conditions.iter().any(|condition| {
                condition.completion_threshold_ms != DEFAULT_COMPLETION_THRESHOLD_NS / 1_000_000
            })
            || self.conditions[0].audio.source_sha256 == self.conditions[1].audio.source_sha256
        {
            return Err("trusted-inventory-condition-closure-invalid");
        }
        self.effective_radius_profile.validate()?;
        for condition in &self.conditions {
            ExperimentIdentity {
                provider_id: self.provider_id.clone(),
                provider_manifest_sha256: self.provider_manifest_sha256.clone(),
                provider_inventory_sha256: self.provider_inventory_sha256.clone(),
                non_audio_profile_sha256: self.non_audio_profile_sha256.clone(),
                condition_id: condition.condition.as_str().to_owned(),
                audio: condition.audio.clone(),
                effective_radius_profile: self.effective_radius_profile,
            }
            .validate_for(&condition.condition)?;
        }
        Ok(())
    }

    fn identity_for(&self, condition: &ConditionKey) -> Option<ExperimentIdentity> {
        let selected = self
            .conditions
            .iter()
            .find(|entry| entry.condition == *condition)?;
        Some(ExperimentIdentity {
            provider_id: self.provider_id.clone(),
            provider_manifest_sha256: self.provider_manifest_sha256.clone(),
            provider_inventory_sha256: self.provider_inventory_sha256.clone(),
            non_audio_profile_sha256: self.non_audio_profile_sha256.clone(),
            condition_id: condition.as_str().to_owned(),
            audio: selected.audio.clone(),
            effective_radius_profile: self.effective_radius_profile,
        })
    }
}

#[derive(Clone, Debug)]
enum ParsedOperation {
    Start {
        arm: bool,
        condition: ConditionKey,
        completion_threshold_ns: u64,
        utc_ns: u64,
        identity: StartIdentity,
    },
    Presentation(PresentationState),
    Tick,
    AudioStarted,
    AudioEnded,
    AudioError,
    AudioPrepared,
    OfficialStart,
    Pause,
    Resume,
    RestartToExperimenter,
    SaveAndExit,
}

#[derive(Clone, Debug)]
struct ParsedCommand {
    operation_id: String,
    expected_generation: u64,
    at: MonotonicNanos,
    value: Value,
    operation: ParsedOperation,
    received_at: Instant,
}

#[derive(Clone, Debug)]
struct OperationHistoryEntry {
    id: String,
    value: Value,
}

#[derive(Debug)]
struct RetryOffer {
    generation: u64,
    record: SessionRecord,
}

#[derive(Clone, Copy, Debug, Default)]
struct ProducerGate {
    generation: u64,
    accepting: bool,
    // Queue admission reserves a reversible cutoff claim. Each actor-validated
    // terminal releases only its own claim; overlapping requests retain theirs.
    terminal_claims: usize,
    resume_accepting: bool,
}

trait RecordingBackend: Send {
    fn record_control(
        &self,
        generation: u64,
        at: MonotonicNanos,
        kind: SessionEventKind,
    ) -> Result<(), String>;
    fn recover(&mut self, root: &Path) -> Result<RecoveryReport, String>;
    fn prepare(&mut self, generation: u64, spec: SessionStartSpec) -> Result<(), String>;
    fn offer(&self, generation: u64, record: SessionRecord) -> OfferReceipt;
    fn mark_completion(&self, generation: u64, at: MonotonicNanos) -> Result<(), String>;
    fn finalize(
        &self,
        generation: u64,
        at: MonotonicNanos,
        reason: FinalizationReason,
        threshold_reached: bool,
    ) -> Result<FinalizedReceipt, String>;
    fn shutdown(&mut self) -> Result<(), String>;
}

struct ActualRecordingBackend {
    root: Option<PathBuf>,
    writer: Option<Arc<SessionRecordingWriter>>,
    writer_slot: Arc<Mutex<Option<Arc<SessionRecordingWriter>>>>,
    external_loss_slot: Arc<OnceLock<ExternalLossRecorder>>,
}

impl ActualRecordingBackend {
    fn new(
        writer_slot: Arc<Mutex<Option<Arc<SessionRecordingWriter>>>>,
        external_loss_slot: Arc<OnceLock<ExternalLossRecorder>>,
    ) -> Self {
        Self {
            root: None,
            writer: None,
            writer_slot,
            external_loss_slot,
        }
    }

    fn ensure_writer(&mut self, root: &Path) -> Result<Arc<SessionRecordingWriter>, String> {
        if let Some(bound) = self.root.as_ref() {
            if bound != root {
                return Err("recording-root-drift".to_owned());
            }
        }
        if let Some(writer) = self.writer.as_ref() {
            return Ok(Arc::clone(writer));
        }
        let writer = Arc::new(SessionRecordingWriter::spawn(WriterConfig {
            root: root.to_owned(),
            data_queue_capacity: 1_024,
            max_batch_samples: 512,
            max_queued_bytes: 8 * 1024 * 1024,
            checkpoint_interval: Duration::from_secs(1),
        })?);
        self.external_loss_slot
            .set(writer.external_loss_recorder())
            .map_err(|_| "recording-external-loss-recorder-already-bound".to_owned())?;
        self.root = Some(root.to_owned());
        self.writer = Some(Arc::clone(&writer));
        *self
            .writer_slot
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(Arc::clone(&writer));
        Ok(writer)
    }

    fn writer(&self) -> Result<&Arc<SessionRecordingWriter>, String> {
        self.writer
            .as_ref()
            .ok_or_else(|| "recording-writer-not-prepared".to_owned())
    }
}

impl RecordingBackend for ActualRecordingBackend {
    fn record_control(
        &self,
        generation: u64,
        at: MonotonicNanos,
        kind: SessionEventKind,
    ) -> Result<(), String> {
        self.writer()?
            .record_control(generation, at, kind)?
            .receive_timeout(CONTROL_TIMEOUT)
    }
    fn recover(&mut self, root: &Path) -> Result<RecoveryReport, String> {
        self.ensure_writer(root)?
            .recover()?
            .receive_timeout(CONTROL_TIMEOUT)
    }

    fn prepare(&mut self, generation: u64, spec: SessionStartSpec) -> Result<(), String> {
        let root = self
            .root
            .clone()
            .ok_or_else(|| "recording-root-not-bound".to_owned())?;
        self.ensure_writer(&root)?
            .prepare(generation, spec)?
            .receive_timeout(CONTROL_TIMEOUT)
            .map(|_| ())
    }

    fn offer(&self, generation: u64, record: SessionRecord) -> OfferReceipt {
        self.writer()
            .expect("active session always has a recording writer")
            .try_offer(generation, record)
    }

    fn mark_completion(&self, generation: u64, at: MonotonicNanos) -> Result<(), String> {
        self.writer()?
            .mark_completion(generation, at)?
            .receive_timeout(CONTROL_TIMEOUT)
            .map(|_| ())
    }

    fn finalize(
        &self,
        generation: u64,
        at: MonotonicNanos,
        reason: FinalizationReason,
        threshold_reached: bool,
    ) -> Result<FinalizedReceipt, String> {
        let writer = self.writer()?;
        let deadline = Instant::now() + Duration::from_millis(250);
        loop {
            match writer.try_finalize(generation, at, reason, threshold_reached) {
                Ok(receipt) => return receipt.receive_timeout(CONTROL_TIMEOUT),
                Err(OfferOutcome::AdmissionBusy) if Instant::now() < deadline => {
                    thread::yield_now();
                }
                Err(outcome) => {
                    return Err(format!("recording-finalize-admission:{outcome:?}"));
                }
            }
        }
    }

    fn shutdown(&mut self) -> Result<(), String> {
        *self
            .writer_slot
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
        let Some(writer) = self.writer.take() else {
            return Ok(());
        };
        Arc::try_unwrap(writer)
            .map_err(|_| "recording-writer-still-borrowed".to_owned())?
            .shutdown(CONTROL_TIMEOUT)
    }
}

struct SessionWorker {
    controller: ExperimentSessionController,
    backend: Box<dyn RecordingBackend>,
    projection: Arc<RwLock<Projection>>,
    readback_cache: Arc<RwLock<ReadbackCache>>,
    active_generation: Arc<AtomicU64>,
    producer_retry_drops: Arc<AtomicU64>,
    monotonic_bridge: Arc<Mutex<Option<(MonotonicNanos, Instant)>>>,
    history: VecDeque<OperationHistoryEntry>,
    next_reducer_operation: u64,
    pending_start: Option<(SessionStartSpec, StartIdentity, Instant)>,
    current_identity: Option<ExperimentIdentity>,
    last_presentation: PresentationState,
    recording_root: PathBuf,
    trusted_inventory: Option<TrustedExperimentInventory>,
    pending_terminal_operation: Option<(u64, String)>,
    producer_gate: Arc<RwLock<ProducerGate>>,
    retry_receiver: Receiver<RetryOffer>,
    route_state: Arc<AtomicU64>,
    presentation_state: Arc<AtomicU64>,
    external_loss_slot: Arc<OnceLock<ExternalLossRecorder>>,
    submitted_frame_generation: Arc<AtomicU64>,
    submitted_frame_sequence: Arc<AtomicU64>,
    submitted_frame_elapsed_ns: Arc<AtomicU64>,
    effective_radius_profile: Arc<RwLock<Option<EffectiveRadiusProfile>>>,
    completion_persistence_failure: Option<String>,
    stop_after_ack: bool,
}

impl SessionWorker {
    fn new(
        backend: Box<dyn RecordingBackend>,
        projection: Arc<RwLock<Projection>>,
        readback_cache: Arc<RwLock<ReadbackCache>>,
        active_generation: Arc<AtomicU64>,
        producer_retry_drops: Arc<AtomicU64>,
        monotonic_bridge: Arc<Mutex<Option<(MonotonicNanos, Instant)>>>,
        recording_root: PathBuf,
        trusted_inventory: Option<TrustedExperimentInventory>,
        producer_gate: Arc<RwLock<ProducerGate>>,
        retry_receiver: Receiver<RetryOffer>,
        route_state: Arc<AtomicU64>,
        presentation_state: Arc<AtomicU64>,
        external_loss_slot: Arc<OnceLock<ExternalLossRecorder>>,
        submitted_frame_generation: Arc<AtomicU64>,
        submitted_frame_sequence: Arc<AtomicU64>,
        submitted_frame_elapsed_ns: Arc<AtomicU64>,
        effective_radius_profile: Arc<RwLock<Option<EffectiveRadiusProfile>>>,
    ) -> Self {
        Self {
            controller: ExperimentSessionController::default(),
            backend,
            projection,
            readback_cache,
            active_generation,
            producer_retry_drops,
            monotonic_bridge,
            history: VecDeque::new(),
            next_reducer_operation: 0,
            pending_start: None,
            current_identity: None,
            last_presentation: PresentationState::Experimenter,
            recording_root,
            trusted_inventory,
            pending_terminal_operation: None,
            producer_gate,
            retry_receiver,
            route_state,
            presentation_state,
            external_loss_slot,
            submitted_frame_generation,
            submitted_frame_sequence,
            submitted_frame_elapsed_ns,
            effective_radius_profile,
            completion_persistence_failure: None,
            stop_after_ack: false,
        }
    }

    fn process_raw(&mut self, raw: String, received_at: Instant) {
        if is_status_command(&raw) {
            self.refresh_controller_projection();
            return;
        }
        let value = match parse_command_value(&raw) {
            Ok(value) => value,
            Err(reason) => {
                self.publish_rejection(None, reason);
                return;
            }
        };
        let parsed = match parse_mutating_command(value, received_at) {
            Ok(command) => command,
            Err((operation, reason)) => {
                self.publish_rejection(operation, reason);
                return;
            }
        };
        if let Some(previous) = self
            .history
            .iter()
            .find(|entry| entry.id == parsed.operation_id)
        {
            if previous.value == parsed.value {
                self.publish_operation(&parsed.operation_id, "duplicate", "none", None);
            } else {
                self.publish_operation(
                    &parsed.operation_id,
                    "rejected",
                    "operation-conflict",
                    None,
                );
            }
            return;
        }
        self.remember(&parsed);
        self.apply_parsed(parsed);
    }

    fn initialize(&mut self) -> bool {
        let inventory_status = match self.trusted_inventory.as_ref() {
            None => "inventory-unavailable",
            Some(inventory) if inventory.validate().is_ok() => "ready",
            Some(_) => "invalid",
        };
        match self.backend.recover(&self.recording_root) {
            Ok(recovery) => {
                self.set_recovery(&recovery);
                self.projection_write(|projection| {
                    projection.initialization_status = "ready";
                    projection.inventory_status = inventory_status;
                    projection.recording_root_status = "ready";
                });
                true
            }
            Err(error) => {
                self.set_storage_state("error", "error", 0);
                self.projection_write(|projection| {
                    projection.initialization_status = "error";
                    projection.inventory_status = inventory_status;
                    projection.recording_root_status = "error";
                    projection.last_operation_status = "failed";
                    projection.last_reason = error;
                });
                false
            }
        }
    }

    fn apply_parsed(&mut self, parsed: ParsedCommand) {
        let resolved_identity = match &parsed.operation {
            ParsedOperation::Start {
                condition,
                completion_threshold_ns,
                identity,
                ..
            } => match self.resolve_start_identity(condition, *completion_threshold_ns, identity) {
                Ok(identity) => Some(identity),
                Err(reason) => {
                    self.publish_operation(&parsed.operation_id, "rejected", reason, None);
                    return;
                }
            },
            _ => None,
        };
        let command = match &parsed.operation {
            ParsedOperation::Start {
                arm,
                condition,
                completion_threshold_ns,
                utc_ns,
                identity,
            } => {
                let spec = SessionStartSpec {
                    started_at: SessionTimestampKey::from_utc(UtcEpochNanos::new(*utc_ns)),
                    condition: condition.clone(),
                    completion_threshold_ns: *completion_threshold_ns,
                    clock_anchor: ClockAnchor::new(parsed.at, UtcEpochNanos::new(*utc_ns)),
                    experiment_identity: resolved_identity
                        .clone()
                        .expect("resolved start identity"),
                };
                self.pending_start = Some((spec, identity.clone(), parsed.received_at));
                if *arm {
                    SessionCommand::Arm {
                        condition: condition.clone(),
                        completion_threshold_ns: *completion_threshold_ns,
                    }
                } else {
                    SessionCommand::Start {
                        condition: condition.clone(),
                        completion_threshold_ns: *completion_threshold_ns,
                    }
                }
            }
            ParsedOperation::Presentation(value) => SessionCommand::PresentationChanged(*value),
            ParsedOperation::Tick => SessionCommand::Tick,
            ParsedOperation::AudioStarted => SessionCommand::AudioStarted,
            ParsedOperation::AudioEnded => SessionCommand::AudioEnded,
            ParsedOperation::AudioError => SessionCommand::AudioError,
            ParsedOperation::AudioPrepared => SessionCommand::AudioPrepared,
            ParsedOperation::OfficialStart => SessionCommand::OfficialStart,
            ParsedOperation::Pause => SessionCommand::Pause,
            ParsedOperation::Resume => SessionCommand::Resume,
            ParsedOperation::RestartToExperimenter => SessionCommand::RestartToExperimenter,
            ParsedOperation::SaveAndExit => SessionCommand::SaveAndExit,
        };
        let outcome = self.apply_controller(parsed.expected_generation, parsed.at, command);
        if !outcome.accepted {
            self.pending_start = None;
            self.publish_operation(
                &parsed.operation_id,
                "rejected",
                outcome
                    .rejection
                    .map_or("controller-rejected".to_owned(), |value| {
                        format!("{value:?}")
                    }),
                Some(&outcome),
            );
            return;
        }
        if matches!(
            parsed.operation,
            ParsedOperation::RestartToExperimenter | ParsedOperation::SaveAndExit
        ) {
            self.pending_terminal_operation =
                Some((outcome.generation, parsed.operation_id.clone()));
        }
        // Publish the reducer transition before any durable control operation.
        // In particular a repeated B/Home interaction observes Saving/Exiting
        // while finalization owns the control worker and cannot emit a route.
        self.refresh_controller_projection();
        let result = self.process_effects(outcome.effects.clone(), parsed.at);
        match result {
            Ok(()) => self.publish_operation(&parsed.operation_id, "accepted", "none", None),
            Err(reason) => {
                self.publish_operation(&parsed.operation_id, "failed", &reason, None);
            }
        }
    }

    fn resolve_start_identity(
        &self,
        condition: &ConditionKey,
        completion_threshold_ns: u64,
        requested: &StartIdentity,
    ) -> Result<ExperimentIdentity, &'static str> {
        let inventory = self
            .trusted_inventory
            .as_ref()
            .ok_or("inventory-unavailable")?;
        inventory.validate()?;
        let expected = inventory
            .identity_for(condition)
            .ok_or("condition-not-packaged")?;
        if completion_threshold_ns / 1_000_000
            != inventory
                .conditions
                .iter()
                .find(|entry| entry.condition == *condition)
                .map(|entry| entry.completion_threshold_ms)
                .ok_or("condition-not-packaged")?
        {
            return Err("completion-threshold-not-packaged");
        }
        if requested.profile_sha256 != expected.non_audio_profile_sha256 {
            return Err("profile-identity-not-packaged");
        }
        if requested.audio != expected.audio {
            return Err("audio-identity-not-packaged");
        }
        Ok(expected)
    }

    fn apply_controller(
        &mut self,
        expected_generation: u64,
        at: MonotonicNanos,
        command: SessionCommand,
    ) -> CommandOutcome {
        self.next_reducer_operation = self.next_reducer_operation.saturating_add(1).max(1);
        self.controller.apply(CommandEnvelope {
            operation: OperationToken::new(self.next_reducer_operation)
                .expect("positive reducer operation"),
            expected_generation,
            at,
            command,
        })
    }

    fn apply_internal(
        &mut self,
        at: MonotonicNanos,
        command: SessionCommand,
    ) -> Result<CommandOutcome, String> {
        let outcome = self.apply_controller(self.controller.generation(), at, command);
        if outcome.accepted {
            Ok(outcome)
        } else {
            Err(format!("session-internal-rejected:{:?}", outcome.rejection))
        }
    }

    fn process_effects(
        &mut self,
        effects: Vec<SessionEffect>,
        at: MonotonicNanos,
    ) -> Result<(), String> {
        for effect in effects {
            match effect {
                SessionEffect::PrepareRecording { generation, .. } => {
                    let (spec, identity, received_at) = self
                        .pending_start
                        .take()
                        .ok_or_else(|| "session-start-contract-missing".to_owned())?;
                    self.set_storage_state("preparing", "running", 0);
                    let preparation =
                        self.backend
                            .recover(&self.recording_root)
                            .and_then(|recovery| {
                                self.set_recovery(&recovery);
                                self.backend.prepare(generation, spec.clone())
                            });
                    match preparation {
                        Ok(()) => {
                            let _ = identity;
                            // Arming owns the pre-roll lifecycle: the participant must be able
                            // to verify breathing before the official controller start. This is
                            // opt-in and inert for builds without an effective breath selection.
                            let _ = crate::breath_composition_runtime::
                                ensure_running_for_experiment_arm();
                            self.current_identity = Some(spec.experiment_identity.clone());
                            *self
                                .effective_radius_profile
                                .write()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) =
                                Some(spec.experiment_identity.effective_radius_profile);
                            self.set_route(RouteAction::None);
                            *self
                                .monotonic_bridge
                                .lock()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) =
                                Some((spec.clock_anchor.monotonic, received_at));
                            reset_producer_loss(&self.producer_retry_drops, generation);
                            self.submitted_frame_generation.store(0, Ordering::Release);
                            self.submitted_frame_sequence.store(0, Ordering::Release);
                            self.submitted_frame_elapsed_ns.store(0, Ordering::Release);
                            *self
                                .producer_gate
                                .write()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = ProducerGate {
                                generation,
                                accepting: true,
                                terminal_claims: 0,
                                resume_accepting: true,
                            };
                            self.active_generation.store(generation, Ordering::Release);
                            // RecordingPrepared is part of the same logical start operation.
                            // Worker scheduling delay must not advance the reducer clock past a
                            // later command timestamp supplied by Android elapsed realtime.
                            let elapsed = spec.clock_anchor.monotonic.get();
                            let prepared = self.apply_internal(
                                MonotonicNanos::new(elapsed),
                                SessionCommand::RecordingPrepared,
                            )?;
                            self.process_effects(prepared.effects, MonotonicNanos::new(elapsed))?;
                        }
                        Err(error) => {
                            self.set_storage_state("error", "error", 0);
                            self.active_generation.store(0, Ordering::Release);
                            *self
                                .effective_radius_profile
                                .write()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
                            self.submitted_frame_generation.store(0, Ordering::Release);
                            self.submitted_frame_sequence.store(0, Ordering::Release);
                            let failed = self.apply_internal(
                                at,
                                SessionCommand::RecordingPrepareFailed {
                                    error: RecordingFailure::Prepare,
                                },
                            )?;
                            self.process_effects(failed.effects, at)?;
                            return Err(error);
                        }
                    }
                }
                SessionEffect::RecordPresentation(presentation) => {
                    let kind = presentation_event(self.last_presentation, presentation);
                    self.last_presentation = presentation;
                    self.offer_control_event(at, kind)?;
                    if presentation == PresentationState::Developer {
                        self.set_route(RouteAction::OpenDeveloper);
                    } else if presentation == PresentationState::ImmersiveActive {
                        self.set_route(RouteAction::None);
                    }
                }
                SessionEffect::RecordControl(kind) => {
                    if let Err(error) =
                        self.backend
                            .record_control(self.controller.generation(), at, kind)
                    {
                        // An uncertain control checkpoint must never leave an apparently
                        // running experiment. Finalize once and report the recording failure.
                        self.completion_persistence_failure = Some(error.clone());
                        let stopping =
                            self.apply_internal(at, SessionCommand::RestartToExperimenter)?;
                        self.refresh_controller_projection();
                        let _ = self.process_effects(stopping.effects, at);
                        return Err(format!("recording-control-persistence:{error}"));
                    }
                    self.projection_write(|projection| {
                        projection.control_receipt_generation = self.controller.generation();
                        projection.control_receipt_revision =
                            projection.control_receipt_revision.saturating_add(1);
                        projection.control_event = kind.as_str();
                        projection.control_event_elapsed_realtime_ns = at.get();
                    });
                }
                SessionEffect::RecordAudioStarted => {
                    self.offer_control_event(at, SessionEventKind::AudioStarted)?;
                }
                SessionEffect::RecordAudioEnded => {
                    self.offer_control_event(at, SessionEventKind::AudioEnded)?;
                }
                SessionEffect::RecordAudioError => {
                    self.offer_control_event(at, SessionEventKind::AudioError)?;
                }
                SessionEffect::PersistCompletion {
                    generation,
                    active_time_ns: _,
                } => {
                    match self.backend.mark_completion(generation, at) {
                        Ok(()) => {
                            let durable =
                                self.apply_internal(at, SessionCommand::CompletionDurable)?;
                            self.process_effects(durable.effects, at)?;
                        }
                        Err(completion_error) => {
                            // The threshold crossing is already part of this
                            // logical operation. Preserve the fault and always
                            // reach the one terminal finalize effect. If no
                            // terminal operation was in flight (worker timer),
                            // initiate a restart now; otherwise the following
                            // effect owns the exact already-selected B/Home
                            // intent.
                            self.completion_persistence_failure = Some(completion_error.clone());
                            if self.controller.phase() == SessionPhase::Active {
                                let stopping =
                                    self.apply_internal(at, SessionCommand::RestartToExperimenter)?;
                                self.refresh_controller_projection();
                                let finalize_result = self.process_effects(stopping.effects, at);
                                return Err(match finalize_result {
                                    Ok(()) => format!(
                                        "recording-completion-persistence:{completion_error}"
                                    ),
                                    Err(finalize_error) => format!(
                                        "recording-completion-persistence:{completion_error};{finalize_error}"
                                    ),
                                });
                            }
                        }
                    }
                }
                SessionEffect::FinalizeRecording {
                    generation,
                    reason,
                    threshold_reached,
                } => {
                    // The recording actor writes the typed restart/exit event as
                    // part of its terminal transaction. Enqueuing a second event
                    // here would create duplicate lifecycle evidence and could
                    // race the accepted-sequence cutoff.
                    self.close_producer_gate_and_drain(generation);
                    match self
                        .backend
                        .finalize(generation, at, reason, threshold_reached)
                    {
                        Ok(receipt) => {
                            self.set_recovery(&receipt.recovery);
                            let completion_failure = self.completion_persistence_failure.take();
                            let finalized = if completion_failure.is_some() {
                                self.apply_internal(
                                    at,
                                    SessionCommand::RecordingFinalizationFailed {
                                        durable_completion: self.controller.completion()
                                            == CompletionProgress::Durable,
                                        error: RecordingFailure::DataLoss,
                                    },
                                )?
                            } else {
                                self.apply_internal(
                                    at,
                                    SessionCommand::RecordingFinalized {
                                        durable_completion: receipt.completed,
                                        saved: receipt.saved
                                            && producer_loss_count(
                                                &self.producer_retry_drops,
                                                generation,
                                            ) == 0,
                                    },
                                )?
                            };
                            // Publish the terminal reducer result before the
                            // route action becomes observable to Android.
                            self.refresh_controller_projection();
                            self.process_effects(finalized.effects, at)?;
                            self.active_generation.store(0, Ordering::Release);
                            self.submitted_frame_generation.store(0, Ordering::Release);
                            self.submitted_frame_sequence.store(0, Ordering::Release);
                            *self
                                .effective_radius_profile
                                .write()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
                            *self
                                .monotonic_bridge
                                .lock()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
                            if let Some(error) = completion_failure {
                                return Err(format!("recording-completion-persistence:{error}"));
                            }
                        }
                        Err(error) => {
                            self.completion_persistence_failure = None;
                            self.set_storage_state("error", "error", 0);
                            let failed = self.apply_internal(
                                at,
                                SessionCommand::RecordingFinalizationFailed {
                                    durable_completion: self.controller.completion()
                                        == CompletionProgress::Durable,
                                    error: RecordingFailure::Finalize,
                                },
                            )?;
                            self.refresh_controller_projection();
                            self.process_effects(failed.effects, at)?;
                            self.active_generation.store(0, Ordering::Release);
                            *self
                                .effective_radius_profile
                                .write()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
                            *self
                                .monotonic_bridge
                                .lock()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
                            return Err(error);
                        }
                    }
                }
                SessionEffect::DisarmKiosk => {}
                SessionEffect::ResetRuntimeDefaults => {
                    crate::breath_composition_runtime::reset_to_packaged_defaults();
                    let reset_revision = request_particle_packaged_defaults_reset();
                    self.current_identity = None;
                    self.projection_write(|projection| {
                        projection.defaults_reset_revision = reset_revision;
                    });
                }
                SessionEffect::ShowExperimenter => {
                    self.publish_terminal_route_identity();
                    self.set_route(RouteAction::ShowExperimenter);
                }
                SessionEffect::ShutdownApp => {
                    self.projection_write(|projection| {
                        projection.shutdown_status = "joining";
                    });
                    match self.backend.shutdown() {
                        Ok(()) => {
                            self.publish_terminal_route_identity();
                            self.projection_write(|projection| {
                                projection.shutdown_status = "complete";
                                projection.shutdown_ack_revision =
                                    projection.shutdown_ack_revision.saturating_add(1);
                            });
                            self.set_route(RouteAction::ShutdownApp);
                            self.stop_after_ack = true;
                        }
                        Err(error) => {
                            self.projection_write(|projection| {
                                projection.shutdown_status = "error";
                                projection.last_operation_status = "failed";
                                projection.last_reason = error.clone();
                            });
                            return Err(error);
                        }
                    }
                }
            }
        }
        Ok(())
    }

    fn offer_control_event(
        &mut self,
        at: MonotonicNanos,
        kind: SessionEventKind,
    ) -> Result<(), String> {
        let generation = self.controller.generation();
        accepted_offer(self.backend.offer(
            generation,
            SessionRecord::Event(SessionEvent {
                observed_at: at,
                kind,
            }),
        ))
    }

    fn process_retry(&mut self, retry: RetryOffer) {
        if self.active_generation.load(Ordering::Acquire) != retry.generation {
            return;
        }
        let mut record = retry.record;
        let deadline = Instant::now() + Duration::from_millis(250);
        loop {
            let receipt = self.backend.offer(retry.generation, record);
            match receipt.outcome {
                OfferOutcome::AdmissionBusy => {
                    let Some(returned) = receipt.into_retry_record() else {
                        record_producer_loss(&self.producer_retry_drops, retry.generation);
                        return;
                    };
                    if self.active_generation.load(Ordering::Acquire) != retry.generation {
                        return;
                    }
                    if Instant::now() >= deadline {
                        if let Some(recorder) = self.external_loss_slot.get() {
                            let _ = recorder.record(retry.generation, &returned);
                        }
                        record_producer_loss(&self.producer_retry_drops, retry.generation);
                        return;
                    }
                    record = returned;
                    thread::yield_now();
                }
                OfferOutcome::WriterClosed => {
                    if let Some(returned) = receipt.into_retry_record() {
                        if let Some(recorder) = self.external_loss_slot.get() {
                            let _ = recorder.record(retry.generation, &returned);
                        }
                    }
                    record_producer_loss(&self.producer_retry_drops, retry.generation);
                    return;
                }
                _ => return,
            }
        }
    }

    fn drain_pending_retries(&mut self) {
        while let Ok(retry) = self.retry_receiver.try_recv() {
            self.process_retry(retry);
        }
    }

    fn close_producer_gate_and_drain(&mut self, generation: u64) {
        let gate = Arc::clone(&self.producer_gate);
        let mut guard = gate
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if guard.generation == generation {
            guard.accepting = false;
        }
        self.drain_pending_retries();
    }

    fn process_immersive_owner_destroyed(&mut self) -> Result<(), String> {
        let at = match (
            current_from_bridge(&self.monotonic_bridge),
            self.controller.last_monotonic(),
        ) {
            (Some(current), Some(last)) => std::cmp::max(current, last),
            (Some(current), None) => current,
            (None, Some(last)) => last,
            (None, None) => MonotonicNanos::new(0),
        };
        let stopping = self.apply_internal(at, SessionCommand::ImmersiveOwnerDestroyed)?;
        self.refresh_controller_projection();
        let result = self.process_effects(stopping.effects, at);
        self.refresh_controller_projection();
        result
    }

    fn process_timed_tick(&mut self) {
        let Some(now) = current_from_bridge(&self.monotonic_bridge) else {
            return;
        };
        if self.controller.presentation() == PresentationState::ImmersiveActive {
            let generation = self.controller.generation();
            let submitted_generation = self.submitted_frame_generation.load(Ordering::Acquire);
            let submitted_sequence = self.submitted_frame_sequence.load(Ordering::Acquire);
            let submitted_at = self.submitted_frame_elapsed_ns.load(Ordering::Acquire);
            if submitted_generation != generation
                || submitted_sequence == 0
                || now.get().saturating_sub(submitted_at) > SUBMITTED_FRAME_STALL_NS
            {
                match self.apply_internal(
                    now,
                    SessionCommand::PresentationChanged(PresentationState::Unfocused),
                ) {
                    Ok(outcome) => {
                        self.refresh_controller_projection();
                        if let Err(error) = self.process_effects(outcome.effects, now) {
                            self.publish_rejection(None, &error);
                        }
                    }
                    Err(error) => self.publish_rejection(None, &error),
                }
                return;
            }
        }
        let Some(deadline) = self.controller.completion_deadline() else {
            return;
        };
        if now < deadline {
            return;
        }
        match self.apply_internal(now, SessionCommand::Tick) {
            Ok(outcome) => {
                self.refresh_controller_projection();
                if let Err(error) = self.process_effects(outcome.effects, now) {
                    self.publish_rejection(None, &error);
                } else {
                    self.refresh_controller_projection();
                }
            }
            Err(error) => self.publish_rejection(None, &error),
        }
    }

    fn publish_terminal_route_identity(&mut self) {
        let Some((generation, operation_id)) = self.pending_terminal_operation.take() else {
            return;
        };
        self.projection_write(|projection| {
            projection.finalized_session_generation = generation;
            projection.finalized_operation_id = Some(operation_id);
        });
    }

    fn remember(&mut self, parsed: &ParsedCommand) {
        if self.history.len() == MAX_OPERATION_HISTORY {
            self.history.pop_front();
        }
        self.history.push_back(OperationHistoryEntry {
            id: parsed.operation_id.clone(),
            value: parsed.value.clone(),
        });
    }

    fn set_recovery(&self, recovery: &RecoveryReport) {
        self.projection_write(|projection| {
            projection.counts = recovery.counts;
            projection.condition_counts = recovery.condition_counts;
            projection.storage_status = "ready";
            projection.recovery_status = "complete";
            projection.recovery_count = recovery.sessions.len() as u64;
        });
    }

    fn set_storage_state(
        &self,
        storage_status: &'static str,
        recovery_status: &'static str,
        recovery_count: u64,
    ) {
        self.projection_write(|projection| {
            projection.storage_status = storage_status;
            projection.recovery_status = recovery_status;
            projection.recovery_count = recovery_count;
        });
    }

    fn set_route(&self, action: RouteAction) {
        self.projection_write(|projection| {
            projection.route_action = action;
            projection.route_action_revision = projection.route_action_revision.saturating_add(1);
            self.route_state.store(
                (projection.route_action_revision << 8) | action.code(),
                Ordering::Release,
            );
        });
    }

    fn publish_rejection(&self, operation: Option<String>, reason: &str) {
        self.projection_write(|projection| {
            projection.revision = projection.revision.saturating_add(1);
            projection.last_operation_id = operation;
            projection.last_operation_status = "rejected";
            projection.last_reason = reason.to_owned();
        });
        self.refresh_controller_projection();
    }

    fn publish_operation(
        &self,
        operation: &str,
        status: &'static str,
        reason: impl AsRef<str>,
        _outcome: Option<&CommandOutcome>,
    ) {
        self.projection_write(|projection| {
            projection.revision = projection.revision.saturating_add(1);
            projection.last_operation_id = Some(operation.to_owned());
            projection.last_operation_status = status;
            projection.last_reason = reason.as_ref().to_owned();
        });
        self.refresh_controller_projection();
    }

    fn refresh_controller_projection(&self) {
        self.projection_write(|projection| {
            projection.generation = self.controller.generation();
            projection.phase = self.controller.phase();
            projection.control_state = self.controller.control_state();
            projection.audio_technical_hold = self.controller.audio_error();
            projection.presentation = self.controller.presentation();
            self.presentation_state.store(
                presentation_code(self.controller.presentation()),
                Ordering::Release,
            );
            projection.active_time_ns = self.controller.active_time_ns();
            projection.completion = self.controller.completion();
            projection.recording_result = self.controller.recording_result();
            projection.profile_identity_bound = self.current_identity.is_some();
            projection.audio_identity_bound = self.current_identity.is_some();
            projection.producer_retry_drops =
                producer_loss_count(&self.producer_retry_drops, self.controller.generation());
        });
    }

    fn projection_write(&self, update: impl FnOnce(&mut Projection)) {
        let mut projection = self
            .projection
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        update(&mut projection);
        let cache = ReadbackCache::from_projection(&projection);
        drop(projection);
        *self
            .readback_cache
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = cache;
    }
}

#[cfg(target_os = "android")]
fn request_particle_packaged_defaults_reset() -> u64 {
    crate::gpu_private_particles::request_packaged_defaults_reset()
}

#[cfg(not(target_os = "android"))]
fn request_particle_packaged_defaults_reset() -> u64 {
    static RESET: AtomicU64 = AtomicU64::new(0);
    RESET.fetch_add(1, Ordering::AcqRel).saturating_add(1)
}

enum WorkerMessage {
    Command {
        raw: String,
        received_at: Instant,
        terminal_claim: Option<u64>,
    },
    ImmersiveOwnerDestroyed {
        acknowledgement: SyncSender<Result<(), String>>,
    },
    Shutdown,
    #[cfg(test)]
    PauseForTest {
        entered: SyncSender<()>,
        resume: Receiver<()>,
    },
}

pub(crate) struct ExperimentSessionRuntime {
    runtime_epoch: u64,
    recording_root: PathBuf,
    command_sender: SyncSender<WorkerMessage>,
    terminal_sender: SyncSender<WorkerMessage>,
    retry_sender: SyncSender<RetryOffer>,
    projection: Arc<RwLock<Projection>>,
    readback_cache: Arc<RwLock<ReadbackCache>>,
    active_generation: Arc<AtomicU64>,
    producer_retry_drops: Arc<AtomicU64>,
    producer_gate: Arc<RwLock<ProducerGate>>,
    writer_slot: Arc<Mutex<Option<Arc<SessionRecordingWriter>>>>,
    external_loss_slot: Arc<OnceLock<ExternalLossRecorder>>,
    monotonic_bridge: Arc<Mutex<Option<(MonotonicNanos, Instant)>>>,
    route_state: Arc<AtomicU64>,
    presentation_state: Arc<AtomicU64>,
    submitted_frame_generation: Arc<AtomicU64>,
    submitted_frame_sequence: Arc<AtomicU64>,
    submitted_frame_elapsed_ns: Arc<AtomicU64>,
    effective_radius_profile: Arc<RwLock<Option<EffectiveRadiusProfile>>>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl ExperimentSessionRuntime {
    fn spawn(
        recording_root: PathBuf,
        trusted_inventory: Option<TrustedExperimentInventory>,
        backend_factory: impl FnOnce(
                Arc<Mutex<Option<Arc<SessionRecordingWriter>>>>,
                Arc<OnceLock<ExternalLossRecorder>>,
            ) -> Box<dyn RecordingBackend>
            + Send
            + 'static,
    ) -> Result<Self, String> {
        Self::spawn_with_epoch(recording_root, trusted_inventory, 1, backend_factory)
    }

    fn spawn_with_epoch(
        recording_root: PathBuf,
        trusted_inventory: Option<TrustedExperimentInventory>,
        runtime_epoch: u64,
        backend_factory: impl FnOnce(
                Arc<Mutex<Option<Arc<SessionRecordingWriter>>>>,
                Arc<OnceLock<ExternalLossRecorder>>,
            ) -> Box<dyn RecordingBackend>
            + Send
            + 'static,
    ) -> Result<Self, String> {
        if runtime_epoch == 0 {
            return Err("experiment-session-runtime-epoch-invalid".to_owned());
        }
        let (command_sender, command_receiver) = mpsc::sync_channel(COMMAND_QUEUE_CAPACITY);
        let (terminal_sender, terminal_receiver) = mpsc::sync_channel(TERMINAL_QUEUE_CAPACITY);
        let (retry_sender, retry_receiver) = mpsc::sync_channel(RETRY_QUEUE_CAPACITY);
        let initial_projection = Projection {
            runtime_epoch,
            ..Projection::default()
        };
        let readback_cache = Arc::new(RwLock::new(ReadbackCache::from_projection(
            &initial_projection,
        )));
        let projection = Arc::new(RwLock::new(initial_projection));
        let active_generation = Arc::new(AtomicU64::new(0));
        let producer_retry_drops = Arc::new(AtomicU64::new(0));
        let producer_gate = Arc::new(RwLock::new(ProducerGate::default()));
        let writer_slot = Arc::new(Mutex::new(None));
        let external_loss_slot = Arc::new(OnceLock::new());
        let monotonic_bridge = Arc::new(Mutex::new(None));
        let route_state = Arc::new(AtomicU64::new(0));
        let presentation_state = Arc::new(AtomicU64::new(0));
        let submitted_frame_generation = Arc::new(AtomicU64::new(0));
        let submitted_frame_sequence = Arc::new(AtomicU64::new(0));
        let submitted_frame_elapsed_ns = Arc::new(AtomicU64::new(0));
        let effective_radius_profile = Arc::new(RwLock::new(None));
        let worker_projection = Arc::clone(&projection);
        let worker_readback = Arc::clone(&readback_cache);
        let worker_generation = Arc::clone(&active_generation);
        let worker_drops = Arc::clone(&producer_retry_drops);
        let worker_slot = Arc::clone(&writer_slot);
        let worker_external_loss = Arc::clone(&external_loss_slot);
        let session_worker_external_loss = Arc::clone(&external_loss_slot);
        let worker_bridge = Arc::clone(&monotonic_bridge);
        let worker_gate = Arc::clone(&producer_gate);
        let worker_route_state = Arc::clone(&route_state);
        let worker_presentation_state = Arc::clone(&presentation_state);
        let worker_submitted_frame_generation = Arc::clone(&submitted_frame_generation);
        let worker_submitted_frame_sequence = Arc::clone(&submitted_frame_sequence);
        let worker_submitted_frame_elapsed_ns = Arc::clone(&submitted_frame_elapsed_ns);
        let worker_effective_radius_profile = Arc::clone(&effective_radius_profile);
        let runtime_recording_root = recording_root.clone();
        let worker = thread::Builder::new()
            .name("experiment-session-control".to_owned())
            .spawn(move || {
                let backend = backend_factory(worker_slot, worker_external_loss);
                run_worker(
                    command_receiver,
                    terminal_receiver,
                    SessionWorker::new(
                        backend,
                        worker_projection,
                        worker_readback,
                        worker_generation,
                        worker_drops,
                        worker_bridge,
                        recording_root,
                        trusted_inventory,
                        worker_gate,
                        retry_receiver,
                        worker_route_state,
                        worker_presentation_state,
                        session_worker_external_loss,
                        worker_submitted_frame_generation,
                        worker_submitted_frame_sequence,
                        worker_submitted_frame_elapsed_ns,
                        worker_effective_radius_profile,
                    ),
                );
            })
            .map_err(|error| format!("experiment-session-worker-spawn:{error}"))?;
        Ok(Self {
            runtime_epoch,
            recording_root: runtime_recording_root,
            command_sender,
            terminal_sender,
            retry_sender,
            projection,
            readback_cache,
            active_generation,
            producer_retry_drops,
            producer_gate,
            writer_slot,
            external_loss_slot,
            monotonic_bridge,
            route_state,
            presentation_state,
            submitted_frame_generation,
            submitted_frame_sequence,
            submitted_frame_elapsed_ns,
            effective_radius_profile,
            worker: Mutex::new(Some(worker)),
        })
    }

    fn spawn_actual(
        recording_root: PathBuf,
        trusted_inventory: Option<TrustedExperimentInventory>,
    ) -> Result<Self, String> {
        Self::spawn_actual_with_epoch(recording_root, trusted_inventory, 1)
    }

    fn spawn_actual_with_epoch(
        recording_root: PathBuf,
        trusted_inventory: Option<TrustedExperimentInventory>,
        runtime_epoch: u64,
    ) -> Result<Self, String> {
        Self::spawn_with_epoch(
            recording_root,
            trusted_inventory,
            runtime_epoch,
            |slot, external_loss| Box::new(ActualRecordingBackend::new(slot, external_loss)),
        )
    }

    pub(crate) fn apply_command_json(&self, raw: &str) -> String {
        if raw.len() > MAX_COMMAND_BYTES {
            return self.cached(|cache| &cache.command_too_large);
        }
        let terminal_generation = terminal_command_generation(raw);
        let mut terminal_gate = if let Some(generation) = terminal_generation {
            let mut gate = match self.producer_gate.try_write() {
                Ok(gate) => gate,
                Err(TryLockError::WouldBlock) => {
                    return self.cached(|cache| &cache.terminal_admission_busy)
                }
                Err(TryLockError::Poisoned(_)) => return self.cached(|cache| &cache.worker_closed),
            };
            let prior_gate = *gate;
            if gate.generation == generation {
                if gate.terminal_claims == 0 {
                    gate.resume_accepting = gate.accepting;
                }
                gate.terminal_claims += 1;
                gate.accepting = false;
            }
            Some((generation, prior_gate, gate))
        } else {
            None
        };
        let sender = if terminal_generation.is_some() {
            &self.terminal_sender
        } else {
            &self.command_sender
        };
        match sender.try_send(WorkerMessage::Command {
            raw: raw.to_owned(),
            received_at: Instant::now(),
            terminal_claim: terminal_gate.as_ref().and_then(|(generation, _, gate)| {
                (gate.generation == *generation).then_some(*generation)
            }),
        }) {
            Ok(()) => self.cached(|cache| &cache.queued),
            Err(TrySendError::Full(_)) if terminal_generation.is_some() => {
                let (generation, prior_gate, gate) = terminal_gate
                    .as_mut()
                    .expect("terminal command holds the admission gate");
                if gate.generation == *generation
                    && self.active_generation.load(Ordering::Acquire) == *generation
                {
                    **gate = *prior_gate;
                }
                self.cached(|cache| &cache.terminal_queue_full)
            }
            Err(TrySendError::Full(_)) => self.cached(|cache| &cache.command_queue_full),
            Err(TrySendError::Disconnected(_)) => {
                if let Some((generation, prior_gate, gate)) = terminal_gate.as_mut() {
                    if gate.generation == *generation
                        && self.active_generation.load(Ordering::Acquire) == *generation
                    {
                        **gate = *prior_gate;
                    }
                }
                self.cached(|cache| &cache.worker_closed)
            }
        }
    }

    pub(crate) fn status_json(&self) -> String {
        self.cached(|cache| &cache.accepted)
    }

    fn cached(&self, select: impl FnOnce(&ReadbackCache) -> &String) -> String {
        match self.readback_cache.try_read() {
            Ok(cache) => select(&cache).clone(),
            Err(_) => "{\"schema\":\"rusty.quest.experiment_session.response.v1\",\"command_status\":\"rejected\",\"reason_code\":\"readback-busy\",\"readback\":{}}".to_owned(),
        }
    }

    fn worker_finished(&self) -> bool {
        self.worker
            .try_lock()
            .ok()
            .is_some_and(|worker| worker.as_ref().map_or(true, JoinHandle::is_finished))
    }

    fn rearmable_after_shutdown(&self) -> bool {
        self.worker_finished()
            && self.projection.try_read().ok().is_some_and(|projection| {
                projection.phase == SessionPhase::Closed
                    && projection.shutdown_status == "complete"
                    && projection.shutdown_ack_revision > 0
            })
    }

    fn restartable_after_initialization_failure(&self) -> bool {
        self.worker_finished()
            && self.projection.try_read().ok().is_some_and(|projection| {
                projection.phase == SessionPhase::Idle
                    && projection.initialization_status == "error"
            })
    }

    fn join_finished_worker(&self) -> Result<(), String> {
        let mut worker = self
            .worker
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(handle) = worker.take() else {
            return Ok(());
        };
        handle
            .join()
            .map_err(|_| "experiment-session-worker-panicked".to_owned())
    }

    fn shutdown_for_immersive_owner_destroyed(&self) -> Result<(), String> {
        if self.worker_finished() {
            return self.join_finished_worker();
        }
        let (acknowledgement, result) = mpsc::sync_channel(1);
        if self
            .terminal_sender
            .send(WorkerMessage::ImmersiveOwnerDestroyed { acknowledgement })
            .is_err()
        {
            if self.worker_finished() {
                return self.join_finished_worker();
            }
            return Err("experiment-session-owner-destroy-dispatch-failed".to_owned());
        }
        let deadline = Instant::now() + CONTROL_TIMEOUT;
        let worker_result = loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err("experiment-session-owner-destroy-ack-timeout".to_owned());
            }
            match result.recv_timeout(remaining.min(Duration::from_millis(50))) {
                Ok(result) => break result,
                Err(mpsc::RecvTimeoutError::Timeout) if self.worker_finished() => break Ok(()),
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) if self.worker_finished() => break Ok(()),
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    return Err("experiment-session-owner-destroy-ack-disconnected".to_owned())
                }
            }
        };
        let join_result = self.join_finished_worker();
        worker_result.and(join_result)
    }

    pub(crate) fn try_offer(&self, record: SessionRecord) -> OfferOutcome {
        let gate = match self.producer_gate.try_read() {
            Ok(gate) => gate,
            // A write-held gate defines the producer cutoff. This offer is
            // outside the admitted generation rather than a consumed busy
            // record, so callers can safely treat it as not accepting.
            Err(TryLockError::WouldBlock) => return OfferOutcome::NotAccepting,
            Err(TryLockError::Poisoned(_)) => return OfferOutcome::WriterClosed,
        };
        let generation = gate.generation;
        if generation == 0 || !gate.accepting {
            return OfferOutcome::NotAccepting;
        }
        let writer = match self.writer_slot.try_lock() {
            Ok(slot) => slot.as_ref().map(Arc::clone),
            Err(TryLockError::WouldBlock) => None,
            Err(TryLockError::Poisoned(_)) => return OfferOutcome::WriterClosed,
        };
        let Some(writer) = writer else {
            return self.enqueue_retry(generation, record, OfferOutcome::AdmissionBusy);
        };
        let receipt = writer.try_offer(generation, record);
        let outcome = receipt.outcome;
        if matches!(
            outcome,
            OfferOutcome::AdmissionBusy | OfferOutcome::WriterClosed
        ) {
            if let Some(record) = receipt.into_retry_record() {
                return self.enqueue_retry(generation, record, outcome);
            }
        }
        outcome
    }

    fn enqueue_retry(
        &self,
        generation: u64,
        record: SessionRecord,
        busy_outcome: OfferOutcome,
    ) -> OfferOutcome {
        match self
            .retry_sender
            .try_send(RetryOffer { generation, record })
        {
            Ok(()) => busy_outcome,
            Err(TrySendError::Full(retry)) | Err(TrySendError::Disconnected(retry)) => {
                if let Some(recorder) = self.external_loss_slot.get() {
                    let _ = recorder.record(retry.generation, &retry.record);
                }
                record_producer_loss(&self.producer_retry_drops, generation);
                OfferOutcome::QueueFull
            }
        }
    }

    fn current_elapsed_realtime_ns(&self) -> Option<u64> {
        current_from_bridge(&self.monotonic_bridge).map(MonotonicNanos::get)
    }

    fn note_submitted_frame(&self, generation: u64, frame_sequence: u64) -> bool {
        if generation == 0 || self.active_generation.load(Ordering::Acquire) != generation {
            return false;
        }
        let Some(observed_at) = self.current_elapsed_realtime_ns() else {
            return false;
        };
        if self.active_generation.load(Ordering::Acquire) != generation {
            return false;
        }
        let encoded_sequence = frame_sequence.saturating_add(1);
        if self.submitted_frame_generation.load(Ordering::Acquire) != generation {
            self.submitted_frame_sequence
                .store(encoded_sequence, Ordering::Release);
            self.submitted_frame_elapsed_ns
                .store(observed_at, Ordering::Release);
            self.submitted_frame_generation
                .store(generation, Ordering::Release);
            return true;
        }
        let mut previous = self.submitted_frame_sequence.load(Ordering::Acquire);
        loop {
            if previous != 0 && encoded_sequence <= previous {
                return false;
            }
            match self.submitted_frame_sequence.compare_exchange_weak(
                previous,
                encoded_sequence,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => break,
                Err(actual) => previous = actual,
            }
        }
        self.submitted_frame_elapsed_ns
            .store(observed_at, Ordering::Release);
        true
    }

    #[cfg(test)]
    fn shutdown_for_test(&self) -> Result<(), String> {
        if !self.worker_finished() {
            self.command_sender
                .send(WorkerMessage::Shutdown)
                .map_err(|_| "session-worker-closed".to_owned())?;
        }
        self.worker
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take()
            .ok_or_else(|| "session-worker-join-missing".to_owned())?
            .join()
            .map_err(|_| "session-worker-panicked".to_owned())
    }
}

fn run_worker(
    command_receiver: Receiver<WorkerMessage>,
    terminal_receiver: Receiver<WorkerMessage>,
    mut worker: SessionWorker,
) {
    if !worker.initialize() {
        return;
    }
    loop {
        match terminal_receiver.try_recv() {
            Ok(WorkerMessage::Command {
                raw,
                received_at,
                terminal_claim,
            }) => {
                worker.process_raw(raw, received_at);
                if let Some(generation) = terminal_claim {
                    let mut gate = worker
                        .producer_gate
                        .write()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    if gate.generation == generation && gate.terminal_claims > 0 {
                        gate.terminal_claims -= 1;
                        if gate.terminal_claims == 0
                            && worker.controller.phase() == SessionPhase::Active
                            && worker.active_generation.load(Ordering::Acquire) == generation
                        {
                            gate.accepting = gate.resume_accepting;
                        }
                    }
                }
                if worker.stop_after_ack {
                    break;
                }
                continue;
            }
            Ok(WorkerMessage::ImmersiveOwnerDestroyed { acknowledgement }) => {
                let result = worker.process_immersive_owner_destroyed();
                if let Err(error) = result.as_ref() {
                    worker.publish_rejection(None, error);
                }
                let _ = acknowledgement.send(result);
                if worker.stop_after_ack {
                    break;
                }
                continue;
            }
            #[cfg(test)]
            Ok(WorkerMessage::PauseForTest { entered, resume }) => {
                let _ = entered.send(());
                let _ = resume.recv();
                continue;
            }
            _ => {}
        }
        worker.drain_pending_retries();
        match command_receiver.recv_timeout(Duration::from_millis(10)) {
            Ok(WorkerMessage::Command {
                raw, received_at, ..
            }) => {
                worker.process_raw(raw, received_at);
                if worker.stop_after_ack {
                    break;
                }
            }
            Ok(WorkerMessage::Shutdown) => {
                let result = worker.backend.shutdown();
                if let Err(error) = result {
                    worker.publish_rejection(None, &error);
                }
                break;
            }
            Ok(WorkerMessage::ImmersiveOwnerDestroyed { acknowledgement }) => {
                let result = worker.process_immersive_owner_destroyed();
                if let Err(error) = result.as_ref() {
                    worker.publish_rejection(None, error);
                }
                let _ = acknowledgement.send(result);
                if worker.stop_after_ack {
                    break;
                }
            }
            #[cfg(test)]
            Ok(WorkerMessage::PauseForTest { entered, resume }) => {
                let _ = entered.send(());
                let _ = resume.recv();
            }
            Err(mpsc::RecvTimeoutError::Timeout) => worker.process_timed_tick(),
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                let _ = worker.backend.shutdown();
                break;
            }
        }
    }
}

fn current_from_bridge(
    bridge: &Mutex<Option<(MonotonicNanos, Instant)>>,
) -> Option<MonotonicNanos> {
    let bridge = bridge.try_lock().ok()?;
    let (at_command, received_at) = bridge.as_ref()?;
    let elapsed = received_at.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64;
    Some(MonotonicNanos::new(
        at_command.get().saturating_add(elapsed),
    ))
}

#[derive(Default)]
struct RuntimeRegistry {
    slot: RwLock<Option<Arc<ExperimentSessionRuntime>>>,
}

impl RuntimeRegistry {
    fn current(&self) -> Result<Arc<ExperimentSessionRuntime>, &'static str> {
        self.slot
            .try_read()
            .map_err(|_| "experiment-session-runtime-busy")?
            .as_ref()
            .cloned()
            .ok_or("experiment-session-runtime-uninitialized")
    }

    fn initialize(
        &self,
        recording_root: PathBuf,
        trusted_inventory: Option<TrustedExperimentInventory>,
    ) -> Result<(), String> {
        let mut slot = self
            .slot
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some(current) = slot.as_ref() {
            if current.recording_root != recording_root {
                return Err("experiment-session-recording-root-drift".to_owned());
            }
            if !current.worker_finished() {
                // Panel-first and NativeActivity startup are deliberately
                // idempotent while the same async initializer owns recovery.
                return Ok(());
            }
            if !current.rearmable_after_shutdown()
                && !current.restartable_after_initialization_failure()
            {
                return Err("experiment-session-worker-ended-without-shutdown-ack".to_owned());
            }
            current.join_finished_worker()?;
        }
        let runtime_epoch = slot.as_ref().map_or(Ok(1), |current| {
            current
                .runtime_epoch
                .checked_add(1)
                .ok_or_else(|| "experiment-session-runtime-epoch-exhausted".to_owned())
        })?;
        let runtime = Arc::new(ExperimentSessionRuntime::spawn_actual_with_epoch(
            recording_root,
            trusted_inventory,
            runtime_epoch,
        )?);
        *slot = Some(runtime);
        Ok(())
    }

    fn shutdown_for_immersive_owner_destroyed(&self) -> Result<(), String> {
        let current = self
            .slot
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .as_ref()
            .cloned();
        current.map_or(Ok(()), |runtime| {
            runtime.shutdown_for_immersive_owner_destroyed()
        })
    }
}

static RUNTIME: OnceLock<RuntimeRegistry> = OnceLock::new();

fn registry() -> &'static RuntimeRegistry {
    RUNTIME.get_or_init(RuntimeRegistry::default)
}

fn runtime() -> Result<Arc<ExperimentSessionRuntime>, &'static str> {
    registry().current()
}

pub(crate) fn shutdown_for_immersive_owner_destroyed() -> Result<(), String> {
    registry().shutdown_for_immersive_owner_destroyed()
}

fn load_compiled_experiment_inventory(
    trusted_files_root: &Path,
    anchors: CompiledExperimentAnchors<'_>,
) -> Result<Option<TrustedExperimentInventory>, String> {
    let (profile_sha256, provider_manifest_sha256, provider_inventory_sha256) = match (
        anchors.profile_sha256,
        anchors.provider_manifest_sha256,
        anchors.provider_inventory_sha256,
    ) {
        (None, None, None) => return Ok(None),
        (Some(profile), Some(manifest), Some(inventory)) => (profile, manifest, inventory),
        _ => return Err("experiment-session-compiled-anchors-partial".to_owned()),
    };
    if !valid_sha256(profile_sha256)
        || !valid_sha256(provider_manifest_sha256)
        || !valid_sha256(provider_inventory_sha256)
    {
        return Err("experiment-session-compiled-anchor-invalid".to_owned());
    }

    let path = trusted_files_root
        .join("viscereality-recordings")
        .join(EXPERIMENT_SESSION_PROFILE_FILE);
    let bytes =
        fs::read(&path).map_err(|error| format!("experiment-session-profile-read:{error}"))?;
    let text = std::str::from_utf8(&bytes)
        .map_err(|_| "experiment-session-profile-utf8-invalid".to_owned())?;
    if text.starts_with('\u{feff}') {
        return Err("experiment-session-profile-bom-forbidden".to_owned());
    }
    if rusty_quest_broker_authority::packaged_json_sha256(text) != profile_sha256 {
        return Err("experiment-session-profile-digest-mismatch".to_owned());
    }
    let document: Value = serde_json::from_str(text)
        .map_err(|error| format!("experiment-session-profile-json-invalid:{error}"))?;
    let document_object = document
        .as_object()
        .ok_or_else(|| "experiment-session-profile-document-invalid".to_owned())?;
    let non_audio_profile_sha256 = document_object
        .get("non_audio_profile_sha256")
        .and_then(Value::as_str)
        .filter(|value| valid_sha256(value))
        .ok_or_else(|| "experiment-session-non-audio-profile-sha256-invalid".to_owned())?
        .to_owned();
    let projection = document_object
        .get("runtime_projection")
        .ok_or_else(|| "experiment-session-runtime-projection-missing".to_owned())?;
    let object = projection
        .as_object()
        .ok_or_else(|| "experiment-session-runtime-projection-invalid".to_owned())?;
    require_exact_fields(
        object,
        &[
            "schema",
            "provider_id",
            "effective_radius_profile",
            "conditions",
        ],
    )
    .map_err(|_| "experiment-session-runtime-projection-fields-invalid".to_owned())?;
    if object.get("schema").and_then(Value::as_str)
        != Some(EXPERIMENT_SESSION_RUNTIME_PROJECTION_SCHEMA)
    {
        return Err("experiment-session-runtime-projection-schema-invalid".to_owned());
    }
    let provider_id = object
        .get("provider_id")
        .and_then(Value::as_str)
        .filter(|value| valid_token(value, 256))
        .ok_or_else(|| "experiment-session-runtime-provider-id-invalid".to_owned())?
        .to_owned();
    let effective_radius_profile = EffectiveRadiusProfile::parse_encoded(
        object
            .get("effective_radius_profile")
            .ok_or_else(|| "experiment-session-runtime-radius-profile-missing".to_owned())?,
    )
    .map_err(str::to_owned)?;
    let conditions = object
        .get("conditions")
        .and_then(Value::as_object)
        .ok_or_else(|| "experiment-session-runtime-conditions-invalid".to_owned())?;
    require_exact_fields(conditions, &["condition-a", "condition-b"])
        .map_err(|_| "experiment-session-runtime-conditions-invalid".to_owned())?;
    let parse_condition = |name: &'static str| -> Result<TrustedConditionInventory, String> {
        let condition = ConditionKey::parse(name).map_err(str::to_owned)?;
        let value = conditions
            .get(name)
            .and_then(Value::as_object)
            .ok_or_else(|| "experiment-session-runtime-condition-invalid".to_owned())?;
        require_exact_fields(value, &["completion_threshold_ms", "audio"])
            .map_err(|_| "experiment-session-runtime-condition-fields-invalid".to_owned())?;
        let completion_threshold_ms = value
            .get("completion_threshold_ms")
            .and_then(Value::as_u64)
            .ok_or_else(|| "experiment-session-runtime-threshold-invalid".to_owned())?;
        let audio = value
            .get("audio")
            .and_then(Value::as_object)
            .ok_or_else(|| "experiment-session-runtime-audio-invalid".to_owned())?;
        Ok(TrustedConditionInventory {
            condition,
            completion_threshold_ms,
            audio: parse_audio_identity(audio).map_err(str::to_owned)?,
        })
    };
    let inventory = TrustedExperimentInventory {
        provider_id,
        provider_manifest_sha256: provider_manifest_sha256.to_owned(),
        provider_inventory_sha256: provider_inventory_sha256.to_owned(),
        non_audio_profile_sha256,
        effective_radius_profile,
        conditions: [
            parse_condition("condition-a")?,
            parse_condition("condition-b")?,
        ],
    };
    inventory.validate().map_err(str::to_owned)?;
    Ok(Some(inventory))
}

fn initialize_with_compiled_experiment_inventory(
    trusted_files_root: PathBuf,
) -> Result<(), String> {
    let trusted_inventory = load_compiled_experiment_inventory(
        &trusted_files_root,
        CompiledExperimentAnchors::production(),
    )?;
    initialize_app_lifetime(trusted_files_root, trusted_inventory)
}

pub(crate) fn initialize_app_lifetime(
    trusted_files_root: PathBuf,
    trusted_inventory: Option<TrustedExperimentInventory>,
) -> Result<(), String> {
    if !trusted_files_root.is_absolute()
        || trusted_files_root
            .components()
            .any(|component| matches!(component, Component::ParentDir))
        || !trusted_android_files_root(&trusted_files_root)
    {
        return Err("android-files-root-contract-invalid".to_owned());
    }
    let recording_root = trusted_files_root.join("viscereality-recordings");
    if !recording_root.is_absolute()
        || recording_root.file_name().and_then(|value| value.to_str())
            != Some("viscereality-recordings")
    {
        return Err("recording-root-contract-invalid".to_owned());
    }
    registry().initialize(recording_root, trusted_inventory)
}

#[cfg(target_os = "android")]
fn trusted_android_files_root(path: &Path) -> bool {
    let value = path.to_string_lossy();
    (value.starts_with("/data/user/") || value.starts_with("/data/data/"))
        && path.file_name().and_then(|name| name.to_str()) == Some("files")
}

#[cfg(not(target_os = "android"))]
fn trusted_android_files_root(_path: &Path) -> bool {
    true
}

#[cfg(target_os = "android")]
pub(crate) fn initialize_from_android_app(
    app: &android_activity::AndroidApp,
) -> Result<(), String> {
    let files_root = app
        .internal_data_path()
        .ok_or_else(|| "android-internal-data-path-unavailable".to_owned())?;
    initialize_with_compiled_experiment_inventory(files_root)
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

/// Panel-first app-lifetime initialization. The caller supplies only Android's
/// app-private files directory. Inventory admission is possible only through
/// the compile-time digest anchors plus the exact pre-materialized profile;
/// no JNI or command argument can install or replace the inventory.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ControlPanelActivity_nativeInitializeExperimentSessionRuntime(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    files_root: jni::objects::JString,
) -> jni::sys::jstring {
    let response = match env
        .with_env(|env| files_root.try_to_string(env))
        .into_outcome()
    {
        jni::Outcome::Ok(files_root) => {
            match initialize_with_compiled_experiment_inventory(PathBuf::from(files_root)) {
                Ok(()) => status_json(),
                Err(error) => startup_failure_json(&error),
            }
        }
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => {
            startup_failure_json("android-files-root-jni-invalid")
        }
    };
    jni_response(env, response)
}

pub(crate) fn apply_command_json(raw: &str) -> String {
    runtime().map_or_else(
        |error| startup_failure_json(error),
        |runtime| runtime.apply_command_json(raw),
    )
}

pub(crate) fn status_json() -> String {
    runtime().map_or_else(startup_failure_json, |runtime| runtime.status_json())
}

pub(crate) fn try_offer_record(record: SessionRecord) -> OfferOutcome {
    runtime().map_or(OfferOutcome::NotAccepting, |runtime| {
        runtime.try_offer(record)
    })
}

pub(crate) fn request_restart_from_native(
    operation_id: &str,
    expected_generation: u64,
    elapsed_realtime_ns: u64,
) -> String {
    apply_command_json(
        &json!({
            "schema": EXPERIMENT_SESSION_COMMAND_SCHEMA,
            "operation": "restart-to-experimenter",
            "operation_id": operation_id,
            "expected_generation": expected_generation,
            "elapsed_realtime_ns": elapsed_realtime_ns,
        })
        .to_string(),
    )
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NativeControlAdmission {
    Queued,
    Retryable,
    RuntimeUnavailable,
}

impl NativeControlAdmission {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Retryable => "retryable",
            Self::RuntimeUnavailable => "runtime-unavailable",
        }
    }
}

pub(crate) fn request_control_from_native(
    operation: &str,
    operation_id: &str,
    expected_generation: u64,
    elapsed_realtime_ns: u64,
) -> NativeControlAdmission {
    let Ok(runtime) = runtime() else {
        return NativeControlAdmission::RuntimeUnavailable;
    };
    let raw = json!({
        "schema": EXPERIMENT_SESSION_COMMAND_SCHEMA,
        "operation": operation,
        "operation_id": operation_id,
        "expected_generation": expected_generation,
        "elapsed_realtime_ns": elapsed_realtime_ns,
    })
    .to_string();
    if raw.len() > MAX_COMMAND_BYTES {
        return NativeControlAdmission::RuntimeUnavailable;
    }
    match runtime.command_sender.try_send(WorkerMessage::Command {
        raw,
        received_at: Instant::now(),
        terminal_claim: None,
    }) {
        Ok(()) => NativeControlAdmission::Queued,
        Err(TrySendError::Full(_)) => NativeControlAdmission::Retryable,
        Err(TrySendError::Disconnected(_)) => NativeControlAdmission::RuntimeUnavailable,
    }
}

pub(crate) fn current_control_receipt() -> Option<(
    crate::experiment_session::ExperimentControlState,
    u64,
    u64,
    &'static str,
)> {
    let runtime = runtime().ok()?;
    let projection = runtime.projection.try_read().ok()?;
    Some((
        projection.control_state,
        projection.control_receipt_generation,
        projection.control_receipt_revision,
        projection.control_event,
    ))
}

pub(crate) fn request_open_developer_from_native(
    operation_id: &str,
    expected_generation: u64,
    elapsed_realtime_ns: u64,
) -> String {
    apply_command_json(
        &json!({
            "schema": EXPERIMENT_SESSION_COMMAND_SCHEMA,
            "operation": "open-developer",
            "operation_id": operation_id,
            "expected_generation": expected_generation,
            "elapsed_realtime_ns": elapsed_realtime_ns,
        })
        .to_string(),
    )
}

pub(crate) fn request_presentation_from_native(
    operation_id: &str,
    expected_generation: u64,
    elapsed_realtime_ns: u64,
    presentation: PresentationState,
) -> String {
    apply_command_json(
        &json!({
            "schema": EXPERIMENT_SESSION_COMMAND_SCHEMA,
            "operation": "presentation",
            "operation_id": operation_id,
            "expected_generation": expected_generation,
            "elapsed_realtime_ns": elapsed_realtime_ns,
            "presentation": presentation_token(presentation),
        })
        .to_string(),
    )
}

pub(crate) fn current_route_action() -> (RouteAction, u64) {
    let Ok(runtime) = runtime() else {
        return (RouteAction::None, 0);
    };
    let packed = runtime.route_state.load(Ordering::Acquire);
    (RouteAction::from_code(packed & 0xff), packed >> 8)
}

pub(crate) fn current_terminal_route_receipt() -> Option<(u64, String)> {
    let runtime = runtime().ok()?;
    let projection = runtime.projection.try_read().ok()?;
    Some((
        projection.finalized_session_generation,
        projection.finalized_operation_id.clone()?,
    ))
}

pub(crate) fn current_generation() -> u64 {
    runtime().map_or(0, |runtime| {
        runtime.active_generation.load(Ordering::Acquire)
    })
}

pub(crate) fn current_presentation() -> PresentationState {
    let Ok(runtime) = runtime() else {
        return PresentationState::Experimenter;
    };
    presentation_from_code(runtime.presentation_state.load(Ordering::Acquire))
}

pub(crate) fn current_elapsed_realtime_ns() -> Option<u64> {
    runtime()
        .ok()
        .and_then(|runtime| runtime.current_elapsed_realtime_ns())
}

pub(crate) fn note_current_session_submitted_frame(generation: u64, frame_sequence: u64) -> bool {
    runtime()
        .ok()
        .is_some_and(|runtime| runtime.note_submitted_frame(generation, frame_sequence))
}

fn startup_failure_json(error: &str) -> String {
    let reason = if error == "experiment-session-runtime-uninitialized" {
        "runtime-uninitialized"
    } else {
        "session-worker-start-failed"
    };
    format!(
        "{{\"schema\":\"{EXPERIMENT_SESSION_RESPONSE_SCHEMA}\",\"command_status\":\"rejected\",\"reason_code\":\"{reason}\",\"readback\":{{\"initialization_status\":\"error\",\"inventory_status\":\"inventory-unavailable\",\"shutdown_status\":\"not-requested\"}}}}"
    )
}

pub(crate) fn record_polar_acc_sample(
    frame_sequence: u64,
    sample_index: u32,
    host_time_ns: u64,
    sensor_time_ns: u64,
    xyz_mg: [f32; 3],
) -> OfferOutcome {
    let Some(observed_at_ns) = current_elapsed_realtime_ns() else {
        return OfferOutcome::NotAccepting;
    };
    try_offer_record(SessionRecord::PolarAcc(PolarAccBatch {
        frame_sequence,
        samples: vec![PolarAccSample {
            source_sequence: frame_sequence,
            sample_index,
            source_time: SourceTimestamp {
                clock: SourceClock::PolarSensor,
                value_ns: sensor_time_ns,
            },
            received_source: SourceTimestamp {
                clock: SourceClock::JavaNanoTime,
                value_ns: host_time_ns,
            },
            observed_at: MonotonicNanos::new(observed_at_ns),
            xyz_mg,
        }]
        .into_boxed_slice(),
    }))
}

pub(crate) fn record_polar_ecg_sample(
    frame_sequence: u64,
    sample_index: u32,
    host_time_ns: u64,
    sensor_time_ns: u64,
    microvolts: i32,
) -> OfferOutcome {
    let Some(observed_at_ns) = current_elapsed_realtime_ns() else {
        return OfferOutcome::NotAccepting;
    };
    try_offer_record(SessionRecord::PolarEcg(PolarEcgBatch {
        frame_sequence,
        samples: vec![PolarEcgSample {
            source_sequence: frame_sequence,
            sample_index,
            source_time: SourceTimestamp {
                clock: SourceClock::PolarSensor,
                value_ns: sensor_time_ns,
            },
            received_source: SourceTimestamp {
                clock: SourceClock::JavaNanoTime,
                value_ns: host_time_ns,
            },
            observed_at: MonotonicNanos::new(observed_at_ns),
            microvolts,
        }]
        .into_boxed_slice(),
    }))
}

pub(crate) fn record_polar_heart_rate(
    source_sequence: u64,
    host_time_ns: u64,
    bpm: u16,
    rr_interval_ms: Option<f32>,
) -> OfferOutcome {
    let Some(observed_at_ns) = current_elapsed_realtime_ns() else {
        return OfferOutcome::NotAccepting;
    };
    try_offer_record(SessionRecord::PolarHeartRate(PolarHeartRateObservation {
        source_sequence,
        source_time: SourceTimestamp {
            clock: SourceClock::JavaNanoTime,
            value_ns: host_time_ns,
        },
        observed_at: MonotonicNanos::new(observed_at_ns),
        bpm,
        rr_interval_ms,
    }))
}

pub(crate) fn record_breath_assessment(
    source_sequence: u64,
    sampled_at_micros: u64,
    sampled_at_clock: SourceClock,
    observed_at_micros: u64,
    observed_at_clock: SourceClock,
    phase: BreathPhase,
    volume01: Option<f32>,
    quality01: f32,
    settings_revision: u64,
) -> OfferOutcome {
    let Some(mapped_observed_at_ns) = current_elapsed_realtime_ns() else {
        return OfferOutcome::NotAccepting;
    };
    try_offer_record(SessionRecord::Breath(BreathObservation {
        source_sequence,
        sampled_at: SourceTimestamp {
            clock: sampled_at_clock,
            value_ns: sampled_at_micros.saturating_mul(1_000),
        },
        observed_source: SourceTimestamp {
            clock: observed_at_clock,
            value_ns: observed_at_micros.saturating_mul(1_000),
        },
        observed_at: MonotonicNanos::new(mapped_observed_at_ns),
        phase,
        volume01,
        quality01,
        settings_revision,
    }))
}

pub(crate) fn record_effective_radius_snapshot(
    source_frame: u64,
    observed_at_ns: u64,
    radius_progress01: f32,
    resulting_radius_m: f32,
    deformation_progress01: f32,
    settings_revision: u64,
    render_session_generation: u64,
    resulting_radius_parameter_source: &'static str,
) -> OfferOutcome {
    let Ok(runtime) = runtime() else {
        return OfferOutcome::NotAccepting;
    };
    let profile = match runtime.effective_radius_profile.try_read() {
        Ok(profile) => *profile,
        Err(_) => return OfferOutcome::AdmissionBusy,
    };
    let Some(profile) = profile else {
        return OfferOutcome::NotAccepting;
    };
    runtime.try_offer(SessionRecord::EffectiveRadiusSnapshot(
        EffectiveRadiusSnapshotObservation {
            source_frame,
            observed_at: MonotonicNanos::new(observed_at_ns),
            configured_radius_min_m: profile.configured_radius_min_m,
            configured_radius_max_m: profile.configured_radius_max_m,
            radius_progress01,
            resulting_radius_m,
            deformation_progress01,
            oblateness: profile.oblateness,
            axis_profile: profile.axis_profile,
            settings_revision,
            render_session_generation,
            resulting_radius_parameter_source,
        },
    ))
}

fn parse_command_value(raw: &str) -> Result<Value, &'static str> {
    if raw.len() > MAX_COMMAND_BYTES {
        return Err("command-too-large");
    }
    let value: Value = serde_json::from_str(raw).map_err(|_| "malformed-json")?;
    let object = value.as_object().ok_or("command-not-object")?;
    if object.get("schema").and_then(Value::as_str) != Some(EXPERIMENT_SESSION_COMMAND_SCHEMA) {
        return Err("unsupported-schema");
    }
    Ok(value)
}

fn parse_mutating_command(
    value: Value,
    received_at: Instant,
) -> Result<ParsedCommand, (Option<String>, &'static str)> {
    let object = value.as_object().ok_or((None, "command-not-object"))?;
    let operation_id = object
        .get("operation_id")
        .and_then(Value::as_str)
        .filter(|value| valid_token(value, 96))
        .map(str::to_owned)
        .ok_or((None, "operation-id-invalid"))?;
    let failure = |reason| (Some(operation_id.clone()), reason);
    let expected_generation = object
        .get("expected_generation")
        .and_then(Value::as_u64)
        .ok_or_else(|| failure("expected-generation-invalid"))?;
    let at = object
        .get("elapsed_realtime_ns")
        .and_then(Value::as_u64)
        .filter(|value| *value > 0)
        .map(MonotonicNanos::new)
        .ok_or_else(|| failure("elapsed-realtime-invalid"))?;
    let operation_name = object
        .get("operation")
        .and_then(Value::as_str)
        .ok_or_else(|| failure("operation-invalid"))?;
    let operation = match operation_name {
        "start" | "arm" => {
            if !object.contains_key("non_audio_profile_sha256") {
                return Err(failure("profile-identity-missing"));
            }
            if !object.contains_key("audio") {
                return Err(failure("audio-identity-missing"));
            }
            require_exact_fields(
                object,
                &[
                    "schema",
                    "operation",
                    "operation_id",
                    "expected_generation",
                    "condition",
                    "completion_threshold_ms",
                    "started_at_utc_ns",
                    "elapsed_realtime_ns",
                    "non_audio_profile_sha256",
                    "audio",
                ],
            )
            .map_err(|reason| failure(reason))?;
            let condition_value = object
                .get("condition")
                .and_then(Value::as_str)
                .ok_or_else(|| failure("condition-invalid"))?;
            if !matches!(condition_value, "condition-a" | "condition-b") {
                return Err(failure("condition-invalid"));
            }
            let condition =
                ConditionKey::parse(condition_value).map_err(|_| failure("condition-invalid"))?;
            let threshold_ms = object
                .get("completion_threshold_ms")
                .and_then(Value::as_u64)
                .filter(|value| *value == DEFAULT_COMPLETION_THRESHOLD_NS / 1_000_000)
                .ok_or_else(|| failure("completion-threshold-invalid"))?;
            let utc_ns = object
                .get("started_at_utc_ns")
                .and_then(Value::as_u64)
                .filter(|value| *value > 0)
                .ok_or_else(|| failure("started-at-utc-invalid"))?;
            let profile_sha256 = object
                .get("non_audio_profile_sha256")
                .and_then(Value::as_str)
                .filter(|value| valid_sha256(value))
                .map(str::to_owned)
                .ok_or_else(|| failure("profile-identity-missing"))?;
            let audio = parse_audio_identity(
                object
                    .get("audio")
                    .and_then(Value::as_object)
                    .ok_or_else(|| failure("audio-identity-missing"))?,
            )
            .map_err(|reason| failure(reason))?;
            ParsedOperation::Start {
                arm: operation_name == "arm",
                condition,
                completion_threshold_ns: threshold_ms.saturating_mul(1_000_000),
                utc_ns,
                identity: StartIdentity {
                    profile_sha256,
                    audio,
                },
            }
        }
        "presentation" => {
            require_common_fields(object, &["presentation"]).map_err(|reason| failure(reason))?;
            ParsedOperation::Presentation(
                parse_presentation(
                    object
                        .get("presentation")
                        .and_then(Value::as_str)
                        .ok_or_else(|| failure("presentation-invalid"))?,
                )
                .ok_or_else(|| failure("presentation-invalid"))?,
            )
        }
        "open-developer" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::Presentation(PresentationState::Developer)
        }
        "return-immersive" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::Presentation(PresentationState::ImmersiveActive)
        }
        "tick" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::Tick
        }
        "audio-prepared" | "official-start" | "pause" | "resume" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            match operation_name {
                "audio-prepared" => ParsedOperation::AudioPrepared,
                "official-start" => ParsedOperation::OfficialStart,
                "pause" => ParsedOperation::Pause,
                _ => ParsedOperation::Resume,
            }
        }
        "audio-started" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::AudioStarted
        }
        "audio-ended" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::AudioEnded
        }
        "audio-error" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::AudioError
        }
        "restart-to-experimenter" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::RestartToExperimenter
        }
        "save-and-exit" => {
            require_common_fields(object, &[]).map_err(|reason| failure(reason))?;
            ParsedOperation::SaveAndExit
        }
        _ => return Err(failure("unsupported-operation")),
    };
    Ok(ParsedCommand {
        operation_id,
        expected_generation,
        at,
        value,
        operation,
        received_at,
    })
}

fn is_status_command(raw: &str) -> bool {
    let Ok(value) = parse_command_value(raw) else {
        return false;
    };
    let Some(object) = value.as_object() else {
        return false;
    };
    object.len() == 2 && object.get("operation").and_then(Value::as_str) == Some("status")
}

fn terminal_command_generation(raw: &str) -> Option<u64> {
    // A partial operation/generation match is not permission to interrupt sampling.
    // State, operation-history and monotonic validation remain serial actor work.
    let parsed = parse_mutating_command(parse_command_value(raw).ok()?, Instant::now()).ok()?;
    matches!(
        parsed.operation,
        ParsedOperation::RestartToExperimenter | ParsedOperation::SaveAndExit
    )
    .then_some(parsed.expected_generation)
}

fn parse_audio_identity(object: &Map<String, Value>) -> Result<AudioAssetIdentity, &'static str> {
    require_exact_fields(
        object,
        &[
            "logical_destination",
            "source_sha256",
            "source_bytes",
            "media_type",
        ],
    )?;
    let logical_destination = object
        .get("logical_destination")
        .and_then(Value::as_str)
        .filter(|value| valid_relative_identity_path(value))
        .ok_or("audio-destination-invalid")?
        .to_owned();
    let source_sha256 = object
        .get("source_sha256")
        .and_then(Value::as_str)
        .filter(|value| valid_sha256(value))
        .ok_or("audio-sha256-invalid")?
        .to_owned();
    let source_bytes = object
        .get("source_bytes")
        .and_then(Value::as_u64)
        .filter(|value| *value > 0)
        .ok_or("audio-bytes-invalid")?;
    let media_type = object
        .get("media_type")
        .and_then(Value::as_str)
        .filter(|value| valid_token(value, 64) && value.starts_with("audio/"))
        .ok_or("audio-media-type-invalid")?
        .to_owned();
    Ok(AudioAssetIdentity {
        logical_destination,
        source_sha256,
        source_bytes,
        media_type,
    })
}

fn require_common_fields(object: &Map<String, Value>, extras: &[&str]) -> Result<(), &'static str> {
    let mut fields = vec![
        "schema",
        "operation",
        "operation_id",
        "expected_generation",
        "elapsed_realtime_ns",
    ];
    fields.extend_from_slice(extras);
    require_exact_fields(object, &fields)
}

fn require_exact_fields(object: &Map<String, Value>, fields: &[&str]) -> Result<(), &'static str> {
    if object.len() != fields.len()
        || object
            .keys()
            .any(|candidate| !fields.contains(&candidate.as_str()))
    {
        Err("unexpected-field")
    } else {
        Ok(())
    }
}

fn valid_token(value: &str, maximum: usize) -> bool {
    !value.is_empty()
        && value.len() <= maximum
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'/'))
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 64
        && !value.bytes().all(|byte| byte == b'0')
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn valid_relative_identity_path(value: &str) -> bool {
    let path = Path::new(value);
    !value.is_empty()
        && value.len() <= 256
        && !path.is_absolute()
        && path
            .components()
            .all(|component| matches!(component, Component::Normal(_)))
}

fn parse_presentation(value: &str) -> Option<PresentationState> {
    match value {
        "experimenter" => Some(PresentationState::Experimenter),
        "transition" => Some(PresentationState::Transition),
        "immersive-active" => Some(PresentationState::ImmersiveActive),
        "developer" => Some(PresentationState::Developer),
        "unfocused" => Some(PresentationState::Unfocused),
        _ => None,
    }
}

fn accepted_offer(receipt: OfferReceipt) -> Result<(), String> {
    match receipt.outcome {
        OfferOutcome::Accepted { .. } => Ok(()),
        outcome => Err(format!("session-record-offer:{outcome:?}")),
    }
}

fn encode_producer_loss(generation: u64, count: u64) -> u64 {
    ((generation & PRODUCER_LOSS_COUNT_MASK) << PRODUCER_LOSS_COUNT_BITS)
        | count.min(PRODUCER_LOSS_COUNT_MASK)
}

fn reset_producer_loss(state: &AtomicU64, generation: u64) {
    state.store(encode_producer_loss(generation, 0), Ordering::Release);
}

fn producer_loss_count(state: &AtomicU64, generation: u64) -> u64 {
    let observed = state.load(Ordering::Acquire);
    let observed_generation = observed >> PRODUCER_LOSS_COUNT_BITS;
    if observed_generation == (generation & PRODUCER_LOSS_COUNT_MASK) {
        observed & PRODUCER_LOSS_COUNT_MASK
    } else {
        0
    }
}

fn record_producer_loss(state: &AtomicU64, generation: u64) {
    let expected_generation = generation & PRODUCER_LOSS_COUNT_MASK;
    let mut current = state.load(Ordering::Acquire);
    loop {
        if current >> PRODUCER_LOSS_COUNT_BITS != expected_generation {
            return;
        }
        let count = current & PRODUCER_LOSS_COUNT_MASK;
        let next = encode_producer_loss(generation, count.saturating_add(1));
        match state.compare_exchange_weak(current, next, Ordering::AcqRel, Ordering::Acquire) {
            Ok(_) => return,
            Err(observed) => current = observed,
        }
    }
}

fn presentation_event(previous: PresentationState, current: PresentationState) -> SessionEventKind {
    match current {
        PresentationState::ImmersiveActive if previous == PresentationState::Developer => {
            SessionEventKind::DeveloperClosed
        }
        PresentationState::ImmersiveActive => SessionEventKind::ImmersiveActive,
        PresentationState::Developer => SessionEventKind::DeveloperOpened,
        PresentationState::Experimenter
        | PresentationState::Transition
        | PresentationState::Unfocused => SessionEventKind::ImmersivePaused,
    }
}

fn phase_token(value: SessionPhase) -> &'static str {
    match value {
        SessionPhase::Idle => "idle",
        SessionPhase::Preparing => "preparing",
        SessionPhase::Active => "active",
        SessionPhase::FinalizingRestart => "finalizing-restart",
        SessionPhase::Exiting => "exiting",
        SessionPhase::Closed => "closed",
    }
}

fn presentation_token(value: PresentationState) -> &'static str {
    match value {
        PresentationState::Experimenter => "experimenter",
        PresentationState::Transition => "transition",
        PresentationState::ImmersiveActive => "immersive-active",
        PresentationState::Developer => "developer",
        PresentationState::Unfocused => "unfocused",
    }
}

const fn presentation_code(value: PresentationState) -> u64 {
    match value {
        PresentationState::Experimenter => 0,
        PresentationState::Transition => 1,
        PresentationState::ImmersiveActive => 2,
        PresentationState::Developer => 3,
        PresentationState::Unfocused => 4,
    }
}

const fn presentation_from_code(value: u64) -> PresentationState {
    match value {
        1 => PresentationState::Transition,
        2 => PresentationState::ImmersiveActive,
        3 => PresentationState::Developer,
        4 => PresentationState::Unfocused,
        _ => PresentationState::Experimenter,
    }
}

fn completion_token(value: CompletionProgress) -> &'static str {
    match value {
        CompletionProgress::NotReached => "not-reached",
        CompletionProgress::PersistencePending => "persistence-pending",
        CompletionProgress::Durable => "durable",
    }
}

fn recording_result_value(value: RecordingResult) -> Value {
    match value {
        RecordingResult::None => json!({"status":"none"}),
        RecordingResult::Saved { completed } => {
            json!({"status":"saved", "completed":completed})
        }
        RecordingResult::Unsaved { completed, error } => json!({
            "status":"unsaved",
            "completed":completed,
            "error":format!("{error:?}")
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        sync::atomic::{AtomicBool, AtomicUsize, Ordering},
        time::{SystemTime, UNIX_EPOCH},
    };

    static NEXT: AtomicUsize = AtomicUsize::new(0);

    fn temp_root(label: &str) -> PathBuf {
        std::env::temp_dir()
            .join(format!(
                "rusty-quest-session-runtime-{label}-{}-{}",
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ))
            .join("viscereality-recordings")
    }

    fn trusted_inventory() -> TrustedExperimentInventory {
        TrustedExperimentInventory {
            provider_id: "private-provider".to_owned(),
            provider_manifest_sha256: "33".repeat(32),
            provider_inventory_sha256: "44".repeat(32),
            non_audio_profile_sha256: "11".repeat(32),
            effective_radius_profile: EffectiveRadiusProfile {
                configured_radius_min_m: 0.8,
                configured_radius_max_m: 1.6,
                oblateness: Some(
                    crate::session_recording_contract::DeformationEnvelopeEndpoints {
                        at_radius_min: 0.2,
                        at_radius_max: 0.4,
                    },
                ),
                axis_profile: Some(
                    crate::session_recording_contract::DeformationEnvelopeEndpoints {
                        at_radius_min: 1.8,
                        at_radius_max: 0.9,
                    },
                ),
            },
            conditions: [
                TrustedConditionInventory {
                    condition: ConditionKey::parse("condition-a").unwrap(),
                    completion_threshold_ms: 30_000,
                    audio: AudioAssetIdentity {
                        logical_destination: "audio/condition-a.wav".to_owned(),
                        source_sha256: "22".repeat(32),
                        source_bytes: 1024,
                        media_type: "audio/wav".to_owned(),
                    },
                },
                TrustedConditionInventory {
                    condition: ConditionKey::parse("condition-b").unwrap(),
                    completion_threshold_ms: 30_000,
                    audio: AudioAssetIdentity {
                        logical_destination: "audio/condition-b.wav".to_owned(),
                        source_sha256: "55".repeat(32),
                        source_bytes: 2048,
                        media_type: "audio/wav".to_owned(),
                    },
                },
            ],
        }
    }

    fn compiled_profile_document() -> String {
        json!({
            "schema_id": "private-outer-profile-v1",
            "private_metadata": {"owned_by": "private-provider"},
            "non_audio_profile_sha256": "66".repeat(32),
            "runtime_projection": {
                "schema": EXPERIMENT_SESSION_RUNTIME_PROJECTION_SCHEMA,
                "provider_id": "private-provider",
                "effective_radius_profile": {
                    "configured_radius_min_m": 0.8,
                    "configured_radius_max_m": 1.6,
                    "oblateness": {"at_radius_min": 0.2, "at_radius_max": 0.4},
                    "axis_profile": {"at_radius_min": 1.8, "at_radius_max": 0.9}
                },
                "conditions": {
                    "condition-a": {
                        "completion_threshold_ms": 30_000,
                        "audio": {
                            "logical_destination": "audio/condition-a.wav",
                            "source_sha256": "22".repeat(32),
                            "source_bytes": 1024,
                            "media_type": "audio/wav"
                        }
                    },
                    "condition-b": {
                        "completion_threshold_ms": 30_000,
                        "audio": {
                            "logical_destination": "audio/condition-b.wav",
                            "source_sha256": "55".repeat(32),
                            "source_bytes": 2048,
                            "media_type": "audio/wav"
                        }
                    }
                }
            }
        })
        .to_string()
    }

    fn materialize_compiled_profile(files_root: &Path, text: &str) {
        let recording_root = files_root.join("viscereality-recordings");
        fs::create_dir_all(&recording_root).unwrap();
        fs::write(recording_root.join(EXPERIMENT_SESSION_PROFILE_FILE), text).unwrap();
    }

    fn compiled_anchors<'a>(profile_sha256: &'a str) -> CompiledExperimentAnchors<'a> {
        CompiledExperimentAnchors {
            profile_sha256: Some(profile_sha256),
            provider_manifest_sha256: Some(
                "3333333333333333333333333333333333333333333333333333333333333333",
            ),
            provider_inventory_sha256: Some(
                "4444444444444444444444444444444444444444444444444444444444444444",
            ),
        }
    }

    fn runtime(root: &Path) -> ExperimentSessionRuntime {
        ExperimentSessionRuntime::spawn_actual(root.to_owned(), Some(trusted_inventory())).unwrap()
    }

    #[test]
    fn compiled_inventory_absent_anchors_is_unavailable_without_file_access() {
        let files_root = temp_root("compiled-anchors-absent")
            .parent()
            .unwrap()
            .to_owned();
        assert_eq!(
            load_compiled_experiment_inventory(&files_root, CompiledExperimentAnchors::default())
                .unwrap(),
            None
        );
    }

    #[test]
    fn compiled_inventory_partial_or_malformed_hashes_fail_closed() {
        let files_root = temp_root("compiled-anchors-invalid")
            .parent()
            .unwrap()
            .to_owned();
        let partial = CompiledExperimentAnchors {
            profile_sha256: Some("11"),
            ..CompiledExperimentAnchors::default()
        };
        assert!(load_compiled_experiment_inventory(&files_root, partial)
            .unwrap_err()
            .contains("anchors-partial"));
        let malformed = compiled_anchors("not-a-sha256");
        assert!(load_compiled_experiment_inventory(&files_root, malformed)
            .unwrap_err()
            .contains("anchor-invalid"));
    }

    #[test]
    fn compiled_inventory_missing_materialized_file_fails_closed() {
        let files_root = temp_root("compiled-profile-missing")
            .parent()
            .unwrap()
            .to_owned();
        assert!(load_compiled_experiment_inventory(
            &files_root,
            compiled_anchors(&"11".repeat(32))
        )
        .unwrap_err()
        .contains("profile-read"));
    }

    #[test]
    fn compiled_inventory_rejects_writable_file_tamper() {
        let recording_root = temp_root("compiled-profile-tamper");
        let files_root = recording_root.parent().unwrap();
        let original = compiled_profile_document();
        let digest = rusty_quest_broker_authority::packaged_json_sha256(&original);
        materialize_compiled_profile(files_root, &(original + " "));
        assert!(
            load_compiled_experiment_inventory(files_root, compiled_anchors(&digest))
                .unwrap_err()
                .contains("digest-mismatch")
        );
        fs::remove_dir_all(files_root).unwrap();
    }

    #[test]
    fn compiled_inventory_rejects_wrong_runtime_projection_schema() {
        let recording_root = temp_root("compiled-profile-schema");
        let files_root = recording_root.parent().unwrap();
        let mut document: Value = serde_json::from_str(&compiled_profile_document()).unwrap();
        document["runtime_projection"]["schema"] = json!("wrong-schema");
        let text = document.to_string();
        let digest = rusty_quest_broker_authority::packaged_json_sha256(&text);
        materialize_compiled_profile(files_root, &text);
        assert!(
            load_compiled_experiment_inventory(files_root, compiled_anchors(&digest))
                .unwrap_err()
                .contains("projection-schema-invalid")
        );
        fs::remove_dir_all(files_root).unwrap();
    }

    #[test]
    fn compiled_inventory_rejects_invalid_runtime_projection_profile() {
        let recording_root = temp_root("compiled-profile-invalid");
        let files_root = recording_root.parent().unwrap();
        let mut document: Value = serde_json::from_str(&compiled_profile_document()).unwrap();
        document["runtime_projection"]["effective_radius_profile"]["configured_radius_min_m"] =
            json!(-1.0);
        let text = document.to_string();
        let digest = rusty_quest_broker_authority::packaged_json_sha256(&text);
        materialize_compiled_profile(files_root, &text);
        assert!(
            load_compiled_experiment_inventory(files_root, compiled_anchors(&digest))
                .unwrap_err()
                .contains("radius-profile-limits-invalid")
        );
        fs::remove_dir_all(files_root).unwrap();
    }

    #[test]
    fn compiled_inventory_accepts_exact_profile_and_injects_compiled_provenance() {
        let recording_root = temp_root("compiled-profile-valid");
        let files_root = recording_root.parent().unwrap();
        let text = compiled_profile_document();
        let digest = rusty_quest_broker_authority::packaged_json_sha256(&text);
        materialize_compiled_profile(files_root, &text);
        let inventory = load_compiled_experiment_inventory(files_root, compiled_anchors(&digest))
            .unwrap()
            .expect("trusted inventory");
        assert_ne!(inventory.non_audio_profile_sha256, digest);
        assert_eq!(inventory.non_audio_profile_sha256, "66".repeat(32));
        assert_eq!(inventory.provider_manifest_sha256, "33".repeat(32));
        assert_eq!(inventory.provider_inventory_sha256, "44".repeat(32));
        assert_eq!(
            inventory.effective_radius_profile.axis_profile,
            Some(
                crate::session_recording_contract::DeformationEnvelopeEndpoints {
                    at_radius_min: 1.8,
                    at_radius_max: 0.9,
                }
            )
        );
        fs::remove_dir_all(files_root).unwrap();
    }

    #[test]
    fn compiled_inventory_rejects_missing_or_invalid_inner_profile_identity() {
        let recording_root = temp_root("compiled-profile-inner-identity");
        let files_root = recording_root.parent().unwrap();
        let mut document: Value = serde_json::from_str(&compiled_profile_document()).unwrap();
        document
            .as_object_mut()
            .unwrap()
            .remove("non_audio_profile_sha256");
        let missing = document.to_string();
        let missing_digest = rusty_quest_broker_authority::packaged_json_sha256(&missing);
        materialize_compiled_profile(files_root, &missing);
        assert!(
            load_compiled_experiment_inventory(files_root, compiled_anchors(&missing_digest))
                .unwrap_err()
                .contains("non-audio-profile-sha256-invalid")
        );

        document["non_audio_profile_sha256"] = json!("not-a-sha256");
        let invalid = document.to_string();
        let invalid_digest = rusty_quest_broker_authority::packaged_json_sha256(&invalid);
        materialize_compiled_profile(files_root, &invalid);
        assert!(
            load_compiled_experiment_inventory(files_root, compiled_anchors(&invalid_digest))
                .unwrap_err()
                .contains("non-audio-profile-sha256-invalid")
        );
        fs::remove_dir_all(files_root).unwrap();
    }

    struct DamageBackend {
        fail_control_once: AtomicBool,
        inner: ActualRecordingBackend,
        recover_delay: Duration,
        prepare_delay: Duration,
        prepare_entered: Option<Arc<AtomicBool>>,
        fail_completion_once: AtomicBool,
        fail_finalize_once: AtomicBool,
    }

    impl DamageBackend {
        fn new(
            slot: Arc<Mutex<Option<Arc<SessionRecordingWriter>>>>,
            loss: Arc<OnceLock<ExternalLossRecorder>>,
        ) -> Self {
            Self {
                fail_control_once: AtomicBool::new(false),
                inner: ActualRecordingBackend::new(slot, loss),
                recover_delay: Duration::ZERO,
                prepare_delay: Duration::ZERO,
                prepare_entered: None,
                fail_completion_once: AtomicBool::new(false),
                fail_finalize_once: AtomicBool::new(false),
            }
        }
    }

    impl RecordingBackend for DamageBackend {
        fn record_control(
            &self,
            generation: u64,
            at: MonotonicNanos,
            kind: SessionEventKind,
        ) -> Result<(), String> {
            if kind == SessionEventKind::OfficialStart
                && self.fail_control_once.swap(false, Ordering::AcqRel)
            {
                return Err("injected-control-checkpoint-failure".to_owned());
            }
            self.inner.record_control(generation, at, kind)
        }
        fn recover(&mut self, root: &Path) -> Result<RecoveryReport, String> {
            thread::sleep(self.recover_delay);
            self.inner.recover(root)
        }

        fn prepare(&mut self, generation: u64, spec: SessionStartSpec) -> Result<(), String> {
            if let Some(entered) = self.prepare_entered.as_ref() {
                entered.store(true, Ordering::Release);
            }
            thread::sleep(self.prepare_delay);
            self.inner.prepare(generation, spec)
        }

        fn offer(&self, generation: u64, record: SessionRecord) -> OfferReceipt {
            self.inner.offer(generation, record)
        }

        fn mark_completion(&self, generation: u64, at: MonotonicNanos) -> Result<(), String> {
            if self.fail_completion_once.swap(false, Ordering::AcqRel) {
                Err("injected-completion-persistence-failure".to_owned())
            } else {
                self.inner.mark_completion(generation, at)
            }
        }

        fn finalize(
            &self,
            generation: u64,
            at: MonotonicNanos,
            reason: FinalizationReason,
            threshold_reached: bool,
        ) -> Result<FinalizedReceipt, String> {
            if self.fail_finalize_once.swap(false, Ordering::AcqRel) {
                let _ = self
                    .inner
                    .finalize(generation, at, reason, threshold_reached)?;
                Err("injected-finalization-receipt-failure".to_owned())
            } else {
                self.inner
                    .finalize(generation, at, reason, threshold_reached)
            }
        }

        fn shutdown(&mut self) -> Result<(), String> {
            self.inner.shutdown()
        }
    }

    fn start_command(_root: &Path, operation_id: &str) -> String {
        json!({
            "schema": EXPERIMENT_SESSION_COMMAND_SCHEMA,
            "operation":"start",
            "operation_id":operation_id,
            "expected_generation":0,
            "condition":"condition-a",
            "completion_threshold_ms":30_000,
            "started_at_utc_ns":1_725_000_000_000_000_000_u64,
            "elapsed_realtime_ns":1_000_u64,
            "non_audio_profile_sha256":"1111111111111111111111111111111111111111111111111111111111111111",
            "audio":{
                "logical_destination":"audio/condition-a.wav",
                "source_sha256":"2222222222222222222222222222222222222222222222222222222222222222",
                "source_bytes":1024,
                "media_type":"audio/wav"
            }
        }).to_string()
    }

    fn command(operation: &str, operation_id: &str, generation: u64, at: u64) -> String {
        json!({
            "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
            "operation":operation,
            "operation_id":operation_id,
            "expected_generation":generation,
            "elapsed_realtime_ns":at
        })
        .to_string()
    }

    fn wait_for(runtime: &ExperimentSessionRuntime, needle: &str) -> String {
        for _ in 0..200 {
            let status = runtime.status_json();
            if status.contains(needle) {
                return status;
            }
            thread::sleep(Duration::from_millis(5));
        }
        panic!("status did not contain {needle}: {}", runtime.status_json());
    }

    #[test]
    fn explicit_control_events_are_durable_once_and_audio_errors_hold_timing() {
        let root = temp_root("explicit-control");
        let runtime = runtime(&root);
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        let arm =
            start_command(&root, "arm").replace("\"operation\":\"start\"", "\"operation\":\"arm\"");
        runtime.apply_command_json(&arm);
        wait_for(&runtime, "\"control_state\":\"arming\"");
        runtime.apply_command_json(&command("audio-prepared", "prepared", 1, 2000));
        wait_for(&runtime, "\"control_receipt_revision\":1");
        runtime.note_submitted_frame(1, 1);
        runtime.apply_command_json(&json!({ "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA, "operation":"presentation",
            "operation_id":"present", "expected_generation":1, "elapsed_realtime_ns":3000, "presentation":"immersive-active" }).to_string());
        wait_for(&runtime, "\"presentation\":\"immersive-active\"");
        let start = command("official-start", "official", 1, 4000);
        runtime.apply_command_json(&start);
        wait_for(&runtime, "\"control_receipt_revision\":2");
        runtime.apply_command_json(&start);
        wait_for(&runtime, "\"last_operation_status\":\"duplicate\"");
        runtime.apply_command_json(&command("pause", "pause", 1, 5000));
        wait_for(&runtime, "\"control_receipt_revision\":3");
        runtime.apply_command_json(&command("resume", "resume", 1, 6000));
        wait_for(&runtime, "\"control_receipt_revision\":4");
        runtime.apply_command_json(&command("audio-error", "audio-failure", 1, 7000));
        let held = wait_for(&runtime, "\"control_receipt_revision\":5");
        assert!(held.contains("\"control_state\":\"paused\""));
        assert!(held.contains("\"audio_technical_hold\":true"));
        // Read before finalization: the control receipt requires a flushed checkpoint,
        // not eventual persistence when the user exits.
        let directory = fs::read_dir(&root)
            .unwrap()
            .filter_map(Result::ok)
            .find(|entry| entry.path().join("events.jsonl").is_file())
            .unwrap()
            .path();
        let events = fs::read_to_string(directory.join("events.jsonl")).unwrap();
        assert_eq!(
            events.matches("\"event\":\"official-start\"").count(),
            1,
            "{events}"
        );
        assert_eq!(events.matches("\"event\":\"armed\"").count(), 1, "{events}");
        assert_eq!(
            events.matches("\"event\":\"experiment-resumed\"").count(),
            1,
            "{events}"
        );
        assert!(
            events.contains("4000"),
            "official t0 must retain command time"
        );
        runtime.apply_command_json(&command("save-and-exit", "exit", 1, 8000));
        let terminal = wait_for(&runtime, "\"shutdown_status\":\"complete\"");
        assert!(
            terminal.contains("\"recovery_status\":\"complete\""),
            "{terminal}"
        );
        assert!(terminal.contains("\"status\":\"saved\""), "{terminal}");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn failed_official_start_checkpoint_finalizes_instead_of_running() {
        let root = temp_root("control-checkpoint-failure");
        let runtime = ExperimentSessionRuntime::spawn(
            root.clone(),
            Some(trusted_inventory()),
            |slot, loss| {
                let backend = DamageBackend::new(slot, loss);
                backend.fail_control_once.store(true, Ordering::Release);
                Box::new(backend)
            },
        )
        .unwrap();
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(
            &start_command(&root, "arm")
                .replace("\"operation\":\"start\"", "\"operation\":\"arm\""),
        );
        wait_for(&runtime, "\"control_state\":\"arming\"");
        runtime.apply_command_json(&command("audio-prepared", "prepared", 1, 2000));
        wait_for(&runtime, "\"control_receipt_revision\":1");
        runtime.note_submitted_frame(1, 1);
        runtime.apply_command_json(&json!({ "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA, "operation":"presentation",
            "operation_id":"present", "expected_generation":1, "elapsed_realtime_ns":3000, "presentation":"immersive-active" }).to_string());
        wait_for(&runtime, "\"presentation\":\"immersive-active\"");
        runtime.apply_command_json(&command("official-start", "official", 1, 4000));
        let failed = wait_for(&runtime, "recording-control-persistence");
        assert!(failed.contains("\"control_state\":\"idle\""));
        assert!(failed.contains("\"control_receipt_revision\":1"));
        assert!(failed.contains("\"status\":\"unsaved\""));
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn command_worker_prepares_records_counts_and_routes_only_after_finalize() {
        let root = temp_root("round-trip");
        let runtime = runtime(&root);
        assert!(runtime
            .apply_command_json(&start_command(&root, "start-1"))
            .contains("queued"));
        let active = wait_for(&runtime, "\"phase\":\"active\"");
        assert!(active.contains("\"profile_identity_bound\":true"));
        assert_eq!(runtime.active_generation.load(Ordering::Acquire), 1);
        assert!(matches!(
            runtime.try_offer(SessionRecord::Breath(BreathObservation {
                source_sequence: 1,
                sampled_at: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 1_100,
                },
                observed_source: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 1_100,
                },
                observed_at: MonotonicNanos::new(1_100),
                phase: BreathPhase::Inhale,
                volume01: Some(0.5),
                quality01: 1.0,
                settings_revision: 1,
            })),
            OfferOutcome::Accepted { .. }
        ));
        assert!(runtime
            .apply_command_json(&command("restart-to-experimenter", "restart-1", 1, 2_000))
            .contains("queued"));
        let finalized = wait_for(&runtime, "\"route_action\":\"show-experimenter\"");
        assert!(
            finalized.contains("\"stopped_early\":1"),
            "unexpected finalized readback: {finalized}"
        );
        assert!(
            finalized.contains("\"recording_result\":{\"completed\":false,\"status\":\"saved\"}")
        );
        let finalized_value: Value = serde_json::from_str(&finalized).unwrap();
        assert_eq!(finalized_value["readback"]["storage_status"], "ready");
        assert_eq!(finalized_value["readback"]["recovery_status"], "complete");
        assert_eq!(finalized_value["readback"]["recovery_count"], 1);
        assert_eq!(
            finalized_value["readback"]["counts"]["by_condition"]["condition-a"]["stopped_early"],
            1
        );
        assert_eq!(finalized_value["readback"]["kiosk_requested"], true);
        assert_eq!(finalized_value["readback"]["kiosk_effective"], "unknown");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn missing_private_identity_stale_generation_and_duplicate_fail_closed() {
        let root = temp_root("negative");
        let runtime = runtime(&root);
        let mut missing: Value =
            serde_json::from_str(&start_command(&root, "start-missing")).unwrap();
        missing.as_object_mut().unwrap().remove("audio");
        runtime.apply_command_json(&missing.to_string());
        let rejected = wait_for(&runtime, "audio-identity-missing");
        assert!(rejected.contains("\"phase\":\"idle\""));

        runtime.apply_command_json(&start_command(&root, "start-good"));
        wait_for(&runtime, "\"phase\":\"active\"");
        runtime.apply_command_json(&command("audio-started", "audio-stale", 0, 1_900));
        let stale_audio = wait_for(&runtime, "\"last_operation_id\":\"audio-stale\"");
        assert!(stale_audio.contains("StaleGeneration"));
        runtime.apply_command_json(&command("tick", "stale", 0, 2_000));
        let stale = wait_for(&runtime, "StaleGeneration");
        assert!(stale.contains("\"generation\":1"));
        runtime.apply_command_json(&command("tick", "dup", 1, 2_100));
        wait_for(&runtime, "\"last_operation_id\":\"dup\"");
        runtime.apply_command_json(&command("tick", "dup", 1, 2_100));
        wait_for(&runtime, "\"last_operation_status\":\"duplicate\"");
        runtime.apply_command_json(&command("save-and-exit", "exit", 1, 2_200));
        wait_for(&runtime, "\"route_action\":\"shutdown-app\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn developer_pause_audio_end_and_effective_radius_taps_preserve_session() {
        let root = temp_root("pause-taps");
        let runtime = runtime(&root);
        runtime.apply_command_json(&start_command(&root, "start"));
        wait_for(&runtime, "\"phase\":\"active\"");
        assert!(runtime.note_submitted_frame(1, 1));
        runtime.apply_command_json(
            &json!({
                "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
                "operation":"presentation",
                "operation_id":"immersive",
                "expected_generation":1,
                "elapsed_realtime_ns":10_000,
                "presentation":"immersive-active"
            })
            .to_string(),
        );
        wait_for(&runtime, "\"presentation\":\"immersive-active\"");
        runtime.apply_command_json(&command("audio-started", "audio-start", 1, 10_001));
        wait_for(&runtime, "\"last_operation_id\":\"audio-start\"");
        runtime.apply_command_json(&command("audio-started", "audio-start", 1, 10_001));
        wait_for(&runtime, "\"last_operation_status\":\"duplicate\"");
        runtime.apply_command_json(&command("tick", "tick-1", 1, 1_000_010_000));
        wait_for(&runtime, "\"last_operation_id\":\"tick-1\"");
        runtime.apply_command_json(&command("open-developer", "dev", 1, 1_000_020_000));
        wait_for(&runtime, "\"route_action\":\"open-developer\"");
        runtime.apply_command_json(&command("tick", "paused", 1, 20_000_000_000));
        let paused = wait_for(&runtime, "\"last_operation_id\":\"paused\"");
        assert!(paused.contains("\"active_time_ms\":1000"));
        runtime.apply_command_json(&command("audio-ended", "audio-end", 1, 20_000_000_001));
        wait_for(&runtime, "\"last_operation_id\":\"audio-end\"");
        runtime.apply_command_json(&command("audio-error", "audio-error", 1, 20_000_000_002));
        let audio_error = wait_for(&runtime, "\"last_operation_id\":\"audio-error\"");
        assert!(audio_error.contains("\"phase\":\"active\""));
        assert!(matches!(
            runtime.try_offer(SessionRecord::EffectiveRadiusSnapshot(
                EffectiveRadiusSnapshotObservation {
                    source_frame: 7,
                    observed_at: MonotonicNanos::new(20_000_000_003),
                    configured_radius_min_m: 0.8,
                    configured_radius_max_m: 1.6,
                    radius_progress01: 0.5,
                    resulting_radius_m: 1.2,
                    deformation_progress01: 0.5,
                    oblateness: Some(
                        crate::session_recording_contract::DeformationEnvelopeEndpoints {
                            at_radius_min: 0.2,
                            at_radius_max: 0.4,
                        },
                    ),
                    axis_profile: Some(
                        crate::session_recording_contract::DeformationEnvelopeEndpoints {
                            at_radius_min: 1.8,
                            at_radius_max: 0.9,
                        },
                    ),
                    settings_revision: 3,
                    render_session_generation: 2,
                    resulting_radius_parameter_source: "app-effective-world-anchor",
                }
            )),
            OfferOutcome::Accepted { .. }
        ));
        runtime.apply_command_json(&command("save-and-exit", "exit", 1, 20_000_000_003));
        let exited = wait_for(&runtime, "\"route_action\":\"shutdown-app\"");
        assert!(exited.contains("\"phase\":\"closed\""));
        assert!(!exited.contains("\"route_action\":\"show-experimenter\""));
        runtime.shutdown_for_test().unwrap();

        let directory = fs::read_dir(&root)
            .unwrap()
            .filter_map(Result::ok)
            .find(|entry| entry.file_type().unwrap().is_dir())
            .unwrap()
            .path();
        let radius = fs::read_to_string(directory.join("radius.jsonl")).unwrap();
        assert!(radius.contains("\"kind\":\"effective-radius-snapshot\""));
        assert!(radius.contains("actual-runtime-radius-with-configured-deformation-envelopes"));
        assert!(radius.contains("\"resulting_radius_m\":1.2"));
        assert!(radius.contains("\"oblateness\""));
        assert!(radius.contains("\"axis_profile\""));
        assert!(!radius.contains("minimum_m"));
        assert!(!radius.contains("mean_m"));
        assert!(!radius.contains("maximum_m"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn producer_retry_loss_is_generation_scoped_and_stale_safe() {
        let state = AtomicU64::new(0);
        reset_producer_loss(&state, 7);
        record_producer_loss(&state, 7);
        assert_eq!(producer_loss_count(&state, 7), 1);

        reset_producer_loss(&state, 8);
        record_producer_loss(&state, 7);
        assert_eq!(producer_loss_count(&state, 7), 0);
        assert_eq!(producer_loss_count(&state, 8), 0);
        record_producer_loss(&state, 8);
        assert_eq!(producer_loss_count(&state, 8), 1);
    }

    #[test]
    fn worker_timer_persists_completion_without_tick_commands() {
        let root = temp_root("worker-timer");
        let runtime = runtime(&root);
        runtime.apply_command_json(&start_command(&root, "start-timer"));
        wait_for(&runtime, "\"phase\":\"active\"");
        assert!(runtime.note_submitted_frame(1, 1));
        runtime.apply_command_json(
            &json!({
                "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
                "operation":"presentation",
                "operation_id":"immersive-timer",
                "expected_generation":1,
                "elapsed_realtime_ns":10_000,
                "presentation":"immersive-active"
            })
            .to_string(),
        );
        wait_for(&runtime, "\"presentation\":\"immersive-active\"");
        {
            let mut bridge = runtime.monotonic_bridge.lock().unwrap();
            let (anchor, _) = bridge.expect("active bridge");
            *bridge = Some((anchor, Instant::now() - Duration::from_secs(31)));
        }
        assert!(runtime.note_submitted_frame(1, 2));
        let completed = wait_for(&runtime, "\"completion\":\"durable\"");
        assert!(completed.contains("\"active_time_ms\":3"));
        runtime.apply_command_json(&command(
            "restart-to-experimenter",
            "timer-stop",
            1,
            31_100_000_000,
        ));
        wait_for(&runtime, "\"route_action\":\"show-experimenter\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn production_absent_inventory_rejects_start_without_touching_command_root() {
        let root = temp_root("inventory-unavailable");
        let runtime = ExperimentSessionRuntime::spawn_actual(root.clone(), None).unwrap();
        let command = start_command(&root, "start-unavailable");
        assert!(!command.contains("recording_root"));
        runtime.apply_command_json(&command);
        let rejected = wait_for(&runtime, "inventory-unavailable");
        assert!(rejected.contains("\"phase\":\"idle\""));
        assert!(rejected.contains("\"inventory_status\":\"inventory-unavailable\""));
        runtime.shutdown_for_test().unwrap();
    }

    #[test]
    fn submitted_frame_gate_requires_focus_and_two_advancing_current_frames() {
        let mut gate = SubmittedFrameReadinessGate::default();
        gate.begin_session();
        assert!(!gate.note_submitted(10, 1));
        assert!(!gate.set_focused(true));
        assert!(!gate.note_submitted(10, 2));
        assert!(!gate.note_submitted(10, 3));
        assert!(gate.note_submitted(11, 4));
        assert!(!gate.note_submitted(12, 5));
        assert!(gate.set_focused(false));
        assert!(!gate.note_submitted(13, 6));
    }

    #[test]
    fn submitted_frame_gate_reproves_after_stall_and_retries_rejected_admission() {
        let mut gate = SubmittedFrameReadinessGate::default();
        gate.set_focused(true);
        assert!(!gate.note_submitted(1, 1));
        assert!(gate.note_submitted(2, 2));
        gate.reject_activation();
        assert!(gate.note_submitted(3, 3));

        assert!(!gate.note_submitted(4, SUBMITTED_FRAME_STALL_NS + 4));
        assert!(gate.note_submitted(5, SUBMITTED_FRAME_STALL_NS + 5));
    }

    #[test]
    fn async_initializer_returns_before_archive_recovery_finishes() {
        let root = temp_root("async-init");
        let started = Instant::now();
        let runtime = ExperimentSessionRuntime::spawn(
            root.clone(),
            Some(trusted_inventory()),
            |slot, loss| {
                let mut backend = DamageBackend::new(slot, loss);
                backend.recover_delay = Duration::from_millis(250);
                Box::new(backend)
            },
        )
        .unwrap();
        assert!(started.elapsed() < Duration::from_millis(100));
        assert!(runtime
            .status_json()
            .contains("\"initialization_status\":\"initializing\""));
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.shutdown_for_test().unwrap();
    }

    #[test]
    fn completion_persistence_fault_still_finalizes_and_reports_unsaved() {
        let root = temp_root("completion-fault");
        let runtime = ExperimentSessionRuntime::spawn(
            root.clone(),
            Some(trusted_inventory()),
            |slot, loss| {
                let backend = DamageBackend::new(slot, loss);
                backend.fail_completion_once.store(true, Ordering::Release);
                Box::new(backend)
            },
        )
        .unwrap();
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-fault"));
        wait_for(&runtime, "\"phase\":\"active\"");
        assert!(runtime.note_submitted_frame(1, 1));
        runtime.apply_command_json(
            &json!({
                "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
                "operation":"presentation",
                "operation_id":"immersive-fault",
                "expected_generation":1,
                "elapsed_realtime_ns":10_000,
                "presentation":"immersive-active"
            })
            .to_string(),
        );
        wait_for(&runtime, "\"presentation\":\"immersive-active\"");
        {
            let mut bridge = runtime.monotonic_bridge.lock().unwrap();
            let (anchor, _) = bridge.expect("active bridge");
            *bridge = Some((anchor, Instant::now() - Duration::from_secs(31)));
        }
        assert!(runtime.note_submitted_frame(1, 2));
        // The route is published inside recursive finalization before its caller
        // publishes the completion-persistence fault. Await the final fault receipt.
        let terminal = wait_for(&runtime, "recording-completion-persistence");
        assert!(terminal.contains("\"route_action\":\"show-experimenter\""));
        assert!(terminal.contains("\"phase\":\"idle\""));
        assert!(terminal.contains("\"status\":\"unsaved\""));
        assert!(terminal.contains("recording-completion-persistence"));
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn terminal_lane_remains_admitted_when_regular_command_lane_is_full() {
        let root = temp_root("terminal-lane");
        let prepare_entered = Arc::new(AtomicBool::new(false));
        let backend_entered = Arc::clone(&prepare_entered);
        let runtime = ExperimentSessionRuntime::spawn(
            root.clone(),
            Some(trusted_inventory()),
            move |slot, loss| {
                let mut backend = DamageBackend::new(slot, loss);
                backend.prepare_delay = Duration::from_millis(250);
                backend.prepare_entered = Some(backend_entered);
                Box::new(backend)
            },
        )
        .unwrap();
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-terminal-lane"));
        for _ in 0..100 {
            if prepare_entered.load(Ordering::Acquire) {
                break;
            }
            thread::sleep(Duration::from_millis(2));
        }
        assert!(prepare_entered.load(Ordering::Acquire));
        let status = json!({
            "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
            "operation":"status"
        })
        .to_string();
        let mut regular_full = false;
        for _ in 0..(COMMAND_QUEUE_CAPACITY * 2) {
            regular_full |= runtime
                .apply_command_json(&status)
                .contains("command-queue-full");
        }
        assert!(regular_full);
        let terminal = runtime.apply_command_json(&command(
            "restart-to-experimenter",
            "terminal-priority",
            1,
            2_000,
        ));
        assert!(terminal.contains("\"command_status\":\"queued\""));
        wait_for(&runtime, "\"route_action\":\"show-experimenter\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn malformed_conflicting_and_regressed_terminal_commands_do_not_close_recording() {
        let root = temp_root("rejected-terminal-transaction");
        let runtime = runtime(&root);
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-rejected-terminal"));
        wait_for(&runtime, "\"phase\":\"active\"");
        for missing in ["operation_id", "elapsed_realtime_ns"] {
            let mut value: Value =
                serde_json::from_str(&command("save-and-exit", "malformed-stop", 1, 5_000))
                    .unwrap();
            value.as_object_mut().unwrap().remove(missing);
            let raw = value.to_string();
            assert_eq!(terminal_command_generation(&raw), None);
            runtime.apply_command_json(&raw);
            assert!(runtime.producer_gate.read().unwrap().accepting);
            wait_for(
                &runtime,
                if missing == "operation_id" {
                    "operation-id-invalid"
                } else {
                    "elapsed-realtime-invalid"
                },
            );
        }
        runtime.apply_command_json(&command("tick", "occupied-id", 1, 10_000));
        wait_for(&runtime, "\"last_operation_id\":\"occupied-id\"");
        for (operation_id, at, reason) in [
            ("occupied-id", 11_000, "operation-conflict"),
            ("regressed-stop", 9_999, "MonotonicTimeRegression"),
        ] {
            runtime.apply_command_json(&command("save-and-exit", operation_id, 1, at));
            wait_for(&runtime, reason);
            let deadline = Instant::now() + Duration::from_secs(2);
            while !runtime.producer_gate.read().unwrap().accepting && Instant::now() < deadline {
                thread::yield_now();
            }
            let gate = runtime.producer_gate.read().unwrap();
            assert!(
                gate.accepting,
                "{reason} must release only its rejected cutoff"
            );
            assert_eq!(gate.terminal_claims, 0);
            assert_eq!(runtime.active_generation.load(Ordering::Acquire), 1);
        }
        runtime.apply_command_json(&command("save-and-exit", "valid-stop", 1, 20_000));
        wait_for(&runtime, "\"shutdown_status\":\"complete\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn rejected_terminal_cannot_release_an_overlapping_admitted_cutoff() {
        let root = temp_root("overlapping-terminal-claims");
        let runtime = runtime(&root);
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-overlap"));
        wait_for(&runtime, "\"phase\":\"active\"");
        runtime.apply_command_json(&command("tick", "occupied-overlap", 1, 10_000));
        wait_for(&runtime, "\"last_operation_id\":\"occupied-overlap\"");
        let (entered, entered_rx) = mpsc::sync_channel(1);
        let (resume, resume_rx) = mpsc::channel();
        runtime
            .command_sender
            .send(WorkerMessage::PauseForTest {
                entered,
                resume: resume_rx,
            })
            .unwrap();
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        runtime.apply_command_json(&command("save-and-exit", "occupied-overlap", 1, 11_000));
        let (rejected_entered, rejected_rx) = mpsc::sync_channel(1);
        let (finish, finish_rx) = mpsc::channel();
        runtime
            .terminal_sender
            .send(WorkerMessage::PauseForTest {
                entered: rejected_entered,
                resume: finish_rx,
            })
            .unwrap();
        runtime.apply_command_json(&command("save-and-exit", "accepted-overlap", 1, 12_000));
        assert_eq!(runtime.producer_gate.read().unwrap().terminal_claims, 2);
        resume.send(()).unwrap();
        rejected_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(runtime.status_json().contains("operation-conflict"));
        let gate = *runtime.producer_gate.read().unwrap();
        assert!(!gate.accepting);
        assert_eq!(gate.terminal_claims, 1);
        assert_eq!(runtime.active_generation.load(Ordering::Acquire), 1);
        finish.send(()).unwrap();
        wait_for(&runtime, "\"shutdown_status\":\"complete\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn rejected_terminal_lane_preserves_an_existing_producer_cutoff() {
        let root = temp_root("terminal-cutoff-saturated-lane");
        let runtime = runtime(&root);
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-cutoff-saturated-lane"));
        wait_for(&runtime, "\"phase\":\"active\"");
        let (entered_sender, entered_receiver) = mpsc::sync_channel(1);
        let (resume_sender, resume_receiver) = mpsc::channel();
        runtime
            .command_sender
            .send(WorkerMessage::PauseForTest {
                entered: entered_sender,
                resume: resume_receiver,
            })
            .unwrap();
        entered_receiver
            .recv_timeout(Duration::from_secs(2))
            .unwrap();
        assert!(runtime.producer_gate.read().unwrap().accepting);
        for index in 0..TERMINAL_QUEUE_CAPACITY {
            let response = runtime.apply_command_json(&command(
                "save-and-exit",
                &format!("cutoff-terminal-{index}"),
                1,
                20_000,
            ));
            assert!(response.contains("\"command_status\":\"queued\""));
            assert!(!runtime.producer_gate.read().unwrap().accepting);
        }
        let rejected = runtime.apply_command_json(&command(
            "save-and-exit",
            "cutoff-terminal-rejected",
            1,
            20_001,
        ));
        assert!(rejected.contains("terminal-lane-full"));
        assert_eq!(runtime.active_generation.load(Ordering::Acquire), 1);
        assert!(!runtime.producer_gate.read().unwrap().accepting);
        assert!(matches!(
            runtime.try_offer(SessionRecord::Breath(BreathObservation {
                source_sequence: 1,
                sampled_at: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 20_002
                },
                observed_source: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 20_002
                },
                observed_at: MonotonicNanos::new(20_002),
                phase: BreathPhase::Inhale,
                volume01: Some(0.5),
                quality01: 1.0,
                settings_revision: 1,
            })),
            OfferOutcome::NotAccepting
        ));
        resume_sender.send(()).unwrap();
        wait_for(&runtime, "\"shutdown_status\":\"complete\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn registry_is_idempotent_and_rearms_only_after_exact_shutdown_ack() {
        let root = temp_root("registry-rearm");
        let registry = RuntimeRegistry::default();
        registry
            .initialize(root.clone(), Some(trusted_inventory()))
            .unwrap();
        let first = registry.current().unwrap();
        assert_eq!(first.runtime_epoch, 1);
        registry
            .initialize(root.clone(), Some(trusted_inventory()))
            .unwrap();
        assert!(Arc::ptr_eq(&first, &registry.current().unwrap()));
        let first_ready = wait_for(&first, "\"initialization_status\":\"ready\"");
        assert!(first_ready.contains("\"runtime_epoch\":1"));
        first.apply_command_json(&command("save-and-exit", "cold-exit", 0, 1));
        wait_for(&first, "\"shutdown_status\":\"complete\"");
        for _ in 0..100 {
            if first.worker_finished() {
                break;
            }
            thread::sleep(Duration::from_millis(2));
        }
        assert!(first.worker_finished());
        registry
            .initialize(root.clone(), Some(trusted_inventory()))
            .unwrap();
        let second = registry.current().unwrap();
        assert!(!Arc::ptr_eq(&first, &second));
        assert_eq!(second.runtime_epoch, 2);
        let second_ready = wait_for(&second, "\"initialization_status\":\"ready\"");
        assert!(second_ready.contains("\"runtime_epoch\":2"));
        assert!(second_ready.contains("\"generation\":0"));
        second.shutdown_for_test().unwrap();
    }

    #[test]
    fn immersive_owner_destruction_finalizes_active_recording_and_relaunches_idle() {
        let root = temp_root("owner-destroy-fresh-launch");
        let registry = RuntimeRegistry::default();
        registry
            .initialize(root.clone(), Some(trusted_inventory()))
            .unwrap();
        let first = registry.current().unwrap();
        wait_for(&first, "\"initialization_status\":\"ready\"");
        first.apply_command_json(&start_command(&root, "first-owner"));
        wait_for(&first, "\"phase\":\"active\"");

        registry.shutdown_for_immersive_owner_destroyed().unwrap();
        assert!(first.worker_finished());
        let first_status = first.status_json();
        assert!(first_status.contains("\"phase\":\"closed\""));
        assert!(first_status.contains("\"shutdown_status\":\"complete\""));
        let first_directory = fs::read_dir(&root)
            .unwrap()
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .find(|path| path.is_dir())
            .expect("first recording directory");
        let first_final = fs::read(first_directory.join("final.json")).unwrap();
        let first_final_value: Value = serde_json::from_slice(&first_final).unwrap();
        assert_eq!(
            first_final_value["stop_reason"],
            json!("interrupted-recovery")
        );

        registry
            .initialize(root.clone(), Some(trusted_inventory()))
            .unwrap();
        let second = registry.current().unwrap();
        assert!(!Arc::ptr_eq(&first, &second));
        assert_eq!(second.runtime_epoch, 2);
        let ready = wait_for(&second, "\"initialization_status\":\"ready\"");
        assert!(ready.contains("\"phase\":\"idle\""));
        assert!(ready.contains("\"generation\":0"));

        let mut fresh_start: Value =
            serde_json::from_str(&start_command(&root, "second-owner")).unwrap();
        fresh_start["started_at_utc_ns"] = json!(1_725_000_000_000_001_000_u64);
        second.apply_command_json(&fresh_start.to_string());
        wait_for(&second, "\"phase\":\"active\"");
        registry.shutdown_for_immersive_owner_destroyed().unwrap();
        assert_eq!(
            fs::read_dir(&root)
                .unwrap()
                .filter_map(Result::ok)
                .filter(|entry| entry.path().is_dir())
                .count(),
            2
        );
        assert_eq!(
            fs::read(first_directory.join("final.json")).unwrap(),
            first_final
        );
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn failed_async_recovery_does_not_poison_registry_reinitialization() {
        let root = temp_root("registry-recovery-retry");
        fs::create_dir_all(root.parent().unwrap()).unwrap();
        fs::write(&root, b"blocks-directory").unwrap();
        let registry = RuntimeRegistry::default();
        registry
            .initialize(root.clone(), Some(trusted_inventory()))
            .unwrap();
        let failed = registry.current().unwrap();
        assert_eq!(failed.runtime_epoch, 1);
        let status = wait_for(&failed, "\"initialization_status\":\"error\"");
        assert!(status.contains("\"recording_root_status\":\"error\""));
        for _ in 0..100 {
            if failed.worker_finished() {
                break;
            }
            thread::sleep(Duration::from_millis(2));
        }
        assert!(failed.worker_finished());
        fs::remove_file(&root).unwrap();
        registry
            .initialize(root.clone(), Some(trusted_inventory()))
            .unwrap();
        let recovered = registry.current().unwrap();
        assert!(!Arc::ptr_eq(&failed, &recovered));
        assert_eq!(recovered.runtime_epoch, 2);
        let recovered_ready = wait_for(&recovered, "\"initialization_status\":\"ready\"");
        assert!(recovered_ready.contains("\"runtime_epoch\":2"));
        recovered.shutdown_for_test().unwrap();
    }

    #[test]
    fn worker_pauses_active_time_when_submitted_frame_heartbeat_stalls() {
        let root = temp_root("frame-stall");
        let runtime = runtime(&root);
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-stall"));
        wait_for(&runtime, "\"phase\":\"active\"");
        assert!(runtime.note_submitted_frame(1, 1));
        runtime.apply_command_json(
            &json!({
                "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
                "operation":"presentation",
                "operation_id":"immersive-stall",
                "expected_generation":1,
                "elapsed_realtime_ns":10_000,
                "presentation":"immersive-active"
            })
            .to_string(),
        );
        wait_for(&runtime, "\"presentation\":\"immersive-active\"");
        {
            let mut bridge = runtime.monotonic_bridge.lock().unwrap();
            let (anchor, _) = bridge.expect("active bridge");
            *bridge = Some((anchor, Instant::now() - Duration::from_millis(600)));
        }
        let paused = wait_for(&runtime, "\"presentation\":\"unfocused\"");
        assert!(paused.contains("\"phase\":\"active\""));
        runtime.apply_command_json(&command("save-and-exit", "stall-exit", 1, 700_000_000));
        wait_for(&runtime, "\"shutdown_status\":\"complete\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn submitted_frame_heartbeat_advances_only_for_current_monotonic_frames() {
        let root = temp_root("frame-monotonic");
        let runtime = runtime(&root);
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-monotonic"));
        wait_for(&runtime, "\"phase\":\"active\"");

        assert!(runtime.note_submitted_frame(1, 10));
        let accepted_at = runtime.submitted_frame_elapsed_ns.load(Ordering::Acquire);
        assert!(!runtime.note_submitted_frame(1, 10));
        assert!(!runtime.note_submitted_frame(1, 9));
        assert!(!runtime.note_submitted_frame(2, 11));
        assert_eq!(
            runtime.submitted_frame_elapsed_ns.load(Ordering::Acquire),
            accepted_at
        );
        assert!(runtime.note_submitted_frame(1, 11));

        runtime.apply_command_json(&command("save-and-exit", "monotonic-exit", 1, 20_000));
        wait_for(&runtime, "\"shutdown_status\":\"complete\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn terminal_admission_rejects_busy_cutoff_without_enqueuing() {
        let root = temp_root("terminal-admission-busy");
        let runtime = runtime(&root);
        wait_for(&runtime, "\"initialization_status\":\"ready\"");
        runtime.apply_command_json(&start_command(&root, "start-terminal-admission"));
        wait_for(&runtime, "\"phase\":\"active\"");

        let held_offer_admission = runtime.producer_gate.read().unwrap();
        let rejected = runtime.apply_command_json(&command(
            "restart-to-experimenter",
            "busy-terminal",
            1,
            20_000,
        ));
        assert!(rejected.contains("\"reason_code\":\"terminal-admission-busy\""));
        assert!(held_offer_admission.accepting);
        drop(held_offer_admission);

        let queued = runtime.apply_command_json(&command(
            "restart-to-experimenter",
            "busy-terminal",
            1,
            20_000,
        ));
        assert!(queued.contains("\"command_status\":\"queued\""));
        wait_for(&runtime, "\"route_action\":\"show-experimenter\"");
        runtime.shutdown_for_test().unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }

    #[test]
    fn terminal_command_crossing_threshold_persists_then_finalizes_for_b_and_home() {
        for (label, terminal_operation, route) in [
            (
                "threshold-b",
                "restart-to-experimenter",
                "show-experimenter",
            ),
            ("threshold-home", "save-and-exit", "shutdown-app"),
        ] {
            let root = temp_root(label);
            let runtime = runtime(&root);
            wait_for(&runtime, "\"initialization_status\":\"ready\"");
            runtime.apply_command_json(&start_command(&root, "start-threshold"));
            wait_for(&runtime, "\"phase\":\"active\"");
            assert!(runtime.note_submitted_frame(1, 1));
            runtime.apply_command_json(
                &json!({
                    "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
                    "operation":"presentation",
                    "operation_id":"immersive-threshold",
                    "expected_generation":1,
                    "elapsed_realtime_ns":10_000,
                    "presentation":"immersive-active"
                })
                .to_string(),
            );
            wait_for(&runtime, "\"presentation\":\"immersive-active\"");
            let terminal = runtime.apply_command_json(&command(
                terminal_operation,
                "threshold-terminal",
                1,
                30_000_010_000,
            ));
            assert!(terminal.contains("\"command_status\":\"queued\""));
            let finalized = wait_for(&runtime, &format!("\"route_action\":\"{route}\""));
            assert!(finalized.contains("\"completed\":true"));
            assert!(finalized.contains("\"status\":\"saved\""));
            runtime.shutdown_for_test().unwrap();
            fs::remove_dir_all(root.parent().unwrap()).unwrap();
        }
    }

    #[test]
    fn terminal_threshold_crossing_routes_unsaved_after_finalize_failure_for_b_and_home() {
        for (label, terminal_operation, route) in [
            (
                "threshold-finalize-fault-b",
                "restart-to-experimenter",
                "show-experimenter",
            ),
            (
                "threshold-finalize-fault-home",
                "save-and-exit",
                "shutdown-app",
            ),
        ] {
            let root = temp_root(label);
            let runtime = ExperimentSessionRuntime::spawn(
                root.clone(),
                Some(trusted_inventory()),
                |slot, loss| {
                    let backend = DamageBackend::new(slot, loss);
                    backend.fail_finalize_once.store(true, Ordering::Release);
                    Box::new(backend)
                },
            )
            .unwrap();
            wait_for(&runtime, "\"initialization_status\":\"ready\"");
            runtime.apply_command_json(&start_command(&root, "start-threshold-fault"));
            wait_for(&runtime, "\"phase\":\"active\"");
            assert!(runtime.note_submitted_frame(1, 1));
            runtime.apply_command_json(
                &json!({
                    "schema":EXPERIMENT_SESSION_COMMAND_SCHEMA,
                    "operation":"presentation",
                    "operation_id":"immersive-threshold-fault",
                    "expected_generation":1,
                    "elapsed_realtime_ns":10_000,
                    "presentation":"immersive-active"
                })
                .to_string(),
            );
            wait_for(&runtime, "\"presentation\":\"immersive-active\"");
            runtime.apply_command_json(&command(
                terminal_operation,
                "threshold-terminal-fault",
                1,
                30_000_010_000,
            ));
            let failed = wait_for(&runtime, &format!("\"route_action\":\"{route}\""));
            assert!(failed.contains("\"completed\":true"));
            assert!(failed.contains("\"status\":\"unsaved\""));
            assert!(failed.contains("\"error\":\"Finalize\""));
            runtime.shutdown_for_test().unwrap();
            fs::remove_dir_all(root.parent().unwrap()).unwrap();
        }
    }
}
