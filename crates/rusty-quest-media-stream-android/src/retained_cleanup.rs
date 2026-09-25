//! Two-principal retained cleanup proof. This is a source-authority join, not
//! a caller-supplied alternative to the signed owner-dispatch protocol.

use rusty_quest_media_stream::{MediaStreamOwnerActionKind, MediaStreamPlatformOperation};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{
    AndroidMediaExecutionMode, AndroidMediaExecutionTicket, ANDROID_MEDIA_EXECUTION_TICKET_SCHEMA,
};

/// Live, source-authored projection for cleanup of one retained terminal route.
pub const RETAINED_CLEANUP_PROJECTION_SCHEMA: &str =
    "rusty.quest.android.media.retained_cleanup_projection.v2";
/// Exact target owner effect plus a separately authorized requester.
pub const RETAINED_CLEANUP_TICKET_SCHEMA: &str =
    "rusty.quest.android.media.retained_cleanup_ticket.v2";

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

/// Verifies a v2 cleanup ticket against a freshly derived live projection.
/// The caller must authenticate the signed dispatch before invoking this and
/// obtain `expected_target` independently from its retained native action and
/// owner-effect record. Never derive that oracle from `ticket.target`.
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
    use rusty_quest_media_stream::MediaStreamOwnerKind;

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
}
