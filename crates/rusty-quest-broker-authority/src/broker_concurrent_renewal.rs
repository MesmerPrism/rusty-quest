//! Fixed same-session renewal over the actual admission and generic lease owners.
//! Every applied phase remains retained when a later owner returns an error.
use super::*;
use rusty_manifold_admission::{
    ManifoldAdmissionGrantRenewalReceipt, ManifoldAdmissionGrantRenewalRequest,
    ManifoldAdmissionReceipt,
};
use rusty_manifold_broker_adapter::{
    ManifoldBrokerCapabilityUseReceipt, ManifoldBrokerControlLeaseLifecycleAuthorizationReceipt,
    ManifoldBrokerControlLeaseLifecycleOperation, ManifoldBrokerControlLeaseLifecycleOutcome,
    ManifoldBrokerControlLeaseLifecycleReceipt, ManifoldBrokerControlLeaseLifecycleRequest,
};
use rusty_manifold_peer_runtime_host::{
    ManifoldConcurrentMediaAuthorityRenewalReceipt, ManifoldConcurrentPairSessionRenewalReceipt,
};

/// Current request namespaces after complete owner publication. These epochs
/// belong to independent owners and are not renewal or media-lifetime counts.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct QuestConcurrentOwnerHistoryEpochs {
    /// Closed observation schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Actual admission owner namespace.
    pub admission_request_epoch: u64,
    /// Actual generic control-lease owner namespace.
    pub control_lease_request_epoch: u64,
    /// Actual outer Broker Runtime Host command namespace.
    pub broker_host_request_epoch: u64,
    /// Actual inner media command namespace.
    pub media_command_request_epoch: u64,
    /// Actual signed reciprocal request/correlation namespace.
    pub reciprocal_request_epoch: u64,
    /// Actual enclosing Peer request namespace.
    pub peer_request_epoch: u64,
}

/// Read-only current owner evidence. It authenticates accepted authority and
/// resource identity, never a fresh physical Java/socket/frame observation.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct QuestCurrentConcurrentOngoingValidation {
    /// Closed current-read schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Fixed successful validation state; failures return no validation receipt.
    pub status: String,
    /// Actual native caller observation time, without extending any deadline.
    pub validated_at_ms: u64,
    /// Earliest current scoped expiry checked by this read.
    pub valid_until_ms: u64,
    /// Original actual provider epoch.
    pub provider_epoch_id: DottedId,
    /// Exact latest completed coupled owner request.
    pub owner_request_id: DottedId,
    /// Digest of the complete actual private retained coupled receipt.
    pub owner_receipt_sha256: String,
    /// Exact current accepted media lifetime.
    pub media_decision_id: DottedId,
    /// Exact current derivative ancestry.
    pub derivative_binding_id: DottedId,
    /// Actual retained current Start action; app ARM/generation stays separate.
    pub current_start_action_id: String,
    /// Identity of retained accepted resources, not a new physical observation.
    pub resource_identity_sha256: String,
    /// Monotonic count across media lifetimes.
    pub cumulative_renewal_count: u64,
    /// Accepted count in the exact current Start lifetime.
    pub current_lifetime_renewal_count: u64,
    /// Independent current namespaces, never inferred from the counts.
    pub owner_history_epochs: QuestConcurrentOwnerHistoryEpochs,
}

/// Complete owner join, issued only after the same resources adopt every renewed authority.
#[derive(Clone, Debug, Serialize)]
pub struct QuestConcurrentAuthorityRenewalReceipt {
    /// Explicit owner storage policy; absent on the unchanged finite contract.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ongoing_media_policy: Option<rusty_manifold_peer_runtime_host::ManifoldOngoingMediaPolicy>,
    /// Actual owner continuation checkpoint, absent on the finite path.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continuation_checkpoint:
        Option<rusty_manifold_peer_runtime_host::ManifoldConcurrentMediaCheckpoint>,
    /// Monotonic accepted renewal count; explicit v3 spans media lifetimes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cumulative_renewal_count: Option<u64>,
    /// Latest checkpoint sequence, zero before the first checkpoint.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checkpoint_sequence: Option<u64>,
    /// Renewals covered by the checkpoint rather than its bounded suffix.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checkpointed_renewal_count: Option<u64>,
    /// Accepted renewals after the latest checkpoint.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suffix_renewal_count: Option<u64>,
    /// V3 count for the current exact Start/derivative lifetime, independently of
    /// the monotonic cumulative count across every accepted lifetime.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub current_lifetime_renewal_count: Option<u64>,
    /// V3 actual parent checkpoint committing generic bounded owner histories.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub runtime_history_checkpoint:
        Option<rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHistoryCheckpoint>,
    /// V3 exact current owner namespaces for subsequent fresh requests.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub owner_history_epochs: Option<QuestConcurrentOwnerHistoryEpochs>,
    /// Fixed complete-composition schema.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Retained identity derived from the exact accepted pair proof.
    pub request_id: DottedId,
    /// Original live process provider epoch.
    pub provider_epoch_id: DottedId,
    /// Actual OS-backed review time.
    pub observed_at_ms: u64,
    /// Original clock retained across a partial retry.
    pub clock: ManifoldClockSnapshot,
    /// Same-session signed owner acceptance.
    pub accepted_pair: ManifoldConcurrentPairSessionRenewalReceipt,
    /// Actual token issuance for grant renewal.
    pub grant_token_issue: ManifoldAdmissionReceipt,
    /// Actual identity and lock-preserving grant renewal.
    pub admission_grant: ManifoldAdmissionGrantRenewalReceipt,
    /// Actual Broker bounded use and consumption that authorized grant renewal.
    pub grant_capability_use: ManifoldBrokerCapabilityUseReceipt,
    /// Separate token issuance for generic lease renewal.
    pub lease_token_issue: ManifoldAdmissionReceipt,
    /// Operation-bound admission use for the lease owner.
    pub lease_authorization: ManifoldBrokerControlLeaseLifecycleAuthorizationReceipt,
    /// Actual generic authority application and outer Runtime Host adoption.
    pub outer_lifecycle: ManifoldBrokerControlLeaseLifecycleReceipt,
    /// Actual unchanged inner media resource graph adoption.
    pub media_authority: ManifoldConcurrentMediaAuthorityRenewalReceipt,
    /// Actual product adoption of the renewed acceptance with the same physical graph.
    pub product_adoption: rusty_quest_media_stream::MediaStreamConcurrentAuthorityAdoptionReceipt,
}

#[derive(Default)]
pub(super) struct QuestConcurrentRenewalState {
    pending: Option<Pending>,
    completed: Vec<QuestConcurrentAuthorityRenewalReceipt>,
    #[cfg(test)]
    pub(super) pause_after_product_adoption: bool,
    #[cfg(test)]
    pub(super) pause_after_history_commit: bool,
}

#[cfg(test)]
impl QuestConcurrentRenewalState {
    pub(super) fn has_pending(&self) -> bool {
        self.pending.is_some()
    }
}

struct Pending {
    proof: ManifoldConcurrentPairSessionRenewalReceipt,
    clock: ManifoldClockSnapshot,
    now_ms: u64,
    entropy: [u8; 32],
    request_id: DottedId,
    identity: rusty_manifold_admission::ManifoldClientIdentity,
    grant_id: DottedId,
    prior_grant_expiry: u64,
    root_horizon_ms: u64,
    lease_id: DottedId,
    token_expiry: u64,
    grant_issue: Option<ManifoldAdmissionReceipt>,
    grant_request: Option<ManifoldAdmissionGrantRenewalRequest>,
    grant: Option<ManifoldAdmissionGrantRenewalReceipt>,
    grant_capability_use: Option<ManifoldBrokerCapabilityUseReceipt>,
    lease_issue: Option<ManifoldAdmissionReceipt>,
    lifecycle_request: Option<ManifoldBrokerControlLeaseLifecycleRequest>,
    lease_authorization: Option<ManifoldBrokerControlLeaseLifecycleAuthorizationReceipt>,
    lifecycle: Option<ManifoldBrokerControlLeaseLifecycleReceipt>,
}

// Canonicalizes through serde_json::Value's sorted object maps, matching the
// native status projection rather than the Rust struct's declaration order.
fn canonical_receipt_sha256(value: &impl Serialize) -> Result<String, QuestBrokerRuntimeError> {
    let value = serde_json::to_value(value).map_err(QuestBrokerRuntimeError::Encode)?;
    Ok(format!(
        "sha256:{}",
        sha256_hex(&serde_json::to_vec(&value).map_err(QuestBrokerRuntimeError::Encode)?)
    ))
}

fn rejected() -> QuestBrokerRuntimeError {
    QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected
}
fn phase_rejected(message: &str) -> QuestBrokerRuntimeError {
    QuestBrokerRuntimeError::MediaPeerRuntime(ManifoldPeerRuntimeHostError::Authority(
        message.to_owned(),
    ))
}
fn request(part: &str, base: &DottedId) -> Result<DottedId, QuestBrokerRuntimeError> {
    DottedId::new(format!(
        "request.quest.concurrent.renew.{part}.{}",
        base.as_str().rsplit('.').next().ok_or_else(rejected)?
    ))
    .map_err(|_| rejected())
}
fn entropy_for(base: &[u8; 32], part: &[u8]) -> [u8; 32] {
    let mut hash = Sha256::new();
    hash.update(base);
    hash.update(part);
    hash.finalize().into()
}
fn token_issue(
    broker: &mut ManifoldBrokerRuntime,
    pending: &Pending,
    part: &str,
    entropy: &[u8; 32],
) -> Result<ManifoldAdmissionReceipt, QuestBrokerRuntimeError> {
    Ok(broker.issue_token(
        &ManifoldAdmissionRequest {
            schema_id: schema(ADMISSION_REQUEST_SCHEMA),
            request_id: broker
                .current_admission_history_request_id(&request(part, &pending.request_id)?)
                .map_err(QuestBrokerRuntimeError::RuntimeState)?,
            expected_authority_revision: broker.admission_snapshot().authority_revision,
            identity: pending.identity.clone(),
            requested_capabilities: vec![
                DottedId::new("capability.manifold.control_lease.renew").expect("fixed cap")
            ],
            issued_at_ms: pending.now_ms,
            expires_at_ms: pending.token_expiry,
            requested_token_ttl_ms: pending.token_expiry - pending.now_ms,
        },
        *entropy,
        pending.now_ms,
    ))
}
fn authorization(
    broker: &ManifoldBrokerRuntime,
    pending: &Pending,
    issue: &ManifoldAdmissionReceipt,
    part: &str,
) -> Result<ManifoldAdmissionUseRequest, QuestBrokerRuntimeError> {
    if !issue.applied {
        return Err(rejected());
    }
    Ok(ManifoldAdmissionUseRequest {
        schema_id: schema(ADMISSION_USE_REQUEST_SCHEMA),
        request_id: broker
            .current_admission_history_request_id(&request(part, &pending.request_id)?)
            .map_err(QuestBrokerRuntimeError::RuntimeState)?,
        expected_authority_revision: broker.admission_snapshot().authority_revision,
        token_id: issue.token.as_ref().ok_or_else(rejected)?.token_id.clone(),
        identity: pending.identity.clone(),
        capability_id: DottedId::new("capability.manifold.control_lease.renew").expect("fixed cap"),
        issued_at_ms: pending.now_ms,
        expires_at_ms: pending.token_expiry,
    })
}

impl QuestBrokerRuntimeProvider {
    /// Validates only this provider's actual private latest completed renewal.
    /// The embedding supplies its native clock, never a caller-provided receipt
    /// or authority snapshot. This read neither journals nor performs effects.
    pub fn current_concurrent_ongoing_validation(
        &self,
        now_ms: u64,
    ) -> Result<QuestCurrentConcurrentOngoingValidation, QuestBrokerRuntimeError> {
        let owner = self
            .runtime
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        if owner.concurrent_renewal.pending.is_some() {
            return Err(rejected());
        }
        let receipt = owner
            .concurrent_renewal
            .completed
            .last()
            .ok_or_else(rejected)?;
        let peer = owner
            .peer_runtime_host
            .as_ref()
            .ok_or_else(rejected)?
            .read()
            .map_err(|_| rejected())?;
        let broker = owner.runtime.read().map_err(|_| rejected())?;
        peer.validate_owned_state_with_live_broker(&broker)
            .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        let snapshot = peer.snapshot();
        if receipt.schema_id != "rusty.quest.concurrent.authority_renewal_receipt.v3"
            || snapshot
                .ongoing_media_policy
                .as_ref()
                .map(|p| p.schema_id.as_str())
                != Some("rusty.manifold.peer.ongoing_media_policy.v2")
            || receipt.provider_epoch_id != owner.provider_epoch_id
            || now_ms < receipt.observed_at_ms
            || receipt.cumulative_renewal_count
                != Some(
                    peer.cumulative_concurrent_media_renewals()
                        .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?,
                )
            || receipt.current_lifetime_renewal_count
                != Some(
                    peer.current_lifetime_concurrent_media_renewals()
                        .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?,
                )
            || receipt.media_authority.paired_session_receipt != receipt.accepted_pair
            || receipt.media_authority.broker_lifecycle_receipt != receipt.outer_lifecycle
            || receipt.product_adoption.owner_request_id != receipt.media_authority.request_id
        {
            return Err(rejected());
        }
        let media = owner
            .media_sessions
            .get(&receipt.media_authority.renewed_media.runtime_client_id)
            .ok_or_else(rejected)?;
        let resource_identity_sha256 = media
            .validate_current_concurrent_authority_identity(
                &peer,
                &broker,
                &receipt.media_authority,
                now_ms,
            )
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        if resource_identity_sha256 != receipt.product_adoption.physical_graph_sha256
            || receipt.product_adoption.prior_physical_graph_sha256 != resource_identity_sha256
        {
            return Err(rejected());
        }
        let mut valid_until_ms = receipt
            .media_authority
            .expires_at_ms
            .min(receipt.accepted_pair.expires_at_ms);
        for route in &receipt.media_authority.renewed_routes {
            let current = peer
                .validate_pair_media_route_v2_with_live_broker_runtime(
                    &broker,
                    &route.grant_id,
                    now_ms,
                )
                .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
            if !current.current {
                return Err(rejected());
            }
            valid_until_ms = valid_until_ms.min(route.expires_at_ms);
        }
        let grant = broker
            .admission_snapshot()
            .grants
            .iter()
            .find(|g| {
                g.grant_id == receipt.media_authority.renewed_media.admission_grant_id
                    && !g.revoked
                    && g.identity.client_id
                        == receipt.media_authority.renewed_media.runtime_client_id
            })
            .ok_or_else(rejected)?;
        valid_until_ms = valid_until_ms.min(grant.expires_at_ms);
        if valid_until_ms <= now_ms {
            return Err(rejected());
        }
        let evidence = broker.evidence();
        let epochs = QuestConcurrentOwnerHistoryEpochs {
            schema_id: "rusty.quest.concurrent.owner_history_epochs.v1".into(),
            admission_request_epoch: evidence
                .admission_snapshot
                .history_checkpoint
                .as_ref()
                .ok_or_else(rejected)?
                .request_epoch,
            control_lease_request_epoch: evidence
                .control_lease_authority
                .history_checkpoint
                .as_ref()
                .ok_or_else(rejected)?
                .request_epoch,
            broker_host_request_epoch: evidence
                .host_snapshot
                .history_checkpoint
                .as_ref()
                .ok_or_else(rejected)?
                .request_epoch,
            media_command_request_epoch: snapshot
                .media_command_runtime
                .history_checkpoint
                .as_ref()
                .ok_or_else(rejected)?
                .request_epoch,
            reciprocal_request_epoch: snapshot
                .reciprocal_ed25519
                .history_checkpoint
                .as_ref()
                .ok_or_else(rejected)?
                .request_epoch,
            peer_request_epoch: snapshot
                .runtime_history_checkpoint
                .as_ref()
                .ok_or_else(rejected)?
                .request_epoch,
        };
        if receipt.owner_history_epochs.as_ref() != Some(&epochs) {
            return Err(rejected());
        }
        let recovery = media
            .recovery_snapshot()
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        Ok(QuestCurrentConcurrentOngoingValidation {
            schema_id: "rusty.quest.concurrent.current_ongoing_owner_validation.v1".into(),
            status: "validated".into(),
            validated_at_ms: now_ms,
            valid_until_ms,
            provider_epoch_id: owner.provider_epoch_id.clone(),
            owner_request_id: receipt.request_id.clone(),
            owner_receipt_sha256: canonical_receipt_sha256(receipt)?,
            media_decision_id: receipt.media_authority.renewed_media.decision_id.clone(),
            derivative_binding_id: receipt
                .media_authority
                .renewed_inner_lease
                .derivative_binding
                .as_ref()
                .ok_or_else(rejected)?
                .binding_id
                .clone(),
            current_start_action_id: recovery
                .active_start_action
                .as_ref()
                .ok_or_else(rejected)?
                .action_id
                .clone(),
            resource_identity_sha256,
            cumulative_renewal_count: receipt.cumulative_renewal_count.ok_or_else(rejected)?,
            current_lifetime_renewal_count: receipt
                .current_lifetime_renewal_count
                .ok_or_else(rejected)?,
            owner_history_epochs: epochs,
        })
    }

    /// Reads the policy accepted by this provider's actual peer owner.
    pub fn ongoing_media_continuation_enabled(&self) -> Result<bool, QuestBrokerRuntimeError> {
        Ok(self.ongoing_media_continuation_policy()?.is_some())
    }
    /// Observes the exact policy held by the actual Peer owner; no adapter flag.
    pub fn ongoing_media_continuation_policy(
        &self,
    ) -> Result<
        Option<rusty_manifold_peer_runtime_host::ManifoldOngoingMediaPolicy>,
        QuestBrokerRuntimeError,
    > {
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
        Ok(peer.snapshot().ongoing_media_policy.clone())
    }
    // Test seam only. Production selects policy in the authenticated config
    // before constructing the provider; it cannot rebind a finite live provider.
    #[cfg(test)]
    pub(crate) fn enable_ongoing_media_continuation(
        &mut self,
        policy: rusty_manifold_peer_runtime_host::ManifoldOngoingMediaPolicy,
    ) -> Result<(), QuestBrokerRuntimeError> {
        let owner = self
            .runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        if owner.concurrent_renewal.pending.is_some() {
            return Err(rejected());
        }
        let shared = owner
            .peer_runtime_host
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut peer = shared
            .write()
            .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let broker = owner
            .runtime
            .read()
            .map_err(|_| QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        peer.enable_ongoing_media_continuation(&broker, policy)
            .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)
    }
    /// Apply an already accepted signed local pair renewal to the live product authorities.
    /// Caller identity, lease, scopes and clocks come from the retained process authority.
    /// Errors retain the exact original request and all actual phase receipts for retry.
    pub fn renew_concurrent_peer_authority(
        &mut self,
        clock: &ManifoldClockSnapshot,
        proof: &ManifoldConcurrentPairSessionRenewalReceipt,
        now_ms: u64,
        entropy_hex: &str,
    ) -> Result<QuestConcurrentAuthorityRenewalReceipt, QuestBrokerRuntimeError> {
        let entropy =
            parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
        let owner = self
            .runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        if let Some(receipt) = owner
            .concurrent_renewal
            .completed
            .iter()
            .find(|r| r.accepted_pair.request_id == proof.request_id)
        {
            return if receipt.accepted_pair == *proof {
                Ok(receipt.clone())
            } else {
                Err(rejected())
            };
        }
        if owner.concurrent_renewal.completed.len() >= 32 {
            return Err(rejected());
        }
        let shared = owner
            .peer_runtime_host
            .clone()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        // The signed child released every network/Java callback before this owner join.
        let mut peer = shared
            .write()
            .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeConfig)?;
        let mut broker = owner
            .runtime
            .write()
            .map_err(|_| QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        if !peer.snapshot().concurrent_session_renewals.contains(proof)
            || !proof.applied
            || !peer
                .validate_peer_session_v2(&proof.session_id, now_ms)
                .current
        {
            return Err(rejected());
        }
        if let Some(pending) = &owner.concurrent_renewal.pending {
            if pending.proof != *proof {
                return Err(rejected());
            }
        } else {
            let prior = &broker.control_lease_authority_snapshot().clock_snapshot;
            if clock.clock_domain != prior.clock_domain
                || clock.clock_epoch_id != prior.clock_epoch_id
                || clock.sequence <= prior.sequence
                || clock.health != ClockHealth::Healthy
                || clock.monotonic_elapsed_ns < prior.monotonic_elapsed_ns
                || clock.wall_unix_ms < prior.wall_unix_ms
                || clock.read_uncertainty_ns > 10_000_000
                || u64::try_from(clock.wall_unix_ms).ok() != Some(now_ms)
                || proof.observed_at_ms > now_ms
                || proof.expires_at_ms <= now_ms
            {
                return Err(QuestBrokerRuntimeError::InvalidAuthorityClock);
            }
            let medias:Vec<_>=peer.snapshot().media_sessions.sessions.iter().filter(|m|
                m.lifecycle_status==rusty_manifold_media_session::ManifoldMediaSessionLifecycleStatus::Current&&m.expires_at_ms>now_ms).collect();
            let [media] = medias.as_slice() else {
                return Err(phase_rejected(
                    "renewal requires one current admitted media decision",
                ));
            };
            if !owner.media_bindings.iter().any(|b| {
                b.manifold.descriptor.platform_runtime_spec_id == media.platform_runtime_spec_id
            }) || !owner.media_sessions.contains_key(&media.runtime_client_id)
            {
                return Err(rejected());
            }
            let grants: Vec<_> = broker
                .admission_snapshot()
                .grants
                .iter()
                .filter(|g| {
                    g.grant_id == media.admission_grant_id
                        && g.identity.client_id == media.runtime_client_id
                        && !g.revoked
                        && g.expires_at_ms > now_ms
                        && g.capabilities
                            .iter()
                            .any(|c| c.as_str() == "capability.manifold.control_lease.renew")
                })
                .collect();
            let [grant] = grants.as_slice() else {
                return Err(phase_rejected("renewal requires one current authenticated grant with the selected renewal capability"));
            };
            let leases: Vec<_> = broker
                .host_snapshot()
                .leases
                .iter()
                .filter(|l| {
                    l.holder_id == media.runtime_client_id
                        && l.scope.as_str() == "lease.media.session"
                        && l.expires_at_ms > now_ms
                })
                .collect();
            let [lease] = leases.as_slice() else {
                return Err(phase_rejected(
                    "renewal requires one current outer media lease",
                ));
            };
            let root_horizon_ms = broker
                .admission_snapshot()
                .root_renewal_horizon_ms(&grant.grant_id);
            let expiry = now_ms
                .checked_add(5_000)
                .ok_or_else(rejected)?
                .min(grant.expires_at_ms)
                .min(lease.expires_at_ms)
                .min(
                    now_ms
                        .checked_add(broker.admission_snapshot().max_token_ttl_ms)
                        .ok_or_else(rejected)?,
                );
            if expiry <= now_ms
                || now_ms.checked_add(root_horizon_ms).ok_or_else(rejected)? <= grant.expires_at_ms
                || now_ms.checked_add(root_horizon_ms).ok_or_else(rejected)? <= lease.expires_at_ms
            {
                return Err(phase_rejected("renewal grant and outer deadlines must advance within their bounded owner policy"));
            }
            let digest =
                sha256_hex(&serde_json::to_vec(proof).map_err(QuestBrokerRuntimeError::Encode)?);
            owner.concurrent_renewal.pending = Some(Pending {
                proof: proof.clone(),
                clock: clock.clone(),
                now_ms,
                entropy,
                request_id: DottedId::new(format!("request.quest.concurrent.renew.{digest}"))
                    .map_err(|_| rejected())?,
                identity: grant.identity.clone(),
                grant_id: grant.grant_id.clone(),
                prior_grant_expiry: grant.expires_at_ms,
                root_horizon_ms,
                lease_id: lease.lease_id.clone(),
                token_expiry: expiry,
                grant_issue: None,
                grant_request: None,
                grant: None,
                grant_capability_use: None,
                lease_issue: None,
                lifecycle_request: None,
                lease_authorization: None,
                lifecycle: None,
            });
        }
        let pending = owner
            .concurrent_renewal
            .pending
            .as_mut()
            .ok_or_else(rejected)?;
        // A retry cannot use an old review clock to revive a consumed/expired permit.
        if pending.lifecycle.is_none() && pending.token_expiry <= now_ms {
            return Err(phase_rejected(
                "retained renewal permit expired before actual outer application",
            ));
        }
        if pending.grant_issue.is_none() {
            pending.grant_issue = Some(token_issue(
                &mut broker,
                pending,
                "grant-issue",
                &entropy_for(&pending.entropy, b"grant"),
            )?);
        }
        if pending.grant_request.is_none() {
            pending.grant_request = Some(ManifoldAdmissionGrantRenewalRequest {
                schema_id: schema("rusty.manifold.admission.grant_renewal_request.v1"),
                request_id: broker
                    .current_admission_history_request_id(&request("grant", &pending.request_id)?)
                    .map_err(QuestBrokerRuntimeError::RuntimeState)?,
                authorization: authorization(
                    &broker,
                    pending,
                    pending.grant_issue.as_ref().ok_or_else(rejected)?,
                    "grant-use",
                )?,
                grant_id: pending.grant_id.clone(),
                prior_expires_at_ms: pending.prior_grant_expiry,
                expires_at_ms: pending
                    .now_ms
                    .checked_add(pending.root_horizon_ms)
                    .ok_or_else(rejected)?,
                accepted_evidence_id: pending.proof.request_id.clone(),
                accepted_evidence_sha256: format!(
                    "sha256:{}",
                    sha256_hex(
                        &serde_json::to_vec(&pending.proof)
                            .map_err(QuestBrokerRuntimeError::Encode)?,
                    )
                ),
            });
        }
        if pending.grant.is_none() {
            let actual = broker
                .renew_current_admission_grant(
                    pending.grant_request.as_ref().ok_or_else(rejected)?,
                    now_ms,
                )
                .map_err(|error| {
                    phase_rejected(&format!("actual grant renewal rejected: {error}"))
                })?;
            pending.grant = Some(actual);
        }
        if !pending
            .grant
            .as_ref()
            .is_some_and(|r| r.application.applied)
        {
            return Err(phase_rejected(
                "actual grant renewal application was rejected",
            ));
        }
        // Preserve the exact already consumed phase before its owner history may
        // compact. A retry cannot search for or consume a replacement grant use.
        if pending.grant_capability_use.is_none() {
            let grant_use_id = &pending
                .grant_request
                .as_ref()
                .ok_or_else(rejected)?
                .authorization
                .request_id;
            let grant_uses: Vec<_> = broker
                .evidence()
                .committed_capability_use_receipts
                .into_iter()
                .filter(|receipt| {
                    receipt.applied
                        && receipt.provider_epoch_id == owner.provider_epoch_id
                        && receipt.bounded_use.as_ref().is_some_and(|use_| {
                            &use_.admission_use_request_id == grant_use_id
                                && use_.identity == pending.identity
                                && use_.admission_grant_id == pending.grant_id
                                && use_.capability_id.as_str()
                                    == "capability.manifold.control_lease.renew"
                        })
                })
                .collect();
            let [actual_grant_capability_use] = grant_uses.as_slice() else {
                return Err(phase_rejected(
                    "actual grant renewal lacks its exact Broker bounded use consumption",
                ));
            };
            pending.grant_capability_use = Some(actual_grant_capability_use.clone());
        }
        let grant_capability_use = pending.grant_capability_use.as_ref().ok_or_else(rejected)?;
        if pending.lease_issue.is_none() {
            pending.lease_issue = Some(token_issue(
                &mut broker,
                pending,
                "lease-issue",
                &entropy_for(&pending.entropy, b"lease"),
            )?);
        }
        if pending.lifecycle_request.is_none() {
            let use_ = authorization(
                &broker,
                pending,
                pending.lease_issue.as_ref().ok_or_else(rejected)?,
                "lease-use",
            )?;
            let lifecycle = ManifoldBrokerControlLeaseLifecycleRequest {
                schema_id: schema("rusty.manifold.broker.control_lease_lifecycle_request.v2"),
                provider_epoch_id: owner.provider_epoch_id.clone(),
                admission_use_request_id: use_.request_id.clone(),
                token_id: use_.token_id.clone(),
                expected_admission_authority_revision: use_.expected_authority_revision,
                operation: ManifoldBrokerControlLeaseLifecycleOperation::Renewal {
                    request_id: broker
                        .current_control_history_request_id(&request("lease", &pending.request_id)?)
                        .map_err(QuestBrokerRuntimeError::RuntimeState)?,
                    lease_id: pending.lease_id.clone(),
                    expected_authority_revision: broker
                        .control_lease_authority_snapshot()
                        .authority_revision,
                    requested_ttl_ms: pending.root_horizon_ms,
                    renewal_reason: DottedId::new("reason.quest.concurrent.current-signed-pair")
                        .expect("fixed reason"),
                    requested_at_ms: pending.now_ms,
                },
            };
            pending.lifecycle_request = Some(lifecycle.clone());
            pending.lease_authorization =
                Some(broker.authorize_control_lease_lifecycle_use(&use_, &lifecycle, now_ms));
        }
        if !pending
            .lease_authorization
            .as_ref()
            .is_some_and(|r| r.applied)
        {
            return Err(phase_rejected(
                "actual operation-bound lease admission was rejected",
            ));
        }
        if pending.lifecycle.is_none() {
            pending.lifecycle = Some(
                broker
                    .commit_control_lease_lifecycle(
                        pending.lifecycle_request.as_ref().ok_or_else(rejected)?,
                        pending.clock.clone(),
                        vec![pending.proof.request_id.clone()],
                        |receipt, _| receipt.clone(),
                    )
                    .map_err(QuestBrokerRuntimeError::RuntimeState)?,
            );
        }
        let lifecycle = pending.lifecycle.as_ref().ok_or_else(rejected)?;
        if !lifecycle.applied
            || lifecycle.outcome != ManifoldBrokerControlLeaseLifecycleOutcome::AcceptedAndAdopted
        {
            return Err(phase_rejected(
                "actual generic outer lease application and adoption were rejected",
            ));
        }
        let media_authority = peer
            .adopt_concurrent_media_authority_renewal_with_live_broker_runtime(
                &broker,
                &pending.proof,
                lifecycle,
                now_ms,
            )
            .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        if !media_authority.applied
            || media_authority.provider_epoch_id != owner.provider_epoch_id
            || media_authority.paired_session_receipt != pending.proof
            || media_authority.broker_lifecycle_receipt != *lifecycle
        {
            return Err(rejected());
        }
        let product_adoption = owner
            .media_sessions
            .get_mut(&pending.identity.client_id)
            .ok_or_else(rejected)?
            .adopt_concurrent_authority_renewal(&peer, &broker, &media_authority, now_ms)
            .map_err(QuestBrokerRuntimeError::MediaRuntime)?;
        if product_adoption.owner_request_id != media_authority.request_id
            || product_adoption.prior_physical_graph_sha256
                != product_adoption.physical_graph_sha256
        {
            return Err(phase_rejected(
                "actual product adoption did not preserve the physical graph",
            ));
        }
        #[cfg(test)]
        if owner.concurrent_renewal.pause_after_product_adoption {
            owner.concurrent_renewal.pause_after_product_adoption = false;
            return Err(phase_rejected(
                "test pause after actual product adoption; exact pending retained",
            ));
        }
        let generic_history = peer.snapshot().runtime_history_checkpoint.is_some();
        let continuation_checkpoint = if generic_history {
            peer.checkpoint_coupled_ongoing_histories(&mut broker, &media_authority, now_ms)
        } else {
            peer.checkpoint_concurrent_media_history(&broker, &media_authority, now_ms)
        }
        .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        #[cfg(test)]
        if owner.concurrent_renewal.pause_after_history_commit {
            owner.concurrent_renewal.pause_after_history_commit = false;
            return Err(phase_rejected(
                "test pause after actual generic history publication; exact pending retained",
            ));
        }
        let cumulative_renewal_count = peer
            .snapshot()
            .ongoing_media_policy
            .as_ref()
            .map(|_| peer.cumulative_concurrent_media_renewals())
            .transpose()
            .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?;
        let owner_history_epochs = if generic_history {
            let evidence = broker.evidence();
            Some(QuestConcurrentOwnerHistoryEpochs {
                schema_id: "rusty.quest.concurrent.owner_history_epochs.v1".into(),
                admission_request_epoch: evidence
                    .admission_snapshot
                    .history_checkpoint
                    .as_ref()
                    .ok_or_else(rejected)?
                    .request_epoch,
                control_lease_request_epoch: evidence
                    .control_lease_authority
                    .history_checkpoint
                    .as_ref()
                    .ok_or_else(rejected)?
                    .request_epoch,
                broker_host_request_epoch: evidence
                    .host_snapshot
                    .history_checkpoint
                    .as_ref()
                    .ok_or_else(rejected)?
                    .request_epoch,
                media_command_request_epoch: peer
                    .snapshot()
                    .media_command_runtime
                    .history_checkpoint
                    .as_ref()
                    .ok_or_else(rejected)?
                    .request_epoch,
                reciprocal_request_epoch: peer
                    .snapshot()
                    .reciprocal_ed25519
                    .history_checkpoint
                    .as_ref()
                    .ok_or_else(rejected)?
                    .request_epoch,
                peer_request_epoch: peer
                    .snapshot()
                    .runtime_history_checkpoint
                    .as_ref()
                    .ok_or_else(rejected)?
                    .request_epoch,
            })
        } else {
            None
        };
        let receipt = QuestConcurrentAuthorityRenewalReceipt {
            owner_history_epochs,
            current_lifetime_renewal_count: generic_history
                .then(|| peer.current_lifetime_concurrent_media_renewals())
                .transpose()
                .map_err(QuestBrokerRuntimeError::MediaPeerRuntime)?,
            runtime_history_checkpoint: peer
                .snapshot()
                .runtime_history_checkpoint
                .as_deref()
                .cloned(),
            ongoing_media_policy: peer.snapshot().ongoing_media_policy.clone(),
            continuation_checkpoint,
            cumulative_renewal_count,
            checkpoint_sequence: cumulative_renewal_count.map(|_| {
                peer.snapshot()
                    .concurrent_media_checkpoint
                    .as_ref()
                    .map_or(0, |c| c.checkpoint_sequence)
            }),
            checkpointed_renewal_count: cumulative_renewal_count.map(|_| {
                peer.snapshot()
                    .concurrent_media_checkpoint
                    .as_ref()
                    .map_or(0, |c| c.cumulative_renewals)
            }),
            suffix_renewal_count: cumulative_renewal_count.map(|count| {
                count
                    - peer
                        .snapshot()
                        .concurrent_media_checkpoint
                        .as_ref()
                        .map_or(0, |c| c.cumulative_renewals)
            }),
            schema_id: if generic_history {
                "rusty.quest.concurrent.authority_renewal_receipt.v3"
            } else if cumulative_renewal_count.is_some() {
                "rusty.quest.concurrent.authority_renewal_receipt.v2"
            } else {
                "rusty.quest.concurrent.authority_renewal_receipt.v1"
            }
            .to_owned(),
            request_id: pending.request_id.clone(),
            provider_epoch_id: owner.provider_epoch_id.clone(),
            observed_at_ms: pending.now_ms,
            clock: pending.clock.clone(),
            accepted_pair: pending.proof.clone(),
            grant_token_issue: pending.grant_issue.as_ref().ok_or_else(rejected)?.clone(),
            admission_grant: pending.grant.as_ref().ok_or_else(rejected)?.clone(),
            grant_capability_use: grant_capability_use.clone(),
            lease_token_issue: pending.lease_issue.as_ref().ok_or_else(rejected)?.clone(),
            lease_authorization: pending
                .lease_authorization
                .as_ref()
                .ok_or_else(rejected)?
                .clone(),
            outer_lifecycle: lifecycle.clone(),
            media_authority,
            product_adoption,
        };
        // A serialization bound is enforced before exposing a fixed operator response.
        if serde_json::to_vec(&receipt)
            .map_err(QuestBrokerRuntimeError::Encode)?
            .len()
            >= 1_048_576
        {
            return Err(rejected());
        }
        if receipt.continuation_checkpoint.is_some() {
            owner.concurrent_renewal.completed.clear();
        }
        owner.concurrent_renewal.completed.push(receipt.clone());
        owner.concurrent_renewal.pending = None;
        Ok(receipt)
    }
}

#[cfg(test)]
mod current_observation_tests {
    use super::*;
    #[test]
    fn current_receipt_hash_matches_native_value_projection_without_reordering_arrays() {
        #[derive(Serialize)]
        struct Typed {
            z: Vec<u64>,
            a: u64,
        }
        let typed = Typed {
            z: vec![2, 1],
            a: 3,
        };
        let projected = serde_json::json!({"a":3,"z":[2,1]});
        assert_eq!(
            canonical_receipt_sha256(&typed).unwrap(),
            canonical_receipt_sha256(&projected).unwrap()
        );
        assert_ne!(
            canonical_receipt_sha256(&typed).unwrap(),
            canonical_receipt_sha256(&serde_json::json!({"a":3,"z":[1,2]})).unwrap()
        );
    }
}
