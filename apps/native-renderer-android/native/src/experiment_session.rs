//! Pure experiment-session reducer.
//!
//! Android lifecycle, audio, filesystem, JNI, and worker threads are adapters
//! around this state machine. Every mutation is generation fenced and
//! idempotent by operation token.

use std::collections::VecDeque;

use crate::{
    session_recording_clock::MonotonicNanos,
    session_recording_contract::{ConditionKey, FinalizationReason},
};

const OPERATION_HISTORY_CAPACITY: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct OperationToken(u64);

impl OperationToken {
    pub(crate) const fn new(value: u64) -> Option<Self> {
        if value == 0 {
            None
        } else {
            Some(Self(value))
        }
    }

    const fn get(self) -> u64 {
        self.0
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum SessionPhase {
    #[default]
    Idle,
    Preparing,
    Active,
    FinalizingRestart,
    Exiting,
    Closed,
}

/// Experiment timing is independent of recording acquisition and Android focus.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum ExperimentControlState {
    #[default]
    Idle,
    Arming,
    Armed,
    Running,
    Paused,
    Finalizing,
}

impl ExperimentControlState {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Arming => "arming",
            Self::Armed => "armed",
            Self::Running => "running",
            Self::Paused => "paused",
            Self::Finalizing => "finalizing",
        }
    }
}

/// A grip chord consumes A/B until all buttons are released, including when a
/// chord is invalid for this state. No held button may become a fresh command.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct ExperimentControlGesture {
    held_seconds: f32,
    candidate: Option<&'static str>,
    consumed: bool,
    suppress_buttons: bool,
}

impl ExperimentControlGesture {
    pub(crate) fn update(
        &mut self,
        dt: f32,
        grip: bool,
        a: bool,
        b: bool,
        state: ExperimentControlState,
    ) -> Option<&'static str> {
        if !grip && !a && !b {
            *self = Self::default();
            return None;
        }
        if grip {
            self.suppress_buttons = true;
        }
        if !dt.is_finite() || dt <= 0.0 || dt > 0.25 {
            self.held_seconds = 0.0;
            return None;
        }
        let candidate = if grip && a && !b {
            match state {
                ExperimentControlState::Armed => Some("official-start"),
                ExperimentControlState::Paused => Some("resume"),
                _ => None,
            }
        } else if grip && b && !a && state == ExperimentControlState::Running {
            Some("pause")
        } else {
            None
        };
        if self.consumed {
            return None;
        }
        if candidate != self.candidate {
            self.held_seconds = 0.0;
            self.candidate = candidate;
        }
        if candidate.is_none() {
            self.held_seconds = 0.0;
            if grip && (a || b) {
                self.consumed = true;
            }
            return None;
        }
        self.held_seconds += dt;
        if self.held_seconds < 0.75 {
            return None;
        }
        self.consumed = true;
        candidate
    }

    pub(crate) fn suppress_buttons(&self) -> bool {
        self.suppress_buttons
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum PresentationState {
    #[default]
    Experimenter,
    Transition,
    ImmersiveActive,
    Developer,
    Unfocused,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum CompletionProgress {
    #[default]
    NotReached,
    PersistencePending,
    Durable,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RecordingFailure {
    Prepare,
    Finalize,
    DataLoss,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum RecordingResult {
    #[default]
    None,
    Saved {
        completed: bool,
    },
    Unsaved {
        completed: bool,
        error: RecordingFailure,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum SessionCommand {
    Arm {
        condition: ConditionKey,
        completion_threshold_ns: u64,
    },
    AudioPrepared,
    OfficialStart,
    Pause,
    Resume,
    Start {
        condition: ConditionKey,
        completion_threshold_ns: u64,
    },
    RecordingPrepared,
    RecordingPrepareFailed {
        error: RecordingFailure,
    },
    PresentationChanged(PresentationState),
    Tick,
    AudioStarted,
    AudioEnded,
    AudioError,
    CompletionDurable,
    RestartToExperimenter,
    SaveAndExit,
    RecordingFinalized {
        durable_completion: bool,
        saved: bool,
    },
    RecordingFinalizationFailed {
        durable_completion: bool,
        error: RecordingFailure,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CommandEnvelope {
    pub(crate) operation: OperationToken,
    pub(crate) expected_generation: u64,
    pub(crate) at: MonotonicNanos,
    pub(crate) command: SessionCommand,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum SessionEffect {
    RecordControl(crate::session_recording_contract::SessionEventKind),
    PrepareRecording {
        generation: u64,
        condition: ConditionKey,
        completion_threshold_ns: u64,
    },
    RecordPresentation(PresentationState),
    RecordAudioStarted,
    RecordAudioEnded,
    RecordAudioError,
    PersistCompletion {
        generation: u64,
        active_time_ns: u64,
    },
    FinalizeRecording {
        generation: u64,
        reason: FinalizationReason,
        threshold_reached: bool,
    },
    DisarmKiosk,
    ResetRuntimeDefaults,
    ShowExperimenter,
    ShutdownApp,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CommandRejection {
    OperationConflict,
    OperationRetired,
    StaleGeneration,
    MonotonicTimeRegression,
    InvalidPhase,
    InvalidThreshold,
    CompletionNotPending,
    FinalizationCompletionMismatch,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CommandOutcome {
    pub(crate) accepted: bool,
    pub(crate) duplicate: bool,
    pub(crate) revision: u64,
    pub(crate) generation: u64,
    pub(crate) phase: SessionPhase,
    pub(crate) recording_result: RecordingResult,
    pub(crate) effects: Vec<SessionEffect>,
    pub(crate) rejection: Option<CommandRejection>,
}

#[derive(Clone, Debug)]
struct AppliedOperation {
    envelope: CommandEnvelope,
    outcome: CommandOutcome,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct ExperimentSessionController {
    phase: SessionPhase,
    control_state: ExperimentControlState,
    explicit_start: bool,
    audio_prepared: bool,
    presentation: PresentationState,
    generation: u64,
    revision: u64,
    last_monotonic: Option<MonotonicNanos>,
    active_since: Option<MonotonicNanos>,
    active_time_ns: u64,
    completion_threshold_ns: u64,
    completion: CompletionProgress,
    condition: Option<ConditionKey>,
    audio_started: bool,
    audio_ended: bool,
    audio_error: bool,
    recording_result: RecordingResult,
    operations: VecDeque<AppliedOperation>,
    retired_operation_floor: u64,
}

impl ExperimentSessionController {
    pub(crate) fn control_state(&self) -> ExperimentControlState {
        self.control_state
    }
    pub(crate) fn phase(&self) -> SessionPhase {
        self.phase
    }

    pub(crate) fn presentation(&self) -> PresentationState {
        self.presentation
    }

    pub(crate) fn generation(&self) -> u64 {
        self.generation
    }

    pub(crate) fn active_time_ns(&self) -> u64 {
        self.active_time_ns
    }

    pub(crate) fn completion(&self) -> CompletionProgress {
        self.completion
    }

    pub(crate) fn audio_ended(&self) -> bool {
        self.audio_ended
    }

    #[cfg(test)]
    fn audio_started(&self) -> bool {
        self.audio_started
    }

    pub(crate) fn audio_error(&self) -> bool {
        self.audio_error
    }

    pub(crate) fn recording_result(&self) -> RecordingResult {
        self.recording_result
    }

    /// The elapsed-realtime deadline at which a worker-owned tick must be
    /// applied. Paused/non-active presentations deliberately have no live
    /// deadline: only time spent in `ImmersiveActive` counts toward completion.
    pub(crate) fn completion_deadline(&self) -> Option<MonotonicNanos> {
        if self.phase != SessionPhase::Active
            || self.control_state != ExperimentControlState::Running
            || self.presentation != PresentationState::ImmersiveActive
            || self.completion != CompletionProgress::NotReached
        {
            return None;
        }
        let active_since = self.active_since?;
        Some(MonotonicNanos::new(
            active_since.get().saturating_add(
                self.completion_threshold_ns
                    .saturating_sub(self.active_time_ns),
            ),
        ))
    }

    pub(crate) fn apply(&mut self, envelope: CommandEnvelope) -> CommandOutcome {
        if let Some(previous) = self
            .operations
            .iter()
            .find(|entry| entry.envelope.operation == envelope.operation)
        {
            if previous.envelope == envelope {
                let mut outcome = previous.outcome.clone();
                outcome.duplicate = true;
                return outcome;
            }
            return self.rejected(CommandRejection::OperationConflict);
        }
        if envelope.operation.get() <= self.retired_operation_floor {
            return self.rejected(CommandRejection::OperationRetired);
        }
        if envelope.expected_generation != self.generation {
            return self.record_rejection(envelope, CommandRejection::StaleGeneration);
        }
        if self
            .last_monotonic
            .is_some_and(|previous| envelope.at < previous)
        {
            return self.record_rejection(envelope, CommandRejection::MonotonicTimeRegression);
        }

        let result = self.reduce(&envelope);
        let outcome = match result {
            Ok(effects) => {
                self.last_monotonic = Some(envelope.at);
                self.revision = self.revision.saturating_add(1);
                CommandOutcome {
                    accepted: true,
                    duplicate: false,
                    revision: self.revision,
                    generation: self.generation,
                    phase: self.phase,
                    recording_result: self.recording_result,
                    effects,
                    rejection: None,
                }
            }
            Err(rejection) => self.rejected(rejection),
        };
        self.remember(envelope, outcome.clone());
        outcome
    }

    fn reduce(
        &mut self,
        envelope: &CommandEnvelope,
    ) -> Result<Vec<SessionEffect>, CommandRejection> {
        let at = envelope.at;
        match &envelope.command {
            SessionCommand::AudioPrepared => {
                if self.phase != SessionPhase::Active
                    || self.control_state != ExperimentControlState::Arming
                {
                    return Err(CommandRejection::InvalidPhase);
                }
                self.audio_prepared = true;
                self.control_state = ExperimentControlState::Armed;
                Ok(vec![SessionEffect::RecordControl(
                    crate::session_recording_contract::SessionEventKind::Armed,
                )])
            }
            SessionCommand::OfficialStart | SessionCommand::Pause | SessionCommand::Resume => {
                use crate::session_recording_contract::SessionEventKind;
                let (required, next, event) = match envelope.command {
                    SessionCommand::OfficialStart => (
                        ExperimentControlState::Armed,
                        ExperimentControlState::Running,
                        SessionEventKind::OfficialStart,
                    ),
                    SessionCommand::Pause => (
                        ExperimentControlState::Running,
                        ExperimentControlState::Paused,
                        SessionEventKind::ExperimentPaused,
                    ),
                    _ => (
                        ExperimentControlState::Paused,
                        ExperimentControlState::Running,
                        SessionEventKind::ExperimentResumed,
                    ),
                };
                if self.phase != SessionPhase::Active || self.control_state != required {
                    return Err(CommandRejection::InvalidPhase);
                }
                if matches!(envelope.command, SessionCommand::Resume) && self.audio_error {
                    return Err(CommandRejection::InvalidPhase);
                }
                // Start/resume requires confirmed immersive presentation. Losing focus never
                // constitutes an operator start or resume.
                if next == ExperimentControlState::Running
                    && self.presentation != PresentationState::ImmersiveActive
                {
                    return Err(CommandRejection::InvalidPhase);
                }
                let mut effects = self.advance_active_time(at);
                self.control_state = next;
                if next == ExperimentControlState::Running {
                    self.audio_error = false;
                }
                self.active_since = (next == ExperimentControlState::Running).then_some(at);
                effects.push(SessionEffect::RecordControl(event));
                Ok(effects)
            }
            SessionCommand::Arm {
                condition,
                completion_threshold_ns,
            }
            | SessionCommand::Start {
                condition,
                completion_threshold_ns,
            } => {
                if self.phase != SessionPhase::Idle {
                    return Err(CommandRejection::InvalidPhase);
                }
                if *completion_threshold_ns == 0 {
                    return Err(CommandRejection::InvalidThreshold);
                }
                self.generation = self.generation.saturating_add(1).max(1);
                self.phase = SessionPhase::Preparing;
                self.explicit_start = matches!(envelope.command, SessionCommand::Arm { .. });
                self.control_state = ExperimentControlState::Arming;
                self.audio_prepared = false;
                self.presentation = PresentationState::Transition;
                self.active_since = None;
                self.active_time_ns = 0;
                self.completion_threshold_ns = *completion_threshold_ns;
                self.completion = CompletionProgress::NotReached;
                self.condition = Some(condition.clone());
                self.audio_started = false;
                self.audio_ended = false;
                self.audio_error = false;
                self.recording_result = RecordingResult::None;
                Ok(vec![SessionEffect::PrepareRecording {
                    generation: self.generation,
                    condition: condition.clone(),
                    completion_threshold_ns: *completion_threshold_ns,
                }])
            }
            SessionCommand::RecordingPrepared => {
                if self.phase != SessionPhase::Preparing {
                    return Err(CommandRejection::InvalidPhase);
                }
                self.phase = SessionPhase::Active;
                self.control_state = if self.explicit_start {
                    ExperimentControlState::Arming
                } else {
                    ExperimentControlState::Running
                };
                Ok(Vec::new())
            }
            SessionCommand::RecordingPrepareFailed { error } => {
                if !matches!(self.phase, SessionPhase::Preparing | SessionPhase::Exiting) {
                    return Err(CommandRejection::InvalidPhase);
                }
                self.recording_result = RecordingResult::Unsaved {
                    completed: false,
                    error: *error,
                };
                if self.phase == SessionPhase::Exiting {
                    self.phase = SessionPhase::Closed;
                    self.control_state = ExperimentControlState::Idle;
                    self.presentation = PresentationState::Unfocused;
                    Ok(vec![SessionEffect::ShutdownApp])
                } else {
                    self.phase = SessionPhase::Idle;
                    self.control_state = ExperimentControlState::Idle;
                    self.presentation = PresentationState::Experimenter;
                    self.condition = None;
                    Ok(vec![
                        SessionEffect::ResetRuntimeDefaults,
                        SessionEffect::ShowExperimenter,
                    ])
                }
            }
            SessionCommand::PresentationChanged(presentation) => {
                if self.phase != SessionPhase::Active {
                    return Err(CommandRejection::InvalidPhase);
                }
                let mut effects = self.advance_active_time(at);
                self.presentation = *presentation;
                self.active_since = (*presentation == PresentationState::ImmersiveActive
                    && self.control_state == ExperimentControlState::Running)
                    .then_some(at);
                effects.push(SessionEffect::RecordPresentation(*presentation));
                Ok(effects)
            }
            SessionCommand::Tick => {
                if self.phase != SessionPhase::Active {
                    return Err(CommandRejection::InvalidPhase);
                }
                Ok(self.advance_active_time(at))
            }
            SessionCommand::AudioStarted => {
                if self.phase != SessionPhase::Active {
                    return Err(CommandRejection::InvalidPhase);
                }
                let mut effects = self.advance_active_time(at);
                if !self.audio_started {
                    self.audio_started = true;
                    effects.push(SessionEffect::RecordAudioStarted);
                }
                Ok(effects)
            }
            SessionCommand::AudioEnded => {
                if self.phase != SessionPhase::Active {
                    return Err(CommandRejection::InvalidPhase);
                }
                let mut effects = self.advance_active_time(at);
                if !self.audio_ended {
                    self.audio_ended = true;
                    effects.push(SessionEffect::RecordAudioEnded);
                }
                Ok(effects)
            }
            SessionCommand::AudioError => {
                if self.phase != SessionPhase::Active {
                    return Err(CommandRejection::InvalidPhase);
                }
                let mut effects = self.advance_active_time(at);
                if !self.audio_error {
                    self.audio_error = true;
                    effects.push(SessionEffect::RecordAudioError);
                }
                if self.explicit_start && self.control_state == ExperimentControlState::Running {
                    self.control_state = ExperimentControlState::Paused;
                    self.active_since = None;
                    effects.push(SessionEffect::RecordControl(
                        crate::session_recording_contract::SessionEventKind::ExperimentPaused,
                    ));
                }
                Ok(effects)
            }
            SessionCommand::CompletionDurable => {
                if !matches!(
                    self.phase,
                    SessionPhase::Active | SessionPhase::FinalizingRestart | SessionPhase::Exiting
                ) || self.completion != CompletionProgress::PersistencePending
                {
                    return Err(CommandRejection::CompletionNotPending);
                }
                self.completion = CompletionProgress::Durable;
                Ok(Vec::new())
            }
            SessionCommand::RestartToExperimenter => {
                if !matches!(self.phase, SessionPhase::Preparing | SessionPhase::Active) {
                    return Err(CommandRejection::InvalidPhase);
                }
                let mut effects = if self.phase == SessionPhase::Active {
                    self.advance_active_time(at)
                } else {
                    Vec::new()
                };
                self.active_since = None;
                self.phase = SessionPhase::FinalizingRestart;
                self.control_state = ExperimentControlState::Finalizing;
                self.presentation = PresentationState::Experimenter;
                effects.push(SessionEffect::FinalizeRecording {
                    generation: self.generation,
                    reason: FinalizationReason::RestartToExperimenter,
                    threshold_reached: self.completion != CompletionProgress::NotReached,
                });
                Ok(effects)
            }
            SessionCommand::SaveAndExit => {
                let mut effects = vec![SessionEffect::DisarmKiosk];
                if self.phase == SessionPhase::Closed || self.phase == SessionPhase::Exiting {
                    return Ok(effects);
                }
                if self.phase == SessionPhase::FinalizingRestart {
                    self.phase = SessionPhase::Exiting;
                    self.presentation = PresentationState::Unfocused;
                    return Ok(effects);
                }
                if self.phase == SessionPhase::Active {
                    effects.extend(self.advance_active_time(at));
                }
                self.active_since = None;
                self.presentation = PresentationState::Unfocused;
                if matches!(self.phase, SessionPhase::Preparing | SessionPhase::Active) {
                    self.phase = SessionPhase::Exiting;
                    self.control_state = ExperimentControlState::Finalizing;
                    effects.push(SessionEffect::FinalizeRecording {
                        generation: self.generation,
                        reason: FinalizationReason::SaveAndExit,
                        threshold_reached: self.completion != CompletionProgress::NotReached,
                    });
                } else if self.phase == SessionPhase::Idle {
                    self.phase = SessionPhase::Closed;
                    effects.push(SessionEffect::ShutdownApp);
                } else {
                    return Err(CommandRejection::InvalidPhase);
                }
                Ok(effects)
            }
            SessionCommand::RecordingFinalized {
                durable_completion,
                saved,
            } => {
                if !matches!(
                    self.phase,
                    SessionPhase::FinalizingRestart | SessionPhase::Exiting
                ) {
                    return Err(CommandRejection::InvalidPhase);
                }
                let threshold_reached = self.completion != CompletionProgress::NotReached;
                if threshold_reached != *durable_completion {
                    return Err(CommandRejection::FinalizationCompletionMismatch);
                }
                self.recording_result = if *saved {
                    RecordingResult::Saved {
                        completed: *durable_completion,
                    }
                } else {
                    RecordingResult::Unsaved {
                        completed: *durable_completion,
                        error: RecordingFailure::DataLoss,
                    }
                };
                Ok(self.finish_terminal_transition())
            }
            SessionCommand::RecordingFinalizationFailed {
                durable_completion,
                error,
            } => {
                if !matches!(
                    self.phase,
                    SessionPhase::FinalizingRestart | SessionPhase::Exiting
                ) {
                    return Err(CommandRejection::InvalidPhase);
                }
                let actually_durable = self.completion == CompletionProgress::Durable;
                if actually_durable != *durable_completion {
                    return Err(CommandRejection::FinalizationCompletionMismatch);
                }
                self.recording_result = RecordingResult::Unsaved {
                    completed: *durable_completion,
                    error: *error,
                };
                Ok(self.finish_terminal_transition())
            }
        }
    }

    fn finish_terminal_transition(&mut self) -> Vec<SessionEffect> {
        self.control_state = ExperimentControlState::Idle;
        if self.phase == SessionPhase::FinalizingRestart {
            self.phase = SessionPhase::Idle;
            self.presentation = PresentationState::Experimenter;
            self.condition = None;
            self.completion = CompletionProgress::NotReached;
            self.completion_threshold_ns = 0;
            self.active_time_ns = 0;
            self.audio_started = false;
            self.audio_ended = false;
            self.audio_error = false;
            vec![
                SessionEffect::ResetRuntimeDefaults,
                SessionEffect::ShowExperimenter,
            ]
        } else {
            self.phase = SessionPhase::Closed;
            vec![SessionEffect::ShutdownApp]
        }
    }

    fn advance_active_time(&mut self, at: MonotonicNanos) -> Vec<SessionEffect> {
        if self.presentation != PresentationState::ImmersiveActive
            || self.control_state != ExperimentControlState::Running
        {
            self.active_since = None;
            return Vec::new();
        }
        let Some(started) = self.active_since else {
            self.active_since = Some(at);
            return Vec::new();
        };
        self.active_since = Some(at);
        let Some(delta) = at.checked_duration_since(started) else {
            return Vec::new();
        };
        self.active_time_ns = self.active_time_ns.saturating_add(delta);
        if self.completion == CompletionProgress::NotReached
            && self.active_time_ns >= self.completion_threshold_ns
        {
            self.completion = CompletionProgress::PersistencePending;
            return vec![SessionEffect::PersistCompletion {
                generation: self.generation,
                active_time_ns: self.active_time_ns,
            }];
        }
        Vec::new()
    }

    fn rejected(&self, rejection: CommandRejection) -> CommandOutcome {
        CommandOutcome {
            accepted: false,
            duplicate: false,
            revision: self.revision,
            generation: self.generation,
            phase: self.phase,
            recording_result: self.recording_result,
            effects: Vec::new(),
            rejection: Some(rejection),
        }
    }

    fn record_rejection(
        &mut self,
        envelope: CommandEnvelope,
        rejection: CommandRejection,
    ) -> CommandOutcome {
        let outcome = self.rejected(rejection);
        self.remember(envelope, outcome.clone());
        outcome
    }

    fn remember(&mut self, envelope: CommandEnvelope, outcome: CommandOutcome) {
        if self.operations.len() == OPERATION_HISTORY_CAPACITY {
            if let Some(retired) = self.operations.pop_front() {
                self.retired_operation_floor = self
                    .retired_operation_floor
                    .max(retired.envelope.operation.get());
            }
        }
        self.operations
            .push_back(AppliedOperation { envelope, outcome });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn explicit_arm_waits_for_audio_and_excludes_preroll_and_paused_time() {
        let mut c = ExperimentSessionController::default();
        assert!(
            c.apply(command(
                1,
                0,
                1,
                SessionCommand::Arm {
                    condition: condition(),
                    completion_threshold_ns: 100
                }
            ))
            .accepted
        );
        assert!(
            c.apply(command(2, 1, 2, SessionCommand::RecordingPrepared))
                .accepted
        );
        assert_eq!(c.control_state(), ExperimentControlState::Arming);
        assert!(
            !c.apply(command(3, 1, 3, SessionCommand::OfficialStart))
                .accepted
        );
        assert!(
            c.apply(command(4, 1, 4, SessionCommand::AudioPrepared))
                .accepted
        );
        assert_eq!(c.control_state(), ExperimentControlState::Armed);
        assert!(
            c.apply(command(
                5,
                1,
                5,
                SessionCommand::PresentationChanged(PresentationState::ImmersiveActive)
            ))
            .accepted
        );
        c.apply(command(6, 1, 500, SessionCommand::Tick));
        assert_eq!(c.active_time_ns(), 0);
        assert!(c.completion_deadline().is_none());
        assert!(
            c.apply(command(7, 1, 501, SessionCommand::OfficialStart))
                .accepted
        );
        let pause = command(8, 1, 511, SessionCommand::Pause);
        assert!(c.apply(pause.clone()).accepted);
        assert!(c.apply(pause).duplicate);
        c.apply(command(9, 1, 1000, SessionCommand::Tick));
        c.apply(command(
            10,
            1,
            1001,
            SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
        ));
        assert_eq!(c.control_state(), ExperimentControlState::Paused);
        assert_eq!(c.active_time_ns(), 10);
        assert!(
            c.apply(command(11, 1, 1100, SessionCommand::Resume))
                .accepted
        );
        c.apply(command(12, 1, 1190, SessionCommand::Tick));
        assert_eq!(c.active_time_ns(), 100);
        assert_eq!(c.completion(), CompletionProgress::PersistencePending);
    }

    #[test]
    fn audio_technical_hold_cannot_be_resumed() {
        let mut c = ExperimentSessionController::default();
        assert!(
            c.apply(command(
                1,
                0,
                1,
                SessionCommand::Arm {
                    condition: condition(),
                    completion_threshold_ns: 100,
                },
            ))
            .accepted
        );
        assert!(
            c.apply(command(2, 1, 2, SessionCommand::RecordingPrepared))
                .accepted
        );
        assert!(
            c.apply(command(3, 1, 3, SessionCommand::AudioPrepared))
                .accepted
        );
        assert!(
            c.apply(command(
                4,
                1,
                4,
                SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
            ))
            .accepted
        );
        assert!(
            c.apply(command(5, 1, 5, SessionCommand::OfficialStart))
                .accepted
        );
        assert!(
            c.apply(command(6, 1, 6, SessionCommand::AudioError))
                .accepted
        );
        assert_eq!(c.control_state(), ExperimentControlState::Paused);
        assert!(!c.apply(command(7, 1, 7, SessionCommand::Resume)).accepted);
        assert!(c.audio_error());
        assert_eq!(c.control_state(), ExperimentControlState::Paused);
    }

    #[test]
    fn grip_chords_are_gated_debounced_and_consumed_until_release() {
        let mut g = ExperimentControlGesture::default();
        for _ in 0..4 {
            assert_eq!(
                g.update(0.2, true, true, false, ExperimentControlState::Idle),
                None
            );
        }
        assert!(g.suppress_buttons());
        g.update(0.1, false, false, false, ExperimentControlState::Armed);
        for _ in 0..3 {
            assert_eq!(
                g.update(0.2, true, true, false, ExperimentControlState::Armed),
                None
            );
        }
        assert_eq!(
            g.update(0.2, true, true, false, ExperimentControlState::Armed),
            Some("official-start")
        );
        assert_eq!(
            g.update(0.2, true, false, true, ExperimentControlState::Running),
            None
        );
        g.update(0.1, false, false, true, ExperimentControlState::Running);
        assert!(g.suppress_buttons());
        g.update(0.1, false, false, false, ExperimentControlState::Running);
        assert!(!g.suppress_buttons());
        for _ in 0..3 {
            assert_eq!(
                g.update(0.2, true, false, true, ExperimentControlState::Running),
                None
            );
        }
        assert_eq!(
            g.update(0.2, true, false, true, ExperimentControlState::Running),
            Some("pause")
        );
    }

    fn token(value: u64) -> OperationToken {
        OperationToken::new(value).unwrap()
    }

    fn condition() -> ConditionKey {
        ConditionKey::parse("condition-a").unwrap()
    }

    fn command(
        operation: u64,
        generation: u64,
        at: u64,
        command: SessionCommand,
    ) -> CommandEnvelope {
        CommandEnvelope {
            operation: token(operation),
            expected_generation: generation,
            at: MonotonicNanos::new(at),
            command,
        }
    }

    fn start(controller: &mut ExperimentSessionController) -> u64 {
        let outcome = controller.apply(command(
            1,
            0,
            0,
            SessionCommand::Start {
                condition: condition(),
                completion_threshold_ns: 30_000_000_000,
            },
        ));
        assert!(outcome.accepted);
        let generation = controller.generation();
        assert!(
            controller
                .apply(command(2, generation, 1, SessionCommand::RecordingPrepared))
                .accepted
        );
        generation
    }

    #[test]
    fn threshold_marks_completion_without_terminating() {
        let mut controller = ExperimentSessionController::default();
        let generation = start(&mut controller);
        controller.apply(command(
            3,
            generation,
            10,
            SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
        ));
        let before = controller.apply(command(
            4,
            generation,
            29_999_999_999 + 10,
            SessionCommand::Tick,
        ));
        assert!(before.effects.is_empty());
        let reached = controller.apply(command(
            5,
            generation,
            30_000_000_000 + 10,
            SessionCommand::Tick,
        ));
        assert!(matches!(
            reached.effects.as_slice(),
            [SessionEffect::PersistCompletion { .. }]
        ));
        assert_eq!(controller.phase(), SessionPhase::Active);
        assert_eq!(
            controller.completion(),
            CompletionProgress::PersistencePending
        );
        assert!(
            controller
                .apply(command(
                    6,
                    generation,
                    30_000_000_011,
                    SessionCommand::CompletionDurable
                ))
                .accepted
        );
        assert_eq!(controller.completion(), CompletionProgress::Durable);
        assert_eq!(controller.phase(), SessionPhase::Active);
    }

    #[test]
    fn terminal_threshold_crossing_accepts_completion_receipt_before_finalize() {
        for terminal in [
            SessionCommand::RestartToExperimenter,
            SessionCommand::SaveAndExit,
        ] {
            let mut controller = ExperimentSessionController::default();
            let generation = controller
                .apply(command(
                    1,
                    0,
                    0,
                    SessionCommand::Start {
                        condition: condition(),
                        completion_threshold_ns: 10,
                    },
                ))
                .generation;
            assert!(
                controller
                    .apply(command(2, generation, 1, SessionCommand::RecordingPrepared))
                    .accepted
            );
            assert!(
                controller
                    .apply(command(
                        3,
                        generation,
                        2,
                        SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
                    ))
                    .accepted
            );
            let terminal_outcome = controller.apply(command(4, generation, 12, terminal));
            assert!(matches!(
                terminal_outcome.effects.as_slice(),
                [
                    SessionEffect::PersistCompletion { .. },
                    SessionEffect::FinalizeRecording { .. }
                ] | [
                    SessionEffect::DisarmKiosk,
                    SessionEffect::PersistCompletion { .. },
                    SessionEffect::FinalizeRecording { .. }
                ]
            ));
            assert!(
                controller
                    .apply(command(
                        5,
                        generation,
                        12,
                        SessionCommand::CompletionDurable,
                    ))
                    .accepted
            );
            assert_eq!(controller.completion(), CompletionProgress::Durable);
            assert!(
                controller
                    .apply(command(
                        6,
                        generation,
                        12,
                        SessionCommand::RecordingFinalized {
                            durable_completion: true,
                            saved: true,
                        },
                    ))
                    .accepted
            );
        }
    }

    #[test]
    fn developer_and_focus_intervals_pause_active_time() {
        let mut controller = ExperimentSessionController::default();
        let generation = start(&mut controller);
        controller.apply(command(
            3,
            generation,
            100,
            SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
        ));
        controller.apply(command(
            4,
            generation,
            1_000,
            SessionCommand::PresentationChanged(PresentationState::Developer),
        ));
        controller.apply(command(
            5,
            generation,
            20_000,
            SessionCommand::PresentationChanged(PresentationState::Unfocused),
        ));
        controller.apply(command(
            6,
            generation,
            30_000,
            SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
        ));
        controller.apply(command(7, generation, 31_000, SessionCommand::Tick));
        assert_eq!(controller.active_time_ns(), 1_900);
    }

    #[test]
    fn audio_lifecycle_events_are_idempotent_and_do_not_stop_recording() {
        let mut controller = ExperimentSessionController::default();
        let generation = start(&mut controller);

        let started = controller.apply(command(3, generation, 10, SessionCommand::AudioStarted));
        assert!(started.accepted);
        assert!(started.effects.contains(&SessionEffect::RecordAudioStarted));
        assert!(controller.audio_started());

        let duplicate_start =
            controller.apply(command(4, generation, 11, SessionCommand::AudioStarted));
        assert!(duplicate_start.accepted);
        assert!(!duplicate_start
            .effects
            .contains(&SessionEffect::RecordAudioStarted));

        let ended = controller.apply(command(5, generation, 12, SessionCommand::AudioEnded));
        assert!(ended.accepted);
        assert!(ended.effects.contains(&SessionEffect::RecordAudioEnded));
        assert!(controller.audio_ended());

        let error = controller.apply(command(6, generation, 13, SessionCommand::AudioError));
        assert!(error.accepted);
        assert!(error.effects.contains(&SessionEffect::RecordAudioError));
        assert!(controller.audio_error());

        let duplicate_error =
            controller.apply(command(7, generation, 14, SessionCommand::AudioError));
        assert!(duplicate_error.accepted);
        assert!(!duplicate_error
            .effects
            .contains(&SessionEffect::RecordAudioError));
        assert_eq!(controller.phase(), SessionPhase::Active);
    }

    #[test]
    fn restart_and_exit_have_distinct_terminal_effects() {
        let mut restart = ExperimentSessionController::default();
        let generation = start(&mut restart);
        let stopping = restart.apply(command(
            3,
            generation,
            10,
            SessionCommand::RestartToExperimenter,
        ));
        assert!(stopping.effects.iter().any(|effect| matches!(
            effect,
            SessionEffect::FinalizeRecording {
                reason: FinalizationReason::RestartToExperimenter,
                ..
            }
        )));
        let finished = restart.apply(command(
            4,
            generation,
            11,
            SessionCommand::RecordingFinalized {
                durable_completion: false,
                saved: true,
            },
        ));
        assert_eq!(restart.phase(), SessionPhase::Idle);
        assert_eq!(
            finished.effects,
            vec![
                SessionEffect::ResetRuntimeDefaults,
                SessionEffect::ShowExperimenter
            ]
        );

        let mut exit = ExperimentSessionController::default();
        let generation = start(&mut exit);
        let stopping = exit.apply(command(3, generation, 10, SessionCommand::SaveAndExit));
        assert_eq!(stopping.effects.first(), Some(&SessionEffect::DisarmKiosk));
        let finished = exit.apply(command(
            4,
            generation,
            11,
            SessionCommand::RecordingFinalized {
                durable_completion: false,
                saved: true,
            },
        ));
        assert_eq!(exit.phase(), SessionPhase::Closed);
        assert_eq!(finished.effects, vec![SessionEffect::ShutdownApp]);
    }

    #[test]
    fn home_during_restart_finalization_upgrades_to_exit_without_second_finalize() {
        let mut controller = ExperimentSessionController::default();
        let generation = start(&mut controller);
        controller.apply(command(
            3,
            generation,
            10,
            SessionCommand::RestartToExperimenter,
        ));
        let home = controller.apply(command(4, generation, 11, SessionCommand::SaveAndExit));
        assert!(home.accepted);
        assert_eq!(controller.phase(), SessionPhase::Exiting);
        assert_eq!(home.effects, vec![SessionEffect::DisarmKiosk]);
        let repeated = controller.apply(command(5, generation, 12, SessionCommand::SaveAndExit));
        assert!(repeated.accepted);
        assert_eq!(repeated.effects, vec![SessionEffect::DisarmKiosk]);
        let finished = controller.apply(command(
            6,
            generation,
            13,
            SessionCommand::RecordingFinalized {
                durable_completion: false,
                saved: true,
            },
        ));
        assert_eq!(finished.effects, vec![SessionEffect::ShutdownApp]);
        assert_eq!(controller.phase(), SessionPhase::Closed);
    }

    #[test]
    fn duplicate_is_idempotent_and_token_conflicts_fail_closed() {
        let mut controller = ExperimentSessionController::default();
        let envelope = command(
            1,
            0,
            10,
            SessionCommand::Start {
                condition: condition(),
                completion_threshold_ns: 30,
            },
        );
        let first = controller.apply(envelope.clone());
        let duplicate = controller.apply(envelope.clone());
        assert!(first.accepted);
        assert!(duplicate.accepted);
        assert!(duplicate.duplicate);
        assert_eq!(duplicate.revision, first.revision);
        let conflict = controller.apply(CommandEnvelope {
            command: SessionCommand::SaveAndExit,
            ..envelope
        });
        assert_eq!(
            conflict.rejection,
            Some(CommandRejection::OperationConflict)
        );
    }

    #[test]
    fn stale_generation_and_monotonic_regression_reject_without_mutation() {
        let mut controller = ExperimentSessionController::default();
        let generation = start(&mut controller);
        let stale = controller.apply(command(3, 0, 2, SessionCommand::Tick));
        assert_eq!(stale.rejection, Some(CommandRejection::StaleGeneration));
        let regressed = controller.apply(command(4, generation, 0, SessionCommand::Tick));
        assert_eq!(
            regressed.rejection,
            Some(CommandRejection::MonotonicTimeRegression)
        );
        assert_eq!(controller.phase(), SessionPhase::Active);
    }

    #[test]
    fn repeated_paused_commands_never_resume_active_time() {
        let mut controller = ExperimentSessionController::default();
        let generation = start(&mut controller);
        controller.apply(command(
            3,
            generation,
            100,
            SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
        ));
        controller.apply(command(4, generation, 200, SessionCommand::Tick));
        controller.apply(command(
            5,
            generation,
            300,
            SessionCommand::PresentationChanged(PresentationState::Developer),
        ));
        controller.apply(command(6, generation, 10_000, SessionCommand::Tick));
        controller.apply(command(7, generation, 20_000, SessionCommand::AudioEnded));
        controller.apply(command(8, generation, 30_000, SessionCommand::Tick));
        controller.apply(command(
            9,
            generation,
            40_000,
            SessionCommand::PresentationChanged(PresentationState::Unfocused),
        ));
        controller.apply(command(10, generation, 50_000, SessionCommand::Tick));
        assert_eq!(controller.active_time_ns(), 200);
    }

    #[test]
    fn prepare_and_finalize_failures_are_typed_and_terminal() {
        let mut prepare = ExperimentSessionController::default();
        let started = prepare.apply(command(
            1,
            0,
            0,
            SessionCommand::Start {
                condition: condition(),
                completion_threshold_ns: 30,
            },
        ));
        let failed = prepare.apply(command(
            2,
            started.generation,
            1,
            SessionCommand::RecordingPrepareFailed {
                error: RecordingFailure::Prepare,
            },
        ));
        assert!(failed.accepted);
        assert_eq!(prepare.phase(), SessionPhase::Idle);
        assert_eq!(
            prepare.recording_result(),
            RecordingResult::Unsaved {
                completed: false,
                error: RecordingFailure::Prepare
            }
        );

        let mut exit = ExperimentSessionController::default();
        let generation = start(&mut exit);
        exit.apply(command(3, generation, 10, SessionCommand::SaveAndExit));
        let failed = exit.apply(command(
            4,
            generation,
            11,
            SessionCommand::RecordingFinalizationFailed {
                durable_completion: false,
                error: RecordingFailure::Finalize,
            },
        ));
        assert!(failed.accepted);
        assert_eq!(exit.phase(), SessionPhase::Closed);
        assert_eq!(failed.effects, vec![SessionEffect::ShutdownApp]);
        assert_eq!(
            exit.recording_result(),
            RecordingResult::Unsaved {
                completed: false,
                error: RecordingFailure::Finalize
            }
        );

        let mut completed = ExperimentSessionController::default();
        completed.apply(command(
            1,
            0,
            0,
            SessionCommand::Start {
                condition: condition(),
                completion_threshold_ns: 30,
            },
        ));
        let generation = completed.generation();
        completed.apply(command(2, generation, 1, SessionCommand::RecordingPrepared));
        completed.apply(command(
            3,
            generation,
            2,
            SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
        ));
        completed.apply(command(4, generation, 32, SessionCommand::Tick));
        completed.apply(command(
            5,
            generation,
            33,
            SessionCommand::CompletionDurable,
        ));
        completed.apply(command(6, generation, 34, SessionCommand::SaveAndExit));
        completed.apply(command(
            7,
            generation,
            35,
            SessionCommand::RecordingFinalizationFailed {
                durable_completion: true,
                error: RecordingFailure::Finalize,
            },
        ));
        assert_eq!(
            completed.recording_result(),
            RecordingResult::Unsaved {
                completed: true,
                error: RecordingFailure::Finalize
            }
        );

        let mut pending = ExperimentSessionController::default();
        pending.apply(command(
            1,
            0,
            0,
            SessionCommand::Start {
                condition: condition(),
                completion_threshold_ns: 30,
            },
        ));
        let generation = pending.generation();
        pending.apply(command(2, generation, 1, SessionCommand::RecordingPrepared));
        pending.apply(command(
            3,
            generation,
            2,
            SessionCommand::PresentationChanged(PresentationState::ImmersiveActive),
        ));
        pending.apply(command(4, generation, 32, SessionCommand::Tick));
        assert_eq!(pending.completion(), CompletionProgress::PersistencePending);
        pending.apply(command(5, generation, 33, SessionCommand::SaveAndExit));
        let failed = pending.apply(command(
            6,
            generation,
            34,
            SessionCommand::RecordingFinalizationFailed {
                durable_completion: false,
                error: RecordingFailure::Finalize,
            },
        ));
        assert!(failed.accepted);
        assert_eq!(pending.phase(), SessionPhase::Closed);
        assert_eq!(
            pending.recording_result(),
            RecordingResult::Unsaved {
                completed: false,
                error: RecordingFailure::Finalize
            }
        );

        let mut prepare_during_exit = ExperimentSessionController::default();
        prepare_during_exit.apply(command(
            1,
            0,
            0,
            SessionCommand::Start {
                condition: condition(),
                completion_threshold_ns: 30,
            },
        ));
        let generation = prepare_during_exit.generation();
        prepare_during_exit.apply(command(2, generation, 1, SessionCommand::SaveAndExit));
        let failed = prepare_during_exit.apply(command(
            3,
            generation,
            2,
            SessionCommand::RecordingPrepareFailed {
                error: RecordingFailure::Prepare,
            },
        ));
        assert!(failed.accepted);
        assert_eq!(prepare_during_exit.phase(), SessionPhase::Closed);
        assert_eq!(failed.effects, vec![SessionEffect::ShutdownApp]);
        assert_eq!(
            prepare_during_exit.recording_result(),
            RecordingResult::Unsaved {
                completed: false,
                error: RecordingFailure::Prepare
            }
        );
    }
}
