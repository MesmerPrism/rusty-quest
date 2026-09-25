//! Borrowed façade over the one live Broker and durable peer Runtime Host.

use std::sync::{Arc, RwLock};

use ed25519_dalek::{Signature, VerifyingKey};
use rusty_manifold_broker_adapter::ManifoldBrokerRuntime;
use rusty_manifold_model::{DottedId, SchemaId};
use rusty_manifold_peer::{
    common_lan_reciprocal_ed25519_context_signing_bytes, ManifoldCommonLanPairMediaRouteRequest,
    ManifoldCommonLanPeerSessionDecision, ManifoldCommonLanPeerSessionProposal,
    ManifoldCommonLanReciprocalEd25519Context, ManifoldCommonLanReciprocalEd25519PeerBinding,
    ManifoldCommonLanReciprocalEd25519Receipt, ManifoldCommonLanReciprocalEd25519ReviewRequest,
    ManifoldCommonLanSignedPeerTopologyAuthorization, ManifoldCommonLanTransportBinding,
    ManifoldPairMediaRouteCleanupCompletionRequestV2, ManifoldPairMediaRouteCleanupReceiptV2,
    ManifoldPairMediaRouteCleanupStatus, ManifoldPairMediaRouteCurrentReceiptV2,
    ManifoldPairMediaRouteLifecycleStatus, ManifoldPairMediaRouteMutationReceipt,
    ManifoldPairMediaRouteReceiptV2, ManifoldPairMediaRouteRequestV2,
    ManifoldPairMediaRouteTerminationRequestV2, ManifoldPeerApplicationReceipt,
    ManifoldPeerDecision, ManifoldPeerEnrollmentReceipt, ManifoldPeerEnrollmentRequest,
    ManifoldPeerSessionCurrentReceiptV2, ManifoldPeerStatusProposal,
    ManifoldReciprocalEd25519ReceiptV3, ManifoldReciprocalEd25519ReviewRequestV3,
    ManifoldReciprocalEd25519Revisions, COMMON_LAN_RECIPROCAL_ED25519_CONTEXT_SCHEMA,
};
use rusty_manifold_peer_runtime_host::{
    ManifoldPeerRuntimeHost, ManifoldPeerRuntimeHostError,
    ManifoldPeerRuntimeHostSnapshotMigrationReceipt,
};
use rusty_manifold_runtime_host::{ManifoldRuntimeCommandRequest, ManifoldRuntimeLease};
use rusty_quest_media_stream_android::{
    request_signing_bytes, AndroidMediaExecutionMode, AndroidMediaExecutionTicket,
    CurrentOwnerProjectionSource, OwnerDispatchAuthorityProjection, OwnerDispatchAuthorityVerifier,
    OwnerDispatchAuthorizationKind, OwnerDispatchClock, OwnerDispatchRequest,
    ProductActivationAuthorityVerifier, ProductActivationRequest,
    MAX_OWNER_DISPATCH_FUTURE_SKEW_MS, MAX_OWNER_DISPATCH_REQUEST_AGE_MS,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Closed authority-projection schema consumed by remote owner dispatch.
pub const QUEST_C1_OWNER_PROJECTION_SCHEMA: &str = "rusty.quest.c1.owner_projection.v1";
/// Product-owned C1 authority bootstrap schema.
pub const QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA: &str =
    "rusty.quest.embedded_duplex.authority_config.v1";
/// Read-only retained cleanup target and current requester projection.
pub const QUEST_RETAINED_CLEANUP_TARGET_SCHEMA: &str =
    "rusty.quest.embedded_duplex.retained_cleanup_target.v1";

/// Separates the immutable media target from the live cleanup requester.
/// This is a preflight projection, never an owner-effect completion receipt.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestRetainedCleanupTarget {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Retained terminal route grant.
    pub route_grant_id: DottedId,
    /// Original media holder whose handles must be stopped.
    pub target_client_id: DottedId,
    /// Original media lease retained as target evidence.
    pub target_runtime_lease_id: DottedId,
    /// Current authorized cleanup requester.
    pub requester_id: DottedId,
    /// Current requester's distinct lease.
    pub requester_lease_id: DottedId,
    /// Whether this requester is a trusted revoker rather than the original holder.
    pub trusted_revoker: bool,
    /// Retained authority process epoch.
    pub provider_epoch_id: DottedId,
    /// Exact retained platform runtime.
    pub platform_runtime_spec_id: DottedId,
    /// Digest of the immutable cleanup target fields.
    pub cleanup_target_sha256: String,
    /// Digest of the full terminal route record.
    pub terminal_route_sha256: String,
    /// Current requester lease expiry.
    pub requester_expires_at_ms: u64,
}

/// Immutable trust roots that enable the mixed peer authority families.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestEmbeddedDuplexAuthorityConfig {
    /// Schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Stable device-local Runtime Host identity. Two peers must never share it.
    pub runtime_host_id: DottedId,
    /// Operators allowed to stage real device enrollments.
    pub trusted_operator_ids: Vec<DottedId>,
    /// Optional packaged peer fingerprints accepted before enrollment.
    pub trusted_key_fingerprints: Vec<DottedId>,
    /// Existing platform adapters allowed by legacy Wi-Fi compatibility APIs.
    pub trusted_adapter_ids: Vec<DottedId>,
    /// Fresh revokers allowed to clean retained media routes.
    pub trusted_media_revoker_ids: Vec<DottedId>,
}

impl QuestEmbeddedDuplexAuthorityConfig {
    /// Validates exact canonical sets and required trust roots.
    pub(crate) fn validate(&self) -> bool {
        self.schema_id == QUEST_EMBEDDED_DUPLEX_AUTHORITY_CONFIG_SCHEMA
            && self.runtime_host_id.as_str().starts_with("host.")
            && !self.trusted_operator_ids.is_empty()
            && !self.trusted_key_fingerprints.is_empty()
            && !self.trusted_adapter_ids.is_empty()
            && !self.trusted_media_revoker_ids.is_empty()
            && canonical_set(&self.trusted_operator_ids)
            && canonical_set(&self.trusted_key_fingerprints)
            && canonical_set(&self.trusted_adapter_ids)
            && canonical_set(&self.trusted_media_revoker_ids)
    }
}

/// Product-selected portions of a common-LAN context. Authority revisions are
/// always read from the current durable Runtime Host.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestCommonLanContextDraft {
    /// Replay-protected correlation.
    pub correlation_id: DottedId,
    /// Initiating enrolled peer and nonce.
    pub initiator: ManifoldCommonLanReciprocalEd25519PeerBinding,
    /// Responding enrolled peer and nonce.
    pub responder: ManifoldCommonLanReciprocalEd25519PeerBinding,
    /// Exact peer-agreed transport configuration.
    pub transport: ManifoldCommonLanTransportBinding,
    /// Current pair coordinator epoch.
    pub coordinator_epoch: u64,
    /// Creation time.
    pub issued_at_ms: u64,
    /// Bounded expiry.
    pub expires_at_ms: u64,
}

/// Shared handle to the single process authority. Cloning this handle does not
/// duplicate state; every operation uses the same two Arc-backed instances.
#[derive(Clone)]
pub struct QuestEmbeddedDuplexAuthority {
    broker: Arc<RwLock<ManifoldBrokerRuntime>>,
    peer: Arc<RwLock<ManifoldPeerRuntimeHost>>,
}

impl QuestEmbeddedDuplexAuthority {
    pub(crate) fn new(
        broker: Arc<RwLock<ManifoldBrokerRuntime>>,
        peer: Arc<RwLock<ManifoldPeerRuntimeHost>>,
    ) -> Self {
        Self { broker, peer }
    }

    /// Reviews one exact peer status proposal.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn review_peer_status(
        &self,
        proposal: ManifoldPeerStatusProposal,
        now_ms: u64,
    ) -> Result<(ManifoldPeerDecision, ManifoldPeerApplicationReceipt), String> {
        write_peer(&self.peer)?
            .review_peer_status(proposal, now_ms)
            .map_err(host_error)
    }

    /// Reviews one operator-authorized enrollment request.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn review_enrollment(
        &self,
        request: &ManifoldPeerEnrollmentRequest,
        now_ms: u64,
    ) -> Result<ManifoldPeerEnrollmentReceipt, String> {
        write_peer(&self.peer)?
            .review_enrollment(request, now_ms)
            .map_err(host_error)
    }

    /// Produces the exact context that both device-local signers must sign.
    /// No signer callback is invoked while the Runtime Host lock is held.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn prepare_common_lan_context(
        &self,
        draft: QuestCommonLanContextDraft,
    ) -> Result<ManifoldCommonLanReciprocalEd25519Context, String> {
        let peer = read_peer(&self.peer)?;
        let snapshot = peer.snapshot();
        Ok(ManifoldCommonLanReciprocalEd25519Context {
            schema_id: SchemaId::new(COMMON_LAN_RECIPROCAL_ED25519_CONTEXT_SCHEMA)
                .map_err(|error| error.to_string())?,
            runtime_host_id: snapshot.host_id.clone(),
            trust_policy_id: snapshot.trust_policy.policy_id.clone(),
            trust_policy_revision: snapshot.trust_policy.revision,
            correlation_id: draft.correlation_id,
            revisions: ManifoldReciprocalEd25519Revisions {
                peer_authority_revision: snapshot.accepted_peers.authority_revision,
                enrollment_authority_revision: snapshot.enrollment.authority_revision,
                rendezvous_authority_revision: snapshot.rendezvous.authority_revision,
                reciprocal_authority_revision: snapshot.reciprocal_ed25519.authority_revision,
                peer_session_authority_revision: snapshot.peer_sessions.authority_revision,
                peer_mesh_authority_revision: snapshot.peer_mesh.authority_revision,
                direct_lane_lease_authority_revision: snapshot
                    .direct_lane_leases
                    .authority_revision,
            },
            initiator: draft.initiator,
            responder: draft.responder,
            transport: draft.transport,
            coordinator_epoch: draft.coordinator_epoch,
            issued_at_ms: draft.issued_at_ms,
            expires_at_ms: draft.expires_at_ms,
        })
    }

    /// Returns Manifold's domain-separated exact bytes for each device-local signer.
    #[must_use]
    pub fn common_lan_context_signing_bytes(
        context: &ManifoldCommonLanReciprocalEd25519Context,
    ) -> Vec<u8> {
        common_lan_reciprocal_ed25519_context_signing_bytes(context)
    }

    /// Applies the exact two-signature reciprocal request against current revisions.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn apply_common_lan_reciprocal(
        &self,
        request: &ManifoldCommonLanReciprocalEd25519ReviewRequest,
        now_ms: u64,
    ) -> Result<ManifoldCommonLanReciprocalEd25519Receipt, String> {
        let request = ManifoldReciprocalEd25519ReviewRequestV3::CommonLan(request.clone());
        match write_peer(&self.peer)?
            .review_reciprocal_ed25519_v3(&request, now_ms)
            .map_err(host_error)?
        {
            ManifoldReciprocalEd25519ReceiptV3::CommonLan(receipt) => Ok(receipt),
            ManifoldReciprocalEd25519ReceiptV3::WifiDirect(_) => {
                Err("common-LAN reciprocal returned Wi-Fi receipt".to_owned())
            }
        }
    }

    /// Applies the exact common-LAN session after reciprocal acceptance.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn apply_common_lan_session(
        &self,
        proposal: &ManifoldCommonLanPeerSessionProposal,
        reciprocal: &ManifoldCommonLanReciprocalEd25519Receipt,
        now_ms: u64,
    ) -> Result<
        (
            ManifoldCommonLanPeerSessionDecision,
            ManifoldCommonLanSignedPeerTopologyAuthorization,
        ),
        String,
    > {
        write_peer(&self.peer)?
            .review_common_lan_peer_session(proposal, reciprocal, now_ms)
            .map_err(host_error)
    }

    /// Revalidates an accepted pair session against live peer authority and time.
    #[must_use]
    pub fn current_common_lan_session(
        &self,
        session_id: &DottedId,
        now_ms: u64,
    ) -> Result<ManifoldPeerSessionCurrentReceiptV2, String> {
        Ok(read_peer(&self.peer)?.validate_peer_session_v2(session_id, now_ms))
    }

    /// Issues a route only after the exact command and media leases join the live Broker.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn issue_common_lan_route(
        &self,
        request: &ManifoldCommonLanPairMediaRouteRequest,
        command: &ManifoldRuntimeCommandRequest,
        now_ms: u64,
    ) -> Result<ManifoldPairMediaRouteReceiptV2, String> {
        let broker = read_broker(&self.broker)?;
        write_peer(&self.peer)?
            .review_pair_media_route_v2_with_live_broker_runtime(
                &broker,
                &ManifoldPairMediaRouteRequestV2::CommonLan(request.clone()),
                command,
                now_ms,
            )
            .map_err(host_error)
    }

    /// Revalidates one route against both current peer authority and live Broker leases.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn current_route(
        &self,
        grant_id: &DottedId,
        now_ms: u64,
    ) -> Result<ManifoldPairMediaRouteCurrentReceiptV2, String> {
        let broker = read_broker(&self.broker)?;
        read_peer(&self.peer)?
            .validate_pair_media_route_v2_with_live_broker_runtime(&broker, grant_id, now_ms)
            .map_err(host_error)
    }

    /// Stops/revokes a route using an original-client or current trusted-revoker command.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn terminate_route(
        &self,
        request: &ManifoldPairMediaRouteTerminationRequestV2,
        command: &ManifoldRuntimeCommandRequest,
        now_ms: u64,
    ) -> Result<ManifoldPairMediaRouteMutationReceipt, String> {
        let broker = read_broker(&self.broker)?;
        write_peer(&self.peer)?
            .review_pair_media_route_termination_v2_with_live_broker_runtime(
                &broker, request, command, now_ms,
            )
            .map_err(host_error)
    }

    /// Completes cleanup with exact authenticated platform effect evidence.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn complete_route_cleanup(
        &self,
        request: &ManifoldPairMediaRouteCleanupCompletionRequestV2,
        command: &ManifoldRuntimeCommandRequest,
        now_ms: u64,
    ) -> Result<ManifoldPairMediaRouteCleanupReceiptV2, String> {
        let broker = read_broker(&self.broker)?;
        write_peer(&self.peer)?
            .complete_pair_media_route_cleanup_v2_with_live_broker_runtime(
                &broker, request, command, now_ms,
            )
            .map_err(host_error)
    }

    /// Builds an immutable projection only from a current Common-LAN route.
    /// The caller may invoke Java/network code after this function returns.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn current_owner_projection(
        &self,
        grant_id: &DottedId,
        authority_peer_id: &DottedId,
        executor_peer_id: &DottedId,
        now_ms: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String> {
        let broker = read_broker(&self.broker)?;
        let peer = read_peer(&self.peer)?;
        projection_from_current(
            &peer,
            &broker,
            grant_id,
            authority_peer_id,
            executor_peer_id,
            now_ms,
        )
    }

    /// Authorizes a platform cleanup against a retained terminal route using
    /// either its still-current original lease or a fresh trusted-revoker lease.
    ///
    /// # Errors
    /// Returns an error unless the exact retained target, current peer authority,
    /// requester lease, and live Broker-derived admission all validate.
    pub fn retained_cleanup_owner_projection(
        &self,
        grant_id: &DottedId,
        authority_peer_id: &DottedId,
        executor_peer_id: &DottedId,
        ticket: &AndroidMediaExecutionTicket,
        now_ms: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String> {
        let broker = read_broker(&self.broker)?;
        let peer = read_peer(&self.peer)?;
        cleanup_projection_from_retained(
            &peer,
            &broker,
            grant_id,
            authority_peer_id,
            executor_peer_id,
            ticket,
            now_ms,
        )
    }

    /// Reads a terminal route and a live original-holder or non-derivative
    /// trusted-revoker lease without changing route or platform state.
    ///
    /// # Errors
    /// Rejects a missing/changed target, stale or foreign lease, derivative
    /// revoker lease, or untrusted requester.
    pub fn retained_cleanup_target(
        &self,
        grant_id: &DottedId,
        requester_id: &DottedId,
        requester_lease_id: &DottedId,
        now_ms: u64,
    ) -> Result<QuestRetainedCleanupTarget, String> {
        let peer = read_peer(&self.peer)?;
        let snapshot = peer.snapshot();
        let route = snapshot
            .pair_media_routes
            .routes
            .iter()
            .find(|route| route.grant_id() == grant_id)
            .ok_or_else(|| "cleanup route is absent".to_owned())?;
        let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(route) = route else {
            return Err("cleanup requires common-LAN route".to_owned());
        };
        if route.lifecycle_status == ManifoldPairMediaRouteLifecycleStatus::Current
            || route.cleanup_status != ManifoldPairMediaRouteCleanupStatus::Pending
            || route.authority_provider_epoch_id != snapshot.provider_epoch_id
            || route.authority_host_id != snapshot.host_id
        {
            return Err("route has no retained cleanup target".to_owned());
        }
        let lease = snapshot
            .media_command_runtime
            .leases
            .iter()
            .find(|lease| lease.lease_id == *requester_lease_id)
            .ok_or_else(|| "cleanup requester lease is absent".to_owned())?;
        let trusted_revoker = current_cleanup_requester(
            &route.authority_client_id,
            &route.authority_runtime_lease_id,
            requester_id,
            requester_lease_id,
            lease,
            &snapshot.trust_policy.media_runtime_lease_scope_id,
            &snapshot.trust_policy.trusted_media_revoker_ids,
            now_ms,
        )?;
        let target = (
            &route.grant_id,
            &route.authority_client_id,
            &route.authority_runtime_lease_id,
            &route.authority_provider_epoch_id,
            &route.platform_runtime_spec_id,
            &route.peer_session_id,
            &route.signed_topology_evidence,
            &route.transport,
        );
        Ok(QuestRetainedCleanupTarget {
            schema_id: QUEST_RETAINED_CLEANUP_TARGET_SCHEMA.to_owned(),
            route_grant_id: route.grant_id.clone(),
            target_client_id: route.authority_client_id.clone(),
            target_runtime_lease_id: route.authority_runtime_lease_id.clone(),
            requester_id: requester_id.clone(),
            requester_lease_id: requester_lease_id.clone(),
            trusted_revoker,
            provider_epoch_id: route.authority_provider_epoch_id.clone(),
            platform_runtime_spec_id: route.platform_runtime_spec_id.clone(),
            cleanup_target_sha256: typed_sha256(&target)?,
            terminal_route_sha256: typed_sha256(route)?,
            requester_expires_at_ms: lease.expires_at_ms,
        })
    }

    /// Returns the complete durable v5 Runtime Host snapshot JSON.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn snapshot_json(&self) -> Result<String, String> {
        read_peer(&self.peer)?.snapshot_json().map_err(host_error)
    }

    /// Restores v1-v5 durable state only after exact trust, provider epoch, and
    /// all retained Broker-derived leases join the current live Broker.
    ///
    /// # Errors
    /// Returns an error when validation, authority state, or a required live join fails.
    pub fn restore_snapshot_json(
        &self,
        snapshot_json: &str,
    ) -> Result<ManifoldPeerRuntimeHostSnapshotMigrationReceipt, String> {
        let broker = read_broker(&self.broker)?;
        let mut peer = write_peer(&self.peer)?;
        let expected_trust = peer.snapshot().trust_policy.clone();
        let expected_epoch = peer.snapshot().provider_epoch_id.clone();
        let (restored, receipt) =
            ManifoldPeerRuntimeHost::restart_from_json_with_live_broker_runtime(
                snapshot_json,
                &expected_trust,
                &expected_epoch,
                &broker,
            )
            .map_err(host_error)?;
        *peer = restored;
        Ok(receipt)
    }

    #[cfg(test)]
    pub(crate) fn verify_remote_projection_for_test(
        &self,
        projection: &OwnerDispatchAuthorityProjection,
        signer_key_id: &str,
        now_ms: u64,
    ) -> Result<(), String> {
        let peer = read_peer(&self.peer)?;
        verify_projection_against_retained(&peer, projection, signer_key_id, now_ms)
    }
}

/// Concrete dispatcher verifier over the same live authority instances.
#[derive(Clone)]
pub struct QuestOwnerDispatchAuthorityVerifier {
    authority: QuestEmbeddedDuplexAuthority,
    clock: Arc<dyn OwnerDispatchClock>,
}

/// Source-side adapter that joins the route to its actual live Broker before
/// any remote transport or Java callback begins.
#[derive(Clone)]
pub struct QuestEmbeddedDuplexProjectionSource {
    authority: QuestEmbeddedDuplexAuthority,
    route_grant_id: DottedId,
    authority_peer_id: DottedId,
}

impl QuestEmbeddedDuplexProjectionSource {
    /// Binds one directional route and coordinator peer.
    #[must_use]
    pub fn new(
        authority: QuestEmbeddedDuplexAuthority,
        route_grant_id: DottedId,
        authority_peer_id: DottedId,
    ) -> Self {
        Self {
            authority,
            route_grant_id,
            authority_peer_id,
        }
    }
}

impl CurrentOwnerProjectionSource for QuestEmbeddedDuplexProjectionSource {
    fn current_projection(
        &self,
        ticket: &AndroidMediaExecutionTicket,
        target_peer_id: &str,
        _mode: AndroidMediaExecutionMode,
        now_ms: u64,
    ) -> Result<OwnerDispatchAuthorityProjection, String> {
        let target = DottedId::new(target_peer_id.to_owned()).map_err(|error| error.to_string())?;
        let current = self.authority.current_owner_projection(
            &self.route_grant_id,
            &self.authority_peer_id,
            &target,
            now_ms,
        );
        let projection = match current {
            Ok(projection) => projection,
            Err(_)
                if matches!(
                    ticket.operation,
                    rusty_quest_media_stream::MediaStreamPlatformOperation::Stop
                ) =>
            {
                self.authority.retained_cleanup_owner_projection(
                    &self.route_grant_id,
                    &self.authority_peer_id,
                    &target,
                    ticket,
                    now_ms,
                )?
            }
            Err(error) => return Err(error),
        };
        if projection.authority_provider_epoch_id != ticket.authority_epoch_id
            || projection.platform_runtime_spec_id.is_empty()
            || projection.authority_client_id != ticket.client_id
            || projection.authority_runtime_lease_id != ticket.lease_id
        {
            return Err("ticket is outside current route authority".to_owned());
        }
        Ok(projection)
    }
}

impl QuestOwnerDispatchAuthorityVerifier {
    /// Creates a verifier sharing the authority state.
    #[must_use]
    pub fn new(
        authority: QuestEmbeddedDuplexAuthority,
        clock: Arc<dyn OwnerDispatchClock>,
    ) -> Self {
        Self { authority, clock }
    }

    fn verify_signed_projection(
        &self,
        authority: &OwnerDispatchAuthorityProjection,
        signer_key_id: &str,
        signing_bytes: &[u8],
        signature: &[u8; 64],
        now_ms: u64,
    ) -> Result<(), String> {
        if authority.schema_id != QUEST_C1_OWNER_PROJECTION_SCHEMA {
            return Err("owner projection schema mismatch".to_owned());
        }
        let peer = read_peer(&self.authority.peer)?;
        let credential = peer
            .snapshot()
            .enrollment
            .credentials
            .iter()
            .find(|credential| {
                credential.peer_id.to_string() == authority.authority_peer_id
                    && credential.key_id.to_string() == signer_key_id
                    && matches!(
                        credential.status,
                        rusty_manifold_peer::ManifoldPeerCredentialStatus::Active
                    )
                    && credential.valid_from_ms <= now_ms
                    && credential.expires_at_ms > now_ms
            })
            .ok_or_else(|| "dispatch signer is not currently enrolled".to_owned())?;
        let public_key = decode_array::<32>(&credential.public_key_hex)?;
        VerifyingKey::from_bytes(&public_key)
            .map_err(|_| "invalid enrolled key".to_owned())?
            .verify_strict(signing_bytes, &Signature::from_bytes(signature))
            .map_err(|_| "dispatch signature".to_owned())?;
        verify_projection_against_retained(&peer, authority, signer_key_id, now_ms)
    }
}

impl OwnerDispatchAuthorityVerifier for QuestOwnerDispatchAuthorityVerifier {
    fn verify_current(
        &self,
        request: &OwnerDispatchRequest,
        signing_bytes: &[u8],
        signature: &[u8; 64],
    ) -> Result<(), String> {
        let now_ms = self.clock.now_ms()?;
        if now_ms == 0
            || request.issued_at_ms > now_ms.saturating_add(MAX_OWNER_DISPATCH_FUTURE_SKEW_MS)
            || now_ms.saturating_sub(request.issued_at_ms) > MAX_OWNER_DISPATCH_REQUEST_AGE_MS
        {
            return Err("owner dispatch request is not currently fresh".to_owned());
        }
        if signing_bytes != request_signing_bytes(request)? {
            return Err("owner projection schema/signing mismatch".to_owned());
        }
        self.verify_signed_projection(
            &request.authority,
            &request.signer_key_id,
            signing_bytes,
            signature,
            now_ms,
        )
    }
}

impl ProductActivationAuthorityVerifier for QuestOwnerDispatchAuthorityVerifier {
    fn verify_activation(
        &self,
        request: &ProductActivationRequest,
        signing_bytes: &[u8],
        signature: &[u8; 64],
    ) -> Result<(), String> {
        let now_ms = self.clock.now_ms()?;
        if now_ms == 0
            || request.issued_at_ms > now_ms.saturating_add(MAX_OWNER_DISPATCH_FUTURE_SKEW_MS)
            || request.expires_at_ms <= now_ms
        {
            return Err("product activation is not current".to_owned());
        }
        self.verify_signed_projection(
            &request.authority,
            &request.signer_key_id,
            signing_bytes,
            signature,
            now_ms,
        )
    }
}

fn projection_from_current(
    peer: &ManifoldPeerRuntimeHost,
    broker: &ManifoldBrokerRuntime,
    grant_id: &DottedId,
    authority_peer_id: &DottedId,
    executor_peer_id: &DottedId,
    now_ms: u64,
) -> Result<OwnerDispatchAuthorityProjection, String> {
    let current = peer
        .validate_pair_media_route_v2_with_live_broker_runtime(broker, grant_id, now_ms)
        .map_err(host_error)?;
    if !current.current {
        return Err("pair route is not current".to_owned());
    }
    let route_authority_evidence_sha256 = typed_sha256(&current)?;
    let route = current
        .route
        .ok_or_else(|| "current route omitted record".to_owned())?;
    let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(route) = route else {
        return Err("owner dispatch requires common-LAN route".to_owned());
    };
    let topology = &route.signed_topology_evidence;
    let peers_match = (&topology.initiator_peer_id == authority_peer_id
        || &topology.responder_peer_id == authority_peer_id)
        && (&topology.initiator_peer_id == executor_peer_id
            || &topology.responder_peer_id == executor_peer_id);
    if !peers_match {
        return Err("dispatch peers are outside signed topology".to_owned());
    }
    Ok(OwnerDispatchAuthorityProjection {
        schema_id: QUEST_C1_OWNER_PROJECTION_SCHEMA.to_owned(),
        authority_peer_id: authority_peer_id.to_string(),
        executor_peer_id: executor_peer_id.to_string(),
        peer_session_id: route.peer_session_id.to_string(),
        route_grant_id: route.grant_id.to_string(),
        route_authority_revision: peer.snapshot().pair_media_routes.authority_revision.get(),
        authority_runtime_host_id: route.authority_host_id.to_string(),
        authority_provider_epoch_id: route.authority_provider_epoch_id.to_string(),
        platform_runtime_spec_id: route.platform_runtime_spec_id.to_string(),
        authority_client_id: route.authority_client_id.to_string(),
        authority_runtime_lease_id: route.authority_runtime_lease_id.to_string(),
        signed_topology_sha256: typed_sha256(topology)?,
        route_configuration_sha256: route.transport.route_configuration_sha256,
        route_authority_evidence_sha256,
        expires_at_ms: route.expires_at_ms,
        authorization_kind: OwnerDispatchAuthorizationKind::CurrentRoute,
    })
}

fn cleanup_projection_from_retained(
    peer: &ManifoldPeerRuntimeHost,
    broker: &ManifoldBrokerRuntime,
    grant_id: &DottedId,
    authority_peer_id: &DottedId,
    executor_peer_id: &DottedId,
    ticket: &AndroidMediaExecutionTicket,
    now_ms: u64,
) -> Result<OwnerDispatchAuthorityProjection, String> {
    let snapshot = peer.snapshot();
    let snapshot_json = peer.snapshot_json().map_err(host_error)?;
    ManifoldPeerRuntimeHost::restart_from_json_with_live_broker_runtime(
        &snapshot_json,
        &snapshot.trust_policy,
        &snapshot.provider_epoch_id,
        broker,
    )
    .map_err(host_error)?;
    let route = snapshot
        .pair_media_routes
        .routes
        .iter()
        .find(|route| route.grant_id() == grant_id)
        .ok_or_else(|| "cleanup route is absent".to_owned())?;
    let rusty_manifold_peer::ManifoldAcceptedPairMediaRouteV2::CommonLan(route) = route else {
        return Err("owner dispatch requires common-LAN route".to_owned());
    };
    if route.lifecycle_status == ManifoldPairMediaRouteLifecycleStatus::Current
        || route.cleanup_status != ManifoldPairMediaRouteCleanupStatus::Pending
        || route.authority_provider_epoch_id.to_string() != ticket.authority_epoch_id
        || !ticket_targets_original_holder(
            &route.authority_client_id,
            &route.authority_runtime_lease_id,
            &ticket.client_id,
            &ticket.lease_id,
            ticket.operation,
        )
    {
        return Err("route has no retained cleanup authorization".to_owned());
    }
    let requester = DottedId::new(ticket.client_id.clone()).map_err(|error| error.to_string())?;
    let lease_id = DottedId::new(ticket.lease_id.clone()).map_err(|error| error.to_string())?;
    let lease = snapshot
        .media_command_runtime
        .leases
        .iter()
        .find(|lease| lease.lease_id == lease_id)
        .ok_or_else(|| "cleanup requester lease is absent".to_owned())?;
    let trusted_revoker = current_cleanup_requester(
        &route.authority_client_id,
        &route.authority_runtime_lease_id,
        &requester,
        &lease_id,
        lease,
        &snapshot.trust_policy.media_runtime_lease_scope_id,
        &snapshot.trust_policy.trusted_media_revoker_ids,
        now_ms,
    )?;
    // This dispatch schema has only one client/lease field, which is the media
    // ticket's immutable target. Revoker execution needs a distinct signed
    // requester binding; it cannot be represented by relabeling this ticket.
    if trusted_revoker {
        return Err("revoker owner dispatch requires distinct requester binding".to_owned());
    }
    let topology = &route.signed_topology_evidence;
    let peers_match = (&topology.initiator_peer_id == authority_peer_id
        || &topology.responder_peer_id == authority_peer_id)
        && (&topology.initiator_peer_id == executor_peer_id
            || &topology.responder_peer_id == executor_peer_id);
    if !peers_match
        || !peer
            .validate_peer_session_v2(&route.peer_session_id, now_ms)
            .current
    {
        return Err("cleanup peer authority is not current".to_owned());
    }
    let expires_at_ms = lease.expires_at_ms.min(topology.expires_at_ms);
    if expires_at_ms <= now_ms {
        return Err("cleanup authority expired".to_owned());
    }
    Ok(OwnerDispatchAuthorityProjection {
        schema_id: QUEST_C1_OWNER_PROJECTION_SCHEMA.to_owned(),
        authority_peer_id: authority_peer_id.to_string(),
        executor_peer_id: executor_peer_id.to_string(),
        peer_session_id: route.peer_session_id.to_string(),
        route_grant_id: route.grant_id.to_string(),
        route_authority_revision: snapshot.pair_media_routes.authority_revision.get(),
        authority_runtime_host_id: route.authority_host_id.to_string(),
        authority_provider_epoch_id: route.authority_provider_epoch_id.to_string(),
        platform_runtime_spec_id: route.platform_runtime_spec_id.to_string(),
        authority_client_id: route.authority_client_id.to_string(),
        authority_runtime_lease_id: route.authority_runtime_lease_id.to_string(),
        signed_topology_sha256: typed_sha256(topology)?,
        route_configuration_sha256: route.transport.route_configuration_sha256.clone(),
        route_authority_evidence_sha256: typed_sha256(route)?,
        expires_at_ms,
        authorization_kind: OwnerDispatchAuthorizationKind::RetainedCleanup,
    })
}

fn current_cleanup_requester(
    original_client_id: &DottedId,
    original_lease_id: &DottedId,
    requester_id: &DottedId,
    requester_lease_id: &DottedId,
    lease: &ManifoldRuntimeLease,
    required_scope: &DottedId,
    trusted_revoker_ids: &[DottedId],
    now_ms: u64,
) -> Result<bool, String> {
    if lease.lease_id != *requester_lease_id
        || lease.holder_id != *requester_id
        || lease.scope != *required_scope
        || lease.expires_at_ms <= now_ms
    {
        return Err("cleanup requester lease is not current".to_owned());
    }
    if original_client_id == requester_id && original_lease_id == requester_lease_id {
        return Ok(false);
    }
    if requester_id != original_client_id
        && trusted_revoker_ids.contains(requester_id)
        && lease.derivative_binding.is_none()
    {
        return Ok(true);
    }
    Err("cleanup requester is not original client or non-derivative trusted revoker".to_owned())
}

fn ticket_targets_original_holder(
    original_client_id: &DottedId,
    original_lease_id: &DottedId,
    ticket_client_id: &str,
    ticket_lease_id: &str,
    operation: rusty_quest_media_stream::MediaStreamPlatformOperation,
) -> bool {
    operation == rusty_quest_media_stream::MediaStreamPlatformOperation::Stop
        && ticket_client_id == original_client_id.as_str()
        && ticket_lease_id == original_lease_id.as_str()
}

fn verify_projection_against_retained(
    peer: &ManifoldPeerRuntimeHost,
    projection: &OwnerDispatchAuthorityProjection,
    signer_key_id: &str,
    now_ms: u64,
) -> Result<(), String> {
    if projection.expires_at_ms <= now_ms
        || !valid_sha256(&projection.route_authority_evidence_sha256)
    {
        return Err("owner projection expired or malformed".to_owned());
    }
    DottedId::new(projection.route_grant_id.clone()).map_err(|error| error.to_string())?;
    let session_id =
        DottedId::new(projection.peer_session_id.clone()).map_err(|error| error.to_string())?;
    let signer_key_id =
        DottedId::new(signer_key_id.to_owned()).map_err(|error| error.to_string())?;
    let snapshot = peer.snapshot();
    let session = peer.validate_peer_session_v2(&session_id, now_ms);
    let topology =
        snapshot
            .signed_topology_authorizations
            .iter()
            .find_map(|topology| match topology {
                rusty_manifold_peer::ManifoldSignedPeerTopologyAuthorizationV2::CommonLan(
                    value,
                ) if value.session_id == session_id => Some(value),
                _ => None,
            })
            .ok_or_else(|| "projected signed topology is absent".to_owned())?;
    let peers_match = (topology.initiator_peer_id.to_string() == projection.authority_peer_id
        || topology.responder_peer_id.to_string() == projection.authority_peer_id)
        && (topology.initiator_peer_id.to_string() == projection.executor_peer_id
            || topology.responder_peer_id.to_string() == projection.executor_peer_id);
    // The route belongs to the coordinator's Runtime Host and is joined to its
    // live Broker before signing. A sink has a distinct local Broker, so it
    // validates the signed remote route projection against its own retained
    // peer/session/topology authority rather than fabricating a local route.
    if !session.current
        || !peers_match
        || !topology.authorized
        || !topology.signer_key_ids.contains(&signer_key_id)
        || topology.expires_at_ms <= now_ms
        || topology.expires_at_ms < projection.expires_at_ms
        || topology.transport.route_configuration_sha256 != projection.route_configuration_sha256
    {
        return Err("owner projection is stale or altered".to_owned());
    }
    Ok(())
}

fn read_peer(
    lock: &Arc<RwLock<ManifoldPeerRuntimeHost>>,
) -> Result<std::sync::RwLockReadGuard<'_, ManifoldPeerRuntimeHost>, String> {
    lock.read()
        .map_err(|_| "peer authority lock poisoned".to_owned())
}
fn write_peer(
    lock: &Arc<RwLock<ManifoldPeerRuntimeHost>>,
) -> Result<std::sync::RwLockWriteGuard<'_, ManifoldPeerRuntimeHost>, String> {
    lock.write()
        .map_err(|_| "peer authority lock poisoned".to_owned())
}
fn read_broker(
    lock: &Arc<RwLock<ManifoldBrokerRuntime>>,
) -> Result<std::sync::RwLockReadGuard<'_, ManifoldBrokerRuntime>, String> {
    lock.read()
        .map_err(|_| "broker authority lock poisoned".to_owned())
}
fn host_error(error: ManifoldPeerRuntimeHostError) -> String {
    error.to_string()
}
fn typed_sha256<T: Serialize>(value: &T) -> Result<String, String> {
    let bytes = serde_json::to_vec(value).map_err(|error| error.to_string())?;
    Ok(format!("sha256:{:x}", Sha256::digest(bytes)))
}
fn valid_sha256(value: &str) -> bool {
    value.len() == 71
        && value.starts_with("sha256:")
        && value[7..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}
fn decode_array<const N: usize>(text: &str) -> Result<[u8; N], String> {
    if text.len() != N * 2 {
        return Err("invalid lowercase hex length".to_owned());
    }
    let mut out = [0; N];
    for (index, pair) in text.as_bytes().chunks_exact(2).enumerate() {
        out[index] = (hex(pair[0])? << 4) | hex(pair[1])?;
    }
    Ok(out)
}
fn hex(value: u8) -> Result<u8, String> {
    match value {
        b'0'..=b'9' => Ok(value - b'0'),
        b'a'..=b'f' => Ok(value - b'a' + 10),
        _ => Err("invalid lowercase hex".to_owned()),
    }
}

fn canonical_set(values: &[DottedId]) -> bool {
    values.windows(2).all(|pair| pair[0] < pair[1])
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_manifold_runtime_host::ManifoldRuntimeDerivativeLeaseBinding;

    #[test]
    fn cleanup_requester_requires_exact_original_or_trusted_revoker() {
        let original = DottedId::new("client.original").expect("original");
        let original_lease = DottedId::new("lease.original").expect("lease");
        let revoker = DottedId::new("client.revoker").expect("revoker");
        let revoker_lease = DottedId::new("lease.revoker").expect("revoker lease");
        let attacker = DottedId::new("client.attacker").expect("attacker");
        let scope = DottedId::new("scope.media").expect("scope");
        let mut lease = ManifoldRuntimeLease {
            lease_id: original_lease.clone(),
            scope: scope.clone(),
            holder_id: original.clone(),
            expires_at_ms: 200,
            derivative_binding: None,
        };
        let trusted = std::slice::from_ref(&revoker);
        let check = |lease: &ManifoldRuntimeLease,
                     requester: &DottedId,
                     requested_lease: &DottedId,
                     now| {
            current_cleanup_requester(
                &original,
                &original_lease,
                requester,
                requested_lease,
                lease,
                &scope,
                trusted,
                now,
            )
        };
        assert_eq!(check(&lease, &original, &original_lease, 100), Ok(false));
        assert!(check(&lease, &original, &revoker_lease, 100).is_err());
        assert!(check(&lease, &original, &original_lease, 200).is_err());
        lease.lease_id = revoker_lease.clone();
        lease.holder_id = revoker.clone();
        assert_eq!(check(&lease, &revoker, &revoker_lease, 100), Ok(true));
        assert!(check(&lease, &attacker, &revoker_lease, 100).is_err());
        lease.derivative_binding = Some(ManifoldRuntimeDerivativeLeaseBinding {
            schema_id: SchemaId::new("rusty.manifold.runtime_host.derivative_lease_binding.v1")
                .expect("schema"),
            binding_id: DottedId::new("binding.revoker").expect("binding"),
            provider_epoch_id: DottedId::new("epoch.one").expect("epoch"),
            upstream_control_lease_id: original_lease.clone(),
            source_authorization_id: DottedId::new("authorization.revoker").expect("source"),
        });
        assert!(check(&lease, &revoker, &revoker_lease, 100).is_err());
    }

    #[test]
    fn retained_cleanup_ticket_never_relabels_original_target_as_revoker() {
        let original = DottedId::new("client.original").expect("original");
        let original_lease = DottedId::new("lease.original").expect("lease");
        use rusty_quest_media_stream::MediaStreamPlatformOperation::{Start, Stop};
        assert!(ticket_targets_original_holder(
            &original,
            &original_lease,
            "client.original",
            "lease.original",
            Stop,
        ));
        assert!(!ticket_targets_original_holder(
            &original,
            &original_lease,
            "client.revoker",
            "lease.revoker",
            Stop,
        ));
        assert!(!ticket_targets_original_holder(
            &original,
            &original_lease,
            "client.original",
            "lease.revoker",
            Stop,
        ));
        assert!(!ticket_targets_original_holder(
            &original,
            &original_lease,
            "client.original",
            "lease.original",
            Start,
        ));
    }
}
