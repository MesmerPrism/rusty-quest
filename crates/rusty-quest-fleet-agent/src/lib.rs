//! Permission-minimal Quest observation production for Rusty Fleet.
//!
//! This crate owns the Quest-side projection and signing boundary. It does not
//! enroll a device, accept Manifold state, listen for commands, discover a Hub,
//! inspect arbitrary foreground applications, or create an ADB dependency.

use std::collections::BTreeMap;

use ed25519_dalek::{Signer, SigningKey};
use fleet_contracts::{
    ApplicationLifecycle, ApplicationObservation, AuthorizationState, CapabilitySnapshot,
    CapabilityState, DeviceIdentity, DeviceObservation, EnablementState, FactProvenance,
    FleetCheckInClaims, ForegroundAuthority, ForegroundState, FreshnessState, KioskState,
    PowerObservation, ReachabilityState, SignedFleetCheckIn, SupportState, ValidateContract,
    CHECKIN_SIGNATURE_ALGORITHM,
};
use rusty_manifold_model::{DottedId, Revision, SchemaId};
use rusty_manifold_peer::{
    ManifoldPeerAvailability, ManifoldPeerIdentity, ManifoldPeerPayloadClass, ManifoldPeerRole,
    ManifoldPeerStatus, ManifoldPeerStatusProposal, PEER_IDENTITY_SCHEMA, PEER_PROPOSAL_SCHEMA,
    PEER_STATUS_SCHEMA,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Stable Android package identity for the first Fleet Agent application.
pub const FLEET_AGENT_PACKAGE: &str = "io.github.mesmerprism.rustyquest.fleetagent";

/// Stable Quest adapter identity carried by provenance-bearing facts.
pub const FLEET_AGENT_ADAPTER_ID: &str = "quest-fleet-agent";

/// Stable Quest repository owner recorded on facts.
pub const FLEET_AGENT_OWNER: &str = "rusty-quest";

/// Explicit product profile. `enabled=false` is the inert default.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestFleetAgentProfile {
    /// Profile schema.
    pub schema: String,
    /// Exact opt-in flag.
    pub enabled: bool,
    /// Fleet device and Manifold peer id.
    pub device_id: String,
    /// Human-readable device name.
    pub display_name: String,
    /// Quest product model.
    pub model: String,
    /// Stable hardware class.
    pub hardware_class: String,
    /// Accepted enrollment identity revision expected by Fleet.
    pub identity_revision: u64,
    /// Current Manifold authority revision expected by the producer.
    pub expected_authority_revision: u64,
    /// Monotonic Quest status/source revision.
    pub source_revision: u64,
    /// Quest producer epoch. A service/process restart creates a new epoch.
    pub source_epoch: String,
    /// Signing key identifier resolved from app-private configuration.
    pub key_id: String,
    /// Expected SHA-256 fingerprint of the signing public key.
    pub key_fingerprint: String,
    /// Manifold trust domain.
    pub trust_domain: String,
    /// Check-in validity duration.
    pub checkin_ttl_ms: u64,
    /// Low-rate publication interval.
    pub checkin_interval_ms: u64,
    /// Explicit Hub endpoint; no discovery fallback exists.
    pub hub_endpoint: String,
    /// Operator-safe device tags.
    #[serde(default)]
    pub tags: BTreeMap<String, String>,
}

/// Quest-owned platform observations captured at one source time.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestPlatformSnapshot {
    /// Battery level from Android's battery service.
    pub battery_percent: u8,
    /// Whether Android reports an active charging source.
    pub charging: bool,
    /// Fleet Agent's own lifecycle.
    pub agent_lifecycle: ApplicationLifecycle,
    /// Optional observation from an explicitly participating application.
    pub participating_application: Option<ParticipatingApplicationObservation>,
}

/// Application evidence supplied by an explicitly participating application.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ParticipatingApplicationObservation {
    /// Exact Android package identity.
    pub package_name: String,
    /// Participating application lifecycle.
    pub lifecycle: ApplicationLifecycle,
    /// Participating application foreground state.
    pub foreground_state: ForegroundState,
    /// App-owned kiosk state.
    pub kiosk_state: KioskState,
    /// Whether the app's separately authenticated control route is ready.
    pub control_ready: bool,
}

/// Public identity corresponding to an app-private signing seed.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestFleetAgentKeyRecord {
    /// Key record schema.
    pub schema: String,
    /// Profile key id.
    pub key_id: String,
    /// Raw public key as lowercase hexadecimal.
    pub public_key_hex: String,
    /// Manifold-compatible SHA-256 fingerprint.
    pub key_fingerprint: String,
}

/// A stable, machine-readable Quest producer failure.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestFleetAgentError {
    /// Stable error code.
    pub code: String,
    /// Human-readable detail safe for local diagnostics.
    pub message: String,
}

impl QuestFleetAgentError {
    fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.to_owned(),
            message: message.into(),
        }
    }
}

/// Derives the public enrollment record without exposing the signing seed.
#[must_use]
pub fn derive_key_record(key_id: &str, private_seed: &[u8; 32]) -> QuestFleetAgentKeyRecord {
    let signing_key = SigningKey::from_bytes(private_seed);
    let public_key = signing_key.verifying_key().to_bytes();
    QuestFleetAgentKeyRecord {
        schema: "rusty.quest.fleet_agent_key_record.v1".to_owned(),
        key_id: key_id.to_owned(),
        public_key_hex: lowercase_hex(&public_key),
        key_fingerprint: format!("fingerprint.{}", lowercase_hex(&Sha256::digest(public_key))),
    }
}

/// Builds and signs one low-rate check-in from a current Quest snapshot.
///
/// # Errors
///
/// Returns a stable producer error when the opt-in profile, source facts,
/// signing identity, Manifold proposal, or Fleet contract is invalid.
pub fn produce_signed_checkin(
    profile: &QuestFleetAgentProfile,
    snapshot: &QuestPlatformSnapshot,
    private_seed: &[u8; 32],
    issued_at_ms: i64,
) -> Result<SignedFleetCheckIn, QuestFleetAgentError> {
    validate_profile(profile)?;
    validate_snapshot(snapshot)?;
    if issued_at_ms < 0 {
        return Err(QuestFleetAgentError::new(
            "invalid_source_time",
            "issued_at_ms must be non-negative",
        ));
    }

    let key_record = derive_key_record(&profile.key_id, private_seed);
    if key_record.key_fingerprint != profile.key_fingerprint {
        return Err(QuestFleetAgentError::new(
            "signing_identity_mismatch",
            "profile fingerprint does not match the app-private signing key",
        ));
    }

    let ttl_ms = i64::try_from(profile.checkin_ttl_ms).map_err(|_| {
        QuestFleetAgentError::new("invalid_ttl", "check-in TTL cannot be represented")
    })?;
    let expires_at_ms = issued_at_ms
        .checked_add(ttl_ms)
        .ok_or_else(|| QuestFleetAgentError::new("invalid_ttl", "check-in expiry overflowed"))?;
    let proposal = manifold_proposal(profile, snapshot, issued_at_ms, expires_at_ms)?;
    let claims = FleetCheckInClaims {
        schema: "rusty.fleet.checkin_claims.v1".to_owned(),
        checkin_id: format!("checkin.{}.{}", profile.device_id, profile.source_revision),
        issued_at_ms,
        expires_at_ms,
        manifold_peer_status_proposal: serde_json::to_value(proposal).map_err(|error| {
            QuestFleetAgentError::new("proposal_serialization_failed", error.to_string())
        })?,
        observation: device_observation(profile, snapshot, issued_at_ms, expires_at_ms),
        extensions: BTreeMap::new(),
    };
    claims.validate().map_err(contract_error)?;
    let signing_bytes = claims
        .signing_bytes()
        .map_err(|error| QuestFleetAgentError::new("canonicalization_failed", error.to_string()))?;
    let signature = SigningKey::from_bytes(private_seed).sign(&signing_bytes);
    let envelope = SignedFleetCheckIn {
        schema: "rusty.fleet.signed_checkin.v1".to_owned(),
        key_id: profile.key_id.clone(),
        algorithm: CHECKIN_SIGNATURE_ALGORITHM.to_owned(),
        signature_hex: lowercase_hex(&signature.to_bytes()),
        claims,
    };
    envelope.validate().map_err(contract_error)?;
    Ok(envelope)
}

fn validate_profile(profile: &QuestFleetAgentProfile) -> Result<(), QuestFleetAgentError> {
    if profile.schema != "rusty.quest.fleet_agent_profile.v1" {
        return Err(QuestFleetAgentError::new(
            "wrong_profile_schema",
            "expected rusty.quest.fleet_agent_profile.v1",
        ));
    }
    if !profile.enabled {
        return Err(QuestFleetAgentError::new(
            "agent_disabled",
            "Fleet Agent profile is inert until explicitly enabled",
        ));
    }
    for (name, value) in [
        ("device_id", profile.device_id.as_str()),
        ("display_name", profile.display_name.as_str()),
        ("model", profile.model.as_str()),
        ("hardware_class", profile.hardware_class.as_str()),
        ("source_epoch", profile.source_epoch.as_str()),
        ("key_id", profile.key_id.as_str()),
        ("key_fingerprint", profile.key_fingerprint.as_str()),
        ("trust_domain", profile.trust_domain.as_str()),
        ("hub_endpoint", profile.hub_endpoint.as_str()),
    ] {
        if value.trim().is_empty() {
            return Err(QuestFleetAgentError::new(
                "required_profile_value",
                format!("{name} must not be empty"),
            ));
        }
    }
    dotted(&profile.device_id)?;
    dotted(&profile.key_id)?;
    dotted(&profile.key_fingerprint)?;
    dotted(&profile.trust_domain)?;
    if profile.identity_revision == 0
        || profile.expected_authority_revision == 0
        || profile.source_revision == 0
    {
        return Err(QuestFleetAgentError::new(
            "invalid_revision",
            "identity, authority, and source revisions must be greater than zero",
        ));
    }
    if !(10_000..=300_000).contains(&profile.checkin_ttl_ms) {
        return Err(QuestFleetAgentError::new(
            "invalid_ttl",
            "checkin_ttl_ms must be between 10000 and 300000",
        ));
    }
    if !(5_000..profile.checkin_ttl_ms).contains(&profile.checkin_interval_ms) {
        return Err(QuestFleetAgentError::new(
            "invalid_interval",
            "checkin_interval_ms must be at least 5000 and less than the TTL",
        ));
    }
    let endpoint = profile.hub_endpoint.to_ascii_lowercase();
    if !(endpoint.starts_with("http://") || endpoint.starts_with("https://"))
        || endpoint.contains('@')
        || endpoint.contains('#')
    {
        return Err(QuestFleetAgentError::new(
            "invalid_hub_endpoint",
            "Hub endpoint must be an explicit HTTP(S) URL without credentials or fragments",
        ));
    }
    if profile
        .tags
        .iter()
        .any(|(key, value)| key.trim().is_empty() || value.trim().is_empty())
    {
        return Err(QuestFleetAgentError::new(
            "invalid_tag",
            "tag keys and values must not be empty",
        ));
    }
    Ok(())
}

fn validate_snapshot(snapshot: &QuestPlatformSnapshot) -> Result<(), QuestFleetAgentError> {
    if snapshot.battery_percent > 100 {
        return Err(QuestFleetAgentError::new(
            "invalid_battery",
            "battery_percent must be between 0 and 100",
        ));
    }
    if let Some(application) = &snapshot.participating_application {
        if application.package_name.trim().is_empty() {
            return Err(QuestFleetAgentError::new(
                "invalid_application",
                "participating application must name its Android package",
            ));
        }
        if application.lifecycle == ApplicationLifecycle::Unknown
            && application.foreground_state != ForegroundState::Unknown
        {
            return Err(QuestFleetAgentError::new(
                "inconsistent_application",
                "unknown lifecycle may report only unknown foreground state",
            ));
        }
    }
    Ok(())
}

fn manifold_proposal(
    profile: &QuestFleetAgentProfile,
    snapshot: &QuestPlatformSnapshot,
    issued_at_ms: i64,
    expires_at_ms: i64,
) -> Result<ManifoldPeerStatusProposal, QuestFleetAgentError> {
    let peer_id = dotted(&profile.device_id)?;
    let mut capability_ids = vec![dotted("capability.monitoring")?];
    if snapshot
        .participating_application
        .as_ref()
        .is_some_and(|application| application.control_ready)
    {
        capability_ids.push(dotted("capability.app-control")?);
    }
    Ok(ManifoldPeerStatusProposal {
        schema_id: schema(PEER_PROPOSAL_SCHEMA)?,
        proposal_id: dotted(&format!(
            "proposal.fleet-checkin.{}.{}",
            profile.device_id, profile.source_revision
        ))?,
        expected_authority_revision: revision(profile.expected_authority_revision)?,
        proposer_id: dotted("adapter.quest.fleet-agent")?,
        identity: ManifoldPeerIdentity {
            schema_id: schema(PEER_IDENTITY_SCHEMA)?,
            peer_id: peer_id.clone(),
            key_fingerprint: dotted(&profile.key_fingerprint)?,
            trust_domain: dotted(&profile.trust_domain)?,
            roles: vec![ManifoldPeerRole::Observer],
        },
        status: ManifoldPeerStatus {
            schema_id: schema(PEER_STATUS_SCHEMA)?,
            peer_id,
            status_revision: revision(profile.source_revision)?,
            observed_at_ms: u64::try_from(issued_at_ms).map_err(|_| {
                QuestFleetAgentError::new("invalid_source_time", "source time must fit u64")
            })?,
            expires_at_ms: u64::try_from(expires_at_ms).map_err(|_| {
                QuestFleetAgentError::new("invalid_source_time", "expiry must fit u64")
            })?,
            availability: ManifoldPeerAvailability::Ready,
            capability_ids,
        },
        payload_class: ManifoldPeerPayloadClass::LowRateDescriptor,
    })
}

fn device_observation(
    profile: &QuestFleetAgentProfile,
    snapshot: &QuestPlatformSnapshot,
    issued_at_ms: i64,
    expires_at_ms: i64,
) -> DeviceObservation {
    let provenance = FactProvenance {
        owner: FLEET_AGENT_OWNER.to_owned(),
        adapter_id: FLEET_AGENT_ADAPTER_ID.to_owned(),
        observed_at_ms: issued_at_ms,
        fresh_until_ms: expires_at_ms,
    };
    let application = snapshot.participating_application.as_ref().map_or_else(
        || ApplicationObservation {
            package_name: None,
            lifecycle: ApplicationLifecycle::Unknown,
            foreground_state: ForegroundState::Unknown,
            foreground_authority: ForegroundAuthority::PlatformLimited,
            provenance: provenance.clone(),
        },
        |application| ApplicationObservation {
            package_name: Some(application.package_name.clone()),
            lifecycle: application.lifecycle,
            foreground_state: application.foreground_state,
            foreground_authority: ForegroundAuthority::ParticipatingApp,
            provenance: provenance.clone(),
        },
    );
    let foreground_app = (application.foreground_state == ForegroundState::Foreground)
        .then(|| application.package_name.clone())
        .flatten();
    let mut capabilities = BTreeMap::new();
    capabilities.insert(
        "capability.monitoring".to_owned(),
        ready_capability(
            "capability.monitoring",
            profile,
            issued_at_ms,
            expires_at_ms,
        ),
    );
    if snapshot
        .participating_application
        .as_ref()
        .is_some_and(|value| value.control_ready)
    {
        capabilities.insert(
            "capability.app-control".to_owned(),
            ready_capability(
                "capability.app-control",
                profile,
                issued_at_ms,
                expires_at_ms,
            ),
        );
    }
    DeviceObservation {
        schema: "rusty.fleet.device_observation.v1".to_owned(),
        identity: DeviceIdentity {
            device_id: profile.device_id.clone(),
            identity_revision: profile.identity_revision,
            display_name: profile.display_name.clone(),
            model: profile.model.clone(),
            hardware_class: profile.hardware_class.clone(),
            tags: profile.tags.clone(),
            extensions: BTreeMap::new(),
        },
        source_epoch: profile.source_epoch.clone(),
        source_revision: profile.source_revision,
        source_time_ms: issued_at_ms,
        received_time_ms: 0,
        battery_percent: Some(snapshot.battery_percent),
        charging: Some(snapshot.charging),
        foreground_app,
        agent: Some(ApplicationObservation {
            package_name: Some(FLEET_AGENT_PACKAGE.to_owned()),
            lifecycle: snapshot.agent_lifecycle,
            foreground_state: lifecycle_foreground(snapshot.agent_lifecycle),
            foreground_authority: ForegroundAuthority::SelfReport,
            provenance: provenance.clone(),
        }),
        power: Some(PowerObservation {
            battery_percent: snapshot.battery_percent,
            charging: snapshot.charging,
            provenance: provenance.clone(),
        }),
        application: Some(application),
        kiosk_state: snapshot
            .participating_application
            .as_ref()
            .map_or(KioskState::Unknown, |value| value.kiosk_state),
        conditions: Vec::new(),
        capabilities: CapabilitySnapshot {
            capabilities,
            extensions: BTreeMap::new(),
        },
        streams: Vec::new(),
        extensions: BTreeMap::new(),
    }
}

fn ready_capability(
    capability_id: &str,
    profile: &QuestFleetAgentProfile,
    issued_at_ms: i64,
    expires_at_ms: i64,
) -> CapabilityState {
    CapabilityState {
        capability_id: capability_id.to_owned(),
        support: SupportState::Supported,
        enablement: EnablementState::Enabled,
        authorization: AuthorizationState::Authorized,
        reachability: ReachabilityState::Reachable,
        freshness: FreshnessState::Current,
        evidence_revision: profile.source_revision,
        observed_at_ms: issued_at_ms,
        fresh_until_ms: expires_at_ms,
        owner: FLEET_AGENT_OWNER.to_owned(),
        reason: "signed_checkin_ready".to_owned(),
        extensions: BTreeMap::new(),
    }
}

fn lifecycle_foreground(lifecycle: ApplicationLifecycle) -> ForegroundState {
    match lifecycle {
        ApplicationLifecycle::Foreground | ApplicationLifecycle::Visible => {
            ForegroundState::Foreground
        }
        ApplicationLifecycle::Background | ApplicationLifecycle::Stopped => {
            ForegroundState::Background
        }
        ApplicationLifecycle::Unknown => ForegroundState::Unknown,
    }
}

fn dotted(value: &str) -> Result<DottedId, QuestFleetAgentError> {
    DottedId::new(value.to_owned()).map_err(|error| {
        QuestFleetAgentError::new("invalid_dotted_id", format!("{value}: {error}"))
    })
}

fn schema(value: &str) -> Result<SchemaId, QuestFleetAgentError> {
    SchemaId::new(value.to_owned()).map_err(|error| {
        QuestFleetAgentError::new("invalid_schema_id", format!("{value}: {error}"))
    })
}

fn revision(value: u64) -> Result<Revision, QuestFleetAgentError> {
    Revision::new(value).ok_or_else(|| {
        QuestFleetAgentError::new("invalid_revision", "revision must be greater than zero")
    })
}

fn contract_error(failures: Vec<fleet_contracts::ContractViolation>) -> QuestFleetAgentError {
    QuestFleetAgentError::new(
        "fleet_contract_rejected",
        failures
            .into_iter()
            .map(|failure| format!("{}:{}:{}", failure.code, failure.path, failure.message))
            .collect::<Vec<_>>()
            .join("; "),
    )
}

fn lowercase_hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;

    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(output, "{byte:02x}");
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile(enabled: bool) -> QuestFleetAgentProfile {
        let seed = [7_u8; 32];
        let key = derive_key_record("key.quest.synthetic.v1", &seed);
        QuestFleetAgentProfile {
            schema: "rusty.quest.fleet_agent_profile.v1".to_owned(),
            enabled,
            device_id: "quest.synthetic.1".to_owned(),
            display_name: "Quest Synthetic 1".to_owned(),
            model: "Quest 3".to_owned(),
            hardware_class: "standalone_xr".to_owned(),
            identity_revision: 1,
            expected_authority_revision: 1,
            source_revision: 1,
            source_epoch: "agent-epoch-1".to_owned(),
            key_id: key.key_id,
            key_fingerprint: key.key_fingerprint,
            trust_domain: "trust.local".to_owned(),
            checkin_ttl_ms: 60_000,
            checkin_interval_ms: 15_000,
            hub_endpoint: "http://192.0.2.10:8741/fleet/v1/checkins".to_owned(),
            tags: BTreeMap::from([("fixture".to_owned(), "synthetic".to_owned())]),
        }
    }

    fn snapshot() -> QuestPlatformSnapshot {
        QuestPlatformSnapshot {
            battery_percent: 82,
            charging: true,
            agent_lifecycle: ApplicationLifecycle::Background,
            participating_application: None,
        }
    }

    #[test]
    fn disabled_profile_is_inert() {
        let error =
            produce_signed_checkin(&profile(false), &snapshot(), &[7_u8; 32], 2_000_000_000_000)
                .expect_err("disabled profile must not produce a check-in");
        assert_eq!(error.code, "agent_disabled");
    }

    #[test]
    fn committed_default_profile_is_inert() {
        let disabled: QuestFleetAgentProfile = serde_json::from_str(include_str!(
            "../../../fixtures/fleet-agent/fleet-agent.disabled.profile.json"
        ))
        .expect("disabled profile fixture");
        assert!(!disabled.enabled);
        let error = produce_signed_checkin(&disabled, &snapshot(), &[7_u8; 32], 2_000_000_000_000)
            .expect_err("committed profile must remain inert");
        assert_eq!(error.code, "agent_disabled");
    }

    #[test]
    fn platform_limited_snapshot_does_not_invent_foreground_or_adb() {
        let checkin =
            produce_signed_checkin(&profile(true), &snapshot(), &[7_u8; 32], 2_000_000_000_000)
                .expect("valid check-in");
        let observation = checkin.claims.observation;
        let application = observation.application.expect("application fact");
        assert_eq!(
            application.foreground_authority,
            ForegroundAuthority::PlatformLimited
        );
        assert_eq!(application.foreground_state, ForegroundState::Unknown);
        assert_eq!(application.package_name, None);
        assert!(observation
            .capabilities
            .get("capability.monitoring")
            .is_some());
        assert!(observation.capabilities.get("capability.adb").is_none());
        assert!(observation
            .capabilities
            .get("capability.file-manager")
            .is_none());
    }

    #[test]
    fn participating_application_can_add_only_its_control_evidence() {
        let mut source = snapshot();
        source.participating_application = Some(ParticipatingApplicationObservation {
            package_name: "org.example.kiosk".to_owned(),
            lifecycle: ApplicationLifecycle::Foreground,
            foreground_state: ForegroundState::Foreground,
            kiosk_state: KioskState::Active,
            control_ready: true,
        });
        let checkin =
            produce_signed_checkin(&profile(true), &source, &[7_u8; 32], 2_000_000_000_000)
                .expect("valid check-in");
        let observation = checkin.claims.observation;
        assert_eq!(
            observation.foreground_app.as_deref(),
            Some("org.example.kiosk")
        );
        assert_eq!(observation.kiosk_state, KioskState::Active);
        assert!(observation
            .capabilities
            .get("capability.app-control")
            .is_some_and(CapabilityState::is_ready));
    }

    #[test]
    fn wrong_signing_seed_fails_before_publication() {
        let error =
            produce_signed_checkin(&profile(true), &snapshot(), &[8_u8; 32], 2_000_000_000_000)
                .expect_err("wrong seed");
        assert_eq!(error.code, "signing_identity_mismatch");
    }

    #[test]
    fn fleet_golden_vector_stays_cross_repository_stable() {
        let claims: FleetCheckInClaims = serde_json::from_str(include_str!(
            "../../../fixtures/fleet-agent/checkin-claims-golden.valid.json"
        ))
        .expect("golden claims fixture");
        claims.validate().expect("valid Fleet claims");
        let signing_bytes = claims.signing_bytes().expect("canonical signing bytes");
        assert_eq!(
            lowercase_hex(&Sha256::digest(&signing_bytes)),
            "a9dd28a3681ccd242fee648a7010b85a69df38147f487e8c4e7e2b08116b8432"
        );
        let signing_key = SigningKey::from_bytes(&[7_u8; 32]);
        assert_eq!(
            lowercase_hex(&signing_key.sign(&signing_bytes).to_bytes()),
            "39871cc49151257e753bb7c83dfaf4e507c87f1b1b3381ce1457eef99d43e25752aaa58c695a747eca416cddd16d5bb7e890dd61b24cf8ffb489260bfbcdc602"
        );
    }
}
