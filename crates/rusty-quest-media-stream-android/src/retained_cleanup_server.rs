//! A v2 target-side dispatch gate. Authority and target are derived locally;
//! a signed request never supplies its own oracle.

use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};

use crate::owner_dispatch::{
    decode_signature_base64, encode_signature_base64, validate_effect, AuthenticatedOwnerEffect,
    OwnerDispatchClock, OwnerDispatchSigner, OwnerDispatchStatus,
};
use crate::{
    retained_cleanup_request_sha256, verify_signed_retained_cleanup_request,
    AndroidMediaExecutionTicket, RetainedCleanupAuthorityProjection,
    RetainedCleanupDispatchRequest, RetainedCleanupReplayDecision, RetainedCleanupReplaySnapshot,
    RetainedCleanupReplayStore,
};

/// Signed v2 response schema.
pub const RETAINED_CLEANUP_DISPATCH_RESPONSE_SCHEMA: &str =
    "rusty.quest.android.media.retained_cleanup_dispatch_response.v2";
const RESPONSE_DOMAIN: &[u8] =
    b"rusty.quest.android.media.retained_cleanup_dispatch.v2\0response\0";

/// Independent target-side authority, obtained from retained owner state and
/// current peer/Broker evidence. The request is only a lookup hint.
pub struct RetainedCleanupLiveSource {
    /// Fresh target-side projection.
    pub projection: RetainedCleanupAuthorityProjection,
    /// Fresh Stop ticket derived from retained native action/effect state.
    pub expected_target: AndroidMediaExecutionTicket,
    /// Current executor generation, never copied from an old Start ticket.
    pub executor_generation: u64,
    /// Currently enrolled coordinator key ID.
    pub signer_key_id: String,
    /// Currently enrolled coordinator public key.
    pub signer_key: [u8; 32],
}

/// Source authority that must not treat request fields as proof.
pub trait RetainedCleanupAuthoritySource {
    /// Derive all live evidence independently of the request payload.
    ///
    /// # Errors
    /// Returns an error if original target or fresh requester cannot be joined.
    fn current_source(
        &self,
        request: &RetainedCleanupDispatchRequest,
        now_ms: u64,
    ) -> Result<RetainedCleanupLiveSource, String>;
}

/// Registry callback exclusively for verified Stop or compensation.
pub trait RetainedCleanupRegistry {
    /// Execute or compensate the exact locally matched Stop ticket.
    ///
    /// # Errors
    /// Returns an error if the provider effect or readback is uncertain.
    fn execute_and_verify(
        &mut self,
        authority: &RetainedCleanupAuthorityProjection,
        expected_target: &AndroidMediaExecutionTicket,
        mode: crate::AndroidMediaExecutionMode,
    ) -> Result<AuthenticatedOwnerEffect, String>;
}

/// Exact signed terminal response for one cleanup request.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RetainedCleanupDispatchResponse {
    /// Response schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Exact stable request identity.
    pub dispatch_id: String,
    /// Peer whose owner registry produced this response.
    pub executor_peer_id: String,
    /// Hash of canonical reserialized signed request fields.
    pub request_sha256: String,
    /// Completed, uncertain, or rejected.
    pub status: OwnerDispatchStatus,
    /// Java-verified provider effect only for completion.
    pub effect: Option<AuthenticatedOwnerEffect>,
    /// Closed reason for uncertainty.
    pub failure: Option<String>,
    /// Executor signing identity.
    pub signer_key_id: String,
    /// Standard padded Base64 Ed25519 signature.
    pub signature_base64: String,
}

/// Domain-separated response bytes. The signature itself is excluded.
pub fn retained_cleanup_response_signing_bytes(
    response: &RetainedCleanupDispatchResponse,
) -> Result<Vec<u8>, String> {
    let mut unsigned = response.clone();
    unsigned.signature_base64.clear();
    let payload = serde_json::to_vec(&unsigned).map_err(|_| "cleanup response encode")?;
    let mut bytes = Vec::with_capacity(RESPONSE_DOMAIN.len() + payload.len());
    bytes.extend_from_slice(RESPONSE_DOMAIN);
    bytes.extend_from_slice(&payload);
    Ok(bytes)
}

/// Verifies the exact target-signed response before source-side adoption.
/// The expected executor key must come from current enrollment, independently
/// of response fields.
///
/// # Errors
/// Returns an error for changed request binding, signer or status shape.
pub fn verify_retained_cleanup_response(
    response_bytes: &[u8],
    request: &RetainedCleanupDispatchRequest,
    expected_target: &AndroidMediaExecutionTicket,
    expected_executor_peer_id: &str,
    expected_executor_key_id: &str,
    enrolled_executor_key: &[u8; 32],
) -> Result<RetainedCleanupDispatchResponse, String> {
    let response: RetainedCleanupDispatchResponse =
        serde_json::from_slice(response_bytes).map_err(|_| "cleanup response decode")?;
    if response.schema_id != RETAINED_CLEANUP_DISPATCH_RESPONSE_SCHEMA
        || response.dispatch_id != request.dispatch_id
        || response.executor_peer_id != expected_executor_peer_id
        || response.executor_peer_id != request.target_peer_id
        || response.request_sha256
            != retained_cleanup_request_sha256(request).map_err(str::to_owned)?
        || &request.cleanup.target != expected_target
        || response.signer_key_id != expected_executor_key_id
        || response.status == OwnerDispatchStatus::Rejected
        || (response.status == OwnerDispatchStatus::Completed)
            != (response.effect.is_some() && response.failure.is_none())
        || (response.status == OwnerDispatchStatus::Uncertain)
            != (response.effect.is_none() && response.failure.is_some())
        || response.effect.as_ref().is_some_and(|effect| {
            validate_effect(expected_target, request.cleanup.mode, effect).is_err()
        })
    {
        return Err("cleanup response binding differs".into());
    }
    let signature = decode_signature_base64(&response.signature_base64)?;
    let key = VerifyingKey::from_bytes(enrolled_executor_key)
        .map_err(|_| "cleanup executor key invalid")?;
    key.verify_strict(
        &retained_cleanup_response_signing_bytes(&response)?,
        &Signature::from_bytes(&signature),
    )
    .map_err(|_| "cleanup executor signature invalid")?;
    Ok(response)
}

/// No caller ticket enters the registry until this gate has authenticated it
/// against independent target state and durably reserved its replay identity.
pub struct RetainedCleanupDispatchServer<A, R, S, C, P> {
    source: A,
    registry: R,
    signer: S,
    clock: C,
    replay: RetainedCleanupReplaySnapshot,
    replay_store: P,
    target_peer_id: String,
    executor_signer_key_id: String,
}

impl<A, R, S, C, P> RetainedCleanupDispatchServer<A, R, S, C, P>
where
    A: RetainedCleanupAuthoritySource,
    R: RetainedCleanupRegistry,
    S: OwnerDispatchSigner,
    C: OwnerDispatchClock,
    P: RetainedCleanupReplayStore,
{
    /// Restore only valid replay state; pending IDs remain uncertain.
    ///
    /// # Errors
    /// Returns an error if target identity or replay state is invalid.
    pub fn restore(
        target_peer_id: String,
        executor_signer_key_id: String,
        source: A,
        registry: R,
        signer: S,
        clock: C,
        replay: RetainedCleanupReplaySnapshot,
        replay_store: P,
    ) -> Result<Self, String> {
        if target_peer_id.is_empty()
            || executor_signer_key_id.is_empty()
            || signer.key_id() != executor_signer_key_id
        {
            return Err("cleanup target signer binding differs".into());
        }
        replay.validate().map_err(str::to_owned)?;
        Ok(Self {
            source,
            registry,
            signer,
            clock,
            replay,
            replay_store,
            target_peer_id,
            executor_signer_key_id,
        })
    }

    /// Exact replay state for the embedding app's atomic journal/readback.
    pub fn replay_snapshot(&self) -> RetainedCleanupReplaySnapshot {
        self.replay.clone()
    }

    /// Handles one typed request. Pending retries return uncertainty; terminal
    /// retries return byte-exact cached responses without registry entry.
    ///
    /// # Errors
    /// Returns an error for invalid authority, persistence, or signing.
    pub fn handle(&mut self, request: &RetainedCleanupDispatchRequest) -> Result<Vec<u8>, String> {
        let request_sha256 = retained_cleanup_request_sha256(request).map_err(str::to_owned)?;
        match self
            .replay
            .classify(&request.dispatch_id, &request_sha256)
            .map_err(str::to_owned)?
        {
            RetainedCleanupReplayDecision::Terminal(bytes) => return Ok(bytes),
            RetainedCleanupReplayDecision::PendingUncertain => {
                return self.signed_response(
                    request,
                    request_sha256,
                    OwnerDispatchStatus::Uncertain,
                    None,
                    Some("pending_compensation_required".into()),
                );
            }
            RetainedCleanupReplayDecision::New => {}
        }
        let now_ms = self.clock.now_ms()?;
        let live = self.source.current_source(request, now_ms)?;
        verify_signed_retained_cleanup_request(
            request,
            &live.projection,
            &live.expected_target,
            live.executor_generation,
            &self.target_peer_id,
            &live.signer_key_id,
            &live.signer_key,
            now_ms,
        )?;
        self.replay.reserve(
            &mut self.replay_store,
            &request.dispatch_id,
            &request_sha256,
        )?;
        let result = self.registry.execute_and_verify(
            &live.projection,
            &live.expected_target,
            request.cleanup.mode,
        );
        let (status, effect, failure) = match result {
            Ok(effect)
                if validate_effect(&live.expected_target, request.cleanup.mode, &effect)
                    .is_ok() =>
            {
                (OwnerDispatchStatus::Completed, Some(effect), None)
            }
            Ok(_) => (
                OwnerDispatchStatus::Uncertain,
                None,
                Some("platform_evidence_invalid".into()),
            ),
            Err(_) => (
                OwnerDispatchStatus::Uncertain,
                None,
                Some("platform_effect_uncertain".into()),
            ),
        };
        let response =
            self.signed_response(request, request_sha256.clone(), status, effect, failure)?;
        // Uncertain effects keep their write-ahead marker. A later distinct
        // authenticated compensation path must resolve them before terminal.
        if status == OwnerDispatchStatus::Completed {
            self.replay.complete(
                &mut self.replay_store,
                &request.dispatch_id,
                &request_sha256,
                response.clone(),
            )?;
        }
        Ok(response)
    }

    fn signed_response(
        &self,
        request: &RetainedCleanupDispatchRequest,
        request_sha256: String,
        status: OwnerDispatchStatus,
        effect: Option<AuthenticatedOwnerEffect>,
        failure: Option<String>,
    ) -> Result<Vec<u8>, String> {
        let mut response = RetainedCleanupDispatchResponse {
            schema_id: RETAINED_CLEANUP_DISPATCH_RESPONSE_SCHEMA.into(),
            dispatch_id: request.dispatch_id.clone(),
            executor_peer_id: self.target_peer_id.clone(),
            request_sha256,
            status,
            effect,
            failure,
            signer_key_id: self.executor_signer_key_id.clone(),
            signature_base64: String::new(),
        };
        response.signature_base64 = encode_signature_base64(
            &self
                .signer
                .sign(&retained_cleanup_response_signing_bytes(&response)?)?,
        );
        serde_json::to_vec(&response).map_err(|_| "cleanup response encode".into())
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Mutex,
    };

    use ed25519_dalek::{Signer, SigningKey};
    use rusty_quest_media_stream::{
        MediaStreamOwnerActionKind, MediaStreamOwnerKind, MediaStreamPlatformOperation,
    };
    use sha2::{Digest, Sha256};

    use super::*;
    use crate::{
        sign_retained_cleanup_request, AndroidMediaExecutionMode, AndroidMediaOwnerReadback,
        RetainedCleanupExecutionTicket, VerifiedOwnerEffect, ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA,
        ANDROID_MEDIA_READBACK_SCHEMA, RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA,
        RETAINED_CLEANUP_PROJECTION_SCHEMA, RETAINED_CLEANUP_TICKET_SCHEMA,
        VERIFIED_OWNER_EFFECT_SCHEMA,
    };

    struct TestSigner(SigningKey, &'static str);
    impl OwnerDispatchSigner for TestSigner {
        fn key_id(&self) -> &str {
            self.1
        }
        fn sign(&self, bytes: &[u8]) -> Result<[u8; 64], String> {
            Ok(self.0.sign(bytes).to_bytes())
        }
    }

    struct Clock;
    impl OwnerDispatchClock for Clock {
        fn now_ms(&self) -> Result<u64, String> {
            Ok(100)
        }
    }

    struct Store;
    impl RetainedCleanupReplayStore for Store {
        fn commit(&mut self, _: &RetainedCleanupReplaySnapshot) -> Result<(), String> {
            Ok(())
        }
    }

    struct CaptureStore(Arc<Mutex<Option<RetainedCleanupReplaySnapshot>>>);
    impl RetainedCleanupReplayStore for CaptureStore {
        fn commit(&mut self, snapshot: &RetainedCleanupReplaySnapshot) -> Result<(), String> {
            *self.0.lock().map_err(|_| "store lock")? = Some(snapshot.clone());
            Ok(())
        }
    }

    struct Source {
        projection: RetainedCleanupAuthorityProjection,
        target: AndroidMediaExecutionTicket,
        key: [u8; 32],
        calls: Arc<AtomicUsize>,
    }
    impl RetainedCleanupAuthoritySource for Source {
        fn current_source(
            &self,
            _: &RetainedCleanupDispatchRequest,
            _: u64,
        ) -> Result<RetainedCleanupLiveSource, String> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            Ok(RetainedCleanupLiveSource {
                projection: self.projection.clone(),
                expected_target: self.target.clone(),
                executor_generation: 9,
                signer_key_id: "key.peer.a.1".into(),
                signer_key: self.key,
            })
        }
    }

    struct Registry(Arc<AtomicUsize>);
    impl RetainedCleanupRegistry for Registry {
        fn execute_and_verify(
            &mut self,
            _: &RetainedCleanupAuthorityProjection,
            _: &AndroidMediaExecutionTicket,
            _: AndroidMediaExecutionMode,
        ) -> Result<AuthenticatedOwnerEffect, String> {
            self.0.fetch_add(1, Ordering::SeqCst);
            Err("simulated uncertain provider".into())
        }
    }

    struct SuccessRegistry(Arc<AtomicUsize>);
    impl RetainedCleanupRegistry for SuccessRegistry {
        fn execute_and_verify(
            &mut self,
            _: &RetainedCleanupAuthorityProjection,
            target: &AndroidMediaExecutionTicket,
            _: AndroidMediaExecutionMode,
        ) -> Result<AuthenticatedOwnerEffect, String> {
            self.0.fetch_add(1, Ordering::SeqCst);
            let readback = AndroidMediaOwnerReadback {
                remote_cleanup: None,
                schema_id: ANDROID_MEDIA_READBACK_SCHEMA.into(),
                capability: target.capability.clone(),
                executor_generation: target.executor_generation,
                action_id: target.action_id.clone(),
                authority_epoch_id: target.authority_epoch_id.clone(),
                media_acceptance_authority_revision: target.media_acceptance_authority_revision,
                expected_runtime_revision: target.expected_runtime_revision,
                client_id: target.client_id.clone(),
                lease_id: target.lease_id.clone(),
                sequence: target.sequence,
                operation: target.operation,
                owner_kind: target.owner_kind,
                action_kind: target.action_kind,
                owner_id: target.owner_id.clone(),
                provider_kind: target.provider_kind.clone(),
                resource_id: target.resource_id.clone(),
                provider_handle_id: "handle.stopped".into(),
                provider_state_revision: 5,
                observed_state: "stopped".into(),
                receipt_id: "receipt.stop".into(),
            };
            let readback_json = serde_json::to_string(&readback).unwrap();
            let verified = VerifiedOwnerEffect {
                schema_id: VERIFIED_OWNER_EFFECT_SCHEMA.into(),
                receipt_id: readback.receipt_id.clone(),
                readback_sha256: format!("sha256:{:x}", Sha256::digest(readback_json.as_bytes())),
                executor_generation: target.executor_generation,
                provider_state_revision: 5,
                observed_state: "stopped".into(),
                terminal: true,
                provider_handle_id: "handle.stopped".into(),
                detail_sha256: format!("sha256:{}", "d".repeat(64)),
            };
            Ok(AuthenticatedOwnerEffect {
                readback,
                readback_json,
                verified,
            })
        }
    }

    fn fixture() -> (
        RetainedCleanupDispatchRequest,
        TestSigner,
        RetainedCleanupAuthorityProjection,
        AndroidMediaExecutionTicket,
    ) {
        let projection = RetainedCleanupAuthorityProjection {
            schema_id: RETAINED_CLEANUP_PROJECTION_SCHEMA.into(),
            authority_peer_id: "peer.a".into(),
            executor_peer_id: "peer.b".into(),
            peer_session_id: "session.one".into(),
            route_grant_id: "grant.one".into(),
            route_authority_revision: 7,
            provider_epoch_id: "epoch.one".into(),
            platform_runtime_spec_id: "runtime.one".into(),
            target_client_id: "client.original".into(),
            target_runtime_lease_id: "lease.original".into(),
            requester_id: "client.revoker".into(),
            requester_runtime_lease_id: "lease.revoker".into(),
            trusted_revoker: true,
            cleanup_target_sha256: format!("sha256:{}", "a".repeat(64)),
            terminal_route_sha256: format!("sha256:{}", "b".repeat(64)),
            signed_topology_sha256: format!("sha256:{}", "c".repeat(64)),
            historical_topology_expires_at_ms: 50,
            requester_expires_at_ms: 200,
            expires_at_ms: 180,
        };
        let target = AndroidMediaExecutionTicket {
            schema_id: ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA.into(),
            capability: "capability.fresh".into(),
            executor_generation: 9,
            action_id: "action.stop.one".into(),
            authority_epoch_id: "epoch.one".into(),
            media_acceptance_authority_revision: 3,
            expected_runtime_revision: 4,
            client_id: "client.original".into(),
            lease_id: "lease.original".into(),
            sequence: 1,
            operation: MediaStreamPlatformOperation::Stop,
            owner_kind: MediaStreamOwnerKind::Source,
            action_kind: MediaStreamOwnerActionKind::Stop,
            owner_id: "owner.camera".into(),
            provider_kind: "camera2".into(),
            resource_id: "camera.stereo".into(),
        };
        let mode = AndroidMediaExecutionMode::Execute;
        let digest = serde_json::to_vec(&(&projection, &target, mode)).unwrap();
        let cleanup = RetainedCleanupExecutionTicket {
            schema_id: RETAINED_CLEANUP_TICKET_SCHEMA.into(),
            authority: projection.clone(),
            target: target.clone(),
            mode,
            owner_effect_sha256: format!("sha256:{:x}", Sha256::digest(digest)),
        };
        let signer = TestSigner(SigningKey::from_bytes(&[27; 32]), "key.peer.a.1");
        let mut request = RetainedCleanupDispatchRequest {
            schema_id: RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA.into(),
            dispatch_id: "dispatch.cleanup.one".into(),
            sequence: 1,
            issued_at_ms: 100,
            target_peer_id: "peer.b".into(),
            cleanup,
            signer_key_id: signer.key_id().into(),
            signature_base64: String::new(),
        };
        sign_retained_cleanup_request(&mut request, &signer).unwrap();
        (request, signer, projection, target)
    }

    #[test]
    fn uncertain_effect_remains_pending_without_second_owner_entry() {
        let (request, coordinator, projection, target) = fixture();
        let executor = TestSigner(SigningKey::from_bytes(&[28; 32]), "key.peer.b.1");
        let executor_key = executor.0.verifying_key().to_bytes();
        let expected_target = target.clone();
        let source_calls = Arc::new(AtomicUsize::new(0));
        let registry_calls = Arc::new(AtomicUsize::new(0));
        let source = Source {
            projection,
            target,
            key: coordinator.0.verifying_key().to_bytes(),
            calls: source_calls.clone(),
        };
        assert!(RetainedCleanupDispatchServer::restore(
            "peer.b".into(),
            "key.peer.b.1".into(),
            Source {
                projection: source.projection.clone(),
                target: source.target.clone(),
                key: source.key,
                calls: source_calls.clone()
            },
            Registry(registry_calls.clone()),
            TestSigner(SigningKey::from_bytes(&[27; 32]), "key.peer.a.1"),
            Clock,
            RetainedCleanupReplaySnapshot::default(),
            Store,
        )
        .is_err());
        let mut server = RetainedCleanupDispatchServer::restore(
            "peer.b".into(),
            "key.peer.b.1".into(),
            source,
            Registry(registry_calls.clone()),
            executor,
            Clock,
            RetainedCleanupReplaySnapshot::default(),
            Store,
        )
        .unwrap();
        let first = server.handle(&request).unwrap();
        let second = server.handle(&request).unwrap();
        assert_ne!(first, second);
        assert_eq!(registry_calls.load(Ordering::SeqCst), 1);
        assert_eq!(source_calls.load(Ordering::SeqCst), 1);
        let response: RetainedCleanupDispatchResponse = serde_json::from_slice(&first).unwrap();
        assert_eq!(response.status, OwnerDispatchStatus::Uncertain);
        assert!(server
            .replay_snapshot()
            .pending_request_sha256
            .contains_key(&request.dispatch_id));
        assert!(server.replay_snapshot().terminal.is_empty());
        assert!(verify_retained_cleanup_response(
            &first,
            &request,
            &expected_target,
            "peer.b",
            "key.peer.b.1",
            &executor_key
        )
        .is_ok());
        assert!(verify_retained_cleanup_response(
            &first,
            &request,
            &expected_target,
            "peer.b",
            "key.peer.a.1",
            &coordinator.0.verifying_key().to_bytes()
        )
        .is_err());
        assert!(verify_retained_cleanup_response(
            &second,
            &request,
            &expected_target,
            "peer.b",
            "key.peer.b.1",
            &executor_key
        )
        .is_ok());
        let mut collision = request.clone();
        collision.sequence = 2;
        assert!(server.handle(&collision).is_err());
        assert_eq!(registry_calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn restored_pending_is_uncertain_without_registry_entry() {
        let (request, coordinator, projection, target) = fixture();
        let executor = TestSigner(SigningKey::from_bytes(&[28; 32]), "key.peer.b.1");
        let mut replay = RetainedCleanupReplaySnapshot::default();
        replay.pending_request_sha256.insert(
            request.dispatch_id.clone(),
            retained_cleanup_request_sha256(&request).unwrap(),
        );
        let calls = Arc::new(AtomicUsize::new(0));
        let source = Source {
            projection,
            target,
            key: coordinator.0.verifying_key().to_bytes(),
            calls: calls.clone(),
        };
        let mut server = RetainedCleanupDispatchServer::restore(
            "peer.b".into(),
            "key.peer.b.1".into(),
            source,
            Registry(calls.clone()),
            executor,
            Clock,
            replay,
            Store,
        )
        .unwrap();
        let response: RetainedCleanupDispatchResponse =
            serde_json::from_slice(&server.handle(&request).unwrap()).unwrap();
        assert_eq!(response.status, OwnerDispatchStatus::Uncertain);
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn completed_effect_replays_exact_response_after_restart_without_owner_entry() {
        let (request, coordinator, projection, target) = fixture();
        let executor = TestSigner(SigningKey::from_bytes(&[28; 32]), "key.peer.b.1");
        let executor_key = executor.0.verifying_key().to_bytes();
        let original_target = target.clone();
        let calls = Arc::new(AtomicUsize::new(0));
        let saved = Arc::new(Mutex::new(None));
        let source = Source {
            projection: projection.clone(),
            target: target.clone(),
            key: coordinator.0.verifying_key().to_bytes(),
            calls: Arc::new(AtomicUsize::new(0)),
        };
        let mut server = RetainedCleanupDispatchServer::restore(
            "peer.b".into(),
            "key.peer.b.1".into(),
            source,
            SuccessRegistry(calls.clone()),
            executor,
            Clock,
            RetainedCleanupReplaySnapshot::default(),
            CaptureStore(saved.clone()),
        )
        .unwrap();
        let first = server.handle(&request).unwrap();
        let verified = verify_retained_cleanup_response(
            &first,
            &request,
            &original_target,
            "peer.b",
            "key.peer.b.1",
            &executor_key,
        )
        .unwrap();
        assert_eq!(verified.status, OwnerDispatchStatus::Completed);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        let durable = saved.lock().unwrap().clone().unwrap();
        assert!(durable.pending_request_sha256.is_empty());
        assert_eq!(durable.terminal.len(), 1);
        let restarted_source = Source {
            projection,
            target,
            key: coordinator.0.verifying_key().to_bytes(),
            calls: Arc::new(AtomicUsize::new(0)),
        };
        let mut restarted = RetainedCleanupDispatchServer::restore(
            "peer.b".into(),
            "key.peer.b.1".into(),
            restarted_source,
            SuccessRegistry(calls.clone()),
            TestSigner(SigningKey::from_bytes(&[28; 32]), "key.peer.b.1"),
            Clock,
            durable,
            CaptureStore(saved),
        )
        .unwrap();
        assert_eq!(restarted.handle(&request).unwrap(), first);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }
    #[test]
    fn independent_target_stop_nested_proof_preserves_actual_raw_effect() {
        independent_target_stop_nested_proof(false);
    }
    #[test]
    fn retained_abort_v2_nested_proof_preserves_actual_stop_and_v1_rejects() {
        independent_target_stop_nested_proof(true);
    }
    fn independent_target_stop_nested_proof(retained_abort: bool) {
        use crate::{
            retained_cleanup_prepared_signing_bytes, RemoteRetainedCleanupEffect,
            RetainedCleanupPreparedStop,
        };
        let (request, coordinator, projection, target) = fixture();
        let executor = TestSigner(SigningKey::from_bytes(&[28; 32]), "key.peer.b.1");
        let key = executor.0.verifying_key().to_bytes();
        let mut source_ticket = target.clone();
        source_ticket.capability = "source.capability".into();
        source_ticket.action_id = "source.action".into();
        source_ticket.executor_generation = 21;
        source_ticket.expected_runtime_revision = 19;
        source_ticket.media_acceptance_authority_revision = 23;
        if retained_abort {
            source_ticket.operation = MediaStreamPlatformOperation::Start;
            source_ticket.action_id = "source.action.abort".into();
            assert!(crate::is_retained_start_abort_ticket(&source_ticket));
        }
        let mut prepared = RetainedCleanupPreparedStop {
            schema_id: if retained_abort {
                "rusty.quest.android.media.retained_abort_prepared_stop.v2"
            } else {
                "rusty.quest.android.media.retained_cleanup_prepared_stop.v1"
            }
            .into(),
            prepare_request_sha256: format!("sha256:{}", "a".repeat(64)),
            dispatch_id: request.dispatch_id.clone(),
            target_preparation_revision: 2,
            source_ticket: source_ticket.clone(),
            target_ticket: target.clone(),
            authority: projection.clone(),
            signer_key_id: executor.key_id().into(),
            signature_base64: String::new(),
        };
        prepared.signature_base64 = encode_signature_base64(
            &executor
                .sign(&retained_cleanup_prepared_signing_bytes(&prepared).unwrap())
                .unwrap(),
        );
        let mut server = RetainedCleanupDispatchServer::restore(
            "peer.b".into(),
            executor.key_id().into(),
            Source {
                projection,
                target: target.clone(),
                key: coordinator.0.verifying_key().to_bytes(),
                calls: Arc::new(AtomicUsize::new(0)),
            },
            SuccessRegistry(Arc::new(AtomicUsize::new(0))),
            executor,
            Clock,
            RetainedCleanupReplaySnapshot::default(),
            Store,
        )
        .unwrap();
        let response_bytes = server.handle(&request).unwrap();
        let proof = RemoteRetainedCleanupEffect {
            schema_id: if retained_abort {
                "rusty.quest.android.media.remote_retained_abort_effect.v2"
            } else {
                "rusty.quest.android.media.remote_retained_cleanup_effect.v1"
            }
            .into(),
            prepared,
            commit: request,
            response_bytes: response_bytes.clone(),
            enrolled_target_key: key,
        };
        let actual = proof.verify(&source_ticket, "key.peer.b.1", &key).unwrap();
        if retained_abort {
            assert_eq!(
                actual.readback.operation,
                MediaStreamPlatformOperation::Stop
            );
            let mut old_schema = proof.clone();
            old_schema.schema_id =
                "rusty.quest.android.media.remote_retained_cleanup_effect.v1".into();
            assert!(old_schema
                .verify(&source_ticket, "key.peer.b.1", &key)
                .is_err());
            let mut normal_start = source_ticket.clone();
            normal_start.action_id = "source.action".into();
            assert!(proof.verify(&normal_start, "key.peer.b.1", &key).is_err());
        }
        let raw = actual.readback_json.clone();
        let wrapper = proof
            .clone()
            .source_readback(&source_ticket, "key.peer.b.1", &key)
            .unwrap();
        assert_ne!(wrapper.capability, actual.readback.capability);
        assert_eq!(
            wrapper.expected_runtime_revision,
            source_ticket.expected_runtime_revision
        );
        assert_eq!(
            wrapper.remote_cleanup.as_ref().unwrap().response_bytes,
            response_bytes
        );
        assert_eq!(
            wrapper
                .remote_cleanup
                .as_ref()
                .unwrap()
                .verify(&source_ticket, "key.peer.b.1", &key)
                .unwrap()
                .readback_json,
            raw
        );
        crate::validate_readback(&source_ticket, &wrapper).unwrap();
        let ordinary = serde_json::to_value(&actual.readback).unwrap();
        assert!(ordinary.get("remote_cleanup").is_none());
        let mut changed = source_ticket.clone();
        changed.lease_id = "lease.revoker".into();
        assert!(proof.verify(&changed, "key.peer.b.1", &key).is_err());
        let mut tampered = proof.clone();
        tampered.prepared.target_ticket.action_id = "unprepared".into();
        assert!(tampered
            .verify(&source_ticket, "key.peer.b.1", &key)
            .is_err());
        let mut tampered = proof.clone();
        tampered.response_bytes[5] ^= 1;
        assert!(tampered
            .verify(&source_ticket, "key.peer.b.1", &key)
            .is_err());
        assert!(proof
            .verify(&source_ticket, "key.peer.b.1", &[3; 32])
            .is_err());
        let mut forged = wrapper;
        forged.provider_state_revision += 1;
        assert!(crate::validate_readback(&source_ticket, &forged).is_err());
    }
    #[test]
    fn target_derivation_uses_only_retained_original_start_and_fresh_native_identity() {
        let (_, _, _, mut original) = fixture();
        original.operation = MediaStreamPlatformOperation::Start;
        original.action_kind = MediaStreamOwnerActionKind::Start;
        original.sequence = 7;
        let stop = crate::derive_retained_target_stop(&original, 71, &"c".repeat(32)).unwrap();
        assert_ne!(stop.capability, original.capability);
        assert_ne!(stop.action_id, original.action_id);
        assert_eq!(stop.executor_generation, 71);
        assert_eq!(stop.sequence, 1);
        assert_eq!(stop.client_id, original.client_id);
        assert_eq!(stop.lease_id, original.lease_id);
        assert_eq!(
            stop.media_acceptance_authority_revision,
            original.media_acceptance_authority_revision
        );
        assert_eq!(
            stop.expected_runtime_revision,
            original.expected_runtime_revision
        );
        assert!(crate::derive_retained_target_stop(&stop, 71, &"c".repeat(32)).is_err());
        assert!(crate::derive_retained_target_stop(&original, 0, &"c".repeat(32)).is_err());
        assert!(crate::derive_retained_target_stop(&original, 71, "caller-pointer").is_err());
    }
    #[test]
    fn retained_local_abort_projection_requires_actual_terminal_effect() {
        let (_, _, projection, mut target) = fixture();
        target.action_id = "source.action.abort".into();
        let mut source = target.clone();
        source.operation = MediaStreamPlatformOperation::Start;
        let mut registry = SuccessRegistry(Arc::new(AtomicUsize::new(0)));
        let effect = registry
            .execute_and_verify(
                &projection,
                &target,
                AndroidMediaExecutionMode::CompensateUncertain,
            )
            .unwrap();
        let projected =
            crate::retained_local_source_readback(&source, &target, effect.clone()).unwrap();
        assert_eq!(projected.operation, MediaStreamPlatformOperation::Start);
        crate::validate_readback(&source, &projected).unwrap();
        let mut damaged = effect.clone();
        damaged.verified.terminal = false;
        assert!(crate::retained_local_source_readback(&source, &target, damaged).is_err());
        let mut damaged = effect.clone();
        damaged.readback.provider_handle_id = "foreign".into();
        assert!(crate::retained_local_source_readback(&source, &target, damaged).is_err());
        let mut wrong = target.clone();
        wrong.executor_generation += 1;
        assert!(crate::retained_local_source_readback(&source, &wrong, effect.clone()).is_err());
        source.action_kind = MediaStreamOwnerActionKind::Start;
        assert!(crate::retained_local_source_readback(&source, &target, effect).is_err());
    }
}
