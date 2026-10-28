//! Native writer lease and per-installation capability. No lease operation is a
//! provider teardown receipt. The native inode is separate from Java fcntl state.
use rusty_quest_broker_authority::packaged_json_sha256 as digest;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

const APP_SCHEMA: &str = "rusty.quest.embedded_duplex.app_process_fence.v1";
const APP_RUN_SCHEMA: &str = "rusty.quest.embedded_duplex.app_process_fence.v2";
const NATIVE_SCHEMA: &str = "rusty.quest.embedded_duplex.native_process_fence.v1";
const NATIVE_OWNER_SCHEMA: &str = "rusty.quest.embedded_duplex.native_process_fence.v2";
const LOCK_NAME: &str = "native-process-fence.v1.lock";
const WITNESS_NAME: &str = "native-process-fence.v1.initialized";

/// Internal live app-channel reader, never an operator-supplied assertion.
pub(super) trait AppFenceSource: Send + Sync {
    fn record(&self) -> Result<String, String>;
    fn inspection_record(&self) -> Result<String, String> {
        self.record()
    }
    fn operational_run_handoff(&self) -> Result<Option<String>, String> {
        Ok(None)
    }
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct OperationalRunHandoff {
    #[serde(rename = "$schema")]
    schema: String,
    operational_run_id: String,
    app_record_sha256: String,
    prior_native_generation: u64,
    whole_original_id: String,
    request_sha256: String,
    outcome_sha256: String,
    physical_retirement_sha256: String,
    physical_retirement: serde_json::Value,
}

/// Same integral, ASCII-keyed canonical JSON used by the app's joined custody.
fn canonical_handoff_json(value: &serde_json::Value) -> Result<String, String> {
    use serde_json::Value;
    Ok(match value {
        Value::Null => "null".into(),
        Value::Bool(v) => v.to_string(),
        Value::String(v) => serde_json::to_string(v).map_err(|_| "handoff string encoding")?,
        Value::Number(v) if v.as_i64().is_some() => v.to_string(),
        Value::Array(items) => format!(
            "[{}]",
            items
                .iter()
                .map(canonical_handoff_json)
                .collect::<Result<Vec<_>, _>>()?
                .join(",")
        ),
        Value::Object(fields) => {
            let mut keys = fields.keys().collect::<Vec<_>>();
            keys.sort();
            let mut values = Vec::with_capacity(keys.len());
            for key in keys {
                if key.is_empty() || !key.bytes().all(|b| (0x20..=0x7e).contains(&b)) {
                    return Err("handoff canonical key invalid".into());
                }
                values.push(format!(
                    "{}:{}",
                    serde_json::to_string(key).map_err(|_| "handoff key encoding")?,
                    canonical_handoff_json(&fields[key])?
                ));
            }
            format!("{{{}}}", values.join(","))
        }
        _ => return Err("handoff canonical value is not an integral app value".into()),
    })
}

/// Validation is read-only, never a serialized capability. Only the fixed app
/// callback may supply the source-owned Original/join projection to its caller.
pub(super) fn validate_operational_run_handoff(
    raw: &str,
    binding: &AppBinding,
    pending: bool,
    counter: u64,
) -> Result<String, String> {
    if raw.is_empty() || raw.len() > 256 * 1024 {
        return Err("operational handoff bounds".into());
    }
    let h: OperationalRunHandoff =
        serde_json::from_str(raw).map_err(|_| "operational handoff closed shape invalid")?;
    let hex = |v: &str, n: usize| {
        v.len() == n
            && v.bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    };
    if h.schema != "rusty.quest.embedded_duplex.operational_run_handoff.v1"
        || !hex(&h.operational_run_id, 32)
        || !hex(&h.whole_original_id, 32)
        || ![
            &h.app_record_sha256,
            &h.request_sha256,
            &h.outcome_sha256,
            &h.physical_retirement_sha256,
        ]
        .iter()
        .all(|s| hex(s, 64))
        || h.app_record_sha256 != binding.record_sha256
        || (pending && binding.operational_run_id.as_deref() != Some(h.operational_run_id.as_str()))
        || counter == 0
        || counter > i64::MAX as u64
        || h.prior_native_generation != counter
    {
        return Err("operational handoff run/channel/counter subject differs".into());
    }
    let p = &h.physical_retirement;
    let schema = p["$schema"].as_str().ok_or("handoff Whole schema absent")?;
    if matches!(
        schema,
        "rusty.quest.embedded_duplex.cold_pending_local_retirement.v1"
            | "rusty.quest.embedded_duplex.cold_pending_local_retirement.v2"
    ) {
        value_fields(
            p,
            &[
                "$schema",
                "recovery_original_id",
                "run_id",
                "prior_run_id",
                "prior_original_id",
                "app_process_generation",
                "app_record_sha256",
                "app_clear_record_sha256",
                "prior_native_generation",
                "old_process_identity",
                "current_process_identity",
                "old_process_retirement",
                "owner_link_sha256",
                "native_retirement",
                "physical_retirement",
                "transaction_outcome",
                "remote_outcome_resolved",
            ],
        )?;
        let app = p["app_process_generation"]
            .as_u64()
            .filter(|v| *v > 0)
            .ok_or("cold handoff app generation")?;
        if p["recovery_original_id"] != h.whole_original_id
            || p["prior_native_generation"].as_u64() != Some(counter)
            || app > binding.generation
            || pending && app == binding.generation
            || p["transaction_outcome"] != "unchanged"
            || p["remote_outcome_resolved"] != false
        {
            return Err("cold handoff subject differs".into());
        }
        let n = &p["native_retirement"];
        value_fields(
            n,
            &[
                "$schema",
                "recovery_original_id",
                "run_id",
                "app_process_generation",
                "app_record_sha256",
                "prior_native_generation",
                "owner_link_sha256",
                "disposition",
                "native_counter_preserved",
                "transaction_outcome",
                "remote_outcome_resolved",
            ],
        )?;
        for k in [
            "recovery_original_id",
            "run_id",
            "app_process_generation",
            "app_record_sha256",
            "prior_native_generation",
            "owner_link_sha256",
            "transaction_outcome",
            "remote_outcome_resolved",
        ] {
            if n[k] != p[k] {
                return Err("cold handoff Native join differs".into());
            }
        }
        let native_schema = if schema.ends_with(".v2") {
            "rusty.quest.embedded_duplex.cold_pending_native_retirement.v2"
        } else {
            "rusty.quest.embedded_duplex.cold_pending_native_retirement.v1"
        };
        if n["$schema"] != native_schema
            || n["native_counter_preserved"] != true
            || n["disposition"] != "prior_process_local_retired"
        {
            return Err("cold handoff Native disposition".into());
        }
        let physical = &p["physical_retirement"];
        let names = [
            "java_own_capture_cleanup",
            "java_peer_resources_cleanup",
            "java_renderer_cleanup",
            "java_control_cleanup",
            "java_callback_cleanup",
            "command_lane_barrier",
            "whole_app_physical_cleanup",
            "app_process_fence_cleanup",
        ];
        value_fields(physical, &names)?;
        if names.iter().any(|k| physical[*k] != "terminal") {
            return Err("cold handoff local owner pending".into());
        }
        if digest(&canonical_handoff_json(p)?) != h.physical_retirement_sha256 {
            return Err("cold handoff digest differs".into());
        }
        return Ok(digest(raw));
    }
    if matches!(
        schema,
        "rusty.quest.embedded_duplex.no_media_whole_retirement.v1"
            | "rusty.quest.embedded_duplex.no_media_whole_retirement.v2"
            | "rusty.quest.embedded_duplex.no_media_whole_retirement.v3"
    ) {
        validate_no_media_handoff(p, binding, pending, counter)?;
        if digest(&canonical_handoff_json(p)?) != h.physical_retirement_sha256 {
            return Err("no-media handoff digest differs".into());
        }
        return Ok(digest(raw));
    }
    let local = match schema {
        "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1" => true,
        "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v3"
        | "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v4" => {
            if p["ongoing_media_policy"]
                != serde_json::json!({"$schema":"rusty.manifold.peer.ongoing_media_policy.v2"})
            {
                return Err("handoff Whole policy differs".into());
            }
            true
        }
        "rusty.quest.embedded_duplex.remote_owner_cleanup.v1" => false,
        _ => return Err("handoff Whole schema unsupported".into()),
    };
    let app = p["app_process_generation"]
        .as_u64()
        .filter(|v| *v > 0)
        .ok_or("handoff Whole app generation invalid")?;
    if p["action"] != "whole_app_close"
        || p["peer_physical_cleanup"]
            != if local {
                "terminal"
            } else {
                "not_locally_started"
            }
        || p["native_executor_generation"].as_u64() != Some(counter)
        || app > binding.generation
        || (pending && app == binding.generation)
        || !p["config_sha256"].as_str().is_some_and(|s| hex(s, 64))
    {
        return Err("handoff Whole actor/config subject differs".into());
    }
    for key in [
        "native_host_physical_cleanup",
        "java_own_capture_cleanup",
        "java_renderer_cleanup",
        "java_control_cleanup",
        "app_process_fence_cleanup",
        "whole_app_physical_cleanup",
        "status",
    ] {
        if p[key] != "terminal" {
            return Err("handoff Whole physical owner remains pending".into());
        }
    }
    if digest(&canonical_handoff_json(p)?) != h.physical_retirement_sha256 {
        return Err("handoff Whole joined digest differs".into());
    }
    // Closed scalar fields cannot contain newlines; this is an unambiguous
    // in-memory retry identity bound to the actual resulting Native capability.
    Ok(digest(&format!(
        "{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n",
        h.schema,
        h.operational_run_id,
        h.app_record_sha256,
        counter,
        h.whole_original_id,
        h.request_sha256,
        h.outcome_sha256,
        h.physical_retirement_sha256
    )))
}

/// Closed file-only DTO validation. A source-owned Java join supplies these
/// facts; this parser cannot turn a caller assertion into Native authority.
fn validate_no_media_handoff(
    p: &serde_json::Value,
    binding: &AppBinding,
    pending: bool,
    counter: u64,
) -> Result<(), String> {
    let schema = p["$schema"].as_str().ok_or("no-media Whole schema")?;
    let finite = schema == "rusty.quest.embedded_duplex.no_media_whole_retirement.v1";
    let configured = p.get("config_sha256").is_some();
    let mut fields = vec![
        "$schema",
        "action",
        "app_process_generation",
        "app_record_sha256",
        "process_epoch",
        "native_retirement",
        "arm_observation",
        "own_capture",
        "native_executor_generation",
        "java_own_capture_cleanup",
        "java_peer_resources_cleanup",
        "java_renderer_cleanup",
        "java_control_cleanup",
        "java_callback_cleanup",
        "command_lane_barrier",
        "whole_app_physical_cleanup",
        "app_process_fence_cleanup",
    ];
    if !finite || configured {
        fields.push("config_sha256");
    }
    value_fields(p, &fields)?;
    let hex = |v: &serde_json::Value| {
        v.as_str().is_some_and(|s| {
            s.len() == 64
                && s.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
    };
    let app = p["app_process_generation"]
        .as_u64()
        .filter(|v| *v > 0)
        .ok_or("no-media handoff app generation")?;
    if p["action"] != "whole_app_close"
        || app > binding.generation
        || pending && app == binding.generation
        || p["native_executor_generation"].as_u64() != Some(counter)
        || !hex(&p["app_record_sha256"])
        || configured && !hex(&p["config_sha256"])
        || !p["process_epoch"]
            .as_str()
            .is_some_and(|s| s.starts_with(&format!("app-process.{app}.")))
    {
        return Err("no-media handoff actor differs".into());
    }
    for key in [
        "java_own_capture_cleanup",
        "java_peer_resources_cleanup",
        "java_renderer_cleanup",
        "java_control_cleanup",
        "java_callback_cleanup",
        "command_lane_barrier",
        "whole_app_physical_cleanup",
        "app_process_fence_cleanup",
    ] {
        if p[key] != "terminal" {
            return Err("no-media handoff local owner pending".into());
        }
    }
    let n = &p["native_retirement"];
    let mut native_fields = vec![
        "$schema",
        "native_runtime_binding",
        "disposition",
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
    ];
    if configured {
        native_fields.push("config_sha256");
    }
    for key in [
        "app_process_generation",
        "app_record_sha256",
        "native_executor_generation",
    ] {
        if p[key] != n[key] {
            return Err("no-media handoff Native actor differs".into());
        }
    }
    if configured && p["config_sha256"] != n["config_sha256"] {
        return Err("no-media handoff configuration differs".into());
    }
    if schema == "rusty.quest.embedded_duplex.no_media_whole_retirement.v3" {
        native_fields.extend([
            "construction_disposition",
            "closure_body_sha256",
            "durable_closure",
            "provider_entered",
            "new_media_starts",
            "old_outcomes_preserved",
            "future_writable_restore",
        ]);
        value_fields(n, &native_fields)?;
        if n["$schema"] != "rusty.quest.embedded_duplex.no_media_retirement.v3"
            || n["native_runtime_binding"] != "partial_construction"
            || n["disposition"] != "partial_construction_closed"
            || n["construction_disposition"] != "not_committed"
            || n["new_media_starts"].as_u64() != Some(0)
            || n["old_outcomes_preserved"] != true
            || !n["closure_body_sha256"].as_str().is_some_and(|s| {
                s.strip_prefix("sha256:")
                    .is_some_and(|h| hex(&serde_json::Value::String(h.into())))
            })
        {
            return Err("no-media partial construction disposition differs".into());
        }
        match n["provider_entered"].as_bool() {
            Some(false)
                if n["durable_closure"].is_null()
                    && n["future_writable_restore"] == "owner_factory_not_entered" =>
            {
                ()
            }
            Some(true)
                if n["future_writable_restore"]
                    == "fenced_pending_supported_owner_reconciliation" =>
            {
                let ack = &n["durable_closure"];
                value_fields(
                    ack,
                    &[
                        "$schema",
                        "body_sha256",
                        "app_process_generation",
                        "app_record_sha256",
                        "native_executor_generation",
                        "config_sha256",
                        "storage_identity",
                    ],
                )?;
                if ack["$schema"]
                    != "rusty.quest.embedded_duplex.partial_construction_closure_retained.v1"
                    || ack["body_sha256"] != n["closure_body_sha256"]
                    || ack["storage_identity"] != "app-no-backup:partial-construction-v1:closure"
                {
                    return Err("no-media partial construction closure differs".into());
                }
                for key in [
                    "app_process_generation",
                    "app_record_sha256",
                    "native_executor_generation",
                    "config_sha256",
                ] {
                    if ack[key] != n[key] {
                        return Err("no-media partial closure actor differs".into());
                    }
                }
            }
            _ => return Err("no-media partial construction owner disposition differs".into()),
        }
        return Ok(());
    }
    let fresh = n["disposition"] == "pending_fresh_local_closed";
    if !finite {
        native_fields.push("configuration_binding");
    }
    if fresh {
        native_fields.extend([
            "transaction_outcome",
            "durable_closure",
            "future_writable_restore",
        ]);
    } else if configured {
        native_fields.push("native_close");
    }
    value_fields(n, &native_fields)?;
    if n["$schema"]
        != if finite {
            "rusty.quest.embedded_duplex.no_media_retirement.v1"
        } else {
            "rusty.quest.embedded_duplex.no_media_retirement.v2"
        }
        || n["native_runtime_binding"]
            != if configured {
                "configured"
            } else {
                "not_initialized"
            }
        || n["disposition"]
            != if fresh {
                "pending_fresh_local_closed"
            } else if configured {
                "runtime_config_closed"
            } else {
                "uninitialized_fence_closed"
            }
        || finite && fresh
    {
        return Err("no-media Native disposition differs".into());
    }
    if !finite {
        let constructor = &n["configuration_binding"];
        value_fields(
            constructor,
            &[
                "$schema",
                "config_sha256",
                "app_process_generation",
                "app_record_sha256",
                "native_executor_generation",
                "local_peer_id",
                "fresh_setup_build_policy",
            ],
        )?;
        if constructor["$schema"] != "rusty.quest.embedded_duplex.runtime_configuration_binding.v1"
        {
            return Err("no-media constructor schema differs".into());
        }
        for key in [
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
        ] {
            if n[key] != constructor[key] {
                return Err("no-media constructor actor differs".into());
            }
        }
    }
    if fresh {
        let ack = &n["durable_closure"];
        value_fields(
            ack,
            &[
                "$schema",
                "body_sha256",
                "storage_identity",
                "original_binding",
                "app_process_generation",
                "app_record_sha256",
                "native_executor_generation",
                "config_sha256",
            ],
        )?;
        if n["transaction_outcome"] != "unknown"
            || n["future_writable_restore"] != "fenced_pending_supported_owner_reconciliation"
            || ack["$schema"] != "rusty.quest.embedded_duplex.fresh_setup_closure_retained.v1"
            || !ack["storage_identity"]
                .as_str()
                .is_some_and(|s| !s.is_empty())
            || !ack["body_sha256"].as_str().is_some_and(|s| {
                s.strip_prefix("sha256:")
                    .is_some_and(|h| hex(&serde_json::Value::String(h.into())))
            })
        {
            return Err("no-media retained unknown closure differs".into());
        }
        for key in [
            "app_process_generation",
            "app_record_sha256",
            "native_executor_generation",
            "config_sha256",
        ] {
            if ack[key] != n[key] {
                return Err("no-media closure actor differs".into());
            }
        }
    } else if configured {
        let close = &n["native_close"];
        if close["$schema"] != "rusty.quest.embedded_duplex.no_media_closed.v1"
            || close["config_sha256"] != p["config_sha256"]
        {
            return Err("no-media Native close differs".into());
        }
    }
    Ok(())
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct AppBinding {
    pub generation: u64,
    pub record_sha256: String,
    checkpoint_sha256: String,
    evidence_sha256: String,
    pub operational_run_id: Option<String>,
}
impl AppBinding {
    pub(super) fn parse(record: &str) -> Result<Self, String> {
        let (binding, pending) = Self::inspect(record)?;
        if !pending {
            return Err("native admission requires pending app fence".into());
        }
        Ok(binding)
    }

    /// Observation accepts clear, but never turns it into execution admission.
    pub(super) fn inspect(record: &str) -> Result<(Self, bool), String> {
        let fields: Vec<_> = record.split('\n').collect();
        let explicit = fields.first() == Some(&APP_RUN_SCHEMA);
        let checksum = if explicit { 7 } else { 6 };
        let hex = |v: &str| {
            v.len() == 64
                && v.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        };
        let uuid = |v: &str| {
            v.len() == 36
                && v.bytes().enumerate().all(|(i, b)| {
                    if [8, 13, 18, 23].contains(&i) {
                        b == b'-'
                    } else {
                        b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
                    }
                })
        };
        if record.len() > 512
            || fields.len() != checksum + 2
            || (!explicit && fields[0] != APP_SCHEMA)
            || fields[1].starts_with('0')
            || !fields[1].bytes().all(|b| b.is_ascii_digit())
            || !uuid(fields[2])
            || !matches!(fields[3], "pending" | "clear")
            || ![fields[4], fields[5]].iter().all(|v| *v == "-" || hex(v))
            || (explicit
                && (fields[6].len() != 32
                    || !fields[6]
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))))
            || !hex(fields[checksum])
            || fields[checksum] != digest(&(fields[..checksum].join("\n") + "\n"))
            || !fields[checksum + 1].is_empty()
        {
            return Err("native app fence binding invalid or recovery unsupported".into());
        }
        let generation: u64 = fields[1]
            .parse()
            .map_err(|_| "native app generation invalid")?;
        if generation == 0 || generation > i64::MAX as u64 {
            return Err("native app generation invalid".into());
        }
        Ok((
            Self {
                generation,
                record_sha256: digest(record),
                checkpoint_sha256: fields[4].to_owned(),
                evidence_sha256: fields[5].to_owned(),
                operational_run_id: explicit.then(|| fields[6].to_owned()),
            },
            fields[3] == "pending",
        ))
    }
}

fn private_metadata(path: &Path, directory: bool) -> Result<fs::Metadata, String> {
    let metadata = fs::symlink_metadata(path).map_err(|_| "native fence path unavailable")?;
    let expected = if directory { 0o700 } else { 0o600 };
    if metadata.file_type().is_symlink()
        || metadata.uid() != unsafe { libc::geteuid() }
        || metadata.mode() & 0o777 != expected
        || if directory {
            !metadata.is_dir()
        } else {
            !metadata.is_file() || metadata.nlink() != 1
        }
    {
        return Err("native fence private path invalid".into());
    }
    Ok(metadata)
}
fn same_inode(a: &fs::Metadata, b: &fs::Metadata) -> bool {
    a.dev() == b.dev() && a.ino() == b.ino()
}
fn read_bounded(file: &mut File) -> Result<String, String> {
    let size = file
        .metadata()
        .map_err(|_| "native fence stat failed")?
        .len();
    if size == 0 || size > 2048 {
        return Err("native fence state missing or corrupt".into());
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|_| "native fence seek failed")?;
    let mut bytes = vec![0; size as usize];
    file.read_exact(&mut bytes)
        .map_err(|_| "native fence read failed")?;
    String::from_utf8(bytes).map_err(|_| "native fence state encoding invalid".into())
}

struct Writer {
    directory_path: PathBuf,
    directory: File,
    file: Mutex<File>,
    app_source: Arc<dyn AppFenceSource>,
    app_generation: AtomicU64,
    counter: AtomicU64,
    poisoned: AtomicBool,
}
// Closed classifications only. Arbitrary errors are fingerprinted, never logged.
fn fence_failure_category(cause: &str) -> &'static str {
    match cause {
        "native fence path unavailable" => "NATIVE_PATH_UNAVAILABLE",
        "native fence private path invalid" => "NATIVE_PRIVATE_PATH_INVALID",
        "native directory stat failed" => "NATIVE_DIRECTORY_STAT",
        "native fence directory changed" => "NATIVE_DIRECTORY_CHANGED",
        "native witness read failed" => "NATIVE_WITNESS_READ",
        "native fence witness changed" => "NATIVE_WITNESS_CHANGED",
        "native writer mutex poisoned" => "NATIVE_WRITER_MUTEX",
        "native file stat failed" | "native fence stat failed" => "NATIVE_FILE_STAT",
        "native writer lease unavailable" => "NATIVE_WRITER_LEASE",
        "native incarnation journal differs" => "NATIVE_JOURNAL_COUNTER",
        "native incarnation journal corrupt" => "NATIVE_JOURNAL_CORRUPT",
        "native fence state missing or corrupt" => "NATIVE_JOURNAL_SIZE",
        "native fence seek failed" => "NATIVE_JOURNAL_SEEK",
        "native fence read failed" => "NATIVE_JOURNAL_READ",
        "native fence state encoding invalid" => "NATIVE_JOURNAL_UTF8",
        "native generation seek failed" => "NATIVE_GENERATION_SEEK",
        "native generation write failed" => "NATIVE_GENERATION_WRITE",
        "native generation truncate failed" => "NATIVE_GENERATION_TRUNCATE",
        "native generation sync failed" => "NATIVE_GENERATION_SYNC",
        "native generation directory sync failed" => "NATIVE_GENERATION_DIRECTORY_SYNC",
        "native app channel attach failed" => "APP_ATTACH_UNAVAILABLE",
        "native app fence callback unavailable" => "APP_CALLBACK_UNAVAILABLE",
        "native app fence callback failed" => "APP_CALLBACK_FAILED",
        "native app fence callback type" => "APP_CALLBACK_TYPE",
        "native app fence callback absent" => "APP_CALLBACK_ABSENT",
        "native app fence callback encoding" => "APP_CALLBACK_ENCODING",
        "native app fence callback bounds" => "APP_CALLBACK_BOUNDS",
        "native admission requires pending app fence" => "APP_PENDING_REQUIRED",
        "native app fence binding invalid or recovery unsupported" => "APP_RECORD_INVALID",
        "native app generation invalid" => "APP_GENERATION_INVALID",
        "app generation changed" => "APP_GENERATION_CHANGED",
        "app operational run changed" => "APP_RUN_CHANGED",
        "app checkpoint changed" => "APP_CHECKPOINT_CHANGED",
        "app evidence changed" => "APP_EVIDENCE_CHANGED",
        "app record digest changed" => "APP_RECORD_DIGEST_CHANGED",
        _ => "UNKNOWN_FAILURE",
    }
}
fn emit_fence_failure(operation: &str, cause: &str, generation: u64) {
    // Evidence cannot replace the existing refusal, including a failed log sink.
    let _ = std::panic::catch_unwind(|| {
        #[cfg(target_os = "android")]
        crate::camera_hwb_marker::log_camera_hwb_marker(format!(
            "status=native-fence-first-failure operation={operation} cause={} writer_generation={generation} error_sha256={} error_bytes={}",
            fence_failure_category(cause), digest(cause), cause.len()
        ));
    });
}
impl Writer {
    fn poison(&self, operation: &'static str, cause: &str) {
        // Publish the same fail-closed state before telemetry. The existing
        // atomic chooses one immutable first marker without another lock.
        if !self.poisoned.swap(true, Ordering::SeqCst) {
            emit_fence_failure(operation, cause, self.counter.load(Ordering::SeqCst));
        }
    }
    fn open(
        directory_path: &Path,
        app_source: Arc<dyn AppFenceSource>,
        binding: &AppBinding,
    ) -> Result<Arc<Self>, String> {
        let metadata = private_metadata(directory_path, true)?;
        let directory = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(directory_path)
            .map_err(|_| "native fence directory open failed")?;
        if !same_inode(
            &metadata,
            &directory
                .metadata()
                .map_err(|_| "native fence directory stat failed")?,
        ) {
            return Err("native fence directory changed".into());
        }
        let path = directory_path.join(LOCK_NAME);
        let witness = directory_path.join(WITNESS_NAME);
        let witnessed = witness
            .try_exists()
            .map_err(|_| "native witness stat failed")?;
        if witnessed {
            let metadata = private_metadata(&witness, false)?;
            if metadata.len() > 128
                || fs::read_to_string(&witness).map_err(|_| "native witness read failed")?
                    != format!("{NATIVE_SCHEMA}\n")
            {
                return Err("native fence initialization witness corrupt".into());
            }
            if !path.exists() {
                return Err("native fence state missing".into());
            }
        }
        let (mut file, created) = match OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(&path)
        {
            Ok(file) => (file, true),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => (
                OpenOptions::new()
                    .read(true)
                    .write(true)
                    .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
                    .open(&path)
                    .map_err(|_| "native fence file open failed")?,
                false,
            ),
            Err(_) => return Err("native fence file creation failed".into()),
        };
        let path_metadata = private_metadata(&path, false)?;
        if !same_inode(
            &path_metadata,
            &file
                .metadata()
                .map_err(|_| "native fence file stat failed")?,
        ) {
            return Err("native fence inode changed".into());
        }
        // flock is attached to this open file description. Never clone this
        // descriptor into Java or open the Java fcntl lock inode here.
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            return Err("native process writer already held".into());
        }
        let counter = if created {
            if witnessed {
                return Err("native fence state missing".into());
            }
            let mut marker = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
                .open(&witness)
                .map_err(|_| "native witness create failed")?;
            marker
                .write_all(format!("{NATIVE_SCHEMA}\n").as_bytes())
                .map_err(|_| "native witness write failed")?;
            marker
                .sync_all()
                .map_err(|_| "native witness sync failed")?;
            directory
                .sync_all()
                .map_err(|_| "native fence directory sync failed")?;
            0
        } else {
            if !witnessed {
                return Err("native initialization witness missing".into());
            }
            parse_counter(&read_bounded(&mut file)?)?
        };
        Ok(Arc::new(Self {
            directory_path: directory_path.to_owned(),
            directory,
            file: Mutex::new(file),
            app_source,
            app_generation: AtomicU64::new(binding.generation),
            counter: AtomicU64::new(counter),
            poisoned: AtomicBool::new(false),
        }))
    }
    fn observe(&self) -> Result<(), String> {
        if self.poisoned.load(Ordering::SeqCst) {
            return Err("native writer poisoned".into());
        }
        let result: Result<(), String> = (|| {
            let directory = private_metadata(&self.directory_path, true)?;
            if !same_inode(
                &directory,
                &self
                    .directory
                    .metadata()
                    .map_err(|_| "native directory stat failed")?,
            ) {
                return Err("native fence directory changed".into());
            }
            let witness = self.directory_path.join(WITNESS_NAME);
            if private_metadata(&witness, false)?.len() > 128
                || fs::read_to_string(witness).map_err(|_| "native witness read failed")?
                    != format!("{NATIVE_SCHEMA}\n")
            {
                return Err("native fence witness changed".into());
            }
            let path = private_metadata(&self.directory_path.join(LOCK_NAME), false)?;
            let mut file = self
                .file
                .lock()
                .map_err(|_| "native writer mutex poisoned")?;
            if !same_inode(
                &path,
                &file.metadata().map_err(|_| "native file stat failed")?,
            ) || unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0
            {
                return Err("native writer lease unavailable".into());
            }
            if self.counter.load(Ordering::SeqCst) > 0
                && parse_counter(&read_bounded(&mut file)?)? != self.counter.load(Ordering::SeqCst)
            {
                return Err("native incarnation journal differs".into());
            }
            Ok(())
        })();
        if let Err(cause) = &result {
            // The file guard in the observation closure has already dropped.
            self.poison("OBSERVE", cause);
        }
        result
    }
    fn allocate(
        self: &Arc<Self>,
        binding: AppBinding,
        operational_handoff_sha256: Option<String>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.observe()?;
        if binding.generation != self.app_generation.load(Ordering::SeqCst) {
            return Err("native app generation differs".into());
        }
        let next = self
            .counter
            .load(Ordering::SeqCst)
            .checked_add(1)
            .filter(|v| *v <= i64::MAX as u64)
            .ok_or("native executor generation exhausted")?;
        // Counter and owner are one checksum-bound durable write, before capability publication.
        // An Original result is not required to recover this binding after a crash.
        let app_record = self.app_source.record()?;
        if AppBinding::parse(&app_record)? != binding {
            return Err("allocation app owner changed".into());
        }
        let owner = serde_json::json!({"counter":next,"app_record":app_record,"process_identity":current_native_process_identity()?});
        let body = format!(
            "{NATIVE_OWNER_SCHEMA}\n{}\n",
            canonical_handoff_json(&owner)?
        );
        let record = format!("{body}{}\n", digest(&body));
        let committed = (|| {
            let mut file = self
                .file
                .lock()
                .map_err(|_| "native writer mutex poisoned")?;
            file.seek(SeekFrom::Start(0))
                .map_err(|_| "native generation seek failed")?;
            file.write_all(record.as_bytes())
                .map_err(|_| "native generation write failed")?;
            file.set_len(record.len() as u64)
                .map_err(|_| "native generation truncate failed")?;
            file.sync_all()
                .map_err(|_| "native generation sync failed")?;
            self.directory
                .sync_all()
                .map_err(|_| "native generation directory sync failed")?;
            Ok::<(), String>(())
        })();
        if let Err(reason) = committed {
            // The commit guard has dropped; no diagnostic I/O under its mutex.
            self.poison("ALLOCATE", &reason);
            return Err(reason);
        }
        self.counter.store(next, Ordering::SeqCst);
        Ok(Arc::new(NativeCapability {
            writer: self.clone(),
            binding,
            generation: next,
            retired: AtomicBool::new(false),
            operational_handoff_sha256,
        }))
    }
}
fn parse_counter(record: &str) -> Result<u64, String> {
    if record.starts_with(&format!("{NATIVE_OWNER_SCHEMA}\n")) {
        return Ok(parse_owner_record(record)?
            .get("counter")
            .and_then(serde_json::Value::as_u64)
            .unwrap());
    }
    let fields: Vec<_> = record.split('\n').collect();
    if fields.len() != 4
        || fields[0] != NATIVE_SCHEMA
        || fields[1].starts_with('0')
        || !fields[1].bytes().all(|b| b.is_ascii_digit())
        || fields[3] != ""
        || fields[2] != digest(&(fields[..2].join("\n") + "\n"))
    {
        return Err("native incarnation journal corrupt".into());
    }
    fields[1]
        .parse::<u64>()
        .ok()
        .filter(|v| *v > 0 && *v <= i64::MAX as u64)
        .ok_or("native incarnation journal invalid".into())
}

fn current_native_process_identity() -> Result<serde_json::Value, String> {
    let boot = fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .map_err(|_| "native owner boot unavailable")?;
    let pid = std::process::id();
    let stat = fs::read_to_string(format!("/proc/{pid}/stat"))
        .map_err(|_| "native owner birth unavailable")?;
    let end = stat.rfind(')').ok_or("native owner stat invalid")?;
    let ticks = stat[end + 1..]
        .split_whitespace()
        .nth(19)
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|v| *v > 0)
        .ok_or("native owner birth invalid")?;
    if fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .map_err(|_| "native owner boot unavailable")?
        != boot
    {
        return Err("native owner boot changed".into());
    }
    Ok(
        serde_json::json!({"boot_id":boot.trim(),"pid":pid,"uid":unsafe{libc::geteuid()},"start_ticks":ticks}),
    )
}

fn parse_owner_record(record: &str) -> Result<serde_json::Value, String> {
    let f = record.split('\n').collect::<Vec<_>>();
    if record.len() > 2048
        || f.len() != 4
        || f[0] != NATIVE_OWNER_SCHEMA
        || !f[3].is_empty()
        || f[2] != digest(&format!("{}\n{}\n", f[0], f[1]))
    {
        return Err("native owner/counter record invalid".into());
    }
    let value: serde_json::Value =
        serde_json::from_str(f[1]).map_err(|_| "native owner binding JSON invalid")?;
    let fields = value
        .as_object()
        .ok_or("native owner binding object required")?;
    if !(fields.len() == 3 || fields.len() == 4 && fields.contains_key("retirement"))
        || !["counter", "app_record", "process_identity"]
            .iter()
            .all(|k| fields.contains_key(*k))
        || !value["counter"]
            .as_u64()
            .is_some_and(|v| v > 0 && v <= i64::MAX as u64)
        || canonical_handoff_json(&value)? != f[1]
    {
        return Err("native owner binding fields invalid".into());
    }
    AppBinding::parse(
        value["app_record"]
            .as_str()
            .ok_or("native owner app record missing")?,
    )?;
    let p = value["process_identity"]
        .as_object()
        .ok_or("native owner identity missing")?;
    if p.len() != 4
        || !["boot_id", "pid", "uid", "start_ticks"]
            .iter()
            .all(|k| p.contains_key(*k))
        || !p["boot_id"].as_str().is_some_and(|v| {
            v.len() == 36
                && v.bytes().enumerate().all(|(i, b)| {
                    if [8, 13, 18, 23].contains(&i) {
                        b == b'-'
                    } else {
                        b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
                    }
                })
        })
        || !p["pid"]
            .as_u64()
            .is_some_and(|v| v > 0 && v <= i32::MAX as u64)
        || !p["uid"].as_u64().is_some_and(|v| v <= u32::MAX as u64)
        || !p["start_ticks"]
            .as_u64()
            .is_some_and(|v| v > 0 && v <= i64::MAX as u64)
    {
        return Err("native owner identity invalid".into());
    }
    if let Some(r) = value.get("retirement") {
        value_fields(r, &["receipt", "current_process_identity", "app_record"])?;
        let binding = AppBinding::parse(
            r["app_record"]
                .as_str()
                .ok_or("retired app record absent")?,
        )?;
        let n = &r["receipt"];
        value_fields(
            n,
            &[
                "$schema",
                "recovery_original_id",
                "run_id",
                "app_process_generation",
                "app_record_sha256",
                "prior_native_generation",
                "owner_link_sha256",
                "disposition",
                "native_counter_preserved",
                "transaction_outcome",
                "remote_outcome_resolved",
            ],
        )?;
        value_fields(
            &r["current_process_identity"],
            &["boot_id", "pid", "uid", "start_ticks"],
        )?;
        if !matches!(
            n["$schema"].as_str(),
            Some(
                "rusty.quest.embedded_duplex.cold_pending_native_retirement.v1"
                    | "rusty.quest.embedded_duplex.cold_pending_native_retirement.v2"
            )
        ) || n["app_process_generation"].as_u64() != Some(binding.generation)
            || n["app_record_sha256"] != binding.record_sha256
            || n["run_id"].as_str() != binding.operational_run_id.as_deref()
            || n["prior_native_generation"] != value["counter"]
            || n["native_counter_preserved"] != true
            || n["transaction_outcome"] != "unchanged"
            || n["remote_outcome_resolved"] != false
            || n["disposition"] != "prior_process_local_retired"
            || r["current_process_identity"]["uid"] != value["process_identity"]["uid"]
        {
            return Err("native retained local retirement invalid".into());
        }
    }
    Ok(value)
}

pub(super) fn cold_pending_owner(directory: &Path) -> Result<serde_json::Value, String> {
    let counter =
        inspect_unclaimed_counter(directory)?.ok_or("cold recovery Native counter absent")?;
    let path = directory.join(LOCK_NAME);
    private_metadata(&path, false)?;
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(&path)
        .map_err(|_| "cold owner read failed")?;
    let raw = read_bounded(&mut file)?;
    if parse_counter(&raw)? != counter {
        return Err("cold owner changed during read".into());
    }
    let owner = if raw.starts_with(&format!("{NATIVE_OWNER_SCHEMA}\n")) {
        parse_owner_record(&raw)?
    } else {
        serde_json::Value::Null
    };
    Ok(
        serde_json::json!({"$schema":"rusty.quest.embedded_duplex.cold_pending_native_owner.v1","native_generation":counter,"native_record_sha256":digest(&raw),"owner":owner}),
    )
}

fn value_fields(value: &serde_json::Value, names: &[&str]) -> Result<(), String> {
    if !value
        .as_object()
        .is_some_and(|v| v.len() == names.len() && names.iter().all(|k| v.contains_key(*k)))
    {
        return Err("cold recovery closed fields differ".into());
    }
    Ok(())
}
/// The closed retirement DTO is shared by the commit and finite producer/consumer ABI controls.
/// This pure projection supplies no cleanup authority; only the verified commit may publish it.
pub(super) fn cold_pending_retirement_receipt(
    record: &str,
    link: &str,
    counter: u64,
) -> Result<serde_json::Value, String> {
    let binding = AppBinding::parse(record)?;
    let evidence: serde_json::Value =
        serde_json::from_str(link).map_err(|_| "cold receipt owner link invalid")?;
    let schema = if evidence["$schema"] == "rusty.quest.embedded_duplex.cold_pending_owner_link.v2"
    {
        "rusty.quest.embedded_duplex.cold_pending_native_retirement.v2"
    } else {
        "rusty.quest.embedded_duplex.cold_pending_native_retirement.v1"
    };
    Ok(serde_json::json!({"$schema":schema,
        "recovery_original_id":evidence["recovery_original_id"],"run_id":evidence["run_id"],"app_process_generation":binding.generation,
        "app_record_sha256":binding.record_sha256,"prior_native_generation":counter,"owner_link_sha256":digest(link),
        "disposition":"prior_process_local_retired","native_counter_preserved":true,"transaction_outcome":"unchanged","remote_outcome_resolved":false}))
}
fn verify_dead_native_owner(
    old: &serde_json::Value,
    current: &serde_json::Value,
    basis: &str,
) -> Result<(), String> {
    if &current_native_process_identity()? != current || old["uid"] != current["uid"] {
        return Err("cold recovery current kernel actor differs".into());
    }
    let actual = if old["boot_id"] != current["boot_id"] {
        "boot_changed"
    } else {
        let pid = old["pid"].as_u64().ok_or("cold old PID absent")?;
        match fs::read_to_string(format!("/proc/{pid}/stat")) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => "not_running",
            Err(_) => return Err("cold old process observation unavailable".into()),
            Ok(stat) => {
                let end = stat.rfind(')').ok_or("cold old stat invalid")?;
                let ticks = stat[end + 1..]
                    .split_whitespace()
                    .nth(19)
                    .and_then(|s| s.parse::<u64>().ok())
                    .ok_or("cold old birth invalid")?;
                if Some(ticks) == old["start_ticks"].as_u64() {
                    return Err("cold old process still live".into());
                }
                "pid_reused"
            }
        }
    };
    if actual != basis {
        return Err("cold old process retirement basis changed".into());
    }
    Ok(())
}
fn validate_cold_link(
    raw: &str,
    binding: &AppBinding,
    record: &str,
    counter: u64,
) -> Result<serde_json::Value, String> {
    if raw.len() > 16384 {
        return Err("cold owner link bounds".into());
    }
    let v: serde_json::Value =
        serde_json::from_str(raw).map_err(|_| "cold owner link JSON invalid")?;
    let maintained_journals =
        v["$schema"] == "rusty.quest.embedded_duplex.cold_pending_owner_link.v2";
    let mut fields = vec![
        "$schema",
        "recovery_original_id",
        "run_id",
        "app_process_generation",
        "app_record_sha256",
        "prior_native_generation",
        "native_record_sha256",
        "native_owner",
        "last_writable_record",
        "last_incarnation_json",
        "old_process_identity",
        "current_process_identity",
        "old_process_retirement",
    ];
    if maintained_journals {
        fields.push("journal_snapshot");
    }
    value_fields(&v, &fields)?;
    if !maintained_journals
        && v["$schema"] != "rusty.quest.embedded_duplex.cold_pending_owner_link.v1"
        || canonical_handoff_json(&v)? != raw
        || v["recovery_original_id"].as_str().is_none_or(|s| {
            s.len() != 32
                || !s
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
        || v["run_id"].as_str() != binding.operational_run_id.as_deref()
        || v["app_process_generation"].as_u64() != Some(binding.generation)
        || v["app_record_sha256"] != binding.record_sha256
        || v["prior_native_generation"].as_u64() != Some(counter)
        || v["native_record_sha256"] != digest(record)
        || v["native_owner"]["counter"].as_u64() != Some(counter)
    {
        return Err("cold owner link current/counter subject differs".into());
    }
    let source = AppBinding::parse(
        v["native_owner"]["app_record"]
            .as_str()
            .ok_or("cold source app record absent")?,
    )?;
    let (last, _last_pending) = AppBinding::inspect(
        v["last_writable_record"]
            .as_str()
            .ok_or("cold last writable record absent")?,
    )?;
    // A selected new run may inspect the authentic, unretired Native owner
    // from its retained source run. Its companion stays bound to that source;
    // the recovery output and independently held current channel stay selected.
    let retained_source_writer = maintained_journals
        && record.starts_with(&format!("{NATIVE_OWNER_SCHEMA}\n"))
        && v["native_owner"].get("retirement").is_none()
        && source.operational_run_id.is_some()
        && source.operational_run_id == last.operational_run_id
        && source.generation == last.generation
        && v["native_owner"]["app_record"]
            .as_str()
            .unwrap()
            .split('\n')
            .nth(2)
            == v["last_writable_record"]
                .as_str()
                .unwrap()
                .split('\n')
                .nth(2);
    if last.operational_run_id != binding.operational_run_id && !retained_source_writer
        || source.generation > last.generation
        || last.generation >= binding.generation
    {
        return Err("cold writable phase ordering differs".into());
    }
    if maintained_journals {
        let snapshot = &v["journal_snapshot"];
        value_fields(
            snapshot,
            &[
                "checkpoint_sha256",
                "evidence_sha256",
                "prior_checkpoint_sha256",
                "prior_evidence_sha256",
            ],
        )?;
        let current_record = v["app_record_sha256"]
            .as_str()
            .ok_or("cold current digest absent")?;
        if current_record != binding.record_sha256 {
            return Err("cold journal binding subject differs".into());
        }
        // The current AppFenceSource is independently held by JNI. Its exact
        // current journal hashes differ legitimately after an interrupted write;
        // the old companion/phase remains separately authenticated below.
        let current = (&binding.checkpoint_sha256, &binding.evidence_sha256);
        let prior: Vec<_> = v["last_writable_record"]
            .as_str()
            .unwrap()
            .split('\n')
            .collect();
        if snapshot["checkpoint_sha256"].as_str() != Some(current.0.as_str())
            || snapshot["evidence_sha256"].as_str() != Some(current.1.as_str())
            || snapshot["prior_checkpoint_sha256"] != prior[4]
            || snapshot["prior_evidence_sha256"] != prior[5]
        {
            return Err("cold retained journal snapshot differs".into());
        }
    }
    let companion: serde_json::Value = serde_json::from_str(
        v["last_incarnation_json"]
            .as_str()
            .ok_or("cold incarnation absent")?,
    )
    .map_err(|_| "cold incarnation JSON invalid")?;
    value_fields(
        &companion,
        &[
            "$schema",
            "original_owner",
            "effect_category",
            "original_fence_record",
            "current_fence_sha256",
            "local_resources_terminated",
            "termination_receipt_sha256",
            "sha256",
        ],
    )?;
    let old = &v["old_process_identity"];
    value_fields(old, &["boot_id", "pid", "uid", "start_ticks"])?;
    if companion["$schema"] != "rusty.quest.embedded_duplex.process_incarnation.v1"
        || companion["original_owner"] != *old
        || companion["effect_category"] != "process_local_resources"
        || companion["local_resources_terminated"] != false
        || companion["termination_receipt_sha256"] != "-"
        || companion["current_fence_sha256"] != last.record_sha256
    {
        return Err("cold companion last owner differs".into());
    }
    let checksum = digest(&format!(
        "{}\n{}\n{}\n{}\n{}{}\nfalse\n-\nprocess_local_resources\n",
        old["boot_id"].as_str().ok_or("old boot absent")?,
        old["pid"].as_u64().ok_or("old PID absent")?,
        old["uid"].as_u64().ok_or("old UID absent")?,
        old["start_ticks"].as_u64().ok_or("old birth absent")?,
        companion["original_fence_record"]
            .as_str()
            .ok_or("old original record absent")?,
        last.record_sha256
    ));
    if companion["sha256"] != checksum {
        return Err("cold companion checksum differs".into());
    }
    let maintained = record.starts_with(&format!("{NATIVE_OWNER_SCHEMA}\n"));
    if maintained {
        if parse_owner_record(record)? != v["native_owner"] {
            return Err("cold durable Native owner differs".into());
        }
        if v["native_owner"].get("retirement").is_none()
            && v["native_owner"]["process_identity"] != *old
        {
            return Err("cold Native owner/last writer differs".into());
        }
    } else if v["native_owner"]["process_identity"] != *old {
        return Err("legacy Native owner/last writer differs".into());
    }
    verify_dead_native_owner(
        old,
        &v["current_process_identity"],
        v["old_process_retirement"]
            .as_str()
            .ok_or("cold retirement basis absent")?,
    )?;
    Ok(v)
}

pub(super) struct NativeCapability {
    writer: Arc<Writer>,
    pub binding: AppBinding,
    pub generation: u64,
    retired: AtomicBool,
    operational_handoff_sha256: Option<String>,
}
impl NativeCapability {
    pub fn require_live(&self) -> Result<(), String> {
        if self.retired.load(Ordering::SeqCst)
            || self.writer.counter.load(Ordering::SeqCst) != self.generation
        {
            return Err("native executor capability retired".into());
        }
        self.writer.observe()?;
        // No native mutex is held across the live app-channel callback.
        let mut operation = "APP_RECORD_PARSE";
        let observed = self
            .writer
            .app_source
            .record()
            .map_err(|error| {
                operation = "APP_CALLBACK";
                error
            })
            .and_then(|record| AppBinding::parse(&record));
        if observed.as_ref() != Ok(&self.binding) {
            match &observed {
                Err(cause) => self.writer.poison(operation, cause),
                Ok(binding) => self.writer.poison(
                    "APP_BINDING",
                    if binding.generation != self.binding.generation {
                        "app generation changed"
                    } else if binding.operational_run_id != self.binding.operational_run_id {
                        "app operational run changed"
                    } else if binding.checkpoint_sha256 != self.binding.checkpoint_sha256 {
                        "app checkpoint changed"
                    } else if binding.evidence_sha256 != self.binding.evidence_sha256 {
                        "app evidence changed"
                    } else {
                        "app record digest changed"
                    },
                ),
            }
            return Err("native app record or lease changed".into());
        }
        if self.retired.load(Ordering::SeqCst) {
            return Err("native executor capability retired".into());
        }
        Ok(())
    }
    /// This only retires execution authority; callers must already prove native
    /// no-media closure and the app's Java/display barriers. It clears no journal.
    pub fn retire(&self) -> Result<(), String> {
        self.require_live()?;
        self.retired.store(true, Ordering::SeqCst);
        Ok(())
    }
}
#[derive(Default)]
struct RegistryState {
    busy: bool,
    writer: Option<Arc<Writer>>,
    active: Option<Arc<NativeCapability>>,
    absence_retired: Option<AppBinding>,
    cold_retirement: Option<(AppBinding, String, String)>,
}
#[derive(Default)]
pub(super) struct NativeFenceRegistry {
    state: Mutex<RegistryState>,
}

pub(super) struct OperationalRunInspection {
    pub actual_binding: Option<AppBinding>,
    pub native_generation: Option<u64>,
    pub refusal: Option<&'static str>,
}

/// Read-only pre-admission check. Never initialize either permanent inode.
fn inspect_unclaimed_counter(directory_path: &Path) -> Result<Option<u64>, String> {
    private_metadata(directory_path, true)?;
    let path = directory_path.join(LOCK_NAME);
    let witness = directory_path.join(WITNESS_NAME);
    let exists = |path: &Path| -> Result<bool, String> {
        match fs::symlink_metadata(path) {
            Ok(_) => Ok(true),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(_) => Err("native inspection path unavailable".into()),
        }
    };
    if !exists(&path)? && !exists(&witness)? {
        return Ok(None);
    }
    let metadata = private_metadata(&path, false)?;
    if private_metadata(&witness, false)?.len() > 128
        || fs::read_to_string(&witness).map_err(|_| "native witness read failed")?
            != format!("{NATIVE_SCHEMA}\n")
    {
        return Err("native inspection witness corrupt".into());
    }
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_CLOEXEC | libc::O_NOFOLLOW)
        .open(&path)
        .map_err(|_| "native inspection file unavailable")?;
    if !same_inode(
        &metadata,
        &file
            .metadata()
            .map_err(|_| "native inspection stat failed")?,
    ) || unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0
    {
        return Err("native inspection writer unavailable".into());
    }
    let counter = parse_counter(&read_bounded(&mut file)?)?;
    if !same_inode(&metadata, &private_metadata(&path, false)?) {
        return Err("native inspection inode changed".into());
    }
    Ok(Some(counter))
}

impl NativeFenceRegistry {
    /// New local retirement, never a replay or settlement of an old transaction.
    /// Java/physical callbacks were bracketed by JNI before entering this Native-only commit.
    pub(super) fn retire_cold_pending_local(
        &self,
        directory: &Path,
        record: &str,
        link: &str,
    ) -> Result<String, String> {
        let binding = AppBinding::parse(record)?;
        let link_digest = digest(link);
        {
            let mut state = self.state.lock().map_err(|_| "cold registry poisoned")?;
            if let Some((prior, hash, receipt)) = &state.cold_retirement {
                if prior == &binding && hash == &link_digest {
                    return Ok(receipt.clone());
                }
                return Err("cold retirement source changed".into());
            }
            if state.busy
                || state.writer.is_some()
                || state.active.is_some()
                || state.absence_retired.is_some()
            {
                return Err("cold Native owner not absent".into());
            }
            state.busy = true;
        }
        struct FixedSource(String);
        impl AppFenceSource for FixedSource {
            fn record(&self) -> Result<String, String> {
                Ok(self.0.clone())
            }
        }
        let result = (|| {
            // The original Native flock protects the exact counter and inode; no Java callbacks below.
            let writer = Writer::open(directory, Arc::new(FixedSource(record.into())), &binding)?;
            writer.observe()?;
            let counter = writer.counter.load(Ordering::SeqCst);
            if counter == 0 {
                return Err("cold Native counter missing".into());
            }
            let before = {
                let mut file = writer
                    .file
                    .lock()
                    .map_err(|_| "cold Native file poisoned")?;
                read_bounded(&mut file)?
            };
            let evidence = validate_cold_link(link, &binding, &before, counter)?;
            let receipt = cold_pending_retirement_receipt(record, link, counter)?;
            // Legacy owner migration is exact evidence, not a counter reset or a fabricated old success.
            let mut owner = evidence["native_owner"].clone();
            owner["retirement"] = serde_json::json!({"receipt":receipt,
                "current_process_identity":evidence["current_process_identity"],"app_record":record});
            let body = format!(
                "{NATIVE_OWNER_SCHEMA}\n{}\n",
                canonical_handoff_json(&owner)?
            );
            let exact = format!("{body}{}\n", digest(&body));
            if exact.len() > 2048 {
                return Err("cold durable Native binding bounds".into());
            }
            {
                let mut file = writer
                    .file
                    .lock()
                    .map_err(|_| "cold Native file poisoned")?;
                file.seek(SeekFrom::Start(0))
                    .map_err(|_| "cold Native seek")?;
                file.write_all(exact.as_bytes())
                    .map_err(|_| "cold Native write")?;
                file.set_len(exact.len() as u64)
                    .map_err(|_| "cold Native truncate")?;
                file.sync_all().map_err(|_| "cold Native sync")?;
                writer
                    .directory
                    .sync_all()
                    .map_err(|_| "cold Native directory sync")?;
            }
            writer.observe()?;
            Ok(receipt.to_string())
        })();
        let mut state = self.state.lock().map_err(|_| "cold registry poisoned")?;
        if let Ok(receipt) = &result {
            state.absence_retired = Some(binding.clone());
            state.cold_retirement = Some((binding, link_digest, receipt.clone()));
        }
        // No capability was allocated. Failed/uncertain Original custody remains with Java.
        state.busy = false;
        result
    }
    /// Readback of an already accepted exact claim; never allocate/reset on retry.
    pub fn retry_selected_run(
        &self,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Option<Arc<NativeCapability>>, String> {
        let binding = AppBinding::parse(&source.record()?)?;
        if binding.operational_run_id.is_none() {
            return Ok(None);
        }
        let cap = {
            let s = self
                .state
                .lock()
                .map_err(|_| "native retry registry poisoned")?;
            if s.busy {
                return Err("native_registry_busy".into());
            }
            s.active.clone()
        };
        let Some(cap) = cap else { return Ok(None) };
        if cap.binding != binding || cap.retired.load(Ordering::SeqCst) {
            return Ok(None);
        }
        if let Some(expected) = &cap.operational_handoff_sha256 {
            let raw = source
                .operational_run_handoff()?
                .ok_or("accepted handoff source unavailable on retry")?;
            let prior = cap
                .generation
                .checked_sub(1)
                .ok_or("native retry generation invalid")?;
            if validate_operational_run_handoff(&raw, &binding, true, prior)? != *expected {
                return Err("native handoff retry source changed".into());
            }
        }
        cap.require_live()?;
        {
            let s = self
                .state
                .lock()
                .map_err(|_| "native retry registry poisoned")?;
            if s.busy
                || !s
                    .active
                    .as_ref()
                    .is_some_and(|active| Arc::ptr_eq(active, &cap))
            {
                return Err("native retry owner changed".into());
            }
        }
        Ok(Some(cap))
    }

    pub fn inspect_operational_run(
        &self,
        directory: &Path,
        observed: &AppBinding,
    ) -> Result<OperationalRunInspection, String> {
        let (busy, writer, cap, closed) = {
            let s = self
                .state
                .lock()
                .map_err(|_| "native registry inspection poisoned")?;
            (
                s.busy,
                s.writer.clone(),
                s.active.clone(),
                s.absence_retired.clone(),
            )
        };
        let native_generation = if let Some(writer) = &writer {
            writer.observe()?;
            let generation = writer.app_generation.load(Ordering::SeqCst);
            let next_run = observed.operational_run_id.is_some()
                && cap
                    .as_ref()
                    .map(|c| &c.binding)
                    .or(closed.as_ref())
                    .is_some_and(|b| b.operational_run_id != observed.operational_run_id);
            if (observed.generation < generation
                || (observed.generation != generation && !next_run))
                || !same_inode(
                    &private_metadata(directory, true)?,
                    &writer
                        .directory
                        .metadata()
                        .map_err(|_| "native inspection directory stat failed")?,
                )
            {
                return Err("native inspection app owner or directory changed".into());
            }
            Some(writer.counter.load(Ordering::SeqCst)).filter(|v| *v > 0)
        } else {
            inspect_unclaimed_counter(directory)?
        };
        let refusal = if busy {
            Some("native_registry_busy")
        } else if observed.operational_run_id.is_some()
            && writer.is_none()
            && cap.is_none()
            && closed.is_none()
            && native_generation.is_some()
        {
            // A counter has no durable run/closure mapping. A recreated
            // registry cannot authenticate the prior process owner.
            Some("native_prior_process_owner_unreconciled")
        } else if cap
            .as_ref()
            .is_some_and(|c| !c.retired.load(Ordering::SeqCst))
        {
            Some("native_capability_owned")
        } else if writer.is_some() && cap.is_none() {
            Some("native_writer_without_capability")
        } else {
            None
        };
        let s = self
            .state
            .lock()
            .map_err(|_| "native registry inspection poisoned")?;
        if s.busy != busy
            || s.writer.as_ref().map(Arc::as_ptr) != writer.as_ref().map(Arc::as_ptr)
            || s.active.as_ref().map(Arc::as_ptr) != cap.as_ref().map(Arc::as_ptr)
            || s.absence_retired != closed
        {
            return Err("native registry changed during inspection".into());
        }
        Ok(OperationalRunInspection {
            actual_binding: cap.map(|c| c.binding.clone()).or(closed),
            native_generation,
            refusal,
        })
    }

    /// Typed observation only. Missing/failed capability validation never becomes absence.
    pub fn ownership_status(&self) -> Result<serde_json::Value, String> {
        let (busy, writer, cap, closed) = {
            let s = self
                .state
                .lock()
                .map_err(|_| "native registry status poisoned")?;
            (
                s.busy,
                s.writer.is_some(),
                s.active.clone(),
                s.absence_retired.clone(),
            )
        };
        if busy {
            return Ok(serde_json::json!({"state":"busy"}));
        }
        if let Some(cap) = cap {
            let retired = cap.retired.load(Ordering::SeqCst);
            if !retired {
                cap.require_live()?;
            }
            return Ok(
                serde_json::json!({"state":if retired{"retired"}else{"admitted"},
                "app_process_generation":cap.binding.generation,"app_record_sha256":cap.binding.record_sha256,
                "native_executor_generation":cap.generation}),
            );
        }
        if let Some(binding) = closed {
            return Ok(serde_json::json!({"state":"absence_retired",
            "app_process_generation":binding.generation,"app_record_sha256":binding.record_sha256}));
        }
        Ok(serde_json::json!({"state":if writer{"writer_without_capability"}else{"never_claimed"}}))
    }

    /// Process-local absence, authenticated by the same live app owner channel.
    /// This allocates no native capability, opens no native writer, and changes no counter.
    pub fn retire_never_claimed(
        &self,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<AppBinding, String> {
        let first = AppBinding::parse(&source.record()?)?;
        {
            let mut s = self
                .state
                .lock()
                .map_err(|_| "native absence registry poisoned")?;
            if s.busy || s.writer.is_some() || s.active.is_some() || s.absence_retired.is_some() {
                return Err("native never-claimed absence unavailable".into());
            }
            s.busy = true;
        }
        // No registry mutex is held across the actual app-channel callback.
        // A failed callback leaves the gate busy/unknown rather than enabling a claim.
        let second = AppBinding::parse(&source.record()?)?;
        if first != second {
            return Err("native absence app owner changed".into());
        }
        let mut s = self
            .state
            .lock()
            .map_err(|_| "native absence registry poisoned")?;
        if !s.busy || s.writer.is_some() || s.active.is_some() || s.absence_retired.is_some() {
            return Err("native absence registry changed".into());
        }
        s.absence_retired = Some(first.clone());
        s.busy = false;
        Ok(first)
    }

    pub fn claim(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.claim_inner(directory, source, false, false)
    }

    /// The app/JNI owner must first check the real process/resource guards.
    /// This changes only completed registry metadata, never its retained writer.
    pub fn claim_selected_run(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.claim_inner(directory, source, true, false)
    }

    /// Only after the Native process/resource owner has validated completed close.
    pub fn claim_selected_run_after_completed_close(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
    ) -> Result<Arc<NativeCapability>, String> {
        self.claim_inner(directory, source, true, true)
    }

    fn claim_inner(
        &self,
        directory: &Path,
        source: Arc<dyn AppFenceSource>,
        selected_run: bool,
        completed_owner: bool,
    ) -> Result<Arc<NativeCapability>, String> {
        // Never call Java while holding the Native registry mutex.
        let binding = AppBinding::parse(&source.record()?)?;
        if selected_run && binding.operational_run_id.is_none() {
            return Err("explicit native run binding absent".into());
        }
        let (writer, completed_cap, completed_transition, unbound_run) = {
            let mut state = self
                .state
                .lock()
                .map_err(|_| "native fence registry poisoned")?;
            let prior = state
                .active
                .as_ref()
                .map(|c| &c.binding)
                .or(state.absence_retired.as_ref());
            let completed_transition = selected_run
                && prior.is_some_and(|p| p.operational_run_id != binding.operational_run_id);
            if (state.absence_retired.is_some() && !completed_transition)
                || state.busy
                || state.active.as_ref().is_some_and(|cap| {
                    !cap.retired.load(Ordering::SeqCst)
                        && !(completed_owner && completed_transition)
                })
            {
                return Err("native executor already owned".into());
            }
            if selected_run && prior.is_some() && !completed_transition {
                return Err("completed native run cannot be reinitialized".into());
            }
            let unbound_run = selected_run && prior.is_none() && state.writer.is_none();
            state.busy = true;
            (
                state.writer.clone(),
                state.active.clone(),
                completed_transition,
                unbound_run,
            )
        };
        let mut retained_writer = writer.clone();
        let result = (|| {
            if AppBinding::parse(&source.record()?)? != binding {
                return Err("native claim app binding changed".into());
            }
            let needs_handoff = unbound_run && inspect_unclaimed_counter(directory)?.is_some();
            let writer = match writer {
                Some(writer) => {
                    if !same_inode(
                        &private_metadata(directory, true)?,
                        &writer
                            .directory
                            .metadata()
                            .map_err(|_| "native claim directory stat failed")?,
                    ) || AppBinding::parse(&writer.app_source.record()?)? != binding
                    {
                        return Err("native claim retained writer binding differs".into());
                    }
                    writer
                }
                None => Writer::open(directory, source.clone(), &binding)?,
            };
            let handoff_sha256 = if needs_handoff {
                let raw = source
                    .operational_run_handoff()?
                    .ok_or("native_prior_process_owner_unreconciled")?;
                // Writer::open owns the original Native flock and re-read the
                // permanent record. A stale inspection counter cannot authorize
                // allocation after another writer advanced it.
                writer.observe()?;
                let hash = validate_operational_run_handoff(
                    &raw,
                    &binding,
                    true,
                    writer.counter.load(Ordering::SeqCst),
                )?;
                if AppBinding::parse(&source.record()?)? != binding {
                    return Err("handoff current app channel changed".into());
                }
                writer.observe()?;
                Some(hash)
            } else {
                None
            };
            retained_writer = Some(writer.clone());
            writer.observe()?;
            if completed_transition {
                if binding.generation < writer.app_generation.load(Ordering::SeqCst) {
                    return Err("native app generation regressed".into());
                }
                writer
                    .app_generation
                    .store(binding.generation, Ordering::SeqCst);
            }
            if completed_owner {
                if let Some(cap) = completed_cap {
                    // The live app channel has intentionally moved from the old
                    // completed admission to the new run's pending record. Its
                    // old capability must not be revalidated against new bytes.
                    cap.retired.store(true, Ordering::SeqCst);
                }
            }
            writer.allocate(binding, handoff_sha256)
        })();
        let mut state = self
            .state
            .lock()
            .map_err(|_| "native fence registry poisoned")?;
        state.busy = false;
        if retained_writer.is_some() {
            state.writer = retained_writer;
        }
        if let Ok(cap) = &result {
            state.writer = Some(cap.writer.clone());
            state.active = Some(cap.clone());
            if selected_run {
                state.absence_retired = None;
            }
        }
        result
    }
    pub fn active(&self) -> Result<Arc<NativeCapability>, String> {
        let cap = self
            .state
            .lock()
            .map_err(|_| "native fence registry poisoned")?
            .active
            .clone()
            .ok_or("native process fence not admitted")?;
        cap.require_live()?;
        Ok(cap)
    }
}

#[cfg(test)]
#[path = "process_fence_tests.rs"]
mod tests;

#[cfg(test)]
mod operational_run_tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    struct Source(Mutex<String>);
    impl AppFenceSource for Source {
        fn record(&self) -> Result<String, String> {
            Ok(self.0.lock().unwrap().clone())
        }
    }

    fn directory() -> PathBuf {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "rq-operational-fence-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        ));
        fs::create_dir(&path).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        path
    }

    fn run_record(run: &str, phase: &str) -> String {
        let body = format!(
            "rusty.quest.embedded_duplex.app_process_fence.v2\n1\n01234567-89ab-cdef-0123-456789abcdef\n{phase}\n-\n-\n{run}\n"
        );
        format!("{body}{}\n", digest(&body))
    }

    #[test]
    fn v2_pending_is_an_actual_run_binding_and_clear_cannot_admit() {
        let run = "ab".repeat(16);
        assert_eq!(
            AppBinding::parse(&run_record(&run, "pending"))
                .unwrap()
                .operational_run_id
                .as_deref(),
            Some(run.as_str())
        );
        assert!(AppBinding::parse(&run_record(&run, "clear")).is_err());
        for invalid in ["AB".repeat(16), "a".repeat(31), "../a".into(), "".into()] {
            assert!(AppBinding::parse(&run_record(&invalid, "pending")).is_err());
        }
        let original = run_record(&run, "pending");
        assert!(AppBinding::parse(&original.replace(&run, &"cd".repeat(16))).is_err());
    }

    #[test]
    fn clear_inspection_never_claims_or_fabricates_a_native_run() {
        let path = directory();
        let registry = NativeFenceRegistry::default();
        let (binding, pending) =
            AppBinding::inspect(&run_record(&"ab".repeat(16), "clear")).unwrap();
        assert!(!pending);
        let observation = registry.inspect_operational_run(&path, &binding).unwrap();
        assert!(observation.actual_binding.is_none());
        assert!(observation.native_generation.is_none());
        assert!(observation.refusal.is_none());
        assert!(!path.join(LOCK_NAME).exists());
        assert!(!path.join(WITNESS_NAME).exists());
    }

    #[test]
    fn allocation_persists_exact_owner_before_original_publication() {
        let path = directory();
        let raw = run_record(&"ab".repeat(16), "pending");
        let source = Arc::new(Source(Mutex::new(raw.clone())));
        let registry = NativeFenceRegistry::default();
        let admitted = registry.claim_selected_run(&path, source).unwrap();
        // No Original result or actor publication is supplied by this neutral caller.
        let exact = fs::read_to_string(path.join(LOCK_NAME)).unwrap();
        let body: serde_json::Value = serde_json::from_str(exact.lines().nth(1).unwrap()).unwrap();
        assert_eq!(body["app_record"], raw);
        assert_eq!(body["counter"], admitted.generation);
        assert_eq!(body["process_identity"]["pid"], std::process::id());
        assert!(body["process_identity"]["start_ticks"].as_u64().unwrap() > 0);
        assert_eq!(parse_counter(&exact).unwrap(), admitted.generation);
    }

    fn cold_record(run: &str, generation: u64, state: &str) -> String {
        let b = format!(
            "{APP_RUN_SCHEMA}\n{generation}\n01234567-89ab-cdef-0123-456789abcdef\n{state}\n-\n-\n{run}\n"
        );
        format!("{b}{}\n", digest(&b))
    }
    fn cold_fixture(path: &Path, old: serde_json::Value) -> (String, serde_json::Value) {
        cold_fixture_phase(path, old, "pending")
    }
    fn cold_fixture_phase(
        path: &Path,
        old: serde_json::Value,
        last_phase: &str,
    ) -> (String, serde_json::Value) {
        let a = "a".repeat(32);
        let b = "b".repeat(32);
        let current = cold_record(&b, 7, "pending");
        let last = cold_record(&b, 6, last_phase);
        let clear = cold_record(&b, 6, "clear");
        let raw = format!("{NATIVE_SCHEMA}\n3\n");
        let native = format!("{raw}{}\n", digest(&raw));
        fs::write(path.join(LOCK_NAME), &native).unwrap();
        fs::set_permissions(path.join(LOCK_NAME), fs::Permissions::from_mode(0o600)).unwrap();
        fs::write(path.join(WITNESS_NAME), format!("{NATIVE_SCHEMA}\n")).unwrap();
        fs::set_permissions(path.join(WITNESS_NAME), fs::Permissions::from_mode(0o600)).unwrap();
        let checksum = digest(&format!(
            "{}\n{}\n{}\n{}\n{}{}\nfalse\n-\nprocess_local_resources\n",
            old["boot_id"].as_str().unwrap(),
            old["pid"],
            old["uid"],
            old["start_ticks"],
            clear,
            digest(&last)
        ));
        let companion = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.process_incarnation.v1","original_owner":old,"effect_category":"process_local_resources","original_fence_record":clear,
            "current_fence_sha256":digest(&last),"local_resources_terminated":false,"termination_receipt_sha256":"-","sha256":checksum});
        let link = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.cold_pending_owner_link.v1","recovery_original_id":"d".repeat(32),"run_id":b,
            "app_process_generation":7,"app_record_sha256":digest(&current),"prior_native_generation":3,"native_record_sha256":digest(&native),
            "native_owner":{"counter":3,"app_record":cold_record(&a,5,"pending"),"process_identity":old},"last_writable_record":last,"last_incarnation_json":canonical_handoff_json(&companion).unwrap(),
            "old_process_identity":old,"current_process_identity":current_native_process_identity().unwrap(),"old_process_retirement":"not_running"});
        (current, link)
    }
    fn dead_child_identity() -> serde_json::Value {
        let mut child = std::process::Command::new("sleep")
            .arg("30")
            .spawn()
            .unwrap();
        let pid = child.id();
        let stat = fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
        let end = stat.rfind(')').unwrap();
        let ticks = stat[end + 1..]
            .split_whitespace()
            .nth(19)
            .unwrap()
            .parse::<u64>()
            .unwrap();
        let mut old = current_native_process_identity().unwrap();
        old["pid"] = serde_json::json!(pid);
        old["start_ticks"] = serde_json::json!(ticks);
        child.kill().unwrap();
        child.wait().unwrap();
        old
    }
    #[test]
    fn populated_journal_retirement_binds_native4_and_new_run_allocates5() {
        let path = directory();
        let old = dead_child_identity();
        let (_, mut link) = cold_fixture(&path, old.clone());
        let run = "b".repeat(32);
        let original = cold_record(&run, 10, "clear");
        let allocation = cold_record(&run, 10, "pending");
        let journals = |record: String, checkpoint: &str, evidence: &str| {
            let mut f = record
                .split('\n')
                .take(7)
                .map(str::to_owned)
                .collect::<Vec<_>>();
            f[4] = checkpoint.into();
            f[5] = evidence.into();
            let body = f.join("\n") + "\n";
            format!("{body}{}\n", digest(&body))
        };
        let last = journals(allocation.clone(), &"1".repeat(64), &"2".repeat(64));
        let current = journals(
            cold_record(&run, 11, "pending"),
            &"3".repeat(64),
            &"4".repeat(64),
        );
        let owner = serde_json::json!({"counter":4,"app_record":allocation,"process_identity":old});
        let body = format!(
            "{NATIVE_OWNER_SCHEMA}\n{}\n",
            canonical_handoff_json(&owner).unwrap()
        );
        let native_record = format!("{body}{}\n", digest(&body));
        fs::write(path.join(LOCK_NAME), &native_record).unwrap();
        let mut companion: serde_json::Value =
            serde_json::from_str(link["last_incarnation_json"].as_str().unwrap()).unwrap();
        companion["original_fence_record"] = serde_json::json!(original);
        companion["current_fence_sha256"] = serde_json::json!(digest(&last));
        companion["sha256"] = serde_json::json!(digest(&format!(
            "{}\n{}\n{}\n{}\n{}{}\nfalse\n-\nprocess_local_resources\n",
            old["boot_id"].as_str().unwrap(),
            old["pid"],
            old["uid"],
            old["start_ticks"],
            original,
            digest(&last)
        )));
        link["$schema"] =
            serde_json::json!("rusty.quest.embedded_duplex.cold_pending_owner_link.v2");
        link["native_owner"] = owner;
        link["native_record_sha256"] = serde_json::json!(digest(&native_record));
        link["prior_native_generation"] = serde_json::json!(4);
        link["app_process_generation"] = serde_json::json!(11);
        link["app_record_sha256"] = serde_json::json!(digest(&current));
        link["last_writable_record"] = serde_json::json!(last);
        link["last_incarnation_json"] =
            serde_json::json!(canonical_handoff_json(&companion).unwrap());
        link["journal_snapshot"] = serde_json::json!({"checkpoint_sha256":"3".repeat(64),"evidence_sha256":"4".repeat(64),"prior_checkpoint_sha256":"1".repeat(64),"prior_evidence_sha256":"2".repeat(64)});
        let binding = AppBinding::parse(&current).unwrap();
        let mut changed = link.clone();
        changed["journal_snapshot"]["evidence_sha256"] = serde_json::json!("0".repeat(64));
        assert!(validate_cold_link(
            &canonical_handoff_json(&changed).unwrap(),
            &binding,
            &native_record,
            4
        )
        .is_err());
        let registry = NativeFenceRegistry::default();
        let raw = registry
            .retire_cold_pending_local(&path, &current, &canonical_handoff_json(&link).unwrap())
            .unwrap();
        let native: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(
            native["$schema"],
            "rusty.quest.embedded_duplex.cold_pending_native_retirement.v2"
        );
        assert_eq!(cold_pending_owner(&path).unwrap()["native_generation"], 4);
        assert_eq!(link["last_writable_record"], last); // old outcome/journal custody is untouched
        let physical = serde_json::json!({"java_own_capture_cleanup":"terminal","java_peer_resources_cleanup":"terminal","java_renderer_cleanup":"terminal","java_control_cleanup":"terminal","java_callback_cleanup":"terminal","command_lane_barrier":"terminal","whole_app_physical_cleanup":"terminal","app_process_fence_cleanup":"terminal"});
        let receipt = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.cold_pending_local_retirement.v2","recovery_original_id":"d".repeat(32),"run_id":run,"prior_run_id":null,"prior_original_id":null,"app_process_generation":11,"app_record_sha256":binding.record_sha256,"app_clear_record_sha256":"5".repeat(64),"prior_native_generation":4,"old_process_identity":old,"current_process_identity":current_native_process_identity().unwrap(),"old_process_retirement":"not_running","owner_link_sha256":native["owner_link_sha256"],"native_retirement":native,"physical_retirement":physical,"transaction_outcome":"unchanged","remote_outcome_resolved":false});
        let next = cold_record(&"c".repeat(32), 12, "pending");
        let next_binding = AppBinding::parse(&next).unwrap();
        let handoff = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.operational_run_handoff.v1","operational_run_id":"c".repeat(32),"app_record_sha256":next_binding.record_sha256,"prior_native_generation":4,"whole_original_id":"d".repeat(32),"request_sha256":"1".repeat(64),"outcome_sha256":"2".repeat(64),"physical_retirement_sha256":digest(&canonical_handoff_json(&receipt).unwrap()),"physical_retirement":receipt});
        drop(registry);
        let restarted = NativeFenceRegistry::default();
        let capability = restarted
            .claim_selected_run(
                &path,
                Arc::new(HandoffSource {
                    record: Mutex::new(next),
                    handoff: Mutex::new(Some(handoff.to_string())),
                }),
            )
            .unwrap();
        assert_eq!(capability.generation, 5);
        assert!(capability.require_live().is_ok());
    }
    #[test]
    fn cold_retirement_preserves_counter_and_ordinary_followup_allocates_next() {
        let path = directory();
        let (record, link) = cold_fixture(&path, dead_child_identity());
        let registry = NativeFenceRegistry::default();
        let exact = canonical_handoff_json(&link).unwrap();
        let reply = registry
            .retire_cold_pending_local(&path, &record, &exact)
            .unwrap();
        let native: serde_json::Value = serde_json::from_str(&reply).unwrap();
        assert_eq!(native["prior_native_generation"], 3);
        assert_eq!(
            registry
                .retire_cold_pending_local(&path, &record, &exact)
                .unwrap(),
            reply
        );
        let owner = cold_pending_owner(&path).unwrap();
        assert_eq!(owner["native_generation"], 3);
        assert_eq!(owner["owner"]["retirement"]["receipt"], native);
        let c = "c".repeat(32);
        let next = cold_record(&c, 8, "pending");
        let mut physical = serde_json::Map::new();
        for key in [
            "java_own_capture_cleanup",
            "java_peer_resources_cleanup",
            "java_renderer_cleanup",
            "java_control_cleanup",
            "java_callback_cleanup",
            "command_lane_barrier",
            "whole_app_physical_cleanup",
            "app_process_fence_cleanup",
        ] {
            physical.insert(key.into(), serde_json::json!("terminal"));
        }
        let receipt = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.cold_pending_local_retirement.v1","recovery_original_id":"d".repeat(32),"run_id":"b".repeat(32),"prior_run_id":"a".repeat(32),"prior_original_id":"e".repeat(32),
            "app_process_generation":7,"app_record_sha256":digest(&record),"app_clear_record_sha256":digest(&cold_record(&"b".repeat(32),7,"clear")),"prior_native_generation":3,
            "old_process_identity":link["old_process_identity"],"current_process_identity":link["current_process_identity"],"old_process_retirement":"not_running","owner_link_sha256":digest(&exact),
            "native_retirement":native,"physical_retirement":physical,"transaction_outcome":"unchanged","remote_outcome_resolved":false});
        let (binding, _) = AppBinding::inspect(&next).unwrap();
        let handoff = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.operational_run_handoff.v1","operational_run_id":c,"app_record_sha256":binding.record_sha256,"prior_native_generation":3,
            "whole_original_id":"d".repeat(32),"request_sha256":"1".repeat(64),"outcome_sha256":"2".repeat(64),"physical_retirement_sha256":digest(&canonical_handoff_json(&receipt).unwrap()),"physical_retirement":receipt});
        let raw = handoff.to_string();
        assert!(validate_operational_run_handoff(&raw, &binding, true, 3).is_ok());
        assert!(validate_operational_run_handoff(&raw, &binding, true, 4).is_err());
        // A restart loses the in-memory absence slot but not its exact correct-N source proof.
        drop(registry);
        let restarted = NativeFenceRegistry::default();
        let source = Arc::new(HandoffSource {
            record: Mutex::new(next),
            handoff: Mutex::new(Some(raw)),
        });
        let cap = restarted.claim_selected_run(&path, source).unwrap();
        assert_eq!(cap.generation, 4);
        assert!(cap.require_live().is_ok());
        assert_eq!(
            parse_counter(&fs::read_to_string(path.join(LOCK_NAME)).unwrap()).unwrap(),
            4
        );
    }
    #[test]
    fn clear_before_old_publication_establishes_new_local_retirement_without_old_success() {
        let path = directory();
        let (record, link) = cold_fixture_phase(&path, dead_child_identity(), "clear");
        let receipt = NativeFenceRegistry::default()
            .retire_cold_pending_local(&path, &record, &canonical_handoff_json(&link).unwrap())
            .unwrap();
        let value: serde_json::Value = serde_json::from_str(&receipt).unwrap();
        assert_eq!(value["prior_native_generation"], 3);
        assert_eq!(value["transaction_outcome"], "unchanged");
        assert_eq!(value["remote_outcome_resolved"], false);
        assert_eq!(cold_pending_owner(&path).unwrap()["owner"]["counter"], 3);
    }
    #[test]
    fn cold_retirement_rejects_live_owner_wrong_counter_companion_and_advanced_counter() {
        for damage in [
            "live",
            "counter",
            "boot",
            "birth",
            "companion",
            "missing-link",
            "advance",
        ] {
            let path = directory();
            let old = if damage == "live" {
                current_native_process_identity().unwrap()
            } else {
                dead_child_identity()
            };
            let (record, mut link) = cold_fixture(&path, old);
            match damage {
                "counter" => link["prior_native_generation"] = serde_json::json!(4),
                "boot" => {
                    link["current_process_identity"]["boot_id"] =
                        serde_json::json!("00000000-0000-0000-0000-000000000000")
                }
                "birth" => link["current_process_identity"]["start_ticks"] = serde_json::json!(1),
                "companion" => link["last_incarnation_json"] = serde_json::json!("{}"),
                "missing-link" => {
                    link.as_object_mut().unwrap().remove("last_writable_record");
                }
                "advance" => {
                    let b = format!("{NATIVE_SCHEMA}\n4\n");
                    fs::write(path.join(LOCK_NAME), format!("{b}{}\n", digest(&b))).unwrap();
                }
                _ => {}
            }
            let before = fs::read(path.join(LOCK_NAME)).unwrap();
            assert!(
                NativeFenceRegistry::default()
                    .retire_cold_pending_local(
                        &path,
                        &record,
                        &canonical_handoff_json(&link).unwrap()
                    )
                    .is_err(),
                "{damage}"
            );
            assert_eq!(
                before,
                fs::read(path.join(LOCK_NAME)).unwrap(),
                "{damage} changed counter/owner bytes"
            );
        }
    }

    #[test]
    fn new_run_preserves_writer_inode_and_counter_but_never_rebinds_old_callback() {
        let path = directory();
        let a = "ab".repeat(16);
        let b = "cd".repeat(16);
        let source = Arc::new(Source(Mutex::new(run_record(&a, "pending"))));
        let registry = NativeFenceRegistry::default();
        let first = registry.claim_selected_run(&path, source.clone()).unwrap();
        let inode = fs::metadata(path.join(LOCK_NAME)).unwrap().ino();
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        first.retire().unwrap();
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        *source.0.lock().unwrap() = run_record(&b, "clear");
        let (binding, _) = AppBinding::inspect(&source.record().unwrap()).unwrap();
        let observed = registry.inspect_operational_run(&path, &binding).unwrap();
        assert_eq!(
            observed
                .actual_binding
                .unwrap()
                .operational_run_id
                .as_deref(),
            Some(a.as_str())
        );
        assert_eq!(observed.native_generation, Some(first.generation));
        assert!(observed.refusal.is_none());
        *source.0.lock().unwrap() = run_record(&b, "pending");
        let second = registry.claim_selected_run(&path, source.clone()).unwrap();
        assert_eq!(
            second.binding.operational_run_id.as_deref(),
            Some(b.as_str())
        );
        assert_eq!(second.generation, first.generation + 1);
        assert_eq!(fs::metadata(path.join(LOCK_NAME)).unwrap().ino(), inode);
        assert!(first.require_live().is_err());
        second.require_live().unwrap();
        assert!(registry.claim_selected_run(&directory(), source).is_err());
    }

    #[test]
    fn actual_busy_or_poisoned_registry_cannot_be_hidden_by_another_run_id() {
        let path = directory();
        let source = Arc::new(Source(Mutex::new(run_record(&"ab".repeat(16), "pending"))));
        let registry = NativeFenceRegistry::default();
        let cap = registry.claim_selected_run(&path, source.clone()).unwrap();
        let (binding, _) = AppBinding::inspect(&source.record().unwrap()).unwrap();
        assert_eq!(
            registry
                .inspect_operational_run(&path, &binding)
                .unwrap()
                .refusal,
            Some("native_capability_owned")
        );
        cap.retire().unwrap();
        fs::write(path.join(LOCK_NAME), "corrupt").unwrap();
        assert!(registry.inspect_operational_run(&path, &binding).is_err());
        *source.0.lock().unwrap() = run_record(&"cd".repeat(16), "pending");
        assert!(registry.claim_selected_run(&path, source).is_err());
    }

    #[test]
    fn completed_absence_can_move_only_to_an_explicit_different_run() {
        let path = directory();
        let a = "ab".repeat(16);
        let source = Arc::new(Source(Mutex::new(run_record(&a, "pending"))));
        let registry = NativeFenceRegistry::default();
        registry.retire_never_claimed(source.clone()).unwrap();
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        *source.0.lock().unwrap() = run_record(&"cd".repeat(16), "pending");
        let actual = registry.claim_selected_run(&path, source).unwrap();
        assert_eq!(actual.generation, 1);
    }

    #[test]
    fn actual_completed_owner_transition_advances_app_generation_without_replacing_inode() {
        let path = directory();
        let a = "ab".repeat(16);
        let b = "cd".repeat(16);
        let source = Arc::new(Source(Mutex::new(run_record(&a, "pending"))));
        let registry = NativeFenceRegistry::default();
        let old = registry.claim_selected_run(&path, source.clone()).unwrap();
        let inode = fs::metadata(path.join(LOCK_NAME)).unwrap().ino();
        // Model only the Java owner's checked rebind, using the same held source.
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{b}\n"
        );
        *source.0.lock().unwrap() = format!("{body}{}\n", digest(&body));
        assert!(registry.claim_selected_run(&path, source.clone()).is_err());
        let next = registry
            .claim_selected_run_after_completed_close(&path, source)
            .unwrap();
        assert_eq!(next.binding.generation, 2);
        assert_eq!(next.binding.operational_run_id.as_deref(), Some(b.as_str()));
        assert_eq!(next.generation, old.generation + 1);
        assert_eq!(fs::metadata(path.join(LOCK_NAME)).unwrap().ino(), inode);
        assert!(old.require_live().is_err());
        next.require_live().unwrap();
    }

    #[test]
    fn restarted_v2_counter_without_actual_owner_binding_refuses_inspection_and_claim() {
        let path = directory();
        let source = Arc::new(Source(Mutex::new(run_record(&"ab".repeat(16), "pending"))));
        {
            let registry = NativeFenceRegistry::default();
            let old = registry.claim_selected_run(&path, source.clone()).unwrap();
            old.retire().unwrap();
        }
        let original = fs::read(path.join(LOCK_NAME)).unwrap();
        let restarted = NativeFenceRegistry::default();
        let (binding, _) = AppBinding::inspect(&source.record().unwrap()).unwrap();
        assert_eq!(
            restarted
                .inspect_operational_run(&path, &binding)
                .unwrap()
                .refusal,
            Some("native_prior_process_owner_unreconciled")
        );
        assert!(restarted.claim_selected_run(&path, source.clone()).is_err());
        assert!(restarted
            .claim_selected_run_after_completed_close(&path, source)
            .is_err());
        assert_eq!(fs::read(path.join(LOCK_NAME)).unwrap(), original);
    }

    struct HandoffSource {
        record: Mutex<String>,
        handoff: Mutex<Option<String>>,
    }
    impl AppFenceSource for HandoffSource {
        fn record(&self) -> Result<String, String> {
            Ok(self.record.lock().unwrap().clone())
        }
        fn operational_run_handoff(&self) -> Result<Option<String>, String> {
            Ok(self.handoff.lock().unwrap().clone())
        }
    }

    fn handoff(binding: &AppBinding, counter: u64) -> serde_json::Value {
        let proof = serde_json::json!({"$schema":"rusty.quest.embedded_duplex.remote_owner_cleanup.v1",
            "action":"whole_app_close","config_sha256":"a".repeat(64),"app_process_generation":1,
            "native_executor_generation":counter,"peer_physical_cleanup":"not_locally_started",
            "native_host_physical_cleanup":"terminal","java_own_capture_cleanup":"terminal",
            "java_renderer_cleanup":"terminal","java_control_cleanup":"terminal",
            "app_process_fence_cleanup":"terminal","whole_app_physical_cleanup":"terminal","status":"terminal"});
        serde_json::json!({"$schema":"rusty.quest.embedded_duplex.operational_run_handoff.v1",
            "operational_run_id":binding.operational_run_id,"app_record_sha256":binding.record_sha256,
            "prior_native_generation":counter,"whole_original_id":"1".repeat(32),
            "request_sha256":"2".repeat(64),"outcome_sha256":"3".repeat(64),
            "physical_retirement_sha256":digest(&canonical_handoff_json(&proof).unwrap()),"physical_retirement":proof})
    }

    #[test]
    fn source_whole_handoff_restarts_once_and_exact_retry_preserves_actual_capability() {
        let path = directory();
        let old_body =
            format!("{APP_SCHEMA}\n1\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n");
        let legacy = Arc::new(Source(Mutex::new(format!(
            "{old_body}{}\n",
            digest(&old_body)
        ))));
        {
            let registry = NativeFenceRegistry::default();
            registry.claim(&path, legacy).unwrap().retire().unwrap();
        }
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
            "ab".repeat(16)
        );
        let record = format!("{body}{}\n", digest(&body));
        let binding = AppBinding::parse(&record).unwrap();
        let witness = handoff(&binding, 1).to_string();
        let source = Arc::new(HandoffSource {
            record: Mutex::new(record),
            handoff: Mutex::new(Some(witness.clone())),
        });
        let registry = NativeFenceRegistry::default();
        let cap = registry.claim_selected_run(&path, source.clone()).unwrap();
        assert_eq!(cap.generation, 2);
        assert_eq!(cap.binding, binding);
        let retry = registry
            .retry_selected_run(source.clone())
            .unwrap()
            .unwrap();
        assert!(Arc::ptr_eq(&cap, &retry));
        assert_eq!(retry.generation, 2);
        assert_eq!(
            registry
                .inspect_operational_run(&path, &binding)
                .unwrap()
                .native_generation,
            Some(2)
        );
        *source.handoff.lock().unwrap() = Some(witness.replace(&"2".repeat(64), &"4".repeat(64)));
        assert!(registry.retry_selected_run(source).is_err());
    }

    #[test]
    fn handoff_refuses_wrong_subjects_terminal_fields_and_counter_advance() {
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
            "ab".repeat(16)
        );
        let binding = AppBinding::parse(&format!("{body}{}\n", digest(&body))).unwrap();
        let valid = handoff(&binding, 1);
        assert!(validate_operational_run_handoff(&valid.to_string(), &binding, true, 1).is_ok());
        for (key, bad) in [
            ("operational_run_id", serde_json::json!("cd".repeat(16))),
            ("app_record_sha256", serde_json::json!("f".repeat(64))),
            ("prior_native_generation", serde_json::json!(2)),
            (
                "physical_retirement_sha256",
                serde_json::json!("0".repeat(64)),
            ),
        ] {
            let mut wrong = valid.clone();
            wrong[key] = bad;
            assert!(
                validate_operational_run_handoff(&wrong.to_string(), &binding, true, 1).is_err(),
                "{key}"
            );
        }
        let mut wrong = valid.clone();
        wrong["physical_retirement"]["java_renderer_cleanup"] = serde_json::json!("pending");
        wrong["physical_retirement_sha256"] = serde_json::json!(digest(
            &canonical_handoff_json(&wrong["physical_retirement"]).unwrap()
        ));
        assert!(validate_operational_run_handoff(&wrong.to_string(), &binding, true, 1).is_err());
        assert!(validate_operational_run_handoff(&valid.to_string(), &binding, true, 2).is_err());
        let mut other_target = valid.clone();
        other_target["operational_run_id"] = serde_json::json!("cd".repeat(16));
        assert!(
            validate_operational_run_handoff(&other_target.to_string(), &binding, false, 1).is_ok()
        );
        let mut current_app = valid.clone();
        current_app["physical_retirement"]["app_process_generation"] = serde_json::json!(2);
        current_app["physical_retirement_sha256"] = serde_json::json!(digest(
            &canonical_handoff_json(&current_app["physical_retirement"]).unwrap()
        ));
        assert!(
            validate_operational_run_handoff(&current_app.to_string(), &binding, false, 1).is_ok()
        );
        assert!(
            validate_operational_run_handoff(&current_app.to_string(), &binding, true, 1).is_err()
        );
    }

    #[test]
    fn restarted_v2_owner_accepts_only_its_current_exact_source_release() {
        let path = directory();
        let old = Arc::new(Source(Mutex::new(run_record(&"cd".repeat(16), "pending"))));
        {
            let registry = NativeFenceRegistry::default();
            registry
                .claim_selected_run(&path, old)
                .unwrap()
                .retire()
                .unwrap();
        }
        let body = format!(
            "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
            "ab".repeat(16)
        );
        let record = format!("{body}{}\n", digest(&body));
        let binding = AppBinding::parse(&record).unwrap();
        let source = Arc::new(HandoffSource {
            record: Mutex::new(record),
            handoff: Mutex::new(Some(handoff(&binding, 1).to_string())),
        });
        let registry = NativeFenceRegistry::default();
        assert_eq!(
            registry
                .claim_selected_run(&path, source)
                .unwrap()
                .generation,
            2
        );
    }

    #[test]
    fn invalid_source_handoff_never_advances_permanent_counter_or_retains_a_writer() {
        for subject in ["run", "channel", "counter", "witness", "advanced"] {
            let path = directory();
            let old_body =
                format!("{APP_SCHEMA}\n1\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n");
            {
                let old = Arc::new(Source(Mutex::new(format!(
                    "{old_body}{}\n",
                    digest(&old_body)
                ))));
                let registry = NativeFenceRegistry::default();
                registry.claim(&path, old).unwrap().retire().unwrap();
            }
            let body = format!(
                "{APP_RUN_SCHEMA}\n2\n01234567-89ab-cdef-0123-456789abcdef\npending\n-\n-\n{}\n",
                "ab".repeat(16)
            );
            let record = format!("{body}{}\n", digest(&body));
            let binding = AppBinding::parse(&record).unwrap();
            let mut witness = handoff(&binding, 1);
            match subject {
                "run" => witness["operational_run_id"] = serde_json::json!("cd".repeat(16)),
                "channel" => witness["app_record_sha256"] = serde_json::json!("f".repeat(64)),
                "counter" => witness["prior_native_generation"] = serde_json::json!(2),
                "witness" => {
                    witness["physical_retirement_sha256"] = serde_json::json!("0".repeat(64))
                }
                _ => {
                    let newer = format!("{NATIVE_SCHEMA}\n2\n");
                    fs::write(path.join(LOCK_NAME), format!("{newer}{}\n", digest(&newer)))
                        .unwrap();
                }
            }
            let bytes = fs::read(path.join(LOCK_NAME)).unwrap();
            let source = Arc::new(HandoffSource {
                record: Mutex::new(record),
                handoff: Mutex::new(Some(witness.to_string())),
            });
            let registry = NativeFenceRegistry::default();
            assert!(
                registry.claim_selected_run(&path, source.clone()).is_err(),
                "{subject}"
            );
            assert_eq!(fs::read(path.join(LOCK_NAME)).unwrap(), bytes);
            assert!(registry.state.lock().unwrap().writer.is_none());
            if subject != "advanced" {
                *source.handoff.lock().unwrap() = Some(handoff(&binding, 1).to_string());
                assert_eq!(
                    registry
                        .claim_selected_run(&path, source)
                        .unwrap()
                        .generation,
                    2
                );
            }
        }
    }
}

#[cfg(test)]
#[path = "process_fence_handoff_tests.rs"]
mod handoff_tests;
