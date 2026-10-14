use super::*;

#[test]
fn composite_consumes_target_proof_and_verifies_source_projection_once() {
    let calls = Arc::new(Mutex::new(0));
    let mut source = arm_ticket();
    let families = [
        MediaStreamOwnerKind::Source,
        MediaStreamOwnerKind::Processor,
        MediaStreamOwnerKind::Route,
        MediaStreamOwnerKind::Socket,
        MediaStreamOwnerKind::Codec,
        MediaStreamOwnerKind::Sink,
        MediaStreamOwnerKind::Cleanup,
    ];
    let placements = families
        .into_iter()
        .map(|kind| AndroidMediaOwnerPlacement {
            owner_kind: kind,
            owner_id: format!("owner.{kind:?}"),
            provider_kind: format!("provider.{kind:?}"),
            resource_id: format!("resource.{kind:?}"),
            target: if kind == MediaStreamOwnerKind::Sink {
                AndroidMediaOwnerPlacementTarget::Remote {
                    peer_id: "peer.b".into(),
                }
            } else {
                AndroidMediaOwnerPlacementTarget::Local
            },
        })
        .collect::<Vec<_>>();
    source.owner_id = "owner.Sink".into();
    source.provider_kind = "provider.Sink".into();
    source.resource_id = "resource.Sink".into();
    let client = generation_client(generation_server(
        Arc::new(std::sync::atomic::AtomicU64::new(29)),
        calls.clone(),
        Arc::new(Mutex::new(Vec::new())),
        false,
        OwnerDispatchReplaySnapshot::default(),
    ));
    let mut executor = CompositeAndroidMediaOwnerExecutor::new(
        30,
        "peer.a".into(),
        placements,
        Box::new(TestRegistry {
            calls: Arc::new(Mutex::new(0)),
        }),
        Box::new(client),
        Box::new(TestProjection),
        Box::new(TestClock),
    )
    .unwrap();
    let wrapper = crate::AndroidMediaOwnerExecutor::execute(
        &mut executor,
        &source,
        AndroidMediaExecutionMode::Execute,
    )
    .unwrap();
    assert_eq!(wrapper.executor_generation, 30);
    assert!(wrapper.remote_dispatch.is_some());
    crate::validate_readback(&source, &wrapper).unwrap();
    assert!(crate::AndroidMediaOwnerExecutor::verify(
        &executor, &source, &wrapper
    ));
    assert!(!crate::AndroidMediaOwnerExecutor::verify(
        &executor, &source, &wrapper
    ));
    assert_eq!(*calls.lock().unwrap(), 1);
    let mut stale = source;
    stale.executor_generation = 29;
    assert!(crate::AndroidMediaOwnerExecutor::execute(
        &mut executor,
        &stale,
        AndroidMediaExecutionMode::Execute
    )
    .is_err());
    assert_eq!(*calls.lock().unwrap(), 1);
}

#[test]
fn original_binding_rejects_missing_cleanup_foreign_abort_and_new_dispatch_replay() {
    let generation = Arc::new(std::sync::atomic::AtomicU64::new(29));
    let calls = Arc::new(Mutex::new(0));
    let mut server = generation_server(
        generation.clone(),
        calls.clone(),
        Arc::new(Mutex::new(Vec::new())),
        false,
        OwnerDispatchReplaySnapshot::default(),
    );
    let mut preparer = generation_client(generation_server(
        generation,
        Arc::new(Mutex::new(0)),
        Arc::new(Mutex::new(Vec::new())),
        false,
        OwnerDispatchReplaySnapshot::default(),
    ));
    let source = arm_ticket();
    let compensation = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            source.clone(),
            AndroidMediaExecutionMode::CompensateUncertain,
            1_000,
        )
        .unwrap();
    assert!(server
        .handle_frame(&encode_request_frame(&compensation).unwrap())
        .is_err());
    let forward = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            source.clone(),
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    server
        .handle_frame(&encode_request_frame(&forward).unwrap())
        .unwrap();
    let duplicate = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            source.clone(),
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    assert!(server
        .handle_frame(&encode_request_frame(&duplicate).unwrap())
        .is_err());
    let mut abort = source;
    abort.action_id = "foreign.action.abort".into();
    abort.action_kind = MediaStreamOwnerActionKind::Stop;
    let request = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            abort,
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    assert!(server
        .handle_frame(&encode_request_frame(&request).unwrap())
        .is_err());
    assert_eq!(*calls.lock().unwrap(), 1);
    let mut damaged = server.replay_snapshot();
    damaged
        .target_bindings
        .values_mut()
        .next()
        .unwrap()
        .target
        .capability
        .push('x');
    let restored = OwnerDispatchServer::restore(
        "peer.b".into(),
        TestVerifier {
            key: SigningKey::from_bytes(&[1; 32]).verifying_key(),
        },
        TestRegistry { calls },
        TestSigner {
            id: "target.key".into(),
            key: SigningKey::from_bytes(&[2; 32]),
        },
        Box::new(TestClock),
        damaged,
        Box::new(TestStore::default()),
    );
    assert!(restored.is_err());
}

struct GenerationRegistry {
    generation: Arc<std::sync::atomic::AtomicU64>,
    calls: Arc<Mutex<u64>>,
    tickets: Arc<Mutex<Vec<AndroidMediaExecutionTicket>>>,
    fail_first: bool,
}
impl AuthenticatedOwnerRegistry for GenerationRegistry {
    fn current_executor_generation(&self) -> Result<u64, String> {
        Ok(self.generation.load(std::sync::atomic::Ordering::SeqCst))
    }
    fn execute_and_verify(
        &mut self,
        authority: Option<&OwnerDispatchAuthorityProjection>,
        target: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        if target.executor_generation != self.current_executor_generation()? {
            return Err("STALE_GENERATION".into());
        }
        self.tickets.lock().unwrap().push(target.clone());
        if self.fail_first {
            self.fail_first = false;
            *self.calls.lock().unwrap() += 1;
            return Err("provider effect uncertain".into());
        }
        let mut effect = TestRegistry {
            calls: self.calls.clone(),
        }
        .execute_and_verify(authority, target, mode)?;
        effect.verified.terminal = mode == AndroidMediaExecutionMode::CompensateUncertain
            || matches!(
                target.action_kind,
                MediaStreamOwnerActionKind::Stop | MediaStreamOwnerActionKind::Cleanup
            );
        effect.readback.observed_state = if effect.verified.terminal {
            "stopped"
        } else {
            "receiver_armed"
        }
        .into();
        effect.verified.observed_state = effect.readback.observed_state.clone();
        effect.readback_json = serde_json::to_string(&effect.readback).unwrap();
        effect.verified.readback_sha256 = sha256_prefixed(effect.readback_json.as_bytes());
        Ok(effect)
    }
}
type GenerationServer = OwnerDispatchServer<TestVerifier, GenerationRegistry, TestSigner>;
struct GenerationLoopback(GenerationServer);
impl OwnerDispatchTransport for GenerationLoopback {
    fn exchange(&mut self, frame: &[u8], _: usize) -> Result<Vec<u8>, String> {
        self.0.handle_frame(frame)
    }
}
fn generation_server(
    generation: Arc<std::sync::atomic::AtomicU64>,
    calls: Arc<Mutex<u64>>,
    tickets: Arc<Mutex<Vec<AndroidMediaExecutionTicket>>>,
    fail_first: bool,
    replay: OwnerDispatchReplaySnapshot,
) -> GenerationServer {
    OwnerDispatchServer::restore(
        "peer.b".into(),
        TestVerifier {
            key: SigningKey::from_bytes(&[1; 32]).verifying_key(),
        },
        GenerationRegistry {
            generation,
            calls,
            tickets,
            fail_first,
        },
        TestSigner {
            id: "target.key".into(),
            key: SigningKey::from_bytes(&[2; 32]),
        },
        Box::new(TestClock),
        replay,
        Box::new(TestStore::default()),
    )
    .unwrap()
}
fn generation_client(
    server: GenerationServer,
) -> RemoteOwnerDispatchExecutor<GenerationLoopback, TestSigner> {
    RemoteOwnerDispatchExecutor::new(
        GenerationLoopback(server),
        TestSigner {
            id: "source.key".into(),
            key: SigningKey::from_bytes(&[1; 32]),
        },
        SigningKey::from_bytes(&[2; 32]).verifying_key().to_bytes(),
        "target.key".into(),
    )
    .unwrap()
}
fn arm_ticket() -> AndroidMediaExecutionTicket {
    let mut t = ticket();
    t.executor_generation = 30;
    t.owner_kind = MediaStreamOwnerKind::Sink;
    t.action_kind = MediaStreamOwnerActionKind::ArmReceiver;
    t
}

#[test]
fn unequal_native_generations_preserve_source_and_actual_target_proof() {
    for target_generation in [29, 30] {
        let calls = Arc::new(Mutex::new(0));
        let tickets = Arc::new(Mutex::new(Vec::new()));
        let server = generation_server(
            Arc::new(std::sync::atomic::AtomicU64::new(target_generation)),
            calls.clone(),
            tickets.clone(),
            false,
            OwnerDispatchReplaySnapshot::default(),
        );
        let mut client = generation_client(server);
        let source = arm_ticket();
        let effect = client
            .execute(
                "peer.b".into(),
                projection(),
                source.clone(),
                AndroidMediaExecutionMode::Execute,
                1_000,
            )
            .unwrap();
        assert_eq!(effect.readback.executor_generation, target_generation);
        assert_eq!(effect.verified.executor_generation, target_generation);
        let proof = effect.remote_dispatch.clone().unwrap();
        assert_eq!(proof.request.ticket, source);
        let wrapper = proof
            .source_readback(
                &source,
                "target.key",
                &SigningKey::from_bytes(&[2; 32]).verifying_key().to_bytes(),
            )
            .unwrap();
        assert_eq!(wrapper.executor_generation, 30);
        crate::validate_readback(&source, &wrapper).unwrap();
        assert_eq!(*calls.lock().unwrap(), 1);
        let mut expected = source;
        expected.executor_generation = target_generation;
        assert_eq!(tickets.lock().unwrap()[0], expected);
    }
}

#[test]
fn uncertain_arm_compensation_and_abort_stop_reuse_bound_target_incarnation() {
    let generation = Arc::new(std::sync::atomic::AtomicU64::new(29));
    let calls = Arc::new(Mutex::new(0));
    let tickets = Arc::new(Mutex::new(Vec::new()));
    let mut server = generation_server(
        generation.clone(),
        calls.clone(),
        tickets.clone(),
        true,
        OwnerDispatchReplaySnapshot::default(),
    );
    let mut preparer = generation_client(generation_server(
        generation.clone(),
        Arc::new(Mutex::new(0)),
        Arc::new(Mutex::new(Vec::new())),
        false,
        OwnerDispatchReplaySnapshot::default(),
    ));
    let source = arm_ticket();
    let forward = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            source.clone(),
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    let bytes = server
        .handle_frame(&encode_request_frame(&forward).unwrap())
        .unwrap();
    assert_eq!(
        decode_response_frame(&bytes).unwrap().status,
        OwnerDispatchStatus::Uncertain
    );
    let snapshot = server.replay_snapshot();
    assert_eq!(
        snapshot
            .target_bindings
            .values()
            .next()
            .unwrap()
            .target
            .executor_generation,
        29
    );
    let mut server = generation_server(
        generation.clone(),
        calls.clone(),
        tickets.clone(),
        false,
        snapshot,
    );
    // Replay is an observation; it never executes again.
    assert_eq!(
        server
            .handle_frame(&encode_request_frame(&forward).unwrap())
            .unwrap(),
        bytes
    );
    let compensation = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            source.clone(),
            AndroidMediaExecutionMode::CompensateUncertain,
            1_000,
        )
        .unwrap();
    assert_eq!(
        decode_response_frame(
            &server
                .handle_frame(&encode_request_frame(&compensation).unwrap())
                .unwrap()
        )
        .unwrap()
        .status,
        OwnerDispatchStatus::Completed
    );
    let mut abort = source.clone();
    abort.action_id.push_str(".abort");
    abort.action_kind = MediaStreamOwnerActionKind::Stop;
    abort.capability = "capability.abort".into();
    let request = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            abort.clone(),
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    assert_eq!(
        decode_response_frame(
            &server
                .handle_frame(&encode_request_frame(&request).unwrap())
                .unwrap()
        )
        .unwrap()
        .status,
        OwnerDispatchStatus::Completed
    );
    let mut stop = abort;
    stop.action_id = "action.stop".into();
    stop.operation = MediaStreamPlatformOperation::Stop;
    stop.capability = "capability.stop".into();
    let request = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            stop,
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    assert_eq!(
        decode_response_frame(
            &server
                .handle_frame(&encode_request_frame(&request).unwrap())
                .unwrap()
        )
        .unwrap()
        .status,
        OwnerDispatchStatus::Completed
    );
    assert!(tickets
        .lock()
        .unwrap()
        .iter()
        .all(|t| t.executor_generation == 29));
    generation.store(31, std::sync::atomic::Ordering::SeqCst);
    let request = preparer
        .prepare_request(
            "peer.b".into(),
            projection(),
            source,
            AndroidMediaExecutionMode::CompensateUncertain,
            1_000,
        )
        .unwrap();
    assert!(server
        .handle_frame(&encode_request_frame(&request).unwrap())
        .is_err());
    assert_eq!(*calls.lock().unwrap(), 4);
}

#[test]
fn uncertain_ordinary_stop_compensates_exact_attempt_without_new_target_binding() {
    let generation = Arc::new(std::sync::atomic::AtomicU64::new(29));
    let calls = Arc::new(Mutex::new(0));
    let tickets = Arc::new(Mutex::new(Vec::new()));
    let mut client = generation_client(generation_server(
        generation,
        calls.clone(),
        tickets.clone(),
        false,
        OwnerDispatchReplaySnapshot::default(),
    ));
    let source = arm_ticket();
    client
        .execute(
            "peer.b".into(),
            projection(),
            source.clone(),
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    client.transport.0.registry.fail_first = true;
    let mut stop = source;
    stop.action_id = "action.stop".into();
    stop.operation = MediaStreamPlatformOperation::Stop;
    stop.action_kind = MediaStreamOwnerActionKind::Stop;
    stop.capability = "capability.stop".into();
    assert!(client
        .execute(
            "peer.b".into(),
            projection(),
            stop.clone(),
            AndroidMediaExecutionMode::Execute,
            1_000
        )
        .is_err());
    let binding = client
        .transport
        .0
        .replay_snapshot()
        .target_bindings
        .values()
        .next()
        .unwrap()
        .clone();
    assert_eq!(binding.cleanup_source, Some(stop.clone()));
    assert!(!binding.terminal);
    let mut foreign = stop.clone();
    foreign.capability.push('x');
    assert!(client
        .execute(
            "peer.b".into(),
            projection(),
            foreign,
            AndroidMediaExecutionMode::CompensateUncertain,
            1_000
        )
        .is_err());
    let effect = client
        .execute(
            "peer.b".into(),
            projection(),
            stop,
            AndroidMediaExecutionMode::CompensateUncertain,
            1_000,
        )
        .unwrap();
    assert!(effect.verified.terminal);
    assert_eq!(effect.verified.executor_generation, 29);
    assert_eq!(*calls.lock().unwrap(), 3);
    assert!(tickets
        .lock()
        .unwrap()
        .iter()
        .all(|t| t.executor_generation == 29));
}

#[test]
fn remote_target_and_raw_readback_proofs_reject_tampering_and_foreign_keys() {
    let mut client = generation_client(generation_server(
        Arc::new(std::sync::atomic::AtomicU64::new(29)),
        Arc::new(Mutex::new(0)),
        Arc::new(Mutex::new(Vec::new())),
        false,
        OwnerDispatchReplaySnapshot::default(),
    ));
    let source = arm_ticket();
    let effect = client
        .execute(
            "peer.b".into(),
            projection(),
            source.clone(),
            AndroidMediaExecutionMode::Execute,
            1_000,
        )
        .unwrap();
    let proof = effect.remote_dispatch.unwrap();
    let key = SigningKey::from_bytes(&[2; 32]);
    let mut altered = proof.clone();
    altered.request.ticket.capability.push('x');
    assert!(altered
        .verify(&source, "target.key", key.verifying_key().as_bytes())
        .is_err());
    for field in [0, 1, 2] {
        let mut altered = proof.clone();
        let mut response = decode_response_frame(&altered.response_bytes).unwrap();
        match field {
            0 => response
                .target_ticket
                .as_mut()
                .unwrap()
                .capability
                .push('x'),
            1 => {
                response
                    .effect
                    .as_mut()
                    .unwrap()
                    .verified
                    .executor_generation = 30
            }
            _ => response.effect.as_mut().unwrap().readback_json.push(' '),
        }
        response.signature_base64 = encode_signature_base64(
            &key.sign(&response_signing_bytes(&response).unwrap())
                .to_bytes(),
        );
        altered.response_bytes = encode_response_frame(&response).unwrap();
        assert!(altered
            .verify(&source, "target.key", key.verifying_key().as_bytes())
            .is_err());
    }
    let foreign = SigningKey::from_bytes(&[9; 32]);
    let mut altered = proof.clone();
    altered.enrolled_target_key = foreign.verifying_key().to_bytes();
    let mut response = decode_response_frame(&altered.response_bytes).unwrap();
    response.signature_base64 = encode_signature_base64(
        &foreign
            .sign(&response_signing_bytes(&response).unwrap())
            .to_bytes(),
    );
    altered.response_bytes = encode_response_frame(&response).unwrap();
    assert!(altered
        .verify(&source, "target.key", key.verifying_key().as_bytes())
        .is_err());
    let mut wrapper = proof
        .clone()
        .source_readback(&source, "target.key", key.verifying_key().as_bytes())
        .unwrap();
    wrapper.provider_handle_id.push('x');
    assert!(crate::validate_readback(&source, &wrapper).is_err());
}
