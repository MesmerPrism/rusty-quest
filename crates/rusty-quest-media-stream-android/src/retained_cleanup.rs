//! Two-principal retained cleanup proof. This is a source-authority join, not
//! a caller-supplied alternative to the signed owner-dispatch protocol.

use std::collections::BTreeMap;

use ed25519_dalek::{Signature, VerifyingKey};
use rusty_quest_media_stream::{MediaStreamOwnerActionKind, MediaStreamPlatformOperation};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::owner_dispatch::{
    decode_signature_base64, encode_signature_base64, OwnerDispatchSigner,
    MAX_OWNER_DISPATCH_FRAME_BYTES,
};
use crate::{
    AndroidMediaExecutionMode, AndroidMediaExecutionTicket, ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA,
};

/// Live, source-authored projection for cleanup of one retained terminal route.
pub const RETAINED_CLEANUP_PROJECTION_SCHEMA: &str =
    "rusty.quest.android.media.retained_cleanup_projection.v2";
/// Exact target owner effect plus a separately authorized requester.
pub const RETAINED_CLEANUP_TICKET_SCHEMA: &str =
    "rusty.quest.android.media.retained_cleanup_ticket.v2";
/// Separate signed request domain; v1 owner dispatch cannot represent two principals.
pub const RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA: &str =
    "rusty.quest.android.media.retained_cleanup_dispatch_request.v2";
const RETAINED_CLEANUP_DISPATCH_DOMAIN: &[u8] =
    b"rusty.quest.android.media.retained_cleanup_dispatch.v2\0request\0";
/// Bounded uncertainty and exact-response replay, separate from v1 dispatch.
pub const MAX_RETAINED_CLEANUP_REPLAY_BYTES: usize = 16 * 1024 * 1024;
/// Maximum unresolved effects after a crash.
pub const MAX_RETAINED_CLEANUP_PENDING: usize = 32;
/// Maximum exact terminal responses retained.
pub const MAX_RETAINED_CLEANUP_TERMINAL: usize = 256;

/// The target is immutable; the requester may be its holder or a fresh revoker.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RetainedCleanupAuthorityProjection {
    /// Projection schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Peer signing the projected authority.
    pub authority_peer_id: String,
    /// Peer that owns the target registry.
    pub executor_peer_id: String,
    /// Exact signed pair-session subject.
    pub peer_session_id: String,
    /// Terminal route retained for cleanup.
    pub route_grant_id: String,
    /// Current route-authority revision.
    pub route_authority_revision: u64,
    /// Original authority provider epoch, restored exactly.
    pub provider_epoch_id: String,
    /// Original platform runtime specification.
    pub platform_runtime_spec_id: String,
    /// Immutable media holder.
    pub target_client_id: String,
    /// Immutable media lease.
    pub target_runtime_lease_id: String,
    /// Current original holder or trusted revoker.
    pub requester_id: String,
    /// Current requester's distinct lease.
    pub requester_runtime_lease_id: String,
    /// True only for a different, trusted non-derivative requester.
    pub trusted_revoker: bool,
    /// Digest of the immutable retained target tuple.
    pub cleanup_target_sha256: String,
    /// Digest of the exact terminal route record.
    pub terminal_route_sha256: String,
    /// Digest of signed topology retained by that route.
    pub signed_topology_sha256: String,
    /// Historical signed topology deadline. Cleanup remains allowed after it.
    pub historical_topology_expires_at_ms: u64,
    /// Current requester lease deadline.
    pub requester_expires_at_ms: u64,
    /// Exclusive upper bound for this projection.
    pub expires_at_ms: u64,
}

/// Exact Stop/compensation intent; never authorizes a Start or substitutes a
/// revoker's identity into the original media execution ticket.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RetainedCleanupExecutionTicket {
    /// Ticket schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Source-authored, signed projection to revalidate at each retry.
    pub authority: RetainedCleanupAuthorityProjection,
    /// Original target's exact native owner ticket.
    pub target: AndroidMediaExecutionTicket,
    /// Execute Stop or compensate one uncertain Stop attempt.
    pub mode: AndroidMediaExecutionMode,
    /// SHA-256 of the exact authority, target ticket and mode tuple.
    pub owner_effect_sha256: String,
}

/// Signed two-principal cleanup request. No v1 owner-dispatch fallback is legal.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RetainedCleanupDispatchRequest {
    /// Request schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Stable retry identity, journaled before owner effect.
    pub dispatch_id: String,
    /// Nonzero coordinator sequence, bound into the signed request identity.
    pub sequence: u64,
    /// Fresh source wall time.
    pub issued_at_ms: u64,
    /// Exact executor of the original target.
    pub target_peer_id: String,
    /// Full two-principal ticket covered by signature.
    pub cleanup: RetainedCleanupExecutionTicket,
    /// Currently enrolled coordinator key ID.
    pub signer_key_id: String,
    /// Standard padded Base64 Ed25519 signature.
    pub signature_base64: String,
}

/// Exact response bytes are retained; a retry never re-enters the owner.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RetainedCleanupTerminalReplay {
    /// Hash of canonical reserialized signed request fields.
    pub request_sha256: String,
    /// Exact signed terminal response bytes.
    pub response_bytes: Vec<u8>,
}

/// App-persisted replay state for the v2 cleanup namespace.
#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RetainedCleanupReplaySnapshot {
    /// Write-ahead requests whose owner effect may have happened.
    pub pending_request_sha256: BTreeMap<String, String>,
    /// Completed exact responses.
    pub terminal: BTreeMap<String, RetainedCleanupTerminalReplay>,
    /// Local fail-closed poison after an ambiguous durable commit error.
    /// Reload exact durable state before accepting another request.
    #[serde(skip)]
    commit_uncertain: bool,
}

/// App-owned atomic replacement store. A commit must be durable before effect.
pub trait RetainedCleanupReplayStore {
    /// Persist the exact replacement snapshot or report failure.
    ///
    /// # Errors
    /// Returns an error if durable replacement fails.
    fn commit(&mut self, snapshot: &RetainedCleanupReplaySnapshot) -> Result<(), String>;
}

/// Result of a collision-safe replay lookup.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RetainedCleanupReplayDecision {
    /// The request may be durably reserved before effect.
    New,
    /// The effect may have happened; only compensation or readback may follow.
    PendingUncertain,
    /// Reuse the exact signed terminal response, without executing again.
    Terminal(Vec<u8>),
}

impl RetainedCleanupReplaySnapshot {
    /// Validates restored state before any cleanup effect can resume.
    ///
    /// # Errors
    /// Returns an error for malformed, overlapping, or oversized state.
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.commit_uncertain
            || self.pending_request_sha256.len() > MAX_RETAINED_CLEANUP_PENDING
            || self.terminal.len() > MAX_RETAINED_CLEANUP_TERMINAL
            || self
                .pending_request_sha256
                .keys()
                .any(|id| self.terminal.contains_key(id))
            || self
                .pending_request_sha256
                .values()
                .any(|value| !digest(value))
            || self
                .terminal
                .values()
                .any(|value| !digest(&value.request_sha256) || value.response_bytes.is_empty())
            || serde_json::to_vec(self).map_or(true, |bytes| {
                bytes.len() > MAX_RETAINED_CLEANUP_REPLAY_BYTES
            })
        {
            return Err("retained cleanup replay state invalid");
        }
        Ok(())
    }

    /// Reads one ID against its exact signed request hash.
    ///
    /// # Errors
    /// Returns an error for invalid state or an ID collision.
    pub fn classify(
        &self,
        dispatch_id: &str,
        request_sha256: &str,
    ) -> Result<RetainedCleanupReplayDecision, &'static str> {
        self.validate()?;
        if dispatch_id.is_empty() || !digest(request_sha256) {
            return Err("retained cleanup replay identity invalid");
        }
        if let Some(value) = self.terminal.get(dispatch_id) {
            return if value.request_sha256 == request_sha256 {
                Ok(RetainedCleanupReplayDecision::Terminal(
                    value.response_bytes.clone(),
                ))
            } else {
                Err("retained cleanup replay collision")
            };
        }
        if let Some(value) = self.pending_request_sha256.get(dispatch_id) {
            return if value == request_sha256 {
                Ok(RetainedCleanupReplayDecision::PendingUncertain)
            } else {
                Err("retained cleanup replay collision")
            };
        }
        Ok(RetainedCleanupReplayDecision::New)
    }

    /// Write-ahead reservation, committed before entering a platform owner.
    ///
    /// # Errors
    /// Returns an error for a replay, capacity limit, or failed durable commit.
    pub fn reserve<S: RetainedCleanupReplayStore>(
        &mut self,
        store: &mut S,
        dispatch_id: &str,
        request_sha256: &str,
    ) -> Result<(), String> {
        if self
            .classify(dispatch_id, request_sha256)
            .map_err(str::to_owned)?
            != RetainedCleanupReplayDecision::New
            || self.pending_request_sha256.len() >= MAX_RETAINED_CLEANUP_PENDING
        {
            return Err("retained cleanup request already reserved".to_owned());
        }
        let mut next = self.clone();
        next.pending_request_sha256
            .insert(dispatch_id.to_owned(), request_sha256.to_owned());
        next.validate().map_err(str::to_owned)?;
        if let Err(error) = store.commit(&next) {
            self.commit_uncertain = true;
            return Err(format!("retained cleanup replay commit uncertain: {error}"));
        }
        *self = next;
        Ok(())
    }

    /// Commit exact terminal response before returning it to the coordinator.
    ///
    /// # Errors
    /// Returns an error unless the exact request is pending and commit succeeds.
    pub fn complete<S: RetainedCleanupReplayStore>(
        &mut self,
        store: &mut S,
        dispatch_id: &str,
        request_sha256: &str,
        response_bytes: Vec<u8>,
    ) -> Result<(), String> {
        if self
            .classify(dispatch_id, request_sha256)
            .map_err(str::to_owned)?
            != RetainedCleanupReplayDecision::PendingUncertain
            || self.terminal.len() >= MAX_RETAINED_CLEANUP_TERMINAL
        {
            return Err("retained cleanup request is not pending".to_owned());
        }
        let mut next = self.clone();
        next.pending_request_sha256.remove(dispatch_id);
        next.terminal.insert(
            dispatch_id.to_owned(),
            RetainedCleanupTerminalReplay {
                request_sha256: request_sha256.to_owned(),
                response_bytes,
            },
        );
        next.validate().map_err(str::to_owned)?;
        if let Err(error) = store.commit(&next) {
            self.commit_uncertain = true;
            return Err(format!("retained cleanup replay commit uncertain: {error}"));
        }
        *self = next;
        Ok(())
    }
}

/// Hash of canonical reserialized signed v2 request fields, used as replay identity.
///
/// # Errors
/// Returns an error if encoding fails or exceeds the frame bound.
pub fn retained_cleanup_request_sha256(
    request: &RetainedCleanupDispatchRequest,
) -> Result<String, &'static str> {
    let bytes = serde_json::to_vec(request).map_err(|_| "cleanup request encode failed")?;
    if bytes.len() > MAX_OWNER_DISPATCH_FRAME_BYTES {
        return Err("cleanup request exceeds frame bound");
    }
    Ok(format!("sha256:{:x}", Sha256::digest(bytes)))
}

/// Canonical, domain-separated bytes binding requester, original target and mode.
///
/// # Errors
/// Returns an error if encoding fails or exceeds the frame bound.
pub fn retained_cleanup_request_signing_bytes(
    request: &RetainedCleanupDispatchRequest,
) -> Result<Vec<u8>, &'static str> {
    let mut unsigned = request.clone();
    unsigned.signature_base64.clear();
    let payload = serde_json::to_vec(&unsigned).map_err(|_| "cleanup request encode failed")?;
    if payload.len() > MAX_OWNER_DISPATCH_FRAME_BYTES {
        return Err("cleanup request exceeds frame bound");
    }
    let mut bytes = Vec::with_capacity(RETAINED_CLEANUP_DISPATCH_DOMAIN.len() + payload.len());
    bytes.extend_from_slice(RETAINED_CLEANUP_DISPATCH_DOMAIN);
    bytes.extend_from_slice(&payload);
    Ok(bytes)
}

/// Signs an already source-authored request. Callers must durably reserve its
/// dispatch ID and sequence before transmitting or entering the owner registry.
///
/// # Errors
/// Returns an error for a wrong signer or signing failure.
pub fn sign_retained_cleanup_request<S: OwnerDispatchSigner>(
    request: &mut RetainedCleanupDispatchRequest,
    signer: &S,
) -> Result<(), String> {
    if request.signer_key_id != signer.key_id() || !request.signature_base64.is_empty() {
        return Err("cleanup signer identity differs".to_owned());
    }
    let bytes = retained_cleanup_request_signing_bytes(request).map_err(str::to_owned)?;
    request.signature_base64 = encode_signature_base64(&signer.sign(&bytes)?);
    Ok(())
}

/// Checks one fresh signed request against a currently enrolled signer and
/// independent retained target. The embedding server still owns durable replay.
///
/// # Errors
/// Returns an error for stale or changed authority, target, identity or signature.
pub fn verify_signed_retained_cleanup_request(
    request: &RetainedCleanupDispatchRequest,
    live: &RetainedCleanupAuthorityProjection,
    expected_target: &AndroidMediaExecutionTicket,
    expected_executor_generation: u64,
    expected_executor_peer_id: &str,
    expected_signer_key_id: &str,
    enrolled_signer_key: &[u8; 32],
    now_ms: u64,
) -> Result<(), String> {
    if request.schema_id != RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA
        || request.dispatch_id.is_empty()
        || request.sequence == 0
        || request.issued_at_ms == 0
        || request.issued_at_ms > now_ms.saturating_add(2_000)
        || now_ms.saturating_sub(request.issued_at_ms) > 30_000
        || request.target_peer_id != expected_executor_peer_id
        || request.target_peer_id != live.executor_peer_id
        || request.signer_key_id != expected_signer_key_id
    {
        return Err("cleanup dispatch identity/freshness differs".to_owned());
    }
    verify_retained_cleanup_ticket(
        &request.cleanup,
        live,
        expected_target,
        expected_executor_generation,
        now_ms,
    )
    .map_err(str::to_owned)?;
    let bytes = retained_cleanup_request_signing_bytes(request).map_err(str::to_owned)?;
    let signature = decode_signature_base64(&request.signature_base64)?;
    let key = VerifyingKey::from_bytes(enrolled_signer_key)
        .map_err(|_| "cleanup enrolled signer key invalid".to_owned())?;
    key.verify_strict(&bytes, &Signature::from_bytes(&signature))
        .map_err(|_| "cleanup dispatch signature invalid".to_owned())
}

/// Verifies a v2 cleanup ticket against a freshly derived live projection.
/// The caller must authenticate the signed dispatch before invoking this and
/// obtain `expected_target` independently from its retained native action and
/// owner-effect record. Never derive that oracle from `ticket.target`.
///
/// # Errors
/// Returns an error if any source, target, requester or effect field differs.
pub fn verify_retained_cleanup_ticket(
    ticket: &RetainedCleanupExecutionTicket,
    live: &RetainedCleanupAuthorityProjection,
    expected_target: &AndroidMediaExecutionTicket,
    expected_executor_generation: u64,
    now_ms: u64,
) -> Result<(), &'static str> {
    let authority = &ticket.authority;
    if ticket.schema_id != RETAINED_CLEANUP_TICKET_SCHEMA
        || authority.schema_id != RETAINED_CLEANUP_PROJECTION_SCHEMA
        || authority != live
        || now_ms >= authority.expires_at_ms
        || now_ms >= authority.requester_expires_at_ms
        || authority.expires_at_ms > authority.requester_expires_at_ms
        || authority.route_authority_revision == 0
        || authority.provider_epoch_id.is_empty()
        || authority.platform_runtime_spec_id.is_empty()
        || authority.route_grant_id.is_empty()
        || authority.peer_session_id.is_empty()
        || !digest(&authority.cleanup_target_sha256)
        || !digest(&authority.terminal_route_sha256)
        || !digest(&authority.signed_topology_sha256)
    {
        return Err("retained cleanup authority invalid");
    }
    let parties_match = if authority.trusted_revoker {
        authority.requester_id != authority.target_client_id
            && authority.requester_runtime_lease_id != authority.target_runtime_lease_id
    } else {
        authority.requester_id == authority.target_client_id
            && authority.requester_runtime_lease_id == authority.target_runtime_lease_id
    };
    if !parties_match
        || ticket.target.schema_id != ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA
        || &ticket.target != expected_target
        || ticket.target.operation != MediaStreamPlatformOperation::Stop
        || !matches!(
            ticket.target.action_kind,
            MediaStreamOwnerActionKind::Stop | MediaStreamOwnerActionKind::Cleanup
        )
        || ticket.target.client_id != authority.target_client_id
        || ticket.target.lease_id != authority.target_runtime_lease_id
        || ticket.target.authority_epoch_id != authority.provider_epoch_id
        || expected_executor_generation == 0
        || ticket.target.executor_generation != expected_executor_generation
        || ticket.target.sequence == 0
    {
        return Err("retained cleanup target/requester mismatch");
    }
    let encoded = serde_json::to_vec(&(&ticket.authority, &ticket.target, ticket.mode))
        .map_err(|_| "retained cleanup effect encode failed")?;
    if ticket.owner_effect_sha256 != format!("sha256:{:x}", Sha256::digest(encoded)) {
        return Err("retained cleanup owner effect differs");
    }
    Ok(())
}

fn digest(value: &str) -> bool {
    value.len() == 71
        && value.starts_with("sha256:")
        && value[7..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use rusty_quest_media_stream::MediaStreamOwnerKind;

    struct TestSigner(SigningKey);

    impl OwnerDispatchSigner for TestSigner {
        fn key_id(&self) -> &str {
            "key.peer.a.1"
        }

        fn sign(&self, message: &[u8]) -> Result<[u8; 64], String> {
            Ok(self.0.sign(message).to_bytes())
        }
    }

    #[derive(Default)]
    struct MemoryReplayStore {
        saved: Option<RetainedCleanupReplaySnapshot>,
        fail: bool,
        fail_after_write: bool,
    }

    impl RetainedCleanupReplayStore for MemoryReplayStore {
        fn commit(&mut self, snapshot: &RetainedCleanupReplaySnapshot) -> Result<(), String> {
            if self.fail {
                return Err("simulated persistence failure".into());
            }
            self.saved = Some(snapshot.clone());
            if self.fail_after_write {
                return Err("ambiguous persistence failure after write".into());
            }
            Ok(())
        }
    }

    fn projection() -> RetainedCleanupAuthorityProjection {
        RetainedCleanupAuthorityProjection {
            schema_id: RETAINED_CLEANUP_PROJECTION_SCHEMA.into(),
            authority_peer_id: "peer.a".into(),
            executor_peer_id: "peer.b".into(),
            peer_session_id: "session.one".into(),
            route_grant_id: "grant.one".into(),
            route_authority_revision: 7,
            provider_epoch_id: "epoch.original".into(),
            platform_runtime_spec_id: "runtime.stereo".into(),
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
        }
    }

    fn retained_stop_target(
        authority: &RetainedCleanupAuthorityProjection,
        executor_generation: u64,
    ) -> AndroidMediaExecutionTicket {
        AndroidMediaExecutionTicket {
            schema_id: ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA.into(),
            capability: "capability.native".into(),
            executor_generation,
            action_id: "action.stop.original".into(),
            authority_epoch_id: authority.provider_epoch_id.clone(),
            media_acceptance_authority_revision: 3,
            expected_runtime_revision: 4,
            client_id: authority.target_client_id.clone(),
            lease_id: authority.target_runtime_lease_id.clone(),
            sequence: 1,
            operation: MediaStreamPlatformOperation::Stop,
            owner_kind: MediaStreamOwnerKind::Source,
            action_kind: MediaStreamOwnerActionKind::Stop,
            owner_id: "owner.camera".into(),
            provider_kind: "camera2".into(),
            resource_id: "camera.stereo".into(),
        }
    }

    fn ticket(
        authority: RetainedCleanupAuthorityProjection,
        target: AndroidMediaExecutionTicket,
    ) -> RetainedCleanupExecutionTicket {
        let mode = AndroidMediaExecutionMode::Execute;
        let encoded = serde_json::to_vec(&(&authority, &target, mode)).unwrap();
        RetainedCleanupExecutionTicket {
            schema_id: RETAINED_CLEANUP_TICKET_SCHEMA.into(),
            authority,
            target,
            mode,
            owner_effect_sha256: format!("sha256:{:x}", Sha256::digest(encoded)),
        }
    }

    #[test]
    fn revoker_is_distinct_while_target_ticket_stays_original() {
        let live = projection();
        // The native owner record independently supplies this fresh Stop
        // target after restore; the incoming ticket is never its own oracle.
        let expected = retained_stop_target(&live, 9);
        let accepted = ticket(live.clone(), expected.clone());
        // The retained route's signed topology expired before this fresh
        // cleanup request. Its digest is provenance, not live authorization.
        assert!(live.historical_topology_expires_at_ms < 100);
        assert_eq!(
            verify_retained_cleanup_ticket(&accepted, &live, &expected, 9, 100),
            Ok(())
        );
        let mut relabeled = accepted.clone();
        relabeled.target.client_id = live.requester_id.clone();
        relabeled.target.lease_id = live.requester_runtime_lease_id.clone();
        assert!(verify_retained_cleanup_ticket(&relabeled, &live, &expected, 9, 100).is_err());
        assert!(verify_retained_cleanup_ticket(&accepted, &live, &expected, 10, 100).is_err());
        let mut stale_requester = live.clone();
        stale_requester.requester_expires_at_ms = 100;
        let stale_ticket = ticket(stale_requester.clone(), expected.clone());
        assert!(
            verify_retained_cleanup_ticket(&stale_ticket, &stale_requester, &expected, 9, 100,)
                .is_err()
        );
        // A pre-restart generation cannot be replayed as a new Stop.
        let old_start_epoch_target = retained_stop_target(&live, 8);
        let old_ticket = ticket(live.clone(), old_start_epoch_target);
        assert!(verify_retained_cleanup_ticket(&old_ticket, &live, &expected, 9, 100).is_err());
    }

    #[test]
    fn changed_epoch_route_requester_effect_and_expiry_reject() {
        let live = projection();
        let expected = retained_stop_target(&live, 9);
        let original = ticket(live.clone(), expected.clone());
        for mut changed in [
            {
                let mut value = original.clone();
                value.target.authority_epoch_id = "epoch.fresh".into();
                value
            },
            {
                let mut value = original.clone();
                value.authority.route_grant_id = "grant.other".into();
                value
            },
            {
                let mut value = original.clone();
                value.authority.requester_runtime_lease_id = "lease.other".into();
                value
            },
            {
                let mut value = original.clone();
                value.owner_effect_sha256 = format!("sha256:{}", "d".repeat(64));
                value
            },
        ] {
            assert!(verify_retained_cleanup_ticket(&changed, &live, &expected, 9, 100).is_err());
            changed.target.operation = MediaStreamPlatformOperation::Start;
            assert!(verify_retained_cleanup_ticket(&changed, &live, &expected, 9, 100).is_err());
        }
        assert!(verify_retained_cleanup_ticket(&original, &live, &expected, 9, 180).is_err());
        let mut wrong_mode = original;
        wrong_mode.mode = AndroidMediaExecutionMode::CompensateUncertain;
        assert!(verify_retained_cleanup_ticket(&wrong_mode, &live, &expected, 9, 100).is_err());

        // A forged target can recompute its own digest, but still differs
        // from the independently retained owner/resource/action record.
        let mut swapped_target = expected.clone();
        swapped_target.resource_id = "camera.other".into();
        let swapped = ticket(live.clone(), swapped_target);
        assert!(verify_retained_cleanup_ticket(&swapped, &live, &expected, 9, 100).is_err());
        let mut start_target = expected.clone();
        start_target.operation = MediaStreamPlatformOperation::Start;
        start_target.action_kind = MediaStreamOwnerActionKind::Start;
        let start = ticket(live.clone(), start_target);
        assert!(verify_retained_cleanup_ticket(&start, &live, &expected, 9, 100).is_err());
    }

    #[test]
    fn signed_request_binds_both_principals_target_generation_and_freshness() {
        let live = projection();
        let expected = retained_stop_target(&live, 9);
        let signer = TestSigner(SigningKey::from_bytes(&[27; 32]));
        let mut request = RetainedCleanupDispatchRequest {
            schema_id: RETAINED_CLEANUP_DISPATCH_REQUEST_SCHEMA.into(),
            dispatch_id: "dispatch.cleanup.one".into(),
            sequence: 1,
            issued_at_ms: 100,
            target_peer_id: live.executor_peer_id.clone(),
            cleanup: ticket(live.clone(), expected.clone()),
            signer_key_id: signer.key_id().into(),
            signature_base64: String::new(),
        };
        sign_retained_cleanup_request(&mut request, &signer).unwrap();
        let key = signer.0.verifying_key().to_bytes();
        let verify = |candidate: &RetainedCleanupDispatchRequest, now| {
            verify_signed_retained_cleanup_request(
                candidate,
                &live,
                &expected,
                9,
                "peer.b",
                "key.peer.a.1",
                &key,
                now,
            )
        };
        assert_eq!(verify(&request, 100), Ok(()));
        let mut changed = request.clone();
        changed.cleanup.authority.requester_id = "client.attacker".into();
        assert!(verify(&changed, 100).is_err());
        let mut changed = request.clone();
        changed.cleanup.target.resource_id = "camera.other".into();
        assert!(verify(&changed, 100).is_err());
        let mut changed = request.clone();
        changed.target_peer_id = "peer.attacker".into();
        assert!(verify(&changed, 100).is_err());
        let mut changed = request.clone();
        changed.sequence = 2;
        assert!(verify(&changed, 100).is_err());
        assert!(verify(&request, 30_101).is_err());
        assert!(verify_signed_retained_cleanup_request(
            &request,
            &live,
            &expected,
            9,
            "peer.b",
            "key.peer.a.1",
            &SigningKey::from_bytes(&[28; 32]).verifying_key().to_bytes(),
            100,
        )
        .is_err());
    }

    #[test]
    fn replay_reservation_survives_crash_and_never_reexecutes() {
        let hash = format!("sha256:{}", "a".repeat(64));
        let other_hash = format!("sha256:{}", "b".repeat(64));
        let mut state = RetainedCleanupReplaySnapshot::default();
        let mut store = MemoryReplayStore::default();
        assert_eq!(
            state.classify("dispatch.one", &hash),
            Ok(RetainedCleanupReplayDecision::New)
        );
        store.fail = true;
        assert!(state.reserve(&mut store, "dispatch.one", &hash).is_err());
        assert!(state.classify("dispatch.one", &hash).is_err());
        // Even an apparent pre-write error poisons this process. Reload only
        // from the durable store before accepting any other request.
        state = store.saved.clone().unwrap_or_default();
        store.fail = false;
        store.fail_after_write = true;
        assert!(state.reserve(&mut store, "dispatch.one", &hash).is_err());
        assert!(state.classify("dispatch.one", &other_hash).is_err());
        store.fail_after_write = false;
        let mut restored = store.saved.clone().unwrap();
        assert_eq!(
            restored.classify("dispatch.one", &hash),
            Ok(RetainedCleanupReplayDecision::PendingUncertain)
        );
        assert!(restored.classify("dispatch.one", &other_hash).is_err());
        assert!(restored.reserve(&mut store, "dispatch.one", &hash).is_err());
        store.fail = true;
        assert!(restored
            .complete(&mut store, "dispatch.one", &hash, vec![1, 2, 3])
            .is_err());
        assert!(restored.classify("dispatch.one", &hash).is_err());
        restored = store.saved.clone().unwrap();
        store.fail = false;
        store.fail_after_write = true;
        assert!(restored
            .complete(&mut store, "dispatch.one", &hash, vec![1, 2, 3])
            .is_err());
        assert!(restored.classify("dispatch.one", &hash).is_err());
        store.fail_after_write = false;
        let after_restart = store.saved.clone().unwrap();
        assert_eq!(
            after_restart.classify("dispatch.one", &hash),
            Ok(RetainedCleanupReplayDecision::Terminal(vec![1, 2, 3]))
        );
        assert!(after_restart.classify("dispatch.one", &other_hash).is_err());
    }
}
