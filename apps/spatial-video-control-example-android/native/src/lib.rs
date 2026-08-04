//! Typed process-local Manifold binding for the attended Quest control example.
//!
//! The Android adapter owns only HTTP transport facts, the manually entered
//! one-use code, and the opaque transport cookie. This crate owns the exact
//! Manifold composite and exposes one JNI function per closed operation. It
//! never accepts a generic command document or reports an application effect.

use getrandom::getrandom;
use rusty_manifold_admission::{
    ManifoldAdmissionAuthority, ManifoldAdmissionGrant, ManifoldAdmissionSnapshot,
    ManifoldClientIdentity, ADMISSION_SNAPSHOT_SCHEMA,
};
use rusty_manifold_local_control::{
    ManifoldLocalControlAccessMode, ManifoldLocalControlAdmissionRequest,
    ManifoldLocalControlAuthority, ManifoldLocalControlEnableActor,
    ManifoldLocalControlCommandDescriptor, ManifoldLocalControlCommandRequest,
    ManifoldLocalControlDisableReceipt, ManifoldLocalControlDisableRequest,
    ManifoldLocalControlExpiryReceipt, ManifoldLocalControlExpiryRequest,
    ManifoldLocalControlPairingPresentation, ManifoldLocalControlPolicy,
    ManifoldLocalControlSafeStatus, ManifoldLocalControlState, ManifoldLocalControlWindowReceipt,
    ManifoldLocalControlWindowRequest, ManifoldLocalControllerEvidence,
    LOCAL_CONTROL_ADMISSION_REQUEST_SCHEMA, LOCAL_CONTROL_COMMAND_REQUEST_SCHEMA,
    LOCAL_CONTROL_CONTROLLER_EVIDENCE_SCHEMA, LOCAL_CONTROL_DISABLE_REQUEST_SCHEMA,
    LOCAL_CONTROL_EXPIRY_REQUEST_SCHEMA, LOCAL_CONTROL_POLICY_SCHEMA,
    LOCAL_CONTROL_WINDOW_REQUEST_SCHEMA,
};
use rusty_manifold_model::{
    AuthorityRole, ClockHealth, DottedId, EndpointDescriptor, EndpointSecurity, EndpointTransport,
    EndpointVisibility, ManifoldAuthoritySnapshot, ManifoldClockSnapshot,
    ManifoldCommandDescriptor, ManifoldHostManifest, ManifoldStreamRegistrySnapshot, Revision,
    SafetyClass, SchemaId,
};
use rusty_manifold_runtime_host::{
    ManifoldRuntimeCommandDescriptor, ManifoldRuntimeHost, ManifoldRuntimeHostSnapshot,
    ManifoldRuntimeTypedParamsDigest, HOST_SNAPSHOT_SCHEMA,
};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};
use std::time::{Instant as MonotonicInstant, SystemTime, UNIX_EPOCH};

const ADAPTER_ID: &str = "adapter.quest.trusted_local_http";
const AUTHORITY_ID: &str = "authority.quest.local_control";
const CONTROLLER_ID: &str = "controller.browser.local";
const CONTROLLER_CAPABILITY: &str = "capability.local.controller";
const CONTROLLER_SCOPE: &str = "scope.local.player";
const HOST_ID: &str = "host.quest.local_control";
const MAX_WINDOW_MS: u64 = 300_000;
const MAX_SESSION_MS: u64 = 180_000;
const IDLE_MS: u64 = 45_000;
const RATE_WINDOW_MS: u64 = 60_000;
const RATE_LIMIT: u16 = 20;

#[derive(Debug)]
pub struct LocalControlEngine {
    authority: ManifoldLocalControlAuthority,
    token_id: Option<DottedId>,
    clock_sequence: u64,
    clock_started: MonotonicInstant,
    last_monotonic_ns: u64,
    last_wall_ms: u64,
    wall_clock_adjustment_count: u32,
    platform_subject: String,
    signing_fingerprint: String,
}

impl LocalControlEngine {
    pub fn new(
        platform_subject: &str,
        signing_fingerprint: &str,
        allow_debug_shell_operator: bool,
    ) -> Result<Self, String> {
        validate_platform_identity(platform_subject, signing_fingerprint)?;
        let clock_started = MonotonicInstant::now();
        let wall_ms = system_wall_ms()?;
        let policy = policy(
            platform_subject,
            signing_fingerprint,
            allow_debug_shell_operator,
        )?;
        let initial_clock = clock(1, wall_ms, 0, 0, ClockHealth::Healthy);
        let authority = ManifoldLocalControlAuthority::new(
            policy.clone(),
            admission(&policy)?,
            lease_authority(&policy, initial_clock),
            runtime_host(&policy)?,
        )
        .map_err(|error| error.to_string())?;
        Ok(Self {
            authority,
            token_id: None,
            clock_sequence: 1,
            clock_started,
            last_monotonic_ns: 0,
            last_wall_ms: wall_ms,
            wall_clock_adjustment_count: 0,
            platform_subject: platform_subject.to_owned(),
            signing_fingerprint: signing_fingerprint.to_owned(),
        })
    }

    pub fn matches_identity(
        &self,
        platform_subject: &str,
        signing_fingerprint: &str,
        allow_debug_shell_operator: bool,
    ) -> bool {
        self.platform_subject == platform_subject
            && self.signing_fingerprint == signing_fingerprint
            && self.authority.policy().allow_debug_shell_operator == allow_debug_shell_operator
    }

    pub fn safe_status_json(&self) -> Result<String, String> {
        to_json(&self.authority.safe_status())
    }

    pub fn open_window_json(
        &mut self,
        external_request_id: &str,
        requested_window_ms: u64,
        access_mode: &str,
        enable_actor: &str,
    ) -> Result<String, String> {
        if requested_window_ms == 0 || requested_window_ms > MAX_WINDOW_MS {
            return Err("pairing_window_out_of_bounds".to_owned());
        }
        let wall_ms = self.trusted_now_ms()?;
        let status = self.authority.safe_status();
        let request_id = derived_external_id("request.local.window.open", external_request_id);
        let wearer_evidence_id =
            derived_external_id("evidence.wearer.window.open", external_request_id);
        let access_mode = match access_mode {
            "paired" => ManifoldLocalControlAccessMode::Paired,
            "open_lan_insecure" => ManifoldLocalControlAccessMode::OpenLanInsecure,
            _ => return Err("invalid_access_mode".to_owned()),
        };
        let enable_actor = match enable_actor {
            "wearer" => ManifoldLocalControlEnableActor::Wearer,
            "debug_shell" if self.authority.policy().allow_debug_shell_operator => {
                ManifoldLocalControlEnableActor::DebugShell
            }
            "debug_shell" => return Err("debug_shell_operator_disabled".to_owned()),
            _ => return Err("invalid_enable_actor".to_owned()),
        };
        let request = ManifoldLocalControlWindowRequest {
            schema_id: schema(LOCAL_CONTROL_WINDOW_REQUEST_SCHEMA),
            window_id: derived_external_id("window.local", external_request_id),
            access_mode,
            enable_actor,
            expected_local_revision: status.local_revision,
            opened_at_ms: wall_ms,
            expires_at_ms: wall_ms.saturating_add(requested_window_ms),
            wearer_evidence_id: wearer_evidence_id.clone(),
            request_id,
        };
        let receipt = self.authority.open_pairing_window(&request);
        to_json(&OpenWindowBridgeReceipt {
            window_receipt: receipt,
            wearer_evidence_id,
        })
    }

    pub fn admit_json(&mut self, external_request_id: &str) -> Result<String, String> {
        let wall_ms = self.trusted_now_ms()?;
        let status = self.authority.safe_status();
        let window_id = status
            .window_id
            .clone()
            .ok_or_else(|| "pairing_window_not_open".to_owned())?;
        let window_expires_at_ms = status
            .window_expires_at_ms
            .ok_or_else(|| "pairing_window_not_open".to_owned())?;
        let access_mode = status
            .access_mode
            .ok_or_else(|| "access_window_mode_missing".to_owned())?;
        let request_id = derived_external_id("request.local.controller.admit", external_request_id);
        let request = ManifoldLocalControlAdmissionRequest {
            schema_id: schema(LOCAL_CONTROL_ADMISSION_REQUEST_SCHEMA),
            request_id,
            expected_local_revision: status.local_revision,
            expected_admission_revision: status.admission_revision,
            expected_lease_authority_revision: status.lease_authority_revision,
            expected_host_revision: status.host_revision,
            evidence: ManifoldLocalControllerEvidence {
                schema_id: schema(LOCAL_CONTROL_CONTROLLER_EVIDENCE_SCHEMA),
                evidence_id: derived_external_id("evidence.local.admission", external_request_id),
                adapter_id: id(ADAPTER_ID),
                window_id,
                controller_id: id(CONTROLLER_ID),
                presentation: match access_mode {
                    ManifoldLocalControlAccessMode::Paired => {
                        ManifoldLocalControlPairingPresentation::ManualEntry
                    }
                    ManifoldLocalControlAccessMode::OpenLanInsecure => {
                        ManifoldLocalControlPairingPresentation::OpenLanInsecure
                    }
                },
                pairing_code_verified: access_mode == ManifoldLocalControlAccessMode::Paired,
                observed_at_ms: wall_ms,
                expires_at_ms: window_expires_at_ms,
            },
            requested_at_ms: wall_ms,
            requested_session_ttl_ms: MAX_SESSION_MS,
        };
        let mut entropy = [0_u8; 32];
        getrandom(&mut entropy).map_err(|_| "secure_random_unavailable".to_owned())?;
        let next_clock = self.next_clock(wall_ms);
        let receipt = self
            .authority
            .admit_controller(&request, entropy, next_clock);
        entropy.fill(0);
        self.token_id = receipt
            .admission
            .as_ref()
            .and_then(|item| item.token.as_ref())
            .map(|token| token.token_id.clone());
        to_json(&receipt)
    }

    pub fn accept_command_json(
        &mut self,
        external_request_id: &str,
        command: &str,
        video_id: Option<&str>,
        expected_local_revision: u64,
    ) -> Result<String, String> {
        let wall_ms = self.trusted_now_ms()?;
        let revisions = self.authority.revision_tuple();
        let token_id = self
            .token_id
            .clone()
            .ok_or_else(|| "controller_not_admitted".to_owned())?;
        let command_id = command_id(command)?;
        let params_digest = typed_params(command, video_id)?;
        let expected_local_revision = Revision::new(expected_local_revision)
            .ok_or_else(|| "invalid_expected_authority_revision".to_owned())?;
        if expected_local_revision != revisions.local_revision {
            return Err("stale_authority_revision".to_owned());
        }
        let request = ManifoldLocalControlCommandRequest {
            schema_id: schema(LOCAL_CONTROL_COMMAND_REQUEST_SCHEMA),
            request_id: derived_external_id("request.local.command", external_request_id),
            expected_local_revision,
            expected_admission_revision: revisions.admission_revision,
            expected_host_revision: revisions.host_revision,
            token_id,
            command_id,
            params_digest,
            issued_at_ms: wall_ms,
            expires_at_ms: wall_ms.saturating_add(5_000),
        };
        let receipt = self.authority.accept_command(&request, wall_ms);
        to_json(&receipt)
    }

    pub fn disable_json(
        &mut self,
        external_request_id: &str,
        cause: &str,
    ) -> Result<String, String> {
        let wall_ms = self.trusted_now_ms()?;
        let status = self.authority.safe_status();
        let request = ManifoldLocalControlDisableRequest {
            schema_id: schema(LOCAL_CONTROL_DISABLE_REQUEST_SCHEMA),
            request_id: derived_external_id("request.local.disable", external_request_id),
            expected_local_revision: status.local_revision,
            expected_admission_revision: status.admission_revision,
            expected_lease_authority_revision: status.lease_authority_revision,
            expected_host_revision: status.host_revision,
            reason: bounded_reason(cause)?,
            requested_at_ms: wall_ms,
            evidence_id: derived_external_id("evidence.wearer.disable", external_request_id),
        };
        let next_clock = self.next_clock(wall_ms);
        let receipt = self.authority.disable(&request, next_clock);
        if receipt.disabled {
            self.token_id = None;
        }
        to_json(&receipt)
    }

    pub fn enforce_expiry_json(&mut self, external_request_id: &str) -> Result<String, String> {
        let wall_ms = self.trusted_now_ms()?;
        let status = self.authority.safe_status();
        let outcome = match status.state {
            ManifoldLocalControlState::Disabled => ExpiryBridgeOutcome::not_due(status),
            ManifoldLocalControlState::PairingWindowOpen => {
                if status
                    .window_expires_at_ms
                    .is_some_and(|deadline| wall_ms >= deadline)
                {
                    let request_id =
                        derived_external_id("request.local.disable", external_request_id);
                    let request = ManifoldLocalControlDisableRequest {
                        schema_id: schema(LOCAL_CONTROL_DISABLE_REQUEST_SCHEMA),
                        request_id,
                        expected_local_revision: status.local_revision,
                        expected_admission_revision: status.admission_revision,
                        expected_lease_authority_revision: status.lease_authority_revision,
                        expected_host_revision: status.host_revision,
                        reason: id("reason.local_control.pairing_window_expired"),
                        requested_at_ms: wall_ms,
                        evidence_id: derived_external_id(
                            "evidence.timer.window",
                            external_request_id,
                        ),
                    };
                    let next_clock = self.next_clock(wall_ms);
                    let receipt = self.authority.disable(&request, next_clock);
                    ExpiryBridgeOutcome::window(receipt, self.authority.safe_status())
                } else {
                    ExpiryBridgeOutcome::not_due(status)
                }
            }
            ManifoldLocalControlState::ControllerActive => {
                let expired = status
                    .session_expires_at_ms
                    .is_some_and(|deadline| wall_ms >= deadline)
                    || status
                        .idle_expires_at_ms
                        .is_some_and(|deadline| wall_ms >= deadline);
                if expired {
                    let request = ManifoldLocalControlExpiryRequest {
                        schema_id: schema(LOCAL_CONTROL_EXPIRY_REQUEST_SCHEMA),
                        request_id: derived_external_id(
                            "request.local.expiry",
                            external_request_id,
                        ),
                        expected_local_revision: status.local_revision,
                        expected_admission_revision: status.admission_revision,
                        expected_lease_authority_revision: status.lease_authority_revision,
                        expected_host_revision: status.host_revision,
                        requested_at_ms: wall_ms,
                        evidence_id: derived_external_id(
                            "evidence.timer.expiry",
                            external_request_id,
                        ),
                    };
                    let next_clock = self.next_clock(wall_ms);
                    let receipt = self.authority.expire_controller(&request, next_clock);
                    if receipt.expired {
                        self.token_id = None;
                    }
                    ExpiryBridgeOutcome::controller(receipt, self.authority.safe_status())
                } else {
                    ExpiryBridgeOutcome::not_due(status)
                }
            }
        };
        to_json(&outcome)
    }

    fn trusted_now_ms(&mut self) -> Result<u64, String> {
        let observed = system_wall_ms()?;
        if observed < self.last_wall_ms {
            self.wall_clock_adjustment_count = self.wall_clock_adjustment_count.saturating_add(1);
            Ok(self.last_wall_ms)
        } else {
            self.last_wall_ms = observed;
            Ok(observed)
        }
    }

    fn next_clock(&mut self, wall_ms: u64) -> ManifoldClockSnapshot {
        self.clock_sequence = self.clock_sequence.saturating_add(1);
        let observed_monotonic_ns = self
            .clock_started
            .elapsed()
            .as_nanos()
            .min(u128::from(u64::MAX)) as u64;
        self.last_monotonic_ns =
            observed_monotonic_ns.max(self.last_monotonic_ns.saturating_add(1));
        let health = if self.wall_clock_adjustment_count == 0 {
            ClockHealth::Healthy
        } else {
            ClockHealth::Degraded
        };
        clock(
            self.clock_sequence,
            wall_ms,
            self.last_monotonic_ns,
            self.wall_clock_adjustment_count,
            health,
        )
    }
}

#[derive(Serialize)]
struct OpenWindowBridgeReceipt {
    window_receipt: ManifoldLocalControlWindowReceipt,
    wearer_evidence_id: DottedId,
}

#[derive(Serialize)]
struct ExpiryBridgeOutcome {
    due: bool,
    enforced: bool,
    expired: bool,
    cause: Option<&'static str>,
    reason: String,
    expiry_receipt: Option<ManifoldLocalControlExpiryReceipt>,
    disable_receipt: Option<ManifoldLocalControlDisableReceipt>,
    status: ManifoldLocalControlSafeStatus,
}

impl ExpiryBridgeOutcome {
    fn not_due(status: ManifoldLocalControlSafeStatus) -> Self {
        Self {
            due: false,
            enforced: false,
            expired: false,
            cause: None,
            reason: "not_due".to_owned(),
            expiry_receipt: None,
            disable_receipt: None,
            status,
        }
    }

    fn window(
        receipt: ManifoldLocalControlDisableReceipt,
        status: ManifoldLocalControlSafeStatus,
    ) -> Self {
        let enforced = receipt.disabled;
        let reason = if enforced {
            "pairing_window_expired".to_owned()
        } else {
            rejection_text(receipt.rejection_reason.as_ref())
        };
        Self {
            due: true,
            enforced,
            expired: false,
            cause: Some("pairing_window_expired"),
            reason,
            expiry_receipt: None,
            disable_receipt: Some(receipt),
            status,
        }
    }

    fn controller(
        receipt: ManifoldLocalControlExpiryReceipt,
        status: ManifoldLocalControlSafeStatus,
    ) -> Self {
        let enforced = receipt.expired;
        let reason = if enforced {
            "controller_expired".to_owned()
        } else {
            rejection_text(receipt.rejection_reason.as_ref())
        };
        Self {
            due: true,
            enforced,
            expired: enforced,
            cause: Some("controller_expired"),
            reason,
            expiry_receipt: Some(receipt),
            disable_receipt: None,
            status,
        }
    }
}

fn policy(
    platform_subject: &str,
    signing_fingerprint: &str,
    allow_debug_shell_operator: bool,
) -> Result<ManifoldLocalControlPolicy, String> {
    Ok(ManifoldLocalControlPolicy {
        schema_id: schema(LOCAL_CONTROL_POLICY_SCHEMA),
        authority_id: id(AUTHORITY_ID),
        trusted_adapter_id: id(ADAPTER_ID),
        adapter_identity: ManifoldClientIdentity {
            client_id: id(ADAPTER_ID),
            platform_subject: platform_subject.to_owned(),
            signing_fingerprint: signing_fingerprint.to_owned(),
        },
        controller_id: id(CONTROLLER_ID),
        controller_lease_scope: id(CONTROLLER_SCOPE),
        controller_lease_capability_id: id(CONTROLLER_CAPABILITY),
        commands: commands(),
        max_window_ttl_ms: MAX_WINDOW_MS,
        max_session_ttl_ms: MAX_SESSION_MS,
        idle_timeout_ms: IDLE_MS,
        rate_window_ms: RATE_WINDOW_MS,
        max_commands_per_window: RATE_LIMIT,
        allow_debug_shell_operator,
    })
}

fn commands() -> Vec<ManifoldLocalControlCommandDescriptor> {
    [
        ("describe", SafetyClass::ReadOnly, false, false),
        ("get_state", SafetyClass::ReadOnly, false, false),
        ("list_videos", SafetyClass::ReadOnly, false, false),
        ("pause", SafetyClass::BoundedMutation, true, false),
        ("play", SafetyClass::BoundedMutation, true, false),
        ("select_video", SafetyClass::BoundedMutation, true, true),
    ]
    .into_iter()
    .map(
        |(name, safety_class, requires_lease, has_params)| ManifoldLocalControlCommandDescriptor {
            command_id: id(&format!("command.local.{name}")),
            capability_id: id(&format!("capability.local.{name}")),
            required_lease_scope: requires_lease.then(|| id(CONTROLLER_SCOPE)),
            params_type_id: has_params.then(|| id("params.local.video_selection")),
            safety_class,
        },
    )
    .collect()
}

fn command_capabilities(policy: &ManifoldLocalControlPolicy) -> Vec<DottedId> {
    let mut values = policy
        .commands
        .iter()
        .map(|command| command.capability_id.clone())
        .collect::<BTreeSet<_>>();
    values.insert(policy.controller_lease_capability_id.clone());
    values.into_iter().collect()
}

fn admission(policy: &ManifoldLocalControlPolicy) -> Result<ManifoldAdmissionAuthority, String> {
    ManifoldAdmissionAuthority::from_snapshot(ManifoldAdmissionSnapshot {
        schema_id: schema(ADMISSION_SNAPSHOT_SCHEMA),
        authority_id: id("authority.quest.local_control.admission"),
        authority_revision: Revision::INITIAL,
        grants: vec![ManifoldAdmissionGrant {
            grant_id: id("grant.quest.local_controller"),
            client_lock_id: id("lock.quest.local_control.registry"),
            client_lock_fingerprint: registry_fingerprint(policy),
            identity: policy.adapter_identity.clone(),
            capabilities: command_capabilities(policy),
            expires_at_ms: i64::MAX as u64,
            revoked: false,
        }],
        active_tokens: Vec::new(),
        revoked_token_ids: Vec::new(),
        consumed_request_ids: Vec::new(),
        consumed_use_request_ids: Vec::new(),
        reviewed_sweep_ids: Vec::new(),
        audit_events: Vec::new(),
        max_token_ttl_ms: policy.max_session_ttl_ms,
    })
    .map_err(|error| error.to_string())
}

fn lease_authority(
    policy: &ManifoldLocalControlPolicy,
    initial_clock: ManifoldClockSnapshot,
) -> ManifoldAuthoritySnapshot {
    let command_descriptors = policy
        .commands
        .iter()
        .map(|command| ManifoldCommandDescriptor {
            schema_id: schema("rusty.manifold.command.descriptor.v1"),
            command_id: command.command_id.clone(),
            target_scope: id("target.quest.video_player"),
            input_schema: schema("rusty.manifold.command.input.local_control.v1"),
            required_capability: command.capability_id.clone(),
            required_lease_scope: command.required_lease_scope.clone(),
            safety_class: command.safety_class,
            operator_confirmation_required: false,
        })
        .collect::<Vec<_>>();
    ManifoldAuthoritySnapshot {
        schema_id: schema("rusty.manifold.authority.snapshot.v2"),
        authority_id: policy.authority_id.clone(),
        authority_revision: Revision::INITIAL,
        host_manifest: ManifoldHostManifest {
            schema_id: schema("rusty.manifold.host.manifest.v1"),
            host_id: id(HOST_ID),
            authority_role: AuthorityRole::Primary,
            host_category: Some(id("host.quest.local_control")),
            clock_domain: id("clock.quest.monotonic"),
            endpoints: vec![EndpointDescriptor {
                endpoint_id: id("endpoint.local_control.in_process"),
                visibility: EndpointVisibility::Loopback,
                transport: EndpointTransport::InProcess,
                security: EndpointSecurity::LocalProcess,
            }],
            capabilities: command_capabilities(policy),
            supported_backends: Vec::new(),
            permissions: Vec::new(),
            lifecycle_limits: Vec::new(),
            missing_requirements: Vec::new(),
        },
        clock_snapshot: initial_clock,
        stream_registry: ManifoldStreamRegistrySnapshot {
            schema_id: schema("rusty.manifold.stream.registry_snapshot.v1"),
            registry_revision: Revision::INITIAL,
            streams: Vec::new(),
        },
        module_runtime_states: Vec::new(),
        command_ids: policy
            .commands
            .iter()
            .map(|command| command.command_id.clone())
            .collect(),
        command_descriptors,
        active_leases: Vec::new(),
        revoked_control_lease_tombstones: Vec::new(),
        active_stream_subscriptions: Vec::new(),
    }
}

fn runtime_host(policy: &ManifoldLocalControlPolicy) -> Result<ManifoldRuntimeHost, String> {
    ManifoldRuntimeHost::from_snapshot(ManifoldRuntimeHostSnapshot {
        schema_id: schema(HOST_SNAPSHOT_SCHEMA),
        host_id: id(HOST_ID),
        authority_revision: Revision::INITIAL,
        commands: policy
            .commands
            .iter()
            .map(|command| ManifoldRuntimeCommandDescriptor {
                command_id: command.command_id.clone(),
                required_lease_scope: command.required_lease_scope.clone(),
            })
            .collect(),
        leases: Vec::new(),
        applied_request_ids: Vec::new(),
        reviewed_sweep_ids: Vec::new(),
        reviewed_control_lease_adoption_ids: Vec::new(),
        reviewed_derivative_lease_revocation_ids: Vec::new(),
        audit_events: Vec::new(),
    })
    .map_err(|error| error.to_string())
}

fn typed_params(
    command: &str,
    video_id: Option<&str>,
) -> Result<Option<ManifoldRuntimeTypedParamsDigest>, String> {
    match (command, video_id) {
        ("select_video", Some(video_id))
            if video_id.len() >= 2
                && video_id.len() <= 48
                && video_id.bytes().all(|value| {
                    value.is_ascii_lowercase() || value.is_ascii_digit() || value == b'-'
                }) =>
        {
            let canonical = format!("{{\"video_id\":\"{video_id}\"}}");
            Ok(Some(ManifoldRuntimeTypedParamsDigest {
                schema_id: schema("rusty.manifold.runtime_host.typed_params_digest.v1"),
                params_type_id: id("params.local.video_selection"),
                canonical_sha256: sha256_label(canonical.as_bytes()),
                canonical_size_bytes: canonical.len() as u32,
            }))
        }
        ("select_video", _) => Err("invalid_video_selection".to_owned()),
        (_, None) => Ok(None),
        (_, Some(_)) => Err("unexpected_command_payload".to_owned()),
    }
}

fn command_id(command: &str) -> Result<DottedId, String> {
    match command {
        "describe" | "get_state" | "list_videos" | "pause" | "play" | "select_video" => {
            Ok(id(&format!("command.local.{command}")))
        }
        _ => Err("command_not_registered".to_owned()),
    }
}

fn bounded_reason(cause: &str) -> Result<DottedId, String> {
    if cause.is_empty()
        || cause.len() > 64
        || !cause
            .bytes()
            .all(|value| value.is_ascii_lowercase() || value.is_ascii_digit() || value == b'_')
        || !cause.as_bytes()[0].is_ascii_alphanumeric()
        || !cause.as_bytes()[cause.len() - 1].is_ascii_alphanumeric()
    {
        return Err("invalid_disable_cause".to_owned());
    }
    DottedId::new(format!("reason.local_control.{cause}"))
        .map_err(|_| "invalid_disable_cause".to_owned())
}

fn registry_fingerprint(policy: &ManifoldLocalControlPolicy) -> String {
    let canonical = policy
        .commands
        .iter()
        .map(|command| {
            format!(
                "{}|{}|{}|{}",
                command.command_id,
                command.capability_id,
                command
                    .required_lease_scope
                    .as_ref()
                    .map_or("-", DottedId::as_str),
                command
                    .params_type_id
                    .as_ref()
                    .map_or("-", DottedId::as_str)
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    sha256_label(canonical.as_bytes())
}

fn validate_platform_identity(
    platform_subject: &str,
    signing_fingerprint: &str,
) -> Result<(), String> {
    if platform_subject.is_empty()
        || platform_subject.len() > 255
        || !platform_subject.bytes().all(|value| {
            value.is_ascii_lowercase() || value.is_ascii_digit() || value == b'.' || value == b'_'
        })
    {
        return Err("invalid_platform_subject".to_owned());
    }
    let Some(hex) = signing_fingerprint.strip_prefix("sha256:") else {
        return Err("invalid_signing_fingerprint".to_owned());
    };
    if hex.len() != 64
        || !hex
            .bytes()
            .all(|value| value.is_ascii_digit() || (b'a'..=b'f').contains(&value))
    {
        return Err("invalid_signing_fingerprint".to_owned());
    }
    Ok(())
}

fn clock(
    sequence: u64,
    wall_ms: u64,
    monotonic_ns: u64,
    wall_clock_adjustment_count: u32,
    health: ClockHealth,
) -> ManifoldClockSnapshot {
    ManifoldClockSnapshot {
        schema_id: schema("rusty.manifold.clock.snapshot.v1"),
        clock_domain: id("clock.quest.monotonic"),
        clock_epoch_id: id("clock_epoch.quest.local_control"),
        sequence,
        monotonic_elapsed_ns: monotonic_ns,
        wall_unix_ms: i64::try_from(wall_ms).unwrap_or(i64::MAX),
        read_uncertainty_ns: 1_000_000,
        health,
        wall_clock_adjustment_count: u64::from(wall_clock_adjustment_count),
    }
}

fn system_wall_ms() -> Result<u64, String> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "trusted_wall_clock_before_epoch".to_owned())?;
    u64::try_from(duration.as_millis()).map_err(|_| "trusted_wall_clock_out_of_range".to_owned())
}

fn derived_external_id(prefix: &str, external: &str) -> DottedId {
    let digest = Sha256::digest(external.as_bytes());
    id(&format!("{prefix}.{}", hex::encode(&digest[..16])))
}

fn sha256_label(bytes: &[u8]) -> String {
    format!("sha256:{}", hex::encode(Sha256::digest(bytes)))
}

fn id(value: &str) -> DottedId {
    DottedId::new(value).expect("static identifiers and hex-derived suffixes are valid")
}

fn schema(value: &str) -> SchemaId {
    SchemaId::new(value).expect("static schema identifiers are valid")
}

fn to_json<T: Serialize>(value: &T) -> Result<String, String> {
    serde_json::to_string(value).map_err(|_| "receipt_serialization_failed".to_owned())
}

fn rejection_text<T: Serialize>(value: Option<&T>) -> String {
    value
        .and_then(|item| serde_json::to_value(item).ok())
        .and_then(|item| item.as_str().map(ToOwned::to_owned))
        .unwrap_or_else(|| "authority_rejected".to_owned())
}

#[cfg(target_os = "android")]
static ENGINE: OnceLock<Mutex<Option<LocalControlEngine>>> = OnceLock::new();

#[cfg(target_os = "android")]
fn with_engine<T>(
    operation: impl FnOnce(&mut LocalControlEngine) -> Result<T, String>,
) -> Result<T, String> {
    let mut guard = ENGINE
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| "native_authority_lock_poisoned".to_owned())?;
    let engine = guard
        .as_mut()
        .ok_or_else(|| "native_authority_not_initialized".to_owned())?;
    operation(engine)
}

#[cfg(target_os = "android")]
mod android_jni {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jboolean, jlong, jstring};
    use jni::JNIEnv;
    use serde_json::json;
    use std::panic::{catch_unwind, AssertUnwindSafe};
    use std::ptr;

    fn java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
        env.get_string(&value)
            .map(|value| value.into())
            .map_err(|_| "invalid_jni_string".to_owned())
    }

    fn result_string(env: &mut JNIEnv<'_>, result: Result<String, String>) -> jstring {
        let payload = result.unwrap_or_else(|reason| {
            json!({
                "bridge_error": reason,
            })
            .to_string()
        });
        env.new_string(payload)
            .map(JString::into_raw)
            .unwrap_or(ptr::null_mut())
    }

    fn guarded(operation: impl FnOnce() -> Result<String, String>) -> Result<String, String> {
        catch_unwind(AssertUnwindSafe(operation))
            .unwrap_or_else(|_| Err("native_bridge_panic".to_owned()))
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1video_1control_NativeManifoldBridge_nativeInitialize(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        platform_subject: JString<'_>,
        signing_fingerprint: JString<'_>,
        allow_debug_shell_operator: jboolean,
    ) -> jstring {
        let result = guarded(|| {
            let platform_subject = java_string(&mut env, platform_subject)?;
            let signing_fingerprint = java_string(&mut env, signing_fingerprint)?;
            let mut guard = ENGINE
                .get_or_init(|| Mutex::new(None))
                .lock()
                .map_err(|_| "native_authority_lock_poisoned".to_owned())?;
            if let Some(engine) = guard.as_mut() {
                if !engine.matches_identity(
                    &platform_subject,
                    &signing_fingerprint,
                    allow_debug_shell_operator != 0,
                ) {
                    return Err("native_authority_identity_changed".to_owned());
                }
                return engine.safe_status_json();
            }
            let engine = LocalControlEngine::new(
                &platform_subject,
                &signing_fingerprint,
                allow_debug_shell_operator != 0,
            )?;
            let status = engine.safe_status_json()?;
            *guard = Some(engine);
            Ok(status)
        });
        result_string(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1video_1control_NativeManifoldBridge_nativeOpenPairingWindow(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        request_id: JString<'_>,
        requested_window_ms: jlong,
        access_mode: JString<'_>,
        enable_actor: JString<'_>,
    ) -> jstring {
        let result = guarded(|| {
            let request_id = java_string(&mut env, request_id)?;
            let access_mode = java_string(&mut env, access_mode)?;
            let enable_actor = java_string(&mut env, enable_actor)?;
            let requested_window_ms = u64::try_from(requested_window_ms)
                .map_err(|_| "invalid_window_lifetime".to_owned())?;
            with_engine(|engine| {
                engine.open_window_json(
                    &request_id,
                    requested_window_ms,
                    &access_mode,
                    &enable_actor,
                )
            })
        });
        result_string(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1video_1control_NativeManifoldBridge_nativeAdmitController(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        request_id: JString<'_>,
    ) -> jstring {
        let result = guarded(|| {
            let request_id = java_string(&mut env, request_id)?;
            with_engine(|engine| engine.admit_json(&request_id))
        });
        result_string(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1video_1control_NativeManifoldBridge_nativeAcceptCommand(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        request_id: JString<'_>,
        command: JString<'_>,
        video_id: JString<'_>,
        expected_local_revision: jlong,
    ) -> jstring {
        let result = guarded(|| {
            let request_id = java_string(&mut env, request_id)?;
            let command = java_string(&mut env, command)?;
            let video_id = java_string(&mut env, video_id)?;
            let expected_local_revision = u64::try_from(expected_local_revision)
                .map_err(|_| "invalid_expected_authority_revision".to_owned())?;
            with_engine(|engine| {
                engine.accept_command_json(
                    &request_id,
                    &command,
                    (!video_id.is_empty()).then_some(video_id.as_str()),
                    expected_local_revision,
                )
            })
        });
        result_string(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1video_1control_NativeManifoldBridge_nativeDisable(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        request_id: JString<'_>,
        cause: JString<'_>,
    ) -> jstring {
        let result = guarded(|| {
            let request_id = java_string(&mut env, request_id)?;
            let cause = java_string(&mut env, cause)?;
            with_engine(|engine| engine.disable_json(&request_id, &cause))
        });
        result_string(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1video_1control_NativeManifoldBridge_nativeEnforceExpiry(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        request_id: JString<'_>,
    ) -> jstring {
        let result = guarded(|| {
            let request_id = java_string(&mut env, request_id)?;
            with_engine(|engine| engine.enforce_expiry_json(&request_id))
        });
        result_string(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1video_1control_NativeManifoldBridge_nativeSafeStatus(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
    ) -> jstring {
        let result = guarded(|| with_engine(|engine| engine.safe_status_json()));
        result_string(&mut env, result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusty_manifold_local_control::{
        ManifoldLocalControlAdmissionReceipt, ManifoldLocalControlCommandReceipt,
    };
    use serde_json::Value;

    fn engine() -> LocalControlEngine {
        LocalControlEngine::new(
            "io.github.mesmerprism.rustyquest.spatial_video_control_example",
            &format!("sha256:{}", "a1".repeat(32)),
            true,
        )
        .expect("engine")
    }

    #[test]
    fn exact_six_command_registry_is_sorted_and_closed() {
        let engine = engine();
        let ids = engine
            .authority
            .policy()
            .commands
            .iter()
            .map(|command| command.command_id.as_str())
            .collect::<Vec<_>>();
        assert_eq!(
            ids,
            vec![
                "command.local.describe",
                "command.local.get_state",
                "command.local.list_videos",
                "command.local.pause",
                "command.local.play",
                "command.local.select_video",
            ]
        );
        assert!(command_id("raw_shell").is_err());
    }

    #[test]
    fn open_admit_accept_and_disable_preserve_composite_receipts() {
        let mut engine = engine();
        let open: Value = serde_json::from_str(
            &engine
                .open_window_json("browser-open-0001", 60_000, "paired", "wearer")
                .expect("open"),
        )
        .expect("open json");
        assert_eq!(open["window_receipt"]["opened"], true);
        let admission: ManifoldLocalControlAdmissionReceipt =
            serde_json::from_str(&engine.admit_json("browser-pair-0001").expect("admit"))
                .expect("admission receipt");
        assert!(admission.admitted);
        let status: ManifoldLocalControlSafeStatus =
            serde_json::from_str(&engine.safe_status_json().expect("status")).expect("status json");
        let command: ManifoldLocalControlCommandReceipt = serde_json::from_str(
            &engine
                .accept_command_json(
                    "browser-command-0001",
                    "select_video",
                    Some("synthetic-grid"),
                    status.local_revision.get(),
                )
                .expect("command"),
        )
        .expect("command receipt");
        assert!(command.command_accepted, "{command:#?}");
        assert!(!command.proves_application_effect);
        let disabled: ManifoldLocalControlDisableReceipt = serde_json::from_str(
            &engine
                .disable_json("wearer-disable-0001", "wearer_revoke")
                .expect("disable"),
        )
        .expect("disable receipt");
        assert!(disabled.disabled);
        assert!(disabled.revocation.is_some());
    }

    #[test]
    fn listener_failure_can_disable_before_admission() {
        let mut engine = engine();
        engine
            .open_window_json("browser-open-0002", 60_000, "paired", "wearer")
            .expect("open");
        let disabled: ManifoldLocalControlDisableReceipt = serde_json::from_str(
            &engine
                .disable_json("listener-failure-0001", "listener_start_failed")
                .expect("disable"),
        )
        .expect("receipt");
        assert!(disabled.disabled);
        assert!(disabled.revocation.is_none());
    }

    #[test]
    fn safe_status_never_serializes_identity_or_bearer_material() {
        let engine = engine();
        let status = engine.safe_status_json().expect("status");
        assert!(!status.contains("signing_fingerprint"));
        assert!(!status.contains("token_id"));
        assert!(!status.contains("pairing_code"));
    }
}
