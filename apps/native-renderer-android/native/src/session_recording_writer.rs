//! Single-actor durable writer for app-private experiment recordings.
//!
//! Producers perform only a short `try_lock`, typed move, and bounded
//! `try_send`. The actor owns JSON encoding, directory scans, file open/write,
//! synchronization, checkpoint publication, final receipts, and index rebuilds.

use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, AtomicUsize, Ordering},
        mpsc::{self, Receiver, RecvTimeoutError, SyncSender, TrySendError},
        Arc, Mutex, TryLockError,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use serde_json::{json, Value};

use crate::{
    session_recording_clock::MonotonicNanos,
    session_recording_contract::{
        FinalizationReason, SessionEvent, SessionEventKind, SessionRecord, SessionStartSpec,
        SESSION_CHECKPOINT_SCHEMA, SESSION_FINAL_SCHEMA, SESSION_INDEX_SCHEMA,
        SESSION_MANIFEST_SCHEMA, SESSION_STREAM_FILES,
    },
    session_recording_recovery::{
        recover_recordings, validate_recording_root_no_links, RecoveryDisposition, RecoveryReport,
    },
};

const MAX_CONTROL_POLL: Duration = Duration::from_millis(10);

#[derive(Clone, Debug)]
pub(crate) struct WriterConfig {
    pub(crate) root: PathBuf,
    pub(crate) data_queue_capacity: usize,
    pub(crate) max_batch_samples: usize,
    pub(crate) max_queued_bytes: usize,
    pub(crate) checkpoint_interval: Duration,
}

impl WriterConfig {
    pub(crate) fn validate(&self) -> Result<(), &'static str> {
        if self.data_queue_capacity == 0 {
            return Err("recording-data-queue-capacity-invalid");
        }
        if self.max_batch_samples == 0 {
            return Err("recording-max-batch-samples-invalid");
        }
        if self.max_queued_bytes == 0 {
            return Err("recording-max-queued-bytes-invalid");
        }
        if self.checkpoint_interval.is_zero() || self.checkpoint_interval > Duration::from_secs(1) {
            return Err("recording-checkpoint-interval-invalid");
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OfferOutcome {
    Accepted { sequence: u64 },
    QueueFull,
    BatchOversize,
    QueueBytesFull,
    NonFinite,
    AdmissionBusy,
    NotAccepting,
    StaleGeneration,
    WriterClosed,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct OfferReceipt {
    pub(crate) outcome: OfferOutcome,
    retry_record: Option<SessionRecord>,
}

impl OfferReceipt {
    fn consumed(outcome: OfferOutcome) -> Self {
        Self {
            outcome,
            retry_record: None,
        }
    }

    fn retry(outcome: OfferOutcome, record: SessionRecord) -> Self {
        Self {
            outcome,
            retry_record: Some(record),
        }
    }

    pub(crate) fn into_retry_record(self) -> Option<SessionRecord> {
        self.retry_record
    }
}

impl PartialEq<OfferOutcome> for OfferReceipt {
    fn eq(&self, other: &OfferOutcome) -> bool {
        self.outcome == *other
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PreparedReceipt {
    pub(crate) generation: u64,
    pub(crate) directory: PathBuf,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct CompletionReceipt {
    pub(crate) generation: u64,
    pub(crate) checkpoint_ordinal: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct FinalizedReceipt {
    pub(crate) generation: u64,
    pub(crate) directory: PathBuf,
    pub(crate) accepted_sequence_cutoff: u64,
    pub(crate) completed: bool,
    pub(crate) saved: bool,
    pub(crate) dropped_records: u64,
    pub(crate) recovery: RecoveryReport,
}

pub(crate) struct ControlReceipt<T> {
    receiver: Receiver<Result<T, String>>,
}

impl<T> ControlReceipt<T> {
    pub(crate) fn try_receive(&self) -> Result<Option<Result<T, String>>, String> {
        match self.receiver.try_recv() {
            Ok(value) => Ok(Some(value)),
            Err(mpsc::TryRecvError::Empty) => Ok(None),
            Err(mpsc::TryRecvError::Disconnected) => {
                Err("recording-control-disconnected".to_owned())
            }
        }
    }

    /// Blocking is permitted only on the dedicated session-control owner or in
    /// host tests, never on Android UI, OpenXR, render, or BLE callback threads.
    pub(crate) fn receive_timeout(self, timeout: Duration) -> Result<T, String> {
        self.receiver
            .recv_timeout(timeout)
            .map_err(|error| format!("recording-control-receive:{error}"))?
    }
}

#[derive(Debug)]
struct QueuedRecord {
    generation: u64,
    sequence: u64,
    payload_bytes: usize,
    record: SessionRecord,
}

#[derive(Debug, Default)]
struct AdmissionState {
    generation: Option<u64>,
    accepting: bool,
    finalize_requested: bool,
    next_sequence: u64,
    last_accepted_sequence: u64,
    next_offer_sequence: u64,
    loss: LossSnapshot,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct LossSnapshot {
    dropped_records: u64,
    streams: Vec<&'static str>,
    first_offer_sequence: Option<u64>,
    last_offer_sequence: Option<u64>,
    first_observed_monotonic_ns: Option<u64>,
    last_observed_monotonic_ns: Option<u64>,
}

#[derive(Debug)]
struct ExternalLossLedger {
    generation: AtomicU64,
    dropped_records: AtomicU64,
    stream_mask: AtomicU64,
    next_sequence: AtomicU64,
    first_sequence: AtomicU64,
    last_sequence: AtomicU64,
    first_observed: AtomicU64,
    last_observed: AtomicU64,
}

impl Default for ExternalLossLedger {
    fn default() -> Self {
        Self {
            generation: AtomicU64::new(0),
            dropped_records: AtomicU64::new(0),
            stream_mask: AtomicU64::new(0),
            next_sequence: AtomicU64::new(0),
            first_sequence: AtomicU64::new(u64::MAX),
            last_sequence: AtomicU64::new(0),
            first_observed: AtomicU64::new(u64::MAX),
            last_observed: AtomicU64::new(0),
        }
    }
}

impl ExternalLossLedger {
    fn reset(&self, generation: u64) {
        self.generation.store(0, Ordering::Release);
        self.dropped_records.store(0, Ordering::Release);
        self.stream_mask.store(0, Ordering::Release);
        self.next_sequence.store(0, Ordering::Release);
        self.first_sequence.store(u64::MAX, Ordering::Release);
        self.last_sequence.store(0, Ordering::Release);
        self.first_observed.store(u64::MAX, Ordering::Release);
        self.last_observed.store(0, Ordering::Release);
        self.generation.store(generation, Ordering::Release);
    }

    fn record(&self, generation: u64, record: &SessionRecord) -> bool {
        if generation == 0 || self.generation.load(Ordering::Acquire) != generation {
            return false;
        }
        let sequence = self
            .next_sequence
            .fetch_add(1, Ordering::AcqRel)
            .saturating_add(1);
        let stream_bit = SESSION_STREAM_FILES
            .iter()
            .position(|name| *name == record.stream_file())
            .map_or(0, |index| 1_u64 << index);
        let observed = record.observed_at().get();
        self.stream_mask.fetch_or(stream_bit, Ordering::AcqRel);
        self.first_sequence.fetch_min(sequence, Ordering::AcqRel);
        self.last_sequence.fetch_max(sequence, Ordering::AcqRel);
        self.first_observed.fetch_min(observed, Ordering::AcqRel);
        self.last_observed.fetch_max(observed, Ordering::AcqRel);
        self.dropped_records.fetch_add(1, Ordering::AcqRel);
        self.generation.load(Ordering::Acquire) == generation
    }

    fn snapshot(&self, generation: u64) -> LossSnapshot {
        if self.generation.load(Ordering::Acquire) != generation {
            return LossSnapshot::default();
        }
        let dropped_records = self.dropped_records.load(Ordering::Acquire);
        if dropped_records == 0 {
            return LossSnapshot::default();
        }
        let mask = self.stream_mask.load(Ordering::Acquire);
        LossSnapshot {
            dropped_records,
            streams: SESSION_STREAM_FILES
                .iter()
                .enumerate()
                .filter_map(|(index, name)| ((mask & (1_u64 << index)) != 0).then_some(*name))
                .collect(),
            first_offer_sequence: Some(self.first_sequence.load(Ordering::Acquire)),
            last_offer_sequence: Some(self.last_sequence.load(Ordering::Acquire)),
            first_observed_monotonic_ns: Some(self.first_observed.load(Ordering::Acquire)),
            last_observed_monotonic_ns: Some(self.last_observed.load(Ordering::Acquire)),
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct ExternalLossRecorder(Arc<ExternalLossLedger>);

impl ExternalLossRecorder {
    pub(crate) fn record(&self, generation: u64, record: &SessionRecord) -> bool {
        self.0.record(generation, record)
    }
}

impl LossSnapshot {
    fn record_drop(&mut self, offer_sequence: u64, record: &SessionRecord) {
        self.dropped_records = self.dropped_records.saturating_add(1);
        let stream = record.stream_file();
        if !self.streams.contains(&stream) {
            self.streams.push(stream);
            self.streams.sort_by_key(|name| {
                SESSION_STREAM_FILES
                    .iter()
                    .position(|candidate| candidate == name)
                    .unwrap_or(usize::MAX)
            });
        }
        self.first_offer_sequence = Some(
            self.first_offer_sequence
                .map_or(offer_sequence, |value| value.min(offer_sequence)),
        );
        self.last_offer_sequence = Some(
            self.last_offer_sequence
                .map_or(offer_sequence, |value| value.max(offer_sequence)),
        );
        let observed = record.observed_at().get();
        self.first_observed_monotonic_ns = Some(
            self.first_observed_monotonic_ns
                .map_or(observed, |value| value.min(observed)),
        );
        self.last_observed_monotonic_ns = Some(
            self.last_observed_monotonic_ns
                .map_or(observed, |value| value.max(observed)),
        );
    }
}

enum ControlCommand {
    RecordControl {
        generation: u64,
        at: MonotonicNanos,
        kind: SessionEventKind,
        reply: mpsc::Sender<Result<(), String>>,
    },
    Prepare {
        generation: u64,
        spec: SessionStartSpec,
        reply: mpsc::Sender<Result<PreparedReceipt, String>>,
    },
    MarkCompletion {
        generation: u64,
        at: MonotonicNanos,
        reply: mpsc::Sender<Result<CompletionReceipt, String>>,
    },
    Finalize {
        generation: u64,
        cutoff: u64,
        loss: LossSnapshot,
        at: MonotonicNanos,
        reason: FinalizationReason,
        threshold_reached: bool,
        reply: mpsc::Sender<Result<FinalizedReceipt, String>>,
    },
    Recover {
        reply: mpsc::Sender<Result<RecoveryReport, String>>,
    },
    Shutdown {
        reply: mpsc::Sender<Result<(), String>>,
    },
}

pub(crate) struct SessionRecordingWriter {
    control_sender: mpsc::Sender<ControlCommand>,
    data_sender: SyncSender<QueuedRecord>,
    admission: Arc<Mutex<AdmissionState>>,
    queued_bytes: Arc<AtomicUsize>,
    external_loss: Arc<ExternalLossLedger>,
    max_batch_samples: usize,
    max_queued_bytes: usize,
    actor: Option<JoinHandle<()>>,
}

impl SessionRecordingWriter {
    pub(crate) fn spawn(config: WriterConfig) -> Result<Self, String> {
        config.validate().map_err(str::to_owned)?;
        let (control_sender, control_receiver) = mpsc::channel();
        let (data_sender, data_receiver) = mpsc::sync_channel(config.data_queue_capacity);
        let admission = Arc::new(Mutex::new(AdmissionState::default()));
        let actor_admission = Arc::clone(&admission);
        let queued_bytes = Arc::new(AtomicUsize::new(0));
        let external_loss = Arc::new(ExternalLossLedger::default());
        let actor_external_loss = Arc::clone(&external_loss);
        let actor_queued_bytes = Arc::clone(&queued_bytes);
        let max_batch_samples = config.max_batch_samples;
        let max_queued_bytes = config.max_queued_bytes;
        let actor = thread::Builder::new()
            .name("experiment-session-writer".to_owned())
            .spawn(move || {
                run_actor(
                    config,
                    control_receiver,
                    data_receiver,
                    actor_admission,
                    actor_queued_bytes,
                    actor_external_loss,
                );
            })
            .map_err(|error| format!("recording-writer-spawn:{error}"))?;
        Ok(Self {
            control_sender,
            data_sender,
            admission,
            queued_bytes,
            external_loss,
            max_batch_samples,
            max_queued_bytes,
            actor: Some(actor),
        })
    }

    pub(crate) fn prepare(
        &self,
        generation: u64,
        spec: SessionStartSpec,
    ) -> Result<ControlReceipt<PreparedReceipt>, String> {
        let (reply, receiver) = mpsc::channel();
        self.control_sender
            .send(ControlCommand::Prepare {
                generation,
                spec,
                reply,
            })
            .map_err(|_| "recording-writer-closed".to_owned())?;
        Ok(ControlReceipt { receiver })
    }

    pub(crate) fn try_offer(&self, generation: u64, record: SessionRecord) -> OfferReceipt {
        offer_to_queue(
            &self.admission,
            &self.data_sender,
            &self.queued_bytes,
            self.max_batch_samples,
            self.max_queued_bytes,
            generation,
            record,
        )
    }

    /// Lock-free loss ownership for a current-generation producer record that
    /// cannot enter the bounded retry lane. The recording actor merges this
    /// ledger into every checkpoint/final receipt.
    pub(crate) fn record_external_loss(&self, generation: u64, record: &SessionRecord) -> bool {
        self.external_loss.record(generation, record)
    }

    pub(crate) fn external_loss_recorder(&self) -> ExternalLossRecorder {
        ExternalLossRecorder(Arc::clone(&self.external_loss))
    }

    pub(crate) fn mark_completion(
        &self,
        generation: u64,
        at: MonotonicNanos,
    ) -> Result<ControlReceipt<CompletionReceipt>, String> {
        let (reply, receiver) = mpsc::channel();
        self.control_sender
            .send(ControlCommand::MarkCompletion {
                generation,
                at,
                reply,
            })
            .map_err(|_| "recording-writer-closed".to_owned())?;
        Ok(ControlReceipt { receiver })
    }

    pub(crate) fn record_control(
        &self,
        generation: u64,
        at: MonotonicNanos,
        kind: SessionEventKind,
    ) -> Result<ControlReceipt<()>, String> {
        let (reply, receiver) = mpsc::channel();
        self.control_sender
            .send(ControlCommand::RecordControl {
                generation,
                at,
                kind,
                reply,
            })
            .map_err(|_| "recording-writer-closed".to_owned())?;
        Ok(ControlReceipt { receiver })
    }

    pub(crate) fn try_finalize(
        &self,
        generation: u64,
        at: MonotonicNanos,
        reason: FinalizationReason,
        threshold_reached: bool,
    ) -> Result<ControlReceipt<FinalizedReceipt>, OfferOutcome> {
        let (cutoff, loss) = match self.admission.try_lock() {
            Ok(mut state) => {
                if state.generation != Some(generation) {
                    return Err(OfferOutcome::StaleGeneration);
                }
                if state.finalize_requested {
                    return Err(OfferOutcome::NotAccepting);
                }
                state.accepting = false;
                state.finalize_requested = true;
                (
                    state.last_accepted_sequence,
                    merge_loss(state.loss.clone(), self.external_loss.snapshot(generation)),
                )
            }
            Err(TryLockError::WouldBlock) => return Err(OfferOutcome::AdmissionBusy),
            Err(TryLockError::Poisoned(_)) => return Err(OfferOutcome::WriterClosed),
        };
        let (reply, receiver) = mpsc::channel();
        if self
            .control_sender
            .send(ControlCommand::Finalize {
                generation,
                cutoff,
                loss,
                at,
                reason,
                threshold_reached,
                reply,
            })
            .is_err()
        {
            return Err(OfferOutcome::WriterClosed);
        }
        Ok(ControlReceipt { receiver })
    }

    pub(crate) fn recover(&self) -> Result<ControlReceipt<RecoveryReport>, String> {
        let (reply, receiver) = mpsc::channel();
        self.control_sender
            .send(ControlCommand::Recover { reply })
            .map_err(|_| "recording-writer-closed".to_owned())?;
        Ok(ControlReceipt { receiver })
    }

    /// Shut down and join the actor. The app-lifetime control worker owns this
    /// call; platform adapters must never invoke it on UI/render/BLE threads.
    pub(crate) fn shutdown(mut self, timeout: Duration) -> Result<(), String> {
        let (reply, receiver) = mpsc::channel();
        self.control_sender
            .send(ControlCommand::Shutdown { reply })
            .map_err(|_| "recording-writer-closed".to_owned())?;
        receiver
            .recv_timeout(timeout)
            .map_err(|error| format!("recording-shutdown-receive:{error}"))??;
        if self
            .actor
            .take()
            .ok_or_else(|| "recording-writer-join-missing".to_owned())?
            .join()
            .is_err()
        {
            return Err("recording-writer-panicked".to_owned());
        }
        Ok(())
    }
}

fn offer_to_queue(
    admission: &Mutex<AdmissionState>,
    sender: &SyncSender<QueuedRecord>,
    queued_bytes: &AtomicUsize,
    max_batch_samples: usize,
    max_queued_bytes: usize,
    generation: u64,
    record: SessionRecord,
) -> OfferReceipt {
    let mut state = match admission.try_lock() {
        Ok(state) => state,
        Err(TryLockError::WouldBlock) => {
            return OfferReceipt::retry(OfferOutcome::AdmissionBusy, record)
        }
        Err(TryLockError::Poisoned(_)) => {
            return OfferReceipt::retry(OfferOutcome::WriterClosed, record)
        }
    };
    if state.generation != Some(generation) {
        return OfferReceipt::consumed(OfferOutcome::StaleGeneration);
    }
    if !state.accepting {
        return OfferReceipt::consumed(OfferOutcome::NotAccepting);
    }
    state.next_offer_sequence = state.next_offer_sequence.saturating_add(1);
    let offer_sequence = state.next_offer_sequence;
    if record.sample_count() > max_batch_samples {
        state.loss.record_drop(offer_sequence, &record);
        return OfferReceipt::consumed(OfferOutcome::BatchOversize);
    }
    if !record.floats_are_finite() {
        state.loss.record_drop(offer_sequence, &record);
        return OfferReceipt::consumed(OfferOutcome::NonFinite);
    }
    let payload_bytes = record.queued_payload_bytes();
    if payload_bytes > max_queued_bytes {
        state.loss.record_drop(offer_sequence, &record);
        return OfferReceipt::consumed(OfferOutcome::QueueBytesFull);
    }
    if !reserve_queued_bytes(queued_bytes, payload_bytes, max_queued_bytes) {
        state.loss.record_drop(offer_sequence, &record);
        return OfferReceipt::consumed(OfferOutcome::QueueBytesFull);
    }
    let sequence = state.next_sequence.saturating_add(1);
    let queued = QueuedRecord {
        generation,
        sequence,
        payload_bytes,
        record,
    };
    match sender.try_send(queued) {
        Ok(()) => {
            state.next_sequence = sequence;
            state.last_accepted_sequence = sequence;
            OfferReceipt::consumed(OfferOutcome::Accepted { sequence })
        }
        Err(TrySendError::Full(queued)) => {
            release_queued_bytes(queued_bytes, queued.payload_bytes);
            state.loss.record_drop(offer_sequence, &queued.record);
            OfferReceipt::consumed(OfferOutcome::QueueFull)
        }
        Err(TrySendError::Disconnected(queued)) => {
            release_queued_bytes(queued_bytes, queued.payload_bytes);
            OfferReceipt::retry(OfferOutcome::WriterClosed, queued.record)
        }
    }
}

fn run_actor(
    config: WriterConfig,
    control_receiver: Receiver<ControlCommand>,
    data_receiver: Receiver<QueuedRecord>,
    admission: Arc<Mutex<AdmissionState>>,
    queued_bytes: Arc<AtomicUsize>,
    external_loss: Arc<ExternalLossLedger>,
) {
    let mut active: Option<ActiveRecording> = None;
    let mut running = true;
    while running {
        while let Ok(command) = control_receiver.try_recv() {
            running = handle_control(
                command,
                &config,
                &data_receiver,
                &admission,
                &queued_bytes,
                &external_loss,
                &mut active,
            );
            if !running {
                break;
            }
        }
        if !running {
            break;
        }

        if let Some(recording) = active.as_mut() {
            if recording.checkpoint_due() {
                let loss = loss_snapshot(&admission, &external_loss, recording.generation);
                if let Err(error) = recording.checkpoint(recording.completion_durable, loss) {
                    recording.last_error = Some(error);
                    set_not_accepting(&admission, recording.generation);
                }
            }
        }

        let wait = active.as_ref().map_or(MAX_CONTROL_POLL, |recording| {
            recording.time_until_checkpoint().min(MAX_CONTROL_POLL)
        });
        match data_receiver.recv_timeout(wait) {
            Ok(record) => {
                release_queued_bytes(&queued_bytes, record.payload_bytes);
                if let Some(recording) = active.as_mut() {
                    if let Err(error) = recording.write_queued(record) {
                        recording.last_error = Some(error);
                        set_not_accepting(&admission, recording.generation);
                    }
                }
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => break,
        }
    }
}

fn handle_control(
    command: ControlCommand,
    config: &WriterConfig,
    data_receiver: &Receiver<QueuedRecord>,
    admission: &Mutex<AdmissionState>,
    queued_bytes: &AtomicUsize,
    external_loss: &ExternalLossLedger,
    active: &mut Option<ActiveRecording>,
) -> bool {
    match command {
        ControlCommand::Prepare {
            generation,
            spec,
            reply,
        } => {
            let result = if active.is_some() {
                Err("recording-already-active".to_owned())
            } else if queued_bytes.load(Ordering::Acquire) != 0 {
                Err("recording-prior-generation-queue-not-retired".to_owned())
            } else {
                ActiveRecording::prepare(config, generation, spec).map(|recording| {
                    let receipt = PreparedReceipt {
                        generation,
                        directory: recording.directory.clone(),
                    };
                    let mut state = admission
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    state.generation = Some(generation);
                    state.accepting = true;
                    state.finalize_requested = false;
                    state.next_sequence = 0;
                    state.last_accepted_sequence = 0;
                    state.next_offer_sequence = 0;
                    state.loss = LossSnapshot::default();
                    external_loss.reset(generation);
                    *active = Some(recording);
                    receipt
                })
            };
            let _ = reply.send(result);
        }
        ControlCommand::RecordControl {
            generation,
            at,
            kind,
            reply,
        } => {
            let result = active
                .as_mut()
                .ok_or_else(|| "recording-not-active".to_owned())
                .and_then(|recording| {
                    if recording.generation != generation {
                        return Err("recording-generation-stale".to_owned());
                    }
                    recording.write_internal_event(kind, at)?;
                    recording
                        .checkpoint(
                            recording.completion_durable,
                            loss_snapshot(admission, external_loss, generation),
                        )
                        .map(|_| ())
                });
            let _ = reply.send(result);
        }
        ControlCommand::MarkCompletion {
            generation,
            at,
            reply,
        } => {
            let result = active
                .as_mut()
                .ok_or_else(|| "recording-not-active".to_owned())
                .and_then(|recording| {
                    if recording.generation != generation {
                        return Err("recording-generation-stale".to_owned());
                    }
                    recording
                        .mark_completion(at, loss_snapshot(admission, external_loss, generation))
                        .map(|checkpoint_ordinal| CompletionReceipt {
                            generation,
                            checkpoint_ordinal,
                        })
                });
            let _ = reply.send(result);
        }
        ControlCommand::Finalize {
            generation,
            cutoff,
            loss,
            at,
            reason,
            threshold_reached,
            reply,
        } => {
            let result = if let Some(mut recording) = active.take() {
                if recording.generation != generation {
                    *active = Some(recording);
                    Err("recording-generation-stale".to_owned())
                } else {
                    let directory = recording.directory.clone();
                    let finalization_loss = loss.clone();
                    let finalization = recording
                        .drain_to_cutoff(data_receiver, queued_bytes, cutoff)
                        .and_then(|()| {
                            recording.finalize(at, reason, threshold_reached, finalization_loss)
                        });
                    let completed = recording.completion_durable;
                    // Drop every stream handle on the actor even when durable
                    // finalization fails. The latest checkpoint remains
                    // recoverable and shutdown must never trap the UI.
                    drop(recording);
                    let mut state = admission
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    state.generation = None;
                    state.accepting = false;
                    state.finalize_requested = true;
                    drop(state);
                    finalization.and_then(|()| {
                        let recovery = recover_recordings(&config.root)?;
                        let saved = validate_current_recovery(&recovery, &directory, &loss)?;
                        publish_index(&config.root, &recovery)?;
                        Ok(FinalizedReceipt {
                            generation,
                            directory,
                            accepted_sequence_cutoff: cutoff,
                            completed,
                            saved,
                            dropped_records: loss.dropped_records,
                            recovery,
                        })
                    })
                }
            } else {
                Err("recording-not-active".to_owned())
            };
            let _ = reply.send(result);
        }
        ControlCommand::Recover { reply } => {
            let result = if active.is_some() {
                Err("recording-recovery-while-active".to_owned())
            } else {
                recover_recordings(&config.root)
            };
            let _ = reply.send(result);
        }
        ControlCommand::Shutdown { reply } => {
            if active.is_some() {
                let _ = reply.send(Err("recording-shutdown-while-active".to_owned()));
                return true;
            }
            let _ = reply.send(Ok(()));
            return false;
        }
    }
    true
}

fn validate_current_recovery(
    recovery: &RecoveryReport,
    directory: &Path,
    loss: &LossSnapshot,
) -> Result<bool, String> {
    let started_at = directory
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(|| "recording-current-session-key-missing".to_owned())?;
    let current = recovery
        .sessions
        .iter()
        .find(|session| session.started_at.as_str() == started_at)
        .ok_or_else(|| "recording-current-session-recovery-missing".to_owned())?;
    if !matches!(
        current.disposition,
        RecoveryDisposition::FinalizedCompleted | RecoveryDisposition::FinalizedEarly
    ) {
        return Err("recording-current-session-not-finalized".to_owned());
    }
    if loss.dropped_records == 0 {
        if let Some(error) = current.error.as_ref() {
            return Err(format!(
                "recording-current-session-recovery-invalid:{error}"
            ));
        }
        Ok(true)
    } else if current
        .error
        .as_deref()
        .is_some_and(|error| error.starts_with("recording-records-dropped:"))
    {
        Ok(false)
    } else {
        Err("recording-current-session-loss-not-recovered".to_owned())
    }
}

fn loss_snapshot(
    admission: &Mutex<AdmissionState>,
    external_loss: &ExternalLossLedger,
    generation: u64,
) -> LossSnapshot {
    let state = admission
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if state.generation == Some(generation) {
        merge_loss(state.loss.clone(), external_loss.snapshot(generation))
    } else {
        LossSnapshot::default()
    }
}

fn merge_loss(mut first: LossSnapshot, second: LossSnapshot) -> LossSnapshot {
    if second.dropped_records == 0 {
        return first;
    }
    first.dropped_records = first.dropped_records.saturating_add(second.dropped_records);
    for stream in second.streams {
        if !first.streams.contains(&stream) {
            first.streams.push(stream);
        }
    }
    first.streams.sort_by_key(|name| {
        SESSION_STREAM_FILES
            .iter()
            .position(|candidate| candidate == name)
            .unwrap_or(usize::MAX)
    });
    let merge_min = |a: Option<u64>, b: Option<u64>| match (a, b) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (value @ Some(_), None) | (None, value @ Some(_)) => value,
        (None, None) => None,
    };
    let merge_max = |a: Option<u64>, b: Option<u64>| match (a, b) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (value @ Some(_), None) | (None, value @ Some(_)) => value,
        (None, None) => None,
    };
    first.first_offer_sequence = merge_min(first.first_offer_sequence, second.first_offer_sequence);
    first.last_offer_sequence = merge_max(first.last_offer_sequence, second.last_offer_sequence);
    first.first_observed_monotonic_ns = merge_min(
        first.first_observed_monotonic_ns,
        second.first_observed_monotonic_ns,
    );
    first.last_observed_monotonic_ns = merge_max(
        first.last_observed_monotonic_ns,
        second.last_observed_monotonic_ns,
    );
    first
}

fn set_not_accepting(admission: &Mutex<AdmissionState>, generation: u64) {
    let mut state = admission
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if state.generation == Some(generation) {
        state.accepting = false;
    }
}

fn reserve_queued_bytes(queued_bytes: &AtomicUsize, bytes: usize, maximum: usize) -> bool {
    let mut current = queued_bytes.load(Ordering::Acquire);
    loop {
        let Some(next) = current.checked_add(bytes) else {
            return false;
        };
        if next > maximum {
            return false;
        }
        match queued_bytes.compare_exchange_weak(current, next, Ordering::AcqRel, Ordering::Acquire)
        {
            Ok(_) => return true,
            Err(observed) => current = observed,
        }
    }
}

fn release_queued_bytes(queued_bytes: &AtomicUsize, payload_bytes: usize) {
    let previous = queued_bytes.fetch_sub(payload_bytes, Ordering::AcqRel);
    debug_assert!(previous >= payload_bytes);
}

struct ActiveRecording {
    generation: u64,
    spec: SessionStartSpec,
    directory: PathBuf,
    streams: BTreeMap<&'static str, BufWriter<File>>,
    consumed_sequence: u64,
    written_sequence: u64,
    checkpoint_ordinal: u64,
    checkpoint_interval: Duration,
    next_checkpoint: Instant,
    completion_durable: bool,
    last_error: Option<String>,
}

impl ActiveRecording {
    fn prepare(
        config: &WriterConfig,
        generation: u64,
        spec: SessionStartSpec,
    ) -> Result<Self, String> {
        spec.validate().map_err(str::to_owned)?;
        validate_recording_root_no_links(&config.root)?;
        ensure_recording_root(&config.root)?;
        let directory = config.root.join(spec.started_at.as_str());
        fs::create_dir(&directory)
            .map_err(|error| format!("recording-session-directory-create:{error}"))?;
        // The exclusive directory name is part of the durable session identity.
        // Persist the parent entry before publishing the preparation receipt so
        // a power loss cannot acknowledge a session whose directory entry was
        // never made durable.
        sync_directory(&config.root)?;

        let prepare_result = (|| {
            publish_json_exclusive(
                &directory,
                "manifest.json",
                &json!({
                    "schema": SESSION_MANIFEST_SCHEMA,
                    "started_at_utc": spec.started_at.as_str(),
                    "condition": spec.condition.as_str(),
                    "experiment_identity": spec.experiment_identity.encoded_value(),
                    "completion_threshold_ns": spec.completion_threshold_ns,
                    "clock_anchor": {
                        "monotonic_clock": "android-elapsed-realtime",
                        "monotonic_ns": spec.clock_anchor.monotonic.get(),
                        "utc_epoch_ns": spec.clock_anchor.utc.get()
                    },
                    "session_identifier_policy": "utc-start-date-time-only",
                    "runtime_generation_persisted": false
                }),
            )?;
            let mut streams = BTreeMap::new();
            for name in SESSION_STREAM_FILES {
                let file = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(directory.join(name))
                    .map_err(|error| format!("recording-stream-create:{name}:{error}"))?;
                streams.insert(name, BufWriter::with_capacity(256 * 1024, file));
            }
            let mut recording = Self {
                generation,
                spec,
                directory: directory.clone(),
                streams,
                consumed_sequence: 0,
                written_sequence: 0,
                checkpoint_ordinal: 0,
                checkpoint_interval: config.checkpoint_interval,
                next_checkpoint: Instant::now() + config.checkpoint_interval,
                completion_durable: false,
                last_error: None,
            };
            recording.write_internal_event(
                SessionEventKind::Started,
                recording.spec.clock_anchor.monotonic,
            )?;
            recording.checkpoint(false, LossSnapshot::default())?;
            Ok(recording)
        })();
        if prepare_result.is_err() {
            // Preserve the exclusive directory and any partial evidence. A
            // later recovery reports it; never erase or reuse it silently.
            let _ = sync_directory(&directory);
        }
        prepare_result
    }

    fn checkpoint_due(&self) -> bool {
        Instant::now() >= self.next_checkpoint
    }

    fn time_until_checkpoint(&self) -> Duration {
        self.next_checkpoint
            .saturating_duration_since(Instant::now())
    }

    fn write_queued(&mut self, queued: QueuedRecord) -> Result<(), String> {
        if queued.generation != self.generation {
            return Err("recording-queued-generation-stale".to_owned());
        }
        let expected = self.consumed_sequence.saturating_add(1);
        if queued.sequence != expected {
            return Err(format!(
                "recording-sequence-gap:expected-{expected}:actual-{}",
                queued.sequence
            ));
        }
        self.consumed_sequence = queued.sequence;
        self.write_record(queued.sequence, &queued.record)?;
        self.written_sequence = queued.sequence;
        Ok(())
    }

    fn write_record(&mut self, sequence: u64, record: &SessionRecord) -> Result<(), String> {
        if !record.floats_are_finite() {
            return Err("recording-row-non-finite".to_owned());
        }
        let rows = record.encoded_rows(sequence, &self.spec);
        let stream = self
            .streams
            .get_mut(record.stream_file())
            .ok_or_else(|| "recording-stream-owner-missing".to_owned())?;
        for row in rows {
            serde_json::to_writer(&mut *stream, &row)
                .map_err(|error| format!("recording-row-encode:{error}"))?;
            stream
                .write_all(b"\n")
                .map_err(|error| format!("recording-row-write:{error}"))?;
        }
        Ok(())
    }

    fn write_internal_event(
        &mut self,
        kind: SessionEventKind,
        at: MonotonicNanos,
    ) -> Result<(), String> {
        self.write_record(
            self.written_sequence,
            &SessionRecord::Event(SessionEvent {
                observed_at: at,
                kind,
            }),
        )
    }

    fn mark_completion(&mut self, at: MonotonicNanos, loss: LossSnapshot) -> Result<u64, String> {
        if !self.completion_durable {
            self.write_internal_event(SessionEventKind::CompletionReached, at)?;
            self.completion_durable = true;
        }
        self.checkpoint(true, loss)
    }

    fn drain_to_cutoff(
        &mut self,
        receiver: &Receiver<QueuedRecord>,
        queued_bytes: &AtomicUsize,
        cutoff: u64,
    ) -> Result<(), String> {
        if cutoff < self.consumed_sequence {
            return Err("recording-cutoff-before-consumed-sequence".to_owned());
        }
        if let Some(error) = self.last_error.clone() {
            self.retire_to_cutoff(receiver, queued_bytes, cutoff)?;
            return Err(format!("recording-writer-fault:{error}"));
        }
        while self.consumed_sequence < cutoff {
            let queued = receiver
                .recv()
                .map_err(|_| "recording-data-disconnected-before-cutoff".to_owned())?;
            release_queued_bytes(queued_bytes, queued.payload_bytes);
            if let Err(error) = self.write_queued(queued) {
                self.last_error = Some(error.clone());
                self.retire_to_cutoff(receiver, queued_bytes, cutoff)?;
                return Err(format!("recording-writer-fault:{error}"));
            }
        }
        Ok(())
    }

    fn retire_to_cutoff(
        &mut self,
        receiver: &Receiver<QueuedRecord>,
        queued_bytes: &AtomicUsize,
        cutoff: u64,
    ) -> Result<(), String> {
        while self.consumed_sequence < cutoff {
            let queued = receiver
                .recv()
                .map_err(|_| "recording-data-disconnected-during-retirement".to_owned())?;
            release_queued_bytes(queued_bytes, queued.payload_bytes);
            if queued.generation != self.generation {
                return Err("recording-retirement-generation-mismatch".to_owned());
            }
            let expected = self.consumed_sequence.saturating_add(1);
            if queued.sequence != expected {
                return Err(format!(
                    "recording-retirement-sequence-gap:expected-{expected}:actual-{}",
                    queued.sequence
                ));
            }
            self.consumed_sequence = queued.sequence;
        }
        Ok(())
    }

    fn checkpoint(&mut self, completed: bool, loss: LossSnapshot) -> Result<u64, String> {
        let lengths = self.flush_sync_streams()?;
        self.checkpoint_ordinal = self.checkpoint_ordinal.saturating_add(1);
        let name = format!("checkpoint-{:020}.json", self.checkpoint_ordinal);
        publish_json_exclusive(
            &self.directory,
            &name,
            &json!({
                "schema": SESSION_CHECKPOINT_SCHEMA,
                "checkpoint_ordinal": self.checkpoint_ordinal,
                "started_at_utc": self.spec.started_at.as_str(),
                "condition": self.spec.condition.as_str(),
                "experiment_identity": self.spec.experiment_identity.encoded_value(),
                "completed": completed,
                "last_accepted_sequence": self.written_sequence,
                "last_consumed_sequence": self.consumed_sequence,
                "durable_stream_lengths": lengths,
                "loss": loss_json(&loss)
            }),
        )?;
        self.next_checkpoint = Instant::now() + self.checkpoint_interval;
        Ok(self.checkpoint_ordinal)
    }

    fn finalize(
        &mut self,
        at: MonotonicNanos,
        reason: FinalizationReason,
        threshold_reached: bool,
        loss: LossSnapshot,
    ) -> Result<(), String> {
        if let Some(error) = self.last_error.as_ref() {
            return Err(format!("recording-writer-fault:{error}"));
        }
        if threshold_reached && !self.completion_durable {
            self.mark_completion(at, loss.clone())?;
        }
        let event = match reason {
            FinalizationReason::RestartToExperimenter => SessionEventKind::RestartRequested,
            FinalizationReason::SaveAndExit => SessionEventKind::ExitRequested,
            FinalizationReason::InterruptedRecovery => SessionEventKind::SourceGap,
        };
        self.write_internal_event(event, at)?;
        let lengths = self.flush_sync_streams()?;
        let ended_utc_ns = self.spec.clock_anchor.utc_at(at).map(|value| value.get());
        publish_json_exclusive(
            &self.directory,
            "final.json",
            &json!({
                "schema": SESSION_FINAL_SCHEMA,
                "started_at_utc": self.spec.started_at.as_str(),
                "condition": self.spec.condition.as_str(),
                "experiment_identity": self.spec.experiment_identity.encoded_value(),
                "completed": self.completion_durable,
                "completion_threshold_ns": self.spec.completion_threshold_ns,
                "stop_reason": reason.as_str(),
                "ended_monotonic_ns": at.get(),
                "ended_utc_ns": ended_utc_ns,
                "accepted_sequence_cutoff": self.written_sequence,
                "dropped_records": loss.dropped_records,
                "loss": loss_json(&loss),
                "durable_stream_lengths": lengths,
                "saved": loss.dropped_records == 0
            }),
        )?;
        Ok(())
    }

    fn flush_sync_streams(&mut self) -> Result<BTreeMap<String, u64>, String> {
        let mut lengths = BTreeMap::new();
        for (name, stream) in &mut self.streams {
            stream
                .flush()
                .map_err(|error| format!("recording-stream-flush:{name}:{error}"))?;
            stream
                .get_ref()
                .sync_all()
                .map_err(|error| format!("recording-stream-sync:{name}:{error}"))?;
            let length = stream
                .get_ref()
                .metadata()
                .map_err(|error| format!("recording-stream-metadata:{name}:{error}"))?
                .len();
            lengths.insert((*name).to_owned(), length);
        }
        Ok(lengths)
    }
}

fn loss_json(loss: &LossSnapshot) -> Value {
    json!({
        "dropped_records": loss.dropped_records,
        "streams": loss.streams,
        "first_offer_sequence": loss.first_offer_sequence,
        "last_offer_sequence": loss.last_offer_sequence,
        "first_observed_monotonic_ns": loss.first_observed_monotonic_ns,
        "last_observed_monotonic_ns": loss.last_observed_monotonic_ns
    })
}

fn publish_index(root: &Path, recovery: &RecoveryReport) -> Result<(), String> {
    ensure_recording_root(root)?;
    let next = fs::read_dir(root)
        .map_err(|error| format!("recording-root-read:{error}"))?
        .filter_map(Result::ok)
        .filter_map(|entry| entry.file_name().to_str().map(str::to_owned))
        .filter_map(|name| {
            name.strip_prefix("index-")
                .and_then(|value| value.strip_suffix(".json"))
                .and_then(|value| value.parse::<u64>().ok())
        })
        .max()
        .unwrap_or(0)
        .saturating_add(1);
    let sessions = recovery
        .sessions
        .iter()
        .map(|session| {
            json!({
                "started_at_utc": session.started_at.as_str(),
                "disposition": session.disposition.as_str(),
                "experiment_identity": session.experiment_identity.as_ref().map(|value| value.encoded_value()),
                "error": session.error
            })
        })
        .collect::<Vec<_>>();
    publish_json_exclusive(
        root,
        &format!("index-{next:020}.json"),
        &json!({
            "schema": SESSION_INDEX_SCHEMA,
            "index_ordinal": next,
            "counts": {
                "completed": recovery.counts.completed,
                "stopped_early": recovery.counts.stopped_early,
                "errors": recovery.counts.errors
            },
            "sessions": sessions
        }),
    )
}

fn ensure_recording_root(root: &Path) -> Result<(), String> {
    let existed = root.exists();
    fs::create_dir_all(root).map_err(|error| format!("recording-root-create:{error}"))?;
    if !existed {
        let parent = root
            .parent()
            .ok_or_else(|| "recording-root-parent-missing".to_owned())?;
        // A synced child directory does not make the parent's new directory
        // entry durable. Android normal operation has an existing app `files`
        // parent; this explicit barrier covers first creation of the recording
        // root before any prepare or index acknowledgement is published.
        sync_directory(parent)?;
    }
    sync_directory(root)
}

fn publish_json_exclusive(directory: &Path, name: &str, value: &Value) -> Result<(), String> {
    let final_path = directory.join(name);
    if final_path.exists() {
        return Err(format!("recording-artifact-collision:{name}"));
    }
    let partial_path = directory.join(format!(".{name}.partial"));
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&partial_path)
        .map_err(|error| format!("recording-artifact-create:{name}:{error}"))?;
    let mut writer = BufWriter::new(file);
    serde_json::to_writer_pretty(&mut writer, value)
        .map_err(|error| format!("recording-artifact-encode:{name}:{error}"))?;
    writer
        .write_all(b"\n")
        .map_err(|error| format!("recording-artifact-write:{name}:{error}"))?;
    writer
        .flush()
        .map_err(|error| format!("recording-artifact-flush:{name}:{error}"))?;
    writer
        .get_ref()
        .sync_all()
        .map_err(|error| format!("recording-artifact-sync:{name}:{error}"))?;
    drop(writer);
    fs::rename(&partial_path, &final_path)
        .map_err(|error| format!("recording-artifact-publish:{name}:{error}"))?;
    sync_directory(directory)?;
    Ok(())
}

#[cfg(unix)]
fn sync_directory(directory: &Path) -> Result<(), String> {
    File::open(directory)
        .and_then(|file| file.sync_all())
        .map_err(|error| format!("recording-directory-sync:{error}"))
}

#[cfg(not(unix))]
fn sync_directory(_directory: &Path) -> Result<(), String> {
    // Android is Unix and receives the directory durability barrier. Windows
    // host tests still verify exclusive publication and synced file contents.
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::atomic::{AtomicU64, Ordering},
        time::{SystemTime, UNIX_EPOCH},
    };

    use crate::{
        session_recording_clock::{
            ClockAnchor, SessionTimestampKey, SourceClock, SourceTimestamp, UtcEpochNanos,
        },
        session_recording_contract::{
            AudioAssetIdentity, BreathObservation, BreathPhase, ConditionKey,
            DeformationEnvelopeEndpoints, EffectiveRadiusProfile,
            EffectiveRadiusSnapshotObservation, ExperimentIdentity, PolarAccBatch, PolarAccSample,
            PolarHeartRateObservation, DEFAULT_COMPLETION_THRESHOLD_NS,
        },
    };

    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn temp_root(label: &str) -> PathBuf {
        std::env::temp_dir()
            .join(format!(
                "rusty-quest-session-writer-{label}-{}-{}",
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ))
            .join("viscereality-recordings")
    }

    fn spec(utc: u64) -> SessionStartSpec {
        let utc = UtcEpochNanos::new(utc);
        SessionStartSpec {
            started_at: SessionTimestampKey::from_utc(utc),
            condition: ConditionKey::parse("condition-a").unwrap(),
            completion_threshold_ns: DEFAULT_COMPLETION_THRESHOLD_NS,
            clock_anchor: ClockAnchor::new(MonotonicNanos::new(1_000), utc),
            experiment_identity: ExperimentIdentity {
                provider_id: "private-provider".to_owned(),
                provider_manifest_sha256: "11".repeat(32),
                provider_inventory_sha256: "22".repeat(32),
                non_audio_profile_sha256: "33".repeat(32),
                condition_id: "condition-a".to_owned(),
                audio: AudioAssetIdentity {
                    logical_destination: "audio/condition-a.wav".to_owned(),
                    source_sha256: "44".repeat(32),
                    source_bytes: 1024,
                    media_type: "audio/wav".to_owned(),
                },
                breath_guidance: None,
                breath_guidance_bias_percent: 0,
                effective_radius_profile: EffectiveRadiusProfile {
                    configured_radius_min_m: 0.8,
                    configured_radius_max_m: 1.6,
                    oblateness: Some(DeformationEnvelopeEndpoints {
                        at_radius_min: 0.2,
                        at_radius_max: 0.4,
                    }),
                    axis_profile: Some(DeformationEnvelopeEndpoints {
                        at_radius_min: 1.8,
                        at_radius_max: 0.9,
                    }),
                },
            },
        }
    }

    fn config(root: PathBuf, capacity: usize, checkpoint_ms: u64) -> WriterConfig {
        WriterConfig {
            root,
            data_queue_capacity: capacity,
            max_batch_samples: 64,
            max_queued_bytes: 64 * 1024,
            checkpoint_interval: Duration::from_millis(checkpoint_ms),
        }
    }

    fn inject_loss(writer: &SessionRecordingWriter, generation: u64, record: &SessionRecord) {
        let mut state = writer.admission.lock().unwrap();
        assert_eq!(state.generation, Some(generation));
        state.next_offer_sequence = state.next_offer_sequence.saturating_add(1);
        let offer_sequence = state.next_offer_sequence;
        state.loss.record_drop(offer_sequence, record);
    }

    fn breath(at: u64) -> SessionRecord {
        SessionRecord::Breath(BreathObservation {
            source_sequence: at,
            sampled_at: SourceTimestamp {
                clock: SourceClock::OpenXrTime,
                value_ns: at,
            },
            observed_source: SourceTimestamp {
                clock: SourceClock::OpenXrTime,
                value_ns: at,
            },
            observed_at: MonotonicNanos::new(at),
            phase: BreathPhase::Inhale,
            unbiased_phase: BreathPhase::Inhale,
            guidance_phase: None,
            guidance_bias_percent: 0,
            guidance_active_time_ms: None,
            volume01: Some(0.5),
            quality01: 1.0,
            settings_revision: 3,
        })
    }

    #[test]
    fn writer_finalizes_exact_cutoff_and_rebuilds_counts() {
        let root = temp_root("round-trip");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 16, 50)).unwrap();
        let start = spec(1_725_000_000_000_000_000);
        let prepared = writer
            .prepare(1, start.clone())
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert_eq!(prepared.generation, 1);
        for sequence in 1..=4 {
            assert_eq!(
                writer.try_offer(1, breath(1_000 + sequence)),
                OfferOutcome::Accepted { sequence }
            );
        }
        writer
            .mark_completion(1, MonotonicNanos::new(30_000_001_000))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        let finalized = writer
            .try_finalize(
                1,
                MonotonicNanos::new(31_000_001_000),
                FinalizationReason::RestartToExperimenter,
                true,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert_eq!(finalized.accepted_sequence_cutoff, 4);
        assert!(finalized.completed);
        assert!(finalized.saved);
        assert_eq!(finalized.dropped_records, 0);
        assert_eq!(finalized.recovery.counts.completed, 1);
        assert_eq!(
            writer.try_offer(1, breath(40)),
            OfferOutcome::StaleGeneration
        );
        let final_text = fs::read_to_string(finalized.directory.join("final.json")).unwrap();
        assert!(!final_text.contains("participant"));
        assert!(!final_text.contains("session_id"));
        assert!(!final_text.contains("runtime_generation"));
        let breath_rows = fs::read_to_string(finalized.directory.join("breath.jsonl")).unwrap();
        assert_eq!(breath_rows.lines().count(), 4);
        assert!(root.join("index-00000000000000000001.json").exists());
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn bounded_offer_reports_full_without_a_consumer_or_encoding() {
        let (sender, _receiver) = mpsc::sync_channel(1);
        let admission = Mutex::new(AdmissionState {
            generation: Some(9),
            accepting: true,
            finalize_requested: false,
            next_sequence: 0,
            last_accepted_sequence: 0,
            next_offer_sequence: 0,
            loss: LossSnapshot::default(),
        });
        let queued_bytes = AtomicUsize::new(0);
        assert_eq!(
            offer_to_queue(
                &admission,
                &sender,
                &queued_bytes,
                64,
                64 * 1024,
                9,
                breath(1)
            ),
            OfferOutcome::Accepted { sequence: 1 }
        );
        assert_eq!(
            offer_to_queue(
                &admission,
                &sender,
                &queued_bytes,
                64,
                64 * 1024,
                9,
                breath(2)
            ),
            OfferOutcome::QueueFull
        );
        let state = admission.lock().unwrap();
        assert_eq!(state.loss.dropped_records, 1);
        assert_eq!(state.last_accepted_sequence, 1);
    }

    fn acc_batch(sample_count: usize, at: u64) -> SessionRecord {
        SessionRecord::PolarAcc(PolarAccBatch {
            frame_sequence: at,
            samples: (0..sample_count)
                .map(|index| PolarAccSample {
                    source_sequence: at,
                    sample_index: index as u32,
                    source_time: SourceTimestamp {
                        clock: SourceClock::PolarSensor,
                        value_ns: at + index as u64,
                    },
                    received_source: SourceTimestamp {
                        clock: SourceClock::JavaNanoTime,
                        value_ns: at + index as u64,
                    },
                    observed_at: MonotonicNanos::new(at + index as u64),
                    xyz_mg: [1.0, 2.0, 3.0],
                })
                .collect::<Vec<_>>()
                .into_boxed_slice(),
        })
    }

    #[test]
    fn batch_and_total_queue_memory_are_bounded_and_released_on_consume() {
        let (sender, receiver) = mpsc::sync_channel(4);
        let admission = Mutex::new(AdmissionState {
            generation: Some(9),
            accepting: true,
            finalize_requested: false,
            next_sequence: 0,
            last_accepted_sequence: 0,
            next_offer_sequence: 0,
            loss: LossSnapshot::default(),
        });
        let queued_bytes = AtomicUsize::new(0);
        assert_eq!(
            offer_to_queue(
                &admission,
                &sender,
                &queued_bytes,
                1,
                64 * 1024,
                9,
                acc_batch(2, 100)
            ),
            OfferOutcome::BatchOversize
        );

        let one = breath(200);
        let one_size = one.queued_payload_bytes();
        assert_eq!(
            offer_to_queue(&admission, &sender, &queued_bytes, 4, one_size, 9, one),
            OfferOutcome::Accepted { sequence: 1 }
        );
        assert_eq!(
            offer_to_queue(
                &admission,
                &sender,
                &queued_bytes,
                4,
                one_size,
                9,
                breath(300)
            ),
            OfferOutcome::QueueBytesFull
        );
        let consumed = receiver.try_recv().unwrap();
        release_queued_bytes(&queued_bytes, consumed.payload_bytes);
        assert_eq!(
            offer_to_queue(
                &admission,
                &sender,
                &queued_bytes,
                4,
                one_size,
                9,
                breath(400)
            ),
            OfferOutcome::Accepted { sequence: 2 }
        );
        assert_eq!(queued_bytes.load(Ordering::Acquire), one_size);
        let loss = admission.lock().unwrap().loss.clone();
        assert_eq!(loss.dropped_records, 2);
        assert_eq!(loss.streams, vec!["polar-acc.jsonl", "breath.jsonl"]);
        assert_eq!(loss.first_observed_monotonic_ns, Some(101));
        assert_eq!(loss.last_observed_monotonic_ns, Some(300));
    }

    #[test]
    fn idle_active_session_publishes_append_only_checkpoints() {
        let root = temp_root("idle-checkpoint");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 20)).unwrap();
        let start = spec(1_725_000_000_000_000_100);
        let prepared = writer
            .prepare(1, start)
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        thread::sleep(Duration::from_millis(75));
        let finalized = writer
            .try_finalize(
                1,
                MonotonicNanos::new(80_000_000),
                FinalizationReason::SaveAndExit,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        let checkpoints = fs::read_dir(&prepared.directory)
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| {
                entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with("checkpoint-")
            })
            .count();
        // One checkpoint is published during prepare. At least one additional
        // generation must be published solely by the idle timer.
        assert!(checkpoints >= 2, "checkpoints={checkpoints}");
        assert_eq!(finalized.recovery.counts.stopped_early, 1);
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn exclusive_timestamp_directory_rejects_reuse() {
        let root = temp_root("collision");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 50)).unwrap();
        let start = spec(1_725_000_000_000_000_200);
        writer
            .prepare(1, start.clone())
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::RestartToExperimenter,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        let error = writer
            .prepare(2, start)
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap_err();
        assert!(error.starts_with("recording-session-directory-create:"));
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn prepare_failure_does_not_claim_an_active_recording() {
        let root = temp_root("bad-root");
        fs::create_dir_all(root.parent().unwrap()).unwrap();
        fs::write(&root, b"not-a-directory").unwrap();
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 50)).unwrap();
        let error = writer
            .prepare(1, spec(1_725_000_000_000_000_300))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap_err();
        assert_eq!(error, "recording-root-not-directory");
        assert_eq!(
            writer.try_offer(1, breath(1)),
            OfferOutcome::StaleGeneration
        );
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_file(root).unwrap();
    }

    #[test]
    fn final_receipt_collision_reports_failure_but_allows_actor_shutdown() {
        let root = temp_root("final-collision");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 50)).unwrap();
        let prepared = writer
            .prepare(1, spec(1_725_000_000_000_000_400))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        fs::write(prepared.directory.join("final.json"), b"collision").unwrap();
        let error = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::SaveAndExit,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap_err();
        assert_eq!(error, "recording-artifact-collision:final.json");
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn dropped_offer_count_is_visible_in_receipt_recovery_and_index() {
        let root = temp_root("dropped-visible");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 50)).unwrap();
        writer
            .prepare(1, spec(1_725_000_000_000_000_500))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        inject_loss(&writer, 1, &breath(10));
        inject_loss(&writer, 1, &breath(20));
        let finalized = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::SaveAndExit,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert!(!finalized.saved);
        assert_eq!(finalized.dropped_records, 2);
        assert_eq!(finalized.recovery.counts.errors, 1);
        assert_eq!(
            finalized.recovery.sessions[0].error.as_deref(),
            Some("recording-records-dropped:2:streams=breath.jsonl:offers=1-2:monotonic-ns=10-20")
        );
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn idle_checkpoint_makes_loss_visible_after_interrupted_writer() {
        let root = temp_root("interrupted-loss");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 20)).unwrap();
        writer
            .prepare(1, spec(1_725_000_000_000_000_550))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        inject_loss(&writer, 1, &breath(500));
        thread::sleep(Duration::from_millis(75));
        drop(writer);
        thread::sleep(Duration::from_millis(25));

        let recovery = recover_recordings(&root).unwrap();
        assert_eq!(recovery.counts.stopped_early, 1);
        assert_eq!(recovery.counts.errors, 1);
        assert!(recovery.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("recording-records-dropped:1"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn consumed_write_fault_at_cutoff_returns_without_waiting_on_empty_queue() {
        let root = temp_root("write-fault-cutoff");
        let configuration = config(root.clone(), 4, 50);
        let mut recording =
            ActiveRecording::prepare(&configuration, 1, spec(1_725_000_000_000_000_600)).unwrap();
        let breath_path = recording.directory.join("breath.jsonl");
        let read_only = OpenOptions::new().read(true).open(breath_path).unwrap();
        recording
            .streams
            .insert("breath.jsonl", BufWriter::with_capacity(0, read_only));
        let queued = QueuedRecord {
            generation: 1,
            sequence: 1,
            payload_bytes: breath(2_000).queued_payload_bytes(),
            record: breath(2_000),
        };
        let write_error = recording.write_queued(queued).unwrap_err();
        recording.last_error = Some(write_error);
        let (sender, receiver) = mpsc::sync_channel(2);
        let tail_record = breath(3_000);
        let tail_bytes = tail_record.queued_payload_bytes();
        sender
            .send(QueuedRecord {
                generation: 1,
                sequence: 2,
                payload_bytes: tail_bytes,
                record: tail_record,
            })
            .unwrap();
        let queued_bytes = AtomicUsize::new(tail_bytes);
        let error = recording
            .drain_to_cutoff(&receiver, &queued_bytes, 2)
            .unwrap_err();
        assert!(error.starts_with("recording-writer-fault:"));
        assert_eq!(recording.consumed_sequence, 2);
        assert_eq!(recording.written_sequence, 0);
        assert_eq!(queued_bytes.load(Ordering::Acquire), 0);
        assert!(matches!(
            receiver.try_recv(),
            Err(mpsc::TryRecvError::Empty)
        ));
        drop(recording);

        let mut next =
            ActiveRecording::prepare(&configuration, 2, spec(1_725_000_000_000_000_601)).unwrap();
        let next_record = breath(4_000);
        next.write_queued(QueuedRecord {
            generation: 2,
            sequence: 1,
            payload_bytes: next_record.queued_payload_bytes(),
            record: next_record,
        })
        .unwrap();
        assert_eq!(next.consumed_sequence, 1);
        assert_eq!(next.written_sequence, 1);
        drop(next);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn loss_at_monotonic_zero_is_preserved_and_recovered() {
        let root = temp_root("zero-loss-bound");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 50)).unwrap();
        writer
            .prepare(1, spec(1_725_000_000_000_000_700))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        inject_loss(&writer, 1, &breath(0));
        let finalized = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::SaveAndExit,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert_eq!(finalized.recovery.counts.errors, 1);
        assert!(finalized.recovery.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("monotonic-ns=0-0"));
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn stale_and_busy_offers_cannot_transfer_loss_to_a_new_generation() {
        let root = temp_root("generation-loss");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 50)).unwrap();
        writer
            .prepare(1, spec(1_725_000_000_000_000_800))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert_eq!(
            writer.try_offer(1, acc_batch(65, 0)),
            OfferOutcome::BatchOversize
        );
        let first = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::RestartToExperimenter,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert!(!first.saved);

        let preparing = writer.prepare(2, spec(1_725_000_000_000_000_801)).unwrap();
        for at in 0..16 {
            assert!(matches!(
                writer.try_offer(1, acc_batch(65, at)).outcome,
                OfferOutcome::StaleGeneration | OfferOutcome::AdmissionBusy
            ));
        }
        preparing.receive_timeout(Duration::from_secs(2)).unwrap();
        {
            let _held = writer.admission.lock().unwrap();
            let busy = offer_to_queue(
                &writer.admission,
                &writer.data_sender,
                &writer.queued_bytes,
                writer.max_batch_samples,
                writer.max_queued_bytes,
                1,
                breath(0),
            );
            assert_eq!(busy, OfferOutcome::AdmissionBusy);
            assert!(busy.into_retry_record().is_some());
        }
        let finalizing = writer
            .try_finalize(
                2,
                MonotonicNanos::new(2_000),
                FinalizationReason::RestartToExperimenter,
                false,
            )
            .unwrap();
        for at in 0..16 {
            assert!(matches!(
                writer.try_offer(1, breath(at)).outcome,
                OfferOutcome::StaleGeneration | OfferOutcome::AdmissionBusy
            ));
        }
        let second = finalizing.receive_timeout(Duration::from_secs(2)).unwrap();
        assert!(second.saved);
        assert_eq!(second.dropped_records, 0);

        writer
            .prepare(3, spec(1_725_000_000_000_000_802))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        let third = writer
            .try_finalize(
                3,
                MonotonicNanos::new(2_000),
                FinalizationReason::SaveAndExit,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert!(third.saved);
        assert_eq!(third.dropped_records, 0);
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn current_generation_busy_returns_record_for_retry_and_stays_saved() {
        let root = temp_root("busy-retry");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 4, 20)).unwrap();
        writer
            .prepare(1, spec(1_725_000_000_000_000_900))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        let retry_record = {
            let _held = writer.admission.lock().unwrap();
            let busy = writer.try_offer(1, breath(0));
            assert_eq!(busy, OfferOutcome::AdmissionBusy);
            busy.into_retry_record().expect("busy offer retains record")
        };
        assert_eq!(
            writer.try_offer(1, retry_record),
            OfferOutcome::Accepted { sequence: 1 }
        );
        thread::sleep(Duration::from_millis(75));
        let interrupted = recover_recordings(&root).unwrap();
        assert_eq!(interrupted.counts.errors, 0);
        assert_eq!(interrupted.counts.stopped_early, 1);
        let finalized = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::SaveAndExit,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert!(finalized.saved);
        assert_eq!(finalized.dropped_records, 0);
        assert_eq!(finalized.recovery.counts.errors, 0);
        assert_eq!(
            fs::read_to_string(finalized.directory.join("breath.jsonl"))
                .unwrap()
                .lines()
                .count(),
            1
        );
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn non_finite_sensor_values_are_rejected_before_durable_writing() {
        let root = temp_root("non-finite");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 16, 50)).unwrap();
        writer
            .prepare(1, spec(1_725_000_000_000_000_901))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        let invalid = vec![
            SessionRecord::PolarAcc(PolarAccBatch {
                frame_sequence: 1,
                samples: vec![PolarAccSample {
                    source_sequence: 1,
                    sample_index: 0,
                    source_time: SourceTimestamp {
                        clock: SourceClock::PolarSensor,
                        value_ns: 1,
                    },
                    received_source: SourceTimestamp {
                        clock: SourceClock::JavaNanoTime,
                        value_ns: 1,
                    },
                    observed_at: MonotonicNanos::new(1),
                    xyz_mg: [f32::NAN, f32::INFINITY, f32::NEG_INFINITY],
                }]
                .into_boxed_slice(),
            }),
            SessionRecord::PolarHeartRate(PolarHeartRateObservation {
                source_sequence: 2,
                source_time: SourceTimestamp {
                    clock: SourceClock::JavaNanoTime,
                    value_ns: 2,
                },
                observed_at: MonotonicNanos::new(2),
                bpm: 60,
                rr_interval_ms: Some(f32::INFINITY),
            }),
            SessionRecord::Breath(BreathObservation {
                source_sequence: 3,
                sampled_at: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 3,
                },
                observed_source: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 3,
                },
                observed_at: MonotonicNanos::new(3),
                phase: BreathPhase::Inhale,
                unbiased_phase: BreathPhase::Inhale,
                guidance_phase: None,
                guidance_bias_percent: 0,
                guidance_active_time_ms: None,
                volume01: Some(f32::NEG_INFINITY),
                quality01: 1.0,
                settings_revision: 1,
            }),
            SessionRecord::Breath(BreathObservation {
                source_sequence: 4,
                sampled_at: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 4,
                },
                observed_source: SourceTimestamp {
                    clock: SourceClock::OpenXrTime,
                    value_ns: 4,
                },
                observed_at: MonotonicNanos::new(4),
                phase: BreathPhase::Exhale,
                unbiased_phase: BreathPhase::Exhale,
                guidance_phase: None,
                guidance_bias_percent: 0,
                guidance_active_time_ms: None,
                volume01: Some(0.5),
                quality01: f32::NAN,
                settings_revision: 1,
            }),
            SessionRecord::EffectiveRadiusSnapshot(EffectiveRadiusSnapshotObservation {
                source_frame: 5,
                observed_at: MonotonicNanos::new(5),
                configured_radius_min_m: 0.8,
                configured_radius_max_m: 1.6,
                radius_progress01: 0.5,
                resulting_radius_m: f32::NAN,
                deformation_progress01: 0.5,
                oblateness: None,
                axis_profile: None,
                settings_revision: 1,
                render_session_generation: 1,
                resulting_radius_parameter_source: "app-effective-world-anchor",
            }),
            SessionRecord::EffectiveRadiusSnapshot(EffectiveRadiusSnapshotObservation {
                source_frame: 6,
                observed_at: MonotonicNanos::new(6),
                configured_radius_min_m: 0.8,
                configured_radius_max_m: 1.6,
                radius_progress01: f32::INFINITY,
                resulting_radius_m: 1.2,
                deformation_progress01: 0.5,
                oblateness: None,
                axis_profile: None,
                settings_revision: 1,
                render_session_generation: 1,
                resulting_radius_parameter_source: "app-effective-world-anchor",
            }),
        ];
        let invalid_count = invalid.len() as u64;
        for record in invalid {
            assert_eq!(writer.try_offer(1, record), OfferOutcome::NonFinite);
        }
        let finalized = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::SaveAndExit,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert!(!finalized.saved);
        assert_eq!(finalized.dropped_records, invalid_count);
        assert_eq!(finalized.recovery.counts.errors, 1);
        for stream in [
            "polar-acc.jsonl",
            "polar-hr-rr.jsonl",
            "breath.jsonl",
            "radius.jsonl",
        ] {
            assert!(fs::read(finalized.directory.join(stream))
                .unwrap()
                .is_empty());
        }
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn current_session_recovery_error_cannot_return_saved_true() {
        let key = SessionTimestampKey::parse("utc-ns-01725000000000000999").unwrap();
        let directory = PathBuf::from("root").join(key.as_str());
        let recovery = RecoveryReport {
            sessions: vec![crate::session_recording_recovery::RecoveredSession {
                started_at: key,
                experiment_identity: None,
                disposition: RecoveryDisposition::FinalizedEarly,
                durable_stream_lengths: BTreeMap::new(),
                error: Some("recording-stream-row-null-invalid".to_owned()),
            }],
            counts: Default::default(),
            condition_counts: Default::default(),
        };
        let error =
            validate_current_recovery(&recovery, &directory, &LossSnapshot::default()).unwrap_err();
        assert!(error.starts_with("recording-current-session-recovery-invalid:"));
    }

    #[test]
    fn lock_free_external_retry_loss_is_checkpointed_and_unsaved() {
        let root = temp_root("external-loss");
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 16, 10)).unwrap();
        writer
            .prepare(1, spec(1_725_000_000_000_001_111))
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        let lost = breath(0);
        assert!(writer.record_external_loss(1, &lost));
        thread::sleep(Duration::from_millis(30));
        let finalized = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2_000),
                FinalizationReason::RestartToExperimenter,
                false,
            )
            .unwrap()
            .receive_timeout(Duration::from_secs(2))
            .unwrap();
        assert!(!finalized.saved);
        assert_eq!(finalized.dropped_records, 1);
        assert_eq!(finalized.recovery.counts.errors, 1);
        let checkpoints = fs::read_dir(&finalized.directory)
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| {
                entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with("checkpoint-")
            })
            .collect::<Vec<_>>();
        assert!(checkpoints.iter().any(|entry| {
            serde_json::from_slice::<Value>(&fs::read(entry.path()).unwrap())
                .ok()
                .and_then(|value| value["loss"]["dropped_records"].as_u64())
                == Some(1)
        }));
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}
