// Actual Broker issuance -> Composite placement -> signed owner dispatch.
// SDK resources and current route authority are strict host models; no device credit.
use super::super::*;
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use rusty_quest_media_stream_android::*;
use serde_json::json;
use std::sync::{Arc, Mutex};

const LOCAL: &str = "peer.source";
const REMOTE: &str = "peer.recipient";

#[derive(Clone)]
struct Key(&'static str, SigningKey);
impl OwnerDispatchSigner for Key {
    fn key_id(&self) -> &str {
        self.0
    }
    fn sign(&self, bytes: &[u8]) -> Result<[u8; 64], String> {
        Ok(self.1.sign(bytes).to_bytes())
    }
}
struct Clock;
impl OwnerDispatchClock for Clock {
    fn now_ms(&self) -> Result<u64, String> {
        Ok(4_100)
    }
}
struct Store;
impl OwnerDispatchReplayStore for Store {
    fn commit(&mut self, _: &OwnerDispatchReplaySnapshot) -> Result<(), String> {
        Ok(())
    }
}
struct Verifier(VerifyingKey);
impl OwnerDispatchAuthorityVerifier for Verifier {
    fn verify_current(
        &self,
        request: &OwnerDispatchRequest,
        bytes: &[u8],
        signature: &[u8; 64],
    ) -> Result<(), String> {
        self.0
            .verify_strict(bytes, &Signature::from_bytes(signature))
            .map_err(|_| "modeled enrolled request key")?;
        if request.signer_key_id != "key.source" || request.authority.authority_peer_id != LOCAL {
            return Err("modeled current route identity".into());
        }
        Ok(())
    }
}

struct Registry {
    generation: u64,
    owner: DeterministicAndroidMediaOwnerExecutor,
    calls: Arc<Mutex<Vec<(u64, AndroidMediaExecutionMode)>>>,
}
impl Registry {
    fn new(generation: u64, calls: Arc<Mutex<Vec<(u64, AndroidMediaExecutionMode)>>>) -> Self {
        Self {
            generation,
            owner: DeterministicAndroidMediaOwnerExecutor::new(generation).unwrap(),
            calls,
        }
    }
}
impl AuthenticatedOwnerRegistry for Registry {
    fn execute_and_verify(
        &mut self,
        authority: Option<&OwnerDispatchAuthorityProjection>,
        ticket: &AndroidMediaExecutionTicket,
        mode: AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String> {
        if authority.is_none() || ticket.executor_generation != self.generation {
            return Err("strict modeled registry generation".into());
        }
        let readback = self.owner.execute(ticket, mode)?;
        assert!(self.owner.verify(ticket, &readback));
        self.calls.lock().unwrap().push((self.generation, mode));
        let readback_json = serde_json::to_string(&readback).unwrap();
        Ok(AuthenticatedOwnerEffect {
            verified: VerifiedOwnerEffect {
                schema_id: VERIFIED_OWNER_EFFECT_SCHEMA.into(),
                receipt_id: readback.receipt_id.clone(),
                readback_sha256: format!("sha256:{}", sha256_hex(readback_json.as_bytes())),
                executor_generation: self.generation,
                provider_state_revision: readback.provider_state_revision,
                observed_state: readback.observed_state.clone(),
                terminal: ticket.operation == MediaStreamPlatformOperation::Stop
                    || mode == AndroidMediaExecutionMode::CompensateUncertain,
                provider_handle_id: readback.provider_handle_id.clone(),
                detail_sha256: format!("sha256:{}", "0".repeat(64)),
            },
            readback,
            readback_json,
        })
    }
}
struct Wire {
    server: OwnerDispatchServer<Verifier, Registry, Key>,
    admitted: Arc<Mutex<Vec<u64>>>,
}
impl OwnerDispatchTransport for Wire {
    fn exchange(&mut self, frame: &[u8], max: usize) -> Result<Vec<u8>, String> {
        let admitted = self.admitted.clone();
        let response = self.server.handle_frame_with_admission(frame, |request| {
            admitted
                .lock()
                .unwrap()
                .push(request.ticket.executor_generation);
            if request.ticket.executor_generation != 5 {
                return Err(format!(
                    "recipient installed generation 5 rejected ticket generation {}",
                    request.ticket.executor_generation
                ));
            }
            Ok(())
        })?;
        if response.len() > max {
            return Err("modeled transport bounds".into());
        }
        Ok(response)
    }
}

// The held proof contains real signed wire frames, plus the exact expected
// constructor and decoder request. A caller cannot substitute a naked integer.
struct PreparedProof {
    key: [u8; 32],
    view: Vec<u8>,
    ready: Vec<u8>,
    request: AndroidPreparedStartRequest,
    decoder: AndroidPreparedDecoderRequest,
}
impl PreparedProof {
    fn generation(&self, target: &str) -> Result<u64, String> {
        if target != REMOTE {
            return Err("prepared proof target differs".into());
        }
        let route = format!("sha256:{}", "5".repeat(64));
        let view =
            decode_prepared_start_frame(&self.view, &self.key, REMOTE, LOCAL, &route, 4_100)?;
        let request: AndroidPreparedStartRequest =
            serde_json::from_value(view.payload["view"].clone()).map_err(|_| "prepared view")?;
        if request != self.request {
            return Err("held prepared request changed".into());
        }
        request.validate()?;
        let ready =
            decode_prepared_start_frame(&self.ready, &self.key, REMOTE, LOCAL, &route, 4_100)?;
        if ready.sender_preparation_id != request.preparation_id
            || ready.receiver_preparation_id.as_deref()
                != Some(self.decoder.peer_preparation_id.as_str())
            || ready.payload["responder_view_sha256"] != request.digest()?
        {
            return Err("prepared ready view binding differs".into());
        }
        let receipt: AndroidPreparedDecoderReadback =
            serde_json::from_value(ready.payload["decoder_receipt"].clone())
                .map_err(|_| "prepared decoder receipt")?;
        receipt.validate(&request, &self.decoder)?;
        Ok(receipt.executor_generation)
    }
}
struct Projection(Arc<Mutex<PreparedProof>>);
impl CurrentOwnerProjectionSource for Projection {
    fn target_executor_generation(&self, target: &str) -> Result<u64, String> {
        self.0.lock().unwrap().generation(target)
    }

    fn current_projection(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        target: &str,
        _: AndroidMediaExecutionMode,
        _: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String> {
        if target == REMOTE {
            assert_eq!(self.0.lock().unwrap().generation(target)?, 5);
        }
        Ok(OwnerDispatchAuthorityProjection {
            schema_id: "rusty.quest.c1.owner_projection.v1".into(),
            authority_peer_id: LOCAL.into(),
            executor_peer_id: target.into(),
            peer_session_id: "session.modeled.current".into(),
            route_grant_id: "grant.modeled.current".into(),
            route_authority_revision: 1,
            authority_runtime_host_id: "host.source".into(),
            authority_provider_epoch_id: ticket.authority_epoch_id.clone(),
            platform_runtime_spec_id: "product.q".into(),
            authority_client_id: ticket.client_id.clone(),
            authority_runtime_lease_id: ticket.lease_id.clone(),
            signed_topology_sha256: format!("sha256:{}", "1".repeat(64)),
            route_configuration_sha256: format!("sha256:{}", "5".repeat(64)),
            route_authority_evidence_sha256: format!("sha256:{}", "3".repeat(64)),
            expires_at_ms: 9_000,
            authorization_kind: OwnerDispatchAuthorizationKind::CurrentRoute,
        })
    }
}
fn proof(key: &Key, action: &MediaStreamPlatformAction) -> PreparedProof {
    let providers: Vec<_> = action.owner_actions.iter().map(|o| json!({"owner_kind": format!("{:?}", o.selection.owner_kind), "owner_id": o.selection.owner_id, "provider_kind": o.selection.provider_kind, "resource_id": o.selection.resource_id})).collect();
    let target = json!({"$schema":"rusty.quest.embedded_duplex.peer_start_resource_target.v1","runtime_config_sha256":"c".repeat(64),"executor_generation":5,
        "product_binding":{"$schema":"rusty.quest.android.media.preparation_target.v1","product_id":"product.q","providers":providers},
        "source_dimensions":{"packed_width":200,"packed_height":100,"per_eye_width":100,"per_eye_height":100,"frame_rate_hz":30,"bitrate_bps":1000000,"left_camera_id":"left","right_camera_id":"right"},
        "own_capture_selected":true,"max_pair_delta_ns":30000000,"outgoing_runtime_spec_id":"product.q","incoming_runtime_spec_id":"product.w"});
    let policy = json!({"$schema":"rusty.quest.embedded_duplex.fresh_setup_build_policy.v1","selection":"source-bound-v2","policy":{},"policy_sha256":format!("sha256:{}","d".repeat(64)),"enrollment_selection_sha256":format!("sha256:{}","e".repeat(64))});
    let request = AndroidPreparedStartRequest {
        schema_id: "rusty.quest.embedded_duplex.prepared_start_request.v1".into(),
        preparation_id: "1".repeat(32),
        original_id: "b".repeat(32),
        app_process_generation: 3,
        app_record_sha256: "a".repeat(64),
        native_executor_generation: 5,
        config_sha256: "c".repeat(64),
        arm_binding: json!({"binding":"established","challenge":"f".repeat(32),"arm_generation":1,"native_process":27,"process_epoch":"1".repeat(32),"runtime_config_sha256":"c".repeat(64)}),
        configuration_binding: json!({"$schema":"rusty.quest.embedded_duplex.runtime_configuration_binding.v1","config_sha256":"c".repeat(64),"app_process_generation":3,"app_record_sha256":"a".repeat(64),"native_executor_generation":5,"local_peer_id":REMOTE,"fresh_setup_build_policy":policy}),
        resource_target_sha256: prepared_start_digest(
            "rusty.quest.embedded_duplex.peer_start_resource_target.v1",
            &target,
        )
        .unwrap(),
        resource_target: target,
    };
    let decoder = AndroidPreparedDecoderRequest {
        schema_id: "rusty.quest.android.media.prepared_decoder_request.v1".into(),
        preparation_id: request.preparation_id.clone(),
        request_sha256: request.digest().unwrap(),
        peer_preparation_id: "2".repeat(32),
        peer_sender_receipt_sha256: format!("sha256:{}", "3".repeat(64)),
        peer_format: AndroidPreparedEncodedFormat {
            schema_id: "rusty.quest.android.media.prepared_encoded_format.v1".into(),
            mime: "video/avc".into(),
            width: 200,
            height: 100,
            frame_rate_hz: 30,
            csd_0_hex: "00000167".into(),
            csd_1_hex: Some("00000168".into()),
            profile: None,
            level: None,
        },
    };
    let receipt = AndroidPreparedDecoderReadback {
        schema_id: "rusty.quest.android.media.prepared_decoder_readback.v1".into(),
        preparation_id: request.preparation_id.clone(),
        executor_generation: 5,
        request_sha256: request.digest().unwrap(),
        resource_target_sha256: request.resource_target_sha256.clone(),
        provider_handle_id: "modeled.actual.decoder".into(),
        provider_state_revision: 1,
        stage: "decoder_ready".into(),
        peer_sender_receipt_sha256: decoder.peer_sender_receipt_sha256.clone(),
        delivery_gate_closed: true,
        peer_delivery_count: 0,
    };
    receipt.validate(&request, &decoder).unwrap();
    let frame = |kind: &str, payload, receiver: Option<String>| {
        let body = AndroidPreparedStartFrameBody {
            schema: "rusty.quest.embedded_duplex.prepared_start_frame.v1".into(),
            kind: kind.into(),
            request_id: receiver
                .clone()
                .unwrap_or_else(|| request.preparation_id.clone()),
            sender_preparation_id: request.preparation_id.clone(),
            receiver_preparation_id: receiver,
            sender_peer_id: REMOTE.into(),
            receiver_peer_id: LOCAL.into(),
            route_configuration_sha256: format!("sha256:{}", "5".repeat(64)),
            issued_at_ms: 1,
            payload,
        };
        let signature = key
            .1
            .sign(&prepared_start_frame_signing_bytes(&body).unwrap())
            .to_bytes();
        encode_prepared_start_frame(body, signature).unwrap()
    };
    let view = frame("view", json!({"view":request}), None);
    let ready = frame(
        "decoder_prepared",
        json!({"initiator_view_sha256":format!("sha256:{}", "4".repeat(64)),"responder_view_sha256":request.digest().unwrap(),"decoder_receipt":receipt}),
        Some(decoder.peer_preparation_id.clone()),
    );
    PreparedProof {
        key: key.1.verifying_key().to_bytes(),
        view,
        ready,
        request,
        decoder,
    }
}
fn accepted_start() -> (QuestBrokerAuthorityRuntime, MediaStreamPlatformAction) {
    let kind = QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni;
    let mut runtime = super::runtime_for(
        kind.clone(),
        vec![super::ManifoldBrokerFeature::MediaSession],
        "command.media.session.start",
        true,
        "77",
    );
    let (use_id, token_id) = super::admit(&mut runtime, "command.media.session.start");
    let request = super::mutation(
        &runtime,
        kind,
        use_id,
        token_id,
        "command.media.session.start",
        Some("lease.broker.media-session.quest.runtime"),
    );
    let response = runtime.handle_server_mutation(&request, 4_000).unwrap();
    assert!(response.accepted);
    let action = response.platform_action.unwrap();
    assert_eq!(action.owner_actions.len(), 7);
    (runtime, action)
}

struct Harness {
    executor: CompositeAndroidMediaOwnerExecutor,
    proof: Arc<Mutex<PreparedProof>>,
    local_calls: Arc<Mutex<Vec<(u64, AndroidMediaExecutionMode)>>>,
    remote_calls: Arc<Mutex<Vec<(u64, AndroidMediaExecutionMode)>>>,
    admitted: Arc<Mutex<Vec<u64>>>,
    nonce: u64,
}

fn harness(action: &MediaStreamPlatformAction) -> Harness {
    let source = Key("key.source", SigningKey::from_bytes(&[3; 32]));
    let recipient = Key("key.recipient", SigningKey::from_bytes(&[4; 32]));
    let prepared = proof(&recipient, action);
    assert_eq!(prepared.generation(REMOTE).unwrap(), 5);
    let prepared = Arc::new(Mutex::new(prepared));
    let local_calls = Arc::new(Mutex::new(Vec::new()));
    let remote_calls = Arc::new(Mutex::new(Vec::new()));
    let admitted = Arc::new(Mutex::new(Vec::new()));
    let server = OwnerDispatchServer::restore(
        REMOTE.into(),
        Verifier(source.1.verifying_key()),
        Registry::new(5, remote_calls.clone()),
        recipient.clone(),
        Box::new(Clock),
        OwnerDispatchReplaySnapshot::default(),
        Box::new(Store),
    )
    .unwrap();
    let remote = RemoteOwnerDispatchExecutor::new(
        Wire {
            server,
            admitted: admitted.clone(),
        },
        source,
        recipient.1.verifying_key().to_bytes(),
        recipient.0.into(),
    )
    .unwrap();
    let placements = action
        .owner_actions
        .iter()
        .map(|o| AndroidMediaOwnerPlacement {
            owner_kind: o.selection.owner_kind,
            owner_id: o.selection.owner_id.clone(),
            provider_kind: o.selection.provider_kind.clone(),
            resource_id: o.selection.resource_id.clone(),
            target: if o.selection.owner_kind == MediaStreamOwnerKind::Sink {
                AndroidMediaOwnerPlacementTarget::Remote {
                    peer_id: REMOTE.into(),
                }
            } else {
                AndroidMediaOwnerPlacementTarget::Local
            },
        })
        .collect();
    let executor = CompositeAndroidMediaOwnerExecutor::new(
        7,
        LOCAL.into(),
        placements,
        Box::new(Registry::new(7, local_calls.clone())),
        Box::new(remote),
        Box::new(Projection(prepared.clone())),
        Box::new(Clock),
    )
    .unwrap();
    Harness {
        executor,
        proof: prepared,
        local_calls,
        remote_calls,
        admitted,
        nonce: 1,
    }
}

fn execute_owner(
    harness: &mut Harness,
    action: &MediaStreamPlatformAction,
    kind: MediaStreamOwnerKind,
    mode: AndroidMediaExecutionMode,
) -> Result<MediaStreamOwnerProviderReadback, String> {
    let index = action
        .owner_actions
        .iter()
        .position(|o| o.selection.owner_kind == kind)
        .unwrap();
    let owner = action.owner_actions[index].clone();
    let capability = next_media_execution_capability(
        action,
        &owner,
        (index + 1) as u32,
        harness.executor.executor_generation(),
        &mut harness.nonce,
    )
    .unwrap();
    let mut provider = QuestBrokerAndroidMediaOwnerProvider::new(
        owner.clone(),
        (index + 1) as u32,
        capability,
        &mut harness.executor,
    );
    let readback = match mode {
        AndroidMediaExecutionMode::Execute => provider.execute_and_readback(action, &owner)?,
        AndroidMediaExecutionMode::CompensateUncertain => {
            provider.compensate_uncertain_attempt(action, &owner)?
        }
    };
    assert!(provider.verify_readback(action, &owner, &readback));
    Ok(readback)
}

#[test]
fn broker_issued_remote_ticket_uses_authenticated_recipient_generation() {
    let (_, action) = accepted_start();
    let mut harness = harness(&action);
    // Same provider issues both local and remote tickets; capability ownership
    // stays at source generation 7 and is never rewritten after signing.
    for kind in [MediaStreamOwnerKind::Source, MediaStreamOwnerKind::Sink] {
        let result = execute_owner(
            &mut harness,
            &action,
            kind,
            AndroidMediaExecutionMode::Execute,
        );
        if kind == MediaStreamOwnerKind::Sink {
            eprintln!(
                "recipient admission observed {:?}; effects {:?}; result {:?}",
                harness.admitted.lock().unwrap(),
                harness.remote_calls.lock().unwrap(),
                result
            );
        }
        result.expect("recipient generation 5 must execute despite source generation 7");
    }
    assert_eq!(
        *harness.local_calls.lock().unwrap(),
        vec![(7, AndroidMediaExecutionMode::Execute)]
    );
    assert_eq!(
        *harness.remote_calls.lock().unwrap(),
        vec![(5, AndroidMediaExecutionMode::Execute)]
    );
    assert_eq!(*harness.admitted.lock().unwrap(), vec![5]);
}

fn accepted_stop(mut runtime: QuestBrokerAuthorityRuntime) -> MediaStreamPlatformAction {
    // Complete the separately retained real Broker pending action before asking
    // for Stop. This owner is a host resource model, not a cloned authority.
    let mut executor = DeterministicAndroidMediaOwnerExecutor::new(7).unwrap();
    runtime
        .complete_media_session_action(
            &QuestBrokerMediaCompletionRequest {
                client_id: super::identity().client_id,
            },
            4_100,
            &mut executor,
            &mut 1,
        )
        .unwrap();
    let revision = runtime
        .runtime
        .read()
        .unwrap()
        .admission_snapshot()
        .authority_revision;
    let token = runtime
        .execute_admission(QuestBrokerAdmissionOperation::IssueToken {
            schema_id: super::QUEST_ADMISSION_OPERATION_SCHEMA.into(),
            caller: super::caller(),
            request_id: super::id("request.recipient.stop.issue"),
            expected_authority_revision: revision,
            requested_capabilities: vec![super::command_capability(&super::id(
                "command.media.session.stop",
            ))],
            requested_token_ttl_ms: 20_000,
            issued_at_ms: 5_000,
            expires_at_ms: 10_000,
            entropy_hex: "08".repeat(32),
        })
        .unwrap()
        .receipt
        .token
        .unwrap();
    let use_id = super::id("request.recipient.stop.use");
    let revision = runtime
        .runtime
        .read()
        .unwrap()
        .admission_snapshot()
        .authority_revision;
    let authorized = runtime
        .execute_admission(QuestBrokerAdmissionOperation::AuthorizeUse {
            schema_id: super::QUEST_ADMISSION_OPERATION_SCHEMA.into(),
            caller: super::caller(),
            request_id: use_id.clone(),
            expected_authority_revision: revision,
            token_id: token.token_id.clone(),
            capability_id: super::command_capability(&super::id("command.media.session.stop")),
            issued_at_ms: 5_100,
            expires_at_ms: 9_000,
        })
        .unwrap();
    assert!(authorized.receipt.applied);
    let mut request = super::mutation(
        &runtime,
        QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni,
        use_id,
        token.token_id,
        "command.media.session.stop",
        Some("lease.broker.media-session.quest.runtime"),
    );
    {
        let broker = runtime.runtime.read().unwrap();
        request.expected_admission_authority_revision =
            broker.admission_snapshot().authority_revision;
        request.command.expected_authority_revision = broker.host_snapshot().authority_revision;
    }
    request.command.request_id = super::id("request.recipient.stop.command");
    request.command.issued_at_ms = 5_200;
    let response = runtime.handle_server_mutation(&request, 5_300).unwrap();
    assert!(response.accepted);
    let action = response.platform_action.unwrap();
    assert_eq!(action.operation, MediaStreamPlatformOperation::Stop);
    action
}

#[test]
fn ordinary_stop_and_compensation_keep_recipient_generation_and_refuse_stale_preparation() {
    let (runtime, start) = accepted_start();
    let mut harness = harness(&start);
    execute_owner(
        &mut harness,
        &start,
        MediaStreamOwnerKind::Sink,
        AndroidMediaExecutionMode::Execute,
    )
    .unwrap();
    let stop = accepted_stop(runtime);
    let stopped = execute_owner(
        &mut harness,
        &stop,
        MediaStreamOwnerKind::Sink,
        AndroidMediaExecutionMode::Execute,
    )
    .unwrap();
    assert_eq!(stopped.observed_state, "stopped");
    execute_owner(
        &mut harness,
        &stop,
        MediaStreamOwnerKind::Sink,
        AndroidMediaExecutionMode::CompensateUncertain,
    )
    .unwrap();
    assert_eq!(
        *harness.remote_calls.lock().unwrap(),
        vec![
            (5, AndroidMediaExecutionMode::Execute),
            (5, AndroidMediaExecutionMode::Execute),
            (5, AndroidMediaExecutionMode::CompensateUncertain)
        ]
    );
    assert_eq!(*harness.admitted.lock().unwrap(), vec![5, 5, 5]);
    assert!(harness.local_calls.lock().unwrap().is_empty());

    // A newly held actor request cannot reuse old signed preparation bytes.
    // Refusal happens before remote transport or any registry callback.
    harness
        .proof
        .lock()
        .unwrap()
        .request
        .native_executor_generation = 6;
    let failure = execute_owner(
        &mut harness,
        &stop,
        MediaStreamOwnerKind::Sink,
        AndroidMediaExecutionMode::CompensateUncertain,
    )
    .unwrap_err();
    assert!(
        failure.contains("held prepared request changed"),
        "{failure}"
    );
    assert_eq!(*harness.admitted.lock().unwrap(), vec![5, 5, 5]);
    assert_eq!(harness.remote_calls.lock().unwrap().len(), 3);

    // Correct actor fields cannot authorize preparation signed by a foreign key.
    {
        let mut proof = harness.proof.lock().unwrap();
        proof.request.native_executor_generation = 5;
        proof.key = SigningKey::from_bytes(&[0xe5; 32]).verifying_key().to_bytes();
    }
    let failure = execute_owner(
        &mut harness,
        &stop,
        MediaStreamOwnerKind::Sink,
        AndroidMediaExecutionMode::CompensateUncertain,
    )
    .unwrap_err();
    assert_eq!(failure, "prepared metadata signature");
    assert_eq!(*harness.admitted.lock().unwrap(), vec![5, 5, 5]);
    assert_eq!(harness.remote_calls.lock().unwrap().len(), 3);
}
