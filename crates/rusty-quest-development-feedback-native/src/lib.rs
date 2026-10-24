//! Explicit trusted-development CPU code replacement; no application activation.
//!
//! The ABI, digest, loader and transactional dispatch remain the proven source
//! owner. This adapter adds finite application budgets and observable state.
//! Every opened library stays resident until process exit, including refusals.

#[path = "../../../examples/development-feedback-native/src/abi.rs"]
pub mod abi;
#[path = "../../../examples/development-feedback-native/src/digest.rs"]
pub mod digest;
#[path = "../../../examples/development-feedback-native/src/loader.rs"]
pub mod loader;
#[path = "../../../examples/development-feedback-native/src/runtime.rs"]
pub mod runtime;

use std::{collections::HashSet, fmt, fs, path::{Component, Path}, sync::atomic::{AtomicU64, Ordering}};

pub const ATTEMPT_LIMIT: u64 = 64;
pub const IMAGE_LIMIT: u64 = 4 * 1024 * 1024;
pub const STAGED_LIMIT: u64 = 64 * 1024 * 1024;
static NEXT_ALLOCATION: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Error(pub &'static str);
impl fmt::Display for Error { fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str(self.0) } }
impl std::error::Error for Error {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub completed: u64,
    pub value: i64,
    pub generation: u64,
    pub retained_images: usize,
    pub allocation_token: u64,
    pub buffer: [u8; 8],
    pub attempts: u64,
    pub staged_bytes: u64,
    pub active_sha256: String,
}

/// Keep this owner on its creating thread. No Send/Sync implementation is added
/// to the raw library handles; an application must supply its own typed queue.
pub struct FeedbackHost {
    libraries: loader::ResidentHost,
    state: runtime::State,
    buffer: runtime::GuardedBuffer,
    allocation: u64,
    attempts: u64,
    staged_bytes: u64,
    attempted_generations: HashSet<u64>,
    active_sha256: String,
}
impl Default for FeedbackHost { fn default() -> Self { Self::new() } }
impl FeedbackHost {
    pub fn new() -> Self {
        Self {
            libraries: loader::ResidentHost::new(), state: runtime::State { completed: 0, value: 0 },
            buffer: runtime::GuardedBuffer::new(), allocation: NEXT_ALLOCATION.fetch_add(1, Ordering::Relaxed),
            attempts: 0, staged_bytes: 0, attempted_generations: HashSet::new(), active_sha256: String::new(),
        }
    }

    /// The caller stages trusted code under its fixed app-private session.
    /// Refused attempts consume the attempt budget, and loaded images are never
    /// unloaded. This is a development interface, not a native-code sandbox.
    pub fn activate_immutable(&mut self, path: &Path, sha256: &str, generation: u64, probe_expected: i64) -> Result<Snapshot, Error> {
        if self.attempts >= ATTEMPT_LIMIT { return Err(Error("attempt limit; restart host")); }
        self.attempts += 1;
        if generation == 0 || !self.attempted_generations.insert(generation) { return Err(Error("generation already attempted or zero")); }
        if sha256.len() != 64 || !sha256.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
            return Err(Error("expected lowercase SHA256"));
        }
        if !path.is_absolute() || path.components().any(|p| matches!(p, Component::ParentDir | Component::CurDir)) {
            return Err(Error("expected exact absolute immutable path"));
        }
        for part in path.ancestors() {
            let meta = fs::symlink_metadata(part).map_err(|_| Error("immutable path metadata"))?;
            if meta.file_type().is_symlink() { return Err(Error("immutable path alias")); }
            #[cfg(windows)] {
                use std::os::windows::fs::MetadataExt;
                if meta.file_attributes() & 0x400 != 0 { return Err(Error("immutable path reparse alias")); }
            }
        }
        let meta = fs::metadata(path).map_err(|_| Error("immutable image metadata"))?;
        if !meta.is_file() || !meta.permissions().readonly() || meta.len() == 0 || meta.len() > IMAGE_LIMIT {
            return Err(Error("expected nonempty readonly image within byte limit"));
        }
        let total = self.staged_bytes.checked_add(meta.len()).ok_or(Error("staged byte overflow"))?;
        if total > STAGED_LIMIT { return Err(Error("staged byte limit; restart host")); }
        self.staged_bytes = total;
        self.libraries.select(loader::Candidate { path, sha256, generation, scratch_expected: probe_expected }).map_err(Error)?;
        self.active_sha256 = sha256.to_owned();
        self.snapshot()
    }
    pub fn compute(&mut self, input: i64) -> Result<Snapshot, Error> {
        let (_, function) = self.libraries.active.ok_or(Error("no active generation"))?;
        runtime::dispatch(function, &mut self.state, &mut self.buffer, input).map_err(Error)?;
        self.snapshot()
    }
    pub fn snapshot(&self) -> Result<Snapshot, Error> {
        if self.buffer.before != 0xaabbccdd || self.buffer.after != 0x11223344 || self.buffer.bytes != self.state.value.to_le_bytes() {
            return Err(Error("resident buffer invariant"));
        }
        Ok(Snapshot {
            completed: self.state.completed, value: self.state.value,
            generation: self.libraries.active.map(|x| x.0).unwrap_or(0),
            retained_images: self.libraries.retained_images(), allocation_token: self.allocation,
            buffer: self.buffer.bytes, attempts: self.attempts, staged_bytes: self.staged_bytes,
            active_sha256: self.active_sha256.clone(),
        })
    }
}
