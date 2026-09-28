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

/// Complete owner join, issued only after the same resources adopt every renewed authority.
#[derive(Clone, Debug, Serialize)]
pub struct QuestConcurrentAuthorityRenewalReceipt {
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
    lease_id: DottedId,
    token_expiry: u64,
    grant_issue: Option<ManifoldAdmissionReceipt>,
    grant_request: Option<ManifoldAdmissionGrantRenewalRequest>,
    grant: Option<ManifoldAdmissionGrantRenewalReceipt>,
    lease_issue: Option<ManifoldAdmissionReceipt>,
    lifecycle_request: Option<ManifoldBrokerControlLeaseLifecycleRequest>,
    lease_authorization: Option<ManifoldBrokerControlLeaseLifecycleAuthorizationReceipt>,
    lifecycle: Option<ManifoldBrokerControlLeaseLifecycleReceipt>,
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
) -> ManifoldAdmissionReceipt {
    broker.issue_token(
        &ManifoldAdmissionRequest {
            schema_id: schema(ADMISSION_REQUEST_SCHEMA),
            request_id: request(part, &pending.request_id).expect("retained validated ID"),
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
    )
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
        request_id: request(part, &pending.request_id)?,
        expected_authority_revision: broker.admission_snapshot().authority_revision,
        token_id: issue.token.as_ref().ok_or_else(rejected)?.token_id.clone(),
        identity: pending.identity.clone(),
        capability_id: DottedId::new("capability.manifold.control_lease.renew").expect("fixed cap"),
        issued_at_ms: pending.now_ms,
        expires_at_ms: pending.token_expiry,
    })
}

impl QuestBrokerRuntimeProvider {
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
                || now_ms.checked_add(300_000).ok_or_else(rejected)? <= grant.expires_at_ms
                || now_ms.checked_add(300_000).ok_or_else(rejected)? <= lease.expires_at_ms
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
                lease_id: lease.lease_id.clone(),
                token_expiry: expiry,
                grant_issue: None,
                grant_request: None,
                grant: None,
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
            ));
        }
        if pending.grant_request.is_none() {
            pending.grant_request = Some(ManifoldAdmissionGrantRenewalRequest {
                schema_id: schema("rusty.manifold.admission.grant_renewal_request.v1"),
                request_id: request("grant", &pending.request_id)?,
                authorization: authorization(
                    &broker,
                    pending,
                    pending.grant_issue.as_ref().ok_or_else(rejected)?,
                    "grant-use",
                )?,
                grant_id: pending.grant_id.clone(),
                prior_expires_at_ms: pending.prior_grant_expiry,
                expires_at_ms: pending.now_ms.checked_add(300_000).ok_or_else(rejected)?,
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
        let [grant_capability_use] = grant_uses.as_slice() else {
            return Err(phase_rejected(
                "actual grant renewal lacks its exact Broker bounded use consumption",
            ));
        };
        if pending.lease_issue.is_none() {
            pending.lease_issue = Some(token_issue(
                &mut broker,
                pending,
                "lease-issue",
                &entropy_for(&pending.entropy, b"lease"),
            ));
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
                    request_id: request("lease", &pending.request_id)?,
                    lease_id: pending.lease_id.clone(),
                    expected_authority_revision: broker
                        .control_lease_authority_snapshot()
                        .authority_revision,
                    requested_ttl_ms: 300_000,
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
        let receipt = QuestConcurrentAuthorityRenewalReceipt {
            schema_id: "rusty.quest.concurrent.authority_renewal_receipt.v1".to_owned(),
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
        owner.concurrent_renewal.completed.push(receipt.clone());
        owner.concurrent_renewal.pending = None;
        Ok(receipt)
    }
}
