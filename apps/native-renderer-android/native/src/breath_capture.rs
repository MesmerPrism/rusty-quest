//! Neutral, bounded, app-private capture for synchronized breath-source replay.
//!
//! The capture is an observation sink only. It never owns sensor acquisition,
//! assessment, selection, or driver application. Producers use a bounded
//! non-blocking queue so file I/O cannot stall OpenXR or Bluetooth callbacks.

use std::{
    fs::{self, File},
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender, TrySendError},
        Arc, Mutex, MutexGuard, OnceLock,
    },
    thread::{self, JoinHandle},
};

use rusty_quest_breath_contract::{
    assessment::BreathAssessmentObservation,
    composition::{BreathCompositionSnapshot, BreathCompositionSource},
};
use serde_json::{json, Value};

const CAPTURE_SCHEMA_ID: &str = "rusty.quest.breath_source_capture.v1";
const MANIFEST_SCHEMA_ID: &str = "rusty.quest.breath_source_capture_manifest.v1";
const RECEIPT_SCHEMA_ID: &str = "rusty.quest.breath_source_capture_receipt.v1";
const QUEUE_CAPACITY: usize = 8_192;

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
    sender: SyncSender<String>,
    counters: Arc<CaptureCounters>,
    writer: JoinHandle<()>,
}

#[derive(Debug, Default)]
struct CaptureRuntime {
    next_generation: u64,
    session: Option<CaptureSession>,
    last_session_id: Option<String>,
    last_directory: Option<PathBuf>,
    last_enqueued: u64,
    last_written: u64,
    last_dropped: u64,
    last_write_failures: u64,
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

/// Start one capture in an exact app-owned directory.
pub(crate) fn start_capture(directory: &str, session_id: &str) -> Result<Value, String> {
    let session_id = validate_token(session_id, "session-id")?;
    let directory = validate_directory(directory)?;
    let polar_acc_presentation = crate::polar_composition_adapters::polar_acc_presentation_status();
    let mut state = lock_runtime();
    if state.session.is_some() {
        return Err("capture-already-active".to_owned());
    }
    state.next_generation = state.next_generation.saturating_add(1).max(1);
    let generation = state.next_generation;
    let samples_path = directory.join("breath_source_samples.jsonl");
    let manifest_path = directory.join("capture_manifest.json");
    let receipt_path = directory.join("capture_receipt.json");
    let manifest = json!({
        "schema": MANIFEST_SCHEMA_ID,
        "capture_schema": CAPTURE_SCHEMA_ID,
        "session_id": session_id,
        "generation": generation,
        "queue_capacity": QUEUE_CAPACITY,
        "clock_contract": {
            "host_time_ns": "android-monotonic",
            "sensor_time_ns": "source-native-when-available",
            "xr_time_ns": "openxr-runtime"
        },
        "streams": [
            "controller_pose",
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
    write_json_file(&manifest_path, &manifest)?;
    let file =
        File::create(&samples_path).map_err(|error| format!("capture-samples-create:{error}"))?;
    let (sender, receiver) = mpsc::sync_channel::<String>(QUEUE_CAPACITY);
    let counters = Arc::new(CaptureCounters::new());
    let writer_counters = Arc::clone(&counters);
    let writer_session_id = session_id.clone();
    let writer = thread::Builder::new()
        .name("breath-capture-writer".to_owned())
        .spawn(move || {
            let mut output = BufWriter::with_capacity(256 * 1024, file);
            for line in receiver {
                if writeln!(output, "{line}").is_err() {
                    writer_counters
                        .write_failures
                        .fetch_add(1, Ordering::Relaxed);
                    continue;
                }
                let written = writer_counters.written.fetch_add(1, Ordering::Relaxed) + 1;
                if written % 256 == 0 && output.flush().is_err() {
                    writer_counters
                        .write_failures
                        .fetch_add(1, Ordering::Relaxed);
                }
            }
            if output.flush().is_err() {
                writer_counters
                    .write_failures
                    .fetch_add(1, Ordering::Relaxed);
            }
            writer_counters.active.store(false, Ordering::Relaxed);
            let receipt = json!({
                "schema": RECEIPT_SCHEMA_ID,
                "session_id": writer_session_id,
                "generation": generation,
                "enqueued_records": writer_counters.enqueued.load(Ordering::Relaxed),
                "written_records": writer_counters.written.load(Ordering::Relaxed),
                "dropped_records": writer_counters.dropped.load(Ordering::Relaxed),
                "write_failures": writer_counters.write_failures.load(Ordering::Relaxed),
                "complete": writer_counters.dropped.load(Ordering::Relaxed) == 0
                    && writer_counters.write_failures.load(Ordering::Relaxed) == 0
            });
            let _ = write_json_file(&receipt_path, &receipt);
        })
        .map_err(|error| format!("capture-writer-start:{error}"))?;
    state.last_error = None;
    state.session = Some(CaptureSession {
        generation,
        session_id: session_id.clone(),
        directory: directory.clone(),
        sender,
        counters,
        writer,
    });
    Ok(json!({
        "status": "started",
        "schema": CAPTURE_SCHEMA_ID,
        "session_id": session_id,
        "generation": generation,
        "directory": directory,
        "samples_file": samples_path
    }))
}

/// Stop the current capture, drain the queue, and join its writer.
pub(crate) fn stop_capture() -> Result<Value, String> {
    let session = {
        let mut state = lock_runtime();
        state
            .session
            .take()
            .ok_or_else(|| "capture-not-active".to_owned())?
    };
    let CaptureSession {
        generation,
        session_id,
        directory,
        sender,
        counters,
        writer,
    } = session;
    drop(sender);
    if writer.join().is_err() {
        let mut state = lock_runtime();
        state.last_error = Some("capture-writer-panicked".to_owned());
        return Err("capture-writer-panicked".to_owned());
    }
    let enqueued = counters.enqueued.load(Ordering::Relaxed);
    let written = counters.written.load(Ordering::Relaxed);
    let dropped = counters.dropped.load(Ordering::Relaxed);
    let write_failures = counters.write_failures.load(Ordering::Relaxed);
    let mut state = lock_runtime();
    state.last_session_id = Some(session_id.clone());
    state.last_directory = Some(directory.clone());
    state.last_enqueued = enqueued;
    state.last_written = written;
    state.last_dropped = dropped;
    state.last_write_failures = write_failures;
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
        "complete": dropped == 0 && write_failures == 0
    }))
}

fn write_json_file(path: &Path, value: &Value) -> Result<(), String> {
    let mut file =
        BufWriter::new(File::create(path).map_err(|error| format!("capture-json-create:{error}"))?);
    serde_json::to_writer_pretty(&mut file, value)
        .map_err(|error| format!("capture-json-serialize:{error}"))?;
    file.write_all(b"\n")
        .map_err(|error| format!("capture-json-write:{error}"))?;
    file.flush()
        .map_err(|error| format!("capture-json-flush:{error}"))
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
    match session.sender.try_send(line) {
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
        return json!({
            "schema": CAPTURE_SCHEMA_ID,
            "status": "recording",
            "active": session.counters.active.load(Ordering::Relaxed),
            "session_id": session.session_id,
            "generation": session.generation,
            "directory": session.directory,
            "enqueued_records": session.counters.enqueued.load(Ordering::Relaxed),
            "written_records": session.counters.written.load(Ordering::Relaxed),
            "dropped_records": session.counters.dropped.load(Ordering::Relaxed),
            "write_failures": session.counters.write_failures.load(Ordering::Relaxed),
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
            if !crate::breath_composition_runtime::feature_lock_active() {
                json!({"status":"rejected", "reason_code":"capture-feature-lock-inactive"})
            } else {
                match start_capture(&directory, &session_id) {
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
        time::{SystemTime, UNIX_EPOCH},
    };

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
        let directory = temp_capture_dir("round-trip");
        let started = start_capture(directory.to_str().unwrap(), "capture_1").unwrap();
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
        let stopped = stop_capture().unwrap();
        assert_eq!(stopped["complete"], true);
        assert!(stopped["written_records"].as_u64().unwrap_or(0) >= 3);
        assert_eq!(stopped["written_records"], stopped["enqueued_records"]);
        let lines = fs::read_to_string(directory.join("breath_source_samples.jsonl")).unwrap();
        assert!(lines.contains("\"kind\":\"polar_acc_frame\""));
        assert!(lines.contains("\"kind\":\"polar_acc_sample\""));
        assert!(lines.contains("\"kind\":\"controller_pose\""));
        let receipt: Value = serde_json::from_str(
            &fs::read_to_string(directory.join("capture_receipt.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(receipt["complete"], true);
        assert_eq!(receipt["dropped_records"], 0);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn capture_rejects_unsafe_identity_and_relative_directory() {
        assert_eq!(
            start_capture("relative", "capture_1").unwrap_err(),
            "capture-directory-must-be-absolute"
        );
        let directory = temp_capture_dir("invalid-id");
        assert_eq!(
            start_capture(directory.to_str().unwrap(), "bad id").unwrap_err(),
            "session-id-invalid"
        );
        let _ = fs::remove_dir_all(directory);
    }
}
