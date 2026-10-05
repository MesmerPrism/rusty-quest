//! Typed bridge between source-issued action and independently prepared target Stop.
//! The actual target registry readback remains byte-exact inside its signed response.
use crate::*;
use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
const PREPARE_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_cleanup_prepare.v1\0";
const ABORT_PREPARE_DOMAIN: &[u8] = b"rusty.quest.android.media.retained_abort_prepare.v2\0";

/// Closed original-Start rollback carrier. This is never a new Start.
pub fn is_retained_start_abort_ticket(ticket: &AndroidMediaExecutionTicket) -> bool {
    use rusty_quest_media_stream::{MediaStreamOwnerActionKind, MediaStreamPlatformOperation};
    ticket.operation == MediaStreamPlatformOperation::Start
        && ticket.action_id.ends_with(".abort")
        && matches!(
            ticket.action_kind,
            MediaStreamOwnerActionKind::Stop | MediaStreamOwnerActionKind::Cleanup
        )
}

/// Projects a locally verified target Stop onto its original rollback carrier.
/// Target bytes/verification are checked first; this creates no target effect.
pub fn retained_local_source_readback(
    source: &AndroidMediaExecutionTicket,
    target: &AndroidMediaExecutionTicket,
    effect: AuthenticatedOwnerEffect,
) -> Result<AndroidMediaOwnerReadback, String> {
    use rusty_quest_media_stream::MediaStreamPlatformOperation;
    let mut expected = source.clone();
    expected.operation = MediaStreamPlatformOperation::Stop;
    if !(source.operation == MediaStreamPlatformOperation::Stop
        || is_retained_start_abort_ticket(source))
        || target != &expected
    {
        return Err("retained local original/target differs".into());
    }
    crate::owner_dispatch::validate_effect(target, AndroidMediaExecutionMode::Execute, &effect)?;
    if !effect.verified.terminal {
        return Err("retained local Stop remains nonterminal".into());
    }
    let mut readback = effect.readback;
    readback.operation = source.operation;
    Ok(readback)
}
/// Target-authored fixed preparation response. No caller target ticket is accepted.
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct RetainedCleanupPreparedStop {
    /// Closed schema.
    pub schema_id: String,
    /// Exact signed prepare request digest.
    pub prepare_request_sha256: String,
    /// Stable bounded dispatch identity.
    pub dispatch_id: String,
    /// Actual target native preparation-state revision, separate from retained media revisions.
    pub target_preparation_revision: u64,
    /// Source-issued action, distinct from target execution identity.
    pub source_ticket: AndroidMediaExecutionTicket,
    /// Target independently derives this from retained original owner state.
    pub target_ticket: AndroidMediaExecutionTicket,
    /// Target independently joined current requester and retained original target.
    pub authority: RetainedCleanupAuthorityProjection,
    /// Actual enrolled target signer ID.
    pub signer_key_id: String,
    /// Domain-separated target signature.
    pub signature_base64: String,
}
/// Exact typed remote effect retained in source completion.
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct RemoteRetainedCleanupEffect {
    /// Closed schema.
    pub schema_id: String,
    /// Exact signed preparation response.
    pub prepared: RetainedCleanupPreparedStop,
    /// Exact signed commit request.
    pub commit: RetainedCleanupDispatchRequest,
    /// Exact raw target response; contains byte-exact actual Java readback.
    pub response_bytes: Vec<u8>,
    /// Key fixed from authenticated installed peer enrollment by executor.
    pub enrolled_target_key: [u8; 32],
}
/// Canonical response signature excludes only its signature field.
pub fn retained_cleanup_prepared_signing_bytes(
    value: &RetainedCleanupPreparedStop,
) -> Result<Vec<u8>, String> {
    let mut value = value.clone();
    value.signature_base64.clear();
    let mut bytes = match value.schema_id.as_str() {
        "rusty.quest.android.media.retained_cleanup_prepared_stop.v1" => PREPARE_DOMAIN,
        "rusty.quest.android.media.retained_abort_prepared_stop.v2" => ABORT_PREPARE_DOMAIN,
        _ => return Err("unknown cleanup preparation schema".into()),
    }
    .to_vec();
    bytes.extend(serde_json::to_vec(&value).map_err(|_| "prepare encode")?);
    Ok(bytes)
}
/// Verify with independently supplied current peer key, never a request-selected oracle.
pub fn verify_retained_cleanup_prepared(
    value: &RetainedCleanupPreparedStop,
    source: &AndroidMediaExecutionTicket,
    target_peer: &str,
    key_id: &str,
    key: &[u8; 32],
) -> Result<(), String> {
    use rusty_quest_media_stream::MediaStreamPlatformOperation;
    let source_allowed = match value.schema_id.as_str() {
        "rusty.quest.android.media.retained_cleanup_prepared_stop.v1" => {
            source.operation == MediaStreamPlatformOperation::Stop
        }
        "rusty.quest.android.media.retained_abort_prepared_stop.v2" => {
            is_retained_start_abort_ticket(source)
        }
        _ => false,
    };
    if !source_allowed
        || &value.source_ticket != source
        || value.authority.executor_peer_id != target_peer
        || value.signer_key_id != key_id
        || value.dispatch_id.is_empty()
        || value.target_preparation_revision == 0
        || value.prepare_request_sha256.len() != 71
        || value.target_ticket.operation != MediaStreamPlatformOperation::Stop
        || value.target_ticket.capability == source.capability
        || value.target_ticket.action_id == source.action_id
        || source.client_id != value.target_ticket.client_id
        || source.lease_id != value.target_ticket.lease_id
        || source.authority_epoch_id != value.target_ticket.authority_epoch_id
        || source.owner_kind != value.target_ticket.owner_kind
        || source.owner_id != value.target_ticket.owner_id
        || source.provider_kind != value.target_ticket.provider_kind
        || source.resource_id != value.target_ticket.resource_id
    {
        return Err("independent prepared Stop binding differs".into());
    }
    let signature = crate::owner_dispatch::decode_signature_base64(&value.signature_base64)?;
    VerifyingKey::from_bytes(key)
        .map_err(|_| "prepare key")?
        .verify_strict(
            &retained_cleanup_prepared_signing_bytes(value)?,
            &Signature::from_bytes(&signature),
        )
        .map_err(|_| "prepare signature".into())
}
impl RemoteRetainedCleanupEffect {
    /// Verify before retaining source completion; supplied key comes from native enrollment.
    pub fn verify(
        &self,
        source: &AndroidMediaExecutionTicket,
        key_id: &str,
        key: &[u8; 32],
    ) -> Result<AuthenticatedOwnerEffect, String> {
        let schema_allowed = match self.schema_id.as_str() {
            "rusty.quest.android.media.remote_retained_cleanup_effect.v1" => {
                self.prepared.schema_id
                    == "rusty.quest.android.media.retained_cleanup_prepared_stop.v1"
            }
            "rusty.quest.android.media.remote_retained_abort_effect.v2" => {
                self.prepared.schema_id
                    == "rusty.quest.android.media.retained_abort_prepared_stop.v2"
                    && is_retained_start_abort_ticket(source)
            }
            _ => false,
        };
        if !schema_allowed
            || &self.enrolled_target_key != key
            || self.response_bytes.len() > 128 * 1024
            || self.commit.dispatch_id != self.prepared.dispatch_id
            || self.commit.cleanup.target != self.prepared.target_ticket
            || self.commit.cleanup.authority != self.prepared.authority
        {
            return Err("remote cleanup proof differs".into());
        }
        verify_retained_cleanup_prepared(
            &self.prepared,
            source,
            &self.prepared.authority.executor_peer_id,
            key_id,
            key,
        )?;
        let response = verify_retained_cleanup_response(
            &self.response_bytes,
            &self.commit,
            &self.prepared.target_ticket,
            &self.prepared.authority.executor_peer_id,
            key_id,
            key,
        )?;
        if response.status != OwnerDispatchStatus::Completed {
            return Err("remote cleanup remains uncertain".into());
        }
        let effect = response.effect.ok_or("remote cleanup effect missing")?;
        if effect.readback.remote_cleanup.is_some() {
            return Err("recursive cleanup proof".into());
        }
        Ok(effect)
    }
    /// Source wrapper binds its action to actual target effect without rewriting target bytes.
    pub fn validate_source_readback(
        &self,
        source: &AndroidMediaExecutionTicket,
        wrapper: &AndroidMediaOwnerReadback,
    ) -> Result<(), String> {
        let effect = self.verify(
            source,
            &self.prepared.signer_key_id,
            &self.enrolled_target_key,
        )?;
        if wrapper.provider_handle_id != effect.readback.provider_handle_id
            || wrapper.provider_state_revision != effect.readback.provider_state_revision
            || wrapper.observed_state != effect.readback.observed_state
            || wrapper.receipt_id
                != format!("remote-cleanup.{:x}", Sha256::digest(&self.response_bytes))
            || !effect.verified.terminal
        {
            return Err("source wrapper differs from actual remote Stop".into());
        }
        Ok(())
    }
    /// Construct explicit source receipt while preserving raw actual target effect nested above.
    pub fn source_readback(
        self,
        source: &AndroidMediaExecutionTicket,
        key_id: &str,
        key: &[u8; 32],
    ) -> Result<AndroidMediaOwnerReadback, String> {
        let effect = self.verify(source, key_id, key)?;
        let mut wrapper = effect.readback;
        wrapper.capability = source.capability.clone();
        wrapper.executor_generation = source.executor_generation;
        wrapper.action_id = source.action_id.clone();
        wrapper.authority_epoch_id = source.authority_epoch_id.clone();
        wrapper.media_acceptance_authority_revision = source.media_acceptance_authority_revision;
        wrapper.expected_runtime_revision = source.expected_runtime_revision;
        wrapper.client_id = source.client_id.clone();
        wrapper.lease_id = source.lease_id.clone();
        wrapper.sequence = source.sequence;
        wrapper.operation = source.operation;
        wrapper.owner_kind = source.owner_kind;
        wrapper.action_kind = source.action_kind;
        wrapper.owner_id = source.owner_id.clone();
        wrapper.provider_kind = source.provider_kind.clone();
        wrapper.resource_id = source.resource_id.clone();
        wrapper.receipt_id = format!("remote-cleanup.{:x}", Sha256::digest(&self.response_bytes));
        wrapper.remote_cleanup = Some(Box::new(self));
        Ok(wrapper)
    }
}

/// Derive a fresh target-owned Stop solely from its independently retained original Start.
/// Historical media/runtime revisions remain original evidence; native preparation revision is separate.
pub fn derive_retained_target_stop(
    original: &AndroidMediaExecutionTicket,
    generation: u64,
    entropy: &str,
) -> Result<AndroidMediaExecutionTicket, String> {
    use rusty_quest_media_stream::{
        MediaStreamOwnerActionKind, MediaStreamOwnerKind, MediaStreamPlatformOperation,
    };
    if original.operation != MediaStreamPlatformOperation::Start
        || generation == 0
        || entropy.len() != 32
        || !entropy.bytes().all(|b| b.is_ascii_hexdigit())
        || original.sequence == 0
        || original.sequence > 7
    {
        return Err("independent original Start/entropy unavailable".into());
    }
    let mut target = original.clone();
    target.capability = format!("cleanup.capability.{entropy}");
    target.action_id = format!("cleanup.action.{entropy}");
    target.executor_generation = generation;
    target.operation = MediaStreamPlatformOperation::Stop;
    target.action_kind = if original.owner_kind == MediaStreamOwnerKind::Cleanup {
        MediaStreamOwnerActionKind::Cleanup
    } else {
        MediaStreamOwnerActionKind::Stop
    };
    target.sequence = 8 - original.sequence;
    Ok(target)
}
