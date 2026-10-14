//! Receiver-local incarnation binding for authenticated ordinary remote effects.
use crate::owner_dispatch::{
    decode_response_frame, encode_request_frame, sha256_prefixed, validate_effect,
};
use crate::*;
use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};

/// Original source action and its independently installed target incarnation.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct OwnerTargetBinding {
    /// Original authenticated source ticket, before receiver translation.
    pub source: AndroidMediaExecutionTicket,
    /// Target execution identity, changing only native registry generation.
    pub target: AndroidMediaExecutionTicket,
    /// Last cleanup attempt retained before its call, for exact uncertain-Stop compensation.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cleanup_source: Option<AndroidMediaExecutionTicket>,
    /// Successful terminal cleanup allows a later Start in this owner slot.
    pub terminal: bool,
}

pub(crate) fn target_binding_key(request: &OwnerDispatchRequest) -> Result<String, String> {
    let t = &request.ticket;
    serde_json::to_vec(&(
        &request.authority.authority_peer_id,
        &request.authority.authority_runtime_host_id,
        &request.authority.route_grant_id,
        &t.authority_epoch_id,
        &t.client_id,
        &t.lease_id,
        t.owner_kind,
        &t.owner_id,
        &t.provider_kind,
        &t.resource_id,
    ))
    .map(|bytes| sha256_prefixed(&bytes))
    .map_err(|_| "target binding key".into())
}

pub(crate) fn is_terminal_owner_request(request: &OwnerDispatchRequest) -> bool {
    request.mode == AndroidMediaExecutionMode::CompensateUncertain
        || matches!(
            request.ticket.action_kind,
            MediaStreamOwnerActionKind::Stop | MediaStreamOwnerActionKind::Cleanup
        )
}

pub(crate) fn valid_retained_cleanup_source(
    original: &AndroidMediaExecutionTicket,
    cleanup: &AndroidMediaExecutionTicket,
) -> bool {
    if original == cleanup {
        return true;
    }
    cleanup.executor_generation == original.executor_generation
        && cleanup.authority_epoch_id == original.authority_epoch_id
        && cleanup.client_id == original.client_id
        && cleanup.lease_id == original.lease_id
        && cleanup.owner_kind == original.owner_kind
        && cleanup.owner_id == original.owner_id
        && cleanup.provider_kind == original.provider_kind
        && cleanup.resource_id == original.resource_id
        && matches!(
            cleanup.action_kind,
            MediaStreamOwnerActionKind::Stop | MediaStreamOwnerActionKind::Cleanup
        )
        && (cleanup.operation == MediaStreamPlatformOperation::Stop
            || (cleanup.action_id == format!("{}.abort", original.action_id)
                && cleanup.media_acceptance_authority_revision
                    == original.media_acceptance_authority_revision
                && cleanup.expected_runtime_revision == original.expected_runtime_revision))
}

pub(crate) fn bind_target_ticket(
    request: &OwnerDispatchRequest,
    generation: u64,
    existing: Option<&OwnerTargetBinding>,
) -> Result<(AndroidMediaExecutionTicket, OwnerTargetBinding), String> {
    if generation == 0 {
        return Err("target generation unavailable".into());
    }
    let source = &request.ticket;
    let terminal = is_terminal_owner_request(request);
    if let Some(binding) = existing {
        validate_target_ticket(&binding.source, &binding.target)?;
        if binding.target.executor_generation != generation {
            return Err("target incarnation changed".into());
        }
        if !terminal && binding.source == *source {
            return Err("source effect already bound to another dispatch".into());
        }
        if !terminal && binding.source != *source && !binding.terminal {
            return Err("target owner has retained prior action".into());
        }
        if terminal {
            let original = &binding.source;
            if request.mode == AndroidMediaExecutionMode::Execute
                && binding.cleanup_source.as_ref() == Some(source)
            {
                return Err("cleanup effect already bound to another dispatch".into());
            }
            if source.executor_generation != original.executor_generation
                || source.authority_epoch_id != original.authority_epoch_id
                || source.client_id != original.client_id
                || source.lease_id != original.lease_id
                || source.owner_kind != original.owner_kind
                || source.owner_id != original.owner_id
                || source.provider_kind != original.provider_kind
                || source.resource_id != original.resource_id
                || (request.mode == AndroidMediaExecutionMode::CompensateUncertain
                    && source != original
                    && binding.cleanup_source.as_ref() != Some(source))
                || (source.operation == MediaStreamPlatformOperation::Start
                    && request.mode != AndroidMediaExecutionMode::CompensateUncertain
                    && (source.action_id != format!("{}.abort", original.action_id)
                        || source.media_acceptance_authority_revision
                            != original.media_acceptance_authority_revision
                        || source.expected_runtime_revision != original.expected_runtime_revision))
            {
                return Err("target cleanup lineage differs".into());
            }
            let mut target = source.clone();
            target.executor_generation = binding.target.executor_generation;
            let mut retained = binding.clone();
            retained.cleanup_source = Some(source.clone());
            retained.terminal = false;
            return Ok((target, retained));
        }
    } else if terminal {
        return Err("target original effect binding missing".into());
    }
    let mut target = source.clone();
    target.executor_generation = generation;
    Ok((
        target.clone(),
        OwnerTargetBinding {
            source: source.clone(),
            target,
            cleanup_source: None,
            terminal: false,
        },
    ))
}

pub(crate) fn validate_target_ticket(
    source: &AndroidMediaExecutionTicket,
    target: &AndroidMediaExecutionTicket,
) -> Result<(), String> {
    let mut expected = source.clone();
    expected.executor_generation = target.executor_generation;
    if target.executor_generation == 0 || &expected != target {
        return Err("remote source/target ticket mismatch".into());
    }
    Ok(())
}

/// Exact signed target response and original signed request retained by the source.
/// Construction verifies against independently enrolled peer identity. Later
/// readback validation checks integrity of that already authenticated envelope.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RemoteOwnerDispatchProof {
    /// Original signed source request; its ticket is never rewritten.
    pub request: OwnerDispatchRequest,
    /// Signed response containing target ticket and untouched Java readback bytes.
    pub response_bytes: Vec<u8>,
    /// Executor key selected from native enrollment by the trusted source executor.
    pub enrolled_target_key: [u8; 32],
    /// Executor key identity selected independently of response fields.
    pub enrolled_target_key_id: String,
}

impl RemoteOwnerDispatchProof {
    /// Verify the exact original/target/readback join against fixed enrolled identity.
    pub fn verify(
        &self,
        source: &AndroidMediaExecutionTicket,
        key_id: &str,
        key: &[u8; 32],
    ) -> Result<AuthenticatedOwnerEffect, String> {
        if &self.request.ticket != source
            || &self.enrolled_target_key != key
            || self.enrolled_target_key_id != key_id
            || self.response_bytes.len() > MAX_OWNER_DISPATCH_FRAME_BYTES
        {
            return Err("remote owner proof identity differs".into());
        }
        let response = decode_response_frame(&self.response_bytes)?;
        if response.schema_id != OWNER_DISPATCH_RESPONSE_SCHEMA
            || response.dispatch_id != self.request.dispatch_id
            || response.request_sha256 != sha256_prefixed(&encode_request_frame(&self.request)?)
            || response.signer_key_id != key_id
            || response.status != OwnerDispatchStatus::Completed
            || response.failure.is_some()
        {
            return Err("remote owner response binding differs".into());
        }
        VerifyingKey::from_bytes(key)
            .map_err(|_| "remote owner key")?
            .verify_strict(
                &response_signing_bytes(&response)?,
                &Signature::from_bytes(&decode_signature_base64(&response.signature_base64)?),
            )
            .map_err(|_| "remote owner signature")?;
        let target = response
            .target_ticket
            .as_ref()
            .ok_or("remote target ticket missing")?;
        validate_target_ticket(source, target)?;
        let effect = response.effect.ok_or("remote target evidence missing")?;
        if effect.remote_dispatch.is_some()
            || effect.readback.remote_dispatch.is_some()
            || effect.readback.remote_cleanup.is_some()
        {
            return Err("recursive remote owner proof".into());
        }
        validate_effect(target, self.request.mode, &effect)?;
        Ok(effect)
    }

    /// Project source action identity without changing target evidence or Java hashes.
    pub fn source_readback(
        self,
        source: &AndroidMediaExecutionTicket,
        key_id: &str,
        key: &[u8; 32],
    ) -> Result<AndroidMediaOwnerReadback, String> {
        let effect = self.verify(source, key_id, key)?;
        let mut wrapper = effect.readback;
        wrapper.executor_generation = source.executor_generation;
        wrapper.receipt_id = format!("remote-owner.{}", sha256_prefixed(&self.response_bytes));
        wrapper.remote_dispatch = Some(Box::new(self));
        Ok(wrapper)
    }

    /// Check a retained source wrapper against its authenticated target evidence.
    pub fn validate_source_readback(
        &self,
        source: &AndroidMediaExecutionTicket,
        wrapper: &AndroidMediaOwnerReadback,
    ) -> Result<(), String> {
        let expected = self.clone().source_readback(
            source,
            &self.enrolled_target_key_id,
            &self.enrolled_target_key,
        )?;
        if &expected != wrapper {
            return Err("remote source wrapper differs".into());
        }
        Ok(())
    }
}
