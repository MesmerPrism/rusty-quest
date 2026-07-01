//! Wi-Fi Direct lifecycle evidence contracts.
//!
//! The artifact modeled here is source-owned evidence for QCL-040/QCL-041.
//! It is still data-only: this crate does not discover peers, create Wi-Fi
//! Direct groups, open sockets, or run ADB.

use serde::{Deserialize, Serialize};

use crate::model::{ValidationError, STATUS_PASS};
use crate::validation::validate_required_text;

/// Quest Wi-Fi Direct lifecycle source artifact schema id.
pub const WIFI_DIRECT_LIFECYCLE_SCHEMA: &str = "rusty.quest.connectivity_wifi_direct_lifecycle.v1";

/// QCL-040 probe id for Quest to Android-phone Wi-Fi Direct.
pub const WIFI_DIRECT_PROBE_QCL_040: &str = "QCL-040";

/// QCL-041 probe id for Quest to Windows Wi-Fi Direct.
pub const WIFI_DIRECT_PROBE_QCL_041: &str = "QCL-041";

/// Android phone peer class token.
pub const WIFI_DIRECT_PEER_ANDROID_PHONE: &str = "android_phone";

/// Windows peer class token.
pub const WIFI_DIRECT_PEER_WINDOWS: &str = "windows";

/// Agent Board manager token required for live headset evidence.
pub const WIFI_DIRECT_AGENT_BOARD_MANAGER: &str = "Agent Board";

const QUEST_LEASE_RESOURCE_PREFIX: &str = "quest:";
const LIVE_EVIDENCE_TIERS: &[&str] = &[
    "quest_runtime",
    "hostess_harness",
    "product_harness",
    "product_owned",
];

/// Source artifact for a complete Wi-Fi Direct topology lifecycle run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecycleArtifact {
    /// Schema id. Serialized as `$schema` for JSON fixture compatibility.
    #[serde(rename = "$schema", alias = "schema")]
    pub schema: String,
    /// Integer schema version.
    pub schema_version: u32,
    /// Probe id, currently `QCL-040` or `QCL-041`.
    pub probe_id: String,
    /// Peer class for the run.
    pub peer_class: String,
    /// Evidence tier. Live promotion requires a live tier.
    pub evidence_tier: String,
    /// Capture kind from the source harness.
    pub capture_kind: String,
    /// True only when the artifact came from a live run.
    pub live_evidence: bool,
    /// Observation timestamp in UTC.
    pub observed_at_utc: String,
    /// Stable source run id.
    pub run_id: String,
    /// Harness identity and ownership.
    pub harness: WifiDirectLifecycleHarness,
    /// Topology identity for the Wi-Fi Direct route.
    pub topology: WifiDirectLifecycleTopology,
    /// Quest device identity used by the live run.
    pub device: WifiDirectLifecycleDevice,
    /// Peer host identity used by the run.
    pub host: WifiDirectLifecycleHost,
    /// Agent Board lease evidence.
    pub lease: WifiDirectLifecycleLease,
    /// Required lifecycle phases.
    pub lifecycle: WifiDirectLifecyclePhases,
    /// Optional timing and count measurements.
    #[serde(default)]
    pub measurements: WifiDirectLifecycleMeasurements,
}

/// Harness identity for a Wi-Fi Direct lifecycle run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecycleHarness {
    /// Stable harness id.
    pub harness_id: String,
    /// Harness authority owner.
    pub owner: String,
    /// Harness route label.
    #[serde(default)]
    pub route: Option<String>,
    /// Whether Hostess directly executed this harness.
    #[serde(default)]
    pub hostess_runs_harness: bool,
    /// Whether this harness writes the live source artifact.
    #[serde(default)]
    pub writes_live_source_artifact: bool,
}

/// Topology identity for Wi-Fi Direct lifecycle evidence.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecycleTopology {
    /// Topology owner token.
    pub owner: String,
    /// Network provider token.
    pub network_provider: String,
    /// Endpoint direction token.
    pub endpoint_direction: String,
    /// Peer class token.
    pub peer_class: String,
}

/// Quest device identity recorded by the lifecycle harness.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecycleDevice {
    /// Quest model label.
    pub model: String,
    /// Quest serial used for lease matching.
    pub serial: String,
    /// Alternate ADB serial, when recorded separately.
    #[serde(default)]
    pub adb_serial: Option<String>,
    /// Quest Wi-Fi Direct role label.
    pub wifi_direct_role: String,
}

/// Peer host identity recorded by the lifecycle harness.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecycleHost {
    /// Peer operating-system label.
    pub os: String,
    /// Toolchain profile that produced the artifact.
    pub toolchain_profile: String,
}

/// Agent Board quest lease evidence.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecycleLease {
    /// Lease manager token.
    pub manager: String,
    /// Lease resource, for example `quest:TESTQUESTSERIAL`.
    pub resource: String,
    /// Concrete lease id.
    pub lease_id: String,
    /// True when the quest lease was reserved before live steps.
    pub reserved_before_live_steps: bool,
    /// True when the quest lease was released after live steps and cleanup.
    pub released_after_live_steps: bool,
    /// True only if a disruptive ADB server lifecycle lease was used.
    #[serde(default)]
    pub adb_server_lifecycle_lease_used: bool,
    /// Reserve command used for operator traceability.
    #[serde(default)]
    pub reserve_command: Option<String>,
    /// Release command used for operator traceability.
    #[serde(default)]
    pub release_command: Option<String>,
    /// ADB server lifecycle policy recorded by the source harness.
    #[serde(default)]
    pub adb_server_lifecycle_policy: Option<String>,
}

/// Required Wi-Fi Direct lifecycle phases.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecyclePhases {
    /// Quest Wi-Fi Direct feature phase.
    pub feature: WifiDirectLifecyclePhase,
    /// Windows Wi-Fi Direct API phase for QCL-041.
    #[serde(default)]
    pub windows_wifi_direct_api: Option<WifiDirectLifecyclePhase>,
    /// Android-phone peer phase for QCL-040.
    #[serde(default)]
    pub android_phone_peer: Option<WifiDirectLifecyclePhase>,
    /// Runtime permission phase.
    pub permission_state: WifiDirectLifecyclePhase,
    /// Peer discovery phase.
    pub peer_discovery: WifiDirectLifecyclePhase,
    /// Group formation phase.
    pub group_formation: WifiDirectLifecyclePhase,
    /// Bounded TCP socket exchange phase.
    pub socket_exchange: WifiDirectLifecyclePhase,
    /// Group cleanup phase.
    pub cleanup: WifiDirectLifecyclePhase,
}

/// One lifecycle phase row.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecyclePhase {
    /// Phase status. Promotion requires `pass`.
    pub status: String,
    /// Evidence summary.
    #[serde(default)]
    pub evidence: Option<String>,
    /// Whether the source harness treated this phase as required.
    #[serde(default)]
    pub required: Option<bool>,
    /// Peer count observed during discovery.
    #[serde(default)]
    pub peer_count: Option<u32>,
    /// Local Wi-Fi Direct group role.
    #[serde(default)]
    pub local_role: Option<String>,
    /// Peer Wi-Fi Direct group role.
    #[serde(default)]
    pub peer_role: Option<String>,
    /// Socket-exchange protocol.
    #[serde(default)]
    pub protocol: Option<String>,
    /// Socket-exchange payload class.
    #[serde(default)]
    pub payload_class: Option<String>,
    /// Whether the socket probe was bounded.
    #[serde(default)]
    pub bounded: Option<bool>,
    /// Socket messages sent.
    #[serde(default)]
    pub messages_sent: Option<u32>,
    /// Socket messages received.
    #[serde(default)]
    pub messages_received: Option<u32>,
    /// Whether cleanup completed.
    #[serde(default)]
    pub completed: Option<bool>,
}

/// Optional Wi-Fi Direct lifecycle measurements.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct WifiDirectLifecycleMeasurements {
    /// TCP connect duration in milliseconds.
    #[serde(default)]
    pub tcp_connect_ms: Option<u64>,
    /// Peer count observed during Wi-Fi Direct discovery.
    #[serde(default)]
    pub wifi_direct_peer_count: Option<u32>,
    /// Group formation duration in milliseconds.
    #[serde(default)]
    pub group_formation_ms: Option<u64>,
}

/// Validate a Wi-Fi Direct lifecycle source artifact.
pub fn validate_wifi_direct_lifecycle_artifact(
    artifact: &WifiDirectLifecycleArtifact,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();

    validate_required_text(
        "wifi_direct_lifecycle.schema",
        &artifact.schema,
        &mut errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.probe_id",
        &artifact.probe_id,
        &mut errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.peer_class",
        &artifact.peer_class,
        &mut errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.evidence_tier",
        &artifact.evidence_tier,
        &mut errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.capture_kind",
        &artifact.capture_kind,
        &mut errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.observed_at_utc",
        &artifact.observed_at_utc,
        &mut errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.run_id",
        &artifact.run_id,
        &mut errors,
    );

    if artifact.schema != WIFI_DIRECT_LIFECYCLE_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported Wi-Fi Direct lifecycle schema {}",
            artifact.schema
        )));
    }
    if artifact.schema_version == 0 {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle schema_version must be nonzero",
        ));
    }
    let expected_peer = expected_peer_class(&artifact.probe_id, &mut errors);
    if let Some(expected) = expected_peer {
        if artifact.peer_class != expected {
            errors.push(ValidationError::new(format!(
                "Wi-Fi Direct lifecycle {} requires peer_class {}",
                artifact.probe_id, expected
            )));
        }
    }
    if !LIVE_EVIDENCE_TIERS.contains(&artifact.evidence_tier.as_str()) {
        errors.push(ValidationError::new(format!(
            "Wi-Fi Direct lifecycle evidence_tier {} is not a live tier",
            artifact.evidence_tier
        )));
    }
    if !artifact.live_evidence {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle requires live_evidence=true",
        ));
    }
    if id_is_placeholder(&artifact.run_id) {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle requires a concrete run_id",
        ));
    }

    validate_harness(&artifact.harness, &mut errors);
    validate_topology(&artifact.topology, &artifact.peer_class, &mut errors);
    validate_device(&artifact.device, &mut errors);
    validate_host(
        &artifact.host,
        expected_peer.unwrap_or(&artifact.peer_class),
        &mut errors,
    );
    validate_lease(&artifact.lease, &artifact.device, &mut errors);
    validate_lifecycle_phases(&artifact.lifecycle, &artifact.probe_id, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_harness(harness: &WifiDirectLifecycleHarness, errors: &mut Vec<ValidationError>) {
    validate_required_text(
        "wifi_direct_lifecycle.harness.harness_id",
        &harness.harness_id,
        errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.harness.owner",
        &harness.owner,
        errors,
    );
    if id_is_placeholder(&harness.harness_id) {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle requires a concrete harness_id",
        ));
    }
    if text_is_placeholder(&harness.owner) {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle requires a concrete harness owner",
        ));
    }
    if !harness.writes_live_source_artifact {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle harness must write the live source artifact",
        ));
    }
}

fn validate_topology(
    topology: &WifiDirectLifecycleTopology,
    artifact_peer_class: &str,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text(
        "wifi_direct_lifecycle.topology.owner",
        &topology.owner,
        errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.topology.network_provider",
        &topology.network_provider,
        errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.topology.endpoint_direction",
        &topology.endpoint_direction,
        errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.topology.peer_class",
        &topology.peer_class,
        errors,
    );
    if topology.owner != "wifi_direct" {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle topology.owner must be wifi_direct",
        ));
    }
    if topology.network_provider != "wifi_direct" {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle topology.network_provider must be wifi_direct",
        ));
    }
    if topology.endpoint_direction != "peer_to_peer_group" {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle topology.endpoint_direction must be peer_to_peer_group",
        ));
    }
    if topology.peer_class != artifact_peer_class {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle topology.peer_class must match artifact peer_class",
        ));
    }
}

fn validate_device(device: &WifiDirectLifecycleDevice, errors: &mut Vec<ValidationError>) {
    validate_required_text("wifi_direct_lifecycle.device.model", &device.model, errors);
    validate_required_text(
        "wifi_direct_lifecycle.device.serial",
        &device.serial,
        errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.device.wifi_direct_role",
        &device.wifi_direct_role,
        errors,
    );
    if serial_is_placeholder(&device.serial) {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle requires a concrete device serial",
        ));
    }
}

fn validate_host(
    host: &WifiDirectLifecycleHost,
    expected_peer_class: &str,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text("wifi_direct_lifecycle.host.os", &host.os, errors);
    validate_required_text(
        "wifi_direct_lifecycle.host.toolchain_profile",
        &host.toolchain_profile,
        errors,
    );
    let expected_os = if expected_peer_class == WIFI_DIRECT_PEER_WINDOWS {
        WIFI_DIRECT_PEER_WINDOWS
    } else {
        "android_phone_peer"
    };
    if host.os != expected_os
        && !(expected_peer_class == WIFI_DIRECT_PEER_ANDROID_PHONE && host.os == "android_phone")
    {
        errors.push(ValidationError::new(format!(
            "Wi-Fi Direct lifecycle host.os {} does not match peer class {}",
            host.os, expected_peer_class
        )));
    }
}

fn validate_lease(
    lease: &WifiDirectLifecycleLease,
    device: &WifiDirectLifecycleDevice,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text(
        "wifi_direct_lifecycle.lease.manager",
        &lease.manager,
        errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.lease.resource",
        &lease.resource,
        errors,
    );
    validate_required_text(
        "wifi_direct_lifecycle.lease.lease_id",
        &lease.lease_id,
        errors,
    );
    if lease.manager != WIFI_DIRECT_AGENT_BOARD_MANAGER {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle lease.manager must be Agent Board",
        ));
    }
    if !lease.resource.starts_with(QUEST_LEASE_RESOURCE_PREFIX)
        || serial_is_placeholder(quest_serial_from_resource(&lease.resource))
    {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle lease.resource must be quest:<serial>",
        ));
    }
    if id_is_placeholder(&lease.lease_id) || lease.lease_id == "LEASE_ID_FROM_RESERVE_OUTPUT" {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle requires a concrete Agent Board lease_id",
        ));
    }
    if !lease.reserved_before_live_steps {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle quest lease must be reserved before live steps",
        ));
    }
    if !lease.released_after_live_steps {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle quest lease must be released after live steps",
        ));
    }

    let lease_serial = quest_serial_from_resource(&lease.resource);
    let device_serial = if serial_is_placeholder(&device.serial) {
        device.adb_serial.as_deref().unwrap_or("")
    } else {
        &device.serial
    };
    if !lease_serial.is_empty() && !device_serial.is_empty() && lease_serial != device_serial {
        errors.push(ValidationError::new(format!(
            "Wi-Fi Direct lifecycle lease resource serial {lease_serial} must match device serial {device_serial}",
        )));
    }
}

fn validate_lifecycle_phases(
    lifecycle: &WifiDirectLifecyclePhases,
    probe_id: &str,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_phase("feature", &lifecycle.feature, errors);
    let peer_phase = if probe_id == WIFI_DIRECT_PROBE_QCL_041 {
        lifecycle.windows_wifi_direct_api.as_ref()
    } else {
        lifecycle.android_phone_peer.as_ref()
    };
    match peer_phase {
        Some(phase) => validate_required_phase(peer_phase_name(probe_id), phase, errors),
        None => errors.push(ValidationError::new(format!(
            "Wi-Fi Direct lifecycle missing required {} phase",
            peer_phase_name(probe_id)
        ))),
    }
    validate_required_phase("permission_state", &lifecycle.permission_state, errors);
    validate_peer_discovery(&lifecycle.peer_discovery, errors);
    validate_group_formation(&lifecycle.group_formation, errors);
    validate_socket_exchange(&lifecycle.socket_exchange, errors);
    validate_cleanup(&lifecycle.cleanup, errors);
}

fn validate_required_phase(
    label: &str,
    phase: &WifiDirectLifecyclePhase,
    errors: &mut Vec<ValidationError>,
) {
    validate_required_text(
        &format!("wifi_direct_lifecycle.lifecycle.{label}.status"),
        &phase.status,
        errors,
    );
    if phase.status != STATUS_PASS {
        errors.push(ValidationError::new(format!(
            "Wi-Fi Direct lifecycle phase {label} must have status=pass"
        )));
    }
    if phase.required == Some(false) {
        errors.push(ValidationError::new(format!(
            "Wi-Fi Direct lifecycle phase {label} must be required"
        )));
    }
}

fn validate_peer_discovery(phase: &WifiDirectLifecyclePhase, errors: &mut Vec<ValidationError>) {
    validate_required_phase("peer_discovery", phase, errors);
    if phase.peer_count.unwrap_or(0) == 0 {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle peer_discovery requires peer_count > 0",
        ));
    }
}

fn validate_group_formation(phase: &WifiDirectLifecyclePhase, errors: &mut Vec<ValidationError>) {
    validate_required_phase("group_formation", phase, errors);
    if text_is_placeholder(phase.local_role.as_deref().unwrap_or(""))
        || text_is_placeholder(phase.peer_role.as_deref().unwrap_or(""))
    {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle group_formation requires local_role and peer_role",
        ));
    }
}

fn validate_socket_exchange(phase: &WifiDirectLifecyclePhase, errors: &mut Vec<ValidationError>) {
    validate_required_phase("socket_exchange", phase, errors);
    let protocol = phase.protocol.as_deref().unwrap_or("").to_ascii_lowercase();
    let payload_class = phase.payload_class.as_deref().unwrap_or("");
    let bounded = phase.bounded == Some(true) || payload_class == "bounded_tcp_probe";
    let tcp = matches!(protocol.as_str(), "tcp" | "tcp_echo" | "bounded_tcp_probe");
    if !bounded || !tcp {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle socket_exchange requires a bounded TCP probe",
        ));
    }
    if phase.messages_sent.unwrap_or(0) == 0 || phase.messages_received.unwrap_or(0) == 0 {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle socket_exchange requires nonzero messages_sent and messages_received",
        ));
    }
}

fn validate_cleanup(phase: &WifiDirectLifecyclePhase, errors: &mut Vec<ValidationError>) {
    validate_required_phase("cleanup", phase, errors);
    if phase.completed != Some(true) {
        errors.push(ValidationError::new(
            "Wi-Fi Direct lifecycle cleanup requires completed=true",
        ));
    }
}

fn expected_peer_class<'a>(probe_id: &str, errors: &mut Vec<ValidationError>) -> Option<&'a str> {
    match probe_id {
        WIFI_DIRECT_PROBE_QCL_040 => Some(WIFI_DIRECT_PEER_ANDROID_PHONE),
        WIFI_DIRECT_PROBE_QCL_041 => Some(WIFI_DIRECT_PEER_WINDOWS),
        _ => {
            errors.push(ValidationError::new(format!(
                "unsupported Wi-Fi Direct lifecycle probe_id {probe_id}"
            )));
            None
        }
    }
}

fn peer_phase_name(probe_id: &str) -> &'static str {
    if probe_id == WIFI_DIRECT_PROBE_QCL_041 {
        "windows_wifi_direct_api"
    } else {
        "android_phone_peer"
    }
}

fn quest_serial_from_resource(resource: &str) -> &str {
    resource
        .strip_prefix(QUEST_LEASE_RESOURCE_PREFIX)
        .unwrap_or("")
        .trim()
}

fn id_is_placeholder(value: &str) -> bool {
    let value = value.trim();
    value.is_empty() || value.contains('<')
}

fn serial_is_placeholder(value: &str) -> bool {
    let value = value.trim();
    value.is_empty() || value.contains('<') || value == "QUEST_SERIAL_FROM_ADB_DEVICES"
}

fn text_is_placeholder(value: &str) -> bool {
    let value = value.trim();
    value.is_empty() || value.contains('<')
}

#[cfg(test)]
mod tests {
    use super::{validate_wifi_direct_lifecycle_artifact, WifiDirectLifecycleArtifact};

    fn parse_fixture(text: &str) -> WifiDirectLifecycleArtifact {
        serde_json::from_str(text).expect("Wi-Fi Direct lifecycle fixture parses")
    }

    fn error_messages(errors: Vec<crate::model::ValidationError>) -> Vec<String> {
        errors.into_iter().map(|error| error.message).collect()
    }

    #[test]
    fn qcl041_windows_lifecycle_fixture_validates() {
        let artifact = parse_fixture(include_str!(
            "../../../fixtures/device-link/wifi-direct-lifecycle-qcl041-windows.pass.json"
        ));

        validate_wifi_direct_lifecycle_artifact(&artifact)
            .expect("QCL-041 Wi-Fi Direct lifecycle fixture validates");
    }

    #[test]
    fn qcl040_android_phone_lifecycle_fixture_validates() {
        let artifact = parse_fixture(include_str!(
            "../../../fixtures/device-link/wifi-direct-lifecycle-qcl040-android-phone.pass.json"
        ));

        validate_wifi_direct_lifecycle_artifact(&artifact)
            .expect("QCL-040 Wi-Fi Direct lifecycle fixture validates");
    }

    #[test]
    fn missing_source_identity_is_rejected() {
        let artifact = parse_fixture(include_str!(
            "../../../fixtures/damaged/wifi-direct-lifecycle-missing-source-identity.json"
        ));

        let messages = error_messages(
            validate_wifi_direct_lifecycle_artifact(&artifact)
                .expect_err("missing source identity must be rejected"),
        );
        assert!(messages
            .iter()
            .any(|message| message.contains("requires live_evidence=true")));
        assert!(messages
            .iter()
            .any(|message| message.contains("concrete run_id")));
        assert!(messages
            .iter()
            .any(|message| message.contains("concrete harness_id")));
    }

    #[test]
    fn lease_serial_mismatch_is_rejected() {
        let artifact = parse_fixture(include_str!(
            "../../../fixtures/damaged/wifi-direct-lifecycle-lease-serial-mismatch.json"
        ));

        let messages = error_messages(
            validate_wifi_direct_lifecycle_artifact(&artifact)
                .expect_err("lease serial mismatch must be rejected"),
        );
        assert!(messages
            .iter()
            .any(|message| message.contains("must match device serial OTHERQUESTSERIAL")));
    }

    #[test]
    fn missing_socket_counters_are_rejected() {
        let artifact = parse_fixture(include_str!(
            "../../../fixtures/damaged/wifi-direct-lifecycle-missing-socket-counters.json"
        ));

        let messages = error_messages(
            validate_wifi_direct_lifecycle_artifact(&artifact)
                .expect_err("missing socket counters must be rejected"),
        );
        assert!(messages
            .iter()
            .any(|message| message.contains("nonzero messages_sent and messages_received")));
    }

    #[test]
    fn cleanup_not_completed_is_rejected() {
        let artifact = parse_fixture(include_str!(
            "../../../fixtures/damaged/wifi-direct-lifecycle-cleanup-not-completed.json"
        ));

        let messages = error_messages(
            validate_wifi_direct_lifecycle_artifact(&artifact)
                .expect_err("cleanup failure must be rejected"),
        );
        assert!(messages
            .iter()
            .any(|message| message.contains("cleanup requires completed=true")));
    }
}
