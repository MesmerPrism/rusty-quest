//! Neutral, bounded, app-private capture for synchronized breath-source replay.
//!
//! The capture is an observation sink only. It never owns sensor acquisition,
//! assessment, selection, or driver application. Producers use a bounded
//! non-blocking queue so file I/O cannot stall OpenXR or Bluetooth callbacks.

use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender, TrySendError},
        Arc, Mutex, MutexGuard, OnceLock,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use rusty_quest_breath_contract::{
    assessment::BreathAssessmentObservation,
    composition::{BreathCompositionSnapshot, BreathCompositionSource},
};
use serde_json::{json, Value};

const CAPTURE_SCHEMA_ID: &str = "rusty.quest.breath_source_capture.v1";
const MANIFEST_SCHEMA_ID: &str = "rusty.quest.breath_source_capture_manifest.v1";
const RECEIPT_SCHEMA_ID: &str = "rusty.quest.breath_source_capture_receipt.v1";
const FIXED_CAPTURE_DURATION_MILLIS: u64 = 120_000;
const QUEUE_CAPACITY: usize = 32_768;
const CHECKPOINT_RECORD_INTERVAL: u64 = 256;

#[derive(Debug)]
struct CaptureRecord {
    kind: &'static str,
    line: String,
}

#[derive(Debug, Default)]
struct CaptureFinalization {
    stop_reason: String,
    completed_at_monotonic_ns: u64,
}

#[derive(Debug, Default)]
struct CaptureWriterResult {
    finalized_samples: bool,
    complete: bool,
    durable_checkpoints: u64,
    record_counts: BTreeMap<String, u64>,
    receipt_written: bool,
}

#[derive(Debug)]
struct CaptureCounters {
    active: AtomicBool,
    enqueued: AtomicU64,
    written: AtomicU64,
    dropped: AtomicU64,
    write_failures: AtomicU64,
}

impl CaptureCounters {
    fn new() -> Self {
        Self {
            active: AtomicBool::new(true),
            enqueued: AtomicU64::new(0),
            written: AtomicU64::new(0),
            dropped: AtomicU64::new(0),
            write_failures: AtomicU64::new(0),
        }
    }
}

#[derive(Debug)]
struct CaptureSession {
    generation: u64,
    session_id: String,
    directory: PathBuf,
    started_at_monotonic_ns: u64,
    duration_millis: u64,
    started: Instant,
    sender: SyncSender<CaptureRecord>,
    counters: Arc<CaptureCounters>,
    finalization: Arc<Mutex<CaptureFinalization>>,
    writer_result: Arc<Mutex<CaptureWriterResult>>,
    watchdog_cancel: Arc<AtomicBool>,
    writer: JoinHandle<()>,
}

#[derive(Debug, Default)]
struct CaptureRuntime {
    next_generation: u64,
    session: Option<CaptureSession>,
    finalizing: bool,
    last_session_id: Option<String>,
    last_directory: Option<PathBuf>,
    last_enqueued: u64,
    last_written: u64,
    last_dropped: u64,
    last_write_failures: u64,
    last_stop_reason: Option<String>,
    last_finalized_samples: bool,
    last_receipt_written: bool,
    last_complete: bool,
    last_durable_checkpoints: u64,
    last_duration_millis: u64,
    last_error: Option<String>,
}

fn runtime() -> &'static Mutex<CaptureRuntime> {
    static RUNTIME: OnceLock<Mutex<CaptureRuntime>> = OnceLock::new();
    RUNTIME.get_or_init(|| Mutex::new(CaptureRuntime::default()))
}

fn lock_runtime() -> MutexGuard<'static, CaptureRuntime> {
    runtime()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Whether a capture is currently accepting records.
pub(crate) fn active() -> bool {
    lock_runtime()
        .session
        .as_ref()
        .is_some_and(|session| session.counters.active.load(Ordering::Relaxed))
}

fn validate_token(value: &str, label: &str) -> Result<String, String> {
    let value = value.trim();
    if value.is_empty() || value.len() > 96 {
        return Err(format!("{label}-invalid"));
    }
    if !value
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        return Err(format!("{label}-invalid"));
    }
    Ok(value.to_owned())
}

fn validate_directory(value: &str) -> Result<PathBuf, String> {
    let path = Path::new(value.trim());
    if value.trim().is_empty() || !path.is_absolute() {
        return Err("capture-directory-must-be-absolute".to_owned());
    }
    fs::create_dir_all(path).map_err(|error| format!("capture-directory-create:{error}"))?;
    fs::canonicalize(path).map_err(|error| format!("capture-directory-canonicalize:{error}"))
}

/// Start the fixed-length capture in an exact app-owned directory.
pub(crate) fn start_capture(
    directory: &str,
    session_id: &str,
    started_at_monotonic_ns: u64,
) -> Result<Value, String> {
    start_capture_with_duration(
        directory,
        session_id,
        started_at_monotonic_ns,
        FIXED_CAPTURE_DURATION_MILLIS,
    )
}

fn start_capture_with_duration(
    directory: &str,
    session_id: &str,
    started_at_monotonic_ns: u64,
    duration_millis: u64,
) -> Result<Value, String> {
    let session_id = validate_token(session_id, "session-id")?;
    let directory = validate_directory(directory)?;
    if duration_millis == 0 || duration_millis > FIXED_CAPTURE_DURATION_MILLIS {
        return Err("capture-duration-invalid".to_owned());
    }
    if fs::read_dir(&directory)
        .map_err(|error| format!("capture-directory-read:{error}"))?
        .next()
        .is_some()
    {
        return Err("capture-directory-not-empty".to_owned());
    }
    let polar_acc_presentation = crate::polar_composition_adapters::polar_acc_presentation_status();
    let mut state = lock_runtime();
    if state.session.is_some() {
        return Err("capture-already-active".to_owned());
    }
    state.next_generation = state.next_generation.saturating_add(1).max(1);
    let generation = state.next_generation;
    let started = Instant::now();
    let partial_samples_path = directory.join("breath_source_samples.partial.jsonl");
    let samples_path = directory.join("breath_source_samples.jsonl");
    let manifest_path = directory.join("capture_manifest.json");
    let active_path = directory.join("capture.active.json");
    let receipt_path = directory.join("capture_receipt.json");
    let manifest = json!({
        "schema": MANIFEST_SCHEMA_ID,
        "capture_schema": CAPTURE_SCHEMA_ID,
        "session_id": session_id,
        "generation": generation,
        "started_at_monotonic_ns": started_at_monotonic_ns,
        "target_duration_millis": duration_millis,
        "queue_capacity": QUEUE_CAPACITY,
        "checkpoint_record_interval": CHECKPOINT_RECORD_INTERVAL,
        "clock_contract": {
            "host_time_ns": "android-monotonic",
            "sensor_time_ns": "source-native-when-available",
            "xr_time_ns": "openxr-runtime"
        },
        "streams": [
            "controller_pose",
            "controller_right_thumbstick",
            "polar_acc_frame",
            "polar_acc_sample",
            "polar_acc_presentation",
            "polar_ecg_frame",
            "polar_ecg_sample",
            "polar_hr",
            "polar_rr",
            "breath_assessment",
            "driver_apply"
        ],
        "rr_consumed_by_breath": false,
        "polar_acc_presentation": polar_acc_presentation,
        "transport": "same-process-direct"
    });
    write_json_file_atomically(&manifest_path, &manifest)?;
    write_json_file_atomically(
        &active_path,
        &json!({
            "schema": CAPTURE_SCHEMA_ID,
            "status": "recording",
            "session_id": session_id,
            "generation": generation,
            "started_at_monotonic_ns": started_at_monotonic_ns,
            "target_duration_millis": duration_millis,
            "partial_samples_file": partial_samples_path.file_name().and_then(|value| value.to_str())
        }),
    )?;
    let file = File::create(&partial_samples_path)
        .map_err(|error| format!("capture-samples-create:{error}"))?;
    let (sender, receiver) = mpsc::sync_channel::<CaptureRecord>(QUEUE_CAPACITY);
    let counters = Arc::new(CaptureCounters::new());
    let writer_counters = Arc::clone(&counters);
    let writer_session_id = session_id.clone();
    let writer_finalization = Arc::new(Mutex::new(CaptureFinalization::default()));
    let writer_finalization_for_thread = Arc::clone(&writer_finalization);
    let writer_result = Arc::new(Mutex::new(CaptureWriterResult::default()));
    let writer_result_for_thread = Arc::clone(&writer_result);
    let writer_partial_samples_path = partial_samples_path.clone();
    let writer_samples_path = samples_path.clone();
    let writer_receipt_path = receipt_path.clone();
    let writer_active_path = active_path.clone();
    let writer_started_at_monotonic_ns = started_at_monotonic_ns;
    let writer_duration_millis = duration_millis;
    let writer = thread::Builder::new()
        .name("breath-capture-writer".to_owned())
        .spawn(move || {
            let mut output = BufWriter::with_capacity(256 * 1024, file);
            let mut record_counts = BTreeMap::new();
            let mut durable_checkpoints = 0_u64;
            for record in receiver {
                if writeln!(output, "{}", record.line).is_err() {
                    writer_counters
                        .write_failures
                        .fetch_add(1, Ordering::Relaxed);
                    continue;
                }
                let written = writer_counters.written.fetch_add(1, Ordering::Relaxed) + 1;
                *record_counts.entry(record.kind.to_owned()).or_insert(0) += 1;
                if written % CHECKPOINT_RECORD_INTERVAL == 0 {
                    if output.flush().is_err() {
                        writer_counters
                            .write_failures
                            .fetch_add(1, Ordering::Relaxed);
                    } else {
                        durable_checkpoints = durable_checkpoints.saturating_add(1);
                    }
                }
            }
            if output.flush().is_err() {
                writer_counters
                    .write_failures
                    .fetch_add(1, Ordering::Relaxed);
            }
            let synced = match output.into_inner() {
                Ok(file) => file.sync_all().is_ok(),
                Err(_) => false,
            };
            if !synced {
                writer_counters
                    .write_failures
                    .fetch_add(1, Ordering::Relaxed);
            } else {
                durable_checkpoints = durable_checkpoints.saturating_add(1);
            }
            let write_failures = writer_counters.write_failures.load(Ordering::Relaxed);
            let dropped = writer_counters.dropped.load(Ordering::Relaxed);
            let finalized_samples = write_failures == 0
                && dropped == 0
                && fs::rename(&writer_partial_samples_path, &writer_samples_path).is_ok();
            if !finalized_samples {
                writer_counters
                    .write_failures
                    .fetch_add(1, Ordering::Relaxed);
            }
            let finalization = writer_finalization_for_thread
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let stop_reason = finalization.stop_reason.clone();
            let completed_at_monotonic_ns = finalization.completed_at_monotonic_ns;
            let observed_duration_millis = finalization
                .completed_at_monotonic_ns
                .saturating_sub(writer_started_at_monotonic_ns)
                / 1_000_000;
            let duration_complete = stop_reason == "duration-elapsed"
                && observed_duration_millis >= writer_duration_millis;
            let active_marker_removed =
                finalized_samples && fs::remove_file(&writer_active_path).is_ok();
            if finalized_samples && !active_marker_removed {
                writer_counters
                    .write_failures
                    .fetch_add(1, Ordering::Relaxed);
            }
            let receipt = json!({
                "schema": RECEIPT_SCHEMA_ID,
                "session_id": writer_session_id,
                "generation": generation,
                "stop_reason": stop_reason,
                "completed_at_monotonic_ns": completed_at_monotonic_ns,
                "target_duration_millis": writer_duration_millis,
                "observed_duration_millis": observed_duration_millis,
                "enqueued_records": writer_counters.enqueued.load(Ordering::Relaxed),
                "written_records": writer_counters.written.load(Ordering::Relaxed),
                "dropped_records": dropped,
                "write_failures": writer_counters.write_failures.load(Ordering::Relaxed),
                "durable_checkpoints": durable_checkpoints,
                "record_counts": record_counts,
                "finalized_samples": finalized_samples,
                "active_marker_removed": active_marker_removed,
                "complete": finalized_samples && active_marker_removed && duration_complete
            });
            drop(finalization);
            let receipt_written =
                write_json_file_atomically(&writer_receipt_path, &receipt).is_ok();
            if !receipt_written {
                writer_counters
                    .write_failures
                    .fetch_add(1, Ordering::Relaxed);
            }
            let mut result = writer_result_for_thread
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            result.finalized_samples = finalized_samples;
            result.complete = receipt["complete"].as_bool().unwrap_or(false);
            result.durable_checkpoints = durable_checkpoints;
            result.record_counts = receipt["record_counts"]
                .as_object()
                .map(|counts| {
                    counts
                        .iter()
                        .filter_map(|(kind, value)| {
                            value.as_u64().map(|count| (kind.clone(), count))
                        })
                        .collect()
                })
                .unwrap_or_default();
            result.receipt_written = receipt_written;
            writer_counters.active.store(false, Ordering::Relaxed);
        })
        .map_err(|error| format!("capture-writer-start:{error}"))?;
    let watchdog_cancel = Arc::new(AtomicBool::new(false));
    let watchdog_cancel_for_thread = Arc::clone(&watchdog_cancel);
    let watchdog_started = started;
    thread::Builder::new()
        .name("breath-capture-watchdog".to_owned())
        .spawn(move || {
            let deadline = watchdog_started + Duration::from_millis(duration_millis);
            while Instant::now() < deadline {
                thread::sleep(deadline.duration_since(Instant::now()));
            }
            if !watchdog_cancel_for_thread.load(Ordering::Relaxed) {
                let _ = stop_capture_generation(generation, "duration-elapsed");
            }
        })
        .map_err(|error| format!("capture-watchdog-start:{error}"))?;
    state.last_error = None;
    state.session = Some(CaptureSession {
        generation,
        session_id: session_id.clone(),
        directory: directory.clone(),
        started_at_monotonic_ns,
        duration_millis,
        started,
        sender,
        counters,
        finalization: writer_finalization,
        writer_result,
        watchdog_cancel,
        writer,
    });
    Ok(json!({
        "status": "started",
        "schema": CAPTURE_SCHEMA_ID,
        "session_id": session_id,
        "generation": generation,
        "directory": directory,
        "target_duration_millis": duration_millis,
        "samples_file": samples_path,
        "partial_samples_file": partial_samples_path
    }))
}

/// Stop the current capture, drain the queue, and join its writer.
pub(crate) fn stop_capture() -> Result<Value, String> {
    stop_capture_matching(None, "operator-stop")
}

fn stop_capture_generation(generation: u64, reason: &str) -> Result<Value, String> {
    stop_capture_matching(Some(generation), reason)
}

fn stop_capture_matching(expected_generation: Option<u64>, reason: &str) -> Result<Value, String> {
    let session = {
        let mut state = lock_runtime();
        if let Some(expected_generation) = expected_generation {
            if state
                .session
                .as_ref()
                .map_or(true, |session| session.generation != expected_generation)
            {
                return Err("capture-generation-not-active".to_owned());
            }
        }
        let session = state
            .session
            .take()
            .ok_or_else(|| "capture-not-active".to_owned())?;
        state.finalizing = true;
        session
    };
    let CaptureSession {
        generation,
        session_id,
        directory,
        started_at_monotonic_ns,
        duration_millis,
        started,
        sender,
        counters,
        finalization,
        writer_result,
        watchdog_cancel,
        writer,
    } = session;
    watchdog_cancel.store(true, Ordering::Relaxed);
    {
        let mut state = finalization
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.stop_reason = reason.to_owned();
        state.completed_at_monotonic_ns = started_at_monotonic_ns
            .saturating_add(started.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64);
    }
    drop(sender);
    if writer.join().is_err() {
        let mut state = lock_runtime();
        state.finalizing = false;
        state.last_error = Some("capture-writer-panicked".to_owned());
        return Err("capture-writer-panicked".to_owned());
    }
    let enqueued = counters.enqueued.load(Ordering::Relaxed);
    let written = counters.written.load(Ordering::Relaxed);
    let dropped = counters.dropped.load(Ordering::Relaxed);
    let write_failures = counters.write_failures.load(Ordering::Relaxed);
    let writer_result = writer_result
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let complete = writer_result.complete && writer_result.receipt_written;
    let mut state = lock_runtime();
    state.finalizing = false;
    state.last_session_id = Some(session_id.clone());
    state.last_directory = Some(directory.clone());
    state.last_enqueued = enqueued;
    state.last_written = written;
    state.last_dropped = dropped;
    state.last_write_failures = write_failures;
    state.last_stop_reason = Some(reason.to_owned());
    state.last_finalized_samples = writer_result.finalized_samples;
    state.last_receipt_written = writer_result.receipt_written;
    state.last_complete = complete;
    state.last_durable_checkpoints = writer_result.durable_checkpoints;
    state.last_duration_millis = duration_millis;
    Ok(json!({
        "status": "stopped",
        "schema": CAPTURE_SCHEMA_ID,
        "session_id": session_id,
        "generation": generation,
        "directory": directory,
        "enqueued_records": enqueued,
        "written_records": written,
        "dropped_records": dropped,
        "write_failures": write_failures,
        "durable_checkpoints": writer_result.durable_checkpoints,
        "record_counts": writer_result.record_counts,
        "finalized_samples": writer_result.finalized_samples,
        "complete": complete,
        "stop_reason": reason,
        "target_duration_millis": duration_millis
    }))
}

fn write_json_file_atomically(path: &Path, value: &Value) -> Result<(), String> {
    let temporary_path = path.with_extension(format!(
        "{}.partial",
        path.extension()
            .and_then(|value| value.to_str())
            .unwrap_or("json")
    ));
    let mut writer = BufWriter::new(
        File::create(&temporary_path).map_err(|error| format!("capture-json-create:{error}"))?,
    );
    serde_json::to_writer_pretty(&mut writer, value)
        .map_err(|error| format!("capture-json-serialize:{error}"))?;
    writer
        .write_all(b"\n")
        .map_err(|error| format!("capture-json-write:{error}"))?;
    writer
        .flush()
        .map_err(|error| format!("capture-json-flush:{error}"))?;
    let file = writer
        .into_inner()
        .map_err(|error| format!("capture-json-close:{}", error.error()))?;
    file.sync_all()
        .map_err(|error| format!("capture-json-sync:{error}"))?;
    drop(file);
    fs::rename(&temporary_path, path).map_err(|error| format!("capture-json-finalize:{error}"))
}

fn enqueue(kind: &'static str, fields: Value) {
    let mut state = lock_runtime();
    let Some(session) = state.session.as_ref() else {
        return;
    };
    let line = json!({
        "schema": CAPTURE_SCHEMA_ID,
        "capture_generation": session.generation,
        "kind": kind,
        "fields": fields
    })
    .to_string();
    match session.sender.try_send(CaptureRecord { kind, line }) {
        Ok(()) => {
            session.counters.enqueued.fetch_add(1, Ordering::Relaxed);
        }
        Err(TrySendError::Full(_)) => {
            session.counters.dropped.fetch_add(1, Ordering::Relaxed);
        }
        Err(TrySendError::Disconnected(_)) => {
            session
                .counters
                .write_failures
                .fetch_add(1, Ordering::Relaxed);
            state.last_error = Some("capture-writer-disconnected".to_owned());
        }
    }
}

pub(crate) fn record_polar_frame(
    kind: &'static str,
    frame_sequence_id: u64,
    host_receipt_time_ns: u64,
    sensor_frame_time_ns: u64,
    sample_rate_hz: u32,
    sample_count: usize,
    previous_receipt_delta_ns: Option<u64>,
) {
    enqueue(
        kind,
        json!({
            "frame_sequence_id": frame_sequence_id,
            "host_receipt_time_ns": host_receipt_time_ns,
            "sensor_frame_time_ns": sensor_frame_time_ns,
            "sample_rate_hz": sample_rate_hz,
            "sample_count": sample_count,
            "previous_receipt_delta_ns": previous_receipt_delta_ns
        }),
    );
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn record_polar_acc_sample(
    frame_sequence_id: u64,
    sample_index: usize,
    sample_count: usize,
    frame_host_receipt_time_ns: u64,
    sample_host_time_ns: u64,
    sample_sensor_time_ns: u64,
    jni_submit_time_ns: u64,
    xyz_mg: [f32; 3],
) {
    enqueue(
        "polar_acc_sample",
        json!({
            "frame_sequence_id": frame_sequence_id,
            "sample_index": sample_index,
            "sample_count": sample_count,
            "frame_host_receipt_time_ns": frame_host_receipt_time_ns,
            "sample_host_time_ns": sample_host_time_ns,
            "sample_sensor_time_ns": sample_sensor_time_ns,
            "jni_submit_time_ns": jni_submit_time_ns,
            "xyz_mg": xyz_mg
        }),
    );
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn record_polar_ecg_sample(
    frame_sequence_id: u64,
    sample_index: usize,
    sample_count: usize,
    frame_host_receipt_time_ns: u64,
    sample_host_time_ns: u64,
    sample_sensor_time_ns: u64,
    jni_submit_time_ns: u64,
    microvolts: i32,
) {
    enqueue(
        "polar_ecg_sample",
        json!({
            "frame_sequence_id": frame_sequence_id,
            "sample_index": sample_index,
            "sample_count": sample_count,
            "frame_host_receipt_time_ns": frame_host_receipt_time_ns,
            "sample_host_time_ns": sample_host_time_ns,
            "sample_sensor_time_ns": sample_sensor_time_ns,
            "jni_submit_time_ns": jni_submit_time_ns,
            "microvolts": microvolts
        }),
    );
}

pub(crate) fn record_polar_hr(host_time_ns: u64, bpm: u32) {
    enqueue(
        "polar_hr",
        json!({"host_time_ns": host_time_ns, "bpm": bpm}),
    );
}

pub(crate) fn record_polar_rr(host_time_ns: u64, rr_interval_ms: f32) {
    enqueue(
        "polar_rr",
        json!({
            "host_time_ns": host_time_ns,
            "rr_interval_ms": rr_interval_ms,
            "consumed_by_breath": false
        }),
    );
}

pub(crate) fn record_polar_acc_presentation(status: Value) {
    enqueue("polar_acc_presentation", status);
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn record_controller_pose(
    frame_sequence_id: u64,
    observed_at_micros: u64,
    xr_time_ns: i64,
    position_m: [f32; 3],
    orientation_xyzw: [f32; 4],
    action_active: bool,
    tracked: bool,
) {
    enqueue(
        "controller_pose",
        json!({
            "frame_sequence_id": frame_sequence_id,
            "observed_at_micros": observed_at_micros,
            "xr_time_ns": xr_time_ns,
            "position_m": position_m,
            "orientation_xyzw": orientation_xyzw,
            "action_active": action_active,
            "tracked": tracked
        }),
    );
}

pub(crate) fn record_controller_missing(frame_sequence_id: u64, observed_at_micros: u64) {
    enqueue(
        "controller_pose",
        json!({
            "frame_sequence_id": frame_sequence_id,
            "observed_at_micros": observed_at_micros,
            "tracked": false,
            "reason": "missing"
        }),
    );
}

pub(crate) fn record_controller_right_thumbstick(
    frame_sequence_id: u64,
    observed_at_micros: u64,
    xr_time_ns: i64,
    action_active: bool,
    right_stick_y: Option<f32>,
    availability: &'static str,
) {
    enqueue(
        "controller_right_thumbstick",
        json!({
            "frame_sequence_id": frame_sequence_id,
            "observed_at_micros": observed_at_micros,
            "xr_time_ns": xr_time_ns,
            "action_active": action_active,
            "right_stick_y": right_stick_y,
            "availability": availability
        }),
    );
}

pub(crate) fn record_assessment(
    source: BreathCompositionSource,
    assessment: BreathAssessmentObservation,
    snapshot: BreathCompositionSnapshot,
) {
    enqueue(
        "breath_assessment",
        json!({
            "source": source.as_str(),
            "sequence_id": assessment.sequence_id,
            "sampled_at_micros": assessment.sampled_at.get(),
            "observed_at_micros": assessment.observed_at.get(),
            "volume01": assessment.volume01,
            "phase": assessment.phase.as_str(),
            "calibration": assessment.calibration.as_str(),
            "tracking": assessment.tracking.as_str(),
            "quality01": assessment.quality01,
            "composition_generation": snapshot.generation.map(|value| value.get()),
            "effective_mapping": snapshot.effective.map(|value| value.mapping.as_str())
        }),
    );
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn record_driver_apply(
    frame_sequence_id: u64,
    observed_at_micros: u64,
    target_slot: usize,
    value01: f32,
    snapshot: BreathCompositionSnapshot,
) {
    enqueue(
        "driver_apply",
        json!({
            "frame_sequence_id": frame_sequence_id,
            "observed_at_micros": observed_at_micros,
            "target_slot": target_slot,
            "value01": value01,
            "composition_generation": snapshot.generation.map(|value| value.get()),
            "source": snapshot.effective.map(|value| value.source.as_str()),
            "mapping": snapshot.effective.map(|value| value.mapping.as_str()),
            "source_sequence_id": snapshot.output.map(|value| value.sequence_id),
            "source_sampled_at_micros": snapshot.output.map(|value| value.sampled_at.get())
        }),
    );
}

pub(crate) fn status_value() -> Value {
    let state = lock_runtime();
    if let Some(session) = state.session.as_ref() {
        let elapsed_millis = session
            .started
            .elapsed()
            .as_millis()
            .min(u128::from(u64::MAX)) as u64;
        let remaining_millis = session.duration_millis.saturating_sub(elapsed_millis);
        return json!({
            "schema": CAPTURE_SCHEMA_ID,
            "status": "recording",
            "active": session.counters.active.load(Ordering::Relaxed),
            "session_id": session.session_id,
            "generation": session.generation,
            "directory": session.directory,
            "started_at_monotonic_ns": session.started_at_monotonic_ns,
            "target_duration_millis": session.duration_millis,
            "elapsed_millis": elapsed_millis,
            "remaining_millis": remaining_millis,
            "enqueued_records": session.counters.enqueued.load(Ordering::Relaxed),
            "written_records": session.counters.written.load(Ordering::Relaxed),
            "dropped_records": session.counters.dropped.load(Ordering::Relaxed),
            "write_failures": session.counters.write_failures.load(Ordering::Relaxed),
            "last_error": state.last_error
        });
    }
    if state.finalizing {
        return json!({
            "schema": CAPTURE_SCHEMA_ID,
            "status": "finalizing",
            "active": true,
            "accepting_records": false,
            "session_id": state.last_session_id,
            "last_error": state.last_error
        });
    }
    json!({
        "schema": CAPTURE_SCHEMA_ID,
        "status": "stopped",
        "active": false,
        "session_id": state.last_session_id,
        "directory": state.last_directory,
        "enqueued_records": state.last_enqueued,
        "written_records": state.last_written,
        "dropped_records": state.last_dropped,
        "write_failures": state.last_write_failures,
        "stop_reason": state.last_stop_reason,
        "finalized_samples": state.last_finalized_samples,
        "receipt_written": state.last_receipt_written,
        "durable_checkpoints": state.last_durable_checkpoints,
        "target_duration_millis": state.last_duration_millis,
        "complete": state.last_complete,
        "last_error": state.last_error
    })
}

#[cfg(target_os = "android")]
fn jni_string_response(mut env: jni::EnvUnowned, value: Value) -> jni::sys::jstring {
    match env
        .with_env(|env| {
            env.new_string(value.to_string())
                .map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeStartParallelBreathCapture(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    directory: jni::objects::JString,
    session_id: jni::objects::JString,
    started_at_monotonic_ns: jni::sys::jlong,
) -> jni::sys::jstring {
    let result = env
        .with_env(|env| {
            let directory = directory.try_to_string(env)?;
            let session_id = session_id.try_to_string(env)?;
            Ok::<_, jni::errors::Error>((directory, session_id))
        })
        .into_outcome();
    let value = match result {
        jni::Outcome::Ok((directory, session_id)) => {
            if started_at_monotonic_ns <= 0 {
                json!({"status":"rejected", "reason_code":"capture-start-time-invalid"})
            } else if !crate::breath_composition_runtime::feature_lock_active() {
                json!({"status":"rejected", "reason_code":"capture-feature-lock-inactive"})
            } else {
                match start_capture(&directory, &session_id, started_at_monotonic_ns as u64) {
                    Ok(value) => value,
                    Err(reason) => json!({"status":"rejected", "reason_code":reason}),
                }
            }
        }
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => {
            json!({"status":"rejected", "reason_code":"jni-string-invalid"})
        }
    };
    jni_string_response(env, value)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeStopParallelBreathCapture(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    let value = match stop_capture() {
        Ok(value) => value,
        Err(reason) => json!({"status":"rejected", "reason_code":reason}),
    };
    jni_string_response(env, value)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_PolarSensorPanel_nativeReadParallelBreathCaptureStatus(
    env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    jni_string_response(env, status_value())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::atomic::{AtomicU64, Ordering},
        sync::OnceLock,
        time::{SystemTime, UNIX_EPOCH},
    };

    fn capture_test_guard() -> std::sync::MutexGuard<'static, ()> {
        static GUARD: OnceLock<Mutex<()>> = OnceLock::new();
        GUARD
            .get_or_init(|| Mutex::new(()))
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn temp_capture_dir(label: &str) -> PathBuf {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let suffix = NEXT.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!(
            "rusty-quest-breath-capture-{label}-{}-{suffix}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ))
    }

    #[test]
    fn capture_writes_synchronized_neutral_rows_and_receipt() {
        let _guard = capture_test_guard();
        let directory = temp_capture_dir("round-trip");
        let started = start_capture(directory.to_str().unwrap(), "capture_1", 1_000_000).unwrap();
        assert_eq!(started["status"], "started");
        record_polar_frame("polar_acc_frame", 1, 200, 100, 200, 2, None);
        record_polar_acc_sample(1, 0, 2, 200, 195, 95, 201, [1.0, 2.0, 3.0]);
        record_controller_pose(
            1,
            1_000,
            1_000_000,
            [0.1; 3],
            [0.0, 0.0, 0.0, 1.0],
            true,
            true,
        );
        record_controller_right_thumbstick(1, 1_000, 1_000_000, true, Some(0.75), "available");
        record_polar_ecg_sample(1, 0, 1, 200, 200, 200, 201, 17);
        record_polar_hr(202, 72);
        record_polar_rr(203, 810.0);
        let stopped = stop_capture().unwrap();
        assert_eq!(stopped["complete"], false);
        assert_eq!(stopped["finalized_samples"], true);
        assert_eq!(
            stopped["target_duration_millis"],
            FIXED_CAPTURE_DURATION_MILLIS
        );
        assert_eq!(stopped["stop_reason"], "operator-stop");
        assert!(stopped["written_records"].as_u64().unwrap_or(0) >= 7);
        assert_eq!(stopped["written_records"], stopped["enqueued_records"]);
        let lines = fs::read_to_string(directory.join("breath_source_samples.jsonl")).unwrap();
        assert!(lines.contains("\"kind\":\"polar_acc_frame\""));
        assert!(lines.contains("\"kind\":\"polar_acc_sample\""));
        assert!(lines.contains("\"kind\":\"controller_pose\""));
        assert!(lines.contains("\"kind\":\"controller_right_thumbstick\""));
        assert!(lines.contains("\"kind\":\"polar_ecg_sample\""));
        assert!(lines.contains("\"kind\":\"polar_hr\""));
        assert!(lines.contains("\"kind\":\"polar_rr\""));
        assert!(!directory
            .join("breath_source_samples.partial.jsonl")
            .exists());
        assert!(!directory.join("capture.active.json").exists());
        let receipt: Value = serde_json::from_str(
            &fs::read_to_string(directory.join("capture_receipt.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(receipt["complete"], false);
        assert_eq!(
            receipt["target_duration_millis"],
            FIXED_CAPTURE_DURATION_MILLIS
        );
        assert_eq!(receipt["dropped_records"], 0);
        assert_eq!(receipt["record_counts"]["controller_right_thumbstick"], 1);
        assert_eq!(receipt["record_counts"]["polar_rr"], 1);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn capture_rejects_unsafe_identity_and_relative_directory() {
        let _guard = capture_test_guard();
        assert_eq!(
            start_capture("relative", "capture_1", 1).unwrap_err(),
            "capture-directory-must-be-absolute"
        );
        let directory = temp_capture_dir("invalid-id");
        assert_eq!(
            start_capture(directory.to_str().unwrap(), "bad id", 1).unwrap_err(),
            "session-id-invalid"
        );
        let _ = fs::remove_dir_all(directory);
    }

    #[test]
    fn capture_watchdog_finalizes_only_its_current_generation() {
        let _guard = capture_test_guard();
        let first_directory = temp_capture_dir("watchdog-first");
        let first = start_capture_with_duration(
            first_directory.to_str().unwrap(),
            "capture_first",
            2_000_000,
            25,
        )
        .unwrap();
        let first_generation = first["generation"].as_u64().unwrap();
        record_controller_right_thumbstick(1, 2_000, 2_000_000, true, Some(-0.5), "available");
        for _ in 0..40 {
            if status_value()["status"] == "stopped" {
                break;
            }
            thread::sleep(Duration::from_millis(5));
        }
        let first_status = status_value();
        assert_eq!(first_status["status"], "stopped");
        assert_eq!(first_status["stop_reason"], "duration-elapsed");
        assert_eq!(first_status["complete"], true);

        let second_directory = temp_capture_dir("watchdog-second");
        let second = start_capture_with_duration(
            second_directory.to_str().unwrap(),
            "capture_second",
            3_000_000,
            FIXED_CAPTURE_DURATION_MILLIS,
        )
        .unwrap();
        assert!(stop_capture_generation(first_generation, "stale-watchdog").is_err());
        assert_eq!(status_value()["generation"], second["generation"]);
        assert_eq!(stop_capture().unwrap()["complete"], false);

        fs::remove_dir_all(first_directory).unwrap();
        fs::remove_dir_all(second_directory).unwrap();
    }
}
