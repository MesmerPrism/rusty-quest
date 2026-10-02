//! Reconcile incoming owners from retained registry facts, never a local Start.
use rusty_quest_media_stream_android::{
    validate_readback, AndroidMediaExecutionTicket, AndroidMediaOwnerReadback,
    AuthenticatedOwnerEffect, MediaStreamPlatformOperation, VERIFIED_OWNER_EFFECT_SCHEMA,
    OwnerDispatchReplaySnapshot, RetainedCleanupReplaySnapshot,
};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct CompletedStop {
    pub(super) ticket: AndroidMediaExecutionTicket,
    pub(super) effect: AuthenticatedOwnerEffect,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(super) struct Original {
    pub(super) ticket: AndroidMediaExecutionTicket,
    pub(super) effect: Option<AuthenticatedOwnerEffect>,
    #[serde(default)]
    pub(super) completed_stop: Option<CompletedStop>,
}

pub(super) fn terminal_owners<'a>(originals: impl Iterator<Item = &'a Original>, generation: u64)
    -> Result<Vec<&'a Original>, String> {
    let owners: Vec<_> = originals.collect();
    if owners.is_empty() { return Err("actual incoming owner target absent".into()); }
    for original in &owners {
        let stop = original.completed_stop.as_ref().ok_or("incoming owner Stop remains Pending")?;
        require_stop(&original.ticket, original.effect.as_ref(), stop, generation)?;
    }
    Ok(owners)
}

pub(super) fn require_quiet_replay(ordinary: &OwnerDispatchReplaySnapshot,
    retained: &RetainedCleanupReplaySnapshot) -> Result<(), String> {
    retained.validate().map_err(|_| "incoming cleanup replay uncertain")?;
    if !ordinary.pending_request_sha256.is_empty() || !retained.pending_request_sha256.is_empty() {
        return Err("incoming owner dispatch remains Pending".into());
    }
    Ok(())
}

fn digest(value: &str) -> bool {
    value.len() == 71 && value.starts_with("sha256:")
        && value[7..].bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub(super) fn same_target(a: &AndroidMediaExecutionTicket, b: &AndroidMediaExecutionTicket) -> bool {
    a.authority_epoch_id == b.authority_epoch_id
        && a.client_id == b.client_id && a.lease_id == b.lease_id
        && a.owner_kind == b.owner_kind && a.owner_id == b.owner_id
        && a.provider_kind == b.provider_kind && a.resource_id == b.resource_id
}

pub(super) fn retain_completed_stop(original: Option<&mut Original>,
    ticket: &AndroidMediaExecutionTicket, effect: &AuthenticatedOwnerEffect,
    generation: u64) -> Result<bool, String> {
    let Some(original) = original else { return Ok(false); };
    let stop = CompletedStop {ticket:ticket.clone(), effect:effect.clone()};
    require_stop(&original.ticket, original.effect.as_ref(), &stop, generation)?;
    original.completed_stop = Some(stop);
    Ok(true)
}

// Called only with an effect returned by the actual authenticated registry.
// Persist the native ticket too: a Stop's own capability is not a Start proof.
pub(super) fn require_stop(original: &AndroidMediaExecutionTicket,
    start_effect: Option<&AuthenticatedOwnerEffect>, stop: &CompletedStop,
    generation: u64) -> Result<(), String> {
    let ticket = &stop.ticket;
    let effect = &stop.effect;
    let verified = &effect.verified;
    let parsed: AndroidMediaOwnerReadback = serde_json::from_str(&effect.readback_json)
        .map_err(|_| "incoming Stop readback encoding")?;
    let hash = format!("sha256:{}", rusty_quest_broker_authority::packaged_json_sha256(&effect.readback_json));
    if original.operation != MediaStreamPlatformOperation::Start
        || ticket.operation != MediaStreamPlatformOperation::Stop
        || generation == 0 || original.executor_generation == 0
        || ticket.executor_generation != generation || !same_target(original, ticket)
        || ticket.expected_runtime_revision < original.expected_runtime_revision
        || ticket.media_acceptance_authority_revision < original.media_acceptance_authority_revision
        || parsed != effect.readback || validate_readback(ticket, &effect.readback).is_err()
        || verified.schema_id != VERIFIED_OWNER_EFFECT_SCHEMA || !verified.terminal
        || verified.receipt_id != effect.readback.receipt_id
        || verified.readback_sha256 != hash || verified.executor_generation != generation
        || verified.provider_state_revision != effect.readback.provider_state_revision
        || verified.observed_state != effect.readback.observed_state
        || verified.provider_handle_id != effect.readback.provider_handle_id
        || !digest(&verified.detail_sha256)
        || start_effect.is_some_and(|start|
            start.verified.executor_generation != original.executor_generation
            || (start.verified.executor_generation == generation
                && verified.provider_state_revision <= start.verified.provider_state_revision))
    {
        return Err("incoming Stop target or terminal provider receipt differs".into());
    }
    Ok(())
}
