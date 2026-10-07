//! Actual independent revoker recovery, retaining each owner transition before
//! physical Stop. The original client/lease remains the cleanup subject.
use super::*;
use rusty_manifold_media_session::{
    media_session_termination_params_digest, ManifoldMediaSessionMutationReceipt,
    ManifoldMediaSessionTerminationAction, ManifoldMediaSessionTerminationRequest,
    MANIFOLD_MEDIA_SESSION_REVOKE_COMMAND, MANIFOLD_MEDIA_SESSION_TERMINATION_REQUEST_SCHEMA,
};
use rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionReceipt;
use rusty_quest_media_stream::MediaStreamTrustedRevokerCleanupEvidence;

/// Actual retained trusted Revoke and the corresponding physical Stop action.
#[derive(Clone, Debug, Serialize)]
pub struct QuestConcurrentPeerRevokerCleanupReceipt {
    /// Closed response schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Original physical subject.
    pub client_id: DottedId,
    /// Actual owner proof, carrying no invented ordinary Stop grant.
    pub recovery: MediaStreamTrustedRevokerCleanupEvidence,
    /// Exact prepared seven-owner Stop.
    pub action: MediaStreamPlatformAction,
}

#[derive(Default)]
pub(super) struct QuestConcurrentRevokerCleanupState {
    pending: Option<Pending>,
    completed: Vec<QuestConcurrentPeerRevokerCleanupReceipt>,
}
struct Pending {
    client_id: DottedId,
    adoption: ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionReceipt,
    request: ManifoldMediaSessionTerminationRequest,
    command: ManifoldRuntimeCommandRequest,
    action_id: String,
    termination: Option<ManifoldMediaSessionMutationReceipt>,
}

fn reject() -> QuestBrokerRuntimeError {
    QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected
}
fn id(text: String) -> Result<DottedId, QuestBrokerRuntimeError> {
    DottedId::new(text).map_err(|_| reject())
}
impl QuestBrokerRuntimeProvider {
    /// Reads whether the original subject's actual holder authority expired or
    /// was revoked.
    /// It never interprets a failed mutation as expiry.
    pub fn concurrent_peer_requires_revoker_cleanup(
        &self,
        client_id: &DottedId,
        now_ms: u64,
    ) -> Result<bool, QuestBrokerRuntimeError> {
        let owner = self
            .runtime
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let shared = owner
            .peer_runtime_host
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let peer = shared
            .read()
            .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let media = owner.media_sessions.get(client_id).ok_or_else(reject)?;
        let accepted = media
            .current_acceptance()
            .session
            .as_ref()
            .ok_or_else(reject)?;
        let broker = owner
            .runtime
            .read()
            .map_err(|_| QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        let grant = broker
            .admission_snapshot()
            .grants
            .iter()
            .find(|grant| grant.grant_id == accepted.admission_grant_id)
            .ok_or_else(reject)?;
        let inner = peer
            .snapshot()
            .media_command_runtime
            .leases
            .iter()
            .find(|lease| lease.lease_id == accepted.runtime_lease_id);
        Ok(grant.revoked
            || accepted.expires_at_ms <= now_ms
            || grant.expires_at_ms <= now_ms
            || inner.is_none_or(|lease| lease.expires_at_ms <= now_ms))
    }

    /// Prepares cleanup from an actual trusted Revoke, including expired original
    /// authority. Exact pending owner requests survive uncertain transitions.
    pub fn review_concurrent_peer_revoker_cleanup(
        &mut self,
        client_id: &DottedId,
        adoption: &ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionReceipt,
        now_ms: u64,
        entropy_hex: &str,
    ) -> Result<MediaStreamTrustedRevokerCleanupEvidence, QuestBrokerRuntimeError> {
        parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
        let owner = self
            .runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let current_decision = owner
            .media_sessions
            .get(client_id)
            .ok_or_else(reject)?
            .current_acceptance()
            .session
            .as_ref()
            .ok_or_else(reject)?
            .decision_id
            .clone();
        if let Some(prior) = owner.concurrent_revoker_cleanup.completed.iter().find(|r| {
            &r.client_id == client_id
                && r.recovery.request.decision_id == current_decision
                && r.recovery.adoption.runtime_adoption.adoption_id
                    == adoption.runtime_adoption.adoption_id
        }) {
            let mut recovery = prior.recovery.clone();
            recovery.cleanup_requester_adoption = Some(adoption.clone());
            let peer = owner
                .peer_runtime_host
                .as_ref()
                .ok_or_else(reject)?
                .read()
                .map_err(|_| reject())?;
            owner
                .media_sessions
                .get(client_id)
                .ok_or_else(reject)?
                .validate_trusted_cleanup_authority(&peer, &recovery, now_ms)
                .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
            return Ok(recovery);
        }
        let shared = owner
            .peer_runtime_host
            .clone()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut peer = shared
            .write()
            .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        if owner.concurrent_revoker_cleanup.pending.is_none() {
            let media = owner.media_sessions.get(client_id).ok_or_else(reject)?;
            let accepted = media
                .current_acceptance()
                .session
                .as_ref()
                .ok_or_else(reject)?;
            if &accepted.runtime_client_id != client_id || adoption.lease.expires_at_ms <= now_ms {
                return Err(reject());
            }
            let request = ManifoldMediaSessionTerminationRequest {
                schema_id: schema(MANIFOLD_MEDIA_SESSION_TERMINATION_REQUEST_SCHEMA),
                request_id: id(format!(
                    "request.quest.concurrent.media-revoke.{entropy_hex}"
                ))?,
                expected_authority_revision: peer.snapshot().media_sessions.authority_revision,
                runtime_command_request_id: id(format!(
                    "request.quest.concurrent.media-revoke-command.{entropy_hex}"
                ))?,
                decision_id: accepted.decision_id.clone(),
                session_id: accepted.session_id.clone(),
                expected_provider_epoch_id: owner.provider_epoch_id.clone(),
                action: ManifoldMediaSessionTerminationAction::Revoke,
            };
            let command = ManifoldRuntimeCommandRequest {
                schema_id: schema(HOST_COMMAND_REQUEST_SCHEMA),
                request_id: request.runtime_command_request_id.clone(),
                expected_authority_revision: peer
                    .snapshot()
                    .media_command_runtime
                    .authority_revision,
                requester_id: adoption.revoker_id.clone(),
                command_id: DottedId::new(MANIFOLD_MEDIA_SESSION_REVOKE_COMMAND)
                    .expect("fixed Revoke"),
                lease_id: Some(adoption.lease.lease_id.clone()),
                params_digest: Some(
                    media_session_termination_params_digest(&request).map_err(|_| reject())?,
                ),
                issued_at_ms: now_ms,
                expires_at_ms: adoption.lease.expires_at_ms,
            };
            owner.concurrent_revoker_cleanup.pending = Some(Pending {
                client_id: client_id.clone(),
                adoption: adoption.clone(),
                request,
                command,
                action_id: format!("platform.concurrent.trusted-revoke.{entropy_hex}"),
                termination: None,
            });
        }
        let pending = owner
            .concurrent_revoker_cleanup
            .pending
            .as_mut()
            .ok_or_else(reject)?;
        if &pending.client_id != client_id {
            return Err(reject());
        }
        if pending.termination.is_none() {
            let terminated = peer
                .review_media_session_revoker_recovery(
                    &pending.request,
                    &pending.command,
                    &pending.adoption,
                    now_ms,
                )
                .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
            if !terminated.applied {
                return Err(reject());
            }
            pending.termination = Some(terminated);
        }
        let recovery = MediaStreamTrustedRevokerCleanupEvidence {
            adoption: pending.adoption.clone(),
            cleanup_requester_adoption: Some(adoption.clone()),
            request: pending.request.clone(),
            termination: pending.termination.clone().ok_or_else(reject)?,
        };
        owner
            .media_sessions
            .get(client_id)
            .ok_or_else(reject)?
            .validate_trusted_cleanup_authority(&peer, &recovery, now_ms)
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        Ok(recovery)
    }

    /// Prepares the exact physical Stop after independently reviewed Revoke.
    /// A pending failed Start must first finish its retained rollback.
    pub fn prepare_concurrent_peer_revoker_cleanup(
        &mut self,
        client_id: &DottedId,
        adoption: &ManifoldPeerRuntimeTrustedMediaRevokerLeaseAdoptionReceipt,
        now_ms: u64,
        entropy_hex: &str,
    ) -> Result<QuestConcurrentPeerRevokerCleanupReceipt, QuestBrokerRuntimeError> {
        let recovery =
            self.review_concurrent_peer_revoker_cleanup(client_id, adoption, now_ms, entropy_hex)?;
        let owner = self
            .runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        if let Some(prior) = owner
            .concurrent_revoker_cleanup
            .completed
            .iter()
            .find(|receipt| {
                &receipt.client_id == client_id && receipt.recovery.request == recovery.request
            })
        {
            return Ok(prior.clone());
        }
        let shared = owner.peer_runtime_host.clone().ok_or_else(reject)?;
        let peer = shared.read().map_err(|_| reject())?;
        let action_id = owner
            .concurrent_revoker_cleanup
            .pending
            .as_ref()
            .ok_or_else(reject)?
            .action_id
            .clone();
        let action = owner
            .media_sessions
            .get_mut(client_id)
            .ok_or_else(reject)?
            .prepare_trusted_revoker_cleanup(&peer, recovery.clone(), action_id, now_ms)
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        let receipt = QuestConcurrentPeerRevokerCleanupReceipt {
            schema_id: "rusty.quest.concurrent.peer_revoker_cleanup_receipt.v1".to_owned(),
            client_id: client_id.clone(),
            recovery,
            action,
        };
        owner
            .concurrent_revoker_cleanup
            .completed
            .push(receipt.clone());
        owner.concurrent_revoker_cleanup.pending = None;
        Ok(receipt)
    }
}
