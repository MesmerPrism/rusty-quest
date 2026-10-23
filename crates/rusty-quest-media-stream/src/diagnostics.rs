//! Bounded failure-only observations. These data never grant lifecycle authority.
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, VecDeque},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
};

const MAX_OPERATIONS: usize = 32;
const MAX_SECONDARY: usize = 4;
const FRAME_BYTES: usize = 256 * 1024;
const OPERATION_BYTES: u64 = 1024 * 1024;
const TOTAL_BYTES: u64 = 4 * 1024 * 1024;

/// Failure phase known at the actual call boundary.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticPhase {
    Primary,
    Compensation,
    Cleanup,
    Recovery,
    Transport,
}
/// Layer that actually observed the failure.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticLayer {
    Host,
    Provider,
    Java,
    Jni,
    Rust,
    Media,
}
/// Effect knowledge, independent of success or failure.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticEffectState {
    NotEntered,
    Entered,
    Settled,
    Unknown,
    Unobserved,
}
/// Cleanup knowledge, independent of effect knowledge.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticCleanupState {
    NotRequested,
    Pending,
    Terminal,
    Unknown,
    Unobserved,
}
/// Validated optional correlation; no identity is invented for absent values.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
pub struct DiagnosticCorrelation {
    pub original_id: Option<String>,
    pub request_sha256: Option<String>,
    pub outcome_sha256: Option<String>,
    pub app_generation: Option<u64>,
    pub native_generation: Option<u64>,
    pub attempt_sequence: Option<u64>,
}
impl DiagnosticCorrelation {
    fn validated(mut self) -> Self {
        self.original_id = self.original_id.filter(|value| hex(value, 32));
        self.request_sha256 = self.request_sha256.filter(|value| hex(value, 64));
        self.outcome_sha256 = self.outcome_sha256.filter(|value| hex(value, 64));
        self
    }
}
/// Source-selected safe value; enum strings are checked before publication.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(untagged)]
pub enum SafeDiagnosticValue {
    Integer(i64),
    Boolean(bool),
    Enum(&'static str),
    Hash(String),
}
/// Descriptor of actual privately retained bytes, never a filesystem path.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct PrivateContextDescriptor {
    pub status: String,
    pub context_id: Option<String>,
    pub bytes: u64,
    pub sha256: Option<String>,
    pub truncated: bool,
}
impl PrivateContextDescriptor {
    fn absent(status: &str) -> Self {
        Self {
            status: status.into(),
            context_id: None,
            bytes: 0,
            sha256: None,
            truncated: false,
        }
    }
}
/// Borrowed raw failure data. Deliberately has no serde implementation.
pub struct PrivateFailureChunk<'a> {
    pub name: &'static str,
    pub bytes: &'a [u8],
}
/// Optional private owner; receipts describe the exact stored envelope bytes.
pub trait PrivateFailureSink: Send + Sync {
    /// Minimum useful encoded-envelope allowance. Smaller remainders trigger eviction.
    fn minimum_context_bytes(&self) -> u64 {
        1024
    }
    /// Deletes only the previously retained opaque context; failure keeps its quota reserved.
    fn evict(&self, _context_id: &str) -> Result<(), ()> {
        Err(())
    }
    fn retain(
        &self,
        context_id: &str,
        chunks: &[PrivateFailureChunk<'_>],
        max_bytes: u64,
    ) -> Result<PrivateContextDescriptor, ()>;
}
/// Safe cause independent of the producer's raw error text.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct DiagnosticCause {
    pub category: String,
    pub code: String,
}
/// One owned safe observation; raw private chunks cannot enter this structure.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct FailureObservation {
    pub schema: String,
    pub sequence: u64,
    pub operation: String,
    pub phase: DiagnosticPhase,
    pub layer: DiagnosticLayer,
    pub component: String,
    pub boundary: String,
    pub cause: DiagnosticCause,
    pub correlation: DiagnosticCorrelation,
    pub effect_state: DiagnosticEffectState,
    pub cleanup_state: DiagnosticCleanupState,
    pub safe_details: BTreeMap<String, SafeDiagnosticValue>,
    pub detail_sha256: Option<String>,
    pub private_context: PrivateContextDescriptor,
}
/// Retention limits reported alongside actual collection configuration.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct DiagnosticRetention {
    pub max_operations: usize,
    pub max_secondary: usize,
    pub max_frame_bytes: usize,
    pub max_operation_private_bytes: u64,
    pub max_total_private_bytes: u64,
    pub private_collection_enabled: bool,
    pub private_budget_exhausted: bool,
}
/// First failure and bounded ordered later failures for one actual operation.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct OperationDiagnostics {
    pub schema: String,
    pub operation: String,
    pub correlation: DiagnosticCorrelation,
    pub primary: FailureObservation,
    pub secondary: Vec<FailureObservation>,
    pub dropped_count: u64,
    pub retention: DiagnosticRetention,
}
/// Owned collection survives retirement of runtime and authority objects.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct OperationDiagnosticsCollection {
    pub schema: String,
    pub operations: Vec<OperationDiagnostics>,
    pub dropped_count: u64,
    pub retention: DiagnosticRetention,
}
/// Source-controlled input to a failure-only side channel.
pub struct FailureInput<'a> {
    phase: DiagnosticPhase,
    layer: DiagnosticLayer,
    component: &'static str,
    boundary: &'static str,
    category: &'static str,
    code: &'static str,
    effect_state: DiagnosticEffectState,
    cleanup_state: DiagnosticCleanupState,
    safe_details: BTreeMap<String, SafeDiagnosticValue>,
    chunks: Vec<PrivateFailureChunk<'a>>,
}
impl<'a> FailureInput<'a> {
    pub fn new(
        phase: DiagnosticPhase,
        layer: DiagnosticLayer,
        component: &'static str,
        boundary: &'static str,
        category: &'static str,
        code: &'static str,
    ) -> Self {
        Self {
            phase,
            layer,
            component,
            boundary,
            category,
            code,
            effect_state: DiagnosticEffectState::Unobserved,
            cleanup_state: DiagnosticCleanupState::Unobserved,
            safe_details: BTreeMap::new(),
            chunks: Vec::new(),
        }
    }
    pub fn states(
        mut self,
        effect: DiagnosticEffectState,
        cleanup: DiagnosticCleanupState,
    ) -> Self {
        self.effect_state = effect;
        self.cleanup_state = cleanup;
        self
    }
    pub fn safe_detail(mut self, name: &'static str, value: SafeDiagnosticValue) -> Self {
        let safe = match &value {
            SafeDiagnosticValue::Enum(value) => identifier(value),
            SafeDiagnosticValue::Hash(value) => hex(value, 64),
            _ => true,
        };
        if self.safe_details.len() < 16 && identifier(name) && safe {
            self.safe_details.insert(name.into(), value);
        }
        self
    }
    pub fn private_context(mut self, chunks: Vec<PrivateFailureChunk<'a>>) -> Self {
        self.chunks = chunks;
        self
    }
}
#[derive(Default)]
struct State {
    next_operation: u64,
    next_sequence: u64,
    entries: VecDeque<(u64, OperationDiagnostics)>,
    dropped: u64,
    private_reserved: u64,
    contexts: VecDeque<RetainedContext>,
}
struct RetainedContext {
    id: String,
    operation: u64,
    bytes: u64,
    operation_bytes: Arc<AtomicU64>,
}
static NEXT_COLLECTOR: AtomicU64 = AtomicU64::new(1);
struct Inner {
    identity: u64,
    nonce: u128,
    private_budget_exhausted: AtomicBool,
    private_collection: Mutex<()>,
    state: Mutex<State>,
    sink: Option<Arc<dyn PrivateFailureSink>>,
}
/// Thread-local operation slot shared by clone identity across long-lived owners.
/// Snapshot at call entry. Cross-thread callbacks have no guessed Original.
#[derive(Clone)]
pub struct DiagnosticScope {
    identity: u64,
}
static NEXT_SCOPE: AtomicU64 = AtomicU64::new(1);
std::thread_local! {
    static CURRENT_OPERATIONS: std::cell::RefCell<BTreeMap<u64, DiagnosticOperation>> = const { std::cell::RefCell::new(BTreeMap::new()) };
}
impl Default for DiagnosticScope {
    fn default() -> Self {
        Self {
            identity: NEXT_SCOPE.fetch_add(1, Ordering::Relaxed),
        }
    }
}
impl DiagnosticScope {
    /// Explicitly unattributed nested work must not inherit an outer Original.
    pub fn suspend(&self) -> DiagnosticScopeGuard {
        let prior = self.current();
        self.set(None);
        DiagnosticScopeGuard {
            scope: self.clone(),
            prior,
            thread_bound: std::marker::PhantomData,
        }
    }
    pub fn set(&self, operation: Option<DiagnosticOperation>) {
        CURRENT_OPERATIONS.with(|current| {
            let mut current = current.borrow_mut();
            if let Some(operation) = operation {
                current.insert(self.identity, operation);
            } else {
                current.remove(&self.identity);
            }
        });
    }
    pub fn current(&self) -> Option<DiagnosticOperation> {
        CURRENT_OPERATIONS.with(|current| current.borrow().get(&self.identity).cloned())
    }
    /// Restores the prior scope on drop, including early errors and nested callbacks.
    pub fn enter(&self, operation: DiagnosticOperation) -> DiagnosticScopeGuard {
        let prior = self.current();
        self.set(Some(operation));
        DiagnosticScopeGuard {
            scope: self.clone(),
            prior,
            thread_bound: std::marker::PhantomData,
        }
    }
}
/// Thread-bound RAII guard; cannot restore another thread's diagnostic scope.
pub struct DiagnosticScopeGuard {
    scope: DiagnosticScope,
    prior: Option<DiagnosticOperation>,
    thread_bound: std::marker::PhantomData<std::rc::Rc<()>>,
}
impl Drop for DiagnosticScopeGuard {
    fn drop(&mut self) {
        self.scope.set(self.prior.take());
    }
}

/// Cheap shared observer with bounded plain-data snapshots and optional private owner.
#[derive(Clone)]
pub struct DiagnosticCollector {
    inner: Arc<Inner>,
}
impl Default for DiagnosticCollector {
    fn default() -> Self {
        Self::new(None)
    }
}
impl DiagnosticCollector {
    pub fn new(sink: Option<Arc<dyn PrivateFailureSink>>) -> Self {
        Self {
            inner: Arc::new(Inner {
                identity: NEXT_COLLECTOR.fetch_add(1, Ordering::Relaxed),
                nonce: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map_or(0, |value| value.as_nanos()),
                private_budget_exhausted: AtomicBool::new(false),
                private_collection: Mutex::new(()),
                state: Mutex::new(State::default()),
                sink,
            }),
        }
    }
    fn retention(&self) -> DiagnosticRetention {
        DiagnosticRetention {
            max_operations: MAX_OPERATIONS,
            max_secondary: MAX_SECONDARY,
            max_frame_bytes: FRAME_BYTES,
            max_operation_private_bytes: OPERATION_BYTES,
            max_total_private_bytes: TOTAL_BYTES,
            private_collection_enabled: self.inner.sink.is_some(),
            private_budget_exhausted: self.inner.private_budget_exhausted.load(Ordering::Relaxed),
        }
    }
    pub fn operation(
        &self,
        operation: &'static str,
        correlation: DiagnosticCorrelation,
    ) -> DiagnosticOperation {
        let mut state = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        state.next_operation = state.next_operation.saturating_add(1);
        DiagnosticOperation {
            collector: self.clone(),
            id: state.next_operation,
            operation: safe_identifier(operation),
            correlation: correlation.validated(),
            private_bytes: Arc::new(AtomicU64::new(0)),
        }
    }
    pub fn snapshot(&self, original_id: Option<&str>) -> OperationDiagnosticsCollection {
        let state = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        OperationDiagnosticsCollection {
            schema: "rusty.quest.operation_diagnostics_collection.v1".into(),
            operations: state
                .entries
                .iter()
                .filter(|(_, item)| {
                    original_id.map_or(true, |id| {
                        hex(id, 32) && item.correlation.original_id.as_deref() == Some(id)
                    })
                })
                .map(|(_, item)| {
                    let mut item = item.clone();
                    item.retention = self.retention();
                    item
                })
                .collect(),
            dropped_count: state.dropped,
            retention: self.retention(),
        }
    }
}
/// Handle to one diagnostic operation, independent of runtime object lifetime.
#[derive(Clone)]
pub struct DiagnosticOperation {
    collector: DiagnosticCollector,
    id: u64,
    operation: String,
    correlation: DiagnosticCorrelation,
    private_bytes: Arc<AtomicU64>,
}
impl DiagnosticOperation {
    pub fn correlation(&self) -> DiagnosticCorrelation {
        self.correlation.clone()
    }
    pub fn with_correlation(mut self, correlation: DiagnosticCorrelation) -> Self {
        self.correlation = correlation.validated();
        self
    }
    pub fn last_failure(&self) -> Option<FailureObservation> {
        self.snapshot().map(|snapshot| {
            snapshot
                .secondary
                .last()
                .cloned()
                .unwrap_or(snapshot.primary)
        })
    }
    pub fn snapshot(&self) -> Option<OperationDiagnostics> {
        self.collector
            .inner
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .entries
            .iter()
            .find(|(id, _)| *id == self.id)
            .map(|(_, value)| {
                let mut value = value.clone();
                value.retention = self.collector.retention();
                value
            })
    }
    pub fn record(&self, input: FailureInput<'_>) -> Option<FailureObservation> {
        // Serialize retention changes without holding the public snapshot lock over I/O.
        let _collection = self
            .collector
            .inner
            .private_collection
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let mut state = self
            .collector
            .inner
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        state.next_sequence = state.next_sequence.saturating_add(1);
        let sequence = state.next_sequence;
        let context_id = format!(
            "{:x}",
            Sha256::digest(
                format!(
                    "diagnostic-context:{}:{}:{}:{}:{sequence}:{:?}",
                    std::process::id(),
                    self.collector.inner.nonce,
                    self.collector.inner.identity,
                    self.id,
                    self.correlation
                )
                .as_bytes()
            )
        );
        let had_chunks = !input.chunks.is_empty();
        let mut truncated = false;
        let chunks: Vec<_> = input
            .chunks
            .iter()
            .take(16)
            .filter(|chunk| identifier(chunk.name))
            .map(|chunk| {
                truncated |= chunk.bytes.len() > FRAME_BYTES;
                PrivateFailureChunk {
                    name: chunk.name,
                    bytes: &chunk.bytes[..chunk.bytes.len().min(FRAME_BYTES)],
                }
            })
            .collect();
        truncated |= input.chunks.len() > 16;
        drop(state);
        let required = self.collector.inner.sink.as_ref().map_or(1, |sink| {
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                sink.minimum_context_bytes()
            }))
            .unwrap_or(1024)
            .clamp(1, OPERATION_BYTES)
        });
        let mut state = self
            .collector
            .inner
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if had_chunks && self.collector.inner.sink.is_some() {
            loop {
                let operation_full = OPERATION_BYTES
                    .saturating_sub(self.private_bytes.load(Ordering::Relaxed))
                    < required;
                if !operation_full
                    && TOTAL_BYTES.saturating_sub(state.private_reserved) >= required
                    && state.contexts.len() < MAX_OPERATIONS * (MAX_SECONDARY + 1)
                {
                    break;
                }
                let candidate = if operation_full {
                    state
                        .contexts
                        .iter()
                        .position(|context| context.operation == self.id)
                } else {
                    (!state.contexts.is_empty()).then_some(0)
                };
                let Some(index) = candidate else { break };
                let Some(context) = state.contexts.remove(index) else {
                    break;
                };
                drop(state);
                let evicted = self.collector.inner.sink.as_ref().is_some_and(|sink| {
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        sink.evict(&context.id)
                    }))
                    .ok()
                    .is_some_and(|result| result.is_ok())
                });
                state = self
                    .collector
                    .inner
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if !evicted {
                    state.contexts.insert(index, context);
                    break;
                }
                state.private_reserved = state.private_reserved.saturating_sub(context.bytes);
                context
                    .operation_bytes
                    .fetch_sub(context.bytes, Ordering::Relaxed);
                for (_, operation) in &mut state.entries {
                    for observation in std::iter::once(&mut operation.primary)
                        .chain(operation.secondary.iter_mut())
                    {
                        if observation.private_context.context_id.as_deref()
                            == Some(context.id.as_str())
                        {
                            observation.private_context =
                                PrivateContextDescriptor::absent("unavailable");
                        }
                    }
                }
            }
        }
        let allowance = OPERATION_BYTES
            .saturating_sub(self.private_bytes.load(Ordering::Relaxed))
            .min(TOTAL_BYTES.saturating_sub(state.private_reserved));
        let allowance = if state.contexts.len() >= MAX_OPERATIONS * (MAX_SECONDARY + 1)
            || allowance < required
        {
            0
        } else {
            allowance
        };
        self.collector
            .inner
            .private_budget_exhausted
            .store(had_chunks && allowance == 0, Ordering::Relaxed);
        state.private_reserved += allowance;
        self.private_bytes.fetch_add(allowance, Ordering::Relaxed);
        drop(state);
        let retained = if had_chunks && allowance > 0 {
            self.collector
                .inner
                .sink
                .as_ref()
                .and_then(|sink| {
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        sink.retain(&context_id, &chunks, allowance)
                    }))
                    .ok()
                    .and_then(Result::ok)
                })
                .filter(|receipt| {
                    matches!(receipt.status.as_str(), "available" | "truncated")
                        && receipt.context_id.as_deref() == Some(context_id.as_str())
                        && receipt.bytes > 0
                        && receipt.bytes <= allowance
                        && receipt
                            .sha256
                            .as_deref()
                            .is_some_and(|value| hex(value, 64))
                        && (receipt.status == "truncated") == receipt.truncated
                })
        } else {
            None
        };
        let mut context = retained.unwrap_or_else(|| {
            PrivateContextDescriptor::absent(if had_chunks { "unavailable" } else { "absent" })
        });
        if context.bytes > 0 && truncated {
            context.truncated = true;
            context.status = "truncated".into();
        }
        let detail_sha256 = chunks
            .iter()
            .find(|chunk| chunk.name == "error")
            .map(|chunk| format!("{:x}", Sha256::digest(chunk.bytes)));
        let observation = FailureObservation {
            schema: "rusty.quest.failure_observation.v1".into(),
            sequence,
            operation: self.operation.clone(),
            phase: input.phase,
            layer: input.layer,
            component: safe_identifier(input.component),
            boundary: safe_identifier(input.boundary),
            cause: DiagnosticCause {
                category: safe_identifier(input.category),
                code: safe_identifier(input.code),
            },
            correlation: self.correlation.clone(),
            effect_state: input.effect_state,
            cleanup_state: input.cleanup_state,
            safe_details: input.safe_details,
            detail_sha256,
            private_context: context,
        };
        let mut state = self
            .collector
            .inner
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let release = allowance.saturating_sub(observation.private_context.bytes);
        state.private_reserved = state.private_reserved.saturating_sub(release);
        self.private_bytes.fetch_sub(release, Ordering::Relaxed);
        if observation.private_context.bytes > 0 {
            state.contexts.push_back(RetainedContext {
                id: context_id,
                operation: self.id,
                bytes: observation.private_context.bytes,
                operation_bytes: self.private_bytes.clone(),
            });
        }
        if let Some((_, existing)) = state.entries.iter_mut().find(|(id, _)| *id == self.id) {
            existing.secondary.push(observation.clone());
            existing.secondary.sort_by_key(|value| value.sequence);
            // A slow private sink cannot reorder which failure was actually first.
            if existing.primary.sequence > existing.secondary[0].sequence {
                std::mem::swap(&mut existing.primary, &mut existing.secondary[0]);
                existing.secondary.sort_by_key(|value| value.sequence);
                existing.correlation = existing.primary.correlation.clone();
            }
            if existing.secondary.len() > MAX_SECONDARY {
                existing.secondary.remove(0);
                existing.dropped_count += 1;
            }
        } else {
            if state.entries.len() == MAX_OPERATIONS {
                state.entries.pop_front();
                state.dropped += 1;
            }
            state.entries.push_back((
                self.id,
                OperationDiagnostics {
                    schema: "rusty.quest.operation_diagnostics.v1".into(),
                    operation: self.operation.clone(),
                    correlation: self.correlation.clone(),
                    primary: observation.clone(),
                    secondary: Vec::new(),
                    dropped_count: 0,
                    retention: self.collector.retention(),
                },
            ));
        }
        Some(observation)
    }
}
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 96
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(&c))
}
fn safe_identifier(value: &str) -> String {
    if identifier(value) {
        value.into()
    } else {
        "invalid_diagnostic_identifier".into()
    }
}
fn hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

#[cfg(test)]
#[path = "diagnostics_tests.rs"]
mod tests;
