//! Actor-local reuse of fully validated historical recordings.
//!
//! No persisted index is trusted. Startup always validates stream contents.
//! Android/Linux reuse requires an unchanged complete directory/file metadata
//! snapshot, including inode identity and nanosecond ctime (not just mtime/size).
//! Recent files also carry content digests because nonzero ctime nanoseconds
//! can still share a coarse kernel clock tick. Old unchanged history avoids
//! stream reads; recent/current bytes are fenced with SHA-256.
//! Privileged mutation capable of forging kernel ctime is outside app-private
//! storage custody. Platforms without this fence retain full recovery.

use crate::{
    session_recording_clock::SessionTimestampKey,
    session_recording_recovery::{
        append_recovered, recover_directory, recover_recordings, validate_recording_root_no_links,
        RecoveredEntry, RecoveryReport,
    },
};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs,
    io::Read,
    path::{Path, PathBuf},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Debug, PartialEq, Eq)]
struct Stamp {
    device: u64,
    inode: u64,
    mode: u32,
    links: u64,
    length: u64,
    modified: (i64, i64),
    changed: (i64, i64),
    digest: Option<[u8; 32]>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Snapshot {
    root_identity: (u64, u64),
    recent_bytes_hashed: u64,
    sessions: BTreeMap<PathBuf, Vec<(PathBuf, Stamp)>>,
}

#[derive(Default)]
pub(crate) struct RecoveryInventory {
    entries: BTreeMap<PathBuf, RecoveredEntry>,
    snapshot: Option<Snapshot>,
    hash_since: Option<SystemTime>,
    observed_at: Option<SystemTime>,
}

pub(crate) fn elapsed_us(start: Instant) -> u64 {
    u64::try_from(start.elapsed().as_micros()).unwrap_or(u64::MAX)
}

impl RecoveryInventory {
    pub(crate) fn recover(
        &mut self,
        root: &Path,
        current: Option<&Path>,
    ) -> Result<RecoveryReport, String> {
        self.recover_observed(root, current, snapshot)
    }

    fn recover_observed(
        &mut self,
        root: &Path,
        current: Option<&Path>,
        mut observe: impl FnMut(&Path, SystemTime) -> Result<Option<Snapshot>, String>,
    ) -> Result<RecoveryReport, String> {
        let start = Instant::now();
        // Linux/Android filesystem timestamps can share a clock tick despite
        // nonzero nanoseconds. Cover a conservative two-second interval with
        // content hashes. This cutoff stays fixed through publication.
        let observed_at = SystemTime::now();
        let hash_since = observed_at
            .checked_sub(Duration::from_secs(2))
            .unwrap_or(UNIX_EPOCH);
        // Build new state separately. Any failed or unstable observation clears
        // all reusable evidence; no partially checked cache escapes.
        let result = (|| {
            if self
                .observed_at
                .is_some_and(|previous| observed_at < previous)
            {
                return Err("recording-inventory-clock-regressed".to_owned());
            }
            let Some(before) = observe(root, hash_since)? else {
                self.entries.clear();
                self.snapshot = None;
                self.hash_since = None;
                self.observed_at = None;
                let mut report = recover_recordings(root)?;
                report.work.elapsed_us = elapsed_us(start);
                return Ok(report);
            };
            let same_root = self
                .snapshot
                .as_ref()
                .is_some_and(|old| old.root_identity == before.root_identity);
            let mut next_entries = BTreeMap::new();
            let mut report = RecoveryReport::default();
            for (directory, fence) in &before.sessions {
                report.work.metadata_entries = report
                    .work
                    .metadata_entries
                    .saturating_add(fence.len() as u64);
                let reusable = same_root
                    && current != Some(directory.as_path())
                    && self
                        .snapshot
                        .as_ref()
                        .and_then(|old| old.sessions.get(directory))
                        .is_some_and(|old| equivalent_validation(old, fence));
                let entry = if let Some(entry) = self.entries.get(directory).filter(|_| reusable) {
                    report.work.reused_sessions += 1;
                    entry.clone()
                } else {
                    report.work.validated_sessions += 1;
                    let key = SessionTimestampKey::parse(
                        directory
                            .file_name()
                            .and_then(|n| n.to_str())
                            .ok_or("recording-inventory-name")?,
                    )
                    .map_err(str::to_owned)?;
                    recover_directory(directory, key)
                };
                append_recovered(&mut report, &entry);
                if entry.session.error.is_none() && entry.condition.is_some() {
                    next_entries.insert(directory.clone(), entry);
                }
            }
            let after = observe(root, hash_since)?;
            if SystemTime::now() < observed_at || after.as_ref() != Some(&before) {
                return Err("recording-inventory-changed-during-validation".to_owned());
            }
            report.work.recent_bytes_hashed = before.recent_bytes_hashed.saturating_mul(2);
            self.entries = next_entries;
            self.hash_since = Some(hash_since);
            self.observed_at = Some(observed_at);
            self.snapshot = Some(before);
            report.work.elapsed_us = elapsed_us(start);
            Ok(report)
        })();
        if result.is_err() {
            self.entries.clear();
            self.snapshot = None;
            self.hash_since = None;
            self.observed_at = None;
        }
        result
    }

    /// Recheck after index publication, before the writer returns its saved
    /// acknowledgment. Index files live at the root, outside session snapshots.
    pub(crate) fn confirm_publication(&mut self, root: &Path) -> Result<u64, String> {
        self.confirm_observed(root, snapshot)
    }

    fn confirm_observed(
        &mut self,
        root: &Path,
        observe: impl FnOnce(&Path, SystemTime) -> Result<Option<Snapshot>, String>,
    ) -> Result<u64, String> {
        if self.snapshot.is_none() {
            return Ok(0);
        }
        let clock_forward = || {
            self.observed_at
                .is_some_and(|previous| SystemTime::now() >= previous)
        };
        let observation = if clock_forward() {
            observe(root, self.hash_since.unwrap_or(UNIX_EPOCH))
        } else {
            Err("recording-inventory-clock-regressed".to_owned())
        };
        match observation {
            Ok(Some(current)) if clock_forward() && self.snapshot.as_ref() == Some(&current) => {
                Ok(current.recent_bytes_hashed)
            }
            _ => {
                self.entries.clear();
                self.snapshot = None;
                self.hash_since = None;
                self.observed_at = None;
                Err("recording-inventory-changed-before-receipt".to_owned())
            }
        }
    }
}

// A validated digest may age out only while every stat field and filename
// remains identical. The caller separately rejects backward clock movement.
// The inverse transition (old metadata becoming recent) always revalidates.
fn equivalent_validation(old: &[(PathBuf, Stamp)], current: &[(PathBuf, Stamp)]) -> bool {
    old.len() == current.len()
        && old
            .iter()
            .zip(current)
            .all(|((old_path, old), (path, current))| {
                if old_path != path {
                    return false;
                }
                let mut previous = old.clone();
                previous.digest = current.digest;
                previous == *current
                    && (old.digest == current.digest
                        || (old.digest.is_some() && current.digest.is_none()))
            })
}

fn snapshot(root: &Path, hash_since: SystemTime) -> Result<Option<Snapshot>, String> {
    validate_recording_root_no_links(root)?;
    if !root.exists() {
        return Ok(None);
    }
    let Some(identity) =
        stamp(&fs::symlink_metadata(root).map_err(|e| format!("recording-inventory-root:{e}"))?)
    else {
        return Ok(None);
    };
    let mut result = Snapshot {
        root_identity: (identity.device, identity.inode),
        recent_bytes_hashed: 0,
        sessions: BTreeMap::new(),
    };
    for entry in fs::read_dir(root).map_err(|e| format!("recording-inventory-root-read:{e}"))? {
        let entry = entry.map_err(|e| format!("recording-inventory-entry:{e}"))?;
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if SessionTimestampKey::parse(&name).is_err() {
            continue;
        }
        let path = entry.path();
        let metadata =
            fs::symlink_metadata(&path).map_err(|e| format!("recording-inventory-stat:{e}"))?;
        if metadata.file_type().is_symlink() {
            return Err("recording-inventory-session-link".to_owned());
        }
        if !metadata.is_dir() {
            continue;
        }
        let Some(directory_stamp) = stamp(&metadata) else {
            return Ok(None);
        };
        let mut files = vec![(PathBuf::new(), directory_stamp)];
        for file in
            fs::read_dir(&path).map_err(|e| format!("recording-inventory-session-read:{e}"))?
        {
            let file = file.map_err(|e| format!("recording-inventory-file:{e}"))?;
            let metadata = fs::symlink_metadata(file.path())
                .map_err(|e| format!("recording-inventory-stat:{e}"))?;
            if metadata.file_type().is_symlink() {
                return Err("recording-inventory-file-link".to_owned());
            }
            // Unknown nested/non-regular entries never earn cached validation.
            if !metadata.is_file() {
                return Ok(None);
            }
            let Some(mut value) = stamp(&metadata) else {
                return Ok(None);
            };
            if value.links != 1 {
                return Err("recording-inventory-file-alias".to_owned());
            }
            let changed =
                UNIX_EPOCH + Duration::new(value.changed.0 as u64, value.changed.1 as u32);
            if changed >= hash_since {
                value.digest = Some(digest_file(&file.path(), &value)?);
                result.recent_bytes_hashed =
                    result.recent_bytes_hashed.saturating_add(value.length);
            }
            files.push((PathBuf::from(file.file_name()), value));
        }
        files.sort_by(|a, b| a.0.cmp(&b.0));
        result.sessions.insert(path, files);
    }
    Ok(Some(result))
}

fn digest_file(path: &Path, expected: &Stamp) -> Result<[u8; 32], String> {
    let mut file =
        fs::File::open(path).map_err(|e| format!("recording-inventory-hash-open:{e}"))?;
    let matches = |metadata: &fs::Metadata| stamp(metadata).as_ref() == Some(expected);
    if !matches(
        &file
            .metadata()
            .map_err(|e| format!("recording-inventory-hash-stat:{e}"))?,
    ) {
        return Err("recording-inventory-changed-during-hash".to_owned());
    }
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 65536];
    let mut bytes = 0u64;
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|e| format!("recording-inventory-hash-read:{e}"))?;
        if count == 0 {
            break;
        }
        bytes = bytes.saturating_add(count as u64);
        if bytes > expected.length {
            return Err("recording-inventory-changed-during-hash".to_owned());
        }
        digest.update(&buffer[..count]);
    }
    if bytes != expected.length
        || !matches(
            &file
                .metadata()
                .map_err(|e| format!("recording-inventory-hash-stat:{e}"))?,
        )
        || !matches(
            &fs::symlink_metadata(path)
                .map_err(|e| format!("recording-inventory-hash-path:{e}"))?,
        )
    {
        return Err("recording-inventory-changed-during-hash".to_owned());
    }
    Ok(digest.finalize().into())
}

#[cfg(unix)]
fn stamp(metadata: &fs::Metadata) -> Option<Stamp> {
    use std::os::unix::fs::MetadataExt;
    // Zero subsecond change time is also the common coarse/unsupported
    // representation. A real exact-second timestamp conservatively misses
    // the cache; it never earns a weaker fence.
    if metadata.ino() == 0
        || metadata.ctime() <= 0
        || !(1..1_000_000_000).contains(&metadata.ctime_nsec())
        || !(0..1_000_000_000).contains(&metadata.mtime_nsec())
    {
        return None;
    }
    Some(Stamp {
        device: metadata.dev(),
        inode: metadata.ino(),
        mode: metadata.mode(),
        links: metadata.nlink(),
        length: metadata.len(),
        modified: (metadata.mtime(), metadata.mtime_nsec()),
        changed: (metadata.ctime(), metadata.ctime_nsec()),
        digest: None,
    })
}

#[cfg(not(unix))]
fn stamp(_metadata: &fs::Metadata) -> Option<Stamp> {
    None
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use crate::{
        session_recording_clock::MonotonicNanos,
        session_recording_contract::FinalizationReason,
        session_recording_writer::{
            tests::{config, spec, temp_root},
            SessionRecordingWriter,
        },
    };
    use std::{
        fs::{File, FileTimes},
        time::Duration,
    };

    fn fixture(root: &Path, utc: u64) -> PathBuf {
        let writer = SessionRecordingWriter::spawn(config(root.to_path_buf(), 16, 1_000)).unwrap();
        let start = spec(utc);
        writer.prepare(1, start).unwrap().receive().unwrap();
        let receipt = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2000),
                FinalizationReason::RestartToExperimenter,
                false,
            )
            .unwrap()
            .receive()
            .unwrap();
        assert!(receipt.saved);
        assert!(receipt.timings.total_us >= receipt.timings.durable_finalize_us);
        writer.shutdown(Duration::from_secs(2)).unwrap();
        receipt.directory
    }

    #[test]
    fn cold_warm_and_current_validation_preserve_results() {
        let root = temp_root("inventory-warm");
        let first = fixture(&root, 1_725_000_000_000_000_000);
        fixture(&root, 1_725_000_000_000_000_001);
        let mut inventory = RecoveryInventory::default();
        let cold = inventory.recover(&root, None).unwrap();
        assert_eq!(cold.work.validated_sessions, 2);
        let warm = inventory.recover(&root, None).unwrap();
        assert_eq!(warm.sessions, cold.sessions);
        assert_eq!(warm.condition_counts, cold.condition_counts);
        assert_eq!(warm.work.reused_sessions, 2);
        let current = inventory.recover(&root, Some(&first)).unwrap();
        assert_eq!(current.work.validated_sessions, 1);
        assert_eq!(current.work.reused_sessions, 1);
        inventory.confirm_publication(&root).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn same_size_corruption_with_restored_mtime_invalidates_and_never_caches_error() {
        let root = temp_root("inventory-corruption");
        let directory = fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        let path = directory.join("events.jsonl");
        let modified = fs::metadata(&path).unwrap().modified().unwrap();
        let mut bytes = fs::read(&path).unwrap();
        bytes[0] = b'!';
        fs::write(&path, bytes).unwrap();
        File::options()
            .write(true)
            .open(&path)
            .unwrap()
            .set_times(FileTimes::new().set_modified(modified))
            .unwrap();
        for _ in 0..2 {
            let report = inventory.recover(&root, None).unwrap();
            assert_eq!(report.work.validated_sessions, 1);
            assert_eq!(report.work.reused_sessions, 0);
            assert!(report.sessions[0].error.is_some());
        }
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn added_deleted_sessions_and_manifest_identity_are_revalidated() {
        let root = temp_root("inventory-membership");
        let first = fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        let second = fixture(&root, 1_725_000_000_000_000_001);
        let added = inventory.recover(&root, None).unwrap();
        assert_eq!(
            (added.sessions.len(), added.work.validated_sessions),
            (2, 1)
        );
        fs::remove_dir_all(first).unwrap();
        assert_eq!(inventory.recover(&root, None).unwrap().sessions.len(), 1);
        let path = second.join("manifest.json");
        let text = fs::read_to_string(&path)
            .unwrap()
            .replace("condition-a", "condition-b");
        fs::write(path, text).unwrap();
        let changed = inventory.recover(&root, None).unwrap();
        assert_eq!(changed.work.validated_sessions, 1);
        assert!(changed.sessions[0].error.is_some());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn validation_and_publication_races_clear_all_reusable_evidence() {
        let root = temp_root("inventory-race");
        let directory = fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        let mut observations = 0;
        let result = inventory.recover_observed(&root, None, |root, since| {
            observations += 1;
            if observations == 2 {
                fs::write(directory.join("new-file"), b"mutation").unwrap();
            }
            snapshot(root, since)
        });
        assert_eq!(
            result.unwrap_err(),
            "recording-inventory-changed-during-validation"
        );
        assert!(inventory.entries.is_empty());
        assert_eq!(
            inventory
                .recover(&root, None)
                .unwrap()
                .work
                .validated_sessions,
            1
        );
        fs::write(directory.join("new-file"), b"mutated!").unwrap();
        assert_eq!(
            inventory.confirm_publication(&root).unwrap_err(),
            "recording-inventory-changed-before-receipt"
        );
        assert!(inventory.entries.is_empty());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn aliases_are_rejected_instead_of_reusing_validation() {
        let root = temp_root("inventory-alias");
        let directory = fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        fs::hard_link(directory.join("final.json"), directory.join("alias")).unwrap();
        assert_eq!(
            inventory.recover(&root, None).unwrap_err(),
            "recording-inventory-file-alias"
        );
        fs::remove_file(directory.join("alias")).unwrap();
        std::os::unix::fs::symlink(directory.join("final.json"), directory.join("alias")).unwrap();
        assert_eq!(
            inventory.recover(&root, None).unwrap_err(),
            "recording-inventory-file-link"
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn replacement_root_and_restarted_actor_require_fresh_validation() {
        let root = temp_root("inventory-replacement");
        fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        let moved = root.with_file_name("previous-recordings");
        fs::rename(&root, &moved).unwrap();
        fixture(&root, 1_725_000_000_000_000_000);
        assert_eq!(
            inventory
                .recover(&root, None)
                .unwrap()
                .work
                .validated_sessions,
            1
        );
        for _ in 0..2 {
            let writer = SessionRecordingWriter::spawn(config(root.clone(), 16, 1_000)).unwrap();
            assert_eq!(
                writer
                    .recover()
                    .unwrap()
                    .receive()
                    .unwrap()
                    .work
                    .validated_sessions,
                1
            );
            assert_eq!(
                writer
                    .recover()
                    .unwrap()
                    .receive()
                    .unwrap()
                    .work
                    .reused_sessions,
                1
            );
            writer.shutdown(Duration::from_secs(2)).unwrap();
        }
        fs::remove_dir_all(root).unwrap();
        fs::remove_dir_all(moved).unwrap();
    }

    #[test]
    fn actor_startup_arm_and_save_reuse_only_historical_sessions() {
        let root = temp_root("inventory-actor");
        for i in 0..7 {
            fixture(&root, 1_725_000_000_000_000_000 + i);
        }
        let writer = SessionRecordingWriter::spawn(config(root.clone(), 16, 1_000)).unwrap();
        assert_eq!(
            writer
                .recover()
                .unwrap()
                .receive()
                .unwrap()
                .work
                .validated_sessions,
            7
        );
        assert_eq!(
            writer
                .recover()
                .unwrap()
                .receive()
                .unwrap()
                .work
                .reused_sessions,
            7
        );
        writer
            .prepare(1, spec(1_725_000_000_000_000_007))
            .unwrap()
            .receive()
            .unwrap();
        let receipt = writer
            .try_finalize(
                1,
                MonotonicNanos::new(2000),
                FinalizationReason::RestartToExperimenter,
                false,
            )
            .unwrap()
            .receive()
            .unwrap();
        assert!(receipt.saved);
        assert_eq!(receipt.recovery.work.validated_sessions, 1);
        assert_eq!(receipt.recovery.work.reused_sessions, 7);
        assert_eq!(receipt.recovery.sessions.len(), 8);
        writer.shutdown(Duration::from_secs(2)).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn unavailable_metadata_fence_falls_back_to_full_validation() {
        let root = temp_root("inventory-fallback");
        fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        for _ in 0..2 {
            let report = inventory
                .recover_observed(&root, None, |_, _| Ok(None))
                .unwrap();
            assert_eq!(report.work.validated_sessions, 1);
            assert_eq!(report.work.reused_sessions, 0);
            assert!(inventory.entries.is_empty());
            assert!(inventory.snapshot.is_none());
        }
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn content_digest_rejects_same_tick_mutation_even_when_all_stat_fields_alias() {
        let root = temp_root("inventory-stat-alias");
        let directory = fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        let report = inventory.recover(&root, None).unwrap();
        assert!(report.work.recent_bytes_hashed > 0);
        let original = inventory.snapshot.clone().unwrap();
        let path = directory.join("events.jsonl");
        let mut bytes = fs::read(&path).unwrap();
        bytes[0] = b'!';
        fs::write(path, bytes).unwrap();
        let result = inventory.confirm_observed(&root, |root, since| {
            let mut changed = snapshot(root, since)?.unwrap();
            // Model the actual coarse-clock counterexample deterministically:
            // all stat fields alias, while content has changed. No sleep.
            for (path, files) in &mut changed.sessions {
                for ((_, current), (_, previous)) in files.iter_mut().zip(&original.sessions[path])
                {
                    let digest = current.digest;
                    *current = previous.clone();
                    current.digest = digest;
                }
            }
            Ok(Some(changed))
        });
        assert_eq!(
            result.unwrap_err(),
            "recording-inventory-changed-before-receipt"
        );
        assert!(inventory.entries.is_empty());
        // An old timestamp outside the fixed content window uses metadata only.
        let old = snapshot(&root, SystemTime::now() + Duration::from_secs(2))
            .unwrap()
            .unwrap();
        assert_eq!(old.recent_bytes_hashed, 0);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn digest_aging_is_one_way_and_clock_rollback_rejects_publication_and_reuse() {
        let root = temp_root("inventory-clock");
        fixture(&root, 1_725_000_000_000_000_000);
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        let original = inventory
            .snapshot
            .as_ref()
            .unwrap()
            .sessions
            .values()
            .next()
            .unwrap()
            .clone();
        assert!(original.iter().any(|(_, stamp)| stamp.digest.is_some()));
        let mut aged = original.clone();
        for (_, stamp) in &mut aged {
            stamp.digest = None;
        }
        assert!(equivalent_validation(&original, &aged));
        assert!(!equivalent_validation(&aged, &original));
        aged[0].1.inode += 1;
        assert!(!equivalent_validation(&original, &aged));
        inventory.observed_at = Some(SystemTime::now() + Duration::from_secs(10));
        assert_eq!(
            inventory.confirm_publication(&root).unwrap_err(),
            "recording-inventory-changed-before-receipt"
        );
        assert!(inventory.entries.is_empty());
        inventory.recover(&root, None).unwrap();
        inventory.observed_at = Some(SystemTime::now() + Duration::from_secs(10));
        assert_eq!(
            inventory.recover(&root, None).unwrap_err(),
            "recording-inventory-clock-regressed"
        );
        assert!(inventory.entries.is_empty());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn interrupted_checkpoint_validation_is_reusable_but_not_a_saved_final() {
        let root = temp_root("inventory-interrupted");
        let directory = fixture(&root, 1_725_000_000_000_000_000);
        fs::remove_file(directory.join("final.json")).unwrap();
        let mut inventory = RecoveryInventory::default();
        let cold = inventory.recover(&root, None).unwrap();
        assert!(cold.sessions[0].error.is_none());
        assert_eq!(
            cold.sessions[0].disposition,
            crate::session_recording_recovery::RecoveryDisposition::InterruptedEarly
        );
        let warm = inventory.recover(&root, None).unwrap();
        assert_eq!(warm.sessions, cold.sessions);
        assert_eq!(warm.work.reused_sessions, 1);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn checkpoint_metadata_cost_is_measured_without_reparsing_historical_streams() {
        let root = temp_root("inventory-checkpoints");
        let directory = fixture(&root, 1_725_000_000_000_000_000);
        // Finalized sessions retain checkpoints; their contents are superseded
        // by validated final.json, but every entry still participates in fences.
        for ordinal in 1..=12_655 {
            fs::write(
                directory.join(format!("checkpoint-{ordinal:020}.json")),
                b"{}",
            )
            .unwrap();
        }
        let mut inventory = RecoveryInventory::default();
        inventory.recover(&root, None).unwrap();
        let warm = inventory.recover(&root, None).unwrap();
        assert_eq!(warm.work.reused_sessions, 1);
        assert_eq!(warm.work.validated_sessions, 0);
        assert!(warm.work.metadata_entries >= 12_655);
        let start = Instant::now();
        inventory.confirm_publication(&root).unwrap();
        eprintln!(
            "checkpoint_metadata entries={} recovery_us={} publication_fence_us={}",
            warm.work.metadata_entries,
            warm.work.elapsed_us,
            elapsed_us(start)
        );
        fs::remove_dir_all(root).unwrap();
    }
}
