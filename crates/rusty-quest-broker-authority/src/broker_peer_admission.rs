//! Fixed app lifecycle operations over the live signature-scoped Broker.
//! No transport-provided client, lease, revision, capability or clock is accepted.
use super::*;
use rusty_quest_broker_admission::QuestAndroidBinderCaller;

/// The two existing outer product commands exposed to the app lifecycle facade.
#[derive(Clone, Copy, Debug)]
pub enum QuestConcurrentPeerCommand {
    /// Prepare the real media Start through admission and Runtime Host.
    Start,
    /// Prepare the real media Stop through admission and Runtime Host.
    Stop,
}

/// Exact admitted mutation and target retained for physical completion.
#[derive(Clone, Debug, Serialize)]
pub struct QuestConcurrentPeerMutation {
    /// Selected media client from the authenticated current admission grant.
    pub client_id: DottedId,
    /// Exact broker lease used for this mutation.
    pub broker_lease_id: DottedId,
    /// Token issue receipt; this alone is never media acceptance.
    pub issue: QuestBrokerAdmissionResponse,
    /// Single-use authorization receipt.
    pub authorized_use: QuestBrokerAdmissionResponse,
    /// Actual Runtime Host and media preparation response.
    pub mutation: QuestBrokerServerMutationResponse,
}

impl QuestBrokerRuntimeProvider {
    /// Bind a real embedding OS clock read to the existing native authority
    /// epoch. This observes time; it grants no lease and changes no snapshot.
    ///
    /// # Errors
    /// Rejects reversed clocks, incoherent wall/monotonic deltas, unhealthy or
    /// foreign accepted clocks, excessive uncertainty and sequence exhaustion.
    pub fn read_concurrent_peer_clock(
        &self,
        wall_unix_ms: i64,
        monotonic_elapsed_ns: u64,
        read_uncertainty_ns: u64,
    ) -> Result<ManifoldClockSnapshot, QuestBrokerRuntimeError> {
        let runtime = self
            .runtime
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let broker = runtime
            .runtime
            .read()
            .map_err(|_| QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
        let prior = &broker.control_lease_authority_snapshot().clock_snapshot;
        if prior.health != ClockHealth::Healthy
            || prior.clock_domain.as_str() != "clock.android.system"
            || wall_unix_ms < prior.wall_unix_ms
            || monotonic_elapsed_ns < prior.monotonic_elapsed_ns
            || read_uncertainty_ns > 10_000_000
        {
            return Err(QuestBrokerRuntimeError::InvalidAuthorityClock);
        }
        let wall_delta = u64::try_from(wall_unix_ms - prior.wall_unix_ms)
            .map_err(|_| QuestBrokerRuntimeError::InvalidAuthorityClock)?;
        let monotonic_delta = (monotonic_elapsed_ns - prior.monotonic_elapsed_ns) / 1_000_000;
        if wall_delta.abs_diff(monotonic_delta) > 1_000 {
            return Err(QuestBrokerRuntimeError::InvalidAuthorityClock);
        }
        let mut clock = prior.clone();
        let mut sequence = clock.sequence;
        if let Some(revoker) = self.concurrent_revoker_clock() {
            if revoker.clock_domain != prior.clock_domain
                || revoker.clock_epoch_id != prior.clock_epoch_id
                || wall_unix_ms < revoker.wall_unix_ms
                || monotonic_elapsed_ns < revoker.monotonic_elapsed_ns
            {
                return Err(QuestBrokerRuntimeError::InvalidAuthorityClock);
            }
            sequence = sequence.max(revoker.sequence);
        }
        clock.sequence = sequence
            .checked_add(1)
            .ok_or(QuestBrokerRuntimeError::InvalidAuthorityClock)?;
        clock.wall_unix_ms = wall_unix_ms;
        clock.monotonic_elapsed_ns = monotonic_elapsed_ns;
        clock.read_uncertainty_ns = read_uncertainty_ns;
        Ok(clock)
    }

    /// Apply a fixed product media command using installed-process identity.
    /// The embedding obtains UID from the OS, and package/certificate from the
    /// PackageManager-authenticated packaged initialization, never shell extras.
    ///
    /// # Errors
    /// Rejects stale identity, missing/ambiguous live grant or lease, bad entropy,
    /// failed admission, and rejected Runtime Host mutation.
    pub fn apply_concurrent_peer_command(
        &mut self,
        action: QuestConcurrentPeerCommand,
        sending_uid: u32,
        package_name: &str,
        signing_certificate_sha256: &str,
        now_ms: u64,
        entropy_hex: &str,
    ) -> Result<QuestConcurrentPeerMutation, QuestBrokerRuntimeError> {
        parse_entropy_hex(entropy_hex).map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
        let runtime = self
            .runtime
            .as_mut()
            .ok_or(QuestBrokerRuntimeError::NotInitialized)?;
        let command_id = DottedId::new(match action {
            QuestConcurrentPeerCommand::Start => "command.media.session.start",
            QuestConcurrentPeerCommand::Stop => "command.media.session.stop",
        })
        .expect("fixed command");
        let caller = QuestAndroidBinderCaller {
            sending_uid,
            package_name: package_name.to_owned(),
            signing_certificate_sha256: signing_certificate_sha256.to_owned(),
        };
        let capability = command_capability(&command_id);
        let (client_id, lease_id, expiry, max_ttl) = {
            let broker = runtime
                .runtime
                .read()
                .map_err(|_| QuestBrokerRuntimeError::RuntimeLockPoisoned)?;
            let identity = project_binder_caller(broker.admission_snapshot(), &caller)
                .map_err(QuestBrokerRuntimeError::AdmissionProjection)?;
            let grants: Vec<_> = broker
                .admission_snapshot()
                .grants
                .iter()
                .filter(|g| {
                    g.identity == identity
                        && !g.revoked
                        && g.expires_at_ms > now_ms
                        && g.capabilities.contains(&capability)
                })
                .collect();
            let [grant] = grants.as_slice() else {
                return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
            };
            let leases: Vec<_> = broker
                .host_snapshot()
                .leases
                .iter()
                .filter(|l| {
                    l.holder_id == identity.client_id
                        && l.scope.as_str() == "lease.media.session"
                        && l.expires_at_ms > now_ms
                })
                .collect();
            let [lease] = leases.as_slice() else {
                return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
            };
            // Start's real admitted use also bounds the derivative media lease.
            // Keep it bounded by the authenticated product policy; Stop remains
            // a short mutation permit and cannot prolong the resource lifetime.
            let command_ttl = match action {
                QuestConcurrentPeerCommand::Start => 120_000,
                QuestConcurrentPeerCommand::Stop => 5_000,
            };
            let expiry = now_ms
                .checked_add(command_ttl)
                .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected)?
                .min(grant.expires_at_ms)
                .min(lease.expires_at_ms)
                .min(now_ms.saturating_add(broker.admission_snapshot().max_token_ttl_ms));
            (
                identity.client_id,
                lease.lease_id.clone(),
                expiry,
                broker.admission_snapshot().max_token_ttl_ms,
            )
        };
        if expiry <= now_ms || max_ttl == 0 {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
        }
        // Entropy also gives request identities. Distinct prefixes prevent one
        // issued token from aliasing its use or command in replay ledgers.
        let request = |part: &str| {
            DottedId::new(format!("request.quest.concurrent.{part}.{entropy_hex}"))
                .map_err(|_| QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected)
        };
        let revision = runtime
            .evidence()?
            .runtime
            .admission_snapshot
            .authority_revision;
        let issue = runtime.execute_admission(QuestBrokerAdmissionOperation::IssueToken {
            schema_id: QUEST_ADMISSION_OPERATION_SCHEMA.to_owned(),
            caller: caller.clone(),
            request_id: request("issue")?,
            expected_authority_revision: revision,
            requested_capabilities: vec![capability.clone()],
            requested_token_ttl_ms: expiry - now_ms,
            issued_at_ms: now_ms,
            expires_at_ms: expiry,
            entropy_hex: entropy_hex.to_owned(),
        })?;
        if !issue.receipt.applied {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
        }
        let token = issue
            .receipt
            .token
            .as_ref()
            .ok_or(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected)?
            .token_id
            .clone();
        let use_id = request("use")?;
        let authorized_use =
            runtime.execute_admission(QuestBrokerAdmissionOperation::AuthorizeUse {
                schema_id: QUEST_ADMISSION_OPERATION_SCHEMA.to_owned(),
                caller,
                request_id: use_id.clone(),
                expected_authority_revision: runtime
                    .evidence()?
                    .runtime
                    .admission_snapshot
                    .authority_revision,
                token_id: token.clone(),
                capability_id: capability,
                issued_at_ms: now_ms,
                expires_at_ms: expiry,
            })?;
        if !authorized_use.receipt.applied {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
        }
        let current = runtime.evidence()?;
        let params = QuestBrokerEffectParams {
            schema_id: QUEST_BROKER_EFFECT_PARAMS_SCHEMA.to_owned(),
            command_id: command_id.clone(),
            values: BTreeMap::new(),
        };
        let mutation = runtime.handle_server_mutation(
            &QuestBrokerServerMutationRequest {
                schema_id: QUEST_BROKER_SERVER_MUTATION_SCHEMA.to_owned(),
                bridge_kind: runtime.bridge_kind.clone(),
                provider_epoch_id: runtime.provider_epoch_id.clone(),
                admission_use_request_id: use_id,
                token_id: token,
                expected_admission_authority_revision: current
                    .runtime
                    .admission_snapshot
                    .authority_revision,
                command: ManifoldRuntimeCommandRequest {
                    schema_id: schema(HOST_COMMAND_REQUEST_SCHEMA),
                    request_id: request("command")?,
                    expected_authority_revision: current.runtime.host_snapshot.authority_revision,
                    requester_id: client_id.clone(),
                    command_id,
                    lease_id: Some(lease_id.clone()),
                    params_digest: Some(canonical_effect_params_digest(&params)?),
                    issued_at_ms: now_ms,
                    expires_at_ms: expiry,
                },
                params,
            },
            now_ms,
        )?;
        if !mutation.accepted {
            return Err(QuestBrokerRuntimeError::MediaPeerRuntimeTransitionRejected);
        }
        Ok(QuestConcurrentPeerMutation {
            client_id,
            broker_lease_id: lease_id,
            issue,
            authorized_use,
            mutation,
        })
    }
}
