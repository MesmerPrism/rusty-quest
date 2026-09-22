//! Read-only valid-prefix recovery for experiment recordings.
//!
//! Recovery never treats a corrupt tail as durable evidence and never deletes
//! or rewrites physiological data. The latest valid append-only checkpoint is
//! the boundary for an interrupted session.

use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File},
    io::{BufRead, BufReader, Read},
    path::{Path, PathBuf},
};

use serde_json::Value;

use crate::{
    session_recording_clock::SessionTimestampKey,
    session_recording_contract::{
        ConditionKey, ExperimentIdentity, SessionCounts, SessionEventKind,
        SESSION_CHECKPOINT_SCHEMA, SESSION_FINAL_SCHEMA, SESSION_MANIFEST_SCHEMA,
        SESSION_ROW_SCHEMA, SESSION_STREAM_FILES,
    },
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RecoveryDisposition {
    FinalizedCompleted,
    FinalizedEarly,
    InterruptedCompleted,
    InterruptedEarly,
    CorruptEarly,
}

impl RecoveryDisposition {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::FinalizedCompleted => "finalized-completed",
            Self::FinalizedEarly => "finalized-early",
            Self::InterruptedCompleted => "interrupted-completed",
            Self::InterruptedEarly => "interrupted-early",
            Self::CorruptEarly => "corrupt-early",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct RecoveredSession {
    pub(crate) started_at: SessionTimestampKey,
    pub(crate) experiment_identity: Option<ExperimentIdentity>,
    pub(crate) disposition: RecoveryDisposition,
    pub(crate) durable_stream_lengths: BTreeMap<String, u64>,
    pub(crate) error: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct RecoveredConditionCounts {
    pub(crate) condition_a: SessionCounts,
    pub(crate) condition_b: SessionCounts,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct RecoveryReport {
    pub(crate) sessions: Vec<RecoveredSession>,
    pub(crate) counts: SessionCounts,
    pub(crate) condition_counts: RecoveredConditionCounts,
}

pub(crate) fn recover_recordings(root: &Path) -> Result<RecoveryReport, String> {
    validate_recording_root_no_links(root)?;
    if !root.exists() {
        return Ok(RecoveryReport::default());
    }
    let mut directories = fs::read_dir(root)
        .map_err(|error| format!("recording-root-read:{error}"))?
        .filter_map(Result::ok)
        .filter_map(|entry| {
            entry
                .file_type()
                .ok()
                .filter(|file_type| file_type.is_dir())
                .map(|_| entry.path())
        })
        .collect::<Vec<_>>();
    directories.sort();

    let mut report = RecoveryReport::default();
    for directory in directories {
        let Some(name) = directory.file_name().and_then(|value| value.to_str()) else {
            continue;
        };
        let Ok(started_at) = SessionTimestampKey::parse(name) else {
            continue;
        };
        // Bind the per-condition summary while still on the recording actor.
        // A corrupt/unreadable manifest stays in aggregate counts only and is
        // never guessed into a condition bucket.
        let manifest = validate_manifest(&directory, &started_at).ok();
        let recovered = recover_session(&directory, started_at);
        match recovered.disposition {
            RecoveryDisposition::FinalizedCompleted | RecoveryDisposition::InterruptedCompleted => {
                report.counts.completed = report.counts.completed.saturating_add(1);
            }
            RecoveryDisposition::FinalizedEarly | RecoveryDisposition::InterruptedEarly => {
                report.counts.stopped_early = report.counts.stopped_early.saturating_add(1);
            }
            RecoveryDisposition::CorruptEarly => {
                report.counts.stopped_early = report.counts.stopped_early.saturating_add(1);
            }
        }
        if recovered.error.is_some() {
            report.counts.errors = report.counts.errors.saturating_add(1);
        }
        if let Some(manifest) = manifest.as_ref() {
            add_condition_count(
                &mut report.condition_counts,
                &manifest.condition,
                &recovered,
            );
        }
        report.sessions.push(recovered);
    }
    Ok(report)
}

pub(crate) fn validate_recording_root_no_links(root: &Path) -> Result<(), String> {
    if !root.is_absolute()
        || root.file_name().and_then(|value| value.to_str()) != Some("viscereality-recordings")
    {
        return Err("recording-root-contract-invalid".to_owned());
    }
    // The trust boundary starts at Android's app-private `files` directory. Do
    // not reject platform-owned ancestors: Quest exposes `/data/user/0` through
    // a system symlink on supported firmware. The app cannot replace that
    // ancestor, while it can influence its direct recording root and parent.
    let parent = root
        .parent()
        .ok_or_else(|| "recording-root-parent-missing".to_owned())?;
    for (path, is_root) in [(parent, false), (root, true)] {
        if !path.exists() {
            continue;
        }
        let metadata = fs::symlink_metadata(path)
            .map_err(|error| format!("recording-root-metadata:{error}"))?;
        if metadata.file_type().is_symlink() {
            return Err("recording-root-symlink-rejected".to_owned());
        }
        if !metadata.is_dir() {
            return Err(if is_root {
                "recording-root-not-directory".to_owned()
            } else {
                "recording-root-parent-not-directory".to_owned()
            });
        }
    }
    Ok(())
}

fn add_condition_count(
    counts: &mut RecoveredConditionCounts,
    condition: &ConditionKey,
    recovered: &RecoveredSession,
) {
    let target = match condition.as_str() {
        "condition-a" => &mut counts.condition_a,
        "condition-b" => &mut counts.condition_b,
        _ => return,
    };
    match recovered.disposition {
        RecoveryDisposition::FinalizedCompleted | RecoveryDisposition::InterruptedCompleted => {
            target.completed = target.completed.saturating_add(1);
        }
        RecoveryDisposition::FinalizedEarly
        | RecoveryDisposition::InterruptedEarly
        | RecoveryDisposition::CorruptEarly => {
            target.stopped_early = target.stopped_early.saturating_add(1);
        }
    }
    if recovered.error.is_some() {
        target.errors = target.errors.saturating_add(1);
    }
}

fn recover_session(directory: &Path, started_at: SessionTimestampKey) -> RecoveredSession {
    let manifest = match validate_manifest(directory, &started_at) {
        Ok(manifest) => manifest,
        Err(error) => {
            return RecoveredSession {
                started_at,
                experiment_identity: None,
                disposition: RecoveryDisposition::CorruptEarly,
                durable_stream_lengths: BTreeMap::new(),
                error: Some(error),
            };
        }
    };

    let final_path = directory.join("final.json");
    if final_path.exists() {
        match read_json(&final_path).and_then(|value| {
            validate_terminal_value(
                &value,
                SESSION_FINAL_SCHEMA,
                &started_at,
                &manifest,
                directory,
            )
        }) {
            Ok((completed, lengths, error)) => {
                return RecoveredSession {
                    started_at,
                    experiment_identity: manifest.experiment_identity.clone(),
                    disposition: if completed {
                        RecoveryDisposition::FinalizedCompleted
                    } else {
                        RecoveryDisposition::FinalizedEarly
                    },
                    durable_stream_lengths: lengths,
                    error,
                };
            }
            Err(error) => {
                return recover_from_checkpoint(
                    directory,
                    started_at,
                    &manifest,
                    Some(format!("recording-final-invalid:{error}")),
                );
            }
        }
    }

    recover_from_checkpoint(directory, started_at, &manifest, None)
}

fn recover_from_checkpoint(
    directory: &Path,
    started_at: SessionTimestampKey,
    manifest: &ValidatedManifest,
    retained_error: Option<String>,
) -> RecoveredSession {
    match latest_valid_checkpoint(directory, &started_at, manifest) {
        Ok(Some((completed, lengths, checkpoint_error))) => RecoveredSession {
            started_at,
            experiment_identity: manifest.experiment_identity.clone(),
            disposition: if completed {
                RecoveryDisposition::InterruptedCompleted
            } else {
                RecoveryDisposition::InterruptedEarly
            },
            durable_stream_lengths: lengths,
            error: combine_errors(retained_error, checkpoint_error),
        },
        Ok(None) => RecoveredSession {
            started_at,
            experiment_identity: manifest.experiment_identity.clone(),
            disposition: RecoveryDisposition::CorruptEarly,
            durable_stream_lengths: BTreeMap::new(),
            error: Some(
                retained_error.unwrap_or_else(|| "recording-checkpoint-missing".to_owned()),
            ),
        },
        Err(error) => RecoveredSession {
            started_at,
            experiment_identity: manifest.experiment_identity.clone(),
            disposition: RecoveryDisposition::CorruptEarly,
            durable_stream_lengths: BTreeMap::new(),
            error: Some(match retained_error {
                Some(retained) => format!("{retained};recording-checkpoint-invalid:{error}"),
                None => error,
            }),
        },
    }
}

#[derive(Clone, Debug)]
struct ValidatedManifest {
    condition: ConditionKey,
    experiment_identity: Option<ExperimentIdentity>,
}

fn validate_manifest(
    directory: &Path,
    started_at: &SessionTimestampKey,
) -> Result<ValidatedManifest, String> {
    let value = read_json(&directory.join("manifest.json"))?;
    if value.get("schema").and_then(Value::as_str) != Some(SESSION_MANIFEST_SCHEMA) {
        return Err("recording-manifest-schema-invalid".to_owned());
    }
    if value.get("started_at_utc").and_then(Value::as_str) != Some(started_at.as_str()) {
        return Err("recording-manifest-start-time-mismatch".to_owned());
    }
    let condition = value
        .get("condition")
        .and_then(Value::as_str)
        .ok_or_else(|| "recording-manifest-condition-missing".to_owned())?;
    let condition = ConditionKey::parse(condition).map_err(str::to_owned)?;
    let experiment_identity = value
        .get("experiment_identity")
        .map(|identity| ExperimentIdentity::parse_encoded(identity, &condition))
        .transpose()
        .map_err(str::to_owned)?;
    Ok(ValidatedManifest {
        condition,
        experiment_identity,
    })
}

fn latest_valid_checkpoint(
    directory: &Path,
    started_at: &SessionTimestampKey,
    manifest: &ValidatedManifest,
) -> Result<Option<(bool, BTreeMap<String, u64>, Option<String>)>, String> {
    let mut checkpoints = checkpoint_paths(directory)?;
    checkpoints.sort();
    checkpoints.reverse();
    let mut last_error = None;
    for path in checkpoints {
        match read_json(&path).and_then(|value| {
            validate_terminal_value(
                &value,
                SESSION_CHECKPOINT_SCHEMA,
                started_at,
                manifest,
                directory,
            )
        }) {
            Ok(value) => return Ok(Some(value)),
            Err(error) => last_error = Some(error),
        }
    }
    if let Some(error) = last_error {
        return Err(error);
    }
    Ok(None)
}

fn combine_errors(first: Option<String>, second: Option<String>) -> Option<String> {
    match (first, second) {
        (Some(first), Some(second)) => Some(format!("{first};{second}")),
        (Some(error), None) | (None, Some(error)) => Some(error),
        (None, None) => None,
    }
}

fn checkpoint_paths(directory: &Path) -> Result<Vec<PathBuf>, String> {
    Ok(fs::read_dir(directory)
        .map_err(|error| format!("recording-directory-read:{error}"))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|value| value.to_str())
                .is_some_and(|name| {
                    name.starts_with("checkpoint-")
                        && name.ends_with(".json")
                        && !name.ends_with(".partial.json")
                })
        })
        .collect())
}

fn validate_terminal_value(
    value: &Value,
    schema: &str,
    started_at: &SessionTimestampKey,
    manifest: &ValidatedManifest,
    directory: &Path,
) -> Result<(bool, BTreeMap<String, u64>, Option<String>), String> {
    if value.get("schema").and_then(Value::as_str) != Some(schema) {
        return Err("recording-terminal-schema-invalid".to_owned());
    }
    if value.get("started_at_utc").and_then(Value::as_str) != Some(started_at.as_str()) {
        return Err("recording-terminal-start-time-mismatch".to_owned());
    }
    if value.get("condition").and_then(Value::as_str) != Some(manifest.condition.as_str()) {
        return Err("recording-terminal-condition-mismatch".to_owned());
    }
    if let Some(expected_identity) = manifest.experiment_identity.as_ref() {
        let terminal_identity = ExperimentIdentity::parse_encoded(
            value
                .get("experiment_identity")
                .ok_or_else(|| "recording-terminal-experiment-identity-missing".to_owned())?,
            &manifest.condition,
        )
        .map_err(str::to_owned)?;
        if &terminal_identity != expected_identity {
            return Err("recording-terminal-experiment-identity-mismatch".to_owned());
        }
    } else if value.get("experiment_identity").is_some() {
        return Err("recording-terminal-experiment-identity-without-manifest".to_owned());
    }
    let completed = value
        .get("completed")
        .and_then(Value::as_bool)
        .ok_or_else(|| "recording-terminal-completion-missing".to_owned())?;
    let lengths = value
        .get("durable_stream_lengths")
        .and_then(Value::as_object)
        .ok_or_else(|| "recording-terminal-lengths-missing".to_owned())?
        .iter()
        .map(|(name, value)| {
            value
                .as_u64()
                .map(|length| (name.clone(), length))
                .ok_or_else(|| "recording-terminal-length-invalid".to_owned())
        })
        .collect::<Result<BTreeMap<_, _>, _>>()?;
    let expected_streams = SESSION_STREAM_FILES
        .iter()
        .map(|name| (*name).to_owned())
        .collect::<BTreeSet<_>>();
    let declared_streams = lengths.keys().cloned().collect::<BTreeSet<_>>();
    if declared_streams != expected_streams {
        return Err("recording-terminal-stream-set-invalid".to_owned());
    }
    let actual_streams = fs::read_dir(directory)
        .map_err(|error| format!("recording-directory-read:{error}"))?
        .filter_map(Result::ok)
        .filter_map(|entry| {
            entry
                .file_type()
                .ok()
                .filter(|file_type| file_type.is_file())
                .and_then(|_| entry.file_name().to_str().map(str::to_owned))
        })
        .filter(|name| name.ends_with(".jsonl"))
        .collect::<BTreeSet<_>>();
    if actual_streams != expected_streams {
        return Err("recording-directory-stream-set-invalid".to_owned());
    }
    let mut lifecycle = LifecycleEvidence::default();
    for (name, expected_length) in &lengths {
        let actual_length = fs::metadata(directory.join(name))
            .map_err(|error| format!("recording-stream-metadata:{error}"))?
            .len();
        if actual_length < *expected_length {
            return Err("recording-stream-shorter-than-durable-prefix".to_owned());
        }
        let evidence = validate_stream_prefix(
            &directory.join(name),
            name,
            *expected_length,
            started_at,
            &manifest.condition,
            manifest.experiment_identity.is_some(),
        )?;
        if name == "events.jsonl" {
            lifecycle = evidence;
        }
    }
    if lifecycle.started != 1 {
        return Err("recording-lifecycle-start-invalid".to_owned());
    }
    if lifecycle.completed > 1 || completed != (lifecycle.completed == 1) {
        return Err("recording-lifecycle-completion-inconsistent".to_owned());
    }
    let loss_error = validate_loss(value)?;
    let error = if schema == SESSION_FINAL_SCHEMA {
        let saved = value
            .get("saved")
            .and_then(Value::as_bool)
            .ok_or_else(|| "recording-final-saved-missing".to_owned())?;
        let dropped_records = value
            .get("dropped_records")
            .and_then(Value::as_u64)
            .ok_or_else(|| "recording-final-dropped-records-missing".to_owned())?;
        if saved != (dropped_records == 0) {
            return Err("recording-final-saved-state-inconsistent".to_owned());
        }
        if dropped_records != loss_error.0 {
            return Err("recording-final-loss-count-mismatch".to_owned());
        }
        loss_error.1
    } else {
        loss_error.1
    };
    Ok((completed, lengths, error))
}

fn validate_loss(value: &Value) -> Result<(u64, Option<String>), String> {
    let loss = value
        .get("loss")
        .and_then(Value::as_object)
        .ok_or_else(|| "recording-loss-missing".to_owned())?;
    let dropped = loss
        .get("dropped_records")
        .and_then(Value::as_u64)
        .ok_or_else(|| "recording-loss-count-missing".to_owned())?;
    let streams = loss
        .get("streams")
        .and_then(Value::as_array)
        .ok_or_else(|| "recording-loss-streams-missing".to_owned())?;
    let mut observed_streams = BTreeSet::new();
    for stream in streams {
        let stream = stream
            .as_str()
            .ok_or_else(|| "recording-loss-stream-invalid".to_owned())?;
        if !SESSION_STREAM_FILES.contains(&stream) || !observed_streams.insert(stream) {
            return Err("recording-loss-stream-invalid".to_owned());
        }
    }
    let bounds = [
        "first_offer_sequence",
        "last_offer_sequence",
        "first_observed_monotonic_ns",
        "last_observed_monotonic_ns",
    ];
    let parsed = bounds
        .iter()
        .map(|name| {
            let value = loss
                .get(*name)
                .ok_or_else(|| "recording-loss-bound-missing".to_owned())?;
            if value.is_null() {
                Ok(None)
            } else {
                value
                    .as_u64()
                    .map(Some)
                    .ok_or_else(|| "recording-loss-bound-invalid".to_owned())
            }
        })
        .collect::<Result<Vec<_>, String>>()?;
    if dropped == 0 {
        if !observed_streams.is_empty() || parsed.iter().any(Option::is_some) {
            return Err("recording-loss-empty-state-inconsistent".to_owned());
        }
        return Ok((0, None));
    }
    if observed_streams.is_empty() || parsed.iter().any(Option::is_none) {
        return Err("recording-loss-bounds-incomplete".to_owned());
    }
    if parsed[0] > parsed[1] || parsed[2] > parsed[3] {
        return Err("recording-loss-bounds-reversed".to_owned());
    }
    Ok((
        dropped,
        Some(format!(
            "recording-records-dropped:{dropped}:streams={}:offers={}-{}:monotonic-ns={}-{}",
            observed_streams.into_iter().collect::<Vec<_>>().join(","),
            parsed[0].unwrap(),
            parsed[1].unwrap(),
            parsed[2].unwrap(),
            parsed[3].unwrap()
        )),
    ))
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct LifecycleEvidence {
    started: u64,
    completed: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RowLifecycle {
    None,
    Started,
    Completed,
}

fn validate_stream_prefix(
    path: &Path,
    stream_name: &str,
    expected_length: u64,
    started_at: &SessionTimestampKey,
    condition: &ConditionKey,
    strict_source_clocks: bool,
) -> Result<LifecycleEvidence, String> {
    if expected_length == 0 {
        return Ok(LifecycleEvidence::default());
    }
    let file = File::open(path).map_err(|error| format!("recording-stream-open:{error}"))?;
    let mut reader = BufReader::new(file.take(expected_length));
    let mut consumed = 0_u64;
    let mut line = Vec::new();
    let mut lifecycle = LifecycleEvidence::default();
    while consumed < expected_length {
        line.clear();
        let read = reader
            .read_until(b'\n', &mut line)
            .map_err(|error| format!("recording-stream-read:{error}"))?;
        if read == 0 {
            return Err("recording-stream-prefix-short".to_owned());
        }
        consumed = consumed.saturating_add(read as u64);
        if line.last() != Some(&b'\n') {
            return Err("recording-stream-prefix-mid-row".to_owned());
        }
        let row: Value = serde_json::from_slice(&line[..line.len() - 1])
            .map_err(|_| "recording-stream-row-malformed".to_owned())?;
        match validate_row(
            &row,
            stream_name,
            started_at,
            condition,
            strict_source_clocks,
        )? {
            RowLifecycle::None => {}
            RowLifecycle::Started => lifecycle.started = lifecycle.started.saturating_add(1),
            RowLifecycle::Completed => lifecycle.completed = lifecycle.completed.saturating_add(1),
        }
    }
    if consumed != expected_length {
        return Err("recording-stream-prefix-length-mismatch".to_owned());
    }
    Ok(lifecycle)
}

fn validate_row(
    row: &Value,
    stream_name: &str,
    started_at: &SessionTimestampKey,
    condition: &ConditionKey,
    strict_source_clocks: bool,
) -> Result<RowLifecycle, String> {
    let object = row
        .as_object()
        .ok_or_else(|| "recording-stream-row-not-object".to_owned())?;
    if object.values().any(Value::is_null) {
        return Err("recording-stream-row-null-invalid".to_owned());
    }
    if object.get("schema").and_then(Value::as_str) != Some(SESSION_ROW_SCHEMA) {
        return Err("recording-stream-row-schema-invalid".to_owned());
    }
    if object.get("session_started_at_utc").and_then(Value::as_str) != Some(started_at.as_str()) {
        return Err("recording-stream-row-session-mismatch".to_owned());
    }
    if object.get("condition").and_then(Value::as_str) != Some(condition.as_str()) {
        return Err("recording-stream-row-condition-mismatch".to_owned());
    }
    for field in ["record_sequence", "observed_monotonic_ns"] {
        if object.get(field).and_then(Value::as_u64).is_none() {
            return Err(format!("recording-stream-row-field-invalid:{field}"));
        }
    }
    if object
        .get("observed_utc_ns")
        .is_some_and(|value| value.as_u64().is_none())
    {
        return Err("recording-stream-row-field-invalid:observed_utc_ns".to_owned());
    }
    let observed_kind = object.get("kind").and_then(Value::as_str);
    let expected_kind = match stream_name {
        "events.jsonl" => "event",
        "polar-acc.jsonl" => "polar-acc",
        "polar-ecg.jsonl" => "polar-ecg",
        "polar-hr-rr.jsonl" => "polar-hr-rr",
        "breath.jsonl" => "breath",
        "radius.jsonl" => match observed_kind {
            Some("effective-radius-snapshot") => "effective-radius-snapshot",
            _ => return Err("recording-stream-row-kind-mismatch".to_owned()),
        },
        _ => return Err("recording-stream-row-stream-invalid".to_owned()),
    };
    if observed_kind != Some(expected_kind) {
        return Err("recording-stream-row-kind-mismatch".to_owned());
    }
    let lifecycle = match expected_kind {
        "event" => {
            let event = object
                .get("event")
                .and_then(Value::as_str)
                .and_then(SessionEventKind::parse)
                .ok_or_else(|| "recording-stream-row-field-invalid:event".to_owned())?;
            match event {
                SessionEventKind::Started => RowLifecycle::Started,
                SessionEventKind::CompletionReached => RowLifecycle::Completed,
                _ => RowLifecycle::None,
            }
        }
        "polar-acc" => {
            for field in [
                "frame_sequence",
                "source_sequence",
                "sample_index",
                "source_time_ns",
            ] {
                require_u64(object, field)?;
            }
            require_source_clock(object, "source_clock")?;
            if strict_source_clocks {
                require_source_clock(object, "received_source_clock")?;
                require_u64(object, "received_source_time_ns")?;
            }
            let xyz = object
                .get("xyz_mg")
                .and_then(Value::as_array)
                .filter(|values| values.len() == 3)
                .ok_or_else(|| "recording-stream-row-field-invalid:xyz_mg".to_owned())?;
            if xyz.iter().any(|value| value.as_f64().is_none()) {
                return Err("recording-stream-row-field-invalid:xyz_mg".to_owned());
            }
            RowLifecycle::None
        }
        "polar-ecg" => {
            for field in [
                "frame_sequence",
                "source_sequence",
                "sample_index",
                "source_time_ns",
            ] {
                require_u64(object, field)?;
            }
            require_source_clock(object, "source_clock")?;
            if strict_source_clocks {
                require_source_clock(object, "received_source_clock")?;
                require_u64(object, "received_source_time_ns")?;
            }
            if object.get("microvolts").and_then(Value::as_i64).is_none() {
                return Err("recording-stream-row-field-invalid:microvolts".to_owned());
            }
            RowLifecycle::None
        }
        "polar-hr-rr" => {
            require_u64(object, "source_sequence")?;
            if strict_source_clocks {
                require_source_clock(object, "source_clock")?;
                require_u64(object, "source_time_ns")?;
            }
            require_u64(object, "bpm")?;
            require_optional_number(object, "rr_interval_ms")?;
            RowLifecycle::None
        }
        "breath" => {
            for field in ["source_sequence", "sampled_time_ns", "settings_revision"] {
                require_u64(object, field)?;
            }
            require_source_clock(object, "sampled_clock")?;
            if strict_source_clocks {
                require_source_clock(object, "observed_source_clock")?;
                require_u64(object, "observed_source_time_ns")?;
            }
            require_enum(object, "phase", &["inhale", "exhale", "hold", "unknown"])?;
            require_optional_number(object, "volume01")?;
            require_number(object, "quality01")?;
            RowLifecycle::None
        }
        "effective-radius-snapshot" => {
            if object.get("metric").and_then(Value::as_str) != Some("effective-radius-snapshot") {
                return Err("recording-stream-row-field-invalid:metric".to_owned());
            }
            if object.get("metric_status").and_then(Value::as_str)
                != Some("actual-runtime-radius-with-configured-deformation-envelopes")
            {
                return Err("recording-stream-row-field-invalid:metric_status".to_owned());
            }
            for field in ["source_frame", "settings_revision"] {
                require_u64(object, field)?;
            }
            if object
                .get("render_session_generation")
                .and_then(Value::as_u64)
                .is_none_or(|value| value == 0)
            {
                return Err(
                    "recording-stream-row-field-invalid:render_session_generation".to_owned(),
                );
            }
            let configured_min = require_number_value(object, "configured_radius_min_m")?;
            let configured_max = require_number_value(object, "configured_radius_max_m")?;
            if configured_min <= 0.0 || configured_max < configured_min {
                return Err(
                    "recording-stream-row-field-invalid:configured-radius-limits".to_owned(),
                );
            }
            for field in ["radius_progress01", "deformation_progress01"] {
                let progress = require_number_value(object, field)?;
                if !(0.0..=1.0).contains(&progress) {
                    return Err(format!("recording-stream-row-field-invalid:{field}"));
                }
            }
            let resulting_radius = require_number_value(object, "resulting_radius_m")?;
            if resulting_radius <= 0.0 {
                return Err("recording-stream-row-field-invalid:resulting_radius_m".to_owned());
            }
            require_enum(object, "radius_progress_source", &["runtime-driver-slot-0"])?;
            require_enum(
                object,
                "deformation_progress_source",
                &["runtime-driver-slot-0"],
            )?;
            require_token(object, "resulting_radius_parameter_source")?;
            require_optional_deformation_envelope(object, "oblateness")?;
            require_optional_deformation_envelope(object, "axis_profile")?;
            forbid_fields(
                object,
                &[
                    "status",
                    "driver_value01",
                    "base_sphere_radius_m",
                    "minimum_m",
                    "mean_m",
                    "maximum_m",
                ],
            )?;
            RowLifecycle::None
        }
        _ => unreachable!(),
    };
    Ok(lifecycle)
}

fn require_token(object: &serde_json::Map<String, Value>, field: &str) -> Result<(), String> {
    match object.get(field).and_then(Value::as_str) {
        Some(value)
            if !value.is_empty()
                && value.len() <= 128
                && value.bytes().all(|byte| {
                    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-')
                }) =>
        {
            Ok(())
        }
        _ => Err(format!("recording-stream-row-field-invalid:{field}")),
    }
}

fn require_optional_deformation_envelope(
    object: &serde_json::Map<String, Value>,
    field: &str,
) -> Result<(), String> {
    let Some(value) = object.get(field) else {
        return Ok(());
    };
    let envelope = value
        .as_object()
        .filter(|value| value.len() == 2)
        .ok_or_else(|| format!("recording-stream-row-field-invalid:{field}"))?;
    for endpoint in ["at_radius_min", "at_radius_max"] {
        require_number(envelope, endpoint)
            .map_err(|_| format!("recording-stream-row-field-invalid:{field}.{endpoint}"))?;
    }
    Ok(())
}

fn require_u64(object: &serde_json::Map<String, Value>, field: &str) -> Result<(), String> {
    if object.get(field).and_then(Value::as_u64).is_some() {
        Ok(())
    } else {
        Err(format!("recording-stream-row-field-invalid:{field}"))
    }
}

fn require_number(object: &serde_json::Map<String, Value>, field: &str) -> Result<(), String> {
    require_number_value(object, field).map(|_| ())
}

fn require_number_value(
    object: &serde_json::Map<String, Value>,
    field: &str,
) -> Result<f64, String> {
    object
        .get(field)
        .and_then(Value::as_f64)
        .filter(|value| value.is_finite())
        .ok_or_else(|| format!("recording-stream-row-field-invalid:{field}"))
}

fn forbid_fields(object: &serde_json::Map<String, Value>, fields: &[&str]) -> Result<(), String> {
    if let Some(field) = fields.iter().find(|field| object.contains_key(**field)) {
        Err(format!("recording-stream-row-cross-shape:{field}"))
    } else {
        Ok(())
    }
}

fn require_optional_number(
    object: &serde_json::Map<String, Value>,
    field: &str,
) -> Result<(), String> {
    match object.get(field) {
        None => Ok(()),
        Some(value) if value.as_f64().is_some() => Ok(()),
        Some(_) => Err(format!("recording-stream-row-field-invalid:{field}")),
    }
}

fn require_enum(
    object: &serde_json::Map<String, Value>,
    field: &str,
    allowed: &[&str],
) -> Result<(), String> {
    match object.get(field).and_then(Value::as_str) {
        Some(value) if allowed.contains(&value) => Ok(()),
        _ => Err(format!("recording-stream-row-field-invalid:{field}")),
    }
}

fn require_source_clock(
    object: &serde_json::Map<String, Value>,
    field: &str,
) -> Result<(), String> {
    require_enum(
        object,
        field,
        &[
            "android-elapsed-realtime",
            "java-nano-time",
            "polar-sensor",
            "openxr-time",
            "utc-unix",
        ],
    )
}

fn read_json(path: &Path) -> Result<Value, String> {
    let bytes = fs::read(path).map_err(|error| format!("recording-json-read:{error}"))?;
    serde_json::from_slice(&bytes).map_err(|error| format!("recording-json-parse:{error}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs::OpenOptions,
        io::Write,
        sync::atomic::{AtomicU64, Ordering},
        time::{SystemTime, UNIX_EPOCH},
    };

    use serde_json::json;

    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn temp_root(label: &str) -> PathBuf {
        std::env::temp_dir()
            .join(format!(
                "rusty-quest-session-recovery-{label}-{}-{}",
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ))
            .join("viscereality-recordings")
    }

    fn write_json(path: &Path, value: Value) {
        fs::write(path, format!("{}\n", value)).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn app_owned_link_checks_allow_platform_alias_but_reject_recording_root_link() {
        use std::os::unix::fs::symlink;

        let base = temp_root("platform-alias").parent().unwrap().to_owned();
        let backing = base.join("backing");
        let alias = base.join("platform-alias");
        let files = backing.join("package/files");
        let recording_root = files.join("viscereality-recordings");
        fs::create_dir_all(&recording_root).unwrap();
        symlink(&backing, &alias).unwrap();

        let aliased_root = alias.join("package/files/viscereality-recordings");
        assert_eq!(validate_recording_root_no_links(&aliased_root), Ok(()));

        fs::remove_dir(&recording_root).unwrap();
        let external = backing.join("external-recordings");
        fs::create_dir(&external).unwrap();
        symlink(&external, &recording_root).unwrap();
        assert_eq!(
            validate_recording_root_no_links(&aliased_root),
            Err("recording-root-symlink-rejected".to_owned())
        );
        fs::remove_dir_all(&base).unwrap();
    }

    #[test]
    fn recovery_accepts_every_canonical_session_event_and_rejects_unknown_events() {
        let started_at = SessionTimestampKey::parse("utc-ns-01725000000000000000").unwrap();
        let condition = ConditionKey::parse("condition-a").unwrap();
        for (sequence, event) in SessionEventKind::ALL.into_iter().enumerate() {
            let row = json!({
                "schema": SESSION_ROW_SCHEMA,
                "record_sequence": sequence as u64,
                "kind": "event",
                "session_started_at_utc": started_at.as_str(),
                "condition": condition.as_str(),
                "observed_monotonic_ns": sequence as u64 + 1,
                "observed_utc_ns": sequence as u64 + 2,
                "event": event.as_str(),
            });
            let expected = match event {
                SessionEventKind::Started => RowLifecycle::Started,
                SessionEventKind::CompletionReached => RowLifecycle::Completed,
                _ => RowLifecycle::None,
            };
            assert_eq!(
                validate_row(&row, "events.jsonl", &started_at, &condition, true),
                Ok(expected),
                "{}",
                event.as_str()
            );
        }

        let unknown = json!({
            "schema": SESSION_ROW_SCHEMA,
            "record_sequence": 99,
            "kind": "event",
            "session_started_at_utc": started_at.as_str(),
            "condition": condition.as_str(),
            "observed_monotonic_ns": 100,
            "observed_utc_ns": 101,
            "event": "future-event-without-contract",
        });
        assert_eq!(
            validate_row(&unknown, "events.jsonl", &started_at, &condition, true),
            Err("recording-stream-row-field-invalid:event".to_owned())
        );
    }

    fn make_session(root: &Path, completed: bool, finalized: bool) -> PathBuf {
        fs::create_dir_all(root).unwrap();
        let key = SessionTimestampKey::parse("utc-ns-01725000000000000000").unwrap();
        let directory = root.join(key.as_str());
        fs::create_dir(&directory).unwrap();
        write_json(
            &directory.join("manifest.json"),
            json!({
                "schema":SESSION_MANIFEST_SCHEMA,
                "started_at_utc":key.as_str(),
                "condition":"condition-a"
            }),
        );
        let event_row = |sequence: u64, event: &str| {
            format!(
                "{}\n",
                json!({
                "schema": SESSION_ROW_SCHEMA,
                "record_sequence": sequence,
                "kind": "event",
                "session_started_at_utc": key.as_str(),
                "condition": "condition-a",
                "observed_monotonic_ns": sequence + 1,
                "observed_utc_ns": sequence + 2,
                "event": event
                })
            )
        };
        let mut valid_prefix = event_row(0, "started");
        if completed {
            valid_prefix.push_str(&event_row(1, "completion-reached"));
        }
        fs::write(
            directory.join("events.jsonl"),
            format!("{valid_prefix}trailing\n"),
        )
        .unwrap();
        for name in SESSION_STREAM_FILES {
            if name != "events.jsonl" {
                fs::write(directory.join(name), []).unwrap();
            }
        }
        let terminal = json!({
            "schema": if finalized { SESSION_FINAL_SCHEMA } else { SESSION_CHECKPOINT_SCHEMA },
            "started_at_utc": key.as_str(),
            "condition": "condition-a",
            "completed": completed,
            "durable_stream_lengths": {
                "events.jsonl": valid_prefix.len(),
                "polar-acc.jsonl": 0,
                "polar-ecg.jsonl": 0,
                "polar-hr-rr.jsonl": 0,
                "breath.jsonl": 0,
                "radius.jsonl": 0
            },
            "loss": {
                "dropped_records": 0,
                "streams": [],
                "first_offer_sequence": null,
                "last_offer_sequence": null,
                "first_observed_monotonic_ns": null,
                "last_observed_monotonic_ns": null
            },
            "saved": true,
            "dropped_records": 0
        });
        if finalized {
            let mut checkpoint = terminal.clone();
            checkpoint["schema"] = json!(SESSION_CHECKPOINT_SCHEMA);
            write_json(
                &directory.join("checkpoint-00000000000000000001.json"),
                checkpoint,
            );
            write_json(&directory.join("final.json"), terminal);
        } else {
            write_json(
                &directory.join("checkpoint-00000000000000000001.json"),
                terminal,
            );
        }
        directory
    }

    fn effective_radius_snapshot_row() -> Value {
        json!({
            "schema": SESSION_ROW_SCHEMA,
            "record_sequence": 1,
            "kind": "effective-radius-snapshot",
            "session_started_at_utc": "utc-ns-01725000000000000000",
            "condition": "condition-a",
            "observed_monotonic_ns": 1_500,
            "observed_utc_ns": 1_725_000_000_000_000_500_u64,
            "metric": "effective-radius-snapshot",
            "metric_status": "actual-runtime-radius-with-configured-deformation-envelopes",
            "source_frame": 7,
            "configured_radius_min_m": 0.8,
            "configured_radius_max_m": 1.6,
            "radius_progress01": 0.5,
            "radius_progress_source": "runtime-driver-slot-0",
            "resulting_radius_m": 1.2,
            "resulting_radius_parameter_source": "app-effective-world-anchor",
            "deformation_progress01": 0.5,
            "deformation_progress_source": "runtime-driver-slot-0",
            "oblateness": {"at_radius_min": 0.2, "at_radius_max": 0.4},
            "axis_profile": {"at_radius_min": 1.8, "at_radius_max": 0.9},
            "settings_revision": 3,
            "render_session_generation": 2
        })
    }

    fn install_radius_prefix(directory: &Path, row: &Value) {
        let line = format!("{row}\n");
        fs::write(directory.join("radius.jsonl"), &line).unwrap();
        let checkpoint_path = directory.join("checkpoint-00000000000000000001.json");
        let mut checkpoint = read_json(&checkpoint_path).unwrap();
        checkpoint["durable_stream_lengths"]["radius.jsonl"] = json!(line.len());
        write_json(&checkpoint_path, checkpoint);
    }

    #[test]
    fn interrupted_session_uses_checkpoint_without_destroying_tail() {
        let root = temp_root("prefix");
        let directory = make_session(&root, true, false);
        let before = fs::read(directory.join("events.jsonl")).unwrap();
        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.completed, 1);
        assert_eq!(report.counts.stopped_early, 0);
        assert_eq!(
            report.sessions[0].disposition,
            RecoveryDisposition::InterruptedCompleted
        );
        assert!(report.sessions[0].durable_stream_lengths["events.jsonl"] > 6);
        assert_eq!(fs::read(directory.join("events.jsonl")).unwrap(), before);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn final_receipts_produce_deterministic_completed_and_early_counts() {
        let root = temp_root("counts");
        make_session(&root, true, true);
        let first = root.join("utc-ns-01725000000000000000");
        let second = root.join("utc-ns-01725000000000000001");
        fs::rename(&first, &second).unwrap();
        let mut manifest: Value = read_json(&second.join("manifest.json")).unwrap();
        manifest["started_at_utc"] = json!("utc-ns-01725000000000000001");
        write_json(&second.join("manifest.json"), manifest);
        rewrite_event_and_checkpoint(&second, |row| {
            row["session_started_at_utc"] = json!("utc-ns-01725000000000000001");
        });
        let checkpoint_path = second.join("checkpoint-00000000000000000001.json");
        let mut checkpoint: Value = read_json(&checkpoint_path).unwrap();
        checkpoint["started_at_utc"] = json!("utc-ns-01725000000000000001");
        checkpoint["completed"] = json!(false);
        let event_length = checkpoint["durable_stream_lengths"]["events.jsonl"].clone();
        write_json(&checkpoint_path, checkpoint);
        let mut final_value: Value = read_json(&second.join("final.json")).unwrap();
        final_value["started_at_utc"] = json!("utc-ns-01725000000000000001");
        final_value["completed"] = json!(false);
        final_value["durable_stream_lengths"]["events.jsonl"] = event_length;
        write_json(&second.join("final.json"), final_value);
        make_session(&root, true, true);

        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.completed, 1);
        assert_eq!(report.counts.stopped_early, 1);
        assert_eq!(report.counts.errors, 0);
        assert_eq!(report.condition_counts.condition_a.stopped_early, 1);
        assert_eq!(report.condition_counts.condition_b.stopped_early, 0);
        assert_eq!(report.sessions.len(), 2);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn corrupt_final_falls_back_to_completed_checkpoint_and_retains_error() {
        let root = temp_root("corrupt");
        let directory = make_session(&root, true, true);
        let mut file = OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(directory.join("final.json"))
            .unwrap();
        file.write_all(b"not-json").unwrap();
        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.completed, 1);
        assert_eq!(report.counts.stopped_early, 0);
        assert_eq!(report.counts.errors, 1);
        assert!(report.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .starts_with("recording-final-invalid:"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn finalized_sample_loss_is_counted_as_an_error_without_changing_completion() {
        let root = temp_root("dropped");
        let directory = make_session(&root, true, true);
        let mut final_value: Value = read_json(&directory.join("final.json")).unwrap();
        final_value["saved"] = json!(false);
        final_value["dropped_records"] = json!(2);
        final_value["loss"] = json!({
            "dropped_records": 2,
            "streams": ["breath.jsonl"],
            "first_offer_sequence": 7,
            "last_offer_sequence": 9,
            "first_observed_monotonic_ns": 100,
            "last_observed_monotonic_ns": 200
        });
        write_json(&directory.join("final.json"), final_value);

        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.completed, 1);
        assert_eq!(report.counts.stopped_early, 0);
        assert_eq!(report.counts.errors, 1);
        assert_eq!(
            report.sessions[0].error.as_deref(),
            Some(
                "recording-records-dropped:2:streams=breath.jsonl:offers=7-9:monotonic-ns=100-200"
            )
        );
        fs::remove_dir_all(root).unwrap();
    }

    fn rewrite_event_and_checkpoint(directory: &Path, mutate: impl FnOnce(&mut Value)) {
        let text = fs::read_to_string(directory.join("events.jsonl")).unwrap();
        let mut row: Value = serde_json::from_str(text.lines().next().unwrap()).unwrap();
        mutate(&mut row);
        let line = format!("{}\n", row);
        fs::write(directory.join("events.jsonl"), &line).unwrap();
        let checkpoint_path = directory.join("checkpoint-00000000000000000001.json");
        let mut checkpoint = read_json(&checkpoint_path).unwrap();
        checkpoint["durable_stream_lengths"]["events.jsonl"] = json!(line.len());
        write_json(&checkpoint_path, checkpoint);
    }

    #[test]
    fn recovery_rejects_mid_row_malformed_schema_and_session_damage() {
        for (label, damage, expected) in [
            ("schema", "schema", "recording-stream-row-schema-invalid"),
            (
                "session",
                "session",
                "recording-stream-row-session-mismatch",
            ),
            ("null", "null", "recording-stream-row-null-invalid"),
        ] {
            let root = temp_root(label);
            let directory = make_session(&root, false, false);
            rewrite_event_and_checkpoint(&directory, |row| {
                if damage == "schema" {
                    row["schema"] = json!("wrong");
                } else if damage == "session" {
                    row["session_started_at_utc"] = json!("utc-ns-00000000000000000000");
                } else {
                    row["observed_utc_ns"] = Value::Null;
                }
            });
            let report = recover_recordings(&root).unwrap();
            assert_eq!(report.counts.errors, 1);
            assert!(report.sessions[0]
                .error
                .as_deref()
                .unwrap()
                .contains(expected));
            fs::remove_dir_all(root).unwrap();
        }

        let root = temp_root("malformed");
        let directory = make_session(&root, false, false);
        fs::write(directory.join("events.jsonl"), b"{broken}\n").unwrap();
        let checkpoint_path = directory.join("checkpoint-00000000000000000001.json");
        let mut checkpoint = read_json(&checkpoint_path).unwrap();
        checkpoint["durable_stream_lengths"]["events.jsonl"] = json!(9);
        write_json(&checkpoint_path, checkpoint);
        let report = recover_recordings(&root).unwrap();
        assert!(report.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("recording-stream-row-malformed"));
        fs::remove_dir_all(root).unwrap();

        let root = temp_root("mid-row");
        let directory = make_session(&root, false, false);
        let checkpoint_path = directory.join("checkpoint-00000000000000000001.json");
        let mut checkpoint = read_json(&checkpoint_path).unwrap();
        let length = checkpoint["durable_stream_lengths"]["events.jsonl"]
            .as_u64()
            .unwrap();
        checkpoint["durable_stream_lengths"]["events.jsonl"] = json!(length - 1);
        write_json(&checkpoint_path, checkpoint);
        let report = recover_recordings(&root).unwrap();
        assert!(report.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("recording-stream-prefix-mid-row"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn recovery_requires_the_exact_stream_set() {
        let root = temp_root("stream-set");
        let directory = make_session(&root, false, false);
        fs::remove_file(directory.join("radius.jsonl")).unwrap();
        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.errors, 1);
        assert!(report.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("recording-directory-stream-set-invalid"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn interrupted_overflow_checkpoint_is_completed_but_error_counted() {
        let root = temp_root("overflow-checkpoint");
        let directory = make_session(&root, true, false);
        let checkpoint_path = directory.join("checkpoint-00000000000000000001.json");
        let mut checkpoint = read_json(&checkpoint_path).unwrap();
        checkpoint["loss"] = json!({
            "dropped_records": 1,
            "streams": ["polar-acc.jsonl"],
            "first_offer_sequence": 11,
            "last_offer_sequence": 11,
            "first_observed_monotonic_ns": 500,
            "last_observed_monotonic_ns": 500
        });
        write_json(&checkpoint_path, checkpoint);
        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.completed, 1);
        assert_eq!(report.counts.errors, 1);
        assert!(report.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("recording-records-dropped:1"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn completed_terminal_requires_started_and_completion_lifecycle_rows() {
        let root = temp_root("zero-prefix-completed");
        let directory = make_session(&root, true, false);
        fs::write(directory.join("events.jsonl"), []).unwrap();
        let checkpoint_path = directory.join("checkpoint-00000000000000000001.json");
        let mut checkpoint = read_json(&checkpoint_path).unwrap();
        checkpoint["durable_stream_lengths"]["events.jsonl"] = json!(0);
        write_json(&checkpoint_path, checkpoint);
        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.completed, 0);
        assert_eq!(report.counts.errors, 1);
        assert!(report.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("recording-lifecycle-start-invalid"));
        fs::remove_dir_all(root).unwrap();

        let root = temp_root("missing-completion-event");
        let directory = make_session(&root, true, false);
        let text = fs::read_to_string(directory.join("events.jsonl")).unwrap();
        let first_line_length = text.lines().next().unwrap().len() + 1;
        let checkpoint_path = directory.join("checkpoint-00000000000000000001.json");
        let mut checkpoint = read_json(&checkpoint_path).unwrap();
        checkpoint["durable_stream_lengths"]["events.jsonl"] = json!(first_line_length);
        write_json(&checkpoint_path, checkpoint);
        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.completed, 0);
        assert_eq!(report.counts.errors, 1);
        assert!(report.sessions[0]
            .error
            .as_deref()
            .unwrap()
            .contains("recording-lifecycle-completion-inconsistent"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn recovery_accepts_effective_radius_snapshot_without_claiming_geometric_extrema() {
        let root = temp_root("effective-radius-snapshot-valid");
        let directory = make_session(&root, false, false);
        install_radius_prefix(&directory, &effective_radius_snapshot_row());

        let report = recover_recordings(&root).unwrap();
        assert_eq!(report.counts.stopped_early, 1);
        assert_eq!(report.counts.errors, 0);
        assert_eq!(
            report.sessions[0].disposition,
            RecoveryDisposition::InterruptedEarly
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn recovered_condition_counts_use_validated_manifests_without_guessing() {
        let key_a = SessionTimestampKey::parse("utc-ns-01725000000000000000").unwrap();
        let key_b = SessionTimestampKey::parse("utc-ns-01725000000000000001").unwrap();
        let key_unknown = SessionTimestampKey::parse("utc-ns-01725000000000000002").unwrap();
        let completed = RecoveredSession {
            started_at: key_a,
            experiment_identity: None,
            disposition: RecoveryDisposition::FinalizedCompleted,
            durable_stream_lengths: BTreeMap::new(),
            error: None,
        };
        let early_error = RecoveredSession {
            started_at: key_b,
            experiment_identity: None,
            disposition: RecoveryDisposition::InterruptedEarly,
            durable_stream_lengths: BTreeMap::new(),
            error: Some("recording-records-dropped:1".to_owned()),
        };
        let unknown = RecoveredSession {
            started_at: key_unknown,
            experiment_identity: None,
            disposition: RecoveryDisposition::CorruptEarly,
            durable_stream_lengths: BTreeMap::new(),
            error: Some("recording-manifest-schema-invalid".to_owned()),
        };
        let mut counts = RecoveredConditionCounts::default();
        add_condition_count(
            &mut counts,
            &ConditionKey::parse("condition-a").unwrap(),
            &completed,
        );
        add_condition_count(
            &mut counts,
            &ConditionKey::parse("condition-b").unwrap(),
            &early_error,
        );
        assert_eq!(counts.condition_a.completed, 1);
        assert_eq!(counts.condition_a.stopped_early, 0);
        assert_eq!(counts.condition_a.errors, 0);
        assert_eq!(counts.condition_b.completed, 0);
        assert_eq!(counts.condition_b.stopped_early, 1);
        assert_eq!(counts.condition_b.errors, 1);
        // No manifest condition means no call to add_condition_count: the
        // aggregate recovery still owns this error without guessing a bucket.
        assert!(unknown.error.is_some());
    }

    #[test]
    fn recovery_rejects_effective_radius_snapshot_mislabelling_and_range_damage() {
        let mut damage = Vec::new();

        let mut wrong_metric = effective_radius_snapshot_row();
        wrong_metric["metric"] = json!("effective-surface-min-mean-max-m");
        damage.push(("wrong-metric", wrong_metric, "field-invalid:metric"));

        let mut wrong_status = effective_radius_snapshot_row();
        wrong_status["metric_status"] = json!("available");
        damage.push(("wrong-status", wrong_status, "field-invalid:metric_status"));

        let mut cross_shape = effective_radius_snapshot_row();
        cross_shape["minimum_m"] = json!(0.4);
        damage.push(("cross-shape", cross_shape, "cross-shape:minimum_m"));

        let mut zero_result = effective_radius_snapshot_row();
        zero_result["resulting_radius_m"] = json!(0.0);
        damage.push((
            "zero-result",
            zero_result,
            "field-invalid:resulting_radius_m",
        ));

        let mut progress_high = effective_radius_snapshot_row();
        progress_high["deformation_progress01"] = json!(1.01);
        damage.push((
            "progress-high",
            progress_high,
            "field-invalid:deformation_progress01",
        ));

        let mut missing_generation = effective_radius_snapshot_row();
        missing_generation
            .as_object_mut()
            .unwrap()
            .remove("render_session_generation");
        damage.push((
            "missing-generation",
            missing_generation,
            "field-invalid:render_session_generation",
        ));

        let mut unnamed_envelope = effective_radius_snapshot_row();
        unnamed_envelope["oblateness"] = json!({"minimum": 0.2, "maximum": 0.4});
        damage.push((
            "unnamed-envelope",
            unnamed_envelope,
            "field-invalid:oblateness.at_radius_min",
        ));

        for (label, row, expected) in damage {
            let root = temp_root(label);
            let directory = make_session(&root, false, false);
            install_radius_prefix(&directory, &row);
            let report = recover_recordings(&root).unwrap();
            assert_eq!(report.counts.errors, 1, "{label}");
            assert!(
                report.sessions[0]
                    .error
                    .as_deref()
                    .is_some_and(|error| error.contains(expected)),
                "{label}: {:?}",
                report.sessions[0].error
            );
            fs::remove_dir_all(root).unwrap();
        }
    }
}
