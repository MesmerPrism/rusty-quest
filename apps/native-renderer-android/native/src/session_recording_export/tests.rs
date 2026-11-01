use super::*;
use crate::session_recording_contract::*;
use serde_json::{json, Value};
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
const ID: &str = "utc-ns-01725000000000000000";
static NEXT: AtomicU64 = AtomicU64::new(0);
fn fixture(completed: bool) -> PathBuf {
    let root = std::env::temp_dir()
        .join(format!(
            "recording-export-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ))
        .join("viscereality-recordings");
    let d = root.join(ID);
    fs::create_dir_all(&d).unwrap();
    let row = |n, event| {
        format!(
            "{}\n",
            json!({"schema":SESSION_ROW_SCHEMA,"record_sequence":n,"kind":"event","session_started_at_utc":ID,"condition":"condition-a","observed_monotonic_ns":n+1,"observed_utc_ns":n+2,"event":event})
        )
    };
    let mut events = row(0, "started");
    if completed {
        events += &row(1, "completion-reached");
    }
    fs::write(
        d.join("manifest.json"),
        json!({"schema":SESSION_MANIFEST_SCHEMA,"started_at_utc":ID,"condition":"condition-a"})
            .to_string(),
    )
    .unwrap();
    let mut lengths = serde_json::Map::new();
    for name in SESSION_STREAM_FILES {
        let data = if name == "events.jsonl" {
            events.as_bytes()
        } else {
            b""
        };
        fs::write(d.join(name), data).unwrap();
        lengths.insert(name.into(), json!(data.len()));
    }
    fs::write(d.join("final.json"),json!({"schema":SESSION_FINAL_SCHEMA,"started_at_utc":ID,"condition":"condition-a","completed":completed,"saved":true,"durable_stream_lengths":lengths,"loss":{"dropped_records":0,"streams":[],"first_offer_sequence":null,"last_offer_sequence":null,"first_observed_monotonic_ns":null,"last_observed_monotonic_ns":null},"dropped_records":0}).to_string()).unwrap();
    root
}
#[test]
fn saved_completed_and_early_export_exact_core8() {
    for done in [true, false] {
        let r = fixture(done);
        let m = prepare(&r, ID, &|| true).unwrap().manifest;
        assert_eq!(m["files"].as_array().unwrap().len(), 8);
        assert_eq!(
            m["disposition"],
            if done {
                "finalized-completed"
            } else {
                "finalized-early"
            }
        );
        assert_eq!(
            m["file_set_policy"],
            "core8-original-no-checkpoints-or-index"
        );
        fs::remove_dir_all(r.parent().unwrap()).unwrap();
    }
}
#[test]
fn traversal_active_interrupted_unsaved_loss_and_tail_are_rejected() {
    let r = fixture(true);
    assert!(prepare(&r, "../x", &|| true).is_err());
    assert!(prepare(&r, ID, &|| false).is_err());
    for damage in ["unsaved", "loss", "tail", "interrupted", "corrupt"] {
        let r = fixture(true);
        let d = r.join(ID);
        let f = d.join("final.json");
        let mut v: Value = serde_json::from_slice(&fs::read(&f).unwrap()).unwrap();
        match damage {
            "unsaved" => v["saved"] = json!(false),
            "loss" => {
                v["dropped_records"] = json!(1);
                v["loss"]["dropped_records"] = json!(1);
            }
            "tail" => {
                use std::io::Write;
                fs::OpenOptions::new()
                    .append(true)
                    .open(d.join("events.jsonl"))
                    .unwrap()
                    .write_all(b"tail")
                    .unwrap();
            }
            "corrupt" => v["schema"] = json!("wrong"),
            _ => {
                fs::remove_file(&f).unwrap();
            }
        }
        if damage != "interrupted" {
            fs::write(f, v.to_string()).unwrap();
        }
        assert!(prepare(&r, ID, &|| true).is_err(), "{damage}");
        fs::remove_dir_all(r.parent().unwrap()).unwrap();
    }
    fs::remove_dir_all(r.parent().unwrap()).unwrap();
}

#[test]
fn chunk_range_identity_mutation_and_owner_are_fenced() {
    let root = fixture(true);
    let p = prepare(&root, ID, &|| true).unwrap();
    let m = p.manifest["manifest_id"].as_str().unwrap();
    let size = p.manifest["files"][0]["size"].as_u64().unwrap();
    assert_eq!(
        p.read(m, "manifest.json", 0, 1, &|| true).unwrap()["data"],
        "ew=="
    );
    for (name, offset, length) in [
        ("../manifest.json", 0, 1),
        ("manifest.json", 0, 32769),
        ("manifest.json", size, 1),
        ("manifest.json", 0, 0),
    ] {
        assert!(p.read(m, name, offset, length, &|| true).is_err());
    }
    assert!(p.read(m, "manifest.json", 0, 1, &|| false).is_err());
    assert!(p
        .read(&"0".repeat(64), "manifest.json", 0, 1, &|| true)
        .is_err());
    fs::write(root.join(ID).join("manifest.json"), b"changed").unwrap();
    assert!(p.read(m, "events.jsonl", 0, 1, &|| true).is_err());
    drop(p);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
#[test]
fn replacement_and_hardlinks_are_rejected() {
    let root = fixture(true);
    let p = prepare(&root, ID, &|| true).unwrap();
    let path = root.join(ID).join("manifest.json");
    let bytes = fs::read(&path).unwrap();
    fs::rename(&path, path.with_extension("old")).unwrap();
    fs::write(&path, bytes).unwrap();
    assert!(p.unchanged().is_err());
    fs::remove_file(&path).unwrap();
    fs::hard_link(path.with_extension("old"), &path).unwrap();
    assert!(prepare(&root, ID, &|| true).is_err());
    drop(p);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
#[test]
fn bounded_query_parser_does_not_coerce_or_accept_paths() {
    for query in [
        json!({"query":"list","before":"","limit":17}),
        json!({"query":"list","before":"","limit":1.0}),
        json!({"query":"describe","id":"../x"}),
        json!({"query":"describe","id":ID,"extra":true}),
        json!({"query":"read","manifest_id":"0".repeat(64),"file":"events.jsonl","offset":0,"length":32769}),
    ] {
        assert!(parse(&query).is_err());
    }
    assert!(parse(&json!({"query":"list","before":"","limit":16})).is_ok());
    assert_eq!(base64(&[0, 1, 2, 253, 254, 255]), "AAEC/f7/");
}
#[test]
fn preparation_is_independent_single_job_and_retirement_never_publishes() {
    use std::sync::atomic::AtomicBool;
    use std::time::{Duration, Instant};
    let root = fixture(true);
    let service = Service::new().unwrap();
    let entered = Arc::new(AtomicBool::new(false));
    let release = Arc::new(AtomicBool::new(false));
    let e = entered.clone();
    let r = release.clone();
    let count = Arc::new(AtomicU64::new(0));
    let c = count.clone();
    let guard: Check = Arc::new(move || {
        let n = c.fetch_add(1, Ordering::Relaxed);
        if n > 1 && std::thread::current().name() == Some("recording-export-prepare") {
            e.store(true, Ordering::Release);
            while !r.load(Ordering::Acquire) {
                std::thread::yield_now();
            }
        }
        true
    });
    let q = json!({"query":"describe","id":ID});
    assert_eq!(
        service.query(&root, &q, "owner-a".into(), guard.clone())["state"],
        "preparing"
    );
    let deadline = Instant::now() + Duration::from_secs(2);
    while !entered.load(Ordering::Acquire) && Instant::now() < deadline {
        thread::yield_now();
    }
    assert!(entered.load(Ordering::Acquire));
    assert_eq!(
        service.query(&root, &q, "owner-a".into(), Arc::new(|| true))["state"],
        "preparing"
    );
    assert_eq!(
        service.query(
            &root,
            &json!({"query":"list","before":"","limit":1}),
            "owner-a".into(),
            Arc::new(|| true)
        )["state"],
        "busy"
    );
    service.retire();
    release.store(true, Ordering::Release);
    while service.state.lock().unwrap().busy && Instant::now() < deadline {
        thread::yield_now();
    }
    assert!(service.state.lock().unwrap().result.is_none());
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
#[test]
fn listing_reports_unexportable_without_claiming_completion_and_pages_newest() {
    let root = fixture(true);
    let newer = "utc-ns-01725000000000000001";
    fs::create_dir(root.join(newer)).unwrap();
    let p = list(&root, "", 1, &|| true).unwrap();
    assert_eq!(p["entries"][0]["id"], newer);
    assert_eq!(p["entries"][0]["exportable"], false);
    assert_eq!(p["entries"][0]["disposition"], "not-finalized");
    assert_eq!(p["next_before"], newer);
    assert_eq!(
        list(&root, newer, 1, &|| true).unwrap()["entries"][0]["id"],
        ID
    );
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

#[test]
fn same_size_mutation_with_restored_modified_time_is_rejected() {
    let root = fixture(true);
    let p = prepare(&root, ID, &|| true).unwrap();
    let path = root.join(ID).join("manifest.json");
    let modified = fs::metadata(&path).unwrap().modified().unwrap();
    let mut bytes = fs::read(&path).unwrap();
    bytes[0] = b'!';
    fs::write(&path, bytes).unwrap();
    File::options()
        .write(true)
        .open(&path)
        .unwrap()
        .set_modified(modified)
        .unwrap();
    assert!(p.unchanged().is_err());
    drop(p);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

#[test]
fn retirement_while_cache_lock_is_busy_cannot_reuse_prepared_files() {
    let root = fixture(true);
    let service = Service::new().unwrap();
    let query = json!({"query":"describe", "id":ID});
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
    let manifest = loop {
        let response = service.query(&root, &query, "same-owner".into(), Arc::new(|| true));
        if response["state"] == "ready" {
            break response["manifest"].clone();
        }
        assert!(std::time::Instant::now() < deadline);
        thread::yield_now();
    };
    let state = service.state.lock().unwrap();
    service.retire();
    drop(state);
    let response = service.query(&root, &json!({"query":"read", "manifest_id":manifest["manifest_id"], "file":"manifest.json", "offset":0, "length":1}), "same-owner".into(), Arc::new(||true));
    assert_eq!(response["state"], "unavailable");
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}

#[test]
fn pinned_bad_stream_cannot_borrow_eligibility_from_a_swapped_valid_directory() {
    let root = fixture(true);
    let alternate = fixture(true);
    let path = root.join(ID).join("events.jsonl");
    let invalid = String::from_utf8(fs::read(&path).unwrap())
        .unwrap()
        .replace("started", "stArted");
    fs::write(&path, invalid).unwrap();
    let mut pins: Vec<_> = CORE
        .into_iter()
        .map(|name| {
            let path = root.join(ID).join(name);
            let (file, stamp) = open_checked(&path, true).unwrap();
            Pinned {
                name,
                file,
                stamp,
                digest: String::new(),
            }
        })
        .collect();
    // Model namespace resolution selecting an alternate directory while the
    // original eight handles remain pinned (Windows forbids renaming parents
    // with these open child handles; Android/Unix need not).
    let result = validate_pinned_core(&mut pins, SessionTimestampKey::parse(ID).unwrap(), &|| true);
    drop(pins);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
    fs::remove_dir_all(alternate.parent().unwrap()).unwrap();
    assert!(
        result.is_err(),
        "invalid pinned streams acquired alternate path's finalized eligibility"
    );
}
#[test]
fn actual_android_open_selection_requires_nofollow_directory_and_nonblocking_flags() {
    assert_ne!(
        unix_open_flags(ExportPlatform::Android, true) & 0x20000,
        0,
        "Android must enable O_NOFOLLOW"
    );
    assert_ne!(
        unix_open_flags(ExportPlatform::Android, false) & 0x10000,
        0,
        "directory descriptors require O_DIRECTORY"
    );
    assert_ne!(
        unix_open_flags(ExportPlatform::Android, true) & 0x800,
        0,
        "a FIFO replacement must not block open"
    );
}

fn pins(root: &Path) -> Vec<Pinned> {
    CORE.into_iter()
        .map(|name| {
            let path = root.join(ID).join(name);
            let (file, stamp) = open_checked(&path, true).unwrap();
            Pinned {
                name,
                file,
                stamp,
                digest: String::new(),
            }
        })
        .collect()
}
struct Audit<'a> {
    inner: PinnedFiles<'a>,
    opened: Vec<String>,
    oversized: Option<(&'static str, u64)>,
}
impl RecordingFiles for Audit<'_> {
    fn stream_names(&self) -> Result<BTreeSet<String>, String> {
        self.inner.stream_names()
    }
    fn len(&self, name: &str) -> Result<u64, String> {
        assert!(
            !name.starts_with("checkpoint-"),
            "unowned checkpoint metadata accessed"
        );
        if let Some((target, size)) = self.oversized {
            if name == target {
                return Ok(size);
            }
        }
        self.inner.len(name)
    }
    fn reader(&mut self, name: &str) -> Result<Box<dyn Read + '_>, String> {
        assert!(
            !name.starts_with("checkpoint-"),
            "unowned checkpoint opened"
        );
        self.opened.push(name.into());
        self.inner.reader(name)
    }
}
#[test]
fn malformed_saved_final_never_reads_streams_or_linked_oversized_checkpoints() {
    let root = fixture(true);
    let directory = root.join(ID);
    let mut final_value: Value =
        serde_json::from_slice(&fs::read(directory.join("final.json")).unwrap()).unwrap();
    final_value["schema"] = json!("malformed-but-saved");
    fs::write(directory.join("final.json"), final_value.to_string()).unwrap();
    let poison = root.parent().unwrap().join("outside-core.json");
    fs::write(&poison, b"unowned checkpoint").unwrap();
    fs::hard_link(
        &poison,
        directory.join("checkpoint-00000000000000000001.json"),
    )
    .unwrap();
    #[cfg(unix)]
    std::os::unix::fs::symlink(
        &poison,
        directory.join("checkpoint-00000000000000000002.json"),
    )
    .unwrap();
    File::create(directory.join("checkpoint-00000000000000000003.json"))
        .unwrap()
        .set_len(2 * 1024 * 1024)
        .unwrap();
    let mut files = pins(&root);
    let mut audit = Audit {
        inner: PinnedFiles {
            files: &mut files,
            check: &|| true,
        },
        opened: Vec::new(),
        oversized: None,
    };
    let result = validate_finalized_core(
        &mut audit,
        SessionTimestampKey::parse(ID).unwrap(),
        1024 * 1024,
        MAX_EXPORT_BYTES,
    );
    assert_eq!(result.unwrap_err(), "recording-terminal-schema-invalid");
    assert_eq!(audit.opened, ["manifest.json", "final.json"]);
    drop(audit);
    drop(files);
    fs::remove_file(directory.join("final.json")).unwrap();
    assert!(prepare(&root, ID, &|| true).is_err());
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
#[test]
fn pinned_metadata_and_total_bounds_are_checked_before_body_reads() {
    for oversized in [
        ("manifest.json", 1024 * 1024 + 1),
        ("events.jsonl", MAX_EXPORT_BYTES + 1),
    ] {
        let root = fixture(true);
        let mut files = pins(&root);
        let mut audit = Audit {
            inner: PinnedFiles {
                files: &mut files,
                check: &|| true,
            },
            opened: Vec::new(),
            oversized: Some(oversized),
        };
        assert!(validate_finalized_core(
            &mut audit,
            SessionTimestampKey::parse(ID).unwrap(),
            1024 * 1024,
            MAX_EXPORT_BYTES
        )
        .is_err());
        assert!(audit.opened.is_empty());
        drop(audit);
        drop(files);
        fs::remove_dir_all(root.parent().unwrap()).unwrap();
    }
}
#[test]
fn owner_retirement_during_pinned_stream_validation_stops_bounded_reads() {
    let root = fixture(true);
    let path = root.join(ID).join("events.jsonl");
    let rows = String::from_utf8(fs::read(&path).unwrap()).unwrap();
    let mut rows = rows
        .lines()
        .map(|line| serde_json::from_str::<Value>(line).unwrap())
        .collect::<Vec<_>>();
    rows[0]["bounded_padding"] = json!("x".repeat(100_000));
    let bytes = rows.iter().map(|v| format!("{v}\n")).collect::<String>();
    fs::write(&path, bytes.as_bytes()).unwrap();
    let terminal = root.join(ID).join("final.json");
    let mut v: Value = serde_json::from_slice(&fs::read(&terminal).unwrap()).unwrap();
    v["durable_stream_lengths"]["events.jsonl"] = json!(bytes.len());
    fs::write(terminal, v.to_string()).unwrap();
    let mut files = pins(&root);
    let calls = std::cell::Cell::new(0);
    let check = || {
        let n = calls.get() + 1;
        calls.set(n);
        n < 32
    };
    let result = validate_pinned_core(&mut files, SessionTimestampKey::parse(ID).unwrap(), &check);
    assert!(result.is_err());
    assert!(calls.get() >= 32);
    assert!(calls.get() <= 33);
    drop(files);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
#[test]
fn retained_components_refuse_replacement_and_escape() {
    let root = fixture(true);
    let p = prepare(&root, ID, &|| true).unwrap();
    for name in ["../outside", "a/b", "a\\b", ".", "..", "file:stream"] {
        assert!(p.custody.root.child(name, true).is_err());
    }
    let away = root.parent().unwrap().join("replaced-directory");
    #[cfg(windows)]
    assert!(
        fs::rename(root.join(ID), &away).is_err(),
        "retained Windows directory must deny renaming"
    );
    #[cfg(unix)]
    {
        fs::rename(root.join(ID), &away).unwrap();
        std::os::unix::fs::symlink(&away, root.join(ID)).unwrap();
        assert!(p.custody.root.directory(ID).is_err());
        assert!(p.unchanged().is_err());
        fs::remove_file(root.join(ID)).unwrap();
        fs::rename(&away, root.join(ID)).unwrap();
    }
    drop(p);
    fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
