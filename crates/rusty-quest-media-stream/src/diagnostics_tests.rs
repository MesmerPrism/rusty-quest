use super::*;
use std::sync::{Arc, Mutex};

fn failure(phase: DiagnosticPhase, boundary: &'static str) -> FailureInput<'static> {
    FailureInput::new(
        phase,
        DiagnosticLayer::Rust,
        "owner_dispatch",
        boundary,
        "proof",
        "unclassified",
    )
}

#[test]
fn success_is_absent_and_first_failure_survives_cleanup_and_retirement() {
    let collector = DiagnosticCollector::default();
    let operation = collector.operation("start", DiagnosticCorrelation::default());
    assert!(collector.snapshot(None).operations.is_empty());
    operation.record(failure(DiagnosticPhase::Primary, "verify_signed_response"));
    for _ in 0..7 {
        operation.record(failure(DiagnosticPhase::Compensation, "abort_readback"));
    }
    drop(operation);
    let snapshot = collector.snapshot(None);
    let observed = &snapshot.operations[0];
    assert_eq!(observed.primary.boundary, "verify_signed_response");
    assert_eq!(observed.secondary.len(), 4);
    assert_eq!(observed.dropped_count, 3);
    assert_eq!(
        observed.secondary.last().unwrap().phase,
        DiagnosticPhase::Compensation
    );
}

#[test]
fn correlation_filter_and_operation_limit_are_bounded() {
    let collector = DiagnosticCollector::default();
    for n in 0..35 {
        let correlation = DiagnosticCorrelation {
            original_id: Some(format!("{n:032x}")),
            ..Default::default()
        };
        collector
            .operation("start", correlation)
            .record(failure(DiagnosticPhase::Primary, "provider_execute"));
    }
    let snapshot = collector.snapshot(None);
    assert_eq!(snapshot.operations.len(), 32);
    assert_eq!(snapshot.dropped_count, 3);
    assert_eq!(
        collector
            .snapshot(Some("00000000000000000000000000000022"))
            .operations
            .len(),
        1
    );
    assert!(collector
        .snapshot(Some("private-token"))
        .operations
        .is_empty());
}

#[derive(Default)]
struct Sink {
    calls: Mutex<Vec<Vec<u8>>>,
}
impl PrivateFailureSink for Sink {
    fn retain(
        &self,
        context_id: &str,
        chunks: &[PrivateFailureChunk<'_>],
        _: u64,
    ) -> Result<PrivateContextDescriptor, ()> {
        self.calls.lock().unwrap().push(
            chunks
                .iter()
                .flat_map(|chunk| chunk.bytes.iter().copied())
                .collect(),
        );
        let bytes: Vec<u8> = chunks
            .iter()
            .flat_map(|chunk| chunk.bytes.iter().copied())
            .collect();
        Ok(PrivateContextDescriptor {
            status: "available".into(),
            context_id: Some(context_id.into()),
            bytes: bytes.len() as u64,
            sha256: Some(format!("{:x}", Sha256::digest(&bytes))),
            truncated: false,
        })
    }
}

#[test]
fn private_bytes_are_separate_and_frame_operation_and_global_bounds_are_real() {
    let sink = Arc::new(Sink::default());
    let collector = DiagnosticCollector::new(Some(sink.clone()));
    let secret = vec![b'x'; 300 * 1024];
    for _ in 0..6 {
        let operation = collector.operation("start", DiagnosticCorrelation::default());
        for _ in 0..5 {
            let input =
                failure(DiagnosticPhase::Primary, "response_binding").private_context(vec![
                    PrivateFailureChunk {
                        name: "signed_response",
                        bytes: &secret,
                    },
                ]);
            operation.record(input);
        }
    }
    let calls = sink.calls.lock().unwrap();
    assert!(calls.iter().all(|bytes| bytes.len() <= 256 * 1024));
    assert_eq!(calls.iter().map(Vec::len).sum::<usize>(), 4 * 1024 * 1024);
    let public = serde_json::to_string(&collector.snapshot(None)).unwrap();
    assert!(!public.contains(&"x".repeat(100)));
    assert!(public.contains("truncated"));
    assert!(public.contains("unavailable"));
}

#[test]
fn disabled_or_failed_collection_never_escapes_and_reports_honestly() {
    struct Broken;
    impl PrivateFailureSink for Broken {
        fn retain(
            &self,
            _: &str,
            _: &[PrivateFailureChunk<'_>],
            _: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            Err(())
        }
    }
    for sink in [None, Some(Arc::new(Broken) as Arc<dyn PrivateFailureSink>)] {
        let collector = DiagnosticCollector::new(sink);
        let operation = collector.operation("start", DiagnosticCorrelation::default());
        let observed = operation
            .record(
                failure(DiagnosticPhase::Primary, "transport_exchange").private_context(vec![
                    PrivateFailureChunk {
                        name: "error",
                        bytes: b"private uri/token",
                    },
                ]),
            )
            .unwrap();
        assert_eq!(observed.private_context.status, "unavailable");
        assert_eq!(
            observed.detail_sha256.as_deref(),
            Some(format!("{:x}", Sha256::digest(b"private uri/token")).as_str())
        );
        assert!(!serde_json::to_string(&observed)
            .unwrap()
            .contains("private uri/token"));
    }
}

#[test]
fn concurrent_clones_allocate_unique_monotonic_sequences_and_safe_values_only() {
    let collector = DiagnosticCollector::default();
    let operation = collector.operation("start", DiagnosticCorrelation::default());
    std::thread::scope(|scope| {
        for _ in 0..4 {
            let operation = operation.clone();
            scope.spawn(move || {
                operation.record(
                    failure(DiagnosticPhase::Transport, "exchange")
                        .safe_detail("expected_generation", SafeDiagnosticValue::Integer(66))
                        .safe_detail("actual_generation", SafeDiagnosticValue::Integer(68))
                        .safe_detail("unsafe", SafeDiagnosticValue::Enum("uri://private")),
                );
            });
        }
    });
    let snapshot = operation.snapshot().unwrap();
    assert_eq!(snapshot.primary.sequence, 1);
    assert_eq!(
        snapshot
            .secondary
            .iter()
            .map(|value| value.sequence)
            .collect::<Vec<_>>(),
        vec![2, 3, 4]
    );
    assert!(!snapshot.primary.safe_details.contains_key("unsafe"));
    assert_eq!(snapshot.primary.safe_details.len(), 2);
}

#[test]
fn scope_is_thread_local_nested_and_restored_on_early_return() {
    let collector = DiagnosticCollector::default();
    let scope = DiagnosticScope::default();
    let outer = collector.operation("start", Default::default());
    let inner = collector.operation("peer_stop", Default::default());
    let _outer = scope.enter(outer.clone());
    {
        let _unattributed = scope.suspend();
        assert!(scope.current().is_none());
        std::thread::scope(|threads| {
            let scope = scope.clone();
            threads.spawn(move || assert!(scope.current().is_none()));
        });
    }
    assert!(scope.current().is_some());
    std::thread::scope(|threads| {
        let scope = scope.clone();
        threads.spawn(move || assert!(scope.current().is_none()));
    });
    {
        let _inner = scope.enter(inner.clone());
        scope
            .current()
            .unwrap()
            .record(failure(DiagnosticPhase::Cleanup, "nested_cleanup"));
    }
    scope
        .current()
        .unwrap()
        .record(failure(DiagnosticPhase::Primary, "outer_failure"));
    assert_eq!(outer.snapshot().unwrap().primary.boundary, "outer_failure");
    assert_eq!(inner.snapshot().unwrap().primary.boundary, "nested_cleanup");
    drop(_outer);
    assert!(scope.current().is_none());
}

#[test]
fn sink_receipt_uses_actual_envelope_bytes_and_invalid_receipts_are_refused() {
    struct Envelope;
    impl PrivateFailureSink for Envelope {
        fn retain(
            &self,
            id: &str,
            chunks: &[PrivateFailureChunk<'_>],
            max_bytes: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            assert_eq!(id.len(), 64);
            assert!(id
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()));
            let bytes = serde_json::to_vec(
                &chunks
                    .iter()
                    .map(|chunk| (chunk.name, chunk.bytes))
                    .collect::<Vec<_>>(),
            )
            .unwrap();
            assert!((bytes.len() as u64) < max_bytes);
            Ok(PrivateContextDescriptor {
                status: "available".into(),
                context_id: Some(id.into()),
                bytes: bytes.len() as u64,
                sha256: Some(format!("{:x}", Sha256::digest(&bytes))),
                truncated: false,
            })
        }
    }
    let collector = DiagnosticCollector::new(Some(Arc::new(Envelope)));
    let operation = collector.operation("start", Default::default());
    let observed = operation
        .record(
            failure(DiagnosticPhase::Primary, "envelope_failure").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: b"error",
                },
            ]),
        )
        .unwrap();
    let envelope = serde_json::to_vec(&vec![("error", b"error".as_slice())]).unwrap();
    assert_eq!(observed.private_context.bytes, envelope.len() as u64);
    assert_eq!(
        observed.private_context.sha256,
        Some(format!("{:x}", Sha256::digest(envelope)))
    );
    struct WrongReceipt;
    impl PrivateFailureSink for WrongReceipt {
        fn retain(
            &self,
            _: &str,
            _: &[PrivateFailureChunk<'_>],
            _: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            Ok(PrivateContextDescriptor {
                status: "available".into(),
                context_id: Some("caller/path".into()),
                bytes: 1,
                sha256: Some("a".repeat(64)),
                truncated: false,
            })
        }
    }
    let operation = DiagnosticCollector::new(Some(Arc::new(WrongReceipt)))
        .operation("start", Default::default());
    let observed = operation
        .record(
            failure(DiagnosticPhase::Primary, "bad_sink_receipt").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: b"original error",
                },
            ]),
        )
        .unwrap();
    assert_eq!(observed.private_context.status, "unavailable");
    assert!(observed.detail_sha256.is_some());
    assert!(!serde_json::to_string(&observed)
        .unwrap()
        .contains("caller/path"));
}

#[test]
fn rolling_eviction_reclaims_actual_bytes_and_preserves_first_cause() {
    #[derive(Default)]
    struct Rolling {
        retained: Mutex<BTreeMap<String, u64>>,
    }
    impl PrivateFailureSink for Rolling {
        fn evict(&self, id: &str) -> Result<(), ()> {
            self.retained.lock().unwrap().remove(id).ok_or(())?;
            Ok(())
        }
        fn retain(
            &self,
            id: &str,
            chunks: &[PrivateFailureChunk<'_>],
            max_bytes: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            let bytes = chunks
                .iter()
                .map(|chunk| chunk.bytes.len() as u64)
                .sum::<u64>()
                .min(max_bytes);
            self.retained.lock().unwrap().insert(id.into(), bytes);
            Ok(PrivateContextDescriptor {
                status: "available".into(),
                context_id: Some(id.into()),
                bytes,
                sha256: Some("a".repeat(64)),
                truncated: false,
            })
        }
    }
    let sink = Arc::new(Rolling::default());
    let collector = DiagnosticCollector::new(Some(sink.clone()));
    let operation = collector.operation("start", Default::default());
    let data = vec![b'x'; FRAME_BYTES];
    let first = operation
        .record(
            failure(DiagnosticPhase::Primary, "first_failure").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: &data,
                },
            ]),
        )
        .unwrap();
    for _ in 0..8 {
        operation.record(
            failure(DiagnosticPhase::Compensation, "later_cleanup").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: &data,
                },
            ]),
        );
    }
    let snapshot = operation.snapshot().unwrap();
    assert_eq!(snapshot.primary.sequence, first.sequence);
    assert_eq!(snapshot.primary.boundary, first.boundary);
    assert_eq!(snapshot.primary.detail_sha256, first.detail_sha256);
    assert_eq!(snapshot.primary.private_context.status, "unavailable");
    assert_eq!(
        snapshot.secondary.last().unwrap().private_context.status,
        "available"
    );
    assert_eq!(
        sink.retained.lock().unwrap().values().sum::<u64>(),
        OPERATION_BYTES
    );
    for _ in 0..20 {
        collector.operation("peer_stop", Default::default()).record(
            failure(DiagnosticPhase::Cleanup, "terminal_readback").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: &data,
                },
            ]),
        );
    }
    assert!(sink.retained.lock().unwrap().values().sum::<u64>() <= TOTAL_BYTES);
    assert!(!collector.snapshot(None).retention.private_budget_exhausted);
}

#[test]
fn partial_envelope_remainder_rolls_instead_of_permanently_rejecting_future_contexts() {
    struct Envelope {
        calls: AtomicU64,
        evictions: AtomicU64,
    }
    impl PrivateFailureSink for Envelope {
        fn evict(&self, _: &str) -> Result<(), ()> {
            self.evictions.fetch_add(1, Ordering::Relaxed);
            Ok(())
        }
        fn retain(
            &self,
            id: &str,
            _: &[PrivateFailureChunk<'_>],
            max_bytes: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            assert!(max_bytes >= 1024);
            let size = if self.calls.fetch_add(1, Ordering::Relaxed) == 0 {
                OPERATION_BYTES - 512
            } else {
                1024
            };
            let actual_envelope = vec![0; size as usize];
            Ok(PrivateContextDescriptor {
                status: "available".into(),
                context_id: Some(id.into()),
                bytes: size,
                sha256: Some(format!("{:x}", Sha256::digest(actual_envelope))),
                truncated: false,
            })
        }
    }
    let sink = Arc::new(Envelope {
        calls: AtomicU64::new(0),
        evictions: AtomicU64::new(0),
    });
    let collector = DiagnosticCollector::new(Some(sink.clone()));
    let operation = collector.operation("start", Default::default());
    for _ in 0..2 {
        operation.record(
            failure(DiagnosticPhase::Primary, "private_envelope").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: b"raw error",
                },
            ]),
        );
    }
    assert_eq!(sink.calls.load(Ordering::Relaxed), 2);
    assert_eq!(sink.evictions.load(Ordering::Relaxed), 1);
    assert_eq!(
        operation.snapshot().unwrap().secondary[0]
            .private_context
            .status,
        "available"
    );
    assert!(!collector.snapshot(None).retention.private_budget_exhausted);
}

#[test]
fn optional_sink_panics_at_minimum_retain_and_eviction_do_not_escape_or_replace_primary() {
    struct PanickingSink(u8);
    impl PrivateFailureSink for PanickingSink {
        fn minimum_context_bytes(&self) -> u64 {
            assert_ne!(self.0, 0, "MODELED minimum callback panic");
            1024
        }
        fn evict(&self, _: &str) -> Result<(), ()> {
            assert_ne!(self.0, 2, "MODELED eviction callback panic");
            Ok(())
        }
        fn retain(
            &self,
            id: &str,
            _: &[PrivateFailureChunk<'_>],
            max_bytes: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            assert_ne!(self.0, 1, "MODELED retain callback panic");
            let envelope = vec![0; max_bytes as usize];
            Ok(PrivateContextDescriptor {
                status: "available".into(),
                context_id: Some(id.into()),
                bytes: max_bytes,
                sha256: Some(format!("{:x}", Sha256::digest(envelope))),
                truncated: false,
            })
        }
    }
    for point in 0..3 {
        let collector = DiagnosticCollector::new(Some(Arc::new(PanickingSink(point))));
        let operation = collector.operation("start", Default::default());
        for _ in 0..2 {
            let original_result: Result<(), &str> = Err("original owner error");
            operation.record(
                failure(DiagnosticPhase::Primary, "real_owner_boundary").private_context(vec![
                    PrivateFailureChunk {
                        name: "error",
                        bytes: b"original owner error",
                    },
                ]),
            );
            assert_eq!(original_result, Err("original owner error"));
        }
        let snapshot = operation.snapshot().unwrap();
        assert_eq!(snapshot.primary.sequence, 1);
        assert_eq!(snapshot.primary.boundary, "real_owner_boundary");
        assert!(snapshot.primary.detail_sha256.is_some());
        if point == 1 || point == 2 {
            assert_eq!(snapshot.secondary[0].private_context.status, "unavailable");
        }
        if point == 2 {
            assert!(collector.snapshot(None).retention.private_budget_exhausted);
        }
    }
}

#[test]
fn sink_callbacks_can_read_owned_snapshots_without_the_public_state_lock() {
    #[derive(Default)]
    struct Reentrant {
        collector: Mutex<Option<DiagnosticCollector>>,
        calls: AtomicU64,
    }
    impl Reentrant {
        fn snapshot(&self, bit: u64) {
            let collector = self.collector.lock().unwrap().as_ref().unwrap().clone();
            assert_eq!(
                collector.snapshot(None).schema,
                "rusty.quest.operation_diagnostics_collection.v1"
            );
            self.calls.fetch_or(bit, Ordering::Relaxed);
        }
    }
    impl PrivateFailureSink for Reentrant {
        fn minimum_context_bytes(&self) -> u64 {
            self.snapshot(1);
            1024
        }
        fn evict(&self, _: &str) -> Result<(), ()> {
            self.snapshot(4);
            Ok(())
        }
        fn retain(
            &self,
            id: &str,
            _: &[PrivateFailureChunk<'_>],
            max_bytes: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            self.snapshot(2);
            let envelope = vec![0; max_bytes as usize];
            Ok(PrivateContextDescriptor {
                status: "available".into(),
                context_id: Some(id.into()),
                bytes: max_bytes,
                sha256: Some(format!("{:x}", Sha256::digest(envelope))),
                truncated: false,
            })
        }
    }
    let sink = Arc::new(Reentrant::default());
    let collector = DiagnosticCollector::new(Some(sink.clone()));
    *sink.collector.lock().unwrap() = Some(collector.clone());
    let operation = collector.operation("start", Default::default());
    let (sender, receiver) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        for _ in 0..2 {
            operation.record(
                failure(DiagnosticPhase::Primary, "reentrant_sink").private_context(vec![
                    PrivateFailureChunk {
                        name: "error",
                        bytes: b"original owner error",
                    },
                ]),
            );
        }
        sender.send(()).unwrap();
    });
    assert!(
        receiver
            .recv_timeout(std::time::Duration::from_secs(5))
            .is_ok(),
        "sink callback deadlocked on public state lock"
    );
    assert_eq!(sink.calls.load(Ordering::Relaxed), 7);
    *sink.collector.lock().unwrap() = None;
}

#[test]
fn blocked_first_sink_preserves_first_primary_and_monotonic_cleanup_sequence() {
    struct Blocking {
        entered: std::sync::mpsc::Sender<()>,
        release: Mutex<std::sync::mpsc::Receiver<()>>,
        calls: AtomicU64,
    }
    impl PrivateFailureSink for Blocking {
        fn retain(
            &self,
            id: &str,
            _: &[PrivateFailureChunk<'_>],
            _: u64,
        ) -> Result<PrivateContextDescriptor, ()> {
            if self.calls.fetch_add(1, Ordering::Relaxed) == 0 {
                self.entered.send(()).unwrap();
                self.release
                    .lock()
                    .unwrap()
                    .recv_timeout(std::time::Duration::from_secs(5))
                    .unwrap();
            }
            let bytes = vec![0; 1024];
            Ok(PrivateContextDescriptor {
                status: "available".into(),
                context_id: Some(id.into()),
                bytes: bytes.len() as u64,
                sha256: Some(format!("{:x}", Sha256::digest(bytes))),
                truncated: false,
            })
        }
    }
    let (entered, seen) = std::sync::mpsc::channel();
    let (release, resume) = std::sync::mpsc::channel();
    let collector = DiagnosticCollector::new(Some(Arc::new(Blocking {
        entered,
        release: Mutex::new(resume),
        calls: AtomicU64::new(0),
    })));
    let operation = collector.operation("start", Default::default());
    let first = operation.clone();
    let a = std::thread::spawn(move || {
        first.record(
            failure(DiagnosticPhase::Primary, "first_owner_error").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: b"first error",
                },
            ]),
        )
    });
    seen.recv_timeout(std::time::Duration::from_secs(5))
        .unwrap();
    let later = operation.clone();
    let b = std::thread::spawn(move || {
        later.record(
            failure(DiagnosticPhase::Cleanup, "later_cleanup_error").private_context(vec![
                PrivateFailureChunk {
                    name: "error",
                    bytes: b"later error",
                },
            ]),
        )
    });
    assert_eq!(collector.inner.state.lock().unwrap().next_sequence, 1);
    release.send(()).unwrap();
    a.join().unwrap();
    b.join().unwrap();
    let observed = operation.snapshot().unwrap();
    assert_eq!(observed.primary.boundary, "first_owner_error");
    assert_eq!(observed.primary.sequence, 1);
    assert_eq!(observed.secondary[0].boundary, "later_cleanup_error");
    assert_eq!(observed.secondary[0].sequence, 2);
    assert_eq!(observed.secondary[0].phase, DiagnosticPhase::Cleanup);
}
