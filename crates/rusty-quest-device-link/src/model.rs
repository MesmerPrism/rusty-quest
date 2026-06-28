//! Device-link report data model.

use serde::{Deserialize, Serialize};

/// Quest device-link report schema id.
pub const DEVICE_LINK_SCHEMA: &str = "rusty.quest.device_link.v1";

/// Passing status token.
pub const STATUS_PASS: &str = "pass";

/// Warning status token.
pub const STATUS_WARN: &str = "warn";

/// Failing status token.
pub const STATUS_FAIL: &str = "fail";

/// Skipped status token.
pub const STATUS_SKIPPED: &str = "skipped";

/// Authorized ADB device state token.
pub const ADB_STATE_DEVICE: &str = "device";

/// USB ADB transport token.
pub const TRANSPORT_ADB_USB: &str = "adb_usb";

/// Wi-Fi ADB transport token.
pub const TRANSPORT_ADB_WIFI: &str = "adb_wifi";

/// ADB forward tunnel transport token.
pub const TRANSPORT_ADB_FORWARD: &str = "adb_forward";

/// Manifold WebSocket transport token.
pub const TRANSPORT_MANIFOLD_WEBSOCKET: &str = "manifold_websocket";

/// LSL transport token.
pub const TRANSPORT_LSL: &str = "lsl";

/// UDP datagram transport token.
pub const TRANSPORT_UDP: &str = "udp";

/// Binary TCP transport token.
pub const TRANSPORT_TCP_BINARY: &str = "tcp_binary";

/// App-private JSON recovery transport token.
pub const TRANSPORT_APP_PRIVATE_JSON: &str = "app_private_json";

/// WebSocket protocol token.
pub const PROTOCOL_WEBSOCKET: &str = "websocket";

/// Manifold event route path used by current Quest broker adapters.
pub const MANIFOLD_EVENTS_PATH: &str = "/manifold/v1/events";

/// JSON event payload plane token.
pub const PAYLOAD_PLANE_JSON_EVENT: &str = "json_event";

/// Binary media payload plane token.
pub const PAYLOAD_PLANE_BINARY_MEDIA: &str = "binary_media";

/// LSL sample payload plane token.
pub const PAYLOAD_PLANE_LSL_SAMPLE: &str = "lsl_sample";

/// UDP datagram payload plane token.
pub const PAYLOAD_PLANE_UDP_DATAGRAM: &str = "udp_datagram";

/// Low-rate command/control rate class.
pub const RATE_CLASS_CONTROL: &str = "control";

/// Low-rate runtime telemetry rate class.
pub const RATE_CLASS_LOW_RATE: &str = "low_rate";

/// Sample-clocked sensor rate class.
pub const RATE_CLASS_SAMPLE_CLOCKED: &str = "sample_clocked";

/// High-rate frame/media rate class.
pub const RATE_CLASS_HIGH_RATE: &str = "high_rate";

/// Report describing one observed host-to-Quest device link.
///
/// The report is intentionally data-only. It records device identity, ADB
/// tunnels, broker readiness, runtime subscriber state, command outcomes, and
/// stream capability descriptors without opening sockets or owning Manifold
/// command authority.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceLinkReport {
    /// Schema id.
    pub schema: String,
    /// Stable link or session id.
    pub link_id: String,
    /// Observation timestamp in Unix epoch milliseconds.
    pub observed_at_ms: u64,
    /// Overall status: `pass`, `warn`, `fail`, or `skipped`.
    pub status: String,
    /// Quest device identity observed through the selected transport.
    pub device_identity: QuestDeviceIdentity,
    /// Host tools required by this link.
    #[serde(default)]
    pub host_tools: Vec<HostToolState>,
    /// Host/device tunnels, including ADB forwards.
    #[serde(default)]
    pub tunnels: Vec<TunnelState>,
    /// Broker endpoints exposed through the link.
    #[serde(default)]
    pub broker_endpoints: Vec<BrokerEndpointState>,
    /// Runtime subscribers observed behind the broker.
    #[serde(default)]
    pub runtime_subscribers: Vec<RuntimeSubscriberState>,
    /// Command-result reports associated with this link.
    #[serde(default)]
    pub command_results: Vec<CommandResultReport>,
    /// Stream capability descriptors available through this device link.
    #[serde(default)]
    pub stream_capabilities: Vec<StreamCapabilityDescriptor>,
    /// Issues raised while building or validating the link report.
    #[serde(default)]
    pub issues: Vec<DeviceLinkIssue>,
}

/// Quest identity as observed by host tooling.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuestDeviceIdentity {
    /// ADB serial or stable redacted operator id.
    pub serial: String,
    /// Transport kind, for example `adb_usb` or `adb_wifi`.
    pub transport_kind: String,
    /// ADB state such as `device`, `offline`, or `unauthorized`.
    pub adb_state: String,
    /// Product model string.
    pub model: String,
    /// Android product name, when known.
    #[serde(default)]
    pub product: Option<String>,
    /// Manufacturer string, when known.
    #[serde(default)]
    pub manufacturer: Option<String>,
    /// Android SDK level, when known.
    #[serde(default)]
    pub android_sdk: Option<u32>,
}

/// Host-side tool state used by a device link.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HostToolState {
    /// Stable tool id, for example `tool.adb`.
    pub tool_id: String,
    /// Tool kind such as `adb`, `python`, or `hostessctl`.
    pub kind: String,
    /// Tool status.
    pub status: String,
    /// Whether this tool is required for the link.
    pub required: bool,
    /// Resolved executable or diagnostic path.
    #[serde(default)]
    pub path: Option<String>,
    /// Version or diagnostic text.
    #[serde(default)]
    pub version: Option<String>,
}

/// Host/device tunnel state.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TunnelState {
    /// Stable tunnel id.
    pub tunnel_id: String,
    /// Transport kind, for example `adb_forward`.
    pub transport_kind: String,
    /// Tunnel status.
    pub status: String,
    /// Whether this tunnel is required by the session.
    pub required: bool,
    /// Host/interface name.
    pub host: String,
    /// Host-side local port, when a local port is bound.
    #[serde(default)]
    pub local_port: Option<u16>,
    /// Device-side host/interface name, when meaningful.
    #[serde(default)]
    pub device_host: Option<String>,
    /// Device-side port, when a remote port is bound.
    #[serde(default)]
    pub device_port: Option<u16>,
    /// Protocol path routed through the tunnel, when applicable.
    #[serde(default)]
    pub path: Option<String>,
}

/// Broker endpoint readiness through a device link.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BrokerEndpointState {
    /// Stable endpoint id.
    pub endpoint_id: String,
    /// Endpoint status.
    pub status: String,
    /// Protocol token, currently `websocket` for the Manifold event route.
    pub protocol: String,
    /// Broker authority label.
    pub authority: String,
    /// Host/interface used by the observer.
    pub host: String,
    /// Port used by the observer.
    pub port: u16,
    /// Route path.
    pub path: String,
    /// Tunnel id used to reach this endpoint, when any.
    #[serde(default)]
    pub routed_through_tunnel_id: Option<String>,
    /// Accepted command envelope schema.
    pub command_envelope_schema: String,
    /// Whether this endpoint allows high-rate payload bytes.
    pub high_rate_payload_allowed: bool,
}

/// Runtime subscriber state behind the broker.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeSubscriberState {
    /// Stable subscriber id.
    pub subscriber_id: String,
    /// Runtime app or module id.
    pub runtime_app_id: String,
    /// Command request stream id.
    pub request_stream_id: String,
    /// Runtime receipt stream id.
    pub receipt_stream_id: String,
    /// Subscriber status: `connected`, `missing`, `unknown`, or `skipped`.
    pub status: String,
    /// Whether command success requires this receipt.
    pub receipt_required: bool,
    /// Last broker dispatch delivery count, when observed.
    #[serde(default)]
    pub last_dispatch_delivered_count: Option<u32>,
}

/// Command-result report associated with a link.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CommandResultReport {
    /// Stable result id.
    pub result_id: String,
    /// Route id, usually a Manifold bridge route id.
    pub route_id: String,
    /// Request id.
    pub request_id: String,
    /// Command id.
    pub command: String,
    /// Transport kind used by the command.
    pub transport_kind: String,
    /// Result status.
    pub status: String,
    /// Required stages for success.
    pub required_stages: Vec<String>,
    /// Observed stage states.
    pub observed_stages: Vec<CommandStageObservation>,
    /// Runtime receipt stream, when required.
    #[serde(default)]
    pub runtime_receipt_stream: Option<String>,
    /// Broker runtime dispatch delivery count, when observed.
    #[serde(default)]
    pub runtime_dispatch_delivered_count: Option<u32>,
    /// Whether runtime evidence says the command took effect.
    pub applied: bool,
}

/// One observed command stage.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CommandStageObservation {
    /// Stage id such as `sent`, `transport_ok`, or `applied`.
    pub stage: String,
    /// Stage status.
    pub status: String,
    /// Evidence artifact refs.
    #[serde(default)]
    pub evidence_refs: Vec<String>,
    /// Issue codes.
    #[serde(default)]
    pub issue_codes: Vec<String>,
}

/// Descriptor for a stream type available through this link.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StreamCapabilityDescriptor {
    /// Stable capability id.
    pub capability_id: String,
    /// Stream id or stream family id.
    pub stream_id: String,
    /// Semantic family such as `command`, `biosignal`, `pose`, or `media`.
    pub semantic_family: String,
    /// Transport kind.
    pub transport_kind: String,
    /// Payload plane.
    pub payload_plane: String,
    /// Rate class.
    pub rate_class: String,
    /// Reliability/ordering policy.
    pub reliability: String,
    /// Direction from the local observer point of view.
    pub direction: String,
    /// Clock/timestamp policy.
    pub clock_policy: String,
    /// Queue/drop policy summary.
    pub queue_policy: String,
    /// Maximum nominal rate in Hertz, when bounded.
    #[serde(default)]
    pub max_rate_hz: Option<u32>,
    /// True only when high-rate payload bytes are intentionally JSON.
    ///
    /// This must remain false for high-rate and sample-clocked streams.
    pub high_rate_json_payload: bool,
    /// Intended uses.
    #[serde(default)]
    pub recommended_for: Vec<String>,
    /// Explicit non-uses.
    #[serde(default)]
    pub not_for: Vec<String>,
}

/// Device-link issue row.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceLinkIssue {
    /// Stable issue code.
    pub issue_code: String,
    /// Severity: `info`, `warning`, or `error`.
    pub severity: String,
    /// Human-readable message.
    pub message: String,
}

/// Validation failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationError {
    /// Human-readable message.
    pub message: String,
}

impl ValidationError {
    pub(crate) fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl std::fmt::Display for ValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for ValidationError {}
